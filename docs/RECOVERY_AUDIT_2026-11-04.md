# CLU recovery, capability audit and laptop-first transformation (2026-11-04)

What was inspected, what was measured, what changed, and what still has to be tried on real hardware. Every result
below names its category: **unit** (logic alone), **integration** (real components together), **simulated** (a real
component fed recorded or synthetic input instead of a person or device), **real hardware** (a real microphone,
speaker, camera or phone), **user acceptance** (the owner using it). Nothing in this report is real-hardware or
user-acceptance evidence: this machine has no microphone, speaker, camera, GPU or phone.

## 1. Audit

| Area | Before this work | Evidence |
|---|---|---|
| **Works** | Desktop host and API, chat streaming (SSE), memory, models view and benchmark runner, capabilities (weather, map), self-repair in worktrees with tests and approval, LiveKit voice loop on the PC (real livekit-server, real STT/TTS/LLM, simulated speech), Android app with on-device engine and "CLU on my PC" engine | existing suites: core 672, desktop 137, app 74 tests |
| **Broken** | "Hello" took 6.2 s to its first word after the model was loaded, and the model was loaded only on the first message (42.6 s). "What is a robotic element?" and weather questions went to web search and failed offline. "Thanks" was taken as the answer to a pending question. A new app was announced as "developing a unknown". "No, I said, talking" (recogniser punctuation) wasn't understood as a correction | `docs/measurements/turns-2026-11-04-before.tsv`; the failing real-speech test `VoiceRecognitionTest` |
| **Incomplete** | Android loaded its own model every time the app opened, even when the PC answered. The on-device engine was the default, so a paired phone still needed a manual switch | `CluApp`, `Settings.VOICE_MODE` |
| **Missing** | Camera hand-gesture interface (none existed). The Primus core visual was absent from CLU's window | – |
| **Recoverable from Primus** | The original JARVIS Core particle head (`jarvis-main` in the astra repository: Three.js, ~9,300 particles, GLSL, MIT) with its state API (`setPrimusState`, `setSpeakLevel`, `setCursorLook`) | restored from the original files, not re-drawn |
| **Needs new work** | Gesture engine (barehands is AGPL-3.0, so only its ideas were used, no code); laptop-first default on the phone | – |

### Why replies could take minutes on the owner's laptop (not reproducible here)

Measured causes, fixed: lazy model load (42.6 s on the first message), no warm-up (5.9 s prompt reading for "Hello"),
and offline web searches for questions that don't need them. Remaining candidates that can only be checked on the
laptop: the 180 s first-token watchdog firing on a slow GPU backend, the model being unloaded after 20 idle minutes and
reloaded on the next message, the resource governor unloading under memory pressure, and a model too large for the
machine. **Settings → Status → Recent requests** now shows each turn's stages (route, model ready, prompt reading,
first token) without conversation text: the first slow turn there will say which one it is.

### Why Qwen failed to build the Galaxy explorer

The documented failure stands (`docs/SOFTWARE_AUTHORING.md`, code kept in `docs/evidence/galaxy-explorer-qwen3-0.6b/`).
Qwen3-0.6B (the strongest real model available here; Hugging Face and remote providers are blocked by the network
policy, HTTP 403) passed 1 of 6 coding tasks. In five attempts it never kept to the file contract: it put inline
scripts in HTML after being told why that fails, hard-coded three fake nodes instead of reading the folder, and
repeated the same files despite the feedback. The engine side holds: requirements become checks, every attempt is
built, run in a browser and judged by those checks, and nothing unverified was installed or claimed. This needs a
stronger coding model, chosen by the same measured benchmark (Models → Benchmark) on the laptop. Splitting the task
into smaller ones doesn't help a model that can't hold a two-file contract.

## 2. Changes (local commits, not pushed)

