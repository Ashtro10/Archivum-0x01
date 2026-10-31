"""Isolated run_python tool. Injected runner. No subprocess in tools package."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from astra.tools.guard import GATE_TOKEN
from astra.tools.run_python import RunPythonTool


class RunPythonToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.calls: list[tuple[Path, float]] = []

        def _execute(script: Path, timeout: float) -> dict[str, object]:
            self.calls.append((script, timeout))
            source = script.read_text(encoding="utf-8")
            return {
                "success": True,
                "stdout": f"ran:{source.strip()}",
                "stderr": "",
                "exit_code": 0,
                "timed_out": False,
            }

        self.tool = RunPythonTool(scratch_roots=(self.root,), execute=_execute)
        self.token = GATE_TOKEN.set(True)
        self.addCleanup(lambda: GATE_TOKEN.reset(self.token))

    def test_writes_and_captures(self) -> None:
        dest = self.root / "agent_run.py"
        result = self.tool.invoke(path=str(dest), code="print(1)\n", timeout=5)
        self.assertTrue(result.success, result.error)
        self.assertEqual("ran:print(1)", result.output)
        self.assertEqual(0, result.details["exit_code"])
        self.assertTrue(dest.is_file())
        self.assertEqual(1, len(self.calls))

    def test_timeout_and_stderr(self) -> None:
        def _timeout(script: Path, timeout: float) -> dict[str, object]:
            return {
                "success": False,
                "stdout": "",
                "stderr": "boom",
                "exit_code": 1,
                "timed_out": True,
                "error": "timed out",
            }

        tool = RunPythonTool(scratch_roots=(self.root,), execute=_timeout)
        result = tool.invoke(
            path=str(self.root / "agent_run.py"),
            code="print(1)\n",
            timeout=1,
        )
        self.assertFalse(result.success)
        self.assertTrue(result.details["timed_out"])
        self.assertIn("timed out", result.output)

    def test_refuses_outside_scratch(self) -> None:
        result = self.tool.invoke(
            path=str(Path(self.tmp.name).parent / "evil.py"),
            code="print(1)\n",
        )
        self.assertFalse(result.success)
        self.assertIn("outside scratch", result.error)
        self.assertEqual([], self.calls)

    def test_unbound_runner_fails_closed(self) -> None:
        tool = RunPythonTool(scratch_roots=(self.root,))
        result = tool.invoke(
            path=str(self.root / "agent_run.py"),
            code="print(1)\n",
        )
        self.assertFalse(result.success)
        self.assertIn("not bound", result.error)
