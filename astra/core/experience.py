"""External AgentRun summaries. Not chain-of-thought. Not a second database."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Sequence

from astra.memory.contracts import MemoryCategory

EXPERIENCE_SOURCE = "agent_loop"
EXPERIENCE_MARK = "EXPERIENCE v1"
_TOKEN = re.compile(r"[A-Za-z0-9]{4,}")


@dataclass(frozen=True, slots=True)
class ExperienceRecord:
    """UI-safe, storeable summary of one run or one failed action."""

    goal: str
    outcome: str
    tasks: tuple[str, ...] = ()
    tools: tuple[str, ...] = ()
    successful: tuple[str, ...] = ()
    failed: tuple[str, ...] = ()
    failure_reason: str = ""
    strategy: str = ""
    lesson: str = ""
    timestamp: str = ""
    steps: int = 0
    tool_calls: int = 0
    replans: int = 0
    failures: int = 0
    duration_s: float = 0.0
    completed: int = 0
    approval_waits: int = 0
    tags: tuple[str, ...] = ()
    category: str = "experiments"


def format_experience(record: ExperienceRecord) -> str:
    """Render a durable memory body. External facts only."""
    return "\n".join(
        (
            EXPERIENCE_MARK,
            f"goal: {record.goal}",
            f"outcome: {record.outcome}",
            f"tasks: {_join(record.tasks)}",
            f"tools: {_join(record.tools)}",
            f"successful: {_join(record.successful)}",
            f"failed: {_join(record.failed)}",
            f"failure_reason: {record.failure_reason or 'none'}",
            f"strategy: {record.strategy}",
            f"lesson: {record.lesson}",
            (
                "metrics: "
                f"steps={record.steps} tool_calls={record.tool_calls} "
                f"replans={record.replans} failures={record.failures} "
                f"duration_s={record.duration_s:.3f} completed={record.completed} "
                f"approval_waits={record.approval_waits}"
            ),
            f"timestamp: {record.timestamp}",
        )
    )


def parse_experience(content: str) -> ExperienceRecord | None:
    """Parse a stored summary. Unknown bodies return None."""
    text = str(content or "").strip()
    if not text.startswith(EXPERIENCE_MARK):
        return None
    fields: dict[str, str] = {}
    for line in text.splitlines()[1:]:
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        fields[key.strip()] = value.strip()
    metrics = _parse_metrics(fields.get("metrics", ""))
    return ExperienceRecord(
        goal=fields.get("goal", ""),
        outcome=fields.get("outcome", ""),
        tasks=_split(fields.get("tasks", "")),
        tools=_split(fields.get("tools", "")),
        successful=_split(fields.get("successful", "")),
        failed=_split(fields.get("failed", "")),
        failure_reason=_none_as_empty(fields.get("failure_reason", "")),
        strategy=fields.get("strategy", ""),
        lesson=fields.get("lesson", ""),
        timestamp=fields.get("timestamp", ""),
        steps=metrics.get("steps", 0),
        tool_calls=metrics.get("tool_calls", 0),
        replans=metrics.get("replans", 0),
        failures=metrics.get("failures", 0),
        duration_s=metrics.get("duration_s", 0.0),
        completed=metrics.get("completed", 0),
        approval_waits=metrics.get("approval_waits", 0),
    )


def retrieve_experiences(memory: Any, goal: str, *, limit: int = 4) -> list[ExperienceRecord]:
    """Fetch related summaries from MemoryStore. Suggestions only."""
    search = getattr(memory, "search", None)
    if not callable(search) or not str(goal or "").strip():
        return []
    found: list[ExperienceRecord] = []
    seen: set[str] = set()
    queries = _search_queries(goal)
    categories = (
        MemoryCategory.EXPERIMENTS,
        MemoryCategory.FAILURES,
        MemoryCategory.PROJECT_KNOWLEDGE,
        MemoryCategory.ARCHITECTURE,
    )
    for query in queries:
        for category in categories:
            try:
                hits = search(query, category=category, limit=limit)
            except TypeError:
                try:
                    hits = search(query, limit=limit)
                except Exception:
                    continue
            except Exception:
                continue
            for item in hits or ():
                parsed = _from_memory_item(item)
                if parsed is None:
                    continue
                key = f"{parsed.goal}|{parsed.timestamp}|{parsed.outcome}"
                if key in seen:
                    continue
                seen.add(key)
                found.append(parsed)
                if len(found) >= limit:
                    return found
    return found


def detect_software_weakness(run: Any) -> str | None:
    """Heuristic: problem in Primus software, not the user's project."""
    if bool(getattr(run, "skill_collapse", False)):
        return (
            "recurring skill failure; planner or tool-selection weakness "
            "may be producing bad tool choices"
        )
    invalid = int(getattr(run, "invalid_tool_attempts", 0) or 0)
    if invalid >= 2:
        return "planner repeatedly produced invalid tool names"
    planning = int(getattr(run, "planning_failures", 0) or 0)
    if planning >= 2:
        return "planner repeatedly produced invalid structured decisions"
    error = str(getattr(run, "error", "") or "")
    state_obj = getattr(run, "state", None)
    value = str(getattr(state_obj, "value", state_obj) or "")
    if value == "FAILED":
        if error in {"max_replans reached", "max_steps reached"}:
            return f"agent loop hit {error}; planner or tool selection may be wrong"
    replans = int(getattr(run, "replan_count", 0) or getattr(run, "replans", 0) or 0)
    steps = int(getattr(run, "step_count", 0) or getattr(run, "total_steps", 0) or 0)
    if replans >= 2 and steps >= 6:
        return "agent wasted steps with repeated replans"
    return None


