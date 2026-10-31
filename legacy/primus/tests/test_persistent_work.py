"""Persistent goals via existing backlog. Fake LLM. No GGUF."""

from __future__ import annotations

import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace

from astra.core.agent_loop import AgentState, is_execution_goal
from astra.core.persistent_work import (
    complete_task,
    ensure_goal,
    find_active_goal,
    is_pause_request,
    is_persistent_objective,
    is_resume_request,
    next_backlog_task,
    priority_from_message,
    progress_lines,
    resume_lines,
)
from astra.planning.contracts import Priority, TaskStatus
from astra.planning.goals import Goal, GoalStatus
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST
from tests.test_agent_loop import FakeLLM, FakeTools, _loop


_GOAL = "Prepare my project for a hackathon."


class FakeBacklog:
    def __init__(self) -> None:
        self.tasks: dict[str, object] = {}
        self._n = 0

    def add(self, task: object) -> str:
        self._n += 1
        ident = str(getattr(task, "task_id", "") or "") or f"t{self._n}"
        stored = replace(task, task_id=ident)  # type: ignore[type-var]
        self.tasks[ident] = stored
        return ident

    def get(self, task_id: str) -> object | None:
        return self.tasks.get(task_id)

    def update_status(self, task_id: str, status: TaskStatus) -> object:
        current = self.tasks[task_id]
        if current.status in {TaskStatus.DONE, TaskStatus.DROPPED}:
            raise RuntimeError("terminal")
        stored = replace(current, status=status)  # type: ignore[type-var]
        self.tasks[task_id] = stored
        return stored

    def complete(self, task_id: str, *, completion_note: str = "") -> object:
        current = self.tasks[task_id]
        stored = replace(
            current, status=TaskStatus.DONE, completion_note=completion_note
        )  # type: ignore[type-var]
        self.tasks[task_id] = stored
        return stored

    def update_task(self, task: object) -> object:
        current = self.tasks[task.task_id]
        stored = replace(
            current,
            title=task.title,
            notes=task.notes,
            priority=task.priority,
            dependencies=task.dependencies,
        )  # type: ignore[type-var]
        self.tasks[task.task_id] = stored
        return stored

    def list_tasks(self, *, status: TaskStatus | None = None, priority=None):
        items = list(self.tasks.values())
        if status is not None:
            items = [item for item in items if item.status is status]
        return items


class FakeGoals:
    def __init__(self) -> None:
        self.goals: dict[str, Goal] = {}
        self._n = 0

    def add_goal(self, goal: Goal) -> str:
        self._n += 1
        ident = goal.goal_id or f"g{self._n}"
        self.goals[ident] = replace(goal, goal_id=ident)
        return ident

    def get_goal(self, goal_id: str) -> Goal | None:
        return self.goals.get(goal_id)

    def list_goals(self, *, status: GoalStatus | None = None):
        items = list(self.goals.values())
        if status is not None:
            items = [item for item in items if item.status is status]
        return items

    def link_task(self, goal_id: str, task_id: str) -> Goal:
        current = self.goals[goal_id]
        updated = replace(current, task_ids=current.task_ids + (task_id,))
        self.goals[goal_id] = updated
        return updated

    def evaluate(self, goal_id: str) -> bool:
        return False

    def mark_achieved(self, goal_id: str) -> Goal:
        current = self.goals[goal_id]
        updated = replace(current, status=GoalStatus.ACHIEVED)
        self.goals[goal_id] = updated
        return updated


def _stores() -> tuple[FakeGoals, FakeBacklog]:
    return FakeGoals(), FakeBacklog()


class PersistentObjectiveTest(unittest.TestCase):
    def test_chat_not_persistent(self) -> None:
        self.assertFalse(is_persistent_objective("What is a hackathon?"))
        self.assertFalse(is_persistent_objective("Hello."))
        self.assertTrue(is_persistent_objective(_GOAL))
        self.assertTrue(is_execution_goal(_GOAL))
        self.assertTrue(is_resume_request("Continue."))
        self.assertTrue(is_resume_request("Continue my project task."))
        self.assertFalse(is_resume_request("Continue my C learning plan."))
        self.assertTrue(is_pause_request("pause"))
        self.assertEqual(Priority.P0, priority_from_message("high priority hackathon"))


