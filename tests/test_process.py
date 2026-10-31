"""Process runner tests (B-024, ADR-0023).

Spawn sys.executable in a copied-subset session. No TestingAgent. No TestReport.
"""

from __future__ import annotations

import ast
import inspect
import os
import socket
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from astra.coding.contracts import ChangeProposal, FileChange
from astra.core.lifecycle import MeasurableProperty
from astra.security.agent import PolicySecurityAgent
from astra.security.contracts import ProcessSpec, Verdict
from astra.testing.contracts import TestingAgent, TestReport
from astra.testing.probe import TOKEN
from astra.testing.runner import (
    DEFAULT_PROBE_TIMEOUT,
    MAX_OUTPUT_BYTES,
    ProcessResult,
    ProcessRunner,
    ProcessStatus,
)
from astra.testing.subset import CopiedSubsetWorkspace
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST


def _proposal(*paths_and_bodies: tuple[str, str]) -> ChangeProposal:
    changes = tuple(
        FileChange(path=Path(path), new_content=body, rationale="test")
        for path, body in paths_and_bodies
    )
    return ChangeProposal(
        objective="process overlay",
        changes=changes,
        improves=(MeasurableProperty.MAINTAINABILITY,),
    )


def _write_probe_fixture(project: Path) -> None:
    (project / "astra" / "testing").mkdir(parents=True)
    (project / "astra" / "__init__.py").write_bytes(b"# astra-marker\n")
    (project / "astra" / "testing" / "__init__.py").write_bytes(b"")
    live_probe = Path(__file__).resolve().parents[1] / "astra" / "testing" / "probe.py"
    (project / "astra" / "testing" / "probe.py").write_bytes(live_probe.read_bytes())
    (project / "tests").mkdir()
    (project / "tests" / "test_x.py").write_bytes(b"x = 1\n")
    (project / "ui").mkdir()
    (project / "ui" / "app.py").write_bytes(b"# ui-app\n")
    (project / "CONSTITUTION.md").write_bytes(b"# constitution-marker\n")


def _probe_spec(cwd: Path, timeout: float = DEFAULT_PROBE_TIMEOUT) -> ProcessSpec:
    return ProcessSpec(
        argv=(sys.executable, "-B", "-s", "-m", "astra.testing.probe"),
        cwd=cwd,
        timeout_seconds=timeout,
    )


class ProcessRunnerSpawnTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.project = Path(self._tmp.name) / "project"
        self.scratch = Path(self._tmp.name) / "scratch"
        self.data = Path(self._tmp.name) / "data"
        self.project.mkdir()
        self.scratch.mkdir()
        self.data.mkdir()
        _write_probe_fixture(self.project)
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
        self.copied = self.workspace.copy()
        self.assertTrue(self.copied.success)
        self.session = self.copied.subset_root
        assert self.session is not None

    def tearDown(self) -> None:
        self.workspace.discard_all()

    def _run(self, spec: ProcessSpec, session_id: str | None = None) -> ProcessResult:
        sid = self.copied.session_id if session_id is None else session_id
        return self.runner.run(spec, session_id=sid)

    def test_authorized_probe_spawns(self) -> None:
        before = (self.project / "astra" / "__init__.py").read_bytes()
        result = self._run(_probe_spec(self.session))
        self.assertTrue(result.spawned)
        self.assertIs(ProcessStatus.EXITED, result.status)
        self.assertEqual(0, result.exit_code)
        self.assertIn(TOKEN, result.stdout)
        self.assertTrue(result.success)
        self.assertEqual(before, (self.project / "astra" / "__init__.py").read_bytes())

    def test_frozen_executable_does_not_spawn(self) -> None:
        with patch.object(sys, "frozen", True, create=True):
            result = self._run(_probe_spec(self.session))
        self.assertFalse(result.spawned)
        self.assertIs(ProcessStatus.NOT_SPAWNED, result.status)
        self.assertIn("frozen executable", result.reason)

    def test_unauthorized_executable_does_not_spawn(self) -> None:
        result = self._run(
            ProcessSpec(
                argv=(str(self.scratch / "nope.exe"),),
                cwd=self.session,
                timeout_seconds=DEFAULT_PROBE_TIMEOUT,
            )
        )
        self.assertFalse(result.spawned)
        self.assertIs(ProcessStatus.NOT_AUTHORIZED, result.status)

    def test_empty_argv_does_not_spawn(self) -> None:
        result = self._run(
            ProcessSpec(argv=(), cwd=self.session, timeout_seconds=DEFAULT_PROBE_TIMEOUT)
        )
        self.assertFalse(result.spawned)

    def test_shells_do_not_spawn(self) -> None:
        for argv in (
            ("cmd.exe", "/c", "dir"),
            ("powershell.exe", "-Command", "Get-Date"),
            ("sh", "-c", "echo x"),
            ("bash", "-c", "echo x"),
        ):
            with self.subTest(argv=argv):
                result = self._run(
                    ProcessSpec(
                        argv=argv,
                        cwd=self.session,
                        timeout_seconds=DEFAULT_PROBE_TIMEOUT,
                    )
                )
                self.assertFalse(result.spawned)

    def test_python_dash_c_does_not_spawn(self) -> None:
        result = self._run(
            ProcessSpec(
                argv=(sys.executable, "-c", "print(1)"),
                cwd=self.session,
                timeout_seconds=DEFAULT_PROBE_TIMEOUT,
            )
        )
        self.assertFalse(result.spawned)

    def test_project_root_cwd_does_not_spawn(self) -> None:
        result = self._run(_probe_spec(self.project))
        self.assertFalse(result.spawned)

    def test_scratch_root_cwd_does_not_spawn(self) -> None:
        result = self._run(_probe_spec(self.scratch))
        self.assertFalse(result.spawned)

    def test_other_session_cwd_does_not_spawn(self) -> None:
        other = self.workspace.copy()
        self.assertTrue(other.success)
        result = self._run(_probe_spec(other.subset_root))  # type: ignore[arg-type]
        self.assertFalse(result.spawned)
        self.workspace.discard(other.session_id)

    def test_missing_session_does_not_spawn(self) -> None:
        result = self._run(_probe_spec(self.session), session_id="")
        self.assertFalse(result.spawned)
        self.assertIs(ProcessStatus.NOT_SPAWNED, result.status)
        unknown = self._run(_probe_spec(self.session), session_id="missing")
        self.assertFalse(unknown.spawned)

    def test_parent_env_does_not_leak(self) -> None:
        overlaid = self.workspace.overlay(
            self.copied.session_id,
            _proposal(
                (
                    "env_probe.py",
                    "import os\n"
                    "print('PP=' + os.environ.get('PYTHONPATH', ''))\n"
                    "print('AH=' + os.environ.get('ASTRA_HOME', ''))\n"
                    "print('AC=' + os.environ.get('ASTRA_CONFIG', ''))\n"
                    "print('OK=' + os.environ.get('OPENAI_API_KEY', ''))\n",
                )
            ),
        )
        self.assertTrue(overlaid.success)
        pollution = {
            "PYTHONPATH": str(self.project),
            "ASTRA_HOME": str(self.data),
            "ASTRA_CONFIG": "leaked.toml",
            "OPENAI_API_KEY": "secret",
        }
        with patch.dict(os.environ, pollution, clear=False):
            result = self._run(
                ProcessSpec(
                    argv=(sys.executable, "-B", "-s", "env_probe.py"),
                    cwd=self.session,
                    timeout_seconds=DEFAULT_PROBE_TIMEOUT,
                )
            )
        self.assertTrue(result.spawned)
        lines = dict(
            line.split("=", 1) for line in result.stdout.splitlines() if "=" in line
        )
        from astra.testing.workspace import _canonical

        self.assertEqual(str(_canonical(self.session)), lines.get("PP", ""))
        self.assertEqual("", lines.get("AH", "missing"))
        self.assertEqual("", lines.get("AC", "missing"))
        self.assertEqual("", lines.get("OK", "missing"))

    def test_timeout_kills_direct_child(self) -> None:
        overlaid = self.workspace.overlay(
            self.copied.session_id,
            _proposal(("sleep.py", "import time\ntime.sleep(30)\n")),
        )
        self.assertTrue(overlaid.success)
        result = self._run(
            ProcessSpec(
                argv=(sys.executable, "-B", "-s", "sleep.py"),
                cwd=self.session,
                timeout_seconds=1.0,
            )
        )
        self.assertTrue(result.spawned)
        self.assertTrue(result.timed_out)
        self.assertIs(ProcessStatus.TIMED_OUT, result.status)
        self.assertFalse(result.success)

    def test_output_is_bounded(self) -> None:
        overlaid = self.workspace.overlay(
            self.copied.session_id,
            _proposal(
                (
                    "flood.py",
                    "import sys\n"
                    "sys.stdout.write('A' * 70000)\n"
                    "sys.stderr.write('B' * 70000)\n",
                )
            ),
        )
        self.assertTrue(overlaid.success)
        result = self._run(
            ProcessSpec(
                argv=(sys.executable, "-B", "-s", "flood.py"),
                cwd=self.session,
                timeout_seconds=DEFAULT_PROBE_TIMEOUT,
            )
        )
        self.assertTrue(result.spawned)
        self.assertTrue(result.stdout_truncated)
        self.assertTrue(result.stderr_truncated)
        self.assertLessEqual(len(result.stdout.encode("utf-8")), MAX_OUTPUT_BYTES)
        self.assertLessEqual(len(result.stderr.encode("utf-8")), MAX_OUTPUT_BYTES)

    def test_nonzero_exit_is_exited_not_testreport(self) -> None:
        overlaid = self.workspace.overlay(
            self.copied.session_id,
            _proposal(("fail.py", "raise SystemExit(2)\n")),
        )
        self.assertTrue(overlaid.success)
        result = self._run(
            ProcessSpec(
                argv=(sys.executable, "-B", "-s", "fail.py"),
                cwd=self.session,
                timeout_seconds=DEFAULT_PROBE_TIMEOUT,
            )
        )
        self.assertTrue(result.spawned)
        self.assertIs(ProcessStatus.EXITED, result.status)
        self.assertEqual(2, result.exit_code)
        self.assertTrue(result.success)
        self.assertNotIsInstance(result, TestReport)

    def test_spawn_failure_status(self) -> None:
        with patch("astra.testing.runner.subprocess.Popen", side_effect=OSError("boom")):
            result = self._run(_probe_spec(self.session))
        self.assertFalse(result.spawned)
        self.assertIs(ProcessStatus.SPAWN_FAILURE, result.status)
        self.assertFalse(result.success)

    def test_discard_removes_session(self) -> None:
        session_id = self.copied.session_id
        self.assertIsNotNone(self.workspace.session_root(session_id))
        self.assertTrue(self.workspace.discard(session_id))
        self.assertIsNone(self.workspace.session_root(session_id))
        self.assertFalse(self.session.exists())

    def test_testing_agent_still_unimplemented(self) -> None:
        with self.assertRaises(NotImplementedError):
            TestingAgent.test_proposal(object(), _proposal(("x.py", "x\n")))  # type: ignore[misc]
        with self.assertRaises(NotImplementedError):
            TestingAgent.run_suite(object())  # type: ignore[misc]

    def test_allowlist_and_schema_and_permanent(self) -> None:
        from astra.state import ApplicationState

        self.assertEqual(
            frozenset({"read_text", "read_project"}),
            PRODUCTION_EXECUTION_ALLOWLIST,
        )
        self.assertEqual(6, ApplicationState().schema_version)
        self.assertIsNot(
            Verdict.ALLOW,
            self.security.check_permanent_change("run tests").verdict,
        )


