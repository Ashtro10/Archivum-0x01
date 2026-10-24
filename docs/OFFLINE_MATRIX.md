# Offline capability matrix

"Offline" = no internet connection. Local network (phone hotspot / Wi-Fi) is not internet.

| Capability | Offline | Requirements / notes |
|---|---|---|
| Voice conversation, offline mode (default) | ✅ | Wake word → VAD → streaming ASR → local model → Piper; verified on the build host with networking disabled (`scripts/test_offline.sh`), not yet on a device |
| Voice conversation, LiveKit mode (optional) | ❌ by design | Needs your own LiveKit server + Clu voice agent on the local network; falls back to offline when unreachable (`livekit.fallbackOffline`) |
| Text conversation | ✅ | A GGUF model imported/downloaded once |
| Local inference (llama.cpp CPU) | ✅ | — |
| Speech recognition (streaming zipformer) | ✅ | Bundled in the APK |
| Speech recognition (Android recognizers) | ⚠️ | "On-device" works offline only if the device has an on-device model; "default" may use the network |
| Speech synthesis — Clu voice (Piper) | ✅ | After the one-time 67 MB voice pack download or file import |
| Speech synthesis — Android TTS | ⚠️ | Offline if the installed TTS engine has offline voice data |
| Wake word (KWS) / clap / noise-aware activation | ✅ | Bundled |
| Voice activity detection, barge-in, termination phrase | ✅ | — |
| Speaker recognition | ✅ | After the 30 MB speaker model download/import and enrolment |
| Conversation history, search, rename, archive | ✅ | — |
| Memory, diary, manual memory insertion, search, approvals | ✅ | — |
| Personality, preferences, learning, characters | ✅ | — |
| Custom commands, routines | ✅ | Steps that need the internet follow the internet rules |
| Device actions (apps, alarms, timers, torch, volume, dialer, settings panels, calendar) | ✅ | Android intents; clock/calendar apps must be installed |
| Open Maps / navigation | ✅ (opens app) | Map data/routes inside the maps app may need internet; Clu doesn't claim to have retrieved them |
| Reading attached text files | ✅ | User-picked files only |
| Image understanding | ❌ | No vision model in this build (honestly labelled in chat) |
| Diagnostics, settings, backups (export/import) | ✅ | — |
| Phone ↔ tablet sync, tablet-hosted inference | ✅ (local network) | Both on the same hotspot/Wi-Fi; no internet or server needed |
| Proactive check-ins, follow-ups, greetings | ✅ | Notification permission for notifications |
| Web research, current facts | ❌ needs internet | Clu says live information is unavailable (or answers from local knowledge, marked possibly outdated, if configured) |
| Geocoding / road distance | ❌ needs internet | OpenStreetMap services |
| Train information | ❌ needs internet | Unofficial web results only |
| External AI | ❌ needs internet | Optional, off by default |
| Model / voice pack downloads | ❌ needs internet | Or import files obtained elsewhere |

No paid service is required for anything. Clu never sends conversations, microphone audio, diary entries or
memories to a remote service unless you enable a specific internet feature.
