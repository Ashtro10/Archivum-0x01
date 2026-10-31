"""Comprehensive tests for the Core Controller (B-007).

Coverage map (ADR-0010 requirements + spec items):

1.  Controller starts with valid settings
2.  Engines accessible after start
3.  Engines raise before start
4.  Idempotent start
5.  Shutdown is idempotent
6.  Context manager (start/shutdown)
7.  Partial start → shutdown cleans up
8.  Memory store operational after start
9.  Knowledge: classify and store through controller
10. Planning: create/list/plan tasks through controller
11. Vector index accessible (empty when no saved index)
12. LLM backend detected (unavailable on this machine w/o model)
13. Embedding backend detected
14. ApplicationState reflects real status
15. Partial availability represented accurately (missing model ≠ OPERATIONAL)
16. Shutdown closes resources cleanly
17. Agent registration
18. Agent dispatch
19. Duplicate agent registration raises
20. Unknown agent dispatch raises
21. Agent exception → AgentResult(success=False), not crash
22. Offline guarantee: start and orchestration produce no network calls
23. No reasoning import in controller startup path
24. ControllerStateProvider returns ApplicationState
25. Controller-backed state not started → NOT_OPERATIONAL
26. B-007 milestone check
27. Settings accessible via controller
28. Factory override (dependency injection)
29. Deep-model name preserved in state
30. Embedding model name preserved in state
31. test_interfaces compatibility: Controller still abstract
"""

from __future__ import annotations

import socket
import tempfile
import unittest
from pathlib import Path
from types import MappingProxyType
from unittest.mock import MagicMock, patch

from astra.configuration.settings import AstraSettings, MemorySettings
from astra.core.astra_controller import AstraController, ControllerStateProvider
from astra.core.contracts import Agent, AgentRequest, AgentResult
from astra.core.controller import Controller
from astra.core.errors import (
    AgentAlreadyRegisteredError,
    AgentNotFoundError,
    ControllerNotStartedError,
)
from astra.state import ApplicationState, CapabilityStatus


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _tmp_settings() -> AstraSettings:
    """Return AstraSettings pointing at fresh temporary database files."""
    from astra.configuration.settings import KnowledgeSettings, PlanningSettings
    mem_dir = tempfile.mkdtemp()
    know_dir = tempfile.mkdtemp()
    plan_dir = tempfile.mkdtemp()
    vec_dir = tempfile.mkdtemp()
    return AstraSettings(
        memory=MemorySettings(
            database_path=Path(mem_dir) / "mem.db",
            vector_index_path=Path(vec_dir) / "vectors.faiss",
        ),
        knowledge=KnowledgeSettings(
            database_path=Path(know_dir) / "knowledge.db",
        ),
        planning=PlanningSettings(
            database_path=Path(plan_dir) / "planning.db",
        ),
    )


def _ctrl(*, settings: AstraSettings | None = None) -> AstraController:
    """Return an initialised (started) controller with temp databases."""
    s = settings or _tmp_settings()
    return AstraController(s)


class _FakeAgent(Agent):
    name = "fake_agent"

    def run(self, request: AgentRequest) -> AgentResult:
        return AgentResult(agent=self.name, success=True, summary="fake ok")


class _RaisingAgent(Agent):
    name = "raising_agent"

    def run(self, request: AgentRequest) -> AgentResult:
        raise RuntimeError("intentional test error")


# ---------------------------------------------------------------------------
# 1–6. Lifecycle
# ---------------------------------------------------------------------------

class LifecycleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.ctrl = _ctrl()

    def tearDown(self) -> None:
        self.ctrl.shutdown()

    def test_starts_cleanly(self) -> None:
        self.ctrl.start()
        self.assertTrue(self.ctrl.started)

    def test_idempotent_start(self) -> None:
        self.ctrl.start()
        self.ctrl.start()  # second call is a no-op
        self.assertTrue(self.ctrl.started)

    def test_shutdown_before_start_safe(self) -> None:
        ctrl = _ctrl()
        ctrl.shutdown()  # Must not raise.
        self.assertFalse(ctrl.started)

    def test_double_shutdown_safe(self) -> None:
        self.ctrl.start()
        self.ctrl.shutdown()
        self.ctrl.shutdown()  # Must not raise.

    def test_context_manager(self) -> None:
        s = _tmp_settings()
        with AstraController(s) as ctrl:
            self.assertTrue(ctrl.started)
        self.assertFalse(ctrl.started)

    def test_started_flag_false_after_shutdown(self) -> None:
        self.ctrl.start()
        self.ctrl.shutdown()
        self.assertFalse(self.ctrl.started)


# ---------------------------------------------------------------------------
# 3. Engine access before start raises
# ---------------------------------------------------------------------------

class EnginePreStartTest(unittest.TestCase):
    def setUp(self) -> None:
        self.ctrl = _ctrl()  # NOT started yet

    def tearDown(self) -> None:
        self.ctrl.shutdown()

    def test_memory_before_start_raises(self) -> None:
        with self.assertRaises(ControllerNotStartedError):
            _ = self.ctrl.memory

    def test_knowledge_before_start_raises(self) -> None:
        with self.assertRaises(ControllerNotStartedError):
            _ = self.ctrl.knowledge

    def test_planning_before_start_raises(self) -> None:
        with self.assertRaises(ControllerNotStartedError):
            _ = self.ctrl.planning

    def test_planning_engine_before_start_raises(self) -> None:
        with self.assertRaises(ControllerNotStartedError):
            _ = self.ctrl.planning_engine

    def test_goals_before_start_raises(self) -> None:
        with self.assertRaises(ControllerNotStartedError):
            _ = self.ctrl.goals

    def test_security_before_start_raises(self) -> None:
        with self.assertRaises(ControllerNotStartedError):
            _ = self.ctrl.security

    def test_llm_before_start_raises(self) -> None:
        with self.assertRaises(ControllerNotStartedError):
            _ = self.ctrl.llm

    def test_embedding_before_start_raises(self) -> None:
        with self.assertRaises(ControllerNotStartedError):
            _ = self.ctrl.embedding

    def test_vector_index_before_start_raises(self) -> None:
        with self.assertRaises(ControllerNotStartedError):
            _ = self.ctrl.vector_index


# ---------------------------------------------------------------------------
# 8–11. Engine accessibility and basic orchestration
# ---------------------------------------------------------------------------

