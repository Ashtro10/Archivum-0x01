"""SQLite Goal Manager (B-014, ADR-0013).

Persists goals in ``planning.db`` (schema v2). Evaluates success criteria
against Memory and Knowledge search. Does not invoke a language model, create
tasks, or change task status.
"""

from __future__ import annotations

import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING, Any, Sequence

from astra.logging import get_logger
from astra.planning.contracts import Backlog, TaskStatus
from astra.planning.errors import (
    DuplicateGoalError,
    GoalNotFoundError,
    InvalidTransitionError,
    PlanningStoreError,
)
from astra.planning.goals import (
    TERMINAL_GOAL_STATUSES,
    CriterionEvidence,
    EvidenceHit,
    Goal,
    GoalEvaluation,
    GoalManager,
    GoalStatus,
)
from astra.planning.schema import apply_migrations

if TYPE_CHECKING:
    from astra.knowledge.contracts import KnowledgeStore
    from astra.memory.contracts import MemoryStore

_LOGGER = get_logger(__name__)

TIMESTAMP_FORMAT = "%Y-%m-%dT%H:%M:%S.%fZ"
_EXCERPT_LEN = 240
_SEARCH_LIMIT = 8


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _fmt(value: datetime) -> str:
    return value.astimezone(timezone.utc).strftime(TIMESTAMP_FORMAT)


def _parse(value: str) -> datetime:
    return datetime.strptime(value, TIMESTAMP_FORMAT).replace(tzinfo=timezone.utc)


def _excerpt(text: str) -> str:
    stripped = " ".join(text.split())
    if len(stripped) <= _EXCERPT_LEN:
        return stripped
    return stripped[: _EXCERPT_LEN - 1] + "…"


