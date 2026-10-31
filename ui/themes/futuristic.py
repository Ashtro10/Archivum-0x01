"""TRON-inspired black + cyan. Quiet machine, not a cockpit."""

from __future__ import annotations

from ui.themes.base import Theme

CYAN = (0, 229, 255, 255)
CYAN_DIM = (0, 140, 160, 200)
CYAN_GLOW = (0, 200, 220, 180)

FUTURISTIC = Theme(
    name="futuristic",
    bg_window=(0, 0, 0, 255),
    bg_child=(0, 0, 0, 255),
    bg_popup=(4, 8, 10, 255),
    bg_header=(0, 0, 0, 255),
    text_primary=(180, 245, 255, 255),
    text_secondary=(0, 180, 200, 210),
    text_dim=(0, 110, 120, 180),
    text_accent=CYAN,
    color_operational=(0, 229, 255, 255),
    color_available=(0, 200, 220, 255),
    color_configured=(0, 170, 190, 255),
    color_implemented=(0, 140, 160, 255),
    color_planned=(0, 80, 90, 220),
    border=(0, 180, 200, 200),
    border_bright=CYAN,
    button=(0, 20, 24, 255),
    button_hover=(0, 50, 58, 255),
    button_active=(0, 140, 150, 255),
    scrollbar=(0, 120, 130, 140),
    separator=(0, 160, 180, 120),
    plot_bg=(0, 0, 0, 255),
    font_size_large=18,
    font_size_normal=14,
    font_size_small=12,
    padding=16,
    rounding=0.0,
    border_size=1.0,
    panel_width_narrow=320,
    panel_width_normal=380,
    panel_height_top=340,
    panel_height_bottom=300,
    progress_height=2,
    progress_color=CYAN_GLOW,
    progress_bg=(0, 20, 24, 180),
)


def get_theme(name: str = "futuristic") -> Theme:
    """Return a theme by name.  Raises ValueError for unknown names."""
    themes = {"futuristic": FUTURISTIC}
    if name not in themes:
        raise ValueError(f"Unknown theme {name!r}; available: {list(themes)}")
    return themes[name]
