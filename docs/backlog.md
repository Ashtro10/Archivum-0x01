# Improvement Backlog

Prioritised per the constitution's Planning System. Every entry carries priority,
estimated impact, estimated effort, risk, dependencies, and status.

Priority: P0 blocks other work, P1 next, P2 later, P3 speculative.
Status: `todo`, `in-progress`, `blocked`, `done`, `dropped`.

---

## B-001 — Memory Engine: SQLite schema and store

- **Priority:** P0
- **Impact:** High. Every other module reads or writes memory.
- **Effort:** Medium.
- **Risk:** Medium. Schema mistakes are expensive once data exists; migrations
  must be planned from the first commit.
- **Dependencies:** ADR-0001 (D1, D3), ADR-0003, `astra.configuration`.
- **Status:** done (2026-08-12)

Implemented as `astra/memory/sqlite_store.py` with `astra/memory/schema.py` and
`astra/memory/errors.py`. 27 tests in `tests/test_memory.py`. Search runs on
FTS5 (SQLite 3.45.3); the `LIKE` fallback exists but is unexercised on this
machine.

Implement `MemoryStore` over SQLite. Records carry the constitution's memory
categories, a verified flag, provenance, and timestamps. Enforce the rule that
unknown information never overwrites verified information at the storage layer,
not in callers.

Schema, migration strategy, and the twelve-test plan are recorded in
`docs/adr/ADR-0003-memory-schema.md` (accepted 2026-08-12). Scope of the first
version is memory records only; embedding provenance columns are reserved but
unused, and knowledge tables belong to B-004.

---

## B-002 — Local reasoning runtime and models

- **Priority:** P0
- **Impact:** High. Nothing reasons or embeds without this.
- **Effort:** Medium, spread across five gated stages.
- **Risk:** Medium. Not from the code, which is contained behind `LLMBackend`
  and `EmbeddingBackend`, but from committing to a runtime or a model before
  evidence justifies it.
- **Dependencies:** **ADR-0002 (hardware, satisfied)**, **ADR-0004 (accepted,
  supersedes ADR-0001 D5)**.
- **Status:** **done (2026-08-14)** — all five stages complete. Production
  default is now Qwen2.5-3B-Instruct Q5_K_M at context 4096, full GPU
  offload. Evidence recorded in ADR-0005.

### Ordering rule

Hardware measurement precedes every runtime and model decision. B-008 supplied
that evidence on 2026-08-12 and is complete, so this item is unblocked. The rule
stands for any future revision: **no installation, no model pull, and no default
model change may be proposed from an unmeasured machine.**

The failure mode this rule exists to prevent is installing a runtime, adopting a
model, and discovering the hardware limit afterwards. During scaffolding the
default was set to `llama3.1:8b` from habit; ADR-0002 later showed it cannot fit
4 GiB of VRAM. That is exactly the mistake the ordering prevents, caught here by
measuring first.

### Transport rule

Astra performs no inference over a network transport, loopback included. ADR-
0004 supersedes ADR-0001 D5 and removes the HTTP client from the design
entirely. Any future proposal to reach a local model through a socket, including
Ollama's API, needs a new ADR that argues against ADR-0004 on its merits.

### Stages

Each stage ends at a human review point. No stage begins before the previous one
is approved.

1. **Runtime architecture, no transport. — DONE 2026-08-12.** Implemented as
   `astra/reasoning/llama_cpp_backend.py`, enforced by `tests/test_reasoning.py`
   (29 tests). `httpx` was removed from `pyproject.toml` and never adopted.
   What was delivered:
   - Adapters in `astra/reasoning/`, loading GGUF files by path.
   - `SUPPORTED_REASONING_BACKENDS` becomes `{"llama_cpp"}`; the `ollama` value
     is removed (ADR-0004 D4).
   - `ReasoningSettings.host` is replaced by `model_path` and
     `embedding_model_path` (ADR-0004 D5). This removes the loopback validation
     and its two tests, and replaces them with a stricter check: no module under
     `astra/reasoning/` may import a network library.
   - `is_available()` reports whether the binding imports and the model file
     exists. It must never hang and never raise.
   - Tests must pass with `llama-cpp-python` absent and no model file present.
     Anything needing a real model is skipped, not failed.
   - No install, no download, no change to the default model name.
2. **Model selection, on paper. — DONE 2026-08-14.** Recorded as
   `docs/adr/ADR-0005-model-selection.md` (proposed, provisional until stage 4).
   Outcome: a conservative VRAM budget of ~3046 MiB for weights and KV cache
   after overheads, five candidates with memory arithmetic, and both 7B and 8B
   classes rejected on partial-offload latency (estimated 4–8 tok/s against
   35–55). Provisional recommendation: Qwen2.5-3B-Instruct at Q5_K_M with 4096
   context fully on GPU, plus nomic-embed-text-v1.5 on CPU for embeddings.
   Embedding pooling is unresolved by design: BERT-family files normally return
   pooled vectors, and this is a stage 3 verification rather than an assumption.
   Nothing installed, nothing downloaded.
3. **Install the local runtime. — DONE 2026-08-14 (user).** llama-cpp-python
   0.3.34 cu124 wheel installed; Qwen2.5-3B Q5_K_M and Dolphin3.0-8B Q5_K_S
   GGUF files obtained. CUDA dependency: torch CUDA DLLs from Anaconda Chamber
   env must be in PATH at startup (documented in ADR-0005 §16).
4. **Benchmark on this machine. — DONE 2026-08-14.** Results in ADR-0005
   §16–22 and `benchmarks/results/`. Key measurements: 3B at 46.6 tok/s full
   GPU; 8B at 6.3 tok/s with 18/33 GPU layers; both 7/7 on simple quality
   checks. VRAM overhead 166–188 MiB (ADR estimated 500 MiB — conservative).
5. **Choose the production default. — DONE 2026-08-14.** ADR-0005 §15 and
   §23 record the approved decision. `settings.py` now carries the measured
   configuration. 102 tests pass.

---

## B-003 — Vector index adapter over FAISS

