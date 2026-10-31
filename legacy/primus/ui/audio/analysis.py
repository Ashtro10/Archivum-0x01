"""Frontend audio-analysis boundary.

Consumes real PCM. Does not invent sine waves or random amplitude.
Backend speech/TTS is not exposed yet; this adapter is the integration point.
"""

from __future__ import annotations

import math
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class AudioFeatures:
    """Normalized 0..1 signals derived from real samples."""

    amplitude: float = 0.0
    energy: float = 0.0
    bands: tuple[float, ...] = (0.0, 0.0, 0.0, 0.0)
    rhythm: float = 0.0
    intensity: float = 0.0
    silent: bool = True


SILENT = AudioFeatures()


class AudioAnalyzer:
    """Analyze mono float32 (or int16 bytes) frames. No background thread."""

    def __init__(self) -> None:
        self._last = SILENT
        self._closed = False

    def consume(self, samples: bytes | tuple[float, ...] | list[float], *, sample_rate: int = 16000) -> AudioFeatures:
        if self._closed:
            return SILENT
        values = _as_floats(samples)
        if not values:
            self._last = SILENT
            return SILENT
        peak = max(abs(v) for v in values)
        energy = min(1.0, math.sqrt(sum(v * v for v in values) / len(values)) * 2.5)
        amplitude = min(1.0, peak)
        n = len(values)
        quarters = max(1, n // 4)
        bands = tuple(
            min(1.0, math.sqrt(sum(v * v for v in values[i * quarters : (i + 1) * quarters]) / max(1, quarters)) * 2.5)
            for i in range(4)
        )
        crossings = sum(
            1 for i in range(1, n) if (values[i - 1] >= 0) != (values[i] >= 0)
        )
        rhythm = min(1.0, crossings / max(1.0, n / (sample_rate / 80.0)))
        silent = amplitude < 0.02 and energy < 0.02
        self._last = AudioFeatures(
            amplitude=amplitude,
            energy=energy,
            bands=bands,
            rhythm=rhythm,
            intensity=min(1.0, 0.6 * amplitude + 0.4 * energy),
            silent=silent,
        )
        return self._last

    @property
    def last(self) -> AudioFeatures:
        return self._last

    def close(self) -> None:
        self._closed = True
        self._last = SILENT


def _as_floats(samples: bytes | tuple[float, ...] | list[float]) -> tuple[float, ...]:
    if isinstance(samples, (tuple, list)):
        return tuple(float(v) for v in samples)
    if not samples:
        return ()
    if len(samples) % 2:
        samples = samples[:-1]
    out: list[float] = []
    for i in range(0, len(samples), 2):
        raw = int.from_bytes(samples[i : i + 2], "little", signed=True)
        out.append(raw / 32768.0)
    return tuple(out)


__all__ = ["AudioAnalyzer", "AudioFeatures", "SILENT"]
