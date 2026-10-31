"""
B-002 Stage 4: Empirical Benchmark
===================================
Tests Candidate A (Qwen2.5-3B Q5_K_M) and Candidate B (Dolphin3.0-Llama3.1-8B Q5_K_S)
across GPU layer configs, context sizes, and quality tasks.

All results are written to benchmarks/results/ as JSON + markdown.
"""

from __future__ import annotations

import gc
import json
import os
import platform
import subprocess
import sys
import time
from dataclasses import dataclass, asdict, field
from pathlib import Path
from typing import Optional

# ---------------------------------------------------------------------------
# Ensure CUDA runtime DLL is findable before importing llama_cpp
# ---------------------------------------------------------------------------
_CUDA_RT_BIN = Path(sys.prefix) / "Lib/site-packages/nvidia/cuda_runtime/bin"
if _CUDA_RT_BIN.exists():
    os.add_dll_directory(str(_CUDA_RT_BIN))

LLAMA_CPP_LIB = Path(sys.prefix) / "Lib/site-packages/llama_cpp/lib"
if LLAMA_CPP_LIB.exists():
    os.add_dll_directory(str(LLAMA_CPP_LIB))

try:
    from llama_cpp import Llama
    LLAMA_AVAILABLE = True
    print(f"[OK] llama_cpp imported")
except Exception as e:
    LLAMA_AVAILABLE = False
    print(f"[FAIL] llama_cpp import: {e}")
    sys.exit(1)

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
HERE = Path(__file__).parent
MODELS_DIR = HERE / "models"
RESULTS_DIR = HERE / "results"
RESULTS_DIR.mkdir(exist_ok=True)

CANDIDATE_A = {
    "name": "Qwen2.5-3B-Instruct Q5_K_M",
    "path": MODELS_DIR / "qwen2.5-3b-instruct-q5_k_m.gguf",
    "n_layers": 36,
    "param_class": "3B",
    "quant": "Q5_K_M",
    "gpu_layer_configs": [99],   # full offload; 99 = all layers
}

# Dolphin3.0-Llama3.1-8B is based on Llama 3.1 architecture (32 transformer layers)
CANDIDATE_B = {
    "name": "Dolphin3.0-Llama3.1-8B Q5_K_S",
    "path": Path(r"C:\Users\ASUS\Downloads\Dolphin3.0-Llama3.1-8B-Q5_K_S.gguf"),
    "n_layers": 32,
    "param_class": "8B",
    "quant": "Q5_K_S",
    "gpu_layer_configs": [0, 10, 18, 24, 28, 33],  # CPU-only, partial, near-full, full-attempt
}

CONTEXT_SIZES = [2048, 4096, 8192]

SEED = 42
N_TOKENS_GEN = 128   # fixed generation length for speed tests
N_TOKENS_PREFILL_PROBE = 512  # tokens used to measure prefill speed

