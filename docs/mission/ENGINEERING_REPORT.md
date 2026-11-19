# CLU — Master Engineering Mission: audit and engineering report

Date: 19 November 2026. Branch `claude/clu-android-ai-build-5cspi2`. Releases: Android 1.15.0 (versionCode 32), PC 0.13.0.

This report has two parts, as the mission asked: the forensic audit (Stage A) and what was built and measured
(Stage B). The rule throughout is the mission's own: nothing is called complete without evidence, and every number
names where it came from. Where something could not be verified here, the report says so and says why.

**Evidence base.** This is a build container (4 vCPU Xeon, 15 GB RAM, no GPU, no Android device). The network policy
blocks Hugging Face, Wikipedia, DuckDuckGo and Brave. Consequences: no new model can be downloaded, web research is
exercised with fixture pages, and nothing has run on the Xiaomi Pad 6 or the iQOO Neo 9 Pro. The only real
instruction-tuned models on disk are **Qwen3-0.6B (Q4_K_M)** and **SmolLM2-135M**. `1b-q4km.gguf` is a random-weight
shape model (`general.name = clu-test-1b-shape-random`) that is useful for speed and memory, not for intelligence.
Earlier reports that used it measured speed only.

---

## Part A — Forensic audit

### A1. Architecture map

```
                    ┌──────────────────────────── one CLU (core module, Kotlin/JVM) ────────────────────────────┐
  Windows PC        │  CluCore: SQLite store · Settings registry · Memory/Archive · Butler (tasks, reminders)   │
  desktop module ───┤  ToolRegistry/ToolExecutor (permissions, confirmation, per-tool settings)                 │
  (ApiServer,       │  InferenceService → LlmService → LlamaEngine (llama.cpp JNI, GBNF grammars, prompt cache) │
  shell UI,         │  CluAgent  ── message pipeline (below) ──  GoalEngine + GoalStore (durable goals)  [new]  │
  LiveKit voice)    │  AgentService (M7 supervised tasks: planner, PermissionBroker, audit log)                 │
                    └───────────────────────────────────────────────────────────────────────────────────────────┘
  Android app ── ShellActivity (WebView, the same shell UI) ── LoopbackServer/ShellHost on 127.0.0.1
                 ├─ paired: /v1 forwarded to the PC (port 47822, device key)
                 └─ alone:  LocalApi on this device's own CluCore (model, memory, reminders, device tools)
                 Native voice (sherpa-onnx VAD/ASR/TTS, LiveKit) via the CluNative JS bridge; Classic native screens.
```

**One message, as it now flows through `CluAgent.respond`.**

1. The message is saved. Then shutdown and confirmation answers, custom commands and feedback commands are handled.
2. Conversation context: follow-ups ("tell me more", "is it dangerous?"), contextual ASR correction and memory recall.
3. **Understanding (new).** When the model supports grammars and `agent.understanding = model`, one grammar-constrained
   planning turn classifies the message as instruction, question, thinking aloud or chat, and plans steps with the
   real tools. A plan with an action, or with several steps, becomes a durable **goal** and is carried out by the
   GoalEngine.
4. Phrase routing (TaskPlanner, IntentRouter): now only for messages the model understood as instructions, or for
   lookups. Before this work, these keyword layers ran *before* any model saw the message.
5. The research decision (hazards, current facts) and then the model reply, with its in-reply tool loop, grounding and
   claim checks, and **the new claim guard**: a reply cannot claim an action that did not run.

### A2. Capability inventory (state after this work)

| Capability | State | Evidence |
|---|---|---|
| Intent understanding (instruction / question / thinking aloud / chat) | **Works with a grammar model; quality depends on the model** | GoalEngineTest (14 tests); real-model evaluation A6/B3 |
| Durable multi-step goals with checkpoints, resume, budgets | **Works** | GoalEngineTest: resume, in-flight action, budgets, stagnation |
| Reminders with absolute dates, weekdays, date arithmetic | **Works** | ButlerTest `parsesCalendarDates`, `dateArithmeticIsExact` |
| Tasks, lists, calendar tools, briefings | Works (existing) | ButlerTest, CalendarTest |
| Memory, archive, recall, provenance | Works (existing) | MemoryServiceTest, HistoryArchiveTest, AgentAcceptanceTest |
| Web research with relevance checks, grounding, citations | Works on fixtures; **not verified live here** (network policy) | AgentContractTest, RealModelEvaluation |
| Multi-source research synthesis with disagreement (Test 3) | **Partial**: multi-result evidence and conflicts exist; no deep multi-page synthesis loop | AgentController, Evidence/Claims |
| Document creation from research (Test 6) | **Not built**: no document-writing tool in the chat registry (M7 has `fs.write` under supervision) | — |
| Voice: streaming STT, TTS, barge-in | Works on the host; turn-based, **not full-duplex** | M8 E2E measurements |
| Browser / computer use | PC only, CDP browser tools; no Playwright agent | `browser.*` tools |
| Coding agent in isolated workspace | PC self-repair/authoring exists (M7/G-series); not part of this mission's work | SELF_REPAIR.md |
| Android life management (alarms, calls, SMS, notifications, calendar) | Implemented; **unverified on a physical device** | app tests (Robolectric) |
| Original CLU face (hexagon HUD) | **Restored** | InterfaceTest, TouchDispatchTest, UiScreenshotTest |

### A3. Confirmed defects and root causes

| # | Defect | Root cause | Fixed |
|---|---|---|---|
| 1 | Words triggered commands. "I was thinking maybe I should set reminders…" could match a reminder rule | 82 IntentRouter regexes and TaskPlanner keyword plans ran before any model read the message | Yes: understanding first; phrase routing only for instructions or lookups; a musing brake |
| 2 | Multi-step goals impossible (Diwali: look up, compute, remind) | In-reply tool loop: in memory only, 4 steps, and it refuses actions after web reads (a correct injection defence, but no data channel) | Yes: GoalEngine. Web text fills **data fields** only; the plan is fixed from the owner's words first |
| 3 | No absolute dates in reminders ("8 November", "2026-11-08") | `Butler.parseWhen` handled relative times, weekdays and clock times only | Yes: `Butler.parseDate`; dated reminders default to 9 am; past dates refused with a clear reason |
| 4 | Date arithmetic left to the model | No tool | Yes: `date.calc` |
| 5 | "On Thursday" or "tomorrow" without a time was not understood | `parseWhen` required a clock time | Yes: 9 am that day |
| 6 | **The reply claimed actions that never ran** ("I'll remind you an hour before your meeting") | Found by the real-model evaluation: a 0.6B model ignores the "never claim actions" rule | Yes: `ActionClaims` guard appends a plain correction |
| 7 | A planning call evicted llama.cpp's prompt cache (reused tokens fell from >100 to 4 per turn) | The planner used its own system prompt, so the next reply re-read its whole prompt | Yes: planning reuses the chat's own system prompt and history (LlmReliabilityTest cache assertion passes) |
| 8 | Planner examples in the system prompt made the small model answer chat **in JSON** | Measured with Qwen3-0.6B | Yes: the planning instructions are sent only in the transient planning turn |
| 9 | The egg-shaped head (`EntityView`) replaced CLU's hexagonal HUD | Commit `270d3af` (1.12.0) deleted `HexHudView`/`OrbView` | Yes: restored from `270d3af^` |
| 10 | Two tool registries (chat `ToolRegistry`, M7 `AgentToolRegistry`) | M7 grew separately | **No**: documented; the GoalEngine uses the chat registry |
| 11 | "Couldn't understand when" for a date that had simply passed | No past-date distinction | Yes |

