"""Gated application launch. Controller injects ProcessRunner.launch."""

from __future__ import annotations

from pathlib import Path
from types import MappingProxyType
from typing import Any, Callable, Mapping, Sequence

from astra.tools.contracts import Tool, ToolResult, ToolSpec

TOOL_NAME = "launch_application"
_ALLOWED_KEYS = frozenset({"application", "executable", "timeout"})
_DEFAULT_TIMEOUT = 15.0
_MAX_TIMEOUT = 60.0
_KNOWN = {
    "blender": ("blender.exe", "blender"),
}
LaunchExecute = Callable[[Path, float], Mapping[str, Any]]


class LaunchApplicationTool(Tool):
    """Launch an allowlisted user application. Not a shell. Not GUI automation."""

    def __init__(
        self,
        allowed_executables: Sequence[Path | str] = (),
        launch: LaunchExecute | None = None,
    ) -> None:
        self._allowed = tuple(Path(item) for item in allowed_executables if str(item).strip())
        self._launch = launch

    def bind_launch(self, launch: LaunchExecute | None) -> None:
        self._launch = launch

    @classmethod
    def from_settings(
        cls,
        settings: Any,
        launch: LaunchExecute | None = None,
    ) -> "LaunchApplicationTool":
        sec = getattr(settings, "security", None)
        allowed = (
            getattr(sec, "allowed_application_executables", ()) if sec is not None else ()
        )
        return cls(allowed_executables=tuple(allowed or ()), launch=launch)

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name=TOOL_NAME,
            description=(
                "Launch an allowlisted local application such as Blender. "
                "Does not click UI, inject keys, or run a shell."
            ),
            parameters=MappingProxyType(
                {
                    "application": "Application name, for example blender",
                    "executable": "Absolute path of an allowlisted executable",
                    "timeout": "Seconds allowed for startup verification",
                }
            ),
            requires_network=False,
            makes_permanent_change=False,
            requires_process=True,
        )

    def invoke(self, **kwargs: Any) -> ToolResult:
        extra = sorted(k for k in kwargs if k not in _ALLOWED_KEYS)
        if extra:
            return ToolResult(
                success=False,
                error=f"unknown arguments: {extra}; fail closed",
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
        if self._launch is None:
            return ToolResult(
                success=False,
                error="application launcher not bound; fail closed",
            )
        requested = str(
            kwargs.get("executable") or kwargs.get("application") or ""
        ).strip()
        if not requested:
            return ToolResult(
                success=False,
                error="application is required; fail closed",
            )
        resolved = _resolve_executable(requested, self._allowed)
        if resolved is None:
            return ToolResult(
                success=False,
                error="executable is not on the application allowlist; fail closed",
            )
        if not resolved.is_file():
            return ToolResult(
                success=False,
                error="invalid executable; fail closed",
            )
        try:
            outcome = self._launch(resolved, timeout)
        except Exception as exc:  # noqa: BLE001
            return ToolResult(
                success=False,
                error=f"launcher raised {type(exc).__name__}: {exc}",
            )
        pid = outcome.get("pid")
        running = bool(outcome.get("running", False))
        timed_out = bool(outcome.get("timed_out", False))
        spawned = bool(outcome.get("spawned", False) or outcome.get("success", False))
        ok = bool(outcome.get("success", False)) and running and not timed_out
        error = ""
        if timed_out:
            error = str(outcome.get("error", "") or "launch timed out")
        elif not ok:
            error = str(
                outcome.get("error", "")
                or "application did not stay running after launch"
            )
        name = resolved.stem
        output = (
            f"application={name} success={str(ok).lower()} "
            f"pid={pid if pid is not None else 'none'} running={str(running).lower()}"
        )
        details = MappingProxyType(
            {
                "application": name,
                "executable": str(resolved),
                "success": ok,
                "pid": pid,
                "running": running,
                "spawned": spawned,
                "timed_out": timed_out,
                "error": error,
            }
        )
        return ToolResult(
            success=ok,
            output=output,
            error=error,
            details=details,
        )


def _resolve_executable(requested: str, allowed: Sequence[Path]) -> Path | None:
    if not allowed:
        return None
    raw = Path(requested)
    looks_like_path = any(sep in requested for sep in ("/", "\\")) or bool(raw.suffix)
    if looks_like_path:
        try:
            canon = raw.expanduser().resolve(strict=False)
        except Exception:
            return None
        for item in allowed:
            try:
                if item.expanduser().resolve(strict=False) == canon:
                    return canon
            except Exception:
                continue
        return None
    aliases = _KNOWN.get(requested.lower(), (requested.lower(),))
    needles = {requested.lower(), *aliases}
    for item in allowed:
        name = item.name.lower()
        stem = item.stem.lower()
        if name in needles or stem in needles or f"{stem}.exe" in needles:
            return item
    return None


__all__ = ["LaunchApplicationTool", "TOOL_NAME"]
