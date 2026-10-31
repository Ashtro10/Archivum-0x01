/* Windows SDK definitions missing from MinGW-w64's headers (Ubuntu's mingw-w64 11), force-included into llama.cpp's
 * CPU backend for the Windows cross-build only (llama.cpp itself is unchanged). Values as documented for
 * SetThreadInformation / THREAD_POWER_THROTTLING_STATE in the Windows SDK (processthreadsapi.h). */
#ifndef CLU_MINGW_COMPAT_H
#define CLU_MINGW_COMPAT_H
#ifndef THREAD_POWER_THROTTLING_CURRENT_VERSION
#define THREAD_POWER_THROTTLING_CURRENT_VERSION 1
#define THREAD_POWER_THROTTLING_EXECUTION_SPEED 0x1
typedef struct _THREAD_POWER_THROTTLING_STATE {
    unsigned long Version;
    unsigned long ControlMask;
    unsigned long StateMask;
} THREAD_POWER_THROTTLING_STATE;
#endif
#endif
