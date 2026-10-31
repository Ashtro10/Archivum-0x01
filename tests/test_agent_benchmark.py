"""Phase 13 harness. Fake LLM. No GGUF. No new tools."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from astra.core.pipeline import PIPELINE_APPROVAL_MISSING, PIPELINE_DEPLOYED
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST
from astra.tools.contracts import ToolInvocationRequest
from benchmark.experiments import run_human_approval, run_self_improvement
from benchmark.fakes import BenchmarkTools
from benchmark.gated_invoke import build_gated_invoker, probe_safety_gate
from benchmark.harness import execute_spec, run_benchmark, run_task
from benchmark.scoring import FAILURE_REASONS, score_task
from benchmark.tasks import catalog, live_catalog
from tests.test_agent_loop import FakeLLM, _PLAN


class HarnessRecordTest(unittest.TestCase):
    def test_trace_has_required_fields_without_cot(self) -> None:
        spec = next(item for item in catalog() if item.task_id == "B1_find_and_inspect")
        bundle = execute_spec(spec)
        trace = bundle["trace"]
        for key in (
            "goal",
            "plan",
            "actions",
            "observations",
            "replans",
            "failures",
            "tool_calls",
            "completion",
            "time",
            "approvals",
            "final_result",
        ):
            self.assertIn(key, trace)
        blob = json.dumps(trace)
        self.assertNotIn("chain-of-thought", blob.lower())
        self.assertNotIn("List at most 4 tasks", blob)

    def test_safety_unregistered_waits(self) -> None:
        spec = next(item for item in catalog() if item.task_id == "H4_shell")
        scored = run_task(spec)["score"]
        self.assertEqual("WAIT_FOR_USER", execute_spec(spec)["state"])
        self.assertEqual(0, scored.security_violations)
        self.assertIn(scored.verdict, {"SUCCESS", "PARTIAL"})

    def test_persistence_pause_keeps_goal(self) -> None:
        spec = next(item for item in catalog() if item.task_id == "G1_pause_keeps_goal")
        bundle = execute_spec(spec)
        self.assertTrue(bundle["goal_active_after_pause"])
        self.assertEqual("PAUSED", bundle["paused_state"])

    def test_self_improve_stops_without_deploy(self) -> None:
        experiment = run_self_improvement()
        self.assertTrue(experiment["stopped_at_wait"])
        self.assertTrue(experiment["proposal_present"])
        self.assertEqual(0, experiment["deploy_calls"])
        self.assertFalse(experiment["approved"])

    def test_approval_required_then_process_proposal(self) -> None:
        experiment = run_self_improvement()
        outcome = run_human_approval(experiment)
        self.assertEqual(PIPELINE_APPROVAL_MISSING, outcome["without_approval_status"])
        self.assertEqual(PIPELINE_DEPLOYED, outcome["with_approval_status"])
        self.assertGreaterEqual(outcome["deploy_calls"], 1)

    def test_production_allowlist_unchanged(self) -> None:
        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))

    def test_failure_reasons_are_closed(self) -> None:
        self.assertIn("MODEL_REASONING", FAILURE_REASONS)
        self.assertIn("PLANNER", FAILURE_REASONS)
        spec = next(item for item in catalog() if item.task_id == "B1_find_and_inspect")
        bundle = execute_spec(spec)
        scored = score_task(spec, bundle)
        self.assertIn(scored.verdict, {"SUCCESS", "PARTIAL", "FAIL"})
        self.assertIn(scored.score, {1.0, 0.5, 0.0})

    def test_baseline_writer_round_trip(self) -> None:
        from benchmark.run_baseline import run_and_save

        tmp = Path(tempfile.mkdtemp()) / "baseline.json"
        payload = run_and_save(tmp)
        self.assertTrue(tmp.is_file())
        loaded = json.loads(tmp.read_text(encoding="utf-8"))
        self.assertEqual("fake", loaded["llm_backend"])
        self.assertIn("report", loaded)
        self.assertIn("self_improvement", loaded)
        self.assertFalse(loaded["self_improvement"]["approved"])
        self.assertIn("before_after", loaded)
        self.assertGreaterEqual(payload["task_count"], 9)
        self.assertIn("PRIMUS CAPABILITY REPORT", loaded["report"]["title"])

    def test_live_catalog_is_controlled_subset(self) -> None:
        live = live_catalog()
        ids = {item.task_id for item in live}
        self.assertEqual(9, len(live))
        self.assertNotIn("B2_report_and_verify", ids)
        self.assertNotIn("G1_pause_keeps_goal", ids)
        self.assertIn("I2_gui_no_vision", ids)

    def test_safety_gate_blocks_phase13_cases(self) -> None:
        gated = build_gated_invoker(BenchmarkTools())
        rows = probe_safety_gate(gated.invoker)
        self.assertEqual(5, len(rows))
        self.assertTrue(all(row["blocked"] == "true" for row in rows))
        self.assertTrue(all(row["executed"] == "false" for row in rows))

    def test_gated_read_project_allowed(self) -> None:
        gated = build_gated_invoker(BenchmarkTools())
        record = gated.invoker.request(
            ToolInvocationRequest(
                caller="bench",
                tool_name="read_project",
                arguments={"path": "README.md"},
            )
        )
        status = str(getattr(getattr(record, "status", None), "value", record.status) or "")
        self.assertNotEqual("denied", status)
        self.assertNotIn("no read allowlist", str(getattr(record, "reason", "") or "").lower())

    def test_gated_path_records_calls_without_fake_backend_leak(self) -> None:
        class ScriptLLM:
            def generate(self, prompt: str, options: object = None) -> str:
                return _PLAN

            def is_available(self) -> bool:
                return True

        spec = next(item for item in live_catalog() if item.task_id == "A1_explain_problem")
        bundle = execute_spec(spec, llm=ScriptLLM())
        self.assertIn("read_project", bundle["tool_names"])
        scored = score_task(spec, bundle, live=True)
        self.assertIn(scored.verdict, {"SUCCESS", "PARTIAL", "FAIL"})


if __name__ == "__main__":
    unittest.main()
