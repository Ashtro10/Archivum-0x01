# Primus runtime reality

Audit date: 2026-08-15 (B-038 packaged path).

**B-040 (2026-08-15):** source and rebuilt `Primus.exe` use `ConversationOrchestrator`.
`--probe` is the frozen verification. Research is local corpus only. The B-038
audit body below remains the pre-orchestrator packaged-path record.

Classification uses **source call paths**, not ADRs, not unit-test presence, not System View labels.

Executable: `dist_windows/Primus.exe`  
Build entry: `scripts/build_windows.py` → PyInstaller → `scripts/primus_entry.py`  
Dev entry: `python -m astra` → `astra/__main__.py` → `astra.boot.launch`

---

## 1. Actual executable call graph

```
Primus.exe
  → scripts/primus_entry.py
      worker argv (-m / -c / --multiprocessing-*) → SystemExit(0)  [no GUI]
      else multiprocessing.freeze_support()
      → astra.boot.launch(argv)
          prepare_process()  [mutex; activate existing window]
          _start_controller()
              prepare_llama_native_libs()
              load_settings()
              AstraController(settings).start()
                  opens SQLite memory / knowledge / planning
                  constructs LLM + embedding backends (does not load GGUF)
                  loads FAISS if present (does not embed)
                  constructs indexer, goals, security, workspaces,
                  ProcessRunner, tools, agents, plugin registry
          conversation = AssistantConversation(controller.llm)
          provider = ControllerStateProvider(controller)
          ui.app.main(..., conversation=conversation)
              PrimusApp._send
                → ConversationPort.ask
                  → AssistantConversation.ask
                    → LlamaCppLLMBackend.generate
                      → llama_cpp.Llama.create_completion
              reply text appended to in-memory ConversationStore
          finally controller.shutdown()  [saves FAISS if loaded; closes DBs]
```

Demo (`--demo`): no controller. `UnavailableConversation`. No LLM.

---

## 2. Answers to the sixteen runtime questions

| # | Question | Runtime fact |
|---|----------|----------------|
| 1 | What receives the user message? | Dear PyGui composer `_composer`. `PrimusApp._send` in `ui/app.py`. |
| 2 | What function processes it? | `AssistantConversation.ask` in `astra/presentation/conversation.py`. |
| 3 | Where is the LLM called? | `LlamaCppLLMBackend.generate` → `create_completion`. First call loads the GGUF. |
| 4 | Is the LLM called directly from the UI? | UI calls `conversation.ask` only. Boot injected `AssistantConversation(controller.llm)`. No `dispatch`. No memory/planning/research in that object. **Direct model call.** |
| 5 | Is AstraController involved? | **Start/shutdown and System View state only.** Talk does not call `dispatch`, `request_tool`, `rebuild_memory_index`, or `process_proposal`. |
| 6 | Is planning invoked? | **No** on talk. `SimplePlanningEngine` is constructed at `start()`. `next_actionable()` has no production caller outside tests / System View listing. |
| 7 | Is memory queried? | **No** on talk. Prompt is ChatML + last 8 in-RAM turns. `MemoryIndexer.search` is not called. |
| 8 | Is memory written after interaction? | **No.** `SQLiteMemoryStore.store` / `MemoryIndexer.store` have no production talk caller. |
| 9 | Is knowledge queried? | **No** on talk. `KnowledgeSource` exists only inside `LocalResearchAgent`, which talk never runs. |
| 10 | Can the system initiate research? | **Not from conversation.** `LocalResearchAgent` is registered. Only `AstraController.dispatch("research", …)` runs it. Production UI never calls `dispatch`. |
| 11 | Can the system invoke tools? | **Not from conversation.** `request_tool` exists. Callers are tests. Allowlist `{read_text, read_project}`. |
| 12 | Can agents be selected/invoked? | **Not from conversation.** Registry is live after `start()`. No intent router. No agent picker in UI. |
| 13 | Can the system create or pursue goals? | **Not from conversation.** `SQLiteGoalManager` initialises. `list_goals()` is used by `ControllerStateProvider` for System View DTOs only. No create/pursue from talk. |
| 14 | Is conversation context persisted? | **No.** `ConversationStore` is a Python list. `AssistantConversation._turns` is RAM, max 8, discarded on process exit. |
| 15 | Is user identity/context loaded at startup? | **No.** Greeting is `PRODUCT` constant. Memory DB may open and stay unread by talk. No identity record load. |
| 16 | Complete architecture or direct local-model call? | **Direct local-model call.** Controller is a process host, not a conversation orchestrator. |

