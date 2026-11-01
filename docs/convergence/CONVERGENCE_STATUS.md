# CLU convergence — status after M6 + M7 (2026-11-01)

Legend: **T** implemented and tested (Linux; Windows APIs through injected runners) · **B** built, **not run on Windows** · **P** partial · **N** not implemented.

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
