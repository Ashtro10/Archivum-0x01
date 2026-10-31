# Primus

A local-first personal AI operating system for a single user, built to run for
years on user-controlled hardware.

The internal Python package remains ``astra``. Historical ADRs and the
constitution still use the former project name where they record history.

Primus runs entirely offline. There are no paid APIs, no hosted inference, no
subscriptions, and no mandatory internet connection. See
[`CONSTITUTION.md`](CONSTITUTION.md) for the rules that govern every decision in
this repository, and [`docs/adr/`](docs/adr/) for the reasoning behind the stack.
The live UI is documented in [`docs/ui.md`](docs/ui.md).

## Current status

| Area | State |
|---|---|
| Conversation path | `ConversationOrchestrator` (B-039/B-041, ADR-0038/0039/0040/0041). Composite turn plan; personality outside the model. Frozen `Primus.exe` must be rebuilt to ship ADR-0041. |
| Configuration manager | Implemented (B-005, Pydantic v2). Public API unchanged. |
| Logging system | Implemented (standard library only) |
| Memory Engine | Implemented (B-001 SQLite + FTS5; B-003 FAISS; B-013 MemoryIndexer) |
| Knowledge Engine | Implemented (B-004). Talk-path ingest via `ingest_text` (B-040). |
| Planning Engine | Implemented (B-006). Goal Manager implemented (B-014) |
| Core Controller | Implemented (B-007 / B-031 / B-036). `process_proposal` composes test → evaluate → human approval → deploy. Does not auto-approve. End-to-end integration and recovery proven. |
| Research Agent | Implemented (B-009 / B-040 / B-041). Local sources plus **controlled HTTP/HTTPS document retrieval** when `research.allow_external` is enabled. SSRF-gated. Not a browser. Not a Tool. |
| Security Agent | Implemented (B-015 / B-032 / B-035). Policy only; deny-by-default. Adversarial tests prove fail-closed gates. Hardening audit closed remaining policy gaps. Does not execute. Not an OS sandbox. `check_temporary_write` (B-021). `check_process_execution` (B-022). |
| Tool Interface | Implemented (B-016 gate + B-017 `read_text` + B-018 `read_project`). |
| Coding Agent | Implemented (B-019 / B-020). Proposal-only. Last proposal inspectable. Never applies. |
| Reasoning | Implemented (B-002, llama.cpp) |
| State + Presentation | Implemented (B-011, B-012) |
| Proposal workspace | Implemented (B-021). Overlay under scratch. |
| Process policy | Implemented (B-022 / B-024). Testing spawn: `sys.executable` in a copied-subset session via `ProcessRunner`. Git spawn is a separate controller-owned boundary (B-033). |
| Copied-subset workspace | Implemented (B-023). Allowlisted copy under scratch; overlay onto copy. |
| Testing Agent | Implemented (B-025 / B-030). `test_proposal` overlays then tests. `run_suite` tests an unchanged copied snapshot via `ProcessRunner`. Honest `TestReport`. |
| Evaluation Agent | Implemented (B-026 / B-029). `evaluate_proposal` judges `ChangeProposal` + `TestReport`. `record_baseline` / `evaluate` compare immutable samples. Does not approve or deploy. |
| Deployment Agent | Implemented (B-027 / B-028 / B-033). `deploy` applies approved files after explicit `ApprovalRecord`. `rollback` restores the pre-image after a matching `ApprovalRecord`. `GitVersionStore` records Git history after a successful deploy; Git restore needs its own approval. Git is not authorization. |
| Plugins | Implemented (B-034). `LocalPluginRegistry` registers descriptors. Metadata is not authority. External plugin execution disabled. Hardened in B-035. |
| Presentation / UI | Conversation-first Primus window (ADR-0037). Talk via `ConversationOrchestrator` (B-039). Primus Core + conversation primary. System View secondary. Settings discreet. Approval/deploy remain render-only. |

Nothing is faked. If a module appears empty of behaviour, it is because the
behaviour has not been built yet. Research returns evidence from local text,
local files, knowledge, memory, and — when enabled — approved HTTP/HTTPS
documents. It does not browse arbitrarily and does not invent citations.
Coding proposes complete file replacements through gated `read_project` reads; it never applies
them. Deployment is the only stage that writes project files, and only with
an explicit human `ApprovalRecord`.

## Fresh start (Primus UI + packaging)

- **Backend:** B-001–B-038 complete. Schema 6. Allowlist `{read_text, read_project}`.
- **Conversation:** B-039 orchestrator (ADR-0041 repair). Memory/knowledge/research/planning/tools/goals may run together when justified. Context is inside the ChatML system turn.
- **Product name:** Primus. Internal package: `astra`.
- **UI:** Conversation-first. Primus Core is the visual identity.
- **Launch (dev):** `python -m astra` or `python -m astra --demo`
- **Launch (Windows dist):** `Primus.exe` from `python scripts/build_windows.py`
- **Persistent data:** source `data/astra.db`; packaged `dist_windows/data/astra.db`. See `docs/memory.md`.
- **Smoke:** `python -m astra --smoke`

## Fresh start (B-041)

