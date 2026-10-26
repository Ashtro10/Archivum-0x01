# Known limitations and unresolved blockers

## Environmental blockers (build environment)

1. **No physical device / emulator.** No Android device was attached and the container has no KVM. All
   hardware-dependent behaviour is unverified on device (see TEST_REPORT.md). Mitigations: Robolectric runs of the
   real app, static API-33 check, APK verification, host runs of the real engines, in-app self-tests and benchmark.
2. **`dl.google.com` / Google Maven / Hugging Face blocked.** Consequences:
   * No Android Gradle Plugin or AndroidX/Compose. The app uses framework Views and a custom, verified APK pipeline.
     Android Studio project sync is not available; build with Gradle as described in the README.
   * NDK obtained from a community build on GitHub; only its Bionic sysroot and compiler-rt are used, compiled
     with the host's Ubuntu clang (supply-chain exposure limited to headers/stub libraries and libc++).
   * No real language model could be downloaded: model quality, tool-calling reliability and real speed are
     untested. The model catalog's Hugging Face URLs could not be checked from here.
   To lift: allow `dl.google.com` and `huggingface.co` in the environment's network settings.
3. **Signing key custody is the owner's job.** `dist/clu-release.apk` is signed with a permanent release key kept
   outside the repository (given to the owner; see SIGNING_AND_BACKUP.md). If it is lost, later builds cannot
   update the installed app without an uninstall (export memories and settings first).
4. **Community NDK sysroot and unpinned model hashes** (see SECURITY_REVIEW.md §8): to be replaced or pinned once
   `dl.google.com` / `huggingface.co` are reachable.

## Functional limitations

* **Image understanding:** not available (text-only model, no llama.cpp multimodal projector built). Pictures can
  be attached and viewed; Clu states it cannot see them.
* **GPU acceleration:** CPU backend only; the setting is shown as unavailable.
* **Languages:** offline ASR and KWS are English. Hindi/Hinglish settings affect text replies only.
* **ASR accuracy:** the bundled 20M streaming model is small (WER ≈ 0.33 on synthetic speech); a larger streaming
  model can be added later. Android's recognizers are selectable alternatives.
* **Wake word "Clue" is an ordinary word.** The leading-word rule rejects mid-sentence use (tuned on synthetic
  speech; verify on device). Speech-gated (ASR) wake detection is less reliable for short names (measured).
* **Speaker recognition** is probabilistic and untested on real voices; it never authorises sensitive actions alone.
  Voice-only sessions cannot complete sensitive actions (verification happens on screen).
* **Barge-in** depends on the device's echo cancellation; with weak AEC Clu may interrupt itself (setting can be off).
* **Background listening** stops on force-stop, permission revocation, OEM battery killers, or if the OS kills the
  service; Android 14 only allows starting it while the app is visible.
* **Phone hotspot discovery:** mDNS is often blocked on hotspots; use the shown IP address (or gateway).
* **Remote inference cancellation** is best effort (second connection); the home device stops at the next token.
* **Train information:** no official free API; only unofficial web results with links to NTES/IRCTC.
* **Physical arrival detection** is not implemented (no location/presence sensing); proactive behaviour uses time,
  battery and approved memories only.
* **Live tools** (web, maps, external AI) were tested only against fake responses.
* **Alarms:** verified through the system's next-alarm record when the clock app registers it; otherwise reported
  as unconfirmed. Timers, calendar entries and maps are hand-offs to other apps and reported as such.
* **Clu server engine (LiveKit)** needs a running `clu_agent.py` and network access for the whole conversation. It is
  not offline. In server mode the agent does not use the phone's memories, tools or characters (feature freeze), and
  background wake-word listening is off. Not yet run on a phone: SDK audio, echo cancellation, interruption on a real
  speaker, reconnection on mobile networks, battery. Local-stack latency (about 2.2 s from end of speech to first
  audio on the host) misses the 1 s target. The cloud stack is unmeasured. See LIVEKIT_AGENT.md.
* **UI** is functional and plain (framework Views), as agreed: visual design is the next phase.
* **Self-improvement** is limited to measurement, suggestions and reversible configuration; no autonomous code
  changes or on-device training.
* **Sync server:** listens only while a device is paired or the pairing window is open (security hardening). The
  first pairing therefore always starts with *Allow pairing* on the device being paired to.

## Streaming voice (this build)
- **Not yet measured on a phone.** All latency numbers so far come from the x86 build host (see VOICE_STREAMING.md).
- **Latency target:** with Whisper, speech end → first audio cannot meet 700 ms. The end-of-speech pause (650 ms by default) plus Whisper (host: about 0.2–0.4 s for base/English, about 0.6–1.6 s for small/Hindi) exceed it before the model even starts.
- **No live Whisper text:** Whisper transcribes after you stop speaking. The live text comes from the built-in English recogniser.
- **Hindi accuracy:** Whisper small on clean synthetic Hindi has CER 0.19–0.40; real speech will be worse. base does not recognise Hindi (it outputs Urdu script).
- **Romanized Hinglish:** it is not spoken intelligibly by any offline voice tested. Hindi must be written in Devanagari to be spoken.
- **Hindi voice licence:** the Pratham voice's training data is CC BY-NC-SA 4.0, i.e. non-commercial.
- **Barge-in:** it needs about 180 ms of speech, clearly louder than Clu's own echo. Quality depends on the device's echo canceller. Using call audio lowers the maximum loudness on some devices.
- **Aborting Whisper:** cancelling a Whisper transcription stops decoding at once, but an encoder pass already running finishes; its result is discarded.
- **HayaiTTS:** it is usable only as an Android system TTS engine, bound by package. It is phrase-level: each phrase is synthesised completely, then delivered over IPC. Its GPL code is not embedded. Binding from a third-party app has not yet been verified on a device.
- **KittenTTS:** English only. Its first phrase is about 0.6 s on the host (Piper: about 0.08 s), so first audio is slower.
- **No automatic engine fallback:** if the selected engine or voice is missing, Clu stays silent and says why, until you install it or choose another.
- **Voices across languages:** English and Hindi use different models, so they sound like different speakers.

## Recognition, turn-taking and UI (repair build)
- **No physical-device verification yet:** recognition accuracy, latency, echo behaviour and the UI layout were measured or rendered on the build host only (pipeline replay, Robolectric screenshots with simulated Android 15 insets). Use DEVICE_ACCEPTANCE.md section U.
- **Test audio:** the recognition test set is synthetic speech (9 voices incl. Indian-accented English) plus a few real LibriSpeech clips; a real phone microphone in a real room will score worse.
- **Hindi:** recognised only by Whisper (466 MB download), after you stop speaking; there is no offline streaming Hindi model. Without Whisper, Clu recognises English only and says so.
- **Pauses:** a thinking pause longer than the end-of-speech pause (800 ms by default) still ends the turn; raise it to 1000 ms if you pause often (answers come ~0.2 s later).
- **Kroko licence:** the built-in live recogniser's licence (Banafo Kroko community model) must be checked before any commercial distribution.

