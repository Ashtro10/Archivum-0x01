"""GuardedTool wrapper (ADR-0015 D4).

Architectural gate, not an OS sandbox. Inner ``invoke`` runs only when
``GatedToolInvoker`` has set the contextvar for this call.
"""

from __future__ import annotations

from contextvars import ContextVar
from typing import Any

from astra.tools.contracts import Tool, ToolResult, ToolSpec

# Default False. Only GatedToolInvoker may set True, and only after both keys.
GATE_TOKEN: ContextVar[bool] = ContextVar("astra_tool_gate_active", default=False)

_BYPASS_ERROR = (
    "invoke only through GatedToolInvoker; architectural gate, not OS sandbox"
)


class GuardedTool(Tool):
    """Wraps a :class:`Tool` so bare ``invoke()`` fails closed."""

    def __init__(self, inner: Tool) -> None:
        if isinstance(inner, GuardedTool):
            inner = inner._inner
        self._inner = inner

    @property
    def spec(self) -> ToolSpec:
        return self._inner.spec

    def invoke(self, **kwargs: Any) -> ToolResult:
        if not GATE_TOKEN.get():
            return ToolResult(success=False, error=_BYPASS_ERROR)
        return self._inner.invoke(**kwargs)


__all__ = ["GuardedTool", "GATE_TOKEN"]
