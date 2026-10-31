# Astra Architecture

**Current beginner map:** [`CURRENT.md`](CURRENT.md) and [`memory.md`](memory.md).
This file is the long-form package map, including historical stages.

Authoritative sources: [`CONSTITUTION.md`](../CONSTITUTION.md) and the ADRs in
[`adr/`](adr/). This document maps those rules onto the repository and records
the boundaries between packages. It does not introduce new architecture.

**Current state (runtime intelligence repair, B-041 research, B-040 capabilities, B-039 orchestrator, B-038 backend closed):** ADR-0036 is the backend
state-of-architecture record. ADR-0037 is the conversation-first UI and
Primus identity record. ADR-0038 is the production conversation orchestrator.
ADR-0039 is runtime capability completion (frozen exe, local corpus research,
knowledge ingest, planning seed). ADR-0040 is controlled HTTP/HTTPS document
retrieval behind the Research Agent. ADR-0041 is the runtime intelligence
repair: composite orchestration, structured ChatML context, personality
outside the model, and restart continuity. ADR-0014–0040 are not rewritten. The live window
is Primus, not a developer dashboard. Technical panels live in System View.

Two pipelines:

- **Normal assistant:** `ConversationOrchestrator` (B-039/B-041/ADR-0041). Memory, knowledge, research, planning, tools, and goals may run together when justified. Context is injected inside the ChatML system turn. Research may use local corpus and, when `research.allow_external` is enabled, SSRF-gated HTTP/HTTPS documents. Not research-by-default. Not a browser.
- **Development / self-improvement:** `process_proposal` (B-031). Testing, evaluation, ApprovalRecord, deploy. Conversation does not invoke that pipeline.

## Current architecture

Schema **6**. Production Tool allowlist exactly `{read_text, read_project}`.
No third Tool. No OS sandbox. No auto-approval.
`AstraController.process_proposal` composes the pipeline; it does not create
approval.

```
ChangeProposal
  → ProposalWorkspace / CopiedSubsetWorkspace
  → LocalTestingAgent.test_proposal
  → ProcessRunner (sys.executable, shell=False, copied-session cwd)
  → real unittest → TestReport
  → LocalEvaluationAgent.evaluate_proposal → EvaluationReport
  → human ApprovalRecord (evidence_fingerprint)
  → LocalDeploymentAgent.deploy
  → LocalVersionManager pre-image
  → optional GitVersionStore history
  → rollback via separate rollback_fingerprint ApprovalRecord
```

**Testing.** `test_proposal` overlays into a copied session and runs unittest
via ProcessRunner. `run_suite` is the canonical isolated suite run (copy, no
overlay, same runner). `TestReport` comes from process output. Empty,
malformed, truncated, timed-out, or non-zero-exit runs are not a pass. Exit
code is not a fake test count. Isolation is policy (copied subset), not an OS
sandbox.

**Evaluation.** Requires test evidence. Baseline comparison is available;
regression fails evaluation. `improved` is measurement-only.
`deployment_authorized` is not authority. Evaluation cannot spawn or deploy.

**Approval.** `ApprovalRecord` is human-supplied. Agents do not manufacture it.
`subject` binds to the exact evidence fingerprint. Stale or wrong approval is
rejected. Rollback and Git restore have their own fingerprints.

**Deployment.** Permanent writes require `check_permanent_change`
`REQUIRES_APPROVAL` plus a matching record. Paths are validated before write.
Only intended files change. Partial writes are reported honestly. No automatic
rollback.

**Versioning.** VersionManager is recovery, not authorization. Snapshots are
immutable. New files record absence. Rollback restores or deletes from the
snapshot after its own approval. Rollback is not atomic. Corrupt or traversal
snapshots fail closed.

**Git.** History/recovery only. Not deployment authority. Unavailable Git does
not block deploy. Failure after write is `deployed_history_failed` (no fake
hash, no auto-rollback). Git restore needs its own approval. `GitRunner` is
the sole Git subprocess boundary.

**Process.** ProcessRunner is the sole testing spawn. Production executable is
`sys.executable`. Structured argv, `shell=False`, no model-selected
executables, exact session cwd, explicit child env, bounded stdout/stderr,
timeout, direct-child termination only. No child network jail. Grandchildren
may survive. Host resources remain OS-permitted.

**Security.** Policy only. Permanent-write gate is `check_permanent_change`.
Scratch uses temporary-write policy. Tools remain exactly two. No process
Tool. Coding is write-free. Plugins do not execute untrusted code.

**Plugins.** `LocalPluginRegistry` is infrastructure. One-level `plugin.toml`.
Metadata is not authority. Register/enable do not execute. Only trusted
builtin `{metadata, read_only}` initialize. External execution disabled.
`enabled_plugin_ids` defaults empty.

