"""Concrete Core Controller (B-007, ADR-0010).

``AstraController`` is the **only** module permitted to construct concrete
engine implementations and wire them together (architecture.md rule).  Every
other package depends on interfaces.

Lifecycle::

    ctrl = AstraController(settings)
    ctrl.start()           # open databases, probe backends, load index
    ...                    # use engines via ctrl.*
    ctrl.shutdown()        # close connections, release resources

Dependency injection via factory overrides::

    ctrl = AstraController(
        settings,
        memory_factory=lambda s: FakeMemoryStore(),
    )

No network I/O anywhere in this module.  No model loading at startup.
"""

from __future__ import annotations

import json
import traceback
from pathlib import Path
from typing import Any, Callable, Mapping

from astra.configuration.settings import AstraSettings
from astra.core.contracts import Agent, AgentRequest, AgentResult
from astra.core.controller import Controller
from astra.core.errors import (
    AgentAlreadyRegisteredError,
    AgentNotFoundError,
    ControllerNotStartedError,
)
from astra.logging import get_logger
from astra.state import (
    ApplicationState,
    CapabilityInfo,
    CapabilityStatus,
    CodingSnapshot,
    GoalSnapshot,
    ModuleStatus,
    SecuritySnapshot,
    StateProvider,
    SystemInfo,
    TestStatus,
    ToolSnapshot,
)

_LOGGER = get_logger(__name__)
_CS = CapabilityStatus


# ---------------------------------------------------------------------------
# Factory type aliases
# ---------------------------------------------------------------------------

_MemoryFactory = Callable[["AstraSettings"], Any]
_KnowledgeFactory = Callable[["AstraSettings"], Any]
_PlanningFactory = Callable[["AstraSettings"], Any]
_LLMFactory = Callable[["AstraSettings"], Any]
_EmbedFactory = Callable[["AstraSettings"], Any]
_VectorFactory = Callable[["AstraSettings"], Any]
_SecurityFactory = Callable[["AstraSettings"], Any]
_ToolsFactory = Callable[["AstraSettings"], Any]


# ---------------------------------------------------------------------------
# Default factories (use concrete implementations)
# ---------------------------------------------------------------------------

def _default_memory(settings: AstraSettings) -> Any:
    from astra.memory.sqlite_store import SQLiteMemoryStore
    return SQLiteMemoryStore(settings.memory.database_path, allow_cross_thread=True)


def _default_knowledge(settings: AstraSettings) -> Any:
    from astra.knowledge.sqlite_store import SQLiteKnowledgeStore
    return SQLiteKnowledgeStore(settings.knowledge.database_path, allow_cross_thread=True)


def _default_planning(settings: AstraSettings) -> Any:
    from astra.planning.sqlite_store import SQLitePlanningStore
    return SQLitePlanningStore(settings.planning.database_path)


def _default_llm(settings: AstraSettings) -> Any:
    from astra.reasoning.llama_cpp_backend import LlamaCppLLMBackend
    return LlamaCppLLMBackend.from_settings(settings.reasoning)


def _default_deep_llm(settings: AstraSettings) -> Any | None:
    from astra.reasoning.deep_llm import build_deep_llm
    return build_deep_llm(settings)


def _default_embed(settings: AstraSettings) -> Any:
    from astra.reasoning.llama_cpp_backend import LlamaCppEmbeddingBackend
    return LlamaCppEmbeddingBackend.from_settings(settings.reasoning)


def _default_security(settings: AstraSettings) -> Any:
    from astra.security.agent import PolicySecurityAgent
    return PolicySecurityAgent.from_settings(settings)


def _default_tools(settings: AstraSettings) -> Any:
    from astra.tools.capture_screen import CaptureScreenTool
    from astra.tools.inspect_active_window import InspectActiveWindowTool
    from astra.tools.inspect_process import InspectProcessTool
    from astra.tools.keyboard_action import KeyboardActionTool
    from astra.tools.mouse_action import MouseActionTool
    from astra.tools.launch_application import LaunchApplicationTool
    from astra.tools.read_project import ReadProjectTool
    from astra.tools.read_text import ReadTextTool, read_roots_from_settings
    from astra.tools.registry import InMemoryToolRegistry
    from astra.tools.run_python import RunPythonTool
    from astra.tools.search_files import SearchFilesTool
    from astra.tools.write_text import WriteTextTool

    registry = InMemoryToolRegistry()
    registry.register(ReadTextTool(read_roots=read_roots_from_settings(settings)))
    registry.register(ReadProjectTool.from_settings(settings))
    registry.register(SearchFilesTool.from_settings(settings))
    registry.register(WriteTextTool.from_settings(settings))
    registry.register(RunPythonTool.from_settings(settings))
    registry.register(LaunchApplicationTool.from_settings(settings))
    registry.register(InspectProcessTool.from_settings(settings))
    registry.register(CaptureScreenTool.from_settings(settings))
    registry.register(InspectActiveWindowTool.from_settings(settings))
    registry.register(MouseActionTool.from_settings(settings))
    registry.register(KeyboardActionTool.from_settings(settings))
    return registry


def _default_vector(settings: AstraSettings) -> Any:
    from astra.memory.faiss_index import FaissVectorIndex
    return FaissVectorIndex(
        dimension=settings.reasoning.embedding_dim,
        embedding_model=settings.reasoning.embedding_model,
    )


# ---------------------------------------------------------------------------
# AstraController
# ---------------------------------------------------------------------------

