# M8 — Real-time voice on the PC (self-hosted LiveKit)

**Status:** implemented and tested end to end on Linux with the real components (own `livekit-server`, LiveKit's Rust
SDK, Chromium's WebRTC, sherpa-onnx engines, CLU's conversation manager, a real local model). The Windows build is
produced and statically verified, but **not run on Windows** here. The Android client is built (signed APK) and its token
client and room protocol are tested against the PC, but **not run on a phone**.

Legend used below: **T** implemented and tested · **B** built, not run on the target platform · **P** partial · **N** not done.

## 1. Architecture

```
 Desktop window (Edge app window)                 CLU host (Java, one process)                         Phone (CLU app)
 ┌──────────────────────────────┐   token (owner API)   ┌──────────────────────────────────────────────┐   device key → token   ┌────────────────────┐
 │ livekit-client 2.15.8 (JS)   │◄─────────────────────►│ VoiceService: sessions, pairing, settings,   │◄──────────────────────►│ LiveKit Android SDK │
 │ mic (AEC/NS/AGC) → track     │                       │ downloads, LAN token endpoint (opt-in)       │  LAN :47822/token      │ TokenProvisioner    │
 │ CLU's track → speaker        │                       │ VoiceAgent per room (LiveKit FFI participant │                        │ mic → track         │
 │ states, live words, Stop,    │   WebRTC (DTLS-SRTP)  │  "clu", kind=agent):                          │  WebRTC over Wi-Fi     │ CLU's track → spkr  │
 │ Mute, PTT, reconnect         │◄────────────┐         │  frames → Silero VAD → Kroko/Whisper          │◄──────────┐            └────────────────────┘
 └──────────────────────────────┘             │         │  → CluAgent (M4 memory, M5 inference)         │           │
                                              │         │  → AdaptiveChunker → Piper/Kitten/Kokoro      │           │
                                       ┌──────┴──────┐  │  → 24 kHz track; barge-in; wake words         │    ┌──────┴──────┐
                                       │livekit-server│◄─┤ LiveKitServer: generated keys, 127.0.0.1     │    │ (same server,│
                                       │ 1.9.12 (Go)  │  │  (+ private address with phone access),      │    │ private addr)│
                                       └─────────────┘  │  watchdog, idle stop                          │    └─────────────┘
                                                        └──────────────────────────────────────────────┘
```

* **One conversation manager.** Voice turns go through `CluAgent.respond` (the same path as typed turns), so memory
  recall/formation (M4), the inference service with its queue, timeouts and cancellation (M5), research policy and
  permissions (M7) all apply. Voice and text share conversation IDs and history (tested both ways).
* **Agent protocol.** CLU is a LiveKit participant of kind *agent* and speaks the LiveKit Agents conventions the
  Android client already used: `lk.agent.state` attribute (initializing / idle / listening / thinking / speaking),
  `lk.transcription` text streams (`lk.transcription_final`, `lk.segment_id`, `lk.transcribed_track_id`; your words are
  sent on behalf of you, as LiveKit agents do), `lk.chat` for typed input, RPCs `clu.interrupt`,
  `clu.set_audio_output`, `clu.load_history`, `clu.set_mic`, `clu.push_to_talk`, data topics `clu.metrics`,
  `clu.events`.
* **Streaming.** Text streams from the model into the chunker; the first chunk is cut after 2–10 words, synthesised and
  sent while the model continues. Nothing longer than 220 characters is synthesised at once.
* **Barge-in.** While CLU thinks or speaks, ≥ 400 ms (setting) of continuous speech detected by the VAD cancels the
  generation and drops queued audio at once; the clients' WebRTC echo cancellation keeps CLU's own voice out of the mic.

## 2. Old voice components: audit and decisions

