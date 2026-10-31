"""Dashboard UI components (ADR-0007).

Each component receives ``ApplicationState`` and a ``Theme``; it does not
query state itself. Components import only from ``astra.state``,
``astra.presentation``, and ``ui.*``.
"""

from ui.components.capability_card import render_capability_row
from ui.components.header import render_header
from ui.components.timeline import render_timeline
from ui.components.system_panel import render_system_panel
from ui.components.memory_panel import render_memory_panel
from ui.components.reasoning_panel import render_reasoning_panel
from ui.components.models_panel import render_models_panel
from ui.components.goals_panel import render_goals_panel
from ui.components.security_panel import render_security_panel
from ui.components.tools_panel import render_tools_panel
from ui.components.coding_panel import render_coding_panel

__all__ = [
    "render_capability_row",
    "render_header",
    "render_timeline",
    "render_system_panel",
    "render_memory_panel",
    "render_reasoning_panel",
    "render_models_panel",
    "render_goals_panel",
    "render_security_panel",
    "render_tools_panel",
    "render_coding_panel",
]
