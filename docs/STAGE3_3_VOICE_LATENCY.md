# Stage 3.3 — voice latency audit and targeted fixes (1.5.4-prototype)

Baseline: the owner's performance report from the **vivo I2304 (iQOO Neo 9 Pro), Android 16, Clu 1.5.3 (versionCode
10)**, on-device engine, model loaded, 154 s uptime, PSS 2228 MB (native 1777 MB). **No device was available in this
stage**: every "after" number below comes from the build machine (4 vCPU x86, real 1B Q4_K_M model through the same
llama.cpp JNI bridge), and **device improvement is unverified** until the 0v/0w checks in DEVICE_ACCEPTANCE.md are run.

## 1. The voice path and what each metric measures

| Stage | Metric | Clock and origin (confirmed in source) |
|---|---|---|
| owner stops talking → VAD endpoint | (not a metric: fixed end-of-speech pause, default 800 ms) | `VoiceSession`: `userSpeechEnd = endpoint − minSilence` |
| endpoint → recognised text | `asr_finalize_ms` (decode only), `asr_end_to_text_ms` (= pause + decode), `speech_end_to_transcript_ms` | wall clock (`currentTimeMillis`), same clock for all voice timings |
| text → model ready | `turn_model_ready_ms` | `RequestTrace` monotonic (`nanoTime`) from request start; includes waiting for a model **load** or a busy model thread |
| model ready → generation start | `turn_generation_start_ms` | memory retrieval (`memory_retrieval_ms`, ≈1 ms), prompt fitting |
| prompt processing | `prompt_ms` (≈ `first_token_ms`), new in 1.5.4: `prompt_tokens`, `prompt_new_tokens`, `prefill_tok_per_s` | native `steady_clock`, per `generate` call |
| text → first token | `turn_first_token_ms` (agent), `transcript_to_first_token_ms` (voice) | see above |
| first token → first TTS request | `first_token_to_first_tts_ms` (≈300 ms: chunker waits for a phrase) | `StreamingSpeech` |
| first TTS request → first audio | `first_tts_to_first_audio_ms` | synthesis of the first chunk + AudioTrack start |
| end of speech → first audio | `speech_end_to_first_audio_ms`, `endpoint_to_first_audio_ms` (= the former − 800 ms pause) | different origins by design; **not additive with the per-stage rows** (stages overlap: synthesis runs during generation) |
| playback continuity | `max_chunk_gap_ms`, `audio_started_before_generation_end_ms` | per reply |
| generation speed | `tokens_per_s` | per `generate` call |
| barge-in | `barge_in_detect_ms` | see §2 (origin fixed) |
| stop / interrupt | `stop_to_silence_ms`, `interrupt_to_silence_ms`, `cancel_complete_ms` | app-side only (not acoustic) |

## 2. Instrumentation audit — which baseline numbers are valid

