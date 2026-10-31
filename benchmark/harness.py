"""Execute AgentRun tasks in mock environments and record external facts."""

from __future__ import annotations

import time
from typing import Any

from astra.core.agent_loop import AgentLoop, AgentState
from astra.planning.goals import GoalStatus
from benchmark.fakes import (
    BenchmarkTools,
    FakeBacklog,
    FakeGoals,
    FakeLLM,
    FakeMemory,
)
from benchmark.gated_invoke import GatedBenchmarkInvoker, build_gated_invoker
from benchmark.scoring import TaskScore, aggregate, score_task
from benchmark.tasks import TaskSpec, catalog
from tests.test_agent_loop import _loop
from tests.test_skills import _parse_stored, _skill_records


def _trace_from_run(run: Any, events: list[Any] | None = None) -> dict[str, Any]:
    """UI-safe facts only. No prompts. No hidden reasoning."""
    events = events or []
    plan = [item.title for item in getattr(run, "plan", []) or []]
    actions = [
        {"tool": item.tool_name, "title": item.title}
        for item in (
            *list(getattr(run, "completed_tasks", []) or []),
            *list(getattr(run, "failed_tasks", []) or []),
        )
    ]
    return {
        "goal": getattr(run, "goal", ""),
        "run_id": getattr(run, "run_id", ""),
        "state": getattr(getattr(run, "state", None), "value", str(getattr(run, "state", ""))),
        "plan": plan,
        "actions": actions,
        "observations": list(getattr(run, "observations", []) or []),
        "replans": int(getattr(run, "replan_count", 0) or 0),
        "failures": [item.title for item in getattr(run, "failed_tasks", []) or []],
        "tool_calls": int(getattr(run, "tool_calls", 0) or 0),
        "completion": getattr(getattr(run, "state", None), "value", ""),
        "time": round(float(getattr(run, "duration", 0.0) or 0.0), 4),
        "approvals": int(getattr(run, "approval_waits", 0) or 0),
        "final_result": str(getattr(run, "result", "") or "")[:800],
        "error": str(getattr(run, "error", "") or ""),
        "event_types": [getattr(item, "type", "") for item in events],
        "llm_calls": int(getattr(run, "llm_calls", 0) or 0),
    }


def _bundle_metrics(run: Any, bundle: dict[str, Any], llm: Any | None = None) -> None:
    invalid_args = 0
    obs_mismatch = 0
    security_denials = 0
    for decision in getattr(run, "decisions", []) or []:
        from astra.core.agent_decision import validate_decision

        reason = validate_decision(decision)
        if reason and "required" in str(reason).lower():
            invalid_args += 1
    if str(getattr(run, "failure_class", "") or "") == "OBSERVATION_MISMATCH":
        obs_mismatch += 1
    if "denied" in str(getattr(run, "error", "") or "").lower():
        security_denials += 1
    bundle["invalid_arguments"] = invalid_args
    bundle["observation_mismatches"] = obs_mismatch
    bundle["security_denials"] = security_denials
    bundle["llm_calls"] = int(getattr(run, "llm_calls", 0) or 0)
    if llm is not None and hasattr(llm, "total_latency_s"):
        bundle["llm_latency_s"] = float(getattr(llm, "total_latency_s", 0.0) or 0.0)
    if getattr(run, "used_skill", None) is not None:
        bundle["skill_used"] = getattr(run.used_skill, "name", "")
        bundle["skill_action"] = "USE"


def _make_loop(
    spec: TaskSpec,
    *,
    memory: Any | None = None,
    tools: Any | None = None,
    llm: Any | None = None,
    invoke: Any | None = None,
    execute_approved: Any | None = None,
) -> AgentLoop:
    if tools is None:
        backend = BenchmarkTools()
        backend.failures_left = spec.tool_failures
        backend.vision_available = spec.vision
        backend.approval_for = spec.approval_for
        if llm is not None and not isinstance(llm, FakeLLM):
            gated = build_gated_invoker(backend)
            tools = gated
            invoke = gated
            execute_approved = gated.execute_approved
        else:
            tools = backend
    model = llm if llm is not None else FakeLLM(script=list(spec.llm_script), default=spec.llm_default)
    kwargs: dict[str, Any] = {
        "llm": model,
        "tools": tools,
        "memory": memory if memory is not None else FakeMemory(),
        "max_steps": 10,
        "max_replans": 2,
        "project_root": __import__("astra.configuration.paths", fromlist=["project_root"]).project_root(),
    }
    loop = _loop(**kwargs)
    if invoke is not None:
        loop._invoke = invoke  # type: ignore[attr-defined]
    if execute_approved is not None:
        loop._execute_approved = execute_approved  # type: ignore[attr-defined]
    return loop


