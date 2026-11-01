# CLU convergence — status after M8 (2026-11-01)

Legend: **T** implemented and tested (Linux; Windows APIs through injected runners) · **B** built, **not run on Windows / a phone** · **P** partial · **N** not implemented.

## M8.5 — conversation repair (details: [M85_CONVERSATION_REPAIR.md](M85_CONVERSATION_REPAIR.md))

| Area | State | Evidence |
|---|---|---|
| Per-turn lifecycle trace (speech → STT → route → memory → model calls → first text/audio → done) in API, voice bar and Status | **T** | `VoiceConversationTest`, `/v1/requests` |
| Reasoning models: reasoning switched off in the prompt, streaming sanitiser before UI/TTS/history, fail-safe | **T** (Qwen3-0.6B real; unit) | `ReasoningTest`, `ConversationRepairTest`, `VoiceConversationRealTest` |
| Warm model during voice, non-blocking state updates, thread count | **T** | `VoiceTurnRulesTest`, traces |
| Corrections ("I said …"), misheard names from context, follow-up research, stop phrases | **T** | `SpeechCorrectionsTest`, `ConversationRepairTest`, `VoiceRecognitionTest` |
| Simple turn: first audio 2.8 s, first text 2.6 s (was 3.9–15.4 s; turns finished in 4–16 s, were 21–96 s) | measured on 4 vCPU | `docs/measurements/m85_*` |
| First visible text under 2 s | **not met on 4 vCPU** (2.6 s); expected on the 8-thread Ryzen, not measured | — |

## M8 — real-time voice (details: [M8_REALTIME_VOICE.md](M8_REALTIME_VOICE.md))

| Area | State | Evidence |
|---|---|---|
| Self-hosted `livekit-server` 1.9.12 (native exe, no Docker, no Cloud), generated keys, loopback/private-address binding, watchdog, idle stop | **T** Linux / **B** Windows | `VoiceTransportTest`, `VoiceConversationTest` |
| PC voice agent on LiveKit's Rust SDK: VAD, streaming Kroko STT (+ optional Whisper), shared conversation manager (M4 memory, M5 inference), streaming TTS, barge-in, 4 listening modes, wake words, farewells, metrics | **T** | `VoiceConversationTest` (5), `SpeechEnginesTest` (6) |
| Real local model through the voice path (SmolLM2-135M): speech end → first audio 2.15–2.39 s on 4 vCPU | **T** (measured) | `VoiceMeasureTest`, `docs/measurements/m8_voice_host_smollm2.json` |
| Desktop window voice (Talk, live words, states, mic indicator, mute, stop, PTT, reconnect) | **T** Chromium / **B** Edge | `VoiceUiTest` |
| Settings → Voice (voices with size/licence/source, real preview, consented verified downloads, shared default, speed/volume/language/mode/timings/wake words/farewells/pronunciations, devices, phone access + pairing) | **T** (downloads not run: GitHub blocked here) | `VoiceUiTest` |
| Phone as LiveKit client of the PC (pairing, LAN token endpoint, own-room only, short-lived tokens, refusal paths) | **T** protocol / **N** on a real phone | `aPairedPhoneGetsATokenFromThePCAndTalksToCLU` |
| Old Python LiveKit agent server and LiveKit Cloud path | **removed** | `server/` deleted after the replacement passed |
| Installer 0.7.0 with voice (DLLs, livekit-server.exe, models; Whisper optional) | **B** (static checks 24/24) | `scripts/verify_installer.sh` |
| Voice on the Ryzen AI 7 350 / Windows audio / firewall / real phone | **N** | owner checklist in M8 §8 |

## Test totals of the final `./gradlew check` (M8)
core 606 tests (13 skipped: opt-in real-model/benchmark), desktop 104 (3 skipped: opt-in real-model/measurement), app 74 — **0 failures**; Android API-level check and ICU regex check pass; both Playwright runs (`ShellUiTest` M3–M7, `VoiceUiTest` M8) green. The first full run found two real problems, both fixed: the inference queue could start a later request before an earlier one (admission order now enforced), and a wake-word test fed the recogniser unprimed audio (now fed the way the agent does). Installer static checks 24/24.

---

# Status after M6 + M7 (kept for reference)

| Area | State | Evidence |
|---|---|---|
| Per-user Windows installer (NSIS), launcher, bundled Java runtime, uninstaller keep/remove data, upgrade path | **B** (static checks T: `scripts/verify_installer.sh`, 18/18) | `dist/installer/CLU-Setup-0.6.0.exe` |
| Prerequisite detection (64-bit, Win10+, AVX2, Edge) | **B** | installer script |
| First-run guide (compatibility, model choice, consented resumable verified downloads, settings, real inference test, permissions step, ready) | **T** | Playwright against real host; `SetupTest` |
| Model management UI (scan, register, download, select, load/unload, context/threads/GPU, benchmark, remove registration, confirmed delete of downloaded files) | **T** | `ShellUiTest`, `SetupTest`, `ModelRoutesTest` |
| Tray, close-to-tray, single instance, restart, quit, start-with-Windows (default off, per-user Run key) | host logic **T**; tray/window/registry on Windows **B** | `SetupTest`, `ShellLauncher` |
| Memory ceiling includes the Edge window | **T** (marker process, governor arithmetic) | `SetupTest` |
| Permission broker A/B/C, grants, expiry, revocation, approvals, denial memory | **T** | `AgentsTest` (36), `AgentApiTest` (6) |
| Tool registry with schemas, validation, timeouts, cancellation | **T** | `AgentsTest` |
| Orchestrator, roles, planner under grammar, bounded concurrency, recovery, restart safety, loop prevention | **T** | `AgentsTest`, `AgentSettingsTest` (7), `AgentIntegrationTest` (4, real llama.cpp) |
| Audit: append-only, hash chain, redaction | **T** | `AgentsTest` |
| Prompt-injection resistance (obedient-model worst case) | **T** | `AgentsTest`, `AgentApiTest`, `ShellUiTest` |
| Agents UI: Tasks / Agents / Permissions / Activity / approval cards / `/task` | **T** | `ShellUiTest` |
| Android approval UI, voice → tasks | **N** | — |
| Code signing, GPU (Vulkan) run, measurements on the Ryzen AI 7 350 | **N** | — |

## Test totals of the final `./gradlew check` (this session)
core 608 tests (15 skipped: opt-in real-model/benchmark), desktop 90 (2 skipped: opt-in), app 74 — **0 failures**; Android API-level check, ICU regex check, settings register: pass (after fixing three real findings the checks caught: `Path.of`, `Process.descendants` unavailable on Android; a regex ICU rejects). Playwright run 3× green after fixing a pre-existing race in the memory-detail test.

## Measured here (Linux container, tiny 80 MB random model — not the target PC)
Host idle: **115 MB RSS, 0.2 % of one core**; with the model loaded **282 MB**; after unload **200 MB**; first turn 1.8 s (includes load). With a real 3B Q4 model expect ≈ 2.4 GB for the model at 4096 context (catalog estimate) on top of ~0.2 GB host + the Edge window; none of that is measured on Windows.

Earlier milestones' limits (M4/M5) still apply; see their documents.
