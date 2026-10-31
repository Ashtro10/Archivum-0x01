#!/bin/bash
# Builds CLU's Windows installer on Linux (no Windows needed):
#   dist/installer/CLU-Setup-<version>.exe     the primary deliverable (NSIS, per-user, no administrator rights)
#   dist/installer/CLU-<version>-portable.zip  secondary: the same files unpacked (run clu.exe; data still goes to %LOCALAPPDATA%\CLU)
#   dist/installer/SHA256SUMS.txt
#
# Inputs it needs (and checks): the Windows engine DLL (scripts/build_windows_native.sh), makensis, x86_64-w64-mingw32-gcc/windres,
# python3+Pillow only for regenerating the icon (the generated files are committed), and a Windows Java runtime image:
#   CLU_WINDOWS_JRE=<dir>   a jlink runtime image for win-x64 (bin\javaw.exe, lib\modules ...). If unset the script downloads the
#                           "jdk4py" wheel (OpenJDK 25.0.2, GPLv2+Classpath Exception) from PyPI, checks its SHA-256 and unpacks it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
VERSION="$(grep -oP 'const val VERSION = "\K[0-9]+\.[0-9]+\.[0-9]+' desktop/src/main/kotlin/ai/clu/desktop/HostMain.kt)"
WORK="$ROOT/build/installer"; STAGE="$WORK/stage"; OUTDIR="$ROOT/dist/installer"
JRE_WHEEL_SHA256="5f0ad2e8f7ad3b40eef8af75062d830afd3feccca62fc03db611b2e9a5fa9824"
rm -rf "$STAGE"; mkdir -p "$STAGE" "$OUTDIR" "$WORK"
need() { command -v "$1" >/dev/null || { echo "missing tool: $1" >&2; exit 1; }; }
need makensis; need x86_64-w64-mingw32-gcc; need x86_64-w64-mingw32-windres
[ -f native/build/cmake-windows/clu_llama.dll ] || { echo "build the Windows engine first: scripts/build_windows_native.sh" >&2; exit 1; }

echo "== 1/6 program files (gradle installDist)"
./gradlew :desktop:installDist -q --offline
mkdir -p "$STAGE/lib"
cp desktop/build/install/clu-host/lib/*.jar "$STAGE/lib/"
mkdir -p "$STAGE/lib/native/windows-x64"; cp native/build/cmake-windows/clu_llama.dll "$STAGE/lib/native/windows-x64/"
if [ -f native/build/cmake-windows-vulkan/clu_llama.dll ]; then mkdir -p "$STAGE/optional/windows-x64-vulkan"; cp native/build/cmake-windows-vulkan/clu_llama.dll "$STAGE/optional/windows-x64-vulkan/"; fi

echo "== 2/6 Java runtime"
if [ -n "${CLU_WINDOWS_JRE:-}" ]; then JRE="$CLU_WINDOWS_JRE"
else
  D="$WORK/jre-download"; mkdir -p "$D"
  if ! ls "$D"/jdk4py-*win_amd64.whl >/dev/null 2>&1; then python3 -m pip download "jdk4py==25.0.2.1" --platform win_amd64 --only-binary=:all: --no-deps --python-version 3.12 -d "$D" -q; fi
  W="$(ls "$D"/jdk4py-*win_amd64.whl | head -1)"; echo "$JRE_WHEEL_SHA256  $W" | sha256sum -c - >/dev/null || { echo "runtime wheel checksum mismatch" >&2; exit 1; }
  rm -rf "$D/x"; unzip -q "$W" -d "$D/x"; JRE="$D/x/jdk4py/java-runtime"
fi
[ -f "$JRE/bin/javaw.exe" ] && [ -f "$JRE/lib/modules" ] || { echo "not a Windows runtime image: $JRE" >&2; exit 1; }
for m in java.desktop java.sql java.net.http java.management jdk.httpserver jdk.management; do grep -q "$m" "$JRE/release" || { echo "runtime lacks module $m" >&2; exit 1; }; done
mkdir -p "$STAGE/runtime"; cp -r "$JRE"/. "$STAGE/runtime/"
# CLU needs only the launcher-less Java programs: drop the debugging/management tools (smaller, less to go wrong).
for x in jcmd jfr jinfo jmap jps jrunscript jstack jstat jwebserver keytool kinit klist ktab rmiregistry java; do rm -f "$STAGE/runtime/bin/$x.exe"; done
mkdir -p "$STAGE/licenses"; cp -r "$STAGE/runtime/legal" "$STAGE/licenses/java-runtime"; cp installer/THIRD_PARTY_NOTICES.txt "$STAGE/licenses/"

echo "== 3/6 launcher (clu.exe) and icon"
cd installer
IFS=. read -r MA MI PA <<<"$VERSION"
printf '#define CLU_VER_COMMA %s,%s,%s,0\n#define CLU_VER_STR "%s"\n' "$MA" "$MI" "$PA" "$VERSION" > "$WORK/version.inc"
x86_64-w64-mingw32-windres -I "$WORK" clu.rc -O coff -o "$WORK/clu.res"
x86_64-w64-mingw32-gcc -O2 -s -municode -mwindows -static -Wall -Wextra clu_launcher.c "$WORK/clu.res" -lshlwapi -o "$STAGE/clu.exe"
cp clu.ico "$STAGE/clu.ico"

echo "== 4/6 installer (NSIS)"
OUT="$OUTDIR/CLU-Setup-$VERSION.exe"
makensis -V2 -DVERSION="$VERSION" -DSTAGE="$STAGE" -DOUT="$OUT" clu.nsi
cd "$ROOT"

echo "== 5/6 portable ZIP (secondary)"
ZIP="$OUTDIR/CLU-$VERSION-portable.zip"; rm -f "$ZIP"
PT="$WORK/portable/CLU"; rm -rf "$WORK/portable"; mkdir -p "$PT"; cp -r "$STAGE"/. "$PT/"
[ -d "$PT/optional/windows-x64-vulkan" ] && { mkdir -p "$PT/lib/native/windows-x64-vulkan"; cp "$PT/optional/windows-x64-vulkan/clu_llama.dll" "$PT/lib/native/windows-x64-vulkan/"; }
rm -rf "$PT/optional"
cat > "$PT/README-PORTABLE.txt" <<TXT
CLU $VERSION - unpacked copy (secondary package; the installer CLU-Setup-$VERSION.exe is the normal way to install).
Unzip anywhere you can write to, then run clu.exe. CLU's data is still kept in %LOCALAPPDATA%\\CLU (not in this folder).
There is no Start Menu entry, uninstaller or "start with Windows" until you use the installer.
To remove: quit CLU from its notification-area icon, then delete this folder (and %LOCALAPPDATA%\\CLU if you want its data gone).
TXT
(cd "$WORK/portable" && zip -q -r -9 "$ZIP" CLU)

echo "== 6/6 checksums"
(cd "$OUTDIR" && sha256sum "CLU-Setup-$VERSION.exe" "CLU-$VERSION-portable.zip" > SHA256SUMS.txt && cat SHA256SUMS.txt)
ls -la "$OUTDIR"
