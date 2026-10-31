"""Read-only filename and text search (agent filesystem search).

Does not delete, modify, or escape injected roots. Architectural gate still
required; this module does not consult Security itself.
"""

from __future__ import annotations

import os
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping, Sequence

from astra.tools.contracts import Tool, ToolResult, ToolSpec
from astra.tools.read_project import (
    project_exclusions_from_settings,
    project_roots_from_settings,
)
from astra.tools.read_text import MAX_BYTES, read_roots_from_settings

TOOL_NAME = "search_files"
_BINARY_PROBE = 8192
_ALLOWED_SUFFIXES = frozenset(
    {".py", ".md", ".toml", ".txt", ".json", ".csv", ".log"}
)
_HIDDEN_OK = frozenset({".gitignore", ".gitattributes"})
_DENIED_NAMES = frozenset(
    {"credentials.json", "secrets.json", "id_rsa", "id_ed25519"}
)
_DENIED_SEGMENTS = frozenset({".git", ".ssh", "__pycache__", ".venv", "venv"})
_ALLOWED_KEYS = frozenset({"path", "query", "mode"})
_MAX_WALK = 400
_MAX_HITS = 20
_SNIPPET = 160
_MAX_SNIPPETS = 3


def _canonical(path: Path | str) -> Path | None:
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


def _denied_file(canon: Path, exclusions: Sequence[Path]) -> str | None:
    denied_norm = {os.path.normcase(seg) for seg in _DENIED_SEGMENTS}
    for part in canon.parts:
        if os.path.normcase(part) in denied_norm:
            return f"path segment {part!r} is excluded"
    for excluded in exclusions:
        if _is_under(canon, excluded):
            return f"path is under excluded prefix {excluded}"
    name = canon.name
    lower = name.lower()
    if lower.startswith(".env"):
        return "dotenv files are excluded"
    if lower in _DENIED_NAMES:
        return f"basename {name!r} is excluded"
    if name.startswith(".") and name not in _HIDDEN_OK:
        return "hidden files are excluded"
    if name not in _HIDDEN_OK and canon.suffix.lower() not in _ALLOWED_SUFFIXES:
        return "suffix is not an allowed search type"
    return None


def _exclusions_for_root(
    root: Path, exclusions: Sequence[Path]
) -> tuple[Path, ...]:
    """Keep prefix exclusions, except the search root itself (data/logs)."""
    kept: list[Path] = []
    for excluded in exclusions:
        if root == excluded or _is_under(root, excluded):
            continue
        kept.append(excluded)
    return tuple(kept)


def _skip_dir(path: Path, exclusions: Sequence[Path]) -> bool:
    denied_norm = {os.path.normcase(seg) for seg in _DENIED_SEGMENTS}
    for part in path.parts:
        if os.path.normcase(part) in denied_norm:
            return True
    for excluded in exclusions:
        if _is_under(path, excluded):
            return True
    name = path.name
    if name.startswith(".") and name not in {".", ".."}:
        return True
    return False


