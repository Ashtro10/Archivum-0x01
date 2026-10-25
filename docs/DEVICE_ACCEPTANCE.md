# On-device acceptance checklist — v1.0.0-prototype

**Status: NOT YET RUN ON ANY DEVICE.** Nothing in this file has been executed on the Xiaomi Pad 6 or the iQOO Neo 9
Pro. Until a row is filled in from a real device, that behaviour counts as **unverified**, whatever the automated
tests say ([TEST_REPORT.md](TEST_REPORT.md) explains what they do and do not cover).

**How to use it:** do the sections in order on the **Pad 6** first (P = Pad 6), then repeat the starred (★) rows on
the **Neo 9 Pro** (N), then section M (sync). For each row write **PASS**, **FAIL** (plus what happened), or
**SKIP** (plus why). A screenshot of Diagnostics after each section helps.

**Enable diagnostics first:** ⋮ → Settings → Developer & diagnostics → **Developer diagnostics** ON and **Show
timings under replies** ON. ⋮ → **Diagnostics** then shows the log, measured timings and *Run self-tests on this
device*.

**Recording results:** copy the table rows into a note or reply with them. Failed rows become bug reports. Fixing
them comes before any new feature.

---

## A. Install and first launch

| ID | Steps | Expected | P | N |
|---|---|---|---|---|
| A1 ★ | Install `clu-release.apk` ([INSTALL.md](INSTALL.md) §1) | Installs without a signature or parse error | | |
| A2 ★ | Open Clu for the first time | Welcome screen within ~2 s; no crash | | |
| A3 ★ | Enter name, form of address, role (Home on the tablet / Portable on the phone) → Start | Chat screen opens; greeting uses your name or form of address | | |
| A4 ★ | Allow notifications when asked | No crash whichever choice you make | | |
| A5 ★ | Diagnostics → **Run self-tests on this device** (before a model is loaded) | ✓ Database, ✓ VAD, ✓ Wake-word, ✓ Speech recognition ("decoded 1 s"), ✓ or a clear message for Speech synthesis; Language model reports that none is loaded (not a crash) | | |
| A6 ★ | Diagnostics → Device & resources | Shows RAM, CPU cores and chosen threads (expected 4 on Pad 6, 5 on Neo 9 Pro) | | |
| A7 ★ | Force-stop Clu (Settings → Apps → Clu → Force stop), reopen | Opens normally; name and settings kept | | |

## B. Local model import and loading

Get a model with **one** of these:
* **In-app download (needs internet):** ⋮ → AI models → *Download 2.1 GB* next to Qwen2.5-3B-Instruct Q4_K_M.
  Clu asks for confirmation and shows the size first.
* **File import:** download `qwen2.5-3b-instruct-q4_k_m.gguf` in Chrome from
  `https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF` (Files and versions tab), then AI models →
  **Import GGUF file…**. Importing copies the file into Clu, so you can delete the download afterwards.

| ID | Steps | Expected | P | N |
|---|---|---|---|---|
| B1 ★ | Download the model, or import it as above | Progress shown; on completion the model appears in the list with name and size. A non-GGUF file is rejected with a message. | | |
| B2 ★ | Tap **Load** | Loads with a time shown. Record the **load time**: ____ s. No crash, no "out of memory". | | |
| B3 ★ | **Benchmark** | Record **first token**: ____ ms and **tokens/s**: ____. (Target, not a promise: ≥ 8 tok/s on Neo 9 Pro; lower expected on Pad 6.) | | |
| B4 ★ | Re-run self-tests | ✓ Language model with tokens and tok/s | | |
| B5 | Import a random non-model file (for example a photo) | Rejected with a clear message; app stays stable | | |
| B6 | (Optional) Download/import Qwen2.5-1.5B; compare benchmark | Record tok/s: ____ | | |

## C. Offline chat

Turn on **Airplane mode** (Wi-Fi off) for this section.

