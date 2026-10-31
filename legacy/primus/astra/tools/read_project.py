"""Controlled project UTF-8 read (B-018, ADR-0017).

Read-only. ``project_root()`` only, with explicit exclusions. Architectural
gate still required; this module does not consult Security itself.
"""

from __future__ import annotations

import os
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping, Sequence

from astra.tools.contracts import Tool, ToolResult, ToolSpec
from astra.tools.read_text import MAX_BYTES

TOOL_NAME = "read_project"
_BINARY_PROBE = 8192
_ALLOWED_SUFFIXES = frozenset({".py", ".md", ".toml", ".txt", ".json", ".csv"})
_HIDDEN_OK = frozenset({".gitignore", ".gitattributes"})
_DENIED_NAMES = frozenset(
    {"credentials.json", "secrets.json", "id_rsa", "id_ed25519"}
)
_DENIED_SEGMENTS = frozenset({".git", ".ssh", "__pycache__", ".venv", "venv"})
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


def _unique_canonical(paths: Sequence[Path | str]) -> tuple[Path, ...]:
    roots: list[Path] = []
    seen: set[str] = set()
    for raw in paths:
        canon = _canonical(raw)
        if canon is None:
            continue
        key = os.path.normcase(str(canon))
        if key in seen:
            continue
        seen.add(key)
        roots.append(canon)
    return tuple(roots)


def _denied_reason(canon: Path, exclusions: Sequence[Path]) -> str | None:
    denied_norm = {os.path.normcase(seg) for seg in _DENIED_SEGMENTS}
    for part in canon.parts:
        if os.path.normcase(part) in denied_norm:
            return f"path segment {part!r} is excluded from project reads"
    for excluded in exclusions:
        if _is_under(canon, excluded):
            return f"path is under excluded prefix {excluded}"
    name = canon.name
    lower = name.lower()
    if lower.startswith(".env"):
        return "dotenv files are excluded from project reads"
    if lower in _DENIED_NAMES:
        return f"basename {name!r} is excluded from project reads"
    if name.startswith(".") and name not in _HIDDEN_OK:
        return "hidden files are excluded from project reads"
    if name not in _HIDDEN_OK and canon.suffix.lower() not in _ALLOWED_SUFFIXES:
        suffix = canon.suffix.lower() or "(none)"
        return f"suffix {suffix!r} is not an allowed project-read type"
    return None


def project_roots_from_settings(settings: Any) -> tuple[Path, ...]:
    """Authorized project read root. No security import."""
    from astra.configuration.paths import project_root

    canon = _canonical(project_root())
    if canon is None:
        return ()
    return (canon,)


def project_exclusions_from_settings(settings: Any) -> tuple[Path, ...]:
    """Same exclusion prefixes as PolicySecurityAgent.from_settings."""
    from astra.configuration.paths import project_read_exclusion_paths

    return _unique_canonical(project_read_exclusion_paths(settings))


class ReadProjectTool(Tool):
    """Read a bounded UTF-8 file under the injected project root."""

    def __init__(
        self,
        read_roots: Sequence[Path | str] = (),
        exclusions: Sequence[Path | str] = (),
    ) -> None:
        self._roots = _unique_canonical(read_roots)
        self._exclusions = _unique_canonical(exclusions)

    @classmethod
    def from_settings(cls, settings: Any) -> "ReadProjectTool":
        return cls(
            read_roots=project_roots_from_settings(settings),
            exclusions=project_exclusions_from_settings(settings),
        )

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name=TOOL_NAME,
            description=(
                "Read UTF-8 project source under the repository root. "
                "Read-only. Bounded to 64 KiB. Excludes data, logs, git, secrets."
            ),
            parameters=MappingProxyType(
                {"path": "Path of a project text file to read"}
            ),
            requires_network=False,
            makes_permanent_change=False,
            reads_files=True,
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
                error="no project read roots configured; fail closed",
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
                error="path is outside the project read root; fail closed",
            )
        denied = _denied_reason(canon, self._exclusions)
        if denied is not None:
            return ToolResult(success=False, error=f"{denied}; fail closed")
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
    "ReadProjectTool",
    "TOOL_NAME",
    "project_roots_from_settings",
    "project_exclusions_from_settings",
]
