# Stage 3.2 — unresponsive buttons fixed, hexagon HUD redesigned (1.5.3-prototype)

**Not tested on a device.** No Android device or emulator was available. Every result below comes from Robolectric (Android framework code on the build machine) or JVM tests. Physical taps on the Pad 6 remain to be confirmed (DEVICE_ACCEPTANCE checks 0a–0c).

## 1. Unresponsive buttons

### Confirmed cause
The Settings entries (*Connection*, *Conversation*, *Memory & diary*, *Search & internet*, *Phone & tablet*, **Diagnostics**, *On-device voice/model*) and the two engine cards on the Connection screen are cards made of text lines, with a click listener on the card.

The text lines come from the shared `p()` helper, which calls `setTextIsSelectable(true)`. A selectable `TextView` is clickable, so it consumed every tap on the text and the card's listener never ran. Only a tap on the card's thin padding worked, which feels like a dead button. The cause was not an overlay, the HUD, the main thread or a disabled state:
* no control in the app is ever disabled (`isEnabled` is never set);
* the HUD view is not clickable or focusable.

The earlier tests missed it because they called `performClick()` on the card directly, bypassing touch dispatch.

### Fix
`LinearLayout.tappable(onClick)` in `Ui.kt` makes a card one tappable control:
* its text lines stop taking touches (and stop being selectable, which makes no sense inside a button);
* the card gets a minimum 48 dp target, the platform's touch feedback, and one accessibility label.

It is used for the Settings sections and the Connection engine cards, the only two places with this pattern. Other screens' text keeps its selection behaviour, and nothing else was removed or changed.

### Tests
`TouchDispatchTest` (Robolectric) sends **real touch events** (ACTION_DOWN/UP dispatched from the window's root view, after scrolling the control into view like a finger would):

| Test | What it proves |
|---|---|
| `settingsSectionsOpenWhenTheirTitleIsTapped` | Tapping the title of Diagnostics, Connection, Phone & tablet, Memory & diary, Search & internet and Conversation, and the description line of Diagnostics, opens the right screen. **Negative control:** with the old click listener restored, this test fails with "tapping Diagnostics opened nothing". |
| `chatControlsRespondToRealTapsBeforeAndAfterARequestAndAfterBackgrounding` | ☰ opens the menu and ⬡ opens voice chat. Send works by tap. After a reply and two background/foreground cycles, ☰ and ⬡ still respond. No full-screen clickable layer exists before or after. |
| `voiceScreenControlsWorkAndTheHudNeverTakesTouches` | The HUD is not clickable or focusable. A tap on it does nothing and blocks nothing. The speaker button toggles spoken replies, ⌨ returns to chat, and there's no blocking overlay. |
| `connectionEngineCardsSelectOnTap` | Tapping the "This device" card's title selects the on-device engine. |
| `theVoiceMainButtonStartsAndStopsListening` | A tap on the main voice button starts the voice pipeline. On the JVM the native speech engines can't run, so the "second tap stops it" step only runs when the session is still alive; stopping by tap isn't fully proven here. |
| `theHudDrawsNoTextInAnyState` | No text is drawn by the frame or in any state. **Negative control:** against the old HUD it reports `[PD-3P, A-78, SYS·06, CLU, A-78, PD-3P]`. |

## 2. Hexagon HUD

* **Removed:** all text inside the hexagon, meaning the six frame labels and the state/timer text that sat in the centre. The state ("LISTENING", "THINKING"…) and the session timer are now on one line **below** the hexagon, and the HUD's accessibility label announces the state.
* **Added:** two sharp, angular, technological eyes inside the hexagon:
  - icy cyan edges and an electric-blue layered glow;
  - each eye slanted (inner corner lower), under an angled dark brow plate, which gives the narrowed, watchful look;
  - hexagonal irises with a dark vertical slit;
  - circuit traces with small nodes running from each eye towards the frame.
  - There is no face, mouth or other features; the hexagon frame is unchanged.
* **States** (from the existing voice engine states; nothing new was invented):

  | State | Look |
  |---|---|
  | Idle | Steady, restrained glow (static: no animation loop) |
  | Listening | Irises open and the glow rises with the microphone level |
  | Thinking | Light pulses travel along the eyes' circuit traces |
  | Speaking | The eyes pulse with the level of Clu's actual audio |
  | Offline / error | Eyes dark and dim, frame desaturated, red dot |

* **Rendering:** a few canvas paths per frame on the existing view. The static frame stays a cached bitmap. No new dependency, model or animation engine. The view takes no touches.
* **Preview:** `docs/screenshots/s32-hud-states.png` (rendered by Robolectric's native graphics, not a device screenshot), showing idle, speaking, thinking and offline.

## 3. Not changed
Voice pipeline, ASR, TTS, memory, diary, research, sync, diagnostics logic and database (no schema change).
