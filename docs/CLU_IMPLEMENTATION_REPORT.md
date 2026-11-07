# CLU Android: implementation report (updated 2026-11-05, release 1.11.0)

This is a progress record for the "complete capability audit and implementation" assignment. It holds enough to
resume without repeating work. Full audit: [CLU_CAPABILITY_AUDIT.md](CLU_CAPABILITY_AUDIT.md). Limits:
[CLU_ANDROID_LIMITATIONS.md](CLU_ANDROID_LIMITATIONS.md). Measurements: [CLU_PERFORMANCE_REPORT.md](CLU_PERFORMANCE_REPORT.md).

## Starting point

The repository already held a large, tested Android app and shared core:
- voice pipeline (wake word, streaming ASR, Whisper, Piper/Kokoro/Kitten TTS, barge-in, speaker ID);
- local llama.cpp inference with prefix caching;
- layered memory with approvals, diary, archive, characters;
- research with claim validation;
- LAN sync;
- diagnostics;
- 254 registered settings, each with a verifying test.

STATUS.md declared a feature freeze pending device acceptance. This assignment explicitly asked for implementation,
so the freeze was lifted for the work below. No existing feature, setting or data was removed.

The audit found the main gaps in the **butler** and **Android system integration** areas:
- no tasks, lists or reminders;
- no notification access, media control or contacts;
- no SMS, email or clipboard tools;
- no brightness control.

## Implemented (new)

| Capability | Code | How it works |
|---|---|---|
| Tasks and lists (to-do, shopping, any named list) | `core/.../butler/Butler.kt`, `ButlerTools.kt` (`task.add`, `task.list`, `task.done`) | Local table `butler_items` (schema v7, migration keeps existing data). "milk, eggs and bread" become three shopping items. Grocery and shopping are one list |
| Reminders, once, daily or weekly | `reminder.set`; Android `proactive/ReminderAlarms.kt` | "in 20 minutes", "at 6 pm", "tomorrow at 9", "every day at 8 am", "every Monday at 10". AlarmManager (exact when allowed), notification on the Reminders channel, survives reboot and clock changes. A repeating reminder moves to its next time. Without a delivery mechanism, CLU says it can't ring |
| Daily briefing | `butler.briefing`, `ProactivePlanner` | "What's my day / brief me": today's reminders, overdue items, open lists. Added to the first greeting of the day only when something is due |
| Media control | `tools/PersonalTools.kt` `MediaControlTool` | Play, pause, stop, next, previous through media keys for any playing app. "Pause" with nothing playing says so |
| Notification reading and summaries | `tools/CluNotificationListener.kt`, `NotificationsReadTool` | After the owner grants Notification access: live list, grouped per app, newest first. Nothing stored. Notification text is reported, never acted on |
| Contacts, call by name | `Contacts`, `ContactFindTool`, `CallTool` | READ_CONTACTS is asked for the first time it is needed, through Android's prompt from the visible screen. Ambiguous names get "Which one: …?". The dialer opens filled in; the owner presses call |
| SMS and email drafts | `SmsComposeTool`, `EmailComposeTool` | The messaging or mail app opens with recipient and text filled in. CLU says the message isn't sent until the owner sends it (`verified = false`) |
| Clipboard | `ClipboardCopyTool` | "Copy that" copies CLU's last reply (or given text); checked by reading it back |
| Brightness | `BrightnessTool` | Percent, up or down, adaptive on or off, after the owner allows "Modify system settings". Otherwise CLU opens that screen and says why |
| Settings screens | `SettingsPanelTool` | Adds "notification access" and "alarms & reminders" |
| Deterministic routing for all of the above | `IntentRouter` | Explicit commands run without the language model: instant, and they work with no model loaded |

## Repaired

- "No, I said, talking": recogniser punctuation broke correction understanding (fixed in the previous milestone, `c3a6f34`).

## Optimised

- The new tools' prompt descriptions were cut from +185 to +116 tokens after measuring. Per-turn latency is unchanged
  (cached prefix); see the performance report.
- Butler and device commands bypass the model entirely.

## Tests added (categories)

