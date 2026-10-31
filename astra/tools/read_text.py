"""Controlled local UTF-8 read (B-017, ADR-0016).

Read-only. Astra data/log roots only. Architectural gate still required;
this module does not consult Security itself.
"""

from __future__ import annotations

import os
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping, Sequence

from astra.tools.allowlist import PRODUCTION_EXECUTION_ALLOWLIST
from astra.tools.contracts import Tool, ToolResult, ToolSpec

TOOL_NAME = "read_text"
MAX_BYTES = 65536
_BINARY_PROBE = 8192
_ALLOWED_SUFFIXES = frozenset({".log", ".txt", ".json", ".md", ".toml", ".csv"})
_ALLOWED_KEYS = frozenset({"path"})


def _canonical(path: Path | str) -> Path | None:
    """Absolute normalised path. Does not create files or directories."""
    try:
        raw = Path(path).expanduser()
        resolved = raw.resolve(strict=False)
        return Path(os.path.normcase(os.path.normpath(str(resolved))))
    except Exception:
        return None


def _is_under(path: Path, root: Path) -> bool:
    p = os.path.normcase(str(path))
    r = os.path.normcase(str(root))
    if p == r:
        return True
    prefix = r if r.endswith(os.sep) else r + os.sep
    return p.startswith(prefix)


def read_roots_from_settings(settings: Any) -> tuple[Path, ...]:
    """Same roots as PolicySecurityAgent.from_settings. No security import."""
    roots: list[Path] = []
    seen: set[str] = set()
    for attr, field in (
        ("memory", "database_path"),
        ("knowledge", "database_path"),
        ("planning", "database_path"),
        ("memory", "vector_index_path"),
    ):
        section = getattr(settings, attr, None)
        path = getattr(section, field, None) if section is not None else None
        if path is not None:
            canon = _canonical(Path(path).parent)
            if canon is None:
                continue
            key = os.path.normcase(str(canon))
            if key in seen:
                continue
            seen.add(key)
            roots.append(canon)
    logging = getattr(settings, "logging", None)
    directory = getattr(logging, "directory", None) if logging is not None else None
    if directory is not None:
        canon = _canonical(Path(directory))
        if canon is not None:
            key = os.path.normcase(str(canon))
            if key not in seen:
                roots.append(canon)
    return tuple(roots)


class ReadTextTool(Tool):
    """Read a bounded UTF-8 file under injected Astra data/log roots."""

    def __init__(self, read_roots: Sequence[Path | str] = ()) -> None:
        roots: list[Path] = []
        seen: set[str] = set()
        for raw in read_roots:
            canon = _canonical(raw)
            if canon is None:
                continue
            key = os.path.normcase(str(canon))
            if key in seen:
                continue
            seen.add(key)
            roots.append(canon)
        self._roots: tuple[Path, ...] = tuple(roots)

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name=TOOL_NAME,
            description=(
                "Read UTF-8 text under Astra data/log directories. "
                "Read-only. Bounded to 64 KiB."
            ),
            parameters=MappingProxyType(
                {"path": "Path of a text file to read"}
            ),
            requires_network=False,
            makes_permanent_change=False,
        )

    def invoke(self, **kwargs: Any) -> ToolResult:
        extra = sorted(k for k in kwargs if k not in _ALLOWED_KEYS)
        if extra:
            return ToolResult(
                success=False,
                error=f"unknown arguments: {extra}; fail closed",
            )
        raw_path = kwargs.get("path")
        if raw_path is None or str(raw_path).strip() == "":
            return ToolResult(success=False, error="path is required; fail closed")
        if not self._roots:
            return ToolResult(
                success=False,
                error="no read roots configured; fail closed",
            )
        canon = _canonical(raw_path)
        if canon is None:
            return ToolResult(
                success=False,
                error="path could not be canonicalised; fail closed",
            )
        if not any(_is_under(canon, root) for root in self._roots):
            return ToolResult(
                success=False,
                error="path is outside Astra data/log roots; fail closed",
            )
        suffix = canon.suffix.lower()
        if suffix not in _ALLOWED_SUFFIXES:
            return ToolResult(
                success=False,
                error=f"suffix {suffix!r} is not an allowed text type; fail closed",
            )
        try:
            if not canon.is_file():
                return ToolResult(
                    success=False,
                    error="path is not an existing file; fail closed",
                )
        except OSError as exc:
            return ToolResult(
                success=False,
                error=f"path could not be stat'd: {exc}",
            )
        try:
            with canon.open("rb") as handle:
                data = handle.read(MAX_BYTES + 1)
        except OSError as exc:
            return ToolResult(success=False, error=f"read failed: {exc}")
        if b"\0" in data[:_BINARY_PROBE]:
            return ToolResult(success=False, error="binary file; fail closed")
        truncated = len(data) > MAX_BYTES
        payload = data[:MAX_BYTES]
        try:
            text = payload.decode("utf-8")
        except UnicodeDecodeError:
            return ToolResult(success=False, error="not valid UTF-8; fail closed")
        details: Mapping[str, Any] = MappingProxyType(
            {
                "path": str(canon),
                "bytes": len(payload),
                "truncated": truncated,
            }
        )
        return ToolResult(success=True, output=text, details=details)


__all__ = [
    "ReadTextTool",
    "TOOL_NAME",
    "PRODUCTION_EXECUTION_ALLOWLIST",
    "MAX_BYTES",
    "read_roots_from_settings",
]