**UI.** Render-only for approval/deploy/rollback. Talk goes through
`ConversationOrchestrator` (ADR-0038). Capability display is informational.
UI does not create approval, deploy, rollback, or spawn.

**Coding.** Produces proposals. Does not write project files. Does not receive
ProcessRunner or DeploymentAgent. Allowlist remains `{read_text, read_project}`.

## Module map

The constitution names sixteen components. Fourteen become top-level packages,
and two are nested inside the package that owns their lifecycle. Nothing is
renamed, omitted, or duplicated.

| Constitution component | Package | Notes |
|---|---|---|
| Core Controller | `astra.core` | Orchestration seam only. `process_proposal` (B-031) composes test → evaluate → human approval → deploy. Does not create approval. End-to-end integration and recovery proven in B-036. |
| Planning Engine | `astra.planning` | Backlog and task contracts |
| Memory Engine | `astra.memory` | Durable store plus derived vector index |
| Knowledge Engine | `astra.knowledge` | Classification before storage |
| Goal Manager | `astra.planning.goals` | Nested; `SQLiteGoalManager` (B-014, ADR-0013) |
| Research Agent | `astra.research` | Implemented (B-009). Local sources only. Returns evidence; does not write knowledge or memory. No web/network research. |
| Coding Agent | `astra.coding` | Implemented (B-019 / B-020). Proposal-only `LocalCodingAgent`. Reads via `ToolInvoker` / `read_project`. Retains last proposal for inspection. Never applies. |
| Testing Agent | `astra.testing` | `LocalTestingAgent.test_proposal` (B-025) runs unittest in a copied+overlaid session via `ProcessRunner`. `run_suite` (B-030) runs the same unittest command on an unchanged copied snapshot (no overlay). Honest `TestReport` from unittest output; non-zero exit is not a pass. Live `project_root` is never the test cwd. `ProposalWorkspace` (B-021) overlay-only. `CopiedSubsetWorkspace` (B-023) allowlisted copy. `ProcessRunner` (B-022/B-024) sole spawn boundary inside `astra.testing`. Git is not in this package. |
| Evaluation Agent | `astra.evaluation` | `LocalEvaluationAgent.evaluate_proposal` (B-026) judges `ChangeProposal` + existing `TestReport`. `evaluate` / `record_baseline` (B-029) record immutable metric samples and compare them. Does not run tests, approve, or deploy. |
| Deployment Agent | `astra.deployment` | `LocalDeploymentAgent.deploy` (B-027) applies an approved proposal to `project_root` after `TestReport` + `EvaluationReport` + human `ApprovalRecord`. `LocalVersionManager` (B-028) records an immutable pre-image under scratch; `rollback` restores it after a matching `ApprovalRecord`. `GitVersionStore` (B-033) records Git history after a successful deploy; Git restore needs its own `ApprovalRecord`. Git is not authorization. |
| Security Agent | `astra.security` | Implemented (B-015 + B-018 `check_file_read` + B-021 `check_temporary_write` + B-022 `check_process_execution`). Policy only; deny-by-default. Does not execute. Adversarial fail-closed proof in B-032. Hardening audit in B-035. Not an OS sandbox. |
| Plugin Manager | `astra.plugins` | `LocalPluginRegistry` (B-034). Controller-owned. Registers descriptors; metadata is not authority. External plugin execution disabled. Not a Tool. |
| Tool Interface | `astra.tools` | Implemented (B-016 gate + B-017 `read_text` + B-018 `read_project`). Catalog + security-gated request path. Production allowlist exactly `{read_text, read_project}`. `read_text` = data/log roots. `read_project` = `project_root` with exclusions. Coding (B-019) is the first agent to receive `ToolInvoker` and uses `read_project` only. Research does not use tools. |
| Configuration Manager | `astra.configuration` | Implemented (B-005 Pydantic v2; TOML loader stdlib) |
| Logging System | `astra.logging` | Implemented |
| Version Manager | `astra.deployment.versioning` | Nested; `LocalVersionManager` (B-028) is controller-owned, not an Agent. Pre-image snapshots under scratch. Git history is a separate `GitVersionStore` (B-033), also controller-owned, not a constitution component. |

`astra.reasoning` is not a constitution component. It exists because ADR-0001
requires the LLM and embedding runtimes to sit behind replaceable interfaces, and
those interfaces need a home that neither the Core Controller nor the Memory
Engine owns. Contracts plus llama.cpp backends (B-002).

`astra.state` is not a constitution component. It exists because ADR-0006
requires a machine-readable state contract that the Presentation Layer
(dashboard, demo mode, future API server) can consume without importing from
core modules. It is a leaf: it imports nothing from the rest of `astra` and
contains dataclasses and enumerations only. See ADR-0006 for the full
rationale and the five capability-status values it defines.

### Why two components are nested