# ---------------------------------------------------------------------------
# Quality eval prompts  (deterministic, Astra-relevant)
# ---------------------------------------------------------------------------
QUALITY_PROMPTS = [
    {
        "id": "Q01-basic-reasoning",
        "category": "basic_reasoning",
        "prompt": (
            "Answer precisely.\n"
            "I have 5 apples. I give 3 to Alice. I then buy 7 more. "
            "I give half of what I have to Bob. How many apples do I have left? "
            "Show step-by-step working."
        ),
        "expected_key": "4",  # 5-3=2, 2+7=9, 9/2=4.5 → 4 (floor)? Actually 9/2=4 remainder 1, so 4 or 5 depending on interpretation
        "rubric": "Correct arithmetic, clear steps",
    },
    {
        "id": "Q02-multi-step-reasoning",
        "category": "multi_step_reasoning",
        "prompt": (
            "Three friends — Amir, Ben, Carl — each tell one statement.\n"
            "Amir: 'Ben is lying.'\n"
            "Ben: 'Carl is lying.'\n"
            "Carl: 'Amir is telling the truth.'\n"
            "Exactly one of them is telling the truth. Who is it? Explain."
        ),
        "expected_key": "Ben",
        "rubric": "Correct logical deduction with valid explanation",
    },
    {
        "id": "Q03-c-programming",
        "category": "c_programming",
        "prompt": (
            "Write a C function:\n"
            "  int find_second_largest(int *arr, int n);\n"
            "It must handle: n < 2 returns INT_MIN; all-equal arrays returns INT_MIN; "
            "negative numbers; duplicates. Include the complete function, no main()."
        ),
        "expected_key": "INT_MIN",
        "rubric": "Correct edge cases, no UB, clean C (not C++)",
    },
    {
        "id": "Q04-python-programming",
        "category": "python_programming",
        "prompt": (
            "Write a Python generator function:\n"
            "  def sliding_window(iterable, size):\n"
            "It must yield tuples of `size` consecutive elements. "
            "If len(iterable) < size, yield nothing. "
            "No imports allowed. Docstring required."
        ),
        "expected_key": "yield",
        "rubric": "Uses generator yield, correct window logic, handles edge cases",
    },
    {
        "id": "Q05-debugging",
        "category": "debugging",
        "prompt": (
            "Find ALL bugs in this Python code and explain each:\n\n"
            "```python\n"
            "def flatten(lst, depth=0):\n"
            "    result = []\n"
            "    for item in lst:\n"
            "        if isinstance(item, list) and depth > 0:\n"
            "            result.extend(flatten(item, depth - 1))\n"
            "        else:\n"
            "            result.append(item)\n"
            "    return result\n\n"
            "print(flatten([[1, [2, 3]], [4]], depth=1))\n"
            "```\n"
            "Expected output: [1, 2, 3, 4]. What is the actual output and why?"
        ),
        "expected_key": "depth",
        "rubric": "Identifies the depth logic inversion; explains actual vs expected",
    },
    {
        "id": "Q06-architecture-design",
        "category": "architecture_design",
        "prompt": (
            "Design the interface (not implementation) for a key-value cache with:\n"
            "- TTL per key\n"
            "- max capacity with LRU eviction\n"
            "- thread-safe reads\n"
            "- optional write-through to a persistent store\n\n"
            "Write only the abstract interface in Python (ABC), "
            "with typed signatures and docstrings. No implementation."
        ),
        "expected_key": "ABC",
        "rubric": "Correct ABC structure, typed, covers all four requirements",
    },
    {
        "id": "Q07-complex-instructions",
        "category": "following_instructions",
        "prompt": (
            "Respond to this EXACTLY as specified — deviation fails:\n"
            "1. Output a JSON object with keys: name, year, prime.\n"
            "2. name: the longest river in Africa.\n"
            "3. year: the year the Eiffel Tower was completed.\n"
            "4. prime: the smallest prime number greater than 100.\n"
            "5. Output ONLY the JSON object. No explanation, no markdown, no extra text."
        ),
        "expected_key": "Nile",
        "rubric": 'Valid JSON only; name=Nile, year=1889, prime=101',
    },
    {
        "id": "Q08-planning",
        "category": "planning",
        "prompt": (
            "Create a concrete ordered plan to: "
            "migrate a 10GB SQLite database to PostgreSQL with zero downtime, "
            "on a live production system with 1000 active users.\n"
            "Each step must be numbered, actionable, and reversible. "
            "State the rollback action for each step."
        ),
        "expected_key": "rollback",
        "rubric": "Numbered steps, each has rollback, addresses zero-downtime and live traffic",
    },
    {
        "id": "Q09-summarization",
        "category": "summarization",
        "prompt": (
            "Summarize this in exactly 3 bullet points, each starting with '•':\n\n"
            "The Ryzen 5 5600H is a mobile processor released by AMD in early 2021. "
            "It features 6 cores and 12 threads based on the Zen 3 microarchitecture, "
            "manufactured on TSMC's 7nm process. The chip has a base clock of 3.3 GHz "
            "and can boost up to 4.2 GHz, with a TDP of 45W configurable between 35W "
            "and 54W. It includes 16 MB of L3 cache and supports DDR4-3200 memory. "
            "The integrated Radeon graphics (7 CUs) run at up to 1800 MHz. "
            "The 5600H competes primarily with Intel's Tiger Lake-H series and delivers "
            "strong single-threaded and multi-threaded performance in its class."
        ),
        "expected_key": "•",
        "rubric": "Exactly 3 bullets starting with •, accurate, no extra text",
    },
    {
        "id": "Q10-tool-call",
        "category": "tool_function_call",
        "prompt": (
            "You are a function-calling assistant. Output a JSON function call.\n\n"
            "Available function:\n"
            "  search_memory(query: str, limit: int, category: str) -> list\n\n"
            "User request: 'Find the last 5 things I stored about Python.'\n\n"
            "Output only the JSON function call, nothing else:\n"
            '{"function": "...", "arguments": {...}}'
        ),
        "expected_key": "search_memory",
        "rubric": 'Valid JSON with function=search_memory, query contains "Python", limit=5',
    },
    {
        "id": "Q11-long-context",
        "category": "long_context_retrieval",
        "prompt": (
            "Read the following project notes carefully.\n\n"
            "--- PROJECT NOTES ---\n"
            "Project: Astra\n"
            "Version: 0.1.0-alpha\n"
            "Primary language: Python 3.12\n"
            "Database: SQLite with FTS5\n"
            "Vector index: FAISS IndexFlatIP\n"
            "Embedding dimension: 768\n"
            "GPU: RTX 3050 Laptop, 4 GiB VRAM\n"
            "Inference runtime: llama-cpp-python 0.3.34\n"
            "Provisional chat model: Qwen2.5-3B-Instruct Q5_K_M\n"
            "Status: Stage 4 benchmarking in progress\n"
            "Constitution rule: No network I/O at runtime\n"
            "Constitution rule: Human approval required for all deployments\n"
            "Constitution rule: Verified information cannot be overwritten by unverified\n"
            "Open question: Embedding pooling strategy not yet confirmed\n"
            "Open question: Chunking strategy for long documents unresolved\n"
            "--- END NOTES ---\n\n"
            "Questions (answer all):\n"
            "1. What is the embedding dimension?\n"
            "2. What constitution rule governs deployments?\n"
            "3. What two questions are still open?"
        ),
        "expected_key": "768",
        "rubric": "All 3 answers correct and sourced from the notes",
    },
    {
        "id": "Q12-error-correction",
        "category": "error_detection_self_correction",
        "prompt": (
            "Here is a claim about Python: "
            "'In Python 3, dividing two integers with / always returns an integer.'\n\n"
            "Is this claim correct? If not, give the exact correction, "
            "a concrete counterexample, and state what operator does return an integer."
        ),
        "expected_key": "//",
        "rubric": "Identifies the error; counterexample with /; names // as floor division",
    },
]

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def get_vram_mib() -> tuple[float, float]:
    """Return (free_mib, used_mib) from nvidia-smi."""
    try:
        out = subprocess.check_output(
            ["nvidia-smi", "--query-gpu=memory.free,memory.used",
             "--format=csv,noheader,nounits"],
            stderr=subprocess.DEVNULL, text=True, timeout=5
        ).strip()
        parts = out.split(",")
        return float(parts[0].strip()), float(parts[1].strip())
    except Exception:
        return -1.0, -1.0


