# -*- coding: utf-8 -*-
"""
B-002 Stage 4: Empirical benchmark.
Tests Candidate A (Qwen2.5-3B Q5_K_M) and Candidate B (Dolphin/Llama3.1-8B Q5_K_S)
across multiple GPU-layer configs and context sizes, then runs quality evaluation.

Run with:  python benchmarks/run_benchmark.py
Results:   benchmarks/results/benchmark_<timestamp>.json
"""

from __future__ import annotations

import gc
import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass, asdict, field
from pathlib import Path
from typing import Optional

# -- DLL bootstrap (must happen before llama_cpp import) ----------------------
TORCH_LIB = r"C:\Users\ASUS\anaconda3\envs\Chamber\Lib\site-packages\torch\lib"
LLAMA_LIB = (
    r"C:\Users\ASUS\AppData\Local\Programs\Python\Python312"
    r"\Lib\site-packages\llama_cpp\lib"
)

def _bootstrap_dlls() -> bool:
    """Pre-load llama.cpp DLL dependencies so Windows can resolve them."""
    import ctypes
    for path in [TORCH_LIB, LLAMA_LIB]:
        if os.path.exists(path):
            os.add_dll_directory(path)
    order = ["ggml-base.dll", "ggml-cpu.dll", "ggml.dll", "ggml-cuda.dll"]
    for name in order:
        full = os.path.join(LLAMA_LIB, name)
        if os.path.exists(full):
            try:
                ctypes.CDLL(full)
            except OSError:
                pass  # ggml-cuda might still fail; llama.dll will try CPU path
    return True


_bootstrap_dlls()

# Force line-buffered output so progress is visible through PowerShell pipes.
sys.stdout.reconfigure(line_buffering=True)
sys.stderr.reconfigure(line_buffering=True)

import psutil  # noqa: E402 (after DLL fix)
import llama_cpp  # noqa: E402

# -- Constants -----------------------------------------------------------------

MODELS = {
    "3B_Q5KM": Path(r"C:\Users\ASUS\Documents\Astra\benchmarks\models\qwen2.5-3b-instruct-q5_k_m.gguf"),
    "8B_Q5KS": Path(r"C:\Users\ASUS\Downloads\Dolphin3.0-Llama3.1-8B-Q5_K_S.gguf"),
}

# GPU-layer configs to sweep for each model.
# 3B: single config (full offload = 99).
# 8B: sweep 0, 10, 18, 24, 32 (partial to near-full).
GPU_LAYERS_3B = [99]
GPU_LAYERS_8B = [0, 10, 18, 24, 32]

CONTEXT_SIZES = [2048, 4096, 8192]

SEED = 42
N_THREADS = 12  # all logical cores
N_BATCH = 512

# -- System helpers -------------------------------------------------------------

def vram_mib() -> dict:
    """Return VRAM stats from nvidia-smi (total/free/used in MiB)."""
    try:
        out = subprocess.check_output(
            ["nvidia-smi", "--query-gpu=memory.total,memory.free,memory.used",
             "--format=csv,noheader,nounits"],
            text=True, timeout=5,
        ).strip()
        parts = [int(x.strip()) for x in out.split(",")]
        return {"total_mib": parts[0], "free_mib": parts[1], "used_mib": parts[2]}
    except Exception as e:
        return {"error": str(e)}


def ram_mib() -> dict:
    """Return system RAM stats in MiB for the current process and system."""
    vm = psutil.virtual_memory()
    proc = psutil.Process(os.getpid())
    return {
        "system_total_mib": vm.total // 1048576,
        "system_available_mib": vm.available // 1048576,
        "system_used_pct": vm.percent,
        "process_rss_mib": proc.memory_info().rss // 1048576,
    }


def wait_settle(seconds: float = 1.0):
    time.sleep(seconds)

# -- Prompts -------------------------------------------------------------------

SPEED_PROMPT = (
    "You are a helpful assistant. "
    "Write a detailed, step-by-step explanation of how the Python garbage collector "
    "works, covering reference counting, cyclic garbage collection, the three "
    "generations, thresholds, and when __del__ is called. Include a worked example "
    "showing a reference cycle being collected. Aim for completeness."
)

