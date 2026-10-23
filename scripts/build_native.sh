#!/usr/bin/env bash
# Builds libclu_llama.so (llama.cpp + JNI bridge).
#   android (default): native/build/jniLibs/arm64-v8a/libclu_llama.so (packaged into the APK)
#   host:              native/build/host/libclu_llama.so (used by JVM integration tests)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-android}"
JOBS="$(nproc)"
git -C "$ROOT" submodule update --init --depth 1 native/llama.cpp
if [[ "$TARGET" == "android" ]]; then
  B="$ROOT/native/build/cmake-android"
  cmake -S "$ROOT/native" -B "$B" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ROOT/native/android-arm64.toolchain.cmake"
  cmake --build "$B" --target clu_llama -j "$JOBS"
  mkdir -p "$ROOT/native/build/jniLibs/arm64-v8a"
  llvm-strip-18 --strip-unneeded -o "$ROOT/native/build/jniLibs/arm64-v8a/libclu_llama.so" "$B/libclu_llama.so" 2>/dev/null \
    || cp "$B/libclu_llama.so" "$ROOT/native/build/jniLibs/arm64-v8a/libclu_llama.so"
  ls -la "$ROOT/native/build/jniLibs/arm64-v8a/"
else
  B="$ROOT/native/build/cmake-host"
  cmake -S "$ROOT/native" -B "$B" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++
  cmake --build "$B" --target clu_llama -j "$JOBS"
  mkdir -p "$ROOT/native/build/host" && cp "$B/libclu_llama.so" "$ROOT/native/build/host/"
  ls -la "$ROOT/native/build/host/"
fi
