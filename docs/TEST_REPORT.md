# Test report

Last full run: 2026-10-24 (see [BUILD_LOG.md](BUILD_LOG.md)). **225 automated tests: 222 passed, 0 failed, 3 skipped by default** (benchmark + two environment-gated end-to-end tests, run separately below)
(the opt-in host benchmark), plus static verification of the release APK.
Commands: `./gradlew :core:test :app:test :app:apiCheck :app:verifyApk -Pclu.verify=release`.

## Automated tests vs real-device tests

| | Automated (this report) | Real-device ([DEVICE_ACCEPTANCE.md](DEVICE_ACCEPTANCE.md)) |
|---|---|---|
| Where | Build container: x86_64 JVM, Robolectric, host builds of the native engines | Xiaomi Pad 6 and iQOO Neo 9 Pro |
| Status | **Run: 222 passed, 0 failed; LiveKit E2E 16/16; offline-without-network pass** | **Not run yet: 0 of ~100 checks done** |
| Shows | Logic, data integrity, protocol and crypto, settings wiring, Android framework integration (simulated), API-33 compatibility, APK structure | Microphone, speaker, wake word, ASR/TTS quality, latency, real model speed, background survival on HyperOS/OriginOS, battery, heat, hotspot sync |
| Does **not** show | That anything works on the actual hardware, or how fast | — |

No hardware behaviour or performance figure is claimed as verified until the matching row in DEVICE_ACCEPTANCE.md
has been filled in from a device.

## How things are tested

