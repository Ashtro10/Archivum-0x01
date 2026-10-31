"""B-040 runtime capability: real orchestrator path, probe, frozen exe.

Uses real AstraController + stores. Spy LLM except frozen probe which uses
the packaged model when present.
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
    PlanningSettings,
)
from astra.core.astra_controller import AstraController
from astra.knowledge.contracts import KnowledgeCategory, KnowledgeRecord, KnowledgeVerification
from astra.knowledge.ingest import ingest_text
from astra.presentation.orchestration import (
    ConversationOrchestrator,
    classify_intent,
    run_conversation_probe,
)
from astra.research.source import DirectoryCorpusSource


ROOT = Path(__file__).resolve().parents[1]
EXE = ROOT / "dist_windows" / "Primus.exe"
CREATE_NO_WINDOW = 0x08000000


class _SpyLLM:
    def __init__(self) -> None:
        self.prompts: list[str] = []

    def is_available(self) -> bool:
        return True

    def generate(self, prompt: str, options: object = None) -> str:
        self.prompts.append(prompt)
        if "Relevant memory:" in prompt and "Kishore" in prompt:
            return "Your name is Kishore."
        if "Local sources only" in prompt:
            return "From local documents: Primus is a local-first assistant."
        if "Proposed backlog" in prompt:
            return "Here is a plan with ordered steps."
        if "Stored knowledge:" in prompt:
            return "TCP congestion control uses AIMD."
        if "File contents:" in prompt:
            return "The file talks about alpha-token-40."
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


class IntentParaphraseTest(unittest.TestCase):
    def test_paraphrases(self) -> None:
        self.assertEqual("conversation", classify_intent("How are you?"))
        self.assertEqual("memory", classify_intent("What do you remember about me?"))
        self.assertEqual("memory", classify_intent("Do you remember my name?"))
        self.assertEqual("memory", classify_intent("Forget that."))
        self.assertEqual("memory", classify_intent("Remember that I prefer dark mode"))
        self.assertEqual("research", classify_intent("Research local GGUF models."))
        self.assertEqual(
            "research", classify_intent("Find the latest information about Qwen")
        )
        self.assertEqual("research", classify_intent("Look into local AI models"))
        self.assertEqual(
            "research", classify_intent("Compare current approaches to quantization")
        )
        self.assertEqual("planning", classify_intent("Make a plan to learn C"))
        self.assertEqual("planning", classify_intent("How should I build this project?"))
        self.assertEqual("planning", classify_intent("Create a roadmap."))
        self.assertEqual("planning", classify_intent("I want to learn C from zero."))
        self.assertEqual("goal", classify_intent("I want to become good at AI."))
        self.assertEqual("goal", classify_intent("My goal is to build Primus."))
        self.assertEqual("conversation", classify_intent("What is recursion?"))


class MemoryExperienceTest(_Harness):
    def test_name_persists_and_does_not_fabricate(self) -> None:
        first = self.orch.process("My name is Kishore.")
        self.assertEqual("success", first.status)
        self.ctrl.shutdown()
        spy2 = _SpyLLM()
        ctrl2 = AstraController(self.settings, llm_factory=lambda _s: spy2)
        ctrl2.start()
        self.addCleanup(ctrl2.shutdown)
        second = ConversationOrchestrator.from_controller(ctrl2).process(
            "What is my name?"
        )
        self.assertTrue(any("Kishore" in item for item in second.memory_retrieved))
        self.assertIn("Kishore", spy2.prompts[0])
        empty = ConversationOrchestrator.from_controller(ctrl2).process(
            "What do you remember about my pet?"
        )
        self.assertTrue(
            any("Do not invent" in part for part in spy2.prompts[-1].split("\n")[:6])
            or "No matching durable memory" in spy2.prompts[-1]
        )
        self.assertFalse(empty.memory_retrieved)

    def test_conflicting_name_supersedes(self) -> None:
        self.orch.process("My name is Ada.")
        self.orch.process("My name is Kishore.")
        hits = self.orch.process("What is my name?").memory_retrieved
        self.assertTrue(any("Kishore" in item for item in hits))
        self.assertFalse(any("Ada" in item and "Kishore" not in item for item in hits))

    def test_memory_failure_does_not_crash(self) -> None:
        def _boom(_query, **_kwargs):
            raise RuntimeError("memory down")

        self.ctrl.memory.search = _boom  # type: ignore[method-assign]
        result = self.orch.process("How are you?")
        self.assertEqual("success", result.status)
        self.assertIn("Understood.", result.text)


class ResearchPathTest(_Harness):
    def test_research_uses_agent_and_corpus_not_web_claim(self) -> None:
        hello = self.orch.process("How are you?")
        self.assertFalse(hello.research_used)
        self.assertEqual([], self.dispatch_names)
        self.ctrl.ingest_knowledge(
            "Local AI models run GGUF files through llama.cpp.",
            source="fixture",
        )
        result = self.orch.process("Look into local AI models")
        self.assertIn("research", self.dispatch_names)
        self.assertTrue(result.research_used)
        self.assertNotIn("no findings to summarise", result.text.lower())
        joined = "\n".join(self.spy.prompts)
        self.assertNotIn("I searched the internet", joined.lower())
        self.assertTrue(
            "Local sources only" in joined
            or "do not have internet research" in joined.lower()
        )

    def test_research_unavailable_is_honest(self) -> None:
        def _fail(name: str, request):
            raise RuntimeError("research agent missing")

        self.ctrl.dispatch = _fail  # type: ignore[method-assign]
        result = self.orch.process("Research quantization")
        self.assertFalse(result.research_used)
        self.assertTrue(self.spy.prompts)
        self.assertIn("couldn't access the research sources", self.spy.prompts[-1])
        self.assertNotIn("I researched the latest", result.text)


class PlanningPathTest(_Harness):
    def test_learn_c_creates_backlog_via_engine(self) -> None:
        trivial = self.orch.process("How are you?")
        self.assertEqual([], self.plan_calls)
        self.assertFalse(trivial.planning_used)
        result = self.orch.process("Make a plan to learn C")
        self.assertTrue(result.planning_used)
        self.assertTrue(self.plan_calls)
        tasks = list(self.ctrl.planning.list_tasks())
        self.assertGreaterEqual(len(tasks), 3)
        self.assertIn("Proposed backlog", self.spy.prompts[-1])
        self.assertIn("not executed", self.spy.prompts[-1].lower())


class KnowledgePathTest(_Harness):
    def test_ingest_then_retrieve(self) -> None:
        stored = self.ctrl.ingest_knowledge(
            "TCP congestion control uses AIMD window adjustment.",
            source="fixture",
        )
        self.assertTrue(stored)
        result = self.orch.process("Explain how TCP congestion control works.")
        self.assertTrue(result.knowledge_used)
        self.assertIn("AIMD", self.spy.prompts[-1])

    def test_empty_knowledge_is_honest(self) -> None:
        result = self.orch.process("Explain how TCP congestion control works.")
        self.assertFalse(result.knowledge_used)
        self.assertIn("No stored knowledge records matched", self.spy.prompts[-1])


class ToolPathTest(_Harness):
    def test_natural_read_reaches_allowlist_only(self) -> None:
        note = self.settings.memory.database_path.parent / "note.txt"
        note.write_text("alpha-token-40", encoding="utf-8")
        result = self.orch.process(f"Open this file and summarize it: {note}")
        self.assertTrue(result.tools_used)
        self.assertTrue(set(result.tools_used) <= {"read_text", "read_project"})
        self.assertIn("alpha-token-40", self.spy.prompts[-1])
        self.assertNotIn("shell", result.tools_used)


class GoalPathTest(_Harness):
    def test_goal_manager_receives_proposal(self) -> None:
        result = self.orch.process("I want to become good at AI.")
        self.assertEqual("goal", result.intent)
        self.assertTrue(result.goals_used)
        goals = list(self.ctrl.goals.list_goals())
        self.assertTrue(any("AI" in goal.description for goal in goals))
        hello = self.orch.process("How are you?")
        self.assertFalse(hello.goals_used)


class CorpusAndIngestUnitTest(unittest.TestCase):
    def test_directory_corpus_is_local(self) -> None:
        src = DirectoryCorpusSource(ROOT / "docs")
        self.assertTrue(src.is_available())
        hits = src.query("conversation orchestrator")
        self.assertTrue(hits)
        self.assertFalse(any("http" in f.source.lower() for f in hits))

    def test_ingest_text_uses_existing_store(self) -> None:
        from astra.knowledge.classifier import KeywordKnowledgeClassifier
        from astra.knowledge.sqlite_store import SQLiteKnowledgeStore

        path = Path(tempfile.mkdtemp()) / "k.db"
        store = SQLiteKnowledgeStore(path)
        store.initialise()
        self.addCleanup(store.close)
        first = ingest_text(
            store,
            KeywordKnowledgeClassifier(),
            "Python closures capture enclosing scope.",
            source="test",
        )
        self.assertTrue(first)
        again = ingest_text(
            store,
            KeywordKnowledgeClassifier(),
            "Python closures capture enclosing scope.",
            source="test",
        )
        self.assertEqual("", again)


class ProbeAndSpawnTest(_Harness):
    def test_source_probe_uses_orchestrator(self) -> None:
        payload = run_conversation_probe(self.ctrl)
        self.assertTrue(payload["ok"])
        self.assertEqual("ConversationOrchestrator", payload["orchestrator"])
        self.assertFalse(payload["hello_research"])
        self.assertTrue(payload["name_retrieved"])

    def test_orchestrator_still_has_no_spawn(self) -> None:
        text = (ROOT / "astra" / "presentation" / "orchestration.py").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("import subprocess", text)
        self.assertNotIn("sys.executable", text)
        self.assertNotIn("import ProcessRunner", text)
        self.assertNotIn("from astra.testing", text)
        self.assertNotIn("GitRunner", text)
        self.assertNotIn("Milestone.complete", text)

    def test_hello_does_not_touch_every_subsystem(self) -> None:
        self.orch.process("How are you?")
        self.assertEqual([], self.dispatch_names)
        self.assertEqual([], self.plan_calls)
        self.assertEqual([], self.tool_calls)


class FrozenExecutableTest(unittest.TestCase):
    def test_primus_exe_probe_uses_orchestrator(self) -> None:
        self.assertTrue(EXE.is_file(), "rebuild Primus.exe with scripts/build_windows.py")
        home = Path(tempfile.mkdtemp())
        env = os.environ.copy()
        env["ASTRA_HOME"] = str(home)
        env.pop("ASTRA_CONFIG", None)
        before = _primus_count()
        flags = CREATE_NO_WINDOW if sys.platform == "win32" else 0
        completed = subprocess.run(
            [str(EXE), "--probe"],
            cwd=str(EXE.parent),
            env=env,
            timeout=180,
            creationflags=flags,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        after = _primus_count()
        self.assertLessEqual(after, before + 1)
        result_path = EXE.parent / "logs" / "primus-probe.json"
        self.assertTrue(result_path.is_file(), f"missing {result_path}")
        payload = json.loads(result_path.read_text(encoding="utf-8"))
        self.assertTrue(payload.get("ok"), payload)
        self.assertEqual("ConversationOrchestrator", payload.get("orchestrator"))
        self.assertFalse(payload.get("hello_research"))
        self.assertFalse(payload.get("hello_planning"))
        self.assertEqual(0, completed.returncode)
        leftover = _primus_count()
        self.assertLessEqual(leftover, before)


def _primus_count() -> int:
    if sys.platform != "win32":
        return 0
    try:
        output = subprocess.check_output(
            ["tasklist", "/FI", "IMAGENAME eq Primus.exe", "/FO", "CSV", "/NH"],
            text=True,
            creationflags=CREATE_NO_WINDOW,
        )
    except (OSError, subprocess.CalledProcessError):
        return 0
    return sum(1 for line in output.splitlines() if "Primus.exe" in line)


if __name__ == "__main__":
    unittest.main()
