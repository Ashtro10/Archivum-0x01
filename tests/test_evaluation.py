"""Proposal evaluation tests (B-026, ADR-0025).

Judge ChangeProposal + TestReport. No spawn, no writes, no approval, no deploy.
"""

from __future__ import annotations

import ast
import inspect
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from astra.coding.contracts import ChangeProposal, FileChange
from astra.core.contracts import AgentRequest
from astra.core.lifecycle import MeasurableProperty
from astra.evaluation.agent import LocalEvaluationAgent
from astra.evaluation.contracts import EvaluationAgent, EvaluationReport
from astra.testing.contracts import TestOutcome, TestReport
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST


def _proposal(path: str = "tests/test_extra.py", body: str = "x = 1\n") -> ChangeProposal:
    return ChangeProposal(
        objective="eval overlay",
        changes=(FileChange(path=Path(path), new_content=body, rationale="test"),),
        improves=(MeasurableProperty.MAINTAINABILITY,),
    )


def _passing(**kwargs: object) -> TestReport:
    fields = dict(
        tests_run=2,
        tests_passed=2,
        tests_failed=0,
        tests_errors=0,
        tests_skipped=0,
    )
    fields.update(kwargs)
    return TestReport(**fields)  # type: ignore[arg-type]


class LocalEvaluationAgentTest(unittest.TestCase):
    def setUp(self) -> None:
        self.agent = LocalEvaluationAgent()
        self.proposal = _proposal()

    def test_passing_report_evaluates_ok(self) -> None:
        report = self.agent.evaluate_proposal(self.proposal, _passing())
        self.assertIsInstance(report, EvaluationReport)
        self.assertTrue(report.evaluation_passed)
        self.assertTrue(report.tests_passed)
        self.assertFalse(report.deployment_authorized)
        self.assertFalse(report.improved)
        self.assertFalse(report.incomplete)

    def test_failed_tests_fail_evaluation(self) -> None:
        tests = _passing(tests_passed=1, tests_failed=1)
        report = self.agent.evaluate_proposal(self.proposal, tests)
        self.assertFalse(report.evaluation_passed)
        self.assertFalse(report.deployment_authorized)
        self.assertIn("failed", report.reason)

    def test_test_errors_fail_evaluation(self) -> None:
        tests = _passing(tests_passed=1, tests_errors=1)
        report = self.agent.evaluate_proposal(self.proposal, tests)
        self.assertFalse(report.evaluation_passed)
        self.assertIn("errored", report.reason)

    def test_timeout_fails_evaluation(self) -> None:
        tests = _passing(timed_out=True)
        report = self.agent.evaluate_proposal(self.proposal, tests)
        self.assertFalse(report.evaluation_passed)
        self.assertTrue(report.incomplete)
        self.assertIn("timed out", report.reason)

    def test_spawn_failure_fails_evaluation(self) -> None:
        tests = _passing(spawn_failed=True)
        report = self.agent.evaluate_proposal(self.proposal, tests)
        self.assertFalse(report.evaluation_passed)
        self.assertTrue(report.incomplete)
        self.assertIn("spawn", report.reason)

    def test_incomplete_report_fails_evaluation(self) -> None:
        tests = _passing(incomplete=True)
        report = self.agent.evaluate_proposal(self.proposal, tests)
        self.assertFalse(report.evaluation_passed)
        self.assertTrue(report.incomplete)

    def test_malformed_report_fails_evaluation(self) -> None:
        report = self.agent.evaluate_proposal(self.proposal, TestReport())
        self.assertFalse(report.evaluation_passed)
        self.assertFalse(report.deployment_authorized)

    def test_zero_run_with_fake_outcomes_fails_evaluation(self) -> None:
        tests = TestReport(
            outcomes=(TestOutcome("fake.test", True),),
            tests_run=0,
            tests_passed=0,
            exit_code=0,
        )
        report = self.agent.evaluate_proposal(self.proposal, tests)
        self.assertFalse(tests.passed)
        self.assertFalse(report.evaluation_passed)
        self.assertFalse(report.deployment_authorized)

    def test_empty_proposal_fails(self) -> None:
        empty = ChangeProposal(
            objective="empty",
            changes=(),
            improves=(MeasurableProperty.MAINTAINABILITY,),
        )
        report = self.agent.evaluate_proposal(empty, _passing())
        self.assertFalse(report.evaluation_passed)
        self.assertIn("empty", report.reason)

    def test_missing_test_report_fails(self) -> None:
        report = self.agent.evaluate_proposal(self.proposal, None)  # type: ignore[arg-type]
        self.assertFalse(report.evaluation_passed)
        self.assertIn("missing", report.reason)

    def test_skipped_tests_follow_test_report(self) -> None:
        tests = _passing(tests_run=2, tests_passed=1, tests_skipped=1)
        report = self.agent.evaluate_proposal(self.proposal, tests)
        self.assertTrue(tests.passed)
        self.assertTrue(report.evaluation_passed)
        self.assertIn("skipped=1", report.notes)
        self.assertEqual((), report.measurements)

    def test_passing_does_not_create_approval(self) -> None:
        report = self.agent.evaluate_proposal(self.proposal, _passing())
        self.assertTrue(report.evaluation_passed)
        self.assertNotIn("approved", report.reason.lower())
        src = Path(__file__).resolve().parents[1] / "astra" / "evaluation" / "agent.py"
        self.assertNotIn("ApprovalRecord", src.read_text(encoding="utf-8"))

    def test_evaluate_and_baseline_are_wired(self) -> None:
        report = self.agent.evaluate("subject")
        self.assertIsInstance(report, EvaluationReport)
        self.assertFalse(report.improved)
        self.assertFalse(report.deployment_authorized)
        stored = self.agent.record_baseline("subject", EvaluationReport(subject="x"))
        self.assertFalse(stored.recorded)
        with self.assertRaises(NotImplementedError):
            EvaluationAgent.evaluate_proposal(  # type: ignore[misc]
                object(), self.proposal, _passing()
            )

    def test_proposal_unchanged(self) -> None:
        before = self.proposal
        self.agent.evaluate_proposal(self.proposal, _passing())
        self.assertIs(before, self.proposal)
        self.assertEqual(before.changes[0].new_content, "x = 1\n")

    def test_allowlist_and_schema(self) -> None:
        from astra.state import ApplicationState

        self.assertEqual(
            frozenset({"read_text", "read_project"}),
            PRODUCTION_EXECUTION_ALLOWLIST,
        )
        self.assertEqual(6, ApplicationState().schema_version)

    def test_run_reads_context_once(self) -> None:
        result = self.agent.run(
            AgentRequest(
                objective="evaluate",
                context={"proposal": self.proposal, "tests": _passing()},
            )
        )
        self.assertTrue(result.success)
        self.assertFalse(result.details["deployment_authorized"])


