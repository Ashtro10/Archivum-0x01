"""Offline speech-to-text via faster-whisper."""

from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from astra.configuration.settings import VoiceSettings

WhisperSttError = RuntimeError

_MODEL = None
_MODEL_KEY: tuple[str, str, str] | None = None


def _import_whisper():
    try:
        from faster_whisper import WhisperModel
    except ImportError as exc:
        raise WhisperSttError(
            "faster-whisper not installed — run: pip install faster-whisper"
        ) from exc
    return WhisperModel


def _resolve_model_name(settings: VoiceSettings) -> str:
    configured = str(settings.whisper_model or "").strip()
    if not configured:
        return "base"
    path = Path(configured).expanduser()
    if path.is_dir():
        return str(path)
    return configured


def _get_model(settings: VoiceSettings):
    global _MODEL, _MODEL_KEY
    from faster_whisper import WhisperModel

    name = _resolve_model_name(settings)
    device = str(settings.whisper_device or "cpu").strip() or "cpu"
    compute = str(settings.whisper_compute_type or "int8").strip() or "int8"
    key = (name, device, compute)
    if _MODEL is None or _MODEL_KEY != key:
        _MODEL = WhisperModel(name, device=device, compute_type=compute)
        _MODEL_KEY = key
    return _MODEL


def _resample(audio, src_rate: int, dst_rate: int = 16000):
    import numpy as np

    samples = np.asarray(audio, dtype=np.float32)
    if samples.size == 0:
        return samples
    if src_rate == dst_rate:
        return samples
    dst_len = max(1, int(round(samples.size * dst_rate / src_rate)))
    x_old = np.linspace(0.0, 1.0, num=samples.size, endpoint=False)
    x_new = np.linspace(0.0, 1.0, num=dst_len, endpoint=False)
    return np.interp(x_new, x_old, samples).astype(np.float32)


def transcribe_pcm_bytes(pcm: bytes, sample_rate: int, settings: VoiceSettings) -> str:
    """Transcribe mono s16le PCM entirely on-device."""
    import numpy as np

    if not pcm:
        raise WhisperSttError("empty audio")
    rate = int(sample_rate or 16000)
    if rate <= 0:
        raise WhisperSttError("invalid sample rate")
    _import_whisper()
    raw = np.frombuffer(pcm, dtype=np.int16)
    if raw.size == 0:
        raise WhisperSttError("empty audio")
    audio = raw.astype(np.float32) / 32768.0
    audio = _resample(audio, rate, 16000)
    model = _get_model(settings)
    segments, _info = model.transcribe(audio, beam_size=1, vad_filter=True)
    parts = [segment.text.strip() for segment in segments if segment.text.strip()]
    text = " ".join(parts).strip()
    if not text:
        raise WhisperSttError("no speech detected")
    return text


__all__ = ["WhisperSttError", "transcribe_pcm_bytes"]
