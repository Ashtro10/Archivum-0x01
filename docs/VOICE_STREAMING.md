# Streaming, interruptible voice conversation

Status: implemented, host-tested. **Not yet measured on a phone**. Every number below is labelled with where it came from.

## 1. Flow

```
mic (30 ms frames, VOICE_COMMUNICATION + AEC/NS)
 ├─ Silero VAD ─ end of speech (configurable pause, default 650 ms)
 ├─ built-in streaming recogniser → live partial text (English)
 └─ utterance audio ──► Whisper (whisper.cpp, own worker thread) ──► final transcript (English / Hindi / mixed)
                                                            │
llama.cpp token stream ◄────────────────────────────────────┘
 └─ Response.text(delta) ─► AdaptiveChunker ─► text queue ─► synthesis worker (clu-tts) ─► audio queue (2 chunks)
                                                                                   └─► playback worker (clu-audio)
                                                                                        └─► AudioTrackSink (one AudioTrack, gapless)
```

Generation, synthesis and playback run at the same time: the first phrase is spoken while the model is still
generating. Nothing waits for the complete reply, and nothing splits a finished reply after the fact.

| Piece | File |
|---|---|
| Adaptive chunking | `core/.../voice/AdaptiveChunker.kt` |
| Concurrent pipeline, response IDs, timings, barge-in detector | `core/.../voice/StreamingSpeech.kt` |
| Gapless output, stale-ID guard, stall guard | `app/.../voice/AudioTrackSink.kt` |
| TTS adapter (sherpa / Android TTS, Hindi routing, audio focus, announcements) | `app/.../voice/SpeechOutput.kt` |
| Call-audio mode, headset/Bluetooth routing | `app/.../voice/CallAudio.kt` |
| Voice loop: barge-in, Whisper hand-off, metrics | `app/.../voice/VoiceSession.kt` |
| whisper.cpp JNI | `native/jni/clu_whisper_jni.cpp`, `native/whisper/CMakeLists.txt`, `core/.../stt/Whisper.kt` |
| Model download, verification, worker | `app/.../voice/WhisperStt.kt` |

## 2. Chunking

- **First chunk:** spoken as soon as one of these happens:
  - a sentence end after 2 or more words;
  - a clause boundary (comma or dash) after 3 or more words;
  - 8 words without either. Then it cuts at the latest clause boundary, or at a word boundary that is not a dangling "the"/"to"/"के"…
- **Later chunks:** cut at sentence ends. Clause boundaries are used once a chunk passes 60 characters, and a chunk is force-cut at a word boundary past 180 characters.
- **Never split:**
  - Words: a boundary only counts once the next whitespace has arrived.
  - Decimals ("3.5"), abbreviations ("Dr.", "e.g.") and initials.
  - Code blocks are not spoken.
- **Stall flush:** if the model pauses for 350 ms (for example during a tool call), the complete words pending so far are spoken.
- **Underrun flush:** when the queued audio will end within 400 ms and nothing is being synthesised, pending complete words (3 or more) are spoken early. When the model is slower than speech, this turns long silences into short phrases. Measured: maximum gap 91 ms with the model at about 4 words/s.
- **Language routing:** each chunk's script is detected. Mostly-Devanagari chunks go to the Hindi voice; the rest go to the English voice.

## 3. Concurrency and cancellation

- **Two workers:**
  - Synthesis runs on a single worker; the TTS engines are not thread-safe.
  - Playback runs on a high-priority worker.
- **Queues:**
  - The text queue is unbounded, but holds only tiny strings.
  - The audio queue is bounded at 2 chunks, so synthesis stays at most about one chunk ahead of playback. This bounds latency and keeps TTS from competing with the LLM for CPU.
- **Response IDs:** every reply gets a new ID.
  - `StreamingSpeech.start()` cancels the previous response.
  - `AudioTrackSink` rejects any chunk whose ID is not the current one. The ID check and the write happen under one lock that `stop()` also takes, so a worker that wakes up late can never play into the next reply.
- **`Response.cancel()` order:**
  1. `sink.stop()`: pause + flush, so silence comes first.
  2. Stop the producer (`core.agent.cancel()`).
  3. Drop the text and audio queues.
  4. Cancel the workers.
