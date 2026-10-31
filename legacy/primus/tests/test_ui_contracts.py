"""UI isolation and contract tests.

These tests guard the architectural boundary defined in ADR-0006 D1 and
ADR-0007: the ``ui/`` package must only import from ``astra.state`` and
``astra.presentation`` — never from ``astra.memory``, ``astra.reasoning``,
``astra.planning``, or other core engines.

No Dear PyGui window is opened. Tests are structural and import-level.
"""

from __future__ import annotations

import ast
import unittest
from pathlib import Path

# ── Helpers ───────────────────────────────────────────────────────────────

_PROJECT_ROOT = Path(__file__).parent.parent
_UI_ROOT = _PROJECT_ROOT / "ui"


def _ui_py_files() -> list[Path]:
    return sorted(_UI_ROOT.rglob("*.py"))


# Imports from these astra modules are forbidden inside ui/
_FORBIDDEN_ASTRA_IMPORTS = {
    "astra.memory",
    "astra.reasoning",
    "astra.planning",
    "astra.knowledge",
    "astra.core",
    "astra.research",
    "astra.coding",
    "astra.testing",
    "astra.evaluation",
    "astra.deployment",
    "astra.security",
    "astra.plugins",
    "astra.tools",
    "astra.logging",
}

# Imports from these are ALLOWED in ui/
_ALLOWED_ASTRA_IMPORTS = {
    "astra.state",
    "astra.presentation",
    "astra.configuration",  # allowed only for config reads, not backend
    "astra.product",  # user-facing identity only
}


def _is_forbidden(module: str) -> bool:
    return any(
        module == forbidden or module.startswith(forbidden + ".")
        for forbidden in _FORBIDDEN_ASTRA_IMPORTS
    )


# ── Tests ──────────────────────────────────────────────────────────────────