def get_ram_available_mib() -> float:
    """Return available system RAM in MiB."""
    try:
        import ctypes
        class MEMSTATUS(ctypes.Structure):
            _fields_ = [
                ("dwLength", ctypes.c_ulong),
                ("dwMemoryLoad", ctypes.c_ulong),
                ("ullTotalPhys", ctypes.c_ulonglong),
                ("ullAvailPhys", ctypes.c_ulonglong),
                ("ullTotalPageFile", ctypes.c_ulonglong),
                ("ullAvailPageFile", ctypes.c_ulonglong),
                ("ullTotalVirtual", ctypes.c_ulonglong),
                ("ullAvailVirtual", ctypes.c_ulonglong),
                ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
            ]
        ms = MEMSTATUS()
        ms.dwLength = ctypes.sizeof(ms)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(ms))
        return ms.ullAvailPhys / (1024 * 1024)
    except Exception:
        return -1.0


def make_prefill_prompt(n_tokens_approx: int) -> str:
    """Build a prompt of approximately n_tokens_approx tokens (rough: 4 chars/token)."""
    base = (
        "The following is a detailed technical document about memory management "
        "in modern operating systems. It covers virtual memory, paging, segmentation, "
        "TLB operation, cache hierarchies, and NUMA effects on large servers. "
    )
    target_chars = n_tokens_approx * 4
    while len(base) < target_chars:
        base += base
    return base[:target_chars]


