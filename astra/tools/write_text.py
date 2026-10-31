"""Controlled UTF-8 write under Astra data/log roots.

Permanent. SecurityAgent still gates every call. Overwrites snapshot the
existing bytes beside the target before replace. Does not write project source.
"""

from __future__ import annotations

import os
from datetime import datetime, timezone
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping, Sequence

from astra.tools.contracts import Tool, ToolResult, ToolSpec
from astra.tools.read_text import MAX_BYTES, read_roots_from_settings

TOOL_NAME = "write_text"
_ALLOWED_SUFFIXES = frozenset({".md", ".txt", ".json", ".csv", ".log"})
_DENIED_NAMES = frozenset(
    {"credentials.json", "secrets.json", "id_rsa", "id_ed25519", "astra.db"}
)
_DENIED_SEGMENTS = frozenset({".git", ".ssh", "__pycache__", ".venv", "venv"})
_ALLOWED_KEYS = frozenset({"path", "content", "operation"})
_ALLOWED_OPS = frozenset({"create", "overwrite", "write"})


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


class WriteTextTool(Tool):
    """Write UTF-8 text under injected write roots (data/logs)."""

    def __init__(self, write_roots: Sequence[Path | str] = ()) -> None:
        self._roots = _unique_canonical(write_roots)

    @classmethod
    def from_settings(cls, settings: Any) -> "WriteTextTool":
        return cls(write_roots=read_roots_from_settings(settings))

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name=TOOL_NAME,
            description=(
                "Write UTF-8 text under Astra data/log directories. "
                "Permanent. Requires approval. Snapshots existing files first."
            ),
            parameters=MappingProxyType(
                {
                    "path": "Destination path under an allowed write root",
                    "content": "UTF-8 text to write",
                    "operation": "create, overwrite, or write",
                }
            ),
            requires_network=False,
            makes_permanent_change=True,
            reads_files=False,
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
        content = kwargs.get("content")
        if content is None:
            return ToolResult(success=False, error="content is required; fail closed")
        text = str(content)
        operation = str(kwargs.get("operation") or "write").strip().lower()
        if operation not in _ALLOWED_OPS:
            return ToolResult(
                success=False,
                error="operation must be create, overwrite, or write; fail closed",
            )
        if not self._roots:
            return ToolResult(
                success=False,
                error="no write roots configured; fail closed",
            )
        encoded = text.encode("utf-8")
        if len(encoded) > MAX_BYTES:
            return ToolResult(
                success=False,
                error=f"content exceeds {MAX_BYTES} bytes; fail closed",
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
                error="path is outside allowed write roots; fail closed",
            )
        denied = self._denied(canon)
        if denied is not None:
            return ToolResult(success=False, error=f"{denied}; fail closed")
        try:
            existed = canon.is_file()
        except OSError as exc:
            return ToolResult(success=False, error=f"path could not be stat'd: {exc}")
        if operation == "create" and existed:
            return ToolResult(
                success=False,
                error="create refused; file already exists; fail closed",
            )
        snapshot = ""
        if existed:
            snapshot, snap_err = self._snapshot(canon)
            if snap_err:
                return ToolResult(success=False, error=snap_err)
        try:
            canon.parent.mkdir(parents=True, exist_ok=True)
            canon.write_bytes(encoded)
        except OSError as exc:
            return ToolResult(success=False, error=f"write failed: {exc}")
        op = "overwrite" if existed else "create"
        details: Mapping[str, Any] = MappingProxyType(
            {
                "path": str(canon),
                "operation": op,
                "bytes": len(encoded),
                "snapshot": snapshot,
            }
        )
        return ToolResult(
            success=True,
            output=f"{op} {canon} ({len(encoded)} bytes)",
            details=details,
        )

    def _denied(self, canon: Path) -> str | None:
        denied_norm = {os.path.normcase(seg) for seg in _DENIED_SEGMENTS}
        for part in canon.parts:
            if os.path.normcase(part) in denied_norm:
                return f"path segment {part!r} is not writable"
        name = canon.name
        lower = name.lower()
        if lower.startswith(".env"):
            return "dotenv files are not writable"
        if lower in _DENIED_NAMES:
            return f"basename {name!r} is not writable"
        if name.startswith("."):
            return "hidden files are not writable"
        if canon.suffix.lower() not in _ALLOWED_SUFFIXES:
            return f"suffix {canon.suffix.lower() or '(none)'} is not writable"
        return None

    def _snapshot(self, canon: Path) -> tuple[str, str]:
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        snap = canon.with_name(f"{canon.name}.preimage-{stamp}")
        snap_canon = _canonical(snap)
        if snap_canon is None or not any(
            _is_under(snap_canon, root) for root in self._roots
        ):
            return "", "snapshot path escaped write roots; fail closed"
        try:
            snap_canon.write_bytes(canon.read_bytes())
        except OSError as exc:
            return "", f"snapshot failed: {exc}"
        return str(snap_canon), ""


__all__ = ["WriteTextTool", "TOOL_NAME"]
