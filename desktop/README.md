# CLU host (desktop)

CLU's brain running on the PC as one background process: the same `core` as the phone (memory archive, conversation,
research, persona), the local llama.cpp engine, a resource governor, and a local API for the desktop shell (M3) and the
phone (M5).

## Build

```
./gradlew :desktop:installDist          # → desktop/build/install/clu-host/
scripts/build_windows_native.sh         # Windows clu_llama.dll (MinGW-w64 cross-build), picked up by installDist
```

`installDist` produces `bin/clu-host` (Linux/macOS), `bin/clu-host.bat`, `bin/clu-host-background.cmd` (Windows,
below-normal priority, minimised) and `lib/` with the jars and `lib/native/{windows-x64,linux-x64}/`. Java 17+ must
be installed for now; a bundled runtime comes with the installer (M11).

## Run

```
clu-host --register-model D:\models\Qwen3-8B-Q4_K_M.gguf   # once: add a model and make it active
clu-host-background.cmd                                     # Windows: start in the background
clu-host --print-token                                      # the API token, for clients
```

Data lives in one folder:
* Windows: `%LOCALAPPDATA%\CLU`;
* Linux: `~/.local/share/clu`;
* override with `--home` or `CLU_HOME`.

It contains:
* `clu.db` (everything CLU knows);
* `host.json` (this PC's limits);
* `api-token`;
* `vault.key` and `secrets.json` (owner-only);
* `files/`;
* `models/`.

### host.json

| Key | Default | Meaning |
|---|---|---|
| `port` | 47821 | Local API port (127.0.0.1 only) |
| `memoryBudgetGB` | 16 | CLU's ceiling, host process including the model |
| `reserveGB` | 2 | Always left for your programs when deciding whether a model may load |
| `pressureFreeGB` | 1.5 | An idle model is unloaded when the PC's free memory falls below this |
| `governorIntervalSeconds` | 15 | How often the governor checks |
| `nativeLibrary` | "" | Path to `clu_llama.dll` if not the bundled one |

Model settings (threads, context, idle unload after N minutes) are CLU's own settings in the database, the same as
on the phone (`model.threads`, `model.contextLength`, `model.autoUnloadMin`).

## Local API

Bound to `127.0.0.1` only. Every call except `/v1/health` needs `Authorization: Bearer <token>`. Requests whose
`Host` or `Origin` isn't this computer are refused (protection against DNS rebinding and cross-site requests). Bodies
are limited to 64 KiB.

| Method | Path | Body / result |
|---|---|---|
| GET | `/v1/health` | `{"ok":true,"name":"CLU","version":…}` (no token) |
| GET | `/v1/status` | model state, governor figures (MB) and its recent actions, conversation count |
| GET | `/v1/conversations` | `[{"id","title","updatedAt"}]` |
| POST | `/v1/conversations` | `{"title"?}` → `{"id","title"}` |
| GET | `/v1/conversations/{id}/messages` | `[{"id","role","content","createdAt","sources":[…]}]` |
| POST | `/v1/conversations/{id}/turns` | `{"text","voice"?}`. With `Accept: text/event-stream`: events `status`, `text` (`delta`), `error`, `done` (`text`, `error`, `interrupted`, `sources`). Otherwise one JSON reply |
| POST | `/v1/stop` | cancels the reply in progress |
| POST | `/v1/model/load`, `/v1/model/unload` | the model's state |

Example:
```
curl -H "Authorization: Bearer $(clu-host --print-token)" -H "Accept: text/event-stream" \
     -d '{"text":"What did I tell you about Riya?"}' http://127.0.0.1:47821/v1/conversations/<id>/turns
```

## Tests

```
./gradlew :desktop:test                                                     # governor, platform, API (scripted engine)
./gradlew :desktop:test --tests '*HostRealModelTest' -Dclu.desktopRealModel=<file.gguf>   # real model end to end
scripts/measure_desktop_host.sh <file.gguf>                                 # installed host: idle/loaded/unloaded memory (Linux)
```
