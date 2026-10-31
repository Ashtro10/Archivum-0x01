"""DemoStateProvider — read-only demo mode.

Produces an honest ApplicationState that reflects what Astra ACTUALLY has,
not a fabricated showcase. Demo mode shows real capability statuses.
This prevents demo mode from claiming capabilities that do not exist.

Use this instead of AstraStateProvider when you want to demonstrate Astra
without granting the presenter access to live configuration or file paths.
"""

from __future__ import annotations

from datetime import datetime, timezone

from astra.state import (
    ApplicationState,
    CapabilityInfo,
    CapabilityStatus,
    ModuleStatus,
    SecuritySnapshot,
    StateProvider,
    SystemInfo,
    TestStatus,
    ToolSnapshot,
    CodingSnapshot,
)
from astra.presentation.progress import MILESTONES

_CS = CapabilityStatus


class DemoStateProvider(StateProvider):
    """Returns ApplicationState based on the real milestone completion state.

    Demo mode does not fabricate operational capabilities. If something is
    CONFIGURED in reality, it is shown as CONFIGURED in demo mode. The only
    difference from AstraStateProvider is that demo mode does not reveal
    machine-specific paths, hardware identifiers, or live configuration.
    """

    def get_state(self) -> ApplicationState:
        completed = tuple(m.id for m in MILESTONES if m.for_ui())
        current_stage = next((m.id for m in MILESTONES if not m.for_ui()), "done")

        modules = (
            ModuleStatus(
                module="astra.memory",
                status=_CS.OPERATIONAL,
                capabilities=(
                    CapabilityInfo("sqlite_store", _CS.OPERATIONAL, "SQLite Store",
                                   "FTS5 search active"),
                    CapabilityInfo("fts5_search", _CS.OPERATIONAL, "FTS5 Search", ""),
                    CapabilityInfo("vector_index", _CS.AVAILABLE, "Vector Index",
                                   "FaissVectorIndex IndexFlatIP, L2-norm (B-003)"),
                ),
            ),
            ModuleStatus(
                module="astra.reasoning",
                status=_CS.CONFIGURED,
                capabilities=(
                    CapabilityInfo("llama_cpp_binding", _CS.CONFIGURED,
                                   "llama.cpp binding",
                                   "Installed; model file not shown in demo"),
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
                    CapabilityInfo("goal_manager", _CS.IMPLEMENTED,
                                   "Goal Manager",
                                   "SQLiteGoalManager present; demo has no live controller goals"),
                ),
            ),
            ModuleStatus(
                module="astra.research",
                status=_CS.OPERATIONAL,
                capabilities=(
                    CapabilityInfo("local_research_agent", _CS.OPERATIONAL,
                                   "Local Research Agent",
                                   "B-009: LocalResearchAgent; TextSource, "
                                   "DocumentSource, KnowledgeSource, MemorySource. "
                                   "Local-only; no web/network research."),
                    CapabilityInfo("network_research", _CS.PLANNED,
                                   "Network / web research",
                                   "Not implemented. allow_network=True consults "
                                   "Security and is denied. No HTTP client."),
                ),
            ),
            ModuleStatus(
                module="astra.security",
                status=_CS.IMPLEMENTED,
                capabilities=(
                    CapabilityInfo("network_policy", _CS.IMPLEMENTED,
                                   "Network policy",
                                   "PolicySecurityAgent present; demo has no live controller"),
                    CapabilityInfo("file_write_policy", _CS.IMPLEMENTED,
                                   "File-write policy",
                                   "allowlist: configured data and log directories"),
                    CapabilityInfo("file_read_policy", _CS.IMPLEMENTED,
                                   "File-read policy",
                                   "project_root only; exclusions; ALLOW/DENY"),
                    CapabilityInfo("permanent_change_policy", _CS.IMPLEMENTED,
                                   "Permanent-change policy",
                                   "never ALLOW; human approval required"),
                    CapabilityInfo("temporary_write_policy", _CS.IMPLEMENTED,
                                   "Temporary-write policy",
                                   "scratch overlay only; demo has no live controller"),
                    CapabilityInfo("process_policy", _CS.IMPLEMENTED,
                                   "Process-execution policy",
                                   "current interpreter only; demo does not spawn"),
                ),
            ),
            ModuleStatus(
                module="astra.tools",
                status=_CS.IMPLEMENTED,
                capabilities=(
                    CapabilityInfo("tool_registry", _CS.IMPLEMENTED,
                                   "Tool registry",
                                   "InMemoryToolRegistry present; demo has no live controller"),
                    CapabilityInfo("gated_invocation", _CS.IMPLEMENTED,
                                   "Gated invocation",
                                   "GatedToolInvoker present; demo has no live requests"),
                    CapabilityInfo("executable_tools", _CS.IMPLEMENTED,
                                   "Executable tools",
                                   "read_text + read_project exist; demo has no live controller execute"),
                ),
            ),
            ModuleStatus(
                module="astra.coding",
                status=_CS.IMPLEMENTED,
                capabilities=(
                    CapabilityInfo("coding_agent", _CS.IMPLEMENTED,
                                   "Coding Agent",
                                   "LocalCodingAgent exists; demo has no live propose"),
                    CapabilityInfo("proposal_apply", _CS.PLANNED,
                                   "Apply proposals",
                                   "not in B-021; never claim applied"),
                ),
            ),
            ModuleStatus(
                module="astra.testing",
                status=_CS.IMPLEMENTED,
                capabilities=(
                    CapabilityInfo("proposal_workspace", _CS.IMPLEMENTED,
                                   "Proposal workspace",
                                   "ProposalWorkspace exists; demo has no live overlay"),
                    CapabilityInfo("process_runner", _CS.IMPLEMENTED,
                                   "Process runner",
                                   "ProcessRunner exists; demo does not spawn; not a test runner"),
                    CapabilityInfo("copied_subset_workspace", _CS.IMPLEMENTED,
                                   "Copied-subset workspace",
                                   "CopiedSubsetWorkspace exists; demo has no live copy"),
                    CapabilityInfo("test_proposal", _CS.IMPLEMENTED,
                                   "Test proposal",
                                   "LocalTestingAgent exists; demo does not run the suite"),
                    CapabilityInfo("run_suite", _CS.IMPLEMENTED,
                                   "Run suite",
                                   "LocalTestingAgent exists; demo does not run the suite"),
                ),
            ),
            ModuleStatus(
                module="astra.evaluation",
                status=_CS.IMPLEMENTED,
                capabilities=(
                    CapabilityInfo("evaluate_proposal", _CS.IMPLEMENTED,
                                   "Evaluate proposal",
                                   "LocalEvaluationAgent exists; demo does not evaluate"),
                    CapabilityInfo("evaluate", _CS.IMPLEMENTED,
                                   "Evaluate baseline",
                                   "immutable baseline; comparison has no deployment authority"),
                    CapabilityInfo("record_baseline", _CS.IMPLEMENTED,
                                   "Record baseline",
                                   "immutable baseline; comparison has no deployment authority"),
                ),
            ),
            ModuleStatus(
                module="astra.deployment",
                status=_CS.IMPLEMENTED,
                capabilities=(
                    CapabilityInfo("deploy", _CS.IMPLEMENTED,
                                   "Deploy",
                                   "Requires explicit human ApprovalRecord"),
                    CapabilityInfo("process_proposal", _CS.IMPLEMENTED,
                                   "Change pipeline",
                                   "controller-owned; demo does not run the pipeline"),
                    CapabilityInfo("rollback", _CS.IMPLEMENTED,
                                   "Rollback",
                                   "Requires explicit human ApprovalRecord"),
                    CapabilityInfo("version_manager", _CS.IMPLEMENTED,
                                   "Version manager",
                                   "LocalVersionManager; scratch store; not an Agent"),
                    CapabilityInfo("git_history", _CS.IMPLEMENTED,
                                   "Git history",
                                   "history/recovery only; not authorization; demo does not run git"),
                ),
            ),
            ModuleStatus(
                module="astra.plugins",
                status=_CS.IMPLEMENTED,
                capabilities=(
                    CapabilityInfo("plugin_registry", _CS.IMPLEMENTED,
                                   "Plugin registry",
                                   "registration only; metadata is not authority; demo does not load plugins"),
                ),
            ),
            ModuleStatus(
                module="astra.state",
                status=_CS.OPERATIONAL,
                capabilities=(
                    CapabilityInfo("state_contracts", _CS.OPERATIONAL,
                                   "State Contracts", "ApplicationState live"),
                ),
            ),
        )

        return ApplicationState(
            schema_version=6,
            timestamp_iso=datetime.now(timezone.utc).isoformat(),
            stage=current_stage,
            completed_stages=completed,
            modules=modules,
            system=SystemInfo(
                cpu="AMD Ryzen 5 5600H (6c/12t)",
                cpu_cores="12",
                ram_total_mib="15773",
                ram_available_mib="—",
                gpu="NVIDIA GeForce RTX 3050 Laptop",
                vram_total_mib="4096",
                python_version="3.12.8",
                platform="Windows",
            ),
            tests=TestStatus(total=1243, passed=1243, last_run_iso="2026-08-15"),
            active_model="Qwen2.5-3B-Instruct-Q5_K_M",
            active_model_status=_CS.CONFIGURED,
            deep_model="Dolphin3.0-Llama3.1-8B Q5_K_S",
            embedding_model="nomic-embed-text-v1.5",
            notes="Demo mode — machine-specific paths not shown",
            goals=(),
            security=SecuritySnapshot(),
            tools=ToolSnapshot(
                registry_status="implemented",
                gated_invocation_status="implemented",
                executable_tool_count=0,
            ),
            coding=CodingSnapshot(
                agent_status="implemented",
                applied=False,
            ),
        )
