# Build and test log

## Last successful build — 2026-10-23

| Item | Value |
|---|---|
| APK | `dist/clu-debug.apk` (copied from `app/build/apk/clu-debug.apk`) |
| Size | 58,417,272 bytes |
| SHA-256 | `2de2df3c4100a2dbb0d50b385dbf56b87aac026216022cc5c25bf26b6a7d01f2` |
| Package / version | `ai.clu` 1.0.0-prototype (versionCode 1) |
| minSdk / targetSdk / compileSdk | 33 / 35 / 35 |
| ABI | arm64-v8a |
| Signature | APK Signature Scheme v1/v2/v3 (apksig), verified; zipalign verified; debug key generated in the build environment |
| Native | llama.cpp b11844 (06230cb) + JNI, host clang 18.1.3 + NDK r29 Bionic sysroot, `-march=armv8.2-a+dotprod+fp16`, 16 KB pages; sherpa-onnx 1.13.8 JNI + ONNX Runtime |
| Bundled models | Silero VAD, KWS zipformer gigaspeech 3.3M int8, streaming zipformer en 20M int8 |
| Toolchain | Gradle 8.14.3, Kotlin 2.2.21, JDK 21, D8 8.11.18, aapt2 2.20 (apktool 3.0.3), apksig (uber-apk-signer 1.3.0), android.jar API 35 (+ API 33 for checks) |

## Last test run — 2026-10-23

```
./gradlew :core:test :app:test :app:apiCheck :app:verifyApk :app:assembleDebug
```

* JVM + Robolectric tests: **181 run, 0 failed** (26 suites; details in TEST_REPORT.md)
* Settings Implementation Register: **179 / 179** settings with a passing verification test
* `apiCheck`: 618 classes, all platform references exist on API 33
* `verifyApk`: 21 manifest components, 76 JNI externals resolved, all bundled assets present
* Host voice pipeline: `measurements/voice_pipeline_host.json`; core benchmark: `measurements/core_host.json`

## Reproducing

```bash
scripts/setup_toolchain.sh            # TOOLCHAIN_CACHE=<dir> to reuse downloads
scripts/build_native.sh android
./gradlew :core:test :app:test :app:apiCheck :app:verifyApk :app:assembleDebug
./gradlew :core:test --tests '*HostBenchmark*' -Dclu.bench=true      # optional host benchmark
python3 scripts/voice_pipeline_test.py <models-dir> docs/measurements/voice_pipeline_host.json   # needs pip sherpa-onnx==1.13.8
```
