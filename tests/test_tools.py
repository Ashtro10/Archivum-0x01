"""Tool Interface tests (B-016 gate, B-017 read_text, B-018 read_project).

Architectural gate, not an OS sandbox.
"""

from __future__ import annotations

import ast
import inspect
import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from astra.core.contracts import AgentRequest
from astra.core.errors import AgentNotFoundError, ControllerNotStartedError
from astra.security.agent import PolicySecurityAgent
from astra.security.contracts import Verdict
from astra.tools.contracts import (
    InvocationStatus,
    Tool,
    ToolError,
    ToolInvocationRequest,
    ToolRegistry,
    ToolResult,
    ToolSpec,
)
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST
from astra.tools.gate import GatedToolInvoker, TOOL_NETWORK_SENTINEL
from astra.tools.guard import GuardedTool
from astra.tools.read_project import ReadProjectTool
from astra.tools.read_text import MAX_BYTES, ReadTextTool
from astra.tools.registry import InMemoryToolRegistry


class SpyTool(Tool):
    """Test double. No I/O. Counts invoke calls."""

    def __init__(self, spec: ToolSpec) -> None:
        self._spec = spec
        self.calls = 0

    @property
    def spec(self) -> ToolSpec:
        return self._spec

    def invoke(self, **kwargs: object) -> ToolResult:
        self.calls += 1
        return ToolResult(success=True, output="ok")


class _AllowDecision:
    def __init__(self, verdict: str, reason: str) -> None:
        self.verdict = type("V", (), {"value": verdict})()
        self.reason = reason


class _FakeSecurity:
    """Test double. Returns configured verdicts. Does not execute."""

    def __init__(
        self,
        *,
        network: str = "deny",
        file_write: str = "deny",
        file_read: str = "deny",
        permanent: str = "requires_approval",
    ) -> None:
        self.network = network
        self.file_write = file_write
        self.file_read = file_read
        self.permanent = permanent
        self.network_calls = 0
        self.file_calls = 0
        self.file_read_calls = 0
        self.permanent_calls = 0

    def check_network_access(self, url: str) -> _AllowDecision:
        self.network_calls += 1
        return _AllowDecision(self.network, f"fake network {url}")

    def check_file_write(self, path: Path) -> _AllowDecision:
        self.file_calls += 1
        return _AllowDecision(self.file_write, f"fake file {path}")

    def check_file_read(self, path: Path) -> _AllowDecision:
        self.file_read_calls += 1
        return _AllowDecision(self.file_read, f"fake read {path}")

    def check_permanent_change(self, description: str) -> _AllowDecision:
        self.permanent_calls += 1
        return _AllowDecision(self.permanent, f"fake permanent {description}")


def _registry_with(tool: Tool) -> InMemoryToolRegistry:
    registry = InMemoryToolRegistry()
    registry.register(tool)
    return registry


class RegistryTest(unittest.TestCase):
    def test_register_get_specs(self) -> None:
        spy = SpyTool(ToolSpec(name="alpha", description="a"))
        registry = _registry_with(spy)
        got = registry.get("alpha")
        self.assertIsNotNone(got)
        assert got is not None
        self.assertEqual("alpha", got.spec.name)
        specs = registry.specs()
        self.assertEqual(1, len(specs))
        self.assertEqual("alpha", specs[0].name)

    def test_missing_get_returns_none(self) -> None:
        registry = InMemoryToolRegistry()
        self.assertIsNone(registry.get("nope"))

    def test_duplicate_registration_raises(self) -> None:
        spy = SpyTool(ToolSpec(name="dup", description="d"))
        registry = _registry_with(spy)
        with self.assertRaises(ToolError):
            registry.register(SpyTool(ToolSpec(name="dup", description="other")))

    def test_empty_name_raises(self) -> None:
        registry = InMemoryToolRegistry()
        with self.assertRaises(ToolError):
            registry.register(SpyTool(ToolSpec(name="  ", description="x")))

    def test_register_wraps_in_guarded_tool(self) -> None:
        spy = SpyTool(ToolSpec(name="g", description="g"))
        registry = _registry_with(spy)
        got = registry.get("g")
        self.assertIsInstance(got, GuardedTool)

    def test_registry_has_no_invoke(self) -> None:
        self.assertNotIn("invoke", ToolRegistry.__abstractmethods__)
        self.assertNotIn("invoke", InMemoryToolRegistry.__dict__)


