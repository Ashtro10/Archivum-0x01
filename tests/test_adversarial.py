"""Adversarial end-to-end safety tests (B-032, ADR-0031).

Policy-gated fail-closed architecture. Not an OS sandbox. Live project bytes
must stay identical after every unauthorized / malformed attempt.
"""

from __future__ import annotations

import ast
import dataclasses
import json
import os
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
    process_change_proposal,
)
from astra.deployment.agent import LocalDeploymentAgent
from astra.deployment.contracts import (
    ApprovalRecord,
    DeploymentResult,
    STATUS_DEPLOYED,
    STATUS_DEPLOYMENT_ERROR,
    STATUS_INVALID_PROPOSAL,
    STATUS_ROLLED_BACK,
    STATUS_SECURITY_DENIED,
)
from astra.deployment.fingerprint import evidence_fingerprint, rollback_fingerprint
from astra.deployment.store import LocalVersionManager
from astra.evaluation.agent import LocalEvaluationAgent
from astra.evaluation.contracts import EvaluationReport, MetricSample
from astra.evaluation.store import LocalBaselineStore, baseline_id_for
from astra.security.agent import PolicySecurityAgent
from astra.security.contracts import PolicyDecision, ProcessSpec, Verdict
from astra.testing.agent import LocalTestingAgent
from astra.testing.contracts import TestReport
from astra.testing.runner import (
    DEFAULT_PROBE_TIMEOUT,
    MAX_OUTPUT_BYTES,
    ProcessRunner,
    ProcessStatus,
)
from astra.testing.subset import CopiedSubsetWorkspace
from astra.testing.workspace import MAX_FILE_BYTES
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


