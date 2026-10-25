# TTS engines and Clu's voice

**Status:** implemented and host-tested. **Not verified on a physical Android device yet.** Every number here is labelled host (x86 build machine) or device.

## 1. Why Piper stayed active (root cause)

Before this change, `SpeechOutput.backend()` selected the engine like this:

```kotlin
if (cfg.ttsEngine == "sherpa" && engines.tts() != null) return Sherpa(Piper)
return AndroidTtsBackend(...)   // TextToSpeech(context) – the device DEFAULT engine
```

Five problems followed from it:

1. **The default was Piper.** The default setting was `"sherpa"`, and Piper was the only model type that engine could load. HayaiTTS was never wired in at all.
2. **"Android TTS" ignored HayaiTTS.** That option created `TextToSpeech(context)` without an engine package, so it used whatever the system default was. HayaiTTS was used only if it happened to be that default.
3. **The voice was not chosen by you.** `VoicePackManager.installedTts()` returned the *first installed* pack (Ryan, then Alan). No setting controlled the voice.
4. **Silent fallback.** If the Piper pack was missing, the code switched to the system default engine without telling anyone.
5. **The "deep" voice came from playback pitch.** Ryan measures about 156 Hz, which is not deep. The 0.88 playback pitch was carrying the depth.

## 2. Engines and their runtime paths

The selected engine is used as-is. If it cannot speak, Clu stays silent and tells you why on three surfaces:

- the Voice screen;
- a toast message;
- Diagnostics.

It never switches to another engine.

| Setting `voice.ttsEngine` | Runtime path | Languages | Installation |
|---|---|---|---|
| `piper` (default) | `TtsSelection.plan` → `SpeechOutput.backendFor` → `SherpaBackend` → `SherpaEngines.tts(packId)` → sherpa-onnx `OfflineTts` (VITS), in-process | English (chosen voice); Hindi through the Pratham pack | Voice pack download (67–80 MB, SHA-256 pinned) or file import |
| `kitten` | Same path with `OfflineTtsKittenModelConfig` (KittenTTS nano v0.8 int8), in-process, 4 threads | **English only** | `tts-kitten-nano` pack, 31 MB |
| `hayai` | `SystemTtsBackend("dev.ahmedmohamed.hayaitts")` → `TextToSpeech(context, listener, package)` → `synthesizeToFile`; the PCM is captured from `onBeginSynthesis`/`onAudioAvailable` and played through Clu's own sink | Whatever voices are installed inside HayaiTTS (it offers English and Hindi voices) | The separate HayaiTTS app, plus voices downloaded inside it |
| `system` | `SystemTtsBackend(voice.systemEngine or the device default)`, same API | Depends on the engine | Any installed Android TTS engine |

Hindi phrases (mostly Devanagari) follow `voice.hindiVoice`:

- `pratham`: Piper hi_IN Pratham.
- `engine`: the selected engine's Hindi voice. Only HayaiTTS and system engines have one; Kitten and Piper do not.
- `off`: Hindi text is not spoken, and the Voice screen says so.

The Voice screen shows:

- the selected engine;
- the English and Hindi voices;
- the languages being spoken right now;
- the last voice that actually spoke;
- any problem, with what to install or choose.

The selection is a normal persisted setting, so it survives restarts. Old ids (`sherpa`, `android`) are rewritten once at startup.

## 3. HayaiTTS: selectable as an Android system engine, verified from source

