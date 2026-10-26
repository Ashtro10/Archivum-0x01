# Reply reliability and speed: investigation and fixes

**Status:** fixed and tested in the build container: unit tests, real llama.cpp on x86 and a host latency benchmark. **Not yet verified on a phone or tablet.** The device procedure is section V of [DEVICE_ACCEPTANCE.md](DEVICE_ACCEPTANCE.md). The "before" code is commit `c0e9882`, and the line numbers below refer to it.

## 1. How a message travelled (before)

The text path was:
1. Send → `MainActivity.send()`, which ran in the **activity's** coroutine scope.
2. `CluAgent.respond()` returned a `callbackFlow`, and `handle()` started on `Dispatchers.Default`.
3. Then `inference.select()` → `LlmService.engine()`, which loads the model on first use.
4. Then `generateReply()`: memory retrieval, prompt build, `fitToContext` (token counting on the LLM thread).
5. Then `withContext(IO) { engine.generate() }`. `LlmService.runOnLlm` submits to the single `clu-llm` thread and **blocks** on `Future.get()`.
6. On that thread, `LlamaEngine.generate` → JNI `nativeGenerate`, which:
   - processes the prompt (prefix-cached KV);
   - samples token by token;
   - passes UTF-8 bytes to Kotlin, then through the stop filter;
   - emits `AgentEvent.Text` via `trySend`.
7. Finally `finish()` saves the message and emits `Done`. The UI cleared its `streaming` state only after the flow ended.

Voice uses the same agent from `VoiceSession.runAgent()`, and LiveKit uses it from `Bridge.respond()`.

## 2. Root causes, ranked by impact

| # | Symptom it explains | Root cause (evidence) | Location (before) |
|---|---|---|---|
| 1 | "Response cancelled"; a typed reply dies when the voice screen is used | Cancellation was **global**. `CluAgent.cancel()` cancelled whatever was generating. It was called from every voice path: closing the voice screen (← ⌨ ✕ → `voice.stop()`), tapping the mic while listening, barge-in, stop-playback, and the spoken reply's `onCancel` (which also runs when one spoken reply *supersedes* another). Any of these cancelled an unrelated typed reply. One shared `cancelled` flag was also reset by every new request. | `VoiceSession.kt:94,143,312,459`, `MainActivity.kt:232,247,263,400,421,466`, `CluAgent.kt:107,111,126` |
| 2 | "Nothing happens after Send", long "thinking", replies that vanish | A typed request ran in the **activity's** scope. The activity has no `configChanges`, so rotation, theme change, keyboard or multi-window recreated it, and Back destroyed it. That killed the request silently: the reply was never saved. Worse, coroutine cancellation **never reached llama.cpp**, because `runOnLlm` blocks in `Future.get()`. The abandoned generation kept the single model thread busy up to max tokens (400), and the next message queued behind it. | `MainActivity.kt:417`, `LlmService.kt:214`, `CluAgent.kt:121` |
| 3 | Thinking indicator that never clears | `streaming = null` ran only after normal completion of the collector, with no `finally`. Any exception in the collector, or cancellation of the scope, left the bubble and the ■ button. | `MainActivity.kt:443` |
| 4 | ~20 s for a 1B model; slow first word on every message | The system prompt started with persona text (154 tokens), then **"Current date and time … HH:mm"**, then per-query memories, then the long tool list and rules. The clock changes every minute and memories every query, so llama.cpp's prefix cache broke at token 154. **Every message re-processed 550–730 prompt tokens.** Measured: reused 154 of 562–883 tokens per turn (§3). | `PromptBuilder.kt:94` |
| 5 | Reply "keeps thinking" after it appeared; next message waits ~20 s | After 25 replies, the alternative-style offer generated a **second full reply** after *every* text reply until the owner picked one (the counter resets only on a choice). The flow stayed open meanwhile: the UI showed the answer twice and kept ■, and the next message queued behind that generation. | `CluAgent.kt:299`, `Preferences.kt` (`nextAlternative`) |
| 6 | Stop sometimes ignored | A cancel that arrived before the native call was lost, because `nativeGenerate` resets the flag at its start. There was no per-token check on the Kotlin side. | `clu_llama_jni.cpp:256` |
| 7 | Garbage, "…" or nothing shown | Empty output was shown as "…". A malformed tool call (`<tool>{…` that does not parse) was saved and shown as Clu's reply. Generation errors were saved as Clu's reply, so they entered the next prompt's history. | `CluAgent.kt:286` |
| 8 | Lost words / final event under load (risk) | `callbackFlow` with the default 64-slot buffer and `trySend` drops events when the collector lags. The UI re-queried the whole conversation from SQLite on the main thread **for every token**. | `CluAgent.kt:115,117`, `MainActivity.refresh` |
| 9 | Infinite thinking if the model stalls | No first-token or stall timeout anywhere. | — |

