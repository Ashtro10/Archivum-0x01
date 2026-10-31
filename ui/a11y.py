"""Accessibility preferences. Visuals must not be required to use Primus."""

from __future__ import annotations

import os


def prefers_reduced_motion() -> bool:
    flag = os.environ.get("PRIMUS_REDUCED_MOTION", "").strip().lower()
    return flag in {"1", "true", "yes", "on"}


def text_scale() -> float:
    raw = os.environ.get("PRIMUS_TEXT_SCALE", "1").strip()
    try:
        value = float(raw)
    except ValueError:
        return 1.0
    return min(1.6, max(0.9, value))


__all__ = ["prefers_reduced_motion", "text_scale"]
