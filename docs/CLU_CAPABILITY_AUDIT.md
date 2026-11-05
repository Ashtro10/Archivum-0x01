# CLU Android: capability audit (2026-11-05)

Audit of the Android app (`app/`) and the shared core it runs (`core/`), against the full CLU capability specification.
Each capability was traced along its execution path: input → intent understanding (IntentRouter / model) → tool
selection → execution → result check → memory/context → feedback.

**Evidence available in this environment:** JVM unit and integration tests against real SQLite and real engines
(llama.cpp, sherpa-onnx, whisper.cpp on the build host), plus Robolectric runs of the real app on Android 15
framework classes. **No physical device was available.** So nothing below is verified on a Xiaomi Pad 6 or iQOO Neo 9 Pro.
"Verified" means verified by the tests named, not on hardware.

## Status codes

| Code | Status | Meaning |
|---|---|---|
| **V** | Implemented and verified | Working implementation; tests exercise its real execution path |
| **I** | Implemented but incomplete | Exists but lacks important functionality |
| **B** | Implemented but broken | Fails when executed |
| **P** | Partially implemented | Only part of the intended behaviour works |
| **M** | Missing | No meaningful implementation |
| **PR** | Platform restricted | Android prevents it; the actual limit is in [CLU_ANDROID_LIMITATIONS.md](CLU_ANDROID_LIMITATIONS.md) |
| **HR** | Hardware restricted | Not feasible on the target hardware without more resources |
| **EX** | Requires external integration | Needs another app, service, account or permission outside CLU |

"(new)" marks capabilities implemented or repaired in this assignment.

## A. Voice and conversation

| Capability | Status | Where | Evidence and notes |
|---|---|---|---|
| Configurable wake word | V | `voice/VoiceSession.kt`, sherpa KWS, `Settings.WAKE_*` | KWS tests on host audio; custom wake words (`WakeWordsActivity`); device effect unverified |
| Voice recognition (offline) | V | streaming zipformer (sherpa), whisper.cpp EN/HI | `VoiceRecognitionTest`, ASR eval sets (`docs/measurements/asr_eval_*`) |
| Speaker identification / owner voice | V | `Speaker.kt`, `voiceprints` table, `Security.kt` | Enrolment and matching tested on host; needs the 30 MB speaker model |
| Continuous conversation, turn-taking | V | `VoiceSession`, endpointer | `EndpointerTest`, `VoiceAgentTest` |
| Interruption / speech cancellation (barge-in) | V | `SpeechOutput`, `CancellationRaceTest` | Cancellation latency measured 21–24 ms (host) |
| Context and follow-ups | V | `ConversationContext.kt`, `FollowUpResolver` | `ConversationContextTest`, `TurnRoutingTest` |
| Conversation history | V | `conversations`/`messages` tables | `MemoryAndConversationTest` |
| Multilingual (EN, HI, Hinglish) | P | whisper multilingual, Hindi Piper voice, `Settings.LANGUAGE` | English and Hindi only; other languages depend on the model |
| Natural speech, consistent voice | V | Piper/Kokoro/Kitten/system TTS, one selected voice | `TtsSelectionTest`, pacing audits; no silent engine switching |
| Speaking speed and volume | V | `voice.speed`, `voice.pitch`, `device.volume` | Bindings tested; audible effect device-unverified |
| Background voice availability | P | `ListeningService` (foreground service, microphone type) | Works while the service runs; Android shows a notification and may stop it under battery saver (PR) |
| Activation / termination phrases | V | wake words, termination phrase setting | Settings register tests |
| Noise-resistant recognition | P | `voice.noiseSuppression`, audio source choice | Uses Android's suppressor; no dedicated denoiser model |
| Voice authentication for sensitive actions | P | `AuthorizationPolicy` + voiceprint | Voice match lowers friction; sensitive actions still need PIN/biometric (by design: voice alone is spoofable) |
| No empty replies / no false tool completion | V | `AgentController` (claim validation, bounded repair), `ToolResult.verified` | `AgentAcceptanceTest`, `ConversationRepairTest`; the agent never reports unverified success as success |

## B. Intelligence and reasoning