Checked and **not** found to be causes:
- The model is not loaded per request: it stays loaded until 20 min idle.
- Session/KV reuse in the JNI works (it is measured by the tests).
- Stop sequences and EOG are handled natively and by `StopSequenceFilter`.
- The thread count is the performance-core count (5 on SD 8 Gen 2, 4 on SD 870).
- Generation already ran off the main thread.

## 3. Latency, before and after (measured)

**Method:** `LlmLatencyBenchmark`, which runs the real agent:
- the default Clu persona with the full tool list, 5 stored memories and a growing conversation;
- one minute between messages, max 48 tokens, 4 threads;
- a model with the **exact architecture and quantisation of Llama 3.2 1B Q4_K_M** (2048 embd, 16 layers, GQA 32/8, tied embeddings, 846 MB), built by `scripts/make_test_gguf.py --shape 1b` + `llama-quantize`.

The weights are random, so replies are meaningless but the compute cost is real. It ran on the 4-vCPU x86 build container, **not a phone**. Absolute phone numbers will differ, but the ratio of prompt tokens processed carries over. Raw results are in `docs/measurements/llm_latency_host_{before,after}.json`.

| Turn | Prompt tokens | Reused (before → after) | Prompt processing ms (before → after) | First visible word ms (before → after) |
|---|---|---|---|---|
| 1 | 562 → 599 | 0 → 558 | 4649 → 455 | 4760 → 532 |
| 2 | 681 → 713 | 154 → 560 | 3923 → 1036 | 3967 → 1070 |
| 3 | 703 → 737 | 154 → 626 | 4057 → 861 | 4080 → 886 |
| 4 | 819 → 849 | 154 → 697 | 4759 → 1084 | 4786 → 1102 |
| 5 | 883 → 914 | 154 → 764 | 5402 → 1145 | 5432 → 1168 |

Other measurements:

| Measure | Value |
|---|---|
| Model load (host, mmap) | 5.0–6.1 s |
| Warm-up after load (560 instruction tokens) | 3.6 s, done in the background before the first message |
| Generation speed | 18–23 tok/s, unchanged (decode-bound; not touched) |
| Submit → generation start | < 5 ms (memory retrieval + prompt build), recorded per request as `turn_generation_start_ms` |

**Bottleneck after the fix:**
- Generation speed, for long answers: 400 max tokens at about 20 tok/s ≈ 20 s to the *last* word. Words stream from ~1 s.
- The ~150 tokens of the previous exchange that are re-processed each turn.

Quantisation, model, context and threads were **not** changed: nothing was measured on the phones to justify it.

## 4. What changed

**Request ownership** (`CancelSignal`, `Turn`, `TurnOrigin`):
- Every request has its own cancel signal and an origin: TEXT, VOICE or LIVEKIT.
- `CluAgent.cancel(origin)` cancels only that origin. Voice code cancels VOICE, the Stop button cancels TEXT, and LiveKit cancels LIVEKIT.
- A spoken typed reply no longer cancels generation when its speech stops: closing the voice screen silences it, and the text still completes.

**Native cancellation:**
- The signal sets llama.cpp's abort flag. This stops prompt processing and decoding within one step.
- It is also checked after every token in Kotlin, so a cancel landing before the native call is not lost.
- `LlmService` removes a cancelled request that is still queued, without touching the running one.
- When the reply flow's collector goes away, its generation is cancelled at once (watchdog in `CluAgent.timedGenerate`).

**Policy for a new request while one runs:**
- The same origin supersedes the old request, which ends cancelled.
- Different origins both complete, one after the other, because the model runs one generation at a time.
- Optional follow-up work (alternative reply, diary) is cancelled by any new request, and no longer counts as busy.

**Terminal states:**
- Every request records exactly one of completed, cancelled, timeout, model init failed or inference error.
- The UI clears the thinking state in `finally` and on `Done`.
- Cancellation is not an error; timeout, empty output, model failure and inference error are shown as errors with Retry.
- Errors are **not saved as Clu's reply**, so they never pollute the next prompt.

**Timeouts:** no first token within 120 s, or no new token for 30 s, stops the request with a clear message. Both limits are constructor parameters, and the tests use 300–400 ms.

**App-scoped chat** (`ChatTurns`):
- Typed requests run in the app's scope. The chat screen only observes them, so rotation, theme change and leaving/returning keep the reply running, and no stale bubble remains.
- Duplicate Sends are ignored while a reply runs.
- Tokens update only the streaming bubble: there is no database query per token on the main thread.
- The event channel is unbounded, so no token or final event can be dropped.

**Cache-friendly prompt:**
- The fixed instructions (persona, style, tools, rules) come first.
- Time, connectivity, speaker and memories go in a `[Context]` block on the newest user message.
- After each model load, the fixed instructions are processed in the background (`CluAgent.warmUp`), so the first message only processes its own words.
- Context overflow now trims memories before instructions.

**Output handling:** tool markup is never shown as reply text, and an empty reply is an error with Retry.

