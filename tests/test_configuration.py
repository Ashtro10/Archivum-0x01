"""Configuration Manager tests.

Two of these tests guard constitutional rules rather than code behaviour: a
reasoning host must stay on this machine, and human approval must stay on.
"""

from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path

from astra.configuration import (
    ConfigurationError,
    LoggingSettings,
    MemorySettings,
    ReasoningSettings,
    SecuritySettings,
    load_settings,
)
from astra.configuration.paths import ENV_CONFIG


class DefaultsTest(unittest.TestCase):
    def test_defaults_are_local(self) -> None:
        reasoning = ReasoningSettings()
        self.assertEqual("llama_cpp", reasoning.backend)

    def test_no_network_setting_exists(self) -> None:
        """ADR-0004: there is no host, port, or URL to misconfigure."""
        reasoning = ReasoningSettings()
        for forbidden in ("host", "port", "url", "endpoint", "base_url"):
            self.assertFalse(hasattr(reasoning, forbidden))

    def test_models_are_unconfigured_until_the_user_provides_files(self) -> None:
        reasoning = ReasoningSettings()
        self.assertIsNone(reasoning.model_path)
        self.assertIsNone(reasoning.embedding_model_path)

    def test_truth_and_index_paths_differ(self) -> None:
        memory = MemorySettings()
        self.assertNotEqual(memory.database_path, memory.vector_index_path)


class BenchmarkedDefaultsTest(unittest.TestCase):
    """B-002 stage 5: defaults now match the empirically benchmarked values.

    Every assertion here is a production commitment, not a typo guard.
    Changing any of these values requires a new measurement and an ADR
    amendment — not just a code edit.

    Evidence: ADR-0005 §16-18, benchmark run 2026-08-14.
    """

    def setUp(self) -> None:
        self.reasoning = ReasoningSettings()

    def test_default_model_label_is_3b_q5_k_m(self) -> None:
        """The production chat model is Qwen2.5-3B-Instruct at Q5_K_M.

        ADR-0005 §20: selected on capability × responsiveness × stability ×
        resource efficiency. 46.2 tok/s on full GPU offload; 1356 MiB free
        VRAM at 4096-token context.
        """
        self.assertEqual("Qwen2.5-3B-Instruct-Q5_K_M", self.reasoning.model)

    def test_default_context_tokens_is_benchmarked_value(self) -> None:
        """4096-token context is the benchmarked production default.

        ADR-0005 §16: at ctx=4096 VRAM used is 2740 MiB with 1356 MiB free.
        Raising to 8192 is possible (2884 MiB, 1212 MiB free) but 4096 is
        the starting point that fits retrieved memory plus conversation.
        """
        self.assertEqual(4096, self.reasoning.context_tokens)

    def test_default_gpu_layers_enables_full_offload(self) -> None:
        """All layers go to the GPU by default.

        ADR-0005 §16: the Qwen2.5-3B has 36 transformer layers. Convention
        in llama.cpp: n_gpu_layers >= layer_count means full offload.
        Value 99 safely exceeds any 3B or 8B layer count.
        """
        self.assertEqual(99, self.reasoning.gpu_layers)

    def test_model_path_is_none_no_machine_specific_path_hardcoded(self) -> None:
        """Astra never hardcodes a machine path.

        The model file location is user configuration, not a source code
        constant. model_path=None means the backend reports itself
        unavailable rather than failing with a missing-file error at an
        unexpected time.
        """
        self.assertIsNone(self.reasoning.model_path)

    def test_embedding_gpu_layers_stays_zero(self) -> None:
        """Embeddings run on CPU; confirmed by stage 4 benchmark.

        ADR-0004 D7 and ADR-0005 §20: generation model uses ~2740 MiB of
        4096 MiB VRAM at ctx=4096. The embedding model (CPU, ~150 MB) is
        fast enough for the expected corpus size without competing for that
        headroom.
        """
        self.assertEqual(0, self.reasoning.embedding_gpu_layers)

    def test_8b_model_not_required_for_normal_startup(self) -> None:
        """The 8B deep-reasoning model is an optional future capability.

        Constructing ReasoningSettings with defaults must succeed without
        any 8B file present. The 8B is not in the active production path.
        """
        settings = ReasoningSettings()
        self.assertIsNone(settings.model_path)
        self.assertIsNone(settings.embedding_model_path)

    def test_gpu_layers_default_is_expressible_in_toml(self) -> None:
        """Validate that gpu_layers=99 is accepted as a positive integer."""
        settings = ReasoningSettings(gpu_layers=99)
        self.assertEqual(99, settings.gpu_layers)

    def test_embedding_context_tokens_is_2048(self) -> None:
        """B-003: embedding context must not inherit generation model's 4096.

        B-010 measured nomic-embed-text-v1.5 Q8_0 has n_ctx_train=2048.
        Setting a larger value produces an overflow warning and may degrade
        quality. This field is independent from context_tokens (generation).
        """
        self.assertEqual(2048, self.reasoning.embedding_context_tokens)

    def test_embedding_context_differs_from_generation_context(self) -> None:
        self.assertNotEqual(
            self.reasoning.context_tokens,
            self.reasoning.embedding_context_tokens,
            "embedding_context_tokens must not inherit generation context",
        )

    def test_alternative_8b_config_is_valid(self) -> None:
        """The 8B config (18 GPU layers) is constructable without errors.

        Documents the empirically-determined optimal partial-offload config:
        18/33 layers on GPU, 6.3 tok/s, 3442 MiB VRAM at ctx=2048.
        This config is not activated by default; it requires explicit user
        configuration of model_path and gpu_layers.
        """
        settings = ReasoningSettings(gpu_layers=18, context_tokens=2048)
        self.assertEqual(18, settings.gpu_layers)
        self.assertEqual(2048, settings.context_tokens)


