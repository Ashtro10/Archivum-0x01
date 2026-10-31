"""Controller-owned plugin registry (B-034, ADR-0033).

Not an Agent. Not a Tool. Does not execute arbitrary plugin code.
Does not grant Security, Process, Git, Deployment, or Tool authority.
"""

from __future__ import annotations

import tomllib
from pathlib import Path
from typing import Any, Callable, Sequence

from astra.logging import get_logger
from astra.plugins.contracts import (
    INITIALIZABLE_PLUGIN_CAPABILITIES,
    KIND_BUILTIN,
    KIND_DESCRIPTOR,
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
)
from astra.plugins.descriptor import PLUGIN_ID_RE, validate_descriptor
from astra.plugins.paths import canonical, is_link, is_non_relative_path, is_under

_LOGGER = get_logger(__name__)

DESCRIPTOR_FILENAME = "plugin.toml"
MAX_DESCRIPTOR_BYTES = 8192


class BuiltinPlugin(Plugin):
    """Trusted no-op plugin. Holds metadata only. No services."""

    def __init__(self, descriptor: PluginDescriptor) -> None:
        self._descriptor = descriptor
        self._metadata = PluginMetadata(
            name=descriptor.plugin_id,
            version=descriptor.version,
            description=descriptor.description,
        )

    @property
    def metadata(self) -> PluginMetadata:
        return self._metadata

    def activate(self) -> None:
        return None

    def deactivate(self) -> None:
        return None


