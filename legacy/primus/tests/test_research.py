"""Comprehensive tests for the Research Agent (B-009).

Coverage map (ADR-0011 + spec requirements):

1.  Agent contract compliance
2.  Successful local research (TextSource)
3.  Empty source / no findings
4.  Malformed request (empty objective)
5.  Missing / unavailable source
6.  Source failure (exception during query)
7.  Reasoning / LLM failure (exception in generate)
8.  Provenance preservation (source, confidence, retrieved_at)
9.  Deterministic local-source behaviour
10. Controller dispatch ("research" agent)
11. Agent exception isolation (run() never raises)
12. Offline guarantee (no network access)
13. No network imports in research modules
14. No subprocess use
15. No automatic permanent-memory writes
16. Dashboard capability state
17. B-009 milestone detection
18. TextSource keyword matching
19. DocumentSource (local file)
20. KnowledgeSource via real store
21. MemorySource via real store
22. ResearchSource is ABC
23. LocalResearchAgent inherits ResearchAgent
24. allow_network=True accepted but unused (no network sources in B-009)
25. summarise() fallback without LLM
26. summarise() with fake LLM
27. Multiple sources aggregated
28. Source is_available() False → skipped
29. run() returns AgentResult with evidence field populated
30. Finding.retrieved_at is set and UTC-aware
"""

from __future__ import annotations

import socket
import tempfile
import unittest
from datetime import timezone
from pathlib import Path
from types import MappingProxyType
from unittest.mock import patch

from astra.core.contracts import AgentRequest, AgentResult
from astra.research.agent import LocalResearchAgent
from astra.research.contracts import Finding, ResearchAgent, ResearchReport
from astra.research.errors import ResearchError, SourceError
from astra.research.source import (
    DocumentSource,
    KnowledgeSource,
    MemorySource,
    ResearchSource,
    TextSource,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

SAMPLE_TEXT = """
Python is a dynamically typed, interpreted programming language.

Closures in Python retain references to variables from their enclosing scope.

Lambda functions are anonymous single-expression functions.

Memory management in Python uses reference counting plus a cyclic garbage collector.
"""

IRRELEVANT_TEXT = """
The weather forecast calls for partly cloudy skies.

A recipe for chocolate cake requires flour, sugar, and cocoa.
"""


class _AlwaysFailSource(ResearchSource):
    @property
    def name(self) -> str:
        return "always_fail"

    def is_available(self) -> bool:
        return True

    def query(self, question: str, *, limit: int = 10):
        raise RuntimeError("intentional source failure")


class _UnavailableSource(ResearchSource):
    @property
    def name(self) -> str:
        return "unavailable"

    def is_available(self) -> bool:
        return False

    def query(self, question: str, *, limit: int = 10):
        raise AssertionError("should never be called when unavailable")


class _EmptySource(ResearchSource):
    @property
    def name(self) -> str:
        return "empty"

    def is_available(self) -> bool:
        return True

    def query(self, question: str, *, limit: int = 10):
        return ()


class _FakeLLM:
    """Fake LLM backend that returns a fixed string."""

    def is_available(self) -> bool:
        return True

    def generate(self, prompt: str, options=None) -> str:
        return f"synthesized answer for prompt length {len(prompt)}"


class _FailingLLM:
    """Fake LLM backend that always raises."""

    def is_available(self) -> bool:
        return True

    def generate(self, prompt: str, options=None) -> str:
        raise RuntimeError("llm failure")


def _temp_stores():
    """Return (SQLiteMemoryStore, SQLiteKnowledgeStore) backed by temp files."""
    from astra.configuration.settings import KnowledgeSettings, MemorySettings
    from astra.memory.sqlite_store import SQLiteMemoryStore
    from astra.knowledge.sqlite_store import SQLiteKnowledgeStore

    mem_dir = tempfile.mkdtemp()
    know_dir = tempfile.mkdtemp()
    vec_dir = tempfile.mkdtemp()

    mem = SQLiteMemoryStore(
        MemorySettings(
            database_path=Path(mem_dir) / "mem.db",
            vector_index_path=Path(vec_dir) / "v.faiss",
        ).database_path
    )
    mem.initialise()

    know = SQLiteKnowledgeStore(
        KnowledgeSettings(database_path=Path(know_dir) / "know.db").database_path
    )
    know.initialise()
    return mem, know


# ---------------------------------------------------------------------------
# 1. Agent contract compliance
# ---------------------------------------------------------------------------

class ContractTest(unittest.TestCase):
    def test_local_agent_is_research_agent(self) -> None:
        self.assertTrue(issubclass(LocalResearchAgent, ResearchAgent))

    def test_local_agent_name(self) -> None:
        agent = LocalResearchAgent()
        self.assertEqual(agent.name, "research")

    def test_research_agent_is_abstract(self) -> None:
        with self.assertRaises(TypeError):
            ResearchAgent()  # type: ignore[abstract]

    def test_research_source_is_abstract(self) -> None:
        with self.assertRaises(TypeError):
            ResearchSource()  # type: ignore[abstract]

    def test_run_returns_agent_result(self) -> None:
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT)])
        result = agent.run(AgentRequest(objective="Python closures"))
        self.assertIsInstance(result, AgentResult)