A Goal Manager that lives outside the Planning Engine would need a bidirectional
dependency with it: goals produce tasks, and task outcomes update goals. Nesting
keeps that cycle inside one package boundary. The same argument applies to the
Version Manager and the Deployment Agent, which promotes versions.

Both remain separate modules with their own interfaces, satisfying the rule that
every module has a single responsibility.

## Dependency direction

Dependencies point downward. A package may import from the layers below it and
never from the layers above.

```
   ui/  (Primus window — ConversationPort.ask only; no engine imports)
        ↑
   astra.presentation  (ApplicationState + ConversationOrchestrator)
        ↑
      core  (controller, shared agent contract, lifecycle)
        ↑
   agents: research, coding, testing, evaluation, deployment, security
        ↑
   engines: planning, memory, knowledge, reasoning
        ↑
   extension: plugins, tools
        ↑
   foundation: configuration, logging, state
```

`astra.state` sits at the foundation layer because it imports nothing else.
`astra.presentation` reads engines to assemble `ApplicationState`.
`ConversationOrchestrator` (B-039 / ADR-0038) coordinates a user turn through
existing controller-owned engines and agents; it does not reimplement them.
The `ui/` directory (outside `astra/`) imports only `astra.state`
and `astra.presentation` — never engines or agents directly. This boundary is
enforced by `tests/test_ui_contracts.py`.

Rules that follow from this:

- `configuration`, `logging`, and `state` import nothing from `astra`. Leaves.
- `ui/` imports only from `astra.state` and `astra.presentation`. AST-enforced.
- `astra.presentation` may read engines to assemble status.
  `ConversationOrchestrator` may retrieve/write Memory Engine records and call
  existing `dispatch` / `request_tool` / `plan` / `goals` for a justified turn,
  including several of those operations in one request (ADR-0041).
  It must not dispatch testing, evaluation, deployment, or coding.
- Engines and agents depend on **interfaces**, never on another module's
  concrete implementation.
- Imports within the same layer are allowed for contracts only. The Deployment
  Agent, for example, imports the proposal, test report, and evaluation report
  types because its approval gate is defined in terms of them.
- The Core Controller is the only module allowed to wire concrete
  implementations together.
- No module reaches into another module's private names.

## The memory invariant

From ADR-0001, and binding on every future change:

> SQLite holds the truth. The vector index is derived and disposable.

A vector index may be deleted at any time and rebuilt from the durable store.
Nothing may be written only to the index. This is what makes an embedding model
upgrade a rebuild rather than a data-loss event, and it is the mechanism behind
the constitution's requirement that memory survive model replacement.

**B-003 implementation:** `FaissVectorIndex` (`IndexFlatIP`, 768-dimensional)
stores L2-normalized vectors (mandatory — raw vectors are rejected). Deletion
is logical (tombstoning); physical reclaim requires `clear()` + `rebuild_index()`.
Persistence stores `index.faiss`, `mapping.json`, and `meta.json` (includes
dimension, model identity, normalization scheme, format version) in the
configured `vector_index_path` directory. A mismatch on load raises
`VectorIndexError` rather than silently mixing incompatible vectors.

**B-013 implementation:** `MemoryIndexer` (`astra/memory/indexer.py`) embeds
whole memory records on write when the embedding backend is available, writes
ADR-0003 D12 provenance columns, and maintains the derived FAISS index.
`SQLiteMemoryStore` stays SQLite-only. If embeddings are unavailable, storage
still succeeds. FTS5 search is unchanged. The controller loads/saves the FAISS
index across `start()`/`shutdown()` and does **not** embed or auto-rebuild at
startup (ADR-0012 D6). `rebuild_memory_index()` is the recovery path; it
refuses to `clear()` when the backend is unavailable. `MemorySource` keeps
FTS5 as the baseline and may merge semantic hits (ADR-0012 D11). Knowledge
vectors remain out of scope for B-013.

**B-014 implementation:** `SQLiteGoalManager` persists goals in `planning.db`
schema v2. `evaluate()` is observational (Memory + Knowledge search; FTS
guaranteed; embeddings optional). `activate` / `abandon` / `mark_achieved`
are additive. Live state is `GoalSnapshot` via `ControllerStateProvider`.
Dashboard Goals panel is render-only.

**B-015 implementation:** `PolicySecurityAgent` answers `check_network_access`,
`check_file_write`, `check_file_read` (B-018 / ADR-0017), and
`check_permanent_change` without executing anything.
Loopback is denied (ADR-0004). `allow_outbound_network=True` yields
`REQUIRES_APPROVAL`, never silent ALLOW. File writes are allowlisted to
configured data/log directories. Permanent changes never ALLOW. The controller
registers the agent as `"security"`. Research consults `check_network_access`
when `allow_network=True` and no HTTP source is registered (still no socket).
When `research.allow_external` is enabled, retrieval uses `check_research_url`
and `check_resolved_ip` instead. Tools still cannot fetch.

