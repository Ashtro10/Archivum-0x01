"""
B-002 Stage 4 — Performance Benchmark
Measures VRAM, RAM, speed, and stability for model candidates.
Must be run with CUDA DLLs in PATH (see run_benchmark.ps1).
"""

import os
import sys
import time
import json
import ctypes
import subprocess
import gc
from datetime import datetime
from pathlib import Path

# Force unbuffered output
sys.stdout.reconfigure(line_buffering=True)

# ---------------------------------------------------------------------------
# Model paths
# ---------------------------------------------------------------------------
MODEL_3B = r"C:\Users\ASUS\Documents\Astra\benchmarks\models\qwen2.5-3b-instruct-q5_k_m.gguf"
MODEL_8B = r"C:\Users\ASUS\Downloads\Dolphin3.0-Llama3.1-8B-Q5_K_S.gguf"
RESULTS_DIR = Path(__file__).parent / "results"

# ---------------------------------------------------------------------------
# System measurement helpers
# ---------------------------------------------------------------------------

def get_vram_mib():
    """Query nvidia-smi for current VRAM free/used."""
    try:
        out = subprocess.check_output(
            ["nvidia-smi", "--query-gpu=memory.total,memory.free,memory.used",
             "--format=csv,noheader,nounits"],
            timeout=5
        ).decode().strip()
        total, free, used = [int(x.strip()) for x in out.split(",")]
        return {"total_mib": total, "free_mib": free, "used_mib": used}
    except Exception as e:
        return {"error": str(e)}


def get_ram_mib():
    """Query Windows GlobalMemoryStatusEx for RAM."""
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
    m = MEMSTATUS()
    m.dwLength = ctypes.sizeof(MEMSTATUS)
    ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
    total = m.ullTotalPhys // (1024 * 1024)
    avail = m.ullAvailPhys // (1024 * 1024)
    used = total - avail
    return {"total_mib": total, "available_mib": avail, "used_mib": used, "load_pct": m.dwMemoryLoad}


def import_llama_cpp():
    import llama_cpp
    return llama_cpp


# ---------------------------------------------------------------------------
# Single generation run
# ---------------------------------------------------------------------------

def measure_generation(llama_cpp, model_path, n_gpu_layers, context_size,
                        prompt, max_tokens=200, seed=42, label=""):
    """
    Load model, run generation, return metrics dict.
    Unloads model before returning.
    """
    result = {
        "label": label,
        "model": Path(model_path).name,
        "n_gpu_layers": n_gpu_layers,
        "context_size": context_size,
        "prompt_tokens": None,
        "generated_tokens": None,
        "load_time_s": None,
        "ttft_s": None,
        "prefill_tps": None,
        "gen_tps": None,
        "total_time_s": None,
        "vram_baseline": None,
        "vram_after_load": None,
        "vram_peak": None,
        "ram_before": None,
        "ram_after_load": None,
        "error": None,
    }

    result["vram_baseline"] = get_vram_mib()
    result["ram_before"] = get_ram_mib()

    print(f"\n{'='*60}")
    print(f"  {label}")
    print(f"  model      : {Path(model_path).name}")
    print(f"  gpu_layers : {n_gpu_layers}")
    print(f"  ctx        : {context_size}")
    print(f"  VRAM free  : {result['vram_baseline'].get('free_mib','?')} MiB")
    print(f"  RAM used   : {result['ram_before'].get('used_mib','?')} MiB")
    print(f"{'='*60}")

    try:
        t0 = time.perf_counter()
        model = llama_cpp.Llama(
            model_path=model_path,
            n_gpu_layers=n_gpu_layers,
            n_ctx=context_size,
            n_batch=512,
            seed=seed,
            verbose=False,
        )
        load_time = time.perf_counter() - t0
        result["load_time_s"] = round(load_time, 3)

        result["vram_after_load"] = get_vram_mib()
        result["ram_after_load"] = get_ram_mib()

        print(f"  loaded in {load_time:.2f}s")
        print(f"  VRAM after load: {result['vram_after_load'].get('used_mib','?')} MiB used")
        print(f"  RAM after load : {result['ram_after_load'].get('used_mib','?')} MiB used")

        # Tokenise prompt to get prompt token count
        tokens = model.tokenize(prompt.encode())
        n_prompt = len(tokens)
        result["prompt_tokens"] = n_prompt
        print(f"  prompt tokens  : {n_prompt}")

        # Generation with timing
        generated = []
        first_token_time = None
        gen_start = time.perf_counter()

        for chunk in model.create_completion(
            prompt,
            max_tokens=max_tokens,
            seed=seed,
            temperature=0.0,
            stream=True,
        ):
            tok = chunk["choices"][0]["text"]
            if tok:
                if first_token_time is None:
                    first_token_time = time.perf_counter()
                generated.append(tok)

        gen_end = time.perf_counter()
        result["vram_peak"] = get_vram_mib()

        n_gen = len(generated)
        result["generated_tokens"] = n_gen

        ttft = (first_token_time - gen_start) if first_token_time else None
        total_gen = gen_end - gen_start
        prefill_approx = ttft if ttft else total_gen

        result["ttft_s"] = round(ttft, 3) if ttft else None
        result["total_time_s"] = round(total_gen, 3)

        if ttft and n_prompt > 0:
            result["prefill_tps"] = round(n_prompt / ttft, 1)
        if n_gen > 1 and ttft:
            gen_time = total_gen - ttft
            result["gen_tps"] = round((n_gen - 1) / gen_time, 2) if gen_time > 0 else None

        output_text = "".join(generated)
        result["output_sample"] = output_text[:300]

        print(f"  TTFT           : {result['ttft_s']}s")
        print(f"  prefill        : {result['prefill_tps']} tok/s")
        print(f"  generation     : {result['gen_tps']} tok/s")
        print(f"  generated      : {n_gen} tokens in {total_gen:.2f}s")
        print(f"  output[:100]   : {output_text[:100].strip()!r}")

        del model
        gc.collect()

    except Exception as e:
        result["error"] = str(e)
        print(f"  ERROR: {e}")
        try:
            del model
            gc.collect()
        except Exception:
            pass

    return result


