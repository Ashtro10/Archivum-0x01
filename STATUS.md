# Project status — Clu prototype v1.0

_Last updated: 2026-10-24_

## Current state

A complete, installable Android prototype (`dist/clu-release.apk`, release-signed) built and verified as far as
possible without a physical device. All milestones (A–F) are implemented.

**Current milestone: real-device validation. Feature freeze:** no new features until
[docs/DEVICE_ACCEPTANCE.md](docs/DEVICE_ACCEPTANCE.md) passes or each failure is written up as an agreed blocker.

| Evidence | State |
|---|---|
| Automated tests (JVM / Robolectric / host engines) | 225 total: 222 passed, 0 failed, 3 skipped by default (run separately: LiveKit E2E 16/16, offline-without-network pass) |
| Static checks (API 33, APK structure, signature) | pass ([RELEASE_VERIFICATION.md](docs/RELEASE_VERIFICATION.md)) |
| Security self-review | done, 5 issues fixed ([SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md)) |
| On-device acceptance (Pad 6 / Neo 9 Pro) | A2 failed on the Neo 9 Pro (crash on launch: ICU regex incompatibility). **Fixed, awaiting re-test.** Nothing else run yet. |

## Added 2026-10-24: optional self-hosted LiveKit voice mode

Two explicit paths ([docs/LIVEKIT_INTEGRATION.md](docs/LIVEKIT_INTEGRATION.md)):
* **Offline mode (default):** unchanged on-device pipeline. Tested on the host with networking disabled.
* **LiveKit mode:** speech recognition and voice on your own LiveKit server (`server/`). Clu's model, memory, tools and
  permissions stay on the device. Tested end to end on the host (16/16). Falls back to offline automatically.

The Flutter starter was evaluated and **not** adopted as the app base (see the decision in that document).
Backup of the state before this work: branch `backup/clu-pre-livekit` (f82dec8). Nothing was removed.
On-device testing of both modes: DEVICE_ACCEPTANCE.md section N.

## Completed

* **Foundation:** custom Gradle APK pipeline (aapt2/D8/apksig) from pinned, checksummed sources; SQLite schema with
  migrations; reactive, validated settings registry (179 settings) with snapshots/rollback; diagnostics log.
* **Working core:** llama.cpp JNI engine (KV-cache reuse, streaming, grammar, cancellation), model registry with
  GGUF validation, import/download, load/unload/reload, idle unload, memory checks; chat with history, search,
  rename, archive, delete; memory system (BM25, provenance, manual inserter, diary, approvals, expiry, export/import).
* **Voice:** capture with AEC/NS, Silero VAD, KWS wake words from configurable phrases (tokenised on device), the
  leading-word rule, clap and noise-aware activation, primed streaming ASR, Android recognizers, Piper/Android TTS
  through one interruptible player (speed, pitch, volume, metallic effect), incremental speech, barge-in,
  termination phrase, speaker recognition, latency instrumentation.
* **Intelligence & actions:** single agent with deterministic command routing, tool loop with verified results,
  device tools (apps, alarms, timers, torch, volume, maps, dialer, settings panels, calendar, status), web/maps/train
  research (internet-gated, never fabricated), optional external AI, custom commands, owner/people permissions,
  PIN/phrase/device-lock verification, characters, preference learning (questions + A/B), proactive messages,
  improvement suggestions.
* **Continuity:** SAS-verified pairing, encrypted mutually authenticated sync with resumable change log and conflict
  policies, home-device model as remote engine, mDNS + hotspot gateway discovery, one shared entity id.
* **Delivery:** 225 automated tests (222 passed, 3 environment-gated/opt-in skipped by default; LiveKit E2E 16/16 and offline-without-network run separately and pass), API-33 static check, APK verification,
  generated settings register, documentation set, release signing, security review, device acceptance checklist.

## Current blockers

* No physical device/emulator in the build environment → hardware behaviour unverified on device.
* `dl.google.com` and `huggingface.co` blocked → no AGP/AndroidX, no real model for quality/speed testing.

## Known defects / risks (see docs/LIMITATIONS.md)

* Tuned-on-synthetic parameters (KWS threshold, leading-word offset, speaker threshold) need on-device calibration.
* Small ASR model accuracy; English-only offline speech.
* Signing key must be kept safe by the owner (SIGNING_AND_BACKUP.md). GGUF model files are not hash-pinned.

## Next actions

1. Install `dist/clu-release.apk` on the Pad 6 ([INSTALL.md](docs/INSTALL.md) §1) and work through
   DEVICE_ACCEPTANCE.md sections A–E; then the Neo 9 Pro; then sync (M).
2. Measure end-of-speech → first-audio latency, battery cost of background listening, thermals over 20 turns.
3. Calibrate wake-word threshold/leading offset and speaker threshold with real voices; enrol the owner.
4. Pair the devices over the hotspot; test sync interruption and tablet-hosted inference on real Wi-Fi.
5. Fix every FAIL from the checklist, rebuild with the same release key, re-test.
6. Only after acceptance: AGP/AndroidX build, OpenCL (Adreno) backend, multimodal model, larger ASR model, UI design
   pass.

## Build instructions

See README.md → *Building from source*. Last successful build and test run: docs/BUILD_LOG.md.
