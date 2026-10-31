# CLU benchmark pack for the target PC (M1)

This measures how fast local models run on **your** PC (Ryzen AI 7 350, Radeon 860M, 32 GB), on the CPU and on the
GPU (Vulkan). It also measures how much memory each model takes and how much it slows down other work. CLU's model and
backend choice is made from these numbers, not from guesses.

Nothing is installed on your system. Everything (llama.cpp tools, models, results) stays in this folder. Delete the
folder to remove it all.

## What you need

* This folder on the PC (for example, copy `bench\windows` from the repository to `C:\clu-bench`).
* An internet connection for the first run: about 60 MB of llama.cpp tools, plus models if you use `-Download`.
* About 30 minutes with the PC **plugged in**, other heavy programs closed.

## Run it

1. Open **PowerShell** (Start → type "PowerShell").
2. Go to the folder: `cd C:\clu-bench`
3. Pick one:
   * **Use the models you already have** (recommended first, for example Primus's models folder):
     ```
     powershell -ExecutionPolicy Bypass -File .\Run-CluBenchmark.ps1 -ModelDir "C:\Users\ASUS\Documents\Astra\benchmarks\models" -Coexistence
     ```
   * **Download the candidate models too** (about 8 GB; it keeps at least 20 GB of disk free):
     ```
     powershell -ExecutionPolicy Bypass -File .\Run-CluBenchmark.ps1 -Download -Coexistence
     ```
   * Add `-Quick` for a short trial run (one repetition, one thread count).
4. When it says **Done**, double-click `report.html` in this folder.
5. Send back `results\results.json`. It contains hardware names, driver versions, file hashes and timings, plus the
   paths of the model files it used. It contains nothing else from your PC.

`-ExecutionPolicy Bypass` lets Windows run this one unsigned script without changing any setting.

## What it does, step by step

1. **Hardware:** reads CPU, GPU and driver version, RAM, Windows version, power plan, and whether it's on battery.
2. **llama.cpp:** downloads release **b11844** (the exact llama.cpp CLU uses) for CPU and for Vulkan from GitHub. It
   checks each file against the SHA-256 GitHub publishes, and deletes the file and stops if they differ.
3. **Models:** finds the candidate models in your folder (and with `-Download`, fetches the missing ones from Hugging
   Face, checked against the SHA-256 Hugging Face publishes). See `bench.config.json` for the list.
4. **Measuring:** for each model, it runs llama.cpp's own `llama-bench`:
   * reading a 512-token prompt, and writing 128 tokens;
   * on the CPU with 4, 6 and 8 threads, and on the GPU through Vulkan.

   While each run goes, it records peak memory, including the GPU's share. The 860M has no memory of its own, so it
   borrows system RAM.
5. **Effect on you** (`-Coexistence`): times a fixed task alone, then while a model generates at low priority. That
   shows how much CLU would slow down what you're doing.
6. **Results:** `results\results.json` (raw) and `report.html` (the recommendation).

## How the recommendation is made

Rules from `analyze.js`, explained in `docs/convergence/M1_BENCHMARK.md`:

* **Main model:** the largest standard model that writes at least **8 tokens/s**, reads a prompt at at least
  **50 tokens/s**, and peaks under **7 GB**.
* **Fast model:** at least **20 tokens/s** and under **3 GB**.
* **GPU:** used only if it's at least **10 % faster** than the CPU, because it competes with games and video.
* **Threads:** fewer threads when within **5 %** of the best, leaving cores free for you.

Primus's current model (with its refusal training removed) is measured for comparison only. It's never chosen as
CLU's default.

## If something goes wrong

* "running scripts is disabled": use the exact command above (it includes `-ExecutionPolicy Bypass`).
* "No vulkan asset": GitHub renamed the download. Send the output, and CPU results are still recorded.
* A Vulkan run fails: that's a result, not a problem. The report says the GPU isn't usable through llama.cpp Vulkan on
  this driver.
* The script saves results after every run, so an interrupted run still leaves useful data.
