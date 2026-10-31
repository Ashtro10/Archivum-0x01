"""ModelsPanel — shows all three model slots with honest capability status."""

from __future__ import annotations

import dearpygui.dearpygui as dpg

from astra.state import ApplicationState, CapabilityStatus
from astra.presentation.provider import _model_status, _embed_model_status
from ui.components.capability_card import _status_color
from ui.themes.base import Theme


def _status_badge(status: CapabilityStatus, theme: Theme) -> tuple[str, list[int]]:
    return status.value.upper(), list(_status_color(status, theme))


def render_models_panel(state: ApplicationState, theme: Theme, height: int) -> None:
    # Re-derive model statuses from current config
    try:
        from astra.configuration import load_settings
        r = load_settings().reasoning
        model_path = str(r.model_path) if r.model_path else None
        embed_path = str(r.embedding_model_path) if r.embedding_model_path else None
    except Exception:
        model_path = None
        embed_path = None

    gen_cap = _model_status(model_path, state.active_model or "Qwen2.5-3B-Instruct-Q5_K_M")
    deep_cap = _model_status(None, state.deep_model or "Dolphin3.0-Llama3.1-8B Q5_K_S")
    emb_cap = _embed_model_status(embed_path)

    with dpg.child_window(width=theme.panel_width_normal, height=height,
                          border=True, tag="_models_panel"):
        dpg.add_spacer(height=4)
        dpg.add_text("  MODELS", color=list(theme.text_accent))
        dpg.add_separator()
        dpg.add_spacer(height=6)

        for title, cap, desc in [
            ("Interactive", gen_cap, "Qwen2.5-3B Q5_K_M  ctx=4096  GPU=99"),
            ("Deep candidate", deep_cap, "Llama3.1-8B Q5_K_S  gpu_layers=18"),
            ("Embedding", emb_cap, "nomic-embed-text-v1.5 Q8_0  dim=768"),
        ]:
            badge, color = _status_badge(cap.status, theme)
            dpg.add_text(f"  {title}", color=list(theme.text_secondary))
            with dpg.group(horizontal=True):
                dpg.add_spacer(width=20)
                dpg.add_text(cap.label, color=list(theme.text_primary))
                dpg.add_spacer(width=8)
                dpg.add_text(f"[{badge}]", color=color)
            dpg.add_text(f"    {desc}", color=list(theme.text_dim))
            if cap.notes:
                dpg.add_text(f"    {cap.notes[:55]}", color=list(theme.color_planned))
            dpg.add_spacer(height=6)
