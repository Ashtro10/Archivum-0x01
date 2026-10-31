"""Optimus Prime TTS — Kokoro base speech + local RVC voice conversion."""

from __future__ import annotations

import os
import re
import sys
import contextlib
import io
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from astra.configuration.settings import VoiceSettings

# ContentVec encoder avoids fairseq build on Windows.
os.environ.setdefault("RVC_ENCODER", "contentvec")

DEFAULT_SAMPLE_RATE = 48000
DEFAULT_MODEL = "optimus_720e_5040s.pth"
DEFAULT_INDEX = "added_IVF216_Flat_nprobe_1_v2.index"
_RVC_WARMED = False


class OptimusTtsError(Exception):
    """Optimus RVC TTS request failed."""


@dataclass(frozen=True)
class OptimusTtsResult:
    pcm: bytes
    sample_rate: int = DEFAULT_SAMPLE_RATE
    channels: int = 1


def project_root() -> Path:
    from astra.configuration.paths import project_root as root

    return root()


def default_optimus_dir() -> Path:
    return project_root() / "Optimus Prime"


def resolve_model_path(settings: VoiceSettings) -> Path:
    configured = settings.optimus_model_path
    if configured is not None:
        path = Path(configured).expanduser()
        if path.is_file():
            return path.resolve()
        if not path.is_absolute():
            path = project_root() / path
        if path.is_file():
            return path.resolve()
        raise OptimusTtsError(f"voice.optimus_model_path missing file: {path}")
    bundled = default_optimus_dir() / DEFAULT_MODEL
    if bundled.is_file():
        return bundled.resolve()
    raise OptimusTtsError(
        "Optimus RVC model not found — place optimus_720e_5040s.pth under Optimus Prime/"
    )


def resolve_index_path(settings: VoiceSettings) -> Path | None:
    configured = settings.optimus_index_path
    if configured is not None:
        path = Path(configured).expanduser()
        if path.is_file():
            return path.resolve()
        if not path.is_absolute():
            path = project_root() / path
        if path.is_file():
            return path.resolve()
        raise OptimusTtsError(f"voice.optimus_index_path missing file: {path}")
    bundled = default_optimus_dir() / DEFAULT_INDEX
    return bundled.resolve() if bundled.is_file() else None


def resolve_device(settings: VoiceSettings) -> str:
    requested = str(settings.optimus_device or "auto").strip().lower() or "auto"
    try:
        import torch
    except ImportError as exc:
        raise OptimusTtsError("Optimus deps missing — run: pip install torch rvc_py kokoro") from exc
    if requested == "cpu":
        return "cpu"
    if requested in ("auto", "cuda", "gpu"):
        return "cuda" if torch.cuda.is_available() else "cpu"
    return requested


_SENTENCE_SPLIT = re.compile(r"(?<=\D)(?<=[.!?…])\s+(?!\d+\.)")


def _prosody_text(text: str) -> str:
    """Keep punctuation; normalize spacing only."""
    line = str(text or "").strip()
    if not line:
        return line
    line = line.replace("…", "...")
    line = re.sub(r"\s+", " ", line)
    line = line.replace(" — ", ", ").replace(" - ", ", ")
    return line


def _split_sentences(text: str) -> list[str]:
    line = _prosody_text(text)
    if not line:
        return []
    parts = [part.strip() for part in _SENTENCE_SPLIT.split(line) if part.strip()]
    if parts:
        return parts
    return [line]


def _float_to_pcm16(waveform) -> bytes:
    import numpy as np

    audio = np.asarray(waveform, dtype=np.float32)
    audio = np.clip(audio, -1.0, 1.0)
    return (audio * 32767.0).astype(np.int16).tobytes()


def _base_speech(text: str, settings: VoiceSettings):
    from ui.voice.kokoro_tts import KokoroTtsError, synthesize_waveform

    line = _prosody_text(text)
    if not line:
        raise OptimusTtsError("empty text")
    try:
        return synthesize_waveform(line, settings=settings)
    except KokoroTtsError as exc:
        raise OptimusTtsError(str(exc)) from exc


def _warm_rvc_once(settings: VoiceSettings) -> None:
    global _RVC_WARMED
    if _RVC_WARMED:
        return
    try:
        synthesize("Ready.", settings=settings)
        _RVC_WARMED = True
    except Exception:
        pass


def warmup(settings: VoiceSettings) -> None:
    """No startup preload — models load on first speak to avoid GPU crash."""
    return


@contextlib.contextmanager
def _safe_rvc_stdout():
    """rvc_py prints Unicode on load; Windows cp1252 consoles raise UnicodeEncodeError."""
    buffer = io.StringIO()
    previous = sys.stdout
    try:
        sys.stdout = buffer
        yield
    finally:
        sys.stdout = previous


def synthesize(text: str, *, settings: VoiceSettings) -> OptimusTtsResult:
    """Generate Optimus Prime speech via Kokoro + RVC."""
    if not _split_sentences(text):
        raise OptimusTtsError("empty text")

    base, base_sr = _base_speech(text, settings)
    model_path = str(resolve_model_path(settings))
    index_path = resolve_index_path(settings)
    device = resolve_device(settings)
    index_rate = float(settings.optimus_index_rate)
    f0_method = str(settings.optimus_f0_method or "rmvpe").strip().lower() or "rmvpe"

    try:
        from rvc_py.rvc_infer import rvc_infer
    except ImportError as exc:
        raise OptimusTtsError(
            "rvc_py missing — run: pip install git+https://github.com/Sergey004/rvc_py.git"
        ) from exc

    try:
        with _safe_rvc_stdout():
            converted, out_sr = rvc_infer(
                wav=base,
                sr=base_sr,
                rvc_model_path=model_path,
                index_path=str(index_path) if index_path is not None else None,
                index_rate=index_rate if index_path is not None else 0.0,
                device=device,
                f0_method=f0_method,
                fp16=device.startswith("cuda"),
                rms_mix_rate=float(settings.optimus_rms_mix_rate),
                protect=float(settings.optimus_protect),
            )
    except Exception as exc:
        raise OptimusTtsError(f"Optimus RVC failed: {exc}") from exc

    global _RVC_WARMED
    _RVC_WARMED = True
    return OptimusTtsResult(pcm=_float_to_pcm16(converted), sample_rate=int(out_sr))


__all__ = [
    "DEFAULT_SAMPLE_RATE",
    "OptimusTtsError",
    "OptimusTtsResult",
    "default_optimus_dir",
    "resolve_index_path",
    "resolve_model_path",
    "synthesize",
    "warmup",
]
