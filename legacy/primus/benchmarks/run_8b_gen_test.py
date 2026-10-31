# -*- coding: utf-8 -*-
"""
Targeted 8B generation speed test using Dolphin instruction format.
Tests gpu_layers=18 (optimal from sweep) at 2048, 4096, 8192 context.
Also measures generation speed and quality.
"""
from __future__ import annotations
import gc, json, os, ctypes, subprocess, sys, time
from pathlib import Path

sys.stdout.reconfigure(line_buffering=True)
sys.stderr.reconfigure(line_buffering=True)

LLAMA_LIB = (
    r"C:\Users\ASUS\AppData\Local\Programs\Python\Python312"
    r"\Lib\site-packages\llama_cpp\lib"
)

os.add_dll_directory(LLAMA_LIB)
for dll in ["ggml-base.dll", "ggml-cpu.dll", "ggml.dll", "ggml-cuda.dll"]:
    try:
        ctypes.CDLL(os.path.join(LLAMA_LIB, dll))
    except OSError:
        pass

import psutil  # noqa: E402
import llama_cpp  # noqa: E402

MODEL_8B = Path(r"C:\Users\ASUS\Downloads\Dolphin3.0-Llama3.1-8B-Q5_K_S.gguf")
MODEL_3B = Path(r"C:\Users\ASUS\Documents\Astra\benchmarks\models\qwen2.5-3b-instruct-q5_k_m.gguf")
SEED = 42
N_THREADS = 12
N_BATCH = 512
OUT = Path(__file__).parent / "results" / "8b_gen_test.json"
OUT.parent.mkdir(parents=True, exist_ok=True)

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
        "expected_contains": None,
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
            "Do not include primes outside the range 10-50."
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
            "You have access to a function called search_web with signature:\n"
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

# Dolphin uses ChatML format (like Qwen)
DOLPHIN_CHAT_FMT = "chatml"

SPEED_SYSTEM = "You are a knowledgeable technical assistant."
SPEED_USER = (
    "Please write a thorough and detailed explanation of how Python's garbage "
    "collector works. Cover reference counting, cyclic garbage collection, "
    "the three generations (gen 0, 1, 2), their thresholds, gc module interface, "
    "and include a worked example demonstrating a reference cycle being detected "
    "and collected. Please be comprehensive."
)


def vram_mib():
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


def ram_mib():
    vm = psutil.virtual_memory()
    proc = psutil.Process(os.getpid())
    return {
        "system_total_mib": vm.total // 1048576,
        "system_available_mib": vm.available // 1048576,
        "system_used_pct": vm.percent,
        "process_rss_mib": proc.memory_info().rss // 1048576,
    }


def test_generation(model_path, n_ctx, n_gpu_layers, label, chat_format):
    print(f"\n  [{label}] ctx={n_ctx} gpu_layers={n_gpu_layers}")
    vram_before = vram_mib()
    ram_before = ram_mib()

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
            chat_format=chat_format,
        )
        load_time = time.perf_counter() - t0
    except Exception as e:
        print(f"    LOAD FAILED: {e}")
        return {
            "error": str(e),
            "n_ctx": n_ctx,
            "n_gpu_layers": n_gpu_layers,
        }

    vram_after = vram_mib()
    ram_after = ram_mib()
    print(f"    Loaded in {load_time:.1f}s  "
          f"VRAM={vram_after.get('used_mib','?')} MiB  "
          f"RAM proc={ram_after.get('process_rss_mib','?')} MiB")

    results = {
        "n_ctx": n_ctx, "n_gpu_layers": n_gpu_layers,
        "load_time_s": round(load_time, 2),
        "vram_before_mib": vram_before,
        "vram_after_load_mib": vram_after,
        "ram_before_mib": ram_before,
        "ram_after_load_mib": ram_after,
        "generation_runs": [],
        "quality": [],
    }

    # Warmup
    try:
        llm.create_chat_completion(
            messages=[
                {"role": "system", "content": "You are helpful."},
                {"role": "user", "content": "Say hello."},
            ],
            max_tokens=8, seed=SEED, temperature=0.0,
        )
    except Exception:
        pass

    # Speed test — 3 runs
    for run_i in range(3):
        try:
            t0 = time.perf_counter()
            resp = llm.create_chat_completion(
                messages=[
                    {"role": "system", "content": SPEED_SYSTEM},
                    {"role": "user", "content": SPEED_USER},
                ],
                max_tokens=256,
                seed=SEED + run_i,
                temperature=0.0,
            )
            wall = time.perf_counter() - t0
            content = resp["choices"][0]["message"]["content"]
            usage = resp.get("usage", {})
            timings = resp.get("timings", {})

            pt = int(timings.get("prompt_n", timings.get("n_p_eval", usage.get("prompt_tokens", 0))))
            ct = int(timings.get("predicted_n", timings.get("n_eval", usage.get("completion_tokens", 0))))
            gen_ms = timings.get("predicted_ms", timings.get("t_eval_ms", 0))
            pre_ms = timings.get("prompt_ms", timings.get("t_p_eval_ms", 0))

            gen_tps = ct / (gen_ms / 1000) if gen_ms > 0 and ct > 0 else ct / max(wall, 0.001)
            pre_tps = pt / (pre_ms / 1000) if pre_ms > 0 and pt > 0 else 0

            # Also compute from wall time as sanity check
            wall_gen_tps = ct / wall if ct > 0 else 0

            entry = {
                "run": run_i,
                "prompt_tokens": pt,
                "completion_tokens": ct,
                "wall_s": round(wall, 2),
                "gen_tps_timings": round(gen_tps, 1),
                "gen_tps_wall": round(wall_gen_tps, 1),
                "pre_tps": round(pre_tps, 1),
                "timings_raw": timings,
                "response_preview": content[:200].replace("\n", " "),
            }
            results["generation_runs"].append(entry)
            print(f"    run {run_i}: {ct} tokens  gen={gen_tps:.1f} tok/s  "
                  f"wall={wall_gen_tps:.1f} tok/s  prefill={pre_tps:.1f} tok/s")
            if ct == 0:
                print(f"    WARNING: 0 completion tokens! Response: {content[:100]!r}")

        except Exception as e:
            print(f"    run {run_i} FAILED: {e}")
            results["generation_runs"].append({"run": run_i, "error": str(e)})

    # Quality eval
    print(f"    Quality eval ({len(QUALITY_PROMPTS)} tasks)...")
    for qp in QUALITY_PROMPTS:
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
            usage = resp.get("usage", {})
            timings = resp.get("timings", {})
            ct = int(timings.get("predicted_n", timings.get("n_eval", usage.get("completion_tokens", 0))))
            gen_ms = timings.get("predicted_ms", timings.get("t_eval_ms", 0))
            gen_tps = ct / (gen_ms / 1000) if gen_ms > 0 and ct > 0 else 0

            expected = qp.get("expected_contains")
            passed = (expected in content) if expected else None

            qr = {
                "task_id": qp["id"],
                "task_name": qp["task"],
                "completion_tokens": ct,
                "gen_tps": round(gen_tps, 1),
                "pass_simple": passed,
                "response": content,
                "response_preview": content[:200].replace("\n", " "),
            }
            results["quality"].append(qr)
            print(f"      {qp['id']}: {ct} tok  gen={gen_tps:.1f} tok/s  pass={passed}")
        except Exception as e:
            results["quality"].append({
                "task_id": qp["id"],
                "error": str(e)
            })
            print(f"      {qp['id']}: ERROR {e}")

    del llm
    gc.collect()
    time.sleep(1.0)
    return results