| Capability | Status | Where | Evidence and notes |
|---|---|---|---|
| General Q&A, reasoning, explanations | P | local GGUF via llama.cpp | Quality is bounded by the on-device model; 1–3B models make mistakes. Measured in `docs/MODEL_AND_ANSWER_QUALITY.md` |
| Planning / task decomposition | V | `TaskPlan.kt` (multi-step plans, progress, cancel, confirm) | `TaskPlanTest` |
| Maths | V | `calc` tool, routed deterministically | `AgentTest` |
| Coding assistance | P | model only on Android | Explanations and small snippets; no code execution on the phone |
| Document summarisation | V | archive documents (txt, md, csv, html, json, docx, pdf text) + model | `HistoryArchiveTest` |
| Research assistance and verification | V | `ResearchPolicy`, multi-source Wikipedia/SearXNG/Brave, claim checks | `ResearchTest`, `AgentAcceptanceTest` (needs internet at run time) |
| Tool selection, conversation vs action | V | `IntentRouter` (deterministic) + model tool calls | `IntentRouterTest`, `PersonalRoutesTest` (new), `TurnRoutingTest` |
| Uncertainty / self-check / error recovery | V | evidence-bound answers, bounded retries, honest failures | `AgentContractTest` (21 shared conversations) |

## C. Memory and personalisation

| Capability | Status | Where | Evidence and notes |
|---|---|---|---|
| Persistent personal memory (kinds: core, episodic, project, preference, instruction, relationship) | V | `MemoryService`, schema v5 | `MemoryServiceTest` |
| Conversational / working memory | V | `working_memory` | `ContextMemoryTest` |
| Diary / journal | V | `diary_entries`, Diary screens | `DiaryTest` |
| Task memory | V (new) | `butler_items` (schema v7) | `ButlerTest` |
| Knowledge memory (documents, people, projects, timeline) | V | archive tables | `HistoryArchiveTest` |
| Retrieval: keyword (BM25-like) + optional vectors, relevance ranking | V | `memory_terms`, `memory_vectors` | 2,000-record lookup median 3–4 ms (host) |
| Summarisation, consolidation, duplicates, conflicts | V | `consolidation_log`, archive conflicts | `ConsolidationTest` |
| Correction, deletion, export | V | Memory editor, encrypted export bundle | `SafeDeleteTest`, `SyncStage2Test` |
| Fact vs preference vs inference vs temporary | V | `memory_proposals` (inferences need approval), `approval` column, working memory expiry | `MemoryFormationTest`: inferred observations never become facts without approval |
| Migrations keep user data | V | `Schema.migrate` (single transaction, rollback) | `MemoryServiceTest`, `ButlerTest` (v6→v7, new) |

## D. Android system integration

| Capability | Status | Where | Evidence and notes |
|---|---|---|---|
| Launch apps | V | `OpenAppTool` | Robolectric |
| Open settings screens | V | `SettingsPanelTool` (now also notification access, alarms & reminders) | Robolectric |
| Wi-Fi / Bluetooth / hotspot toggles | PR | opens the system panel | Android 10+ forbids apps toggling them |
| Brightness | V (new) | `BrightnessTool` | `PersonalToolsTest`: changes the system value once "Modify system settings" is allowed; otherwise opens that screen |
| Volume | V | `VolumeTool` | — |
| Media controls (play, pause, next, previous) | V (new) | `MediaControlTool` (media keys) | `PersonalToolsTest`: key events reach the media system; "pause" with nothing playing says so |
| Notification reading and summaries | V (new) | `CluNotificationListener`, `NotificationsReadTool` | Needs owner-granted notification access (EX/PR); summary format tested; nothing stored |
| Replying to notifications | M | — | Possible via `RemoteInput` actions; not implemented |
| Phone calls | V (new: by name) | `CallTool`, `DialTool` | Dialer opens with the number filled in; the owner presses call |
| Contact lookup | V (new) | `Contacts`, `ContactFindTool` | READ_CONTACTS is asked when first needed. Lookup against a real contacts provider isn't testable in Robolectric |
| SMS | P (new) | `SmsComposeTool` | Drafted in the messaging app; the owner sends it. Silent sending needs the default-SMS-app role (PR) |
| Alarms / timers | V | `AlarmTool` (verified via the system next-alarm), `TimerTool` | — |
| Calendar | P | `CalendarAddTool` (insert via calendar app) | Reading the calendar (READ_CALENDAR) not implemented |
| File management | P | user-picked document import | No general file browser (scoped storage, PR) |
| Clipboard | V (new) | `ClipboardCopyTool` | Writing works; background reading is blocked by Android 10+ (PR) |
| Screenshot / screen understanding / app navigation | M | — | Needs an accessibility service or MediaProjection, plus a vision model (HR) |
| Battery / network / diagnostics | V | `DeviceStatusTool`, Diagnostics screens | — |

