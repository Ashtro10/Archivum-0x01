"""Controlled plugin registry tests (B-034, ADR-0033).

Metadata is not authority. Disabled plugins do not execute.
"""

from __future__ import annotations

import ast
import dataclasses
import os
import tempfile
import unittest
from pathlib import Path

from astra.deployment.contracts import ApprovalRecord
from astra.plugins.contracts import (
    KIND_BUILTIN,
    KIND_DESCRIPTOR,
    PLUGIN_CAP_GIT,
    PLUGIN_CAP_METADATA,
    PLUGIN_CAP_PERMANENT_CHANGE,
    PLUGIN_CAP_PROCESS,
    PLUGIN_CAP_TOOL,
    Plugin,
    PluginDescriptor,
    PluginError,
    PluginManager,
    PluginMetadata,
    STATE_DISABLED,
    STATE_ENABLED,
    STATE_INITIALIZATION_FAILED,
    STATE_OPERATIONAL,
)
from astra.plugins.descriptor import validate_descriptor
from astra.plugins.registry import BuiltinPlugin, LocalPluginRegistry
from astra.state import ApplicationState
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST
from astra.tools.contracts import ToolSpec


def _desc(**kwargs: object) -> PluginDescriptor:
    fields = dict(
        plugin_id="sample",
        name="Sample",
        version="1.0.0",
        description="probe",
        capabilities=(PLUGIN_CAP_METADATA,),
        kind=KIND_BUILTIN,
        trusted=True,
    )
    fields.update(kwargs)
    return PluginDescriptor(**fields)  # type: ignore[arg-type]


class _RaisingPlugin(Plugin):
    def __init__(self) -> None:
        self._metadata = PluginMetadata(name="boom", version="1.0.0")

    @property
    def metadata(self) -> PluginMetadata:
        return self._metadata

    def activate(self) -> None:
        raise RuntimeError("init boom")

    def deactivate(self) -> None:
        return None


class PluginDescriptorTest(unittest.TestCase):
    def test_valid_descriptor_registers(self) -> None:
        registry = LocalPluginRegistry()
        record = registry.register(_desc())
        self.assertEqual("sample", record.plugin_id)
        self.assertEqual(STATE_DISABLED, record.state)
        self.assertFalse(record.operational)

    def test_duplicate_id_rejected(self) -> None:
        registry = LocalPluginRegistry()
        registry.register(_desc())
        with self.assertRaises(PluginError) as caught:
            registry.register(_desc(name="Other"))
        self.assertIn("duplicate", str(caught.exception))
        self.assertEqual("Sample", registry.get("sample").descriptor.name)  # type: ignore[union-attr]

    def test_malformed_descriptor_rejected(self) -> None:
        with self.assertRaises(PluginError):
            validate_descriptor("not-a-table")
        with self.assertRaises(PluginError):
            validate_descriptor({"plugin_id": "sample"})

    def test_invalid_version_rejected(self) -> None:
        with self.assertRaises(PluginError):
            validate_descriptor(
                {
                    "plugin_id": "sample",
                    "name": "Sample",
                    "version": "v1",
                }
            )

    def test_unknown_capability_rejected(self) -> None:
        with self.assertRaises(PluginError):
            validate_descriptor(
                {
                    "plugin_id": "sample",
                    "name": "Sample",
                    "version": "1.0.0",
                    "capabilities": ["laser_shark"],
                }
            )

    def test_long_metadata_rejected(self) -> None:
        with self.assertRaises(PluginError):
            validate_descriptor(
                {
                    "plugin_id": "sample",
                    "name": "Sample",
                    "version": "1.0.0",
                    "description": "x" * 513,
                }
            )

    def test_plugin_id_cannot_escape_namespace(self) -> None:
        for ident in ("../plugin", "foo/bar", "foo\\bar", "Astra", "a" * 40):
            with self.subTest(ident=ident):
                with self.assertRaises(PluginError):
                    validate_descriptor(
                        {
                            "plugin_id": ident,
                            "name": "Sample",
                            "version": "1.0.0",
                        }
                    )


class PluginPathTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        root = Path(self._tmp.name)
        self.plugin_root = root / "plugins"
        self.project = root / "project"
        self.scratch = root / "scratch"
        self.plugin_root.mkdir()
        self.project.mkdir()
        self.scratch.mkdir()
        self.registry = LocalPluginRegistry(
            plugin_root=self.plugin_root,
            project_root=self.project,
            scratch_root=self.scratch,
        )

    def test_path_traversal_rejected(self) -> None:
        with self.assertRaises(PluginError):
            self.registry.register(
                _desc(kind=KIND_DESCRIPTOR, trusted=False, source_path="../plugin")
            )
        with self.assertRaises(PluginError):
            self.registry.register(
                _desc(
                    plugin_id="outer",
                    kind=KIND_DESCRIPTOR,
                    trusted=False,
                    source_path=str(self.project / ".." / "outside"),
                )
            )

    def test_absolute_path_outside_root_rejected(self) -> None:
        with self.assertRaises(PluginError):
            self.registry.register(
                _desc(
                    plugin_id="absone",
                    kind=KIND_DESCRIPTOR,
                    trusted=False,
                    source_path=str(self.project / "plugin"),
                )
            )

    def test_project_root_plugin_rejected_when_not_plugin_root(self) -> None:
        dest = self.project / "plugin"
        dest.mkdir()
        with self.assertRaises(PluginError):
            self.registry.register(
                _desc(
                    plugin_id="projplug",
                    kind=KIND_DESCRIPTOR,
                    trusted=False,
                    source_path=str(dest),
                )
            )

    def test_scratch_plugin_rejected(self) -> None:
        dest = self.scratch / "plugin"
        dest.mkdir()
        with self.assertRaises(PluginError):
            self.registry.register(
                _desc(
                    plugin_id="scratchy",
                    kind=KIND_DESCRIPTOR,
                    trusted=False,
                    source_path=str(dest),
                )
            )

    def test_git_plugin_rejected(self) -> None:
        git_dir = self.plugin_root / ".git" / "plugin"
        git_dir.mkdir(parents=True)
        with self.assertRaises(PluginError):
            self.registry.register(
                _desc(
                    plugin_id="githost",
                    kind=KIND_DESCRIPTOR,
                    trusted=False,
                    source_path=str(git_dir),
                )
            )

    def test_discover_wrong_directory_is_empty(self) -> None:
        self.assertEqual((), self.registry.discover(self.project))
        self.assertEqual((), self.registry.discover(self.scratch))
        self.assertEqual((), self.registry.scan_descriptors(self.project / ".."))

    def test_discover_reads_toml_without_importing(self) -> None:
        folder = self.plugin_root / "listed"
        folder.mkdir()
        (folder / "plugin.toml").write_text(
            'plugin_id = "listed"\nname = "Listed"\nversion = "1.2.3"\n'
            'description = "static"\ncapabilities = ["metadata"]\n',
            encoding="utf-8",
        )
        (folder / "evil.py").write_text("raise SystemExit('imported')\n", encoding="utf-8")
        found = self.registry.discover(self.plugin_root)
        self.assertEqual(1, len(found))
        self.assertEqual("listed", found[0].name)
        self.assertEqual((), found[0].provides_tools)
        self.assertEqual((), self.registry.list_plugins())

    def test_malformed_toml_does_not_register(self) -> None:
        bad = self.plugin_root / "broken"
        bad.mkdir()
        (bad / "plugin.toml").write_text("plugin_id = [\n", encoding="utf-8")
        good = self.plugin_root / "listed"
        good.mkdir()
        (good / "plugin.toml").write_text(
            'plugin_id = "listed"\nname = "Listed"\nversion = "1.0.0"\n',
            encoding="utf-8",
        )
        found = self.registry.scan_descriptors(self.plugin_root)
        self.assertEqual(("listed",), tuple(item.plugin_id for item in found))

    def test_symlink_escape_rejected(self) -> None:
        outside = Path(self._tmp.name) / "outside"
        outside.mkdir()
        link = self.plugin_root / "linked"
        try:
            os.symlink(outside, link, target_is_directory=True)
        except OSError:
            self.skipTest("symlink privilege unavailable")
        self.assertEqual((), self.registry.scan_descriptors(self.plugin_root))
        with self.assertRaises(PluginError):
            self.registry.register(
                _desc(
                    plugin_id="linked",
                    kind=KIND_DESCRIPTOR,
                    trusted=False,
                    source_path=str(link),
                )
            )


