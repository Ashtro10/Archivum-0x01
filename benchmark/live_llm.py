"""Load configured local LlamaCpp backend for live benchmark runs."""

from __future__ import annotations

import time
from typing import Any

from astra.configuration.loader import load_settings
from astra.reasoning.backends import GenerationOptions
from astra.reasoning.llama_cpp_backend import LlamaCppLLMBackend


class TimedLiveLLM:
    """Wrap LlamaCppLLMBackend. Count calls + wall latency. No prompt logging."""

    def __init__(self, inner: LlamaCppLLMBackend) -> None:
        self._inner = inner
        self.llm_calls = 0
        self.total_latency_s = 0.0
        self.prompt_chars = 0

    @property
    def model_name(self) -> str:
        return self._inner.model_name

    def is_available(self) -> bool:
        return self._inner.is_available()

    def unavailable_reason(self) -> str:
        return self._inner.unavailable_reason()

    def generate(self, prompt: str, options: GenerationOptions | None = None) -> str:
        started = time.monotonic()
        text = self._inner.generate(prompt, options=options or GenerationOptions(temperature=0.2, max_tokens=512))
        self.total_latency_s += max(0.0, time.monotonic() - started)
        self.llm_calls += 1
        self.prompt_chars += len(str(prompt or ""))
        return str(text or "").strip()


def load_live_llm() -> TimedLiveLLM:
    settings = load_settings()
    backend = LlamaCppLLMBackend.from_settings(settings.reasoning)
    if not backend.is_available():
        reason = backend.unavailable_reason() or "local model unavailable"
        raise RuntimeError(reason)
    return TimedLiveLLM(backend)


__all__ = ["TimedLiveLLM", "load_live_llm"]
