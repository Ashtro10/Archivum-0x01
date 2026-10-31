"""Controlled Git version history tests (B-033, ADR-0032).

Git is history/recovery, not authorization. One spawn boundary. No Git Tool.
"""

from __future__ import annotations

import ast
import dataclasses
import inspect
import os
import shutil
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

from astra.coding.contracts import ChangeProposal, FileChange
from astra.core.lifecycle import MeasurableProperty
from astra.deployment.agent import LocalDeploymentAgent
from astra.deployment.contracts import (
    ApprovalRecord,
    STATUS_DEPLOYED,
    STATUS_DEPLOYED_HISTORY_FAILED,
    STATUS_ROLLED_BACK,
    STATUS_TESTS_FAILED,
)
from astra.deployment.fingerprint import (
    evidence_fingerprint,
    git_restore_fingerprint,
    rollback_fingerprint,
)
from astra.deployment.gitrun import GitRunner, parse_commit_id
from astra.deployment.gitstore import (
    GIT_NOT_REPOSITORY,
    GIT_OPERATION_FAILED,
    GIT_SECURITY_DENIED,
    GIT_UNAVAILABLE,
    HISTORY_RECORDED,
    RESTORE_DENIED,
    RESTORE_FAILED,
    GitRecord,
    GitVersionStore,
)
from astra.deployment.store import LocalVersionManager
from astra.evaluation.contracts import EvaluationReport
from astra.security.agent import PolicySecurityAgent
from astra.security.contracts import PolicyDecision, Verdict
from astra.state import ApplicationState
from astra.testing.contracts import TestReport
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST
from astra.tools.contracts import ToolSpec


def _git_exe() -> Path | None:
    found = shutil.which("git")
    return Path(found) if found else None


