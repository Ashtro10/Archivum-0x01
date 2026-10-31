"""Comprehensive tests for the Planning Engine (B-006).

Coverage map (ADR-0009 requirements + spec items):

1.  Task creation — basic add and retrieve
2.  Persistence — records survive close/reopen
3.  Restart/reload — schema survives re-initialise
4.  Status transitions — every valid transition
5.  Priority ordering — P0 first in list_tasks and actionable_tasks
6.  Impact/effort/risk fields — all enum values stored and retrieved
7.  Dependency creation — simple dep chain
8.  Missing dependency — add() raises DependencyError
9.  Completed dependency — dep DONE → task is actionable
10. Blocked dependency — dep not DONE → task not actionable
11. Dependency cycles — DFS detects and rejects
12. Actionable-task calculation — SQL query correctness
13. Cancellation — cancel() marks DROPPED, terminal
14. Completion — complete() marks DONE, terminal, records note
15. Invalid transitions — DONE/DROPPED → any raises InvalidTransitionError
16. Duplicate IDs — DuplicateTaskError on re-add
17. Malformed data — empty title rejected
18. Database constraints — terminal trigger via raw SQL
19. Deterministic behaviour — same input → same output
20. Offline behaviour — no socket access
21. Architecture/interface compliance — isinstance checks
22. Planning Engine does not import or invoke reasoning model
23. update_task — mutable fields change; status unchanged
24. update_task — dep cycle rejected
25. validate_dependencies — lists missing + non-DONE
26. Priority filter on list_tasks
27. Status filter on list_tasks
28. plan() — keyword search + topological order
29. plan() — empty objective raises ValueError
30. next_actionable() — highest-priority actionable task
31. unresolved_dependencies() — delegates to backlog
32. Cancelled dep → unresolvable
33. Schema version is 2 after fresh init
34. Initialise is idempotent
35. PlanningSettings validation
36. Self-dependency rejected
37. iter_tasks streams without materialising
38. complete() records completion_note and completed_at
39. cancel() records completed_at
40. list_tasks with priority+status combined filter
"""

from __future__ import annotations

import socket
import sqlite3
import tempfile
import unittest
from datetime import timezone
from pathlib import Path
from unittest.mock import patch

from astra.planning.contracts import (
    Backlog,
    Magnitude,
    PlanningEngine,
    Priority,
    RiskLevel,
    Task,
    TaskStatus,
    TERMINAL_STATUSES,
)
from astra.planning.engine import SimplePlanningEngine
from astra.planning.errors import (
    DependencyError,
    DuplicateTaskError,
    InvalidTransitionError,
    PlanningStoreError,
)
from astra.planning.schema import current_version
from astra.planning.sqlite_store import SQLitePlanningStore


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _temp_store() -> SQLitePlanningStore:
    tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
    tmp.close()
    store = SQLitePlanningStore(tmp.name)
    store.initialise()
    return store


def _task(
    task_id: str = "",
    title: str = "Default Task",
    priority: Priority = Priority.P2,
    impact: Magnitude = Magnitude.MEDIUM,
    effort: Magnitude = Magnitude.MEDIUM,
    risk: RiskLevel = RiskLevel.LOW,
    dependencies: tuple[str, ...] = (),
    status: TaskStatus = TaskStatus.TODO,
    notes: str = "",
) -> Task:
    return Task(
        task_id=task_id,
        title=title,
        priority=priority,
        impact=impact,
        effort=effort,
        risk=risk,
        dependencies=dependencies,
        status=status,
        notes=notes,
    )


# ---------------------------------------------------------------------------
# 1. Task creation
# ---------------------------------------------------------------------------

class TaskCreationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_add_returns_id(self) -> None:
        tid = self.store.add(_task())
        self.assertTrue(tid)

    def test_explicit_id_preserved(self) -> None:
        tid = self.store.add(_task(task_id="B-099"))
        self.assertEqual(tid, "B-099")

    def test_auto_id_generated_when_empty(self) -> None:
        tid = self.store.add(_task(task_id=""))
        self.assertTrue(tid)  # UUID assigned

    def test_get_after_add(self) -> None:
        tid = self.store.add(_task(title="My Task", priority=Priority.P0))
        fetched = self.store.get(tid)
        self.assertIsNotNone(fetched)
        assert fetched is not None
        self.assertEqual(fetched.title, "My Task")
        self.assertEqual(fetched.priority, Priority.P0)

    def test_all_fields_preserved(self) -> None:
        t = _task(
            task_id="T-full",
            title="Full Task",
            priority=Priority.P1,
            impact=Magnitude.LARGE,
            effort=Magnitude.SMALL,
            risk=RiskLevel.HIGH,
            notes="important context",
        )
        self.store.add(t)
        f = self.store.get("T-full")
        assert f is not None
        self.assertEqual(f.impact, Magnitude.LARGE)
        self.assertEqual(f.effort, Magnitude.SMALL)
        self.assertEqual(f.risk, RiskLevel.HIGH)
        self.assertEqual(f.notes, "important context")

    def test_get_unknown_returns_none(self) -> None:
        self.assertIsNone(self.store.get("does-not-exist"))


