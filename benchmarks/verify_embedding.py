# -*- coding: utf-8 -*-
"""
B-010: Empirical embedding verification for nomic-embed-text-v1.5.Q8_0.gguf
Covers ADR-0005 Section 24 checklist items 1-12.

Run: python benchmarks/verify_embedding.py
Output: benchmarks/results/embedding_verification.json
"""

from __future__ import annotations

import gc
import json
import math
import os
import ctypes
import sys
import time
from pathlib import Path

sys.stdout.reconfigure(line_buffering=True)
sys.stderr.reconfigure(line_buffering=True)

# DLL bootstrap for CUDA-linked llama_cpp on this machine
_TORCH_LIB = r"C:\Users\ASUS\anaconda3\envs\Chamber\Lib\site-packages\torch\lib"
_LLAMA_LIB = (
    r"C:\Users\ASUS\AppData\Local\Programs\Python\Python312"
    r"\Lib\site-packages\llama_cpp\lib"
)
for _p in [_TORCH_LIB, _LLAMA_LIB]:
    if os.path.exists(_p):
        os.add_dll_directory(_p)
for _dll in ["ggml-base.dll", "ggml-cpu.dll", "ggml.dll", "ggml-cuda.dll"]:
    _full = os.path.join(_LLAMA_LIB, _dll)
    if os.path.exists(_full):
        try:
            ctypes.CDLL(_full)
        except OSError:
            pass

_PROJECT_ROOT = str(Path(__file__).parent.parent)
if _PROJECT_ROOT not in sys.path:
    sys.path.insert(0, _PROJECT_ROOT)

import psutil  # noqa: E402

MODEL_PATH = Path(__file__).parent / "models" / "nomic-embed-text-v1.5.Q8_0.gguf"
OUT_PATH = Path(__file__).parent / "results" / "embedding_verification.json"
OUT_PATH.parent.mkdir(parents=True, exist_ok=True)

# ── Deterministic test corpus ─────────────────────────────────────────────

CORPUS = {
    "identical_a": "The quick brown fox jumps over the lazy dog.",
    "identical_b": "The quick brown fox jumps over the lazy dog.",
    "near_duplicate": "The quick brown fox jumped over the lazy dog.",
    "semantic_similar": "A swift auburn fox leaped across a lethargic canine.",
    "semantic_different": "Quantum entanglement enables non-local correlations between particles.",
    "short_a": "Hello",
    "short_b": "Hi",
    "long_text": (
        "Transformer models process sequences of tokens by converting them into "
        "dense vector representations called embeddings. These are passed through "
        "multiple layers of self-attention mechanisms, where each token can attend "
        "to every other token in the sequence. The self-attention operation computes "
        "query, key, and value projections and combines them via scaled dot-product "
        "attention. Feed-forward sub-layers follow each attention block, applying "
        "independent non-linear transformations at each position. The final hidden "
        "states are used for downstream tasks such as classification or generation."
    ),
    "punctuation_heavy": "Hello!!! Are you okay?? Yes... I'm fine; but tired -- very tired.",
    "source_code": (
        "def two_sum(nums: list[int], target: int) -> list[int]:\n"
        "    seen = {}\n"
        "    for i, n in enumerate(nums):\n"
        "        if target - n in seen:\n"
        "            return [seen[target - n], i]\n"
        "        seen[n] = i\n"
        "    return []"
    ),
    "structured_data": (
        "DATABASE_HOST=prod-db-01.internal\n"
        "DATABASE_PORT=5432\n"
        "SECRET_KEY=xK9#mP2\n"
        "LOG_LEVEL=WARNING\n"
        "MAX_CONNECTIONS=100"
    ),
    "unrelated": (
        "The mitochondria are the powerhouse of the cell. They produce ATP through "
        "oxidative phosphorylation, a process that uses the electron transport chain."
    ),
}

# ── Helpers ───────────────────────────────────────────────────────────────

def ram_proc_mib() -> int:
    return psutil.Process(os.getpid()).memory_info().rss // 1048576


def l2_norm(vec: list[float]) -> float:
    return math.sqrt(sum(x * x for x in vec))


def dot(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b))


def cosine(a: list[float], b: list[float]) -> float:
    na, nb = l2_norm(a), l2_norm(b)
    if na == 0 or nb == 0:
        return 0.0
    return dot(a, b) / (na * nb)


def ip_sim(a: list[float], b: list[float]) -> float:
    """Inner-product similarity (= cosine if both normalized)."""
    return dot(a, b)


# ── Main verification ──────────────────────────────────────────────────────