| Component | Where | Decision | Why / what happened |
|---|---|---|---|
| Python LiveKit Agents worker `clu_agent.py`, token server `clu_voice_server.py`, local speech helpers, second controller `clu_controller.py`/`clu_context.py` (3.8k lines) | `server/` | **Removed** | Replaced by the host's `VoiceService`/`VoiceAgent` (one controller, no Python, no LiveKit Cloud path). Removed only after the PC path passed the phone token + conversation test. Its two opt-in tests (`ServerContractTest`, `TokenServerConformanceTest`) went with it; the phone's token conformance is now tested against the PC (`aPairedPhoneGetsATokenFromThePCAndTalksToCLU`). The shared JSON contract stays (core `AgentContractTest`). |
| LiveKit Cloud settings/texts | core settings, app Connection screen | **Replaced** | Texts now describe pairing with "CLU on my PC"; the token endpoint/device-key settings are unchanged (the PC speaks the same format). |
| Android LiveKit client (`LiveKitSession`, `LiveConversation`, `TokenProvisioner`, HUD) | `app/`, `core/live`, `core/livekit` | **Kept** | Already a standards-conforming client; works against the PC unchanged except texts. |
| Android on-device engines (`VoiceSession`, sherpa VAD/ASR/TTS, whisper.cpp, `ListeningService`) | `app/voice` | **Kept as fallback** | "This device" mode for when the PC is unreachable. Not used when talking to CLU on the PC (the PC synthesises with the shared voice). |
| Speech engines and helpers (`AdaptiveChunker`, `SpeechText` pacing/normalisation, `WakePhraseMatcher`, `isTermination`) | `core/voice` | **Adapted / reused** | Used by the PC agent as-is. |
| Keyword-spotting wake model (sherpa KWS gigaspeech) | toolchain assets | **Rejected for the PC** | Missed isolated "Clue" in testing; wake words use streaming STT + `WakePhraseMatcher` instead. |
| Kroko streaming English, Silero VAD, Piper/Kitten/Kokoro voice packs, Whisper base | models | **Kept**, moved to the PC | Bundled in the installer (Whisper optional). |

## 3. What was built

