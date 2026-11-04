# CLU capabilities: development and management from conversation

CLU treats instructions such as "Give yourself a sci-fi weather service", "Create a fantasy map too", "Make the
tactical map my default" or "Improve my weather service" as development work on itself. It builds the experience
in an isolated workspace, tests it in a real browser against its live services, looks at the rendered screen,
installs it only if it passes, and manages it alongside its other capabilities. All of this runs on the desktop host.

## What is real, and where the limits are

* **Interpretation** (`core/.../capability/CapabilityCommand.kt`) is deterministic. It recognises create, improve,
  list, open, default, enable, disable, rollback, uninstall and inspect, but only when a capability noun is present,
  so ordinary chat ("What's the weather in London?", "Make me a sandwich") is left alone. The agent routes these
  instructions to the `capability.manage` tool (`CluAgent`, step 3c) before the model is involved. The tool exists
  only on the desktop, so Android is unchanged.
* **Generation is composition, not free-form coding.** A new experience is generated as its own source (manifest,
  page layout, theme and configuration/wiring) on top of CLU's shared, tested experience kit: a runtime, a map engine
  and a weather client. The development model contributes a name, a tagline and, for styles CLU has no curated look
  for, a palette. Its answer is validated: grammar-constrained JSON, `#rrggbb` colours, WCAG contrast, and names that
  are new and not generic. Rejected proposals are recorded.
  Reason: the local models available measurably cannot write working applications (the Milestone 2 coding benchmark
  scored 3/9 with Qwen3-0.6B). In the acceptance run, Qwen3-0.6B named every experience "CLU" or "CLU Weather", and
  validation rejected those names.
* **Families CLU can build today:** weather, map, and dashboard (combining an installed weather and map capability).
  Any style is accepted: seven have curated looks, and other style words get a derived or model-proposed palette. A
  request for anything else (e.g. "a pomodoro timer app") is declined and recorded, never faked.
* **Data:** weather comes from Open-Meteo (free, no key). Map geography works offline (Natural Earth country
  outlines and about 3,000 GeoNames cities, bundled with CLU). OpenStreetMap street tiles come through CLU when
  reachable. When a provider is unreachable, the experience says so and shows no figures. Data that could not be
  refreshed is labelled with its age.
* **This environment** cannot reach Open-Meteo, OpenStreetMap or Ollama Cloud (the network policy answers 403).
  Live data was therefore verified with a local stand-in that answers in Open-Meteo's format (test equipment only:
  `OpenMeteoStandIn`). Against the real, blocked providers, CLU recorded "not verified here: live weather data" and
  "street tiles" as limitations instead of passes.

## The workflow (`desktop/.../capability/CapabilityDeveloper.kt`)

Each run is recorded step by step in the development memory and shown live in chat:

1. **understand**: requirements from the request.
2. **existing**: improve the existing capability, add a variant, or say it already exists. A dashboard needs its
   weather and map dependencies.
3. **memory**: earlier runs of the same family: their fixes and limitations. A remembered contrast fix is applied
   up front.
4. **research**: which data sources are reachable now, which browser can verify, which offline data is present.
5. **plan**: features (for an improvement: what was asked, else the next improvements CLU knows) and the look.
   The model's proposals are validated here.
6. **modules**: integration points (kit components, services, registry, shell).
7. **implement**: files written to `capabilities/workspaces/<run>/out`.
8. **build check**: required files, a valid manifest, nothing inline (the CSP would block it), nothing pointing
   outside CLU.
9. **test**: the page loads in headless Chromium or Edge with `?selftest=1` and a one-time launch code. The
   experience's own self-tests run against CLU's live services, and the results are read from the rendered DOM.
   Examples:
   * the temperature shown equals the one the service returned;
   * land is drawn where Africa is and water where the Pacific is;
   * drag pans and the wheel zooms;
   * searching "Paris" centres on Paris;
   * "unavailable" is said plainly when there is no data.
