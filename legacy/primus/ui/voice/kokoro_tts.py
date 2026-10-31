"""Offline Kokoro TTS — expressive local speech (hexgrad/Kokoro-82M)."""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from astra.configuration.settings import VoiceSettings

DEFAULT_SAMPLE_RATE = 24000
DEFAULT_VOICE = "am_adam"
DEFAULT_LANG = "a"
DEFAULT_REPO = "hexgrad/Kokoro-82M"
_PIPELINE = None
_PIPELINE_KEY: tuple[str, str, str] | None = None


class KokoroTtsError(Exception):
    """Kokoro TTS request failed."""


@dataclass(frozen=True)
class KokoroTtsResult:
    pcm: bytes
    sample_rate: int = DEFAULT_SAMPLE_RATE
    channels: int = 1


def resolve_device(settings: VoiceSettings) -> str:
    requested = str(settings.kokoro_device or "auto").strip().lower() or "auto"
    try:
        import torch
    except ImportError as exc:
        raise KokoroTtsError("Kokoro deps missing — run: pip install kokoro torch") from exc
    if requested == "cpu":
        return "cpu"
    if requested in ("auto", "cuda", "gpu"):
        return "cuda" if torch.cuda.is_available() else "cpu"
    return requested


def _load(settings: VoiceSettings):
    global _PIPELINE, _PIPELINE_KEY
    lang = str(settings.kokoro_lang or DEFAULT_LANG).strip().lower() or DEFAULT_LANG
    device = resolve_device(settings)
    repo = str(settings.kokoro_repo or DEFAULT_REPO).strip() or DEFAULT_REPO
    key = (lang, device, repo)
    if _PIPELINE is not None and _PIPELINE_KEY == key:
        return _PIPELINE
    try:
        from kokoro import KPipeline
    except ImportError as exc:
        raise KokoroTtsError("Kokoro deps missing — run: pip install kokoro") from exc
    _PIPELINE = KPipeline(lang_code=lang, repo_id=repo, device=device)
    _PIPELINE_KEY = key
    return _PIPELINE


def warmup(settings: VoiceSettings) -> None:
    """Preload Kokoro pipeline so first speak is not blocked on model load."""
    _load(settings)


def synthesize_waveform(text: str, *, settings: VoiceSettings):
    """Generate mono float32 audio and sample rate."""
    import numpy as np

    line = str(text or "").strip()
    if not line:
        raise KokoroTtsError("empty text")
    pipe = _load(settings)
    voice = str(settings.kokoro_voice or DEFAULT_VOICE).strip() or DEFAULT_VOICE
    speed = float(settings.kokoro_speed)
    segments: list[np.ndarray] = []
    for result in pipe(line, voice=voice, speed=speed):
        audio = result.audio
        if audio is None:
            continue
        if hasattr(audio, "detach"):
            arr = audio.detach().cpu().numpy()
        else:
            arr = np.asarray(audio, dtype=np.float32)
        segments.append(arr.astype(np.float32))
    if not segments:
        raise KokoroTtsError("empty audio response")
    return np.concatenate(segments), DEFAULT_SAMPLE_RATE


def synthesize(text: str, *, settings: VoiceSettings) -> KokoroTtsResult:
    """Generate mono PCM (s16le) with Kokoro."""
    import numpy as np

    waveform, sample_rate = synthesize_waveform(text, settings=settings)
    clipped = np.clip(waveform, -1.0, 1.0)
    pcm = (clipped * 32767.0).astype(np.int16).tobytes()
    return KokoroTtsResult(pcm=pcm, sample_rate=int(sample_rate))


__all__ = [
    "DEFAULT_SAMPLE_RATE",
    "DEFAULT_VOICE",
    "KokoroTtsError",
    "KokoroTtsResult",
    "resolve_device",
    "synthesize",
    "synthesize_waveform",
    "warmup",
]
