# Project status — Clu prototype v1.0

_Last updated: 2026-10-28 (v1.5.5)_

## Current state

A complete, installable Android prototype (`dist/clu-release.apk`, release-signed) built and verified as far as
possible without a physical device. All milestones (A–F) are implemented.

**Current milestone: real-device validation. Feature freeze:** no new features until
[docs/DEVICE_ACCEPTANCE.md](docs/DEVICE_ACCEPTANCE.md) passes or each failure is written up as an agreed blocker.

| Evidence | State |
|---|---|
| Automated tests (JVM / Robolectric / host engines) | 442 total (core 380, app 62): 432 passed, 0 failed, 10 skipped by default (opt-in benchmarks and audits / model-dependent measurements / namespace-only offline test, run separately: pass); server E2E with a **real** model 13/13, with the mock 13/13 |
| Static checks (API 33, APK structure, signature) | pass ([RELEASE_VERIFICATION.md](docs/RELEASE_VERIFICATION.md)) |
| Security self-review | done, 5 issues fixed ([SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md)) |
| On-device acceptance (Pad 6 / Neo 9 Pro) | A2 failed on the Neo 9 Pro (crash on launch: ICU regex incompatibility). **Fixed, awaiting re-test.** Nothing else run yet. |

## 2026-10-28: Stage 3.4 — latency root cause: interrupted replies erased the prompt cache (v1.5.5, versionCode 12)

Details: [docs/STAGE3_4_LATENCY_ROOT_CAUSE.md](docs/STAGE3_4_LATENCY_ROOT_CAUSE.md). Stopping a reply (barge-in, Stop,
newer request) aborted the model's current step and the native code then cleared the whole prompt cache, so the next
reply re-read the entire conversation (host, 1B model: 1,277–1,873 tokens instead of ~172 after each interruption).
Fixed in the JNI bridge; per-turn prefill causes, queue time, TTS split and a numbers-only voice-turn timeline added to
the performance report. Host-verified only; **device improvement unverified**.

## 2026-10-27: Stage 3.3 — voice latency audit and targeted fixes (v1.5.4, versionCode 11)

Details: [docs/STAGE3_3_VOICE_LATENCY.md](docs/STAGE3_3_VOICE_LATENCY.md). From the owner's Neo 9 Pro performance report:
(1) long conversations lost llama.cpp's prompt cache every turn (history trimmed one message per turn) — now trimmed in
one step with a stable start; (2) speech synthesis and generation starved each other's CPU — spoken replies now generate
with 2 cores left free and synthesis uses 2 threads; (3) the model loaded in background processes and could be unloaded
on waking — now it loads when a screen is visible or voice starts. Diagnostics keep background, stopped, timed-out and
interrupted samples apart. Host-verified only; **device latency improvement unverified**.

## 2026-10-27: Stage 3.2 — unresponsive buttons fixed, hexagon HUD redesigned (v1.5.3, versionCode 10)

Details: [docs/STAGE3_2_UI.md](docs/STAGE3_2_UI.md). Settings entries (incl. Diagnostics) and the Connection engine
cards ignored taps on their text (selectable text swallowed the touch); fixed and covered by real touch-dispatch tests.
The hexagon has no text inside any more and shows two angular, icy-blue technological eyes that follow Clu's real voice
state. **Not yet tested on a device.**

## 2026-10-27: Stage 3.1 — security audit and release stabilisation (v1.5.2, versionCode 9)

Details: [docs/STAGE3_RELIABILITY.md](docs/STAGE3_RELIABILITY.md) (Stage 3.1 section). **Real-device acceptance is pending**
([docs/DEVICE_ACCEPTANCE.md](docs/DEVICE_ACCEPTANCE.md), now 15 plain-language checks); live providers unreachable here.
* Security: page reads and robots.txt connect only to the validated address (pinned HTTP/1.1 client, TLS SNI and
  certificate/host-name verification kept, no proxy, no reuse); robots.txt no longer bypasses the address checks; wider
  address policy (NAT64, 6to4, Teredo, reserved ranges, ports); cancelled page reads close their socket; API keys never
  follow a redirect to another host.
* Cancellation: no sources on an empty interrupted reply; no stale status line after an abnormal end.
* Diagnostics: performance list with units and "not measured yet", shareable report (version, device, timestamp, no private data).

## 2026-10-27: Stage 3 — reliability audit (v1.5.1, versionCode 8)

Details: [docs/STAGE3_RELIABILITY.md](docs/STAGE3_RELIABILITY.md). **No device and no live provider were reachable**
from the build environment, so this release fixes what probing found and makes device measurement possible:
* Fixed: hostile pages could freeze research for minutes (linear HTML extraction); Stop didn't stop a running search;
  DNS rebinding / odd numeric addresses could bypass the local-network block; records written while a sync scope was
  off were never sent; one Stage 2 test had silently never run.
* Added device timings to Diagnostics: stop-to-silence and return-from-background. Device checklist: §Z17–Z21 and §P.

## Added 2026-10-27: Stage 2 — research, contextual memory, phone ↔ tablet, tasks (v1.5.0, versionCode 7)