# ---------------------------------------------------------------------------
# 2–3. Persistence and reload
# ---------------------------------------------------------------------------

class PersistenceTest(unittest.TestCase):
    def test_records_survive_reopen(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            db_path = f.name

        store = SQLitePlanningStore(db_path)
        store.initialise()
        tid = store.add(_task(task_id="T-persist", title="Persistent Task"))
        store.close()

        store2 = SQLitePlanningStore(db_path)
        store2.initialise()
        fetched = store2.get(tid)
        store2.close()

        self.assertIsNotNone(fetched)
        assert fetched is not None
        self.assertEqual(fetched.title, "Persistent Task")

    def test_schema_version_is_2(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            db_path = f.name
        store = SQLitePlanningStore(db_path)
        store.initialise()
        conn = store._connection
        assert conn is not None
        self.assertEqual(current_version(conn), 2)
        store.close()

    def test_initialise_idempotent(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            db_path = f.name
        store = SQLitePlanningStore(db_path)
        for _ in range(3):
            store.initialise()
        store.close()

    def test_deps_survive_reopen(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            db_path = f.name

        store = SQLitePlanningStore(db_path)
        store.initialise()
        store.add(_task(task_id="A"))
        store.add(_task(task_id="B", dependencies=("A",)))
        store.close()

        store2 = SQLitePlanningStore(db_path)
        store2.initialise()
        b = store2.get("B")
        store2.close()

        assert b is not None
        self.assertIn("A", b.dependencies)


# ---------------------------------------------------------------------------
# 4. Status transitions
# ---------------------------------------------------------------------------

class StatusTransitionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()
        self.tid = self.store.add(_task())

    def tearDown(self) -> None:
        self.store.close()

    def test_todo_to_in_progress(self) -> None:
        t = self.store.update_status(self.tid, TaskStatus.IN_PROGRESS)
        self.assertEqual(t.status, TaskStatus.IN_PROGRESS)

    def test_todo_to_blocked(self) -> None:
        t = self.store.update_status(self.tid, TaskStatus.BLOCKED)
        self.assertEqual(t.status, TaskStatus.BLOCKED)

    def test_todo_to_done(self) -> None:
        t = self.store.update_status(self.tid, TaskStatus.DONE)
        self.assertEqual(t.status, TaskStatus.DONE)

    def test_todo_to_dropped(self) -> None:
        t = self.store.update_status(self.tid, TaskStatus.DROPPED)
        self.assertEqual(t.status, TaskStatus.DROPPED)

    def test_in_progress_to_todo(self) -> None:
        self.store.update_status(self.tid, TaskStatus.IN_PROGRESS)
        t = self.store.update_status(self.tid, TaskStatus.TODO)
        self.assertEqual(t.status, TaskStatus.TODO)

    def test_in_progress_to_blocked(self) -> None:
        self.store.update_status(self.tid, TaskStatus.IN_PROGRESS)
        t = self.store.update_status(self.tid, TaskStatus.BLOCKED)
        self.assertEqual(t.status, TaskStatus.BLOCKED)

    def test_blocked_to_in_progress(self) -> None:
        self.store.update_status(self.tid, TaskStatus.BLOCKED)
        t = self.store.update_status(self.tid, TaskStatus.IN_PROGRESS)
        self.assertEqual(t.status, TaskStatus.IN_PROGRESS)

    def test_in_progress_to_done(self) -> None:
        self.store.update_status(self.tid, TaskStatus.IN_PROGRESS)
        t = self.store.update_status(self.tid, TaskStatus.DONE)
        self.assertEqual(t.status, TaskStatus.DONE)

    def test_all_statuses_round_trip(self) -> None:
        """Every TaskStatus value can be stored and retrieved."""
        for status in TaskStatus:
            with self.subTest(status=status):
                tid = self.store.add(_task())
                if status not in TERMINAL_STATUSES:
                    t = self.store.update_status(tid, status)
                    self.assertEqual(t.status, status)


# ---------------------------------------------------------------------------
# 5. Priority ordering
# ---------------------------------------------------------------------------

class PriorityOrderingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()
        for p in [Priority.P3, Priority.P1, Priority.P0, Priority.P2]:
            self.store.add(_task(priority=p))

    def tearDown(self) -> None:
        self.store.close()

    def test_list_tasks_p0_first(self) -> None:
        tasks = self.store.list_tasks()
        priorities = [t.priority for t in tasks]
        self.assertEqual(priorities[0], Priority.P0)
        self.assertEqual(priorities[-1], Priority.P3)

    def test_p0_before_p1_before_p2_before_p3(self) -> None:
        tasks = self.store.list_tasks()
        priorities = [t.priority for t in tasks]
        self.assertEqual(sorted(priorities, key=lambda p: p.value), priorities)

    def test_actionable_p0_first(self) -> None:
        tasks = self.store.actionable_tasks()
        if tasks:
            self.assertEqual(tasks[0].priority, Priority.P0)


# ---------------------------------------------------------------------------
# 6. Impact/effort/risk fields
# ---------------------------------------------------------------------------

class MagnitudeRiskTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_all_magnitude_values(self) -> None:
        for m in Magnitude:
            with self.subTest(magnitude=m):
                tid = self.store.add(_task(impact=m, effort=m))
                t = self.store.get(tid)
                assert t is not None
                self.assertEqual(t.impact, m)
                self.assertEqual(t.effort, m)

    def test_all_risk_levels(self) -> None:
        for r in RiskLevel:
            with self.subTest(risk=r):
                tid = self.store.add(_task(risk=r))
                t = self.store.get(tid)
                assert t is not None
                self.assertEqual(t.risk, r)


# ---------------------------------------------------------------------------
# 7. Dependency creation
# ---------------------------------------------------------------------------

class DependencyCreationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()
        self.a = self.store.add(_task(task_id="A", title="Foundation"))
        self.b = self.store.add(_task(task_id="B", title="Build", dependencies=("A",)))

    def tearDown(self) -> None:
        self.store.close()

    def test_dep_stored(self) -> None:
        b = self.store.get("B")
        assert b is not None
        self.assertIn("A", b.dependencies)

    def test_dep_chain(self) -> None:
        c = self.store.add(_task(task_id="C", title="Deploy", dependencies=("B",)))
        task_c = self.store.get("C")
        assert task_c is not None
        self.assertIn("B", task_c.dependencies)

    def test_multiple_deps(self) -> None:
        x = self.store.add(_task(task_id="X"))
        y = self.store.add(_task(task_id="Y", dependencies=("A", "X")))
        task_y = self.store.get("Y")
        assert task_y is not None
        self.assertIn("A", task_y.dependencies)
        self.assertIn("X", task_y.dependencies)


# ---------------------------------------------------------------------------
# 8. Missing dependency
# ---------------------------------------------------------------------------

class MissingDependencyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_missing_dep_raises_on_add(self) -> None:
        with self.assertRaises(DependencyError) as ctx:
            self.store.add(_task(dependencies=("nonexistent",)))
        self.assertIn("nonexistent", str(ctx.exception))

    def test_error_message_names_missing_dep(self) -> None:
        with self.assertRaises(DependencyError) as ctx:
            self.store.add(_task(dependencies=("MISSING-ID",)))
        self.assertIn("MISSING-ID", str(ctx.exception))


# ---------------------------------------------------------------------------
# 9–10. Actionable with completed / blocked deps
# ---------------------------------------------------------------------------

class ActionabilityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_no_deps_is_actionable(self) -> None:
        self.store.add(_task(task_id="A"))
        tasks = self.store.actionable_tasks()
        ids = [t.task_id for t in tasks]
        self.assertIn("A", ids)

    def test_dep_done_makes_dependent_actionable(self) -> None:
        self.store.add(_task(task_id="A"))
        self.store.add(_task(task_id="B", dependencies=("A",)))
        self.store.complete("A")
        tasks = self.store.actionable_tasks()
        ids = [t.task_id for t in tasks]
        self.assertIn("B", ids)
        self.assertNotIn("A", ids)  # DONE task not in actionable

    def test_dep_todo_blocks_dependent(self) -> None:
        self.store.add(_task(task_id="A"))  # stays TODO
        self.store.add(_task(task_id="B", dependencies=("A",)))
        tasks = self.store.actionable_tasks()
        ids = [t.task_id for t in tasks]
        self.assertIn("A", ids)    # A is unblocked
        self.assertNotIn("B", ids)  # B is blocked by A

    def test_dep_in_progress_blocks_dependent(self) -> None:
        self.store.add(_task(task_id="A"))
        self.store.add(_task(task_id="B", dependencies=("A",)))
        self.store.update_status("A", TaskStatus.IN_PROGRESS)
        ids = [t.task_id for t in self.store.actionable_tasks()]
        self.assertNotIn("B", ids)

    def test_dep_blocked_blocks_dependent(self) -> None:
        self.store.add(_task(task_id="A"))
        self.store.add(_task(task_id="B", dependencies=("A",)))
        self.store.update_status("A", TaskStatus.BLOCKED)
        ids = [t.task_id for t in self.store.actionable_tasks()]
        self.assertNotIn("B", ids)

    def test_in_progress_task_with_all_deps_done_is_actionable(self) -> None:
        self.store.add(_task(task_id="A"))
        self.store.add(_task(task_id="B", dependencies=("A",), status=TaskStatus.TODO))
        self.store.complete("A")
        self.store.update_status("B", TaskStatus.IN_PROGRESS)
        ids = [t.task_id for t in self.store.actionable_tasks()]
        self.assertIn("B", ids)


# ---------------------------------------------------------------------------
# 11. Dependency cycles
# ---------------------------------------------------------------------------

class DependencyCycleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_direct_cycle_A_depends_A_rejected(self) -> None:
        self.store.add(_task(task_id="A"))
        with self.assertRaises(DependencyError):
            # A depends on itself via update_task
            self.store.update_task(
                Task(
                    task_id="A", title="A", priority=Priority.P2,
                    impact=Magnitude.MEDIUM, effort=Magnitude.MEDIUM,
                    risk=RiskLevel.LOW, dependencies=("A",),
                )
            )

    def test_two_node_cycle_rejected(self) -> None:
        self.store.add(_task(task_id="A"))
        self.store.add(_task(task_id="B", dependencies=("A",)))
        # Trying to make A depend on B would create A→B→A
        with self.assertRaises(DependencyError):
            self.store.update_task(
                Task(
                    task_id="A", title="A", priority=Priority.P2,
                    impact=Magnitude.MEDIUM, effort=Magnitude.MEDIUM,
                    risk=RiskLevel.LOW, dependencies=("B",),
                )
            )

    def test_three_node_cycle_rejected(self) -> None:
        self.store.add(_task(task_id="A"))
        self.store.add(_task(task_id="B", dependencies=("A",)))
        self.store.add(_task(task_id="C", dependencies=("B",)))
        # C→B→A, now trying A→C would give A→C→B→A
        with self.assertRaises(DependencyError):
            self.store.update_task(
                Task(
                    task_id="A", title="A", priority=Priority.P2,
                    impact=Magnitude.MEDIUM, effort=Magnitude.MEDIUM,
                    risk=RiskLevel.LOW, dependencies=("C",),
                )
            )

    def test_valid_dag_is_accepted(self) -> None:
        self.store.add(_task(task_id="A"))
        self.store.add(_task(task_id="B", dependencies=("A",)))
        self.store.add(_task(task_id="C", dependencies=("A",)))
        # Both B and C depend on A — diamond DAG, not a cycle
        cid = self.store.add(_task(task_id="D", dependencies=("B", "C")))
        self.assertTrue(cid)


# ---------------------------------------------------------------------------
# 13–14. Cancellation and completion
# ---------------------------------------------------------------------------

class CompletionCancellationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_complete_marks_done(self) -> None:
        tid = self.store.add(_task())
        t = self.store.complete(tid, completion_note="all done")
        self.assertEqual(t.status, TaskStatus.DONE)

    def test_complete_records_note(self) -> None:
        tid = self.store.add(_task())
        t = self.store.complete(tid, completion_note="shipped v1")
        self.assertEqual(t.completion_note, "shipped v1")

    def test_complete_records_completed_at(self) -> None:
        tid = self.store.add(_task())
        t = self.store.complete(tid)
        self.assertIsNotNone(t.completed_at)

    def test_complete_completed_at_is_utc(self) -> None:
        tid = self.store.add(_task())
        t = self.store.complete(tid)
        assert t.completed_at is not None
        self.assertEqual(t.completed_at.tzinfo, timezone.utc)

    def test_cancel_marks_dropped(self) -> None:
        tid = self.store.add(_task())
        t = self.store.cancel(tid)
        self.assertEqual(t.status, TaskStatus.DROPPED)

    def test_cancel_records_completed_at(self) -> None:
        tid = self.store.add(_task())
        t = self.store.cancel(tid)
        self.assertIsNotNone(t.completed_at)

    def test_complete_default_note_empty(self) -> None:
        tid = self.store.add(_task())
        t = self.store.complete(tid)
        self.assertEqual(t.completion_note, "")


# ---------------------------------------------------------------------------
# 15. Invalid transitions (terminal states)
# ---------------------------------------------------------------------------

class InvalidTransitionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_done_cannot_transition(self) -> None:
        tid = self.store.add(_task())
        self.store.complete(tid)
        for status in TaskStatus:
            if status != TaskStatus.DONE:
                with self.subTest(status=status):
                    with self.assertRaises(InvalidTransitionError):
                        self.store.update_status(tid, status)

    def test_dropped_cannot_transition(self) -> None:
        tid = self.store.add(_task())
        self.store.cancel(tid)
        for status in TaskStatus:
            if status != TaskStatus.DROPPED:
                with self.subTest(status=status):
                    with self.assertRaises(InvalidTransitionError):
                        self.store.update_status(tid, status)

    def test_complete_on_done_raises(self) -> None:
        tid = self.store.add(_task())
        self.store.complete(tid)
        with self.assertRaises(InvalidTransitionError):
            self.store.complete(tid)

    def test_complete_on_dropped_raises(self) -> None:
        tid = self.store.add(_task())
        self.store.cancel(tid)
        with self.assertRaises(InvalidTransitionError):
            self.store.complete(tid)

    def test_cancel_on_done_raises(self) -> None:
        tid = self.store.add(_task())
        self.store.complete(tid)
        with self.assertRaises(InvalidTransitionError):
            self.store.cancel(tid)

    def test_database_trigger_rejects_terminal_transition(self) -> None:
        """Terminal trigger holds even when bypassing Python guard."""
        tid = self.store.add(_task())
        self.store.complete(tid)
        conn = self.store._connection
        assert conn is not None
        with self.assertRaises(sqlite3.IntegrityError) as ctx:
            conn.execute(
                "UPDATE planning_tasks SET status = 'todo' WHERE task_id = ?",
                (tid,),
            )
        self.assertIn("terminal", str(ctx.exception).lower())

    def test_update_task_on_done_raises(self) -> None:
        tid = self.store.add(_task(task_id="done-task"))
        self.store.complete(tid)
        with self.assertRaises(InvalidTransitionError):
            self.store.update_task(_task(task_id="done-task", title="Changed"))

    def test_update_task_on_dropped_raises(self) -> None:
        tid = self.store.add(_task(task_id="drop-task"))
        self.store.cancel(tid)
        with self.assertRaises(InvalidTransitionError):
            self.store.update_task(_task(task_id="drop-task", title="Changed"))


# ---------------------------------------------------------------------------
# 16. Duplicate IDs
# ---------------------------------------------------------------------------

class DuplicateTaskTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_duplicate_id_raises(self) -> None:
        self.store.add(_task(task_id="DUP"))
        with self.assertRaises(DuplicateTaskError):
            self.store.add(_task(task_id="DUP", title="Different title"))


# ---------------------------------------------------------------------------
# 17. Malformed data
# ---------------------------------------------------------------------------

class MalformedDataTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_empty_title_rejected(self) -> None:
        with self.assertRaises(PlanningStoreError):
            self.store.add(_task(title=""))

    def test_whitespace_title_rejected(self) -> None:
        with self.assertRaises(PlanningStoreError):
            self.store.add(_task(title="   "))

    def test_update_task_empty_title_rejected(self) -> None:
        tid = self.store.add(_task(task_id="T1"))
        with self.assertRaises(PlanningStoreError):
            self.store.update_task(Task(
                task_id="T1", title="", priority=Priority.P2,
                impact=Magnitude.MEDIUM, effort=Magnitude.MEDIUM,
                risk=RiskLevel.LOW,
            ))


# ---------------------------------------------------------------------------
# 19. Deterministic behaviour
# ---------------------------------------------------------------------------

class DeterministicTest(unittest.TestCase):
    def test_list_tasks_order_is_stable(self) -> None:
        store = _temp_store()
        for i, p in enumerate([Priority.P3, Priority.P0, Priority.P1, Priority.P2]):
            store.add(_task(task_id=f"T{i}", priority=p))
        first = [(t.task_id, t.priority) for t in store.list_tasks()]
        second = [(t.task_id, t.priority) for t in store.list_tasks()]
        store.close()
        self.assertEqual(first, second)

    def test_actionable_tasks_order_is_stable(self) -> None:
        store = _temp_store()
        for i, p in enumerate([Priority.P2, Priority.P0]):
            store.add(_task(task_id=f"T{i}", priority=p))
        first = [t.task_id for t in store.actionable_tasks()]
        second = [t.task_id for t in store.actionable_tasks()]
        store.close()
        self.assertEqual(first, second)


# ---------------------------------------------------------------------------
# 20. Offline behaviour
# ---------------------------------------------------------------------------

class OfflinePlanningTest(unittest.TestCase):
    def _block(self, *args: object, **kwargs: object) -> None:
        raise AssertionError("network access attempted")

    def test_store_offline(self) -> None:
        store = _temp_store()
        with patch.object(socket, "socket", side_effect=self._block), \
             patch.object(socket, "create_connection", side_effect=self._block), \
             patch.object(socket, "getaddrinfo", side_effect=self._block):
            tid = store.add(_task())
            t = store.get(tid)
        store.close()
        self.assertIsNotNone(t)

    def test_engine_offline(self) -> None:
        store = _temp_store()
        store.add(_task(title="Python recursion task", notes=""))
        engine = SimplePlanningEngine(store)
        with patch.object(socket, "socket", side_effect=self._block), \
             patch.object(socket, "create_connection", side_effect=self._block), \
             patch.object(socket, "getaddrinfo", side_effect=self._block):
            result = engine.plan("recursion")
        store.close()
        self.assertIsInstance(result, tuple)

    def test_no_network_imports_in_planning_modules(self) -> None:
        import ast
        import importlib
        network_libs = {"httpx", "requests", "urllib3", "aiohttp"}
        pkg = importlib.import_module("astra.planning")
        pkg_path = Path(pkg.__file__).parent  # type: ignore[arg-type]
        for py_file in pkg_path.glob("*.py"):
            source = py_file.read_text(encoding="utf-8")
            try:
                tree = ast.parse(source)
            except SyntaxError:
                continue
            for node in ast.walk(tree):
                if isinstance(node, (ast.Import, ast.ImportFrom)):
                    names = (
                        [alias.name for alias in node.names]
                        if isinstance(node, ast.Import)
                        else ([node.module] if node.module else [])
                    )
                    for name in names:
                        root = (name or "").split(".")[0]
                        with self.subTest(file=py_file.name, library=root):
                            self.assertNotIn(root, network_libs)


# ---------------------------------------------------------------------------
# 21–22. Architecture compliance + no reasoning model
# ---------------------------------------------------------------------------

class ArchitectureComplianceTest(unittest.TestCase):
    def test_store_is_backlog(self) -> None:
        self.assertTrue(issubclass(SQLitePlanningStore, Backlog))

    def test_engine_is_planning_engine(self) -> None:
        self.assertTrue(issubclass(SimplePlanningEngine, PlanningEngine))

    def test_store_instance_is_backlog(self) -> None:
        store = _temp_store()
        self.assertIsInstance(store, Backlog)
        store.close()

    def test_engine_does_not_import_reasoning(self) -> None:
        import ast
        from pathlib import Path
        engine_path = Path(__file__).parent.parent / "astra" / "planning" / "engine.py"
        source = engine_path.read_text(encoding="utf-8")
        tree = ast.parse(source)
        for node in ast.walk(tree):
            if isinstance(node, (ast.Import, ast.ImportFrom)):
                names = (
                    [a.name for a in node.names]
                    if isinstance(node, ast.Import)
                    else ([node.module] if node.module else [])
                )
                for name in names:
                    self.assertNotIn(
                        "reasoning", (name or "").lower(),
                        f"engine.py imports reasoning-related module: {name!r}",
                    )

    def test_store_does_not_import_reasoning(self) -> None:
        import ast
        from pathlib import Path
        store_path = Path(__file__).parent.parent / "astra" / "planning" / "sqlite_store.py"
        source = store_path.read_text(encoding="utf-8")
        tree = ast.parse(source)
        for node in ast.walk(tree):
            if isinstance(node, (ast.Import, ast.ImportFrom)):
                names = (
                    [a.name for a in node.names]
                    if isinstance(node, ast.Import)
                    else ([node.module] if node.module else [])
                )
                for name in names:
                    self.assertNotIn(
                        "reasoning", (name or "").lower(),
                        f"sqlite_store.py imports reasoning: {name!r}",
                    )

    def test_task_is_frozen(self) -> None:
        t = _task()
        with self.assertRaises((AttributeError, TypeError)):
            t.title = "mutated"  # type: ignore[misc]

    def test_terminal_statuses_constant(self) -> None:
        from astra.planning.contracts import TERMINAL_STATUSES
        self.assertIn(TaskStatus.DONE, TERMINAL_STATUSES)
        self.assertIn(TaskStatus.DROPPED, TERMINAL_STATUSES)


# ---------------------------------------------------------------------------
# 23–24. update_task
# ---------------------------------------------------------------------------

class UpdateTaskTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()
        self.tid = self.store.add(_task(task_id="U1", title="Original"))

    def tearDown(self) -> None:
        self.store.close()

    def test_title_updated(self) -> None:
        t = self.store.update_task(_task(task_id="U1", title="Updated"))
        self.assertEqual(t.title, "Updated")

    def test_priority_updated(self) -> None:
        t = self.store.update_task(_task(task_id="U1", title="U1", priority=Priority.P0))
        self.assertEqual(t.priority, Priority.P0)

    def test_notes_updated(self) -> None:
        t = self.store.update_task(_task(task_id="U1", title="U1", notes="new notes"))
        self.assertEqual(t.notes, "new notes")

    def test_status_unchanged_by_update_task(self) -> None:
        self.store.update_status(self.tid, TaskStatus.IN_PROGRESS)
        # update_task should NOT change status
        t = self.store.update_task(_task(task_id="U1", title="U1", status=TaskStatus.TODO))
        self.assertEqual(t.status, TaskStatus.IN_PROGRESS)

    def test_deps_updated(self) -> None:
        dep = self.store.add(_task(task_id="DEP"))
        t = self.store.update_task(_task(task_id="U1", title="U1", dependencies=("DEP",)))
        self.assertIn("DEP", t.dependencies)

    def test_update_task_nonexistent_raises(self) -> None:
        with self.assertRaises(PlanningStoreError):
            self.store.update_task(_task(task_id="NONEXISTENT", title="X"))

    def test_update_task_cycle_dep_rejected(self) -> None:
        self.store.add(_task(task_id="A2"))
        self.store.add(_task(task_id="B2", dependencies=("A2",)))
        with self.assertRaises(DependencyError):
            # B2→A2; trying to make A2→B2 creates cycle
            self.store.update_task(
                Task(
                    task_id="A2", title="A2", priority=Priority.P2,
                    impact=Magnitude.MEDIUM, effort=Magnitude.MEDIUM,
                    risk=RiskLevel.LOW, dependencies=("B2",),
                )
            )


# ---------------------------------------------------------------------------
# 25. validate_dependencies
# ---------------------------------------------------------------------------

class ValidateDependenciesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()

    def tearDown(self) -> None:
        self.store.close()

    def test_no_deps_returns_empty(self) -> None:
        tid = self.store.add(_task(task_id="N"))
        self.assertEqual(self.store.validate_dependencies("N"), [])

    def test_done_dep_not_in_unresolved(self) -> None:
        self.store.add(_task(task_id="A"))
        self.store.add(_task(task_id="B", dependencies=("A",)))
        self.store.complete("A")
        self.assertEqual(self.store.validate_dependencies("B"), [])

    def test_todo_dep_in_unresolved(self) -> None:
        self.store.add(_task(task_id="A"))
        self.store.add(_task(task_id="B", dependencies=("A",)))
        unresolved = self.store.validate_dependencies("B")
        self.assertIn("A", unresolved)

    def test_dropped_dep_in_unresolved(self) -> None:
        self.store.add(_task(task_id="A"))
        self.store.add(_task(task_id="B", dependencies=("A",)))
        self.store.cancel("A")
        unresolved = self.store.validate_dependencies("B")
        self.assertIn("A", unresolved)


# ---------------------------------------------------------------------------
# 26–27. Filters on list_tasks
# ---------------------------------------------------------------------------

class ListTasksFilterTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()
        self.store.add(_task(task_id="A", priority=Priority.P0, status=TaskStatus.TODO))
        self.store.add(_task(task_id="B", priority=Priority.P1, status=TaskStatus.TODO))
        self.store.add(_task(task_id="C", priority=Priority.P2))
        self.store.complete("C")

    def tearDown(self) -> None:
        self.store.close()

    def test_filter_by_status(self) -> None:
        todo = self.store.list_tasks(status=TaskStatus.TODO)
        ids = {t.task_id for t in todo}
        self.assertIn("A", ids)
        self.assertNotIn("C", ids)

    def test_filter_by_priority(self) -> None:
        p0 = self.store.list_tasks(priority=Priority.P0)
        self.assertEqual(len(p0), 1)
        self.assertEqual(p0[0].task_id, "A")

    def test_combined_filter(self) -> None:
        result = self.store.list_tasks(status=TaskStatus.TODO, priority=Priority.P1)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0].task_id, "B")

    def test_no_filter_returns_all(self) -> None:
        all_tasks = self.store.list_tasks()
        self.assertEqual(len(all_tasks), 3)

    def test_filter_done(self) -> None:
        done = self.store.list_tasks(status=TaskStatus.DONE)
        self.assertEqual(len(done), 1)
        self.assertEqual(done[0].task_id, "C")


# ---------------------------------------------------------------------------
# 28–31. SimplePlanningEngine
# ---------------------------------------------------------------------------

class PlanningEngineTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = _temp_store()
        self.engine = SimplePlanningEngine(self.store)

    def tearDown(self) -> None:
        self.store.close()

    def test_plan_empty_objective_raises(self) -> None:
        with self.assertRaises(ValueError):
            self.engine.plan("")

    def test_plan_whitespace_objective_raises(self) -> None:
        with self.assertRaises(ValueError):
            self.engine.plan("   ")

    def test_plan_no_match_returns_empty(self) -> None:
        self.store.add(_task(title="Implement memory store"))
        result = self.engine.plan("xyzzy-nonexistent-keyword")
        self.assertEqual(len(result), 0)

    def test_plan_returns_matching_tasks(self) -> None:
        self.store.add(_task(task_id="T1", title="Python async coroutine"))
        self.store.add(_task(task_id="T2", title="Install Docker"))
        result = self.engine.plan("python")
        ids = [t.task_id for t in result]
        self.assertIn("T1", ids)
        self.assertNotIn("T2", ids)

    def test_plan_keyword_in_notes(self) -> None:
        self.store.add(_task(task_id="T3", title="Build something", notes="uses redis caching"))
        result = self.engine.plan("redis")
        ids = [t.task_id for t in result]
        self.assertIn("T3", ids)

    def test_plan_case_insensitive(self) -> None:
        self.store.add(_task(task_id="T4", title="PYTHON lambda"))
        result = self.engine.plan("python")
        ids = [t.task_id for t in result]
        self.assertIn("T4", ids)

    def test_plan_topological_order(self) -> None:
        self.store.add(_task(task_id="A", title="foundation work", priority=Priority.P1))
        self.store.add(_task(task_id="B", title="build on foundation", priority=Priority.P1,
                             dependencies=("A",)))
        result = self.engine.plan("foundation")
        ids = [t.task_id for t in result]
        self.assertLess(ids.index("A"), ids.index("B"))

    def test_next_actionable_returns_highest_priority(self) -> None:
        self.store.add(_task(task_id="P2", priority=Priority.P2))
        self.store.add(_task(task_id="P0", priority=Priority.P0))
        self.store.add(_task(task_id="P1", priority=Priority.P1))
        t = self.engine.next_actionable()
        self.assertIsNotNone(t)
        assert t is not None
        self.assertEqual(t.task_id, "P0")

    def test_next_actionable_none_when_all_done(self) -> None:
        tid = self.store.add(_task())
        self.store.complete(tid)
        t = self.engine.next_actionable()
        self.assertIsNone(t)

    def test_next_actionable_none_when_empty(self) -> None:
        t = self.engine.next_actionable()
        self.assertIsNone(t)

    def test_unresolved_dependencies_empty_when_unblocked(self) -> None:
        tid = self.store.add(_task())
        self.assertEqual(self.engine.unresolved_dependencies(tid), [])

    def test_unresolved_dependencies_lists_incomplete_deps(self) -> None:
        self.store.add(_task(task_id="A"))
        self.store.add(_task(task_id="B", dependencies=("A",)))
        unresolved = self.engine.unresolved_dependencies("B")
        self.assertIn("A", unresolved)


# ---------------------------------------------------------------------------
# 32. Cancelled dep → unresolvable
# ---------------------------------------------------------------------------

class CancelledDepTest(unittest.TestCase):
    def test_cancelled_dep_not_actionable(self) -> None:
        store = _temp_store()
        store.add(_task(task_id="A"))
        store.add(_task(task_id="B", dependencies=("A",)))
        store.cancel("A")
        ids = [t.task_id for t in store.actionable_tasks()]
        store.close()
        self.assertNotIn("B", ids)

    def test_cancelled_dep_in_validate(self) -> None:
        store = _temp_store()
        store.add(_task(task_id="A"))
        store.add(_task(task_id="B", dependencies=("A",)))
        store.cancel("A")
        unresolved = store.validate_dependencies("B")
        store.close()
        self.assertIn("A", unresolved)


# ---------------------------------------------------------------------------
# 35. PlanningSettings
# ---------------------------------------------------------------------------

class PlanningSettingsTest(unittest.TestCase):
    def test_default_path_is_planning_db(self) -> None:
        from astra.configuration.settings import PlanningSettings
        s = PlanningSettings()
        self.assertIn("planning", s.database_path.name.lower())

    def test_differs_from_memory_and_knowledge(self) -> None:
        from astra.configuration.settings import (
            KnowledgeSettings, MemorySettings, PlanningSettings
        )
        p = PlanningSettings()
        m = MemorySettings()
        k = KnowledgeSettings()
        self.assertNotEqual(p.database_path, m.database_path)
        self.assertNotEqual(p.database_path, k.database_path)

    def test_astra_settings_includes_planning(self) -> None:
        from astra.configuration.settings import AstraSettings, PlanningSettings
        s = AstraSettings()
        self.assertIsInstance(s.planning, PlanningSettings)

    def test_planning_settings_exported(self) -> None:
        from astra.configuration import settings as m
        self.assertIn("PlanningSettings", m.__all__)


# ---------------------------------------------------------------------------
# 36. Self-dependency
# ---------------------------------------------------------------------------

class SelfDependencyTest(unittest.TestCase):
    def test_self_dep_on_add_rejected(self) -> None:
        store = _temp_store()
        # A depends on A — must fail on add because A doesn't exist
        with self.assertRaises(DependencyError):
            store.add(_task(task_id="SELF", dependencies=("SELF",)))
        store.close()

    def test_self_dep_on_update_rejected(self) -> None:
        store = _temp_store()
        store.add(_task(task_id="SELF2"))
        with self.assertRaises(DependencyError):
            store.update_task(Task(
                task_id="SELF2", title="Self dep", priority=Priority.P2,
                impact=Magnitude.MEDIUM, effort=Magnitude.MEDIUM,
                risk=RiskLevel.LOW, dependencies=("SELF2",),
            ))
        store.close()


# ---------------------------------------------------------------------------
# 37. iter_tasks streaming
# ---------------------------------------------------------------------------

class IterTasksTest(unittest.TestCase):
    def test_iter_tasks_all(self) -> None:
        store = _temp_store()
        for i in range(3):
            store.add(_task(task_id=f"IT{i}"))
        all_tasks = list(store.iter_tasks())
        store.close()
        self.assertEqual(len(all_tasks), 3)

    def test_iter_tasks_is_lazy(self) -> None:
        store = _temp_store()
        store.add(_task(task_id="L1"))
        result = store.iter_tasks()
        self.assertNotIsInstance(result, (list, tuple))
        store.close()

    def test_iter_tasks_filter_by_status(self) -> None:
        store = _temp_store()
        store.add(_task(task_id="A"))
        tid_b = store.add(_task(task_id="B"))
        store.complete(tid_b)
        todo_tasks = list(store.iter_tasks(status=TaskStatus.TODO))
        store.close()
        ids = [t.task_id for t in todo_tasks]
        self.assertIn("A", ids)
        self.assertNotIn("B", ids)


# ---------------------------------------------------------------------------
# Uninitialised store
# ---------------------------------------------------------------------------

class UninitialisedStoreTest(unittest.TestCase):
    def test_add_before_init_raises(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            path = f.name
        store = SQLitePlanningStore(path)
        with self.assertRaises(PlanningStoreError):
            store.add(_task())

    def test_get_before_init_raises(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            path = f.name
        store = SQLitePlanningStore(path)
        with self.assertRaises(PlanningStoreError):
            store.get("any")

    def test_context_manager_initialises(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
            path = f.name
        with SQLitePlanningStore(path) as store:
            tid = store.add(_task())
            self.assertTrue(tid)
        self.assertIsNone(store._connection)


if __name__ == "__main__":
    unittest.main()
