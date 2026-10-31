cmake_minimum_required(VERSION 3.22)
project(clu_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# llama.cpp configuration: static, CPU backend only, no tools/tests/server.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)
if(CLU_WINDOWS)
    # MinGW-w64's headers lack one newer Windows SDK type llama.cpp uses; supply it without touching llama.cpp.
    target_compile_options(ggml-cpu PRIVATE -include "${CMAKE_CURRENT_LIST_DIR}/jni/win32/mingw_compat.h")
endif()

find_package(JNI QUIET)
add_library(clu_llama SHARED jni/clu_llama_jni.cpp)
target_link_libraries(clu_llama PRIVATE llama ggml)
if(CMAKE_SYSTEM_NAME STREQUAL "Android" OR CLU_ANDROID)
    target_link_libraries(clu_llama PRIVATE log)
    target_link_options(clu_llama PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections -static-libstdc++)
elseif(CLU_WINDOWS)
    # Cross-compiled with MinGW-w64 (windows-x64.toolchain.cmake): the host JDK's jni.h + a Windows jni_md.h.
    set_target_properties(clu_llama PROPERTIES PREFIX "")   # clu_llama.dll, not libclu_llama.dll
    target_include_directories(clu_llama PRIVATE "${CMAKE_CURRENT_LIST_DIR}/jni/win32" "${CLU_JDK_INCLUDE}")
    target_link_options(clu_llama PRIVATE -static -static-libgcc -static-libstdc++ -Wl,--gc-sections)   # no MinGW runtime DLLs needed
else()
    target_include_directories(clu_llama PRIVATE ${JNI_INCLUDE_DIRS})
endif()
