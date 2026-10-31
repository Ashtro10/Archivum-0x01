"""Production talk-path audit. Real controller, spy LLM only.

Proves what the packaged conversation loop actually calls.
Does not load a GGUF. Does not open Dear PyGui.
"""

from __future__ import annotations

import ast
import tempfile
import unittest
from pathlib import Path

from astra.configuration.settings import (
    AstraSettings,
    KnowledgeSettings,
    MemorySettings,
    PlanningSettings,
)
from astra.core.astra_controller import AstraController
from astra.core.contracts import AgentRequest
from astra.presentation.orchestration import ConversationOrchestrator


ROOT = Path(__file__).resolve().parents[1]


class _SpyLLM:
    def __init__(self) -> None:
        self.prompts: list[str] = []

    def is_available(self) -> bool:
        return True

    def generate(self, prompt: str, options: object = None) -> str:
        self.prompts.append(prompt)
        return "I will remember that."


def _tmp_settings() -> AstraSettings:
    mem_dir = tempfile.mkdtemp()
    know_dir = tempfile.mkdtemp()
    plan_dir = tempfile.mkdtemp()
    vec_dir = tempfile.mkdtemp()
    return AstraSettings(
        memory=MemorySettings(
            database_path=Path(mem_dir) / "mem.db",
            vector_index_path=Path(vec_dir) / "vectors.faiss",
        ),
        knowledge=KnowledgeSettings(
            database_path=Path(know_dir) / "knowledge.db",
        ),
        planning=PlanningSettings(
            database_path=Path(plan_dir) / "planning.db",
        ),
    )


class ProductionTalkPathTest(unittest.TestCase):
    def test_boot_launch_injects_assistant_on_controller_llm(self) -> None:
        tree = ast.parse((ROOT / "astra" / "boot.py").read_text(encoding="utf-8"))
        assigned = []
        for node in ast.walk(tree):
            if isinstance(node, ast.Assign):
                for target in node.targets:
                    if isinstance(target, ast.Name) and target.id == "conversation":
                        assigned.append(ast.unparse(node.value))
        joined = "\n".join(assigned)
        self.assertIn("ConversationOrchestrator", joined)
        self.assertNotIn("AssistantConversation(controller.llm)", joined)

    def test_ask_calls_generate_not_dispatch_memory_research_or_tools(self) -> None:
        spy = _SpyLLM()
        settings = _tmp_settings()
        ctrl = AstraController(settings, llm_factory=lambda _s: spy)
        ctrl.start()
        self.addCleanup(ctrl.shutdown)

        dispatch_names: list[str] = []
        real_dispatch = ctrl.dispatch

        def _dispatch(agent_name: str, request: AgentRequest):
            dispatch_names.append(agent_name)
            return real_dispatch(agent_name, request)

        ctrl.dispatch = _dispatch  # type: ignore[method-assign]

        memory_writes: list[object] = []
        real_mem_store = ctrl.memory.store

        def _mem_store(record):
            memory_writes.append(record)
            return real_mem_store(record)

        ctrl.memory.store = _mem_store  # type: ignore[method-assign]

        knowledge_writes: list[object] = []
        real_know_store = ctrl.knowledge.store

        def _know_store(record):
            knowledge_writes.append(record)
            return real_know_store(record)

        ctrl.knowledge.store = _know_store  # type: ignore[method-assign]

        tool_calls: list[tuple[str, str]] = []
        real_tool = ctrl.request_tool

        def _request_tool(caller: str, tool_name: str, arguments=None):
            tool_calls.append((caller, tool_name))
            return real_tool(caller, tool_name, arguments)

        ctrl.request_tool = _request_tool  # type: ignore[method-assign]

        plan_calls: list[str] = []
        engine = ctrl.planning_engine
        real_plan = engine.plan

        def _plan(objective: str):
            plan_calls.append(objective)
            return real_plan(objective)

        engine.plan = _plan  # type: ignore[method-assign]

        talk = ConversationOrchestrator.from_controller(ctrl)
        reply = talk.ask("How are you?")

        self.assertEqual("success", reply.status)
        self.assertEqual(1, len(spy.prompts))
        self.assertEqual([], dispatch_names)
        self.assertEqual([], knowledge_writes)
        self.assertEqual([], tool_calls)
        self.assertEqual([], plan_calls)

        research = ctrl._agents.get("research")
        self.assertIsNotNone(research)
        run_calls: list[str] = []
        real_run = research.run

        def _run(request: AgentRequest):
            run_calls.append(request.objective)
            return real_run(request)

        research.run = _run  # type: ignore[method-assign]
        talk.ask("What is recursion?")
        self.assertEqual([], run_calls)
        self.assertEqual(2, len(spy.prompts))

    def test_summarise_failure_string_is_research_not_talk(self) -> None:
        text = (ROOT / "astra" / "research" / "agent.py").read_text(encoding="utf-8")
        self.assertIn("no findings to summarise", text)
        talk = (ROOT / "astra" / "presentation" / "conversation.py").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("no findings to summarise", talk)
        self.assertNotIn("summarise", talk)
        boot = (ROOT / "astra" / "boot.py").read_text(encoding="utf-8")
        self.assertNotIn("nothing it can stand behind", boot)
        self.assertIn("ConversationOrchestrator.from_controller", boot)


if __name__ == "__main__":
    unittest.main()
