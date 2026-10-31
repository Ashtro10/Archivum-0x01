"""Persistent goals via existing PlanningEngine / GoalManager. No new database."""

from __future__ import annotations

import re
from dataclasses import replace
from typing import Any, Sequence

from astra.planning.contracts import Magnitude, Priority, RiskLevel, Task, TaskStatus
from astra.planning.goals import Goal, GoalStatus

_HACKATHON = (
    "Inspect project",
    "Find problems",
    "Fix approved problems",
    "Run tests",
    "Evaluate result",
    "Prepare report",
)
_RELIABILITY = (
    "Observe recent plan outcomes",
    "Identify planner weakness",
    "Propose improvement",
)
_TOKEN = re.compile(r"[a-z0-9]+")


def is_persistent_objective(message: str) -> bool:
    """True for multi-cycle objectives. Chat Q&A stays False."""
    text = str(message or "").strip().lower()
    if not text or text.startswith(("what ", "what's ", "explain ")):
        return False
    if "hackathon" in text and any(token in text for token in ("prepare", "project", "ready")):
        return True
    if "prepare my project" in text or "prepare the project" in text:
        return True
    if "improve primus" in text or "planning reliability" in text:
        return True
    return False


def is_resume_request(message: str) -> bool:
    text = str(message or "").strip().lower().rstrip(".!")
    if text in {"continue", "resume"}:
        return True
    if "continue my project" in text or "continue the project" in text:
        return True
    if "resume my project" in text:
        return True
    return False


def is_pause_request(message: str) -> bool:
    text = str(message or "").strip().lower().rstrip(".!")
    return text in {"pause", "stop", "interrupt", "stop working", "pause work"}


def priority_from_message(message: str) -> Priority:
    text = str(message or "").lower()
    if re.search(r"\bhigh\b", text):
        return Priority.P0
    if re.search(r"\blow\b", text):
        return Priority.P2
    return Priority.P1


def priority_label(priority: Priority) -> str:
    return {Priority.P0: "HIGH", Priority.P1: "MEDIUM", Priority.P2: "LOW"}.get(
        priority, "MEDIUM"
    )


def find_active_goal(goals: Any, description: str = "") -> Any | None:
    if goals is None or not callable(getattr(goals, "list_goals", None)):
        return None
    try:
        active = list(goals.list_goals(status=GoalStatus.ACTIVE) or ())
    except TypeError:
        active = [item for item in (goals.list_goals() or ()) if getattr(item, "status", None) is GoalStatus.ACTIVE]
    except Exception:
        return None
    if not active:
        return None
    needle = _norm(description)
    if needle:
        for item in active:
            if needle in _norm(getattr(item, "description", "")) or _norm(
                getattr(item, "description", "")
            ) in needle:
                return item
    return active[-1]


def ensure_goal(goals: Any, planning: Any, objective: str, *, priority: Priority) -> Any | None:
    """Create or reuse an ACTIVE goal and seed linked tasks once."""
    if goals is None or planning is None:
        return None
    existing = find_active_goal(goals, objective)
    if existing is not None:
        if not getattr(existing, "task_ids", ()):
            _seed_tasks(goals, planning, existing.goal_id, objective, priority)
            return goals.get_goal(existing.goal_id) or existing
        return existing
    titles = _titles_for(objective)
    criterion = (
        f"Backlog tasks for this objective are complete (priority {priority_label(priority)})."
    )
    try:
        ident = goals.add_goal(
            Goal(
                goal_id="",
                description=_clip(objective, 200),
                success_criteria=(criterion, "User-visible project progress is recorded."),
                status=GoalStatus.ACTIVE,
            )
        )
    except Exception:
        return None
    _seed_tasks(goals, planning, ident, objective, priority, titles=titles)
    return goals.get_goal(ident)


