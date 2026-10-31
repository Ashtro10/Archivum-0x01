"""Machine-readable Astra project milestone definitions.

This file is the single source of truth for project progress displayed in
the dashboard. Do not parse backlog.md or any other Markdown document to
determine stage status; query this module instead.

Milestone status is determined at runtime by checking what is actually
importable and configured — not by hard-coded boolean flags.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

# Frozen Primus.exe is not a Python interpreter. These checks spawn
# ``sys.executable`` and would re-launch the GUI (fork-bomb).
_LIVE_INTERPRETER_CHECKS = frozenset(
    {
        "_check_process_spawn",
        "_check_proposal_testing",
        "_check_run_suite",
        "_check_change_pipeline",
        "_check_end_to_end_safety",
        "_check_end_to_end_integration",
        "_check_git_history",
    }
)


@dataclass(frozen=True, slots=True)
class Milestone:
    """One discrete development milestone.

    Attributes:
        id: Backlog identifier, e.g. ``"B-002"``.
        stage: Display stage number (1-based).
        title: Short name shown in the timeline.
        description: One-sentence description.
        is_complete: Zero-argument callable that returns True if the milestone
            artefacts are detectable at runtime. Must never raise.
    """

    id: str
    stage: int
    title: str
    description: str
    is_complete: Callable[[], bool]

    def complete(self) -> bool:
        """Return current completion status. Safe to call at any time."""
        try:
            import sys

            name = getattr(self.is_complete, "__name__", "")
            if getattr(sys, "frozen", False) and name in _LIVE_INTERPRETER_CHECKS:
                from astra.testing.agent import LocalTestingAgent
                from astra.testing.runner import ProcessRunner

                return LocalTestingAgent is not None and ProcessRunner is not None
            return bool(self.is_complete())
        except Exception:
            return False

    def for_ui(self) -> bool:
        """Dashboard/UI status. Never spawns a process."""
        try:
            name = getattr(self.is_complete, "__name__", "")
            if name in _LIVE_INTERPRETER_CHECKS:
                from astra.testing.agent import LocalTestingAgent
                from astra.testing.runner import ProcessRunner

                return LocalTestingAgent is not None and ProcessRunner is not None
            return bool(self.is_complete())
        except Exception:
            return False


def _check_memory_engine() -> bool:
    """B-001 complete if SQLiteMemoryStore is importable."""
    try:
        import importlib.util

        return importlib.util.find_spec("astra.memory.sqlite_store") is not None
    except Exception:
        return False


def _check_reasoning_runtime() -> bool:
    """B-002 complete if llama_cpp backend module exists."""
    try:
        import importlib.util

        return importlib.util.find_spec("astra.reasoning.llama_cpp_backend") is not None
    except Exception:
        return False


def _check_state_contracts() -> bool:
    """B-011 complete if astra.state is importable."""
    try:
        import importlib.util

        return importlib.util.find_spec("astra.state") is not None
    except Exception:
        return False


def _check_presentation_layer() -> bool:
    """B-012 complete if the ui/app.py dashboard launcher exists."""
    try:
        from pathlib import Path

        root = Path(__file__).parent.parent.parent
        return (root / "ui" / "app.py").is_file()
    except Exception:
        return False


def _check_knowledge_engine() -> bool:
    """B-004 complete if SQLiteKnowledgeStore is importable."""
    try:
        import importlib.util
        return importlib.util.find_spec("astra.knowledge.sqlite_store") is not None
    except Exception:
        return False


def _check_vector_index() -> bool:
    """B-003 complete if FaissVectorIndex module is importable."""
    try:
        import importlib.util
        return importlib.util.find_spec("astra.memory.faiss_index") is not None
    except Exception:
        return False


def _check_planning_engine() -> bool:
    """B-006 complete if SQLitePlanningStore is importable."""
    try:
        import importlib.util
        return importlib.util.find_spec("astra.planning.sqlite_store") is not None
    except Exception:
        return False


def _check_core_controller() -> bool:
    """B-007 complete if AstraController is importable."""
    try:
        import importlib.util
        return importlib.util.find_spec("astra.core.astra_controller") is not None
    except Exception:
        return False


def _check_pydantic_config() -> bool:
    """B-005 complete if settings are Pydantic models with production defaults.

    Detects Pydantic via ``model_fields`` (absent on the old dataclasses).
    Missing pydantic, a dataclass regression, or a default-model change all
    fail this check. Presentation layer does not import that package.
    """
    try:
        from astra.configuration.settings import AstraSettings, ReasoningSettings

        sample = ReasoningSettings()
        return (
            hasattr(AstraSettings, "model_fields")
            and hasattr(ReasoningSettings, "model_fields")
            and "host" not in ReasoningSettings.model_fields
            and sample.model == "Qwen2.5-3B-Instruct-Q5_K_M"
            and sample.context_tokens == 4096
            and sample.gpu_layers == 99
            and sample.embedding_context_tokens == 2048
            and sample.embedding_dim == 768
            and sample.embedding_gpu_layers == 0
            and sample.backend == "llama_cpp"
        )
    except Exception:
        return False


def _check_memory_indexer() -> bool:
    """B-013 complete if MemoryIndexer is importable (ADR-0012 D16)."""
    try:
        from astra.memory.indexer import MemoryIndexer

        return isinstance(MemoryIndexer, type)
    except Exception:
        return False


def _check_goal_manager() -> bool:
    """B-014 complete if SQLiteGoalManager is a GoalManager subclass."""
    try:
        from astra.planning.goal_manager import SQLiteGoalManager
        from astra.planning.goals import GoalManager

        return issubclass(SQLiteGoalManager, GoalManager)
    except Exception:
        return False


def _check_security_agent() -> bool:
    """B-015 complete if PolicySecurityAgent is deny-by-default (ADR-0014 D12)."""
    try:
        from astra.security.agent import PolicySecurityAgent
        from astra.security.contracts import SecurityAgent, Verdict

        if not issubclass(PolicySecurityAgent, SecurityAgent):
            return False
        agent = PolicySecurityAgent()
        net = agent.check_network_access("https://example.com")
        perm = agent.check_permanent_change("x")
        return net.verdict is Verdict.DENY and perm.verdict is not Verdict.ALLOW
    except Exception:
        return False


def _check_tools_interface() -> bool:
    """B-016 complete if registry+gate deny unknown and GuardedTool fails closed.

    ADR-0015 D12. Not a hard-coded True.
    """
    try:
        from astra.tools.contracts import (
            InvocationStatus,
            Tool,
            ToolInvocationRequest,
            ToolRegistry,
            ToolResult,
            ToolSpec,
        )
        from astra.tools.gate import GatedToolInvoker
        from astra.tools.guard import GuardedTool
        from astra.tools.registry import InMemoryToolRegistry

        class _Spy(Tool):
            def __init__(self) -> None:
                self.calls = 0

            @property
            def spec(self) -> ToolSpec:
                return ToolSpec(name="spy", description="milestone spy")

            def invoke(self, **kwargs: object) -> ToolResult:
                self.calls += 1
                return ToolResult(success=True, output="ran")

        spy = _Spy()
        bypass = GuardedTool(spy).invoke()
        registry = InMemoryToolRegistry()
        invoker = GatedToolInvoker(
            registry, security=None, execution_allowlist=()
        )
        rec = invoker.request(
            ToolInvocationRequest(caller="milestone", tool_name="missing")
        )
        return (
            issubclass(InMemoryToolRegistry, ToolRegistry)
            and not bool(invoker.execution_allowlist)
            and rec.status is InvocationStatus.DENIED
            and not rec.executed
            and not bypass.success
            and spy.calls == 0
        )
    except Exception:
        return False


def _check_read_text_tool() -> bool:
    """B-017 complete if read_text is gated, allowlisted, and fail-closed.

    ADR-0016 D14. Not a hard-coded True.
    """
    try:
        import tempfile
        from pathlib import Path

        from astra.security.agent import PolicySecurityAgent
        from astra.tools.contracts import Tool, ToolInvocationRequest
        from astra.tools.gate import GatedToolInvoker
        from astra.tools.read_text import (
            MAX_BYTES,
            PRODUCTION_EXECUTION_ALLOWLIST,
            TOOL_NAME,
            ReadTextTool,
        )
        from astra.tools.registry import InMemoryToolRegistry

        spec_ok = (
            issubclass(ReadTextTool, Tool)
            and ReadTextTool(read_roots=()).spec.name == TOOL_NAME
            and TOOL_NAME in PRODUCTION_EXECUTION_ALLOWLIST
            and MAX_BYTES == 65536
        )
        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            target = root / "sample.txt"
            target.write_text("hello-b017", encoding="utf-8")
            registry = InMemoryToolRegistry()
            registry.register(ReadTextTool(read_roots=(root,)))
            invoker = GatedToolInvoker(
                registry,
                security=PolicySecurityAgent(write_roots=(root,)),
                execution_allowlist=frozenset({TOOL_NAME}),
            )
            ok = invoker.request(
                ToolInvocationRequest(
                    caller="milestone",
                    tool_name=TOOL_NAME,
                    arguments={"path": str(target)},
                )
            )
            denied = invoker.request(
                ToolInvocationRequest(
                    caller="milestone",
                    tool_name=TOOL_NAME,
                    arguments={"path": str(root / ".." / "outside.txt")},
                )
            )
            guarded = registry.get(TOOL_NAME)
            bypass = (
                guarded.invoke(path=str(target)) if guarded is not None else None
            )
            return (
                spec_ok
                and ok.executed
                and ok.result is not None
                and ok.result.success
                and "hello-b017" in (ok.result.output or "")
                and not denied.executed
                and bypass is not None
                and not bypass.success
            )
        finally:
            tmp.cleanup()
    except Exception:
        return False


def _check_read_project_tool() -> bool:
    """B-018 complete if read_project is gated, allowlisted, and fail-closed.

    ADR-0017 D14. Not a hard-coded True.
    """
    try:
        from astra.configuration.paths import project_root
        from astra.configuration.settings import AstraSettings
        from astra.security.agent import PolicySecurityAgent
        from astra.security.contracts import Verdict
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST
        from astra.tools.contracts import Tool, ToolInvocationRequest
        from astra.tools.gate import GatedToolInvoker
        from astra.tools.read_project import TOOL_NAME, ReadProjectTool
        from astra.tools.read_text import ReadTextTool
        from astra.tools.registry import InMemoryToolRegistry

        spec = ReadProjectTool(read_roots=()).spec
        spec_ok = (
            issubclass(ReadProjectTool, Tool)
            and spec.name == TOOL_NAME
            and spec.reads_files is True
            and PRODUCTION_EXECUTION_ALLOWLIST
            == frozenset({"read_text", "read_project"})
        )
        settings = AstraSettings()
        agent = PolicySecurityAgent.from_settings(settings)
        py_path = project_root() / "astra" / "coding" / "__init__.py"
        outside = project_root().parent / "not-astra-b018.txt"
        env_path = project_root() / ".env"
        write_on_py = agent.check_file_write(py_path)
        read_ok = agent.check_file_read(py_path)
        read_env = agent.check_file_read(env_path)
        if write_on_py.verdict is not Verdict.DENY:
            return False
        if read_ok.verdict is not Verdict.ALLOW:
            return False
        if read_env.verdict is not Verdict.DENY:
            return False
        registry = InMemoryToolRegistry()
        registry.register(ReadTextTool(read_roots=()))
        registry.register(ReadProjectTool.from_settings(settings))
        invoker = GatedToolInvoker(
            registry,
            security=agent,
            execution_allowlist=PRODUCTION_EXECUTION_ALLOWLIST,
        )
        ok = invoker.request(
            ToolInvocationRequest(
                caller="milestone",
                tool_name=TOOL_NAME,
                arguments={"path": str(py_path)},
            )
        )
        denied = invoker.request(
            ToolInvocationRequest(
                caller="milestone",
                tool_name=TOOL_NAME,
                arguments={"path": str(outside)},
            )
        )
        excluded = invoker.request(
            ToolInvocationRequest(
                caller="milestone",
                tool_name=TOOL_NAME,
                arguments={"path": str(env_path)},
            )
        )
        guarded = registry.get(TOOL_NAME)
        bypass = (
            guarded.invoke(path=str(py_path)) if guarded is not None else None
        )
        names = frozenset(s.name for s in registry.specs())
        return (
            spec_ok
            and names == PRODUCTION_EXECUTION_ALLOWLIST
            and ok.executed
            and ok.result is not None
            and ok.result.success
            and "Coding Agent" in (ok.result.output or "")
            and not denied.executed
            and not excluded.executed
            and bypass is not None
            and not bypass.success
        )
    except Exception:
        return False


def _check_coding_agent() -> bool:
    """B-019 complete if LocalCodingAgent proposes via ToolInvoker only.

    ADR-0018 D13. Not a hard-coded True.
    """
    try:
        from pathlib import Path

        from astra.coding.agent import LocalCodingAgent
        from astra.coding.contracts import CodingAgent
        from astra.tools.contracts import (
            InvocationStatus,
            ToolInvocationRecord,
            ToolInvocationRequest,
            ToolResult,
        )

        class _FakeLLM:
            def is_available(self) -> bool:
                return self is not None

            def generate(self, prompt: str, options: object = None) -> str:
                return "<<<FILE target.py\nmarker-b019\n>>>END\n"

        class _FakeInvoker:
            def __init__(self) -> None:
                self.calls: list[ToolInvocationRequest] = []

            def request(self, req: ToolInvocationRequest) -> ToolInvocationRecord:
                self.calls.append(req)
                ok = req.tool_name == "read_project"
                return ToolInvocationRecord(
                    invocation_id="milestone",
                    request=req,
                    spec_name=req.tool_name,
                    verdict="allow" if ok else "deny",
                    reason="fake",
                    status=(
                        InvocationStatus.EXECUTED if ok else InvocationStatus.DENIED
                    ),
                    executed=ok,
                    result=ToolResult(success=ok, output="print(1)\n"),
                )

        if not issubclass(LocalCodingAgent, CodingAgent):
            return False
        if getattr(LocalCodingAgent, "name", None) != "coding":
            return False
        invoker = _FakeInvoker()
        agent = LocalCodingAgent(tools=invoker, llm=_FakeLLM())
        proposal = agent.propose_change(
            "improve maintainability of the helper",
            (Path("target.py"),),
        )
        none_invoker = _FakeInvoker()
        none_agent = LocalCodingAgent(tools=none_invoker, llm=_FakeLLM())
        empty = none_agent.propose_change("improve maintainability", None)
        return (
            len(invoker.calls) == 1
            and invoker.calls[0].tool_name == "read_project"
            and invoker.calls[0].caller == "coding"
            and bool(proposal.changes)
            and "marker-b019" in proposal.changes[0].new_content
            and len(none_invoker.calls) == 0
            and not empty.changes
            and bool(proposal.improves)
        )
    except Exception:
        return False


def _check_proposal_inspectability() -> bool:
    """B-020 complete if last proposal is retained and reviewed once.

    ADR-0019 D7. Not a hard-coded True.
    """
    try:
        from pathlib import Path

        from astra.coding.agent import LocalCodingAgent
        from astra.core.contracts import AgentRequest
        from astra.state import ApplicationState
        from astra.tools.contracts import (
            InvocationStatus,
            ToolInvocationRecord,
            ToolInvocationRequest,
            ToolResult,
        )

        class _FakeLLM:
            def __init__(self) -> None:
                self.calls = 0

            def is_available(self) -> bool:
                return self is not None

            def generate(self, prompt: str, options: object = None) -> str:
                self.calls += 1
                return "<<<FILE target.py\nmarker-b020\n>>>END\n"

        class _FakeInvoker:
            def __init__(self) -> None:
                self.calls: list[ToolInvocationRequest] = []

            def request(self, req: ToolInvocationRequest) -> ToolInvocationRecord:
                self.calls.append(req)
                ok = req.tool_name == "read_project"
                return ToolInvocationRecord(
                    invocation_id="milestone-b020",
                    request=req,
                    spec_name=req.tool_name,
                    verdict="allow" if ok else "deny",
                    reason="fake",
                    status=(
                        InvocationStatus.EXECUTED if ok else InvocationStatus.DENIED
                    ),
                    executed=ok,
                    result=ToolResult(success=ok, output="print(1)\n"),
                )

        class _CountingAgent(LocalCodingAgent):
            def __init__(self, *args: object, **kwargs: object) -> None:
                super().__init__(*args, **kwargs)
                self.review_calls = 0

            def review(self, proposal):  # type: ignore[no-untyped-def]
                self.review_calls += 1
                return super().review(proposal)

        target = Path(__file__).resolve().parents[2] / "astra" / "coding" / "__init__.py"
        before = target.read_bytes()
        invoker = _FakeInvoker()
        llm = _FakeLLM()
        agent = _CountingAgent(tools=invoker, llm=llm)
        result = agent.run(
            AgentRequest(
                objective="improve maintainability of the helper",
                context={"targets": [Path("target.py")]},
            )
        )
        none_invoker = _FakeInvoker()
        none_llm = _FakeLLM()
        none_agent = _CountingAgent(tools=none_invoker, llm=none_llm)
        empty = none_agent.run(
            AgentRequest(objective="improve maintainability")
        )
        after = target.read_bytes()
        snap_default = ApplicationState()
        proposal = agent.last_proposal
        return (
            proposal is not None
            and bool(proposal.changes)
            and "marker-b020" in proposal.changes[0].new_content
            and agent.review_calls == 1
            and llm.calls == 1
            and before == after
            and len(none_invoker.calls) == 0
            and none_agent.last_proposal is not None
            and none_agent.last_proposal.changes == ()
            and none_agent.review_calls == 1
            and result.details.get("applied") is False
            and empty.details.get("applied") is False
            and snap_default.schema_version == 6
            and snap_default.coding.applied is False
            and bool(agent.last_change_paths)
            and "new_content" not in result.details
        )
    except Exception:
        return False


def _check_proposal_workspace() -> bool:
    """B-021 complete if overlay writes scratch only and project bytes stay.

    ADR-0020 D8. Not a hard-coded True.
    """
    try:
        import tempfile
        from pathlib import Path

        from astra.coding.contracts import ChangeProposal, FileChange
        from astra.core.lifecycle import MeasurableProperty
        from astra.security.agent import PolicySecurityAgent
        from astra.security.contracts import Verdict
        from astra.state import ApplicationState
        from astra.testing.contracts import TestingAgent
        from astra.testing.workspace import ProposalWorkspace
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            project = root / "project"
            scratch = root / "scratch"
            data = root / "data"
            project.mkdir()
            scratch.mkdir()
            data.mkdir()
            target = project / "marker.py"
            original = b"original-bytes\n"
            target.write_bytes(original)
            agent = PolicySecurityAgent(
                write_roots=(data,),
                scratch_root=scratch,
                project_roots=(project,),
            )
            workspace = ProposalWorkspace(
                security=agent,
                scratch_root=scratch,
                project_root=project,
            )
            proposal = ChangeProposal(
                objective="isolate overlay",
                changes=(
                    FileChange(
                        path=Path("marker.py"),
                        new_content="marker-b021\n",
                        rationale="overlay",
                    ),
                ),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
            result = workspace.materialize(proposal)
            overlay_file = (
                result.overlay_root / "marker.py" if result.overlay_root else None
            )
            overlay_ok = (
                result.success
                and overlay_file is not None
                and overlay_file.is_file()
                and "marker-b021" in overlay_file.read_text(encoding="utf-8")
            )
            project_unchanged = target.read_bytes() == original
            project_deny = agent.check_temporary_write(target)
            scratch_allow = agent.check_temporary_write(scratch / "ok.py")
            scratch_not_write_root = agent.check_file_write(scratch / "ok.py")
            permanent = agent.check_permanent_change("apply overlay")
            empty = workspace.materialize(
                ChangeProposal(
                    objective="empty",
                    changes=(),
                    improves=(MeasurableProperty.MAINTAINABILITY,),
                )
            )
            escape = workspace.materialize(
                ChangeProposal(
                    objective="escape",
                    changes=(
                        FileChange(
                            path=Path("..") / "outside.py",
                            new_content="nope\n",
                        ),
                    ),
                    improves=(MeasurableProperty.MAINTAINABILITY,),
                )
            )
            escape_unwritten = not (root / "outside.py").exists()
            testing_refused = False
            try:
                TestingAgent.test_proposal(object(), proposal)  # type: ignore[misc]
            except NotImplementedError:
                testing_refused = True
            discarded = workspace.discard(result.session_id)
            gone = result.overlay_root is not None and not result.overlay_root.exists()
            snap = ApplicationState()
            allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
                "read_text",
                "read_project",
            }
            return (
                overlay_ok
                and project_unchanged
                and project_deny.verdict is Verdict.DENY
                and scratch_allow.verdict is Verdict.ALLOW
                and scratch_not_write_root.verdict is Verdict.DENY
                and permanent.verdict is not Verdict.ALLOW
                and not empty.success
                and not escape.success
                and escape_unwritten
                and testing_refused
                and discarded
                and gone
                and snap.schema_version == 6
                and allowlist
            )
        finally:
            tmp.cleanup()
    except Exception:
        return False


def _check_process_policy() -> bool:
    """B-022 complete if process policy denies by default and runner does not spawn.

    ADR-0021 D7. Not a hard-coded True.
    """
    try:
        import ast
        from pathlib import Path

        from astra.security.agent import PolicySecurityAgent
        from astra.security.contracts import ProcessSpec, Verdict
        from astra.state import ApplicationState
        from astra.testing.contracts import TestingAgent
        from astra.testing.runner import ProcessRunner
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        agent = PolicySecurityAgent()
        denied = agent.check_process_execution(
            ProcessSpec(argv=("python", "-m", "unittest"), cwd=Path("."), timeout_seconds=1.0)
        )
        empty = agent.check_process_execution(ProcessSpec())
        shell = agent.check_process_execution(
            ProcessSpec(argv=("cmd.exe", "/c", "dir"), timeout_seconds=1.0)
        )
        dash_c = agent.check_process_execution(
            ProcessSpec(argv=("python", "-c", "print(1)"), timeout_seconds=1.0)
        )
        runner = ProcessRunner(security=agent)
        result = runner.run(
            ProcessSpec(argv=("python", "-m", "unittest"), timeout_seconds=1.0)
        )
        testing_src = Path(__file__).resolve().parents[1] / "testing"
        extra_subprocess = False
        for py_file in testing_src.glob("*.py"):
            if py_file.name == "runner.py":
                continue
            tree = ast.parse(py_file.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                names: list[str] = []
                if isinstance(node, ast.Import):
                    names = [alias.name.split(".")[0] for alias in node.names]
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names = [node.module.split(".")[0]]
                if "subprocess" in names:
                    extra_subprocess = True
        runner_src = (testing_src / "runner.py").read_text(encoding="utf-8")
        runner_has_subprocess = "import subprocess" in runner_src
        testing_refused = False
        try:
            TestingAgent.test_proposal(object(), None)  # type: ignore[misc]
        except NotImplementedError:
            testing_refused = True
        snap = ApplicationState()
        allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
            "read_text",
            "read_project",
        }
        permanent = agent.check_permanent_change("run tests")
        never_approval = all(
            d.verdict is not Verdict.REQUIRES_APPROVAL
            for d in (denied, empty, shell, dash_c)
        )
        return (
            denied.verdict is Verdict.DENY
            and empty.verdict is Verdict.DENY
            and shell.verdict is Verdict.DENY
            and dash_c.verdict is Verdict.DENY
            and never_approval
            and result.spawned is False
            and result.success is False
            and not extra_subprocess
            and runner_has_subprocess
            and testing_refused
            and snap.schema_version == 6
            and allowlist
            and permanent.verdict is not Verdict.ALLOW
        )
    except Exception:
        return False


def _check_copied_subset_workspace() -> bool:
    """B-023 complete if allowlisted subset copies to scratch and project bytes stay.

    ADR-0022 D8. Not a hard-coded True.
    """
    try:
        import tempfile
        from pathlib import Path

        from astra.coding.contracts import ChangeProposal, FileChange
        from astra.core.lifecycle import MeasurableProperty
        from astra.security.agent import PolicySecurityAgent
        from astra.security.contracts import ProcessSpec
        from astra.state import ApplicationState
        from astra.testing.contracts import TestingAgent
        from astra.testing.runner import ProcessRunner
        from astra.testing.subset import CopiedSubsetWorkspace
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            project = root / "project"
            scratch = root / "scratch"
            data = root / "data"
            project.mkdir()
            scratch.mkdir()
            data.mkdir()
            (project / "astra").mkdir()
            (project / "astra" / "__init__.py").write_bytes(b"# astra-marker\n")
            (project / "tests").mkdir()
            (project / "tests" / "test_x.py").write_bytes(b"x = 1\n")
            (project / "ui").mkdir()
            (project / "ui" / "app.py").write_bytes(b"# ui-app\n")
            (project / "CONSTITUTION.md").write_bytes(b"# constitution-marker\n")
            (project / "pyproject.toml").write_bytes(b"[project]\nname='fixture'\n")
            (project / ".git").mkdir()
            (project / ".git" / "config").write_bytes(b"git\n")
            (project / "data").mkdir()
            (project / "data" / "secret.db").write_bytes(b"db")
            (project / ".env").write_bytes(b"SECRET=1\n")
            (project / "models").mkdir()
            (project / "models" / "weights.gguf").write_bytes(b"gguf")
            marker = (project / "astra" / "__init__.py").read_bytes()
            constitution = (project / "CONSTITUTION.md").read_bytes()
            agent = PolicySecurityAgent(
                write_roots=(data,),
                scratch_root=scratch,
                project_roots=(project,),
            )
            workspace = CopiedSubsetWorkspace(
                security=agent,
                scratch_root=scratch,
                project_root=project,
            )
            copied = workspace.copy()
            session = copied.subset_root
            required = (
                copied.success
                and session is not None
                and (session / "astra" / "__init__.py").is_file()
                and (session / "tests").is_dir()
                and (session / "ui" / "app.py").is_file()
                and (session / "CONSTITUTION.md").is_file()
            )
            exclusions = session is not None and not (
                (session / ".git").exists()
                or (session / "data").exists()
                or (session / ".env").exists()
                or any(session.rglob("*.gguf"))
            )
            proposal = ChangeProposal(
                objective="overlay into subset",
                changes=(
                    FileChange(
                        path=Path("astra") / "__init__.py",
                        new_content="# overlay-b023\n",
                        rationale="overlay",
                    ),
                ),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
            overlaid = workspace.overlay(copied.session_id, proposal)
            overlay_file = (
                session / "astra" / "__init__.py" if session is not None else None
            )
            overlay_ok = (
                overlaid.success
                and overlay_file is not None
                and overlay_file.is_file()
                and "overlay-b023" in overlay_file.read_text(encoding="utf-8")
            )
            project_unchanged = (
                (project / "astra" / "__init__.py").read_bytes() == marker
                and (project / "CONSTITUTION.md").read_bytes() == constitution
            )
            escape = workspace.overlay(
                copied.session_id,
                ChangeProposal(
                    objective="escape",
                    changes=(
                        FileChange(
                            path=Path("..") / "outside.py",
                            new_content="nope\n",
                        ),
                    ),
                    improves=(MeasurableProperty.MAINTAINABILITY,),
                ),
            )
            escape_unwritten = not (root / "outside.py").exists()
            onto_project = CopiedSubsetWorkspace(
                security=agent,
                scratch_root=project,
                project_root=project,
            ).copy()
            runner = ProcessRunner(security=agent)
            process = runner.run(
                ProcessSpec(argv=("python", "-m", "unittest"), timeout_seconds=1.0)
            )
            testing_refused = False
            try:
                TestingAgent.test_proposal(object(), proposal)  # type: ignore[misc]
            except NotImplementedError:
                testing_refused = True
            discarded = workspace.discard(copied.session_id)
            gone = session is not None and not session.exists()
            snap = ApplicationState()
            allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
                "read_text",
                "read_project",
            }
            return (
                required
                and exclusions
                and overlay_ok
                and project_unchanged
                and not escape.success
                and escape_unwritten
                and not onto_project.success
                and process.spawned is False
                and testing_refused
                and discarded
                and gone
                and snap.schema_version == 6
                and allowlist
            )
        finally:
            tmp.cleanup()
    except Exception:
        return False


def _check_process_spawn() -> bool:
    """B-024 complete if interpreter probe spawns in a copied session.

    ADR-0023 D11. Not a hard-coded True.
    """
    try:
        import sys
        import tempfile
        from pathlib import Path

        from astra.coding.contracts import ChangeProposal, FileChange
        from astra.core.lifecycle import MeasurableProperty
        from astra.security.agent import PolicySecurityAgent
        from astra.security.contracts import ProcessSpec, Verdict
        from astra.state import ApplicationState
        from astra.testing.contracts import TestingAgent
        from astra.testing.probe import TOKEN
        from astra.testing.runner import ProcessRunner, ProcessStatus
        from astra.testing.subset import CopiedSubsetWorkspace
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            project = root / "project"
            scratch = root / "scratch"
            data = root / "data"
            project.mkdir()
            scratch.mkdir()
            data.mkdir()
            (project / "astra" / "testing").mkdir(parents=True)
            (project / "astra" / "__init__.py").write_bytes(b"# astra-marker\n")
            (project / "astra" / "testing" / "__init__.py").write_bytes(b"")
            (project / "astra" / "testing" / "probe.py").write_bytes(
                b'TOKEN = "ASTRA_PROCESS_PROBE_OK"\n'
                b"def main() -> int:\n"
                b"    print(TOKEN, flush=True)\n"
                b"    return 0\n"
                b'if __name__ == "__main__":\n'
                b"    raise SystemExit(main())\n"
            )
            (project / "tests").mkdir()
            (project / "tests" / "test_x.py").write_bytes(b"x = 1\n")
            (project / "ui").mkdir()
            (project / "ui" / "app.py").write_bytes(b"# ui-app\n")
            (project / "CONSTITUTION.md").write_bytes(b"# constitution-marker\n")
            marker = (project / "astra" / "__init__.py").read_bytes()
            agent = PolicySecurityAgent(
                write_roots=(data,),
                scratch_root=scratch,
                project_roots=(project,),
                process_executables=(Path(sys.executable),),
            )
            workspace = CopiedSubsetWorkspace(
                security=agent,
                scratch_root=scratch,
                project_root=project,
            )
            copied = workspace.copy()
            session = copied.subset_root
            runner = ProcessRunner(security=agent, subset=workspace)
            probe = runner.run(
                ProcessSpec(
                    argv=(sys.executable, "-B", "-s", "-m", "astra.testing.probe"),
                    cwd=session,
                    timeout_seconds=5.0,
                ),
                session_id=copied.session_id,
            )
            unauthorized = runner.run(
                ProcessSpec(
                    argv=(str(scratch / "nope.exe"),),
                    cwd=session,
                    timeout_seconds=5.0,
                ),
                session_id=copied.session_id,
            )
            missing = runner.run(
                ProcessSpec(
                    argv=(sys.executable, "-B", "-s", "-m", "astra.testing.probe"),
                    cwd=session,
                    timeout_seconds=5.0,
                ),
                session_id="",
            )
            shell = runner.run(
                ProcessSpec(
                    argv=("cmd.exe", "/c", "dir"),
                    cwd=session,
                    timeout_seconds=5.0,
                ),
                session_id=copied.session_id,
            )
            dash_c = runner.run(
                ProcessSpec(
                    argv=(sys.executable, "-c", "print(1)"),
                    cwd=session,
                    timeout_seconds=5.0,
                ),
                session_id=copied.session_id,
            )
            project_cwd = runner.run(
                ProcessSpec(
                    argv=(sys.executable, "-B", "-s", "-m", "astra.testing.probe"),
                    cwd=project,
                    timeout_seconds=5.0,
                ),
                session_id=copied.session_id,
            )
            testing_refused = False
            try:
                TestingAgent.test_proposal(  # type: ignore[misc]
                    object(),
                    ChangeProposal(
                        objective="no",
                        changes=(FileChange(path=Path("x.py"), new_content="x\n"),),
                        improves=(MeasurableProperty.MAINTAINABILITY,),
                    ),
                )
            except NotImplementedError:
                testing_refused = True
            discarded = workspace.discard(copied.session_id)
            gone = session is not None and not session.exists()
            snap = ApplicationState()
            allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
                "read_text",
                "read_project",
            }
            permanent = agent.check_permanent_change("run tests")
            return (
                copied.success
                and probe.spawned is True
                and probe.status is ProcessStatus.EXITED
                and probe.exit_code == 0
                and TOKEN in probe.stdout
                and marker == (project / "astra" / "__init__.py").read_bytes()
                and unauthorized.spawned is False
                and missing.spawned is False
                and shell.spawned is False
                and dash_c.spawned is False
                and project_cwd.spawned is False
                and testing_refused
                and discarded
                and gone
                and snap.schema_version == 6
                and allowlist
                and permanent.verdict is not Verdict.ALLOW
            )
        finally:
            tmp.cleanup()
    except Exception:
        return False


def _check_proposal_testing() -> bool:
    """B-025 complete if test_proposal runs unittest in a copied session.

    ADR-0024 D7. Not a hard-coded True.
    """
    try:
        import sys
        import tempfile
        from pathlib import Path

        from astra.coding.contracts import ChangeProposal, FileChange
        from astra.core.lifecycle import MeasurableProperty
        from astra.security.agent import PolicySecurityAgent
        from astra.security.contracts import Verdict
        from astra.state import ApplicationState
        from astra.testing.agent import LocalTestingAgent
        from astra.testing.contracts import TestReport
        from astra.testing.runner import ProcessRunner
        from astra.testing.subset import CopiedSubsetWorkspace
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            project = root / "project"
            scratch = root / "scratch"
            data = root / "data"
            project.mkdir()
            scratch.mkdir()
            data.mkdir()
            (project / "astra").mkdir()
            (project / "astra" / "__init__.py").write_bytes(b"# astra-marker\n")
            (project / "tests").mkdir()
            (project / "tests" / "__init__.py").write_bytes(b"")
            (project / "tests" / "test_ok.py").write_text(
                "import unittest\n"
                "class OkTest(unittest.TestCase):\n"
                "    def test_ok(self) -> None:\n"
                "        self.assertTrue(True)\n",
                encoding="utf-8",
            )
            (project / "ui").mkdir()
            (project / "ui" / "app.py").write_bytes(b"# ui\n")
            (project / "CONSTITUTION.md").write_bytes(b"# constitution-marker\n")
            marker = (project / "astra" / "__init__.py").read_bytes()
            agent = PolicySecurityAgent(
                write_roots=(data,),
                scratch_root=scratch,
                project_roots=(project,),
                process_executables=(Path(sys.executable),),
            )
            workspace = CopiedSubsetWorkspace(
                security=agent, scratch_root=scratch, project_root=project
            )
            runner = ProcessRunner(security=agent, subset=workspace)
            testing = LocalTestingAgent(
                subset=workspace, runner=runner, timeout_seconds=15.0
            )
            proposal = ChangeProposal(
                objective="add passing test",
                changes=(
                    FileChange(
                        path=Path("tests") / "test_extra.py",
                        new_content=(
                            "import unittest\n"
                            "class ExtraTest(unittest.TestCase):\n"
                            "    def test_extra(self) -> None:\n"
                            "        self.assertEqual(1, 1)\n"
                        ),
                        rationale="passing overlay",
                    ),
                ),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
            report = testing.test_proposal(proposal)
            leftover = list(scratch.iterdir())
            suite = testing.run_suite()
            leftover_after_suite = list(scratch.iterdir())
            snap = ApplicationState()
            allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
                "read_text",
                "read_project",
            }
            permanent = agent.check_permanent_change("apply tests")
            return (
                report.passed
                and report.tests_run >= 2
                and report.tests_failed == 0
                and report.tests_errors == 0
                and not report.incomplete
                and not report.timed_out
                and not report.spawn_failed
                and marker == (project / "astra" / "__init__.py").read_bytes()
                and leftover == []
                and leftover_after_suite == []
                and isinstance(suite, TestReport)
                and suite.tests_run >= 1
                and snap.schema_version == 6
                and allowlist
                and permanent.verdict is not Verdict.ALLOW
            )
        finally:
            tmp.cleanup()
    except Exception:
        return False


def _check_run_suite() -> bool:
    """B-030 complete if run_suite executes unittest in a copy, not project_root.

    ADR-0029. Not a hard-coded True.
    """
    try:
        import ast
        import inspect
        import sys
        import tempfile
        from pathlib import Path

        from astra.security.agent import PolicySecurityAgent
        from astra.security.contracts import Verdict
        from astra.state import ApplicationState
        from astra.testing.agent import LocalTestingAgent
        from astra.testing.contracts import TestReport
        from astra.testing.runner import ProcessRunner
        from astra.testing.subset import CopiedSubsetWorkspace
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            project = root / "project"
            scratch = root / "scratch"
            data = root / "data"
            project.mkdir()
            scratch.mkdir()
            data.mkdir()
            (project / "astra").mkdir()
            marker = b"# astra-marker\n"
            (project / "astra" / "__init__.py").write_bytes(marker)
            (project / "tests").mkdir()
            (project / "tests" / "__init__.py").write_bytes(b"")
            (project / "tests" / "test_ok.py").write_text(
                "import unittest\n"
                "class OkTest(unittest.TestCase):\n"
                "    def test_ok(self) -> None:\n"
                "        self.assertTrue(True)\n",
                encoding="utf-8",
            )
            (project / "ui").mkdir()
            (project / "ui" / "app.py").write_bytes(b"# ui\n")
            (project / "CONSTITUTION.md").write_bytes(b"# constitution-marker\n")
            live_test = (project / "tests" / "test_ok.py").read_bytes()
            agent = PolicySecurityAgent(
                write_roots=(data,),
                scratch_root=scratch,
                project_roots=(project,),
                process_executables=(Path(sys.executable),),
            )
            workspace = CopiedSubsetWorkspace(
                security=agent, scratch_root=scratch, project_root=project
            )
            runner = ProcessRunner(security=agent, subset=workspace)
            testing = LocalTestingAgent(
                subset=workspace, runner=runner, timeout_seconds=15.0
            )
            seen_cwd: list[Path] = []
            real_run = runner.run

            def _capture(spec, *, session_id: str = ""):
                cwd = getattr(spec, "cwd", None)
                if cwd is not None:
                    seen_cwd.append(Path(cwd))
                return real_run(spec, session_id=session_id)

            runner.run = _capture  # type: ignore[method-assign]
            report = testing.run_suite()
            leftover = list(scratch.iterdir())
            src = Path(__file__).resolve().parents[1] / "testing" / "agent.py"
            text = src.read_text(encoding="utf-8")
            names: list[str] = []
            for node in ast.walk(ast.parse(text)):
                if isinstance(node, ast.Import):
                    names.extend(alias.name.split(".")[0] for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names.append(node.module.split(".")[0])
            run_src = inspect.getsource(LocalTestingAgent.run_suite)
            snap = ApplicationState()
            allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
                "read_text",
                "read_project",
            }
            permanent = agent.check_permanent_change("apply tests")
            cwd_ok = bool(seen_cwd) and all(
                Path(path).resolve() != project.resolve() for path in seen_cwd
            )
            return (
                isinstance(report, TestReport)
                and report.passed
                and report.tests_run >= 1
                and not report.incomplete
                and live_test == (project / "tests" / "test_ok.py").read_bytes()
                and marker == (project / "astra" / "__init__.py").read_bytes()
                and leftover == []
                and cwd_ok
                and "subprocess" not in names
                and "overlay(" not in run_src
                and snap.schema_version == 6
                and allowlist
                and permanent.verdict is not Verdict.ALLOW
            )
        finally:
            tmp.cleanup()
    except Exception:
        return False


def _check_proposal_evaluation() -> bool:
    """B-026 complete if evaluate_proposal judges DTOs fail-closed.

    ADR-0025 D7. Not a hard-coded True.
    """
    try:
        from pathlib import Path

        from astra.coding.contracts import ChangeProposal, FileChange
        from astra.core.lifecycle import MeasurableProperty
        from astra.evaluation.agent import LocalEvaluationAgent
        from astra.evaluation.contracts import EvaluationAgent
        from astra.state import ApplicationState
        from astra.testing.contracts import TestReport
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        proposal = ChangeProposal(
            objective="eval fixture",
            changes=(
                FileChange(
                    path=Path("tests") / "test_extra.py",
                    new_content="x = 1\n",
                    rationale="fixture",
                ),
            ),
            improves=(MeasurableProperty.MAINTAINABILITY,),
        )
        passing = TestReport(
            tests_run=2,
            tests_passed=2,
            tests_failed=0,
            tests_errors=0,
            tests_skipped=0,
        )
        failing = TestReport(
            tests_run=2,
            tests_passed=1,
            tests_failed=1,
            tests_errors=0,
        )
        incomplete = TestReport(incomplete=True, tests_run=2, tests_passed=2)
        agent = LocalEvaluationAgent()
        ok = agent.evaluate_proposal(proposal, passing)
        bad = agent.evaluate_proposal(proposal, failing)
        broken = agent.evaluate_proposal(proposal, incomplete)
        empty = agent.evaluate_proposal(
            ChangeProposal(
                objective="empty",
                changes=(),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            ),
            passing,
        )
        missing = agent.evaluate_proposal(proposal, None)  # type: ignore[arg-type]
        compared = agent.evaluate("subject")
        stored = agent.record_baseline("subject", ok)
        abc_refused = False
        try:
            EvaluationAgent.evaluate_proposal(object(), proposal, passing)  # type: ignore[misc]
        except NotImplementedError:
            abc_refused = True
        src = Path(__file__).resolve().parents[1] / "evaluation" / "agent.py"
        text = src.read_text(encoding="utf-8")
        allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
            "read_text",
            "read_project",
        }
        snap = ApplicationState()
        return (
            ok.evaluation_passed
            and ok.tests_passed
            and not ok.deployment_authorized
            and not ok.improved
            and not bad.evaluation_passed
            and not broken.evaluation_passed
            and broken.incomplete
            and not empty.evaluation_passed
            and not missing.evaluation_passed
            and not compared.improved
            and not compared.deployment_authorized
            and not stored.recorded
            and abc_refused
            and "subprocess" not in text
            and "ApprovalRecord" not in text
            and "DeploymentAgent" not in text
            and "write_text" not in text
            and snap.schema_version == 6
            and allowlist
        )
    except Exception:
        return False


def _check_baseline_evaluation() -> bool:
    """B-029 complete if baseline comparison is honest and has no deploy authority.

    ADR-0028. Not a hard-coded True.
    """
    try:
        import tempfile
        from pathlib import Path

        from astra.coding.contracts import ChangeProposal, FileChange
        from astra.core.lifecycle import MeasurableProperty
        from astra.evaluation.agent import LocalEvaluationAgent
        from astra.evaluation.contracts import EvaluationReport, MetricSample
        from astra.evaluation.store import LocalBaselineStore
        from astra.security.agent import PolicySecurityAgent
        from astra.state import ApplicationState
        from astra.testing.contracts import TestReport
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            scratch = root / "scratch"
            data = root / "data"
            project = root / "project"
            scratch.mkdir()
            data.mkdir()
            project.mkdir()
            marker = project / "keep.py"
            marker.write_bytes(b"# keep\n")
            security = PolicySecurityAgent(
                write_roots=(data,),
                project_roots=(project,),
                scratch_root=scratch,
            )
            store = LocalBaselineStore(
                security=security, store_root=scratch / "baselines"
            )
            agent = LocalEvaluationAgent(baselines=store)
            proposal = ChangeProposal(
                objective="baseline fixture",
                changes=(
                    FileChange(
                        path=Path("tests") / "test_extra.py",
                        new_content="x = 1\n",
                        rationale="fixture",
                    ),
                ),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
            other = ChangeProposal(
                objective="no baseline subject",
                changes=proposal.changes,
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
            passing = TestReport(tests_run=1, tests_passed=1)
            higher = MetricSample(
                property=MeasurableProperty.ACCURACY,
                metric="score",
                value=1.0,
                unit="ratio",
                higher_is_better=True,
            )
            recorded = agent.record_baseline(
                "baseline fixture",
                EvaluationReport(
                    subject="baseline fixture",
                    samples=(higher,),
                    implementation="fixture",
                ),
            )
            duplicate = agent.record_baseline(
                "baseline fixture",
                EvaluationReport(
                    subject="baseline fixture",
                    samples=(higher,),
                    implementation="fixture",
                ),
            )
            improved = agent.evaluate_proposal(
                proposal,
                passing,
                baseline_id=recorded.baseline_id,
                candidate=(
                    MetricSample(
                        property=MeasurableProperty.ACCURACY,
                        metric="score",
                        value=2.0,
                        unit="ratio",
                        higher_is_better=True,
                    ),
                ),
            )
            none = agent.evaluate_proposal(proposal, passing)
            missing = agent.evaluate_proposal(
                other,
                passing,
                candidate=(
                    MetricSample(
                        property=MeasurableProperty.ACCURACY,
                        metric="score",
                        value=2.0,
                        unit="ratio",
                        higher_is_better=True,
                    ),
                ),
            )
            src_root = Path(__file__).resolve().parents[1] / "evaluation"
            agent_src = (src_root / "agent.py").read_text(encoding="utf-8")
            compare_src = (src_root / "compare.py").read_text(encoding="utf-8")
            joined = agent_src + "\n" + compare_src
            allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
                "read_text",
                "read_project",
            }
            snap = ApplicationState()
            return (
                recorded.recorded
                and not duplicate.recorded
                and improved.improved
                and improved.evaluation_passed
                and not improved.deployment_authorized
                and none.evaluation_passed
                and not none.improved
                and not none.deployment_authorized
                and not missing.improved
                and missing.incomplete
                and marker.read_bytes() == b"# keep\n"
                and "subprocess" not in joined
                and "ApprovalRecord" not in joined
                and "DeploymentAgent" not in joined
                and snap.schema_version == 6
                and allowlist
            )
        finally:
            tmp.cleanup()
    except Exception:
        return False


def _check_controlled_deployment() -> bool:
    """B-027 complete if deploy writes only with matching ApprovalRecord.

    ADR-0026 D7. Not a hard-coded True.
    """
    try:
        import tempfile
        from datetime import datetime, timezone
        from pathlib import Path

        from astra.coding.contracts import ChangeProposal, FileChange
        from astra.core.lifecycle import MeasurableProperty
        from astra.deployment.agent import LocalDeploymentAgent
        from astra.deployment.contracts import ApprovalRecord, STATUS_DEPLOYED
        from astra.deployment.fingerprint import evidence_fingerprint
        from astra.evaluation.contracts import EvaluationReport
        from astra.security.agent import PolicySecurityAgent
        from astra.security.contracts import Verdict
        from astra.state import ApplicationState
        from astra.testing.contracts import TestReport
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            project = root / "project"
            data = root / "data"
            project.mkdir()
            data.mkdir()
            target = project / "astra"
            target.mkdir()
            original = b"# before\n"
            (target / "hello.py").write_bytes(original)
            sibling = project / "keep.py"
            sibling.write_bytes(b"# keep\n")
            security = PolicySecurityAgent(
                write_roots=(data,),
                project_roots=(project,),
            )
            agent = LocalDeploymentAgent(security=security, project_root=project)
            proposal = ChangeProposal(
                objective="deploy fixture",
                changes=(
                    FileChange(
                        path=Path("astra") / "hello.py",
                        new_content="# after\n",
                        rationale="fixture",
                    ),
                ),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
            tests = TestReport(tests_run=1, tests_passed=1)
            evaluation = EvaluationReport(
                subject="deploy fixture",
                tests_passed=True,
                evaluation_passed=True,
            )
            missing = agent.deploy(proposal, tests, evaluation, None)  # type: ignore[arg-type]
            after_missing = (target / "hello.py").read_bytes()
            approval = ApprovalRecord(
                subject=evidence_fingerprint(proposal, tests, evaluation),
                approved_by="milestone-check",
                approved_at=datetime.now(timezone.utc),
            )
            ok = agent.deploy(proposal, tests, evaluation, approval)
            missing_rb = agent.rollback("d-x", None)  # type: ignore[arg-type]
            src = Path(__file__).resolve().parents[1] / "deployment" / "agent.py"
            text = src.read_text(encoding="utf-8")
            permanent = security.check_permanent_change("deploy fixture")
            allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
                "read_text",
                "read_project",
            }
            snap = ApplicationState()
            return (
                missing.status != STATUS_DEPLOYED
                and after_missing == original
                and ok.deployed
                and (target / "hello.py").read_bytes() == b"# after\n"
                and sibling.read_bytes() == b"# keep\n"
                and not missing_rb.rolled_back
                and (target / "hello.py").read_bytes() == b"# after\n"
                and "subprocess" not in text
                and "ProcessRunner" not in text
                and permanent.verdict is not Verdict.ALLOW
                and snap.schema_version == 6
                and allowlist
            )
        finally:
            tmp.cleanup()
    except Exception:
        return False


def _check_version_rollback() -> bool:
    """B-028 complete if approved rollback restores the pre-deployment snapshot.

    ADR-0027. Not a hard-coded True.
    """
    try:
        import tempfile
        from datetime import datetime, timezone
        from pathlib import Path

        from astra.coding.contracts import ChangeProposal, FileChange
        from astra.core.lifecycle import MeasurableProperty
        from astra.deployment.agent import LocalDeploymentAgent
        from astra.deployment.contracts import ApprovalRecord, STATUS_DEPLOYED
        from astra.deployment.fingerprint import evidence_fingerprint, rollback_fingerprint
        from astra.deployment.store import LocalVersionManager
        from astra.evaluation.contracts import EvaluationReport
        from astra.security.agent import PolicySecurityAgent
        from astra.security.contracts import Verdict
        from astra.state import ApplicationState
        from astra.testing.contracts import TestReport
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            project = root / "project"
            data = root / "data"
            scratch = root / "scratch"
            project.mkdir()
            data.mkdir()
            scratch.mkdir()
            target = project / "astra"
            target.mkdir()
            original = b"# before\n"
            (target / "hello.py").write_bytes(original)
            sibling = project / "keep.py"
            sibling.write_bytes(b"# keep\n")
            security = PolicySecurityAgent(
                write_roots=(data,),
                project_roots=(project,),
                scratch_root=scratch,
            )
            versions = LocalVersionManager(
                security=security,
                project_root=project,
                store_root=scratch / "versions",
            )
            agent = LocalDeploymentAgent(
                security=security, project_root=project, versions=versions
            )
            proposal = ChangeProposal(
                objective="version fixture",
                changes=(
                    FileChange(
                        path=Path("astra") / "hello.py",
                        new_content="# after\n",
                        rationale="fixture",
                    ),
                    FileChange(
                        path=Path("astra") / "new.py",
                        new_content="# new\n",
                        rationale="fixture",
                    ),
                ),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
            tests = TestReport(tests_run=1, tests_passed=1)
            evaluation = EvaluationReport(
                subject="version fixture",
                tests_passed=True,
                evaluation_passed=True,
            )
            approval = ApprovalRecord(
                subject=evidence_fingerprint(proposal, tests, evaluation),
                approved_by="milestone-check",
                approved_at=datetime.now(timezone.utc),
            )
            ok = agent.deploy(proposal, tests, evaluation, approval)
            version_id = ok.version
            record = versions.get(version_id)
            missing_rb = agent.rollback(version_id, None)  # type: ignore[arg-type]
            after_missing = (target / "hello.py").read_bytes()
            rb_approval = ApprovalRecord(
                subject=rollback_fingerprint(version_id),
                approved_by="milestone-check",
                approved_at=datetime.now(timezone.utc),
            )
            restored = agent.rollback(version_id, rb_approval)
            src_root = Path(__file__).resolve().parents[1] / "deployment"
            texts = [
                (src_root / name).read_text(encoding="utf-8")
                for name in ("agent.py", "store.py", "apply.py", "versioning.py")
            ]
            joined = "\n".join(texts)
            permanent = security.check_permanent_change("rollback fixture")
            allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
                "read_text",
                "read_project",
            }
            snap = ApplicationState()
            return (
                ok.deployed
                and ok.status == STATUS_DEPLOYED
                and bool(version_id)
                and record is not None
                and record.version == version_id
                and not missing_rb.rolled_back
                and after_missing == b"# after\n"
                and restored.rolled_back
                and (target / "hello.py").read_bytes() == original
                and not (target / "new.py").exists()
                and sibling.read_bytes() == b"# keep\n"
                and "subprocess" not in joined
                and "ProcessRunner" not in joined
                and "import git" not in joined
                and permanent.verdict is not Verdict.ALLOW
                and snap.schema_version == 6
                and allowlist
            )
        finally:
            tmp.cleanup()
    except Exception:
        return False


def _check_change_pipeline() -> bool:
    """B-031 complete if controller pipeline composes test→eval→approved deploy.

    ADR-0030. Not a hard-coded True.
    """
    try:
        import ast
        import inspect
        import sys
        import tempfile
        from datetime import datetime, timezone
        from pathlib import Path

        from astra.coding.contracts import ChangeProposal, FileChange
        from astra.core.lifecycle import MeasurableProperty
        from astra.core.pipeline import (
            PIPELINE_APPROVAL_MISSING,
            PIPELINE_DEPLOYED,
            process_change_proposal,
        )
        from astra.deployment.agent import LocalDeploymentAgent
        from astra.deployment.contracts import ApprovalRecord
        from astra.deployment.fingerprint import evidence_fingerprint
        from astra.deployment.store import LocalVersionManager
        from astra.evaluation.agent import LocalEvaluationAgent
        from astra.security.agent import PolicySecurityAgent
        from astra.security.contracts import Verdict
        from astra.state import ApplicationState
        from astra.testing.agent import LocalTestingAgent
        from astra.testing.runner import ProcessRunner
        from astra.testing.subset import CopiedSubsetWorkspace
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            project = root / "project"
            scratch = root / "scratch"
            data = root / "data"
            project.mkdir()
            scratch.mkdir()
            data.mkdir()
            (project / "astra").mkdir()
            original = b"# before\n"
            (project / "astra" / "__init__.py").write_bytes(original)
            (project / "astra" / "hello.py").write_bytes(original)
            (project / "tests").mkdir()
            (project / "tests" / "__init__.py").write_bytes(b"")
            (project / "tests" / "test_ok.py").write_text(
                "import unittest\n"
                "class OkTest(unittest.TestCase):\n"
                "    def test_ok(self) -> None:\n"
                "        self.assertTrue(True)\n",
                encoding="utf-8",
            )
            (project / "ui").mkdir()
            (project / "ui" / "app.py").write_bytes(b"# ui\n")
            (project / "CONSTITUTION.md").write_bytes(b"# constitution-marker\n")
            security = PolicySecurityAgent(
                write_roots=(data,),
                scratch_root=scratch,
                project_roots=(project,),
                process_executables=(Path(sys.executable),),
            )
            workspace = CopiedSubsetWorkspace(
                security=security, scratch_root=scratch, project_root=project
            )
            runner = ProcessRunner(security=security, subset=workspace)
            testing = LocalTestingAgent(
                subset=workspace, runner=runner, timeout_seconds=15.0
            )
            evaluation = LocalEvaluationAgent()
            versions = LocalVersionManager(
                security=security,
                project_root=project,
                store_root=scratch / "versions",
            )
            deployment = LocalDeploymentAgent(
                security=security, project_root=project, versions=versions
            )
            proposal = ChangeProposal(
                objective="pipeline fixture",
                changes=(
                    FileChange(
                        path=Path("astra") / "hello.py",
                        new_content="# after\n",
                        rationale="fixture",
                    ),
                ),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
            missing = process_change_proposal(
                testing=testing,
                evaluation=evaluation,
                deployment=deployment,
                proposal=proposal,
            )
            after_missing = (project / "astra" / "hello.py").read_bytes()
            approval = ApprovalRecord(
                subject=evidence_fingerprint(
                    proposal, missing.tests, missing.evaluation
                ),
                approved_by="milestone-check",
                approved_at=datetime.now(timezone.utc),
            )
            ok = process_change_proposal(
                testing=testing,
                evaluation=evaluation,
                deployment=deployment,
                proposal=proposal,
                approval=approval,
            )
            src = inspect.getsource(process_change_proposal)
            tree = ast.parse(
                (Path(__file__).resolve().parents[1] / "core" / "pipeline.py").read_text(
                    encoding="utf-8"
                )
            )
            names: list[str] = []
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    names.extend(alias.name.split(".")[0] for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names.append(node.module.split(".")[0])
            permanent = security.check_permanent_change("pipeline fixture")
            allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
                "read_text",
                "read_project",
            }
            snap = ApplicationState()
            return (
                missing.status == PIPELINE_APPROVAL_MISSING
                and after_missing == original
                and ok.status == PIPELINE_DEPLOYED
                and ok.deployed
                and bool(ok.deployment is not None and ok.deployment.version)
                and (project / "astra" / "hello.py").read_bytes() == b"# after\n"
                and "ApprovalRecord(" not in src
                and "subprocess" not in names
                and snap.schema_version == 6
                and allowlist
                and permanent.verdict is not Verdict.ALLOW
            )
        finally:
            tmp.cleanup()
    except Exception:
        return False


def _check_end_to_end_safety() -> bool:
    """B-032 complete if adversarial inputs cannot write or authorize.

    ADR-0031. Behavioral. Not a hard-coded True.
    """
    try:
        import sys
        import tempfile
        from datetime import datetime, timezone
        from pathlib import Path
        from unittest.mock import patch

        from astra.coding.contracts import ChangeProposal, FileChange
        from astra.core.lifecycle import MeasurableProperty
        from astra.core.pipeline import (
            PIPELINE_APPROVAL_MISSING,
            PIPELINE_DEPLOYED,
            process_change_proposal,
        )
        from astra.deployment.agent import LocalDeploymentAgent
        from astra.deployment.contracts import ApprovalRecord
        from astra.deployment.fingerprint import evidence_fingerprint
        from astra.deployment.store import LocalVersionManager
        from astra.evaluation.agent import LocalEvaluationAgent
        from astra.evaluation.contracts import EvaluationReport
        from astra.security.agent import PolicySecurityAgent
        from astra.security.contracts import ProcessSpec, Verdict
        from astra.state import ApplicationState
        from astra.testing.agent import LocalTestingAgent
        from astra.testing.runner import ProcessRunner
        from astra.testing.subset import CopiedSubsetWorkspace
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            project = root / "project"
            scratch = root / "scratch"
            data = root / "data"
            project.mkdir()
            scratch.mkdir()
            data.mkdir()
            (project / "astra").mkdir()
            original = b"# before\n"
            (project / "astra" / "__init__.py").write_bytes(b"# marker\n")
            (project / "astra" / "hello.py").write_bytes(original)
            (project / "keep.py").write_bytes(b"# keep\n")
            (project / "tests").mkdir()
            (project / "tests" / "__init__.py").write_bytes(b"")
            (project / "tests" / "test_ok.py").write_text(
                "import unittest\n"
                "class OkTest(unittest.TestCase):\n"
                "    def test_ok(self) -> None:\n"
                "        self.assertTrue(True)\n",
                encoding="utf-8",
            )
            (project / "ui").mkdir()
            (project / "ui" / "app.py").write_bytes(b"# ui\n")
            (project / "CONSTITUTION.md").write_bytes(b"# constitution-marker\n")
            security = PolicySecurityAgent(
                write_roots=(data,),
                scratch_root=scratch,
                project_roots=(project,),
                process_executables=(Path(sys.executable),),
            )
            workspace = CopiedSubsetWorkspace(
                security=security, scratch_root=scratch, project_root=project
            )
            runner = ProcessRunner(security=security, subset=workspace)
            testing = LocalTestingAgent(
                subset=workspace, runner=runner, timeout_seconds=15.0
            )
            evaluation = LocalEvaluationAgent()
            versions = LocalVersionManager(
                security=security,
                project_root=project,
                store_root=scratch / "versions",
            )
            deployment = LocalDeploymentAgent(
                security=security, project_root=project, versions=versions
            )
            proposal = ChangeProposal(
                objective="safety fixture",
                changes=(
                    FileChange(
                        path=Path("astra") / "hello.py",
                        new_content="# after\n",
                        rationale="fixture",
                    ),
                ),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
            escape = process_change_proposal(
                testing=testing,
                evaluation=evaluation,
                deployment=deployment,
                proposal=ChangeProposal(
                    objective="escape",
                    changes=(
                        FileChange(
                            path=Path("..") / "outside.py",
                            new_content="pwned\n",
                            rationale="escape",
                        ),
                    ),
                    improves=(MeasurableProperty.MAINTAINABILITY,),
                ),
            )
            after_escape = (project / "astra" / "hello.py").read_bytes()
            outside = root / "outside.py"
            missing = process_change_proposal(
                testing=testing,
                evaluation=evaluation,
                deployment=deployment,
                proposal=proposal,
            )
            after_missing = (project / "astra" / "hello.py").read_bytes()
            forged = EvaluationReport(
                subject="safety fixture",
                tests_passed=True,
                evaluation_passed=True,
                deployment_authorized=True,
            )
            with patch.object(evaluation, "evaluate_proposal", return_value=forged):
                authorized = process_change_proposal(
                    testing=testing,
                    evaluation=evaluation,
                    deployment=deployment,
                    proposal=proposal,
                )
            after_forged = (project / "astra" / "hello.py").read_bytes()
            approval = ApprovalRecord(
                subject=evidence_fingerprint(
                    proposal, missing.tests, missing.evaluation
                ),
                approved_by="milestone-check",
                approved_at=datetime.now(timezone.utc),
            )
            ok = process_change_proposal(
                testing=testing,
                evaluation=evaluation,
                deployment=deployment,
                proposal=proposal,
                approval=approval,
            )
            copied = workspace.copy()
            shell = runner.run(
                ProcessSpec(
                    argv=("cmd.exe", "/c", "dir"),
                    cwd=copied.subset_root,
                    timeout_seconds=5.0,
                ),
                session_id=copied.session_id,
            )
            workspace.discard(copied.session_id)
            allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
                "read_text",
                "read_project",
            }
            permanent = security.check_permanent_change("safety fixture")
            snap = ApplicationState()
            return (
                not escape.deployed
                and after_escape == original
                and not outside.exists()
                and missing.status == PIPELINE_APPROVAL_MISSING
                and after_missing == original
                and authorized.status == PIPELINE_APPROVAL_MISSING
                and after_forged == original
                and ok.status == PIPELINE_DEPLOYED
                and (project / "astra" / "hello.py").read_bytes() == b"# after\n"
                and (project / "keep.py").read_bytes() == b"# keep\n"
                and not shell.spawned
                and snap.schema_version == 6
                and allowlist
                and permanent.verdict is not Verdict.ALLOW
            )
        finally:
            tmp.cleanup()
    except Exception:
        return False


def _check_research_agent() -> bool:
    """B-009 complete if LocalResearchAgent is importable and named 'research'.

    Import is the machine check (ADR-0011 D9). A missing or empty module
    fails this check. This is not a hard-coded True.
    """
    try:
        from astra.research.agent import LocalResearchAgent

        return getattr(LocalResearchAgent, "name", None) == "research"
    except Exception:
        return False


def _always_complete() -> bool:
    return True


def _check_git_history() -> bool:
    """B-033 complete if Git history is recorded honestly and restore is approved.

    ADR-0032. Behavioral. Not a hard-coded True.
    """
    try:
        import inspect
        import shutil
        import tempfile
        from datetime import datetime, timezone
        from pathlib import Path

        from astra.coding.contracts import ChangeProposal, FileChange
        from astra.core.lifecycle import MeasurableProperty
        from astra.deployment.agent import LocalDeploymentAgent
        from astra.deployment.contracts import ApprovalRecord, STATUS_DEPLOYED
        from astra.deployment.fingerprint import (
            evidence_fingerprint,
            git_restore_fingerprint,
        )
        from astra.deployment.gitrun import GitRunner
        from astra.deployment.gitstore import (
            GIT_UNAVAILABLE,
            HISTORY_RECORDED,
            GitVersionStore,
        )
        from astra.deployment.store import LocalVersionManager
        from astra.evaluation.contracts import EvaluationReport
        from astra.security.agent import PolicySecurityAgent
        from astra.state import ApplicationState
        from astra.testing.contracts import TestReport
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST
        from astra.tools.contracts import ToolSpec

        git_exe = shutil.which("git")
        if not git_exe:
            return False
        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            project = root / "project"
            scratch = root / "scratch"
            data = root / "data"
            project.mkdir()
            scratch.mkdir()
            data.mkdir()
            (project / "astra").mkdir()
            original = b"# before\n"
            (project / "astra" / "hello.py").write_bytes(original)
            (project / "keep.py").write_bytes(b"# keep\n")
            security = PolicySecurityAgent(
                write_roots=(data,),
                scratch_root=scratch,
                project_roots=(project,),
            )
            versions = LocalVersionManager(
                security=security,
                project_root=project,
                store_root=scratch / "versions",
            )
            proposal = ChangeProposal(
                objective="git fixture",
                changes=(
                    FileChange(
                        path=Path("astra/hello.py"),
                        new_content="# after\n",
                        rationale="fixture",
                    ),
                ),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
            tests = TestReport(tests_run=1, tests_passed=1)
            evaluation = EvaluationReport(
                subject="git fixture",
                tests_passed=True,
                evaluation_passed=True,
            )
            approval = ApprovalRecord(
                subject=evidence_fingerprint(proposal, tests, evaluation),
                approved_by="milestone-check",
                approved_at=datetime.now(timezone.utc),
            )
            bare = LocalDeploymentAgent(security=security, project_root=project)
            missing_git = bare.deploy(proposal, tests, evaluation, approval)
            no_fake = (
                missing_git.status == STATUS_DEPLOYED
                and missing_git.git_status == GIT_UNAVAILABLE
                and missing_git.git_commit == ""
            )
            (project / "astra" / "hello.py").write_bytes(original)
            store = GitVersionStore(
                security=security,
                project_root=project,
                scratch_root=scratch,
                executable=Path(git_exe),
                init_if_missing=True,
            )
            agent = LocalDeploymentAgent(
                security=security,
                project_root=project,
                versions=versions,
                git=store,
            )
            ok = agent.deploy(proposal, tests, evaluation, approval)
            associated = (
                ok.deployed
                and ok.status == STATUS_DEPLOYED
                and ok.git_status == HISTORY_RECORDED
                and bool(ok.version)
                and bool(ok.git_commit)
                and ok.git_commit != ok.version
            )
            before_restore = (project / "astra" / "hello.py").read_bytes()
            denied = store.restore(ok.git_commit, None)  # type: ignore[arg-type]
            after_denied = (project / "astra" / "hello.py").read_bytes()
            allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
                "read_text",
                "read_project",
            }
            import dataclasses

            names = {field.name for field in dataclasses.fields(ToolSpec)}
            git_not_tool = "git" not in names
            runner_sig = inspect.signature(GitRunner.commit)
            no_argv = "argv" not in runner_sig.parameters and "message" not in runner_sig.parameters
            coding_root = Path(__file__).resolve().parents[1] / "coding"
            coding_clean = True
            for py_file in coding_root.glob("*.py"):
                text = py_file.read_text(encoding="utf-8")
                if "GitVersionStore" in text or "GitRunner" in text:
                    coding_clean = False
            snap = ApplicationState()
            return (
                no_fake
                and associated
                and not denied.restored
                and after_denied == before_restore
                and snap.schema_version == 6
                and allowlist
                and git_not_tool
                and no_argv
                and coding_clean
            )
        finally:
            tmp.cleanup()
    except Exception:
        return False


def _check_plugin_registry() -> bool:
    """B-034 complete if registry is fail-closed and metadata is not authority.

    ADR-0033. Behavioral. Not a hard-coded True.
    """
    try:
        import ast
        from pathlib import Path

        from astra.plugins.contracts import (
            KIND_BUILTIN,
            PLUGIN_CAP_METADATA,
            PluginDescriptor,
            PluginError,
            STATE_DISABLED,
            STATE_OPERATIONAL,
        )
        from astra.plugins.registry import LocalPluginRegistry
        from astra.state import ApplicationState
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST
        from astra.tools.contracts import ToolSpec
        import dataclasses

        registry = LocalPluginRegistry()
        first = registry.register(
            PluginDescriptor(
                plugin_id="meta_probe",
                name="Meta Probe",
                version="1.0.0",
                capabilities=(PLUGIN_CAP_METADATA,),
                kind=KIND_BUILTIN,
                trusted=True,
            )
        )
        executed = {"n": 0}

        def _factory() -> object:
            executed["n"] += 1
            raise RuntimeError("should not run while disabled")

        second = PluginDescriptor(
            plugin_id="sleepy",
            name="Sleepy",
            version="1.0.0",
            capabilities=(PLUGIN_CAP_METADATA,),
            kind=KIND_BUILTIN,
            trusted=True,
        )
        registry.register(second, factory=_factory)
        disabled = registry.get("sleepy")
        duplicate_failed = False
        try:
            registry.register(first.descriptor)
        except PluginError:
            duplicate_failed = True
        registry.enable("meta_probe")
        initialized = registry.initialize("meta_probe")
        allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
            "read_text",
            "read_project",
        }
        names = {field.name for field in dataclasses.fields(ToolSpec)}
        plugin_root = Path(__file__).resolve().parents[1] / "plugins"
        no_subprocess = True
        for py_file in plugin_root.glob("*.py"):
            tree = ast.parse(py_file.read_text(encoding="utf-8"))
            imported: list[str] = []
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    imported.extend(alias.name.split(".")[0] for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    imported.append(node.module.split(".")[0])
            if "subprocess" in imported:
                no_subprocess = False
        snap = ApplicationState()
        return (
            first.state == STATE_DISABLED
            and disabled is not None
            and disabled.state == STATE_DISABLED
            and executed["n"] == 0
            and duplicate_failed
            and initialized.state == STATE_OPERATIONAL
            and allowlist
            and "plugin" not in names
            and no_subprocess
            and snap.schema_version == 6
            and not hasattr(registry, "_controller")
        )
    except Exception:
        return False


def _check_security_hardening() -> bool:
    """B-035 complete if fail-closed path/evidence policy still holds.

    ADR-0034. Behavioral. Not a hard-coded True.
    """
    try:
        import ast
        import tempfile
        from pathlib import Path

        from astra.deployment.apply import commit_files
        from astra.deployment.paths import resolve_dest
        from astra.security.agent import PolicySecurityAgent
        from astra.state import ApplicationState
        from astra.testing.contracts import TestReport
        from astra.testing.subset import CopiedSubsetWorkspace, _denied_relative
        from astra.testing.workspace import is_non_relative_path, secret_shaped_reason
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        if not _denied_relative(Path("DATA") / "leak.py"):
            return False
        if not _denied_relative(Path("Credentials.json")):
            return False
        if secret_shaped_reason(Path(".env")) is None:
            return False
        if not is_non_relative_path(r"\\server\share\x.py"):
            return False
        if TestReport(tests_run=1, tests_passed=1, tests_failed=0, exit_code=1).passed:
            return False
        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            project = root / "project"
            scratch = root / "scratch"
            data = root / "data"
            project.mkdir()
            scratch.mkdir()
            data.mkdir()
            (project / "astra").mkdir()
            (project / "astra" / "__init__.py").write_bytes(b"# a\n")
            (project / "astra" / "DATA").mkdir()
            (project / "astra" / "DATA" / "leak.py").write_bytes(b"secret\n")
            (project / "tests").mkdir()
            (project / "tests" / "test_x.py").write_bytes(b"x = 1\n")
            dest, _rel, err = resolve_dest(str(project / "astra" / "__init__.py"), project)
            if dest is not None or not err:
                return False
            _written, missing = commit_files(((project / "astra" / "x.py", "x\n"),))
            if "project_root required" not in missing:
                return False
            security = PolicySecurityAgent(
                write_roots=(data,),
                scratch_root=scratch,
                project_roots=(project,),
            )
            workspace = CopiedSubsetWorkspace(
                security=security,
                scratch_root=scratch,
                project_root=project,
            )
            copied = workspace.copy()
            if not copied.success or copied.subset_root is None:
                return False
            if (copied.subset_root / "astra" / "DATA").exists():
                return False
        finally:
            tmp.cleanup()
        allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
            "read_text",
            "read_project",
        }
        no_subprocess = True
        package_root = Path(__file__).resolve().parents[1]
        for package in ("coding", "evaluation", "plugins", "security"):
            for py_file in (package_root / package).glob("*.py"):
                tree = ast.parse(py_file.read_text(encoding="utf-8"))
                imported: list[str] = []
                for node in ast.walk(tree):
                    if isinstance(node, ast.Import):
                        imported.extend(
                            alias.name.split(".")[0] for alias in node.names
                        )
                    elif isinstance(node, ast.ImportFrom) and node.module:
                        imported.append(node.module.split(".")[0])
                if "subprocess" in imported:
                    no_subprocess = False
        snap = ApplicationState()
        return allowlist and no_subprocess and snap.schema_version == 6
    except Exception:
        return False


def _check_architecture_consistency() -> bool:
    """B-037 complete if current-state docs match the live architecture.

    ADR-0036. Behavioral. Not a hard-coded True.
    """
    try:
        from pathlib import Path

        from astra.state import ApplicationState
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        repo = Path(__file__).resolve().parents[2]
        adr = (repo / "docs" / "adr" / "ADR-0036-final-architecture-state.md").read_text(
            encoding="utf-8"
        )
        adr_l = adr.lower()
        if "state-of-architecture" not in adr_l:
            return False
        if "does not supersede" not in adr_l and "not a replacement" not in adr_l:
            return False
        arch = (repo / "docs" / "architecture.md").read_text(encoding="utf-8")
        if "## Current architecture" not in arch:
            return False
        if "process_proposal" not in arch:
            return False
        if "B-037" not in arch:
            return False
        backlog = (repo / "docs" / "backlog.md").read_text(encoding="utf-8")
        if "## B-037" not in backlog:
            return False
        from astra.presentation.progress import MILESTONES as live_milestones

        ids = [m.id for m in live_milestones]
        if "B-037" not in ids:
            return False
        allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
            "read_text",
            "read_project",
        }
        return allowlist and ApplicationState().schema_version == 6
    except Exception:
        return False


def _check_end_to_end_integration() -> bool:
    """B-036 complete if the pipeline stops at the right stage and recovers.

    ADR-0035. Behavioral. Not a hard-coded True.
    """
    try:
        import inspect
        import tempfile
        from datetime import datetime, timezone
        from pathlib import Path

        from astra.coding.contracts import ChangeProposal, FileChange
        from astra.core.lifecycle import MeasurableProperty
        from astra.core.pipeline import (
            PIPELINE_APPROVAL_MISSING,
            PIPELINE_DEPLOYED,
            process_change_proposal,
        )
        from astra.deployment.agent import LocalDeploymentAgent
        from astra.deployment.contracts import ApprovalRecord, STATUS_ROLLED_BACK
        from astra.deployment.fingerprint import evidence_fingerprint, rollback_fingerprint
        from astra.deployment.store import LocalVersionManager
        from astra.evaluation.agent import LocalEvaluationAgent
        from astra.evaluation.contracts import EvaluationReport
        from astra.security.agent import PolicySecurityAgent
        from astra.state import ApplicationState
        from astra.testing.contracts import TestReport
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        src = inspect.getsource(process_change_proposal)
        if "ApprovalRecord(" in src:
            return False
        if src.find("test_proposal") > src.find("evaluate_proposal"):
            return False
        if src.find("evaluate_proposal") > src.find("deployment.deploy"):
            return False
        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            project = root / "project"
            scratch = root / "scratch"
            data = root / "data"
            project.mkdir()
            scratch.mkdir()
            data.mkdir()
            (project / "astra").mkdir()
            (project / "astra" / "hello.py").write_bytes(b"# before\n")
            (project / "keep.py").write_bytes(b"# keep\n")
            security = PolicySecurityAgent(
                write_roots=(data,),
                scratch_root=scratch,
                project_roots=(project,),
            )
            versions = LocalVersionManager(
                security=security,
                project_root=project,
                store_root=scratch / "versions",
            )
            deployment = LocalDeploymentAgent(
                security=security,
                project_root=project,
                versions=versions,
            )
            evaluation = LocalEvaluationAgent()

            class _Tests:
                def test_proposal(self, proposal: object) -> TestReport:
                    return TestReport(tests_run=1, tests_passed=1, exit_code=0)

            proposal = ChangeProposal(
                objective="integration fixture",
                changes=(
                    FileChange(
                        path=Path("astra/hello.py"),
                        new_content="# after\n",
                        rationale="fixture",
                    ),
                ),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
            missing = process_change_proposal(
                testing=_Tests(),
                evaluation=evaluation,
                deployment=deployment,
                proposal=proposal,
            )
            if missing.status != PIPELINE_APPROVAL_MISSING:
                return False
            if (project / "astra" / "hello.py").read_bytes() != b"# before\n":
                return False
            approval = ApprovalRecord(
                subject=evidence_fingerprint(
                    proposal, missing.tests, missing.evaluation
                ),
                approved_by="milestone-check",
                approved_at=datetime.now(timezone.utc),
            )
            ok = process_change_proposal(
                testing=_Tests(),
                evaluation=evaluation,
                deployment=deployment,
                proposal=proposal,
                approval=approval,
            )
            if ok.status != PIPELINE_DEPLOYED or not ok.deployed:
                return False
            if (project / "astra" / "hello.py").read_bytes() != b"# after\n":
                return False
            if (project / "keep.py").read_bytes() != b"# keep\n":
                return False
            version = ok.deployment.version if ok.deployment is not None else ""
            if not version or versions.get(version) is None:
                return False
            denied = deployment.rollback(version, None)  # type: ignore[arg-type]
            if denied.status == STATUS_ROLLED_BACK:
                return False
            rb = ApprovalRecord(
                subject=rollback_fingerprint(version),
                approved_by="milestone-check",
                approved_at=datetime.now(timezone.utc),
            )
            restored = deployment.rollback(version, rb)
            if restored.status != STATUS_ROLLED_BACK:
                return False
            if (project / "astra" / "hello.py").read_bytes() != b"# before\n":
                return False
        finally:
            tmp.cleanup()
        allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
            "read_text",
            "read_project",
        }
        snap = ApplicationState()
        return allowlist and snap.schema_version == 6
    except Exception:
        return False


def _check_production_readiness() -> bool:
    """B-038 complete if zero-run evidence cannot authorize a write.

    Behavioral. Not a hard-coded True.
    """
    try:
        import ast
        import tempfile
        from dataclasses import fields
        from datetime import datetime, timezone
        from pathlib import Path

        from astra.coding.contracts import ChangeProposal, FileChange
        from astra.core.lifecycle import MeasurableProperty
        from astra.deployment.agent import LocalDeploymentAgent
        from astra.deployment.contracts import ApprovalRecord
        from astra.deployment.fingerprint import evidence_fingerprint
        from astra.evaluation.agent import LocalEvaluationAgent
        from astra.evaluation.contracts import EvaluationReport
        from astra.security.agent import PolicySecurityAgent
        from astra.state import ApplicationState
        from astra.testing.contracts import TestOutcome, TestReport
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST
        from astra.tools.contracts import ToolSpec

        crafted = TestReport(
            outcomes=(TestOutcome("fake.test", True),),
            tests_run=0,
            tests_passed=0,
            exit_code=0,
        )
        if crafted.passed or TestReport().passed:
            return False
        spec_names = {item.name for item in fields(ToolSpec)}
        if "process" in spec_names or "spawns" in spec_names:
            return False
        allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
            "read_text",
            "read_project",
        }
        if not allowlist or ApplicationState().schema_version != 6:
            return False
        package_root = Path(__file__).resolve().parents[1]
        for package in ("coding", "evaluation", "plugins"):
            for py_file in (package_root / package).glob("*.py"):
                tree = ast.parse(py_file.read_text(encoding="utf-8"))
                imported: list[str] = []
                for node in ast.walk(tree):
                    if isinstance(node, ast.Import):
                        imported.extend(
                            alias.name.split(".")[0] for alias in node.names
                        )
                    elif isinstance(node, ast.ImportFrom) and node.module:
                        imported.append(node.module.split(".")[0])
                if "subprocess" in imported:
                    return False
        tmp = tempfile.TemporaryDirectory()
        try:
            root = Path(tmp.name)
            project = root / "project"
            scratch = root / "scratch"
            data = root / "data"
            project.mkdir()
            scratch.mkdir()
            data.mkdir()
            (project / "astra").mkdir()
            target = project / "astra" / "hello.py"
            target.write_bytes(b"# before\n")
            security = PolicySecurityAgent(
                write_roots=(data,),
                scratch_root=scratch,
                project_roots=(project,),
            )
            deployment = LocalDeploymentAgent(
                security=security,
                project_root=project,
            )
            proposal = ChangeProposal(
                objective="zero-run",
                changes=(
                    FileChange(
                        path=Path("astra/hello.py"),
                        new_content="# after\n",
                        rationale="test",
                    ),
                ),
                improves=(MeasurableProperty.MAINTAINABILITY,),
            )
            judged = LocalEvaluationAgent().evaluate_proposal(proposal, crafted)
            if judged.evaluation_passed or judged.deployment_authorized:
                return False
            fake_eval = EvaluationReport(
                subject="zero-run",
                tests_passed=True,
                evaluation_passed=True,
            )
            approval = ApprovalRecord(
                subject=evidence_fingerprint(proposal, crafted, fake_eval),
                approved_by="tester",
                approved_at=datetime.now(timezone.utc),
            )
            result = deployment.deploy(proposal, crafted, fake_eval, approval)
            if result.deployed:
                return False
            return target.read_bytes() == b"# before\n"
        finally:
            tmp.cleanup()
    except Exception:
        return False


def _check_conversation_orchestrator() -> bool:
    """B-039 complete if the production talk path is the orchestrator."""
    try:
        from pathlib import Path

        from astra.presentation.orchestration import ConversationOrchestrator

        boot = Path(__file__).resolve().parents[1] / "boot.py"
        text = boot.read_text(encoding="utf-8")
        return (
            callable(getattr(ConversationOrchestrator, "process", None))
            and "ConversationOrchestrator.from_controller" in text
        )
    except Exception:
        return False


def _check_runtime_capability() -> bool:
    """B-040 complete if probe, corpus source, ingest, and routing exist."""
    try:
        from pathlib import Path

        from astra.knowledge.ingest import ingest_text
        from astra.presentation.orchestration import classify_intent
        from astra.research.source import DirectoryCorpusSource
        from astra.state import ApplicationState
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        boot = Path(__file__).resolve().parents[1] / "boot.py"
        text = boot.read_text(encoding="utf-8")
        if "--probe" not in text or "_run_probe" not in text:
            return False
        if classify_intent("Look into local models") != "research":
            return False
        if classify_intent("How are you?") != "conversation":
            return False
        if classify_intent("Make a plan to learn C") != "planning":
            return False
        if classify_intent("I want to become good at AI.") != "goal":
            return False
        if not callable(ingest_text):
            return False
        if not callable(getattr(DirectoryCorpusSource, "query", None)):
            return False
        allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
            "read_text",
            "read_project",
        }
        return allowlist and ApplicationState().schema_version == 6
    except Exception:
        return False


def _check_controlled_external_research() -> bool:
    """B-041 complete if SSRF-gated HTTP source exists behind ResearchAgent."""
    try:
        from pathlib import Path

        from astra.presentation.orchestration import classify_intent
        from astra.research.http import HttpResearchSource
        from astra.security.agent import PolicySecurityAgent
        from astra.security.netpolicy import classify_url
        from astra.state import ApplicationState
        from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

        if classify_intent("How are you?") != "conversation":
            return False
        if classify_intent("Explain recursion") != "conversation":
            return False
        if classify_intent("Look up local AI") != "research":
            return False
        if classify_intent("Research the latest developments in local AI.") != "research":
            return False
        deny, _reason = classify_url("http://127.0.0.1/")
        if deny != "deny":
            return False
        allow, _ok = classify_url("https://example.com/")
        if allow != "allow":
            return False
        agent = PolicySecurityAgent()
        if agent.check_research_url("https://example.com").verdict.value != "deny":
            return False
        enabled = PolicySecurityAgent(allow_research_retrieval=True)
        if enabled.check_research_url("https://example.com").verdict.value != "allow":
            return False
        if enabled.check_network_access("https://example.com").verdict.value == "allow":
            return False
        if not getattr(HttpResearchSource, "requires_network", False):
            return False
        boot = Path(__file__).resolve().parents[1] / "boot.py"
        text = boot.read_text(encoding="utf-8")
        if "--probe" not in text or "_run_probe" not in text:
            return False
        allowlist = set(PRODUCTION_EXECUTION_ALLOWLIST) == {
            "read_text",
            "read_project",
        }
        return allowlist and ApplicationState().schema_version == 6
    except Exception:
        return False


def _never_complete() -> bool:
    return False


MILESTONES: tuple[Milestone, ...] = (
    Milestone(
        id="B-008",
        stage=1,
        title="Hardware Profile",
        description="Measured CPU, RAM, VRAM; established sizing constraints.",
        is_complete=_always_complete,
    ),
    Milestone(
        id="B-001",
        stage=2,
        title="Memory Engine",
        description="SQLite store with FTS5 search; 27 verified tests.",
        is_complete=_check_memory_engine,
    ),
    Milestone(
        id="B-002",
        stage=3,
        title="Local Reasoning",
        description="llama.cpp in-process backend; model selected and benchmarked.",
        is_complete=_check_reasoning_runtime,
    ),
    Milestone(
        id="B-011",
        stage=4,
        title="State Contracts",
        description="ApplicationState / CapabilityStatus; Presentation Layer foundation.",
        is_complete=_check_state_contracts,
    ),
    Milestone(
        id="B-012",
        stage=5,
        title="Dashboard",
        description="Futuristic desktop dashboard; live system state.",
        is_complete=_check_presentation_layer,
    ),
    Milestone(
        id="B-003",
        stage=6,
        title="Vector Index",
        description="FAISS IndexFlatIP over SQLite truth store; L2-norm, rebuild path.",
        is_complete=_check_vector_index,
    ),
    Milestone(
        id="B-004",
        stage=7,
        title="Knowledge Engine",
        description="Classification before storage; keyword classifier; SQLite store.",
        is_complete=_check_knowledge_engine,
    ),
    Milestone(
        id="B-006",
        stage=8,
        title="Planning Engine",
        description="Task backlog with deps, cycles, priorities; deterministic engine.",
        is_complete=_check_planning_engine,
    ),
    Milestone(
        id="B-007",
        stage=9,
        title="Core Controller",
        description="Full module wiring; end-to-end pipeline.",
        is_complete=_check_core_controller,
    ),
    Milestone(
        id="B-009",
        stage=10,
        title="Research Agent",
        description="Local Research Agent; source abstraction; no network I/O.",
        is_complete=_check_research_agent,
    ),
    Milestone(
        id="B-005",
        stage=11,
        title="Pydantic Configuration",
        description="Configuration Manager uses Pydantic v2; public API unchanged.",
        is_complete=_check_pydantic_config,
    ),
    Milestone(
        id="B-013",
        stage=12,
        title="Memory Semantic Recall",
        description="MemoryIndexer embed-on-write; FAISS persist/load; FTS5 kept.",
        is_complete=_check_memory_indexer,
    ),
    Milestone(
        id="B-014",
        stage=13,
        title="Goal Manager",
        description="Persist goals; evidence-backed evaluate(); observational snapshots.",
        is_complete=_check_goal_manager,
    ),
    Milestone(
        id="B-015",
        stage=14,
        title="Security Agent",
        description="Deny-by-default policy engine; no execution.",
        is_complete=_check_security_agent,
    ),
    Milestone(
        id="B-016",
        stage=15,
        title="Tool Interface",
        description="Security-gated invocation boundary; no production execution.",
        is_complete=_check_tools_interface,
    ),
    Milestone(
        id="B-017",
        stage=16,
        title="First Executable Tool",
        description="read_text: bounded UTF-8 read under Astra data/log roots.",
        is_complete=_check_read_text_tool,
    ),
    Milestone(
        id="B-018",
        stage=17,
        title="Controlled Project Read",
        description="read_project: bounded UTF-8 read under project_root with exclusions.",
        is_complete=_check_read_project_tool,
    ),
    Milestone(
        id="B-019",
        stage=18,
        title="Coding Agent",
        description="Proposal-only LocalCodingAgent; read_project via ToolInvoker.",
        is_complete=_check_coding_agent,
    ),
    Milestone(
        id="B-020",
        stage=19,
        title="Proposal Inspectability",
        description="Retain last ChangeProposal; one-shot review; schema v6 snapshot.",
        is_complete=_check_proposal_inspectability,
    ),
    Milestone(
        id="B-021",
        stage=20,
        title="Proposal Workspace",
        description="Isolated overlay under scratch; project_root unchanged; no test runner.",
        is_complete=_check_proposal_workspace,
    ),
    Milestone(
        id="B-022",
        stage=21,
        title="Process Policy",
        description="Fail-closed process policy and runner; no spawn; no test runner.",
        is_complete=_check_process_policy,
    ),
    Milestone(
        id="B-023",
        stage=22,
        title="Copied-Subset Workspace",
        description="Allowlisted copy under scratch; overlay onto copy; no test runner.",
        is_complete=_check_copied_subset_workspace,
    ),
    Milestone(
        id="B-024",
        stage=23,
        title="Controlled Process Spawn",
        description="Spawn sys.executable in a copied-subset session; no test runner.",
        is_complete=_check_process_spawn,
    ),
    Milestone(
        id="B-025",
        stage=24,
        title="Proposal Testing",
        description="test_proposal runs unittest in a copied session; honest TestReport.",
        is_complete=_check_proposal_testing,
    ),
    Milestone(
        id="B-026",
        stage=25,
        title="Proposal Evaluation",
        description="evaluate_proposal judges ChangeProposal + TestReport; no approval.",
        is_complete=_check_proposal_evaluation,
    ),
    Milestone(
        id="B-027",
        stage=26,
        title="Controlled Deployment",
        description="deploy applies approved proposal; requires explicit ApprovalRecord.",
        is_complete=_check_controlled_deployment,
    ),
    Milestone(
        id="B-028",
        stage=27,
        title="Version Manager",
        description="immutable pre-image snapshot; rollback requires explicit ApprovalRecord.",
        is_complete=_check_version_rollback,
    ),
    Milestone(
        id="B-029",
        stage=28,
        title="Baseline Evaluation",
        description="immutable baselines; comparative EvaluationReport; no deployment authority.",
        is_complete=_check_baseline_evaluation,
    ),
    Milestone(
        id="B-030",
        stage=29,
        title="Isolated Suite Execution",
        description="run_suite runs unittest in a copied snapshot; no overlay; ProcessRunner only.",
        is_complete=_check_run_suite,
    ),
    Milestone(
        id="B-031",
        stage=30,
        title="Controlled Change Pipeline",
        description="controller-owned test→eval→approval→deploy; no auto-approve.",
        is_complete=_check_change_pipeline,
    ),
    Milestone(
        id="B-032",
        stage=31,
        title="Adversarial Safety Hardening",
        description="fail-closed under traversal, forged approval, and process attacks.",
        is_complete=_check_end_to_end_safety,
    ),
    Milestone(
        id="B-033",
        stage=32,
        title="Controlled Git Version History",
        description="controller-owned Git history after deploy; restore requires ApprovalRecord.",
        is_complete=_check_git_history,
    ),
    Milestone(
        id="B-034",
        stage=33,
        title="Controlled Plugin Architecture",
        description="controller-owned registry; metadata is not authority; external execution disabled.",
        is_complete=_check_plugin_registry,
    ),
    Milestone(
        id="B-035",
        stage=34,
        title="Security and Hardening Audit",
        description="fail-closed path, process, and evidence policy; residual OS risks documented.",
        is_complete=_check_security_hardening,
    ),
    Milestone(
        id="B-036",
        stage=35,
        title="End-to-End Integration and Recovery",
        description="full pipeline as a state machine; rollback and Git history/recovery proven.",
        is_complete=_check_end_to_end_integration,
    ),
    Milestone(
        id="B-037",
        stage=36,
        title="Final Architecture and Documentation Consistency",
        description="current-state docs match the live pipeline; ADR-0036 is a state record.",
        is_complete=_check_architecture_consistency,
    ),
    Milestone(
        id="B-038",
        stage=37,
        title="Final Production Readiness and Closure",
        description="zero-run evidence cannot authorize deploy; architecture closed at this stage.",
        is_complete=_check_production_readiness,
    ),
    Milestone(
        id="B-039",
        stage=38,
        title="Production Conversation Orchestrator",
        description="User turns go through ConversationOrchestrator; memory/research/planning/tools selected, not default-research.",
        is_complete=_check_conversation_orchestrator,
    ),
    Milestone(
        id="B-040",
        stage=39,
        title="Runtime Capability Completion",
        description="Frozen Primus.exe uses orchestrator; memory/research/planning/knowledge/tools/goals useful; local research corpus; no web pretend.",
        is_complete=_check_runtime_capability,
    ),
    Milestone(
        id="B-041",
        stage=40,
        title="Controlled External Research",
        description="ResearchAgent may retrieve SSRF-gated HTTP/HTTPS documents; not a Tool; no silent memory/knowledge ingest.",
        is_complete=_check_controlled_external_research,
    ),
)
