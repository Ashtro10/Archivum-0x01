# Primus runtime gap analysis

Paired with `docs/runtime-reality.md`. Same original audit date (B-038).

**B-039 (source):** the conversation orchestrator now exists.
`boot.launch` injects `ConversationOrchestrator.from_controller`.
Memory retrieve/persist is LIVE on the talk path. Research/planning/tools/goals
are selected, not default. Testing/evaluation/deployment stay disconnected from
chat. Frozen `Primus.exe` is stale until rebuild. Sections A–G below are the
B-038 packaged-path record.

---

## A. What actually works

- One-window launch: `Primus.exe` → `primus_entry.py` → `boot.launch`.
- Controller **starts**: SQLite stores open, agents register, tools exist, security policy object exists.
- Talk reaches the **real** local GGUF via `llama_cpp` (`AssistantConversation` → `generate`).
- Honest failure if model/DLL missing (no invented reply).
- System View **reads** live controller status (`ControllerStateProvider`) without mutating engines.
- Frozen spawn loop **guards** exist: worker silent-exit, `for_ui()` non-spawn, ProcessRunner frozen refuse, mutex.
- Schema 6 and tool allowlist `{read_text, read_project}` unchanged.
- Change pipeline (test → evaluate → approval → deploy) exists as controller methods; not used by chat.

---

## B. What exists but is disconnected

| Piece | Built at `start()` | Missing talk connection |
|-------|--------------------|-------------------------|
| Memory SQLite + indexer | yes | no retrieve on `ask`; no `store` after reply |
| Knowledge SQLite | yes | only via unused research sources |
| Planning engine + backlog | yes | no `next_actionable` / create from `ask` |
| Goal manager | yes | System View list only |
| Research agent | registered | no `dispatch("research")` from UI |
| Coding agent | registered | no dispatch from UI |
| Tools + gate | yes | no `request_tool` from `ask` |
| Security agent | yes | not consulted for chat text |
| FAISS | load/save | no query; rebuild has no UI |
| `AstraController.dispatch` | yes | **zero** production UI callers |

---

## C. What is test-only

`ProcessRunner`, `LocalTestingAgent`, `LocalEvaluationAgent`, `LocalDeploymentAgent`, `VersionManager`, `GitRunner` / `GitVersionStore`, `process_proposal`, plugin enable/execute.

Unit tests call `dispatch` and `request_tool`. The packaged talk loop does not.

`ResearchConversation` is neither test nor live: dead class.

---

## D. What is missing

- Conversation orchestrator (intent → retrieve → maybe research/plan/tools → write memory).
- Durable transcript / identity load.
- Policy for when research is appropriate.
- UI return chrome from System/Settings to Talk (trap).
- Real MIC capture and STOP cancellation of `generate`.

These are not “unwired classes.” They have no production type to call.

---

## E. What the executable actually does

Opens a black/cyan chat window. On Send, formats ChatML, runs llama.cpp in the same process, shows the completion. Keeps up to 8 turns in RAM. On exit, drops the transcript. Background: databases sit open unused by chat. System View is a status dashboard.

---

## F. Why it behaves like a basic chatbot

`boot.launch` sets:

```python
conversation = AssistantConversation(controller.llm)
```

That class’s only side effect is `llm.generate`. It was introduced to stop routing chat through `LocalResearchAgent.summarise` (empty sources → “no findings to summarise”). The replacement **bypassed the whole agent/engine graph**, not only the broken summarise path.

UI contracts **forbid** `ui/` from calling `.dispatch(`. There is no other allowed module that orchestrates a user turn. Result: chatbot.

---

## G. Exact wiring failures

1. **Talk port is the LLM, not the controller.** `AssistantConversation.__init__(llm)` never receives `controller`.
2. **No user-turn method on `AstraController`.** `dispatch` requires a pre-chosen agent name. Nothing chooses one.
3. **Memory write API unused** (`MemoryIndexer.store` / `SQLiteMemoryStore.store`) from presentation.
4. **Memory read API unused** (`MemoryIndexer.search`) from presentation.
5. **Research is agent-shaped, not conversation-shaped.** Empty-source summarise is correct for that agent and wrong as a chat backend.
6. **Goals/planning have no conversation adapter.**
7. **System View status ≠ invocation.** OPERATIONAL cards do not run those subsystems.
8. **Nav chrome is inside the view it hides.** System/Settings trap.

---

## H. Minimum required integration work

Do **not** start this until explicitly scheduled. Not B-039. Not a UI redesign.

1. Add a **conversation orchestrator** in `astra.presentation` (or controller method) that UI already may import. It owns one user turn. It is not Testing/Deploy.
2. On `ask`: retrieve memory (indexer/search) → build prompt context from **retrieved records**, not invented facts.
3. Intent policy (explicit, testable): chat vs research vs refuse. Research only when policy says so; never as the default for “Hello”.
4. After a successful turn: write a `MemoryRecord` through the indexer when the turn contains a durable fact (policy, not “always store the prompt”).
5. Keep ProcessRunner/Git/testing **off** this loop.
6. Move TALK/SYSTEM/SETTINGS **outside** `_primary_root` so System View cannot trap.
7. Regression: production path test (see `tests/test_runtime_path.py`) must fail if talk starts calling `dispatch("testing")` or stops calling `generate`.

Until (1) exists, connecting research again as the default talk port will restore “no findings to summarise.”
