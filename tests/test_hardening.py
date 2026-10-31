"""Security hardening tests (B-035, ADR-0034).

Policy-gated fail-closed architecture. Not an OS sandbox. Live project bytes
must stay identical after unauthorized attempts.
"""

from __future__ import annotations

import ast
import inspect
import os
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import MagicMock

from astra.coding.contracts import ChangeProposal, FileChange
from astra.core.lifecycle import MeasurableProperty
from astra.deployment.agent import LocalDeploymentAgent
from astra.deployment.apply import commit_files, restore_entries
from astra.deployment.contracts import ApprovalRecord, STATUS_INVALID_PROPOSAL, STATUS_TESTS_FAILED
from astra.deployment.fingerprint import evidence_fingerprint
from astra.deployment.paths import resolve_dest
from astra.evaluation.agent import LocalEvaluationAgent
from astra.evaluation.contracts import EvaluationReport
from astra.plugins.contracts import KIND_DESCRIPTOR, PluginDescriptor, PluginError
from astra.plugins.registry import LocalPluginRegistry
from astra.security.agent import PolicySecurityAgent
from astra.security.contracts import PolicyDecision, Verdict
from astra.state import ApplicationState
from astra.testing.agent import LocalTestingAgent
from astra.testing.contracts import TestReport
from astra.testing.subset import CopiedSubsetWorkspace, _denied_relative
from astra.testing.workspace import (
    ProposalWorkspace,
    is_non_relative_path,
    secret_shaped_reason,
)
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST
from astra.tools.contracts import ToolSpec


def _proposal(*pairs: tuple[str, str]) -> ChangeProposal:
    changes = tuple(
        FileChange(path=Path(path), new_content=body, rationale="hard")
        for path, body in pairs
    )
    return ChangeProposal(
        objective="hardening overlay",
        changes=changes,
        improves=(MeasurableProperty.MAINTAINABILITY,),
    )


def _write_mini(project: Path) -> None:
    (project / "astra").mkdir()
    (project / "astra" / "__init__.py").write_bytes(b"# astra-marker\n")
    (project / "astra" / "hello.py").write_bytes(b"# before\n")
    (project / "tests").mkdir()
    (project / "tests" / "__init__.py").write_bytes(b"")
    (project / "tests" / "test_ok.py").write_bytes(
        b"import unittest\nclass OkTest(unittest.TestCase):\n"
        b"    def test_ok(self) -> None:\n        self.assertTrue(True)\n"
    )
    (project / "CONSTITUTION.md").write_bytes(b"# constitution-marker\n")


def _tree_bytes(root: Path) -> dict[str, bytes]:
    out: dict[str, bytes] = {}
    for path in sorted(root.rglob("*")):
        if path.is_file() and not path.is_symlink():
            out[path.relative_to(root).as_posix()] = path.read_bytes()
    return out


def _passing_tests(**kwargs: object) -> TestReport:
    fields: dict[str, object] = dict(
        tests_run=1,
        tests_passed=1,
        tests_failed=0,
        tests_errors=0,
        tests_skipped=0,
        exit_code=0,
    )
    fields.update(kwargs)
    return TestReport(**fields)  # type: ignore[arg-type]


def _eval_ok() -> EvaluationReport:
    return EvaluationReport(
        subject="hardening overlay",
        notes="ok",
        tests_passed=True,
        evaluation_passed=True,
        deployment_authorized=False,
    )


class HardeningHarness(unittest.TestCase):
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
        )
        self.workspace = CopiedSubsetWorkspace(
            security=self.security,
            scratch_root=self.scratch,
            project_root=self.project,
        )
        self.overlay = ProposalWorkspace(
            security=self.security,
            scratch_root=self.scratch,
            project_root=self.project,
        )
        self.deployment = LocalDeploymentAgent(
            security=self.security,
            project_root=self.project,
        )
        self.evaluation = LocalEvaluationAgent()

    def _snap(self) -> dict[str, bytes]:
        return _tree_bytes(self.project)

    def _assert_live(self, before: dict[str, bytes]) -> None:
        self.assertEqual(before, _tree_bytes(self.project))


