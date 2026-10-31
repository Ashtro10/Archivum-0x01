@echo off
setlocal
set "EXE=%~dp0..\dist_windows\Primus.exe"
set "DLL=%~dp0..\dist_windows\_internal\python312.dll"
if not exist "%EXE%" (
  echo Build first: python scripts/build_windows.py
  exit /b 1
)
if not exist "%DLL%" (
  echo Missing _internal\python312.dll. Keep that folder next to Primus.exe.
  exit /b 1
)
start "" "%EXE%"
