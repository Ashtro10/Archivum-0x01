# Android permissions

| Permission | Why Clu needs it | When it is requested | If denied |
|---|---|---|---|
| `RECORD_AUDIO` | Voice input, wake word, clap detection, voice enrolment. Audio is processed on the device. | First time you tap the microphone (runtime prompt) | Typing still works; voice features are disabled |
| `POST_NOTIFICATIONS` | Proactive messages, background-listening notification, download completion | After the welcome screen (runtime prompt) | Proactive messages are only shown in chat/spoken in the app |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Keep listening for the wake word while Clu is in the background (with a visible notification) | Granted at install; used only if *Background listening* is on | — |
| `MODIFY_AUDIO_SETTINGS` | LiveKit mode only: switch to communication audio (hardware echo cancellation) and route to the loudspeaker/headset during a session; restored afterwards | Install time (normal permission) | — |
| `USE_BIOMETRIC` | Owner verification with the screen lock or fingerprint before sensitive changes (device key, token endpoint, PIN-protected settings). Without it Android 16 refuses the check (crash report from a vivo I2304, fixed in 1.8.3) | Install time (normal permission) | — |
| `INTERNET` | Phone↔tablet sync (local network), optional web/maps/AI tools, downloads, optional LiveKit mode (your own server only) | Install time | — |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Detect offline state, find local addresses and the hotspot gateway | Install time | — |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS discovery of the paired device | Install time | Manual/gateway address still works |
| `com.android.alarm.permission.SET_ALARM` | Set alarms and timers through the clock app | Install time | Alarm/timer commands fail with a clear message |
| `READ_CONTACTS` | "Call Mom", "text Rahul…", "what's Priya's number": finding a contact's number or email. Read on request only, nothing copied into CLU | The first time a command needs a contact (runtime prompt) | CLU says it needs the permission; numbers still work |
| `WRITE_SETTINGS` (special) | Changing screen brightness | Only you can allow it, in "Modify system settings" (CLU opens that screen when you ask for brightness) | CLU opens display settings instead |
| `SCHEDULE_EXACT_ALARM` (special on Android 14+) | Reminders exactly on time | "Alarms & reminders" in system settings | Reminders still ring, possibly a few minutes late in deep sleep |
| `RECEIVE_BOOT_COMPLETED` | Setting reminder alarms again after a restart | Install time | Reminders set before a restart wait until CLU is next opened |
| Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`, granted in settings) | "Read my notifications": reading and summarising current notifications on request. Nothing is stored | Only you can turn it on (say "open notification access settings") | CLU explains that it can't see notifications |

Not requested: contacts, location, camera, storage-wide access, accessibility, SMS, call placement. Files and
pictures are accessed only through the system picker (you choose each one). The dialer is opened with a number;
you place the call yourself.

Package visibility (`<queries>`): launcher apps (to open apps by name) and handlers for maps, browser, dialer,
alarms/timers, calendar insert, speech recognition and TTS services.

Battery/background: Android and the vendor ROMs (HyperOS, OriginOS) may stop background listening. See
[INSTALL.md](INSTALL.md) for the per-device battery settings. Listening never survives a force-stop or permission
revocation.
