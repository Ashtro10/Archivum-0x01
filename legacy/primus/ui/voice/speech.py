"""Unified offline TTS entrypoint."""

from __future__ import annotations

import atexit
import base64
import concurrent.futures
import json
import os
import subprocess
import sys
import threading
from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from astra.configuration.settings import VoiceSettings

# Kokoro/RVC use PyTorch — must stay off the HTTP/LLM process (native crash).
_TTS_EXECUTOR = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="astra-tts")
_KOKORO_LOCK = threading.Lock()
_KOKORO_PROC: subprocess.Popen[bytes] | None = None
_KOKORO_TIMEOUT = 170.0


@dataclass(frozen=True)
class TtsResult:
    pcm: bytes
    sample_rate: int
    channels: int = 1


def warmup(settings: VoiceSettings) -> None:
    """Preload configured TTS engine in the isolated worker when needed."""
    if not settings.tts_enabled:
        return
    engine = str(settings.tts_engine or "kokoro").strip().lower()
    if engine == "kokoro":
        _synthesize_kokoro_isolated("Ready.")
        return
    if engine == "optimus":
        from ui.voice.optimus_tts import warmup as optimus_warmup

        optimus_warmup(settings)


def schedule_warmup(settings: VoiceSettings) -> None:
    """Preload models on the TTS worker thread without blocking startup."""
    if not settings.tts_enabled:
        return
    _TTS_EXECUTOR.submit(_warmup_worker, settings)


def _warmup_worker(settings: VoiceSettings) -> None:
    try:
        _synthesize_unlocked("Ready.", settings)
    except Exception:
        pass


def synthesize(text: str, *, settings: VoiceSettings) -> TtsResult:
    """Generate mono PCM for the configured engine."""
    future = _TTS_EXECUTOR.submit(_synthesize_unlocked, text, settings)
    return future.result()


def _synthesize_unlocked(text: str, settings: VoiceSettings) -> TtsResult:
    engine = str(settings.tts_engine or "kokoro").strip().lower()
    if engine == "optimus":
        return _synthesize_optimus_isolated(text)
    if engine == "kokoro":
        return _synthesize_kokoro_isolated(text)
    raise SpeechError(f"unsupported TTS engine: {engine}")


def _synthesize_kokoro_isolated(text: str) -> TtsResult:
    """Talk to a persistent Kokoro child. Parent stays alive if PyTorch dies."""
    with _KOKORO_LOCK:
        try:
            return _kokoro_roundtrip(_ensure_kokoro_worker(), text)
        except Exception:
            _stop_kokoro_worker()
            return _kokoro_roundtrip(_ensure_kokoro_worker(), text)


