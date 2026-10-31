"""CodingPanel — render-only coding-agent snapshot (ADR-0019 D5).

Reads ``ApplicationState.coding`` and module status only. No buttons.
No Apply. No command dispatch. Theme stays in ``ui.themes``.
"""

from __future__ import annotations

import dearpygui.dearpygui as dpg

from astra.state import ApplicationState, CapabilityStatus, ModuleStatus
from ui.components.capability_card import render_capability_row, _status_color
from ui.themes.base import Theme


def _get_coding_module(state: ApplicationState) -> ModuleStatus | None:
    for m in state.modules:
        if m.module == "astra.coding":
            return m
    return None


def render_coding_panel(state: ApplicationState, theme: Theme, height: int) -> None:
    mod = _get_coding_module(state)
    mod_status = mod.status if mod else CapabilityStatus.PLANNED
    color = list(_status_color(mod_status, theme))
    snap = state.coding

    with dpg.child_window(
        width=theme.panel_width_normal, height=height, border=True, tag="_coding_panel"
    ):
        dpg.add_spacer(height=4)
        with dpg.group(horizontal=True):
            dpg.add_text("  CODING", color=list(theme.text_accent))
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
            f"  agent: {snap.agent_status or 'planned'}",
            color=list(theme.text_dim),
        )
        dpg.add_text(
            f"  last proposal: {snap.last_proposal_status or 'none'}",
            color=list(theme.text_dim),
        )
        dpg.add_text(
            f"  applied: {'yes' if snap.applied else 'never (B-020)'}",
            color=list(theme.text_dim),
        )
        dpg.add_text(
            f"  last changes: {snap.last_change_count}",
            color=list(theme.text_dim),
        )
        paths = ", ".join(snap.last_change_paths) or "(none)"
        dpg.add_text(
            f"  paths: {paths}",
            color=list(theme.text_dim),
            wrap=360,
        )
        improves = ", ".join(snap.last_improves) or "(none)"
        dpg.add_text(
            f"  improves: {improves}",
            color=list(theme.text_dim),
            wrap=360,
        )
        dpg.add_text(
            f"  last risks: {snap.last_risk_count}",
            color=list(theme.text_dim),
        )
        for risk in snap.last_risks:
            dpg.add_text(
                f"    - {risk}",
                color=list(theme.text_dim),
                wrap=360,
            )
        dpg.add_text(
            f"  objections: {snap.last_objection_count}",
            color=list(theme.text_dim),
        )
        for obj in snap.last_objections:
            dpg.add_text(
                f"    - {obj}",
                color=list(theme.text_dim),
                wrap=360,
            )
        dpg.add_text(
            f"  llm used: {'yes' if snap.llm_used else 'no'}",
            color=list(theme.text_dim),
        )
        objective = snap.last_objective or "(none)"
        dpg.add_text(
            f"  last objective: {objective}",
            color=list(theme.text_dim),
            wrap=360,
        )
        preview = snap.last_preview or "(none)"
        dpg.add_text(
            f"  preview: {preview}",
            color=list(theme.text_dim),
            wrap=360,
        )
        dpg.add_text(
            "  this panel does not apply, write, or dispatch proposals",
            color=list(theme.text_dim),
        )