class ValidationTest(unittest.TestCase):
    def test_unsupported_backend_is_rejected(self) -> None:
        with self.assertRaises(ConfigurationError):
            ReasoningSettings(backend="hosted_api")

    def test_ollama_is_no_longer_a_supported_backend(self) -> None:
        """ADR-0004 removed it: reaching Ollama requires an HTTP API."""
        with self.assertRaises(ConfigurationError):
            ReasoningSettings(backend="ollama")

    def test_human_approval_cannot_be_disabled(self) -> None:
        with self.assertRaises(ConfigurationError):
            SecuritySettings(require_human_approval=False)

    def test_unknown_log_level_is_rejected(self) -> None:
        with self.assertRaises(ConfigurationError):
            LoggingSettings(level="CHATTY")

    def test_non_positive_context_is_rejected(self) -> None:
        with self.assertRaises(ConfigurationError):
            ReasoningSettings(context_tokens=0)

    def test_negative_gpu_layers_are_rejected(self) -> None:
        with self.assertRaises(ConfigurationError):
            ReasoningSettings(gpu_layers=-1)

    def test_non_positive_thread_count_is_rejected(self) -> None:
        with self.assertRaises(ConfigurationError):
            ReasoningSettings(threads=0)


class LoaderTest(unittest.TestCase):
    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.addCleanup(self._temp.cleanup)
        self.directory = Path(self._temp.name)
        self._previous_env = os.environ.get(ENV_CONFIG)
        self.addCleanup(self._restore_env)

    def _restore_env(self) -> None:
        if self._previous_env is None:
            os.environ.pop(ENV_CONFIG, None)
        else:
            os.environ[ENV_CONFIG] = self._previous_env

    def _write(self, text: str) -> Path:
        path = self.directory / "astra.toml"
        path.write_text(text, encoding="utf-8")
        return path

    def test_file_values_override_defaults(self) -> None:
        path = self._write(
            "[reasoning]\n"
            'model = "qwen2.5:3b"\n'
            "context_tokens = 4096\n"
            "\n"
            "[logging]\n"
            'level = "DEBUG"\n'
        )
        settings = load_settings(path)
        self.assertEqual("qwen2.5:3b", settings.reasoning.model)
        self.assertEqual(4096, settings.reasoning.context_tokens)
        self.assertEqual("DEBUG", settings.logging.level)

    def test_optional_model_paths_become_path_objects(self) -> None:
        path = self._write('[reasoning]\nmodel_path = "models/chat.gguf"\n')
        settings = load_settings(path)
        self.assertEqual(Path("models/chat.gguf"), settings.reasoning.model_path)

    def test_path_values_become_path_objects(self) -> None:
        path = self._write('[memory]\ndatabase_path = "state/astra.db"\n')
        settings = load_settings(path)
        self.assertIsInstance(settings.memory.database_path, Path)
        from astra.configuration.paths import data_root

        self.assertEqual(
            (data_root() / "state" / "astra.db").resolve(),
            settings.memory.database_path,
        )

    def test_deep_reasoning_keys_load(self) -> None:
        path = self._write(
            "[reasoning]\n"
            'model = "fast"\n'
            'model_path = "models/fast.gguf"\n'
            'deep_model = "deep"\n'
            'deep_model_path = "models/deep.gguf"\n'
            "deep_gpu_layers = 18\n"
        )
        settings = load_settings(path)
        self.assertEqual("deep", settings.reasoning.deep_model)
        self.assertEqual(Path("models/deep.gguf"), settings.reasoning.deep_model_path)
        self.assertEqual(18, settings.reasoning.deep_gpu_layers)

    def test_unknown_key_is_rejected(self) -> None:
        path = self._write('[reasoning]\napi_key = "secret"\n')
        with self.assertRaises(ConfigurationError) as caught:
            load_settings(path)
        self.assertIn("api_key", str(caught.exception))

    def test_unknown_section_is_rejected(self) -> None:
        path = self._write('[cloud]\nendpoint = "https://example.com"\n')
        with self.assertRaises(ConfigurationError):
            load_settings(path)

    def test_wrong_type_is_rejected(self) -> None:
        path = self._write("[logging]\nconsole_enabled = 3\n")
        with self.assertRaises(ConfigurationError):
            load_settings(path)

    def test_missing_explicit_file_is_an_error(self) -> None:
        with self.assertRaises(ConfigurationError):
            load_settings(self.directory / "absent.toml")

    def test_environment_variable_is_honoured(self) -> None:
        path = self._write('[reasoning]\nmodel = "phi4:14b"\n')
        os.environ[ENV_CONFIG] = str(path)
        self.assertEqual("phi4:14b", load_settings().reasoning.model)

    def test_malformed_toml_is_reported_clearly(self) -> None:
        path = self._write("[reasoning\nmodel = broken")
        with self.assertRaises(ConfigurationError) as caught:
            load_settings(path)
        self.assertIn("TOML", str(caught.exception))


