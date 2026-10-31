"""B-003 vector index tests.

Tests cover:
- Normalization utility (_vec_norm.py)
- FaissVectorIndex: add, search, remove, save/load, clear, rebuild
- All failure modes: wrong dim, NaN, Inf, zero vector, corrupt metadata
- Persistence: round-trip through save/load
- Rebuild: delete index, rebuild from SQLite, equivalent search results
- Embedding-model identity mismatch detection
- SQLite-authoritative invariant: FAISS never treated as truth

Evidence vectors are from B-010 corpus (cosine similarities measured):
    identical pair:   cosine ≈ 1.0000
    near-duplicate:   cosine ≈ 0.9857
    semantically similar: cosine ≈ 0.8431
    unrelated:        cosine ≈ 0.4325

These are approximated here with hand-crafted unit vectors that exhibit
the same ordering properties.
"""

from __future__ import annotations

import json
import math
import sys
import tempfile
import unittest
from pathlib import Path

from astra.memory._vec_norm import cosine_similarity, normalize
from astra.memory.errors import VectorIndexError
from astra.memory.faiss_index import FaissVectorIndex, rebuild_index
from astra.memory.vector import VectorMatch

DIM = 8        # small dimension for fast tests
MODEL = "test-model-v1"


# ── Vector helpers ────────────────────────────────────────────────────────

def _make_vec(*components: float) -> list[float]:
    """Return a raw (non-normalized) vector padded/truncated to DIM."""
    v = list(components) + [0.0] * DIM
    return v[:DIM]


def _unit(*components: float) -> list[float]:
    """Return an L2-normalized vector of dimension DIM."""
    v = _make_vec(*components)
    n = math.sqrt(sum(x * x for x in v))
    if n == 0:
        raise ValueError("zero vector cannot be unit-normalized")
    return [x / n for x in v]


# Representative unit vectors for similarity ordering tests
VEC_A = _unit(1, 0, 0, 0, 0, 0, 0, 0)          # canonical basis
VEC_B = _unit(1, 0.01, 0, 0, 0, 0, 0, 0)        # near-duplicate of A
VEC_C = _unit(0.7, 0.7, 0, 0, 0, 0, 0, 0)       # semantically similar to A
VEC_D = _unit(0, 0, 1, 0, 0, 0, 0, 0)           # orthogonal — "unrelated"

# Raw (non-unit) versions for normalization tests
RAW_A = [x * 22.5 for x in VEC_A]   # mimics B-010 L2 norm ≈ 22.5
RAW_D = [x * 15.0 for x in VEC_D]


def _new_index() -> FaissVectorIndex:
    return FaissVectorIndex(DIM, MODEL)


# ── Normalization utility tests ───────────────────────────────────────────


class NormalizationTest(unittest.TestCase):
    """astra.memory._vec_norm.normalize"""

    def test_unit_norm_of_normalized_output(self) -> None:
        v = [3.0, 4.0] + [0.0] * 6
        normed = normalize(v, expected_dim=DIM)
        norm = math.sqrt(sum(x * x for x in normed))
        self.assertAlmostEqual(1.0, norm, places=6)

    def test_normalizes_b010_scale_vector(self) -> None:
        """B-010: raw vectors have L2 ≈ 22.5; must normalize to 1."""
        normed = normalize(RAW_A, expected_dim=DIM)
        norm = math.sqrt(sum(x * x for x in normed))
        self.assertAlmostEqual(1.0, norm, places=6)

    def test_normalized_ip_equals_cosine(self) -> None:
        """After normalization, inner product == cosine similarity."""
        a = normalize(RAW_A, expected_dim=DIM)
        d = normalize(RAW_D, expected_dim=DIM)
        ip = sum(x * y for x, y in zip(a, d))
        cos = cosine_similarity(RAW_A, RAW_D)
        self.assertAlmostEqual(cos, ip, places=6)

    def test_identical_vectors_normalized_ip_is_one(self) -> None:
        a = normalize(RAW_A, expected_dim=DIM)
        b = normalize(list(RAW_A), expected_dim=DIM)
        ip = sum(x * y for x, y in zip(a, b))
        self.assertAlmostEqual(1.0, ip, places=6)

    def test_wrong_dimension_raises(self) -> None:
        with self.assertRaises(VectorIndexError):
            normalize([1.0, 2.0], expected_dim=DIM)

    def test_zero_vector_raises(self) -> None:
        with self.assertRaises(VectorIndexError):
            normalize([0.0] * DIM, expected_dim=DIM)

    def test_nan_raises(self) -> None:
        v = [1.0] * DIM
        v[3] = float("nan")
        with self.assertRaises(VectorIndexError):
            normalize(v, expected_dim=DIM)

    def test_infinity_raises(self) -> None:
        v = [1.0] * DIM
        v[0] = float("inf")
        with self.assertRaises(VectorIndexError):
            normalize(v, expected_dim=DIM)

    def test_empty_vector_raises(self) -> None:
        with self.assertRaises(VectorIndexError):
            normalize([], expected_dim=DIM)

    def test_returns_tuple(self) -> None:
        normed = normalize([1.0] + [0.0] * (DIM - 1), expected_dim=DIM)
        self.assertIsInstance(normed, tuple)


