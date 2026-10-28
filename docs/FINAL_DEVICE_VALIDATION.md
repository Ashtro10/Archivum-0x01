# Final device validation pass — real model, grounding, pacing, timing (1.6.1-prototype)

Starting point: 1.6.0-prototype, versionCode 13, commit `6160e52` (clean tree, equal to the remote branch; APK SHA-256
`bbbf679f…e51e`, 85,248,012 bytes). **No phone or tablet was connected to this build machine.** Every number below
is from the build machine (x86-64, 4 vCPU) unless it is explicitly labelled as the earlier device report.

## 1. Live request path (audited in source)

| Path | Goes through the agent controller? |
|---|---|
| Typed chat (on device) | yes: chat screen → `core.chat.send` (`ChatTurns`) → `CluAgent.respond` (policy, research, validation, grounding) |
| Voice (on-device engine) | yes: `VoiceSession.runAgent` → the same `CluAgent.respond`, `viaVoice = true` |
| Multi-step tasks | yes (`runTask`, inside `CluAgent`) |
| **Voice via LiveKit server engine** (Settings → Connection → *Clu server*) | **no**: `server/clu_agent.py` reasons with the server's own model; the controller's research policy and grounding checks do not apply there. Not changed in this pass (optional engine, needs a server); recorded as an open gap |

Gaps between the 1.6.0 report and the code, found with a real model (§2): the controller decided *when* to research
correctly, but nothing checked whether the model's answer *used* the evidence; after a failed search, honesty depended
on the prompt; the model could echo Clu's own internal blocks. These are fixed below.

## 2. Real-model evaluation

**Model actually used:** SmolLM2-135M-Instruct, Q4_1 GGUF (llama.cpp reports it as "llama 256M Q4_1, 92 MB"), real
trained weights, through the production path: `CluCore` → `LlmService` → llama.cpp JNI → `CluAgent` with the real
prompt, controller and research provider. Context 4096, 4 threads, max reply 120 tokens. Network: Wikipedia JSON
fixtures for exactly the queries Clu makes (this machine can't reach Wikipedia); offline and provider-down are real
code paths (`platform.online = false`, connection refused).

**Why not a larger model:** Hugging Face is blocked from this machine (proxy 403), so no other trained checkpoint could
be downloaded; the larger GGUFs here (`1b-q4km`, `tiny*`) have random weights and say nothing about quality. The model
configured on the owner's phones is not known to this machine (the 1.5.5+ performance report shows it; none was
shared since). Replacing the working model was therefore not on the table (§4).

Test: `RealModelEvaluation` (opt-in, `-Dclu.realModel=<gguf>`). Orchestration is **asserted** (research decision,
nothing sent offline, `search_failed` on provider failure, no source talk); answer quality is **recorded**.
Five runs after the fixes: `docs/measurements/final_real_model_eval_run{1..5}.json`.