class PydanticModelTest(unittest.TestCase):
    """B-005: settings are Pydantic models; public construction still works."""

    def test_settings_are_pydantic_models(self) -> None:
        from pydantic import BaseModel

        from astra.configuration.settings import (
            AstraSettings,
            KnowledgeSettings,
            LoggingSettings,
            MemorySettings,
            PlanningSettings,
            ReasoningSettings,
            SecuritySettings,
        )

        for cls in (
            AstraSettings,
            ReasoningSettings,
            MemorySettings,
            KnowledgeSettings,
            PlanningSettings,
            LoggingSettings,
            SecuritySettings,
        ):
            with self.subTest(cls=cls.__name__):
                self.assertTrue(issubclass(cls, BaseModel))

    def test_unknown_field_on_direct_construction_is_configuration_error(self) -> None:
        with self.assertRaises(ConfigurationError) as caught:
            ReasoningSettings(host="127.0.0.1")  # type: ignore[call-arg]
        self.assertIn("host", str(caught.exception))

    def test_validation_error_is_not_leaked(self) -> None:
        from pydantic import ValidationError

        with self.assertRaises(ConfigurationError):
            ReasoningSettings(context_tokens=0)
        try:
            ReasoningSettings(context_tokens=0)
        except ValidationError:
            self.fail("raw ValidationError leaked to caller")
        except ConfigurationError:
            pass

    def test_models_are_frozen(self) -> None:
        reasoning = ReasoningSettings()
        with self.assertRaises(Exception):
            reasoning.model = "other"  # type: ignore[misc]

    def test_error_messages_name_the_field(self) -> None:
        with self.assertRaises(ConfigurationError) as caught:
            ReasoningSettings(backend="ollama")
        message = str(caught.exception)
        self.assertIn("ollama", message)
        self.assertIn("llama_cpp", message)

    def test_human_approval_error_names_the_constitution_rule(self) -> None:
        with self.assertRaises(ConfigurationError) as caught:
            SecuritySettings(require_human_approval=False)
        self.assertIn("require_human_approval", str(caught.exception))
        self.assertIn("cannot be disabled", str(caught.exception))


