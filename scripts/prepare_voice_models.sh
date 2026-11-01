#!/bin/bash
# Assembles the speech models installed with CLU (and used by the tests) into build/voice-models:
#   vad/silero_vad.onnx                       Silero VAD (MIT)
#   stt-kroko-en/{encoder,decoder,joiner}.onnx tokens.txt   Kroko streaming English (see docs/VOICE_MODELS.md for its licence note)
#   espeak-ng-data/                           pronunciation data shared by the voices (GPL-3.0, from espeak-ng)
#   voices/<id>/model.onnx tokens.txt [voices.bin]   Piper voices Joe, Alan, Ryan; KittenTTS nano (8 speakers)
#   optional/stt-whisper-base/                Whisper base int8 (MIT), multilingual; an optional installer component
# Sources: the same archives as the voice downloads (k2-fsa/sherpa-onnx releases), already unpacked in CLU_VOICE_CACHE.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
C="${CLU_VOICE_CACHE:-/root/clu-build/models}"; A="$ROOT/toolchain/assets/models"; O="$ROOT/build/voice-models"
rm -rf "$O"; mkdir -p "$O"/{vad,stt-kroko-en,voices,optional}
cp "$A/vad/silero_vad.onnx" "$O/vad/"
cp "$A/asr/"{encoder.onnx,decoder.onnx,joiner.onnx,tokens.txt,MODEL.txt} "$O/stt-kroko-en/"
cp -r "$C/vits-piper-en_US-joe-medium/espeak-ng-data" "$O/espeak-ng-data"
for v in "joe:en_US-joe-medium" "alan:en_GB-alan-medium" "ryan:en_US-ryan-medium"; do
  id=${v%%:*}; m=${v#*:}; d="$O/voices/piper-$id"; mkdir -p "$d"
  cp "$C/vits-piper-$m/$m.onnx" "$d/model.onnx"; cp "$C/vits-piper-$m/tokens.txt" "$d/"; cp "$C/vits-piper-$m/MODEL_CARD" "$d/" 2>/dev/null || true
done
d="$O/voices/kitten-nano"; mkdir -p "$d"; cp "$C/kitten-nano-en-v0_8-int8/"{model.int8.onnx,voices.bin,tokens.txt,LICENSE} "$d/"; mv "$d/model.int8.onnx" "$d/model.onnx"
W="$C/sherpa-onnx-whisper-base"; if [ -d "$W" ]; then d="$O/optional/stt-whisper-base"; mkdir -p "$d"; cp "$W/base-encoder.int8.onnx" "$d/encoder.onnx"; cp "$W/base-decoder.int8.onnx" "$d/decoder.onnx"; cp "$W/base-tokens.txt" "$d/tokens.txt"; fi
du -sh "$O"/* "$O"/voices/* "$O"/optional/* 2>/dev/null