- **Priority:** P1
- **Impact:** High. Semantic recall over memory and knowledge.
- **Effort:** Medium. Manual id mapping and persistence.
- **Risk:** Low. The index is disposable by design (ADR-0001 D1).
- **Dependencies:** B-001, B-002, B-010, `faiss-cpu`, `numpy`.
- **Status:** done (2026-08-14)

Implemented `FaissVectorIndex` (IndexFlatIP, dim=768, L2-norm mandatory),
`rebuild_index()`, and `_vec_norm.normalize()` utility. 63 tests in
`tests/test_vector_index.py`. Added `embedding_context_tokens=2048` to
`ReasoningSettings` (B-010 finding: GGUF n_ctx_train=2048, not 4096).

Critical B-010-derived architectural requirement met: every vector is
L2-normalized in `add()` and `search()` — raw vectors cannot enter FAISS.
Deletion is logical (tombstone); rebuild clears physical FAISS state.
Metadata versioning detects dimension/model/format mismatches on load.

---

## B-004 — Knowledge Engine: classification before storage

- **Priority:** P1
- **Impact:** Medium-high. Prevents the store degrading into an unsorted dump.
- **Effort:** Medium.
- **Risk:** Medium. Misclassification is silent; needs a measurable accuracy
  check against a hand-labelled sample.
- **Dependencies:** B-001, B-002.
- **Status:** done (2026-08-14)

Implemented as `astra/knowledge/sqlite_store.py` (SQLiteKnowledgeStore),
`astra/knowledge/classifier.py` (KeywordKnowledgeClassifier), `astra/knowledge/schema.py`,
`astra/knowledge/errors.py`. 87 tests in `tests/test_knowledge.py`.

Design decisions recorded in `docs/adr/ADR-0008-knowledge-engine.md` (accepted 2026-08-14):
separate SQLite file from Memory (`data/knowledge.db`); deterministic keyword-based
classifier (no model invocation); `KnowledgeVerification` enum independent of Memory's
`VerificationState`; `KnowledgeRecord` extended with `verification`, `updated_at`,
`acceptance_note`; confidence semantics defined as exact constants (1.0 / 0.75 / 0.5 /
0.25 / 0.0); `KnowledgeSettings` added to `AstraSettings`. Classification and storage
are separate steps — the store does not classify, and the classifier does not store.

---

## B-005 — Adopt pydantic in the Configuration Manager

- **Priority:** P2
- **Impact:** Low-medium. Better error messages and schema validation.
- **Effort:** Small.
- **Risk:** Low.
- **Dependencies:** `pydantic` installed (declared in ADR-0001's register;
  present on this machine as pydantic 2.13.4).
- **Status:** done (2026-08-14)

Implemented as Pydantic v2 models in `astra/configuration/settings.py`.
Loader (`loader.py`) still uses stdlib `tomllib`. Public names, TOML
sections, defaults, precedence (`explicit` → `ASTRA_CONFIG` → `astra.toml`
→ defaults), Path coercion, and `ConfigurationError` are unchanged.

Validation rules encoded on the models (not dropped):
- `backend` ∈ `{llama_cpp}` (Ollama rejected)
- no `host` / `port` / `url` fields
- `require_human_approval` cannot be `false`
- extra keys forbidden
- truth path ≠ vector index path
- numeric bounds as before; bool/int mix-ups still rejected

Pydantic `ValidationError` is wrapped as `ConfigurationError`. No new ADR:
ADR-0001 already selected pydantic for this role. No TOML schema change.
Production model defaults unchanged (Qwen2.5-3B Q5_K_M, ctx 4096, gpu 99;
embedding nomic-embed-text-v1.5, ctx 2048, dim 768, CPU).

---

## B-006 — Planning Engine and Goal Manager

- **Priority:** P2
- **Impact:** Medium. Turns this file into machine-readable state.
- **Effort:** Medium.
- **Risk:** Low.
- **Dependencies:** B-001.
- **Status:** done (2026-08-14)

Implemented as `astra/planning/sqlite_store.py` (SQLitePlanningStore),
`astra/planning/engine.py` (SimplePlanningEngine), `astra/planning/schema.py`,
`astra/planning/errors.py`. 112 tests in `tests/test_planning.py`.

Design decisions in `docs/adr/ADR-0009-planning-engine.md` (accepted 2026-08-14):
separate SQLite file (`data/planning.db`); DONE/DROPPED are terminal (DB trigger +
Python guard); dep existence validated at `add()` time; cycle detection via DFS;
`SimplePlanningEngine.plan()` is keyword search + topological ordering, no LLM;
`Task` extended with `created_at`, `updated_at`, `completed_at`, `completion_note`;
`Backlog` extended with `complete`, `cancel`, `actionable_tasks`, `validate_dependencies`,
`update_task`. `GoalManager` implementation deferred.

---

## B-007 — Core Controller wiring

- **Priority:** P2
- **Impact:** High once engines exist; useless before then.
- **Effort:** Small.
- **Risk:** Low.
- **Dependencies:** B-001, B-002, B-003.
- **Status:** done (2026-08-14)

Implemented as `astra/core/astra_controller.py` (`AstraController` + `ControllerStateProvider`)
and `astra/core/errors.py`. 59 tests in `tests/test_controller.py`.

Design decisions in `docs/adr/ADR-0010-core-controller.md` (accepted 2026-08-14):
engines constructed in `start()` not `__init__()` (DI-friendly); reasoning backends
detected but not loaded at startup; vector index loaded if files exist, silently empty
otherwise; `shutdown()` idempotent; `dispatch()` catches agent exceptions → returns
`AgentResult(success=False)`; duplicate agent registration is an error; `ControllerStateProvider`
queries live engine instances for truthful capability states. `python -m astra` starts
the controller before launching the dashboard.

---

## B-008 — Measure the host hardware

- **Priority:** P1
- **Impact:** Medium. ADR-0001 assumes an unmeasured CPU, RAM, and GPU profile;
  model size and index type both depend on the real numbers.
- **Effort:** Trivial.
- **Risk:** None.
- **Dependencies:** none.
- **Status:** done (2026-08-12)

Measured with the standard library and `nvidia-smi`. Results and their
consequences are recorded in `docs/adr/ADR-0002-hardware-profile.md`.

