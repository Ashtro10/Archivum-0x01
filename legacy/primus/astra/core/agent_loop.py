"""Bounded autonomous execution loop.

Controller-owned. LLMBackend reasons; tools execute. Never applies production
edits. Never invents ApprovalRecord. Caps prevent infinite loops.
"""

from __future__ import annotations

import json
import re
import time
import uuid
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Iterator, Mapping, Sequence

from astra.core.agent_decision import (
    AgentDecision,
    FailureClass,
    classify_failure,
    compact_context,
    decision_from_task,
    goal_success_verified,
    observation_matches,
    parse_decision,
    rank_experiences,
    rank_skills,
    self_check,
    validate_decision,
)
from astra.core.experience import (
    EXPERIENCE_SOURCE,
    ExperienceRecord,
    compare_metrics,
    detect_software_weakness,
    format_experience,
    retrieve_experiences,
    utc_now,
)
from astra.core.persistent_work import (
    all_tasks_done,
    complete_task,
    ensure_goal,
    fail_task,
    find_active_goal,
    is_persistent_objective,
    is_resume_request,
    maybe_achieve,
    next_backlog_task,
    pause_task,
    plan_listing,
    priority_from_message,
    progress_lines,
    resume_lines,
    start_task,
)
from astra.core.skills import (
    SkillRecord,
    apply_skill_outcome,
    maybe_create_skill,
    persist_skill,
    retrieve_skills,
    skill_plan_hint,
    skill_status_messages,
)
from astra.tools.allowlist import AGENT_EXECUTION_ALLOWLIST

ALLOWED_TOOLS = AGENT_EXECUTION_ALLOWLIST
DEFAULT_MAX_STEPS = 10
DEFAULT_MAX_REPLANS = 2
DEFAULT_MAX_ACTIONS = 10
DEFAULT_MAX_VISUAL_OBSERVATIONS = 10
DEFAULT_MAX_INTERACTION_TIME = 60.0
VISUAL_REASONING_UNAVAILABLE = "Visual reasoning unavailable."
_VISION_MARKERS = (
    "Vision capability unavailable.",
    VISUAL_REASONING_UNAVAILABLE,
)
_OBS_CAP = 1200
_TASK_LINE = re.compile(
    r"TASK\s+(?P<body>.+?)\s*\|\s*(?P<title>.+)",
    re.IGNORECASE,
)
_JSON_TOOL = re.compile(
    r"\{[^{}]*\"tool\"\s*:\s*\"(?P<tool>[A-Za-z0-9_]+)\"[^{}]*\}",
    re.IGNORECASE,
)
_APPROVAL_STATUSES = frozenset({"pending_approval", "requires_approval"})
_UNAVAILABLE_STATUSES = frozenset({"unavailable", "blocked_execution", "denied"})
_IMPROVE_MARKERS = (
    "propose an improvement",
    "propose a change",
    "propose improvement",
    "propose a fix",
)
_REPORT_MARKERS = (
    "save the report",
    "save a report",
    "save the report as a file",
    "create a small report",
    "create a report",
    "write a report",
    "save a file",
)
_EXEC_PHRASES = (
    "inspect my project",
    "inspect the project",
    "inspect this project",
    "read my project",
    "read the project",
    "check the project",
    "check my project",
    "analyze these files",
    "analyse these files",
    "analyze the project",
    "analyse the project",
    "inspect the codebase",
    "find the relevant files",
)
_EXEC_BROKEN = (
    "what is broken",
    "what's broken",
    "tests are failing",
    "why the tests",
    "find why the tests",
    "what needs improvement",
    "what should be improved",
    "determine what should be improved",
    "determine what needs improvement",
    "find a problem",
    "propose an improvement",
    "describing one problem",
)
_LAUNCH_PHRASES = (
    "open blender",
    "launch blender",
    "start blender",
    "open the blender",
)
_WINDOW_PHRASES = (
    "what application is currently open",
    "which application is currently open",
    "what application is open",
    "which application is open",
    "what app is currently open",
    "which app is open",
    "what is the active window",
    "inspect the active window",
    "currently open application",
)
_SCREEN_PHRASES = (
    "take a look at my desktop",
    "look at my desktop",
    "look at the desktop",
    "look at my screen",
    "look at the screen",
    "capture my screen",
    "take a screenshot",
    "what's on my screen",
    "what is on my screen",
    "what is on the screen",
)
_PERCEPTION_TOOLS = frozenset({"capture_screen", "inspect_active_window"})
_INTERACTION_TOOLS = frozenset({"mouse_action", "keyboard_action"})
_GUI_PHRASES = (
    "create a new project",
    "new project",
    "new file",
    "click",
    "type ",
    "press ",
    "hotkey",
)


class AgentState(Enum):
    """Explicit execution states. Not chain-of-thought."""

    IDLE = "IDLE"
    RETRIEVE = "RETRIEVE"
    UNDERSTAND = "UNDERSTAND"
    PLAN = "PLAN"
    SELECT_TASK = "SELECT_TASK"
    SELECT_TOOL = "SELECT_TOOL"
    EXECUTE = "EXECUTE"
    OBSERVE = "OBSERVE"
    EVALUATE = "EVALUATE"
    ANALYZE = "ANALYZE"
    DIAGNOSE = "DIAGNOSE"
    REFLECT = "REFLECT"
    REPLAN = "REPLAN"
    WAIT_FOR_USER = "WAIT_FOR_USER"
    PAUSED = "PAUSED"
    COMPLETE = "COMPLETE"
    FAILED = "FAILED"


@dataclass(frozen=True, slots=True)
class AgentTask:
    """One planned action. Tool must be on the agent execution allowlist."""

    title: str
    tool_name: str
    arguments: Mapping[str, str] = field(default_factory=dict)
    expected_observation: str = ""
    success_condition: str = ""
    confidence: str = "HIGH"


@dataclass(frozen=True, slots=True)
class ToolCallResult:
    """Normalized tool outcome. Independent of GatedToolInvoker types."""

    success: bool
    status: str = "executed"
    output: str = ""
    error: str = ""
    invocation_id: str = ""


@dataclass(frozen=True, slots=True)
class StepEvent:
    """UI-safe execution summary. Not hidden reasoning."""

    type: str
    state: str = ""
    message: str = ""
    name: str = ""
    success: bool | None = None
    goal: str = ""
    plan: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {"type": self.type}
        if self.state:
            payload["state"] = self.state
        if self.message:
            payload["message"] = self.message
        if self.name:
            payload["name"] = self.name
        if self.success is not None:
            payload["success"] = self.success
        if self.goal:
            payload["goal"] = self.goal
        if self.plan:
            payload["plan"] = list(self.plan)
        return payload


@dataclass
class AgentRun:
    """Mutable working memory for one goal."""

    run_id: str
    goal: str
    state: AgentState = AgentState.IDLE
    plan: list[AgentTask] = field(default_factory=list)
    current_task: AgentTask | None = None
    completed_tasks: list[AgentTask] = field(default_factory=list)
    failed_tasks: list[AgentTask] = field(default_factory=list)
    step_count: int = 0
    replan_count: int = 0
    max_steps: int = DEFAULT_MAX_STEPS
    max_replans: int = DEFAULT_MAX_REPLANS
    result: str = ""
    error: str = ""
    observations: list[str] = field(default_factory=list)
    proposal: Any = None
    tests_passed: int = 0
    tests_run: int = 0
    test_report: Any = None
    evaluation: Any = None
    memory_written: bool = False
    pending_kind: str = ""
    pending_invocation_id: str = ""
    pending_remaining: list[AgentTask] = field(default_factory=list)
    tool_calls: int = 0
    approval_waits: int = 0
    total_steps: int = 0
    replans: int = 0
    failures: int = 0
    completed: int = 0
    duration: float = 0.0
    started_mono: float = 0.0
    experiences: list[ExperienceRecord] = field(default_factory=list)
    experience_note: str = ""
    skills: list[SkillRecord] = field(default_factory=list)
    used_skill: SkillRecord | None = None
    skill_memory: Any = None
    skill_notes: list[str] = field(default_factory=list)
    pending_skill_events: list[str] = field(default_factory=list)
    skill_collapse: bool = False
    skill_synced: bool = False
    persistent_goal_id: str = ""
    backlog_task_id: str = ""
    progress_note: str = ""
    invalid_tool_attempts: int = 0
    memory_ids: list[str] = field(default_factory=list)
    interaction_actions: int = 0
    visual_observations: int = 0
    failed_interaction_sigs: list[str] = field(default_factory=list)
    max_actions: int = DEFAULT_MAX_ACTIONS
    max_visual_observations: int = DEFAULT_MAX_VISUAL_OBSERVATIONS
    max_interaction_time: float = DEFAULT_MAX_INTERACTION_TIME
    decisions: list[AgentDecision] = field(default_factory=list)
    failure_class: str = ""
    llm_calls: int = 0
    planning_failures: int = 0
    timeout_retried: bool = False
    last_decision: AgentDecision | None = None


