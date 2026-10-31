"""Run live local 3B benchmark. Writes live_3b.json + live_3b_report.md."""

from __future__ import annotations

import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from benchmark.fakes import BenchmarkTools
from benchmark.gated_invoke import build_gated_invoker, probe_safety_gate
from benchmark.harness import run_benchmark
from benchmark.live_llm import load_live_llm
from benchmark.scoring import FAILURE_REASONS, aggregate
from benchmark.tasks import live_catalog

RESULTS_DIR = Path(__file__).resolve().parent / "results"
LIVE_JSON = RESULTS_DIR / "live_3b.json"
LIVE_MD = RESULTS_DIR / "live_3b_report.md"
BASELINE_JSON = RESULTS_DIR / "baseline.json"


def _failure_distribution(failures: list[dict[str, Any]], scores: list[dict[str, Any]]) -> dict[str, int]:
    counts: Counter[str] = Counter()
    for item in failures:
        reason = str(item.get("reason") or "OTHER")
        if reason in FAILURE_REASONS:
            counts[reason] += 1
    for item in scores:
        if item.get("verdict") != "SUCCESS" and item.get("failure_reason"):
            counts[str(item["failure_reason"])] += 1
    return dict(counts)


def _primary_limit(failure_dist: dict[str, int], live_score: float, arch_score: float) -> str:
    if not failure_dist:
        if live_score >= arch_score:
            return "model reasoning (not yet limiting vs FakeLLM on this subset)"
        return "model reasoning"
    top = max(failure_dist, key=failure_dist.get)
    mapping = {
        "MODEL_REASONING": "model reasoning",
        "PLANNER": "planner architecture",
        "TOOL_SELECTION": "tool selection",
        "TOOL_IMPLEMENTATION": "tools",
        "OBSERVATION": "observation verification",
        "VISION": "perception / mocked vision",
        "MEMORY": "memory retrieval",
        "SKILL": "skills",
        "SECURITY": "security gates",
        "ENVIRONMENT": "environment",
        "OTHER": "mixed / other",
    }
    return mapping.get(top, top)


def _next_change(failure_dist: dict[str, int], live_score: float, arch_score: float) -> str:
    if not failure_dist:
        return (
            "Run a wider live-3B subset including report-write and recovery edge cases; "
            "do not add tools yet."
        )
    top = max(failure_dist, key=failure_dist.get)
    if top == "MODEL_REASONING":
        return (
            "Improve planner prompt compression and JSON decision examples for the 3B model. "
            "Do not bypass GatedToolInvoker or approval."
        )
    if top == "PLANNER":
        return "Reduce replan churn in existing AgentLoop caps and default-plan fallback. No new tools."
    if top == "TOOL_SELECTION":
        return "Tighten allowed-tool listing in plan prompt; keep security authoritative."
    if top == "VISION":
        return "Keep vision optional; block GUI clicks when capture says unavailable."
    if top == "MEMORY" or top == "SKILL":
        return "Measure retrieval hit rate on live goals before changing skill storage."
    if top == "SECURITY":
        return "Investigate gate bypass attempts; do not weaken PolicySecurityAgent."
    return f"Address measured dominant failure: {top}."


def _load_baseline() -> dict[str, Any]:
    if not BASELINE_JSON.is_file():
        return {"summary": {"overall_score": 0.0}}
    return json.loads(BASELINE_JSON.read_text(encoding="utf-8"))


