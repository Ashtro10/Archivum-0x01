# Model and voice-engine compatibility report

Target devices: **Xiaomi Pad 6** (Snapdragon 870: 1×A77 3.2 GHz + 3×A77 2.42 GHz + 4×A55; Adreno 650; 8 GB RAM;
Android 13+/HyperOS) and **iQOO Neo 9 Pro** (Snapdragon 8 Gen 2: 1×X3 + 2×A715 + 2×A710 + 3×A510; Adreno 740;
Android 14+). Both are arm64-v8a. Neither device was available to the build environment: everything below marked
*host* was measured on an x86_64 build container; nothing here is a phone/tablet measurement.

## 1. Primary language model (llama.cpp)

| Item | Choice | Evidence |
|---|---|---|
| Runtime | llama.cpp b11844 (commit 06230cb), static, CPU backend, built for `armv8.2-a+dotprod+fp16` (supported by both SoCs), 16 KB page aligned | Cross-compiled; JNI symbols verified in the APK; same JNI code tested on host with real llama.cpp (11 integration tests + end-to-end agent turn) |
| Format | GGUF v2/v3, any architecture llama.cpp b11844 supports | `GgufReader` validates headers before loading; load failures reported with llama.cpp's log |
| Recommended default (~3B) | **Qwen2.5-3B-Instruct Q4_K_M** (~2.1 GB file) | Fits the Pad 6's 8 GB with 4k context (estimate ≈2.6 GB incl. KV cache); ChatML template built in. *Quality/speed unverified on device.* |
| Alternatives | Llama-3.2-3B-Instruct Q4_K_M (~2.0 GB); Qwen2.5-1.5B-Instruct Q4_K_M (~1.1 GB) as a low-latency fallback | Catalog in the Models screen; downloads only after confirmation with size shown |
| Chat template | From the model file (`llama_chat_apply_template` built-in set: ChatML, Llama 3, Gemma, Phi-3, …), override setting available | Tested: ChatML and Llama 3 formatting |
| Prompt caching | KV-cache prefix reuse between turns | Tested: 2nd turn reuses the shared prefix |
| Tool use | `<tool>{json}</tool>` protocol + deterministic router for explicit commands; optional GBNF grammar support in the engine | Tested with scripted engine and grammar-constrained generation |
| GPU | **Not included.** llama.cpp's OpenCL (Adreno) / Vulkan backends were not built in this prototype | Setting shown as unavailable; never claimed |
| Model download | Hugging Face URLs (reachable from the phone, not from the build environment) | URLs could not be verified from the build environment |

Replacement: models are files registered in `models`; switching the active model reloads the engine (tested: a
different model produces different greedy output).

## 2. Offline voice stack (sherpa-onnx 1.13.8, ONNX Runtime)

| Stage | Engine / model | Size | Packaging | Host verification |
|---|---|---|---|---|
| Voice activity | Silero VAD | 0.6 MB | bundled | 2/2 utterances segmented correctly (`voice_pipeline_host.json`) |
| Wake word | sherpa-onnx KWS zipformer gigaspeech 3.3M (int8) | 5.3 MB | bundled | Detected "CLUE", "HEY CLUE", "BUDDY" at threshold 0.1; no false trigger on "Please open the window" / "The glue is drying" |
| Speech recognition | streaming zipformer en 20M (int8) | 43.6 MB | bundled | WER 0.33–0.35 on synthetic sentences; the model loses the first ~0.5 s without context → streams are primed with 1 s of silence (verified to restore the first words on real recordings) |
| Speech synthesis (Clu voice) | Piper `en_US-ryan-medium` (VITS) via sherpa-onnx | 67 MB download | consented download / file import, SHA-256 verified | RTF 0.18 on host (5.2 s audio in 0.94 s); first sentence 167 ms |
| Alternative voice | Piper `en_GB-alan-medium` | 67 MB | same | — |
| Speaker recognition | 3D-Speaker CAM++ (VoxCeleb) | 29.6 MB download | same | same voice 0.97 vs different voice 0.11 (Alan) / 0.89 (Joe – another synthetic US male); **not validated on real human voices** |
| Android alternatives | `TextToSpeech` (rendered to PCM, same player), `SpeechRecognizer` (on-device or default) | — | system | Availability depends on installed services; on-device recognizer reported honestly if missing |

Voice identity: one masculine Piper voice across devices (same model file on both); settings for speed, pitch
(AudioTrack PlaybackParams, independent of speed), volume and a "metallic" effect (ring modulation + comb filter,
tested to change the waveform) give an Ultron-inspired, calm, synthetic timbre. No attempt is made to imitate a
copyrighted character's voice.

Clap detection, noise-floor estimation, sentence chunking and turn-taking are implemented in pure Kotlin and tested
with synthetic audio.

## 3. LiveKit evaluation

**What LiveKit is:** an open-source WebRTC media server (`livekit-server`, Go), client SDKs (including Android), and
the *Agents* framework (Python/Node) that orchestrates STT → LLM → TTS with turn detection, usually via cloud or
desktop-server plugins.

**What it does not provide:** LiveKit ships no offline language model, speech recogniser or synthesiser for Android.
Its Agents framework runs on a server (Python/Node), not inside an Android app. An "offline LiveKit" setup means
self-hosting the media server *and* an agent worker with local models on another machine, i.e. a permanently
running server, which the requirements exclude.

**Decision:** LiveKit is **not used** in the offline path. On a single device, audio never needs to leave the
process, so a WebRTC hop would only add latency and moving parts. Clu implements the same pipeline stages
in-process: VAD and end-of-turn detection, streaming ASR, incremental sentence-level TTS, barge-in, latency
accounting. (Practical note: LiveKit's Android SDK also depends on Google Maven/AndroidX artifacts that were
unreachable from this build environment.) No LiveKit Cloud or other hosted media service is involved.

**Possible future use:** if a laptop or tablet later hosts a LiveKit server, it could stream the phone's microphone
to the home device's larger model in real time. The engine interfaces (`LlmEngine`, `TtsBackend`, ASR) keep that
option open without changing Clu's identity or data model.

## 4. Optional external services (never required)

* Web research: DuckDuckGo instant answers / HTML results, Wikipedia API (no key).
* Maps: OpenStreetMap Nominatim (geocoding) + OSRM demo server (road distance).
* Trains: no free official Indian Railways API; Clu searches the web and labels results unofficial, pointing to
  NTES/IRCTC.
* External AI: any OpenAI-compatible endpoint you configure (off by default; receives only your words unless memory
  sharing is enabled).
All of these are subject to the internet settings and are tested against fake HTTP responses (no live calls were
possible from the build environment).