- **Synthesis in progress during cancel:** a synthesis call that is already running finishes in its native code, but its audio is discarded (tested).
- **Stall guard:** if the device stops accepting audio for 1.5 s (for example, the output device is lost), the chunk is abandoned instead of blocking forever.

## 4. Barge-in

- **No wake word needed.** While Clu speaks, every 30 ms microphone frame goes through `BargeInDetector`:
  - It learns the echo floor: the microphone level during Clu's speech when the VAD reports no speech.
  - It interrupts after 180 ms of continuous VAD speech that is at least 6 dB (call audio) or 10 dB (media audio) above that floor.
- **On interrupt:**
  - The reply is silenced, and TTS, queued audio and the LLM are cancelled.
  - A new utterance starts, using 0.6 s of pre-roll so the first word is not lost.
- **Echo protection:**
  - **Call audio (default):** `MODE_IN_COMMUNICATION` plus `USAGE_VOICE_COMMUNICATION` give the platform echo canceller Clu's output as its reference.
  - **Routing:** a wired, USB or Bluetooth headset is used if connected, otherwise the loudspeaker. Routing follows devices being plugged in or removed.
  - **Detector floor:** the echo-floor detector handles what the echo canceller leaves behind.
  - **Media audio** (`USAGE_ASSISTANT`, louder) is available in settings, with a larger margin.
- **Audio focus:** transient focus with ducking. If a call or another app takes the audio, Clu stops speaking.

## 5. TTS engine: verified result

