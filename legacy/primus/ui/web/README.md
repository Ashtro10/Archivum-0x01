# Primus Web UI

Browser-first interface. Talk + System views.

## Where things live

| Area | Path |
|------|------|
| Main shell | `ui/web/primus.html` |
| Talk logic | `ui/web/chat.js` |
| Styles | `ui/web/chat.css` |
| JARVIS Core | `jarvis-main/` (served at `/jarvis/`) |
| Visual states | `ui/visual/states.py`, `ui/visual/manager.py`, `ui/visual/jarvis_host.py` |
| Web API | `ui/web_api.py`, `ui/web_server.py` |
| Voice bus | `ui/voice/bus.py` |
| Memory | `astra/memory/`, System → Memory |
| Personality | System → Personality |
| fullstack mapping | `docs/fullstack-integration.md` |

## Run

```bash
python -m astra
```

Or `scripts\start_primus.bat` on Windows.

Opens `http://127.0.0.1:8765/`

## Do not

- Call the LLM from the browser directly
- Hardcode user identity in UI
- Fake speech animation with random timers
