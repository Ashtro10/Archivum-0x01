"""Local Testing Agent (B-025 / B-030, ADR-0024 / ADR-0029).

``test_proposal`` copies a subset, overlays the proposal, runs unittest via
ProcessRunner, and returns an honest TestReport. ``run_suite`` copies a
subset with no overlay and runs the same unittest command. One execution
attempt. Not a Tool. No subprocess import.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

from astra.coding.contracts import ChangeProposal
from astra.core.contracts import AgentRequest, AgentResult
from astra.logging import get_logger
from astra.security.agent import MAX_PROCESS_TIMEOUT_SECONDS
from astra.security.contracts import ProcessSpec, Verdict
from astra.testing.contracts import TestReport, TestingAgent
from astra.testing.report import parse_unittest_output
from astra.testing.runner import ProcessStatus

_LOGGER = get_logger(__name__)

_DISCOVER_TAIL = ("-B", "-s", "-m", "unittest", "discover", "-s", "tests", "-t", ".")


class LocalTestingAgent(TestingAgent):
    """Isolated unittest against a copied-subset session. Not an OS sandbox."""

    name = "testing"

    def __init__(
        self,
        *,
        subset: Any,
        runner: Any,
        timeout_seconds: float = MAX_PROCESS_TIMEOUT_SECONDS,
    ) -> None:
        self._subset = subset
        self._runner = runner
        self._timeout_seconds = float(timeout_seconds)

    def run_suite(self, path: str | None = None) -> TestReport:
        """Copy unchanged snapshot, run unittest, discard. Never overlays."""
        argv = (sys.executable, *_DISCOVER_TAIL)
        command = " ".join(argv)
        if path is not None and str(path).strip() not in ("", "tests"):
            return _incomplete(
                command,
                "run_suite subset path not supported; canonical tests/ discover only",
            )
        session_id = ""
        try:
            copied = self._subset.copy()
            if not getattr(copied, "success", False):
                return _incomplete(
                    command,
                    getattr(copied, "reason", "subset copy failed") or "subset copy failed",
                )
            session_id = str(getattr(copied, "session_id", "") or "")
            session = getattr(copied, "subset_root", None)
            return self._run_unittest(command, argv, session_id, session)
        except Exception as exc:  # noqa: BLE001
            _LOGGER.debug("run_suite failed: %s", exc)
            return _incomplete(command, f"run_suite failed: {exc}")
        finally:
            if session_id:
                try:
                    self._subset.discard(session_id)
                except Exception as exc:  # noqa: BLE001
                    _LOGGER.warning("could not discard subset session %s: %s", session_id, exc)

    def test_proposal(self, proposal: ChangeProposal) -> TestReport:
        """Copy, overlay, run unittest once, discard. Never mutates project_root."""
        argv = (sys.executable, *_DISCOVER_TAIL)
        command = " ".join(argv)
        session_id = ""
        try:
            if proposal is None or not tuple(getattr(proposal, "changes", ()) or ()):
                return _incomplete(
                    command,
                    "empty proposal; no overlay, no execution",
                )
            copied = self._subset.copy()
            if not getattr(copied, "success", False):
                return _incomplete(
                    command,
                    getattr(copied, "reason", "subset copy failed") or "subset copy failed",
                )
            session_id = str(getattr(copied, "session_id", "") or "")
            session = getattr(copied, "subset_root", None)
            overlaid = self._subset.overlay(session_id, proposal)
            if not getattr(overlaid, "success", False):
                return _incomplete(
                    command,
                    getattr(overlaid, "reason", "overlay failed") or "overlay failed",
                )
            return self._run_unittest(command, argv, session_id, session)
        except Exception as exc:  # noqa: BLE001
            _LOGGER.debug("test_proposal failed: %s", exc)
            return _incomplete(command, f"test_proposal failed: {exc}")
        finally:
            if session_id:
                try:
                    self._subset.discard(session_id)
                except Exception as exc:  # noqa: BLE001
                    _LOGGER.warning("could not discard subset session %s: %s", session_id, exc)

    def _run_unittest(
        self,
        command: str,
        argv: tuple[str, ...],
        session_id: str,
        session: Path | None,
    ) -> TestReport:
        """Sole unittest execution after a session exists. Session-only writes."""
        if session is not None:
            tests_dir = Path(session) / "tests"
            init_py = tests_dir / "__init__.py"
            if tests_dir.is_dir() and not init_py.exists():
                check = None
                for owner in (self._subset, self._runner):
                    check = getattr(owner, "check_temporary_write", None)
                    if check is None:
                        security = getattr(owner, "_security", None)
                        check = getattr(security, "check_temporary_write", None)
                    if check is not None:
                        break
                if check is None:
                    return _incomplete(command, "security has no check_temporary_write")
                decision = check(init_py)
                if getattr(decision, "verdict", None) is not Verdict.ALLOW:
                    reason = getattr(decision, "reason", "temporary write denied")
                    return _incomplete(command, str(reason))
                init_py.write_bytes(b"")
        timeout = min(self._timeout_seconds, MAX_PROCESS_TIMEOUT_SECONDS)
        if timeout <= 0:
            return _incomplete(command, "timeout missing; fail closed")
        result = self._runner.run(
            ProcessSpec(argv=argv, cwd=session, timeout_seconds=timeout),
            session_id=session_id,
        )
        truncated = bool(
            getattr(result, "stdout_truncated", False)
            or getattr(result, "stderr_truncated", False)
        )
        status = getattr(result, "status", None)
        timed_out = status is ProcessStatus.TIMED_OUT or bool(
            getattr(result, "timed_out", False)
        )
        spawn_failed = status in (
            ProcessStatus.SPAWN_FAILURE,
            ProcessStatus.NOT_SPAWNED,
            ProcessStatus.NOT_AUTHORIZED,
        ) or not bool(getattr(result, "spawned", False))
        if status is ProcessStatus.EXITED:
            spawn_failed = False
        incomplete = truncated or timed_out or spawn_failed
        reason = str(getattr(result, "reason", "") or "")
        if truncated:
            reason = reason or "output truncated; counts not trusted"
            incomplete = True
        return parse_unittest_output(
            stdout=str(getattr(result, "stdout", "") or ""),
            stderr=str(getattr(result, "stderr", "") or ""),
            command=command,
            exit_code=getattr(result, "exit_code", None),
            timed_out=timed_out,
            spawn_failed=spawn_failed,
            incomplete=incomplete,
            process_reason=reason,
        )

    def run(self, request: AgentRequest) -> AgentResult:
        proposal = None
        if request is not None:
            context = getattr(request, "context", {}) or {}
            proposal = context.get("proposal") if hasattr(context, "get") else None
        if not isinstance(proposal, ChangeProposal):
            return AgentResult(
                agent=self.name,
                success=False,
                summary="no ChangeProposal in request context",
            )
        report = self.test_proposal(proposal)
        return AgentResult(
            agent=self.name,
            success=report.passed,
            summary=(
                f"tests_run={report.tests_run} passed={report.tests_passed} "
                f"failed={report.tests_failed} errors={report.tests_errors} "
                f"skipped={report.tests_skipped} incomplete={report.incomplete}"
            ),
            details={
                "tests_run": report.tests_run,
                "tests_passed": report.tests_passed,
                "tests_failed": report.tests_failed,
                "tests_errors": report.tests_errors,
                "tests_skipped": report.tests_skipped,
                "exit_code": report.exit_code,
                "timed_out": report.timed_out,
                "spawn_failed": report.spawn_failed,
                "incomplete": report.incomplete,
                "report_passed": report.passed,
            },
            evidence=(report.command,) if report.command else (),
        )


def _incomplete(command: str, reason: str) -> TestReport:
    return TestReport(
        command=command,
        incomplete=True,
        process_reason=reason,
    )


__all__ = ["LocalTestingAgent"]