# ---------------------------------------------------------------------------
# 2. Successful local research
# ---------------------------------------------------------------------------

class SuccessfulResearchTest(unittest.TestCase):
    def setUp(self) -> None:
        self.agent = LocalResearchAgent(
            sources=[TextSource("python_docs", SAMPLE_TEXT)]
        )

    def test_investigate_returns_report(self) -> None:
        report = self.agent.investigate("Python closures")
        self.assertIsInstance(report, ResearchReport)

    def test_findings_non_empty_for_relevant_query(self) -> None:
        report = self.agent.investigate("Python closures")
        self.assertGreater(len(report.findings), 0)

    def test_findings_are_finding_instances(self) -> None:
        report = self.agent.investigate("Python")
        for f in report.findings:
            self.assertIsInstance(f, Finding)

    def test_report_question_preserved(self) -> None:
        q = "What are lambda functions?"
        report = self.agent.investigate(q)
        self.assertEqual(report.question, q)

    def test_used_network_false(self) -> None:
        report = self.agent.investigate("Python closures")
        self.assertFalse(report.used_network)

    def test_run_success_for_relevant_query(self) -> None:
        result = self.agent.run(AgentRequest(objective="Python lambda"))
        self.assertTrue(result.success)
        self.assertEqual(result.agent, "research")


# ---------------------------------------------------------------------------
# 3. Empty source / no findings
# ---------------------------------------------------------------------------

class EmptySourceTest(unittest.TestCase):
    def test_empty_source_returns_empty_findings(self) -> None:
        agent = LocalResearchAgent(sources=[_EmptySource()])
        report = agent.investigate("anything")
        self.assertEqual(len(report.findings), 0)

    def test_no_sources_returns_empty_report(self) -> None:
        agent = LocalResearchAgent(sources=[])
        report = agent.investigate("anything")
        self.assertEqual(len(report.findings), 0)

    def test_run_with_no_sources(self) -> None:
        agent = LocalResearchAgent(sources=[])
        result = agent.run(AgentRequest(objective="anything"))
        # success may be False with no findings — that's honest
        self.assertIsInstance(result, AgentResult)
        self.assertEqual(result.agent, "research")

    def test_irrelevant_query_returns_empty(self) -> None:
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT)])
        report = agent.investigate("xyzzy blargle frobnitz")
        self.assertEqual(len(report.findings), 0)


# ---------------------------------------------------------------------------
# 4. Malformed request
# ---------------------------------------------------------------------------

