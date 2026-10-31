"""Real LLM smoke test. Does not mock the model.

Exit 0: model generated text containing PRIMUS_RUNTIME_OK
Exit 2: model/runtime unavailable (honest, not a fake pass)
Exit 1: model ran but did not follow the instruction
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

TOKEN = "PRIMUS_RUNTIME_OK"


def main() -> int:
    from astra.configuration import load_settings
    from astra.reasoning.llama_cpp_backend import (
        LlamaCppLLMBackend,
        prepare_llama_native_libs,
    )
    from astra.reasoning.backends import GenerationOptions

    prepare_llama_native_libs()
    settings = load_settings()
    llm = LlamaCppLLMBackend.from_settings(settings.reasoning)
    if not llm.is_available():
        why = llm.unavailable_reason()
        print(
            "LLM unavailable. Set [reasoning].model_path in astra.toml "
            "or place a .gguf in models/ or benchmarks/models/. "
            f"{why}",
            file=sys.stderr,
        )
        return 2
    print(f"model={settings.reasoning.model_path}", flush=True)
    prompt = f"Reply with exactly {TOKEN} and nothing else."
    try:
        text = llm.generate(
            prompt,
            GenerationOptions(temperature=0.0, max_tokens=32, seed=1),
        )
    except Exception as exc:
        print(f"generate failed: {exc}", file=sys.stderr)
        return 2
    print(text)
    if TOKEN in text:
        return 0
    print("model ran but token missing", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
