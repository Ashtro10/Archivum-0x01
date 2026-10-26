# Voice-first agent: voice, speech delivery, conversation and tools

**Status:** implemented and tested in the build container: unit tests, Robolectric UI tests and host measurements with the real sherpa-onnx runtime. **Nothing here has been verified on the iQOO Neo 9 Pro or the Xiaomi Pad 6.** The device procedure is section W of [DEVICE_ACCEPTANCE.md](DEVICE_ACCEPTANCE.md). Every number below is labelled host or device.

## 1. Previous phase re-checked

Checked by code review, plus 323 automated tests passing, 0 failing:
- **Request ownership:** the voice paths only cancel `TurnOrigin.VOICE`, typed requests run in the app-scoped `ChatTurns`, and LiveKit cancels only LIVEKIT.
- **Thinking state:** the UI leaves Thinking on Done, Error and cancel.
- **Streaming:** tokens stream to the chat bubble and to speech.
- **Follow-up work:** the alternative-reply generation is cancelled by any new request.

No foundational defect was found. Device confirmation is still outstanding: sections V and W.

## 2. Clu's voice

### Candidates measured
Method: `scripts/scan_voices.py`, extended for Kokoro, running sherpa-onnx 1.13.8 (the app's runtime) on the x86 build host.
- **Pitch** is the YIN median.
- **Range** is the 10th–90th percentile spread in semitones. A wider range means less monotone.
- **WER** is word error rate, measured by transcribing the output back with whisper.cpp.
- **Phrase latency** is in `docs/measurements/tts_phrase_latency_host.json`.

| Voice | Licence | Median pitch | Range | WER | Short first phrase (4 threads) | Real-time factor | Download |
|---|---|---|---|---|---|---|---|
| **Joe** (Piper en_US medium) | Dataset CC0; fine-tuned from Piper's "lessac" voice, whose data (Blizzard 2013) has research-licence terms | 89 Hz | 6.1 st | 0.019 | 47 ms ("Sure.") / 96 ms (short sentence) | 0.06–0.15 | 67 MB |
| **Lewis** (Kokoro-82M v0.19 fp32, `bm_lewis`) | Apache-2.0 (LICENSE file in the archive) | 84 Hz | 10.7 st | 0.019 | 561 ms / 793 ms | 0.46–0.85 | 320 MB |
| Kokoro int8 (same voices) | Apache-2.0 | 91 Hz | 10.8 st | 0.019 | 2.6–3.4 s per sentence | 1.5 (slower than real time) | 103 MB |
| Kokoro `am_adam` / `am_michael` | Apache-2.0 | 121 Hz | 9–10 st | 0.019 | ~0.9–1.0 s per sentence | 0.52–0.53 | — |

**Decision:**
- **Joe stays the primary voice.** It is deep, fully intelligible, and starts speaking about 10× faster than Lewis. That speed matters for barge-in and turn-taking, and it leaves the CPU to the language model.
- **Lewis is offered as the optional "most natural" voice.** It has a wider, less monotone intonation range and the cleanest licence.
- **Lewis is a risk on the phone.** Its host real-time factor is 0.46–0.85 while nothing else runs. Alongside the LLM on a phone it may fall behind real time, which causes gaps. The screen says it starts slower. Device test W9 checks "Measure voice" and keeps Lewis only if the RTF is below 0.8.
- **Int8 Kokoro was rejected:** it is 3× slower than fp32 on the host.

Whether a voice sounds "calm and authoritative" can only be judged by listening. The bundled previews (`app/src/main/assets/voices/preview-*.wav`) are the actual output of each model.

### In the app
- **Settings → Voice:** two cards, Joe and Lewis.
  - Each card has a human description and **▶ Preview**, which plays a bundled clip, so it works before downloading.
  - Each card also has its download size, a Download button and a Use button.
- **Saving the choice:** choosing writes the normal engine settings, so it persists across updates.
- **Missing voice:** if the chosen voice is not downloaded, Clu stays silent and the chat shows a one-tap "Download Joe/Lewis" banner. It never switches to another voice.
- **Other engines** (Kitten, HayaiTTS, Android engines, other Piper voices, Hindi voice, measurements, streaming test) are under **Voice → Advanced voice settings**.

## 3. Natural speech delivery

`core/voice/SpeechText.kt` prepares the spoken version only; the chat text is unchanged.

**Never spoken:**
- markdown: headings, emphasis, bullets, numbered items, tables, inline code;
- raw URLs (a link keeps its label);
- tool markup;
- emoji.

**Read as words.** The phonemiser (espeak-ng) misreads these, so they are rewritten:
- times: 9:30 am → "9 30 A M", 18:05 → "18 oh 5";
- ISO dates;
- ₹/Rs/$/€/£ amounts, including "k" (thousands);
- units after numbers: km, km/h, kg, °C, %, GB…;
- numeric ranges, written with "to" (phone numbers are not treated as ranges);
- e.g., i.e., etc., vs., Dr., Mr.

**Pauses are measured, not guessed:**
1. The voices' own edge silences were measured on the host:
   - Joe: ~180 ms after a full stop, only 50–80 ms after a comma or ellipsis.
   - Kokoro: ~190 ms everywhere.
   - Both: 50–130 ms of lead-in before the next chunk.
2. Each chunk is padded only up to a target gap:
   - clause: 170 ms;
   - sentence: 360 ms;
   - question or exclamation: 380 ms;
   - ellipsis: 450 ms;
   - list line: 320 ms;
   - paragraph: 650 ms.
3. A voice that already pauses enough gets nothing added.
4. The chunker looks one character past a line break to tell a list line from a paragraph.

**Streaming stays incremental:**
- Sentence-sized chunks are synthesised while the model generates and played in order.
- A chunk is only cut at a completed word, so the final word is not cut off.
- No SSML: the on-device engines don't support it.

## 4. Conversation, interruption and recognition

**Continuous conversation** (default listening mode "Conversation"):
- The microphone stays open through Clu's reply, so barge-in works and listening resumes without restarting capture or the recogniser (new metric: `reply_end_to_listening_ms`).
- 30 s of silence closes the mic.
- ✕ ends the session.

**Barge-in** (unchanged mechanism, documented in SPEECH_RECOGNITION.md):
- The VAD detects voice onset during playback.
- About 180 ms of speech louder than the measured echo floor stops audio and cancels the VOICE request.
- The new utterance is captured with 0.8 s of pre-roll.
- **Limitation:** on the loudspeaker without call-mode echo cancellation, loud playback can mask a quiet interruption, or rarely trigger a false one. Measure it with W4. Speech output path = "communication" uses the platform echo canceller.

**Latency metrics** (Diagnostics → Performance, per voice turn):
- `speech_end_to_transcript_ms`;
- `speech_end_to_first_token_ms` (new) — this separates model delay from recognition and synthesis delay;
- `speech_end_to_first_audio_ms`;
- `interrupt_to_silence_ms`;
- `reply_end_to_listening_ms` (new).

**Host component numbers:**
- Recognition finalise p50 97 ms (Kroko, SPEECH_RECOGNITION.md).
- First token after the transcript ≈ 0.5–1.2 s with a 1B-shaped model (LLM_RELIABILITY.md).
- Joe's first phrase is 50–100 ms.
- **Not measured on the phone.** A fake model was not used to claim end-to-end speed.

**Misheard words:**
- A spoken turn's context block tells the model the text is a transcript that may contain misheard names and places.
- The model is told to:
  - resolve misheard words from the conversation and similar-sounding alternatives;
  - just act when the meaning is clear;
  - ask one short question when two readings remain;
  - never search random guesses.
- It is also told to answer in spoken style: no markdown, brief.
- The maps tool itself detects ambiguous place names (§5).
- No n-best alternatives are available from the streaming recogniser, so this is prompt-level plus tool-level.

## 5. Agent tools

**Architecture.** The existing central `ToolRegistry` and `ToolExecutor` define, for every tool:
- its name, description and argument example;
- its sensitivity and whether it needs the internet (category, per-category switches);
- whether it needs confirmation;
- a 45 s timeout;
- logging of every run in Tool history.

The model calls tools with `<tool>{…}</tool>`, and explicit commands are routed deterministically without the model.

**New: progress lines.**
- The chat shows "Searching the web…" or "Checking the routes…" while a network tool runs.
- In voice mode, one short phrase is spoken ("Checking the map.").

| Tool | What it does | Offline | Status |
|---|---|---|---|
| `maps.distance` | Nominatim places → OSRM routes (routing.openstreetmap.de car/bike/foot profiles, free, no key). Returns distance, typical time, main roads, up to two alternatives (fastest first), and the source. Says **"no live traffic"**. Asks which place is meant when a name matches far-apart places (e.g. Springfield). Transit: says it isn't in the data and offers Google Maps transit directions | reports "no internet" | **tested with recorded-format responses only**: the build container's network policy blocked both services |
| `maps.find` | Best place matches with full names; flags ambiguity | as above | same |
| `maps.open` | Google Maps navigation, or directions with a travel mode (walking, cycling, transit, from–to) — live traffic and transit appear there | needs the maps app | Android intent (untested on device) |
| `web.search` | DuckDuckGo (instant answers, then HTML results), with Wikipedia as fallback; returns snippets with source URLs; the model is told to name sources and say results are current | reports "no internet" | live services not reachable from the container |
| `calc`, `time.now`, `memory.*`, alarms, timers, flashlight, volume, apps, dialer, calendar | deterministic on-device tools | yes | unchanged |
| `trains.lookup` | web search for train info, labelled unofficial | no | unchanged |

**Router:** the example "What is the distance between Manhattan and Queens, how long will the trip take, and which route should I take?" is now parsed correctly (from = Manhattan, to = Queens, plus a travel mode when one is mentioned). It is answered from the tool result without the model, so it is fast and cannot be fabricated.

**Dependencies and costs:**
- **Routing:** the free FOSSGIS OSRM servers and Nominatim. Their usage policies allow light personal use with a User-Agent and about 1 request per second. No key, no cost, no live traffic.
- **Live traffic** would need a commercial API, such as Google Directions (pay-per-request after the free tier) or TomTom (key; free tier). **Not added:** it would need a paid account; opening Google Maps shows live traffic instead.

**Consequential actions:** tools marked `confirmByDefault` ask first, and messages are never sent; the dialer only opens. Success is reported only from a tool's result.

## 6. Simpler interface

- **Settings:**
  - six sections: Voice, Models & offline downloads, Conversation, Memory, Tools & internet, Diagnostics;
  - each simple section lists only its essential settings (22 in total, `SimpleSettings`), every one a real registry setting with an automated test of its effect;
  - all 130+ settings remain under **All settings (advanced)**.
- **Navigation sheet:** Chats, Memory, Voice, Settings and "More" (the rest), instead of 14 equal tiles.
- **Voice screen:** voice cards, three listening choices (language, what happens after Clu answers, "I pause a lot"), and optional Hindi downloads; technical details under Advanced.
- **Screenshots:** `app/build/screenshots/p2-*.png` (Robolectric render, not a phone).

## 7. Offline vs online

**Offline:**
- text chat and voice conversation (local LLM);
- English recognition (Kroko, built in);
- Hindi and mixed recognition (Whisper, optional download);
- both voices after download;
- memory, history, calculations, alarms, timers and device controls.

**Online only:** web search, place lookup and routing, train lookup, and the optional external AI. Each reports "No internet connection is available right now" instead of hanging.

## 8. Resources

| Component | Size on disk |
|---|---|
| LLM (e.g. 1B Q4_K_M) | ~0.8 GB, memory-mapped |
| Kroko recogniser | 71 MB |
| Joe | 67 MB |
| Lewis | 345 MB model |
| Whisper small (optional) | ~0.5 GB, plus ~1 GB of RAM while transcribing |

At most two voices stay loaded.

**Not measured:** RAM, CPU contention, battery and thermal behaviour on the phones. That is device test W14 (60 min), with the numbers coming from Diagnostics. Six-hour operation is **not** verified.

## 9. Tests

| Test | Kind |
|---|---|
| `SpeechTextTest` (5) | host unit: markdown/URL/tool stripping, notation, meaning kept, pause top-up, paragraph and list marks |
| `MapsToolTest` (6) | host, FakeHttp with recorded-format responses: route summary, modes, transit, ambiguity, routing failure, offline, compound question end to end |
| `VoiceAgentTest` (3) | host: curated voice ↔ engine without fallback; speech-aware prompt only for voice turns; tool progress in the chat (mocked model) |
| `SimpleSettingsTest` | every simple setting is real, tested and not advanced |
| `UiScreenshotTest` (+2) | Robolectric renders of Settings and advanced Voice |
| Earlier suites | all still pass (323 total, 0 failures, 7 opt-in skipped) |
