# CLU on Android: platform, hardware and integration limits

These are real limits, each with the most CLU does within them. CLU says the same things in conversation when asked
for something it can't do.

## Platform restricted (Android)

| What | Android's rule | What CLU does |
|---|---|---|
| Wi-Fi, Bluetooth, mobile data, airplane mode, hotspot on/off | Since Android 10, normal apps can't toggle these (`WifiManager.setWifiEnabled` is ignored; hotspot needs a privileged app) | Opens the system panel and says the owner has to flip the switch |
| Brightness | Needs the owner to allow "Modify system settings" (`WRITE_SETTINGS`, a special permission) | Changes it once allowed; otherwise opens that permission screen and says why |
| Reading notifications | Needs "Notification access", granted only by the owner in system settings | Reads the live list on request ("read my notifications"); never stores it; explains how to grant access |
| Sending SMS silently | Only the default SMS app may send without the user (Play policy restricts `SEND_SMS`) | Writes the message in the messaging app; the owner presses send. CLU never claims it was sent |
| Placing a call without a tap | `CALL_PHONE` would allow it, but CLU keeps a human tap for safety | Opens the dialer with the number filled in |
| Reading the clipboard | Android 10+ only lets the focused app read it | Copies to the clipboard (e.g. "copy that" copies CLU's last reply) |
| Exact reminder times | Android 13+ needs "Alarms & reminders" (`SCHEDULE_EXACT_ALARM`) for exact alarms; without it, alarms fall into battery windows (can be several minutes late in deep sleep) | Uses exact alarms when allowed, otherwise allowed-while-idle alarms; "open alarms and reminders settings" opens the switch |
| Screenshots, screen reading, controlling other apps | Needs an Accessibility Service (strict Play policy) or a MediaProjection consent each session | Not implemented |
| Background listening | A microphone foreground service with a visible notification; battery saver and some vendor ROMs (MIUI/HyperOS, OriginOS) can stop it | Runs the service while enabled; the owner may need to exempt CLU from battery optimisation on Xiaomi and iQOO |
| General file access | Scoped storage: only folders and files the user picks | Imports user-picked documents |
| Caller identity for incoming calls | Apps get the caller's number only through the call log (`READ_CALL_LOG`, restricted by Google Play to default dialer/SMS apps) or the **call-screening role** (Android 10+, granted once by the owner in a system dialog; one app at a time, so it replaces another screening app such as a spam blocker) | CLU uses the call-screening role. It allows every call unchanged, looks the number up in the owner's contacts (`READ_CONTACTS`) and announces "Call from <name>"; unknown numbers are "unknown number" or silent. Without the role, CLU still notices calls from the audio mode (no permission) and stops listening or speaking, but can't say who is calling |
| Announcing while the phone rings | The ringtone plays at the same time; vendor dialers may duck other audio | The announcement is spoken over the ringtone; loudness on device is unverified |
| Replying to notifications | Only through the reply action an app attaches (RemoteInput); some apps (and all promotional notifications) have none | CLU uses the reply box when there is one and says when an app doesn't allow it |
| Calendar | Requires `READ_CALENDAR`/`WRITE_CALENDAR`; events from accounts the calendar app doesn't sync aren't visible | Reads expanded instances (repeats included); writes to the primary editable calendar; handles revoked permission |

## Hardware restricted

| What | Why | What would make it possible |
|---|---|---|
| Image understanding (camera, OCR, objects, scenes, QR, screenshots) | CLU's on-device model reads text only. See "Vision evaluation" below | The interface is ready (`VisionEngine`, `vision.describe`). Until an engine exists, CLU says plainly that it can't see images |
| Answer quality of large models | 8 GB RAM devices run 1–4B quantised models well; larger models swap. Memory extension (virtual RAM) is flash storage and does not behave like RAM for inference | The paired PC answers when it is available ("Automatic" engine) |
| Thermal and battery behaviour | Not measurable without a device | `docs/DEVICE_ACCEPTANCE.md` lists what to measure |

## Requires external integration

| What | Needs |
|---|---|
| Email inbox reading and organisation | An account integration (IMAP or Gmail API with the owner's credentials). CLU only drafts mail in the owner's mail app |
| Streaming-service and playlist control (Spotify, YouTube Music) | Each service's SDK and an account. CLU controls whatever is playing through media keys |
| Bookings (travel, tickets), payments, banking | Provider accounts and APIs; not implemented |
| News feeds | A news provider (free RSS or API); CLU uses general web search when online |
| Smart home, wearables | Device-specific APIs (Home Assistant, Wear OS); not implemented |
| Web research providers | Wikipedia works without keys. SearXNG (self-hosted) or Brave Search (own key) are optional |

## Vision evaluation (not shipped)

| Option | Size on device | RAM while running | Fit for Snapdragon 870 / 8 GB and Dimensity 9300 / 12–16 GB | Status here |
|---|---|---|---|---|
| llama.cpp multimodal library (`tools/mtmd`, already in the vendored source) + **SmolVLM-256M/500M** GGUF + projector | ~0.2–0.5 GB | ~0.5–1 GB | Good. Describes scenes and reads large text; small-text OCR is weak | Weights not obtainable: Hugging Face is refused by this environment's proxy |
| Same + **Qwen2-VL-2B / Qwen2.5-VL-3B** (Q4) | ~1.5–2.5 GB | ~2.5–3.5 GB | Usable on the Neo 9 Pro; tight on the Pad 6 next to the text model (load one at a time) | Not obtainable here |
| Tesseract OCR (native) + traineddata | ~15–30 MB | small | Text only (no scene understanding) | Needs a native build and data files; not done |
| ML Kit / Google cloud vision | — | — | Excluded: Play Services dependency or cloud | — |

To finish, CLU needs:
1. llama.cpp's multimodal library added to the JNI bridge (arm64 build).
2. An image picker or camera capture in the chat screen.
3. An owner-confirmed, hash-verified download of one of the model pairs above.
4. A real-model test on the host before shipping.

None of these can be completed without the model files, so no vision capability is claimed.

## Not yet implemented (feasible)

- Driving web pages on the phone (reading, clicking, typing). The phone only opens sites and site searches; page
  control exists on the PC, where CLU has its own browser window.

- Screen reading and app navigation (accessibility service; Play policy review).
- Notification prioritisation beyond the owner's app rules (learned importance).
- A general event-trigger rule engine ("when I get home, …"); time and event triggers exist only for reminders,
  appointment alerts and summaries.

No physical-device testing was possible for any capability in this report.