### A4. The "thinking trap" (why CLU could get stuck saying "Searching…")

Reasoning, task state and execution were one thing: a model generation with tool calls inside it. If that generation
ended, timed out or the app restarted, the task was gone, and a progress line could be the last thing said.

The fix separates the three:

* **Reasoning context.** The planner sees the owner's words, the tool list and the conversation. An extraction sees
  one result and what to take from it.
* **Persistent state.** The `goals` table holds objective, interpretation, plan, every step's status, value and
  result, errors, retries, model calls, verification and final text. Each step is written as STARTED before it runs
  and as DONE or FAILED after.
* **Execution.** Tools run through `ToolExecutor`, so permissions, per-tool settings and confirmation apply exactly
  as for any request.

States: CREATED → READY → RUNNING ⇄ WAITING_FOR_TOOL → (RECOVERING on retry or restart) → VERIFYING → COMPLETED,
or FAILED or CANCELLED. WAITING_FOR_EXTERNAL_RESULT is used while an action awaits the owner's confirmation.
UNDERSTANDING and PLANNING are carried by the planning turn before the goal row exists: a message that never becomes
a goal leaves no row.

Bounds:

* At most 6 steps and 10 model calls.
* 30 s per step and 120 s per goal.
* One retry, for lookups only.
* The same call twice in a row is stagnation and stops the goal.
* The final answer is built from the steps' own results. A failure says exactly what failed and what (if anything)
  was already done.

### A5. Performance baseline (measured, host)

| Measurement | Value | Source |
|---|---|---|
| 1B-shape Q4_K_M, 2 threads: prefill / generation | 85.6 / 10.7 tok/s; first token 2.5 s for 217 tokens | `docs/measurements/m5_linux_1b-q4km.json` |
| 1B-shape: process memory loaded / peak | 1,521 / 1,558 MB | same |
| Chat turn, time to first token with cached prefix (135M) | 316–352 ms | CLU_PERFORMANCE_REPORT.md |
| Goal planning turn, Qwen3-0.6B, 4 threads | see B3 | `docs/measurements/goal_eval_qwen3_0.6b.json` |

### A6. Hardware feasibility

* **Xiaomi Pad 6** (Snapdragon 870, 8 GB) as the primary host: a 1.5–3B model at Q4 fits in memory (≈2.6 GB for a 3B
  model with 4k context, an estimate from file size and KV-cache arithmetic). By the same CPU-scaling estimate,
  planning adds roughly 3–10 s per message there. **Unmeasured: no device here.**
* **What the evaluation shows.** A 0.6B model classifies kinds reasonably well but often fails to produce valid
  multi-step plans, and it hallucinates when planning fails (B3). Intent-driven autonomy on the tablet needs at least
  a ~3B instruct model (for example Qwen2.5-3B-Instruct, already the documented default in COMPATIBILITY.md). That
  could not be evaluated here because the download host is blocked.
* **Phone** (iQOO Neo 9 Pro): a client of the Pad or the PC. It can run the same core standalone.
* **The laptop** (32 GB): development and benchmarking. Nothing in this work makes it a required server.

### A7. Licensing

Unchanged by this work: no new third-party code or models. The bundled components and their licences are listed in
`installer/THIRD_PARTY_NOTICES.txt`:

* OpenJDK: GPLv2 with the Classpath Exception.
* llama.cpp: MIT.
* Kotlin libraries: Apache 2.0.
* SQLite JDBC: Apache 2.0.
* LiveKit server and SDKs: Apache 2.0.
* sherpa-onnx: Apache 2.0, including espeak-ng (GPL-3.0-or-later).
* ONNX Runtime: MIT.
* Speech models: Silero MIT; Piper MIT/CC0; KittenTTS Apache 2.0.
* The Kroko ASR model card describes CC-BY-SA community models. Check before commercial use.

Model licences are shown before download (for example the Qwen and Llama community licences, which are not
open-source licences).

### A8. Security

* **Actions only on instruction.** Questions, chat and thinking aloud may look things up; any action step in such a
  plan is removed. A musing brake overrides a small model that mislabels a reflection as an instruction.
* **Argument grounding (new).** An action's text must share words with the owner's message. A calendar date in an
  action or in `date.calc` must be the owner's own or come from an earlier step. A guessed date is refused, and the
  planner is told to look it up. A random or confused plan cannot invent an action (tested with a "Message number 3"
  prompt).
* **Prompt injection.** Tool and web text can fill a data field of an already-fixed plan, but never choose a tool or
  add a step. An extracted value is grammar-limited to one short line and must be a real date when a date was asked
  for (tested with an "IGNORE PREVIOUS INSTRUCTIONS…" page).
* **Claims.** A reply cannot claim an action that did not run (`ActionClaims`).
* Permissions, confirmations, per-tool settings and the audit trail are unchanged and still apply to goal steps.
* Secrets: the release key stays outside the repository and is used through environment variables. Nothing secret
  is committed.

### A9. UI regression

* **What changed.** CLU 1.12.0 (`270d3af`, 9 Nov 2026) replaced the hexagonal HUD with CLU's eyes (`HexHudView`,
  introduced 1.3.0 and refined in `70cfd31`) by a particle "entity" (`EntityView`), which reads as an egg-shaped head.
* **Restored.** `HexHudView` is back exactly as at `270d3af^`, on the main and voice screens, with the same modes and
  audio level. `EntityView` is removed.
* **Tests.** The HUD tests were brought back to the hexagon: it draws every state, it is decoration (not clickable),
  and frames stay cheap. The PC and tablet shell (Primus core) were not affected by that commit.

### A10. Recovery architecture

* **Goals.** After a restart, every unfinished goal is marked RECOVERING. "Continue the task" resumes it from its
  last checkpoint:
  * finished steps are not repeated;
  * a lookup that was running is run again;
  * an action that was running is **not** repeated blindly, because it may already have happened. The goal stops and
    says so.
* **M7 supervised tasks** keep their own recovery (`agents.recoverAfterRestart`).
* **Model trouble.** Timeouts, empty output and cancellation already end a turn with a clear error and a retry
  (LlmReliabilityTest).
* **Context exhaustion.** History is trimmed at a stable point so the prompt cache survives (VoiceLatencyTest).

---

## Part B — What was built, tested and measured

### B1. Built

* `core/agent/GoalEngine.kt`: GoalEngine, GoalStore (durable state machine), plan grammar, validation (registry,
  references, authorization, grounding, completeness) and execution with budgets.
* CluAgent integration:
  * understanding before phrase routing;
  * a planning turn on the chat's cached prefix;
  * resume via "continue the task";
  * goal progress shown as task steps.
* `LlmEngine.supportsGrammar` (llama.cpp engines and the paired home device: yes; anything else: no, so CLU keeps
  phrase routing there).
* Setting `agent.understanding` (model / rules), under Tools, advanced.
* `date.calc` tool; `Butler.parseDate`; weekday and "tomorrow" reminders default to 9 am; a clear message for past
  dates.
* `ActionClaims` guard against unbacked action claims.
* `HexHudView` restored; `EntityView` removed; tests updated.
* `GoalEvaluation`: an opt-in real-model evaluation through the production path.

### B2. Tests (automated)