class EngineAccessTest(unittest.TestCase):
    def setUp(self) -> None:
        self.ctrl = _ctrl()
        self.ctrl.start()

    def tearDown(self) -> None:
        self.ctrl.shutdown()

    def test_memory_is_not_none(self) -> None:
        self.assertIsNotNone(self.ctrl.memory)

    def test_knowledge_is_not_none(self) -> None:
        self.assertIsNotNone(self.ctrl.knowledge)

    def test_planning_is_not_none(self) -> None:
        self.assertIsNotNone(self.ctrl.planning)

    def test_planning_engine_is_not_none(self) -> None:
        self.assertIsNotNone(self.ctrl.planning_engine)

    def test_goals_is_not_none(self) -> None:
        self.assertIsNotNone(self.ctrl.goals)

    def test_security_is_not_none(self) -> None:
        self.assertIsNotNone(self.ctrl.security)
        self.assertIn("security", self.ctrl.agent_names)

    def test_llm_is_not_none(self) -> None:
        self.assertIsNotNone(self.ctrl.llm)

    def test_embedding_is_not_none(self) -> None:
        self.assertIsNotNone(self.ctrl.embedding)

    def test_vector_index_is_not_none(self) -> None:
        self.assertIsNotNone(self.ctrl.vector_index)

    def test_memory_store_accepts_record(self) -> None:
        from astra.memory.contracts import MemoryCategory, MemoryRecord, VerificationState
        rec = MemoryRecord(
            record_id="",
            category=MemoryCategory.IDEAS,
            content="controller integration test memory record",
            verification=VerificationState.UNVERIFIED,
            source="test_controller",
        )
        rid = self.ctrl.memory.store(rec)
        self.assertTrue(rid)

    def test_knowledge_store_accepts_record(self) -> None:
        from astra.knowledge.contracts import (
            KnowledgeCategory, KnowledgeRecord, KnowledgeVerification
        )
        rec = KnowledgeRecord(
            record_id="",
            category=KnowledgeCategory.PROGRAMMING,
            content="Python closures retain outer scope variables",
            source="test_controller",
            confidence=1.0,
            verification=KnowledgeVerification.UNVERIFIED,
        )
        rid = self.ctrl.knowledge.store(rec)
        self.assertTrue(rid)

    def test_knowledge_classifier_works(self) -> None:
        from astra.knowledge.classifier import KeywordKnowledgeClassifier
        clf = KeywordKnowledgeClassifier()
        result = clf.classify("Python lambda function")
        from astra.knowledge.contracts import KnowledgeCategory
        self.assertEqual(result.category, KnowledgeCategory.PROGRAMMING)

    def test_planning_store_accepts_task(self) -> None:
        from astra.planning.contracts import Magnitude, Priority, RiskLevel, Task
        task = Task(
            task_id="",
            title="Controller integration task",
            priority=Priority.P2,
            impact=Magnitude.MEDIUM,
            effort=Magnitude.SMALL,
            risk=RiskLevel.LOW,
        )
        tid = self.ctrl.planning.add(task)
        self.assertTrue(tid)

    def test_planning_engine_next_actionable(self) -> None:
        from astra.planning.contracts import Magnitude, Priority, RiskLevel, Task
        task = Task(
            task_id="",
            title="Actionable task",
            priority=Priority.P0,
            impact=Magnitude.LARGE,
            effort=Magnitude.MEDIUM,
            risk=RiskLevel.MEDIUM,
        )
        self.ctrl.planning.add(task)
        next_t = self.ctrl.planning_engine.next_actionable()
        self.assertIsNotNone(next_t)

    def test_vector_index_starts_empty_without_saved_index(self) -> None:
        self.assertEqual(len(self.ctrl.vector_index), 0)

    def test_settings_accessible(self) -> None:
        s = self.ctrl.settings
        self.assertIsNotNone(s)
        self.assertEqual(s.reasoning.model, "Qwen2.5-3B-Instruct-Q5_K_M")


# ---------------------------------------------------------------------------
# 12–13. Reasoning backends
# ---------------------------------------------------------------------------

class ReasoningBackendTest(unittest.TestCase):
    def setUp(self) -> None:
        self.ctrl = _ctrl()
        self.ctrl.start()

    def tearDown(self) -> None:
        self.ctrl.shutdown()

    def test_llm_model_name_is_set(self) -> None:
        name = self.ctrl.llm.model_name
        self.assertTrue(name)

    def test_embedding_model_name_is_set(self) -> None:
        name = self.ctrl.embedding.model_name
        self.assertTrue(name)

    def test_llm_is_available_returns_bool(self) -> None:
        result = self.ctrl.llm.is_available()
        self.assertIsInstance(result, bool)

    def test_embedding_is_available_returns_bool(self) -> None:
        result = self.ctrl.embedding.is_available()
        self.assertIsInstance(result, bool)

    def test_missing_model_file_not_available(self) -> None:
        """Without a real GGUF file, is_available() must return False."""
        # Default settings have model_path=None → not available.
        self.assertFalse(self.ctrl.llm.is_available())
        self.assertFalse(self.ctrl.embedding.is_available())


# ---------------------------------------------------------------------------
# 14–15. ApplicationState / capability truthfulness
# ---------------------------------------------------------------------------

