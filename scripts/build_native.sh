#!/usr/bin/env bash
# Builds the native libraries:
#   libclu_llama.so   (llama.cpp + JNI bridge)
#   libclu_whisper.so (whisper.cpp + JNI bridge; separate library with hidden symbols, since it carries its own ggml)
#   android (default): native/build/jniLibs/arm64-v8a/ (packaged into the APK)
#   host:              native/build/host/ (used by JVM integration tests)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-android}"
JOBS="$(nproc)"
git -C "$ROOT" submodule update --init --depth 1 native/llama.cpp native/whisper.cpp
build() { # <source dir> <build dir> <target> <cmake args...>
  local src="$1" b="$2" t="$3"; shift 3
  cmake -S "$src" -B "$b" -G Ninja -DCMAKE_BUILD_TYPE=Release "$@"
  cmake --build "$b" --target "$t" -j "$JOBS"
}
if [[ "$TARGET" == "android" ]]; then
  TC=(-DCMAKE_TOOLCHAIN_FILE="$ROOT/native/android-arm64.toolchain.cmake")
  OUT="$ROOT/native/build/jniLibs/arm64-v8a"; mkdir -p "$OUT"
  build "$ROOT/native" "$ROOT/native/build/cmake-android" clu_llama "${TC[@]}"
  build "$ROOT/native/whisper" "$ROOT/native/build/cmake-whisper-android" clu_whisper "${TC[@]}"
  for l in clu_llama:cmake-android clu_whisper:cmake-whisper-android; do
    n="${l%%:*}"; d="${l##*:}"
    llvm-strip-18 --strip-unneeded -o "$OUT/lib$n.so" "$ROOT/native/build/$d/lib$n.so" 2>/dev/null || cp "$ROOT/native/build/$d/lib$n.so" "$OUT/lib$n.so"
  done
  ls -la "$OUT"
else
  CC=(-DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++)
  OUT="$ROOT/native/build/host"; mkdir -p "$OUT"
  build "$ROOT/native" "$ROOT/native/build/cmake-host" clu_llama "${CC[@]}"
  build "$ROOT/native/whisper" "$ROOT/native/build/cmake-whisper-host" clu_whisper "${CC[@]}"
  cp "$ROOT/native/build/cmake-host/libclu_llama.so" "$ROOT/native/build/cmake-whisper-host/libclu_whisper.so" "$OUT/"
  ls -la "$OUT"
fi