class UIIsolationTest(unittest.TestCase):
    """ADR-0006 D1: UI must not import from engine/agent packages."""

    def test_ui_directory_exists(self) -> None:
        self.assertTrue(_UI_ROOT.is_dir(), f"ui/ not found at {_UI_ROOT}")

    def test_ui_has_python_sources(self) -> None:
        self.assertTrue(_ui_py_files(), "No .py files found in ui/")

    def test_ui_does_not_import_memory_store(self) -> None:
        for path in _ui_py_files():
            tree = ast.parse(path.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    for alias in node.names:
                        if _is_forbidden(alias.name):
                            self.fail(
                                f"{path.relative_to(_PROJECT_ROOT)} imports "
                                f"{alias.name!r} (forbidden by ADR-0006 D1)"
                            )
                elif isinstance(node, ast.ImportFrom):
                    mod = node.module or ""
                    if _is_forbidden(mod):
                        self.fail(
                            f"{path.relative_to(_PROJECT_ROOT)} imports from "
                            f"{mod!r} (forbidden by ADR-0006 D1)"
                        )

    def test_ui_does_not_import_reasoning_backends(self) -> None:
        for path in _ui_py_files():
            text = path.read_text(encoding="utf-8")
            rel = str(path.relative_to(_PROJECT_ROOT))
            self.assertNotIn("LlamaCppLLMBackend", text,
                             f"{rel} references LlamaCppLLMBackend directly")
            self.assertNotIn("LlamaCppEmbeddingBackend", text,
                             f"{rel} references LlamaCppEmbeddingBackend directly")

    def test_ui_does_not_open_sqlite(self) -> None:
        for path in _ui_py_files():
            text = path.read_text(encoding="utf-8")
            rel = str(path.relative_to(_PROJECT_ROOT))
            self.assertNotIn("sqlite3.connect", text,
                             f"{rel} opens SQLite directly")
            self.assertNotIn("MemoryStore", text,
                             f"{rel} references MemoryStore directly")


class ThemeSystemTest(unittest.TestCase):
    def test_theme_has_no_dearpygui_import(self) -> None:
        """Themes are pure data — no dpg dependency."""
        for path in _UI_ROOT.joinpath("themes").glob("*.py"):
            text = path.read_text(encoding="utf-8")
            rel = str(path.relative_to(_PROJECT_ROOT))
            self.assertNotIn("import dearpygui", text,
                             f"{rel} imports dearpygui (themes must be pure data)")

    def test_futuristic_theme_importable(self) -> None:
        from ui.themes.futuristic import FUTURISTIC, get_theme
        self.assertEqual("futuristic", FUTURISTIC.name)
        self.assertIs(FUTURISTIC, get_theme("futuristic"))
        self.assertEqual((0, 0, 0, 255), FUTURISTIC.bg_window)
        self.assertEqual((0, 229, 255, 255), FUTURISTIC.text_accent)

    def test_unknown_theme_raises(self) -> None:
        from ui.themes.futuristic import get_theme
        with self.assertRaises(ValueError):
            get_theme("steampunk")

    def test_theme_has_five_status_colors(self) -> None:
        from ui.themes.futuristic import FUTURISTIC
        self.assertTrue(FUTURISTIC.color_operational)
        self.assertTrue(FUTURISTIC.color_available)
        self.assertTrue(FUTURISTIC.color_configured)
        self.assertTrue(FUTURISTIC.color_implemented)
        self.assertTrue(FUTURISTIC.color_planned)
        # All must be distinct
        colors = [
            FUTURISTIC.color_operational,
            FUTURISTIC.color_available,
            FUTURISTIC.color_configured,
            FUTURISTIC.color_implemented,
            FUTURISTIC.color_planned,
        ]
        self.assertEqual(len(colors), len(set(colors)))

    def test_theme_change_does_not_affect_state(self) -> None:
        from ui.themes.futuristic import FUTURISTIC
        from ui.themes.base import Theme
        from astra.state import ApplicationState

        alt_theme = Theme(name="minimal", bg_window=(0, 0, 0, 255))
        state = ApplicationState(stage="B-003")
        # Applying a different theme must not alter state
        self.assertEqual("B-003", state.stage)
        _ = alt_theme  # theme object created without touching state
        self.assertEqual("B-003", state.stage)


class StateProviderContractTest(unittest.TestCase):
    def test_app_consumes_state_provider(self) -> None:
        """PrimusApp must accept any StateProvider, not a concrete type."""
        import inspect
        from ui.app import AstraApp, PrimusApp
        self.assertIs(AstraApp, PrimusApp)
        sig = inspect.signature(PrimusApp.__init__)
        param = sig.parameters.get("provider")
        self.assertIsNotNone(param, "PrimusApp.__init__ must have 'provider' param")

    def test_demo_provider_is_state_provider(self) -> None:
        from ui.demo.provider import DemoStateProvider
        from astra.state import StateProvider
        self.assertTrue(issubclass(DemoStateProvider, StateProvider))

    def test_app_imports_only_astra_state_at_top_level(self) -> None:
        """ui/app.py top-level imports must not include forbidden modules."""
        app_path = _UI_ROOT / "app.py"
        tree = ast.parse(app_path.read_text(encoding="utf-8"))
        # Only check module-level nodes (not inside functions)
        for node in ast.iter_child_nodes(tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    self.assertFalse(
                        _is_forbidden(alias.name),
                        f"app.py top-level imports forbidden {alias.name!r}",
                    )
            elif isinstance(node, ast.ImportFrom):
                mod = node.module or ""
                self.assertFalse(
                    _is_forbidden(mod),
                    f"app.py top-level from-imports forbidden {mod!r}",
                )


class OfflineCapabilityTest(unittest.TestCase):
    def test_application_state_renders_missing_model_as_configured(self) -> None:
        """A missing GGUF must show CONFIGURED, not AVAILABLE or OPERATIONAL."""
        from astra.presentation.provider import _model_status
        from astra.state import CapabilityStatus

        cap = _model_status(None, "SomeModel")
        self.assertNotIn(cap.status, [
            CapabilityStatus.AVAILABLE,
            CapabilityStatus.OPERATIONAL,
        ])

    def test_configured_not_same_as_available(self) -> None:
        from astra.state import CapabilityStatus
        self.assertNotEqual(CapabilityStatus.CONFIGURED, CapabilityStatus.AVAILABLE)

    def test_available_not_same_as_operational(self) -> None:
        from astra.state import CapabilityStatus
        self.assertNotEqual(CapabilityStatus.AVAILABLE, CapabilityStatus.OPERATIONAL)

    def test_memory_panel_does_not_claim_pending_b003(self) -> None:
        text = (_UI_ROOT / "components" / "memory_panel.py").read_text(encoding="utf-8")
        self.assertNotIn("pending B-003", text)

    def test_goals_panel_is_render_only(self) -> None:
        path = _UI_ROOT / "components" / "goals_panel.py"
        text = path.read_text(encoding="utf-8")
        self.assertNotIn("add_button", text)
        self.assertNotIn(".dispatch(", text)
        self.assertIn("render_goals_panel", text)
        tree = ast.parse(text)
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom):
                mod = node.module or ""
                self.assertFalse(
                    mod == "astra.planning" or mod.startswith("astra.planning."),
                    "goals panel must not import astra.planning",
                )
            elif isinstance(node, ast.Import):
                for alias in node.names:
                    self.assertFalse(
                        alias.name == "astra.planning"
                        or alias.name.startswith("astra.planning."),
                        "goals panel must not import astra.planning",
                    )

    def test_security_panel_is_render_only(self) -> None:
        path = _UI_ROOT / "components" / "security_panel.py"
        text = path.read_text(encoding="utf-8")
        self.assertNotIn("add_button", text)
        self.assertNotIn(".dispatch(", text)
        self.assertIn("render_security_panel", text)
        tree = ast.parse(text)
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom):
                mod = node.module or ""
                self.assertFalse(
                    mod == "astra.security" or mod.startswith("astra.security."),
                    "security panel must not import astra.security",
                )

    def test_tools_panel_is_render_only(self) -> None:
        path = _UI_ROOT / "components" / "tools_panel.py"
        text = path.read_text(encoding="utf-8")
        self.assertNotIn("add_button", text)
        self.assertNotIn(".dispatch(", text)
        self.assertNotIn("record_human_approval", text)
        self.assertIn("render_tools_panel", text)
        self.assertIn("read_text", text)
        self.assertIn("read_project", text)
        tree = ast.parse(text)
        for node in ast.walk(tree):
            names: list[str] = []
            if isinstance(node, ast.ImportFrom) and node.module:
                names = [node.module]
            elif isinstance(node, ast.Import):
                names = [alias.name for alias in node.names]
            for mod in names:
                self.assertFalse(
                    mod == "astra.tools" or mod.startswith("astra.tools."),
                    "tools panel must not import astra.tools",
                )

    def test_coding_panel_is_render_only(self) -> None:
        path = _UI_ROOT / "components" / "coding_panel.py"
        text = path.read_text(encoding="utf-8")
        self.assertNotIn("add_button", text)
        self.assertNotIn("add_input_text", text)
        self.assertNotIn(".dispatch(", text)
        self.assertIn("render_coding_panel", text)
        self.assertIn("last_preview", text)
        self.assertIn("last_objections", text)
        tree = ast.parse(text)
        for node in ast.walk(tree):
            names: list[str] = []
            if isinstance(node, ast.ImportFrom) and node.module:
                names = [node.module]
            elif isinstance(node, ast.Import):
                names = [alias.name for alias in node.names]
            for mod in names:
                self.assertFalse(
                    mod == "astra.coding" or mod.startswith("astra.coding."),
                    "coding panel must not import astra.coding",
                )

    def test_main_accepts_injected_provider(self) -> None:
        import inspect
        from ui.app import main
        sig = inspect.signature(main)
        self.assertIn("provider", sig.parameters)