| ID | Steps | Expected | P | N |
|---|---|---|---|---|
| C1 ★ | Type "Hello, who are you?" | Streaming reply as Clu, in its persona; timing shown under the reply | | |
| C2 ★ | Ask 3 follow-ups that refer back ("what did I just ask?") | Uses conversation context; replies stay coherent | | |
| C3 ★ | Ask "What's the latest news?" | Says it is offline / cannot look it up. **Must not invent news.** | | |
| C4 ★ | Long question, then tap ■ while it is generating | Stops within about 1 s; partial reply kept | | |
| C5 | "Set a timer for 1 minute" | Hands off to the clock app (or says it can't), and reports honestly whether it was set | | |
| C6 | "Open calculator" | Calculator opens (or Clu explains the app policy) | | |
| C7 ★ | ☰ (top left) → Conversations: rename, search, archive and delete a conversation | Each works and survives an app restart | | |
| C8 ★ | 20-turn conversation | Record reply speed at turn 1 vs 20 (KV-cache reuse should keep later turns fast): ____ / ____ | | |

## D. Memory: persistence and manual insertion

| ID | Steps | Expected | P | N |
|---|---|---|---|---|
| D1 ★ | ⋮ → Memory & diary → **Insert memory**: title "Favourite tea", content "I like masala chai with less sugar", category Preferences → Save | "Saved."; appears in the list | | |
| D2 ★ | New conversation: "What tea do I like?" | Answers with masala chai and less sugar | | |
| D3 ★ | Force-stop Clu, reopen, ask again | Still remembered (persists across restarts) | | |
| D4 | Reboot the tablet, ask again | Still remembered | | |
| D5 | Insert memory with an event date and time, plus people tags; then search for it | Search finds it; date shown correctly | | |
| D6 | Insert memory → **Import text file** (a .txt) → "Keep summary only" | Confirmation shown before the original text is discarded; summary saved | | |
| D7 | In chat: "Remember that my sister's birthday is 12 March" | Saved directly, or offered as a **Memory suggestion** (⋮ → Memory suggestions) with Approve / Edit / Reject | | |
| D8 | Reject a suggestion; ask about it | Not used | | |
| D9 | Edit D1 (change to "green tea"), ask again | Uses the edited value | | |
| D10 | Delete D1, ask again | No longer claims the preference | | |
| D11 | Memory & diary → **Export (JSON)** to Downloads; delete one memory; **Import (JSON)** | Export file created; import restores the deleted memory without duplicating the others | | |

## E. Settings actually change behaviour (sample of the 179)

| ID | Setting (⋮ → Settings) | Check | P | N |
|---|---|---|---|---|
| E1 ★ | General → **Response length** = short, then long | Replies visibly shorter / longer | | |
| E2 | Persona: form of address (for example "Sir") | Used in the next reply | | |
| E3 | Voice → **Speak replies to typed messages** ON | Typed questions get spoken replies | | |
| E4 | Network → **Allow internet access** OFF, ask for the weather with Wi-Fi on | Refuses with an explanation; makes no web call | | |
| E5 | Developer → **Show timings under replies** OFF | Timings disappear | | |
| E6 | Diagnostics → **Snapshot now**; change a setting; Diagnostics → **Restore configuration** → pick the snapshot | Earlier value restored | | |
| E7 | Diagnostics → Export settings → change a setting → Import settings | Value restored after owner verification | | |
| E8 | People & security → set a PIN; then try a protected action (for example exporting private memories) | PIN prompt; wrong PIN refused; repeated wrong PINs → temporary lockout | | |

## F. Wake word

Grant the microphone permission. Quiet room, tablet about 1 m away. In the main screen, start listening with the
wake word enabled (Settings → Wake words & activation → *Wake word* ON).

| ID | Steps | Expected | P | N |
|---|---|---|---|---|
| F1 ★ | Say "**Clue**, what time is it?" | Acknowledgement tone/greeting, then an answer. Record success count out of 10 tries: ___/10 | | |
| F2 | Say "Buddy, …" and "Friend, …" (10 tries each) | ___/10, ___/10 | | |
| F3 ★ | False activations: talk normally / play a podcast or TV for 10 minutes without the wake word | Record the number of false wakes: ____ (target 0–1) | | |
| F4 | Say a sentence with "clue" in the middle ("I have no clue about that") ×5 | Should **not** activate (leading-word rule). ___/5 rejected | | |
| F5 | From 3 m away, and with a fan or TV on (noise-aware mode) | Record ___/10 at 3 m and ___/10 with noise | | |
| F6 | Settings → **Keyword spotter threshold** lower or higher; repeat F1/F3 | Lower = more sensitive / more false wakes. Note the best value: ____ | | |
| F7 | Clap activation ON → double clap in a quiet room | Activates. Also check that single knocks or door sounds do not. | | |
| F8 ★ | "**End of the line**" during a voice session | Says a closing line and stops listening | | |

## G. Speech recognition (ASR)

| ID | Steps | Expected | P | N |
|---|---|---|---|---|
| G1 ★ | Tap 🎙, say "Remind me to call mom at five" | Transcript appears live while speaking. Record words wrong out of total: ___/___ | | |
| G2 ★ | 5 everyday sentences (your accent, normal speed) | Record roughly how many words were wrong. Note any first word that keeps being lost. | | |
| G3 | End-of-speech: stop talking | Turn ends ~0.7 s after you stop (VAD); not cut off mid-sentence | | |
| G4 | Settings → Voice → **Speech recognition** = Android recognizer (if offered) and repeat G1 | Works or shows a clear message if unavailable on the device | | |
| G5 | Record *asr_finalize_ms* from Diagnostics → timings | ____ ms (target < 300) | | |

## H. Speech output (TTS)

| ID | Steps | Expected | P | N |
|---|---|---|---|---|
| H1 ★ | Voice → download **Clu voice – Ryan** (67 MB, after confirmation) → test | Voice installs (checksum verified) and speaks | | |
| H2 ★ | Ask a question by voice | Reply spoken in the Ryan voice with the metallic effect; speech starts at the first short phrase while the rest is still generating | | |
| H3 | Voice → pitch, speed, volume and metallic effect: change each one | Each change is audible | | |
| H4 | Switch the engine to the Android TTS voice | Speaks with the system voice | | |
| H5 ★ | Record **end of speech → first audio** from timings (*first_audio_ms*) over 5 turns | ____ ms median (goal ≈ 1–2 s with 3B; not yet measured) | | |

## I. Interruption (barge-in)

| ID | Steps | Expected | P | N |
|---|---|---|---|---|
| I1 ★ | While Clu is speaking a long answer, start talking (no wake word needed) | Speech stops within about a quarter of a second; Clu listens. Record *barge_in_detect_ms* and *interrupt_to_silence_ms* | | |
| I2 | Same, using the speaker at high volume | Clu must **not** interrupt itself from its own voice. If it does, note the volume. (Echo cancellation quality varies by device.) | | |
| I3 | Tap ■ while it speaks | Stops immediately | | |
| I4 | Settings → **Interrupt by speaking** OFF → repeat I1 | Speaking no longer interrupts | | |

## S. Streaming voice conversation (new in this build; see VOICE_STREAMING.md)

Before starting:
- Voice → download **Whisper small** (466 MB, confirmation shows the size; Wi-Fi) and **Hindi voice – Pratham** (67 MB).
- Settings → Voice → Speech recognition = Whisper, Spoken language = Detect.
- After each block, open Diagnostics → Performance and copy the p50/p90 values.

| ID | Steps | Expected | P | N |
|---|---|---|---|---|
| S1 ★ | Say "Set an alarm for seven tomorrow morning" | Correct transcript. Record *whisper_ms* ___ and *speech_end_to_first_audio_ms* ___ | | |
| S2 ★ | Say in Hindi "कल सुबह सात बजे का अलार्म लगा दो" | Transcript in Devanagari, mostly right (words wrong ___/___). Reply in Hindi spoken by the Pratham voice | | |
| S3 | Mixed: "कल की meeting के लिए मुझे नौ बजे याद दिलाना" | Understood. Note how "meeting" is written | | |
| S4 ★ | Ask "Explain how a solar panel works in detail" | Speech starts while the text is still appearing; no audible gaps between phrases; *audio_started_before_generation_end_ms* > 0 | | |
| S5 ★ | Ask a long question, then interrupt by talking as soon as the first words play | Silence almost at once; Clu listens; the next reply contains nothing from the old one | | |
| S6 | Same, interrupting in the middle of the reply | Same as S5 | | |
| S7 | Interrupt 5 times in a row, each time asking something new quickly | Only the latest reply is heard. Never two voices, never an old sentence | | |
| S8 | Tap ■ right after asking (while the first phrase is being synthesised) | Nothing is spoken afterwards | | |
| S9 | Remove the Whisper model and talk | Clu still works with the built-in English recogniser and the Voice screen says why | | |
| S10 ★ | Airplane mode, Wi-Fi and data off; repeat S1, S2 and S5 | All work offline | | |
| S11 | Loudspeaker at high volume, long reply, stay silent | Clu does **not** interrupt itself. Repeat with Speech output path = Assistant audio and note the difference | | |
| S12 | Connect wired or Bluetooth headphones during a conversation | Speech moves to the headphones; barge-in still works | | |
| S13 | 15-minute conversation | Record battery % used ___ and whether the device became hot (Diagnostics → thermal) | | |

## T. TTS engine selection and deep voice (see TTS_ENGINES.md)

| ID | Steps | Expected | P | N |
|---|---|---|---|---|
| T1 ★ | Fresh install. Voice → Speech output shows Piper / Joe "not installed" with a warning; ask something by voice | Clu stays silent and says why (toast plus Voice screen). It does **not** speak with another voice | | |
| T2 ★ | Download Joe (67 MB) and Pratham (67 MB) → Speak test | Deep male English voice, then Hindi. "Last phrase spoken by" names Joe, then Pratham | | |
| T3 ★ | Voice → Measure voice | English median pitch ___ Hz (expect ~90), Hindi ___ Hz (expect ~107); first phrase ___ ms | | |
| T4 | Install HayaiTTS (github.com/HayaiApp/HayaiTTS/releases), download a male English voice inside it. TTS engine… → HayaiTTS → Voice… → English voices | A list of installed voices with measured pitch, deepest first. Pick one and Speak test: that HayaiTTS voice speaks | | |
| T5 | Same, with HayaiTTS uninstalled | Warning "HayaiTTS is not installed"; Clu silent; selection unchanged | | |
| T6 | TTS engine… → KittenTTS (download 31 MB) → Measure voice | Speaks; record first phrase ___ ms. A Hindi phrase is spoken by Pratham (or not at all if Hindi voice = engine) | | |
| T7 ★ | With each engine in turn: Streaming test | Record first audio ___, max gap ___, interrupt→silence ___ ms | | |
| T8 | Restart the app | Engine and voices unchanged | | |

## U. Recognition, turn-taking and UI repair (this build) – run on the iQOO Neo 9 Pro

None of these have been run on a physical device yet. For each item, write the result and P/N.
- **Where results come from:** Voice screen → Listening & speech recognition → Speech recognition diagnostics. It shows the recognised text, engine/model, language, end-of-speech wait, recognition time and audio levels of every utterance, plus "Play what Clu heard".
- **Before you start:** Settings → Voice → Spoken language = English for U1–U2 and U4–U9; Hindi or Mixed for U3. Hindi and Mixed need the Whisper small download (466 MB).

| ID | Steps | Record / expected | P | N |
|---|---|---|---|---|
| U1 ★ | Tap the mic, say "Apple", wait for the reply. Repeat 5 times at normal volume, then 3 times quietly | Transcripts ___ (expect "apple" each time). If one is wrong, tap "Play what Clu heard" and note whether the start of the word is missing | | |
| U2 ★ | Say a full English sentence ("Remind me to buy milk tomorrow morning") once, without touching anything | Words appear live while you speak; one reply. Transcript ___ | | |
| U3 ★ | Spoken language = Hindi: "कल सुबह सात बजे का अलार्म लगा दो". Then Mixed: "कल की meeting के लिए मुझे नौ बजे याद दिलाना" | Transcripts ___ (Devanagari); recognition time ___ ms | | |
| U4 ★ | Say "Set an alarm for … (pause about half a second) … seven tomorrow" | One turn, not two. Repeat with a ~1 s pause: with the default 800 ms this may split; set End-of-speech pause = 1000 ms and repeat | | |
| U5 ★ | Ask a short question; from Diagnostics read "total after you stopped", and time until you hear the first word | End-of-speech wait ___ ms, recognition ___ ms; first audio ___ s (bench_* and speech_end_to_first_audio_ms in Diagnostics → Performance) | | |
| U6 ★ | While Clu speaks a long answer, start talking | Clu's audio stops at once; your new sentence is answered; nothing of the old answer plays later. interrupt_to_silence_ms ___ | | |
| U7 ★ | Have 5 turns in a row without touching the phone (Listening mode = Conversation) | No mic taps needed; the state says Listening / Thinking / Speaking each time. Silence for 30 s closes the mic | | |
| U8 | Repeat U6 on the loudspeaker at high volume (stay silent), with wired/Bluetooth headphones, and with a TV or fan on | Loudspeaker: Clu must not interrupt itself (false interruptions ___). Headphones: speech routes there. Noise: transcripts ___ | | |
| U9 ★ | Tap the message box | The keyboard opens directly under the composer; the mic and send stay visible; nothing is hidden behind the keyboard or the gesture bar | | |
| U10 ★ | Tap ☰ (bottom left), then Chats, Settings and Voice & speech | All reachable with the thumb; the header is not under the status bar or camera cutout | | |
| U11 ★ | Fresh install (or with Joe not downloaded) | The chat shows "Clu's voice isn't downloaded yet" with Download Joe (67.2 MB) and Choose voice. Nothing is spoken by a different voice | | |
| U12 ★ | Airplane mode: repeat U1, U2, U6, and U3 if Whisper is installed | All work offline | | |

## J. Background behaviour

Apply the battery settings in [INSTALL.md](INSTALL.md) §2 first (Autostart ON, Battery saver *No restrictions*).

| ID | Steps | Expected | P | N |
|---|---|---|---|---|
| J1 ★ | Settings → **Background listening** ON while Clu is open → go to the home screen | Persistent notification "listening"; Android's microphone indicator is visible | | |
| J2 ★ | Screen on, other app open: say "Clue, what's the time?" | Clu answers (spoken) | | |
| J3 | Screen off for 5 min, then the wake word | Record whether it responds: ____ | | |
| J4 | Leave it for 1 h in the background | Notification still there; service not killed (note if HyperOS kills it) | | |
| J5 | Swipe Clu away from Recents | Note whether listening continues | | |
| J6 | Turn Background listening OFF | Notification and mic indicator disappear | | |
| J7 | Proactive messages: leave Clu closed for several hours with proactive messages ON | At most the configured frequency; none during quiet hours | | |

## K. Battery and thermal

Record from Android's Settings → Battery (and touch or feel for heat). Use a charged device, unplugged, ~50 %
brightness.

| ID | Measurement | Result P | Result N |
|---|---|---|---|
| K1 ★ | Battery % used by **1 hour of background listening**, screen off | | |
| K2 ★ | Battery % used by **20 text turns** with the 3B model | | |
| K3 ★ | Battery % used by **20 voice turns** | | |
| K4 ★ | Heat after 20 consecutive turns: cool / warm / hot; did replies get slower or shorter (thermal guard)? | | |
| K5 | Clu's share in Settings → Battery after one day of normal use | | |
| K6 | Peak RAM: Diagnostics → Device & resources during generation (3B) | | |

## L. Stability

| ID | Steps | Expected | P | N |
|---|---|---|---|---|
| L1 ★ | Rotate the screen during generation and during voice | No crash; reply continues or is kept | | |
| L2 | Switch apps while generating | Continues or stops cleanly; no crash | | |
| L3 | Open many heavy apps while a model is loaded | Clu releases the model if memory is low; reloads when needed | | |
| L4 | Revoke the microphone permission in Android settings while listening | Stops cleanly with a message | | |

## M. Phone ↔ tablet sync (both devices)

Phone hotspot ON, tablet connected to it ([INSTALL.md](INSTALL.md) §4).

| ID | Steps | Expected | Result |
|---|---|---|---|
| M1 | Tablet: ⋮ → Phone & tablet | Server "off (listens once paired or while pairing is allowed)" before pairing (security fix S1) | |
| M2 | Tablet: **Allow pairing (2 min)** | Server "listening on port 47823"; This device shows an address | |
| M3 | Phone: **Pair with…** → tablet address | Both show the same 6-digit code → confirm on both → "Paired and synced." | |
| M4 | Wrong code test: pair again and reject on one side | Pairing fails; nothing synced | |
| M5 | Insert a memory on the phone → *Sync now* | Appears on the tablet | |
| M6 | Edit the same memory on both while disconnected, then sync | Conflict resolved per policy; no duplicate, no crash | |
| M7 | Change a shared setting (for example form of address) on the tablet → sync | Phone uses it. Device-only settings (role) do **not** sync. | |
| M8 | Phone with no model loaded, *Use the home device's model* = fallback → chat | Reply generated by the tablet's model (source badge shows the home device) | |
| M9 | Turn the hotspot off during a sync | Fails with a message; the next sync resumes without losing or duplicating data | |
| M10 | Wait for the automatic interval (default 5 min) with both apps open | Syncs on its own | |
| M11 | Record the time to sync after 50 new memories | ____ s | |

## N. Conversation modes: offline vs LiveKit (new in this build)

**N-A. Offline mode with networking disabled (do this first, on both devices).** Model and Clu voice pack must
already be installed.

| ID | Steps | Expected | P | N |
|---|---|---|---|---|
| NA1 ★ | ⋮ → Voice → Conversation mode shows **Offline**. Turn on **Airplane mode** (Wi-Fi and Bluetooth off). | "Next conversation: offline" | | |
| NA2 ★ | Force-stop Clu, reopen, tap ☰ → **+ New conversation** | Opens normally | | |
| NA3 ★ | Say the wake word, then "How are you doing today?" | Transcribed, answered by the local model (source: local), spoken in the Clu voice | | |
| NA4 ★ | Ask about a memory inserted earlier (D1) | Answer uses the memory | | |
| NA5 ★ | Interrupt a long answer by speaking | Stops (as in section I) | | |
| NA6 ★ | "End of the line" | Session ends | | |
| NA7 | Switch mode to **LiveKit** while still in airplane mode, say the wake word | Falls back: "continuing offline" (Voice screen), conversation works offline | | |

**N-B. LiveKit mode (optional; needs your own server, see server/README.md).** A computer on the same Wi-Fi or on the
phone's hotspot runs `livekit-server` and `clu_voice_server.py serve`.

| ID | Steps | Expected | Result |
|---|---|---|---|
| NB1 | `python clu_voice_server.py add-device phone` → Clu → Voice → **Set device key** (owner verification) → paste | "Device key saved (encrypted)" | |
| NB2 | LiveKit settings → Token endpoint `http://<PC-IP>:8089/token` (owner verification) | Saved | |
| NB3 | Voice → **Use LiveKit** → **Start LiveKit conversation** | Status: connecting → connected → "Voice agent ready"; record "connected in … ms": ____ | |
| NB4 | Say "How are you doing today?" | Voice screen shows "Hearing…" while you speak, then "Heard: …"; Clu answers in the server's voice; chat history shows the turn | |
| NB5 | Record end of speech → first audio (stopwatch or video): ____ ms (5 tries) | Host reference ≈1.3 s; phone not yet measured | |
| NB6 | Ask about a memory (D1) | Answer uses the memory (brain on the phone) | |
| NB7 | Talk over a long answer | Clu stops within about 1 s. Record ____ ms. Check it does **not** interrupt itself at normal speaker volume | |
| NB8 | "End of the line" | Farewell spoken, session ends, wake word works again (offline) | |
| NB9 | Wake word while in LiveKit mode | Tone, then the conversation continues through LiveKit | |
| NB10 | Stop the server (Ctrl+C) during a session | Clu reports the session ended; next activation falls back to offline | |
| NB11 | Revoke the key on the server (`revoke-device phone`), start again | "Token server rejected this device key"; falls back to offline | |
| NB12 | 10 min LiveKit conversation: battery %, warmth, RAM (Diagnostics) | Record: ____ % / ____ / ____ MB | |

---

## Results summary (fill in)

| Section | Pad 6 | Neo 9 Pro | Blocking issues |
|---|---|---|---|
| A First launch | | | |
| B Model | | | |
| C Offline chat | | | |
| D Memory | | | |
| E Settings | | | |
| F Wake word | | | |
| G ASR | | | |
| H TTS | | | |
| I Interruption | | | |
| J Background | | | |
| K Battery/thermal | | | |
| L Stability | | | |
| M Sync | | | |
| N Modes (offline / LiveKit) | | | |

**Acceptance rule:** v1 is accepted when sections A–E, G, H and M pass on both devices, and every FAIL in F, I, J, K
or L either passes after tuning or is written up as a known limitation that you have agreed to. No new features
until then.
