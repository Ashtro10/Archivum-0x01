# M8.5 — Conversation failure and latency repair

**Method:** your exact conversation was played into CLU's real voice path (own livekit-server, LiveKit Rust SDK,
Silero VAD, Kroko recognition, CluAgent, llama.cpp, Piper) by a second synthetic voice, with every turn traced from
the moment speech was detected to the last audio frame (`VoiceConversationRealTest`). It was measured before any change,
after the reasoning fix (intermediate) and after all fixes.

**Model used for testing:** Qwen3-0.6B, Q4_K_M (Unsloth GGUF, 397 MB, SHA-256 `ac2d9771…524a`, Apache-2.0), a real
*reasoning* model whose chat template thinks unless `enable_thinking` is false — the same family of behaviour as the
`<think>` output you saw. It was the only reasoning model reachable here (Hugging Face is blocked; the file came from
an npm data package whose per-part and whole-file SHA-256 matched the upstream file). Hardware: 4-vCPU x86-64 build
container, no GPU — **not** your Ryzen AI 7 350. Research was answered by a fake Wikipedia (no internet), so routing is
real but the facts are canned.

## 1. Root causes

### The 20-second (and longer) delays
Measured in the baseline trace (`docs/measurements/m85_before_qwen3.json`), not guessed:

| Cause | Evidence | Share |
|---|---|---|
| **Reasoning before every answer.** CLU formatted prompts with llama.cpp's legacy template function, which treats Qwen3's template as plain ChatML and never applies `enable_thinking=false`; nothing removed `<think>` output | 125–212 reasoning tokens per reply = 14–25 s of generation; then the reasoning was **spoken**: turns took 32–96 s to finish | dominant |
| **Model not loaded when voice starts** (the PC loads on demand) | first turn: 5.8 s load + 7.3 s cold prompt read | first turn |
| **A blocking LiveKit attribute update** at the start of each turn ("thinking" state, acknowledged by the server) | 5.0 s between `submitted` and the model request in one turn | intermittent |
| **Prompt reading at 2 threads.** The PC's automatic thread count is half the logical processors: 2 on this 4-core machine | ~100 tokens/s; ~1.5 s for a greeting (~140 new tokens), 4.8 s for research evidence | every turn |
| LiveKit / audio transport | STT final 45–100 ms after the endpoint; first audio 0.2–0.4 s after the first sentence | not a cause |

### The missing or irrelevant responses
* The reasoning consumed the reply: what was "answered" was the model thinking aloud ("Okay, the user said…").
* Research answers were rejected by CLU's own claim check because they began with `<think>`, so CLU fell back to quoting
  the source behind an apology ("I couldn't put that into my own words reliably…").
* Follow-ups went to the wrong place: "Can you search for him?" was not recognised as research ("him" was missing from the
  research-request words, and the previous turn had replaced the topic) — the model then searched *memories* for an
  invented "Ashutosh birthday".

### The `<think>` leakage
No reasoning handling existed anywhere — not in the prompt, not in the output path. Reasoning was streamed to the window,
spoken, stored in the conversation, and fed back to the model with the history (which it then imitated).

### The transcription errors
* **"Leman Russ" is never heard correctly by the recogniser:** 0 of 12 (4 voices × 3 utterances: "Lemon Russ/Ross/rust",
  `docs/measurements/m85_stt_utterances.json`). It is not a recogniser setting; the name isn't in its vocabulary.
* Nothing used the conversation to correct it: the topic was built from the *misheard* words, although the search had
  already found "Leman Russ".
* "I said Leman Russ" was treated as a new message, not a correction.
* An older corrector silently rewrote ordinary words toward the previous message: "Can you hear me?" became
  "Can you **Hello** me?" after "Hello, Clue." (found by the new regression test).
* Plain phrases ("Can you hear me?", "Answer me.", "No, I said talking.") were recognised exactly by every voice.

## 2. What changed