class MalformedRequestTest(unittest.TestCase):
    def test_empty_objective_returns_failure(self) -> None:
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT)])
        result = agent.run(AgentRequest(objective=""))
        self.assertFalse(result.success)

    def test_whitespace_objective_returns_failure(self) -> None:
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT)])
        result = agent.run(AgentRequest(objective="   "))
        self.assertFalse(result.success)

    def test_empty_question_investigate_returns_empty_report(self) -> None:
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT)])
        report = agent.investigate("")
        self.assertEqual(len(report.findings), 0)


# ---------------------------------------------------------------------------
# 5. Missing / unavailable source
# ---------------------------------------------------------------------------

class UnavailableSourceTest(unittest.TestCase):
    def test_unavailable_source_skipped(self) -> None:
        agent = LocalResearchAgent(sources=[_UnavailableSource()])
        report = agent.investigate("anything")
        self.assertEqual(len(report.findings), 0)

    def test_unavailable_source_in_open_questions(self) -> None:
        agent = LocalResearchAgent(sources=[_UnavailableSource()])
        report = agent.investigate("anything")
        self.assertGreater(len(report.open_questions), 0)

    def test_run_with_unavailable_source_does_not_raise(self) -> None:
        agent = LocalResearchAgent(sources=[_UnavailableSource()])
        result = agent.run(AgentRequest(objective="test"))
        self.assertIsInstance(result, AgentResult)


# ---------------------------------------------------------------------------
# 6. Source failure
# ---------------------------------------------------------------------------

class SourceFailureTest(unittest.TestCase):
    def test_failing_source_does_not_crash_agent(self) -> None:
        agent = LocalResearchAgent(sources=[_AlwaysFailSource()])
        result = agent.run(AgentRequest(objective="test"))
        self.assertIsInstance(result, AgentResult)

    def test_failing_source_continues_with_next(self) -> None:
        good = TextSource("good", SAMPLE_TEXT)
        agent = LocalResearchAgent(sources=[_AlwaysFailSource(), good])
        report = agent.investigate("Python")
        # Good source findings must still be returned
        self.assertGreater(len(report.findings), 0)

    def test_all_sources_fail_returns_empty_report(self) -> None:
        agent = LocalResearchAgent(sources=[_AlwaysFailSource(), _AlwaysFailSource()])
        report = agent.investigate("Python")
        self.assertEqual(len(report.findings), 0)


# ---------------------------------------------------------------------------
# 7. Reasoning / LLM failure
# ---------------------------------------------------------------------------

class LLMFailureTest(unittest.TestCase):
    def test_llm_failure_falls_back_to_deterministic(self) -> None:
        agent = LocalResearchAgent(
            sources=[TextSource("t", SAMPLE_TEXT)],
            llm=_FailingLLM(),
        )
        findings = agent.investigate("Python").findings
        result = agent.summarise(findings)
        # Should still succeed with deterministic fallback
        self.assertTrue(result.success)
        self.assertFalse(result.details.get("synthesized", True))

    def test_llm_failure_run_still_returns_result(self) -> None:
        agent = LocalResearchAgent(
            sources=[TextSource("t", SAMPLE_TEXT)],
            llm=_FailingLLM(),
        )
        result = agent.run(AgentRequest(objective="Python"))
        self.assertIsInstance(result, AgentResult)


# ---------------------------------------------------------------------------
# 8. Provenance preservation
# ---------------------------------------------------------------------------

class ProvenanceTest(unittest.TestCase):
    def test_source_name_in_findings(self) -> None:
        agent = LocalResearchAgent(
            sources=[TextSource("my_source", SAMPLE_TEXT)]
        )
        report = agent.investigate("Python lambda")
        for f in report.findings:
            self.assertEqual(f.source, "my_source")

    def test_confidence_in_range(self) -> None:
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT)])
        report = agent.investigate("Python closures lambda")
        for f in report.findings:
            self.assertGreaterEqual(f.confidence, 0.0)
            self.assertLessEqual(f.confidence, 1.0)

    def test_retrieved_at_is_set(self) -> None:
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT)])
        report = agent.investigate("Python")
        for f in report.findings:
            self.assertIsNotNone(f.retrieved_at)

    def test_retrieved_at_is_utc(self) -> None:
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT)])
        report = agent.investigate("Python")
        for f in report.findings:
            assert f.retrieved_at is not None
            self.assertEqual(f.retrieved_at.tzinfo, timezone.utc)

    def test_evidence_field_in_run_result(self) -> None:
        agent = LocalResearchAgent(sources=[TextSource("src", SAMPLE_TEXT)])
        result = agent.run(AgentRequest(objective="Python"))
        if result.success:
            self.assertIsInstance(result.evidence, tuple)


