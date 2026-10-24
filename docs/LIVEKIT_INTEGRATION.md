# LiveKit voice integration

_Status 2026-10-24: implemented and tested end to end on the build host (real LiveKit server, real voice agent, real
Kotlin client on WebRTC, Clu's real core). **Not yet tested on the phone or tablet.** Every number below is labelled
with where it was measured._

## 1. What the starter is (inspection of `ruxakK/livekit_flutter_starter`, commit 74d39bf)

| Aspect | Finding |
|---|---|
| Origin / license | Copy of LiveKit's official Flutter agent starter. MIT, © 2025 LiveKit, Inc. 3 commits. |
| Stack | Flutter / Dart. `livekit_client ^2.10`, `livekit_components ^1.3.1`, `flutter_dotenv`, `provider`, `chat_bubbles`, `http`, `url_launcher`. Android build: AGP 8.11.1, NDK 28.2, Kotlin 2.2.20. |
| What it does | A voice/text/video **front end** for a LiveKit Agent. Speech recognition, the LLM and speech synthesis all run in a separate agent process on a server. **The starter contains no offline STT, LLM or TTS.** |
| Default agent | With no configuration it fetches a token from `https://livekit.com/api/homepage-agent/token` and talks to **LiveKit's hosted demo agent**, i.e. it sends your microphone audio to LiveKit's cloud. |
| Token flow | `LIVEKIT_SANDBOX_ID` → LiveKit Cloud "sandbox token server" (development only), or the homepage endpoint above, or hard-coded dev credentials in `app_ctrl.dart`. For production it says to write your own token endpoint. |
| Permissions | Camera, microphone, Bluetooth, network-state change, audio settings. |
| Network | Always needs the LiveKit server and agent; nothing works offline. |

**Conclusion:** the starter is useful as a UI and token-flow reference, but none of its defaults are acceptable for
Clu. They send audio to a third-party demo agent, depend on LiveKit Cloud, and provide no offline capability.

## 2. Integration strategy: options and decision

| | A. Migrate Clu to Flutter (starter as base) | B. Keep Kotlin, use LiveKit's official Android SDK | **C. Keep Kotlin, minimal LiveKit client (chosen)** |
|---|---|---|---|
| Offline voice, llama.cpp, memory, settings, security, sync | Must be re-plumbed through platform channels; all native parts stay Kotlin/C++ anyway | Unchanged | Unchanged |
| Work | Rewrite all 16 screens, services, permissions and tests (~200 tests target Kotlin) | Small | Medium (one isolated module, ~600 lines + tests) |
| Build in this environment | **Impossible**: Flutter's Android build needs the Android Gradle Plugin from Google Maven (blocked) | **Impossible**: needs AndroidX from Google Maven, plus extra native libraries | **Possible**: only Maven Central + GitHub (WebRTC SDK, OkHttp, protobuf-javalite, LiveKit protocol files) |
| APK size | + Flutter engine (~8–10 MB) + WebRTC | + WebRTC + LiveKit native helper | + WebRTC (≈12 MB arm64 library) + ≈1 MB code |
| Risk | Very high: regressions everywhere, months of re-verification | Low (mature SDK) | Medium: own protocol client (no resume/reconnect, no video). Mitigated by real-server tests. |
| Reversible | No | Yes | Yes (`lkclient` module + one setting; removable) |

**Decision: C.** Migrating to Flutter would not reduce complexity. Clu's hard parts (llama.cpp, sherpa-onnx, Android
services, Keystore, sync) are native and would all need bridges again. The starter's real value is a server-side
agent workflow plus a UI, both of which can be replicated. Option B is the better long-term client, and the
`RtcFactory`/`LiveKitRoom` boundary allows swapping it in once Google Maven is reachable. No existing feature was
removed or changed.

Taken from the starter, as concepts (no Dart code copied):
* LiveKit's standard endpoint token format (`room_name`, `participant_identity`, `room_config.agents` →
  `server_url`, `participant_token`)
* the agent-state indicator (listening / thinking / speaking)
* live transcription (partial text)
* clear connection status and error messages

Not taken: video, avatars and screen share (no use for Clu); the pre-connect audio buffer (possible later).

## 3. Architecture

```
 Phone / tablet (Clu app)                                   Owner's computer (home network / hotspot)
 ┌────────────────────────────────────────────┐            ┌───────────────────────────────────────────┐
 │ Wake word / clap / mic button (offline)    │            │ clu_voice_server.py                        │
 │        │ activation                         │  token req │   /token: device key → 10-min room token   │
 │        ▼                                    │ ─────────▶ │   (LiveKit API secret stays here)          │
 │ VoiceModePolicy ─ offline ─▶ VoiceSession   │            │   starts one agent per device room         │
 │        │ livekit                            │            │                                            │
 │        ▼                                    │   WebRTC   │ livekit-server  ◀──────▶  clu_voice_agent  │
 │ LiveKitSession ── lkclient (LiveKitRoom) ◀──┼──────────▶ │   (SFU)                   sherpa-onnx ASR  │
 │        │  mic (AEC/NS) ▲ agent voice        │  audio +   │                           Piper TTS        │
 │        │               │                    │  data      │                           barge-in         │
 │ RemoteVoiceBridge ◀─ clu.transcript ────────┼────────────┼──────────────────────────────┘             │
 │   CluAgent: local llama.cpp, memory, tools, │            └───────────────────────────────────────────┘
 │   permissions, history ── clu.reply ───────▶│
 └────────────────────────────────────────────┘
```

