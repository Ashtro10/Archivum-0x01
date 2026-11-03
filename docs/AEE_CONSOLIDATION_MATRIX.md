# AEE Consolidation Matrix — the earlier milestones mapped onto the evolution architecture

_Milestone 2. This document consolidates; it does not implement anything._

## 0. Which "eight milestones"

The repository holds **eight completed convergence milestones**, M1–M8, each with a specification in
`docs/convergence/CONVERGENCE_AUDIT.md` §6 and a report in `docs/convergence/M*_*.md`. They are, by a wide margin, the
only eight-milestone set that can be recovered, so this matrix uses them.

The roadmap's numbering and the executed numbering diverged:

| Executed as | Roadmap spec it actually implemented |
|---|---|
| M1 Benchmark | Roadmap M1 |
| M2 Host | Roadmap M2 |
| M3 Shell | Roadmap M3 |
| M4 Memory and knowledge | The "M4" of a separate memory brief, close to roadmap M10 |
| M5 Local intelligence | A separate brief; extends roadmap M2 and M8 inference |
| M6 Deployment | Roadmap M11 |
| M7 Agents and permissions | Roadmap M6 and M8 |
| M8 Real-time voice | Roadmap M4 and M5 voice |

The roadmap items that **never ran** are:

* M7 "Tools and computer use on Windows";
* M9 "Coding, testing, evaluation and change pipeline";
* the M10 parts beyond M4 (embedding model, Primus import, Obsidian vault).

M0 (preservation), M8.5 (conversation repair) and the new **Milestone 1: Reliable Foundation** (in progress, commit
`1fd8d98` plus uncommitted performance work) are included for completeness.

> **Missing:** if the owner's "eight milestones" refers to a new series that started with "Milestone 1: Reliable
> Foundation" and this brief as Milestone 2, then only those two briefs exist. The other six are **not available**, and
> this document does not invent them.

Classification key: **IV** implemented and verified · **II** implemented, insufficiently verified · **P** partially
implemented · **M** missing · **R** redundant · **U** architecturally unsuitable · **N** not currently necessary.

## 1. Per-milestone extraction