---

## 3. Subsystem classification

Exactly one label per row. Labels: LIVE / PARTIALLY WIRED / DEAD CODE / TEST ONLY / PRESENTATION ONLY / NOT IMPLEMENTED.

| Subsystem | Class | Evidence |
|-----------|-------|----------|
| PyInstaller entry / freeze_support / worker silent-exit | LIVE | `scripts/primus_entry.py` |
| `astra.boot.launch` | LIVE | only GUI entry after freeze |
| `AstraController.start` / `shutdown` | LIVE | boot starts it; DBs open; GGUF not loaded until generate |
| `LlamaCppLLMBackend.generate` | LIVE | `AssistantConversation.ask` |
| `AssistantConversation` | LIVE | boot wires it as the talk port |
| Dear PyGui talk composer | LIVE | `_send` → `ask` |
| `load_settings` / `astra.toml` / GGUF discover | LIVE | boot `_start_controller` |
| `prepare_llama_native_libs` | LIVE | boot + first generate |
| Single-instance mutex | LIVE | `prepare_process` |
| SQLite memory / knowledge / planning files | PARTIALLY WIRED | opened at start; talk never reads/writes; shutdown may save empty/stale FAISS |
| FAISS vector index | PARTIALLY WIRED | load/save at start/shutdown; no talk query; rebuild is a method with no UI caller |
| `MemoryIndexer` | PARTIALLY WIRED | constructed; `search`/`store` unused by talk |
| `ControllerStateProvider.get_state` | PARTIALLY WIRED | System View only; render-only; `Milestone.for_ui()` |
| `LocalResearchAgent` | PARTIALLY WIRED | registered at start; reachable only via `dispatch("research")`; UI never dispatches |
| `SimplePlanningEngine` | PARTIALLY WIRED | constructed; no talk caller |
| `SQLiteGoalManager` | PARTIALLY WIRED | constructed; listed on System View; no talk create/pursue |
| `PolicySecurityAgent` | PARTIALLY WIRED | constructed; gates tools/process; talk does not consult it |
| Tool catalog + `GatedToolInvoker` | PARTIALLY WIRED | constructed; `request_tool` unused by talk |
| `LocalCodingAgent` | PARTIALLY WIRED | registered; no talk caller |
| Plugin registry | PARTIALLY WIRED | constructed; external execution disabled; talk unused |
| `ResearchConversation` | DEAD CODE | class in `boot.py`; `launch` never constructs it; its `ask` is also a direct LLM shortcut |
| Conversation orchestrator / intent router | NOT IMPLEMENTED | no module; no `dispatch` from talk |
| Durable conversation log | NOT IMPLEMENTED | `ConversationStore` is RAM |
| User profile / identity load | NOT IMPLEMENTED | none |
| Proactive clarifying-question planner | NOT IMPLEMENTED | research `open_questions` never surface in UI |
| Network / web research | NOT IMPLEMENTED | agent refuses network; no network source |
| `ProcessRunner.run` | TEST ONLY | frozen exe refuses spawn; talk never calls it |
| `LocalTestingAgent` | TEST ONLY | proposal/suite path; not conversation |
| `LocalEvaluationAgent` | TEST ONLY | proposal path |
| `LocalDeploymentAgent` / `process_proposal` | TEST ONLY | not conversation; UI cannot approve |
| `GitRunner` / `GitVersionStore` | TEST ONLY | deploy/restore history; not talk |
| `VersionManager` | TEST ONLY | rollback snapshots; not talk |
| System View capability cards | PRESENTATION ONLY | `CapabilityStatus` from probes; buttons do not invoke engines. Goals panel has no `add_button` / no `dispatch` (`tests/test_ui_contracts.py`) |
| Demo `DemoStateProvider` | PRESENTATION ONLY | `--demo` only; hard-coded OPERATIONAL labels |
| MIC / STOP | PRESENTATION ONLY | visual state only; does not cancel `generate` |
| SETTINGS reduced-motion copy | PRESENTATION ONLY | env vars; not a model/memory control |
| `UnavailableConversation` | LIVE (degraded) | demo / controller start failure |

