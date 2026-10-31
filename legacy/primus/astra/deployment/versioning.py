"""Version Manager.

Nested inside the Deployment Agent's package because versions are exactly what
deployment promotes; separating them would create a bidirectional dependency
between two packages. The rationale is recorded in ``docs/architecture.md``.

``VersionManager`` is a controller-owned service, not an Agent and not a Tool.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime
from typing import Sequence

from astra.deployment.contracts import ApprovalRecord, RollbackResult


@dataclass(frozen=True, slots=True)
class VersionRecord:
    """One deployed version of Astra.

    Attributes:
        version: Identifier, for example ``d-abc123-0001``.
        deployed_at: When it became active.
        summary: What changed.
        approved_by: The human who approved the deployment.
        evidence: References to the test and evaluation reports that
            justified it.
        file_count: How many paths this version snapshotted.
        integrity: Hash over the snapshot manifest; empty if unknown.
    """

    version: str
    deployed_at: datetime
    summary: str
    approved_by: str
    evidence: tuple[str, ...] = field(default=())
    file_count: int = 0
    integrity: str = ""


@dataclass(frozen=True, slots=True)
class VersionFileSnapshot:
    """Pre-deployment bytes for one path changed by that deployment.

    ``original_bytes`` is None when the file did not exist before deploy.
    """

    relative_path: str
    existed: bool
    sha256: str = ""
    original_bytes: bytes | None = None


class VersionManager(ABC):
    """Records what is deployed and what came before it."""

    @abstractmethod
    def current(self) -> VersionRecord | None:
        """Return the active version, or ``None`` before the first deploy."""
        raise NotImplementedError

    @abstractmethod
    def record(self, version: VersionRecord) -> None:
        """Append a version to the history.

        History is append-only. Rewriting it would destroy the audit trail
        that makes rollback trustworthy.
        """
        raise NotImplementedError

    @abstractmethod
    def history(self, limit: int = 50) -> Sequence[VersionRecord]:
        """Return recent versions, newest first."""
        raise NotImplementedError

    @abstractmethod
    def get(self, version: str) -> VersionRecord | None:
        """Return one version record, or ``None`` if unknown."""
        raise NotImplementedError

    @abstractmethod
    def rollback(self, version: str, approval: ApprovalRecord) -> RollbackResult:
        """Restore the pre-image for ``version`` after a matching approval.

        Never automatic. Missing or invalid approval must not write.
        """
        raise NotImplementedError


__all__ = [
    "VersionFileSnapshot",
    "VersionManager",
    "VersionRecord",
]
