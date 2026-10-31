/*
 * clu.exe - CLU's launcher. It is what the Start Menu entry, the desktop shortcut and "start with Windows" run.
 *
 * It does only what a launcher must, so that the user never sees a console, a .bat file or a command line:
 *   1. finds the bundled Java runtime next to itself (runtime\bin\javaw.exe) and CLU's jars (lib\*),
 *   2. tells CLU facts only Windows knows (where this program is, whether the processor has AVX2),
 *   3. starts CLU's host, with its error output going to %LOCALAPPDATA%\CLU\logs\launcher.log,
 *   4. if CLU stops within a few seconds with an error, shows the reason in a message box instead of failing silently.
 * Everything else (single instance, window, tray, model) is CLU's host, written in Kotlin.
 *
 * Build: x86_64-w64-mingw32-gcc -O2 -s -municode -mwindows -static clu_launcher.c clu.res -o clu.exe
 */
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shlwapi.h>
#include <stdio.h>
#include <wchar.h>

#ifndef PF_AVX2_INSTRUCTIONS_AVAILABLE
#define PF_AVX2_INSTRUCTIONS_AVAILABLE 40
#endif

#define MAXCMD 32768

static void fail(const wchar_t *text) { MessageBoxW(NULL, text, L"CLU", MB_OK | MB_ICONERROR | MB_SETFOREGROUND); }

/* The arguments after our own name, exactly as the user (or Windows) gave them. */
static const wchar_t *rest_of_command_line(void) {
    const wchar_t *p = GetCommandLineW();
    if (*p == L'"') { p++; while (*p && *p != L'"') p++; if (*p) p++; }
    else { while (*p && *p != L' ' && *p != L'\t') p++; }
    while (*p == L' ' || *p == L'\t') p++;
    return p;
}

static int has_flag(const wchar_t *args, const wchar_t *flag) {
    size_t n = wcslen(flag);
    for (const wchar_t *p = args; (p = wcsstr(p, flag)) != NULL; p += n)
        if ((p == args || p[-1] == L' ') && (p[n] == 0 || p[n] == L' ')) return 1;
    return 0;
}

/* Last bytes of the launcher log, to show when CLU could not start. */
static void log_tail(const wchar_t *path, wchar_t *out, size_t outChars) {
    out[0] = 0;
    HANDLE f = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING, 0, NULL);
    if (f == INVALID_HANDLE_VALUE) return;
    LARGE_INTEGER sz; char buf[900]; DWORD got = 0;
    if (GetFileSizeEx(f, &sz)) {
        LONGLONG start = sz.QuadPart > (LONGLONG)(sizeof buf - 1) ? sz.QuadPart - (LONGLONG)(sizeof buf - 1) : 0;
        LARGE_INTEGER at; at.QuadPart = start; SetFilePointerEx(f, at, NULL, FILE_BEGIN);
        ReadFile(f, buf, sizeof buf - 1, &got, NULL);
    }
    CloseHandle(f); buf[got] = 0;
    MultiByteToWideChar(CP_UTF8, 0, buf, -1, out, (int)outChars);
}

