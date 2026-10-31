"""Controlled deployment tests (B-027, ADR-0026).

Permanent writes only through LocalDeploymentAgent with a matching ApprovalRecord.
"""

from __future__ import annotations

import ast
import inspect
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

from astra.coding.contracts import ChangeProposal, FileChange
from astra.core.lifecycle import MeasurableProperty
from astra.deployment.agent import LocalDeploymentAgent
from astra.deployment.apply import commit_files
from astra.deployment.contracts import (
    ApprovalRecord,
    DeploymentAgent,
    DeploymentResult,
    STATUS_APPROVAL_INVALID,
    STATUS_APPROVAL_MISSING,
    STATUS_DEPLOYED,
    STATUS_DEPLOYMENT_ERROR,
    STATUS_EVALUATION_FAILED,
    STATUS_INVALID_PROPOSAL,
    STATUS_SECURITY_DENIED,
    STATUS_TESTS_FAILED,
)
from astra.deployment.fingerprint import evidence_fingerprint
from astra.evaluation.contracts import EvaluationReport
from astra.security.agent import PolicySecurityAgent
from astra.security.contracts import PolicyDecision, Verdict
from astra.testing.contracts import TestOutcome, TestReport
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST


def _proposal(*pairs: tuple[str, str]) -> ChangeProposal:
    changes = tuple(
        FileChange(path=Path(path), new_content=body, rationale="test")
        for path, body in pairs
    )
    return ChangeProposal(
        objective="deploy overlay",
        changes=changes,
        improves=(MeasurableProperty.MAINTAINABILITY,),
    )


def _tests(**kwargs: object) -> TestReport:
    fields = dict(
        tests_run=1,
        tests_passed=1,
        tests_failed=0,
        tests_errors=0,
        tests_skipped=0,
    )
    fields.update(kwargs)
    return TestReport(**fields)  # type: ignore[arg-type]


def _eval(**kwargs: object) -> EvaluationReport:
    fields = dict(subject="deploy overlay", tests_passed=True, evaluation_passed=True)
    fields.update(kwargs)
    return EvaluationReport(**fields)  # type: ignore[arg-type]


def _approval(proposal: ChangeProposal, tests: TestReport, evaluation: EvaluationReport) -> ApprovalRecord:
    return ApprovalRecord(
        subject=evidence_fingerprint(proposal, tests, evaluation),
        approved_by="tester",
        approved_at=datetime.now(timezone.utc),
    )


class _DenyPermanent:
    def check_permanent_change(self, description: str) -> PolicyDecision:
        return PolicyDecision(verdict=Verdict.DENY, reason="stub deny")


class LocalDeploymentAgentTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.project = Path(self._tmp.name) / "project"
        self.data = Path(self._tmp.name) / "data"
        self.project.mkdir()
        self.data.mkdir()
        (self.project / "astra").mkdir()
        (self.project / "astra" / "hello.py").write_bytes(b"# before\n")
        (self.project / "keep.py").write_bytes(b"# keep\n")
        self.security = PolicySecurityAgent(
            write_roots=(self.data,),
            project_roots=(self.project,),
        )
        self.agent = LocalDeploymentAgent(
            security=self.security, project_root=self.project
        )
        self.proposal = _proposal(("astra/hello.py", "# after\n"))
        self.tests = _tests()
        self.evaluation = _eval()
        self.approval = _approval(self.proposal, self.tests, self.evaluation)

    def _hello(self) -> bytes:
        return (self.project / "astra" / "hello.py").read_bytes()

    def _keep(self) -> bytes:
        return (self.project / "keep.py").read_bytes()

    def test_valid_bundle_deploys(self) -> None:
        before_keep = self._keep()
        result = self.agent.deploy(
            self.proposal, self.tests, self.evaluation, self.approval
        )
        self.assertIsInstance(result, DeploymentResult)
        self.assertTrue(result.deployed)
        self.assertEqual(STATUS_DEPLOYED, result.status)
        self.assertTrue(result.version)
        self.assertEqual(b"# after\n", self._hello())
        self.assertEqual(before_keep, self._keep())

    def test_missing_approval_does_not_write(self) -> None:
        before = self._hello()
        result = self.agent.deploy(self.proposal, self.tests, self.evaluation, None)  # type: ignore[arg-type]
        self.assertEqual(STATUS_APPROVAL_MISSING, result.status)
        self.assertFalse(result.deployed)
        self.assertEqual(before, self._hello())

    def test_invalid_approval_does_not_write(self) -> None:
        before = self._hello()
        bad = ApprovalRecord(
            subject="",
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )
        result = self.agent.deploy(self.proposal, self.tests, self.evaluation, bad)
        self.assertEqual(STATUS_APPROVAL_INVALID, result.status)
        self.assertEqual(before, self._hello())

    def test_approval_for_other_proposal_does_not_write(self) -> None:
        before = self._hello()
        other = _proposal(("astra/hello.py", "# other\n"))
        stolen = _approval(other, self.tests, self.evaluation)
        result = self.agent.deploy(
            self.proposal, self.tests, self.evaluation, stolen
        )
        self.assertEqual(STATUS_APPROVAL_INVALID, result.status)
        self.assertEqual(before, self._hello())

    def test_zero_run_with_fake_outcomes_does_not_write(self) -> None:
        before = self._hello()
        tests = TestReport(
            outcomes=(TestOutcome("fake.test", True),),
            tests_run=0,
            tests_passed=0,
            exit_code=0,
        )
        evaluation = _eval()
        result = self.agent.deploy(
            self.proposal, tests, evaluation, _approval(self.proposal, tests, evaluation)
        )
        self.assertEqual(STATUS_TESTS_FAILED, result.status)
        self.assertFalse(result.deployed)
        self.assertEqual(before, self._hello())

    def test_failed_tests_do_not_write(self) -> None:
        before = self._hello()
        tests = _tests(tests_run=2, tests_passed=1, tests_failed=1)
        result = self.agent.deploy(
            self.proposal, tests, self.evaluation, _approval(self.proposal, tests, self.evaluation)
        )
        self.assertEqual(STATUS_TESTS_FAILED, result.status)
        self.assertEqual(before, self._hello())

    def test_incomplete_tests_do_not_write(self) -> None:
        before = self._hello()
        tests = _tests(incomplete=True)
        result = self.agent.deploy(
            self.proposal, tests, self.evaluation, _approval(self.proposal, tests, self.evaluation)
        )
        self.assertEqual(STATUS_TESTS_FAILED, result.status)
        self.assertEqual(before, self._hello())

    def test_timeout_tests_do_not_write(self) -> None:
        before = self._hello()
        tests = _tests(timed_out=True)
        result = self.agent.deploy(
            self.proposal, tests, self.evaluation, _approval(self.proposal, tests, self.evaluation)
        )
        self.assertEqual(STATUS_TESTS_FAILED, result.status)
        self.assertEqual(before, self._hello())

    def test_spawn_failure_tests_do_not_write(self) -> None:
        before = self._hello()
        tests = _tests(spawn_failed=True)
        result = self.agent.deploy(
            self.proposal, tests, self.evaluation, _approval(self.proposal, tests, self.evaluation)
        )
        self.assertEqual(STATUS_TESTS_FAILED, result.status)
        self.assertEqual(before, self._hello())

    def test_failed_evaluation_does_not_write(self) -> None:
        before = self._hello()
        evaluation = _eval(evaluation_passed=False)
        result = self.agent.deploy(
            self.proposal,
            self.tests,
            evaluation,
            _approval(self.proposal, self.tests, evaluation),
        )
        self.assertEqual(STATUS_EVALUATION_FAILED, result.status)
        self.assertEqual(before, self._hello())

    def test_incomplete_evaluation_does_not_write(self) -> None:
        before = self._hello()
        evaluation = _eval(incomplete=True, evaluation_passed=False)
        result = self.agent.deploy(
            self.proposal,
            self.tests,
            evaluation,
            _approval(self.proposal, self.tests, evaluation),
        )
        self.assertEqual(STATUS_EVALUATION_FAILED, result.status)
        self.assertEqual(before, self._hello())

    def test_empty_proposal_does_not_write(self) -> None:
        before = self._hello()
        empty = ChangeProposal(
            objective="empty",
            changes=(),
            improves=(MeasurableProperty.MAINTAINABILITY,),
        )
        result = self.agent.deploy(
            empty, self.tests, self.evaluation, _approval(empty, self.tests, self.evaluation)
        )
        self.assertEqual(STATUS_INVALID_PROPOSAL, result.status)
        self.assertEqual(before, self._hello())

    def test_path_traversal_does_not_write(self) -> None:
        before_keep = self._keep()
        proposal = _proposal(("astra/../keep.py", "# pwned\n"))
        result = self.agent.deploy(
            proposal, self.tests, self.evaluation, _approval(proposal, self.tests, self.evaluation)
        )
        self.assertEqual(STATUS_INVALID_PROPOSAL, result.status)
        self.assertEqual(before_keep, self._keep())
        self.assertEqual(b"# before\n", self._hello())

    def test_absolute_outside_path_does_not_write(self) -> None:
        outside = Path(self._tmp.name) / "outside.py"
        outside.write_bytes(b"# out\n")
        proposal = _proposal((str(outside), "# pwned\n"))
        result = self.agent.deploy(
            proposal, self.tests, self.evaluation, _approval(proposal, self.tests, self.evaluation)
        )
        self.assertEqual(STATUS_INVALID_PROPOSAL, result.status)
        self.assertEqual(b"# out\n", outside.read_bytes())
        self.assertEqual(b"# before\n", self._hello())

    def test_git_and_data_paths_do_not_write(self) -> None:
        git = self.project / ".git"
        git.mkdir()
        (git / "config").write_bytes(b"# git\n")
        data = self.project / "data"
        data.mkdir()
        (data / "store.txt").write_bytes(b"# data\n")
        for rel, original, path in (
            (".git/config", b"# git\n", git / "config"),
            ("data/store.txt", b"# data\n", data / "store.txt"),
        ):
            proposal = _proposal((rel, "# pwned\n"))
            result = self.agent.deploy(
                proposal,
                self.tests,
                self.evaluation,
                _approval(proposal, self.tests, self.evaluation),
            )
            self.assertEqual(STATUS_INVALID_PROPOSAL, result.status, rel)
            self.assertEqual(original, path.read_bytes(), rel)

    def test_security_deny_does_not_write(self) -> None:
        before = self._hello()
        agent = LocalDeploymentAgent(security=_DenyPermanent(), project_root=self.project)
        result = agent.deploy(self.proposal, self.tests, self.evaluation, self.approval)
        self.assertEqual(STATUS_SECURITY_DENIED, result.status)
        self.assertEqual(before, self._hello())

    def test_requires_approval_with_record_deploys(self) -> None:
        verdict = self.security.check_permanent_change("probe")
        self.assertIs(Verdict.REQUIRES_APPROVAL, verdict.verdict)
        result = self.agent.deploy(
            self.proposal, self.tests, self.evaluation, self.approval
        )
        self.assertTrue(result.deployed)
        self.assertEqual(b"# after\n", self._hello())

    def test_multiple_files_validated_then_written(self) -> None:
        (self.project / "astra" / "second.py").write_bytes(b"# old\n")
        proposal = _proposal(
            ("astra/hello.py", "# one\n"),
            ("astra/second.py", "# two\n"),
        )
        result = self.agent.deploy(
            proposal, self.tests, self.evaluation, _approval(proposal, self.tests, self.evaluation)
        )
        self.assertTrue(result.deployed)
        self.assertEqual(b"# one\n", self._hello())
        self.assertEqual(b"# two\n", (self.project / "astra" / "second.py").read_bytes())
        self.assertEqual(b"# keep\n", self._keep())
        self.assertEqual(2, len(result.written))

    def test_write_failure_is_honest(self) -> None:
        before = self._hello()
        with patch(
            "astra.deployment.agent.commit_files",
            return_value=((), "disk full"),
        ):
            result = self.agent.deploy(
                self.proposal, self.tests, self.evaluation, self.approval
            )
        self.assertEqual(STATUS_DEPLOYMENT_ERROR, result.status)
        self.assertFalse(result.deployed)
        self.assertEqual(before, self._hello())

    def test_abc_methods_still_raise(self) -> None:
        with self.assertRaises(NotImplementedError):
            DeploymentAgent.deploy(  # type: ignore[misc]
                object(), self.proposal, self.tests, self.evaluation, self.approval
            )
        with self.assertRaises(NotImplementedError):
            DeploymentAgent.rollback(object(), "d-x", self.approval)  # type: ignore[misc]

    def test_rollback_without_version_manager_fails_closed(self) -> None:
        before = self._hello()
        result = self.agent.rollback("d-x", self.approval)
        self.assertFalse(result.rolled_back)
        self.assertEqual(before, self._hello())

    def test_allowlist_and_schema(self) -> None:
        from astra.state import ApplicationState

        self.assertEqual(
            frozenset({"read_text", "read_project"}),
            PRODUCTION_EXECUTION_ALLOWLIST,
        )
        self.assertEqual(6, ApplicationState().schema_version)
        self.assertIsNot(
            Verdict.ALLOW,
            self.security.check_permanent_change("deploy").verdict,
        )

    def test_symlink_escape_does_not_write(self) -> None:
        link = self.project / "astra" / "link.py"
        try:
            link.symlink_to(self.project / "keep.py")
        except OSError:
            self.skipTest("symlink privilege unavailable")
        before_keep = self._keep()
        proposal = _proposal(("astra/link.py", "# via-link\n"))
        result = self.agent.deploy(
            proposal, self.tests, self.evaluation, _approval(proposal, self.tests, self.evaluation)
        )
        self.assertEqual(STATUS_INVALID_PROPOSAL, result.status)
        self.assertEqual(before_keep, self._keep())