**Source:** [github.com/HayaiApp/HayaiTTS](https://github.com/HayaiApp/HayaiTTS), commit 7d51b0f, GPL-3.0, package `dev.ahmedmohamed.hayaitts`. My earlier docs pointed to a wrong URL; this is the official repository.

What the source shows:

- **Exported service:** it exports a `TextToSpeechService` (`.tts.HayaiTtsService`, intent `android.intent.action.TTS_SERVICE`). That is the standard Android interface, so Clu binds it by package through the public `TextToSpeech` API. No HayaiTTS code is copied, so there is no GPL issue.
- **Synthesis:** `onSynthesizeText` synthesises the **whole request**, then streams the PCM through `SynthesisCallback.audioAvailable`. Its README also says streaming is "still being refined".
  - So HayaiTTS gives **phrase-level** streaming only.
  - Clu sends it short phrases, so first-audio time equals that phrase's synthesis time plus IPC.
- **Voices:** `onGetVoices` lists the voices installed inside HayaiTTS, named `voiceId` or `voiceId#speakerId#name`.
  - Clu's "Find deepest voice" synthesises a test reply with each one (up to 40) and measures its median pitch on the device.
- **Unverified on a device:** the service declares `android:permission="android.permission.BIND_TEXT_SERVICE"`. On Android 11+ the system TTS manager performs the bind on the app's behalf, so a third-party app should be able to use it. This has **not been verified on a device yet**. If binding fails, Clu shows "HayaiTTS failed to start" and stays silent; it does not use another engine.
- **Verdict:** genuinely integrated **as a selectable Android system engine**. It is not embedded and not a library.

## 4. KittenTTS: genuinely integrated, slow first phrase

**What was checked:**
- KittenTTS v0.8 runs in sherpa-onnx 1.13.8. That is the same native runtime already in the APK (`libsherpa-onnx-jni.so` plus `libonnxruntime.so`), so no new native dependencies are needed.
- The vendored Kotlin API has `OfflineTtsKittenModelConfig`.
- **Model:** nano int8 is 31 MB (archive SHA-256 `6fa5be85…2af2fd`). The code is Apache-2.0.
- It has 8 speakers. Only `expr-voice-3-m` (id 2) is in the deep male range.

| | Host measurement |
|---|---|
| First short phrase (4 threads) | p50 591 ms, p90 784 ms (Piper Joe: 81/119 ms) |
| Real-time factor | ~0.30, so faster than real time and streaming stays gapless |
| Median pitch, `expr-voice-3-m` | 116 Hz |
| Median pitch, the other "-m" speakers | 167–190 Hz (not deep) |

**Verdict:**
- Selectable and operational in the app, but English only.
- About 0.5 s slower to first audio than Piper on the host; on a phone it is likely 0.6–1.5 s. Measure it with Voice → Measure voice.
- **Not verified on a device.**
- Piper was not removed; Kitten is an alternative.

## 5. Deep male voice: how the voices were chosen

- **Method:** `scripts/scan_voices.py` synthesises three representative Clu replies with every candidate voice and speaker. Multi-speaker models (VCTK, LibriTTS-R with 904 speakers, Arctic, Semaine) were screened first.
- **Measurements:**
  - median F0 using **YIN** (checked against librosa pYIN: medians agree within 1–10 Hz on male and female voices, with no octave errors);
  - pitch range in semitones (a small range means monotone);
  - first-phrase latency and real-time factor;
  - a whisper.cpp round trip for intelligibility (word error rate, WER).
- **Data:** `docs/measurements/voice_scan_en.json`, `voice_scan_hi.json` and `tts_first_phrase_host.json`.
- **Reference:** adult male speech has a median of roughly 85–155 Hz.

| Voice | Median F0 | Range | WER | First phrase (host) | Decision |
|---|---|---|---|---|---|
| **Piper Joe (en_US)** | **90 Hz** | 5.4 st | 0.019 | 81 ms | **English default.** Deepest natural voice and clearest; CC0 data. |
| Piper Arctic rms | 91 Hz | 4.8 st | 0.019 | 100 ms | Alternative (steady) |
| Piper Alan (en_GB) | 96 Hz | 4.2 st | 0.019 | — | Alternative (flatter) |
| Piper Northern English male | 100 Hz | 7.0 st | 0.019 | — | Alternative |
| VCTK p326 / p237 / p254 | 82 Hz | 2.4–2.9 st | 0.06–0.10 | — | **Rejected:** monotone and less intelligible |
| KittenTTS `expr-voice-3-m` | 116 Hz | 6.7 st | 0.019 | 591 ms | Kitten default speaker |
| Piper Ryan (previous default) | 156 Hz | 9.7 st | 0.019 | 64 ms | Kept for compatibility; labelled "not deep" |
| **Piper Pratham (hi_IN)** | **107 Hz** | 6.2 st | 0.20 | 95 ms | **Hindi default** |
| Piper Rohan (hi_IN) | 158 Hz | 8.9 st | 0.25 | — | Not deep |

**Pitch control:**
- The default is now **1.0** (the natural voice). The allowed range was narrowed to 0.8–1.2.
- Depth comes from the voice model itself, not from playback pitch shifting.
- Speed stays at 0.92 (calm).

**Evidence limits:**
- Pitch and intelligibility are measured, but "natural, calm, authoritative" can only be judged by listening. Samples of Joe, Arctic rms, Kitten voice 3 m, Pratham and Ryan were provided.
- English and Hindi are different models, so they sound like different speakers.

## 6. Streaming and barge-in (unchanged)

- **Pipeline:** the concurrent streaming pipeline (adaptive phrases, bounded audio queue, gapless sink, response-id guard) is engine-independent. Every engine speaks through it.
- **Barge-in:** it now also calls `TextToSpeech.stop()` on system engines, so HayaiTTS stops working on an abandoned phrase.
- **Gaps:** phrases are synthesised ahead while one plays. Gaps appear only if synthesis is slower than real time; the measured real-time factor was below 0.31 for every selected voice on the host.

**Device benchmarks** (run them on the phone; results go to Diagnostics → Performance with p50/p90/p95):
- **Voice → Measure voice:** first-phrase latency, real-time factor and median pitch of the selected English and Hindi voices.
- **Voice → Streaming test:** plays a long reply fed word by word at 12 words/s, interrupts it after 4 s, and records `first_tts_to_first_audio_ms`, `max_chunk_gap_ms`, `interrupt_to_silence_ms` and `cancel_complete_ms`.

## 7. Tests

| Test | Kind | What it checks |
|---|---|---|
| `TtsSelectionTest` | host | no engine is substituted; HayaiTTS binds its own package; Piper needs the chosen voice; Kitten is English-only and Hindi routing is explicit; legacy ids; YIN pitch on 90/110/220 Hz signals at two sample rates |
| `TtsEngineSelectionTest` | Robolectric (Android framework on the JVM) | the real `SpeechOutput` with a fake device: HayaiTTS missing → reported, selection unchanged, no backend; HayaiTTS installed (registered TTS service) → `SystemTtsBackend` bound to `dev.ahmedmohamed.hayaitts` |
| `VoiceSettingsTest` | host | the new settings persist and validate |
| `StreamingSpeechTest`, `AudioOutputTest` | host / Robolectric | streaming, interruption and stale-audio guard (unchanged, still passing) |

**Not tested:** actual audio from HayaiTTS and Kitten on a phone. Run the device checklist in DEVICE_ACCEPTANCE.md, section T.