Headline: Ryzen 5 5600H, 6 cores and 12 threads, 15.4 GiB RAM, RTX 3050 Laptop
with **4 GiB VRAM**, CUDA compute capability 8.6, 236 GiB free disk. VRAM is the
binding constraint: a 3B-class 4-bit model fits fully on the GPU, a 7B or 8B one
does not.

---

## B-009 — First agent implementation (Research Agent)

- **Priority:** P3
- **Impact:** Medium. Proves the plugin and tool contracts under real load.
- **Effort:** Medium.
- **Risk:** Medium. Network access is user-initiated only and must be explicitly
  gated by the Security Agent.
- **Dependencies:** B-001, B-002, B-004, tool interface implementation.
- **Status:** done (2026-08-14)

Implemented as `astra/research/agent.py` (`LocalResearchAgent`),
`astra/research/source.py` (`ResearchSource` ABC + 4 local sources),
`astra/research/errors.py`. 74 tests in `tests/test_research.py`. Suite total
after this milestone: **585 tests, 0 failures**.

**Actual outcome (local-only).** Research gathers evidence from local sources
and returns `Finding` / `ResearchReport` / `AgentResult`. It does **not**
accept knowledge, write memory, plan tasks, or perform reasoning itself.
Network/web research is **not** implemented: `used_network` is always `False`;
`allow_network=True` is accepted and unused. No HTTP client, no subprocess,
no `astra.tools` usage.

Four local sources: `TextSource`, `DocumentSource`, `KnowledgeSource`,
`MemorySource`. LLM synthesis is optional and model-agnostic; without a loaded
model, `summarise()` concatenates top findings. `AstraController.start()`
registers the agent with knowledge + memory sources (ADR-0011 D8).

Design decisions in `docs/adr/ADR-0011-research-agent.md` (accepted 2026-08-14):
`ResearchSource` ABC; provenance via `Finding.retrieved_at`; no auto-writes to
memory/knowledge; auto-registration; completion check is import of
`astra.research.agent`. Deviation from this backlog's original dependency list:
the Tool Interface was named as a dependency and was not used.

---

## B-010 — Embedding model acquisition (prerequisite to B-003)

- **Priority:** P1
- **Impact:** High. B-003 (FAISS index) cannot be built without a verified
  embedding model.
- **Effort:** Trivial (user downloads one file).
- **Risk:** Low.
- **Dependencies:** none (user action only).
- **Status:** done (2026-08-14)

**Completed 2026-08-14.** File: `benchmarks/models/nomic-embed-text-v1.5.Q8_0.gguf`
(138.65 MiB). Script: `benchmarks/verify_embedding.py`. Results:
`benchmarks/results/embedding_verification.json`. 21 tests in
`tests/test_embedding_verification.py`.

Key findings: dim=768, pooled, deterministic, RAM=152 MiB, lat=15 ms/short.
**Critical: NOT normalized (L2~22.5)** — B-003 MUST normalize vectors before
IndexFlatIP. n_ctx_train=2048 (not 8192). Outcome B: verified with changes.

---

## B-011 — Application State interface (astra.state)

- **Priority:** P1
- **Impact:** Medium. Prerequisite for the Presentation Layer dashboard and
  for any future API server or demo mode.
- **Effort:** Small.
- **Risk:** Low. Pure contracts; no I/O.
- **Dependencies:** ADR-0006 (accepted).
- **Status:** done (2026-08-14)

Delivered `astra/state/contracts.py`: `CapabilityStatus` (5 values),
`CapabilityInfo`, `ModuleStatus`, `SystemInfo`, `TestStatus`,
`ApplicationState`, `StateProvider`. 24 tests in `tests/test_state.py`.
The package is a dependency leaf — enforced by the test suite.

---

## B-012 — Presentation Layer: Stage 1 dashboard (UI foundation)

- **Priority:** P2
- **Impact:** High. Makes Astra demonstrable at every future stage without
  manual documentation updates.
- **Effort:** Medium.
- **Risk:** Low. Architecture enforces separation; dashboard cannot corrupt
  core state.
- **Dependencies:** B-011 (done), B-007 (Core Controller wiring — for a wired
  system to query).
- **Status:** done (2026-08-14)

Deliver a minimal launchable dashboard that consumes `ApplicationState` and
displays the current Astra development state. Visual design is separate from
application logic (ADR-0006 D5). Scope for Stage 1 panel:

- Project overview (stage, completed milestones, next milestone)
- Module map (status per package, colour-coded by CapabilityStatus)
- Test status (total / passing / last run)
- Active model and runtime configuration
- Hardware summary

The dashboard must read state from a `StateProvider` implementation, never from
hard-coded strings. When B-003 adds FAISS, the dashboard discovers the new
capability through `ApplicationState` without a dashboard code change.

Technology choice (to be made at implementation time): options include a
lightweight Python web framework (FastAPI + HTMX, Flask), a desktop GUI
library (tkinter, PyQt, Dear PyGui), or a TUI. The choice must not introduce
mandatory network dependencies for local-only use. Packaging as a single
executable must remain achievable.

---

## B-013 — Memory semantic recall: live embed + FAISS pipeline

- **Priority:** P1
- **Impact:** High. Turns keyword-only memory into meaning-based recall.
- **Effort:** Medium.
- **Risk:** Low. Index remains disposable (ADR-0001 D1). Store succeeds without embeddings.
- **Dependencies:** B-001, B-002, B-003, B-007, B-010, B-012. ADR-0012.
- **Status:** done (2026-08-14)

Implemented `MemoryIndexer` (`astra/memory/indexer.py`): embed-on-write when
the embedding backend is available; SQLite write always first; provenance
columns written on success. Controller persists/loads FAISS on lifecycle;
does not embed or auto-rebuild at `start()` (ADR-0012 D6).
`rebuild_index()` / `rebuild_memory_index()` refuse to clear when the backend
is unavailable. `MemorySource` keeps FTS5 and optionally merges semantic hits.
Dashboard uses `ControllerStateProvider`; memory panel no longer claims
"pending B-003". Tests pass with no GGUF.

Out of scope (unchanged): knowledge FAISS, chunking, UI dispatch,
Coding Agent, network research, Ollama, new packages.

Design: `docs/adr/ADR-0012-memory-semantic-recall.md` (accepted 2026-08-14).

