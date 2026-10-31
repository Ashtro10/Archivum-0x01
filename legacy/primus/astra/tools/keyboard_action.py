"""Gated keyboard control. Allowlisted keys only. No raw injection API."""

from __future__ import annotations

from types import MappingProxyType
from typing import Any, Callable, Mapping, Sequence

from astra.tools.contracts import Tool, ToolResult, ToolSpec

TOOL_NAME = "keyboard_action"
_ALLOWED_KEYS = frozenset({"action", "text", "key", "keys", "observe"})
_ACTIONS = frozenset({"type_text", "press_key", "hotkey"})
_NAMED_KEYS = frozenset(
    {
        "enter",
        "tab",
        "escape",
        "space",
        "backspace",
        "delete",
        "up",
        "down",
        "left",
        "right",
        "home",
        "end",
        "pageup",
        "pagedown",
        *{f"f{i}" for i in range(1, 13)},
        *{chr(code) for code in range(ord("a"), ord("z") + 1)},
        *{str(i) for i in range(10)},
    }
)
_MODIFIERS = frozenset({"ctrl", "alt", "shift", "win"})
_BANNED_HOTKEYS = frozenset(
    {
        ("alt", "f4"),
        ("ctrl", "alt", "del"),
        ("ctrl", "shift", "esc"),
        ("win", "l"),
        ("win", "r"),
        ("alt", "tab"),
    }
)
_MAX_TEXT = 500
KeyboardExecute = Callable[[str, str, tuple[str, ...]], Mapping[str, Any]]


class KeyboardActionTool(Tool):
    """Type text, press one key, or send an allowlisted hotkey."""

    def __init__(self, act: KeyboardExecute | None = None) -> None:
        self._act = act

    def bind_act(self, act: KeyboardExecute | None) -> None:
        self._act = act

    @classmethod
    def from_settings(
        cls,
        settings: Any = None,
        act: KeyboardExecute | None = None,
    ) -> "KeyboardActionTool":
        del settings
        return cls(act=act)

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name=TOOL_NAME,
            description=(
                "Type text, press one named key, or send an allowlisted hotkey. "
                "Requires approval. No raw scan-code injection."
            ),
            parameters=MappingProxyType(
                {
                    "action": "type_text, press_key, or hotkey",
                    "text": "Characters to type for type_text",
                    "key": "Single named key for press_key",
                    "keys": "Hotkey parts, for example ctrl+n",
                    "observe": "Capture screen after the action (default true)",
                }
            ),
            requires_network=False,
            makes_permanent_change=False,
            controls_input=True,
        )

    def invoke(self, **kwargs: Any) -> ToolResult:
        extra = sorted(k for k in kwargs if k not in _ALLOWED_KEYS)
        if extra:
            return ToolResult(
                success=False,
                error=f"unknown arguments: {extra}; fail closed",
            )
        action = str(kwargs.get("action") or "").strip().lower()
        if action not in _ACTIONS:
            return ToolResult(
                success=False,
                error="keyboard action is invalid; fail closed",
            )
        text = str(kwargs.get("text") or "")
        key = str(kwargs.get("key") or "").strip().lower()
        combo = _parse_keys(kwargs.get("keys"))
        if action == "type_text":
            if not text or len(text) > _MAX_TEXT:
                return ToolResult(success=False, error="text is invalid; fail closed")
            if "\x00" in text:
                return ToolResult(success=False, error="text is invalid; fail closed")
            key = ""
            combo = ()
        elif action == "press_key":
            if key not in _NAMED_KEYS:
                return ToolResult(success=False, error="key is not allowlisted; fail closed")
            text = ""
            combo = (key,)
        else:
            if not combo or not _hotkey_allowed(combo):
                return ToolResult(success=False, error="hotkey is not allowlisted; fail closed")
            text = ""
            key = "+".join(combo)
        if self._act is None:
            return ToolResult(success=False, error="keyboard host unavailable; fail closed")
        try:
            outcome = dict(self._act(action, text, combo) or {})
        except Exception as exc:  # noqa: BLE001
            return ToolResult(
                success=False,
                error=f"keyboard action failed: {type(exc).__name__}: {exc}",
            )
        ok = bool(outcome.get("success", False))
        error = "" if ok else str(outcome.get("error") or "keyboard action failed")
        shown = text if action == "type_text" else key
        output = f"action={action} keys={shown} success={str(ok).lower()}"
        details = MappingProxyType(
            {
                "action": action,
                "text": text,
                "key": key,
                "keys": combo,
                "success": ok,
                "observe": _wants_observe(kwargs.get("observe")),
            }
        )
        return ToolResult(success=ok, output=output, error=error, details=details)


def _parse_keys(raw: Any) -> tuple[str, ...]:
    text = str(raw or "").strip().lower()
    if not text:
        return ()
    parts = [part.strip() for part in text.replace("+", ",").split(",") if part.strip()]
    return tuple(parts)


def _hotkey_allowed(keys: Sequence[str]) -> bool:
    if len(keys) < 2 or len(keys) > 3:
        return False
    normalized = tuple(keys)
    if normalized in _BANNED_HOTKEYS:
        return False
    if tuple(sorted(normalized)) in {tuple(sorted(item)) for item in _BANNED_HOTKEYS}:
        return False
    mods = [part for part in keys if part in _MODIFIERS]
    rest = [part for part in keys if part not in _MODIFIERS]
    if not mods or len(rest) != 1:
        return False
    return rest[0] in _NAMED_KEYS


def _wants_observe(value: Any) -> bool:
    text = str(value or "true").strip().lower()
    return text not in {"0", "false", "no", "off"}


__all__ = ["KeyboardActionTool", "TOOL_NAME"]
