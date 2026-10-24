# Build and test log

## Last successful build — 2026-10-24

| Item | Value |
|---|---|
| APK | `dist/clu-release.apk` (copied from `app/build/apk/clu-release.apk`) |
| Size | 58,052,726 bytes |
| SHA-256 | `e5a6b14273d3b15dec3d5981ab953f3a16cac71c75bb29fa057bf255f84680be` |
| Package / version | `ai.clu` 1.0.0-prototype (versionCode 1) |
| minSdk / targetSdk / compileSdk | 33 / 35 / 35 |
| ABI | arm64-v8a |
| Signature | Release key `CN=Clu, O=Clu, C=IN` (RSA 4096), cert SHA-256 `b8d460942f88…5370bec6`, verified by apksig; zipalign verified. Key kept outside the repo ([SIGNING_AND_BACKUP.md](SIGNING_AND_BACKUP.md)) |
| Native | llama.cpp b11844 (06230cb) + JNI, host clang 18.1.3 + NDK r29 Bionic sysroot, `-march=armv8.2-a+dotprod+fp16`, 16 KB pages; sherpa-onnx 1.13.8 JNI + ONNX Runtime |
| Bundled models | Silero VAD, KWS zipformer gigaspeech 3.3M int8, streaming zipformer en 20M int8 |
| Toolchain | Gradle 8.14.3, Kotlin 2.2.21, JDK 21, D8 8.11.18, aapt2 2.20 (apktool 3.0.3), apksig (uber-apk-signer 1.3.0), android.jar API 35 (+ API 33 for checks) |

## Last test run — 2026-10-24

```
./gradlew :core:test :app:test :app:apiCheck :app:verifyApk :app:assembleRelease -Pclu.verify=release
```

* JVM + Robolectric tests: **207 total: 206 passed, 0 failed, 1 skipped (opt-in benchmark); app tests run on API 33, 34 and 35** (26 suites; details in TEST_REPORT.md)
* Settings Implementation Register: **179 / 179** settings with a passing verification test
* `icuRegexCheck`: 69 regex literals compile with ICU (Android's engine)
* `apiCheck`: 622 classes, all platform references exist on API 33
* `verifyApk` (release APK): 22 manifest components, 76 JNI externals resolved, all bundled assets present
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
