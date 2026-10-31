"""End-to-end integration, recovery, and failure-path tests (B-036, ADR-0035).

Real controller/services. Isolated temp project. Not an OS sandbox.
"""

from __future__ import annotations

import ast
import dataclasses
import inspect
import shutil
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

from astra.coding.contracts import ChangeProposal, FileChange
from astra.configuration.settings import (
    AstraSettings,
    GitSettings,
    KnowledgeSettings,
    LoggingSettings,
    MemorySettings,
    PlanningSettings,
    TestingSettings,
)
from astra.core.lifecycle import MeasurableProperty
from astra.core.pipeline import (
    PIPELINE_APPROVAL_INVALID,
    PIPELINE_APPROVAL_MISSING,
    PIPELINE_DEPLOYED,
    PIPELINE_DEPLOYED_HISTORY_FAILED,
    PIPELINE_DEPLOYMENT_FAILED,
    PIPELINE_EVALUATION_FAILED,
    PIPELINE_TESTING_FAILED,
    PIPELINE_TESTING_INCOMPLETE,
    process_change_proposal,
)
from astra.deployment.contracts import (
    ApprovalRecord,
    STATUS_APPROVAL_INVALID,
    STATUS_APPROVAL_MISSING,
    STATUS_CORRUPTED,
    STATUS_DEPLOYED,
    STATUS_DEPLOYED_HISTORY_FAILED,
    STATUS_ROLLED_BACK,
)
from astra.deployment.fingerprint import evidence_fingerprint, rollback_fingerprint
from astra.deployment.gitstore import GIT_OPERATION_FAILED, GIT_UNAVAILABLE, HISTORY_RECORDED
from astra.evaluation.contracts import COMPARISON_REGRESSED, EvaluationReport, MetricSample
from astra.security.contracts import Verdict
from astra.state import ApplicationState
from astra.testing.agent import LocalTestingAgent
from astra.testing.contracts import TestReport
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST
from astra.tools.contracts import ToolSpec

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


