"""JARVIS asset paths and visual-state mapping for the browser UI."""

from __future__ import annotations

from pathlib import Path

from ui.visual.states import VisualState

_JARVIS_STATE: dict[VisualState, str] = {
    VisualState.IDLE: "IDLE",
    VisualState.LISTENING: "LISTENING",
    VisualState.THINKING: "THINKING",
    VisualState.PROCESSING: "THINKING",
    VisualState.RETRIEVING: "THINKING",
    VisualState.TOOL_ACTIVITY: "THINKING",
    VisualState.SPEAKING: "SPEAKING",
    VisualState.INTERRUPTED: "IDLE",
    VisualState.SUCCESS: "IDLE",
    VisualState.WARNING: "THINKING",
    VisualState.ERROR: "THINKING",
}


def jarvis_assets_dir() -> Path:
    """Return canonical JARVIS bundle (repo ``jarvis-main/``)."""
    from astra.configuration.paths import project_root

    root = project_root()
    candidates = (
        root / "jarvis-main",
        root / "_internal" / "jarvis-main",
        Path(__file__).resolve().parents[2] / "jarvis-main",
    )
    for candidate in candidates:
        if (candidate / "index.html").is_file():
            return candidate
    return candidates[0]


def map_visual_state(state: VisualState) -> str:
    return _JARVIS_STATE.get(state, "IDLE")


__all__ = ["jarvis_assets_dir", "map_visual_state"]