class PersistentGoalLifecycleTest(unittest.TestCase):
    def test_create_tasks_dependencies_progress(self) -> None:
        goals, planning = _stores()
        goal = ensure_goal(goals, planning, _GOAL, priority=Priority.P1)
        assert goal is not None
        self.assertEqual(GoalStatus.ACTIVE, goal.status)
        self.assertEqual(6, len(goal.task_ids))
        first = planning.get(goal.task_ids[0])
        second = planning.get(goal.task_ids[1])
        assert first is not None and second is not None
        self.assertEqual(TaskStatus.TODO, first.status)
        self.assertEqual((first.task_id,), second.dependencies)
        self.assertEqual(first.task_id, next_backlog_task(planning, goal).task_id)
        complete_task(planning, first.task_id, result="inspected")
        goal = goals.get_goal(goal.goal_id)
        nxt = next_backlog_task(planning, goal)
        assert nxt is not None
        self.assertEqual(second.task_id, nxt.task_id)
        lines = "\n".join(progress_lines(planning, goal))
        self.assertIn("1 / 6", lines)
        self.assertIn("Find problems", lines)

    def test_loop_creates_goal_and_completes_one_task(self) -> None:
        goals, planning = _stores()
        run = _loop(
            llm=FakeLLM(default=""),
            planning=planning,
            goals=goals,
        ).run(_GOAL)
        self.assertEqual(AgentState.COMPLETE, run.state)
        self.assertTrue(run.persistent_goal_id)
        self.assertTrue(run.backlog_task_id)
        stored = goals.get_goal(run.persistent_goal_id)
        assert stored is not None
        self.assertEqual(GoalStatus.ACTIVE, stored.status)
        done = [planning.get(tid) for tid in stored.task_ids]
        self.assertEqual(TaskStatus.DONE, done[0].status)
        self.assertTrue(all(item.status is TaskStatus.TODO for item in done[1:]))
        self.assertIn("Goal created", run.result)
        self.assertIn("1 / 6", run.result)

    def test_goal_survives_agent_run_and_resume(self) -> None:
        goals, planning = _stores()
        first = _loop(llm=FakeLLM(default=""), planning=planning, goals=goals).run(_GOAL)
        self.assertEqual(AgentState.COMPLETE, first.state)
        second_loop = _loop(llm=FakeLLM(default=""), planning=planning, goals=goals)
        second_loop.active_goal_id = first.persistent_goal_id
        second = second_loop.run("Continue.")
        self.assertEqual(AgentState.COMPLETE, second.state)
        self.assertEqual(first.persistent_goal_id, second.persistent_goal_id)
        self.assertNotEqual(first.backlog_task_id, second.backlog_task_id)
        self.assertIn("Resuming", second.progress_note)
        self.assertIn("1/6", second.progress_note.replace(" ", ""))
        stored = goals.get_goal(first.persistent_goal_id)
        done = [planning.get(tid).status for tid in stored.task_ids]
        self.assertEqual(2, done.count(TaskStatus.DONE))

    def test_pause_keeps_goal_active(self) -> None:
        goals, planning = _stores()
        loop = _loop(llm=FakeLLM(default=""), planning=planning, goals=goals)
        for event in loop.iter_run(_GOAL):
            if event.type == "plan":
                break
        paused = loop.pause()
        assert paused is not None
        self.assertEqual(AgentState.PAUSED, paused.state)
        stored = goals.get_goal(paused.persistent_goal_id)
        assert stored is not None
        self.assertEqual(GoalStatus.ACTIVE, stored.status)
        self.assertIn("Progress", paused.result)

    def test_approval_wait_same_goal(self) -> None:
        goals, planning = _stores()
        goal = ensure_goal(goals, planning, _GOAL, priority=Priority.P1)
        assert goal is not None
        for tid in goal.task_ids[:-1]:
            complete_task(planning, tid, result="ok")
        tools = FakeTools()
        tools.approval_for = "write_text"
        loop = _loop(llm=FakeLLM(default=""), tools=tools, planning=planning, goals=goals)
        run = loop.run(_GOAL)
        self.assertEqual(AgentState.WAIT_FOR_USER, run.state)
        self.assertEqual(GoalStatus.ACTIVE, goals.get_goal(run.persistent_goal_id).status)
        last = planning.get(goal.task_ids[-1])
        self.assertEqual(TaskStatus.IN_PROGRESS, last.status)
        resumed = loop.resume(True)
        self.assertEqual(run.run_id, resumed.run_id)
        self.assertEqual(AgentState.COMPLETE, resumed.state)
        self.assertEqual(TaskStatus.DONE, planning.get(goal.task_ids[-1]).status)

    def test_failed_task_stays_on_goal(self) -> None:
        goals, planning = _stores()
        tools = FakeTools()
        tools.failures_left = 99
        run = _loop(
            llm=FakeLLM(default=""),
            tools=tools,
            planning=planning,
            goals=goals,
            max_replans=1,
            max_steps=8,
        ).run(_GOAL)
        self.assertEqual(AgentState.FAILED, run.state)
        stored = goals.get_goal(run.persistent_goal_id)
        assert stored is not None
        self.assertEqual(GoalStatus.ACTIVE, stored.status)
        failed = planning.get(run.backlog_task_id)
        self.assertEqual(TaskStatus.BLOCKED, failed.status)
        self.assertIn("error=", failed.notes)

    def test_malformed_llm_still_persists(self) -> None:
        goals, planning = _stores()
        run = _loop(
            llm=FakeLLM(default="not a valid TASK line"),
            planning=planning,
            goals=goals,
        ).run(_GOAL)
        self.assertEqual(AgentState.COMPLETE, run.state)
        self.assertTrue(goals.list_goals(status=GoalStatus.ACTIVE))

    def test_interrupted_agent(self) -> None:
        goals, planning = _stores()
        loop = _loop(llm=FakeLLM(default=""), planning=planning, goals=goals)
        gen = loop.iter_run(_GOAL)
        for _ in range(6):
            next(gen)
        paused = loop.pause()
        assert paused is not None
        self.assertEqual(AgentState.PAUSED, paused.state)
        self.assertEqual(GoalStatus.ACTIVE, goals.get_goal(paused.persistent_goal_id).status)

    def test_chatbot_and_security_unchanged(self) -> None:
        self.assertFalse(is_execution_goal("What is Python?"))
        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))
        from astra.presentation.orchestration import ConversationOrchestrator

        tools = FakeTools()
        loop = _loop(llm=FakeLLM(), tools=tools)
        orch = ConversationOrchestrator.from_controller(
            SimpleNamespace(_agent_loop=loop, llm=FakeLLM())
        )
        reply = orch.ask("What is Python?")
        self.assertEqual("success", reply.status)
        self.assertEqual([], tools.calls)


