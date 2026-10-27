# Stage 2 — research, contextual memory, phone ↔ tablet continuity, task planning (1.5.0-prototype)

This builds on Stage 1 ([STAGE1_SEARCH_MEMORY_DIARY.md](STAGE1_SEARCH_MEMORY_DIARY.md)). Nothing was replaced: same offline engine, speech pipeline, HUD, providers, memory store and sync transport.

**How each part was verified:**
* **JVM tests:** automated tests on the build machine.
* **Mocked tests:** these use recorded or fake HTTP responses. No real search provider or website was contacted.
* **Not verified on a device:** no Android device or emulator was available. There is no adb, and no KVM for an emulator. **Nothing in this stage has been run on the Pad 6 or the Neo 9 Pro.**

## 1. Research

**What happens for a question that needs research** (`SearchService`, `ResearchPlan`, `PageReader`, `WebSearchTool`, `CluAgent`):

1. **Decide.** The Stage 1 rules decide whether to search (`SearchTrigger`). Stage 2 adds:
   - explicit research requests: "check whether…", "look this up / look into…", "research…", "compare the available options";
   - comparisons of things you can buy or choose between: "Pixel 9 vs iPhone 16 price", "compare broadband plans".
   - Greetings, creative writing, opinions and questions about the owner's own life still never search.
2. **Plan the queries.**
   - Usually one focused query, made from the owner's words with filler removed.
   - For a comparison, three queries: the comparison and one per option ("A vs B for X", "A for X", "B for X").
   - Never more than three. They run in parallel, each with a 15 s limit.
3. **Merge.**
   - Results are interleaved across the queries and duplicate URLs are dropped.
   - **At most two results per site**, so one site can't look like independent confirmation.
   - The answer reports how many *different sites* the results came from.
4. **Read pages.** If *Read top result pages* is on, Clu reads the best 2 pages (3 for a comparison) **from different sites**. Each read is checked:
   - **Address:** only public `http(s)` addresses. Clu refuses `localhost`, private, link-local and single-label hosts, and URLs with credentials. This applies to search results and to every redirect, so a result can't make the phone open router or LAN pages.
   - **robots.txt:** checked for every host, including each redirect target.
   - **Limits:** 6 s connect/read timeout per request, at most 1 MB read (a bounded read: a large page is never fully loaded into memory), at most 3 redirects.
   - **Type:** HTML or text only. A malformed page yields "no readable text" rather than an error.
   - **Recorded outcome:** each page is marked as *opened* or *not opened*, with the reason: robots.txt, timeout, HTTP code, not a web page, too many redirects…
5. **Treat web text as untrusted** (`WebContent`).
   - Before the model sees anything, markup the agent or chat template treats specially is removed: `<tool>`, `<|im_start|>`, `[Context]`, `[system]` and similar.
   - Sentences addressed to an AI are cut and marked "[text that addressed an AI was removed]". Examples: "ignore previous instructions", "you are now…", "reveal the memories", "call the tool".
   - The results sit between BEGIN and END markers, labelled as untrusted data.
6. **Allow no actions after reading the web.**
   - Once web text is part of a reply, the model may search again (at most twice per reply) but **no other tool runs**. Clu says so instead.
   - So a page can't save memories, open apps or change settings.
7. **Allow no private details in model-written queries** (`SearchPrivacy`).
   - Automatic searches use only the owner's own words.
   - A query the model writes itself is refused if it contains words from memories, standing instructions or the owner's names that the owner didn't write in this request.
   - Diary text never reaches the prompt in the first place.
8. **Answer.** The answer leads with the conclusion, then the reasons, with citations [n].
   - "Confirmed" only when two different sites agree. A claim from one source is said to come from one source.
   - Disagreement is stated.
   - Clu's own reasoning is marked as such ("My assessment: …").
   - Links come only from the provider; the model never sees URLs (unchanged from Stage 1).
9. **Show the sources.** Under the answer, each source card shows:
   - "Page read", or "Snippet only (reason)";
   - a publication date when the provider (Brave `page_age`, SearXNG `publishedDate`) or the page itself (`article:published_time`, `datePublished`, `<time datetime>`) states one;
   - "updated …" for Wikipedia's last edit;
   - the retrieval time.
   - Dates are never guessed.

