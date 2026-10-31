"""SQLite implementation of :class:`astra.planning.contracts.Backlog`.

Implements ADR-0009. This database is the source of truth for tasks; it is
separate from the Memory and Knowledge databases (ADR-0009 D1).

Constitutional invariants enforced here:

* Dependency existence: every dep_id must exist at add time (D5/D6).
* Cycle prevention: DFS check before every insert/update of deps (D5).
* Terminal state protection: DONE/DROPPED tasks cannot transition (D4),
  enforced by a ``BEFORE UPDATE`` trigger and by a Python pre-check.
"""

from __future__ import annotations

import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator, Sequence

from astra.logging import get_logger
from astra.planning.contracts import (
    Backlog,
    Magnitude,
    Priority,
    RiskLevel,
    Task,
    TaskStatus,
    TERMINAL_STATUSES,
)
from astra.planning.errors import (
    DependencyError,
    DuplicateTaskError,
    InvalidTransitionError,
    PlanningStoreError,
)
from astra.planning.schema import apply_migrations

_LOGGER = get_logger(__name__)

TIMESTAMP_FORMAT = "%Y-%m-%dT%H:%M:%S.%fZ"

_TASK_COLUMNS = (
    "task_id, title, notes, priority, impact, effort, risk, "
    "status, created_at, updated_at, completed_at, completion_note"
)


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _fmt(value: datetime) -> str:
    return value.astimezone(timezone.utc).strftime(TIMESTAMP_FORMAT)


def _parse(value: str) -> datetime:
    return datetime.strptime(value, TIMESTAMP_FORMAT).replace(tzinfo=timezone.utc)


def _parse_opt(value: str | None) -> datetime | None:
    return None if value is None else _parse(value)


