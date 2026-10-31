"""Reasoning backend tests (B-002 stage 1).

Two things are proved here. First, that the reasoning package contains no
network transport of any kind, which is the whole point of ADR-0004. Second,
that the llama.cpp adapters behave correctly, using a fake binding so the suite
passes on a machine with no ``llama-cpp-python`` installed and no model file.
"""

from __future__ import annotations

import importlib.machinery
import re
import sys
import tempfile
import types
import unittest
from pathlib import Path

import astra.reasoning
from astra.configuration import ReasoningSettings
from astra.reasoning import (
    BackendUnavailableError,
    GenerationOptions,
    LlamaCppEmbeddingBackend,
    LlamaCppLLMBackend,
    ModelFileMissingError,
)
from astra.reasoning.llama_cpp_backend import BINDING_MODULE

FORBIDDEN_MODULES = (
    "socket",
    "ssl",
    "subprocess",
    "httpx",
    "requests",
    "urllib",
    "urllib3",
    "http",
    "http.client",
    "aiohttp",
    "websockets",
    "xmlrpc",
    "ftplib",
    "telnetlib",
    "smtplib",
)

_IMPORT_PATTERN = re.compile(
    r"^\s*(?:import|from)\s+(" + "|".join(re.escape(m) for m in FORBIDDEN_MODULES) + r")\b",
    re.MULTILINE,
)


class NoNetworkTransportTest(unittest.TestCase):
    """ADR-0004: inference happens in this process, never over a socket."""

    def _sources(self) -> list[Path]:
        package_root = Path(astra.reasoning.__file__).parent
        return sorted(package_root.glob("*.py"))

    def test_package_has_sources(self) -> None:
        self.assertTrue(self._sources(), "no reasoning sources found to scan")

    def test_no_module_imports_a_network_library(self) -> None:
        for source in self._sources():
            text = source.read_text(encoding="utf-8")
            found = _IMPORT_PATTERN.findall(text)
            with self.subTest(module=source.name):
                self.assertEqual([], found, f"network import in {source.name}: {found}")

    def test_no_process_or_socket_calls(self) -> None:
        """Options B and D of ADR-0004 spawn processes; neither was chosen.

        Matches call sites rather than words, so prose in a docstring cannot
        trip it.
        """
        call_pattern = re.compile(
            r"\b(?:subprocess\.\w+|Popen|socket\.socket|os\.(?:system|popen|exec\w*|spawn\w*))\s*\("
        )
        for source in self._sources():
            text = source.read_text(encoding="utf-8")
            with self.subTest(module=source.name):
                self.assertIsNone(call_pattern.search(text))


class _FakeLlama:
    """Stands in for ``llama_cpp.Llama`` so tests need no model file."""

    instances: list["_FakeLlama"] = []

    def __init__(self, **kwargs: object) -> None:
        self.kwargs = kwargs
        self.completions: list[tuple[str, dict[str, object]]] = []
        self.embedded: list[list[str]] = []
        _FakeLlama.instances.append(self)

    def create_completion(self, prompt: str, **kwargs: object) -> dict[str, object]:
        self.completions.append((prompt, kwargs))
        return {"choices": [{"text": "a local completion"}]}

    def create_embedding(self, inputs: list[str]) -> dict[str, object]:
        self.embedded.append(list(inputs))
        return {"data": [{"embedding": [0.1, 0.2, 0.3]} for _ in inputs]}

    def n_embd(self) -> int:
        return 3


class FakeBindingTestCase(unittest.TestCase):
    """Installs a fake ``llama_cpp`` module and a placeholder model file."""

    def setUp(self) -> None:
        _FakeLlama.instances.clear()

        module = types.ModuleType(BINDING_MODULE)
        module.Llama = _FakeLlama  # type: ignore[attr-defined]
        module.__spec__ = importlib.machinery.ModuleSpec(BINDING_MODULE, loader=None)
        self._previous = sys.modules.get(BINDING_MODULE)
        sys.modules[BINDING_MODULE] = module
        self.addCleanup(self._restore_binding)

        self._temp = tempfile.TemporaryDirectory()
        self.addCleanup(self._temp.cleanup)
        self.model_path = Path(self._temp.name) / "model.gguf"
        self.model_path.write_bytes(b"not a real model")

    def _restore_binding(self) -> None:
        if self._previous is None:
            sys.modules.pop(BINDING_MODULE, None)
        else:
            sys.modules[BINDING_MODULE] = self._previous


