"""Coding Agent tests (B-019, ADR-0018).

Proposal-only. Reads go through ToolInvoker / read_project.
"""

from __future__ import annotations

import ast
import inspect
import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from astra.coding.agent import LocalCodingAgent
from astra.coding.contracts import ChangeProposal, CodingAgent, FileChange
from astra.core.contracts import AgentRequest
from astra.core.lifecycle import MeasurableProperty
from astra.tools.contracts import (
    InvocationStatus,
    ToolInvocationRecord,
    ToolInvocationRequest,
    ToolResult,
)


class _FakeLLM:
    def __init__(self, text: str = "<<<FILE target.py\nmarker-b019\n>>>END\n") -> None:
        self.text = text
        self.calls = 0

    def is_available(self) -> bool:
        return True

    def generate(self, prompt: str, options: object = None) -> str:
        self.calls += 1
        return self.text


class _DownLLM(_FakeLLM):
    def is_available(self) -> bool:
        return False


class _FakeInvoker:
    def __init__(self, output: str = "print(1)\n", *, truncated: bool = False) -> None:
        self.calls: list[ToolInvocationRequest] = []
        self.output = output
        self.truncated = truncated
        self.deny = False

    def request(self, req: ToolInvocationRequest) -> ToolInvocationRecord:
        self.calls.append(req)
        if self.deny or req.tool_name != "read_project":
            return ToolInvocationRecord(
                invocation_id="x",
                request=req,
                spec_name=req.tool_name,
                verdict="deny",
                reason="denied",
                status=InvocationStatus.DENIED,
                executed=False,
            )
        details = {"truncated": self.truncated}
        return ToolInvocationRecord(
            invocation_id="x",
            request=req,
            spec_name="read_project",
            verdict="allow",
            reason="ok",
            status=InvocationStatus.EXECUTED,
            executed=True,
            result=ToolResult(
                success=True,
                output=self.output,
                details=details,
            ),
        )


class ContractTest(unittest.TestCase):
    def test_is_coding_agent(self) -> None:
        self.assertTrue(issubclass(LocalCodingAgent, CodingAgent))
        self.assertEqual("coding", LocalCodingAgent.name)


