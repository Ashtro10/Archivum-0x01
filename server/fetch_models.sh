#!/usr/bin/env bash
# Downloads the speech models for the agent's LOCAL stack only (CLU_STACK=local). The default cloud stack needs none.
# Same files and SHA-256 pins as the Clu app build:
#   Kroko streaming English recogniser  57 MB download, 68 MB on disk  -> models/kroko-en
#   Piper "Joe" US male voice           67 MB download, ~110 MB on disk -> models/vits-piper-en_US-joe-medium
# Total ~124 MB download, ~180 MB on disk. Usage: ./fetch_models.sh [dir]   (default ./models)
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
get asr-kroko.tar.bz2 "$R/asr-models/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2" c8676e5ff9ac2a85296e53ee0fd4d5fb1db6770e7a7647166eeafe349ade6834
get tts-joe.tar.bz2 "$R/tts-models/vits-piper-en_US-joe-medium.tar.bz2" 261b903a1fb2ce621e2b5b14233cd41223c3b9f9590f896c11daee4df22d9bd3
if [[ ! -f kroko-en/tokens.txt ]]; then
  tar xjf asr-kroko.tar.bz2
  mkdir -p kroko-en
  K=sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06
  cp "$K/encoder.onnx" "$K/decoder.onnx" "$K/joiner.onnx" "$K/tokens.txt" kroko-en/
  rm -rf "$K"
fi
[[ -d vits-piper-en_US-joe-medium ]] || tar xjf tts-joe.tar.bz2
echo "models ready in $(pwd)"