class PluginLifecycleTest(unittest.TestCase):
    def test_disabled_plugin_does_not_execute(self) -> None:
        ran = {"n": 0}

        def _factory() -> Plugin:
            ran["n"] += 1
            return BuiltinPlugin(_desc(plugin_id="sleepy"))

        registry = LocalPluginRegistry()
        registry.register(_desc(plugin_id="sleepy"), factory=_factory)
        self.assertEqual(0, ran["n"])
        self.assertEqual((), registry.loaded())

    def test_enable_disable_is_deterministic(self) -> None:
        registry = LocalPluginRegistry()
        registry.register(_desc())
        enabled = registry.enable("sample")
        self.assertEqual(STATE_ENABLED, enabled.state)
        self.assertTrue(enabled.enabled)
        disabled = registry.disable("sample")
        self.assertEqual(STATE_DISABLED, disabled.state)
        self.assertFalse(disabled.enabled)

    def test_initialize_builtin_metadata_only(self) -> None:
        registry = LocalPluginRegistry()
        registry.register(_desc())
        registry.enable("sample")
        record = registry.initialize("sample")
        self.assertEqual(STATE_OPERATIONAL, record.state)
        loaded = registry.load("sample")
        self.assertIsInstance(loaded, BuiltinPlugin)

    def test_external_execution_disabled(self) -> None:
        registry = LocalPluginRegistry()
        registry.register(
            _desc(kind=KIND_DESCRIPTOR, trusted=False, plugin_id="extone")
        )
        registry.enable("extone")
        record = registry.initialize("extone")
        self.assertEqual(STATE_INITIALIZATION_FAILED, record.state)
        self.assertIn("external plugin execution disabled", record.reason)
        self.assertEqual((), registry.loaded())

    def test_failed_init_does_not_leave_instance(self) -> None:
        registry = LocalPluginRegistry()
        registry.register(_desc(plugin_id="boom"), factory=_RaisingPlugin)
        registry.enable("boom")
        record = registry.initialize("boom")
        self.assertEqual(STATE_INITIALIZATION_FAILED, record.state)
        self.assertEqual((), registry.loaded())
        self.assertIsNone(getattr(registry, "_instances").get("boom"))

    def test_one_bad_plugin_does_not_corrupt_other(self) -> None:
        registry = LocalPluginRegistry()
        registry.register(_desc(plugin_id="good"))
        registry.register(_desc(plugin_id="boom"), factory=_RaisingPlugin)
        registry.enable("good")
        registry.enable("boom")
        registry.initialize("boom")
        good = registry.initialize("good")
        self.assertEqual(STATE_OPERATIONAL, good.state)
        self.assertEqual(STATE_INITIALIZATION_FAILED, registry.get("boom").state)  # type: ignore[union-attr]

    def test_unsupported_capability_does_not_initialize(self) -> None:
        registry = LocalPluginRegistry()
        registry.register(
            _desc(plugin_id="proc", capabilities=(PLUGIN_CAP_PROCESS,))
        )
        registry.enable("proc")
        record = registry.initialize("proc")
        self.assertEqual(STATE_INITIALIZATION_FAILED, record.state)
        self.assertFalse(record.operational)


