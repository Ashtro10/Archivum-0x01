#!/usr/bin/env bash
# Fetches the pinned build toolchain for Clu into ./toolchain (git-ignored).
#
# Why this exists: the standard Android toolchain (SDK manager, Google Maven,
# AGP) is served from dl.google.com. Clu's build is designed so it can be
# produced from sources that are reachable when dl.google.com is not:
#   - android.jar (API 35)   GitHub: Sable/android-platforms (official SDK jar)
#   - aapt2 (linux x86_64)   GitHub: iBotPeaches/Apktool release (bundled aapt2)
#   - D8/R8                  storage.googleapis.com/r8-releases (official)
#   - apksig                 GitHub: patrickfav/uber-apk-signer release (bundles apksig)
#   - sherpa-onnx JNI libs   GitHub: k2-fsa/sherpa-onnx release
#   - Bionic sysroot         GitHub: HomuHomu833/android-ndk-custom r29 (only the
#                            sysroot + compiler-rt are used; compilation uses the
#                            host's own clang, see scripts/build_native.sh)
# Every file is verified against a pinned SHA-256.
#
# Set TOOLCHAIN_CACHE=<dir> to reuse previously downloaded files.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="$ROOT/toolchain"
CACHE="${TOOLCHAIN_CACHE:-$TC/.cache}"
mkdir -p "$TC" "$CACHE"

fetch() { # name url sha256
  local name="$1" url="$2" sha="$3" out="$CACHE/$1"
  if [[ -f "$out" ]] && echo "$sha  $out" | sha256sum -c --quiet - 2>/dev/null; then
    echo "[ok]   $name (cached)"; return
  fi
  echo "[get]  $name"
  local tries=0
  until curl -fsSL --retry 3 -o "$out.part" "$url"; do
    tries=$((tries+1)); [[ $tries -ge 4 ]] && { echo "download failed: $url" >&2; exit 1; }
    sleep $((2**tries))
  done
  mv "$out.part" "$out"
  echo "$sha  $out" | sha256sum -c --quiet - || { echo "checksum mismatch: $name" >&2; rm -f "$out"; exit 1; }
}

fetch android.jar \
  https://raw.githubusercontent.com/Sable/android-platforms/1e98db1a199e8f7f85541af26bfc27019501b132/android-35/android.jar \
  4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b
# API 33 (= minSdk) platform jar, used only by the :app:apiCheck task (static NewApi-style verification).
fetch android-33.jar \
  https://raw.githubusercontent.com/Sable/android-platforms/1e98db1a199e8f7f85541af26bfc27019501b132/android-33/android.jar \
  4fade8d5e04130bd8ccd74d5fec6c86b01ad75c3ee877ccc5d660255c4c78646
fetch apktool.jar \
  https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar \
  dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423
fetch r8lib.jar \
  https://storage.googleapis.com/r8-releases/raw/8.11.18/r8lib.jar \
  fbe6f7d59d46d6296919f6cc4793536439921d357c9a6bbf45ea4acc3c8736a7
fetch uber-apk-signer.jar \
  https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar \
  e1299fd6fcf4da527dd53735b56127e8ea922a321128123b9c32d619bba1d835
fetch sherpa-android.tar.bz2 \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2 \
  2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44

cp "$CACHE/android.jar" "$TC/android.jar"
cp "$CACHE/android-33.jar" "$TC/android-33.jar"
cp "$CACHE/r8lib.jar" "$TC/r8lib.jar"
cp "$CACHE/uber-apk-signer.jar" "$TC/apksig.jar"
unzip -o -q -j "$CACHE/apktool.jar" prebuilt/linux/aapt2 -d "$TC"
chmod +x "$TC/aapt2"

rm -rf "$TC/jniLibs" && mkdir -p "$TC/jniLibs/arm64-v8a"
tmp="$(mktemp -d)"
tar xjf "$CACHE/sherpa-android.tar.bz2" -C "$tmp"
cp "$tmp/jniLibs/arm64-v8a/libonnxruntime.so" "$tmp/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$TC/jniLibs/arm64-v8a/"
rm -rf "$tmp"