10. **inspect**: a screenshot, with pixel checks: not blank, the theme's colours on screen, foreground content
    present, and land and water visible on maps.
11. **fix**: failures are diagnosed and a known fix is applied, at most 3 attempts: drop an obscuring texture, raise
    contrast, separate land and water colours, or rebuild without a newly added feature that broke. With no known
    fix, the run stops.
12. **integrate**: dependencies are enabled; the version is served at `/x/<id>/<version>/` and listed in the
    registry.
13. **install**: files are copied to `capabilities/installed/<id>/<version>/` with SHA-256 hashes and the screenshot
    as `preview.png`. Earlier versions are kept for rollback.
14. **report**: what was built, what was checked, what could not be verified, how to open it.

A version that fails verification is never installed. Workspaces are removed with `SafeDelete` (allowed root:
`capabilities/workspaces`), and leftovers of an interrupted run are cleaned at start-up.

## Registry and runtime

* `capabilities/capabilities.db` (its own SQLite database) holds the following.
  * Capabilities: id, kind, style, name, description, category, active version, enabled, uninstalled, config,
    limitations, dependencies, interfaces, recovery.
  * Versions: files with hashes, blueprint, verification evidence, run id.
  * Preferences: the default per family, and the remote-model consents.
  * Event history (created, installed, updated, default, switched, rolled back, uninstalled), development runs with
    their steps, and measured model scores.
* The four states are separate facts.
  * **Installed**: the files are on disk and their hashes match.
  * **Verified**: this version passed in a browser.
  * **Enabled**: not turned off by the owner.
  * **Active**: open in CLU's window now, reported by the shell.
* **Operations:** create, improve (a new version), list, search, open, set default, enable/disable, roll back,
  activate a version, duplicate as a variant, inspect, and uninstall.
  * Uninstall needs confirmation; its files move to `capabilities/trash/`.
  * Opening without naming one asks "which one?" when several fit and no default is set. Defaults exist only when
    the owner chooses one.
* **Runtime:** experiences are static, CSP-restricted pages served by CLU (`/x/…`, kit at `/kit/…`) and shown in a
  sandboxed iframe in the shell.
  * They talk only to CLU's API: `/v1/kit/weather`, `/places`, `/tiles`, `/status`, `/home`, and their own
    `/v1/capabilities/{id}/config`.
  * The rail lists every enabled experience. The Capabilities view shows cards with previews, status, actions,
    version history and the development history.
* **API:** documented on `CapabilityRoutes` (`/v1/capabilities…`) and `KitServices` (`/v1/kit/…`).

## Models (`DevModels.kt`)

* **Local by default:** CLU's own model, through the shared inference service.
* **Optional remote:** an OpenAI-compatible endpoint, e.g. Ollama Cloud (`https://ollama.com/v1`). It needs three
  separate owner settings: enabled, "this endpoint is free" (CLU cannot see billing, so it never assumes), and "may
  receive development context". Nothing is sent without all three; a test checks that zero requests are made before.
* **Choice:** by measured validation rate per task (`model_scores`), not by parameter count.

## Evidence

* `CapabilityCommandTest` (core): parsing and non-interference with chat.
* `CapabilityUnitTest`: design validation, contrast fixes, build check, diagnosis, remote consent, weather
  honesty, gazetteer, registry persistence.
* `CapabilityDevelopmentTest`: the full workflow through the API with a real browser, covering:
  * create weather, tactical map and fantasy map;
  * the "which one?" question, default, improve to v2, rollback and re-activate;
  * restart persistence;
  * an unreachable provider, and unknown families.
* `CapabilityUiTest` + `src/test/ui/capability.ui.js`: acceptance tests A–H typed into CLU's real window, with a
  host restart between phases and screenshots of every step.
  * Opt-in with `-Dclu.shellUi=<node>`.
  * `-Dclu.capProvider=real` uses the real providers.
  * `-Dclu.capModel=<gguf>` uses a real local model for the design step.
