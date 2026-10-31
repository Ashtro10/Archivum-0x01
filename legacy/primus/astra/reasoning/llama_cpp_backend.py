"""In-process llama.cpp backends (ADR-0004).

No socket, no HTTP client, no daemon, no subprocess. The model is loaded inside
Astra's own process through the ``llama_cpp`` binding, and the only external
resource involved is a GGUF file on disk.

The binding is imported lazily, on first use, so that Astra imports and its test
suite runs on a machine where ``llama-cpp-python`` is not installed. Astra never
installs the package and never downloads a model; both are user-initiated
(ADR-0004 D6, backlog B-002 stage 3).
"""

from __future__ import annotations

import importlib
import importlib.util
import os
import sys
from pathlib import Path
from typing import Any, Iterator, Sequence

from astra.logging import get_logger
from astra.reasoning.backends import (
    BackendUnavailableError,
    EmbeddingBackend,
    GenerationOptions,
    LLMBackend,
)

_LOGGER = get_logger(__name__)

BINDING_MODULE = "llama_cpp"
_NATIVE_LIBS_READY = False


def prepare_llama_native_libs() -> None:
    """Put llama.cpp + CUDA runtime DLLs on the Windows search path.

    The CUDA wheel of llama-cpp-python loads llama.dll which depends on
    ggml-cuda.dll, which needs NVIDIA pip CUDA bins (cudart/cublas).
    ``add_dll_directory`` alone is not enough; PATH must include those dirs.
    """
    global _NATIVE_LIBS_READY
    if _NATIVE_LIBS_READY or sys.platform != "win32":
        return
    roots: list[Path] = []
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        roots.append(Path(meipass))
    prefix = Path(sys.prefix) / "Lib" / "site-packages"
    roots.append(prefix)
    exe_dir = Path(sys.executable).resolve().parent
    roots.append(exe_dir)
    roots.append(exe_dir / "_internal")
    rels = (
        Path("llama_cpp") / "lib",
        Path("nvidia") / "cuda_runtime" / "bin",
        Path("nvidia") / "cublas" / "bin",
        Path("nvidia") / "cuda_nvrtc" / "bin",
    )
    seen: set[str] = set()
    for root in roots:
        for rel in rels:
            directory = (root / rel).resolve()
            key = str(directory).lower()
            if key in seen or not directory.is_dir():
                continue
            seen.add(key)
            try:
                os.add_dll_directory(str(directory))
            except (OSError, AttributeError):
                pass
            os.environ["PATH"] = str(directory) + os.pathsep + os.environ.get("PATH", "")
    _NATIVE_LIBS_READY = True


class ModelFileMissingError(BackendUnavailableError):
    """Raised when the configured GGUF file is absent or unconfigured.

    A subclass of :class:`BackendUnavailableError` because, from a caller's
    point of view, a model that is not on disk and a runtime that is not
    installed are the same condition: no local inference is possible.
    """


def binding_available() -> bool:
    """Report whether ``llama_cpp`` can be imported, without importing it.

    Uses :func:`importlib.util.find_spec`, so the check stays cheap and has no
    side effects. Never raises.
    """
    if BINDING_MODULE in sys.modules:
        return True
    try:
        return importlib.util.find_spec(BINDING_MODULE) is not None
    except (ImportError, ValueError):
        return False


def _llama_class() -> Any:
    prepare_llama_native_libs()
    try:
        module = importlib.import_module(BINDING_MODULE)
    except (ImportError, OSError, RuntimeError) as exc:
        raise BackendUnavailableError(
            "llama-cpp-python is not usable, so local inference is "
            "unavailable. Install it yourself when you are ready; Astra never "
            "installs packages or downloads models on your behalf. "
            f"({exc})"
        ) from exc
    return module.Llama