| Layer | Technique | Real or simulated |
|---|---|---|
| Persistence (all repositories, migrations, settings) | JVM tests on **real SQLite** (sqlite-jdbc) executing the exact SQL used on Android; Robolectric runs the **real Android SQLite driver** | Real |
| Language model | Host build of the **same JNI bridge + llama.cpp b11844** with tiny random-weight GGUF models that carry a **real Qwen2 tokenizer** and chat template | Real engine, meaningless text (plumbing only) |
| Agent / tools logic | `ScriptedEngine` returns scripted model outputs and records prompts | Deterministic simulation of the model |
| Sync / pairing / remote inference | Two independent Clu cores (two databases) over **real localhost TCP** with the real crypto | Real |
| Voice logic (wake policy, clap, noise, turn-taking, chunking, effects, tokenizer) | Synthetic audio + reference outputs (official sentencepiece) | Real algorithms, synthetic audio |
| Voice models | `scripts/voice_pipeline_test.py` runs sherpa-onnx 1.13.8 (same version as the APK's JNI libs) with the bundled/downloadable models on the host | Real models, synthetic speech (Piper TTS), host CPU |
| Android app | **Robolectric 4.14 on Android 13 framework code**: application start, chat flow, tool-routed command, all 16 screens, every settings control, memory inserter | Real framework code on JVM; no audio/native engines |
| API compatibility | `apiCheck`: every platform class/method/field referenced by 1,225 app/core/LiveKit-client classes must exist in the **API 33** android.jar | Static |
| APK integrity | `verifyApk`: manifest components ↔ classes, all **300 JNI externals** (incl. WebRTC) ↔ symbols in the packaged `.so`, bundled assets | Static |

## Results by suite

| Suite | Tests | Failures | What it covers |
|---|---|---|---|
| PersonaSettingsTest | 16 | 0 | Identity, style settings → prompt, templates, quiet hours, active persona |
| VoiceSettingsTest | 19 | 0 | Voice config mapping, metallic DSP, chunking, barge-in, follow-up, mic modes |
| WakeSettingsTest | 13 | 0 | Wake words, fuzzy match, KWS threshold, leading-word rule (measured data), clap sensitivity/count, noise estimation, strict mode, termination |
| ModelSettingsTest | 14 | 0 | Real llama.cpp: model switching changes output, context, sampling, threads, mmap, GPU honesty, idle unload, load at start, thermal, chat template, missing/corrupt/insufficient-memory handling |
| MemorySettingsTest | 8 | 0 | Memory off ⇒ no writes on any path, retrieval switches, threshold, temporary expiry, insights, remember command, diary modes |
| SecuritySettingsTest | 6 | 0 | Speaker ID policy, thresholds, unknown speakers, refusals ("Clu, turn off" from a guest), verification window |
| ToolSettingsTest | 5 | 0 | App allowlist, history retention, max steps, per-tool enable/confirm |
| NetworkSettingsTest | 8 | 0 | Internet master switch (no request sent), categories, provider, external AI, memory-sharing guard, source badges, offline behaviour, no fabricated search results |
| SyncSettingsTest | 10 | 0 | Pairing, one entity, scopes, device-only settings, conflicts (latest / home wins / keep both), discovery candidates, remote inference, listen-only-when-paired policy, connection cap |
| ProactiveSettingsTest | 5 | 0 | Enable, caps, spacing, quiet hours, follow-ups, well-being, check-ins, delivery, battery |
| LearningSettingsTest | 3 | 0 | Preferences change prompt and token budget, A/B alternatives end-to-end, questions |
| DevSettingsTest | 5 | 0 | Diagnostics visibility, log level/capacity, secret redaction |
| MemoryTest | 10 | 0 | Manual insertion persists after restart with original text, relevant vs irrelevant retrieval, provenance, edit/delete, approvals/rejections, character memory boundaries |
| ConversationTest | 4 | 0 | Persistence, search/rename/archive/delete, reply association, interrupted replies |
| AgentTest | 12 | 0 | Tool loop with real results, failure reporting, no tool markup shown, direct commands, honest no-model reply, guest/trusted permissions, private memory protection, character switching and permissions, custom commands, termination, context trimming |
| IntentRouterTest | 3 | 0 | Command routing, conversation left to the model, time/duration parsing |
| LlamaEngineIntegrationTest | 11 | 0 | JNI: info, template, streaming, KV reuse, determinism, grammar, cancellation, errors, UTF-8 |
| EndToEndLlamaTest | 1 | 0 | Full CluCore turn on the real engine incl. KV reuse |
| SyncRobustnessTest | 9 | 0 | Same SAS on both devices, rejected code, closed pairing window, unpaired device, idempotency, deletions, interruption + resume (450 records), phone without tablet, tampered/replayed frames |
| CredentialTest | 3 | 0 | PIN hashed (not plaintext), temporary lockout capped at 15 min, phrase normalisation |
| RegistryTest | 5 | 0 | Every setting: valid default, invalid values rejected, persistence across restart; snapshots/rollback; every setting has an existing verification test; GGUF reader |
| SentencePieceBpeTest | 2 | 0 | Wake-phrase tokenisation identical to the reference library (20 phrases) |
| ImprovementAdvisorTest | 1 | 0 | Suggestions from measured failures/performance |
| HostBenchmark | 1 | 0 | Skipped unless `-Dclu.bench=true` (writes `measurements/core_host.json`) |
| AppSmokeTest (Robolectric) | 7 | 0 | See above |
| RegisterTest (Robolectric) | 1 | 0 | Generates the Settings Implementation Register |

## Defects found and fixed by testing (examples)

* First launch would have crashed: `SQLiteOpenHelper` set `user_version`, so migrations were skipped (Robolectric).
* Four calls to JDK 9+ APIs absent on Android 13 (GGUF reader, UTF-8 stream decoder, proactive planner) (`apiCheck`).
* Owner could not leave a character by voice (character tool permissions blocked `persona.switch`).
* Fixed BM25 threshold rejected relevant memories in small collections → size-scaled threshold.
* Model service didn't refresh state when started after a model was selected.
* JUnit 5 silently skipped a non-`Unit` test; all coroutine tests now declare `Unit`, and a declared-vs-executed audit runs clean.
* Wake word triggered mid-sentence ("I have a clue…") → leading-word rule based on measured timestamps.
* ASR lost the first word → recogniser streams primed with 1 s of silence (measured fix).

## LiveKit voice mode and offline-without-network (2026-10-24)

| Test | Environment | Result |
|---|---|---|
| `LiveKitSettingsTest`, `LiveKitBridgeTest`, `SignallingTest` (16) | JVM | pass |
| `LiveKitEndToEndTest` | Real livekit-server 1.13.10 + Clu voice agent + Kotlin client on WebRTC (webrtc-java) + Clu core incl. real llama.cpp | **16/16** |
| `server/tests/test_e2e.py` | Real livekit-server + token server + agent + Python stand-in device | **16/16** |
| `OfflineConversationTest` (`scripts/test_offline.sh`) | Network namespace, loopback only: ASR → local llama.cpp → TTS, LiveKit fallback | **pass** |

Details: LIVEKIT_INTEGRATION.md (renamed LIVEKIT_AGENT.md in 1.3.0). **Superseded in 1.3.0:** the custom client and its tests were removed; see below. Not run on
a device: Android WebRTC audio, echo cancellation and barge-in on a real speaker, hotspot NAT, battery/thermal/RAM.

## Field defect: crash on every launch (found on device, fixed)

**Found:** 2026-10-24, first install on the iQOO Neo 9 Pro (vivo I2304, Android 16). Captured with the in-app crash
report (`CrashReportActivity`, added for this purpose because there is no logcat without a PC).

**Cause:** `java.util.regex.PatternSyntaxException` in `ResponseTemplates.render`: the pattern `,?\s*\{owner}` has
an unescaped `}`. Desktop Java accepts that; Android compiles regexes with **ICU**, which rejects it. The greeting
evaluated ~1 s after the main screen opens used this pattern, so the app crashed on every launch on every device.
The same mistake was in the model tool-call parser (`<tool>\s*(\{.*?})\s*</tool>`), which would have crashed the
first tool call of a loaded model.

**Why the tests missed it:** JVM and Robolectric tests use the JDK regex engine, not ICU.

**Why only on first launch:** the bad pattern was compiled only when no form of address is set, the state before
the welcome screen is completed.

**Fix (two steps):** first both patterns were escaped. Then `ResponseTemplates` was rewritten to substitute
placeholders literally in a single pass, with **no regular expressions at all** (also fixing three latent bugs the
regression tests exposed: a leading ", " when `{owner}` starts a template with no owner, re-expansion of placeholders
inside substituted values, and a divide-by-zero on an empty template list). `ProactivePlanner` now evaluates each
candidate message inside an error boundary: a failure is logged ("greeting message skipped: …") and that message is
skipped; the others and the app continue. `ResponseTemplatesTest` (9 tests) covers {owner}, missing/empty owner,
punctuation, multiple placeholders, Unicode names, regex-special characters and the boundary; 4 of them fail against
the old renderer. The original pattern `,?\s*\{owner}` is rejected by ICU with status 66305 at offset 13, the same
position as the device report. New build check `:app:icuRegexCheck` (`scripts/check_icu_regex.py`) compiles every
regex literal with the system ICU library and runs before `verifyApk`. It reproduced the device error exactly
before the fix and now passes: 72 patterns, 0 rejected. Greeting evaluation on app open can no longer crash the app
(errors are logged). The crash report screen stays in the app for future field defects.

## Not verified (no physical device or live network available)

* Anything audible or hardware-dependent: microphone capture, AEC/NS, real wake-word accuracy in rooms, TTS
  playback, pitch, barge-in, speaker recognition on real voices, clap detection with real claps.
* Latency, throughput, memory, battery and thermal behaviour on the Pad 6 / Neo 9 Pro.
* Android services on real ROMs (foreground listening survival on HyperOS/OriginOS, notifications, JobScheduler timing).
* mDNS / TCP over a real phone hotspot; pairing between two physical devices (protocol tested over localhost).
* Live internet tools (DuckDuckGo, Wikipedia, Nominatim, OSRM, external AI) – tested with fake HTTP responses only.
* Real 3B model quality, tool-calling reliability of a small model, chat templates of downloaded models.

The in-app **Diagnostics → Run self-tests on this device** and the **AI models → Benchmark** button exist to close
these gaps on the real devices.


## Clu server engine on the official LiveKit SDK (1.3.0, 2026-10-26)

| Test | Environment | Result |
|---|---|---|
| `LiveConversationTest` (7): state, history, segment de-duplication, interruption marking, reply timeout, backoff | JVM | pass |
| `LiveKitSettingsTest` (5), `WakeSettingsTest#background` (no wake listening with the server engine) | JVM | pass |
| `AppSmokeTest` (21) incl. chat send via the new composer, Connection and Voice chat screens open | Robolectric | pass |
| `UiScreenshotTest` (11): chat, chat with keyboard, voice HUD idle/listening/thinking/speaking/disconnected, Connection, Settings | Robolectric native graphics, iQOO Neo 9 Pro resolution | rendered (docs/screenshots/p3-*.png) |
| `server/tests/e2e_agents.py` (8 checks) | Real livekit-server 1.13.10 + LiveKit Agents 1.8.9 worker (Kroko STT, Piper TTS, Silero VAD, turn detector) + **mock LLM** + Python stand-in phone | **8/8** (1.3.0; that report was superseded by the 1.3.1 runs below) |
| `OfflineConversationTest` (moved to `:core`), `scripts/test_offline.sh` | Network namespace, loopback only | pass |
| `:app:verifyApk` (376 JNI symbols incl. LiveKit WebRTC and JNA), `:app:apiCheck` (784 classes), dex reference scan | release APK | pass |

Totals: core 276 tests (6 skipped by default: opt-in benchmarks, model-dependent measurements and the namespace-only offline test), app 47 tests; 0 failures. **Not run:** anything on a phone, the official SDK
connecting to a real room (it needs Android's WebRTC natives), and the cloud stack (LiveKit Cloud not reachable from
the build machine).

## Real-model integration (1.3.1, 2026-10-27)

**Real providers vs. mocks.** "Real" below means genuine inference: llama.cpp `llama-server` with SmolLM2-135M-Instruct
(real weights, from the `llm-smollm2` 0.1.2 wheel on PyPI, SHA-256 `bcc81830…`; the GGUF inside is `b179c952…`),
sherpa-onnx Kroko speech recognition, Piper speech synthesis, Silero VAD, livekit-server 1.13.10 and the LiveKit
Agents 1.8.9 worker. Cloud providers (LiveKit Inference: Deepgram, OpenAI) were **not** tested: no credentials and no
network access to them from the build machine.

| Test | Providers | Result |
|---|---|---|
| `server/tests/e2e_agents.py --llm-cmd llama-server…` (13 checks: dispatch + providers, 4 paths, context reaches the model, interruption, stop, short reconnect, rejoin after room closed, LiveKit outage, model outage, prolonged model outage) | **Real** (local stack) | **13/13** — docs/measurements/livekit-real-llm-e2e.json |
| same with `--mock-llm` | STT/TTS/LiveKit real, **LLM mock** | **13/13** — docs/measurements/livekit-mock-llm-e2e.json |
| `TokenServerConformanceTest` (opt-in `-Dclu.serverPython`) | **Real** token server process; app's `TokenProvisioner` unchanged | pass |
| `LiveTraceReplayTest` | Event trace recorded from the **real**-model run, replayed into `LiveConversation` | pass |
| `LiveConversationTest` (+6: interruption marking, agent error events, thinking timeout, phone-side latency, diagnostics redaction, bounded log, typed acknowledgement) | JVM | pass |
| `LiveKitSettingsTest#tokenEndpoint` (server refusal messages) | JVM, fake HTTP | pass |
| `OfflineConversationTest` in a loopback-only network namespace | device engine, real llama.cpp (random-weight test model) | pass |

Earlier real-model runs during the work failed in ways that exposed bugs (agent start 20.5 s; a typed message
dropped after reconnecting; a harness that judged interruption and replies wrongly); all are fixed and described in
docs/LIVEKIT_AGENT.md §7. **Not run:** the Android SDK in a real room, anything on a phone, the cloud stack.

Totals 1.3.1: core 284 tests (6 skipped by default; `TokenServerConformanceTest` and `LiveTraceReplayTest` ran),
app 47 tests, 0 failures; `OfflineConversationTest` passed separately in the network namespace; `:app:verifyApk`
(376 JNI symbols) and `:app:apiCheck` (793 classes) pass on the release APK.