---

## B-014 — Goal Manager: persistence, evidence-backed evaluate, lifecycle

- **Priority:** P1
- **Impact:** High. Persistent goals evaluated against recorded evidence.
- **Effort:** Medium.
- **Risk:** Low. Observational evaluate; no agent loop.
- **Dependencies:** B-006, B-013, ADR-0009 D12, ADR-0013.
- **Status:** done (2026-08-14)

Implemented `SQLiteGoalManager` (`astra/planning/goal_manager.py`) in
`planning.db` schema v2. `evaluate()` searches Memory + Knowledge (FTS
guaranteed, embeddings optional/additive), persists `GoalEvaluation`, does
not change `Goal.status`. Additive lifecycle: `activate`, `abandon`,
`mark_achieved` (fresh evaluate must be True). `ACHIEVED`/`ABANDONED`
terminal. Live `GoalSnapshot` via `ControllerStateProvider`. Dashboard Goals
panel is render-only.

Out of scope (unchanged): Goal Manager agent, auto task create/complete,
auto `mark_achieved`, other agents, UI dispatch, network, Ollama, new
packages, Knowledge FAISS, chunking, LLM-judged criteria, GoalStatus expansion.

Design: `docs/adr/ADR-0013-goal-manager.md` (accepted 2026-08-14).

---

## B-015 — Security Agent: deny-by-default policy engine

- **Priority:** P0
- **Impact:** High. Gate for tools, network research, and later agents.
- **Effort:** Medium.
- **Risk:** Low. Policy only; no execution.
- **Dependencies:** B-005, B-007, B-009, ADR-0014.
- **Status:** done (2026-08-14)

Implemented `PolicySecurityAgent` (`astra/security/agent.py`): deny-by-default
network (loopback denied; `allow_outbound_network=True` → REQUIRES_APPROVAL,
never silent ALLOW), file-write allowlist for configured data/log dirs,
`check_permanent_change` never ALLOW. In-memory decision history. Controller
registers `"security"`. Research `allow_network=True` consults Security;
`used_network` stays False. `SecuritySnapshot` in `astra.state` schema v3.
Dashboard Security panel is render-only.

Out of scope: Tools, Coding/Testing/Evaluation/Deployment, UI dispatch,
network sources, HTTP, Ollama, intercepting engine DB writes, Knowledge FAISS.

Design: `docs/adr/ADR-0014-security-agent.md` (accepted 2026-08-14).

---

## B-016 — Tool Interface + Security-Gated Invocation Boundary

- **Priority:** P0
- **Impact:** High. Safe request path for future tools and agents.
- **Effort:** Medium.
- **Risk:** Medium. Bypass via `get().invoke()` if the gate is weak.
- **Dependencies:** B-007, B-015, ADR-0015.
- **Status:** done (2026-08-14)

Implemented `InMemoryToolRegistry`, `GuardedTool`, and `GatedToolInvoker`.
Controller owns the catalog and `request_tool()`. Security is always consulted
before inner invoke. Production allowlist was empty in B-016; B-017 supersedes
that zero-tool production state with `read_text` only. Dual-key remains.
`record_human_approval()` records only. Architectural gate, not an OS sandbox.
`ToolSnapshot` schema v4. Dashboard Tools panel is render-only.

Out of scope: real tools (until B-017), subprocess/shell/HTTP/fs mutation, plugins, Coding/
Testing/Evaluation/Deployment, research-via-tools, UI dispatch, automatic
approval, Security ABC changes, routing engine DB writes through the gate.

Design: `docs/adr/ADR-0015-tool-interface.md` (accepted 2026-08-14).

---

## B-017 — First executable tool: `read_text`

- **Priority:** P0
- **Impact:** High. Proves the gated path can run one real capability.
- **Effort:** Medium.
- **Risk:** Medium. Must not become a general filesystem.
- **Dependencies:** B-016, ADR-0016.
- **Status:** done (2026-08-14)

Implemented `ReadTextTool`. Production registry and allowlist are exactly
`read_text`. UTF-8 read under existing Security write roots (data/log). 64 KiB
cap. Suffix allowlist `.log .txt .json .md .toml .csv`. Reuses
`check_file_write`; no `check_file_read`. `project_root` DENY. Dual-key and
`GuardedTool` unchanged. `ToolInvoker` not injected into agents. Schema stays v4.
Dashboard Tools panel remains render-only.

Out of scope: second tool, repo/source reads, Coding/Testing/Evaluation/
Deployment, plugins, shell/HTTP/eval, Security ABC rewrite, UI dispatch.

Design: `docs/adr/ADR-0016-read-text-tool.md` (accepted 2026-08-14).

---

## B-018 — Controlled project read: `check_file_read` + `read_project`

- **Priority:** P0
- **Impact:** High. Repo/CONSTITUTION/source readable without expanding write roots.
- **Effort:** Medium.
- **Risk:** Medium. Must not become a general filesystem or Coding Agent.
- **Dependencies:** B-017, ADR-0017.
- **Status:** done (2026-08-14)

Implemented `check_file_read` (ALLOW/DENY only) and `ReadProjectTool`. Production
registry and allowlist are exactly `{read_text, read_project}`. Authorized root
is `project_root()` with exclusions (`.git`, `data/`, `logs/`, secrets, binaries).
Suffix allowlist `.py .md .toml .txt .json .csv`. 64 KiB cap. `ToolSpec.reads_files`
maps to `check_file_read`; `read_text` still uses `check_file_write`. Write roots
not expanded. No directory listing. Dual-key and `GuardedTool` unchanged.
`ToolInvoker` not injected into agents. Schema stays v4. UI render-only.

Out of scope: listing, file-write tools,
Git mutation, shell/HTTP/eval, UI dispatch, schema v5 (B-019), applying proposals.

Design: `docs/adr/ADR-0017-controlled-project-read.md` (accepted 2026-08-14).

---

## B-019 — Coding Agent: proposal-only via ToolInvoker

- **Priority:** P0
- **Impact:** High. First agent that can propose file changes through the gated read path.
- **Effort:** Medium.
- **Risk:** High if it writes, lists directories, or receives the registry.
- **Dependencies:** B-018, ADR-0018.
- **Status:** done (2026-08-14)

