"""Read-only process inspection. Never terminates a process."""

from __future__ import annotations

from types import MappingProxyType
from typing import Any, Callable, Mapping, Sequence

from astra.tools.contracts import Tool, ToolResult, ToolSpec

TOOL_NAME = "inspect_process"
_ALLOWED_KEYS = frozenset({"query", "pid"})
InspectExecute = Callable[[str, int | None], Sequence[Mapping[str, Any]]]
_BANNED = (
    "kill",
    "terminate",
    "taskkill",
    "admin",
    "install",
    "delete",
    "shutdown",
)


class InspectProcessTool(Tool):
    """List running processes by name or PID. Read-only."""

    def __init__(self, inspect: InspectExecute | None = None) -> None:
        self._inspect = inspect

    def bind_inspect(self, inspect: InspectExecute | None) -> None:
        self._inspect = inspect

    @classmethod
    def from_settings(
        cls,
        settings: Any = None,
        inspect: InspectExecute | None = None,
    ) -> "InspectProcessTool":
        del settings
        return cls(inspect=inspect)

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name=TOOL_NAME,
            description=(
                "Inspect running processes: names, PIDs, and basic status. "
                "Read-only. Cannot kill or change processes."
            ),
            parameters=MappingProxyType(
                {
                    "query": "Process name fragment to match",
                    "pid": "Exact process id",
                }
            ),
            requires_network=False,
            makes_permanent_change=False,
            inspects_processes=True,
        )

    def invoke(self, **kwargs: Any) -> ToolResult:
        extra = sorted(k for k in kwargs if k not in _ALLOWED_KEYS)
        if extra:
            return ToolResult(
                success=False,
                error=f"unknown arguments: {extra}; fail closed",
            )
        query = str(kwargs.get("query") or "").strip()
        raw_pid = kwargs.get("pid")
        pid: int | None = None
        if raw_pid is not None and str(raw_pid).strip() != "":
            try:
                pid = int(raw_pid)
            except (TypeError, ValueError):
                return ToolResult(success=False, error="pid is invalid; fail closed")
            if pid <= 0:
                return ToolResult(success=False, error="pid is invalid; fail closed")
        lowered = query.lower()
        for banned in _BANNED:
            if banned in lowered:
                return ToolResult(
                    success=False,
                    error=f"process inspection refuses {banned!r}; fail closed",
                )
        if not query and pid is None:
            return ToolResult(
                success=False,
                error="query or pid is required; fail closed",
            )
        if self._inspect is None:
            return ToolResult(
                success=False,
                error="process inspector not bound; fail closed",
            )
        try:
            rows = list(self._inspect(query, pid) or ())
        except Exception as exc:  # noqa: BLE001
            return ToolResult(
                success=False,
                error=f"inspector raised {type(exc).__name__}: {exc}",
            )
        running = [row for row in rows if bool(row.get("running", True))]
        names = ", ".join(
            f"{row.get('name', '?')} pid={row.get('pid', '?')} "
            f"status={row.get('status', 'unknown')}"
            for row in rows[:8]
        )
        output = (
            f"matches={len(rows)} running={len(running)}"
            + (f" {names}" if names else "")
        )
        details = MappingProxyType(
            {
                "query": query,
                "pid": pid,
                "matches": len(rows),
                "running": len(running) > 0,
                "processes": tuple(
                    {
                        "name": str(row.get("name", "")),
                        "pid": row.get("pid"),
                        "status": str(row.get("status", "")),
                        "running": bool(row.get("running", True)),
                    }
                    for row in rows[:32]
                ),
            }
        )
        return ToolResult(
            success=True,
            output=output,
            details=details,
        )


__all__ = ["InspectProcessTool", "TOOL_NAME"]
