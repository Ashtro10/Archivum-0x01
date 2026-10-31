"""Deployment package.

``LocalDeploymentAgent.deploy`` applies an approved ChangeProposal (B-027).
``LocalVersionManager`` records an immutable pre-image; ``rollback`` restores
it after a matching human ApprovalRecord (B-028). ``GitVersionStore`` records
Git history after a successful deploy (B-033). ApprovalRecord is never
synthesised here.
"""

from __future__ import annotations

from astra.deployment.agent import LocalDeploymentAgent
from astra.deployment.contracts import (
    ApprovalRecord,
    ApprovalRequiredError,
    DeploymentAgent,
    DeploymentResult,
    RollbackResult,
)
from astra.deployment.fingerprint import evidence_fingerprint, git_restore_fingerprint, rollback_fingerprint
from astra.deployment.gitstore import GitRecord, GitVersionStore
from astra.deployment.store import LocalVersionManager
from astra.deployment.versioning import VersionFileSnapshot, VersionManager, VersionRecord

__all__ = [
    "ApprovalRecord",
    "ApprovalRequiredError",
    "DeploymentAgent",
    "DeploymentResult",
    "GitRecord",
    "GitVersionStore",
    "LocalDeploymentAgent",
    "LocalVersionManager",
    "RollbackResult",
    "VersionFileSnapshot",
    "VersionRecord",
    "VersionManager",
    "evidence_fingerprint",
    "git_restore_fingerprint",
    "rollback_fingerprint",
]
