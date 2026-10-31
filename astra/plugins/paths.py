"""Path checks for plugin roots. Not a writer. No Security grants."""

from __future__ import annotations

import os
from pathlib import Path


def canonical(path: Path | str) -> Path | None:
    try:
        raw = Path(path).expanduser()
        resolved = raw.resolve(strict=False)
        return Path(os.path.normcase(os.path.normpath(str(resolved))))
    except Exception:
        return None


def is_under(path: Path, root: Path) -> bool:
    p = os.path.normcase(str(path))
    r = os.path.normcase(str(root))
    if p == r:
        return True
    prefix = r if r.endswith(os.sep) else r + os.sep
    return p.startswith(prefix)


def is_non_relative_path(path: Path | str) -> bool:
    """True for absolute, UNC, extended, or drive-letter plugin paths."""
    text = str(path).strip()
    if not text:
        return False
    raw = Path(path)
    if raw.is_absolute():
        return True
    if text.startswith("\\\\") or text.startswith("//"):
        return True
    if text.startswith("\\\\?\\") or text.startswith("//?/"):
        return True
    if len(text) >= 2 and text[1] == ":" and text[0].isalpha():
        return True
    return False


def is_link(path: Path) -> bool:
    try:
        if path.is_symlink():
            return True
        is_junction = getattr(path, "is_junction", None)
        if callable(is_junction) and is_junction():
            return True
    except OSError:
        return True
    return False


__all__ = ["canonical", "is_link", "is_non_relative_path", "is_under"]
