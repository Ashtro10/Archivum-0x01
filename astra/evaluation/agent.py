"""Local Evaluation Agent (B-026 / B-029, ADR-0025 / ADR-0028).

``evaluate_proposal`` judges a ChangeProposal against an existing TestReport
and optional baseline/candidate samples. ``evaluate`` compares samples to a
recorded baseline. ``record_baseline`` stores immutable samples.

Does not run tests, write project files, spawn, approve, or deploy.
"""

from __future__ import annotations

from typing import Any

from astra.coding.contracts import ChangeProposal
from astra.core.contracts import AgentRequest, AgentResult
from astra.evaluation.compare import compare_samples
from astra.evaluation.contracts import (
    BASELINE_CORRUPTED,
    BASELINE_INCOMPLETE,
    BASELINE_MISMATCH,
    BASELINE_MISSING,
    BASELINE_VALID,
    CANDIDATE_INCOMPLETE,
    CANDIDATE_MISSING,
    CANDIDATE_VALID,
    COMPARISON_IMPROVED,
    COMPARISON_INCOMPLETE,
    COMPARISON_REGRESSED,
    COMPARISON_UNAVAILABLE,
    COMPARISON_UNCHANGED,
    BaselineWriteResult,
    EvaluationAgent,
    EvaluationReport,
    MetricSample,
    WRITE_REJECTED,
)
from astra.evaluation.store import baseline_id_for
from astra.logging import get_logger
from astra.testing.contracts import TestReport
from astra.testing.workspace import MAX_FILE_BYTES, MAX_FILES

_LOGGER = get_logger(__name__)


