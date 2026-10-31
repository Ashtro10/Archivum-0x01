"""Optional deep-reasoning GGUF backend (secondary to chat model)."""

from __future__ import annotations

from typing import Any

from astra.configuration.settings import AstraSettings, ReasoningSettings
from astra.reasoning.llama_cpp_backend import LlamaCppLLMBackend


def deep_reasoning_settings(reasoning: ReasoningSettings) -> ReasoningSettings | None:
    """Return settings for the deep model when configured and present."""
    path = reasoning.deep_model_path
    if path is None:
        return None
    try:
        if not path.is_file():
            return None
    except OSError:
        return None
    layers = reasoning.deep_gpu_layers
    if layers is None:
        layers = reasoning.gpu_layers
    label = str(reasoning.deep_model or "").strip() or path.stem
    return reasoning.model_copy(
        update={
            "model": label,
            "model_path": path,
            "gpu_layers": layers,
        }
    )


def build_deep_llm(settings: AstraSettings) -> LlamaCppLLMBackend | None:
    """Construct the deep backend. Does not load weights until first generate."""
    deep = deep_reasoning_settings(settings.reasoning)
    if deep is None:
        return None
    return LlamaCppLLMBackend.from_settings(deep)


def agent_llm(primary: Any, deep: Any | None) -> Any:
    """Pick LLM for AgentLoop: deep when configured, else primary."""
    if deep is not None and getattr(deep, "is_available", lambda: False)():
        return deep
    if deep is not None:
        probe = getattr(deep, "unavailable_reason", None)
        if callable(probe) and not str(probe() or "").strip():
            return deep
    return primary


__all__ = ["agent_llm", "build_deep_llm", "deep_reasoning_settings"]