InvokeTool = Callable[[str, Mapping[str, str]], ToolCallResult]
ExecuteApproved = Callable[[str], ToolCallResult]


def is_execution_goal(message: str) -> bool:
    """True only for inspect/fix-project goals. Chat Q&A stays False."""
    text = str(message or "").strip().lower()
    if not text or len(text) > 4000:
        return False
    if text in {"hello", "hi", "hey", "how are you?", "how are you"}:
        return False
    if is_persistent_objective(text):
        return True
    if any(phrase in text for phrase in _EXEC_PHRASES):
        return True
    if any(phrase in text for phrase in _LAUNCH_PHRASES):
        return True
    if wants_window_inspect(text) or wants_screen_capture(text):
        return True
    if wants_gui_interaction(text):
        return True
    if any(phrase in text for phrase in _IMPROVE_MARKERS):
        return True
    if any(phrase in text for phrase in _REPORT_MARKERS) and any(
        token in text for token in ("project", "codebase", "files", "repo")
    ):
        return True
    if (
        "investigate" in text
        and "test" in text
        and "fail" in text
        and not text.startswith(("what ", "what's ", "explain "))
    ):
        return True
    projectish = any(
        token in text for token in ("project", "codebase", "files", "repo")
    )
    broken = any(phrase in text for phrase in _EXEC_BROKEN)
    return projectish and broken


def wants_improvement(message: str) -> bool:
    """True when the user asked for a proposed change, not only an inspect."""
    text = str(message or "").lower()
    return any(marker in text for marker in _IMPROVE_MARKERS)


def wants_app_launch(message: str) -> bool:
    """True when the user asked to open/launch a known local application."""
    text = str(message or "").lower()
    return any(phrase in text for phrase in _LAUNCH_PHRASES)


def wants_window_inspect(message: str) -> bool:
    """True when the user asked which application/window is active."""
    text = str(message or "").strip().lower()
    return any(phrase in text for phrase in _WINDOW_PHRASES)


def wants_screen_capture(message: str) -> bool:
    """True when the user asked to look at the desktop or screen."""
    text = str(message or "").strip().lower()
    return any(phrase in text for phrase in _SCREEN_PHRASES)


def wants_perception(message: str) -> bool:
    """True when the task needs optional environmental observation."""
    return wants_window_inspect(message) or wants_screen_capture(message)


def wants_gui_interaction(message: str) -> bool:
    """True when launch is paired with a GUI follow-up, not inspect/report."""
    text = str(message or "").strip().lower()
    if not wants_app_launch(text):
        return False
    return any(phrase in text for phrase in _GUI_PHRASES)


def application_from_goal(message: str) -> str:
    text = str(message or "").lower()
    if "blender" in text:
        return "blender"
    return ""


def wants_report_write(message: str) -> bool:
    """True when the user asked to save an inspection report."""
    text = str(message or "").lower()
    return any(marker in text for marker in _REPORT_MARKERS)


