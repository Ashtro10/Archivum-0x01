# CLU Convergence — Phase 1: Audit, Architecture and Roadmap

_2026-10-31. Audit only: no code was merged, moved or rewritten. Every statement about a repository comes from reading
its source or running its tests in this session; where something could not be verified, this document says so._

**Status (2026-10-31):** you approved the plan and the §9 recommendations.
* **M0 done:** see [M0_PRESERVATION.md](M0_PRESERVATION.md).
* **M1 benchmark pack delivered:** see [M1_BENCHMARK.md](M1_BENCHMARK.md). It is waiting for a run on your PC.
* **Scope changes in M1:** speech benchmarks move to M4; CLU's Windows JNI build moves to M2.

---

## 0. Sources inspected (pinned)

| Project | Repository | Commit audited | Notes |
|---|---|---|---|
| **CLU** | github.com/Ashtro10/Archivum-0x01, branch `claude/clu-android-ai-build-5cspi2` | `1899d1d` (Clu 1.8.2) | Full history (130+ commits). |
| **Primus / Astra** | github.com/Ashtro10/astra | `16764d2` ("Initial commit - Astra project", 2026-10-31) | **One commit only**: the 41-milestone history is not in Git. `docs/adr/` is referenced 20+ times but is not in the repository. |
| fullstack-agent | github.com/jaredrhod/fullstack-agent | `5bb159f` | An installer: Markdown instructions + start/update scripts. No runtime code. |
| ai-memory-vault | github.com/jaredrhod/ai-memory-vault | `659bba9` | Markdown templates only (CC BY-SA 4.0). |
| backtalk | github.com/jaredrhod/backtalk | `84b3a6c` | 10 Python files (AGPL-3.0). |
| ai-visualizer | github.com/jaredrhod/ai-visualizer | `6921e1d` | HTML/JS faces + stdlib Python server (AGPL-3.0). |
| barehands | github.com/jaredrhod/barehands | `eb23bed` | One HTML page + stdlib server; MediaPipe from CDN (AGPL-3.0). |
| ai-marketing-skills | github.com/jaredrhod/ai-marketing-skills | `47b68a5` | Markdown prompt packs (CC BY-SA 4.0). |
| jarvis-main (inside Astra) | bundled copy of "JARVIS Core — Nebula Fire Spirit" (hnturk25) | — | three.js particle face, MIT. |

No source repository was missing; none needed to be requested. The only missing material is Astra's ADR folder and its
Git history (see §2.6).

---

## 1. CLU (Archivum-0x01)

### 1.1 What it is

An Android assistant whose **brain is a platform-independent Kotlin library**:

| Module | Language | Size | Role |
|---|---|---|---|
| `core/` | Kotlin (JVM 17), **no Android imports** | 14.5k lines main | Persona, settings registry (211 settings), SQLite storage + migrations (schema v4), conversations, **agent controller** (routing, research decision, claim checks, follow-up resolver), memory + historical archive (people, documents, projects, time), diary, research (Wikipedia/Brave/SearXNG, pinned fetching, relevance), hazards/weather, tools, security (people, roles, auth window), sync engine (HLC, tombstones, encrypted bundles), LiveKit bridge, voice-pipeline logic. Depends only on kotlinx-coroutines and kotlinx-serialization. |
| `app/` | Kotlin, Android | 8.9k lines | Activities, Android voice I/O (sherpa-onnx VAD/ASR/TTS/KWS), services, Android tools (alarms, apps, panels), UI. |
| `native/` | C/C++ (JNI) | llama.cpp, whisper.cpp | In-process inference; built for arm64 Android and x86-64 host (Linux). |
| `server/` | Python | 3.8k lines | LiveKit voice worker + a **second copy** of the conversation controller (kept in parity with core through a shared JSON contract). |

### 1.2 Status (verified this session)

* Stage 4 complete (1.7.0), memory archive 1.8.0/1.8.1, Stage 4 completion 1.8.2.
* Tests, from scratch: core 488 (13 opt-in skipped), app 74, server 9/9, offline test passed; 0 failures.
* Signed release APK, versionCode 19.
* **Stage 5:** no plan exists in the repository. Earlier instructions described it as a self-improvement system and
  deferred it. The "nine remaining milestones" are therefore not recoverable from source and are superseded by this
  convergence plan.

### 1.3 Reusable for the PC (decision basis)

* `core/` runs on a desktop JVM today: every core test runs on the host JVM with real SQLite. Moving it to Windows
  needs only a desktop SQLite driver (already used in tests) and Windows builds of the two JNI libraries.
* The local LiveKit stack (`livekit-server` on the PC + worker + Android client) was verified end to end on the build
  machine with a real local model (13/13 checks). This is exactly the phone↔PC voice architecture the brief asks for,
  without LiveKit Cloud.
* The sync engine already pairs phone and another device over the LAN with encryption. The PC can be the "home"
  device.

### 1.4 Defects and debt

* Two controllers (Kotlin core + Python `server/clu_controller.py`) that must be kept in parity: convergence should
  leave **one**.
* The local model on the build machine (SmolLM2-135M) is far too weak; answer quality is limited by the model, not the
  pipeline.
* Archive tables (people, documents) aren't synced between devices.
* No embeddings (retrieval is BM25 keyword ranking).
* Never tested on a physical device in this project's sessions.
* The signing key lives only in the build container (`/root/clu-build/release-key`); see §9.

---

## 2. Primus / Astra

### 2.1 What it is

"A local-first personal AI operating system" in Python 3.11+, about 39k lines (`astra/` package + `ui/`), with a
constitution that matches the brief's principles: local-only, no paid APIs, human approval before permanent changes,
memory survives model changes.