class GuardedToolTest(unittest.TestCase):
    def test_direct_invoke_fails_closed(self) -> None:
        spy = SpyTool(ToolSpec(name="spy", description="s"))
        guarded = GuardedTool(spy)
        result = guarded.invoke()
        self.assertFalse(result.success)
        self.assertIn("GatedToolInvoker", result.error)
        self.assertEqual(0, spy.calls)

    def test_get_invoke_fails_closed(self) -> None:
        spy = SpyTool(ToolSpec(name="spy", description="s"))
        registry = _registry_with(spy)
        tool = registry.get("spy")
        assert tool is not None
        result = tool.invoke(value=1)
        self.assertFalse(result.success)
        self.assertEqual(0, spy.calls)


class GateDenialTest(unittest.TestCase):
    def test_unknown_tool_denied(self) -> None:
        spy = SpyTool(ToolSpec(name="other", description="o"))
        invoker = GatedToolInvoker(
            _registry_with(spy),
            security=PolicySecurityAgent(),
            execution_allowlist=(),
        )
        rec = invoker.request(
            ToolInvocationRequest(caller="test", tool_name="missing")
        )
        self.assertIs(InvocationStatus.DENIED, rec.status)
        self.assertEqual("deny", rec.verdict)
        self.assertFalse(rec.executed)
        self.assertEqual(0, spy.calls)

    def test_missing_security_denied(self) -> None:
        spy = SpyTool(ToolSpec(name="spy", description="s"))
        invoker = GatedToolInvoker(
            _registry_with(spy),
            security=None,
            execution_allowlist=("spy",),
        )
        rec = invoker.request(ToolInvocationRequest(caller="test", tool_name="spy"))
        self.assertIs(InvocationStatus.DENIED, rec.status)
        self.assertIn("security missing", rec.reason)
        self.assertFalse(rec.executed)
        self.assertEqual(0, spy.calls)

    def test_unmapped_capability_denied(self) -> None:
        spy = SpyTool(
            ToolSpec(
                name="echo",
                description="unmapped",
                requires_network=False,
                makes_permanent_change=False,
            )
        )
        security = PolicySecurityAgent()
        invoker = GatedToolInvoker(
            _registry_with(spy),
            security=security,
            execution_allowlist=("echo",),
        )
        rec = invoker.request(ToolInvocationRequest(caller="test", tool_name="echo"))
        self.assertIs(InvocationStatus.DENIED, rec.status)
        self.assertIn("no applicable Security check", rec.reason)
        self.assertFalse(rec.executed)
        self.assertEqual(0, spy.calls)
        self.assertEqual(0, security.checks_total)

    def test_network_capability_denied(self) -> None:
        spy = SpyTool(
            ToolSpec(name="net", description="n", requires_network=True)
        )
        security = PolicySecurityAgent()
        invoker = GatedToolInvoker(
            _registry_with(spy), security=security, execution_allowlist=("net",)
        )
        rec = invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="net",
                arguments={"url": "https://example.com"},
            )
        )
        self.assertIs(InvocationStatus.DENIED, rec.status)
        self.assertEqual("deny", rec.verdict)
        self.assertFalse(rec.executed)
        self.assertEqual(0, spy.calls)
        self.assertGreaterEqual(security.checks_total, 1)

    def test_permanent_change_requires_approval_not_executed(self) -> None:
        spy = SpyTool(
            ToolSpec(
                name="deploy",
                description="ship",
                makes_permanent_change=True,
            )
        )
        invoker = GatedToolInvoker(
            _registry_with(spy),
            security=PolicySecurityAgent(),
            execution_allowlist=("deploy",),
        )
        rec = invoker.request(
            ToolInvocationRequest(caller="test", tool_name="deploy")
        )
        self.assertIs(InvocationStatus.PENDING_APPROVAL, rec.status)
        self.assertEqual("requires_approval", rec.verdict)
        self.assertFalse(rec.executed)
        self.assertEqual(0, spy.calls)
        self.assertEqual(1, invoker.pending_approvals)

    def test_deny_wins_over_requires_approval(self) -> None:
        spy = SpyTool(
            ToolSpec(
                name="both",
                description="b",
                requires_network=True,
                makes_permanent_change=True,
            )
        )
        invoker = GatedToolInvoker(
            _registry_with(spy),
            security=PolicySecurityAgent(),
            execution_allowlist=("both",),
        )
        rec = invoker.request(
            ToolInvocationRequest(caller="test", tool_name="both")
        )
        self.assertIs(InvocationStatus.DENIED, rec.status)
        self.assertEqual("deny", rec.verdict)
        self.assertFalse(rec.executed)
        self.assertEqual(0, spy.calls)


