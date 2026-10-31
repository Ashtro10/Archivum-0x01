"""Browser UI for Primus — JARVIS background + Talk / System shell."""

from __future__ import annotations

import json
import mimetypes
import socket
import threading
import time
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Iterator

from astra.presentation.conversation import ConversationPort, UnavailableConversation
from astra.product import GREETING
from astra.state import StateProvider
from ui.a11y import prefers_reduced_motion
from ui.visual.jarvis_host import jarvis_assets_dir
from ui.web_api import PrimusWebRuntime

_WEB_ROOT = Path(__file__).resolve().parent / "web"
_DEFAULT_PORT = 8765
_CLIENT_ABORT = (BrokenPipeError, ConnectionResetError, ConnectionAbortedError)


def _resolve_web_root() -> Path:
    from astra.configuration.paths import project_root

    root = project_root()
    for candidate in (
        _WEB_ROOT,
        root / "ui" / "web",
        root / "_internal" / "ui" / "web",
    ):
        if (candidate / "primus.html").is_file():
            return candidate
    return _WEB_ROOT


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def _guess_type(path: Path) -> str:
    ctype, _ = mimetypes.guess_type(str(path))
    return ctype or "application/octet-stream"


def _make_handler(
    runtime: PrimusWebRuntime, jarvis_root: Path, web_root: Path
) -> type[BaseHTTPRequestHandler]:

    class Handler(BaseHTTPRequestHandler):
        server_version = "PrimusWebUI/2.0"

        def log_message(self, _format: str, *_args: Any) -> None:
            return

        def _send_bytes(
            self, status: int, body: bytes, content_type: str, extra_headers: dict[str, str] | None = None
        ) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            if extra_headers:
                for key, value in extra_headers.items():
                    self.send_header(key, value)
            self.end_headers()
            try:
                self.wfile.write(body)
            except _CLIENT_ABORT:
                return

        def _send_json(self, status: int, payload: dict[str, Any]) -> None:
            body = json.dumps(payload).encode("utf-8")
            self._send_bytes(status, body, "application/json; charset=utf-8")

        def _send_sse(self, events: Iterator[dict[str, Any]]) -> None:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.end_headers()
            try:
                for event in events:
                    body = json.dumps(event).encode("utf-8")
                    try:
                        self.wfile.write(b"data: ")
                        self.wfile.write(body)
                        self.wfile.write(b"\n\n")
                        self.wfile.flush()
                    except _CLIENT_ABORT:
                        break
            finally:
                close = getattr(events, "close", None)
                if callable(close):
                    try:
                        close()
                    except Exception:
                        pass

        def _read_json(self) -> dict[str, Any]:
            length = int(self.headers.get("Content-Length", "0") or "0")
            raw = self.rfile.read(length) if length else b"{}"
            try:
                payload = json.loads(raw.decode("utf-8"))
            except json.JSONDecodeError as exc:
                raise ValueError("invalid json") from exc
            return payload if isinstance(payload, dict) else {}

        def _serve_path(self, path: Path) -> None:
            if not path.is_file():
                self._send_bytes(404, b"not found", "text/plain; charset=utf-8")
                return
            self._send_bytes(200, path.read_bytes(), _guess_type(path))

        def do_GET(self) -> None:  # noqa: N802
            try:
                self._dispatch_get()
            except _CLIENT_ABORT:
                return
            except Exception as exc:
                try:
                    self._send_json(500, {"ok": False, "error": f"{type(exc).__name__}: {exc}"})
                except Exception:
                    return

        def _dispatch_get(self) -> None:
            route = self.path.split("?", 1)[0]
            if route in {"/", "/index.html"}:
                self._serve_path(web_root / "primus.html")
                return
            if route.startswith("/static/"):
                rel = route[len("/static/") :]
                target = (web_root / rel).resolve()
                if web_root.resolve() not in target.parents and target != web_root.resolve():
                    self._send_bytes(403, b"forbidden", "text/plain; charset=utf-8")
                    return
                self._serve_path(target)
                return
            if route.startswith("/jarvis/"):
                rel = route[len("/jarvis/") :]
                target = (jarvis_root / rel).resolve()
                if jarvis_root.resolve() not in target.parents and target != jarvis_root.resolve():
                    self._send_bytes(403, b"forbidden", "text/plain; charset=utf-8")
                    return
                self._serve_path(target)
                return
            if route == "/api/greeting":
                self._send_json(200, {"greeting": GREETING})
                return
            if route == "/api/health":
                self._send_json(200, {"ok": True})
                return
            if route == "/api/config":
                self._send_json(200, runtime.get_config())
                return
            if route == "/api/memory":
                self._send_json(200, runtime.list_memory())
                return
            if route == "/api/knowledge":
                self._send_json(200, runtime.list_knowledge())
                return
            if route == "/api/rag/status":
                self._send_json(200, runtime.rag_status())
                return
            if route == "/api/resources":
                self._send_json(200, runtime.get_resources())
                return
            if route == "/api/automation":
                self._send_json(200, runtime.list_automation())
                return
            if route == "/api/state":
                self._send_json(200, runtime.system_state())
                return
            if route == "/api/voice/state":
                self._send_json(200, runtime.voice_state())
                return
            self._send_bytes(404, b"not found", "text/plain; charset=utf-8")

        def _read_bytes(self) -> bytes:
            length = int(self.headers.get("Content-Length", "0") or "0")
            return self.rfile.read(length) if length else b""

        def do_POST(self) -> None:  # noqa: N802
            try:
                self._dispatch_post()
            except _CLIENT_ABORT:
                return
            except Exception as exc:
                try:
                    self._send_json(500, {"ok": False, "error": f"{type(exc).__name__}: {exc}"})
                except Exception:
                    return

        def _dispatch_post(self) -> None:
            route = self.path.split("?", 1)[0]
            if route == "/api/voice/stt":
                content_type = self.headers.get("Content-Type", "") or ""
                if "octet-stream" in content_type or content_type.startswith("audio/"):
                    pcm = self._read_bytes()
                    sample_rate = int(self.headers.get("X-Sample-Rate", "16000") or "16000")
                    self._send_json(200, runtime.transcribe_audio(pcm, sample_rate))
                    return
                self._send_json(400, {"ok": False, "error": "send PCM as application/octet-stream"})
                return
            try:
                payload = self._read_json()
            except ValueError:
                self._send_json(400, {"ok": False, "error": "invalid json"})
                return
            if route == "/api/chat":
                self._send_json(200, runtime.chat(str(payload.get("message", ""))))
                return
            if route == "/api/chat/stream":
                self._send_sse(runtime.chat_stream(str(payload.get("message", ""))))
                return
            if route == "/api/memory/forget":
                self._send_json(200, runtime.forget_memory(str(payload.get("record_id", ""))))
                return
            if route == "/api/memory/add":
                self._send_json(200, runtime.add_memory(payload))
                return
            if route == "/api/memory/importance":
                self._send_json(200, runtime.set_memory_importance(payload))
                return
            if route == "/api/knowledge/upload":
                self._send_json(200, runtime.upload_knowledge(payload))
                return
            if route == "/api/knowledge/sync":
                self._send_json(200, runtime.sync_knowledge_folder(payload))
                return
            if route == "/api/rag/reindex":
                self._send_json(200, runtime.reindex_rag())
                return
            if route == "/api/automation/event":
                self._send_json(200, runtime.add_automation_event(payload))
                return
            if route == "/api/automation/schedule":
                self._send_json(200, runtime.add_scheduled_task(payload))
                return
            if route == "/api/automation/recurring":
                self._send_json(200, runtime.add_recurring_task(payload))
                return
            if route == "/api/personality":
                self._send_json(200, runtime.update_personality(payload))
                return
            if route == "/api/research":
                self._send_json(200, runtime.update_research(payload))
                return
            if route == "/api/reasoning":
                self._send_json(200, runtime.update_reasoning(payload))
                return
            if route == "/api/voice/mode":
                self._send_json(200, runtime.set_voice_mode(payload))
                return
            if route == "/api/voice/pcm":
                self._send_json(200, runtime.ingest_voice_pcm(payload))
                return
            if route == "/api/voice/tts":
                outcome = runtime.synthesize_speech(payload)
                pcm = outcome.get("pcm")
                if outcome.get("ok") and isinstance(pcm, (bytes, bytearray)):
                    rate = int(outcome.get("sample_rate", 24000))
                    channels = int(outcome.get("channels", 1))
                    self._send_bytes(
                        200,
                        bytes(pcm),
                        f"audio/L16;rate={rate};channels={channels}",
                        {
                            "X-Sample-Rate": str(rate),
                            "X-Audio-Channels": str(channels),
                        },
                    )
                    return
                safe = {k: v for k, v in outcome.items() if k != "pcm"}
                self._send_json(200, safe)
                return
            self._send_bytes(404, b"not found", "text/plain; charset=utf-8")

    return Handler


