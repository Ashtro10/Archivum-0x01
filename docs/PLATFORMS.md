# One CLU, two platforms: Windows PC and Android

Release **2026-11 "1.13.1"**: Android app **1.13.1** (versionCode 28) and Windows PC **0.11.1**. Ship and update them
together: the tablet's shell and the PC's API come from the same commit.

## How the code is shared

| Layer | Where | Used by |
|---|---|---|
| Personality, conversation handling, agent, intent router, tools framework, calculator, memory and archive, settings, sync | `core/` (pure Kotlin/JVM) | both |
| Shell API: messages and streamed turns (`ShellApi`), archive routes (`ArchiveRoutes`), memory routes (`MemoryRoutes`), OpenAI wire format, all written against the transport-neutral `ApiCall` | `core/api/` | both. The PC serves them from the JDK HTTP server; Android from `LoopbackServer` |
| The interface: shell, Primus head, particles, boot, motion, sounds | `desktop/src/main/resources/shell` | both. The APK packages it at build time; `platform.js` adapts it on Android and does nothing on Windows |
| Platform adapters | `desktop/` (Windows host: inference, governor, tray, installer, coding agents, self-repair, capabilities, browser control, PC voice server); `app/` (Android: native voice, device tools, notifications, calendar, WebView host) | one each |

Platform-specific services plug into shared code through interfaces, never by copying it:
- `MaintenanceHost` and `BackupHost`: the PC's maintenance scheduler and its database backups.
- `ShellHost.deviceTools`: the tools that act on the Android device.
- The `platform` capability report, served at `/v1/shell/platform`.

## What each platform does

| Feature | Windows PC | Android alone (no PC) | Android paired with the PC |
|---|---|---|---|
| CLU window: Primus head, particles, navigation | yes | yes (same shell) | yes |
| Chat with streaming replies | local model | on-device model | the PC's model |
| Conversation history | PC database | device database | the PC's |
| Memory, timeline, people, documents, suggestions | yes | yes, device data (shared routes) | the PC's data |
| Voice | PC voice server (LiveKit, Kroko, Piper/Kokoro) | native on-device voice | native client to the PC's voice server |
| Device controls (alarm, timer, volume, brightness, flashlight, apps, media, dial, notifications) | PC tools only | yes | yes: typed commands run **on the device**, not the PC |
| Web search and research | yes | yes (the device's search providers) | the PC's |
| Calculator (typed or spoken) | yes | yes | yes |
| Model management, benchmarks | yes | no (Classic view has the device model settings) | the PC's, from the tablet |
| Coding agents, task agents, self-repair, capabilities (self-built apps), browser control | yes | **no**: marked "PC" in the rail, the view explains | yes, run on the PC |
| Camera gestures (Hands) | yes | no (removed from the rail) | no |
| OpenAI-compatible endpoint for local tools | yes, this PC only | no | no (never offered over the network) |
| Backups and background maintenance | yes | "not available here" (said, not faked) | the PC's |

## Communication

The Android app reaches the PC's phone endpoint (port 47822, private network only) with its paired device key:
- `/token` for voice;
- `/v1/*` for the shell's API.

The PC refuses browsers (any Origin header) and PC-only actions. If the PC can't be reached, the tablet answers locally
within one request and retries the PC after 15 s. The top bar shows which side is answering.

## Verification in this release

| Check | Result |
|---|---|
| Android APK builds, signed (v3), launcher = the CLU window, shell packaged | yes |
| Windows installer builds; static checks (per-user, no services, no data, licences, uninstaller) | yes |
| Tests (shared core, Android/Robolectric, PC including real voice and browser tests) | see `IMPLEMENTATION_LOG_1.13.md` |
| PC → Android chain (tablet shell → ShellHost → PC phone endpoint → PC API), in a real browser at Pad 6 size | pass (`TabletShellTest`) |
| Android alone: chat, memory, timeline, people, voice UI, device commands | pass (`ShellHostTest`, `ShellActivityTest`, `TabletShellTest` device mode) |
| The PC window (shell + Primus) on Windows' own rendering path | browser tests on the same shell (`PrimusShellTest`) |
| `clu.exe` run under wine here | the bundled runtime starts and creates CLU's data folder, then wine's network-adapter emulation crashes the JVM (`NetworkInterface.getAll` in `kernelbase.dll`). This is a sandbox limit, not a Windows one. **Not verified on real Windows in this release.** |

## Platform limitations (not hidden)
- **Android without a PC:**
  - No coding agents, self-repair, model benchmarks or capabilities: these need the PC's compute.
  - Answer quality depends on the on-device model, if one is installed.
- **Android paired:**
  - Sending messages, calling and replying to notifications typed in the PC-style window go to the PC's agent. The PC
    can't act on the tablet's phone functions, so use voice or Classic view for those.
  - Camera gestures are PC-only.
- **Windows:** no device controls of a phone.
- **Not measured on hardware:** Pad 6 frame rate, memory and battery; real Windows launch of 0.11.1. Checklists:
  `DEVICE_ACCEPTANCE.md` §T (tablet) and `INSTALLATION_WINDOWS.md` (PC).
