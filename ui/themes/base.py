"""Base Theme contract.

Themes are pure data — RGB tuples and scalar constants. They contain no
Dear PyGui imports and no application logic, so they can be tested and
swapped without touching the render loop.

RGB tuples follow Dear PyGui convention: (R, G, B) or (R, G, B, A) as
integers 0–255.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    pass  # keep imports minimal at module level

Color = tuple[int, int, int] | tuple[int, int, int, int]


@dataclass(frozen=True, slots=True)
class Theme:
    """Visual configuration for one dashboard theme.

    All dimensions are in pixels. All colors are (R, G, B) or (R, G, B, A).
    """

    name: str

    # Background layers
    bg_window: Color = (10, 14, 26, 255)
    bg_child: Color = (14, 20, 38, 255)
    bg_popup: Color = (18, 26, 48, 255)
    bg_header: Color = (8, 12, 22, 255)

    # Text
    text_primary: Color = (210, 230, 255, 255)
    text_secondary: Color = (140, 170, 210, 200)
    text_dim: Color = (80, 110, 160, 180)
    text_accent: Color = (0, 210, 255, 255)

    # Status colors
    color_operational: Color = (0, 255, 140, 255)
    color_available: Color = (0, 180, 255, 255)
    color_configured: Color = (255, 180, 0, 255)
    color_implemented: Color = (160, 100, 255, 255)
    color_planned: Color = (100, 120, 160, 255)

    # UI elements
    border: Color = (0, 150, 200, 80)
    border_bright: Color = (0, 210, 255, 150)
    button: Color = (0, 100, 160, 200)
    button_hover: Color = (0, 140, 200, 220)
    button_active: Color = (0, 180, 240, 255)
    scrollbar: Color = (0, 100, 160, 120)
    separator: Color = (0, 150, 200, 60)
    plot_bg: Color = (10, 16, 30, 255)

    # Fonts and spacing
    font_size_large: int = 22
    font_size_normal: int = 14
    font_size_small: int = 12
    padding: int = 10
    rounding: float = 4.0
    border_size: float = 1.0

    # Panel sizes (px)
    panel_width_narrow: int = 320
    panel_width_normal: int = 380
    panel_height_top: int = 340
    panel_height_bottom: int = 280

    # Progress bar visual
    progress_height: int = 6
    progress_color: Color = (0, 210, 255, 200)
    progress_bg: Color = (30, 40, 60, 180)
