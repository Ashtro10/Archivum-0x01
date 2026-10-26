#!/usr/bin/env bash
# Fetches the official LiveKit Android SDK (io.livekit:livekit-android) and its Android-library dependencies from
# Maven Central and unpacks what the custom (no-AGP) build needs: classes.jar of each AAR and the arm64-v8a native
# libraries. Plain-jar dependencies (OkHttp, Dagger, protobuf-javalite, ...) are resolved by Gradle.
# Every archive is pinned by SHA-256.
#
# Not used from the SDK's dependency list:
#  - androidx.core / androidx.annotation (Google Maven, unreachable here): the SDK only calls
#    ContextCompat.checkSelfPermission and, in its screen-share service, NotificationCompat.Builder; small
#    source shims in app/src/main/kotlin/androidx/ provide exactly those (annotations are compile-time only).
#  - com.github.davidliu:audioswitch (JitPack, unreachable): Clu passes its own AudioHandler
#    (ai.clu.voice.CluAudioHandler), so the SDK's AudioSwitchHandler is never instantiated.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/toolchain/livekit"
DL="$OUT/dl"; JARS="$OUT/jars"; JNI="$OUT/jniLibs/arm64-v8a"
mkdir -p "$DL" "$JARS" "$JNI"
M=${MAVEN:-https://maven-central.storage-download.googleapis.com/maven2}

fetch() { # path sha256
  local f="$DL/$(basename "$1")"
  if [[ -f "$f" ]] && echo "$2  $f" | sha256sum -c --quiet - 2>/dev/null; then return; fi
  curl -fsSL --retry 3 -o "$f.part" "$M/$1" 2>/dev/null || curl -fsSL --retry 3 -o "$f.part" "https://repo1.maven.org/maven2/$1"
  echo "$2  $f.part" | sha256sum -c --quiet - || { echo "checksum mismatch: $1" >&2; rm -f "$f.part"; exit 1; }
  mv "$f.part" "$f"
}
aar() { # path sha256 jarname
  fetch "$1" "$2"
  local f="$DL/$(basename "$1")" t; t="$(mktemp -d)"
  unzip -q -o "$f" -d "$t"
  # classes.jar, plus libs/*.jar (the WebRTC AAR keeps its classes there; its classes.jar is empty)
  if [[ "$(unzip -Z1 "$t/classes.jar" 2>/dev/null | grep -c '\.class$' || true)" -gt 0 ]]; then cp "$t/classes.jar" "$JARS/$3.jar"; fi
  if [[ -d "$t/libs" ]]; then for j in "$t"/libs/*.jar; do cp "$j" "$JARS/$3-$(basename "$j")"; done; fi
  [[ -d "$t/jni/arm64-v8a" ]] && cp "$t"/jni/arm64-v8a/*.so "$JNI/"
  rm -rf "$t"
}

aar io/livekit/livekit-android/2.29.2/livekit-android-2.29.2.aar 4ede9085f52df4f50f4218bfe523d9e808812db44c7443bba7fd3a7bc379f076 livekit-android
aar io/github/webrtc-sdk/android-prefixed/150.7871.04/android-prefixed-150.7871.04.aar 96f1ebbd7128984a4ca8abb6fba68f212adf75ee6606c5f3cb60ad8f7db5d8b3 webrtc-prefixed
aar io/livekit/livekit-uniffi-android/0.1.12/livekit-uniffi-android-0.1.12.aar bf4a46a56e7a65d0d8de1be39aa3ff54e5f5923fcee92a4894cea52b8a8d8a3d livekit-uniffi
aar com/auth0/android/jwtdecode/2.0.2/jwtdecode-2.0.2.aar 89d4d1cd4a86cc9815d25e20993cef92d70f3a40a58ce861fe893e0814e8fe72 jwtdecode
aar net/java/dev/jna/jna/5.19.1/jna-5.19.1.aar b57125cb7d16253f0d65a80f7d3a4c3664effa711b8bdbb7f87fb572ce1624ed jna
echo "LiveKit Android SDK unpacked into $OUT"
ls -la "$JARS" "$JNI"