---

## 4. Memory: one conversation, restart, recall

Traced production objects, not a hoped-for pipeline.

1. **Start.** `AstraController.start` → `SQLiteMemoryStore.initialise()` at `data/astra.db` (or `ASTRA_HOME`). FAISS load if `data/vectors.faiss/index.faiss` exists. Talk path does not read those records into the prompt.
2. **User sends a personal fact.** `_send` → `ask` → ChatML prompt from `_turns` (empty on first message) + the user string → `generate`. No `memory.search`. No `indexer.search`.
3. **Primus responds.** Reply stored in `AssistantConversation._turns` and `ConversationStore.messages`. Both process-local.
4. **Close.** `controller.shutdown()` closes SQLite. Vector index `save` if an index object exists. **No new memory row is inserted.** Transcript is discarded.
5. **Restart.** New `AssistantConversation` (`_turns = []`). New `ConversationStore` (greeting only). SQLite may still hold older rows from tests or manual API use; talk still does not query them.
6. **User asks about the fact.** Prompt has no prior turn and no retrieved memory. Model answers from weights only.

**Missing connection:** there is no production function that, on `ask`, retrieves memory into `AgentRequest.context` or writes a `MemoryRecord` after a successful reply. `MemoryIndexer.store` exists for that write. Nothing in `ui/` or `AssistantConversation` calls it.

Do not treat the ChatML system line or the 8-turn RAM buffer as memory. That is session echo, not the Memory Engine.

---

## 5. Research

`LocalResearchAgent.run` = `investigate` + `summarise`.

Production reachability:

- Registered in `_register_research_agent` with `KnowledgeSource` + `MemorySource`.
- Invoked only by `AstraController.dispatch("research", AgentRequest)`.
- `dispatch` callers in this repo: **tests only** (`tests/test_research.py`, plus other agent tests). `ui/` forbids `.dispatch(` (`tests/test_ui_contracts.py`).

There is **no** production predicate “this question requires research.” No classifier. No keyword gate. No planner step.

Empty local sources + `summarise(())` returns `summary="no findings to summarise"`. That string is **not** on the current talk path. Historical talk used a research adapter; current `launch` does not.

---

## 6. Proactive behaviour

| Behaviour | Exists in source? | On talk path? |
|-----------|-------------------|---------------|
| Clarifying questions | `ResearchReport.open_questions` | No; research unused; UI ignores the field |
| Identify missing information | same | No |
| Create plans | `SimplePlanningEngine` / backlog `create` | No talk caller |
| Multi-step tasks | planning statuses | No talk loop |
| Pursue goals | `SQLiteGoalManager` | System View lists; talk does not create or advance |
| Decide more work is needed | none | NOT IMPLEMENTED |

No fake autonomy added. These are disconnected or absent.

---

## 7. “Nothing to summarise”

**Current production talk path does not emit this string.**

Exact remaining source:

```
astra/research/agent.py  LocalResearchAgent.summarise
  if not findings:
      return AgentResult(..., summary="no findings to summarise")
```

`run()` always calls `summarise` after `investigate`. Empty knowledge/memory → that summary.

Historical production wiring (replaced in `astra/boot.py`): talk used a research adapter so ordinary chat (`Hello`) became `dispatch("research")` → empty sources → summarise failure. User-facing variants in tests: `"nothing to summarise"`, `"nothing it can stand behind"`. Those phrases are **not** in current `boot.py` / `conversation.py`. `ResearchConversation` remains as dead code and now also calls `AssistantConversation`.

If a running window still shows summarise text: old exe, or the **model** parroting the system line “Do not mention summarization unless asked.”

Routing already points at `AssistantConversation`, not research. This audit does **not** re-wire research into talk (that was the bug). An orchestrator that may *choose* research is NOT IMPLEMENTED.

