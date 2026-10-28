# Clu's real-time agent (LiveKit) – architecture, setup, costs, test status

Build 1.3.1-prototype (versionCode 5). The app uses the official LiveKit Android SDK; the conversation runs in a
LiveKit Agents worker (`server/clu_agent.py`). The custom client of 1.1–1.2 is gone.

**Verification status (1.3.1).**

| What | Verified? | How |
|---|---|---|
| Complete conversation with a **real** language model: STT → LLM → TTS over a real LiveKit room, all four paths, follow-up context, interruption, stop, reconnects, server and model outages | **Yes, on the build machine** | `server/tests/e2e_agents.py` with llama.cpp `llama-server` running SmolLM2-135M-Instruct (real weights, real inference), Kroko STT, Piper TTS, real livekit-server and worker; a Python LiveKit client in the phone's role. 13/13 checks ([report](measurements/livekit-real-llm-e2e.json)) |
| The app's token client against the real token server | **Yes** (JVM) | `TokenServerConformanceTest`: unchanged `TokenProvisioner` vs. `clu_voice_server.py` process |
| The app's conversation state fed with what a real agent sent | **Yes** (JVM) | `LiveTraceReplayTest` replays the [recorded event trace](measurements/livekit-real-trace.jsonl) of the real-model run through `LiveConversation`, routed as `LiveKitSession` routes SDK events |
| The Android SDK joining a real room | **No** | No phone or emulator here (no KVM; no Android system images; the APK is arm64-only and the SDK needs Android's WebRTC). The SDK API usage was checked against the SDK's bytecode (see §7) |
| Anything on a physical device | **No** | Checklist: `DEVICE_ACCEPTANCE.md` section X |
| The cloud stack (LiveKit Cloud + Inference) | **No** | Needs a LiveKit Cloud project (credentials) and internet access to `*.livekit.cloud`; neither exists on the build machine (§2) |
| Answer quality | **Not with a useful model** | The only real model reachable here is 135M parameters: correct pipeline, often wrong or rambling answers (§5) |

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
* **Same agent behaviour as the device (since 1.6.2).** Every reply goes through `server/clu_controller.py` (via
  `Agent.llm_node`): the same research decisions, bounded keyless Wikipedia searches, evidence for the model, answer
  checks with one correction attempt and an attributed quote as last resort, and honesty when a search fails or finds
  nothing — one behavioural contract (`contract/agent_contract.json`) tested on both engines. `CLU_SEARCH=off` turns
  research off. See docs/MODEL_AND_ANSWER_QUALITY.md §4.
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

## 2. Server stacks (`CLU_STACK`) and where everything runs

| | `cloud` (default) | `local` |
|---|---|---|
| STT | LiveKit Inference `deepgram/nova-3`, language `multi` (English including Indian English, Hindi, mixed) | sherpa-onnx, Kroko streaming English, recognised per VAD segment |
| LLM | LiveKit Inference `openai/gpt-4.1-mini` | Any OpenAI-compatible server (`CLU_LLM_URL`, `CLU_LLM_MODEL`; default Ollama `llama3.2:3b`) |
| TTS | LiveKit Inference `deepgram/aura-2`, voice `zeus` | Piper "Joe" (or Kokoro) via sherpa-onnx |
| Turn detection | LiveKit multilingual turn-detector + VAD (after `download-files`) | Same, or VAD only (`CLU_TURN=vad`) |
| Interruption | Adaptive (ignores backchannels like "uh-huh") | VAD |
| Tested here | **No** (no credentials, no internet access to LiveKit Cloud) | **Yes**, with a real model (SmolLM2-135M) |
| Cost | Usage-billed, see §4 | Electricity only |

**Where each part runs, and what needs internet:**

| Part | Cloud stack | Local stack |
|---|---|---|
| Clu app | Phone | Phone |
| Token server `clu_voice_server.py` | Your computer (holds the LiveKit secret) | Your computer |
| LiveKit server (media/signalling) | LiveKit Cloud | Your computer (`livekit-server`) |
| Agent worker `clu_agent.py` | Your computer (or LiveKit Cloud agent hosting) | Your computer |
| STT / LLM / TTS | LiveKit Inference (cloud providers) | Your computer (sherpa-onnx + model server) |
| Phone reaches | token server (Wi-Fi or HTTPS) + `wss://<project>.livekit.cloud` | token server + `ws://<computer>:7880`, 7881/TCP, 50000–50100/UDP |
| Internet needed | Yes: phone and computer, for the whole conversation | **No**, if phone and computer share a network (home Wi-Fi or the phone's hotspot). One-time downloads (Python packages, models, `download-files`) need internet |

Nothing of the server engine runs on the phone. The local stack is "local" to *your computer*, not to the phone; the
only engine that runs entirely on the phone is *This device* (Settings → Connection), which is a separate, explicit
choice. Clu never switches between them by itself.

Override models with `CLU_STT`, `CLU_LLM`, `CLU_TTS` and `CLU_TTS_VOICE`. Endpointing: 0.4 s minimum and 3.0 s maximum
delay (`CLU_MIN_ENDPOINT`, `CLU_MAX_ENDPOINT`); VAD silence 0.45 s (`CLU_MIN_SILENCE`). Preemptive generation is on.
Worker settings for a personal server (`CLU_IDLE_PROCESSES`, `CLU_PROCESS_INIT_TIMEOUT`, `CLU_LOAD_THRESHOLD`):
`server/README.md`.

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
python clu_agent.py download-files            # optional: turn-detector model (otherwise VAD-only, logged)
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
* **Avoiding surprise costs.** The app connects while a Clu conversation screen is visible and disconnects 60 s after
  none is (app in the background or closed; added in 1.3.1 after finding that 1.3.0 stayed connected in the
  background). While connected it keeps one agent session for its single room; ✕ in voice chat ends it at once.

## 5. Measurements (build machine; real model vs. mock reported separately)

Environment: 4-core x86 Xeon container, no GPU; everything on 127.0.0.1 (no Wi-Fi or mobile network in the path);
local stack: Kroko STT, Piper Joe TTS, Silero VAD, VAD turn detection (the turn-detector files can't be downloaded
here); the owner's questions are synthesised with a second Piper voice and played into a real WebRTC microphone
track in real time. A phone adds network latency and audio device buffers on top of these numbers.

**Real model** — SmolLM2-135M-Instruct Q4_1 in llama.cpp `llama-server` (2 threads). Two runs:
**B (final code)** — [report](measurements/livekit-real-llm-e2e.json), [agent per-turn metrics](measurements/livekit-real-llm-turn-metrics.txt);
**A** — same code before fix #10, on the container's previous CPU with an AVX-512 build of llama.cpp (the container
was moved to other hardware between the runs; B uses a portable AVX2 build) — [report](measurements/livekit-real-llm-e2e-run-a.json),
[metrics](measurements/livekit-real-llm-turn-metrics-run-a.txt).
**Mock** — scripted replies, 300 ms first token, on the previous CPU: [report](measurements/livekit-mock-llm-e2e.json),
[metrics](measurements/livekit-mock-llm-turn-metrics.txt).

| Measurement | Real model, run B (final) | Real model, run A | Mock |
|---|---|---|---|
| Checks passed | **13/13** | 13/13 | 13/13 |
| Token request / join room | 12 / 134 ms | 9 / 111 ms | 10 / 119 ms |
| First connection → agent listening (cold worker) | **1.32 s** | 1.03 s (20.5 s before fix #1) | 1.09 s |
| Rejoin after the room closed → new agent listening | 1.33 s | 1.07 s | 1.07 s |
| Short reconnect (same session) → listening | 0.14 s | 0.13 s | 0.13 s |
| End of speech → first audio, phone side (2 spoken voice turns) | **2.90 s, 1.52 s** | 1.29, 1.15 s | 1.41, 1.78 s |
| … agent-measured `e2e_latency`, all voice turns | 1.03 / 1.37 / 1.92 / 2.81 / **28.2** s (median 1.92 s) | 0.81–2.04 s (median 1.20 s) | 1.02–1.96 s |
| Text sent → first reply text | **232 / 1,224 / 1,374 ms** | 148 / 556 / 669 ms | 327 / 671 / 786 ms |
| Text sent → first audio | **1,089 / 1,345 ms** | 526 / 639 ms | 667 / 749 ms |
| Owner starts talking over Clu → Clu silent | **486 ms** | 717 ms | — |
| Stop button → Clu silent | **53 ms** | 55 ms | — |
| Model server down → error event at the client | 27 ms after sending; after 3 failed turns the session closed and the agent left; a fresh agent in the same room received the history and answered 3.5 s after the model came back | 16 ms; 2.2 s | same |
| LiveKit server down → client notices / back → agent listening | 37.7 s / 27.5 s (Python SDK; the Android SDK's timing may differ) | 24.8 / 26.3 s | 33.1 / 27.5 s |
| Speech recognition | 3/3 questions word-perfect; "Stop. What is two plus two?" heard as "sop, what is two plus two?" | same | "supp …" |

**Where the time goes, agent-measured per voice turn (run B):**

| Stage | Range | Note |
|---|---|---|
| End of speech → final transcript (`transcription_delay`) | 750–1,075 ms | Largest share in normal turns. Recognition itself takes 80–185 ms (measured separately); the rest is the VAD deciding speech ended (0.45 s silence window) plus VAD lag under CPU contention ("VAD inference is slower than realtime") |
| LLM first token (`llm_ttft`) | 185–1,638 ms, one 6.1 s | A 135M model on 2 CPU threads; a 3B model on CPU or a cloud model is the next bottleneck |
| TTS first audio (`tts_ttfb`) | 236–693 ms, one **12.1 s** | Piper speaks sentence by sentence; when the model produced one endless run-on "sentence", the first audio waited for its end. This caused the 28.2 s outlier |

The largest share in normal turns is the end-of-speech decision, set by `CLU_MIN_SILENCE`; lowering it trades speed
for the risk of cutting the owner off, and it was **not** changed without device evidence. Streaming recognition
would save at most the ~100 ms recognition time. The ≈1 s goal is roughly met for typed questions (first audio
0.5–1.3 s) and missed for speech (median 1.2–1.9 s) **with a tiny model on loopback**; a realistic model and a phone
on Wi-Fi add to this. A model that writes long run-on sentences delays the voice badly (TTS waits for a sentence
end): a remaining limitation of the local stack. The cloud stack is designed for lower latency but is unmeasured.

**Answer quality (real model):** pipeline correct, answers from a 135M model are unreliable: in run B it recalled the name in 3 of 4 follow-ups and answered 4 of 4 capitals,
but said "Florence"/"Mumbai" in earlier runs, repeated an earlier answer instead of answering, rambled for a minute
on a story, and answered "two plus two" with an endless string of arithmetic. The mock run,
which answers deterministically from the last message, shows the right message and context reach the model each
time. For real use, run at least a 3B instruct model (local) or the cloud stack.

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
* Release APK (1.3.1): 85,100,556 bytes, up from 83,142,567 in 1.2.0. The previous WebRTC build was replaced by the SDK's
  prefixed one, and `liblivekit_uniffi`, JNA and the SDK classes were added.

## 7. Integration bugs found and fixed in 1.3.1

Found by tracing the runtime path, by the real-model runs and by reviewing the fixes; each was re-tested by the same run.

| # | Bug (evidence) | Fix |
|---|---|---|
| 1 | **Agent start took 20.5 s.** Warm processes load the speech models; with the framework's production defaults (up to 4 warm processes, 10 s init timeout) every process on a 4-core machine missed the timeout and was killed twice (`process exited with non-zero exit code -10`). The worker also marked itself "full" at 0.7 CPU load, which a local model reaches while generating | `num_idle_processes=1`, `initialize_process_timeout=60`, `load_threshold=0.95` (env-configurable). Measured after: 1.03 s |
| 2 | **Typed message silently dropped after a reconnect.** The agent framework ignores chat from a participant it has not yet seen join (`participant not found, ignoring text input` in the log); a question typed ~60 ms after rejoining got no answer at all, in two runs | App: a message is sent ≥1.5 s after joining and re-sent once if the agent shows no reaction within 4 s (`typed_resent` in Diagnostics). Re-test: answered, 0 re-sends needed |
| 3 | **Turn detector could never work** in a real deployment: imported lazily inside the job, after the worker had decided (no runners registered) not to start its inference process. The 1.3.0 test hid this by forcing VAD | Imported at module load when its files are present (and for `download-files`); VAD with a logged warning otherwise. *Not runtime-tested: the model files can't be downloaded here* |
| 4 | **Server-side failures invisible to the app.** STT/LLM/TTS errors stayed in the server log; the app showed "Thinking…" until a generic 30 s timeout. After 3 failures the session closed but the agent stayed in the room, looking alive | Agent publishes errors and session closure on `clu.events` and ends the job on a fatal close; the app shows "Clu's language model failed on the server: …" at once, and the watchdog gets a fresh agent. Re-test: error at the app 16 ms after sending; recovery after the model returned |
| 5 | **Cached token skipped the agent dispatch.** Reopening Clu within 10 minutes reused the token, so the server never dispatched an agent into the new room (15 s wait for the watchdog) | Fresh token on every connect |
| 6 | **Connected forever in the background** (agent minutes billed on LiveKit Cloud) | Disconnect 60 s after no Clu screen is visible |
| 7 | Token server's refusal reasons lost: wrong agent name, wrong room and throttling showed as "HTTP 400/403/429" or "rejected this device key" | The server's reason is shown; 429 explained. Checked against the real server (`TokenServerConformanceTest`) |
| 8 | Agent latency breakdown incomplete: transcription and end-of-turn delays were never sent (they belong to the owner's message) | Merged into the per-turn report shown in Diagnostics |
| 9 | Reply stopped with ■ not marked *interrupted*; HUD said READY while the agent was still starting | Marked; HUD shows STARTING |
| 10 | **Replacement agent started without context.** When a failed session's agent left and the watchdog brought a new one into the same room, the app thought it had already sent the history (found in code review of the outage path) | History is sent again to every new agent. Re-test (app-like flow: stay in the room, token request → new agent → history): the earlier turn reached the new agent's model request |

**Checked and found consistent (no change needed):** the Android SDK reads a transcript's sender from the same packet
field the agent sets (so the owner's words are never shown as Clu's), the SDK reserves only `lk.rpc_*` text-stream
topics (no clash with `lk.transcription`), RPC and chat topic names match the agent, the token claims the app checks
match what the server issues, and cleartext `ws://`/`http://` to a LAN server is permitted by the app's network
security config (restricted to private addresses in code).

## 8. Diagnostics on the phone

Settings → Connection → **Diagnostics** (not shown in the conversation screens):
* engine, connection and agent state, and the providers the agent reports (`clu.stack`, `clu.stt`, `clu.llm`,
  `clu.tts`, `clu.turn` participant attributes);
* the last error, in plain words with the failing part (token server, LiveKit server, microphone permission, speech
  recognition, language model, voice);
* latency measured on the phone (`token`, `connect`, `agent_ready`, `text_to_first_text`, `text_to_speaking`) and by
  the agent for the last turn (`transcription_delay_ms`, `end_of_turn_delay_ms`, `llm_ttft_ms`, `tts_ttfb_ms`,
  `speech_end_to_first_audio_ms`);
* the last 25 of up to 200 timestamped events (link changes, agent joined/left, state changes, errors, re-sends,
  idle disconnects).

**Copy** / **Share…** produce a text report for sending without a computer. It contains no conversation text
(typed messages are logged as their length only), and anything shaped like a device key, bearer key or token is
removed before it leaves the screen (`LiveDiagnostics.redact`, unit-tested). Agent error messages are provider
error strings, truncated to 200 characters.

