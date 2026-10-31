; CLU installer (NSIS 3, built on Linux with makensis). Per-user: no administrator rights, no service.
;   makensis -DVERSION=0.6.0 -DSTAGE=<dir with clu.exe, lib\, runtime\> -DOUT=<file> clu.nsi
Unicode true
!ifndef VERSION
  !error "pass -DVERSION=x.y.z"
!endif
!define APP "CLU"
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\CLU"

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"
!include "WinVer.nsh"
!include "FileFunc.nsh"

Name "${APP}"
OutFile "${OUT}"
RequestExecutionLevel user
InstallDir "$LOCALAPPDATA\Programs\CLU"
InstallDirRegKey HKCU "Software\CLU" "InstallDir"
SetCompressor /SOLID lzma
SetCompressorDictSize 32
ShowInstDetails show
BrandingText "CLU ${VERSION}"
Icon "clu.ico"
UninstallIcon "clu.ico"

VIProductVersion "${VERSION}.0"
VIAddVersionKey "ProductName" "CLU"
VIAddVersionKey "FileDescription" "CLU installer"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "LegalCopyright" "Personal software; third-party notices are included"

!define MUI_ICON "clu.ico"
!define MUI_UNICON "clu.ico"
!define MUI_WELCOMEFINISHPAGE_BITMAP "sidebar.bmp"
!define MUI_UNWELCOMEFINISHPAGE_BITMAP "sidebar.bmp"
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_BITMAP "header.bmp"
!define MUI_ABORTWARNING

!define MUI_WELCOMEPAGE_TITLE "Install CLU ${VERSION}"
!define MUI_WELCOMEPAGE_TEXT "CLU is your personal assistant. It thinks on this PC: the language model, your memories and your conversations stay here.$\r$\n$\r$\nThis installs CLU for your Windows account only. It needs no administrator rights and installs no service.$\r$\n$\r$\nCLU needs 64-bit Windows 10 or 11 and a processor with AVX2. The installer has checked both. Everything else CLU needs (its Java runtime and its inference engine) is included.$\r$\n$\r$\nNo language model is included and nothing is downloaded now: CLU's first-run guide helps you choose a model, and downloads only start when you confirm them.$\r$\n$\r$\nCLU opens in a Microsoft Edge app window (Edge is part of Windows 10/11). If Edge is missing, CLU uses your default browser."
!insertmacro MUI_PAGE_WELCOME
!define MUI_LICENSEPAGE_TEXT_TOP "Software that is part of this installation:"
!define MUI_LICENSEPAGE_BUTTON "Next >"
!define MUI_LICENSEPAGE_TEXT_BOTTOM "These notices are also saved in CLU's folder (licenses)."
!insertmacro MUI_PAGE_LICENSE "THIRD_PARTY_NOTICES.txt"
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\clu.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Open CLU now"
!define MUI_FINISHPAGE_TEXT "CLU is installed.$\r$\n$\r$\nOpen it from the Start Menu or the desktop shortcut. Its first-run guide helps you choose a model. Closing its window keeps CLU running in the notification area; use its icon there to quit.$\r$\n$\r$\nYour data (memories, conversations, settings, downloaded models) is kept in %LOCALAPPDATA%\CLU, separate from the program, so updating or reinstalling never touches it."
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_WELCOME
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH
!insertmacro MUI_LANGUAGE "English"

Var Upgrading

; ---------------------------------------------------------------- prerequisite checks (each one explained)
Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_OK|MB_ICONSTOP "CLU needs 64-bit Windows. This PC is running a 32-bit version, so CLU cannot be installed."
    Abort
  ${EndIf}
  ${IfNot} ${AtLeastWin10}
    MessageBox MB_OK|MB_ICONSTOP "CLU needs Windows 10 or Windows 11. This version of Windows is too old."
    Abort
  ${EndIf}
  ; CLU's inference engine is built for processors with AVX2 (all Intel since 2013/Haswell, AMD since 2015/Excavator, including every Ryzen).
  System::Call 'kernel32::IsProcessorFeaturePresent(i 40) i .r0'
  ${If} $0 == 0
    MessageBox MB_YESNO|MB_ICONEXCLAMATION "This PC's processor does not report AVX2 support, which CLU's language-model engine needs. CLU's memory, people and documents features would still work, but it cannot run a model here.$\r$\n$\r$\nInstall anyway?" /SD IDNO IDYES +2
    Abort
  ${EndIf}
  ReadRegStr $1 HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe" ""
  ${If} $1 == ""
    ReadRegStr $1 HKCU "SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe" ""
  ${EndIf}
  ${If} $1 == ""
    MessageBox MB_OK|MB_ICONINFORMATION "Microsoft Edge was not found. CLU normally opens in an Edge app window; without it, CLU opens in your default web browser instead. Nothing else is needed." /SD IDOK
  ${EndIf}
  ReadRegStr $Upgrading HKCU "Software\CLU" "Version"
FunctionEnd

; Asks a running CLU to quit cleanly (the launcher waits for the answer), so its files can be replaced.
!macro StopCLU
  IfFileExists "$INSTDIR\clu.exe" 0 +3
    DetailPrint "Asking CLU to quit (if it is running)..."
    nsExec::Exec '"$INSTDIR\clu.exe" --quit'
  Sleep 1500
!macroend