class AgentLoop:
    """UNDERSTAND → PLAN → SELECT TOOL → EXECUTE → OBSERVE → EVALUATE."""

    def __init__(
        self,
        *,
        llm: Any | None = None,
        invoke_tool: InvokeTool | None = None,
        execute_approved: ExecuteApproved | None = None,
        memory: Any | None = None,
        coding: Any | None = None,
        testing: Any | None = None,
        evaluation: Any | None = None,
        project_root: Path | str | None = None,
        write_root: Path | str | None = None,
        scratch_root: Path | str | None = None,
        max_steps: int = DEFAULT_MAX_STEPS,
        max_replans: int = DEFAULT_MAX_REPLANS,
        planning: Any | None = None,
        goals: Any | None = None,
        max_actions: int = DEFAULT_MAX_ACTIONS,
        max_visual_observations: int = DEFAULT_MAX_VISUAL_OBSERVATIONS,
        max_interaction_time: float = DEFAULT_MAX_INTERACTION_TIME,
    ) -> None:
        self._llm = llm
        self._invoke = invoke_tool
        self._execute_approved = execute_approved
        self._memory = memory
        self._coding = coding
        self._testing = testing
        self._evaluation = evaluation
        self._planning = planning
        self._goals = goals
        root = Path(project_root) if project_root else Path.cwd()
        self._root = root
        self._write_root = Path(write_root) if write_root else (root / "data")
        self._scratch_root = Path(scratch_root) if scratch_root else None
        self._max_steps = max(1, int(max_steps))
        self._max_replans = max(0, int(max_replans))
        self._max_actions = max(1, int(max_actions))
        self._max_visual_observations = max(1, int(max_visual_observations))
        self._max_interaction_time = max(1.0, float(max_interaction_time))
        self.last_run: AgentRun | None = None
        self._interrupt = False
        self.active_goal_id = ""

    def bind_llm(self, llm: Any | None) -> None:
        """Keep loop on the live backend after a settings refresh."""
        self._llm = llm

    def pause(self) -> AgentRun | None:
        """User interrupt. Goal stays ACTIVE. Nothing destructive continues."""
        self._interrupt = True
        run = self.last_run
        if run is None:
            return None
        if run.state in {AgentState.COMPLETE, AgentState.FAILED}:
            return run
        run.state = AgentState.PAUSED
        run.error = "Paused by user."
        if run.backlog_task_id:
            pause_task(self._planning, run.backlog_task_id)
        bound = self._bound_goal(run)
        run.result = (
            "\n".join(progress_lines(self._planning, bound))
            if bound is not None
            else "Paused."
        )
        return run

    def run(self, goal: str) -> AgentRun:
        """Execute one goal to a terminal state. Collects events."""
        for _ in self.iter_run(goal):
            pass
        assert self.last_run is not None
        return self.last_run

    def resume(self, approved: bool) -> AgentRun:
        """Continue the same AgentRun after a WAIT_FOR_USER decision."""
        for _ in self.iter_resume(approved):
            pass
        assert self.last_run is not None
        return self.last_run

    def iter_run(self, goal: str) -> Iterator[StepEvent]:
        """Yield UI events while advancing the state machine."""
        text = str(goal or "").strip()
        run = AgentRun(
            run_id=uuid.uuid4().hex[:12],
            goal=text,
            max_steps=self._max_steps,
            max_replans=self._max_replans,
            started_mono=time.monotonic(),
            max_actions=self._max_actions,
            max_visual_observations=self._max_visual_observations,
            max_interaction_time=self._max_interaction_time,
        )
        if wants_gui_interaction(text):
            run.max_steps = max(
                run.max_steps,
                run.max_actions + run.max_visual_observations + 8,
            )
        self.last_run = run
        self._interrupt = False
        if not text:
            run.state = AgentState.FAILED
            run.error = "goal is empty"
            yield self._state_event(run)
            yield StepEvent(type="error", state=run.state.value, message=run.error)
            return

        run.state = AgentState.RETRIEVE
        yield self._state_event(run)
        run.experiences = rank_experiences(
            retrieve_experiences(self._memory, run.goal, limit=6),
            run.goal,
            limit=3,
        )
        if run.experiences:
            run.experience_note = (
                "I found a relevant previous experience and will use it as a possible strategy."
            )
            yield StepEvent(
                type="experience",
                state=run.state.value,
                message=run.experience_note,
            )
        hits = retrieve_skills(self._memory, run.goal, limit=6)
        ranked = rank_skills([parsed for _item, parsed in hits], run.goal, limit=2)
        names = {item.name for item in ranked}
        hits = [(item, parsed) for item, parsed in hits if parsed.name in names][:2]
        run.skills = [parsed for _item, parsed in hits]
        if hits:
            memory_item, parsed = hits[0]
            run.used_skill = parsed
            run.skill_memory = memory_item
            for message in skill_status_messages(parsed):
                if parsed.status != "verified" and message.endswith("verified."):
                    continue
                run.skill_notes.append(message)
                yield StepEvent(
                    type="skill",
                    state=run.state.value,
                    message=message,
                )

        run.state = AgentState.UNDERSTAND
        yield self._state_event(run)
        yield StepEvent(type="goal", state=run.state.value, goal=run.goal, message=run.goal)

        stored = self._bind_persistent(run)
        if stored is not None:
            for line in progress_lines(self._planning, stored):
                yield StepEvent(type="progress", state=run.state.value, message=line)
            if run.progress_note:
                yield StepEvent(
                    type="progress",
                    state=run.state.value,
                    message=run.progress_note.split("\n", 1)[0],
                )

        run.state = AgentState.PLAN
        yield self._state_event(run)
        if stored is not None:
            nxt = next_backlog_task(self._planning, stored)
            if nxt is None:
                run.state = AgentState.COMPLETE
                run.result = "\n".join(progress_lines(self._planning, stored))
                yield self._state_event(run)
                yield StepEvent(type="completed", state=run.state.value, message="Goal completed")
                return
            started = start_task(self._planning, nxt)
            run.backlog_task_id = getattr(started or nxt, "task_id", "")
            run.plan = self._plan_for_backlog_title(getattr(started or nxt, "title", ""))
        else:
            run.plan = self._make_plan(run)
        yield StepEvent(
            type="plan",
            state=run.state.value,
            plan=tuple(task.title for task in run.plan),
            message="; ".join(task.title for task in run.plan),
        )
        yield from self._drive(run, list(run.plan))

    def iter_resume(self, approved: bool) -> Iterator[StepEvent]:
        """Resume the paused AgentRun. Does not start a new goal."""
        run = self.last_run
        if run is None or run.state is not AgentState.WAIT_FOR_USER:
            return
        if run.pending_kind == "proposal":
            if not approved:
                run.state = AgentState.FAILED
                run.error = "User rejected the improvement."
                run.result = run.error
                self._store_experience(run)
                self._finish_persistent(run, success=False)
                yield from self._skill_events(run)
                yield self._state_event(run)
                yield StepEvent(type="error", state=run.state.value, message=run.error)
            return
        if not approved:
            run.state = AgentState.FAILED
            run.error = "User rejected the action."
            run.result = run.error
            self._store_experience(run)
            self._finish_persistent(run, success=False)
            yield from self._skill_events(run)
            yield self._state_event(run)
            yield StepEvent(type="error", state=run.state.value, message=run.error)
            return
        task = run.current_task
        if task is None:
            run.state = AgentState.FAILED
            run.error = "no pending task to resume"
            yield self._state_event(run)
            yield StepEvent(type="error", state=run.state.value, message=run.error)
            return
        run.state = AgentState.EXECUTE
        yield self._state_event(run)
        yield StepEvent(type="task", state=run.state.value, message=task.title)
        yield StepEvent(
            type="tool",
            state=run.state.value,
            name=task.tool_name,
            message=task.title,
        )
        outcome = self._run_approved(run, task)
        paused = yield from self._observe(run, task, outcome)
        if paused:
            return
        yield from self._drive(run, list(run.pending_remaining))

    def _drive(self, run: AgentRun, pending: list[AgentTask]) -> Iterator[StepEvent]:
        while pending:
            if self._interrupt:
                self.pause()
                yield self._state_event(run)
                yield StepEvent(type="progress", state=run.state.value, message=run.result)
                return
            if run.step_count >= run.max_steps:
                run.state = AgentState.FAILED
                run.error = "max_steps reached"
                yield self._state_event(run)
                yield StepEvent(type="error", state=run.state.value, message=run.error)
                yield from self._after_failure(run)
                return

            task = pending.pop(0)
            run.current_task = task
            stopped = yield from self._guard_interaction(run, task)
            if stopped:
                return
            run.state = AgentState.SELECT_TASK
            yield self._state_event(run)
            run.state = AgentState.SELECT_TOOL
            yield self._state_event(run)
            task = self._prepare_task(run, task)
            decision, reject = self._decide(run, task)
            run.last_decision = decision
            if decision is not None:
                run.decisions.append(decision)
                yield StepEvent(
                    type="decision",
                    state=run.state.value,
                    name=decision.tool,
                    message=_clip(
                        f"{decision.action}; expect {decision.expected_observation}",
                        240,
                    ),
                )
            if reject:
                paused = yield from self._reject_decision(
                    run, task, reject, remaining=pending
                )
                if paused:
                    if run.state is AgentState.FAILED:
                        yield from self._after_failure(run)
                    return
                continue
            task = _task_from_decision(task, decision)
            run.current_task = task
            run.state = AgentState.EXECUTE
            yield self._state_event(run)
            yield StepEvent(type="task", state=run.state.value, message=task.title)
            yield StepEvent(
                type="tool",
                state=run.state.value,
                name=task.tool_name,
                message=task.title,
            )
            outcome = self._execute_task(task)
            paused = yield from self._observe(run, task, outcome, remaining=pending)
            if paused:
                if run.state is AgentState.FAILED:
                    yield from self._after_failure(run)
                return

        run.state = AgentState.EVALUATE
        yield self._state_event(run)

        if wants_improvement(run.goal):
            self._store_experience(run)
            yield from self._skill_events(run)
            yield from self._self_improve_hook(run)
            return

        if any(
            item.tool_name in {"search_files", "read_project", "read_text"}
            for item in run.completed_tasks
        ):
            run.state = AgentState.ANALYZE
            yield self._state_event(run)

        run.state = AgentState.REFLECT
        yield self._state_event(run)
        reflection = self._reflect(run)
        yield StepEvent(type="reflection", state=run.state.value, message=reflection)
        self._store_experience(run)
        yield from self._skill_events(run)
        self._finish_persistent(run, success=True)
        weakness = detect_software_weakness(run)
        if weakness:
            yield from self._self_improve_hook(run, objective=weakness)
            return
        used = [item.tool_name for item in run.completed_tasks]
        if not goal_success_verified(run.goal, run.observations, used):
            run.state = AgentState.WAIT_FOR_USER
            run.error = "success cannot be verified"
            run.result = run.error
            yield self._state_event(run)
            yield StepEvent(type="error", state=run.state.value, message=run.error)
            return
        run.state = AgentState.COMPLETE
        run.result = self._inspect_result(run, reflection)
        yield self._state_event(run)
        yield StepEvent(
            type="completed",
            state=run.state.value,
            message="Goal completed",
        )

    def _observe(
        self,
        run: AgentRun,
        task: AgentTask,
        outcome: ToolCallResult,
        remaining: list[AgentTask] | None = None,
    ) -> Iterator[StepEvent]:
        run.step_count += 1
        run.tool_calls += 1
        run.state = AgentState.OBSERVE
        yield self._state_event(run)
        yield StepEvent(
            type="tool_result",
            state=run.state.value,
            name=task.tool_name,
            success=outcome.success,
            message=_clip(outcome.output or outcome.error, 240),
        )
        if task.tool_name in _PERCEPTION_TOOLS or task.tool_name in _INTERACTION_TOOLS:
            yield StepEvent(
                type="observe",
                state=run.state.value,
                name=task.tool_name,
                success=outcome.success,
                message=_clip(outcome.output or outcome.error, 240),
            )

        status = (outcome.status or "").lower()
        if status in _APPROVAL_STATUSES:
            leftover = (
                list(remaining)
                if remaining is not None
                else list(run.pending_remaining)
            )
            run.pending_kind = "tool"
            run.pending_invocation_id = outcome.invocation_id
            run.pending_remaining = leftover
            run.current_task = task
            run.approval_waits += 1
            run.state = AgentState.WAIT_FOR_USER
            prompt = _approval_prompt(task, outcome)
            run.error = "Approval required."
            run.result = prompt
            yield self._state_event(run)
            yield StepEvent(
                type="approval",
                state=run.state.value,
                name=task.tool_name,
                message=prompt,
            )
            yield StepEvent(
                type="error",
                state=run.state.value,
                message="Approval required.",
            )
            return True
        if (not outcome.success) and (
            status in _UNAVAILABLE_STATUSES or task.tool_name not in ALLOWED_TOOLS
        ):
            kind = classify_failure(
                status=status,
                error=outcome.error,
                tool_success=False,
            )
            run.failure_class = kind.value
            run.state = AgentState.WAIT_FOR_USER
            run.error = outcome.error or f"tool {task.tool_name} is unavailable"
            if kind is FailureClass.SECURITY_DENIAL:
                run.error = outcome.error or "security denial"
            if task.tool_name not in ALLOWED_TOOLS and "not registered" not in run.error:
                run.error = outcome.error or f"tool {task.tool_name} is not registered"
            run.result = run.error
            yield self._state_event(run)
            yield StepEvent(type="error", state=run.state.value, message=run.error)
            return True

        expected = task.expected_observation or (
            run.last_decision.expected_observation if run.last_decision is not None else ""
        )
        matched = observation_matches(
            expected,
            outcome.output or outcome.error,
            tool_success=outcome.success,
        )
        if outcome.success and expected and not matched:
            outcome = ToolCallResult(
                success=False,
                status="executed",
                output=outcome.output,
                error="observation mismatch",
                invocation_id=outcome.invocation_id,
            )
            run.failure_class = FailureClass.OBSERVATION_MISMATCH.value

        if not outcome.success and "timeout" in (outcome.error or "").lower():
            if not run.timeout_retried:
                run.timeout_retried = True
                run.failure_class = FailureClass.TIMEOUT.value
                leftover = list(remaining) if remaining is not None else []
                leftover.insert(0, task)
                if remaining is not None:
                    remaining[:] = leftover
                else:
                    run.pending_remaining = leftover
                run.state = AgentState.EVALUATE
                yield self._state_event(run)
                return False

        if not outcome.success:
            if not run.failure_class:
                run.failure_class = classify_failure(
                    status=outcome.status,
                    error=outcome.error,
                    tool_success=False,
                    invalid_arguments="invalid" in (outcome.error or "").lower(),
                ).value
            if task.tool_name in _INTERACTION_TOOLS:
                run.interaction_actions += 1
                sig = _interaction_sig(task)
                if sig in run.failed_interaction_sigs:
                    run.state = AgentState.WAIT_FOR_USER
                    run.error = "same action failed repeatedly"
                    run.result = run.error
                    yield self._state_event(run)
                    yield StepEvent(type="error", state=run.state.value, message=run.error)
                    return True
                run.failed_interaction_sigs.append(sig)
            run.failed_tasks.append(task)
            run.state = AgentState.EVALUATE
            yield self._state_event(run)
            if run.replan_count >= run.max_replans:
                run.state = AgentState.DIAGNOSE
                yield self._state_event(run)
                run.state = AgentState.FAILED
                run.error = "max_replans reached"
                yield self._state_event(run)
                yield StepEvent(
                    type="error",
                    state=run.state.value,
                    message=run.error,
                )
                return True
            run.state = AgentState.REPLAN
            run.replan_count += 1
            yield self._state_event(run)
            leftover = list(remaining) if remaining is not None else []
            pending = self._replan(run.goal, task, leftover)
            run.plan = list(run.completed_tasks) + pending
            if remaining is not None:
                remaining[:] = pending
            else:
                run.pending_remaining = pending
            yield StepEvent(
                type="plan",
                state=run.state.value,
                plan=tuple(item.title for item in pending),
                message="Replanned remaining tasks",
            )
            return False

        note = _clip(outcome.output, _OBS_CAP)
        if note:
            run.observations.append(f"{task.title}: {note}")
        run.completed_tasks.append(task)
        if task.tool_name in _INTERACTION_TOOLS:
            run.interaction_actions += 1
        if task.tool_name == "capture_screen":
            run.visual_observations += 1
        leftover = list(remaining) if remaining is not None else list(run.pending_remaining)
        stopped = self._after_successful_action(run, task, outcome, leftover)
        if remaining is not None:
            remaining[:] = leftover
        else:
            run.pending_remaining = leftover
        run.state = AgentState.EVALUATE
        yield self._state_event(run)
        if stopped:
            run.state = AgentState.WAIT_FOR_USER
            yield self._state_event(run)
            yield StepEvent(type="error", state=run.state.value, message=run.error)
            return True
        return False

    def _run_approved(self, run: AgentRun, task: AgentTask) -> ToolCallResult:
        ident = (run.pending_invocation_id or "").strip()
        if ident and self._execute_approved is not None:
            try:
                result = self._execute_approved(ident)
            except Exception as exc:  # noqa: BLE001
                return ToolCallResult(
                    success=False,
                    status="executed",
                    error=f"{type(exc).__name__}: {exc}",
                )
            if isinstance(result, ToolCallResult):
                return result
            return record_from_invoker(result)
        return self._execute_task(task)

    def _execute_task(self, task: AgentTask) -> ToolCallResult:
        name = (task.tool_name or "").strip()
        if name not in ALLOWED_TOOLS:
            run = self.last_run
            if run is not None:
                run.invalid_tool_attempts += 1
            return ToolCallResult(
                success=False,
                status="unavailable",
                error=f"tool {name or '(none)'} is not registered",
            )
        if self._invoke is None:
            return ToolCallResult(
                success=False,
                status="unavailable",
                error="no tool invoker",
            )
        try:
            result = self._invoke(name, dict(task.arguments))
        except Exception as exc:  # noqa: BLE001
            return ToolCallResult(
                success=False,
                status="executed",
                error=f"{type(exc).__name__}: {exc}",
            )
        if isinstance(result, ToolCallResult):
            return result
        return record_from_invoker(result)

    def _prepare_task(self, run: AgentRun, task: AgentTask) -> AgentTask:
        args = dict(task.arguments)
        if task.tool_name == "write_text" and not str(args.get("content") or "").strip():
            args["content"] = _report_from_observations(run)
        if task.tool_name == "run_python" and not str(args.get("path") or "").strip():
            root = self._scratch_root
            if root is not None:
                args["path"] = str(root / "agent-python" / f"{run.run_id}.py")
        if task.tool_name == "search_files" and not str(args.get("query") or "").strip():
            args["query"] = _query_from_goal(run.goal)
        if task.tool_name == "launch_application" and not str(
            args.get("application") or args.get("executable") or ""
        ).strip():
            args["application"] = application_from_goal(run.goal) or "blender"
        if task.tool_name == "inspect_process" and not str(
            args.get("query") or args.get("pid") or ""
        ).strip():
            args["query"] = application_from_goal(run.goal) or "blender"
        if task.tool_name == "capture_screen":
            if not str(args.get("max_width") or "").strip():
                args["max_width"] = "1280"
            if not str(args.get("max_height") or "").strip():
                args["max_height"] = "720"
        if task.tool_name == "mouse_action" and not str(args.get("action") or "").strip():
            args["action"] = "click"
        if task.tool_name == "keyboard_action" and not str(args.get("action") or "").strip():
            args["action"] = "type_text"
        return AgentTask(
            title=task.title,
            tool_name=task.tool_name,
            arguments=args,
            expected_observation=task.expected_observation,
            success_condition=task.success_condition,
            confidence=task.confidence,
        )

    def _decide(self, run: AgentRun, task: AgentTask) -> tuple[AgentDecision | None, str]:
        """Build a structured decision. LLM only if the planned task is invalid."""
        decision = decision_from_task(
            state=run.state.value,
            goal=run.goal,
            task_title=task.title,
            tool=task.tool_name,
            arguments=task.arguments,
            expected_observation=task.expected_observation,
            success_condition=task.success_condition,
            confidence=task.confidence,
        )
        reason = self_check(
            decision,
            current_task=task.title,
            within_limits=not bool(self._interaction_limit_reason(run, task)),
        )
        if reason is None:
            if decision.confidence == "LOW":
                return decision, "low confidence"
            return decision, ""
        repaired = self._decide_from_llm(run, task)
        if repaired is not None and validate_decision(repaired) is None:
            if repaired.confidence == "LOW":
                return repaired, "low confidence"
            return repaired, ""
        run.planning_failures += 1
        if task.tool_name not in ALLOWED_TOOLS:
            run.invalid_tool_attempts += 1
        invalid = bool(reason) and (
            "required" in reason or "invalid" in reason
        )
        run.failure_class = (
            FailureClass.INVALID_ARGUMENT.value
            if invalid
            else FailureClass.PLANNING_FAILURE.value
        )
        return decision, reason or "invalid decision"

    def _decide_from_llm(self, run: AgentRun, task: AgentTask) -> AgentDecision | None:
        raw = self._generate(
            "Return one JSON decision only. No prose.\n"
            '{"state":"SELECT_TOOL","goal":"...","current_task":"...","action":"...",'
            '"tool":"NAME","arguments":{},"expected_observation":"...",'
            '"success_condition":"...","confidence":"HIGH"}\n'
            f"{self._compact_prompt(run, task)}\n"
            f"Allowed tools: {', '.join(sorted(ALLOWED_TOOLS))}\n"
        )
        parsed = parse_decision(raw)
        if parsed is None:
            return None
        return decision_from_task(
            state=parsed.state or run.state.value,
            goal=parsed.goal or run.goal,
            task_title=parsed.current_task or task.title,
            tool=parsed.tool,
            arguments=parsed.arguments or task.arguments,
            expected_observation=parsed.expected_observation,
            success_condition=parsed.success_condition,
            confidence=parsed.confidence,
        )

    def _reject_decision(
        self,
        run: AgentRun,
        task: AgentTask,
        reason: str,
        remaining: list[AgentTask] | None = None,
    ) -> Iterator[bool]:
        if reason == "low confidence":
            run.state = AgentState.WAIT_FOR_USER
            run.error = "low confidence; ask user"
            run.result = run.error
            run.failure_class = FailureClass.UNKNOWN.value
            yield self._state_event(run)
            yield StepEvent(type="error", state=run.state.value, message=run.error)
            return True
        if task.tool_name not in ALLOWED_TOOLS or "not allowed" in reason:
            run.state = AgentState.WAIT_FOR_USER
            run.error = f"tool {task.tool_name or '(none)'} is not registered"
            run.result = run.error
            run.failure_class = FailureClass.PLANNING_FAILURE.value
            yield self._state_event(run)
            yield StepEvent(type="error", state=run.state.value, message=run.error)
            return True
        fake = ToolCallResult(success=False, status="executed", error=reason)
        paused = yield from self._observe(run, task, fake, remaining=remaining)
        return paused

    def _compact_prompt(self, run: AgentRun, task: AgentTask | None = None) -> str:
        last_obs = run.observations[-1] if run.observations else ""
        last_result = ""
        if run.last_decision is not None:
            last_result = run.last_decision.action
        return compact_context(
            goal=run.goal,
            task=(task.title if task is not None else ""),
            plan=[item.title for item in run.plan],
            experiences=run.experiences,
            skills=run.skills,
            last_observation=last_obs,
            last_result=last_result or run.error,
        )

    def _select_tool(self, run: AgentRun, task: AgentTask) -> AgentTask:
        """Keep planned tool. Do not regenerate the plan."""
        del run
        return task

    def _make_plan(self, run: AgentRun) -> list[AgentTask]:
        planned = self._plan_from_llm(run)
        if planned:
            if not wants_perception(run.goal) and not wants_gui_interaction(run.goal):
                planned = [
                    item
                    for item in planned
                    if item.tool_name not in _PERCEPTION_TOOLS
                ]
            if not wants_gui_interaction(run.goal):
                planned = [
                    item
                    for item in planned
                    if item.tool_name not in _INTERACTION_TOOLS
                ]
            perception = self._perception_tasks(run.goal)
            have = {item.tool_name for item in planned}
            missing = [item for item in perception if item.tool_name not in have]
            if missing:
                planned = missing + list(planned)
            if wants_app_launch(run.goal) and not any(
                item.tool_name == "launch_application" for item in planned
            ):
                planned = self._app_control_tasks(run.goal) + list(planned)
            if wants_gui_interaction(run.goal):
                planned = self._ensure_gui_observe(planned)
            if wants_report_write(run.goal) and not any(
                item.tool_name == "write_text" for item in planned
            ):
                planned = list(planned) + self._report_write_tasks()
            return planned
        return self._default_plan(run.goal)

    def _default_plan(self, goal: str) -> list[AgentTask]:
        if wants_gui_interaction(goal):
            return self._gui_interaction_tasks(goal)
        perception = self._perception_tasks(goal)
        if perception:
            return perception
        readme = self._root / "README.md"
        other = self._root / "pyproject.toml"
        if not other.is_file():
            other = self._root / "astra.toml"
        if wants_app_launch(goal):
            tasks = self._app_control_tasks(goal)
            if wants_report_write(goal):
                return tasks + [
                    AgentTask(
                        title="Search project files",
                        tool_name="search_files",
                        arguments={"path": str(readme), "query": _query_from_goal(goal), "mode": "both"},
                    ),
                    AgentTask(
                        title="Read relevant files",
                        tool_name="read_project",
                        arguments={"path": str(readme)},
                    ),
                ] + self._report_write_tasks()
            if any(phrase in goal.lower() for phrase in _EXEC_PHRASES) or "project" in goal.lower():
                return tasks + [
                    AgentTask(
                        title="Inspect project structure",
                        tool_name="read_project",
                        arguments={"path": str(readme)},
                    ),
                ]
            return tasks
        if wants_report_write(goal):
            report = self._report_path()
            query = _query_from_goal(goal)
            return [
                AgentTask(
                    title="Search project files",
                    tool_name="search_files",
                    arguments={"path": str(readme), "query": query, "mode": "both"},
                ),
                AgentTask(
                    title="Read relevant files",
                    tool_name="read_project",
                    arguments={"path": str(readme)},
                ),
                AgentTask(
                    title="Write inspection report",
                    tool_name="write_text",
                    arguments={"path": str(report), "operation": "write"},
                ),
                AgentTask(
                    title="Verify saved report",
                    tool_name="read_text",
                    arguments={"path": str(report)},
                ),
            ]
        return [
            AgentTask(
                title="Inspect project structure",
                tool_name="read_project",
                arguments={"path": str(readme)},
            ),
            AgentTask(
                title="Inspect relevant files",
                tool_name="read_project",
                arguments={"path": str(other)},
            ),
        ]

    def _report_write_tasks(self) -> list[AgentTask]:
        report = self._report_path()
        return [
            AgentTask(
                title="Write inspection report",
                tool_name="write_text",
                arguments={"path": str(report), "operation": "write"},
            ),
            AgentTask(
                title="Verify saved report",
                tool_name="read_text",
                arguments={"path": str(report)},
            ),
        ]

    def _report_path(self) -> Path:
        return self._write_root / "agent-reports" / "inspection.md"

    def _guard_interaction(self, run: AgentRun, task: AgentTask) -> Iterator[bool]:
        reason = self._interaction_limit_reason(run, task)
        if not reason:
            yield from ()
            return False
        run.state = AgentState.WAIT_FOR_USER
        run.error = reason
        run.result = reason
        yield self._state_event(run)
        yield StepEvent(type="error", state=run.state.value, message=reason)
        return True

    def _interaction_limit_reason(self, run: AgentRun, task: AgentTask) -> str:
        elapsed = time.monotonic() - run.started_mono if run.started_mono else 0.0
        interacting = wants_gui_interaction(run.goal) or task.tool_name in _INTERACTION_TOOLS
        if interacting and elapsed >= run.max_interaction_time:
            return "max_interaction_time reached"
        if task.tool_name in _INTERACTION_TOOLS and run.interaction_actions >= run.max_actions:
            return "max_actions reached"
        if task.tool_name == "capture_screen" and interacting:
            if run.visual_observations >= run.max_visual_observations:
                return "max_visual_observations reached"
        return ""

    def _after_successful_action(
        self,
        run: AgentRun,
        task: AgentTask,
        outcome: ToolCallResult,
        leftover: list[AgentTask],
    ) -> bool:
        if task.tool_name == "capture_screen" and wants_gui_interaction(run.goal):
            if _vision_unavailable(outcome.output):
                pending_input = any(item.tool_name in _INTERACTION_TOOLS for item in leftover)
                if pending_input or run.interaction_actions == 0:
                    leftover[:] = [
                        item for item in leftover if item.tool_name not in _INTERACTION_TOOLS
                    ]
                    run.error = VISUAL_REASONING_UNAVAILABLE
                    run.result = VISUAL_REASONING_UNAVAILABLE
                    return True
        if task.tool_name in _INTERACTION_TOOLS and _wants_task_observe(task):
            if leftover and leftover[0].tool_name == "capture_screen":
                return False
            leftover.insert(0, _capture_task())
        return False

    def _gui_interaction_tasks(self, goal: str) -> list[AgentTask]:
        return self._app_control_tasks(goal) + [
            AgentTask(
                title="Inspect the active window",
                tool_name="inspect_active_window",
                arguments={},
            ),
            _capture_task(),
        ]

    def _ensure_gui_observe(self, planned: list[AgentTask]) -> list[AgentTask]:
        have = {item.tool_name for item in planned}
        extra: list[AgentTask] = []
        if "inspect_active_window" not in have:
            extra.append(
                AgentTask(
                    title="Inspect the active window",
                    tool_name="inspect_active_window",
                    arguments={},
                )
            )
        if "capture_screen" not in have:
            extra.append(_capture_task())
        if not extra:
            return planned
        insert_at = 0
        for index, item in enumerate(planned):
            if item.tool_name in {"launch_application", "inspect_process"}:
                insert_at = index + 1
        return list(planned[:insert_at]) + extra + list(planned[insert_at:])

    def _perception_tasks(self, goal: str) -> list[AgentTask]:
        tasks: list[AgentTask] = []
        if wants_window_inspect(goal):
            tasks.append(
                AgentTask(
                    title="Inspect the active window",
                    tool_name="inspect_active_window",
                    arguments={},
                )
            )
        if wants_screen_capture(goal):
            tasks.append(
                AgentTask(
                    title="Capture the desktop",
                    tool_name="capture_screen",
                    arguments={"max_width": "1280", "max_height": "720"},
                )
            )
        return tasks

    def _app_control_tasks(self, goal: str) -> list[AgentTask]:
        app = application_from_goal(goal) or "blender"
        return [
            AgentTask(
                title=f"Launch {app}",
                tool_name="launch_application",
                arguments={"application": app, "timeout": "15"},
            ),
            AgentTask(
                title=f"Verify {app} is running",
                tool_name="inspect_process",
                arguments={"query": app},
            ),
        ]

    def _plan_from_llm(self, run: AgentRun) -> list[AgentTask]:
        if wants_gui_interaction(run.goal):
            pass
        elif wants_perception(run.goal):
            return []
        elif wants_app_launch(run.goal) and not wants_report_write(run.goal):
            if "project" not in run.goal.lower() and not any(
                phrase in run.goal.lower() for phrase in _EXEC_PHRASES
            ):
                return []
        catalog = ", ".join(sorted(ALLOWED_TOOLS))
        hint = _experience_hint(run.experiences) + skill_plan_hint(run.skills)
        raw = self._generate(
            "List at most 4 tasks for this goal.\n"
            f"Use only tools: {catalog}.\n"
            "Each line: TASK tool=NAME path=RELATIVE query=TEXT | short title\n"
            "Do not use shell or network tools.\n"
            "Previous experiences are suggestions only. Do not copy them blindly.\n"
            "Skills are suggestions. You may USE, MODIFY, or IGNORE them.\n"
            "Do not override the current goal or security policy.\n"
            f"{self._compact_prompt(run)}\n"
            f"{hint}"
        )
        if not raw:
            return []
        tasks: list[AgentTask] = []
        for line in raw.splitlines():
            task = self._parse_task_line(line.strip())
            if task is None:
                continue
            tasks.append(task)
            cap = 6 if wants_gui_interaction(run.goal) else 4
            if len(tasks) >= cap:
                break
        return tasks

    def _parse_task_line(self, line: str) -> AgentTask | None:
        match = _TASK_LINE.search(line)
        if match is None:
            return None
        tool, arguments = _parse_task_body(match.group("body"))
        if not tool:
            return None
        title = match.group("title").strip()[:80]
        resolved = self._resolve_arguments(tool, arguments)
        return AgentTask(
            title=title or f"Use {tool}",
            tool_name=tool,
            arguments=resolved,
        )

    def _resolve_arguments(
        self, tool: str, arguments: Mapping[str, str]
    ) -> dict[str, str]:
        resolved = dict(arguments)
        raw_path = str(resolved.get("path") or "").strip()
        if raw_path:
            resolved["path"] = self._resolve_path(tool, raw_path)
        return resolved

    def _resolve_path(self, tool: str, path: str) -> str:
        candidate = Path(path)
        if candidate.is_absolute():
            return str(candidate)
        text = path.replace("\\", "/")
        if tool == "write_text":
            if text.startswith("data/"):
                return str(self._write_root / text[5:])
            return str(self._write_root / text)
        if tool == "run_python":
            root = self._scratch_root or self._write_root
            return str(root / text)
        if tool == "read_text":
            if text.startswith("data/"):
                return str(self._write_root / text[5:])
            under_write = self._write_root / text
            if "agent-reports" in text:
                return str(under_write)
        return str(self._root / path)

    def _replan(
        self,
        goal: str,
        failed: AgentTask,
        remaining: list[AgentTask],
    ) -> list[AgentTask]:
        catalog = ", ".join(sorted(ALLOWED_TOOLS))
        hint = ""
        current = self.last_run
        if current is not None:
            hint = _experience_hint(current.experiences) + skill_plan_hint(
                current.skills
            )
        raw = self._generate(
            "Previous task failed. Propose one replacement task.\n"
            f"Line format: TASK tool=NAME path=FILE | title\nAllowed: {catalog}\n"
            "Previous experiences are suggestions only.\n"
            "Skills are suggestions. You may USE, MODIFY, or IGNORE them.\n"
            "Do not override the current goal or security policy.\n"
            f"{self._compact_prompt(current, failed) if current is not None else ''}\n"
            f"Goal: {goal}\nFailed: {failed.title}\n"
            f"Failure class: {getattr(current, 'failure_class', '') or 'UNKNOWN'}\n"
            f"{hint}"
        )
        parsed: list[AgentTask] = []
        if raw:
            for line in raw.splitlines():
                task = self._parse_task_line(line.strip())
                if task is None:
                    continue
                parsed.append(task)
                break
        if failed.tool_name in _INTERACTION_TOOLS:
            parsed = [item for item in parsed if _interaction_sig(item) != _interaction_sig(failed)]
            leftover = [item for item in remaining if _interaction_sig(item) != _interaction_sig(failed)]
            observe = [
                AgentTask(
                    title="Inspect the active window",
                    tool_name="inspect_active_window",
                    arguments={},
                ),
                _capture_task(),
            ]
            if not parsed:
                return observe + leftover
            return observe + parsed + leftover
        if not parsed:
            alt = self._root / "CONSTITUTION.md"
            if not alt.is_file():
                alt = self._root / "pyproject.toml"
            parsed = [
                AgentTask(
                    title="Inspect fallback file",
                    tool_name="read_project",
                    arguments={"path": str(alt)},
                )
            ]
        return parsed + remaining

    def _self_improve_hook(
        self, run: AgentRun, *, objective: str | None = None
    ) -> Iterator[StepEvent]:
        """Coding → test → evaluate → WAIT_FOR_USER. Never deploy."""
        run.state = AgentState.EXECUTE
        yield self._state_event(run)
        yield StepEvent(
            type="task",
            state=run.state.value,
            message="Propose an improvement",
        )
        proposal = None
        coding = self._coding
        target_goal = (objective or run.goal).strip()
        if coding is not None and callable(getattr(coding, "propose_change", None)):
            targets = _targets_from(run)
            try:
                proposal = coding.propose_change(target_goal, targets)
            except Exception as exc:  # noqa: BLE001
                run.error = f"coding failed: {type(exc).__name__}"
        run.proposal = proposal
        changes = tuple(getattr(proposal, "changes", ()) or ()) if proposal else ()

        tests = None
        if changes and self._testing is not None:
            test_fn = getattr(self._testing, "test_proposal", None)
            if callable(test_fn):
                try:
                    tests = test_fn(proposal)
                except Exception as exc:  # noqa: BLE001
                    run.error = f"testing failed: {type(exc).__name__}"
        run.test_report = tests
        if tests is not None:
            run.tests_run = int(getattr(tests, "tests_run", 0) or 0)
            run.tests_passed = int(getattr(tests, "tests_passed", 0) or 0)

        evaluation = None
        if changes and tests is not None and self._evaluation is not None:
            eval_fn = getattr(self._evaluation, "evaluate_proposal", None)
            if callable(eval_fn):
                try:
                    evaluation = eval_fn(proposal, tests)
                except Exception:
                    evaluation = None
        run.evaluation = evaluation

        run.pending_kind = "proposal"
        run.state = AgentState.WAIT_FOR_USER
        run.result = _improvement_prompt(run, proposal, changes, evaluation)
        yield self._state_event(run)
        yield StepEvent(
            type="approval",
            state=run.state.value,
            message=run.result,
        )
        yield StepEvent(
            type="completed",
            state=run.state.value,
            message=run.result.split("\n", 1)[0],
        )

    def _after_failure(self, run: AgentRun) -> Iterator[StepEvent]:
        self._store_experience(run)
        self._finish_persistent(run, success=False)
        yield from self._skill_events(run)
        weakness = detect_software_weakness(run)
        if weakness and self._coding is not None:
            yield from self._self_improve_hook(run, objective=weakness)

    def _skill_events(self, run: AgentRun) -> Iterator[StepEvent]:
        notes = list(run.pending_skill_events)
        run.pending_skill_events.clear()
        for message in notes:
            yield StepEvent(type="skill", state=run.state.value, message=message)

    def _sync_skills(self, run: AgentRun) -> None:
        if run.skill_synced or self._memory is None:
            return
        run.skill_synced = True
        tools = tuple(
            dict.fromkeys(
                task.tool_name
                for task in (*run.completed_tasks, *run.failed_tasks, *run.plan)
                if task.tool_name
            )
        )
        success = run.state is not AgentState.FAILED
        if run.used_skill is not None:
            updated, note = apply_skill_outcome(run.used_skill, success=success)
            persist_skill(self._memory, updated, run.skill_memory)
            run.used_skill = updated
            if updated.status == "failed":
                run.skill_collapse = True
            if note:
                run.skill_notes.append(note)
                run.pending_skill_events.append(note)
            return
        if not success:
            return
        strategy = ""
        if run.experiences:
            strategy = run.experiences[0].strategy
        if not strategy:
            strategy = "Inspect relevant files, then diagnose."
        created = maybe_create_skill(
            self._memory, goal=run.goal, tools=tools, strategy=strategy
        )
        if created is None:
            return
        run.skill_notes.append(f"Skill candidate created: {created.name}")
        run.pending_skill_events.append(
            f"Skill candidate created: {created.name}"
        )

    def _snapshot_metrics(self, run: AgentRun) -> None:
        run.total_steps = run.step_count
        run.replans = run.replan_count
        run.failures = len(run.failed_tasks)
        run.completed = len(run.completed_tasks)
        if run.started_mono:
            run.duration = max(0.0, time.monotonic() - run.started_mono)
        else:
            run.duration = 0.0

    def _store_experience(self, run: AgentRun) -> None:
        store = getattr(self._memory, "store", None)
        if not callable(store) or run.step_count < 1:
            return
        self._snapshot_metrics(run)
        try:
            from astra.memory.contracts import MemoryRecord, VerificationState
            from astra.memory.contracts import MemoryCategory
        except Exception:
            return
        records = _experience_records(run)
        for item, category, tags in records:
            try:
                ident = store(
                    MemoryRecord(
                        record_id="",
                        category=category,
                        content=format_experience(item),
                        verification=VerificationState.UNVERIFIED,
                        source=EXPERIENCE_SOURCE,
                        tags=tags,
                    )
                )
                if ident:
                    run.memory_ids.append(str(ident))
                run.memory_written = True
            except Exception:
                continue
        self._sync_skills(run)

    def _reflect(self, run: AgentRun) -> str:
        done = len(run.completed_tasks)
        failed = len(run.failed_tasks)
        succeeded = ", ".join(item.title for item in run.completed_tasks[:4]) or "none"
        failed_names = ", ".join(item.title for item in run.failed_tasks[:4]) or "none"
        lesson = "none"
        if run.experiences:
            lesson = run.experiences[0].lesson or run.experiences[0].strategy or "none"
        weakness = detect_software_weakness(run) or "none"
        return (
            f"Objective: {run.goal.rstrip('.')}. Actions: {done} succeeded, {failed} failed, "
            f"{run.step_count} steps, {run.replan_count} replans. "
            f"Succeeded: {succeeded}. Failed: {failed_names}. "
            f"Lesson: {_clip(lesson, 160)}. Weakness: {_clip(weakness, 120)}."
        )

    def _bind_persistent(self, run: AgentRun) -> Any | None:
        if self._goals is None or self._planning is None:
            return None
        if is_resume_request(run.goal):
            found = None
            if self.active_goal_id:
                found = self._goals.get_goal(self.active_goal_id)
            if found is None:
                found = find_active_goal(self._goals, "")
            if found is None:
                return None
            run.persistent_goal_id = found.goal_id
            self.active_goal_id = found.goal_id
            run.progress_note = resume_lines(self._planning, found)
            return found
        if not is_persistent_objective(run.goal):
            return None
        found = ensure_goal(
            self._goals,
            self._planning,
            run.goal,
            priority=priority_from_message(run.goal),
        )
        if found is None:
            return None
        run.persistent_goal_id = found.goal_id
        self.active_goal_id = found.goal_id
        run.progress_note = (
            f"Goal created:\n{found.description}\n\n{plan_listing(self._planning, found)}"
        )
        return found

    def _bound_goal(self, run: AgentRun) -> Any | None:
        if self._goals is None:
            return None
        ident = run.persistent_goal_id or self.active_goal_id
        if ident and callable(getattr(self._goals, "get_goal", None)):
            got = self._goals.get_goal(ident)
            if got is not None:
                return got
        return find_active_goal(self._goals, run.goal)

    def _plan_for_backlog_title(self, title: str) -> list[AgentTask]:
        text = str(title or "").lower()
        if "report" in text:
            return self._default_plan(
                "Inspect my project, find the relevant files, create a small "
                "report describing one problem, and save the report as a file."
            )
        return self._default_plan(
            "Inspect my project and determine what needs improvement."
        )

    def _finish_persistent(self, run: AgentRun, *, success: bool) -> None:
        if not run.backlog_task_id:
            return
        if success:
            complete_task(self._planning, run.backlog_task_id, result=run.result or "ok")
            bound = self._bound_goal(run)
            if bound is not None and all_tasks_done(self._planning, bound):
                maybe_achieve(self._goals, bound)
        else:
            fail_task(self._planning, run.backlog_task_id, error=run.error or "task failed")
        bound = self._bound_goal(run)
        if bound is not None:
            extra = "\n".join(progress_lines(self._planning, bound))
            if extra:
                run.progress_note = (run.progress_note + "\n\n" + extra).strip()

    def _inspect_result(self, run: AgentRun, reflection: str) -> str:
        bits = []
        if run.progress_note:
            bits.append(run.progress_note)
        if run.experience_note:
            bits.append(run.experience_note)
        skill = run.used_skill
        if skill is not None:
            rate = (
                f"{skill.successes}/{skill.attempts}" if skill.attempts else "0/0"
            )
            bits.append(f"Relevant skill found: {skill.name}.")
            bits.append(f"Previous success rate: {rate}.")
            bits.append("I'll use it as a starting strategy.")
            if skill.status == "degraded":
                bits.append("Skill performance degraded.")
            if skill.status == "verified":
                bits.append(f"Skill {skill.name} verified.")
        elif any("Skill candidate created" in note for note in run.skill_notes):
            bits.append(run.skill_notes[-1])
        bits.append(reflection)
        if run.experiences:
            bits.append(compare_metrics(run.experiences[0], run))
        if run.observations:
            bits.append("Findings:")
            bits.extend(f"- {_clip(item, 280)}" for item in run.observations[:4])
        else:
            bits.append("No file contents were retrieved.")
        written = [
            str((task.arguments or {}).get("path", "") or "")
            for task in run.completed_tasks
            if task.tool_name == "write_text"
        ]
        if written:
            bits.append("Saved: " + ", ".join(p for p in written if p))
        return "\n".join(bits)

    def _generate(self, prompt: str) -> str:
        llm = self._llm
        if llm is None:
            return ""
        available = getattr(llm, "is_available", None)
        generate = getattr(llm, "generate", None)
        if not callable(generate):
            return ""
        try:
            if callable(available) and not available():
                return ""
        except Exception:
            return ""
        try:
            text = generate(prompt)
        except Exception:
            return ""
        if self.last_run is not None:
            self.last_run.llm_calls += 1
        from astra.presentation.conversation import strip_thinking

        return strip_thinking(str(text or "").strip())

    @staticmethod
    def _state_event(run: AgentRun) -> StepEvent:
        return StepEvent(type="agent_state", state=run.state.value)


