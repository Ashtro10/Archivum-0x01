"""Voice signal bus — fullstack-agent compatible file bridge for the Core.

backtalk writes ``.voice_state`` and ``.voice_waveform``; Primus uses the same
layout under the data directory so external visualizers can still read signals.
"""

from __future__ import annotations

import json
from pathlib import Path

from astra.configuration.paths import data_root, ensure_directory
from ui.audio.analysis import AudioAnalyzer, AudioFeatures, SILENT

_STATE_FILE = ".voice_state"
_WAVE_FILE = ".voice_waveform"


def signals_dir() -> Path:
    path = ensure_directory(data_root() / "signals")
    return path


def write_voice_state(state: str) -> None:
    name = str(state or "idle").strip().lower() or "idle"
    (signals_dir() / _STATE_FILE).write_text(name, encoding="utf-8")


def read_voice_state() -> str:
    path = signals_dir() / _STATE_FILE
    if not path.is_file():
        return "idle"
    try:
        return path.read_text(encoding="utf-8").strip().lower() or "idle"
    except OSError:
        return "idle"


class VoiceBus:
    """Runtime voice bus with PCM analysis."""

    def __init__(self) -> None:
        self._analyzer = AudioAnalyzer()
        self._state = "idle"

    def set_state(self, state: str) -> None:
        self._state = str(state or "idle").strip().lower() or "idle"
        write_voice_state(self._state)

    @property
    def state(self) -> str:
        return self._state

    def ingest_pcm(self, samples: list[float] | tuple[float, ...]) -> AudioFeatures:
        features = self._analyzer.consume(tuple(float(v) for v in samples))
        payload = {
            "amplitude": features.amplitude,
            "energy": features.energy,
            "intensity": features.intensity,
            "rhythm": features.rhythm,
            "silent": features.silent,
            "bands": list(features.bands),
        }
        try:
            (signals_dir() / _WAVE_FILE).write_text(json.dumps(payload), encoding="utf-8")
        except OSError:
            pass
        return features

    def snapshot(self) -> dict[str, object]:
        features = self._analyzer.last
        return {
            "state": self._state,
            "amplitude": features.amplitude,
            "energy": features.energy,
            "intensity": features.intensity,
            "rhythm": features.rhythm,
            "silent": features.silent,
            "bands": list(features.bands),
        }

    def close(self) -> None:
        self._analyzer.close()
        self.set_state("idle")
        try:
            (signals_dir() / _WAVE_FILE).write_text(json.dumps({"silent": True}), encoding="utf-8")
        except OSError:
            pass


__all__ = [
    "VoiceBus",
    "signals_dir",
    "write_voice_state",
    "read_voice_state",
    "SILENT",
]
