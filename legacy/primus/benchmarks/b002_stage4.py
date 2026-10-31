"""B-002 Stage 4 — Empirical benchmark.

Standalone script. No Astra imports. Run with:
    python benchmarks/b002_stage4.py [--download] [--skip-7b] [--skip-quality]

Results saved to benchmarks/results/b002_stage4_<timestamp>.json.

Windows DLL note: llama-cpp-python requires the CUDA runtime DLLs that ship with
PyTorch. This script pre-loads them in the correct order before importing
llama_cpp. The approach is a workaround for the absence of a CUDA Toolkit
installation; it works because CUDA 12.4 is satisfied by the PyTorch wheel.
"""

from __future__ import annotations

import argparse
import ctypes
import gc
import json
import os
import subprocess
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

# ---------------------------------------------------------------------------
# Windows DLL pre-load (must happen before any llama_cpp import)
# ---------------------------------------------------------------------------

TORCH_LIB = Path(sys.prefix) / "Lib" / "site-packages" / "torch" / "lib"
LLAMA_LIB = Path(sys.prefix) / "Lib" / "site-packages" / "llama_cpp" / "lib"

_CUDA_DEPS_LOADED = False


def _preload_cuda_dlls() -> bool:
    """Pre-load CUDA runtime DLLs from PyTorch's bundled copies.

    Required on Windows when no CUDA Toolkit is installed. llama-cpp-python's
    ggml-cuda.dll and llama.dll depend on cudart64_12.dll and cublas64_12.dll,
    which are present in the PyTorch wheel but not in the system PATH.

    Returns True on success, False if any DLL failed to load (CPU-only
    operation remains possible).
    """
    global _CUDA_DEPS_LOADED
    if _CUDA_DEPS_LOADED:
        return True

    if not TORCH_LIB.is_dir() or not LLAMA_LIB.is_dir():
        print(
            "WARNING: torch lib or llama lib directory not found; "
            "GPU offload may fail.",
            file=sys.stderr,
        )
        return False

    os.add_dll_directory(str(TORCH_LIB))
    os.add_dll_directory(str(LLAMA_LIB))

    cuda_deps = ["cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll"]
    ggml_chain = ["ggml-base.dll", "ggml-cpu.dll", "ggml.dll", "ggml-cuda.dll", "llama.dll"]

    ok = True
    for name in cuda_deps:
        path = TORCH_LIB / name
        if path.exists():
            try:
                ctypes.WinDLL(str(path))
            except OSError as e:
                print(f"WARNING: could not load {name}: {e}", file=sys.stderr)
                ok = False

    for name in ggml_chain:
        path = LLAMA_LIB / name
        if path.exists():
            try:
                ctypes.WinDLL(str(path), mode=0)
            except OSError as e:
                print(f"WARNING: could not load {name}: {e}", file=sys.stderr)
                ok = False

    _CUDA_DEPS_LOADED = True
    return ok


_preload_cuda_dlls()

import llama_cpp  # noqa: E402 — must be after DLL preload

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

BENCH_DIR = Path(__file__).parent
MODELS_DIR = BENCH_DIR / "models"
RESULTS_DIR = BENCH_DIR / "results"
MODELS_DIR.mkdir(exist_ok=True)
RESULTS_DIR.mkdir(exist_ok=True)

CANDIDATE_A = {
    "label": "Qwen2.5-3B-Instruct Q5_K_M",
    "repo": "Qwen/Qwen2.5-3B-Instruct-GGUF",
    "filename": "qwen2.5-3b-instruct-q5_k_m.gguf",
    "family": "qwen2.5-3b",
    "n_layers": 36,
    "quant": "Q5_K_M",
}

CANDIDATE_B = {
    "label": "Qwen2.5-7B-Instruct Q4_K_M",
    "repo": "Qwen/Qwen2.5-7B-Instruct-GGUF",
    # Split GGUF — llama.cpp loads from the first shard; both must be present.
    "filename": "qwen2.5-7b-instruct-q4_k_m-00001-of-00002.gguf",
    "extra_files": ["qwen2.5-7b-instruct-q4_k_m-00002-of-00002.gguf"],
    "family": "qwen2.5-7b",
    "n_layers": 28,
    "quant": "Q4_K_M",
}

# ---------------------------------------------------------------------------
# System measurements
# ---------------------------------------------------------------------------


