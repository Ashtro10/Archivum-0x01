"""Packaging and Primus identity tests. Does not require Primus.exe."""

from __future__ import annotations

import ast
import unittest
from pathlib import Path
from unittest.mock import patch

from astra.configuration.paths import ENV_HOME, ENV_HOME_ALIAS, project_root
from astra.product import PRODUCT_NAME, PRODUCT_VERSION
from astra.state import ApplicationState
from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST

ROOT = Path(__file__).resolve().parents[1]


class PackagingSafetyTest(unittest.TestCase):
    def test_build_script_excludes_secrets_and_models(self) -> None:
        text = (ROOT / "scripts" / "build_windows.py").read_text(encoding="utf-8")
        self.assertIn("--add-data", text)
        self.assertIn("ui", text)
        self.assertNotIn("models/", text)
        self.assertNotIn(".env\"", text)
        self.assertIn("windowed", text)
        self.assertIn("Primus", text)
        self.assertIn("--noupx", text)
        self.assertIn("python312.dll", text)
        self.assertIn("_remove_decoy_exes", text)
        self.assertIn("matplotlib", text)
        self.assertIn("nvidia", text)
        self.assertIn("cuda_runtime", text)
        self.assertIn("_write_local_model_config", text)
        self.assertIn("_copy_runtime_corpus", text)

    def test_gitignore_keeps_build_out_of_source(self) -> None:
        text = (ROOT / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("dist_windows/", text)
        self.assertIn("dist-windows/", text)
        self.assertIn(".env", text)
        self.assertIn("models/", text)
        self.assertIn("data/", text)

    def test_unfrozen_project_root_is_repo(self) -> None:
        self.assertEqual(ROOT.resolve(), project_root().resolve())

    def test_frozen_project_root_is_exe_dir(self) -> None:
        import astra.configuration.paths as paths

        fake = str(ROOT / "dist_windows" / "Primus.exe")
        with patch.object(paths.sys, "frozen", True, create=True), patch.object(
            paths.sys, "executable", fake
        ):
            self.assertEqual(Path(fake).parent.resolve(), paths.project_root().resolve())

    def test_home_alias_does_not_drop_astra_home(self) -> None:
        self.assertEqual("ASTRA_HOME", ENV_HOME)
        self.assertEqual("PRIMUS_HOME", ENV_HOME_ALIAS)

    def test_schema_and_allowlist_unchanged(self) -> None:
        self.assertEqual(6, ApplicationState().schema_version)
        self.assertEqual({"read_text", "read_project"}, set(PRODUCTION_EXECUTION_ALLOWLIST))
        self.assertEqual("0.0.1", PRODUCT_VERSION)
        self.assertEqual("Primus", PRODUCT_NAME)


class BuildScriptStructureTest(unittest.TestCase):
    def test_onedir_verify_requires_python_dll(self) -> None:
        import tempfile

        from scripts.build_windows import _verify_onedir

        with tempfile.TemporaryDirectory() as raw:
            exe = Path(raw) / "Primus.exe"
            exe.write_bytes(b"x")
            with self.assertRaises(FileNotFoundError) as ctx:
                _verify_onedir(exe)
            self.assertIn("python312.dll", str(ctx.exception))

    def test_remove_decoy_spares_dist_exe(self) -> None:
        import tempfile

        from scripts import build_windows as build

        with tempfile.TemporaryDirectory() as raw:
            work = Path(raw) / "work"
            decoy_dir = work / "Primus"
            decoy_dir.mkdir(parents=True)
            decoy = decoy_dir / "Primus.exe"
            keep_dir = Path(raw) / "dist"
            keep_dir.mkdir()
            keep = keep_dir / "Primus.exe"
            decoy.write_bytes(b"decoy")
            keep.write_bytes(b"keep")
            original = build.WORK
            try:
                build.WORK = work
                build._remove_decoy_exes(keep)
            finally:
                build.WORK = original
            self.assertFalse(decoy.is_file())
            self.assertTrue(keep.is_file())

    def test_entry_freezes_support_before_ui_import(self) -> None:
        text = (ROOT / "scripts" / "primus_entry.py").read_text(encoding="utf-8")
        freeze_at = text.find("multiprocessing.freeze_support()")
        launch_at = text.find("from astra.boot import launch")
        ui_at = text.find("ui.app")
        self.assertGreaterEqual(freeze_at, 0)
        self.assertGreater(launch_at, freeze_at)
        self.assertEqual(-1, ui_at)
        self.assertNotIn("from astra.__main__ import", text)

    def test_dunder_main_does_not_import_ui_at_module_level(self) -> None:
        tree = ast.parse((ROOT / "astra" / "__main__.py").read_text(encoding="utf-8"))
        for node in ast.iter_child_nodes(tree):
            if isinstance(node, ast.ImportFrom):
                self.assertNotEqual("ui.app", node.module)
                self.assertFalse((node.module or "").startswith("ui."))

    def test_prepare_process_allows_unfrozen(self) -> None:
        from astra.boot import prepare_process

        self.assertTrue(prepare_process())

    def test_worker_argv_is_not_a_gui(self) -> None:
        from astra.boot import is_non_gui_invocation, prepare_process

        self.assertTrue(
            is_non_gui_invocation(
                ["Primus.exe", "-B", "-s", "-m", "unittest", "discover"]
            )
        )
        self.assertTrue(is_non_gui_invocation(["Primus.exe", "-c", "print(1)"]))
        self.assertTrue(
            is_non_gui_invocation(["Primus.exe", "--multiprocessing-fork"])
        )
        self.assertFalse(is_non_gui_invocation(["Primus.exe"]))
        self.assertFalse(is_non_gui_invocation(["Primus.exe", "--demo"]))
        self.assertFalse(is_non_gui_invocation(["Primus.exe", "--smoke"]))
        with patch("sys.argv", ["Primus.exe", "-m", "unittest", "discover"]):
            self.assertFalse(prepare_process())

    def test_frozen_error_writes_real_exception(self) -> None:
        from astra.boot import _frozen_error, _startup_log_path

        _frozen_error(ModuleNotFoundError("No module named 'ui'"))
        path = _startup_log_path()
        self.assertTrue(path.is_file())
        text = path.read_text(encoding="utf-8")
        self.assertIn("ModuleNotFoundError", text)
        self.assertIn("No module named 'ui'", text)