| # | Objective | Components (as built) | Dependencies | Existing implementation and evidence | Unresolved requirements | Overlaps | Risks | Acceptance criteria (original) | Class |
|---|---|---|---|---|---|---|---|---|---|
| M1 | Measure the target PC | `bench/Run-CluBenchmark.ps1`, report page | Owner runs it on Windows | Pack built; report renders from a fixture | **No run on the Ryzen AI 7 350** → no model or backend decision | Overlaps `desktop/ModelBenchmark` (in-app benchmark) and MS1 `PerformanceBaselineTest` | Every hardware number in the AEE is an estimate until this runs | Measured model and backend choice with headroom | P |
| M2 | CLU host on Windows | `desktop/` `HostMain`, `DesktopPlatform`, `ApiServer`, `ResourceGovernor`, model manager, JDBC SQLite | `core/`, JNI DLLs | Linux tests; memory-probe fix in `1fd8d98` | Windows run; idle RAM under 600 MB unmeasured on Windows | — | Windows-specific failures unknown | Conversation over the local API with a real model; idle under 600 MB; unload on timeout | II |
| M3 | Desktop shell | `resources/shell/*`, Playwright `ShellUiTest` | API | Chromium Playwright green | Window and tray on Windows | — | — | Chat, memory, research and approvals end to end in the window | II |
| M4 | Unified memory and knowledge | `MemoryService`, formation, consolidation, working memory, preferences, health, optional vectors | SQLite schema v5 | ~100+ core tests; desktop memory API | No bundled embedding model (`Embedder` seam is empty); archive not synced | Primus memory (legacy, unused) | — | Recall, formation with owner approval, lifecycle | IV (Linux) |
| M5 | Local intelligence | `InferenceService` (states, ids, queue, timeouts, GPU-fallback seam), `LlmService`, model registry, benchmark | llama.cpp JNI | Real-model tests (SmolLM2, Qwen3-0.6B) | Vulkan and GPU not measured; no capable model chosen | — | Model quality caps everything | Requests queued, cancellable, measured | IV (CPU, Linux) / P (GPU) |
| M6 | Deployment | NSIS installer, `clu.exe` launcher, bundled JRE, setup wizard, tray, autostart | M2, M3 | Static checks 24/24; wizard Playwright | Not run on Windows; **no update channel, side-by-side versions or automatic rollback** | Primus `deployment/` (legacy) | Single in-place install, a recovery gap | Fresh Windows user reaches a greeting without a terminal | II (install) / P (for AEE needs) |
| M7 | Supervised agents and permissions | `PermissionBroker`, `AgentToolRegistry`, `AgentService`, `Planner`, `AuditLog`, roles, Agents UI | M5 inference | `AgentsTest` (36) and others, injection tests | No OS sandbox for `proc.run`; no Android approvals; broker sits inside modifiable code | Roadmap M6 + M8; Primus security (legacy) | Trust anchor not isolated | No tool reachable except through the broker | IV (scope) / U (as AEE anchor) |
| M8 | Real-time voice on the PC | LiveKit supervisor, voice agent, STT/TTS, desktop and phone clients | M2, M5 | Real E2E tests on Linux | Windows audio, real phone | — | — | Measured latency, barge-in, no PyTorch | IV (Linux) / II (Windows, phone) |
| M8.5 | Conversation repair | Reasoning sanitizer, trace, corrections | M8 | Regression tests, before/after JSON | First text under 2 s not met on 4 vCPU | — | — | — | IV |
| MS1 | Reliable foundation | Router fixes, `ConversationMemory` seam, end-token filter, empty-input guard, trim-memory, perf harness | all | `1fd8d98`; `FoundationTest` (8) | Perf baseline run, 4 docs, full green check, APK: **unfinished** | — | AEE must not start before a green baseline | Evidence-based foundation | P (in progress) |
| (roadmap M9, never run) | Coding, testing, evaluation, change pipeline | Only `legacy/primus/astra/{coding,testing,evaluation,deployment}` (Python, 5.8k lines, unwired) | — | None in CLU | All | — | — | Proposed change → tests → evaluation → approval → deploy → rollback | M |
| (roadmap M7, never run) | Windows tools and computer use | Primus tools (legacy) | broker | None | All | — | High privilege | Scripted Notepad task, audited and reversible | M / N for AEE |

## 2. Merged responsibilities (what each AEE subsystem absorbs)

| AEE subsystem | Absorbs from earlier milestones | Reused component (keep, extend) | New infrastructure needed |
|---|---|---|---|
| A. System Observer | M2 governor, M5 inference states, M4 `MemoryHealth`, M8.5 `RequestTrace`, MS1 perf harness, `DiagnosticsLog`, `perf_samples` | All of these as *signal sources* | Baselines, anomaly rules, incident records, CPU/disk/thermal/crash probes, repository and test-state probes |
| B. Architecture Intelligence | — (nothing comparable exists) | Gradle module graph, `apiCheck` pattern (a build-time static check), the settings register generator | Source index, symbol and import graph, test map, schema map, reconciliation, provenance |
| C. Diagnostics | `ImprovementAdvisor` rules, RequestTrace stage timings | Advisor as the first rule set | Investigation records, hypotheses, reproduction harness, experiment runner |
| D. Research | Conversational research stack (search, pinned fetch, evidence, untrusted wrapping) | **Entire stack reused** | Engineering sources (arXiv API, repository metadata), license detection, research records |
| E. Planning | M7 `Planner` and `AgentService`, memory proposals pattern | Task persistence, states, restart recovery | Goal store, backlog, prioritisation, goal budget |
| F. Coding | M7 `fs.*` tools with trash and versions, `proc.run` | Tool and broker framework | Git worktree tool, patch tool, code-context retrieval, build tool |
| G. Build and test | `./gradlew check`, Playwright, `verify_installer.sh`, benchmarks | **All existing suites unchanged** | Runner service, result parsers, coverage-to-change check, flake detection |
| H. Evaluation | Before/after measurement practice, Primus evaluation ideas | Measurement JSON shapes | Evaluation records, comparison engine, complexity metrics |
| I. Deployment and recovery | M6 installer and launcher, `DatabaseBackup`, transactional migrations | Backup, verify and restore; data-folder separation | Supervisor (outside the JVM), side-by-side version slots, health gate, automatic rollback |
| J. Dev knowledge | M7 audit log, `tool_runs`, docs/measurements | Audit log for raw facts | `evolution.db` knowledge records and retrieval before tasks |
| K. Scheduler | `MaintenanceScheduler`, governor `taskBlockReason`, window-aware governor | **Pattern reused directly** | Idle detection, CPU/thermal/disk/working-hours gates, backlog selection, retry limits |
| Trust | M7 broker, categories, audit, injection defences | Categories and audit semantics | Policy outside the modifiable tree, OS-level sandbox, "evolution workspace" scope |

