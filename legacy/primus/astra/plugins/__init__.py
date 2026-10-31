"""Plugin Manager.

``LocalPluginRegistry`` is controller-owned registration infrastructure.
Metadata is not authority. External plugin execution is disabled.
"""

from __future__ import annotations

from astra.plugins.contracts import (
    INITIALIZABLE_PLUGIN_CAPABILITIES,
    KIND_BUILTIN,
    KIND_DESCRIPTOR,
    PLUGIN_CAP_AGENT,
    PLUGIN_CAP_GIT,
    PLUGIN_CAP_METADATA,
    PLUGIN_CAP_PERMANENT_CHANGE,
    PLUGIN_CAP_PROCESS,
    PLUGIN_CAP_READ_ONLY,
    PLUGIN_CAP_TEMPORARY_WRITE,
    PLUGIN_CAP_TOOL,
    Plugin,
    PluginDescriptor,
    PluginError,
    PluginManager,
    PluginMetadata,
    PluginRecord,
    STATE_DISABLED,
    STATE_ENABLED,
    STATE_INITIALIZATION_FAILED,
    STATE_INVALID,
    STATE_OPERATIONAL,
    STATE_UNAVAILABLE,
    SUPPORTED_PLUGIN_CAPABILITIES,
)
from astra.plugins.registry import BuiltinPlugin, LocalPluginRegistry

__all__ = [
    "BuiltinPlugin",
    "INITIALIZABLE_PLUGIN_CAPABILITIES",
    "KIND_BUILTIN",
    "KIND_DESCRIPTOR",
    "LocalPluginRegistry",
    "PLUGIN_CAP_AGENT",
    "PLUGIN_CAP_GIT",
    "PLUGIN_CAP_METADATA",
    "PLUGIN_CAP_PERMANENT_CHANGE",
    "PLUGIN_CAP_PROCESS",
    "PLUGIN_CAP_READ_ONLY",
    "PLUGIN_CAP_TEMPORARY_WRITE",
    "PLUGIN_CAP_TOOL",
    "Plugin",
    "PluginDescriptor",
    "PluginError",
    "PluginManager",
    "PluginMetadata",
    "PluginRecord",
    "STATE_DISABLED",
    "STATE_ENABLED",
    "STATE_INITIALIZATION_FAILED",
    "STATE_INVALID",
    "STATE_OPERATIONAL",
    "STATE_UNAVAILABLE",
    "SUPPORTED_PLUGIN_CAPABILITIES",
]
