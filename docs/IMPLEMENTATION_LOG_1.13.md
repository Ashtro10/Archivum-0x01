# CLU 1.13 / PC 0.11 — implementation log

The first implementation pass of the master plan (`CLU_NEXT_ARCHITECTURE.md`, the 2026-11 plan). Its focus is the
Xiaomi Pad 6: the Windows interface on the tablet, with the PC as the brain. Every claim below is either tested here
(named test) or listed as unverified.

## What changed

### 1. The Windows interface on Android (tablets and phones)
- **The app opens CLU's PC window.** `ShellActivity` (now the launcher) shows the desktop shell in a WebView. The shell
  includes the Primus particle head, the boot sequence, motion, sounds and every view. It is packaged from
  `desktop/src/main/resources/shell` at build time (`app/build.gradle.kts` → `shellAssets`), so the two platforms can't
  drift apart.
- **One server per process.** `ShellHost` (core) is served by `LoopbackServer` (core), which listens on 127.0.0.1 only.
  - It applies the PC's Content-Security-Policy.
  - A one-time launch code (in the URL) is exchanged for a per-run token.
  - The page never sees the device key, and the PC never sees the page's token.
- **Paired with a PC:** every `/v1` call goes to the PC's phone endpoint (port 47822) with the paired device key.
  - The tablet therefore shows the PC's real conversations, memory, models and settings.
  - Streamed replies are passed through as they arrive.
  - On the PC, `ApiServer.handleRemote` refuses any request carrying an Origin header (web pages). It also refuses
    PC-only actions: window quit/restart/open, the launch-code exchange and the PC microphone session.
- **Alone, or with the PC out of reach:** `LocalApi` answers on the device.
  - It covers chat with the on-device model (streamed), conversations, settings and suggestions.
  - Everything else returns 503 with a plain explanation.
  - The top bar shows "Connected to your PC", "On this device" or "PC offline — on this device". The page reloads
    when that changes, so it shows the right source's data.
- **Voice:** `platform.js` (it does nothing on Windows) replaces the browser LiveKit client with the app's native
  session through `window.CluNative`.
  - That keeps one LiveKit connection, plus Android's echo cancellation and audio routing.
  - It is shown in the PC's own voice bar: states, live words as bubbles, Stop, Mute and End.
- **Pad 6 tuning:**
  - The Primus core draws at 1.25× instead of 2× on Android, about 2.5× fewer pixels on a 2880×1800 panel.
  - The window asks for 60 Hz.
  - The activity survives rotation and resizing without reloading.
  - The 1280×800 CSS-px landscape layout is the PC layout, unchanged. Phones get the existing narrow layout.
  - Touch targets are at least 44 px; text fields use 16 px text so focusing them doesn't zoom.
- **Classic view:** the native screens stay as the "Classic view" entry, and as the fallback when no system WebView
  is installed. From there, "CLU window" returns. Notifications and the crash-recovery restart open the window.
- **Shared JSON:** the message and streamed-turn JSON (`ShellApi`) is now shared by the PC and the tablet.

### 2. Intelligence
- **Calculator** (`CalcTool`):
  - Spoken forms: "15 percent of 240", "12 times 7", "2 to the power of 10", "square root of 81".
  - Functions (sqrt, cbrt, abs, round, floor, ceil, ln, log, exp, sin/cos/tan in degrees) and the constants pi and e.
  - Output has no float noise (0.1 + 0.2 = 0.3) and large numbers are grouped.
  - Limits on length and nesting.
  - The router sends arithmetic to it, typed or spoken, only when the whole remainder parses. Dates, phone numbers,
    "40%" alone and "covid-19" don't.
- **OpenAI-compatible endpoint** on the PC (`OpenAiEndpoint`), at `http://127.0.0.1:<port>/openai/v1`:
  - Serves `chat/completions` (with streaming) and `models`.
  - Uses CLU's own queue, timeouts and governor, so there is never a second copy of the model.
  - Authentication: a per-install local API key (`<data>/local-api-key`), shown to the window at `GET /v1/openai`.
  - It is never reachable from other devices.
  - Tools, images and n > 1 are refused, not faked.
  - This is the hook for OpenCode and other local tools.