### 2.2 Architecture (from source)

| Package | Real content | Wired into conversation? |
|---|---|---|
| `presentation/orchestration.py` (3.4k lines) | `ConversationOrchestrator`: composite turn plan, memory retrieve/persist, user facts, selected engines | **Yes** (B-039) |
| `memory/` | SQLite + FTS5 store, FAISS vector index, `MemoryIndexer`, verification states, user-fact boundary | Yes (retrieve/persist) |
| `knowledge/` | SQLite knowledge store, keyword classifier, `ingest_text` | Partly (talk-path ingest) |
| `planning/` | Planning engine (backlog with priority/impact/effort/risk), goal manager | Selected by the orchestrator when justified |
| `research/` | Local sources + SSRF-gated HTTP/HTTPS document retrieval, DuckDuckGo HTML | Selected when justified |
| `core/` | Controller, agent loop (2.2k lines), agent decisions, skills, persistent work, input/perception hosts | Agent loop is separate from chat |
| `tools/` | Tool gate + allowlists. **Chat:** `read_text`, `read_project`. **Agent loop:** also `search_files`, `write_text`, `run_python`, `launch_application`, `inspect_process`, `capture_screen`, `inspect_active_window`, `mouse_action`, `keyboard_action` (real Windows code: `user32` via ctypes, Pillow screen grab) | Agent loop only |
| `security/` (871 lines) | Deny-by-default policy: path rules, temporary writes, process execution, secret names | Gates tools and processes |
| `coding/` | Proposal-only coding agent (complete file replacements, never applies) | No (by design) |
| `testing/` | Proposal workspace, copied-subset workspace, process runner, real unittest runs → honest `TestReport` | No (by design) |
| `evaluation/` | Judges proposal + test report; baselines | No |
| `deployment/` | Deploy only with a human `ApprovalRecord`; version snapshots; approval-bound rollback; Git history store | No |
| `plugins/` | Descriptor registry; external execution disabled | No |
| `reasoning/` | llama-cpp-python backends (chat, embeddings, a "deep" model, vision) | Yes |
| `ui/` | Local web UI (stdlib HTTP server bound to 127.0.0.1) + jarvis-main face; voice: faster-whisper STT, Kokoro TTS, an RVC "Optimus" voice (torch) | Yes |

### 2.3 Tests (run this session, Linux, no GPU)

`python -m unittest discover -s tests -t .`: **1,585 tests, 25 failures, 49 errors, 12 skipped.** The README states
"1,353 collected, 0 failures". The difference was classified:

| Cause | Count | Real defect? |
|---|---|---|
| `faiss` not installed here | 40 errors + ~6 vector-count failures | No (environment) |
| `docs/adr/*.md` missing from the repository (tests check ADR files exist) | 8 errors | Repository incomplete |
| `ui` import path / `rvc_py` not installed | 3 errors | No (environment) |
| Windows case-insensitive filesystem assumptions ("DATA" == "data") | ~3 failures | Platform: must re-run on Windows |
| Symlink tests (running as root on Linux) | 4 failures | Unverified: must re-run on Windows |
| **UI breaks Astra's own architecture contract** (`ui/web_api.py` imports memory contracts; web UI opens SQLite directly, which ADR-0006 forbids) | 3 failures | **Yes** |
| Stale milestone assertions (B-035/B-037 "complete" checks) | 4 failures | Yes (documentation drift) |
| Frozen `Primus.exe` not present (tests expect a built exe) | 2 failures | Build artefact not in Git |
| Memory "revive refuted row" / semantic merge | 2 failures | Likely embedding-dependent; unverified |

**Verdict:** a substantial, carefully gated codebase whose own audit documents (`runtime-reality.md`,
`runtime-gap-analysis.md`) are unusually honest about what is wired. It needs a run on the Windows machine with its
dependencies before its test status can be confirmed.

### 2.4 Model and hardware fit (important)

* Configured for an **NVIDIA GPU**: llama-cpp-python CUDA 12.4 wheel, `gpu_layers = 99`, documented `cublas64_12.dll`
  workarounds.
* The target PC has an **AMD Radeon 860M**. CUDA builds will not run there; this path must be replaced (llama.cpp
  Vulkan or CPU backend, to be benchmarked).
* Default model `Huihui-Qwen3-8B-abliterated-v2.Q2_K`. An "abliterated" model has its refusal training removed. That
  is a deliberate choice you should confirm (§9). Q2_K is also a very lossy quantisation.
* The RVC "Optimus Prime" voice pulls in PyTorch (several GB of RAM). Incompatible with the 16 GB budget as a resident
  component.
* Hard-coded paths (`C:/Users/ASUS/...`, `D:/Projects/...`) in `astra.toml`.

### 2.5 Duplicates with CLU

Both have:
* a conversation orchestrator;
* SQLite memory with retrieval;
* persona settings;
* research with SSRF/robots protection;
* local llama.cpp inference;
* Whisper STT and Kokoro TTS;
* a local web/phone UI.

CLU's versions are deeper in conversation (follow-ups, claim checks, honest failure, memory archive, sync). Primus's
are deeper in **agentic infrastructure** (tool gate, computer use, change pipeline, planning, goals, knowledge
classification, embeddings).

### 2.6 Integration conflicts

* **Language:** Kotlin/JVM (CLU) vs Python (Primus).
* **Memory schemas:** two different ones.
* **GPU:** Primus assumes NVIDIA.
* **History:** Primus's Git history and ADRs are absent, so its design rationale survives only in `session-handoff.md`
  and `backlog.md`.

---

## 3. Jared Hodes' Jarvis ecosystem

