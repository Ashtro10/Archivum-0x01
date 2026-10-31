"""System tab API — memory, knowledge, RAG, automation, resources."""

from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Any

from astra.configuration.paths import data_root
from astra.memory.contracts import MemoryCategory, MemoryRecord, VerificationState
from astra.memory.user_facts import (
    SOURCE_EXPLICIT_STATEMENT,
    USER_FACT,
    field_key_tag,
    filter_user_durable,
    inspect_record,
    memory_display_label,
    normalize_custom_field_key,
)

IMPORTANCE_PREFIX = "importance:"
_AUTOMATION_FILE = data_root() / "automation.json"
_TEXT_SUFFIXES = frozenset({".txt", ".md", ".markdown", ".rst", ".csv", ".json", ".toml"})


def _resolve_gguf_path(raw: str) -> Path:
    text = str(raw or "").strip().strip('"').strip("'")
    path = Path(text).expanduser()
    if path.is_file():
        return path.resolve()
    from astra.configuration.paths import project_root

    relative = (project_root() / path).resolve()
    if relative.is_file():
        return relative
    return path.resolve()


def _sync_controller_settings(runtime: Any, settings: Any) -> None:
    ctrl = getattr(runtime, "controller", None)
    if ctrl is None:
        return
    apply = getattr(ctrl, "apply_settings", None)
    if callable(apply):
        apply(settings)
    elif hasattr(ctrl, "_settings"):
        ctrl._settings = settings


def parse_importance(tags: tuple[str, ...] | list[str]) -> int:
    for tag in tags:
        text = str(tag)
        if text.startswith(IMPORTANCE_PREFIX):
            try:
                return max(0, min(100, int(text.split(":", 1)[1])))
            except ValueError:
                pass
    return 50


def with_importance(tags: tuple[str, ...], importance: int) -> tuple[str, ...]:
    kept = tuple(tag for tag in tags if not str(tag).startswith(IMPORTANCE_PREFIX))
    return kept + (f"{IMPORTANCE_PREFIX}{max(0, min(100, int(importance)))}",)