class AstraController(Controller):
    """Wires all implemented engines and exposes them through a single seam.

    ``__init__`` performs no I/O. ``start()`` opens databases and probes
    backends (ADR-0010 D1). Optional factory overrides replace concrete
    implementations for testing (ADR-0010 D1 / DI note).
    """

    def __init__(
        self,
        settings: AstraSettings | None = None,
        *,
        memory_factory: _MemoryFactory | None = None,
        knowledge_factory: _KnowledgeFactory | None = None,
        planning_factory: _PlanningFactory | None = None,
        llm_factory: _LLMFactory | None = None,
        deep_llm_factory: _LLMFactory | None = None,
        embed_factory: _EmbedFactory | None = None,
        vector_factory: _VectorFactory | None = None,
        security_factory: _SecurityFactory | None = None,
        tools_factory: _ToolsFactory | None = None,
    ) -> None:
        self._settings: AstraSettings = settings or AstraSettings()

        self._mf = memory_factory or _default_memory
        self._kf = knowledge_factory or _default_knowledge
        self._pf = planning_factory or _default_planning
        self._lf = llm_factory or _default_llm
        self._dlf = deep_llm_factory or _default_deep_llm
        self._ef = embed_factory or _default_embed
        self._vf = vector_factory or _default_vector
        self._sf = security_factory or _default_security
        self._tf = tools_factory or _default_tools

        self._memory: Any | None = None
        self._knowledge: Any | None = None
        self._classifier: Any | None = None
        self._planning: Any | None = None
        self._planning_engine: Any | None = None
        self._llm: Any | None = None
        self._deep_llm: Any | None = None
        self._embedding: Any | None = None
        self._vector_index: Any | None = None
        self._indexer: Any | None = None
        self._goals: Any | None = None
        self._security: Any | None = None
        self._tools: Any | None = None
        self._tool_invoker: Any | None = None
        self._workspace: Any | None = None
        self._subset_workspace: Any | None = None
        self._process_runner: Any | None = None
        self._version_manager: Any | None = None
        self._git_store: Any | None = None
        self._plugin_registry: Any | None = None
        self._baseline_store: Any | None = None
        self._agents: dict[str, Agent] = {}
        self._agent_loop: Any | None = None
        self._started: bool = False
        self._index_needs_rebuild: bool = False
        self._last_persist_iso: str = ""

    # ------------------------------------------------------------------
    # lifecycle
    # ------------------------------------------------------------------

    def start(self) -> None:
        """Open databases, detect backends, and load the vector index.

        Idempotent — calling ``start()`` on an already-started controller
        is a no-op. Raises on genuine initialisation failures; leaves
        ``_started = False`` until the full sequence completes so that
        ``shutdown()`` knows what to clean up.
        """
        if self._started:
            _LOGGER.debug("controller already started; ignoring duplicate start()")
            return

        _LOGGER.info("starting AstraController")
        s = self._settings

        try:
            # 1. Memory store
            self._memory = self._mf(s)
            self._memory.initialise()
            _LOGGER.info("memory store initialised: %s", s.memory.database_path)

            # 2. Knowledge store
            self._knowledge = self._kf(s)
            self._knowledge.initialise()
            from astra.knowledge.classifier import KeywordKnowledgeClassifier

            self._classifier = KeywordKnowledgeClassifier()
            _LOGGER.debug("knowledge store initialised: %s", s.knowledge.database_path)

            # 3. Planning store + engine
            self._planning = self._pf(s)
            self._planning.initialise()
            from astra.planning.engine import SimplePlanningEngine
            self._planning_engine = SimplePlanningEngine(self._planning)
            _LOGGER.debug("planning store initialised: %s", s.planning.database_path)

            # 4. Reasoning backends (constructed but NOT loaded)
            self._llm = self._lf(s)
            self._deep_llm = self._dlf(s)
            self._embedding = self._ef(s)
            llm_avail = self._llm.is_available()
            deep_avail = (
                self._deep_llm.is_available() if self._deep_llm is not None else False
            )
            emb_avail = self._embedding.is_available()
            _LOGGER.debug(
                "reasoning: llm_available=%s deep_available=%s embed_available=%s",
                llm_avail,
                deep_avail,
                emb_avail,
            )

            # 5. Vector index — load if saved, empty otherwise. Never embed here
            #    (ADR-0012 D6 / ADR-0010 D2).
            self._vector_index = self._vf(s)
            self._index_needs_rebuild = False
            idx_path = Path(s.memory.vector_index_path)
            index_file = idx_path / "index.faiss"
            if index_file.is_file():
                try:
                    self._vector_index.load(idx_path)
                    _LOGGER.debug("vector index loaded from %s", idx_path)
                    meta_path = idx_path / "meta.json"
                    if meta_path.is_file():
                        try:
                            meta = json.loads(meta_path.read_text(encoding="utf-8"))
                            self._last_persist_iso = str(meta.get("saved_at") or "")
                        except Exception:
                            self._last_persist_iso = ""
                except Exception as exc:  # noqa: BLE001
                    _LOGGER.warning(
                        "vector index at %s could not be loaded (%s); "
                        "starting empty — call rebuild_memory_index() when ready",
                        idx_path, exc,
                    )
                    self._vector_index = self._vf(s)
                    self._index_needs_rebuild = True
            else:
                _LOGGER.debug(
                    "vector index not found at %s; starting empty", idx_path
                )

            # 6. Memory indexer (ADR-0012). Constructed, not exercised.
            from astra.memory.indexer import MemoryIndexer
            self._indexer = MemoryIndexer(
                self._memory,
                self._vector_index,
                self._embedding,
                embedding_context_tokens=s.reasoning.embedding_context_tokens,
            )
            if len(self._vector_index) == 0 and self._store_has_records(self._memory):
                self._index_needs_rebuild = True

            # 7. Goal manager (ADR-0013). After stores + indexer exist.
            from astra.planning.goal_manager import SQLiteGoalManager
            self._goals = SQLiteGoalManager(
                s.planning.database_path,
                backlog=self._planning,
                memory=self._memory,
                knowledge=self._knowledge,
                indexer=self._indexer,
            )
            self._goals.initialise()
            _LOGGER.debug("goal manager initialised")

            # 8. Security agent (ADR-0014). Policy only; no execution.
            self._security = self._sf(s)
            self._agents["security"] = self._security
            _LOGGER.debug("security agent registered")

            # 8b. Proposal overlay workspace (ADR-0020). Not an agent.
            from astra.configuration.paths import project_root
            from astra.testing.workspace import ProposalWorkspace

            testing = getattr(s, "testing", None)
            scratch = (
                getattr(testing, "scratch_directory", None)
                if testing is not None
                else None
            )
            self._workspace = ProposalWorkspace(
                security=self._security,
                scratch_root=scratch,
                project_root=project_root(),
            )
            _LOGGER.debug("proposal workspace constructed; not registered as agent")

            # 8c. Copied-subset workspace (ADR-0022). Not an agent. Layout only.
            from astra.testing.subset import CopiedSubsetWorkspace

            self._subset_workspace = CopiedSubsetWorkspace(
                security=self._security,
                scratch_root=scratch,
                project_root=project_root(),
            )
            _LOGGER.debug("copied-subset workspace constructed; not registered as agent")

            # 8d. Process runner (ADR-0023). Sole spawn boundary. Not an agent.
            from astra.testing.runner import ProcessRunner

            self._process_runner = ProcessRunner(
                security=self._security,
                subset=self._subset_workspace,
            )
            _LOGGER.debug("process runner constructed; spawn requires a subset session")

            # 9. Tool catalog + gated invoker (ADR-0015, ADR-0016, ADR-0017).
            #    Production constant stays {read_text, read_project}.
            #    Agent loop uses AGENT_EXECUTION_ALLOWLIST (search/write/python/apps).
            from astra.tools.allowlist import AGENT_EXECUTION_ALLOWLIST
            from astra.tools.gate import GatedToolInvoker
            self._tools = self._tf(s)
            self._bind_python_runner()
            self._bind_application_tools()
            self._bind_perception_tools()
            self._bind_input_tools()
            self._tool_invoker = GatedToolInvoker(
                self._tools,
                security=self._security,
                execution_allowlist=AGENT_EXECUTION_ALLOWLIST,
            )
            _LOGGER.debug(
                "tool registry owned; agent execution allowlist=%s",
                sorted(AGENT_EXECUTION_ALLOWLIST),
            )

            # 10. Register research agent (ADR-0011 D8, ADR-0012 D11, ADR-0014 D8)
            self._register_research_agent()

            # 11. Register coding agent (ADR-0018). Proposal only. Gets invoker, not registry.
            self._register_coding_agent()

            # 12. Register testing agent (ADR-0024). Proposal tests only. Gets subset + runner.
            self._register_testing_agent()

            # 13. Register evaluation agent (ADR-0025). DTO judgement only. No spawn, no tools.
            self._register_evaluation_agent()

            # 14. Register deployment agent (ADR-0026). Permanent apply; needs ApprovalRecord.
            self._register_deployment_agent()

            # 15. Plugin registry (ADR-0033). Metadata only. Does not execute plugins.
            self._register_plugin_registry()

            # 16. Bounded agent loop. Read-only tools. Never deploys.
            self._register_agent_loop()

        except Exception:
            # Partial start: clean up whatever opened before failure.
            _LOGGER.error("controller start() failed; shutting down partial state")
            self._partial_shutdown()
            raise

        self._started = True
        _LOGGER.info("AstraController started")

    def shutdown(self) -> None:
        """Close all open resources. Safe to call before start or multiple times."""
        self._partial_shutdown()
        self._started = False
        _LOGGER.info("AstraController shut down")

    def _partial_shutdown(self) -> None:
        """Close whatever is open, tolerating None and already-closed stores."""
        if self._vector_index is not None:
            try:
                path = Path(self._settings.memory.vector_index_path)
                self._vector_index.save(path)
                self._last_persist_iso = self._utc_now_iso()
                _LOGGER.debug("vector index saved to %s", path)
            except Exception as exc:  # noqa: BLE001
                _LOGGER.warning("error saving vector index: %s", exc)
        self._indexer = None
        if self._goals is not None:
            try:
                self._goals.close()
            except Exception as exc:  # noqa: BLE001
                _LOGGER.warning("error closing goal manager: %s", exc)
            self._goals = None
        for attr in ("_memory", "_knowledge", "_planning"):
            obj = getattr(self, attr, None)
            if obj is not None:
                try:
                    obj.close()
                except Exception as exc:  # noqa: BLE001
                    _LOGGER.warning("error closing %s: %s", attr, exc)
                setattr(self, attr, None)
        # FaissVectorIndex has no close(); just drop the reference.
        self._vector_index = None
        self._llm = None
        self._deep_llm = None
        self._embedding = None
        self._planning_engine = None
        if self._workspace is not None:
            try:
                self._workspace.discard_all()
            except Exception as exc:  # noqa: BLE001
                _LOGGER.warning("error discarding proposal workspace: %s", exc)
            self._workspace = None
        if self._subset_workspace is not None:
            try:
                self._subset_workspace.discard_all()
            except Exception as exc:  # noqa: BLE001
                _LOGGER.warning("error discarding copied-subset workspace: %s", exc)
            self._subset_workspace = None
        self._process_runner = None
        self._version_manager = None
        self._git_store = None
        self._plugin_registry = None
        self._baseline_store = None
        self._security = None
        self._tools = None
        self._tool_invoker = None
        self._agent_loop = None

    @staticmethod
    def _utc_now_iso() -> str:
        from datetime import datetime, timezone
        return datetime.now(timezone.utc).isoformat()

    @staticmethod
    def _store_has_records(store: Any) -> bool:
        try:
            return next(iter(store.iter_records()), None) is not None
        except Exception:
            return False

    def __enter__(self) -> "AstraController":
        self.start()
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.shutdown()

    @property
    def started(self) -> bool:
        return self._started

    # ------------------------------------------------------------------
    # engine accessors
    # ------------------------------------------------------------------

    def _require_started(self) -> None:
        if not self._started:
            raise ControllerNotStartedError(
                "controller not started; call start() first"
            )

    @property
    def memory(self) -> Any:
        """Active :class:`~astra.memory.MemoryStore`."""
        self._require_started()
        return self._memory

    @property
    def knowledge(self) -> Any:
        """Active :class:`~astra.knowledge.KnowledgeStore`."""
        self._require_started()
        return self._knowledge

    def ingest_knowledge(self, content: str, *, source: str = "conversation") -> str:
        """Classify and store knowledge. Empty string if duplicate or unavailable."""
        self._require_started()
        if self._knowledge is None or self._classifier is None:
            return ""
        from astra.knowledge.ingest import ingest_text

        return ingest_text(
            self._knowledge,
            self._classifier,
            content,
            source=source,
            note="conversation ingest",
        )

    @property
    def planning(self) -> Any:
        """Active :class:`~astra.planning.Backlog`."""
        self._require_started()
        return self._planning

    @property
    def planning_engine(self) -> Any:
        """Active :class:`~astra.planning.PlanningEngine`."""
        self._require_started()
        return self._planning_engine

    @property
    def goals(self) -> Any:
        """Active :class:`~astra.planning.goals.GoalManager`."""
        self._require_started()
        return self._goals

    @property
    def security(self) -> Any:
        """Active :class:`~astra.security.contracts.SecurityAgent`."""
        self._require_started()
        return self._security

    @property
    def proposal_workspace(self) -> Any:
        """Active proposal overlay workspace. Not an Agent."""
        self._require_started()
        if self._workspace is None:
            raise ControllerNotStartedError("proposal workspace not available")
        return self._workspace

    @property
    def process_runner(self) -> Any:
        """Fail-closed process runner. Not an Agent. Spawns only in a subset session."""
        self._require_started()
        if self._process_runner is None:
            raise ControllerNotStartedError("process runner not available")
        return self._process_runner

    @property
    def copied_subset_workspace(self) -> Any:
        """Copied-subset workspace. Not an Agent. Layout only; does not spawn."""
        self._require_started()
        if self._subset_workspace is None:
            raise ControllerNotStartedError("copied-subset workspace not available")
        return self._subset_workspace

    @property
    def tools(self) -> Any:
        """Active tool catalog. Inspection / tests only. Not an Agent."""
        self._require_started()
        return self._tools

    @property
    def coding(self) -> Any:
        """Active Coding Agent. Raises if missing."""
        self._require_started()
        agent = self._agents.get("coding")
        if agent is None:
            raise AgentNotFoundError("coding")
        return agent

    @property
    def testing(self) -> Any:
        """Active Testing Agent. Raises if missing."""
        self._require_started()
        agent = self._agents.get("testing")
        if agent is None:
            raise AgentNotFoundError("testing")
        return agent

    @property
    def evaluation(self) -> Any:
        """Active Evaluation Agent. Raises if missing."""
        self._require_started()
        agent = self._agents.get("evaluation")
        if agent is None:
            raise AgentNotFoundError("evaluation")
        return agent

    @property
    def baselines(self) -> Any:
        """Controller-owned baseline store. Not an Agent."""
        self._require_started()
        if self._baseline_store is None:
            raise ControllerNotStartedError("baseline store not available")
        return self._baseline_store

    @property
    def deployment(self) -> Any:
        """Active Deployment Agent. Raises if missing."""
        self._require_started()
        agent = self._agents.get("deployment")
        if agent is None:
            raise AgentNotFoundError("deployment")
        return agent

    @property
    def versions(self) -> Any:
        """Controller-owned VersionManager. Not an Agent."""
        self._require_started()
        if self._version_manager is None:
            raise ControllerNotStartedError("version manager not available")
        return self._version_manager

    @property
    def git_history(self) -> Any:
        """Controller-owned GitVersionStore. History only. Not authorization."""
        self._require_started()
        if self._git_store is None:
            raise ControllerNotStartedError("git version store not available")
        return self._git_store

    @property
    def plugins(self) -> Any:
        """Controller-owned PluginRegistry. Metadata only. Not authorization."""
        self._require_started()
        if self._plugin_registry is None:
            raise ControllerNotStartedError("plugin registry not available")
        return self._plugin_registry

    def restore_git_history(
        self,
        commit_id: str,
        approval: Any,
        version_id: str = "",
    ) -> Any:
        """Delegate Git restore. Does not create approval."""
        self._require_started()
        if self._git_store is None:
            raise ControllerNotStartedError("git version store not available")
        return self._git_store.restore(commit_id, approval, version_id=version_id)

    def process_proposal(
        self,
        proposal: Any,
        approval: Any | None = None,
    ) -> Any:
        """Compose test → evaluate → optional approved deploy.

        Does not create ApprovalRecord. Does not treat evaluation as authority.
        Direct agent APIs remain available.
        """
        self._require_started()
        from astra.core.pipeline import process_change_proposal

        return process_change_proposal(
            testing=self.testing,
            evaluation=self.evaluation,
            deployment=self.deployment,
            proposal=proposal,
            approval=approval,
        )

    def rollback_change(self, version: str, approval: Any) -> Any:
        """Delegate rollback to DeploymentAgent. Does not create approval."""
        self._require_started()
        return self.deployment.rollback(version, approval)

    def request_tool(
        self,
        caller: str,
        tool_name: str,
        arguments: Mapping[str, Any] | None = None,
    ) -> Any:
        """Security-gated invocation path (ADR-0015). Dual-key still required."""
        self._require_started()
        from astra.tools.contracts import ToolInvocationRequest

        return self._tool_invoker.request(
            ToolInvocationRequest(
                caller=caller,
                tool_name=tool_name,
                arguments=arguments or {},
            )
        )

    def record_human_approval(self, invocation_id: str) -> Any:
        """Record approval only. Does not execute. Does not expand allowlist."""
        self._require_started()
        return self._tool_invoker.record_human_approval(invocation_id)

    @property
    def llm(self) -> Any:
        """Active chat :class:`~astra.reasoning.LLMBackend` (fast primary model)."""
        self._require_started()
        return self._llm

    @property
    def deep_llm(self) -> Any | None:
        """Optional deep-reasoning backend for agent work."""
        self._require_started()
        return self._deep_llm

    @property
    def embedding(self) -> Any:
        """Active :class:`~astra.reasoning.EmbeddingBackend`."""
        self._require_started()
        return self._embedding

    @property
    def vector_index(self) -> Any:
        """Active :class:`~astra.memory.VectorIndex`."""
        self._require_started()
        return self._vector_index

    @property
    def memory_indexer(self) -> Any:
        """Active :class:`~astra.memory.indexer.MemoryIndexer` (ADR-0012)."""
        self._require_started()
        return self._indexer

    def rebuild_memory_index(self) -> int:
        """Rebuild the derived FAISS index from SQLite and persist it.

        Recovery / model-change path (ADR-0012 D6–D7). Does not run at start.
        """
        self._require_started()
        count = self._indexer.rebuild()
        path = Path(self._settings.memory.vector_index_path)
        self._indexer.persist(path)
        self._last_persist_iso = self._indexer.last_persist_iso or self._utc_now_iso()
        self._index_needs_rebuild = False
        return count

    @property
    def settings(self) -> AstraSettings:
        return self._settings

    def apply_settings(self, settings: AstraSettings) -> None:
        """Replace in-memory settings after a System-tab config save."""
        self._settings = settings

    # ------------------------------------------------------------------
    # agent registry
    # ------------------------------------------------------------------

    def register_agent(self, agent_name: str, agent: object) -> None:
        if agent_name in self._agents:
            raise AgentAlreadyRegisteredError(
                f"agent {agent_name!r} is already registered; "
                "use a different name or remove the existing registration first"
            )
        self._agents[agent_name] = agent  # type: ignore[assignment]
        _LOGGER.debug("agent registered: %s", agent_name)

    def dispatch(self, agent_name: str, request: AgentRequest) -> AgentResult:
        if agent_name not in self._agents:
            raise AgentNotFoundError(
                f"no agent named {agent_name!r}; registered agents: "
                + ", ".join(sorted(self._agents)) or "<none>"
            )
        agent = self._agents[agent_name]
        try:
            return agent.run(request)
        except Exception as exc:  # noqa: BLE001
            _LOGGER.error(
                "agent %r raised: %s", agent_name, traceback.format_exc()
            )
            return AgentResult(
                agent=agent_name,
                success=False,
                summary=f"agent raised {type(exc).__name__}: {exc}",
            )

    def unregister_agent(self, agent_name: str) -> None:
        """Remove a registered agent. No-op if not present."""
        self._agents.pop(agent_name, None)

    def _register_research_agent(self) -> None:
        """Build and register LocalResearchAgent with local corpus sources."""
        try:
            from astra.configuration.paths import project_root
            from astra.research.agent import LocalResearchAgent
            from astra.research.source import (
                DirectoryCorpusSource,
                DocumentSource,
                KnowledgeSource,
                MemorySource,
            )

            sources: list[object] = [
                KnowledgeSource(self._knowledge),
                MemorySource(self._memory, indexer=self._indexer),
            ]
            root = project_root()
            docs = root / "docs"
            if docs.is_dir():
                sources.append(DirectoryCorpusSource(docs))
            readme = root / "README.md"
            if readme.is_file():
                sources.append(DocumentSource(readme))
            if bool(getattr(getattr(self._settings, "research", None), "allow_external", False)):
                from astra.research.wikipedia import WikipediaResearchSource

                sources.append(WikipediaResearchSource(self._security))
            agent = LocalResearchAgent(
                sources=sources,  # type: ignore[arg-type]
                llm=self._llm,
                security=self._security,
            )
            self._agents[agent.name] = agent
            _LOGGER.debug(
                "research agent registered with knowledge, memory, and local corpus"
            )
        except Exception as exc:  # noqa: BLE001
            _LOGGER.warning("could not register research agent: %s", exc)

    def refresh_research_agent(self) -> None:
        """Re-register the research agent after ``allow_external`` changes."""
        if not self._started:
            return
        self.unregister_agent("research")
        self._register_research_agent()

    def refresh_reasoning_backend(self) -> None:
        """Rebuild LLM backend after ``model_path`` or GPU settings change."""
        if not self._started:
            return
        if self._llm is not None:
            unload = getattr(self._llm, "unload", None)
            if callable(unload):
                try:
                    unload()
                except Exception as exc:  # noqa: BLE001
                    _LOGGER.warning("llm unload failed: %s", exc)
        if self._deep_llm is not None:
            unload = getattr(self._deep_llm, "unload", None)
            if callable(unload):
                try:
                    unload()
                except Exception as exc:  # noqa: BLE001
                    _LOGGER.warning("deep llm unload failed: %s", exc)
        self._llm = self._lf(self._settings)
        self._deep_llm = self._dlf(self._settings)
        research = self._agents.get("research")
        if research is not None:
            setattr(research, "_llm", self._llm)
        coding = self._agents.get("coding")
        if coding is not None:
            setattr(coding, "_llm", self._llm)
        if self._agent_loop is not None:
            bind = getattr(self._agent_loop, "bind_llm", None)
            if callable(bind):
                from astra.reasoning.deep_llm import agent_llm

                bind(agent_llm(self._llm, self._deep_llm))
        _LOGGER.info(
            "reasoning backend refreshed: available=%s model=%s deep=%s",
            self._llm.is_available() if self._llm else False,
            getattr(self._llm, "model_name", "") if self._llm else "",
            getattr(self._deep_llm, "model_name", "") if self._deep_llm else "",
        )

    def _register_coding_agent(self) -> None:
        """Build and register LocalCodingAgent with ToolInvoker, not the registry."""
        try:
            from astra.coding.agent import LocalCodingAgent

            agent = LocalCodingAgent(tools=self._tool_invoker, llm=self._llm)
            self._agents[agent.name] = agent
            _LOGGER.debug("coding agent registered with ToolInvoker; proposal only")
        except Exception as exc:  # noqa: BLE001
            _LOGGER.warning("could not register coding agent: %s", exc)

    def _register_testing_agent(self) -> None:
        """Build and register LocalTestingAgent with subset + runner, not tools."""
        try:
            from astra.testing.agent import LocalTestingAgent

            agent = LocalTestingAgent(
                subset=self._subset_workspace,
                runner=self._process_runner,
            )
            self._agents[agent.name] = agent
            _LOGGER.debug("testing agent registered; proposal tests via ProcessRunner")
        except Exception as exc:  # noqa: BLE001
            _LOGGER.warning("could not register testing agent: %s", exc)

    def _register_evaluation_agent(self) -> None:
        """Build LocalEvaluationAgent + baseline store. Not a Tool."""
        try:
            from astra.evaluation.agent import LocalEvaluationAgent
            from astra.evaluation.store import LocalBaselineStore

            testing = getattr(self._settings, "testing", None)
            scratch = (
                getattr(testing, "scratch_directory", None)
                if testing is not None
                else None
            )
            store_root = Path(scratch) / "baselines" if scratch is not None else None
            self._baseline_store = LocalBaselineStore(
                security=self._security,
                store_root=store_root,
            )
            agent = LocalEvaluationAgent(baselines=self._baseline_store)
            self._agents[agent.name] = agent
            _LOGGER.debug("evaluation agent registered; baseline store under scratch")
        except Exception as exc:  # noqa: BLE001
            _LOGGER.warning("could not register evaluation agent: %s", exc)

    def _register_deployment_agent(self) -> None:
        """Build LocalDeploymentAgent + LocalVersionManager. Not a Tool."""
        try:
            from astra.configuration.paths import project_root
            from astra.deployment.agent import LocalDeploymentAgent
            from astra.deployment.gitstore import GitVersionStore
            from astra.deployment.store import LocalVersionManager

            testing = getattr(self._settings, "testing", None)
            scratch = (
                getattr(testing, "scratch_directory", None)
                if testing is not None
                else None
            )
            store_root = Path(scratch) / "versions" if scratch is not None else None
            self._version_manager = LocalVersionManager(
                security=self._security,
                project_root=project_root(),
                store_root=store_root,
            )
            git_settings = getattr(self._settings, "git", None)
            self._git_store = GitVersionStore(
                security=self._security,
                project_root=project_root(),
                scratch_root=scratch,
                executable=getattr(git_settings, "executable", None)
                if git_settings is not None
                else None,
                init_if_missing=bool(
                    getattr(git_settings, "init_if_missing", False)
                )
                if git_settings is not None
                else False,
                author_name=str(getattr(git_settings, "author_name", "Astra") or "Astra")
                if git_settings is not None
                else "Astra",
                author_email=str(
                    getattr(git_settings, "author_email", "astra@localhost")
                    or "astra@localhost"
                )
                if git_settings is not None
                else "astra@localhost",
                timeout_seconds=float(
                    getattr(git_settings, "timeout_seconds", 30.0) or 30.0
                )
                if git_settings is not None
                else 30.0,
            )
            agent = LocalDeploymentAgent(
                security=self._security,
                project_root=project_root(),
                versions=self._version_manager,
                git=self._git_store,
            )
            self._agents[agent.name] = agent
            _LOGGER.debug(
                "deployment agent registered; VersionManager under scratch/versions"
            )
        except Exception as exc:  # noqa: BLE001
            _LOGGER.warning("could not register deployment agent: %s", exc)

    def _register_plugin_registry(self) -> None:
        """Build LocalPluginRegistry. Not an Agent. Does not execute plugins."""
        try:
            from astra.configuration.paths import project_root
            from astra.plugins.registry import LocalPluginRegistry

            plugin_settings = getattr(self._settings, "plugins", None)
            testing = getattr(self._settings, "testing", None)
            scratch = (
                getattr(testing, "scratch_directory", None)
                if testing is not None
                else None
            )
            plugin_root = (
                getattr(plugin_settings, "plugin_root", None)
                if plugin_settings is not None
                else None
            )
            self._plugin_registry = LocalPluginRegistry(
                plugin_root=plugin_root,
                project_root=project_root(),
                scratch_root=scratch,
            )
            if plugin_root is not None:
                for descriptor in self._plugin_registry.scan_descriptors(plugin_root):
                    try:
                        self._plugin_registry.register(descriptor)
                    except Exception as exc:  # noqa: BLE001
                        _LOGGER.debug("plugin register skipped: %s", exc)
            enabled = (
                getattr(plugin_settings, "enabled_plugin_ids", ())
                if plugin_settings is not None
                else ()
            )
            for plugin_id in enabled:
                try:
                    self._plugin_registry.enable(str(plugin_id))
                except Exception as exc:  # noqa: BLE001
                    _LOGGER.debug("plugin enable skipped: %s", exc)
            _LOGGER.debug("plugin registry constructed; external execution disabled")
        except Exception as exc:  # noqa: BLE001
            _LOGGER.warning("could not construct plugin registry: %s", exc)
            if self._plugin_registry is None:
                from astra.plugins.registry import LocalPluginRegistry

                self._plugin_registry = LocalPluginRegistry()

    def _register_agent_loop(self) -> None:
        """Wire the bounded execution loop. Gated tools. Never deploys."""
        from astra.configuration.paths import project_root
        from astra.core.agent_loop import AgentLoop
        from astra.reasoning.deep_llm import agent_llm

        testing = getattr(self._settings, "testing", None)
        scratch = getattr(testing, "scratch_directory", None) if testing is not None else None
        write_root = Path(self._settings.memory.database_path).parent
        security = getattr(self._settings, "security", None)
        self._agent_loop = AgentLoop(
            llm=agent_llm(self._llm, self._deep_llm),
            invoke_tool=self._invoke_agent_tool,
            execute_approved=self._execute_approved_agent_tool,
            memory=self._memory,
            coding=self._agents.get("coding"),
            testing=self._agents.get("testing"),
            evaluation=self._agents.get("evaluation"),
            project_root=project_root(),
            write_root=write_root,
            scratch_root=scratch,
            planning=self._planning,
            goals=self._goals,
            max_actions=int(getattr(security, "max_input_actions", 10) or 10),
            max_visual_observations=int(
                getattr(security, "max_visual_observations", 10) or 10
            ),
            max_interaction_time=float(
                getattr(security, "max_interaction_time_seconds", 60.0) or 60.0
            ),
        )

    def _bind_python_runner(self) -> None:
        registry = self._tools
        if registry is None or not callable(getattr(registry, "get", None)):
            return
        tool = registry.get("run_python")
        inner = getattr(tool, "_inner", tool)
        bind = getattr(inner, "bind_execute", None)
        if callable(bind):
            bind(self._run_isolated_python)

    def _bind_application_tools(self) -> None:
        registry = self._tools
        if registry is None or not callable(getattr(registry, "get", None)):
            return
        launch_tool = registry.get("launch_application")
        launch_inner = getattr(launch_tool, "_inner", launch_tool)
        bind_launch = getattr(launch_inner, "bind_launch", None)
        if callable(bind_launch):
            bind_launch(self._launch_application)
        inspect_tool = registry.get("inspect_process")
        inspect_inner = getattr(inspect_tool, "_inner", inspect_tool)
        bind_inspect = getattr(inspect_inner, "bind_inspect", None)
        if callable(bind_inspect):
            bind_inspect(self._inspect_processes)

    def _bind_perception_tools(self) -> None:
        registry = self._tools
        if registry is None or not callable(getattr(registry, "get", None)):
            return
        from astra.reasoning.vision import vision_from_settings

        vision = vision_from_settings(self._settings)
        capture_tool = registry.get("capture_screen")
        capture_inner = getattr(capture_tool, "_inner", capture_tool)
        bind_capture = getattr(capture_inner, "bind_capture", None)
        if callable(bind_capture):
            bind_capture(self._capture_screen)
        bind_describe = getattr(capture_inner, "bind_describe", None)
        if callable(bind_describe):
            bind_describe(vision.describe)
        window_tool = registry.get("inspect_active_window")
        window_inner = getattr(window_tool, "_inner", window_tool)
        bind_window = getattr(window_inner, "bind_inspect", None)
        if callable(bind_window):
            bind_window(self._inspect_active_window)

    def _bind_input_tools(self) -> None:
        registry = self._tools
        if registry is None or not callable(getattr(registry, "get", None)):
            return
        mouse = getattr(registry.get("mouse_action"), "_inner", registry.get("mouse_action"))
        bind_mouse = getattr(mouse, "bind_act", None)
        if callable(bind_mouse):
            bind_mouse(self._perform_mouse)
        bind_size = getattr(mouse, "bind_screen_size", None)
        if callable(bind_size):
            bind_size(self._screen_size)
        keyboard = getattr(
            registry.get("keyboard_action"),
            "_inner",
            registry.get("keyboard_action"),
        )
        bind_keys = getattr(keyboard, "bind_act", None)
        if callable(bind_keys):
            bind_keys(self._perform_keyboard)

    def _screen_size(self) -> tuple[int, int]:
        from astra.core.input_host import screen_size

        return screen_size()

    def _perform_mouse(
        self,
        action: str,
        x: int,
        y: int,
        button: str,
        click_count: int,
        scroll_amount: int,
    ) -> Mapping[str, Any]:
        from astra.core.input_host import perform_mouse

        return perform_mouse(action, x, y, button, click_count, scroll_amount)

    def _perform_keyboard(
        self,
        action: str,
        text: str,
        keys: tuple[str, ...],
    ) -> Mapping[str, Any]:
        from astra.core.input_host import perform_keyboard

        return perform_keyboard(action, text, keys)

    def _capture_screen(self, max_width: int, max_height: int) -> Mapping[str, Any]:
        from astra.core.perception_host import capture_desktop

        return capture_desktop(max_width=max_width, max_height=max_height)

    def _inspect_active_window(self) -> Mapping[str, Any]:
        from astra.core.perception_host import inspect_foreground_window

        return inspect_foreground_window()

    def _launch_application(self, executable: Path, timeout: float) -> Mapping[str, Any]:
        """Spawn an allowlisted application via ProcessRunner.launch."""
        from astra.security.contracts import ProcessSpec

        subset = self._subset_workspace
        runner = self._process_runner
        if subset is None or runner is None:
            return {"success": False, "error": "application launcher unavailable"}
        created = subset.create_empty_session()
        if not created.success or created.subset_root is None:
            return {"success": False, "error": created.reason or "session failed"}
        launch = getattr(runner, "launch", None)
        if not callable(launch):
            return {"success": False, "error": "process runner cannot launch applications"}
        try:
            spec = ProcessSpec(
                argv=(str(executable),),
                cwd=created.subset_root,
                timeout_seconds=float(timeout),
            )
            proc = launch(spec, session_id=created.session_id)
        except Exception as exc:  # noqa: BLE001
            return {"success": False, "error": f"{type(exc).__name__}: {exc}"}
        running = str(getattr(getattr(proc, "status", None), "value", "") or "") == "running"
        pid = getattr(proc, "pid", None)
        ok = bool(getattr(proc, "success", False)) and running
        return {
            "success": ok,
            "spawned": bool(getattr(proc, "spawned", False)),
            "running": running,
            "pid": pid,
            "timed_out": bool(getattr(proc, "timed_out", False)),
            "error": "" if ok else (getattr(proc, "reason", "") or "application did not start"),
        }

    def _inspect_processes(self, query: str, pid: int | None) -> list[Mapping[str, Any]]:
        runner = self._process_runner
        inspect = getattr(runner, "inspect", None) if runner is not None else None
        if not callable(inspect):
            return []
        try:
            rows = inspect(query=query, pid=pid)
        except Exception:
            return []
        return list(rows or [])

    def _run_isolated_python(self, script_path: Path, timeout: float) -> Mapping[str, Any]:
        """Spawn sys.executable in an empty subset session. Not host-arbitrary."""
        import sys

        from astra.security.contracts import ProcessSpec

        subset = self._subset_workspace
        runner = self._process_runner
        if subset is None or runner is None:
            return {"success": False, "error": "python runner unavailable"}
        created = subset.create_empty_session()
        if not created.success or created.subset_root is None:
            return {"success": False, "error": created.reason or "session failed"}
        dest = created.subset_root / "agent_run.py"
        proc = None
        try:
            dest.write_bytes(Path(script_path).read_bytes())
            spec = ProcessSpec(
                argv=(sys.executable, "agent_run.py"),
                cwd=created.subset_root,
                timeout_seconds=float(timeout),
            )
            proc = runner.run(spec, session_id=created.session_id)
        except Exception as exc:  # noqa: BLE001
            return {"success": False, "error": f"{type(exc).__name__}: {exc}"}
        finally:
            try:
                subset.discard(created.session_id)
            except Exception:
                pass
        if proc is None:
            return {"success": False, "error": "python runner produced no result"}
        ok = bool(proc.success) and proc.exit_code == 0 and not proc.timed_out
        return {
            "success": ok,
            "stdout": proc.stdout,
            "stderr": proc.stderr,
            "exit_code": proc.exit_code,
            "timed_out": proc.timed_out,
            "error": "" if ok else (proc.reason or proc.stderr or "python execution failed"),
        }

    def _invoke_agent_tool(
        self, tool_name: str, arguments: Mapping[str, Any]
    ) -> Any:
        from astra.core.agent_loop import record_from_invoker
        from astra.tools.allowlist import AGENT_EXECUTION_ALLOWLIST

        name = str(tool_name or "").strip()
        if name not in AGENT_EXECUTION_ALLOWLIST:
            from astra.core.agent_loop import ToolCallResult

            return ToolCallResult(
                success=False,
                status="unavailable",
                error=f"tool {name or '(none)'} is not registered",
            )
        record = self.request_tool("agent_loop", name, dict(arguments or {}))
        return record_from_invoker(record)

    def _execute_approved_agent_tool(self, invocation_id: str) -> Any:
        from astra.core.agent_loop import record_from_invoker

        record = self._tool_invoker.execute_approved(invocation_id)
        return record_from_invoker(record)

    @property
    def agent_loop(self) -> Any:
        """Bounded Goal→Plan→Execute loop. Raises if missing."""
        self._require_started()
        if self._agent_loop is None:
            raise ControllerNotStartedError("agent loop not available")
        return self._agent_loop

    def run_agent(self, goal: str) -> Any:
        """Run one execution goal. Never creates ApprovalRecord or deploys."""
        return self.agent_loop.run(goal)

    @property
    def agent_names(self) -> tuple[str, ...]:
        return tuple(sorted(self._agents))

    # ------------------------------------------------------------------
    # state
    # ------------------------------------------------------------------

    def get_state(self) -> ApplicationState:
        """Return current :class:`~astra.state.ApplicationState`.

        Returns a truthful state: a not-started controller reports every
        engine as NOT_IMPLEMENTED rather than OPERATIONAL.
        """
        return ControllerStateProvider(self).get_state()


