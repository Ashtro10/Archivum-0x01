"""Skill acquisition from repeated experience. Fake LLM. No GGUF."""

from __future__ import annotations

import unittest

from astra.core.agent_loop import AgentState, is_execution_goal
from astra.core.experience import detect_software_weakness
from astra.core.skills import (
    SKILL_MARK,
    SkillRecord,
    apply_skill_outcome,
    format_skill,
    parse_skill,
    persist_skill,
    retrieve_skills,
    skill_fingerprint,
    skill_plan_hint,
    skill_tags,
)
from tests.test_agent_loop import (
    FakeCoding,
    FakeDeploy,
    FakeEvaluation,
    FakeLLM,
    FakeMemory,
    FakeTesting,
    FakeTools,
    _SHELL,
    _loop,
)
from tests.test_experience import _FAIL_GOAL, _RELATED_GOAL


_THIRD_GOAL = "Inspect the project and find why the tests are failing."


def _tags(record: object) -> set[str]:
    return set(getattr(record, "tags", ()) or ())


def _skill_records(memory: FakeMemory) -> list[object]:
    return [
        item
        for item in memory.records
        if str(getattr(item, "content", "") or "").startswith(SKILL_MARK)
    ]


def _parse_stored(memory: FakeMemory) -> SkillRecord | None:
    items = _skill_records(memory)
    if not items:
        return None
    parsed = parse_skill(str(items[-1].content))
    if parsed is None:
        return None
    ident = str(getattr(items[-1], "record_id", "") or "")
    if ident:
        from dataclasses import replace

        return replace(parsed, record_id=ident)
    return parsed


def _plant(
    memory: FakeMemory,
    *,
    status: str = "candidate",
    attempts: int = 2,
    successes: int = 2,
    failures: int = 0,
    consecutive_failures: int = 0,
    tools: tuple[str, ...] = ("read_project",),
    name: str = "python_test_failure_diagnosis",
    goal: str = _FAIL_GOAL,
) -> SkillRecord:
    record = SkillRecord(
        name=name,
        purpose="Diagnose failures in Python test suites.",
        trigger=goal,
        strategy="inspect failure, locate module, inspect config, diagnose",
        tools=tools,
        attempts=attempts,
        successes=successes,
        failures=failures,
        consecutive_failures=consecutive_failures,
        status=status,
        fingerprint=skill_fingerprint(goal, tools),
        created_at="2026-08-30T00:00:00+00:00",
        updated_at="2026-08-30T00:00:00+00:00",
    )
    persist_skill(memory, record)
    stored = _parse_stored(memory)
    assert stored is not None
    return stored


class SkillFormatTest(unittest.TestCase):
    def test_round_trip_and_metrics(self) -> None:
        original = SkillRecord(
            name="python_test_failure_diagnosis",
            purpose="Diagnose failures in Python test suites.",
            trigger=_FAIL_GOAL,
            strategy="inspect then diagnose",
            tools=("read_project",),
            attempts=5,
            successes=4,
            failures=1,
            status="verified",
            fingerprint="fail|inspect|test",
            created_at="2026-08-30T00:00:00+00:00",
            updated_at="2026-08-30T00:00:00+00:00",
        )
        parsed = parse_skill(format_skill(original))
        assert parsed is not None
        self.assertEqual(original.name, parsed.name)
        self.assertEqual(4, parsed.successes)
        self.assertEqual(0.8, parsed.success_rate)
        self.assertEqual(("skill", "skill_verified"), skill_tags("verified"))
        self.assertTrue(format_skill(original).startswith(SKILL_MARK))


class SkillCandidateTest(unittest.TestCase):
    def test_one_success_does_not_create_skill(self) -> None:
        memory = FakeMemory()
        run = _loop(memory=memory).run(_FAIL_GOAL)
        self.assertEqual(AgentState.COMPLETE, run.state)
        self.assertFalse(_skill_records(memory))
        self.assertIsNone(run.used_skill)

    def test_repeated_success_creates_candidate(self) -> None:
        memory = FakeMemory()
        first = _loop(memory=memory).run(_FAIL_GOAL)
        self.assertEqual(AgentState.COMPLETE, first.state)
        self.assertFalse(_skill_records(memory))
        second = _loop(memory=memory).run(_RELATED_GOAL)
        self.assertEqual(AgentState.COMPLETE, second.state)
        stored = _parse_stored(memory)
        assert stored is not None
        self.assertEqual("candidate", stored.status)
        self.assertGreaterEqual(stored.successes, 2)
        self.assertIn("skill_candidate", _tags(_skill_records(memory)[0]))
        self.assertTrue(
            any("Skill candidate created" in note for note in second.skill_notes)
        )