def _nvidia_smi_vram_mib() -> tuple[int, int, int] | None:
    """Return (total, used, free) VRAM in MiB, or None on failure."""
    try:
        out = subprocess.check_output(
            [
                "nvidia-smi",
                "--query-gpu=memory.total,memory.used,memory.free",
                "--format=csv,noheader,nounits",
            ],
            text=True,
            timeout=10,
        ).strip()
        parts = [int(x.strip()) for x in out.split(",")]
        return (parts[0], parts[1], parts[2])
    except Exception:
        return None


def _ram_mib() -> tuple[int, int, int] | None:
    """Return (total, used, available) RAM in MiB, or None."""
    try:
        import psutil  # type: ignore

        m = psutil.virtual_memory()
        return (m.total // (1024 * 1024), m.used // (1024 * 1024), m.available // (1024 * 1024))
    except ImportError:
        return None


@dataclass
class SystemSnapshot:
    vram_total_mib: int | None = None
    vram_used_mib: int | None = None
    vram_free_mib: int | None = None
    ram_total_mib: int | None = None
    ram_used_mib: int | None = None
    ram_available_mib: int | None = None
    timestamp: float = field(default_factory=time.time)

    @classmethod
    def capture(cls) -> "SystemSnapshot":
        s = cls()
        vram = _nvidia_smi_vram_mib()
        if vram:
            s.vram_total_mib, s.vram_used_mib, s.vram_free_mib = vram
        ram = _ram_mib()
        if ram:
            s.ram_total_mib, s.ram_used_mib, s.ram_available_mib = ram
        return s

    def vram_delta_mib(self, before: "SystemSnapshot") -> int | None:
        if self.vram_used_mib is None or before.vram_used_mib is None:
            return None
        return self.vram_used_mib - before.vram_used_mib

    def ram_delta_mib(self, before: "SystemSnapshot") -> int | None:
        if self.ram_used_mib is None or before.ram_used_mib is None:
            return None
        return self.ram_used_mib - before.ram_used_mib


# ---------------------------------------------------------------------------
# Qwen2.5 chat format
# ---------------------------------------------------------------------------

_QWEN_SYSTEM = "You are Astra, a capable AI assistant. Be concise and accurate."

_QWEN_TEMPLATE = (
    "<|im_start|>system\n{system}<|im_end|>\n"
    "<|im_start|>user\n{user}<|im_end|>\n"
    "<|im_start|>assistant\n"
)


def _fmt(user: str, system: str = _QWEN_SYSTEM) -> str:
    return _QWEN_TEMPLATE.format(system=system, user=user)


# ---------------------------------------------------------------------------
# Evaluation prompt suite
# ---------------------------------------------------------------------------

# Fixed prompts. Deterministic, Astra-oriented, short enough for 2048-token
# context. Each has a category and a brief rubric for qualitative scoring.

EVAL_PROMPTS: list[dict[str, Any]] = [
    {
        "id": "Q01-basic-reasoning",
        "category": "basic_reasoning",
        "prompt": _fmt(
            "A bat and a ball cost $1.10 together. The bat costs $1.00 more "
            "than the ball. How much does the ball cost? Show your reasoning."
        ),
        "rubric": "Must reach $0.05 (not $0.10). Show algebra or clear logic.",
        "max_tokens": 256,
    },
    {
        "id": "Q02-multi-step-reasoning",
        "category": "multi_step_reasoning",
        "prompt": _fmt(
            "Three friends — Alice, Bob, and Carol — each have some money. "
            "Alice has $10 more than Bob. Bob has $5 more than Carol. "
            "Together they have $85. How much does each person have? "
            "Show each step."
        ),
        "rubric": "Correct: Carol=$20, Bob=$25, Alice=$35. Must show steps.",
        "max_tokens": 300,
    },
    {
        "id": "Q03-c-programming",
        "category": "c_programming",
        "prompt": _fmt(
            "Write a correct C function:\n"
            "  int *remove_duplicates(int *arr, int n, int *out_len);\n"
            "It removes duplicates from a sorted array in-place and returns "
            "a pointer to the same array with *out_len set to the new length. "
            "Include no extra headers beyond <stdlib.h>."
        ),
        "rubric": "Must be syntactically correct C. O(n) time. No extra allocation.",
        "max_tokens": 400,
    },
    {
        "id": "Q04-python-programming",
        "category": "python_programming",
        "prompt": _fmt(
            "Write a Python decorator `retry(times, delay_sec)` that retries "
            "a function up to `times` attempts if it raises an exception, "
            "waiting `delay_sec` seconds between attempts. "
            "Preserve the function's name and docstring. "
            "Raise the last exception if all attempts fail."
        ),
        "rubric": "Correct use of functools.wraps. Proper exception re-raise. No extra deps.",
        "max_tokens": 400,
    },
    {
        "id": "Q05-debugging",
        "category": "debugging",
        "prompt": _fmt(
            "Find and fix all bugs in this Python function:\n\n"
            "```python\n"
            "def binary_search(arr, target):\n"
            "    lo, hi = 0, len(arr)\n"
            "    while lo < hi:\n"
            "        mid = (lo + hi) / 2\n"
            "        if arr[mid] == target:\n"
            "            return mid\n"
            "        elif arr[mid] < target:\n"
            "            lo = mid\n"
            "        else:\n"
            "            hi = mid\n"
            "    return -1\n"
            "```\n\n"
            "List each bug and the fix."
        ),
        "rubric": (
            "Bugs: hi=len(arr) should be len(arr)-1 or hi exclusive; "
            "mid = (lo+hi)/2 should use //; lo=mid causes infinite loop (should be mid+1); "
            "hi=mid also wrong when hi is exclusive. Must identify at least 2 of 3."
        ),
        "max_tokens": 400,
    },
    {
        "id": "Q06-architecture-design",
        "category": "architecture_design",
        "prompt": _fmt(
            "Design a simple LRU (Least Recently Used) cache in Python. "
            "Requirements: O(1) get and put. Maximum capacity enforced. "
            "Evicts the least recently used item when full. "
            "Give the class definition with method signatures and a brief "
            "explanation of the data structures chosen."
        ),
        "rubric": "Must mention OrderedDict or doubly-linked list + hashmap. O(1) operations.",
        "max_tokens": 500,
    },
    {
        "id": "Q07-complex-instructions",
        "category": "complex_instructions",
        "prompt": _fmt(
            "You must respond with ONLY a JSON object and nothing else — "
            "no prose before or after, no markdown code fences. "
            "The JSON must have exactly these keys: "
            "'status' (string, either 'ok' or 'error'), "
            "'message' (string, a brief greeting), "
            "'timestamp' (integer, use 0 as a placeholder), "
            "'items' (array of exactly 3 strings: 'alpha', 'beta', 'gamma'). "
            "Any deviation from this format is a failure."
        ),
        "rubric": "Response must be valid JSON with exactly those keys and values. No extra text.",
        "max_tokens": 150,
    },
    {
        "id": "Q08-planning",
        "category": "planning",
        "prompt": _fmt(
            "Create a concrete, step-by-step plan to migrate a SQLite database "
            "to PostgreSQL for a small Python web application. "
            "The plan must: identify risks, include a rollback strategy, "
            "and be ordered so that each step can be verified before the next begins. "
            "Maximum 8 steps."
        ),
        "rubric": "Ordered steps. Must mention schema export, data migration, testing, rollback.",
        "max_tokens": 500,
    },
    {
        "id": "Q09-summarization",
        "category": "summarization",
        "prompt": _fmt(
            "Summarise the following text in exactly 3 bullet points. "
            "Each bullet must be one sentence. Do not add commentary.\n\n"
            "TEXT:\n"
            "The Ryzen 5 5600H is a 6-core, 12-thread processor from AMD's "
            "Zen 3 microarchitecture, released in 2021. It targets laptop "
            "performance with a 35–54W TDP and boost clocks up to 4.2 GHz. "
            "The chip includes integrated Radeon graphics and is manufactured "
            "on TSMC's 7nm node. It competes primarily with Intel's Tiger Lake-H "
            "lineup and offers strong multi-threaded performance for its class. "
            "Battery life in thin-and-light designs benefits from its aggressive "
            "frequency scaling. Software support via AMD's driver stack is mature "
            "on both Windows and Linux."
        ),
        "rubric": "Exactly 3 bullets. No heading. Each bullet one sentence. Accurate content.",
        "max_tokens": 200,
    },
    {
        "id": "Q10-tool-call-output",
        "category": "tool_function_call",
        "prompt": _fmt(
            "Generate a JSON function call in the following format:\n"
            '{"name": "<function_name>", "arguments": {<key-value pairs>}}\n\n'
            "Function: search_memory\n"
            "Description: Search Astra's memory store for relevant records.\n"
            "Parameters:\n"
            "  query (string, required): the search query\n"
            "  category (string, optional): one of 'fact', 'preference', 'event', 'skill'\n"
            "  limit (integer, optional, default 5): max results to return\n\n"
            "Generate a call to find memories about Python debugging, "
            "filtered to the 'skill' category, returning at most 3 results. "
            "Output ONLY the JSON object."
        ),
        "rubric": (
            'Must be valid JSON: {"name": "search_memory", '
            '"arguments": {"query": "...", "category": "skill", "limit": 3}}. '
            "No prose."
        ),
        "max_tokens": 150,
    },
    {
        "id": "Q11-long-context-retrieval",
        "category": "long_context_retrieval",
        "prompt": _fmt(
            "The following is a configuration excerpt. Answer the specific "
            "questions at the end using ONLY information from the text.\n\n"
            "--- CONFIG ---\n"
            "database_path: /data/astra/memory.db\n"
            "vector_index_path: /data/astra/index.faiss\n"
            "embedding_dim: 768\n"
            "context_tokens: 4096\n"
            "gpu_layers: 36\n"
            "embedding_gpu_layers: 0\n"
            "model: qwen2.5-3b-instruct\n"
            "embedding_model: nomic-embed-text-v1.5\n"
            "log_level: INFO\n"
            "require_human_approval: true\n"
            "allow_outbound_network: false\n"
            "backup_count: 5\n"
            "--- END CONFIG ---\n\n"
            "Questions (answer each on its own line, labelled Q1–Q4):\n"
            "Q1: What is the embedding dimension?\n"
            "Q2: How many GPU layers are used for the embedding model?\n"
            "Q3: Is outbound network allowed?\n"
            "Q4: What is the chat model name?"
        ),
        "rubric": "Q1=768, Q2=0, Q3=no/false, Q4=qwen2.5-3b-instruct. All 4 must be correct.",
        "max_tokens": 200,
    },
    {
        "id": "Q12-error-detection-correction",
        "category": "error_detection_correction",
        "prompt": _fmt(
            "The following Python class has multiple errors. "
            "List each error with its line reference and provide a corrected version:\n\n"
            "```python\n"
            "class Stack:\n"
            "    def __init__(self):\n"
            "        self.items = None  # bug 1\n"
            "\n"
            "    def push(self, item):\n"
            "        self.items.append(item)\n"
            "\n"
            "    def pop(self):\n"
            "        if self.is_empty():\n"
            "            raise IndexError('Stack is empty')\n"
            "        return self.items.pop\n"  # noqa: E501 — intentional trailing space
            "\n"
            "    def peek(self):\n"
            "        return self.items[-1]  # bug 3 (no empty check)\n"
            "\n"
            "    def is_empty(self):\n"
            "        return len(self.items) == 0\n"
            "```"
        ),
        "rubric": (
            "Bug 1: items=None should be []. "
            "Bug 2: items.pop missing (). "
            "Bug 3: peek crashes on empty (no guard). Must identify all 3."
        ),
        "max_tokens": 500,
    },
]

# ---------------------------------------------------------------------------
# Model download
# ---------------------------------------------------------------------------


def download_model(
    repo_id: str,
    filename: str,
    dest: Path,
    extra_files: list[str] | None = None,
) -> Path:
    """Download a GGUF file (and optional extra shards) from HuggingFace Hub."""
    from huggingface_hub import hf_hub_download  # type: ignore

    all_files = [filename] + (extra_files or [])
    for fn in all_files:
        target = dest / fn
        if target.exists():
            size_mib = target.stat().st_size // (1024 * 1024)
            print(f"  Already present: {fn} ({size_mib} MiB)")
        else:
            print(f"  Downloading {fn} from {repo_id} ...")
            try:
                path = hf_hub_download(
                    repo_id=repo_id,
                    filename=fn,
                    local_dir=str(dest),
                )
                size_mib = Path(path).stat().st_size // (1024 * 1024)
                print(f"  Downloaded: {path} ({size_mib} MiB)")
            except Exception as e:
                print(f"  ERROR downloading {fn}: {e}", file=sys.stderr)
                raise

    return dest / filename


# ---------------------------------------------------------------------------
# Performance benchmark
# ---------------------------------------------------------------------------

PREFILL_PROBE = "The capital of France is"
_PREFILL_WARMUP_TOKENS = 10


@dataclass
class PerfResult:
    model_label: str
    gpu_layers: int
    context_tokens: int
    pre_load: SystemSnapshot = field(default_factory=SystemSnapshot.capture)
    post_load: SystemSnapshot = field(default_factory=SystemSnapshot.capture)
    load_wall_sec: float = 0.0
    vram_delta_load_mib: int | None = None
    ram_delta_load_mib: int | None = None
    prefill_toks_per_sec: float | None = None
    gen_toks_per_sec: float | None = None
    ttft_ms: float | None = None
    gen_tokens_produced: int = 0
    gen_sample_text: str = ""
    error: str | None = None
    context_size_note: str = ""


def _run_perf(
    model_path: Path,
    label: str,
    gpu_layers: int,
    context_tokens: int,
    threads: int | None = None,
    verbose: bool = False,
) -> PerfResult:
    r = PerfResult(model_label=label, gpu_layers=gpu_layers, context_tokens=context_tokens)
    r.pre_load = SystemSnapshot.capture()

    try:
        print(
            f"    Loading model: gpu_layers={gpu_layers}, ctx={context_tokens} ...",
            end="",
            flush=True,
        )
        load_start = time.perf_counter()
        kwargs: dict[str, Any] = {
            "model_path": str(model_path),
            "n_ctx": context_tokens,
            "n_gpu_layers": gpu_layers,
            "verbose": verbose,
            "n_batch": 512,
        }
        if threads is not None:
            kwargs["n_threads"] = threads

        model = llama_cpp.Llama(**kwargs)
        load_elapsed = time.perf_counter() - load_start
        r.load_wall_sec = load_elapsed
        r.post_load = SystemSnapshot.capture()
        r.vram_delta_load_mib = r.post_load.vram_delta_mib(r.pre_load)
        r.ram_delta_load_mib = r.post_load.ram_delta_mib(r.pre_load)
        print(
            f" loaded in {load_elapsed:.1f}s | "
            f"VRAM Δ={r.vram_delta_load_mib}MiB | "
            f"RAM Δ={r.ram_delta_load_mib}MiB"
        )

        # Warmup pass (don't time)
        _ = model.create_completion(
            PREFILL_PROBE,
            max_tokens=4,
            temperature=0.0,
        )

        # Prefill speed: tokenise a 512-token prompt and time the prompt-processing.
        # We use a repeated string that's long enough to force significant prefill work.
        # llama.cpp processes the whole prompt as a batch; timing from call start to
        # first-token gives us prefill + one decode step.
        # We measure prefill as: input_token_count / (first_token_time - decode_time).
        # Simpler approximation: use create_completion with max_tokens=1 and time it.

        prefill_prompt = _fmt(
            "Please analyse the following code and explain what it does, "
            "what edge cases it misses, and how you would improve it.\n\n"
            "```python\n"
            + ("x = x + 1\n" * 60)
            + "```"
        )

        t0 = time.perf_counter()
        _ = model.create_completion(prefill_prompt, max_tokens=1, temperature=0.0)
        t1 = time.perf_counter()

        prefill_tok_count = len(model.tokenize(prefill_prompt.encode()))
        prefill_elapsed = t1 - t0
        r.prefill_toks_per_sec = prefill_tok_count / prefill_elapsed if prefill_elapsed > 0 else None

        # Time to first token (TTFT) on a realistic Astra prompt
        astra_prompt = _fmt(
            "Summarise your capabilities as an AI assistant in three sentences."
        )
        t0 = time.perf_counter()
        _ = model.create_completion(astra_prompt, max_tokens=1, temperature=0.0)
        t1 = time.perf_counter()
        r.ttft_ms = (t1 - t0) * 1000

        # Generation speed: generate 128 tokens with temperature=0, fixed seed
        gen_prompt = _fmt("Explain the difference between a stack and a queue in data structures.")
        gen_start = time.perf_counter()
        gen_response = model.create_completion(
            gen_prompt,
            max_tokens=128,
            temperature=0.0,
            seed=42,
        )
        gen_elapsed = time.perf_counter() - gen_start
        gen_text = gen_response["choices"][0]["text"]
        gen_tokens = gen_response.get("usage", {}).get("completion_tokens", len(gen_text.split()))
        r.gen_toks_per_sec = gen_tokens / gen_elapsed if gen_elapsed > 0 else None
        r.gen_tokens_produced = gen_tokens
        r.gen_sample_text = gen_text[:200]

        print(
            f"    TTFT={r.ttft_ms:.0f}ms | "
            f"prefill={r.prefill_toks_per_sec:.0f} tok/s | "
            f"gen={r.gen_toks_per_sec:.1f} tok/s"
        )

    except Exception as e:
        r.error = str(e)
        print(f" ERROR: {e}", file=sys.stderr)
    finally:
        try:
            del model
        except Exception:
            pass
        gc.collect()
        time.sleep(3)  # let VRAM settle before next test

    return r


# ---------------------------------------------------------------------------
# Quality benchmark
# ---------------------------------------------------------------------------


@dataclass
class QualResult:
    model_label: str
    gpu_layers: int
    context_tokens: int
    prompt_id: str
    category: str
    response: str = ""
    response_tokens: int = 0
    elapsed_ms: float = 0.0
    error: str | None = None


def _run_quality(
    model_path: Path,
    label: str,
    gpu_layers: int,
    context_tokens: int = 4096,
    threads: int | None = None,
) -> list[QualResult]:
    """Run all EVAL_PROMPTS against a single loaded model."""
    results: list[QualResult] = []

    print(f"    Loading for quality eval: gpu_layers={gpu_layers}, ctx={context_tokens} ...")
    try:
        kwargs: dict[str, Any] = {
            "model_path": str(model_path),
            "n_ctx": context_tokens,
            "n_gpu_layers": gpu_layers,
            "verbose": False,
            "n_batch": 512,
            "seed": 42,
        }
        if threads is not None:
            kwargs["n_threads"] = threads

        model = llama_cpp.Llama(**kwargs)
    except Exception as e:
        for p in EVAL_PROMPTS:
            results.append(
                QualResult(
                    model_label=label,
                    gpu_layers=gpu_layers,
                    context_tokens=context_tokens,
                    prompt_id=p["id"],
                    category=p["category"],
                    error=str(e),
                )
            )
        return results

    for p in EVAL_PROMPTS:
        qr = QualResult(
            model_label=label,
            gpu_layers=gpu_layers,
            context_tokens=context_tokens,
            prompt_id=p["id"],
            category=p["category"],
        )
        try:
            t0 = time.perf_counter()
            resp = model.create_completion(
                p["prompt"],
                max_tokens=p["max_tokens"],
                temperature=0.0,
                seed=42,
                stop=["<|im_end|>", "<|im_start|>"],
            )
            elapsed = time.perf_counter() - t0
            qr.response = resp["choices"][0]["text"].strip()
            qr.response_tokens = resp.get("usage", {}).get("completion_tokens", 0)
            qr.elapsed_ms = elapsed * 1000
            print(f"      {p['id']} ({qr.response_tokens} tok, {elapsed:.1f}s)")
        except Exception as e:
            qr.error = str(e)
            print(f"      {p['id']} ERROR: {e}", file=sys.stderr)

        results.append(qr)

    try:
        del model
    except Exception:
        pass
    gc.collect()
    time.sleep(3)

    return results


# ---------------------------------------------------------------------------
# Report helpers
# ---------------------------------------------------------------------------


def _bar(value: float | None, max_val: float, width: int = 20) -> str:
    if value is None:
        return "-" * width
    filled = int(min(value / max_val, 1.0) * width)
    return "█" * filled + "░" * (width - filled)


def print_perf_table(results: list[PerfResult]) -> None:
    print("\n" + "=" * 80)
    print("PERFORMANCE RESULTS")
    print("=" * 80)
    header = (
        f"{'Model':<38} {'GPU':>4} {'CTX':>5} {'VRAMΔ':>6} "
        f"{'RAMΔ':>6} {'TTFT':>6} {'Prefill':>8} {'Gen':>7}"
    )
    print(header)
    print("-" * 80)

    for r in results:
        if r.error:
            print(
                f"{r.model_label[:37]:<38} {r.gpu_layers:>4} {r.context_tokens:>5} "
                f"  ERROR: {r.error[:30]}"
            )
            continue
        print(
            f"{r.model_label[:37]:<38} {r.gpu_layers:>4} {r.context_tokens:>5} "
            f"{(str(r.vram_delta_load_mib)+'M') if r.vram_delta_load_mib else 'N/A':>6} "
            f"{(str(r.ram_delta_load_mib)+'M') if r.ram_delta_load_mib else 'N/A':>6} "
            f"{(f'{r.ttft_ms:.0f}ms') if r.ttft_ms else 'N/A':>6} "
            f"{(f'{r.prefill_toks_per_sec:.0f}t/s') if r.prefill_toks_per_sec else 'N/A':>8} "
            f"{(f'{r.gen_toks_per_sec:.1f}t/s') if r.gen_toks_per_sec else 'N/A':>7}"
        )
    print("=" * 80)


def print_quality_summary(results: list[QualResult]) -> None:
    print("\n" + "=" * 80)
    print("QUALITY BENCHMARK RESPONSES")
    print("=" * 80)

    by_model: dict[str, list[QualResult]] = {}
    for r in results:
        key = f"{r.model_label} (gpu={r.gpu_layers}, ctx={r.context_tokens})"
        by_model.setdefault(key, []).append(r)

    for model_key, model_results in by_model.items():
        print(f"\n{'─'*80}")
        print(f"MODEL: {model_key}")
        print(f"{'─'*80}")
        for r in model_results:
            print(f"\n[{r.prompt_id}] {r.category}")
            if r.error:
                print(f"  ERROR: {r.error}")
            else:
                print(f"  ({r.response_tokens} tokens, {r.elapsed_ms:.0f}ms)")
                # Print first 600 chars of response
                snippet = r.response[:600]
                for line in snippet.split("\n"):
                    print(f"  {line}")
                if len(r.response) > 600:
                    print(f"  ... [{len(r.response)-600} chars truncated]")


# ---------------------------------------------------------------------------
# Main orchestration
# ---------------------------------------------------------------------------


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="B-002 Stage 4 benchmark")
    p.add_argument("--download", action="store_true", help="Download models before benchmarking")
    p.add_argument("--skip-7b", action="store_true", help="Skip Candidate B (7B) tests")
    p.add_argument("--skip-quality", action="store_true", help="Skip quality eval (faster)")
    p.add_argument("--threads", type=int, default=None, help="CPU threads (default: auto)")
    p.add_argument(
        "--contexts-3b",
        nargs="+",
        type=int,
        default=[2048, 4096, 8192],
        help="Context sizes for 3B model",
    )
    p.add_argument(
        "--gpu-layers-7b",
        nargs="+",
        type=int,
        default=[0, 10, 15, 20, 25, 28],
        help="GPU layer configs for 7B sweep",
    )
    return p.parse_args()