**B-016 implementation:** `InMemoryToolRegistry` is a catalog only (`register` /
`get` / `specs`; no `invoke` on the registry). `GatedToolInvoker` is the only
supported request path. Security is consulted before any inner `Tool.invoke`.
`ALLOW` is not sufficient to run without the execution allowlist. B-016 shipped
an empty allowlist; B-017 set it to `{"read_text"}`; B-018 sets it to
`{"read_text", "read_project"}`. `GuardedTool` makes
`registry.get(name).invoke()` fail closed without the gate token. This is an
architectural gate, not an OS sandbox.
Memory/Knowledge/Planning SQLite writes stay outside the gate. Research still
does not use `astra.tools`.

**B-017 implementation:** `ReadTextTool` (`read_text`) is the first executable
tool. UTF-8 read under existing Security write roots (data/log). 64 KiB cap.
Suffix allowlist `.log .txt .json .md .toml .csv`. Dual-key allowlist is
exactly `{"read_text"}`. `project_root` remains DENY via `read_text`. No
`check_file_read` in B-017 (added in B-018).
Research is not given `ToolInvoker`.

**B-018 implementation:** `check_file_read` (ALLOW/DENY only) plus
`ReadProjectTool` (`read_project`). Authorized root is `project_root()` with
explicit exclusions (`.git`, `data/`, `logs/`, secrets, binaries). Suffix
allowlist `.py .md .toml .txt .json .csv`. 64 KiB cap. `ToolSpec.reads_files`
maps path args to `check_file_read`; `read_text` still uses `check_file_write`.
Production allowlist exactly `{"read_text", "read_project"}`. Write roots are
not expanded. No directory listing. No Coding Agent in B-018. Schema stays v4.

**B-019 implementation:** `LocalCodingAgent` (`astra/coding/agent.py`) is a
proposal-only `CodingAgent`. The controller injects `ToolInvoker`, never the
registry. Every file read is `read_project` with caller `"coding"`.
`targets=None` or empty performs zero tool calls (no directory listing).
At most 3 targets per call. Truncated or over-budget files are omitted.
No-LLM yields empty `changes` (no identity `FileChange`). `improves` is never
empty. `review()` is structural only. `applied` is always `False`. Schema v5
adds `CodingSnapshot`. Dashboard Coding panel is render-only (no Apply).
Model stays Qwen2.5-3B-Instruct; quality of generated code is a documented
limit, not a reason to swap models. Allowlist remains
`{read_text, read_project}`. Coding does not import `astra.security`.

**B-020 implementation:** `LocalCodingAgent` retains `last_proposal` after
`propose_change` / `run`. `run()` calls `review()` once (no retry loop).
`CodingSnapshot` schema v6 adds inspectable fields: paths, risks, objections,
improves names, bounded preview (≤2048 chars). Full `new_content` stays on the
agent object, not in `ApplicationState`. `applied` remains `False`. Dashboard
shows the new fields; still no Apply. Allowlist unchanged. No Testing Agent
as of B-020 (landed in B-025), listing, write tool, or model swap.

**B-021 implementation:** `ProposalWorkspace` (`astra/testing/workspace.py`)
materializes `ChangeProposal` files under a dedicated scratch overlay
(`<temp>/astra-scratch` by default). Writes happen only after
`SecurityAgent.check_temporary_write` ALLOW. Scratch is **not** a
`check_file_write` root. `project_root` bytes are unchanged. Overlay is
proposed files only (≤3 files, ≤64 KiB each). No full repo copy, no Git,
no subprocess, no `TestingAgent`. `test_proposal` / `run_suite` remain
`NotImplementedError` (as of B-021; both implemented later). Schema stays **6**. Coding still has no write path
and does not receive the workspace. Allowlist remains
`{read_text, read_project}`.

**B-022 implementation:** `ProcessRunner` (`astra/testing/runner.py`) is
controller-owned and fail-closed. `SecurityAgent.check_process_execution`
authorizes a `ProcessSpec` (argv list, scratch cwd, explicit env, timeout).
Production executable allowlist is empty, so the check never ALLOW by
default. `run()` consults Security and does **not** spawn (`spawned=False`)
as of B-022 (B-024 later spawns `sys.executable` in a copied session).
`astra.testing` still has no `subprocess` import as of B-022. Overlay semantics
unchanged. No TestingAgent, pytest, `TestReport`, third tool, or schema bump.
`nvidia-smi` stays in presentation. Allowlist remains
`{read_text, read_project}`. Architectural gate, not an OS sandbox.

