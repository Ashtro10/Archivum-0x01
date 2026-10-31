"""State and telemetry contracts for the Astra Presentation Layer.

This module is intentionally a leaf in the dependency graph. It imports
nothing from the rest of ``astra``. The Presentation Layer (dashboard, API
server, demo mode) imports from here; the core modules (memory, reasoning,
planning, ...) do not.

The design separates five things that must not be conflated (ADR-0006 D2):

    planned       Code not yet written.
    implemented   Code exists; not configured for this machine.
    configured    Configuration provided; runtime prerequisites may be absent.
    available     Can load and respond; not exercised end-to-end.
    operational   Running and verified.

These statuses flow upward: the Core Controller assembles an
``ApplicationState`` by querying each module. The dashboard consumes
``ApplicationState`` without knowing how it was assembled.

Nothing in this module performs I/O, modifies state, or has side effects.
"""

from __future__ import annotations

import enum
from dataclasses import dataclass, field
from typing import Any


class CapabilityStatus(enum.Enum):
    """Lifecycle status of a single Astra capability.

    Values are ordered from least to most ready. Do not compare ordinally
    in code; use explicit membership checks.
    """

    PLANNED = "planned"
    IMPLEMENTED = "implemented"
    CONFIGURED = "configured"
    AVAILABLE = "available"
    OPERATIONAL = "operational"


@dataclass(frozen=True, slots=True)
class CapabilityInfo:
    """Status of one named capability.

    Attributes:
        name: Machine-readable capability identifier, e.g. ``"memory_engine"``.
        status: Current lifecycle status.
        label: Short human-readable display name.
        notes: Optional detail visible in the dashboard (e.g. model name,
            version, or reason for non-operational status).
        metadata: Unstructured key-value pairs for dashboard-specific
            rendering. Do not put business logic here.
    """

    name: str
    status: CapabilityStatus
    label: str = ""
    notes: str = ""
    metadata: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.name:
            raise ValueError("CapabilityInfo.name must not be empty")


@dataclass(frozen=True, slots=True)
class ModuleStatus:
    """Status summary for one top-level Astra module.

    Attributes:
        module: Python dotted name, e.g. ``"astra.memory"``.
        status: Coarsest status across all capabilities in the module.
        capabilities: Individual capabilities this module exposes.
    """

    module: str
    status: CapabilityStatus
    capabilities: tuple[CapabilityInfo, ...] = ()


@dataclass(frozen=True, slots=True)
class SystemInfo:
    """Hardware and runtime facts at the time ``ApplicationState`` was built.

    All values are strings to avoid cross-platform import complications.
    Callers supply them; this dataclass does not query the system itself.

    Attributes:
        cpu: CPU model string, e.g. ``"AMD Ryzen 5 5600H"``.
        cpu_cores: Physical core count as a string.
        ram_total_mib: Total system RAM in MiB as a string.
        ram_available_mib: Available system RAM in MiB at query time.
        gpu: GPU model string, or empty if none.
        vram_total_mib: GPU VRAM in MiB as a string, or empty if none.
        python_version: Python version string, e.g. ``"3.12.8"``.
        platform: OS/platform string.
        extras: Additional key-value pairs (e.g. driver version, CUDA version).
    """

    cpu: str = ""
    cpu_cores: str = ""
    ram_total_mib: str = ""
    ram_available_mib: str = ""
    gpu: str = ""
    vram_total_mib: str = ""
    python_version: str = ""
    platform: str = ""
    extras: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class TestStatus:
    """Summary of the most recent test run.

    Attributes:
        total: Total tests in the suite.
        passed: Tests that passed.
        failed: Tests that failed.
        errors: Tests that errored (not the same as failed).
        skipped: Tests skipped.
        last_run_iso: ISO-8601 timestamp of the last run, or empty.
    """

    total: int = 0
    passed: int = 0
    failed: int = 0
    errors: int = 0
    skipped: int = 0
    last_run_iso: str = ""

    @property
    def all_passing(self) -> bool:
        return self.failed == 0 and self.errors == 0 and self.total > 0


@dataclass(frozen=True, slots=True)
class GoalSnapshot:
    """Presentation DTO for one goal. No planning-package types.

    ``task_progress`` is linked-task completion, not goal success.
    ``blocked`` is derived from linked tasks in BLOCKED status.
    """

    goal_id: str
    description: str
    status: str = ""
    success_criteria: tuple[str, ...] = ()
    task_ids: tuple[str, ...] = ()
    tasks_done: int = 0
    tasks_total: int = 0
    blocked: bool = False
    last_evaluate_met: bool | None = None
    last_evaluated_at: str = ""
    evidence_hit_count: int = 0
    embedding_used: bool = False
    created_at: str = ""


