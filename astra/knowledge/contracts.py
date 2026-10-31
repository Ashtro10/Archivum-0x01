"""Knowledge Engine contracts.

The constitution requires that every piece of information be classified before
storage. Classification is therefore part of the write path, not an optional
post-processing step.

Interfaces only: no behaviour is implemented here. Concrete implementations
are provided by B-004 in :mod:`astra.knowledge.classifier` and
:mod:`astra.knowledge.sqlite_store`.

Design decisions recorded in ADR-0008.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Iterable, Sequence


class KnowledgeCategory(Enum):
    """Knowledge categories named by the constitution."""

    ARCHITECTURE = "architecture"
    PROGRAMMING = "programming"
    ARTIFICIAL_INTELLIGENCE = "artificial_intelligence"
    MATHEMATICS = "mathematics"
    OPERATING_SYSTEMS = "operating_systems"
    NETWORKING = "networking"
    SECURITY = "security"
    HARDWARE = "hardware"
    PROJECTS = "projects"
    USER_PREFERENCES = "user_preferences"
    EXPERIMENTS = "experiments"
    FAILURES = "failures"
    IDEAS = "ideas"


class KnowledgeVerification(Enum):
    """Trust level for a knowledge record.

    ``VERIFIED`` records may only be replaced by other ``VERIFIED`` records.
    Defined here rather than imported from :mod:`astra.memory` to keep the
    two systems independent (ADR-0008 D4).
    """

    UNVERIFIED = "unverified"
    VERIFIED = "verified"
    REFUTED = "refuted"


@dataclass(frozen=True, slots=True)
class Classification:
    """The outcome of classifying a piece of information.

    Attributes:
        category: Assigned category.
        confidence: Classifier confidence in the category assignment.
            Semantics are defined per classifier implementation and must be
            documented there. Do not compare confidence values from different
            classifier implementations.
        rationale: Why this category was chosen. Required for explainability;
            a classifier that cannot explain itself cannot be audited.
    """

    category: KnowledgeCategory
    confidence: float
    rationale: str = ""


@dataclass(frozen=True, slots=True)
class KnowledgeRecord:
    """One classified piece of knowledge.

    Attributes:
        record_id: Stable identifier. Assigned by the store on first write.
        category: Which constitutional category this belongs to.
        content: The knowledge text.
        source: Where the content came from, for provenance.
        confidence: Classifier's confidence in the category assignment.
            Represents the quality of the classification, not the reliability
            of the content itself. Content reliability is expressed through
            :attr:`verification`.
        verification: Trust level; governs whether this record may be
            overwritten by unverified content.
        created_at: First write time.
        updated_at: Last write time.
        acceptance_note: Why this record was accepted or rejected. Records why
            a particular piece of knowledge entered or was barred from the
            store, making the decision auditable.
        tags: Free-form labels for retrieval.
    """

    record_id: str
    category: KnowledgeCategory
    content: str
    source: str = ""
    confidence: float = 0.0
    verification: KnowledgeVerification = KnowledgeVerification.UNVERIFIED
    created_at: datetime | None = None
    updated_at: datetime | None = None
    acceptance_note: str = ""
    tags: tuple[str, ...] = field(default_factory=tuple)


class KnowledgeClassifier(ABC):
    """Assigns a category to information before it may be stored.

    Implementations must be deterministic for the same input. Confidence
    semantics must be documented in the concrete class; the values carry no
    meaning beyond what the implementation documents.
    """

    @abstractmethod
    def classify(self, content: str) -> Classification:
        """Return the category this content belongs to.

        Implementations must never guess silently: low confidence must be
        reported through :attr:`Classification.confidence` so the caller can
        route the item for human review.
        """
        raise NotImplementedError


class KnowledgeStore(ABC):
    """Durable storage for classified knowledge.

    Per ADR-0001 (applied to Knowledge as well as Memory): SQLite is the source
    of truth. Any vector index built over it is derived and rebuildable from
    these records.

    Two constitutional rules are enforced by the store itself:

    * Verified knowledge is not duplicated.
    * Unknown information never overwrites verified information.
    """

    @abstractmethod
    def initialise(self) -> None:
        """Create or migrate the underlying schema. Must be idempotent."""
        raise NotImplementedError

    @abstractmethod
    def store(self, record: KnowledgeRecord) -> str:
        """Persist a classified record and return its identifier.

        The record must already carry a category (assigned by a
        :class:`KnowledgeClassifier`). The store does not classify; it
        enforces the storage invariants (ADR-0008 D9).

        Raises:
            DuplicateKnowledgeError: if the same content already exists in
                the same category.
            VerifiedKnowledgeProtectedError: if unverified content would
                overwrite a verified record.
            KnowledgeStoreError: if the content is empty.
        """
        raise NotImplementedError

    @abstractmethod
    def get(self, record_id: str) -> KnowledgeRecord | None:
        """Return one record, or ``None`` if it does not exist."""
        raise NotImplementedError

    @abstractmethod
    def search(
        self,
        query: str,
        *,
        category: KnowledgeCategory | None = None,
        limit: int = 20,
    ) -> Sequence[KnowledgeRecord]:
        """Return matching records, most relevant first."""
        raise NotImplementedError

    @abstractmethod
    def iter_records(
        self, *, category: KnowledgeCategory | None = None
    ) -> Iterable[KnowledgeRecord]:
        """Iterate over stored records.

        Used to rebuild derived structures such as a future vector index over
        knowledge. Implementations must stream rather than load the whole
        table into memory.
        """
        raise NotImplementedError

    @abstractmethod
    def delete(self, record_id: str) -> bool:
        """Remove a record permanently.

        Deletion is a permanent change and requires human approval upstream;
        this method performs no approval check of its own.

        Returns:
            ``True`` if a record was removed.
        """
        raise NotImplementedError


__all__ = [
    "KnowledgeCategory",
    "KnowledgeVerification",
    "Classification",
    "KnowledgeRecord",
    "KnowledgeClassifier",
    "KnowledgeStore",
]
