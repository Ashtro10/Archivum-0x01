"""Isolated Python execution in a scratch workspace.

Does not import a process-spawn library. Controller injects the spawn callback.
Script is written under scratch, then executed by ProcessRunner outside this package.
"""

from __future__ import annotations

import os
from pathlib import Path
from types import MappingProxyType
from typing import Any, Callable, Mapping, Sequence

from astra.tools.contracts import Tool, ToolResult, ToolSpec
from astra.tools.read_text import MAX_BYTES

TOOL_NAME = "run_python"
_ALLOWED_KEYS = frozenset({"path", "code", "timeout"})
_DEFAULT_TIMEOUT = 15.0
_MAX_TIMEOUT = 60.0
PythonExecute = Callable[[Path, float], Mapping[str, Any]]


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


def scratch_roots_from_settings(settings: Any) -> tuple[Path, ...]:
    testing = getattr(settings, "testing", None)
    directory = getattr(testing, "scratch_directory", None) if testing is not None else None
    if directory is None:
        return ()
    canon = _canonical(directory)
    if canon is None:
        return ()
    return (canon,)


class RunPythonTool(Tool):
    """Write a temporary .py file under scratch and execute via injected runner."""

    def __init__(
        self,
        scratch_roots: Sequence[Path | str] = (),
        execute: PythonExecute | None = None,
    ) -> None:
        self._roots = _unique_canonical(scratch_roots)
        self._execute = execute

    def bind_execute(self, execute: PythonExecute | None) -> None:
        """Controller injects ProcessRunner-backed spawn after start."""
        self._execute = execute

    @classmethod
    def from_settings(
        cls,
        settings: Any,
        execute: PythonExecute | None = None,
    ) -> "RunPythonTool":
        return cls(
            scratch_roots=scratch_roots_from_settings(settings),
            execute=execute,
        )

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name=TOOL_NAME,
            description=(
                "Execute Python in an isolated scratch workspace. "
                "Captures stdout, stderr, and exit code. Not host-arbitrary."
            ),
            parameters=MappingProxyType(
                {
                    "path": "Scratch .py path to write before execution",
                    "code": "Python source to write and run",
                    "timeout": "Seconds before the process is killed",
                }
            ),
            requires_network=False,
            makes_permanent_change=False,
            scratch_writes=True,
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
        code = kwargs.get("code")
        if code is None or str(code).strip() == "":
            return ToolResult(success=False, error="code is required; fail closed")
        source = str(code)
        encoded = source.encode("utf-8")
        if len(encoded) > MAX_BYTES:
            return ToolResult(
                success=False,
                error=f"code exceeds {MAX_BYTES} bytes; fail closed",
            )
        try:
            timeout = float(kwargs.get("timeout") or _DEFAULT_TIMEOUT)
        except (TypeError, ValueError):
            return ToolResult(success=False, error="timeout is invalid; fail closed")
        if timeout <= 0 or timeout > _MAX_TIMEOUT:
            return ToolResult(
                success=False,
                error="timeout missing or above cap; fail closed",
            )
        if not self._roots:
            return ToolResult(
                success=False,
                error="no scratch roots configured; fail closed",
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
                error="path is outside scratch roots; fail closed",
            )
        if canon.suffix.lower() != ".py":
            return ToolResult(
                success=False,
                error="scratch script must be a .py file; fail closed",
            )
        if self._execute is None:
            return ToolResult(
                success=False,
                error="python runner not bound; fail closed",
            )
        try:
            canon.parent.mkdir(parents=True, exist_ok=True)
            canon.write_bytes(encoded)
        except OSError as exc:
            return ToolResult(success=False, error=f"scratch write failed: {exc}")
        try:
            outcome = self._execute(canon, timeout)
        except Exception as exc:  # noqa: BLE001
            return ToolResult(
                success=False,
                error=f"python runner raised {type(exc).__name__}: {exc}",
            )
        stdout = str(outcome.get("stdout", "") or "")
        stderr = str(outcome.get("stderr", "") or "")
        exit_code = outcome.get("exit_code")
        timed_out = bool(outcome.get("timed_out", False))
        ok = bool(outcome.get("success", False)) and not timed_out
        bits = []
        if stdout:
            bits.append(stdout.rstrip())
        if stderr:
            bits.append(stderr.rstrip())
        if timed_out:
            bits.append("timed out")
        details: Mapping[str, Any] = MappingProxyType(
            {
                "path": str(canon),
                "exit_code": exit_code,
                "timed_out": timed_out,
                "stdout": stdout,
                "stderr": stderr,
            }
        )
        error = "" if ok else str(outcome.get("error", "") or stderr or "python execution failed")
        return ToolResult(
            success=ok,
            output="\n".join(bits),
            error=error,
            details=details,
        )


__all__ = ["RunPythonTool", "TOOL_NAME", "scratch_roots_from_settings"]