# ---------------------------------------------------------------------------
# ControllerStateProvider
# ---------------------------------------------------------------------------

class ControllerStateProvider(StateProvider):
    """Assembles :class:`ApplicationState` from a live :class:`AstraController`.

    Uses actual engine instances (not file probes) when the controller is
    started. Falls back to PLANNED for anything not yet started.
    Never raises in normal operation.
    """

    def __init__(self, controller: AstraController) -> None:
        self._ctrl = controller

    def get_state(self) -> ApplicationState:
        try:
            return self._build()
        except Exception as exc:  # noqa: BLE001
            from datetime import datetime, timezone
            return ApplicationState(
                timestamp_iso=datetime.now(timezone.utc).isoformat(),
                notes=f"ControllerStateProvider error: {exc}",
            )

    def _build(self) -> ApplicationState:
        from datetime import datetime, timezone
        from astra.presentation.progress import MILESTONES

        ctrl = self._ctrl
        started = ctrl.started

        # Memory
        if started and ctrl._memory is not None:
            try:
                ctrl._memory._require_connection()
                mem_st, mem_note = _CS.OPERATIONAL, "SQLiteMemoryStore"
            except Exception:
                mem_st, mem_note = _CS.IMPLEMENTED, "store not reachable"
        else:
            mem_st, mem_note = _CS.IMPLEMENTED, "not started"

        # FAISS (ADR-0012 D13)
        if started and ctrl._vector_index is not None:
            active = len(ctrl._vector_index)
            backend_ok = False
            if ctrl._embedding is not None:
                try:
                    backend_ok = bool(ctrl._embedding.is_available())
                except Exception:
                    backend_ok = False
            if active > 0 and backend_ok:
                faiss_st = _CS.OPERATIONAL
            elif active > 0 or backend_ok:
                faiss_st = _CS.AVAILABLE
            else:
                faiss_st = _CS.CONFIGURED
            tombs = getattr(ctrl._vector_index, "tombstone_count", 0)
            faiss_meta = {
                "active_vectors": active,
                "tombstones": tombs if isinstance(tombs, int) else 0,
                "backend_available": backend_ok,
                "index_model": getattr(ctrl._vector_index, "embedding_model", ""),
                "configured_model": ctrl._settings.reasoning.embedding_model,
                "needs_rebuild": bool(ctrl._index_needs_rebuild),
                "last_persist_iso": ctrl._last_persist_iso,
            }
            if faiss_st is _CS.OPERATIONAL:
                faiss_note = f"FaissVectorIndex; {active} active vectors"
            elif ctrl._index_needs_rebuild:
                faiss_note = "derived index empty or stale; call rebuild_memory_index()"
            elif backend_ok:
                faiss_note = "embedding backend available; index not yet populated"
            else:
                faiss_note = "embedding model file absent; FTS5 search still works"
        else:
            faiss_st = _CS.IMPLEMENTED
            faiss_note = "not started"
            faiss_meta = {}

        # Knowledge
        if started and ctrl._knowledge is not None:
            know_st, know_note = _CS.OPERATIONAL, "SQLiteKnowledgeStore"
        else:
            know_st, know_note = _CS.IMPLEMENTED, "not started"

        # Planning
        if started and ctrl._planning is not None:
            plan_st, plan_note = _CS.OPERATIONAL, "SQLitePlanningStore"
        else:
            plan_st, plan_note = _CS.IMPLEMENTED, "not started"

        # Goal manager
        if started and ctrl._goals is not None:
            goal_st, goal_note = _CS.OPERATIONAL, "SQLiteGoalManager"
        else:
            goal_st, goal_note = _CS.IMPLEMENTED, "not started"
        goal_snaps = _goal_snapshots(ctrl) if started else ()

        # LLM backend
        if started and ctrl._llm is not None:
            if ctrl._llm.is_available():
                llm_st = _CS.AVAILABLE
                llm_note = f"model file present: {ctrl._llm.model_name}"
            else:
                llm_st = _CS.CONFIGURED
                llm_note = (
                    "llm binding or model file absent; "
                    "set reasoning.model_path in astra.toml"
                )
        else:
            llm_st = _CS.IMPLEMENTED
            llm_note = "not started"

        # Embedding backend
        if started and ctrl._embedding is not None:
            if ctrl._embedding.is_available():
                emb_st = _CS.AVAILABLE
                emb_note = f"embedding model present: {ctrl._embedding.model_name}"
            else:
                emb_st = _CS.CONFIGURED
                emb_note = "embedding model file absent"
        else:
            emb_st = _CS.IMPLEMENTED
            emb_note = "not started"

        # Security (policy only)
        if started and ctrl._security is not None:
            sec_st, sec_note = _CS.OPERATIONAL, "PolicySecurityAgent; deny-by-default"
        else:
            sec_st, sec_note = _CS.IMPLEMENTED, "not started"
        sec_snap = _security_snapshot(ctrl) if started else SecuritySnapshot()
        tool_snap = _tool_snapshot(ctrl) if started else ToolSnapshot()
        coding_snap = _coding_snapshot(ctrl) if started else CodingSnapshot()

        # Tools (ADR-0015 / ADR-0016 / ADR-0017). Gate answers; two executable tools.
        if started and ctrl._tool_invoker is not None:
            tools_mod_st = _CS.AVAILABLE
            registry_st = _CS.AVAILABLE
            gate_st = _CS.OPERATIONAL
            exec_st = _CS.AVAILABLE
            tools_note = (
                "gated invocation operational; executable tools: "
                "read_text, read_project"
            )
        else:
            tools_mod_st = _CS.IMPLEMENTED
            registry_st = _CS.IMPLEMENTED
            gate_st = _CS.IMPLEMENTED
            exec_st = _CS.IMPLEMENTED
            tools_note = "not started"

        coding_agent = ctrl._agents.get("coding") if started else None
        if started and coding_agent is not None:
            coding_st = _CS.OPERATIONAL
            coding_note = "LocalCodingAgent; proposal-only; read_project via ToolInvoker"
        else:
            coding_st = _CS.IMPLEMENTED
            coding_note = "not started"

        if started and ctrl._workspace is not None:
            workspace_st = _CS.OPERATIONAL
            workspace_note = "ProposalWorkspace; overlay only; no test runner"
        else:
            workspace_st = _CS.IMPLEMENTED
            workspace_note = "not started"

        if started and ctrl._process_runner is not None:
            runner_st = _CS.OPERATIONAL
            runner_note = "ProcessRunner; spawn enabled; session cwd; not a test runner"
        else:
            runner_st = _CS.IMPLEMENTED
            runner_note = "not started"

        if started and ctrl._subset_workspace is not None:
            subset_st = _CS.OPERATIONAL
            subset_note = "CopiedSubsetWorkspace; layout only; no test runner"
        else:
            subset_st = _CS.IMPLEMENTED
            subset_note = "not started"

        testing_agent = ctrl._agents.get("testing") if started else None
        if started and testing_agent is not None:
            test_prop_st = _CS.OPERATIONAL
            test_prop_note = "LocalTestingAgent.test_proposal; unittest in copied session"
            run_suite_st = _CS.OPERATIONAL
            run_suite_note = "LocalTestingAgent.run_suite; copied snapshot; no overlay"
        else:
            test_prop_st = _CS.IMPLEMENTED
            test_prop_note = "not started"
            run_suite_st = _CS.IMPLEMENTED
            run_suite_note = "copied snapshot; no overlay; not started"

        evaluation_agent = ctrl._agents.get("evaluation") if started else None
        if started and evaluation_agent is not None:
            eval_st = _CS.OPERATIONAL
            eval_prop_st = _CS.OPERATIONAL
            eval_prop_note = "judges ChangeProposal + TestReport; no spawn"
        else:
            eval_st = _CS.IMPLEMENTED
            eval_prop_st = _CS.IMPLEMENTED
            eval_prop_note = "not started"
        if started and getattr(ctrl, "_baseline_store", None) is not None:
            baseline_st = _CS.OPERATIONAL
            baseline_note = "immutable baseline; comparison has no deployment authority"
        else:
            baseline_st = _CS.IMPLEMENTED
            baseline_note = "immutable baseline; not started"

        deployment_agent = ctrl._agents.get("deployment") if started else None
        if started and deployment_agent is not None:
            deploy_st = _CS.OPERATIONAL
            deploy_note = "Requires explicit human ApprovalRecord"
            pipeline_st = _CS.OPERATIONAL
            pipeline_note = "controller-owned; test→eval→approval→deploy; no auto-approve"
        else:
            deploy_st = _CS.IMPLEMENTED
            deploy_note = "Requires explicit human ApprovalRecord; not started"
            pipeline_st = _CS.IMPLEMENTED
            pipeline_note = "controller-owned pipeline; not started"
        if started and getattr(ctrl, "_version_manager", None) is not None:
            rollback_st = _CS.OPERATIONAL
            rollback_note = "Requires explicit human ApprovalRecord"
        else:
            rollback_st = _CS.IMPLEMENTED
            rollback_note = "Requires explicit human ApprovalRecord; not started"
        git_settings = getattr(getattr(ctrl, "_settings", None), "git", None)
        git_exe = getattr(git_settings, "executable", None) if git_settings is not None else None
        if started and getattr(ctrl, "_git_store", None) is not None:
            git_st = _CS.OPERATIONAL if git_exe is not None else _CS.IMPLEMENTED
            git_note = (
                "history/recovery only; not authorization"
                if git_exe is not None
                else "history/recovery only; git unavailable until configured"
            )
        else:
            git_st = _CS.IMPLEMENTED
            git_note = "history/recovery only; not authorization; not started"
        if started and getattr(ctrl, "_plugin_registry", None) is not None:
            plugin_st = _CS.OPERATIONAL
            plugin_note = (
                "registration only; metadata is not authority; "
                "external execution disabled"
            )
        else:
            plugin_st = _CS.IMPLEMENTED
            plugin_note = (
                "registration only; metadata is not authority; "
                "external execution disabled; not started"
            )

        modules = (
            ModuleStatus(
                module="astra.memory",
                status=mem_st,
                capabilities=(
                    CapabilityInfo("sqlite_store", mem_st, "SQLite Store", mem_note),
                    CapabilityInfo("vector_index", faiss_st, "Vector Index",
                                   faiss_note, metadata=faiss_meta),
                ),
            ),
            ModuleStatus(
                module="astra.knowledge",
                status=know_st,
                capabilities=(
                    CapabilityInfo("knowledge_store", know_st, "Knowledge Store", know_note),
                ),
            ),
            ModuleStatus(
                module="astra.planning",
                status=plan_st,
                capabilities=(
                    CapabilityInfo("planning_store", plan_st, "Planning Store", plan_note),
                    CapabilityInfo("goal_manager", goal_st, "Goal Manager", goal_note),
                ),
            ),
            ModuleStatus(
                module="astra.reasoning",
                status=llm_st,
                capabilities=(
                    CapabilityInfo("llm_backend", llm_st, "LLM Backend", llm_note),
                    CapabilityInfo("embedding_backend", emb_st, "Embedding Backend", emb_note),
                ),
            ),
            ModuleStatus(
                module="astra.security",
                status=sec_st,
                capabilities=(
                    CapabilityInfo("network_policy", sec_st, "Network policy", sec_note),
                    CapabilityInfo("file_write_policy", sec_st, "File-write policy",
                                   "allowlist: configured data and log directories"),
                    CapabilityInfo("file_read_policy", sec_st, "File-read policy",
                                   "project_root only; exclusions; ALLOW/DENY"),
                    CapabilityInfo("permanent_change_policy", sec_st,
                                   "Permanent-change policy",
                                   "never ALLOW; human approval required"),
                    CapabilityInfo("temporary_write_policy", sec_st,
                                   "Temporary-write policy",
                                   "scratch overlay only; not a check_file_write root"),
                    CapabilityInfo("process_policy", sec_st,
                                   "Process-execution policy",
                                   "empty executable allowlist; never spawn via this check"),
                ),
            ),
            ModuleStatus(
                module="astra.tools",
                status=tools_mod_st,
                capabilities=(
                    CapabilityInfo("tool_registry", registry_st, "Tool registry",
                                   "catalog; production registers read_text and read_project"),
                    CapabilityInfo("gated_invocation", gate_st, "Gated invocation",
                                   tools_note),
                    CapabilityInfo("executable_tools", exec_st, "Executable tools",
                                   "read_text (data/log) + read_project (repo); 64 KiB cap"),
                ),
            ),
            ModuleStatus(
                module="astra.coding",
                status=coding_st,
                capabilities=(
                    CapabilityInfo("coding_agent", coding_st, "Coding Agent",
                                   coding_note),
                    CapabilityInfo("proposal_apply", _CS.PLANNED, "Apply proposals",
                                   "not in B-021; Deployment after tests, eval, human approval"),
                ),
            ),
            ModuleStatus(
                module="astra.testing",
                status=workspace_st,
                capabilities=(
                    CapabilityInfo("proposal_workspace", workspace_st,
                                   "Proposal workspace", workspace_note),
                    CapabilityInfo("process_runner", runner_st,
                                   "Process runner", runner_note),
                    CapabilityInfo("copied_subset_workspace", subset_st,
                                   "Copied-subset workspace", subset_note),
                    CapabilityInfo("test_proposal", test_prop_st, "Test proposal",
                                   test_prop_note),
                    CapabilityInfo("run_suite", run_suite_st, "Run suite",
                                   run_suite_note),
                ),
            ),
            ModuleStatus(
                module="astra.evaluation",
                status=eval_st,
                capabilities=(
                    CapabilityInfo("evaluate_proposal", eval_prop_st,
                                   "Evaluate proposal", eval_prop_note),
                    CapabilityInfo("evaluate", baseline_st, "Evaluate baseline",
                                   baseline_note),
                    CapabilityInfo("record_baseline", baseline_st, "Record baseline",
                                   baseline_note),
                ),
            ),
            ModuleStatus(
                module="astra.deployment",
                status=deploy_st,
                capabilities=(
                    CapabilityInfo("deploy", deploy_st, "Deploy", deploy_note),
                    CapabilityInfo("process_proposal", pipeline_st,
                                   "Change pipeline", pipeline_note),
                    CapabilityInfo("rollback", rollback_st, "Rollback",
                                   rollback_note),
                    CapabilityInfo("version_manager", rollback_st,
                                   "Version manager",
                                   "LocalVersionManager; scratch store; not an Agent"),
                    CapabilityInfo("git_history", git_st, "Git history", git_note),
                ),
            ),
            ModuleStatus(
                module="astra.plugins",
                status=plugin_st,
                capabilities=(
                    CapabilityInfo("plugin_registry", plugin_st,
                                   "Plugin registry", plugin_note),
                ),
            ),
        )

        completed = tuple(m.id for m in MILESTONES if m.for_ui())
        current_stage = next((m.id for m in MILESTONES if not m.for_ui()), "done")

        s = ctrl._settings
        return ApplicationState(
            schema_version=6,
            timestamp_iso=datetime.now(timezone.utc).isoformat(),
            stage=current_stage,
            completed_stages=completed,
            modules=modules,
            system=SystemInfo(),
            tests=TestStatus(),
            active_model=s.reasoning.model,
            active_model_status=llm_st,
            deep_model=str(getattr(s.reasoning, "deep_model", "") or ""),
            embedding_model=s.reasoning.embedding_model,
            notes="controller state" if started else "controller not started",
            goals=goal_snaps,
            security=sec_snap,
            tools=tool_snap,
            coding=coding_snap,
        )


