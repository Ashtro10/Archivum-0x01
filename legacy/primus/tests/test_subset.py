"""Copied-subset workspace tests (B-023, ADR-0022).

Allowlisted copy only. No TestingAgent. No subprocess. project_root unchanged.
"""

from __future__ import annotations

import ast
import inspect
import os
import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from astra.coding.contracts import ChangeProposal, FileChange
from astra.core.lifecycle import MeasurableProperty
from astra.security.agent import PolicySecurityAgent
from astra.testing.contracts import TestingAgent
from astra.testing.runner import ProcessRunner
from astra.testing.subset import CopiedSubsetWorkspace
from astra.testing.workspace import MAX_FILE_BYTES, MAX_FILES, ProposalWorkspace
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST


def _proposal(*paths_and_bodies: tuple[str, str]) -> ChangeProposal:
    changes = tuple(
        FileChange(path=Path(path), new_content=body, rationale="test")
        for path, body in paths_and_bodies
    )
    return ChangeProposal(
        objective="subset overlay test",
        changes=changes,
        improves=(MeasurableProperty.MAINTAINABILITY,),
    )


def _write_fixture(project: Path) -> None:
    (project / "astra").mkdir()
    (project / "astra" / "__init__.py").write_bytes(b"# astra-marker\n")
    (project / "tests").mkdir()
    (project / "tests" / "test_x.py").write_bytes(b"x = 1\n")
    (project / "ui").mkdir()
    (project / "ui" / "app.py").write_bytes(b"# ui-app\n")
    (project / "CONSTITUTION.md").write_bytes(b"# constitution-marker\n")
    (project / "pyproject.toml").write_bytes(b"[project]\nname='fixture'\n")
    (project / "benchmarks" / "results").mkdir(parents=True)
    (project / "benchmarks" / "results" / "embedding_verification.json").write_bytes(
        b"{}\n"
    )
    (project / "benchmarks" / "models").mkdir()
    (project / "benchmarks" / "models" / "weights.gguf").write_bytes(b"gguf")
    (project / "data").mkdir()
    (project / "data" / "secret.db").write_bytes(b"db")
    (project / ".git").mkdir()
    (project / ".git" / "config").write_bytes(b"git\n")
    (project / ".env").write_bytes(b"SECRET=1\n")
    (project / "astra" / "__pycache__").mkdir()
    (project / "astra" / "__pycache__" / "x.pyc").write_bytes(b"pyc")
    (project / "docs").mkdir()
    (project / "docs" / "readme.md").write_bytes(b"docs\n")
    (project / "README.md").write_bytes(b"readme\n")
    (project / "credentials.json").write_bytes(b"{}\n")


class CopiedSubsetWorkspaceTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.project = Path(self._tmp.name) / "project"
        self.scratch = Path(self._tmp.name) / "scratch"
        self.data = Path(self._tmp.name) / "data"
        self.project.mkdir()
        self.scratch.mkdir()
        self.data.mkdir()
        _write_fixture(self.project)
        self.security = PolicySecurityAgent(
            write_roots=(self.data,),
            scratch_root=self.scratch,
            project_roots=(self.project,),
        )
        self.workspace = CopiedSubsetWorkspace(
            security=self.security,
            scratch_root=self.scratch,
            project_root=self.project,
        )

    def test_copy_writes_allowlisted_files_not_exclusions(self) -> None:
        before_init = (self.project / "astra" / "__init__.py").read_bytes()
        before_const = (self.project / "CONSTITUTION.md").read_bytes()
        result = self.workspace.copy()
        self.assertTrue(result.success)
        root = result.subset_root
        self.assertIsNotNone(root)
        assert root is not None
        self.assertTrue((root / "astra" / "__init__.py").is_file())
        self.assertTrue((root / "tests" / "test_x.py").is_file())
        self.assertTrue((root / "ui" / "app.py").is_file())
        self.assertTrue((root / "CONSTITUTION.md").is_file())
        self.assertTrue((root / "pyproject.toml").is_file())
        self.assertTrue(
            (root / "benchmarks" / "results" / "embedding_verification.json").is_file()
        )
        self.assertFalse((root / ".git").exists())
        self.assertFalse((root / "data").exists())
        self.assertFalse((root / ".env").exists())
        self.assertFalse((root / "docs").exists())
        self.assertFalse((root / "README.md").exists())
        self.assertFalse((root / "credentials.json").exists())
        self.assertFalse((root / "astra" / "__pycache__").exists())
        self.assertEqual([], list(root.rglob("*.gguf")))
        self.assertEqual([], list(root.rglob("*.pyc")))
        self.assertEqual(
            before_init, (self.project / "astra" / "__init__.py").read_bytes()
        )
        self.assertEqual(
            before_const, (self.project / "CONSTITUTION.md").read_bytes()
        )

    def test_overlay_writes_into_existing_session(self) -> None:
        copied = self.workspace.copy()
        self.assertTrue(copied.success)
        before = (self.project / "astra" / "__init__.py").read_bytes()
        overlaid = self.workspace.overlay(
            copied.session_id, _proposal(("astra/__init__.py", "# overlay-b023\n"))
        )
        self.assertTrue(overlaid.success)
        dest = copied.subset_root / "astra" / "__init__.py"  # type: ignore[operator]
        self.assertIn("overlay-b023", dest.read_text(encoding="utf-8"))
        self.assertEqual(before, (self.project / "astra" / "__init__.py").read_bytes())

    def test_overlay_unknown_session_writes_nothing(self) -> None:
        result = self.workspace.overlay("missing", _proposal(("astra/__init__.py", "x\n")))
        self.assertFalse(result.success)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_parent_escape_writes_nothing(self) -> None:
        copied = self.workspace.copy()
        result = self.workspace.overlay(
            copied.session_id, _proposal(("../outside.py", "nope\n"))
        )
        self.assertFalse(result.success)
        self.assertFalse((Path(self._tmp.name) / "outside.py").exists())
        self.assertEqual(
            b"# astra-marker\n", (self.project / "astra" / "__init__.py").read_bytes()
        )

    def test_copy_onto_project_root_fails_closed(self) -> None:
        closed = CopiedSubsetWorkspace(
            security=self.security,
            scratch_root=self.project,
            project_root=self.project,
        )
        result = closed.copy()
        self.assertFalse(result.success)
        self.assertEqual(
            b"# astra-marker\n", (self.project / "astra" / "__init__.py").read_bytes()
        )

    def test_too_many_overlay_files_writes_nothing_extra(self) -> None:
        copied = self.workspace.copy()
        files = tuple((f"f{i}.py", "x\n") for i in range(MAX_FILES + 1))
        result = self.workspace.overlay(copied.session_id, _proposal(*files))
        self.assertFalse(result.success)
        self.assertFalse((copied.subset_root / "f0.py").exists())  # type: ignore[operator]

    def test_oversize_overlay_file_writes_nothing_extra(self) -> None:
        copied = self.workspace.copy()
        body = "a" * (MAX_FILE_BYTES + 1)
        result = self.workspace.overlay(
            copied.session_id, _proposal(("big.py", body))
        )
        self.assertFalse(result.success)
        self.assertFalse((copied.subset_root / "big.py").exists())  # type: ignore[operator]

    def test_denied_security_copies_nothing(self) -> None:
        closed = PolicySecurityAgent(project_roots=(self.project,))
        workspace = CopiedSubsetWorkspace(
            security=closed,
            scratch_root=self.scratch,
            project_root=self.project,
        )
        result = workspace.copy()
        self.assertFalse(result.success)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_discard_removes_session(self) -> None:
        result = self.workspace.copy()
        self.assertTrue(result.success)
        self.assertTrue(result.subset_root.is_dir())  # type: ignore[union-attr]
        self.assertTrue(self.workspace.discard(result.session_id))
        self.assertFalse(result.subset_root.exists())  # type: ignore[union-attr]

    def test_discard_all_clears_sessions(self) -> None:
        first = self.workspace.copy()
        second = self.workspace.copy()
        self.assertTrue(first.success and second.success)
        self.workspace.discard_all()
        self.assertFalse(first.subset_root.exists())  # type: ignore[union-attr]
        self.assertFalse(second.subset_root.exists())  # type: ignore[union-attr]

    def test_proposal_workspace_still_overlay_only(self) -> None:
        overlay = ProposalWorkspace(
            security=self.security,
            scratch_root=self.scratch,
            project_root=self.project,
        )
        result = overlay.materialize(_proposal(("marker.py", "only-overlay\n")))
        self.assertTrue(result.success)
        self.assertFalse((self.project / "marker.py").exists())
        self.assertTrue((result.overlay_root / "marker.py").is_file())  # type: ignore[operator]
        overlay.discard_all()

    def test_process_runner_still_does_not_spawn(self) -> None:
        from astra.security.contracts import ProcessSpec

        runner = ProcessRunner(security=self.security)
        result = runner.run(
            ProcessSpec(argv=("python", "-m", "unittest"), timeout_seconds=1.0)
        )
        self.assertIs(False, result.spawned)

    def test_testing_agent_still_unimplemented(self) -> None:
        with self.assertRaises(NotImplementedError):
            TestingAgent.test_proposal(object(), _proposal(("x.py", "x\n")))  # type: ignore[misc]
        with self.assertRaises(NotImplementedError):
            TestingAgent.run_suite(object())  # type: ignore[misc]

    def test_allowlist_unchanged(self) -> None:
        self.assertEqual(
            frozenset({"read_text", "read_project"}),
            PRODUCTION_EXECUTION_ALLOWLIST,
        )

    def test_symlink_source_fails_closed(self) -> None:
        target = self.project / "astra" / "real.py"
        target.write_bytes(b"real\n")
        link = self.project / "astra" / "linked.py"
        try:
            os.symlink(target, link)
        except (OSError, NotImplementedError):
            self.skipTest("symlink creation not permitted")
        result = self.workspace.copy()
        self.assertFalse(result.success)
        self.assertEqual([], list(self.scratch.iterdir()))