class ProposeTest(unittest.TestCase):
    def test_proposes_via_read_project_once(self) -> None:
        invoker = _FakeInvoker()
        llm = _FakeLLM()
        agent = LocalCodingAgent(tools=invoker, llm=llm)
        proposal = agent.propose_change(
            "improve maintainability",
            (Path("target.py"),),
        )
        self.assertEqual(1, len(invoker.calls))
        self.assertEqual("read_project", invoker.calls[0].tool_name)
        self.assertEqual("coding", invoker.calls[0].caller)
        self.assertEqual(1, llm.calls)
        self.assertEqual(1, len(proposal.changes))
        self.assertIn("marker-b019", proposal.changes[0].new_content)
        self.assertTrue(proposal.improves)
        self.assertEqual("generated", agent.last_proposal_status)

    def test_none_targets_makes_zero_requests(self) -> None:
        invoker = _FakeInvoker()
        agent = LocalCodingAgent(tools=invoker, llm=_FakeLLM())
        proposal = agent.propose_change("improve maintainability", None)
        self.assertEqual(0, len(invoker.calls))
        self.assertEqual((), proposal.changes)
        self.assertTrue(any("targets required" in r for r in proposal.risks))
        self.assertTrue(proposal.improves)

    def test_missing_invoker_fail_closed(self) -> None:
        agent = LocalCodingAgent(tools=None, llm=_FakeLLM())
        proposal = agent.propose_change("improve maintainability", (Path("t.py"),))
        self.assertEqual((), proposal.changes)
        self.assertTrue(any("ToolInvoker missing" in r for r in proposal.risks))

    def test_denied_read_omits_file(self) -> None:
        invoker = _FakeInvoker()
        invoker.deny = True
        agent = LocalCodingAgent(tools=invoker, llm=_FakeLLM())
        proposal = agent.propose_change("improve security", (Path("t.py"),))
        self.assertEqual((), proposal.changes)
        self.assertEqual("failed", agent.last_proposal_status)

    def test_truncated_omits_file(self) -> None:
        invoker = _FakeInvoker(truncated=True)
        agent = LocalCodingAgent(tools=invoker, llm=_FakeLLM())
        proposal = agent.propose_change("improve maintainability", (Path("t.py"),))
        self.assertEqual((), proposal.changes)
        self.assertTrue(any("truncated" in r for r in proposal.risks))

    def test_llm_unavailable_no_identity_change(self) -> None:
        invoker = _FakeInvoker()
        agent = LocalCodingAgent(tools=invoker, llm=_DownLLM())
        proposal = agent.propose_change("improve maintainability", (Path("t.py"),))
        self.assertEqual(1, len(invoker.calls))
        self.assertEqual((), proposal.changes)
        self.assertTrue(any("LLM unavailable" in r for r in proposal.risks))

    def test_noop_omitted(self) -> None:
        invoker = _FakeInvoker(output="same\n")
        llm = _FakeLLM("<<<FILE target.py\nsame\n>>>END\n")
        agent = LocalCodingAgent(tools=invoker, llm=llm)
        proposal = agent.propose_change("improve simplicity", (Path("target.py"),))
        self.assertEqual((), proposal.changes)

    def test_extra_targets_capped(self) -> None:
        invoker = _FakeInvoker()
        llm = _FakeLLM("<<<FILE a.py\nnew\n>>>END\n")
        paths = tuple(Path(f"{i}.py") for i in "abcdef")
        agent = LocalCodingAgent(tools=invoker, llm=llm)
        agent.propose_change("improve maintainability", paths)
        self.assertEqual(3, len(invoker.calls))

    def test_review_flags_empty_improves(self) -> None:
        agent = LocalCodingAgent()
        objections = agent.review(
            ChangeProposal(objective="x", changes=(), improves=())
        )
        self.assertTrue(any("improves" in o for o in objections))

    def test_run_malformed_targets(self) -> None:
        agent = LocalCodingAgent(tools=_FakeInvoker(), llm=_FakeLLM())
        result = agent.run(
            AgentRequest(objective="improve reliability", context={"targets": 3})
        )
        self.assertFalse(result.success)
        self.assertIs(False, result.details.get("applied"))

    def test_run_empty_objective(self) -> None:
        agent = LocalCodingAgent(tools=_FakeInvoker(), llm=_FakeLLM())
        result = agent.run(AgentRequest(objective="  "))
        self.assertFalse(result.success)

    def test_run_never_claims_applied(self) -> None:
        llm = _FakeLLM()
        agent = LocalCodingAgent(tools=_FakeInvoker(), llm=llm)
        result = agent.run(
            AgentRequest(
                objective="improve maintainability",
                context={"targets": [Path("target.py")]},
            )
        )
        self.assertTrue(result.success)
        self.assertIs(False, result.details.get("applied"))
        self.assertNotIn("new_content", result.details)
        self.assertIsNotNone(agent.last_proposal)
        self.assertEqual(1, llm.calls)
        self.assertTrue(agent.last_change_paths)

    def test_run_reviews_once_and_retains(self) -> None:
        class _CountReview(LocalCodingAgent):
            def __init__(self, *args: object, **kwargs: object) -> None:
                super().__init__(*args, **kwargs)
                self.review_calls = 0

            def review(self, proposal):  # type: ignore[no-untyped-def]
                self.review_calls += 1
                return super().review(proposal)

        llm = _FakeLLM()
        agent = _CountReview(tools=_FakeInvoker(), llm=llm)
        result = agent.run(
            AgentRequest(
                objective="improve maintainability",
                context={"targets": [Path("target.py")]},
            )
        )
        self.assertEqual(1, agent.review_calls)
        self.assertEqual(1, llm.calls)
        self.assertIsNotNone(agent.last_proposal)
        assert agent.last_proposal is not None
        self.assertIn("marker-b019", agent.last_proposal.changes[0].new_content)
        self.assertIs(False, result.details.get("applied"))
        self.assertIn("target.py", result.details.get("change_paths", ()))
        self.assertNotIn("new_content", result.details)
        self.assertNotEqual("applied", agent.last_proposal_status)
        self.assertNotEqual("approved", agent.last_proposal_status)

    def test_run_none_targets_retains_empty_proposal(self) -> None:
        llm = _FakeLLM()
        invoker = _FakeInvoker()
        agent = LocalCodingAgent(tools=invoker, llm=llm)
        result = agent.run(AgentRequest(objective="improve maintainability"))
        self.assertEqual(0, len(invoker.calls))
        self.assertEqual(0, llm.calls)
        self.assertIsNotNone(agent.last_proposal)
        assert agent.last_proposal is not None
        self.assertEqual((), agent.last_proposal.changes)
        self.assertIs(False, result.details.get("applied"))
        self.assertTrue(agent.last_objections or agent.last_proposal.risks)

    def test_propose_change_retains_without_review_loop(self) -> None:
        class _CountReview(LocalCodingAgent):
            def __init__(self, *args: object, **kwargs: object) -> None:
                super().__init__(*args, **kwargs)
                self.review_calls = 0

            def review(self, proposal):  # type: ignore[no-untyped-def]
                self.review_calls += 1
                return super().review(proposal)

        agent = _CountReview(tools=_FakeInvoker(), llm=_FakeLLM())
        proposal = agent.propose_change(
            "improve maintainability", (Path("target.py"),)
        )
        self.assertEqual(0, agent.review_calls)
        self.assertIs(proposal, agent.last_proposal)

    def test_preview_is_bounded(self) -> None:
        llm = _FakeLLM("<<<FILE target.py\n" + ("x" * 5000) + "\n>>>END\n")
        agent = LocalCodingAgent(tools=_FakeInvoker(), llm=llm)
        agent.propose_change("improve maintainability", (Path("target.py"),))
        self.assertLessEqual(len(agent.last_preview), 2048)