@dataclass
class SpeedResult:
    gpu_layers: int
    context_size: int
    vram_free_before_mib: float
    vram_used_before_mib: float
    vram_free_after_load_mib: float
    vram_used_after_load_mib: float
    ram_available_before_mib: float
    ram_available_after_load_mib: float
    load_time_s: float
    prefill_tokens: int
    prefill_time_s: float
    prefill_tps: float
    time_to_first_token_s: float
    gen_tokens: int
    gen_time_s: float
    gen_tps: float
    stable: bool
    notes: str = ""


@dataclass
class QualityResult:
    prompt_id: str
    category: str
    gpu_layers: int
    context_size: int
    response: str
    contains_key: bool
    gen_tps: float
    notes: str = ""


@dataclass
class ModelBenchmark:
    candidate_name: str
    param_class: str
    quant: str
    model_path: str
    speed_results: list[SpeedResult] = field(default_factory=list)
    quality_results: list[QualityResult] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Core benchmark functions
# ---------------------------------------------------------------------------

def measure_speed(
    model_path: Path,
    gpu_layers: int,
    context_size: int,
    n_batch: int = 512,
) -> SpeedResult:
    """Load model, measure prefill + generation speed, unload."""

    vram_free_before, vram_used_before = get_vram_mib()
    ram_before = get_ram_available_mib()

    notes = []
    stable = True

    # Build prefill prompt
    prefill_prompt = make_prefill_prompt(N_TOKENS_PREFILL_PROBE)

    t_load_start = time.perf_counter()
    try:
        llm = Llama(
            model_path=str(model_path),
            n_gpu_layers=gpu_layers,
            n_ctx=context_size,
            n_batch=n_batch,
            seed=SEED,
            verbose=False,
        )
    except Exception as e:
        notes.append(f"LOAD FAILED: {e}")
        stable = False
        return SpeedResult(
            gpu_layers=gpu_layers,
            context_size=context_size,
            vram_free_before_mib=vram_free_before,
            vram_used_before_mib=vram_used_before,
            vram_free_after_load_mib=-1,
            vram_used_after_load_mib=-1,
            ram_available_before_mib=ram_before,
            ram_available_after_load_mib=-1,
            load_time_s=-1,
            prefill_tokens=0,
            prefill_time_s=-1,
            prefill_tps=-1,
            time_to_first_token_s=-1,
            gen_tokens=0,
            gen_time_s=-1,
            gen_tps=-1,
            stable=False,
            notes="; ".join(notes),
        )

    t_load_end = time.perf_counter()
    load_time = t_load_end - t_load_start

    vram_free_after, vram_used_after = get_vram_mib()
    ram_after = get_ram_available_mib()

    # --- Prefill speed test ---
    # Tokenize the prefill prompt to get actual token count
    prefill_tokens_enc = llm.tokenize(prefill_prompt.encode(), add_bos=True)
    actual_prefill_n = min(len(prefill_tokens_enc), context_size - N_TOKENS_GEN - 10)
    prefill_tokens_enc = prefill_tokens_enc[:actual_prefill_n]

    t_prefill_start = time.perf_counter()
    try:
        # Use eval to measure pure prefill
        llm.reset()
        llm.eval(prefill_tokens_enc)
        t_prefill_end = time.perf_counter()
        prefill_time = t_prefill_end - t_prefill_start
        prefill_tps = actual_prefill_n / prefill_time if prefill_time > 0 else 0
    except Exception as e:
        notes.append(f"PREFILL FAILED: {e}")
        prefill_time = -1
        prefill_tps = -1
        stable = False

    # --- Generation speed + TTFT ---
    gen_prompt = "Describe the water cycle in three sentences."
    try:
        llm.reset()
        t_gen_start = time.perf_counter()
        first_token_time = None
        gen_count = 0
        output = llm(
            gen_prompt,
            max_tokens=N_TOKENS_GEN,
            seed=SEED,
            temperature=0.0,
            stream=True,
        )
        for chunk in output:
            token_text = chunk["choices"][0]["text"]
            if first_token_time is None and token_text:
                first_token_time = time.perf_counter() - t_gen_start
            gen_count += 1
            if gen_count >= N_TOKENS_GEN:
                break

        t_gen_end = time.perf_counter()
        gen_time = t_gen_end - t_gen_start
        gen_tps = gen_count / gen_time if gen_time > 0 else 0
        ttft = first_token_time if first_token_time is not None else gen_time

    except Exception as e:
        notes.append(f"GEN FAILED: {e}")
        gen_time = -1
        gen_tps = -1
        ttft = -1
        gen_count = 0
        stable = False

    del llm
    gc.collect()

    return SpeedResult(
        gpu_layers=gpu_layers,
        context_size=context_size,
        vram_free_before_mib=vram_free_before,
        vram_used_before_mib=vram_used_before,
        vram_free_after_load_mib=vram_free_after,
        vram_used_after_load_mib=vram_used_after,
        ram_available_before_mib=ram_before,
        ram_available_after_load_mib=ram_after,
        load_time_s=round(load_time, 2),
        prefill_tokens=actual_prefill_n if prefill_tps >= 0 else 0,
        prefill_time_s=round(prefill_time, 3) if prefill_time >= 0 else -1,
        prefill_tps=round(prefill_tps, 1) if prefill_tps >= 0 else -1,
        time_to_first_token_s=round(ttft, 3) if ttft >= 0 else -1,
        gen_tokens=gen_count,
        gen_time_s=round(gen_time, 3) if gen_time >= 0 else -1,
        gen_tps=round(gen_tps, 1) if gen_tps >= 0 else -1,
        stable=stable,
        notes="; ".join(notes),
    )


