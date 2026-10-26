# Clu's real-time agent (LiveKit) – architecture, setup, costs, test status

Build 1.3.0-prototype (versionCode 4). This replaces the custom LiveKit client of 1.1–1.2 (removed: `lkclient/`,
`AndroidRtc.kt`, the RPC "brain bridge" and `clu_voice_agent.py`).

**What is and isn't verified.** The app compiles against the official LiveKit Android SDK and its unit/UI tests pass
on the JVM (Robolectric). The server side ran end to end on the build host (x86, no GPU) with a real
`livekit-server`, the real LiveKit Agents worker, real speech recognition and voice, a **mock** language model and a
Python stand-in for the phone. **Nothing in this document has run on a phone.** The device checklist is
`DEVICE_ACCEPTANCE.md` section X.

## 1. Architecture

```
 Clu app (Android)                                  Your server (or LiveKit Cloud)
 ┌─────────────────────────────┐   HTTPS   ┌────────────────────────────────┐
 │ Connection: device key ─────┼──────────▶│ clu_voice_server.py  :8089     │  LiveKit API secret lives
 │ (encrypted, Android Keystore)│◀── token │  device key → 10-min room token │  only here
 │                             │           │  + dispatches agent "clu"       │
 │ LiveKitSession              │           └────────────────────────────────┘
 │  official SDK 2.29.2        │  WebRTC   ┌────────────────────────────────┐
 │  mic track ─────────────────┼──────────▶│ LiveKit server (self-hosted    │
 │  lk.chat (typed text) ──────┼──────────▶│  livekit-server or LiveKit     │
 │  ◀── agent audio track      │           │  Cloud)                         │
 │  ◀── lk.transcription       │           └──────────────┬─────────────────┘
 │  ◀── lk.agent.state attr    │                          │
 │  RPC clu.interrupt / ...    │           ┌──────────────▼─────────────────┐
 │                             │           │ clu_agent.py (LiveKit Agents)  │
 │ LiveConversation (core)     │           │  Silero VAD + turn detector     │
 │  state, history, timeouts   │           │  STT → streaming LLM → streaming│
 │ Engine ─ screens            │           │  TTS, barge-in, transcripts     │
 └─────────────────────────────┘           └────────────────────────────────┘
```

* **The SDK alone is not an agent.** The Android SDK only moves audio, text and state. Listening, understanding,
  replying and speaking happen in `server/clu_agent.py`, which must be running somewhere.
* **Engines are an explicit choice.** Settings → Connection picks the *Clu server* or *This device* (the
  on-device model, recogniser and voice from earlier builds, unchanged). Clu never switches silently. If the server
  is unreachable, the screens say so and retry with backoff (1, 2, 4, 8, 16, then every 30 s). The device engine
  stays in the app, so offline support remains possible later.
* **Feature freeze.** In server mode the agent does not use the phone's memories, tools, characters or on-device
  model; those belong to the device engine. Background wake-word listening is off while the server engine is
  selected, because it belongs to the device engine and would otherwise answer offline without saying so.

### The four paths

| Path | How |
|---|---|
| Voice → spoken | Microphone track → STT → LLM → TTS → agent audio track |
| Voice → text | Same; the reply streams into the chat as `lk.transcription`. With 🔊 off, the app calls `clu.set_audio_output {enabled:false}` and the agent only sends text |
| Text → text | Typed message on text-stream topic `lk.chat` → LLM → transcript stream (🔊 off) |
| Text → spoken | Same, with 🔊 on |

Follow-ups share one `AgentSession`, so context carries across voice and text. On reconnect the app offers recent
history (`clu.load_history`); the agent ignores it if its session already has turns, so nothing is duplicated.

### Screen states come from real signals

| HUD / label | Signal |
|---|---|
| LISTENING (teal sweep + level ring) | Agent attribute `lk.agent.state = listening` **and** the microphone is published. Ring level = the SDK's `audioLevel` for the local participant, which the LiveKit server reports in active-speaker updates (a few per second, not per audio frame) |
| THINKING | `lk.agent.state = thinking`, or a typed message has been sent and no reply text has arrived yet (gives up after 30 s with an error) |
| SPEAKING | `lk.agent.state = speaking`; level = the agent participant's `audioLevel`. Animation stops when the state changes, e.g. on interruption |
| WAITING | Connected, but no agent in the room yet. After 15 s an error names the likely cause and the token server is asked to dispatch again |
| OFFLINE / CONNECTING / NOT SET UP | Connection state from the SDK (`Reconnecting`, `Disconnected`, …), shown dimmed with a red marker |
| IDLE / MIC OFF | Agent listening but microphone muted. No animation runs |