def _proposal(*pairs: tuple[str, str], objective: str = "integration overlay") -> ChangeProposal:
    changes = tuple(
        FileChange(path=Path(path), new_content=body, rationale="e2e")
        for path, body in pairs
    )
    return ChangeProposal(
        objective=objective,
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
    (project / "keep.py").write_bytes(b"# keep\n")


def _tree_bytes(root: Path) -> dict[str, bytes]:
    out: dict[str, bytes] = {}
    for path in sorted(root.rglob("*")):
        if path.is_file() and not path.is_symlink():
            out[path.relative_to(root).as_posix()] = path.read_bytes()
    return out


def _settings(root: Path, *, git_exe: Path | None = None, init_git: bool = False) -> AstraSettings:
    data = root / "data"
    scratch = root / "scratch"
    data.mkdir(exist_ok=True)
    return AstraSettings(
        memory=MemorySettings(
            database_path=data / "mem.db",
            vector_index_path=data / "vectors.faiss",
        ),
        knowledge=KnowledgeSettings(database_path=data / "knowledge.db"),
        planning=PlanningSettings(database_path=data / "planning.db"),
        logging=LoggingSettings(directory=data / "logs"),
        testing=TestingSettings(scratch_directory=scratch),
        git=GitSettings(
            executable=git_exe,
            init_if_missing=init_git,
            author_name="Astra",
            author_email="astra@localhost",
        ),
    )


def _approve(proposal: ChangeProposal, tests: TestReport, evaluation: EvaluationReport) -> ApprovalRecord:
    return ApprovalRecord(
        subject=evidence_fingerprint(proposal, tests, evaluation),
        approved_by="tester",
        approved_at=datetime.now(timezone.utc),
    )


class ControllerIntegrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.project = self.root / "project"
        self.scratch = self.root / "scratch"
        self.project.mkdir()
        _write_mini(self.project)
        self._project_patch = patch(
            "astra.configuration.paths.project_root", return_value=self.project
        )
        self._project_patch.start()
        self.addCleanup(self._project_patch.stop)
        from astra.core.astra_controller import AstraController

        self.ctrl = AstraController(_settings(self.root))
        self.ctrl.start()
        self.addCleanup(self.ctrl.shutdown)
        self.change = _proposal(("astra/hello.py", "# after\n"))

    def _snap(self) -> dict[str, bytes]:
        return _tree_bytes(self.project)

    def _assert_live(self, before: dict[str, bytes]) -> None:
        self.assertEqual(before, _tree_bytes(self.project))

    def _hello(self) -> bytes:
        return (self.project / "astra" / "hello.py").read_bytes()

    def test_a_complete_success_path_without_git(self) -> None:
        before = self._snap()
        preview = self.ctrl.process_proposal(self.change)
        self.assertEqual(PIPELINE_APPROVAL_MISSING, preview.status)
        self.assertIsInstance(preview.tests, TestReport)
        self.assertTrue(preview.tests.passed)
        self.assertFalse(preview.tests.incomplete)
        self.assertIsInstance(preview.evaluation, EvaluationReport)
        self.assertTrue(preview.evaluation.evaluation_passed)
        self.assertFalse(preview.evaluation.deployment_authorized)
        self._assert_live(before)
        approval = _approve(self.change, preview.tests, preview.evaluation)
        result = self.ctrl.process_proposal(self.change, approval)
        self.assertEqual(PIPELINE_DEPLOYED, result.status)
        self.assertTrue(result.deployed)
        assert result.deployment is not None
        self.assertEqual(STATUS_DEPLOYED, result.deployment.status)
        self.assertEqual(GIT_UNAVAILABLE, result.deployment.git_status)
        self.assertEqual("", result.deployment.git_commit)
        self.assertEqual(b"# after\n", self._hello())
        self.assertEqual(b"# keep\n", (self.project / "keep.py").read_bytes())
        version = result.deployment.version
        self.assertTrue(version)
        recorded = self.ctrl.versions.get(version)
        self.assertIsNotNone(recorded)
        pre = self.scratch / "versions" / version / "files" / "astra" / "hello.py"
        self.assertEqual(b"# before\n", pre.read_bytes())
        expected = before.copy()
        expected["astra/hello.py"] = b"# after\n"
        self.assertEqual(expected, _tree_bytes(self.project))

    def test_b_test_failure_skips_eval_deploy_and_cleans_scratch(self) -> None:
        before = self._snap()
        failing = _proposal(("tests/test_fail.py", _FAILING))
        with (
            patch.object(self.ctrl.evaluation, "evaluate_proposal") as ev,
            patch.object(self.ctrl.deployment, "deploy") as dep,
        ):
            result = self.ctrl.process_proposal(failing)
        self.assertEqual(PIPELINE_TESTING_FAILED, result.status)
        self.assertFalse(result.deployed)
        self.assertIsInstance(result.tests, TestReport)
        self.assertFalse(result.tests.passed)
        self.assertIsNone(result.evaluation)
        ev.assert_not_called()
        dep.assert_not_called()
        self._assert_live(before)
        self.assertEqual([], list(self.ctrl.copied_subset_workspace._sessions))
        self.assertEqual((), self.ctrl.versions.history())

    def test_c_timeout_is_incomplete_and_blocks_later_stages(self) -> None:
        before = self._snap()
        sleeper = LocalTestingAgent(
            subset=self.ctrl.copied_subset_workspace,
            runner=self.ctrl.process_runner,
            timeout_seconds=1.0,
        )
        with (
            patch.object(self.ctrl.evaluation, "evaluate_proposal") as ev,
            patch.object(self.ctrl.deployment, "deploy") as dep,
        ):
            result = process_change_proposal(
                testing=sleeper,
                evaluation=self.ctrl.evaluation,
                deployment=self.ctrl.deployment,
                proposal=_proposal(("tests/test_sleep.py", _SLEEPING)),
            )
        self.assertEqual(PIPELINE_TESTING_INCOMPLETE, result.status)
        self.assertTrue(result.tests is not None and result.tests.timed_out)
        self.assertFalse(result.tests.passed)
        ev.assert_not_called()
        dep.assert_not_called()
        self._assert_live(before)

    def test_d_evaluation_regression_cannot_be_overridden(self) -> None:
        before = self._snap()
        preview = self.ctrl.process_proposal(self.change)
        self.assertTrue(preview.tests is not None and preview.tests.passed)
        recorded = self.ctrl.evaluation.record_baseline(
            self.change.objective,
            EvaluationReport(
                subject=self.change.objective,
                samples=(
                    MetricSample(
                        property=MeasurableProperty.MAINTAINABILITY,
                        metric="score",
                        value=10.0,
                        higher_is_better=True,
                    ),
                ),
                implementation="base",
            ),
        )
        self.assertTrue(recorded.recorded)
        failed = self.ctrl.evaluation.evaluate_proposal(
            self.change,
            preview.tests,
            baseline_id=recorded.baseline_id,
            candidate=(
                MetricSample(
                    property=MeasurableProperty.MAINTAINABILITY,
                    metric="score",
                    value=1.0,
                    higher_is_better=True,
                ),
            ),
        )
        self.assertFalse(failed.evaluation_passed)
        self.assertEqual(COMPARISON_REGRESSED, failed.comparison_status)
        self.assertFalse(failed.deployment_authorized)
        approval = _approve(self.change, preview.tests, failed)
        deployed = self.ctrl.deployment.deploy(
            self.change, preview.tests, failed, approval
        )
        self.assertFalse(deployed.deployed)
        self._assert_live(before)

    def test_e_missing_approval_writes_nothing(self) -> None:
        before = self._snap()
        result = self.ctrl.process_proposal(self.change)
        self.assertEqual(PIPELINE_APPROVAL_MISSING, result.status)
        self.assertIsNone(result.deployment)
        self._assert_live(before)
        self.assertEqual((), self.ctrl.versions.history())

    def test_f_wrong_approval_writes_nothing(self) -> None:
        before = self._snap()
        preview = self.ctrl.process_proposal(self.change)
        other = _proposal(("astra/hello.py", "# other\n"))
        other_preview = self.ctrl.process_proposal(other)
        stolen = _approve(other, other_preview.tests, other_preview.evaluation)
        result = self.ctrl.process_proposal(self.change, stolen)
        self.assertEqual(PIPELINE_APPROVAL_INVALID, result.status)
        self._assert_live(before)

    def test_g_stale_approval_after_evidence_change(self) -> None:
        before = self._snap()
        preview = self.ctrl.process_proposal(self.change)
        approval = _approve(self.change, preview.tests, preview.evaluation)
        mutated = _proposal(("astra/hello.py", "# mutated\n"))
        result = self.ctrl.process_proposal(mutated, approval)
        self.assertEqual(PIPELINE_APPROVAL_INVALID, result.status)
        self._assert_live(before)

    def test_h_i_deploy_then_rollback_restores_preimage(self) -> None:
        created = _proposal(("astra/new.py", "# created\n"))
        preview = self.ctrl.process_proposal(created)
        approval = _approve(created, preview.tests, preview.evaluation)
        deployed = self.ctrl.process_proposal(created, approval)
        self.assertEqual(PIPELINE_DEPLOYED, deployed.status)
        self.assertTrue((self.project / "astra" / "new.py").is_file())
        self.assertEqual(b"# keep\n", (self.project / "keep.py").read_bytes())
        assert deployed.deployment is not None
        version = deployed.deployment.version
        denied = self.ctrl.rollback_change(version, None)
        self.assertNotEqual(STATUS_ROLLED_BACK, denied.status)
        self.assertTrue((self.project / "astra" / "new.py").is_file())
        rb = ApprovalRecord(
            subject=rollback_fingerprint(version),
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )
        restored = self.ctrl.rollback_change(version, rb)
        self.assertEqual(STATUS_ROLLED_BACK, restored.status)
        self.assertFalse((self.project / "astra" / "new.py").exists())
        self.assertEqual(b"# before\n", self._hello())
        self.assertEqual(b"# keep\n", (self.project / "keep.py").read_bytes())

    def test_j_rollback_authorization_failures(self) -> None:
        preview = self.ctrl.process_proposal(self.change)
        deployed = self.ctrl.process_proposal(
            self.change, _approve(self.change, preview.tests, preview.evaluation)
        )
        assert deployed.deployment is not None
        version = deployed.deployment.version
        live = self._hello()
        none = self.ctrl.rollback_change(version, None)
        self.assertEqual(STATUS_APPROVAL_MISSING, none.status)
        wrong = ApprovalRecord(
            subject=evidence_fingerprint(self.change, preview.tests, preview.evaluation),
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )
        bad = self.ctrl.rollback_change(version, wrong)
        self.assertEqual(STATUS_APPROVAL_INVALID, bad.status)
        other = ApprovalRecord(
            subject=rollback_fingerprint("d-deadbeefdeadbeef-0001"),
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )
        stale = self.ctrl.rollback_change(version, other)
        self.assertEqual(STATUS_APPROVAL_INVALID, stale.status)
        self.assertEqual(live, self._hello())

    def test_k_corrupt_snapshot_fails_closed(self) -> None:
        preview = self.ctrl.process_proposal(self.change)
        deployed = self.ctrl.process_proposal(
            self.change, _approve(self.change, preview.tests, preview.evaluation)
        )
        assert deployed.deployment is not None
        version = deployed.deployment.version
        blob = self.scratch / "versions" / version / "files" / "astra" / "hello.py"
        blob.write_bytes(b"# tampered\n")
        live = self._hello()
        rb = ApprovalRecord(
            subject=rollback_fingerprint(version),
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )
        rolled = self.ctrl.rollback_change(version, rb)
        self.assertEqual(STATUS_CORRUPTED, rolled.status)
        self.assertFalse(rolled.rolled_back)
        self.assertEqual(live, self._hello())

    def test_l_git_failure_after_write_is_honest(self) -> None:
        from astra.deployment.gitstore import GitRecord

        preview = self.ctrl.process_proposal(self.change)
        approval = _approve(self.change, preview.tests, preview.evaluation)
        failed = GitRecord(status=GIT_OPERATION_FAILED, reason="git exited non-zero")
        with patch.object(self.ctrl.git_history, "record_deployment", return_value=failed):
            result = self.ctrl.process_proposal(self.change, approval)
        self.assertEqual(PIPELINE_DEPLOYED_HISTORY_FAILED, result.status)
        self.assertTrue(result.deployed)
        assert result.deployment is not None
        self.assertEqual(STATUS_DEPLOYED_HISTORY_FAILED, result.deployment.status)
        self.assertEqual("", result.deployment.git_commit)
        self.assertEqual(b"# after\n", self._hello())
        self.assertTrue(result.deployment.version)
        self.assertIsNotNone(self.ctrl.versions.get(result.deployment.version))

    def test_m_git_unavailable_is_not_a_deploy_prerequisite(self) -> None:
        preview = self.ctrl.process_proposal(self.change)
        result = self.ctrl.process_proposal(
            self.change, _approve(self.change, preview.tests, preview.evaluation)
        )
        self.assertEqual(PIPELINE_DEPLOYED, result.status)
        assert result.deployment is not None
        self.assertEqual(GIT_UNAVAILABLE, result.deployment.git_status)
        self.assertEqual("", result.deployment.git_commit)

    def test_n_repeated_deployments_have_distinct_preimages(self) -> None:
        first_p = _proposal(("astra/hello.py", "# v1\n"))
        first_prev = self.ctrl.process_proposal(first_p)
        first = self.ctrl.process_proposal(
            first_p, _approve(first_p, first_prev.tests, first_prev.evaluation)
        )
        second_p = _proposal(("astra/hello.py", "# v2\n"))
        second_prev = self.ctrl.process_proposal(second_p)
        second = self.ctrl.process_proposal(
            second_p, _approve(second_p, second_prev.tests, second_prev.evaluation)
        )
        assert first.deployment is not None and second.deployment is not None
        self.assertNotEqual(first.deployment.version, second.deployment.version)
        self.assertEqual(b"# v2\n", self._hello())
        first_blob = (
            self.scratch
            / "versions"
            / first.deployment.version
            / "files"
            / "astra"
            / "hello.py"
        )
        second_blob = (
            self.scratch
            / "versions"
            / second.deployment.version
            / "files"
            / "astra"
            / "hello.py"
        )
        self.assertEqual(b"# before\n", first_blob.read_bytes())
        self.assertEqual(b"# v1\n", second_blob.read_bytes())
        rb = ApprovalRecord(
            subject=rollback_fingerprint(second.deployment.version),
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )
        restored = self.ctrl.rollback_change(second.deployment.version, rb)
        self.assertEqual(STATUS_ROLLED_BACK, restored.status)
        self.assertEqual(b"# v1\n", self._hello())
        self.assertIsNotNone(self.ctrl.versions.get(first.deployment.version))

    def test_o_controller_failures_are_honest(self) -> None:
        before = self._snap()
        with patch.object(
            self.ctrl.testing, "test_proposal", side_effect=RuntimeError("test boom")
        ):
            testing = self.ctrl.process_proposal(self.change)
        self.assertEqual(PIPELINE_TESTING_INCOMPLETE, testing.status)
        self.assertIsNone(testing.evaluation)
        self._assert_live(before)
        with patch.object(
            self.ctrl.evaluation,
            "evaluate_proposal",
            side_effect=RuntimeError("eval boom"),
        ):
            evaluation = self.ctrl.process_proposal(self.change)
        self.assertEqual(PIPELINE_EVALUATION_FAILED, evaluation.status)
        self.assertIsNone(evaluation.deployment)
        self._assert_live(before)
        preview = self.ctrl.process_proposal(self.change)
        approval = _approve(self.change, preview.tests, preview.evaluation)
        with patch.object(
            self.ctrl.deployment, "deploy", side_effect=RuntimeError("deploy boom")
        ):
            broken = self.ctrl.process_proposal(self.change, approval)
        self.assertEqual(PIPELINE_DEPLOYMENT_FAILED, broken.status)
        self._assert_live(before)
        with patch.object(
            self.ctrl.versions, "save_preimage", return_value=("", "persist failed")
        ):
            persist = self.ctrl.process_proposal(self.change, approval)
        self.assertEqual(PIPELINE_DEPLOYMENT_FAILED, persist.status)
        self.assertFalse(persist.deployed)
        self.assertEqual(b"# after\n", self._hello())
        self.assertEqual("", persist.deployment.version if persist.deployment else "")

    def test_p_service_boundaries_hold(self) -> None:
        self.assertFalse(hasattr(self.ctrl.coding, "deploy"))
        self.assertFalse(hasattr(self.ctrl.testing, "deploy"))
        self.assertFalse(hasattr(self.ctrl.evaluation, "deploy"))
        self.assertFalse(hasattr(self.ctrl.plugins, "run"))
        self.assertFalse(hasattr(self.ctrl.plugins, "commit_files"))
        self.assertFalse(hasattr(self.ctrl.testing, "rollback"))
        denied = self.ctrl.request_tool("testing", "process", {})
        self.assertFalse(denied.executed)
        self.assertFalse(denied.execution_permitted)
        names = {field.name for field in dataclasses.fields(ToolSpec)}
        self.assertNotIn("process", names)
        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))
        src = inspect.getsource(process_change_proposal)
        self.assertNotIn("ApprovalRecord(", src)
        preview = self.ctrl.process_proposal(self.change)
        assert preview.evaluation is not None
        self.assertFalse(preview.evaluation.deployment_authorized)
        self.assertIsNot(
            Verdict.ALLOW,
            self.ctrl.security.check_permanent_change("e2e").verdict,
        )

    def test_q_scratch_sessions_cleaned(self) -> None:
        self.ctrl.process_proposal(self.change)
        self.assertEqual([], list(self.ctrl.copied_subset_workspace._sessions))
        leftover = [
            path
            for path in self.scratch.iterdir()
            if path.is_dir() and path.name not in {"versions", "baselines"}
        ]
        self.assertEqual([], leftover)
        self.ctrl.shutdown()
        self.assertIsNone(self.ctrl._subset_workspace)

    def test_r_repeatable_identical_workflows(self) -> None:
        first_prev = self.ctrl.process_proposal(self.change)
        first = self.ctrl.process_proposal(
            self.change, _approve(self.change, first_prev.tests, first_prev.evaluation)
        )
        second_prev = self.ctrl.process_proposal(self.change)
        second = self.ctrl.process_proposal(
            self.change, _approve(self.change, second_prev.tests, second_prev.evaluation)
        )
        assert first.deployment is not None and second.deployment is not None
        self.assertNotEqual(first.deployment.version, second.deployment.version)
        self.assertEqual(b"# after\n", self._hello())
        self.assertEqual([], list(self.ctrl.copied_subset_workspace._sessions))

    def test_invariants_and_adr(self) -> None:
        self.assertEqual(6, ApplicationState().schema_version)
        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))
        root = Path(__file__).resolve().parents[1] / "astra"
        for package in ("coding", "evaluation", "plugins", "security"):
            for py_file in (root / package).glob("*.py"):
                tree = ast.parse(py_file.read_text(encoding="utf-8"))
                imported: list[str] = []
                for node in ast.walk(tree):
                    if isinstance(node, ast.Import):
                        imported.extend(alias.name.split(".")[0] for alias in node.names)
                    elif isinstance(node, ast.ImportFrom) and node.module:
                        imported.append(node.module.split(".")[0])
                self.assertNotIn("subprocess", imported, py_file.name)
        adr = (
            Path(__file__).resolve().parents[1]
            / "docs"
            / "adr"
            / "ADR-0035-end-to-end-integration-and-recovery.md"
        )
        text = adr.read_text(encoding="utf-8")
        self.assertIn("implemented in B-036", text)
        self.assertIn("os sandbox", text.lower())

    def test_milestone_check_not_hard_coded(self) -> None:
        from astra.presentation import progress

        src = inspect.getsource(progress._check_end_to_end_integration)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_end_to_end_integration())