PREFILL_FILLER = "The quick brown fox jumps over the lazy dog. " * 50  # ~450 tokens

QUALITY_PROMPTS = [
    {
        "id": "basic_reasoning",
        "task": "Basic reasoning",
        "prompt": (
            "A farmer has 17 sheep. All but 9 die. How many sheep does the farmer "
            "have left? Think step by step and give a single numeric answer."
        ),
        "expected_contains": "9",
    },
    {
        "id": "multi_step_reasoning",
        "task": "Multi-step reasoning",
        "prompt": (
            "Alice, Bob, and Carol each have some apples. Alice has twice as many "
            "as Bob. Carol has 3 fewer than Alice. Together they have 29 apples. "
            "How many apples does each person have? Show all working."
        ),
        "expected_contains": None,  # qualitative
    },
    {
        "id": "c_programming",
        "task": "C programming",
        "prompt": (
            "Write a correct C function that reverses a null-terminated string "
            "in-place without allocating heap memory. Include the function signature "
            "and a brief test in main()."
        ),
        "expected_contains": "char",
    },
    {
        "id": "python_programming",
        "task": "Python programming",
        "prompt": (
            "Write a Python function that, given a list of integers, returns the "
            "two indices whose values sum to a target. Use a hash-map approach for "
            "O(n) time complexity. Include type annotations and a docstring."
        ),
        "expected_contains": "def",
    },
    {
        "id": "debugging",
        "task": "Debugging",
        "prompt": (
            "Find the bug in this Python code and explain why it is wrong:\n\n"
            "```python\n"
            "def remove_duplicates(lst):\n"
            "    for i in range(len(lst)):\n"
            "        if lst[i] in lst[i+1:]:\n"
            "            lst.pop(i)\n"
            "    return lst\n"
            "```"
        ),
        "expected_contains": None,
    },
    {
        "id": "architecture_design",
        "task": "Architecture/design reasoning",
        "prompt": (
            "Design a minimal job-queue system for a single-server Python "
            "application. The requirements: tasks must survive a process crash, "
            "order must be preserved, and at-least-once delivery is acceptable. "
            "Name the components, describe their responsibilities, and justify "
            "your storage choice in two sentences."
        ),
        "expected_contains": None,
    },
    {
        "id": "instruction_following",
        "task": "Following complex instructions",
        "prompt": (
            "List exactly five prime numbers between 10 and 50. "
            "Format your response as a JSON array of integers. "
            "Do not include any text outside the JSON array. "
            "Do not include primes outside the range 10–50."
        ),
        "expected_contains": "[",
    },
    {
        "id": "planning",
        "task": "Planning",
        "prompt": (
            "You need to migrate a 10 GB SQLite database to PostgreSQL with "
            "zero downtime. Create a numbered step-by-step migration plan. "
            "Each step must be actionable and include a verification step."
        ),
        "expected_contains": None,
    },
    {
        "id": "summarization",
        "task": "Summarization",
        "prompt": (
            "Summarize the following paragraph in exactly two sentences:\n\n"
            "Transformer models process sequences of tokens by first converting "
            "them into dense vector representations called embeddings. These "
            "embeddings are then passed through multiple layers of self-attention "
            "mechanisms, where each token can attend to every other token in the "
            "sequence. The self-attention operation computes query, key, and value "
            "projections, combines them via scaled dot-product attention, and "
            "produces a context-aware representation for each position. Feed-forward "
            "sub-layers follow each attention layer, applying a non-linear "
            "transformation independently at each position."
        ),
        "expected_contains": None,
    },
    {
        "id": "tool_call_output",
        "task": "Tool/function-call style output",
        "prompt": (
            "You have access to a function called `search_web` with signature:\n"
            "search_web(query: str, max_results: int = 5) -> list[dict]\n\n"
            "A user asked: 'What is the capital of France?'\n\n"
            "Respond only with a JSON object representing the function call you "
            "would make. Format: {\"name\": \"...\", \"arguments\": {...}}"
        ),
        "expected_contains": "search_web",
    },
    {
        "id": "long_context_retrieval",
        "task": "Long-context retrieval",
        "prompt": (
            "The following is a list of configuration values for a fictional system:\n\n"
            "DATABASE_HOST=prod-db-01.internal\n"
            "DATABASE_PORT=5432\n"
            "DATABASE_NAME=appdb\n"
            "CACHE_TTL=300\n"
            "MAX_CONNECTIONS=100\n"
            "SECRET_KEY=xK9#mP2$vL7@nQ4\n"
            "LOG_LEVEL=WARNING\n"
            "WORKERS=4\n"
            "TIMEOUT_SECONDS=30\n"
            "ENABLE_METRICS=true\n\n"
            "What is the value of SECRET_KEY? "
            "Reply with only the exact value, nothing else."
        ),
        "expected_contains": "xK9#mP2$vL7@nQ4",
    },
    {
        "id": "error_detection_correction",
        "task": "Error detection and self-correction",
        "prompt": (
            "I claimed that the output of this Python expression is 8:\n"
            "    2 ** 3 + 1\n"
            "Is this correct? If not, what is the actual output and why?"
        ),
        "expected_contains": "9",
    },
]