Implemented `LocalCodingAgent`. Constructor takes `ToolInvoker`, never
`ToolRegistry`. Only `read_project` with caller `"coding"`. `targets=None` /
empty → zero tool calls. Max 3 targets. Truncation and 8000-char prompt budget
omit files. No-LLM → empty `changes`. `improves` never empty. `review()`
structural only. `applied` always `False`. Schema v5 `CodingSnapshot`.
Dashboard Coding panel render-only (no Apply). Qwen2.5-3B unchanged; inadequacy
documented. Production allowlist still `{read_text, read_project}`. No
`astra.security` import from `astra.coding`. ADR-0014–0017 files not edited.

Out of scope: Testing/Evaluation/Deployment, applying proposals, directory
listing, third tool, Git, shell/HTTP/eval, UI Apply, injecting `ToolInvoker`
into Research, model swap.

Design: `docs/adr/ADR-0018-coding-agent-proposal-only.md` (accepted 2026-08-14).

---

## B-020 — Proposal inspectability (one-shot review, schema v6)

- **Priority:** P0
- **Impact:** High. Human (and later Testing) can inspect the last proposal.
- **Effort:** Small.
- **Risk:** Low if `applied` stays False and full bodies stay off `ApplicationState`.
- **Dependencies:** B-019, ADR-0019.
- **Status:** done (2026-08-14)

Implemented last-proposal retain on `LocalCodingAgent`. `run()` calls `review()`
exactly once; `propose_change` does not call `review`. Schema v6 `CodingSnapshot`
fields: `last_change_paths`, `last_risks`, `last_objections`, `last_improves`,
`last_preview` (≤2048), `last_objection_count`. `applied` always `False`.
Status never `applied` / `approved` / `tested`. Full `new_content` on the agent
only. UI Coding panel render-only. Allowlist still `{read_text, read_project}`.
ADR-0014–0018 files not edited.

Out of scope: Testing/Evaluation/Deployment, apply, listing, third tool, Git,
shell/HTTP/eval, UI Apply, model swap, agent loops.

Design: `docs/adr/ADR-0019-coding-proposal-inspectability.md` (accepted 2026-08-14).

---

## B-021 — Isolated proposal workspace (not TestingAgent)

- **Priority:** P0
- **Impact:** High. Later Testing Agent needs a place to put proposed bytes.
- **Effort:** Medium.
- **Risk:** Medium if scratch is silently treated as a durable write root or if tests run against the live tree.
- **Dependencies:** B-020, ADR-0020.
- **Status:** done (2026-08-14)

Implemented `ProposalWorkspace` overlay under `TestingSettings.scratch_directory`
(default OS-temp child `astra-scratch`). Writes only after
`check_temporary_write` ALLOW. Scratch is not a `check_file_write` root.
`project_root` source bytes unchanged. Overlay = proposed files only (≤3,
≤64 KiB). No TestingAgent, subprocess, pytest, Apply, Git, or third tool.
Allowlist still `{read_text, read_project}`. Schema stays 6. ADR-0014–0019
files not edited.

Out of scope: TestingAgent behaviour, process policy, Apply, Deployment,
listing, third tool, Coding writes, model swap, schema v7, fake passed counts.

Design: `docs/adr/ADR-0020-isolated-proposal-workspace.md` (accepted 2026-08-14).

---

## B-022 — Process execution policy (fail-closed runner, not TestingAgent)

- **Priority:** P0
- **Impact:** High. Later Testing Agent needs one authorized spawn boundary.
- **Effort:** Medium.
- **Risk:** High if spawn is added before overlay can host tests, or if policy is treated as an OS jail.
- **Dependencies:** B-021, ADR-0021.
- **Status:** done (2026-08-14)

Implemented `check_process_execution(ProcessSpec)` (ALLOW/DENY only; production
defaults never ALLOW) and controller-owned `ProcessRunner.run()` that consults
Security and does not spawn. Empty executable allowlist. argv list only; no
shell; no `python -c`. cwd must be under scratch, never `project_root`.
`astra.testing` still has no `subprocess` import. Overlay unchanged. No
TestingAgent, pytest, `TestReport`, third tool, or schema bump. `nvidia-smi`
stays in presentation. ADR-0014–0020 files not edited.

Out of scope: spawn, TestingAgent behaviour, copytree worktree, OS sandbox,
Apply, Deployment, listing, third tool, Coding writes, model swap, schema v7,
fake passed counts.

Design: `docs/adr/ADR-0021-process-execution-policy.md` (accepted 2026-08-14).

---

## B-023 — Copied-subset isolated test workspace

- **Priority:** P0
- **Impact:** High. Later Testing Agent needs a session tree that preserves package layout so imports do not silently hit the live repo.
- **Effort:** Medium.
- **Risk:** High if copytree of the repo copies secrets, or if the copy is treated as an OS jail.
- **Dependencies:** B-021, B-022, ADR-0022.
- **Status:** done (2026-08-14)

Implemented `CopiedSubsetWorkspace` as a new type (does not turn
`ProposalWorkspace` into copytree). `copy()` writes allowlisted files under
`scratch/<session>/` after `check_temporary_write` ALLOW. `overlay()` writes
into that existing session (≤3 files, ≤64 KiB). Live `project_root` bytes
unchanged. Explicit exclusions: `.git`, `data/`, `.env`, GGUF, secrets,
`__pycache__`. Symlink sources fail closed. Controller-owned; not an agent;
not injected into Coding. `ProcessRunner` unchanged (`spawned=False`). No
`subprocess` in `astra.testing`. Schema stays 6. ADR-0014–0021 files not
edited.

Out of scope: TestingAgent behaviour, spawn, pytest, unittest execution,
`TestReport`, Apply, Deployment, listing, third tool, Coding writes, model
swap, schema v7, fake passed counts, OS sandbox.

Design: `docs/adr/ADR-0022-copied-subset-workspace.md` (accepted 2026-08-14).

---

## B-024 — Controlled process spawn in copied-subset session