# ---------------------------------------------------------------------------
# 9. Deterministic behaviour
# ---------------------------------------------------------------------------

class DeterministicTest(unittest.TestCase):
    def test_same_input_same_findings(self) -> None:
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT)])
        r1 = agent.investigate("Python closures")
        r2 = agent.investigate("Python closures")
        self.assertEqual(
            [f.claim for f in r1.findings],
            [f.claim for f in r2.findings],
        )

    def test_same_source_same_confidence(self) -> None:
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT)])
        r1 = agent.investigate("Python")
        r2 = agent.investigate("Python")
        confidences1 = [f.confidence for f in r1.findings]
        confidences2 = [f.confidence for f in r2.findings]
        self.assertEqual(confidences1, confidences2)


# ---------------------------------------------------------------------------
# 10–11. Controller dispatch + exception isolation
# ---------------------------------------------------------------------------

class ControllerDispatchTest(unittest.TestCase):
    def _make_ctrl(self):
        from astra.configuration.settings import (
            AstraSettings, KnowledgeSettings, MemorySettings, PlanningSettings
        )
        from astra.core.astra_controller import AstraController

        mem_dir = tempfile.mkdtemp()
        know_dir = tempfile.mkdtemp()
        plan_dir = tempfile.mkdtemp()
        vec_dir = tempfile.mkdtemp()

        s = AstraSettings(
            memory=MemorySettings(
                database_path=Path(mem_dir) / "mem.db",
                vector_index_path=Path(vec_dir) / "v.faiss",
            ),
            knowledge=KnowledgeSettings(
                database_path=Path(know_dir) / "know.db"
            ),
            planning=PlanningSettings(
                database_path=Path(plan_dir) / "plan.db"
            ),
        )
        return AstraController(s)

    def test_research_agent_registered_after_start(self) -> None:
        ctrl = self._make_ctrl()
        ctrl.start()
        self.assertIn("research", ctrl.agent_names)
        ctrl.shutdown()

    def test_dispatch_research_returns_result(self) -> None:
        ctrl = self._make_ctrl()
        ctrl.start()
        result = ctrl.dispatch("research", AgentRequest(objective="Python closures"))
        self.assertIsInstance(result, AgentResult)
        ctrl.shutdown()

    def test_dispatch_research_empty_question(self) -> None:
        ctrl = self._make_ctrl()
        ctrl.start()
        result = ctrl.dispatch("research", AgentRequest(objective=""))
        self.assertFalse(result.success)
        ctrl.shutdown()

    def test_research_exception_does_not_crash_controller(self) -> None:
        ctrl = self._make_ctrl()
        ctrl.start()
        # Even if something weird is dispatched, controller stays up
        ctrl.dispatch("research", AgentRequest(objective="test"))
        self.assertTrue(ctrl.started)
        ctrl.shutdown()


# ---------------------------------------------------------------------------
# 12–14. Offline guarantee, no network imports, no subprocess
# ---------------------------------------------------------------------------

