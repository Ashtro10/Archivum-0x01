# CLU host (desktop)

CLU's brain running on the PC as one background process: the same `core` as the phone (memory archive, conversation,
research, persona), the local llama.cpp engine, a resource governor, a local API, and the desktop shell (CLU's window,
M3) served from that API. The phone comes in M5.

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
clu-host --open                                             # start CLU and open its window
clu-host-background.cmd                                     # Windows: start in the background (with the tray icon)
clu-host --print-token                                      # the API token, for clients
```

### The window and the tray

* **The window.** On Windows it opens in Edge's app mode (no address bar); elsewhere it opens in the default browser.
  It's served by the host, so it works only while CLU runs.
* **Getting in.**
  * Each opening carries a single-use launch code (valid for 60 seconds), which the page swaps for the API token.
  * The address alone shows a locked screen.
* **The tray icon:**
  * Open CLU;
  * Unload the model now;
  * Quit CLU;
  * double-clicking it opens the window.
* **One copy at a time.** Running `clu-host --open` while CLU is already running opens the running copy's window
  instead of starting a second one.
* **Without the icon.** `--no-tray` runs without it, for servers or remote sessions.

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

* Bound to `127.0.0.1` only.
* Every call except `/v1/health`, `/v1/session` and the shell's static files (`/`, `/shell/*`) needs
  `Authorization: Bearer <token>`.
* Requests whose `Host` or `Origin` isn't this computer are refused (protection against DNS rebinding and cross-site
  requests).
* JSON bodies are limited to 64 KiB; document uploads to 50 MB.
* The static files carry a strict Content-Security-Policy and never contain the token.

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
| POST | `/v1/session` | `{"code"}` → `{"token"}`: swaps a single-use launch code (no token needed) |
| POST | `/v1/shell/open` | opens the window (used by a second `clu-host --open`) |
| GET, POST | `/v1/memories` | `?q=` search, `?category=`; create `{title, content, when, people, places, tags, category, certainty, importance}` → `{memory, possibleDuplicates, conflicts, hints, ambiguousPeople}`. An unreadable `when` is refused (400), never guessed |
| PUT, DELETE | `/v1/memories/{id}` | edit (fields not sent are kept) / delete |
| GET | `/v1/dates/parse?q=` | `{understood, label, precision}`: how a date is read |
| GET | `/v1/timeline` | dated memories, oldest first |
| GET, POST | `/v1/people` | list / create `{name, relation, aliases, notes}` → `{person, possibleDuplicates}` |
| GET, POST | `/v1/documents` | list / upload: raw bytes, file name in `X-File-Name` (URL-encoded); 409 duplicate, 422 refused |
| GET | `/v1/documents/search?q=` | matching passages |
| GET | `/v1/suggestions` | memory suggestions waiting for approval |
| POST | `/v1/suggestions/{id}/approve`, `/reject` | approve (optionally `{text}` to edit first) / reject (erased) |
| GET, PUT | `/v1/settings` | all settings with type, value and default / `{key, value}`. Sensitive settings → 403 until owner verification (M6) |
| GET, PUT | `/v1/host-config` | this PC's limits; a PUT applies to the governor at once and is saved to `host.json` |

Example:
```
curl -H "Authorization: Bearer $(clu-host --print-token)" -H "Accept: text/event-stream" \
     -d '{"text":"What did I tell you about Riya?"}' http://127.0.0.1:47821/v1/conversations/<id>/turns
```

## Tests

```
./gradlew :desktop:test                                                     # governor, platform, API, UI contract (scripted engine)
./gradlew :desktop:test --tests '*ShellUiTest' -Dclu.shellUi=node [-Dclu.shellUiShots=<dir>]   # the window in Chromium (Node + playwright)
./gradlew :desktop:test --tests '*HostRealModelTest' -Dclu.desktopRealModel=<file.gguf>   # real model end to end
scripts/measure_desktop_host.sh <file.gguf>                                 # installed host: idle/loaded/unloaded memory (Linux)
```