class ProcessIsolationTest(unittest.TestCase):
    def test_subprocess_only_in_runner(self) -> None:
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
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
                    self.assertNotIn(node.func.id, {"eval", "exec"})
            if py_file.name == "runner.py":
                self.assertIn("subprocess", names)
                self.assertNotIn("nvidia-smi", text)
            else:
                self.assertNotIn("subprocess", names, f"{py_file.name} imports subprocess")

    def test_security_and_coding_have_no_subprocess(self) -> None:
        for package in ("security", "coding"):
            root = Path(__file__).parent.parent / "astra" / package
            for py_file in root.glob("*.py"):
                text = py_file.read_text(encoding="utf-8")
                self.assertNotIn("subprocess", text, py_file.name)
                if package == "coding":
                    self.assertNotIn("write_text", text)

    def test_nvidia_smi_stays_in_presentation(self) -> None:
        text = (
            Path(__file__).parent.parent
            / "astra"
            / "presentation"
            / "provider.py"
        ).read_text(encoding="utf-8")
        self.assertIn("nvidia-smi", text)
        self.assertIn("import subprocess", text)
        runner = (
            Path(__file__).parent.parent / "astra" / "testing" / "runner.py"
        ).read_text(encoding="utf-8")
        self.assertNotIn("nvidia-smi", runner)

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
            _write_probe_fixture(project)
            agent = PolicySecurityAgent(
                scratch_root=scratch,
                project_roots=(project,),
                process_executables=(Path(sys.executable),),
            )
            workspace = CopiedSubsetWorkspace(
                security=agent, scratch_root=scratch, project_root=project
            )
            copied = workspace.copy()
            runner = ProcessRunner(security=agent, subset=workspace)
            result = runner.run(
                _probe_spec(copied.subset_root),  # type: ignore[arg-type]
                session_id=copied.session_id,
            )
            workspace.discard_all()
        self.assertTrue(result.spawned)
        self.assertIn(TOKEN, result.stdout)

    def test_milestone_check_not_hard_coded_true(self) -> None:
        from astra.presentation import progress

        src = inspect.getsource(progress._check_process_spawn)
        self.assertIn("ProcessRunner", src)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_process_spawn())


