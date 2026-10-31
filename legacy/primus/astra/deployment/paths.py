"""Project-path policy for deploy and rollback (ADR-0026 / ADR-0027).

Not a writer. Canonicalise, reject traversal, deny protected segments.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from astra.testing.workspace import (
    MAX_FILE_BYTES,
    SECRET_NAMES,
    SECRET_SEGMENTS,
    is_non_relative_path,
    secret_shaped_reason,
)

DENIED_SEGMENTS = SECRET_SEGMENTS
DENIED_NAMES = SECRET_NAMES


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


def denied_reason(rel: Path) -> str | None:
    return secret_shaped_reason(rel)


def resolve_dest(raw: Path | str, project: Path) -> tuple[Path | None, Path | None, str]:
    """Return ``(dest, rel, error)``. ``error`` empty on success."""
    path = Path(raw)
    if str(path).strip() == "" or path.name in ("", ".", ".."):
        return None, None, "proposal change missing path"
    if ".." in path.parts:
        return None, None, "path traversal rejected"
    if is_non_relative_path(path):
        return None, None, "absolute, UNC, or drive path rejected"
    candidate = canonical(project / path)
    if candidate is None or not is_under(candidate, project):
        return None, None, f"path {path} is outside project_root"
    try:
        rel = candidate.relative_to(project)
    except ValueError:
        return None, None, f"path {path} is outside project_root"
    if not rel.parts or rel.parts[0] == "..":
        return None, None, f"path {path} is outside project_root"
    denied = denied_reason(rel)
    if denied:
        return None, None, denied
    if is_link(candidate):
        return None, None, f"{rel.as_posix()} is a symlink or junction; refuse"
    parent = candidate.parent
    while is_under(parent, project) and parent != project:
        if is_link(parent):
            return None, None, f"{rel.as_posix()} parent is a symlink or junction; refuse"
        if parent.parent == parent:
            break
        parent = parent.parent
    return candidate, rel, ""


def plan_text_destinations(
    changes: tuple[Any, ...], project: Path
) -> tuple[tuple[tuple[Path, str], ...], str]:
    planned: list[tuple[Path, str]] = []
    seen: set[str] = set()
    for change in changes:
        dest, rel, err = resolve_dest(getattr(change, "path", "") or "", project)
        if err or dest is None or rel is None:
            return (), err or "path rejected"
        key = os.path.normcase(str(rel))
        if key in seen:
            return (), f"duplicate deploy path {rel.as_posix()}"
        seen.add(key)
        content = getattr(change, "new_content", "") or ""
        if not isinstance(content, str):
            return (), f"{rel.as_posix()} content is not text"
        try:
            encoded = content.encode("utf-8")
        except Exception:
            return (), f"{rel.as_posix()} content not encodable"
        if len(encoded) > MAX_FILE_BYTES:
            return (), f"{rel.as_posix()} exceeds size cap"
        planned.append((dest, content))
    if not planned:
        return (), "no deployable files"
    return tuple(planned), ""


__all__ = [
    "DENIED_NAMES",
    "DENIED_SEGMENTS",
    "canonical",
    "denied_reason",
    "is_link",
    "is_non_relative_path",
    "is_under",
    "plan_text_destinations",
    "resolve_dest",
]
