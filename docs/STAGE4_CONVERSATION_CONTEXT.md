# Stage 4 correction: conversation context, research relevance, answer first (1.7.0)

_2026-10-29. Verified on the build machine only (x86 host, JVM/Robolectric, host llama.cpp). **Not tested on a
device.** GDACS, Open-Meteo and Wikipedia are refused by this environment's network policy (proxy 403), so the hazard,
weather and search paths were verified against recorded-format fixtures, not the live services._

## 1. Confirmed root causes

Each was reproduced with the production agent and fixture network before it was changed (the reported conversations
replayed through `CluCore`).

| Reported failure | Root cause in 1.6.2 |
|---|---|
| "Search the web and tell me" after a cyclone question searched "the web" | The task planner split the sentence at "and" into two steps; step 1 searched its own words. Nothing linked the command to the previous question. |
| "Look it up" / "Search for it" after "Who is the CEO of Microsoft?" | The command itself was the query ("Look it up", "it"). The intent router turned "search for it" into a search for "it". |
| "Tell me more", "What about its location?", "Is it dangerous?" lost the subject | The only context was an in-memory map of the last research subject (15 minutes, lost on restart or reload, absent after a failed search or a plain model answer). Hazard and weather topics had none. |
| Cyclone question answered from the model or an encyclopedia | "cyclone" was not treated as current information and "which X is …" was not matched, so no research ran; when it did, the provider was Wikipedia. There was no official hazard source and no owner location at all. |
| Unrelated results presented as the answer | Current-information queries had no subject, so the relevance check accepted any result; the query was the whole question sentence ("What is the latest Android version"); the fuzzy name match counted "June" as "Pune". A routed "search for …" bypassed the relevance check entirely. |
| A deferral kept as an answer | "I need to check current information." was not recognised as a non-answer, so it was kept "from memory" and never researched. |
| Correct answers rejected | The claim check required half of a sentence's other words to appear in the result: "has led the company since 2014" vs "has held the role since 2014" failed, and the acronym "CEO" (spelt out in the result) was required to appear. A word capitalised only because it starts a sentence ("See …") counted as an invented name. |
| Bibliography in the answer | The chat drew every source as a card with "[n]", host, snippet and retrieval time under each answer; "See https://…" was left as "See". |
| Misheard words ("convoy" for "cyclone") | Nothing compared a transcript with the conversation. |

## 2. What changed

**Conversation state** (`core/.../agent/ConversationContext.kt`, `server/clu_context.py`). Each conversation keeps an
explicit state: the active topic (question, subject, entities, kind: general/entity/current/hazard/weather, whether
it was answered, what research found), the previous topic, a pending clarification, and a place named in the
conversation. Stored per conversation in the local database (`kv_meta`, no migration), so it survives errors, model
reloads, app restarts and switching between voice and typing; deleted with the conversation; never synced, never a
memory. The server keeps the same state per session (`ConversationState.to_json/from_json`).

**Follow-up resolution** runs before routing, deterministic and model-free:
* a bare research command ("search the web and tell me", "look it up", "can you check online?") researches the open
  question; with no topic Clu asks "What would you like me to look up?";
* "tell me more" / "go on" continue the topic; with nothing new found Clu says so instead of repeating itself;
* references ("it", "he", "the previous one", "what about its location?", "is the cyclone dangerous?") bind to the
  topic only when the message adds no subject of its own; a new name or new content starts a new topic;
* "it" with two subjects in play gets one question ("Do you mean Mars or Venus?") and the answer completes the request;
* explicit tool commands ("save this conversation to my diary") keep their own words.

