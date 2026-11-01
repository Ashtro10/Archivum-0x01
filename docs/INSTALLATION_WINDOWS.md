# Installing CLU on Windows (no technical knowledge needed)

**You need:** Windows 10 or 11, 64-bit; a processor with AVX2 (every Ryzen and every Intel since 2013); 8 GB RAM minimum (16–32 GB recommended); about 1 GB for CLU plus 1–3 GB per language model. No administrator rights.

## Install
1. Get `CLU-Setup-0.6.0.exe` (and, optionally, check it against `SHA256SUMS.txt`: PowerShell `Get-FileHash CLU-Setup-0.6.0.exe`).
2. Double-click it. Windows SmartScreen may say "Windows protected your PC" because the installer is not code-signed: choose **More info → Run anyway**.
3. The installer checks your PC and explains anything missing (64-bit Windows, AVX2, Edge). Click through: license/notices → components (desktop shortcut is optional; the GPU engine is off) → folder (default `%LOCALAPPDATA%\Programs\CLU`) → Install → "Open CLU now".
4. CLU opens in its own window with the **first-run guide**: it checks your PC, helps you pick or download a model (it always asks first, showing size, source and licence), lets you set options, and tests the model. You can skip any step; CLU works without a model, it just cannot talk yet.

## Daily use
Start Menu → CLU. Closing the window keeps CLU running in the notification area (bottom-right; check the ^ arrow). Its icon: **Open CLU**, **Unload the model now**, **Restart CLU**, **Quit CLU**. "Start with Windows" is **off** unless you switch it on (Settings → CLU on this PC). Everything is in the window; you never need a terminal.

## Where things are
Program: `%LOCALAPPDATA%\Programs\CLU`. Your data (memories, conversations, settings, models CLU downloaded, backups, logs): `%LOCALAPPDATA%\CLU`. Backups: `…\CLU\backups` (verified copies; made automatically and before upgrades). Logs: `…\CLU\logs\host.log` (never contains tokens).

## Update
Run the newer installer. It asks CLU to quit, backs up your database, replaces only the program, and leaves your data alone.

## Uninstall
Settings → Apps → CLU → Uninstall. It removes the program, shortcuts and the start-with-Windows entry, then **asks** whether to delete your data too (default: keep). Models you only *registered* from other folders are never deleted.

## If something goes wrong
* "CLU stopped right after starting": the message shows the reason; details in `…\CLU\logs\launcher.log` and `host.log`.
* "database is damaged": CLU refuses to open it and tells you which backup to restore (`Settings → Health & backups` or `clu.exe --restore-backup <file>` from a terminal).
* Model won't load: Models shows the exact reason and what to try (smaller model, lower context); the rest of CLU keeps working.
* Start again from a clean state without losing data: Quit CLU, delete `…\CLU\window-profile`.

## Acceptance checklist (what to run and report back)
I built and tested everything possible on Linux; **the Windows steps below have not been run by me**. Please run them and tell me what differs.
1. Install on a clean user; Start Menu entry and (if ticked) desktop shortcut launch CLU. 2. First-run guide completes; with no model it still ends in a usable CLU. 3. Register a GGUF in Models, load, unload, reload. 4. Chat. 5. Close the window → tray icon stays; tray → Open restores. 6. Tray → Restart. 7. Launch CLU twice → one instance. 8. Settings → start with Windows on, reboot → CLU in tray without a window; turn it off, reboot → not started. 9. Quit; relaunch → memories, conversations, model registration still there. 10. Load a bad file as a model → clear error, CLU still works. 11. Disconnect the network → chat and memory work. 12. Upgrade by running the installer again. 13. Uninstall → answer "keep data", reinstall → data found. 14. Task Manager while idle with no model loaded, with the model loaded, and during a reply: note CLU's memory (java.exe is `javaw.exe` here, plus the Edge window). 15. Models → Benchmark on the selected model and send me the result.
