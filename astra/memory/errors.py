"""Memory Engine error types.

Named ``MemoryStoreError`` rather than ``MemoryError`` because the latter is a
builtin and shadowing it would be a trap for every future maintainer.
"""

from __future__ import annotations


class MemoryStoreError(Exception):
    """Base class for memory storage failures."""


class DuplicateRecordError(MemoryStoreError):
    """Raised when content already exists in the same category.

    The constitution forbids duplicating verified knowledge; the store applies
    the rule to every record so that duplication cannot enter the database and
    wait to be promoted.
    """


class VerifiedRecordProtectedError(MemoryStoreError):
    """Raised when unverified content would overwrite a verified record.

    The database enforces this with a trigger (ADR-0003 D4). This exception
    exists so the caller sees an explanation rather than an integrity error.
    """


class VectorIndexError(MemoryStoreError):
    """Raised for vector index failures.

    Covers: wrong dimension, invalid vector content (NaN, Inf, zero),
    incompatible index metadata, missing or corrupted index files.
    """


__all__ = [
    "MemoryStoreError",
    "DuplicateRecordError",
    "VerifiedRecordProtectedError",
    "VectorIndexError",
]
