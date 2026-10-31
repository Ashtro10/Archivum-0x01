"""Vector index contract.

ADR-0001 D1: the index is a derived, disposable cache. It may be deleted at any
time and rebuilt from the memory store. Nothing may exist only in the index.

Each entry records the embedding model and dimensionality that produced it, so
that vectors left stale by a model upgrade can be found and re-embedded instead
of silently returning nonsense.

Interfaces only: no behaviour is implemented here.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


@dataclass(frozen=True, slots=True)
class VectorMatch:
    """One search hit.

    Attributes:
        record_id: Identifier of the memory or knowledge record.
        score: Similarity score; higher is closer.
    """

    record_id: str
    score: float


class VectorIndex(ABC):
    """Approximate or exact nearest-neighbour search over stored records."""

    @property
    @abstractmethod
    def dimension(self) -> int:
        """Vector width this index was built for."""
        raise NotImplementedError

    @property
    @abstractmethod
    def embedding_model(self) -> str:
        """Name of the model whose vectors this index holds.

        A mismatch against the configured model means the index is stale and
        must be rebuilt.
        """
        raise NotImplementedError

    @abstractmethod
    def add(self, record_id: str, vector: Sequence[float]) -> None:
        """Insert or replace the vector for a record."""
        raise NotImplementedError

    @abstractmethod
    def remove(self, record_id: str) -> bool:
        """Drop a record's vector. Returns ``True`` if one was present."""
        raise NotImplementedError

    @abstractmethod
    def search(self, vector: Sequence[float], limit: int = 10) -> Sequence[VectorMatch]:
        """Return the closest records, nearest first."""
        raise NotImplementedError

    @abstractmethod
    def save(self, path: Path) -> None:
        """Write the index to disk."""
        raise NotImplementedError

    @abstractmethod
    def load(self, path: Path) -> None:
        """Read the index from disk.

        A missing or unreadable file is not fatal: callers are expected to
        rebuild from the memory store instead.
        """
        raise NotImplementedError

    @abstractmethod
    def clear(self) -> None:
        """Discard all vectors, leaving the durable store untouched."""
        raise NotImplementedError

    @abstractmethod
    def __len__(self) -> int:
        """Number of indexed vectors."""
        raise NotImplementedError


__all__ = ["VectorMatch", "VectorIndex"]
