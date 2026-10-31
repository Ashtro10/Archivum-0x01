# Primus UI

Current presentation layer. Backend/core safety architecture is frozen at
B-038 (ADR-0036). Talk path is B-039 (ADR-0038). UI chrome: ADR-0037.
Runtime: `docs/runtime.md`.

## Boundary

```
ApplicationState
        ↓
VisualStateManager
        ↓
Primus Core (small)  +  conversation stream  +  composer
        ↓
 System View (secondary)   Settings (discreet)
```

The UI imports `astra.state`, `astra.presentation`, `astra.product`, and
`astra.configuration` only. It does not import engines, agents, or Security.

Talk path is injected: `ConversationOrchestrator` → existing engines when
selected → `LLMBackend.generate`. UI calls `ConversationPort.ask` only.

## Views

- **Talk (primary):** small Core, conversation stream, one field, Send / Mic / Stop.
- **System:** existing technical panels (render-only). Opened on demand.
- **Settings:** identity and accessibility notes. No deploy/approve.

## Visual language

Near-black background, restrained cyan linework, thin borders, large space.
Core is a modest mark, not a cockpit.

## Visual states

idle, listening, thinking, speaking, error (plus internal processing aliases).

Status text is a fixed overlay (`Thinking…`). It is never a conversation message.

## Refresh

System View loads state when opened. Primary talk does not poll milestones
and does not rebuild the conversation.

## Audio

`AudioAnalyzer.consume` accepts real PCM. No TTS/STT backend yet. Mic is
honest about that.

## Run

```
python -m astra
python -m astra --demo
python -m astra --smoke
```

Distribution: `dist_windows/Primus.exe` from `python scripts/build_windows.py`.
