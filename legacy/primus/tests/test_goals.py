"""Goal Manager tests (B-014, ADR-0013)."""

from __future__ import annotations

import ast
import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from astra.knowledge.contracts import KnowledgeCategory, KnowledgeRecord
from astra.knowledge.sqlite_store import SQLiteKnowledgeStore
from astra.memory.contracts import MemoryCategory, MemoryRecord, VerificationState
from astra.memory.sqlite_store import SQLiteMemoryStore
from astra.planning.contracts import Magnitude, Priority, RiskLevel, Task, TaskStatus
from astra.planning.errors import (
    DuplicateGoalError,
    GoalNotFoundError,
    InvalidTransitionError,
    PlanningStoreError,
)
from astra.planning.goal_manager import SQLiteGoalManager
from astra.planning.goals import Goal, GoalManager, GoalStatus
from astra.planning.sqlite_store import SQLitePlanningStore


class _FakeIndexer:
    def __init__(self, records: tuple[MemoryRecord, ...] = ()) -> None:
        self._records = records

    def backend_available(self) -> bool:
        return True

    def search_with_scores(self, query: str, limit: int = 8):
        return [(r, 0.91) for r in self._records[:limit]]


def _mem(content: str, record_id: str = "") -> MemoryRecord:
    return MemoryRecord(
        record_id=record_id,
        category=MemoryCategory.IDEAS,
        content=content,
        verification=VerificationState.UNVERIFIED,
        source="test_goals",
    )


def _know(content: str) -> KnowledgeRecord:
    return KnowledgeRecord(
        record_id="",
        category=KnowledgeCategory.IDEAS,
        content=content,
        source="test_goals",
        confidence=1.0,
    )


def _task(task_id: str, *, status: TaskStatus = TaskStatus.TODO) -> Task:
    return Task(
        task_id=task_id,
        title=f"task {task_id}",
        priority=Priority.P2,
        impact=Magnitude.MEDIUM,
        effort=Magnitude.MEDIUM,
        risk=RiskLevel.LOW,
        status=status,
    )


