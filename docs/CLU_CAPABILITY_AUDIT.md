# CLU Android: capability audit (updated 2026-11-05, release 1.10.0)

Every capability of the CLU specification, checked against the source code, not against earlier reports. Each one was
traced along its execution path: input → intent understanding (IntentRouter or the model's tool calls) → tool → Android
API → result check → memory/context → reply.

**Evidence in this environment:**
- JVM unit and integration tests against real SQLite, plus real engines on the build host (llama.cpp, sherpa-onnx,
  whisper.cpp).
- Phone ↔ tablet sync between two real installations over localhost TCP, with production pairing and encryption.
- Robolectric runs of the real app on Android 15 framework classes.

**No physical device was available.** Nothing is verified on the Xiaomi Pad 6 or the iQOO Neo 9 Pro.
[DEVICE_ACCEPTANCE.md](DEVICE_ACCEPTANCE.md) section Q lists the exact manual checks for this release.

**Feature freeze.** STATUS.md recorded a freeze pending device acceptance. The owner explicitly authorised this
continuation, so code work went ahead. Device acceptance remains the open gate and is tracked separately from the code.

## Status scale

| Status | Meaning |
|---|---|
| **Verified** | Implemented. Automated tests exercise the real execution path, and the behaviour does not depend on device hardware (logic, database, sync protocol) |
| **Awaiting device** | Implemented, with automated tests of the real code against Android's framework classes. Real-device behaviour (vendor ROMs, audio, timing, permissions UI) is unconfirmed |
| **Partial** | Some of the intended behaviour works; what remains is stated |
| **Not implemented** | Nothing meaningful exists yet; the reason is stated |
| **Android-restricted** | Android doesn't allow it, or only within a limit. See [CLU_ANDROID_LIMITATIONS.md](CLU_ANDROID_LIMITATIONS.md) |
| **Hardware-restricted** | Not feasible on the target hardware, or the needed model couldn't be obtained or verified |
| **External integration** | Needs another app, service or account |

## A. Voice and conversation

| Capability | Status | Where / evidence |
|---|---|---|
| Wake word (configurable) | Awaiting device | sherpa KWS, `Settings.WAKE_*`; host audio tests |
| Speech recognition (offline, EN/HI) | Awaiting device | zipformer + whisper.cpp; `VoiceRecognitionTest`, ASR evaluation sets |
| Speaker / owner voice recognition | Awaiting device | voiceprints, `Speaker.kt`; host tests |
| Continuous conversation, turn-taking, barge-in, cancellation | Awaiting device | `VoiceSession`, endpointer; `EndpointerTest`, `CancellationRaceTest` (21–24 ms cancel on host) |
| Context and follow-ups | Verified | `ConversationContext`; `ConversationContextTest`, `TurnRoutingTest` |
| Multilingual | Partial | English and Hindi. Other languages depend on the model |
| Consistent voice, speed, volume | Awaiting device | One selected TTS engine, never switched silently; bindings tested |
| Background voice | Partial | Microphone foreground service. Battery savers on MIUI/OriginOS may stop it (Android-restricted) |
| Pauses for phone calls | Awaiting device (new) | `CallStateWatcher`: Android's audio mode, no permission needed. Ringing stops listening; an answered call also stops speech. `AssistantIntegrationsTest` |
| Voice authentication for sensitive actions | Partial | Voice match plus PIN/biometric. Voice alone is never enough, by design |
| No empty replies / no false completion | Verified | Claim validation, bounded repair, `ToolResult.verified`; `AgentAcceptanceTest`, `ConversationRepairTest` |

## B. Intelligence

| Capability | Status | Where / evidence |
|---|---|---|
| Q&A, reasoning, explanations | Partial | Bounded by the on-device model (1–3B). The paired PC answers when available |
| Planning, multi-step tasks | Verified | `TaskPlan`; `TaskPlanTest` |
| Maths | Verified | `calc` |
| Coding help | Partial | Explanations through the model; no code execution on the phone |
| Document summaries | Verified | Archive documents (txt, md, csv, html, json, docx, pdf text) |
| Research with verification | Verified | Multi-source research, pinned fetch; `ResearchTest` |
| Natural-language tool use | Verified | Deterministic routes for explicit commands, model tool calls for paraphrases. `IntentRouterTest`, `PersonalRoutesTest` and `CalendarTest` cover about 90 phrasings, including non-commands |

## C. Memory and personalisation

| Capability | Status | Where / evidence |
|---|---|---|
| Persistent memory (all kinds), working memory, diary, archive | Verified | `MemoryServiceTest`, `DiaryTest`, `HistoryArchiveTest` |
| Facts vs approved preferences vs inferences vs temporary context | Verified | Inferences go to `memory_proposals` and need approval; working memory expires. `MemoryFormationTest` |
| Routine recognition | Verified (new) | `RoutineDetector`: repeated tasks and reminders, and apps opened through CLU at similar times. Suggestions need approval; rejected ones never return; switch `learning.routines`. `RoutinesTest` |
| Correction, deletion, export, duplicates | Verified | `SafeDeleteTest`, `ConsolidationTest`, encrypted export |
| Migrations keep data | Verified | v6→v7→v8 tested (`ButlerTest`, `ButlerSyncTest`) |

## D. Android integration

| Capability | Status | Where / evidence |
|---|---|---|
| Apps, settings screens, volume, torch, alarms, timers | Awaiting device | `AndroidTools.kt`; Robolectric |
| Wi-Fi, Bluetooth, hotspot toggles | Android-restricted | CLU opens the system panel |
| Brightness | Awaiting device | `BrightnessTool`, after "Modify system settings" is allowed |
| Media control | Awaiting device | Media keys; `PersonalToolsTest` |
| Calendar: read, ranges, conflicts, free time, create, move, delete | Awaiting device (new) | `core/calendar` + `AndroidCalendar` (CalendarContract.Instances and Events). Permissions are asked when first needed, and revocation is handled. Deletion asks first. `CalendarTest` (logic plus agent integration), `AssistantIntegrationsTest` (provider bridge against a contract-faithful provider) |
| Alerts before appointments | Awaiting device (new) | `calendar.remindBefore` (off by default), AlarmManager, notified once per event |
| Notifications: read, summarise, what did I miss, priorities, muted apps | Awaiting device (new) | `CluNotificationListener` + `NotificationPolicy`; `NotificationPolicyTest`, `AssistantIntegrationsTest` |
| Notification announcements | Awaiting device (new) | Off by default. Chosen apps (or messages, calls, alarms and reminders), once each, quiet hours respected, text only if allowed |
| Notification replies and dismissal | Awaiting device (new) | The app's own reply box (RemoteInput), asks first by default; `cancelNotification`. Robolectric verifies the reply reaches the app's intent |
| Contacts, call by name, SMS and email drafts | Awaiting device | The owner presses call or send |
| Incoming-call announcements | Awaiting device (new) | `CluCallScreeningService` (the call-screening role, granted once). The caller comes from contacts; unknown numbers are never read out; every call is allowed unchanged. `CallAwarenessTest` |
| Clipboard | Awaiting device | Write only. Background reading is Android-restricted |
| Screen reading / app navigation / screenshots | Not implemented | Needs an accessibility service, with Play policy limits, and a vision model |
| Battery, network, diagnostics | Awaiting device | `device.status`, Diagnostics |

## E. Butler

| Capability | Status | Where / evidence |
|---|---|---|
| Tasks, shopping and named lists | Verified | `ButlerTest` |
| Reminders (once, daily, weekly), reboot-safe | Awaiting device | AlarmManager path; `PersonalToolsTest`, `AssistantIntegrationsTest` |
| Morning briefing with calendar | Verified (new: calendar) | `DayBrief.morning`; greeting addition |
| Evening summary | Verified (new) | Off by default. Configurable time, days and sections; once a day; quiet hours respected. `DayBriefTest`, plus the alarm path in `AssistantIntegrationsTest` |
| Weather, navigation | Verified (needs internet) | — |
| Email inbox, bookings, finance, attendance | External integration | — |

## F. Personality and companion

| Capability | Status | Where / evidence |
|---|---|---|
| One stable identity across devices; characters that change behaviour; proactive check-ins | Verified | `PersonaMeasurement`, `ResponseTemplatesTest` |

## G. Vision

| Capability | Status | Where / evidence |
|---|---|---|
| Image description, OCR, objects, screenshots, camera, visual troubleshooting | Hardware-restricted (model unavailable here) | The `VisionEngine` interface and the `vision.describe` tool are ready. Without an engine, CLU answers image questions honestly instead of letting the text model guess (`VisionSeamTest`). Evaluation and requirements are in the limitations doc |

## H. Internet and research

| Capability | Status | Where / evidence |
|---|---|---|
| Search, multi-source comparison, maps, downloads | Verified | Needs internet; fails gracefully offline |
| News, translation, saved research library | Partial | General search and the model |

## I–K. Media, communication, productivity

| Capability | Status | Where / evidence |
|---|---|---|
| Media control | Awaiting device | — |
| Streaming-app control, playlists | External integration | — |
| Message drafting, replies through notifications, email drafts | Awaiting device | — |
| Notes, diary, documents, projects | Verified | — |
| Study plans, presentations, spreadsheets | Partial | Model plus lists and reminders; CSV import only |

## L–M. Autonomy and proactivity

| Capability | Status | Where / evidence |
|---|---|---|
| Multi-step execution, history, confirmation, cancellation | Verified | — |
| Recurring tasks | Verified | — |
| Event-triggered workflows | Partial | Reminders, appointment alerts, the evening summary and proactive messages are event/time triggered. No general rule engine |
| Context-aware proactive messages (caps, quiet hours, upcoming events, open tasks) | Verified | `ProactivePlanner`, `DayBrief` |
| Routine recognition | Verified (new) | — |

## N. Security and privacy

| Capability | Status | Where / evidence |
|---|---|---|
| Permissions only when needed | Verified | Contacts, calendar and the call role are requested on first use; notification access is the owner's switch |
| Private content not stored | Verified | Notification text is never stored or logged. The diagnostics log records app names and counts only, never names, numbers or titles |
| Sensitive-action confirmation | Verified | Calendar delete and notification reply confirm by default; per-tool switches |
| Encrypted DB beyond Android's file encryption | Partial | No SQLCipher layer (documented) |
| Prompt injection from web and notification text | Verified | `PromptSafetyTest`; notification text is reported, never followed |

## O. Multi-device continuity

| Capability | Status | Where / evidence |
|---|---|---|
| Shared identity, memory, conversations, preferences, characters, diary (opt-in) | Verified | `SyncSettingsTest`, `SyncStage2Test` |
| Tasks, lists and reminders | Verified (new) | Schema v8 (version, origin, tombstone) and scope `sync.tasks`. Newer edit wins, no duplicates, deletions propagate, a delivered reminder isn't rung again, offline edits converge, pre-sync items get versions. `ButlerSyncTest` over real TCP with encryption |
| Offline queueing, interrupted sync recovery | Verified | Change log with acknowledged sequence; one transaction per batch |
| PC host | Verified | "Automatic" engine (`EnginePolicy`) |

## P–Q. Offline, optimisation, diagnostics

| Capability | Status | Where / evidence |
|---|---|---|
| Offline: model, STT, TTS, memory, reminders, calendar, lists | Verified (host) / Awaiting device | `OFFLINE_MATRIX.md`. Calendar and reminders need no network |
| One alarm for reminders, appointment alerts and the evening summary; no polling | Awaiting device | `ReminderAlarms.reschedule` |
| Diagnostics, self-test, request traces | Verified | — |

## Settings audit

- Every setting names its component and a verifying test that exists and passes:
  - `RegistryTest#everySettingHasAnExistingVerificationTest`;
  - persistence and invalid-value rejection for every setting.
- This release adds 15 settings: evening summary (7), calendar lead time, notification announcing / apps / muted / read
  text, call announcing / unknown callers, routines, and task sync.
- All are off or neutral by default where they speak, notify or share.
- [SETTINGS_REGISTER.md](SETTINGS_REGISTER.md) is regenerated from the JUnit results.
