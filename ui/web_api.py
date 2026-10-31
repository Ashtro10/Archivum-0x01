"""Backend boundary for the Primus web UI."""

from __future__ import annotations

import json
from dataclasses import asdict, is_dataclass
from typing import Any, Iterator

from astra.configuration import load_settings, save_settings
from astra.configuration.errors import ConfigurationError
from astra.memory.contracts import MemoryRecord, VerificationState
from ui.visual.jarvis_host import map_visual_state
from astra.presentation.conversation import ConversationPort
from astra.product import GREETING, PRODUCT_NAME
from astra.state import StateProvider
from ui.visual.manager import VisualStateManager
from ui.visual.states import ACTIVITY_COPY, VisualState
from ui.web_system import WebSystemMixin, _sync_controller_settings
from ui.voice.speech import SpeechError, synthesize as synthesize_speech
from ui.voice.optimus_tts import default_optimus_dir, resolve_index_path, resolve_model_path as resolve_optimus_model
from ui.voice.bus import VoiceBus
from ui.voice.whisper_stt import WhisperSttError, transcribe_pcm_bytes


def _optimus_model_path(voice) -> str | None:
    try:
        return str(resolve_optimus_model(voice))
    except Exception:
        return None


def _optimus_index_path(voice) -> str | None:
    try:
        path = resolve_index_path(voice)
        return str(path) if path is not None else None
    except Exception:
        return None