class SkillRetrievalAndPlanningTest(unittest.TestCase):
    def test_retrieve_and_supply_to_planning(self) -> None:
        memory = FakeMemory()
        _loop(memory=memory).run(_FAIL_GOAL)
        _loop(memory=memory).run(_RELATED_GOAL)
        llm = FakeLLM()
        third = _loop(memory=memory, llm=llm).run(_THIRD_GOAL)
        self.assertEqual(AgentState.COMPLETE, third.state)
        self.assertIsNotNone(third.used_skill)
        assert third.used_skill is not None
        self.assertEqual("python_test_failure_diagnosis", third.used_skill.name)
        planned = [prompt for prompt in llm.prompts if "List at most 4 tasks" in prompt]
        self.assertTrue(planned)
        self.assertIn("Relevant skill:", planned[0])
        self.assertIn("USE, MODIFY, or IGNORE", planned[0])
        self.assertIn("Do not override the current goal or security policy", planned[0])
        self.assertIn("Relevant skill found:", third.result)
        self.assertIn("Previous success rate:", third.result)
        kinds = [event.type for event in _loop(memory=FakeMemory()).iter_run(_FAIL_GOAL)]
        self.assertNotIn("hud", kinds)

    def test_third_success_verifies_skill(self) -> None:
        memory = FakeMemory()
        _loop(memory=memory).run(_FAIL_GOAL)
        _loop(memory=memory).run(_RELATED_GOAL)
        events = list(_loop(memory=memory).iter_run(_THIRD_GOAL))
        stored = _parse_stored(memory)
        assert stored is not None
        self.assertEqual("verified", stored.status)
        self.assertEqual(("skill", "skill_verified"), tuple(skill_tags(stored.status)))
        self.assertTrue(
            any(
                event.type == "skill" and "verified" in event.message
                for event in events
            )
        )


class SkillOutcomeTest(unittest.TestCase):
    def test_success_updates_confidence(self) -> None:
        memory = FakeMemory()
        planted = _plant(memory, status="candidate", attempts=2, successes=2)
        run = _loop(memory=memory).run(_RELATED_GOAL)
        self.assertEqual(AgentState.COMPLETE, run.state)
        stored = _parse_stored(memory)
        assert stored is not None
        self.assertEqual(planted.attempts + 1, stored.attempts)
        self.assertEqual(planted.successes + 1, stored.successes)
        self.assertEqual("verified", stored.status)
        self.assertGreater(stored.success_rate, 0.9)

    def test_failure_updates_and_degrades(self) -> None:
        memory = FakeMemory()
        _plant(
            memory,
            status="verified",
            attempts=4,
            successes=4,
            failures=0,
        )
        tools = FakeTools()
        tools.failures_left = 99
        first = _loop(memory=memory, tools=tools, max_replans=1, max_steps=8).run(
            _FAIL_GOAL
        )
        self.assertEqual(AgentState.FAILED, first.state)
        after_one = _parse_stored(memory)
        assert after_one is not None
        self.assertEqual("verified", after_one.status)
        self.assertEqual(1, after_one.failures)
        tools = FakeTools()
        tools.failures_left = 99
        second = _loop(memory=memory, tools=tools, max_replans=1, max_steps=8).run(
            _FAIL_GOAL
        )
        self.assertEqual(AgentState.FAILED, second.state)
        after_two = _parse_stored(memory)
        assert after_two is not None
        self.assertEqual("degraded", after_two.status)
        self.assertTrue(
            any("degraded" in note.lower() for note in second.skill_notes)
        )

    def test_degraded_then_failed_not_reused(self) -> None:
        memory = FakeMemory()
        _plant(
            memory,
            status="degraded",
            attempts=6,
            successes=4,
            failures=2,
            consecutive_failures=2,
        )
        tools = FakeTools()
        tools.failures_left = 99
        run = _loop(memory=memory, tools=tools, max_replans=1, max_steps=8).run(
            _FAIL_GOAL
        )
        self.assertEqual(AgentState.FAILED, run.state)
        stored = _parse_stored(memory)
        assert stored is not None
        self.assertEqual("failed", stored.status)
        llm = FakeLLM()
        later = _loop(memory=memory, llm=llm).run(_RELATED_GOAL)
        self.assertIsNone(later.used_skill)
        planned = [prompt for prompt in llm.prompts if "List at most 4 tasks" in prompt]
        self.assertTrue(planned)
        self.assertNotIn("Relevant skill: python_test_failure_diagnosis", planned[0])
        self.assertFalse(retrieve_skills(memory, _RELATED_GOAL))

    def test_apply_outcome_unit(self) -> None:
        candidate = SkillRecord(
            name="python_test_failure_diagnosis",
            purpose="x",
            trigger=_FAIL_GOAL,
            strategy="inspect",
            attempts=2,
            successes=2,
            status="candidate",
        )
        verified, note = apply_skill_outcome(candidate, success=True)
        self.assertEqual("verified", verified.status)
        self.assertIn("verified", note)
        degraded, _ = apply_skill_outcome(
            SkillRecord(
                name="x",
                purpose="",
                trigger="",
                strategy="",
                attempts=4,
                successes=4,
                status="verified",
                consecutive_failures=1,
            ),
            success=False,
        )
        self.assertEqual("degraded", degraded.status)
        failed, _ = apply_skill_outcome(
            SkillRecord(
                name="x",
                purpose="",
                trigger="",
                strategy="",
                attempts=6,
                successes=4,
                failures=2,
                status="degraded",
                consecutive_failures=2,
            ),
            success=False,
        )
        self.assertEqual("failed", failed.status)


