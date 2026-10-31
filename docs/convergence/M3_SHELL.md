# M3 — Desktop shell

_2026-10-31. Built and tested on Linux (headless Chromium through Playwright). Not yet opened on Windows._

## What was built

**The window.** A local page served by the CLU host (`desktop/src/main/resources/shell/`: `index.html`, `shell.css`,
`shell.js`, `face.js`), with no frameworks and no build step. On Windows, `ShellLauncher` opens it in Microsoft Edge's
app mode (`msedge --app=…`), which gives a window with no address bar. Edge ships with Windows 10 and 11. Elsewhere it
opens in the default browser.

**Screens:**
* **Chat:** a conversation list, streamed replies (SSE), a Stop button, and the face. Sources sit on one collapsed
  "Sources (n)" line under the answer and expand only when clicked.
* **Memory:** search, a category filter, and an add/edit/delete editor. As you type a date, the editor shows how CLU
  reads it ("Understood as: around March 2018") and flags a date it can't read before you save. Duplicates, conflicts,
  ambiguous names and hints come back as notes; nothing is fixed silently.
* **People:** add a person. Possible duplicates are reported, never merged.
* **Timeline:** dated memories, oldest first, grouped by year.
* **Documents:** import (TXT, Markdown, CSV, HTML, JSON, PDF, .docx) and search inside them. Duplicates and
  unsupported files are refused with a reason.
* **Suggestions:** inferred memories waiting for approval, with a badge in the sidebar. You can edit the text before
  approving. Reject erases the suggestion.
* **Status:** model state with load/unload, memory bars (CLU vs. the PC), and what the resource governor did recently.
* **Settings:**
  * this PC's limits (memory budget, reserve, unload threshold), applied to the running governor and saved to `host.json`;
  * every CLU setting, generated from the core's settings registry with the right control for each type;
  * sensitive settings are shown but disabled until owner verification exists (M6); the API refuses them with 403.

**The face** (`face.js`): a hexagonal ring of points with three states.
* idle: breathing, about 4 frames a second;
* thinking: turning, about 30 frames a second;
* speaking: pulsing with the words as they arrive, about 30 frames a second.

It draws nothing while the window is hidden or minimised, and shows a still frame when the system asks for reduced
motion. It was written for CLU; no code was copied.

**Tray and start-up** (`ShellLauncher`, `HostMain`):
* a notification-area icon with Open CLU, Unload the model now and Quit CLU; double-clicking it opens the window;
* `clu-host --open` starts CLU and opens the window;
* if CLU is already running, a second `clu-host --open` asks the running one to open its window (`POST /v1/shell/open`)
  instead of starting a second copy;
* `--no-tray` runs without the icon.

**Getting into the window safely.** The page itself carries no secret. The host opens it as `/#code=<launch code>`.
The code:
* is random;
* works once;
* expires after 60 seconds.

The page exchanges it at `POST /v1/session` for the API token, keeps the token in `sessionStorage` (this window only),
and removes the code from the address. Without a code the page shows a locked screen. Static files are served with a
strict Content-Security-Policy:
```
default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self';
frame-ancestors 'none'; base-uri 'none'; form-action 'none'
```
That means no inline scripts, nothing from other origins, and the page can't be framed. The existing loopback-only
binding and the Host/Origin checks still apply to everything.

**New API endpoints** (full table in `desktop/README.md`):
* `/v1/session`;
* `/v1/memories` (+ `/{id}`), `/v1/dates/parse`, `/v1/timeline`, `/v1/people`, `/v1/documents` (+ `/search`);
* `/v1/suggestions` (+ `/{id}/approve|reject`);
* `/v1/settings`, `/v1/host-config`;
* `/v1/shell/open`.

Each one goes through the same core repositories and rules as the phone's archive screens.

## Verification