class DualKeyTest(unittest.TestCase):
    def test_allow_empty_allowlist_does_not_execute(self) -> None:
        spy = SpyTool(ToolSpec(name="writeish", description="w"))
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        security = PolicySecurityAgent(write_roots=(root,))
        invoker = GatedToolInvoker(
            _registry_with(spy),
            security=security,
            execution_allowlist=(),
        )
        rec = invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="writeish",
                arguments={"path": str(root / "x.db")},
            )
        )
        self.assertEqual("allow", rec.verdict)
        self.assertIs(InvocationStatus.BLOCKED_EXECUTION, rec.status)
        self.assertFalse(rec.execution_permitted)
        self.assertFalse(rec.executed)
        self.assertEqual(0, spy.calls)

    def test_allow_plus_test_allowlist_executes_once(self) -> None:
        spy = SpyTool(ToolSpec(name="writeish", description="w"))
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        security = PolicySecurityAgent(write_roots=(root,))
        invoker = GatedToolInvoker(
            _registry_with(spy),
            security=security,
            execution_allowlist=("writeish",),
        )
        rec = invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="writeish",
                arguments={"path": str(root / "x.db")},
            )
        )
        self.assertEqual("allow", rec.verdict)
        self.assertIs(InvocationStatus.EXECUTED, rec.status)
        self.assertTrue(rec.executed)
        self.assertEqual(1, spy.calls)
        self.assertIsNotNone(rec.result)
        assert rec.result is not None
        self.assertTrue(rec.result.success)

    def test_fake_security_allow_empty_allowlist(self) -> None:
        spy = SpyTool(
            ToolSpec(name="net", description="n", requires_network=True)
        )
        fake = _FakeSecurity(network="allow")
        invoker = GatedToolInvoker(
            _registry_with(spy),
            security=fake,
            execution_allowlist=(),
        )
        rec = invoker.request(ToolInvocationRequest(caller="t", tool_name="net"))
        self.assertEqual("allow", rec.verdict)
        self.assertFalse(rec.executed)
        self.assertEqual(0, spy.calls)
        self.assertEqual(1, fake.network_calls)


class ApprovalTest(unittest.TestCase):
    def test_approval_does_not_execute(self) -> None:
        spy = SpyTool(
            ToolSpec(
                name="deploy",
                description="ship",
                makes_permanent_change=True,
            )
        )
        invoker = GatedToolInvoker(
            _registry_with(spy),
            security=PolicySecurityAgent(),
            execution_allowlist=("deploy",),
        )
        rec = invoker.request(
            ToolInvocationRequest(caller="test", tool_name="deploy")
        )
        self.assertIs(InvocationStatus.PENDING_APPROVAL, rec.status)
        approved = invoker.record_human_approval(rec.invocation_id)
        self.assertTrue(approved.approved)
        self.assertFalse(approved.executed)
        self.assertEqual(0, spy.calls)
        self.assertEqual(0, invoker.pending_approvals)
        self.assertEqual(0, invoker.executed_count)

    def test_execute_approved_runs_after_stamp(self) -> None:
        spy = SpyTool(
            ToolSpec(
                name="deploy",
                description="ship",
                makes_permanent_change=True,
            )
        )
        invoker = GatedToolInvoker(
            _registry_with(spy),
            security=PolicySecurityAgent(),
            execution_allowlist=("deploy",),
        )
        rec = invoker.request(
            ToolInvocationRequest(caller="test", tool_name="deploy")
        )
        invoker.record_human_approval(rec.invocation_id)
        self.assertEqual(0, spy.calls)
        executed = invoker.execute_approved(rec.invocation_id)
        self.assertTrue(executed.executed)
        self.assertTrue(executed.approved)
        self.assertEqual(1, spy.calls)
        self.assertEqual(1, invoker.executed_count)

    def test_unknown_approval_id_fail_closed(self) -> None:
        invoker = GatedToolInvoker(
            InMemoryToolRegistry(),
            security=PolicySecurityAgent(),
        )
        rec = invoker.record_human_approval("no-such-id")
        self.assertFalse(rec.executed)
        self.assertIs(InvocationStatus.DENIED, rec.status)


