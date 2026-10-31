@echo off
rem Starts CLU's host in the background at below-normal priority, so games, builds and video always come first.
rem The window starts minimised; closing it (or Ctrl-C in it) stops CLU. Start-at-sign-in is set up by the installer (M11).
start "CLU host" /belownormal /min "%~dp0clu-host.bat" %*