| Area | Change | Files |
|---|---|---|
| Trace | Every turn: VAD onset, estimated speech end, endpoint, STT final, submission, route, memory, **every model call** (prompt/reused/generated tokens, queue, prompt-read time, stop reason, hidden reasoning), first text, first visible text, first audio, done; recognised transcript and reply kept in memory | `VoiceAgent.kt`, `CluAgent.kt`, `RequestTrace.kt`, `VoiceService.kt`, `ApiServer.kt` (`GET /v1/requests`), shell (voice bar "Timings of the last turn", Status → "Recent requests") |
| Reasoning off | The model's template is read from the GGUF; Qwen3-style (`enable_thinking`) and R1-style templates get the closed empty think block the template itself emits for `enable_thinking=false` | `Reasoning.kt`, `LlamaEngine.kt`, `Models.kt` |
| Sanitiser | Streaming removal of `<think>`, `<thinking>`, `<analysis>`, `<reasoning>`, `<reflection>` (tags split across tokens; partial `<…` held back) at the engine, in the inference service, and in CluAgent for **every** engine — before UI, streaming, TTS and history; stored replies cleaned on read | `Reasoning.kt`, `LlamaEngine.kt`, `InferenceService.kt`, `CluAgent.kt`, `Conversations.kt` |
| Fail-safe | Reasoning past a budget (or never closed) is stopped and retried once with reasoning forced closed; if still nothing, a clear error ("kept reasoning and never got to an answer") — never silence | `LlamaEngine.kt`, `CluAgent.kt` |
| Warm model | A voice session loads the model and reads CLU's instructions into its cache in parallel with the speech engines; the model is kept loaded while the session is open; listening starts without waiting for it | `VoiceService.kt` |
| No blocking state updates | CLU's state changes at once; LiveKit is told in the background (latest state only) | `VoiceAgent.kt` |
| Threads | All but one core on machines without SMT (4 → 3), half the logical processors with SMT (16 → 8, unchanged for your PC) | `DesktopPlatform.kt` |
| Text first | Each complete sentence is shown when it is ready, not when its audio is | `VoiceAgent.kt` |
| Corrections | "I said X" / "No, I meant X" corrects the previous request; misheard names corrected from what the conversation knows (research results, recent names, Settings → Voice → *Names CLU should recognise*); multi-word names must match word by word with the same first sounds; unsure → "Did you mean …?"; typed text never rewritten; raw transcript always kept (`interpreted` is recorded next to it); research adopts the source's spelling of a single name | `SpeechCorrections.kt`, `CluAgent.kt`, `ConversationContext.kt`, `VoiceConfig.kt`, Settings → Voice |
| Follow-ups | "search for him/her" is a research request on the current subject | `ConversationContext.kt` |
| Stop | "Stop", "stop talking", "be quiet", "that's enough", "never mind", "cancel" stop audio and generation at once and start no new turn | `VoiceAgent.kt` |
| No apology | The source fallback says "From what I found: …" (still attributed) | `CluAgent.kt`, contract |
| Queue order | Requests start in submission order (a real race, found by the full check) | `InferenceService.kt` |

## 3. Before and after (Qwen3-0.6B, 4 vCPU)

All times are from the estimated end of the owner's words (the 700 ms end-of-speech pause is included).

| Line | Heard (raw) | Before: first audio / first text / turn done | After: first audio / first text / turn done | After: what CLU did |
|---|---|---|---|---|
| Hello, Clue. | "Hello, clue" / "Hello, hello" | **15.4 s** / 15.4 s / 43.9 s, spoke its reasoning | 4.8 s / 4.5 s / 8.4 s ¹ | greeted |
| Can you hear me? | exact | 3.9 s / 3.9 s / 31.8 s, spoke its reasoning | **2.8 s / 2.6 s / 3.9 s** | answered (one model call, no research, no memory search) |
| Who is Leman Russ? | "Who is Lemon Russ?" | 11.9 s / 11.9 s / 20.8 s, apology + quote | 10.0 s / 9.9 s / 15.9 s | researched and answered (as the found name) ² |
| I said Leman Russ. | "I said lemon rust" | 4.2 s / 4.2 s / 42.4 s, new message, spoke reasoning | 6.6 s / 6.3 s / 9.9 s | understood as the correction "Who is Leman Russ?", answered |
| Can you search for him? | exact | 6.3 s / 6.3 s / **95.8 s**, searched memories for an invented name | 6.7 s / 6.4 s / 10.1 s | researched Leman Russ, answered |
| Stop talking. (over a long answer) | — | stopped in 1.35 s | stopped in 1.33 s, no new turn | — |