def _run_single(spec: TaskSpec, *, llm: Any | None = None) -> dict[str, Any]:
    loop = _make_loop(spec, llm=llm)
    started = time.monotonic()
    events = list(loop.iter_run(spec.goal))
    run = loop.last_run
    if spec.auto_resume_approval and run is not None and run.state is AgentState.WAIT_FOR_USER:
        if run.pending_kind == "tool":
            run = loop.resume(True)
    if run is not None and not getattr(run, "duration", 0):
        run.duration = max(0.0, time.monotonic() - started)
    invoke = loop._invoke
    if isinstance(invoke, GatedBenchmarkInvoker):
        names = [name for name, _args in invoke.calls]
        successful = [name for name in names if name not in invoke.denied]
    elif hasattr(invoke, "calls"):
        names = [name for name, _args in invoke.calls]
        successful = [name for name in names if name not in getattr(invoke, "denied", [])]
    else:
        names = []
        successful = []
    assert run is not None
    bundle = {
        "run": run,
        "events": events,
        "trace": _trace_from_run(run, [item for item in events if item is not None]),
        "state": run.state.value,
        "error": run.error,
        "tool_names": names,
        "successful_tools": successful,
        "experience_retrieved": bool(run.experiences),
        "skill_status": getattr(run.used_skill, "status", "") if run.used_skill else "",
        "proposal": run.proposal,
    }
    _bundle_metrics(run, bundle, llm)
    return bundle


def _run_memory_pair(spec: TaskSpec, *, llm: Any | None = None) -> dict[str, Any]:
    memory = FakeMemory()
    first_loop = _make_loop(
        TaskSpec(task_id=spec.task_id + "_first", category=spec.category, goal=spec.goal),
        memory=memory,
        llm=llm,
    )
    first = first_loop.run(spec.goal)
    second_goal = spec.extra_goals[0] if spec.extra_goals else spec.goal
    second_loop = _make_loop(
        TaskSpec(task_id=spec.task_id, category=spec.category, goal=second_goal),
        memory=memory,
        llm=llm,
    )
    second = second_loop.run(second_goal)
    invoke = second_loop._invoke
    if isinstance(invoke, GatedBenchmarkInvoker):
        names = [name for name, _args in invoke.calls]
        successful = [name for name in names if name not in invoke.denied]
    else:
        names = [name for name, _args in getattr(invoke, "calls", [])]
        successful = names
    bundle = {
        "run": second,
        "events": [],
        "trace": _trace_from_run(second),
        "state": second.state.value,
        "error": second.error,
        "tool_names": names,
        "successful_tools": successful,
        "experience_retrieved": bool(second.experiences) or bool(second.experience_note),
        "skill_status": "",
        "proposal": None,
        "prior_run_state": first.state.value,
        "prior_steps": int(getattr(first, "step_count", 0) or 0),
        "prior_llm_calls": int(getattr(first, "llm_calls", 0) or 0),
    }
    _bundle_metrics(second, bundle, llm)
    return bundle


def _run_skill_use(spec: TaskSpec, *, llm: Any | None = None) -> dict[str, Any]:
    from tests.test_skills import _plant

    memory = FakeMemory()
    _plant(memory, status="verified", attempts=4, successes=4)
    loop = _make_loop(spec, memory=memory, llm=llm)
    run = loop.run(spec.goal)
    invoke = loop._invoke
    if isinstance(invoke, GatedBenchmarkInvoker):
        names = [name for name, _args in invoke.calls]
        successful = [name for name in names if name not in invoke.denied]
    else:
        names = [item.tool_name for item in run.completed_tasks]
        successful = names
    bundle = {
        "run": run,
        "events": [],
        "trace": _trace_from_run(run),
        "state": run.state.value,
        "error": run.error,
        "tool_names": names,
        "successful_tools": successful,
        "experience_retrieved": bool(run.experiences),
        "skill_status": getattr(run.used_skill, "status", "") if run.used_skill else "",
        "skill_used": bool(run.used_skill),
        "proposal": None,
    }
    _bundle_metrics(run, bundle, llm)
    return bundle


def _run_skill_chain(spec: TaskSpec, *, llm: Any | None = None) -> dict[str, Any]:
    memory = FakeMemory()
    goals = (spec.goal,) + spec.extra_goals
    last = None
    for goal in goals:
        loop = _make_loop(
            TaskSpec(task_id=spec.task_id, category=spec.category, goal=goal),
            memory=memory,
            llm=llm,
        )
        last = loop.run(goal)
    assert last is not None
    stored = _parse_stored(memory)
    status = stored.status if stored is not None else ""
    return {
        "run": last,
        "events": [],
        "trace": _trace_from_run(last),
        "state": last.state.value,
        "error": last.error,
        "tool_names": [item.tool_name for item in last.completed_tasks],
        "successful_tools": [item.tool_name for item in last.completed_tasks],
        "experience_retrieved": bool(last.experiences),
        "skill_status": status,
        "proposal": None,
        "skill_count": len(_skill_records(memory)),
        "skill_success_rate": getattr(stored, "success_rate", None),
    }