class EvaluationIsolationTest(unittest.TestCase):
    def test_no_subprocess_or_writes_in_agent(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "evaluation"
        forbidden = {"subprocess", "socket", "httpx", "requests", "dearpygui", "ui"}
        for py_file in root.glob("*.py"):
            if py_file.name == "store.py":
                continue
            text = py_file.read_text(encoding="utf-8")
            self.assertNotIn("write_text", text, py_file.name)
            self.assertNotIn("write_bytes", text, py_file.name)
            self.assertNotIn("os.system", text, py_file.name)
            tree = ast.parse(text)
            names: list[str] = []
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    names.extend(alias.name.split(".")[0] for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names.append(node.module.split(".")[0])
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
                    self.assertNotIn(node.func.id, {"eval", "exec", "open"})
            for name in forbidden:
                self.assertNotIn(name, names, py_file.name)
        store_src = (root / "store.py").read_text(encoding="utf-8")
        self.assertIn("write_bytes", store_src)
        self.assertNotIn("subprocess", store_src)
        self.assertNotIn("ProcessRunner", store_src)
        self.assertNotIn("import git", store_src)

    def test_does_not_import_deployment_or_agents(self) -> None:
        text = (
            Path(__file__).parent.parent / "astra" / "evaluation" / "agent.py"
        ).read_text(encoding="utf-8")
        self.assertNotIn("DeploymentAgent", text)
        self.assertNotIn("astra.deployment", text)
        self.assertNotIn("astra.testing.agent", text)
        self.assertNotIn("astra.coding.agent", text)
        self.assertNotIn("ProcessRunner", text)
        self.assertNotIn("ToolInvoker", text)

    def test_does_not_call_testing_or_coding(self) -> None:
        agent = LocalEvaluationAgent()
        with patch("astra.testing.agent.LocalTestingAgent.test_proposal") as test_fn:
            with patch("astra.coding.agent.LocalCodingAgent.propose_change") as code_fn:
                agent.evaluate_proposal(_proposal(), _passing())
        test_fn.assert_not_called()
        code_fn.assert_not_called()

    def test_milestone_check_not_hard_coded(self) -> None:
        from astra.presentation import progress

        src = inspect.getsource(progress._check_proposal_evaluation)
        self.assertIn("LocalEvaluationAgent", src)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_proposal_evaluation())

    def test_adr_0024_untouched_marker(self) -> None:
        root = Path(__file__).parent.parent / "docs" / "adr"
        text = (root / "ADR-0024-honest-proposal-testing.md").read_text(encoding="utf-8")
        self.assertIn("implemented in B-025", text)
        self.assertIn("No `run_suite`, Evaluation, Apply, Deployment", text)
        self.assertTrue((root / "ADR-0025-evaluation-of-tested-proposals.md").is_file())


class ControllerEvaluationAgentTest(unittest.TestCase):
    def test_evaluation_agent_is_controller_owned(self) -> None:
        from astra.configuration.settings import (
            AstraSettings,
            KnowledgeSettings,
            LoggingSettings,
            MemorySettings,
            PlanningSettings,
            TestingSettings,
        )
        from astra.core.astra_controller import AstraController
        from astra.core.errors import ControllerNotStartedError
        from astra.state import CapabilityStatus

        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        data = root / "data"
        scratch = root / "scratch"
        data.mkdir()
        settings = AstraSettings(
            memory=MemorySettings(
                database_path=data / "mem.db",
                vector_index_path=data / "vectors.faiss",
            ),
            knowledge=KnowledgeSettings(database_path=data / "knowledge.db"),
            planning=PlanningSettings(database_path=data / "planning.db"),
            logging=LoggingSettings(directory=data / "logs"),
            testing=TestingSettings(scratch_directory=scratch),
        )
        ctrl = AstraController(settings)
        with self.assertRaises(ControllerNotStartedError):
            _ = ctrl.evaluation
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        self.assertIn("evaluation", ctrl.agent_names)
        self.assertIsInstance(ctrl.evaluation, LocalEvaluationAgent)
        self.assertIsNot(ctrl.evaluation, ctrl.testing)
        self.assertIsNot(ctrl.evaluation, ctrl.coding)
        compared = ctrl.evaluation.evaluate("x")
        self.assertFalse(compared.improved)
        self.assertFalse(compared.deployment_authorized)
        report = ctrl.evaluation.evaluate_proposal(_proposal(), _passing())
        self.assertTrue(report.evaluation_passed)
        self.assertFalse(report.deployment_authorized)
        self.assertFalse(report.improved)
        state = ctrl.get_state()
        self.assertEqual(6, state.schema_version)
        evaluation = next(m for m in state.modules if m.module == "astra.evaluation")
        cap = next(c for c in evaluation.capabilities if c.name == "evaluate_proposal")
        self.assertEqual(CapabilityStatus.OPERATIONAL, cap.status)
        baseline = next(c for c in evaluation.capabilities if c.name == "record_baseline")
        self.assertEqual(CapabilityStatus.OPERATIONAL, baseline.status)
        self.assertNotIn("baselines", ctrl.agent_names)
        from astra.evaluation.store import LocalBaselineStore

        self.assertIsInstance(ctrl.baselines, LocalBaselineStore)
        self.assertIs(False, state.coding.applied)
        ctrl.shutdown()
        with self.assertRaises(ControllerNotStartedError):
            _ = ctrl.evaluation


if __name__ == "__main__":
    unittest.main()
