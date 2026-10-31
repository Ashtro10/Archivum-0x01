"""Contracts shared by every agent.

Agents exchange these objects and nothing else. Keeping the shared vocabulary
small is what allows one agent to be replaced without touching the others.

Interfaces only: no behaviour is implemented here.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from types import MappingProxyType
from typing import Any, ClassVar, Mapping


@dataclass(frozen=True, slots=True)
class AgentRequest:
    """A unit of work handed to an agent.

    Attributes:
        objective: What the caller wants, in plain language.
        context: Read-only supporting material, such as retrieved memory.
        constraints: Restrictions the agent must respect.
    """

    objective: str
    context: Mapping[str, Any] = field(default_factory=lambda: MappingProxyType({}))
    constraints: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class AgentResult:
    """What an agent returns.

    Attributes:
        agent: Name of the agent that produced this result.
        success: Whether the objective was met.
        summary: Short human-readable outcome.
        details: Structured output, specific to the agent.
        evidence: References supporting the result, such as file paths,
            measurements, or memory record identifiers. Claims without
            evidence are assumptions, which the constitution ranks last.
    """

    agent: str
    success: bool
    summary: str
    details: Mapping[str, Any] = field(default_factory=lambda: MappingProxyType({}))
    evidence: tuple[str, ...] = ()


class Agent(ABC):
    """Base contract for every agent in the system.

    Subclasses declare ``name`` and implement :meth:`run`. Agents must not
    import one another's implementations; the Core Controller wires them.
    """

    name: ClassVar[str] = "agent"

    @abstractmethod
    def run(self, request: AgentRequest) -> AgentResult:
        """Execute one unit of work.

        Implementations must not raise for ordinary failure. Return an
        ``AgentResult`` with ``success=False`` and an explanatory summary
        instead, so a failing agent cannot halt the system.
        """
        raise NotImplementedError


__all__ = ["AgentRequest", "AgentResult", "Agent"]