**B-023 implementation:** `CopiedSubsetWorkspace` (`astra/testing/subset.py`)
copies an explicit allowlist (`astra/`, `tests/`, `ui/`, `CONSTITUTION.md`,
`pyproject.toml`, optional embedding JSON) into `scratch/<session>/` after
`check_temporary_write` ALLOW. Overlay onto that session uses B-021 caps
(≤3 files, ≤64 KiB). Live `project_root` bytes are unchanged. Exclusions
(`.git`, `data/`, `.env`, GGUF, secrets) are never copied. Not
`copytree`. Not an OS sandbox. `ProcessRunner` still does not spawn
as of B-023 (superseded by B-024). `TestingAgent` still unimplemented
as of B-023 (superseded by B-025). Schema stays **6**. Allowlist remains
`{read_text, read_project}`.

**B-024 implementation:** `ProcessRunner.run(spec, session_id=...)` is the
sole testing spawn boundary (B-033 later added `GitRunner` as the Git spawn).
After `check_process_execution` ALLOW, it
may spawn **only** `sys.executable` with `shell=False` inside a known
`CopiedSubsetWorkspace` session. Explicit child env (`PYTHONPATH` = session
only; no `ASTRA_HOME`). Timeout in `(0, 60]`; default probe 5s. Stdout/stderr
capped at 64 KiB. `nvidia-smi` stays in presentation. No TestingAgent,
pytest, `TestReport`, or schema bump. Copy is not an OS sandbox; process
policy does not jail the child. Allowlist remains `{read_text, read_project}`.

**B-025 implementation:** `LocalTestingAgent.test_proposal` copies an
allowlisted subset, overlays the proposal, runs
`python -m unittest discover -s tests -t .` via `ProcessRunner` in that
session, parses unittest stdout/stderr into a `TestReport`, and discards
the session in `finally`. Exit code is recorded; B-025 did not use it as
pass/fail (B-035 later treats non-zero exit as not a pass).
Empty, truncated, timed-out, spawn-failed, or unparseable output is not a
pass. One attempt; no retry. `run_suite` stays `NotImplementedError`
(as of B-025; implemented in B-030).
Not a Tool. Not given to Coding. Not an OS sandbox. Schema stays **6**.
Allowlist remains `{read_text, read_project}`.

**B-026 implementation:** `LocalEvaluationAgent.evaluate_proposal` consumes a
`ChangeProposal` and an existing `TestReport`. Fail closed on missing/empty
proposal, missing/incomplete/timeout/spawn-failed/malformed tests, and
failures/errors. Skips follow `TestReport.passed`. `evaluation_passed` is
not `improved` (no fake measurements) and not deployment authorization.
`deployment_authorized` stays False. No `ApprovalRecord`. Two-argument
`evaluate_proposal` remains the B-026 surface. Not a Tool. Schema stays **6**.
Allowlist remains `{read_text, read_project}`.

**B-027 implementation:** `LocalDeploymentAgent.deploy` is the first
permanent-write stage. It requires `ChangeProposal` + passing `TestReport` +
passing `EvaluationReport` + a human-supplied `ApprovalRecord` whose
`subject` equals `evidence_fingerprint(...)`. `check_permanent_change` is
consulted before any write and still never ALLOW; `REQUIRES_APPROVAL` plus
the matching record may proceed. The only write call is
`astra.deployment.apply.commit_files`. Paths stay inside `project_root`;
`.git` / `data` / `logs` / secrets are refused. After a successful write,
`LocalVersionManager` records an immutable pre-image under
`{scratch}/versions/` via `check_temporary_write` (not a new permanent-write
root). `rollback` requires its own `ApprovalRecord` bound to that version
id. Mid-write failure is `deployment_error` / `rollback_error` with no
atomicity claim. Schema stays **6**. Allowlist remains
`{read_text, read_project}`.

**B-028 implementation:** `LocalVersionManager` snapshots only files changed
by that approved deployment. Version ids are
`d-{evidence_fingerprint[:16]}-{n:04d}`. New files record absence. Rollback
restores original bytes and deletes files that did not exist before that
deploy. Unknown / corrupted / traversal / symlink / DENY / unexpected ALLOW
fail closed with zero writes. Not Git. Not an Agent. Not injected into
Coding.

**B-029 implementation:** `LocalBaselineStore` records immutable metric
samples under `{scratch}/baselines/` via `check_temporary_write`. Distinct
from testing overlays and deployment version snapshots. `evaluate` /
`record_baseline` compare supplied samples with exact higher/lower/equal
rules. No fabricated benchmarks. Passing tests do not set `improved`.
`deployment_authorized` stays False. No `ApprovalRecord`. Schema stays **6**.

**B-030 implementation:** `LocalTestingAgent.run_suite` copies an allowlisted
subset with **no overlay**, runs
`python -m unittest discover -s tests -t .` via `ProcessRunner` in that
session, parses unittest stdout/stderr into a `TestReport`, and discards
the session in `finally`. Same runner, parser, and discard rule as
`test_proposal`. Live `project_root` is never the test cwd. Unsupported
paths fail closed. One attempt; no retry. Not a Tool. Does not invoke
Coding, Evaluation, Deployment, or Approval. Not an OS sandbox. Schema
stays **6**. Allowlist remains `{read_text, read_project}`.