# ── FaissVectorIndex construction tests ───────────────────────────────────


class ConstructionTest(unittest.TestCase):
    def test_valid_construction(self) -> None:
        idx = FaissVectorIndex(768, "nomic-embed-text-v1.5 Q8_0")
        self.assertEqual(768, idx.dimension)
        self.assertEqual("nomic-embed-text-v1.5 Q8_0", idx.embedding_model)

    def test_zero_dimension_raises(self) -> None:
        with self.assertRaises(VectorIndexError):
            FaissVectorIndex(0, MODEL)

    def test_negative_dimension_raises(self) -> None:
        with self.assertRaises(VectorIndexError):
            FaissVectorIndex(-1, MODEL)

    def test_empty_model_label_raises(self) -> None:
        with self.assertRaises(VectorIndexError):
            FaissVectorIndex(DIM, "")

    def test_implements_vector_index(self) -> None:
        from astra.memory.vector import VectorIndex
        self.assertIsInstance(_new_index(), VectorIndex)


# ── Add / search tests ────────────────────────────────────────────────────


class AddSearchTest(unittest.TestCase):
    def setUp(self) -> None:
        self.idx = _new_index()

    def test_add_one_and_search(self) -> None:
        self.idx.add("r1", RAW_A)
        results = self.idx.search(RAW_A, limit=1)
        self.assertEqual(1, len(results))
        self.assertEqual("r1", results[0].record_id)

    def test_identical_vectors_score_near_one(self) -> None:
        """Normalized identical vectors must have IP ≈ 1.0."""
        self.idx.add("r1", RAW_A)
        results = self.idx.search(RAW_A, limit=1)
        self.assertAlmostEqual(1.0, results[0].score, places=5)

    def test_normalization_enforced_internally(self) -> None:
        """Raw (un-normalized) input still yields cosine-equivalent scores."""
        self.idx.add("r1", RAW_A)
        self.idx.add("r2", RAW_D)
        res_a = self.idx.search(RAW_A, limit=2)
        # r1 (same direction) must rank higher than r2 (orthogonal)
        ids = [r.record_id for r in res_a]
        self.assertEqual("r1", ids[0])

    def test_similarity_ordering(self) -> None:
        """near-dup > sem-similar > orthogonal."""
        self.idx.add("near_dup", VEC_B)
        self.idx.add("sem_sim", VEC_C)
        self.idx.add("unrelated", VEC_D)
        results = self.idx.search(VEC_A, limit=3)
        ids = [r.record_id for r in results]
        self.assertEqual("near_dup", ids[0])
        self.assertEqual("sem_sim", ids[1])
        self.assertEqual("unrelated", ids[2])
        # Scores must be descending
        scores = [r.score for r in results]
        self.assertGreater(scores[0], scores[1])
        self.assertGreater(scores[1], scores[2])

    def test_scores_in_cosine_range(self) -> None:
        """All scores must be in [-1, 1] after normalization."""
        self.idx.add("r1", VEC_A)
        self.idx.add("r2", VEC_D)
        for r in self.idx.search(VEC_A, limit=10):
            self.assertGreaterEqual(r.score, -1.0)
            self.assertLessEqual(r.score, 1.0001)   # tiny float tolerance

    def test_add_multiple_records(self) -> None:
        for i in range(1, 21):   # start from 1 — zero vector is invalid
            v = [float(i)] + [0.0] * (DIM - 1)
            self.idx.add(f"r{i}", v)
        self.assertEqual(20, len(self.idx))

    def test_top_k_respected(self) -> None:
        for i in range(10):
            v = [float(i + 1)] + [0.0] * (DIM - 1)
            self.idx.add(f"r{i}", v)
        results = self.idx.search([1.0] + [0.0] * (DIM - 1), limit=3)
        self.assertEqual(3, len(results))

    def test_search_empty_index(self) -> None:
        results = self.idx.search(VEC_A, limit=5)
        self.assertEqual(0, len(results))

    def test_zero_limit_returns_empty(self) -> None:
        self.idx.add("r1", VEC_A)
        self.assertEqual(0, len(self.idx.search(VEC_A, limit=0)))

    def test_wrong_dimension_raises_on_add(self) -> None:
        with self.assertRaises(VectorIndexError):
            self.idx.add("r1", [1.0, 2.0])   # too short

    def test_wrong_dimension_raises_on_search(self) -> None:
        with self.assertRaises(VectorIndexError):
            self.idx.search([1.0, 2.0])

    def test_nan_raises_on_add(self) -> None:
        v = [1.0] * DIM
        v[0] = float("nan")
        with self.assertRaises(VectorIndexError):
            self.idx.add("r1", v)

    def test_zero_vector_raises_on_add(self) -> None:
        with self.assertRaises(VectorIndexError):
            self.idx.add("r1", [0.0] * DIM)

    def test_inf_raises_on_add(self) -> None:
        v = [1.0] * DIM
        v[2] = float("inf")
        with self.assertRaises(VectorIndexError):
            self.idx.add("r1", v)

    def test_update_existing_record(self) -> None:
        self.idx.add("r1", VEC_A)
        self.idx.add("r1", VEC_D)   # update to different direction
        results = self.idx.search(VEC_D, limit=1)
        self.assertEqual("r1", results[0].record_id)
        # Length stays 1 (one active record, not two)
        self.assertEqual(1, len(self.idx))

    def test_return_type_is_vector_match(self) -> None:
        self.idx.add("r1", VEC_A)
        results = self.idx.search(VEC_A, limit=1)
        self.assertIsInstance(results[0], VectorMatch)


