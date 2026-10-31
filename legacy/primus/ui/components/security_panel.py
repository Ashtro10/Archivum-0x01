"""SecurityPanel — render-only security policy (ADR-0014 D11).

Reads ``ApplicationState.security`` and module status only. No buttons.
No command dispatch. Theme stays in ``ui.themes``.
"""

from __future__ import annotations

import dearpygui.dearpygui as dpg

from astra.state import ApplicationState, CapabilityStatus, ModuleStatus
from ui.components.capability_card import render_capability_row, _status_color
from ui.themes.base import Theme


def _get_security_module(state: ApplicationState) -> ModuleStatus | None:
    for m in state.modules:
        if m.module == "astra.security":
            return m
    return None


def render_security_panel(state: ApplicationState, theme: Theme, height: int) -> None:
    mod = _get_security_module(state)
    mod_status = mod.status if mod else CapabilityStatus.PLANNED
    color = list(_status_color(mod_status, theme))
    snap = state.security

    with dpg.child_window(
        width=theme.panel_width_normal, height=height, border=True, tag="_security_panel"
    ):
        dpg.add_spacer(height=4)
        with dpg.group(horizontal=True):
            dpg.add_text("  SECURITY", color=list(theme.text_accent))
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
            f"  outbound network: {'allowed to request approval' if snap.allow_outbound_network else 'denied by default'}",
            color=list(theme.text_dim),
        )
        dpg.add_text(
            f"  human approval required: {'yes' if snap.require_human_approval else 'no'}",
            color=list(theme.text_dim),
        )
        dpg.add_text("  file-write policy: allowlist data/log dirs", color=list(theme.text_dim))
        dpg.add_text("  permanent-change policy: never ALLOW", color=list(theme.text_dim))
        dpg.add_text(f"  checks total: {snap.checks_total}", color=list(theme.text_dim))
        verdict = snap.last_verdict or "(none)"
        dpg.add_text(f"  last verdict: {verdict}", color=list(theme.text_dim))
        if snap.last_kind:
            dpg.add_text(f"  last kind: {snap.last_kind}", color=list(theme.text_dim))
        reason = snap.last_reason or "(none)"
        dpg.add_text(f"  last reason: {reason}", color=list(theme.text_dim), wrap=360)
        dpg.add_text("  this panel does not execute or approve actions", color=list(theme.text_dim))
