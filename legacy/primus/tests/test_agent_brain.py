"""Structured agent brain. Fake LLM. No GUI. No new tools."""

from __future__ import annotations

import unittest
from types import SimpleNamespace

from astra.core.agent_decision import (
    AgentDecision,
    FailureClass,
    classify_failure,
    compact_context,
    decision_from_task,
    goal_success_verified,
    observation_matches,
    parse_decision,
    rank_experiences,
    rank_skills,
    self_check,
    validate_decision,
)
from astra.core.agent_loop import AgentState, is_execution_goal
from astra.core.experience import ExperienceRecord, detect_software_weakness
from astra.core.skills import SkillRecord
from astra.presentation.orchestration import ConversationOrchestrator
from tests.test_agent_loop import FakeLLM, FakeTools, _loop


class StructuredDecisionTest(unittest.TestCase):
    def test_valid_structured_decision(self) -> None:
        decision = decision_from_task(
            state="SELECT_TOOL",
            goal="Open Blender.",
            task_title="Launch Blender",
            tool="launch_application",
            arguments={"application": "blender"},
        )
        self.assertIsNone(validate_decision(decision))
        self.assertEqual("HIGH", decision.confidence)
        self.assertTrue(decision.expected_observation)
        self.assertIn("tool", decision.to_dict())

    def test_malformed_decision_rejected(self) -> None:
        self.assertIsNone(parse_decision("please run blender now"))
        self.assertIsNone(parse_decision("{not json"))
        self.assertIsNone(parse_decision('{"action":"click"}'))
        bad = AgentDecision(
            state="SELECT_TOOL",
            goal="Open Blender.",
            current_task="Launch",
            action="use launch_application",
            tool="run_shell",
            expected_observation="running",
            success_condition="running",
        )
        self.assertEqual("tool not allowed", validate_decision(bad))

    def test_invalid_tool_arguments(self) -> None:
        decision = decision_from_task(
            state="SELECT_TOOL",
            goal="Open Blender.",
            task_title="Launch Blender",
            tool="launch_application",
            arguments={},
        )
        self.assertEqual("application is required", validate_decision(decision))
        self.assertIsNotNone(
            self_check(
                decision_from_task(
                    state="SELECT_TOOL",
                    goal="Inspect",
                    task_title="Read",
                    tool="read_project",
                    arguments={},
                )
            )
        )


class ObservationAndSuccessTest(unittest.TestCase):
    def test_expected_observation_success(self) -> None:
        self.assertTrue(
            observation_matches(
                "application process is running",
                "application=blender success=true pid=4242 running=true",
                tool_success=True,
            )
        )

    def test_observation_mismatch(self) -> None:
        self.assertFalse(
            observation_matches(
                "application process is running",
                "launched something",
                tool_success=True,
            )
        )
        self.assertFalse(
            observation_matches("file contents retrieved", "empty", tool_success=False)
        )

    def test_success_condition(self) -> None:
        self.assertTrue(
            goal_success_verified(
                "Open Blender.",
                ["Verify: blender pid=4242 status=running"],
                ["launch_application", "inspect_process"],
            )
        )
        self.assertFalse(
            goal_success_verified(
                "Open Blender and create a new project.",
                ["Vision capability unavailable."],
                ["launch_application", "capture_screen"],
            )
        )
        self.assertTrue(
            goal_success_verified(
                "Inspect my project",
                ["file contents for README.md"],
                ["read_project"],
            )
        )


class ConfidenceAndFailureTest(unittest.TestCase):
    def test_confidence_handling(self) -> None:
        high = decision_from_task(
            state="SELECT_TOOL",
            goal="g",
            task_title="t",
            tool="read_project",
            arguments={"path": "README.md"},
            confidence="0.9",
        )
        self.assertEqual("HIGH", high.confidence)
        med = decision_from_task(
            state="SELECT_TOOL",
            goal="g",
            task_title="t",
            tool="read_project",
            arguments={"path": "README.md"},
            confidence="0.5",
        )
        self.assertEqual("MEDIUM", med.confidence)
        low = decision_from_task(
            state="SELECT_TOOL",
            goal="g",
            task_title="t",
            tool="read_project",
            arguments={"path": "README.md"},
            confidence="LOW",
        )
        self.assertEqual("LOW", low.confidence)

    def test_failure_classification(self) -> None:
        self.assertIs(
            FailureClass.SECURITY_DENIAL,
            classify_failure(status="denied", error="denied", tool_success=False),
        )
        self.assertIs(
            FailureClass.TIMEOUT,
            classify_failure(status="executed", error="timed out", tool_success=False),
        )
        self.assertIs(
            FailureClass.INVALID_ARGUMENT,
            classify_failure(invalid_arguments=True, tool_success=False),
        )
        self.assertIs(
            FailureClass.OBSERVATION_MISMATCH,
            classify_failure(tool_success=True, matched=False),
        )
        self.assertIs(
            FailureClass.TOOL_FAILURE,
            classify_failure(status="executed", error="read failed", tool_success=False),
        )
        self.assertIs(FailureClass.UNKNOWN, classify_failure(tool_success=True))


