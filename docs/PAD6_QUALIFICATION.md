# Xiaomi Pad 6: installation and model qualification

What this produces: measurements taken **on the tablet**, with the model and quantization it actually runs. Nothing
measured on the build server or a laptop counts as a tablet result.

## 0. What you need

* The Pad 6, a USB cable, a computer with `adb` (Android platform-tools) and Python 3.
* The qualification build `clu-1.16.0-qualification.apk` (built from commit given in the engineering report, signed with
  the CLU release key, so it installs **over** 1.15.0 and keeps your data). It is a measurement build, not a release.
  SHA-256 is listed in the report; check it before installing: `sha256sum clu-1.16.0-qualification.apk`.
* A model file (GGUF). Start with `Qwen3-0.6B-Q4_K_M.gguf` (about 400 MB) as the baseline; then each stronger model
  (`scripts/model_qualification.sh` downloads them on a computer with internet and checks free space first).
* Free storage on the tablet: model size + 1 GB. A 4B Q4_K_M model is about 2.5 GB.

## 1. Prepare the tablet

1. Settings → About tablet → tap *OS version* 7 times → Developer options → enable **USB debugging**.
2. Connect the cable, accept the computer's key on the tablet, check `adb devices` lists it.
3. For battery figures: charge to at least 80 %, then **unplug** before measuring. For heat: no case, room temperature,
   brightness at about half, other apps closed.

## 2. Install and load a model

```
scripts/qualify_device.sh clu-1.16.0-qualification.apk models/Qwen3-0.6B-Q4_K_M.gguf
```
The script installs the APK, copies the model to `Download/`, opens CLU, records logcat and the thermal state while
you work, and waits for the report. On the tablet:

1. Models → import the file from Download → select it.
2. Settings → Diagnostics → **Run model qualification** (standard: 20 rounds, a few minutes).
   Keep the screen on and do not use the tablet during the run.
3. When the report appears, the script pulls it into `docs/measurements/device/` with logcat, `meminfo`, `battery`
   and thermal dumps, and prints the summary.

Then run it again and press **Sustained run (long)** (80 rounds of longer replies: about 15 minutes with a 0.6B model,
an hour or more with a 4B one). This is the heat, throttling and battery measurement.

## 3. What the report contains (all measured on the tablet)

| Item | Where in the JSON |
|---|---|
| Model name, quantization, file size, context, threads, CPU/GPU backend | `model` (`summary_model` names the quantization, e.g. "Q4_K - Medium") |
| Model load time | `load_ms` |
| Peak RAM (PSS) | `peak_memory_mb`; per round `rounds[].memory_mb` |
| First-token latency | `summary.first_token_ms_median` / `_p95`; per round |
| Sustained generation speed and throttling | `summary.gen_tok_per_s_median`, `sustained_gen_tok_per_s_first_third` vs `_last_third`, `sustained_drop_percent` |
| Thermal behaviour | `summary.thermal_states_seen`; per round `thermal`, `battery_temp_c`; script's `thermal-*.txt` |
| Battery consumption | `summary.battery_used_percent` over `duration_s` (only meaningful unplugged) |
| Context retention | `context_retention.recalled` with `fill` (share of the context the prompt used) |
| Planning call (instruction understanding) | `planning` |
| Crashes and failed requests | `errors`, `summary.rounds_ok`; script's logcat scan (crashes, ANRs, low-memory kills) |

## 4. Daily-use check on the tablet (by hand, 10 minutes)

Use the qualification build normally and note anything that fails:

1. Say hello; ask "Explain how a refrigerator keeps food cold" — expect a real explanation, not one sentence.
2. Follow up: "Which part uses the most electricity?"
3. "My sister lives in Pune" (or any fact), later "Where does my sister live?"
4. Ask about something you don't know (with internet on) — sources should appear.
5. "Write a short document about Diwali from what you can find online" — check Archive → Documents.
6. "Nudge me to take my vitamins at 9 tonight" — check the reminder says 21:00 today.
7. Start a two-part request, press stop half-way, then say "continue the task".
8. Force-close CLU (recent apps → swipe away), reopen: conversation, reminder and document are still there.
9. Switch to voice and ask "why do we have seasons".

## 5. Pass criteria for a model on the Pad 6 (proposed)

* Standard run: 20/20 rounds, no errors, no crash or low-memory kill in logcat.
* First token under 2 s median for ordinary turns; generation at least 8 tokens/s (comfortable reading speed).
* Context recalled at about 70 % fill.
* Sustained run: completes; speed drop under 30 %; thermal status never "severe"; no app kill.
* Peak PSS leaves at least 1.5 GB free for the system and voice models.

Send the files in `docs/measurements/device/` (or commit them); the engineering report's model decision is made from them.