class OfflineAndImportTest(unittest.TestCase):
    def test_request_with_sockets_blocked(self) -> None:
        def _block(*_a, **_k):
            raise RuntimeError("network blocked")

        spy = SpyTool(
            ToolSpec(name="net", description="n", requires_network=True)
        )
        invoker = GatedToolInvoker(
            _registry_with(spy),
            security=PolicySecurityAgent(),
        )
        with patch.object(socket, "socket", side_effect=_block), patch.object(
            socket, "create_connection", side_effect=_block
        ), patch.object(socket, "getaddrinfo", side_effect=_block):
            rec = invoker.request(
                ToolInvocationRequest(caller="t", tool_name="net")
            )
        self.assertFalse(rec.executed)
        self.assertIs(InvocationStatus.DENIED, rec.status)

    def test_tools_package_forbidden_imports(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "tools"
        forbidden = {
            "astra.security",
            "subprocess",
            "socket",
            "httpx",
            "requests",
            "dearpygui",
            "ui",
            "astra.coding",
            "astra.testing",
            "astra.evaluation",
            "astra.deployment",
            "astra.plugins",
        }
        for py_file in root.glob("*.py"):
            tree = ast.parse(py_file.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                names: list[str] = []
                if isinstance(node, ast.Import):
                    names = [alias.name for alias in node.names]
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names = [node.module]
                for name in names:
                    for banned in forbidden:
                        self.assertFalse(
                            name == banned or name.startswith(banned + "."),
                            f"{py_file.name} imports {name}",
                        )

    def test_research_does_not_import_tools(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "research"
        for py_file in root.glob("*.py"):
            tree = ast.parse(py_file.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                names: list[str] = []
                if isinstance(node, ast.Import):
                    names = [alias.name for alias in node.names]
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names = [node.module]
                for name in names:
                    self.assertFalse(
                        name == "astra.tools" or name.startswith("astra.tools."),
                        f"{py_file.name} imports {name}",
                    )

    def test_only_gate_sets_token(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "tools"
        gate_text = (root / "gate.py").read_text(encoding="utf-8")
        self.assertIn("GATE_TOKEN.set", gate_text)
        for py_file in root.glob("*.py"):
            if py_file.name == "gate.py":
                continue
            self.assertNotIn(
                "GATE_TOKEN.set",
                py_file.read_text(encoding="utf-8"),
                f"{py_file.name} must not set GATE_TOKEN",
            )

    def test_no_eval_exec_subprocess_in_tools(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "tools"
        for py_file in root.glob("*.py"):
            text = py_file.read_text(encoding="utf-8")
            self.assertNotIn("subprocess", text)
            self.assertNotIn("os.system", text)
            tree = ast.parse(text)
            for node in ast.walk(tree):
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
                    self.assertNotIn(node.func.id, {"eval", "exec"})

    def test_network_sentinel_is_not_fetched(self) -> None:
        self.assertTrue(TOOL_NETWORK_SENTINEL.startswith("https://"))
        self.assertIn(".invalid", TOOL_NETWORK_SENTINEL)


class ControllerToolsTest(unittest.TestCase):
    def _ctrl(self):
        from astra.configuration.settings import (
            AstraSettings,
            KnowledgeSettings,
            LoggingSettings,
            MemorySettings,
            PlanningSettings,
        )
        from astra.core.astra_controller import AstraController

        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        settings = AstraSettings(
            memory=MemorySettings(
                database_path=root / "mem.db",
                vector_index_path=root / "vectors.faiss",
            ),
            knowledge=KnowledgeSettings(database_path=root / "knowledge.db"),
            planning=PlanningSettings(database_path=root / "planning.db"),
            logging=LoggingSettings(directory=root / "logs"),
        )
        return AstraController(settings)

    def test_tools_before_start_raises(self) -> None:
        ctrl = self._ctrl()
        with self.assertRaises(ControllerNotStartedError):
            _ = ctrl.tools
        with self.assertRaises(ControllerNotStartedError):
            ctrl.request_tool("t", "x")

    def test_start_registers_production_tools(self) -> None:
        from astra.tools.allowlist import AGENT_EXECUTION_ALLOWLIST, PRODUCTION_EXECUTION_ALLOWLIST

        ctrl = self._ctrl()
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        names = frozenset(s.name for s in ctrl.tools.specs())
        self.assertTrue(PRODUCTION_EXECUTION_ALLOWLIST <= names)
        self.assertEqual(AGENT_EXECUTION_ALLOWLIST, names)
        self.assertEqual(
            AGENT_EXECUTION_ALLOWLIST,
            ctrl._tool_invoker.execution_allowlist,
        )
        rec = ctrl.request_tool("test", "anything")
        self.assertFalse(rec.executed)
        state = ctrl.get_state()
        self.assertEqual(6, state.schema_version)
        self.assertEqual(len(AGENT_EXECUTION_ALLOWLIST), state.tools.executable_tool_count)
        self.assertEqual(len(AGENT_EXECUTION_ALLOWLIST), state.tools.registered_tool_count)
        self.assertEqual(0, state.tools.executed_count)
        tools_mod = next(m for m in state.modules if m.module == "astra.tools")
        from astra.state import CapabilityStatus

        exec_cap = next(c for c in tools_mod.capabilities if c.name == "executable_tools")
        self.assertEqual(CapabilityStatus.AVAILABLE, exec_cap.status)
        gate_cap = next(c for c in tools_mod.capabilities if c.name == "gated_invocation")
        self.assertEqual(CapabilityStatus.OPERATIONAL, gate_cap.status)

    def test_dispatch_tools_is_not_an_agent(self) -> None:
        ctrl = self._ctrl()
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        self.assertNotIn("tools", ctrl.agent_names)
        with self.assertRaises(AgentNotFoundError):
            ctrl.dispatch("tools", AgentRequest(objective="run"))

    def test_shutdown_idempotent(self) -> None:
        ctrl = self._ctrl()
        ctrl.start()
        ctrl.shutdown()
        ctrl.shutdown()
        with self.assertRaises(ControllerNotStartedError):
            _ = ctrl.tools

    def test_controller_approval_does_not_execute(self) -> None:
        spy = SpyTool(
            ToolSpec(
                name="deploy",
                description="ship",
                makes_permanent_change=True,
            )
        )
        registry = _registry_with(spy)
        from astra.configuration.settings import (
            AstraSettings,
            KnowledgeSettings,
            LoggingSettings,
            MemorySettings,
            PlanningSettings,
        )
        from astra.core.astra_controller import AstraController

        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        settings = AstraSettings(
            memory=MemorySettings(
                database_path=root / "mem.db",
                vector_index_path=root / "vectors.faiss",
            ),
            knowledge=KnowledgeSettings(database_path=root / "knowledge.db"),
            planning=PlanningSettings(database_path=root / "planning.db"),
            logging=LoggingSettings(directory=root / "logs"),
        )
        ctrl = AstraController(settings, tools_factory=lambda _s: registry)
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        rec = ctrl.request_tool("test", "deploy")
        self.assertFalse(rec.executed)
        approved = ctrl.record_human_approval(rec.invocation_id)
        self.assertTrue(approved.approved)
        self.assertFalse(approved.executed)
        self.assertEqual(0, spy.calls)
        self.assertEqual(0, ctrl.get_state().tools.executed_count)

    def test_milestone_check_not_hard_coded_true(self) -> None:
        from astra.presentation import progress

        src = inspect.getsource(progress._check_tools_interface)
        self.assertIn("InMemoryToolRegistry", src)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_tools_interface())
        b017 = inspect.getsource(progress._check_read_text_tool)
        self.assertIn("ReadTextTool", b017)
        self.assertNotIn("return True", b017)
        self.assertTrue(progress._check_read_text_tool())
        b018 = inspect.getsource(progress._check_read_project_tool)
        self.assertIn("ReadProjectTool", b018)
        self.assertNotIn("return True", b018)
        self.assertTrue(progress._check_read_project_tool())


class ReadTextToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.tool = ReadTextTool(read_roots=(self.root,))

    def _write(self, name: str, data: bytes) -> Path:
        path = self.root / name
        path.write_bytes(data)
        return path

    def test_spec(self) -> None:
        spec = self.tool.spec
        self.assertEqual("read_text", spec.name)
        self.assertFalse(spec.requires_network)
        self.assertFalse(spec.makes_permanent_change)
        self.assertFalse(spec.reads_files)
        self.assertIn("path", spec.parameters)

    def test_unknown_arguments_fail_closed(self) -> None:
        path = self._write("a.txt", b"hi")
        result = self.tool.invoke(path=str(path), max_bytes=99)
        self.assertFalse(result.success)
        self.assertEqual(0, result.output.count("hi") if result.output else 0)

    def test_missing_path_fail_closed(self) -> None:
        result = self.tool.invoke()
        self.assertFalse(result.success)

    def test_empty_path_fail_closed(self) -> None:
        result = self.tool.invoke(path="  ")
        self.assertFalse(result.success)

    def test_missing_file_fail_closed(self) -> None:
        result = self.tool.invoke(path=str(self.root / "nope.txt"))
        self.assertFalse(result.success)

    def test_directory_fail_closed(self) -> None:
        result = self.tool.invoke(path=str(self.root))
        self.assertFalse(result.success)

    def test_db_suffix_rejected(self) -> None:
        path = self._write("secret.db", b"not a database dump")
        result = self.tool.invoke(path=str(path))
        self.assertFalse(result.success)
        self.assertNotIn("not a database dump", result.output)

    def test_binary_rejected(self) -> None:
        path = self._write("x.txt", b"ok\0nope")
        result = self.tool.invoke(path=str(path))
        self.assertFalse(result.success)

    def test_output_cap_and_no_mutation(self) -> None:
        payload = ("A" * (MAX_BYTES + 50)).encode("utf-8")
        path = self._write("big.txt", payload)
        before = path.read_bytes()
        result = self.tool.invoke(path=str(path))
        self.assertTrue(result.success)
        self.assertLessEqual(len(result.output.encode("utf-8")), MAX_BYTES)
        self.assertTrue(result.details.get("truncated"))
        self.assertEqual(before, path.read_bytes())

    def test_path_escape_rejected(self) -> None:
        result = self.tool.invoke(path=str(self.root / ".." / "outside.txt"))
        self.assertFalse(result.success)

    def test_reads_utf8(self) -> None:
        path = self._write("note.txt", "hello-read-text".encode("utf-8"))
        result = self.tool.invoke(path=str(path))
        self.assertTrue(result.success)
        self.assertEqual("hello-read-text", result.output)


class ReadTextGatedTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.target = self.root / "ok.txt"
        self.target.write_text("once", encoding="utf-8")
        self.registry = InMemoryToolRegistry()
        self.registry.register(ReadTextTool(read_roots=(self.root,)))
        self.security = PolicySecurityAgent(write_roots=(self.root,))
        self.invoker = GatedToolInvoker(
            self.registry,
            security=self.security,
            execution_allowlist=PRODUCTION_EXECUTION_ALLOWLIST,
        )

    def test_security_consulted_and_executes_once(self) -> None:
        before = self.security.checks_total
        rec = self.invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="read_text",
                arguments={"path": str(self.target)},
            )
        )
        self.assertTrue(rec.executed)
        self.assertEqual(1, self.invoker.executed_count)
        self.assertGreater(self.security.checks_total, before)
        self.assertIsNotNone(rec.result)
        assert rec.result is not None
        self.assertEqual("once", rec.result.output)

    def test_outside_root_denied(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="read_text",
                arguments={"path": str(self.root / ".." / "escape.txt")},
            )
        )
        self.assertFalse(rec.executed)
        self.assertEqual("deny", rec.verdict)

    def test_direct_invoke_cannot_bypass(self) -> None:
        tool = self.registry.get("read_text")
        assert tool is not None
        result = tool.invoke(path=str(self.target))
        self.assertFalse(result.success)
        self.assertNotEqual("once", result.output)

    def test_unrelated_tool_not_executable(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(caller="test", tool_name="shell")
        )
        self.assertFalse(rec.executed)
        self.assertIs(InvocationStatus.DENIED, rec.status)


class ControllerReadTextTest(unittest.TestCase):
    def test_request_tool_reads_in_root_and_denies_outside(self) -> None:
        from astra.configuration.paths import project_root
        from astra.configuration.settings import (
            AstraSettings,
            KnowledgeSettings,
            LoggingSettings,
            MemorySettings,
            PlanningSettings,
        )
        from astra.core.astra_controller import AstraController

        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        logs = root / "logs"
        logs.mkdir()
        target = logs / "note.txt"
        target.write_text("controller-once", encoding="utf-8")
        before = target.read_bytes()
        settings = AstraSettings(
            memory=MemorySettings(
                database_path=root / "mem.db",
                vector_index_path=root / "vectors.faiss",
            ),
            knowledge=KnowledgeSettings(database_path=root / "knowledge.db"),
            planning=PlanningSettings(database_path=root / "planning.db"),
            logging=LoggingSettings(directory=logs),
        )
        ctrl = AstraController(settings)
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        rec = ctrl.request_tool(
            "test", "read_text", {"path": str(target)}
        )
        self.assertTrue(rec.executed)
        assert rec.result is not None
        self.assertEqual("controller-once", rec.result.output)
        self.assertEqual(before, target.read_bytes())
        self.assertEqual(1, ctrl.get_state().tools.executed_count)

        outside = project_root() / "CONSTITUTION.md"
        denied = ctrl.request_tool(
            "test", "read_text", {"path": str(outside)}
        )
        self.assertFalse(denied.executed)
        self.assertEqual("deny", denied.verdict)
        self.assertEqual(1, ctrl.get_state().tools.executed_count)

        malformed = ctrl.request_tool(
            "test", "read_text", {"path": str(target), "extra": "no"}
        )
        self.assertTrue(malformed.executed)
        assert malformed.result is not None
        self.assertFalse(malformed.result.success)


class ReadsFilesGateMappingTest(unittest.TestCase):
    def test_reads_files_uses_check_file_read_not_write(self) -> None:
        spy = SpyTool(
            ToolSpec(
                name="read_project",
                description="r",
                reads_files=True,
            )
        )
        fake = _FakeSecurity(file_read="allow", file_write="deny")
        invoker = GatedToolInvoker(
            _registry_with(spy),
            security=fake,
            execution_allowlist={"read_project"},
        )
        rec = invoker.request(
            ToolInvocationRequest(
                caller="t",
                tool_name="read_project",
                arguments={"path": "mod.py"},
            )
        )
        self.assertEqual(1, fake.file_read_calls)
        self.assertEqual(0, fake.file_calls)
        self.assertTrue(rec.executed)
        self.assertEqual(1, spy.calls)

    def test_read_text_still_uses_check_file_write(self) -> None:
        spy = SpyTool(
            ToolSpec(name="read_text", description="r", reads_files=False)
        )
        fake = _FakeSecurity(file_write="allow", file_read="deny")
        invoker = GatedToolInvoker(
            _registry_with(spy),
            security=fake,
            execution_allowlist={"read_text"},
        )
        rec = invoker.request(
            ToolInvocationRequest(
                caller="t",
                tool_name="read_text",
                arguments={"path": "note.txt"},
            )
        )
        self.assertEqual(1, fake.file_calls)
        self.assertEqual(0, fake.file_read_calls)
        self.assertTrue(rec.executed)

    def test_missing_check_file_read_fails_closed(self) -> None:
        class _NoRead:
            def check_network_access(self, url: str) -> _AllowDecision:
                return _AllowDecision("deny", "n")

            def check_file_write(self, path: Path) -> _AllowDecision:
                return _AllowDecision("allow", "w")

            def check_permanent_change(self, description: str) -> _AllowDecision:
                return _AllowDecision("deny", "p")

        spy = SpyTool(
            ToolSpec(name="read_project", description="r", reads_files=True)
        )
        invoker = GatedToolInvoker(
            _registry_with(spy),
            security=_NoRead(),
            execution_allowlist={"read_project"},
        )
        rec = invoker.request(
            ToolInvocationRequest(
                caller="t",
                tool_name="read_project",
                arguments={"path": "mod.py"},
            )
        )
        self.assertFalse(rec.executed)
        self.assertEqual(0, spy.calls)


class ReadProjectToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "data").mkdir()
        (self.root / ".git").mkdir()
        self.tool = ReadProjectTool(
            read_roots=(self.root,),
            exclusions=(self.root / "data", self.root / ".git", self.root / "logs"),
        )

    def _write(self, rel: str, data: bytes) -> Path:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def test_spec(self) -> None:
        spec = self.tool.spec
        self.assertEqual("read_project", spec.name)
        self.assertTrue(spec.reads_files)
        self.assertFalse(spec.requires_network)
        self.assertFalse(spec.makes_permanent_change)
        self.assertIn("path", spec.parameters)

    def test_reads_python(self) -> None:
        path = self._write("mod.py", b"print('ok')\n")
        result = self.tool.invoke(path=str(path))
        self.assertTrue(result.success)
        self.assertEqual("print('ok')\n", result.output)

    def test_unknown_arguments_fail_closed(self) -> None:
        path = self._write("mod.py", b"x")
        result = self.tool.invoke(path=str(path), extra="no")
        self.assertFalse(result.success)

    def test_directory_not_listed(self) -> None:
        result = self.tool.invoke(path=str(self.root))
        self.assertFalse(result.success)

    def test_excluded_data_denied(self) -> None:
        path = self._write("data/note.md", b"secret-store")
        result = self.tool.invoke(path=str(path))
        self.assertFalse(result.success)
        self.assertNotIn("secret-store", result.output)

    def test_env_denied(self) -> None:
        path = self._write(".env", b"SECRET=1")
        result = self.tool.invoke(path=str(path))
        self.assertFalse(result.success)
        self.assertNotIn("SECRET=1", result.output)

    def test_git_denied(self) -> None:
        path = self._write(".git/config", b"[core]\n")
        result = self.tool.invoke(path=str(path))
        self.assertFalse(result.success)

    def test_pem_denied(self) -> None:
        path = self._write("cert.pem", b"-----BEGIN")
        result = self.tool.invoke(path=str(path))
        self.assertFalse(result.success)

    def test_binary_rejected(self) -> None:
        path = self._write("x.py", b"ok\0nope")
        result = self.tool.invoke(path=str(path))
        self.assertFalse(result.success)

    def test_output_cap_and_no_mutation(self) -> None:
        payload = ("A" * (MAX_BYTES + 50)).encode("utf-8")
        path = self._write("big.py", payload)
        before = path.read_bytes()
        result = self.tool.invoke(path=str(path))
        self.assertTrue(result.success)
        self.assertLessEqual(len(result.output.encode("utf-8")), MAX_BYTES)
        self.assertTrue(result.details.get("truncated"))
        self.assertEqual(before, path.read_bytes())

    def test_path_escape_rejected(self) -> None:
        result = self.tool.invoke(path=str(self.root / ".." / "outside.py"))
        self.assertFalse(result.success)

    def test_symlink_escape_denied(self) -> None:
        outside = Path(self.tmp.name).parent / "b018-outside.py"
        outside.write_text("escaped", encoding="utf-8")
        self.addCleanup(lambda: outside.unlink(missing_ok=True))
        link = self.root / "link.py"
        try:
            link.symlink_to(outside)
        except OSError:
            self.skipTest("symlink creation not permitted")
        result = self.tool.invoke(path=str(link))
        self.assertFalse(result.success)
        self.assertNotEqual("escaped", result.output)


class ReadProjectGatedTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.target = self.root / "once.py"
        self.target.write_text("once", encoding="utf-8")
        self.registry = InMemoryToolRegistry()
        self.registry.register(
            ReadProjectTool(read_roots=(self.root,), exclusions=())
        )
        self.security = PolicySecurityAgent(
            read_roots=(self.root,),
            write_roots=(),
        )
        self.invoker = GatedToolInvoker(
            self.registry,
            security=self.security,
            execution_allowlist=PRODUCTION_EXECUTION_ALLOWLIST,
        )

    def test_security_consulted_and_executes_once(self) -> None:
        before = self.security.checks_total
        rec = self.invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="read_project",
                arguments={"path": str(self.target)},
            )
        )
        self.assertTrue(rec.executed)
        self.assertEqual(1, self.invoker.executed_count)
        self.assertGreater(self.security.checks_total, before)
        assert rec.result is not None
        self.assertEqual("once", rec.result.output)

    def test_outside_root_denied(self) -> None:
        rec = self.invoker.request(
            ToolInvocationRequest(
                caller="test",
                tool_name="read_project",
                arguments={"path": str(self.root / ".." / "escape.py")},
            )
        )
        self.assertFalse(rec.executed)
        self.assertEqual("deny", rec.verdict)

    def test_direct_invoke_cannot_bypass(self) -> None:
        tool = self.registry.get("read_project")
        assert tool is not None
        result = tool.invoke(path=str(self.target))
        self.assertFalse(result.success)
        self.assertNotEqual("once", result.output)


