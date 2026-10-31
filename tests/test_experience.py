"""Agent experience memory. Fake LLM. No GGUF."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from astra.core.agent_loop import AgentState, is_execution_goal
from astra.core.experience import (
    EXPERIENCE_MARK,
    ExperienceRecord,
    detect_software_weakness,
    format_experience,
    parse_experience,
    retrieve_experiences,
)
from astra.memory.contracts import MemoryCategory, MemoryRecord, VerificationState
from astra.memory.sqlite_store import SQLiteMemoryStore
from tests.test_agent_loop import (
    FakeCoding,
    FakeDeploy,
    FakeEvaluation,
    FakeLLM,
    FakeMemory,
    FakeTesting,
    FakeTools,
    _loop,
)


_FAIL_GOAL = "Inspect the project and find why X is failing."
_RELATED_GOAL = "Investigate another test failure."


def _tags(record: object) -> set[str]:
    return set(getattr(record, "tags", ()) or ())


class ExperienceFormatTest(unittest.TestCase):
    def test_round_trip(self) -> None:
        original = ExperienceRecord(
            goal="inspect tests",
            outcome="COMPLETE",
            tasks=("read README",),
            tools=("read_project",),
            successful=("read README",),
            failed=(),
            strategy="read first",
            lesson="start with README",
            timestamp="2026-08-30T00:00:00+00:00",
            steps=5,
            tool_calls=5,
            replans=0,
            failures=0,
            duration_s=1.5,
            completed=2,
            approval_waits=0,
        )
        parsed = parse_experience(format_experience(original))
        assert parsed is not None
        self.assertEqual(original.goal, parsed.goal)
        self.assertEqual(original.lesson, parsed.lesson)
        self.assertEqual(5, parsed.steps)
        self.assertEqual(0, parsed.replans)
        self.assertTrue(format_experience(original).startswith(EXPERIENCE_MARK))


class ExperienceLoopTest(unittest.TestCase):
    def test_successful_run_stores_lesson(self) -> None:
        memory = FakeMemory()
        run = _loop(memory=memory).run(
            "Inspect my project and determine what needs improvement."
        )
        self.assertEqual(AgentState.COMPLETE, run.state)
        self.assertTrue(run.memory_written)
        self.assertGreaterEqual(run.total_steps, 1)
        self.assertGreaterEqual(run.tool_calls, 1)
        self.assertEqual(run.replans, run.replan_count)
        bodies = [str(getattr(item, "content", "")) for item in memory.records]
        self.assertTrue(any(item.startswith(EXPERIENCE_MARK) for item in bodies))
        ok = [item for item in memory.records if "strategy_ok" in _tags(item)]
        self.assertTrue(ok)
        parsed = parse_experience(str(ok[0].content))
        assert parsed is not None
        self.assertEqual("COMPLETE", parsed.outcome)
        self.assertIn("lesson", _tags(ok[0]))
        self.assertIn("skill", _tags(ok[0]))
        self.assertEqual(MemoryCategory.EXPERIMENTS, ok[0].category)

    def test_failed_then_recovered_stores_fail_and_ok(self) -> None:
        memory = FakeMemory()
        tools = FakeTools()
        tools.failures_left = 1
        run = _loop(memory=memory, tools=tools).run(_FAIL_GOAL)
        self.assertEqual(AgentState.COMPLETE, run.state)
        self.assertGreaterEqual(run.replan_count, 1)
        self.assertGreaterEqual(run.failures, 1)
        tags = [_tags(item) for item in memory.records]
        self.assertTrue(any("strategy_fail" in item for item in tags))
        self.assertTrue(any("strategy_ok" in item for item in tags))
        fail = next(item for item in memory.records if "strategy_fail" in _tags(item))
        self.assertEqual(MemoryCategory.FAILURES, fail.category)
        parsed = parse_experience(str(fail.content))
        assert parsed is not None
        self.assertTrue(parsed.failed)
        self.assertTrue(parsed.lesson)
        self.assertNotIn("chain-of-thought", str(fail.content).lower())

    def test_terminal_failure_stores_failure_lesson(self) -> None:
        memory = FakeMemory()
        tools = FakeTools()
        tools.failures_left = 99
        run = _loop(memory=memory, tools=tools, max_replans=1, max_steps=8).run(_FAIL_GOAL)
        self.assertEqual(AgentState.FAILED, run.state)
        fail = [item for item in memory.records if "strategy_fail" in _tags(item)]
        self.assertTrue(fail)
        parsed = parse_experience(str(fail[0].content))
        assert parsed is not None
        self.assertEqual("FAILED", parsed.outcome)
        self.assertEqual("max_replans reached", parsed.failure_reason)

    def test_retrieve_and_supply_to_planning(self) -> None:
        memory = FakeMemory()
        tools = FakeTools()
        tools.failures_left = 1
        first = _loop(memory=memory, tools=tools).run(_FAIL_GOAL)
        self.assertEqual(AgentState.COMPLETE, first.state)
        llm = FakeLLM()
        second = _loop(memory=memory, llm=llm).run(_RELATED_GOAL)
        self.assertEqual(AgentState.COMPLETE, second.state)
        self.assertTrue(second.experiences)
        self.assertIn("relevant previous experience", second.experience_note)
        self.assertIn("possible strategy", second.result)
        planned = [prompt for prompt in llm.prompts if "List at most 4 tasks" in prompt]
        self.assertTrue(planned)
        self.assertIn("Previous experience", planned[0])
        self.assertIn("suggestions only", planned[0].lower())
        self.assertIn("Previous similar run:", second.result)

    def test_self_improve_on_loop_weakness_never_deploys(self) -> None:
        coding = FakeCoding()
        deploy = FakeDeploy()
        tools = FakeTools()
        tools.failures_left = 99
        run = _loop(
            tools=tools,
            coding=coding,
            testing=FakeTesting(),
            evaluation=FakeEvaluation(),
            max_replans=1,
            max_steps=8,
        ).run(_FAIL_GOAL)
        self.assertEqual(AgentState.WAIT_FOR_USER, run.state)
        self.assertTrue(coding.objectives)
        self.assertIn("max_replans reached", coding.objectives[0])
        self.assertIsNotNone(run.proposal)
        self.assertEqual(0, deploy.calls)
        self.assertIn("Apply?", run.result)
        self.assertIn("No production code was modified", run.result)

    def test_chatbot_path_unchanged(self) -> None:
        self.assertFalse(is_execution_goal("What is Python?"))
        self.assertFalse(is_execution_goal("Hello."))


class ExperiencePersistenceTest(unittest.TestCase):
    def test_sqlite_store_and_search(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        store = SQLiteMemoryStore(Path(tmp.name) / "mem.db")
        store.initialise()
        self.addCleanup(store.close)
        body = format_experience(
            ExperienceRecord(
                goal=_FAIL_GOAL,
                outcome="COMPLETE",
                tools=("read_project",),
                lesson="After a project read fails, inspect a fallback file.",
                strategy="inspect config fallback first",
                timestamp="2026-08-30T00:00:00+00:00",
                steps=8,
                replans=2,
                failures=1,
            )
        )
        ident = store.store(
            MemoryRecord(
                record_id="",
                category=MemoryCategory.EXPERIMENTS,
                content=body,
                verification=VerificationState.UNVERIFIED,
                source="agent_loop",
                tags=("strategy_ok", "lesson"),
            )
        )
        self.assertTrue(ident)
        found = retrieve_experiences(store, _RELATED_GOAL)
        self.assertTrue(found)
        self.assertIn("fallback", found[0].lesson.lower())


class WeaknessHeuristicTest(unittest.TestCase):
    def test_detects_invalid_tools_and_caps(self) -> None:
        from types import SimpleNamespace

        from astra.core.agent_loop import AgentState

        clean = SimpleNamespace(
            invalid_tool_attempts=0,
            error="",
            state=AgentState.COMPLETE,
            replan_count=0,
            step_count=2,
        )
        self.assertIsNone(detect_software_weakness(clean))
        bad = SimpleNamespace(
            invalid_tool_attempts=2,
            error="",
            state=AgentState.COMPLETE,
            replan_count=0,
            step_count=2,
        )
        self.assertIn("invalid tool", detect_software_weakness(bad) or "")
        capped = SimpleNamespace(
            invalid_tool_attempts=0,
            error="max_steps reached",
            state=AgentState.FAILED,
            replan_count=0,
            step_count=8,
        )
        self.assertIn("max_steps reached", detect_software_weakness(capped) or "")


if __name__ == "__main__":
    unittest.main()