**B-031 implementation:** `AstraController.process_proposal` composes existing
`test_proposal` → `evaluate_proposal` → optional `deploy`. Failed stages skip
later agents. Missing or mismatched `ApprovalRecord` writes nothing.
`EvaluationReport.deployment_authorized` is ignored. `PipelineResult` is
additive and off-schema. `rollback_change` delegates to DeploymentAgent and
stays a separate human gate. Direct agent APIs remain valid. Schema stays
**6**. Allowlist remains `{read_text, read_project}`.

**B-032 implementation:** Adversarial tests prove the B-001–B-031 pipeline
stays fail-closed. Traversal, forbidden destinations, forged approval,
process-policy attacks, dishonest test/eval evidence, and unauthorized
rollback leave live `project_root` bytes unchanged unless a matching human
`ApprovalRecord` authorizes the intended deploy. Policy-gated, not an OS
sandbox. Schema stays **6**. Allowlist remains `{read_text, read_project}`.

**B-033 implementation:** `GitVersionStore` records Git history after a
successful permanent deploy. `GitRunner` (`astra.deployment.gitrun`) is the
only Git subprocess boundary. Commands are fixed argv arrays; Git is not a
Tool and is not available to Coding, Testing, or Evaluation. Production does
not search PATH. Global Git config and credentials are untouched. Git failure
after a successful write is `deployed_history_failed` with no fake commit id
and no automatic rollback. Git restore requires its own
`git_restore_fingerprint` `ApprovalRecord`. VersionManager rollback remains.
Schema stays **6**. Allowlist remains `{read_text, read_project}`.

**B-034 implementation:** `LocalPluginRegistry` is controller-owned
registration infrastructure. `PluginDescriptor` is static metadata, not a
grant. Discovery reads `plugin.toml` one level under an explicit
`plugin_root` and never imports plugin Python. Disabled plugins do not
execute. External execution stays disabled. No third Tool. Schema stays
**6**. Allowlist remains `{read_text, read_project}`.

**B-035 implementation:** Security/hardening audit of the live pipeline.
Case-fold copy denials, secret-shaped overlay refusal, absolute/UNC/drive
proposal rejection, write-time symlink/project re-check, non-zero
`exit_code` is not passing evidence, gated `tests/__init__.py` writes, and
a minimal-env presentation `nvidia-smi` probe. Policy-gated, not an OS
sandbox. Residual risks (host-visible child, no Job Object, grandchild
leakage, non-atomic rollback, irreducible TOCTOU) stay documented. Schema
stays **6**. Allowlist remains `{read_text, read_project}`.

**B-036 implementation:** Controller-owned integration suite treats the
pipeline as one state machine. Real `AstraController.process_proposal`
covers success, test/eval/approval failure, deploy, rollback, Git
available/unavailable/failure, repeated versions, exception mapping, and
scratch cleanup. VersionManager is recovery; Git is history. Rollback is
not atomic. Policy-gated, not an OS sandbox. Schema stays **6**. Allowlist
remains `{read_text, read_project}`.

**B-037 implementation:** Final architecture and documentation consistency
pass. ADR-0036 is the state-of-architecture record; it does not supersede
ADR-0014–0035. Current-state docs (`architecture.md`, `README.md`,
`session-handoff.md`, `backlog.md`) now describe the live pipeline.
Milestone-log paragraphs above stay as historical context and are marked
where later work superseded them. No new capability. Schema stays **6**.
Allowlist remains `{read_text, read_project}`.

**B-038 implementation:** Final production-readiness and closure audit.
Live pipeline, security, and authority boundaries match ADR-0036. One
evidence-integrity defect was closed: `TestReport.passed` is false when
`tests_run <= 0`, even if crafted `outcomes` look green; deploy and
evaluate reject that evidence and leave live project bytes unchanged.
No new capability. No ADR-0037 (contract already required a non-empty
run). Schema stays **6**. Allowlist remains `{read_text, read_project}`.
Architecture closed at this stage.

**B-039 implementation:** Production conversation orchestrator. User turns
enter `ConversationOrchestrator` (ADR-0038), not `LlamaCppLLMBackend.generate`
from the UI. Memory retrieve/persist uses the Memory Engine. Research,
planning, tools, and goals are selected per intent. Research is not the
default chat backend. Testing/evaluation/deployment stay off the talk path.
Schema stays **6**. Allowlist remains `{read_text, read_project}`.

**B-040 implementation:** Runtime capability completion (ADR-0039). Frozen
`Primus.exe` rebuilt from this source. `--probe` verifies the orchestrator
path without a GUI. Local `DirectoryCorpusSource` plus knowledge ingest.
Planning seeds backlog tasks then calls `plan()`. No web research in that
milestone. Schema stays **6**. Allowlist remains `{read_text, read_project}`.