The HUD is drawn procedurally (`HexHudView`): a flat-top hexagon, translucent panels, teal circuit details and
markings, after the first reference image. It is redrawn rather than embedding the image, so it can animate per
state and so the reference artwork is not redistributed. The animation loop runs only in listening, thinking or
speaking.

## 2. Server stacks (`CLU_STACK`)

| | `cloud` (default) | `local` |
|---|---|---|
| STT | LiveKit Inference `deepgram/nova-3`, language `multi` (English including Indian English, Hindi, mixed) | sherpa-onnx, Kroko streaming English (recognised per VAD segment) |
| LLM | LiveKit Inference `openai/gpt-4.1-mini` | Any OpenAI-compatible server (`CLU_LLM_URL`, default Ollama `llama3.2:3b`) |
| TTS | LiveKit Inference `deepgram/aura-2`, voice `zeus` | Piper "Joe" (or Kokoro) via sherpa-onnx |
| Turn detection | LiveKit multilingual turn-detector model + VAD | Same (or `CLU_TURN=vad`) |
| Interruption | Adaptive (ignores backchannels like "uh-huh") | VAD |
| Needs | **LiveKit Cloud project** (Inference is a LiveKit Cloud service and does not work with self-hosted `livekit-server`) | Self-hosted `livekit-server` or Cloud; a computer that can run the LLM |
| Cost | Usage-billed, see §4 | Electricity only |

Override models with `CLU_STT`, `CLU_LLM`, `CLU_TTS` and `CLU_TTS_VOICE`. Endpointing defaults: 0.4 s minimum and 3.0 s
maximum silence (`CLU_MIN_ENDPOINT`, `CLU_MAX_ENDPOINT`). Preemptive generation is on.

## 3. Setup

### A. LiveKit Cloud (best quality; simplest)
1. Create a project at cloud.livekit.io. Note its URL (`wss://<project>.livekit.cloud`), API key and secret.
2. On a computer you control (it holds the secret):
   ```bash
   cd server && python3 -m venv .venv && . .venv/bin/activate
   pip install -r requirements.txt
   python clu_agent.py download-files          # turn-detector + VAD model files (one time)
   export LIVEKIT_URL=wss://<project>.livekit.cloud LIVEKIT_API_KEY=<key> LIVEKIT_API_SECRET=<secret>
   export CLU_PUBLIC_LIVEKIT_URL=$LIVEKIT_URL CLU_INTERNAL_LIVEKIT_URL=$LIVEKIT_URL
   python clu_voice_server.py add-device phone  # prints the device key ONCE
   python clu_voice_server.py serve             # token endpoint :8089 + agent worker
   ```
   The agent can instead be deployed to LiveKit Cloud's agent hosting (`lk agent create`). The token endpoint still
   has to run somewhere you control.
3. The phone must reach the token endpoint. On home Wi-Fi, `http://<computer-LAN-IP>:8089/token` works (plain HTTP
   is accepted only for private addresses). From outside, put it behind HTTPS.

### B. Fully self-hosted (no cloud account; `CLU_STACK=local`)
```bash
livekit-server generate-keys; cp livekit.yaml.example livekit.yaml   # paste key/secret
livekit-server --config livekit.yaml &        # 7880 signalling, 7881/tcp, 50000-50100/udp
./fetch_models.sh models                      # ~124 MB download, ~180 MB on disk, SHA-256 checked
ollama pull llama3.2:3b                       # or any OpenAI-compatible server (CLU_LLM_URL / CLU_LLM_MODEL)
export CLU_STACK=local CLU_MODELS=$PWD/models LIVEKIT_API_KEY=... LIVEKIT_API_SECRET=...
export CLU_PUBLIC_LIVEKIT_URL=ws://<LAN-IP>:7880
python clu_voice_server.py add-device phone && python clu_voice_server.py serve
```

### C. In the app
Settings → **Connection** → *Clu server* → **Token endpoint…** → **Device key…** (both need owner verification;
the key is stored encrypted) → **Test connection**. The status line shows *Connected – Clu's agent is listening*
when everything works. After that, the ⬡ button opens voice chat and the chat screen sends text.

### Secrets
The LiveKit API secret and any provider keys stay on the server. The app holds only its revocable device key
(`revoke-device phone`) and receives 10-minute tokens limited to its own room (`clu-<id>`). The tokens give no admin
rights and cannot create rooms.

## 4. Network, limits and costs

* **Network.** Server mode needs a working connection to the LiveKit server for the whole conversation: about
  50 kbit/s of Opus audio each way while speaking, plus signalling. With the cloud stack, the agent host also needs
  internet. **No offline operation in server mode.** Use *This device* for that.