def _clip(text: str, width: int) -> str:
    value = re.sub(r"\s+", " ", str(text or "")).strip()
    if len(value) <= width:
        return value
    return value[: width - 1] + "…"


def _experience_hint(records: Sequence[ExperienceRecord] | None) -> str:
    if not records:
        return ""
    lines = ["Previous experience (suggestion only):"]
    for item in records[:2]:
        lines.append(f"- goal: {_clip(item.goal, 160)}")
        if item.lesson:
            lines.append(f"  lesson: {_clip(item.lesson, 200)}")
        if item.strategy:
            lines.append(f"  strategy: {_clip(item.strategy, 200)}")
    return "\n".join(lines) + "\n"


def _experience_records(run: AgentRun) -> list[tuple[ExperienceRecord, Any, tuple[str, ...]]]:
    from astra.memory.contracts import MemoryCategory

    _snapshot_inline(run)
    tools = tuple(
        dict.fromkeys(
            task.tool_name
            for task in (*run.completed_tasks, *run.failed_tasks, *run.plan)
            if task.tool_name
        )
    )
    successful = tuple(task.title for task in run.completed_tasks)
    failed = tuple(task.title for task in run.failed_tasks)
    stamp = utc_now()
    items: list[tuple[ExperienceRecord, Any, tuple[str, ...]]] = []
    if run.failed_tasks or run.state is AgentState.FAILED:
        first_fail = failed[0] if failed else "a step"
        recovered = bool(run.completed_tasks) and run.state is not AgentState.FAILED
        lesson = (
            f"After {first_fail} failed ({run.error or 'tool error'}), "
            f"{'a fallback task worked' if recovered else 'the run did not recover'}."
        )
        strategy = (
            "Replan after a failed inspect and try a fallback project file."
            if run.completed_tasks
            else "Diagnose the failed tool before repeating the same action."
        )
        items.append(
            (
                ExperienceRecord(
                    goal=run.goal,
                    outcome="FAILED" if run.state is AgentState.FAILED else "RECOVERED",
                    tasks=tuple(task.title for task in run.plan) or failed + successful,
                    tools=tools,
                    successful=successful,
                    failed=failed,
                    failure_reason=run.error or (failed[0] if failed else "task failed"),
                    strategy=strategy,
                    lesson=lesson,
                    timestamp=stamp,
                    steps=run.total_steps,
                    tool_calls=run.tool_calls,
                    replans=run.replans,
                    failures=run.failures,
                    duration_s=run.duration,
                    completed=run.completed,
                    approval_waits=run.approval_waits,
                    tags=("strategy_fail", "lesson", "tool_note"),
                    category="failures",
                ),
                MemoryCategory.FAILURES,
                ("strategy_fail", "lesson", "tool_note"),
            )
        )
    if run.state is AgentState.COMPLETE or (
        run.completed_tasks and run.state is not AgentState.FAILED
    ):
        lesson = (
            f"Plan using {', '.join(tools) or 'inspect tools'} completed the goal."
            if not run.failed_tasks
            else "Recovered by replanning after a failed action; keep the working fallback."
        )
        items.append(
            (
                ExperienceRecord(
                    goal=run.goal,
                    outcome="COMPLETE",
                    tasks=tuple(task.title for task in run.plan) or successful,
                    tools=tools,
                    successful=successful,
                    failed=failed,
                    failure_reason="",
                    strategy="Execute the planned inspect tools; treat past lessons as suggestions.",
                    lesson=lesson,
                    timestamp=stamp,
                    steps=run.total_steps,
                    tool_calls=run.tool_calls,
                    replans=run.replans,
                    failures=run.failures,
                    duration_s=run.duration,
                    completed=run.completed,
                    approval_waits=run.approval_waits,
                    tags=("strategy_ok", "lesson", "skill"),
                    category="experiments",
                ),
                MemoryCategory.EXPERIMENTS,
                ("strategy_ok", "lesson", "skill"),
            )
        )
    return items


