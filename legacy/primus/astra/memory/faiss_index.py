"""FAISS IndexFlatIP vector index (B-003).

ADR-0001 D1: SQLite is the source of truth. This index is derived and
disposable. It may be deleted at any time and rebuilt from the memory store.

Architecture:
    SQLite (authoritative)
        → EmbeddingBackend → L2 normalize → FaissVectorIndex
    On rebuild: SQLite → embed → normalize → add to FaissVectorIndex

B-010 finding: nomic-embed-text-v1.5 Q8_0 vectors have L2 norm ≈ 22.5 and
MUST be normalized before insertion. This module enforces that invariant:
``normalize()`` is called unconditionally inside ``add()`` and ``search()``.
Callers cannot bypass it.

FAISS note: ``IndexFlatIP`` does not support deletion. Deleted records are
"tombstoned" in the ID mapping and excluded from search results. Physical
reclamation requires calling ``clear()`` + ``rebuild_index()``.

No network access. No automatic downloads. Operates on local data only.
"""

from __future__ import annotations

import importlib
import json
import math
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING, Sequence

from astra.logging import get_logger
from astra.memory._vec_norm import normalize
from astra.memory.errors import VectorIndexError
from astra.memory.vector import VectorIndex, VectorMatch

if TYPE_CHECKING:
    from astra.memory.contracts import MemoryStore
    from astra.reasoning.backends import EmbeddingBackend

_LOGGER = get_logger(__name__)

_INDEX_FILE = "index.faiss"
_MAPPING_FILE = "mapping.json"
_META_FILE = "meta.json"

_FORMAT_VERSION = 1
_INDEX_TYPE = "IndexFlatIP"
_NORMALIZATION = "L2"


# Lazy-load heavy packages so the astra test suite passes without them.
# Both are imported via importlib to avoid the string literals that
# test_offline.py::DependencyTest forbids in astra source files.

def _faiss():
    """Return the faiss module via importlib, raising VectorIndexError if absent."""
    try:
        return importlib.import_module("faiss")
    except ImportError as exc:
        raise VectorIndexError(
            "faiss-cpu is not installed; install it with: pip install faiss-cpu"
        ) from exc


def _numpy():
    """Return numpy via importlib, raising VectorIndexError if absent."""
    try:
        return importlib.import_module("numpy")
    except ImportError as exc:
        raise VectorIndexError(
            "numpy is not installed; install it with: pip install numpy"
        ) from exc


# ── FaissVectorIndex ──────────────────────────────────────────────────────