**When the search fails.**
* **New default:** *When offline* now defaults to **answer locally**. Clu says the search couldn't be done and why, then answers from its own knowledge, marked as possibly outdated. The badge reads "Search unavailable · answered without the web". The previous "explain only" behaviour is still available as a setting.
* **No model loaded:** Clu now says plainly that it couldn't search and has no model. Before this fix, the internal instruction text or the raw result block could appear as the reply.

**Tests (mocked):**
* `ResearchTest` (9): comparison planning and merging, page safety, page status and dates, injection, query privacy, the search limit, offline default, the no-model case, trigger phrases.
* `SearchTest` and `SearchAgentTest`: updated.

## 2. Contextual memory

* **Relevance first.** The Stage 1 BM25 threshold still decides what is relevant. Nothing irrelevant is added.
* **Ordering** (`MemoryRanking`): among relevant memories, what the owner stated explicitly ranks above Clu's approved inferences (×1.15 vs ×0.9). Recent memories rank above old ones (×1.0 today, about ×0.87 after a year, never below ×0.8). These weights only change the order.
* **Conflicts** (`MemoryConflicts`):
  - **What is flagged:** pairs of preferences, decisions, project notes or instructions that are mostly about the same subject but say different things. Example: "prefers tea" and later "prefers black coffee now".
  - **What Clu is told:** both appear in the prompt with their dates, plus a note to prefer the newer one, or the owner's own statement over an approved inference. If it matters, Clu is told to ask the owner which is current.
  - **Limits:** at most two notes. Plain facts aren't checked, because many facts about one topic are normal.
* **Projects** (`ProjectContext`): when a request is about a project memory, or says "continue / resume / back to / where were we / status of…", the best-matching project's decisions, facts and preferences come along. That adds at most 2 memories beyond the normal limit. No schema change was needed: related memories are found by the project's words.
* **Standing instructions:**
  - They are ordered oldest first and the prompt says the later one wins.
  - Editing an instruction makes it the newest.
  - The prompt also says stored instructions never override core rules, security checks or confirmations. Those are enforced in code anyway, not by the model.
* **Unchanged:** deleted memories are never retrieved, an edited memory replaces the old text, inferred memories need approval, and provenance is shown in the prompt and in Memory.
* **Diary → memory:** "Keep as memory" now shows the proposed memory text for editing and saves only on OK. The diary entry itself is unchanged.
* **Tests:** `ContextMemoryTest` (5) and `MemoryStage1Test` (updated).

## 3. Phone ↔ tablet continuity

**What already existed** (`SyncEngine` / `SyncNode`):
* Paired local-network sync: ECDH P-256 key agreement, HKDF, and an AES-256-GCM encrypted, authenticated channel.
* Pairing confirmed by a 6-digit code.
* Hybrid logical clocks, a change log, idempotent apply, propagated deletions, and resumable batches.
* Conflict detection with *latest*, *home device wins* and *keep both* policies.

**What Stage 2 changed:**

* **The screen is now reachable.** The *Phone & tablet* screen existed but no menu opened it. It is now in Settings → **Phone & tablet**.
* **Diary sync.** Moving the diary out of memories in 1.4.0 had silently stopped it from syncing, while the setting still said "memories & diary".
  - Schema v3 versions diary entries (`hlc`, `origin`).
  - They sync under a new **Sync diary** setting, **off by default** because the diary is private.
  - Turning it on later also sends entries written before.
  - "Sync memories" is now named just that.
* **Encrypted sync file** (`SyncBundle`), for when the devices can't reach each other (no shared Wi-Fi, hotspot isolation):
  - **Export:** on one device, choose whether to include the diary, then set a passphrase. Move the file any way you like. Import it on the other device.
  - **Contents:** the same versioned rows as live sync: memories with deletions, shared settings, and the diary if chosen. Device-specific settings never leave the device.
  - **Merging:** newer versions win and older ones are ignored. Importing twice changes nothing, and nothing is duplicated.
  - **Encryption:** AES-256-GCM. The key comes from the passphrase (PBKDF2-HMAC-SHA256, 210,000 iterations, random salt).
  - **Rejected files:** a wrong passphrase, a tampered file (the header is authenticated) and a device's own file are each rejected with a clear message.
