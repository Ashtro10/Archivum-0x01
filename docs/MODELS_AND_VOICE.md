# Models, voice packs and offline operation

## Language model (required for conversation)

**Option A — download in the app (needs internet once):** ⋮ → AI models → *Download* on a catalog entry. Clu shows
the size and asks first; Android's download manager resumes interrupted downloads. When finished the model is
registered automatically and becomes active if none was.

**Option B — import a file (fully offline):**
1. Get a GGUF file on any device, e.g. `qwen2.5-3b-instruct-q4_k_m.gguf` from
   `https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF` (Files tab).
2. Put it on the phone/tablet (USB, Files, Nearby Share, or the other device).
3. ⋮ → AI models → **Import GGUF file…** → pick it. Clu copies it into its storage, reads the GGUF header
   (architecture, quantisation, trained context) and shows compatibility before loading.
4. Tap **Use this model** (if not already active). Status becomes *ready* after loading; *Benchmark* measures
   tokens/s on your device.

Guidance (estimates, verify with *Benchmark*): 3B Q4_K_M ≈ 2.1 GB file, ≈2.6 GB RAM at 4k context; 1.5B ≈ 1.1 GB.
On low memory, Clu refuses to load and tells you how much is needed. Settings → AI model: context length,
max reply tokens, sampling, CPU threads (auto = performance cores), unload when idle, thermal protection.
If something misbehaves: AI models → *Restore known-good configuration*.

## Voice

Built in (no download): speech recognition, wake word, voice activity detection.

Optional packs (⋮ → Voice):
| Pack | Size | Purpose |
|---|---|---|
| Clu voice – Ryan (Piper medium, US male) | 67 MB | Clu's consistent voice on both devices |
| Alan (Piper medium, British male) | 67 MB | Alternative voice |
| Speaker recognition (CAM++) | 30 MB | Recognise the owner and registered people |

Each pack can be downloaded (SHA-256 verified) or **imported offline**: download the exact file named on the Voice
screen (from `https://github.com/k2-fsa/sherpa-onnx/releases`) on any device and use *Import file…*.

Until the Clu voice is installed, Android's own TTS is used (choose a male voice under *Android voices*).

## Enabling fully offline operation

1. Import/download a model and the Clu voice pack once.
2. Optional: Settings → Internet & external services → *Allow internet access* OFF. Clu then never uses the network;
   requests that need live information are answered with an honest "unavailable" (or from local knowledge, marked
   as possibly outdated, if you choose that under *When offline*).
3. Phone↔tablet sync keeps working over the local hotspot without internet.

## Added for streaming voice

| Model | Size | Source / verification | Licence | Use |
|---|---|---|---|---|
| Whisper small (`ggml-small.bin`) | 466 MiB | huggingface.co/ggerganov/whisper.cpp, SHA-1 `55356645c2b361a969dfd0ef2c5a50d530afd8d5` | MIT (OpenAI weights) | Default STT: English, Hindi, mixed |
| Whisper base (`ggml-base.bin`) | 142 MiB | same, SHA-1 `465707469ff3a37a2b9b8d8f89f2f99de7299dac` | MIT | Faster STT, English only in practice |
| Hindi voice – Pratham (Piper hi_IN medium) | 67 MB | sherpa-onnx tts-models release, SHA-256 `2084d321…5648` | Data: CC BY-NC-SA 4.0 | Speaks Devanagari chunks |

All are optional downloads that start only after a confirmation showing the size and free storage, or they can be imported from a file. Nothing is downloaded automatically. Whisper runtime: whisper.cpp 231638a0 (MIT), in `libclu_whisper.so`.

## TTS voice packs (deep-voice update)

| Pack | Size | SHA-256 (archive) | Licence (data) | Median pitch (host) |
|---|---|---|---|---|
| Piper en_US Joe (default English) | 67.2 MB | `261b903a…22d9bd3` | CC0 | ~90 Hz |
| Piper en_US Arctic, speaker rms | 80.3 MB | `caf83305…d4bf25c` | CMU Arctic (permissive) | ~91 Hz |
| Piper en_GB Northern English male | 67.2 MB | `2bb2c1e7…f12c55` | CC-BY-SA 4.0 | ~100 Hz |
| Piper en_GB Alan | 67.2 MB | `a48d4017…ef7c9012` | see model card | ~96 Hz |
| Piper en_US Ryan (previous default) | 67.2 MB | `c546af78…ad7505` | see model card | ~156 Hz |
| KittenTTS nano v0.8 int8 | 31.2 MB | `6fa5be85…3af2fd` | Apache-2.0 | ~116 Hz (expr-voice-3-m) |
| Piper hi_IN Pratham (Hindi) | 67.2 MB | `2084d321…5648` | CC BY-NC-SA 4.0 | ~107 Hz |

## Built-in live recogniser (repair build)
Replaced `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` (dropped utterance onsets; 1/27 "Apple") with `sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06` (archive SHA-256 `c8676e5f…ade6834`, 71 MB in the APK, licence: huggingface.co/Banafo/Kroko-ASR). 13.2% vs 63.7% WER on Clu's test set; see SPEECH_RECOGNITION.md.

