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
| Incoming-call announcement | Needs `READ_PHONE_STATE` plus call-screening or dialer roles | Not implemented |

## Hardware restricted

| What | Why | What would make it possible |
|---|---|---|
| Image understanding (camera, OCR, objects, scenes, QR, screenshots) | CLU's on-device model reads text only. A vision model adds roughly 200–600 MB and a multimodal projector, and needs llama.cpp's multimodal library in the native build | A small vision GGUF (SmolVLM/Qwen2-VL class) fits an 8 GB Snapdragon 870 tablet, but it wasn't obtainable here (Hugging Face blocked, HTTP 403) and the native rebuild wasn't done. Until then CLU says plainly that it can't see images |
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

## Not yet implemented (feasible)

- Calendar reading (`READ_CALENDAR`).
- Replying to notifications through their reply actions.
- Automatic announcement of new notifications.
- Butler items in the phone ↔ tablet sync scope.
- Evening summary of completed tasks.
- Routine recognition.

No physical-device testing was possible for any capability in this report.
