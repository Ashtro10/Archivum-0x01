"""B-039 production conversation orchestrator path.

Real AstraController + Memory/Knowledge/Planning stores.
Spy LLM only (no GGUF). Spies on dispatch/plan/request_tool to prove routing.
"""

from __future__ import annotations

import ast
import tempfile
import unittest
from pathlib import Path

from astra.configuration.settings import (
    AstraSettings,
    KnowledgeSettings,
    LoggingSettings,
    MemorySettings,
    PlanningSettings,
)
from astra.core.astra_controller import AstraController
from astra.core.contracts import AgentRequest
from astra.knowledge.contracts import KnowledgeCategory, KnowledgeRecord, KnowledgeVerification
from astra.planning.contracts import Magnitude, Priority, RiskLevel, Task, TaskStatus
from astra.presentation.orchestration import ConversationOrchestrator, classify_intent


ROOT = Path(__file__).resolve().parents[1]


class _SpyLLM:
    def __init__(self) -> None:
        self.prompts: list[str] = []

    def is_available(self) -> bool:
        return True

    def generate(self, prompt: str, options: object = None) -> str:
        self.prompts.append(prompt)
        if "Relevant memory:" in prompt and "Ada" in prompt:
            return "Your name is Ada."
        return "Understood."


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


class _Harness(unittest.TestCase):
    def setUp(self) -> None:
        self.spy = _SpyLLM()
        self.settings = _settings()
        self.ctrl = AstraController(self.settings, llm_factory=lambda _s: self.spy)
        self.ctrl.start()
        self.addCleanup(self.ctrl.shutdown)
        self.dispatch_names: list[str] = []
        real_dispatch = self.ctrl.dispatch

        def _dispatch(name: str, request: AgentRequest):
            self.dispatch_names.append(name)
            return real_dispatch(name, request)

        self.ctrl.dispatch = _dispatch  # type: ignore[method-assign]
        self.plan_calls: list[str] = []
        engine = self.ctrl.planning_engine
        real_plan = engine.plan

        def _plan(objective: str):
            self.plan_calls.append(objective)
            return real_plan(objective)

        engine.plan = _plan  # type: ignore[method-assign]
        self.tool_calls: list[tuple[str, str]] = []
        real_tool = self.ctrl.request_tool

        def _tool(caller: str, tool_name: str, arguments=None):
            self.tool_calls.append((caller, tool_name))
            return real_tool(caller, tool_name, arguments)

        self.ctrl.request_tool = _tool  # type: ignore[method-assign]
        self.orch = ConversationOrchestrator.from_controller(self.ctrl)


class ProductionEntrypointTest(unittest.TestCase):
    def test_boot_wires_orchestrator_not_direct_llm(self) -> None:
        tree = ast.parse((ROOT / "astra" / "boot.py").read_text(encoding="utf-8"))
        assigned = []
        for node in ast.walk(tree):
            if isinstance(node, ast.Assign):
                for target in node.targets:
                    if isinstance(target, ast.Name) and target.id == "conversation":
                        assigned.append(ast.unparse(node.value))
        joined = "\n".join(assigned)
        self.assertIn("ConversationOrchestrator.from_controller", joined)
        self.assertNotIn("controller.llm", joined)
        self.assertNotIn('dispatch("research"', joined)

    def test_ui_calls_ask_not_generate(self) -> None:
        text = (ROOT / "ui" / "app.py").read_text(encoding="utf-8")
        self.assertIn("self._talk.ask", text)
        self.assertNotIn("generate(", text)
        self.assertIn("_nav_root", text)


class IntentTest(unittest.TestCase):
    def test_normal_vs_research_vs_plan(self) -> None:
        self.assertEqual("conversation", classify_intent("How are you?"))
        self.assertEqual("conversation", classify_intent("What is recursion?"))
        self.assertEqual(
            "research",
            classify_intent("What are the latest developments in local AI models?"),
        )
        self.assertEqual(
            "planning",
            classify_intent("Compare these three approaches, research them, and recommend one."),
        )
        self.assertEqual("memory", classify_intent("What is my name?"))
        self.assertEqual("tool", classify_intent("Read this file and summarize it"))
        self.assertEqual("goal", classify_intent("Remember that I want to learn C"))
        self.assertEqual(
            "knowledge",
            classify_intent("Explain how TCP congestion control works."),
        )


class NormalConversationTest(_Harness):
    def test_production_path_reaches_llm_not_research(self) -> None:
        result = self.orch.process("How are you?")
        self.assertEqual("success", result.status)
        self.assertEqual("Understood.", result.text)
        self.assertEqual("conversation", result.intent)
        self.assertEqual(1, len(self.spy.prompts))
        self.assertEqual([], self.dispatch_names)
        self.assertEqual([], self.plan_calls)
        self.assertEqual([], self.tool_calls)
        self.assertFalse(result.research_used)
        self.assertNotIn("no findings to summarise", result.text.lower())


