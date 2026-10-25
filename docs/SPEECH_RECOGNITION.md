# Speech recognition and turn-taking: investigation and fixes

**Status:** fixed and measured on the build host by replaying Clu's capture pipeline. **Not yet verified on a physical phone.** The device procedure is section U of DEVICE_ACCEPTANCE.md. Numbers below are host measurements unless marked otherwise.

## 1. Root cause of "Apple" → "peel"

There were three causes, from largest to smallest.

1. **The built-in live recogniser dropped the start of every utterance.**
   - The model was `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` (LibriSpeech-trained, 20M parameters).
   - On Clu's test set it lost the first ~0.3–0.5 s of speech after silence, even when given the whole clip:
     - "Turn off the bedroom lights" came out as "F BEDROOM LIGHTS";
     - "Remind me to buy milk" as "D ME TO BUY MILK";
     - single words came out empty.
   - "Apple" was recognised in **1 of 27** clips.
   - On LibriSpeech book audio, the data it was trained on, it scored 4.5% WER, which is why earlier host tests looked fine.
   - The previous build fell back to this model whenever Whisper wasn't downloaded. That was the default state, since Whisper is a 466 MB manual download.
2. **The app's pre-roll was too short.**
   - When the VAD fires, the app hands the recogniser the last *pre-roll* of audio. That was 0.4 s for the live model and 0.3 s for Whisper.
   - Measured VAD onset delay is p50 360 ms, p90 510 ms, max 810 ms (n = 1833).
   - So roughly half of all utterances lost their first syllable before any model saw them.
3. **Speech during Clu's own activation tone was discarded.**
   - The VAD was masked while any Clu audio played in the listening state, including the 180 ms tone (plus output latency).
   - Speaking right after tapping the mic lost the onset.

**Not a cause:** the capture format. It is 16 kHz mono; float, or PCM16/32768; 30 ms frames into a 4 s ring buffer. Resampling, channel count and buffer sizes were checked and are correct. Clipping is now measured per utterance in the diagnostics screen.

## 2. Fixes

| Problem | Fix | File |
|---|---|---|
| Live model loses onsets, weak on short commands | Replaced with the **Kroko streaming zipformer (English)**, bundled in the APK (71 MB) | `scripts/setup_toolchain.sh`, `VoiceModels.kt` |
| Kroko emits the last word only after enough look-ahead | 1.5 s of silence is appended when finalising (measured; with 0.5 s the end was cut: "Turn off the bed") | `ASR_TAIL_SEC` |
| Pre-roll 0.3/0.4 s | 1.0 s (covers p90 + 0.5 s), never reaching before the mic opened or into Clu's own words | `VoiceSession.preRoll` |
| Speech masked during the tone | Only Clu's spoken *words* (spoken acknowledgement, greeting) are masked; tones are not | `SpeechOutput.speakingWords` |
| Hindi decoded as English when Whisper was missing | Explicit language routing. English → live recogniser. Hindi / mixed / automatic → Whisper. Without Whisper, Clu says "English only until it is installed" instead of pretending | `AsrRouting.kt` |
| Turn ended at 650 ms of silence, often mid-sentence | 800 ms default, chosen from measurements (§4); adjustable on the Voice screen | `Settings.END_SILENCE` |
| No way to see what was heard | Speech recognition diagnostics screen (§6) | `SpeechDiagnosticsActivity.kt` |

## 3. Model comparison

**Method:**
- 629 clips: 23 items (common words including "Apple", names, short commands, numbers and sentences).
- Each item rendered with 9 voices: US, female, Scottish, and four Indian-accented English speakers (CMU Arctic ksp/aup/gka, VCTK p251).
- Three conditions: clean, pink noise at 10 dB SNR, and quiet speech (−26 dB). Plus real LibriSpeech recordings.
- Replayed through Clu's pipeline (30 ms frames, Silero VAD, pre-roll, priming, tail).
- Scripts: `scripts/make_asr_testset.py`, `scripts/eval_asr.py`.
- Synthetic speech is cleaner than a phone microphone, so absolute numbers are optimistic. The comparison between models is the point.

| Model | Size | WER, all | Words | Names | Quiet | Indian accent | "Apple" | Finalise p50 / p90 |
|---|---|---|---|---|---|---|---|---|
| zipformer en-20M (old built-in), old pipeline | 43 MB | 63.7% | — | — | 80.2% | 70.1% | 1/27 | — |
| zipformer en-2023-06-26 (LibriSpeech) | 71 MB | 41.6% | 60.2% | 61.8% | 78.3% | 47.5% | 14/27 | 79 / 128 ms |
| zipformer en-2023-06-21 (Libri + Giga) | 189 MB | 30.5% | 54.6% | 47.9% | 56.2% | 34.9% | 20/27 | 71 / 130 ms |
| **Kroko 2025-08-06 (new built-in)** | **71 MB** | **13.2%** | **23.6%** | **39.9%** | **16.5%** | **17.0%** | **27/27** | **97 / 183 ms** |
| NeMo FastConformer streaming 80 ms | 478 MB fp32 | not run: too large for the phone's RAM budget | | | | | | |