## E. Personal butler

| Capability | Status | Where | Evidence and notes |
|---|---|---|---|
| Tasks and to-do lists | V (new) | `Butler`, `task.add/list/done` | `ButlerTest`, through CLU's agent with a real DB |
| Reminders (one-off, daily, weekly) | V (new) | `reminder.set` + `ReminderAlarms` (AlarmManager → notification, reboot-safe) | `ButlerTest` (parsing, recurrence), `PersonalToolsTest` (alarm scheduled, notification posted once) |
| Shopping lists | V (new) | `shopping` list ("milk, eggs and bread" become three items) | `ButlerTest` |
| Daily briefing | V (new) | `butler.briefing`, first greeting of the day | `ButlerTest` |
| Evening summary | P | evening check-in (proactive) | Doesn't yet list the day's completed tasks |
| Pending-task / deadline tracking | V (new) | overdue items in the briefing | `ButlerTest` |
| Calendar management / scheduling | P | add events only | — |
| Navigation, weather | V | `maps.open/distance`, weather tool | Needs internet (OSM, Open-Meteo) |
| Travel planning | P | routes, trains lookup | No bookings (EX) |
| Email integration | P (new) | `EmailComposeTool` drafts in the mail app | Inbox access needs an account integration (EX) |
| Document organisation | V | archive documents and projects | — |
| Financial / academic organisation, attendance | M | — | Can be kept as lists and projects, but there is no dedicated model or data source |

## F. Personality and companion

| Capability | Status | Where | Evidence and notes |
|---|---|---|---|
| Stable identity, one entity | V | `store.entityId`, `PersonaRegistry`, synced identity | Characters share CLU's memory scope rules |
| Character profiles that change behaviour | V | `characters` (prompt, style, memory scope, tool permissions) | `PersonaMeasurement`, `AgentTest` |
| Humour, depth, disagreement, preferences | V | personality settings, preference learner | `PreferenceLearnerTest` |
| Proactive check-ins, situational talk | V | `ProactivePlanner` (caps, quiet hours, spacing) | `ResponseTemplatesTest` |
| Silence and interruption awareness | V | barge-in, quiet hours | — |

## G. Visual perception

| Capability | Status | Where | Evidence and notes |
|---|---|---|---|
| Image input / camera / OCR / objects / scenes / VQA / QR / screenshots | HR | — | The on-device text model can't see; CLU says so instead of pretending. A local vision model (SmolVLM-class GGUF + mmproj, roughly 200–600 MB) needs a native rebuild with llama.cpp's multimodal library and a download that is blocked in this build environment (Hugging Face returns HTTP 403). See limitations |

## H. Internet and research

| Capability | Status | Where | Evidence and notes |
|---|---|---|---|
| Web search, multi-source comparison, verification | V | `WebSearchTool`, research policy, pinned fetch (DNS-rebinding safe) | `ResearchTest`, `PinnedFetchTest` |
| News | P | general search | No news provider |
| Maps | V | geocode, distance, open in maps | `MapsToolTest` |
| Translation | P | model translation (EN/HI quality) | No dedicated translation model |
| Downloads | V | model and voice downloads (hash-verified, owner-confirmed) | — |
| Research history / saved research | P | conversation history and diary | No dedicated research library |
| Offline behaviour | V | every online tool fails gracefully | `OfflineConversationTest`, `scripts/test_offline.sh` |

## I. Entertainment and media

| Capability | Status | Notes |
|---|---|---|
| Music / video playback control | V (new) | Media keys (`media.control`) |
| Streaming-app or playlist control | PR / EX | Needs per-app APIs (e.g. Spotify SDK and account) |
| Reading documents aloud, storytelling, trivia, discussions, recommendations | P | Through the model and TTS; quality bounded by the model |

## J. Communication

| Capability | Status | Notes |
|---|---|---|
| Incoming-call awareness / caller ID announcement | M | Needs READ_PHONE_STATE and a call-screening role; not implemented |
| Notification announcements and reading | V (new) | Reading on request; automatic announcement not implemented |
| Message drafting / sending | P (new) | Drafted in the messaging app; the owner sends |
| Email composition | P (new) | Drafted in the mail app; organisation needs an account (EX) |
| Contacts | V (new) | Lookup by name; editing contacts not implemented |
| Relationship context | V | Archive people and relationship memories (owner-approved) |