def main() -> dict:
    import llama_cpp

    print("=" * 68)
    print("B-010: Embedding Model Verification")
    print(f"Model: {MODEL_PATH.name}")
    print(f"Size:  {MODEL_PATH.stat().st_size // 1048576} MB")
    print("=" * 68)

    results: dict = {
        "model_filename": MODEL_PATH.name,
        "model_size_mb": MODEL_PATH.stat().st_size // 1048576,
        "llama_cpp_version": llama_cpp.__version__,
    }

    # ── 1. Load model ─────────────────────────────────────────────────────
    print("\n[1] Load model")
    ram_before = ram_proc_mib()
    t0 = time.perf_counter()
    try:
        llm = llama_cpp.Llama(
            model_path=str(MODEL_PATH),
            embedding=True,
            n_ctx=8192,
            n_gpu_layers=0,      # CPU per ADR-0004 D7
            n_threads=12,
            verbose=True,
        )
        load_time = time.perf_counter() - t0
        ram_after_load = ram_proc_mib()
        results["load_ok"] = True
        results["load_time_s"] = round(load_time, 2)
        results["ram_before_load_mib"] = ram_before
        results["ram_after_load_mib"] = ram_after_load
        results["ram_model_cost_mib"] = ram_after_load - ram_before
        print(f"    Loaded in {load_time:.2f}s  RAM +{ram_after_load - ram_before} MiB")
    except Exception as e:
        results["load_ok"] = False
        results["load_error"] = str(e)
        print(f"    FAILED: {e}")
        OUT_PATH.write_text(json.dumps(results, indent=2))
        return results

    # ── 2. Embedding dimension ────────────────────────────────────────────
    print("\n[2] Embedding dimension")
    try:
        test_resp = llm.create_embedding("test")
        raw_emb = test_resp["data"][0]["embedding"]
        # Check if token-level (list of lists) or pooled (list of floats)
        is_token_level = isinstance(raw_emb[0], (list, tuple)) if raw_emb else False
        if is_token_level:
            dim = len(raw_emb[0])
            token_count = len(raw_emb)
        else:
            dim = len(raw_emb)
            token_count = None

        results["embedding_dim"] = dim
        results["is_token_level"] = is_token_level
        results["token_count_if_token_level"] = token_count
        print(f"    dim={dim}  token_level={is_token_level}")
    except Exception as e:
        results["dimension_error"] = str(e)
        print(f"    ERROR: {e}")

    # ── 3. Pooling behavior ───────────────────────────────────────────────
    print("\n[3] Pooling behavior")
    if not results.get("is_token_level", False):
        results["pooling_method"] = "model_returns_pooled_vector"
        print("    Model returns single pooled vector (no manual pooling needed)")
    else:
        results["pooling_method"] = "token_level_requires_pooling_strategy"
        print("    WARNING: model returns token-level embeddings")

    # ── Helper: get single embedding ──────────────────────────────────────
    def get_embedding(text: str) -> list[float]:
        resp = llm.create_embedding(text)
        raw = resp["data"][0]["embedding"]
        if isinstance(raw[0], (list, tuple)):
            # Mean pooling fallback (with warning)
            vec = [sum(raw[i][j] for i in range(len(raw))) / len(raw)
                   for j in range(len(raw[0]))]
            return vec
        return list(raw)

    # ── 4. Normalization ──────────────────────────────────────────────────
    print("\n[4] Normalization")
    try:
        v = get_embedding("normalization test sentence")
        norm = l2_norm(v)
        is_normalized = abs(norm - 1.0) < 0.01
        results["l2_norm_sample"] = round(norm, 6)
        results["is_normalized"] = is_normalized
        print(f"    L2 norm = {norm:.6f}  normalized={is_normalized}")
    except Exception as e:
        results["normalization_error"] = str(e)
        print(f"    ERROR: {e}")

    # ── 5. Determinism ────────────────────────────────────────────────────
    print("\n[5] Determinism")
    try:
        v1 = get_embedding("determinism check sentence")
        v2 = get_embedding("determinism check sentence")
        matches = all(abs(a - b) < 1e-6 for a, b in zip(v1, v2))
        max_diff = max(abs(a - b) for a, b in zip(v1, v2))
        results["deterministic"] = matches
        results["determinism_max_diff"] = round(max_diff, 9)
        print(f"    deterministic={matches}  max_diff={max_diff:.2e}")
    except Exception as e:
        results["determinism_error"] = str(e)
        print(f"    ERROR: {e}")

    # ── 6. Similarity behavior ────────────────────────────────────────────
    print("\n[6] Similarity behavior (corpus)")
    sims: dict = {}
    embeddings: dict[str, list[float]] = {}
    for key, text in CORPUS.items():
        try:
            embeddings[key] = get_embedding(text)
        except Exception as e:
            print(f"    {key}: ERROR {e}")
            continue

    # Key similarity pairs
    pairs = [
        ("identical_a", "identical_b", "identical"),
        ("identical_a", "near_duplicate", "near_dup"),
        ("identical_a", "semantic_similar", "sem_similar"),
        ("identical_a", "semantic_different", "sem_different"),
        ("identical_a", "unrelated", "unrelated"),
        ("identical_a", "source_code", "vs_code"),
        ("short_a", "short_b", "short_hi_hello"),
    ]
    for ka, kb, label in pairs:
        if ka in embeddings and kb in embeddings:
            ip = ip_sim(embeddings[ka], embeddings[kb])
            cos = cosine(embeddings[ka], embeddings[kb])
            sims[label] = {"ip": round(ip, 4), "cosine": round(cos, 4)}
            print(f"    {label:<20} IP={ip:.4f}  cos={cos:.4f}")
    results["similarity_pairs"] = sims

    # ── 7. CPU memory ─────────────────────────────────────────────────────
    print("\n[7] CPU memory")
    ram_mid = ram_proc_mib()
    results["ram_during_embed_mib"] = ram_mid
    print(f"    Process RSS = {ram_mid} MiB")

    # ── 8. Single-input latency ───────────────────────────────────────────
    print("\n[8] Single-input latency")
    latencies_short = []
    latencies_long = []
    for _ in range(5):
        t = time.perf_counter()
        get_embedding("Short text for latency test.")
        latencies_short.append(time.perf_counter() - t)
    for _ in range(3):
        t = time.perf_counter()
        get_embedding(CORPUS["long_text"])
        latencies_long.append(time.perf_counter() - t)
    avg_short = sum(latencies_short) / len(latencies_short)
    avg_long = sum(latencies_long) / len(latencies_long)
    results["latency_short_text_ms"] = round(avg_short * 1000, 1)
    results["latency_long_text_ms"] = round(avg_long * 1000, 1)
    print(f"    Short (~5 tokens): {avg_short*1000:.1f} ms avg")
    print(f"    Long  (~130 tokens): {avg_long*1000:.1f} ms avg")

    # ── 9. Batch throughput ───────────────────────────────────────────────
    print("\n[9] Batch throughput")
    batch_texts_10 = ["Sample sentence for batch test number " + str(i) for i in range(10)]
    batch_texts_50 = ["Sample sentence for batch test number " + str(i) for i in range(50)]
    try:
        t0 = time.perf_counter()
        resp10 = llm.create_embedding(batch_texts_10)
        t10 = time.perf_counter() - t0
        n10 = len(resp10["data"])

        t0 = time.perf_counter()
        resp50 = llm.create_embedding(batch_texts_50)
        t50 = time.perf_counter() - t0
        n50 = len(resp50["data"])

        results["batch_10_time_s"] = round(t10, 3)
        results["batch_10_per_item_ms"] = round(t10 / n10 * 1000, 1)
        results["batch_50_time_s"] = round(t50, 3)
        results["batch_50_per_item_ms"] = round(t50 / n50 * 1000, 1)
        results["batch_10_items_per_s"] = round(n10 / t10, 1)
        results["batch_50_items_per_s"] = round(n50 / t50, 1)
        print(f"    Batch-10:  {t10:.3f}s  ({n10/t10:.1f} items/s)")
        print(f"    Batch-50:  {t50:.3f}s  ({n50/t50:.1f} items/s)")
    except Exception as e:
        results["batch_error"] = str(e)
        print(f"    ERROR: {e}")

    # ── 10. Practical context length ──────────────────────────────────────
    print("\n[10] Practical input length")
    # Test token counts near the 8192 limit; use ~500-token text
    five_hundred_tok = CORPUS["long_text"] * 4   # ~520 tokens
    try:
        t0 = time.perf_counter()
        v = get_embedding(five_hundred_tok)
        t_long = time.perf_counter() - t0
        norm_long = l2_norm(v)
        results["test_500tok_ok"] = True
        results["test_500tok_lat_ms"] = round(t_long * 1000, 1)
        results["test_500tok_norm"] = round(norm_long, 4)
        print(f"    ~500 tokens: {t_long*1000:.1f} ms  norm={norm_long:.4f}")
    except Exception as e:
        results["test_500tok_ok"] = False
        results["test_500tok_error"] = str(e)
        print(f"    ~500 tokens: FAILED {e}")

    # Test near model limit — 4000 chars ~ 1000 tokens
    one_k_tok = CORPUS["long_text"] * 8
    try:
        t0 = time.perf_counter()
        v = get_embedding(one_k_tok)
        t_vlong = time.perf_counter() - t0
        results["test_1ktok_ok"] = True
        results["test_1ktok_lat_ms"] = round(t_vlong * 1000, 1)
        print(f"    ~1000 tokens: {t_vlong*1000:.1f} ms")
    except Exception as e:
        results["test_1ktok_ok"] = False
        results["test_1ktok_error"] = str(e)
        print(f"    ~1000 tokens: {e}")

    # ── 11. Q8_0 quality spot-check ───────────────────────────────────────
    print("\n[11] Q8_0 quality spot-check")
    # Can't compare to float32 without a second model.
    # Record: identical pair should have IP ≈ 1.0 (≤ 0.001 error expected for Q8_0)
    if "identical_a" in embeddings and "identical_b" in embeddings:
        ip_identical = ip_sim(embeddings["identical_a"], embeddings["identical_b"])
        results["q8_identical_ip"] = round(ip_identical, 6)
        results["q8_identical_perfect"] = abs(ip_identical - 1.0) < 0.002
        print(f"    Identical pair IP = {ip_identical:.6f}  (ideal=1.0)")
        print(f"    Q8_0 quantization error: {abs(ip_identical - 1.0):.6f}")
    else:
        print("    (skipped — embeddings not available)")

    # ── 12. Interface compatibility ───────────────────────────────────────
    print("\n[12] Astra interface compatibility")
    try:
        # Test through LlamaCppEmbeddingBackend (the actual Astra interface)
        from astra.reasoning.llama_cpp_backend import LlamaCppEmbeddingBackend
        from astra.configuration.settings import ReasoningSettings

        backend = LlamaCppEmbeddingBackend(
            MODEL_PATH,
            label="nomic-embed-text-v1.5",
            context_tokens=8192,
            gpu_layers=0,
            threads=12,
        )
        is_avail = backend.is_available()
        results["astra_backend_available"] = is_avail
        print(f"    is_available() = {is_avail}")

        if is_avail:
            # Test embed()
            t0 = time.perf_counter()
            vecs = backend.embed(["Hello world", "Testing Astra interface"])
            t_iface = time.perf_counter() - t0
            n = len(vecs)
            dim_iface = len(vecs[0]) if vecs else 0
            results["astra_embed_ok"] = True
            results["astra_embed_count"] = n
            results["astra_embed_dim"] = dim_iface
            results["astra_embed_time_ms"] = round(t_iface * 1000, 1)
            print(f"    embed() returned {n} vectors, dim={dim_iface}, {t_iface*1000:.1f} ms")

            # Verify dim matches configured embedding_dim
            settings_dim = ReasoningSettings().embedding_dim
            dim_match = dim_iface == settings_dim
            results["dim_matches_settings"] = dim_match
            results["settings_embedding_dim"] = settings_dim
            print(f"    settings.embedding_dim={settings_dim}  match={dim_match}")
    except Exception as e:
        results["astra_interface_error"] = str(e)
        print(f"    ERROR: {e}")

    del llm
    gc.collect()

    print("\n" + "=" * 68)
    print("SUMMARY")
    print("=" * 68)
    print(f"  Load:           {'OK' if results.get('load_ok') else 'FAIL'} ({results.get('load_time_s')}s)")
    print(f"  Dimension:      {results.get('embedding_dim')} (expected 768)")
    print(f"  Token-level:    {results.get('is_token_level')} (should be False)")
    print(f"  Normalized:     {results.get('is_normalized')} (L2={results.get('l2_norm_sample')})")
    print(f"  Deterministic:  {results.get('deterministic')}")
    print(f"  RAM cost:       {results.get('ram_model_cost_mib')} MiB")
    print(f"  Lat (short):    {results.get('latency_short_text_ms')} ms")
    print(f"  Lat (long):     {results.get('latency_long_text_ms')} ms")
    print(f"  Batch-50:       {results.get('batch_50_items_per_s')} items/s")
    print(f"  IP identical:   {results.get('q8_identical_ip')} (ideal 1.0)")
    print(f"  Astra iface:    {'OK' if results.get('astra_embed_ok') else 'FAIL'}")
    print(f"  Dim match cfg:  {results.get('dim_matches_settings')}")
    print("=" * 68)

    return results


if __name__ == "__main__":
    r = main()
    OUT_PATH.write_text(json.dumps(r, indent=2))
    print(f"\nFull results: {OUT_PATH}")