| Case | Researched | What the owner got (5 runs) |
|---|---|---|
| "Who is Leman Russ?" | yes, query `Leman Russ` | 5/5 the result's own sentence ("Leman Russ is the Primarch of the Space Wolves…") — the model's wording was replaced every time |
| "who is lemon rush" (voice) | yes: nothing → provider suggestion `leman russ` → found | 5/5 as above (one earlier run kept a correct model sentence) |
| "Which legion did he lead?" | yes, query `Leman Russ legion lead` | 5/5 "Leman Russ leads the VI Legion, the Space Wolves, from their home world Fenris." |
| "What is two plus two?" | no (correct) | 3/5 correct ("Two plus two is 4."), 2/5 wrong ("08546"; text about Leman Russ from the conversation) — not grounded by design (plain chat), **a model limitation** |
| "Who was Tenzing Norgay?" | yes | 5/5 the result's sentence |
| "What is the latest Android version?" | yes (current information) | 5/5 the result's sentence |
| "Tell me about Mercury" | yes → ambiguous | 5/5 "Do you mean Mercury (planet), Mercury (element), or Freddie Mercury?" (Clu's code, no model) |
| provider down | yes → failed | 5/5 "I couldn't reach the search service just now, so I can't check that." |
| offline | no request made | 5/5 "I'm offline right now, so I can't look that up." |

**Conclusion, stated plainly:** with this 135M model, Clu's orchestration is correct and nothing unsupported reached
the owner in the final five runs (35/35 research, offline and provider cases safe), but that is because Clu's checks
replaced the model's answer every time — the model itself did not produce a usable grounded answer once. This is **not**
evidence that Clu is intelligent; it shows that a weak model now fails safely instead of confidently. Answer quality
with the phone's model must be judged on the phone (checks 1a–1d in DEVICE_ACCEPTANCE.md).

### Failures found with the real model, and what Clu now does (generic rules, no names)

Each was seen in a real run, then reproduced in `AgentAcceptanceTest` with a scripted engine.

| Real model output (abridged) | Rule now in `Grounding` / `CluAgent` |
|---|---|
| "…cannot be found in Wikipedia. https://en.wikipedia.org/… #Howliverse" | opening must use a word or number from the results that is not in the question or a title; links, hashtags, site names removed |
| "Android 16 (2023-10-30) released on 9th October 2025" | every number must occur in the results/question |
| "(16) What was the latest Android version" (filler) | a title number counts only when used as the results use it ("Android 16") |
| "Lemmertz Norgay, the Emperor of Tibet, 1953…" | capitalised names absent from results and question are invented |
| good opening, then "[Response] [Citations] 1. Wikipedia, 2025-11-02…" | after the opening, each sentence is shown only once checked; the first unsupported sentence ends the reply and stops generation |
| "I'm sorry, I couldn't resist the urge to make your story about a space wolf…" | the opening must name the subject (as asked or corrected) or refer to it ("He…") |
| "[Tool result: web.search] …", "Offline: the web search could not be completed… Begin with one sentence…" | internal markers stripped; any 5 consecutive words copied from Clu's own instructions/notes (contractions spelled out) is an echo |
| "You can't access live information, but…" (paraphrased note) | failure acknowledged only in Clu's own first person and naming what failed |
| after a failed search: "Trouble in a bucket: this is not the first time…" | a reply from memory must mention the subject; otherwise the honest fallback |

After a failed or empty search, a substantive reply from the model's own knowledge is **kept** and Clu itself says it
is unchecked ("I couldn't check online just now, so this is from memory. …"), as the 1.6.0 design intended ("answer
from knowledge, saying so"); replies that claim a search happened, non-answers and echoes get the plain message.
A replaced reply is stopped as soon as it is known to be replaced (no generation to the token limit).

## 3. Research decisions and grounding

Unchanged from 1.6.0 and re-verified: at most 3 attempts, never the same query twice, failures to run are not
retried; cancellation stops further queries and model calls; untrusted web text can't start actions (asserted by
`retrievedContentCannotTriggerActions`); failed / empty / irrelevant / ambiguous are separate outcomes. New in this
pass: the app — not the prompt — decides what is presented as found (§2).

## 4. The model

Path: `Settings` (Models) → `LlmService.configFor` → `LlamaEngine` (llama.cpp, GGUF, chat template from the file).
Catalog/import: in-app catalog (Qwen2.5-3B-Instruct Q4_K_M default, Llama-3.2-3B-Instruct Q4_K_M) or any imported
GGUF; a model that fails to load falls back to the last verified one (1.6.0, `ModelProviderTest`). Interface unchanged
(`LlmEngine` / `InferenceRouter`); no second model added. **No candidate was evaluated** — none obtainable here — and
none is recommended as an upgrade on loading alone. The real-model results above say the 135M model is far too small
for grounded answers; the catalog's 3B models are the obvious next step **to be judged on the phone**, not here.

## 5. Latency

**Device (historical, from the owner's report before 1.5.4; not re-measured — no device):** first token p50 3.088 s /
p90 19.802 s; complete p50 4.602 s / p90 31.831 s / max 501.761 s; end of speech → audio p50 6.35 s / p90 22.53 s;
ASR p50 858 ms; prefill 56.2 tok/s; generation 16.2 tok/s; memory retrieval 0.4 ms.

**Host, real 135M model (5 runs, monotonic clock, request start → first text / done):**

| Turn type | n | first text p50 / p90 | complete p50 / p90 / max |
|---|---|---|---|
| researched, found | 25 | 1.94 s / 2.53 s | 1.97 s / 2.55 s / 2.85 s |
| ambiguous → clarify | 5 | 0.03 s / 0.03 s | 0.04 s / 0.04 s / 0.04 s |
| plain chat | 5 | 0.36 s / 0.42 s | 0.73 s / 0.95 s / 1.09 s |
| provider down | 5 | 1.27 s / 1.87 s | 1.29 s / 1.89 s / 2.20 s |
| offline | 5 | 2.55 s / 2.62 s | 2.57 s / 2.64 s / 2.65 s |

Prompt ≈ 1,264 tokens (p50), of which ≈ 381 new per turn (prompt cache working); generation 57 tok/s; load 158–224 ms.
On a fallback turn the first text arrives when the model's opening has been rejected, so a stronger model (whose
opening passes) shows text sooner. These host figures say nothing about the phone.

**Timing fixes (evidence-based):** voice-turn stage timings in `VoiceSession` (end of speech, ASR, Whisper, Android
recogniser, acceptance, reply end), TTS ms/char and the streaming-speech clock used the wall clock
(`currentTimeMillis`), which jumps on network time sync; now monotonic (`System.nanoTime`). Wall-clock time is kept
where a date is meant. Whether a clock jump caused any of the device outliers (e.g. the 501 s maximum) is a
**hypothesis**, not established.

## 6. Speech pacing (real synthesised audio)

Measured on real speech from Kokoro "Lewis" (Clu's primary voice) and Piper Joe, synthesised on this machine with
the bundled models (sherpa-onnx), through the production `SpeechText.withPause`: `RealVoicePacingAudit`,
`docs/measurements/final_voice_pacing_host.json` (8 phrases × 2 voices).

| Boundary | 1.5.x (pad-only, estimated from measured voice silence) | 1.6.0 target | now (both voices, measured) |
|---|---|---|---|
| comma between chunks | Kokoro 248 ms, Piper 300 ms | 170 ms | **120 ms** |
| full stop | Kokoro 373–398 ms, Piper 343–393 ms | 360 ms | 360 ms |
| question | Kokoro 403 ms, Piper 366 ms | 380 ms | 380 ms |

The voices' own pauses inside a chunk after a comma measure 60–100 ms, so 170 ms between chunks still sounded like a
full stop next to them; the comma target is now 120 ms. Every audible sample (≥ 2 % of peak) is kept and in order;
the loudest removed sample is −80 to −125 dB below the chunk's peak; boundary gaps land within ±25 ms of target.
Abbreviations, decimals, initials, questions, streaming, Stop/barge-in: `SpeechPacingTest`, `SpeechTextTest`,
`AdaptiveChunkerTest` (pass). **How it sounds through the phone's speaker is unverified** (check 1e).

## 7. Tests

* Full suite (`./gradlew test check :app:assembleRelease -Pclu.verify=release --rerun-tasks`): core 422 tests, 0
  failures, 12 skipped (opt-in benchmarks/audits/evaluations, model-dependent Whisper tests, the namespace-only
  offline test); app 62, 0 failures. Discovery audit: 422 `@Test` declared in core = 422 reported; app tests run once
  per Robolectric SDK (33, 34), all reported. No temp files leaked by the build.
* New/changed tests: `AgentAcceptanceTest` 31 (+13 for the real-model failure shapes), `RealModelEvaluation`,
  `RealVoicePacingAudit` (opt-in), `SpeechPacingTest` (comma 100–150 ms). Two existing expectations changed with the
  behaviour, not loosened: `DiaryTest` (the fake network fails the automatic search, so the saved reply now carries
  "…so this is from memory.") and `retrievedContentCannotTriggerActions` (the scripted reply no longer adds a name its
  fixture doesn't contain; its action assertions are unchanged).
* **Offline namespace test — two defects found and fixed:**
  1. `scripts/test_offline.sh` could pass **without running the test**: Gradle restored the test task from its
     up-to-date state or build cache and `-q` hid it. Of 15 runs early in this pass, only 4 executed (2 failed, 2
     passed). The 1.6.0 report's "3/3 passed" may have had the same flaw. Now `--rerun` and the script prints the
     executed result with its timestamp.
  2. Root cause of the long-standing intermittent failure (confirmed): the test makes the owner's speech with Piper
     VITS, whose random noise gives a different waveform every run; about 1 in 10 renditions (11 of 100 measured) is
     decoded as nothing by the old 20M streaming model the helper uses — deterministically for that waveform
     (re-decoding, other padding, one chunk: still empty). The speech is now synthesised without VITS noise
     (bit-identical every run; decodes "how are you doing to day"). The app ships a different ASR (Kroko), so this
     does not implicate the product.
* Test temp-file leaks fixed (they had filled the disk: ~5.2 GB of GGUF copies, ~3.5 GB of SQLite files):
  `deleteOnExit`/cleanup in `TestSupport.tempDb`, model tests, `PinnedFetchTest`, `TokenServerConformanceTest`,
  `OfflineConversationTest` (kept only on failure, with the failure written into it).

## 8. Open issues

* LiveKit server engine bypasses the controller and grounding (§1).
* The real model available here never produced a grounded answer; answer quality with the phone's model is unknown.
* Grounding is lexical: it catches invented numbers, names, sites, echoes and unrelated text, not a wrong relation
  between true words ("Hillary reached the summit with Norgay in 1955" would fail on the number, but a wrong verb
  would not). It errs toward the extractive fallback (the result's own sentence).
* Plain chat and arithmetic are not grounded (by design); the 135M model got "two plus two" wrong 2/5 times.
* Device latency, device pacing and physical taps remain unverified until DEVICE_ACCEPTANCE.md is run.

## 9. Release

`dist/clu-release.apk` — 1.6.1-prototype, versionCode 14, 85,260,303 bytes, SHA-256
`1aaacc3bcfb8f95784943afeb92731c982d032c2ad4a0acda2bde6b3fbab50ae`. apksig v3 and zipalign verified; signer certificate
SHA-256 `B8:D4:60:94:2F:88:EE:74:D2:59:6B:BD:D3:4E:6A:44:F4:4D:CF:47:BF:DE:0D:0C:51:06:BF:A5:53:70:BE:C6` (unchanged:
installs as an update, data kept); verifyApk 27 components / 377 JNI externals; secret scan of the unpacked APK clean.