class OfflineTest(unittest.TestCase):
    def _block(self, *a, **kw):
        raise AssertionError("network access attempted")

    def test_investigate_offline(self) -> None:
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT)])
        with patch.object(socket, "socket", side_effect=self._block), \
             patch.object(socket, "create_connection", side_effect=self._block), \
             patch.object(socket, "getaddrinfo", side_effect=self._block):
            report = agent.investigate("Python closures")
        self.assertIsInstance(report, ResearchReport)

    def test_run_offline(self) -> None:
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT)])
        with patch.object(socket, "socket", side_effect=self._block), \
             patch.object(socket, "create_connection", side_effect=self._block), \
             patch.object(socket, "getaddrinfo", side_effect=self._block):
            result = agent.run(AgentRequest(objective="Python"))
        self.assertIsInstance(result, AgentResult)

    def test_no_network_imports_in_research_modules(self) -> None:
        import ast
        import importlib
        network_libs = {"httpx", "requests", "urllib3", "aiohttp"}
        pkg = importlib.import_module("astra.research")
        pkg_path = Path(pkg.__file__).parent  # type: ignore[arg-type]
        for py_file in pkg_path.glob("*.py"):
            source = py_file.read_text(encoding="utf-8")
            try:
                tree = ast.parse(source)
            except SyntaxError:
                continue
            for node in ast.walk(tree):
                if isinstance(node, (ast.Import, ast.ImportFrom)):
                    names = (
                        [a.name for a in node.names]
                        if isinstance(node, ast.Import)
                        else ([node.module] if node.module else [])
                    )
                    for name in names:
                        root = (name or "").split(".")[0]
                        with self.subTest(file=py_file.name, library=root):
                            self.assertNotIn(root, network_libs)

    def test_no_subprocess_in_research_modules(self) -> None:
        import ast
        import importlib
        forbidden = {"subprocess", "os.system", "popen"}
        pkg = importlib.import_module("astra.research")
        pkg_path = Path(pkg.__file__).parent  # type: ignore[arg-type]
        for py_file in pkg_path.glob("*.py"):
            source = py_file.read_text(encoding="utf-8")
            try:
                tree = ast.parse(source)
            except SyntaxError:
                continue
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    for alias in node.names:
                        with self.subTest(file=py_file.name, module=alias.name):
                            self.assertNotIn(alias.name, forbidden)


# ---------------------------------------------------------------------------
# 15. No automatic permanent-memory writes
# ---------------------------------------------------------------------------

class NoAutoWriteTest(unittest.TestCase):
    def test_investigate_does_not_write_to_memory(self) -> None:
        mem, know = _temp_stores()
        mem_source = MemorySource(mem)
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT), mem_source])

        # Count records before
        before = list(mem.iter_records())
        agent.investigate("Python closures")
        after = list(mem.iter_records())

        mem.close()
        know.close()
        self.assertEqual(len(before), len(after), "investigate() must not write to memory")

    def test_run_does_not_write_to_memory(self) -> None:
        mem, know = _temp_stores()
        mem_source = MemorySource(mem)
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT), mem_source])

        before = list(mem.iter_records())
        agent.run(AgentRequest(objective="Python lambda"))
        after = list(mem.iter_records())

        mem.close()
        know.close()
        self.assertEqual(len(before), len(after), "run() must not write to memory")

    def test_run_does_not_write_to_knowledge(self) -> None:
        mem, know = _temp_stores()
        know_source = KnowledgeSource(know)
        agent = LocalResearchAgent(
            sources=[TextSource("t", SAMPLE_TEXT), know_source]
        )

        before = list(know.iter_records())
        agent.run(AgentRequest(objective="Python"))
        after = list(know.iter_records())

        mem.close()
        know.close()
        self.assertEqual(len(before), len(after), "run() must not write to knowledge")


# ---------------------------------------------------------------------------
# 16–17. Dashboard + milestone
# ---------------------------------------------------------------------------

class DashboardMilestoneTest(unittest.TestCase):
    def test_b009_milestone_complete(self) -> None:
        from astra.presentation.progress import MILESTONES
        b009 = next((m for m in MILESTONES if m.id == "B-009"), None)
        self.assertIsNotNone(b009)
        assert b009 is not None
        self.assertTrue(b009.complete())

    def test_research_module_in_provider_modules(self) -> None:
        from astra.presentation.provider import AstraStateProvider
        state = AstraStateProvider().get_state()
        modules = {m.module for m in state.modules}
        self.assertIn("astra.research", modules)

    def test_research_status_operational(self) -> None:
        from astra.presentation.provider import AstraStateProvider
        from astra.state import CapabilityStatus
        state = AstraStateProvider().get_state()
        res = next((m for m in state.modules if m.module == "astra.research"), None)
        self.assertIsNotNone(res)
        assert res is not None
        self.assertEqual(res.status, CapabilityStatus.OPERATIONAL)


