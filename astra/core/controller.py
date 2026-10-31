"""Core Controller.

The controller is the only module permitted to construct concrete
implementations and wire them together. Every other module depends on
interfaces alone.

Interfaces only: no behaviour is implemented here.
"""

from __future__ import annotations

from abc import ABC, abstractmethod

from astra.core.contracts import AgentRequest, AgentResult


class Controller(ABC):
    """Routes requests to agents and owns system startup and shutdown."""

    @abstractmethod
    def start(self) -> None:
        """Prepare subsystems for use.

        Must be idempotent and must not perform network I/O.
        """
        raise NotImplementedError

    @abstractmethod
    def shutdown(self) -> None:
        """Release resources.

        Must leave durable state consistent even when called after a failure.
        """
        raise NotImplementedError

    @abstractmethod
    def register_agent(self, agent_name: str, agent: object) -> None:
        """Make an agent available for dispatch.

        Args:
            agent_name: Unique name. Re-registering an existing name is an
                error rather than a silent replacement.
            agent: An :class:`astra.core.contracts.Agent` implementation.
        """
        raise NotImplementedError

    @abstractmethod
    def dispatch(self, agent_name: str, request: AgentRequest) -> AgentResult:
        """Send a request to a registered agent and return its result."""
        raise NotImplementedError


__all__ = ["Controller"]
