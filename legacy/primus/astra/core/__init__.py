"""Core Controller and the vocabulary shared across modules.

Contains the controller contract, the shared agent contract, and the lifecycle
enumerations drawn from the constitution.

B-007 (ADR-0010): concrete ``AstraController`` implemented in
:mod:`astra.core.astra_controller`.
"""

from __future__ import annotations

from astra.core.astra_controller import AstraController, ControllerStateProvider
from astra.core.contracts import Agent, AgentRequest, AgentResult
from astra.core.controller import Controller
from astra.core.errors import (
    AgentAlreadyRegisteredError,
    AgentNotFoundError,
    ControllerError,
    ControllerNotStartedError,
)
from astra.core.lifecycle import (
    Competence,
    ImprovementStage,
    LifecycleStage,
    MeasurableProperty,
    ScientificStage,
)

__all__ = [
    "Agent",
    "AgentRequest",
    "AgentResult",
    "Controller",
    "AstraController",
    "ControllerStateProvider",
    "ControllerError",
    "ControllerNotStartedError",
    "AgentAlreadyRegisteredError",
    "AgentNotFoundError",
    "LifecycleStage",
    "ImprovementStage",
    "ScientificStage",
    "MeasurableProperty",
    "Competence",
]
