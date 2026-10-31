"""B-010 embedding verification tests.

These tests document and enforce the empirically verified properties of
nomic-embed-text-v1.5 Q8_0, measured on 2026-08-14.

Tests are fast (no model loaded). They verify:
- That the verification results JSON exists and contains the measured values.
- That architectural constraints derived from the measurements are correct.
- That the presentation layer correctly reflects embedding status.

Tests that require the model file are skipped if the file is absent.
"""

from __future__ import annotations

import json
import math
import sys
import unittest
from pathlib import Path

_ROOT = Path(__file__).parent.parent
_GGUF = _ROOT / "benchmarks" / "models" / "nomic-embed-text-v1.5.Q8_0.gguf"
_RESULTS = _ROOT / "benchmarks" / "results" / "embedding_verification.json"

# ── Helpers ───────────────────────────────────────────────────────────────

def _load_results() -> dict | None:
    if not _RESULTS.is_file():
        return None
    return json.loads(_RESULTS.read_text())


def _model_available() -> bool:
    return _GGUF.is_file()


# ── Verification results tests ─────────────────────────────────────────────


class VerificationResultsTest(unittest.TestCase):
    """Properties measured in B-010 and recorded in the JSON artefact."""

    def setUp(self) -> None:
        r = _load_results()
        if r is None:
            self.skipTest("Verification results JSON not found — run verify_embedding.py first")
        self.r = r

    def test_model_loaded_successfully(self) -> None:
        """[M] Model loaded without error."""
        self.assertTrue(self.r.get("load_ok"), "Model failed to load")

    def test_embedding_dimension_is_768(self) -> None:
        """[M] Actual embedding dimension matches configured 768."""
        self.assertEqual(768, self.r.get("embedding_dim"))

    def test_output_is_not_token_level(self) -> None:
        """[M] Model returns single pooled vector, not per-token array."""
        self.assertFalse(self.r.get("is_token_level"))

    def test_output_is_deterministic(self) -> None:
        """[M] Identical input produces bit-identical output."""
        self.assertTrue(self.r.get("deterministic"))
        self.assertEqual(0.0, self.r.get("determinism_max_diff", 1.0))

    def test_vectors_are_not_normalized(self) -> None:
        """[M] L2 norm >> 1.0 — B-003 must normalize before IndexFlatIP.

        This is the critical finding from B-010. IndexFlatIP assumes
        normalized vectors for cosine equivalence. Without normalization,
        IP = raw dot product, not cosine similarity.
        """
        norm = self.r.get("l2_norm_sample", 0.0)
        self.assertGreater(norm, 5.0,
                           "Expected non-normalized vectors (L2 >> 1)")
        self.assertFalse(self.r.get("is_normalized"),
                         "Model must NOT return normalized vectors")

    def test_identical_texts_have_cosine_1(self) -> None:
        """[M] Identical strings produce cosine similarity = 1.0."""
        sim = self.r.get("similarity_pairs", {}).get("identical", {})
        self.assertAlmostEqual(1.0, sim.get("cosine", 0.0), places=3)

    def test_near_duplicate_has_high_cosine(self) -> None:
        """[M] Near-duplicate text has cosine > 0.95."""
        sim = self.r.get("similarity_pairs", {}).get("near_dup", {})
        self.assertGreater(sim.get("cosine", 0.0), 0.95)

    def test_semantic_similar_higher_than_unrelated(self) -> None:
        """[M] Semantically similar text ranks higher than unrelated text."""
        pairs = self.r.get("similarity_pairs", {})
        sem = pairs.get("sem_similar", {}).get("cosine", 0.0)
        unrel = pairs.get("unrelated", {}).get("cosine", 0.0)
        self.assertGreater(sem, unrel,
                           "Semantic similarity should exceed unrelated cosine")

    def test_ram_cost_under_300_mib(self) -> None:
        """[M] Model load costs under 300 MiB RAM (measured ~152 MiB)."""
        cost = self.r.get("ram_model_cost_mib", 999)
        self.assertLess(cost, 300,
                        f"RAM cost {cost} MiB exceeds 300 MiB threshold")

    def test_short_text_latency_under_100ms(self) -> None:
        """[M] Short-text single embedding under 100 ms."""
        lat = self.r.get("latency_short_text_ms", 999)
        self.assertLess(lat, 100)

    def test_batch_throughput_sufficient(self) -> None:
        """[M] Batch-50 throughput > 50 items/s."""
        tps = self.r.get("batch_50_items_per_s", 0)
        self.assertGreater(tps, 50)

    def test_astra_interface_available(self) -> None:
        """[M] LlamaCppEmbeddingBackend.is_available() True when file present."""
        self.assertTrue(self.r.get("astra_backend_available"))

    def test_astra_embed_returns_768_dim(self) -> None:
        """[M] backend.embed() returns vectors of dimension 768."""
        self.assertEqual(768, self.r.get("astra_embed_dim"))

    def test_dim_matches_settings(self) -> None:
        """[M] Measured dim matches ReasoningSettings().embedding_dim."""
        self.assertTrue(self.r.get("dim_matches_settings"))