- **Current milestone:** B-041 (controlled external research). **Complete.**
- **Complete through:** B-041. Backend closed at B-038; orchestrator at B-039; useful local capabilities at B-040; controlled HTTP/HTTPS retrieval at B-041.
- **Schema:** 6. No schema 7.
- **Tool allowlist:** exactly `{read_text, read_project}`. No third Tool. No web Tool.
- **Assistant pipeline:** user → orchestrator → selected engines → llama.cpp.
- **Research:** local documents/knowledge/memory, plus SSRF-gated HTTP/HTTPS document retrieval when `research.allow_external` is true. Not a browser. Not unrestricted internet.
- **Memory/Knowledge:** research evidence is temporary. No silent durable ingest.
- **Improvement pipeline:** unchanged. Conversation does not dispatch testing/evaluation/deployment.
- **Next:** do not start B-042. Runtime intelligence repair is ADR-0041 on the existing talk path, not a new milestone.
- **Tests after runtime intelligence repair:** 1353 collected, 0 failures, 9 skipped.
- **Schema:** 6. No schema 7.
- **Tool allowlist:** exactly `{read_text, read_project}`. No third Tool.
- **Assistant pipeline:** user → orchestrator → selected engines → llama.cpp.
- **Improvement pipeline:** ChangeProposal → copied-subset workspace → real unittest →
  TestReport → EvaluationReport → human ApprovalRecord → deploy →
  VersionManager snapshot → optional Git history → approval-bound rollback.
  Conversation does not invoke this pipeline.
- **Security:** policy, not an OS sandbox. Coding is write-free. Evaluation
  cannot spawn or authorize deploy. Plugins do not execute untrusted code.
  UI is render-only for approval/deploy. No auto-approval. Empty/`tests_run<=0` evidence is not
  a pass, even with crafted outcomes. Research networking is capability-gated and SSRF-checked.
- **Residual limits:** child processes may use OS-permitted host resources;
  no child network jail; grandchildren may survive; rollback is not atomic;
  Git failure after write does not auto-rollback; persist-after-write can
  leave bytes in place. Intent routing is keyword-based, not NLU. External
  research is document retrieval (HTML/text), not a full browser. DuckDuckGo
  HTML search is used only when the user does not supply an explicit URL.
  Frozen `Primus.exe` must be rebuilt to ship B-041.

## Layout

```
astra/
├── core/            Core Controller, shared agent contract, lifecycle stages
├── memory/          Memory Engine contracts, vector index contract
├── knowledge/       Knowledge Engine contracts, classification
├── planning/        Planning Engine, Goal Manager (planning/goals.py)
├── reasoning/       LLM and embedding backend interfaces (swappable)
├── research/        Research Agent (B-009 local sources; B-041 controlled HTTP/HTTPS)
├── coding/          Coding Agent (B-019, proposal-only)
├── testing/         ProposalWorkspace (B-021); CopiedSubsetWorkspace (B-023); ProcessRunner spawn (B-024); LocalTestingAgent.test_proposal (B-025); run_suite (B-030)
├── evaluation/      LocalEvaluationAgent (B-026 / B-029); baseline store under scratch
├── deployment/      LocalDeploymentAgent.deploy (B-027); LocalVersionManager + rollback (B-028); GitVersionStore (B-033)
├── security/        Security Agent contract
├── plugins/         LocalPluginRegistry (B-034); metadata is not authority
├── tools/           Tool Interface + read_text + read_project (B-016–B-018)
├── configuration/   Configuration Manager (implemented)
└── logging/         Logging System (implemented)

tests/               Mirrors astra/, plus offline enforcement
docs/                Architecture, backlog, lifecycle, ADRs
```

The fifteen packages above map one-to-one onto the System Architecture section of
the constitution. Goal Manager and Version Manager are nested inside `planning/`
and `deployment/` respectively rather than given their own top-level packages;
this is recorded in [`docs/architecture.md`](docs/architecture.md).

## Requirements

- Python 3.11 or newer (uses `tomllib` from the standard library).

No third-party packages are required to run the scaffold or its tests. The
dependencies declared in `pyproject.toml` are the ones chosen in ADR-0001 for
future implementation work; install them only when the module that needs them is
built.

## Running the tests

```
python -m unittest discover -s tests -v
```

`pytest` also works if installed, but is not required.

The suite includes `tests/test_offline.py`, which imports every Astra module with
outbound sockets disabled. Any module that opens a network connection at import
time will fail the build.

## Windows distribution

```
python scripts/build_windows.py
```

Output: `dist_windows/Primus.exe` plus `_internal/`. Does not bundle GGUF
models, `.env`, or user databases. Put a `.gguf` in `models/` or set
`[reasoning].model_path` in `astra.toml`. Persistent data stays in `data/`
or `ASTRA_HOME` / `PRIMUS_HOME`. Development remains `python -m astra`.

## Configuration

Astra reads an optional TOML file. Resolution order:

1. Path passed to `load_settings()`.
2. `ASTRA_CONFIG` environment variable.
3. `astra.toml` in the project root.
4. Built-in defaults.

Unknown keys are rejected rather than ignored, so a typo fails loudly instead of
silently changing behaviour.

```python
from astra.configuration import load_settings
from astra.logging import configure_logging, get_logger

settings = load_settings()
configure_logging(settings.logging)
get_logger(__name__).info("astra configured")
```

## Contributing rules

Read `CONSTITUTION.md` first. In short: extend modules rather than rewriting
them, keep every external component behind an interface, document architectural
decisions as ADRs, and never let a change land without a measurable improvement
to point at.
