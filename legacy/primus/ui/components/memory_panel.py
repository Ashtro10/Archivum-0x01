"""MemoryPanel — memory engine status."""

from __future__ import annotations

import dearpygui.dearpygui as dpg

from astra.state import ApplicationState, CapabilityStatus, ModuleStatus
from ui.components.capability_card import render_capability_row, _status_color
from ui.themes.base import Theme


def _get_memory_module(state: ApplicationState) -> ModuleStatus | None:
    for m in state.modules:
        if m.module == "astra.memory":
            return m
    return None


def render_memory_panel(state: ApplicationState, theme: Theme, height: int) -> None:
    mem = _get_memory_module(state)
    mod_status = mem.status if mem else CapabilityStatus.PLANNED
    color = list(_status_color(mod_status, theme))

    with dpg.child_window(width=theme.panel_width_normal, height=height,
                          border=True, tag="_memory_panel"):
        dpg.add_spacer(height=4)
        with dpg.group(horizontal=True):
            dpg.add_text("  MEMORY ENGINE", color=list(theme.text_accent))
            dpg.add_spacer(width=12)
            label = mod_status.value.upper()
            dpg.add_text(f"[{label}]", color=color)
        dpg.add_separator()
        dpg.add_spacer(height=6)

        if mem is None:
            dpg.add_text("  status unknown", color=list(theme.color_planned))
            return

        for cap in mem.capabilities:
            render_capability_row(cap, theme)
            dpg.add_spacer(height=2)

        dpg.add_spacer(height=6)
        dpg.add_separator()
        dpg.add_text("  Storage: SQLite (source of truth)",
                     color=list(theme.text_dim))

        vec = next((c for c in mem.capabilities if c.name == "vector_index"), None)
        if vec is None:
            dpg.add_text("  Index:   not reported", color=list(theme.text_dim))
            return

        meta = vec.metadata or {}
        n = meta.get("active_vectors")
        status = vec.status.value
        if n is None:
            index_line = f"  Index:   FAISS — {status}"
        else:
            index_line = f"  Index:   FAISS — {status} ({n} vectors)"
        dpg.add_text(index_line, color=list(theme.text_dim))
        if vec.notes:
            dpg.add_text(f"           {vec.notes}", color=list(theme.text_dim))
        if meta.get("needs_rebuild"):
            dpg.add_text("           rebuild required (derived index)",
                         color=list(theme.text_dim))
