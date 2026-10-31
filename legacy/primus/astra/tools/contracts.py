"""Tool Interface contracts.

A tool is a single capability an agent may invoke, such as reading a file or
running a command. Each one declares up front whether it needs the network and
whether it makes a permanent change, so the Security Agent can decide before
the tool runs rather than after.

``Tool`` / ``ToolRegistry`` remain abstract. Additive B-016 types describe the
security-gated request path (ADR-0015). ``ToolRegistry`` has no ``invoke``.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping, Protocol, Sequence


class ToolError(RuntimeError):
    """Raised when a tool cannot run at all.

    Ordinary failures belong in :class:`ToolResult`, not here.
    """


@dataclass(frozen=True, slots=True)
class ToolSpec:
    """Declared behaviour of a tool.

    Attributes:
        name: Unique name.
        description: What it does, written for an agent to read.
        parameters: Parameter name to description.
        requires_network: Whether invoking it reaches the network.
        makes_permanent_change: Whether invoking it alters durable state, and
            therefore requires human approval.
        reads_files: When True, path-like arguments map to
            ``check_file_read`` instead of ``check_file_write`` (ADR-0017 D7).
        scratch_writes: When True, path-like arguments map to
            ``check_temporary_write`` (isolated scratch, not durable roots).
        requires_process: When True, consult ``check_application_launch`` /
            ``check_process_execution`` instead of filesystem write checks.
        inspects_processes: When True, consult ``check_process_inspection``
            (read-only). Never authorizes termination.
        inspects_environment: When True, consult
            ``check_environment_perception`` (read-only screen/window).
        controls_input: When True, consult ``check_input_control``
            (mouse/keyboard). First implementation requires approval.
    """

    name: str
    description: str
    parameters: Mapping[str, str] = field(
        default_factory=lambda: MappingProxyType({})
    )
    requires_network: bool = False
    makes_permanent_change: bool = False
    reads_files: bool = False
    scratch_writes: bool = False
    requires_process: bool = False
    inspects_processes: bool = False
    inspects_environment: bool = False
    controls_input: bool = False


@dataclass(frozen=True, slots=True)
class ToolResult:
    """Outcome of one invocation."""

    success: bool
    output: str = ""
    error: str = ""
    details: Mapping[str, Any] = field(
        default_factory=lambda: MappingProxyType({})
    )


class Tool(ABC):
    """One invokable capability."""

    @property
    @abstractmethod
    def spec(self) -> ToolSpec:
        """Describe this tool. Must be cheap and side-effect free."""
        raise NotImplementedError

    @abstractmethod
    def invoke(self, **kwargs: Any) -> ToolResult:
        """Run the tool.

        Implementations must return a failed :class:`ToolResult` for expected
        problems instead of raising, so that one broken tool cannot abort an
        agent's run.
        """
        raise NotImplementedError


class ToolRegistry(ABC):
    """The set of tools available to agents."""

    @abstractmethod
    def register(self, tool: Tool) -> None:
        """Add a tool.

        Registering a name that is already present is an error rather than a
        silent replacement.
        """
        raise NotImplementedError

    @abstractmethod
    def get(self, name: str) -> Tool | None:
        """Return a tool by name, or ``None`` if it is not registered."""
        raise NotImplementedError

    @abstractmethod
    def specs(self) -> Sequence[ToolSpec]:
        """Describe every registered tool."""
        raise NotImplementedError


class InvocationStatus(Enum):
    """Outcome of one gated invocation request. Not a Security verdict."""

    DENIED = "denied"
    PENDING_APPROVAL = "pending_approval"
    BLOCKED_EXECUTION = "blocked_execution"
    EXECUTED = "executed"


@dataclass(frozen=True, slots=True)
class ToolInvocationRequest:
    """An agent (or test) asking to use a named tool."""

    caller: str
    tool_name: str
    arguments: Mapping[str, Any] = field(
        default_factory=lambda: MappingProxyType({})
    )


@dataclass(frozen=True, slots=True)
class ExecutionDecision:
    """Second key: whether the execution boundary permits a run.

    Independent of Security. ``permitted=True`` does not override a DENY.
    """

    permitted: bool
    reason: str


@dataclass(frozen=True, slots=True)
class ToolInvocationRecord:
    """Audit-shaped result of one ``ToolInvoker.request`` call.

    ``executed`` is True only when the inner ``Tool.invoke`` ran.
    """

    invocation_id: str
    request: ToolInvocationRequest
    spec_name: str
    verdict: str
    reason: str
    status: InvocationStatus
    execution_permitted: bool = False
    executed: bool = False
    approved: bool = False
    result: ToolResult | None = None
    timestamp: str = ""


class SecurityPolicyPort(Protocol):
    """Duck-typed Security checks. Implemented by PolicySecurityAgent.

    Defined here so ``astra.tools`` never imports ``astra.security``.
    """

    def check_network_access(self, url: str) -> Any:
        """Return an object with ``verdict`` and ``reason``."""

    def check_file_write(self, path: Path) -> Any:
        """Return an object with ``verdict`` and ``reason``."""

    def check_file_read(self, path: Path) -> Any:
        """Return an object with ``verdict`` and ``reason``."""

    def check_permanent_change(self, description: str) -> Any:
        """Return an object with ``verdict`` and ``reason``."""


class ToolInvoker(Protocol):
    """The invocation boundary future agents should receive."""

    def request(self, request: ToolInvocationRequest) -> ToolInvocationRecord:
        """Consult Security, then maybe execute. Must not raise for denial."""


__all__ = [
    "ToolError",
    "ToolSpec",
    "ToolResult",
    "Tool",
    "ToolRegistry",
    "InvocationStatus",
    "ToolInvocationRequest",
    "ExecutionDecision",
    "ToolInvocationRecord",
    "SecurityPolicyPort",
    "ToolInvoker",
]
