"""Memory Engine contracts.

Two constitutional rules are the reason this interface looks the way it does:

* verified knowledge is never duplicated, and
* unknown information never overwrites verified information.

Both must be enforced by the store itself. A rule that depends on every caller
remembering it is not a rule.

Interfaces only: no behaviour is implemented here.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Iterable, Sequence


class MemoryCategory(Enum):
    """Memory categories named by the constitution."""

    USER_PREFERENCES = "user_preferences"
    PROJECT_KNOWLEDGE = "project_knowledge"
    DOCUMENTATION = "documentation"
    ARCHITECTURE = "architecture"
    RESEARCH = "research"
    CODE_SUMMARIES = "code_summaries"
    EXPERIMENTS = "experiments"
    FAILURES = "failures"
    HARDWARE = "hardware"
    IDEAS = "ideas"


class VerificationState(Enum):
    """How much trust a record has earned.

    ``VERIFIED`` records may only be replaced by other ``VERIFIED`` records.
    """

    UNVERIFIED = "unverified"
    VERIFIED = "verified"
    REFUTED = "refuted"


@dataclass(frozen=True, slots=True)
class EmbeddingProvenance:
    """Indexing metadata for one memory record (ADR-0003 D12, ADR-0012 D5).

    Not part of :class:`MemoryRecord`. Written by ``MemoryIndexer`` after a
    successful embed; unused when the embedding backend is unavailable.
    """

    record_id: str
    embedding_model: str
    embedding_version: str
    embedding_dim: int
    embedded_at: datetime | None = None


@dataclass(frozen=True, slots=True)
class MemoryRecord:
    """One durable memory.

    Attributes:
        record_id: Stable identifier. Assigned by the store on first write.
        category: Which constitutional category this belongs to.
        content: The remembered text.
        verification: Trust level; governs whether this record may be
            overwritten.
        source: Where the content came from, for explainability.
        created_at: First write time.
        updated_at: Last write time.
        tags: Free-form labels for retrieval.
    """

    record_id: str
    category: MemoryCategory
    content: str
    verification: VerificationState = VerificationState.UNVERIFIED
    source: str = ""
    created_at: datetime | None = None
    updated_at: datetime | None = None
    tags: tuple[str, ...] = field(default=())


class MemoryStore(ABC):
    """Durable storage for memory records.

    Per ADR-0001 this is the single source of truth. Any vector index built
    over it is derived and may be rebuilt from these records at any time.
    """

    @abstractmethod
    def initialise(self) -> None:
        """Create or migrate the underlying schema. Must be idempotent."""
        raise NotImplementedError

    @abstractmethod
    def store(self, record: MemoryRecord) -> str:
        """Persist a record and return its identifier.

        Implementations must refuse to overwrite a ``VERIFIED`` record with an
        ``UNVERIFIED`` one, and must not create a duplicate of content already
        stored as verified.
        """
        raise NotImplementedError

    @abstractmethod
    def get(self, record_id: str) -> MemoryRecord | None:
        """Return one record, or ``None`` if it does not exist."""
        raise NotImplementedError

    @abstractmethod
    def search(
        self,
        query: str,
        *,
        category: MemoryCategory | None = None,
        limit: int = 20,
    ) -> Sequence[MemoryRecord]:
        """Return records matching a textual query, most relevant first."""
        raise NotImplementedError

    @abstractmethod
    def iter_records(
        self, *, category: MemoryCategory | None = None
    ) -> Iterable[MemoryRecord]:
        """Iterate over stored records.

        Used to rebuild derived structures such as the vector index, so
        implementations must stream rather than load everything into memory.
        """
        raise NotImplementedError

    @abstractmethod
    def delete(self, record_id: str) -> bool:
        """Remove a record permanently.

        Deletion of memory is a permanent change and therefore requires human
        approval upstream; this method performs no approval check of its own.

        Returns:
            ``True`` if a record was removed.
        """
        raise NotImplementedError


__all__ = [
    "MemoryCategory",
    "VerificationState",
    "EmbeddingProvenance",
    "MemoryRecord",
    "MemoryStore",
]
