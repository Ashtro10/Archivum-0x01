"""Planning Engine error types."""

from __future__ import annotations


class PlanningStoreError(Exception):
    """Base class for planning storage failures."""


class DuplicateTaskError(PlanningStoreError):
    """Raised when a task with the same ID already exists."""


class DependencyError(PlanningStoreError):
    """Raised for invalid dependency declarations.

    Covers: a dep_id that does not exist in the store (ADR-0009 D5/D6),
    and dependency cycles detected at add/update time (ADR-0009 D5).
    """


class InvalidTransitionError(PlanningStoreError):
    """Raised when a status transition is not allowed.

    DONE and DROPPED are terminal for tasks (ADR-0009 D4). ACHIEVED and
    ABANDONED are terminal for goals (ADR-0013 D4).
    """


class DuplicateGoalError(PlanningStoreError):
    """Raised when a goal with the same ID already exists."""


class GoalNotFoundError(PlanningStoreError):
    """Raised when a goal_id does not exist."""


__all__ = [
    "PlanningStoreError",
    "DuplicateTaskError",
    "DependencyError",
    "InvalidTransitionError",
    "DuplicateGoalError",
    "GoalNotFoundError",
]