class ControllerReadProjectTest(unittest.TestCase):
    def test_request_tool_reads_in_repo_and_denies_excluded(self) -> None:
        from astra.configuration.paths import project_root
        from astra.configuration.settings import (
            AstraSettings,
            KnowledgeSettings,
            LoggingSettings,
            MemorySettings,
            PlanningSettings,
        )
        from astra.core.astra_controller import AstraController

        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        settings = AstraSettings(
            memory=MemorySettings(
                database_path=root / "mem.db",
                vector_index_path=root / "vectors.faiss",
            ),
            knowledge=KnowledgeSettings(database_path=root / "knowledge.db"),
            planning=PlanningSettings(database_path=root / "planning.db"),
            logging=LoggingSettings(directory=root / "logs"),
        )
        ctrl = AstraController(settings)
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        target = project_root() / "astra" / "coding" / "__init__.py"
        before = target.read_bytes()
        rec = ctrl.request_tool("test", "read_project", {"path": str(target)})
        self.assertTrue(rec.executed)
        assert rec.result is not None
        self.assertIn("Coding Agent", rec.result.output)
        self.assertEqual(before, target.read_bytes())
        self.assertEqual(1, ctrl.get_state().tools.executed_count)

        constitution = project_root() / "CONSTITUTION.md"
        doc = ctrl.request_tool(
            "test", "read_project", {"path": str(constitution)}
        )
        self.assertTrue(doc.executed)
        assert doc.result is not None
        self.assertTrue(doc.result.success)

        via_text = ctrl.request_tool(
            "test", "read_text", {"path": str(target)}
        )
        self.assertFalse(via_text.executed)

        excluded = ctrl.request_tool(
            "test", "read_project", {"path": str(project_root() / ".env")}
        )
        self.assertFalse(excluded.executed)
        self.assertEqual("deny", excluded.verdict)

        outside = project_root().parent / "not-astra-b018.py"
        denied = ctrl.request_tool(
            "test", "read_project", {"path": str(outside)}
        )
        self.assertFalse(denied.executed)
        self.assertEqual("deny", denied.verdict)


class RealPolicyNeverAllowPermanent(unittest.TestCase):
    def test_permanent_still_never_allow(self) -> None:
        agent = PolicySecurityAgent()
        d = agent.check_permanent_change("via tools")
        self.assertIsNot(Verdict.ALLOW, d.verdict)


if __name__ == "__main__":
    unittest.main()
