"""Visual state mapping for the browser UI."""

from ui.visual.jarvis_host import jarvis_assets_dir, map_visual_state
from ui.visual.manager import VisualStateManager
from ui.visual.states import ACTIVITY_COPY, VisualState

__all__ = [
    "ACTIVITY_COPY",
    "VisualState",
    "VisualStateManager",
    "jarvis_assets_dir",
    "map_visual_state",
]
