"""Security Agent tests (B-015, ADR-0014)."""

from __future__ import annotations

import ast
import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from astra.core.contracts import AgentRequest
from astra.security.agent import PolicySecurityAgent
from astra.security.contracts import ProcessSpec, SecurityAgent, Verdict


class NetworkPolicyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.agent = PolicySecurityAgent()

    def test_external_url_denied(self) -> None:
        d = self.agent.check_network_access("https://example.com")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_loopback_http_denied(self) -> None:
        for url in (
            "http://127.0.0.1:11434",
            "http://localhost:8080",
            "https://localhost/",
            "http://[::1]/",
        ):
            with self.subTest(url=url):
                d = self.agent.check_network_access(url)
                self.assertIs(Verdict.DENY, d.verdict)

    def test_allow_outbound_false_denied(self) -> None:
        agent = PolicySecurityAgent(allow_outbound_network=False)
        d = agent.check_network_access("https://example.org")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_allow_outbound_true_never_silent_allow(self) -> None:
        agent = PolicySecurityAgent(allow_outbound_network=True)
        d = agent.check_network_access("https://example.com")
        self.assertIsNot(Verdict.ALLOW, d.verdict)
        self.assertIs(Verdict.REQUIRES_APPROVAL, d.verdict)

    def test_loopback_denied_even_when_outbound_flag_true(self) -> None:
        agent = PolicySecurityAgent(allow_outbound_network=True)
        d = agent.check_network_access("http://127.0.0.1:11434")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_empty_url_fail_closed(self) -> None:
        d = self.agent.check_network_access("")
        self.assertIs(Verdict.DENY, d.verdict)
        self.assertTrue(d.reason)


class FileWritePolicyTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name) / "data"
        self.logs = Path(self._tmp.name) / "logs"
        self.root.mkdir()
        self.logs.mkdir()
        self.agent = PolicySecurityAgent(write_roots=(self.root, self.logs))
        self._before = {p.name for p in Path(self._tmp.name).iterdir()}

    def test_allowed_data_path(self) -> None:
        d = self.agent.check_file_write(self.root / "astra.db")
        self.assertIs(Verdict.ALLOW, d.verdict)

    def test_allowed_log_path(self) -> None:
        d = self.agent.check_file_write(self.logs / "astra.log")
        self.assertIs(Verdict.ALLOW, d.verdict)

    def test_windows_system_path_denied(self) -> None:
        d = self.agent.check_file_write(Path(r"C:\Windows\System32\evil.dll"))
        self.assertIs(Verdict.DENY, d.verdict)

    def test_arbitrary_path_denied(self) -> None:
        d = self.agent.check_file_write(Path(self._tmp.name) / "elsewhere" / "x")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_path_traversal_cannot_escape(self) -> None:
        d = self.agent.check_file_write(self.root / ".." / ".." / "Windows")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_empty_path_fail_closed(self) -> None:
        d = self.agent.check_file_write(Path(""))
        self.assertIs(Verdict.DENY, d.verdict)

    def test_no_filesystem_mutation(self) -> None:
        missing = self.root / "never-created.db"
        self.agent.check_file_write(missing)
        self.assertFalse(missing.exists())
        after = {p.name for p in Path(self._tmp.name).iterdir()}
        self.assertEqual(self._before, after)

    def test_empty_allowlist_fail_closed(self) -> None:
        agent = PolicySecurityAgent(write_roots=())
        d = agent.check_file_write(self.root / "x")
        self.assertIs(Verdict.DENY, d.verdict)


class FileReadPolicyTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name) / "repo"
        self.root.mkdir()
        (self.root / "data").mkdir()
        (self.root / ".git").mkdir()
        self.agent = PolicySecurityAgent(
            read_roots=(self.root,),
            write_roots=(),
            read_exclusions=(
                self.root / "data",
                self.root / "logs",
                self.root / ".git",
                self.root / "models",
            ),
        )

    def test_allowed_python_path(self) -> None:
        d = self.agent.check_file_read(self.root / "mod.py")
        self.assertIs(Verdict.ALLOW, d.verdict)

    def test_allowed_markdown_path(self) -> None:
        d = self.agent.check_file_read(self.root / "CONSTITUTION.md")
        self.assertIs(Verdict.ALLOW, d.verdict)

    def test_never_requires_approval(self) -> None:
        for path in (
            self.root / "mod.py",
            self.root / ".env",
            self.root / "data" / "x.md",
            Path(r"C:\Windows\System32\x.py"),
            Path(""),
        ):
            with self.subTest(path=str(path)):
                d = self.agent.check_file_read(path)
                self.assertIsNot(Verdict.REQUIRES_APPROVAL, d.verdict)

    def test_outside_root_denied(self) -> None:
        d = self.agent.check_file_read(Path(self._tmp.name) / "elsewhere.py")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_path_traversal_cannot_escape(self) -> None:
        d = self.agent.check_file_read(self.root / ".." / "elsewhere.py")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_env_denied(self) -> None:
        d = self.agent.check_file_read(self.root / ".env")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_git_denied(self) -> None:
        d = self.agent.check_file_read(self.root / ".git" / "config")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_data_prefix_denied(self) -> None:
        d = self.agent.check_file_read(self.root / "data" / "note.md")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_pem_denied(self) -> None:
        d = self.agent.check_file_read(self.root / "id.pem")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_gguf_denied(self) -> None:
        d = self.agent.check_file_read(self.root / "model.gguf")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_write_policy_unchanged_for_repo_py(self) -> None:
        d = self.agent.check_file_write(self.root / "mod.py")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_empty_read_roots_fail_closed(self) -> None:
        agent = PolicySecurityAgent(read_roots=())
        d = agent.check_file_read(self.root / "mod.py")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_empty_path_fail_closed(self) -> None:
        d = self.agent.check_file_read(Path(""))
        self.assertIs(Verdict.DENY, d.verdict)

    def test_no_filesystem_mutation(self) -> None:
        missing = self.root / "never-created.py"
        self.agent.check_file_read(missing)
        self.assertFalse(missing.exists())

    def test_from_settings_allows_constitution_denies_env(self) -> None:
        from astra.configuration.paths import project_root
        from astra.configuration.settings import AstraSettings

        agent = PolicySecurityAgent.from_settings(AstraSettings())
        allowed = agent.check_file_read(project_root() / "CONSTITUTION.md")
        self.assertIs(Verdict.ALLOW, allowed.verdict)
        denied = agent.check_file_read(project_root() / ".env")
        self.assertIs(Verdict.DENY, denied.verdict)
        write = agent.check_file_write(project_root() / "astra" / "coding" / "__init__.py")
        self.assertIs(Verdict.DENY, write.verdict)
        from astra.configuration.paths import default_scratch_directory

        scratch_dest = default_scratch_directory() / "overlay.py"
        temp_write = agent.check_temporary_write(scratch_dest)
        self.assertIs(Verdict.ALLOW, temp_write.verdict)
        self.assertIs(Verdict.DENY, agent.check_file_write(scratch_dest).verdict)
        self.assertIs(
            Verdict.DENY,
            agent.check_temporary_write(project_root() / "astra" / "coding" / "__init__.py").verdict,
        )

    def test_symlink_escape_denied(self) -> None:
        outside = Path(self._tmp.name) / "outside.py"
        outside.write_text("x", encoding="utf-8")
        link = self.root / "link.py"
        try:
            link.symlink_to(outside)
        except OSError:
            self.skipTest("symlink creation not permitted")
        d = self.agent.check_file_read(link)
        self.assertIs(Verdict.DENY, d.verdict)


class TemporaryWritePolicyTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.project = Path(self._tmp.name) / "project"
        self.scratch = Path(self._tmp.name) / "scratch"
        self.data = Path(self._tmp.name) / "data"
        self.project.mkdir()
        self.scratch.mkdir()
        self.data.mkdir()
        self.agent = PolicySecurityAgent(
            write_roots=(self.data,),
            scratch_root=self.scratch,
            project_roots=(self.project,),
        )

    def test_scratch_allowed(self) -> None:
        d = self.agent.check_temporary_write(self.scratch / "overlay.py")
        self.assertIs(Verdict.ALLOW, d.verdict)
        self.assertIsNot(Verdict.REQUIRES_APPROVAL, d.verdict)

    def test_project_denied(self) -> None:
        d = self.agent.check_temporary_write(self.project / "mod.py")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_scratch_not_a_file_write_root(self) -> None:
        d = self.agent.check_file_write(self.scratch / "overlay.py")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_empty_path_fail_closed(self) -> None:
        d = self.agent.check_temporary_write(Path(""))
        self.assertIs(Verdict.DENY, d.verdict)

    def test_missing_scratch_fail_closed(self) -> None:
        agent = PolicySecurityAgent(project_roots=(self.project,))
        d = agent.check_temporary_write(self.scratch / "x.py")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_scratch_inside_project_fail_closed(self) -> None:
        inner = self.project / "scratch"
        inner.mkdir()
        agent = PolicySecurityAgent(
            scratch_root=inner,
            project_roots=(self.project,),
        )
        d = agent.check_temporary_write(inner / "x.py")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_never_requires_approval(self) -> None:
        for path in (
            self.scratch / "ok.py",
            self.project / "mod.py",
            Path(""),
        ):
            with self.subTest(path=str(path)):
                d = self.agent.check_temporary_write(path)
                self.assertIsNot(Verdict.REQUIRES_APPROVAL, d.verdict)

    def test_permanent_change_still_not_allow(self) -> None:
        d = self.agent.check_permanent_change("apply overlay")
        self.assertIsNot(Verdict.ALLOW, d.verdict)

    def test_no_filesystem_mutation(self) -> None:
        missing = self.scratch / "never-created.py"
        self.agent.check_temporary_write(missing)
        self.assertFalse(missing.exists())


class ProcessExecutionPolicyTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.project = Path(self._tmp.name) / "project"
        self.scratch = Path(self._tmp.name) / "scratch"
        self.project.mkdir()
        self.scratch.mkdir()
        self.session = self.scratch / "session"
        self.session.mkdir()
        self.exe = self.scratch / "fake-python.exe"
        self.agent = PolicySecurityAgent(
            scratch_root=self.scratch,
            project_roots=(self.project,),
            process_executables=(self.exe,),
        )

    def _spec(self, **kwargs: object) -> ProcessSpec:
        values: dict[str, object] = {
            "argv": (str(self.exe), "-m", "unittest"),
            "cwd": self.session,
            "timeout_seconds": 1.0,
        }
        values.update(kwargs)
        return ProcessSpec(**values)  # type: ignore[arg-type]

    def test_allowlisted_scratch_cwd(self) -> None:
        d = self.agent.check_process_execution(self._spec())
        self.assertIs(Verdict.ALLOW, d.verdict)
        self.assertIsNot(Verdict.REQUIRES_APPROVAL, d.verdict)

    def test_scratch_root_cwd_denies(self) -> None:
        d = self.agent.check_process_execution(self._spec(cwd=self.scratch))
        self.assertIs(Verdict.DENY, d.verdict)

    def test_empty_allowlist_denies(self) -> None:
        agent = PolicySecurityAgent(
            scratch_root=self.scratch, project_roots=(self.project,)
        )
        d = agent.check_process_execution(self._spec())
        self.assertIs(Verdict.DENY, d.verdict)

    def test_empty_argv_denies(self) -> None:
        d = self.agent.check_process_execution(self._spec(argv=()))
        self.assertIs(Verdict.DENY, d.verdict)

    def test_shell_denies(self) -> None:
        d = self.agent.check_process_execution(self._spec(argv=("cmd.exe", "/c", "dir")))
        self.assertIs(Verdict.DENY, d.verdict)

    def test_python_dash_c_denies(self) -> None:
        d = self.agent.check_process_execution(
            self._spec(argv=(str(self.exe), "-c", "print(1)"))
        )
        self.assertIs(Verdict.DENY, d.verdict)

    def test_cwd_under_project_denies(self) -> None:
        d = self.agent.check_process_execution(self._spec(cwd=self.project))
        self.assertIs(Verdict.DENY, d.verdict)

    def test_cwd_outside_scratch_denies(self) -> None:
        other = Path(self._tmp.name) / "other"
        other.mkdir()
        d = self.agent.check_process_execution(self._spec(cwd=other))
        self.assertIs(Verdict.DENY, d.verdict)

    def test_timeout_zero_denies(self) -> None:
        d = self.agent.check_process_execution(self._spec(timeout_seconds=0))
        self.assertIs(Verdict.DENY, d.verdict)

    def test_timeout_above_cap_denies(self) -> None:
        d = self.agent.check_process_execution(self._spec(timeout_seconds=61))
        self.assertIs(Verdict.DENY, d.verdict)

    def test_pythonpath_env_denies(self) -> None:
        d = self.agent.check_process_execution(
            self._spec(env=(("PYTHONPATH", str(self.scratch)),))
        )
        self.assertIs(Verdict.DENY, d.verdict)

    def test_never_requires_approval(self) -> None:
        specs = (
            ProcessSpec(),
            self._spec(),
            self._spec(argv=("cmd.exe",)),
            self._spec(cwd=self.project),
        )
        for spec in specs:
            with self.subTest(spec=spec):
                d = self.agent.check_process_execution(spec)
                self.assertIsNot(Verdict.REQUIRES_APPROVAL, d.verdict)

    def test_from_settings_allows_only_interpreter(self) -> None:
        import sys

        from astra.configuration.settings import AstraSettings, TestingSettings

        agent = PolicySecurityAgent.from_settings(
            AstraSettings(testing=TestingSettings(scratch_directory=self.scratch))
        )
        d_ok = agent.check_process_execution(
            ProcessSpec(
                argv=(sys.executable, "-B", "-s", "-m", "astra.testing.probe"),
                cwd=self.session,
                timeout_seconds=5.0,
            )
        )
        self.assertIs(Verdict.ALLOW, d_ok.verdict)
        d_fake = agent.check_process_execution(self._spec())
        self.assertIs(Verdict.DENY, d_fake.verdict)
        d_root = agent.check_process_execution(
            ProcessSpec(
                argv=(sys.executable,),
                cwd=self.scratch,
                timeout_seconds=5.0,
            )
        )
        self.assertIs(Verdict.DENY, d_root.verdict)

    def test_permanent_change_still_not_allow(self) -> None:
        d = self.agent.check_permanent_change("spawn tests")
        self.assertIsNot(Verdict.ALLOW, d.verdict)


class PermanentChangeTest(unittest.TestCase):
    def test_never_allow(self) -> None:
        agent = PolicySecurityAgent()
        d = agent.check_permanent_change("deploy version 2")
        self.assertIsNot(Verdict.ALLOW, d.verdict)
        self.assertIs(Verdict.REQUIRES_APPROVAL, d.verdict)

    def test_empty_description_fail_closed(self) -> None:
        agent = PolicySecurityAgent()
        d = agent.check_permanent_change("  ")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_approval_flag_false_still_never_allow(self) -> None:
        agent = PolicySecurityAgent(require_human_approval=False)
        d = agent.check_permanent_change("sneak")
        self.assertIsNot(Verdict.ALLOW, d.verdict)
        self.assertIs(Verdict.DENY, d.verdict)


class RunAndHistoryTest(unittest.TestCase):
    def test_run_returns_honest_result(self) -> None:
        agent = PolicySecurityAgent()
        result = agent.run(AgentRequest(objective="status"))
        self.assertEqual("security", result.agent)
        self.assertTrue(result.success)
        self.assertFalse(result.details.get("executed"))
        self.assertIn("checks=", result.summary)

    def test_run_does_not_claim_execution(self) -> None:
        agent = PolicySecurityAgent()
        result = agent.run(AgentRequest(objective="approve all"))
        self.assertNotIn("executed action", result.summary.lower())
        self.assertIs(False, result.details.get("executed"))

    def test_from_settings_missing_fields_fail_closed(self) -> None:
        agent = PolicySecurityAgent.from_settings(object())
        d = agent.check_network_access("https://example.com")
        self.assertIs(Verdict.DENY, d.verdict)


