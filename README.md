# Clu — offline-first Android AI companion (prototype v1.0)

Clu is **one continuous AI entity** that runs on your own Android devices (Xiaomi Pad 6 as the home/base device,
iQOO Neo 9 Pro as the portable device). It talks by text and voice, remembers what matters, runs device actions,
and keeps working without an internet connection. The phone and tablet are two execution environments for the
*same* Clu: same identity, same memories, synchronised over your local network (including the phone's hotspot).

> **Status:** a complete, installable prototype. Everything that could be verified in the build environment was —
> 225 automated tests (222 passed, 0 failed; 3 skipped by default: the opt-in benchmark and two environment-gated LiveKit/offline end-to-end tests, run separately and passing), static API checks, Robolectric runs of the
> real app. **No physical device was available**, so hardware behaviour (audio, real latency, battery, thermals) is
> **unverified on device**. Current milestone: real-device validation with
> [docs/DEVICE_ACCEPTANCE.md](docs/DEVICE_ACCEPTANCE.md); feature work is frozen until it passes.

## Get the APK

* `dist/clu-release.apk` (65 MB, arm64-v8a, Android 13+, release-signed). Direct download link for a phone or tablet
  browser:
  https://github.com/Ashtro10/Archivum-0x01/raw/claude/clu-android-ai-build-5cspi2/dist/clu-release.apk
* SHA-256 `3bd51c9792bb94748e764c4d5f5b53a8a92881a7e462f42e121b7c0d4037f32f`. Signature, certificate and provenance:
  [docs/RELEASE_VERIFICATION.md](docs/RELEASE_VERIFICATION.md).
* Install steps without a PC: [docs/INSTALL.md](docs/INSTALL.md).
* First run: enter your name, choose the device role, then load a language model
  ([docs/MODELS_AND_VOICE.md](docs/MODELS_AND_VOICE.md)). Without a model Clu still handles direct commands
  (apps, alarms, timers, memory, settings) and tells you honestly that conversation needs a model.

## What works offline

Text chat with a local ~3B model (llama.cpp, GGUF) · streaming speech recognition · wake word ("Clue", "Buddy",
"Friend", your own) · clap activation · noise-aware activation · Clu's voice (optional 67 MB download, then
offline) · memory, diary and manual memory insertion · characters · device actions · phone↔tablet sync over a
local network. Full matrix: [docs/OFFLINE_MATRIX.md](docs/OFFLINE_MATRIX.md).

## Documentation

| Document | Contents |
|---|---|
| [STATUS.md](STATUS.md) | Current state, blockers, next actions, last build/test |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | One-entity model, modules, data flow, sync protocol, security |
| [docs/SETTINGS_REGISTER.md](docs/SETTINGS_REGISTER.md) | All 179 settings: default, range, storage, component, effect, test, result (generated) |
| [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) | Model & voice engine report, LiveKit evaluation |
| [docs/TEST_REPORT.md](docs/TEST_REPORT.md) | What is tested, how, and results |
| [docs/PERFORMANCE.md](docs/PERFORMANCE.md) | Measured results (host), how to measure on device |
| [docs/LLM_RELIABILITY.md](docs/LLM_RELIABILITY.md) | Reply pipeline: cancellation, timeouts, request diagnostics, prompt caching, before/after latency |
| [docs/OFFLINE_MATRIX.md](docs/OFFLINE_MATRIX.md) | Offline vs online capabilities |
| [docs/PERMISSIONS.md](docs/PERMISSIONS.md) | Every Android permission and why |
| [docs/INSTALL.md](docs/INSTALL.md) | Installing on Xiaomi Pad 6 and iQOO Neo 9 Pro, pairing over hotspot |
| [docs/MODELS_AND_VOICE.md](docs/MODELS_AND_VOICE.md) | Importing models, voice packs, enabling offline operation |
| [docs/LIMITATIONS.md](docs/LIMITATIONS.md) | Known limitations and unresolved blockers |
| [docs/BUILD_LOG.md](docs/BUILD_LOG.md) | Record of the last successful build and test run |
| [docs/DEVICE_ACCEPTANCE.md](docs/DEVICE_ACCEPTANCE.md) | On-device acceptance checklist (not yet run) |
| [docs/RELEASE_VERIFICATION.md](docs/RELEASE_VERIFICATION.md) | APK checksum, certificate, ABI, native deps, provenance of all binaries |
| [docs/SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md) | Permissions, endpoints, downloads, data at rest, sync; fixes made |
| [docs/SIGNING_AND_BACKUP.md](docs/SIGNING_AND_BACKUP.md) | Release key custody, signed updates, data backup/restore |
| [docs/LIVEKIT_INTEGRATION.md](docs/LIVEKIT_INTEGRATION.md) | Optional self-hosted LiveKit voice mode: starter evaluation, decision, architecture, security, tests, measurements |
| [server/README.md](server/README.md) | Setting up the optional LiveKit voice server on your own computer |

## Building from source

The standard Android toolchain (SDK manager, Google Maven, AGP) was **not reachable** from the build environment
(`dl.google.com` blocked), so Clu builds with a small custom pipeline from pinned, checksummed sources:
`android.jar` (API 35), aapt2, D8, apksig, sherpa-onnx JNI libs, and the Bionic sysroot (compiled with the host's
clang). Requirements: Linux x86_64, JDK 21, clang/lld 18, cmake, ninja, python3 (+numpy for test models), git.

```bash
git clone --recurse-submodules <repo> && cd Archivum-0x01
scripts/setup_toolchain.sh          # fetches + verifies the toolchain and bundled voice models into ./toolchain
scripts/build_native.sh android     # llama.cpp + JNI for arm64-v8a
./gradlew :app:assembleDebug        # -> app/build/apk/clu-debug.apk (throw-away debug key)
./gradlew :core:test :app:test :app:apiCheck :app:verifyApk   # all tests + static checks (+ regenerates the settings register)
```

Release signing (needed to update an installed `clu-release.apk`): `CLU_KEYSTORE=/path/clu-release.p12
CLU_KEYSTORE_PASSWORD=… ./gradlew :app:assembleRelease :app:verifyApk -Pclu.verify=release`, see
[docs/SIGNING_AND_BACKUP.md](docs/SIGNING_AND_BACKUP.md).
Keystores are never committed.

## Project layout

```
core/      Pure Kotlin: identity, settings registry, memory, agent, tools, LLM service, voice logic, sync, security (JVM-tested)
app/       Android: platform layer, voice pipeline, device tools, services, sync transport, UI
native/    llama.cpp (submodule, pinned) + JNI bridge, CMake toolchain for host-clang cross compilation
buildSrc/  APK pipeline tasks (aapt2 link, D8, packaging/alignment, apksig), API-level and APK verification
scripts/   Toolchain setup, native build, test-model generation, host voice-pipeline benchmark
testing/   Test-only androidx.test shim for Robolectric
docs/      Reports and guides (settings register is generated)
dist/      Built APK
```
