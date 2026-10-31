"""Knowledge Engine error types."""

from __future__ import annotations


class KnowledgeStoreError(Exception):
    """Base class for knowledge storage failures."""


class DuplicateKnowledgeError(KnowledgeStoreError):
    """Raised when content already exists in the same category.

    The constitution forbids duplicating verified knowledge; the store applies
    the rule to every record so that duplication cannot enter the database and
    wait to be promoted.
    """


class VerifiedKnowledgeProtectedError(KnowledgeStoreError):
    """Raised when unverified content would overwrite a verified record.

    The database enforces this with a trigger (ADR-0008 D6). This exception
    exists so the caller sees an explanation rather than a bare integrity error.
    """


__all__ = [
    "KnowledgeStoreError",
    "DuplicateKnowledgeError",
    "VerifiedKnowledgeProtectedError",
]