Reasoning markers: present in text, speech and history before; **none** after (text stream, transcripts, stored history).

¹ Spoken while the model warm-up (load 4.7 s + instructions 5.3 s) was still running; the trace shows the wait. With the
warm-up done (intermediate run) the greeting's first audio was 3.45 s at 2 threads.
² Research turns read ~470 extra tokens of evidence (3.2–3.6 s here) and may run the claim check's second attempt.

### Against the targets
* **First audible response under 3 s for simple conversation:** met here for a warm model (2.8 s), on 4 vCPU.
* **First visible response under 2 s:** **not met here** (2.6 s). The breakdown of "Can you hear me?": 0.7 s end-of-speech
  pause, 0.05 s recognition, 0.04 s routing, 1.07 s prompt read (~180 new tokens at 3 threads), 0.7 s for the first
  sentence of tokens. This is a hardware/model limit of the container: with 8 threads on the Ryzen AI 7 350 prompt reading
  and generation should be roughly 2–3× faster (not measured). Lowering the end-of-speech pause (Settings → Voice) trades
  speed for being cut off mid-sentence.
* **No unexplained waits:** every turn's time is accounted for stage by stage in the trace.

## 4. Speech recognition of the test utterances
`VoiceRecognitionTest`, Kroko fed exactly as the agent feeds it, 4 synthetic voices (Ryan, Alan, Kitten, Joe):
"Answer me.", "Can you hear me?", "No, I said talking." → recognised exactly by every voice and never altered by
correction. "Leman Russ" → heard right 0/12, **understood right 12/12** once the name is known from the conversation
(or listed under *Names CLU should recognise*). The first mention of an unknown name cannot be fixed this way; research
then finds the right spelling and CLU uses it from then on.

## 5. Tests added
`ReasoningTest` (8: tags split at every position, other tag names, partial `<`, prompt-opened blocks, stray/unclosed
tags, template detection and prompt switch, stored text, output guard) · `SpeechCorrectionsTest` (4) ·
`ConversationRepairTest` (5: no leakage with a reasoning model, never-ending reasoning, fast greeting = one model call
with no research, the acceptance conversation through CluAgent, misheard names and typed text) · `VoiceRecognitionTest` ·
`VoiceTurnRulesTest` (stop phrases; model warm on session start) · `VoiceConversationTest` (+ one turn per utterance,
full trace) · `VoiceConversationRealTest` (opt-in, real model; the measurements above). Existing tests for barge-in,
TTS interruption, queue ordering, offline voice and wake words still pass.

## 6. Full regression run
`./gradlew check` with the Playwright suites: core **623 tests, 0 failures** (13 opt-in skipped), app **74, 0 failures**,
Android API-level and ICU regex checks pass; desktop **108 tests, 1 failure**: `ShellUiTest` (M3–M7 window test) timed out
on a memory-link search under full-suite load and **passes when run alone** — a timing flake in an existing test, not
in the changed code. The full check also exposed, and this milestone fixed, a **JVM crash** that already existed in M8:
the voice detector was freed while the audio thread could still be inside it (SIGSEGV in
`SherpaOnnxVoiceActivityDetectorAcceptWaveform`); it is now freed on the audio thread after its last frame and refuses
calls once closed. Two tests needed their expectations updated for intended behaviour: the request trace now has
`ROUTE`, `MEMORY` and `LLM_CALL` events, and a misheard name known from the conversation is corrected *before*
searching (one search instead of two).

## 7. Remaining limitations
* **Not run on Windows, your PC or a phone.** All numbers are from the 4-vCPU container.
* **A 0.6B model is weak:** it sometimes repeats itself or misreads a greeting ("Hello, clue" answered with
  "Hello Clue, what is your name?"). Larger models (3B) answer better and read prompts slower; no model was switched.
* The fixed instructions are ~830 tokens; every turn re-reads only what changed (~140–180 tokens), but research turns add
  ~450 tokens of evidence.
* The first mention of an unusual name may still be misheard; CLU corrects it once the conversation (or your list) knows it.
* The recogniser has no custom vocabulary of its own; correction happens after recognition.
* The phone app's on-device engine (used only without the PC) has the same reasoning filter through core, but its own
  voice path was not re-measured.
