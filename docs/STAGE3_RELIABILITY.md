# Stage 3 — reliability audit (1.5.1-prototype)

Stage 3 was a reliability milestone: verify what Stage 2 couldn't, fix what breaks, add nothing unrelated.
**What could and couldn't be verified here**, stated first:

| Check | Possible here? | Why |
|---|---|---|
| Real Android device (Pad 6, Neo 9 Pro) | **No** | No adb, no USB device, no KVM for an emulator in the build container. |
| Live web providers and real websites | **No** | The environment's network policy refuses the connection (proxy answers 403 to CONNECT) for `en.wikipedia.org`, `api.duckduckgo.com`, `api.search.brave.com` and ordinary websites (`example.com`, `source.android.com`). Only package registries and GitHub are reachable. |
| Everything else | Yes | JVM tests, Robolectric (Android framework code), two Clu installations syncing over real localhost TCP, the real llama.cpp engine in a network-less namespace, APK checks. |

So no result below comes from a device or a live service. Section 5 lists what still needs one.

**Baseline:** branch `claude/clu-android-ai-build-5cspi2` at `f146571` (1.5.0-prototype, versionCode 7). The working tree was clean, and all 338 core and 53 app tests passed.

## 1. Defects found and fixed

Each defect was found by a probe that reproduced it, and each has a regression test.

| # | Defect | How it was found | Fix | Regression test |
|---|---|---|---|---|
| 1 | **A hostile or broken page could freeze research.** The regex-based text extraction backtracked badly: one page with 20,000 unclosed `<script>` tags took **189 s**. A page with 100,000 unclosed `<p>` tags ran for more than 10 minutes without finishing. On the phone that pins a CPU core, and it can't be cancelled. | Probe with adversarial 1 MB pages | `PageReader.textBlocks`: one forward pass over the HTML, with no backtracking regexes. Skipped elements (script, style, nav…) jump to their closing tag; an unclosed one ends the text. | `Stage3AuditTest.hostilePagesAreProcessedInLinearTime`: 6 hostile pages, each under 2 s (they take milliseconds), and correct extraction of normal pages, including text inside comments and scripts being ignored. |
| 2 | **Stop during a web search didn't stop it.** Cancelling only set a flag, so the provider request finished and robots.txt plus both pages were still fetched (4 more requests). The turn could also end without a final event. | Probe: cancel 150 ms into a search | `executeCancellable`: tool runs are tied to the turn's cancellation. Stop cancels the coroutine, so no further requests start, the model isn't run, and the turn ends with an "interrupted" Done. Applied to automatic searches, plan steps and model-requested tools. | `Stage3AuditTest.stopDuringResearchStopsFurtherRequests`: only the one in-flight request, no model call, an interrupted Done, and the next message working. |
| 3 | **Local-network protection could be bypassed by DNS.** Addresses were checked only by how they were spelled. A public-looking name resolving to `192.168.x.x` (DNS rebinding), or numeric spellings such as `0300.0250.0.1`, `0x7f.0.0.1` or `127.1`, were not refused. | Review plus probe | `HttpFetcher.resolve`, with Android doing a real DNS lookup: every hop (each redirect too) is refused if *any* resolved address is loopback, private, link-local, CGNAT, multicast or unspecified, including IPv4-mapped IPv6. Numeric hosts must be plain dotted decimal. | `Stage3AuditTest.namesResolvingIntoTheLocalNetworkAreRefused` |
| 4 | **Records written while a sync scope was off were never sent.** The sync log moved past them, so a memory saved while "Sync memories" was off stayed missing on the other device after turning it back on. | Data-integrity probe | Every scope that was off during a sync is re-queued once when it is turned on again. Receivers ignore versions they already have. | `SyncIntegrityTest.recordsWrittenWhileAScopeWasOffAreSentWhenItIsTurnedOn` |
| 5 | **One Stage 2 test never ran.** JUnit 5 silently ignores a test method that returns a value. `ResearchTest.pagesAreFetchedSafely` ended with an expression, so it was skipped while the Stage 2 report counted it as passing. | Comparing declared vs executed tests per class | All `= runBlocking` tests now declare `: Unit`. That test now runs and passes. Declared and executed counts match for every class. | The test itself (now 9/9 in `ResearchTest`) |

**For device measurements** (not defects): two timings the device needs and the app didn't record are now in Diagnostics → Performance:
* `stop_to_silence_ms`: time from a Stop or interruption request until playback is paused and flushed.
* `resume_to_frame_ms`: time from returning from the background to the first frame drawn.

