"""Static plugin descriptor validation (B-034, ADR-0033).

Metadata only. Never imports plugin code. Never grants authority.
"""

from __future__ import annotations

import re
from typing import Any

from astra.plugins.contracts import (
    KIND_BUILTIN,
    KIND_DESCRIPTOR,
    PluginDescriptor,
    PluginError,
    SUPPORTED_PLUGIN_CAPABILITIES,
    SUPPORTED_PLUGIN_KINDS,
)

PLUGIN_ID_RE = re.compile(r"^[a-z][a-z0-9_]{1,31}$")
PLUGIN_VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")
PLUGIN_NAME_RE = re.compile(r"^[A-Za-z0-9 ._'-]{1,64}$")
ENTRYPOINT_RE = re.compile(r"^[a-z][a-z0-9_.]{0,63}$")
MAX_DESCRIPTION = 512
MAX_CAPABILITIES = 8


def validate_descriptor(raw: Any) -> PluginDescriptor:
    """Build a descriptor from a mapping. Raise PluginError on any defect."""
    if not isinstance(raw, dict):
        raise PluginError("plugin descriptor must be a table")
    unknown = set(raw) - {
        "plugin_id",
        "name",
        "version",
        "description",
        "capabilities",
        "kind",
        "entrypoint",
        "source_path",
        "trusted",
        "enabled",
    }
    if unknown:
        raise PluginError(
            f"unknown plugin descriptor key(s): {', '.join(sorted(unknown))}"
        )
    plugin_id = _text(raw.get("plugin_id"), "plugin_id")
    if not PLUGIN_ID_RE.fullmatch(plugin_id):
        raise PluginError("plugin_id rejected")
    name = _text(raw.get("name"), "name")
    if not PLUGIN_NAME_RE.fullmatch(name):
        raise PluginError("plugin name rejected")
    version = _text(raw.get("version"), "version")
    if not PLUGIN_VERSION_RE.fullmatch(version):
        raise PluginError("plugin version rejected")
    description = str(raw.get("description") or "")
    if len(description) > MAX_DESCRIPTION:
        raise PluginError("plugin description too long")
    if any(ch in description for ch in ("\0",)):
        raise PluginError("plugin description rejected")
    capabilities = _capabilities(raw.get("capabilities", ()))
    kind = str(raw.get("kind") or KIND_DESCRIPTOR).strip()
    if kind not in SUPPORTED_PLUGIN_KINDS:
        raise PluginError("plugin kind rejected")
    entrypoint = str(raw.get("entrypoint") or "").strip()
    if entrypoint and not ENTRYPOINT_RE.fullmatch(entrypoint):
        raise PluginError("plugin entrypoint rejected")
    source_path = str(raw.get("source_path") or "").strip()
    if any(ch in source_path for ch in ("\n", "\r", "\0")):
        raise PluginError("plugin source_path rejected")
    trusted_raw = raw.get("trusted", False)
    if not isinstance(trusted_raw, bool):
        raise PluginError("plugin trusted must be a bool")
    trusted = bool(trusted_raw) and kind == KIND_BUILTIN
    if raw.get("enabled") is not None and not isinstance(raw.get("enabled"), bool):
        raise PluginError("plugin enabled must be a bool")
    return PluginDescriptor(
        plugin_id=plugin_id,
        name=name,
        version=version,
        description=description,
        capabilities=capabilities,
        kind=kind,
        entrypoint=entrypoint,
        source_path=source_path,
        trusted=trusted,
    )


def _text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise PluginError(f"plugin {field} missing")
    text = value.strip()
    if any(ch in text for ch in ("\n", "\r", "\0", "/", "\\", "..")):
        raise PluginError(f"plugin {field} rejected")
    return text


def _capabilities(value: Any) -> tuple[str, ...]:
    if value is None:
        return ()
    if isinstance(value, str):
        raise PluginError("plugin capabilities must be a list")
    if not isinstance(value, (list, tuple)):
        raise PluginError("plugin capabilities must be a list")
    if len(value) > MAX_CAPABILITIES:
        raise PluginError("plugin capabilities too many")
    seen: list[str] = []
    for item in value:
        if not isinstance(item, str):
            raise PluginError("plugin capability rejected")
        cap = item.strip()
        if cap not in SUPPORTED_PLUGIN_CAPABILITIES:
            raise PluginError(f"unknown plugin capability {cap!r}")
        if cap not in seen:
            seen.append(cap)
    return tuple(seen)


__all__ = ["ENTRYPOINT_RE", "PLUGIN_ID_RE", "PLUGIN_VERSION_RE", "validate_descriptor"]