### 3. Voice
- **Speech chunks keep phrases whole when the model pauses.** When the model goes quiet mid-sentence (a slow token,
  not yet a tool call), the early flush now ends the chunk at a clause boundary (`, ; : —`; not a thousands
  separator), so the voice falls in the right place.
  - It cuts between words only after a long silence (≈1 s, e.g. a tool call) or a long clause (10+ words).
  - When playback is about to run dry, it still cuts at a word (never on a function word), because a gap is worse.
    The existing gap limits in `StreamingSpeechTest` enforce this.

## Tests (this pass)

| Test | What it proves | Result |
|---|---|---|
| `LoopbackServerTest` (core, 5) | requests, bodies, server-sent events, limits, malformed input, loopback only | pass |
| `ShellHostTest` (core, 5) | CSP, launch code once, token, local streamed chat, PC forwarding with the device key (no token or Origin leaves the device), fallback when the PC is unreachable | pass |
| `RemoteApiTest` (desktop) | the PC serves paired devices; wrong or no key, any Origin and PC-only actions are refused | pass |
| `ShellActivityTest` (Robolectric) | the launcher is the window; WebView settings; the packaged shell is served; the session works; links leave the app | pass |
| `TabletShellTest` + `tablet.ui.js` (Chromium, Pad 6 viewport 1280×800 @2.25, touch) | both modes: the PC design and layout unchanged, Primus running at 1.25×, the link badge, a streamed typed reply, native voice in the voice bar, Classic view, no script errors; paired mode runs the full chain tablet → ShellHost → PC phone endpoint → PC API, and the conversation is on the PC, not the tablet | pass (screenshots in `desktop/build/tablet-ui/`) |
| `CalculatorTest` (core, 4) | exact results, spoken forms, refusals, routing only arithmetic | pass |
| `OpenAiEndpointTest` (core, 3) | request mapping, completion, streaming chunks + `[DONE]`, refusals, 429/503 | pass |
| `AdaptiveChunkerTest` (+1) | early flushes end at a clause; long clauses still cut; "1,500" is not a clause | pass |

| `OpenAiRouteTest` (desktop) | local key or window token only; the window learns the key; it persists; no model → 503 in OpenAI form | pass (it caught a real bug: the 401 path left the response open and the client hung; fixed) |
| Full suites | core 720, app 99 (Robolectric, SDK 33–35), desktop (incl. real voice end-to-end and browser tests) | 0 failures |

**One regression caught and fixed during this pass:** clause-first flushing on the *underrun* path (playback about to run
dry) let a slow model starve playback, a 2.4 s gap in `StreamingSpeechTest.modelSlowerThanSpeechKeepsGapsShort`. The
gap limits stay as they are. The underrun flush keeps cutting at a word (never on a function word), and clause-first
applies only when the model pauses.

## Not verified (no hardware here)
- **Pad 6 performance** has not been measured: frame rate, Primus GPU cost, WebView memory and battery. The render
  scale and 60 Hz cap are design choices from the panel and GPU specification, not measurements. The checklist is in
  `DEVICE_ACCEPTANCE.md` §T.
- **Real hardware:** voice through the native bridge on a real tablet (the bridge is tested with a scripted native side
  only), and Wi-Fi forwarding from a real tablet to a real Windows PC.
- **PC hardware:** GPU and NPU were not verified (no access to the PC). No hardware-specific choices were made.

## Not done in this pass (next)
- **Model slots.** The fast/heavy slots with a `llama-server` sidecar and escalation (plan §3.2, M1) are not built.
  The OpenAI endpoint and the calculator are the first M1 pieces.
- **Voice.** Adaptive endpointing (450–900 ms) and the final-pass STT (Parakeet or Whisper after the endpoint) are not
  built. Both need the PC voice agent's VAD loop reworked and measured with real audio.
- **Research engine adapters** (OpenAlex, arXiv, Stack Exchange, HN and others) with multi-source disagreement. Live
  tests are blocked here: the network proxy refuses Wikipedia, DuckDuckGo and Brave.
- **RAG embeddings with provenance:** needs an embedding model download and a llama.cpp embedding check.
- **Tablet features:** gestures (Hands) and capability experiences on the tablet. Both are PC-only for now.