| Piece | What the code actually is | Runtime brain | Paid? | Reusable for CLU |
|---|---|---|---|---|
| fullstack-agent | A Claude Code installer wizard (Markdown + scripts) | Claude Code | **Yes**: Claude subscription required ("Runs on: Claude Code only") | Concept only: one guided first-run setup |
| ai-memory-vault | Markdown templates telling Claude how to keep an Obsidian vault | Claude Code | Yes (Claude) | **Concepts:** plain-text vault the user can read; *AI priming* (per-job note sets); daily notes; self-updating profile; "Jobs" for recurring tasks |
| backtalk | Push-to-talk voice loop: faster-whisper STT (local), Kokoro TTS (local) or ElevenLabs (paid, optional), **brain = Claude Agent SDK** (`claude-sonnet-5`) | Claude | Brain yes; voice optional | **Concepts:** push-to-talk + hands-free toggle, spoken approval prompts ("details" reads the exact command), voice console ("clear the session", "switch to the deep model"), music ducking, thinking sound, sentence-streamed speech, a state-file bus for the face |
| ai-visualizer | Four HTML/JS faces driven by a state/waveform file bus; stdlib Python server | Any | No | **Concept and protocol:** idle/listening/thinking/speaking states + waveform → face. Code is AGPL (see licensing) |
| barehands | Webcam hand tracking (MediaPipe loaded from Google/jsDelivr CDNs) moving glass cards; file-based hooks for an AI | Any | No | Optional later. Needs a webcam and Chrome; needs local copies of MediaPipe to work offline |
| ai-marketing-skills | Markdown prompt packs | Any | No | Optional "skill pack" format |

**Tied to Claude Code:** the installer, the memory-vault workflow and backtalk's brain. **Paid:** Claude subscription
(mandatory for all three), ElevenLabs (optional). **Unneeded hardware:** webcam (barehands only).

**Licensing (decisive):** backtalk, ai-visualizer, barehands and fullstack-agent are **AGPL-3.0**; the vault and
marketing packs are **CC BY-SA 4.0**. Copying their code into CLU would make CLU AGPL. **Recommendation: reimplement
the concepts in CLU's own code; copy no AGPL code.** The state-file protocol and UX ideas aren't copyrightable code.
Primus already took this route (`docs/fullstack-integration.md`). jarvis-main (MIT) may be reused with attribution.

---

## 4. Capability comparison

