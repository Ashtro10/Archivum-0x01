# General software authoring

This milestone removes the predefined-family boundary. A request for an application that no kit family covers (a
file explorer, a timer, a solar system viewer, a knowledge graph…) goes to CLU's authoring engine. There, the
development model writes the application, CLU runs it in a real browser against checkable requirements, and the
actual failures go back to the model for revision, within a budget. Nothing is installed unless every check passes.

## Result with the models available here (stated first)

**The engine works; the available local model cannot do the job.**

* **Engine:** proven with a scripted test model (`SoftwareAuthorTest`).
  * A broken first draft is caught in the browser.
  * The real failure and the model's own code go back to it, and the revision passes all 15 checks.
  * The app is installed with a scoped file permission, modified by a later instruction (v2), survives a restart,
    and can be paused and resumed.
* **Measured model ability** (`ModelEvaluationTest`): six small coding tasks, each run in a real browser against
  tests.

  | Model | Passed |
  |---|---|
  | Qwen3-0.6B | 1/6 (only the syntax fix) |
  | SmolLM2-135M | 0/6 |

  These are the only real local models on this machine (`1b-q4km.gguf` has random weights). Remote providers
  (Ollama Cloud and others) are blocked by this environment's network policy (HTTP 403), and Hugging Face is blocked,
  so no stronger model could be obtained or evaluated.
* **Acceptance in the real window** with Qwen3-0.6B (`CapabilityAuthoringUiTest`, 16k context):
  * "Give yourself a futuristic file explorer with a three-dimensional galaxy interface" → **not installed**. In 5
    attempts the model never produced files that passed even the build check; the last answer still put `<script>`
    inside `app.html` after being told why that fails. CLU said it couldn't, installed nothing, and kept the code and
    the failures in the development history.
  * With CLU's mechanical fix (moving the inline script into `app.js`), the model's code reached the browser. It
    crashed on start (`getElementById('path')` is null), drew three hardcoded fake nodes instead of the real files,
    and called a method that does not exist. Attempts 3 to 5 repeated the same files despite the feedback. The code
    is kept unedited in `docs/evidence/galaxy-explorer-qwen3-0.6b/`.
  * Restart → weather and map are still installed; the list of applications is accurate.
  * "Add a search function to your galaxy file explorer" → the first run exposed a real defect. With no explorer
    installed, the sentence fell through to chat and the model *claimed* to have added search. Fixed: such sentences
    are always handled as instructions, and CLU answers "I don't have a galaxy file explorer to improve… I haven't
    changed anything". The acceptance test now asserts this.

A capable coding model, local or a permitted free remote one, is needed for CLU to write applications of this size.
The engine, checks, permissions and memory are ready for it. Model choice is by measured results, so a better model
is used as soon as it is available and measured.

## How it works (`desktop/.../capability/`)

* **Routing:**
  * `CapabilityCommand` (core) sends a request for something no kit family covers to `CapabilityDeveloper.authorFlow`.
    It used to decline such requests.
  * Improvements to an authored app (`kind = app`) are matched by installed names ("your galaxy file explorer")
    and modify the installed code.
  * Requests to change CLU itself ("improve your memory retrieval") are `UPGRADE_CORE`. They go to the self-repair
    engine (isolated Git worktree, regression tests, diff, owner approval); see "Core upgrades".
* **Requirements (`Requirements`):** generic, checkable building blocks.
  * The blocks are `renders`, `fs` (browse a permitted folder: list, open, back, empty, error), `search`,
    `scene3d` (canvas drawn, turns when dragged) and `timer`.
  * Each comes with a page contract (`data-entry`, `data-action`, `data-role`) so the checks can find things. The
    checks are declarative steps run by `/kit/clu-probe.js` in the page: click, type, press keys, expect elements or
    text, canvas drawn, canvas changes on drag.
  * Applications are not templates. The blocks only define what must be true; the model writes all of `app.html`,
    `app.css` and `app.js`.
* **Mechanical fixes:** CLU itself moves inline `<script>` blocks into `app.js` and drops inline handlers, like a
  linter's autofix, so a model's logic gets tested rather than rejected for formatting. Each fix is recorded.
* **Scaffold (`AppScaffold`):** `index.html`, `boot.js`, `manifest.json` and `checks.json` are identical for every
  application. The build check rejects inline scripts and handlers, style attributes, `eval`, and references to other
  sites. With Node present, JavaScript syntax is checked first (`node --check`); otherwise the browser run reports it.
* **Loop (`SoftwareAuthor`):**
  * The coding model writes; the debugging model revises, given its own files plus every failed check, script error
    and build problem.
  * Budget: attempts and minutes, configurable (default 5 / 45). Pause and resume are supported; the workspace is
    kept.
  * The prompt size is measured against the model's real context window, and CLU says so when the window is too
    small (default 4096; authoring needs more).
* **Verification:** `ExperienceVerifier` (headless Chromium or Edge) runs the requirement checks against a fixture
  folder CLU creates. The fixture holds files, nested folders, an empty folder, and a link leading outside (which
  must be refused). Then a screenshot is taken and inspected.
* **Install:** versioned in the existing registry, with SHA-256 hashes and a preview. Earlier versions are kept for
  rollback. The app is served at `/x/<id>/<v>/` and appears in the rail and the Capabilities view.

## Permissions and isolation (`AppServices`)

* **Nothing by default.** An application declares permissions in its manifest. `fs.read` gives read-only access
  below one folder: the owner lists permitted folders (Capabilities → Permissions), and an installed app is granted
  one.
* **Containment:** `..`, absolute paths and links leading outside are refused by real path. Listings, reads and
  searches are capped. Verification uses a fixture, never the owner's files.
* **Isolation:** pages keep the shell's CSP (no other sites, no inline code). Scoped APIs are the only route to the
  host.

## Models (`DevModels`, `AuthoringBench`)

* **Candidates:** the local model counts only when one is actually selected. A remote model (OpenAI-compatible, e.g.
  Ollama Cloud) counts only with three owner consents: enabled, confirmed free, and allowed to receive development
  context.
* **Roles:** `code`, `edit` and `debug` are chosen separately by measured success. They are task roles of one CLU,
  not separate identities.
* **Measurement:** benchmark tasks are run in the browser against tests (Capabilities → Measure models), and results
  are kept per model and role.

## Resources (`HostResources`)

Each run records what the host really offers: CPU cores, free RAM and disk, GPU, JavaScript checker, browser,
internet reachability and the model's context. Low disk is flagged so no large downloads are attempted.

## Core upgrades

The self-repair engine (`SelfRepairService.develop`) already builds core changes in an isolated worktree, runs the
regression tests, produces a diff and report, and merges only after owner approval. CLU identifies the subsystem
files from its source index and explains the path. It needs an owner-supplied acceptance test, deliberately: a test
written by the same small model could not judge its own change.

## Evidence

| Test | What it covers |
|---|---|
| `SoftwareAuthorTest` | engine mechanics (scripted model) |
| `ModelEvaluationTest` | harness check; real models with `-Dclu.capModels` |
| `CapabilityAuthoringUiTest` + `src/test/ui/authoring.ui.js` | the milestone acceptance in the real window, with `-Dclu.shellUi` and `-Dclu.capModel`; asserts that CLU's account is true either way |
| `CapabilityCommandTest` | parsing, including core upgrades and installed names |