| Commit | What |
|---|---|
| `6b01c19` | Conversation routing by need: concepts are answered directly, named or current subjects are researched, weather questions go to the weather tool (it asks for a city when none is known), closing phrases end an exchange, apps get a real name |
| `9030662` | The model is preloaded and warmed when the host starts (background thread), measured after a real restart. Registering or switching a model still loads nothing |
| `0f54d6b` | Primus core restored: the original particle head runs behind CLU's window and follows CLU's state (idle, listening, thinking, speaking, voice level, cursor). On software graphics it renders at a third of the resolution |
| `c3a6f34` | "I said, X" (comma from the recogniser) is understood as a correction |
| `f8317f9` | **Hands**: camera palm and finger gestures over glass widgets (core status, weather, CLU's applications, time). MediaPipe hand landmarker, downloaded only on the owner's click, pinned by SHA-256, then offline. CLU's own gesture engine: pinch with hysteresis and debounce, poses, adaptive smoothing, tap, drag, two-hand resize, calibration. Camera refused, missing or busy states are explained |
| `59a4265` | **Android as a terminal**: "Automatic" is the default engine. The PC answers voice and text once the phone is paired, otherwise the phone answers. While the PC answers, the phone loads no model of its own. Nothing removed |
| (this commit) | This report, user guide, regenerated settings register |

## 3. Measurements and tests

**Conversation** (integration with the real Qwen3-0.6B, real host restart, `TurnLatencyTest`):

| Turn | Before | After |
|---|---|---|
| Hello: first words | 6,207 ms (+42.6 s model load on first use) | 778 ms (model ready at start) |
| What is a robotic element? | web search, failed offline | answered in 1.5 s |
| Weather | web search, failed offline | weather tool; asks for a city when none is known (50 ms) |
| Thanks | answered as the pending question | answered normally |

**Hands** (`HandsUiTest`, opt-in) is integration with the real model, **simulated camera**: Chromium's fake capture device
plays clips made from MediaPipe's own photos of real hands.

| Clip | Frames with a hand | Pose | Other checks |
|---|---|---|---|
| pointing up | 25 of 25 | `point` | cursor on the fingertip, no false tap |
| victory | 25 of 25 | `two` | – |
| empty picture | 0 of 35 | – | no false detections |

Also checked: a refused permission is explained on screen, nothing is downloaded without a click, and leaving the view
releases the camera. The CPU path ran at 4 to 6 frames/s here with software graphics; real speed needs the laptop.
Measured limit: with the hand only about 110 px wide in a 640×480 picture, the model finds nothing. Hands must be within
about arm's length of a laptop webcam.

**Gesture engine** (`HandGesturesUnitTest`): 11 unit tests with synthetic landmarks.
- Poses
- Scale invariance
- Debounce
- Tap
- Hysteresis dead band
- Long pinch
- Drag
- Mirroring and jitter damping
- Two-hand scale
- Lost hand
- Calibration

**Primus** (`PrimusShellTest`): integration in the real window. The original core runs and goes thinking → speaking →
idle during a real turn.

**Suites:**
- Core: 672 tests, 0 failures.
- Desktop: 137 tests, 0 failures after the speech fix. It includes the real-speech `VoiceRecognitionTest` (simulated
  voices: Piper/Kitten synthetic speech) and the window test `ShellUiTest`.
- App: 74 tests (Robolectric), 0 failures; `apiCheck` and `verifyApk` pass.

No test was removed or weakened. Three expectations changed with intended behaviour, each noted in its commit:
- `DiaryTest`: a concept is answered directly.
- `LiveKitSettingsTest` and `OfflineConversationTest`: the new default, with the same property kept ("an unpaired phone
  answers on its own").
- `TouchDispatchTest`: the renamed card, plus a new check for the Automatic card.

## 4. Real-hardware acceptance: not done here

There is no microphone, speaker, camera or phone on this machine, so none of the following has been tried. They are
the acceptance steps for the owner:

1. **Laptop voice**: CLU → Settings → Voice, speak "Hello". **Status → Recent requests** shows speech end → first
   audio. The last measured value (simulated speech, real models, M8.5) was 2.8 to 10 s with Qwen3-0.6B on 4 CPU cores.
2. **Android → laptop → Android**: pair the phone (PC: Settings → Voice → Pair a phone; phone: Settings → Connection).
   The phone switches to the PC automatically. Speak and type; the phone's Connection screen shows the last
   speech-to-first-audio time.
3. **Hands**: open Hands, click Download (17.6 MB), allow the camera, press C to calibrate, then point, pinch to tap the
   Applications list, pinch and move to drag a widget, and pinch with both hands to resize it.
4. **First slow reply**: if a reply still takes minutes, Status → Recent requests names the stage.

## 5. Remaining blockers

- No real-hardware or user-acceptance evidence for voice, LiveKit phone↔laptop, or Hands.
- The coding model is too weak for applications of the Galaxy explorer's size. No stronger model can be downloaded
  here (Hugging Face and remote providers return HTTP 403). The owner's approval is needed for any download over 100 MB.
- Live weather is unavailable here (Open-Meteo blocked). The widget says so instead of inventing values.
- Hand-tracking speed and the GPU path are unmeasured on real graphics hardware.

## 6. Pushing

The commits above are local on `claude/clu-android-ai-build-5cspi2`. Nothing has been pushed since `9af6dce`, which was
pushed when the owner asked. They will be pushed only with the owner's explicit approval.