def _serialize(obj: Any) -> Any:
    if is_dataclass(obj) and not isinstance(obj, type):
        return {k: _serialize(v) for k, v in asdict(obj).items()}
    if isinstance(obj, dict):
        return {str(k): _serialize(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_serialize(v) for v in obj]
    if hasattr(obj, "model_dump"):
        return obj.model_dump()
    if hasattr(obj, "isoformat"):
        try:
            return obj.isoformat()
        except Exception:
            return str(obj)
    return obj


class PrimusWebRuntime(WebSystemMixin):
    """Shared backend state for HTTP handlers."""

    def __init__(
        self,
        *,
        provider: StateProvider,
        conversation: ConversationPort,
        controller: object | None,
        reduced_motion: bool,
    ) -> None:
        self.provider = provider
        self.conversation = conversation
        self.controller = controller
        self.visual = VisualStateManager()
        self.voice = VoiceBus()
        self.reduced_motion = reduced_motion
        self._busy = False
        self._busy_since: float | None = None
        disk_settings = load_settings()
        self.settings = disk_settings
        if controller is not None:
            ctrl_settings = getattr(controller, "settings", None)
            if ctrl_settings is not None:
                self.settings = ctrl_settings.model_copy(
                    update={"reasoning": disk_settings.reasoning}
                )
                _sync_controller_settings(self, self.settings)
                if ctrl_settings.reasoning.model_path != disk_settings.reasoning.model_path:
                    refresh = getattr(controller, "refresh_reasoning_backend", None)
                    if callable(refresh):
                        try:
                            refresh()
                        except Exception:
                            pass
        self._init_system_stores()

    def _start_voice_preload(self) -> None:
        """Voice models load on first speak — not at startup (saves RAM with LLM)."""

    def _llm_is_ready(self) -> bool:
        ctrl = self.controller
        if ctrl is None:
            return False
        try:
            llm = getattr(ctrl, "llm", None)
            if llm is None:
                llm = getattr(ctrl, "_llm", None)
            return bool(llm is not None and llm.is_available())
        except Exception:
            return False

    def _clear_stale_busy(self, *, max_seconds: float = 180.0) -> None:
        if not self._busy:
            return
        import time

        started = self._busy_since
        if started is None:
            self._busy = False
            return
        if time.monotonic() - started >= max_seconds:
            self._busy = False
            self._busy_since = None

    def _set_busy(self, busy: bool) -> None:
        import time

        self._busy = busy
        self._busy_since = time.monotonic() if busy else None

    def _reload_reasoning_from_disk(self) -> bool:
        """Pick up astra.toml changes without a full process restart."""
        try:
            fresh = load_settings()
        except Exception:
            return False
        current = getattr(self.settings, "reasoning", None)
        if (
            current is not None
            and current.model_path == fresh.reasoning.model_path
            and current.gpu_layers == fresh.reasoning.gpu_layers
            and current.context_tokens == fresh.reasoning.context_tokens
            and self._llm_is_ready()
        ):
            return False
        self.settings = self.settings.model_copy(update={"reasoning": fresh.reasoning})
        _sync_controller_settings(self, self.settings)
        refresh = getattr(self.controller, "refresh_reasoning_backend", None)
        if callable(refresh):
            refresh()
        return True

    def chat(self, message: str) -> dict[str, Any]:
        text = str(message or "").strip()
        if not text:
            return {"ok": False, "error": "empty message"}
        self._clear_stale_busy()
        if self._busy:
            return {"ok": False, "error": "busy", "retry": True}
        if not self._llm_is_ready():
            self._reload_reasoning_from_disk()
        self._set_busy(True)
        self.visual.set_interaction(VisualState.THINKING)
        self.voice.set_state("thinking")
        memory_updated = False
        memory_notice = ""
        try:
            if hasattr(self.conversation, "process"):
                result = self.conversation.process(text)
                reply = str(getattr(result, "text", "") or "")
                status = str(getattr(result, "status", "") or "success")
                activity = str(getattr(result, "activity", "") or "")
                research_used = bool(getattr(result, "research_used", False))
                used_network = bool(getattr(result, "used_network", False))
                persisted = tuple(getattr(result, "memory_persisted", ()) or ())
                memory_updated = bool(persisted)
                if memory_updated:
                    memory_notice = "Memory updated"
            else:
                result = self.conversation.ask(text)
                reply = str(getattr(result, "text", "") or "")
                status = str(getattr(result, "status", "") or "success")
                activity = str(getattr(result, "activity", "") or "")
                research_used = False
                used_network = False
        except Exception as exc:
            self.visual.set_interaction(VisualState.ERROR)
            self.voice.set_state("error")
            visual = self.visual.resolve(self.provider.get_state())
            return {
                "ok": True,
                "reply": "I couldn't reach my language model.",
                "status": "error",
                "activity": ACTIVITY_COPY[VisualState.ERROR],
                "jarvis_state": map_visual_state(visual),
                "memory_updated": False,
                "memory_notice": "I couldn't save that memory.",
                "error_detail": f"{type(exc).__name__}",
            }
        finally:
            self._set_busy(False)

        if status == "success":
            visual_state = VisualState.SUCCESS
        elif status == "error":
            visual_state = VisualState.ERROR
        else:
            visual_state = VisualState.WARNING
        if research_used:
            self.visual.set_interaction(VisualState.RETRIEVING)
            visual_state = VisualState.RETRIEVING
        else:
            self.visual.set_interaction(visual_state)
        visual = self.visual.resolve(self.provider.get_state())
        self.voice.set_state(map_visual_state(visual).lower())
        if not activity:
            activity = ACTIVITY_COPY.get(visual_state, "")
        if research_used and used_network:
            activity = activity or "Researching…"
        return {
            "ok": True,
            "reply": reply,
            "status": status,
            "activity": activity,
            "jarvis_state": map_visual_state(visual),
            "research_used": research_used,
            "memory_updated": memory_updated,
            "memory_notice": memory_notice,
        }

    def chat_stream(self, message: str) -> Iterator[dict[str, Any]]:
        """Stream chat tokens for live UI + incremental TTS."""
        text = str(message or "").strip()
        if not text:
            yield {"type": "error", "ok": False, "error": "empty message"}
            return
        self._clear_stale_busy()
        if self._busy:
            yield {"type": "error", "ok": False, "error": "busy", "retry": True}
            return
        if not self._llm_is_ready():
            self._reload_reasoning_from_disk()

        self._set_busy(True)
        self.visual.set_interaction(VisualState.THINKING)
        self.voice.set_state("thinking")
        try:
            stream_fn = getattr(self.conversation, "process_stream", None)
            if callable(stream_fn):
                for event in stream_fn(text):
                    kind = str(event.get("type", "") or "")
                    if kind == "status":
                        activity = str(event.get("activity", "") or "")
                        jarvis = str(event.get("jarvis_state", "") or "THINKING")
                        self.visual.set_interaction(getattr(VisualState, jarvis, VisualState.THINKING))
                        self.voice.set_state(jarvis.lower())
                    elif kind == "done":
                        jarvis = str(event.get("jarvis_state", "") or "IDLE")
                        self.visual.set_interaction(getattr(VisualState, jarvis, VisualState.IDLE))
                        self.voice.set_state(jarvis.lower())
                    yield event
                return

            if hasattr(self.conversation, "process"):
                result = self.conversation.process(text)
                reply = str(getattr(result, "text", "") or "")
                activity = str(getattr(result, "activity", "") or "")
                status = str(getattr(result, "status", "") or "success")
                memory_updated = bool(getattr(result, "memory_persisted", ()) or ())
                memory_notice = "Memory updated" if memory_updated else ""
                if reply:
                    yield {"type": "delta", "text": reply}
                visual = self.visual.resolve(self.provider.get_state())
                yield {
                    "type": "done",
                    "ok": status != "error",
                    "reply": reply,
                    "status": status,
                    "activity": activity,
                    "jarvis_state": map_visual_state(visual),
                    "memory_updated": memory_updated,
                    "memory_notice": memory_notice,
                }
                return

            yield {"type": "error", "ok": False, "error": "streaming unavailable"}
        except Exception as exc:
            self.visual.set_interaction(VisualState.ERROR)
            self.voice.set_state("error")
            yield {
                "type": "error",
                "ok": False,
                "error": f"{type(exc).__name__}: {exc}",
            }
        finally:
            self._set_busy(False)

    def forget_memory(self, record_id: str) -> dict[str, Any]:
        with self._store_lock:
            store = self._memory_store()
            writer = getattr(store, "store", None)
            iterate = getattr(store, "iter_records", None)
            if store is None or not callable(writer) or not callable(iterate):
                return {"ok": False, "error": "memory unavailable"}
            target = str(record_id or "").strip()
            if not target:
                return {"ok": False, "error": "missing record_id"}
            try:
                for record in iterate():
                    if str(getattr(record, "record_id", "") or "") != target:
                        continue
                    updated = MemoryRecord(
                        record_id=record.record_id,
                        category=record.category,
                        content=record.content,
                        verification=VerificationState.REFUTED,
                        source=record.source,
                        tags=tuple(getattr(record, "tags", ()) or ()),
                    )
                    writer(updated)
                    return {"ok": True, "record_id": target}
            except Exception as exc:
                return {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
            return {"ok": False, "error": "not found"}

    def get_config(self) -> dict[str, Any]:
        personality = self.settings.personality
        research = self.settings.research
        voice = self.settings.voice
        reasoning = self._llm_health()
        return {
            "ok": True,
            "product": PRODUCT_NAME,
            "greeting": GREETING,
            "personality": {
                "name": personality.name,
                "warmth": personality.warmth,
                "directness": personality.directness,
                "humor": personality.humor,
                "formality": personality.formality,
                "verbosity": personality.verbosity,
            },
            "research": {"allow_external": research.allow_external},
            "reasoning": reasoning,
            "voice": {
                "tts_enabled": voice.tts_enabled,
                "tts_engine": voice.tts_engine,
                "kokoro_voice": voice.kokoro_voice,
                "kokoro_lang": voice.kokoro_lang,
                "optimus_model_path": _optimus_model_path(voice),
                "optimus_index_path": _optimus_index_path(voice),
                "stt_enabled": voice.stt_enabled,
                "stt_engine": voice.stt_engine,
                "whisper_model": voice.whisper_model,
            },
            "reduced_motion": self.reduced_motion,
        }

    def update_personality(self, payload: dict[str, Any]) -> dict[str, Any]:
        section = self.settings.personality
        updates: dict[str, Any] = {}
        for key in ("name", "warmth", "directness", "humor", "formality", "verbosity"):
            if key in payload:
                updates[key] = payload[key]
        try:
            personality = section.model_copy(update=updates)
            self.settings = self.settings.model_copy(update={"personality": personality})
            _sync_controller_settings(self, self.settings)
            path = save_settings(self.settings)
        except Exception as exc:
            return {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
        return {"ok": True, "path": str(path), "personality": self.get_config()["personality"]}

    def update_research(self, payload: dict[str, Any]) -> dict[str, Any]:
        if "allow_external" not in payload:
            return {"ok": False, "error": "allow_external required"}
        try:
            research = self.settings.research.model_copy(
                update={"allow_external": bool(payload["allow_external"])}
            )
            self.settings = self.settings.model_copy(update={"research": research})
            _sync_controller_settings(self, self.settings)
            refresh = getattr(self.controller, "refresh_research_agent", None)
            if callable(refresh):
                refresh()
            path = save_settings(self.settings)
        except Exception as exc:
            return {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
        return {"ok": True, "path": str(path), "research": self.get_config()["research"]}

    def system_state(self) -> dict[str, Any]:
        app_state = self.provider.get_state()
        return {"ok": True, "state": _serialize(app_state)}

    def voice_state(self) -> dict[str, Any]:
        snap = self.voice.snapshot()
        visual = self.visual.resolve(self.provider.get_state())
        return {
            "ok": True,
            "voice": snap,
            "jarvis_state": map_visual_state(visual),
        }

    def set_voice_mode(self, payload: dict[str, Any]) -> dict[str, Any]:
        mode = str(payload.get("state", "") or "").strip().lower()
        mapping = {
            "idle": VisualState.IDLE,
            "listening": VisualState.LISTENING,
            "thinking": VisualState.THINKING,
            "speaking": VisualState.SPEAKING,
            "processing": VisualState.PROCESSING,
            "research": VisualState.RETRIEVING,
            "error": VisualState.ERROR,
        }
        if mode not in mapping:
            return {"ok": False, "error": "unknown voice state"}
        self.voice.set_state(mode)
        self.visual.set_interaction(mapping[mode])
        visual = self.visual.resolve(self.provider.get_state())
        return {"ok": True, "jarvis_state": map_visual_state(visual), "voice": self.voice.snapshot()}

    def ingest_voice_pcm(self, payload: dict[str, Any]) -> dict[str, Any]:
        raw = payload.get("samples", ())
        if not isinstance(raw, list):
            return {"ok": False, "error": "samples must be a list"}
        try:
            samples = [float(v) for v in raw]
        except (TypeError, ValueError):
            return {"ok": False, "error": "invalid samples"}
        features = self.voice.ingest_pcm(samples)
        mode = str(payload.get("state", "") or self.voice.state).strip().lower()
        if mode:
            self.voice.set_state(mode)
            if mode == "listening":
                self.visual.set_interaction(VisualState.LISTENING)
            elif mode == "speaking":
                self.visual.set_interaction(VisualState.SPEAKING)
        visual = self.visual.resolve(self.provider.get_state())
        return {
            "ok": True,
            "voice": self.voice.snapshot(),
            "jarvis_state": map_visual_state(visual),
            "speak_level": features.intensity,
        }

    def _voice_settings(self):
        """Reload voice section so astra.toml edits apply without full restart."""
        try:
            fresh = load_settings()
        except ConfigurationError as exc:
            raise SpeechError(
                "Voice config mismatch — stop Primus and run `python -m astra` again. "
                f"Detail: {exc}"
            ) from exc
        voice = fresh.voice
        if self.controller is not None:
            try:
                self.controller.settings = self.controller.settings.model_copy(update={"voice": voice})
            except Exception:
                pass
        self.settings = self.settings.model_copy(update={"voice": voice})
        return voice

    def synthesize_speech(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Return offline TTS PCM audio."""
        text = str(payload.get("text", "") or "").strip()
        if not text:
            return {"ok": False, "error": "empty text"}
        try:
            voice = self._voice_settings()
        except SpeechError as exc:
            return {"ok": False, "error": str(exc)}
        if not voice.tts_enabled:
            return {"ok": False, "error": "TTS disabled"}
        try:
            result = synthesize_speech(text, settings=voice)
        except SpeechError as exc:
            return {"ok": False, "error": str(exc), "tts_engine": voice.tts_engine}
        self.voice.set_state("speaking")
        self.visual.set_interaction(VisualState.SPEAKING)
        return {
            "ok": True,
            "pcm": result.pcm,
            "sample_rate": result.sample_rate,
            "channels": result.channels,
            "tts_engine": voice.tts_engine,
            "jarvis_state": map_visual_state(self.visual.resolve(self.provider.get_state())),
        }

    def transcribe_audio(self, pcm: bytes, sample_rate: int) -> dict[str, Any]:
        """Offline Whisper STT from mono s16le PCM."""
        voice = self.settings.voice
        if not voice.stt_enabled:
            return {"ok": False, "error": "STT disabled"}
        if voice.stt_engine != "whisper":
            return {"ok": False, "error": "unsupported STT engine"}
        self.voice.set_state("listening")
        self.visual.set_interaction(VisualState.LISTENING)
        try:
            text = transcribe_pcm_bytes(pcm, sample_rate, voice)
        except WhisperSttError as exc:
            self.voice.set_state("idle")
            self.visual.set_interaction(VisualState.IDLE)
            return {"ok": False, "error": str(exc)}
        self.voice.set_state("idle")
        self.visual.set_interaction(VisualState.IDLE)
        visual = self.visual.resolve(self.provider.get_state())
        return {"ok": True, "text": text, "jarvis_state": map_visual_state(visual)}


__all__ = ["PrimusWebRuntime"]
