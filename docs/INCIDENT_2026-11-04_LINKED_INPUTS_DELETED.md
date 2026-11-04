# Incident 2026-11-04: self-repair cleanup deleted the linked toolchain and native build

## What happened
Self-repair workspaces (Git worktrees and the coding benchmark's disposable clones) link two large, Git-ignored folders
from the owner's checkout instead of copying them: `toolchain/` and `native/build/`. Cleanup used Kotlin's
`File.deleteRecursively()`, which follows symbolic links into directories. Deleting the benchmark folder (which held a
clone with a `toolchain` link) and repair workspaces deleted the contents of the owner's real `toolchain/` (~1.4 GB) and
`native/build/` (JNI libraries, test models). Detected when builds started failing with `Unresolved reference: android`
(buildSrc needs `toolchain/apksig.jar`).

## Full scope (found during recovery)
* `toolchain/`: all contents (Android jars, aapt2, r8, apksig, sherpa-onnx JNI libraries, voice model assets, LiveKit
  jars and JNI libraries, androidx-test, NDK, debug keystore).
* `native/build/`: all contents (host and Android JNI libraries, voice native libraries, test models, CMake trees).
* `/usr/lib/llvm-18/lib/clang/18/include` (clang's own headers, package `libclang-common-18-dev`). Reached through a
  nested link: `native/build/cmake-android/clang-resource/include` is a link that
  `native/android-arm64.toolchain.cmake` creates with `file(CREATE_LINK)`. Found when native builds failed with
  `'stdbool.h' file not found`.

## Not affected
Git repository and history (fsck clean), commit f8bb4fb and all tracked source, the release signing key
(`/root/clu-build/release-key`), model files and the download cache under `/root/clu-build`, CLU's data.

## Fix
`ai.clu.core.util.SafeDelete.tree()` (shared by app and desktop) replaces every recursive delete in production code:
* the target must lie strictly inside an allowed root, compared by real path (`..` and linked parents cannot escape);
* protected folders (the repository, its `toolchain/` and `native/build/`) are refused even inside an allowed root;
* links (top-level, nested, chains, broken) are deleted as links and never followed;
* every directory entered must still resolve inside the target, or the walk stops.

Callers: `GitCli.removeWorktree`, self-repair test-result cleanup, coding-benchmark cleanup, desktop voice model
installs, Android voice model installs. Build scripts use `buildSrc` `safeClean()` (only folders with a `build` path
component, links not followed). Regression tests: `core` `SafeDeleteTest` (real files, external sentinel folders
checked by SHA-256, including the nested-link layout of this incident) and `desktop` `SafeDeleteTest` (a real Git
worktree).

## Recovery (no download over 100 MB; no mirrors)
* Pinned download cache `/root/clu-build/dl`: Android jars, aapt2, r8, apksig, sherpa-onnx libraries and models.
* The previously built release APK `dist/clu-release.apk`: Android JNI libraries (llama, whisper, sherpa, LiveKit) and
  voice assets, byte-identical to the cache where both exist.
* Local build outputs: LiveKit AARs, voice-native wheels, the Go module cache (livekit-server rebuilt offline), and the
  desktop install image (Windows `clu_llama.dll`).
* Rebuilt from source: host `libclu_llama.so` and `libclu_whisper.so`.
* Downloaded (small, from the original sources): androidx-test at commit edea994e (about 4 MB) and the Ubuntu package
  `libclang-common-18-dev` 1:18.1.3-1ubuntu1 (736 KB, SHA-256 checked against the archive index, `dpkg --verify` clean).
* `tiny.gguf`: the original, recovered from a test copy left in `/tmp` (SHA-256 0e910666…).

## Not recovered
* `tiny2.gguf` and `tiny-llama3.gguf`: regenerated with `scripts/prepare_native_test.sh`; they are random test models,
  not byte-identical to the lost ones.
* The NDK (not needed: the Android JNI libraries come from the APK).
* `protoc`, `webrtc-sdk.jar`, LiveKit protocol sources (only used by the `lkclient` module, which is not in the build).
* The debug keystore: regenerated automatically, so debug builds have a new signing identity. The release key was not
  touched.
* Intermediate CMake build trees in `native/build`.

## Verification after recovery
`./gradlew check :app:assembleDebug --offline --continue --rerun-tasks` (nothing reused from cache): BUILD SUCCESSFUL;
app 74 tests, core 663 (16 skipped: opt-in model and benchmark tests), desktop 116 (4 skipped), no failures; the debug APK
builds and passes `verifyApk`. Before that clean run, two unrelated intermittent failures were seen:
* `LlmReliabilityTest` (two cancel tests) read an event list on the test thread while the collector thread appended to it
  (`ConcurrentModificationException`). The list is now thread-safe; the assertions are unchanged.
* `SpeechEnginesTest.wakePhrasesAreFound…` failed once when speech recognition of synthesised speech dropped the first
  word ("Clue"); it passed on 4 further runs. The speech models (`build/voice-models`) were not affected by the
  incident, and the restored voice libraries are byte-identical to their source. The test is left as it is.
