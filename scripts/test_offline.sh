#!/usr/bin/env bash
# Runs Clu's offline conversation test inside a new network namespace (unshare -rn) that has only a loopback
# interface - no route to any network, no DNS: proves a new voice conversation (ASR -> local llama.cpp -> TTS)
# completes with network access disabled. Gradle runs --offline --no-daemon inside the namespace, so all build
# dependencies must already be cached (run any normal build once first).
#
# usage: scripts/test_offline.sh <python with sherpa-onnx==1.13.8> <models dir>
set -euo pipefail
cd "$(dirname "$0")/.."
PY="$1"; MODELS="$2"
exec unshare -rn bash -c '
python3 -I -c "
import socket, fcntl, struct
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
flags = struct.unpack(\"16sH\", fcntl.ioctl(s, 0x8913, struct.pack(\"16sH\", b\"lo\", 0)))[1]  # SIOCGIFFLAGS
fcntl.ioctl(s, 0x8914, struct.pack(\"16sH\", b\"lo\", flags | 1))                         # SIOCSIFFLAGS: lo up (Gradle needs it)
t = socket.socket(); t.settimeout(3)
assert t.connect_ex((\"1.1.1.1\", 443)) != 0, \"network is reachable - not an offline environment\"
print(\"network namespace: loopback only, internet unreachable\")
"
./gradlew --offline --no-daemon -q :lkclient:test --tests "*OfflineConversationTest*" \
  -Dclu.offline=true -Dclu.lk.python="$0" -Dclu.lk.models="$1"
' "$PY" "$MODELS"
