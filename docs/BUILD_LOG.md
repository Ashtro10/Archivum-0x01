# Build and test log

## Last successful build — 2026-10-24

| Item | Value |
|---|---|
| APK | `dist/clu-release.apk` (copied from `app/build/apk/clu-release.apk`) |
| Size | 65,324,677 bytes |
| SHA-256 | `3bd51c9792bb94748e764c4d5f5b53a8a92881a7e462f42e121b7c0d4037f32f` |
| Package / version | `ai.clu` 1.0.0-prototype (versionCode 1) |
| minSdk / targetSdk / compileSdk | 33 / 35 / 35 |
| ABI | arm64-v8a |
| Signature | Release key `CN=Clu, O=Clu, C=IN` (RSA 4096), cert SHA-256 `b8d460942f88…5370bec6`, verified by apksig; zipalign verified. Key kept outside the repo ([SIGNING_AND_BACKUP.md](SIGNING_AND_BACKUP.md)) |
| Native | llama.cpp b11844 (06230cb) + JNI, host clang 18.1.3 + NDK r29 Bionic sysroot, `-march=armv8.2-a+dotprod+fp16`, 16 KB pages; sherpa-onnx 1.13.8 JNI + ONNX Runtime; WebRTC Android SDK 150.7871.04 (LiveKit mode) |
| Bundled models | Silero VAD, KWS zipformer gigaspeech 3.3M int8, streaming zipformer en 20M int8 |
| Toolchain | Gradle 8.14.3, Kotlin 2.2.21, JDK 21, D8 8.11.18, aapt2 2.20 (apktool 3.0.3), apksig (uber-apk-signer 1.3.0), android.jar API 35 (+ API 33 for checks) |

## Last test run — 2026-10-24

```
./gradlew :core:test :app:test :app:apiCheck :app:verifyApk :app:assembleRelease -Pclu.verify=release
```

* JVM + Robolectric tests: **225 total: 222 passed, 0 failed, 3 skipped by default** (opt-in benchmark; LiveKit end-to-end and offline-without-network tests need their environment and were run separately: 16/16 and pass). App tests run on API 33, 34 and 35. Details in TEST_REPORT.md
* Server side: `server/tests/test_e2e.py` 16/16 against livekit-server 1.13.10
* Settings Implementation Register: **179 / 179** settings with a passing verification test
* `icuRegexCheck`: 74 regex literals compile with ICU (Android's engine)
* `apiCheck`: 1,225 classes, all platform references exist on API 33
* `verifyApk` (release APK): 22 manifest components, 300 JNI externals resolved (incl. WebRTC), all bundled assets present
* Host voice pipeline: `measurements/voice_pipeline_host.json`; core benchmark: `measurements/core_host.json`

## Reproducing

```bash
scripts/setup_toolchain.sh            # TOOLCHAIN_CACHE=<dir> to reuse downloads
scripts/build_native.sh android
export CLU_KEYSTORE=… CLU_KEYSTORE_PASSWORD=…   # release key, see SIGNING_AND_BACKUP.md
./gradlew :core:test :app:test :app:apiCheck :app:verifyApk :app:assembleRelease -Pclu.verify=release
./gradlew :app:assembleDebug   # alternative without the release key: throw-away debug key, cannot update a release install
./gradlew :core:test --tests '*HostBenchmark*' -Dclu.bench=true      # optional host benchmark
python3 scripts/voice_pipeline_test.py <models-dir> docs/measurements/voice_pipeline_host.json   # needs pip sherpa-onnx==1.13.8
```

Verification details, provenance of every binary: [RELEASE_VERIFICATION.md](RELEASE_VERIFICATION.md).
