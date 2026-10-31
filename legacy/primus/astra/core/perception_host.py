"""Host-side read-only screen/window probes. Injected into tools. No upload."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any


def capture_desktop(*, max_width: int = 1280, max_height: int = 720) -> dict[str, Any]:
    """Grab the desktop locally. Never writes a file. Fail closed."""
    stamp = datetime.now(timezone.utc).isoformat()
    monitors = _monitor_count()
    try:
        from PIL import ImageGrab  # type: ignore[import-not-found]
    except Exception:
        return {
            "available": False,
            "error": "screen capture unavailable",
            "width": 0,
            "height": 0,
            "max_width": max_width,
            "max_height": max_height,
            "monitor": 0,
            "monitors": monitors,
            "timestamp": stamp,
            "image": b"",
        }
    try:
        image = ImageGrab.grab()
    except Exception as exc:  # noqa: BLE001
        return {
            "available": False,
            "error": f"screen capture unavailable: {exc}",
            "width": 0,
            "height": 0,
            "max_width": max_width,
            "max_height": max_height,
            "monitor": 0,
            "monitors": monitors,
            "timestamp": stamp,
            "image": b"",
        }
    width, height = int(image.size[0]), int(image.size[1])
    resized = image
    if width > max_width or height > max_height:
        resized = image.copy()
        resized.thumbnail((max_width, max_height))
    payload = b""
    try:
        import io

        buf = io.BytesIO()
        resized.save(buf, format="PNG")
        payload = buf.getvalue()
    except Exception:
        payload = b""
    out_w, out_h = int(resized.size[0]), int(resized.size[1])
    return {
        "available": True,
        "error": "",
        "width": out_w,
        "height": out_h,
        "source_width": width,
        "source_height": height,
        "max_width": max_width,
        "max_height": max_height,
        "monitor": 1,
        "monitors": monitors,
        "timestamp": stamp,
        "image": payload,
    }


def inspect_foreground_window() -> dict[str, Any]:
    """Read-only active window. Does not focus, move, or close."""
    try:
        import ctypes
        from ctypes import wintypes
    except Exception:
        return {
            "available": False,
            "error": "active window inspection unavailable",
            "application": "",
            "title": "",
            "pid": None,
        }
    try:
        user32 = ctypes.windll.user32
        hwnd = user32.GetForegroundWindow()
        if not hwnd:
            return {
                "available": False,
                "error": "no active window",
                "application": "",
                "title": "",
                "pid": None,
            }
        length = int(user32.GetWindowTextLengthW(hwnd))
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        title = str(buf.value or "")
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        ident = int(pid.value) if pid.value else None
        name = ""
        if ident:
            try:
                import psutil

                name = str(psutil.Process(ident).name() or "")
            except Exception:
                name = ""
        return {
            "available": True,
            "error": "",
            "application": name,
            "title": title,
            "pid": ident,
            "hwnd": int(hwnd),
        }
    except Exception as exc:  # noqa: BLE001
        return {
            "available": False,
            "error": f"active window inspection unavailable: {exc}",
            "application": "",
            "title": "",
            "pid": None,
        }


def _monitor_count() -> int:
    try:
        import ctypes

        count = int(ctypes.windll.user32.GetSystemMetrics(80))
        return count if count > 0 else 1
    except Exception:
        return 1


__all__ = ["capture_desktop", "inspect_foreground_window"]
