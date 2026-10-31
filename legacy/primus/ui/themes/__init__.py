"""Theme system for the Astra dashboard (ADR-0006 D5, ADR-0007).

Import ``get_theme`` to retrieve a theme by name, or import a specific
theme dataclass directly. Themes contain no application logic and no
Dear PyGui imports; they are pure data containers.
"""

from ui.themes.base import Theme, Color
from ui.themes.futuristic import FUTURISTIC, get_theme

__all__ = ["Theme", "Color", "FUTURISTIC", "get_theme"]