| Area | State | Evidence |
|---|---|---|
| `livekit-server` 1.9.12 built from source (Go module proxy), Windows GUI-subsystem exe + Linux | **T** (Linux) / **B** (Windows) | `scripts/build_voice_native.sh`; `VoiceTransportTest` |
| Supervisor: generated production keys, per-start config, bind 127.0.0.1 (+ private address), health check, watchdog (3 restarts / 5 min), idle stop, memory reported to the governor | **T** | `VoiceTransportTest`, `VoiceConversationTest`; `VoiceService.serverMemory` (Linux `/proc`, Windows `tasklist`, cached 30 s) |
| LiveKit Rust SDK via `livekit_ffi` (protobuf, JNI bridge `clu_voice`) | **T** (Linux) / **B** (Windows DLLs, MinGW) | all voice tests |
| Speech engines (sherpa-onnx 1.13.8 C API): VAD, streaming Kroko, Whisper, Piper/Kitten/Kokoro | **T** | `SpeechEnginesTest` (TTS→STT round trip exact, partials, voices' pitch, speed, wake phrases, farewells, resampler) |
| Voice agent: continuous / push-to-talk / wake-word / manual modes, live partials, barge-in, farewell, streaming TTS, timings | **T** | `VoiceConversationTest` (5) |
| Desktop window client: Talk, state, live transcripts, mic indicator, Mute, Stop, PTT/space bar, manual, reconnection (3 fresh-session retries), device choice, metrics | **T** (Chromium) / **B** (Edge on Windows) | `VoiceUiTest` (real Chromium with a fake microphone playing a spoken question) |
| Settings → Voice: voice grid (installed/size/licence/source), real Preview, consented resumable verified downloads, selection shared with the phone, speed, volume, language, mode, timings, wake words + strictness, farewells, pronunciations, mic/speaker, phone access + pairing | **T** | `VoiceUiTest`; download path unit-covered by consent/verification code, **not** exercised against GitHub here (blocked network) |
| Phone: pairing, LAN token endpoint (rate-limited, own room only, short-lived tokens), talking to CLU | **T** (protocol, with the app's own `TokenProvisioner` and LiveKit's SDK) / **N** on a real phone | `aPairedPhoneGetsATokenFromThePCAndTalksToCLU` |
| Installer: voice DLLs + `livekit-server.exe` + models in the main component, Whisper optional, licences | **B** | `scripts/verify_installer.sh` 24/24 |

## 4. Acceptance tests

### A. Basic voice — **T**
Spoken question → live words → reply text + audio → recognised back from CLU's own audio
(`aSpokenQuestionIsTranscribedAnsweredThroughTheConversationAndSpokenBack`); the same from the real browser window
(`VoiceUiTest`). Stop, Mute and End tested in the window.

### B. Voice selection and persistence — **T**
Choosing Alan in the window is stored on the host (`voice.settings` in the database), shown in the bar of the next
session, and used for the phone (one setting, synthesised on the PC). Preview returns real WAV from the selected
engine. Download asks first with size/source/licence; declining downloads nothing.

### C. Offline — **T**
All voice tests run with `NET_ENABLED=false`, no internet route used: the LiveKit server, engines, model and window
talk only over loopback (the phone test over a stand-in private address). No external host is contacted.

### D. Real-time behaviour — **T**, measured (section 5)
Barge-in stops CLU 1.27 s after the owner starts talking over it in the scripted test, the rest of the answer is
dropped, the new question is answered with context kept. Wake-word mode ignores ordinary speech (nothing sent to the
model), answers "Clue, please introduce yourself", and sleeps after "goodbye".

### E. Regression of M4–M7 — **T**
Full `./gradlew check` (core, desktop, app, Android API check, ICU regex check) and the M3–M7 Playwright run; results
in section 7.

### F. Hardware validation — **N here**; checklist for the owner in section 8.

## 5. Measurements (Linux build container: 4 vCPU x86-64, no GPU — not the target PC) {#resources}

### Latency, real local model (SmolLM2-135M Instruct Q4_1, `docs/measurements/m8_voice_host_smollm2.json`)

| Question (spoken by a second synthetic voice) | Heard as | speech end → CLU's first audio (PC) | STT finalise | first token | first audio after first token |
|---|---|---|---|---|---|
| What is the capital of France? | exact | **2.17 s** | 104 ms | 1.14 s | 211 ms |
| Give me one tip for staying focused. | exact (no ".") | **2.15 s** | 45 ms | 1.04 s | 361 ms |
| Please introduce yourself in one sentence. | exact (no ".") | **2.39 s** | 46 ms | 1.29 s | 352 ms |

* "Speech end" is the end of the words; 700 ms of that is the end-of-speech pause (setting).
* The model's first token (prompt prefill of CLU's system prompt + memory context on 4 cores) is the largest part.
  On the target PC (Ryzen AI 7 350, 8 cores) and with a 1–3 B model this will differ in both directions; it is the number
  to check first on the real hardware.
* With a scripted model (no LLM time) the window measured **reply began 1.0 s after you stopped** (`VoiceUiTest`) and
  the PC measured 0.9–1.4 s; STT 45 ms, first token 115 ms, TTS 275 ms.
* The stand-in client's own "heard after" figure is ~1.2 s larger because it counts from when its last frame was queued,
  not played; the PC-side number is the reliable one.

### Resources (same run; `SmolLM2` / random-weight 1B-shaped model)

| | SmolLM2-135M | 1B-shaped Q4_K_M |
|---|---|---|
| Host idle, no model, no voice | 152 MB RSS, 0.4 % of one core | 153 MB, 0.3 % |
| + model loaded | 334 MB | 1 534 MB |
| + voice engines (VAD, Kroko, Piper Joe) | 573 MB (+239 MB) | 1 769 MB (+235 MB) |
| livekit-server process | 77 MB | 78 MB |
| Open microphone, nobody talking (CPU) | 28 % of one core* | 29 %* |
| During a turn (CPU, whole process) | 75–106 % of one core | 59–62 % |
| After 3 turns | 850 MB | 2 820 MB |
| After voice ends and engines unload | 705 MB | 1 957 MB |

\* Both participants run in the test process (the stand-in client encodes Opus and runs its own WebRTC stack), so the
idle-listening CPU of the host alone is lower than this; it was not separated.