class _ModelLoader:
    """Loads and holds one GGUF model.

    Shared by the generation and embedding backends through composition rather
    than a common base class, because the two backends have different
    interfaces and only this loading behaviour in common.
    """

    def __init__(
        self,
        model_path: Path | None,
        *,
        context_tokens: int,
        gpu_layers: int,
        threads: int | None,
        embedding: bool,
    ) -> None:
        self._path = Path(model_path) if model_path is not None else None
        self._context_tokens = context_tokens
        self._gpu_layers = gpu_layers
        self._threads = threads
        self._embedding = embedding
        self._model: Any | None = None

    @property
    def path(self) -> Path | None:
        return self._path

    @property
    def loaded(self) -> bool:
        return self._model is not None

    def available(self) -> bool:
        """Whether a load would be possible right now. Never raises."""
        if not binding_available():
            return False
        try:
            return self._path is not None and self._path.is_file()
        except OSError:
            return False

    def model(self) -> Any:
        """Return the loaded model, loading it on first call.

        Raises:
            BackendUnavailableError: if the binding is not installed.
            ModelFileMissingError: if no model file is configured or present.
        """
        if self._model is not None:
            return self._model

        if self._path is None:
            raise ModelFileMissingError(
                "no model file is configured; set reasoning.model_path (or "
                "reasoning.embedding_model_path) to a local GGUF file"
            )
        if not self._path.is_file():
            raise ModelFileMissingError(
                f"model file not found: {self._path}. Astra does not download "
                "models; provide the file locally."
            )

        llama = _llama_class()
        arguments: dict[str, Any] = {
            "model_path": str(self._path),
            "n_ctx": self._context_tokens,
            "n_gpu_layers": self._gpu_layers,
            "embedding": self._embedding,
            "use_mmap": True,
            "use_mlock": False,
            "verbose": False,
        }
        if self._threads is not None:
            arguments["n_threads"] = self._threads

        _LOGGER.info(
            "loading %s (context=%s, gpu_layers=%s, embedding=%s)",
            self._path.name,
            self._context_tokens,
            self._gpu_layers,
            self._embedding,
        )
        self._model = llama(**arguments)
        return self._model

    def unload(self) -> None:
        """Drop the model, releasing its memory. Safe to call repeatedly."""
        self._model = None


class LlamaCppLLMBackend(LLMBackend):
    """Text generation from a local GGUF model, in this process."""

    def __init__(
        self,
        model_path: Path | None,
        *,
        label: str = "",
        context_tokens: int = 2048,
        gpu_layers: int = 0,
        threads: int | None = None,
    ) -> None:
        self._label = label
        self._loader = _ModelLoader(
            model_path,
            context_tokens=context_tokens,
            gpu_layers=gpu_layers,
            threads=threads,
            embedding=False,
        )

    @classmethod
    def from_settings(cls, settings: Any) -> "LlamaCppLLMBackend":
        """Build from :class:`astra.configuration.settings.ReasoningSettings`."""
        return cls(
            settings.model_path,
            label=settings.model,
            context_tokens=settings.context_tokens,
            gpu_layers=settings.gpu_layers,
            threads=settings.threads,
        )

    @property
    def model_name(self) -> str:
        if self._label:
            return self._label
        path = self._loader.path
        return path.name if path is not None else "unconfigured"

    def is_available(self) -> bool:
        return self._loader.available()

    def unavailable_reason(self) -> str:
        """Human-readable why generate would fail. Never raises."""
        if self._loader.path is None:
            return (
                "No model file configured. Place a .gguf in models/ or "
                "benchmarks/models/, or set [reasoning].model_path in astra.toml."
            )
        try:
            if not self._loader.path.is_file():
                return f"Model file not found: {self._loader.path}"
        except OSError:
            return f"Model file not readable: {self._loader.path}"
        if not binding_available():
            return "llama-cpp-python is not installed."
        return ""

    def generate(self, prompt: str, options: GenerationOptions | None = None) -> str:
        """Return a completion for ``prompt``.

        Raises:
            BackendUnavailableError: if the binding or the model file is
                missing.
            RuntimeError: if the binding returns a response shape this adapter
                does not recognise, which would mean the binding's API changed.
        """
        settings = options or GenerationOptions()
        model = self._loader.model()

        arguments: dict[str, Any] = {
            "max_tokens": settings.max_tokens,
            "temperature": settings.temperature,
        }
        if settings.stop:
            arguments["stop"] = list(settings.stop)
        if settings.seed is not None:
            arguments["seed"] = settings.seed
        if settings.repeat_penalty is not None:
            arguments["repeat_penalty"] = settings.repeat_penalty

        response = model.create_completion(prompt, **arguments)
        try:
            return str(response["choices"][0]["text"])
        except (KeyError, IndexError, TypeError) as exc:
            raise RuntimeError(
                f"unrecognised completion response from {BINDING_MODULE}: {response!r}"
            ) from exc

    def generate_stream(
        self, prompt: str, options: GenerationOptions | None = None
    ) -> Iterator[str]:
        """Yield completion text chunks as the model generates them."""
        settings = options or GenerationOptions()
        model = self._loader.model()

        arguments: dict[str, Any] = {
            "max_tokens": settings.max_tokens,
            "temperature": settings.temperature,
            "stream": True,
        }
        if settings.stop:
            arguments["stop"] = list(settings.stop)
        if settings.seed is not None:
            arguments["seed"] = settings.seed
        if settings.repeat_penalty is not None:
            arguments["repeat_penalty"] = settings.repeat_penalty

        for chunk in model.create_completion(prompt, **arguments):
            try:
                choice = chunk["choices"][0]
                piece = str(choice.get("text") or "")
                if not piece:
                    delta = choice.get("delta") or {}
                    piece = str(delta.get("content") or "")
            except (KeyError, IndexError, TypeError):
                continue
            if piece:
                yield piece

    def unload(self) -> None:
        """Release the model. The next call reloads it."""
        self._loader.unload()