def measure_quality(
    model_path: Path,
    gpu_layers: int,
    context_size: int,
    prompts: list[dict],
) -> list[QualityResult]:
    """Run quality prompts against a loaded model."""
    results = []

    try:
        llm = Llama(
            model_path=str(model_path),
            n_gpu_layers=gpu_layers,
            n_ctx=context_size,
            n_batch=512,
            seed=SEED,
            verbose=False,
        )
    except Exception as e:
        for p in prompts:
            results.append(QualityResult(
                prompt_id=p["id"],
                category=p["category"],
                gpu_layers=gpu_layers,
                context_size=context_size,
                response="",
                contains_key=False,
                gen_tps=-1,
                notes=f"MODEL LOAD FAILED: {e}",
            ))
        return results

    for p in prompts:
        try:
            t0 = time.perf_counter()
            out = llm(
                p["prompt"],
                max_tokens=512,
                seed=SEED,
                temperature=0.0,
            )
            t1 = time.perf_counter()
            text = out["choices"][0]["text"].strip()
            tokens_gen = out["usage"]["completion_tokens"]
            elapsed = t1 - t0
            tps = tokens_gen / elapsed if elapsed > 0 else 0

            results.append(QualityResult(
                prompt_id=p["id"],
                category=p["category"],
                gpu_layers=gpu_layers,
                context_size=context_size,
                response=text,
                contains_key=p["expected_key"].lower() in text.lower(),
                gen_tps=round(tps, 1),
                notes="",
            ))
            print(f"  [{p['id']}] {tps:.1f} tok/s | key_found={p['expected_key'].lower() in text.lower()}")
        except Exception as e:
            results.append(QualityResult(
                prompt_id=p["id"],
                category=p["category"],
                gpu_layers=gpu_layers,
                context_size=context_size,
                response="",
                contains_key=False,
                gen_tps=-1,
                notes=f"FAILED: {e}",
            ))

    del llm
    gc.collect()
    return results


