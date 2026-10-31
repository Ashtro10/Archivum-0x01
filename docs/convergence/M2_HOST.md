# M2 — CLU host on the PC

_2026-10-31._

* **Status:** built and verified on the build machine (Linux), with a real model.
* **Windows:** the Windows-specific parts (the DLL, the launcher, the file ACLs) are built but **not yet run on
  Windows**. They need a run on your PC.

## What was built

| Piece | Where | Reuses |
|---|---|---|
| Desktop module | `desktop/` (new Gradle module) | The whole `core` unchanged: memory archive, conversation, research, persona, model service |
| Platform | `DesktopPlatform.kt` | `core` contracts (`CluPlatform`, `LlmPlatform`, `KeyVault`). Data folder per user (`%LOCALAPPDATA%\CLU`); owner-only files (POSIX permissions / Windows ACL); AES-GCM vault with a key file only the user can read; secrets file sealed by the vault (`CLU_SECRET_*` env overrides); memory probe (Linux `/proc`, Windows private bytes via the JDK) |
| Shared HTTP client | `core/.../net/JvmHttp.kt` | Moved unchanged from the Android app (`AndroidHttp`), so phone and PC use one client |
| SQLite | `JdbcSqlDriver.kt` | The same SQL as the Android driver; WAL + busy timeout for a long-running process. All migrations run (schema v4) |
| Resource governor v1 | `ResourceGovernor.kt` | Builds on the core's existing load check and idle unload (`model.autoUnloadMin`) |
| Local API | `ApiServer.kt` | JDK built-in HTTP server: loopback only, bearer token, Host/Origin checks, 64 KiB bodies, SSE streaming. Endpoints in `desktop/README.md` |
| Entry point | `HostMain.kt` | `clu-host`, `--register-model`, `--print-token`, `--home`, `--port` |
| Windows llama.cpp JNI | `native/windows-x64.toolchain.cmake`, `scripts/build_windows_native.sh` | CLU's own JNI source and vendored llama.cpp (b11844), cross-built with MinGW-w64 |
| Windows launcher | `bin/clu-host-background.cmd` | Starts the host minimised at below-normal priority |

### Governor rules (first version)

* **A model loads only into what is left.** That means the smaller of: (budget − the host's own memory) and (the PC's
  free memory − a 2 GB reserve for you). The core's existing load check enforces this. That check allows 1.35× slack
  for phones, so the desktop divides by 1.35 to keep the budget a hard ceiling.
* **When the PC runs short** (free memory under 1.5 GB), or CLU exceeds its budget, an idle model is unloaded within
  one check (15 s).
* **A reply in progress is never interrupted.** The unload waits for the next check.
* **Idle unload after N quiet minutes** is the core's own setting (default 20).
* **Priority:** below-normal process priority on Windows through the launcher; the governor thread runs at minimum
  priority.
* **Not yet:** detecting full-screen games and throttling threads. That comes after M1's foreground-slowdown numbers and
  will be added to this governor.

### The Windows DLL

* **Build:** `clu_llama.dll`, 10.0 MB, SHA-256 `df4df95d…5a76c8`, cross-built with MinGW-w64 13.2 for AVX2/FMA/F16C.
* **Dependencies:** only `KERNEL32`, `ADVAPI32` and `msvcrt`, all part of Windows. MinGW's runtime is linked
  statically.
* **Exports:** all 16 JNI functions of `ai.clu.core.llm.LlamaBridge`.
* **Compatibility fix:** MinGW's headers lack one Windows SDK type llama.cpp uses (`THREAD_POWER_THROTTLING_STATE`).
  It is supplied by a force-included compatibility header for the Windows build only; llama.cpp itself is unchanged.
* **Not loaded yet:** it has not been loaded on Windows.

## Verified (build machine, Linux)

| Check | How | Result |
|---|---|---|
| Governor rules | `ResourceGovernorTest` (fake memory probe) | 6/6 |
| Platform: paths, owner-only files, vault, secrets, token, host.json, database with all migrations, native-library lookup, real memory probe | `DesktopPlatformTest` | 7/7 |
| API: token, loopback binding, DNS-rebinding and cross-site refusal (raw sockets with forged Host/Origin), JSON and SSE turns, Stop cancels the running reply, 404/400/413, unload | `ApiServerTest` (real host, scripted engine) | 7/7 |
| Real model through the whole path | `HostRealModelTest` with SmolLM2-135M (real weights): API → core → llama.cpp JNI → reply | Coherent reply; state READY; memory returned after unload over the API |
| Installed host as its own process | `scripts/measure_desktop_host.sh` | Table below |
| Android after moving the HTTP client | Full check (below) | Green |

**Installed host on Linux** (`docs/measurements/desktop_host_linux_m2.json`; 4 vCPU Xeon, JVM 21 `-Xmx384m`):

| | SmolLM2-135M | 1B-shaped test model (random weights) |
|---|---|---|
| Idle, no model loaded | **101 MB**, **0.2 %** of one core | 101 MB, 0.2 % |
| Model loaded | 341 MB | 1,579 MB |
| After unload via the API | 150 MB | 178 MB |

**M2 acceptance:**

| Criterion | Status |
|---|---|
| A conversation over the local API with a real model | **Met** (Linux) |
| Idle memory under 600 MB with no model loaded | **Met** (101 MB on Linux) |
| Models unload on timeout | Met by the core's idle unload (`model.autoUnloadMin`, already tested in core) plus the governor's pressure and budget unloads (tested) |

All of this still needs confirming on Windows.

## Not verified yet, and why

* **Loading `clu_llama.dll` on Windows,** the Windows ACL path of `OwnerOnly`, and `%LOCALAPPDATA%` resolution. This
  environment is Linux, and running Windows software here is blocked by policy.
* **The memory figures on Windows:** the probe reads private bytes there, a different measure from Linux's resident
  set.
* **Vulkan:** not built. It is waiting on M1: the GPU build is only worth adding if your benchmark shows the 860M
  clearly faster.

## How to check it on your PC

After the benchmark, with Java 17+ installed:

1. Copy `desktop/build/install/clu-host` (or build it with `./gradlew :desktop:installDist`).
2. Run `bin\clu-host.bat --register-model <a GGUF from your models folder>`.
3. Run `bin\clu-host-background.cmd`, then `bin\clu-host.bat --print-token`.
4. In PowerShell:
   ```
   $t = "<token>"
   $c = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:47821/v1/conversations -Headers @{Authorization="Bearer $t"} -Body "{}"
   Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:47821/v1/conversations/$($c.id)/turns" -Headers @{Authorization="Bearer $t"} -Body '{"text":"Hello"}'
   Invoke-RestMethod -Uri http://127.0.0.1:47821/v1/status -Headers @{Authorization="Bearer $t"}
   ```
5. Task Manager → Details → `java.exe`: note the memory column before and after the first reply.

## Rollback

* Remove `:desktop` from `settings.gradle.kts`, and delete `desktop/`, the Windows toolchain files and the two scripts.
* The only change outside them is the HTTP client move. Android keeps working either way (the class is shared); to undo
  it, move `JvmHttp` back into `AndroidPlatform.kt`.