class ApplicationStateTest(unittest.TestCase):
    def test_state_before_start_does_not_show_operational(self) -> None:
        ctrl = _ctrl()
        state = ctrl.get_state()
        for mod in state.modules:
            if "memory" in mod.module or "knowledge" in mod.module:
                self.assertNotEqual(mod.status, CapabilityStatus.OPERATIONAL)
        ctrl.shutdown()

    def test_state_after_start_shows_memory_operational(self) -> None:
        ctrl = _ctrl()
        ctrl.start()
        state = ctrl.get_state()
        mem = next((m for m in state.modules if m.module == "astra.memory"), None)
        self.assertIsNotNone(mem)
        assert mem is not None
        self.assertEqual(mem.status, CapabilityStatus.OPERATIONAL)
        ctrl.shutdown()

    def test_state_after_start_shows_knowledge_operational(self) -> None:
        ctrl = _ctrl()
        ctrl.start()
        state = ctrl.get_state()
        know = next((m for m in state.modules if m.module == "astra.knowledge"), None)
        self.assertIsNotNone(know)
        assert know is not None
        self.assertEqual(know.status, CapabilityStatus.OPERATIONAL)
        ctrl.shutdown()

    def test_state_after_start_shows_planning_operational(self) -> None:
        ctrl = _ctrl()
        ctrl.start()
        state = ctrl.get_state()
        plan = next((m for m in state.modules if m.module == "astra.planning"), None)
        self.assertIsNotNone(plan)
        assert plan is not None
        self.assertEqual(plan.status, CapabilityStatus.OPERATIONAL)
        gm = next((c for c in plan.capabilities if c.name == "goal_manager"), None)
        self.assertIsNotNone(gm)
        assert gm is not None
        self.assertEqual(gm.status, CapabilityStatus.OPERATIONAL)
        self.assertEqual(6, state.schema_version)
        ctrl.shutdown()

    def test_missing_model_not_shown_as_available(self) -> None:
        """model_path=None → reasoning must not be AVAILABLE or OPERATIONAL."""
        ctrl = _ctrl()
        ctrl.start()
        state = ctrl.get_state()
        rea = next((m for m in state.modules if m.module == "astra.reasoning"), None)
        self.assertIsNotNone(rea)
        assert rea is not None
        llm_cap = next(
            (c for c in rea.capabilities if c.name == "llm_backend"), None
        )
        self.assertIsNotNone(llm_cap)
        assert llm_cap is not None
        self.assertNotIn(
            llm_cap.status,
            (CapabilityStatus.AVAILABLE, CapabilityStatus.OPERATIONAL),
            "LLM backend must not show as available when model file is absent",
        )
        ctrl.shutdown()

    def test_state_includes_timestamps(self) -> None:
        ctrl = _ctrl()
        ctrl.start()
        state = ctrl.get_state()
        ctrl.shutdown()
        self.assertIn("T", state.timestamp_iso)

    def test_state_preserves_model_labels(self) -> None:
        ctrl = _ctrl()
        ctrl.start()
        state = ctrl.get_state()
        ctrl.shutdown()
        self.assertEqual(state.active_model, "Qwen2.5-3B-Instruct-Q5_K_M")
        self.assertEqual(state.deep_model, "")
        self.assertEqual(state.embedding_model, "nomic-embed-text-v1.5")

    def test_state_returns_application_state_type(self) -> None:
        ctrl = _ctrl()
        state = ctrl.get_state()
        ctrl.shutdown()
        self.assertIsInstance(state, ApplicationState)

    def test_controller_state_provider(self) -> None:
        ctrl = _ctrl()
        ctrl.start()
        provider = ControllerStateProvider(ctrl)
        state = provider.get_state()
        ctrl.shutdown()
        self.assertIsInstance(state, ApplicationState)


# ---------------------------------------------------------------------------
# 17–21. Agent registry and dispatch
# ---------------------------------------------------------------------------

class AgentRegistryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.ctrl = _ctrl()
        self.ctrl.start()

    def tearDown(self) -> None:
        self.ctrl.shutdown()

    def test_register_and_dispatch(self) -> None:
        self.ctrl.register_agent("fake_agent", _FakeAgent())
        req = AgentRequest(objective="test")
        result = self.ctrl.dispatch("fake_agent", req)
        self.assertTrue(result.success)
        self.assertEqual(result.agent, "fake_agent")

    def test_dispatch_unknown_agent_raises(self) -> None:
        with self.assertRaises(AgentNotFoundError):
            self.ctrl.dispatch("nonexistent", AgentRequest(objective="x"))

    def test_duplicate_registration_raises(self) -> None:
        self.ctrl.register_agent("dup", _FakeAgent())
        with self.assertRaises(AgentAlreadyRegisteredError):
            self.ctrl.register_agent("dup", _FakeAgent())

    def test_agent_exception_returns_failure_result(self) -> None:
        self.ctrl.register_agent("raising", _RaisingAgent())
        result = self.ctrl.dispatch("raising", AgentRequest(objective="x"))
        self.assertFalse(result.success)
        self.assertIn("intentional test error", result.summary)

    def test_agent_failure_does_not_crash_controller(self) -> None:
        self.ctrl.register_agent("raiser2", _RaisingAgent())
        # Should not raise, should not crash memory or other engines
        self.ctrl.dispatch("raiser2", AgentRequest(objective="x"))
        # Memory still works
        self.assertIsNotNone(self.ctrl.memory)

    def test_unregister_agent(self) -> None:
        self.ctrl.register_agent("temp", _FakeAgent())
        self.ctrl.unregister_agent("temp")
        with self.assertRaises(AgentNotFoundError):
            self.ctrl.dispatch("temp", AgentRequest(objective="x"))

    def test_agent_names_sorted(self) -> None:
        self.ctrl.register_agent("z_agent", _FakeAgent())
        self.ctrl.register_agent("a_agent", _FakeAgent())
        names = self.ctrl.agent_names
        self.assertEqual(list(names), sorted(names))


# ---------------------------------------------------------------------------
# 22. Offline guarantee
# ---------------------------------------------------------------------------

class OfflineControllerTest(unittest.TestCase):
    def _block(self, *a: object, **kw: object) -> None:
        raise AssertionError("network access attempted")

    def test_start_produces_no_network_calls(self) -> None:
        ctrl = _ctrl()
        with patch.object(socket, "socket", side_effect=self._block), \
             patch.object(socket, "create_connection", side_effect=self._block), \
             patch.object(socket, "getaddrinfo", side_effect=self._block):
            ctrl.start()
        self.assertTrue(ctrl.started)
        ctrl.shutdown()

    def test_get_state_produces_no_network_calls(self) -> None:
        ctrl = _ctrl()
        ctrl.start()
        with patch.object(socket, "socket", side_effect=self._block), \
             patch.object(socket, "create_connection", side_effect=self._block), \
             patch.object(socket, "getaddrinfo", side_effect=self._block):
            state = ctrl.get_state()
        ctrl.shutdown()
        self.assertIsNotNone(state)

    def test_no_network_imports_in_controller_module(self) -> None:
        import ast
        ctrl_path = (
            Path(__file__).parent.parent / "astra" / "core" / "astra_controller.py"
        )
        source = ctrl_path.read_text(encoding="utf-8")
        tree = ast.parse(source)
        network_libs = {"httpx", "requests", "urllib3", "aiohttp"}
        for node in ast.walk(tree):
            if isinstance(node, (ast.Import, ast.ImportFrom)):
                names = (
                    [a.name for a in node.names]
                    if isinstance(node, ast.Import)
                    else ([node.module] if node.module else [])
                )
                for name in names:
                    root = (name or "").split(".")[0]
                    with self.subTest(library=root):
                        self.assertNotIn(root, network_libs)


# ---------------------------------------------------------------------------
# 23. No reasoning import at startup (before is_available probe)
# ---------------------------------------------------------------------------

class NoImplicitModelLoadTest(unittest.TestCase):
    def test_start_does_not_invoke_generate(self) -> None:
        """start() must not call generate() or load any GGUF."""
        ctrl = _ctrl()
        original_generate = None
        try:
            from astra.reasoning.llama_cpp_backend import LlamaCppLLMBackend
            original_generate = LlamaCppLLMBackend.generate

            calls = []

            def spy_generate(self, *a, **kw):
                calls.append("generate called")
                return original_generate(self, *a, **kw)

            LlamaCppLLMBackend.generate = spy_generate  # type: ignore[method-assign]
            ctrl.start()
            ctrl.shutdown()
            self.assertEqual(calls, [], "generate() must not be called during start()")
        finally:
            if original_generate is not None:
                from astra.reasoning.llama_cpp_backend import LlamaCppLLMBackend
                LlamaCppLLMBackend.generate = original_generate  # type: ignore[method-assign]


