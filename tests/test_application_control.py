"""Gated application launch and read-only process inspection. No real apps."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from astra.core.agent_loop import AgentState, is_execution_goal
from astra.security.agent import PolicySecurityAgent
from astra.security.contracts import ProcessSpec, Verdict
from astra.testing.runner import ProcessRunner, ProcessStatus
from astra.testing.subset import CopiedSubsetWorkspace
from astra.tools.allowlist import AGENT_EXECUTION_ALLOWLIST, PRODUCTION_EXECUTION_ALLOWLIST
from astra.tools.contracts import InvocationStatus, ToolInvocationRequest
from astra.tools.gate import GatedToolInvoker
from astra.tools.guard import GATE_TOKEN
from astra.tools.inspect_process import InspectProcessTool
from astra.tools.launch_application import LaunchApplicationTool
from astra.tools.registry import InMemoryToolRegistry
from tests.test_agent_loop import FakeLLM, FakeTools, _loop


class AllowlistBoundaryTest(unittest.TestCase):
    def test_production_allowlist_unchanged(self) -> None:
        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))
        self.assertIn("launch_application", AGENT_EXECUTION_ALLOWLIST)
        self.assertIn("inspect_process", AGENT_EXECUTION_ALLOWLIST)
        self.assertTrue(PRODUCTION_EXECUTION_ALLOWLIST <= AGENT_EXECUTION_ALLOWLIST)


class LaunchApplicationToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.blender = Path(self.tmp.name) / "blender.exe"
        self.blender.write_bytes(b"fake")
        self.calls: list[tuple[Path, float]] = []
        self.token = GATE_TOKEN.set(True)
        self.addCleanup(lambda: GATE_TOKEN.reset(self.token))

    def _tool(self, launch=None) -> LaunchApplicationTool:
        def _ok(executable: Path, timeout: float) -> dict[str, object]:
            self.calls.append((executable, timeout))
            return {
                "success": True,
                "spawned": True,
                "running": True,
                "pid": 4242,
                "timed_out": False,
            }

        return LaunchApplicationTool(
            allowed_executables=(self.blender,),
            launch=launch or _ok,
        )

    def test_allowed_application_launch(self) -> None:
        result = self._tool().invoke(application="blender", timeout=10)
        self.assertTrue(result.success, result.error)
        self.assertEqual(4242, result.details["pid"])
        self.assertTrue(result.details["running"])
        self.assertIn("application=blender", result.output)
        self.assertEqual(1, len(self.calls))

    def test_denied_application_launch(self) -> None:
        result = self._tool().invoke(application="cmd.exe")
        self.assertFalse(result.success)
        self.assertIn("allowlist", result.error)
        self.assertEqual([], self.calls)

    def test_invalid_executable(self) -> None:
        missing = Path(self.tmp.name) / "missing.exe"
        tool = LaunchApplicationTool(
            allowed_executables=(missing,),
            launch=lambda *_a: {"success": True, "running": True, "pid": 1},
        )
        result = tool.invoke(executable=str(missing))
        self.assertFalse(result.success)
        self.assertIn("invalid executable", result.error)

    def test_timeout(self) -> None:
        def _slow(executable: Path, timeout: float) -> dict[str, object]:
            return {
                "success": False,
                "running": False,
                "pid": None,
                "timed_out": True,
                "error": "launch timed out",
            }

        result = self._tool(launch=_slow).invoke(application="blender", timeout=1)
        self.assertFalse(result.success)
        self.assertTrue(result.details["timed_out"])
        self.assertIn("timed out", result.error)

    def test_startup_verification_requires_running(self) -> None:
        def _died(executable: Path, timeout: float) -> dict[str, object]:
            return {
                "success": True,
                "spawned": True,
                "running": False,
                "pid": 7,
                "timed_out": False,
            }

        result = self._tool(launch=_died).invoke(application="blender")
        self.assertFalse(result.success)
        self.assertIn("did not stay running", result.error)

    def test_unbound_launcher_fails_closed(self) -> None:
        tool = LaunchApplicationTool(allowed_executables=(self.blender,))
        result = tool.invoke(application="blender")
        self.assertFalse(result.success)
        self.assertIn("not bound", result.error)


class InspectProcessToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.token = GATE_TOKEN.set(True)
        self.addCleanup(lambda: GATE_TOKEN.reset(self.token))

    def test_process_inspection(self) -> None:
        def _rows(query: str, pid: int | None):
            self.assertEqual("blender", query)
            return (
                {"name": "blender.exe", "pid": 4242, "status": "running", "running": True},
            )

        tool = InspectProcessTool(inspect=_rows)
        result = tool.invoke(query="blender")
        self.assertTrue(result.success, result.error)
        self.assertTrue(result.details["running"])
        self.assertEqual(1, result.details["matches"])
        self.assertIn("4242", result.output)

    def test_refuses_kill_query(self) -> None:
        tool = InspectProcessTool(inspect=lambda *_a: ())
        result = tool.invoke(query="kill blender")
        self.assertFalse(result.success)
        self.assertIn("kill", result.error)


class SecurityEnforcementTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.blender = root / "blender.exe"
        self.blender.write_bytes(b"fake")
        self.scratch = root / "scratch"
        self.project = root / "project"
        self.scratch.mkdir()
        self.project.mkdir()
        self.security = PolicySecurityAgent(
            write_roots=(root / "data",),
            scratch_root=self.scratch,
            project_roots=(self.project,),
            process_executables=(Path(sys.executable), self.blender),
        )
        registry = InMemoryToolRegistry()
        registry.register(
            LaunchApplicationTool(
                allowed_executables=(self.blender,),
                launch=lambda *_a: {
                    "success": True,
                    "running": True,
                    "pid": 9,
                    "spawned": True,
                },
            )
        )
        registry.register(
            InspectProcessTool(
                inspect=lambda query, pid: (
                    {"name": "blender.exe", "pid": 9, "status": "running", "running": True},
                )
            )
        )
        self.invoker = GatedToolInvoker(
            registry,
            security=self.security,
            execution_allowlist=AGENT_EXECUTION_ALLOWLIST,
        )

    def test_security_allows_allowlisted_app(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="launch_application",
                arguments={"application": "blender"},
            )
        )
        self.assertEqual(InvocationStatus.EXECUTED, rec.status)
        self.assertTrue(rec.executed)
        assert rec.result is not None
        self.assertTrue(rec.result.success)

    def test_security_denies_shell(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="launch_application",
                arguments={"application": "cmd.exe"},
            )
        )
        self.assertEqual(InvocationStatus.DENIED, rec.status)
        self.assertFalse(rec.executed)
        self.assertIn("forbidden", rec.reason.lower())

    def test_security_denies_unknown_app(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="launch_application",
                arguments={"application": "notepad"},
            )
        )
        self.assertEqual(InvocationStatus.DENIED, rec.status)
        self.assertFalse(rec.executed)

    def test_inspect_kill_denied_at_security(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="inspect_process",
                arguments={"query": "terminate blender"},
            )
        )
        self.assertEqual(InvocationStatus.DENIED, rec.status)
        self.assertFalse(rec.executed)

    def test_inspect_allowed(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="inspect_process",
                arguments={"query": "blender"},
            )
        )
        self.assertEqual(InvocationStatus.EXECUTED, rec.status)
        self.assertTrue(rec.executed)

    def test_application_launch_policy_unit(self) -> None:
        allow = self.security.check_application_launch("blender")
        self.assertIs(Verdict.ALLOW, allow.verdict)
        deny = self.security.check_application_launch("powershell.exe")
        self.assertIs(Verdict.DENY, deny.verdict)
        inspect = self.security.check_process_inspection("blender")
        self.assertIs(Verdict.ALLOW, inspect.verdict)
        banned = self.security.check_process_inspection("kill")
        self.assertIs(Verdict.DENY, banned.verdict)


class ProcessRunnerLaunchTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.project = root / "project"
        self.scratch = root / "scratch"
        self.data = root / "data"
        self.project.mkdir()
        self.scratch.mkdir()
        self.data.mkdir()
        self.blender = root / "blender.exe"
        self.blender.write_bytes(b"fake")
        self.security = PolicySecurityAgent(
            write_roots=(self.data,),
            scratch_root=self.scratch,
            project_roots=(self.project,),
            process_executables=(Path(sys.executable), self.blender),
        )
        self.workspace = CopiedSubsetWorkspace(
            security=self.security,
            scratch_root=self.scratch,
            project_root=self.project,
        )
        self.runner = ProcessRunner(security=self.security, subset=self.workspace)
        self.session = self.workspace.create_empty_session()
        self.assertTrue(self.session.success)
        self.addCleanup(self.workspace.discard_all)

    def test_launch_mocked_popen(self) -> None:
        class _Proc:
            pid = 4242

            def poll(self) -> None:
                return None

        spec = ProcessSpec(
            argv=(str(self.blender),),
            cwd=self.session.subset_root,
            timeout_seconds=15,
        )
        with patch("astra.testing.runner.subprocess.Popen", return_value=_Proc()) as popped:
            result = self.runner.launch(spec, session_id=self.session.session_id)
        self.assertTrue(result.success)
        self.assertTrue(result.spawned)
        self.assertIs(ProcessStatus.RUNNING, result.status)
        self.assertEqual(4242, result.pid)
        popped.assert_called_once()
        kwargs = popped.call_args.kwargs
        self.assertFalse(kwargs.get("shell"))

    def test_launch_immediate_exit_is_not_success(self) -> None:
        class _Proc:
            pid = 8

            def poll(self) -> int:
                return 1

        spec = ProcessSpec(
            argv=(str(self.blender),),
            cwd=self.session.subset_root,
            timeout_seconds=15,
        )
        with patch("astra.testing.runner.subprocess.Popen", return_value=_Proc()):
            result = self.runner.launch(spec, session_id=self.session.session_id)
        self.assertFalse(result.success)
        self.assertIn("exited immediately", result.reason)

    def test_launch_missing_file(self) -> None:
        missing = Path(self.tmp.name) / "gone.exe"
        security = PolicySecurityAgent(
            write_roots=(self.data,),
            scratch_root=self.scratch,
            project_roots=(self.project,),
            process_executables=(Path(sys.executable), missing),
        )
        runner = ProcessRunner(security=security, subset=self.workspace)
        spec = ProcessSpec(
            argv=(str(missing),),
            cwd=self.session.subset_root,
            timeout_seconds=15,
        )
        result = runner.launch(spec, session_id=self.session.session_id)
        self.assertFalse(result.spawned)
        self.assertIn("invalid", result.reason)

    def test_inspect_mocked_psutil(self) -> None:
        class _Iter:
            info = {"pid": 4242, "name": "blender.exe", "status": "running"}

        with patch("psutil.process_iter", return_value=[_Iter()]):
            rows = self.runner.inspect(query="blender")
        self.assertEqual(1, len(rows))
        self.assertEqual(4242, rows[0]["pid"])
        self.assertTrue(rows[0]["running"])


class AgentApplicationTaskTest(unittest.TestCase):
    def test_open_blender_and_verify(self) -> None:
        tools = FakeTools()
        loop = _loop(llm=FakeLLM(default=""), tools=tools)
        run = loop.run("Open Blender and tell me whether it started.")
        self.assertEqual(AgentState.COMPLETE, run.state)
        names = [name for name, _args in tools.calls]
        self.assertEqual("launch_application", names[0])
        self.assertEqual("inspect_process", names[1])
        self.assertIn("blender", tools.calls[0][1].get("application", ""))
        self.assertIn("4242", run.result)

    def test_open_blender_inspect_project_report(self) -> None:
        tools = FakeTools()
        loop = _loop(llm=FakeLLM(default=""), tools=tools)
        run = loop.run("Open Blender, inspect my project, and create a report.")
        self.assertIn(run.state, {AgentState.COMPLETE, AgentState.WAIT_FOR_USER})
        names = [name for name, _args in tools.calls]
        self.assertIn("launch_application", names)
        self.assertIn("inspect_process", names)
        self.assertTrue(any(name in {"search_files", "read_project"} for name in names))
        self.assertIn("write_text", names)

    def test_chat_unchanged(self) -> None:
        self.assertFalse(is_execution_goal("What is Blender?"))
        self.assertFalse(is_execution_goal("Open a file."))
        tools = FakeTools()
        from types import SimpleNamespace

        from astra.presentation.orchestration import ConversationOrchestrator

        loop = _loop(llm=FakeLLM(), tools=tools)
        orch = ConversationOrchestrator.from_controller(
            SimpleNamespace(_agent_loop=loop, llm=FakeLLM())
        )
        reply = orch.ask("What is Blender?")
        self.assertEqual("success", reply.status)
        self.assertEqual([], tools.calls)


if __name__ == "__main__":
    unittest.main()