They join the existing `app_create_ms` (cold start), `endpoint_to_first_audio_ms` (end of speech → first audio), `barge_in_detect_ms`, `turn_first_token_ms` and the PSS / Java heap readout.

## 2. Audited and found correct

These were checked by tests that exercise the behaviour, not by reading code:

* **Ordinary conversation makes no network requests,** online or offline, across seven kinds of messages (`ordinaryConversationMakesNoNetworkRequests`).
* **Garbage provider responses and pages never crash anything.** That covers empty, truncated, wrong types, `javascript:` URLs, binary and 30 random Unicode bodies. Each becomes a stated failure (`garbageResponsesNeverCrash`).
* **No private data in search requests:**
  - Memories, diary, history and profile never reach search requests (`SearchAgentTest.personalDataNeverSentToTheProvider`).
  - Model-written queries carrying private words are refused (`ResearchTest`).
  - After web content is read, no action can run (`ResearchTest`).
* **No credentials in the APK.** I unpacked the 1.5.0 APK (dex, resources, assets, manifest) and searched for Brave (`BSA…`), OpenAI (`sk-…`), Google (`AIza…`) and Slack-style tokens, LiveKit secrets and private keys: **none found**. Only the names under which secrets are stored on the device appear (`brave_search_key`, `external_ai_key`, `livekit_device_key`).
* **Limits:**
  - Page reads: 1 MB bounded read, 6 s connect/read, 3 redirects.
  - Provider calls: 15 s each, at most 3 queries.
  - Model: at most 2 model-initiated searches per reply.
* **Sync integrity** (`SyncIntegrityTest`, two installations over localhost TCP):
  - Stale copies from before a deletion, from a live peer re-sending everything or from an old sync file, never bring a deleted memory or diary entry back. Deleted text stays erased.
  - Damaged sync files (truncated, altered ciphertext, reversed, empty, wrong format, weakened key settings, a future version) are refused with a message, and nothing is half-imported.
  - After both apps restart right after a sync, the pairing survives, nothing is duplicated, and new edits flow.
  - Four simultaneous syncs plus a follow-up leave exactly one copy of each of 120 records on both sides.
  - Existing tests cover interrupted-sync resume, wrong passphrase, double import and diary scope (`SyncSettingsTest`, `SyncStage2Test`).

## 3. Known limitations (unchanged by design or not fixable here)

* **DNS check, then connect:** Android's HTTP client resolves the name again when it connects, so a DNS server answering differently within milliseconds (rebinding with zero TTL) could still slip through. Closing that would need address pinning in the HTTP stack.
* **After Stop**, a request already in flight finishes in the background until its socket timeout (up to 30 s). No further request starts and its result is discarded.
* **Memory switched off on the receiving device:** memories synced to a device with *Persistent memory* off are refused there and aren't re-sent when memory is switched on later. Turning *Sync memories* off and on again on the sending device re-queues them.
* Everything in §5.

## 4. Measurements

**No device measurements were possible.** The only new numbers are build-machine timings, from tests on a 4-vCPU x86 container:
* Hostile-page processing: under 2 s each (the assertion), in practice milliseconds, versus 189 s or more before.
* Stage 2 host measurements are in `docs/measurements/stage2_host.json`. They are not phone numbers.

**How to measure on the devices:** DEVICE_ACCEPTANCE §P gives the procedure. Diagnostics → Performance shows the timings listed in §1, and *Copy report* exports them.

## 5. Still requiring external validation

1. **On the Pad 6 and the Neo 9 Pro:** every row of DEVICE_ACCEPTANCE §Z and §P, which covers install and upgrade from 1.5.0, migration, offline text and voice, interruption and Stop, backgrounding and process death, microphone permission denial and recovery, settings intents, pairing and sync on real Wi-Fi, sync files through Android's file picker, and resizing and keyboard.
2. **Live search providers:** the default Wikipedia and DuckDuckGo path, Brave with your key, and real pages (redirects, robots.txt, publication dates). Two ways to run these:
   - **On the device:** DEVICE_ACCEPTANCE §Y and §Z.
   - **From this environment:** allow `en.wikipedia.org`, `api.duckduckgo.com`, `api.search.brave.com` and general web access in the environment's network settings (https://code.claude.com/docs/en/cloud-environments#network-access).
3. **Performance and battery:** cold start, resume, speech end to first audio, stop to silence, memory during conversation and research, and battery over a session. All need the devices.
