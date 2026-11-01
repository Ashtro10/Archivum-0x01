# Voice models

All speech models run on the PC through sherpa-onnx 1.13.8 (ONNX Runtime, CPU). The phone uses the PC's models when it
talks to CLU on the PC, so the selected voice is the same everywhere.

## Listening

| Model | Role | Size | Licence | Notes |
|---|---|---|---|---|
| Silero VAD | voice activity (start/end of speech, barge-in) | 0.6 MB | MIT | 10 ms frames at 16 kHz |
| Kroko streaming English (Banafo, 2025-08) | live English recognition while you speak | 68 MB | see note | Best of four streaming models on CLU's 629-clip test set (13.2 % WER vs 30–64 %, 27/27 on "Apple"; `docs/SPEECH_RECOGNITION.md`). Needs ~1 s of priming silence + 1 s pre-roll, and 1.5 s of tail when finishing |
| Whisper base (OpenAI, sherpa-onnx export) — **optional installer component** | Hindi, German, French, Spanish, automatic language | 154 MB | MIT | Transcribes after the turn ends; selected in Settings → Voice → Language |

**Kroko licence note.** The model card points to huggingface.co/Banafo/Kroko-ASR; third-party summaries call the
community models CC-BY-SA, other wording mentions licence keys for commercial use. Fine for a personal assistant; check
before any commercial distribution.

**Wake words** use the same streaming recogniser plus CLU's fuzzy phrase matcher (`WakePhraseMatcher`). A separate
keyword-spotting model was tried and rejected: it missed isolated words such as "Clue" in testing. Costs nothing extra
while CLU is already listening; in wake-word mode, recognised text is discarded unless it starts with a wake word.

## Speaking

Voices installed with CLU (the default is **Joe**: masculine, deep, calm — change it any time):

| Voice | Engine | Accent | Measured median pitch | Size | Licence |
|---|---|---|---|---|---|
| **Joe** (default) | Piper VITS medium | US | ≈ 96 Hz | 61 MB | Piper MIT; dataset CC0 (NabuCasa) |
| Alan | Piper VITS medium | British | ≈ 102 Hz | 61 MB | Piper MIT; dataset per MODEL_CARD |
| Ryan | Piper VITS medium | US | ≈ 184 Hz | 61 MB | Piper MIT; dataset per MODEL_CARD |
| Kitten 1–8 | KittenTTS nano v0.8 int8 (8 speakers) | US | ≈ 190–208 Hz (speakers 1, 4 measured) | 27 MB | Apache 2.0 |

Downloadable from Settings → Voice (source: k2-fsa/sherpa-onnx `tts-models` GitHub release, pinned URL and SHA-256;
size, source and licence are shown before confirming):

| Voice | Engine | Download | Licence |
|---|---|---|---|
| Northern | Piper VITS (Northern English male) | 64 MB | Piper MIT; dataset per model card |
| Arctic (rms) | Piper VITS, 18-speaker Arctic model, speaker "rms" | 77 MB | Piper MIT; CMU Arctic data |
| Lewis | Kokoro-82M v0.19 (11 speakers) | 305 MB | Apache 2.0 |
| Pratham | Piper VITS, Hindi | 64 MB | training data **CC BY-NC-SA 4.0 (non-commercial)** |

Pitches above were measured by `SpeechEnginesTest` on synthesised speech (autocorrelation over voiced frames). They
order the voices; they are not perceptual ratings.

### Performance (measured on the 4-core Linux build container, 2 threads per engine)

* Joe synthesis: real-time factor **0.09** (1 s of speech in ~90 ms). First sentence of a reply ready **190–370 ms**
  after its text arrives.
* Kroko: finalising a turn **45–300 ms** after the end of speech is detected.
* Kokoro (Lewis) is several times slower than Piper; use it on a fast PC.

### Controls that exist (and ones that do not)

* **Speed** 0.7–1.4: the engine's length scale (real, not resampling).
* **Volume** 0.2–1.5: applied to the samples before they are sent.
* **Pronunciations**: text substitutions applied before synthesis (`CLU = Clue` by default).
* **No pitch control**: none of these engines can shift pitch without artefacts; choose a deeper or lighter voice.

## Memory

Engines load when a voice session starts and unload after 5 minutes without one. At most two voices stay loaded.
Measured resident memory is in [convergence/M8_REALTIME_VOICE.md](convergence/M8_REALTIME_VOICE.md#resources).

## Where the files are

* Installed with CLU: `<install folder>\voice-models\{vad, stt-kroko-en, espeak-ng-data, voices\…, optional\stt-whisper-base}` (read-only).
* Downloaded voices: `%LOCALAPPDATA%\CLU\voice\voices\<pack>` (kept on upgrade, removed only with CLU's data).