class GitConfiguredIntegrationTest(unittest.TestCase):
    def setUp(self) -> None:
        found = shutil.which("git")
        if not found:
            self.skipTest("git executable unavailable")
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.project = self.root / "project"
        self.project.mkdir()
        _write_mini(self.project)
        self._project_patch = patch(
            "astra.configuration.paths.project_root", return_value=self.project
        )
        self._project_patch.start()
        self.addCleanup(self._project_patch.stop)
        from astra.core.astra_controller import AstraController

        self.ctrl = AstraController(
            _settings(self.root, git_exe=Path(found), init_git=True)
        )
        self.ctrl.start()
        self.addCleanup(self.ctrl.shutdown)
        self.change = _proposal(("astra/hello.py", "# after\n"))

    def test_a_success_records_git_history_not_authority(self) -> None:
        preview = self.ctrl.process_proposal(self.change)
        result = self.ctrl.process_proposal(
            self.change, _approve(self.change, preview.tests, preview.evaluation)
        )
        self.assertEqual(PIPELINE_DEPLOYED, result.status)
        assert result.deployment is not None
        self.assertEqual(HISTORY_RECORDED, result.deployment.git_status)
        self.assertTrue(result.deployment.git_commit)
        self.assertNotEqual(result.deployment.git_commit, result.deployment.version)
        self.assertEqual(b"# after\n", (self.project / "astra" / "hello.py").read_bytes())
        denied = self.ctrl.restore_git_history(result.deployment.git_commit, None)
        self.assertFalse(denied.restored)
        self.assertEqual(b"# after\n", (self.project / "astra" / "hello.py").read_bytes())
