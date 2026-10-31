"""B-013 MemoryIndexer tests.

Fake embedding backend only. No GGUF file required. SQLite remains
authoritative; FAISS is derived.
"""

from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

from astra.memory.contracts import MemoryCategory, MemoryRecord, VerificationState
from astra.memory.faiss_index import FaissVectorIndex
from astra.memory.indexer import MemoryIndexer
from astra.memory.sqlite_store import SQLiteMemoryStore
from astra.reasoning.backends import BackendUnavailableError

DIM = 8
MODEL = "test-embed-v1"


def _record(
    content: str,
    *,
    record_id: str = "",
    verification: VerificationState = VerificationState.UNVERIFIED,
) -> MemoryRecord:
    return MemoryRecord(
        record_id=record_id,
        category=MemoryCategory.IDEAS,
        content=content,
        verification=verification,
        source="test",
    )


def _vector_for(text: str) -> list[float]:
    digest = hashlib.sha256(text.encode("utf-8")).digest()
    return [(digest[i % len(digest)] / 255.0) + 0.01 for i in range(DIM)]


class FakeEmbeddingBackend:
    def __init__(self, *, available: bool = True, fail_embed: bool = False) -> None:
        self._available = available
        self._fail_embed = fail_embed
        self.embed_calls: list[list[str]] = []

    @property
    def model_name(self) -> str:
        return MODEL

    @property
    def dimension(self) -> int:
        return DIM

    def is_available(self) -> bool:
        return self._available

    def embed(self, texts):
        self.embed_calls.append(list(texts))
        if not self._available or self._fail_embed:
            raise BackendUnavailableError("fake backend refused embed")
        return [_vector_for(t) for t in texts]


class IndexerTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.store = SQLiteMemoryStore(Path(self._tmp.name) / "mem.db")
        self.store.initialise()
        self.addCleanup(self.store.close)
        self.index = FaissVectorIndex(DIM, MODEL)
        self.backend = FakeEmbeddingBackend()
        self.indexer = MemoryIndexer(self.store, self.index, self.backend)


class EmbedOnWriteTest(IndexerTestCase):
    def test_store_with_backend_indexes_and_sets_provenance(self) -> None:
        rid = self.indexer.store(_record("semantic apple pie"))
        self.assertEqual(1, len(self.index))
        hits = self.indexer.search("semantic apple pie", limit=1)
        self.assertEqual(1, len(hits))
        self.assertEqual(rid, hits[0].record_id)
        prov = self.store.get_embedding_provenance(rid)
        self.assertIsNotNone(prov)
        assert prov is not None
        self.assertEqual(MODEL, prov.embedding_model)
        self.assertEqual(DIM, prov.embedding_dim)

    def test_store_without_backend_succeeds_and_leaves_index_empty(self) -> None:
        self.backend._available = False
        rid = self.indexer.store(_record("unindexed fact"))
        loaded = self.store.get(rid)
        self.assertIsNotNone(loaded)
        self.assertEqual(0, len(self.index))
        self.assertIsNone(self.store.get_embedding_provenance(rid))
        self.assertEqual((), self.indexer.search("unindexed fact"))

    def test_fts_still_works_without_embeddings(self) -> None:
        self.backend._available = False
        self.indexer.store(_record("keyword retrieval still works"))
        hits = self.store.search("keyword retrieval")
        self.assertEqual(1, len(hits))

    def test_embed_failure_after_store_does_not_raise(self) -> None:
        self.backend._fail_embed = True
        rid = self.indexer.store(_record("keep me anyway"))
        self.assertIsNotNone(self.store.get(rid))
        self.assertEqual(0, len(self.index))


class UpdateDeleteTest(IndexerTestCase):
    def test_content_update_replaces_vector(self) -> None:
        rid = self.indexer.store(_record("original text about cats"))
        self.indexer.store(_record("entirely new text about dogs", record_id=rid))
        self.assertEqual(1, len(self.index))
        hits = self.indexer.search("entirely new text about dogs", limit=1)
        self.assertEqual(rid, hits[0].record_id)

    def test_content_update_without_backend_tombstones(self) -> None:
        rid = self.indexer.store(_record("first version"))
        self.assertEqual(1, len(self.index))
        self.backend._available = False
        self.indexer.store(_record("second version", record_id=rid))
        self.assertIsNotNone(self.store.get(rid))
        self.assertEqual(0, len(self.index))
        self.assertEqual(1, self.index.tombstone_count)
        self.assertIsNone(self.store.get_embedding_provenance(rid))

    def test_verification_only_update_leaves_vector(self) -> None:
        rid = self.indexer.store(_record("stable content"))
        calls = len(self.backend.embed_calls)
        self.indexer.store(
            _record(
                "stable content",
                record_id=rid,
                verification=VerificationState.VERIFIED,
            )
        )
        self.assertEqual(calls, len(self.backend.embed_calls))
        self.assertEqual(1, len(self.index))

    def test_delete_tombstones_and_misses_search(self) -> None:
        rid = self.indexer.store(_record("delete me please"))
        self.assertTrue(self.indexer.delete(rid))
        self.assertIsNone(self.store.get(rid))
        self.assertEqual((), self.indexer.search("delete me please"))
        self.assertEqual((), self.store.search("delete me please"))


class RebuildPersistTest(IndexerTestCase):
    def test_rebuild_after_unindexed_rows(self) -> None:
        self.backend._available = False
        rid = self.indexer.store(_record("backfill candidate"))
        self.assertEqual(0, len(self.index))
        self.backend._available = True
        count = self.indexer.rebuild()
        self.assertEqual(1, count)
        self.assertEqual(1, len(self.index))
        prov = self.store.get_embedding_provenance(rid)
        self.assertIsNotNone(prov)

    def test_rebuild_unavailable_does_not_clear(self) -> None:
        self.indexer.store(_record("must survive"))
        self.assertEqual(1, len(self.index))
        self.backend._available = False
        with self.assertRaises(BackendUnavailableError):
            self.indexer.rebuild()
        self.assertEqual(1, len(self.index))

    def test_persist_and_load_round_trip(self) -> None:
        rid = self.indexer.store(_record("persisted memory"))
        dest = Path(self._tmp.name) / "idx"
        self.indexer.persist(dest)
        fresh = FaissVectorIndex(DIM, MODEL)
        fresh.load(dest)
        self.assertEqual(1, len(fresh))
        backend = FakeEmbeddingBackend()
        loaded = MemoryIndexer(self.store, fresh, backend)
        hits = loaded.search("persisted memory", limit=1)
        self.assertEqual(rid, hits[0].record_id)


class IndexerImportBoundaryTest(unittest.TestCase):
    def test_indexer_source_has_no_faiss_numpy_llama(self) -> None:
        source = Path(__file__).parent.parent / "astra" / "memory" / "indexer.py"
        text = source.read_text(encoding="utf-8")
        for forbidden in ("import faiss", "import numpy", "import llama_cpp", "httpx"):
            self.assertNotIn(forbidden, text)


if __name__ == "__main__":
    unittest.main()