- **Priority:** P0
- **Impact:** High. Later Testing Agent needs one authorized spawn against the copy.
- **Effort:** Medium.
- **Risk:** High if spawn inherits the parent environment, uses a shell, or is treated as an OS jail.
- **Dependencies:** B-023, ADR-0023.
- **Status:** done (2026-08-14)

Implemented `ProcessRunner.run(spec, session_id=...)` as the sole spawn
boundary. Production `from_settings` allowlists `sys.executable` only.
Structured argv, `shell=False`, no `-c`, no shells. cwd must be the exact
copied session. Explicit child env (`PYTHONPATH` = session; no `ASTRA_HOME`).
Timeout cap 60s; probe default 5s; stdout/stderr 64 KiB. Direct child killed
on timeout (not a process tree). Harmless `astra.testing.probe` token.
No TestingAgent, pytest, `TestReport`, third tool, or schema bump.
`nvidia-smi` stays in presentation. ADR-0014–0022 files not edited.

Out of scope: TestingAgent behaviour, unittest/pytest as product feature,
`TestReport`, Apply, Deployment, listing, third tool, Coding writes, model
swap, schema v7, fake passed counts, OS sandbox, network isolation.

Design: `docs/adr/ADR-0023-controlled-process-spawn.md` (accepted 2026-08-14).

---

## B-025 — Honest proposal testing through copied session and ProcessRunner

- **Priority:** P0
- **Impact:** High. Self-improvement cannot evaluate a proposal that was never tested.
- **Effort:** Medium.
- **Risk:** High if tests run against live `project_root`, exit code is treated as a `TestReport`, or the session is left behind.
- **Dependencies:** B-024, ADR-0024.
- **Status:** done (2026-08-14)

Implemented `LocalTestingAgent.test_proposal`: copy allowlisted subset →
overlay proposal → `ProcessRunner` runs
`sys.executable -B -s -m unittest discover -s tests -t .` in that session →
parse unittest output into `TestReport` → discard session in `finally`.
Counts come from `Ran N` / `OK` / `FAILED`. Exit code stored, not used as
pass/fail. Empty, truncated, timeout, spawn failure, and unparseable output
are incomplete and not passing. One execution; no retry. `run_suite` stays
`NotImplementedError`. Not a Tool. Not given to Coding. Schema stays 6.
ADR-0014–0023 files not edited.

Out of scope: `run_suite`; pytest; Evaluation; Apply; Deployment; Git;
third tool; Coding writes; retries; OS sandbox; claiming exit 0 means tests
passed.

Design: `docs/adr/ADR-0024-honest-proposal-testing.md` (accepted 2026-08-14).

---

## B-026 — Evaluation of tested proposals before human approval

- **Priority:** P0
- **Impact:** High. Deployment must not proceed on tests alone; evaluation is a distinct gate.
- **Effort:** Small.
- **Risk:** High if evaluation auto-approves, reruns tests, or treats `improved` as deployable.
- **Dependencies:** B-025, ADR-0025.
- **Status:** done (2026-08-14)

Implemented `LocalEvaluationAgent.evaluate_proposal(proposal, tests)`.
Fail-closed judgement over existing DTOs. No spawn, no writes, no
`ApprovalRecord`, no Deployment call. `evaluation_passed` is separate from
`TestReport.passed`, from `EvaluationReport.improved` (still measurement-only),
and from `deployment_authorized` (always False). `evaluate` / `record_baseline`
stay `NotImplementedError`. Schema stays 6. ADR-0014–0024 files not edited.

Out of scope: baseline benchmarking; Approval; Apply; Deployment; Git;
third tool; Coding writes; test re-execution; retries; claiming tests-passed
means deployable.

Design: `docs/adr/ADR-0025-evaluation-of-tested-proposals.md` (accepted 2026-08-14).

---

## B-027 — Human-approved controlled deployment

- **Priority:** P0
- **Impact:** High. First permanent write to `project_root`.
- **Effort:** Medium.
- **Risk:** High if approval is inferred, paths escape `project_root`, or rollback is claimed without VersionManager.
- **Dependencies:** B-026, ADR-0026.
- **Status:** done (2026-08-14)

Implemented `LocalDeploymentAgent.deploy(proposal, tests, evaluation, approval)`.
Writes only after a matching `ApprovalRecord.subject` fingerprint and
`check_permanent_change` = `REQUIRES_APPROVAL`. Sole write site:
`astra.deployment.apply.commit_files`. Coding/Testing/Evaluation stay
write-free. No write Tool. `rollback` stays `NotImplementedError`. Schema
stays 6. ADR-0014–0025 files not edited.

Out of scope: Git; VersionManager; rollback; auto-approve; write Tool;
Coding writes; UI Deploy control; OS sandbox.

Design: `docs/adr/ADR-0026-human-approved-controlled-deployment.md` (accepted 2026-08-14).

---

## B-028 — VersionManager + honest rollback

- **Priority:** P0
- **Impact:** High. First undo path for an approved deployment.
- **Effort:** Medium.
- **Risk:** High if rollback is automatic, Git-backed, atomicity is claimed, or a new permanent-write root is added.
- **Dependencies:** B-027, ADR-0027.
- **Status:** done (2026-08-14)

Implemented controller-owned `LocalVersionManager`. Successful deploy
records an immutable pre-image under `{scratch}/versions/` via
`check_temporary_write`. `rollback(version_id, approval)` restores that
pre-image after `rollback_fingerprint(version_id)` matches a human
`ApprovalRecord`. `check_permanent_change` still never ALLOW. Partial
restore is `rollback_error`. Not Git. Not an Agent. Not a Tool. Schema
stays 6. ADR-0014–0026 files not edited.

Out of scope: Git; `run_suite`; baseline measurement; plugins / third tool;
OS sandbox; automatic rollback; UI Rollback control; schema 7.

Design: `docs/adr/ADR-0027-version-manager-and-honest-rollback.md` (accepted 2026-08-14).

---

## B-029 — Baseline + comparative evaluation

- **Priority:** P0
- **Impact:** High. Tests passing must not be treated as measured improvement.
- **Effort:** Medium.
- **Risk:** High if numbers are fabricated, evaluation auto-approves, or a new write root is added.
- **Dependencies:** B-026, ADR-0028.
- **Status:** done (2026-08-14)

