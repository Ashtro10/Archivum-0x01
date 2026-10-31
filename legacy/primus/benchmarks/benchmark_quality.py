"""
B-002 Stage 4 — Quality Benchmark
Fixed-seed Astra-oriented evaluation across 12 task categories.
Must be run with CUDA DLLs in PATH (see run_benchmark.ps1).
"""

import os
import sys
import time
import json
import gc
from datetime import datetime
from pathlib import Path

sys.stdout.reconfigure(line_buffering=True)

MODEL_3B = r"C:\Users\ASUS\Documents\Astra\benchmarks\models\qwen2.5-3b-instruct-q5_k_m.gguf"
MODEL_8B = r"C:\Users\ASUS\Downloads\Dolphin3.0-Llama3.1-8B-Q5_K_S.gguf"
RESULTS_DIR = Path(__file__).parent / "results"

# ---------------------------------------------------------------------------
# Evaluation set — 12 categories, fixed prompts
# Each entry: (category, prompt, expected_keywords_for_scoring)
# ---------------------------------------------------------------------------
EVAL_SET = [
    (
        "01_basic_reasoning",
        (
            "A farmer has 17 sheep. All but 9 run away. How many sheep does the farmer have left? "
            "Explain your reasoning step by step."
        ),
        ["9", "nine"],
    ),
    (
        "02_multi_step_reasoning",
        (
            "Alice is twice as old as Bob. In 5 years, Alice will be 1.5 times Bob's age. "
            "What are their current ages? Show all steps."
        ),
        ["10", "20", "alice", "bob"],
    ),
    (
        "03_c_programming",
        (
            "Write a C function that reverses a null-terminated string in place, without allocating any heap memory. "
            "Include a main() that tests it with at least two cases."
        ),
        ["char", "while", "swap", "\\0", "strlen"],
    ),
    (
        "04_python_programming",
        (
            "Write a Python function that returns the nth Fibonacci number using memoization with a decorator. "
            "Do not use functools.lru_cache. Implement the decorator yourself."
        ),
        ["def", "cache", "wrapper", "fibonacci", "memo"],
    ),
    (
        "05_debugging",
        (
            "The following Python code is supposed to find the maximum element in a list, "
            "but it has a bug. Find the bug and fix it:\n\n"
            "def find_max(lst):\n"
            "    max_val = 0\n"
            "    for x in lst:\n"
            "        if x > max_val:\n"
            "            max_val = x\n"
            "    return max_val\n\n"
            "find_max([-5, -3, -1, -8])  # should return -1"
        ),
        ["negative", "float('inf')", "float(\"-inf\")", "max_val = lst[0]", "None"],
    ),
    (
        "06_architecture_design",
        (
            "Design a simple key-value cache with a maximum capacity and LRU eviction policy. "
            "Describe the data structures you would use and why. "
            "Then write a Python class implementing it. Include get() and put() methods."
        ),
        ["OrderedDict", "dict", "capacity", "evict", "lru", "doubly"],
    ),
    (
        "07_complex_instructions",
        (
            "You will receive a JSON object. Extract all string values (not keys) that are longer than 5 characters, "
            "sort them alphabetically, deduplicate them, and return them as a JSON array. "
            "Do not include any explanation. Return only the JSON array.\n\n"
            '{"name": "Alexander", "city": "Berlin", "tag": "cat", "role": "engineer", "note": "ok", "id": "abc"}'
        ),
        ["Alexander", "Berlin", "engineer"],
    ),
    (
        "08_planning",
        (
            "You are planning a software release. List a concrete, ordered sequence of steps to safely deploy "
            "a new version of a web API that has breaking changes, ensuring zero downtime and the ability to roll back. "
            "Be specific about what to do at each step."
        ),
        ["backup", "rollback", "test", "deploy", "monitor", "version"],
    ),
    (
        "09_summarization",
        (
            "Summarize the following passage in exactly 3 bullet points, each starting with a dash. "
            "Be concise.\n\n"
            "The Linux kernel is a monolithic kernel used in Linux distributions. "
            "It manages CPU scheduling, memory management, file systems, device drivers, and network stacks. "
            "It was first released by Linus Torvalds in 1991. "
            "The kernel communicates with hardware through device drivers, which can be compiled into the kernel "
            "or loaded as modules at runtime. "
            "The kernel provides a system call interface that allows user-space programs to request services. "
            "Security features include namespaces, cgroups, SELinux, and seccomp."
        ),
        ["- ", "Linux", "kernel", "Torvalds"],
    ),
    (
        "10_tool_function_call",
        (
            "You have access to the following tools:\n"
            "  search_web(query: str) -> list[str]\n"
            "  read_file(path: str) -> str\n"
            "  write_file(path: str, content: str) -> None\n\n"
            "A user asks: 'Summarize the contents of /home/user/notes.txt and save the summary to /home/user/summary.txt'\n\n"
            "Respond with a JSON array of tool calls in order, each with 'tool' and 'args' keys. "
            "Return only JSON, no explanation."
        ),
        ["read_file", "write_file", "notes.txt", "summary.txt"],
    ),
    (
        "11_long_context_retrieval",
        (
            "The following is a system log. Answer the question at the end based only on the log.\n\n"
            "2026-08-14 09:01:03 INFO  [auth] user=alice login success\n"
            "2026-08-14 09:01:45 INFO  [api]  user=alice GET /api/v1/items status=200\n"
            "2026-08-14 09:02:12 WARN  [auth] user=bob login failed (bad password)\n"
            "2026-08-14 09:02:13 WARN  [auth] user=bob login failed (bad password)\n"
            "2026-08-14 09:02:14 ERROR [auth] user=bob account locked after 2 failed attempts\n"
            "2026-08-14 09:03:00 INFO  [api]  user=alice POST /api/v1/items status=201\n"
            "2026-08-14 09:03:55 INFO  [auth] user=charlie login success\n"
            "2026-08-14 09:04:01 INFO  [api]  user=charlie GET /api/v1/items status=200\n"
            "2026-08-14 09:05:22 ERROR [api]  user=alice DELETE /api/v1/items/99 status=404\n"
            "2026-08-14 09:06:00 INFO  [auth] user=alice logout\n\n"
            "Question: Which user's account was locked, and after how many failed login attempts?"
        ),
        ["bob", "2", "two", "locked"],
    ),
    (
        "12_error_detection_self_correction",
        (
            "Here is a Python function with a subtle error. Identify the exact error, "
            "explain why it is wrong, and provide the corrected version.\n\n"
            "def binary_search(arr, target):\n"
            "    left, right = 0, len(arr)\n"
            "    while left < right:\n"
            "        mid = (left + right) // 2\n"
            "        if arr[mid] == target:\n"
            "            return mid\n"
            "        elif arr[mid] < target:\n"
            "            left = mid\n"
            "        else:\n"
            "            right = mid\n"
            "    return -1"
        ),
        ["left = mid + 1", "right = mid - 1", "infinite loop", "len(arr) - 1"],
    ),
]