# ---------------------------------------------------------------------------
# 18. TextSource keyword matching
# ---------------------------------------------------------------------------

class TextSourceTest(unittest.TestCase):
    def test_matches_relevant_paragraphs(self) -> None:
        src = TextSource("t", SAMPLE_TEXT)
        findings = src.query("Python closures")
        self.assertGreater(len(findings), 0)
        for f in findings:
            self.assertGreater(f.confidence, 0.0)

    def test_returns_empty_for_irrelevant_query(self) -> None:
        src = TextSource("t", SAMPLE_TEXT)
        findings = src.query("xyzzy blargle")
        self.assertEqual(len(findings), 0)

    def test_limit_respected(self) -> None:
        src = TextSource("t", SAMPLE_TEXT)
        findings = src.query("Python", limit=1)
        self.assertLessEqual(len(findings), 1)

    def test_sorted_by_confidence_descending(self) -> None:
        src = TextSource("t", SAMPLE_TEXT)
        findings = src.query("Python closures memory")
        confidences = [f.confidence for f in findings]
        self.assertEqual(confidences, sorted(confidences, reverse=True))

    def test_empty_text_is_unavailable(self) -> None:
        src = TextSource("empty", "")
        self.assertFalse(src.is_available())

    def test_non_empty_text_is_available(self) -> None:
        src = TextSource("t", SAMPLE_TEXT)
        self.assertTrue(src.is_available())


# ---------------------------------------------------------------------------
# 19. DocumentSource (local file)
# ---------------------------------------------------------------------------

class DocumentSourceTest(unittest.TestCase):
    def test_reads_local_file(self) -> None:
        with tempfile.NamedTemporaryFile(
            suffix=".txt", mode="w", encoding="utf-8", delete=False
        ) as f:
            f.write(SAMPLE_TEXT)
            path = f.name
        src = DocumentSource(path)
        self.assertTrue(src.is_available())
        findings = src.query("Python closures")
        self.assertGreater(len(findings), 0)

    def test_missing_file_unavailable(self) -> None:
        src = DocumentSource("/nonexistent/path/file.txt")
        self.assertFalse(src.is_available())

    def test_missing_file_query_returns_empty(self) -> None:
        src = DocumentSource("/nonexistent/path/file.txt")
        findings = src.query("Python")
        self.assertEqual(len(findings), 0)

    def test_source_name_is_path_string(self) -> None:
        src = DocumentSource("/some/file.txt")
        self.assertEqual(src.name, str(Path("/some/file.txt")))


# ---------------------------------------------------------------------------
# 20. KnowledgeSource via real store
# ---------------------------------------------------------------------------

class KnowledgeSourceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.mem, self.know = _temp_stores()
        # Add a record so search can return something
        from astra.knowledge.contracts import (
            KnowledgeCategory, KnowledgeRecord, KnowledgeVerification
        )
        rec = KnowledgeRecord(
            record_id="",
            category=KnowledgeCategory.PROGRAMMING,
            content="Python is a dynamically typed programming language",
            source="test",
            confidence=0.9,
            verification=KnowledgeVerification.UNVERIFIED,
        )
        self.know.store(rec)
        self.src = KnowledgeSource(self.know)

    def tearDown(self) -> None:
        self.mem.close()
        self.know.close()

    def test_knowledge_source_available(self) -> None:
        self.assertTrue(self.src.is_available())

    def test_knowledge_source_returns_findings(self) -> None:
        findings = self.src.query("Python programming")
        self.assertGreater(len(findings), 0)

    def test_finding_has_knowledge_source_prefix(self) -> None:
        findings = self.src.query("Python")
        for f in findings:
            self.assertTrue(f.source.startswith("knowledge:"))

    def test_confidence_from_record(self) -> None:
        findings = self.src.query("Python")
        for f in findings:
            self.assertAlmostEqual(f.confidence, 0.9, places=5)