def next_backlog_task(planning: Any, goal: Any) -> Any | None:
    if planning is None or goal is None:
        return None
    linked = tuple(getattr(goal, "task_ids", ()) or ())
    if not linked:
        return None
    get = getattr(planning, "get", None)
    if not callable(get):
        return None
    items = [get(tid) for tid in linked]
    items = [item for item in items if item is not None]
    done = {item.task_id for item in items if item.status is TaskStatus.DONE}
    for item in items:
        if item.status is TaskStatus.IN_PROGRESS and _deps_done(item, done):
            return item
    for item in items:
        if item.status is TaskStatus.TODO and _deps_done(item, done):
            return item
    for item in items:
        if item.status is TaskStatus.BLOCKED and _deps_done(item, done):
            return item
    return None


def start_task(planning: Any, task: Any) -> Any | None:
    if planning is None or task is None:
        return None
    current = planning.get(task.task_id) if callable(getattr(planning, "get", None)) else task
    if current is None:
        return None
    if current.status is TaskStatus.BLOCKED:
        planning.update_status(current.task_id, TaskStatus.IN_PROGRESS)
        current = planning.get(current.task_id) or current
    elif current.status is TaskStatus.TODO:
        planning.update_status(current.task_id, TaskStatus.IN_PROGRESS)
        current = planning.get(current.task_id) or current
    attempts = _note_int(current.notes, "attempts") + 1
    notes = _set_note(current.notes, "attempts", str(attempts))
    notes = _set_note(notes, "paused", "0")
    if callable(getattr(planning, "update_task", None)):
        planning.update_task(replace(current, notes=notes, status=current.status))
        return planning.get(current.task_id) or current
    return current


def complete_task(planning: Any, task_id: str, *, result: str = "") -> None:
    if planning is None or not task_id:
        return
    current = planning.get(task_id) if callable(getattr(planning, "get", None)) else None
    if current is None or current.status is TaskStatus.DONE:
        return
    note = _clip(result, 180) or "task completed"
    if callable(getattr(planning, "update_task", None)):
        planning.update_task(
            replace(current, notes=_set_note(current.notes, "result", note))
        )
    planning.complete(task_id, completion_note=note)


def fail_task(planning: Any, task_id: str, *, error: str) -> None:
    if planning is None or not task_id:
        return
    current = planning.get(task_id) if callable(getattr(planning, "get", None)) else None
    if current is None or current.status is TaskStatus.DONE:
        return
    notes = _set_note(current.notes, "error", _clip(error, 180))
    if callable(getattr(planning, "update_task", None)):
        planning.update_task(replace(current, notes=notes))
    if current.status is not TaskStatus.BLOCKED:
        try:
            planning.update_status(task_id, TaskStatus.BLOCKED)
        except Exception:
            return


def pause_task(planning: Any, task_id: str) -> None:
    if planning is None or not task_id:
        return
    current = planning.get(task_id) if callable(getattr(planning, "get", None)) else None
    if current is None or current.status is TaskStatus.DONE:
        return
    notes = _set_note(current.notes, "paused", "1")
    if callable(getattr(planning, "update_task", None)):
        planning.update_task(replace(current, notes=notes))


def progress_lines(planning: Any, goal: Any) -> list[str]:
    if goal is None:
        return []
    items = _goal_tasks(planning, goal)
    done = sum(1 for item in items if item.status is TaskStatus.DONE)
    total = len(items) or 0
    current = next_backlog_task(planning, goal)
    title = getattr(current, "title", "") if current is not None else "none"
    return [
        f"Goal:\n{getattr(goal, 'description', '')}",
        f"Progress:\n{done} / {total} tasks complete",
        f"Current:\n{title}",
    ]


def resume_lines(planning: Any, goal: Any) -> str:
    items = _goal_tasks(planning, goal)
    done = sum(1 for item in items if item.status is TaskStatus.DONE)
    total = len(items)
    nxt = next_backlog_task(planning, goal)
    nxt_title = getattr(nxt, "title", "none") if nxt is not None else "none"
    return (
        f"Resuming:\n{getattr(goal, 'description', '')}.\n"
        f"{done}/{total} tasks complete.\n"
        f"Next: {nxt_title}."
    )


