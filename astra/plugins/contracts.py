"""Plugin Manager contracts.

Plugins are how Astra gains capabilities without its core being rewritten.
They are loaded from local directories only: there is no remote registry, and
none may be added, because that would make the system depend on a service.

``Plugin`` / ``PluginManager`` remain abstract. ``PluginDescriptor`` is
declarative metadata only — never executable. Capability names describe
intent; they do not grant Security, Tool, Process, Git, or Approval power.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

PLUGIN_CAP_METADATA = "metadata"
PLUGIN_CAP_READ_ONLY = "read_only"
PLUGIN_CAP_AGENT = "agent"
PLUGIN_CAP_TOOL = "tool"
PLUGIN_CAP_PROCESS = "process"
PLUGIN_CAP_TEMPORARY_WRITE = "temporary_write"
PLUGIN_CAP_PERMANENT_CHANGE = "permanent_change"
PLUGIN_CAP_GIT = "git"

SUPPORTED_PLUGIN_CAPABILITIES = frozenset(
    {
        PLUGIN_CAP_METADATA,
        PLUGIN_CAP_READ_ONLY,
        PLUGIN_CAP_AGENT,
        PLUGIN_CAP_TOOL,
        PLUGIN_CAP_PROCESS,
        PLUGIN_CAP_TEMPORARY_WRITE,
        PLUGIN_CAP_PERMANENT_CHANGE,
        PLUGIN_CAP_GIT,
    }
)
INITIALIZABLE_PLUGIN_CAPABILITIES = frozenset(
    {PLUGIN_CAP_METADATA, PLUGIN_CAP_READ_ONLY}
)

KIND_BUILTIN = "builtin"
KIND_DESCRIPTOR = "descriptor"
SUPPORTED_PLUGIN_KINDS = frozenset({KIND_BUILTIN, KIND_DESCRIPTOR})

STATE_UNAVAILABLE = "unavailable"
STATE_INVALID = "invalid"
STATE_DISABLED = "disabled"
STATE_ENABLED = "enabled"
STATE_INITIALIZATION_FAILED = "initialization_failed"
STATE_OPERATIONAL = "operational"


class PluginError(RuntimeError):
    """Raised when a plugin cannot be loaded or misbehaves.

    A failing plugin must never take the system down with it.
    """


@dataclass(frozen=True, slots=True)
class PluginMetadata:
    """Identity and requirements of a plugin.

    Attributes:
        name: Unique name.
        version: Plugin version.
        description: What it does.
        requires_network: Whether it needs outbound access. Plugins that do
            are optional by definition and must degrade when offline.
        provides_tools: Names of the tools it registers. Empty in B-034:
            a ``tool`` capability declaration is not a Tool grant.
    """

    name: str
    version: str
    description: str = ""
    requires_network: bool = False
    provides_tools: tuple[str, ...] = field(default=())


@dataclass(frozen=True, slots=True)
class PluginDescriptor:
    """Static plugin identity. Not executable. Not authority."""

    plugin_id: str
    name: str
    version: str
    description: str = ""
    capabilities: tuple[str, ...] = field(default=())
    kind: str = KIND_DESCRIPTOR
    entrypoint: str = ""
    source_path: str = ""
    trusted: bool = False


@dataclass(frozen=True, slots=True)
class PluginRecord:
    """Registered plugin state. ``operational`` only after successful init."""

    descriptor: PluginDescriptor
    state: str
    reason: str = ""
    trusted: bool = False

    @property
    def plugin_id(self) -> str:
        return self.descriptor.plugin_id

    @property
    def operational(self) -> bool:
        return self.state == STATE_OPERATIONAL

    @property
    def enabled(self) -> bool:
        return self.state in {STATE_ENABLED, STATE_OPERATIONAL}


class Plugin(ABC):
    """A loadable capability."""

    @property
    @abstractmethod
    def metadata(self) -> PluginMetadata:
        """Describe this plugin. Must not perform work."""
        raise NotImplementedError

    @abstractmethod
    def activate(self) -> None:
        """Prepare the plugin for use.

        Must be safe to call once per load and must not perform network I/O
        unless the plugin declares ``requires_network``.
        """
        raise NotImplementedError

    @abstractmethod
    def deactivate(self) -> None:
        """Release anything the plugin holds. Must be safe to call twice."""
        raise NotImplementedError


class PluginManager(ABC):
    """Discovers, loads, and unloads plugins."""

    @abstractmethod
    def discover(self, directory: Path) -> Sequence[PluginMetadata]:
        """List plugins available in a local directory without loading them."""
        raise NotImplementedError

    @abstractmethod
    def load(self, name: str) -> Plugin:
        """Load and activate a plugin by name.

        Raises:
            PluginError: if the plugin is missing, invalid, or fails to
                activate.
        """
        raise NotImplementedError

    @abstractmethod
    def unload(self, name: str) -> bool:
        """Deactivate and drop a plugin. Returns ``True`` if it was loaded."""
        raise NotImplementedError

    @abstractmethod
    def loaded(self) -> Sequence[PluginMetadata]:
        """Return metadata for every currently loaded plugin."""
        raise NotImplementedError


__all__ = [
    "INITIALIZABLE_PLUGIN_CAPABILITIES",
    "KIND_BUILTIN",
    "KIND_DESCRIPTOR",
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
    "SUPPORTED_PLUGIN_KINDS",
]
