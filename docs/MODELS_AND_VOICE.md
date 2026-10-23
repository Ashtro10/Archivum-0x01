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
