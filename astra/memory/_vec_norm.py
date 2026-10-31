"""Vector normalization utility for the FAISS vector index (B-003).

B-010 measured that nomic-embed-text-v1.5 Q8_0 produces vectors with
L2 norm ≈ 22.5 — NOT unit-normalized. IndexFlatIP only gives cosine
similarity when stored and query vectors are L2-normalized. Therefore:

    Every vector entering the FAISS index MUST pass through ``normalize``.
    Every query vector MUST pass through ``normalize``.

This module is pure Python (no numpy, no faiss imports at module level) so
it can be imported and tested independently of those packages.
"""

from __future__ import annotations

import math
from typing import Sequence

from astra.memory.errors import VectorIndexError


def normalize(
    vector: Sequence[float],
    *,
    expected_dim: int,
) -> tuple[float, ...]:
    """Validate and L2-normalize a vector.

    Returns a unit vector (L2 norm = 1.0) as a tuple of floats.

    Args:
        vector: The raw embedding vector from the embedding backend.
        expected_dim: The dimension this index was built for.

    Raises:
        VectorIndexError: If the vector has wrong dimension, is empty,
            contains NaN or Inf, or has L2 norm zero (cannot normalize).

    Notes:
        The normalization makes inner-product (IndexFlatIP) equivalent to
        cosine similarity: ``dot(normalize(a), normalize(b)) == cosine(a, b)``.
        This is a hard requirement derived from B-010 (ADR-0005 §24).
    """
    if len(vector) == 0:
        raise VectorIndexError("vector must not be empty")

    if len(vector) != expected_dim:
        raise VectorIndexError(
            f"vector dimension {len(vector)} does not match index dimension "
            f"{expected_dim}; rebuild the index or check the embedding model"
        )

    floats = tuple(float(v) for v in vector)

    for i, v in enumerate(floats):
        if math.isnan(v):
            raise VectorIndexError(f"vector contains NaN at position {i}")
        if math.isinf(v):
            raise VectorIndexError(f"vector contains Inf at position {i}")

    norm = math.sqrt(sum(v * v for v in floats))
    if norm == 0.0:
        raise VectorIndexError(
            "cannot normalize a zero vector; the embedding model may have "
            "returned an all-zero vector for this input"
        )

    return tuple(v / norm for v in floats)


def cosine_similarity(a: Sequence[float], b: Sequence[float]) -> float:
    """Return cosine similarity of two raw (non-normalized) vectors.

    Used in tests to verify that normalized IP matches expected cosine.
    Not used in the hot path.
    """
    if len(a) != len(b):
        raise VectorIndexError(
            f"vector length mismatch: {len(a)} vs {len(b)}"
        )
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    if na == 0 or nb == 0:
        return 0.0
    return sum(x * y for x, y in zip(a, b)) / (na * nb)


__all__ = ["normalize", "cosine_similarity"]
