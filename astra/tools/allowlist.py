"""Exact production execution allowlist (ADR-0016, ADR-0017).

Single source of truth. No automatic discovery.
"""

from __future__ import annotations

PRODUCTION_EXECUTION_ALLOWLIST = frozenset({"read_text", "read_project"})

# Agent loop only. Does not replace PRODUCTION_EXECUTION_ALLOWLIST.
AGENT_EXECUTION_ALLOWLIST = frozenset(
    {
        *PRODUCTION_EXECUTION_ALLOWLIST,
        "search_files",
        "write_text",
        "run_python",
        "launch_application",
        "inspect_process",
        "capture_screen",
        "inspect_active_window",
        "mouse_action",
        "keyboard_action",
    }
)

__all__ = ["AGENT_EXECUTION_ALLOWLIST", "PRODUCTION_EXECUTION_ALLOWLIST"]
