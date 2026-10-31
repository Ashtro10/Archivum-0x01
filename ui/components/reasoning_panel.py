"""ReasoningPanel — generation model, GPU config, runtime status."""

from __future__ import annotations

import dearpygui.dearpygui as dpg

from astra.state import ApplicationState, CapabilityStatus, ModuleStatus
from ui.components.capability_card import _status_color
from ui.themes.base import Theme


def _get_reasoning_module(state: ApplicationState) -> ModuleStatus | None:
    for m in state.modules:
        if m.module == "astra.reasoning":
            return m
    return None


def render_reasoning_panel(state: ApplicationState, theme: Theme, height: int) -> None:
    rea = _get_reasoning_module(state)
    mod_status = rea.status if rea else CapabilityStatus.PLANNED
    color = list(_status_color(mod_status, theme))

    with dpg.child_window(width=theme.panel_width_normal, height=height,
                          border=True, tag="_reasoning_panel"):
        dpg.add_spacer(height=4)
        with dpg.group(horizontal=True):
            dpg.add_text("  REASONING", color=list(theme.text_accent))
            dpg.add_spacer(width=12)
            dpg.add_text(f"[{mod_status.value.upper()}]", color=color)
        dpg.add_separator()
        dpg.add_spacer(height=6)

        # Active model
        model_name = state.active_model or "unconfigured"
        model_color = list(_status_color(state.active_model_status, theme))
        dpg.add_text("  Interactive model:", color=list(theme.text_secondary))
        dpg.add_text(f"    {model_name}", color=list(theme.text_primary))
        dpg.add_text(f"    Status: [{state.active_model_status.value.upper()}]",
                     color=model_color)

        dpg.add_spacer(height=6)

        if rea:
            for cap in rea.capabilities:
                if cap.notes:
                    dpg.add_text(f"  Runtime: {cap.notes[:50]}",
                                 color=list(theme.text_dim))

        dpg.add_spacer(height=4)
        dpg.add_separator()

        dpg.add_text("  Configuration:", color=list(theme.text_secondary))
        try:
            from astra.configuration import load_settings
            r = load_settings().reasoning
            dpg.add_text(f"    Context: {r.context_tokens} tokens",
                         color=list(theme.text_dim))
            dpg.add_text(f"    GPU layers: {r.gpu_layers}",
                         color=list(theme.text_dim))
        except Exception:
            dpg.add_text("    (config unavailable)", color=list(theme.text_dim))