Implemented `LocalBaselineStore` plus `evaluate` / `record_baseline`.
Baselines are immutable under `{scratch}/baselines/` via
`check_temporary_write`. Comparison is exact per metric (higher/lower/equal).
Empty or incomplete evidence leaves `improved` false.
`deployment_authorized` stays False. Two-argument `evaluate_proposal` keeps
B-026 meaning. Schema stays 6. ADR-0014–0027 files not edited.

Out of scope: `run_suite`; Git; plugins; third tool; fake benchmarks;
automatic approval/deployment; UI mutation; schema 7.

Design: `docs/adr/ADR-0028-baseline-and-comparative-evaluation.md` (accepted 2026-08-14).

## B-030 — Real run_suite + unified testing orchestration

- **Priority:** P0
- **Impact:** High. The project suite must run in a copied session, never against live `project_root`.
- **Effort:** Medium.
- **Risk:** High if a second subprocess path appears, overlay leaks into `run_suite`, or results are faked from exit code.
- **Dependencies:** B-023, B-024, B-025, ADR-0029.
- **Status:** done (2026-08-14)

Implemented `LocalTestingAgent.run_suite`. Copy → unittest via `ProcessRunner`
→ `TestReport` → discard. No overlay. Same command and parser as
`test_proposal`. Live tree is never the test cwd. Schema stays 6.
ADR-0014–0028 files not edited.

Out of scope: autonomous loops; Coding → Testing retry; automatic
Evaluation / Approval / Deployment; Git; plugins; third tool; UI Run Tests;
OS sandbox; pytest; schema 7; fake results.

Design: `docs/adr/ADR-0029-canonical-isolated-test-suite-execution.md` (accepted 2026-08-14).

## B-031 — End-to-end controlled change pipeline

- **Priority:** P0
- **Impact:** High. Stages existed; callers still had to sequence them by hand without a fail-closed gate.
- **Effort:** Medium.
- **Risk:** High if the pipeline auto-approves, invents evidence, or adds a second spawn path.
- **Dependencies:** B-025, B-026, B-027, B-028, B-030, ADR-0030.
- **Status:** done (2026-08-14)

Implemented `AstraController.process_proposal` plus `PipelineResult`.
Copy/test/eval/deploy remain owned by their agents. Missing approval writes
nothing. Matching `ApprovalRecord` deploys and records a VersionManager
snapshot. Schema stays 6. ADR-0014–0029 files not edited.

Out of scope: Git; automatic approval/rollback; third tool; UI Run/Deploy;
schema 7; OS sandbox; Coding writes; agent loops.

Design: `docs/adr/ADR-0030-end-to-end-controlled-change-pipeline.md` (accepted 2026-08-14).

## B-032 — End-to-end adversarial safety hardening

- **Priority:** P0
- **Impact:** High. Pipeline must remain fail-closed under malicious input.
- **Effort:** Medium.
- **Risk:** High if forged approval, traversal, or a second spawn path can write.
- **Dependencies:** B-031, ADR-0031.
- **Status:** done (2026-08-14)

Added adversarial tests over the live B-001–B-031 architecture. Live
`project_root` bytes stay identical after unauthorized attempts. Matching
approval still deploys only intended files. Schema stays 6. ADR-0014–0030
files not edited. Not an OS sandbox.

Out of scope: Git; third tool; schema 7; auto-approval; OS sandbox; Coding
writes; extra subprocess boundaries.

Design: `docs/adr/ADR-0031-adversarial-end-to-end-safety-hardening.md` (accepted 2026-08-14).

## B-033 — Controlled Git version history and recovery

- **Priority:** P0
- **Impact:** High. Recoverability needs honest Git history without making Git an authority.
- **Effort:** Medium.
- **Risk:** High if Git becomes a Tool, bypasses approval, reuses ProcessRunner, or fabricates commit ids.
- **Dependencies:** B-032, ADR-0032.
- **Status:** done (2026-08-14)

Added controller-owned `GitVersionStore` / `GitRunner`. Successful permanent
deploys may record Git history against `project_root`. Git restore requires a
separate matching `ApprovalRecord`. Git failure after a successful write is
`deployed_history_failed` with no fake commit id and no automatic rollback.
VersionManager snapshots remain. Schema stays 6. ADR-0014–0031 files not
edited. Not an OS sandbox.

Out of scope: third tool; schema 7; auto-approval; OS sandbox; Coding writes;
Git as a Tool; UI Git buttons; replacing VersionManager.

Design: `docs/adr/ADR-0032-controlled-git-version-history.md` (accepted 2026-08-14).

## B-034 — Controlled plugin architecture and extension registry

- **Priority:** P0
- **Impact:** High. Future capabilities need a registry that cannot bypass Security.
- **Effort:** Medium.
- **Risk:** High if plugins become Tools, spawn processes, or auto-execute on discover.
- **Dependencies:** B-033, ADR-0033.
- **Status:** done (2026-08-15)

Added controller-owned `LocalPluginRegistry`. Descriptors register disabled.
Discovery reads static `plugin.toml` only. External plugin execution stays
disabled. Capability names are descriptive, not grants. Schema stays 6.
ADR-0014–0032 files not edited. Not an OS sandbox.

Out of scope: marketplace; remote install; third tool; schema 7; auto-approval;
plugin subprocess sandbox; Git Tool; Coding writes.

Design: `docs/adr/ADR-0033-controlled-plugin-extension-architecture.md` (accepted 2026-08-15).

## B-035 — Security and hardening audit

- **Priority:** P0
- **Impact:** High. Remaining policy gaps must fail closed without a redesign.
- **Effort:** Medium.
- **Risk:** High if hardening is claimed as an OS sandbox or invents new authority.
- **Dependencies:** B-034, ADR-0034.
- **Status:** done (2026-08-15)

Audited the live ChangeProposal → deploy/rollback/Git/plugin path. Closed
fixable application-policy gaps: case-fold copy denials, secret overlay
refusal, absolute/UNC proposal rejection, write-time revalidation, non-zero
exit as non-pass, gated session `__init__.py`, hardened `nvidia-smi` env.
Schema stays 6. ADR-0014–0033 files not edited. Not an OS sandbox.

