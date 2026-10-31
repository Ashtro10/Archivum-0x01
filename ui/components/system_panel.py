"""SystemPanel — CPU, RAM, GPU, VRAM, Python info."""

from __future__ import annotations

import dearpygui.dearpygui as dpg

from astra.state import ApplicationState
from ui.themes.base import Theme


def _bar(label: str, value_str: str, fraction: float, theme: Theme) -> None:
    """Render a labeled progress bar row."""
    bar_w = theme.panel_width_normal - 80
    filled = max(0, min(bar_w, int(bar_w * fraction)))
    dpg.add_text(f"  {label:<10}", color=list(theme.text_secondary))
    dpg.add_same_line()
    dpg.add_text(value_str, color=list(theme.text_primary))


def render_system_panel(state: ApplicationState, theme: Theme, height: int) -> None:
    s = state.system
    with dpg.child_window(width=theme.panel_width_normal, height=height,
                          border=True, tag="_system_panel"):
        dpg.add_spacer(height=4)
        dpg.add_text("  SYSTEM", color=list(theme.text_accent))
        dpg.add_separator()
        dpg.add_spacer(height=6)

        cpu_label = s.cpu[:36] if s.cpu else "Unknown"
        dpg.add_text(f"  CPU     {cpu_label}", color=list(theme.text_primary))
        dpg.add_text(f"  Cores   {s.cpu_cores or '?'}",
                     color=list(theme.text_secondary))

        ram_str = (
            f"{s.ram_total_mib} MiB total / {s.ram_available_mib} MiB free"
            if s.ram_total_mib else "?"
        )
        dpg.add_text(f"  RAM     {ram_str}", color=list(theme.text_primary))

        dpg.add_spacer(height=6)
        dpg.add_separator()
        dpg.add_spacer(height=4)

        if s.gpu:
            dpg.add_text(f"  GPU     {s.gpu[:36]}", color=list(theme.text_primary))
            dpg.add_text(f"  VRAM    {s.vram_total_mib} MiB total",
                         color=list(theme.text_primary))
            free_vram = s.extras.get("vram_free_mib", "?")
            dpg.add_text(f"          {free_vram} MiB free",
                         color=list(theme.text_secondary))
            driver = s.extras.get("gpu_driver", "")
            if driver:
                dpg.add_text(f"  Driver  {driver}", color=list(theme.text_dim))
        else:
            dpg.add_text("  GPU     not detected", color=list(theme.color_planned))

        dpg.add_spacer(height=6)
        dpg.add_separator()
        dpg.add_spacer(height=4)
        dpg.add_text(f"  Python  {s.python_version or '?'}",
                     color=list(theme.text_secondary))
        dpg.add_text(f"  OS      {s.platform or '?'}",
                     color=list(theme.text_secondary))
