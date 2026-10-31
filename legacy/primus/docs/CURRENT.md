# Primus today

This is the **current** map. Historical milestone ADRs live in `docs/adr/`.
You do not need those to change Primus.

## Layout

```text
Astra/                          # repository root (product name: Primus)
├── astra/                      # Primus implementation (package still named astra)
├── ui/                         # conversation window
├── scripts/                    # build and launch helpers
├── tests/                      # automated tests
├── docs/                       # current + historical docs
├── data/                       # source-runtime persistent data (not in git)
├── dist_windows/               # packaged Windows app
│   ├── Primus.exe
│   ├── _internal/              # required next to the exe
│   ├── astra.toml              # packaged settings
│   └── data/                   # exe persistent data
├── build/                      # disposable PyInstaller work
├── README.md
└── pyproject.toml
```

There is no second `config/` tree. Settings are `astra.toml` in the project
root (source) or next to `Primus.exe` (packaged).

## Subsystems (beginner)

```text
Primus
├── Conversation   → astra/presentation/orchestration.py, ui/
├── Memory         → astra/memory/, orchestration persist/retrieve
├── Core visual    → jarvis-main/ (background), ui/web/ (chat shell)
├── Research       → astra/research/
├── Planning       → astra/planning/
├── Goals          → astra/planning/goals.py
├── Tools          → astra/tools/ (read-only allowlist)
├── UI             → ui/
├── Security       → astra/security/
└── Configuration  → astra.toml, astra/configuration/
```

Want to change what gets remembered? → `_durable_facts()` in
`astra/presentation/orchestration.py`.

User-memory boundary (sources, field keys, probe filter) →
`astra/memory/user_facts.py`.

Want to change how memories are selected? → `_retrieve_memory_scored()` in the
same file.

Want to change storage? → `astra/memory/sqlite_store.py`.

Want to change the UI? → `ui/`.

Want to change research? → `astra/research/`.

## Where to change things

| I want to change… | Go here |
|---|---|
| UI | `ui/` |
| What Primus remembers | `astra/memory/` plus persist/retrieve in `astra/presentation/orchestration.py` |
| Personality | `[personality]` in `astra.toml`; model `PersonalitySettings` |
| Research | `astra/research/` |
| Planning | `astra/planning/` |
| Goals | `astra/planning/goals.py` and `astra/planning/goal_manager.py` |
| Conversation behavior | `astra/presentation/orchestration.py` |
| Local LLM | `[reasoning]` in `astra.toml`; `astra/reasoning/` |
| Security | `astra/security/` |
| Tests | `tests/` |
| Build `Primus.exe` | `python scripts/build_windows.py` |

## Two runtimes, two data directories

| How you start | Persistent memory file |
|---|---|
| `python -m astra` | `<repo>/data/astra.db` |
| `dist_windows/Primus.exe` | `<exe-dir>/data/astra.db` |

Override the data directory with `ASTRA_HOME` or `PRIMUS_HOME`.
Do not point that at `_internal` or `build/`.

## Run / build / test

```text
python -m astra
python scripts/build_windows.py
python -m unittest discover -s tests -t . -q
```

Memory details: [`memory.md`](memory.md).
