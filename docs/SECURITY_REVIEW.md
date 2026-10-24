# Security and privacy review — v1.0.0-prototype

_Reviewed 2026-10-24 against the source and the built `dist/clu-release.apk`. Scope: the manifest, permissions,
network endpoints, downloads, local data and third-party code. This is a self-review by the developer, not an
independent audit._

## 1. Findings fixed in this round

| # | Finding | Risk | Fix |
|---|---|---|---|
| S1 | The sync server (TCP 47823) listened on every network interface as soon as the app started, even before any device was paired. On café/office Wi-Fi this was visible to everyone on the network. | Medium: unauthenticated parties could reach the handshake parser, although they still could not pair or read data. | It now listens only while **a device is paired** or the **2-minute pairing window** is open (`ServicePolicy.shouldListenForSync`). The UI and Diagnostics say why it is off. |
| S2 | No limit on simultaneous incoming connections. | Low/medium: local resource exhaustion (threads, memory) by a flood of idle connections. | Cap of 4 concurrent connections (`SyncNode.MAX_CONNECTIONS`). Extra connections are closed at once. Covered by test `SyncSettingsTest.connectionLimit`. |
| S3 | Voice-pack extraction checked paths with a plain prefix test (`/voice/tts-ryan` also matches `/voice/tts-ryan2`). | Low: archives are SHA-256 pinned, so not exploitable today. | The check now requires the path separator. |
| S4 | The text heard during ASR wake detection was written to the diagnostics log at *info* level. | Low: private speech kept in the local log. | Now logged at *debug*, which is off by default. |
| S5 | The APK was signed with a throw-away debug key. | Updates impossible; anyone holding the published debug password could sign look-alike updates. | Signed with a dedicated RSA-4096 release key kept outside the repository ([SIGNING_AND_BACKUP.md](SIGNING_AND_BACKUP.md)). |

## 2. Manifest and permissions

| Permission | Why | Prompted? | Notes |
|---|---|---|---|
| `RECORD_AUDIO` | voice, wake word, clap | yes (runtime) | Microphone is used only while a voice session or background listening is active. Android shows its mic indicator. Raw audio is never stored. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | background listening | no (install-time) | Off by default. A persistent notification is shown while it runs. |
| `POST_NOTIFICATIONS` | proactive messages, listening notice | yes (runtime) | |
| `INTERNET`, `ACCESS_NETWORK_STATE` | optional online tools, downloads, sync | no | Every online feature is behind a setting (§3). |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | finding the paired device by mDNS and its hotspot address | no | |
| `SET_ALARM` | the alarm tool hands off to the clock app | no | Normal permission. Clu cannot read or delete other alarms. |

Not requested: location, contacts, SMS, call log, camera, storage, accessibility, device admin, or usage access.
Files are opened only through the system picker, one file at a time, when you choose them.