class WebSystemMixin:
    """Backend helpers for the Primus System tab."""

    _web_memory: Any
    _web_knowledge: Any
    _store_lock: threading.RLock

    def _init_system_stores(self) -> None:
        self._web_memory = None
        self._web_knowledge = None
        self._store_lock = threading.RLock()

    def _memory_store(self) -> Any | None:
        """Web-owned SQLite handle — never use controller memory from HTTP threads."""
        if self._web_memory is False:
            return None
        if self._web_memory is None:
            try:
                from astra.memory.sqlite_store import SQLiteMemoryStore

                store = SQLiteMemoryStore(
                    self.settings.memory.database_path,
                    allow_cross_thread=True,
                )
                store.initialise()
                self._web_memory = store
            except Exception:
                self._web_memory = False
        return self._web_memory if self._web_memory is not False else None

    def _knowledge_store(self) -> Any | None:
        if self._web_knowledge is False:
            return None
        if self._web_knowledge is None:
            try:
                from astra.knowledge.sqlite_store import SQLiteKnowledgeStore

                store = SQLiteKnowledgeStore(
                    self.settings.knowledge.database_path,
                    allow_cross_thread=True,
                )
                store.initialise()
                self._web_knowledge = store
            except Exception:
                self._web_knowledge = False
        return self._web_knowledge if self._web_knowledge is not False else None

    def _memory_row(self, record: Any) -> dict[str, Any]:
        row = inspect_record(record)
        row["record_id"] = str(getattr(record, "record_id", "") or "")
        tags = tuple(getattr(record, "tags", ()) or ())
        row["importance"] = parse_importance(tags)
        return row

    def set_memory_importance(self, payload: dict[str, Any]) -> dict[str, Any]:
        with self._store_lock:
            store = self._memory_store()
            writer = getattr(store, "store", None)
            iterate = getattr(store, "iter_records", None)
            if store is None or not callable(writer) or not callable(iterate):
                return {"ok": False, "error": "memory unavailable"}
            target = str(payload.get("record_id", "") or "").strip()
            if not target:
                return {"ok": False, "error": "missing record_id"}
            try:
                importance = int(payload.get("importance", 50))
            except (TypeError, ValueError):
                return {"ok": False, "error": "invalid importance"}
            try:
                for record in iterate():
                    if str(getattr(record, "record_id", "") or "") != target:
                        continue
                    updated = MemoryRecord(
                        record_id=record.record_id,
                        category=record.category,
                        content=record.content,
                        verification=record.verification,
                        source=record.source,
                        created_at=record.created_at,
                        updated_at=record.updated_at,
                        tags=with_importance(tuple(getattr(record, "tags", ()) or ()), importance),
                    )
                    writer(updated)
                    return {"ok": True, "record_id": target, "importance": parse_importance(updated.tags)}
            except Exception as exc:
                return {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
            return {"ok": False, "error": "not found"}

    def add_memory(self, payload: dict[str, Any] | str, value: str = "") -> dict[str, Any]:
        with self._store_lock:
            store = self._memory_store()
            writer = getattr(store, "store", None)
            if store is None or not callable(writer):
                return {"ok": False, "error": "memory unavailable"}
            if isinstance(payload, str):
                data = {"field": payload, "value": value}
            elif isinstance(payload, dict):
                data = payload
            else:
                data = {}
            label = str(data.get("label", "") or "").strip()
            content = str(data.get("content", "") or "").strip()
            legacy_field = str(data.get("field", "") or "").strip()
            legacy_value = str(data.get("value", "") or "").strip()
            if not content and legacy_value:
                content = legacy_value
            if not content:
                return {"ok": False, "error": "content required"}
            try:
                importance = int(data.get("importance", 50))
            except (TypeError, ValueError):
                importance = 50
            if label:
                key = normalize_custom_field_key(label)
            elif legacy_field:
                key = legacy_field
            else:
                key = USER_FACT
            tags = (
                "durable",
                "memory:manual",
                field_key_tag(key),
                f"{IMPORTANCE_PREFIX}{max(0, min(100, importance))}",
            )
            try:
                record = MemoryRecord(
                    record_id="",
                    category=MemoryCategory.USER_PREFERENCES,
                    content=content,
                    verification=VerificationState.UNVERIFIED,
                    source=SOURCE_EXPLICIT_STATEMENT,
                    tags=tags,
                )
                stored = writer(record)
            except Exception as exc:
                return {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
            display = memory_display_label(key) or ""
            return {
                "ok": bool(stored),
                "record_id": str(stored or ""),
                "field": key,
                "label": display,
                "content": content,
                "importance": importance,
            }

    def list_memory(self) -> dict[str, Any]:
        with self._store_lock:
            store = self._memory_store()
            iterate = getattr(store, "iter_records", None)
            if store is None or not callable(iterate):
                return {"ok": False, "error": "memory unavailable — run python -m astra (not --demo)"}
            rows: list[dict[str, Any]] = []
            try:
                records = list(iterate())
            except Exception as exc:
                return {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
            for record in filter_user_durable(records):
                rows.append(self._memory_row(record))
            rows.sort(
                key=lambda item: (int(item.get("importance", 0)), str(item.get("updated_at", ""))),
                reverse=True,
            )
            return {"ok": True, "memories": rows, "count": len(rows)}

    def list_knowledge(self) -> dict[str, Any]:
        with self._store_lock:
            store = self._knowledge_store()
            iterate = getattr(store, "iter_records", None)
            if store is None or not callable(iterate):
                return {"ok": False, "error": "knowledge unavailable"}
            rows: list[dict[str, Any]] = []
            try:
                for record in iterate():
                    category = getattr(record, "category", None)
                    rows.append(
                        {
                            "record_id": str(getattr(record, "record_id", "") or ""),
                            "category": str(getattr(category, "value", category) or ""),
                            "content": str(getattr(record, "content", "") or "")[:400],
                            "source": str(getattr(record, "source", "") or ""),
                            "updated_at": str(getattr(record, "updated_at", "") or ""),
                        }
                    )
            except Exception as exc:
                return {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
            rows.sort(key=lambda item: str(item.get("updated_at", "")), reverse=True)
            return {"ok": True, "records": rows[:200], "count": len(rows)}

    def upload_knowledge(self, payload: dict[str, Any]) -> dict[str, Any]:
        content = str(payload.get("content", "") or "").strip()
        if not content:
            return {"ok": False, "error": "content required"}
        source = str(payload.get("source", "") or "upload").strip() or "upload"
        with self._store_lock:
            store = self._knowledge_store()
            if store is None:
                return {"ok": False, "error": "knowledge unavailable"}
            try:
                from astra.knowledge.classifier import KeywordKnowledgeClassifier
                from astra.knowledge.ingest import ingest_text

                classifier = KeywordKnowledgeClassifier()
                record_id = ingest_text(store, classifier, content, source=source)
                return {"ok": bool(record_id), "record_id": str(record_id or "")}
            except Exception as exc:
                return {"ok": False, "error": f"{type(exc).__name__}: {exc}"}

    def sync_knowledge_folder(self, payload: dict[str, Any]) -> dict[str, Any]:
        folder = Path(str(payload.get("path", "") or "").strip()).expanduser()
        if not folder.is_dir():
            return {"ok": False, "error": "folder not found"}
        ingested = 0
        skipped = 0
        for path in sorted(folder.rglob("*")):
            if not path.is_file() or path.suffix.lower() not in _TEXT_SUFFIXES:
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace").strip()
            except OSError:
                skipped += 1
                continue
            if not text:
                skipped += 1
                continue
            outcome = self.upload_knowledge({"content": text, "source": str(path)})
            if outcome.get("ok"):
                ingested += 1
            else:
                skipped += 1
        return {"ok": True, "ingested": ingested, "skipped": skipped, "folder": str(folder)}

    def _embedding_health(self) -> dict[str, Any]:
        path = getattr(self.settings.reasoning, "embedding_model_path", None)
        path_obj = Path(path) if path else None
        exists = bool(path_obj and path_obj.is_file())
        ready = False
        message = ""
        if path_obj is None or not str(path_obj).strip():
            message = "No embedding model configured in astra.toml ([reasoning].embedding_model_path)."
        elif not exists:
            message = f"Embedding model file missing: {path_obj}"
        else:
            try:
                from astra.core.astra_controller import _default_embed

                backend = _default_embed(self.settings)
                ready = bool(backend.is_available())
            except Exception as exc:
                message = f"Embedding backend unavailable ({type(exc).__name__})"
            if not ready and not message:
                message = "Embedding backend unavailable — check llama-cpp-python install."
        ctrl = self.controller
        if ctrl is not None and bool(getattr(ctrl, "_started", False)):
            emb = getattr(ctrl, "_embedding", None)
            if emb is not None:
                try:
                    ready = bool(emb.is_available())
                    if ready:
                        message = "Embedding backend ready."
                except Exception:
                    pass
        return {
            "embedding_path": str(path_obj or ""),
            "embedding_exists": exists,
            "embedding_ready": ready,
            "embedding_message": message,
        }

    def _llm_health(self) -> dict[str, Any]:
        reasoning = self.settings.reasoning
        path_obj = Path(reasoning.model_path) if reasoning.model_path else None
        exists = bool(path_obj and path_obj.is_file())
        ready = False
        message = ""
        label = str(reasoning.model or "").strip() or (path_obj.name if path_obj else "")
        if path_obj is None or not str(path_obj).strip():
            message = "No chat model configured — set [reasoning].model_path in astra.toml."
        elif not exists:
            message = f"Chat model file missing: {path_obj}"
        else:
            ctrl = self.controller
            llm = getattr(ctrl, "_llm", None) if ctrl is not None else None
            if llm is not None:
                try:
                    ready = bool(llm.is_available())
                    if ready:
                        message = f"Ready: {getattr(llm, 'model_name', label) or label}"
                    else:
                        probe = getattr(llm, "unavailable_reason", None)
                        message = str(probe() or "LLM backend unavailable") if callable(probe) else "LLM backend unavailable"
                except Exception as exc:
                    message = f"LLM check failed ({type(exc).__name__})"
            else:
                try:
                    from astra.core.astra_controller import _default_llm

                    backend = _default_llm(self.settings)
                    ready = bool(backend.is_available())
                    message = (
                        f"Ready: {backend.model_name}"
                        if ready
                        else str(backend.unavailable_reason() or "LLM backend unavailable")
                    )
                except Exception as exc:
                    message = f"LLM backend unavailable ({type(exc).__name__})"
        return {
            "model": label,
            "model_path": str(path_obj or ""),
            "model_exists": exists,
            "llm_ready": ready,
            "llm_message": message,
            "gpu_layers": int(getattr(reasoning, "gpu_layers", 0) or 0),
            "context_tokens": int(getattr(reasoning, "context_tokens", 0) or 0),
        }

    def update_reasoning(self, payload: dict[str, Any]) -> dict[str, Any]:
        from astra.configuration.loader import save_settings

        reasoning = self.settings.reasoning
        updates: dict[str, Any] = {}
        if "model_path" in payload:
            raw = str(payload.get("model_path", "") or "").strip()
            if not raw:
                return {"ok": False, "error": "model_path required"}
            path = _resolve_gguf_path(raw)
            if not path.is_file():
                return {"ok": False, "error": f"model file not found: {path}"}
            updates["model_path"] = path
            updates["model"] = path.stem
        for key in ("model", "gpu_layers", "context_tokens", "threads"):
            if key in payload and payload[key] is not None:
                updates[key] = payload[key]
        if not updates:
            return {"ok": False, "error": "no reasoning fields to update"}
        try:
            reasoning = reasoning.model_copy(update=updates)
            self.settings = self.settings.model_copy(update={"reasoning": reasoning})
            path = save_settings(self.settings)
            _sync_controller_settings(self, self.settings)
        except Exception as exc:
            return {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
        health = self._llm_health()
        return {
            "ok": True,
            "path": str(path),
            "reasoning": health,
            "restart_recommended": True,
        }

    def _research_health(self) -> dict[str, Any]:
        ctrl = self.controller
        started = ctrl is not None and bool(getattr(ctrl, "_started", False))
        agent = False
        if started:
            agents = getattr(ctrl, "_agents", {}) or {}
            agent = "research" in agents
        message = "Research ready."
        if not started:
            message = "Controller not started — restart with python -m astra (not --demo)."
        elif not agent:
            message = "Research agent failed to register — check logs/primus-startup.log."
        return {
            "controller_started": started,
            "research_agent": agent,
            "research_ready": started and agent,
            "allow_external": bool(getattr(self.settings.research, "allow_external", False)),
            "research_message": message,
        }

    def rag_status(self) -> dict[str, Any]:
        memory_path = str(self.settings.memory.database_path)
        index_path = str(self.settings.memory.vector_index_path)
        knowledge_path = str(self.settings.knowledge.database_path)
        memory_count = 0
        knowledge_count = 0
        mem = self.list_memory()
        if mem.get("ok"):
            memory_count = int(mem.get("count", 0))
        know = self.list_knowledge()
        if know.get("ok"):
            knowledge_count = int(know.get("count", 0))
        embed = self._embedding_health()
        research = self._research_health()
        llm = self._llm_health()
        return {
            "ok": True,
            "memory_db": memory_path,
            "vector_index": index_path,
            "knowledge_db": knowledge_path,
            "memory_records": memory_count,
            "knowledge_records": knowledge_count,
            "indexed_records": memory_count,
            "index_exists": Path(index_path).is_file(),
            **embed,
            **research,
            **llm,
        }

    def reindex_rag(self) -> dict[str, Any]:
        embed = self._embedding_health()
        if not embed.get("embedding_ready"):
            hint = str(embed.get("embedding_message") or "Embedding model unavailable.")
            if not embed.get("embedding_exists"):
                hint += " Download nomic-embed-text-v1.5.Q8_0.gguf into benchmarks/models and restart."
            return {"ok": False, "error": hint}
        if self.controller is not None and hasattr(self.controller, "rebuild_memory_index"):
            started = bool(getattr(self.controller, "_started", False))
            if started:
                try:
                    count = int(self.controller.rebuild_memory_index())
                    return {"ok": True, "indexed": count}
                except Exception as exc:
                    return {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
        return self._reindex_memory_web()

    def _reindex_memory_web(self) -> dict[str, Any]:
        """Rebuild FAISS from the web memory store when controller is unavailable."""
        store = self._memory_store()
        if store is None:
            return {"ok": False, "error": "memory unavailable"}
        try:
            from astra.core.astra_controller import _default_embed, _default_vector
            from astra.memory.indexer import MemoryIndexer

            backend = _default_embed(self.settings)
            if not backend.is_available():
                return {"ok": False, "error": "embedding model unavailable — check reasoning settings"}
            index = _default_vector(self.settings)
            ctx = int(getattr(self.settings.reasoning, "embedding_context_tokens", 2048) or 2048)
            indexer = MemoryIndexer(store, index, backend, embedding_context_tokens=ctx)
            count = int(indexer.rebuild())
            path = Path(self.settings.memory.vector_index_path)
            indexer.persist(path)
            return {"ok": True, "indexed": count}
        except Exception as exc:
            return {"ok": False, "error": f"{type(exc).__name__}: {exc}"}

    def get_resources(self) -> dict[str, Any]:
        cpu_percent: float | None = None
        ram_percent: float | None = None
        try:
            import psutil

            cpu_percent = float(psutil.cpu_percent(interval=0.05))
            ram_percent = float(psutil.virtual_memory().percent)
        except Exception:
            pass
        gpu_name = None
        vram_used_gb: float | None = None
        vram_total_gb: float | None = None
        gpu_util: float | None = None
        try:
            import torch

            if torch.cuda.is_available():
                device = torch.cuda.current_device()
                props = torch.cuda.get_device_properties(device)
                gpu_name = str(props.name)
                vram_total_gb = round(float(props.total_memory) / (1024**3), 2)
                vram_used_gb = round(float(torch.cuda.memory_allocated(device)) / (1024**3), 2)
                if vram_total_gb:
                    gpu_util = round((vram_used_gb / vram_total_gb) * 100.0, 1)
        except Exception:
            pass
        llm = self._llm_health()
        return {
            "ok": True,
            "cpu_percent": cpu_percent,
            "ram_percent": ram_percent,
            "gpu_name": gpu_name,
            "gpu_util_percent": gpu_util,
            "vram_used_gb": vram_used_gb,
            "vram_total_gb": vram_total_gb,
            **llm,
        }

    def _load_automation(self) -> dict[str, Any]:
        if not _AUTOMATION_FILE.is_file():
            return {"events": [], "scheduled": [], "recurring": []}
        try:
            payload = json.loads(_AUTOMATION_FILE.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {"events": [], "scheduled": [], "recurring": []}
        if not isinstance(payload, dict):
            return {"events": [], "scheduled": [], "recurring": []}
        return {
            "events": list(payload.get("events", []) or []),
            "scheduled": list(payload.get("scheduled", []) or []),
            "recurring": list(payload.get("recurring", []) or []),
        }

    def _save_automation(self, payload: dict[str, Any]) -> None:
        _AUTOMATION_FILE.parent.mkdir(parents=True, exist_ok=True)
        _AUTOMATION_FILE.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    def list_automation(self) -> dict[str, Any]:
        data = self._load_automation()
        return {"ok": True, **data}

    def add_automation_event(self, payload: dict[str, Any]) -> dict[str, Any]:
        name = str(payload.get("name", "") or "").strip()
        trigger = str(payload.get("trigger", "") or "").strip()
        action = str(payload.get("action", "") or "").strip()
        if not name or not trigger or not action:
            return {"ok": False, "error": "name, trigger, action required"}
        data = self._load_automation()
        entry = {"name": name, "trigger": trigger, "action": action}
        data["events"] = list(data.get("events", [])) + [entry]
        self._save_automation(data)
        return {"ok": True, "event": entry}

    def add_scheduled_task(self, payload: dict[str, Any]) -> dict[str, Any]:
        name = str(payload.get("name", "") or "").strip()
        run_at = str(payload.get("run_at", "") or "").strip()
        action = str(payload.get("action", "") or "").strip()
        if not name or not run_at or not action:
            return {"ok": False, "error": "name, run_at, action required"}
        data = self._load_automation()
        entry = {"name": name, "run_at": run_at, "action": action}
        data["scheduled"] = list(data.get("scheduled", [])) + [entry]
        self._save_automation(data)
        return {"ok": True, "task": entry}

    def add_recurring_task(self, payload: dict[str, Any]) -> dict[str, Any]:
        name = str(payload.get("name", "") or "").strip()
        interval_hours = payload.get("interval_hours")
        action = str(payload.get("action", "") or "").strip()
        if not name or not action:
            return {"ok": False, "error": "name and action required"}
        try:
            hours = max(1, int(interval_hours))
        except (TypeError, ValueError):
            return {"ok": False, "error": "invalid interval_hours"}
        data = self._load_automation()
        entry = {"name": name, "interval_hours": hours, "action": action}
        data["recurring"] = list(data.get("recurring", [])) + [entry]
        self._save_automation(data)
        return {"ok": True, "task": entry}


__all__ = ["WebSystemMixin", "parse_importance"]