class SQLiteGoalManager(GoalManager):
    """Concrete :class:`GoalManager` backed by ``planning.db``.

    Args:
        database_path: Same file as :class:`SQLitePlanningStore`.
        backlog: Used to validate ``link_task`` and derive blocked/progress.
        memory: Evidence source. Search is FTS/keyword.
        knowledge: Evidence source. Search is keyword.
        indexer: Optional MemoryIndexer for additive semantic hits.
    """

    def __init__(
        self,
        database_path: Path | str,
        backlog: Backlog,
        memory: "MemoryStore",
        knowledge: "KnowledgeStore",
        indexer: Any | None = None,
    ) -> None:
        self._path = Path(database_path)
        self._backlog = backlog
        self._memory = memory
        self._knowledge = knowledge
        self._indexer = indexer
        self._connection: sqlite3.Connection | None = None

    def initialise(self) -> None:
        if self._connection is None:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            conn = sqlite3.connect(self._path)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA journal_mode = WAL")
            conn.execute("PRAGMA synchronous = FULL")
            conn.execute("PRAGMA foreign_keys = ON")
            self._connection = conn
        apply_migrations(self._connection)

    def close(self) -> None:
        if self._connection is not None:
            self._connection.close()
            self._connection = None

    def _require(self) -> sqlite3.Connection:
        if self._connection is None:
            raise PlanningStoreError(
                "goal manager not initialised; call initialise() first"
            )
        return self._connection

    # ------------------------------------------------------------------
    # GoalManager
    # ------------------------------------------------------------------

    def add_goal(self, goal: Goal) -> str:
        conn = self._require()
        if not goal.description.strip():
            raise PlanningStoreError("goal description must not be empty")
        if goal.status in TERMINAL_GOAL_STATUSES:
            raise InvalidTransitionError(
                "goals cannot be inserted as achieved or abandoned; "
                "use mark_achieved() or abandon()"
            )
        criteria = tuple(c.strip() for c in goal.success_criteria if c.strip())
        if goal.status is GoalStatus.ACTIVE and not criteria:
            raise InvalidTransitionError(
                "a goal without success criteria cannot be activated"
            )
        goal_id = goal.goal_id or str(uuid.uuid4())
        existing = conn.execute(
            "SELECT 1 FROM planning_goals WHERE goal_id = ?", (goal_id,)
        ).fetchone()
        if existing:
            raise DuplicateGoalError(f"goal {goal_id!r} already exists")
        now = _now()
        created = goal.created_at or now
        status = goal.status
        try:
            conn.execute(
                "INSERT INTO planning_goals "
                "(goal_id, description, status, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?)",
                (
                    goal_id,
                    goal.description.strip(),
                    status.value,
                    _fmt(created),
                    _fmt(now),
                ),
            )
            self._replace_criteria(conn, goal_id, criteria)
            for task_id in dict.fromkeys(goal.task_ids):
                self._assert_task_exists(task_id)
                conn.execute(
                    "INSERT INTO planning_goal_tasks (goal_id, task_id) "
                    "VALUES (?, ?)",
                    (goal_id, task_id),
                )
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        return goal_id

    def get_goal(self, goal_id: str) -> Goal | None:
        row = self._require().execute(
            "SELECT * FROM planning_goals WHERE goal_id = ?", (goal_id,)
        ).fetchone()
        if row is None:
            return None
        return self._to_goal(row)

    def list_goals(self, *, status: GoalStatus | None = None) -> Sequence[Goal]:
        conn = self._require()
        if status is None:
            rows = conn.execute(
                "SELECT * FROM planning_goals ORDER BY created_at"
            ).fetchall()
        else:
            rows = conn.execute(
                "SELECT * FROM planning_goals WHERE status = ? ORDER BY created_at",
                (status.value,),
            ).fetchall()
        return tuple(self._to_goal(row) for row in rows)

    def link_task(self, goal_id: str, task_id: str) -> Goal:
        conn = self._require()
        goal = self._require_goal(goal_id)
        self._guard_terminal(goal)
        self._assert_task_exists(task_id)
        conn.execute(
            "INSERT OR IGNORE INTO planning_goal_tasks (goal_id, task_id) "
            "VALUES (?, ?)",
            (goal_id, task_id),
        )
        conn.execute(
            "UPDATE planning_goals SET updated_at = ? WHERE goal_id = ?",
            (_fmt(_now()), goal_id),
        )
        conn.commit()
        loaded = self.get_goal(goal_id)
        assert loaded is not None
        return loaded

    def evaluate(self, goal_id: str) -> bool:
        goal = self._require_goal(goal_id)
        now = _now()
        embedding_used = False
        criterion_rows: list[CriterionEvidence] = []
        indexer = self._indexer
        if indexer is not None:
            available = getattr(indexer, "backend_available", None)
            try:
                embedding_used = bool(available()) if callable(available) else False
            except Exception:
                embedding_used = False

        for criterion in goal.success_criteria:
            hits = self._hits_for_criterion(criterion, embedding_used)
            criterion_rows.append(
                CriterionEvidence(criterion=criterion, met=bool(hits), hits=hits)
            )
        met = bool(goal.success_criteria) and all(c.met for c in criterion_rows)
        eval_id = str(uuid.uuid4())
        conn = self._require()
        conn.execute(
            "INSERT INTO planning_goal_evaluations "
            "(eval_id, goal_id, evaluated_at, met, embedding_used) "
            "VALUES (?, ?, ?, ?, ?)",
            (eval_id, goal_id, _fmt(now), 1 if met else 0, 1 if embedding_used else 0),
        )
        for block in criterion_rows:
            for hit in block.hits:
                retrieved = hit.retrieved_at or now
                conn.execute(
                    "INSERT INTO planning_goal_evidence "
                    "(eval_id, criterion, kind, record_id, excerpt, retrieved_at) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    (
                        eval_id,
                        block.criterion,
                        hit.kind,
                        hit.record_id,
                        hit.excerpt,
                        _fmt(retrieved),
                    ),
                )
        conn.commit()
        return met

    def activate(self, goal_id: str) -> Goal:
        goal = self._require_goal(goal_id)
        self._guard_terminal(goal)
        if goal.status is GoalStatus.ACTIVE:
            return goal
        if goal.status is not GoalStatus.PROPOSED:
            raise InvalidTransitionError(
                f"goal {goal_id!r} cannot be activated from {goal.status.value}"
            )
        if not goal.success_criteria:
            raise InvalidTransitionError(
                "a goal without success criteria cannot be activated"
            )
        return self._set_status(goal_id, GoalStatus.ACTIVE)

    def abandon(self, goal_id: str) -> Goal:
        goal = self._require_goal(goal_id)
        self._guard_terminal(goal)
        return self._set_status(goal_id, GoalStatus.ABANDONED)

    def mark_achieved(self, goal_id: str) -> Goal:
        goal = self._require_goal(goal_id)
        self._guard_terminal(goal)
        if not self.evaluate(goal_id):
            raise InvalidTransitionError(
                f"goal {goal_id!r} cannot be marked achieved: "
                "fresh evaluate() returned False"
            )
        return self._set_status(goal_id, GoalStatus.ACHIEVED)

    def last_evaluation(self, goal_id: str) -> GoalEvaluation | None:
        self._require_goal(goal_id)
        conn = self._require()
        row = conn.execute(
            "SELECT * FROM planning_goal_evaluations WHERE goal_id = ? "
            "ORDER BY evaluated_at DESC LIMIT 1",
            (goal_id,),
        ).fetchone()
        if row is None:
            return None
        evidence_rows = conn.execute(
            "SELECT * FROM planning_goal_evidence WHERE eval_id = ?",
            (row["eval_id"],),
        ).fetchall()
        by_criterion: dict[str, list[EvidenceHit]] = {}
        for ev in evidence_rows:
            by_criterion.setdefault(ev["criterion"], []).append(
                EvidenceHit(
                    kind=ev["kind"],
                    record_id=ev["record_id"],
                    excerpt=ev["excerpt"],
                    retrieved_at=_parse(ev["retrieved_at"]),
                )
            )
        goal = self.get_goal(goal_id)
        criteria_order = goal.success_criteria if goal else tuple(by_criterion)
        blocks = []
        for criterion in criteria_order:
            hits = tuple(by_criterion.get(criterion, ()))
            blocks.append(
                CriterionEvidence(criterion=criterion, met=bool(hits), hits=hits)
            )
        return GoalEvaluation(
            eval_id=row["eval_id"],
            goal_id=goal_id,
            evaluated_at=_parse(row["evaluated_at"]),
            met=bool(row["met"]),
            embedding_used=bool(row["embedding_used"]),
            criteria=tuple(blocks),
        )

    # ------------------------------------------------------------------
    # helpers
    # ------------------------------------------------------------------

    def _require_goal(self, goal_id: str) -> Goal:
        goal = self.get_goal(goal_id)
        if goal is None:
            raise GoalNotFoundError(f"goal {goal_id!r} does not exist")
        return goal

    @staticmethod
    def _guard_terminal(goal: Goal) -> None:
        if goal.status in TERMINAL_GOAL_STATUSES:
            raise InvalidTransitionError(
                f"goal {goal.goal_id!r} is {goal.status.value} and cannot "
                "be transitioned"
            )

    def _set_status(self, goal_id: str, status: GoalStatus) -> Goal:
        conn = self._require()
        conn.execute(
            "UPDATE planning_goals SET status = ?, updated_at = ? "
            "WHERE goal_id = ?",
            (status.value, _fmt(_now()), goal_id),
        )
        conn.commit()
        loaded = self.get_goal(goal_id)
        assert loaded is not None
        return loaded

    def _assert_task_exists(self, task_id: str) -> None:
        task = self._backlog.get(task_id)
        if task is None:
            raise PlanningStoreError(
                f"cannot link unknown task {task_id!r} to a goal"
            )

    def _replace_criteria(
        self, conn: sqlite3.Connection, goal_id: str, criteria: Sequence[str]
    ) -> None:
        conn.execute(
            "DELETE FROM planning_goal_criteria WHERE goal_id = ?", (goal_id,)
        )
        conn.executemany(
            "INSERT INTO planning_goal_criteria (goal_id, ordinal, criterion) "
            "VALUES (?, ?, ?)",
            [(goal_id, i, text) for i, text in enumerate(criteria)],
        )

    def _to_goal(self, row: sqlite3.Row) -> Goal:
        conn = self._require()
        criteria = tuple(
            r["criterion"]
            for r in conn.execute(
                "SELECT criterion FROM planning_goal_criteria "
                "WHERE goal_id = ? ORDER BY ordinal",
                (row["goal_id"],),
            )
        )
        task_ids = tuple(
            r["task_id"]
            for r in conn.execute(
                "SELECT task_id FROM planning_goal_tasks WHERE goal_id = ? "
                "ORDER BY task_id",
                (row["goal_id"],),
            )
        )
        return Goal(
            goal_id=row["goal_id"],
            description=row["description"],
            success_criteria=criteria,
            status=GoalStatus(row["status"]),
            task_ids=task_ids,
            created_at=_parse(row["created_at"]),
        )

    def _hits_for_criterion(
        self, criterion: str, embedding_used: bool
    ) -> tuple[EvidenceHit, ...]:
        now = _now()
        seen: set[tuple[str, str]] = set()
        hits: list[EvidenceHit] = []

        def _add(kind: str, record_id: str, content: str) -> None:
            key = (kind, record_id)
            if key in seen:
                return
            seen.add(key)
            hits.append(
                EvidenceHit(
                    kind=kind,
                    record_id=record_id,
                    excerpt=_excerpt(content),
                    retrieved_at=now,
                )
            )

        query = criterion.strip()
        if not query:
            return ()

        try:
            for rec in self._memory.search(query, limit=_SEARCH_LIMIT):
                _add("memory", rec.record_id, rec.content)
        except Exception as exc:  # noqa: BLE001
            _LOGGER.warning("goal evaluate memory search failed: %s", exc)

        try:
            for rec in self._knowledge.search(query, limit=_SEARCH_LIMIT):
                _add("knowledge", rec.record_id, rec.content)
        except Exception as exc:  # noqa: BLE001
            _LOGGER.warning("goal evaluate knowledge search failed: %s", exc)

        if embedding_used and self._indexer is not None:
            search = getattr(self._indexer, "search_with_scores", None)
            if callable(search):
                try:
                    for rec, _score in search(query, limit=_SEARCH_LIMIT):
                        _add("memory", rec.record_id, rec.content)
                except Exception as exc:  # noqa: BLE001
                    _LOGGER.warning("goal evaluate semantic search failed: %s", exc)

        return tuple(hits)


__all__ = ["SQLiteGoalManager"]