def _run_persist(spec: TaskSpec) -> dict[str, Any]:
    goals, planning = FakeGoals(), FakeBacklog()
    tools = BenchmarkTools()
    loop = _loop(
        llm=FakeLLM(default=""),
        tools=tools,
        planning=planning,
        goals=goals,
    )
    events = []
    for event in loop.iter_run(spec.goal):
        events.append(event)
        if spec.pause_on and event.type == spec.pause_on:
            break
    paused = loop.pause()
    stored = None
    goal_active = False
    if paused is not None and paused.persistent_goal_id:
        stored = goals.get_goal(paused.persistent_goal_id)
        goal_active = stored is not None and stored.status is GoalStatus.ACTIVE
    second = _loop(llm=FakeLLM(default=""), tools=BenchmarkTools(), planning=planning, goals=goals)
    if paused is not None:
        second.active_goal_id = paused.persistent_goal_id
    resumed = second.run("Continue.")
    names = [name for name, _args in tools.calls]
    run = resumed
    return {
        "run": run,
        "events": events,
        "trace": _trace_from_run(run, events),
        "state": run.state.value,
        "error": run.error,
        "tool_names": names + [item.tool_name for item in run.completed_tasks],
        "successful_tools": [item.tool_name for item in run.completed_tasks],
        "experience_retrieved": False,
        "skill_status": "",
        "proposal": None,
        "goal_active_after_pause": goal_active,
        "paused_state": getattr(getattr(paused, "state", None), "value", ""),
        "resumed": run.state is AgentState.COMPLETE and paused is not None,
    }


def execute_spec(spec: TaskSpec, *, llm: Any | None = None) -> dict[str, Any]:
    if spec.scenario == "memory_pair":
        return _run_memory_pair(spec, llm=llm)
    if spec.scenario == "skill_use":
        return _run_skill_use(spec, llm=llm)
    if spec.scenario == "skill_chain":
        return _run_skill_chain(spec, llm=llm)
    if spec.scenario == "persist":
        return _run_persist(spec)
    return _run_single(spec, llm=llm)


def run_task(spec: TaskSpec, *, live: bool = False, llm: Any | None = None) -> dict[str, Any]:
    """Run all trials for one spec and score the last trial plus repeat stats."""
    trials: list[dict[str, Any]] = []
    scores: list[TaskScore] = []
    for _ in range(max(1, spec.trials)):
        bundle = execute_spec(spec, llm=llm)
        scored = score_task(spec, bundle, live=live)
        scores.append(scored)
        trials.append(
            {
                "trace": bundle["trace"],
                "score": scored.to_dict(),
                "skill_status": bundle.get("skill_status"),
                "skill_success_rate": bundle.get("skill_success_rate"),
                "goal_active_after_pause": bundle.get("goal_active_after_pause"),
                "paused_state": bundle.get("paused_state"),
            }
        )
    last = scores[-1]
    repeat = None
    if spec.trials > 1:
        repeat = {
            "attempts": spec.trials,
            "successes": sum(1 for item in scores if item.verdict == "SUCCESS"),
            "failures": sum(1 for item in scores if item.verdict == "FAIL"),
            "partials": sum(1 for item in scores if item.verdict == "PARTIAL"),
            "average_steps": round(sum(item.steps for item in scores) / spec.trials, 3),
            "average_replans": round(sum(item.replans for item in scores) / spec.trials, 3),
        }
    return {
        "spec_id": spec.task_id,
        "category": spec.category,
        "goal": spec.goal,
        "score": last,
        "trials": trials,
        "repeat": repeat,
    }


def run_benchmark(
    specs: tuple[TaskSpec, ...] | None = None,
    *,
    llm_backend: str = "fake",
    llm: Any | None = None,
    live: bool = False,
) -> dict[str, Any]:
    """Run the catalog. Fake or live LLM. Local only."""
    selected = specs or catalog()
    rows = [run_task(spec, live=live, llm=llm) for spec in selected]
    scored = [row["score"] for row in rows]
    summary = aggregate(scored)
    summary["total_tool_calls"] = sum(
        int((row["trials"][-1]["trace"] if row["trials"] else {}).get("tool_calls", 0) or 0)
        for row in rows
    )
    repeats = [
        {"task_id": row["spec_id"], **row["repeat"]}
        for row in rows
        if row["repeat"] is not None
    ]
    return {
        "llm_backend": llm_backend,
        "task_count": len(rows),
        "summary": summary,
        "tasks": [
            {
                "task_id": row["spec_id"],
                "category": row["category"],
                "goal": row["goal"],
                "score": row["score"].to_dict(),
                "trials": row["trials"],
                "repeat": row["repeat"],
            }
            for row in rows
        ],
        "repeated": repeats,
        "failures": [
            {
                "task_id": row["spec_id"],
                "verdict": row["score"].verdict,
                "reason": row["score"].failure_reason,
                "notes": row["score"].notes,
            }
            for row in rows
            if row["score"].verdict != "SUCCESS"
        ],
    }


__all__ = ["execute_spec", "run_benchmark", "run_task"]
