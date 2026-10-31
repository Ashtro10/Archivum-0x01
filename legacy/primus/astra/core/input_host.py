"""Host-side mouse/keyboard. Injected into tools. Fail closed. No network."""

from __future__ import annotations

from typing import Any


def screen_size() -> tuple[int, int]:
    """Return the primary display size. (0, 0) if unavailable."""
    try:
        import ctypes

        width = int(ctypes.windll.user32.GetSystemMetrics(0))
        height = int(ctypes.windll.user32.GetSystemMetrics(1))
        if width > 0 and height > 0:
            return width, height
    except Exception:
        return (0, 0)
    return (0, 0)


def perform_mouse(
    action: str,
    x: int,
    y: int,
    button: str,
    click_count: int,
    scroll_amount: int,
) -> dict[str, Any]:
    """Move/click/scroll locally. Never called from unit tests."""
    width, height = screen_size()
    if width <= 0 or height <= 0:
        return {"success": False, "error": "screen dimensions unavailable"}
    if not (0 <= int(x) < width and 0 <= int(y) < height):
        return {"success": False, "error": "coordinates outside screen"}
    try:
        import ctypes

        user32 = ctypes.windll.user32
        if not user32.SetCursorPos(int(x), int(y)):
            return {"success": False, "error": "pointer move failed"}
        if action == "move":
            return {"success": True}
        if action == "scroll":
            user32.mouse_event(0x0800, 0, 0, int(scroll_amount) * 120, 0)
            return {"success": True}
        down, up = _button_flags(button)
        if down == 0:
            return {"success": False, "error": "mouse button is invalid"}
        for _ in range(max(1, int(click_count))):
            user32.mouse_event(down, 0, 0, 0, 0)
            user32.mouse_event(up, 0, 0, 0, 0)
        return {"success": True}
    except Exception as exc:  # noqa: BLE001
        return {"success": False, "error": f"mouse host unavailable: {exc}"}


def perform_keyboard(action: str, text: str, keys: tuple[str, ...]) -> dict[str, Any]:
    """Type or press allowlisted keys. Never called from unit tests."""
    try:
        import ctypes
        from ctypes import wintypes
    except Exception:
        return {"success": False, "error": "keyboard host unavailable"}
    try:
        user32 = ctypes.windll.user32
        if action == "type_text":
            for char in text:
                vk = user32.VkKeyScanW(wintypes.WCHAR(char))
                if vk == -1:
                    return {"success": False, "error": "character cannot be typed"}
                _tap(user32, vk & 0xFF)
            return {"success": True}
        codes = [_vk(name) for name in keys]
        if any(code is None for code in codes):
            return {"success": False, "error": "key is not allowlisted"}
        if action == "press_key":
            _tap(user32, codes[0] or 0)
            return {"success": True}
        for code in codes:
            user32.keybd_event(code, 0, 0, 0)
        for code in reversed(codes):
            user32.keybd_event(code, 0, 2, 0)
        return {"success": True}
    except Exception as exc:  # noqa: BLE001
        return {"success": False, "error": f"keyboard host unavailable: {exc}"}


def _button_flags(button: str) -> tuple[int, int]:
    mapping = {
        "left": (0x0002, 0x0004),
        "right": (0x0008, 0x0010),
        "middle": (0x0020, 0x0040),
    }
    return mapping.get(button, (0, 0))


def _vk(name: str) -> int | None:
    special = {
        "enter": 0x0D,
        "tab": 0x09,
        "escape": 0x1B,
        "space": 0x20,
        "backspace": 0x08,
        "delete": 0x2E,
        "up": 0x26,
        "down": 0x28,
        "left": 0x25,
        "right": 0x27,
        "home": 0x24,
        "end": 0x23,
        "pageup": 0x21,
        "pagedown": 0x22,
        "ctrl": 0x11,
        "alt": 0x12,
        "shift": 0x10,
        "win": 0x5B,
    }
    if name in special:
        return special[name]
    if len(name) == 1 and name.isalpha():
        return ord(name.upper())
    if len(name) == 1 and name.isdigit():
        return ord(name)
    if name.startswith("f") and name[1:].isdigit():
        index = int(name[1:])
        if 1 <= index <= 12:
            return 0x70 + index - 1
    return None


def _tap(user32: Any, vk: int) -> None:
    user32.keybd_event(vk, 0, 0, 0)
    user32.keybd_event(vk, 0, 2, 0)


__all__ = ["perform_keyboard", "perform_mouse", "screen_size"]
