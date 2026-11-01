# Talking to CLU (voice setup)

CLU's voice runs entirely on your PC: its own LiveKit server, speech recognition, the local language model and speech
synthesis. Nothing you say is recorded or sent to the internet. The phone is a thin client of the PC: the PC listens,
thinks and speaks for it, with the same voice and the same memory.

## On the PC

Nothing to set up. The installer puts everything in place: the LiveKit server, the speech engines, the English
recogniser and four voices. The optional *Speech recognition for other languages (Whisper base)* component adds Hindi,
German, French, Spanish and automatic language detection.

1. Open CLU → **Chat** → **🎙 Talk**. The first time, the window asks for the microphone: allow it.
2. The bar under the conversation shows what CLU is doing: *Getting ready…* (engines loading, a second or two the first
   time) → *Listening* → *Thinking…* → *Speaking*.
3. Just talk. Your words appear live; CLU's reply appears as text and is spoken. **Talk over CLU to interrupt it**,
   or press **Stop**. **Mute** closes the microphone; **End** ends the voice session (the conversation stays).
4. Spoken and typed turns are the same conversation: type a follow-up and CLU knows what you said.

While CLU can hear you, a red **● Microphone on** indicator is shown in the bar.

### Settings → Voice

| Setting | What it does |
|---|---|
| Voice | Pick one of the voices. **Preview** synthesises the sentence above the grid with the real engine. Voices that are not installed show their size, source and licence; **Download…** asks first, resumes after an interruption, checks the file's SHA-256 and installs it without selecting it. The choice applies to the PC and the phone. |
| Speed, Volume | Speech rate (0.7–1.4) and loudness (0.2–1.5). There is no pitch control: the engines cannot change pitch without distortion, so choose a deeper or lighter voice instead. |
| Language | English is recognised live while you speak. Other languages (with the optional Whisper component) are transcribed after you finish. |
| Listening | **Conversation** (default; just talk), **Hold to talk** (hold the Talk button or the space bar), **After a wake word** (say “Clue”, “Buddy” or “Friend” first; CLU sends nothing to the model until it hears one, and goes back to sleep after a farewell or 30 s of quiet), **Press to talk** (press Talk to open the microphone, press again when done). |
| End of speech after | How long a pause ends your turn (default 700 ms; raise it if CLU cuts you off mid-sentence). |
| Interrupt CLU after | How much continuous speech interrupts CLU while it speaks (default 400 ms). |
| Wake words, strictness | Your wake words and how closely the recognised word must match (0.72 default; higher = fewer false wakes). |
| Farewells | Phrases that end the conversation (“goodbye”, “that's all”…). |
| Pronunciations | `written = spoken` pairs applied before speech (e.g. `CLU = Clue`). |
| Names CLU should recognise | Names the speech recogniser gets wrong (e.g. "Leman Russ" is heard as "Lemon Russ"). CLU also learns names from the conversation and from what it looked up; a close match is corrected (the raw transcript is kept), an uncertain one is asked about. Saying "I said …" corrects your previous request. |
| Microphone, Speaker | Device choice for this window (remembered on this PC). |
| Phone access | Lets paired phones reach CLU over your private network. Off by default. |

## On the phone

1. On the PC: **Settings → Voice** → tick **Let my paired phone talk to CLU…** → **Save**.
   Windows may ask whether *livekit-server* and *CLU (Java)* may communicate on **private networks**: allow private
   networks only (not public).
2. **Pair a phone…** → name it. CLU shows an **endpoint** (e.g. `http://192.168.1.20:47822/token`) and a **key**. The key
   is shown only once.
3. In the CLU app on the phone: ☰ → **Connection** → **CLU on my PC** → **Token endpoint…** (enter the endpoint) →
   **Device key…** (enter the key) → **Test connection**. It should say *Connected – Clu's agent is listening*.
4. Talk from the voice screen as before. The phone must be on the same Wi-Fi as the PC (or the PC on the phone's hotspot).

To stop a phone: **Settings → Voice → Phones → Remove**. Its key stops working at once. Turning phone access off closes
the endpoint and keeps CLU's LiveKit server on 127.0.0.1 only.

When the PC cannot be reached, the phone can still use its own on-device engine (☰ → Connection → *This device*).

## When something does not work

| Symptom | What to check |
|---|---|
| *The voice libraries are not installed* / *speech models are not installed* | Reinstall CLU (the voice files are part of the program). |
| Talk button does nothing / microphone error | Windows Settings → Privacy → Microphone → allow desktop apps; pick the right device in Settings → Voice. |
| CLU cuts you off | Raise *End of speech after* to 900–1200 ms. |
| Slow replies, or "thinking" text | Status → *Recent requests* (or the voice bar's *Timings of the last turn*) shows where the time goes: model loading, prompt reading, generation, speech. Reasoning models (Qwen3, DeepSeek-R1 distills) have their reasoning switched off and filtered. |
| "Stop" | Saying "stop", "stop talking", "be quiet" or "never mind" stops CLU at once without starting a new answer. |
| CLU interrupts itself (hears its own voice) | Use headphones, or lower the speaker volume; the window uses the browser's echo cancellation. Raise *Interrupt CLU after*. |
| Phone: *Token server rejected this device key (HTTP 401)* | The key was mistyped or the phone was removed: pair again. |
| Phone: *not reachable* | Same network? Phone access on? Windows firewall allowed *private* networks? The endpoint address changes if the PC's address changes: check Settings → Voice. |
| Phone connects but CLU never joins | Look at Settings → Voice (status line) and `%LOCALAPPDATA%\CLU\voice\livekit.log`. |

Measured latencies and resource use are in [convergence/M8_REALTIME_VOICE.md](convergence/M8_REALTIME_VOICE.md).