def _goal_snapshots(ctrl: AstraController) -> tuple[GoalSnapshot, ...]:
    """Map live Goal Manager rows to presentation DTOs. Never raises."""
    from astra.planning.contracts import TaskStatus

    mgr = getattr(ctrl, "_goals", None)
    backlog = getattr(ctrl, "_planning", None)
    if mgr is None:
        return ()
    try:
        goals = mgr.list_goals()
    except Exception:
        return ()
    snaps: list[GoalSnapshot] = []
    for goal in goals:
        done = 0
        blocked = False
        for task_id in goal.task_ids:
            task = None
            if backlog is not None:
                try:
                    task = backlog.get(task_id)
                except Exception:
                    task = None
            if task is None:
                continue
            if task.status is TaskStatus.DONE:
                done += 1
            if task.status is TaskStatus.BLOCKED:
                blocked = True
        ev = None
        try:
            ev = mgr.last_evaluation(goal.goal_id)
        except Exception:
            ev = None
        hits = 0
        met: bool | None = None
        evaluated_at = ""
        embedding_used = False
        if ev is not None:
            met = bool(ev.met)
            evaluated_at = (
                ev.evaluated_at.strftime("%Y-%m-%dT%H:%M:%S.%fZ")
                if ev.evaluated_at is not None
                else ""
            )
            hits = sum(len(c.hits) for c in ev.criteria)
            embedding_used = bool(ev.embedding_used)
        created = ""
        if goal.created_at is not None:
            created = goal.created_at.strftime("%Y-%m-%dT%H:%M:%S.%fZ")
        snaps.append(
            GoalSnapshot(
                goal_id=goal.goal_id,
                description=goal.description,
                status=goal.status.value,
                success_criteria=goal.success_criteria,
                task_ids=goal.task_ids,
                tasks_done=done,
                tasks_total=len(goal.task_ids),
                blocked=blocked,
                last_evaluate_met=met,
                last_evaluated_at=evaluated_at,
                evidence_hit_count=hits,
                embedding_used=embedding_used,
                created_at=created,
            )
        )
    return tuple(snaps)