* **GoalEngineTest (14 tests):**
  * Diwali: look up, extract, compute, remind, with the reminder verified in the store;
  * Rahul;
  * acceptance test 1 (busy Thursday);
  * thinking aloud never acts (three variants);
  * questions may look up but never act;
  * a failing lookup is retried once and then reported honestly;
  * missing information is not invented;
  * page text fills data but never chooses actions;
  * resume after restart from the checkpoint;
  * an in-flight action is not repeated;
  * budgets, stagnation and time limits;
  * plan validation and grounding;
  * unbacked claims are corrected;
  * phrase routing still works without a grammar model.
* GoalSettingsTest (the new setting). ButlerTest additions for dates and weekdays.
* Full-suite results are in B5.

### B3. Real-model evaluation (Qwen3-0.6B Q4_K_M, host, 4 threads)

Production path: CluCore, InferenceService, llama.cpp with grammar, GoalEngine, and the real reminder, list and date
tools. Web search was a fixture page, and the clock was fixed at Thursday 9 October 2025, 10:00. Effects were checked
in the reminder store, not in the reply. The 14 messages are paraphrases that do not appear in the planner's examples,
and include acceptance tests 1 (verbatim) and 5. Raw data: `docs/measurements/goal_eval_qwen3_0.6b.json`.

| Metric | Result |
|---|---|
| Task success (the right effect, or correctly no effect) | **9 / 14** |
| Unwanted actions (anything changed for a question, chat or thinking aloud) | **0 / 7** |
| Correct kind | 9 / 14 (two questions labelled "instruction", harmlessly; one musing labelled "chat") |
| No valid plan after one repair | 3 / 14 |
| Planning turn: median / p95 | **4.7 s / 9.3 s** |
| Whole turn: median / p95 | 7.4 s / 20.0 s |
| Model load | 0.9 s (372 MB) |

| Case | Outcome |
|---|---|
| assignment ("due on 25 October, remind me two days before") | ✓ goal `date.calc → reminder.set`, reminder Thu 23 Oct 09:00 |
| call mom at 7 pm today; add milk and eggs to the shopping list | ✓ |
| three thinking-aloud and two question messages, one chat | ✓ nothing changed |
| Diwali (look up, then remind) ×2 | ✗ the model never chose `web.search`. It wrote guessed dates, which grounding refused, so nothing was set and nothing false was claimed |
| Rahul (tomorrow at 3, an hour before) | ✗ no valid plan. The chat model then claimed "I'll remind you…" and **the new claim guard corrected it** |
| Test 1 busy Thursday | ✗ the planner invented a "Dentist" calendar event, which grounding refused; then no action |
| Test 5 exam | ✗ instruction with no steps; the answer quoted the exam date from memory but set nothing |

**Reading.** With a 0.6B model the safety properties hold: no unwanted action, no invented action got through, and no
unbacked claim stands. Simple instructions and computed dates succeed. Multi-step lookup-then-act planning is beyond
this model. The same pipeline passes those cases when the planner is capable (scripted plans in GoalEngineTest).
Whether a ~3B model on the Pad 6 reaches that is the most important open measurement.

Before the fixes in A3 #7, #8 and #11 (the same evaluation, first run), the chat path answered in JSON on several turns
and a passed date produced a misleading error. Those defects were found by this evaluation and are fixed.

### B4. Acceptance tests 1–10: honest status

| Test | Status | Evidence / gap |
|---|---|---|
| 1 Natural-language understanding (busy Thursday, assignment) | **Pipeline passes with a capable planner** (scripted); real 0.6B result in B3 | GoalEngineTest `busyNextThursdayAssignment`; GoalEvaluation `t1-busy-thursday` |
| 2 Implicit research ("Moon disappeared") | Not re-evaluated in this work; the research decision exists | Needs live network and a stronger model |
| 3 Multi-source research synthesis | **Partial** (see A2) | No live sources here |
| 4 Context continuity ("continue what we were discussing yesterday") | Existing project context and memory recall; not re-measured | MemoryStage1Test, ContextMemoryTest |
| 5 Dynamic tool selection (exam, preparation time) | Pipeline supports memory lookup, extraction, `date.calc` and a reminder; real 0.6B result in B3 | GoalEvaluation `t5-exam` |
| 6 Research, compare, write a document | **Not built**: no document tool in the chat registry | — |
| 7 Error recovery (tool failure, network, timeout, context, restart) | Tool failure, restart, time budget: **tested**. Model timeout and context exhaustion: existing tests | GoalEngineTest; LlmReliabilityTest; VoiceLatencyTest |
| 8 Speech interaction | Not re-tested in this work; voice is turn-based | M8 measurements |
| 9 Long-running autonomous task | Bounded, persistent, verified goals: **tested with scripted plans**; long real-model goals unproven | GoalEngineTest |
| 10 Generalization (unseen paraphrases) | Evaluation messages are paraphrases absent from the planner examples | B3 |

### B5. Builds and full test suites

