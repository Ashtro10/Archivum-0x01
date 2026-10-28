# Final Stage 3 — agent intelligence, automatic research, model flexibility, natural voice (1.6.0-prototype)

Built on 1.5.5 (`d67cf84`, which is the requested starting point `20f564b` plus the Stage 3.4 prompt-cache fix).
**Everything here is verified on the build machine only** (JVM tests, real llama.cpp through the JNI bridge with
random-weight test models, a fake network). Nothing in this document is a phone measurement.

## 1. What Clu actually did (traced in source, before changes)

| Responsibility | Component (before) | Finding |
|---|---|---|
| Understanding the request | The local LLM, with a system prompt (`PromptBuilder`) and per-message context | works as designed |
| Deciding to research | `SearchTrigger` (regexes) at agent step 5 | **only** time-sensitive topics ("latest", prices, weather, news) and explicit "look it up". "Who is Leman Russ?" never searched unless the small model emitted a perfectly formatted `<tool>` call on its own |
| Selecting/executing tools | `IntentRouter` (deterministic commands), the model's `<tool>{json}</tool>` text parsed by `ToolCallParser`, run by `ToolExecutor` (permissions, network, confirmation) | model calls were run without argument validation; an identical call could be repeated |
| Recovering from a misheard name / failed action | prompt text only ("words may be misheard") | no retry with another spelling; a search that **ran and found nothing** was reported as "I couldn't search the web just now (… returned no results)" |
| Generating the answer | the LLM, instructed to "say so and name the source", cite `[1]`, and in voice "mention the sites by name" | **source narration was requested by Clu's own prompts** |
| Speech chunking and pauses | `AdaptiveChunker` → `SpeechText.withPause` → TTS → `AudioTrackSink` | first chunk cut at the first comma after 3 words, later chunks at commas after 60 chars; pauses were only ever *padded*, never trimmed (see §5) |

Failures by origin: research decision (orchestration), empty-vs-failed confusion (tool result handling), source
narration (prompt construction), comma pauses (chunker + pause shaping). None of these was the model's fault.

## 2. Agent architecture (now)

`core/.../agent/AgentController.kt` + the loop in `CluAgent` — ordinary code, a bounded state machine:

```
DECIDE   ResearchPolicy: RESEARCH | VERIFY_IF_UNSURE | NONE   (from sentence structure, not names; microseconds)
ACT      typed AgentAction: Research(query) | UseTool(name, args) | Answer | Clarify(question) | Rejected(reason)
         – the model may only *propose* an action (<tool>…</tool>); ActionValidator checks tool exists, schema (keys and
           types from the tool's argument example), no exact repeat; ToolExecutor still checks permission/network/confirmation
OBSERVE  the tool's real result: Found | NothingFound | Failed | Ambiguous | Cancelled; results must mention the subject
RETRY    only when the search ran and found nothing relevant: the provider's spelling suggestion, the bare subject, or a
         similar name already used in this conversation — never the same query twice, at most 3 attempts; failures to
         run (offline, provider error) are not retried
STOP     Clarify (one question, e.g. "Do you mean Mercury (planet), Mercury (element), or Freddie Mercury?"),
         capability unavailable (offline → answer from knowledge, saying so), cancelled (no further work)
ANSWER   the model writes one reply from the verified outcome ("[Information found] … start with the direct answer …
         no websites, sources, links, citation numbers or 'according to' … confirmed only when two sites agree …")
```

- **RESEARCH**: current information (as before); "who is/was X", "tell me about X", "what is <named thing>"; follow-ups
  with a pronoun about the last researched subject ("which legion did he lead?" → "Leman Russ legion lead").
- **VERIFY_IF_UNSURE**: other factual questions are answered from knowledge; the first sentence is held back, and if it
  is a non-answer ("I'm not familiar with…") it is never shown or spoken — the controller researches instead.
- **NONE**: conversation, opinions, creative and personal requests, commands, arithmetic and stable facts, questions
  about Clu or the owner.
- Untrusted web text can't trigger actions (existing taint rule kept); private words can't leave in model-written
  queries (existing `SearchPrivacy`); URL validation, pinned fetching and robots rules unchanged.

## 3. Automatic research — evidence

`AgentAcceptanceTest` (default keyless provider = Wikipedia + DuckDuckGo behind a fake network; a deterministic model
that answers only from the evidence it receives):

- "Who is Leman Russ?" → one Wikipedia query `Leman Russ` (not "who is…"), without the word "search" → the retrieved
  snippet is in the model's input → answer: *"Leman Russ is the Primarch of the Space Wolves, one of the Space Marine
  Legions in Warhammer 40,000."* — the fake model's own "According to Wikipedia, … [1]" is removed; the source stays
  on the message card. The same path answers Ada Lovelace, the Horus Heresy and (by voice, lower-case) Tenzing Norgay.
- Misheard: "who is lemon rush" → nothing found + Wikipedia suggestion `leman russ` → second query → answer names
  Leman Russ. "who is lehman ross" after Leman Russ was discussed → the similar name from the conversation is tried.
