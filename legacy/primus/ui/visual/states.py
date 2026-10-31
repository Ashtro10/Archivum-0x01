"""Normalized visual states. UI-facing only. Not backend enums."""

from __future__ import annotations

from enum import Enum


class VisualState(Enum):
    """Safe activity the Core may show. No private reasoning."""

    IDLE = "idle"
    LISTENING = "listening"
    THINKING = "thinking"
    PROCESSING = "processing"
    RETRIEVING = "retrieving"
    TOOL_ACTIVITY = "tool_activity"
    SPEAKING = "speaking"
    INTERRUPTED = "interrupted"
    SUCCESS = "success"
    WARNING = "warning"
    ERROR = "error"


ACTIVITY_COPY: dict[VisualState, str] = {
    VisualState.IDLE: "",
    VisualState.LISTENING: "Listening",
    VisualState.THINKING: "Thinking…",
    VisualState.PROCESSING: "Thinking…",
    VisualState.RETRIEVING: "Thinking…",
    VisualState.TOOL_ACTIVITY: "I'm looking through the available information.",
    VisualState.SPEAKING: "",
    VisualState.INTERRUPTED: "I stopped.",
    VisualState.SUCCESS: "",
    VisualState.WARNING: "Something needs attention.",
    VisualState.ERROR: "I could not complete that.",
}

__all__ = ["ACTIVITY_COPY", "VisualState"]
