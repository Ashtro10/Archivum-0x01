"""Conversation-first UI and Primus Core tests. No Dear PyGui window."""

from __future__ import annotations

import ast
import unittest
from pathlib import Path

from astra.presentation.conversation import (
    AssistantConversation,
    ConversationReply,
    UnavailableConversation,
)
from astra.product import CORE_NAME, GREETING, PRODUCT_NAME

_ROOT = Path(__file__).resolve().parents[1]
from astra.state import ApplicationState, CapabilityStatus, ModuleStatus
from ui.audio.analysis import AudioAnalyzer, SILENT
from ui.conversation.store import ConversationStore
from ui.visual.manager import VisualStateManager
from ui.visual.states import VisualState


class ProductIdentityTest(unittest.TestCase):
    def test_product_name_is_primus(self) -> None:
        self.assertEqual("Primus", PRODUCT_NAME)
        self.assertEqual("Primus Core", CORE_NAME)
        self.assertIn("Primus", GREETING)
        self.assertNotIn("Astra", GREETING)

    def test_header_uses_product_name(self) -> None:
        text = (_ROOT / "ui" / "components" / "header.py").read_text(encoding="utf-8")
        self.assertIn("PRODUCT_NAME", text)
        self.assertNotIn('"ASTRA"', text)


class VisualStateTest(unittest.TestCase):
    def test_operational_modules_are_idle(self) -> None:
        manager = VisualStateManager()
        state = ApplicationState(
            modules=(
                ModuleStatus(
                    module="astra.memory",
                    status=CapabilityStatus.OPERATIONAL,
                    capabilities=(),
                ),
            )
        )
        self.assertEqual(VisualState.IDLE, manager.resolve(state))

    def test_interaction_overrides_without_timer(self) -> None:
        manager = VisualStateManager()
        manager.set_interaction(VisualState.RETRIEVING)
        self.assertEqual(VisualState.RETRIEVING, manager.resolve(ApplicationState()))

    def test_missing_modules_are_error(self) -> None:
        self.assertEqual(VisualState.ERROR, VisualStateManager().resolve(ApplicationState()))


class JarvisAssetsTest(unittest.TestCase):
    def test_assets_present(self) -> None:
        from ui.visual.jarvis_host import jarvis_assets_dir

        root = jarvis_assets_dir()
        self.assertTrue((root / "index.html").is_file(), root)
        self.assertTrue((root / "app.js").is_file(), root)
        self.assertTrue((root / "three.min.js").is_file(), root)

    def test_visual_state_maps_to_jarvis(self) -> None:
        from ui.visual.jarvis_host import map_visual_state

        self.assertEqual("LISTENING", map_visual_state(VisualState.LISTENING))
        self.assertEqual("THINKING", map_visual_state(VisualState.PROCESSING))
        self.assertEqual("SPEAKING", map_visual_state(VisualState.SPEAKING))
        self.assertEqual("IDLE", map_visual_state(VisualState.SUCCESS))


class BrowserUiTest(unittest.TestCase):
    def test_web_shell_present(self) -> None:
        root = _ROOT / "ui" / "web"
        self.assertTrue((root / "primus.html").is_file())
        self.assertTrue((root / "chat.js").is_file())
        self.assertTrue((root / "chat.css").is_file())

    def test_smoke_server(self) -> None:
        from ui.demo.provider import DemoStateProvider
        from ui.web_server import run_browser_ui

        run_browser_ui(provider=DemoStateProvider(), smoke=True)


class ConversationStoreTest(unittest.TestCase):
    def test_status_is_not_a_message(self) -> None:
        store = ConversationStore()
        store.greet(GREETING)
        store.add("human", "hello")
        self.assertEqual(2, len(store.messages))
        self.assertTrue(all(m.speaker in {"human", "primus"} for m in store.messages))

    def test_greet_once(self) -> None:
        store = ConversationStore()
        store.greet(GREETING)
        store.greet(GREETING)
        self.assertEqual(1, len(store.messages))


class AudioAnalysisTest(unittest.TestCase):
    def test_empty_is_silent(self) -> None:
        self.assertTrue(AudioAnalyzer().consume(()).silent)

    def test_real_pcm_is_not_invented(self) -> None:
        features = AudioAnalyzer().consume([0.0, 0.4, -0.4, 0.2])
        self.assertFalse(features.silent)
        self.assertGreater(features.amplitude, 0.0)
        self.assertEqual(4, len(features.bands))

    def test_close_stops_analysis(self) -> None:
        analyzer = AudioAnalyzer()
        analyzer.close()
        self.assertTrue(analyzer.consume([1.0, 1.0]).silent)