# ---------------------------------------------------------------------------
# Scoring helper
# ---------------------------------------------------------------------------

def score_response(output: str, keywords: list[str]) -> dict:
    """Simple keyword hit scoring. Not a substitute for human review."""
    lower = output.lower()
    hits = [kw for kw in keywords if kw.lower() in lower]
    return {
        "hits": hits,
        "score": round(len(hits) / len(keywords), 2) if keywords else 0.0,
        "max": len(keywords),
    }


# ---------------------------------------------------------------------------
# Run evaluation for one model
# ---------------------------------------------------------------------------

def run_eval(llama_cpp, model_path, n_gpu_layers, ctx, label):
    print(f"\n{'='*60}")
    print(f"QUALITY EVAL: {label}")
    print(f"  model      : {Path(model_path).name}")
    print(f"  gpu_layers : {n_gpu_layers} | ctx: {ctx}")
    print(f"{'='*60}")

    try:
        model = llama_cpp.Llama(
            model_path=model_path,
            n_gpu_layers=n_gpu_layers,
            n_ctx=ctx,
            n_batch=512,
            seed=42,
            verbose=False,
        )
    except Exception as e:
        print(f"  ERROR loading: {e}")
        return {"label": label, "error": str(e), "items": []}

    items = []
    total_score = 0.0

    for cat, prompt, kws in EVAL_SET:
        t0 = time.perf_counter()
        try:
            resp = model.create_completion(
                prompt,
                max_tokens=512,
                seed=42,
                temperature=0.0,
                stream=False,
            )
            output = resp["choices"][0]["text"].strip()
            elapsed = time.perf_counter() - t0
            n_tok = resp["usage"]["completion_tokens"] if "usage" in resp else None
            tps = round(n_tok / elapsed, 1) if n_tok else None
            sc = score_response(output, kws)

            items.append({
                "category": cat,
                "score": sc["score"],
                "hits": sc["hits"],
                "max_keywords": sc["max"],
                "elapsed_s": round(elapsed, 2),
                "gen_tps": tps,
                "output_chars": len(output),
                "output_sample": output[:400],
            })
            total_score += sc["score"]
            status = "PASS" if sc["score"] >= 0.5 else "WEAK" if sc["score"] > 0 else "FAIL"
            print(f"  [{status}] {cat}: score={sc['score']} hits={sc['hits']} | {elapsed:.1f}s @ {tps} tok/s")

        except Exception as e:
            items.append({"category": cat, "error": str(e)})
            print(f"  [ERR ] {cat}: {e}")

    mean_score = round(total_score / len(EVAL_SET), 3) if EVAL_SET else 0.0
    print(f"\n  MEAN SCORE: {mean_score} / 1.0  ({len([i for i in items if i.get('score',0)>=0.5])}/{len(EVAL_SET)} passed)")

    del model
    gc.collect()

    return {
        "label": label,
        "model": Path(model_path).name,
        "n_gpu_layers": n_gpu_layers,
        "context_size": ctx,
        "mean_score": mean_score,
        "pass_count": len([i for i in items if i.get("score", 0) >= 0.5]),
        "total": len(EVAL_SET),
        "items": items,
    }


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    print("\n" + "="*60)
    print("B-002 Stage 4 Quality Benchmark")
    print(f"Timestamp: {timestamp}")
    print(f"Eval items: {len(EVAL_SET)}")
    print("="*60)

    llama_cpp = __import__("llama_cpp")

    results = {
        "timestamp": timestamp,
        "eval_set_size": len(EVAL_SET),
        "candidate_a_3b": None,
        "candidate_b_8b": None,
    }

    # Candidate A: 3B full GPU, ctx=4096 (production context)
    results["candidate_a_3b"] = run_eval(
        llama_cpp, MODEL_3B, n_gpu_layers=99, ctx=4096,
        label="Qwen2.5-3B Q5_K_M | 99gpu | ctx4096"
    )

    # Candidate B: 8B best partial GPU (17 layers from perf sweep), ctx=2048
    # Use ctx=2048 to be safer on VRAM for the 8B partial offload
    results["candidate_b_8b"] = run_eval(
        llama_cpp, MODEL_8B, n_gpu_layers=17, ctx=2048,
        label="Dolphin3-Llama3.1-8B Q5_K_S | 17gpu | ctx2048"
    )

    out_path = RESULTS_DIR / f"quality_{timestamp}.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, default=str)

    print(f"\n\nResults saved: {out_path}")
    print("\n" + "="*60)
    print("QUALITY SUMMARY")
    print("="*60)
    for key in ["candidate_a_3b", "candidate_b_8b"]:
        ev = results[key]
        if ev:
            print(f"{ev['label']}")
            print(f"  Mean score : {ev.get('mean_score')}")
            print(f"  Pass/Total : {ev.get('pass_count')}/{ev.get('total')}")

    return str(out_path)


if __name__ == "__main__":
    main()