# ---------------------------------------------------------------------------
# Sustained load test (thermal throttle detection)
# ---------------------------------------------------------------------------

def sustained_test(
    model_path: Path,
    gpu_layers: int,
    context_size: int = 2048,
    duration_s: float = 120.0,
) -> dict:
    """Generate continuously for `duration_s` seconds, record TPS per 10s interval."""
    print(f"\n  [SUSTAINED] {duration_s}s sustained generation test...")
    try:
        llm = Llama(
            model_path=str(model_path),
            n_gpu_layers=gpu_layers,
            n_ctx=context_size,
            n_batch=512,
            seed=SEED,
            verbose=False,
        )
    except Exception as e:
        return {"error": str(e), "intervals": []}

    prompt = "Continue this story about artificial intelligence and human society: Once upon a time "
    intervals = []
    t_global_start = time.perf_counter()

    while True:
        elapsed_global = time.perf_counter() - t_global_start
        if elapsed_global >= duration_s:
            break

        t_int_start = time.perf_counter()
        try:
            out = llm(prompt, max_tokens=200, seed=SEED, temperature=0.1)
            t_int_end = time.perf_counter()
            tokens = out["usage"]["completion_tokens"]
            tps = tokens / (t_int_end - t_int_start)
            intervals.append({
                "elapsed_s": round(elapsed_global, 1),
                "tps": round(tps, 1),
            })
            # append output to prompt to keep generating new context
            prompt += out["choices"][0]["text"]
            if len(prompt) > context_size * 3:  # rough char limit
                prompt = prompt[-context_size * 2:]
        except Exception as e:
            intervals.append({
                "elapsed_s": round(elapsed_global, 1),
                "tps": -1,
                "error": str(e),
            })
            break

    del llm
    gc.collect()

    if intervals:
        first_tps = intervals[0]["tps"]
        last_tps = intervals[-1]["tps"]
        throttle_pct = ((first_tps - last_tps) / first_tps * 100) if first_tps > 0 else 0
        return {
            "duration_s": duration_s,
            "intervals": intervals,
            "first_tps": first_tps,
            "last_tps": last_tps,
            "throttle_pct": round(throttle_pct, 1),
        }
    return {"duration_s": duration_s, "intervals": [], "error": "no data"}


# ---------------------------------------------------------------------------
# Main benchmark runner
# ---------------------------------------------------------------------------

