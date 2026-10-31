"""Supervised improvement experiments. Human approval stays mandatory."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from astra.core.agent_loop import AgentState
from astra.core.pipeline import process_change_proposal
from astra.deployment.contracts import ApprovalRecord
from astra.deployment.fingerprint import evidence_fingerprint
from benchmark.fakes import (
    BenchmarkTools,
    FakeCoding,
    FakeEvaluation,
    FakeLLM,
    FakeTesting,
    RecordingDeploy,
)
from tests.test_agent_loop import _loop
from tests.test_experience import _FAIL_GOAL


def run_self_improvement() -> dict[str, Any]:
    """Coding → test → evaluate → WAIT_FOR_USER. Does not approve."""
    coding = FakeCoding()
    deploy = RecordingDeploy()
    tools = BenchmarkTools()
    tools.failures_left = 99
    loop = _loop(
        llm=FakeLLM(default=""),
        tools=tools,
        coding=coding,
        testing=FakeTesting(),
        evaluation=FakeEvaluation(),
        max_replans=1,
        max_steps=8,
    )
    run = loop.run(_FAIL_GOAL)
    return {
        "goal": run.goal,
        "state": run.state.value,
        "error": run.error,
        "stopped_at_wait": run.state is AgentState.WAIT_FOR_USER,
        "proposal_present": run.proposal is not None,
        "coding_objectives": list(coding.objectives),
        "tests_run": run.tests_run,
        "tests_passed": run.tests_passed,
        "evaluation_passed": bool(getattr(run.evaluation, "evaluation_passed", False)),
        "deploy_calls": len(deploy.calls),
        "result_excerpt": str(run.result or "")[:400],
        "approved": False,
        "run": run,
        "coding": coding,
        "testing": FakeTesting(),
        "evaluation_agent": FakeEvaluation(),
        "proposal": run.proposal,
        "test_report": run.test_report,
        "evaluation": run.evaluation,
    }


def run_human_approval(experiment: dict[str, Any]) -> dict[str, Any]:
    """User-supplied ApprovalRecord → process_proposal. No auto-loop."""
    proposal = experiment.get("proposal")
    tests = experiment.get("test_report")
    evaluation = experiment.get("evaluation")
    deploy = RecordingDeploy()
    missing = process_change_proposal(
        testing=FakeTesting(),
        evaluation=FakeEvaluation(),
        deployment=deploy,
        proposal=proposal,
        approval=None,
    )
    if tests is None or evaluation is None or proposal is None:
        return {
            "without_approval_status": missing.status,
            "with_approval_status": "",
            "deploy_calls": 0,
            "approved_by_human": False,
        }
    approval = ApprovalRecord(
        subject=evidence_fingerprint(proposal, tests, evaluation),
        approved_by="benchmark-operator",
        approved_at=datetime.now(timezone.utc),
        note="Phase 13 human approval experiment. One safe apply only.",
    )
    applied = process_change_proposal(
        testing=FakeTesting(),
        evaluation=FakeEvaluation(),
        deployment=deploy,
        proposal=proposal,
        approval=approval,
    )
    return {
        "without_approval_status": missing.status,
        "with_approval_status": applied.status,
        "deploy_calls": len(deploy.calls),
        "approved_by_human": True,
        "written": list(getattr(applied.deployment, "written", ()) or ()),
    }


__all__ = ["run_human_approval", "run_self_improvement"]
