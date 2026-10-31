"""Gated read-only desktop capture. No upload. No permanent store."""

from __future__ import annotations

from types import MappingProxyType
from typing import Any, Callable, Mapping

from astra.tools.contracts import Tool, ToolResult, ToolSpec

TOOL_NAME = "capture_screen"
VISION_UNAVAILABLE = "Vision capability unavailable."
_ALLOWED_KEYS = frozenset({"max_width", "max_height"})
_DEFAULT_MAX_WIDTH = 1280
_DEFAULT_MAX_HEIGHT = 720
_MAX_DIM = 4096
CaptureExecute = Callable[[int, int], Mapping[str, Any]]
DescribeExecute = Callable[[bytes, str], str]


class CaptureScreenTool(Tool):
    """Capture the local desktop. Read-only. Never writes a screenshot file."""

    def __init__(
        self,
        capture: CaptureExecute | None = None,
        describe: DescribeExecute | None = None,
        *,
        max_width: int = _DEFAULT_MAX_WIDTH,
        max_height: int = _DEFAULT_MAX_HEIGHT,
    ) -> None:
        self._capture = capture
        self._describe = describe
        self._max_width = max(1, int(max_width))
        self._max_height = max(1, int(max_height))

    def bind_capture(self, capture: CaptureExecute | None) -> None:
        self._capture = capture

    def bind_describe(self, describe: DescribeExecute | None) -> None:
        self._describe = describe

    @classmethod
    def from_settings(
        cls,
        settings: Any = None,
        capture: CaptureExecute | None = None,
        describe: DescribeExecute | None = None,
    ) -> "CaptureScreenTool":
        reasoning = getattr(settings, "reasoning", None) if settings is not None else None
        width = (
            getattr(reasoning, "screen_max_width", _DEFAULT_MAX_WIDTH)
            if reasoning is not None
            else _DEFAULT_MAX_WIDTH
        )
        height = (
            getattr(reasoning, "screen_max_height", _DEFAULT_MAX_HEIGHT)
            if reasoning is not None
            else _DEFAULT_MAX_HEIGHT
        )
        return cls(
            capture=capture,
            describe=describe,
            max_width=int(width or _DEFAULT_MAX_WIDTH),
            max_height=int(height or _DEFAULT_MAX_HEIGHT),
        )

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name=TOOL_NAME,
            description=(
                "Capture the current local desktop as an in-memory image with "
                "resolution and monitor metadata. Read-only. Does not store, "
                "upload, or send the image anywhere."
            ),
            parameters=MappingProxyType(
                {
                    "max_width": "Maximum capture width in pixels",
                    "max_height": "Maximum capture height in pixels",
                }
            ),
            requires_network=False,
            makes_permanent_change=False,
            inspects_environment=True,
        )

    def invoke(self, **kwargs: Any) -> ToolResult:
        extra = sorted(k for k in kwargs if k not in _ALLOWED_KEYS)
        if extra:
            return ToolResult(
                success=False,
                error=f"unknown arguments: {extra}; fail closed",
            )
        width = _parse_dim(kwargs.get("max_width"), self._max_width)
        height = _parse_dim(kwargs.get("max_height"), self._max_height)
        if width is None or height is None:
            return ToolResult(
                success=False,
                error="capture resolution is invalid; fail closed",
            )
        if self._capture is None:
            return ToolResult(
                success=False,
                error="screen capture unavailable",
            )
        try:
            outcome = dict(self._capture(width, height) or {})
        except Exception as exc:  # noqa: BLE001
            return ToolResult(
                success=False,
                error=f"screen capture unavailable: {type(exc).__name__}: {exc}",
            )
        if not bool(outcome.get("available", False)):
            err = str(outcome.get("error") or "").strip() or "screen capture unavailable"
            return ToolResult(success=False, error=err, details=_metadata(outcome, stored=False))
        vision = VISION_UNAVAILABLE
        image = outcome.get("image")
        payload = image if isinstance(image, (bytes, bytearray)) else b""
        if self._describe is not None and payload:
            try:
                vision = str(self._describe(bytes(payload), "") or "").strip() or VISION_UNAVAILABLE
            except Exception:
                vision = VISION_UNAVAILABLE
        meta = _metadata(outcome, stored=False)
        output = (
            f"resolution={meta['width']}x{meta['height']} "
            f"monitor={meta['monitor']} monitors={meta['monitors']} "
            f"timestamp={meta['timestamp']} stored=false {vision}"
        )
        return ToolResult(
            success=True,
            output=output,
            details=meta,
        )


def _parse_dim(value: Any, default: int) -> int | None:
    if value is None or str(value).strip() == "":
        return default
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    if parsed <= 0 or parsed > _MAX_DIM:
        return None
    return parsed


def _metadata(outcome: Mapping[str, Any], *, stored: bool) -> Mapping[str, Any]:
    return MappingProxyType(
        {
            "width": int(outcome.get("width") or 0),
            "height": int(outcome.get("height") or 0),
            "monitor": int(outcome.get("monitor") or 0),
            "monitors": int(outcome.get("monitors") or 0),
            "timestamp": str(outcome.get("timestamp") or ""),
            "stored": stored,
            "source_width": int(outcome.get("source_width") or outcome.get("width") or 0),
            "source_height": int(outcome.get("source_height") or outcome.get("height") or 0),
            "max_width": int(outcome.get("max_width") or 0),
            "max_height": int(outcome.get("max_height") or 0),
        }
    )


__all__ = ["CaptureScreenTool", "TOOL_NAME", "VISION_UNAVAILABLE"]
