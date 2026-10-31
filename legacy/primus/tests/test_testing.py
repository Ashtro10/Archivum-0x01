"""Proposal testing agent tests (B-025 / B-030, ADR-0024 / ADR-0029).

Honest unittest in a copied session. No live-tree mutation. No TestReport fakes.
run_suite copies an unchanged snapshot; test_proposal overlays then tests.
"""

from __future__ import annotations

import ast
import inspect
import socket
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from astra.coding.contracts import ChangeProposal, FileChange
from astra.core.lifecycle import MeasurableProperty
from astra.security.agent import PolicySecurityAgent
from astra.security.contracts import Verdict
from astra.testing.agent import LocalTestingAgent
from astra.testing.contracts import TestingAgent, TestOutcome, TestReport
from astra.testing.report import parse_unittest_output
from astra.testing.runner import ProcessResult, ProcessRunner, ProcessStatus
from astra.testing.subset import CopiedSubsetWorkspace
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST


def _proposal(path: str, body: str) -> ChangeProposal:
    return ChangeProposal(
        objective="test overlay",
        changes=(FileChange(path=Path(path), new_content=body, rationale="test"),),
        improves=(MeasurableProperty.MAINTAINABILITY,),
    )


def _write_mini(project: Path, test_src: str | None = None) -> None:
    (project / "astra").mkdir()
    (project / "astra" / "__init__.py").write_bytes(b"# astra-marker\n")
    (project / "tests").mkdir()
    (project / "tests" / "__init__.py").write_bytes(b"")
    if test_src is not None:
        (project / "tests" / "test_ok.py").write_text(test_src, encoding="utf-8")
    (project / "ui").mkdir()
    (project / "ui" / "app.py").write_bytes(b"# ui\n")
    (project / "CONSTITUTION.md").write_bytes(b"# constitution-marker\n")


_PASSING = (
    "import unittest\n"
    "class OkTest(unittest.TestCase):\n"
    "    def test_ok(self) -> None:\n"
    "        self.assertTrue(True)\n"
)
_PASSING_EXTRA = (
    "import unittest\n"
    "class ExtraTest(unittest.TestCase):\n"
    "    def test_extra(self) -> None:\n"
    "        self.assertEqual(1, 1)\n"
)
_FAILING = (
    "import unittest\n"
    "class FailTest(unittest.TestCase):\n"
    "    def test_fail(self) -> None:\n"
    "        self.assertEqual(1, 2)\n"
)
_ERRORING = (
    "import unittest\n"
    "class ErrTest(unittest.TestCase):\n"
    "    def test_err(self) -> None:\n"
    "        raise RuntimeError('boom')\n"
)
_SKIPPING = (
    "import unittest\n"
    "class SkipTest(unittest.TestCase):\n"
    "    def test_ok(self) -> None:\n"
    "        self.assertTrue(True)\n"
    "    def test_skip(self) -> None:\n"
    "        self.skipTest('skip-me')\n"
)
_SLEEPING = (
    "import time\n"
    "import unittest\n"
    "class SleepTest(unittest.TestCase):\n"
    "    def test_sleep(self) -> None:\n"
    "        time.sleep(30)\n"
)