def _snapshot_inline(run: AgentRun) -> None:
    run.total_steps = run.step_count
    run.replans = run.replan_count
    run.failures = len(run.failed_tasks)
    run.completed = len(run.completed_tasks)


def _targets_from(run: AgentRun) -> tuple[Path, ...]:
    found: list[Path] = []
    for task in run.completed_tasks:
        raw = str((task.arguments or {}).get("path", "") or "").strip()
        if raw:
            found.append(Path(raw))
        if len(found) >= 3:
            break
    return tuple(found)


def _query_from_goal(goal: str) -> str:
    text = re.sub(r"\s+", " ", str(goal or "")).strip()
    if not text:
        return "TODO"
    return text[:80]


def _report_from_observations(run: AgentRun) -> str:
    findings = "\n".join(f"- {_clip(item, 400)}" for item in run.observations[:6])
    if not findings:
        findings = "- Search and read returned no file contents."
    problem = (
        _clip(run.observations[0], 280)
        if run.observations
        else "No specific file problem isolated."
    )
    return (
        f"# Inspection report\n\n"
        f"Goal: {run.goal}\n\n"
        f"## Problem\n\n{problem}\n\n"
        f"## Findings\n\n{findings}\n"
    )


def _task_from_decision(task: AgentTask, decision: AgentDecision | None) -> AgentTask:
    if decision is None:
        return task
    return AgentTask(
        title=decision.current_task or task.title,
        tool_name=decision.tool or task.tool_name,
        arguments=dict(decision.arguments or task.arguments),
        expected_observation=decision.expected_observation,
        success_condition=decision.success_condition,
        confidence=decision.confidence,
    )


