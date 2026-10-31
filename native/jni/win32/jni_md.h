/* Windows (x64) machine-dependent JNI types, for cross-compiling CLU's JNI libraries with MinGW-w64 from Linux, whose
 * JDK ships only the Linux variant of this header. Values follow the JNI specification for Win32/Win64. */
#ifndef CLU_JNI_MD_H
#define CLU_JNI_MD_H
#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall
typedef long jint;
typedef long long jlong;
typedef signed char jbyte;
#endif
