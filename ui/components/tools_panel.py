"""ToolsPanel — render-only tool-gate snapshot (ADR-0015 D11).

Reads ``ApplicationState.tools`` and module status only. No buttons.
No command dispatch. No approval controls. Theme stays in ``ui.themes``.
"""

from __future__ import annotations

import dearpygui.dearpygui as dpg

from astra.state import ApplicationState, CapabilityStatus, ModuleStatus
from ui.components.capability_card import render_capability_row, _status_color
from ui.themes.base import Theme


def _get_tools_module(state: ApplicationState) -> ModuleStatus | None:
    for m in state.modules:
        if m.module == "astra.tools":
            return m
    return None


def render_tools_panel(state: ApplicationState, theme: Theme, height: int) -> None:
    mod = _get_tools_module(state)
    mod_status = mod.status if mod else CapabilityStatus.PLANNED
    color = list(_status_color(mod_status, theme))
    snap = state.tools

    with dpg.child_window(
        width=theme.panel_width_normal, height=height, border=True, tag="_tools_panel"
    ):
        dpg.add_spacer(height=4)
        with dpg.group(horizontal=True):
            dpg.add_text("  TOOLS", color=list(theme.text_accent))
            dpg.add_spacer(width=12)
            dpg.add_text(f"[{mod_status.value.upper()}]", color=color)
        dpg.add_separator()
        dpg.add_spacer(height=4)

        if mod is not None:
            for cap in mod.capabilities:
                render_capability_row(cap, theme)
                dpg.add_spacer(height=2)
            dpg.add_separator()

        dpg.add_text(
            f"  registry: {snap.registry_status or 'planned'}",
            color=list(theme.text_dim),
        )
        dpg.add_text(
            f"  gated invocation: {snap.gated_invocation_status or 'planned'}",
            color=list(theme.text_dim),
        )
        dpg.add_text(
            f"  executable tools: {snap.executable_tool_count}",
            color=list(theme.text_dim),
        )
        dpg.add_text(
            "  production tool: read_text (data/log roots, 64 KiB, UTF-8)",
            color=list(theme.text_dim),
        )
        dpg.add_text(
            "  production tool: read_project (project_root, exclusions, 64 KiB, UTF-8)",
            color=list(theme.text_dim),
        )
        dpg.add_text(
            f"  registered tools: {snap.registered_tool_count}",
            color=list(theme.text_dim),
        )
        dpg.add_text(
            f"  pending approvals: {snap.pending_approvals}",
            color=list(theme.text_dim),
        )
        dpg.add_text(
            f"  executed: {snap.executed_count}",
            color=list(theme.text_dim),
        )
        last = snap.last_status or "(none)"
        dpg.add_text(f"  last status: {last}", color=list(theme.text_dim))
        if snap.last_tool_name:
            dpg.add_text(
                f"  last tool: {snap.last_tool_name}",
                color=list(theme.text_dim),
            )
        if snap.last_verdict:
            dpg.add_text(
                f"  last verdict: {snap.last_verdict}",
                color=list(theme.text_dim),
            )
        reason = snap.last_reason or "(none)"
        dpg.add_text(f"  last reason: {reason}", color=list(theme.text_dim), wrap=360)
        dpg.add_text(
            "  this panel does not execute, approve, or dispatch tools",
            color=list(theme.text_dim),
        )
