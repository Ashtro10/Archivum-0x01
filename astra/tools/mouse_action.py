"""Gated mouse control. Controller injects the host. No real move in tools."""

from __future__ import annotations

from types import MappingProxyType
from typing import Any, Callable, Mapping

from astra.tools.contracts import Tool, ToolResult, ToolSpec

TOOL_NAME = "mouse_action"
_ALLOWED_KEYS = frozenset(
    {"action", "x", "y", "button", "click_count", "scroll_amount", "observe"}
)
_ACTIONS = frozenset({"move", "click", "double_click", "scroll"})
_BUTTONS = frozenset({"left", "right", "middle"})
_MAX_SCROLL = 50
MouseExecute = Callable[[str, int, int, str, int, int], Mapping[str, Any]]
ScreenSize = Callable[[], tuple[int, int]]


class MouseActionTool(Tool):
    """Move, click, or scroll. Coordinates must sit on the live screen."""

    def __init__(
        self,
        act: MouseExecute | None = None,
        screen_size: ScreenSize | None = None,
    ) -> None:
        self._act = act
        self._screen_size = screen_size

    def bind_act(self, act: MouseExecute | None) -> None:
        self._act = act

    def bind_screen_size(self, screen_size: ScreenSize | None) -> None:
        self._screen_size = screen_size

    @classmethod
    def from_settings(
        cls,
        settings: Any = None,
        act: MouseExecute | None = None,
        screen_size: ScreenSize | None = None,
    ) -> "MouseActionTool":
        del settings
        return cls(act=act, screen_size=screen_size)

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name=TOOL_NAME,
            description=(
                "Move the pointer, click, double-click, or scroll. "
                "Requires approval. Does not type keys."
            ),
            parameters=MappingProxyType(
                {
                    "action": "move, click, double_click, or scroll",
                    "x": "Horizontal pixel coordinate",
                    "y": "Vertical pixel coordinate",
                    "button": "left, right, or middle",
                    "click_count": "Clicks for click actions",
                    "scroll_amount": "Wheel ticks for scroll",
                    "observe": "Capture screen after the action (default true)",
                }
            ),
            requires_network=False,
            makes_permanent_change=False,
            controls_input=True,
        )

    def invoke(self, **kwargs: Any) -> ToolResult:
        extra = sorted(k for k in kwargs if k not in _ALLOWED_KEYS)
        if extra:
            return ToolResult(
                success=False,
                error=f"unknown arguments: {extra}; fail closed",
            )
        action = str(kwargs.get("action") or "").strip().lower()
        if action not in _ACTIONS:
            return ToolResult(success=False, error="mouse action is invalid; fail closed")
        button = str(kwargs.get("button") or "left").strip().lower()
        if button not in _BUTTONS:
            return ToolResult(success=False, error="mouse button is invalid; fail closed")
        point = _parse_point(kwargs.get("x"), kwargs.get("y"))
        if point is None:
            return ToolResult(success=False, error="coordinates are invalid; fail closed")
        x, y = point
        clicks = _parse_int(kwargs.get("click_count"), 2 if action == "double_click" else 1)
        scroll = _parse_int(kwargs.get("scroll_amount"), 0)
        if clicks is None or clicks <= 0 or clicks > 3:
            return ToolResult(success=False, error="click_count is invalid; fail closed")
        if scroll is None or abs(scroll) > _MAX_SCROLL:
            return ToolResult(success=False, error="scroll_amount is invalid; fail closed")
        if action == "double_click":
            clicks = 2
        if action == "scroll" and scroll == 0:
            return ToolResult(success=False, error="scroll_amount is required; fail closed")
        if self._screen_size is None or self._act is None:
            return ToolResult(success=False, error="mouse host unavailable; fail closed")
        try:
            width, height = self._screen_size()
        except Exception as exc:  # noqa: BLE001
            return ToolResult(
                success=False,
                error=f"screen dimensions unavailable: {type(exc).__name__}: {exc}",
            )
        width, height = int(width), int(height)
        if width <= 0 or height <= 0:
            return ToolResult(success=False, error="screen dimensions unavailable; fail closed")
        if not (0 <= x < width and 0 <= y < height):
            return ToolResult(
                success=False,
                error=f"coordinates {x},{y} outside {width}x{height}; fail closed",
            )
        try:
            outcome = dict(self._act(action, x, y, button, clicks, scroll) or {})
        except Exception as exc:  # noqa: BLE001
            return ToolResult(
                success=False,
                error=f"mouse action failed: {type(exc).__name__}: {exc}",
            )
        ok = bool(outcome.get("success", False))
        error = "" if ok else str(outcome.get("error") or "mouse action failed")
        output = (
            f"action={action} x={x} y={y} button={button} "
            f"success={str(ok).lower()} resolution={width}x{height}"
        )
        details = MappingProxyType(
            {
                "action": action,
                "x": x,
                "y": y,
                "button": button,
                "click_count": clicks,
                "scroll_amount": scroll,
                "success": ok,
                "width": width,
                "height": height,
                "observe": _wants_observe(kwargs.get("observe")),
            }
        )
        return ToolResult(success=ok, output=output, error=error, details=details)


def _parse_point(raw_x: Any, raw_y: Any) -> tuple[int, int] | None:
    try:
        x = int(raw_x)
        y = int(raw_y)
    except (TypeError, ValueError):
        return None
    return x, y


def _parse_int(value: Any, default: int) -> int | None:
    if value is None or str(value).strip() == "":
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _wants_observe(value: Any) -> bool:
    text = str(value or "true").strip().lower()
    return text not in {"0", "false", "no", "off"}


__all__ = ["MouseActionTool", "TOOL_NAME"]
