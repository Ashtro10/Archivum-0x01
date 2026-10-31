"""End-to-end controlled change pipeline tests (B-031, ADR-0030).

Controller composes existing agents. No auto-approve. No fake evidence.
"""

from __future__ import annotations

import ast
import inspect
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

from astra.coding.contracts import ChangeProposal, FileChange
from astra.core.lifecycle import MeasurableProperty
from astra.core.pipeline import (
    PIPELINE_APPROVAL_INVALID,
    PIPELINE_APPROVAL_MISSING,
    PIPELINE_DEPLOYED,
    PIPELINE_DEPLOYMENT_FAILED,
    PIPELINE_EVALUATION_FAILED,
    PIPELINE_INVALID_PROPOSAL,
    PIPELINE_TESTING_FAILED,
    PIPELINE_TESTING_INCOMPLETE,
    PipelineResult,
    process_change_proposal,
)
from astra.deployment.agent import LocalDeploymentAgent
from astra.deployment.contracts import (
    ApprovalRecord,
    DeploymentResult,
    STATUS_DEPLOYMENT_ERROR,
    STATUS_ROLLED_BACK,
)
from astra.deployment.fingerprint import evidence_fingerprint, rollback_fingerprint
from astra.deployment.store import LocalVersionManager
from astra.evaluation.agent import LocalEvaluationAgent
from astra.evaluation.contracts import EvaluationReport
from astra.security.agent import PolicySecurityAgent
from astra.security.contracts import Verdict
from astra.testing.agent import LocalTestingAgent
from astra.testing.contracts import TestReport
from astra.testing.runner import ProcessRunner
from astra.testing.subset import CopiedSubsetWorkspace
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

_PASSING = (
    "import unittest\n"
    "class OkTest(unittest.TestCase):\n"
    "    def test_ok(self) -> None:\n"
    "        self.assertTrue(True)\n"
)
_FAILING = (
    "import unittest\n"
    "class FailTest(unittest.TestCase):\n"
    "    def test_fail(self) -> None:\n"
    "        self.assertEqual(1, 2)\n"
)
_SLEEPING = (
    "import time\n"
    "import unittest\n"
    "class SleepTest(unittest.TestCase):\n"
    "    def test_sleep(self) -> None:\n"
    "        time.sleep(30)\n"
)


def _proposal(*pairs: tuple[str, str]) -> ChangeProposal:
    changes = tuple(
        FileChange(path=Path(path), new_content=body, rationale="test")
        for path, body in pairs
    )
    return ChangeProposal(
        objective="pipeline overlay",
        changes=changes,
        improves=(MeasurableProperty.MAINTAINABILITY,),
    )


def _write_mini(project: Path) -> None:
    (project / "astra").mkdir()
    (project / "astra" / "__init__.py").write_bytes(b"# astra-marker\n")
    (project / "astra" / "hello.py").write_bytes(b"# before\n")
    (project / "tests").mkdir()
    (project / "tests" / "__init__.py").write_bytes(b"")
    (project / "tests" / "test_ok.py").write_text(_PASSING, encoding="utf-8")
    (project / "ui").mkdir()
    (project / "ui" / "app.py").write_bytes(b"# ui\n")
    (project / "CONSTITUTION.md").write_bytes(b"# constitution-marker\n")


class ChangePipelineTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.project = Path(self._tmp.name) / "project"
        self.scratch = Path(self._tmp.name) / "scratch"
        self.data = Path(self._tmp.name) / "data"
        self.project.mkdir()
        self.scratch.mkdir()
        self.data.mkdir()
        _write_mini(self.project)
        self.security = PolicySecurityAgent(
            write_roots=(self.data,),
            scratch_root=self.scratch,
            project_roots=(self.project,),
            process_executables=(Path(sys.executable),),
        )
        self.workspace = CopiedSubsetWorkspace(
            security=self.security,
            scratch_root=self.scratch,
            project_root=self.project,
        )
        self.runner = ProcessRunner(security=self.security, subset=self.workspace)
        self.testing = LocalTestingAgent(
            subset=self.workspace, runner=self.runner, timeout_seconds=15.0
        )
        self.evaluation = LocalEvaluationAgent()
        self.versions = LocalVersionManager(
            security=self.security,
            project_root=self.project,
            store_root=self.scratch / "versions",
        )
        self.deployment = LocalDeploymentAgent(
            security=self.security,
            project_root=self.project,
            versions=self.versions,
        )
        self.change = _proposal(("astra/hello.py", "# after\n"))

    def _run(self, proposal: ChangeProposal, approval: ApprovalRecord | None = None) -> PipelineResult:
        return process_change_proposal(
            testing=self.testing,
            evaluation=self.evaluation,
            deployment=self.deployment,
            proposal=proposal,
            approval=approval,
        )

    def _hello(self) -> bytes:
        return (self.project / "astra" / "hello.py").read_bytes()

    def _approve(self, result: PipelineResult, proposal: ChangeProposal) -> ApprovalRecord:
        assert result.tests is not None
        assert result.evaluation is not None
        return ApprovalRecord(
            subject=evidence_fingerprint(proposal, result.tests, result.evaluation),
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )

    def test_invalid_proposal_skips_later_stages(self) -> None:
        with (
            patch.object(self.testing, "test_proposal") as test,
            patch.object(self.evaluation, "evaluate_proposal") as ev,
            patch.object(self.deployment, "deploy") as dep,
        ):
            result = self._run(ChangeProposal(objective="empty"))
        self.assertEqual(PIPELINE_INVALID_PROPOSAL, result.status)
        self.assertFalse(result.deployed)
        test.assert_not_called()
        ev.assert_not_called()
        dep.assert_not_called()
        self.assertEqual(b"# before\n", self._hello())

    def test_no_approval_does_not_deploy(self) -> None:
        before = self._hello()
        result = self._run(self.change)
        self.assertEqual(PIPELINE_APPROVAL_MISSING, result.status)
        self.assertFalse(result.deployed)
        self.assertIsInstance(result.tests, TestReport)
        self.assertTrue(result.tests.passed)
        self.assertIsInstance(result.evaluation, EvaluationReport)
        self.assertTrue(result.evaluation.evaluation_passed)
        self.assertFalse(result.evaluation.deployment_authorized)
        self.assertIsNone(result.deployment)
        self.assertEqual(before, self._hello())

    def test_invalid_approval_does_not_deploy(self) -> None:
        before = self._hello()
        preview = self._run(self.change)
        bad = ApprovalRecord(
            subject="not-the-fingerprint",
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )
        result = self._run(self.change, bad)
        self.assertEqual(PIPELINE_APPROVAL_INVALID, result.status)
        self.assertFalse(result.deployed)
        self.assertEqual(before, self._hello())
        self.assertTrue(preview.evaluation is not None)
        self.assertFalse(preview.evaluation.deployment_authorized)

    def test_matching_approval_deploys_and_records_version(self) -> None:
        preview = self._run(self.change)
        approval = self._approve(preview, self.change)
        result = self._run(self.change, approval)
        self.assertEqual(PIPELINE_DEPLOYED, result.status)
        self.assertTrue(result.deployed)
        self.assertEqual(b"# after\n", self._hello())
        self.assertIsNotNone(result.deployment)
        assert result.deployment is not None
        self.assertTrue(result.deployment.version)
        recorded = self.versions.get(result.deployment.version)
        self.assertIsNotNone(recorded)

    def test_failed_tests_skip_evaluation_and_deploy(self) -> None:
        failing = _proposal(("tests/test_fail.py", _FAILING))
        with (
            patch.object(self.evaluation, "evaluate_proposal") as ev,
            patch.object(self.deployment, "deploy") as dep,
        ):
            result = self._run(failing)
        self.assertEqual(PIPELINE_TESTING_FAILED, result.status)
        self.assertFalse(result.deployed)
        self.assertIsInstance(result.tests, TestReport)
        self.assertFalse(result.tests.passed)
        self.assertIsNone(result.evaluation)
        ev.assert_not_called()
        dep.assert_not_called()
        self.assertEqual(b"# before\n", self._hello())

    def test_incomplete_tests_skip_later_stages(self) -> None:
        sleeper = LocalTestingAgent(
            subset=self.workspace, runner=self.runner, timeout_seconds=1.0
        )
        with (
            patch.object(self.evaluation, "evaluate_proposal") as ev,
            patch.object(self.deployment, "deploy") as dep,
        ):
            result = process_change_proposal(
                testing=sleeper,
                evaluation=self.evaluation,
                deployment=self.deployment,
                proposal=_proposal(("tests/test_sleep.py", _SLEEPING)),
            )
        self.assertEqual(PIPELINE_TESTING_INCOMPLETE, result.status)
        self.assertTrue(result.tests is not None and result.tests.timed_out)
        ev.assert_not_called()
        dep.assert_not_called()

    def test_failed_evaluation_skips_deploy(self) -> None:
        failed = EvaluationReport(
            subject="pipeline overlay",
            tests_passed=True,
            evaluation_passed=False,
            deployment_authorized=False,
            reason="forced evaluation failure",
        )
        with (
            patch.object(self.evaluation, "evaluate_proposal", return_value=failed) as ev,
            patch.object(self.deployment, "deploy") as dep,
        ):
            result = self._run(self.change)
        self.assertEqual(PIPELINE_EVALUATION_FAILED, result.status)
        self.assertFalse(result.deployed)
        ev.assert_called()
        dep.assert_not_called()
        self.assertEqual(b"# before\n", self._hello())

    def test_changed_proposal_invalidates_approval(self) -> None:
        preview = self._run(self.change)
        approval = self._approve(preview, self.change)
        other = _proposal(("astra/hello.py", "# other\n"))
        result = self._run(other, approval)
        self.assertEqual(PIPELINE_APPROVAL_INVALID, result.status)
        self.assertEqual(b"# before\n", self._hello())

    def test_changed_test_report_invalidates_approval(self) -> None:
        preview = self._run(self.change)
        approval = self._approve(preview, self.change)
        mutated = TestReport(
            tests_run=99,
            tests_passed=99,
            tests_failed=0,
            tests_errors=0,
        )
        with patch.object(self.testing, "test_proposal", return_value=mutated):
            result = self._run(self.change, approval)
        self.assertEqual(PIPELINE_APPROVAL_INVALID, result.status)
        self.assertEqual(b"# before\n", self._hello())

    def test_changed_evaluation_report_invalidates_approval(self) -> None:
        preview = self._run(self.change)
        approval = self._approve(preview, self.change)
        mutated = EvaluationReport(
            subject="pipeline overlay",
            tests_passed=False,
            evaluation_passed=True,
            deployment_authorized=False,
        )
        with patch.object(self.evaluation, "evaluate_proposal", return_value=mutated):
            result = self._run(self.change, approval)
        self.assertEqual(PIPELINE_APPROVAL_INVALID, result.status)
        self.assertEqual(b"# before\n", self._hello())

    def test_deployment_failure_is_not_deployed(self) -> None:
        preview = self._run(self.change)
        approval = self._approve(preview, self.change)
        failed = DeploymentResult(status=STATUS_DEPLOYMENT_ERROR, reason="disk full")
        with patch.object(self.deployment, "deploy", return_value=failed):
            result = self._run(self.change, approval)
        self.assertEqual(PIPELINE_DEPLOYMENT_FAILED, result.status)
        self.assertFalse(result.deployed)
        self.assertEqual(b"# before\n", self._hello())

    def test_rollback_remains_separately_approval_bound(self) -> None:
        preview = self._run(self.change)
        approval = self._approve(preview, self.change)
        deployed = self._run(self.change, approval)
        self.assertTrue(deployed.deployed)
        assert deployed.deployment is not None
        version = deployed.deployment.version
        denied = self.deployment.rollback(version, None)  # type: ignore[arg-type]
        self.assertNotEqual(STATUS_ROLLED_BACK, denied.status)
        self.assertEqual(b"# after\n", self._hello())
        rb = ApprovalRecord(
            subject=rollback_fingerprint(version),
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )
        restored = self.deployment.rollback(version, rb)
        self.assertEqual(STATUS_ROLLED_BACK, restored.status)
        self.assertEqual(b"# before\n", self._hello())

    def test_pipeline_does_not_manufacture_approval_or_authority(self) -> None:
        src = inspect.getsource(process_change_proposal)
        self.assertNotIn("ApprovalRecord(", src)
        self.assertNotIn("deployment_authorized=True", src)
        preview = self._run(self.change)
        self.assertIsNotNone(preview.evaluation)
        assert preview.evaluation is not None
        self.assertFalse(preview.evaluation.deployment_authorized)

    def test_coding_not_invoked_and_allowlist_schema(self) -> None:
        from astra.state import ApplicationState

        with patch("astra.coding.agent.LocalCodingAgent.propose_change") as code:
            result = self._run(self.change)
            code.assert_not_called()
        self.assertEqual(PIPELINE_APPROVAL_MISSING, result.status)
        self.assertEqual(
            frozenset({"read_text", "read_project"}),
            PRODUCTION_EXECUTION_ALLOWLIST,
        )
        self.assertEqual(6, ApplicationState().schema_version)
        self.assertIsNot(
            Verdict.ALLOW,
            self.security.check_permanent_change("pipeline").verdict,
        )

    def test_no_subprocess_in_pipeline_or_core_orchestration(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "core"
        text = (root / "pipeline.py").read_text(encoding="utf-8")
        tree = ast.parse(text)
        names: list[str] = []
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names.extend(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                names.append(node.module.split(".")[0])
        self.assertNotIn("subprocess", names)
        self.assertNotIn("import subprocess", text)

    def test_adr_0030_exists_and_0029_untouched(self) -> None:
        root = Path(__file__).parent.parent / "docs" / "adr"
        adr30 = (root / "ADR-0030-end-to-end-controlled-change-pipeline.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("does not introduce a new authority", adr30.lower())
        self.assertIn("ProcessRunner", adr30)
        adr29 = (
            root / "ADR-0029-canonical-isolated-test-suite-execution.md"
        ).read_text(encoding="utf-8")
        self.assertIn("implemented in B-030", adr29)

    def test_milestone_check_not_hard_coded(self) -> None:
        from astra.presentation import progress

        src = inspect.getsource(progress._check_change_pipeline)
        self.assertIn("process_change_proposal", src)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_change_pipeline())


class ControllerPipelineTest(unittest.TestCase):
    def test_process_proposal_is_controller_owned(self) -> None:
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
        project = root / "project"
        data = root / "data"
        scratch = root / "scratch"
        project.mkdir()
        data.mkdir()
        _write_mini(project)
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
        with patch("astra.configuration.paths.project_root", return_value=project):
            ctrl = AstraController(settings)
            with self.assertRaises(ControllerNotStartedError):
                ctrl.process_proposal(self._unused_proposal())
            ctrl.start()
            self.addCleanup(ctrl.shutdown)
            self.assertTrue(callable(ctrl.process_proposal))
            self.assertTrue(callable(ctrl.rollback_change))
            before = (project / "astra" / "hello.py").read_bytes()
            change = _proposal(("astra/hello.py", "# after\n"))
            missing = ctrl.process_proposal(change)
            self.assertEqual(PIPELINE_APPROVAL_MISSING, missing.status)
            self.assertEqual(before, (project / "astra" / "hello.py").read_bytes())
            approval = ApprovalRecord(
                subject=evidence_fingerprint(change, missing.tests, missing.evaluation),
                approved_by="tester",
                approved_at=datetime.now(timezone.utc),
            )
            deployed = ctrl.process_proposal(change, approval)
            self.assertEqual(PIPELINE_DEPLOYED, deployed.status)
            self.assertEqual(b"# after\n", (project / "astra" / "hello.py").read_bytes())
            state = ctrl.get_state()
            self.assertEqual(6, state.schema_version)
            deployment = next(m for m in state.modules if m.module == "astra.deployment")
            cap = next(c for c in deployment.capabilities if c.name == "process_proposal")
            self.assertEqual(CapabilityStatus.OPERATIONAL, cap.status)
            ctrl.shutdown()
            with self.assertRaises(ControllerNotStartedError):
                ctrl.process_proposal(change)

    @staticmethod
    def _unused_proposal() -> ChangeProposal:
        return _proposal(("astra/hello.py", "# after\n"))


if __name__ == "__main__":
    unittest.main()
