# Stage 1 — search, long-term memory, diary, personality, Android actions (1.4.0-prototype)

What was built, how it works, what it needs, and what has been verified, and how. "Verified" here means an automated
test in this repository runs the code path. **Nothing in this stage has been run on the Xiaomi Pad 6 yet**, and no
real search provider could be reached from the build machine (see §7).

## 1. Web search

### When Clu searches
`SearchTrigger` (core/search/SearchNeed.kt) decides with plain rules, not a model:

* **Searches:**
  - explicit requests: "look it up", "check online", "with sources", "fact-check", "is it true that…";
  - a live topic plus a time word or a question: news, prices, exchange rates, stocks, weather, scores, election results, release dates, versions, "CEO of…", population;
  - a time word plus a question: "latest", "today", "this week", "just released";
  - a question that names the current or previous year.
* **Never searches:** greetings, creative writing, opinions, feelings, and anything about the owner's own life or memory ("my sister's birthday", "my diary").
* **Tested:** `SearchTest#triggerSearchesOnlyWhenNeeded` runs 14 phrasings that should search and 17 that should not. `SearchAgentTest#ordinaryConversationNeverSearches` sends ordinary chat and checks that no HTTP request is made.
* **Setting:** *Search automatically* (`net.searchAuto`). `explicit` means Clu searches only when asked. The model can still call `web.search` itself on request.
* **Requirements:** a search also needs *Internet access* and *Web search* to be on, and the device to be online (`NetworkPolicy`). If any of these is missing, Clu says so; it does not answer as if it had searched.

### Providers

All providers use documented JSON APIs. No HTML scraping, and no crawler.

| Provider (`net.searchProvider`) | What it covers | Needs | Cost / limits |
|---|---|---|---|
| Wikipedia + DuckDuckGo Instant Answers (default) | Encyclopedic facts and definitions. **Not news, prices or scores** — the Instant Answer API is not a full web search. | Nothing. | Free. Both APIs ask clients to be reasonable; Clu makes 2 requests per search. |
| Wikipedia only | Encyclopedic facts. | Nothing. | Free. |
| Brave Search API | The full web, including news. | Your own API key, entered in Settings → Search & internet → *Brave Search API key*. | Brave's dashboard lists $5 per 1,000 requests, with $5 of free credit each month (about 1,000 searches) and up to 50 requests/s. A card is required to activate a plan. Prices change: check api-dashboard.search.brave.com before relying on these figures. |
| SearXNG | Whatever engines your instance queries. | Your own instance URL (`net.searxngUrl`), with `json` in `search.formats`. | Free if self-hosted. Public instances usually disable JSON or rate-limit it. |

* **Results:** 3–5 per search (`net.searchResults`, default 4).
* **Page reading:** with *Read top result pages* on, Clu fetches short excerpts (up to 1,200 characters) from the two best result pages.
  - It does this only after checking each site's robots.txt (`RobotsPolicy`). The robots file is cached for 1 hour; a server error or timeout counts as "disallow".
  - A page fetch is limited to 6 s and to HTML pages.
  - JSON, PDF and binary responses are skipped.

### Privacy
* **What leaves the device:** only the search words, sent to the chosen provider. If page reading is on, the excerpt requests go to the result sites.
* **What never leaves the device:** memories, the diary, conversation history and the owner's name are never sent. `SearchTest` checks the outgoing requests.
* **Brave key storage:** the key is kept in the app's encrypted secret store (Android Keystore). It is never placed in the source code, the APK, settings exports or logs.

### How an answer is built

The flow, in order:
1. The search runs on a background dispatcher. The chat shows "Searching the web…" and voice says "Let me look that up."
2. The model receives numbered results: title, site name, snippet and any page excerpt. It is told to cite them as [1], [2] and to say plainly when the results don't answer the question.
3. The model never receives full URLs, so it cannot invent or alter a link.
4. The links shown under the answer come straight from the provider's response (`MessageMeta.sources`).
5. `Citations.clean` removes any [n] that doesn't match a real source. Spoken replies carry no numbers or links; the voice prompt asks the model to name sites instead.

In the chat:
* **A searched answer:** carries the badge "🔎 Searched the web · N sources", followed by tappable source cards (title, site, snippet). A tap opens the page in the browser.
* **An offline answer:** shows its normal engine badge instead.