class LocalEvaluationAgent(EvaluationAgent):
    """Fail-closed proposal evaluation. Not approval. Not deployment."""

    name = "evaluation"

    def __init__(self, *, baselines: Any | None = None) -> None:
        self._baselines = baselines

    def evaluate(
        self,
        subject: str,
        candidate: tuple[MetricSample, ...] = (),
    ) -> EvaluationReport:
        """Compare candidate samples to the recorded baseline. No tests."""
        subject_text = str(subject or "").strip()
        report = self._compare(
            subject=subject_text,
            baseline_id=baseline_id_for(subject_text) if subject_text else None,
            candidate=tuple(candidate or ()),
            tests_passed=False,
            proposal_ok=False,
        )
        return report

    def record_baseline(
        self, subject: str, report: EvaluationReport
    ) -> BaselineWriteResult:
        """Persist samples from ``report``. Never overwrites."""
        if self._baselines is None:
            return BaselineWriteResult(
                status=WRITE_REJECTED,
                reason="baseline store not configured",
            )
        return self._baselines.record(subject, report)

    def evaluate_proposal(
        self,
        proposal: ChangeProposal,
        tests: TestReport,
        *,
        baseline_id: str | None = None,
        candidate: tuple[MetricSample, ...] = (),
    ) -> EvaluationReport:
        """Judge existing evidence once. Never mutates proposal or reruns tests."""
        subject = str(getattr(proposal, "objective", "") or "") if proposal is not None else ""
        if proposal is None:
            return _fail(subject, "proposal missing")
        changes = tuple(getattr(proposal, "changes", ()) or ())
        if not changes:
            return _fail(subject, "empty proposal")
        improves = tuple(getattr(proposal, "improves", ()) or ())
        if not improves:
            return _fail(subject, "proposal improves nothing")
        if len(changes) > MAX_FILES:
            return _fail(subject, "proposal exceeds file cap")
        for change in changes:
            path = getattr(change, "path", None)
            if path is None or str(path).strip() == "":
                return _fail(subject, "proposal change missing path")
            content = getattr(change, "new_content", "") or ""
            try:
                size = len(content.encode("utf-8"))
            except Exception:
                return _fail(subject, "proposal content not encodable")
            if size > MAX_FILE_BYTES:
                return _fail(subject, "proposal file exceeds size cap")
        if tests is None:
            return _fail(subject, "TestReport missing")
        if not isinstance(tests, TestReport):
            return _fail(subject, "TestReport malformed")
        if bool(getattr(tests, "timed_out", False)):
            return _fail(subject, "TestReport timed out", incomplete=True)
        if bool(getattr(tests, "spawn_failed", False)):
            return _fail(subject, "TestReport spawn failed", incomplete=True)
        if bool(getattr(tests, "incomplete", False)):
            return _fail(subject, "TestReport incomplete", incomplete=True)
        exit_code = getattr(tests, "exit_code", None)
        if exit_code not in (0, None):
            return _fail(subject, "TestReport exit_code is not success")
        tests_failed = int(getattr(tests, "tests_failed", 0) or 0)
        tests_errors = int(getattr(tests, "tests_errors", 0) or 0)
        tests_run = int(getattr(tests, "tests_run", 0) or 0)
        tests_skipped = int(getattr(tests, "tests_skipped", 0) or 0)
        tests_ok_count = int(getattr(tests, "tests_passed", 0) or 0)
        if tests_failed > 0:
            return _fail(subject, f"tests failed={tests_failed}")
        if tests_errors > 0:
            return _fail(subject, f"tests errored={tests_errors}")
        if tests_run <= 0:
            return _fail(subject, "execution evidence malformed or empty")
        if not bool(getattr(tests, "passed", False)):
            return _fail(subject, "execution evidence malformed or empty")
        comparative = bool(baseline_id) or bool(candidate)
        if not comparative:
            notes = (
                f"tests_run={tests_run} passed={tests_ok_count} failed={tests_failed} "
                f"errors={tests_errors} skipped={tests_skipped}; evaluation passed; "
                "not approved; not deployment-authorized"
            )
            return EvaluationReport(
                subject=subject,
                notes=notes,
                tests_passed=True,
                evaluation_passed=True,
                deployment_authorized=False,
                incomplete=False,
                reason="",
                baseline_status=BASELINE_MISSING,
                candidate_status=CANDIDATE_MISSING,
                comparison_status=COMPARISON_UNAVAILABLE,
            )
        wanted = str(baseline_id).strip() if baseline_id else baseline_id_for(subject)
        return self._compare(
            subject=subject,
            baseline_id=wanted,
            candidate=tuple(candidate or ()),
            tests_passed=True,
            proposal_ok=True,
            tests_note=(
                f"tests_run={tests_run} passed={tests_ok_count} failed={tests_failed} "
                f"errors={tests_errors} skipped={tests_skipped}"
            ),
        )

    def run(self, request: AgentRequest) -> AgentResult:
        proposal: Any = None
        tests: Any = None
        baseline_id: Any = None
        candidate: Any = ()
        if request is not None:
            context = getattr(request, "context", {}) or {}
            if hasattr(context, "get"):
                proposal = context.get("proposal")
                tests = context.get("tests")
                if tests is None:
                    tests = context.get("test_report")
                baseline_id = context.get("baseline_id")
                candidate = context.get("candidate") or ()
        report = self.evaluate_proposal(
            proposal,
            tests,
            baseline_id=baseline_id,
            candidate=tuple(candidate or ()),
        )
        return AgentResult(
            agent=self.name,
            success=report.evaluation_passed,
            summary=(
                f"evaluation_passed={report.evaluation_passed} "
                f"tests_passed={report.tests_passed} "
                f"deployment_authorized={report.deployment_authorized} "
                f"improved={report.improved} reason={report.reason}"
            ),
            details={
                "evaluation_passed": report.evaluation_passed,
                "tests_passed": report.tests_passed,
                "deployment_authorized": report.deployment_authorized,
                "improved": report.improved,
                "incomplete": report.incomplete,
                "reason": report.reason,
                "baseline_status": report.baseline_status,
                "comparison_status": report.comparison_status,
            },
            evidence=(report.reason or report.notes,),
        )

    def _compare(
        self,
        *,
        subject: str,
        baseline_id: str | None,
        candidate: tuple[MetricSample, ...],
        tests_passed: bool,
        proposal_ok: bool,
        tests_note: str = "",
    ) -> EvaluationReport:
        candidate_status = (
            CANDIDATE_VALID
            if candidate
            else CANDIDATE_MISSING
        )
        if candidate and any(
            not str(getattr(item, "metric", "") or "").strip() for item in candidate
        ):
            candidate_status = CANDIDATE_INCOMPLETE
        baseline_status = BASELINE_MISSING
        loaded = None
        load_err = ""
        if not baseline_id:
            baseline_status = BASELINE_MISSING
            load_err = "baseline id missing"
        elif self._baselines is None:
            load_err = "baseline store not configured"
        else:
            loaded, load_err = self._baselines.load(baseline_id)
            if load_err == "unknown baseline":
                baseline_status = BASELINE_MISSING
            elif load_err:
                baseline_status = BASELINE_CORRUPTED
            elif loaded is None:
                baseline_status = BASELINE_MISSING
            elif loaded.subject != subject:
                baseline_status = BASELINE_MISMATCH
                loaded = None
            elif loaded.status == BASELINE_INCOMPLETE or not loaded.metrics:
                baseline_status = BASELINE_INCOMPLETE
            else:
                baseline_status = BASELINE_VALID
        measurements = ()
        unavailable: tuple[str, ...] = ()
        comparison_status = COMPARISON_UNAVAILABLE
        if (
            baseline_status == BASELINE_VALID
            and candidate_status == CANDIDATE_VALID
            and loaded is not None
        ):
            measurements, unavailable, comparison_status = compare_samples(
                loaded.metrics, candidate
            )
        elif candidate_status != CANDIDATE_MISSING or baseline_status != BASELINE_MISSING:
            comparison_status = COMPARISON_INCOMPLETE
        incomplete = comparison_status in {
            COMPARISON_INCOMPLETE,
            COMPARISON_UNAVAILABLE,
        } or baseline_status in {
            BASELINE_MISSING,
            BASELINE_CORRUPTED,
            BASELINE_MISMATCH,
            BASELINE_INCOMPLETE,
        } or candidate_status != CANDIDATE_VALID
        if candidate_status == CANDIDATE_MISSING and baseline_status == BASELINE_MISSING:
            incomplete = True
            comparison_status = COMPARISON_UNAVAILABLE
        evaluation_passed = (
            proposal_ok
            and tests_passed
            and comparison_status in {COMPARISON_IMPROVED, COMPARISON_UNCHANGED}
            and not incomplete
        )
        if comparison_status == COMPARISON_REGRESSED:
            evaluation_passed = False
            incomplete = False
        reason = ""
        if baseline_status == BASELINE_MISMATCH:
            reason = "baseline does not match this subject"
        elif baseline_status == BASELINE_CORRUPTED:
            reason = load_err or "baseline corrupted"
        elif comparison_status == COMPARISON_REGRESSED:
            reason = "candidate regressed"
        elif comparison_status == COMPARISON_INCOMPLETE:
            reason = load_err or "comparison incomplete"
        elif comparison_status == COMPARISON_UNAVAILABLE:
            reason = load_err or "comparison unavailable"
        notes = tests_note or reason or "comparative evaluation"
        if tests_note:
            notes = f"{tests_note}; {reason or comparison_status}; not approved; not deployment-authorized"
        return EvaluationReport(
            subject=subject,
            measurements=measurements,
            notes=notes,
            tests_passed=tests_passed,
            evaluation_passed=evaluation_passed,
            deployment_authorized=False,
            incomplete=incomplete,
            reason=reason,
            baseline_id=str(baseline_id or ""),
            baseline_status=baseline_status,
            candidate_status=candidate_status,
            comparison_status=comparison_status,
            unavailable_metrics=unavailable,
        )


def _fail(
    subject: str,
    reason: str,
    *,
    incomplete: bool = False,
    tests_passed: bool = False,
) -> EvaluationReport:
    _LOGGER.debug("evaluate_proposal failed: %s", reason)
    return EvaluationReport(
        subject=subject,
        notes=reason,
        tests_passed=tests_passed,
        evaluation_passed=False,
        deployment_authorized=False,
        incomplete=incomplete,
        reason=reason,
        baseline_status=BASELINE_MISSING,
        candidate_status=CANDIDATE_MISSING,
        comparison_status=COMPARISON_UNAVAILABLE,
    )


__all__ = ["LocalEvaluationAgent"]
