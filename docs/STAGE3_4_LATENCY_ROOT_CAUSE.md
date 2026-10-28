# Stage 3.4 — real-device latency root-cause investigation (1.5.5-prototype)

Evidence: the owner's performance report from the **vivo I2304 (iQOO Neo 9 Pro), Clu 1.5.4**. It mixes retained
samples from different turns, so percentiles of different metrics are **not paired**. No device was available in this
stage: every "after" figure is from the build machine (4 vCPU x86, real 1B Q4_K_M model, the same llama.cpp JNI code)
and **device improvement is unverified**.

## 1. What the baseline numbers say (and don't)

| Observation (device) | Reading |
|---|---|
| prompt_ms p50 3.09 s ≈ new tokens p50 174 ÷ prefill p50 56 tok/s (3.1 s) | consistent: a normal turn's prompt time is its new tokens at ~56–63 tok/s |
| prompt_ms p90 19.7 s ≈ 1,164 new tokens ÷ ~60 tok/s (19.4 s) | consistent: slow turns are slow because they process **many new tokens**, not because prefill is slower (prefill p50 56, p90 63 — narrow) |
| transcript→first token p50 2.97 s ≈ prompt_ms p50 | the agent adds little around the model at the median (memory retrieval 0.4 ms) |
| end of speech→first audio p50 6.35 s | ≈ ASR 0.86 + prompt ~3.0 + chunker ~0.3 + first TTS→audio ~2.0 (unpaired sum, indicative only) |

So the tail is a **token-count** problem: which turns had to re-read >1,000 prompt tokens? The 1.5.4 diagnostics
could not say (no per-turn cause, no request id on generation samples, no model settings in the report).

## 2. Metric definitions and consistency checks

All of `prompt_ms`, `first_token_ms`, `prompt_tokens`, `prompt_new_tokens`, `prefill_tok_per_s`, `tokens_per_s` come
from **one native call** (`nativeGenerate`, `steady_clock`):

* `prompt_ms` = from entering the call (before tokenising) to the end of the last prompt batch: tokenising the whole
  prompt + processing exactly `promptTokens − reusedTokens` tokens. **Excludes** model load, waiting for the model thread,
  chat-template formatting and history fitting (all before the call). Not counted twice anywhere.
* `first_token_ms` = same start → first sampled token = `prompt_ms` + one sampling step (it *contains* prompt_ms;
  never add the two). It was −1 when the model ended with no token; 1.5.5 records that as `first_token_none` instead
  of a −1 "time".
* `prompt_tokens` = tokens of the full formatted prompt; `prompt_new_tokens` = prompt − reused (the tokens actually
  processed in `prompt_ms`); `prefill_tok_per_s` = new ÷ prompt_ms, only when new ≥ 16. Same call, same interval,
  compatible counts — **consistent**.
* These are per **generation**, not per turn: a request with a tool/search step has two; Diagnostics' "Test model" and
  the benchmark add their own. 1.5.5 adds per-turn metrics (below) with the request number.
* Stopped generations were silently dropped from these metrics (bias towards fast turns); now `prompt_cancelled_ms`.
* Background: native time is monotonic and would include a frozen period; generation samples now use the visibility
  classification (`…_background_ms`) like request timings.
* Voice-turn metrics (`speech_end_*`, `transcript_to_first_token_ms`, `first_tts_to_first_audio_ms`) use the wall
  clock, all from one `TurnTimings` object per reply — paired within a turn. `endpoint_to_first_audio_ms` =
  `speech_end_to_first_audio_ms` − the fixed 800 ms end-of-speech pause.

New in 1.5.5 (numbers only, no text):

* `llm_queue_ms`: waiting for the model thread before a generation starts (another generation, a load, warm-up).
* Per request: `turn_prefill_<cause>_ms` and `turn_new_tokens_<cause>`, cause ∈ `steady` (normal), `retrim` (history
  cut to fit the window this turn), `tool_result` (later step with a tool/search result), `after_load` (first request
  after loading: only the instructions were prepared), `cache_replaced` (something else used the model in between),
  `system_changed` (instructions changed, e.g. online/offline tool list), `cold` (nothing cached). Detected from the
  native `cachedTokens` (tokens in the cache before the call) against what the previous request left there.
* `first_chunk_synth_ms`, `first_chunk_chars`, `first_audio_after_synth_ms`: first TTS request → audio split into
  synthesising the first chunk vs. getting it heard.
* **Recent voice turns** in the shared report: one line per turn, linked to its request number: asr · accept · model
  ready · prompt build · queue · prefill (cause, new of total tokens) · first word · TTS request · first synthesis
  (chars) · audible · end of speech → audio.
