"""Read-only filesystem search tool."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from astra.tools.guard import GATE_TOKEN
from astra.tools.search_files import SearchFilesTool


class SearchFilesToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "README.md").write_text("# hello search-token\n", encoding="utf-8")
        (self.root / "src").mkdir()
        (self.root / "src" / "app.py").write_text("TODO: fix leak\nprint(1)\n", encoding="utf-8")
        (self.root / ".git").mkdir()
        (self.root / ".git" / "config").write_text("secret\n", encoding="utf-8")
        (self.root / "secrets.json").write_text('{"k":1}\n', encoding="utf-8")
        self.tool = SearchFilesTool(search_roots=(self.root,), exclusions=(self.root / ".git",))
        self.token = GATE_TOKEN.set(True)
        self.addCleanup(lambda: GATE_TOKEN.reset(self.token))

    def test_filename_and_text_hits(self) -> None:
        result = self.tool.invoke(
            path=str(self.root / "README.md"),
            query="app.py",
            mode="filename",
        )
        self.assertTrue(result.success)
        self.assertIn("app.py", result.output)
        text = self.tool.invoke(
            path=str(self.root / "README.md"),
            query="fix leak",
            mode="text",
        )
        self.assertTrue(text.success)
        self.assertIn("app.py", text.output)
        self.assertIn("TODO: fix leak", text.output)

    def test_skips_git_and_secrets_and_does_not_modify(self) -> None:
        before = (self.root / "src" / "app.py").read_text(encoding="utf-8")
        result = self.tool.invoke(
            path=str(self.root / "README.md"),
            query="secret",
            mode="both",
        )
        self.assertTrue(result.success)
        self.assertNotIn(".git", result.output)
        self.assertNotIn("secrets.json", result.output)
        self.assertEqual(before, (self.root / "src" / "app.py").read_text(encoding="utf-8"))

    def test_escapes_root(self) -> None:
        result = self.tool.invoke(path="C:/Windows/notepad.exe", query="x")
        self.assertFalse(result.success)
        self.assertIn("outside", result.error)
