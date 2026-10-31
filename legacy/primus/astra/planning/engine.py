"""Deterministic planning engine over a :class:`~astra.planning.contracts.Backlog`.

``SimplePlanningEngine`` is a thin coordination layer. It holds no state of
its own beyond the injected backlog reference. It does **not** invoke any
language model (ADR-0009 D8).
"""

from __future__ import annotations

from typing import Sequence

from astra.planning.contracts import Backlog, PlanningEngine, Task


class SimplePlanningEngine(PlanningEngine):
    """Deterministic planning engine backed by a :class:`Backlog`.

    ``plan(objective)`` finds existing tasks by keyword and returns them in
    topological order (dependencies before dependents). It does not create
    tasks; task creation is the caller's responsibility.

    No model is loaded or invoked. All methods are pure computation over
    the task graph.
    """

    def __init__(self, backlog: Backlog) -> None:
        self._backlog = backlog

    # ------------------------------------------------------------------
    # PlanningEngine
    # ------------------------------------------------------------------

    def plan(self, objective: str) -> Sequence[Task]:
        """Return existing tasks related to ``objective`` in a safe start order.

        Searches task ``title`` and ``notes`` for the objective string
        (case-insensitive substring). Returns matches in topological order
        (dependencies first) with same-level tasks sorted P0 first.

        Raises:
            ValueError: if ``objective`` is empty or whitespace.
        """
        if not objective or not objective.strip():
            raise ValueError("objective must not be empty")

        needle = objective.strip().lower()
        # Load all non-terminal tasks and filter by keyword.
        all_tasks = list(self._backlog.list_tasks())
        matching: list[Task] = [
            t for t in all_tasks
            if needle in t.title.lower() or needle in t.notes.lower()
        ]
        if not matching:
            return ()

        # Build an ID-indexed map over ALL tasks (for dep resolution).
        task_map: dict[str, Task] = {t.task_id: t for t in all_tasks}
        matching_ids = {t.task_id for t in matching}

        return tuple(_topological_sort(matching, task_map, matching_ids))

    def next_actionable(self) -> Task | None:
        """Highest-priority task with all deps DONE, or ``None``."""
        tasks = self._backlog.actionable_tasks()
        return tasks[0] if tasks else None

    def unresolved_dependencies(self, task_id: str) -> Sequence[str]:
        """Dep IDs that are missing or not DONE for the given task."""
        return self._backlog.validate_dependencies(task_id)


# ---------------------------------------------------------------------------
# Topological sort
# ---------------------------------------------------------------------------

def _topological_sort(
    tasks: list[Task],
    task_map: dict[str, Task],
    included_ids: set[str],
) -> list[Task]:
    """Return tasks in topological order, ties broken by priority (P0 first).

    Uses Kahn's algorithm. If a dep is not in ``included_ids`` it is still
    used for ordering but not included in the output. Cycles are impossible
    at this point (the store's add() prevents them); if somehow present,
    the remaining tasks are returned in priority order.
    """
    # Build in-degree within included set only.
    in_degree: dict[str, int] = {t.task_id: 0 for t in tasks}
    dependents: dict[str, list[str]] = {t.task_id: [] for t in tasks}

    for task in tasks:
        for dep_id in task.dependencies:
            if dep_id in included_ids:
                in_degree[task.task_id] += 1
                dependents[dep_id].append(task.task_id)

    # Priority queue: sort by (priority_value, task_id) for stable ordering.
    ready = sorted(
        [t for t in tasks if in_degree[t.task_id] == 0],
        key=lambda t: (t.priority.value, t.task_id),
    )
    result: list[Task] = []
    task_lookup = {t.task_id: t for t in tasks}

    while ready:
        task = ready.pop(0)
        result.append(task)
        for dep_tid in sorted(dependents.get(task.task_id, [])):
            in_degree[dep_tid] -= 1
            if in_degree[dep_tid] == 0:
                dep_task = task_lookup[dep_tid]
                # Insert in sorted position.
                pos = 0
                while pos < len(ready) and (
                    ready[pos].priority.value,
                    ready[pos].task_id,
                ) <= (dep_task.priority.value, dep_task.task_id):
                    pos += 1
                ready.insert(pos, dep_task)

    # If cycles somehow present, append remaining in priority order.
    remaining_ids = {t.task_id for t in tasks} - {t.task_id for t in result}
    if remaining_ids:
        result.extend(
            sorted(
                [task_lookup[tid] for tid in remaining_ids],
                key=lambda t: (t.priority.value, t.task_id),
            )
        )

    return result


__all__ = ["SimplePlanningEngine"]