def _capture_task() -> AgentTask:
    return AgentTask(
        title="Capture the desktop",
        tool_name="capture_screen",
        arguments={"max_width": "1280", "max_height": "720"},
    )


def _interaction_sig(task: AgentTask) -> str:
    args = task.arguments or {}
    return "|".join(
        (
            task.tool_name,
            str(args.get("action") or ""),
            str(args.get("x") or ""),
            str(args.get("y") or ""),
            str(args.get("button") or ""),
            str(args.get("text") or ""),
            str(args.get("key") or ""),
            str(args.get("keys") or ""),
        )
    )


def _vision_unavailable(output: str) -> bool:
    text = str(output or "")
    return any(marker in text for marker in _VISION_MARKERS)


def _wants_task_observe(task: AgentTask) -> bool:
    text = str((task.arguments or {}).get("observe") or "true").strip().lower()
    return text not in {"0", "false", "no", "off"}


def _approval_prompt(task: AgentTask, outcome: ToolCallResult) -> str:
    target = str((task.arguments or {}).get("path", "") or "")
    reason = outcome.error or "Permanent change requires approval."
    risk = "Permanent filesystem change"
    if task.tool_name == "write_text":
        risk = "Writes or overwrites a file under data/logs"
    elif task.tool_name == "run_python":
        risk = "Runs Python in an isolated scratch session"
    elif task.tool_name == "launch_application":
        risk = "Starts an allowlisted local application"
    elif task.tool_name in _INTERACTION_TOOLS:
        risk = "Controls the local mouse or keyboard"
        target = (
            str((task.arguments or {}).get("action") or "")
            + " "
            + str((task.arguments or {}).get("x") or "")
            + ","
            + str((task.arguments or {}).get("y") or "")
            + str((task.arguments or {}).get("text") or "")
            + str((task.arguments or {}).get("keys") or task.arguments.get("key") or "")
        ).strip()
    return (
        "Approval required.\n\n"
        f"Action: {task.title}\n"
        f"Tool: {task.tool_name}\n"
        f"Target: {target}\n"
        f"Reason: {reason}\n"
        f"Risk: {risk}\n\n"
        "Approve / Reject"
    )