def _proposal(*pairs: tuple[str, str]) -> ChangeProposal:
    changes = tuple(
        FileChange(path=Path(path), new_content=body, rationale="test")
        for path, body in pairs
    )
    return ChangeProposal(
        objective="git overlay",
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
    fields = dict(subject="git overlay", tests_passed=True, evaluation_passed=True)
    fields.update(kwargs)
    return EvaluationReport(**fields)  # type: ignore[arg-type]


def _approval(proposal: ChangeProposal, tests: TestReport, evaluation: EvaluationReport) -> ApprovalRecord:
    return ApprovalRecord(
        subject=evidence_fingerprint(proposal, tests, evaluation),
        approved_by="tester",
        approved_at=datetime.now(timezone.utc),
    )


class _FailingGit:
    def record_deployment(self, **_kwargs: object) -> GitRecord:
        return GitRecord(status=GIT_OPERATION_FAILED, reason="injected git failure")


class GitUnavailableTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.project = Path(self._tmp.name) / "project"
        self.data = Path(self._tmp.name) / "data"
        self.project.mkdir()
        self.data.mkdir()
        (self.project / "astra").mkdir()
        (self.project / "astra" / "hello.py").write_bytes(b"# before\n")
        self.security = PolicySecurityAgent(
            write_roots=(self.data,),
            project_roots=(self.project,),
        )
        self.proposal = _proposal(("astra/hello.py", "# after\n"))
        self.tests = _tests()
        self.evaluation = _eval()
        self.approval = _approval(self.proposal, self.tests, self.evaluation)

    def test_unwired_git_does_not_fabricate_commit(self) -> None:
        agent = LocalDeploymentAgent(security=self.security, project_root=self.project)
        result = agent.deploy(self.proposal, self.tests, self.evaluation, self.approval)
        self.assertEqual(STATUS_DEPLOYED, result.status)
        self.assertTrue(result.deployed)
        self.assertEqual(GIT_UNAVAILABLE, result.git_status)
        self.assertEqual("", result.git_commit)
        self.assertFalse(parse_commit_id(result.git_commit))

    def test_missing_executable_is_unavailable(self) -> None:
        store = GitVersionStore(
            security=self.security,
            project_root=self.project,
            executable=self.project / "git.exe",
        )
        rec = store.record_deployment(
            version_id="d-0123456789abcdef-0001",
            written=(str(self.project / "astra" / "hello.py"),),
        )
        self.assertEqual(GIT_UNAVAILABLE, rec.status)
        self.assertEqual("", rec.commit_id)

    def test_repo_absent_without_init(self) -> None:
        exe = _git_exe()
        if exe is None:
            self.skipTest("git executable not on PATH")
        store = GitVersionStore(
            security=self.security,
            project_root=self.project,
            executable=exe,
            init_if_missing=False,
        )
        rec = store.record_deployment(
            version_id="d-0123456789abcdef-0001",
            written=(str(self.project / "astra" / "hello.py"),),
        )
        self.assertEqual(GIT_NOT_REPOSITORY, rec.status)
        self.assertEqual("", rec.commit_id)


class GitDeployHistoryTest(unittest.TestCase):
    def setUp(self) -> None:
        exe = _git_exe()
        if exe is None:
            self.skipTest("git executable not on PATH")
        self.exe = exe
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        root = Path(self._tmp.name)
        self.project = root / "project"
        self.scratch = root / "scratch"
        self.data = root / "data"
        self.project.mkdir()
        self.scratch.mkdir()
        self.data.mkdir()
        (self.project / "astra").mkdir()
        (self.project / "astra" / "hello.py").write_bytes(b"# before\n")
        (self.project / "keep.py").write_bytes(b"# keep\n")
        self.security = PolicySecurityAgent(
            write_roots=(self.data,),
            scratch_root=self.scratch,
            project_roots=(self.project,),
        )
        self.versions = LocalVersionManager(
            security=self.security,
            project_root=self.project,
            store_root=self.scratch / "versions",
        )
        self.git = GitVersionStore(
            security=self.security,
            project_root=self.project,
            scratch_root=self.scratch,
            executable=self.exe,
            init_if_missing=True,
        )
        self.agent = LocalDeploymentAgent(
            security=self.security,
            project_root=self.project,
            versions=self.versions,
            git=self.git,
        )
        self.proposal = _proposal(("astra/hello.py", "# after\n"))
        self.tests = _tests()
        self.evaluation = _eval()
        self.approval = _approval(self.proposal, self.tests, self.evaluation)
        self._gitconfig = Path.home() / ".gitconfig"
        self._gitconfig_before = (
            self._gitconfig.read_bytes() if self._gitconfig.is_file() else None
        )
        self._creds = Path.home() / ".git-credentials"
        self._creds_before = self._creds.read_bytes() if self._creds.is_file() else None

    def _assert_global_untouched(self) -> None:
        if self._gitconfig_before is None:
            self.assertFalse(self._gitconfig.is_file())
        else:
            self.assertEqual(self._gitconfig_before, self._gitconfig.read_bytes())
        if self._creds_before is None:
            self.assertFalse(self._creds.is_file())
        else:
            self.assertEqual(self._creds_before, self._creds.read_bytes())

    def test_successful_deploy_records_git_history(self) -> None:
        result = self.agent.deploy(
            self.proposal, self.tests, self.evaluation, self.approval
        )
        self.assertEqual(STATUS_DEPLOYED, result.status)
        self.assertEqual(HISTORY_RECORDED, result.git_status)
        self.assertTrue(parse_commit_id(result.git_commit))
        self.assertEqual(result.version, result.version)
        history = self.git.history()
        self.assertTrue(history)
        self.assertEqual(result.git_commit, history[0].commit_id)
        self.assertEqual(result.version, history[0].version_id)
        self._assert_global_untouched()

    def test_failed_deploy_records_no_git_history(self) -> None:
        failed = self.agent.deploy(
            self.proposal, _tests(tests_run=2, tests_passed=1, tests_failed=1), self.evaluation, self.approval
        )
        self.assertEqual(STATUS_TESTS_FAILED, failed.status)
        self.assertEqual("", failed.git_commit)
        self.assertFalse(any(item.recorded for item in self.git.history()))
        self.assertEqual(b"# before\n", (self.project / "astra" / "hello.py").read_bytes())

    def test_git_failure_after_deploy_is_explicit(self) -> None:
        agent = LocalDeploymentAgent(
            security=self.security,
            project_root=self.project,
            versions=self.versions,
            git=_FailingGit(),
        )
        result = agent.deploy(
            self.proposal, self.tests, self.evaluation, self.approval
        )
        self.assertEqual(STATUS_DEPLOYED_HISTORY_FAILED, result.status)
        self.assertTrue(result.deployed)
        self.assertEqual(GIT_OPERATION_FAILED, result.git_status)
        self.assertEqual("", result.git_commit)
        self.assertEqual(b"# after\n", (self.project / "astra" / "hello.py").read_bytes())
        self.assertIsNotNone(self.versions.get(result.version))

    def test_version_manager_rollback_still_works(self) -> None:
        result = self.agent.deploy(
            self.proposal, self.tests, self.evaluation, self.approval
        )
        self.assertTrue(result.deployed)
        rb = self.agent.rollback(
            result.version,
            ApprovalRecord(
                subject=rollback_fingerprint(result.version),
                approved_by="tester",
                approved_at=datetime.now(timezone.utc),
            ),
        )
        self.assertEqual(STATUS_ROLLED_BACK, rb.status)
        self.assertEqual(b"# before\n", (self.project / "astra" / "hello.py").read_bytes())

    def test_restore_requires_matching_approval(self) -> None:
        result = self.agent.deploy(
            self.proposal, self.tests, self.evaluation, self.approval
        )
        before = (self.project / "astra" / "hello.py").read_bytes()
        denied = self.git.restore(result.git_commit, self.approval, version_id=result.version)
        self.assertEqual(RESTORE_DENIED, denied.status)
        self.assertEqual(before, (self.project / "astra" / "hello.py").read_bytes())
        (self.project / "astra" / "hello.py").write_bytes(b"# mutated\n")
        ok = self.git.restore(
            result.git_commit,
            ApprovalRecord(
                subject=git_restore_fingerprint(result.git_commit, result.version),
                approved_by="tester",
                approved_at=datetime.now(timezone.utc),
            ),
            version_id=result.version,
        )
        self.assertTrue(ok.restored)
        self.assertEqual(b"# after\n", (self.project / "astra" / "hello.py").read_bytes())
        self._assert_global_untouched()

    def test_wrong_version_id_restore_denied(self) -> None:
        result = self.agent.deploy(
            self.proposal, self.tests, self.evaluation, self.approval
        )
        before = (self.project / "astra" / "hello.py").read_bytes()
        rec = self.git.restore(
            result.git_commit,
            ApprovalRecord(
                subject=git_restore_fingerprint(result.git_commit, "d-ffffffffffffffff-9999"),
                approved_by="tester",
                approved_at=datetime.now(timezone.utc),
            ),
            version_id=result.version,
        )
        self.assertEqual(RESTORE_DENIED, rec.status)
        self.assertEqual(before, (self.project / "astra" / "hello.py").read_bytes())

    def test_fake_commit_hash_restore_fails(self) -> None:
        fake = "0" * 40
        rec = self.git.restore(
            fake,
            ApprovalRecord(
                subject=git_restore_fingerprint(fake),
                approved_by="tester",
                approved_at=datetime.now(timezone.utc),
            ),
        )
        self.assertEqual(RESTORE_FAILED, rec.status)
        self.assertEqual(b"# before\n", (self.project / "astra" / "hello.py").read_bytes())


class GitBoundaryTest(unittest.TestCase):
    def test_no_arbitrary_argv_or_message(self) -> None:
        sig = inspect.signature(GitRunner.commit)
        self.assertNotIn("argv", sig.parameters)
        self.assertNotIn("message", sig.parameters)
        self.assertFalse(hasattr(GitRunner, "run_argv"))
        self.assertFalse(hasattr(GitRunner, "run"))

    def test_shell_metacharacters_rejected_in_version_id(self) -> None:
        runner = GitRunner(project_root=".", executable=None)
        result = runner.commit("d-0123456789abcdef-0001; rm -rf /")
        self.assertFalse(result.ok)
        self.assertIn("version id rejected", result.reason)

    def test_newline_injection_rejected(self) -> None:
        runner = GitRunner(project_root=".", executable=None)
        result = runner.commit("d-0123456789abcdef-0001\n-m pwned")
        self.assertFalse(result.ok)

    def test_parse_commit_id_rejects_garbage(self) -> None:
        self.assertEqual("", parse_commit_id("not a hash"))
        self.assertEqual("", parse_commit_id("deadbeef\n" + "a" * 40))
        self.assertEqual("", parse_commit_id("; rm -rf /"))
        self.assertEqual("a" * 40, parse_commit_id("a" * 40 + "\nextra"))

    def test_scratch_cannot_be_repository(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        scratch = Path(tmp.name) / "scratch"
        data = Path(tmp.name) / "data"
        scratch.mkdir()
        data.mkdir()
        security = PolicySecurityAgent(write_roots=(data,), scratch_root=scratch)
        store = GitVersionStore(
            security=security,
            project_root=scratch,
            scratch_root=scratch,
            executable=_git_exe() or scratch / "git.exe",
        )
        rec = store.record_deployment(
            version_id="d-0123456789abcdef-0001",
            written=("hello.py",),
        )
        self.assertEqual(GIT_SECURITY_DENIED, rec.status)
        self.assertEqual("", rec.commit_id)

    def test_copied_session_cannot_be_repository(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        project = Path(tmp.name) / "project"
        scratch = Path(tmp.name) / "scratch"
        data = Path(tmp.name) / "data"
        project.mkdir()
        scratch.mkdir()
        data.mkdir()
        session = scratch / "deadbeefsession"
        session.mkdir()
        (session / "hello.py").write_bytes(b"# session\n")
        security = PolicySecurityAgent(
            write_roots=(data,), scratch_root=scratch, project_roots=(project,)
        )
        store = GitVersionStore(
            security=security,
            project_root=session,
            scratch_root=scratch,
            executable=_git_exe() or session / "git.exe",
            init_if_missing=True,
        )
        rec = store.record_deployment(
            version_id="d-0123456789abcdef-0001",
            written=(str(session / "hello.py"),),
        )
        self.assertEqual(GIT_SECURITY_DENIED, rec.status)

    def test_protected_paths_cannot_be_added(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        project = Path(tmp.name) / "project"
        scratch = Path(tmp.name) / "scratch"
        data = Path(tmp.name) / "data"
        project.mkdir()
        scratch.mkdir()
        data.mkdir()
        (project / "hello.py").write_bytes(b"# x\n")
        runner = GitRunner(
            project_root=project,
            executable=_git_exe(),
            scratch_root=scratch,
        )
        for rel in ("data/store.txt", "logs/app.log", "models/w.bin", "scratch/x.py"):
            result = runner.add((rel,))
            self.assertFalse(result.ok, rel)

    def test_path_traversal_rejected(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        project = Path(tmp.name) / "project"
        project.mkdir()
        runner = GitRunner(project_root=project, executable=_git_exe())
        result = runner.add(("../outside.py",))
        self.assertFalse(result.ok)

    def test_fake_git_directory_rejected(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        project = Path(tmp.name) / "project"
        data = Path(tmp.name) / "data"
        project.mkdir()
        data.mkdir()
        (project / ".git").write_bytes(b"gitdir: /tmp/evil\n")
        (project / "hello.py").write_bytes(b"# x\n")
        security = PolicySecurityAgent(write_roots=(data,), project_roots=(project,))
        store = GitVersionStore(
            security=security,
            project_root=project,
            executable=_git_exe() or project / "git.exe",
            init_if_missing=True,
        )
        rec = store.record_deployment(
            version_id="d-0123456789abcdef-0001",
            written=(str(project / "hello.py"),),
        )
        self.assertEqual(GIT_SECURITY_DENIED, rec.status)

    def test_symlink_repository_rejected(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        real = Path(tmp.name) / "real"
        link = Path(tmp.name) / "link"
        data = Path(tmp.name) / "data"
        real.mkdir()
        data.mkdir()
        try:
            os.symlink(real, link, target_is_directory=True)
        except OSError:
            self.skipTest("symlink privilege unavailable")
        security = PolicySecurityAgent(write_roots=(data,), project_roots=(link,))
        store = GitVersionStore(
            security=security,
            project_root=link,
            executable=_git_exe() or link / "git.exe",
        )
        rec = store.record_deployment(
            version_id="d-0123456789abcdef-0001",
            written=("hello.py",),
        )
        self.assertEqual(GIT_SECURITY_DENIED, rec.status)

    def test_git_nonzero_is_operation_failed(self) -> None:
        exe = _git_exe()
        if exe is None:
            self.skipTest("git executable not on PATH")
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        project = Path(tmp.name) / "project"
        data = Path(tmp.name) / "data"
        project.mkdir()
        data.mkdir()
        (project / "hello.py").write_bytes(b"# x\n")
        security = PolicySecurityAgent(write_roots=(data,), project_roots=(project,))
        store = GitVersionStore(
            security=security,
            project_root=project,
            executable=exe,
            init_if_missing=True,
        )
        with patch(
            "astra.deployment.gitrun.GitRunner.commit",
            return_value=__import__(
                "astra.deployment.gitrun", fromlist=["GitExecResult"]
            ).GitExecResult(reason="git exited non-zero", exit_code=1),
        ):
            rec = store.record_deployment(
                version_id="d-0123456789abcdef-0001",
                written=(str(project / "hello.py"),),
            )
        self.assertEqual(GIT_OPERATION_FAILED, rec.status)
        self.assertEqual("", rec.commit_id)

    def test_git_timeout_is_operation_failed(self) -> None:
        exe = _git_exe()
        if exe is None:
            self.skipTest("git executable not on PATH")
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        project = Path(tmp.name) / "project"
        data = Path(tmp.name) / "data"
        project.mkdir()
        data.mkdir()
        security = PolicySecurityAgent(write_roots=(data,), project_roots=(project,))
        store = GitVersionStore(
            security=security,
            project_root=project,
            executable=exe,
            init_if_missing=True,
        )
        with patch(
            "astra.deployment.gitrun.GitRunner.init_repository",
            return_value=__import__(
                "astra.deployment.gitrun", fromlist=["GitExecResult"]
            ).GitExecResult(reason="git timed out", timed_out=True),
        ):
            rec = store.record_deployment(
                version_id="d-0123456789abcdef-0001",
                written=(str(project / "hello.py"),),
            )
        self.assertEqual(GIT_OPERATION_FAILED, rec.status)


class GitIsolationTest(unittest.TestCase):
    def test_allowlist_schema_and_not_a_tool(self) -> None:
        self.assertEqual(
            frozenset({"read_text", "read_project"}),
            PRODUCTION_EXECUTION_ALLOWLIST,
        )
        self.assertEqual(6, ApplicationState().schema_version)
        names = {field.name for field in dataclasses.fields(ToolSpec)}
        self.assertNotIn("git", names)
        self.assertNotIn("git_history", names)

    def test_coding_evaluation_testing_have_no_git(self) -> None:
        root = Path(__file__).parent.parent / "astra"
        for package in ("coding", "evaluation", "testing"):
            for py_file in (root / package).glob("*.py"):
                text = py_file.read_text(encoding="utf-8")
                self.assertNotIn("GitVersionStore", text, py_file.name)
                self.assertNotIn("GitRunner", text, py_file.name)
                if py_file.name != "runner.py":
                    tree = ast.parse(text)
                    imported: list[str] = []
                    for node in ast.walk(tree):
                        if isinstance(node, ast.Import):
                            imported.extend(alias.name.split(".")[0] for alias in node.names)
                        elif isinstance(node, ast.ImportFrom) and node.module:
                            imported.append(node.module.split(".")[0])
                    if package == "testing" and py_file.name == "runner.py":
                        continue
                    if package != "testing":
                        self.assertNotIn("subprocess", imported, py_file.name)

    def test_testing_agent_still_has_no_subprocess(self) -> None:
        agent = (
            Path(__file__).parent.parent / "astra" / "testing" / "agent.py"
        ).read_text(encoding="utf-8")
        tree = ast.parse(agent)
        imported: list[str] = []
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.extend(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imported.append(node.module.split(".")[0])
        self.assertNotIn("subprocess", imported)

    def test_process_runner_not_used_for_git(self) -> None:
        gitrun = (
            Path(__file__).parent.parent / "astra" / "deployment" / "gitrun.py"
        ).read_text(encoding="utf-8")
        self.assertIn("import subprocess", gitrun)
        self.assertNotIn("ProcessRunner", gitrun)
        self.assertNotIn("shell=True", gitrun)
        self.assertIn("shell=False", gitrun)
        runner = (
            Path(__file__).parent.parent / "astra" / "testing" / "runner.py"
        ).read_text(encoding="utf-8")
        self.assertNotIn("GitRunner", runner)
        self.assertNotIn("gitstore", runner)

    def test_exactly_one_git_spawn_file(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "deployment"
        spawn_files = []
        for py_file in root.glob("*.py"):
            tree = ast.parse(py_file.read_text(encoding="utf-8"))
            names: list[str] = []
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    names.extend(alias.name.split(".")[0] for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names.append(node.module.split(".")[0])
            if "subprocess" in names:
                spawn_files.append(py_file.name)
        self.assertEqual(["gitrun.py"], spawn_files)

    def test_deploy_still_requires_approval(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        project = Path(tmp.name) / "project"
        data = Path(tmp.name) / "data"
        project.mkdir()
        data.mkdir()
        (project / "astra").mkdir()
        (project / "astra" / "hello.py").write_bytes(b"# before\n")
        security = PolicySecurityAgent(write_roots=(data,), project_roots=(project,))
        agent = LocalDeploymentAgent(
            security=security,
            project_root=project,
            git=GitVersionStore(security=security, project_root=project),
        )
        proposal = _proposal(("astra/hello.py", "# after\n"))
        result = agent.deploy(proposal, _tests(), _eval(), None)  # type: ignore[arg-type]
        self.assertFalse(result.deployed)
        self.assertEqual(b"# before\n", (project / "astra" / "hello.py").read_bytes())
        self.assertEqual("", result.git_commit)


if __name__ == "__main__":
    unittest.main()
