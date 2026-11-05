# CLU performance report (updated 2026-11-05, release 1.10.0)

## What was measured, and where

The measurements come from `PerformanceBaselineTest`, CLU's existing baseline. It drives the real engine
(CluCore → CluAgent → inference service → llama.cpp) with a real GGUF model on the build host:
- 4 CPU threads, x86-64;
- SmolLM2-135M-Instruct Q4_1, 4,096-token context.

It was run twice:
- **Before:** the commit preceding this assignment (`5199fe5`), in a separate Git worktree.
- **After:** the final code.

These are host numbers. On a Snapdragon 870 or Dimensity 9300 with a 1–3B model, absolute times are larger. The
differences between before and after are what this report is about. On-device measurement steps are in
[DEVICE_ACCEPTANCE.md](DEVICE_ACCEPTANCE.md); the app records the same timings itself in Diagnostics.

Raw results: `docs/measurements/perf-2026-11-05-before.json` and `docs/measurements/perf-2026-11-05-after.json`.

## Before and after

| Metric | Before | After | Notes |
|---|---|---|---|
| Core start-up (open the DB, migrate, register tools) | 352 ms | 334 ms | Within noise. The v7 migration (one table, one index) runs once |
| Instruction prompt (tokens) | 767 | 883 | +116 tokens for the five butler tools. The first version added 185; descriptions were cut to the minimum the model needs |
| Instruction warm-up (once per model load, in the background) | 1,524 ms | 1,866 ms | The cost of the longer prompt, paid once |
| Time to first token, median of 5 turns | 322 ms | 316 ms | Unchanged: the tool list sits in the cached prefix (790–890 tokens reused per turn) |
| Generation speed, median | 31.9 tok/s | 41.3 tok/s | Varies with host load and disk cache; not caused by this change |
| Memory lookup, 2,000 records, median | 3 ms | 4 ms | Unchanged |
| Cancel latency | 24 ms | 23 ms | Unchanged |
| Process memory (RSS), model loaded, after turns | 332 MB | 358 MB | +26 MB, within run-to-run variation of the native allocator (the after-trim run was 347 MB) |
| Model load | 7,320 ms | 599 ms | Disk-cache effect: the first run read the file cold. **Not an improvement from this work** |

### The new deterministic paths

The butler and device commands ("remind me…", "add milk to my shopping list", "pause the music", "call Mom",
"brightness 40") go through `IntentRouter` and run without the language model.
- These turns don't wait for a model at all: no model load, no prompt reading, no generation.
- On a phone this turns a multi-second model turn into an action that completes in milliseconds.
- In the integration test (`ButlerTest.throughClusAgentWithARealDatabase`), six butler commands through CLU's agent
  with a real SQLite database complete together well under a second on the host.
- The 604 regular expressions (all patterns, including the new ones) compile on Android's ICU engine
  (`icuRegexCheck` in the build).

## Optimisations in place (preserved)

- KV-cache prefix reuse across turns, with the instruction block ordered first ([LLM_RELIABILITY.md](LLM_RELIABILITY.md)).
- Model loading:
  - The model loads when a CLU screen becomes visible or voice starts, not at process start.
  - It is released on memory trim after an idle delay and auto-unloaded after idle minutes.
- With the PC paired ("Automatic"), the phone loads no model of its own.
- Reminders use AlarmManager: no polling, no wake-locks, nothing running between reminders.
- Notification reading uses the live list on request: no background processing, nothing stored.
- Proactive evaluation runs about hourly through JobScheduler, batched by the OS.

## Measured cost introduced, and its mitigation

Every tool the model may call adds a line to the cached instruction prompt. The 13 new tools add about 116 tokens on
the core side (measured) and about the same again for the 8 Android-only tools (not measurable on the host).
- **Per-turn latency is unaffected** while the prefix stays cached.
- The cost appears in the one-time warm-up after a model load. That runs in the background before the first reply
  when the model is loaded on screen open.
- If on-device warm-up becomes a problem, owners can disable tools they don't use (Settings → Tools →
  "Allow <tool>"). Disabled tools are left out of the prompt.

## Not measured (no device)

- Battery drain.
- Thermal behaviour.
- Voice activation latency on device.
- Real STT/TTS latency on device.
- ANR risk under load.
- Memory pressure on 8 GB devices with memory extension.

The app records cold start, speech-end → first audio and stop latency in Diagnostics, ready for the device run.

## Release 1.10.0 (calendar, notifications, calls, evening summary, routines, task sync)

Same baseline, same model and settings, on the idle build host (host-only numbers). Raw results:
`docs/measurements/perf-2026-11-05-1.10.0.json`.

| Metric | 1.9.0 | 1.10.0 | Notes |
|---|---|---|---|
| Core start-up | 334 ms | 321 ms | The v8 migration (three columns) runs once |
| Instruction prompt | 883 tokens | 1,076 tokens | +193 for seven new core tools: five calendar, the evening summary, and the honest vision fallback |
| Instruction warm-up (once per model load) | 1,866 ms | 2,270 ms | The cost of the longer prompt, paid once in the background |
| Time to first token, first turn (same question, cached prefix) | 352 ms | 324 ms | Per-turn latency is unaffected by the tool list |
| Time to first token, median of 4 turns | 316 ms | 598 ms | **Not comparable.** In the 1.9.0 run the 135M model produced empty replies, so the conversation never grew. In this run it wrote real answers (400 tokens on turn 1), and later turns read that longer history. Per-turn prompt reading tracks conversation length, not this release |
| Memory lookup, 2,000 records | 4 ms | 3 ms | Unchanged |
| Cancel latency | 23 ms | 27 ms | Within noise |
| Process memory after turns | 358 MB | 364 MB | Within run-to-run variation |

### Background cost of the new features (by design)

- Reminders, appointment alerts and the evening summary share one AlarmManager alarm, set to the soonest of the three.
  Nothing polls; between alarms nothing runs.
- Notification announcements react to Android's "posted" callback only when the owner enabled them. No notification
  is stored; the announced-key set is in memory and bounded to 300 entries.
- Call awareness uses Android's audio-mode callback (no permission, no polling). Call screening runs only when a call
  arrives.
- Routine recognition runs at most once a day, inside the existing hourly proactive job, over 30 days of local rows.
- Task sync rides the existing sync session: a change-log entry per change, no extra connections.

### On-device expectation (not measured)

With a 1–3B model on the Snapdragon 870, the 193 extra prompt tokens add roughly 1–3 s to the one-time warm-up after
a model load. Owners can drop tools they don't use from the prompt (Settings → Tools → "Allow <tool>"); disabled tools
are not offered to the model.