**Diagnostics** (`RequestTrace`):
- The events MESSAGE_RECEIVED → REQUEST_CREATED → MODEL_READY → GENERATION_STARTED → FIRST_TOKEN → GENERATION_COMPLETED / REQUEST_CANCELLED / REQUEST_TIMEOUT / MODEL_INIT_FAILED / INFERENCE_ERROR are recorded with timestamps.
- They appear in Diagnostics → Requests and in logcat tag `CluTurn`.
- **No message content is logged**: only lengths, token counts, timings and error categories.
- Per-request perf metrics: `turn_model_ready_ms`, `turn_generation_start_ms`, `turn_first_token_ms`, `turn_total_ms`.

## 5. Models and data across updates

| Item | Where | Survives an in-place update | Survives uninstall |
|---|---|---|---|
| Models (imported or downloaded) | app-specific storage `Android/data/ai.clu/files/models` (`getExternalFilesDir`) | yes | no (Android deletes it) |
| Chats, memories, settings, model registry | internal `databases/clu.db` | yes | no |
| PIN / keys | Android Keystore + encrypted store | yes | no |

**Update compatibility** (checked on the built APK):
- Same package `ai.clu`.
- Same signing certificate SHA-256 `B8:D4:60:94:…:BE:C6`, verified with apksig, scheme v3.
- versionCode raised 1 → 2.
- `allowBackup=false`, so data is not restored after an uninstall either.
- Models are not bundled in the APK.

**Integrity:**
- At import or download, the GGUF header **and tensor table** are checked. A file whose tensor data is cut off is rejected as "file is incomplete … (interrupted download or copy?)" before llama.cpp sees it.
- Before each load, a file whose size changed since it was checked is validated again. A damaged file is marked "Incompatible or corrupted" with a clear message.

**Import and download:**
- Import copies to `name.gguf.part`, checks the byte count, validates, then renames. An interrupted copy never becomes a model, and stale `.part` files are removed.
- Importing a file name that is already registered no longer overwrites a model that may be loaded.
- A download is skipped when a valid local copy exists, and a damaged copy is replaced.
- Downloads use Android's download manager, which resumes after interruptions.

Nothing was deleted from existing installs by this change.

## 6. Acceleration

**Verified (build configuration):** `libclu_llama.so` is llama.cpp's **CPU backend only**, compiled for `armv8.2-a+dotprod+fp16` (NEON + dot-product + FP16). It has no GPU (Vulkan/OpenCL) or NPU backend. The "GPU" acceleration setting only takes effect when a GPU backend exists, and none does.

**Not verified:**
- Speed on the phones.
- Whether the int8 matrix-multiply (i8mm) path of the Snapdragon 8 Gen 2 would help. It cannot be compiled in unconditionally, because the Snapdragon 870 lacks it and would crash. It would need llama.cpp's runtime CPU-variant dispatch; that is a measured-on-device follow-up, not done blind.

AI models → Benchmark and the "Native backend" line show what the device actually runs.

## 7. Tests

`core/src/test/kotlin/ai/clu/core/LlmReliabilityTest.kt`, 17 tests:

| Required test | Mocked engine | Real llama.cpp (tiny model) |
|---|---|---|
| 1 Normal request completes (full lifecycle) | ✓ | ✓ |
| 2 Consecutive requests succeed, prompt cache reused | — | ✓ (5 turns, >100 tokens reused per turn) |
| 3 Slow generation completes / stalled one times out | ✓ | — |
| 4 Runtime exception clears thinking | ✓ (`IllegalStateException`, `LlmException`) | — |
| 5 Explicit cancel is clean | ✓ | ✓ (stops early, model reusable) |
| 6 Voice cancel does not cancel text | ✓ | ✓ (cancelled queued request does not stop the running one) |
| 7 Repeated Send | ✓ (ignored; agent-level supersede) | — |
| 8 Empty / invalid output | ✓ (empty, malformed tool call) | — |
| 9 Init failure → useful error | — | ✓ (truncated tensors; not enough memory) |
| 10 Missing / corrupted model detected | — | ✓ (garbage, deleted, half file, truncated after registration) |
| 11 Returning to chat leaves no stale state | ✓ | — |
| 12 Streaming does not block the main thread | ✓ (worst UI frame < 100 ms during streaming) | — |

Extra tests:
- Closing the flow cancels the generation (mocked).
- Follow-up work never delays the next reply (mocked).

The mocked engine is a test double, `SlowEngine`. The real tests use the actual JNI bridge and llama.cpp with random weights: real inference mechanics, meaningless text.

Full suite: 306 tests, 0 failures, 7 skipped (opt-in benchmarks and model-dependent measurements).

## 8. Remaining device blockers

- No phone or tablet was available, so every claim about the iQOO Neo 9 Pro and Xiaomi Pad 6 is unverified. Run section V of DEVICE_ACCEPTANCE.md.
- Real model quality and speed with the owner's actual 1B model are unmeasured.
- The thermal behaviour of long sessions is unmeasured.
- Generation speed bounds long answers: about 20 s for 400 tokens at about 20 tok/s.