class UnittestParserTest(unittest.TestCase):
    def test_ok_summary(self) -> None:
        report = parse_unittest_output(stdout="Ran 3 tests in 0.01s\n\nOK\n")
        self.assertEqual(3, report.tests_run)
        self.assertEqual(3, report.tests_passed)
        self.assertTrue(report.passed)

    def test_ok_with_skips(self) -> None:
        report = parse_unittest_output(
            stdout="Ran 4 tests in 0.01s\n\nOK (skipped=1)\n"
        )
        self.assertEqual(4, report.tests_run)
        self.assertEqual(1, report.tests_skipped)
        self.assertEqual(3, report.tests_passed)
        self.assertTrue(report.passed)

    def test_failed_summary(self) -> None:
        text = (
            "FAIL: test_fail (tests.test_fail.FailTest.test_fail)\n"
            "Ran 2 tests in 0.01s\n\n"
            "FAILED (failures=1)\n"
        )
        report = parse_unittest_output(stdout=text, exit_code=1)
        self.assertEqual(2, report.tests_run)
        self.assertEqual(1, report.tests_failed)
        self.assertFalse(report.passed)
        self.assertEqual(1, len(report.failures))
        self.assertEqual(1, report.exit_code)

    def test_empty_run_is_not_pass(self) -> None:
        report = parse_unittest_output(stdout="Ran 0 tests in 0.000s\n\nOK\n")
        self.assertEqual(0, report.tests_run)
        self.assertFalse(report.passed)

    def test_zero_run_with_fake_outcomes_is_not_pass(self) -> None:
        report = TestReport(
            outcomes=(TestOutcome("fake.test", True),),
            tests_run=0,
            tests_passed=0,
            exit_code=0,
        )
        self.assertFalse(report.passed)

    def test_unparseable_is_not_pass(self) -> None:
        report = parse_unittest_output(stdout="garbage", exit_code=0)
        self.assertTrue(report.incomplete)
        self.assertFalse(report.passed)

    def test_truncated_is_not_pass(self) -> None:
        report = parse_unittest_output(
            stdout="Ran 3 tests in 0.01s\n\nOK\n", incomplete=True
        )
        self.assertFalse(report.passed)

    def test_timeout_is_not_pass(self) -> None:
        report = parse_unittest_output(stdout="", timed_out=True)
        self.assertTrue(report.timed_out)
        self.assertFalse(report.passed)

    def test_spawn_failure_is_not_pass(self) -> None:
        report = parse_unittest_output(stdout="", spawn_failed=True)
        self.assertTrue(report.spawn_failed)
        self.assertFalse(report.passed)


class LocalTestingAgentTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.project = Path(self._tmp.name) / "project"
        self.scratch = Path(self._tmp.name) / "scratch"
        self.data = Path(self._tmp.name) / "data"
        self.project.mkdir()
        self.scratch.mkdir()
        self.data.mkdir()
        _write_mini(self.project, _PASSING)
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
        self.agent = LocalTestingAgent(
            subset=self.workspace, runner=self.runner, timeout_seconds=15.0
        )

    def test_passing_proposal_runs_unittest(self) -> None:
        before = (self.project / "astra" / "__init__.py").read_bytes()
        report = self.agent.test_proposal(_proposal("tests/test_extra.py", _PASSING_EXTRA))
        self.assertIsInstance(report, TestReport)
        self.assertTrue(report.passed)
        self.assertGreaterEqual(report.tests_run, 2)
        self.assertEqual(0, report.tests_failed)
        self.assertEqual(before, (self.project / "astra" / "__init__.py").read_bytes())
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_failing_proposal_records_failure(self) -> None:
        report = self.agent.test_proposal(_proposal("tests/test_fail.py", _FAILING))
        self.assertFalse(report.passed)
        self.assertGreaterEqual(report.tests_failed, 1)
        self.assertFalse(report.incomplete)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_error_proposal_records_error(self) -> None:
        report = self.agent.test_proposal(_proposal("tests/test_err.py", _ERRORING))
        self.assertFalse(report.passed)
        self.assertGreaterEqual(report.tests_errors, 1)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_skipped_test_is_honest(self) -> None:
        report = self.agent.test_proposal(_proposal("tests/test_skip.py", _SKIPPING))
        self.assertTrue(report.passed)
        self.assertGreaterEqual(report.tests_skipped, 1)
        self.assertGreaterEqual(report.tests_run, 2)

    def test_empty_suite_is_not_passing(self) -> None:
        empty = Path(self._tmp.name) / "empty_project"
        empty.mkdir()
        _write_mini(empty, None)
        workspace = CopiedSubsetWorkspace(
            security=self.security, scratch_root=self.scratch, project_root=empty
        )
        runner = ProcessRunner(security=self.security, subset=workspace)
        agent = LocalTestingAgent(subset=workspace, runner=runner, timeout_seconds=15.0)
        report = agent.test_proposal(_proposal("tests/test_none.py", "# no tests\n"))
        self.assertFalse(report.passed)
        self.assertEqual(0, report.tests_run)

    def test_malformed_output_is_not_passing(self) -> None:
        fake = ProcessResult(
            spawned=True,
            success=True,
            status=ProcessStatus.EXITED,
            exit_code=0,
            stdout="not unittest output",
        )
        with patch.object(self.runner, "run", return_value=fake):
            report = self.agent.test_proposal(
                _proposal("tests/test_extra.py", _PASSING_EXTRA)
            )
        self.assertFalse(report.passed)
        self.assertTrue(report.incomplete)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_timeout_is_not_passing(self) -> None:
        agent = LocalTestingAgent(
            subset=self.workspace, runner=self.runner, timeout_seconds=1.0
        )
        report = agent.test_proposal(_proposal("tests/test_sleep.py", _SLEEPING))
        self.assertFalse(report.passed)
        self.assertTrue(report.timed_out)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_spawn_failure_is_not_passing(self) -> None:
        with patch("astra.testing.runner.subprocess.Popen", side_effect=OSError("boom")):
            report = self.agent.test_proposal(
                _proposal("tests/test_extra.py", _PASSING_EXTRA)
            )
        self.assertFalse(report.passed)
        self.assertTrue(report.spawn_failed)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_nonzero_exit_uses_unittest_counts(self) -> None:
        report = self.agent.test_proposal(_proposal("tests/test_fail.py", _FAILING))
        self.assertIsNotNone(report.exit_code)
        self.assertNotEqual(0, report.exit_code)
        self.assertGreaterEqual(report.tests_failed, 1)
        self.assertGreaterEqual(report.tests_run, 2)
        self.assertFalse(report.passed)

    def test_abc_methods_remain_abstract(self) -> None:
        with self.assertRaises(NotImplementedError):
            TestingAgent.run_suite(object())  # type: ignore[misc]
        with self.assertRaises(NotImplementedError):
            TestingAgent.test_proposal(object(), _proposal("x.py", "x\n"))  # type: ignore[misc]

    def test_allowlist_and_schema_and_permanent(self) -> None:
        from astra.state import ApplicationState

        self.assertEqual(
            frozenset({"read_text", "read_project"}),
            PRODUCTION_EXECUTION_ALLOWLIST,
        )
        self.assertEqual(6, ApplicationState().schema_version)
        self.assertIsNot(
            Verdict.ALLOW,
            self.security.check_permanent_change("apply tests").verdict,
        )


