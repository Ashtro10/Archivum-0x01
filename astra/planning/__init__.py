"""Planning Engine, including the Goal Manager.

Maintains the prioritised improvement backlog and resolves task dependencies
before implementation begins. Implemented in B-006 per ADR-0009.

Key components:

* :class:`Priority`, :class:`Magnitude`, :class:`RiskLevel`, :class:`TaskStatus`
  — strongly typed enum fields.
* :class:`Task` — one backlog entry with all constitutional fields.
* :class:`Backlog` — abstract contract; durable storage.
* :class:`PlanningEngine` — abstract contract; planning coordination.
* :class:`SQLitePlanningStore` — concrete :class:`Backlog` implementation.
* :class:`SimplePlanningEngine` — deterministic :class:`PlanningEngine`.
* :class:`Goal`, :class:`GoalStatus`, :class:`GoalManager` — B-014
  ``SQLiteGoalManager`` implements the interface (ADR-0013).
"""

from __future__ import annotations

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
    DuplicateGoalError,
    DuplicateTaskError,
    GoalNotFoundError,
    InvalidTransitionError,
    PlanningStoreError,
)
from astra.planning.goal_manager import SQLiteGoalManager
from astra.planning.goals import (
    TERMINAL_GOAL_STATUSES,
    CriterionEvidence,
    EvidenceHit,
    Goal,
    GoalEvaluation,
    GoalManager,
    GoalStatus,
)
from astra.planning.sqlite_store import SQLitePlanningStore

__all__ = [
    "Priority",
    "Magnitude",
    "RiskLevel",
    "TaskStatus",
    "TERMINAL_STATUSES",
    "Task",
    "Backlog",
    "PlanningEngine",
    "SQLitePlanningStore",
    "SimplePlanningEngine",
    "PlanningStoreError",
    "DuplicateTaskError",
    "DependencyError",
    "InvalidTransitionError",
    "DuplicateGoalError",
    "GoalNotFoundError",
    "Goal",
    "GoalStatus",
    "TERMINAL_GOAL_STATUSES",
    "GoalManager",
    "SQLiteGoalManager",
    "GoalEvaluation",
    "CriterionEvidence",
    "EvidenceHit",
]