# ---------------------------------------------------------------------------
# Sustained generation test
# ---------------------------------------------------------------------------

def sustained_generation_test(llama_cpp, model_path, n_gpu_layers, context_size,
                               prompt, n_passes=3, max_tokens_per_pass=150, seed=42):
    """Run multiple passes to detect thermal throttling."""
    print(f"\n  [Sustained test: {n_passes} passes × {max_tokens_per_pass} tokens]")
    speeds = []
    try:
        model = llama_cpp.Llama(
            model_path=model_path,
            n_gpu_layers=n_gpu_layers,
            n_ctx=context_size,
            n_batch=512,
            seed=seed,
            verbose=False,
        )
        for i in range(n_passes):
            t0 = time.perf_counter()
            count = 0
            for chunk in model.create_completion(
                prompt,
                max_tokens=max_tokens_per_pass,
                seed=seed + i,
                temperature=0.0,
                stream=True,
            ):
                if chunk["choices"][0]["text"]:
                    count += 1
            elapsed = time.perf_counter() - t0
            tps = round(count / elapsed, 2) if elapsed > 0 else 0
            speeds.append(tps)
            print(f"    pass {i+1}: {tps} tok/s  ({count} tokens, {elapsed:.2f}s)")
        del model
        gc.collect()
    except Exception as e:
        print(f"    ERROR: {e}")
        return {"passes": [], "error": str(e)}

    return {
        "passes": speeds,
        "mean_tps": round(sum(speeds) / len(speeds), 2) if speeds else None,
        "min_tps": min(speeds) if speeds else None,
        "max_tps": max(speeds) if speeds else None,
        "throttle_ratio": round(min(speeds) / speeds[0], 3) if speeds and speeds[0] > 0 else None,
    }


# ---------------------------------------------------------------------------
# Main benchmark
# ---------------------------------------------------------------------------