| Test | Category | What it proves |
|---|---|---|
| `ButlerTest` (7) | unit + integration (real SQLite, CLU's agent) | Time parsing, what/when split, recurrence, delivery-once, briefing, routing, v6→v7 migration keeps data, honest "can't ring" without a scheduler, briefing in the greeting |
| `PersonalRoutesTest` (1, about 20 cases) | unit | Device commands route correctly; conversation ("call it a day", "call me Ishaan", bare "pause"/"stop") is not mistaken for commands |
| `PersonalToolsTest` (7) | Android integration (Robolectric, real framework classes) | Reminder → AlarmManager at the right time → notification posted once; media keys dispatched; dialer, SMS and mailto intents correct; contacts permission requested instead of pretending; clipboard holds CLU's last reply; brightness written only with permission; notification access required; summary format |

Final results on the final code (build host, 2026-11-05):

| Suite | Tests | Failed | Skipped |
|---|---|---|---|
| core | 680 | 0 | 16, opt-in (benchmarks and real-model or real-audio evaluations) |
| app (Robolectric) | 81 | 0 | 0 |
| desktop (uses the same core) | 137 | 0 | 13, opt-in (window and real-model tests) |

Static checks: `apiCheck` (API 33) passes; `verifyApk` passes, with the ICU regex check accepting all 604 patterns.

Release APK `dist/clu-release.apk`:
- version 1.9.0-prototype (code 22), 85,903,372 bytes;
- SHA-256 `8351f220213443f91451d6cd774bfd7b5c5e1eb9bed0c1e07bb907089d7319d8`;
- signed with the existing release key (certificate SHA-256 `B8:D4:60…BE:C6`), v3 signature and zipalign verified.

No test was skipped, disabled or weakened.
`MemoryServiceTest`'s schema-version assertion moved from v6 to v7, with the reason in the message.

## Not done (see limitations for why)

| Item | Category |
|---|---|
| Vision: OCR, camera, scenes, QR | hardware / model availability |
| Screen reading, app navigation, screenshots | platform (accessibility policy) |
| Incoming-call announcements | not implemented; needs a role |
| Calendar reading | not implemented; feasible next |
| Notification replies and automatic announcements | not implemented; feasible next |
| Butler items in sync | not implemented; feasible next |
| Email inbox, streaming services, bookings, news, smart home | external integrations |

## Resume notes

- Schema is v7. Any new table goes in a new migration entry.
- Android tools are registered in `CluApp.registerAndroidTools()`. Runtime permissions go through `CluApp.permissionWanted`.
- New explicit commands go in `IntentRouter`, with cases in `PersonalRoutesTest`/`ButlerTest`. The ICU regex check runs
  in `:app:verifyApk`.
- Physical-device acceptance is still the top open item: [DEVICE_ACCEPTANCE.md](DEVICE_ACCEPTANCE.md).

## Continuation: release 1.10.0

Verified first: the branch `claude/clu-android-ai-build-5cspi2` of `Ashtro10/Archivum-0x01` was at `8f4f6f3`,
matching the remote; `bba1a07`, `3a257f4` and `8f4f6f3` were in history, and the working tree was clean. The feature
freeze in STATUS.md is recorded; this continuation was explicitly authorised, and device acceptance stays a separate,
open gate.

### Implemented

| Capability | Code | How it works |
|---|---|---|
| Task, list and reminder sync (phone ↔ tablet) | `Butler` (versioned writes, tombstones, `applyRemote`), schema v8, `SyncEngine` scope `sync.tasks`, sync files | Same protocol as memories and diary: hybrid-logical-clock versions, change log, newer wins, idempotent. A reminder rung on one device carries `notifiedAt` to the other. Items from 1.9.0 get a version on first sync |
| Calendar intelligence | `core/calendar` (ranges, conflicts, free slots, wording, five tools); `AndroidCalendar` (CalendarContract) | "What's on my calendar this week", "am I free tomorrow at 3", "when am I free on Friday", "schedule a meeting with Priya tomorrow at 4", "move…", "cancel…" (asks first). Permissions on first use; revocation handled |
| Appointment alerts | `ReminderAlarms` | `calendar.remindBefore` minutes before timed events (off by default), once per event |
| Evening summary | `DayBrief.evening`, `ProactivePlanner`, `ReminderAlarms` | Done today, still open, reminders and calendar tomorrow, diary count, optionally notifying apps. Own switch, time and days; once a day; quiet hours respected; exact time through the shared alarm |
| Morning briefing with calendar | `DayBrief.morning` | Calendar for the rest of today plus lists, in "brief me" and the first greeting |
| Notification intelligence | `CluNotificationListener`, `core/notify/NotificationPolicy`, four tools | Priorities (chosen apps, or messages, calls, alarms and reminders), muted apps, announce once, quiet hours, read text only if allowed, "what did I miss", replies through the app's reply box (asks first), dismissal |
| Call awareness | `CluCallScreeningService`, `CallAnnouncer`, `CallStateWatcher`, `calls.announce` | Caller from contacts announced while ringing (call-screening role, granted once; calls are never blocked or answered). Unknown numbers are never read out. CLU stops listening when the phone rings and stops speaking when a call is answered |
| Routine recognition | `RoutineDetector` | Repeated tasks and reminders, and apps opened through CLU at similar times. Offered as suggestions; approval needed; rejections remembered |
| Vision (architecture only) | `core/vision` | `VisionEngine` interface and `vision.describe`. Without an engine, image questions get an honest "I can't see images yet". No model could be obtained, so no capability is claimed |

### Tests added (by category)

| Test | Category |
|---|---|
| `ButlerSyncTest` (4) | Integration: two installations over real TCP with production pairing and encryption. Scope switch, both directions, no duplicates, tombstones, delivered-once, offline convergence, v7→v8 upgrade |
| `CalendarTest` (4) | Unit (ranges, conflicts, free slots, about 20 routes) and integration through CLU's agent: denied permission, create/move/delete, confirmation before delete, nothing invented |
| `NotificationPolicyTest`, `CallAwarenessTest` | Unit: every owner control |
| `DayBriefTest` (2), `RoutinesTest` | Integration with the real database, planner and proposals |
| `VisionSeamTest` | Integration: honest answer without an engine; an engine's real output when present |
| `AssistantIntegrationsTest` (4) | Robolectric: calendar provider bridge (read, insert, update, delete, revoked permission); notification reply through RemoteInput and dismissal; call-screening role request and call-state behaviour; appointment alert and evening summary through the alarm path |

### Results (build host)

| Suite | Tests | Failed | Skipped |
|---|---|---|---|
| core | 694 | 0 | 16 (opt-in) |
| app | 85 | 0 | 0 |
| desktop | 137 | 0 | 13 (opt-in) |

- `apiCheck` passes.
- `verifyApk` passes, with ICU accepting all 625 regular expressions.
- No test was disabled or weakened. Expectations that moved with intended changes:
  - schema version 7 → 8 (`MemoryServiceTest`; `ButlerTest` now checks "the current version");
  - "evening summary" now routes to the evening tool instead of the morning briefing.

### What remains

See [CLU_ANDROID_LIMITATIONS.md](CLU_ANDROID_LIMITATIONS.md):
- vision (model files unobtainable here);
- screen reading and app navigation (accessibility policy);
- a general event-rule engine;
- learned notification importance;
- streaming-service, email-inbox and booking integrations (external);
- physical-device acceptance of everything ([DEVICE_ACCEPTANCE.md](DEVICE_ACCEPTANCE.md) §Q).

## Continuation: release 1.11.0 (PC 0.9.0)

Request: improve CLU using a YouTube tutorial (`youtu.be/Fhm8goKN6rM`) and the `ruxakK/jarvis-voice-butler`
repository. **The video was not watched**: the environment's proxy refuses youtube.com, and no workaround was used.
The ideas below come from the repository's code and README.

| Idea in the repo | In CLU | Code |
|---|---|---|
| Browser automation (Playwright: open, search, click, type, screenshot) | PC only. CLU drives Edge or Chrome through the DevTools protocol (`java.net.http` WebSocket), with no new dependency. Its own profile; headless only in tests | `desktop/browser/CluBrowser.kt`, `BrowserTools.kt` |
| Ask before risky clicks | Clicks on controls that send, buy, delete, submit, confirm or log out return `needsConfirmation`. The `_confirmed` flag is set only by `ToolExecutor` after a spoken yes; a model-supplied flag is stripped | `Sites.consequential`, `ToolExecutor.execute`, `CluAgent.askToConfirm` |
| Spoken confirmation ("shall I?" → yes/no) | Per conversation, owner only, 3-minute expiry, dropped by any other request. **Also fixes a 1.10.0 bug:** confirm-first tools always cancelled on devices because no handler was installed | `CluAgent.answerConfirmation` |
| Named sites ("open YouTube", "search Amazon for …") | 18 sites with their own search pages. PC opens them in CLU's browser; the phone opens a VIEW intent, so the site's app opens if installed | `core/browser/Sites.kt`, `IntentRouter`, `SiteOpenTool`/`SiteSearchTool` |
| Presence check ("you there?") | Deterministic route, no model call | `PresenceTool` |
| Adaptive interruptions | Backchannels (at most 4 words, all listener noises, no "wait/stop/no/but/what") do not barge in or get answered while CLU speaks | `core/voice/Backchannel.kt`, `VoiceAgent` |
| Not adopted | Gemini realtime (paid cloud), preemptive generation (no gain with a local model that serves one request at a time), video input (no vision model) | – |

Also fixed, from the owner's phone diagnostics (token server timeouts on the same subnet):
* The PC bound phone access to the address it found at start and never rebound it. It now checks every 15 s and
  re-offers phone access when the Wi-Fi address changes (`VoiceService.rebindIfAddressChanged`).
* The phone's error lists the checks: same Wi-Fi (not mobile data), the address, Windows Firewall, and client
  isolation (use a hotspot).

### Tests added

| Test | Category |
|---|---|
| `BrowserControlTest` | Integration: CLU's real host and agent drive headless Chromium against a local site that records requests. Inspect, type, Enter (form really submitted), read, back, a consequential click asked first ("no" → nothing; "yes" → clicked), a self-set confirmation flag ignored, credentialed URL refused, presence, screenshot |
| `SiteRoutesTest` (6) | Unit: site search/open routes, page-action routes, URL safety, consequential controls, presence, backchannels |
| `CalendarTest` (extended) | Integration: delete asks first; "no", a stale "yes" after another request, and an expired question delete nothing; "yes, go ahead" deletes |

### Results (build host)

Core 700 (16 skipped), app 85, desktop 138 (13 skipped): 0 failures. apiCheck and verifyApk pass; the ICU regex
check compiled 645 patterns with 0 rejected. Release APK 1.11.0 (versionCode 24) was signed with the same certificate.
Nothing was tested on a physical device.