class PrimusHTTPServer(ThreadingHTTPServer):
    """Threading server that does not dump a traceback on a client abort."""

    def handle_error(self, request: object, client_address: object) -> None:
        import sys as _sys

        etype = _sys.exc_info()[0]
        if etype is not None and issubclass(etype, _CLIENT_ABORT):
            return
        super().handle_error(request, client_address)


def run_browser_ui(
    *,
    provider: StateProvider,
    conversation: ConversationPort | None = None,
    controller: object | None = None,
    smoke: bool = False,
    reduced_motion: bool | None = None,
    port: int | None = None,
) -> None:
    """Serve browser UI and open default browser. Blocks until interrupted."""
    jarvis_root = jarvis_assets_dir()
    web_root = _resolve_web_root()
    if not (jarvis_root / "index.html").is_file():
        raise FileNotFoundError(f"jarvis assets missing: {jarvis_root / 'index.html'}")
    if not (web_root / "primus.html").is_file():
        raise FileNotFoundError(f"web shell missing: {web_root / 'primus.html'}")

    talk = conversation or UnavailableConversation()
    motion = prefers_reduced_motion() if reduced_motion is None else reduced_motion
    runtime = PrimusWebRuntime(
        provider=provider,
        conversation=talk,
        controller=controller,
        reduced_motion=motion,
    )
    chosen = _free_port() if smoke else (port if port is not None else _DEFAULT_PORT)
    handler = _make_handler(runtime, jarvis_root, web_root)
    try:
        server = PrimusHTTPServer(("127.0.0.1", chosen), handler)
    except OSError as exc:
        if smoke:
            chosen = _free_port()
            server = PrimusHTTPServer(("127.0.0.1", chosen), handler)
        else:
            import sys

            print(
                f"\nPrimus could not bind to port {chosen}. "
                "Another copy is probably still running.\n"
                "Close all python.exe Primus windows, or run in Task Manager, then start again.\n",
                file=sys.stderr,
            )
            raise SystemExit(1) from exc

    def _serve() -> None:
        server.serve_forever()

    thread = threading.Thread(target=_serve, daemon=True, name="primus-web")
    thread.start()
    url = f"http://127.0.0.1:{chosen}/"
    print(f"Primus running at {url}")
    if not smoke:
        try:
            from ui.voice.speech import schedule_warmup

            schedule_warmup(runtime.settings.voice)
        except Exception:
            pass

    def _shutdown() -> None:
        try:
            from ui.voice.speech import _stop_kokoro_worker

            _stop_kokoro_worker()
        except Exception:
            pass
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)

    if smoke:
        last_error: Exception | None = None
        for _ in range(30):
            try:
                with urllib.request.urlopen(url, timeout=1) as response:
                    if response.status == 200:
                        last_error = None
                        break
            except Exception as exc:
                last_error = exc
                time.sleep(0.1)
        if last_error is not None:
            raise RuntimeError(f"smoke failed: {last_error}") from last_error
        with urllib.request.urlopen(f"{url}api/config", timeout=2) as response:
            if response.status != 200:
                raise RuntimeError("smoke config failed")
        with urllib.request.urlopen(f"{url}api/voice/state", timeout=2) as response:
            if response.status != 200:
                raise RuntimeError("smoke voice failed")
        _shutdown()
        return

    webbrowser.open(url)
    try:
        while thread.is_alive():
            thread.join(timeout=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        _shutdown()


__all__ = ["run_browser_ui"]