# ---------------------------------------------------------------------------
# 21. MemorySource via real store
# ---------------------------------------------------------------------------

class MemorySourceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.mem, self.know = _temp_stores()
        from astra.memory.contracts import (
            MemoryCategory, MemoryRecord, VerificationState
        )
        rec = MemoryRecord(
            record_id="",
            category=MemoryCategory.RESEARCH,
            content="Python closures are a key language feature",
            source="test",
            verification=VerificationState.UNVERIFIED,
        )
        self.mem.store(rec)
        self.src = MemorySource(self.mem)

    def tearDown(self) -> None:
        self.mem.close()
        self.know.close()

    def test_memory_source_available(self) -> None:
        self.assertTrue(self.src.is_available())

    def test_memory_source_returns_findings(self) -> None:
        findings = self.src.query("Python closures")
        self.assertGreater(len(findings), 0)

    def test_finding_has_memory_source_prefix(self) -> None:
        findings = self.src.query("Python")
        for f in findings:
            self.assertTrue(f.source.startswith("memory:"))

    def test_confidence_is_fixed_hit_value(self) -> None:
        from astra.research.source import MEMORY_HIT_CONFIDENCE
        findings = self.src.query("Python")
        for f in findings:
            self.assertEqual(f.confidence, MEMORY_HIT_CONFIDENCE)

    def test_without_indexer_stays_fts_only(self) -> None:
        from astra.research.source import MEMORY_HIT_CONFIDENCE
        findings = self.src.query("closures")
        self.assertGreater(len(findings), 0)
        for f in findings:
            self.assertEqual(f.confidence, MEMORY_HIT_CONFIDENCE)


class MemorySourceSemanticMergeTest(unittest.TestCase):
    def setUp(self) -> None:
        from astra.memory.faiss_index import FaissVectorIndex
        from astra.memory.indexer import MemoryIndexer

        self.mem, self.know = _temp_stores()
        from astra.memory.contracts import MemoryCategory, MemoryRecord

        dim = 8
        model = "test-embed-v1"
        index = FaissVectorIndex(dim, model)

        garden_vec = [1.0] + [0.01] * (dim - 1)
        other_vec = [0.01] * dim
        other_vec[1] = 1.0

        class _Backend:
            def is_available(self) -> bool:
                return True

            @property
            def model_name(self) -> str:
                return model

            @property
            def dimension(self) -> int:
                return dim

            def embed(self, texts):
                out = []
                for t in texts:
                    low = t.lower()
                    if any(w in low for w in ("horticulture", "tomato", "gardening")):
                        out.append(list(garden_vec))
                    else:
                        out.append(list(other_vec))
                return out

        self.indexer = MemoryIndexer(self.mem, index, _Backend())
        self.mem.store(
            MemoryRecord(
                record_id="",
                category=MemoryCategory.RESEARCH,
                content="Python closures are a key language feature",
                source="test",
            )
        )
        self.indexer.store(
            MemoryRecord(
                record_id="",
                category=MemoryCategory.IDEAS,
                content="completely unrelated gardening notes about tomatoes",
                source="test",
            )
        )
        self.src = MemorySource(self.mem, indexer=self.indexer)

    def tearDown(self) -> None:
        self.mem.close()
        self.know.close()

    def test_fts_hit_keeps_baseline_confidence(self) -> None:
        from astra.research.source import MEMORY_HIT_CONFIDENCE
        findings = self.src.query("Python closures")
        fts = [f for f in findings if "closures" in f.claim]
        self.assertTrue(fts)
        self.assertEqual(MEMORY_HIT_CONFIDENCE, fts[0].confidence)

    def test_semantic_can_add_non_fts_hit(self) -> None:
        findings = self.src.query("horticulture")
        claims = [f.claim for f in findings]
        self.assertTrue(any("gardening" in c for c in claims))
        self.assertFalse(any("closures" in c for c in claims))

    def test_fts_still_runs_with_indexer(self) -> None:
        findings = self.src.query("Python closures")
        self.assertTrue(any("closures" in f.claim for f in findings))


