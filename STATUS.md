# Project status — Clu prototype v1.0

_Last updated: 2026-10-23_

## Current state

A complete, installable Android prototype (`dist/clu-debug.apk`) built and verified as far as possible without a
physical device. All milestones (A–F) are implemented; device-level verification is the remaining gap.

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
* **Delivery:** 181 automated tests (0 failures), API-33 static check, APK verification, generated settings
  register, documentation set.

## Current blockers

* No physical device/emulator in the build environment → hardware behaviour unverified on device.
* `dl.google.com` and `huggingface.co` blocked → no AGP/AndroidX, no real model for quality/speed testing.

## Known defects / risks (see docs/LIMITATIONS.md)

* Tuned-on-synthetic parameters (KWS threshold, leading-word offset, speaker threshold) need on-device calibration.
* Small ASR model accuracy; English-only offline speech.
* Debug signing key is ephemeral (back up before installing a differently signed build).

## Next actions

1. Install on the Pad 6 and Neo 9 Pro; run Diagnostics → *Run self-tests*; benchmark Qwen2.5-3B and 1.5B.
2. Measure end-of-speech → first-audio latency, battery cost of background listening, thermals over 20 turns.
3. Calibrate wake-word threshold/leading offset and speaker threshold with real voices; enrol the owner.
4. Pair the devices over the hotspot; test sync interruption and tablet-hosted inference on real Wi-Fi.
5. If allowed by the network policy: add AGP/AndroidX build, llama.cpp OpenCL (Adreno) backend, a multimodal
   model (mtmd) for image understanding, and a larger streaming ASR model.
6. UI design pass.

## Build instructions

See README.md → *Building from source*. Last successful build and test run: docs/BUILD_LOG.md.