def _security_snapshot(ctrl: AstraController) -> SecuritySnapshot:
    """Map live Security Agent to a leaf DTO. Never raises."""
    agent = getattr(ctrl, "_security", None)
    settings = getattr(ctrl, "_settings", None)
    allow = False
    require = True
    if settings is not None:
        try:
            allow = bool(settings.security.allow_outbound_network)
            require = bool(settings.security.require_human_approval)
        except Exception:
            pass
    if agent is None:
        return SecuritySnapshot(
            allow_outbound_network=allow,
            require_human_approval=require,
        )
    try:
        return SecuritySnapshot(
            allow_outbound_network=bool(
                getattr(agent, "allow_outbound_network", allow)
            ),
            require_human_approval=bool(
                getattr(agent, "require_human_approval", require)
            ),
            last_verdict=str(getattr(agent, "last_verdict", "") or ""),
            last_reason=str(getattr(agent, "last_reason", "") or ""),
            last_kind=str(getattr(agent, "last_kind", "") or ""),
            checks_total=int(getattr(agent, "checks_total", 0) or 0),
        )
    except Exception:
        return SecuritySnapshot(
            allow_outbound_network=allow,
            require_human_approval=require,
        )


def _tool_snapshot(ctrl: AstraController) -> ToolSnapshot:
    """Map live tool gate to a leaf DTO. Never raises. Never claims execution."""
    registry = getattr(ctrl, "_tools", None)
    invoker = getattr(ctrl, "_tool_invoker", None)
    registered = 0
    if registry is not None:
        try:
            registered = len(tuple(registry.specs()))
        except Exception:
            registered = 0
    if invoker is None:
        return ToolSnapshot(
            registry_status="implemented",
            gated_invocation_status="implemented",
            executable_tool_count=0,
            registered_tool_count=registered,
        )
    try:
        allowlist = getattr(invoker, "execution_allowlist", frozenset())
        allowlist_n = len(allowlist) if allowlist is not None else 0
        pending = int(getattr(invoker, "pending_approvals", 0) or 0)
        executed = int(getattr(invoker, "executed_count", 0) or 0)
        last = getattr(invoker, "last_record", None)
        last_status = ""
        last_tool = ""
        last_verdict = ""
        last_reason = ""
        if last is not None:
            status = getattr(last, "status", None)
            last_status = str(getattr(status, "value", status) or "")
            last_tool = str(
                getattr(last, "spec_name", "") or getattr(last, "tool_name", "") or ""
            )
            last_verdict = str(getattr(last, "verdict", "") or "")
            last_reason = str(getattr(last, "reason", "") or "")
        return ToolSnapshot(
            registry_status="available",
            gated_invocation_status="operational",
            executable_tool_count=allowlist_n,
            registered_tool_count=registered,
            pending_approvals=pending,
            executed_count=executed,
            last_status=last_status,
            last_tool_name=last_tool,
            last_verdict=last_verdict,
            last_reason=last_reason,
        )
    except Exception:
        return ToolSnapshot(
            registry_status="implemented",
            gated_invocation_status="implemented",
            executable_tool_count=0,
            registered_tool_count=registered,
        )