| Baseline value | Verdict | Cause (confirmed from source) |
|---|---|---|
| `model_load_ms` max 3,442,057 ms (57 min), p90 115 s | **invalid as load time** | Measured with the wall clock around `LlamaEngine.load`. The model also loaded at **process start**, including processes Android started in the background (scheduled work, sync), which Android throttles and then freezes; the frozen time was counted. 25 loads over 33 process starts: almost every start loaded the model. The ~1.3–3.8 s samples (p50 3.8 s, last 3.0 s) are plausible real loads. |
| `turn_total_ms` max 501,763 ms | **inconclusive** | Monotonic clock, but recorded for every outcome (cancelled, timed out, failed) and with no record of the app being hidden during the request. Most likely a request spanning time in the background; cannot be told apart in 1.5.3 data. |
| `turn_first_token_ms` / `first_token_ms` p90 ≈ 19–20 s | **valid, real** | Prompt processing: `prompt_ms` ≈ `first_token_ms` at every percentile. Explained by the prompt-cache defect in §3.1. |
| `tokens_per_s` last 5.4 vs p50 15.9 (min 3.9) | **valid but mixed** | Recorded for every generation, including very short ones (a 3-token reply's rate is noise), and slowed by CPU contention with speech synthesis (§3.2). |
| `first_tts_to_first_audio_ms` p50 1.94 s | **valid** | First-chunk synthesis while the model generates on all performance cores (§3.2); `tts_ms_per_char` p50 67 vs best 5.5 (12×). |
| `audio_started_before_generation_end_ms` p50 −346 | **valid** | Audio typically starts *after* generation ends: synthesis only gets the CPU once the model stops. Interrupted replies were mixed in. |
| `max_chunk_gap_ms` p50 4.4 s | **valid, partly mixed** | Same contention; interrupted replies were mixed in. |
| `barge_in_detect_ms` p50 602, p90/max 3178 | **origin wrong for the tail** | Measured from the endpointer's speech start (`speechStartAt`), which can be seconds older than the speech that triggered the decision (Clu's echo or an earlier utterance still "open"). The detector needs 180 ms of loud speech, after the VAD's own onset delay (~0.25–0.45 s measured earlier): ≈0.4–0.6 s is the expected real range. |
| `turn_model_ready_ms` p90 3.9 s, max 13.4 s | **valid** | ≥10 % of requests waited for a model load at request time (model unloaded while away, or never preloaded in that process). |
| Clocks | consistent | Voice timings all wall clock; agent timings all monotonic; no negative durations possible (`TurnTimings.d()` drops b < a). |
| Pairing | OK | Each voice reply owns its own `TurnTimings` object; nothing is paired across turns. |
| Sample counts | small | n = 3–33 per metric across the device's history (several sessions mixed); p90 of n=8 is the 8th value. |

Fixes to the instrumentation (no sample is deleted; kinds are kept apart):

* **Background:** the app now tracks whether any Clu screen is visible. A duration that spans time with no visible
  screen is stored as `…_background_ms` (model loads and request timings).
* **Outcome:** request timings of stopped, timed-out and failed requests are stored as `turn_*_cancelled_ms`,
  `_timeout_ms`, `_error_ms`; playback figures of interrupted replies as `…_interrupted_ms`.
* **Generation:** `tokens_per_s` only for ≥16 generated tokens (others `tokens_per_s_short`); new `prompt_tokens`,
  `prompt_new_tokens`, `prefill_tok_per_s`, so a slow first token can be attributed to cache misses vs slow prefill.
* **IDs:** request timings carry "request #N text|voice", voice timings "voice turn N" (in the sample detail, not in
  the shared report; no text).
* **Barge-in:** measured from the start of the unbroken voice activity that triggered it.
* Model load timed with the monotonic clock.

## 3. Confirmed causes and fixes

### 3.1 Prompt cache lost every turn in long conversations (first-token p90 ≈ 19 s)

llama.cpp reuses the longest common prefix of the previous prompt. When the history no longer fitted the context
window, `fitToContext` dropped **one** oldest message per turn, so the history started one message later every turn:
the common prefix ended right after the system prompt and the **whole history was re-read before every reply**. The
voice screen keeps one conversation, so this is the normal state after a while; with the 4096-token default it means
re-reading roughly 1,500–2,500 tokens per reply (an estimate from the window size; not measured on the phone).

**Fix:** when it overflows, the history is cut once to 60 % of the room it has (exact token counts, binary search) and
later turns start from the same message until it overflows again; the kept history always starts with the owner's
message. Short conversations are unchanged.

Host measurement, real 1B model, context 2048, 30 spoken turns, turns 5–29 (n = 25; token counts depend only on
prompt layout and tokenizer, so they carry over to the phone; times do not):