**Re-hearing misheard words** (voice only). A word the conversation never used that sounds like a word of a specific
topic (sound-class similarity ≥ 0.6, measured: convoy/cyclone 0.70, cycle/cyclone 0.85, weather/microsoft 0.33) in a
sentence that otherwise continues the topic is re-interpreted; the raw transcript and the interpretation are stored
separately (`MessageMeta.interpreted`). With partial evidence, a name-like word, or a request that acts ("send lemon
a message") Clu asks "Do you mean Leman?" instead. Ordinary chat topics are never re-heard. Nothing is hard-coded
for "cyclone".

**Hazards and weather from official sources** (`core/.../search/Hazards.kt`, `Places.kt`). Current cyclones,
earthquakes, floods, eruptions and wildfires come from GDACS (UN / European Commission; the global event list, so no
location is sent); distance and direction are computed on the device from **Settings → General → Home location**
(a city from a built-in list of ~135, or "lat, lon"; unknown places are never guessed). Answers name the event, its
position, distance, intensity and alert level, the official forecasting centre for that ocean basin (IMD, JMA, NHC, …),
and say plainly what the data lacks (no forecast track, no landfall time). Follow-ups ("is it dangerous?", "when will
it arrive?", "where is it now?", "compare it with the previous one") are answered from the stored finding while it is
fresh (30 min). Weather comes from Open-Meteo (only the city's coordinates are sent). Without a location Clu asks once.

**Research relevance.** Queries are the question's terms ("current CEO of Microsoft", not the sentence); current
queries require their names in the results (acronyms only when nothing else); "search for …" goes through the same
bounded, relevance-checked research; one-letter spelling variants need five letters or the same first letter;
deferrals count as non-answers; the claim check accepts light paraphrase words ("led the company" for "held the
role") only when the claim's names and numbers are all in one result sentence, ignores sentence-initial capitals,
and rejects copied result-block formatting.

**Answer first.** The chat shows the answer, a badge ("Searched the web · On-device model"), and one collapsed line
("Sources (3) ▸", or "Source: GDACS (UN / European Commission)"); tapping it lists the sources without numbers.
"See …" / "Read more at …" left by a removed link is dropped. Nothing about sources is spoken.

**Server parity.** The Python controller has the same resolver, re-hearing, hazard/weather path (`CLU_HOME_LOCATION`),
query focus, relevance and claim rules. The shared contract (`contract/agent_contract.json`, v2) gained Stage 4
conversations (look it up; cyclone + follow-ups + misheard word; unknown location; ambiguous "it"; unrelated results)
plus claim, policy and clean-up cases; core's `AgentContractTest` and `server/tests/test_contract.py` both run them.

## 3. Latency (host, before vs after)

`ConversationLatencyBenchmark` replays the reported conversations through the production path with a real model
(SmolLM2-135M Q4_1, llama.cpp JNI, 4 threads, temperature 0) and the same fixture responses for both builds; the same
file ran unchanged on 1.6.2 (`ac3eda6`). Monotonic clock, 5 runs, medians in ms; "model" is time inside generate calls.
Raw data: `docs/measurements/stage4_followup_latency_before_1.6.2.json`, `…_after_1.7.0.json`.

| Turn | 1.6.2 total (model) | 1.7.0 total (model) | What 1.6.2 answered | What 1.7.0 answers |
|---|---|---|---|---|
| Which cyclone is heading towards us? | 613 (582) | 23 (0) | nothing (empty reply) | GDACS: DANA-24, ~700 km ENE of Chennai, Orange, IMD for warnings |
| Search the web and tell me | 2771 (2677) | 32 (0) | a two-step plan; searched "the web", failed | the same GDACS answer (re-checked) |
| Is it dangerous? | 1386 (1357) | 15 (0) | invented "No, it's not. It's safe." | GDACS alert meaning, distance, authority |
| When will it arrive? | 1323 (1278) | 14 (0) | invented timing | no forecast track in the data; who publishes it |
| Who is the current CEO of Microsoft? | 1897 (1777) | 1832 (1763) | attributed quote | attributed quote (same model calls and tokens) |
| Look it up. | 1074 (993) | 1771 (1715) | searched "Look it up"; nothing found | researched the question; nothing new to add |
| Tell me more (CEO) | 590 (547) | 1724 (1629) | "I don't have a specific context" | researched; says it has nothing more |
| Who is Leman Russ? | 1803 (1729) | 1790 (1714) | attributed quote | attributed quote |
| Which legion does he lead? | 1587 (1516) | 1761 (1685) | answer | same answer, same tokens |
| Tell me more (Leman Russ) | 708 (680) | 2387 (2304) | repeated the earlier answer | researched; quotes a sentence not yet said |

Measured conclusions:
* Hazard turns: no model call, at most one network request (follow-ups reuse the fresh finding), ~15–32 ms instead of 0.6–2.8 s.
* Comparable research turns (same model calls and prompt tokens): no change beyond model-time variance. Time outside
  the model went **down** (e.g. 86 → 68 ms, 87 → 75 ms) — the resolver and state store add no measurable cost.
* Follow-ups that used to be answered from nothing ("tell me more", "look it up") are now researched, which costs a
  search and up to two model calls (+1.1–1.7 s on this host). That is the price of answering them at all.
* Prompt caching: ordinary follow-ups keep the system prompt identical (test 15).
No device numbers exist yet.

## 4. Acceptance tests

Deterministic, mocked providers (fixture HTTP, scripted model) unless stated.

| # | Scenario | Test |
|---|---|---|
| 1 | question, then "search for it" / "look it up" | `ConversationContextTest.aBareSearchCommandResearchesTheOpenQuestion`; contract "a bare search command…" (both engines) |
| 2 | cyclone, then direction and danger | `…aCycloneQuestionIsAnsweredFromTheOfficialSourceAndFollowedUp`; contract (both engines) |
| 3 | ASR error on the second turn | `…aMisheardWordIsReinterpretedFromTheConversation`; contract "Is the convoy dangerous?" (both engines) |
| 4 | topic survives model reload and restart | `…theTopicSurvivesAReloadAndARestart`; server `Context.test_state_survives_a_restart` |
| 5 | unrelated question starts a new topic | `…anUnrelatedQuestionStartsANewTopic` |
| 6 | ambiguous follow-up → one clarification | `…anAmbiguousFollowUpIsClarifiedOnce`; contract "Mars and Venus" (both engines) |
| 7 | irrelevant results rejected | `…irrelevantResultsAreRejected`; `SearchAgentTest.personalDataNeverSentToTheProvider` (Pune/June); contract (both engines) |
| 8 | failed search not presented as research | `…aFailedSearchIsNotPresentedAsResearch` |
| 9 | current cyclone uses authoritative sources | test 2 (GDACS, no encyclopedia request); `…anUnknownLocationIsAskedForOnce`; `HomeLocationTest` |
| 10 | correct answer in other words kept | `…aCorrectAnswerInOtherWordsIsKept`; contract claims (both engines) |
| 11 | no bibliography shown or spoken | `…answersCarryNoBibliography`; `Stage1UiTest.searchedAnswerShowsBadgeAndClickableSources` (collapsed list); contract clean-up cases |
| 12 | cancellation stops tools and generation | `…cancellingStopsFurtherWork`; server `Cancellation` tests |
| 13 | voice and typing behave alike | `…voiceAndTypingBehaveAlike` |
| 14 | server mode follows the same rules | `AgentContractTest` + `ServerContractTest` (runs `server/tests/test_contract.py`) on the same contract file |
| 15 | prompt caching preserved on follow-ups | `…followUpsKeepTheCachedPrompt` |
| 16 | simple questions take the short path | `…simpleQuestionsTakeTheShortPath` |
| 17 | memory and history survive restarts | test 4 (memories, messages, and the history reaching the model's prompt after restart) |
| 18 | consequential ambiguity asked about | `…aConsequentialMishearingIsAskedAboutNotGuessed` |

**Real providers:** the real model (SmolLM2-135M) ran the latency benchmark and the LiveKit end-to-end test (18/18
checks). GDACS, Open-Meteo and Wikipedia could not be reached from this environment, so no live-service test exists.

## 5. Limits

* The place list has ~135 cities; others must be entered as "lat, lon".
* GDACS gives positions and intensities, not forecast tracks or warnings; Clu says so and names the official centre.
* Re-hearing is a sound-alike heuristic on a specific topic; it is conservative (asks when unsure) and only on voice.
  The server agent treats every turn as speech (its input is speech-to-text).
* The 135M model rarely paraphrases acceptably, so researched answers on this host are mostly attributed quotes;
  a larger model is needed for natural answers (see MODEL_AND_ANSWER_QUALITY.md).

## 6. Completion (1.8.2)

_2026-10-31. The Stage 4 brief was re-issued as "resume the interrupted Stage 4". The interrupted work had in fact been
finished and released as 1.7.0 (sections 1–5). Each finding in the brief was re-checked against the 1.8.1 code with a
new acceptance suite (`Stage4CompletionTest`). Five scenarios already passed. Seven failed and are fixed here. Fixtures
and a scripted model only: GDACS, Open-Meteo and Wikipedia are still refused by this environment's network policy (proxy
403 on 2026-10-31), so **no live-provider test was possible**. Not tested on a device._

**Still wrong in 1.8.1, fixed:**

| Finding | What happened in 1.8.1 | 1.8.2 |
|---|---|---|
| "Search the web for X" searched the literal words | The router matched "search" and kept "the web for the current CEO of Microsoft" as the query. Queries also kept a leading "the", and "look up who X is" kept the trailing "is". | The router accepts "search the web/internet/online for …". The query is the subject: "current CEO of Microsoft", "history of Bosnia and Herzegovina". |
| Two cyclones near home | Only the nearest was described ("GDACS also lists Y"), and every follow-up silently meant the nearest. | Each nearby cyclone is named with its own distance and direction. "Is it heading towards us?" asks "Do you mean DANA-24 or VAYU-24?", and the choice carries into later turns. Naming one ("is DANA dangerous?") switches without another question. |
| "Is it heading towards us?" with no location | Answered without a place ("near 15.2°N 86.4°E"). | One question ("Which city or area should I check that for?"). The reply then answers that follow-up for the place given. |
| "What about tomorrow?" | Fetched the weather again and repeated today's. | Open-Meteo is asked for 3 days. "Tomorrow" and "the day after tomorrow" are answered from the forecast; follow-ups reuse a forecast fetched in the last 30 minutes. |
| "Is there any update?" | Repeated the stored finding (or went to the model). | Checks the open topic again (a new request). With no topic, Clu asks what to look up. |
| Misheard names on a memory topic | Memory recall ran before contextual correction, so "where did I meet Leah?" while talking about Riya was never re-heard. The People catalog also matched Leah to Riya as a "spelling variant" (sound-alike rule on one consonant). | A sound-alike of the person being discussed who isn't in the catalog gets "Do you mean Riya?" (names are never changed silently). "Yes" carries the corrected question on. The spelling-variant rule now needs the same first letter or a 3+ consonant skeleton: Sara/Sarah and Kristina/Christina still match; Leah/Riya don't. |
| An ordinary word "re-heard" as the topic | On a voice turn, "Is there any update?" during a weather conversation was silently rewritten to "Is there any weather?" (update/weather share a consonant skeleton, similarity 0.8). The raw text was kept but the meaning changed without asking. Found by the latency benchmark. | A message already understood as a request (update, look it up, tell me more) is never re-heard. Common request words (update, news, latest, forecast, tomorrow, …) are never treated as mishearings. Both engines. |
| Conversation-state clean-up | State was removed only when a conversation was deleted in the list. | It is also removed when the conversation is deleted on the other device (sync). Orphaned or 30-day-idle state is pruned at start-up. |

**Re-checked and already correct in 1.8.1:**
* the bare research command;
* the restart;
* unrelated questions;
* "and" inside a subject ("Bosnia and Herzegovina" is not split; real chains still are);
* provider failures;
* cancellation;
* collapsed sources;
* "what did he do?" with one person (it binds) and with two (Clu asks);
* memory recall vs live research (a memory question sends nothing to the web; a current question is researched and
  never answered from a memory).

**Server parity.** The LiveKit server controller (`server/clu_context.py`, `server/clu_controller.py`) has the same
changes. The shared contract gained two conversations: the two-cyclone conversation (6 turns, with "any update?") and
"search the web for …". Both engines run them (`AgentContractTest`, `server/tests/test_contract.py`).

### Required tests → where they are

| # | Brief | Test(s) |
|---|---|---|
| 1 | CEO question, then "Look it up." | `ConversationContextTest.aBareSearchCommandResearchesTheOpenQuestion`; contract (both engines) |
| 2 | Cyclone, then "Is it heading towards us?" | `Stage4CompletionTest.twoNearbyCyclonesAreDistinguished`, `ConversationContextTest.aCycloneQuestionIsAnsweredFromTheOfficialSourceAndFollowedUp` |
| 3 | ASR correction from the active topic | `…aMisheardWordIsReinterpretedFromTheConversation`, `Stage4CompletionTest.aMisheardNameIsReheardFromThePersonInPlay` |
| 4 | Original transcript kept | the same two (raw message content and `meta.interpreted` checked) |
| 5 | Ambiguous pronouns → clarification | `…anAmbiguousFollowUpIsClarifiedOnce`, `Stage4CompletionTest.pronounsBindOrAreClarified` |
| 6 | Clarification answers carry on | `…anAmbiguousFollowUpIsClarifiedOnce` ("How hot is it?"), `twoNearbyCyclonesAreDistinguished` (VAYU, then "Is it dangerous?") |
| 7 | State survives a restart | `…theTopicSurvivesAReloadAndARestart` |
| 8 | Unrelated questions don't inherit | `…anUnrelatedQuestionStartsANewTopic` |
| 9 | No filler-word searches | `Stage4CompletionTest.aSearchCommandResearchesTheSubjectNotTheFillerWords`; contract (both engines) |
| 10 | Planner doesn't split on "and" | `Stage4CompletionTest.aSubjectWithAndIsNotSplit`, `TaskPlanTest` |
| 11 | Weather uses the weather path | `Stage4CompletionTest.weatherAndTomorrowUseTheForecast`, `SearchAgentTest.personalDataNeverSentToTheProvider` |
| 12 | Cyclones use structured hazard data | `…aCycloneQuestionIsAnsweredFromTheOfficialSourceAndFollowedUp` (GDACS, no encyclopedia request) |
| 13 | Two simultaneous cyclones | `Stage4CompletionTest.twoNearbyCyclonesAreDistinguished`; contract (both engines) |
| 14 | Configured coordinates | `Stage4CompletionTest.configuredCoordinatesAreUsed`, `HomeLocationTest` |
| 15 | Missing location → clarification | `…anUnknownLocationIsAskedForOnce`, `Stage4CompletionTest.aFollowUpAboutUsWithoutALocationAsksForIt` |
| 16 | Missing forecast track said | `…aCycloneQuestion…` ("Which way is it moving?", "When will it arrive?"), `twoNearbyCyclones…` |
| 17 | Provider failure → honest limitation | `…aFailedSearchIsNotPresentedAsResearch`, `Stage4CompletionTest.aWeatherFailureIsAnHonestLimitation` |
| 18 | Cancellation on Stop | `…cancellingStopsFurtherWork`, `Stage4CompletionTest.stopCancelsTheWeatherRequest`, server `Cancellation` |
| 19 | Irrelevant results rejected | `…irrelevantResultsAreRejected`; contract (both engines) |
| 20 | Collapsed sources expand | `Stage1UiTest.searchedAnswerShowsBadgeAndClickableSources` (collapsed by default, tap expands, link opens) |
| 21 | Voice and text consistent | `…voiceAndTypingBehaveAlike`; the contract runs the same conversations on the server (voice) engine |
| 22 | Memory recall ≠ live research | `Stage4CompletionTest.memoryRecallIsNotConfusedWithLiveResearch` |
| 23 | Persistence and clean-up | `…theTopicSurvivesAReloadAndARestart`, `Stage4CompletionTest.conversationStateIsCleanedUp` |
| 24 | Existing tests green | the full core and app suites, server tests and the offline test (see STATUS.md) |

### Latency, 1.8.1 vs 1.8.2 (host)

`ConversationLatencyBenchmark`, the same file and the same fixture responses on both builds (1.8.1 = commit `5dffe1d` in
a separate worktree). Setup: SmolLM2-135M-Instruct Q4_1 through llama.cpp JNI, 4 threads, temperature 0; x86 build
container; 5 runs; medians; monotonic clock.
* **Model:** time inside generate calls.
* **Outside the model:** follow-up resolution, routing, planning, memory retrieval, research checks and storage.
* **Network:** fixture requests (instant), so these numbers exclude real provider latency.

Raw data: `docs/measurements/stage4_completion_latency_{before_1.8.1,after_1.8.2}.json`.

| Turn | 1.8.1 total ms (model) | 1.8.2 total ms (model) | What changed |
|---|---|---|---|
| Which cyclone is approaching? (two near) | 12 (0) | 12 (0) | 1.8.2 names both cyclones |
| Is it heading towards us? | 16 (0) | 9 (0) | 1.8.1 silently answered about the nearest; 1.8.2 asks which one |
| VAYU | 1,768 (1,736) | 11 (0) | 1.8.1 sent the reply to the model; 1.8.2 answers from the data |
| What about tomorrow? | 12 (0), 1 request | 12 (0), 0 requests | 1.8.1 repeated today's weather; 1.8.2 gives tomorrow's forecast |
| Is there any update? (weather) | 19 (0) | 13 (0) | both re-check |
| Search the web for the current CEO of Microsoft. | 1,722 (1,653), 4 requests | 1,715 (1,645), 2 requests | 1.8.1 searched "the web for the current CEO of Microsoft", then "Microsoft" |
| What did I tell you about Microsoft? (memory) | 10 (0) | 10 (0) | unchanged; no network |
| Comparable research turns (CEO, Leman Russ) | 1,723–2,508 | 1,868–2,617 | same model calls and queries |

* **Comparable research turns:** these make the same calls in both builds, and the differences are within model-time
  variance (±10 %). Time outside the model is 54–82 ms in both builds, so the new resolution and checks add no
  measurable cost.
* **Answered from data:** every hazard, weather and memory turn takes 4–16 ms on this host, with no model call.
* **Not re-measured here:** speech-recognition time and time to first audio. That code is unchanged; earlier host
  measurements are in `docs/measurements/`. No device numbers exist.