class SubsetIsolationTest(unittest.TestCase):
    def test_no_subprocess_eval_exec(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "testing"
        forbidden = {"subprocess", "socket", "httpx", "requests", "dearpygui", "ui"}
        for py_file in root.glob("*.py"):
            text = py_file.read_text(encoding="utf-8")
            self.assertNotIn("os.system", text)
            tree = ast.parse(text)
            forbidden_here = set(forbidden)
            if py_file.name == "runner.py":
                forbidden_here.discard("subprocess")
            for node in ast.walk(tree):
                names: list[str] = []
                if isinstance(node, ast.Import):
                    names = [alias.name.split(".")[0] for alias in node.names]
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names = [node.module.split(".")[0]]
                for name in names:
                    self.assertNotIn(name, forbidden_here, f"{py_file.name} imports {name}")
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
                    self.assertNotIn(node.func.id, {"eval", "exec"})

    def test_coding_package_still_has_no_write_text(self) -> None:
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
            _write_fixture(project)
            agent = PolicySecurityAgent(
                scratch_root=scratch, project_roots=(project,)
            )
            workspace = CopiedSubsetWorkspace(
                security=agent, scratch_root=scratch, project_root=project
            )
            result = workspace.copy()
        self.assertTrue(result.success)
        workspace.discard_all()

    def test_milestone_check_not_hard_coded_true(self) -> None:
        from astra.presentation import progress

        src = inspect.getsource(progress._check_copied_subset_workspace)
        self.assertIn("CopiedSubsetWorkspace", src)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_copied_subset_workspace())


class ControllerSubsetWorkspaceTest(unittest.TestCase):
    def test_subset_not_an_agent(self) -> None:
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
            _ = ctrl.copied_subset_workspace
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        self.assertIn("testing", ctrl.agent_names)
        workspace = ctrl.copied_subset_workspace
        self.assertIsInstance(workspace, CopiedSubsetWorkspace)
        self.assertIsNot(workspace, ctrl.coding)
        self.assertIsNot(workspace, ctrl.proposal_workspace)
        self.assertEqual(
            frozenset({"read_text", "read_project"}),
            PRODUCTION_EXECUTION_ALLOWLIST,
        )
        state = ctrl.get_state()
        self.assertEqual(6, state.schema_version)
        testing = next(m for m in state.modules if m.module == "astra.testing")
        self.assertEqual(CapabilityStatus.OPERATIONAL, testing.status)
        subset_cap = next(
            c for c in testing.capabilities if c.name == "copied_subset_workspace"
        )
        self.assertEqual(CapabilityStatus.OPERATIONAL, subset_cap.status)
        test_prop = next(c for c in testing.capabilities if c.name == "test_proposal")
        self.assertEqual(CapabilityStatus.OPERATIONAL, test_prop.status)
        self.assertIs(False, state.coding.applied)
        from astra.security.contracts import ProcessSpec

        spawned = ctrl.process_runner.run(
            ProcessSpec(argv=("python", "-m", "unittest"), timeout_seconds=1.0)
        )
        self.assertIs(False, spawned.spawned)
        ctrl.shutdown()
        with self.assertRaises(ControllerNotStartedError):
            _ = ctrl.copied_subset_workspace


if __name__ == "__main__":
    unittest.main()