@dataclass(frozen=True, slots=True)
class SecuritySnapshot:
    """Presentation DTO for Security Agent policy. No security-package types."""

    allow_outbound_network: bool = False
    require_human_approval: bool = True
    last_verdict: str = ""
    last_reason: str = ""
    last_kind: str = ""
    checks_total: int = 0


@dataclass(frozen=True, slots=True)
class ToolSnapshot:
    """Presentation DTO for the tool gate. No tools-package types."""

    registry_status: str = "planned"
    gated_invocation_status: str = "planned"
    executable_tool_count: int = 0
    registered_tool_count: int = 0
    pending_approvals: int = 0
    executed_count: int = 0
    last_status: str = ""
    last_tool_name: str = ""
    last_verdict: str = ""
    last_reason: str = ""


@dataclass(frozen=True, slots=True)
class CodingSnapshot:
    """Presentation DTO for the Coding Agent. No coding-package types.

    ``applied`` is always False in B-020. ``last_proposal_status`` is never
    ``applied``, ``approved``, ``tested``, or ``deployed``. Full file bodies
    stay on the agent, not here. Preview is bounded.
    """

    agent_status: str = "planned"
    last_proposal_status: str = "none"
    applied: bool = False
    last_objective: str = ""
    last_change_count: int = 0
    last_risk_count: int = 0
    llm_used: bool = False
    last_change_paths: tuple[str, ...] = ()
    last_risks: tuple[str, ...] = ()
    last_objections: tuple[str, ...] = ()
    last_improves: tuple[str, ...] = ()
    last_preview: str = ""
    last_objection_count: int = 0


@dataclass(frozen=True, slots=True)
class ApplicationState:
    """Root state object consumed by the Presentation Layer.

    The dashboard renders this; the Core Controller (or a dedicated
    ``StateProvider``) assembles it. Neither side knows about the other.

    Attributes:
        schema_version: Integer version of this dataclass layout. Increment
            when fields are added or renamed so that a cached state can be
            detected as stale.
        timestamp_iso: ISO-8601 UTC timestamp when this snapshot was taken.
        stage: Current milestone label, e.g. ``"B-003"``.
        completed_stages: Ordered list of completed milestone IDs.
        modules: Status of each top-level Astra module.
        system: Hardware and runtime facts.
        tests: Most recent test run summary.
        active_model: Human-readable label of the current generation model,
            or empty if unconfigured.
        active_model_status: Status of the generation model capability.
        deep_model: Label of the optional deep-reasoning model, or empty.
        embedding_model: Label of the embedding model, or empty.
        notes: Free-form notes from the state builder (not shown in normal
            dashboard mode, only in debug/verbose mode).
        goals: Live goal snapshots. Empty when no Goal Manager is running.
        security: Live security policy snapshot. Defaults are deny-by-default.
        tools: Live tool-gate snapshot.
        coding: Live coding-agent snapshot. ``applied`` is always False in B-020.
            Inspectable fields are bounded; full ``new_content`` is not stored.
    """

    schema_version: int = 6
    timestamp_iso: str = ""
    stage: str = ""
    completed_stages: tuple[str, ...] = ()
    modules: tuple[ModuleStatus, ...] = ()
    system: SystemInfo = field(default_factory=SystemInfo)
    tests: TestStatus = field(default_factory=TestStatus)
    active_model: str = ""
    active_model_status: CapabilityStatus = CapabilityStatus.PLANNED
    deep_model: str = ""
    embedding_model: str = ""
    notes: str = ""
    goals: tuple[GoalSnapshot, ...] = ()
    security: SecuritySnapshot = field(default_factory=SecuritySnapshot)
    tools: ToolSnapshot = field(default_factory=ToolSnapshot)
    coding: CodingSnapshot = field(default_factory=CodingSnapshot)


class StateProvider:
    """Abstract base for objects that can produce an ``ApplicationState``.

    Subclasses query the live system and assemble a snapshot. The dashboard
    calls ``get_state()`` and receives a frozen, serialisable value; it does
    not hold a reference to the provider itself.

    This is not an ``abc.ABC`` subclass to keep the import surface minimal.
    Callers that need duck-typing verification should check for the method
    rather than the class.
    """

    def get_state(self) -> ApplicationState:
        """Return a snapshot of the current application state.

        Must never raise in normal operation. On error, return an
        ``ApplicationState`` with minimal fields populated and the error
        message in ``notes``.
        """
        raise NotImplementedError(
            "StateProvider.get_state() must be overridden by a concrete provider"
        )


__all__ = [
    "CapabilityStatus",
    "CapabilityInfo",
    "ModuleStatus",
    "SystemInfo",
    "TestStatus",
    "ApplicationState",
    "GoalSnapshot",
    "SecuritySnapshot",
    "ToolSnapshot",
    "CodingSnapshot",
    "StateProvider",
]