def _proposal(*pairs: tuple[str, str]) -> ChangeProposal:
    changes = tuple(
        FileChange(path=Path(path), new_content=body, rationale="adv")
        for path, body in pairs
    )
    return ChangeProposal(
        objective="adversarial overlay",
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
            rel = path.relative_to(root).as_posix()
            out[rel] = path.read_bytes()
    return out


class _ForcePermanent:
    def __init__(self, verdict: Verdict) -> None:
        self._verdict = verdict

    def check_permanent_change(self, description: str) -> PolicyDecision:
        return PolicyDecision(verdict=self._verdict, reason="forced")


class AdversarialHarness(unittest.TestCase):
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

    def _snap(self) -> dict[str, bytes]:
        return _tree_bytes(self.project)

    def _assert_live(self, before: dict[str, bytes]) -> None:
        self.assertEqual(before, _tree_bytes(self.project))

    def _run(self, proposal: ChangeProposal, approval: ApprovalRecord | None = None):
        return process_change_proposal(
            testing=self.testing,
            evaluation=self.evaluation,
            deployment=self.deployment,
            proposal=proposal,
            approval=approval,
        )

    def _approve(self, result, proposal: ChangeProposal) -> ApprovalRecord:
        assert result.tests is not None
        assert result.evaluation is not None
        return ApprovalRecord(
            subject=evidence_fingerprint(proposal, result.tests, result.evaluation),
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )

    def _passing_tests(self, **kwargs: object) -> TestReport:
        fields = dict(tests_run=1, tests_passed=1, tests_failed=0, tests_errors=0)
        fields.update(kwargs)
        return TestReport(**fields)  # type: ignore[arg-type]

    def _eval_ok(self, **kwargs: object) -> EvaluationReport:
        fields = dict(
            subject="adversarial overlay",
            tests_passed=True,
            evaluation_passed=True,
            deployment_authorized=False,
        )
        fields.update(kwargs)
        return EvaluationReport(**fields)  # type: ignore[arg-type]


class ProposalPathAttackTest(AdversarialHarness):
    def test_traversal_and_absolute_paths_do_not_touch_live_tree(self) -> None:
        outside = Path(self._tmp.name) / "outside.py"
        outside.write_bytes(b"# out\n")
        scratch_target = self.scratch / "escaped.py"
        before = self._snap()
        attacks = (
            ("../outside.py", "nope\n"),
            ("../../outside.py", "nope\n"),
            ("astra/../keep.py", "# pwned\n"),
            (str(outside), "# pwned\n"),
            (str(scratch_target), "nope\n"),
        )
        for rel, body in attacks:
            with self.subTest(path=rel):
                result = self._run(_proposal((rel, body)))
                self.assertNotEqual(PIPELINE_DEPLOYED, result.status)
                self.assertFalse(result.deployed)
                self._assert_live(before)
                self.assertEqual(b"# out\n", outside.read_bytes())
                self.assertFalse(scratch_target.exists())


class ProposalDestinationAttackTest(AdversarialHarness):
    def test_protected_destinations_do_not_write(self) -> None:
        git = self.project / ".git"
        git.mkdir()
        (git / "config").write_bytes(b"# git\n")
        for name, original in (
            ("data", b"# data\n"),
            ("logs", b"# logs\n"),
            ("models", b"# models\n"),
        ):
            folder = self.project / name
            folder.mkdir()
            (folder / "store.txt").write_bytes(original)
        (self.project / "secrets.json").write_bytes(b"{}\n")
        (self.project / ".env").write_bytes(b"K=1\n")
        (self.project / "credentials.json").write_bytes(b"{}\n")
        before = self._snap()
        tests = self._passing_tests()
        evaluation = self._eval_ok()
        for rel in (
            ".git/config",
            "data/store.txt",
            "logs/store.txt",
            "models/store.txt",
            "secrets.json",
            ".env",
            "credentials.json",
        ):
            with self.subTest(path=rel):
                proposal = _proposal((rel, "# pwned\n"))
                piped = self._run(proposal)
                self.assertFalse(piped.deployed)
                deployed = self.deployment.deploy(
                    proposal,
                    tests,
                    evaluation,
                    ApprovalRecord(
                        subject=evidence_fingerprint(proposal, tests, evaluation),
                        approved_by="tester",
                        approved_at=datetime.now(timezone.utc),
                    ),
                )
                self.assertFalse(deployed.deployed)
                self.assertEqual(STATUS_INVALID_PROPOSAL, deployed.status)
                self._assert_live(before)

    def test_symlink_destination_does_not_write(self) -> None:
        link = self.project / "astra" / "link.py"
        try:
            link.symlink_to(self.project / "keep.py")
        except OSError:
            self.skipTest("symlink privilege unavailable")
        before = self._snap()
        proposal = _proposal(("astra/link.py", "# via-link\n"))
        result = self._run(proposal)
        self.assertFalse(result.deployed)
        tests = self._passing_tests()
        evaluation = self._eval_ok()
        deployed = self.deployment.deploy(
            proposal,
            tests,
            evaluation,
            ApprovalRecord(
                subject=evidence_fingerprint(proposal, tests, evaluation),
                approved_by="tester",
                approved_at=datetime.now(timezone.utc),
            ),
        )
        self.assertFalse(deployed.deployed)
        self._assert_live(before)
        self.assertEqual(b"# keep\n", (self.project / "keep.py").read_bytes())


class ProposalContentAttackTest(AdversarialHarness):
    def test_oversize_empty_malformed_duplicate_conflict(self) -> None:
        before = self._snap()
        huge = _proposal(("astra/hello.py", "a" * (MAX_FILE_BYTES + 1)))
        self.assertNotEqual(PIPELINE_DEPLOYED, self._run(huge).status)
        self._assert_live(before)

        empty_body = _proposal(("astra/hello.py", ""))
        missing = self._run(empty_body)
        self.assertEqual(PIPELINE_APPROVAL_MISSING, missing.status)
        self._assert_live(before)

        malformed = self._run(ChangeProposal(objective="no changes"))
        self.assertEqual(PIPELINE_INVALID_PROPOSAL, malformed.status)
        self._assert_live(before)

        dup = _proposal(("astra/hello.py", "# one\n"), ("astra/hello.py", "# two\n"))
        conflict = self._run(dup)
        self.assertIn(
            conflict.status,
            {PIPELINE_TESTING_INCOMPLETE, PIPELINE_INVALID_PROPOSAL, PIPELINE_DEPLOYMENT_FAILED},
        )
        self.assertFalse(conflict.deployed)
        self._assert_live(before)


class ApprovalAttackTest(AdversarialHarness):
    def test_missing_malformed_wrong_and_stale_approvals(self) -> None:
        before = self._snap()
        preview = self._run(self.change)
        self.assertEqual(PIPELINE_APPROVAL_MISSING, preview.status)
        self._assert_live(before)

        self.assertFalse(
            self.deployment.deploy(
                self.change, preview.tests, preview.evaluation, None  # type: ignore[arg-type]
            ).deployed
        )
        self._assert_live(before)

        malformed = self._run(self.change, object())  # type: ignore[arg-type]
        self.assertEqual(PIPELINE_APPROVAL_INVALID, malformed.status)
        self._assert_live(before)

        wrong = ApprovalRecord(
            subject="not-the-fingerprint",
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )
        self.assertEqual(PIPELINE_APPROVAL_INVALID, self._run(self.change, wrong).status)
        self._assert_live(before)

        other = _proposal(("astra/hello.py", "# other\n"))
        stolen = self._approve(preview, self.change)
        self.assertEqual(PIPELINE_APPROVAL_INVALID, self._run(other, stolen).status)
        self._assert_live(before)

        mutated_tests = self._passing_tests(tests_run=99, tests_passed=99)
        with patch.object(self.testing, "test_proposal", return_value=mutated_tests):
            self.assertEqual(PIPELINE_APPROVAL_INVALID, self._run(self.change, stolen).status)
        self._assert_live(before)

        mutated_eval = self._eval_ok(tests_passed=False)
        with patch.object(self.evaluation, "evaluate_proposal", return_value=mutated_eval):
            self.assertEqual(PIPELINE_APPROVAL_INVALID, self._run(self.change, stolen).status)
        self._assert_live(before)

        forged = self._eval_ok(deployment_authorized=True)
        with (
            patch.object(self.evaluation, "evaluate_proposal", return_value=forged),
            patch.object(self.deployment, "deploy") as dep,
        ):
            result = self._run(self.change)
        self.assertEqual(PIPELINE_APPROVAL_MISSING, result.status)
        dep.assert_not_called()
        self.assertTrue(result.evaluation is not None)
        assert result.evaluation is not None
        self.assertTrue(result.evaluation.deployment_authorized)
        self._assert_live(before)

        first = self._run(self.change, stolen)
        self.assertEqual(PIPELINE_DEPLOYED, first.status)
        other_live = _tree_bytes(self.project)
        stale = self._run(other, stolen)
        self.assertEqual(PIPELINE_APPROVAL_INVALID, stale.status)
        self.assertEqual(other_live, _tree_bytes(self.project))


class TestingIsolationAttackTest(AdversarialHarness):
    def test_test_proposal_never_mutates_live_project(self) -> None:
        before = self._snap()
        attacks = (
            _proposal(("astra/hello.py", "# session-only\n")),
            _proposal(("../outside.py", "nope\n")),
            _proposal(("data/store.txt", "nope\n")),
            _proposal(("logs/app.log", "nope\n")),
            _proposal(("astra/hello.py", "# live?\n")),
        )
        (self.project / "data").mkdir(exist_ok=True)
        (self.project / "data" / "store.txt").write_bytes(b"# data\n")
        (self.project / "logs").mkdir(exist_ok=True)
        (self.project / "logs" / "app.log").write_bytes(b"# logs\n")
        before = self._snap()
        for proposal in attacks:
            with self.subTest(path=str(proposal.changes[0].path)):
                report = self.testing.test_proposal(proposal)
                self.assertIsInstance(report, TestReport)
                self._assert_live(before)
                self.assertFalse((Path(self._tmp.name) / "outside.py").exists())
        leftover = list(self.scratch.iterdir())
        self.assertEqual([], leftover)


class ProcessRunnerAttackTest(AdversarialHarness):
    def setUp(self) -> None:
        super().setUp()
        self.copied = self.workspace.copy()
        self.assertTrue(self.copied.success)
        self.session = self.copied.subset_root
        assert self.session is not None
        self.addCleanup(self.workspace.discard_all)

    def _run_spec(self, spec: ProcessSpec, session_id: str | None = None):
        sid = self.copied.session_id if session_id is None else session_id
        return self.runner.run(spec, session_id=sid)

    def test_forbidden_executables_and_cwds_do_not_spawn(self) -> None:
        before = self._snap()
        session = self.session
        assert session is not None
        attacks = [
            ProcessSpec(argv=("cmd.exe", "/c", "dir"), cwd=session, timeout_seconds=5),
            ProcessSpec(
                argv=("powershell.exe", "-Command", "Get-Date"),
                cwd=session,
                timeout_seconds=5,
            ),
            ProcessSpec(
                argv=(sys.executable, "-c", "print(1)"),
                cwd=session,
                timeout_seconds=5,
            ),
            ProcessSpec(
                argv=(sys.executable, "-B", "-s", "-m", "astra.testing.probe"),
                cwd=self.project,
                timeout_seconds=5,
            ),
            ProcessSpec(
                argv=(sys.executable, "-B", "-s", "-m", "astra.testing.probe"),
                cwd=self.scratch,
                timeout_seconds=5,
            ),
            ProcessSpec(argv=(), cwd=session, timeout_seconds=5),
            ProcessSpec(argv=("",), cwd=session, timeout_seconds=5),
            ProcessSpec(
                argv=(sys.executable, "-B", "-s", "-m", "astra.testing.probe"),
                cwd=session,
                timeout_seconds=0,
            ),
            ProcessSpec(
                argv=(sys.executable, "-B", "-s", "-m", "astra.testing.probe"),
                cwd=session,
                timeout_seconds=-1,
            ),
        ]
        for spec in attacks:
            with self.subTest(argv=spec.argv, cwd=str(spec.cwd), timeout=spec.timeout_seconds):
                result = self._run_spec(spec)
                self.assertFalse(result.spawned)
                self.assertNotEqual(ProcessStatus.EXITED, result.status)
        other = self.workspace.copy()
        self.assertTrue(other.success)
        sibling = self._run_spec(
            ProcessSpec(
                argv=(sys.executable, "-B", "-s", "-m", "astra.testing.probe"),
                cwd=other.subset_root,
                timeout_seconds=5,
            )
        )
        self.assertFalse(sibling.spawned)
        missing = self._run_spec(
            ProcessSpec(
                argv=(sys.executable, "-B", "-s", "-m", "astra.testing.probe"),
                cwd=session,
                timeout_seconds=5,
            ),
            session_id="missing",
        )
        self.assertFalse(missing.spawned)
        self._assert_live(before)

    def test_inherited_env_timeout_output_nonzero_spawn_failure(self) -> None:
        before = self._snap()
        session = self.session
        assert session is not None
        overlaid = self.workspace.overlay(
            self.copied.session_id,
            _proposal(
                (
                    "env_probe.py",
                    "import os\nprint('OK=' + os.environ.get('OPENAI_API_KEY', ''))\n",
                )
            ),
        )
        self.assertTrue(overlaid.success)
        with patch.dict(os.environ, {"OPENAI_API_KEY": "secret"}, clear=False):
            result = self._run_spec(
                ProcessSpec(
                    argv=(sys.executable, "-B", "-s", "env_probe.py"),
                    cwd=session,
                    env=(("OPENAI_API_KEY", "from-spec"),),
                    timeout_seconds=DEFAULT_PROBE_TIMEOUT,
                )
            )
        self.assertTrue(result.spawned)
        self.assertIn("OK=", result.stdout)
        self.assertNotIn("secret", result.stdout)
        self.assertNotIn("from-spec", result.stdout)

        flood = self.workspace.overlay(
            self.copied.session_id,
            _proposal(("flood.py", "import sys\nsys.stdout.write('A' * 70000)\n")),
        )
        self.assertTrue(flood.success)
        bounded = self._run_spec(
            ProcessSpec(
                argv=(sys.executable, "-B", "-s", "flood.py"),
                cwd=session,
                timeout_seconds=DEFAULT_PROBE_TIMEOUT,
            )
        )
        self.assertTrue(bounded.stdout_truncated)
        self.assertLessEqual(len(bounded.stdout.encode("utf-8")), MAX_OUTPUT_BYTES)

        sleepy = self.workspace.overlay(
            self.copied.session_id,
            _proposal(("sleep.py", "import time\ntime.sleep(30)\n")),
        )
        self.assertTrue(sleepy.success)
        timed = self._run_spec(
            ProcessSpec(
                argv=(sys.executable, "-B", "-s", "sleep.py"),
                cwd=session,
                timeout_seconds=1.0,
            )
        )
        self.assertTrue(timed.timed_out)

        failing = self.workspace.overlay(
            self.copied.session_id,
            _proposal(("fail.py", "raise SystemExit(2)\n")),
        )
        self.assertTrue(failing.success)
        exited = self._run_spec(
            ProcessSpec(
                argv=(sys.executable, "-B", "-s", "fail.py"),
                cwd=session,
                timeout_seconds=DEFAULT_PROBE_TIMEOUT,
            )
        )
        self.assertEqual(2, exited.exit_code)
        self.assertIs(ProcessStatus.EXITED, exited.status)

        with patch("astra.testing.runner.subprocess.Popen", side_effect=OSError("boom")):
            boom = self._run_spec(
                ProcessSpec(
                    argv=(sys.executable, "-B", "-s", "fail.py"),
                    cwd=session,
                    timeout_seconds=DEFAULT_PROBE_TIMEOUT,
                )
            )
        self.assertIs(ProcessStatus.SPAWN_FAILURE, boom.status)
        self._assert_live(before)


class TestingAgentHonestyTest(AdversarialHarness):
    def test_false_passing_reports_are_rejected(self) -> None:
        before = self._snap()
        fake = TestReport(
            command="unittest",
            tests_run=3,
            tests_passed=3,
            incomplete=True,
        )
        with patch.object(self.runner, "run", return_value=_exited("not unittest")):
            malformed = self.testing.test_proposal(self.change)
        self.assertTrue(malformed.incomplete)
        self.assertFalse(malformed.passed)

        with patch.object(self.runner, "run", return_value=_exited("Ran 0 tests in 0.000s\n\nOK\n")):
            zero = self.testing.test_proposal(self.change)
        self.assertFalse(zero.passed)
        self.assertEqual(0, zero.tests_run)

        truncated = TestReport(
            tests_run=2, tests_passed=2, incomplete=True, process_reason="truncated"
        )
        self.assertFalse(truncated.passed)

        sleeper = LocalTestingAgent(
            subset=self.workspace, runner=self.runner, timeout_seconds=1.0
        )
        timed = sleeper.test_proposal(
            _proposal(
                (
                    "tests/test_sleep.py",
                    "import time\nimport unittest\n"
                    "class SleepTest(unittest.TestCase):\n"
                    "    def test_sleep(self) -> None:\n"
                    "        time.sleep(30)\n",
                )
            )
        )
        self.assertTrue(timed.timed_out)
        self.assertFalse(timed.passed)

        failed = self.testing.test_proposal(_proposal(("tests/test_fail.py", _FAILING)))
        self.assertFalse(failed.passed)
        self.assertGreaterEqual(failed.tests_failed, 1)
        self.assertNotEqual(0, failed.exit_code)

        with patch.object(self.runner, "run", return_value=_exited("", spawned=False)):
            spawn = self.testing.test_proposal(self.change)
        self.assertTrue(spawn.spawn_failed or spawn.incomplete)
        self.assertFalse(spawn.passed)
        self._assert_live(before)


def _exited(stdout: str, *, spawned: bool = True):
    from astra.testing.runner import ProcessResult

    return ProcessResult(
        spawned=spawned,
        success=spawned,
        status=ProcessStatus.EXITED if spawned else ProcessStatus.SPAWN_FAILURE,
        exit_code=0 if spawned else None,
        stdout=stdout,
    )


class EvaluationAttackTest(AdversarialHarness):
    def test_evaluation_never_authorizes_deployment(self) -> None:
        before = self._snap()
        agent = LocalEvaluationAgent()
        proposal = self.change
        missing = agent.evaluate_proposal(proposal, None)  # type: ignore[arg-type]
        self.assertFalse(missing.evaluation_passed)
        self.assertFalse(missing.deployment_authorized)

        incomplete = agent.evaluate_proposal(
            proposal, self._passing_tests(incomplete=True)
        )
        self.assertFalse(incomplete.evaluation_passed)
        self.assertFalse(incomplete.deployment_authorized)

        failed = agent.evaluate_proposal(
            proposal, self._passing_tests(tests_passed=0, tests_failed=1)
        )
        self.assertFalse(failed.evaluation_passed)
        self.assertFalse(failed.deployment_authorized)

        malformed = agent.evaluate_proposal(proposal, object())  # type: ignore[arg-type]
        self.assertFalse(malformed.evaluation_passed)
        self.assertFalse(malformed.deployment_authorized)

        store = LocalBaselineStore(
            security=self.security, store_root=self.scratch / "baselines"
        )
        ev = LocalEvaluationAgent(baselines=store)
        high = MetricSample(
            property=MeasurableProperty.PERFORMANCE,
            metric="score",
            value=10.0,
            higher_is_better=True,
        )
        recorded = ev.record_baseline(
            "adversarial overlay",
            EvaluationReport(
                subject="adversarial overlay",
                samples=(high,),
                implementation="base",
            ),
        )
        self.assertTrue(recorded.recorded)
        empty = ev.record_baseline(
            "empty subject",
            EvaluationReport(subject="empty subject", samples=(), implementation="empty"),
        )
        empty_eval = ev.evaluate("empty subject", candidate=())
        self.assertFalse(empty_eval.evaluation_passed)
        self.assertFalse(empty_eval.deployment_authorized)
        self.assertFalse(empty_eval.improved)
        worse = MetricSample(
            property=MeasurableProperty.PERFORMANCE,
            metric="score",
            value=1.0,
            higher_is_better=True,
        )
        compared = ev.evaluate_proposal(
            proposal,
            self._passing_tests(),
            baseline_id=baseline_id_for("adversarial overlay"),
            candidate=(worse,),
        )
        self.assertFalse(compared.evaluation_passed)
        self.assertFalse(compared.deployment_authorized)

        forged = EvaluationReport(
            subject="adversarial overlay",
            tests_passed=True,
            evaluation_passed=True,
            deployment_authorized=True,
        )
        with (
            patch.object(self.evaluation, "evaluate_proposal", return_value=forged),
            patch.object(self.deployment, "deploy") as dep,
        ):
            result = self._run(self.change)
        self.assertEqual(PIPELINE_APPROVAL_MISSING, result.status)
        dep.assert_not_called()
        self._assert_live(before)


class DeploymentAttackTest(AdversarialHarness):
    def test_failed_eval_missing_invalid_deny_allow_partial(self) -> None:
        before = self._snap()
        tests = self._passing_tests()
        failed_eval = self._eval_ok(evaluation_passed=False)
        approval = ApprovalRecord(
            subject=evidence_fingerprint(self.change, tests, failed_eval),
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )
        self.assertFalse(
            self.deployment.deploy(self.change, tests, failed_eval, approval).deployed
        )
        self._assert_live(before)

        ok_eval = self._eval_ok()
        self.assertFalse(
            self.deployment.deploy(self.change, tests, ok_eval, None).deployed  # type: ignore[arg-type]
        )
        self._assert_live(before)

        bad = ApprovalRecord(
            subject="nope",
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )
        self.assertFalse(self.deployment.deploy(self.change, tests, ok_eval, bad).deployed)
        self._assert_live(before)

        deny_agent = LocalDeploymentAgent(
            security=_ForcePermanent(Verdict.DENY), project_root=self.project
        )
        good = ApprovalRecord(
            subject=evidence_fingerprint(self.change, tests, ok_eval),
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )
        denied = deny_agent.deploy(self.change, tests, ok_eval, good)
        self.assertEqual(STATUS_SECURITY_DENIED, denied.status)
        self._assert_live(before)

        allow_agent = LocalDeploymentAgent(
            security=_ForcePermanent(Verdict.ALLOW), project_root=self.project
        )
        allowed = allow_agent.deploy(self.change, tests, ok_eval, good)
        self.assertEqual(STATUS_SECURITY_DENIED, allowed.status)
        self.assertFalse(allowed.deployed)
        self._assert_live(before)

        with patch(
            "astra.deployment.agent.commit_files",
            return_value=((self.project / "astra" / "hello.py",), "disk full"),
        ):
            partial = self.deployment.deploy(self.change, tests, ok_eval, good)
        self.assertEqual(STATUS_DEPLOYMENT_ERROR, partial.status)
        self.assertFalse(partial.deployed)
        self.assertNotEqual(STATUS_DEPLOYED, partial.status)
        self._assert_live(before)


class RollbackAndVersionAttackTest(AdversarialHarness):
    def test_rollback_and_version_manager_fail_closed(self) -> None:
        preview = self._run(self.change)
        deployed = self._run(self.change, self._approve(preview, self.change))
        self.assertTrue(deployed.deployed)
        assert deployed.deployment is not None
        version = deployed.deployment.version
        live = self._snap()

        missing = self.deployment.rollback(version, None)  # type: ignore[arg-type]
        self.assertFalse(missing.rolled_back)
        self.assertEqual(live, _tree_bytes(self.project))

        wrong = ApprovalRecord(
            subject="not-rollback",
            approved_by="tester",
            approved_at=datetime.now(timezone.utc),
        )
        bad = self.deployment.rollback(version, wrong)
        self.assertFalse(bad.rolled_back)
        self.assertEqual(live, _tree_bytes(self.project))

        unknown = self.deployment.rollback(
            "d-deadbeefdeadbeef-0001",
            ApprovalRecord(
                subject=rollback_fingerprint("d-deadbeefdeadbeef-0001"),
                approved_by="tester",
                approved_at=datetime.now(timezone.utc),
            ),
        )
        self.assertFalse(unknown.rolled_back)
        self.assertEqual(live, _tree_bytes(self.project))

        blob = (
            self.scratch / "versions" / version / "files" / "astra" / "hello.py"
        )
        original_blob = blob.read_bytes()
        blob.write_bytes(b"# tampered\n")
        corrupt = self.deployment.rollback(
            version,
            ApprovalRecord(
                subject=rollback_fingerprint(version),
                approved_by="tester",
                approved_at=datetime.now(timezone.utc),
            ),
        )
        self.assertFalse(corrupt.rolled_back)
        self.assertEqual(live, _tree_bytes(self.project))
        blob.write_bytes(original_blob)

        manifest = self.scratch / "versions" / version / "manifest.json"
        raw = json.loads(manifest.read_text(encoding="utf-8"))
        raw["files"][0]["path"] = "../escape.py"
        manifest.write_text(json.dumps(raw), encoding="utf-8")
        outside = self.project.parent / "escape.py"
        traversed = self.deployment.rollback(
            version,
            ApprovalRecord(
                subject=rollback_fingerprint(version),
                approved_by="tester",
                approved_at=datetime.now(timezone.utc),
            ),
        )
        self.assertFalse(traversed.rolled_back)
        self.assertFalse(outside.exists())
        self.assertEqual(live, _tree_bytes(self.project))

        existing = self.versions.get(version)
        self.assertIsNotNone(existing)
        with self.assertRaises(ValueError):
            self.versions.record(existing)  # type: ignore[arg-type]
        self.assertEqual(live, _tree_bytes(self.project))

        import shutil

        shutil.rmtree(self.scratch / "versions" / version, ignore_errors=True)
        deleted = self.deployment.rollback(
            version,
            ApprovalRecord(
                subject=rollback_fingerprint(version),
                approved_by="tester",
                approved_at=datetime.now(timezone.utc),
            ),
        )
        self.assertFalse(deleted.rolled_back)
        self.assertEqual(live, _tree_bytes(self.project))
        self.assertNotEqual(STATUS_ROLLED_BACK, deleted.status)


class PipelineStageStopTest(AdversarialHarness):
    def test_every_failure_stops_later_stages_and_success_writes_only_target(self) -> None:
        before = self._snap()
        with (
            patch.object(self.evaluation, "evaluate_proposal") as ev,
            patch.object(self.deployment, "deploy") as dep,
        ):
            failed = self._run(_proposal(("tests/test_fail.py", _FAILING)))
        self.assertEqual(PIPELINE_TESTING_FAILED, failed.status)
        ev.assert_not_called()
        dep.assert_not_called()
        self._assert_live(before)

        forced = EvaluationReport(
            subject="adversarial overlay",
            tests_passed=True,
            evaluation_passed=False,
            deployment_authorized=False,
        )
        with (
            patch.object(self.evaluation, "evaluate_proposal", return_value=forced),
            patch.object(self.deployment, "deploy") as dep,
        ):
            eval_fail = self._run(self.change)
        self.assertEqual(PIPELINE_EVALUATION_FAILED, eval_fail.status)
        dep.assert_not_called()
        self._assert_live(before)

        missing = self._run(self.change)
        self.assertEqual(PIPELINE_APPROVAL_MISSING, missing.status)
        self._assert_live(before)

        invalid = self._run(
            self.change,
            ApprovalRecord(
                subject="stale",
                approved_by="tester",
                approved_at=datetime.now(timezone.utc),
            ),
        )
        self.assertEqual(PIPELINE_APPROVAL_INVALID, invalid.status)
        self._assert_live(before)

        preview = self._run(self.change)
        ok = self._run(self.change, self._approve(preview, self.change))
        self.assertEqual(PIPELINE_DEPLOYED, ok.status)
        self.assertEqual(b"# after\n", (self.project / "astra" / "hello.py").read_bytes())
        self.assertEqual(b"# keep\n", (self.project / "keep.py").read_bytes())
        self.assertEqual(b"# astra-marker\n", (self.project / "astra" / "__init__.py").read_bytes())


class InvariantAttackTest(AdversarialHarness):
    def test_security_tools_schema_and_process_boundaries(self) -> None:
        from astra.state import ApplicationState

        self.assertIsNot(
            Verdict.ALLOW,
            self.security.check_permanent_change("adversarial").verdict,
        )
        temp = self.security.check_temporary_write(self.scratch / "x.py")
        self.assertIs(Verdict.ALLOW, temp.verdict)
        self.assertIsNot(
            Verdict.ALLOW,
            self.security.check_temporary_write(self.project / "astra" / "hello.py").verdict,
        )
        self.assertEqual(
            frozenset({"read_text", "read_project"}),
            PRODUCTION_EXECUTION_ALLOWLIST,
        )
        self.assertEqual(6, ApplicationState().schema_version)
        names = {field.name for field in dataclasses.fields(ToolSpec)}
        self.assertNotIn("process", names)
        self.assertNotIn("process_capability", names)

        root = Path(__file__).parent.parent / "astra"
        for package, extra in (
            ("coding", ("ProcessRunner", "subprocess")),
            ("evaluation", ("ProcessRunner", "subprocess")),
            ("deployment", ("ProcessRunner", "subprocess")),
        ):
            for py_file in (root / package).glob("*.py"):
                tree = ast.parse(py_file.read_text(encoding="utf-8"))
                imported: list[str] = []
                for node in ast.walk(tree):
                    if isinstance(node, ast.Import):
                        imported.extend(alias.name.split(".")[0] for alias in node.names)
                    elif isinstance(node, ast.ImportFrom) and node.module:
                        imported.append(node.module.split(".")[0])
                if package == "deployment" and py_file.name == "gitrun.py":
                    self.assertIn("subprocess", imported)
                    text = py_file.read_text(encoding="utf-8")
                    self.assertNotIn("ProcessRunner", text)
                    continue
                self.assertNotIn("subprocess", imported, py_file.name)
                text = py_file.read_text(encoding="utf-8")
                if extra[0] == "ProcessRunner" and package != "testing":
                    self.assertNotIn("ProcessRunner", text, py_file.name)
        agent_src = (root / "testing" / "agent.py").read_text(encoding="utf-8")
        tree = ast.parse(agent_src)
        imported = []
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.extend(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imported.append(node.module.split(".")[0])
        self.assertNotIn("subprocess", imported)
        testing_root = root / "testing"
        for py_file in testing_root.glob("*.py"):
            tree = ast.parse(py_file.read_text(encoding="utf-8"))
            names_imported: list[str] = []
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    names_imported.extend(alias.name.split(".")[0] for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names_imported.append(node.module.split(".")[0])
            if py_file.name == "runner.py":
                self.assertIn("subprocess", names_imported)
            else:
                self.assertNotIn("subprocess", names_imported, py_file.name)
        presentation = (root / "presentation" / "provider.py").read_text(encoding="utf-8")
        self.assertIn("nvidia-smi", presentation)
        self.assertNotIn("nvidia-smi", agent_src)
        git_hits = list(root.rglob("*.py"))
        for py_file in git_hits:
            text = py_file.read_text(encoding="utf-8")
            tree = ast.parse(text)
            imported = []
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    imported.extend(alias.name for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    imported.append(node.module)
            self.assertNotIn("git", imported, py_file.name)
            self.assertNotIn("pygit2", imported, py_file.name)

    def test_coding_remains_write_free(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "coding"
        for py_file in root.glob("*.py"):
            text = py_file.read_text(encoding="utf-8")
            self.assertNotIn("write_text", text)
            self.assertNotIn("write_bytes", text)

    def test_milestone_check_is_behavioral(self) -> None:
        import inspect

        from astra.presentation import progress

        src = inspect.getsource(progress._check_end_to_end_safety)
        self.assertIn("process_change_proposal", src)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_end_to_end_safety())

    def test_adr_0031_exists_and_0030_untouched(self) -> None:
        root = Path(__file__).parent.parent / "docs" / "adr"
        adr31 = (root / "ADR-0031-adversarial-end-to-end-safety-hardening.md").read_text(
            encoding="utf-8"
        )
        lowered = adr31.lower()
        self.assertIn("not an os sandbox", lowered)
        self.assertIn("human approval remains the only permanent-change authority", lowered)
        self.assertIn("git is intentionally still absent", lowered)
        adr30 = (root / "ADR-0030-end-to-end-controlled-change-pipeline.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("implemented in B-031", adr30)


if __name__ == "__main__":
    unittest.main()
