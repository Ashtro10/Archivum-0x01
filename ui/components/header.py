"""Header component — title, tagline, global status badge."""

from __future__ import annotations

import dearpygui.dearpygui as dpg

from astra.product import PRODUCT_NAME, PRODUCT_DESCRIPTION
from astra.state import ApplicationState, CapabilityStatus
from ui.themes.base import Theme


def _overall_status(state: ApplicationState) -> tuple[str, list[int]]:
    """Derive global status label and color from module statuses."""
    if not state.modules:
        return "OFFLINE", [255, 50, 80, 255]
    statuses = [m.status for m in state.modules]
    if all(s == CapabilityStatus.OPERATIONAL for s in statuses):
        return "FULLY OPERATIONAL", [0, 255, 140, 255]
    if any(s in (CapabilityStatus.OPERATIONAL, CapabilityStatus.AVAILABLE) for s in statuses):
        return "PARTIAL", [255, 175, 0, 255]
    return "INITIALIZING", [130, 160, 200, 200]


def render_header(state: ApplicationState, theme: Theme, viewport_width: int) -> None:
    """Render the top header bar."""
    status_label, status_color = _overall_status(state)

    with dpg.child_window(height=90, border=False,
                          tag="_header_panel"):
        dpg.add_spacer(height=8)
        with dpg.group(horizontal=True):
            dpg.add_spacer(width=theme.padding)
            dpg.add_text(PRODUCT_NAME.upper(), color=list(theme.text_accent))
            dpg.add_spacer(width=16)
            dpg.add_text(PRODUCT_DESCRIPTION,
                         color=list(theme.text_secondary))
            dpg.add_spacer(width=40)
            dpg.add_text(f"STATUS: {status_label}", color=status_color)
        dpg.add_spacer(height=4)
        dpg.add_text(
            f"  Stage: {state.stage}  |  "
            f"Completed: {len(state.completed_stages)}  |  "
            f"Tests: {state.tests.total}  |  "
            f"Python {state.system.python_version}",
            color=list(theme.text_dim),
        )
        dpg.add_separator()
