"""Reasoning backends.

Holds the replaceable interfaces for local text generation and embedding, and
the in-process llama.cpp implementation of them.

Not a constitution component in its own right: it exists so that the runtime
choice has a home that no engine or agent owns. See ``docs/architecture.md``.

**No module in this package may import a network library.** Inference happens
in this process against a local GGUF file; there is no socket, no HTTP client,
and no daemon anywhere in the call path (ADR-0004, superseding ADR-0001 D5).
``tests/test_reasoning.py`` enforces this.
"""

from __future__ import annotations

from astra.reasoning.backends import (
    BackendUnavailableError,
    EmbeddingBackend,
    GenerationOptions,
    LLMBackend,
)
from astra.reasoning.llama_cpp_backend import (
    LlamaCppEmbeddingBackend,
    LlamaCppLLMBackend,
    ModelFileMissingError,
    binding_available,
)
from astra.reasoning.vision import (
    UnavailableVisionBackend,
    VisionBackend,
    vision_from_settings,
)

__all__ = [
    "BackendUnavailableError",
    "GenerationOptions",
    "LLMBackend",
    "EmbeddingBackend",
    "LlamaCppLLMBackend",
    "LlamaCppEmbeddingBackend",
    "ModelFileMissingError",
    "binding_available",
    "UnavailableVisionBackend",
    "VisionBackend",
    "vision_from_settings",
]
