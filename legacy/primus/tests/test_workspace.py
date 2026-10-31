"""Proposal workspace tests (B-021, ADR-0020).

Overlay only. No TestingAgent. No subprocess. project_root unchanged.
"""

from __future__ import annotations

import ast
import inspect
import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from astra.coding.contracts import ChangeProposal, FileChange
from astra.core.lifecycle import MeasurableProperty
from astra.security.agent import PolicySecurityAgent
from astra.security.contracts import Verdict
from astra.testing.contracts import TestingAgent
from astra.testing.workspace import MAX_FILE_BYTES, MAX_FILES, ProposalWorkspace


def _proposal(*paths_and_bodies: tuple[str, str]) -> ChangeProposal:
    changes = tuple(
        FileChange(path=Path(path), new_content=body, rationale="test")
        for path, body in paths_and_bodies
    )
    return ChangeProposal(
        objective="overlay test",
        changes=changes,
        improves=(MeasurableProperty.MAINTAINABILITY,),
    )


class ProposalWorkspaceTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.project = Path(self._tmp.name) / "project"
        self.scratch = Path(self._tmp.name) / "scratch"
        self.data = Path(self._tmp.name) / "data"
        self.project.mkdir()
        self.scratch.mkdir()
        self.data.mkdir()
        (self.project / "marker.py").write_bytes(b"original\n")
        self.security = PolicySecurityAgent(
            write_roots=(self.data,),
            scratch_root=self.scratch,
            project_roots=(self.project,),
        )
        self.workspace = ProposalWorkspace(
            security=self.security,
            scratch_root=self.scratch,
            project_root=self.project,
        )

    def test_materialize_writes_overlay_not_project(self) -> None:
        before = (self.project / "marker.py").read_bytes()
        result = self.workspace.materialize(_proposal(("marker.py", "marker-b021\n")))
        self.assertTrue(result.success)
        self.assertIsNotNone(result.overlay_root)
        overlay = result.overlay_root / "marker.py"  # type: ignore[operator]
        self.assertTrue(overlay.is_file())
        self.assertIn("marker-b021", overlay.read_text(encoding="utf-8"))
        self.assertEqual(before, (self.project / "marker.py").read_bytes())

    def test_empty_proposal_writes_nothing(self) -> None:
        result = self.workspace.materialize(_proposal())
        self.assertFalse(result.success)
        self.assertEqual((), result.written)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_parent_escape_writes_nothing(self) -> None:
        result = self.workspace.materialize(
            _proposal(("../outside.py", "nope\n"))
        )
        self.assertFalse(result.success)
        self.assertFalse((Path(self._tmp.name) / "outside.py").exists())
        self.assertEqual(b"original\n", (self.project / "marker.py").read_bytes())

    def test_absolute_outside_writes_nothing(self) -> None:
        outside = Path(self._tmp.name) / "elsewhere.py"
        result = self.workspace.materialize(
            ChangeProposal(
                objective="abs",
                changes=(FileChange(path=outside, new_content="nope\n"),),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
        )
        self.assertFalse(result.success)
        self.assertFalse(outside.exists())

    def test_too_many_files_writes_nothing(self) -> None:
        files = tuple((f"f{i}.py", "x\n") for i in range(MAX_FILES + 1))
        result = self.workspace.materialize(_proposal(*files))
        self.assertFalse(result.success)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_oversize_file_writes_nothing(self) -> None:
        body = "a" * (MAX_FILE_BYTES + 1)
        result = self.workspace.materialize(_proposal(("big.py", body)))
        self.assertFalse(result.success)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_new_file_does_not_create_project_path(self) -> None:
        result = self.workspace.materialize(_proposal(("new.py", "created\n")))
        self.assertTrue(result.success)
        self.assertFalse((self.project / "new.py").exists())
        overlay = result.overlay_root / "new.py"  # type: ignore[operator]
        self.assertEqual("created\n", overlay.read_text(encoding="utf-8"))

    def test_denied_security_writes_nothing(self) -> None:
        closed = PolicySecurityAgent(project_roots=(self.project,))
        workspace = ProposalWorkspace(
            security=closed,
            scratch_root=self.scratch,
            project_root=self.project,
        )
        result = workspace.materialize(_proposal(("marker.py", "x\n")))
        self.assertFalse(result.success)
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_discard_removes_session(self) -> None:
        result = self.workspace.materialize(_proposal(("marker.py", "x\n")))
        self.assertTrue(result.success)
        self.assertTrue(result.overlay_root.is_dir())  # type: ignore[union-attr]
        self.assertTrue(self.workspace.discard(result.session_id))
        self.assertFalse(result.overlay_root.exists())  # type: ignore[union-attr]

    def test_discard_all_clears_sessions(self) -> None:
        first = self.workspace.materialize(_proposal(("a.py", "a\n")))
        second = self.workspace.materialize(_proposal(("b.py", "b\n")))
        self.assertTrue(first.success and second.success)
        self.workspace.discard_all()
        self.assertFalse(first.overlay_root.exists())  # type: ignore[union-attr]
        self.assertFalse(second.overlay_root.exists())  # type: ignore[union-attr]

    def test_testing_agent_still_unimplemented(self) -> None:
        with self.assertRaises(NotImplementedError):
            TestingAgent.test_proposal(object(), _proposal(("x.py", "x\n")))  # type: ignore[misc]
        with self.assertRaises(NotImplementedError):
            TestingAgent.run_suite(object())  # type: ignore[misc]


class WorkspaceIsolationTest(unittest.TestCase):
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
            (project / "m.py").write_text("old\n", encoding="utf-8")
            agent = PolicySecurityAgent(
                scratch_root=scratch, project_roots=(project,)
            )
            workspace = ProposalWorkspace(
                security=agent, scratch_root=scratch, project_root=project
            )
            result = workspace.materialize(_proposal(("m.py", "new\n")))
        self.assertTrue(result.success)
        workspace.discard_all()

    def test_milestone_check_not_hard_coded_true(self) -> None:
        from astra.presentation import progress

        src = inspect.getsource(progress._check_proposal_workspace)
        self.assertIn("ProposalWorkspace", src)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_proposal_workspace())