| Check | Result |
|---|---|
| `:desktop:test` | 33 tests, 0 failures, 1 skipped: `HostRealModelTest`, which needs `-Dclu.desktopRealModel`. 11 of them are new in `ShellApiTest`. |
| `ShellApiTest` | Covers: <ul><li>launch code single use / expiry / garbage;</li><li>static assets with CSP and no token;</li><li>path traversal refused;</li><li>memory create with "around March 2018", unreadable date refused, unknown date stays unknown, search, partial edit, timeline order, delete;</li><li>date reading;</li><li>people duplicates not merged;</li><li>document upload, duplicate 409, unsupported 422, search;</li><li>suggestions approve with edit / reject saves nothing / no double approve;</li><li>settings list, range check, unknown key, sensitive key 403;</li><li>host config defaults, applied to the governor, persisted, invalid and unknown fields refused;</li><li>`/v1/shell/open`.</li></ul> |
| UI contract (`theShellOnlyTalksToTheApi`) | The shell's scripts call `fetch` only on `/v1/…` paths of their own origin. They use no other storage than the session token in `sessionStorage` (no `localStorage`, IndexedDB or WebSocket), no `innerHTML`, `eval` or inline handlers, and load nothing from other origins. The shell has no database access of its own; the host process is the only thing that opens `clu.db`. |
| `ShellUiTest` (Playwright + Chromium, opt-in) | The real host with a scripted model and a fake Wikipedia (no network), driven through the window. 20 checks, all passed. <ul><li>locked without a code; code stripped from the address;</li><li>streamed chat;</li><li>research answered from the evidence with no source talk, one collapsed source line, link shown on request;</li><li>memory added with a live date reading, then search;</li><li>recall in chat answered from that memory;</li><li>timeline under 2018;</li><li>suggestion edited and approved, badge cleared;</li><li>person added;</li><li>document imported and searched;</li><li>status bars;</li><li>host limits saved; sensitive settings disabled;</li><li>no script errors or CSP violations.</li></ul> Afterwards the test checks the stores directly: the memory with its 2018 date, the approved edited suggestion, the person and the document are there. It also checks that no memory or personal detail reached the search provider. |
| Screenshots | `docs/screenshots/desktop-m3/` (chat, memory, status, settings), taken by `ShellUiTest`. |

**Defects found and fixed while testing:**
* **`GET /v1/host-config` returned `{}` on a fresh install.** The default JSON encoder drops default values, so the
  Settings form came up empty. Defaults are now included, and unknown fields in a PUT are refused. A test was added.
* **The source line ran into the answer text.** It now sits on its own line.
* **The date reading could show a stale "Not understood" for a moment after saving.** Late readings for text you've
  since changed are now ignored, and a save shows the stored reading.
* **Disabled controls didn't look disabled.** They are now dimmed.
* **The status screen pointed to a "Models" screen that only exists on the phone.** It now says
  `clu-host --register-model <file.gguf>`.

**Acceptance against the roadmap.** Chat, memory recall, research with sources and memory-suggestion approvals work end
to end in the window: verified by `ShellUiTest`, with a scripted model and faked search, on Linux.

## Not verified / differences from the roadmap

* **Not run on Windows yet:**
  * the Edge app-mode window;
  * the tray icon (AWT `SystemTray`; the build machine is headless);
  * the single-instance hand-off;
  * how the window looks at Windows scaling.

  To check on your PC:
  ```
  gradlew :desktop:installDist
  build\install\clu-host\bin\clu-host.bat --open
  ```
  Expect a CLU window and a hexagon icon in the notification area.
* **No real model in the window test.** The UI path doesn't depend on the model. The real-model API path was verified
  in M2 (SmolLM2 over the API); a real-model window session needs your PC.
* **Edge app mode instead of WebView2:**
  * WebView2 needs a native host (C#/C++ or a JNI bridge), which is a new runtime and build chain;
  * Edge app mode uses the same engine and is already on Windows 10/11;
  * swapping in WebView2 later only replaces `ShellLauncher.open`, because the page and API stay as they are.
* **The face is CLU's own.** The roadmap suggested jarvis-main (MIT) for the face. A small canvas face was simpler and
  lighter than adapting it, so nothing was taken from it.
* **"Approvals panel" covers memory suggestions only.** Approving actions (tools that change things) needs the
  permission broker in M6, and the panel will show those too.
* **Shell resource use on Windows isn't measured.** The face's frame budget is designed to keep idle use low, but
  Edge's own process memory and CPU need measuring on your PC.
* **Not built yet** (the phone has these; they come later):
  * people merge;
  * projects;
  * bulk import/export;
  * voice (M4).

## Rollback

The shell is static files plus API routes in `:desktop`. Running `clu-host --no-tray` without `--open` gives the M2
host behaviour. The API stays usable on its own.