### When search fails

The causes handled are no internet, a provider error or quota, a malformed response, a timeout, and no results. In each case Clu says that the search failed and why.

* **Default reply:** "I couldn't search the web just now (…), so I can't give you current information on that."
* **With "When offline: answer locally":** Clu says the search failed. It then answers from the model's own knowledge, says the answer may be out of date, and labels it "Search unavailable · answered without the web".
* **No made-up sources:** no source is ever claimed after a failure.
* **Chat keeps working:** the next ordinary message works as usual. Tested by `SearchAgentTest#searchFailureOfflineDoesNotBreakChat` and `#searchFailureWithLocalAnswer`.
* **Voice stays responsive:** a slow search (1.5 s) does not delay voice. `SearchAgentTest#voiceStaysResponsiveDuringASlowSearch` measures a voice reply arriving in under 500 ms while the search is still running.

### Not in this stage
* The **Clu server (LiveKit) engine does not search.** Automatic search runs in the on-device engine.
* There is no news-specific provider and no result caching.

## 2. Long-term memory

The existing BM25 memory store was extended. It does not use embeddings and does not need the network.

* **Categories:** fact, preference, project/status, instruction, decision, experience, insight, manual, temporary.
  - "Remember that …" chooses a category with simple rules (`MemoryCategory.classify`), and you can change it in the editor.
* **Standing instructions:** "From now on, use metric units" is saved as an *instruction* memory.
  - Instructions go into every reply's system prompt as "Standing instructions the owner asked you to follow" (at most 12).
  - They are not repeated as retrieved facts.
  - Deleting the memory removes the instruction (`MemoryStage1Test#standingInstructionsShapeEveryReply`).
* **Durable vs temporary:** conversation history is separate from memory, and nothing is saved automatically from ordinary chat.
* **Retrieval is bounded:** only the top matches above a score threshold are used, at most *Memories per reply* (`MemoryStage1Test#retrievalIsBoundedNotTheWholeDatabase`, with 40 memories).
* **Inferred insights need approval:** Clu can suggest one, for example "Forgets to drink water while working".
  - A suggestion appears as a card in the chat (Save / Edit / Dismiss) and in Memory → Suggestions.
  - It is not used as a fact until you approve it.
  - A dismissed suggestion's text is erased and is not suggested again (`MemoryStage1Test#inferredInsightsNeedApproval`).
* **Manual control:**
  - Memory screen: insert (paste or import a text file), edit, delete (the text is erased), pin, search and filter.
  - Long-press any chat message → *Remember this*. This is an explicit request, so the memory is saved directly.
  - Tested across a restart by `MemoryStage1Test#manualInsertEditDeleteAndRestart`.

## 3. Diary

* **Storage:** a separate table (`diary_entries`) and repository. Entries live on this device only and work offline.
* **Writing an entry:** write entries in Menu → Diary, or say:
  - "Add to my diary: …";
  - "diary: …";
  - "Save this conversation to my diary" (saves the last exchange as "Me: … / Clu: …").
  - In chat, long-press a message → *Save to diary*.
* **Browsing and editing:** browse by month (the list is grouped by day), search (every word must match, ignoring case), edit (including the date) and delete (the text is erased).
* **Not memory:** diary entries never reach the model's prompt by themselves.
  - *Keep as memory* copies an entry into memory under a category you choose. The entry stays in the diary.
  - Tested by `DiaryTest#diaryIsNotMemoryUntilPromoted`.
* **Diary mode (existing setting):**
  - *automatic*: end-of-conversation summaries go to the diary, not to memory.
  - *propose*: Clu suggests the entry first.
* **Migration:** diary entries kept as memories in versions up to 1.3.1 move into the diary on first launch (schema v2). They are then removed from memory search (`DiaryTest#legacyDiaryMemoriesMigrate`).
* **Not synchronised** between devices in this version.

## 4. Personality and preferences

These settings are in Settings → Conversation. Each one changes the system prompt, and response length also changes the token budget:

* response length;
* formality and directness;
* humour;
* form of address;
* **explanation depth** (new);
* **unprompted observations / proactivity** (new);
* spoken replies.

`PersonaSettingsTest` checks the prompt text for every value.

