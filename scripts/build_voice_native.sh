#!/bin/bash
# Builds everything CLU's voice needs that is not Kotlin, for Linux (tests) and Windows (the installer), from pinned sources:
#   native/build/voice/<os>/  livekit-server, livekit_ffi, sherpa-onnx C API + onnxruntime, clu_voice (JNI bridge)
#   desktop/src/ffi/java/     Java (lite) classes for LiveKit's FFI protocol, generated from the SDK's own descriptors
#
# Sources (all open source, all pinned by version and SHA-256, nothing from a cloud service at run time):
#   livekit-server 1.9.12  Apache-2.0  built from github.com/livekit/livekit (Go module proxy), pure Go, cross-compiled
#   livekit (Python SDK wheel) 1.1.21  Apache-2.0  only for its prebuilt livekit_ffi library (LiveKit's Rust SDK + libwebrtc) and protocol descriptors
#   sherpa-onnx-core 1.13.8  Apache-2.0 (onnxruntime: MIT)  only for its prebuilt C API libraries and header
#   protobuf-protoc-bin 36.2  BSD-3  protoc, to generate the Java classes
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
W="$ROOT/build/voice-native"; OUT="$ROOT/native/build/voice"; mkdir -p "$W/whl" "$OUT/linux-x64" "$OUT/windows-x64"
LK_SERVER=v1.9.12
declare -A PIN=(
  [livekit-1.1.21-py3-none-win_amd64.whl]=0f4bafc5d51cf686dbe308762fa4431e89503ee8348539924438c305a8019a54
  [livekit-1.1.21-py3-none-manylinux_2_28_x86_64.whl]=148553a7d6515cf0c373f591f962de221253fe305d0a84857f2955f520a54340
  [sherpa_onnx_core-1.13.8-py3-none-win_amd64.whl]=5579e80196d516e6dae23c8f629292ce3142ab8869925d32b94612fd86f93733
  [sherpa_onnx_core-1.13.8-py3-none-manylinux2014_x86_64.whl]=4da90acf435373d7b2ba9cc0be806e7e274d28f63f5780880b5dea6721626e36
  [protobuf_protoc_bin-36.2-py2.py3-none-manylinux_2_24_x86_64.whl]=eb79667e8ca9e40d877aa116cfdb9c75216cd200b1cb2da2c15dce0e5068d98c
)
fetch() { # name, pip args...
  local n=$1; shift
  [ -f "$W/whl/$n" ] || python3 -m pip download -q --no-deps --only-binary=:all: -d "$W/whl" "$@"
  echo "${PIN[$n]}  $W/whl/$n" | sha256sum -c --quiet - || { echo "checksum mismatch: $n" >&2; exit 1; }
}
echo "== wheels"
fetch livekit-1.1.21-py3-none-win_amd64.whl --platform win_amd64 --python-version 3.12 livekit==1.1.21
fetch livekit-1.1.21-py3-none-manylinux_2_28_x86_64.whl --platform manylinux_2_28_x86_64 --python-version 3.12 livekit==1.1.21
fetch sherpa_onnx_core-1.13.8-py3-none-win_amd64.whl --platform win_amd64 --python-version 3.12 sherpa-onnx-core==1.13.8
fetch sherpa_onnx_core-1.13.8-py3-none-manylinux2014_x86_64.whl --platform manylinux2014_x86_64 sherpa-onnx-core==1.13.8
fetch protobuf_protoc_bin-36.2-py2.py3-none-manylinux_2_24_x86_64.whl --platform manylinux_2_24_x86_64 protobuf-protoc-bin==36.2
for f in "$W"/whl/*.whl; do d="$W/x/$(basename "$f" .whl)"; [ -d "$d" ] || { mkdir -p "$d"; unzip -q "$f" -d "$d"; }; done
LKW="$W/x/livekit-1.1.21-py3-none-win_amd64/livekit/rtc"; LKL="$W/x/livekit-1.1.21-py3-none-manylinux_2_28_x86_64/livekit/rtc"
SOW="$W/x/sherpa_onnx_core-1.13.8-py3-none-win_amd64/sherpa_onnx"; SOL="$W/x/sherpa_onnx_core-1.13.8-py3-none-manylinux2014_x86_64/sherpa_onnx"
PROTOC="$(find "$W/x/protobuf_protoc_bin-36.2-py2.py3-none-manylinux_2_24_x86_64" -name protoc -type f | head -1)"; chmod +x "$PROTOC"

echo "== libraries"
cp "$LKL/resources/liblivekit_ffi.so" "$SOL/lib/libsherpa-onnx-c-api.so" "$SOL/lib/libonnxruntime.so" "$OUT/linux-x64/"
cp "$LKW/resources/livekit_ffi.dll" "$SOW/lib/sherpa-onnx-c-api.dll" "$SOW/lib/onnxruntime.dll" "$OUT/windows-x64/"
cp "$LKW/resources/LICENSE.md" "$OUT/LICENSE-livekit-ffi.md"

echo "== JNI bridge (clu_voice)"
JI="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")/include"
gcc -O2 -shared -fPIC -Wall -Wno-deprecated-declarations -o "$OUT/linux-x64/libclu_voice.so" native/voice/clu_voice_jni.c -I"$JI" -I"$JI/linux" -I"$SOL/include" \
  -L"$OUT/linux-x64" -lsherpa-onnx-c-api -llivekit_ffi -Wl,-rpath,'$ORIGIN'
x86_64-w64-mingw32-gcc -O2 -shared -Wall -Wno-deprecated-declarations -static-libgcc -o "$OUT/windows-x64/clu_voice.dll" native/voice/clu_voice_jni.c -Inative/jni/win32 -I"$JI" -I"$SOW/include" \
  "$OUT/windows-x64/sherpa-onnx-c-api.dll" "$OUT/windows-x64/livekit_ffi.dll"

echo "== livekit-server $LK_SERVER"
if [ ! -x "$OUT/linux-x64/livekit-server" ] || [ ! -f "$OUT/windows-x64/livekit-server.exe" ]; then
  go mod download "github.com/livekit/livekit-server@$LK_SERVER"
  SRC="$W/livekit-server"; rm -rf "$SRC"; cp -r "$(go env GOMODCACHE)/github.com/livekit/livekit-server@$LK_SERVER" "$SRC"; chmod -R u+w "$SRC"
  (cd "$SRC" && CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o "$OUT/linux-x64/livekit-server" ./cmd/server)
  # GUI subsystem on Windows: CLU starts it in the background and no console window may appear.
  (cd "$SRC" && CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-s -w -H=windowsgui" -o "$OUT/windows-x64/livekit-server.exe" ./cmd/server)
  cp "$SRC/LICENSE" "$OUT/LICENSE-livekit-server.txt"
fi

echo "== FFI protocol classes"
python3 -I scripts/livekit_ffi_descriptors.py "$W/x/livekit-1.1.21-py3-none-manylinux_2_28_x86_64" "$W/ffi.desc"
rm -rf desktop/src/ffi/java; mkdir -p desktop/src/ffi/java
"$PROTOC" --descriptor_set_in="$W/ffi.desc" --java_out=lite:desktop/src/ffi/java $(python3 -I -c 'import sys; from google.protobuf import descriptor_pb2 as d; s=d.FileDescriptorSet(); s.ParseFromString(open(sys.argv[1],"rb").read()); print(" ".join(f.name for f in s.file))' "$W/ffi.desc")
ls -la "$OUT"/*
