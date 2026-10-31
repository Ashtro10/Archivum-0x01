"""Gated mouse/keyboard. Mocked host. No real pointer or keys."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from astra.core.agent_loop import (
    VISUAL_REASONING_UNAVAILABLE,
    AgentState,
    is_execution_goal,
    wants_gui_interaction,
)
from astra.security.agent import PolicySecurityAgent
from astra.security.contracts import Verdict
from astra.tools.allowlist import AGENT_EXECUTION_ALLOWLIST, PRODUCTION_EXECUTION_ALLOWLIST
from astra.tools.contracts import InvocationStatus, ToolInvocationRequest
from astra.tools.gate import GatedToolInvoker
from astra.tools.guard import GATE_TOKEN
from astra.tools.keyboard_action import KeyboardActionTool
from astra.tools.mouse_action import MouseActionTool
from astra.tools.registry import InMemoryToolRegistry
from tests.test_agent_loop import FakeLLM, FakeTools, _loop
from tests.test_application_control import AgentApplicationTaskTest


_GUI_PLAN = (
    "TASK tool=launch_application application=blender | Launch Blender\n"
    "TASK tool=inspect_process query=blender | Verify process\n"
    "TASK tool=inspect_active_window | Inspect window\n"
    "TASK tool=capture_screen | Observe screen\n"
    "TASK tool=mouse_action action=click x=40 y=80 button=left | Click New\n"
)


def _ok_mouse(action, x, y, button, clicks, scroll):
    return {
        "success": True,
        "action": action,
        "x": x,
        "y": y,
        "button": button,
        "click_count": clicks,
        "scroll_amount": scroll,
    }


def _ok_keys(action, text, keys):
    return {"success": True, "action": action, "text": text, "keys": keys}


class AllowlistBoundaryTest(unittest.TestCase):
    def test_production_allowlist_unchanged(self) -> None:
        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))
        self.assertIn("mouse_action", AGENT_EXECUTION_ALLOWLIST)
        self.assertIn("keyboard_action", AGENT_EXECUTION_ALLOWLIST)
        self.assertNotIn("mouse_action", PRODUCTION_EXECUTION_ALLOWLIST)


class MouseActionToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.token = GATE_TOKEN.set(True)
        self.addCleanup(lambda: GATE_TOKEN.reset(self.token))
        self.calls: list[tuple] = []

        def _act(action, x, y, button, clicks, scroll):
            self.calls.append((action, x, y, button, clicks, scroll))
            return _ok_mouse(action, x, y, button, clicks, scroll)

        self.tool = MouseActionTool(act=_act, screen_size=lambda: (1920, 1080))

    def test_move(self) -> None:
        result = self.tool.invoke(action="move", x=10, y=20)
        self.assertTrue(result.success, result.error)
        self.assertEqual("move", result.details["action"])
        self.assertEqual(( "move", 10, 20, "left", 1, 0), self.calls[0])
        self.assertIn("resolution=1920x1080", result.output)

    def test_click(self) -> None:
        result = self.tool.invoke(action="click", x=40, y=80, button="left")
        self.assertTrue(result.success, result.error)
        self.assertEqual(40, result.details["x"])
        self.assertEqual(80, result.details["y"])
        self.assertTrue(result.details["success"])

    def test_coordinate_outside_screen(self) -> None:
        result = self.tool.invoke(action="click", x=1920, y=10)
        self.assertFalse(result.success)
        self.assertIn("outside", result.error)
        self.assertEqual([], self.calls)

    def test_negative_coordinate(self) -> None:
        result = self.tool.invoke(action="click", x=-1, y=10)
        self.assertFalse(result.success)
        self.assertIn("outside", result.error)

    def test_missing_host_fails_closed(self) -> None:
        result = MouseActionTool().invoke(action="click", x=1, y=1)
        self.assertFalse(result.success)
        self.assertIn("unavailable", result.error)


class KeyboardActionToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.token = GATE_TOKEN.set(True)
        self.addCleanup(lambda: GATE_TOKEN.reset(self.token))
        self.calls: list[tuple] = []

        def _act(action, text, keys):
            self.calls.append((action, text, keys))
            return _ok_keys(action, text, keys)

        self.tool = KeyboardActionTool(act=_act)

    def test_type_text(self) -> None:
        result = self.tool.invoke(action="type_text", text="hello")
        self.assertTrue(result.success, result.error)
        self.assertEqual(("type_text", "hello", ()), self.calls[0])

    def test_hotkey(self) -> None:
        result = self.tool.invoke(action="hotkey", keys="ctrl+n")
        self.assertTrue(result.success, result.error)
        self.assertEqual(("ctrl", "n"), result.details["keys"])

    def test_banned_hotkey(self) -> None:
        result = self.tool.invoke(action="hotkey", keys="alt+f4")
        self.assertFalse(result.success)
        self.assertIn("allowlisted", result.error)
        self.assertEqual([], self.calls)

    def test_unknown_key(self) -> None:
        result = self.tool.invoke(action="press_key", key="scan_code_99")
        self.assertFalse(result.success)
        self.assertIn("allowlisted", result.error)


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
        registry.register(MouseActionTool(act=_ok_mouse, screen_size=lambda: (1920, 1080)))
        registry.register(KeyboardActionTool(act=_ok_keys))
        self.invoker = GatedToolInvoker(
            registry,
            security=self.security,
            execution_allowlist=AGENT_EXECUTION_ALLOWLIST,
        )

    def test_mouse_requires_approval(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="mouse_action",
                arguments={"action": "click", "x": "40", "y": "80"},
            )
        )
        self.assertEqual(InvocationStatus.PENDING_APPROVAL, rec.status)
        self.assertFalse(rec.executed)

    def test_keyboard_requires_approval(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="keyboard_action",
                arguments={"action": "type_text", "text": "hello"},
            )
        )
        self.assertEqual(InvocationStatus.PENDING_APPROVAL, rec.status)
        self.assertFalse(rec.executed)

    def test_password_denied(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="keyboard_action",
                arguments={"action": "type_text", "text": "password"},
            )
        )
        self.assertEqual(InvocationStatus.DENIED, rec.status)
        self.assertFalse(rec.executed)

    def test_shutdown_denied(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="keyboard_action",
                arguments={"action": "hotkey", "keys": "shutdown"},
            )
        )
        self.assertEqual(InvocationStatus.DENIED, rec.status)

    def test_missing_check_denies(self) -> None:
        class _Blind:
            def check_network_access(self, url: str) -> SimpleNamespace:
                return SimpleNamespace(verdict=Verdict.ALLOW, reason="n")

            def check_permanent_change(self, desc: str) -> SimpleNamespace:
                return SimpleNamespace(verdict=Verdict.ALLOW, reason="p")

        registry = InMemoryToolRegistry()
        registry.register(MouseActionTool(act=_ok_mouse, screen_size=lambda: (800, 600)))
        invoker = GatedToolInvoker(
            registry,
            security=_Blind(),
            execution_allowlist=AGENT_EXECUTION_ALLOWLIST,
        )
        rec = invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="mouse_action",
                arguments={"action": "move", "x": "1", "y": "1"},
            )
        )
        self.assertEqual(InvocationStatus.DENIED, rec.status)
        self.assertIn("missing", rec.reason)

    def test_policy_unit(self) -> None:
        need = self.security.check_input_control("mouse_action action=click x=1 y=1")
        self.assertIs(Verdict.REQUIRES_APPROVAL, need.verdict)
        deny = self.security.check_input_control("keyboard_action text=password")
        self.assertIs(Verdict.DENY, deny.verdict)


class AgentInteractionLoopTest(unittest.TestCase):
    def test_open_blender_unchanged(self) -> None:
        AgentApplicationTaskTest().test_open_blender_and_verify()
        self.assertFalse(wants_gui_interaction("Open Blender."))
        self.assertTrue(is_execution_goal("Open Blender."))

    def test_create_project_stops_without_vision(self) -> None:
        self.assertTrue(wants_gui_interaction("Open Blender and create a new project."))
        tools = FakeTools()
        loop = _loop(llm=FakeLLM(default=""), tools=tools, max_steps=12)
        run = loop.run("Open Blender and create a new project.")
        self.assertEqual(AgentState.WAIT_FOR_USER, run.state)
        self.assertEqual(VISUAL_REASONING_UNAVAILABLE, run.result)
        names = [name for name, _args in tools.calls]
        self.assertEqual(
            ["launch_application", "inspect_process", "inspect_active_window", "capture_screen"],
            names,
        )
        self.assertNotIn("mouse_action", names)

    def test_action_then_observe_then_approval(self) -> None:
        tools = FakeTools()
        tools.vision_available = True
        tools.approval_for = "mouse_action"
        loop = _loop(llm=FakeLLM(script=[_GUI_PLAN], default=""), tools=tools, max_steps=16)
        events = list(loop.iter_run("Open Blender and create a new project."))
        run = loop.last_run
        assert run is not None
        self.assertEqual(AgentState.WAIT_FOR_USER, run.state)
        self.assertIn("Approval required", run.result)
        names = [name for name, _args in tools.calls]
        self.assertEqual("launch_application", names[0])
        self.assertIn("inspect_process", names)
        self.assertIn("inspect_active_window", names)
        self.assertIn("capture_screen", names)
        self.assertIn("mouse_action", names)
        states = [event.state for event in events if event.type == "agent_state"]
        self.assertIn(AgentState.EXECUTE.value, states)
        self.assertIn(AgentState.OBSERVE.value, states)
        self.assertIn(AgentState.EVALUATE.value, states)

    def test_after_action_capture(self) -> None:
        tools = FakeTools()
        tools.vision_available = True
        loop = _loop(llm=FakeLLM(script=[_GUI_PLAN], default=""), tools=tools, max_steps=16)
        run = loop.run("Open Blender and create a new project.")
        names = [name for name, _args in tools.calls]
        self.assertGreaterEqual(names.count("capture_screen"), 2)
        mouse_at = names.index("mouse_action")
        self.assertEqual("capture_screen", names[mouse_at + 1])
        self.assertEqual(AgentState.COMPLETE, run.state)

    def test_action_failure_replans_without_same_click(self) -> None:
        tools = FakeTools()
        tools.vision_available = True
        tools.fail_next.add("mouse_action")
        loop = _loop(llm=FakeLLM(script=[_GUI_PLAN], default=""), tools=tools, max_steps=16)
        events = list(loop.iter_run("Open Blender and create a new project."))
        run = loop.last_run
        assert run is not None
        names = [name for name, _args in tools.calls]
        self.assertEqual(1, names.count("mouse_action"))
        self.assertIn(AgentState.REPLAN.value, [event.state for event in events if event.type == "agent_state"])
        self.assertNotEqual("same action failed repeatedly", run.error)

    def test_repeated_same_action_asks_user(self) -> None:
        tools = FakeTools()
        tools.vision_available = True
        loop = _loop(
            llm=FakeLLM(script=[_GUI_PLAN, _GUI_PLAN], default=""),
            tools=tools,
            max_steps=16,
            max_replans=3,
        )
        tools.fail_next.add("mouse_action")
        run = loop.run("Open Blender and create a new project.")
        if "mouse_action" in {name for name, _ in tools.calls}:
            tools.fail_next.add("mouse_action")
        self.assertIn(run.state, {AgentState.COMPLETE, AgentState.WAIT_FOR_USER, AgentState.FAILED})

    def test_max_actions(self) -> None:
        plan = (
            "TASK tool=mouse_action action=click x=10 y=10 observe=false | Click one\n"
            "TASK tool=mouse_action action=click x=20 y=20 observe=false | Click two\n"
            "TASK tool=mouse_action action=click x=30 y=30 observe=false | Click three\n"
        )
        tools = FakeTools()
        tools.vision_available = True
        loop = _loop(
            llm=FakeLLM(script=[plan], default=""),
            tools=tools,
            max_actions=2,
            max_steps=16,
        )
        run = loop.run("Open Blender and create a new project.")
        self.assertEqual(AgentState.WAIT_FOR_USER, run.state)
        self.assertEqual("max_actions reached", run.error)
        self.assertEqual(2, sum(1 for name, _ in tools.calls if name == "mouse_action"))

    def test_max_visual_observations(self) -> None:
        plan = (
            "TASK tool=capture_screen | Look one\n"
            "TASK tool=capture_screen | Look two\n"
            "TASK tool=capture_screen | Look three\n"
        )
        tools = FakeTools()
        tools.vision_available = True
        loop = _loop(
            llm=FakeLLM(script=[plan], default=""),
            tools=tools,
            max_visual_observations=2,
            max_steps=16,
        )
        run = loop.run("Open Blender and create a new project.")
        self.assertEqual(AgentState.WAIT_FOR_USER, run.state)
        self.assertEqual("max_visual_observations reached", run.error)
        self.assertEqual(2, sum(1 for name, _ in tools.calls if name == "capture_screen"))

    def test_chat_and_inspect_unchanged(self) -> None:
        self.assertFalse(is_execution_goal("What is a mouse?"))
        self.assertFalse(wants_gui_interaction("Inspect my project"))
        tools = FakeTools()
        loop = _loop(llm=FakeLLM(), tools=tools)
        run = loop.run("Inspect my project")
        names = [name for name, _args in tools.calls]
        self.assertNotIn("mouse_action", names)
        self.assertNotIn("keyboard_action", names)
        self.assertTrue(names)


if __name__ == "__main__":
    unittest.main()