class IsolationTest(unittest.TestCase):
    def test_no_forbidden_imports_or_writes(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "coding"
        forbidden = {
            "astra.security",
            "astra.tools.registry",
            "astra.tools.guard",
            "subprocess",
            "socket",
            "httpx",
            "requests",
            "dearpygui",
            "ui",
        }
        for py_file in root.glob("*.py"):
            text = py_file.read_text(encoding="utf-8")
            self.assertNotIn("write_text", text)
            self.assertNotIn("write_bytes", text)
            self.assertNotIn("os.system", text)
            self.assertNotIn(".read_text(", text)
            self.assertNotIn("list_directory", text)
            self.assertNotIn("GATE_TOKEN", text)
            tree = ast.parse(text)
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
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
                    self.assertNotIn(node.func.id, {"eval", "exec"})
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
                    self.assertNotEqual("write_text", node.func.attr)
                    self.assertNotEqual("write_bytes", node.func.attr)

    def test_offline(self) -> None:
        def _block(*_a, **_k):
            raise RuntimeError("network blocked")

        with patch.object(socket, "socket", side_effect=_block), patch.object(
            socket, "create_connection", side_effect=_block
        ), patch.object(socket, "getaddrinfo", side_effect=_block):
            agent = LocalCodingAgent(tools=_FakeInvoker(), llm=_FakeLLM())
            proposal = agent.propose_change(
                "improve maintainability", (Path("target.py"),)
            )
        self.assertTrue(proposal.changes)

    def test_milestone_not_hard_coded_true(self) -> None:
        from astra.presentation import progress

        src = inspect.getsource(progress._check_coding_agent)
        self.assertIn("LocalCodingAgent", src)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_coding_agent())

    def test_b020_milestone_not_hard_coded_true(self) -> None:
        from astra.presentation import progress

        src = inspect.getsource(progress._check_proposal_inspectability)
        self.assertIn("last_proposal", src)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_proposal_inspectability())


class ControllerCodingTest(unittest.TestCase):
    def test_dispatch_and_no_mutation(self) -> None:
        from astra.configuration.paths import project_root
        from astra.configuration.settings import (
            AstraSettings,
            KnowledgeSettings,
            LoggingSettings,
            MemorySettings,
            PlanningSettings,
        )
        from astra.core.astra_controller import AstraController
        from astra.core.errors import ControllerNotStartedError

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
        with self.assertRaises(ControllerNotStartedError):
            _ = ctrl.coding
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        self.assertIn("coding", ctrl.agent_names)
        self.assertIsNot(ctrl.coding, ctrl.tools)
        target = project_root() / "astra" / "coding" / "__init__.py"
        before = target.read_bytes()
        result = ctrl.dispatch(
            "coding",
            AgentRequest(
                objective="improve maintainability of the coding package",
                context={"targets": [target]},
            ),
        )
        self.assertEqual("coding", result.agent)
        self.assertIs(False, result.details.get("applied"))
        self.assertEqual(before, target.read_bytes())
        state = ctrl.get_state()
        self.assertEqual(6, state.schema_version)
        self.assertFalse(state.coding.applied)
        self.assertNotEqual("applied", state.coding.last_proposal_status)
        self.assertNotEqual("approved", state.coding.last_proposal_status)
        self.assertNotEqual("tested", state.coding.last_proposal_status)
        self.assertLessEqual(len(state.coding.last_preview), 2048)
        self.assertIsNotNone(ctrl.coding.last_proposal)
        self.assertNotIn("new_content", result.details)
        coding_mod = next(m for m in state.modules if m.module == "astra.coding")
        from astra.state import CapabilityStatus

        self.assertEqual(CapabilityStatus.OPERATIONAL, coding_mod.status)
        ctrl.shutdown()
        ctrl.shutdown()


if __name__ == "__main__":
    unittest.main()