class MemoryPathTest(_Harness):
    def test_durable_fact_persists_and_retrieves_after_reinit(self) -> None:
        first = self.orch.process("My name is Ada.")
        self.assertEqual("success", first.status)
        stored = [
            record.content
            for record in self.ctrl.memory.iter_records()
            if "Ada" in record.content and "conversation" not in (record.tags or ())
        ]
        self.assertTrue(stored)
        self.ctrl.shutdown()

        spy2 = _SpyLLM()
        ctrl2 = AstraController(self.settings, llm_factory=lambda _s: spy2)
        ctrl2.start()
        self.addCleanup(ctrl2.shutdown)
        orch2 = ConversationOrchestrator.from_controller(ctrl2)
        second = orch2.process("What is my name?")
        self.assertEqual("memory", second.intent)
        self.assertTrue(any("Ada" in item for item in second.memory_retrieved))
        self.assertIn("Ada", spy2.prompts[0])
        self.assertIn("Ada", second.text)

    def test_session_transcript_reloads(self) -> None:
        self.orch.process("Hello there.")
        again = ConversationOrchestrator.from_controller(self.ctrl)
        self.assertTrue(any(turn[0] == "Hello there." for turn in again._turns))
        again.process("Still here?")
        self.assertIn("Hello there.", self.spy.prompts[-1])


class ResearchRoutingTest(_Harness):
    def test_research_selected_only_when_required(self) -> None:
        self.orch.process("What is recursion?")
        self.assertEqual([], self.dispatch_names)
        result = self.orch.process(
            "What are the latest developments in local AI models?"
        )
        self.assertIn("research", self.dispatch_names)
        self.assertTrue(result.research_used)
        self.assertNotIn("no findings to summarise", result.text.lower())
        self.assertNotIn("no findings to summarise", result.activity.lower())


class PlanningRoutingTest(_Harness):
    def test_planning_engine_reached_only_for_multistep(self) -> None:
        self.ctrl.planning.add(
            Task(
                task_id="cmp-1",
                title="Compare local model approaches",
                priority=Priority.P1,
                impact=Magnitude.MEDIUM,
                effort=Magnitude.SMALL,
                risk=RiskLevel.LOW,
                status=TaskStatus.TODO,
                notes="research them and recommend one",
            )
        )
        self.orch.process("How are you?")
        self.assertEqual([], self.plan_calls)
        result = self.orch.process(
            "Compare these three approaches, research them, and recommend one."
        )
        self.assertEqual(1, len(self.plan_calls))
        self.assertTrue(result.planning_used)


class ToolRoutingTest(_Harness):
    def test_authorized_read_and_no_shell_tool(self) -> None:
        note = self.settings.memory.database_path.parent / "note.txt"
        note.write_text("alpha-token-39", encoding="utf-8")
        result = self.orch.process(f"Read this file and summarize it: {note}")
        self.assertTrue(result.tools_used)
        self.assertTrue(set(result.tools_used) <= {"read_text", "read_project"})
        self.assertIn("alpha-token-39", self.spy.prompts[-1])
        denied = self.orch.process("Read this file and summarize it: C:/Windows/System32/drivers/etc/hosts")
        self.assertNotIn("shell", denied.tools_used)
        self.assertNotIn("process", denied.tools_used)


class AgentBoundaryTest(_Harness):
    def test_normal_talk_cannot_dispatch_improvement_agents(self) -> None:
        self.orch.process("How are you?")
        self.orch.process("What is recursion?")
        for name in ("testing", "evaluation", "deployment", "coding", "security"):
            self.assertNotIn(name, self.dispatch_names)
        with self.assertRaises(RuntimeError):
            self.orch._safe_dispatch("testing", AgentRequest(objective="x"))
        src = (ROOT / "astra" / "presentation" / "orchestration.py").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("import ProcessRunner", src)
        self.assertNotIn("from astra.testing", src)
        self.assertNotIn("GitRunner", src)
        self.assertNotIn("import subprocess", src)
        self.assertNotIn("Milestone.complete", src)


class KnowledgeAndGoalsTest(_Harness):
    def test_knowledge_search_when_appropriate(self) -> None:
        self.ctrl.knowledge.store(
            KnowledgeRecord(
                record_id="",
                category=KnowledgeCategory.NETWORKING,
                content="TCP congestion control uses AIMD window adjustment.",
                source="fixture",
                verification=KnowledgeVerification.UNVERIFIED,
            )
        )
        result = self.orch.process("Explain how TCP congestion control works.")
        self.assertEqual("knowledge", result.intent)
        self.assertTrue(result.knowledge_used)
        self.assertIn("AIMD", self.spy.prompts[-1])

    def test_goal_manager_records_learning_intent(self) -> None:
        result = self.orch.process("Remember that I want to learn C.")
        self.assertEqual("goal", result.intent)
        self.assertTrue(result.goals_used)
        goals = list(self.ctrl.goals.list_goals())
        self.assertTrue(any("C" in goal.description for goal in goals))


class SpawnRegressionTest(unittest.TestCase):
    def test_orchestrator_has_no_process_spawn(self) -> None:
        text = (ROOT / "astra" / "presentation" / "orchestration.py").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("sys.executable", text)
        self.assertNotIn("unittest", text)
        self.assertNotIn("CREATE_NO_WINDOW", text)
        frozen = (ROOT / "astra" / "testing" / "runner.py").read_text(encoding="utf-8")
        self.assertIn("frozen executable is not a Python interpreter", frozen)


if __name__ == "__main__":
    unittest.main()