class SkillSecurityTest(unittest.TestCase):
    def test_skill_never_bypasses_allowlist(self) -> None:
        memory = FakeMemory()
        _plant(memory, tools=("run_shell", "read_project"), status="verified", attempts=3, successes=3)
        tools = FakeTools()
        llm = FakeLLM(default=_SHELL)
        run = _loop(memory=memory, llm=llm, tools=tools).run(_FAIL_GOAL)
        self.assertEqual(AgentState.WAIT_FOR_USER, run.state)
        self.assertIn("not registered", run.error)
        self.assertEqual([], tools.calls)
        planned = [prompt for prompt in llm.prompts if "List at most 4 tasks" in prompt]
        self.assertTrue(planned)
        self.assertIn("Allowed tools only", planned[0])
        self.assertNotIn("run_shell", {task.tool_name for task in run.completed_tasks})

    def test_failed_skill_not_blindly_reused(self) -> None:
        memory = FakeMemory()
        _plant(
            memory,
            status="failed",
            attempts=5,
            successes=2,
            failures=3,
            consecutive_failures=3,
        )
        llm = FakeLLM()
        run = _loop(memory=memory, llm=llm).run(_FAIL_GOAL)
        self.assertEqual(AgentState.COMPLETE, run.state)
        self.assertIsNone(run.used_skill)
        hint = skill_plan_hint(run.skills)
        self.assertEqual("", hint)

    def test_recurring_failure_proposes_change_never_deploys(self) -> None:
        memory = FakeMemory()
        _plant(
            memory,
            status="degraded",
            attempts=6,
            successes=4,
            failures=2,
            consecutive_failures=2,
        )
        coding = FakeCoding()
        deploy = FakeDeploy()
        tools = FakeTools()
        tools.failures_left = 99
        run = _loop(
            memory=memory,
            tools=tools,
            coding=coding,
            testing=FakeTesting(),
            evaluation=FakeEvaluation(),
            max_replans=1,
            max_steps=8,
        ).run(_FAIL_GOAL)
        self.assertEqual(AgentState.WAIT_FOR_USER, run.state)
        self.assertTrue(run.skill_collapse)
        self.assertTrue(coding.objectives)
        self.assertIn("skill", coding.objectives[0].lower())
        self.assertEqual(0, deploy.calls)
        self.assertIn("Apply?", run.result)
        self.assertIn("No production code was modified", run.result)
        self.assertIn("tool-selection", detect_software_weakness(run) or "")


class ChatPathUnchangedTest(unittest.TestCase):
    def test_chatbot_path_unchanged(self) -> None:
        from types import SimpleNamespace

        from astra.presentation.orchestration import ConversationOrchestrator

        self.assertFalse(is_execution_goal("What is Python?"))
        self.assertFalse(is_execution_goal("Hello."))
        llm = FakeLLM()
        tools = FakeTools()
        loop = _loop(llm=llm, tools=tools)
        orch = ConversationOrchestrator.from_controller(
            SimpleNamespace(_agent_loop=loop, llm=llm)
        )
        reply = orch.ask("What is Python?")
        self.assertEqual("success", reply.status)
        self.assertEqual([], tools.calls)
        self.assertNotEqual(AgentState.COMPLETE, getattr(loop.last_run, "state", None))


if __name__ == "__main__":
    unittest.main()