**B-041 implementation:** Controlled external research (ADR-0040). `HttpResearchSource`
retrieves HTTP/HTTPS documents behind `LocalResearchAgent`. Security is
deny-by-default (`research.allow_external=False` unless packaged config
enables it). SSRF checks on URL, redirect, and resolved IP. HTML is converted
to text; page content is untrusted data. Research does not auto-write Memory
or Knowledge. Not a browser. Not a Tool. Schema stays **6**. Allowlist remains
`{read_text, read_project}`.

**Runtime intelligence repair (ADR-0041):** The orchestrator builds a
non-exclusive execution plan per turn. Personality comes from
`PersonalitySettings`, not the GGUF. Retrieved memory, identity, goals,
knowledge, research evidence, and tool output are placed inside the ChatML
system message. Planning continue/where-am-I reuses existing backlog rows.
Schema stays **6**. Allowlist remains `{read_text, read_project}`. Not B-042.

**B-006 implementation:** `SQLitePlanningStore` stores tasks in `data/planning.db` —
a separate SQLite file from Memory and Knowledge (ADR-0009 D1). `SimplePlanningEngine`
wraps the store and provides topological-order `plan()`, `next_actionable()`, and
`unresolved_dependencies()` without invoking any model (ADR-0009 D8). Terminal state
protection (DONE/DROPPED → no further transitions) is enforced at the DB level via a
`BEFORE UPDATE` trigger, mirroring the memory/knowledge pattern. Dependency cycles are
prevented at `add()`/`update_task()` time via DFS. The `GoalManager` interface is
preserved unchanged (ADR-0009 D12). Concrete persistence landed in B-014.

**B-004 implementation:** `SQLiteKnowledgeStore` stores accepted knowledge in
`data/astra.db` (ADR-0008 D1). `KeywordKnowledgeClassifier` assigns one of the
13 constitutional categories deterministically, with documented confidence
semantics (ADR-0008 D2). Both constitutional invariants (no duplicate verified
knowledge; unverified cannot overwrite verified) are enforced at the database
level via triggers and unique index, mirroring the Memory Engine's approach
(ADR-0003 D3/D4 applied to Knowledge). `KnowledgeVerification` is defined in
`astra.knowledge` — not shared with `astra.memory` — to keep the two systems
independent (ADR-0008 D4).

**B-009 implementation:** `LocalResearchAgent` (`astra/research/agent.py`)
queries a list of `ResearchSource` objects and returns evidence. Four local
sources exist: `TextSource` (inline text), `DocumentSource` (local file),
`KnowledgeSource` (`KnowledgeStore.search`), `MemorySource` (`MemoryStore.search`).
`Finding.retrieved_at` records when evidence was collected. `run()` never
raises and never writes to Memory or Knowledge. LLM synthesis is optional;
without a loaded model the agent concatenates the top findings. The controller
registers this agent in `start()` (ADR-0011 D8).

### Role boundaries (must not be conflated)

| Role | Package | Produces |
|---|---|---|
| Research | `astra.research` | Evidence (`Finding`, `ResearchReport`). Not accepted knowledge. |
| Knowledge | `astra.knowledge` | Accepted knowledge after classification. |
| Memory | `astra.memory` | Personal / contextual memory records. |
| Planning | `astra.planning` | Tasks and plans. |
| Reasoning | `astra.reasoning` | LLM and embedding inference. |
| Controller | `astra.core` | Orchestration and wiring only. |
| Coding | `astra.coding` | Reviewable `ChangeProposal`. Never applied files. |
| Deployment | `astra.deployment` | Permanent apply of an approved proposal, and approved rollback of a recorded pre-image. Requires human `ApprovalRecord`. |

Research may *read* knowledge and memory through injected store interfaces.
It must not *write* them. `LocalTestingAgent.test_proposal` is implemented
(B-025). `LocalTestingAgent.run_suite` is implemented (B-030).
`LocalEvaluationAgent.evaluate_proposal` is implemented (B-026).
`evaluate` / `record_baseline` are implemented (B-029). `LocalDeploymentAgent.deploy`
is implemented (B-027). `LocalVersionManager` and `rollback` are implemented
(B-028). `AstraController.process_proposal` (B-031) composes those stages
without creating approval. `ProposalWorkspace` (B-021) is an overlay helper, not a
test runner. The Coding Agent is implemented as proposal-only
(`LocalCodingAgent`, B-019). The Security Agent is implemented as policy only
(`PolicySecurityAgent`, B-015, plus `check_temporary_write` in B-021 and
`check_process_execution` in B-022). The Goal Manager is implemented
(`SQLiteGoalManager`, B-014). Goal `evaluate()` is observational: it does not create
tasks or change goal status.

### Research dependency boundaries

