"""Runtime intelligence: real conversation path, restart, frozen probes.

Exercises ConversationOrchestrator end-to-end. Spy LLM except process/frozen
probes which use the configured backend when present.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from astra.configuration.settings import (
    AstraSettings,
    KnowledgeSettings,
    LoggingSettings,
    MemorySettings,
    PersonalitySettings,
    PlanningSettings,
    ResearchSettings,
)
from astra.core.astra_controller import AstraController
from astra.presentation.context import DEFAULT_PERSONALITY
from astra.presentation.orchestration import (
    ConversationOrchestrator,
    analyze_intent,
    classify_intent,
)
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST


ROOT = Path(__file__).resolve().parents[1]
EXE = ROOT / "dist_windows" / "Primus.exe"
CREATE_NO_WINDOW = 0x08000000


class _SpyLLM:
    def __init__(self, label: str = "A") -> None:
        self.label = label
        self.prompts: list[str] = []

    def is_available(self) -> bool:
        return True

    def generate(self, prompt: str, options: object = None) -> str:
        self.prompts.append(prompt)
        if "Kishore" in prompt and "name" in prompt.lower():
            return "Your name is Kishore."
        if "practical programming examples" in prompt.lower() and "pointer" in prompt.lower():
            return "Here is a practical C pointer example."
        if "UNTRUSTED_WEB_DOCUMENT" in prompt or "External research" in prompt:
            return "From the research evidence: local GGUF runtimes improved."
        if "Existing plan" in prompt or "Proposed backlog" in prompt:
            return "Continuing the C learning plan from the backlog."
        if "Active goals" in prompt or "Suggested next task" in prompt:
            return "Work on the next recorded Primus goal."
        if "File contents:" in prompt:
            return "The file defines PRODUCT_NAME as Primus."
        if "Stored knowledge:" in prompt:
            return "Primus is a local-first personal AI."
        return f"Understood from backend {self.label}."


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
        research=ResearchSettings(allow_external=False),
        personality=PersonalitySettings(name="Primus", voice=DEFAULT_PERSONALITY),
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

        def _dispatch(name: str, request):
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


class IntentFlagsTest(unittest.TestCase):
    def test_primary_unchanged_and_flags_compose(self) -> None:
        self.assertEqual("conversation", classify_intent("How are you?"))
        self.assertEqual("planning", classify_intent("Make a plan to learn C"))
        self.assertEqual("planning", classify_intent("Continue my C learning plan."))
        self.assertEqual("conversation", classify_intent("What should I work on next?"))
        self.assertEqual("research", classify_intent("Research the latest developments in local LLMs."))
        hello = analyze_intent("How are you?")
        self.assertFalse(hello.research)
        self.assertFalse(hello.planning)
        self.assertFalse(hello.tools)
        composite = analyze_intent(
            "Research the latest local LLM developments and tell me which "
            "ones could help my Primus project."
        )
        self.assertEqual("research", composite.primary)
        self.assertTrue(composite.research)
        self.assertTrue(composite.knowledge)
        self.assertTrue(composite.memory)


class IdentityRestartTest(_Harness):
    def test_a_name_survives_controller_reinit(self) -> None:
        first = self.orch.process("My name is Kishore.")
        self.assertEqual("success", first.status)
        self.assertTrue(first.memory_persisted)
        self.ctrl.shutdown()
        spy2 = _SpyLLM()
        ctrl2 = AstraController(self.settings, llm_factory=lambda _s: spy2)
        ctrl2.start()
        self.addCleanup(ctrl2.shutdown)
        second = ConversationOrchestrator.from_controller(ctrl2).process("What is my name?")
        self.assertTrue(any("Kishore" in item for item in second.memory_retrieved))
        self.assertIn("Kishore", spy2.prompts[-1])
        self.assertIn("Kishore", second.text)
        self.assertIn("<|im_start|>system", spy2.prompts[-1])
        self.assertLess(spy2.prompts[-1].find("Kishore"), spy2.prompts[-1].find("<|im_start|>user"))


class PersonalityTest(_Harness):
    def test_b_personality_survives_backend_swap(self) -> None:
        first = self.orch.process("How are you?")
        self.assertTrue(first.personality_applied)
        self.assertIn(DEFAULT_PERSONALITY[:40], self.spy.prompts[-1])
        self.assertIn("You are Primus", self.spy.prompts[-1])
        spy_b = _SpyLLM("B")
        ctrl_b = AstraController(self.settings, llm_factory=lambda _s: spy_b)
        ctrl_b.start()
        self.addCleanup(ctrl_b.shutdown)
        second = ConversationOrchestrator.from_controller(ctrl_b).process("How are you?")
        self.assertTrue(second.personality_applied)
        self.assertIn(DEFAULT_PERSONALITY[:40], spy_b.prompts[-1])
        self.assertIn("Understood from backend B.", second.text)
        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))


class MemoryInfluenceTest(_Harness):
    def test_c_preference_enters_llm_context(self) -> None:
        stored = self.orch.process("I prefer practical programming examples.")
        self.assertTrue(stored.memory_persisted)
        taught = self.orch.process("Teach me pointers.")
        self.assertTrue(any("practical" in item.lower() for item in taught.memory_retrieved))
        self.assertIn("practical programming examples", self.spy.prompts[-1].lower())
        self.assertFalse(taught.research_used)
        self.assertFalse(taught.planning_used)


class ResearchTest(_Harness):
    def test_d_research_reaches_llm(self) -> None:
        self.ctrl.ingest_knowledge(
            "Local LLM runtimes increasingly use GGUF and llama.cpp.",
            source="fixture",
        )
        result = self.orch.process("Research the latest developments in local LLMs.")
        self.assertIn("research", self.dispatch_names)
        self.assertTrue(result.research_used)
        self.assertIn("research", result.intents)
        joined = self.spy.prompts[-1]
        self.assertTrue(
            "Local sources only" in joined or "research" in joined.lower()
        )
        self.assertNotIn("I searched the internet", joined.lower())


class ResearchPlusProjectTest(_Harness):
    def test_e_research_and_project_context(self) -> None:
        self.orch.process("My project is Primus.")
        self.ctrl.ingest_knowledge(
            "Primus is a local-first personal AI that uses llama.cpp.",
            source="fixture",
        )
        result = self.orch.process(
            "Research the latest local LLM developments and tell me which "
            "ones are relevant to my Primus project."
        )
        self.assertTrue(result.research_used)
        self.assertTrue(result.knowledge_used or any("Primus" in item for item in result.memory_retrieved))
        prompt = self.spy.prompts[-1]
        self.assertIn("Primus", prompt)
        self.assertTrue(result.knowledge_used or "Stored knowledge:" in prompt or "project" in prompt.lower())


class PlanningContinuityTest(_Harness):
    def test_f_continue_retrieves_existing_plan(self) -> None:
        created = self.orch.process("Create a plan for learning C.")
        self.assertTrue(created.planning_used)
        tasks = list(self.ctrl.planning.list_tasks())
        self.assertGreaterEqual(len(tasks), 3)
        self.ctrl.shutdown()
        spy2 = _SpyLLM()
        ctrl2 = AstraController(self.settings, llm_factory=lambda _s: spy2)
        ctrl2.start()
        self.addCleanup(ctrl2.shutdown)
        continued = ConversationOrchestrator.from_controller(ctrl2).process(
            "Continue my C learning plan."
        )
        self.assertEqual("planning", continued.intent)
        self.assertTrue(continued.planning_used)
        self.assertIn("continue_plan", continued.intents)
        self.assertIn("Existing plan", spy2.prompts[-1])
        later = list(ctrl2.planning.list_tasks())
        self.assertEqual(len(tasks), len(later))


class GoalContinuityTest(_Harness):
    def test_g_active_goal_influences_next_work(self) -> None:
        recorded = self.orch.process("My goal is to finish the Primus runtime.")
        self.assertTrue(recorded.goals_used)
        self.ctrl.shutdown()
        spy2 = _SpyLLM()
        ctrl2 = AstraController(self.settings, llm_factory=lambda _s: spy2)
        ctrl2.start()
        self.addCleanup(ctrl2.shutdown)
        nxt = ConversationOrchestrator.from_controller(ctrl2).process(
            "What should I work on next?"
        )
        self.assertTrue(nxt.goals_used)
        self.assertFalse(nxt.research_used)
        self.assertIn("Primus runtime", spy2.prompts[-1])


class ToolUseTest(_Harness):
    def test_h_read_project_contents_reach_llm(self) -> None:
        path = ROOT / "astra" / "product.py"
        result = self.orch.process(f"Read this project file and explain what it does: {path}")
        self.assertTrue(result.tools_used)
        self.assertTrue(set(result.tools_used) <= {"read_text", "read_project"})
        self.assertIn("read_project", result.tools_used)
        self.assertIn("PRODUCT_NAME", self.spy.prompts[-1])
        self.assertIn("File contents:", self.spy.prompts[-1])


class CompositeTest(_Harness):
    def test_i_memory_research_knowledge_together(self) -> None:
        self.orch.process("My name is Kishore.")
        self.orch.process("My project is Primus.")
        self.ctrl.ingest_knowledge(
            "Primus conversation goes through ConversationOrchestrator.",
            source="fixture",
        )
        result = self.orch.process(
            "Research the latest local LLM developments and tell me which "
            "ones could help my Primus project."
        )
        self.assertTrue(result.research_used)
        self.assertTrue(result.knowledge_used)
        self.assertTrue(any("Primus" in item for item in result.memory_retrieved) or "Primus" in self.spy.prompts[-1])
        self.assertIn("research", result.intents)
        self.assertIn("knowledge", result.intents)
        self.assertIn("LLM: called=True", result.trace)


class NormalConversationTest(_Harness):
    def test_j_hello_stays_ordinary(self) -> None:
        result = self.orch.process("How are you?")
        self.assertEqual("conversation", result.intent)
        self.assertFalse(result.research_used)
        self.assertFalse(result.planning_used)
        self.assertFalse(result.tools_used)
        self.assertEqual([], self.dispatch_names)
        self.assertEqual([], self.plan_calls)
        self.assertEqual([], self.tool_calls)
        self.assertTrue(result.personality_applied)
        self.assertIn(DEFAULT_PERSONALITY[:40], self.spy.prompts[-1])
        self.assertNotIn("Researching", result.activity)
        self.assertNotIn("I'm putting together a plan", result.activity)


class ProcessRestartTest(unittest.TestCase):
    def test_source_process_restart_keeps_identity(self) -> None:
        home = Path(tempfile.mkdtemp())
        config = home / "astra.toml"
        config.write_text("[reasoning]\n", encoding="utf-8")
        env = os.environ.copy()
        env["ASTRA_HOME"] = str(home)
        env["ASTRA_CONFIG"] = str(config)
        write = subprocess.run(
            [sys.executable, "-m", "astra", "--probe-runtime-write"],
            cwd=str(ROOT),
            env=env,
            timeout=180,
            creationflags=CREATE_NO_WINDOW if sys.platform == "win32" else 0,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.assertEqual(0, write.returncode, ROOT / "logs" / "probe-runtime-write.json")
        written = json.loads(
            (ROOT / "logs" / "probe-runtime-write.json").read_text(encoding="utf-8")
        )
        self.assertTrue(written.get("ok"), written)
        read = subprocess.run(
            [sys.executable, "-m", "astra", "--probe-runtime-read"],
            cwd=str(ROOT),
            env=env,
            timeout=180,
            creationflags=CREATE_NO_WINDOW if sys.platform == "win32" else 0,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.assertEqual(0, read.returncode, ROOT / "logs" / "probe-runtime-read.json")
        payload = json.loads(
            (ROOT / "logs" / "probe-runtime-read.json").read_text(encoding="utf-8")
        )
        self.assertTrue(payload.get("ok"), payload)
        self.assertTrue(payload.get("name_retrieved"), payload)
        self.assertTrue(payload.get("prefer_in_context"), payload)
        self.assertTrue(payload.get("plan_continued"), payload)
        self.assertTrue(payload.get("goals_used"), payload)
        self.assertTrue(payload.get("personality_applied"), payload)


class FrozenRestartTest(unittest.TestCase):
    def test_frozen_exe_write_then_read(self) -> None:
        if not EXE.is_file():
            self.skipTest("rebuild Primus.exe with scripts/build_windows.py")
        home = Path(tempfile.mkdtemp())
        env = os.environ.copy()
        env["ASTRA_HOME"] = str(home)
        env.pop("ASTRA_CONFIG", None)
        flags = CREATE_NO_WINDOW if sys.platform == "win32" else 0
        write = subprocess.run(
            [str(EXE), "--probe-runtime-write"],
            cwd=str(EXE.parent),
            env=env,
            timeout=180,
            creationflags=flags,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.assertEqual(0, write.returncode)
        read = subprocess.run(
            [str(EXE), "--probe-runtime-read"],
            cwd=str(EXE.parent),
            env=env,
            timeout=180,
            creationflags=flags,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.assertEqual(0, read.returncode)
        payload = json.loads(
            (EXE.parent / "logs" / "probe-runtime-read.json").read_text(encoding="utf-8")
        )
        self.assertTrue(payload.get("ok"), payload)
        self.assertTrue(payload.get("name_retrieved"), payload)


if __name__ == "__main__":
    unittest.main()
