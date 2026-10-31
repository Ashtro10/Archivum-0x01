"""ProjectTimeline component — displays Astra development milestones.

Milestones come from ApplicationState.completed_stages (not hard-coded).
Current stage is inferred from state.stage.
"""

from __future__ import annotations

import dearpygui.dearpygui as dpg

from astra.state import ApplicationState
from astra.presentation.progress import MILESTONES
from ui.themes.base import Theme


def render_timeline(state: ApplicationState, theme: Theme, height: int) -> None:
    """Render project timeline panel."""
    with dpg.child_window(width=theme.panel_width_narrow, height=height,
                          border=True, tag="_timeline_panel"):
        dpg.add_spacer(height=4)
        dpg.add_text("  PROJECT TIMELINE", color=list(theme.text_accent))
        dpg.add_separator()
        dpg.add_spacer(height=4)

        completed_ids = set(state.completed_stages)
        current_id = state.stage

        total = len(MILESTONES)
        done_count = len(completed_ids)
        progress_pct = done_count / total if total > 0 else 0.0

        for ms in MILESTONES:
            is_done = ms.id in completed_ids
            is_current = ms.id == current_id

            if is_done:
                marker = "[+]"
                color = list(theme.color_operational)
            elif is_current:
                marker = "[>]"
                color = list(theme.color_configured)
            else:
                marker = "[ ]"
                color = list(theme.color_planned)

            with dpg.group(horizontal=True):
                dpg.add_text(f"  {marker}", color=color)
                dpg.add_text(
                    f"S{ms.stage:02d}  {ms.title[:22]:<22}",
                    color=color,
                )
            if is_current:
                dpg.add_text(f"       {ms.description[:38]}", color=list(theme.text_dim))

        dpg.add_spacer(height=8)
        dpg.add_separator()
        dpg.add_text(
            f"  Progress: {done_count}/{total} milestones",
            color=list(theme.text_secondary),
        )
        # Progress bar
        with dpg.group(horizontal=False):
            dpg.add_spacer(height=4)
            with dpg.drawlist(width=theme.panel_width_narrow - 20, height=10):
                bar_w = theme.panel_width_narrow - 20
                filled = int(bar_w * progress_pct)
                dpg.draw_rectangle(
                    [0, 2], [bar_w, 8],
                    color=list(theme.progress_bg), fill=list(theme.progress_bg),
                )
                if filled > 0:
                    dpg.draw_rectangle(
                        [0, 2], [filled, 8],
                        color=list(theme.progress_color), fill=list(theme.progress_color),
                    )