## 3. Duplicate or redundant features

| Duplicate | Where | Resolution |
|---|---|---|
| Three benchmark harnesses: `bench/` (PowerShell, M1), `desktop/ModelBenchmark` (M5), `PerformanceBaselineTest` (MS1) | — | Keep all three: they measure different things (raw llama-bench on Windows, in-app model choice, the end-to-end turn). The AEE's benchmark runner *calls* them and does not add a fourth. Record results in one shape (`benchmark_runs`, see data model). |
| Primus change pipeline (Python) vs the planned AEE pipeline (Kotlin) | `legacy/primus` | **R**: do not run Primus. Port its rules (no empty-evidence pass, snapshots, rollback records) as tests of the new pipeline. |
| Primus security policy vs `PermissionBroker` | `legacy/primus/astra/security` | **R**: the broker already covers it. Port only the adversarial path cases (case-folding, junctions) as tests when Windows runs. |
| `docs/ARCHITECTURE.md` (hand-written) vs the planned architecture index | — | The index becomes the source of truth. The Markdown keeps rationale only and is checked against the index (drift is reported, not auto-rewritten). |
| `ImprovementAdvisor` vs Observer + Diagnostics | `core/diag` | Becomes the Observer's first rule set; no parallel advisor. |

## 4. Deferred features (not currently necessary)

| Feature | Origin | Why deferred |
|---|---|---|
| Windows computer use (mouse, keyboard, screen) | Roadmap M7, Primus tools | The AEE needs files, Git and processes only. Computer use adds the highest-risk privileges for no evolution benefit. |
| Multi-agent marketplace or plugins with code | Primus `plugins/` | Out of scope: one entity |
| Embedding model for memory | Roadmap M10 | Useful for development-knowledge retrieval later. Start with SQLite FTS5 and BM25 (already the memory approach) and add vectors only on measured benefit. |
| Obsidian vault export, Primus memory import | Roadmap M10 | Unrelated to evolution |
| Hand gestures, marketing skill packs | Jarvis concepts | Unrelated |
| Self-improvement of model weights (fine-tuning) | — | Out of scope (no training) |

## 5. Dependency order that falls out of the matrix

1. **MS1 must finish** (green baseline, perf data). Otherwise the AEE inherits an unknown baseline.
2. **The M1 PC benchmark must run.** Coding-model feasibility on the Ryzen AI 7 350 decides whether autonomous coding is local or delegated.
3. Architecture Intelligence (new) and Observer (extends M2/M5/M4) can start in parallel. Both are read-only and low risk.
4. The verification pipeline and the supervisor with side-by-side deployment (extends M6) must exist **before** any code writing.
5. Diagnostics, coding and planning build on 3 and 4. The scheduler comes last.
