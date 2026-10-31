"""Structured agent decisions. Python validates. LLM does not guess."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from enum import Enum
from types import MappingProxyType
from typing import Any, Mapping, Sequence

from astra.tools.allowlist import AGENT_EXECUTION_ALLOWLIST

ALLOWED_TOOLS = AGENT_EXECUTION_ALLOWLIST
_TOKEN = re.compile(r"[A-Za-z0-9]{4,}")
_CONFIDENCE = frozenset({"HIGH", "MEDIUM", "LOW"})

_EXPECT = {
    "launch_application": (
        "application process is running",
        "named application process is running",
    ),
    "inspect_process": (
        "matching process listed as running",
        "process is running",
    ),
    "inspect_active_window": (
        "active window application and title reported",
        "window information is available",
    ),
    "capture_screen": (
        "screenshot metadata with resolution",
        "screen capture metadata is present",
    ),
    "read_project": (
        "project file contents retrieved",
        "file contents were read",
    ),
    "read_text": (
        "file contents retrieved",
        "file exists and contents were read",
    ),
    "search_files": (
        "search hits or scanned files reported",
        "search completed",
    ),
    "write_text": (
        "file write succeeded",
        "file exists after write",
    ),
    "run_python": (
        "python process exited successfully",
        "test or script command succeeded",
    ),
    "mouse_action": (
        "pointer action succeeded and UI state changed",
        "expected visual or application state occurs",
    ),
    "keyboard_action": (
        "key action succeeded and UI state changed",
        "expected visual or application state occurs",
    ),
}


class Confidence(Enum):
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"


class FailureClass(Enum):
    TOOL_FAILURE = "TOOL_FAILURE"
    INVALID_ARGUMENT = "INVALID_ARGUMENT"
    OBSERVATION_MISMATCH = "OBSERVATION_MISMATCH"
    PLANNING_FAILURE = "PLANNING_FAILURE"
    SECURITY_DENIAL = "SECURITY_DENIAL"
    TIMEOUT = "TIMEOUT"
    ENVIRONMENT_CHANGED = "ENVIRONMENT_CHANGED"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True, slots=True)
class AgentDecision:
    """One validated action. Not free-form tool speech."""

    state: str
    goal: str
    current_task: str
    action: str
    tool: str
    arguments: Mapping[str, str] = field(
        default_factory=lambda: MappingProxyType({})
    )
    expected_observation: str = ""
    success_condition: str = ""
    confidence: str = "HIGH"

    def to_dict(self) -> dict[str, Any]:
        return {
            "state": self.state,
            "goal": self.goal,
            "current_task": self.current_task,
            "action": self.action,
            "tool": self.tool,
            "arguments": dict(self.arguments),
            "expected_observation": self.expected_observation,
            "success_condition": self.success_condition,
            "confidence": self.confidence,
        }


def defaults_for(tool: str) -> tuple[str, str]:
    expected, success = _EXPECT.get(
        (tool or "").strip(),
        ("tool output is present", "tool succeeded"),
    )
    return expected, success


def decision_from_task(
    *,
    state: str,
    goal: str,
    task_title: str,
    tool: str,
    arguments: Mapping[str, str] | None = None,
    expected_observation: str = "",
    success_condition: str = "",
    confidence: str = "",
) -> AgentDecision:
    expect, success = defaults_for(tool)
    args = MappingProxyType(dict(arguments or {}))
    return AgentDecision(
        state=state or "SELECT_TOOL",
        goal=str(goal or "").strip(),
        current_task=str(task_title or "").strip(),
        action=f"use {tool}".strip(),
        tool=str(tool or "").strip(),
        arguments=args,
        expected_observation=(expected_observation or expect).strip(),
        success_condition=(success_condition or success).strip(),
        confidence=_normalize_confidence(confidence),
    )


def validate_decision(decision: AgentDecision | None) -> str | None:
    """Return a reason if the decision must not execute. None if valid."""
    if decision is None:
        return "decision missing"
    if not str(decision.goal or "").strip():
        return "goal missing"
    if not str(decision.current_task or "").strip():
        return "current_task missing"
    if not str(decision.action or "").strip():
        return "action missing"
    tool = str(decision.tool or "").strip()
    if not tool:
        return "tool missing"
    if tool not in ALLOWED_TOOLS:
        return "tool not allowed"
    if not str(decision.expected_observation or "").strip():
        return "expected_observation missing"
    if not str(decision.success_condition or "").strip():
        return "success_condition missing"
    if _normalize_confidence(decision.confidence) not in _CONFIDENCE:
        return "confidence invalid"
    reason = _argument_problem(tool, decision.arguments)
    if reason:
        return reason
    return None


def parse_decision(raw: str) -> AgentDecision | None:
    """Parse a structured decision. Natural-language commands return None."""
    text = str(raw or "").strip()
    if not text or not text.startswith("{") or "tool" not in text:
        return None
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return None
    if not isinstance(data, dict):
        return None
    tool = str(data.get("tool") or "").strip()
    if not tool:
        return None
    raw_args = data.get("arguments")
    arguments: dict[str, str] = {}
    if isinstance(raw_args, dict):
        arguments = {str(k): str(v) for k, v in raw_args.items() if v is not None}
    return AgentDecision(
        state=str(data.get("state") or "SELECT_TOOL"),
        goal=str(data.get("goal") or ""),
        current_task=str(data.get("current_task") or ""),
        action=str(data.get("action") or f"use {tool}"),
        tool=tool,
        arguments=MappingProxyType(arguments),
        expected_observation=str(data.get("expected_observation") or ""),
        success_condition=str(data.get("success_condition") or ""),
        confidence=_normalize_confidence(str(data.get("confidence") or "")),
    )


def classify_failure(
    *,
    status: str = "",
    error: str = "",
    tool_success: bool = False,
    matched: bool | None = None,
    invalid_arguments: bool = False,
) -> FailureClass:
    text = f"{status} {error}".lower()
    if invalid_arguments or "invalid" in text and "argument" in text:
        return FailureClass.INVALID_ARGUMENT
    if status in {"denied", "blocked_execution"} or "denies" in text or "denied" in text:
        return FailureClass.SECURITY_DENIAL
    if "timeout" in text or "timed out" in text:
        return FailureClass.TIMEOUT
    if matched is False and tool_success:
        return FailureClass.OBSERVATION_MISMATCH
    if "environment" in text or "changed" in text:
        return FailureClass.ENVIRONMENT_CHANGED
    if not tool_success:
        return FailureClass.TOOL_FAILURE
    if matched is False:
        return FailureClass.OBSERVATION_MISMATCH
    return FailureClass.UNKNOWN


def observation_matches(expected: str, actual: str, *, tool_success: bool) -> bool:
    """Deterministic compare. No visual hallucination."""
    if not tool_success:
        return False
    expect = str(expected or "").strip().lower()
    text = str(actual or "").strip().lower()
    if not expect:
        return bool(text)
    if "vision" in expect and (
        "vision capability unavailable" in text or "visual reasoning unavailable" in text
    ):
        return False
    tokens = [tok for tok in _TOKEN.findall(expect) if tok not in _STOP]
    if not tokens:
        return bool(text)
    hits = sum(1 for tok in tokens if tok in text)
    return hits >= max(1, len(tokens) // 3)


def goal_success_verified(goal: str, observations: Sequence[str], tools: Sequence[str]) -> bool:
    """Python checks only. Does not invent GUI state."""
    text = " ".join(str(item) for item in observations).lower()
    used = {str(name) for name in tools}
    low = str(goal or "").lower()
    if "create a new project" in low or "new project" in low:
        if "visual reasoning unavailable" in text or "vision capability unavailable" in text:
            return False
        return "success=true" in text and any(
            name in used for name in ("mouse_action", "keyboard_action")
        )
    if any(p in low for p in ("open blender", "launch blender", "start blender")):
        return "running" in text and "inspect_process" in used
    if any(p in low for p in ("create a report", "write a report", "save the report")):
        return "write_text" in used
    if any(p in low for p in ("run the tests", "run tests", "execute tests")):
        return "run_python" in used
    return bool(used) and bool(text)


def compact_context(
    *,
    goal: str,
    task: str = "",
    plan: Sequence[str] = (),
    experiences: Sequence[Any] = (),
    skills: Sequence[Any] = (),
    last_observation: str = "",
    last_result: str = "",
) -> str:
    lines = [
        f"CURRENT GOAL: {_clip(goal, 200)}",
        f"CURRENT TASK: {_clip(task, 160) or '(none)'}",
        "RELEVANT PLAN: " + ("; ".join(_clip(item, 80) for item in list(plan)[:6]) or "(none)"),
        "RELEVANT MEMORY:",
    ]
    mems = list(experiences)[:3]
    if not mems:
        lines.append("- (none)")
    for item in mems:
        lesson = str(getattr(item, "lesson", "") or getattr(item, "strategy", "") or item)
        lines.append(f"- {_clip(lesson, 160)}")
    lines.append("RELEVANT SKILLS:")
    skills = list(skills)[:2]
    if not skills:
        lines.append("- (none)")
    for item in skills:
        name = str(getattr(item, "name", "") or item)
        lines.append(f"- {_clip(name, 80)}")
    lines.append(f"LAST OBSERVATION: {_clip(last_observation, 240) or '(none)'}")
    lines.append(f"LAST ACTION RESULT: {_clip(last_result, 200) or '(none)'}")
    return "\n".join(lines)


def rank_experiences(records: Sequence[Any], goal: str, *, limit: int = 3) -> list[Any]:
    return _rank(records, goal, limit=max(1, int(limit)))


def rank_skills(records: Sequence[Any], goal: str, *, limit: int = 2) -> list[Any]:
    return _rank(records, goal, limit=max(1, int(limit)), extra=_skill_bonus)


def self_check(
    decision: AgentDecision,
    *,
    current_task: str = "",
    within_limits: bool = True,
) -> str | None:
    reason = validate_decision(decision)
    if reason:
        return reason
    if current_task and current_task != decision.current_task:
        return "action does not match current task"
    if not within_limits:
        return "limits exceeded"
    return None


def _rank(
    records: Sequence[Any],
    goal: str,
    *,
    limit: int,
    extra: Any = None,
) -> list[Any]:
    goal_tokens = set(_TOKEN.findall(str(goal or "").lower()))
    scored: list[tuple[int, Any]] = []
    for item in records:
        hay = " ".join(
            str(getattr(item, attr, "") or "")
            for attr in ("goal", "lesson", "strategy", "name", "purpose", "trigger")
        ).lower()
        score = len(goal_tokens & set(_TOKEN.findall(hay)))
        if extra is not None:
            score += int(extra(item))
        scored.append((score, item))
    scored.sort(key=lambda pair: pair[0], reverse=True)
    picked = [item for score, item in scored if score > 0][:limit]
    if picked:
        return picked
    return [item for _score, item in scored[:limit]]


def _skill_bonus(item: Any) -> int:
    status = str(getattr(item, "status", "") or "")
    if status == "verified":
        return 2
    if status == "candidate":
        return 1
    if status == "degraded":
        return -1
    return 0


def _argument_problem(tool: str, arguments: Mapping[str, Any] | None) -> str | None:
    args = arguments or {}
    if tool in {"read_text", "read_project", "write_text", "run_python"}:
        if not str(args.get("path") or "").strip():
            return "path is required"
    if tool == "search_files" and not str(args.get("query") or "").strip():
        return "query is required"
    if tool == "launch_application":
        if not str(args.get("application") or args.get("executable") or "").strip():
            return "application is required"
    if tool == "inspect_process":
        if not str(args.get("query") or args.get("pid") or "").strip():
            return "query or pid is required"
    if tool == "mouse_action":
        action = str(args.get("action") or "").strip()
        if action not in {"move", "click", "double_click", "scroll"}:
            return "mouse action is invalid"
        if str(args.get("x") or "") == "" or str(args.get("y") or "") == "":
            return "coordinates are required"
    if tool == "keyboard_action":
        action = str(args.get("action") or "").strip()
        if action not in {"type_text", "press_key", "hotkey"}:
            return "keyboard action is invalid"
    return None


def _normalize_confidence(value: str) -> str:
    text = str(value or "HIGH").strip().upper()
    if text in _CONFIDENCE:
        return text
    try:
        number = float(text)
    except ValueError:
        return "HIGH"
    if number >= 0.75:
        return "HIGH"
    if number >= 0.4:
        return "MEDIUM"
    return "LOW"


def _clip(text: str, width: int) -> str:
    value = re.sub(r"\s+", " ", str(text or "")).strip()
    if len(value) <= width:
        return value
    return value[: width - 1] + "…"


_STOP = frozenset(
    {
        "that",
        "this",
        "with",
        "from",
        "have",
        "been",
        "were",
        "expected",
        "observation",
        "condition",
        "success",
        "state",
        "application",
        "project",
        "contents",
        "retrieved",
        "listed",
        "present",
        "changed",
        "occurs",
        "after",
        "write",
    }
)


__all__ = [
    "AgentDecision",
    "Confidence",
    "FailureClass",
    "classify_failure",
    "compact_context",
    "decision_from_task",
    "defaults_for",
    "goal_success_verified",
    "observation_matches",
    "parse_decision",
    "rank_experiences",
    "rank_skills",
    "self_check",
    "validate_decision",
]
