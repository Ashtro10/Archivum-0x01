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
cp "$CACHE/r8lib.jar" "$TC/r8lib.jar"
cp "$CACHE/uber-apk-signer.jar" "$TC/apksig.jar"
unzip -o -q -j "$CACHE/apktool.jar" prebuilt/linux/aapt2 -d "$TC"
chmod +x "$TC/aapt2"

rm -rf "$TC/jniLibs" && mkdir -p "$TC/jniLibs/arm64-v8a"
tmp="$(mktemp -d)"
tar xjf "$CACHE/sherpa-android.tar.bz2" -C "$tmp"
cp "$tmp/jniLibs/arm64-v8a/libonnxruntime.so" "$tmp/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$TC/jniLibs/arm64-v8a/"
rm -rf "$tmp"

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