def run_benchmark(candidate: dict, run_sustained: bool = True) -> ModelBenchmark:
    bench = ModelBenchmark(
        candidate_name=candidate["name"],
        param_class=candidate["param_class"],
        quant=candidate["quant"],
        model_path=str(candidate["path"]),
    )

    if not candidate["path"].exists():
        print(f"\n[SKIP] {candidate['name']}: file not found at {candidate['path']}")
        return bench

    print(f"\n{'='*70}")
    print(f"BENCHMARKING: {candidate['name']}")
    print(f"Path: {candidate['path']}")
    print(f"{'='*70}")

    for gpu_layers in candidate["gpu_layer_configs"]:
        for ctx_size in CONTEXT_SIZES:
            print(f"\n  [SPEED] gpu_layers={gpu_layers}, ctx={ctx_size}")
            result = measure_speed(candidate["path"], gpu_layers, ctx_size)
            bench.speed_results.append(result)
            print(
                f"    Load: {result.load_time_s:.1f}s | "
                f"VRAM used: {result.vram_used_after_load_mib:.0f} MiB | "
                f"RAM avail: {result.ram_available_after_load_mib:.0f} MiB | "
                f"Prefill: {result.prefill_tps:.1f} tok/s | "
                f"Gen: {result.gen_tps:.1f} tok/s | "
                f"TTFT: {result.time_to_first_token_s:.3f}s | "
                f"Stable: {result.stable}"
            )
            if not result.stable:
                print(f"    NOTES: {result.notes}")
                # If the model fails to load at this config, skip remaining ctx sizes
                if "LOAD FAILED" in result.notes:
                    break

    # Quality benchmark: use best GPU config + 4096 context
    best_gpu = candidate["gpu_layer_configs"][-1]  # highest layer count
    print(f"\n  [QUALITY] gpu_layers={best_gpu}, ctx=4096")
    quality = measure_quality(candidate["path"], best_gpu, 4096, QUALITY_PROMPTS)
    bench.quality_results.extend(quality)

    # Sustained test: use best GPU config + 2048 context (stable baseline)
    if run_sustained:
        print(f"\n  [SUSTAINED] gpu_layers={best_gpu}, ctx=2048")
        sustained = sustained_test(candidate["path"], best_gpu, 2048, 120.0)
        # Store as a special entry in speed_results with negative ctx to distinguish
        bench.speed_results.append(SpeedResult(
            gpu_layers=best_gpu,
            context_size=-1,  # sentinel: sustained test
            vram_free_before_mib=-1,
            vram_used_before_mib=-1,
            vram_free_after_load_mib=-1,
            vram_used_after_load_mib=-1,
            ram_available_before_mib=-1,
            ram_available_after_load_mib=-1,
            load_time_s=-1,
            prefill_tokens=0,
            prefill_time_s=-1,
            prefill_tps=-1,
            time_to_first_token_s=-1,
            gen_tokens=0,
            gen_time_s=-1,
            gen_tps=sustained.get("last_tps", -1),
            stable=sustained.get("throttle_pct", 100) < 30,
            notes=json.dumps(sustained),
        ))
        print(f"    First: {sustained.get('first_tps','?')} tok/s → Last: {sustained.get('last_tps','?')} tok/s | Throttle: {sustained.get('throttle_pct','?')}%")

    return bench


# ---------------------------------------------------------------------------
# Report generation
# ---------------------------------------------------------------------------

def save_json(results: list[ModelBenchmark], timestamp: str):
    path = RESULTS_DIR / f"stage4_results_{timestamp}.json"
    data = []
    for b in results:
        d = {
            "candidate_name": b.candidate_name,
            "param_class": b.param_class,
            "quant": b.quant,
            "model_path": b.model_path,
            "speed_results": [asdict(r) for r in b.speed_results],
            "quality_results": [asdict(r) for r in b.quality_results],
        }
        data.append(d)
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")
    print(f"\n[SAVED] {path}")
    return path