class TestingIsolationTest(unittest.TestCase):
    def test_agent_and_report_have_no_subprocess(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "testing"
        for py_file in root.glob("*.py"):
            text = py_file.read_text(encoding="utf-8")
            self.assertNotIn("os.system", text)
            tree = ast.parse(text)
            names: list[str] = []
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    names.extend(alias.name.split(".")[0] for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names.append(node.module.split(".")[0])
            if py_file.name == "runner.py":
                self.assertIn("subprocess", names)
            else:
                self.assertNotIn("subprocess", names, py_file.name)

    def test_coding_still_has_no_write(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "coding"
        for py_file in root.glob("*.py"):
            text = py_file.read_text(encoding="utf-8")
            self.assertNotIn("write_text", text)
            self.assertNotIn("subprocess", text)

    def test_offline(self) -> None:
        def _block(*_a: object, **_k: object) -> None:
            raise RuntimeError("network blocked")

        with patch.object(socket, "socket", side_effect=_block):
            tmp = tempfile.TemporaryDirectory()
            self.addCleanup(tmp.cleanup)
            project = Path(tmp.name) / "p"
            scratch = Path(tmp.name) / "s"
            project.mkdir()
            scratch.mkdir()
            _write_mini(project, _PASSING)
            agent_sec = PolicySecurityAgent(
                scratch_root=scratch,
                project_roots=(project,),
                process_executables=(Path(sys.executable),),
            )
            workspace = CopiedSubsetWorkspace(
                security=agent_sec, scratch_root=scratch, project_root=project
            )
            runner = ProcessRunner(security=agent_sec, subset=workspace)
            testing = LocalTestingAgent(
                subset=workspace, runner=runner, timeout_seconds=15.0
            )
            report = testing.test_proposal(_proposal("tests/test_extra.py", _PASSING_EXTRA))
        self.assertTrue(report.passed)

    def test_milestone_check_not_hard_coded_true(self) -> None:
        from astra.presentation import progress

        src = inspect.getsource(progress._check_proposal_testing)
        self.assertIn("LocalTestingAgent", src)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_proposal_testing())
        suite_src = inspect.getsource(progress._check_run_suite)
        self.assertIn("run_suite", suite_src)
        self.assertNotIn("return True", suite_src)
        self.assertTrue(progress._check_run_suite())


class ControllerTestingAgentTest(unittest.TestCase):
    def test_testing_agent_is_controller_owned(self) -> None:
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
            _ = ctrl.testing
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        self.assertIn("testing", ctrl.agent_names)
        self.assertIsInstance(ctrl.testing, LocalTestingAgent)
        self.assertIsNot(ctrl.testing, ctrl.coding)
        self.assertTrue(callable(ctrl.testing.run_suite))
        state = ctrl.get_state()
        self.assertEqual(6, state.schema_version)
        testing = next(m for m in state.modules if m.module == "astra.testing")
        cap = next(c for c in testing.capabilities if c.name == "test_proposal")
        self.assertEqual(CapabilityStatus.OPERATIONAL, cap.status)
        run_suite = next(c for c in testing.capabilities if c.name == "run_suite")
        self.assertEqual(CapabilityStatus.OPERATIONAL, run_suite.status)
        self.assertIs(False, state.coding.applied)
        ctrl.shutdown()
        with self.assertRaises(ControllerNotStartedError):
            _ = ctrl.testing


class RunSuiteTest(unittest.TestCase):
    """B-030: run_suite copies an unchanged snapshot and runs unittest there."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.project = Path(self._tmp.name) / "project"
        self.scratch = Path(self._tmp.name) / "scratch"
        self.data = Path(self._tmp.name) / "data"
        self.project.mkdir()
        self.scratch.mkdir()
        self.data.mkdir()
        _write_mini(self.project, _PASSING)
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
        self.agent = LocalTestingAgent(
            subset=self.workspace, runner=self.runner, timeout_seconds=15.0
        )

    def _live_bytes(self) -> dict[str, bytes]:
        return {
            "init": (self.project / "astra" / "__init__.py").read_bytes(),
            "ok": (self.project / "tests" / "test_ok.py").read_bytes(),
            "constitution": (self.project / "CONSTITUTION.md").read_bytes(),
        }

    def test_run_suite_executes_copied_tree_not_project_root(self) -> None:
        before = self._live_bytes()
        seen_cwd: list[Path] = []
        real_run = self.runner.run

        def _capture(spec, *, session_id: str = ""):
            cwd = Path(getattr(spec, "cwd"))
            seen_cwd.append(cwd)
            self.assertTrue((cwd / "tests" / "test_ok.py").is_file())
            self.assertNotEqual(cwd.resolve(), self.project.resolve())
            self.assertTrue(str(cwd.resolve()).startswith(str(self.scratch.resolve())))
            return real_run(spec, session_id=session_id)

        self.runner.run = _capture  # type: ignore[method-assign]
        report = self.agent.run_suite()
        self.assertIsInstance(report, TestReport)
        self.assertTrue(report.passed)
        self.assertGreaterEqual(report.tests_run, 1)
        self.assertFalse(report.incomplete)
        self.assertEqual(1, len(seen_cwd))
        self.assertEqual(before, self._live_bytes())
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_injected_session_taint_does_not_alter_live_project(self) -> None:
        before = self._live_bytes()
        real_copy = self.workspace.copy

        def _tainted_copy():
            copied = real_copy()
            if getattr(copied, "success", False):
                dest = Path(copied.subset_root) / "tests" / "test_taint.py"
                dest.write_text(_FAILING, encoding="utf-8")
            return copied

        self.workspace.copy = _tainted_copy  # type: ignore[method-assign]
        report = self.agent.run_suite()
        self.assertFalse(report.passed)
        self.assertGreaterEqual(report.tests_failed, 1)
        self.assertFalse((self.project / "tests" / "test_taint.py").exists())
        self.assertEqual(before, self._live_bytes())
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_session_discarded_after_failure(self) -> None:
        (self.project / "tests" / "test_ok.py").write_text(_FAILING, encoding="utf-8")
        report = self.agent.run_suite()
        self.assertFalse(report.passed)
        self.assertGreaterEqual(report.tests_failed, 1)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_session_discarded_after_timeout(self) -> None:
        (self.project / "tests" / "test_ok.py").write_text(_SLEEPING, encoding="utf-8")
        agent = LocalTestingAgent(
            subset=self.workspace, runner=self.runner, timeout_seconds=1.0
        )
        report = agent.run_suite()
        self.assertFalse(report.passed)
        self.assertTrue(report.timed_out)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_malformed_output_is_incomplete(self) -> None:
        fake = ProcessResult(
            spawned=True,
            success=True,
            status=ProcessStatus.EXITED,
            exit_code=0,
            stdout="not unittest output",
        )
        with patch.object(self.runner, "run", return_value=fake) as mocked:
            report = self.agent.run_suite()
            mocked.assert_called()
        self.assertIsInstance(report, TestReport)
        self.assertFalse(report.passed)
        self.assertTrue(report.incomplete)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_empty_output_is_incomplete(self) -> None:
        fake = ProcessResult(
            spawned=True,
            success=True,
            status=ProcessStatus.EXITED,
            exit_code=0,
            stdout="",
        )
        with patch.object(self.runner, "run", return_value=fake):
            report = self.agent.run_suite()
        self.assertFalse(report.passed)
        self.assertTrue(report.incomplete)

    def test_unsupported_path_is_incomplete_without_copy(self) -> None:
        report = self.agent.run_suite("pytest")
        self.assertIsInstance(report, TestReport)
        self.assertTrue(report.incomplete)
        self.assertFalse(report.passed)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_run_suite_does_not_overlay(self) -> None:
        overlay_calls: list[object] = []
        real_overlay = self.workspace.overlay

        def _track(*args: object, **kwargs: object):
            overlay_calls.append((args, kwargs))
            return real_overlay(*args, **kwargs)

        self.workspace.overlay = _track  # type: ignore[method-assign]
        report = self.agent.run_suite()
        self.assertTrue(report.passed)
        self.assertEqual([], overlay_calls)
        src = inspect.getsource(LocalTestingAgent.run_suite)
        self.assertNotIn("overlay(", src)

    def test_process_runner_is_sole_boundary(self) -> None:
        with patch.object(self.runner, "run", wraps=self.runner.run) as mocked:
            report = self.agent.run_suite()
            mocked.assert_called()
        self.assertTrue(report.passed)
        root = Path(__file__).parent.parent / "astra" / "testing"
        for py_file in root.glob("*.py"):
            tree = ast.parse(py_file.read_text(encoding="utf-8"))
            names: list[str] = []
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    names.extend(alias.name.split(".")[0] for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names.append(node.module.split(".")[0])
            if py_file.name == "runner.py":
                self.assertIn("subprocess", names)
            else:
                self.assertNotIn("subprocess", names, py_file.name)

    def test_evaluation_deployment_approval_not_invoked(self) -> None:
        with (
            patch("astra.evaluation.agent.LocalEvaluationAgent.evaluate") as ev,
            patch("astra.evaluation.agent.LocalEvaluationAgent.evaluate_proposal") as evp,
            patch("astra.evaluation.agent.LocalEvaluationAgent.record_baseline") as rec,
            patch("astra.deployment.agent.LocalDeploymentAgent.deploy") as dep,
            patch("astra.coding.agent.LocalCodingAgent.propose_change") as code,
        ):
            report = self.agent.run_suite()
            ev.assert_not_called()
            evp.assert_not_called()
            rec.assert_not_called()
            dep.assert_not_called()
            code.assert_not_called()
        self.assertTrue(report.passed)
        text = inspect.getsource(LocalTestingAgent)
        self.assertNotIn("ApprovalRecord", text)
        self.assertNotIn("git", text.lower())

    def test_test_proposal_still_overlays(self) -> None:
        report = self.agent.test_proposal(_proposal("tests/test_extra.py", _PASSING_EXTRA))
        self.assertTrue(report.passed)
        self.assertGreaterEqual(report.tests_run, 2)
        self.assertFalse((self.project / "tests" / "test_extra.py").exists())

    def test_allowlist_and_schema(self) -> None:
        from astra.state import ApplicationState

        self.assertEqual(
            frozenset({"read_text", "read_project"}),
            PRODUCTION_EXECUTION_ALLOWLIST,
        )
        self.assertEqual(6, ApplicationState().schema_version)
        self.assertIsNot(
            Verdict.ALLOW,
            self.security.check_permanent_change("apply tests").verdict,
        )

    def test_adr_0029_exists_and_0024_untouched(self) -> None:
        root = Path(__file__).parent.parent / "docs" / "adr"
        adr29 = (root / "ADR-0029-canonical-isolated-test-suite-execution.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("copied-subset", adr29.lower())
        self.assertIn("ProcessRunner", adr29)
        self.assertIn("not an os sandbox", adr29.lower())
        adr24 = (root / "ADR-0024-honest-proposal-testing.md").read_text(encoding="utf-8")
        self.assertIn("implemented in B-025", adr24)
        self.assertIn("remains a concrete `NotImplementedError`", adr24)


if __name__ == "__main__":
    unittest.main()
