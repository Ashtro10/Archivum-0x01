#!/bin/bash
# Static checks of a built CLU installer (what can be verified without running Windows): unpacks it with 7z into a fresh temp folder and checks
# the layout, that the launcher is a 64-bit GUI program, that no model/user data/script files are inside, and that the NSIS script keeps
# its promises (per-user, no admin, no service, data kept on uninstall unless asked, "start with Windows" off by default).
# Running the installer itself needs Windows: see docs/INSTALLATION_WINDOWS.md for the acceptance steps.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXE="${1:-$(ls "$ROOT"/dist/installer/CLU-Setup-*.exe | head -1)}"
X="$(mktemp -d /tmp/clu-verify.XXXXXX)"; trap 'rm -rf "$X"' EXIT
fail=0; ok() { echo "ok   - $1"; }; bad() { echo "FAIL - $1"; fail=1; }
chk() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
cd "$X"; 7z x -y -bso0 -bsp0 "$EXE" >/dev/null || true
chk "installer is an NSIS Windows executable" "file '$EXE' | grep -q 'Nullsoft Installer'"
chk "launcher clu.exe present and is a 64-bit Windows GUI program" "file clu.exe | grep -q 'PE32+ executable (GUI) x86-64'"
rsrc_size() { echo $((16#$(x86_64-w64-mingw32-objdump -h clu.exe | awk '/\.rsrc/ {print $3}'))); }
chk "launcher carries the CLU icon and version information" "[ \$(rsrc_size) -gt 20000 ] && strings -e l clu.exe | grep -q 'personal assistant (launcher)'"
chk "bundled runtime has javaw.exe, jvm.dll and the module image" "[ -f runtime/bin/javaw.exe ] && [ -f runtime/bin/server/jvm.dll ] && [ -f runtime/lib/modules ]"
chk "runtime includes the modules CLU needs" "for m in java.desktop java.sql java.net.http java.management jdk.httpserver jdk.management; do grep -q \$m runtime/release || exit 1; done"
chk "runtime's debugging/management tools were not installed" "[ ! -f runtime/bin/jcmd.exe ] && [ ! -f runtime/bin/jshell.exe ] && [ ! -f runtime/bin/keytool.exe ]"
chk "CPU inference engine present, only Windows system DLLs imported" "[ -f lib/native/windows-x64/clu_llama.dll ] && ! x86_64-w64-mingw32-objdump -p lib/native/windows-x64/clu_llama.dll | grep 'DLL Name' | grep -vi -E 'KERNEL32|ADVAPI32|msvcrt'"
chk "CLU's own jars and third-party jars present" "ls lib/desktop.jar lib/core.jar lib/sqlite-jdbc-*.jar lib/kotlin-stdlib-*.jar >/dev/null"
chk "licence texts and third-party notices are installed" "[ -f licenses/THIRD_PARTY_NOTICES.txt ] && [ -f licenses/java-runtime/java.base/LICENSE ]"
chk "an uninstaller is included" "[ -f 'Uninstall CLU.exe' ]"
chk "no language model is inside the installer" "[ -z \"\$(find . -iname '*.gguf' -o -iname '*.safetensors' | head -1)\" ]"
chk "no scripts or batch files are inside (the launcher is a program)" "[ -z \"\$(find . -iname '*.bat' -o -iname '*.cmd' -o -iname '*.ps1' -o -iname '*.vbs' | head -1)\" ]"
chk "no user data (database, tokens, keys) is inside" "[ -z \"\$(find . -iname 'clu.db*' -o -iname 'api-token' -o -iname 'vault.key' -o -iname 'host.json' | head -1)\" ]"
NSI="$ROOT/installer/clu.nsi"
chk "installs per-user without administrator rights" "grep -q 'RequestExecutionLevel user' '$NSI' && ! grep -q 'HKLM.*Uninstall' '$NSI'"
chk "no Windows service is created" "! grep -qi -E 'sc create|nsSCM|CreateService' '$NSI'"
chk "'start with Windows' is not enabled by the installer" "! grep -q 'CurrentVersion\\\\Run\"' '$NSI' || ! grep -E 'WriteReg.*CurrentVersion\\\\Run' '$NSI'"
chk "uninstaller keeps user data unless the owner agrees (default No, silent keeps)" "grep -q 'MB_DEFBUTTON2' '$NSI' && grep -q '/SD IDNO IDNO keep' '$NSI'"
chk "the install section never removes the data folder (only the uninstall section can, after asking)" "[ -z \"\$(awk '/^Section \"Uninstall\"/ {exit} {print}' '$NSI' | grep -E 'RMDir.*LOCALAPPDATA..CLU\"|Delete.*LOCALAPPDATA..CLU.[^w]')\" ] && grep -q 'RMDir /r \"\$INSTDIR.lib\"' '$NSI'"
echo; [ $fail = 0 ] && echo "installer static checks: all passed" || { echo "installer static checks: FAILURES"; exit 1; }
