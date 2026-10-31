"""Goal Manager.

Nested inside the Planning Engine because goals and tasks form a cycle: goals
produce tasks, and task outcomes update goals. Keeping both inside one package
boundary avoids a bidirectional dependency between packages. The rationale is
recorded in ``docs/architecture.md``.

``Goal``, ``GoalStatus``, and the original five ``GoalManager`` methods are
unchanged (ADR-0009 D12, ADR-0013 D2). B-014 adds lifecycle methods and
evaluation snapshot types.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Sequence


class GoalStatus(Enum):
    """Lifecycle of a goal."""

    PROPOSED = "proposed"
    ACTIVE = "active"
    ACHIEVED = "achieved"
    ABANDONED = "abandoned"


#: Statuses from which no transition is permitted (ADR-0013 D4).
TERMINAL_GOAL_STATUSES: frozenset[GoalStatus] = frozenset(
    {GoalStatus.ACHIEVED, GoalStatus.ABANDONED}
)


@dataclass(frozen=True, slots=True)
class Goal:
    """A durable objective the user wants Astra to pursue.

    Attributes:
        goal_id: Stable identifier.
        description: What success means, in plain language.
        success_criteria: Observable conditions that decide achievement. A
            goal without criteria cannot be evaluated and must not be
            activated.
        status: Current state.
        task_ids: Backlog tasks serving this goal.
        created_at: When the goal was recorded.
    """

    goal_id: str
    description: str
    success_criteria: tuple[str, ...] = field(default=())
    status: GoalStatus = GoalStatus.PROPOSED
    task_ids: tuple[str, ...] = field(default=())
    created_at: datetime | None = None


@dataclass(frozen=True, slots=True)
class EvidenceHit:
    """One Memory or Knowledge record that matched a success criterion."""

    kind: str
    record_id: str
    excerpt: str = ""
    retrieved_at: datetime | None = None


@dataclass(frozen=True, slots=True)
class CriterionEvidence:
    """Whether one success criterion was met, and by which records."""

    criterion: str
    met: bool
    hits: tuple[EvidenceHit, ...] = ()


@dataclass(frozen=True, slots=True)
class GoalEvaluation:
    """Observational snapshot produced by :meth:`GoalManager.evaluate`.

    Does not change :class:`Goal` status (ADR-0013 D4).
    """

    eval_id: str
    goal_id: str
    evaluated_at: datetime | None
    met: bool
    embedding_used: bool = False
    criteria: tuple[CriterionEvidence, ...] = ()


class GoalManager(ABC):
    """Tracks goals and their relationship to backlog tasks."""

    @abstractmethod
    def add_goal(self, goal: Goal) -> str:
        """Record a goal and return its identifier."""
        raise NotImplementedError

    @abstractmethod
    def get_goal(self, goal_id: str) -> Goal | None:
        """Return one goal, or ``None`` if it does not exist."""
        raise NotImplementedError

    @abstractmethod
    def list_goals(self, *, status: GoalStatus | None = None) -> Sequence[Goal]:
        """Return goals, optionally filtered by status."""
        raise NotImplementedError

    @abstractmethod
    def link_task(self, goal_id: str, task_id: str) -> Goal:
        """Attach a backlog task to a goal and return the updated goal."""
        raise NotImplementedError

    @abstractmethod
    def evaluate(self, goal_id: str) -> bool:
        """Report whether a goal's success criteria are currently met.

        Judgement must rest on recorded evidence, not on an assumption that
        the work was done.
        """
        raise NotImplementedError

    @abstractmethod
    def activate(self, goal_id: str) -> Goal:
        """Move a proposed goal to active. Requires non-empty criteria."""
        raise NotImplementedError

    @abstractmethod
    def abandon(self, goal_id: str) -> Goal:
        """Abandon a proposed or active goal. Terminal."""
        raise NotImplementedError

    @abstractmethod
    def mark_achieved(self, goal_id: str) -> Goal:
        """Mark achieved only if a fresh :meth:`evaluate` returns True."""
        raise NotImplementedError

    @abstractmethod
    def last_evaluation(self, goal_id: str) -> GoalEvaluation | None:
        """Return the latest evaluation snapshot, or ``None``."""
        raise NotImplementedError


__all__ = [
    "GoalStatus",
    "TERMINAL_GOAL_STATUSES",
    "Goal",
    "EvidenceHit",
    "CriterionEvidence",
    "GoalEvaluation",
    "GoalManager",
]