* **LiveKit Cloud (cloud stack).** LiveKit's quotas page, as returned by a web search on 2026-10-26, lists for the free
  *Build* plan **1,000 agent session minutes** and **$2.50 of LiveKit Inference** per month, as **hard caps**: new
  requests fail once a cap is used up, so you are not charged. A minute of Clu conversation uses agent session minutes
  plus Inference for STT, LLM and TTS. Inference pricing varies by model, and $2.50 may cover far fewer minutes than
  the 1,000 session minutes. The LiveKit sites were not reachable from the build machine, so **check
  livekit.io/pricing for current allowances, paid-plan prices and per-model Inference rates before relying on any
  number here.** Paid plans bill overage. There is no fixed monthly cost unless you choose a paid plan.
* **Self-hosted (local stack).** No service charges. You need a computer running whenever you want to talk to Clu.
  CPU-only works; a GPU makes the LLM faster.
* **Avoiding surprise costs.** The app connects only while a Clu screen is open. It disconnects when you end the
  session, and while connected it keeps one agent session for its single room. An idle open chat screen still holds
  a session; leave the app or tap ✕ to end it.

## 5. Measurements (host only, labelled honestly)

`docs/measurements/livekit-agents-e2e.json`: build container (4-core x86 Xeon, no GPU), real livekit-server,
real Agents worker with the **local** stack (Kroko STT, Piper Joe TTS, Silero VAD, turn detector), **mock LLM**
(first token after 300 ms), Python stand-in phone over loopback.

| Check | Result |
|---|---|
| Agent dispatched and listening | pass (1.56 s after the phone joined) |
| 1 Voice → spoken reply | pass |
| 3 Voice → displayed text | pass (recognised "…train to **puna**…": 1 word error in 9) |
| 4 Text → spoken reply | pass; **text sent → first audio 826 ms** |
| 2 Text → text, audio off | pass (no audio frames received) |
| Follow-up keeps context | pass |
| Interruption | pass; agent audio stopped **804 ms** after the owner started talking, then it answered the new question |
| Reconnect keeps the conversation | pass; listening again 0.27 s after rejoining, no duplicated history |

End of speech → first agent audio, voice turns: **2,222 ms** measured at the stand-in phone. The agent's own
`e2e_latency` metrics were 2,088 / 1,576 / 1,450 ms. LLM first token was 306–365 ms (the mock), TTS first byte
159–436 ms. **The ≈1 s target is not met with the local stack.** Most of the time goes to the local recogniser,
which waits for the end of the utterance, plus VAD and endpointing silence. The cloud stack (streaming Deepgram STT,
streaming TTS) is designed for lower latency but **could not be measured here** because LiveKit Cloud is not
reachable from the build machine. Real phone latency adds the mobile network and audio device buffers. The
`clu.metrics` data from the agent is shown in Connection ("Last reply started … ms after you stopped speaking") and
logged to Diagnostics as `lk_*`, so it can be measured on the device.

Reading the report: the interruption check's `new_reply` field joins every agent transcript finished after the
cut-off, so it contains the end of the interrupted story followed by the new answer. They are two separate
streams, and the app shows them as two messages, with the first marked *interrupted*.

## 6. Android build notes

* The SDK and its Android-library dependencies come from Maven as AARs. `scripts/fetch_livekit_sdk.sh` downloads them
  with SHA-256 pins and extracts classes and arm64 natives into `toolchain/livekit/` (not committed). Plain-jar
  dependencies (OkHttp, protobuf-javalite, Dagger, …) come through Gradle.
* None of these AARs ship Android resources the code uses. The SDK's manifest adds a screen-share service and camera
  permission, which Clu does not need and does not merge.
* AndroidX is not available to this build, so two small source shims supply the only AndroidX classes the SDK calls:
  `ContextCompat.checkSelfPermission` and `NotificationCompat.Builder`. The SDK's default audio router needs the
  `audioswitch` library, which was unreachable. Clu passes its own `AudioHandler` (`CluAudioHandler`: communication
  mode and audio focus), so the SDK never constructs the default.
* Static check of the APK's dex: every referenced class resolves, except desktop-JVM-only paths in dependencies
  (log4j, AWT, `sun.misc`, Conscrypt, …), `audioswitch` (unused, as above) and `jni_zero.JniZeroJni`. The last one is
  only used by `JniZero.setJniClassLoader`, which nothing calls; it is also missing from upstream's own artifact.
  `verifyApk` checks all 376 JNI symbols against the bundled `.so` files.
* Release APK: 85,088,268 bytes, up from 83,142,567 in 1.2.0. The previous WebRTC build was replaced by the SDK's
  prefixed one, and `liblivekit_uniffi`, JNA and the SDK classes were added.
