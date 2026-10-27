# Release verification and provenance

_Verified 2026-10-27 in the build container. These facts were checked by tools, not recalled._

## 1. Where everything is

| What | Location |
|---|---|
| Repository (public) | https://github.com/Ashtro10/Archivum-0x01 |
| Branch | `claude/clu-android-ai-build-5cspi2` |
| APK in the repository | `dist/clu-release.apk` |
| Direct download (works in a phone/tablet browser) | https://github.com/Ashtro10/Archivum-0x01/raw/claude/clu-android-ai-build-5cspi2/dist/clu-release.apk |
| Browse the file on GitHub | https://github.com/Ashtro10/Archivum-0x01/blob/claude/clu-android-ai-build-5cspi2/dist/clu-release.apk |
| Source | `core/` (platform-independent logic), `app/` (Android), `native/` (llama.cpp JNI; llama.cpp is a git submodule), `buildSrc/` (APK pipeline), `scripts/`, `testing/` |
| Documentation | `README.md`, `STATUS.md`, `docs/` |

The earlier `dist/clu-debug.apk` was removed on purpose. It was signed with a throw-away debug key that no longer
exists, so it could never have been updated. There is now one APK and one permanent key (see
[SIGNING_AND_BACKUP.md](SIGNING_AND_BACKUP.md)).

The repository is **public**. Anyone can download the APK and read the source. No keys, passwords or personal data
are committed: `.gitignore` excludes `*.keystore`, `*.jks` and `toolchain/`, and the signing key lives outside the
repository.

## 2. APK facts