# ---------------------------------------------------------------------------
# 26. B-007 milestone
# ---------------------------------------------------------------------------

class MilestoneTest(unittest.TestCase):
    def test_b007_milestone_is_complete(self) -> None:
        from astra.presentation.progress import MILESTONES
        b007 = next((m for m in MILESTONES if m.id == "B-007"), None)
        self.assertIsNotNone(b007)
        assert b007 is not None
        self.assertTrue(
            b007.complete(),
            "B-007 milestone check must return True now that astra_controller exists",
        )

    def test_b007_in_completed_stages(self) -> None:
        ctrl = _ctrl()
        state = ctrl.get_state()
        ctrl.shutdown()
        self.assertIn("B-007", state.completed_stages)


# ---------------------------------------------------------------------------
# 28. Dependency injection via factory overrides
# ---------------------------------------------------------------------------

class DependencyInjectionTest(unittest.TestCase):
    def test_fake_memory_store_used_when_injected(self) -> None:
        from astra.memory.contracts import MemoryStore

        class FakeMemoryStore(MemoryStore):
            initialised = False
            closed = False

            def initialise(self) -> None:
                self.initialised = True

            def store(self, record):  # type: ignore[override]
                return "fake-id"

            def get(self, record_id):  # type: ignore[override]
                return None

            def search(self, query, *, category=None, limit=20):  # type: ignore[override]
                return ()

            def iter_records(self, *, category=None):  # type: ignore[override]
                return iter([])

            def delete(self, record_id):  # type: ignore[override]
                return False

            def close(self) -> None:
                self.closed = True

            def _require_connection(self):
                pass  # Needed for ControllerStateProvider

        fake_mem = FakeMemoryStore()
        s = _tmp_settings()
        ctrl = AstraController(s, memory_factory=lambda _: fake_mem)
        ctrl.start()

        self.assertTrue(fake_mem.initialised)
        self.assertIs(ctrl.memory, fake_mem)

        ctrl.shutdown()
        self.assertTrue(fake_mem.closed)

    def test_partial_injection_uses_defaults_for_rest(self) -> None:
        from astra.memory.sqlite_store import SQLiteMemoryStore

        s = _tmp_settings()
        ctrl = AstraController(s, memory_factory=lambda cfg: SQLiteMemoryStore(cfg.memory.database_path))
        ctrl.start()
        self.assertIsNotNone(ctrl.memory)
        self.assertIsNotNone(ctrl.knowledge)
        ctrl.shutdown()