class LocalPluginRegistry(PluginManager):
    """Explicit registration plus one-level static descriptor discovery."""

    def __init__(
        self,
        *,
        plugin_root: Path | str | None = None,
        project_root: Path | str | None = None,
        scratch_root: Path | str | None = None,
    ) -> None:
        self._plugin_root = canonical(plugin_root) if plugin_root is not None else None
        self._project_root = canonical(project_root) if project_root is not None else None
        self._scratch_root = canonical(scratch_root) if scratch_root is not None else None
        self._records: dict[str, PluginRecord] = {}
        self._instances: dict[str, Plugin] = {}
        self._factories: dict[str, Callable[[], Plugin]] = {}

    def register(
        self,
        descriptor: PluginDescriptor,
        *,
        factory: Callable[[], Plugin] | None = None,
    ) -> PluginRecord:
        """Validate and store a descriptor as disabled. Duplicate IDs fail closed."""
        if not isinstance(descriptor, PluginDescriptor):
            raise PluginError("plugin descriptor malformed")
        checked = validate_descriptor(
            {
                "plugin_id": descriptor.plugin_id,
                "name": descriptor.name,
                "version": descriptor.version,
                "description": descriptor.description,
                "capabilities": list(descriptor.capabilities),
                "kind": descriptor.kind,
                "entrypoint": descriptor.entrypoint,
                "source_path": descriptor.source_path,
                "trusted": descriptor.trusted,
            }
        )
        if checked.plugin_id in self._records:
            raise PluginError(f"duplicate plugin id {checked.plugin_id}")
        if factory is not None and checked.kind != KIND_BUILTIN:
            raise PluginError("factory only allowed for builtin plugins")
        if checked.source_path:
            source_err = self._source_denied(checked.source_path)
            if source_err:
                raise PluginError(source_err)
        trusted = checked.kind == KIND_BUILTIN
        record = PluginRecord(
            descriptor=PluginDescriptor(
                plugin_id=checked.plugin_id,
                name=checked.name,
                version=checked.version,
                description=checked.description,
                capabilities=checked.capabilities,
                kind=checked.kind,
                entrypoint=checked.entrypoint,
                source_path=checked.source_path,
                trusted=trusted,
            ),
            state=STATE_DISABLED,
            reason="registered; disabled",
            trusted=trusted,
        )
        self._records[checked.plugin_id] = record
        if factory is not None:
            self._factories[checked.plugin_id] = factory
        return record

    def enable(self, plugin_id: str) -> PluginRecord:
        record = self._require(plugin_id)
        if record.state == STATE_INVALID:
            raise PluginError("invalid plugin cannot be enabled")
        if record.state == STATE_UNAVAILABLE:
            raise PluginError("unavailable plugin cannot be enabled")
        updated = PluginRecord(
            descriptor=record.descriptor,
            state=STATE_ENABLED,
            reason="enabled; not initialized",
            trusted=record.trusted,
        )
        self._records[plugin_id] = updated
        return updated

    def disable(self, plugin_id: str) -> PluginRecord:
        record = self._require(plugin_id)
        self._drop_instance(plugin_id)
        updated = PluginRecord(
            descriptor=record.descriptor,
            state=STATE_DISABLED,
            reason="disabled",
            trusted=record.trusted,
        )
        self._records[plugin_id] = updated
        return updated

    def get(self, plugin_id: str) -> PluginRecord | None:
        ident = str(plugin_id or "").strip()
        if not PLUGIN_ID_RE.fullmatch(ident):
            return None
        return self._records.get(ident)

    def list_plugins(self) -> tuple[PluginRecord, ...]:
        return tuple(self._records[key] for key in sorted(self._records))

    def initialize(self, plugin_id: str) -> PluginRecord:
        """Activate a trusted builtin only. External execution stays disabled."""
        record = self._require(plugin_id)
        if record.state == STATE_DISABLED:
            raise PluginError("disabled plugin cannot be initialized")
        if record.state == STATE_INVALID:
            raise PluginError("invalid plugin cannot be initialized")
        if not record.trusted or record.descriptor.kind != KIND_BUILTIN:
            failed = PluginRecord(
                descriptor=record.descriptor,
                state=STATE_INITIALIZATION_FAILED,
                reason="external plugin execution disabled",
                trusted=False,
            )
            self._records[plugin_id] = failed
            self._drop_instance(plugin_id)
            return failed
        extra = set(record.descriptor.capabilities) - INITIALIZABLE_PLUGIN_CAPABILITIES
        if extra:
            failed = PluginRecord(
                descriptor=record.descriptor,
                state=STATE_INITIALIZATION_FAILED,
                reason="plugin capabilities are not executable in B-034",
                trusted=record.trusted,
            )
            self._records[plugin_id] = failed
            self._drop_instance(plugin_id)
            return failed
        factory = self._factories.get(plugin_id)
        try:
            instance = factory() if factory is not None else BuiltinPlugin(record.descriptor)
            instance.activate()
        except Exception as exc:  # noqa: BLE001
            self._drop_instance(plugin_id)
            failed = PluginRecord(
                descriptor=record.descriptor,
                state=STATE_INITIALIZATION_FAILED,
                reason=f"plugin initialization failed: {exc}",
                trusted=record.trusted,
            )
            self._records[plugin_id] = failed
            return failed
        self._instances[plugin_id] = instance
        operational = PluginRecord(
            descriptor=record.descriptor,
            state=STATE_OPERATIONAL,
            reason="initialized",
            trusted=True,
        )
        self._records[plugin_id] = operational
        return operational

    def scan_descriptors(self, directory: Path | str) -> tuple[PluginDescriptor, ...]:
        """Read plugin.toml one level deep. Never imports Python."""
        root_err = self._discovery_denied(directory)
        if root_err:
            _LOGGER.debug("plugin discovery refused: %s", root_err)
            return ()
        root = canonical(directory)
        if root is None or not root.is_dir():
            return ()
        found: list[PluginDescriptor] = []
        try:
            children = sorted(root.iterdir(), key=lambda item: item.name)
        except OSError as exc:
            _LOGGER.debug("plugin root unreadable: %s", exc)
            return ()
        for child in children:
            if not child.is_dir() or is_link(child):
                continue
            if not PLUGIN_ID_RE.fullmatch(child.name):
                continue
            spec = child / DESCRIPTOR_FILENAME
            if is_link(spec) or not spec.is_file():
                continue
            parsed = self._read_descriptor_file(spec, child.name)
            if parsed is not None:
                found.append(parsed)
        return tuple(found)

    def discover(self, directory: Path) -> Sequence[PluginMetadata]:
        """List static metadata. Does not register, enable, or initialize."""
        return tuple(_to_metadata(item) for item in self.scan_descriptors(directory))

    def load(self, name: str) -> Plugin:
        """Initialize only after explicit enable + builtin trust."""
        ident = str(name or "").strip()
        record = self.get(ident)
        if record is None:
            raise PluginError(f"plugin {ident!r} is not registered")
        if record.state == STATE_DISABLED:
            raise PluginError("disabled plugin cannot be loaded")
        initialized = self.initialize(ident)
        if not initialized.operational:
            raise PluginError(initialized.reason or "plugin not operational")
        instance = self._instances.get(ident)
        if instance is None:
            raise PluginError("plugin instance missing after initialize")
        return instance

    def unload(self, name: str) -> bool:
        ident = str(name or "").strip()
        if ident not in self._records:
            return False
        self.disable(ident)
        return True

    def loaded(self) -> Sequence[PluginMetadata]:
        items: list[PluginMetadata] = []
        for plugin_id, instance in self._instances.items():
            record = self._records.get(plugin_id)
            if record is not None and record.operational:
                items.append(instance.metadata)
        return tuple(items)

    def _require(self, plugin_id: str) -> PluginRecord:
        ident = str(plugin_id or "").strip()
        if not PLUGIN_ID_RE.fullmatch(ident):
            raise PluginError("plugin_id rejected")
        record = self._records.get(ident)
        if record is None:
            raise PluginError(f"plugin {ident!r} is not registered")
        return record

    def _drop_instance(self, plugin_id: str) -> None:
        instance = self._instances.pop(plugin_id, None)
        if instance is None:
            return
        try:
            instance.deactivate()
        except Exception:  # noqa: BLE001
            return

    def _discovery_denied(self, directory: Path | str) -> str:
        requested = canonical(directory)
        if requested is None:
            return "plugin directory unresolvable"
        if self._plugin_root is None:
            return "plugin_root is not configured"
        if requested != self._plugin_root:
            return "plugin directory is not the configured plugin_root"
        if is_link(requested):
            return "plugin_root is a symlink or junction"
        if self._scratch_root is not None and (
            requested == self._scratch_root or is_under(requested, self._scratch_root)
        ):
            return "scratch cannot be a plugin_root"
        if any(part.lower() == ".git" for part in requested.parts):
            return ".git cannot be a plugin_root"
        return ""

    def _source_denied(self, source_path: str) -> str:
        raw = Path(source_path)
        if ".." in raw.parts:
            return "plugin source path traversal rejected"
        if is_non_relative_path(raw) and self._plugin_root is None:
            return "absolute plugin path rejected"
        text = str(source_path).strip()
        if text.startswith("\\\\") or text.startswith("//"):
            return "UNC plugin path rejected"
        source = canonical(source_path)
        if source is None:
            return "plugin source path unresolvable"
        if is_link(source):
            return "plugin source is a symlink or junction"
        if self._scratch_root is not None and (
            source == self._scratch_root or is_under(source, self._scratch_root)
        ):
            return "scratch cannot host a plugin"
        if any(part.lower() == ".git" for part in source.parts):
            return ".git cannot host a plugin"
        if self._plugin_root is None:
            return "plugin source requires a configured plugin_root"
        if source != self._plugin_root and not is_under(source, self._plugin_root):
            return "plugin source is outside plugin_root"
        return ""

    def _read_descriptor_file(
        self, spec: Path, expected_id: str
    ) -> PluginDescriptor | None:
        try:
            size = spec.stat().st_size
        except OSError:
            return None
        if size > MAX_DESCRIPTOR_BYTES:
            _LOGGER.debug("plugin descriptor too large: %s", spec)
            return None
        try:
            with spec.open("rb") as handle:
                raw = tomllib.load(handle)
        except Exception as exc:  # noqa: BLE001
            _LOGGER.debug("plugin descriptor unreadable %s: %s", spec, exc)
            return None
        if not isinstance(raw, dict):
            return None
        raw = dict(raw)
        raw.setdefault("plugin_id", expected_id)
        raw["kind"] = KIND_DESCRIPTOR
        raw["trusted"] = False
        raw["source_path"] = str(spec)
        try:
            descriptor = validate_descriptor(raw)
        except PluginError as exc:
            _LOGGER.debug("plugin descriptor invalid %s: %s", spec, exc)
            return None
        if descriptor.plugin_id != expected_id:
            _LOGGER.debug("plugin id does not match directory %s", spec)
            return None
        return descriptor


def _to_metadata(descriptor: PluginDescriptor) -> PluginMetadata:
    return PluginMetadata(
        name=descriptor.plugin_id,
        version=descriptor.version,
        description=descriptor.description,
        requires_network=False,
        provides_tools=(),
    )


__all__ = ["BuiltinPlugin", "LocalPluginRegistry"]
