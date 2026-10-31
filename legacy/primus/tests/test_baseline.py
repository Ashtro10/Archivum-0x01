"""Baseline + comparative evaluation tests (B-029, ADR-0028)."""

from __future__ import annotations

import ast
import inspect
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from astra.coding.contracts import ChangeProposal, FileChange
from astra.core.lifecycle import MeasurableProperty
from astra.evaluation.agent import LocalEvaluationAgent
from astra.evaluation.contracts import (
    BASELINE_CORRUPTED,
    BASELINE_MISMATCH,
    BASELINE_MISSING,
    COMPARISON_IMPROVED,
    COMPARISON_INCOMPLETE,
    COMPARISON_REGRESSED,
    COMPARISON_UNCHANGED,
    EvaluationAgent,
    EvaluationReport,
    MetricSample,
    WRITE_DUPLICATE,
    WRITE_INCOMPLETE,
    WRITE_RECORDED,
)
from astra.evaluation.store import LocalBaselineStore, baseline_id_for
from astra.security.agent import PolicySecurityAgent
from astra.testing.contracts import TestReport
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST


def _proposal(objective: str = "cmp fixture") -> ChangeProposal:
    return ChangeProposal(
        objective=objective,
        changes=(
            FileChange(path=Path("tests") / "test_extra.py", new_content="x = 1\n", rationale="t"),
        ),
        improves=(MeasurableProperty.MAINTAINABILITY,),
    )


def _passing() -> TestReport:
    return TestReport(tests_run=1, tests_passed=1)


def _sample(
    metric: str,
    value: float,
    *,
    higher: bool = True,
    unit: str = "u",
    prop: MeasurableProperty = MeasurableProperty.ACCURACY,
) -> MetricSample:
    return MetricSample(
        property=prop,
        metric=metric,
        value=value,
        unit=unit,
        higher_is_better=higher,
    )


class BaselineComparisonTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        root = Path(self._tmp.name)
        self.project = root / "project"
        self.data = root / "data"
        self.scratch = root / "scratch"
        self.project.mkdir()
        self.data.mkdir()
        self.scratch.mkdir()
        (self.project / "keep.py").write_bytes(b"# keep\n")
        self.security = PolicySecurityAgent(
            write_roots=(self.data,),
            project_roots=(self.project,),
            scratch_root=self.scratch,
        )
        self.store = LocalBaselineStore(
            security=self.security, store_root=self.scratch / "baselines"
        )
        self.agent = LocalEvaluationAgent(baselines=self.store)
        self.proposal = _proposal()

    def _record(self, subject: str, *samples: MetricSample, implementation: str = "fix"):
        return self.agent.record_baseline(
            subject,
            EvaluationReport(
                subject=subject,
                samples=samples,
                implementation=implementation,
            ),
        )

    def test_valid_baseline_creation(self) -> None:
        result = self._record("cmp fixture", _sample("score", 1.0))
        self.assertEqual(WRITE_RECORDED, result.status)
        self.assertTrue(result.recorded)
        record = self.store.get(result.baseline_id)
        self.assertIsNotNone(record)
        assert record is not None
        self.assertEqual("cmp fixture", record.subject)
        self.assertEqual("fix", record.implementation)
        self.assertEqual(1, len(record.metrics))
        self.assertEqual(1.0, record.metrics[0].value)
        self.assertEqual("u", record.metrics[0].unit)

    def test_immutable_and_duplicate_rejected(self) -> None:
        first = self._record("cmp fixture", _sample("score", 1.0))
        blob = (
            self.scratch / "baselines" / first.baseline_id / "record.json"
        ).read_bytes()
        second = self._record("cmp fixture", _sample("score", 9.0))
        self.assertEqual(WRITE_DUPLICATE, second.status)
        self.assertFalse(second.recorded)
        self.assertEqual(
            blob,
            (self.scratch / "baselines" / first.baseline_id / "record.json").read_bytes(),
        )
        record = self.store.get(first.baseline_id)
        assert record is not None
        self.assertEqual(1.0, record.metrics[0].value)

    def test_missing_baseline(self) -> None:
        report = self.agent.evaluate_proposal(
            _proposal("unknown subject"),
            _passing(),
            candidate=(_sample("score", 2.0),),
        )
        self.assertEqual(BASELINE_MISSING, report.baseline_status)
        self.assertEqual(COMPARISON_INCOMPLETE, report.comparison_status)
        self.assertFalse(report.improved)
        self.assertTrue(report.incomplete)
        self.assertFalse(report.evaluation_passed)

    def test_malformed_baseline_rejected(self) -> None:
        bad = self.agent.record_baseline(
            "cmp fixture",
            EvaluationReport(
                subject="cmp fixture",
                samples=(_sample("score", float("nan")),),
            ),
        )
        self.assertFalse(bad.recorded)
        self.assertIsNone(self.store.get_for_subject("cmp fixture"))

    def test_corrupted_baseline_fails_closed(self) -> None:
        recorded = self._record("cmp fixture", _sample("score", 1.0))
        path = self.scratch / "baselines" / recorded.baseline_id / "record.json"
        raw = json.loads(path.read_text(encoding="utf-8"))
        raw["metrics"][0]["value"] = 99.0
        path.write_text(json.dumps(raw), encoding="utf-8")
        report = self.agent.evaluate_proposal(
            self.proposal,
            _passing(),
            baseline_id=recorded.baseline_id,
            candidate=(_sample("score", 2.0),),
        )
        self.assertEqual(BASELINE_CORRUPTED, report.baseline_status)
        self.assertFalse(report.improved)
        self.assertEqual((), report.measurements)
        self.assertFalse(report.evaluation_passed)
        self.assertEqual(b"# keep\n", (self.project / "keep.py").read_bytes())

    def test_path_traversal_fails_closed(self) -> None:
        report = self.agent.evaluate_proposal(
            self.proposal,
            _passing(),
            baseline_id="../escape",
            candidate=(_sample("score", 2.0),),
        )
        self.assertFalse(report.improved)
        self.assertNotEqual(COMPARISON_IMPROVED, report.comparison_status)
        self.assertFalse((self.scratch.parent / "escape").exists())

    def test_higher_is_better_improvement(self) -> None:
        recorded = self._record("cmp fixture", _sample("score", 1.0, higher=True))
        report = self.agent.evaluate_proposal(
            self.proposal,
            _passing(),
            baseline_id=recorded.baseline_id,
            candidate=(_sample("score", 2.0, higher=True),),
        )
        self.assertEqual(COMPARISON_IMPROVED, report.comparison_status)
        self.assertTrue(report.improved)
        self.assertTrue(report.evaluation_passed)
        self.assertFalse(report.deployment_authorized)

    def test_higher_is_better_regression(self) -> None:
        recorded = self._record("cmp fixture", _sample("score", 2.0, higher=True))
        report = self.agent.evaluate_proposal(
            self.proposal,
            _passing(),
            baseline_id=recorded.baseline_id,
            candidate=(_sample("score", 1.0, higher=True),),
        )
        self.assertEqual(COMPARISON_REGRESSED, report.comparison_status)
        self.assertFalse(report.improved)
        self.assertFalse(report.evaluation_passed)
        self.assertTrue(report.tests_passed)
        self.assertFalse(report.deployment_authorized)

    def test_lower_is_better_improvement(self) -> None:
        recorded = self._record(
            "cmp fixture",
            _sample("latency_ms", 10.0, higher=False, prop=MeasurableProperty.PERFORMANCE),
        )
        report = self.agent.evaluate_proposal(
            self.proposal,
            _passing(),
            baseline_id=recorded.baseline_id,
            candidate=(
                _sample(
                    "latency_ms",
                    8.0,
                    higher=False,
                    prop=MeasurableProperty.PERFORMANCE,
                ),
            ),
        )
        self.assertEqual(COMPARISON_IMPROVED, report.comparison_status)
        self.assertTrue(report.improved)
        self.assertFalse(report.deployment_authorized)

    def test_lower_is_better_regression(self) -> None:
        recorded = self._record(
            "cmp fixture",
            _sample("latency_ms", 8.0, higher=False, prop=MeasurableProperty.PERFORMANCE),
        )
        report = self.agent.evaluate_proposal(
            self.proposal,
            _passing(),
            baseline_id=recorded.baseline_id,
            candidate=(
                _sample(
                    "latency_ms",
                    12.0,
                    higher=False,
                    prop=MeasurableProperty.PERFORMANCE,
                ),
            ),
        )
        self.assertEqual(COMPARISON_REGRESSED, report.comparison_status)
        self.assertFalse(report.improved)
        self.assertFalse(report.evaluation_passed)

    def test_unchanged_metric(self) -> None:
        recorded = self._record("cmp fixture", _sample("score", 1.0))
        report = self.agent.evaluate_proposal(
            self.proposal,
            _passing(),
            baseline_id=recorded.baseline_id,
            candidate=(_sample("score", 1.0),),
        )
        self.assertEqual(COMPARISON_UNCHANGED, report.comparison_status)
        self.assertFalse(report.improved)
        self.assertTrue(report.evaluation_passed)
        self.assertFalse(report.deployment_authorized)

    def test_unavailable_metric(self) -> None:
        recorded = self._record("cmp fixture", _sample("score", 1.0))
        report = self.agent.evaluate_proposal(
            self.proposal,
            _passing(),
            baseline_id=recorded.baseline_id,
            candidate=(_sample("other", 2.0),),
        )
        self.assertEqual(COMPARISON_INCOMPLETE, report.comparison_status)
        self.assertTrue(report.unavailable_metrics)
        self.assertFalse(report.improved)
        self.assertTrue(report.incomplete)

    def test_missing_candidate_measurement(self) -> None:
        recorded = self._record("cmp fixture", _sample("score", 1.0))
        report = self.agent.evaluate_proposal(
            self.proposal,
            _passing(),
            baseline_id=recorded.baseline_id,
        )
        self.assertFalse(report.improved)
        self.assertTrue(report.incomplete)
        self.assertEqual(COMPARISON_INCOMPLETE, report.comparison_status)

    def test_incomplete_comparison(self) -> None:
        recorded = self._record(
            "cmp fixture",
            _sample("score", 1.0),
            _sample("other", 3.0),
        )
        report = self.agent.evaluate_proposal(
            self.proposal,
            _passing(),
            baseline_id=recorded.baseline_id,
            candidate=(_sample("score", 2.0),),
        )
        self.assertEqual(COMPARISON_INCOMPLETE, report.comparison_status)
        self.assertFalse(report.evaluation_passed)
        self.assertTrue(report.incomplete)

    def test_tests_pass_without_measurements_not_improved(self) -> None:
        report = self.agent.evaluate_proposal(self.proposal, _passing())
        self.assertTrue(report.tests_passed)
        self.assertTrue(report.evaluation_passed)
        self.assertFalse(report.improved)
        self.assertEqual((), report.measurements)
        self.assertFalse(report.deployment_authorized)

    def test_wrong_baseline_not_substituted(self) -> None:
        other = self._record("other subject", _sample("score", 1.0))
        report = self.agent.evaluate_proposal(
            self.proposal,
            _passing(),
            baseline_id=other.baseline_id,
            candidate=(_sample("score", 9.0),),
        )
        self.assertEqual(BASELINE_MISMATCH, report.baseline_status)
        self.assertFalse(report.improved)
        self.assertEqual((), report.measurements)
        self.assertFalse(report.evaluation_passed)

    def test_evaluate_compares_without_tests(self) -> None:
        self._record("solo", _sample("score", 1.0))
        report = self.agent.evaluate("solo", candidate=(_sample("score", 2.0),))
        self.assertTrue(report.improved)
        self.assertFalse(report.tests_passed)
        self.assertFalse(report.evaluation_passed)
        self.assertFalse(report.deployment_authorized)

    def test_incomplete_baseline_has_no_improvement(self) -> None:
        result = self.agent.record_baseline(
            "empty", EvaluationReport(subject="empty")
        )
        self.assertEqual(WRITE_INCOMPLETE, result.status)
        report = self.agent.evaluate("empty", candidate=(_sample("score", 2.0),))
        self.assertFalse(report.improved)

    def test_no_live_project_writes(self) -> None:
        before = (self.project / "keep.py").read_bytes()
        recorded = self._record("cmp fixture", _sample("score", 1.0))
        self.agent.evaluate_proposal(
            self.proposal,
            _passing(),
            baseline_id=recorded.baseline_id,
            candidate=(_sample("score", 2.0),),
        )
        self.assertEqual(before, (self.project / "keep.py").read_bytes())
        self.assertFalse((self.project / "baselines").exists())

    def test_no_subprocess_in_evaluation(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "evaluation"
        for py_file in root.glob("*.py"):
            text = py_file.read_text(encoding="utf-8")
            self.assertNotIn("ProcessRunner", text, py_file.name)
            self.assertNotIn("import git", text, py_file.name)
            tree = ast.parse(text)
            names: list[str] = []
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    names.extend(alias.name.split(".")[0] for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names.append(node.module.split(".")[0])
            self.assertNotIn("subprocess", names, py_file.name)

    def test_allowlist_and_schema(self) -> None:
        from astra.state import ApplicationState

        self.assertEqual(
            frozenset({"read_text", "read_project"}),
            PRODUCTION_EXECUTION_ALLOWLIST,
        )
        self.assertEqual(6, ApplicationState().schema_version)

    def test_does_not_call_deploy_or_tests(self) -> None:
        recorded = self._record("cmp fixture", _sample("score", 1.0))
        with patch("astra.testing.agent.LocalTestingAgent.test_proposal") as test_fn:
            with patch("astra.deployment.agent.LocalDeploymentAgent.deploy") as deploy_fn:
                with patch("astra.coding.agent.LocalCodingAgent.propose_change") as code_fn:
                    self.agent.evaluate_proposal(
                        self.proposal,
                        _passing(),
                        baseline_id=recorded.baseline_id,
                        candidate=(_sample("score", 2.0),),
                    )
        test_fn.assert_not_called()
        deploy_fn.assert_not_called()
        code_fn.assert_not_called()

    def test_abc_still_raises(self) -> None:
        with self.assertRaises(NotImplementedError):
            EvaluationAgent.evaluate(object(), "x")  # type: ignore[misc]
        with self.assertRaises(NotImplementedError):
            EvaluationAgent.record_baseline(  # type: ignore[misc]
                object(), "x", EvaluationReport(subject="x")
            )

    def test_milestone_check_not_hard_coded(self) -> None:
        from astra.presentation import progress

        src = inspect.getsource(progress._check_baseline_evaluation)
        self.assertIn("LocalBaselineStore", src)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_baseline_evaluation())

    def test_prior_adrs_untouched(self) -> None:
        root = Path(__file__).parent.parent / "docs" / "adr"
        text = (root / "ADR-0027-version-manager-and-honest-rollback.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("implemented in B-028", text)
        self.assertTrue(
            (root / "ADR-0028-baseline-and-comparative-evaluation.md").is_file()
        )

    def test_baseline_id_is_stable(self) -> None:
        self.assertEqual(baseline_id_for("alpha"), baseline_id_for("alpha"))
        self.assertNotEqual(baseline_id_for("alpha"), baseline_id_for("beta"))


if __name__ == "__main__":
    unittest.main()
