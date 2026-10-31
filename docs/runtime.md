# Primus runtime

## Identity

User-facing name: **Primus**. Internal Python package remains `astra`.

## Development

```
python -m astra
python -m astra --demo
python -m astra --smoke
python -m astra --probe
```

`--probe` writes orchestrator verification JSON and exits. No window.

## Packaged

Canonical executable:

```
dist_windows/Primus.exe
```

Build:

```
python scripts/build_windows.py
```

Keep `_internal` next to the exe. Do not copy the exe alone.

## Model

Provider: local `llama_cpp` (`LlamaCppLLMBackend`). No cloud. No second chatbot.

Configure one of:

1. `astra.toml` in the project root (or next to `Primus.exe` when frozen):

```toml
[reasoning]
model_path = "C:/path/to/model.gguf"
```

2. Drop a `.gguf` file in `models/` (first file found is used if `model_path` is unset).

3. `ASTRA_CONFIG` pointing at a TOML file.

Models, `.env`, databases, and scratch data are never bundled.

## Conversation path

```
UI composer
  → ConversationOrchestrator.process
  → memory retrieve / optional knowledge, research, planning, tools, goals
  → LLMBackend.generate
  → persist session + justified durable memory
  → UI
```

Research is not the default talk path. "Hello" and "What is recursion?" go to
the model with retrieved memory. Freshness / "look into" / "research X" may
dispatch the Research Agent against **local** sources (docs corpus, knowledge,
memory). There is no web search. Empty local research does not show
`no findings to summarise` and does not claim an internet search.

If the model file or llama.cpp binding is missing, Primus says it could not
reach the language model. It does not invent an answer.

## Process tree

Intended:

```
Primus.exe
```

No child `Primus.exe`. ProcessRunner is testing-only and refuses to spawn
when frozen. UI state refresh does not run live unittest/git spawn checks.

## Troubleshooting

- **Failed to load Python DLL:** `_internal` missing next to the exe.
- **Already running / console storm:** end leftover Primus tasks; use the
  `dist_windows` build, not `build/primus-pyinstaller`.
- **I couldn't reach my language model:** no GGUF, bad path, or llama.cpp
  native library failed to load (often a missing CUDA runtime on GPU builds).
