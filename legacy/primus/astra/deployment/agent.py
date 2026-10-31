"""Local Deployment Agent (B-027 / B-028 / B-033, ADR-0026 / ADR-0027 / ADR-0032).

Applies an approved ChangeProposal to project_root after TestReport,
EvaluationReport, and a human-supplied ApprovalRecord. After a successful
write, records an immutable pre-image via VersionManager, then asks
GitVersionStore to record history. Does not create approval. Does not spawn
processes. Git spawn lives in gitrun.py.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from astra.coding.contracts import ChangeProposal
from astra.core.contracts import AgentRequest, AgentResult
from astra.deployment.apply import commit_files
from astra.deployment.contracts import (
    ApprovalRecord,
    DeploymentAgent,
    DeploymentResult,
    RollbackResult,
    STATUS_APPROVAL_INVALID,
    STATUS_APPROVAL_MISSING,
    STATUS_DENIED,
    STATUS_DEPLOYED,
    STATUS_DEPLOYED_HISTORY_FAILED,
    STATUS_DEPLOYMENT_ERROR,
    STATUS_EVALUATION_FAILED,
    STATUS_INVALID_PROPOSAL,
    STATUS_SECURITY_DENIED,
    STATUS_TESTS_FAILED,
    STATUS_UNKNOWN_VERSION,
)
from astra.deployment.fingerprint import evidence_fingerprint
from astra.deployment.gitstore import (
    GIT_UNAVAILABLE,
    HISTORY_NOT_RECORDED,
    HISTORY_RECORDED,
)
from astra.deployment.paths import canonical, plan_text_destinations
from astra.deployment.store import capture_preimage
from astra.evaluation.contracts import EvaluationReport
from astra.logging import get_logger
from astra.security.contracts import Verdict
from astra.testing.contracts import TestReport
from astra.testing.workspace import MAX_FILES

_LOGGER = get_logger(__name__)


class LocalDeploymentAgent(DeploymentAgent):
    """Human-approved permanent apply. Not a Tool. Not an OS sandbox."""

    name = "deployment"

    def __init__(
        self,
        *,
        security: Any,
        project_root: Path | str,
        versions: Any | None = None,
        git: Any | None = None,
    ) -> None:
        self._security = security
        self._project_root = Path(project_root)
        self._versions = versions
        self._git = git

    def rollback(self, version: str, approval: ApprovalRecord) -> RollbackResult:
        """Delegate to VersionManager. Fail closed when no store is wired."""
        if self._versions is None:
            return RollbackResult(
                status=STATUS_UNKNOWN_VERSION,
                reason="VersionManager not configured",
            )
        return self._versions.rollback(version, approval)

    def deploy(
        self,
        proposal: ChangeProposal,
        tests: TestReport,
        evaluation: EvaluationReport,
        approval: ApprovalRecord,
    ) -> DeploymentResult:
        """Validate evidence and approval, commit, then snapshot the pre-image."""
        if proposal is None:
            return _result(STATUS_INVALID_PROPOSAL, "proposal missing")
        changes = tuple(getattr(proposal, "changes", ()) or ())
        if not changes:
            return _result(STATUS_INVALID_PROPOSAL, "empty proposal")
        improves = tuple(getattr(proposal, "improves", ()) or ())
        if not improves:
            return _result(STATUS_INVALID_PROPOSAL, "proposal improves nothing")
        if len(changes) > MAX_FILES:
            return _result(STATUS_INVALID_PROPOSAL, "proposal exceeds file cap")
        if tests is None or not isinstance(tests, TestReport):
            return _result(STATUS_TESTS_FAILED, "TestReport missing")
        if bool(getattr(tests, "timed_out", False)):
            return _result(STATUS_TESTS_FAILED, "TestReport timed out")
        if bool(getattr(tests, "spawn_failed", False)):
            return _result(STATUS_TESTS_FAILED, "TestReport spawn failed")
        if bool(getattr(tests, "incomplete", False)):
            return _result(STATUS_TESTS_FAILED, "TestReport incomplete")
        if getattr(tests, "exit_code", None) not in (0, None):
            return _result(STATUS_TESTS_FAILED, "TestReport exit_code is not success")
        if int(getattr(tests, "tests_run", 0) or 0) <= 0:
            return _result(STATUS_TESTS_FAILED, "TestReport recorded no tests")
        if int(getattr(tests, "tests_failed", 0) or 0) > 0:
            return _result(STATUS_TESTS_FAILED, "tests failed")
        if int(getattr(tests, "tests_errors", 0) or 0) > 0:
            return _result(STATUS_TESTS_FAILED, "tests errored")
        if not bool(getattr(tests, "passed", False)):
            return _result(STATUS_TESTS_FAILED, "tests did not pass")
        if evaluation is None or not isinstance(evaluation, EvaluationReport):
            return _result(STATUS_EVALUATION_FAILED, "EvaluationReport missing")
        if bool(getattr(evaluation, "incomplete", False)):
            return _result(STATUS_EVALUATION_FAILED, "EvaluationReport incomplete")
        if not bool(getattr(evaluation, "evaluation_passed", False)):
            return _result(STATUS_EVALUATION_FAILED, "evaluation did not pass")
        if approval is None:
            return _result(STATUS_APPROVAL_MISSING, "ApprovalRecord missing")
        if not isinstance(approval, ApprovalRecord):
            return _result(STATUS_APPROVAL_INVALID, "ApprovalRecord malformed")
        approved_by = str(getattr(approval, "approved_by", "") or "").strip()
        subject = str(getattr(approval, "subject", "") or "").strip()
        if not approved_by or not subject:
            return _result(STATUS_APPROVAL_INVALID, "ApprovalRecord incomplete")
        expected = evidence_fingerprint(proposal, tests, evaluation)
        if subject != expected:
            return _result(
                STATUS_APPROVAL_INVALID,
                "ApprovalRecord does not match this proposal and evidence",
            )
        project = canonical(self._project_root)
        if project is None:
            return _result(STATUS_DENIED, "project_root unresolvable")
        planned, path_reason = plan_text_destinations(changes, project)
        if path_reason:
            return _result(STATUS_INVALID_PROPOSAL, path_reason)
        check = getattr(self._security, "check_permanent_change", None)
        if check is None:
            return _result(STATUS_SECURITY_DENIED, "security has no check_permanent_change")
        decision = check(f"deploy {expected}")
        verdict = getattr(decision, "verdict", None)
        if verdict is Verdict.DENY:
            return _result(
                STATUS_SECURITY_DENIED,
                str(getattr(decision, "reason", "permanent change denied") or "denied"),
            )
        if verdict is not Verdict.REQUIRES_APPROVAL:
            return _result(
                STATUS_SECURITY_DENIED,
                "permanent change is not REQUIRES_APPROVAL; fail closed; never ALLOW",
            )
        preimage, snap_reason = capture_preimage(planned, project)
        if snap_reason:
            return _result(STATUS_INVALID_PROPOSAL, snap_reason)
        written, write_error = commit_files(planned, project=project)
        written_names = _relative_written(written, project)
        if write_error:
            return DeploymentResult(
                status=STATUS_DEPLOYMENT_ERROR,
                reason=write_error,
                written=written_names,
            )
        version = f"d-{expected[:16]}"
        if self._versions is not None:
            save = getattr(self._versions, "save_preimage", None)
            if save is None:
                return DeploymentResult(
                    status=STATUS_DEPLOYMENT_ERROR,
                    reason="VersionManager cannot save a pre-image",
                    written=written_names,
                )
            version, persist_error = save(
                evidence_fingerprint=expected,
                summary=str(getattr(proposal, "objective", "") or "deployed"),
                approved_by=approved_by,
                evidence=(expected,),
                files=preimage,
            )
            if persist_error:
                return DeploymentResult(
                    status=STATUS_DEPLOYMENT_ERROR,
                    reason=persist_error,
                    written=written_names,
                )
        git_status = GIT_UNAVAILABLE
        git_commit = ""
        if self._git is not None:
            record_fn = getattr(self._git, "record_deployment", None)
            if record_fn is None:
                return DeploymentResult(
                    status=STATUS_DEPLOYED_HISTORY_FAILED,
                    version=version,
                    reason="GitVersionStore cannot record history",
                    written=written_names,
                    git_status=HISTORY_NOT_RECORDED,
                    git_commit="",
                )
            rec = record_fn(
                version_id=version,
                written=written_names,
                approval=approval,
            )
            git_status = str(getattr(rec, "status", "") or "") or "history_not_recorded"
            raw_commit = str(getattr(rec, "commit_id", "") or "")
            git_commit = raw_commit if git_status == HISTORY_RECORDED else ""
            if git_status == HISTORY_RECORDED and git_commit:
                return DeploymentResult(
                    status=STATUS_DEPLOYED,
                    version=version,
                    reason="deployed",
                    written=written_names,
                    git_status=git_status,
                    git_commit=git_commit,
                )
            if git_status == GIT_UNAVAILABLE:
                return DeploymentResult(
                    status=STATUS_DEPLOYED,
                    version=version,
                    reason="deployed",
                    written=written_names,
                    git_status=GIT_UNAVAILABLE,
                    git_commit="",
                )
            return DeploymentResult(
                status=STATUS_DEPLOYED_HISTORY_FAILED,
                version=version,
                reason=str(getattr(rec, "reason", "") or git_status),
                written=written_names,
                git_status=git_status,
                git_commit="",
            )
        return DeploymentResult(
            status=STATUS_DEPLOYED,
            version=version,
            reason="deployed",
            written=written_names,
            git_status=git_status,
            git_commit=git_commit,
        )

    def run(self, request: AgentRequest) -> AgentResult:
        proposal = None
        tests = None
        evaluation = None
        approval = None
        if request is not None:
            context = getattr(request, "context", {}) or {}
            if hasattr(context, "get"):
                proposal = context.get("proposal")
                tests = context.get("tests")
                if tests is None:
                    tests = context.get("test_report")
                evaluation = context.get("evaluation")
                approval = context.get("approval")
        result = self.deploy(proposal, tests, evaluation, approval)
        return AgentResult(
            agent=self.name,
            success=result.deployed,
            summary=(
                f"status={result.status} version={result.version} "
                f"reason={result.reason}"
            ),
            details={
                "status": result.status,
                "version": result.version,
                "reason": result.reason,
                "written": result.written,
                "deployed": result.deployed,
                "git_status": result.git_status,
                "git_commit": result.git_commit,
            },
            evidence=(result.version,) if result.deployed else (result.reason,),
        )


def _relative_written(written: tuple[Path, ...], project: Path) -> tuple[str, ...]:
    names: list[str] = []
    for path in written:
        try:
            names.append(path.relative_to(project).as_posix())
        except ValueError:
            names.append(str(path))
    return tuple(names)


def _result(status: str, reason: str) -> DeploymentResult:
    _LOGGER.debug("deploy refused: %s (%s)", status, reason)
    return DeploymentResult(status=status, reason=reason)


__all__ = ["LocalDeploymentAgent"]
