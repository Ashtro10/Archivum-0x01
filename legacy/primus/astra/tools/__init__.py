"""Tool Interface.

B-016: catalog + security-gated invocation boundary (ADR-0015).
B-017: ``read_text`` (ADR-0016). B-018: ``read_project`` (ADR-0017).
Architectural gate, not an OS sandbox.
"""

from __future__ import annotations

from astra.tools.contracts import (
    ExecutionDecision,
    InvocationStatus,
    SecurityPolicyPort,
    Tool,
    ToolError,
    ToolInvoker,
    ToolInvocationRecord,
    ToolInvocationRequest,
    ToolRegistry,
    ToolResult,
    ToolSpec,
)
from astra.tools.allowlist import AGENT_EXECUTION_ALLOWLIST, PRODUCTION_EXECUTION_ALLOWLIST
from astra.tools.gate import GatedToolInvoker, TOOL_NETWORK_SENTINEL
from astra.tools.guard import GuardedTool
from astra.tools.read_project import ReadProjectTool
from astra.tools.read_text import (
    MAX_BYTES,
    TOOL_NAME,
    ReadTextTool,
    read_roots_from_settings,
)
from astra.tools.registry import InMemoryToolRegistry
from astra.tools.run_python import RunPythonTool
from astra.tools.search_files import SearchFilesTool
from astra.tools.write_text import WriteTextTool
from astra.tools.launch_application import LaunchApplicationTool
from astra.tools.inspect_process import InspectProcessTool
from astra.tools.capture_screen import CaptureScreenTool
from astra.tools.inspect_active_window import InspectActiveWindowTool
from astra.tools.mouse_action import MouseActionTool
from astra.tools.keyboard_action import KeyboardActionTool

__all__ = [
    "ToolError",
    "ToolSpec",
    "ToolResult",
    "Tool",
    "ToolRegistry",
    "InvocationStatus",
    "ToolInvocationRequest",
    "ExecutionDecision",
    "ToolInvocationRecord",
    "SecurityPolicyPort",
    "ToolInvoker",
    "InMemoryToolRegistry",
    "GuardedTool",
    "GatedToolInvoker",
    "TOOL_NETWORK_SENTINEL",
    "ReadTextTool",
    "ReadProjectTool",
    "SearchFilesTool",
    "WriteTextTool",
    "RunPythonTool",
    "LaunchApplicationTool",
    "InspectProcessTool",
    "CaptureScreenTool",
    "InspectActiveWindowTool",
    "MouseActionTool",
    "KeyboardActionTool",
    "TOOL_NAME",
    "PRODUCTION_EXECUTION_ALLOWLIST",
    "AGENT_EXECUTION_ALLOWLIST",
    "MAX_BYTES",
    "read_roots_from_settings",
]

__all__ = [
    "ToolError",
    "ToolSpec",
    "ToolResult",
    "Tool",
    "ToolRegistry",
    "InvocationStatus",
    "ToolInvocationRequest",
    "ExecutionDecision",
    "ToolInvocationRecord",
    "SecurityPolicyPort",
    "ToolInvoker",
    "InMemoryToolRegistry",
    "GuardedTool",
    "GatedToolInvoker",
    "TOOL_NETWORK_SENTINEL",
    "ReadTextTool",
    "ReadProjectTool",
    "SearchFilesTool",
    "WriteTextTool",
    "RunPythonTool",
    "LaunchApplicationTool",
    "InspectProcessTool",
    "CaptureScreenTool",
    "InspectActiveWindowTool",
    "MouseActionTool",
    "KeyboardActionTool",
    "TOOL_NAME",
    "PRODUCTION_EXECUTION_ALLOWLIST",
    "AGENT_EXECUTION_ALLOWLIST",
    "MAX_BYTES",
    "read_roots_from_settings",
]
