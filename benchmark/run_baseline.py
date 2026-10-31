"""Run the local AgentRun baseline and write benchmark/results/baseline.json."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from benchmark.experiments import run_human_approval, run_self_improvement
from benchmark.harness import run_benchmark

RESULTS_DIR = Path(__file__).resolve().parent / "results"
BASELINE_PATH = RESULTS_DIR / "baseline.json"


def _recommend(payload: dict[str, Any]) -> str:
    failures = payload.get("failures") or []
    reasons = [str(item.get("reason") or "") for item in failures if item.get("reason")]
    if "VISION" in reasons:
        return (
            "Keep vision optional. Script GUI clicks only after a verified capture; "
            "do not guess coordinates when vision is unavailable."
        )
    if "OBSERVATION" in reasons:
        return (
            "Tighten expected_observation vs actual compare on GUI and file verify "
            "before claiming COMPLETE."
        )
    if "PLANNER" in reasons:
        return (
            "Reduce unnecessary replans in the existing planner path. Do not add tools."
        )
    if "MEMORY" in reasons or "SKILL" in reasons:
        return "Keep experience/skill retrieval as suggestions only; measure hit rate again after planner work."
    if not failures:
        return (
            "Architecture gates held on this fake-LLM baseline. Next: one live-3B "
            "subset of the same tasks, still without new features."
        )
    return "Inspect the most common failure_reason in baseline.json. Do not add a new subsystem."


def _origin(payload: dict[str, Any]) -> str:
    if payload.get("llm_backend") == "fake":
        return (
            "architecture / tools / perception (FakeLLM). "
            "3B model quality not measured in this baseline."
        )
    return "mixed"


def build_report(payload: dict[str, Any]) -> dict[str, Any]:
    summary = payload.get("summary") or {}
    categories = summary.get("categories") or {}

    def _cat(name: str) -> dict[str, Any]:
        return categories.get(name) or {"score": 0.0, "n": 0, "success": 0, "partial": 0, "fail": 0}

    return {
        "title": "PRIMUS CAPABILITY REPORT",
        "overall_benchmark_score": summary.get("overall_score", 0.0),
        "reasoning_performance": _cat("reasoning"),
        "tool_use_performance": {
            "file_work": _cat("file_work"),
            "multi_step": _cat("multi_step"),
        },
        "recovery_performance": _cat("recovery"),
        "memory_performance": _cat("memory"),
        "skill_performance": _cat("skill"),
        "persistence_performance": _cat("persistence"),
        "safety_performance": _cat("safety"),
        "gui_perception_performance": _cat("gui"),
        "most_common_failure": summary.get("most_common_failure") or "none",
        "failure_origin": _origin(payload),
        "recommended_next_improvement": _recommend(payload),
        "what_primus_reliably_does": _reliable(payload),
        "what_prevents_more": _limits(payload),
    }


def _reliable(payload: dict[str, Any]) -> list[str]:
    lines = []
    for row in payload.get("tasks") or []:
        score = row.get("score") or {}
        if score.get("verdict") == "SUCCESS":
            lines.append(f"{row.get('task_id')}: {row.get('goal')}")
    return lines


def _limits(payload: dict[str, Any]) -> list[str]:
    lines = []
    for row in payload.get("failures") or []:
        lines.append(
            f"{row.get('task_id')}: {row.get('verdict')} / {row.get('reason')} — "
            + "; ".join(row.get("notes") or [])
        )
    if payload.get("llm_backend") == "fake":
        lines.append("3B reasoning quality unknown; baseline used FakeLLM.")
    return lines


def run_and_save(path: Path | None = None) -> dict[str, Any]:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    bench = run_benchmark()
    improve = run_self_improvement()
    approval = run_human_approval(improve)
    after = run_benchmark()
    before_score = float((bench.get("summary") or {}).get("overall_score") or 0.0)
    after_score = float((after.get("summary") or {}).get("overall_score") or 0.0)
    delta = round(after_score - before_score, 4)
    payload = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "llm_backend": "fake",
        **bench,
        "self_improvement": {
            "state": improve["state"],
            "stopped_at_wait": improve["stopped_at_wait"],
            "proposal_present": improve["proposal_present"],
            "deploy_calls": improve["deploy_calls"],
            "approved": False,
            "coding_objectives": improve["coding_objectives"],
            "result_excerpt": improve["result_excerpt"],
        },
        "human_approval": approval,
        "before_after": {
            "before": before_score,
            "after": after_score,
            "delta": delta,
            "improved": delta > 0,
        },
    }
    payload["report"] = build_report(payload)
    target = path or BASELINE_PATH
    target.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return payload


def main() -> int:
    payload = run_and_save()
    report = payload["report"]
    print(f"wrote {BASELINE_PATH}")
    print(f"overall={report['overall_benchmark_score']}")
    print(f"most_common_failure={report['most_common_failure']}")
    print(f"origin={report['failure_origin']}")
    print(f"before_after={payload['before_after']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