# -- Benchmark core ------------------------------------------------------------

@dataclass
class SpeedResult:
    context_tokens: int
    gpu_layers: int
    vram_before_mib: dict = field(default_factory=dict)
    ram_before_mib: dict = field(default_factory=dict)
    vram_after_load_mib: dict = field(default_factory=dict)
    ram_after_load_mib: dict = field(default_factory=dict)
    load_time_s: float = 0.0
    prefill_tps: float = 0.0     # tokens/s prompt eval
    generation_tps: float = 0.0  # tokens/s generation
    ttft_ms: float = 0.0         # time to first token
    tokens_generated: int = 0
    prompt_tokens: int = 0
    stable: bool = True
    error: Optional[str] = None
    notes: str = ""


@dataclass
class QualityResult:
    task_id: str
    task_name: str
    prompt_tokens: int = 0
    response_tokens: int = 0
    generation_tps: float = 0.0
    response: str = ""
    pass_simple: Optional[bool] = None   # whether expected_contains found
    notes: str = ""
    error: Optional[str] = None


def _nvidia_free_mib() -> int:
    d = vram_mib()
    return d.get("free_mib", -1)


def run_speed_benchmark(
    model_path: Path,
    n_ctx: int,
    n_gpu_layers: int,
    label: str,
) -> SpeedResult:
    result = SpeedResult(context_tokens=n_ctx, gpu_layers=n_gpu_layers)

    print(f"\n  [{label}] ctx={n_ctx} gpu_layers={n_gpu_layers}")

    result.vram_before_mib = vram_mib()
    result.ram_before_mib = ram_mib()

    # Load model
    try:
        t0 = time.perf_counter()
        llm = llama_cpp.Llama(
            model_path=str(model_path),
            n_ctx=n_ctx,
            n_gpu_layers=n_gpu_layers,
            n_threads=N_THREADS,
            n_batch=N_BATCH,
            seed=SEED,
            verbose=False,
        )
        result.load_time_s = time.perf_counter() - t0
    except Exception as e:
        result.stable = False
        result.error = str(e)
        print(f"    LOAD FAILED: {e}")
        return result

    wait_settle(0.5)
    result.vram_after_load_mib = vram_mib()
    result.ram_after_load_mib = ram_mib()

    print(f"    Loaded in {result.load_time_s:.1f}s  "
          f"VRAM used={result.vram_after_load_mib.get('used_mib','?')} MiB  "
          f"RAM proc={result.ram_after_load_mib.get('process_rss_mib','?')} MiB")

    # Warmup
    try:
        llm.create_completion(SPEED_PROMPT[:200], max_tokens=16, seed=SEED)
    except Exception:
        pass

    # Prefill + generation benchmark
    # Use a prompt that fills ~half the context with known content
    prompt = SPEED_PROMPT + "\n\n" + PREFILL_FILLER
    max_new = min(256, n_ctx // 4)

    try:
        t_start = time.perf_counter()
        resp = llm.create_completion(
            prompt,
            max_tokens=max_new,
            seed=SEED,
            temperature=0.0,
            echo=False,
        )
        t_end = time.perf_counter()

        timings = resp.get("timings", {})
        if timings:
            result.prefill_tps = timings.get("prompt_per_second", 0.0)
            result.generation_tps = timings.get("predicted_per_second", 0.0)
            result.prompt_tokens = int(timings.get("prompt_n", 0))
            result.tokens_generated = int(timings.get("predicted_n", 0))
            if result.generation_tps > 0:
                result.ttft_ms = (1.0 / result.generation_tps) * 1000
        else:
            # Fallback: estimate from wall time
            usage = resp.get("usage", {})
            pt = usage.get("prompt_tokens", 0)
            ct = usage.get("completion_tokens", 0)
            elapsed = t_end - t_start
            result.prompt_tokens = pt
            result.tokens_generated = ct
            # Rough split: assume prefill ~80% of time, gen ~20%
            result.generation_tps = ct / max(elapsed * 0.2, 0.001)
            result.prefill_tps = pt / max(elapsed * 0.8, 0.001)

        print(f"    prefill={result.prefill_tps:.0f} tok/s  "
              f"gen={result.generation_tps:.1f} tok/s  "
              f"output_tokens={result.tokens_generated}")

    except Exception as e:
        result.stable = False
        result.error = str(e)
        print(f"    GENERATION FAILED: {e}")

    # Sustained load test (10 more completions, track speed drift)
    speeds = []
    for i in range(5):
        try:
            r2 = llm.create_completion(
                SPEED_PROMPT,
                max_tokens=64,
                seed=SEED + i,
                temperature=0.0,
            )
            t2 = r2.get("timings", {})
            if t2:
                speeds.append(t2.get("predicted_per_second", 0.0))
        except Exception:
            result.notes += " [sustained gen failed]"
            break

    if speeds:
        avg_sustained = sum(speeds) / len(speeds)
        drop_pct = ((result.generation_tps - avg_sustained) / max(result.generation_tps, 1)) * 100
        result.notes += f" sustained_avg={avg_sustained:.1f} tok/s drop={drop_pct:.0f}%"
        print(f"    sustained avg={avg_sustained:.1f} tok/s  thermal drop={drop_pct:.0f}%")

    # Check RAM pressure
    ram_now = ram_mib()
    avail = ram_now.get("system_available_mib", 9999)
    if avail < 500:
        result.notes += " [WARNING: RAM pressure <500 MiB]"
        print("    *** WARNING: low system RAM available ***")

    # Cleanup
    del llm
    gc.collect()
    wait_settle(1.0)

    return result


def _chat_format_for(model_path: Path) -> str:
    """Infer llama-cpp-python chat format string from model filename."""
    name = model_path.name.lower()
    if "qwen" in name:
        return "chatml"
    if "llama" in name or "dolphin" in name:
        return "llama-3"
    return "chatml"  # safe default for instruction-tuned models


def run_quality_benchmark(
    model_path: Path,
    n_ctx: int,
    n_gpu_layers: int,
    label: str,
) -> list[QualityResult]:
    print(f"\n  [{label}] QUALITY EVAL ctx={n_ctx} gpu_layers={n_gpu_layers}")
    results = []
    chat_fmt = _chat_format_for(model_path)

    try:
        llm = llama_cpp.Llama(
            model_path=str(model_path),
            n_ctx=n_ctx,
            n_gpu_layers=n_gpu_layers,
            n_threads=N_THREADS,
            n_batch=N_BATCH,
            seed=SEED,
            verbose=False,
            chat_format=chat_fmt,
        )
    except Exception as e:
        print(f"  LOAD FAILED: {e}")
        for qp in QUALITY_PROMPTS:
            results.append(QualityResult(
                task_id=qp["id"],
                task_name=qp["task"],
                error=str(e),
            ))
        return results

    for qp in QUALITY_PROMPTS:
        qr = QualityResult(task_id=qp["id"], task_name=qp["task"])
        try:
            resp = llm.create_chat_completion(
                messages=[
                    {"role": "system", "content": "You are a precise and helpful assistant."},
                    {"role": "user", "content": qp["prompt"]},
                ],
                max_tokens=512,
                seed=SEED,
                temperature=0.0,
            )
            content = resp["choices"][0]["message"]["content"]
            qr.response = content
            # llama_cpp timings dict has slightly different keys depending on version
            timings = resp.get("timings", {})
            if timings:
                qr.prompt_tokens = int(timings.get("prompt_n", timings.get("n_p_eval", 0)))
                qr.response_tokens = int(timings.get("predicted_n", timings.get("n_eval", 0)))
                gen_ms = timings.get("predicted_ms", timings.get("t_eval_ms", 0))
                if gen_ms > 0 and qr.response_tokens > 0:
                    qr.generation_tps = qr.response_tokens / (gen_ms / 1000)
                else:
                    qr.generation_tps = timings.get("predicted_per_second", 0.0)
            usage = resp.get("usage", {})
            if not qr.prompt_tokens:
                qr.prompt_tokens = usage.get("prompt_tokens", 0)
            if not qr.response_tokens:
                qr.response_tokens = usage.get("completion_tokens", 0)

            expected = qp.get("expected_contains")
            if expected is not None:
                qr.pass_simple = expected in content

            print(f"    {qp['id']}: {qr.response_tokens} tok  "
                  f"{qr.generation_tps:.1f} tok/s  "
                  f"pass={qr.pass_simple}")
        except Exception as e:
            qr.error = str(e)
            print(f"    {qp['id']}: ERROR {e}")
        results.append(qr)

    del llm
    gc.collect()
    wait_settle(1.0)

    return results


# -- Main -----------------------------------------------------------------------

def main():
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    out_path = Path(__file__).parent / "results" / f"benchmark_{timestamp}.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)

    print("=" * 70)
    print("B-002 Stage 4: Empirical Benchmark")
    print(f"Started: {time.strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 70)

    # Verify models
    for label, path in MODELS.items():
        if not path.exists():
            print(f"ERROR: model not found: {path}")
            sys.exit(1)
        size_gb = path.stat().st_size / 1e9
        print(f"  {label}: {path.name} ({size_gb:.2f} GB)")

    baseline_vram = vram_mib()
    baseline_ram = ram_mib()
    print(f"\nBaseline VRAM: {baseline_vram}")
    print(f"Baseline RAM:  {baseline_ram}")

    all_results: dict = {
        "timestamp": timestamp,
        "baseline_vram": baseline_vram,
        "baseline_ram": baseline_ram,
        "llama_cpp_version": llama_cpp.__version__,
        "gpu_offload_supported": llama_cpp.llama_supports_gpu_offload(),
        "speed": {},
        "quality": {},
    }

    # -- Candidate A: 3B — full offload, all context sizes -------------------
    print("\n" + "-" * 70)
    print("CANDIDATE A: Qwen2.5-3B-Instruct Q5_K_M")
    print("-" * 70)

    all_results["speed"]["3B"] = {}
    for ctx in CONTEXT_SIZES:
        key = f"ctx{ctx}_gpu99"
        r = run_speed_benchmark(MODELS["3B_Q5KM"], ctx, 99, "3B")
        all_results["speed"]["3B"][key] = asdict(r)
        # Save incrementally
        out_path.write_text(json.dumps(all_results, indent=2))

    # Quality eval for 3B at 4096
    print("\n  3B Quality Evaluation at ctx=4096")
    qrs_3b = run_quality_benchmark(MODELS["3B_Q5KM"], 4096, 99, "3B")
    all_results["quality"]["3B"] = [asdict(r) for r in qrs_3b]
    out_path.write_text(json.dumps(all_results, indent=2))

    # -- Candidate B: 8B — GPU layer sweep -----------------------------------
    print("\n" + "-" * 70)
    print("CANDIDATE B: Dolphin3.0-Llama3.1-8B Q5_K_S")
    print("-" * 70)

    cuda_available = llama_cpp.llama_supports_gpu_offload()
    if not cuda_available:
        print("  NOTE: CUDA not available. Partial-offload sweep skipped. CPU baseline only.")

    all_results["cuda_available"] = cuda_available
    all_results["speed"]["8B"] = {}
    best_8b_layers = 0
    best_8b_speed = 0.0

    # CPU-only: skip layer sweep (all configs produce same CPU result). GPU: full sweep.
    layers_to_test = GPU_LAYERS_8B if cuda_available else [0]
    for gpu_layers in layers_to_test:
        # Use 2048 context for layer sweep (memory-conservative)
        key = f"ctx2048_gpu{gpu_layers}"
        r = run_speed_benchmark(MODELS["8B_Q5KS"], 2048, gpu_layers, "8B")  # type: ignore[assignment]
        all_results["speed"]["8B"][key] = asdict(r)
        out_path.write_text(json.dumps(all_results, indent=2))
        if r.stable and r.generation_tps > best_8b_speed:
            best_8b_speed = r.generation_tps
            best_8b_layers = gpu_layers

    print(f"\n  Best 8B config: gpu_layers={best_8b_layers} → {best_8b_speed:.1f} tok/s")

    # Test best 8B config at multiple context sizes
    for ctx in CONTEXT_SIZES:
        key = f"ctx{ctx}_gpu{best_8b_layers}"
        if f"ctx2048_gpu{best_8b_layers}" in all_results["speed"]["8B"] and ctx == 2048:
            continue  # already have 2048 result
        r = run_speed_benchmark(MODELS["8B_Q5KS"], ctx, best_8b_layers, "8B_bestlayer")
        all_results["speed"]["8B"][key] = asdict(r)
        out_path.write_text(json.dumps(all_results, indent=2))

    # Quality eval for 8B at best config, 4096 context
    print(f"\n  8B Quality Evaluation at ctx=4096 gpu_layers={best_8b_layers}")
    qrs_8b = run_quality_benchmark(MODELS["8B_Q5KS"], 4096, best_8b_layers, "8B")
    all_results["quality"]["8B"] = [asdict(r) for r in qrs_8b]
    out_path.write_text(json.dumps(all_results, indent=2))

    # -- Summary --------------------------------------------------------------
    print("\n" + "=" * 70)
    print("SUMMARY")
    print("=" * 70)

    def _speed_row(label, results_dict, key):
        r = results_dict.get(key, {})
        if not r:
            return
        if r.get("error"):
            print(f"  {label:40s}  FAILED: {r['error'][:60]}")
            return
        gen = r.get("generation_tps", 0)
        pre = r.get("prefill_tps", 0)
        vram = r.get("vram_after_load_mib", {}).get("used_mib", "?")
        ram_proc = r.get("ram_after_load_mib", {}).get("process_rss_mib", "?")
        print(f"  {label:40s}  gen={gen:5.1f} tok/s  pre={pre:6.0f} tok/s  "
              f"VRAM={vram} MiB  procRAM={ram_proc} MiB")

    print("\nSpeed results (generation tok/s):")
    for ctx in CONTEXT_SIZES:
        _speed_row(f"3B Q5KM ctx={ctx} full-GPU", all_results["speed"]["3B"], f"ctx{ctx}_gpu99")
    for gl in GPU_LAYERS_8B:
        _speed_row(f"8B Q5KS ctx=2048 gpu_layers={gl}", all_results["speed"]["8B"], f"ctx2048_gpu{gl}")

    print("\nQuality pass rates (simple string match):")
    for model_key, qrs in all_results["quality"].items():
        checkable = [q for q in qrs if q.get("pass_simple") is not None]
        passed = sum(1 for q in checkable if q["pass_simple"])
        print(f"  {model_key}: {passed}/{len(checkable)} passed  "
              f"(of {len(qrs)} tasks, {len(qrs)-len(checkable)} qualitative)")

    print(f"\nFull results: {out_path}")
    print("=" * 70)

    all_results["best_8b_gpu_layers"] = best_8b_layers
    all_results["best_8b_gen_tps"] = best_8b_speed
    out_path.write_text(json.dumps(all_results, indent=2))
    print("Done.")


if __name__ == "__main__":
    main()