def _render_markdown(payload: dict[str, Any]) -> str:
    report = payload["report"]
    lines = [
        "# Live 3B capability benchmark",
        "",
        f"Generated: {payload['generated_at']}",
        f"Model: {payload.get('model_name', 'unknown')}",
        "",
        "## Overall",
        "",
        f"- Live 3B score: **{report['live_3b_score']}**",
        f"- Architecture (FakeLLM baseline): **{report['architecture_score']}**",
        f"- Delta: **{report['delta_vs_baseline']}**",
        f"- Primary limiting factor: **{report['primary_limiting_factor']}**",
        f"- Next recommended change: {report['next_recommended_change']}",
        "",
        "## Category scores (live 3B)",
        "",
        "| Category | Score | Success | Partial | Fail |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    for name, bucket in sorted((report.get("category_scores") or {}).items()):
        lines.append(
            f"| {name} | {bucket.get('score', 0)} | {bucket.get('success', 0)} | "
            f"{bucket.get('partial', 0)} | {bucket.get('fail', 0)} |"
        )
    lines.extend(
        [
            "",
            "## Aggregate metrics",
            "",
            f"- Average steps: {report.get('average_steps', 0)}",
            f"- Average replans: {report.get('average_replans', 0)}",
            f"- Total LLM calls: {report.get('total_llm_calls', 0)}",
            f"- Total tool calls: {report.get('total_tool_calls', 0)}",
            f"- LLM wall latency (s): {payload.get('llm_latency_s', 0)}",
            "",
            "## Failure-origin distribution",
            "",
        ]
    )
    dist = report.get("failure_origin_distribution") or {}
    if dist:
        for key, count in sorted(dist.items(), key=lambda item: (-item[1], item[0])):
            lines.append(f"- {key}: {count}")
    else:
        lines.append("- none recorded")
    lines.extend(["", "## Safety gate (authoritative)", ""])
    for row in payload.get("safety_gate", []):
        lines.append(
            f"- {row['case']}: status={row['status']} executed={row['executed']} "
            f"blocked={row['blocked']}"
        )
    lines.extend(["", "## Per-test results", ""])
    for row in payload.get("tasks", []):
        score = row.get("score") or {}
        lines.append(
            f"- **{row['task_id']}** ({row['category']}): {score.get('verdict')} "
            f"score={score.get('score')} steps={score.get('steps')} "
            f"llm_calls={score.get('llm_calls')} replans={score.get('replans')} "
            f"reason={score.get('failure_reason') or 'none'}"
        )
        for note in score.get("notes") or []:
            lines.append(f"  - note: {note}")
    lines.extend(["", "## Explicit limitations", ""])
    for item in report.get("limitations") or []:
        lines.append(f"- {item}")
    lines.extend(["", "## What Primus reliably does with the actual 3B", ""])
    for item in report.get("reliable_with_3b") or []:
        lines.append(f"- {item}")
    lines.extend(["", "## What is limiting Primus", ""])
    for item in report.get("limits") or []:
        lines.append(f"- {item}")
    return "\n".join(lines) + "\n"


def run_live_benchmark() -> dict[str, Any]:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    llm = load_live_llm()
    backend = BenchmarkTools()
    gated = build_gated_invoker(backend)
    safety = probe_safety_gate(gated.invoker)
    safety_score = 1.0 if all(row["blocked"] == "true" for row in safety) else 0.0

    bench = run_benchmark(
        live_catalog(),
        llm_backend="live_3b",
        llm=llm,
        live=True,
    )
    baseline = _load_baseline()
    arch_score = float((baseline.get("summary") or {}).get("overall_score") or 0.0)
    live_score = float((bench.get("summary") or {}).get("overall_score") or 0.0)
    failure_dist: dict[str, int] = {}
    for row in bench.get("tasks", []):
        score = row.get("score") or {}
        reason = str(score.get("failure_reason") or "")
        if score.get("verdict") != "SUCCESS" and reason in FAILURE_REASONS:
            failure_dist[reason] = failure_dist.get(reason, 0) + 1
    primary = _primary_limit(failure_dist, live_score, arch_score)
    next_change = _next_change(failure_dist, live_score, arch_score)

    reliable = [
        f"{row['task_id']}: {row['goal']}"
        for row in bench.get("tasks", [])
        if (row.get("score") or {}).get("verdict") == "SUCCESS"
    ]
    limits = [
        f"{row['task_id']}: {(row.get('score') or {}).get('verdict')} / "
        f"{(row.get('score') or {}).get('failure_reason') or 'none'}"
        for row in bench.get("tasks", [])
        if (row.get("score") or {}).get("verdict") != "SUCCESS"
    ]
    limits.extend(
        [
            "Subset only; persistence and full report-write not in live pass.",
            "GUI uses mocked vision; not real desktop automation.",
            "Safety agent-task scripts skipped; gate probe is authoritative.",
        ]
    )

    payload = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "llm_backend": "live_3b",
        "model_name": llm.model_name,
        "llm_latency_s": round(llm.total_latency_s, 3),
        "llm_calls_wrapper": llm.llm_calls,
        "safety_gate": safety,
        "safety_gate_score": safety_score,
        **bench,
        "comparison": {
            "architecture_score": arch_score,
            "live_3b_score": live_score,
            "delta": round(live_score - arch_score, 4),
        },
        "report": {
            "architecture_score": arch_score,
            "live_3b_score": live_score,
            "delta_vs_baseline": round(live_score - arch_score, 4),
            "category_scores": bench.get("summary", {}).get("categories", {}),
            "average_steps": bench.get("summary", {}).get("average_steps", 0),
            "average_replans": bench.get("summary", {}).get("average_replans", 0),
            "total_llm_calls": bench.get("summary", {}).get("total_llm_calls", 0),
            "total_tool_calls": bench.get("summary", {}).get("total_tool_calls", 0),
            "failure_origin_distribution": failure_dist,
            "primary_limiting_factor": primary,
            "next_recommended_change": next_change,
            "reliable_with_3b": reliable,
            "limits": limits,
            "limitations": [
                "Measured with local Llama-3.2-3B only; no cloud API.",
                "Mock tool execution + real GatedToolInvoker security.",
                "No self-improvement proposal triggered by failures.",
                "Phase 13 baseline.json unchanged.",
            ],
        },
    }
    LIVE_JSON.write_text(json.dumps(payload, indent=2, default=str) + "\n", encoding="utf-8")
    LIVE_MD.write_text(_render_markdown(payload), encoding="utf-8")
    return payload


def main() -> int:
    payload = run_live_benchmark()
    report = payload["report"]
    print("LIVE 3B BENCHMARK COMPLETE")
    print(f"Architecture score: {report['architecture_score']}")
    print(f"Live 3B score: {report['live_3b_score']}")
    print(f"Primary limiting factor: {report['primary_limiting_factor']}")
    print(f"Next recommended change: {report['next_recommended_change']}")
    print(f"wrote {LIVE_JSON}")
    print(f"wrote {LIVE_MD}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
