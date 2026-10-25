cmake_minimum_required(VERSION 3.22)
project(clu_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Hide everything but the JNI entry points (see clu_whisper.map): whisper.cpp brings its own ggml copy, which must
# not interpose on the one inside libclu_llama.so.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory(../whisper.cpp whisper.cpp EXCLUDE_FROM_ALL)

find_package(JNI QUIET)
add_library(clu_whisper SHARED ../jni/clu_whisper_jni.cpp)
target_link_libraries(clu_whisper PRIVATE whisper ggml)
target_link_options(clu_whisper PRIVATE -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/clu_whisper.map -Wl,-Bsymbolic)
if(CMAKE_SYSTEM_NAME STREQUAL "Android" OR CLU_ANDROID)
    target_link_libraries(clu_whisper PRIVATE log)
    target_link_options(clu_whisper PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections -static-libstdc++)
else()
    target_include_directories(clu_whisper PRIVATE ${JNI_INCLUDE_DIRS})
endif()