class LlamaCppEmbeddingBackend(EmbeddingBackend):
    """Embeddings from a local GGUF model, in this process.

    Defaults to CPU (``gpu_layers=0``) per ADR-0004 D7: confirmed by the
    B-002 stage 4 benchmark. The Qwen2.5-3B generation model occupies
    ~2740 MiB of the 4 GiB VRAM budget at 4096-token context; a 137 M-
    parameter encoder on six Zen 3 cores is fast enough for the expected
    corpus size without competing for that headroom. Configurable; raise
    ``embedding_gpu_layers`` if a bulk re-index proves slow.
    """

    def __init__(
        self,
        model_path: Path | None,
        *,
        label: str = "",
        context_tokens: int = 2048,
        gpu_layers: int = 0,
        threads: int | None = None,
    ) -> None:
        self._label = label
        self._loader = _ModelLoader(
            model_path,
            context_tokens=context_tokens,
            gpu_layers=gpu_layers,
            threads=threads,
            embedding=True,
        )

    @classmethod
    def from_settings(cls, settings: Any) -> "LlamaCppEmbeddingBackend":
        """Build from :class:`astra.configuration.settings.ReasoningSettings`.

        Uses ``embedding_context_tokens`` (default 2048) rather than
        ``context_tokens`` (generation, default 4096). B-010 established that
        nomic-embed-text-v1.5 Q8_0 has n_ctx_train = 2048; setting a larger
        context triggers a training-overflow warning.
        """
        embed_ctx = getattr(settings, "embedding_context_tokens", 2048)
        return cls(
            settings.embedding_model_path,
            label=settings.embedding_model,
            context_tokens=embed_ctx,
            gpu_layers=settings.embedding_gpu_layers,
            threads=settings.threads,
        )

    @property
    def model_name(self) -> str:
        if self._label:
            return self._label
        path = self._loader.path
        return path.name if path is not None else "unconfigured"

    @property
    def dimension(self) -> int:
        """Vector width reported by the loaded model.

        Loading is required to answer, because the width is a property of the
        model file rather than of the configuration.
        """
        model = self._loader.model()
        width = getattr(model, "n_embd", None)
        if not callable(width):
            raise RuntimeError(
                f"{BINDING_MODULE} model does not report n_embd; cannot "
                "determine embedding dimension"
            )
        return int(width())

    def is_available(self) -> bool:
        return self._loader.available()

    def embed(self, texts: Sequence[str]) -> Sequence[Sequence[float]]:
        """Return one vector per input text, in input order."""
        if not texts:
            return ()

        model = self._loader.model()
        response = model.create_embedding(list(texts))
        try:
            entries = response["data"]
        except (KeyError, TypeError) as exc:
            raise RuntimeError(
                f"unrecognised embedding response from {BINDING_MODULE}: {response!r}"
            ) from exc

        return tuple(_as_vector(entry["embedding"]) for entry in entries)

    def unload(self) -> None:
        """Release the model. The next call reloads it."""
        self._loader.unload()


def _as_vector(embedding: Any) -> tuple[float, ...]:
    """Convert one embedding entry to a flat vector.

    Some models return token-level embeddings rather than a single pooled
    vector. Pooling changes retrieval behaviour, so this adapter refuses rather
    than averaging silently: the choice belongs with model selection in
    ADR-0005, decided on evidence.
    """
    if embedding and isinstance(embedding[0], (list, tuple)):
        raise RuntimeError(
            "model returned token-level embeddings; a pooling strategy must be "
            "chosen in ADR-0005 before this model can be used"
        )
    return tuple(float(value) for value in embedding)


__all__ = [
    "BINDING_MODULE",
    "ModelFileMissingError",
    "binding_available",
    "prepare_llama_native_libs",
    "LlamaCppLLMBackend",
    "LlamaCppEmbeddingBackend",
]