# Offline voice models bundled in the APK (sherpa-onnx releases): Silero VAD, KWS wake word, streaming ASR (int8).
R=https://github.com/k2-fsa/sherpa-onnx/releases/download
fetch silero_vad.onnx "$R/asr-models/silero_vad.onnx" 9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6
fetch kws.tar.bz2 "$R/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2" f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a
fetch asr.tar.bz2 "$R/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2" 9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9
A="$TC/assets/models"
if [[ ! -f "$A/asr/encoder.int8.onnx" ]]; then
  echo "[unpack] bundled voice models"
  rm -rf "$A" && mkdir -p "$A/vad" "$A/kws" "$A/asr"
  cp "$CACHE/silero_vad.onnx" "$A/vad/silero_vad.onnx"
  tmp="$(mktemp -d)"
  tar xjf "$CACHE/kws.tar.bz2" -C "$tmp"; K="$tmp/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
  cp "$K/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$A/kws/encoder.int8.onnx"
  cp "$K/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$A/kws/decoder.int8.onnx"
  cp "$K/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$A/kws/joiner.int8.onnx"
  cp "$K/tokens.txt" "$K/bpe.model" "$A/kws/"
  # Default keyword list required at construction; the app supplies the configured wake words per stream.
  printf '\xe2\x96\x81C LU E @CLUE\n' > "$A/kws/keywords.txt"
  tar xjf "$CACHE/asr.tar.bz2" -C "$tmp"; S="$tmp/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
  cp "$S/encoder-epoch-99-avg-1.int8.onnx" "$A/asr/encoder.int8.onnx"
  cp "$S/decoder-epoch-99-avg-1.int8.onnx" "$A/asr/decoder.int8.onnx"
  cp "$S/joiner-epoch-99-avg-1.int8.onnx" "$A/asr/joiner.int8.onnx"
  cp "$S/tokens.txt" "$A/asr/"
  rm -rf "$tmp"
fi

# androidx.test monitor sources (Apache-2.0) for the Robolectric UI smoke tests (test-only).
if [[ ! -d "$TC/androidx-test/runner/monitor/java" ]]; then
  echo "[get]  androidx.test monitor sources"
  rm -rf "$TC/androidx-test"
  git clone -q --filter=blob:none --no-checkout https://github.com/android/android-test "$TC/androidx-test"
  git -C "$TC/androidx-test" sparse-checkout set runner/monitor/java espresso/idling_resource/java
  git -C "$TC/androidx-test" checkout -q edea994e9ad118c4ac6869c7105dcd06d66da7df
fi

# ---- Optional LiveKit voice path (lkclient module) ----
# protoc for the LiveKit signalling protobufs (javalite), the LiveKit protocol definitions (Apache-2.0) at a pinned
# commit, and the WebRTC engine (io.github.webrtc-sdk:android, BSD) - only its classes jar and arm64 library are used.
M=https://maven-central.storage-download.googleapis.com/maven2
fetch protoc "$M/com/google/protobuf/protoc/4.36.2/protoc-4.36.2-linux-x86_64.exe" \
  f6ee22d65361338459208da8bff801c9ca689741cba9187b34990dd45198f864
cp "$CACHE/protoc" "$TC/protoc" && chmod +x "$TC/protoc"
if [[ ! -f "$TC/livekit-protocol/protobufs/livekit_rtc.proto" ]]; then
  echo "[get]  LiveKit protocol definitions"
  rm -rf "$TC/livekit-protocol"
  git clone -q --filter=blob:none --no-checkout https://github.com/livekit/protocol "$TC/livekit-protocol"
  git -C "$TC/livekit-protocol" sparse-checkout set --no-cone /protobufs/livekit_rtc.proto /protobufs/livekit_models.proto /protobufs/livekit_metrics.proto /LICENSE
  git -C "$TC/livekit-protocol" checkout -q 5f3bf01d03f8242c83eacf6bdb2d77142f7c8ac4   # tag v1.53.0
fi
fetch webrtc-sdk.aar "$M/io/github/webrtc-sdk/android/150.7871.04/android-150.7871.04.aar" \
  1872dda4e3bf7d6ea6f7c6e11861332081c57081a1e62fc82fab72415c69c84c
mkdir -p "$TC/webrtc"
unzip -o -q -p "$CACHE/webrtc-sdk.aar" classes.jar > "$TC/webrtc/webrtc-sdk.jar"
unzip -o -q -j "$CACHE/webrtc-sdk.aar" jni/arm64-v8a/libjingle_peerconnection_so.so -d "$TC/jniLibs/arm64-v8a"

if [[ "${SKIP_NDK:-0}" != "1" ]]; then
  fetch ndk-r29.tar.xz \
    https://github.com/HomuHomu833/android-ndk-custom/releases/download/r29/android-ndk-r29-x86_64-linux-gnu.tar.xz \
    adf4cb3ec6b866f5a665da888e06cb5dfdd1b22638745813d4cba658a7cd4378
  if [[ ! -d "$TC/ndk/sysroot" ]]; then
    echo "[unpack] Bionic sysroot + compiler-rt"
    rm -rf "$TC/ndk" && mkdir -p "$TC/ndk"
    P=android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64
    tar xJf "$CACHE/ndk-r29.tar.xz" -C "$TC/ndk" "$P/sysroot" "$P/lib/clang"
    mv "$TC/ndk/$P/sysroot" "$TC/ndk/sysroot"
    mv "$TC/ndk/$P/lib/clang/"*/lib "$TC/ndk/clang-rt-lib"
    rm -rf "$TC/ndk/android-ndk-r29"
  fi
fi

"$TC/aapt2" version
java -cp "$TC/r8lib.jar" com.android.tools.r8.D8 --version | head -1
echo "Toolchain ready in $TC"
