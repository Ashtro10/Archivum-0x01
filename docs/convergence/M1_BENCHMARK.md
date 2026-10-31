# M1 — Measuring the target PC

_2026-10-31._

* **Status:** the benchmark pack is built and its analysis is tested.
* **Waiting on:** the run on your PC (Ryzen AI 7 350, Radeon 860M, 32 GB). No decision about models or the GPU backend
  is made until those results exist.

## What was delivered

`bench/windows/`:

| File | Role |
|---|---|
| `Run-CluBenchmark.ps1` | The runner (PowerShell 5.1, built into Windows; nothing to install). It downloads and checks llama.cpp b11844 (CPU and Vulkan builds), finds or downloads models, runs `llama-bench`, records peak memory including GPU shared memory, optionally measures the effect on a foreground task, and writes `results/results.json` + `results/results.js`. |
| `bench.config.json` | Candidate models (standard instruction models for main and fast roles, Primus's existing models for comparison, an embedding model), thread counts, and the llama.cpp release. |
| `analyze.js` | The decision rules as pure functions (the same file runs in the report and in the tests). |
| `report.html` | Opens by double-click; shows the recommendation, warnings, each model's best setting and every run. |
| `README.md` | Plain-language steps for running it. |
| `test/` | Node tests + a fixture of real `llama-bench` output. |

## Why llama.cpp b11844

CLU vendors llama.cpp at commit `06230cb`. `git describe --contains` maps that commit to release tag `b11844`, and the
`llama-bench` output reports `build_number: 11844`. Benchmarking the official Windows builds of that release measures
the same engine CLU will embed.

## Decision rules

From `analyze.js`; any rule can be overridden in `bench.config.json` → `rules`.

| Rule | Value | Reason |
|---|---|---|
| Total ceiling | 16 GB | Your budget (a ceiling, not a target) |
| Main model peak | ≤ 7 GB | Leaves room for the host, voice and a fast model. GPU shared memory is counted, because the 860M borrows system RAM |
| Fast model peak | ≤ 3 GB | Can stay loaded while you work |
| Main model generation | ≥ 8 tokens/s | Spoken replies need about 4–6 tokens/s; 8 gives margin |
| Main model prompt reading | ≥ 50 tokens/s | A 512-token prompt in about 10 s at worst (the prompt cache avoids most repeats) |
| Fast model generation | ≥ 20 tokens/s | Routing and short replies should feel instant |
| Use the GPU only if | ≥ 10 % faster than the best CPU setting | The GPU is shared with games and video |
| Threads | Fewest within 5 % of the best | Leaves cores for you |
| Foreground slowdown warning | > 25 % at below-normal priority | Tells the M2 governor how aggressive it must be |
| Choice | Largest *eligible* model that passes | Quality first; Primus's abliterated model is never eligible (decision 3) |

## What was verified, and how

| Item | How | Result |
|---|---|---|
| Analysis rules | `node --test bench/windows/test/*.test.js`: 8 tests | 8/8 pass |
| Parsing real `llama-bench` output | The fixture is real b11844 output from this build container (SmolLM2-135M and a 1B model, CPU, 4 and 8 threads) | Parsed correctly. The data also shows 8 threads slower than 4 on a 4-core machine, which is why the pack measures several thread counts |
| Report page | Rendered in headless Chromium from results shaped like the runner's output, built from the real fixture | Renders, no script errors |
| **The PowerShell runner** | **Not executed.** This environment is Linux without PowerShell, and downloading and running PowerShell here was blocked by the session's safety policy | Reviewed by hand. Three PowerShell 5.1 pitfalls were found and fixed before delivery (`$args` reuse, ordered-dictionary `+`, `ConvertFrom-Json` array wrapping) |
| llama.cpp download names and hashes | Not checkable here: GitHub's API and Hugging Face are blocked from this environment (proxy 403) | The runner looks up the release and model files on your PC and checks each against the publisher's SHA-256; a mismatch deletes the file and stops |
| Model repositories/file names in the config | Chosen from public listings, not checkable here | A missing one is reported and skipped |

## Changes to the roadmap

* **Speech benchmarks** (whisper.cpp, sherpa-onnx) move to **M4**, where CLU's own voice pipeline will exist on
  Windows to measure end to end. Voice models are small (well under 1 GB together) and don't drive the memory or
  backend decision this milestone is for.
* **Building CLU's own JNI libraries for Windows** moves to **M2**, where the host needs them. M1 measures the
  identical upstream release instead.

## What happens with the results

From `results.json`, M2 will be configured with:
* the backend (CPU or Vulkan);
* the thread count;
* the main and fast models;
* the governor's thresholds (from the foreground-slowdown measurement).

The numbers and the decision will be recorded here. If no main model passes, CLU runs on its fast model and a smaller
main model is tried; the report says so.

## Rollback

Nothing outside `bench/` and `docs/convergence/` changed. Delete those folders.
