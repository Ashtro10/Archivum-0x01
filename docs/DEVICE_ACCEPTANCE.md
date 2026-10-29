# Device acceptance checklist — Clu 1.7.0-prototype

**Status: not yet run on any device.** Everything automated was tested on a build computer only. Until you tick a
check below on a real device, that behaviour is *unverified*.

## Before you start

* **Devices:** do everything on the **Xiaomi Pad 6** first. Then repeat the checks marked ★ on the **iQOO Neo 9 Pro**.
  Checks 13–14 need both devices.
* **Opening the menu:** tap **☰** at the top of the chat screen. "Settings" and "Diagnostics" below are reached from there.
* **Turn on measurements once:** ☰ → Settings → **All settings (advanced)** → *Developer & diagnostics* → **Developer diagnostics: On**.
* **How to record each check:** write ✅ (worked), ❌ (didn't), or ➖ (skipped, and why).
* **The three things to collect when something fails.** You never need a computer, a cable or a command line.
  1. **A screenshot** of the screen showing the problem.
  2. **The performance report:** ☰ → Settings → Diagnostics → **Share performance report**. It is also copied to the clipboard, so you can paste it.
  3. **If Clu crashed:** reopen Clu. A crash screen appears with **Copy** / **Share**. Send that text.

  None of these contain your messages, memories, diary or keys.

## The checks

| # | What to do | Success looks like | If it fails, collect |
|---|---|---|---|
| 1a ★ | **(1.6.0) Clu researches on its own.** Online, voice chat (⬡): ask "Who is Leman Russ?" (don't say "search"). Then ask "Which legion did he lead?" | A direct, natural answer (e.g. "Leman Russ is the Primarch of the Space Wolves…") without reading out websites, links or "[1]", and nothing invented. Since 1.6.2, if the model's answer doesn't match what was found, Clu asks it once more and, failing that, says "I couldn't put that into my own words reliably. The top result says: …" — note how often you hear that (it means the model is too weak). The follow-up answers about Leman Russ. Since 1.7.0 the sources are one collapsed line under the answer ("Sources (n) ▸"); tapping it lists them. | What Clu said; performance report; **the model's name** (☰ → Settings → AI models). |
| 1b ★ | **(1.6.0) Misheard names.** Voice: say a name the way it might be misheard (e.g. "Who is Lehman Ross?" right after 1a). | Clu answers about the intended person by name, or asks you to repeat/spell it — never an unrelated refusal. | What Clu heard (shown on screen) and said. |
| 1c | **(1.6.0) No needless searching.** Ask "How are you?", "What is two plus two?", "Write a two-line poem about rain". | Normal answers; no "Searching the web…" status. | Which one searched. |
| 1d | **(1.6.0) Offline honesty.** Airplane mode, ask "Who is Leman Russ?" | Clu either says "I'm offline right now, so I can't look that up." or starts with "I'm offline, so this is from memory and not checked." before answering; it never claims it looked anything up. | What Clu said. |
| 1e ★ | **(1.6.0) Pauses.** Ask for something with several sentences and commas, e.g. "Explain how a bicycle gear works in a few sentences." Listen. | Short pauses at commas, clearly longer ones at full stops; no long gaps in the middle of a sentence; no words skipped or repeated. | Which sentence sounded wrong (screen recording with sound if possible). |
| 1f ★ | **(1.7.0) Follow-ups keep the subject.** ☰ → Settings → General → **Home location**: enter your city (e.g. "Pune"). Voice: ask "Which cyclone is heading towards us?", then "Search the web and tell me", "Is it dangerous?", "When will it arrive?" | The first answer names a current storm from GDACS (or says GDACS lists none / none within about 1,500 km), with its distance and direction from your city and "(GDACS, … updated … UTC.)". The follow-ups talk about that storm: alert level for "dangerous", and for "when" Clu says it has no forecast track and names the official agency (e.g. IMD) — it never invents a track, a landfall time or a warning. No encyclopedia answer. | What Clu said for each; performance report. |
| 1g | **(1.7.0) No location yet.** Clear Home location, ask "Is a cyclone heading towards us?" then answer with your city. | Clu asks once which city or area, then answers for the city you named. | What Clu asked and said. |
| 1h ★ | **(1.7.0) "Look it up" researches the question.** Ask "Who is the current CEO of Microsoft?", then "Look it up." and then "Tell me more." | "Look it up" searches the question you asked (not the words "look it up"); "Tell me more" adds something new or says it has nothing more — it doesn't repeat itself. | What Clu said. |
| 1i ★ | **(1.7.0) A misheard word.** After 1f, say "Is the convoy dangerous?" (or let speech recognition mishear "cyclone"). | Clu answers about the cyclone; long-pressing your message shows what was heard. For a name in a request that does something ("send …", "call …") Clu asks "Do you mean …?" instead of guessing. | What Clu heard and said. |
| 1j | **(1.7.0) Sources collapsed.** After any researched answer, look under it, then tap "Sources (n) ▸". | No links, numbers or retrieval dates in the answer; the sources appear only after tapping; tapping one opens the browser. | Screenshot. |
| 0u ★ | **(1.5.5) Speed after interruptions.** Neo 9 Pro. ☰ → Settings → Diagnostics → **Clear measurements**. Voice chat (⬡): ~15 short exchanges; interrupt Clu 3 times (speak over it) and press Stop once; after each interruption ask one more short question. Then **Share performance report**. | The answer right after an interruption comes about as fast as the others. In the report's *Recent voice turns*, the turn after an interruption shows `steady` (not a "new" count close to the whole prompt). | The performance report (always). |
| 0v ★ | **(1.5.4) Voice speed, fresh numbers.** Neo 9 Pro first. ☰ → Settings → Diagnostics → **Clear measurements**. Then: open ⬡ voice chat; have **one conversation of at least 15 exchanges** (short questions, wait for each answer); include 3 interruptions (speak over Clu early in an answer, mid-answer, and between two sentences); press Stop once; leave Clu for a minute (home screen) and come back for 3 more exchanges. Then **Share performance report**. | The report lists `endpoint_to_first_audio_ms`, `first_tts_to_first_audio_ms`, `turn_first_token_ms`, `prompt_new_tokens`, `prefill_tok_per_s`, `tokens_per_s`. Clu's answers start sooner and play without long pauses compared with 1.5.3. | The performance report (always send it, good or bad); which question felt slow. |
| 0w | **(1.5.4) Interruptions still work, and only you interrupt.** During 0v: let at least two full answers play without speaking. | Clu never stops itself in the middle of its own answer; your interruptions stop it within about a second. | Which answer stopped by itself; performance report. |
| 0a ★ | **(1.5.3) Settings buttons respond.** ☰ → Settings. Tap each entry **on its title text**: Connection, Conversation, Memory & diary, Search & internet, Phone & tablet, **Diagnostics**. Go back after each. | Every entry opens its screen on the first tap, and you see a brief touch highlight. | Which entry didn't open, and where on it you tapped. |
| 0b ★ | **(1.5.3) Voice screen.** Tap ⬡. Look at the hexagon; tap the 🔊, 🎤 and ⌨ buttons. | Two glowing blue angular eyes inside the hexagon, **no text inside it**. The state word appears below the hexagon. Each button responds. | Screenshot of the voice screen. |
| 0c | **(1.5.3) Eyes follow Clu's state.** Speak to Clu (🎤) and let it answer. | Listening: the eyes brighten with your voice. Thinking: light runs along the lines beside the eyes. Speaking: the eyes pulse. Airplane mode in server mode: eyes go dark. | Screenshot of the state that looked wrong. |
| 1 ★ | **Upgrade.** Install the new APK over the one you have (don't uninstall). Open Clu. | Android says "Update" (not "Install"); Clu opens normally. | Screenshot of the Android message. Crash text if it crashed. |
| 2 ★ | **Your data is still there.** Open ☰ → Memory, then ☰ → Diary, then ☰ → Conversations. | The same memories, diary entries and conversations as before, nothing doubled. | Screenshots of the screen that looks wrong; how many items you expected vs. see. |
| 3 ★ | **Typed chat offline.** Turn on airplane mode. Type "Explain photosynthesis in two sentences." | A reply appears; the label under it says on-device. No error. | Screenshot; performance report. |
| 4 ★ | **Voice chat offline.** Still offline: tap the **⬡** button at the top (voice chat), ask "What's two plus two?" | Clu hears you, answers aloud, and the hexagon shows listening → thinking → speaking. | Screenshot of the voice screen; performance report; what you said and what Clu heard. |
| 5 ★ | **Interrupt and Stop.** Ask for something long ("Tell me a long story"). While Clu speaks, start talking. Then ask again and press **Stop** while it speaks. | Clu stops speaking at once both times. After Stop, the next question works normally. | Performance report (it records interruption and stop times); a note of how long it kept talking. |
| 6 ★ | **Microphone permission.** Android Settings → Apps → Clu → Permissions → Microphone → **Don't allow**. Return to Clu, tap voice. Then allow it again and return. | First: a clear message that the microphone is needed (no crash). After allowing: voice works without restarting Clu. | Screenshot of what Clu showed; crash text if any. |
| 7 ★ | **Background and return.** Mid-conversation, press Home, wait a minute, reopen Clu. Then swipe Clu away from recent apps and open it again. | The conversation is still there; no message appears twice; nothing is stuck on "Thinking…"; the next message works. | Screenshot; performance report. |
| 8 | **Live search.** Turn airplane mode off. Ask "Who is the current CEO of Microsoft?" | "Searching the web…", then an answer with the 🔎 label and numbered source cards. Each card says "Page read" or "Snippet only (why)" and when it was retrieved. Tapping a card opens the page. | Screenshot of the answer and cards; the question you asked. |
| 9 | **Search problems.** (a) Ask a research question and press **Stop** while it says "Searching the web…". (b) Turn airplane mode on and ask "What's the latest Android version?" | (a) It stops immediately, no answer or sources appear afterwards, and the next question works. (b) Clu says it couldn't search and why, then gives an answer marked as possibly outdated. | Screenshots; performance report. |
| 10 ★ | **Memory.** Say or type "Remember that my favourite tea is masala chai." Close and reopen Clu, ask "What's my favourite tea?" Then in ☰ → Memory edit that memory to "green tea", ask again, then delete it and ask again. | Remembered after reopening; the edited version is used; after deleting, Clu no longer knows. | Screenshot of the Memory screen and the reply at the step that went wrong. |
| 11 ★ | **Diary.** ☰ → Diary → New entry: write something, save. Search for a word in it. Edit it, then delete it. Check ☰ → Memory. | Each step works offline; the entry never appears in Memory unless you tap "Keep as memory". | Screenshot of the step that went wrong. |
| 12 | **A multi-step request.** "Set a timer for 1 minute, then open the calculator." | A short plan with ✓ or ✗ for each step that matches what actually happened (the timer really runs). | Screenshot of the plan reply. |
| 13 | **Pair and sync** (both devices on the same Wi-Fi). On both: ☰ → Settings → **Phone & tablet**. Tablet: **Allow pairing**. Phone: **Pair with…** → choose the tablet. Check that the 6-digit codes match and confirm. Add a memory on the phone, tap **Sync now**. | "Paired and synced"; the new memory appears on the tablet. Editing it on the tablet and syncing updates the phone. | Screenshot of the Phone & tablet screen on both devices (the "Last:" line shows the error). |
| 14 | **Sync file (no Wi-Fi needed).** Phone: Phone & tablet → **Export sync file**, choose a passphrase, save it. Move the file to the tablet (any way). Tablet: **Import sync file**, enter the passphrase. Import the same file again. Then try once with a wrong passphrase. | First import: "N changes applied". Second: "0 changes applied". Wrong passphrase: a clear refusal, nothing changed. | Screenshot of each message. |
| 15 ★ | **Performance report.** Do the cold-start and return-from-background steps in the table below, then ☰ → Settings → Diagnostics → **Share performance report** and send it. | The report shows the app version, device, and numbers in ms. Anything not tried yet says "not measured yet". | The report itself. |

### Measurements to fill in (from the performance report)

| Measurement | How to produce it | In the report as | Pad 6 | Neo 9 Pro |
|---|---|---|---|---|
| Cold start | Swipe Clu away, open it | Cold start (app_create_ms) | | |
| Return from background | Home, wait 1 minute, reopen | Return from background (resume_to_frame_ms) | | |
| End of speech → first audio | Check 4 | End of speech → first audio | | |
| Stop → silence | Check 5 | Stop/interruption → playback silenced | | |
| Memory use | Right after check 8 | "Memory now: PSS …" | | |
| Battery | 20 minutes of use, then Android Settings → Battery | (not measured by Clu) | | |

**When you're done:** send the filled tables, the performance reports and any screenshots. Every ❌ becomes a bug to fix
before anything new is built.

---

# Appendix — detailed technical checks (optional)

The sections below are the earlier, more detailed test lists, kept for reference.

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

## X. Clu server engine, voice HUD and chat (1.3.0) – iQOO Neo 9 Pro first, then Xiaomi Pad 6

None of these have run on a device. Setup: [LIVEKIT_AGENT.md](LIVEKIT_AGENT.md) §3. Note which stack the server
runs (`cloud` or `local`) next to every timing. Agent-measured timings appear in Connection ("Last reply started …")
and in Diagnostics → Performance as `lk_speech_end_to_first_audio_ms`, `lk_llm_ttft_ms` and `lk_tts_ttfb_ms`.

| ID | Steps | Record / expected | P | N |
|---|---|---|---|---|
| X1 ★ | Install over 1.2.0 | Installs as an update; chats kept | | |
| X2 ★ | Settings → Connection → *Clu server*, token endpoint, device key, **Test connection** | "Connected – Clu's agent is listening" within a few seconds. Record time: ___ s | | |
| X3 ★ | Chat: type "What's the capital of France?" with 🔊 off (text → text) | "Thinking…" appears, then the reply streams in. No audio | | |
| X4 ★ | Same with 🔊 on (text → spoken) | Reply text and speech. Record send → first audio (stopwatch/video): ___ ms | | |
| X5 ★ | Chat: tap 🎤, ask a question, 🔊 off (voice → text) | Your words appear as the message-box hint while you speak; the reply streams in as text only | | |
| X6 ★ | Tap ⬡ (voice chat), ask "How are you doing today?" (voice → spoken) | HUD: LISTENING (teal, ring follows your voice) → THINKING → SPEAKING (pulses with Clu's audio) → LISTENING. Record end of speech → first audio, 5 tries: ___ ms | | |
| X7 ★ | Follow-up: "And what about tomorrow?" after a question about today | Answer uses the earlier context | | |
| X8 ★ | Talk over a long answer | Clu stops quickly (record ___ ms) and answers the new question. The interrupted reply is marked *interrupted* in the chat | | |
| X9 ★ | Tap ■ while Clu is speaking or thinking | Stops at once; HUD returns to LISTENING | | |
| X10 ★ | Loudspeaker at high volume, stay silent during a long answer | Clu does **not** interrupt itself (echo cancellation). Count false stops: ___ | | |
| X11 ★ | Mute (mic button) in voice chat | HUD shows MIC OFF and no animation; nothing is transcribed | | |
| X12 ★ | Airplane mode during a conversation, wait 10 s, then turn it off | HUD shows OFFLINE/CONNECTING with a red marker and no fake activity; it reconnects by itself and the conversation continues. Record recovery time: ___ s | | |
| X13 ★ | Stop `clu_agent.py` on the server while connected | Within about 15 s: "Clu's agent hasn't joined…" message; HUD WAITING. Restart the agent: Clu becomes ready without restarting the app | | |
| X14 ★ | Stop the token server / wrong device key | Clear error in Connection and on the screens; no reply from the on-device model | | |
| X15 ★ | Rotate the phone in chat; open and close the keyboard | Composer stays above the keyboard; send and mic remain visible and tappable | | |
| X16 ★ | ⬡ → ⌨ → ⬡ several times | Switches without losing the conversation; leaving voice chat turns the microphone off | | |
| X17 | Switch Connection to *This device*, airplane mode, chat and voice | On-device engine works offline as in section N-A | | |
| X18 | 10 min voice conversation: battery %, warmth, data used (Android settings → Data usage) | ___ % / ___ / ___ MB | | |
| X19 ★ | (1.3.1) Settings → Connection → Diagnostics while connected | Shows engine, connection, agent state, the server's providers (stack, stt, llm, tts, turn) and timestamped events (`token`, `connect`, `agent_ready` in ms) | | |
| X20 ★ | Diagnostics → **Copy**, paste into a note | Report has app version, device, endpoint host, events and latencies; **no** conversation text, device key or token | | |
| X21 ★ | Stop the model server (e.g. Ollama) on the computer, type a question | Within seconds: "Clu's language model failed on the server: …" (no endless "Thinking…"). Restart it: the next question is answered (after 3 failed questions the agent restarts by itself) | | |
| X22 ★ | Press Home during a chat, wait 70 s, check Diagnostics on return | Event `idle_disconnect` after 60 s (no agent session kept open in the background); reconnects on return | | |
| X23 ★ | Leave voice chat and come back within a few seconds, then type at once | The first message is answered (no silent drop; Diagnostics shows `typed_resent` only if the agent missed it) | | |

## W. Voice-first agent (this build) – iQOO Neo 9 Pro first, then Xiaomi Pad 6

None of these have been run on a physical device yet. Background: [VOICE_AND_AGENT.md](VOICE_AND_AGENT.md). Timings come from Diagnostics → Requests and Performance (`speech_end_to_transcript_ms`, `speech_end_to_first_token_ms`, `speech_end_to_first_audio_ms`, `interrupt_to_silence_ms`, `reply_end_to_listening_ms`).

| ID | Steps | Record / expected | P | N |
|---|---|---|---|---|
| W1 ★ | Type 3 questions in a row | Each answers; no "…" left; ■ returns to ➤ | | |
| W2 ★ | Repeat W1 five more times quickly after each answer | No stuck Thinking; Requests shows `generation completed` each time | | |
| W3 ★ | Tap the mic and hold a 5-turn spoken conversation without touching the phone | Listening → Thinking → Speaking each time; mic never needs a tap. `reply_end_to_listening_ms` ___ | | |
| W4 ★ | While Clu speaks a long answer, say "stop, tell me something else" | Audio stops at once (`interrupt_to_silence_ms` ___), the new request is answered; nothing of the old answer plays later. Repeat on loudspeaker at high volume while silent: Clu must not interrupt itself (false interruptions ___) | | |
| W5 ★ | Tap the mic and immediately say "Apple"; repeat 5× | Transcript starts with the first syllable each time (Speech diagnostics) | | |
| W6 ★ | Indian-English speaker: 10 everyday requests (names, places, numbers) | Transcripts correct ___/10; when a place is misheard, Clu either understands from context or asks one short question | | |
| W7 ★ | Ask for "three tips for sleeping better, with explanations" by voice | Clear pauses between sentences and items; no "asterisk", "hash", URLs or list numbers spoken | | |
| W8 ★ | Ask by voice: "The meeting is at 9:30 am on 2026-11-03, costs ₹1,500 and is 12 km away" (ask Clu to repeat it) | Spoken as "nine thirty A M", "3 November 2026", "1500 rupees", "12 kilometres"; final word never cut off | | |
| W9 ★ | Settings → Voice: ▶ Preview Joe and Lewis; choose Lewis without downloading | Previews play before download. Chat shows "Clu's voice isn't downloaded yet" with a Download Lewis button; no other voice speaks. Download → Lewis speaks. Voice → Advanced → Measure voice: RTF ___ (Lewis is only usable if RTF < 0.8 while the model runs) | | |
| W10 ★ | Airplane mode: type and speak 3 questions each | All answered locally; voice works | | |
| W11 ★ | Online: "search for today's news about ISRO" | "Searching the web…" shows; answer names its source; no invented results. Offline: Clu says it can't search | | |
| W12 ★ | Online: "What is the distance between Manhattan and Queens, how long will the trip take, and which route should I take?" Then "how far is Springfield from Chicago" | First: km, minutes, main roads, an alternative, and "no live traffic". Second: asks which Springfield. "Directions to Phoenix Mall by train" opens Google Maps transit directions | | |
| W13 ★ | Install this APK over the previous one (do not uninstall) | Chats, settings, chosen voice and downloaded models/voices remain; nothing re-downloads | | |
| W14 ★ | 60 min of mixed voice conversation on battery, screen on | Battery used ___ %, phone temperature ___, any slowdown (tokens_per_s at start ___ / end ___), crashes ___. Repeat on Pad 6 | | |

## V. Reply reliability and speed (this build) – run on the iQOO Neo 9 Pro, then the Xiaomi Pad 6

None of these have been run on a physical device yet. Background and measurements: [LLM_RELIABILITY.md](LLM_RELIABILITY.md).
- **Where results come from:** ☰ → Diagnostics → **Requests** (one line per message: model ready, first token, total, and how it ended) and **Performance** (`turn_first_token_ms`, `turn_total_ms`, `prompt_ms`, `tokens_per_s`, `model_load_ms`). With a PC: `adb logcat -s CluTurn` shows the same events live (no message text is logged).
- **Before you start:** AI models → note the model, quantisation and "loaded in … ms". Keep the screen on.

| ID | Steps | Record / expected | P | N |
|---|---|---|---|---|
| V1 ★ | Restart the app, wait 10 s, send "Hello" | Words start appearing within ___ s; Requests shows `generation completed`, first token ___ ms (warm-up after load: Diagnostics log line "warm-up: N instruction tokens") | | |
| V2 ★ | Send 5 messages in a row, each after the previous answer finished | Each answers; first token ___ / ___ / ___ / ___ / ___ ms (expect turns 2–5 at most about as fast as turn 1, not slower) | | |
| V3 ★ | Ask for a long answer ("Explain how a car engine works in detail"), tap ■ after a few words | Words stop within ~1 s; the partial answer stays; Requests shows `request cancelled`; the next message answers normally | | |
| V4 ★ | Ask a long question by typing; while it is answering, open the voice screen and close it with ✕ (or tap the mic and stop) | The typed answer keeps streaming and completes (Requests: `generation completed`). Before this build it was cancelled | | |
| V5 ★ | Ask something long, rotate the phone / switch dark mode / press Home and come back during the answer | The answer keeps streaming and completes; after it finishes no "…" bubble or ■ button remains | | |
| V6 | Tap Send twice quickly | One question, one answer | | |
| V7 | In voice mode ask a question, then say something while Clu answers (barge-in) | Old answer stops, new one is answered; no "response cancelled" left over | | |
| V8 | AI models → Benchmark | Prompt ___ tokens in ___ ms, ___ tok/s, first token ___ ms. Native backend line shows the CPU features (NEON/dotprod); this build has no GPU/NPU backend | | |
| V9 | Update test: install this APK over the previous Clu (do not uninstall) | Installs as an update; chats, settings, PIN and downloaded models are all still there; the model is not downloaded again | | |
| V10 | AI models → Remove nothing; check "Installed models" status after V9 | Status "Loaded and generated successfully" (or "Header valid") – not "File missing" | | |

## Y. Stage 1: search, memory, diary, personality, Android actions (1.4.0) – Xiaomi Pad 6

Background: [STAGE1_SEARCH_MEMORY_DIARY.md](STAGE1_SEARCH_MEMORY_DIARY.md). Use the on-device engine (Connection → this device). The first real web search ever made by Clu happens here: none could be made from the build machine.

| # | Step | Expected | P |
|---|---|---|---|
| Y1 | Airplane mode on. Chat: "Explain photosynthesis briefly." | Normal offline answer, badge "On-device model", no search attempted | |
| Y2 | Online, default provider. "Who is the CEO of Microsoft?" | "Searching the web…", answer with "🔎 Searched the web · N sources" and source cards; tapping a card opens the right page | |
| Y3 | "Tell me a story about a fox." / "How are you?" | No search | |
| Y4 | Airplane mode on. "What's the latest news about ISRO today?" | "I couldn't search the web just now (…)"; next ordinary message works | |
| Y5 | Settings → Search & internet → Brave, enter key, "What's the latest news about ISRO today?" | Current results with sources (or a clear key/quota error) | |
| Y6 | Voice: ask a search question, then interrupt by speaking | Voice stays responsive; "Let me look that up" spoken; no URLs or [1] read aloud | |
| Y7 | "Remember that I prefer tea over coffee." Force-stop the app, reopen. "What do I prefer to drink?" | Remembered after restart; Memory shows it as Preference | |
| Y8 | "From now on, use metric units." then "How tall is Everest?" | Answer in metres; Memory shows an Instruction; deleting it stops the behaviour | |
| Y9 | "I always forget to drink water when I'm working." | Suggestion card in chat; nothing in Memory until Save | |
| Y10 | Memory → Insert memory (paste a text), edit it, delete it | Each step reflected; deleted text gone | |
| Y11 | Airplane mode on. Menu → Diary: new entry, edit date, search a word, delete | All work offline; entries grouped by day | |
| Y12 | "Save this conversation to my diary"; long-press a message → Save to diary | "Added to your diary."; entries appear; Memory count unchanged | |
| Y13 | Diary entry → Keep as memory | Appears in Memory with the chosen category | |
| Y14 | "Be more concise" then ask a question; Settings → Conversation | Confirmation names the setting; Response length shows Brief; replies shorter | |
| Y15 | "Open Wi-Fi settings", "open battery settings", "turn on Bluetooth", "open Spotify" (installed / not installed) | Correct screen opens; toggle request explains Android's limit; app result is truthful | |
| Y16 | Settings → Search & internet → Search automatically: "Only when I say" ; repeat Y2 | No search unless asked | |

## Z. Stage 2: research, contextual memory, phone ↔ tablet, tasks (1.5.0) – Pad 6 and Neo 9 Pro

Background: [STAGE2_RESEARCH_MEMORY_SYNC.md](STAGE2_RESEARCH_MEMORY_SYNC.md). Install 1.5.0 **over 1.4.0** first (Z1) so the upgrade and migration are tested.

| # | Step | Expected | P | N |
|---|---|---|---|---|
| Z1 | Install 1.5.0 over 1.4.0 with some diary entries and memories | App opens; diary entries and memories intact, none duplicated | | |
| Z2 | Airplane mode: type "Explain photosynthesis briefly", then a voice question, then interrupt Clu mid-reply | Offline text and voice answers; interruption stops speech at once | | |
| Z3 | Online: "Compare Pixel 9 and iPhone 16 for photography" | "Searching the web…"; answer leads with a conclusion; source cards from several sites, each showing "Page read" or "Snippet only (reason)" and the retrieval time; tapping opens the page | | |
| Z4 | "Check whether coffee stunts growth" | Searched; answer separates what sources say from "My assessment" | | |
| Z5 | Airplane mode: "What's the latest Android version?" | Says it couldn't search, then answers marked as possibly outdated; badge "Search unavailable · answered without the web"; next message works | | |
| Z6 | "Remember that I prefer tea." … later "Remember that I prefer black coffee now." then "What should I drink in the morning?" | Mentions the newer preference or asks which is current — doesn't silently mix them | | |
| Z7 | Memory: add a Project "Thesis: chapter 4 in progress" and a Decision "Thesis: use survey data". New chat: "Let's continue with the thesis" | Reply uses both without you repeating them | | |
| Z8 | Diary entry → Keep as memory | Editable proposal shown; nothing saved on Cancel; saved text = edited text | | |
| Z9 | "Set a timer for 2 minutes, then open Spotify and explain what a P-trap is" | Plan with ✓ timer (timer really set), ✓/✗ Spotify matching reality, explanation below | | |
| Z10 | "Set an alarm for 6:00, then set a timer for 1 minute", decline the confirmation; then "continue the task" and confirm | First time: alarm "not confirmed", timer not started; after continue: both done | | |
| Z11 | Start a 3-step plan and press Stop during step 1 | Remaining steps "–", "Stopped" line; nothing else happens | | |
| Z12 | Settings → Phone & tablet on both devices: pair (same Wi-Fi), Sync now | Memories and shared settings appear on the other device | | |
| Z13 | Diary entry on the phone, Sync now (Sync diary OFF) — then turn Sync diary ON on both and sync again | Not on the tablet the first time; appears after enabling | | |
| Z14 | Edit the same memory on both devices while one is offline; reconnect and sync | The later edit wins on both (or a conflict copy with "Keep both"); no duplicates | | |
| Z15 | Without network between devices: Export sync file (memories only) on the phone, move it, Import on the tablet; import again | Memories arrive; second import "0 changes applied"; wrong passphrase refused | | |
| Z16 | Background the app for 10 min, reopen; rotate the tablet | No crash; chat/HUD state correct; keyboard behaves | | |
| Z17 | (1.5.1) Ask a research question, press Stop while "Searching the web…" shows | Stops at once; reply marked interrupted; next message works | | |
| Z18 | (1.5.1) Upgrade 1.5.0 → 1.5.1 | Installs as an update; data intact (no migration in 1.5.1) | | |
| Z19 | (1.5.1) Turn "Sync memories" off, save a memory, sync; turn it on, sync | The memory reaches the other device after turning it on | | |
| Z20 | Deny the microphone permission, try voice; then allow it in Android settings and return | Clear message when denied; voice works after allowing, without restarting | | |
| Z21 | Swipe Clu away from recents during a conversation; reopen | Conversation and history are back; no duplicate messages | | |

## P. Performance measurements (1.5.1) — record for each device

Developer diagnostics ON (Settings → All settings → Developer & diagnostics). After each step, open ⋮ → Diagnostics →
Performance and note the value (median of 5 tries). *Copy report* exports everything.

| Measure | How | Diagnostics value | Pad 6 | Neo 9 Pro |
|---|---|---|---|---|
| Cold start | Force-stop Clu, open it | `app_create_ms` | | |
| Return from background | Home, wait 1 min, reopen Clu | `resume_to_frame_ms` | | |
| End of speech → first audio | Voice: ask a short question offline | `endpoint_to_first_audio_ms` | | |
| Interruption detected | Speak over Clu's answer | `barge_in_detect_ms` | | |
| Stop → silence | Press Stop while Clu speaks | `stop_to_silence_ms` | | |
| First token | Typed question with the model loaded | `turn_first_token_ms` | | |
| Memory during conversation | After 10 exchanges | PSS / Java heap line at the top | | |
| Memory during research | Right after a researched answer | PSS / Java heap | | |
| Battery | 20 min of mixed voice/text use; Android battery settings | % used by Clu | | |
| Sync | Sync now with 100+ memories | "Last: … (N ms)" on Phone & tablet | | |


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

**N-B (removed in 1.3.0).** The old LiveKit mode with automatic offline fallback no longer exists. Test the Clu
server engine in section X.

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
| Y Stage 1 (search, memory, diary) | | | |
| Z Stage 2 (research, memory, sync, tasks) | | | |
| P Performance (1.5.1) | | | |

**Acceptance rule:** v1 is accepted when sections A–E, G, H and M pass on both devices, and every FAIL in F, I, J, K
or L either passes after tuning or is written up as a known limitation that you have agreed to. No new features
until then.