| Property | Value | How it was checked |
|---|---|---|
| File | `dist/clu-release.apk`, 85,198,863 bytes | `stat` |
| SHA-256 | `407b60888d179bc5ac61fedf6d2c10149c8415522d358cc4694f3d8d5ace84dd` | `sha256sum` |
| Package ID | `ai.clu` | `aapt2 dump badging` |
| Version | `1.5.0-prototype` (versionCode 7; installs as an update over versionCodes 1–6, same package and signer; database migrates to schema v3 on first launch — see STAGE2_RESEARCH_MEMORY_SYNC.md §7 for rollback) | `aapt2 dump badging` |
| Minimum Android | API 33 (Android 13) | manifest `minSdkVersion=33` |
| Target / compile SDK | 35 / 35 | manifest |
| ABI | `arm64-v8a` only | `native-code: 'arm64-v8a'` |
| Signature | verified by apksig (scheme v3, the scheme Android 13+ checks); zip alignment verified | `uber-apk-signer --onlyVerify` |
| Signer | `CN=Clu, O=Clu, C=IN`, RSA 4096, SHA384withRSA, valid 2026-10-24 → 2108-12-13 | `keytool -list -v` |
| Signing certificate SHA-256 | `B8:D4:60:94:2F:88:EE:74:D2:59:6B:BD:D3:4E:6A:44:F4:4D:CF:47:BF:DE:0D:0C:51:06:BF:A5:53:70:BE:C6` | apksig + keytool |
| `allowBackup` | `false` | `aapt2 dump xmltree` |
| Debuggable | no (release build, D8 `--release`) | `aapt2 dump badging` (no `application-debuggable`) |
| Reference check | Static scan of every type referenced from the release dex against the classes in the APK plus API 33 (`android.jar`): nothing missing in Clu's code. The gaps are optional paths in libraries: desktop-JVM classes (log4j, AWT, `sun.misc`, `java.lang.instrument`), OkHttp's optional TLS providers (Conscrypt/BouncyCastle/OpenJSSE), compression codecs (xz, zstd, brotli), Twilio `audioswitch` (only used by the SDK's default audio handler, which Clu replaces with its own; built lazily through a Dagger `Provider`), and `livekit.org.jni_zero.JniZeroJni` (only used by `JniZero.setJniClassLoader`, which nothing calls; also absent from upstream's artifact). Details: docs/LIVEKIT_AGENT.md §6 | dex type-reference scan |
| Regex compatibility | every regex literal compiles with ICU (the engine Android uses) | `:app:icuRegexCheck` |
| Test code in APK | none (no `androidx/`, `org/robolectric`, `org/junit` classes in `classes.dex`) | `strings classes.dex` |
| Static API check | 793 classes (app, core), every platform reference exists at API 33. The prebuilt LiveKit SDK is not part of this check: it is built by LiveKit for minSdk 21 | `:app:apiCheck` |
| APK structure check | 25 manifest components exist; 376 JNI methods (Clu, sherpa-onnx, LiveKit's prefixed WebRTC, JNA) resolve to exported native symbols; all bundled models present. Exception, documented: WebRTC's AV1 *video* encoder is not compiled in and is unreachable (Clu sends no video) | `:app:verifyApk -Pclu.verify=release` |

**Check the download on the tablet (optional):** the file size should be 85,100,556 bytes (Files → file → Details). To
check the SHA-256 without a PC, use any "hash checker" app from Google Play on the downloaded file. It must match the
value above.

**Signer mismatch on update:** if an APK signed with a different key is installed over Clu, Android refuses with
*"App not installed as package conflicts with an existing package"*. Your data is untouched in that case.

### Native libraries (`lib/arm64-v8a/`)

| Library | Size | SHA-256 | Dynamic dependencies (`readelf -d` NEEDED) | LOAD alignment |
|---|---|---|---|---|
| `libclu_llama.so` (llama.cpp + JNI, built here) | 6.3 MB | `2f208149a8b9fe51f91d88b69ec32055a16bdcaaa3f4b90e3b05ff41e7eee690` | liblog, libdl, libm, libc | 16 KB |
| `libclu_whisper.so` (whisper.cpp 231638a0 + JNI, built here; only JNI symbols exported) | 1.6 MB | `70a65e42a1dfd8bd2c526c16de6a4250a58d8cae727cf0fbf68bfd16a32a7ad6` | liblog, libdl, libm, libc | 16 KB |
| `libsherpa-onnx-jni.so` (prebuilt, sherpa-onnx 1.13.8) | 4.8 MB | `3c6492ea91ea68fd3b72b9495efdde6dd913f6b58c387e3b07389cf335f4d26f` | libandroid, liblog, libonnxruntime, libm, libdl, libc | 16 KB |
| `libonnxruntime.so` (prebuilt, from the same archive) | 22.2 MB | `33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1` | libdl, liblog, libm, libc | 16 KB |
| `liblkjingle_peerconnection_so.so` (prebuilt WebRTC with `livekit.` prefix, io.livekit:android-prefixed 150.7871.04, from the official SDK; server mode only) | 12.3 MB | `6b0f579e043dfc6bd39d22d86f2c3855e0039988edbde52392450893810c3772` | libEGL, libdl, libm, liblog, libc | 16 KB |
| `liblivekit_uniffi.so` (prebuilt, io.livekit:livekit-uniffi-android 0.1.12; token/JWT helpers of the SDK) | 1.6 MB | `5a361b8d7ecf4ef4ca4a4788d671c72e2de1b54c3605e888951225b00cb02c93` | libdl, libm, libc | 16 KB |
| `libjnidispatch.so` (prebuilt, JNA 5.19.1; used by livekit-uniffi) | 0.2 MB | `abc26e994517bcaa3309acdb0a27373864086c7569c89d3087b8626fada9ef06` | libc, libdl, libm | 16 KB |

All dependencies are Android system libraries (or each other), so there is no hidden third-party `.so`. 16 KB
alignment keeps the libraries loadable on 16 KB-page kernels. `libclu_llama.so` links libc++ statically.

## 3. Provenance of every downloaded input

`scripts/setup_toolchain.sh` downloads each file below and **checks its SHA-256 before use**. On a mismatch the
build stops. The hashes were fixed when the files were first downloaded. They prove the files have not changed
since then, but not who originally published them. The trust anchors are the HTTPS publishers listed.

| Input | Source URL (publisher) | SHA-256 | Used for |
|---|---|---|---|
| android.jar API 35 | `raw.githubusercontent.com/Sable/android-platforms/1e98db1a…/android-35/android.jar` (community mirror of SDK stubs) | `4566663c…b32e619b` | compile-only stubs (not shipped) |
| android.jar API 33 | same repo/commit, `android-33/android.jar` | `4fade8d5…b4c78646` | `apiCheck` only |
| aapt2 2.20 (inside apktool 3.0.3) | `github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar` | `dbf930b0…14f9423` | resource compile/link |
| D8 8.11.18 | `storage.googleapis.com/r8-releases/raw/8.11.18/r8lib.jar` (Google) | `fbe6f7d5…c8736a7` | dex compilation |
| apksig / zipalign (uber-apk-signer 1.3.0) | `github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/…` | `e1299fd6…bba1d835` | signing + verification |
| sherpa-onnx 1.13.8 Android libs | `github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2` (k2-fsa) | `2ff63469…a6b4f44` | `libsherpa-onnx-jni.so`, `libonnxruntime.so` (**shipped**) |
| Silero VAD | `…/sherpa-onnx/releases/download/asr-models/silero_vad.onnx` | `9e2449e1…f6` | bundled model (**shipped**) |
| KWS zipformer gigaspeech 3.3M | `…/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2` | `f170013b…f9b6561a` | bundled model (**shipped**) |
| Streaming zipformer en 20M | `…/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2` | `9c559283…d752765d9` | bundled model (**shipped**) |
| Android NDK r29 (community build) | `github.com/HomuHomu833/android-ndk-custom/releases/download/r29/android-ndk-r29-x86_64-linux-gnu.tar.xz` | `adf4cb3e…7cd4378` | **only** the Bionic sysroot (headers, stub libs, libc++ static) and compiler-rt builtins |
| androidx.test monitor sources | `github.com/android/android-test` @ `edea994e9ad1…` (git) | git commit hash | test-only shim (not shipped) |
| llama.cpp | `github.com/ggml-org/llama.cpp` tag b11844, submodule commit `06230cb` | git commit hash | compiled into `libclu_llama.so` |
| LiveKit Android SDK 2.29.2 (Apache-2.0) | `maven-central…/io/livekit/livekit-android/2.29.2/livekit-android-2.29.2.aar` | `4ede9085…c379f076` | classes (**shipped**) |
| LiveKit prefixed WebRTC 150.7871.04 (BSD) | `maven-central…/io/github/webrtc-sdk/android-prefixed/150.7871.04/android-prefixed-150.7871.04.aar` | `96f1ebbd…7db5d8b3` | classes + arm64 `liblkjingle_peerconnection_so.so` (**shipped**) |
| livekit-uniffi-android 0.1.12 (Apache-2.0) | `maven-central…/io/livekit/livekit-uniffi-android/0.1.12/…aar` | `bf4a46a5…8b8a8d8a3d` | classes + `liblivekit_uniffi.so` (**shipped**) |
| JNA 5.19.1 (Apache-2.0/LGPL) and jwtdecode 2.0.2 (MIT) | `maven-central…/net/java/dev/jna/jna/5.19.1/jna-5.19.1.aar`, `…/com/auth0/android/jwtdecode/2.0.2/…aar` | `b57125cb…1624ed`, `89d4d1cd…e8fe72` | classes + `libjnidispatch.so` (**shipped**) |

Full hashes are in `scripts/setup_toolchain.sh`.

**Why a community NDK:** `dl.google.com` (the official NDK host) is blocked in the build environment. The compiler
itself is **not** from that archive. Native code is compiled by the host's distribution compiler,
`Ubuntu clang 18.1.3 (1ubuntu1)` from the Ubuntu 24.04 archive (signed apt). The community archive supplies only
the Bionic headers and stub libraries, which carry no code (the device's own system libraries are used at runtime),
plus libc++/compiler-rt, which are linked into `libclu_llama.so`. Residual risk: a tampered libc++ or compiler-rt
in that archive would end up in `libclu_llama.so`. To remove that risk, rebuild once `dl.google.com` is allowed, using
the official NDK r29's standard `android.toolchain.cmake` instead of `native/android-arm64.toolchain.cmake`. The sherpa-onnx and
ONNX Runtime libraries are the upstream project's own prebuilt release binaries, used unmodified.

**Host toolchain:**
* OpenJDK 21.0.12.1 (Ubuntu package)
* Gradle 8.14.3 (wrapper, from `services.gradle.org`)
* Kotlin 2.2.21

**Java/Kotlin libraries shipped in the APK** come from Maven Central through Google's mirror
(`maven-central.storage-download.googleapis.com`), over HTTPS:

| Library | SHA-256 of the jar used |
|---|---|
| kotlin-stdlib 2.2.21 | `6558a3d2…6ef098f78a2c223dc6abb79dd` |
| kotlinx-coroutines-core-jvm 1.10.2 | `5ca175b3…dd8cae1251fa9ee369709b36d42e0a288ccce3fd` |
| kotlinx-coroutines-android 1.10.2 | `e713f1f8…a07571065cffa0f24f5e78300e9720fea16de3af1d75fd41` |
| kotlinx-serialization-core-jvm 1.9.0 | `1f0afa17…c1ebaff96f3fc3ad68ef8c48120b59c` |
| kotlinx-serialization-json-jvm 1.9.0 | `d94cc34c…216ef641d5fa3bbbb539a6922d8` |
| commons-compress 1.27.1 (voice-pack extraction) | `293d80f5…402519281332495f4420d370d16` |
| commons-io 2.16.1, commons-lang3 3.16.0, commons-codec 1.17.1 (its dependencies) | `f41f7baa…`, `08709dd7…`, `f9f6cb10…` |
| org.jetbrains annotations 23.0.0 | `7b0f1972…` |
| OkHttp 4.12.0 (LiveKit signalling WebSocket) | `b1050081…91d5f4e0` |
| Okio 3.6.0 (OkHttp dependency) | `67543f07…cb713da28412` |
| protobuf-javalite 3.25.9, Dagger 2.46 + javax.inject 1, Gson 2.8.9, semver4j 3.1.0, android-jain-sip-ri 1.3.0-91 (LiveKit SDK dependencies) | `e85ac75f…`, `2250c583…`, `91c77044…`, `d3999291…`, `0f33724d…`, `3d91eb33…` |

Gap: Gradle's dependency-verification file and the wrapper's `distributionSha256Sum` are not yet set up, so Maven and
Gradle downloads rely on HTTPS alone. The hashes above record what was actually used for this build.

## 4. Reproducing the APK

```bash
git clone -b claude/clu-android-ai-build-5cspi2 --recurse-submodules https://github.com/Ashtro10/Archivum-0x01
scripts/setup_toolchain.sh && scripts/fetch_livekit_sdk.sh && scripts/build_native.sh android
export CLU_KEYSTORE=/path/clu-release.p12 CLU_KEYSTORE_PASSWORD=…   # see SIGNING_AND_BACKUP.md
./gradlew :core:test :app:test :app:apiCheck :app:verifyApk :app:assembleRelease -Pclu.verify=release
```

Until 1.3.0 the build logic in `buildSrc/src/main/kotlin/clu/build/` (APK packaging, signing, verification) was
excluded from git by an over-broad `build/` ignore rule, so earlier commits could not be rebuilt from the repository
alone. It is committed from 1.3.0.

The build is not bit-for-bit reproducible across machines. Native code and zip timestamps are normalised, but
compiler versions may differ. An update needs only the same signing key, not the same bytes.