* Report conditions now include context length, threads (prompt / generation while speaking), batch, max reply
  tokens and the model's type and size (not its file name).

## 3. Root cause found and fixed: stopping a reply threw away the whole prompt cache

Code path: Stop, barge-in or a newer request → `CancelSignal` → `nativeCancel` sets the abort flag → the decode step in
progress returns 2 (aborted). `nativeGenerate` then called `llama_memory_clear` + `cached.clear()` — for *any* failed
decode, in both the prompt loop and the generation loop. Because cancelling almost always lands inside a decode step,
**every interrupted reply erased the cache**, and the next reply re-read the entire conversation (instructions +
history). llama.cpp documents that an aborted decode keeps the completed steps, so nothing needed clearing.

Fix (native, 10 lines): on an abort, remove only the positions after the last confirmed token (`cached` is extended
only after a successful step); other failures still clear. Plus `cachedTokens` in the statistics.

Reproduction and verification:

* `LlamaEngineIntegrationTest.aCancelledReplyKeepsThePromptCache` (real JNI, tiny model): before the fix the next prompt
  reused **0 of 162** tokens; after, the conversation stays cached, and the reply from the kept cache equals the reply
  from a fresh prompt (cancel during the prompt this time) — the kept state is exact.
* Real 1B model, 20 spoken turns, every 4th interrupted at its first word (`PromptCacheAudit`,
  `docs/measurements/stage3_4_cancel_cache_{before,after}.json`, host):

| turn after an interruption (n = 4 each) | reused tokens | new tokens | prompt time (host) |
|---|---|---|---|
| before (1.5.4 library) | 0, 0, 0, 0 | 1,277 · 1,578 · 1,873 · 1,683 | 8.5–13.4 s |
| after | 1,103–1,689 | 171 · 171 · 173 · 173 | 1.3–1.5 s |

  All other turns are identical in both runs (~238 new tokens; one history cut of ~735 in each).

Relevance to the device report (inference, not proof): at the phone's ~56–63 tok/s, a turn after an interruption
re-read ~1,300–2,100 tokens ≈ 20–37 s — the size of the p90 (19.7 s, 1,164 new tokens). The requested acceptance run
(0v) has 3 interruptions and a Stop in ~18 turns, i.e. ~20 % of turns follow a cancel, enough to set the p90. The other
large-token cause, history re-cuts (`retrim`, ~735 new tokens here, more at 4096 context), remains by design; the new
per-cause metrics will show the split on the phone.

## 4. Not changed

Thread counts, history length, personality/instructions, chunking, TTS engine, barge-in thresholds. The ~2 s first TTS
request → audio is now split into synthesis vs. playback start for the next report; nothing in this stage proved a
cause for it.

## 5. Smallest next device test

Clear measurements; one voice conversation of ~15 exchanges with 3 interruptions; share the report. Check:
`turn_prefill_steady_ms` vs the others (expect no turn after an interruption with `new` ≈ whole prompt; the turn
lines show the cause), `turn_new_tokens_retrim`, `llm_queue_ms`, and `first_chunk_synth_ms` vs
`first_audio_after_synth_ms`.

## 6. Tests and release checks (build machine)

* `./gradlew test check :app:assembleRelease -Pclu.verify=release --rerun-tasks` (twice): core 380 tests, 0 failures,
  10 skipped (opt-in benchmarks/audits, model-dependent Whisper tests, the namespace-only offline test); app 62, 0
  failures. Discovery audit: every declared `@Test` ran or was reported skipped.
* New: `LlamaEngineIntegrationTest.aCancelledReplyKeepsThePromptCache` (fails on the 1.5.4 library: reused 0 of 162),
  `TurnDiagnosticsTest` (prefill causes after load / steady / cache replaced / cold / retrim; stats for the timeline;
  timeline line; report section).
* Offline namespace test (`scripts/test_offline.sh`): 6 runs this stage, **1 failure** (first run after the first full
  build: "offline ASR transcript: ''"), 5 passes, including one immediately after a full build. The same failure
  happened once in Stage 3.3. The failing step is the Python/sherpa-onnx helper that synthesises a test sentence and
  transcribes it (no Clu code involved); run directly in a network namespace it passed 30/30. Root cause **not
  found**; the test now prints the helper's audio length/peak on failure, so the next occurrence shows whether the
  synthesised audio was empty.
* APK: aapt2 badging, apksig v3 + zipalign, verifyApk (27 components, 377 JNI externals), secret scan of the unpacked
  APK — all pass.