class GenerationTest(FakeBindingTestCase):
    def test_available_when_binding_and_file_are_present(self) -> None:
        backend = LlamaCppLLMBackend(self.model_path)
        self.assertTrue(backend.is_available())

    def test_generate_returns_the_completion_text(self) -> None:
        backend = LlamaCppLLMBackend(self.model_path)
        self.assertEqual("a local completion", backend.generate("hello"))

    def test_options_are_passed_to_the_runtime(self) -> None:
        backend = LlamaCppLLMBackend(self.model_path)
        backend.generate(
            "hello",
            GenerationOptions(temperature=0.0, max_tokens=64, stop=("<end>",), seed=7),
        )
        _prompt, arguments = _FakeLlama.instances[0].completions[0]
        self.assertEqual(0.0, arguments["temperature"])
        self.assertEqual(64, arguments["max_tokens"])
        self.assertEqual(["<end>"], arguments["stop"])
        self.assertEqual(7, arguments["seed"])

    def test_repeat_penalty_is_omitted_when_unset(self) -> None:
        backend = LlamaCppLLMBackend(self.model_path)
        backend.generate("hello")
        _prompt, arguments = _FakeLlama.instances[0].completions[0]
        self.assertNotIn("repeat_penalty", arguments)

    def test_repeat_penalty_is_passed_when_set(self) -> None:
        backend = LlamaCppLLMBackend(self.model_path)
        backend.generate(
            "hello",
            GenerationOptions(repeat_penalty=1.15),
        )
        _prompt, arguments = _FakeLlama.instances[0].completions[0]
        self.assertEqual(1.15, arguments["repeat_penalty"])

    def test_seed_is_omitted_when_unset(self) -> None:
        backend = LlamaCppLLMBackend(self.model_path)
        backend.generate("hello")
        _prompt, arguments = _FakeLlama.instances[0].completions[0]
        self.assertNotIn("seed", arguments)

    def test_model_is_loaded_once_and_reused(self) -> None:
        backend = LlamaCppLLMBackend(self.model_path)
        backend.generate("first")
        backend.generate("second")
        self.assertEqual(1, len(_FakeLlama.instances))

    def test_unload_forces_a_reload(self) -> None:
        backend = LlamaCppLLMBackend(self.model_path)
        backend.generate("first")
        backend.unload()
        backend.generate("second")
        self.assertEqual(2, len(_FakeLlama.instances))

    def test_runtime_arguments_reflect_configuration(self) -> None:
        backend = LlamaCppLLMBackend(
            self.model_path, context_tokens=1024, gpu_layers=12, threads=6
        )
        backend.generate("hello")
        kwargs = _FakeLlama.instances[0].kwargs
        self.assertEqual(1024, kwargs["n_ctx"])
        self.assertEqual(12, kwargs["n_gpu_layers"])
        self.assertEqual(6, kwargs["n_threads"])
        self.assertFalse(kwargs["embedding"])

    def test_model_name_falls_back_to_the_file_name(self) -> None:
        self.assertEqual("model.gguf", LlamaCppLLMBackend(self.model_path).model_name)
        self.assertEqual(
            "labelled", LlamaCppLLMBackend(self.model_path, label="labelled").model_name
        )


