"""Deployment Agent contract.

Deployment is the only stage that makes a change permanent, so it is the stage
that carries the human approval gate. Every deployment method requires an
:class:`ApprovalRecord`; there is deliberately no overload that omits it.

``deploy`` returns a :class:`DeploymentResult`. ``rollback`` returns a
:class:`RollbackResult` and also requires a human :class:`ApprovalRecord`.
"""

from __future__ import annotations

from abc import abstractmethod
from dataclasses import dataclass, field
from datetime import datetime

from astra.coding.contracts import ChangeProposal
from astra.core.contracts import Agent
from astra.evaluation.contracts import EvaluationReport
from astra.testing.contracts import TestReport


class ApprovalRequiredError(PermissionError):
    """Raised when a permanent change is attempted without human approval."""


@dataclass(frozen=True, slots=True)
class ApprovalRecord:
    """Evidence that a human approved a specific change.

    Attributes:
        subject: What was approved, identified precisely enough to detect
            substitution.
        approved_by: The human who approved it.
        approved_at: When approval was given.
        note: Optional reasoning, kept for the audit trail.

    Implementations must never synthesise this object. It exists to be
    supplied from outside the system. For deploy, ``subject`` must equal
    ``evidence_fingerprint(proposal, tests, evaluation)``. For rollback,
    ``subject`` must equal ``rollback_fingerprint(version_id)``. For Git
    restore, ``subject`` must equal ``git_restore_fingerprint(commit_id,
    version_id)``.
    """

    subject: str
    approved_by: str
    approved_at: datetime
    note: str = ""


STATUS_DEPLOYED = "deployed"
STATUS_DEPLOYED_HISTORY_FAILED = "deployed_history_failed"
STATUS_DENIED = "denied"
STATUS_APPROVAL_MISSING = "approval_missing"
STATUS_APPROVAL_INVALID = "approval_invalid"
STATUS_TESTS_FAILED = "tests_failed"
STATUS_EVALUATION_FAILED = "evaluation_failed"
STATUS_INVALID_PROPOSAL = "invalid_proposal"
STATUS_SECURITY_DENIED = "security_denied"
STATUS_DEPLOYMENT_ERROR = "deployment_error"
STATUS_ROLLED_BACK = "rolled_back"
STATUS_UNKNOWN_VERSION = "unknown_version"
STATUS_CORRUPTED = "corrupted"
STATUS_ROLLBACK_ERROR = "rollback_error"


@dataclass(frozen=True, slots=True)
class DeploymentResult:
    """Outcome of one deploy attempt. Ordinary failure stays in this object."""

    status: str
    version: str = ""
    reason: str = ""
    written: tuple[str, ...] = field(default=())
    git_status: str = ""
    git_commit: str = ""

    @property
    def deployed(self) -> bool:
        """True after permanent writes completed, even if Git history failed."""
        return self.status in (STATUS_DEPLOYED, STATUS_DEPLOYED_HISTORY_FAILED)


@dataclass(frozen=True, slots=True)
class RollbackResult:
    """Outcome of one rollback attempt. Partial work is reported honestly."""

    status: str
    reason: str = ""
    restored: tuple[str, ...] = field(default=())
    deleted: tuple[str, ...] = field(default=())

    @property
    def rolled_back(self) -> bool:
        """True only after every planned restore/delete completed."""
        return self.status == STATUS_ROLLED_BACK


class DeploymentAgent(Agent):
    """Applies approved changes and can undo them."""

    name = "deployment"

    @abstractmethod
    def deploy(
        self,
        proposal: ChangeProposal,
        tests: TestReport,
        evaluation: EvaluationReport,
        approval: ApprovalRecord,
    ) -> DeploymentResult:
        """Apply an approved change and return a :class:`DeploymentResult`.

        Implementations must verify that the tests passed, that evaluation
        passed, and that the approval fingerprint matches this exact proposal
        and evidence. Missing or invalid approval must not write.

        Raises:
            ApprovalRequiredError: optional; implementations may return a
                result with ``approval_missing`` / ``approval_invalid`` instead.
        """
        raise NotImplementedError

    @abstractmethod
    def rollback(self, version: str, approval: ApprovalRecord) -> RollbackResult:
        """Restore the pre-image recorded for ``version``.

        Requires an explicit human :class:`ApprovalRecord` bound to this
        version id. Never rolls back automatically. Does not claim atomicity.
        """
        raise NotImplementedError


__all__ = [
    "ApprovalRequiredError",
    "ApprovalRecord",
    "DeploymentAgent",
    "DeploymentResult",
    "RollbackResult",
    "STATUS_APPROVAL_INVALID",
    "STATUS_APPROVAL_MISSING",
    "STATUS_CORRUPTED",
    "STATUS_DENIED",
    "STATUS_DEPLOYED",
    "STATUS_DEPLOYED_HISTORY_FAILED",
    "STATUS_DEPLOYMENT_ERROR",
    "STATUS_EVALUATION_FAILED",
    "STATUS_INVALID_PROPOSAL",
    "STATUS_ROLLBACK_ERROR",
    "STATUS_ROLLED_BACK",
    "STATUS_SECURITY_DENIED",
    "STATUS_TESTS_FAILED",
    "STATUS_UNKNOWN_VERSION",
]