class PathAttackTest(HardeningHarness):
    def test_case_fold_secret_names_are_not_copied(self) -> None:
        (self.project / "astra" / "DATA").mkdir()
        (self.project / "astra" / "DATA" / "leak.py").write_bytes(b"secret\n")
        (self.project / "astra" / "Credentials.json").write_bytes(b"{}\n")
        (self.project / "astra" / "ID_RSA").write_bytes(b"key\n")
        before = self._snap()
        copied = self.workspace.copy()
        self.assertTrue(copied.success)
        root = copied.subset_root
        assert root is not None
        self.assertFalse((root / "astra" / "DATA").exists())
        self.assertFalse((root / "astra" / "Credentials.json").exists())
        self.assertFalse((root / "astra" / "ID_RSA").exists())
        self._assert_live(before)

    def test_denied_relative_is_case_insensitive(self) -> None:
        self.assertTrue(_denied_relative(Path("DATA") / "leak.py"))
        self.assertTrue(_denied_relative(Path("Credentials.json")))
        self.assertTrue(_denied_relative(Path("ID_RSA")))
        self.assertTrue(_denied_relative(Path(".ENV")))

    def test_overlay_secret_paths_fail_closed(self) -> None:
        copied = self.workspace.copy()
        self.assertTrue(copied.success)
        before = self._snap()
        for rel in (".env", "credentials.json", "data/store.txt", "secrets.json"):
            with self.subTest(path=rel):
                result = self.workspace.overlay(copied.session_id, _proposal((rel, "pwned\n")))
                self.assertFalse(result.success)
                self.assertFalse((copied.subset_root / Path(rel)).exists())  # type: ignore[operator]
        material = self.overlay.materialize(_proposal((".env", "pwned\n")))
        self.assertFalse(material.success)
        self._assert_live(before)

    def test_absolute_and_unc_proposal_paths_fail_closed(self) -> None:
        before = self._snap()
        inside = self.project / "astra" / "hello.py"
        dest, rel, err = resolve_dest(inside, self.project)
        self.assertIsNone(dest)
        self.assertIsNone(rel)
        self.assertIn("rejected", err)
        unc = r"\\server\share\astra\hello.py"
        dest, rel, err = resolve_dest(unc, self.project)
        self.assertIsNone(dest)
        self.assertIn("rejected", err)
        self.assertTrue(is_non_relative_path(unc))
        self.assertTrue(is_non_relative_path("C:/Windows/astra/hello.py"))
        result = self.overlay.materialize(
            ChangeProposal(
                objective="abs",
                changes=(FileChange(path=inside, new_content="nope\n"),),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
        )
        self.assertFalse(result.success)
        self._assert_live(before)

    def test_commit_without_project_fails_closed(self) -> None:
        before = self._snap()
        dest = self.project / "astra" / "hello.py"
        written, err = commit_files(((dest, "pwned\n"),))
        self.assertEqual((), written)
        self.assertIn("project_root required", err)
        self._assert_live(before)

    def test_symlink_destination_refused_at_write_time(self) -> None:
        outside = Path(self._tmp.name) / "outside.py"
        outside.write_bytes(b"safe\n")
        link = self.project / "astra" / "link.py"
        try:
            link.symlink_to(outside)
        except OSError:
            self.skipTest("symlink privilege unavailable")
        before = self._snap()
        written, err = commit_files(((link, "pwned\n"),), project=self.project)
        self.assertEqual((), written)
        self.assertIn("symlink", err)
        self.assertEqual(b"safe\n", outside.read_bytes())
        restored, deleted, rerr = restore_entries(((link, b"old\n"),), project=self.project)
        self.assertEqual((), restored)
        self.assertEqual((), deleted)
        self.assertIn("symlink", rerr)
        self._assert_live(before)


class EvidenceAttackTest(HardeningHarness):
    def test_nonzero_exit_code_is_not_passing_evidence(self) -> None:
        before = self._snap()
        report = TestReport(
            tests_run=1,
            tests_passed=1,
            tests_failed=0,
            tests_errors=0,
            exit_code=1,
        )
        self.assertFalse(report.passed)
        evaluated = self.evaluation.evaluate_proposal(_proposal(("astra/hello.py", "# x\n")), report)
        self.assertFalse(evaluated.evaluation_passed)
        deployed = self.deployment.deploy(
            _proposal(("astra/hello.py", "# x\n")),
            report,
            _eval_ok(),
            ApprovalRecord(
                subject=evidence_fingerprint(
                    _proposal(("astra/hello.py", "# x\n")), report, _eval_ok()
                ),
                approved_by="tester",
                approved_at=datetime.now(timezone.utc),
            ),
        )
        self.assertFalse(deployed.deployed)
        self.assertEqual(STATUS_TESTS_FAILED, deployed.status)
        self._assert_live(before)

    def test_path_case_is_normalized_in_fingerprint(self) -> None:
        tests = _passing_tests()
        evaluation = _eval_ok()
        left = _proposal(("astra/Hello.py", "x\n"))
        right = _proposal(("astra/hello.py", "x\n"))
        left_fp = evidence_fingerprint(left, tests, evaluation)
        right_fp = evidence_fingerprint(right, tests, evaluation)
        if os.path.normcase("Hello.py") == os.path.normcase("hello.py"):
            self.assertEqual(left_fp, right_fp)

    def test_forbidden_deploy_paths_do_not_write(self) -> None:
        before = self._snap()
        tests = _passing_tests()
        evaluation = _eval_ok()
        proposal = _proposal(("data/store.txt", "nope\n"))
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


class ProcessAndBoundaryTest(HardeningHarness):
    def test_init_py_write_requires_temporary_allow(self) -> None:
        subset = MagicMock()
        subset._security = MagicMock()
        subset._security.check_temporary_write.return_value = PolicyDecision(
            verdict=Verdict.DENY, reason="denied init"
        )
        runner = MagicMock()
        runner._security = subset._security
        agent = LocalTestingAgent(subset=subset, runner=runner)
        session = self.scratch / "sess"
        (session / "tests").mkdir(parents=True)
        report = agent._run_unittest("cmd", ("python",), "sess", session)
        self.assertTrue(report.incomplete)
        self.assertFalse((session / "tests" / "__init__.py").exists())
        self.assertFalse(report.passed)

    def test_nvidia_smi_uses_minimal_env_and_no_shell(self) -> None:
        src = (
            Path(__file__).resolve().parents[1]
            / "astra"
            / "presentation"
            / "provider.py"
        ).read_text(encoding="utf-8")
        self.assertIn("shell=False", src)
        self.assertIn("_nvidia_smi_env", src)
        self.assertNotIn("shell=True", src)

    def test_plugin_unc_and_privileged_services_fail_closed(self) -> None:
        registry = LocalPluginRegistry(plugin_root=self.scratch / "plugins")
        with self.assertRaises(PluginError):
            registry.register(
                PluginDescriptor(
                    plugin_id="uncplug",
                    name="UNC",
                    version="1.0.0",
                    kind=KIND_DESCRIPTOR,
                    trusted=False,
                    source_path=r"\\server\share\plugin",
                )
            )
        self.assertFalse(hasattr(registry, "run"))
        self.assertFalse(hasattr(registry, "commit_files"))
        self.assertFalse(hasattr(registry, "_runner"))

    def test_coding_evaluation_plugins_security_have_no_subprocess(self) -> None:
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

    def test_allowlist_and_schema_unchanged(self) -> None:
        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))
        names = {field.name for field in __import__("dataclasses").fields(ToolSpec)}
        self.assertNotIn("process", names)
        self.assertEqual(6, ApplicationState().schema_version)
        self.assertIsNone(secret_shaped_reason(Path("astra") / "hello.py"))
        self.assertIsNotNone(secret_shaped_reason(Path(".env")))

    def test_milestone_check_not_hard_coded(self) -> None:
        from astra.presentation import progress

        src = inspect.getsource(progress._check_security_hardening)
        self.assertNotIn("return True", src)
        self.assertTrue(progress._check_security_hardening())
        adr = (
            Path(__file__).resolve().parents[1]
            / "docs"
            / "adr"
            / "ADR-0034-security-hardening-audit.md"
        )
        text = adr.read_text(encoding="utf-8")
        self.assertIn("implemented in B-035", text)
        self.assertIn("not an os sandbox", text.lower())
        self.assertIn("Job Object", text)