| Suite | Result |
|---|---|
| core | 742 tests: 741 passed, 17 opt-in skipped, **1 failed under full-suite load**: `InferenceServiceTest` "a request that runs past its limit…". On the loaded machine, the tiny test model finished before the watchdog saw the advanced clock. The same class passed 3 of 3 runs on its own. Recorded as a timing flake; not caused by this work, and not hidden |
| app (Robolectric) | 99 tests passed, including the restored HUD tests and the regenerated settings register (305 settings, each with a passing verifying test) |
| desktop (with shell UI checks in Node and Playwright) | 144 tests, 0 failures, 8 opt-in skipped |
| ICU regex check (Android's regex engine) | 683 patterns, 0 rejected. It caught one new pattern (`{{sN}}`) that would have crashed on Android; fixed before release |
| APK | `dist/clu-release.apk`, 1.15.0 (versionCode 32), signed v1+v2+v3 and verified by the build; `verifyApk`: 40 manifest components, 377 JNI externals OK |
| Windows installer | `CLU-Setup-0.13.0.exe` (see SHA256SUMS.txt), built with `scripts/build_installer.sh`, static checks with `scripts/verify_installer.sh`; **not run on real Windows** |

### B6. What is not done (prioritised)

1. **A stronger on-device model.** This is the largest single factor. Evaluate Qwen2.5-3B-Instruct (or similar) on
   the Pad 6 with this evaluation, once a download is possible.
2. Document-writing tool for the chat agent (Test 6), with the M7 never-overwrite rule.
3. One tool registry (merge M7's `AgentToolRegistry` and the chat `ToolRegistry`).
4. Continue a goal after the owner confirms a step. Today, confirming runs that step only; the goal stays
   WAITING_FOR_EXTERNAL_RESULT.
5. Full-duplex voice (it is turn-based with barge-in).
6. Live multi-source research evaluation (blocked here by network policy).
7. Device validation on the Pad 6 and the Neo 9 Pro: nothing in this report ran on them.

---

# Phase 2 — Intelligence and execution quality (20 November 2026)

## P0. What this phase could and could not measure

| Asked for | Done here? | Why |
|---|---|---|
| Download and compare stronger models (Qwen2.5-3B, Qwen3-4B, Gemma 3 4B, …) | **No** | This container's network policy blocks Hugging Face (`CONNECT tunnel failed, 403`). Allowing it needs an environment change; the approval prompt couldn't be shown in the app version used |
| Controlled comparison on the 32 GB laptop | **No** | No computer is attached to this session (no shared folders) |
| Load time, RAM, first token, tok/s, thermal, battery on the Xiaomi Pad 6 | **No** | No device is reachable from here |
| Live research (search, open pages, cite) | **No live test** | Search providers are blocked by the same policy; the evaluation uses a topic fixture |
| Everything else | Yes | Measured with the only real instruction model available: **Qwen3-0.6B Q4_K_M**, on the 4-vCPU build host |

Following the release rule of this phase, **no release was made**: a stronger model has not been shown to improve
CLU, and the 0.6B results below are not a basis for one. The code is on the branch; the published 1.15.0 APK and
0.13.0 installer are unchanged.

## P1. Candidate models (from their published model cards; not verified in this session)

| Model | Licence | Context | Tool use | Android fit (Pad 6, 8 GB) |
|---|---|---|---|---|
| Qwen3-0.6B (measured here) | Apache-2.0 | 32k | Template supports tools | Fits easily (372 MB); too weak, see P3 |
| **Qwen2.5-3B-Instruct** (the documented default) | **Qwen Research License, not Apache-2.0** (the one Qwen2.5 size besides 72B under it) | 32k | Template supports tools | ~2.1 GB Q4_K_M; fits |
| Qwen3-4B / Qwen3-4B-Instruct-2507 | Apache-2.0 | 32k native (longer with YaRN; 256k for 2507) | Yes (native tool template); 2507 has no thinking mode, which suits CLU | ~2.5 GB Q4_K_M; fits with a 4k context |
| Gemma 3 4B (instruction-tuned) | Gemma Terms of Use (custom, with a prohibited-use policy) | 128k | No dedicated tool tokens (prompted) | ~2.5 GB Q4_K_M; fits |
| Phi-4-mini-instruct (3.8B) | MIT | 128k | Function calling supported | ~2.4 GB Q4_K_M; fits |
| Llama 3.2 3B Instruct | Llama 3.2 Community License | 128k | Yes | ~2.0 GB; fits |

**Recommendation to evaluate first:**
* **Qwen3-4B-Instruct-2507**: permissive licence, no thinking overhead, strong tool use for its size.
* **Phi-4-mini-instruct**: MIT licence.

The current recommended default carries a restrictive licence. The Android catalog now says so (the PC wizard
already did). Whichever model is chosen, the decision must follow the measurements in P6, not this table.

## P2. Why plans failed (diagnosis from the stored refusals of every planning call)

| Suspected cause | Finding |
|---|---|
| Model capability | **Main cause.** The 0.6B model classifies most messages correctly (38/44 kinds in the final run), but it cannot reliably compose a multi-step JSON plan. It copies example values, and it rarely decides on its own to look something up |
| Prompt construction | **Contributing.** Every concrete example anywhere in the prompt was copied: the planner's examples ("Chennai marathon"), the tool list's argument examples ("call Ravi", "milk", "tomorrow 9 am"), and a time hint ("12 March"). Grounding refused each copy, so nothing wrong was done, but the task failed |
| Tool descriptions | **Contributing.** "Set a reminder." vs "Add to a list" gave no distinguishing cue; the model used the list for reminders |
| Context truncation | Not a cause: the planning turn reuses the cached prefix and fits easily in 4k |
| Schema complexity | **Contributing.** One whole plan with references (`{{s2}}`) and extraction steps was too much for this model |
| Planning architecture / validation | Validation worked as designed: 0 unwanted changes in every run. Its strictness turned the model's copies into honest failures, never into wrong actions |

## P3. What changed (no phrase is hard-coded; every change applies to any model)

1. **Stepwise planning (new default `agent.planner = stepwise`).** One constrained call answers only:
   * the kind of message;
   * the facts it needs that the owner didn't give;
   * the single tool that does what was asked.

   The engine then looks each fact up itself (the owner's memory first, then the web), using a grammar-limited
   extraction. Only after that does the model fill the tool's arguments, with the facts in hand. The whole-plan
   planner stays available (`plan`) for stronger models and multi-tool requests.
2. **Vocabulary-constrained arguments.** The fill grammar admits only:
   * the owner's own words and the facts found;
   * for times, also time words and numbers;
   * month names only if someone actually said them.

   Copying an example or inventing a name or date becomes impossible rather than merely refused.
3. **A yes/no intent check before any action** ("Does this message ask you to …?"). It stopped a greeting from being
   turned into a reminder (measured).
4. **A focused tool choice** when the model says "instruction" but names no tool (only the action tools are offered).
5. **Anchoring.** "two days before" / "an hour before it" gets the moment that was named (a fact found, or the single
   date or time in the owner's words). The reminder parser now understands:
   * "N days/hours before X";
   * "the day before X";
   * "the night before X".
6. **Context.** A goal's objective includes the owner's previous two messages from the last 30 minutes. "Remind me the
   day before" after "My dentist appointment is on 30 October at 11 am" now works (2/2).
7. **Grounding of lookups.** A fact to look up must be about something in the message (copied examples are dropped).
8. **Tool descriptions and argument examples.**
   * Clearer descriptions: "Alert the owner at a time (remind me, don't let me forget)" and "Add an item to a list
     (shopping, to-do); no alert".
   * Argument examples now describe the fields instead of carrying copyable values.
   * Tool lines are more compact (`- name {args}: description`), so the system prompt did not grow (the 2,000-token
     window test still passes unchanged).
9. **Questions the model can't answer are researched.** When the model names a missing fact and no tool, the agent's
   research path runs (search, relevance checks, sources) instead of answering from the model's memory.
10. **Task continuation (requirement 4).**
    * When a goal step needs the owner's yes, the goal waits (WAITING_FOR_EXTERNAL_RESULT). On "yes" the step runs once
      and the **rest of the goal continues automatically**; completed steps are never repeated.
    * On "no", the goal is cancelled and says what was and wasn't done.
    * After a restart, a step that was waiting is asked again, never run blindly.
11. **Response quality (requirement 7).**
    * A reply that hits the token limit (`max_tokens`, default 400) is now **continued** (up to twice, within the
      context window). Before, long answers stopped mid-sentence: the stop reason was never checked.
    * Spoken replies stay short.
    * Malformed tool calls (`[tool]…[/tool`) the model sometimes writes into chat are removed from the answer.

## P4. Real-model results (Qwen3-0.6B Q4_K_M, build host, 4 threads, 4k context)

The production path is the same as in Phase 1. Reminders, lists and tool calls are checked in the stores, never in the
reply. There are 22 cases: the original 14 plus 8 new ones (paraphrases, an indirect instruction, a context-dependent
instruction, multi-source research, a multi-tool request and one more musing). The Phase 2 criteria are stricter than
Phase 1's: questions must give the right answer, and research questions must actually search.

| | Phase 1 (whole plan) | Phase 2 whole plan (`plan`) | **Phase 2 stepwise (default), 2 runs** |
|---|---|---|---|
| Tasks right | 9/14 (Phase 1 criteria) | 9/22 | **26/44 (59 %)**, the same 13/22 in both runs |
| Original 14, Phase 1 criteria | 9/14 | — | 10/14 |
| Unwanted changes | 0 | 0 | **0** |
| No valid plan | 3/14 | 6/22 | **0/44** |
| Correct kind | 9/14 | 14/22 | 38/44 |
| Understanding, median / p95 | 4.7 s / 9.3 s | 4.5 s / 5.8 s | 4.5 s / 5.3 s |
| Whole turn, median / p95 | 7.4 s / 20.0 s | 7.8 s / 15.3 s | 6.5 s / 8.2 s |

By group (stepwise, 2 runs):

| Group | Right |
|---|---|
| no-action (musing, chat) | 12/12 |
| simple actions | 6/8 |
| paraphrases | 4/6 |
| context-dependent | 2/2 |
| research | 2/4 |
| look-up-then-act | 0/4 |
| acceptance tests 1 and 5 | 0/4 |
| indirect | 0/2 |
| multi-tool | 0/2 |

**Individual failures (stepwise, final runs):**
* **Diwali** (both runs): the lookup worked (web, then extraction "20 October 2025") and the reminder was set for the
  correct day, 17 October. But the model filled *what* with "Diwali's coming up", so the reminder text is wrong.
* **Diwali, clean the house**: run 1 wrote "two days before" with nothing to anchor it (the lookup was skipped).
  Run 2 set the right date (18 October) with the text "set".
* **Rahul**: "3 pm" today instead of "an hour before tomorrow at 3 pm". The model dropped both the day and the offset.
* **Test 1, busy Thursday**: reminder set, but with *what* = "sure" at 10:00 tomorrow (wrong text; the date is
  acceptable).
* **Test 5, exam**: the model chose the calendar tool. There is no calendar on the build host, so it failed honestly.
* **Vitamins tonight**: the planner invented a lookup ("date of the next vitamin registration"). Lookup grounding let it
  through because it shares the word "vitamin" with the message. The lookup found nothing, so the goal failed honestly
  and nothing was set.
* **Sister's birthday** (indirect): no tool chosen; the model replied conversationally.
* **Multi-tool** (eggs + reminder): stepwise handles one action per message, and the model filled the list entry
  badly.
* **When is Diwali?**: classified as an instruction with no lookup, so the model said it didn't know. The world-cup
  question was researched correctly.

**Reading.**
* The architecture changes removed every invalid plan and every copied value, and kept unwanted actions at zero.
* Context, paraphrase and simple actions improved clearly.
* What remains is the 0.6B model choosing poor words and the wrong tool.
* This is model capability, and no further prompt tuning against this one model was done: each further change only
  moved which cases failed (observed in three intermediate runs, also kept in the logs).
* The decisive next measurement is the same evaluation with a 3–4B model.

Raw data:
* `docs/measurements/goal_eval2_qwen3_0.6b_stepwise.json` (2 runs)
* `docs/measurements/goal_eval2_qwen3_0.6b_plan.json`
* Phase 1: `goal_eval_qwen3_0.6b.json`

## P5. Acceptance tests and recovery (automated, scripted model)

Pipeline tests with a scripted planner, so the engine is tested independently of model quality:

* **StepwiseGoalTest (15 tests):**
  * Diwali (memory, web, action);
  * Test 1;
  * Test 5 from memory, with no web search;
  * an invented detail sent back and refilled;
  * details the tool can't use, refilled;
  * questions researched;
  * thinking aloud never acts;
  * a misread greeting stopped by the intent check;
  * focused tool choice;
  * owner-words-only grammar;
  * anchoring;
  * **continuation after "yes" and cancellation after "no"**;
  * malformed tool calls removed;
  * **a reply cut at the token limit continued to its end**.
* **GoalEngineTest (15 tests, whole-plan mode):** restart and resume from the checkpoint; an action caught by a
  restart is not repeated; budgets and stagnation; tool failure with one retry; injection.
* Model timeout and context exhaustion: LlmReliabilityTest, VoiceLatencyTest, InferenceServiceTest (unchanged, pass).

**Full suites after this phase:** core 758 tests, 0 failures, 17 opt-in skipped; app 99, 0 failures; desktop 144, 0 failures,
8 opt-in skipped. ICU regex check: 690 patterns, 0 rejected. The settings register was regenerated with `agent.planner`.

## P6. How to finish the model decision (needs your hardware or network)

1. **On the laptop** (or here once Hugging Face is allowed), for each candidate GGUF:
   ```
   ./gradlew :core:test --tests ai.clu.core.GoalEvaluation -Dclu.goalModel=<file.gguf> -Dclu.goalRuns=2 \
       -Dclu.goalOut=docs/measurements/goal_eval_<model>.json
   ./gradlew :core:test --tests ai.clu.core.LlmLatencyBenchmark -Dclu.llmBenchModel=<file.gguf>
   ```
   Compare task success, unwanted changes (must stay 0), planning p95 and load time.
2. **On the Pad 6**, with the chosen model: the app's diagnostics already record model load, time to first token,
   tok/s and per-turn timings (Settings → Diagnostics). Add a 30-minute conversation for thermal throttling and
   battery drain.
3. **Release** only if the chosen model clearly beats Qwen3-0.6B on this evaluation, with zero unwanted changes.


---

# Phase 3 — Model capability, failure attribution and grounding (21 November 2026)

## Q0. What could and could not be done

| Asked for | Status | Reason / evidence |
|---|---|---|
| Evaluate Qwen3-4B-Instruct-2507, Phi-4-mini-instruct and others | **Not done** | Hugging Face and its mirrors are unreachable from this container (proxy refusal, re-checked this phase). The environment change that would allow them could not be approved from the Claude app version in use. Disk: 2.1 GB free, below a 4B Q4 file (≈2.5 GB) |
| Laptop comparison, Xiaomi Pad 6 measurements | **Not done** | No computer or device is attached to this session |
| Live research against real websites | **Not done** | Search providers and Wikipedia are blocked the same way; research is exercised with a topic fixture shaped like the real search tool's output |
| Model licences and suitability | **Unverified here** | Phase 2 section P1 lists what the model cards say; nothing in this report claims a model is suitable |
| Everything below | Done and measured | With the one real instruction model available (Qwen3-0.6B Q4_K_M) and with oracle substitution |

No APK or installer was released.

## Q1. Where failures come from: stage-by-stage oracle substitution

**Method.** Every case has gold answers for each model stage:
* the understanding (kind, missing facts, tools);
* the focused tool choice;
* the intent check;
* each tool's arguments;
* the fact extracted from a lookup.

The production path runs three times, with real tools and stores, and the real model wherever the oracle doesn't
answer:
* **model alone** (`none`);
* **oracle understanding** (`understand`: gold understanding, tool choice and intent check);
* **full oracle** (`full`: gold arguments and extractions too).

How a failure is attributed:
* A case that passes only with oracle understanding failed at understanding. The model's raw answer is compared with
  gold to name the sub-stage: intent, missing-information detection, tool selection or intent check.
* A case that needs the full oracle failed at argument generation or fact retrieval.
* A case that fails even with the full oracle failed in **CLU's own** orchestration (execution, verification, routing,
  response).

Code: `GoalEvaluation` (`-Dclu.goalOracle=none,understand,full`). Data:
* `docs/measurements/goal_eval3_qwen3_0.6b_oracle.json` (final code);
* `…_oracle_prefix.json` (before the Q2 fixes).

**30 cases.** These are the 22 from Phase 2 plus 8 new ones:
* a commitment;
* a hypothetical;
* a dependent multi-tool task (look up, add to list, remind);
* a document written from research;
* an indirect intention (insurance that "runs out" and was "let lapse before");
* context resolution ("Put those on the shopping list");
* a paraphrase ("ping me at 6:30 this evening…");
* an open question.

| | Model alone | Oracle understanding | Full oracle |
|---|---|---|---|
| Before the Phase 3 fixes | 12/30 | 14/30 | 26/30 |
| **Final code** | **14/30** | **15/30** | **29/30** |
| Unwanted changes (every run) | 0 | 0 | 0 |
| Correct kind (final) | 22/30 | 30/30 | 30/30 |
| Whole turn, median / p95 (final) | 7.8 s / 9.9 s | — | — |

Phase 2's comparable figure was 13/22 per run on its 22 cases; the 30 cases here include harder ones (multi-tool,
document, indirect, context resolution).

| Group | Model alone | Oracle understanding | Full oracle |
|---|---|---|---|
| acceptance | 0/2 | 0/2 | 2/2 |
| act | 2/4 | 2/4 | 4/4 |
| context | 1/2 | 1/2 | 2/2 |
| document | 0/1 | 1/1 | 1/1 |
| indirect | 0/2 | 2/2 | 2/2 |
| lookup+act | 0/2 | 0/2 | 2/2 |
| multi-tool | 0/2 | 0/2 | 2/2 |
| no-action | 5/5 | 5/5 | 5/5 |
| paraphrase | 1/4 | 0/4 | 4/4 |
| question | 1/2 | 0/2 | 1/2 |
| research | 2/2 | 2/2 | 2/2 |
| statement | 2/2 | 2/2 | 2/2 |

**Reading.** With every model stage answered correctly, CLU completes 29 of 30 tasks:
* look-up-then-act;
* two dependent tools in one message;
* conversation context;
* indirect intentions;
* a document written from research;
* questions, statements and musings, which never act.

The one full-oracle failure is a free-text answer: the Moon answer didn't mention tides. In the previous run the
failure was a different free-text answer instead ("Sydney" for Australia's capital).

**Run-to-run variation.** The model-alone result was 15, 15 and 14 of 30 over the last three runs on near-final code,
so the differences between those runs are noise in free-text answers, not in task execution. **After the fixes, no failure is attributed to CLU's orchestration.**

The 16 model-alone failures:
* **argument generation: 12**
* **tool selection: 2**
* **missing-information detection: 1**
* **response generation: 1**

The bottleneck is a 0.6B model choosing the words and times of correctly chosen tools. Whether a stronger model removes
it is what the stronger-model evaluation must show; **it has not been measured**.

### Failure attribution, model alone (final code)

The stage named is the one whose oracle makes the case pass; an earlier stage may also have been wrong. For example,
in *diwali-clean* the model also failed to ask for the Diwali date, and with that supplied it still wrote an unusable
time, so it needs the full oracle.

| Case | Failed check | Stage (attributed) | Passes with oracle understanding | Passes with full oracle | What the model produced |
|---|---|---|---|---|---|
| diwali | reminder | argument generation | no | yes | {"what": "Diwali's coming up", "when": "3 days before it"} |
| diwali-clean | reminder | argument generation | no | yes | {"what": "set a reminder", "when": "two days before"} |
| rahul | reminder | argument generation | no | yes | {"what": "I have a meeting with Rahul tomorrow at 3 pm", "when": "3 hours before tomorrow at 3 pm"} |
| shopping | items | argument generation | no | yes | {"list": "Add milk and eggs to my shopping list"}; {"title": "milk"} |
| t1-busy-thursday | reminder | argument generation | no | yes | {"kind": "instruction", "goal": "register for marathon", "lookup": [], "tools": ["reminder.set"]} |
| t5-exam | reminder | argument generation | no | yes | {"kind":"instruction","goal":"find next examination date","lookup":["next examination date"],"tools" |
| para-vitamins | reminder | argument generation | no | yes | {"what": "vitamins", "when": "9:00"} |
| para-groceries | items | argument generation | no | yes | {"kind": "instruction", "goal": "set reminder for shopping list", "lookup": [], "tools": ["reminder. |
| indirect-birthday | reminder | tool selection | yes | yes | {"kind": "instruction", "goal": "check birthday date", "lookup": [], "tools": ["none"]} |
| multi-tool | reminder, items | argument generation | no | yes | {"title": "shopping", "list": "Add eggs to the shopping list"} |
| multi-dependent | items | argument generation | no | yes | {"what": "shopping list", "when": "3 days before 20 October 2025"} |
| doc-research | document | missing-information detection | yes | yes | {"kind": "instruction", "goal": "create a write-up on Diwali", "lookup": [], "tools": ["none"]} |
| indirect-insurance | reminder | tool selection | yes | yes | {"kind": "instruction", "goal": "update car insurance status", "lookup": [], "tools": ["none"]} |
| context-list | items | argument generation | no | yes | {"list": "shopping"}; {"title": "shopping", "list": "milk bread coffee"} |
| para-water | reminder | argument generation | no | yes | {"what": "water the plants", "when": "6:30 this evening"} |
| question-moon | reply | response generation | no | no | {"kind": "instruction", "goal": "determine the impact of the Moon disappearing", "lookup": [], "tool |

## Q2. CLU defects found by the oracle runs (fixed and tested)

1. **Irrelevant evidence.** "Diwali 2025" matched an exam memory on "2025", and that text was used as the Diwali evidence
   (the document said "14th of November (your memory)"). A source must now mention a distinctive word of the fact (not
   "date", "next" or a number) before anything is extracted from it
   (`StepwiseGoalTest.anUnrelatedRecordIsNotEvidenceForAFact`).
2. **Weak lookup grounding.** A copied example lookup ("date of the next Chennai marathon") passed because it shared
   "date" with the message. Generic words no longer count.
3. **Understanding unrelated to the message.**
   * A plan whose own restatement of the goal shares no distinctive word with the message no longer drives actions. This
     covers copied examples such as "marathon registration reminder" for a Diwali message, and noise.
   * Found because the real-inference plumbing tests use a random-weight model: its grammar-valid but meaningless plans
     occasionally started a goal (an action, or two read-only tools), making two tests intermittently fail. The check
     covers every goal step, and those tests are now stable (5/5 runs)
     (`anUnderstandingUnrelatedToTheMessageDrivesNoAction`).
4. **Extraction echo.** An extraction that repeats the question ("Wanted: Diwali 2025") is no longer accepted as a value.
5. **A regression introduced this phase, then reverted.** Offering word forms, generic verbs and task verbs in the
   argument *generation* vocabulary made the 0.6B model write "go to mom", "vitaminss" and "submit". Generation is back
   to Phase 2's vocabulary (the owner's words and facts). Paraphrase acceptance now lives in provenance validation (Q3).
6. **Schema regression, caught before measuring.** An optional `"tools": []` list was always left empty by the 0.6B
   model. The list now needs an explicit entry, with `"none"` allowed.
7. **An evaluation defect, not a CLU one.** Research questions failed even under the full oracle because the search
   fixture lacked the source records the real search tool returns; the research path rightly judges relevance on them.
   The fixture now has the real shape.

## Q3. Semantic grounding with provenance

* Every argument of every tool step now records where it came from, persisted with the goal (`GoalStep.provenance`):
  * `owner`: this message;
  * `context`: the owner's earlier messages, last 30 minutes;
  * `lookup:sN`: a verified lookup, its value or the source text it was read from;
  * `calc`: a deterministic calculation, such as anchoring "two days before" to a date, or time words;
  * `derived`: an obvious task verb in a note to the owner ("renew" the insurance that "runs out");
  * `composed`: text CLU wrote from the evidence, such as a document's content.
* **Accepted as paraphrase:** word forms ("submission" → "submit", "prepare" → "preparing"), connecting words ("to",
  "your") and generic verbs ("start", "remember").
* **Refused:** a value with any content word that traces to nothing (an invented name, place or date). Derived task
  verbs are allowed only in notes to the owner (reminders, lists), never in text sent to anyone.
* Tests: `everyArgumentRecordsWhereItCameFrom` (the reminder date is `lookup:s1` + `calc`) and
  `paraphraseIsAllowedButInventionIsNot`.

## Q4. Multi-tool tasks, statements, documents

* **Dependent tools.** One message can drive up to two tools in order, after its lookups:
  * Each action gets its own intent check, and an action the message doesn't ask for is dropped while the others run
    (`anActionTheMessageDoesNotAskForIsLeftOutOfAMultiToolPlan`).
  * Measured: "Look up when Diwali is, put diyas on my shopping list and remind me to buy them three days before"
    completes with the full oracle (lookup, list entry, reminder on 17 October). With the 0.6B model alone it does not
    (argument generation).
* **Statements.** A new message kind `statement` covers facts, plans, commitments and hypotheticals. Like thinking aloud,
  it never acts (2/2 in every mode).
* **`document.write` (new tool).**
  * Saves a new Markdown document in the owner's archive and never overwrites ("Diwali (2).md").
  * Its content is composed from the evidence found, with each fact's source in brackets.
  * Verified on both platforms' APIs: the Android path (`ShellHostTest.aDocumentCluWritesOnThisDeviceAppearsInItsDocumentsView`)
    and the PC (`ShellApiTest.aDocumentCluWritesAppearsInThePcDocumentsView`); listed and searchable on both.
  * Not run on a physical Android device.

## Q5. Recovery, confirmation and context exhaustion (automated, scripted model)

* Restart and resume from the checkpoint, and no blind repeat of an action caught by a restart: GoalEngineTest.
* Confirmation, then automatic continuation of the remaining steps; a declined step cancels: StepwiseGoalTest.
* **New:** a goal runs correctly when the conversation has outgrown a 1,600-token window, with every planning prompt
  inside it (`aGoalStillRunsWhenTheConversationHasOutgrownTheContextWindow`).
* Model timeouts and request limits: InferenceServiceTest, LlmReliabilityTest.

## Q5b. Test results (final code)

| Suite | Result |
|---|---|
| core | 768 tests, 0 failures, 17 opt-in skipped (StepwiseGoalTest is now 24 tests) |
| app | 99 tests, 0 failures |
| desktop | 145 tests, 0 failures, 8 opt-in skipped |
| ICU regex check (Android) | 694 patterns, 0 rejected |
| Real-inference plumbing tests (random-weight model) | 5/5 clean runs after the Q2 #3 fix; before it they failed intermittently |

## Q6. Reproducible commands for what this environment could not do

On the owner's laptop (or here once Hugging Face is reachable):
```
# 1. Models (licences: check each model card before use; repository names were not verifiable from here and may change)
huggingface-cli download unsloth/Qwen3-4B-Instruct-2507-GGUF --include "*Q4_K_M*" --local-dir models
huggingface-cli download unsloth/Phi-4-mini-instruct-GGUF --include "*Q4_K_M*" --local-dir models
# 2. The same 30-case evaluation with oracle attribution, per model (2 runs for variance)
./gradlew :core:test --tests ai.clu.core.GoalEvaluation -Dclu.goalModel=models/<file>.gguf \
    -Dclu.goalOracle=none,understand,full -Dclu.goalRuns=2 -Dclu.goalOut=docs/measurements/goal_eval3_<model>.json
# 3. Speed and memory on the laptop
./gradlew :core:test --tests ai.clu.core.LlmLatencyBenchmark -Dclu.llmBenchModel=models/<file>.gguf
```

**Release gate for a model:**
* model-alone success clearly above Qwen3-0.6B's 14–15/30;
* zero unwanted changes;
* multi-tool and lookup-then-act cases passing without the oracle.

Then measure on the **Pad 6** with the app's diagnostics (model load, first token, tokens/s, RAM, a 30-minute session
for heat and battery) before any recommendation is published. Live research then needs a session with web access:
the questions in the evaluation's research group, plus a multi-source one, checking that pages were opened (sources
marked "opened") and that claims cite them.

# Phase 4 — Real-world validation and model qualification (19 November 2026)

## R0. Status against the release criteria

| Criterion | Status | Evidence |
|---|---|---|
| Stronger model evaluated, or unavailability documented | **Unavailability documented** | Re-checked this phase: `huggingface.co`, `hf-mirror.com`, `modelscope.cn` all refused by this container's proxy (`CONNECT tunnel failed, 403`). An environment allowing Hugging Face, Wikipedia and DuckDuckGo was proposed; the Claude app version in use cannot show the approval, so nothing changed. Disk: 2.4 GB free after removing already-extracted archives; a 4B Q4_K_M file is ≈2.5 GB, so no model was squeezed in |
| Device stability tested | **Not done** | No tablet attached. A qualification kit is ready (R3); it was validated on this host only |
| Live research verified | **Not done** | Search providers unreachable from here. An opt-in live harness exists (R4) and has **not** been run |
| Multi-step execution passed | **Done (scripted model)** | R2: three dependent tools, restart, confirmation, context exhaustion, ambiguity, follow-ups |
| No critical regressions | Full suites in R7; goal evaluation 13/30 vs 14/30 in Phase 3 (one sampled run each, within run-to-run noise), 0 unwanted changes |

**Release decision: no APK or installer.** Three of five criteria cannot be met from this environment.

## R1. Grounding calibration (false rejection / false acceptance)

`GroundingCalibrationTest` runs the production checks (`GoalEngine.admissible`: words and provenance; `GoalEngine.about`:
lookup and goal relevance) on 31 legitimate translations of natural instructions (paraphrase, obvious inferred task,
context from an earlier message, lookups, calculations, Hinglish, composed email bodies) and 9 invented ones.

| | First run | Final |
|---|---|---|
| False rejections (legitimate refused) | 3/40 | **0/40** |
| False acceptances (invented admitted) | 0/13 | **0/13** |

Fixes:
* **Default list names** ("shopping", "tasks") were refused as unsaid words when the owner said "the list" or
  "to-do list". They are now a known category with provenance `derived`.
* **Word forms**: "exam"/"examination", "remind"/"reminder" are the same word for matching (`GoalEngine.sameWord`).
* **Context**: goal relevance and lookup relevance now see the earlier message the instruction refers to
  ("My dentist appointment is on 30 October" → "Remind me the day before" would have dropped a correct "dentist
  reminder" goal).

Three more false rejections were found by the real-model conversation evaluation (R5) and are covered by
`answerChecksDoNotRejectGoodAnswersForTheirLabel` and `researchDecisionsForOrdinaryConversation`.

## R2. Execution coverage (StepwiseGoalTest, scripted grammar-capable model)

| Scenario | Test |
|---|---|
| Three dependent tools after a lookup, in order | `threeDependentToolsRunInOrderAfterTheLookup` |
| Restart in the middle of a three-tool task; finished actions not repeated | `aThreeToolTaskInterruptedByARestartResumesWithoutRepeatingFinishedActions` |
| Confirmation in the middle, then the rest continues | `aConfirmationInTheMiddleOfAStepwiseTaskContinuesTheRest` |
| History far larger than the context window | `aMultiToolTaskStillRunsWhenTheConversationHasOutgrownTheWindow` |
| Ambiguous instruction: nothing changed, says what is missing | `anAmbiguousInstructionChangesNothingAndSaysWhatIsMissing` |
| Follow-up instruction using the earlier one | `aFollowUpInstructionUsesTheEarlierOne` |

Defect found: a resumed task counted the model calls of its interrupted run against its budget and stopped
("needed more model calls than allowed"). The budget is now per run, and the limit is 16 (three tools need it).

## R3. Device qualification kit

* `core/.../DeviceQualification.kt`: through CLU's own inference service, with the selected model, it measures:
  load time and memory; per round first-token time, prompt and generation speed, memory, thermal state and battery
  temperature; sustained speed (last third vs first third); context retention (a fact at the start of a prompt
  filling 70% of the context); a planning call. It writes nothing to the owner's data and records every error
  without stopping. It unloads and reloads the model first, so it cancels anything running.
* Android: Settings → Diagnostics → **Run model qualification** (20 rounds; `AndroidDeviceProbe`: PSS, PowerManager
  thermal status, battery level/temperature/charging, SoC, RAM). Report saved under
  `Android/data/ai.clu/files/qualification/` and offered to share.
* `scripts/qualify_device.sh [apk] [model]`: installs, pushes a model, waits for the report, pulls it to
  `docs/measurements/device/`, and records logcat (crashes, ANRs, low-memory kills), `dumpsys thermalservice`,
  `meminfo` and `battery`.

**Host validation only** (`docs/measurements/qualification_host_qwen3_0.6b.json`, this build server, 4 threads, CPU;
says nothing about the Pad 6):

| Load | Peak RSS | First token median / p95 | Generation median | Sustained change | Context recall at 68% fill | Planning call | Errors |
|---|---|---|---|---|---|---|---|
| 13.7 s | 1249 MB | 129 / 191 ms | 28.6 tok/s | +17% (no throttling on this host) | recalled | 8.1 s, correct tool | 0 (12/12 rounds) |

To run on the Pad 6:
```
scripts/qualify_device.sh build/clu.apk models/Qwen3-0.6B-Q4_K_M.gguf
# then the same with Qwen3-4B-Instruct-2507 and Phi-4-mini-instruct Q4_K_M; for sustained heat, run it 2–3 times back to back
```
On a laptop: `./gradlew :core:test --tests ai.clu.core.DeviceQualificationTest -Dclu.qualifyModel=<gguf> -Dclu.qualifyOut=<json>`.

## R4. Live research harness (not run)

`LiveResearchEvaluation` uses real HTTP (`JvmHttp`), CLU's search providers and page reader, the real model and the
production agent: Everest height with "do the sources agree?", the 2023 World Cup final, the latest Linux kernel,
Tenzing Norgay, and the full chain "look up the history of the Eiffel Tower and save a short document about it"
(research → document.write → archive check). It records queries, sources, pages opened, distinct sites, whether the
answer names a disagreement, and the document. It skips when Wikipedia is unreachable, as it is here.
```
./gradlew :core:test --tests ai.clu.core.LiveResearchEvaluation -Dclu.liveResearch=models/<file>.gguf \
    -Dclu.liveResearchOut=docs/measurements/live_research_<model>.json [-Dclu.searchProvider=brave]   # Brave: CLU_BRAVE_KEY
```

## R5. Ordinary conversation (real Qwen3-0.6B, production agent)

`ConversationQualityEvaluation`: 15 cases (explanations, technical reasoning, two follow-up chains, honest
uncertainty, length, long-form, and a multi-source question with two disagreeing search results as a fixture).
Checks are coarse (concepts a correct answer cannot avoid, word counts, an admission of not knowing); every reply and
raw model output is saved in `docs/measurements/conversation_eval_qwen3_0.6b.json`. Single sampled run each.

| | Before fixes | After fixes |
|---|---|---|
| Passed | 3/15 | 5/15 |
| Conversations turned into actions | 0 (the evaluation first counted the calculator as one; corrected) | 0 |
| First text (median) | – | 0.53 s |

CLU defects found and fixed (all decisions in CLU's code, not the model's):
1. **Correct offline answer discarded.** The model copied the note's "OFFLINE:" label in front of a real answer
   about the Python GIL; the echo check rejected the whole reply and the owner got "I'm offline right now, so I
   can't look that up." The label is now removed before the check; the label alone, or a copied note, is still
   rejected.
2. **The owner's own past sent to the web.** "What did I have for breakfast yesterday?" was researched because of
   "yesterday". Questions whose subject is the owner ("what did I…", "where did we…", "have I…") are no longer web
   research unless they are about news, prices, scores and similar.
3. **"Do the sources agree?" did not research.** It now counts as an explicit request for sources.

Remaining failures are model-stage (read in the raw outputs): answers too short to explain (fridge, tides,
vaccines), refusals that cite tools or live data for ordinary questions ("I don't have a tool for translating",
"I cannot access live information" for an SQL question), and declining the GIL question outright on one run.
These are what a stronger model has to fix; no prompt tuning was done for them.

## R6. Goal evaluation after the Phase 4 changes

`docs/measurements/goal_eval4_qwen3_0.6b.json` (30 cases, model alone, one run): **13/30** task success,
**0 unwanted changes**, kind correct 19/30, planning median 5.5 s. Phase 3: 14/30, 0 unwanted, 22/30. One sampled
run each, so the difference is within run-to-run noise, but it is not an improvement: the failures are the same
model-stage errors as Phase 3 (copied example goals such as "marathon registration reminder" for unrelated
messages, now correctly refused by the relevance check; wrong tools such as `task.list` for "add to the list").

## R7. Test results (final code)

| Suite | Result |
|---|---|
| core | 780 tests, 0 failures, 20 opt-in skipped (the new real-model, device and live evaluations skip without their properties) |
| app | 99 tests, 0 failures |
| desktop | 145 tests, 1 failure in the full run, 8 opt-in skipped. The failure, `SpeechEnginesTest.wakePhrasesAreFoundInRecognisedSpeechWithTheCommandThatFollows`, synthesises speech with a real TTS voice and recognises it with a real STT model; it passed when re-run alone (heard "Clue what time is" → wake word + command). No voice code changed in this phase. It is intermittent and needs its own investigation; it was not skipped or changed |
| Real-model runs this phase | conversation evaluation (twice), goal evaluation (once), host qualification (once), all with Qwen3-0.6B Q4_K_M |

## R8. Remaining work and risks

* **Model decision is still open.** Every evaluation here (goals 13–14/30, conversation 5/15) points to the 0.6B
  model as the limit; whether a 4B model fixes it is unmeasured. Commands: Phase 3 Q6 plus R3/R4/R5 of this phase
  (`-Dclu.convModel=<gguf>` for conversation).
* **Pad 6 behaviour is unknown**: memory headroom for a 4B model next to the voice models, sustained heat, battery
  per hour. The kit measures all of these; nobody has run it on the tablet.
* **Live research is unverified**: page reading, multiple sites and contradiction handling have only been tested
  with fixtures.
* **Conversation answers from the 0.6B model are often too short or refuse ordinary questions** (R5). Not fixed by
  prompt tuning, by design of this phase.
* **The qualification run unloads the model** and cancels anything running; it is meant to be started deliberately
  from Diagnostics.
* **One intermittent desktop speech test** (R7).
