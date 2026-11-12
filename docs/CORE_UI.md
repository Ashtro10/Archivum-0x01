# CLU Core: a reconstruction of the reference machine (PC 0.12.1, Android 1.14.1)

The Core is **one vector composition in the reference image's own coordinate space (2000 × 1244)**. It is not a
dashboard. Every region of the reference is rebuilt where the reference has it, with its proportions:
- curved and angular boundaries;
- the hexagonal architecture;
- connecting pathways;
- negative space.

The whole machine scales as one piece. While the Core is shown, the window's sidebar and title bar are gone. The
hex-crosshair icon (bottom right) brings the navigation rail over the machine.

## Where CLU's functions live in the reference's geometry

| Reference region (coordinates) | CLU function | Source of truth |
|---|---|---|
| Arc quadrant (75–572 × 105–576): six curved bands of blocks, labels along the arcs | Data matrix: CLU memory, system memory, inference queue, memory records (log), suggestions, tasks. Lit blocks are the live value; the dim block texture is structure, not data | `/v1/status`, `/v1/inference/status`, `/v1/memory/overview`, `/v1/suggestions` |
| Five circles under the arc, with their bus | Lamps: MODEL ready, VOICE session, NET, HOST answering, WEB research | live state |
| Shield with nested triangles | System status (opens Status) | — |
| Centre frame: angular mark, three schematic blocks, trefoil hexagon | Count blocks: conversations, memory records, documents (cells lit by real counts). The trefoil opens Chat | live counts |
| Dotted concentric rings with their cradle | INPUT, ANALYSIS, SYNTHESIS, OUTPUT, plus the stage list (≡○) and the stage line | the real turn stream |
| Wire hexagon with connector lines | The processing pipeline: documents are injected here (energy runs in; particles on success) | `/v1/documents` |
| Diagonal stripe ladders, ⊖ control, warning triangle | Turn progress (lit stripes). ⊖ opens the intelligence container. The triangle turns red on a fault | the turn stream |
| Segmented ring (top right) and dotted ruler | Progress, source and suggestion counts, answer preview. The ruler shows the length of the streamed answer | the turn stream |
| Left honeycomb | Modules: CLU, WEB (real `net.web` toggle), TERM, SYS, MEM, VOICE, FILES, UTIL | real views and settings |
| Five pointer dots | Interface sound, ambient hum, motion, voice cues, mute | saved on this device |
| Open lower region and its progress rail | File explorer: pick or drop, metadata and preview, Upload into the archive (the rail shows progress), Inject | `/v1/documents` |
| Bottom strip: left and right segments, emblem | ◂ previous command · command line · on-screen keyboard ▸. Holding the triangle-of-triangles emblem executes (a visual confirmation, not a security check) | terminal |
| Octagon: crosshair with two chevrons | Three touch sensors: ‹ STOP · TALK (crosshair) · NEW › | `/v1/stop`, voice session, new conversation |
| Octagon: inner hexagonal screen | System log: real request traces and app events | `/v1/requests` (PC), app events |
| Octagon: honeycomb cells | Readouts: model, host, STT, response, queue, voice (full value in the tooltip) | live state |
| Octagon: waveform band, trapezoid with grid button | Audio waveform. Grid button: mic monitor on the PC (level only); on Android the native session's level | real audio only, otherwise NO SIGNAL |
| Black triangle (left) | Clears the log | — |
| Star / hex crosshair (bottom right) | Sound panel / navigation rail | — |

Expandable surfaces materialize over the machine and leave the architecture recognizable: the intelligence container
over the top-right region, the keyboard over the lower region, the sound panel over the octagon.

## Screen sizes
- **Desktop and tablet (Pad 6):** the whole machine is visible at the reference's proportions.
- **Portrait phone:** the machine fills the height and pans sideways. It never restacks into cards.

## Fidelity check
- `desktop/build/core-ui/core-idle.png` (1608 × 1000) was compared side by side, and as a 50% overlay, with the
  reference.
- Corrections made from that comparison: an opaque background, arc radius and density, the angular mark, label
  collisions, ring weight, and the explorer clearing the emblem.

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
