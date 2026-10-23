# Test report

Last full run: 2026-10-23 (see [BUILD_LOG.md](BUILD_LOG.md)). **181 tests, 0 failures**, plus static verification.
Commands: `./gradlew :core:test :app:test :app:apiCheck :app:verifyApk`.

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
| API compatibility | `apiCheck`: every platform class/method/field referenced by 618 app/core classes must exist in the **API 33** android.jar | Static |
| APK integrity | `verifyApk`: manifest components ↔ classes, all **76 JNI externals** ↔ symbols in the packaged `.so`, bundled assets | Static |

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
| SyncSettingsTest | 9 | 0 | Pairing, one entity, scopes, device-only settings, conflicts (latest / home wins / keep both), discovery candidates, remote inference |
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