; ---------------------------------------------------------------- install
Section "CLU (required)" SecMain
  SectionIn RO
  SetShellVarContext current
  !insertmacro StopCLU

  ${If} $Upgrading != ""
    ; A new version: keep a verified copy of the database first (CLU also does this itself before any schema change).
    IfFileExists "$LOCALAPPDATA\CLU\clu.db" 0 +3
      DetailPrint "Upgrading from $Upgrading: backing up your CLU database first..."
      nsExec::Exec '"$INSTDIR\clu.exe" --backup'
  ${EndIf}

  ; Only the program is replaced. Old libraries and runtime go; the data folder is never touched here.
  RMDir /r "$INSTDIR\lib"
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\licenses"

  SetOutPath "$INSTDIR"
  File "${STAGE}\clu.exe"
  File "clu.ico"
  SetOutPath "$INSTDIR\lib"
  File /r "${STAGE}\lib\*.*"
  SetOutPath "$INSTDIR\runtime"
  File /r "${STAGE}\runtime\*.*"
  SetOutPath "$INSTDIR\licenses"
  File "THIRD_PARTY_NOTICES.txt"
  File /nonfatal /r "${STAGE}\licenses\*.*"
  WriteUninstaller "$INSTDIR\Uninstall CLU.exe"

  ; Start Menu entry.
  CreateDirectory "$SMPROGRAMS\CLU"
  CreateShortcut "$SMPROGRAMS\CLU\CLU.lnk" "$INSTDIR\clu.exe" "" "$INSTDIR\clu.ico" 0 SW_SHOWNORMAL "" "CLU - your personal assistant"
  CreateShortcut "$SMPROGRAMS\CLU\Uninstall CLU.lnk" "$INSTDIR\Uninstall CLU.exe" "" "$INSTDIR\Uninstall CLU.exe" 0

  ; Where CLU is, and the entry in Windows' "Installed apps" (this user only).
  WriteRegStr HKCU "Software\CLU" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "Software\CLU" "Version" "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "CLU"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "CLU"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\clu.ico"
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\Uninstall CLU.exe"'
  WriteRegStr HKCU "${UNINST_KEY}" "QuietUninstallString" '"$INSTDIR\Uninstall CLU.exe" /S'
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  IntFmt $0 "0x%08X" $0
  WriteRegDWORD HKCU "${UNINST_KEY}" "EstimatedSize" "$0"

  ; The data folder is created by CLU on its first start; create it here so the owner can see where it is.
  CreateDirectory "$LOCALAPPDATA\CLU"
SectionEnd

Section "Desktop shortcut" SecDesktop
  SetShellVarContext current
  CreateShortcut "$DESKTOP\CLU.lnk" "$INSTDIR\clu.exe" "" "$INSTDIR\clu.ico" 0 SW_SHOWNORMAL "" "CLU - your personal assistant"
SectionEnd

Section /o "GPU engine (Vulkan, experimental)" SecGpu
  ; Only the files differ: the engine build that can use the graphics chip. CLU uses it only if the owner switches it on in Models
  ; and CLU confirms it started; otherwise the CPU engine runs.
  SetOutPath "$INSTDIR\lib\native\windows-x64-vulkan"
  File /nonfatal "${STAGE}\optional\windows-x64-vulkan\clu_llama.dll"
SectionEnd

LangString DESC_Main ${LANG_ENGLISH} "CLU, its Java runtime and its inference engine. Required."
LangString DESC_Desktop ${LANG_ENGLISH} "Put a CLU shortcut on the desktop."
LangString DESC_Gpu ${LANG_ENGLISH} "An optional engine build that can use the graphics chip (Vulkan). Not verified on every graphics driver; CLU tells you what actually runs, and uses the CPU unless you switch it on."
!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SecMain} $(DESC_Main)
  !insertmacro MUI_DESCRIPTION_TEXT ${SecDesktop} $(DESC_Desktop)
  !insertmacro MUI_DESCRIPTION_TEXT ${SecGpu} $(DESC_Gpu)
!insertmacro MUI_FUNCTION_DESCRIPTION_END

; ---------------------------------------------------------------- uninstall
Section "Uninstall"
  SetShellVarContext current
  DetailPrint "Asking CLU to quit (if it is running)..."
  nsExec::Exec '"$INSTDIR\clu.exe" --quit'
  Sleep 1500

  ; Things CLU put in this user's account outside its folders: the optional "start with Windows" entry.
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "CLU"
  Delete "$DESKTOP\CLU.lnk"
  Delete "$SMPROGRAMS\CLU\CLU.lnk"
  Delete "$SMPROGRAMS\CLU\Uninstall CLU.lnk"
  RMDir "$SMPROGRAMS\CLU"

  ; The program. (Never the data folder: that is decided below.)
  RMDir /r "$INSTDIR\lib"
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\licenses"
  Delete "$INSTDIR\clu.exe"
  Delete "$INSTDIR\clu.ico"
  Delete "$INSTDIR\Uninstall CLU.exe"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "${UNINST_KEY}"
  DeleteRegKey HKCU "Software\CLU"

  ; The owner's data: kept unless the owner says otherwise. Silent uninstalls always keep it.
  IfFileExists "$LOCALAPPDATA\CLU\*.*" 0 done
  MessageBox MB_YESNO|MB_ICONQUESTION|MB_DEFBUTTON2 "CLU's program has been removed.$\r$\n$\r$\nYour CLU data is still in:$\r$\n$LOCALAPPDATA\CLU$\r$\n(memories, conversations, people, documents, settings, backups and any models CLU downloaded).$\r$\n$\r$\nDelete it too? This cannot be undone. Choose No to keep it: installing CLU again will find it. Model files that CLU only registered (stored elsewhere) are never deleted." /SD IDNO IDNO keep
    RMDir /r "$LOCALAPPDATA\CLU"
    DetailPrint "Your CLU data was deleted."
    Goto done
  keep:
    ; Only the regenerable browser cache of CLU's window goes; everything else stays.
    RMDir /r "$LOCALAPPDATA\CLU\window-profile"
    DetailPrint "Your CLU data was kept in $LOCALAPPDATA\CLU."
  done:
SectionEnd