class IsolationAndOfflineTest(unittest.TestCase):
    def test_no_forbidden_imports(self) -> None:
        root = Path(__file__).parent.parent / "astra" / "security"
        forbidden = {
            "llama_cpp",
            "faiss",
            "dearpygui",
            "ui",
            "subprocess",
            "httpx",
            "requests",
            "urllib3",
            "aiohttp",
        }
        for py_file in root.glob("*.py"):
            tree = ast.parse(py_file.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                names: list[str] = []
                if isinstance(node, ast.Import):
                    names = [alias.name.split(".")[0] for alias in node.names]
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names = [node.module.split(".")[0]]
                for name in names:
                    self.assertNotIn(name, forbidden, f"{py_file.name} imports {name}")

    def test_offline_checks(self) -> None:
        def _block(*_a, **_k):
            raise RuntimeError("network blocked")

        with patch.object(socket, "socket", side_effect=_block), patch.object(
            socket, "create_connection", side_effect=_block
        ), patch.object(socket, "getaddrinfo", side_effect=_block):
            agent = PolicySecurityAgent()
            d = agent.check_network_access("https://example.com")
        self.assertIs(Verdict.DENY, d.verdict)

    def test_milestone_check_not_hard_coded_true(self) -> None:
        import inspect

        from astra.presentation import progress

        src = inspect.getsource(progress._check_security_agent)
        self.assertIn("PolicySecurityAgent", src)
        self.assertNotIn("return True", src)
        self.assertTrue(issubclass(PolicySecurityAgent, SecurityAgent))


class ControllerSecurityTest(unittest.TestCase):
    def test_dispatch_and_factory(self) -> None:
        from astra.configuration.settings import (
            AstraSettings,
            KnowledgeSettings,
            LoggingSettings,
            MemorySettings,
            PlanningSettings,
        )
        from astra.core.astra_controller import AstraController
        from astra.core.errors import ControllerNotStartedError

        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        settings = AstraSettings(
            memory=MemorySettings(
                database_path=root / "mem.db",
                vector_index_path=root / "vectors.faiss",
            ),
            knowledge=KnowledgeSettings(database_path=root / "knowledge.db"),
            planning=PlanningSettings(database_path=root / "planning.db"),
            logging=LoggingSettings(directory=root / "logs"),
        )
        injected = PolicySecurityAgent(write_roots=(root,))
        ctrl = AstraController(settings, security_factory=lambda _: injected)
        with self.assertRaises(ControllerNotStartedError):
            _ = ctrl.security
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        self.assertIs(injected, ctrl.security)
        self.assertIn("security", ctrl.agent_names)
        result = ctrl.dispatch("security", AgentRequest(objective="policy"))
        self.assertEqual("security", result.agent)
        self.assertTrue(result.success)
        state = ctrl.get_state()
        self.assertEqual(6, state.schema_version)
        sec = next(m for m in state.modules if m.module == "astra.security")
        from astra.state import CapabilityStatus

        self.assertEqual(CapabilityStatus.OPERATIONAL, sec.status)
        ctrl.shutdown()
        ctrl.shutdown()


class ResearchConsultTest(unittest.TestCase):
    def test_allow_network_consults_and_stays_offline(self) -> None:
        from astra.research.agent import LocalResearchAgent
        from astra.research.source import TextSource

        agent = PolicySecurityAgent()
        research = LocalResearchAgent(
            sources=[TextSource("t", "Python is a language.")],
            security=agent,
        )

        def _block(*_a, **_k):
            raise AssertionError("network access attempted")

        with patch.object(socket, "socket", side_effect=_block), patch.object(
            socket, "create_connection", side_effect=_block
        ), patch.object(socket, "getaddrinfo", side_effect=_block):
            report = research.investigate("Python", allow_network=True)
        self.assertFalse(report.used_network)
        self.assertTrue(any("network deny" in q for q in report.open_questions))
        self.assertGreaterEqual(agent.checks_total, 1)
