"""Controller-owned change pipeline (B-031, ADR-0030).

Composes existing agents. Does not invent evidence or ApprovalRecord.
Does not spawn processes. Not a Tool. Not an Agent.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from astra.deployment.contracts import (
    STATUS_APPROVAL_INVALID,
    STATUS_APPROVAL_MISSING,
    STATUS_DEPLOYED,
    STATUS_DEPLOYED_HISTORY_FAILED,
    DeploymentResult,
)
from astra.evaluation.contracts import EvaluationReport
from astra.testing.contracts import TestReport

PIPELINE_INVALID_PROPOSAL = "invalid_proposal"
PIPELINE_TESTING_FAILED = "testing_failed"
PIPELINE_TESTING_INCOMPLETE = "testing_incomplete"
PIPELINE_EVALUATION_FAILED = "evaluation_failed"
PIPELINE_APPROVAL_MISSING = "approval_missing"
PIPELINE_APPROVAL_INVALID = "approval_invalid"
PIPELINE_DEPLOYMENT_FAILED = "deployment_failed"
PIPELINE_DEPLOYED = "deployed"
PIPELINE_DEPLOYED_HISTORY_FAILED = "deployed_history_failed"


@dataclass(frozen=True, slots=True)
class PipelineResult:
    """Outcome of one controller-owned change pipeline run.

    Status names are distinct. ``success`` is not collapsed into a boolean.
    ``tests`` and ``evaluation`` are the actual evidence objects when produced.
    """

    status: str
    reason: str = ""
    tests: TestReport | None = None
    evaluation: EvaluationReport | None = None
    deployment: DeploymentResult | None = None

    @property
    def deployed(self) -> bool:
        """True only after DeploymentAgent reported a completed write."""
        return self.status in (PIPELINE_DEPLOYED, PIPELINE_DEPLOYED_HISTORY_FAILED)


def process_change_proposal(
    *,
    testing: Any,
    evaluation: Any,
    deployment: Any,
    proposal: Any,
    approval: Any | None = None,
) -> PipelineResult:
    """Run test → evaluate → deploy only when a human approval is supplied.

    Failed stages skip later agents. Does not create ApprovalRecord.
    Ignores EvaluationReport.deployment_authorized.
    """
    if proposal is None or not tuple(getattr(proposal, "changes", ()) or ()):
        return PipelineResult(
            status=PIPELINE_INVALID_PROPOSAL,
            reason="empty proposal",
        )
    if not tuple(getattr(proposal, "improves", ()) or ()):
        return PipelineResult(
            status=PIPELINE_INVALID_PROPOSAL,
            reason="proposal improves nothing",
        )

    try:
        tests = testing.test_proposal(proposal)
    except Exception as exc:  # noqa: BLE001
        return PipelineResult(
            status=PIPELINE_TESTING_INCOMPLETE,
            reason=f"test_proposal failed: {exc}",
        )
    if not isinstance(tests, TestReport):
        return PipelineResult(
            status=PIPELINE_TESTING_INCOMPLETE,
            reason="TestReport missing",
        )
    if bool(getattr(tests, "incomplete", False)) or bool(
        getattr(tests, "timed_out", False)
    ) or bool(getattr(tests, "spawn_failed", False)):
        return PipelineResult(
            status=PIPELINE_TESTING_INCOMPLETE,
            reason=str(getattr(tests, "process_reason", "") or "testing incomplete"),
            tests=tests,
        )
    if not bool(getattr(tests, "passed", False)):
        return PipelineResult(
            status=PIPELINE_TESTING_FAILED,
            reason="tests did not pass",
            tests=tests,
        )

    try:
        report = evaluation.evaluate_proposal(proposal, tests)
    except Exception as exc:  # noqa: BLE001
        return PipelineResult(
            status=PIPELINE_EVALUATION_FAILED,
            reason=f"evaluate_proposal failed: {exc}",
            tests=tests,
        )
    if not isinstance(report, EvaluationReport):
        return PipelineResult(
            status=PIPELINE_EVALUATION_FAILED,
            reason="EvaluationReport missing",
            tests=tests,
        )
    if not bool(getattr(report, "evaluation_passed", False)):
        return PipelineResult(
            status=PIPELINE_EVALUATION_FAILED,
            reason=str(getattr(report, "reason", "") or "evaluation did not pass"),
            tests=tests,
            evaluation=report,
        )

    if approval is None:
        return PipelineResult(
            status=PIPELINE_APPROVAL_MISSING,
            reason="ApprovalRecord missing",
            tests=tests,
            evaluation=report,
        )

    try:
        result = deployment.deploy(proposal, tests, report, approval)
    except Exception as exc:  # noqa: BLE001
        return PipelineResult(
            status=PIPELINE_DEPLOYMENT_FAILED,
            reason=f"deploy failed: {exc}",
            tests=tests,
            evaluation=report,
        )
    if not isinstance(result, DeploymentResult):
        return PipelineResult(
            status=PIPELINE_DEPLOYMENT_FAILED,
            reason="DeploymentResult missing",
            tests=tests,
            evaluation=report,
        )
    status = str(getattr(result, "status", "") or "")
    if status == STATUS_DEPLOYED:
        mapped = PIPELINE_DEPLOYED
    elif status == STATUS_DEPLOYED_HISTORY_FAILED:
        mapped = PIPELINE_DEPLOYED_HISTORY_FAILED
    elif status == STATUS_APPROVAL_MISSING:
        mapped = PIPELINE_APPROVAL_MISSING
    elif status == STATUS_APPROVAL_INVALID:
        mapped = PIPELINE_APPROVAL_INVALID
    else:
        mapped = PIPELINE_DEPLOYMENT_FAILED
    return PipelineResult(
        status=mapped,
        reason=str(getattr(result, "reason", "") or mapped),
        tests=tests,
        evaluation=report,
        deployment=result,
    )


__all__ = [
    "PIPELINE_APPROVAL_INVALID",
    "PIPELINE_APPROVAL_MISSING",
    "PIPELINE_DEPLOYED",
    "PIPELINE_DEPLOYED_HISTORY_FAILED",
    "PIPELINE_DEPLOYMENT_FAILED",
    "PIPELINE_EVALUATION_FAILED",
    "PIPELINE_INVALID_PROPOSAL",
    "PIPELINE_TESTING_FAILED",
    "PIPELINE_TESTING_INCOMPLETE",
    "PipelineResult",
    "process_change_proposal",
]