Details: [docs/STAGE2_RESEARCH_MEMORY_SYNC.md](docs/STAGE2_RESEARCH_MEMORY_SYNC.md). Device checklist: DEVICE_ACCEPTANCE §Z.
* **Research**: comparison queries per option, results merged across sites, pages read from different sites with
  address/robots/size/redirect checks, per-source "page read / snippet only (why)" and dates, web text cleaned of
  instructions, no actions after reading web content, model-written queries can't carry private details, offline
  default now "say so, then answer marked as possibly outdated".
* **Memory**: explicit and recent first among relevant memories, conflicting memories flagged (not silently merged),
  project context on "continue …", newest instruction wins, diary → memory shows an editable proposal.
* **Phone ↔ tablet**: the sync screen is reachable again (Settings → Phone & tablet); opt-in diary sync (schema v3);
  encrypted sync file for devices without a shared network.
* **Tasks**: multi-step requests run step by step with real per-step results, stop at unconfirmed actions, cancel
  cleanly, resume with "continue the task".
* **Not verified:** anything on a device; live search providers.

## Added 2026-10-27: Stage 1 — persistent, connected personal AI (v1.4.0, versionCode 6)

Details: [docs/STAGE1_SEARCH_MEMORY_DIARY.md](docs/STAGE1_SEARCH_MEMORY_DIARY.md). Device checklist: DEVICE_ACCEPTANCE §Y.
* **Web search** when a question needs current information (rules, not a model), through documented APIs: keyless
  Wikipedia + DuckDuckGo Instant Answers (default), Brave Search API (your key), or your SearXNG. Answers cite numbered
  sources shown as tappable cards; links come only from the provider. robots.txt is checked before reading pages.
  Failures are stated plainly; ordinary chat never searches and keeps working offline.
* **Long-term memory**: categories (preference, project, instruction, decision…), standing instructions in every
  reply, inferred insights as in-chat suggestions that need approval, "Remember this" on any message.
* **Diary**: separate from memory, offline, browse by date, search, edit, delete, "Keep as memory"; voice/text
  commands ("Add to my diary: …", "Save this conversation to my diary").
* **Personality**: explanation depth and proactivity settings; explicit feedback ("be more concise", "call me …")
  changes the matching setting. Measured on SmolLM2-135M: the model follows style instructions only loosely.
* **Android**: open specific settings screens with truthful results; toggle requests explain Android's limits.
* **Not verified:** any live search provider (unreachable from the build machine) and anything on the tablet.

## Added 2026-10-27: real-model integration and diagnostics (v1.3.1, versionCode 5)

Details: [docs/LIVEKIT_AGENT.md](docs/LIVEKIT_AGENT.md) (status table, §5 measurements, §7 bugs, §8 diagnostics).
* A complete conversation now runs end to end with a **real** language model (llama.cpp + SmolLM2-135M; the only
  real weights reachable from the build machine) over a real LiveKit room: all four paths, context, interruption,
  stop, reconnects, LiveKit and model outages — 13/13 on the build machine.
* Ten integration bugs fixed, among them: agent start 20.5 s → 1.0 s (worker process settings), typed messages
  silently dropped after a reconnect, a turn detector that could never run, server failures invisible to the app,
  and staying connected (billed) in the background.
* Minimal diagnostics in Settings → Connection with a redacted Copy/Share report.
* Still **not** verified: the Android SDK in a real room, anything on a phone, the cloud stack (no credentials or
  network access to LiveKit Cloud here).

Not yet verified on a device: DEVICE_ACCEPTANCE.md section X (X19–X23 are new).

## Added 2026-10-26: real-time agent on the official LiveKit SDK, hexagonal HUD (v1.3.0, versionCode 4)

Details, setup, costs and measurements: [docs/LIVEKIT_AGENT.md](docs/LIVEKIT_AGENT.md).
* The custom LiveKit client and the phone-side "brain bridge" are removed. The app uses the official LiveKit Android
  SDK 2.29.2. A LiveKit Agents worker (`server/clu_agent.py`) does STT, a streaming LLM, streaming TTS, turn
  detection and interruption.
* Two engines, chosen explicitly in Settings → Connection: *Clu server* or *This device* (the earlier on-device
  pipeline, unchanged). No automatic fallback.
* New screens: a voice chat built on a hexagonal HUD that shows only real states, an Aura-style text chat and a
  Connection screen. Everything else is hidden behind Settings (feature freeze).
* Host E2E with a real livekit-server and worker (local speech, **mock LLM**): all 8 checks pass (four paths,
  follow-up, interruption, reconnect). Text → first audio 826 ms. Voice end → first audio about 2.2 s with the local
  stack, so the 1 s target is **not met** there. The cloud stack was not measurable from the build machine.
* Fixed: the custom build logic (`buildSrc/.../clu/build/`) had never been committed because of an over-broad
  ignore rule.

Not yet verified on a device: DEVICE_ACCEPTANCE.md section X.

## Added 2026-10-26: voice-first agent (v1.2.0, versionCode 3)