def main() -> None:
    args = parse_args()

    print("\n" + "=" * 80)
    print("B-002 STAGE 4 — EMPIRICAL BENCHMARK")
    print("=" * 80)

    # Baseline system state
    print("\n[SYSTEM BASELINE]")
    baseline = SystemSnapshot.capture()
    print(
        f"  VRAM: {baseline.vram_total_mib}MiB total, "
        f"{baseline.vram_used_mib}MiB used, "
        f"{baseline.vram_free_mib}MiB free"
    )
    print(
        f"  RAM:  {baseline.ram_total_mib}MiB total, "
        f"{baseline.ram_used_mib}MiB used, "
        f"{baseline.ram_available_mib}MiB available"
    )

    if baseline.ram_available_mib is not None and baseline.ram_available_mib < 2000:
        print(
            f"  WARNING: Only {baseline.ram_available_mib}MiB RAM free. "
            "The 7B partial-offload test will likely cause significant paging. "
            "Close other applications for a more representative result."
        )

    # Resolve model paths
    path_a = MODELS_DIR / CANDIDATE_A["filename"]
    path_b = MODELS_DIR / CANDIDATE_B["filename"]

    if args.download:
        print("\n[DOWNLOADING MODELS]")
        print(f"Candidate A: {CANDIDATE_A['label']}")
        path_a = download_model(
            CANDIDATE_A["repo"],
            CANDIDATE_A["filename"],
            MODELS_DIR,
            CANDIDATE_A.get("extra_files"),
        )

        if not args.skip_7b:
            print(f"Candidate B: {CANDIDATE_B['label']}")
            path_b = download_model(
                CANDIDATE_B["repo"],
                CANDIDATE_B["filename"],
                MODELS_DIR,
                CANDIDATE_B.get("extra_files"),
            )

    # Check presence
    if not path_a.exists():
        print(
            f"ERROR: {CANDIDATE_A['filename']} not found at {path_a}. "
            "Run with --download or place the file manually.",
            file=sys.stderr,
        )
        sys.exit(1)

    if not args.skip_7b and not path_b.exists():
        print(
            f"WARNING: {CANDIDATE_B['filename']} not found at {path_b}. "
            "Skipping 7B tests.",
            file=sys.stderr,
        )
        args.skip_7b = True

    all_perf: list[PerfResult] = []
    all_qual: list[QualResult] = []

    # -----------------------------------------------------------------------
    # Candidate A — Qwen2.5-3B-Instruct Q5_K_M
    # -----------------------------------------------------------------------
    print(f"\n{'='*80}")
    print(f"CANDIDATE A: {CANDIDATE_A['label']}")
    print(f"  Path: {path_a}")
    print(f"  Size: {path_a.stat().st_size // (1024*1024)} MiB")
    print(f"{'='*80}")

    full_gpu_layers = 99  # llama.cpp convention: value > layer count = all layers

    for ctx in args.contexts_3b:
        print(f"\n[3B | gpu=99 (all) | ctx={ctx}]")
        r = _run_perf(
            path_a,
            CANDIDATE_A["label"],
            gpu_layers=full_gpu_layers,
            context_tokens=ctx,
            threads=args.threads,
        )
        all_perf.append(r)

    if not args.skip_quality:
        print(f"\n[3B | QUALITY EVAL | gpu=99 | ctx=4096]")
        qual_a = _run_quality(
            path_a,
            CANDIDATE_A["label"],
            gpu_layers=full_gpu_layers,
            context_tokens=4096,
            threads=args.threads,
        )
        all_qual.extend(qual_a)

    # -----------------------------------------------------------------------
    # Candidate B — Qwen2.5-7B-Instruct Q4_K_M  (partial offload sweep)
    # -----------------------------------------------------------------------
    if not args.skip_7b:
        print(f"\n{'='*80}")
        print(f"CANDIDATE B: {CANDIDATE_B['label']}")
        print(f"  Path: {path_b}")
        print(f"  Size: {path_b.stat().st_size // (1024*1024)} MiB")
        print(f"{'='*80}")
        print(
            f"  Sweeping gpu_layers in {args.gpu_layers_7b}. "
            "Context=2048 for all sweep configs. "
            "Will test ctx=4096 for the fastest successful config."
        )

        best_7b_perf: PerfResult | None = None

        for gl in args.gpu_layers_7b:
            print(f"\n[7B | gpu={gl} | ctx=2048]")
            r = _run_perf(
                path_b,
                CANDIDATE_B["label"],
                gpu_layers=gl,
                context_tokens=2048,
                threads=args.threads,
            )
            all_perf.append(r)

            if r.error is None and r.gen_toks_per_sec is not None:
                if best_7b_perf is None or r.gen_toks_per_sec > (
                    best_7b_perf.gen_toks_per_sec or 0
                ):
                    best_7b_perf = r

        # Test ctx=4096 with the best stable config
        if best_7b_perf is not None:
            best_gl = best_7b_perf.gpu_layers
            print(f"\n[7B | gpu={best_gl} (best stable) | ctx=4096]")
            r4k = _run_perf(
                path_b,
                CANDIDATE_B["label"],
                gpu_layers=best_gl,
                context_tokens=4096,
                threads=args.threads,
            )
            all_perf.append(r4k)

        if not args.skip_quality and best_7b_perf is not None:
            best_gl = best_7b_perf.gpu_layers
            print(f"\n[7B | QUALITY EVAL | gpu={best_gl} | ctx=4096]")
            qual_b = _run_quality(
                path_b,
                CANDIDATE_B["label"],
                gpu_layers=best_gl,
                context_tokens=4096,
                threads=args.threads,
            )
            all_qual.extend(qual_b)

    # -----------------------------------------------------------------------
    # Print reports
    # -----------------------------------------------------------------------
    print_perf_table(all_perf)
    if all_qual:
        print_quality_summary(all_qual)

    # -----------------------------------------------------------------------
    # Save results to JSON
    # -----------------------------------------------------------------------
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    result_file = RESULTS_DIR / f"b002_stage4_{timestamp}.json"

    payload = {
        "benchmark": "B-002 Stage 4",
        "timestamp": timestamp,
        "system_baseline": asdict(baseline),
        "candidate_a": CANDIDATE_A,
        "candidate_b": CANDIDATE_B,
        "performance": [asdict(r) for r in all_perf],
        "quality": [asdict(r) for r in all_qual],
    }

    with open(result_file, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, default=str)

    print(f"\nResults saved to: {result_file}")
    print("\nBenchmark complete. Review results before any production changes.")
    print("Next step: update ADR-0005 with these measurements.")


if __name__ == "__main__":
    main()
