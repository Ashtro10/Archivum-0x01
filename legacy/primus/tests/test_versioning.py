"""VersionManager + honest rollback tests (B-028, ADR-0027)."""

from __future__ import annotations

import ast
import inspect
import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

from astra.coding.contracts import ChangeProposal, FileChange
from astra.core.lifecycle import MeasurableProperty
from astra.deployment.agent import LocalDeploymentAgent
from astra.deployment.apply import restore_entries
from astra.deployment.contracts import (
    ApprovalRecord,
    STATUS_APPROVAL_INVALID,
    STATUS_APPROVAL_MISSING,
    STATUS_CORRUPTED,
    STATUS_DEPLOYED,
    STATUS_DEPLOYMENT_ERROR,
    STATUS_INVALID_PROPOSAL,
    STATUS_ROLLBACK_ERROR,
    STATUS_ROLLED_BACK,
    STATUS_SECURITY_DENIED,
    STATUS_UNKNOWN_VERSION,
)
from astra.deployment.fingerprint import evidence_fingerprint, rollback_fingerprint
from astra.deployment.store import LocalVersionManager
from astra.deployment.versioning import VersionManager
from astra.evaluation.contracts import EvaluationReport
from astra.security.agent import PolicySecurityAgent
from astra.security.contracts import PolicyDecision, Verdict
from astra.testing.contracts import TestReport
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST


def _proposal(*pairs: tuple[str, str]) -> ChangeProposal:
    changes = tuple(
        FileChange(path=Path(path), new_content=body, rationale="test")
        for path, body in pairs
    )
    return ChangeProposal(
        objective="version overlay",
        changes=changes,
        improves=(MeasurableProperty.MAINTAINABILITY,),
    )


def _tests() -> TestReport:
    return TestReport(tests_run=1, tests_passed=1)


def _eval() -> EvaluationReport:
    return EvaluationReport(
        subject="version overlay", tests_passed=True, evaluation_passed=True
    )


def _deploy_approval(
    proposal: ChangeProposal, tests: TestReport, evaluation: EvaluationReport
) -> ApprovalRecord:
    return ApprovalRecord(
        subject=evidence_fingerprint(proposal, tests, evaluation),
        approved_by="tester",
        approved_at=datetime.now(timezone.utc),
    )


def _rb_approval(version_id: str) -> ApprovalRecord:
    return ApprovalRecord(
        subject=rollback_fingerprint(version_id),
        approved_by="tester",
        approved_at=datetime.now(timezone.utc),
    )


class _ForcePermanent:
    def __init__(self, verdict: Verdict) -> None:
        self._verdict = verdict

    def check_permanent_change(self, description: str) -> PolicyDecision:
        return PolicyDecision(verdict=self._verdict, reason="forced")

    def check_temporary_write(self, path: Path) -> PolicyDecision:
        return PolicyDecision(verdict=Verdict.ALLOW, reason="scratch")


class VersionManagerRollbackTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        root = Path(self._tmp.name)
        self.project = root / "project"
        self.data = root / "data"
        self.scratch = root / "scratch"
        self.project.mkdir()
        self.data.mkdir()
        self.scratch.mkdir()
        (self.project / "astra").mkdir()
        (self.project / "astra" / "hello.py").write_bytes(b"# before\n")
        (self.project / "keep.py").write_bytes(b"# keep\n")
        self.security = PolicySecurityAgent(
            write_roots=(self.data,),
            project_roots=(self.project,),
            scratch_root=self.scratch,
        )
        self.versions = LocalVersionManager(
            security=self.security,
            project_root=self.project,
            store_root=self.scratch / "versions",
        )
        self.agent = LocalDeploymentAgent(
            security=self.security,
            project_root=self.project,
            versions=self.versions,
        )
        self.tests = _tests()
        self.evaluation = _eval()

    def _hello(self) -> bytes:
        return (self.project / "astra" / "hello.py").read_bytes()

    def _keep(self) -> bytes:
        return (self.project / "keep.py").read_bytes()

    def _deploy(self, *pairs: tuple[str, str]):
        proposal = _proposal(*pairs)
        result = self.agent.deploy(
            proposal,
            self.tests,
            self.evaluation,
            _deploy_approval(proposal, self.tests, self.evaluation),
        )
        return proposal, result

    def test_successful_deploy_creates_version_snapshot(self) -> None:
        _, result = self._deploy(("astra/hello.py", "# after\n"))
        self.assertEqual(STATUS_DEPLOYED, result.status)
        self.assertTrue(result.version)
        record = self.versions.get(result.version)
        self.assertIsNotNone(record)
        assert record is not None
        self.assertEqual(result.version, record.version)
        self.assertEqual(1, record.file_count)
        self.assertTrue(record.integrity)
        blob = (
            self.scratch
            / "versions"
            / result.version
            / "files"
            / "astra"
            / "hello.py"
        )
        self.assertEqual(b"# before\n", blob.read_bytes())
        self.assertEqual(b"# after\n", self._hello())

    def test_new_file_records_absence(self) -> None:
        _, result = self._deploy(("astra/new.py", "# new\n"))
        self.assertTrue(result.deployed)
        manifest = json.loads(
            (
                self.scratch / "versions" / result.version / "manifest.json"
            ).read_text(encoding="utf-8")
        )
        entry = next(item for item in manifest["files"] if item["path"] == "astra/new.py")
        self.assertFalse(entry["existed"])
        self.assertFalse(
            (
                self.scratch
                / "versions"
                / result.version
                / "files"
                / "astra"
                / "new.py"
            ).exists()
        )

    def test_rollback_restores_modified_bytes(self) -> None:
        original = self._hello()
        _, result = self._deploy(("astra/hello.py", "# after\n"))
        rolled = self.agent.rollback(result.version, _rb_approval(result.version))
        self.assertTrue(rolled.rolled_back)
        self.assertEqual(STATUS_ROLLED_BACK, rolled.status)
        self.assertEqual(original, self._hello())
        self.assertEqual(b"# keep\n", self._keep())

    def test_rollback_deletes_newly_created_files(self) -> None:
        created = self.project / "astra" / "new.py"
        self.assertFalse(created.exists())
        _, result = self._deploy(("astra/new.py", "# new\n"))
        self.assertTrue(created.is_file())
        rolled = self.agent.rollback(result.version, _rb_approval(result.version))
        self.assertTrue(rolled.rolled_back)
        self.assertFalse(created.exists())
        self.assertEqual(b"# keep\n", self._keep())

    def test_wrong_approval_cannot_rollback(self) -> None:
        before_deploy = self._hello()
        _, first = self._deploy(("astra/hello.py", "# after\n"))
        _, second = self._deploy(("astra/hello.py", "# later\n"))
        stolen = _rb_approval(second.version)
        live = self._hello()
        rolled = self.agent.rollback(first.version, stolen)
        self.assertEqual(STATUS_APPROVAL_INVALID, rolled.status)
        self.assertFalse(rolled.rolled_back)
        self.assertEqual(live, self._hello())
        self.assertNotEqual(before_deploy, live)

    def test_missing_approval_cannot_rollback(self) -> None:
        _, result = self._deploy(("astra/hello.py", "# after\n"))
        live = self._hello()
        rolled = self.agent.rollback(result.version, None)  # type: ignore[arg-type]
        self.assertEqual(STATUS_APPROVAL_MISSING, rolled.status)
        self.assertEqual(live, self._hello())

    def test_unknown_version_fails(self) -> None:
        _, result = self._deploy(("astra/hello.py", "# after\n"))
        live = self._hello()
        rolled = self.agent.rollback("d-deadbeefdeadbeef-0001", _rb_approval("d-deadbeefdeadbeef-0001"))
        self.assertEqual(STATUS_UNKNOWN_VERSION, rolled.status)
        self.assertEqual(live, self._hello())
        self.assertTrue(result.version)

    def test_corrupted_snapshot_fails_closed(self) -> None:
        _, result = self._deploy(("astra/hello.py", "# after\n"))
        blob = (
            self.scratch
            / "versions"
            / result.version
            / "files"
            / "astra"
            / "hello.py"
        )
        blob.write_bytes(b"# tampered\n")
        live = self._hello()
        rolled = self.agent.rollback(result.version, _rb_approval(result.version))
        self.assertEqual(STATUS_CORRUPTED, rolled.status)
        self.assertFalse(rolled.rolled_back)
        self.assertEqual(live, self._hello())

    def test_path_traversal_fails_closed(self) -> None:
        _, result = self._deploy(("astra/hello.py", "# after\n"))
        manifest_path = self.scratch / "versions" / result.version / "manifest.json"
        raw = json.loads(manifest_path.read_text(encoding="utf-8"))
        raw["files"][0]["path"] = "../escape.py"
        manifest_path.write_text(json.dumps(raw), encoding="utf-8")
        live = self._hello()
        outside = self.project.parent / "escape.py"
        rolled = self.agent.rollback(result.version, _rb_approval(result.version))
        self.assertIn(rolled.status, {STATUS_CORRUPTED, STATUS_INVALID_PROPOSAL})
        self.assertFalse(rolled.rolled_back)
        self.assertEqual(live, self._hello())
        self.assertFalse(outside.exists())

    def test_symlink_destination_fails_closed(self) -> None:
        _, result = self._deploy(("astra/hello.py", "# after\n"))
        target = self.project / "astra" / "hello.py"
        keep = self.project / "keep.py"
        before_keep = keep.read_bytes()
        try:
            target.unlink()
            target.symlink_to(keep)
        except OSError:
            self.skipTest("symlink privilege unavailable")
        rolled = self.agent.rollback(result.version, _rb_approval(result.version))
        self.assertFalse(rolled.rolled_back)
        self.assertEqual(STATUS_INVALID_PROPOSAL, rolled.status)
        self.assertEqual(before_keep, keep.read_bytes())

    def test_security_deny_causes_zero_writes(self) -> None:
        _, result = self._deploy(("astra/hello.py", "# after\n"))
        live = self._hello()
        self.versions._security = _ForcePermanent(Verdict.DENY)
        rolled = self.agent.rollback(result.version, _rb_approval(result.version))
        self.assertEqual(STATUS_SECURITY_DENIED, rolled.status)
        self.assertEqual((), rolled.restored)
        self.assertEqual((), rolled.deleted)
        self.assertEqual(live, self._hello())

    def test_unexpected_allow_causes_zero_writes(self) -> None:
        _, result = self._deploy(("astra/hello.py", "# after\n"))
        live = self._hello()
        self.versions._security = _ForcePermanent(Verdict.ALLOW)
        rolled = self.agent.rollback(result.version, _rb_approval(result.version))
        self.assertEqual(STATUS_SECURITY_DENIED, rolled.status)
        self.assertEqual((), rolled.restored)
        self.assertEqual((), rolled.deleted)
        self.assertEqual(live, self._hello())

    def test_partial_write_is_reported_honestly(self) -> None:
        (self.project / "astra" / "second.py").write_bytes(b"# old-second\n")
        _, result = self._deploy(
            ("astra/hello.py", "# after\n"),
            ("astra/second.py", "# second\n"),
        )
        self.assertTrue(result.deployed)

        def _partial(planned: tuple, **_kwargs: object) -> tuple:
            dest, payload = planned[0]
            dest.write_bytes(payload)
            return ((dest,), (), "disk full")

        with patch("astra.deployment.store.restore_entries", side_effect=_partial):
            rolled = self.agent.rollback(result.version, _rb_approval(result.version))
        self.assertEqual(STATUS_ROLLBACK_ERROR, rolled.status)
        self.assertFalse(rolled.rolled_back)
        self.assertEqual(1, len(rolled.restored))
        self.assertEqual(b"# before\n", self._hello())
        self.assertEqual(b"# second\n", (self.project / "astra" / "second.py").read_bytes())

    def test_multiple_deployments_are_distinct_and_immutable(self) -> None:
        _, first = self._deploy(("astra/hello.py", "# v1\n"))
        first_blob = (
            self.scratch
            / "versions"
            / first.version
            / "files"
            / "astra"
            / "hello.py"
        ).read_bytes()
        _, second = self._deploy(("astra/hello.py", "# v2\n"))
        self.assertNotEqual(first.version, second.version)
        self.assertEqual(b"# before\n", first_blob)
        self.assertEqual(
            b"# v1\n",
            (
                self.scratch
                / "versions"
                / second.version
                / "files"
                / "astra"
                / "hello.py"
            ).read_bytes(),
        )
        history = self.versions.history()
        self.assertEqual(second.version, history[0].version)
        self.assertEqual(first.version, history[1].version)
        self.assertEqual(b"# before\n", first_blob)

    def test_rollback_targets_requested_version_not_latest(self) -> None:
        _, first = self._deploy(("astra/hello.py", "# v1\n"))
        _, second = self._deploy(("astra/hello.py", "# v2\n"))
        self.assertEqual(b"# v2\n", self._hello())
        rolled = self.agent.rollback(first.version, _rb_approval(first.version))
        self.assertTrue(rolled.rolled_back)
        self.assertEqual(b"# before\n", self._hello())
        self.assertNotEqual(first.version, second.version)

    def test_snapshot_persist_failure_is_honest(self) -> None:
        with patch.object(
            self.versions, "save_preimage", return_value=("", "persist failed")
        ):
            _, result = self._deploy(("astra/hello.py", "# after\n"))
        self.assertEqual(STATUS_DEPLOYMENT_ERROR, result.status)
        self.assertFalse(result.deployed)
        self.assertEqual("", result.version)
        self.assertEqual(b"# after\n", self._hello())

    def test_no_git_subprocess_or_process_runner(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "deployment"
        forbidden = {"subprocess", "git"}
        for py_file in root.glob("*.py"):
            text = py_file.read_text(encoding="utf-8")
            self.assertNotIn("ProcessRunner", text, py_file.name)
            tree = ast.parse(text)
            names: list[str] = []
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    names.extend(alias.name.split(".")[0] for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names.append(node.module.split(".")[0])
            if py_file.name in {"gitrun.py", "gitstore.py"}:
                if py_file.name == "gitrun.py":
                    self.assertIn("subprocess", names)
                else:
                    self.assertNotIn("subprocess", names)
                self.assertNotIn("git", names)
                continue
            self.assertNotIn("import git", text, py_file.name)
            for name in forbidden:
                self.assertNotIn(name, names, py_file.name)

    def test_allowlist_and_schema(self) -> None:
        from astra.state import ApplicationState

        self.assertEqual(
            frozenset({"read_text", "read_project"}),
            PRODUCTION_EXECUTION_ALLOWLIST,
        )
        self.assertEqual(6, ApplicationState().schema_version)
        self.assertIsNot(
            Verdict.ALLOW,
            self.security.check_permanent_change("rollback").verdict,
        )

    def test_version_manager_is_not_an_agent(self) -> None:
        from astra.core.contracts import Agent

        self.assertFalse(issubclass(LocalVersionManager, Agent))
        self.assertTrue(issubclass(LocalVersionManager, VersionManager))

    def test_abc_rollback_still_raises(self) -> None:
        with self.assertRaises(NotImplementedError):
            VersionManager.rollback(object(), "d-x", _rb_approval("d-x"))  # type: ignore[misc]

    def test_restore_entries_is_the_rollback_write_boundary(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        dest = Path(tmp.name) / "out.py"
        dest.write_bytes(b"new\n")
        restored, deleted, err = restore_entries(
            ((dest, b"old\n"),), project=Path(tmp.name)
        )
        self.assertEqual("", err)
        self.assertEqual((dest,), restored)
        self.assertEqual((), deleted)
        self.assertEqual(b"old\n", dest.read_bytes())
        gone = Path(tmp.name) / "gone.py"
        gone.write_bytes(b"x\n")
        restored, deleted, err = restore_entries(((gone, None),), project=Path(tmp.name))
        self.assertEqual("", err)
        self.assertEqual((gone,), deleted)
        self.assertFalse(gone.exists())

    def test_milestone_check_not_hard_coded(self) -> None:
        from astra.presentation import progress

        src = inspect.getsource(progress._check_version_rollback)
        self.assertIn("LocalVersionManager", src)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_version_rollback())

    def test_prior_adrs_untouched(self) -> None:
        root = Path(__file__).parent.parent / "docs" / "adr"
        text = (root / "ADR-0026-human-approved-controlled-deployment.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("implemented in B-027", text)
        self.assertIn("No Git, no VersionManager, no rollback", text)
        self.assertTrue(
            (root / "ADR-0027-version-manager-and-honest-rollback.md").is_file()
        )


if __name__ == "__main__":
    unittest.main()
