"""Read-only local perception. Mocked capture. No real desktop. No network."""

from __future__ import annotations

import ast
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from astra.core.agent_loop import (
    AgentState,
    is_execution_goal,
    wants_perception,
    wants_screen_capture,
    wants_window_inspect,
)
from astra.reasoning.vision import (
    VISION_UNAVAILABLE,
    UnavailableVisionBackend,
    vision_from_settings,
)
from astra.security.agent import PolicySecurityAgent
from astra.security.contracts import Verdict
from astra.tools.allowlist import AGENT_EXECUTION_ALLOWLIST, PRODUCTION_EXECUTION_ALLOWLIST
from astra.tools.capture_screen import CaptureScreenTool
from astra.tools.contracts import InvocationStatus, ToolInvocationRequest
from astra.tools.gate import GatedToolInvoker
from astra.tools.guard import GATE_TOKEN
from astra.tools.inspect_active_window import InspectActiveWindowTool
from astra.tools.registry import InMemoryToolRegistry
from tests.test_agent_loop import FakeLLM, FakeTools, _loop


def _ok_capture(max_width: int, max_height: int) -> dict[str, object]:
    return {
        "available": True,
        "width": min(1920, max_width),
        "height": min(1080, max_height),
        "source_width": 1920,
        "source_height": 1080,
        "max_width": max_width,
        "max_height": max_height,
        "monitor": 1,
        "monitors": 2,
        "timestamp": "2026-08-30T16:35:00+00:00",
        "image": b"\x89PNG\r\n",
    }


class AllowlistBoundaryTest(unittest.TestCase):
    def test_production_allowlist_unchanged(self) -> None:
        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))
        self.assertIn("capture_screen", AGENT_EXECUTION_ALLOWLIST)
        self.assertIn("inspect_active_window", AGENT_EXECUTION_ALLOWLIST)
        self.assertTrue(PRODUCTION_EXECUTION_ALLOWLIST <= AGENT_EXECUTION_ALLOWLIST)


class CaptureScreenToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.token = GATE_TOKEN.set(True)
        self.addCleanup(lambda: GATE_TOKEN.reset(self.token))
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def test_screenshot_metadata_and_no_store(self) -> None:
        tool = CaptureScreenTool(capture=_ok_capture)
        result = tool.invoke(max_width=1280, max_height=720)
        self.assertTrue(result.success, result.error)
        self.assertEqual(1280, result.details["width"])
        self.assertEqual(720, result.details["height"])
        self.assertEqual(1, result.details["monitor"])
        self.assertEqual(2, result.details["monitors"])
        self.assertEqual("2026-08-30T16:35:00+00:00", result.details["timestamp"])
        self.assertFalse(result.details["stored"])
        self.assertIn("resolution=1280x720", result.output)
        self.assertIn(VISION_UNAVAILABLE, result.output)
        self.assertNotIn("image", result.details)
        pngs = list(Path(self.tmp.name).rglob("*.png"))
        self.assertEqual([], pngs)

    def test_unavailable_screen_capture(self) -> None:
        tool = CaptureScreenTool(
            capture=lambda *_a: {
                "available": False,
                "error": "screen capture unavailable",
                "width": 0,
                "height": 0,
                "monitor": 0,
                "monitors": 1,
                "timestamp": "2026-08-30T16:35:00+00:00",
            }
        )
        result = tool.invoke()
        self.assertFalse(result.success)
        self.assertIn("screen capture unavailable", result.error)

    def test_unbound_capture_fails_closed(self) -> None:
        result = CaptureScreenTool().invoke()
        self.assertFalse(result.success)
        self.assertIn("screen capture unavailable", result.error)

    def test_capture_exception_fails_closed(self) -> None:
        def _boom(*_a: object) -> dict[str, object]:
            raise RuntimeError("no desktop")

        result = CaptureScreenTool(capture=_boom).invoke()
        self.assertFalse(result.success)
        self.assertIn("screen capture unavailable", result.error)

    def test_unknown_argument_refused(self) -> None:
        result = CaptureScreenTool(capture=_ok_capture).invoke(path="C:/secret.png")
        self.assertFalse(result.success)
        self.assertIn("unknown arguments", result.error)

    def test_local_vision_used_when_available(self) -> None:
        def _describe(image: bytes, prompt: str) -> str:
            del prompt
            self.assertTrue(image)
            return "desktop shows a code editor"

        tool = CaptureScreenTool(capture=_ok_capture, describe=_describe)
        result = tool.invoke()
        self.assertTrue(result.success)
        self.assertIn("desktop shows a code editor", result.output)
        self.assertNotIn(VISION_UNAVAILABLE, result.output)


class ActiveWindowToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.token = GATE_TOKEN.set(True)
        self.addCleanup(lambda: GATE_TOKEN.reset(self.token))

    def test_inspect_active_window(self) -> None:
        tool = InspectActiveWindowTool(
            inspect=lambda: {
                "available": True,
                "application": "notepad.exe",
                "title": "Untitled - Notepad",
                "pid": 77,
                "hwnd": 1234,
            }
        )
        result = tool.invoke()
        self.assertTrue(result.success, result.error)
        self.assertEqual("notepad.exe", result.details["application"])
        self.assertEqual("Untitled - Notepad", result.details["title"])
        self.assertEqual(77, result.details["pid"])
        self.assertIn("application=notepad.exe", result.output)

    def test_unavailable_window(self) -> None:
        tool = InspectActiveWindowTool(
            inspect=lambda: {"available": False, "error": "no active window"}
        )
        result = tool.invoke()
        self.assertFalse(result.success)
        self.assertIn("no active window", result.error)

    def test_unbound_window_fails_closed(self) -> None:
        result = InspectActiveWindowTool().invoke()
        self.assertFalse(result.success)
        self.assertIn("unavailable", result.error)


class SecurityGatingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        scratch = root / "scratch"
        project = root / "project"
        scratch.mkdir()
        project.mkdir()
        self.security = PolicySecurityAgent(
            write_roots=(root / "data",),
            scratch_root=scratch,
            project_roots=(project,),
        )
        registry = InMemoryToolRegistry()
        registry.register(CaptureScreenTool(capture=_ok_capture))
        registry.register(
            InspectActiveWindowTool(
                inspect=lambda: {
                    "available": True,
                    "application": "code.exe",
                    "title": "Astra",
                    "pid": 9,
                }
            )
        )
        self.invoker = GatedToolInvoker(
            registry,
            security=self.security,
            execution_allowlist=AGENT_EXECUTION_ALLOWLIST,
        )

    def test_screen_capture_allowed(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(caller="test", tool_name="capture_screen")
        )
        self.assertEqual(InvocationStatus.EXECUTED, rec.status)
        self.assertTrue(rec.executed)
        assert rec.result is not None
        self.assertTrue(rec.result.success)

    def test_window_inspect_allowed(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(caller="test", tool_name="inspect_active_window")
        )
        self.assertEqual(InvocationStatus.EXECUTED, rec.status)
        self.assertTrue(rec.executed)

    def test_upload_kind_denied(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="capture_screen",
                arguments={"kind": "upload"},
            )
        )
        self.assertEqual(InvocationStatus.DENIED, rec.status)
        self.assertFalse(rec.executed)

    def test_missing_security_check_denies(self) -> None:
        class _Blind:
            def check_network_access(self, url: str) -> SimpleNamespace:
                return SimpleNamespace(verdict=Verdict.ALLOW, reason="n")

            def check_permanent_change(self, desc: str) -> SimpleNamespace:
                return SimpleNamespace(verdict=Verdict.ALLOW, reason="p")

        registry = InMemoryToolRegistry()
        registry.register(CaptureScreenTool(capture=_ok_capture))
        invoker = GatedToolInvoker(
            registry,
            security=_Blind(),
            execution_allowlist=AGENT_EXECUTION_ALLOWLIST,
        )
        rec = invoker.request(
            ToolInvocationRequest(caller="test", tool_name="capture_screen")
        )
        self.assertEqual(InvocationStatus.DENIED, rec.status)
        self.assertIn("missing", rec.reason)

    def test_policy_unit(self) -> None:
        allow = self.security.check_environment_perception("capture_screen")
        self.assertIs(Verdict.ALLOW, allow.verdict)
        deny = self.security.check_environment_perception("upload screenshot")
        self.assertIs(Verdict.DENY, deny.verdict)
        net = self.security.check_environment_perception("https://vision.example")
        self.assertIs(Verdict.DENY, net.verdict)


