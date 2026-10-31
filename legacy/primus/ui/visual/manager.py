"""Map ApplicationState + local interaction onto VisualState.

No fake work timers. Background refresh does not invent processing.
"""

from __future__ import annotations

from astra.state import ApplicationState, CapabilityStatus
from ui.visual.states import VisualState


class VisualStateManager:
    """Pure mapping. Safe to test without Dear PyGui."""

    def __init__(self) -> None:
        self._interaction = VisualState.IDLE
        self._override: VisualState | None = None

    def set_interaction(self, state: VisualState) -> None:
        self._interaction = state

    def resolve(self, app: ApplicationState | None) -> VisualState:
        if self._interaction in {
            VisualState.LISTENING,
            VisualState.THINKING,
            VisualState.PROCESSING,
            VisualState.RETRIEVING,
            VisualState.SPEAKING,
            VisualState.INTERRUPTED,
            VisualState.SUCCESS,
            VisualState.WARNING,
            VisualState.ERROR,
        }:
            return self._interaction
        if app is None:
            return VisualState.IDLE
        if not app.modules:
            return VisualState.ERROR
        statuses = [module.status for module in app.modules]
        if all(status is CapabilityStatus.PLANNED for status in statuses):
            return VisualState.ERROR
        if any(status is CapabilityStatus.OPERATIONAL for status in statuses):
            return VisualState.IDLE
        if any(
            status in (CapabilityStatus.AVAILABLE, CapabilityStatus.CONFIGURED)
            for status in statuses
        ):
            return VisualState.WARNING
        return VisualState.IDLE


__all__ = ["VisualStateManager"]
