# CLU Android: implementation report (2026-11-05)

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