def _improvement_prompt(
    run: AgentRun,
    proposal: Any,
    changes: tuple[Any, ...],
    evaluation: Any,
) -> str:
    objective = str(getattr(proposal, "objective", "") or run.goal)
    if changes:
        first = changes[0]
        change = (
            f"{getattr(first, 'path', '')}: "
            f"{getattr(first, 'rationale', '') or 'proposed file change'}"
        )
    else:
        change = "No reviewable code change was produced."
    label = "same"
    status = str(getattr(evaluation, "comparison_status", "") or "")
    if status in {"improved"} or bool(getattr(evaluation, "improved", False)):
        label = "better"
    elif status in {"regressed"}:
        label = "worse"
    return (
        "Improvement proposed\n\n"
        f"Problem:\n{_clip(objective, 280)}\n\n"
        f"Change:\n{_clip(change, 280)}\n\n"
        f"Tests:\n{run.tests_passed}/{run.tests_run} passed\n\n"
        f"Evaluation:\n{label}\n\n"
        "Apply?\n"
        "No production code was modified."
    )


def _parse_task_body(body: str) -> tuple[str, dict[str, str]]:
    tool = ""
    arguments: dict[str, str] = {}
    for part in str(body or "").split():
        if "=" not in part:
            continue
        key, value = part.split("=", 1)
        key = key.strip().lower()
        value = value.strip().strip("\"'")
        if key == "tool":
            tool = value
        elif key:
            arguments[key] = value
    return tool, arguments


