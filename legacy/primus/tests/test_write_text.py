"""Controlled write_text tool. Data/log roots only."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from astra.tools.guard import GATE_TOKEN
from astra.tools.write_text import WriteTextTool


class WriteTextToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.tool = WriteTextTool(write_roots=(self.root,))
        self.token = GATE_TOKEN.set(True)
        self.addCleanup(lambda: GATE_TOKEN.reset(self.token))

    def test_create_and_overwrite_snapshot(self) -> None:
        dest = self.root / "agent-reports" / "inspection.md"
        created = self.tool.invoke(path=str(dest), content="# one\n", operation="create")
        self.assertTrue(created.success, created.error)
        self.assertEqual("create", created.details["operation"])
        self.assertTrue(dest.is_file())
        overwritten = self.tool.invoke(path=str(dest), content="# two\n", operation="write")
        self.assertTrue(overwritten.success, overwritten.error)
        self.assertEqual("overwrite", overwritten.details["operation"])
        self.assertEqual("# two\n", dest.read_text(encoding="utf-8"))
        snap = Path(str(overwritten.details["snapshot"]))
        self.assertTrue(snap.is_file())
        self.assertEqual("# one\n", snap.read_text(encoding="utf-8"))

    def test_refuses_project_source_and_escape(self) -> None:
        escaped = self.tool.invoke(
            path=str(Path(self.tmp.name).parent / "outside.md"),
            content="nope",
        )
        self.assertFalse(escaped.success)
        py = self.tool.invoke(path=str(self.root / "hack.py"), content="print(1)")
        self.assertFalse(py.success)

    def test_result_shape(self) -> None:
        dest = self.root / "note.txt"
        result = self.tool.invoke(path=str(dest), content="hi")
        self.assertTrue(result.success)
        self.assertEqual(str(dest).lower(), str(result.details["path"]).lower())
        self.assertIn(result.details["operation"], {"create", "overwrite"})
        self.assertIn("bytes", result.details)


class WriteTextGatedTest(unittest.TestCase):
    def test_security_gate_requires_approval_then_execute(self) -> None:
        from astra.configuration.settings import (
            AstraSettings,
            KnowledgeSettings,
            LoggingSettings,
            MemorySettings,
            PlanningSettings,
        )
        from astra.security.agent import PolicySecurityAgent
        from astra.tools.contracts import ToolInvocationRequest
        from astra.tools.gate import GatedToolInvoker
        from astra.tools.registry import InMemoryToolRegistry
        from astra.tools.contracts import InvocationStatus

        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        dest = root / "agent-reports" / "inspection.md"
        settings = AstraSettings(
            memory=MemorySettings(
                database_path=root / "mem.db",
                vector_index_path=root / "vectors.faiss",
            ),
            knowledge=KnowledgeSettings(database_path=root / "knowledge.db"),
            planning=PlanningSettings(database_path=root / "planning.db"),
            logging=LoggingSettings(directory=root / "logs"),
        )
        registry = InMemoryToolRegistry()
        registry.register(WriteTextTool(write_roots=(root,)))
        invoker = GatedToolInvoker(
            registry,
            security=PolicySecurityAgent.from_settings(settings),
            execution_allowlist=("write_text",),
        )
        rec = invoker.request(
            ToolInvocationRequest(
                caller="agent_loop",
                tool_name="write_text",
                arguments={"path": str(dest), "content": "# report\n"},
            )
        )
        self.assertEqual(InvocationStatus.PENDING_APPROVAL, rec.status)
        self.assertFalse(dest.exists())
        invoker.record_human_approval(rec.invocation_id)
        self.assertFalse(dest.exists())
        done = invoker.execute_approved(rec.invocation_id)
        self.assertTrue(done.executed)
        self.assertTrue(done.result.success)
        self.assertTrue(dest.is_file())
        self.assertEqual("# report\n", dest.read_text(encoding="utf-8"))