def main():
    print("=" * 70)
    print("8B Generation Speed + Quality Test")
    print(f"Model: {MODEL_8B.name}")
    print(f"GPU: {llama_cpp.llama_supports_gpu_offload()}")
    print("=" * 70)

    baseline_vram = vram_mib()
    baseline_ram = ram_mib()
    print(f"Baseline VRAM: {baseline_vram}")
    print(f"Baseline RAM:  {baseline_ram}")

    all_results = {
        "model": MODEL_8B.name,
        "model_size_gb": round(MODEL_8B.stat().st_size / 1e9, 2),
        "baseline_vram": baseline_vram,
        "baseline_ram": baseline_ram,
        "gpu_offload": llama_cpp.llama_supports_gpu_offload(),
        "tests": [],
    }

    # Test 18 GPU layers (optimal from sweep) at 2048, 4096, 8192
    for ctx in [2048, 4096, 8192]:
        print(f"\n--- 8B ctx={ctx} gpu_layers=18 ---")
        r = test_generation(MODEL_8B, ctx, 18, "8B", DOLPHIN_CHAT_FMT)
        all_results["tests"].append({"ctx": ctx, "gpu_layers": 18, **r})
        OUT.write_text(json.dumps(all_results, indent=2))

    # Also test 0 GPU layers for comparison baseline
    print(f"\n--- 8B ctx=2048 gpu_layers=0 (CPU baseline) ---")
    r = test_generation(MODEL_8B, 2048, 0, "8B_cpu", DOLPHIN_CHAT_FMT)
    all_results["tests"].append({"ctx": 2048, "gpu_layers": 0, **r})
    OUT.write_text(json.dumps(all_results, indent=2))

    print("\n" + "=" * 70)
    print("RESULTS SUMMARY")
    print("=" * 70)
    for t in all_results["tests"]:
        runs = t.get("generation_runs", [])
        if runs:
            valid = [r for r in runs if "gen_tps_wall" in r and r.get("completion_tokens", 0) > 0]
            if valid:
                avg_gen = sum(r["gen_tps_wall"] for r in valid) / len(valid)
                avg_pre = sum(r.get("pre_tps", 0) for r in valid) / len(valid)
            else:
                avg_gen = 0
                avg_pre = 0
        else:
            avg_gen = 0
            avg_pre = 0
        vram = t.get("vram_after_load_mib", {}).get("used_mib", "?")
        ram_p = t.get("ram_after_load_mib", {}).get("process_rss_mib", "?")
        q = t.get("quality", [])
        checkable = [x for x in q if x.get("pass_simple") is not None]
        passed = sum(1 for x in checkable if x["pass_simple"])
        print(f"  ctx={t['ctx']} gpu={t['gpu_layers']}: "
              f"VRAM={vram} MiB  RAM={ram_p} MiB  "
              f"gen={avg_gen:.1f} tok/s  pre={avg_pre:.1f} tok/s  "
              f"quality={passed}/{len(checkable)}")

    print(f"\nFull results: {OUT}")


if __name__ == "__main__":
    main()