# ── Normalization architecture tests ──────────────────────────────────────


class NormalizationArchitectureTest(unittest.TestCase):
    """Architectural requirements derived from B-010 normalization finding.

    B-010 found L2 norm ≈ 22. This section proves the normalization
    requirement is understood and documented correctly.
    """

    def test_indexflatip_requires_normalized_vectors(self) -> None:
        """IndexFlatIP with non-normalized vectors gives raw dot products.

        For IndexFlatIP to act as cosine similarity, all stored vectors
        must have L2 norm = 1.0. This is the responsibility of B-003.
        """
        # Demonstrate: normalized IP = cosine, non-normalized IP != cosine
        a = [3.0, 4.0]        # norm = 5.0
        b = [4.0, 3.0]        # norm = 5.0
        ip = sum(x * y for x, y in zip(a, b))         # = 24
        na = math.sqrt(sum(x * x for x in a))          # = 5.0
        nb = math.sqrt(sum(x * x for x in b))          # = 5.0
        cos = ip / (na * nb)                            # = 0.96
        self.assertNotAlmostEqual(ip, cos, places=1,
                                  msg="Raw IP != cosine for non-normalized vectors")

    def test_normalize_then_ip_equals_cosine(self) -> None:
        """After L2 normalization, IndexFlatIP gives cosine similarity."""
        a = [3.0, 4.0]
        b = [4.0, 3.0]
        na = math.sqrt(sum(x * x for x in a))
        nb = math.sqrt(sum(x * x for x in b))
        an = [x / na for x in a]
        bn = [x / nb for x in b]
        ip_normalized = sum(x * y for x, y in zip(an, bn))
        cos = sum(x * y for x, y in zip(a, b)) / (na * nb)
        self.assertAlmostEqual(ip_normalized, cos, places=10)

    def test_b003_normalization_requirement_documented(self) -> None:
        """Verification results JSON records the normalization requirement."""
        r = _load_results()
        if r is None:
            self.skipTest("Results JSON not found")
        self.assertIn("normalization_note", r)
        self.assertIn("B-003", r["normalization_note"])

    def test_context_limit_documented(self) -> None:
        """GGUF n_ctx_train=2048 is documented in results."""
        r = _load_results()
        if r is None:
            self.skipTest("Results JSON not found")
        self.assertIn("n_ctx_train_gguf", r)
        self.assertEqual(2048, r["n_ctx_train_gguf"])


# ── Presentation layer tests ───────────────────────────────────────────────


class EmbeddingPresentationTest(unittest.TestCase):
    def test_embedding_available_when_gguf_present(self) -> None:
        """Dashboard reports AVAILABLE when GGUF is on disk."""
        if not _model_available():
            self.skipTest("GGUF not present")
        from astra.presentation.provider import _embed_model_status
        from astra.state import CapabilityStatus
        cap = _embed_model_status(None)
        self.assertEqual(CapabilityStatus.AVAILABLE, cap.status)

    def test_embedding_configured_when_gguf_absent(self) -> None:
        """Dashboard reports CONFIGURED when GGUF is absent."""
        from astra.presentation.provider import _embed_model_status
        from astra.state import CapabilityStatus
        # Pass a non-existent path
        cap = _embed_model_status("/nonexistent/path.gguf")
        self.assertNotIn(cap.status, [
            CapabilityStatus.AVAILABLE,
            CapabilityStatus.OPERATIONAL,
        ])

    def test_embedding_status_notes_include_normalization_warning(self) -> None:
        """Dashboard notes mention that vectors are not normalized."""
        if not _model_available():
            self.skipTest("GGUF not present")
        from astra.presentation.provider import _embed_model_status
        cap = _embed_model_status(None)
        self.assertIn("normalized", cap.notes.lower(),
                      "Status notes must mention normalization requirement")


if __name__ == "__main__":
    unittest.main()
