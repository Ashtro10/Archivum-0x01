"""CapabilityCard — renders one CapabilityInfo row.

Receives data; does not query state. All rendering is via the passed
dpg context tag; no global state is written here.
"""

from __future__ import annotations

import dearpygui.dearpygui as dpg

from astra.state import CapabilityInfo, CapabilityStatus
from ui.themes.base import Theme

_STATUS_LABEL = {
    CapabilityStatus.OPERATIONAL: "[OPERATIONAL]",
    CapabilityStatus.AVAILABLE: "[AVAILABLE]",
    CapabilityStatus.CONFIGURED: "[CONFIGURED]",
    CapabilityStatus.IMPLEMENTED: "[IMPLEMENTED]",
    CapabilityStatus.PLANNED: "[PLANNED]",
}


def _status_color(status: CapabilityStatus, theme: Theme) -> tuple[int, int, int, int]:
    mapping = {
        CapabilityStatus.OPERATIONAL: theme.color_operational,
        CapabilityStatus.AVAILABLE: theme.color_available,
        CapabilityStatus.CONFIGURED: theme.color_configured,
        CapabilityStatus.IMPLEMENTED: theme.color_implemented,
        CapabilityStatus.PLANNED: theme.color_planned,
    }
    c = mapping.get(status, theme.color_planned)
    return c if len(c) == 4 else (*c, 255)  # type: ignore[return-value]


def render_capability_row(cap: CapabilityInfo, theme: Theme) -> None:
    """Render one capability info row inline.  Call inside an active Dear PyGui window."""
    label = cap.label or cap.name
    status_text = _STATUS_LABEL.get(cap.status, str(cap.status.value).upper())
    color = _status_color(cap.status, theme)

    with dpg.group(horizontal=True):
        dpg.add_text(f"  {label}", color=list(theme.text_secondary))
        dpg.add_text(status_text, color=list(color))

    if cap.notes:
        dpg.add_text(f"    {cap.notes[:80]}", color=list(theme.text_dim))