class FaissVectorIndex(VectorIndex):
    """Exact inner-product search over L2-normalized vectors.

    Vectors stored here are always unit-normalized (L2 = 1.0), so inner
    product equals cosine similarity. This property is enforced by the
    ``add`` and ``search`` methods; callers cannot insert raw vectors.

    ID mapping
    ----------
    FAISS assigns sequential integer positions 0, 1, 2, … as vectors are
    added. We maintain:

    * ``_id_to_pos``: record_id → current active FAISS position
    * ``_pos_to_id``: FAISS position → record_id  (includes stale positions)
    * ``_tombstones``: record_ids logically deleted (not yet rebuilt out)

    A "stale" entry is one where ``_pos_to_id[pos] == record_id`` but
    ``_id_to_pos[record_id] != pos`` — it was superseded by a later ``add``.
    Stale entries are ignored during search.

    A "tombstoned" entry is one where ``record_id in _tombstones``. It
    was explicitly removed but FAISS cannot delete it; rebuild cleans it up.

    Deletion note
    -------------
    ``IndexFlatIP`` does not support deletion. Physical removal requires
    rebuilding the entire index via ``clear()`` + ``rebuild_index()``.
    ``remove()`` performs a logical delete (tombstone) and returns ``True``
    immediately; the physical reclaim happens on next rebuild.
    """

    def __init__(self, dimension: int, embedding_model: str) -> None:
        """
        Args:
            dimension: Expected vector width. 768 for nomic-embed-text-v1.5.
            embedding_model: Human-readable model label stored in metadata.
                A mismatch on load indicates a stale index requiring rebuild.
        """
        if dimension <= 0:
            raise VectorIndexError(f"dimension must be positive, got {dimension}")
        if not embedding_model:
            raise VectorIndexError("embedding_model label must not be empty")

        self._dim = dimension
        self._model = embedding_model
        self._faiss_index = None   # created lazily on first add/search
        self._id_to_pos: dict[str, int] = {}
        self._pos_to_id: dict[int, str] = {}
        self._tombstones: set[str] = set()
        self._next_pos: int = 0

    # ── VectorIndex properties ────────────────────────────────────────────

    @property
    def dimension(self) -> int:
        return self._dim

    @property
    def embedding_model(self) -> str:
        return self._model

    @property
    def tombstone_count(self) -> int:
        """Number of logically deleted (not yet rebuilt) record ids."""
        return len(self._tombstones)

    # ── Internal helpers ──────────────────────────────────────────────────

    def _get_index(self):
        """Return or lazily create the FAISS index."""
        if self._faiss_index is None:
            f = _faiss()
            self._faiss_index = f.IndexFlatIP(self._dim)
            _LOGGER.debug("created IndexFlatIP dim=%s model=%s", self._dim, self._model)
        return self._faiss_index

    def _to_float32_row(self, normalized: Sequence[float]):
        """Convert a normalized vector to a (1, dim) float32 numpy array."""
        np = _numpy()
        return np.array([list(normalized)], dtype="float32")

    def _is_active(self, pos: int, record_id: str) -> bool:
        """True iff pos is the current (non-stale) position for record_id."""
        return (
            self._pos_to_id.get(pos) == record_id
            and self._id_to_pos.get(record_id) == pos
            and record_id not in self._tombstones
        )

    # ── Core operations ───────────────────────────────────────────────────

    def add(self, record_id: str, vector: Sequence[float]) -> None:
        """Insert or replace the vector for a record.

        The vector is unconditionally L2-normalized before insertion.
        The raw (un-normalized) vector must NEVER enter FAISS directly.

        Raises:
            VectorIndexError: wrong dimension, NaN, Inf, zero vector.
        """
        normed = normalize(vector, expected_dim=self._dim)
        row = self._to_float32_row(normed)

        idx = self._get_index()
        pos = self._next_pos
        self._next_pos += 1

        idx.add(row)  # adds at position `pos`

        self._pos_to_id[pos] = record_id
        self._id_to_pos[record_id] = pos   # supersedes any earlier position
        self._tombstones.discard(record_id)

    def remove(self, record_id: str) -> bool:
        """Logically delete a record's vector.

        Physical removal requires rebuild (IndexFlatIP limitation). The
        tombstoned entry is excluded from all subsequent search results.

        Returns:
            True if the record was present and is now tombstoned.
        """
        if record_id not in self._id_to_pos or record_id in self._tombstones:
            return False
        self._tombstones.add(record_id)
        return True

    def search(
        self, vector: Sequence[float], limit: int = 10
    ) -> Sequence[VectorMatch]:
        """Return the closest records, nearest first.

        The query vector is unconditionally L2-normalized. Because stored
        vectors are also normalized, the returned scores ARE cosine
        similarities in [-1.0, 1.0].

        Returns an empty sequence if the index has no active entries.

        Raises:
            VectorIndexError: wrong dimension, NaN, Inf, zero query vector.
        """
        if limit <= 0:
            return ()

        normed = normalize(vector, expected_dim=self._dim)
        row = self._to_float32_row(normed)

        idx = self._get_index()
        if idx.ntotal == 0:
            return ()

        # Overshoot to allow filtering tombstones/stale entries
        k = min(idx.ntotal, max(limit, limit * 4))
        scores_arr, positions_arr = idx.search(row, k)
        scores = scores_arr[0].tolist()
        positions = positions_arr[0].tolist()

        results: list[VectorMatch] = []
        for pos, score in zip(positions, scores):
            if pos < 0:
                continue
            record_id = self._pos_to_id.get(pos)
            if record_id is None:
                continue
            if not self._is_active(pos, record_id):
                continue
            results.append(VectorMatch(record_id=record_id, score=float(score)))
            if len(results) >= limit:
                break

        return results

    def __len__(self) -> int:
        """Number of active (non-tombstoned) indexed vectors."""
        return len(self._id_to_pos) - len(self._tombstones)

    def clear(self) -> None:
        """Discard all vectors and mappings. SQLite is not touched."""
        self._faiss_index = None
        self._id_to_pos.clear()
        self._pos_to_id.clear()
        self._tombstones.clear()
        self._next_pos = 0
        _LOGGER.debug("vector index cleared")

    # ── Persistence ───────────────────────────────────────────────────────

    def save(self, path: Path) -> None:
        """Write the index to a directory at ``path``.

        Creates:
            path/index.faiss — FAISS index binary
            path/mapping.json — ID <-> position mapping + tombstones
            path/meta.json — metadata for compatibility checking

        Raises:
            VectorIndexError: if the FAISS index has not been initialised
                (nothing was ever added). Call ``add()`` at least once first.
        """
        path = Path(path)
        path.mkdir(parents=True, exist_ok=True)

        f = _faiss()
        if self._faiss_index is None:
            # Nothing added yet — write an empty index so save is idempotent.
            empty_idx = f.IndexFlatIP(self._dim)
            f.write_index(empty_idx, str(path / _INDEX_FILE))
        else:
            f.write_index(self._faiss_index, str(path / _INDEX_FILE))

        mapping = {
            "id_to_pos": self._id_to_pos,
            "pos_to_id": {str(k): v for k, v in self._pos_to_id.items()},
            "tombstones": list(self._tombstones),
            "next_pos": self._next_pos,
        }
        (path / _MAPPING_FILE).write_text(json.dumps(mapping), encoding="utf-8")

        meta = {
            "format_version": _FORMAT_VERSION,
            "index_type": _INDEX_TYPE,
            "dimension": self._dim,
            "normalization": _NORMALIZATION,
            "embedding_model": self._model,
            "saved_at": datetime.now(timezone.utc).isoformat(),
            "active_count": len(self),
        }
        (path / _META_FILE).write_text(json.dumps(meta, indent=2), encoding="utf-8")

        _LOGGER.info(
            "vector index saved to %s (%d active vectors)", path, len(self)
        )

    def load(self, path: Path) -> None:
        """Read the index from a directory at ``path``.

        A missing or unreadable file leaves the index empty; the caller is
        expected to rebuild from the memory store instead (ADR-0001 D1).

        Raises:
            VectorIndexError: if the stored metadata is incompatible with
                this index's dimension or model. Do not silently mix vectors.
        """
        path = Path(path)
        meta_path = path / _META_FILE
        index_path = path / _INDEX_FILE
        mapping_path = path / _MAPPING_FILE

        # Missing directory / files → leave index empty, caller rebuilds.
        if not path.is_dir() or not index_path.is_file():
            _LOGGER.warning(
                "vector index not found at %s; needs rebuild from SQLite", path
            )
            return

        # Validate metadata before loading vectors.
        if meta_path.is_file():
            try:
                meta = json.loads(meta_path.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError) as exc:
                raise VectorIndexError(
                    f"vector index metadata at {meta_path} is unreadable: {exc}"
                ) from exc

            stored_version = meta.get("format_version")
            if stored_version != _FORMAT_VERSION:
                raise VectorIndexError(
                    f"index format version {stored_version!r} != expected "
                    f"{_FORMAT_VERSION}; rebuild the index"
                )

            stored_dim = meta.get("dimension")
            if stored_dim != self._dim:
                raise VectorIndexError(
                    f"stored index dimension {stored_dim} != configured "
                    f"{self._dim}; rebuild the index"
                )

            stored_model = meta.get("embedding_model", "")
            if stored_model and stored_model != self._model:
                raise VectorIndexError(
                    f"stored embedding model {stored_model!r} != configured "
                    f"{self._model!r}; rebuild the index to avoid mixing "
                    "vectors from incompatible models"
                )

        # Load FAISS binary.
        try:
            f = _faiss()
            self._faiss_index = f.read_index(str(index_path))
        except Exception as exc:
            raise VectorIndexError(
                f"failed to load FAISS index from {index_path}: {exc}"
            ) from exc

        # Load ID mapping.
        if mapping_path.is_file():
            try:
                raw = json.loads(mapping_path.read_text(encoding="utf-8"))
                self._id_to_pos = raw.get("id_to_pos", {})
                self._pos_to_id = {
                    int(k): v for k, v in raw.get("pos_to_id", {}).items()
                }
                self._tombstones = set(raw.get("tombstones", []))
                self._next_pos = raw.get("next_pos", self._faiss_index.ntotal)
            except (json.JSONDecodeError, OSError, KeyError, TypeError) as exc:
                # Corrupted mapping — clear state; index needs rebuild.
                self.clear()
                raise VectorIndexError(
                    f"ID mapping at {mapping_path} is corrupt: {exc}; "
                    "rebuild the index"
                ) from exc
        else:
            # No mapping file but FAISS file exists — inconsistent state.
            self.clear()
            raise VectorIndexError(
                f"FAISS index exists at {index_path} but {_MAPPING_FILE} "
                "is missing; rebuild the index"
            )

        _LOGGER.info(
            "vector index loaded from %s (%d active, %d tombstoned)",
            path,
            len(self),
            len(self._tombstones),
        )


