"""Local model runtime interfaces.

ADR-0001 D5 selects Ollama as the default backend and llama.cpp as the second
implementation. Both run GGUF models locally. These interfaces are the seam
that makes the choice reversible: swapping runtimes must never require changes
outside this package.

Reasoning is temporary and memory is permanent, so nothing here may hold
durable state.

Interfaces only: no behaviour is implemented here.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Sequence


class BackendUnavailableError(RuntimeError):
    """Raised when a local runtime is not reachable.

    Expected in normal operation, for example when Ollama is not running. The
    message must tell the user how to start the runtime rather than exposing a
    connection trace.
    """


@dataclass(frozen=True, slots=True)
class GenerationOptions:
    """Sampling parameters for one generation call.

    Attributes:
        temperature: Randomness. ``0.0`` is deterministic.
        max_tokens: Upper bound on generated tokens.
        stop: Sequences that end generation.
        seed: Fixed seed for reproducible output; required when a result is
            used as experimental evidence.
        repeat_penalty: llama.cpp repetition penalty. ``None`` leaves the
            runtime default. Values above ``1.0`` discourage looping.
    """

    temperature: float = 0.2
    max_tokens: int = 1024
    stop: tuple[str, ...] = ()
    seed: int | None = None
    repeat_penalty: float | None = None


class LLMBackend(ABC):
    """Text generation from a locally executed model."""

    @property
    @abstractmethod
    def model_name(self) -> str:
        """Name of the loaded model, recorded alongside any result."""
        raise NotImplementedError

    @abstractmethod
    def is_available(self) -> bool:
        """Report whether the local runtime is reachable.

        Must not raise, and must not block for longer than the configured
        timeout.
        """
        raise NotImplementedError

    @abstractmethod
    def generate(self, prompt: str, options: GenerationOptions | None = None) -> str:
        """Return a completion for ``prompt``.

        Raises:
            BackendUnavailableError: if the local runtime cannot be reached.
        """
        raise NotImplementedError


class EmbeddingBackend(ABC):
    """Vector embeddings from a locally executed model.

    Every vector produced must be attributable to :attr:`model_name` and
    :attr:`dimension`, because a change to either invalidates the derived
    index (ADR-0001 D1).
    """

    @property
    @abstractmethod
    def model_name(self) -> str:
        """Name of the embedding model."""
        raise NotImplementedError

    @property
    @abstractmethod
    def dimension(self) -> int:
        """Width of the vectors this backend produces."""
        raise NotImplementedError

    @abstractmethod
    def is_available(self) -> bool:
        """Report whether the local runtime is reachable. Must not raise."""
        raise NotImplementedError

    @abstractmethod
    def embed(self, texts: Sequence[str]) -> Sequence[Sequence[float]]:
        """Return one vector per input text, in input order.

        Raises:
            BackendUnavailableError: if the local runtime cannot be reached.
        """
        raise NotImplementedError


__all__ = [
    "BackendUnavailableError",
    "GenerationOptions",
    "LLMBackend",
    "EmbeddingBackend",
]