class PersistentSqliteTest(unittest.TestCase):
    def test_sqlite_goal_survives_new_loop(self) -> None:
        from astra.memory.sqlite_store import SQLiteMemoryStore
        from astra.planning.goal_manager import SQLiteGoalManager
        from astra.planning.sqlite_store import SQLitePlanningStore

        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        planning = SQLitePlanningStore(root / "planning.db")
        planning.initialise()
        self.addCleanup(planning.close)
        memory = SQLiteMemoryStore(root / "mem.db")
        memory.initialise()
        self.addCleanup(memory.close)
        knowledge = SimpleNamespace(search=lambda *a, **k: [])
        goals = SQLiteGoalManager(
            root / "planning.db",
            backlog=planning,
            memory=memory,
            knowledge=knowledge,
        )
        goals.initialise()
        self.addCleanup(goals.close)
        first = _loop(llm=FakeLLM(default=""), planning=planning, goals=goals).run(_GOAL)
        self.assertTrue(first.persistent_goal_id)
        found = find_active_goal(goals, _GOAL)
        assert found is not None
        self.assertEqual(first.persistent_goal_id, found.goal_id)
        later = _loop(llm=FakeLLM(default=""), planning=planning, goals=goals)
        later.active_goal_id = found.goal_id
        second = later.run("Continue.")
        self.assertIn("Resuming", second.result)
        self.assertEqual(found.goal_id, second.persistent_goal_id)


if __name__ == "__main__":
    unittest.main()
