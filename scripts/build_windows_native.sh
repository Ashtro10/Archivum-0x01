#!/bin/bash
# Cross-builds CLU's llama.cpp JNI library for Windows x64 (clu_llama.dll) on Linux with MinGW-w64 (Ubuntu package
# g++-mingw-w64-x86-64-posix). Output: native/build/cmake-windows/clu_llama.dll (picked up by :desktop:installDist).
set -euo pipefail
cd "$(dirname "$0")/../native"
JDK_INCLUDE="${JDK_INCLUDE:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")/include}"
cmake -S . -B build/cmake-windows -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$PWD/windows-x64.toolchain.cmake" -DCLU_JDK_INCLUDE="$JDK_INCLUDE"
ninja -C build/cmake-windows clu_llama
x86_64-w64-mingw32-objdump -p build/cmake-windows/clu_llama.dll | grep "DLL Name"   # only Windows system DLLs
sha256sum build/cmake-windows/clu_llama.dll