**Explicit feedback** changes the matching setting and confirms where it lives. Examples:
* "be more concise" → *Understood. Shorter replies from now on. (Settings → Conversation → Response length.)*
* "call me Ashwin"
* "just give me the answer"
* "no jokes"
* "stop reading your answers aloud"

Only short messages that are clearly feedback count; for example, "call me back tomorrow" does not. Nothing about the owner is inferred or saved as a memory (`MemoryStage1Test#explicitFeedbackChangesSettingsAndReplies`).

Clu's identity (calm, composed, not overly cheerful) is fixed in the persona; these settings adjust style only.

### Measured on a real model
Measured with SmolLM2-135M-Instruct Q4_1 through the full agent, using greedy decoding, 6 questions and a fresh conversation for each. Output: `docs/measurements/persona_host.json`; test: `PersonaMeasurement`, opt-in.

| Setting | Mean words per reply |
|---|---|
| Response length: brief / balanced / detailed | 59.5 / 143.8 / 177.5 |
| Explanation: minimal / thorough | 115.8 / 112.8 |
| Proactivity: off / active | 89.7 / 137.7 |

* **Response length:** the difference is real, but it comes mostly from the token budget (100 / 200 / 320 tokens). Most replies hit the limit, and "brief" replies can end mid-sentence.
* **Explanation and proactivity:** these change only the prompt. This 135M model barely follows them: explanation made no measurable difference, and the proactivity numbers come from two outlier questions.
* **Untested:** whether a larger model (for example, a 1–3B model on the tablet) follows these instructions has not been measured. No such model could be downloaded here.

## 5. Android actions

* **Opening an app** (existing): `app.open` reports success only when Android actually starts the activity.
* **Opening a settings screen:**
  - Examples: "open Wi-Fi settings", "show battery settings", "go to Bluetooth settings".
  - Clu uses documented `Settings` intents: main, Wi-Fi panel, internet panel, volume panel, sound, Bluetooth, battery, display, location, apps, storage, date, accessibility, security, privacy, Clu's notifications and Clu's app info.
  - If the device has no matching screen, Clu says so ("This device has no … settings screen"). An unknown name gets the list of options.
* **Switching settings:** "turn on Wi-Fi" and similar requests open the matching panel. Clu says "Android doesn't let apps switch this themselves", because since Android 10 apps cannot toggle Wi-Fi, Bluetooth, location or airplane mode.
* No accessibility automation and no background monitoring.
* **Device testing:** routing is tested on the JVM. Whether each intent resolves on the Pad 6 (MIUI/HyperOS) must be checked on the device (DEVICE_ACCEPTANCE §Y).

## 6. New settings (each verified by a test; see SETTINGS_REGISTER.md)

| Key | Values | Test |
|---|---|---|
| `net.searchAuto` | auto / explicit | `SearchAgentTest#autoSetting` |
| `net.searchProvider` | wikipedia+instant answers / wikipedia / brave / searxng | `ToolNetworkSettingsTest#searchProvider` |
| `net.searchResults` (advanced) | 3–5 | `SearchAgentTest#resultCount` |
| `net.searchReadPages` (advanced) | on / off | `SearchTest#pageReading` |
| `net.searxngUrl` | URL | `SearchTest#searxng` |
| `persona.explanation` | minimal / balanced / thorough | `PersonaSettingsTest#explanation` |
| `persona.proactivity` | off / occasional / active | `PersonaSettingsTest#proactivity` |

## 7. How this was tested, and what was not

* **Providers (offline replicas):**
  - Every provider is tested against recorded responses in the shape each API documents, served by a fake HTTP layer. This covers parsing, the headers sent (the Brave key travels only in `X-Subscription-Token`), error codes (401/403/429/5xx), malformed JSON, timeouts and empty results.
  - **No live request reached Brave, SearXNG, Wikipedia or DuckDuckGo from the build machine**, because its network allows only a few hosts.
  - The first real search must happen on the tablet (DEVICE_ACCEPTANCE §Y).
* **UI:** search badges and source links, the suggestion card, the diary screens and the search settings run on Android framework code under Robolectric (`Stage1UiTest`), not on the tablet.
* **Performance:** search and database work run off the main thread, with no polling. The voice-responsiveness test above is a JVM measurement.