`astra.research` depends on contracts, not concrete engine implementations:

- `astra.core.contracts` — `Agent`, `AgentRequest`, `AgentResult`
- `astra.logging`
- `astra.knowledge.contracts.KnowledgeStore` and `astra.memory.contracts.MemoryStore` — injected; `source.py` imports them only under `TYPE_CHECKING`
- `astra.reasoning.backends.LLMBackend` / `GenerationOptions` — optional; used only when an LLM is injected for `summarise()`
- `astra.security.netpolicy` / Security duck-typing — `http.py` only, for destination checks. `agent.py` stays socket-free.

It does **not** depend on `astra.tools` or `astra.planning`. Networking is not a Tool.
The Core Controller constructs local sources and, when `allow_external` is set,
`HttpResearchSource`.

### Controlled external research (B-041)

Local sources remain. `HttpResearchSource` is registered only when
`research.allow_external` is true. `investigate(..., allow_network=True)` then
queries that source after Security allows the URL and every resolved IP.
`used_network` is true only if a fetch was attempted. Failures are distinct
outcomes, not a fake web search. This is document retrieval, not unrestricted
browsing.

## Offline invariant

No module performs network I/O at import time. This is enforced by
`tests/test_offline.py`, which imports the whole package tree with outbound
sockets blocked. User-initiated research may retrieve approved HTTP/HTTPS
documents at query time (B-041, ADR-0040) after Security checks. Inference
remains in-process via llama.cpp (ADR-0004). There is no loopback
Ollama/HTTP transport.

## Current implementation state

| Package | State |
|---|---|
| `configuration` | Implemented (B-005 Pydantic v2 models + stdlib TOML loader). Public API unchanged. |
| `logging` | Implemented, standard library only |
| `memory` | Implemented (B-001 SQLite + FTS5; B-003 FaissVectorIndex; B-013 MemoryIndexer) |
| `knowledge` | Implemented (B-004 SQLiteKnowledgeStore + KeywordKnowledgeClassifier) |
| `planning` | Implemented (B-006 SQLitePlanningStore + SimplePlanningEngine; B-014 SQLiteGoalManager) |
| `core` | Implemented (B-007 AstraController + ControllerStateProvider). `process_proposal` (B-031) composes test→eval→approved deploy. Adversarial fail-closed proof (B-032). Integration/recovery proof (B-036). Closed at B-038. |
| `research` | Implemented (B-009 `LocalResearchAgent` + local sources; B-041 `HttpResearchSource` SSRF-gated HTTP/HTTPS documents). Not a browser. Not a Tool. |
| `security` | Implemented (B-015 `PolicySecurityAgent` + B-018 `check_file_read` + B-021 `check_temporary_write` + B-022 `check_process_execution`). Policy only; deny-by-default; no execution. Hardened in B-035. Not an OS sandbox. |
| `tools` | Implemented (B-016 gate + B-017 `read_text` + B-018 `read_project`). Production allowlist `{read_text, read_project}`. Not an OS sandbox. |
| `coding` | Implemented (B-019 / B-020 `LocalCodingAgent`). Proposal-only. Last proposal inspectable. Never applies. |
| `testing` | `ProposalWorkspace` overlay (B-021). `CopiedSubsetWorkspace` allowlisted copy (B-023). `ProcessRunner` spawn in session (B-024). `LocalTestingAgent.test_proposal` (B-025) honest unittest `TestReport`. `run_suite` (B-030) same runner on an unchanged copied snapshot. |
| `evaluation` | `LocalEvaluationAgent.evaluate_proposal` (B-026) + immutable baselines / comparative `evaluate` (B-029). No approval, no deploy. |
| `deployment` | `LocalDeploymentAgent.deploy` (B-027) + `LocalVersionManager` / `rollback` (B-028) + `GitVersionStore` (B-033). Permanent apply, pre-image restore, and Git history after ApprovalRecord. Git is not authorization. |
| `reasoning` | Implemented (B-002, llama.cpp backends) |
| `state` | Implemented (ADR-0006, contracts only — no I/O) |
| `presentation` | Implemented (B-012, AstraStateProvider + milestone progress). B-037 current-state consistency. B-038 closure. Conversation-first Primus UI (ADR-0037). Production talk path: `ConversationOrchestrator` (B-039/B-041, ADR-0038/0039/0040/0041). |
| `plugins` | `LocalPluginRegistry` (B-034). Registration and lifecycle. Metadata is not authority. External execution disabled. |
| Everything else | Remaining plugin execution / marketplace: not implemented |

`ui/` (outside `astra/`) contains the dashboard application (ADR-0007,
Dear PyGui). It is not a Python package under `astra/` and is not scanned
by `test_architecture.py`. Its isolation from core modules is enforced by
`test_ui_contracts.py`.

No placeholder behaviour exists anywhere. A module either works or refuses to
run.