## K. Productivity and education

| Capability | Status | Notes |
|---|---|---|
| Notes, voice-to-text notes, diary | V | Diary and memories; voice input goes through ASR |
| PDF and document reading | V | Text extraction for import; scanned PDFs need OCR (HR) |
| Project tracking / knowledge organisation | V | Archive projects, links, timeline |
| Study help, learning plans, exam prep, presentations | P | Through the model plus lists and reminders; no dedicated planner |
| Spreadsheets | P | CSV import only |

## L. Autonomous task execution

| Capability | Status | Notes |
|---|---|---|
| Multi-step plans, tracking, cancellation, confirmation | V | `TaskPlan` (core), `TaskPlanTest` |
| Recurring tasks | V (new) | Daily and weekly reminders |
| Event-triggered workflows, task queues, background execution | P | Custom commands and routines; proactive job (JobScheduler); no general event-trigger engine on Android |
| Supervised agents with approvals and audit trail | P | The M7 orchestrator lives in core; its UI is on the PC only |
| Execution history | V | `tool_runs` and the Tool history screen |

## M. Proactive and contextual intelligence

| Capability | Status | Notes |
|---|---|---|
| Configurable proactive notifications with caps and quiet hours | V | `ProactivePlanner` |
| Context-aware reminders / upcoming events / unfinished tasks | V (new) | Briefing in the first greeting of the day when something is due |
| Battery warnings | V | Battery candidate |
| Network awareness | V | Online state in status and tools |
| Notification prioritisation | M | — |
| Routine recognition | M | — |

## N. Security and privacy

| Capability | Status | Notes |
|---|---|---|
| Biometric / PIN owner verification | V | `verifyOwner` (BiometricPrompt) |
| Sensitive-action authorisation, confirmations | V | `AuthorizationPolicy`, per-tool confirm settings (dynamic `tool.*.confirm`) |
| Secrets in Android Keystore | V | API keys and device keys |
| Encrypted personal database | P | App-private SQLite with Android file-based encryption; no SQLCipher layer (documented) |
| Prompt-injection protection | V | Untrusted web and notification text is reported, never followed (`PromptSafetyTest`) |
| Action history, privacy controls, export/delete | V | Tool history, Diagnostics (no content), memory export and delete |

## O. Multi-device

| Capability | Status | Notes |
|---|---|---|
| Shared identity, memory, history, personality | V | Encrypted LAN sync (`SyncEngine`, HLC), `SecuritySyncRegistryTest` |
| PC host integration | V | "Automatic" engine: the paired PC answers, otherwise the phone (`EnginePolicy`) |
| Task continuity | P | Butler items are local to each device (not yet in the sync scope) |

## P. Offline and optimisation

| Capability | Status | Notes |
|---|---|---|
| Local inference, STT, TTS, memory, reminders, app control offline | V | `docs/OFFLINE_MATRIX.md`; reminders are AlarmManager-based (no network) |
| KV-cache reuse, warm-up, model load on demand, auto-unload, memory-trim release | V | `LLM_RELIABILITY.md`; reuse of 690–890 prompt tokens measured per turn |
| No second model on the phone when the PC answers | V | Previous milestone |
| Battery and thermal management | P | Unload on idle and trim; no thermal throttling policy measured (no device) |

## Q. Self-diagnostics

| Capability | Status | Notes |
|---|---|---|
| Model, voice, STT, TTS, network, tools, permissions status | V | Diagnostics, Speech diagnostics, `device.status` |
| Request lifecycle and timings, crash reports, self-test | V | `RequestTrace`, `CrashReport`, `PerfReport` |

## Settings audit

The settings registry is the single source of truth: 254 settings plus a dynamic pair per tool (`tool.<name>.enabled` and
`tool.<name>.confirm`, 26 more for the 13 tools added here).

- Every setting names its component and its verifying test.
- `RegistryTest` checks persistence across restart and rejection of invalid values for every setting.
- [SETTINGS_REGISTER.md](SETTINGS_REGISTER.md) is regenerated from the JUnit results. All verifying tests pass. Twenty
  voice, wake, sync and proactive settings are marked "device effect unverified": their binding is tested, but the
  audible effect needs a phone.
- No decorative toggle was found.
- The new tools get working enable and confirm switches automatically (`ToolSettingsTest#perToolEnable/perToolConfirm`).