def _parse_tool_json(raw: str) -> tuple[str, dict[str, str]] | None:
    text = str(raw or "").strip()
    if not text:
        return None
    match = _JSON_TOOL.search(text)
    blob = match.group(0) if match is not None else text
    try:
        data = json.loads(blob)
    except json.JSONDecodeError:
        return None
    if not isinstance(data, dict):
        return None
    tool = str(data.get("tool") or "").strip()
    if not tool:
        return None
    arguments: dict[str, str] = {}
    raw_args = data.get("arguments")
    if isinstance(raw_args, dict):
        for key, value in raw_args.items():
            if value is None:
                continue
            arguments[str(key)] = str(value)
    for key in (
        "path",
        "query",
        "content",
        "mode",
        "code",
        "timeout",
        "operation",
        "application",
        "executable",
        "pid",
        "max_width",
        "max_height",
        "action",
        "x",
        "y",
        "button",
        "click_count",
        "scroll_amount",
        "text",
        "key",
        "keys",
        "observe",
    ):
        if key in data and key not in arguments and data.get(key) is not None:
            arguments[key] = str(data.get(key))
    return tool, arguments


def record_from_invoker(record: Any) -> ToolCallResult:
    """Adapt a ToolInvocationRecord to ToolCallResult."""
    status_obj = getattr(record, "status", None)
    status = str(getattr(status_obj, "value", status_obj) or "")
    result = getattr(record, "result", None)
    output = str(getattr(result, "output", "") or "") if result is not None else ""
    error = str(getattr(result, "error", "") or "") if result is not None else ""
    if not error:
        error = str(getattr(record, "reason", "") or "")
    executed = bool(getattr(record, "executed", False))
    success = executed and bool(getattr(result, "success", False))
    ident = str(getattr(record, "invocation_id", "") or "")
    if status in _APPROVAL_STATUSES:
        return ToolCallResult(
            success=False,
            status="pending_approval",
            error="Approval required.",
            invocation_id=ident,
        )
    if not executed:
        mapped = status if status in _UNAVAILABLE_STATUSES else "unavailable"
        return ToolCallResult(
            success=False,
            status=mapped,
            error=error or mapped,
            invocation_id=ident,
        )
    return ToolCallResult(
        success=success,
        status=status or "executed",
        output=output,
        error=error,
        invocation_id=ident,
    )


__all__ = [
    "ALLOWED_TOOLS",
    "AgentLoop",
    "AgentRun",
    "AgentState",
    "AgentTask",
    "StepEvent",
    "ToolCallResult",
    "is_execution_goal",
    "record_from_invoker",
    "is_persistent_objective",
    "wants_app_launch",
    "wants_gui_interaction",
    "wants_improvement",
    "wants_perception",
    "wants_report_write",
    "wants_screen_capture",
    "wants_window_inspect",
    "VISUAL_REASONING_UNAVAILABLE",
    "AgentDecision",
]