def main():
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    results = {
        "timestamp": timestamp,
        "hardware": {
            "vram_baseline": get_vram_mib(),
            "ram_baseline": get_ram_mib(),
        },
        "candidate_a_3b": [],
        "candidate_b_8b": [],
        "sustained_3b": None,
        "sustained_8b_best": None,
    }

    print("\n" + "="*60)
    print("B-002 Stage 4 Performance Benchmark")
    print(f"Timestamp: {timestamp}")
    print("="*60)
    print(f"VRAM baseline: {results['hardware']['vram_baseline']}")
    print(f"RAM  baseline: {results['hardware']['ram_baseline']}")

    llama_cpp = import_llama_cpp()

    PROMPT_SHORT = (
        "You are Astra, an AI assistant. Explain in detail how a CPU cache works, "
        "including L1, L2, and L3 caches, cache lines, and cache misses."
    )

    # ------------------------------------------------------------------
    # Candidate A: Qwen2.5-3B Q5_K_M — full GPU offload, 3 contexts
    # ------------------------------------------------------------------
    print("\n\n### CANDIDATE A: Qwen2.5-3B-Instruct Q5_K_M ###")

    for ctx in [2048, 4096, 8192]:
        r = measure_generation(
            llama_cpp,
            MODEL_3B,
            n_gpu_layers=99,   # all layers
            context_size=ctx,
            prompt=PROMPT_SHORT,
            max_tokens=200,
            seed=42,
            label=f"3B-Q5_K_M ctx={ctx}",
        )
        results["candidate_a_3b"].append(r)

    # Sustained test at 4096 (production context)
    print("\n  [3B sustained stability test @ ctx=4096]")
    results["sustained_3b"] = sustained_generation_test(
        llama_cpp, MODEL_3B, n_gpu_layers=99, context_size=4096,
        prompt=PROMPT_SHORT, n_passes=3, max_tokens_per_pass=150,
    )

    # ------------------------------------------------------------------
    # Candidate B: Dolphin3-Llama3.1-8B Q5_K_S — GPU layer sweep
    # ------------------------------------------------------------------
    print("\n\n### CANDIDATE B: Dolphin3.0-Llama3.1-8B Q5_K_S ###")
    print("  Llama 3.1 8B architecture: 32 transformer layers")
    print("  Weight size: ~5340 MB (Q5_K_S)")
    print("  KV per token @fp16: ~128 KiB (32 layers × 8 heads × 128 dim × 2)")

    # GPU layer sweep with ctx=2048 (lower to give headroom for weight offload)
    # 32 layers total; try 0 (CPU only), 10, 17 (estimated max), 20, 25, 32 (all)
    gpu_layer_configs = [
        (0,  2048, "8B cpu-only ctx=2048"),
        (10, 2048, "8B 10gpu ctx=2048"),
        (17, 2048, "8B 17gpu ctx=2048 (est max stable)"),
        (20, 2048, "8B 20gpu ctx=2048"),
        (25, 2048, "8B 25gpu ctx=2048"),
        (32, 2048, "8B all-gpu ctx=2048 (OOM expected)"),
        (17, 4096, "8B 17gpu ctx=4096"),
    ]

    best_8b_layers = None
    best_8b_tps = 0.0

    for gpu_layers, ctx, lbl in gpu_layer_configs:
        r = measure_generation(
            llama_cpp,
            MODEL_8B,
            n_gpu_layers=gpu_layers,
            context_size=ctx,
            prompt=PROMPT_SHORT,
            max_tokens=150,
            seed=42,
            label=lbl,
        )
        results["candidate_b_8b"].append(r)
        tps = r.get("gen_tps") or 0.0
        if r.get("error") is None and tps > best_8b_tps:
            best_8b_tps = tps
            best_8b_layers = gpu_layers

    # Sustained test at best GPU layer config
    if best_8b_layers is not None:
        print(f"\n  [8B sustained test at best config: {best_8b_layers} GPU layers]")
        results["sustained_8b_best"] = sustained_generation_test(
            llama_cpp, MODEL_8B, n_gpu_layers=best_8b_layers,
            context_size=2048, prompt=PROMPT_SHORT,
            n_passes=3, max_tokens_per_pass=100,
        )
        results["best_8b_gpu_layers"] = best_8b_layers

    # ------------------------------------------------------------------
    # Save
    # ------------------------------------------------------------------
    out_path = RESULTS_DIR / f"perf_{timestamp}.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, default=str)
    print(f"\n\nResults saved: {out_path}")

    # Quick summary table
    print("\n" + "="*60)
    print("PERFORMANCE SUMMARY")
    print("="*60)
    print(f"{'Label':<35} {'TTFT':>6} {'Prefill':>9} {'GenTPS':>8} {'VRAMused':>10} {'Error'}")
    print("-"*85)
    for r in results["candidate_a_3b"] + results["candidate_b_8b"]:
        vram_used = r.get("vram_after_load", {}).get("used_mib", "?") if isinstance(r.get("vram_after_load"), dict) else "?"
        err = "OOM/ERR" if r.get("error") else ""
        print(f"{r['label']:<35} {str(r.get('ttft_s','?')):>6} {str(r.get('prefill_tps','?')):>9} "
              f"{str(r.get('gen_tps','?')):>8} {str(vram_used):>10} {err}")

    return str(out_path)


if __name__ == "__main__":
    main()