def _coding_snapshot(ctrl: AstraController) -> CodingSnapshot:
    """Map live Coding Agent to a leaf DTO. Never claims applied. No full bodies."""
    agent = None
    try:
        agent = ctrl._agents.get("coding")
    except Exception:
        agent = None
    if agent is None:
        return CodingSnapshot(agent_status="implemented", applied=False)
    try:
        status = str(getattr(agent, "last_proposal_status", "none") or "none")
        if status in {"applied", "approved", "tested", "deployed"}:
            status = "generated"
        preview = str(getattr(agent, "last_preview", "") or "")
        if len(preview) > 2048:
            preview = preview[:2048]
        paths = tuple(str(p) for p in (getattr(agent, "last_change_paths", ()) or ()))
        risks = tuple(str(r) for r in (getattr(agent, "last_risks", ()) or ()))
        objections = tuple(str(o) for o in (getattr(agent, "last_objections", ()) or ()))
        improves = tuple(str(i) for i in (getattr(agent, "last_improves", ()) or ()))
        return CodingSnapshot(
            agent_status="operational",
            last_proposal_status=status,
            applied=False,
            last_objective=str(getattr(agent, "last_objective", "") or ""),
            last_change_count=int(getattr(agent, "last_change_count", 0) or 0),
            last_risk_count=int(getattr(agent, "last_risk_count", 0) or 0),
            llm_used=bool(getattr(agent, "llm_used", False)),
            last_change_paths=paths,
            last_risks=risks[:16],
            last_objections=objections[:16],
            last_improves=improves[:16],
            last_preview=preview,
            last_objection_count=int(
                getattr(agent, "last_objection_count", len(objections)) or 0
            ),
        )
    except Exception:
        return CodingSnapshot(agent_status="implemented", applied=False)


__all__ = ["AstraController", "ControllerStateProvider"]