def make_markdown_report(results: list[ModelBenchmark], timestamp: str) -> Path:
    lines = []
    lines.append("# B-002 Stage 4: Empirical Benchmark Report")
    lines.append(f"\nGenerated: {timestamp}")
    lines.append(f"Platform: {platform.platform()}")
    lines.append(f"Python: {sys.version.split()[0]}")
    lines.append("")

    for b in results:
        lines.append(f"## {b.candidate_name}")
        lines.append(f"- **Path:** `{b.model_path}`")
        lines.append(f"- **Class:** {b.param_class} | **Quant:** {b.quant}")
        lines.append("")

        # Speed table
        speed = [r for r in b.speed_results if r.context_size > 0]
        if speed:
            lines.append("### Speed Results")
            lines.append("")
            lines.append("| GPU Layers | Ctx | VRAM Used (MiB) | RAM Avail (MiB) | Load (s) | Prefill (tok/s) | Gen (tok/s) | TTFT (s) | Stable |")
            lines.append("|---|---|---|---|---|---|---|---|---|")
            for r in speed:
                lines.append(
                    f"| {r.gpu_layers} | {r.context_size} | "
                    f"{r.vram_used_after_load_mib:.0f} | {r.ram_available_after_load_mib:.0f} | "
                    f"{r.load_time_s:.1f} | {r.prefill_tps:.1f} | {r.gen_tps:.1f} | "
                    f"{r.time_to_first_token_s:.3f} | {'✓' if r.stable else '✗'} |"
                )
                if r.notes:
                    lines.append(f"  - *Note: {r.notes}*")
            lines.append("")

        # Sustained test
        sustained_entries = [r for r in b.speed_results if r.context_size == -1]
        if sustained_entries:
            s = sustained_entries[-1]
            try:
                sd = json.loads(s.notes)
                lines.append("### Sustained Generation (2 min)")
                lines.append("")
                lines.append(f"- First TPS: {sd.get('first_tps','N/A')}")
                lines.append(f"- Last TPS: {sd.get('last_tps','N/A')}")
                lines.append(f"- Throttle: {sd.get('throttle_pct','N/A')}%")
                lines.append(f"- Stable (<30% drop): {'Yes' if s.stable else 'No'}")
                intervals = sd.get("intervals", [])
                if len(intervals) > 1:
                    lines.append("")
                    lines.append("| Time (s) | TPS |")
                    lines.append("|---|---|")
                    for iv in intervals:
                        lines.append(f"| {iv['elapsed_s']} | {iv.get('tps', 'ERR')} |")
            except Exception:
                lines.append(f"  Sustained test notes: {s.notes[:200]}")
            lines.append("")

        # Quality results
        if b.quality_results:
            lines.append("### Quality Results")
            lines.append("")
            lines.append("| ID | Category | Key Found | TPS | Response (truncated) |")
            lines.append("|---|---|---|---|---|")
            for q in b.quality_results:
                resp_short = q.response.replace("\n", " ")[:120].replace("|", "\\|")
                lines.append(
                    f"| {q.prompt_id} | {q.category} | "
                    f"{'✓' if q.contains_key else '✗'} | "
                    f"{q.gen_tps:.1f} | {resp_short}... |"
                )
            lines.append("")
            passed = sum(1 for q in b.quality_results if q.contains_key)
            lines.append(f"**Key-found score: {passed}/{len(b.quality_results)}**")
            lines.append("")

            # Full responses
            lines.append("### Full Quality Responses")
            for q in b.quality_results:
                lines.append(f"#### {q.prompt_id} — {q.category}")
                lines.append(f"- Key found: {'✓' if q.contains_key else '✗'} (expected: `{next(p['expected_key'] for p in QUALITY_PROMPTS if p['id'] == q.prompt_id)}`)")
                lines.append(f"- TPS: {q.gen_tps}")
                if q.notes:
                    lines.append(f"- Notes: {q.notes}")
                lines.append("")
                lines.append("```")
                lines.append(q.response[:2000] if q.response else "(no response)")
                lines.append("```")
                lines.append("")

    path = RESULTS_DIR / f"stage4_report_{timestamp}.md"
    path.write_text("\n".join(lines), encoding="utf-8")
    print(f"[SAVED] {path}")
    return path


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import datetime

    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")

    print("B-002 Stage 4: Empirical Benchmark")
    print("=" * 70)
    print(f"Timestamp: {ts}")
    vf, vu = get_vram_mib()
    ram = get_ram_available_mib()
    print(f"Baseline VRAM: {vu:.0f} MiB used, {vf:.0f} MiB free")
    print(f"Baseline RAM:  {ram:.0f} MiB available")
    print(f"Candidate A: {CANDIDATE_A['path']}")
    print(f"Candidate B: {CANDIDATE_B['path']}")
    print()

    all_results: list[ModelBenchmark] = []

    # Run Candidate A
    bench_a = run_benchmark(CANDIDATE_A, run_sustained=True)
    all_results.append(bench_a)

    # Run Candidate B
    bench_b = run_benchmark(CANDIDATE_B, run_sustained=True)
    all_results.append(bench_b)

    # Save results
    json_path = save_json(all_results, ts)
    md_path = make_markdown_report(all_results, ts)

    print(f"\n{'='*70}")
    print("BENCHMARK COMPLETE")
    print(f"JSON: {json_path}")
    print(f"Report: {md_path}")
    print(f"{'='*70}")