* **Offline mode** (default, `voice.mode = offline`): unchanged from before. Microphone → Silero VAD → sherpa-onnx
  streaming ASR → CluAgent (llama.cpp) → Piper/Android TTS, all on the device. No LiveKit code runs.
* **LiveKit mode** (`voice.mode = livekit`): the offline session still owns the wake word, clap and mic button. On
  activation it plays the acknowledgement, releases the microphone and hands over to `LiveKitSession`. The
  agent transcribes on the server and sends `clu.transcript`. `RemoteVoiceBridge` runs it through the same
  `CluAgent` (model, memory retrieval, tools, owner/guest permissions, conversation history) and streams
  `clu.reply` sentences back, which the agent speaks. "End of the line" ends the session after the farewell
  has been spoken, and the offline wake word resumes.
* **Fallback:** no network, no device key/endpoint, or an unreachable server → the conversation continues offline
  (`livekit.fallbackOffline`, on by default). Off = an error is shown instead.
* **Interruption:** the agent detects your speech while Clu talks (VAD), stops playback at once and sends
  `clu.interrupt`. Clu cancels generation. The 1 s of audio before detection is passed to recognition so the
  interrupting sentence is heard whole.
* **Wake-word controls, manual memory importer, settings, sync:** unchanged. LiveKit turns are stored in the
  same conversation, so they sync to the other device like any turn.

### Data protocol (reliable LiveKit data packets, JSON, v1)

| Direction | Topic | Payload |
|---|---|---|
| agent → Clu | `clu.transcript` | `{turn, text, speech_ms}` |
| agent → Clu | `clu.interrupt` | `{turn}` |
| agent → Clu | `clu.spoken` | `{turn}`: the agent finished playing a reply |
| agent → Clu | `clu.agent_state`, `clu.partial` | UI only |
| Clu → agent | `clu.reply` | `{turn, seq, text, final, end_session}` |

Clu accepts these only from the participant LiveKit marks as an **agent** (`kind = AGENT`, set by the token
server). Data from anyone else in the room is ignored.

## 4. Security

| Concern | Measure |
|---|---|
| Secrets in the app | None. The app holds a revocable **device key** (`id.secret`), encrypted with an Android Keystore key. It is never shown again after entry, and changing it requires owner verification. |
| Token scope | Minted by the owner's server: only room `clu-<id>`, identity `clu-<id>`, microphone + data, 10-minute lifetime, no admin/create. Clu also **refuses** a token with a different room/identity, an expired token, or an admin/create grant. |
| Transport | `https://`/`wss://` required. `http://`/`ws://` only to private-network addresses (`livekit.allowLanCleartext`, owner verification to change). Media is always DTLS-SRTP encrypted by WebRTC. |
| Brute force | Token server stores only SHA-256 of keys, compares in constant time, throttles 10 bad keys/min per address. |
| Who can drive Clu | Only the agent participant. Voice permissions as offline: voice-only turns cannot complete sensitive actions. If speaker verification is in use, LiveKit voices count as **unknown speakers** (LiveKit cannot verify the voice). |
| Privacy | The agent necessarily sees transcripts and replies (it speaks them), and replies may contain memory content. Run it only on your own machine. No third-party service, no telemetry. The starter's LiveKit homepage/sandbox defaults are not used anywhere. |
| Changing where voice goes | `livekit.tokenEndpoint` is a sensitive setting (owner verification). |

## 5. Required servers, tokens and dependencies (network mode)

| Item | Version tested | Notes |
|---|---|---|
| `livekit-server` | 1.13.10 (Apache-2.0) | Your own keys (`livekit-server generate-keys`); ports 7880, 7881/tcp, 50000–50100/udp on the LAN |
| Python | 3.13 (3.10+) | `server/requirements.txt` (livekit 1.1.21, livekit-api 1.2.2, sherpa-onnx 1.13.8, numpy, aiohttp) |
| Speech models | sherpa-onnx streaming zipformer en 20M, Silero VAD, Piper "Ryan" | `server/fetch_models.sh` (SHA-256 pinned, ~115 MB) |
| Token | Device key from `clu_voice_server.py add-device <id>` | Entered once in Clu |
| In the app | WebRTC Android SDK 150.7871.04 (BSD), OkHttp 4.12.0, protobuf-javalite 4.36.2, LiveKit protocol v1.53.0 (Apache-2.0) | All pinned in `scripts/setup_toolchain.sh` / Gradle |

No LiveKit Cloud account, sandbox, or paid plan is needed.

## 6. Tests

### Automated (build host, no phone)