def compare_metrics(previous: ExperienceRecord, current: Any) -> str:
    """Describe step/replan/failure counts. Does not claim intelligence."""
    return (
        f"Previous similar run: {previous.steps} steps, {previous.replans} replans, "
        f"{previous.failures} failures.\n"
        f"Current: {int(getattr(current, 'total_steps', 0) or 0)} steps, "
        f"{int(getattr(current, 'replans', 0) or 0)} replans, "
        f"{int(getattr(current, 'failures', 0) or 0)} failures."
    )


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _from_memory_item(item: Any) -> ExperienceRecord | None:
    content = str(getattr(item, "content", item) or "")
    if content.lstrip().startswith("SKILL"):
        return None
    parsed = parse_experience(content)
    if parsed is not None:
        return parsed
    tags = tuple(str(tag) for tag in (getattr(item, "tags", ()) or ()))
    if not any(tag in {"lesson", "strategy_ok", "strategy_fail", "skill"} for tag in tags):
        return None
    return ExperienceRecord(
        goal=_clip(content, 120),
        outcome="UNKNOWN",
        lesson=_clip(content, 240),
        strategy=_clip(content, 240),
        tags=tags,
    )


def _search_queries(goal: str) -> tuple[str, ...]:
    text = str(goal or "").strip()
    tokens = [tok.lower() for tok in _TOKEN.findall(text)]
    ordered = [text]
    for token in tokens:
        if token not in {item.lower() for item in ordered}:
            ordered.append(token)
    low = text.lower()
    if "fail" in low:
        for extra in ("failing", "failure", "failed"):
            if extra not in ordered:
                ordered.append(extra)
        if "test" in low:
            for extra in ("inspect", "project"):
                if extra not in ordered:
                    ordered.append(extra)
    return tuple(ordered[:10])


def _parse_metrics(raw: str) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for part in str(raw or "").split():
        if "=" not in part:
            continue
        key, value = part.split("=", 1)
        if key == "duration_s":
            try:
                out[key] = float(value)
            except ValueError:
                out[key] = 0.0
        else:
            try:
                out[key] = int(float(value))
            except ValueError:
                out[key] = 0
    return out


def _join(parts: Sequence[str]) -> str:
    items = [str(part).strip() for part in parts if str(part).strip()]
    return "; ".join(items) if items else "none"


def _split(raw: str) -> tuple[str, ...]:
    text = str(raw or "").strip()
    if not text or text == "none":
        return ()
    return tuple(part.strip() for part in text.split(";") if part.strip())


def _none_as_empty(value: str) -> str:
    text = str(value or "").strip()
    if text.lower() == "none":
        return ""
    return text


def _clip(text: str, width: int) -> str:
    value = re.sub(r"\s+", " ", str(text or "")).strip()
    if len(value) <= width:
        return value
    return value[: width - 1] + "…"


__all__ = [
    "EXPERIENCE_MARK",
    "EXPERIENCE_SOURCE",
    "ExperienceRecord",
    "compare_metrics",
    "detect_software_weakness",
    "format_experience",
    "parse_experience",
    "retrieve_experiences",
    "utc_now",
]
