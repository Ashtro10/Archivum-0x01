# M6 — Production deployment, one-click installation, background runtime

_Status labels: **[tested]** ran and passed here (Linux, injected runners where Windows APIs are involved); **[built, not run on Windows]**; **[not done]**._

## What it is

A per-user Windows installer (`CLU-Setup-<version>.exe`, NSIS) that installs CLU, its Java runtime and its inference engine with no
administrator rights and no service; a launcher (`clu.exe`); a first-run guide; model management in the window; and a
background runtime with a tray icon.

```
Start Menu / desktop shortcut / "start with Windows"
        └─ clu.exe (64-bit GUI launcher: finds runtime\bin\javaw.exe, sets CLU_LAUNCHER / CLU_AVX2, logs to %LOCALAPPDATA%\CLU\logs\launcher.log)
              └─ javaw.exe  (bundled OpenJDK 25 runtime, ai.clu.desktop.HostMainKt)
                    ├─ ApiServer 127.0.0.1 (token, Host/Origin checks)  ── setup / models / agents / memory routes
                    ├─ CluCore ── InferenceService ── clu_llama.dll (llama.cpp, CPU; optional Vulkan build)
                    ├─ ResourceGovernor (16 GB ceiling; counts the window's Edge processes too)
                    ├─ tray (AWT SystemTray: Open / Unload / Restart / Quit; icon + tooltip follow the model state)
                    └─ Edge --app window (own profile folder %LOCALAPPDATA%\CLU\window-profile) ── heartbeat → host
Program: %LOCALAPPDATA%\Programs\CLU      Data (never touched by install/upgrade): %LOCALAPPDATA%\CLU
```

## Installer **[built; static checks tested; not run on Windows]**

* Path: `dist/installer/CLU-Setup-0.6.0.exe` (≈48 MB; unsigned, so SmartScreen will warn: "More info → Run anyway"). Secondary: `CLU-0.6.0-portable.zip`.
* Build: `scripts/build_installer.sh` (Linux, `makensis`, MinGW). Static verification: `scripts/verify_installer.sh` (18 checks: PE type, layout, no models/scripts/user data, per-user, no service, data kept on uninstall…) — **all passed**.
* Why NSIS: the only mainstream free packager that builds Windows installers on Linux; per-user (`RequestExecutionLevel user`, `$LOCALAPPDATA\Programs\CLU`).
* Prerequisite checks in the installer (each explained in a dialog): 64-bit Windows, Windows 10+, AVX2 (via `IsProcessorFeaturePresent`), Edge present (else: default browser). Disk space is checked by NSIS on the directory page.
* Dependencies: nothing to install. Java runtime = `jdk4py` 25.0.2.1 win_amd64 wheel from PyPI (OpenJDK, GPLv2+Classpath Exception; SHA-256 pinned in the build script; legal texts installed under `licenses\java-runtime`). It carries the VC++/UCRT DLLs, so no redistributable package is required. `clu_llama.dll` imports only KERNEL32/ADVAPI32/msvcrt.
* Optional component "GPU engine (Vulkan, experimental)": off by default; built, **never run on a GPU**.
* Upgrade: same directory (from the registry), CLU asked to quit (`clu.exe --quit`), database backed up (`clu.exe --backup`), only `lib\`, `runtime\`, `licenses\` replaced; the host also backs up before any schema change. Uninstall: removes program, shortcuts, the optional autostart entry; **asks** whether to delete `%LOCALAPPDATA%\CLU` (default No; silent uninstall always keeps it).
* No model is bundled; no download happens at install time.

## First-run guide **[tested in Chromium against the real host]**

Welcome → Your PC (OS, RAM, disk, engine, AVX2, GPU names — read from the machine) → Model (existing models, *Find models on this PC*, paste a path, or suggested downloads) → Settings (context, threads, CPU/GPU, name, keep running in tray, start with Windows — **off**) → Assistant tasks (M7) → Try it (a real short generation via the shared inference service: reply, backend, threads, load time, first-token, tokens/s) → Ready. Every step can be skipped; CLU is usable with no model (memory, people, documents), and the guide offers itself again until completed. It never changes the selected model by itself.

Downloads (Qwen2.5-3B/1.5B, Llama-3.2-3B from Hugging Face): only after a consent dialog showing source, file, size, destination, licence (with link), memory need and suitability; HTTPS only; HEAD size required; free-space check; resumable (HTTP Range validated against size/ETag); size + SHA-256 (when the server publishes it, as Hugging Face does) + GGUF parse verified **before** the file gets its name; registered but **not selected** unless the box "Use it when ready" was ticked at the start. Tested with a local Range server: verified download, interrupt + resume (only the rest is requested), checksum mismatch discarded, non-model refused, no-space refusal with zero bytes fetched, confirmation/HTTPS/filename rules.

## Model management in the window **[tested]**

Register (path), *Find models on this PC*, suggested downloads with progress/cancel, metadata and memory estimate per model, select, load/unload/reload, context/threads/GPU layers (M5 settings; they reload the model and the runtime shows the real backend), benchmark and history, **Remove registration** (file untouched), **Delete file…** (only files inside CLU's own models folder, after confirmation; anything else is refused with 403).

## Background runtime and tray **[host logic tested; the tray/window on Windows not run]**

Single instance (health check; a second launch just opens the window), closing the window → tray (the shell pings every 5 s; the host treats 20 s of silence after a window was seen as "closed"; Settings can make closing quit instead), tray menu Open / Unload / Restart / Quit with state in the tooltip and icon colour, clean shutdown (`/v1/shell/quit`, shutdown hook), restart (relaunch after the old process exits), uncaught-exception handler → `host.log`, damaged database refuses to open and points to the backups, start with Windows = `HKCU\...\Run` value written/removed/read back (per user, default off, no service). CLI for diagnostics only: `--quit --backup --version --background --models --benchmark ...`.

## Memory ceiling

`ResourceGovernor` now takes a `CombinedMemoryProbe`: host process + the memory of Edge processes started with CLU's own `--user-data-dir` (found by command line; Windows via CIM, cached 20 s). Tested with a marker process; the governor test shows 2 GB of window memory removes exactly 2 GB from what a model may use.

## Not done / limits

* The installer, launcher, tray, Edge window, autostart and uninstaller were **not executed on Windows** (no Windows here; Wine here has no 32-bit support, which the NSIS stub needs). Acceptance steps: [../INSTALLATION_WINDOWS.md](../INSTALLATION_WINDOWS.md).
* Unsigned installer. GPU path unverified. No measurements on the Ryzen AI 7 350 / Radeon 860M exist yet.
* Reboot + optional startup, tray restore, offline operation on the packaged app: logic covered by tests, not by a Windows run.
