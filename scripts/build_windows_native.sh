#!/bin/bash
# Cross-builds CLU's llama.cpp JNI library for Windows x64 (clu_llama.dll) on Linux with MinGW-w64 (Ubuntu package
# g++-mingw-w64-x86-64-posix). Output: native/build/cmake-windows/clu_llama.dll (picked up by :desktop:installDist).
#
#   scripts/build_windows_native.sh            CPU build (AVX2/FMA/F16C), needs only Windows itself
#   scripts/build_windows_native.sh --vulkan   also the optional GPU build -> native/build/cmake-windows-vulkan/clu_llama.dll
#                                              (needs: apt install glslc libvulkan-dev spirv-headers). It is built, NOT yet run on a
#                                              GPU: it imports vulkan-1.dll (installed with the graphics driver) and the host loads
#                                              it only if the owner asked for the GPU and that DLL exists, else it uses the CPU build.
set -euo pipefail
cd "$(dirname "$0")/../native"
JDK_INCLUDE="${JDK_INCLUDE:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")/include}"
cmake -S . -B build/cmake-windows -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$PWD/windows-x64.toolchain.cmake" -DCLU_JDK_INCLUDE="$JDK_INCLUDE"
ninja -C build/cmake-windows clu_llama
x86_64-w64-mingw32-objdump -p build/cmake-windows/clu_llama.dll | grep "DLL Name"   # only Windows system DLLs
sha256sum build/cmake-windows/clu_llama.dll

if [ "${1:-}" = "--vulkan" ]; then
  # ggml's Vulkan backend loads the Vulkan API through a dynamic dispatcher, so the Windows build needs only the headers and a
  # tiny import library for the handful of entry points it links statically (taken from the compiled objects, below).
  P="$PWD/build/vulkan-prefix"; rm -rf "$P"; mkdir -p "$P/include" "$P/lib"
  cp -r /usr/include/vulkan /usr/include/vk_video /usr/include/spirv "$P/include/"
  printf 'LIBRARY vulkan-1.dll\nEXPORTS\nvkGetInstanceProcAddr\nvkGetDeviceProcAddr\n' > "$P/lib/vulkan-1.def"
  x86_64-w64-mingw32-dlltool -d "$P/lib/vulkan-1.def" -l "$P/lib/libvulkan-1.dll.a" -D vulkan-1.dll
  cfg=(cmake -S . -B build/cmake-windows-vulkan -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE="$PWD/windows-x64.toolchain.cmake"
       -DCLU_JDK_INCLUDE="$JDK_INCLUDE" -DGGML_VULKAN=ON -DVulkan_INCLUDE_DIR="$P/include" -DVulkan_LIBRARY="$P/lib/libvulkan-1.dll.a"
       -DVulkan_GLSLC_EXECUTABLE=/usr/bin/glslc -DCMAKE_PREFIX_PATH=/usr)
  "${cfg[@]}"
  ninja -C build/cmake-windows-vulkan ggml-vulkan || true            # compiles shaders and objects; the link of clu_llama below needs the symbol list
  x86_64-w64-mingw32-nm -u build/cmake-windows-vulkan/llama.cpp/ggml/src/ggml-vulkan/ggml-vulkan.a | awk '{print $2}' | grep -E "^vk[A-Z]" | sort -u > "$P/syms.txt"
  (printf 'LIBRARY vulkan-1.dll\nEXPORTS\n'; cat "$P/syms.txt") > "$P/lib/vulkan-1.def"
  x86_64-w64-mingw32-dlltool -d "$P/lib/vulkan-1.def" -l "$P/lib/libvulkan-1.dll.a" -D vulkan-1.dll
  ninja -C build/cmake-windows-vulkan clu_llama
  x86_64-w64-mingw32-objdump -p build/cmake-windows-vulkan/clu_llama.dll | grep "DLL Name"   # + vulkan-1.dll
  sha256sum build/cmake-windows-vulkan/clu_llama.dll
fi