int WINAPI wWinMain(HINSTANCE hInst, HINSTANCE hPrev, LPWSTR cmdLine, int show) {
    (void)hInst; (void)hPrev; (void)cmdLine; (void)show;
    wchar_t exe[MAX_PATH], dir[MAX_PATH], java[MAX_PATH], jars[MAX_PATH + 8];
    if (!GetModuleFileNameW(NULL, exe, MAX_PATH)) { fail(L"CLU could not find its own location."); return 2; }
    wcscpy(dir, exe); PathRemoveFileSpecW(dir);
    _snwprintf(java, MAX_PATH, L"%ls\\runtime\\bin\\javaw.exe", dir);
    _snwprintf(jars, MAX_PATH + 8, L"%ls\\lib\\*", dir);
    wchar_t mainJar[MAX_PATH]; _snwprintf(mainJar, MAX_PATH, L"%ls\\lib", dir);
    if (!PathFileExistsW(java) || !PathIsDirectoryW(mainJar)) {
        fail(L"CLU's program files are incomplete (the bundled Java runtime or the program libraries are missing).\n\n"
             L"Run the CLU installer again to repair the installation. Your data (memories, conversations, settings) is stored separately and is not affected.");
        return 2;
    }

    /* Facts for the host: where the launcher is (for "Restart" and "start with Windows"), and whether the CPU can run the engine. */
    SetEnvironmentVariableW(L"CLU_LAUNCHER", exe);
    SetEnvironmentVariableW(L"CLU_APP_DIR", dir);
    SetEnvironmentVariableW(L"CLU_AVX2", IsProcessorFeaturePresent(PF_AVX2_INSTRUCTIONS_AVAILABLE) ? L"1" : L"0");

    /* The data folder (for the launcher log): CLU_HOME if set, else %LOCALAPPDATA%\CLU. */
    wchar_t home[MAX_PATH] = L"", logdir[MAX_PATH], logfile[MAX_PATH];
    if (!GetEnvironmentVariableW(L"CLU_HOME", home, MAX_PATH)) {
        wchar_t la[MAX_PATH] = L"";
        if (GetEnvironmentVariableW(L"LOCALAPPDATA", la, MAX_PATH)) _snwprintf(home, MAX_PATH, L"%ls\\CLU", la);
        else { GetTempPathW(MAX_PATH, la); _snwprintf(home, MAX_PATH, L"%lsCLU", la); }
    }
    _snwprintf(logdir, MAX_PATH, L"%ls\\logs", home);
    CreateDirectoryW(home, NULL); CreateDirectoryW(logdir, NULL);
    _snwprintf(logfile, MAX_PATH, L"%ls\\launcher.log", logdir);

    const wchar_t *args = rest_of_command_line();
    wchar_t *cmd = (wchar_t *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, MAXCMD * sizeof(wchar_t));
    if (!cmd) return 2;
    _snwprintf(cmd, MAXCMD,
        L"\"%ls\" -Xmx384m -XX:+UseSerialGC -XX:MaxMetaspaceSize=160m -Xss1m -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 "
        L"-Djava.awt.headless=false -cp \"%ls\" ai.clu.desktop.HostMainKt %ls", java, jars, args);

    SECURITY_ATTRIBUTES sa = { sizeof sa, NULL, TRUE };
    HANDLE log = CreateFileW(logfile, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE, &sa, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (log != INVALID_HANDLE_VALUE) {
        LARGE_INTEGER sz; if (GetFileSizeEx(log, &sz) && sz.QuadPart > 1024 * 1024) { SetFilePointer(log, 0, NULL, FILE_BEGIN); SetEndOfFile(log); } /* keep it small */
    }
    STARTUPINFOW si; ZeroMemory(&si, sizeof si); si.cb = sizeof si;
    PROCESS_INFORMATION pi; ZeroMemory(&pi, sizeof pi);
    if (log != INVALID_HANDLE_VALUE) { si.dwFlags = STARTF_USESTDHANDLES; si.hStdOutput = log; si.hStdError = log; si.hStdInput = GetStdHandle(STD_INPUT_HANDLE); }
    BOOL ok = CreateProcessW(java, cmd, NULL, NULL, TRUE, CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT, NULL, dir, &si, &pi);
    HeapFree(GetProcessHeap(), 0, cmd);
    if (!ok) {
        wchar_t m[600]; _snwprintf(m, 600, L"CLU could not start its program (Windows error %lu).\n\nTry running the CLU installer again to repair it.", GetLastError());
        fail(m); if (log != INVALID_HANDLE_VALUE) CloseHandle(log); return 2;
    }
    if (log != INVALID_HANDLE_VALUE) CloseHandle(log);

    /* Short commands (quit, backup) are waited for, so the installer can rely on them having finished. */
    DWORD wait = 6000;
    if (has_flag(args, L"--quit")) wait = 30000;
    if (has_flag(args, L"--backup")) wait = 600000;
    DWORD r = WaitForSingleObject(pi.hProcess, wait), code = 0;
    if (r == WAIT_OBJECT_0) {
        GetExitCodeProcess(pi.hProcess, &code);
        if (code != 0) {
            wchar_t tail[1000], m[1700]; log_tail(logfile, tail, 1000);
            _snwprintf(m, 1700, L"CLU stopped right after starting (code %lu).\n\n%ls\n\nMore detail: %ls", code, tail[0] ? tail : L"(no message)", logfile);
            fail(m);
        }
    }
    CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
    return (r == WAIT_OBJECT_0) ? (int)code : 0;
}
