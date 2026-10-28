#!/usr/bin/env bash
# Builds the host JNI library and tiny random-weight GGUF models used by JVM integration tests (idempotent).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[[ -f "$ROOT/native/build/host/libclu_llama.so" && -f "$ROOT/native/build/host/libclu_whisper.so" ]] || "$ROOT/scripts/build_native.sh" host
mkdir -p "$ROOT/native/build/test"
python3 -c "import numpy" 2>/dev/null || pip install -q numpy
[[ -f "$ROOT/native/build/test/tiny.gguf" ]] || python3 "$ROOT/scripts/make_test_gguf.py" "$ROOT/native/llama.cpp" "$ROOT/native/build/test/tiny.gguf" 42
[[ -f "$ROOT/native/build/test/tiny2.gguf" ]] || python3 "$ROOT/scripts/make_test_gguf.py" "$ROOT/native/llama.cpp" "$ROOT/native/build/test/tiny2.gguf" 7
[[ -f "$ROOT/native/build/test/tiny-llama3.gguf" ]] || python3 "$ROOT/scripts/make_test_gguf.py" "$ROOT/native/llama.cpp" "$ROOT/native/build/test/tiny-llama3.gguf" 11 --vocab llama-bpe