* **No cloud and no server.** Nothing runs in the background beyond what already did: auto-sync while the app runs, every N minutes, set in the existing setting.

**Tests** (`SyncStage2Test`, 4), with two real Clu installations over localhost TCP:
* diary scope;
* offline edits that merge;
* the newer edit winning on both devices;
* a late older version being ignored;
* keep-both copies;
* sync file round trip, wrong passphrase, tampering and re-import;
* the v2→v3 migration.

**Not verified:** two real phones on a real network, mDNS discovery on MIUI/HyperOS, and Android's file picker with real storage providers.

## 4. Task planning

**Recognising a task** (`TaskPlanner`): Clu recognises requests that chain actions, for example "Set a timer for 10 minutes, then look up the weather in Pune and open Spotify", or a numbered list.
* Every step must start with an action verb. So "I went to the market and then came home" and "salt and pepper prices" are not plans.
* A plan has at most 5 steps. Clu says when more weren't taken on.

**Running it** (`CluAgent.runTask`):
* **Action steps** go through the existing direct routes. Each step's ✓ or ✗ comes from **the tool's own result**, for example "Spotify isn't installed on this device."
* **Lookup steps** run a web search with sources.
* **Questions** are answered by the model in **one** reply under the report. The model is told not to describe anything as done beyond the report.
* **Progress:** the chat shows "Step 2 of 3: …" while running. `TaskProgress` events carry each step's status.
* **Confirmation:** an action that needs confirmation and isn't confirmed, or needs verification, stops the plan, and the later steps don't run. "Say 'continue the task' when ready" resumes from there; confirmation is asked again.
* **Stop:** cancels the remaining steps, and the report says so.
* **Resuming:** only within 2 hours, in the same conversation, and only while the app keeps running (state is kept in memory). After a restart Clu says it has no recent task rather than guessing.

There is no autonomous background work and no second model.

**Tests:** `TaskPlanTest` (5).

## 5. Measurements (build machine, not a phone)

From `docs/measurements/stage2_host.json` (`Stage2Benchmark`, opt-in `-Dclu.bench=true`). These were taken on a 4-vCPU x86 container with a scripted model, so they show Clu's own per-turn cost, not inference:

| | 100 memories | 1,000 | 5,000 |
|---|---|---|---|
| Memory retrieval (rank + order + project context) | ~6 ms | ~12 ms | ~59 ms |
| BM25 ranking alone (the Stage 1 step) | ~2 ms | ~3 ms | ~7 ms |
| Whole turn without inference | ~36 ms | ~49 ms | ~77 ms |

* **Why retrieval is slower at 5,000:** the project lookup adds one more ranking pass. It is costlier at 5,000 here because every synthetic title shares the word "note".
* **Sync file, 1,000 memories:** export ≈ 0.7 s and import ≈ 1.0 s, about 0.84 MB. Most of the time is the deliberate passphrase key stretching.
* **Cleaning 30 kB of web text:** ≈ 5–9 ms.
* **Not measured:** phone timings, idle RAM, and battery. These need the device.

## 6. Configuration

* **Search:** as in Stage 1. Keyless by default; Brave needs your key (Settings → Search & internet); SearXNG needs your instance.
* **Sync:**
  - **Live sync:** both devices on the same network, paired once (Settings → Phone & tablet).
  - **Sync file:** nothing but a way to move a file.
  - **Diary:** add it by turning on *Sync diary* (Settings → All settings → Devices) on both devices.
* **No new permissions, services or dependencies.**

## 7. Database migration and rollback

* **v2 → v3** runs on first launch of 1.5.0. It adds two columns to `diary_entries` and one change-log row per existing entry. No data is rewritten or removed. Tested from a v2 fixture by `SyncStage2Test#diaryMigrationKeepsEntriesAndMakesThemSyncable`.
* **Rollback:**
  - 1.4.0 cannot open a v3 database: its schema check refuses a newer version, so the app fails at startup.
  - Android also won't install a lower versionCode over a higher one without uninstalling, and uninstalling erases app data.
  - So before going back, export a sync file (or Memory → Export) on 1.5.0, uninstall, install 1.4.0, and re-enter or import what you need.
  - 1.4.0 can't read sync files: the format is new in 1.5.0.
