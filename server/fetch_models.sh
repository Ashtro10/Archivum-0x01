#!/usr/bin/env bash
# Downloads the speech models the voice agent uses (same files and SHA-256 pins as the Clu app build).
# Total ~115 MB download. Usage: ./fetch_models.sh [dir]   (default ./models)
set -euo pipefail
D="${1:-models}"; mkdir -p "$D"; cd "$D"
R=https://github.com/k2-fsa/sherpa-onnx/releases/download
get() { # name url sha256
  if [[ ! -f "$1" ]]; then
    curl -fL --retry 3 -o "$1.part" "$2"
    mv -f "$1.part" "$1"
  fi
  if ! echo "$3  $1" | sha256sum -c --quiet -; then
    echo "checksum mismatch: $1" >&2; rm -f "$1"; exit 1
  fi
}
get silero_vad.onnx "$R/asr-models/silero_vad.onnx" 9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6
get asr.tar.bz2 "$R/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2" 9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9
get tts.tar.bz2 "$R/tts-models/vits-piper-en_US-ryan-medium.tar.bz2" c546af78b6395b4e7c4ce1ed899438b64426a362f5d4ec5fecd090ded9ad7505
[[ -d sherpa-onnx-streaming-zipformer-en-20M-2023-02-17 ]] || tar xjf asr.tar.bz2
[[ -d vits-piper-en_US-ryan-medium ]] || tar xjf tts.tar.bz2
echo "models ready in $(pwd)"
