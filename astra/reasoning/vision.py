"""Local vision backend. Does not replace LLMBackend. No network."""

from __future__ import annotations

from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any

VISION_UNAVAILABLE = "Vision capability unavailable."


class VisionBackend(ABC):
    """Local image description. Never uploads. Never calls a cloud API."""

    @abstractmethod
    def is_available(self) -> bool:
        """True only when a configured local vision model can run."""

    @abstractmethod
    def describe(self, image: bytes, *, prompt: str = "") -> str:
        """Return a local description, or ``VISION_UNAVAILABLE``."""


class UnavailableVisionBackend(VisionBackend):
    """Default. Capture still works; visual reasoning does not."""

    def is_available(self) -> bool:
        return False

    def describe(self, image: bytes, *, prompt: str = "") -> str:
        del image, prompt
        return VISION_UNAVAILABLE


def vision_from_settings(settings: Any) -> VisionBackend:
    """Build a vision backend. Missing/unusable config → unavailable."""
    reasoning = getattr(settings, "reasoning", None) if settings is not None else None
    path = getattr(reasoning, "vision_model_path", None) if reasoning is not None else None
    if path is None or not Path(path).is_file():
        return UnavailableVisionBackend()
    return UnavailableVisionBackend()


__all__ = [
    "VISION_UNAVAILABLE",
    "UnavailableVisionBackend",
    "VisionBackend",
    "vision_from_settings",
]