**HayaiTTS** ([github.com/AhmedMohamed1a/HayaiTTS](https://github.com/AhmedMohamed1a/HayaiTTS)):
- A GPL-3.0 Android **app** that registers as a system TTS engine. It is built on sherpa-onnx 1.13.2 and its catalogue holds 632 voices from the sherpa-onnx tts-models releases.
- It is **not an embeddable library**, and its README says its TTS service "still buffers".
- Clu has no licence that allows copying GPL code in.

What Clu uses:
- **Directly:** the same voice models HayaiTTS uses, run through Clu's own sherpa-onnx runtime. This gives sentence-level streaming in-process with no IPC.
- **As a system engine:** HayaiTTS still works through "Android TTS". That path is chunk-level only, with one IPC call plus a file round trip per chunk.

Host evaluation (x86, 4 threads, sherpa-onnx 1.13.8; `scripts/eval_tts.py`, data in `docs/measurements/tts_eval_host.json`):

| Voice | First audio, short phrase | RTF | Median F0 | Verdict |
|---|---|---|---|---|
| Piper en_US Ryan (current Clu voice) | 91 ms | 0.061 | 161 Hz | ✓ English. Fast. Deepened at playback (pitch 0.88) with the metallic effect. |
| Piper hi_IN **Pratham** (new optional pack) | 100 ms | 0.049 | 103 Hz | ✓ Hindi, deep male. Best Hindi intelligibility in the ASR round trip. **CC BY-NC-SA 4.0 data (non-commercial).** |
| Piper hi_IN Rohan | 119 ms | 0.056 | 141 Hz | Hindi alternative |
| Kitten nano/mini v0.8 | 0.6–1.9 s | 0.27–0.63 | ≥118 Hz | ✗ Too slow for the first phrase; English only. Not adopted. |
| Supertonic-3 (deep male IDs 6/9) | 0.5–0.8 s | 0.15–0.2 | 88–101 Hz | Deepest voice, but delivers everything in one callback (no sentence streaming) and is 129 MB. Not adopted as default. |

**Selected:**
- **English:** Piper Ryan (existing pack).
- **Hindi:** Piper Pratham (new optional download, 67 MB, SHA-256 pinned).
- **Mixed replies:** each chunk is routed by script.

Limitations:
- Romanized Hinglish, i.e. Hindi written in Latin letters, is unintelligible in every voice tested. Replies need Devanagari for Hindi.
- Pronunciation was judged by a Whisper round trip on synthetic audio, which is a proxy. **The owner should listen to and judge the voices on the device.**

## 6. Whisper.cpp (selected STT)

- **Source:**
  - Submodule `native/whisper.cpp` at commit 231638a0 (MIT licence).
  - Built as its own `libclu_whisper.so` (1.6 MB arm64). ggml is linked statically and only 7 JNI symbols are exported, so its ggml copy never clashes with llama.cpp's.
- **Models** (official, SHA-1 from `models/README.md`): `ggml-base.bin` (142 MiB) and `ggml-small.bin` (466 MiB).
  - Downloaded only after a confirmation that shows the size, free storage and memory needed.
  - Downloads resume if interrupted, and the SHA-1 is verified before use.
  - Import from a file is supported, with the same check.
  - Until a model is installed, the built-in English recogniser is used, and the Voice screen says so.
- **Streaming:** Whisper is not a streaming model.
  - The VAD segments speech, and Whisper transcribes each complete utterance on its own worker while microphone capture continues.
  - The built-in streaming recogniser keeps showing live partial text meanwhile, and its text is the fallback if Whisper fails.
- **Speed settings:**
  - The encoder window is shrunk to the utterance length ("fast audio context"). If that makes the decoder loop, the utterance is detected as repetitive and retried with the full window.
  - The token budget is bounded, and temperature-fallback re-decoding is off.
- **Cancellation:** the abort flag stops decoding at the next token (measured: 13–19 ms). whisper.cpp cannot interrupt an encoder pass that is already running; its result is discarded.
- **Language setting:** Detect (default), English or Hindi.

Host measurements:
- **Conditions:** whisper.cpp through Clu's JNI on x86, 4 threads, fast audio context, no fallback; `docs/measurements/whisper_cpp_host.json`.
- **Weights:** rebuilt from the fp32 sherpa-onnx export with `scripts/onnx_whisper_to_ggml.py`, because huggingface.co is blocked in the build container. They have the same OpenAI weights and the same file size as the official files, but a different SHA-1. **These are not phone numbers.**

| Clip | base: ms (CER) | small: ms (CER) |
|---|---|---|
| English command, 2.9 s (synthetic) | 159 (0.00) | 564 (0.00) |
| English human speech, 7.1 s | 396 (0.00) | 1506 (0.00) |
| Hindi command, 3.0 s (synthetic) | 250 (0.84: Urdu script) | 1155 (0.19) |
| Hindi question, 2.4 s (synthetic) | 184 (1.24) | 853 (0.40) |
| Mixed Hindi + "meeting", 3.3 s (synthetic), language = hi | 328 (0.84) | 1147 (0.35) |
| Same, language = auto | 464 | 1639 |

Conclusions:
- `base` handles English but **not Hindi**.
- `small` is the minimum for Hindi. Its accuracy on clean synthetic Hindi is only moderate, and real speech will be worse.
- Detect costs about 0.5 s; fix the language when possible.
- The Hindi test clips are synthetic because no licensed real Hindi recordings were available. **Device testing with the owner's voice is required.**

## 7. Latency instrumentation

Each voice reply records `TurnTimings` and writes them to Diagnostics → Performance, which shows p50/p90/p95/min/max per metric:

- `speech_end_to_transcript_ms`
- `transcript_to_first_token_ms`
- `first_token_to_first_tts_ms`
- `first_tts_to_first_audio_ms`
- `speech_end_to_first_audio_ms`
- `endpoint_to_first_audio_ms`
- `interrupt_to_silence_ms`
- `cancel_complete_ms`
- `max_chunk_gap_ms`
- `audio_started_before_generation_end_ms`
- `barge_in_detect_ms`
- `whisper_ms`, `whisper_encode_ms`, `whisper_load_ms`
- `tts_errors`

**Speech end** means the VAD endpoint minus the configured end-of-speech pause, which approximates when the owner actually stopped talking.

### Budget against the targets (honest)

**< 700 ms speech end → first audio:** not reachable with Whisper as configured. The pause plus Whisper alone exceed it. Estimated budget:

| Stage | Estimate | Source |
|---|---|---|
| End-of-speech pause | 650 ms (setting; minimum 250) | — |
| Whisper | ~0.2–0.4 s (base, English) or ~0.6–1.6 s (small, Hindi) | host |
| LLM first token | not measured here | device dependent |
| First phrase (~3 words at LLM speed) | — | — |
| TTS first chunk | ~0.1 s | host Piper |
| Output latency | ~20–40 ms | device |

The built-in streaming recogniser (English) finalises within about 0.1 s; its `asr_finalize_ms` is in Diagnostics. Whisper was kept as the selected engine, as instructed. Changing it is the owner's decision.

**< 100 ms interrupt detection → silence:** the pipeline part is measured: `sink.stop()` returns within 1 ms (host benchmark, p99 1 ms). On a device, add the AudioTrack pause/flush output latency.
- Detecting speech itself needs at least 180 ms of voice plus VAD latency, to avoid false triggers. That is a deliberate trade-off.

**Gaps:**
- Simulated pipeline: no starvation gaps when the model is faster than speech.
- Simulated pipeline: maximum gap 91 ms when the model produces about 4 words/s.
- No per-chunk device restart any more (the old player drained and restarted the track for every sentence).

Host pipeline benchmark (`docs/measurements/streaming_pipeline_host.json`; simulated model, TTS and real-time sink, 30 runs, half interrupted at random points):

| Metric | Value |
|---|---|
| Stale-audio incidents | 0 |
| Interrupt → silence (`stop` call) | p50 0 ms, p99 1 ms |
| Cancellation complete | p99 4 ms |
| Pipeline overhead (first TTS → first audio, beyond the 40 ms simulated synthesis) | about 1–6 ms |

## 8. Tests

| # | Scenario | Host test | Device test |
|---|---|---|---|
| 1 | English short | WhisperIntegrationTest (en_cmd, en_real_short) | DEVICE_ACCEPTANCE S1 |
| 2 | Hindi short | WhisperIntegrationTest (hi_cmd, hi_question; synthetic) + script routing (StreamingSpeechTest.hindiAndMixed…) | S2 |
| 3 | Mixed | WhisperIntegrationTest (mixed_cmd), chunk routing test | S3 |
| 4 | Long answer starts before generation ends | StreamingSpeechTest.longAnswerStartsSpeaking… (speaks more than 3 s before the model finishes) | S4 |
| 5 | Interrupt during the first chunk | StreamingSpeechTest.interruptionDuringFirstChunk | S5 |
| 6 | Interrupt during later chunks | …interruptionDuringLaterChunk | S6 |
| 7 | Repeated interruptions, rapid follow-ups | …repeatedInterruptionsAndRapidFollowUps (6 interruptions, only the latest reply audible) | S7 |
| 8 | Cancel during TTS | …cancellationWhileSynthesising | S8 |
| 9 | TTS/STT errors and recovery | …ttsErrorSkipsChunkAndPipelineRecovers; Whisper failure falls back to streaming text; AudioOutputTest (stall guard) | S9 |
| 10 | Offline with networking disabled | `scripts/test_offline.sh` runs the pipeline and Whisper tests in a network namespace (all pass) | S10 (airplane mode) |

Other tests:
- **Barge-in echo rejection:** StreamingSpeechTest.bargeInIgnoresEcho….
- **Gaps when the model is slower than speech:** …modelSlowerThanSpeech….
- **Stale-ID guard on the Android API (Robolectric):** AudioOutputTest.

## 9. Reproduce

```
scripts/build_native.sh android && scripts/build_native.sh host
# Whisper measurements (needs ggml models; official ones from huggingface.co, or rebuilt for host tests):
python3 scripts/onnx_whisper_to_ggml.py <whisper-small/small-encoder.onnx> <small-decoder.onnx> <openai/whisper assets> ggml-small.bin
python3 scripts/make_stt_fixtures.py <models dir> <fixtures dir>
./gradlew :core:test --tests '*Whisper*' -Dclu.whisperModels=<dir> -Dclu.sttFixtures=<fixtures dir>
./gradlew :core:test --tests '*StreamingSpeechTest*' -Dclu.bench=1
scripts/test_offline.sh <python with sherpa-onnx> <models dir> <whisper dir> <fixtures dir>
```