def _ensure_kokoro_worker() -> subprocess.Popen[bytes]:
    global _KOKORO_PROC
    proc = _KOKORO_PROC
    if proc is not None and proc.poll() is None:
        return proc
    from astra.configuration.paths import project_root

    env = os.environ.copy()
    env["PYTHONUNBUFFERED"] = "1"
    env.setdefault("HF_HUB_OFFLINE", "1")
    env.setdefault("TRANSFORMERS_OFFLINE", "1")
    kwargs: dict[str, object] = {}
    if sys.platform == "win32":
        kwargs["creationflags"] = int(getattr(subprocess, "CREATE_NO_WINDOW", 0))
    started = subprocess.Popen(
        [sys.executable, "-m", "ui.voice.kokoro_worker"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=str(project_root()),
        env=env,
        bufsize=0,
        **kwargs,  # type: ignore[arg-type]
    )
    if started.stdin is None or started.stdout is None:
        started.kill()
        raise SpeechError("Kokoro TTS worker pipes unavailable")
    if started.stderr is not None:
        threading.Thread(
            target=_drain_pipe,
            args=(started.stderr,),
            daemon=True,
            name="kokoro-stderr",
        ).start()
    _KOKORO_PROC = started
    return started


def _kokoro_roundtrip(proc: subprocess.Popen[bytes], text: str) -> TtsResult:
    if proc.stdin is None or proc.stdout is None:
        raise SpeechError("Kokoro TTS worker pipes unavailable")
    if proc.poll() is not None:
        raise SpeechError("Kokoro TTS worker exited")
    payload = json.dumps({"text": text}, ensure_ascii=True).encode("utf-8") + b"\n"
    try:
        proc.stdin.write(payload)
        proc.stdin.flush()
    except Exception as exc:
        raise SpeechError(f"Kokoro TTS worker write failed: {exc}") from exc
    line = _readline_timeout(proc, _KOKORO_TIMEOUT)
    if not line:
        raise SpeechError("Kokoro TTS worker returned no output")
    try:
        payload_out = json.loads(line.decode("utf-8", errors="replace"))
    except json.JSONDecodeError as exc:
        raise SpeechError("Kokoro TTS worker returned invalid output") from exc
    if not payload_out.get("ok"):
        raise SpeechError(str(payload_out.get("error") or "Kokoro TTS failed"))
    return TtsResult(
        pcm=base64.b64decode(str(payload_out["pcm_b64"])),
        sample_rate=int(payload_out["sample_rate"]),
        channels=int(payload_out.get("channels") or 1),
    )


def _readline_timeout(proc: subprocess.Popen[bytes], timeout: float) -> bytes:
    holder: list[bytes] = []

    def _read() -> None:
        assert proc.stdout is not None
        holder.append(proc.stdout.readline())

    thread = threading.Thread(target=_read, daemon=True, name="kokoro-read")
    thread.start()
    thread.join(timeout)
    if thread.is_alive():
        _stop_kokoro_worker()
        raise SpeechError("Kokoro TTS timed out")
    return holder[0] if holder else b""


def _drain_pipe(pipe: object) -> None:
    try:
        while True:
            chunk = pipe.readline()  # type: ignore[attr-defined]
            if not chunk:
                break
    except Exception:
        return


def _stop_kokoro_worker() -> None:
    global _KOKORO_PROC
    proc = _KOKORO_PROC
    _KOKORO_PROC = None
    if proc is None:
        return
    try:
        if proc.poll() is None:
            proc.kill()
        proc.wait(timeout=2)
    except Exception:
        pass


atexit.register(_stop_kokoro_worker)


def _synthesize_optimus_isolated(text: str) -> TtsResult:
    """Run Optimus RVC in a child process so a native crash cannot kill Primus."""
    from astra.configuration.paths import project_root

    env = os.environ.copy()
    env.setdefault("RVC_ENCODER", "contentvec")
    proc = subprocess.run(
        [sys.executable, "-m", "ui.voice.optimus_worker"],
        input=json.dumps({"text": text}).encode("utf-8"),
        capture_output=True,
        cwd=str(project_root()),
        env=env,
        timeout=300,
    )
    stdout = proc.stdout.decode("utf-8", errors="replace").strip()
    if proc.returncode != 0 and not stdout:
        stderr = proc.stderr.decode("utf-8", errors="replace").strip()
        raise SpeechError(stderr or "Optimus TTS subprocess failed")
    try:
        payload = json.loads(stdout)
    except json.JSONDecodeError as exc:
        stderr = proc.stderr.decode("utf-8", errors="replace").strip()
        raise SpeechError(stderr or "Optimus TTS returned invalid output") from exc
    if not payload.get("ok"):
        raise SpeechError(str(payload.get("error") or "Optimus TTS failed"))
    return TtsResult(
        pcm=base64.b64decode(str(payload["pcm_b64"])),
        sample_rate=int(payload["sample_rate"]),
        channels=int(payload.get("channels") or 1),
    )


class SpeechError(Exception):
    """TTS request failed."""


__all__ = [
    "SpeechError",
    "TtsResult",
    "schedule_warmup",
    "synthesize",
    "warmup",
]
