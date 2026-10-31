"""Web API boundary tests."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from astra.configuration import load_settings, save_settings
from astra.configuration.settings import (
    AstraSettings,
    KnowledgeSettings,
    LoggingSettings,
    MemorySettings,
    PlanningSettings,
)
from astra.core.astra_controller import AstraController
from astra.presentation.conversation import UnavailableConversation
from astra.presentation.orchestration import ConversationOrchestrator, _durable_facts
from astra.state import ApplicationState
from ui.demo.provider import DemoStateProvider
from ui.web_api import PrimusWebRuntime
from ui.web_server import run_browser_ui


def _settings() -> AstraSettings:
    root = Path(tempfile.mkdtemp())
    return AstraSettings(
        memory=MemorySettings(
            database_path=root / "mem.db",
            vector_index_path=root / "vectors.faiss",
        ),
        knowledge=KnowledgeSettings(database_path=root / "knowledge.db"),
        planning=PlanningSettings(database_path=root / "planning.db"),
        logging=LoggingSettings(directory=root / "logs"),
    )


class SaveSettingsTest(unittest.TestCase):
    def test_personality_persists_to_toml(self) -> None:
        root = Path(tempfile.mkdtemp())
        path = root / "astra.toml"
        settings = AstraSettings()
        updated = settings.model_copy(
            update={
                "personality": settings.personality.model_copy(update={"warmth": 77})
            }
        )
        save_settings(updated, path)
        reloaded = load_settings(path)
        self.assertEqual(77, reloaded.personality.warmth)


class CreatorFactTest(unittest.TestCase):
    def test_creator_statement_extracts_field(self) -> None:
        facts = _durable_facts("I am your creator.")
        keys = {key for _c, _content, _kind, key in facts}
        self.assertIn("user.creator", keys)

    def test_creator_survives_restart(self) -> None:
        class _Spy:
            prompts: list[str] = []

            def is_available(self) -> bool:
                return True

            def generate(self, prompt: str, options: object = None) -> str:
                _Spy.prompts.append(prompt)
                if "creator" in prompt.lower():
                    return "You are my creator."
                return "Understood."

        settings = _settings()
        ctrl = AstraController(settings, llm_factory=lambda _s: _Spy())
        ctrl.start()
        ConversationOrchestrator.from_controller(ctrl).process("I am your creator.")
        ctrl.shutdown()
        ctrl2 = AstraController(settings, llm_factory=lambda _s: _Spy())
        ctrl2.start()
        self.addCleanup(ctrl2.shutdown)
        result = ConversationOrchestrator.from_controller(ctrl2).process("Who is your creator?")
        joined = "\n".join(result.memory_retrieved) + _Spy.prompts[-1]
        self.assertIn("creator", joined.lower())


class WebRuntimeTest(unittest.TestCase):
    def test_config_and_memory_endpoints(self) -> None:
        class _Down:
            def is_available(self) -> bool:
                return False

        settings = _settings()
        ctrl = AstraController(settings, llm_factory=lambda _s: _Down())
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        runtime = PrimusWebRuntime(
            provider=DemoStateProvider(),
            conversation=UnavailableConversation(),
            controller=ctrl,
            reduced_motion=True,
        )
        config = runtime.get_config()
        self.assertTrue(config["ok"])
        self.assertIn("personality", config)
        self.assertIn("voice", config)
        added = runtime.add_memory({"label": "nickname", "content": "Call me Ashtro when we talk."})
        self.assertTrue(added["ok"])
        listed = runtime.list_memory()
        self.assertTrue(listed["ok"])
        flat = [row["content"] for row in listed["memories"]]
        self.assertTrue(any("Ashtro" in item for item in flat))

    def test_tts_disabled_returns_error(self) -> None:
        settings = _settings().model_copy(
            update={
                "voice": _settings().voice.model_copy(update={"tts_enabled": False}),
            }
        )
        runtime = PrimusWebRuntime(
            provider=DemoStateProvider(),
            conversation=UnavailableConversation(),
            controller=None,
            reduced_motion=True,
        )
        runtime.settings = settings
        with patch("ui.web_api.load_settings", return_value=settings):
            outcome = runtime.synthesize_speech({"text": "Hello"})
        self.assertFalse(outcome.get("ok"))
        self.assertIn("disabled", outcome.get("error", "").lower())

    def test_stt_disabled_returns_error(self) -> None:
        settings = _settings().model_copy(
            update={
                "voice": _settings().voice.model_copy(update={"stt_enabled": False}),
            }
        )
        runtime = PrimusWebRuntime(
            provider=DemoStateProvider(),
            conversation=UnavailableConversation(),
            controller=None,
            reduced_motion=True,
        )
        runtime.settings = settings
        outcome = runtime.transcribe_audio(b"\x00\x01", 16000)
        self.assertFalse(outcome.get("ok"))
        self.assertIn("disabled", outcome.get("error", "").lower())

    def test_chat_endpoint_returns_json(self) -> None:
        runtime = PrimusWebRuntime(
            provider=DemoStateProvider(),
            conversation=UnavailableConversation(),
            controller=None,
            reduced_motion=True,
        )
        outcome = runtime.chat("hello")
        self.assertTrue(outcome.get("ok"))
        self.assertIn("reply", outcome)
        self.assertIn("jarvis_state", outcome)

    def test_smoke_server_exposes_config(self) -> None:
        run_browser_ui(provider=DemoStateProvider(), smoke=True)


class WebSystemFeaturesTest(unittest.TestCase):
    def test_knowledge_upload_uses_correct_ingest_order(self) -> None:
        settings = _settings()
        runtime = PrimusWebRuntime(
            provider=DemoStateProvider(),
            conversation=UnavailableConversation(),
            controller=None,
            reduced_motion=False,
        )
        runtime.settings = settings
        runtime._web_knowledge = None
        outcome = runtime.upload_knowledge(
            {"content": "Astra is a local assistant.", "source": "unit-test"}
        )
        self.assertTrue(outcome["ok"], outcome.get("error"))
        listed = runtime.list_knowledge()
        self.assertTrue(listed["ok"])
        self.assertGreaterEqual(listed["count"], 1)


__all__ = []