| | new tokens per turn p50 / p90 / mean | prompt time p50 / p90 (host) | prompt size kept |
|---|---|---|---|
| before (1.5.3 code) | 514 / 522 / 515 | 3.50 s / 3.73 s | 1,246–1,276 tokens |
| after | 239 / 694 / 294 | 1.83 s / 4.43 s | 1,347–1,902 tokens (more history kept) |

The p90 is a re-cut every ~6 turns at this window size; at 4096 the room is larger, so re-cuts are rarer and the
typical turn saves more. Files: `docs/measurements/stage3_3_prompt_cache_{before,after}.json`.

### 3.2 Speech synthesis and generation starve each other (first audio, gaps, tokens/s)

During a spoken reply the model generated with every performance core (5–6 threads on this phone) while Kokoro/Kitten
synthesised with 4 threads, plus VAD/capture/playback. llama.cpp's worker threads wait for each other at every step,
so one preempted thread stalls all of them; the synthesiser in turn gets little CPU until generation ends.

Device evidence: `tts_ms_per_char` p50 67 vs best 5.5; audio usually starts after the last token; `tokens_per_s` down to
3.9. Host reproduction (`docs/measurements/stage3_3_contention_host.json`, 4 vCPU, busy threads standing in for the
synthesiser):

| llama threads (decode) | competing threads | tokens/s | competing work per thread |
|---|---|---|---|
| 4 | 0 | 21.1 | – |
| 4 | 2 | **1.0** | 69 k/s |
| 4, **2 reserved** (1.5.4) | 2 | **12.1** | 96 k/s |

**Fix:** for replies spoken on the device, token-by-token generation leaves 2 cores free (5→3, 6→4 decode threads; never
below 2); the prompt is still read with all threads (speech isn't running yet then). The on-device synthesisers use 2
threads instead of 4 (best measured 5.5 ms per character against ~70 ms of speech per character, so 2 leave a wide
margin). Text chat is unchanged. New JNI call `nativeSetThreads` (`llama_set_n_threads`). No thread count was raised.

### 3.3 Model not ready when the owner speaks

* The model loaded at process start even in background processes (see §2) and the result was frozen loads and memory
  held in the background. Now it loads when a Clu screen becomes visible or voice starts (`LlmService.prepare`), so the
  load overlaps the owner's first sentence.
* The idle-unload check (20 min default) uses the wall clock; after Android froze the process, the first check on
  waking fired immediately and could unload the model the owner had just come back to use. A check arriving far later
  than its 30 s period now skips that round, and becoming visible counts as use.
* Unchanged: memory-pressure release when the app goes to the background and voice is off.

### 3.4 Not changed (no defect found)

Chunking (first phrase after ≈300 ms), the bounded audio queue, AudioTrack sink, barge-in detector thresholds
(180 ms / 10 dB) and the rule that a cancelled turn never resumes. Barge-in scenarios are pinned by tests (sustained
speech caught at 180 ms, echo ignored, short words with gaps don't interrupt, loud non-speech never interrupts, works in
the first frames of playback).

## 4. Tests

New: `VoiceLatencyTest` (history start stable across 45 turns; prompts always fit; short conversations untouched;
background/outcome classification; barge-in scenarios), `LlamaEngineIntegrationTest.reservingThreadsForSpeech…` (real
JNI: identical greedy output with reserved threads and back), opt-in audits `PromptCacheAudit`, `ContentionAudit`
(`-Dclu.llmBenchModel=<gguf>`).

## 5. What still needs the device

All latency effects above. Expected direction on the phone: shorter `turn_first_token_ms` tail in long conversations
(watch `prompt_new_tokens`), shorter `first_tts_to_first_audio_ms` and `max_chunk_gap_ms`, steadier `tokens_per_s`
during spoken replies (possibly slightly lower peak), fewer `turn_model_ready_ms` waits, and no `model_load_ms` values in
the minutes. Risk to watch: if 3/4 decode threads are slower than expected on this SoC, typed chat is unaffected but
spoken replies would generate slower — `tokens_per_s` in the report shows it.