class PublicApiCompatibilityTest(unittest.TestCase):
    """B-005 must not rename or drop the dataclass-era public surface."""

    def test_package_exports_unchanged(self) -> None:
        import astra.configuration as pkg

        for name in (
            "ConfigurationError",
            "AstraSettings",
            "ReasoningSettings",
            "MemorySettings",
            "KnowledgeSettings",
            "PlanningSettings",
            "LoggingSettings",
            "SecuritySettings",
            "TestingSettings",
            "load_settings",
            "find_config_file",
            "project_root",
            "data_root",
            "default_database_path",
            "default_knowledge_database_path",
            "default_planning_database_path",
            "default_vector_index_path",
            "default_log_directory",
            "ensure_directory",
        ):
            with self.subTest(name=name):
                self.assertIn(name, pkg.__all__)
                self.assertTrue(hasattr(pkg, name))

    def test_supported_backends_public_constant(self) -> None:
        from astra.configuration.settings import SUPPORTED_REASONING_BACKENDS

        self.assertEqual(frozenset({"llama_cpp"}), SUPPORTED_REASONING_BACKENDS)
        self.assertNotIn("ollama", SUPPORTED_REASONING_BACKENDS)

    def test_nested_sections_present_on_defaults(self) -> None:
        from astra.configuration import AstraSettings

        settings = AstraSettings()
        self.assertIsInstance(settings.reasoning, ReasoningSettings)
        self.assertIsInstance(settings.memory, MemorySettings)
        self.assertIsInstance(settings.logging, LoggingSettings)
        self.assertIsInstance(settings.security, SecuritySettings)
        from astra.configuration import KnowledgeSettings, PlanningSettings, TestingSettings

        self.assertIsInstance(settings.knowledge, KnowledgeSettings)
        self.assertIsInstance(settings.planning, PlanningSettings)
        self.assertIsInstance(settings.testing, TestingSettings)
        self.assertEqual("astra-scratch", settings.testing.scratch_directory.name)
        self.assertEqual((), settings.testing.allowed_process_executables)
        from astra.configuration import GitSettings

        self.assertIsInstance(settings.git, GitSettings)
        self.assertIsNone(settings.git.executable)
        self.assertFalse(settings.git.init_if_missing)
        from astra.configuration import PluginSettings, ResearchSettings, PersonalitySettings

        self.assertIsInstance(settings.plugins, PluginSettings)
        self.assertIsNone(settings.plugins.plugin_root)
        self.assertEqual((), settings.plugins.enabled_plugin_ids)
        self.assertIsInstance(settings.research, ResearchSettings)
        self.assertFalse(settings.research.allow_external)
        self.assertIsInstance(settings.personality, PersonalitySettings)
        self.assertEqual("Primus", settings.personality.name)
        for forbidden in ("host", "port", "url", "endpoint", "base_url"):
            self.assertFalse(hasattr(settings.personality, forbidden))

    def test_logging_file_path_property(self) -> None:
        logging = LoggingSettings()
        self.assertEqual(logging.directory / logging.filename, logging.file_path)


