"""In-memory ToolRegistry (ADR-0015 D3). Catalog only. No invoke."""

from __future__ import annotations

from typing import Sequence

from astra.tools.contracts import Tool, ToolError, ToolRegistry, ToolSpec
from astra.tools.guard import GuardedTool


class InMemoryToolRegistry(ToolRegistry):
    """Process-local catalog. Duplicate names are errors."""

    def __init__(self) -> None:
        self._tools: dict[str, GuardedTool] = {}

    def register(self, tool: Tool) -> None:
        spec = tool.spec
        name = (spec.name or "").strip()
        if not name:
            raise ToolError("tool name must not be empty")
        if name in self._tools:
            raise ToolError(
                f"tool {name!r} is already registered; "
                "use a different name or remove the existing registration first"
            )
        wrapped = tool if isinstance(tool, GuardedTool) else GuardedTool(tool)
        self._tools[name] = wrapped

    def get(self, name: str) -> Tool | None:
        return self._tools.get(name)

    def specs(self) -> Sequence[ToolSpec]:
        return tuple(tool.spec for tool in self._tools.values())


__all__ = ["InMemoryToolRegistry"]