class SearchFilesTool(Tool):
    """Search filenames and file text under injected project/data roots."""

    def __init__(
        self,
        search_roots: Sequence[Path | str] = (),
        exclusions: Sequence[Path | str] = (),
    ) -> None:
        self._roots = _unique_canonical(search_roots)
        self._exclusions = _unique_canonical(exclusions)

    @classmethod
    def from_settings(cls, settings: Any) -> "SearchFilesTool":
        roots = list(project_roots_from_settings(settings))
        roots.extend(read_roots_from_settings(settings))
        return cls(
            search_roots=roots,
            exclusions=project_exclusions_from_settings(settings),
        )

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name=TOOL_NAME,
            description=(
                "Read-only search of filenames and text under project and data "
                "roots. Never deletes or modifies files."
            ),
            parameters=MappingProxyType(
                {
                    "path": "Authorized file under a search root (security grant)",
                    "query": "Filename or text substring to find",
                    "mode": "filename, text, or both (default both)",
                }
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
        query = str(kwargs.get("query") or "").strip()
        if not query:
            return ToolResult(success=False, error="query is required; fail closed")
        mode = str(kwargs.get("mode") or "both").strip().lower()
        if mode not in {"filename", "text", "both"}:
            return ToolResult(success=False, error="mode must be filename, text, or both")
        if not self._roots:
            return ToolResult(
                success=False,
                error="no search roots configured; fail closed",
            )
        grant = _canonical(raw_path)
        if grant is None:
            return ToolResult(
                success=False,
                error="path could not be canonicalised; fail closed",
            )
        if not any(_is_under(grant, root) for root in self._roots):
            return ToolResult(
                success=False,
                error="path is outside authorized search roots; fail closed",
            )
        denied = _denied_file(grant, _exclusions_for_root(grant.parent, self._exclusions))
        if denied is not None:
            return ToolResult(success=False, error=f"{denied}; fail closed")
        try:
            if not grant.is_file():
                return ToolResult(
                    success=False,
                    error="path is not an existing file; fail closed",
                )
        except OSError as exc:
            return ToolResult(success=False, error=f"path could not be stat'd: {exc}")

        needle = query.lower()
        hits: list[dict[str, Any]] = []
        walked = 0
        for root in self._roots:
            if not root.exists() or not root.is_dir():
                continue
            scoped = _exclusions_for_root(root, self._exclusions)
            for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
                current = Path(dirpath)
                if _skip_dir(current, scoped):
                    dirnames[:] = []
                    continue
                dirnames[:] = [
                    name
                    for name in dirnames
                    if not _skip_dir(current / name, scoped)
                ]
                for name in filenames:
                    if len(hits) >= _MAX_HITS:
                        break
                    walked += 1
                    if walked > _MAX_WALK:
                        break
                    candidate = current / name
                    canon = _canonical(candidate)
                    if canon is None:
                        continue
                    if not any(_is_under(canon, allowed) for allowed in self._roots):
                        continue
                    if _denied_file(canon, scoped) is not None:
                        continue
                    record = self._match(canon, needle, mode)
                    if record is not None:
                        hits.append(record)
                if len(hits) >= _MAX_HITS or walked > _MAX_WALK:
                    break
            if len(hits) >= _MAX_HITS or walked > _MAX_WALK:
                break

        lines = []
        for item in hits:
            lines.append(str(item["path"]))
            for snippet in item.get("snippets") or ():
                lines.append(f"  {snippet}")
        output = "\n".join(lines) if lines else "No matches."
        details: Mapping[str, Any] = MappingProxyType(
            {
                "query": query,
                "mode": mode,
                "matches": len(hits),
                "paths": tuple(item["path"] for item in hits),
                "truncated": walked > _MAX_WALK or len(hits) >= _MAX_HITS,
            }
        )
        return ToolResult(success=True, output=output, details=details)

    def _match(
        self, canon: Path, needle: str, mode: str
    ) -> dict[str, Any] | None:
        name_hit = needle in canon.name.lower() if mode in {"filename", "both"} else False
        snippets: list[str] = []
        if mode in {"text", "both"}:
            snippets = self._snippets(canon, needle)
        if not name_hit and not snippets:
            return None
        return {
            "path": str(canon),
            "filename_match": name_hit,
            "snippets": tuple(snippets),
        }

    def _snippets(self, canon: Path, needle: str) -> list[str]:
        try:
            with canon.open("rb") as handle:
                data = handle.read(MAX_BYTES + 1)
        except OSError:
            return []
        if b"\0" in data[:_BINARY_PROBE]:
            return []
        try:
            text = data[:MAX_BYTES].decode("utf-8")
        except UnicodeDecodeError:
            return []
        found: list[str] = []
        for line in text.splitlines():
            if needle not in line.lower():
                continue
            clipped = line.strip()
            if len(clipped) > _SNIPPET:
                clipped = clipped[: _SNIPPET - 1] + "…"
            found.append(clipped)
            if len(found) >= _MAX_SNIPPETS:
                break
        return found


__all__ = ["SearchFilesTool", "TOOL_NAME"]