class ControllerWorkspaceTest(unittest.TestCase):
    def test_workspace_not_an_agent(self) -> None:
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
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

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
            _ = ctrl.proposal_workspace
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        self.assertIn("testing", ctrl.agent_names)
        workspace = ctrl.proposal_workspace
        self.assertIsInstance(workspace, ProposalWorkspace)
        self.assertIsNot(workspace, ctrl.coding)
        self.assertEqual(
            frozenset({"read_text", "read_project"}),
            PRODUCTION_EXECUTION_ALLOWLIST,
        )
        state = ctrl.get_state()
        self.assertEqual(6, state.schema_version)
        testing = next(m for m in state.modules if m.module == "astra.testing")
        self.assertEqual(CapabilityStatus.OPERATIONAL, testing.status)
        test_prop = next(c for c in testing.capabilities if c.name == "test_proposal")
        self.assertEqual(CapabilityStatus.OPERATIONAL, test_prop.status)
        self.assertIs(False, state.coding.applied)
        target = Path(__file__).resolve().parents[1] / "astra" / "coding" / "__init__.py"
        before = target.read_bytes()
        result = workspace.materialize(
            ChangeProposal(
                objective="controller overlay",
                changes=(
                    FileChange(
                        path=Path("astra") / "coding" / "__init__.py",
                        new_content="# overlay-only\n",
                    ),
                ),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
        )
        self.assertTrue(result.success)
        self.assertEqual(before, target.read_bytes())
        self.assertIs(
            Verdict.DENY,
            ctrl.security.check_temporary_write(target).verdict,
        )
        self.assertIs(
            Verdict.DENY,
            ctrl.security.check_file_write(scratch / "overlay.py").verdict,
        )
        self.assertIs(
            Verdict.ALLOW,
            ctrl.security.check_temporary_write(scratch / "overlay.py").verdict,
        )
        workspace.discard_all()
        ctrl.shutdown()
        with self.assertRaises(ControllerNotStartedError):
            _ = ctrl.proposal_workspace


if __name__ == "__main__":
    unittest.main()
