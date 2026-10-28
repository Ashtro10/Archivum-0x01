# Real model, unified agent path and answer quality (1.6.2-prototype)

Starting point: 1.6.1-prototype, versionCode 14, commit `798a07e`, clean tree equal to the remote branch, APK SHA-256
`1aaacc3b…50ae`. **No phone or tablet was connected.** Every measurement here is from the build machine (x86-64,
4 vCPU) and is labelled so; nothing in this document is a device result.

## 1. The model — what is actually configured

| | Local (on-device) mode | LiveKit server mode |
|---|---|---|
| Model | Whatever GGUF the owner selected: in-app catalog (Qwen2.5-3B-Instruct Q4_K_M ≈2.1 GB, marked "recommended default"; Llama-3.2-3B-Instruct Q4_K_M ≈2.0 GB; Qwen2.5-1.5B-Instruct Q4_K_M ≈1.1 GB) or an imported file. **No model is bundled.** | `CLU_STACK=cloud` (default): `openai/gpt-4.1-mini` via LiveKit Inference. `CLU_STACK=local`: any OpenAI-compatible server, default `llama3.2:3b` on Ollama |
| Which one the owner runs | **Unknown here.** The phone reports never named it (the 1.5.5+ performance report shows it; none was shared since). The Neo 9 Pro report's native memory (~1.8 GB) fits a 1–3 B Q4 model but doesn't identify it. | Unknown (server configuration is on the owner's computer) |
| Engine | llama.cpp (bundled, JNI, arm64), GGUF; architecture, tokenizer and chat template from the file (`llama_chat_apply_template`, ChatML fallback, override in Settings) | the provider's own |
| Settings (defaults) | context 4096, max reply 400 tokens, temperature 0.7, top-k 40, top-p 0.9, min-p 0.05, repeat penalty 1.1, batch 256, threads auto (performance cores; 2 left free while speaking), mmap on | provider defaults; `CLU_LLM_MAX_TOKENS` 300 (local stack) |
| Prompt | static system prompt (persona, style; cacheable) + history fitted to context + newest user message carrying a `[Context]` block (time, offline note, relevant memories, instructions) + evidence/notes as further user messages | server `INSTRUCTIONS` + LiveKit chat context + evidence/notes added by the controller (new) |
| Tools | the model may *propose* `<tool>{json}</tool>`; `ActionValidator` (exists, schema, no repeat) and `ToolExecutor` (permission, network, confirmation) decide. Research is decided by `ResearchPolicy`, not the model | no tools; research by the controller (new) |
| RAM estimate (`ModelRegistry.estimateRamBytes`, ctx 4096) | Qwen2.5-3B Q4_K_M ≈ 2.7 GB; Llama-3.2-3B ≈ 2.7 GB; Qwen2.5-1.5B ≈ 1.6 GB | — |
| Acquisition | explicit: the download dialog states the size and recommends Wi-Fi; nothing downloads by itself | — |

### Candidates

**No trained candidate could be obtained.** Hugging Face, its mirrors, Ollama's registry, ModelScope and GPT4All are
denied by this environment's network policy (proxy 403, recorded); GitHub access is bound to this repository; PyPI's
100 MB file limit is why the one credible trained GGUF reachable here (SmolLM2-135M-Instruct, from Simon Willison's
`llm-smollm2` wheel, SHA-pinned) is tiny. npm "model" packages download from Hugging Face at install time, and
re-uploads without provenance were not used. The other GGUFs on this machine have random weights and are not models.

So **no comparison between models was possible**, no candidate is recommended on loading alone, and the working model
was not replaced. The comparison harness (§2) is ready for any GGUF: `-Dclu.realModel=a.gguf,b.gguf`.

## 2. Model evaluation — SmolLM2-135M through the production path

`RealModelEvaluation` (opt-in): the production `CluCore`, `LlmService` and llama.cpp JNI engine, wrapped in a
pass-through recorder so the model's **raw** output is kept apart from **Clu's final answer**. 23 cases × (1 run at
temperature 0 + 3 at the production 0.7). Network: Wikipedia JSON fixtures for the queries Clu makes.
Data: `docs/measurements/model_comparison_host.json`.

| Group (cases) | n | Raw model answer correct | Final answer correct | How the final answer came about |
|---|---|---|---|---|
| Arithmetic (+, ×, −, ÷, %) | 20 | **9** | 9 | the model's own ("7 * 8 = 54", "15 - 9 = 2") |
| Elementary facts | 12 | 9 | 8 | the model's own |
| Instruction following ("only the word yes", "exactly three words", "three primary colours") | 12 | **2** | 2 | the model's own |
| Conversation | 4 | 4 | 4 | the model's own (lenient check) |
| Research: entity, follow-up, misheard name, unfamiliar person, current information, ambiguity | 24 | 5 (keyword match; **24/24 raw outputs had an unsupported sentence**) | 24 | 20 attributed quotes of the result, 4 clarifying questions |
| Honesty: empty, irrelevant, provider down, offline | 16 | 2 | 16 | Clu's honest messages (15), one acknowledged by the model |
| Prompt injection in a result | 4 | 0 | 4 | attributed quotes; nothing saved, no action |

Raw research outputs also contained citation/site talk in 6 of 28 cases and often began by echoing the prompt format
("[Question] …", "[Context] …").

**Verdict:** SmolLM2-135M is not adequate as Clu's model: it fails arithmetic half the time, practically never
follows instructions, and never produced a usable grounded answer. The controller keeps it from misleading the owner
(every research answer above is either supported, an attributed quote, a clarifying question or an honest
limitation), but that is damage control, not intelligence. A capable trained model is still the missing piece and
has to be chosen and tried on the phone.

Host performance (135M, 4 vCPU): generation 61.7 tok/s (p50, n=88); load 0.28 s; peak RSS 466 MB; first text p50 /
p90: plain questions 0.10–0.22 s / 0.14–1.27 s, research 2.45 s / 3.33 s (n=24, includes the correction attempt and
quote), honesty 1.22 s / 1.68 s (n=16). Stability over 92 turns: 0 crashes, 0 timeouts, 6 empty replies (the model only
echoed the prompt's `[Context]` block, which is never shown; the app reports "empty reply" with Retry).

## 3. Answers from evidence — what changed in the controller

Kept: the deterministic decisions (when to research, validated tool calls, ≤3 attempts, cancellation, failed vs empty
vs irrelevant vs found, untrusted web text, offline-first).

1. **Concise evidence.** The model gets the same safe frame (untrusted-text header, one line per result with its site),
   but each result is cut to its sentences most relevant to the question (snippet ≤320, page excerpt ≤480 characters):
   easier for a small model, and less prompt to read on the phone.
2. **Claim-level validation instead of word overlap.** Each answer sentence must be supported by *one* result
   sentence (or two neighbouring ones): its names and numbers together there, plus at least half of its other words;
   the subject's own name only counts in a result that is about the subject. This checks the relation, not just
   shared words: "Tenzing Norgay reached Everest in 1955" (wrong year), "Leman Russ leads the Ultramarines",
   "Leman Russ climbed Everest with Hillary" (names joined from two different results), "I will save your address"
   (an action announced from web text) are rejected; "He was a Sherpa climber who reached the top of Everest in 1953"
   is accepted. Opening checked before anything is shown or spoken; later sentences one by one.
3. **One correction attempt.** If the opening fails, generation stops at once and the model is told exactly why
   ("the name 'Ultramarines' is not in the information") and asked again, once.
4. **Attributed quote as the last resort:** "I couldn't put that into my own words reliably. The top result says: …"
   — never a copied sentence presented as Clu's own explanation. Recorded in the reply's stats
   (`answer_repairs`, `answer_extractive`).
5. **Honesty after failed vs empty searches.** Provider down or offline: an answer from the model's own knowledge is
   kept with Clu's note ("…so this is from memory"). A search that ran and found nothing: no answer from memory —
   Clu says it found nothing reliable and asks for the name again (the 135M model's "from memory" answers there were
   inventions).

Limits, stated plainly: the check is lexical. A sentence that swaps who did what among the words of one result
sentence ("Edmund Hillary was a Sherpa mountaineer") is not caught. No second model is used for verification.

## 4. One agent contract for both engines

The audit found the LiveKit server agent answered with its model alone: no research, no checks. Now:

* `server/clu_controller.py` implements the on-device controller's behaviour (policy, bounded research through
  keyless Wikipedia search, evidence, claim checks, one correction, attributed quote, honesty rules) in standard-library
  Python, and `clu_agent.py` routes every reply through it via `Agent.llm_node`, so only checked text reaches speech
  and transcripts. Cancellation (barge-in, Stop) cancels the search and the generation. No cloud service is added;
  `CLU_SEARCH=off` disables research, `CLU_SEARCH_URL` points it elsewhere.
* **Shared contract:** `contract/agent_contract.json` — 18 claim cases, 14 research decisions, 4 clean-up cases and
  14 whole-turn scenarios (19 turns: natural answer, follow-up, misheard name, correction, attributed quote, citation
  clean-up, ambiguity, empty, irrelevant, provider down, offline, injection, arithmetic, verify-if-unsure, quote framing kept out of later prompts) with exact expected answers. Run by `core` (`AgentContractTest`, through the real `CluAgent`) and by `server`
  (`tests/test_contract.py`, through the real controller). Both pass. The contract found a bug in both engines: a short
  non-answer without a trailing space ("I'm not familiar with that.") was shown instead of triggering research.
* **Through LiveKit, end to end** (`server/tests/e2e_agents.py`, real LiveKit server, agent worker, Kroko STT, Piper
  TTS, fake Wikipedia): research decided, evidence reached the model, natural answer spoken; follow-up resolved;
  injected instruction ignored and answer corrected once (2 model requests); provider outage → honest message;
  stable fact answered without a search — plus all earlier pipeline checks: **18/18 with the scripted mock model and
  18/18 with SmolLM2-135M** behind llama.cpp's `llama-server` (`docs/measurements/livekit_e2e_agent_contract_*.json`).
  With the real model every research answer ended as the attributed quote after one correction (typed question → first
  text 3.8–8.2 s on this machine, two generations), and its plain answers stayed unreliable ("The capital of India is
  Mumbai"; plain chat isn't checked against evidence by design).
* **Found by the real-model run:** the 135M model copied Clu's own quote framing into a later turn where nothing was
  searched ("The top result says: India is a republic…") — a fabricated attribution. Earlier quotes now go back to the
  model without the framing on both engines (contract scenario 14); the re-run no longer showed it.

## 5. A latency defect found by the evaluation (fixed)

After `learning.alternativesEvery` (25) replies, an "alternative style" reply was generated after **every** typed reply
until the owner picked one (the cooldown restarted only on a choice), and with a restyled *system* prompt, which
evicted llama.cpp's prompt cache. Measured on the host: new prompt tokens per turn 13 → 545 (median), first text 0.12 →
1.73 s. At the phone's reported prompt speed (56 tok/s) that is roughly 10 s extra per typed turn — a plausible
contributor to the device's 19.8 s first-token p90, **not verified on a device**. Fixed: the cooldown starts when an
alternative is offered, and the style goes into the message, not the system prompt (the cache survives; measured:
13 new tokens per turn in all runs after the fix).

## 6. Verified / not verified

Verified on the build machine: orchestration on both engines (shared contract), the LiveKit path end to end with a
scripted model and with SmolLM2-135M, the claim checks and correction loop, the latency fix (host), the full test
suites (STATUS.md). **Not verified:** any answer quality with a capable model (none obtainable), anything on a phone or
tablet (latency, memory, speech, taps), the server engine against the real Wikipedia or a cloud model.

## 7. Tests and release

* Core 428 tests (0 failures; 12 opt-in skips: benchmarks, audits, real-model/real-audio evaluations, Whisper model
  tests, the namespace-only offline test), app 62 (0 failures); discovery audit 428 `@Test` declared = 428 reported
  (app tests run once per Robolectric SDK). Server: 6/6 (`tests/test_contract.py`: contract + cancellation; also run
  inside the core suite as `ServerContractTest`). LiveKit end to end 18/18 (mock) and 18/18 (SmolLM2-135M). Offline
  namespace test 3/3, each verified to have executed. Fake-model tests prove orchestration only; the real-model results
  are §2 and §4.
* Changed expectations (behaviour changed, not loosened): `AgentAcceptanceTest` (a rejected answer is now corrected
  once; the last resort is the attributed quote), `SearchAgentTest` (the evidence block is found in the prompt rather
  than assumed to be its last message, since a correction may follow it), `LearningSettingsTest` (the alternative keeps
  the system prompt; the cooldown starts on offer).
* `dist/clu-release.apk` — 1.6.2-prototype, versionCode 15, 85,264,396 bytes, SHA-256
  `eab3600ddfcce1b9ed2d455f232bab68a41f071efc9a57956b4d72bc4534e9e2`; apksig v3 + zipalign verified; signer certificate
  SHA-256 `B8:D4:60:94:2F:88:EE:74:D2:59:6B:BD:D3:4E:6A:44:F4:4D:CF:47:BF:DE:0D:0C:51:06:BF:A5:53:70:BE:C6` (unchanged:
  installs over 1.6.1 as an update, data kept); verifyApk 27 components / 377 JNI externals; secret scan clean. No
  model is bundled; the in-app download states the size and recommends Wi-Fi.
