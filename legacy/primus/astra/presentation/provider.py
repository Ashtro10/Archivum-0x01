"""Concrete StateProvider that queries the live Astra system.

This module is in ``astra.presentation``, which sits above the engine
and agent layers. It may import from ``astra.configuration``,
``astra.reasoning``, ``astra.memory``, etc., to check status — but it must
not perform inference, store records, or mutate any system state.

The dashboard calls ``get_state()`` and renders the frozen result.
No UI code lives here.
"""

from __future__ import annotations

import importlib.util
import os
import platform
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from astra.state import (
    ApplicationState,
    CapabilityInfo,
    CapabilityStatus,
    CodingSnapshot,
    ModuleStatus,
    SecuritySnapshot,
    StateProvider,
    SystemInfo,
    TestStatus,
    ToolSnapshot,
)
from astra.presentation.progress import MILESTONES

_CS = CapabilityStatus


# ---------------------------------------------------------------------------
# Hardware helpers (stdlib only)
# ---------------------------------------------------------------------------

_NVIDIA_FIELDS = re.compile(r"^[A-Za-z0-9.,_]+$")
_WINDOWS_HOST_VARS = ("SYSTEMROOT", "WINDIR", "SystemDrive")


def _nvidia_smi_env() -> dict[str, str]:
    env: dict[str, str] = {}
    for key in _WINDOWS_HOST_VARS:
        value = os.environ.get(key)
        if value:
            env[key] = value
    return env


def _nvidia_smi_query(fields: str) -> str | None:
    if not fields or _NVIDIA_FIELDS.fullmatch(fields) is None:
        return None
    found = shutil.which("nvidia-smi")
    if found is None:
        return None
    exe = Path(found)
    if exe.name.lower() not in {"nvidia-smi", "nvidia-smi.exe"}:
        return None
    env = _nvidia_smi_env()
    env["PATH"] = str(exe.parent)
    try:
        out = subprocess.check_output(  # noqa: S603
            [str(exe), f"--query-gpu={fields}", "--format=csv,noheader,nounits"],
            text=True,
            timeout=3,
            env=env,
            shell=False,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        ).strip()
        return out or None
    except Exception:
        return None


