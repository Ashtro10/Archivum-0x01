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

---

# Stage 3.1 — security audit, regression tests, release stabilisation (1.5.2-prototype)

**Still not verified here:**
* **Real device:** no adb, USB device or KVM.
* **Live search providers and websites:** the environment's network policy refuses `en.wikipedia.org`, `api.duckduckgo.com`, `api.search.brave.com` and ordinary sites; the proxy answers 403 to CONNECT.

Every result below comes from the build machine. Each test is labelled as one of:
* **host integration:** real sockets or TLS on loopback;
* **JVM:** unit and behaviour tests;
* **Robolectric:** Android framework code;
* **mocked:** fake HTTP.

## Security changes

| # | Weakness | Fix | Proof (test, kind) |
|---|---|---|---|
| S1 | **DNS check, then connect (TOCTOU).** Stage 3 checked a host's DNS answers, but Android's HTTP client then resolved the name again, so a rebinding DNS server could switch the address in between. | **`PinnedHttpClient`**, a small HTTP/1.1 GET client built on standard JDK sockets:<br>• connects to the exact validated IP; the address literal is parsed without any lookup;<br>• keeps the original host for TLS **SNI** and **certificate plus host-name verification** (`endpointIdentificationAlgorithm=HTTPS`, plus Android's platform `HostnameVerifier`);<br>• never follows redirects; the caller re-validates every hop;<br>• no connection reuse (`Connection: close`, a new socket per request);<br>• uncompressed body, 32 KB of headers, 1 MB of body, a per-read timeout and a total deadline of 3 × timeout. | `PinnedFetchTest` (host integration):<br>• connects to the validated address with the real Host header;<br>• refuses a non-public address and credentials;<br>• blocks header injection;<br>• bounded / chunked bodies, unfollowed redirects, malformed responses;<br>• slow-drip deadline;<br>• HTTPS: trusted certificate OK, **wrong host name refused**, untrusted certificate refused, platform verifier honoured, SNI carries the host name;<br>• DNS answers that change between check and connect (resolved once, request sent to the checked address);<br>• mixed public/private answers refused;<br>• fail-safe without a resolver or a pinned client.<br>**Negative control:** with host-name verification switched off, the HTTPS test fails as expected. |
| S2 | **robots.txt bypassed every check.** It was fetched with the platform client, which follows redirects anywhere (including into the LAN) and resolves names itself. | robots.txt now goes through the same validated, pinned path (`SafeFetch.get`), with each redirect re-validated. Anything that can't be checked counts as "disallow". | `PinnedFetchTest.robotsTxtCannotBeRedirectedIntoTheLocalNetwork` (mocked): the redirect to 192.168.1.1 is never requested, the page isn't fetched, and robots.txt is fetched pinned. |
| S3 | **Gaps in the address policy.** Not covered before:<br>• IETF/documentation/benchmark IPv4 ranges;<br>• IPv6 forms carrying an IPv4 address: NAT64 `64:ff9b::/96`, 6to4 `2002::/16`, IPv4-compatible `::a.b.c.d`, Teredo;<br>• site-local `fec0::/10`;<br>• out-of-range ports. | Addresses are parsed into bytes (literals only, never a lookup) and judged by range. Embedded IPv4 addresses are judged as IPv4, and ports must be 1–65535. | `PinnedFetchTest.everySpellingOfANonPublicAddressIsRefused` (JVM): 39 non-public spellings refused, 6 public ones accepted, 18 malformed or unsafe URLs refused. |
| S4 | **A proxy could carry page requests.** The pinned socket used `new Socket()`, which honours a JVM-wide SOCKS proxy. | `Socket(Proxy.NO_PROXY)` (`PinnedHttpClient.DIRECT`). | `PinnedFetchTest.theDefaultConnectorBypassesProxies` (host integration, on the machine's non-loopback address). A default socket fails through a configured unreachable SOCKS proxy (the control); the production connector connects directly. |
| S5 | **A cancelled fetch held its socket** until the timeout, because blocking reads can't be interrupted. | `PinnedHttpClient.fetch` closes the socket the moment the coroutine is cancelled. | `PinnedFetchTest.cancellingAFetchClosesItsConnectionAtOnce` (host integration): the server sees the close within 1 s, against a 10 s timeout. |
| S6 | **API keys could follow a redirect to another host.** The provider client (HttpURLConnection) carried `X-Subscription-Token` to the redirect target. **Reproduced:** the second server received `secret-key-123`. | A request carrying a credential header never follows redirects; a 3xx is returned and reported. Uncredentialed requests still follow ordinary redirects. | `ProviderRedirectTest` (Robolectric, JVM HttpURLConnection against two loopback servers). |

**Audit points with no change needed:**
* Search providers are only Brave, Wikipedia and DuckDuckGo (fixed HTTPS hosts) and your own SearXNG URL (http/https only, deliberately allowed to be on your network).
* No connection pooling exists on the page path.
* All limits from Stage 3 remain.

## Cancellation and lifecycle

| Check | Result | Test (kind) |
|---|---|---|
| A newer request supersedes a slow researched one: the old one saves no reply and never reaches the model | pass | `CancellationRaceTest.aNewerRequestSupersedesAndTheOldOneNeverAnswers` (JVM, mocked) |
| 25 cancellations at random moments against random network delays: exactly one terminal event each, no spurious error, nothing left running, next message works | pass; **5 repeated clean runs** | `cancellingAtRandomMomentsAlwaysEndsCleanly` (JVM, mocked) |
| **Defect fixed:** an interrupted reply with no words still carried the search's sources | sources are now empty when the reply is empty (cannot produce a misleading source card) | same test |
| **Defect fixed:** the chat's status line ("Searching the web…") was not cleared when a turn ended abnormally | now cleared in every outcome | `chatStateIsIdleAfterStopDuringResearch`, `aFailingTurnLeavesNoLoadingState` (JVM) |
| Home → back ×3 and recreating the chat screen: no duplicate or re-sent messages, same conversation, no stuck "Thinking…", next message works | pass | `Stage31LifecycleTest` (Robolectric) |
| Stop silences speech immediately | Not verifiable without a device. The app records `stop_to_silence_ms` for you. | — |

## Diagnostics

* **Diagnostics → Performance** now lists the checklist's measurements with units:
  - cold start, return from background, end of speech → first audio, stop → silence, interruption detected;
  - message → first word, message → complete, recognition, first synthesised phrase, **memory retrieval per message** (new), model load.
* Anything not yet measured says **"not measured yet"**. Battery is stated as **not measured by Clu**.
* **Share performance report** copies and shares a text report with:
  - the app version and versionCode, device model and Android version;
  - a timestamp and the conditions (online, engine, model loaded, uptime);
  - current PSS and Java heap, and the timings with units.
* **The report contains no messages, memories, diary, logs or keys** (`PerfReportTest`, JVM; `Stage31LifecycleTest`, Robolectric).
* **Clear measurements** resets the timings.

## Device acceptance

[DEVICE_ACCEPTANCE.md](DEVICE_ACCEPTANCE.md) now starts with 15 plain-language checks: what to do, what success looks like, and what to collect if a step fails (a screenshot, the performance report, or the crash screen's text). No computer or command line is needed. The earlier detailed lists are kept as an appendix.

## Remaining limitations

* **IDN host names:** pages on internationalised host names (non-ASCII) are refused, and the source shows "Snippet only".
* **Android HTTP proxy:** page reads connect directly, so a global HTTP proxy configured on the Android device is not used for them.
* **Credentialed requests and redirects:** a redirect returned to a request carrying a key (LiveKit token endpoint, external AI, Brave) is reported as an HTTP error instead of followed.
* **Untested on Android itself:** host-name verification on Android depends on the platform verifier, and endpoint identification on Android's TLS stack was not tested on a device.
* **Device and live services:** everything that needs a device or live services is still outstanding (§5 above and DEVICE_ACCEPTANCE).