def plan_listing(planning: Any, goal: Any) -> str:
    items = _goal_tasks(planning, goal)
    if not items:
        return ""
    lines = ["Plan:"]
    for index, item in enumerate(items, start=1):
        mark = "x" if item.status is TaskStatus.DONE else " "
        lines.append(f"{index}. [{mark}] {item.title}")
    return "\n".join(lines)


def all_tasks_done(planning: Any, goal: Any) -> bool:
    items = _goal_tasks(planning, goal)
    return bool(items) and all(item.status is TaskStatus.DONE for item in items)


def maybe_achieve(goals: Any, goal: Any) -> None:
    if goals is None or goal is None:
        return
    evaluate = getattr(goals, "evaluate", None)
    mark = getattr(goals, "mark_achieved", None)
    if not callable(evaluate) or not callable(mark):
        return
    try:
        if evaluate(goal.goal_id):
            mark(goal.goal_id)
    except Exception:
        return


def _seed_tasks(
    goals: Any,
    planning: Any,
    goal_id: str,
    objective: str,
    priority: Priority,
    titles: Sequence[str] | None = None,
) -> None:
    names = list(titles or _titles_for(objective))
    previous = ""
    for title in names:
        deps = (previous,) if previous else ()
        ident = planning.add(
            Task(
                task_id="",
                title=title,
                priority=priority,
                impact=Magnitude.MEDIUM,
                effort=Magnitude.SMALL,
                risk=RiskLevel.LOW,
                dependencies=deps,
                status=TaskStatus.TODO,
                notes="attempts=0",
            )
        )
        goals.link_task(goal_id, ident)
        previous = ident


def _titles_for(objective: str) -> tuple[str, ...]:
    text = objective.lower()
    if "planning reliability" in text or "improve primus" in text:
        return _RELIABILITY
    return _HACKATHON


def _goal_tasks(planning: Any, goal: Any) -> list[Any]:
    if planning is None or goal is None:
        return []
    get = getattr(planning, "get", None)
    if not callable(get):
        return []
    items = []
    for tid in getattr(goal, "task_ids", ()) or ():
        item = get(tid)
        if item is not None:
            items.append(item)
    return items


def _deps_done(task: Any, done: set[str]) -> bool:
    deps = tuple(getattr(task, "dependencies", ()) or ())
    return all(dep in done for dep in deps)


def _norm(text: str) -> str:
    return " ".join(_TOKEN.findall(str(text or "").lower()))


def _clip(text: str, width: int) -> str:
    value = re.sub(r"\s+", " ", str(text or "")).strip()
    if len(value) <= width:
        return value
    return value[: width - 1] + "…"


def _note_int(notes: str, key: str) -> int:
    match = re.search(rf"(?:^|\n){re.escape(key)}=(\d+)", str(notes or ""))
    if match is None:
        return 0
    return int(match.group(1))


def _set_note(notes: str, key: str, value: str) -> str:
    rows = [row for row in str(notes or "").splitlines() if row.strip()]
    line = f"{key}={value}"
    prefix = f"{key}="
    replaced = False
    out: list[str] = []
    for row in rows:
        if row.startswith(prefix):
            out.append(line)
            replaced = True
        else:
            out.append(row)
    if not replaced:
        out.append(line)
    return "\n".join(out)


__all__ = [
    "all_tasks_done",
    "complete_task",
    "ensure_goal",
    "fail_task",
    "find_active_goal",
    "is_pause_request",
    "is_persistent_objective",
    "is_resume_request",
    "maybe_achieve",
    "next_backlog_task",
    "pause_task",
    "plan_listing",
    "priority_from_message",
    "priority_label",
    "progress_lines",
    "resume_lines",
    "start_task",
]
