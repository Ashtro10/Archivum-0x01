# Fullstack-agent integration

Primus absorbed the **useful patterns** from [fullstack-agent](https://github.com/jaredrhod/fullstack-agent). The installer folder is not bundled; features run inside Primus.

## Mapped features

| fullstack piece | Primus equivalent |
|-----------------|-------------------|
| **Face** (ai-visualizer) | `jarvis-main/` + Talk view iframe |
| **Voice bus** (`.voice_state`, `.voice_waveform`) | `ui/voice/bus.py` → `data/signals/` |
| **Voice** (backtalk STT/TTS) | Browser Web Speech API — Mic button, TTS on replies |
| **Memory** (Obsidian vault) | `astra/memory/` SQLite + System → Memory UI |
| **Personality / identity** | `astra/configuration/settings.py` + System → Personality |
| **Launch** (`start.bat`) | `scripts/start_primus.bat` or `python -m astra` |

## Voice signal files

External tools (including ai-visualizer) can read:

- `data/signals/.voice_state` — `idle`, `listening`, `thinking`, `speaking`, …
- `data/signals/.voice_waveform` — JSON amplitude/energy from real PCM

## Optional external backtalk

If you clone [backtalk](https://github.com/jaredrhod/backtalk) beside this repo, point its `signals_dir` at Primus `data/signals/` for shared bus wiring. Primus does not require Claude Code or backtalk to run.

## Not integrated (by design)

- **barehands** gesture board — no webcam dependency in core Primus
- **ai-memory-vault** Obsidian wizard — Primus uses SQLite memory
- **Claude Code installer wizard** — Primus uses local llama.cpp orchestration

## Run

```bash
python -m astra
```

Windows: `scripts\start_primus.bat`