class NoNetworkTest(unittest.TestCase):
    def test_specs_are_offline(self) -> None:
        self.assertFalse(CaptureScreenTool().spec.requires_network)
        self.assertFalse(InspectActiveWindowTool().spec.requires_network)
        self.assertFalse(CaptureScreenTool().spec.makes_permanent_change)
        self.assertFalse(InspectActiveWindowTool().spec.makes_permanent_change)

    def test_sources_import_no_network(self) -> None:
        root = Path(__file__).parent.parent
        files = [
            root / "astra" / "tools" / "capture_screen.py",
            root / "astra" / "tools" / "inspect_active_window.py",
            root / "astra" / "reasoning" / "vision.py",
            root / "astra" / "core" / "perception_host.py",
        ]
        banned = ("socket", "httpx", "requests", "urllib", "http.client", "aiohttp")
        for path in files:
            tree = ast.parse(path.read_text(encoding="utf-8"))
            names: list[str] = []
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    names.extend(alias.name for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names.append(node.module)
            for name in names:
                for item in banned:
                    self.assertFalse(
                        name == item or name.startswith(item + "."),
                        f"{path.name} imports {name}",
                    )


class VisionUnavailableTest(unittest.TestCase):
    def test_default_vision_unavailable(self) -> None:
        backend = UnavailableVisionBackend()
        self.assertFalse(backend.is_available())
        self.assertEqual(VISION_UNAVAILABLE, backend.describe(b"png"))

    def test_settings_without_model_stay_unavailable(self) -> None:
        backend = vision_from_settings(SimpleNamespace(reasoning=SimpleNamespace()))
        self.assertFalse(backend.is_available())
        self.assertEqual(VISION_UNAVAILABLE, backend.describe(b"png"))

    def test_configured_missing_file_stays_unavailable(self) -> None:
        backend = vision_from_settings(
            SimpleNamespace(
                reasoning=SimpleNamespace(vision_model_path=Path("C:/missing-vision.gguf"))
            )
        )
        self.assertFalse(backend.is_available())

    def test_does_not_replace_llm_backend(self) -> None:
        from astra.reasoning import LLMBackend, VisionBackend

        self.assertFalse(issubclass(UnavailableVisionBackend, LLMBackend))
        self.assertTrue(issubclass(UnavailableVisionBackend, VisionBackend))


class AgentObservationTest(unittest.TestCase):
    def test_open_application_question(self) -> None:
        self.assertTrue(is_execution_goal("What application is currently open?"))
        self.assertTrue(wants_window_inspect("What application is currently open?"))
        self.assertFalse(wants_screen_capture("What application is currently open?"))
        tools = FakeTools()
        loop = _loop(llm=FakeLLM(default=""), tools=tools)
        events = list(loop.iter_run("What application is currently open?"))
        run = loop.last_run
        assert run is not None
        self.assertEqual(AgentState.COMPLETE, run.state)
        names = [name for name, _args in tools.calls]
        self.assertEqual(["inspect_active_window"], names)
        self.assertIn("notepad.exe", run.result)
        self.assertTrue(any(event.type == "observe" for event in events))
        states = [event.state for event in events if event.type == "agent_state"]
        self.assertIn(AgentState.EXECUTE.value, states)
        self.assertIn(AgentState.OBSERVE.value, states)
        self.assertIn(AgentState.EVALUATE.value, states)

    def test_look_at_desktop(self) -> None:
        self.assertTrue(is_execution_goal("Take a look at my desktop."))
        self.assertTrue(wants_screen_capture("Take a look at my desktop."))
        tools = FakeTools()
        loop = _loop(llm=FakeLLM(default=""), tools=tools)
        run = loop.run("Take a look at my desktop.")
        self.assertEqual(AgentState.COMPLETE, run.state)
        names = [name for name, _args in tools.calls]
        self.assertEqual(["capture_screen"], names)
        self.assertIn(VISION_UNAVAILABLE, run.result)
        self.assertIn("1280x720", run.result)

    def test_inspect_project_does_not_force_screenshot(self) -> None:
        self.assertFalse(wants_perception("Inspect my project"))
        tools = FakeTools()
        loop = _loop(llm=FakeLLM(), tools=tools)
        run = loop.run("Inspect my project")
        names = [name for name, _args in tools.calls]
        self.assertNotIn("capture_screen", names)
        self.assertNotIn("inspect_active_window", names)
        self.assertTrue(names)

    def test_chat_qa_unchanged(self) -> None:
        self.assertFalse(is_execution_goal("What is a screenshot?"))
        self.assertFalse(is_execution_goal("What is an application?"))
        self.assertFalse(is_execution_goal("Hello"))
        tools = FakeTools()
        from astra.presentation.orchestration import ConversationOrchestrator

        loop = _loop(llm=FakeLLM(), tools=tools)
        orch = ConversationOrchestrator.from_controller(
            SimpleNamespace(_agent_loop=loop, llm=FakeLLM())
        )
        reply = orch.ask("What is a screenshot?")
        self.assertEqual("success", reply.status)
        self.assertEqual([], tools.calls)


class HostFailClosedTest(unittest.TestCase):
    def test_capture_desktop_without_pillow(self) -> None:
        import builtins

        from astra.core import perception_host

        real_import = builtins.__import__

        def _blocked(name: str, *args: object, **kwargs: object):
            if name == "PIL" or name.startswith("PIL."):
                raise ImportError("no pillow")
            return real_import(name, *args, **kwargs)

        with patch("builtins.__import__", side_effect=_blocked):
            outcome = perception_host.capture_desktop(max_width=640, max_height=480)
        self.assertFalse(outcome["available"])
        self.assertIn("unavailable", str(outcome["error"]))
        self.assertEqual(b"", outcome["image"])


if __name__ == "__main__":
    unittest.main()
