"""Core Controller error types."""

from __future__ import annotations


class ControllerError(Exception):
    """Base class for Core Controller failures."""


class ControllerNotStartedError(ControllerError):
    """Raised when an engine is accessed before :meth:`AstraController.start`."""


class AgentAlreadyRegisteredError(ControllerError):
    """Raised when :meth:`AstraController.register_agent` is called with a
    name that is already registered (ADR-0010 D6)."""


class AgentNotFoundError(ControllerError):
    """Raised when :meth:`AstraController.dispatch` receives an unknown agent
    name."""


__all__ = [
    "ControllerError",
    "ControllerNotStartedError",
    "AgentAlreadyRegisteredError",
    "AgentNotFoundError",
]