class ControllerProcessRunnerTest(unittest.TestCase):
    def test_runner_not_an_agent_and_can_spawn_probe(self) -> None:
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
            _ = ctrl.process_runner
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        self.assertIn("testing", ctrl.agent_names)
        runner = ctrl.process_runner
        self.assertIsInstance(runner, ProcessRunner)
        self.assertIsNot(runner, ctrl.coding)
        denied = runner.run(
            ProcessSpec(
                argv=("python", "-m", "unittest"),
                cwd=scratch,
                timeout_seconds=1.0,
            )
        )
        self.assertIs(False, denied.spawned)
        copied = ctrl.copied_subset_workspace.copy()
        self.assertTrue(copied.success)
        try:
            result = runner.run(
                _probe_spec(copied.subset_root),  # type: ignore[arg-type]
                session_id=copied.session_id,
            )
        finally:
            ctrl.copied_subset_workspace.discard_all()
        self.assertTrue(result.spawned)
        self.assertIn(TOKEN, result.stdout)
        state = ctrl.get_state()
        self.assertEqual(6, state.schema_version)
        testing = next(m for m in state.modules if m.module == "astra.testing")
        cap = next(c for c in testing.capabilities if c.name == "process_runner")
        self.assertEqual(CapabilityStatus.OPERATIONAL, cap.status)
        test_prop = next(c for c in testing.capabilities if c.name == "test_proposal")
        self.assertEqual(CapabilityStatus.OPERATIONAL, test_prop.status)
        self.assertIs(False, state.coding.applied)
        ctrl.shutdown()
        with self.assertRaises(ControllerNotStartedError):
            _ = ctrl.process_runner


if __name__ == "__main__":
    unittest.main()