---

## 8. Command-window / process explosion

### Historical root cause (source-proven)

Frozen `sys.executable` **is** `Primus.exe`.

1. UI `get_state()` evaluated `Milestone.complete()` for spawn-backed checks (`_check_process_spawn`, unittest discover, git).
2. Those checks `Popen([sys.executable, "-m", "unittest", …])`.
3. Child was another Primus GUI (entry imported UI before `freeze_support` in older builds).
4. Children repeated the same `get_state()` → thousands of consoles.

`nvidia-smi` from `astra/presentation/provider.py` is a separate short subprocess (now `CREATE_NO_WINDOW`). Used by `AstraStateProvider`, not by `ControllerStateProvider`. Live boot uses `ControllerStateProvider`.

### Current source mitigations (still required)

| Guard | Where |
|-------|--------|
| Worker argv exits before GUI | `primus_entry.py`, `astra/__main__.py`, `is_non_gui_invocation` |
| `Milestone.for_ui()` never spawns | `progress.py`; both state providers use it |
| Talk view does not poll `get_state` | `ui/app.py` `_refresh_state` returns unless SYSTEM |
| Frozen `ProcessRunner` refuses spawn | `testing/runner.py` `"frozen executable is not a Python interpreter"` |
| `CREATE_NO_WINDOW` on remaining Popen | ProcessRunner, GitRunner, nvidia-smi |
| Single-instance mutex | `boot.prepare_process` |

### Intended process tree (ordinary talk)

```
Primus.exe     # one process
  llama.cpp    # in-process, not a child
```

No child Primus. No cmd.exe for chat. ProcessRunner/GitRunner/unittest are **not** part of conversation execution.

`--smoke` of the current exe has exited 0 with no leftover Primus process (2026-08-15).

---

## 9. Development infrastructure vs assistant

These are **not** on the talk path (no caller from `AssistantConversation` / `ui/app.py`):

- `ProcessRunner`
- `LocalTestingAgent`
- `LocalEvaluationAgent`
- `LocalDeploymentAgent`
- `LocalVersionManager`
- `GitRunner`
- Plugin external execution
- `process_proposal`

They remain constructed at `start()` so System View can report OPERATIONAL/IMPLEMENTED. Construction ≠ conversation integration.

---

## 10. System View trap (presentation bug)

Nav buttons TALK / SYSTEM / SETTINGS live inside `_primary_root` (`ui/app.py`).

`_show_view(SYSTEM)` sets `_primary_root` `show=False`. `_system_root` has no TALK control. `_settings_root` has none. No keybind returns to talk.

**User can enter System View and lose the conversation chrome.** Not an engine bug. Not fixed in this audit (no UI redesign).

---

## 11. Why the window is sluggish

Proven contributors, not guesses about “the OS”:

1. First `generate` loads Qwen 3B GGUF in-process (`n_gpu_layers=99`).
2. Dear PyGui 30 FPS Core draw loop always on.
3. Opening System View walks every engine + every `Milestone.for_ui()`.
4. Frozen `_internal` still contains unused heavy packages (transformers/torch traces observed in the bundle). Those are packaging leakage; talk does not import them, but they inflate disk/startup.

---

## 12. Production vs intended (graphs)

### Actual

```
USER MESSAGE
  → PrimusApp._send
  → AssistantConversation.ask
  → LlamaCppLLMBackend.generate
  → create_completion
  → RAM transcript
  → RESPONSE
```

### Intended (architecture.md + constitution components; not present as a talk loop)

```
USER
  → CONVERSATION PORT
  → CONTEXT / MEMORY retrieve
  → INTENT
  → PLANNING
  → REASONING
  → KNOWLEDGE / RESEARCH / TOOLS / AGENTS  (controller.dispatch / request_tool)
  → RESULT
  → MEMORY UPDATE
  → RESPONSE
```

The second graph is implemented in source as `ConversationOrchestrator`
(B-039 / ADR-0038). Frozen `Primus.exe` is still the B-038 chatbot path until
rebuild. Testing/evaluation/deployment remain off the talk path.
