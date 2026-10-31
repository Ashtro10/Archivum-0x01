"""Application state contracts for the Presentation Layer (ADR-0006).

This package is a leaf in ``astra``'s dependency graph. It imports nothing
from the rest of ``astra``. The dashboard and any future Presentation API
import from here; core modules (memory, reasoning, planning) do not.

See ``astra.state.contracts`` for the full type catalogue.
"""

from astra.state.contracts import (
    ApplicationState,
    CapabilityInfo,
    CapabilityStatus,
    CodingSnapshot,
    GoalSnapshot,
    ModuleStatus,
    SecuritySnapshot,
    StateProvider,
    SystemInfo,
    TestStatus,
    ToolSnapshot,
)

__all__ = [
    "ApplicationState",
    "CapabilityInfo",
    "CapabilityStatus",
    "CodingSnapshot",
    "GoalSnapshot",
    "ModuleStatus",
    "SecuritySnapshot",
    "StateProvider",
    "SystemInfo",
    "TestStatus",
    "ToolSnapshot",
]