Out of scope: OS sandbox; Docker/WSL; third tool; schema 7; auto-approval;
plugin execution; Coding writes; Git authority; UI mutation controls.

Design: `docs/adr/ADR-0034-security-hardening-audit.md` (accepted 2026-08-15).

## B-036 — End-to-end integration, recovery, and failure-path validation

- **Priority:** P0
- **Impact:** High. The pipeline must behave as one fail-closed state machine.
- **Effort:** Medium.
- **Risk:** High if integration tests mock away approval, Security, or deploy.
- **Dependencies:** B-035, ADR-0035.
- **Status:** done (2026-08-15)

Controller-owned integration suite over the live services. Success, stop,
rollback, Git history/failure, repeated deploys, exception mapping, and
scratch cleanup. Schema stays 6. ADR-0014–0034 files not edited. Not an OS
sandbox. Rollback remains non-atomic.

Out of scope: OS sandbox; third tool; schema 7; auto-approval; plugin
execution; Coding writes; Git authority; UI mutation controls.

Design: `docs/adr/ADR-0035-end-to-end-integration-and-recovery.md` (accepted 2026-08-15).

## B-037 — Final architecture and documentation consistency pass

- **Priority:** P0
- **Impact:** High. Current-state docs must match the live tree.
- **Effort:** Medium.
- **Risk:** Medium if historical ADRs are rewritten or new capabilities are invented.
- **Dependencies:** B-036, ADR-0036.
- **Status:** done (2026-08-15)

Reconciled current-state documentation with the implemented pipeline.
ADR-0036 is the state-of-architecture record; ADR-0014–0035 files were not
edited. Schema stays 6. Allowlist `{read_text, read_project}`. No new
capability. Residual limits stay documented.

Out of scope: OS sandbox; third tool; schema 7; auto-approval; plugin
execution; Coding writes; Git authority; UI mutation controls; B-038.

Design: `docs/adr/ADR-0036-final-architecture-state.md` (accepted 2026-08-15).

## B-038 — Final production-readiness audit and closure

- **Priority:** P0
- **Impact:** High. Last gate before treating the system as closed at this stage.
- **Effort:** Medium.
- **Risk:** High if the audit invents architecture or weakens authority gates.
- **Dependencies:** B-037.
- **Status:** done (2026-08-15)

Audited the live pipeline, security, and authority boundaries against
ADR-0036. Closed one evidence-integrity gap: `tests_run <= 0` is never a
pass, including crafted outcomes; deploy/evaluate refuse it with zero
live-project mutation. Schema stays 6. Allowlist `{read_text, read_project}`.
No ADR-0037. Architecture closed at this stage.

Out of scope: OS sandbox; third tool; schema 7; auto-approval; plugin
execution; Coding writes; Git authority; UI mutation controls; new
subprocess boundary.

Design: `docs/adr/ADR-0036-final-architecture-state.md` (accepted 2026-08-15).

## B-039 — Production conversation orchestrator

- **Priority:** P0
- **Impact:** High. Connects existing engines to the real talk path.
- **Effort:** Medium.
- **Risk:** High if research becomes the default chat backend again, or if testing/deploy leak into conversation.
- **Dependencies:** B-038.
- **Status:** done (2026-08-15)

`ConversationOrchestrator` is the production user-turn entrypoint. UI calls
`ConversationPort.ask` only. Memory retrieve/persist uses the Memory Engine.
Research/planning/tools/goals are selected, not default. Allowlist stays
`{read_text, read_project}`. Testing/evaluation/deployment/Git stay off the
talk path. Empty research does not surface `no findings to summarise`.

Design: `docs/adr/ADR-0038-production-conversation-orchestration.md`.

Out of scope: autonomous coding; auto-approval; third tool; schema 7; UI redesign.

## B-040 — Runtime capability completion

- **Priority:** P0
- **Impact:** High. Makes B-039 useful in the frozen executable and in real turns.
- **Effort:** Medium.
- **Risk:** High if web research is pretended or if the improvement pipeline leaks into talk.
- **Dependencies:** B-039.
- **Status:** done (2026-08-15)

Rebuilt `Primus.exe` from current source. `--probe` verifies frozen orchestrator
path. Memory paraphrases, local corpus research, knowledge ingest, planning
seed-then-`plan()`, goals via existing manager. Allowlist stays
`{read_text, read_project}`. No web HTTP source. No third tool.

Design: `docs/adr/ADR-0039-runtime-capability-completion.md`.

Out of scope: web research; autonomous coding; auto-approval; third tool; schema 7; UI redesign.

## B-041 — Controlled external research

- **Priority:** P0
- **Impact:** High. Research can retrieve approved public documents instead of pretending local files are the web.
- **Effort:** Medium.
- **Risk:** High if SSRF, a network Tool, silent memory ingest, or fake browsing leak in.
- **Dependencies:** B-040, ADR-0014, ADR-0011.
- **Status:** done (2026-08-15)

SSRF-gated HTTP/HTTPS document retrieval behind `ResearchAgent`. Deny-by-default
`research.allow_external`. Redirect and DNS re-checked. HTML → text. Evidence
reaches LLM synthesis. Failures are honest. Research does not auto-write Memory
or Knowledge. Schema 6. Allowlist `{read_text, read_project}`. Frozen
`Primus.exe` rebuilt. `--probe` unchanged. Optional `--probe-research`.

Design: `docs/adr/ADR-0040-controlled-external-research.md` (ADR-0039 already
records B-040).

Out of scope: full browser; unrestricted internet; network Tool; autonomous
coding; auto-approval; schema 7; UI redesign; B-042.

## Runtime intelligence repair (2026-08-16)

Not a new milestone. Repair of the existing B-039 talk path (ADR-0041).

Composite (non-exclusive) orchestration; structured ChatML context; persistent
`UserContext` and `[personality]` settings; justified durable memory; plan/goal
continuity across restart; developer `OrchestrationTrace`. Schema 6. Allowlist
`{read_text, read_project}`. Probes: `--probe-runtime-write` /
`--probe-runtime-read`.

Design: `docs/adr/ADR-0041-runtime-intelligence-integration.md`.

Out of scope: B-042; UI redesign; third Tool; schema 7; autonomy; weakening SSRF.