| Capability | CLU | Primus | Jarvis | Decision |
|---|---|---|---|---|
| Identity, persona, personality | Persona engine, templates, characters, feedback commands | `[personality]` settings outside the model | Agent identity in CLAUDE.md | **Keep (CLU)**: CLU is the entity |
| Conversation orchestration | Agent controller: research decision, claim checks, follow-ups, honest failure, 560+ tests | ConversationOrchestrator (keyword intents) | Claude Code | **Keep (CLU)**, absorb Primus's composite-turn idea where it adds engines |
| Conversation state / follow-ups | Persisted per conversation, ASR re-hearing | 8-turn RAM + orchestrator | Claude session | **Keep (CLU)** |
| Long-term memory store | SQLite archive: historical dates, people, documents, projects, revisions, conflicts, backup, sync | SQLite + FTS5 + verification states + user facts | Obsidian vault | **Keep (CLU)** as the one store; **Absorb** Primus's verification states; **Adapt** vault as export/import |
| Semantic retrieval (embeddings) | None (BM25) | FAISS + nomic-embed GGUF | — | **Absorb** concept; rebuild in CLU with the shared llama.cpp engine and an in-process vector index (no FAISS, no extra runtime) |
| Knowledge engine (classified knowledge) | Documents archive | Knowledge store + classifier + ingest | Vault notes | **Adapt**: merge into CLU's document archive with Primus's categories |
| AI priming / job note sets | — | — | Vault "Jobs" | **Rebuild** in CLU as "priming sets" over the archive |
| Planning and goals | Task planner (multi-step, cancellation) | Planning engine + goal manager (priority, risk, dependencies) | — | **Absorb** Primus planning/goals into CLU (port) |
| Research | Wikipedia/Brave/SearXNG, pinned fetch, relevance, claim grounding, hazards/weather | Local corpus + SSRF-gated document fetch | Claude tools | **Keep (CLU)**; absorb Primus's local-corpus sources |
| Local LLM inference | llama.cpp JNI in-process (Android, Linux) | llama-cpp-python (CUDA) | Claude (cloud) | **Keep (CLU's JNI path)**; **Replace** the CUDA path with Vulkan/CPU after benchmarking |
| Model management | Registry, integrity, cache audit, reload | Model paths in TOML, deep model | — | **Keep (CLU)**, extend with a resource-aware load/unload governor |
| STT | sherpa-onnx (VAD, streaming, Whisper), whisper.cpp JNI | faster-whisper | faster-whisper | **Keep (CLU)**: no Python, no CTranslate2 |
| TTS | Kokoro/Piper via sherpa-onnx (ONNX, no torch), normalisation, pacing | Kokoro (torch) + RVC Optimus (torch) | Kokoro / ElevenLabs | **Keep (CLU)**; **Discard** resident RVC/torch (optional add-on at most); ElevenLabs never required |
| Voice UX (PTT, hands-free, barge-in, approvals by voice) | Continuous mode, barge-in, wake word | Mic button | PTT, hands-free, spoken approvals, voice console, ducking | **Keep (CLU)** + **Absorb** Jarvis's spoken approvals, voice console and ducking (reimplemented) |
| Face / visual identity | Hexagonal HUD (Android) | jarvis-main particle face + web chat | Four faces on a state bus | **Adapt**: one face in the PC shell driven by CLU's voice states; reuse jarvis-main (MIT); reimplement the state bus |
| Desktop UI | — | Local web UI (127.0.0.1) | Browser faces | **Adapt Primus's approach**: local web UI served by the CLU host, shown in a Windows WebView2 window |
| Phone ↔ PC | Sync engine (LAN, encrypted), self-hosted LiveKit stack verified | — | — | **Keep (CLU)**; the Android app becomes the remote client |
| Tool gate / permissions | Tool registry, sensitivity levels, owner auth window, people roles | Deny-by-default tool gate, path/process policy, secret-name guard, adversarially tested | Claude Code permissions | **Absorb (Primus policy model)** into one CLU permission broker |
| Computer use (screen, mouse, keyboard, apps) | Android actions only | Real Windows tools (user32, screen grab) behind agent allowlist | — | **Absorb (port)** behind the broker with approvals |
| Coding agent | — | Proposal-only, never applies | Claude Code | **Absorb** |
| Testing / evaluation agents | Test suites only | Copied-subset workspace, honest TestReport, evaluation | — | **Absorb** |
| Deployment / rollback / versioning | Signed releases, migrations | Approval-bound deploy, snapshots, rollback, Git store | — | **Absorb**: the controlled self-modification pipeline |
| Plugins / skills | Custom commands | Descriptor registry (no external execution) | Skill packs (Markdown) | **Adapt**: skill packs as data, no arbitrary code |
| Hazards / weather / maps | GDACS, Open-Meteo, routes | — | — | **Keep (CLU)** |
| Diary, historical archive, people, timeline | Complete | — | Daily notes | **Keep (CLU)**; absorb daily-note view |
| Hand-gesture board | — | — | barehands | **Discard for now**; optional later milestone |
| Marketing skills | — | — | Prompt packs | **Discard from core**; optional skill pack |
| Second Python conversation controller | `server/clu_controller.py` | — | — | **Retire** once the LiveKit worker calls the one CLU core |

---

## 5. Unified architecture

### 5.1 The decision: one brain, on the JVM, in CLU's core

**Recommendation:** CLU's Kotlin `core/` becomes the single always-on brain on the PC. Primus's valuable parts
(permission policy, computer-use tools, planning/goals, knowledge classification, embeddings and the controlled change
pipeline) are **ported into it as CLU modules**.

**Why this way round:**
1. CLU is the entity, and its core already holds the identity, conversation logic and memory archive, with the deepest
   tests (560+, all green).
2. It is already platform-independent and has in-process llama.cpp/whisper.cpp and the phone protocols.
3. A JVM process idles at a few hundred MB. A Python process plus PyTorch (needed for Primus's voice stack) idles at
   several GB.
4. One language means one memory system, one permission broker and one test suite. Primus's own rule ("every
   subsystem replaceable behind interfaces") makes its modules portable by design.

**What we don't do:** run Primus and CLU side by side and bridge them. That would be two assistants.

**Python's place:** none resident. If a specific Primus module proves cheaper to keep than to port (for example the
copied-subset test runner for Python projects), it runs as an **on-demand, sandboxed worker** that CLU starts for a
task and stops afterwards. It is an internal tool, never a second brain.

### 5.2 Diagram

```
                         ┌────────────────────────── Windows PC (home) ───────────────────────────┐
                         │                                                                        │
  Phone (CLU Android) ◀──┼──▶ Phone Gateway  (LAN first: pairing, mutual auth, TLS;               │
  remote voice/text,     │    self-hosted livekit-server for low-latency voice; WireGuard later)  │
  notifications,         │          │                                                             │
  approvals, status      │          ▼                                                             │
                         │   ┌──────────────────────────── CLU Host (one JVM process) ───────────┐│
  Desktop shell ◀────────┼──▶│ Conversation (CLU agent controller, follow-ups, claim checks)     ││
  (WebView2 window, tray,│   │      │                                                            ││
   face, chat, approvals)│   │      ▼                                                            ││
                         │   │ Orchestrator ──▶ task router ──▶ agents (research, knowledge,     ││
                         │   │      │             planning, file, coding, testing, evaluation,   ││
                         │   │      │             browser, computer-use, system, model mgmt)     ││
                         │   │      │                    │ every action                          ││
                         │   │      │                    ▼                                       ││
                         │   │      │           Permission broker (policy, approvals, audit log) ││
                         │   │      │                    │                                       ││
                         │   │      ▼                    ▼                                       ││
                         │   │ Memory & archive     Tools: files, processes, screen, input,      ││
                         │   │ (one SQLite store,   apps, web fetch, Windows APIs                ││
                         │   │  embeddings index)                                                ││
                         │   │      ▲                                                            ││
                         │   │ Inference service (one llama.cpp engine, model manager, queue)    ││
                         │   │ Voice service (sherpa-onnx VAD/ASR/TTS, wake word)                ││
                         │   │ Resource governor (RAM/CPU/GPU budget, game/fullscreen detection) ││
                         │   │ Change pipeline (proposal → workspace → tests → eval → approval   ││
                         │   │                  → deploy → snapshot → rollback)                  ││
                         │   └───────────────────────────────────────────────────────────────────┘│
                         └────────────────────────────────────────────────────────────────────────┘
```

### 5.3 Module responsibilities

| Module | Responsibility | Origin |
|---|---|---|
| Conversation | One user turn: understand, resolve follow-ups, decide research, answer, verify claims | CLU core (keep) |
| Orchestrator + task router | Turns that need work become tasks, assigned to agents; progress, cancellation, results back into the conversation | CLU task planner + Primus agent loop (merge) |
| Agents | Specialised behaviour as code plus prompts over the **shared** inference service (no per-agent model) | CLU research; Primus coding/testing/evaluation/planning; new browser/system/model agents |
| Permission broker | Every tool call: policy check (deny by default), approval request when needed, audit record | Primus security + CLU auth window (merge) |
| Tools | Thin, typed actions; never decide policy themselves | Primus tools (port) + CLU tools |
| Memory & archive | The only persistent store of what CLU knows: memories, people, documents, projects, knowledge, diary, conversations, embeddings | CLU archive (keep) + Primus verification/knowledge categories (absorb) |
| Inference service | Loads one chat model (plus an optional small fast one and the embedding model), queues requests by priority, streams tokens, keeps the prompt cache | CLU llama.cpp JNI + model registry (keep, extend) |
| Voice service | Wake word, VAD, ASR, TTS, barge-in, push-to-talk, spoken approvals | CLU voice pipeline (move from `app/` to a desktop-capable module) + Jarvis UX ideas |
| Resource governor | Enforces the budget; unloads models; pauses background work; degrades gracefully | New |
| Change pipeline | Controlled self-modification and code changes with human approval | Primus testing/evaluation/deployment (port) |
| Phone gateway | Pairing, transport security, sync, voice relay, notifications, remote approvals | CLU sync + LiveKit stack (keep) |
| Desktop shell | Chat, face, approvals, settings, archive screens, status | Primus web UI pattern + jarvis-main (adapt) |

### 5.4 Data flow (one spoken request that needs work)

1. Voice service hears the wake word, transcribes, and keeps the raw transcript.
2. Conversation resolves the request against context ("look it up", "that file").
3. The orchestrator creates a task and the router picks agents.
4. Each agent asks the inference service for reasoning and the broker for actions.
5. The broker checks policy; consequential actions need your approval (PC dialog, phone notification, or spoken
   "yes" with "details" on request).
6. Results return to the conversation. The answer is spoken, and the sources and audit entries are stored.
7. Memory writes follow CLU's existing approval rules: nothing inferred is saved without approval.

### 5.5 Memory architecture

* **One SQLite database** (CLU schema, migrated forward), owned by the host. No other process opens it, which fixes
  Primus's ADR-0006 violation by design.
* **Tables:** memories (with Primus's verification state added); people; documents (with Primus's knowledge categories
  added); projects; diary; conversations; conversation state; revisions; conflicts; audit log; tasks.
* **Embeddings:**
  * produced by the same llama.cpp engine (a small GGUF embedding model, about 150 MB, loaded only while indexing or
    searching);
  * vectors stored in SQLite and searched in-process;
  * hybrid ranking: BM25 + vectors.
  * No FAISS and no separate database.
* **Import paths:**
  * Primus `astra.db` → CLU memories/knowledge, with provenance, through the existing preview-then-import flow;
  * Obsidian vault Markdown ↔ CLU archive (export and import), so the user can keep a readable copy.
* **Priming sets:** named collections of notes and documents attached to a kind of task ("write an email", "review
  code").

### 5.6 Model and inference strategy

* **One engine:** llama.cpp in-process via CLU's JNI binding, built for Windows.
  * **Backend:** Vulkan (works with AMD iGPUs in llama.cpp in general) or CPU (Zen 5 has AVX-512). **Which one wins on
    the Radeon 860M must be measured (milestone 1); nothing is assumed.**
  * **NPU:** the Ryzen AI NPU is reachable only through AMD's own (free) Ryzen AI software. It is noted as a later
    experiment, never a dependency.
* **Models, loaded on demand** (candidates to benchmark, not decisions):

  | Role | Size | Approx. RAM | Loaded |
  |---|---|---|---|
  | Main chat/reasoning | 7–8B Q4_K_M | ~5–6 GB with a 4k context | Only while needed |
  | Fast | 1.5–3B | ~1.5–2.5 GB | For routing, short replies and when the PC is busy |
  | Embeddings | small embedding GGUF | ~0.15 GB | Only while indexing or searching |

* **Agents share the loaded model.** Requests are queued by priority (live conversation first, background agents
  last). The prompt cache is kept per conversation, as CLU already does.
* **UMA note:** the 860M has no dedicated VRAM. GPU allocations come out of system RAM, so they count against the
  16 GB budget.

### 5.7 Agent orchestration

* **Agent shape:** an agent is a Kotlin class with a prompt template, a typed tool set and a budget (time, tokens,
  actions). Agents never hold a model of their own.
* **Router rules:** deterministic first (CLU's existing approach), the model only when the request is ambiguous.
* **Task rules:** tasks are persisted and resumable; they report progress and are cancellable; Stop cancels everything
  (CLU's cancellation guarantees extend to tasks).
* **Shared memory, controlled:**
  * agents read memory through a query interface scoped by task;
  * agents write only *proposals*, which go through the same approval rules as conversation memories.

### 5.8 Voice architecture

* **Engines:** sherpa-onnx (already used by CLU, Windows supported) for VAD, wake word, streaming ASR and Kokoro/Piper
  TTS; whisper.cpp for accurate offline transcription. No PyTorch.
* **Behaviour:**
  * CLU's existing streaming chunker, barge-in, pacing and normalisation;
  * Jarvis-style push-to-talk and hands-free toggle;
  * spoken approvals with "details";
  * a voice console ("switch to the fast model", "pause background work");
  * ducking of other audio while CLU speaks (Windows audio session API).
* **Phone voice:** through the self-hosted LiveKit server on the PC, as already built. The LiveKit worker calls the
  CLU host instead of running its own controller.

### 5.9 Computer-use architecture

* **Native helper:** Windows actions (screen capture, window inspection, input, application launch, UI Automation for
  reading controls) run through one small native helper. It is either a JNI library next to the llama binding or a
  helper executable with a narrow, typed protocol. Primus's ctypes code is the reference implementation.
* **Every action goes through the broker:**
  * observing (screenshot, window list) needs a session grant;
  * acting (click, type, launch) needs approval or a scoped standing grant ("in this window, for this task");
  * destructive actions always need explicit approval.
* **Safety features:**
  * a visible "CLU is controlling" overlay;
  * a global stop hotkey;
  * all input actions logged with before/after screenshots kept for a short, configurable time.

### 5.10 Security architecture

* **One permission broker**, deny by default:
  * policy rules by capability (read file, write file, run process, network, input, screen, delete, install, send
    message);
  * scope (paths, apps, domains) and lifetime (once, task, session, permanent).
* **Absorbed from Primus:**
  * path and secret-name guards;
  * process policy;
  * the copied-workspace isolation for code;
  * deploy-only-with-ApprovalRecord;
  * its adversarial tests, ported.
* **Absorbed from CLU:** owner verification (PIN or Windows Hello on the PC; biometrics on the phone); people and
  roles (guests can't run tools); untrusted-content handling for web text.
* **Process sandboxing:** Windows Job Objects (memory/CPU caps, kill-on-close) for every spawned process; a restricted
  token and low-integrity level where practical; a separate workspace folder for code changes.
* **Audit log:** append-only, in the main database, with every approval and every action.
* **Reversibility:**
  * file changes go through snapshots, using the Primus version manager design;
  * deletions go to a CLU trash folder first;
  * self-modification goes only through the change pipeline, never directly.
* **Honest limit:** this is policy plus OS-level containment, not a perfect sandbox. Process isolation that is
  stronger than Job Objects/AppContainer is out of scope for phase 2 (the same residual limits Primus documents).

### 5.11 Phone ↔ PC communication

* **LAN first:**
  * pairing by QR code shown on the PC (an evolution of CLU's existing pairing);
  * mutual authentication with per-device keys;
  * TLS for control and sync;
  * WebRTC (livekit-server, Apache-2.0, run locally) for voice;
  * nothing listens on public interfaces.
* **Remote access, later:** WireGuard (free, self-hosted) between phone and PC. No LiveKit Cloud and no paid relay.
  The PC is never exposed to the internet without the VPN in front.
* **Phone app roles:** remote chat and voice, notifications (task finished, approval needed), status (CPU/RAM, models,
  running tasks), remote approvals, and an offline fallback that keeps today's on-device small model for when the PC is
  unreachable (clearly labelled).

### 5.12 Resource-management architecture

**Initial budget, total ≤ 16 GB** (a ceiling, not a target):

| Component | Typical | Peak |
|---|---|---|
| CLU host (JVM, heap capped with `-Xmx`) | 0.3–0.5 GB | 1 GB |
| Main model + context | 0 (unloaded) | ~6 GB |
| Fast model | 0–2.5 GB | 2.5 GB |
| Embeddings | 0 | 0.2 GB |
| Voice (VAD + ASR + TTS) | 0.3 GB when voice is on | 1 GB |
| Spawned workers (Job Object capped) | 0 | 2 GB |
| Headroom | — | ~3 GB |

**Governor rules:**
* models unload after an idle timeout (configurable; for example 10 min for the main model);
* background agents pause when the foreground app is a game or full-screen, when CPU stays above a threshold, or when
  Windows reports memory pressure;
* the fast model replaces the main model under pressure;
* inference threads drop to below-normal priority when you're active;
* GPU offload is reduced or off while a GPU-heavy app runs.

**Order of sacrifice:** background tasks → embeddings → main model → voice. The live conversation is degraded (slower
model) before it is refused.

**Visibility and limits:** live status (RAM, CPU, GPU, loaded models) in the shell and on the phone; all limits are
settings.

### 5.13 Installation and startup

* One installer (no admin rights for the default per-user install) containing:
  * the CLU host (JVM runtime bundled with jlink, so no Java install is needed);
  * native libraries;
  * the desktop shell;
  * livekit-server.
* **Models are not bundled.** A first-run setup downloads chosen open models with checksums, or points to existing GGUF
  files, including Primus's.
* **First-run wizard** (Jarvis-style, but CLU's own): name and personality, voice, folders CLU may read, phone pairing,
  resource budget, importing the Primus database and an Obsidian vault.
* **Start-up:**
  * runs per-user at sign-in (Task Scheduler, not a system service), so CLU lives in the user's session with access to
    the screen;
  * a tray icon; the window opens on demand;
  * idle start-up loads no models.
* **Updates:** through the change pipeline for CLU's own changes; signed release packages for normal updates;
  automatic backup of the database before every migration.

---

## 6. Roadmap

Milestones are ordered so that a **working unified system exists early** (milestone 4) and every later step adds to
something running. Each one keeps the previous one releasable.

### M0 — Preserve and set up the unified workspace

* **Objective:** a safe base.
* **Reused:** all three repositories, untouched.
* **New:**
  * a unified branch in Archivum-0x01 (CLU stays the root);
  * Primus imported under `legacy/primus/` with its single commit preserved via a merge (unrelated histories), its
    commit hash recorded, and its missing ADRs noted;
  * the Jarvis concepts recorded in `docs/convergence/` (no AGPL code copied);
  * a backup tag of every source.
* **Dependencies:** your approval and the push target branch.
* **Files:** `docs/convergence/*`, `legacy/primus/**`.
* **Tests:** CLU suite green; Primus suite run in CI with its dependencies (faiss etc.) to record its true baseline.
* **Acceptance:**
  * hashes recorded;
  * both suites' baselines documented;
  * originals unchanged.
* **Rollback:** delete the branch.

### M1 — Measure the target PC

* **Objective:** facts for every hardware decision.
* **Reused:** CLU's llama.cpp benchmark harness and latency benchmark.
* **New:**
  * Windows builds of llama.cpp (CPU, Vulkan), whisper.cpp and sherpa-onnx;
  * a benchmark pack that runs on your PC: tokens/s, first-token time, RAM per model, Vulkan vs CPU, idle cost and
    effect on a game/benchmark running alongside.
* **Dependencies:** the Windows machine (you run the pack, or a Windows CI runner for the build part).
* **Files:** `native/` Windows toolchain files, `bench/`.
* **Tests:** the benchmark itself, with results committed as JSON.
* **Acceptance:** a measured model and backend choice that fits the budget with headroom.
* **Rollback:** none needed (measurement only).

### M2 — CLU host on Windows

* **Objective:** CLU's brain runs as a background process on Windows.
* **Reused:** `core/` unchanged where possible, the JNI bindings, SQLite.
* **New:**
  * a desktop platform module (`desktop/`): implementations of `CluPlatform` for Windows (paths, HTTP, key vault via
    Windows DPAPI, clock);
  * a model manager with idle unload;
  * a resource governor (first version: RAM ceiling, idle unload, priority);
  * a local API (127.0.0.1, token-authenticated) for the shell and phone.
* **Files:** `desktop/**`, small extensions in `core/llm`.
* **Tests:**
  * core suite on Windows;
  * new governor tests (fixture memory pressure);
  * an API contract test.
* **Acceptance:**
  * a conversation over the local API with a real model;
  * idle RAM under 600 MB with no model loaded;
  * models unload on timeout.
* **Rollback:** the Android app is untouched; remove `desktop/`.

### M3 — Desktop shell

* **Objective:** something you use daily.
* **Reused:** Primus's web UI layout ideas, jarvis-main (MIT) as the face, CLU's archive screens logic.
* **New:** WebView2 window plus tray icon, chat, face driven by voice states, approvals panel, status panel, settings,
  archive screens (memories, people, documents, timeline).
* **Tests:** UI contract tests (the shell never touches the database; only the API), Playwright tests against the
  local page.
* **Acceptance:** chat, memory recall, research with sources, and approvals all work end to end in the window.
* **Rollback:** the API remains usable without the shell.

### M4 — First unified release: voice on the PC

* **Objective:** talk to CLU on the PC.
* **Reused:** CLU's voice pipeline (moved from `app/` into a shared module), sherpa-onnx, streaming chunker, barge-in.
* **New:**
  * Windows audio I/O;
  * push-to-talk hotkey and hands-free toggle;
  * spoken approvals;
  * voice console;
  * ducking.
* **Tests:**
  * pipeline tests with recorded audio;
  * latency benchmark on the PC (speech end → first audio);
  * barge-in test.
* **Acceptance:**
  * measured first-audio latency reported;
  * Stop cancels within the measured bound;
  * no PyTorch anywhere.
* **Rollback:** voice off by setting.

### M5 — Phone as the remote companion

* **Objective:** the phone talks to the PC brain.
* **Reused:** the Android app, sync engine, LiveKit local stack, Android voice I/O.
* **New:**
  * QR pairing with the PC;
  * a "remote" mode in the app (the default when the PC is reachable) and a labelled offline fallback;
  * notifications and remote approvals;
  * the LiveKit worker calls the CLU host API and `server/clu_controller.py` is retired, removing the duplicate
    controller.
* **Tests:**
  * pairing and auth tests (wrong key refused);
  * the e2e voice test against the PC host;
  * a sync test PC↔phone;
  * an offline-fallback test.
* **Acceptance:**
  * a conversation from the phone answered by the PC model;
  * the PC unreachable → clear fallback message;
  * nothing listening on public interfaces (port scan check).
* **Rollback:** the app's current on-device mode remains.

### M6 — One permission broker and audit

* **Objective:** every action is checked and logged.
* **Reused:** Primus security policy and its adversarial tests (ported), CLU auth window and roles.
* **New:** the broker, grant scopes and lifetimes, the audit log table, approval UX (PC, phone, voice), Windows Hello
  for owner verification.
* **Tests:**
  * ported Primus adversarial tests (path escape, symlinks, case-folding on Windows, secret names, process spawn);
  * fail-closed tests;
  * an audit completeness test (every tool call has a record).
* **Acceptance:** no tool reachable except through the broker (enforced by a test like Primus's contract tests).
* **Rollback:** the broker defaults to deny; tools are simply unavailable.

### M7 — Tools and computer use on Windows

* **Objective:** CLU can act on the PC, safely.
* **Reused:** Primus's tools (port the logic from `astra/tools/*` and the input/perception hosts).
* **New:**
  * the native helper;
  * file tools with trash and snapshots;
  * process tools in Job Objects;
  * screen/input/window tools with the overlay and stop hotkey;
  * UI Automation reads.
* **Tests:**
  * each tool against a test window and a temp folder;
  * Job Object limit tests;
  * a stop-hotkey test;
  * a broker-denial test for each tool.
* **Acceptance:** a scripted task ("open Notepad, type a line, save to the workspace") completes with approvals and is
  fully audited and reversible.
* **Rollback:** tools disabled by policy.

### M8 — Orchestrator and agents

* **Objective:** real multi-step work.
* **Reused:**
  * CLU task planner;
  * Primus agent loop, agent decisions, planning engine and goal manager (ported);
  * CLU research.
* **New:**
  * task router and persistent tasks;
  * research, knowledge, file, planning, browser (fetch and read; full browser automation later), system and
    model-management agents;
  * the shared inference queue with priorities.
* **Tests:**
  * a router decision table;
  * task persistence across restart;
  * cancellation;
  * budget limits;
  * concurrent agents sharing one model (no second model loaded).
* **Acceptance:** "Research X, summarise into a document in my archive and add a follow-up goal" completes with one
  approval.
* **Rollback:** agents can be disabled individually.

### M9 — Coding, testing, evaluation and the change pipeline

* **Objective:** supervised improvement, including of CLU itself.
* **Reused:** Primus coding (proposal-only), copied-subset workspace, test runner, evaluation, deployment, version
  manager and Git store (ported, or kept as the on-demand worker where porting is wasteful).
* **New:**
  * a pipeline UI;
  * proposal diff review on the PC;
  * Gradle/JUnit and Python runners;
  * self-modification restricted to the pipeline.
* **Tests:**
  * ported Primus pipeline tests (empty evidence is never a pass; no auto-approval; rollback);
  * an end-to-end change to a sample project;
  * a test that CLU cannot write its own install folder outside the pipeline.
* **Acceptance:** a proposed change → tests → evaluation → your approval → deployed → rolled back on request.
* **Rollback:** pipeline off; nothing applies.

### M10 — Memory convergence

* **Objective:** one memory, better retrieval.
* **Reused:** CLU archive; Primus verification states and knowledge categories; Primus `astra.db` (import).
* **New:**
  * the embeddings index and hybrid ranking;
  * the Primus import (preview, then apply);
  * Obsidian vault export/import;
  * priming sets;
  * daily notes view.
* **Tests:**
  * retrieval quality on a fixed question set (BM25 vs hybrid, measured);
  * import round-trip;
  * vault round-trip;
  * migration tests on a copy of real data.
* **Acceptance:**
  * Primus memories appear in CLU with provenance;
  * measured retrieval improvement or the feature stays off.
* **Rollback:** the database backup taken before migration.

### M11 — Installer, start-up and updates

* **Objective:** install it like normal software.
* **Reused:** CLU's signing and backup practice.
* **New:** the installer, the first-run wizard, per-user autostart, model download with checksums, update and backup
  flow, uninstall that keeps your data unless asked.
* **Tests:**
  * a clean-machine install test (Windows VM);
  * an upgrade preserving data;
  * an idle-cost test after sign-in.
* **Acceptance:** a fresh Windows user gets to a spoken greeting through the wizard without a terminal.
* **Rollback:** the previous installer version.

**Later, optional (not scheduled):** hand-gesture board (barehands concept, local MediaPipe); skill packs (marketing and
others) as data; NPU inference experiments; remote access over WireGuard (if not done in M5).

---

## 7. Resource constraints: how each is met

| Brief requirement | Where |
|---|---|
| ≤ 16 GB combined ceiling, adaptive | §5.12 budget + governor (M2, refined in M8) |
| Low idle CPU/RAM | No model loaded at idle; JVM heap cap; idle target < 600 MB (M2 acceptance) |
| Load and unload intelligently, no duplication | One inference service, shared by agents (§5.6, M8 test) |
| Quantised models, efficient inference | GGUF Q4/Q5 chosen by measurement (M1) |
| Don't assume the 860M backend | Vulkan vs CPU measured on the target (M1) |
| Throttle for games and editing | Foreground/full-screen detection, priority, GPU offload reduction (§5.12) |
| Configurable limits and monitoring | Settings + status panel + phone status (M2, M3, M5) |

---

## 8. Financial independence

* **Free and open only:** everything in the design is free and open: llama.cpp (MIT), whisper.cpp (MIT), sherpa-onnx
  (Apache-2.0), livekit-server (Apache-2.0), WireGuard, SQLite, WebView2 (part of Windows), open-weight models.
* **No paid dependency:** no paid API is needed. The internet is used only for research, maps, weather and model
  downloads you choose.
* **Removed or optional:**
  * Claude Code and the Claude Agent SDK are removed from CLU's runtime path entirely;
  * ElevenLabs is never required;
  * Brave Search stays optional with a free key; SearXNG/Wikipedia work without one.

---

## 9. Decisions for you to approve

1. **Architecture:** CLU's Kotlin core as the single brain, with Primus ported into it (§5.1), rather than Primus's
   Python as the base. This is the central decision; everything else follows from it.
2. **Licensing:** reimplement the Jarvis concepts; copy no AGPL/CC BY-SA code (§3). If you'd rather accept AGPL for CLU,
   their code could be reused directly, but CLU would then have to stay open source under the AGPL.
3. **Model choice:** Primus's default is an *abliterated* model (refusal training removed). Do you want CLU's default
   to be a standard instruction-tuned model, with the abliterated one as an option you choose? Recommendation: standard
   by default.
4. **The RVC "Optimus" voice:** drop it from the core (PyTorch, several GB of RAM). It could return later as an optional
   add-on only if it fits the budget.
5. **Repository layout:** CLU (Archivum-0x01) becomes the unified repository, with Primus imported under
   `legacy/primus/` (§6 M0). It needs a branch name and permission to push it.
6. **Missing Primus material:** do you have Astra's `docs/adr/` folder and its Git history somewhere? Importing them
   would preserve its design rationale.
7. **Signing key:** CLU's release key currently exists only in the build container. Please keep your own copy;
   otherwise future phone updates can't install over the current app.
8. **Windows verification:** M1 needs runs on your PC (a benchmark pack you start), because this build environment is
   Linux with no AMD GPU. Is that acceptable, or do you want a Windows CI runner set up?