class DeploymentIsolationTest(unittest.TestCase):
    def test_no_subprocess_in_package(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "deployment"
        for py_file in root.glob("*.py"):
            text = py_file.read_text(encoding="utf-8")
            tree = ast.parse(text)
            names: list[str] = []
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    names.extend(alias.name.split(".")[0] for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names.append(node.module.split(".")[0])
            if py_file.name == "gitrun.py":
                self.assertIn("subprocess", names)
                self.assertNotIn("ProcessRunner", text)
                continue
            self.assertNotIn("subprocess", names, py_file.name)

    def test_agent_is_not_the_write_site(self) -> None:
        text = (
            Path(__file__).parent.parent / "astra" / "deployment" / "agent.py"
        ).read_text(encoding="utf-8")
        self.assertNotIn("write_text", text)
        self.assertNotIn("ProcessRunner", text)
        self.assertNotIn("astra.coding.agent", text)
        self.assertNotIn("astra.testing.agent", text)
        self.assertNotIn("astra.evaluation.agent", text)
        apply_src = (
            Path(__file__).parent.parent / "astra" / "deployment" / "apply.py"
        ).read_text(encoding="utf-8")
        self.assertIn("write_text", apply_src)
        self.assertIn("write_bytes", apply_src)
        self.assertIn("unlink", apply_src)
        agent_src = (
            Path(__file__).parent.parent / "astra" / "deployment" / "agent.py"
        ).read_text(encoding="utf-8")
        self.assertNotIn("write_bytes", agent_src)
        self.assertNotIn("unlink(", agent_src)

    def test_does_not_call_other_agents(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        project = Path(tmp.name) / "p"
        project.mkdir()
        (project / "a.py").write_bytes(b"x\n")
        security = PolicySecurityAgent(project_roots=(project,))
        agent = LocalDeploymentAgent(security=security, project_root=project)
        proposal = _proposal(("a.py", "y\n"))
        tests = _tests()
        evaluation = _eval()
        approval = _approval(proposal, tests, evaluation)
        with patch("astra.testing.agent.LocalTestingAgent.test_proposal") as test_fn:
            with patch("astra.coding.agent.LocalCodingAgent.propose_change") as code_fn:
                with patch(
                    "astra.evaluation.agent.LocalEvaluationAgent.evaluate_proposal"
                ) as eval_fn:
                    agent.deploy(proposal, tests, evaluation, approval)
        test_fn.assert_not_called()
        code_fn.assert_not_called()
        eval_fn.assert_not_called()

    def test_coding_still_write_free(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "coding"
        for py_file in root.glob("*.py"):
            text = py_file.read_text(encoding="utf-8")
            self.assertNotIn("write_text", text)

    def test_milestone_check_not_hard_coded(self) -> None:
        from astra.presentation import progress

        src = inspect.getsource(progress._check_controlled_deployment)
        self.assertIn("LocalDeploymentAgent", src)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_controlled_deployment())

    def test_adr_0025_untouched_marker(self) -> None:
        root = Path(__file__).parent.parent / "docs" / "adr"
        text = (root / "ADR-0025-evaluation-of-tested-proposals.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("implemented in B-026", text)
        self.assertIn("No test execution, no proposal mutation, no approval, no deployment", text)
        self.assertTrue((root / "ADR-0026-human-approved-controlled-deployment.md").is_file())

    def test_commit_files_is_the_write_boundary(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        dest = Path(tmp.name) / "out.py"
        written, err = commit_files(((dest, "ok\n"),), project=Path(tmp.name))
        self.assertEqual("", err)
        self.assertEqual((dest,), written)
        self.assertEqual("ok\n", dest.read_text(encoding="utf-8"))


class ControllerDeploymentAgentTest(unittest.TestCase):
    def test_deployment_agent_is_controller_owned(self) -> None:
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
            _ = ctrl.deployment
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        self.assertIn("deployment", ctrl.agent_names)
        self.assertIsInstance(ctrl.deployment, LocalDeploymentAgent)
        self.assertIsNot(ctrl.deployment, ctrl.coding)
        self.assertIsNot(ctrl.deployment, ctrl.testing)
        self.assertIsNot(ctrl.deployment, ctrl.evaluation)
        from astra.deployment.store import LocalVersionManager

        self.assertIsInstance(ctrl.versions, LocalVersionManager)
        self.assertNotIn("version_manager", ctrl.agent_names)
        from astra.deployment.contracts import RollbackResult
        from astra.deployment.fingerprint import rollback_fingerprint

        missing = ctrl.deployment.rollback("d-missing", None)  # type: ignore[arg-type]
        self.assertIsInstance(missing, RollbackResult)
        self.assertFalse(missing.rolled_back)
        unknown = ctrl.deployment.rollback(
            "d-missing",
            ApprovalRecord(
                subject=rollback_fingerprint("d-missing"),
                approved_by="tester",
                approved_at=datetime.now(timezone.utc),
            ),
        )
        self.assertFalse(unknown.rolled_back)
        state = ctrl.get_state()
        self.assertEqual(6, state.schema_version)
        deployment = next(m for m in state.modules if m.module == "astra.deployment")
        cap = next(c for c in deployment.capabilities if c.name == "deploy")
        self.assertEqual(CapabilityStatus.OPERATIONAL, cap.status)
        self.assertIn("ApprovalRecord", cap.notes)
        rollback = next(c for c in deployment.capabilities if c.name == "rollback")
        self.assertEqual(CapabilityStatus.OPERATIONAL, rollback.status)
        self.assertIn("ApprovalRecord", rollback.notes)
        self.assertIs(False, state.coding.applied)
        ctrl.shutdown()
        with self.assertRaises(ControllerNotStartedError):
            _ = ctrl.deployment
        with self.assertRaises(ControllerNotStartedError):
            _ = ctrl.versions


if __name__ == "__main__":
    unittest.main()