- Not hard-coded: the policy test covers many phrasings; nothing in the code mentions any subject.

## 4. The language model — decision

**Keep the current model as the default; it is replaceable today, and now safely so.**

- Format and backend: GGUF through the bundled llama.cpp (2026-10-23 build, 165 architectures incl. Qwen2/2.5/3,
  Llama 3.x, Gemma 3/4, Phi-3, SmolLM3, LFM2). Path, context, threads, batch, sampling: `Settings` (Models section) →
  `LlmService.configFor`; tokenizer and chat template come from the GGUF itself (`llama_chat_apply_template`, ChatML
  fallback, override in Settings). The agent depends only on the `LlmEngine`/`InferenceRouter` interfaces, so no new
  provider layer was needed (adding one would only rename the existing one).
- Shown, not assumed (`ModelProviderTest`, real JNI): a Qwen2-tokenizer/ChatML model and a Llama-3-tokenizer (128,256
  tokens)/Llama-3-template model load through the same path and serve the same agent; memories, conversation and
  settings survive the switch. New: a newly selected model that can't be loaded (missing, damaged, incompatible) falls
  back to the last model that generated on this device, and the selection follows. This also fixed a crash-path:
  a model file truncated after import threw an uncaught `EOFException` instead of being reported as damaged.
- Is the model the limit? For tool selection, no longer critically: research decisions, retries, validation and
  stopping are application code. Language understanding and phrasing still depend on it.
- Upgrade candidates (compatible with this build; **not evaluated here** — Hugging Face is blocked from this build
  machine, so no trained model could be downloaded and tested): Qwen2.5-3B-Instruct Q4_K_M (≈2.1 GB, already the
  in-app catalog default), Llama-3.2-3B-Instruct Q4_K_M (≈2.0 GB, in the catalog). Requirements for any alternative:
  GGUF v3, an architecture in the list above, an embedded chat template (or a matching override), Q4_K_M or Q4_0
  quantisation, ≤ ~2.5 GB file for the Neo 9 Pro's RAM budget with a 4096-token context (~0.5 GB KV cache), an
  instruct/chat fine-tune. Larger models were not added: no measured justification on the phones yet.

## 5. Speech pacing

Cause: every chunk is synthesised as a separate utterance, and voices add their own silence around each one (Kokoro
~190 ms after a comma *and* after a full stop, plus a lead-in). The chunker cut at commas early and often, and the pause
code only padded short gaps. Result: a comma between chunks ≈ 250–320 ms (more if the next chunk wasn't ready yet), a
full stop ≈ 360–420 ms — nearly the same.

Fix: `SpeechText.withPause` now sets each boundary exactly — lead-in trimmed to 40 ms, trailing silence trimmed *or*
padded so comma ≈ 170 ms, full stop ≈ 360 ms, question ≈ 380 ms, paragraph ≈ 650 ms; only samples below 2 % of the
chunk's peak are ever removed. The chunker cuts the first chunk at a comma only after 5+ words (else at the sentence end
or after 10 words), later chunks at a comma only beyond 110 characters, so most commas stay inside a chunk with the
voice's natural short pause. Source talk ("[2]", "according to Wikipedia") is also removed before synthesis.
Tests: `SpeechPacingTest` (commas, full stops, questions, abbreviations, decimals, initials, short/long sentences,
paragraphs, multi-chunk streaming through the real pipeline, stop/barge-in), `AdaptiveChunkerTest`, `SpeechTextTest`.

## 6. What the tests prove — and don't

They prove the orchestration: decisions, actions, evidence flow, retries, honesty about failure, no source talk, no
actions from web text, cancellation, offline behaviour, pacing math and chunk integrity. They do **not** prove answer
quality of a real trained model (none available here), phone latency, or how the voice sounds on the device.

## 7. Verification (build machine)

* `./gradlew test check :app:assembleRelease -Pclu.verify=release --rerun-tasks`: core **407** tests, 0 failures, 10
  skipped (opt-in benchmarks/audits, model-dependent Whisper tests, the namespace-only offline test); app **62**, 0
  failures. Discovery audit: every declared `@Test` ran or was reported skipped.
* New suites: `AgentAcceptanceTest` (18 end-to-end scenarios), `SpeechPacingTest` (6), `ModelProviderTest` (3, real JNI).
* Offline namespace test (`scripts/test_offline.sh`): 3/3 passed, including the first run straight after the full build
  (the condition of the earlier intermittent failure; its cause is still unidentified, and the test now reports the
  helper's audio length/peak if it recurs).
* APK: `dist/clu-release.apk`, 1.6.0-prototype (versionCode 13), 85,248,012 bytes, SHA-256
  `bbbf679f72c5e64059c0807142004882ddad2bb19d35ca453bbad083f529e51e`, apksig v3 + zipalign verified, signer certificate
  SHA-256 `B8:D4:60:94:…:BE:C6` (unchanged; installs as an update), verifyApk 27 components / 377 JNI externals, secret
  scan clean.