class EmbeddingTest(FakeBindingTestCase):
    def test_embed_returns_one_vector_per_text(self) -> None:
        backend = LlamaCppEmbeddingBackend(self.model_path)
        vectors = backend.embed(["first", "second"])
        self.assertEqual(2, len(vectors))
        self.assertEqual((0.1, 0.2, 0.3), tuple(vectors[0]))

    def test_embedding_of_nothing_is_nothing(self) -> None:
        self.assertEqual((), LlamaCppEmbeddingBackend(self.model_path).embed([]))

    def test_dimension_comes_from_the_model(self) -> None:
        self.assertEqual(3, LlamaCppEmbeddingBackend(self.model_path).dimension)

    def test_embeddings_default_to_cpu(self) -> None:
        """ADR-0004 D7, an assumption to be measured at stage 4."""
        backend = LlamaCppEmbeddingBackend(self.model_path)
        backend.embed(["text"])
        kwargs = _FakeLlama.instances[0].kwargs
        self.assertEqual(0, kwargs["n_gpu_layers"])
        self.assertTrue(kwargs["embedding"])

    def test_token_level_embeddings_are_refused_rather_than_pooled(self) -> None:
        class _TokenLevelLlama(_FakeLlama):
            def create_embedding(self, inputs: list[str]) -> dict[str, object]:
                return {"data": [{"embedding": [[0.1, 0.2], [0.3, 0.4]]} for _ in inputs]}

        sys.modules[BINDING_MODULE].Llama = _TokenLevelLlama  # type: ignore[attr-defined]
        backend = LlamaCppEmbeddingBackend(self.model_path)
        with self.assertRaises(RuntimeError) as caught:
            backend.embed(["text"])
        self.assertIn("ADR-0005", str(caught.exception))


class UnavailableTest(unittest.TestCase):
    """Behaviour with no model file, and with no binding installed."""

    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.addCleanup(self._temp.cleanup)
        self.missing = Path(self._temp.name) / "absent.gguf"

    def test_missing_file_is_unavailable_without_raising(self) -> None:
        self.assertFalse(LlamaCppLLMBackend(self.missing).is_available())

    def test_unconfigured_path_is_unavailable_without_raising(self) -> None:
        self.assertFalse(LlamaCppLLMBackend(None).is_available())

    def test_unavailable_reason_names_model_path(self) -> None:
        reason = LlamaCppLLMBackend(None).unavailable_reason()
        self.assertIn("model_path", reason)
        missing = LlamaCppLLMBackend(self.missing).unavailable_reason()
        self.assertIn(str(self.missing), missing)

    def test_generate_without_a_model_file_raises_a_clear_error(self) -> None:
        with self.assertRaises(ModelFileMissingError) as caught:
            LlamaCppLLMBackend(self.missing).generate("hello")
        self.assertIn(str(self.missing), str(caught.exception))

    def test_generate_without_configuration_names_the_setting(self) -> None:
        with self.assertRaises(ModelFileMissingError) as caught:
            LlamaCppLLMBackend(None).generate("hello")
        self.assertIn("model_path", str(caught.exception))

    def test_missing_file_error_is_an_unavailability(self) -> None:
        self.assertTrue(issubclass(ModelFileMissingError, BackendUnavailableError))

    def test_astra_never_offers_to_install_or_download(self) -> None:
        with self.assertRaises(ModelFileMissingError) as caught:
            LlamaCppEmbeddingBackend(self.missing).embed(["text"])
        self.assertIn("does not download", str(caught.exception))


class FromSettingsTest(unittest.TestCase):
    def test_backends_are_built_from_reasoning_settings(self) -> None:
        settings = ReasoningSettings(
            model_path=Path("chat.gguf"),
            embedding_model_path=Path("embed.gguf"),
            model="chat-label",
            embedding_model="embed-label",
        )
        self.assertEqual("chat-label", LlamaCppLLMBackend.from_settings(settings).model_name)
        self.assertEqual(
            "embed-label", LlamaCppEmbeddingBackend.from_settings(settings).model_name
        )

    def test_unconfigured_settings_produce_unavailable_backends(self) -> None:
        settings = ReasoningSettings()
        self.assertFalse(LlamaCppLLMBackend.from_settings(settings).is_available())
        self.assertFalse(LlamaCppEmbeddingBackend.from_settings(settings).is_available())


class ContractTest(unittest.TestCase):
    def test_adapters_implement_the_existing_interfaces(self) -> None:
        from astra.reasoning import EmbeddingBackend, LLMBackend

        self.assertTrue(issubclass(LlamaCppLLMBackend, LLMBackend))
        self.assertTrue(issubclass(LlamaCppEmbeddingBackend, EmbeddingBackend))


if __name__ == "__main__":
    unittest.main()
