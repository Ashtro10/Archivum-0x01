"""Bounded agent loop. Fake LLM. Fake tools. No GGUF."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from astra.coding.contracts import ChangeProposal, FileChange
from astra.configuration.settings import (
    AstraSettings,
    KnowledgeSettings,
    MemorySettings,
    PlanningSettings,
)
from astra.core.agent_loop import (
    AgentLoop,
    AgentState,
    AgentTask,
    ToolCallResult,
    is_execution_goal,
    wants_improvement,
    wants_report_write,
)
from astra.core.astra_controller import AstraController
from astra.core.lifecycle import MeasurableProperty
from astra.evaluation.contracts import EvaluationReport
from astra.presentation.orchestration import ConversationOrchestrator
from astra.testing.contracts import TestReport


_PLAN = (
    "TASK tool=read_project path=README.md | Inspect project structure\n"
    "TASK tool=read_project path=pyproject.toml | Inspect relevant files\n"
)
_SHELL = "TASK tool=run_shell path=. | Run a shell command\n"


class FakeLLM:
    def __init__(self, script: list[str] | None = None, default: str = _PLAN) -> None:
        self.script = list(script or [])
        self.default = default
        self.prompts: list[str] = []

    def is_available(self) -> bool:
        return True

    def generate(self, prompt: str, options: object = None) -> str:
        self.prompts.append(prompt)
        if self.script:
            return self.script.pop(0)
        return self.default


class FakeTools:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, str]]] = []
        self.failures_left = 0
        self.approval_for = ""
        self.force_status: str = ""
        self.vision_available = False
        self.fail_next: set[str] = set()
        self.outputs: dict[str, str] = {}

    def __call__(self, name: str, arguments: dict[str, str]) -> ToolCallResult:
        self.calls.append((name, dict(arguments)))
        if self.approval_for and name == self.approval_for:
            self.approval_for = ""
            return ToolCallResult(
                success=False,
                status="pending_approval",
                error="Approval required.",
                invocation_id="inv-test",
            )
        if self.force_status == "denied":
            return ToolCallResult(
                success=False,
                status="denied",
                error="denied",
            )
        if self.force_status == "unavailable":
            return ToolCallResult(
                success=False,
                status="unavailable",
                error="tool missing",
            )
        if self.failures_left > 0:
            self.failures_left -= 1
            return ToolCallResult(success=False, status="executed", error="read failed")
        if name in self.fail_next:
            self.fail_next.discard(name)
            return ToolCallResult(success=False, status="executed", error="action failed")
        path = str(arguments.get("path", "") or "")
        if name in self.outputs:
            return ToolCallResult(
                success=True,
                status="executed",
                output=self.outputs[name],
            )
        if name == "launch_application":
            app = str(arguments.get("application") or arguments.get("executable") or "app")
            return ToolCallResult(
                success=True,
                status="executed",
                output=f"application={app} success=true pid=4242 running=true",
            )
        if name == "inspect_process":
            query = str(arguments.get("query") or arguments.get("pid") or "app")
            return ToolCallResult(
                success=True,
                status="executed",
                output=f"{query} pid=4242 status=running",
            )
        if name == "inspect_active_window":
            return ToolCallResult(
                success=True,
                status="executed",
                output="application=notepad.exe title=Untitled pid=77",
            )
        if name == "capture_screen":
            vision = (
                "desktop shows Blender"
                if self.vision_available
                else "Vision capability unavailable."
            )
            return ToolCallResult(
                success=True,
                status="executed",
                output=(
                    "resolution=1280x720 monitor=1 monitors=1 "
                    "timestamp=2026-08-30T00:00:00+00:00 stored=false "
                    f"{vision}"
                ),
            )
        if name == "mouse_action":
            return ToolCallResult(
                success=True,
                status="executed",
                output=(
                    f"action={arguments.get('action', 'click')} "
                    f"x={arguments.get('x', '0')} y={arguments.get('y', '0')} "
                    f"button={arguments.get('button', 'left')} success=true "
                    "resolution=1920x1080"
                ),
            )
        if name == "keyboard_action":
            return ToolCallResult(
                success=True,
                status="executed",
                output=(
                    f"action={arguments.get('action', 'type_text')} "
                    f"keys={arguments.get('text') or arguments.get('keys') or arguments.get('key') or ''} "
                    "success=true"
                ),
            )
        return ToolCallResult(
            success=True,
            status="executed",
            output=f"file contents for {path}",
        )


class FakeMemory:
    def __init__(self) -> None:
        self.records: list[object] = []
        self._n = 0

    def store(self, record: object) -> str:
        from dataclasses import replace

        ident = str(getattr(record, "record_id", "") or "")
        if ident:
            for index, existing in enumerate(self.records):
                if str(getattr(existing, "record_id", "") or "") == ident:
                    stored = record
                    try:
                        stored = replace(record, record_id=ident)
                    except TypeError:
                        stored = record
                    self.records[index] = stored
                    return ident
        self._n += 1
        ident = ident or f"mem-{self._n}"
        stored = record
        try:
            stored = replace(record, record_id=ident)
        except TypeError:
            stored = record
        self.records.append(stored)
        return ident

    def get(self, record_id: str) -> object | None:
        for record in self.records:
            if str(getattr(record, "record_id", "") or "") == record_id:
                return record
        return None

    def iter_records(self, *, category: object = None):
        for record in self.records:
            if category is not None and getattr(record, "category", None) != category:
                continue
            yield record

    def search(
        self,
        query: str,
        *,
        category: object = None,
        limit: int = 20,
    ) -> list[object]:
        needle = str(query or "").lower()
        tokens = {part for part in needle.replace("|", " ").split() if len(part) >= 4}
        hits: list[object] = []
        for record in self.records:
            if category is not None and getattr(record, "category", None) != category:
                continue
            content = str(getattr(record, "content", "") or "").lower()
            if needle and needle in content:
                hits.append(record)
                continue
            if tokens and any(token in content for token in tokens):
                hits.append(record)
            if len(hits) >= limit:
                break
        return hits[:limit]


class FakeCoding:
    def __init__(self) -> None:
        self.objectives: list[str] = []
        self.applied = False

    def propose_change(self, objective: str, targets: object = None) -> ChangeProposal:
        self.objectives.append(objective)
        return ChangeProposal(
            objective=objective,
            changes=(
                FileChange(
                    path=Path("README.md"),
                    new_content="# improved\n",
                    rationale="clarity",
                ),
            ),
            improves=(MeasurableProperty.MAINTAINABILITY,),
        )


class FakeTesting:
    def test_proposal(self, proposal: object) -> TestReport:
        return TestReport(tests_run=94, tests_passed=94, exit_code=0)


class FakeEvaluation:
    def evaluate_proposal(self, proposal: object, tests: object, **kwargs: object) -> EvaluationReport:
        return EvaluationReport(
            subject=str(getattr(proposal, "objective", "") or ""),
            tests_passed=True,
            evaluation_passed=True,
            deployment_authorized=False,
        )


class FakeDeploy:
    def __init__(self) -> None:
        self.calls = 0

    def deploy(self, *args: object, **kwargs: object) -> None:
        self.calls += 1


def _loop(**kwargs: object) -> AgentLoop:
    tools = kwargs.pop("tools", None)
    if tools is None:
        tools = FakeTools()
    return AgentLoop(
        llm=kwargs.get("llm") or FakeLLM(),
        invoke_tool=tools,  # type: ignore[arg-type]
        memory=kwargs.get("memory") or FakeMemory(),
        coding=kwargs.get("coding"),
        testing=kwargs.get("testing"),
        evaluation=kwargs.get("evaluation"),
        project_root=kwargs.get("project_root") or Path("."),
        write_root=kwargs.get("write_root"),
        scratch_root=kwargs.get("scratch_root"),
        max_steps=int(kwargs.get("max_steps", 8)),  # type: ignore[arg-type]
        max_replans=int(kwargs.get("max_replans", 2)),  # type: ignore[arg-type]
        planning=kwargs.get("planning"),
        goals=kwargs.get("goals"),
        max_actions=int(kwargs.get("max_actions", 10)),  # type: ignore[arg-type]
        max_visual_observations=int(kwargs.get("max_visual_observations", 10)),  # type: ignore[arg-type]
        max_interaction_time=float(kwargs.get("max_interaction_time", 60.0)),
    )


class ExecutionGoalTest(unittest.TestCase):
    def test_chat_questions_are_not_execution_goals(self) -> None:
        for text in (
            "Hello.",
            "What is Python?",
            "Explain recursion.",
            "How are you?",
            "Make a plan to learn C",
            "What is a test failure?",
            "What is Blender?",
            "Open a file.",
        ):
            self.assertFalse(is_execution_goal(text), text)

    def test_inspect_goals_route_to_loop(self) -> None:
        for text in (
            "Read my project and tell me what is broken.",
            "Inspect the project and find why the tests are failing.",
            "Analyze these files and give me a plan.",
            "Check the project and determine what should be improved.",
            "Inspect my project and determine what needs improvement.",
            "Find a problem in the project and propose an improvement.",
            "Inspect my project, find the relevant files, create a small report describing one problem, and save the report as a file.",
            "Inspect the project and find why X is failing.",
            "Investigate another test failure.",
            "Open Blender.",
            "Open Blender and tell me whether it started.",
            "Open Blender, inspect my project, and create a report.",
            "Prepare my project for a hackathon.",
        ):
            self.assertTrue(is_execution_goal(text), text)

    def test_report_goal_wants_write(self) -> None:
        self.assertTrue(
            wants_report_write(
                "Inspect my project, find the relevant files, create a small report describing one problem, and save the report as a file."
            )
        )

    def test_propose_marker(self) -> None:
        self.assertTrue(wants_improvement("Find a problem in the project and propose an improvement."))
        self.assertFalse(
            wants_improvement("Inspect my project and determine what needs improvement.")
        )


class AgentLoopSuccessTest(unittest.TestCase):
    def test_successful_multi_step_execution(self) -> None:
        tools = FakeTools()
        loop = _loop(tools=tools)
        events = list(loop.iter_run("Inspect my project and determine what needs improvement."))
        run = loop.last_run
        assert run is not None
        self.assertEqual(AgentState.COMPLETE, run.state)
        self.assertEqual(2, len(run.completed_tasks))
        self.assertEqual(2, len(tools.calls))
        self.assertEqual("read_project", tools.calls[0][0])
        self.assertEqual("read_project", tools.calls[1][0])
        kinds = [event.type for event in events]
        self.assertIn("agent_state", kinds)
        self.assertIn("plan", kinds)
        self.assertIn("tool", kinds)
        self.assertIn("tool_result", kinds)
        self.assertIn("reflection", kinds)
        self.assertIn("completed", kinds)
        self.assertTrue(any(event.message == "Goal completed" for event in events))
        self.assertIn("Findings:", run.result)
        states = [event.state for event in events if event.type == "agent_state"]
        self.assertIn("UNDERSTAND", states)
        self.assertIn("PLAN", states)
        self.assertIn("EXECUTE", states)
        self.assertIn("OBSERVE", states)
        self.assertIn("EVALUATE", states)
        self.assertIn("REFLECT", states)
        self.assertIn("COMPLETE", states)
        self.assertTrue(run.memory_written)


class AgentLoopFailureTest(unittest.TestCase):
    def test_tool_failure_then_replan(self) -> None:
        tools = FakeTools()
        tools.failures_left = 1
        loop = _loop(tools=tools, max_replans=2)
        run = loop.run("Inspect my project and determine what needs improvement.")
        self.assertEqual(AgentState.COMPLETE, run.state)
        self.assertGreaterEqual(run.replan_count, 1)
        self.assertEqual(1, len(run.failed_tasks))
        self.assertGreaterEqual(len(run.completed_tasks), 1)

    def test_max_step_protection(self) -> None:
        loop = _loop(max_steps=1)
        run = loop.run("Inspect my project and determine what needs improvement.")
        self.assertEqual(AgentState.FAILED, run.state)
        self.assertEqual("max_steps reached", run.error)
        self.assertEqual(1, run.step_count)

    def test_max_replan_protection(self) -> None:
        tools = FakeTools()
        tools.failures_left = 99
        loop = _loop(tools=tools, max_replans=1, max_steps=8)
        run = loop.run("Inspect my project and determine what needs improvement.")
        self.assertEqual(AgentState.FAILED, run.state)
        self.assertEqual("max_replans reached", run.error)
        self.assertEqual(1, run.replan_count)

    def test_approval_required_state(self) -> None:
        tools = FakeTools()
        tools.approval_for = "read_project"
        loop = _loop(tools=tools)
        events = list(loop.iter_run("Inspect my project and determine what needs improvement."))
        run = loop.last_run
        assert run is not None
        self.assertEqual(AgentState.WAIT_FOR_USER, run.state)
        self.assertEqual("Approval required.", run.error)
        self.assertTrue(any(event.message == "Approval required." for event in events))

    def test_approval_resume_continues_same_run(self) -> None:
        tools = FakeTools()
        tools.approval_for = "read_project"
        loop = _loop(tools=tools)
        goal = "Inspect my project and determine what needs improvement."
        list(loop.iter_run(goal))
        first = loop.last_run
        assert first is not None
        run_id = first.run_id
        self.assertEqual(AgentState.WAIT_FOR_USER, first.state)
        resumed = loop.resume(True)
        self.assertIs(first, resumed)
        self.assertEqual(run_id, resumed.run_id)
        self.assertEqual(AgentState.COMPLETE, resumed.state)
        self.assertGreaterEqual(len(tools.calls), 2)

    def test_report_plan_search_read_write_verify(self) -> None:
        tools = FakeTools()
        tools.approval_for = "write_text"
        loop = _loop(llm=FakeLLM(default=""), tools=tools)
        goal = (
            "Inspect my project, find the relevant files, create a small report "
            "describing one problem, and save the report as a file."
        )
        events = list(loop.iter_run(goal))
        run = loop.last_run
        assert run is not None
        self.assertEqual(AgentState.WAIT_FOR_USER, run.state)
        self.assertIn("Action:", run.result)
        self.assertIn("Tool: write_text", run.result)
        self.assertIn("Approve / Reject", run.result)
        names = [name for name, _args in tools.calls]
        self.assertEqual("search_files", names[0])
        self.assertIn("read_project", names)
        self.assertIn("write_text", names)
        states = [event.state for event in events if event.type == "agent_state"]
        self.assertIn("SELECT_TOOL", states)
        resumed = loop.resume(True)
        self.assertEqual(AgentState.COMPLETE, resumed.state)
        self.assertIn("write_text", [task.tool_name for task in resumed.completed_tasks])
        self.assertIn("read_text", [name for name, _args in tools.calls])

    def test_unavailable_tool_stops(self) -> None:
        llm = FakeLLM(default=_SHELL)
        loop = _loop(llm=llm)
        run = loop.run("Inspect my project and determine what needs improvement.")
        self.assertEqual(AgentState.WAIT_FOR_USER, run.state)
        self.assertIn("not registered", run.error)


class SelfImproveHookTest(unittest.TestCase):
    def test_proposal_stops_at_approval_and_never_deploys(self) -> None:
        coding = FakeCoding()
        testing = FakeTesting()
        evaluation = FakeEvaluation()
        deploy = FakeDeploy()
        loop = _loop(coding=coding, testing=testing, evaluation=evaluation)
        run = loop.run("Find a problem in the project and propose an improvement.")
        self.assertEqual(AgentState.WAIT_FOR_USER, run.state)
        self.assertEqual(1, len(coding.objectives))
        self.assertFalse(coding.applied)
        self.assertEqual(0, deploy.calls)
        self.assertIn("Improvement proposed", run.result)
        self.assertIn("Tests:", run.result)
        self.assertIn("94/94 passed", run.result)
        self.assertIn("Apply?", run.result)
        self.assertIn("No production code was modified", run.result)
        self.assertIsNotNone(run.proposal)
        self.assertTrue(tuple(getattr(run.proposal, "changes", ()) or ()))


class ChatPathUntouchedTest(unittest.TestCase):
    def test_normal_chat_does_not_enter_loop(self) -> None:
        llm = FakeLLM()
        tools = FakeTools()
        loop = _loop(llm=llm, tools=tools)
        ctrl = SimpleNamespace(_agent_loop=loop, llm=llm)
        orch = ConversationOrchestrator.from_controller(ctrl)
        reply = orch.ask("What is Python?")
        self.assertEqual("success", reply.status)
        self.assertEqual([], tools.calls)
        self.assertTrue(llm.prompts)
        self.assertNotEqual(AgentState.COMPLETE, getattr(loop.last_run, "state", None))

    def test_inspect_goal_uses_loop_not_one_shot_only(self) -> None:
        llm = FakeLLM()
        tools = FakeTools()
        loop = _loop(llm=llm, tools=tools)
        ctrl = SimpleNamespace(_agent_loop=loop, llm=llm)
        orch = ConversationOrchestrator.from_controller(ctrl)
        result = orch.process("Inspect my project and determine what needs improvement.")
        self.assertEqual("agent", result.intent)
        self.assertEqual(2, len(tools.calls))
        self.assertIn("Findings:", result.text)

    def test_yes_resumes_paused_agent_same_run(self) -> None:
        tools = FakeTools()
        tools.approval_for = "read_project"
        loop = _loop(tools=tools)
        ctrl = SimpleNamespace(_agent_loop=loop, llm=FakeLLM())
        orch = ConversationOrchestrator.from_controller(ctrl)
        paused = orch.process("Inspect my project and determine what needs improvement.")
        self.assertEqual("warning", paused.status)
        self.assertIn("Approve / Reject", paused.text)
        run_id = loop.last_run.run_id  # type: ignore[union-attr]
        continued = orch.process("yes")
        self.assertEqual(run_id, loop.last_run.run_id)  # type: ignore[union-attr]
        self.assertEqual(AgentState.COMPLETE, loop.last_run.state)  # type: ignore[union-attr]
        self.assertEqual("agent", continued.intent)
        self.assertIn("Findings:", continued.text)

    def test_stream_emits_agent_events_and_keeps_done(self) -> None:
        loop = _loop()
        ctrl = SimpleNamespace(_agent_loop=loop, llm=FakeLLM())
        orch = ConversationOrchestrator.from_controller(ctrl)
        events = list(
            orch.process_stream("Inspect my project and determine what needs improvement.")
        )
        kinds = [str(item.get("type", "")) for item in events]
        self.assertIn("agent_state", kinds)
        self.assertIn("task", kinds)
        self.assertIn("tool", kinds)
        self.assertIn("tool_result", kinds)
        self.assertIn("completed", kinds)
        self.assertEqual("done", kinds[-1])
        self.assertTrue(events[-1].get("ok"))

    def test_bare_continue_without_active_work_uses_chat(self) -> None:
        spy = FakeLLM()
        loop = _loop()
        ctrl = SimpleNamespace(_agent_loop=loop, llm=spy, goals=None, planning=None)
        orch = ConversationOrchestrator.from_controller(ctrl)
        result = orch.process("continue")
        self.assertNotEqual("agent", result.intent)
        self.assertEqual(1, len(spy.prompts))
        self.assertIn("continue", spy.prompts[0].lower())


def _tmp_settings() -> AstraSettings:
    root = Path(tempfile.mkdtemp())
    return AstraSettings(
        memory=MemorySettings(
            database_path=root / "mem.db",
            vector_index_path=root / "vectors.faiss",
        ),
        knowledge=KnowledgeSettings(database_path=root / "knowledge.db"),
        planning=PlanningSettings(database_path=root / "planning.db"),
    )


class ControllerOwnsLoopTest(unittest.TestCase):
    def test_controller_creates_loop_and_chat_hello_stays_one_shot(self) -> None:
        spy = FakeLLM()
        settings = _tmp_settings()
        ctrl = AstraController(settings, llm_factory=lambda _s: spy)
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        self.assertIsNotNone(ctrl.agent_loop)
        orch = ConversationOrchestrator.from_controller(ctrl)
        reply = orch.ask("How are you?")
        self.assertEqual("success", reply.status)
        self.assertEqual(1, len(spy.prompts))


if __name__ == "__main__":
    unittest.main()