Notes:
- Real-time factor on the host is 0.13 for Kroko, so it decodes about 7× faster than real time on 2 threads.
- Names remain the hardest category for every model.
- **Kroko licence:** the model README points to huggingface.co/Banafo/Kroko-ASR. A third-party summary describes the community models as CC-BY-SA, but other wording mentions non-commercial keys. Verify before any commercial distribution. For a personal assistant this is fine; the attribution is in this file and in `setup_toolchain.sh`.

### 3a. Whisper on the same clips (whole clip, after the turn ends)

| Model | WER, all | Commands | Words | Names | Numbers* | Quiet | Indian accent | "Apple" |
|---|---|---|---|---|---|---|---|---|
| Whisper base | 15.1% | 5.3% | 38.4% | 27.1% | 31.4% | 16.2% | 16.1% | 26/27 |
| Whisper small | 11.7% | 3.6% | 23.6% | 23.6% | 24.5% | 12.2% | 14.2% | 27/27 |

\*Whisper writes digits ("25"), and the scorer reads "25" as "two five", so number WER is overstated.

**Speed:** the timing here (whisper-cli, full 30 s window, process start per clip) does not represent the app. In-app Whisper uses the shortened audio window: small needed ~0.6–1.5 s per utterance on the host (VOICE_STREAMING.md).

**Conclusion:**
- For English, the new live recogniser (13.2%) is close to Whisper small (11.7%) and gives words while you speak, with a ~0.1 s final.
- Whisper stays the engine for Hindi, mixed and automatic language.

**Hindi:**
- No streaming Hindi transducer exists in the sherpa-onnx catalogue (checked against the k2-fsa/sherpa model list).
- Hindi and mixed Hindi-English therefore use Whisper small, which transcribes after the turn ends.
- Earlier host measurement on synthetic Hindi: CER 0.19–0.40, about 0.9–1.2 s per utterance (docs/VOICE_STREAMING.md).

## 4. Turn-taking: end-of-speech pause

**Method:** sentences with a real thinking pause ("Remind me to … buy milk tomorrow"), 10 sentences × 3 voices × pauses of 0.4, 0.7 and 1.0 s. `scripts/eval_endpointing.py` replays them on Silero VAD decisions.

| End-of-speech pause | Turn ends after the last word (p50) | Sentence cut at 0.4 s pause | at 0.7 s | at 1.0 s |
|---|---|---|---|---|
| 500 ms | 0.62 s | 0/30 | 30/30 | 30/30 |
| 650 ms (old default) | 0.78 s | 0/30 | 12/30 | 30/30 |
| **800 ms (new default)** | **0.91 s** | **0/30** | **0/30** | 30/30 |
| 1000 ms | 1.13 s | 0/30 | 0/30 | 2/30 |
| 1200 ms | 1.33 s | 0/30 | 0/30 | 0/30 |

**Tried and dropped:** waiting longer when the live words end in "and" / "the" / "to". Kroko's live words lag about 1.5 s (its look-ahead), so at a pause the last word is not there yet. The rule rarely fired inside sentences, and at real sentence ends it added 0.6–1.1 s (p90). Claiming it worked would have been wrong.

**Barge-in** is unchanged from the previous build: no wake word, about 180 ms of speech louder than Clu's echo, call-audio echo cancellation.

## 5. Conversation flow

- **Listening mode** (Voice screen → Listening…):
  - **Conversation** (default): after you tap the mic, Clu keeps listening for follow-ups with no wake word, until you tap Stop/✕ or stay silent for the follow-up window (now 30 s, was 10 s).
  - **One question**: the mic closes after each reply.
  - **Hold to talk**.
- **Mic closes on timeout:** a session opened with the mic button now closes the microphone when the follow-up window passes. Before, it silently moved on to waiting for the wake word.
- **Voice mode (full screen):**
  - shows Listening / Thinking / Speaking / Error, the live words, the session time and an orb driven by your voice level;
  - the big button interrupts Clu while it speaks, or says "I'm done" while listening;
  - ⌨ returns to typing, ✕ ends the session.
- **Text input:** typing and Send are always available.
- **Microphone permission:** explained before the system prompt. Audio is processed on the device; nothing is uploaded or saved; the last utterance is kept in memory only for diagnostics.

## 6. Speech recognition diagnostics

Open it from Voice → Speech recognition diagnostics, ⋮ in voice mode, or ☰ → Speech recognition diagnostics.

For each utterance it shows:
- recognised text (or "nothing recognised");
- engine and model, and language;
- speech length and pre-roll;
- end-of-speech wait, recognition time, and the total after you stopped;
- peak and RMS level, and clipping, with warnings for clipping or a very quiet microphone;
- capture effects (AEC/NS, PCM16).

**"Play what Clu heard"** plays back the last utterance exactly as the recogniser got it, to check for clipped words or a bad microphone route. It is memory only: nothing is written to storage or uploaded.

## 7. Resource use (host)

- **Kroko live recogniser:** 71 MB on disk; real-time factor 0.13 on 2 x86 threads. Phone CPU and RAM are not yet measured; check Diagnostics → Performance, `asr_finalize_ms`.
- **Whisper:** runs only after a turn ends, never continuously next to the live model. Small needs ~1 GB RAM.