class ContextAndMemoryTest(unittest.TestCase):
    def test_compact_context(self) -> None:
        text = compact_context(
            goal="Open Blender.",
            task="Launch Blender",
            plan=["Launch Blender", "Verify Blender"],
            experiences=[SimpleNamespace(lesson="Launch then inspect process")],
            skills=[SimpleNamespace(name="launch-blender")],
            last_observation="running=true",
            last_result="launch ok",
        )
        self.assertIn("CURRENT GOAL:", text)
        self.assertIn("CURRENT TASK:", text)
        self.assertIn("RELEVANT PLAN:", text)
        self.assertIn("RELEVANT MEMORY:", text)
        self.assertIn("RELEVANT SKILLS:", text)
        self.assertIn("LAST OBSERVATION:", text)
        self.assertNotIn("AgentRun", text)
        self.assertLess(len(text), 800)

    def test_memory_ranking(self) -> None:
        blender = ExperienceRecord(goal="Open Blender.", lesson="inspect process", outcome="COMPLETE")
        other = ExperienceRecord(goal="Write a report", lesson="write_text", outcome="COMPLETE")
        ranked = rank_experiences([other, blender], "Open Blender and tell me whether it started.", limit=1)
        self.assertEqual("Open Blender.", ranked[0].goal)
        skills = [
            SkillRecord(name="report-writer", purpose="reports", trigger="report", strategy="write", status="verified"),
            SkillRecord(name="blender-launch", purpose="launch blender", trigger="open blender", strategy="launch", status="verified"),
        ]
        top = rank_skills(skills, "Open Blender.", limit=1)
        self.assertEqual("blender-launch", top[0].name)


class AgentLoopBrainTest(unittest.TestCase):
    def test_malformed_decision_replans(self) -> None:
        tools = FakeTools()
        loop = _loop(
            llm=FakeLLM(
                script=["TASK tool=run_shell path=. | Run a shell command\n"],
                default="",
            ),
            tools=tools,
            max_replans=2,
        )
        run = loop.run("Inspect my project and determine what needs improvement.")
        self.assertGreaterEqual(run.planning_failures, 1)
        self.assertEqual(FailureClass.PLANNING_FAILURE.value, run.failure_class)
        self.assertIn("not registered", run.error)
        self.assertNotIn("run_shell", [name for name, _ in tools.calls])

    def test_observation_mismatch_replans(self) -> None:
        tools = FakeTools()
        tools.outputs["launch_application"] = "launched something"
        loop = _loop(llm=FakeLLM(default=""), tools=tools, max_replans=2)
        events = list(loop.iter_run("Open Blender and tell me whether it started."))
        run = loop.last_run
        assert run is not None
        self.assertEqual(FailureClass.OBSERVATION_MISMATCH.value, run.failure_class)
        self.assertGreaterEqual(run.replan_count, 1)
        self.assertTrue(
            any(event.state == AgentState.REPLAN.value for event in events if event.type == "agent_state")
        )

    def test_security_denial_waits(self) -> None:
        tools = FakeTools()
        tools.force_status = "denied"
        loop = _loop(llm=FakeLLM(default=""), tools=tools)
        run = loop.run("Open Blender.")
        self.assertEqual(AgentState.WAIT_FOR_USER, run.state)
        self.assertEqual(FailureClass.SECURITY_DENIAL.value, run.failure_class)

    def test_replan_and_limits(self) -> None:
        tools = FakeTools()
        tools.failures_left = 1
        loop = _loop(tools=tools, max_replans=2)
        run = loop.run("Inspect my project and determine what needs improvement.")
        self.assertEqual(AgentState.COMPLETE, run.state)
        self.assertGreaterEqual(run.replan_count, 1)
        capped = _loop(max_steps=1).run("Inspect my project and determine what needs improvement.")
        self.assertEqual(AgentState.FAILED, capped.state)
        self.assertEqual("max_steps reached", capped.error)

    def test_normal_task_llm_calls(self) -> None:
        tools = FakeTools()
        loop = _loop(llm=FakeLLM(default=""), tools=tools)
        run = loop.run("Open Blender and tell me whether it started.")
        self.assertEqual(AgentState.COMPLETE, run.state)
        self.assertEqual(0, run.llm_calls)
        self.assertGreaterEqual(len(run.decisions), 1)
        inspect = _loop(tools=FakeTools())
        inspected = inspect.run("Inspect my project and determine what needs improvement.")
        self.assertEqual(AgentState.COMPLETE, inspected.state)
        self.assertEqual(1, inspected.llm_calls)

    def test_existing_chatbot_path(self) -> None:
        self.assertFalse(is_execution_goal("What is Python?"))
        tools = FakeTools()
        loop = _loop(llm=FakeLLM(), tools=tools)
        orch = ConversationOrchestrator.from_controller(
            SimpleNamespace(_agent_loop=loop, llm=FakeLLM())
        )
        reply = orch.ask("What is Python?")
        self.assertEqual("success", reply.status)
        self.assertEqual([], tools.calls)

    def test_existing_approval_flow(self) -> None:
        tools = FakeTools()
        tools.approval_for = "read_project"
        loop = _loop(tools=tools)
        run = loop.run("Inspect my project and determine what needs improvement.")
        self.assertEqual(AgentState.WAIT_FOR_USER, run.state)
        self.assertEqual("Approval required.", run.error)
        resumed = loop.resume(True)
        self.assertEqual(run.run_id, resumed.run_id)

    def test_repeated_planning_is_weakness(self) -> None:
        run = SimpleNamespace(
            skill_collapse=False,
            invalid_tool_attempts=0,
            planning_failures=2,
            error="",
            state=AgentState.WAIT_FOR_USER,
            replan_count=0,
            step_count=2,
        )
        self.assertIn("structured decisions", detect_software_weakness(run) or "")


if __name__ == "__main__":
    unittest.main()