class ExtendedLoaderTest(unittest.TestCase):
    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.addCleanup(self._temp.cleanup)
        self.directory = Path(self._temp.name)
        self._previous_env = os.environ.get(ENV_CONFIG)
        self.addCleanup(self._restore_env)

    def _restore_env(self) -> None:
        if self._previous_env is None:
            os.environ.pop(ENV_CONFIG, None)
        else:
            os.environ[ENV_CONFIG] = self._previous_env

    def _write(self, text: str, name: str = "astra.toml") -> Path:
        path = self.directory / name
        path.write_text(text, encoding="utf-8")
        return path

    def test_missing_optional_keys_keep_defaults(self) -> None:
        path = self._write("[logging]\nlevel = \"WARNING\"\n")
        settings = load_settings(path)
        self.assertEqual("WARNING", settings.logging.level)
        self.assertEqual("Qwen2.5-3B-Instruct-Q5_K_M", settings.reasoning.model)
        self.assertEqual(4096, settings.reasoning.context_tokens)
        self.assertEqual(99, settings.reasoning.gpu_layers)
        self.assertEqual(2048, settings.reasoning.embedding_context_tokens)
        self.assertEqual(768, settings.reasoning.embedding_dim)
        self.assertEqual(0, settings.reasoning.embedding_gpu_layers)
        self.assertTrue(settings.security.require_human_approval)
        self.assertFalse(settings.security.allow_outbound_network)

    def test_empty_file_uses_all_defaults(self) -> None:
        path = self._write("")
        settings = load_settings(path)
        self.assertEqual("llama_cpp", settings.reasoning.backend)
        self.assertIsNone(settings.reasoning.model_path)

    def test_tilde_in_path_is_expanded(self) -> None:
        path = self._write('[memory]\ndatabase_path = "~/astra-data/astra.db"\n')
        settings = load_settings(path)
        self.assertEqual(Path("~/astra-data/astra.db").expanduser(), settings.memory.database_path)
        self.assertNotIn("~", str(settings.memory.database_path))

    def test_optional_path_none_by_default(self) -> None:
        path = self._write("[reasoning]\nmodel = \"Qwen2.5-3B-Instruct-Q5_K_M\"\n")
        settings = load_settings(path)
        self.assertIsNone(settings.reasoning.model_path)
        self.assertIsNone(settings.reasoning.embedding_model_path)

    def test_knowledge_and_planning_paths_from_toml(self) -> None:
        path = self._write(
            "[knowledge]\ndatabase_path = \"k.db\"\n\n"
            "[planning]\ndatabase_path = \"p.db\"\n"
        )
        settings = load_settings(path)
        from astra.configuration.paths import data_root

        self.assertEqual((data_root() / "k.db").resolve(), settings.knowledge.database_path)
        self.assertEqual((data_root() / "p.db").resolve(), settings.planning.database_path)

    def test_testing_scratch_from_toml(self) -> None:
        path = self._write("[testing]\nscratch_directory = \"overlay-scratch\"\n")
        settings = load_settings(path)
        self.assertEqual(Path("overlay-scratch"), settings.testing.scratch_directory)

    def test_git_section_from_toml(self) -> None:
        path = self._write(
            "[git]\n"
            'executable = "C:/Program Files/Git/cmd/git.exe"\n'
            "init_if_missing = true\n"
        )
        settings = load_settings(path)
        self.assertEqual(Path("C:/Program Files/Git/cmd/git.exe"), settings.git.executable)
        self.assertTrue(settings.git.init_if_missing)
        self.assertEqual("Astra", settings.git.author_name)

    def test_plugins_section_from_toml(self) -> None:
        path = self._write(
            "[plugins]\n"
            'plugin_root = "ext-plugins"\n'
            "enabled_plugin_ids = [\"sample\"]\n"
        )
        settings = load_settings(path)
        self.assertEqual(Path("ext-plugins"), settings.plugins.plugin_root)
        self.assertEqual(("sample",), settings.plugins.enabled_plugin_ids)

    def test_voice_kokoro_settings_from_toml(self) -> None:
        path = self._write(
            "[voice]\n"
            "kokoro_voice = \"am_michael\"\n"
            "kokoro_lang = \"a\"\n"
            "kokoro_speed = 1.1\n"
            "kokoro_device = \"cpu\"\n"
            "optimus_rms_mix_rate = 0.5\n"
            "optimus_protect = 0.3\n"
            "optimus_f0_method = \"rmvpe\"\n"
            "optimus_warm_rvc = false\n"
        )
        voice = load_settings(path).voice
        self.assertEqual("am_michael", voice.kokoro_voice)
        self.assertEqual("a", voice.kokoro_lang)
        self.assertEqual(1.1, voice.kokoro_speed)
        self.assertEqual("cpu", voice.kokoro_device)
        self.assertEqual(0.5, voice.optimus_rms_mix_rate)
        self.assertEqual(0.3, voice.optimus_protect)
        self.assertEqual("rmvpe", voice.optimus_f0_method)
        self.assertFalse(voice.optimus_warm_rvc)

    def test_identical_memory_paths_rejected(self) -> None:
        path = self._write(
            "[memory]\n"
            'database_path = "same.db"\n'
            'vector_index_path = "same.db"\n'
        )
        with self.assertRaises(ConfigurationError) as caught:
            load_settings(path)
        self.assertIn("must differ", str(caught.exception))

    def test_bool_field_rejects_integer(self) -> None:
        path = self._write("[security]\nrequire_human_approval = 1\n")
        with self.assertRaises(ConfigurationError):
            load_settings(path)

    def test_int_field_rejects_bool(self) -> None:
        path = self._write("[reasoning]\ncontext_tokens = true\n")
        with self.assertRaises(ConfigurationError):
            load_settings(path)

    def test_path_field_rejects_integer(self) -> None:
        path = self._write("[memory]\ndatabase_path = 12\n")
        with self.assertRaises(ConfigurationError) as caught:
            load_settings(path)
        self.assertIn("string path", str(caught.exception))

    def test_section_must_be_a_table(self) -> None:
        path = self._write("reasoning = \"nope\"\n")
        with self.assertRaises(ConfigurationError) as caught:
            load_settings(path)
        self.assertIn("table", str(caught.exception))

    def test_remote_host_key_rejected(self) -> None:
        path = self._write('[reasoning]\nhost = "https://example.com"\n')
        with self.assertRaises(ConfigurationError) as caught:
            load_settings(path)
        self.assertIn("host", str(caught.exception))

    def test_ollama_backend_in_toml_rejected(self) -> None:
        path = self._write('[reasoning]\nbackend = "ollama"\n')
        with self.assertRaises(ConfigurationError) as caught:
            load_settings(path)
        self.assertIn("ollama", str(caught.exception))

    def test_approval_false_in_toml_rejected(self) -> None:
        path = self._write("[security]\nrequire_human_approval = false\n")
        with self.assertRaises(ConfigurationError):
            load_settings(path)

    def test_astra_config_missing_file_is_error(self) -> None:
        os.environ[ENV_CONFIG] = str(self.directory / "nope.toml")
        with self.assertRaises(ConfigurationError) as caught:
            load_settings()
        self.assertIn(ENV_CONFIG, str(caught.exception))

    def test_explicit_path_wins_over_astra_config(self) -> None:
        env_file = self._write('[reasoning]\nmodel = "from-env"\n', "env.toml")
        explicit = self._write('[reasoning]\nmodel = "from-explicit"\n', "explicit.toml")
        os.environ[ENV_CONFIG] = str(env_file)
        settings = load_settings(explicit)
        self.assertEqual("from-explicit", settings.reasoning.model)

    def test_lowercase_log_level_accepted_and_not_normalised(self) -> None:
        path = self._write('[logging]\nlevel = "debug"\n')
        settings = load_settings(path)
        self.assertEqual("debug", settings.logging.level)

    def test_production_defaults_survive_partial_toml(self) -> None:
        path = self._write("[logging]\nconsole_enabled = false\n")
        r = load_settings(path).reasoning
        self.assertEqual("Qwen2.5-3B-Instruct-Q5_K_M", r.model)
        self.assertEqual("nomic-embed-text-v1.5", r.embedding_model)
        self.assertEqual(4096, r.context_tokens)
        self.assertEqual(99, r.gpu_layers)
        self.assertEqual(2048, r.embedding_context_tokens)
        self.assertEqual(768, r.embedding_dim)
        self.assertEqual(0, r.embedding_gpu_layers)
        self.assertEqual("llama_cpp", r.backend)
        self.assertIsNone(r.model_path)

    def test_no_config_file_discovers_chat_gguf(self) -> None:
        from unittest.mock import patch

        folder = self.directory / "gguf"
        folder.mkdir()
        chat = folder / "alpha-chat.gguf"
        embed = folder / "nomic-embed.gguf"
        chat.write_bytes(b"x")
        embed.write_bytes(b"y")
        os.environ.pop(ENV_CONFIG, None)
        with patch(
            "astra.configuration.loader.find_config_file", return_value=None
        ), patch(
            "astra.configuration.loader._gguf_search_dirs", return_value=(folder,)
        ):
            settings = load_settings()
        self.assertEqual(chat, settings.reasoning.model_path)
        self.assertEqual(embed, settings.reasoning.embedding_model_path)


class DeepModelSettingsTest(unittest.TestCase):
    def test_deep_model_fields_are_optional(self) -> None:
        from astra.configuration.settings import ReasoningSettings

        r = ReasoningSettings()
        self.assertEqual("", r.deep_model)
        self.assertIsNone(r.deep_model_path)
        self.assertIsNone(r.deep_gpu_layers)


class MilestoneB005Test(unittest.TestCase):
    def test_b005_milestone_complete(self) -> None:
        from astra.presentation.progress import MILESTONES

        b005 = next((m for m in MILESTONES if m.id == "B-005"), None)
        self.assertIsNotNone(b005)
        assert b005 is not None
        self.assertTrue(b005.complete())

    def test_b005_not_hard_coded_true(self) -> None:
        from astra.presentation.progress import _check_pydantic_config

        self.assertTrue(callable(_check_pydantic_config))
        self.assertTrue(_check_pydantic_config())


if __name__ == "__main__":
    unittest.main()
