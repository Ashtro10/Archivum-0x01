"""Planning Engine contracts.

The constitution requires a prioritised improvement backlog whose tasks each
carry priority, estimated impact, estimated effort, risk, dependencies, and
status, and forbids beginning implementation without understanding
dependencies. Both rules are expressed here.

Concrete implementations are in :mod:`astra.planning.sqlite_store` and
:mod:`astra.planning.engine` (B-006, ADR-0009).

The Goal Manager interface (:class:`GoalManager`) is defined in
:mod:`astra.planning.goals` and implemented by :class:`SQLiteGoalManager`
(ADR-0013 / B-014).
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Sequence


class Priority(Enum):
    """Task ordering. ``P0`` is the most urgent and blocks other work.

    Sort order: P0 > P1 > P2 > P3. String values ``'p0' < 'p1' < ...``
    sort correctly lexicographically (ADR-0009 D9).
    """

    P0 = "p0"
    P1 = "p1"
    P2 = "p2"
    P3 = "p3"


class Magnitude(Enum):
    """Coarse scale used for estimated impact and estimated effort."""

    TRIVIAL = "trivial"
    SMALL = "small"
    MEDIUM = "medium"
    LARGE = "large"


class RiskLevel(Enum):
    """How much damage a task can do if it goes wrong."""

    NONE = "none"
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"


class TaskStatus(Enum):
    """Current state of a backlog task.

    ``DONE`` and ``DROPPED`` are terminal: no further transitions are
    allowed once a task reaches either state (ADR-0009 D4).
    """

    TODO = "todo"
    IN_PROGRESS = "in_progress"
    BLOCKED = "blocked"
    DONE = "done"
    DROPPED = "dropped"


#: States from which no transition is permitted.
TERMINAL_STATUSES: frozenset[TaskStatus] = frozenset(
    {TaskStatus.DONE, TaskStatus.DROPPED}
)


@dataclass(frozen=True, slots=True)
class Task:
    """One backlog entry, carrying every field the constitution requires.

    Attributes:
        task_id: Stable identifier. Assigned by the store on first write if
            empty; caller may supply a human-readable ID (e.g. ``"B-007"``).
        title: Short description of the task.
        priority: Urgency/importance ordering.
        impact: Estimated positive impact if completed.
        effort: Estimated work required.
        risk: Estimated damage if the task goes wrong.
        dependencies: IDs of tasks that must be DONE before this one may
            start. All dep IDs must exist in the store at add time
            (ADR-0009 D6).
        status: Current lifecycle state.
        notes: Freeform context.
        created_at: When the task was first recorded.
        updated_at: When the task was last changed.
        completed_at: When the task reached DONE or DROPPED. ``None`` while
            the task is still active.
        completion_note: Why the task was completed or cancelled.
    """

    task_id: str
    title: str
    priority: Priority
    impact: Magnitude
    effort: Magnitude
    risk: RiskLevel
    dependencies: tuple[str, ...] = field(default_factory=tuple)
    status: TaskStatus = TaskStatus.TODO
    notes: str = ""
    created_at: datetime | None = None
    updated_at: datetime | None = None
    completed_at: datetime | None = None
    completion_note: str = ""


class Backlog(ABC):
    """Durable, prioritised list of improvement tasks.

    All implementations must enforce:

    * Dependency existence: every dep_id must refer to a known task
      (ADR-0009 D5/D6).
    * Cycle prevention: a task may not directly or indirectly depend on
      itself (ADR-0009 D5).
    * Terminal state protection: DONE and DROPPED tasks may not be
      transitioned to any other state (ADR-0009 D4).
    """

    @abstractmethod
    def initialise(self) -> None:
        """Create or migrate the underlying schema. Must be idempotent."""
        raise NotImplementedError

    @abstractmethod
    def add(self, task: Task) -> str:
        """Record a task and return its identifier.

        Raises:
            DependencyError: if any dep_id does not exist or if the new
                dependencies would create a cycle.
            DuplicateTaskError: if ``task.task_id`` is non-empty and already
                exists.
            PlanningStoreError: if the title is empty.
        """
        raise NotImplementedError

    @abstractmethod
    def get(self, task_id: str) -> Task | None:
        """Return one task, or ``None`` if it does not exist."""
        raise NotImplementedError

    @abstractmethod
    def update_status(self, task_id: str, status: TaskStatus) -> Task:
        """Change a task's status and return the updated task.

        Raises:
            InvalidTransitionError: if the task is in a terminal state
                (DONE or DROPPED).
            PlanningStoreError: if the task does not exist.
        """
        raise NotImplementedError

    @abstractmethod
    def complete(self, task_id: str, *, completion_note: str = "") -> Task:
        """Mark a task as DONE (terminal transition).

        Raises:
            InvalidTransitionError: if already in a terminal state.
            PlanningStoreError: if the task does not exist.
        """
        raise NotImplementedError

    @abstractmethod
    def cancel(self, task_id: str) -> Task:
        """Mark a task as DROPPED (terminal transition).

        Raises:
            InvalidTransitionError: if already in a terminal state.
            PlanningStoreError: if the task does not exist.
        """
        raise NotImplementedError

    @abstractmethod
    def update_task(self, task: Task) -> Task:
        """Update all mutable fields of an existing task.

        The task's ``task_id`` must already exist. Status transitions must
        go through :meth:`update_status`, :meth:`complete`, or
        :meth:`cancel`; this method does not change status.

        Raises:
            InvalidTransitionError: if the task is in a terminal state.
            DependencyError: if the updated dependencies are invalid.
            PlanningStoreError: if the task does not exist.
        """
        raise NotImplementedError

    @abstractmethod
    def list_tasks(
        self,
        *,
        status: TaskStatus | None = None,
        priority: Priority | None = None,
    ) -> Sequence[Task]:
        """Return tasks, highest priority first.

        Both filters are optional and can be combined.
        """
        raise NotImplementedError

    @abstractmethod
    def actionable_tasks(self) -> Sequence[Task]:
        """Return tasks that can start now, highest priority first.

        A task is actionable when its status is TODO or IN_PROGRESS and
        every declared dependency has status DONE (ADR-0009 D5).
        """
        raise NotImplementedError

    @abstractmethod
    def validate_dependencies(self, task_id: str) -> list[str]:
        """Return dep_ids that are unresolved (missing or not DONE).

        An empty list means the task is unblocked. Callers must check this
        before beginning implementation.
        """
        raise NotImplementedError


class PlanningEngine(ABC):
    """Turns objectives into an ordered, dependency-aware plan.

    The Planning Engine is deterministic infrastructure — it must never
    invoke the Reasoning Engine (ADR-0009 D8). LLM-assisted plan proposals
    belong to agents (B-009 and later).
    """

    @abstractmethod
    def plan(self, objective: str) -> Sequence[Task]:
        """Return existing tasks related to the objective in a safe start order.

        Tasks are ordered so that dependencies appear before the tasks that
        need them (topological order). Tasks at the same level are sorted by
        priority (P0 first).

        ``objective`` is a search string. An empty or whitespace-only
        objective raises :class:`ValueError`.

        Implementations must not invoke any language model or network call.
        """
        raise NotImplementedError

    @abstractmethod
    def next_actionable(self) -> Task | None:
        """Return the highest-priority task whose dependencies are all done.

        Returns ``None`` when nothing is currently unblocked.
        """
        raise NotImplementedError

    @abstractmethod
    def unresolved_dependencies(self, task_id: str) -> Sequence[str]:
        """List dependencies of a task that are not yet complete.

        Callers must check this before implementation begins.
        """
        raise NotImplementedError


__all__ = [
    "Priority",
    "Magnitude",
    "RiskLevel",
    "TaskStatus",
    "TERMINAL_STATUSES",
    "Task",
    "Backlog",
    "PlanningEngine",
]
