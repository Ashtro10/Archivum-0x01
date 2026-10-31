"""Memory semantic indexer (B-013, ADR-0012).

Wires the durable SQLite store to a derived vector index and an embedding
backend. SQLite is the source of truth; FAISS is disposable.

This module imports embedding and vector **interfaces** only. It must not
import llama.cpp, FAISS, or NumPy.
"""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING, Sequence

from astra.logging import get_logger
from astra.memory.contracts import MemoryRecord
from astra.memory.user_facts import (
    filter_user_durable,
    query_field_key,
    record_field_key,
    score_user_fact,
)

if TYPE_CHECKING:
    from astra.memory.contracts import MemoryStore
    from astra.memory.vector import VectorIndex, VectorMatch
    from astra.reasoning.backends import EmbeddingBackend

_LOGGER = get_logger(__name__)

# Rough chars-per-token for the overflow warning (D9). Not a tokenizer.
_CHARS_PER_TOKEN_ESTIMATE = 4


class MemoryIndexer:
    """Embed-on-write adapter over a :class:`MemoryStore` and :class:`VectorIndex`.

    Storage always goes to SQLite first. Indexing is best-effort (ADR-0012 D2).
    """

    def __init__(
        self,
        store: "MemoryStore",
        index: "VectorIndex",
        backend: "EmbeddingBackend",
        *,
        embedding_context_tokens: int = 2048,
    ) -> None:
        self._store = store
        self._index = index
        self._backend = backend
        self._ctx = embedding_context_tokens
        self._last_persist_iso: str = ""

    @property
    def last_persist_iso(self) -> str:
        return self._last_persist_iso

    def backend_available(self) -> bool:
        """Return whether the embedding backend can run. Never raises."""
        try:
            return bool(self._backend.is_available())
        except Exception:
            return False

    def store(self, record: MemoryRecord) -> str:
        """Persist ``record`` to SQLite, then embed if the backend is available."""
        prior = None
        if record.record_id:
            try:
                prior = self._store.get(record.record_id)
            except Exception:
                prior = None

        record_id = self._store.store(record)
        stored = self._store.get(record_id)
        if stored is None:
            return record_id

        content_changed = prior is None or stored.content != prior.content
        if not content_changed:
            return record_id

        if self.backend_available():
            try:
                self._index_record(stored)
            except Exception as exc:  # noqa: BLE001
                _LOGGER.warning(
                    "embed-on-write failed for %s (%s); SQLite write kept",
                    record_id,
                    exc,
                )
                self._drop_derived(record_id)
        elif prior is not None:
            self._drop_derived(record_id)

        return record_id

    def delete(self, record_id: str) -> bool:
        """Delete the SQLite row, then tombstone the derived vector."""
        removed = self._store.delete(record_id)
        if removed:
            try:
                self._index.remove(record_id)
            except Exception as exc:  # noqa: BLE001
                _LOGGER.warning(
                    "index remove failed after deleting %s (%s); rebuild will recover",
                    record_id,
                    exc,
                )
        return removed

    def search(self, query: str, *, limit: int = 10) -> Sequence[MemoryRecord]:
        """Semantic search. Returns ``()`` if embeddings are unavailable."""
        return tuple(record for record, _score in self.search_with_scores(query, limit=limit))

    def search_with_scores(
        self, query: str, *, limit: int = 10
    ) -> Sequence[tuple[MemoryRecord, float]]:
        """Semantic search with similarity scores (cosine / inner product)."""
        if not query.strip() or not self.backend_available():
            return ()
        try:
            vectors = self._backend.embed([query])
            if not vectors:
                return ()
            matches: Sequence["VectorMatch"] = self._index.search(vectors[0], limit=limit)
        except Exception as exc:  # noqa: BLE001
            _LOGGER.warning("semantic search failed: %s", exc)
            return ()

        results: list[tuple[MemoryRecord, float]] = []
        for match in matches:
            record = self._store.get(match.record_id)
            if record is not None:
                results.append((record, float(match.score)))
        return tuple(results)

    def search_user_facts(
        self,
        query: str,
        *,
        field_key: str | None = None,
        limit: int = 5,
    ) -> Sequence[MemoryRecord]:
        """Return only explicit user memories relevant to ``query``.

        Semantic retrieval is used when the embedding backend is available, but
        the final answer set is always filtered through the durable-user boundary
        and re-ranked with field awareness.  A SQLite lexical fallback keeps
        personal-memory retrieval working when embeddings are unavailable.
        """
        if limit <= 0 or not str(query).strip():
            return ()
        requested = field_key or query_field_key(query)
        candidates: dict[str, MemoryRecord] = {}

        if self.backend_available():
            try:
                for record, _score in self.search_with_scores(query, limit=max(limit * 8, 32)):
                    if record.record_id and record.record_id not in candidates:
                        candidates[record.record_id] = record
            except Exception as exc:  # noqa: BLE001
                _LOGGER.debug("semantic user-memory retrieval failed: %s", exc)

        for record in filter_user_durable(
            self._store.search_user_durable(query, field_key=requested, limit=max(limit * 8, 32))
            if hasattr(self._store, "search_user_durable")
            else ()
        ):
            candidates.setdefault(record.record_id, record)

        # If an exact field exists, it must dominate unrelated user memories.
        if requested:
            exact = {
                rid: record
                for rid, record in candidates.items()
                if record_field_key(record) == requested
            }
            if exact:
                candidates = exact

        ranked = sorted(
            candidates.values(),
            key=lambda record: score_user_fact(query, record, requested),
            reverse=True,
        )
        return tuple(ranked[:limit])

    def rebuild(self) -> int:
        """Rebuild the derived index from SQLite. Does not persist to disk."""
        from astra.memory.faiss_index import rebuild_index

        count = rebuild_index(self._index, self._store, self._backend)
        self._write_all_provenance()
        return count

    def persist(self, path: Path) -> None:
        """Save the derived index to ``path`` (a directory)."""
        self._index.save(Path(path))
        self._last_persist_iso = datetime.now(timezone.utc).isoformat()

    def _index_record(self, record: MemoryRecord) -> None:
        if self._ctx > 0 and len(record.content) > self._ctx * _CHARS_PER_TOKEN_ESTIMATE:
            _LOGGER.warning(
                "memory record %s exceeds embedding context estimate "
                "(%s chars, context_tokens=%s); backend may truncate; not chunking",
                record.record_id,
                len(record.content),
                self._ctx,
            )
        vectors = self._backend.embed([record.content])
        if not vectors:
            raise RuntimeError("embedding backend returned no vectors")
        vector = vectors[0]
        self._index.add(record.record_id, vector)
        self._write_provenance(record.record_id, len(vector))

    def _write_provenance(self, record_id: str, dim: int) -> None:
        setter = getattr(self._store, "set_embedding_provenance", None)
        if not callable(setter):
            return
        model = str(getattr(self._backend, "model_name", "") or "")
        setter(record_id, model, model, dim, datetime.now(timezone.utc))

    def _write_all_provenance(self) -> None:
        setter = getattr(self._store, "set_embedding_provenance", None)
        if not callable(setter):
            return
        model = str(getattr(self._backend, "model_name", "") or "")
        dim = self._index.dimension
        now = datetime.now(timezone.utc)
        for record in self._store.iter_records():
            try:
                setter(record.record_id, model, model, dim, now)
            except Exception as exc:  # noqa: BLE001
                _LOGGER.warning(
                    "provenance write failed for %s after rebuild: %s",
                    record.record_id,
                    exc,
                )

    def _drop_derived(self, record_id: str) -> None:
        try:
            self._index.remove(record_id)
        except Exception as exc:  # noqa: BLE001
            _LOGGER.warning("failed to tombstone %s: %s", record_id, exc)
        clearer = getattr(self._store, "clear_embedding_provenance", None)
        if callable(clearer):
            try:
                clearer(record_id)
            except Exception as exc:  # noqa: BLE001
                _LOGGER.warning("failed to clear provenance for %s: %s", record_id, exc)


__all__ = ["MemoryIndexer"]