**Synthesis memory (found by these runs).** Host memory rose by ~1.1 GB over three turns with the random-weight 1B
model and by ~0.28 GB with SmolLM2, and most of it was returned when the voice engines unloaded. A direct probe
(120 syntheses on one Piper voice) showed why: ONNX Runtime keeps memory for every input shape it has seen — +0.7 MB
per varied English sentence (458 → 531 MB, still rising), +1.4 GB for 120 chunks of punctuation-free mixed-script text —
and gives all of it back when the voice is closed (→ 212 / 249 MB). Not a leak, but unbounded over a long session.
Fixes: (1) nothing longer than 220 characters is synthesised at once; (2) a voice that has synthesised more than 120
times is replaced by a fresh instance **between turns**, in the background (`VoiceEngines.recycleWorn`, tested by
`aWornVoiceIsReplacedByAFreshOneAndTheOldOneIsSafelyClosed`); a chunk that races with the swap is re-synthesised on
the new instance. The random model's 3–9 s to first audio is its output having almost no spaces or punctuation (the
chunker waits for a word boundary); real model output does not do that (SmolLM2: 0.2–0.36 s).

**16 GB budget:** host + voice engines + livekit-server ≈ 0.65 GB on top of the model; with a 3 B Q4 model (~2.4 GB at
4 k context) and the Edge window (~0.3–0.5 GB) the total stays near 3.5–4 GB. The governor counts the LiveKit process.
Engines unload after 5 min without a session, the server stops after 10 min (unless phone access is on).

## 6. Known limits

* **Not run on Windows or a phone.** WebView/Edge microphone permission, Windows firewall prompts, Windows audio devices,
  `tasklist` parsing and the Windows DLL loading order are built and reviewed but unverified.
* **Phone in the background:** the phone keeps the room only while a CLU screen is visible (it disconnects after idle
  to free the PC's engines); hands-free use with the phone locked is not supported in PC mode.
* **Signalling between phone and PC is cleartext on the LAN** (media is encrypted); see `docs/VOICE_SECURITY.md`.
* **No speaker verification**; anyone near the microphone can talk to CLU.
* First-token latency is dominated by prompt prefill; prefix caching (M5) does not remove it across turns in this run.
* Changing *phone access* while a desktop session runs restarts the server; the window reconnects with a fresh session.
* Voice downloads from GitHub were not exercised (GitHub is blocked in the build container); consent, resume and
  SHA-256 verification are implemented against pinned URLs/hashes.

## 7. Test totals

See `docs/convergence/CONVERGENCE_STATUS.md` (final `./gradlew check` of this milestone).

## 8. What the owner should test on the PC (Windows) and phone

1. Install `CLU-Setup-0.7.0.exe` (leave *Speech recognition for other languages* unticked first). Start CLU.
2. Chat → 🎙 Talk → allow the microphone. Ask "What can you do?". Expect *Listening → Thinking → Speaking*, your words
   live, a spoken answer. Note the "reply began … s" figure under the bar.
3. Ask for something long ("tell me about the history of Rome") and talk over CLU after a few words: it should stop
   within about a second and answer the new question.
4. Settings → Voice: Preview Joe, Alan, Ryan, a Kitten voice; choose Alan; start Talk again: the bar says *Voice: Alan*.
5. Listening → *After a wake word*: say "what time is it" (nothing should happen), then "Clue, what time is it?",
   then "goodbye" (CLU goes back to waiting).
6. Turn off Wi-Fi/Ethernet and repeat step 2: it must work the same.
7. Task Manager: note CLU's (OpenJDK) and livekit-server's memory while talking and 10 minutes after ending voice.
8. Phone: Settings → Voice → phone access on (allow *private networks* in the firewall prompt) → Pair a phone; enter the
   endpoint and key in the app (☰ → Connection → CLU on my PC) → Test connection → talk from the voice screen. The voice
   should be the one chosen on the PC. Remove the phone on the PC: the app must be refused at once.
9. Send `%LOCALAPPDATA%\CLU\voice\livekit.log`, `%LOCALAPPDATA%\CLU\logs\host.log` and the `/v1/voice/status` timings
   if anything misbehaves.
