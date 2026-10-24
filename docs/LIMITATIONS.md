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
* **LiveKit** is not used (see COMPATIBILITY.md §3).
* **UI** is functional and plain (framework Views), as agreed: visual design is the next phase.
* **Self-improvement** is limited to measurement, suggestions and reversible configuration; no autonomous code
  changes or on-device training.
* **Sync server:** listens only while a device is paired or the pairing window is open (security hardening). The
  first pairing therefore always starts with *Allow pairing* on the device being paired to.