class SQLitePlanningStore(Backlog):
    """Durable task backlog backed by a single SQLite file.

    Example:
        >>> store = SQLitePlanningStore(path)    # doctest: +SKIP
        >>> store.initialise()                   # doctest: +SKIP
        >>> task_id = store.add(task)            # doctest: +SKIP

    Not thread-safe. Matches the single-user, single-process design (ADR-0001 D3).
    """

    def __init__(self, database_path: Path | str) -> None:
        self._path = Path(database_path)
        self._connection: sqlite3.Connection | None = None

    # ------------------------------------------------------------------
    # lifecycle
    # ------------------------------------------------------------------

    def initialise(self) -> None:
        """Create or migrate the schema. Idempotent."""
        if self._connection is None:
            self._connection = self._connect()
        version = apply_migrations(self._connection)
        _LOGGER.debug("planning store ready at %s (schema v%s)", self._path, version)

    def close(self) -> None:
        """Close the connection. Safe to call more than once."""
        if self._connection is not None:
            self._connection.close()
            self._connection = None

    def __enter__(self) -> "SQLitePlanningStore":
        self.initialise()
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()

    @property
    def database_path(self) -> Path:
        return self._path

    def _connect(self) -> sqlite3.Connection:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(self._path)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode = WAL")
        conn.execute("PRAGMA synchronous = FULL")
        conn.execute("PRAGMA foreign_keys = ON")
        return conn

    def _require(self) -> sqlite3.Connection:
        if self._connection is None:
            raise PlanningStoreError(
                "planning store not initialised; call initialise() first"
            )
        return self._connection

    # ------------------------------------------------------------------
    # writes
    # ------------------------------------------------------------------

    def add(self, task: Task) -> str:
        """Record a task. Validates deps and cycles before writing."""
        conn = self._require()

        if not task.title.strip():
            raise PlanningStoreError("task title must not be empty")

        task_id = task.task_id or str(uuid.uuid4())

        # Unique ID check.
        if conn.execute(
            "SELECT 1 FROM planning_tasks WHERE task_id = ?", (task_id,)
        ).fetchone():
            raise DuplicateTaskError(f"task {task_id!r} already exists")

        # Dep existence + cycle check.
        self._validate_deps_for_add(conn, task_id, task.dependencies)

        now = _now()
        created = task.created_at or now
        try:
            conn.execute(
                f"INSERT INTO planning_tasks ({_TASK_COLUMNS}) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    task_id,
                    task.title,
                    task.notes,
                    task.priority.value,
                    task.impact.value,
                    task.effort.value,
                    task.risk.value,
                    task.status.value,
                    _fmt(created),
                    _fmt(now),
                    _fmt(task.completed_at) if task.completed_at else None,
                    task.completion_note,
                ),
            )
            self._set_deps(conn, task_id, task.dependencies)
            conn.commit()
        except sqlite3.IntegrityError as exc:
            conn.rollback()
            raise self._translate(exc) from exc
        except Exception:
            conn.rollback()
            raise

        return task_id

    def get(self, task_id: str) -> Task | None:
        row = self._row(task_id)
        return None if row is None else self._to_task(row)

    def update_status(self, task_id: str, status: TaskStatus) -> Task:
        """Change status. Raises on terminal state or missing task."""
        conn = self._require()
        row = self._row(task_id)
        if row is None:
            raise PlanningStoreError(f"task {task_id!r} does not exist")

        current = TaskStatus(row["status"])
        if current in TERMINAL_STATUSES:
            raise InvalidTransitionError(
                f"task {task_id!r} is {current.value!r} (terminal); "
                "no further transitions allowed"
            )

        now = _now()
        completed_at = _fmt(now) if status in TERMINAL_STATUSES else None

        try:
            conn.execute(
                "UPDATE planning_tasks SET status = ?, updated_at = ?, "
                "completed_at = COALESCE(completed_at, ?) "
                "WHERE task_id = ?",
                (status.value, _fmt(now), completed_at, task_id),
            )
            conn.commit()
        except sqlite3.IntegrityError as exc:
            conn.rollback()
            raise self._translate(exc) from exc
        except Exception:
            conn.rollback()
            raise

        return self._to_task(self._row(task_id))  # type: ignore[arg-type]

    def complete(self, task_id: str, *, completion_note: str = "") -> Task:
        """Mark task DONE. Terminal."""
        conn = self._require()
        row = self._row(task_id)
        if row is None:
            raise PlanningStoreError(f"task {task_id!r} does not exist")

        current = TaskStatus(row["status"])
        if current in TERMINAL_STATUSES:
            raise InvalidTransitionError(
                f"task {task_id!r} is {current.value!r} (terminal)"
            )

        now = _now()
        try:
            conn.execute(
                "UPDATE planning_tasks SET status = 'done', updated_at = ?, "
                "completed_at = ?, completion_note = ? WHERE task_id = ?",
                (_fmt(now), _fmt(now), completion_note, task_id),
            )
            conn.commit()
        except Exception:
            conn.rollback()
            raise

        return self._to_task(self._row(task_id))  # type: ignore[arg-type]

    def cancel(self, task_id: str) -> Task:
        """Mark task DROPPED. Terminal."""
        conn = self._require()
        row = self._row(task_id)
        if row is None:
            raise PlanningStoreError(f"task {task_id!r} does not exist")

        current = TaskStatus(row["status"])
        if current in TERMINAL_STATUSES:
            raise InvalidTransitionError(
                f"task {task_id!r} is {current.value!r} (terminal)"
            )

        now = _now()
        try:
            conn.execute(
                "UPDATE planning_tasks SET status = 'dropped', updated_at = ?, "
                "completed_at = ? WHERE task_id = ?",
                (_fmt(now), _fmt(now), task_id),
            )
            conn.commit()
        except Exception:
            conn.rollback()
            raise

        return self._to_task(self._row(task_id))  # type: ignore[arg-type]

    def update_task(self, task: Task) -> Task:
        """Update mutable fields. Status must go through update_status/complete/cancel."""
        conn = self._require()

        if not task.title.strip():
            raise PlanningStoreError("task title must not be empty")

        row = self._row(task.task_id)
        if row is None:
            raise PlanningStoreError(f"task {task.task_id!r} does not exist")

        current = TaskStatus(row["status"])
        if current in TERMINAL_STATUSES:
            raise InvalidTransitionError(
                f"task {task.task_id!r} is {current.value!r} (terminal); "
                "cannot update"
            )

        # Validate updated dependencies (existence + no cycles).
        self._validate_deps_for_update(conn, task.task_id, task.dependencies)

        now = _now()
        try:
            conn.execute(
                "UPDATE planning_tasks SET title = ?, notes = ?, "
                "priority = ?, impact = ?, effort = ?, risk = ?, "
                "updated_at = ? WHERE task_id = ?",
                (
                    task.title,
                    task.notes,
                    task.priority.value,
                    task.impact.value,
                    task.effort.value,
                    task.risk.value,
                    _fmt(now),
                    task.task_id,
                ),
            )
            self._set_deps(conn, task.task_id, task.dependencies)
            conn.commit()
        except sqlite3.IntegrityError as exc:
            conn.rollback()
            raise self._translate(exc) from exc
        except Exception:
            conn.rollback()
            raise

        return self._to_task(self._row(task.task_id))  # type: ignore[arg-type]

    # ------------------------------------------------------------------
    # reads
    # ------------------------------------------------------------------

    def list_tasks(
        self,
        *,
        status: TaskStatus | None = None,
        priority: Priority | None = None,
    ) -> Sequence[Task]:
        """Return tasks, P0 first, then by creation time."""
        conn = self._require()
        sql = f"SELECT {_TASK_COLUMNS} FROM planning_tasks WHERE 1=1"
        params: list[object] = []
        if status is not None:
            sql += " AND status = ?"
            params.append(status.value)
        if priority is not None:
            sql += " AND priority = ?"
            params.append(priority.value)
        sql += " ORDER BY priority, created_at"
        rows = conn.execute(sql, params).fetchall()
        return tuple(self._to_task(row) for row in rows)

    def actionable_tasks(self) -> Sequence[Task]:
        """Return tasks that can start now (deps all DONE), P0 first.

        A task is actionable when:
        * status is TODO or IN_PROGRESS, and
        * every dep_id in planning_deps either has no corresponding task
          (impossible if add() enforced existence) or has status = 'done'.

        The SQL uses NOT EXISTS to avoid loading all tasks into Python.
        """
        conn = self._require()
        sql = f"""
            SELECT {_TASK_COLUMNS} FROM planning_tasks t
            WHERE t.status IN ('todo', 'in_progress')
            AND NOT EXISTS (
                SELECT 1 FROM planning_deps d
                LEFT JOIN planning_tasks dt ON dt.task_id = d.dep_id
                WHERE d.task_id = t.task_id
                AND (dt.task_id IS NULL OR dt.status != 'done')
            )
            ORDER BY t.priority, t.created_at
        """
        rows = conn.execute(sql).fetchall()
        return tuple(self._to_task(row) for row in rows)

    def validate_dependencies(self, task_id: str) -> list[str]:
        """Return dep_ids that are unresolved (missing or not DONE)."""
        conn = self._require()
        # Missing deps.
        missing = [
            row[0]
            for row in conn.execute(
                "SELECT dep_id FROM planning_deps "
                "WHERE task_id = ? "
                "AND dep_id NOT IN (SELECT task_id FROM planning_tasks)",
                (task_id,),
            ).fetchall()
        ]
        # Existing but not DONE.
        not_done = [
            row[0]
            for row in conn.execute(
                "SELECT d.dep_id FROM planning_deps d "
                "JOIN planning_tasks dt ON dt.task_id = d.dep_id "
                "WHERE d.task_id = ? AND dt.status != 'done'",
                (task_id,),
            ).fetchall()
        ]
        seen: dict[str, None] = {}
        for dep_id in missing + not_done:
            seen[dep_id] = None
        return list(seen)

    def iter_tasks(
        self, *, status: TaskStatus | None = None
    ) -> Iterator[Task]:
        """Stream tasks for bulk operations. Oldest first."""
        conn = self._require()
        sql = f"SELECT {_TASK_COLUMNS} FROM planning_tasks"
        params: tuple[object, ...] = ()
        if status is not None:
            sql += " WHERE status = ?"
            params = (status.value,)
        sql += " ORDER BY created_at"
        cursor = conn.execute(sql, params)
        try:
            for row in cursor:
                yield self._to_task(row)
        finally:
            cursor.close()

    # ------------------------------------------------------------------
    # dependency internals
    # ------------------------------------------------------------------

    def _set_deps(
        self, conn: sqlite3.Connection, task_id: str, deps: Sequence[str]
    ) -> None:
        conn.execute("DELETE FROM planning_deps WHERE task_id = ?", (task_id,))
        conn.executemany(
            "INSERT INTO planning_deps (task_id, dep_id) VALUES (?, ?)",
            [(task_id, dep_id) for dep_id in dict.fromkeys(deps)],
        )

    def _validate_deps_for_add(
        self,
        conn: sqlite3.Connection,
        new_task_id: str,
        dep_ids: Sequence[str],
    ) -> None:
        for dep_id in dep_ids:
            if not conn.execute(
                "SELECT 1 FROM planning_tasks WHERE task_id = ?", (dep_id,)
            ).fetchone():
                raise DependencyError(
                    f"dependency {dep_id!r} does not exist in the store"
                )
        if dep_ids and _has_cycle(conn, new_task_id, dep_ids):
            raise DependencyError(
                f"adding {dep_ids} as dependencies of {new_task_id!r} "
                "would create a cycle"
            )

    def _validate_deps_for_update(
        self,
        conn: sqlite3.Connection,
        task_id: str,
        dep_ids: Sequence[str],
    ) -> None:
        for dep_id in dep_ids:
            if dep_id == task_id:
                raise DependencyError(
                    f"task {task_id!r} cannot depend on itself"
                )
            if not conn.execute(
                "SELECT 1 FROM planning_tasks WHERE task_id = ?", (dep_id,)
            ).fetchone():
                raise DependencyError(
                    f"dependency {dep_id!r} does not exist in the store"
                )
        if dep_ids and _has_cycle_for_update(conn, task_id, dep_ids):
            raise DependencyError(
                f"updating dependencies of {task_id!r} to {list(dep_ids)} "
                "would create a cycle"
            )

    # ------------------------------------------------------------------
    # conversion helpers
    # ------------------------------------------------------------------

    def _row(self, task_id: str) -> sqlite3.Row | None:
        return (
            self._require()
            .execute(
                f"SELECT {_TASK_COLUMNS} FROM planning_tasks WHERE task_id = ?",
                (task_id,),
            )
            .fetchone()
        )

    def _deps(self, task_id: str) -> tuple[str, ...]:
        rows = self._require().execute(
            "SELECT dep_id FROM planning_deps WHERE task_id = ? ORDER BY dep_id",
            (task_id,),
        )
        return tuple(row[0] for row in rows)

    def _to_task(self, row: sqlite3.Row) -> Task:
        return Task(
            task_id=row["task_id"],
            title=row["title"],
            notes=row["notes"],
            priority=Priority(row["priority"]),
            impact=Magnitude(row["impact"]),
            effort=Magnitude(row["effort"]),
            risk=RiskLevel(row["risk"]),
            status=TaskStatus(row["status"]),
            created_at=_parse(row["created_at"]),
            updated_at=_parse(row["updated_at"]),
            completed_at=_parse_opt(row["completed_at"]),
            completion_note=row["completion_note"],
            dependencies=self._deps(row["task_id"]),
        )

    @staticmethod
    def _translate(error: sqlite3.IntegrityError) -> Exception:
        msg = str(error)
        if "terminal state" in msg:
            return InvalidTransitionError(msg)
        return PlanningStoreError(msg)


# ---------------------------------------------------------------------------
# Cycle detection (pure function; no DB writes)
# ---------------------------------------------------------------------------

def _has_cycle(
    conn: sqlite3.Connection,
    new_task_id: str,
    dep_ids: Sequence[str],
) -> bool:
    """Return True if making ``new_task_id`` depend on ``dep_ids`` creates a cycle.

    Performs a DFS from each dep_id through the existing dependency graph.
    If ``new_task_id`` is reachable from any dep, a cycle would result.
    """
    visited: set[str] = set()
    stack = list(dep_ids)
    while stack:
        current = stack.pop()
        if current == new_task_id:
            return True
        if current in visited:
            continue
        visited.add(current)
        rows = conn.execute(
            "SELECT dep_id FROM planning_deps WHERE task_id = ?", (current,)
        ).fetchall()
        stack.extend(row[0] for row in rows)
    return False


def _has_cycle_for_update(
    conn: sqlite3.Connection,
    task_id: str,
    new_dep_ids: Sequence[str],
) -> bool:
    """Cycle check for update_task: treat the task's NEW deps as its deps."""
    return _has_cycle(conn, task_id, new_dep_ids)


__all__ = ["SQLitePlanningStore"]