| Test | What is real | Result |
|---|---|---|
| `LiveKitSettingsTest` (6) | Settings, path policy, token provisioning and validation, LAN policy | pass |
| `LiveKitBridgeTest` (6) | Clu's agent/memory/permissions through the bridge, sentence streaming, interrupt, "End of the line" | pass |
| `SignallingTest` (4) | Protocol encoding, failure handling | pass |
| `server/tests/test_e2e.py` (16 checks) | Real livekit-server, token server, agent (ASR/TTS), Python stand-in for the phone | **16/16** |
| `LiveKitEndToEndTest` (16 checks) | Real livekit-server + agent + **this Kotlin client on WebRTC** (webrtc-java, headless audio, continuous mic) + Clu core: scripted model, memory, and the **real llama.cpp engine** | **16/16**, all 6 utterances transcribed correctly |
| `OfflineConversationTest` via `scripts/test_offline.sh` | **Network disabled** (network namespace: loopback only, no route, no DNS): owner speech → sherpa-onnx streaming ASR → new conversation in Clu with the real llama.cpp engine → Piper TTS; LiveKit selected but unreachable → offline fallback; no HTTP request attempted | **pass** |

The end-to-end tests run only with a LiveKit server binary and the voice server environment:

```bash
./gradlew :lkclient:test --tests '*LiveKitEndToEndTest*' -Dclu.lk.server=<livekit-server> -Dclu.lk.python=<venv python> -Dclu.lk.models=<models>
scripts/test_offline.sh <venv python> <models>          # stop other Gradle daemons first (./gradlew --stop)
python server/tests/test_e2e.py <livekit-server> <models>
```

Defects found and fixed by these tests:
* a server import failure under isolated Python
* test cleanup hanging on a server that ignored SIGTERM
* a field/method name clash in the agent
* barge-in swallowing the first words of the interrupting sentence (fixed with a 1 s pre-roll)
* the last word lost at end-of-utterance (fixed with tail padding)
* the agent announcing "ready" before the device's audio actually arrived
* Interrupt events not raised when generation had already finished

One hypothesis was disproved by measurement and reverted: "a fresh primed recogniser per utterance improves accuracy". It made accuracy worse; `reset()` keeps 6/6 correct.

### Real device: not yet run

See `DEVICE_ACCEPTANCE.md` section N. Untested on hardware:
* the Android WebRTC audio path (capture with AEC, playout)
* barge-in with the phone's speaker and echo cancellation
* hotspot NAT behaviour
* battery, thermal and RAM in LiveKit mode
* the full Android UI flow

## 7. Measurements

All numbers are from the **x86-64 build container over localhost** with synthetic speech (Piper voices). None
are phone measurements. Ranges cover the final runs. Raw data: `measurements/livekit_server_e2e_host.json`,
`measurements/livekit_kotlin_e2e_host.json`.

| Metric (host) | LiveKit mode | Notes |
|---|---|---|
| Token request (Kotlin client → token server) | 24–26 ms | localhost |
| Join room (WebSocket + join response) | 138–214 ms | localhost |
| End of speech → transcript at Clu | 1.05–1.37 s | Includes the ASR end-of-utterance rule (≥0.65 s of silence) and 0.3 s tail padding |
| End of speech → first reply audio at the client | 1.14–1.62 s | Scripted model (instant reply); with a real 3B model add its first-sentence time |
| Transcript → first reply audio | 162–245 ms | Bridge + data channel + Piper first sentence + WebRTC |
| Barge-in: start of speech → agent stops/interrupts | 0.56–0.84 s (agent) / 0.97–1.07 s (seen at Clu) | VAD minimum 0.2 s + 0.1 s + audio path |
| Interrupt → reply audio silent at the client | 33–67 ms | |
| Offline conversation (no network): ASR + tiny-model reply + TTS | completes | Tiny random-weight model; not a speed figure |

| Metric | Value | Measured? |
|---|---|---|
| APK size | 65,324,677 bytes (+7.27 MB vs. 58,052,726; WebRTC native library 12.3 MB uncompressed) | yes (build) |
| RAM, battery, thermal in LiveKit mode | — | **not measured: no device** (DEVICE_ACCEPTANCE NB12) |
| End-to-end latency on the Pad 6 / Neo 9 Pro | — | **not measured: no device** (NB5) |
| Interruption on real speaker + echo cancellation | — | **not measured: no device** (NB7) |
| Offline-mode latency on device | — | **not measured: no device** (H5, NA3) |

## 8. Limitations

* **No automatic reconnect/resume.** If the connection drops, the session ends; the next activation starts a new
  one, or falls back to offline.
* **LAN / hotspot only out of the box.** Remote access needs TLS plus a public address or TURN in `livekit.yaml`
  (not tested).
* **Speech recognition quality** is that of the small 20M English model, the same as offline. A larger model can be
  set on the server (`Models.asr_dir`) without changing the app.
* **No speaker verification** over LiveKit. If you use speaker verification, LiveKit voices are treated as unknown.
* **One device per room**, by design (`clu-<device-id>`).
* The client is minimal: audio + data only, no video, no simulcast, no end-to-end encryption beyond WebRTC's
  transport encryption.
* The Android WebRTC path (`AndroidRtcFactory`, audio routing) is **compiled and statically verified, not run**,
  because there is no device or emulator here.