class PluginIsolationTest(unittest.TestCase):
    def test_no_controller_process_git_or_deploy_defaults(self) -> None:
        registry = LocalPluginRegistry()
        self.assertFalse(hasattr(registry, "_controller"))
        self.assertFalse(hasattr(registry, "_process_runner"))
        self.assertFalse(hasattr(registry, "_git_store"))
        self.assertFalse(hasattr(registry, "_deployment"))
        src = Path(__file__).parent.parent / "astra" / "plugins"
        for py_file in src.glob("*.py"):
            text = py_file.read_text(encoding="utf-8")
            self.assertNotIn("ProcessRunner", text, py_file.name)
            self.assertNotIn("GitRunner", text, py_file.name)
            self.assertNotIn("LocalDeploymentAgent", text, py_file.name)
            self.assertNotIn("AstraController", text, py_file.name)
            self.assertNotIn("ApprovalRecord", text, py_file.name)
            tree = ast.parse(text)
            imported: list[str] = []
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    imported.extend(alias.name.split(".")[0] for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    imported.append(node.module.split(".")[0])
            self.assertNotIn("subprocess", imported, py_file.name)

    def test_allowlist_and_schema_unchanged(self) -> None:
        self.assertEqual(
            frozenset({"read_text", "read_project"}),
            PRODUCTION_EXECUTION_ALLOWLIST,
        )
        self.assertEqual(6, ApplicationState().schema_version)
        names = {field.name for field in dataclasses.fields(ToolSpec)}
        self.assertNotIn("plugin", names)
        self.assertNotIn("plugin_id", names)

    def test_registry_is_plugin_manager_not_agent(self) -> None:
        self.assertTrue(issubclass(LocalPluginRegistry, PluginManager))
        self.assertFalse(hasattr(LocalPluginRegistry, "name"))

    def test_cannot_create_approval_authority(self) -> None:
        registry = LocalPluginRegistry()
        registry.register(_desc())
        self.assertFalse(hasattr(registry, "approve"))
        self.assertFalse(callable(getattr(registry, "record_human_approval", None)))
        self.assertNotIsInstance(registry, ApprovalRecord)

    def test_cannot_expand_allowlist(self) -> None:
        before = frozenset(PRODUCTION_EXECUTION_ALLOWLIST)
        registry = LocalPluginRegistry()
        registry.register(
            _desc(plugin_id="tooler", capabilities=(PLUGIN_CAP_TOOL,))
        )
        registry.enable("tooler")
        registry.initialize("tooler")
        self.assertEqual(before, PRODUCTION_EXECUTION_ALLOWLIST)

    def test_git_and_deploy_capabilities_do_not_grant_access(self) -> None:
        registry = LocalPluginRegistry()
        registry.register(
            _desc(
                plugin_id="heavy",
                capabilities=(PLUGIN_CAP_GIT, PLUGIN_CAP_PERMANENT_CHANGE),
            )
        )
        registry.enable("heavy")
        record = registry.initialize("heavy")
        self.assertEqual(STATE_INITIALIZATION_FAILED, record.state)
        self.assertFalse(hasattr(registry, "git"))
        self.assertFalse(hasattr(registry, "commit_files"))


class PluginControllerTest(unittest.TestCase):
    def test_startup_survives_bad_plugin_and_does_not_auto_enable(self) -> None:
        from astra.configuration.settings import AstraSettings, PluginSettings
        from astra.core.astra_controller import AstraController

        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name) / "plugins"
        root.mkdir()
        broken = root / "broken"
        broken.mkdir()
        (broken / "plugin.toml").write_text("not toml", encoding="utf-8")
        listed = root / "listed"
        listed.mkdir()
        (listed / "plugin.toml").write_text(
            'plugin_id = "listed"\nname = "Listed"\nversion = "1.0.0"\n',
            encoding="utf-8",
        )
        settings = AstraSettings(
            plugins=PluginSettings(plugin_root=root, enabled_plugin_ids=())
        )
        ctrl = AstraController(settings)
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        record = ctrl.plugins.get("listed")
        self.assertIsNotNone(record)
        assert record is not None
        self.assertEqual(STATE_DISABLED, record.state)
        self.assertEqual((), ctrl.plugins.loaded())
        self.assertNotIn("plugins", ctrl.agent_names)
        state = ctrl.get_state()
        self.assertEqual(6, state.schema_version)
        plugins = next(m for m in state.modules if m.module == "astra.plugins")
        cap = next(c for c in plugins.capabilities if c.name == "plugin_registry")
        self.assertIn("not authority", cap.notes)


if __name__ == "__main__":
    unittest.main()