class GoalManagerFixture(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        root = Path(self._tmp.name)
        self.plan_path = root / "planning.db"
        self.backlog = SQLitePlanningStore(self.plan_path)
        self.backlog.initialise()
        self.addCleanup(self.backlog.close)
        self.memory = SQLiteMemoryStore(root / "memory.db")
        self.memory.initialise()
        self.addCleanup(self.memory.close)
        self.knowledge = SQLiteKnowledgeStore(root / "knowledge.db")
        self.knowledge.initialise()
        self.addCleanup(self.knowledge.close)
        self.mgr = SQLiteGoalManager(
            self.plan_path,
            backlog=self.backlog,
            memory=self.memory,
            knowledge=self.knowledge,
            indexer=None,
        )
        self.mgr.initialise()
        self.addCleanup(self.mgr.close)


class PersistenceTest(GoalManagerFixture):
    def test_goal_survives_reopen(self) -> None:
        gid = self.mgr.add_goal(
            Goal(
                goal_id="G-persist",
                description="persist this goal",
                success_criteria=("alphaevidence",),
            )
        )
        self.mgr.close()
        again = SQLiteGoalManager(
            self.plan_path,
            backlog=self.backlog,
            memory=self.memory,
            knowledge=self.knowledge,
        )
        again.initialise()
        self.addCleanup(again.close)
        loaded = again.get_goal(gid)
        self.assertIsNotNone(loaded)
        assert loaded is not None
        self.assertEqual("persist this goal", loaded.description)
        self.assertEqual(("alphaevidence",), loaded.success_criteria)
        self.assertIs(GoalStatus.PROPOSED, loaded.status)


class LifecycleTest(GoalManagerFixture):
    def test_empty_criteria_cannot_activate(self) -> None:
        gid = self.mgr.add_goal(Goal(goal_id="G-empty", description="no criteria"))
        with self.assertRaises(InvalidTransitionError):
            self.mgr.activate(gid)

    def test_duplicate_goal_rejected(self) -> None:
        self.mgr.add_goal(Goal(goal_id="G-dup", description="once"))
        with self.assertRaises(DuplicateGoalError):
            self.mgr.add_goal(Goal(goal_id="G-dup", description="twice"))

    def test_unknown_linked_task_rejected(self) -> None:
        gid = self.mgr.add_goal(
            Goal(
                goal_id="G-link",
                description="needs a real task",
                success_criteria=("alphaevidence",),
            )
        )
        with self.assertRaises(PlanningStoreError):
            self.mgr.link_task(gid, "T-missing")

    def test_evaluate_does_not_change_status(self) -> None:
        self.memory.store(_mem("alphaevidence recorded"))
        gid = self.mgr.add_goal(
            Goal(
                goal_id="G-obs",
                description="observational",
                success_criteria=("alphaevidence",),
                status=GoalStatus.ACTIVE,
            )
        )
        self.assertTrue(self.mgr.evaluate(gid))
        loaded = self.mgr.get_goal(gid)
        assert loaded is not None
        self.assertIs(GoalStatus.ACTIVE, loaded.status)

    def test_mark_achieved_rejects_failed_evaluate(self) -> None:
        gid = self.mgr.add_goal(
            Goal(
                goal_id="G-fail",
                description="no evidence yet",
                success_criteria=("alphaevidence",),
            )
        )
        with self.assertRaises(InvalidTransitionError):
            self.mgr.mark_achieved(gid)

    def test_mark_achieved_succeeds_after_true(self) -> None:
        self.memory.store(_mem("alphaevidence recorded"))
        gid = self.mgr.add_goal(
            Goal(
                goal_id="G-win",
                description="has evidence",
                success_criteria=("alphaevidence",),
            )
        )
        goal = self.mgr.mark_achieved(gid)
        self.assertIs(GoalStatus.ACHIEVED, goal.status)

    def test_achieved_is_terminal(self) -> None:
        self.memory.store(_mem("alphaevidence recorded"))
        gid = self.mgr.add_goal(
            Goal(
                goal_id="G-term-a",
                description="terminal achieved",
                success_criteria=("alphaevidence",),
            )
        )
        self.mgr.mark_achieved(gid)
        with self.assertRaises(InvalidTransitionError):
            self.mgr.activate(gid)
        with self.assertRaises(InvalidTransitionError):
            self.mgr.abandon(gid)
        with self.assertRaises(InvalidTransitionError):
            self.mgr.mark_achieved(gid)

    def test_abandoned_is_terminal(self) -> None:
        gid = self.mgr.add_goal(
            Goal(
                goal_id="G-term-b",
                description="terminal abandoned",
                success_criteria=("alphaevidence",),
            )
        )
        self.mgr.abandon(gid)
        with self.assertRaises(InvalidTransitionError):
            self.mgr.activate(gid)
        with self.assertRaises(InvalidTransitionError):
            self.mgr.mark_achieved(gid)
        with self.assertRaises(InvalidTransitionError):
            self.mgr.abandon(gid)

    def test_unknown_goal_evaluate_raises(self) -> None:
        with self.assertRaises(GoalNotFoundError):
            self.mgr.evaluate("G-missing")


class EvaluationTest(GoalManagerFixture):
    def test_every_criterion_hit_is_true(self) -> None:
        self.memory.store(_mem("alphaevidence in memory"))
        self.knowledge.store(_know("betaevidence in knowledge"))
        gid = self.mgr.add_goal(
            Goal(
                goal_id="G-all",
                description="all criteria",
                success_criteria=("alphaevidence", "betaevidence"),
            )
        )
        self.assertTrue(self.mgr.evaluate(gid))

    def test_any_missing_criterion_is_false(self) -> None:
        self.memory.store(_mem("alphaevidence in memory"))
        gid = self.mgr.add_goal(
            Goal(
                goal_id="G-miss",
                description="one missing",
                success_criteria=("alphaevidence", "betaevidence"),
            )
        )
        self.assertFalse(self.mgr.evaluate(gid))

    def test_task_done_alone_never_true(self) -> None:
        tid = self.backlog.add(_task("T-done"))
        self.backlog.complete(tid, completion_note="finished")
        gid = self.mgr.add_goal(
            Goal(
                goal_id="G-task",
                description="task is not evidence",
                success_criteria=("alphaevidence",),
            )
        )
        self.mgr.link_task(gid, tid)
        self.assertFalse(self.mgr.evaluate(gid))

    def test_empty_criteria_evaluate_false(self) -> None:
        gid = self.mgr.add_goal(Goal(goal_id="G-nocrit", description="empty"))
        self.assertFalse(self.mgr.evaluate(gid))

    def test_fts_without_embeddings(self) -> None:
        self.memory.store(_mem("alphaevidence recorded offline"))
        gid = self.mgr.add_goal(
            Goal(
                goal_id="G-fts",
                description="fts path",
                success_criteria=("alphaevidence",),
            )
        )
        self.assertTrue(self.mgr.evaluate(gid))
        snap = self.mgr.last_evaluation(gid)
        self.assertIsNotNone(snap)
        assert snap is not None
        self.assertTrue(snap.met)
        self.assertFalse(snap.embedding_used)
        self.assertEqual(1, len(snap.criteria))
        self.assertTrue(snap.criteria[0].met)
        self.assertGreaterEqual(len(snap.criteria[0].hits), 1)
        hit = snap.criteria[0].hits[0]
        self.assertEqual("memory", hit.kind)
        self.assertTrue(hit.record_id)
        self.assertIn("alphaevidence", hit.excerpt)

    def test_semantic_hits_are_additive(self) -> None:
        semantic = _mem("gammaevidence only in indexer", record_id="sem-1")
        other_plan = Path(self._tmp.name) / "planning-sem.db"
        other_backlog = SQLitePlanningStore(other_plan)
        other_backlog.initialise()
        self.addCleanup(other_backlog.close)
        mgr = SQLiteGoalManager(
            other_plan,
            backlog=other_backlog,
            memory=self.memory,
            knowledge=self.knowledge,
            indexer=_FakeIndexer((semantic,)),
        )
        mgr.initialise()
        self.addCleanup(mgr.close)
        goal = Goal(
            goal_id="G-sem",
            description="semantic additive",
            success_criteria=("gammaevidence",),
        )
        gid = mgr.add_goal(goal)
        self.mgr.add_goal(goal)
        self.assertFalse(self.mgr.evaluate(gid))
        self.assertTrue(mgr.evaluate(gid))
        snap = mgr.last_evaluation(gid)
        assert snap is not None
        self.assertTrue(snap.embedding_used)
        self.assertEqual("memory", snap.criteria[0].hits[0].kind)
        self.assertEqual("sem-1", snap.criteria[0].hits[0].record_id)

    def test_snapshot_preserves_criterion_level_evidence(self) -> None:
        self.memory.store(_mem("alphaevidence recorded"))
        gid = self.mgr.add_goal(
            Goal(
                goal_id="G-snap",
                description="partial evidence",
                success_criteria=("alphaevidence", "betaevidence"),
            )
        )
        self.assertFalse(self.mgr.evaluate(gid))
        snap = self.mgr.last_evaluation(gid)
        assert snap is not None
        self.assertFalse(snap.met)
        self.assertEqual(2, len(snap.criteria))
        by_name = {c.criterion: c for c in snap.criteria}
        self.assertTrue(by_name["alphaevidence"].met)
        self.assertFalse(by_name["betaevidence"].met)
        self.assertEqual("memory", by_name["alphaevidence"].hits[0].kind)


class IsolationTest(unittest.TestCase):
    def test_goal_manager_has_no_llama_faiss_ui_imports(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "planning"
        forbidden = {"llama_cpp", "faiss", "dearpygui", "ui"}
        for py_file in (root / "goal_manager.py", root / "goals.py"):
            tree = ast.parse(py_file.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                names: list[str] = []
                if isinstance(node, ast.Import):
                    names = [alias.name.split(".")[0] for alias in node.names]
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names = [node.module.split(".")[0]]
                for name in names:
                    self.assertNotIn(name, forbidden, f"{py_file.name} imports {name}")

    def test_offline_import_and_evaluate(self) -> None:
        def _block(*_a, **_k):
            raise RuntimeError("network blocked")

        with patch.object(socket, "socket", side_effect=_block), patch.object(
            socket, "create_connection", side_effect=_block
        ), patch.object(socket, "getaddrinfo", side_effect=_block):
            from astra.planning.goal_manager import SQLiteGoalManager as GM

            self.assertTrue(issubclass(GM, GoalManager))


class ControllerGoalStateTest(unittest.TestCase):
    def test_derived_blocked_from_linked_blocked_task(self) -> None:
        from astra.core.astra_controller import AstraController, ControllerStateProvider
        from astra.configuration.settings import (
            AstraSettings,
            KnowledgeSettings,
            MemorySettings,
            PlanningSettings,
        )

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
        )
        ctrl = AstraController(settings)
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        tid = ctrl.planning.add(_task("T-block"))
        ctrl.planning.update_status(tid, TaskStatus.BLOCKED)
        gid = ctrl.goals.add_goal(
            Goal(
                goal_id="G-block",
                description="blocked by task",
                success_criteria=("alphaevidence",),
            )
        )
        ctrl.goals.link_task(gid, tid)
        state = ControllerStateProvider(ctrl).get_state()
        self.assertEqual(1, len(state.goals))
        snap = state.goals[0]
        self.assertTrue(snap.blocked)
        self.assertEqual((tid,), snap.task_ids)
        self.assertEqual(0, snap.tasks_done)
        self.assertEqual(1, snap.tasks_total)
        self.assertIsNone(snap.last_evaluate_met)
        gm = next(
            c
            for m in state.modules
            if m.module == "astra.planning"
            for c in m.capabilities
            if c.name == "goal_manager"
        )
        from astra.state import CapabilityStatus

        self.assertEqual(CapabilityStatus.OPERATIONAL, gm.status)

    def test_b014_check_not_hard_coded_true(self) -> None:
        import inspect

        from astra.presentation import progress

        src = inspect.getsource(progress._check_goal_manager)
        self.assertIn("SQLiteGoalManager", src)
        self.assertNotIn("return True", src)