class MemoryIndexerLifecycleTest(unittest.TestCase):
    """ADR-0012: persist/load, no embed on start, explicit rebuild."""

    def _counting_embed(self, *, available: bool = True):
        import hashlib

        class _Counting:
            def __init__(self) -> None:
                self.embed_calls = 0
                self._available = available

            @property
            def model_name(self) -> str:
                return "nomic-embed-text-v1.5"

            @property
            def dimension(self) -> int:
                return 768

            def is_available(self) -> bool:
                return self._available

            def embed(self, texts):
                self.embed_calls += 1
                out = []
                for t in texts:
                    digest = hashlib.sha256(t.encode("utf-8")).digest()
                    out.append(
                        [(digest[i % 32] / 255.0) + 0.01 for i in range(768)]
                    )
                return out

        return _Counting()

    def test_memory_indexer_before_start_raises(self) -> None:
        ctrl = _ctrl()
        with self.assertRaises(ControllerNotStartedError):
            _ = ctrl.memory_indexer
        ctrl.shutdown()

    def test_start_does_not_call_embed(self) -> None:
        backend = self._counting_embed()
        s = _tmp_settings()
        ctrl = AstraController(s, embed_factory=lambda _: backend)
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        self.assertEqual(0, backend.embed_calls)
        self.assertIsNotNone(ctrl.memory_indexer)

    def test_shutdown_persists_and_next_start_loads(self) -> None:
        from astra.memory.contracts import MemoryCategory, MemoryRecord

        backend = self._counting_embed()
        s = _tmp_settings()
        ctrl = AstraController(s, embed_factory=lambda _: backend)
        ctrl.start()
        rec = MemoryRecord(
            record_id="",
            category=MemoryCategory.IDEAS,
            content="controller persist round trip",
            source="test",
        )
        rid = ctrl.memory_indexer.store(rec)
        self.assertGreater(backend.embed_calls, 0)
        self.assertEqual(1, len(ctrl.vector_index))
        ctrl.shutdown()

        backend2 = self._counting_embed()
        ctrl2 = AstraController(s, embed_factory=lambda _: backend2)
        ctrl2.start()
        self.addCleanup(ctrl2.shutdown)
        self.assertEqual(0, backend2.embed_calls)
        self.assertEqual(1, len(ctrl2.vector_index))
        hits = ctrl2.memory_indexer.search("controller persist round trip", limit=1)
        self.assertEqual(1, len(hits))
        self.assertEqual(rid, hits[0].record_id)

    def test_rebuild_unavailable_does_not_clear(self) -> None:
        from astra.memory.contracts import MemoryCategory, MemoryRecord
        from astra.reasoning.backends import BackendUnavailableError

        backend = self._counting_embed()
        s = _tmp_settings()
        ctrl = AstraController(s, embed_factory=lambda _: backend)
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        ctrl.memory_indexer.store(
            MemoryRecord(
                record_id="",
                category=MemoryCategory.IDEAS,
                content="keep this vector",
                source="test",
            )
        )
        self.assertEqual(1, len(ctrl.vector_index))
        backend._available = False
        with self.assertRaises(BackendUnavailableError):
            ctrl.rebuild_memory_index()
        self.assertEqual(1, len(ctrl.vector_index))

    def test_vector_index_status_configured_when_empty_without_backend(self) -> None:
        ctrl = _ctrl()
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        state = ctrl.get_state()
        mem = next(m for m in state.modules if m.module == "astra.memory")
        vec = next(c for c in mem.capabilities if c.name == "vector_index")
        self.assertEqual(CapabilityStatus.CONFIGURED, vec.status)
        self.assertEqual(0, vec.metadata.get("active_vectors"))
        self.assertNotEqual(CapabilityStatus.OPERATIONAL, vec.status)

    def test_vector_index_operational_when_populated_and_backend_up(self) -> None:
        from astra.memory.contracts import MemoryCategory, MemoryRecord

        backend = self._counting_embed()
        s = _tmp_settings()
        ctrl = AstraController(s, embed_factory=lambda _: backend)
        ctrl.start()
        self.addCleanup(ctrl.shutdown)
        ctrl.memory_indexer.store(
            MemoryRecord(
                record_id="",
                category=MemoryCategory.IDEAS,
                content="live index status",
                source="test",
            )
        )
        state = ctrl.get_state()
        mem = next(m for m in state.modules if m.module == "astra.memory")
        vec = next(c for c in mem.capabilities if c.name == "vector_index")
        self.assertEqual(CapabilityStatus.OPERATIONAL, vec.status)
        self.assertEqual(1, vec.metadata.get("active_vectors"))


# ---------------------------------------------------------------------------
# 31. Controller abstract contract still respected
# ---------------------------------------------------------------------------

class ContractTest(unittest.TestCase):
    def test_astra_controller_is_controller(self) -> None:
        self.assertTrue(issubclass(AstraController, Controller))

    def test_astra_controller_instance(self) -> None:
        ctrl = _ctrl()
        self.assertIsInstance(ctrl, Controller)
        ctrl.shutdown()

    def test_abstract_controller_not_instantiable(self) -> None:
        with self.assertRaises(TypeError):
            Controller()  # type: ignore[abstract]


if __name__ == "__main__":
    unittest.main()
