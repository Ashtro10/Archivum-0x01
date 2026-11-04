# Incident 2026-11-04: self-repair cleanup deleted the linked toolchain and native build

## What happened
Self-repair workspaces (Git worktrees and the coding benchmark's disposable clones) link two large, Git-ignored folders
from the owner's checkout instead of copying them: `toolchain/` and `native/build/`. Cleanup used Kotlin's
`File.deleteRecursively()`, which follows symbolic links into directories. Deleting the benchmark folder (which held a
clone with a `toolchain` link) and repair workspaces deleted the contents of the owner's real `toolchain/` (~1.4 GB) and
`native/build/` (JNI libraries, test models). Detected when builds started failing with `Unresolved reference: android`
(buildSrc needs `toolchain/apksig.jar`).

## Not affected
Git repository and history (fsck clean), all tracked source, the release signing key (`/root/clu-build/release-key`),
model files under `/root/clu-build`, CLU's data.

## Fix
`SafeDelete.tree()` deletes without following links (a link is removed as a link). All recursive deletes in self-repair
use it (`GitCli.removeWorktree`, test-result cleanup, benchmark cleanup). Regression test: `SafeDeleteTest`.

## Restoration (needs the owner's decision: large downloads)
`scripts/setup_toolchain.sh` (Android jars, r8, apktool, uber-apk-signer/apksig, sherpa-onnx, NDK, androidx-test,
LiveKit protocol), the voice toolchain scripts (`build_voice_native.sh`, `fetch_livekit_sdk.sh`), then
`scripts/build_native.sh` and `scripts/prepare_native_test.sh` for `native/build`. Until then nothing in the Gradle build
compiles (buildSrc depends on `toolchain/apksig.jar`).