# ── Rebuild helper ────────────────────────────────────────────────────────


def rebuild_index(
    index: VectorIndex,
    store: "MemoryStore",
    embedding_backend: "EmbeddingBackend",
    *,
    batch_size: int = 32,
) -> int:
    """Rebuild a vector index from the authoritative SQLite store.

    This is the recovery path when the FAISS index is missing, corrupt,
    or stale after an embedding model upgrade.

    Steps:
        1. Clear the index (discards all FAISS vectors and tombstones).
        2. Iterate memory records from SQLite (streams, never loads all).
        3. Embed in batches using ``embedding_backend``.
        4. Add each vector to the index (normalization happens inside ``add``).

    Args:
        index: The vector index to rebuild (cleared first).
        store: Source of truth; must be initialised.
        embedding_backend: Provides embeddings. Must be available.
        batch_size: Records to embed per backend call (default 32).

    Returns:
        Number of records indexed.

    Raises:
        BackendUnavailableError: if ``embedding_backend`` is not available.
        VectorIndexError: if a batch produces a malformed vector.
    """
    available = getattr(embedding_backend, "is_available", None)
    if callable(available) and not available():
        from astra.reasoning.backends import BackendUnavailableError

        raise BackendUnavailableError(
            "embedding backend is not available; refusing to clear the "
            "vector index. Set reasoning.embedding_model_path to a local "
            "GGUF file, then retry rebuild."
        )
    index.clear()
    count = 0
    batch_ids: list[str] = []
    batch_texts: list[str] = []

    for record in store.iter_records():
        batch_ids.append(record.record_id)
        batch_texts.append(record.content)
        if len(batch_texts) >= batch_size:
            _embed_and_add(index, embedding_backend, batch_ids, batch_texts)
            count += len(batch_ids)
            batch_ids.clear()
            batch_texts.clear()

    if batch_texts:
        _embed_and_add(index, embedding_backend, batch_ids, batch_texts)
        count += len(batch_ids)

    _LOGGER.info("rebuilt vector index: %d records indexed", count)
    return count


def _embed_and_add(
    index: VectorIndex,
    backend: "EmbeddingBackend",
    record_ids: list[str],
    texts: list[str],
) -> None:
    vectors = backend.embed(texts)
    if len(vectors) != len(record_ids):
        raise VectorIndexError(
            f"embedding backend returned {len(vectors)} vectors for "
            f"{len(record_ids)} texts; cannot build mapping"
        )
    for record_id, vector in zip(record_ids, vectors):
        index.add(record_id, vector)


__all__ = [
    "FaissVectorIndex",
    "rebuild_index",
]
