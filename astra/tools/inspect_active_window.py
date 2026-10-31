"""Gated read-only active-window inspection. Does not control the window."""

from __future__ import annotations

from types import MappingProxyType
from typing import Any, Callable, Mapping

from astra.tools.contracts import Tool, ToolResult, ToolSpec

TOOL_NAME = "inspect_active_window"
_ALLOWED_KEYS = frozenset()
InspectExecute = Callable[[], Mapping[str, Any]]


class InspectActiveWindowTool(Tool):
    """Report the foreground application and title. Read-only."""

    def __init__(self, inspect: InspectExecute | None = None) -> None:
        self._inspect = inspect

    def bind_inspect(self, inspect: InspectExecute | None) -> None:
        self._inspect = inspect

    @classmethod
    def from_settings(
        cls,
        settings: Any = None,
        inspect: InspectExecute | None = None,
    ) -> "InspectActiveWindowTool":
        del settings
        return cls(inspect=inspect)

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name=TOOL_NAME,
            description=(
                "Inspect the active window: application name, title, and "
                "basic identifiers. Read-only. Cannot focus, move, or close."
            ),
            parameters=MappingProxyType({}),
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
        if self._inspect is None:
            return ToolResult(
                success=False,
                error="active window inspection unavailable",
            )
        try:
            outcome = dict(self._inspect() or {})
        except Exception as exc:  # noqa: BLE001
            return ToolResult(
                success=False,
                error=f"active window inspection unavailable: {type(exc).__name__}: {exc}",
            )
        if not bool(outcome.get("available", False)):
            err = (
                str(outcome.get("error") or "").strip()
                or "active window inspection unavailable"
            )
            return ToolResult(success=False, error=err)
        application = str(outcome.get("application") or "")
        title = str(outcome.get("title") or "")
        pid = outcome.get("pid")
        output = (
            f"application={application or 'unknown'} "
            f"title={title or 'unknown'} "
            f"pid={pid if pid is not None else 'none'}"
        )
        details = MappingProxyType(
            {
                "application": application,
                "title": title,
                "pid": pid,
                "hwnd": outcome.get("hwnd"),
            }
        )
        return ToolResult(success=True, output=output, details=details)


__all__ = ["InspectActiveWindowTool", "TOOL_NAME"]