# ---------------------------------------------------------------------------
# 24. allow_network=True accepted but no network calls made
# ---------------------------------------------------------------------------

class AllowNetworkTest(unittest.TestCase):
    def _block(self, *a, **kw):
        raise AssertionError("network access attempted")

    def test_allow_network_true_still_offline(self) -> None:
        agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT)])
        with patch.object(socket, "socket", side_effect=self._block), \
             patch.object(socket, "create_connection", side_effect=self._block), \
             patch.object(socket, "getaddrinfo", side_effect=self._block):
            report = agent.investigate("Python", allow_network=True)
        self.assertFalse(report.used_network)


# ---------------------------------------------------------------------------
# 25–26. summarise()
# ---------------------------------------------------------------------------

class SummariseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.agent = LocalResearchAgent(sources=[TextSource("t", SAMPLE_TEXT)])

    def test_summarise_no_llm_returns_result(self) -> None:
        findings = (Finding(claim="Python is dynamic", source="s", confidence=1.0),)
        result = self.agent.summarise(findings)
        self.assertIsInstance(result, AgentResult)
        self.assertTrue(result.success)

    def test_summarise_empty_findings_failure(self) -> None:
        result = self.agent.summarise(())
        self.assertFalse(result.success)

    def test_summarise_with_fake_llm(self) -> None:
        agent = LocalResearchAgent(
            sources=[TextSource("t", SAMPLE_TEXT)],
            llm=_FakeLLM(),
        )
        findings = agent.investigate("Python closures").findings
        result = agent.summarise(findings)
        self.assertTrue(result.success)
        self.assertTrue(result.details.get("synthesized", False))

    def test_summarise_deterministic_not_synthesized(self) -> None:
        findings = (Finding(claim="Python closures retain scope", source="s"),)
        result = self.agent.summarise(findings)
        self.assertFalse(result.details.get("synthesized", True))
        self.assertIn("Python closures retain scope", result.summary)


# ---------------------------------------------------------------------------
# 27. Multiple sources aggregated
# ---------------------------------------------------------------------------

class MultiSourceTest(unittest.TestCase):
    def test_findings_from_multiple_sources(self) -> None:
        src1 = TextSource("a", "Python closures retain variables from outer scope.")
        src2 = TextSource("b", "Python lambda expressions are anonymous functions.")
        agent = LocalResearchAgent(sources=[src1, src2])
        report = agent.investigate("Python")
        sources_seen = {f.source for f in report.findings}
        self.assertIn("a", sources_seen)
        self.assertIn("b", sources_seen)


# ---------------------------------------------------------------------------
# 28. add_source()
# ---------------------------------------------------------------------------

class AddSourceTest(unittest.TestCase):
    def test_add_source_increases_results(self) -> None:
        agent = LocalResearchAgent(sources=[])
        before = len(agent.investigate("Python").findings)
        agent.add_source(TextSource("new", SAMPLE_TEXT))
        after = len(agent.investigate("Python").findings)
        self.assertGreater(after, before)


# ---------------------------------------------------------------------------
# 30. Finding.retrieved_at
# ---------------------------------------------------------------------------

class FindingTimestampTest(unittest.TestCase):
    def test_retrieved_at_default_is_none(self) -> None:
        f = Finding(claim="x", source="s")
        self.assertIsNone(f.retrieved_at)

    def test_retrieved_at_can_be_set(self) -> None:
        from datetime import datetime, timezone
        now = datetime.now(timezone.utc)
        f = Finding(claim="x", source="s", retrieved_at=now)
        self.assertEqual(f.retrieved_at, now)


if __name__ == "__main__":
    unittest.main()