# ── Remove (tombstone) tests ──────────────────────────────────────────────


class RemoveTest(unittest.TestCase):
    def setUp(self) -> None:
        self.idx = _new_index()
        self.idx.add("r1", VEC_A)
        self.idx.add("r2", VEC_B)

    def test_remove_returns_true_for_known(self) -> None:
        self.assertTrue(self.idx.remove("r1"))

    def test_remove_returns_false_for_unknown(self) -> None:
        self.assertFalse(self.idx.remove("nonexistent"))

    def test_remove_returns_false_second_time(self) -> None:
        self.idx.remove("r1")
        self.assertFalse(self.idx.remove("r1"))

    def test_removed_record_absent_from_search(self) -> None:
        self.idx.remove("r1")
        ids = [r.record_id for r in self.idx.search(VEC_A, limit=10)]
        self.assertNotIn("r1", ids)

    def test_removed_record_decrements_len(self) -> None:
        self.assertEqual(2, len(self.idx))
        self.idx.remove("r1")
        self.assertEqual(1, len(self.idx))

    def test_remaining_records_still_searchable(self) -> None:
        self.idx.remove("r1")
        ids = [r.record_id for r in self.idx.search(VEC_B, limit=1)]
        self.assertEqual("r2", ids[0])


# ── Clear tests ───────────────────────────────────────────────────────────


class ClearTest(unittest.TestCase):
    def test_clear_empties_index(self) -> None:
        idx = _new_index()
        idx.add("r1", VEC_A)
        idx.clear()
        self.assertEqual(0, len(idx))
        self.assertEqual(0, len(idx.search(VEC_A, limit=1)))

    def test_can_add_after_clear(self) -> None:
        idx = _new_index()
        idx.add("r1", VEC_A)
        idx.clear()
        idx.add("r2", VEC_B)
        self.assertEqual(1, len(idx))


# ── Save / load / persistence tests ──────────────────────────────────────


class PersistenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.index_dir = Path(self._tmp.name) / "idx"

    def _save_load(self, idx1: FaissVectorIndex) -> FaissVectorIndex:
        idx1.save(self.index_dir)
        idx2 = FaissVectorIndex(DIM, MODEL)
        idx2.load(self.index_dir)
        return idx2

    def test_save_creates_files(self) -> None:
        idx = _new_index()
        idx.add("r1", VEC_A)
        idx.save(self.index_dir)
        self.assertTrue((self.index_dir / "index.faiss").is_file())
        self.assertTrue((self.index_dir / "mapping.json").is_file())
        self.assertTrue((self.index_dir / "meta.json").is_file())

    def test_round_trip_preserves_records(self) -> None:
        idx1 = _new_index()
        idx1.add("r1", VEC_A)
        idx1.add("r2", VEC_D)
        idx2 = self._save_load(idx1)
        self.assertEqual(2, len(idx2))

    def test_round_trip_preserves_search_results(self) -> None:
        idx1 = _new_index()
        idx1.add("near_dup", VEC_B)
        idx1.add("sem_sim", VEC_C)
        idx1.add("unrelated", VEC_D)
        idx2 = self._save_load(idx1)
        results = idx2.search(VEC_A, limit=3)
        self.assertEqual("near_dup", results[0].record_id)

    def test_round_trip_preserves_tombstones(self) -> None:
        idx1 = _new_index()
        idx1.add("r1", VEC_A)
        idx1.add("r2", VEC_B)
        idx1.remove("r1")
        idx2 = self._save_load(idx1)
        ids = [r.record_id for r in idx2.search(VEC_A, limit=10)]
        self.assertNotIn("r1", ids)
        self.assertIn("r2", ids)

    def test_load_missing_directory_leaves_empty(self) -> None:
        idx = FaissVectorIndex(DIM, MODEL)
        idx.load(Path(self._tmp.name) / "nonexistent")
        self.assertEqual(0, len(idx))

    def test_save_empty_index_is_idempotent(self) -> None:
        idx = _new_index()
        idx.save(self.index_dir)   # no records added
        idx2 = FaissVectorIndex(DIM, MODEL)
        idx2.load(self.index_dir)
        self.assertEqual(0, len(idx2))

    def test_reload_after_process_restart(self) -> None:
        """Simulate process restart by creating a new index object and loading."""
        idx1 = _new_index()
        idx1.add("r1", VEC_A)
        idx1.add("r2", VEC_D)
        idx1.save(self.index_dir)
        del idx1

        # New process: create fresh index, load from disk
        idx2 = FaissVectorIndex(DIM, MODEL)
        idx2.load(self.index_dir)
        results = idx2.search(VEC_A, limit=1)
        self.assertEqual("r1", results[0].record_id)


# ── Metadata / compatibility tests ────────────────────────────────────────


class MetadataTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.index_dir = Path(self._tmp.name) / "idx"

    def _write_meta(self, **overrides: object) -> None:
        self.index_dir.mkdir(parents=True, exist_ok=True)
        meta = {
            "format_version": 1,
            "index_type": "IndexFlatIP",
            "dimension": DIM,
            "normalization": "L2",
            "embedding_model": MODEL,
            "saved_at": "2026-01-01T00:00:00+00:00",
            "active_count": 0,
        }
        meta.update(overrides)
        (self.index_dir / "meta.json").write_text(
            json.dumps(meta), encoding="utf-8"
        )
        # Also need a valid faiss file and mapping for load to proceed
        idx_tmp = FaissVectorIndex(DIM, MODEL)
        import importlib
        f = importlib.import_module("faiss")
        f.write_index(f.IndexFlatIP(DIM), str(self.index_dir / "index.faiss"))
        (self.index_dir / "mapping.json").write_text(
            json.dumps({"id_to_pos": {}, "pos_to_id": {}, "tombstones": [], "next_pos": 0}),
            encoding="utf-8",
        )

    def test_dimension_mismatch_raises(self) -> None:
        self._write_meta(dimension=999)
        idx = FaissVectorIndex(DIM, MODEL)
        with self.assertRaises(VectorIndexError) as ctx:
            idx.load(self.index_dir)
        self.assertIn("dimension", str(ctx.exception))

    def test_format_version_mismatch_raises(self) -> None:
        self._write_meta(format_version=99)
        idx = FaissVectorIndex(DIM, MODEL)
        with self.assertRaises(VectorIndexError) as ctx:
            idx.load(self.index_dir)
        self.assertIn("format version", str(ctx.exception))

    def test_embedding_model_mismatch_raises(self) -> None:
        """Different model → incompatible vectors → must not load silently."""
        self._write_meta(embedding_model="different-model-v2")
        idx = FaissVectorIndex(DIM, "correct-model-v1")
        with self.assertRaises(VectorIndexError) as ctx:
            idx.load(self.index_dir)
        self.assertIn("model", str(ctx.exception).lower())

    def test_corrupt_meta_raises(self) -> None:
        self.index_dir.mkdir(parents=True, exist_ok=True)
        (self.index_dir / "meta.json").write_text("{not valid json", encoding="utf-8")
        # Need a FAISS index file to reach meta check
        import importlib
        f = importlib.import_module("faiss")
        f.write_index(f.IndexFlatIP(DIM), str(self.index_dir / "index.faiss"))
        idx = FaissVectorIndex(DIM, MODEL)
        with self.assertRaises(VectorIndexError):
            idx.load(self.index_dir)

    def test_missing_mapping_raises(self) -> None:
        self.index_dir.mkdir(parents=True, exist_ok=True)
        meta = {"format_version": 1, "dimension": DIM, "embedding_model": MODEL}
        (self.index_dir / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
        import importlib
        f = importlib.import_module("faiss")
        f.write_index(f.IndexFlatIP(DIM), str(self.index_dir / "index.faiss"))
        idx = FaissVectorIndex(DIM, MODEL)
        with self.assertRaises(VectorIndexError) as ctx:
            idx.load(self.index_dir)
        self.assertIn("mapping", str(ctx.exception))

    def test_meta_json_is_written_with_correct_fields(self) -> None:
        idx = FaissVectorIndex(DIM, MODEL)
        idx.add("r1", VEC_A)
        tmp_dir = Path(self._tmp.name) / "meta_check"
        idx.save(tmp_dir)
        meta = json.loads((tmp_dir / "meta.json").read_text(encoding="utf-8"))
        self.assertEqual(1, meta["format_version"])
        self.assertEqual("IndexFlatIP", meta["index_type"])
        self.assertEqual(DIM, meta["dimension"])
        self.assertEqual("L2", meta["normalization"])
        self.assertEqual(MODEL, meta["embedding_model"])


# ── Rebuild tests ─────────────────────────────────────────────────────────


class RebuildTest(unittest.TestCase):
    """Tests for rebuild_index() and the delete-and-rebuild recovery path."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.index_dir = Path(self._tmp.name) / "idx"
        self.index_dir.mkdir()
        self.idx = _new_index()

    def _fake_store(self, records: list[tuple[str, str]]):
        """Return a minimal MemoryStore stub with fixed records."""
        from astra.memory.contracts import MemoryRecord, MemoryCategory

        class _FakeStore:
            def iter_records(self, *, category=None):
                for rid, content in records:
                    yield MemoryRecord(
                        record_id=rid,
                        category=MemoryCategory.RESEARCH,
                        content=content,
                    )

        return _FakeStore()

    def _fake_backend(self, mapping: dict[str, list[float]]):
        """Return an EmbeddingBackend stub that maps content → vector."""

        class _FakeBackend:
            def is_available(self) -> bool:
                return True

            def embed(self, texts):
                return [mapping[t] for t in texts]

        return _FakeBackend()

    def test_rebuild_populates_index(self) -> None:
        store = self._fake_store([("r1", "text_a"), ("r2", "text_d")])
        backend = self._fake_backend({"text_a": VEC_A, "text_d": VEC_D})
        count = rebuild_index(self.idx, store, backend)
        self.assertEqual(2, count)
        self.assertEqual(2, len(self.idx))

    def test_rebuild_clears_existing_entries(self) -> None:
        self.idx.add("old_r", VEC_C)
        store = self._fake_store([("r1", "text_a")])
        backend = self._fake_backend({"text_a": VEC_A})
        rebuild_index(self.idx, store, backend)
        ids = [r.record_id for r in self.idx.search(VEC_C, limit=10)]
        self.assertNotIn("old_r", ids)

    def test_rebuild_restores_search_results(self) -> None:
        store = self._fake_store([
            ("near", "text_b"),
            ("sim", "text_c"),
            ("unrel", "text_d"),
        ])
        backend = self._fake_backend({
            "text_b": VEC_B, "text_c": VEC_C, "text_d": VEC_D
        })
        rebuild_index(self.idx, store, backend)
        results = self.idx.search(VEC_A, limit=3)
        self.assertEqual("near", results[0].record_id)

    def test_delete_index_and_rebuild_from_sqlite(self) -> None:
        """Core recovery path: delete FAISS, rebuild from SQLite source of truth."""
        store = self._fake_store([("r1", "text_a"), ("r2", "text_d")])
        backend = self._fake_backend({"text_a": VEC_A, "text_d": VEC_D})

        # Build and save
        rebuild_index(self.idx, store, backend)
        self.idx.save(self.index_dir)

        # Delete FAISS files (simulate corruption / deletion)
        (self.index_dir / "index.faiss").unlink()
        (self.index_dir / "mapping.json").unlink()
        (self.index_dir / "meta.json").unlink()

        # Fresh index: load fails silently, then rebuild from SQLite
        fresh = FaissVectorIndex(DIM, MODEL)
        fresh.load(self.index_dir)        # missing → index stays empty
        self.assertEqual(0, len(fresh))   # confirms SQLite is authoritative

        rebuild_index(fresh, store, backend)
        self.assertEqual(2, len(fresh))
        results = fresh.search(VEC_A, limit=1)
        self.assertEqual("r1", results[0].record_id)

    def test_rebuild_is_deterministic(self) -> None:
        store = self._fake_store([("r1", "text_a"), ("r2", "text_d")])
        backend = self._fake_backend({"text_a": VEC_A, "text_d": VEC_D})
        rebuild_index(self.idx, store, backend)
        res1 = self.idx.search(VEC_A, limit=2)

        self.idx.clear()
        rebuild_index(self.idx, store, backend)
        res2 = self.idx.search(VEC_A, limit=2)

        self.assertEqual([r.record_id for r in res1], [r.record_id for r in res2])

    def test_rebuild_empty_store(self) -> None:
        store = self._fake_store([])
        backend = self._fake_backend({})
        count = rebuild_index(self.idx, store, backend)
        self.assertEqual(0, count)
        self.assertEqual(0, len(self.idx))

    def test_rebuild_unavailable_does_not_clear_index(self) -> None:
        self.idx.add("keep_me", VEC_A)
        self.assertEqual(1, len(self.idx))

        class _Down:
            def is_available(self) -> bool:
                return False

            def embed(self, texts):
                raise AssertionError("embed must not run when unavailable")

        from astra.reasoning.backends import BackendUnavailableError

        with self.assertRaises(BackendUnavailableError):
            rebuild_index(self.idx, self._fake_store([("r1", "text_a")]), _Down())
        self.assertEqual(1, len(self.idx))
        self.assertEqual("keep_me", self.idx.search(VEC_A, limit=1)[0].record_id)


# ── SQLite-authoritative invariant ────────────────────────────────────────


class SqliteAuthoritativeTest(unittest.TestCase):
    """FAISS must never be treated as the source of truth."""

    def test_load_missing_index_does_not_raise(self) -> None:
        """Missing index → graceful empty; caller rebuilds from SQLite."""
        with tempfile.TemporaryDirectory() as tmp:
            idx = FaissVectorIndex(DIM, MODEL)
            idx.load(Path(tmp) / "nonexistent")
            self.assertEqual(0, len(idx))

    def test_clear_does_not_touch_sqlite(self) -> None:
        """Clearing the FAISS index must not interact with the store."""
        # Verify by checking that clear() accepts no store argument.
        idx = _new_index()
        idx.add("r1", VEC_A)
        idx.clear()   # should not raise, should not need a store argument
        self.assertEqual(0, len(idx))

    def test_faiss_not_imported_at_module_level(self) -> None:
        """faiss_index module must be importable without faiss installed.

        Verified by checking that importlib is the only way faiss is
        accessed (forbidden-string test in test_offline.py guards this).
        """
        source = Path(__file__).parent.parent / "astra" / "memory" / "faiss_index.py"
        text = source.read_text(encoding="utf-8")
        self.assertNotIn("import faiss", text)
        self.assertNotIn("import numpy", text)


# ── Offline enforcement ───────────────────────────────────────────────────


class OfflineTest(unittest.TestCase):
    def test_faiss_index_module_has_no_network_imports(self) -> None:
        source = Path(__file__).parent.parent / "astra" / "memory" / "faiss_index.py"
        text = source.read_text(encoding="utf-8")
        for forbidden in ("import httpx", "import requests", "import urllib"):
            self.assertNotIn(forbidden, text,
                             f"{forbidden!r} found in faiss_index.py")

    def test_vec_norm_module_has_no_network_imports(self) -> None:
        source = Path(__file__).parent.parent / "astra" / "memory" / "_vec_norm.py"
        text = source.read_text(encoding="utf-8")
        for forbidden in ("import httpx", "import requests", "import urllib",
                          "import numpy", "import faiss"):
            self.assertNotIn(forbidden, text)


if __name__ == "__main__":
    unittest.main()