The changes, measurements and limits are in [docs/VOICE_AND_AGENT.md](docs/VOICE_AND_AGENT.md):
* Two curated voices with previews: Joe (default) and Lewis (Kokoro, optional).
* Natural speech delivery: no markdown spoken, notation read as words, measured pauses.
* Speech-aware prompting for misheard words.
* Routes with travel modes, alternatives and ambiguity questions.
* Tool progress shown in the chat.
* Simpler Settings and navigation.

Not yet verified on a device: DEVICE_ACCEPTANCE.md section W.

## Added 2026-10-26: reply reliability and speed (v1.1.0, versionCode 2)

Root causes and fixes are in [docs/LLM_RELIABILITY.md](docs/LLM_RELIABILITY.md):
* Voice actions no longer cancel typed replies.
* Rotating the device or leaving the chat no longer kills a reply.
* Stop reaches llama.cpp.
* Timeouts are enforced and every request ends in a defined state.
* A cache-friendly prompt cut the time to the first word 4–8× in host measurements.
* Request diagnostics were added.
* Model files are integrity-checked and imports are atomic.

Not yet verified on a device: DEVICE_ACCEPTANCE.md section V.

## Added 2026-10-24: optional self-hosted LiveKit voice mode

Two explicit paths (superseded in v1.3.0 by [docs/LIVEKIT_AGENT.md](docs/LIVEKIT_AGENT.md)):
* **Offline mode (default):** unchanged on-device pipeline. Tested on the host with networking disabled.
* **LiveKit mode:** speech recognition and voice on your own LiveKit server (`server/`). Clu's model, memory, tools and
  permissions stay on the device. Tested end to end on the host (16/16). Falls back to offline automatically.

The Flutter starter was evaluated and **not** adopted as the app base (see the decision in that document).
Backup of the state before this work: branch `backup/clu-pre-livekit` (f82dec8). Nothing was removed.
On-device testing of both modes: DEVICE_ACCEPTANCE.md section N.

## Completed

* **Foundation:** custom Gradle APK pipeline (aapt2/D8/apksig) from pinned, checksummed sources; SQLite schema with
  migrations; reactive, validated settings registry (179 settings) with snapshots/rollback; diagnostics log.
* **Working core:** llama.cpp JNI engine (KV-cache reuse, streaming, grammar, cancellation), model registry with
  GGUF validation, import/download, load/unload/reload, idle unload, memory checks; chat with history, search,
  rename, archive, delete; memory system (BM25, provenance, manual inserter, diary, approvals, expiry, export/import).
* **Voice:** capture with AEC/NS, Silero VAD, KWS wake words from configurable phrases (tokenised on device), the
  leading-word rule, clap and noise-aware activation, primed streaming ASR, Android recognizers, Piper/Android TTS
  through one interruptible player (speed, pitch, volume, metallic effect), incremental speech, barge-in,
  termination phrase, speaker recognition, latency instrumentation.
* **Intelligence & actions:** single agent with deterministic command routing, tool loop with verified results,
  device tools (apps, alarms, timers, torch, volume, maps, dialer, settings panels, calendar, status), web/maps/train
  research (internet-gated, never fabricated), optional external AI, custom commands, owner/people permissions,
  PIN/phrase/device-lock verification, characters, preference learning (questions + A/B), proactive messages,
  improvement suggestions.
* **Continuity:** SAS-verified pairing, encrypted mutually authenticated sync with resumable change log and conflict
  policies, home-device model as remote engine, mDNS + hotspot gateway discovery, one shared entity id.
* **Delivery:** 225 automated tests (222 passed, 3 environment-gated/opt-in skipped by default; LiveKit E2E 16/16 and offline-without-network run separately and pass), API-33 static check, APK verification,
  generated settings register, documentation set, release signing, security review, device acceptance checklist.

## Current blockers

* No physical device/emulator in the build environment → hardware behaviour unverified on device.
* `dl.google.com` and `huggingface.co` blocked → no AGP/AndroidX, no real model for quality/speed testing.

## Known defects / risks (see docs/LIMITATIONS.md)

* Tuned-on-synthetic parameters (KWS threshold, leading-word offset, speaker threshold) need on-device calibration.
* Small ASR model accuracy; English-only offline speech.
* Signing key must be kept safe by the owner (SIGNING_AND_BACKUP.md). GGUF model files are not hash-pinned.

## Next actions

1. Install `dist/clu-release.apk` on the Pad 6 ([INSTALL.md](docs/INSTALL.md) §1) and work through
   DEVICE_ACCEPTANCE.md sections A–E; then the Neo 9 Pro; then sync (M).
2. Measure end-of-speech → first-audio latency, battery cost of background listening, thermals over 20 turns.
3. Calibrate wake-word threshold/leading offset and speaker threshold with real voices; enrol the owner.
4. Pair the devices over the hotspot; test sync interruption and tablet-hosted inference on real Wi-Fi.
5. Fix every FAIL from the checklist, rebuild with the same release key, re-test.
6. Only after acceptance: AGP/AndroidX build, OpenCL (Adreno) backend, multimodal model, larger ASR model, UI design
   pass.

## Build instructions

See README.md → *Building from source*. Last successful build and test run: docs/BUILD_LOG.md.