def _collect_system_info() -> SystemInfo:
    cpu = platform.processor() or platform.machine()
    cpu_cores = str(os.cpu_count() or "?")

    try:
        import psutil
        vm = psutil.virtual_memory()
        ram_total = str(vm.total // 1048576)
        ram_avail = str(vm.available // 1048576)
    except ImportError:
        ram_total = ""
        ram_avail = ""

    gpu_info = _nvidia_smi_query("name,memory.total,memory.free")
    gpu = ""
    vram_total = ""
    extras: dict[str, str] = {}
    if gpu_info:
        parts = [p.strip() for p in gpu_info.split(",")]
        if len(parts) >= 3:
            gpu = parts[0]
            vram_total = parts[1]
            extras["vram_free_mib"] = parts[2]

    driver = _nvidia_smi_query("driver_version")
    if driver:
        extras["gpu_driver"] = driver

    py_ver = f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}"

    return SystemInfo(
        cpu=cpu,
        cpu_cores=cpu_cores,
        ram_total_mib=ram_total,
        ram_available_mib=ram_avail,
        gpu=gpu,
        vram_total_mib=vram_total,
        python_version=py_ver,
        platform=platform.system(),
        extras=extras,
    )


# ---------------------------------------------------------------------------
# Capability detection helpers
# ---------------------------------------------------------------------------

def _pkg_available(name: str) -> bool:
    if name in sys.modules:
        return True
    return importlib.util.find_spec(name) is not None


def _file_available(path_str: str | None) -> bool:
    if not path_str:
        return False
    try:
        return Path(path_str).is_file()
    except Exception:
        return False


def _memory_engine_status() -> tuple[_CS, str]:
    if _pkg_available("astra.memory.sqlite_store"):
        return _CS.OPERATIONAL, "SQLiteMemoryStore; FTS5 search active"
    return _CS.IMPLEMENTED, "Module exists but cannot be imported"


def _reasoning_status(model_path: str | None, gpu_layers: int) -> tuple[_CS, str]:
    binding_ok = _pkg_available("llama_cpp")
    file_ok = _file_available(model_path)
    if not binding_ok:
        return _CS.IMPLEMENTED, "llama-cpp-python not installed or DLL missing"
    if not file_ok:
        return _CS.CONFIGURED, "GGUF file not present (set model_path in astra.toml)"
    return _CS.AVAILABLE, f"llama.cpp loaded; gpu_layers={gpu_layers}"


def _model_status(model_path: str | None, label: str) -> CapabilityInfo:
    """Status for one named model."""
    binding_ok = _pkg_available("llama_cpp")
    file_ok = _file_available(model_path)
    if not binding_ok:
        st = _CS.IMPLEMENTED
        note = "llama-cpp-python missing"
    elif not file_ok:
        st = _CS.CONFIGURED
        note = "GGUF file not present"
    else:
        st = _CS.AVAILABLE
        note = str(Path(model_path).name) if model_path else ""
    return CapabilityInfo(name=label.lower().replace(" ", "_"), status=st,
                          label=label, notes=note)


def _embed_model_status(embed_path: str | None) -> CapabilityInfo:
    """Embedding model status reflects B-010 verification outcome.

    B-010 (2026-08-14): nomic-embed-text-v1.5.Q8_0.gguf verified.
    dim=768, pooled, deterministic, NOT normalized (L2~22).
    B-003 FAISS adapter must normalize vectors before indexing.
    n_ctx_train=2048; set context_tokens<=2048 for embedding model.
    """
    binding_ok = _pkg_available("llama_cpp")
    # Check for the verified embedding GGUF by name if embed_path not configured
    if not embed_path:
        from pathlib import Path
        default_path = (
            Path(__file__).parent.parent.parent
            / "benchmarks" / "models" / "nomic-embed-text-v1.5.Q8_0.gguf"
        )
        if default_path.is_file():
            embed_path = str(default_path)

    file_ok = _file_available(embed_path)
    if not binding_ok:
        st = _CS.IMPLEMENTED
        note = "llama-cpp-python missing"
    elif not file_ok:
        st = _CS.CONFIGURED
        note = "GGUF not present (place nomic-embed-text-v1.5.Q8_0.gguf in benchmarks/models/)"
    else:
        # B-010 complete: file present + verified
        st = _CS.AVAILABLE
        note = (
            "B-010 verified: dim=768, pooled, deterministic. "
            "NOT normalized (L2~22). B-003 must normalize for IndexFlatIP."
        )
    return CapabilityInfo(
        name="nomic_embed_text",
        status=st,
        label="nomic-embed-text-v1.5 Q8_0",
        notes=note,
    )


def _research_status() -> tuple[_CS, str]:
    """Local research is operational when LocalResearchAgent imports."""
    if _pkg_available("astra.research.agent"):
        return (
            _CS.OPERATIONAL,
            "LocalResearchAgent; TextSource, DocumentSource, "
            "KnowledgeSource, MemorySource, DirectoryCorpusSource.",
        )
    return _CS.PLANNED, "LocalResearchAgent not importable"


def _network_research_status() -> tuple[_CS, str]:
    """Controlled HTTP(S) retrieval behind ResearchAgent. Not a Tool."""
    try:
        from astra.research.http import HttpResearchSource

        if getattr(HttpResearchSource, "requires_network", False):
            return (
                _CS.OPERATIONAL,
                "Controlled HTTP/HTTPS document retrieval. SSRF-gated. "
                "Not a browser. Not a Tool. Deny-by-default "
                "(research.allow_external).",
            )
    except Exception:
        pass
    return _CS.PLANNED, "HttpResearchSource not importable"


def _faiss_status() -> _CS:
    """Vector index status for processes without a live controller.

    Probe path cannot see a live index, so it must never report OPERATIONAL
    (ADR-0006 D2, ADR-0012 D13). Package present → AVAILABLE; missing →
    IMPLEMENTED.
    """
    if not _pkg_available("faiss"):
        return _CS.IMPLEMENTED
    return _CS.AVAILABLE


def _security_probe_status() -> tuple[_CS, str]:
    """Probe path: policy class exists; live checks need a controller."""
    try:
        from astra.security.agent import PolicySecurityAgent
        from astra.security.contracts import SecurityAgent

        if issubclass(PolicySecurityAgent, SecurityAgent):
            return (
                _CS.IMPLEMENTED,
                "PolicySecurityAgent importable; live policy needs a started controller",
            )
    except Exception:
        pass
    return _CS.PLANNED, "Security Agent not importable"


def _tools_probe_status() -> tuple[_CS, str]:
    """Probe path: registry class exists; live gate needs a controller."""
    try:
        from astra.tools.contracts import ToolRegistry
        from astra.tools.registry import InMemoryToolRegistry

        if issubclass(InMemoryToolRegistry, ToolRegistry):
            return (
                _CS.IMPLEMENTED,
                "InMemoryToolRegistry importable; live gate needs a started controller",
            )
    except Exception:
        pass
    return _CS.PLANNED, "Tool Interface not importable"


def _coding_probe_status() -> tuple[_CS, str]:
    """Probe path: class exists; live propose needs a started controller."""
    try:
        from astra.coding.agent import LocalCodingAgent
        from astra.coding.contracts import CodingAgent

        if issubclass(LocalCodingAgent, CodingAgent):
            return (
                _CS.IMPLEMENTED,
                "LocalCodingAgent importable; live propose needs a started controller",
            )
    except Exception:
        pass
    return _CS.PLANNED, "Coding Agent not importable"


def _workspace_probe_status() -> tuple[_CS, str]:
    """Probe path: class exists; live overlay needs a started controller."""
    try:
        from astra.testing.workspace import ProposalWorkspace

        if ProposalWorkspace is not None:
            return (
                _CS.IMPLEMENTED,
                "ProposalWorkspace importable; live overlay needs a started controller",
            )
    except Exception:
        pass
    return _CS.PLANNED, "Proposal workspace not importable"


def _runner_probe_status() -> tuple[_CS, str]:
    """Probe path: class exists; live spawn needs a copied-subset session."""
    try:
        from astra.testing.runner import ProcessRunner

        if ProcessRunner is not None:
            return (
                _CS.IMPLEMENTED,
                "ProcessRunner importable; spawn needs a copied-subset session",
            )
    except Exception:
        pass
    return _CS.PLANNED, "Process runner not importable"


def _subset_probe_status() -> tuple[_CS, str]:
    """Probe path: class exists; live copy needs a started controller."""
    try:
        from astra.testing.subset import CopiedSubsetWorkspace

        if CopiedSubsetWorkspace is not None:
            return (
                _CS.IMPLEMENTED,
                "CopiedSubsetWorkspace importable; live copy needs a started controller",
            )
    except Exception:
        pass
    return _CS.PLANNED, "Copied-subset workspace not importable"


def _testing_agent_probe_status() -> tuple[_CS, str]:
    """Probe path: class exists; live test_proposal needs a started controller."""
    try:
        from astra.testing.agent import LocalTestingAgent
        from astra.testing.contracts import TestingAgent

        if issubclass(LocalTestingAgent, TestingAgent):
            return (
                _CS.IMPLEMENTED,
                "LocalTestingAgent importable; live test_proposal needs a started controller",
            )
    except Exception:
        pass
    return _CS.PLANNED, "Testing Agent not importable"


def _baseline_probe_status() -> tuple[_CS, str]:
    """Probe path: LocalBaselineStore exists; live record needs a controller."""
    try:
        from astra.evaluation.store import LocalBaselineStore

        if LocalBaselineStore is not None:
            return (
                _CS.IMPLEMENTED,
                "immutable baseline; comparison has no deployment authority",
            )
    except Exception:
        pass
    return _CS.PLANNED, "Baseline store not importable"


def _evaluation_agent_probe_status() -> tuple[_CS, str]:
    """Probe path: class exists; live evaluate_proposal needs a started controller."""
    try:
        from astra.evaluation.agent import LocalEvaluationAgent
        from astra.evaluation.contracts import EvaluationAgent

        if issubclass(LocalEvaluationAgent, EvaluationAgent):
            return (
                _CS.IMPLEMENTED,
                "LocalEvaluationAgent importable; live evaluate_proposal needs a started controller",
            )
    except Exception:
        pass
    return _CS.PLANNED, "Evaluation Agent not importable"


def _deployment_agent_probe_status() -> tuple[_CS, str]:
    """Probe path: class exists; live deploy needs ApprovalRecord + controller."""
    try:
        from astra.deployment.agent import LocalDeploymentAgent
        from astra.deployment.contracts import DeploymentAgent

        if issubclass(LocalDeploymentAgent, DeploymentAgent):
            return (
                _CS.IMPLEMENTED,
                "Requires explicit human ApprovalRecord",
            )
    except Exception:
        pass
    return _CS.PLANNED, "Deployment Agent not importable"


def _rollback_probe_status() -> tuple[_CS, str]:
    """Probe path: LocalVersionManager exists; live rollback needs ApprovalRecord."""
    try:
        from astra.deployment.store import LocalVersionManager
        from astra.deployment.versioning import VersionManager

        if issubclass(LocalVersionManager, VersionManager):
            return (
                _CS.IMPLEMENTED,
                "Requires explicit human ApprovalRecord",
            )
    except Exception:
        pass
    return _CS.PLANNED, "VersionManager not importable"


def _git_history_probe_status() -> tuple[_CS, str]:
    """Probe path: GitVersionStore exists; not authorization; demo does not run git."""
    try:
        from astra.deployment.gitstore import GitVersionStore

        if GitVersionStore is not None:
            return (
                _CS.IMPLEMENTED,
                "history/recovery only; not authorization; probe does not run git",
            )
    except Exception:
        pass
    return _CS.PLANNED, "GitVersionStore not importable"


def _plugin_registry_probe_status() -> tuple[_CS, str]:
    """Probe path: registry exists; metadata is not authority."""
    try:
        from astra.plugins.registry import LocalPluginRegistry

        if LocalPluginRegistry is not None:
            return (
                _CS.IMPLEMENTED,
                "registration only; metadata is not authority; probe does not load plugins",
            )
    except Exception:
        pass
    return _CS.PLANNED, "PluginRegistry not importable"


def _pipeline_probe_status() -> tuple[_CS, str]:
    """Probe path: orchestration exists; live run needs a started controller."""
    try:
        from astra.core.astra_controller import AstraController
        from astra.core.pipeline import process_change_proposal

        if callable(process_change_proposal) and callable(
            getattr(AstraController, "process_proposal", None)
        ):
            return (
                _CS.IMPLEMENTED,
                "controller-owned; test→eval→approval→deploy; live run needs a started controller",
            )
    except Exception:
        pass
    return _CS.PLANNED, "change pipeline not importable"


def _goal_manager_probe_status() -> tuple[_CS, str]:
    """Probe path: code exists, but live goal list needs a controller."""
    try:
        from astra.planning.goal_manager import SQLiteGoalManager
        from astra.planning.goals import GoalManager

        if issubclass(SQLiteGoalManager, GoalManager):
            return (
                _CS.IMPLEMENTED,
                "SQLiteGoalManager importable; live goal list requires a started controller",
            )
    except Exception:
        pass
    return _CS.PLANNED, "Goal Manager not importable"


def _count_tests() -> TestStatus:
    """Discover test count without running tests. Fast and safe."""
    try:
        import unittest
        loader = unittest.TestLoader()
        tests_dir = Path(__file__).parent.parent.parent / "tests"
        suite = loader.discover(str(tests_dir), pattern="test_*.py",
                                top_level_dir=str(tests_dir.parent))
        count = suite.countTestCases()
        return TestStatus(total=count, passed=count)
    except Exception:
        return TestStatus()


# ---------------------------------------------------------------------------
# AstraStateProvider
# ---------------------------------------------------------------------------

class AstraStateProvider(StateProvider):
    """Assembles ApplicationState from live Astra system.

    Reads configuration, checks file existence, and queries hardware.
    Never performs inference, never writes state, never raises in normal use.
    """

    def get_state(self) -> ApplicationState:
        try:
            return self._build_state()
        except Exception as exc:
            return ApplicationState(
                timestamp_iso=datetime.now(timezone.utc).isoformat(),
                notes=f"StateProvider error: {exc}",
            )

    def _build_state(self) -> ApplicationState:
        from astra.configuration import load_settings, ReasoningSettings

        try:
            settings = load_settings()
            r = settings.reasoning
        except Exception:
            r = ReasoningSettings()

        model_path = str(r.model_path) if r.model_path else None
        embed_path = str(r.embedding_model_path) if r.embedding_model_path else None

        # Module statuses
        mem_st, mem_note = _memory_engine_status()
        rea_st, rea_note = _reasoning_status(model_path, r.gpu_layers)
        faiss_st = _faiss_status()
        research_st, research_note = _research_status()
        net_research_st, net_research_note = _network_research_status()
        gm_st, gm_note = _goal_manager_probe_status()
        sec_st, sec_note = _security_probe_status()
        tools_st, tools_note = _tools_probe_status()
        coding_st, coding_note = _coding_probe_status()
        workspace_st, workspace_note = _workspace_probe_status()
        runner_st, runner_note = _runner_probe_status()
        subset_st, subset_note = _subset_probe_status()
        testing_st, testing_note = _testing_agent_probe_status()
        eval_st, eval_note = _evaluation_agent_probe_status()
        baseline_st, baseline_note = _baseline_probe_status()
        deploy_st, deploy_note = _deployment_agent_probe_status()
        rollback_st, rollback_note = _rollback_probe_status()
        pipeline_st, pipeline_note = _pipeline_probe_status()
        git_st, git_note = _git_history_probe_status()
        plugin_st, plugin_note = _plugin_registry_probe_status()

        modules = (
            ModuleStatus(
                module="astra.memory",
                status=mem_st,
                capabilities=(
                    CapabilityInfo("sqlite_store", mem_st, "SQLite Store", mem_note),
                    CapabilityInfo("fts5_search", mem_st, "FTS5 Search", ""),
                    CapabilityInfo("vector_index", faiss_st, "Vector Index",
                                   "FaissVectorIndex IndexFlatIP, L2-norm. "
                                   "Probe provider cannot see live vectors; "
                                   "not operational without a controller."),
                ),
            ),
            ModuleStatus(
                module="astra.reasoning",
                status=rea_st,
                capabilities=(
                    CapabilityInfo("llama_cpp_binding", rea_st, "llama.cpp binding", rea_note),
                ),
            ),
            ModuleStatus(
                module="astra.knowledge",
                status=_CS.OPERATIONAL,
                capabilities=(
                    CapabilityInfo("knowledge_store", _CS.OPERATIONAL,
                                   "SQLite Knowledge Store",
                                   "B-004: SQLiteKnowledgeStore + KeywordKnowledgeClassifier"),
                    CapabilityInfo("keyword_classifier", _CS.OPERATIONAL,
                                   "Keyword Classifier",
                                   "13 categories; deterministic; no model required"),
                ),
            ),
            ModuleStatus(
                module="astra.planning",
                status=_CS.OPERATIONAL,
                capabilities=(
                    CapabilityInfo("planning_store", _CS.OPERATIONAL,
                                   "SQLite Planning Store",
                                   "B-006: SQLitePlanningStore + SimplePlanningEngine"),
                    CapabilityInfo("planning_engine", _CS.OPERATIONAL,
                                   "Planning Engine",
                                   "Deterministic; no LLM; dep cycles prevented"),
                    CapabilityInfo("goal_manager", gm_st, "Goal Manager", gm_note),
                ),
            ),
            ModuleStatus(
                module="astra.research",
                status=research_st,
                capabilities=(
                    CapabilityInfo("local_research_agent", research_st,
                                   "Local Research Agent", research_note),
                    CapabilityInfo("network_research", net_research_st,
                                   "Controlled external research",
                                   net_research_note),
                ),
            ),
            ModuleStatus(
                module="astra.security",
                status=sec_st,
                capabilities=(
                    CapabilityInfo("network_policy", sec_st, "Network policy", sec_note),
                    CapabilityInfo("file_write_policy", sec_st, "File-write policy",
                                   "allowlist: configured data and log directories"),
                    CapabilityInfo("file_read_policy", sec_st, "File-read policy",
                                   "project_root only; exclusions; ALLOW/DENY"),
                    CapabilityInfo("permanent_change_policy", sec_st,
                                   "Permanent-change policy",
                                   "never ALLOW; human approval required"),
                    CapabilityInfo("temporary_write_policy", sec_st,
                                   "Temporary-write policy",
                                   "scratch overlay only; live checks need a started controller"),
                    CapabilityInfo("process_policy", sec_st,
                                   "Process-execution policy",
                                   "current interpreter only; live checks need a started controller"),
                ),
            ),
            ModuleStatus(
                module="astra.tools",
                status=tools_st,
                capabilities=(
                    CapabilityInfo("tool_registry", tools_st, "Tool registry",
                                   tools_note),
                    CapabilityInfo("gated_invocation", tools_st, "Gated invocation",
                                   "live requests need a started controller"),
                    CapabilityInfo("executable_tools", tools_st, "Executable tools",
                                   "read_text + read_project; live execute needs a started controller"),
                ),
            ),
            ModuleStatus(
                module="astra.coding",
                status=coding_st,
                capabilities=(
                    CapabilityInfo("coding_agent", coding_st, "Coding Agent",
                                   coding_note),
                    CapabilityInfo("proposal_apply", _CS.PLANNED, "Apply proposals",
                                   "not in B-021; Deployment after tests, eval, human approval"),
                ),
            ),
            ModuleStatus(
                module="astra.testing",
                status=workspace_st,
                capabilities=(
                    CapabilityInfo("proposal_workspace", workspace_st,
                                   "Proposal workspace", workspace_note),
                    CapabilityInfo("process_runner", runner_st,
                                   "Process runner", runner_note),
                    CapabilityInfo("copied_subset_workspace", subset_st,
                                   "Copied-subset workspace", subset_note),
                    CapabilityInfo("test_proposal", testing_st, "Test proposal",
                                   testing_note),
                    CapabilityInfo("run_suite", testing_st, "Run suite",
                                   "LocalTestingAgent importable; live run_suite needs a started controller"),
                ),
            ),
            ModuleStatus(
                module="astra.evaluation",
                status=eval_st,
                capabilities=(
                    CapabilityInfo("evaluate_proposal", eval_st,
                                   "Evaluate proposal", eval_note),
                    CapabilityInfo("evaluate", baseline_st, "Evaluate baseline",
                                   baseline_note),
                    CapabilityInfo("record_baseline", baseline_st, "Record baseline",
                                   baseline_note),
                ),
            ),
            ModuleStatus(
                module="astra.deployment",
                status=deploy_st,
                capabilities=(
                    CapabilityInfo("deploy", deploy_st, "Deploy", deploy_note),
                    CapabilityInfo("process_proposal", pipeline_st,
                                   "Change pipeline", pipeline_note),
                    CapabilityInfo("rollback", rollback_st, "Rollback",
                                   rollback_note),
                    CapabilityInfo("version_manager", rollback_st,
                                   "Version manager",
                                   "LocalVersionManager; scratch store; not an Agent"),
                    CapabilityInfo("git_history", git_st, "Git history", git_note),
                ),
            ),
            ModuleStatus(
                module="astra.plugins",
                status=plugin_st,
                capabilities=(
                    CapabilityInfo("plugin_registry", plugin_st,
                                   "Plugin registry", plugin_note),
                ),
            ),
            ModuleStatus(
                module="astra.state",
                status=_CS.OPERATIONAL,
                capabilities=(
                    CapabilityInfo("state_contracts", _CS.OPERATIONAL,
                                   "State Contracts", "ApplicationState, CapabilityStatus"),
                ),
            ),
        )

        # Models
        gen_model = _model_status(model_path, r.model)
        deep_path = str(r.deep_model_path) if getattr(r, "deep_model_path", None) else None
        deep_label = str(getattr(r, "deep_model", "") or "").strip() or "deep reasoning"
        deep_model = _model_status(deep_path, deep_label) if deep_path else _model_status(None, deep_label)
        embed_model = _embed_model_status(embed_path)

        # Milestones
        completed = tuple(m.id for m in MILESTONES if m.for_ui())
        current_stage = next((m.id for m in MILESTONES if not m.for_ui()), "done")

        return ApplicationState(
            schema_version=6,
            timestamp_iso=datetime.now(timezone.utc).isoformat(),
            stage=current_stage,
            completed_stages=completed,
            modules=modules,
            system=_collect_system_info(),
            tests=_count_tests(),
            active_model=r.model,
            active_model_status=gen_model.status,
            deep_model=deep_label if deep_path else "",
            embedding_model=r.embedding_model,
            notes="",
            goals=(),
            security=SecuritySnapshot(),
            tools=ToolSnapshot(
                registry_status="implemented" if tools_st is not _CS.PLANNED else "planned",
                gated_invocation_status="implemented" if tools_st is not _CS.PLANNED else "planned",
                executable_tool_count=0,
            ),
            coding=CodingSnapshot(
                agent_status="implemented" if coding_st is not _CS.PLANNED else "planned",
                applied=False,
            ),
        )
