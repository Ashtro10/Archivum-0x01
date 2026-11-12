# CLU Core: the main console (PC 0.12.0 and Android 1.14.0)

The start view of CLU's window on both platforms (the same files: `shell/core.js`, `shell/core.css`). It is a dark,
machined-metal console on a faint grid:
- the seven original modules, joined by thin energy conduits;
- every instrument driven by CLU's real state.

When CLU can't measure something here it shows **n/a**, never a made-up value.

| Module | What it shows / does | Source of truth |
|---|---|---|
| **A. Data matrix** (top-left arc) | Six segmented rings: CLU memory, system memory, inference queue, memory records (log scale), suggestions, tasks/approvals. A slow scan sweeps one ring at a time (idle motion, not data) | `/v1/status` (governor, model), `/v1/inference/status`, `/v1/memory/overview`, `/v1/suggestions`, every 5 s while visible |
| **B. Sources & ideas** (top-right) | Progress ring for the current turn, answer preview, source and suggestion counters. Tap to open the intelligence container: last answer, its sources (links), suggestions waiting (Keep/Drop for real), background discoveries (merge suggestions, preference questions) | the turn stream; `/v1/suggestions`; `/v1/memory/overview` |
| **C. Core process** (concentric rings) | INPUT, ANALYSIS, SYNTHESIS, OUTPUT. Each ring accelerates while its stage runs, settles when done, and returns to a slow idle drift | the real turn stream (`shell.js` reports every event of every turn: Core terminal, Chat or voice) |
| **D. Core control** (hexagon) | Three touch sensors: TALK (voice session), STOP (`/v1/stop`), NEW (new conversation). Readouts: model, host, STT, response, queue, voice. Audio waveform. Execution console | live states. Console: real request traces (`/v1/requests`, PC) and app events |
| **E. Command strip** | Command line plus on-screen keyboard (both type, Enter runs). Hold-to-execute pad: a deliberate visual confirmation, **not** a biometric or security check. Sound settings | the terminal commands below |
| **F. Modules** | CLU (chat), Research (toggles the real `net.web` setting), Terminal, System, Memory, Voice, Files, Utility (settings) | real views and settings |
| **G. File explorer** | Pick or drop a file: metadata and preview (images, text). Upload into CLU's archive (cyan burst on success), browse the archive, Inject (asks CLU about the document in the conversation) | `/v1/documents` (PC and Android alike) |

## Terminal
`help`, `<question>` / `ask <question>` (the same conversation as Chat), `stop`, `status`, `memory <words>`, `docs`,
`open <view>`, `history` (↑/↓ too), `clear`, `sound on|off|mute|volume N|ambient on|off`, `celebrate`.
- Lines are timestamped; info, ok, warning and error lines are styled differently.
- A failed command says why and turns the execution indicator red. Nothing reports success it didn't have.

## Motion
- **Materialization:** a scan-line, then the outline forms, fragments flicker. Used for windows, the file explorer and
  the first opening.
- **Micro-glitch:** one or two frames, only on module switches, materialization, stage changes and errors.
- **Mechanical feedback:** a light sweep on hover; on press, a ripple, a slight depression and a click.
- **Idle:** breathing inner hexagon, slow ring drift, arc scan, conduit pulses (faster on active paths).
- **Particles:** canvas, pooled, at most 420. Used for upload success, module switches, completed answers, and `celebrate`.
- **Reduced motion:** honoured (OS setting or Settings → Interface motion). Animation loops stop when the view or the
  window is hidden.

## Sound (procedural Web Audio, no files)
- **Categories:** interface (click, chirp, confirm, activate, materialize, dissolve, tick, warn, error, success),
  voice cues (listen, record, ready), and an ambient processing hum (off by default).
- **Mix:** stereo-panned to where the event happens on screen, with a short metallic room reverb.
- **Settings:** master volume and mute, each category on or off. Saved on this device.
- **When it plays:**
  - only after your first click or key press (autoplay rules);
  - never over a spoken conversation;
  - interface sounds follow Settings → Interface sounds until changed here.

## Audio-reactive waveform
- **Mic monitor (PC):** your microphone through an AnalyserNode. Level only; nothing is recorded or sent.
- **CLU's voice on the PC:** the voice session's audio stream through an analyser. Playback is untouched.
- **Android:** the native voice session's real level (mic or speech).
- **Otherwise:** a flat line and "NO SIGNAL". The waveform is never simulated.

## Notes and limitations
- **No React:** vanilla JS with one central state store. The shell's security policy allows only its own scripts, and
  CLU's window has no build step. The store separates state from presentation, as the brief asks.
- **Persona switching** happens in the conversation ("switch to Rick", "back to Clu"). There is no persona API yet, so
  the grid has no fake persona buttons.
- **Request traces** (console) come from the PC. On Android alone the console shows the device's own events.
- **Mic monitor on Android:** hidden. The native voice session owns the microphone there.

Tests: `PrimusShellTest.theCoreConsoleWorks` (`core.ui.js`) runs against a real host at 1440×900, Pad 6 (1280×800) and
phone (390×844) sizes. It checks:
- all modules, conduits, and real figures in the arc;
- NO SIGNAL when there is no audio;
- the terminal and on-screen keys;
- a real streamed turn driving the rings;
- the intelligence container;
- a real upload into the archive;
- the module and STOP sensor actions, and the Research toggle;
- no sideways scroll, and no script errors.

Screenshots are in `desktop/build/core-ui/`.