Components: only `MainActivity` (the launcher) is exported. `ListeningService` and `ProactiveJob` are
`exported=false`. There are no content providers and no broadcast receivers reachable from other apps.
`allowBackup=false`, so Android cloud/ADB backup does not copy memories (use Clu's own export). The release build is
not debuggable. Cleartext HTTP is blocked by the Android 9+ default (targetSdk 35): all built-in endpoints are HTTPS,
and a user-configured `http://` external-AI endpoint will fail.

## 3. Network endpoints

| Endpoint | When it is contacted | Data sent | Control |
|---|---|---|---|
| `api.duckduckgo.com`, `html.duckduckgo.com` | You ask something that needs the web | Search query, written by the model from your message | Settings → Network → *Web research*, or *Allow internet access* |
| `en.wikipedia.org` (API) | Factual look-ups | Search term | same |
| `nominatim.openstreetmap.org` | Place look-ups | Place name | *Maps & places* |
| `router.project-osrm.org` | Route/distance questions | Coordinates of the two places | *Maps & places* |
| Web pages found by search | Reading a result | Normal page request | *Web research* |
| `huggingface.co` (via Android DownloadManager) | Only when you tap *Download* on a model, after a size confirmation | File request | *Model & voice downloads* |
| `github.com/k2-fsa/sherpa-onnx` releases | Only when you tap *Download* on a voice pack, after a size confirmation | File request | same |
| Your external AI endpoint | Only if you configure one (default **off**) | Conversation context. Memories only if *Share memories with external AI* is on (default off) | *External AI* |
| Paired device, TCP 47823 + mDNS `_clu-sync._tcp` | Sync, home-device inference | Encrypted change log, prompts | *Synchronisation* |

No analytics, crash reporting, ads, telemetry, or account sign-in. Nothing is sent anywhere at first launch.

**Privacy risk to know about (documented, not changed):** with internet allowed (the default), the model decides
when to search and writes the query itself, so a search query can contain words from your message. Turn off *Web
research* or *Allow internet access* for fully offline use. The offline matrix is in
[OFFLINE_MATRIX.md](OFFLINE_MATRIX.md).

## 4. Downloads and imported files

* **Voice packs:** downloaded over HTTPS. SHA-256 is verified before extraction. Only the expected files are
  extracted, with path-traversal protection.
* **Language models (GGUF):** downloaded from Hugging Face or imported from a file you choose. The header is
  validated, but the file is **not** hash-pinned: the catalog hashes could not be fetched because Hugging Face is
  blocked in the build environment. llama.cpp's GGUF parser has had memory-safety bugs in the past. **Only load
  models from sources you trust** (the official Qwen/Meta repositories linked in the app). Adding pinned hashes to
  the catalog is a follow-up once the hashes can be checked.
* **Memory/settings import:** JSON files you choose. They are parsed and validated, with no code execution.

## 5. Local data at rest

| Data | Where | Protection |
|---|---|---|
| Memories, conversations, settings, tool history, diagnostics log | app-private SQLite (`/data/data/ai.clu/`) | Android sandbox + file-based encryption (device lock). Not additionally encrypted by Clu. |
| PIN and security phrase | `credentials` table | Salted PBKDF2-HMAC-SHA256 (60,000 iterations), never plaintext, never logged. Repeated failures lock out. |
| Sync identity key, pairing secrets, external-AI API key | `SecretStore` | AES-256-GCM, with the key held in the Android Keystore (hardware-backed where available). |
| Voiceprints | database | Embeddings only, never audio. Never synced: people sync excludes voiceprints and the PIN. |
| Models, voice packs | app-specific storage | |

Residual risks:
* A short PIN (4 digits allowed, up to 12) is small. Anyone who can extract the database (root or a forensic tool) could brute-force it
  offline. The PIN protects against casual access, not a determined attacker with the unlocked device.
* Tool arguments (for example search queries and app names) are kept in *Tool history* for 30 days by default
  (configurable, and can be cleared).

## 6. Sync channel

* Pairing uses ephemeral ECDH P-256 plus static identity keys. A 6-digit code (SAS) is shown on both screens and
  must be confirmed by the owner (plus PIN or device lock).
* Sessions are mutually authenticated against the stored peer key, with HKDF-derived AES-GCM keys, replay
  protection and size-limited frames (64 KB handshake).
* Unknown devices are rejected before any data is exchanged. Pairing mode is refused unless the window is open.
* Limitations:
  * A 6-digit SAS gives roughly a 1-in-a-million chance of an undetected man-in-the-middle during pairing, and only
    if the owner confirms.
  * No forward secrecy beyond each session's ephemeral keys.
  * Remote inference prompts are encrypted in transit but visible to the home device by design.

## 7. Third-party code

All shipped dependencies, their versions and hashes are listed in
[RELEASE_VERIFICATION.md](RELEASE_VERIFICATION.md):
* llama.cpp (MIT)
* sherpa-onnx and ONNX Runtime (Apache-2.0 / MIT)
* Kotlin stdlib, kotlinx-coroutines and kotlinx-serialization (Apache-2.0)
* Apache Commons Compress/IO/Lang/Codec (Apache-2.0)

The Bionic sysroot comes from a community NDK build (risk discussed there). There is no closed-source SDK.

## 8. Open items (not blockers for device testing)

1. Pin GGUF hashes in the model catalog. This needs access to `huggingface.co`.
2. Gradle dependency verification and the wrapper checksum.
3. Rebuild native code with the official NDK once `dl.google.com` is reachable.
4. Optional database encryption (SQLCipher) if you want protection beyond Android's own.