class RefreshDoesNotInjectMessagesTest(unittest.TestCase):
    def test_chat_api_does_not_fake_refresh_messages(self) -> None:
        source = (_ROOT / "ui" / "web_server.py").read_text(encoding="utf-8")
        self.assertIn("PrimusWebRuntime", source)
        self.assertNotIn("Refreshing", source)
        self.assertNotIn("Last refresh", source)


class ConversationPortTest(unittest.TestCase):
    def test_unavailable_is_honest(self) -> None:
        reply = UnavailableConversation().ask("hello")
        self.assertIsInstance(reply, ConversationReply)
        self.assertEqual("warning", reply.status)
        self.assertIn(PRODUCT_NAME, reply.text)

    def test_assistant_uses_real_llm_generate(self) -> None:
        class _Fake:
            def is_available(self) -> bool:
                return True

            def generate(self, prompt: str, options: object = None) -> str:
                self.prompt = prompt
                return "Paris is the capital of France."

        llm = _Fake()
        reply = AssistantConversation(llm).ask("What is the capital of France?")
        self.assertEqual("success", reply.status)
        self.assertIn("Paris", reply.text)
        self.assertIn("capital of France", llm.prompt)
        self.assertNotIn("nothing to summarise", reply.text.lower())

    def test_assistant_is_honest_when_model_missing(self) -> None:
        class _Down:
            def is_available(self) -> bool:
                return False

            def generate(self, prompt: str, options: object = None) -> str:
                raise AssertionError("must not generate")

        reply = AssistantConversation(_Down()).ask("Hello")
        self.assertEqual("error", reply.status)
        self.assertIn("language model", reply.text)
        self.assertNotIn("summar", reply.text.lower())

    def test_hello_does_not_route_to_research_summary(self) -> None:
        text = (_ROOT / "astra" / "boot.py").read_text(encoding="utf-8")
        self.assertIn("ConversationOrchestrator", text)
        self.assertNotIn("nothing it can stand behind", text)

    def test_missing_model_explains_path(self) -> None:
        class _Down:
            def is_available(self) -> bool:
                return False

            def unavailable_reason(self) -> str:
                return "No model file configured."

            def generate(self, prompt: str, options: object = None) -> str:
                raise AssertionError("must not generate")

        reply = AssistantConversation(_Down()).ask("Hello")
        self.assertIn("language model", reply.text)
        self.assertIn("No model file configured", reply.text)

    def test_chat_prompt_uses_qwen_chatml(self) -> None:
        captured: dict[str, object] = {}

        class _Fake:
            def is_available(self) -> bool:
                return True

            def generate(self, prompt: str, options: object = None) -> str:
                captured["prompt"] = prompt
                captured["options"] = options
                return "Hello. Hello. Hello."

        reply = AssistantConversation(_Fake()).ask("Hi")
        prompt = str(captured["prompt"])
        self.assertIn("<|im_start|>user", prompt)
        self.assertIn("Hi", prompt)
        self.assertIn("<|im_start|>assistant", prompt)
        self.assertNotIn("USER:", prompt)
        options = captured["options"]
        self.assertIn("<|im_end|>", options.stop)
        self.assertEqual(1.15, options.repeat_penalty)
        self.assertEqual("Hello.", reply.text)


class NoFakeProcessingTimerTest(unittest.TestCase):
    def test_no_setinterval_processing(self) -> None:
        root = _ROOT / "ui"
        for path in root.rglob("*.py"):
            text = path.read_text(encoding="utf-8")
            self.assertNotIn("animateProcessing", text, path.name)
            self.assertNotIn("setInterval", text, path.name)


class TronChromeTest(unittest.TestCase):
    def test_browser_shell_uses_primus_branding(self) -> None:
        html = (_ROOT / "ui" / "web" / "primus.html").read_text(encoding="utf-8")
        css = (_ROOT / "ui" / "web" / "chat.css").read_text(encoding="utf-8")
        self.assertIn("Primus", html)
        self.assertIn("LOCAL SESSION", html)
        self.assertIn("jarvis-bg", html)
        self.assertIn("Talk", html)
        self.assertIn("System", html)
        self.assertIn("#22e9ff", css)
        self.assertNotIn("ENCOM", html)
