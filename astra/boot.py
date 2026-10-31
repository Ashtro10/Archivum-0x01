"""Process bootstrap for Primus.

Windows frozen builds re-exec the exe for multiprocessing children.
Those children must not open another window. ``freeze_support`` has to
run before Dear PyGui or the controller is imported.
"""

from __future__ import annotations

import multiprocessing
import sys
import traceback
from pathlib import Path

from astra.presentation.conversation import (
    AssistantConversation,
    ConversationReply,
    UnavailableConversation,
)
from astra.presentation.orchestration import ConversationOrchestrator
from astra.product import PRODUCT_NAME

_MUTEX_NAME = "Local\\PrimusSingleInstance"
_ERROR_ALREADY_EXISTS = 183
_MUTEX_HANDLE = None
_GUI_FLAGS = frozenset({"--demo", "--smoke"})
_PROBE_FLAG = "--probe"
_PROBE_RESEARCH_FLAG = "--probe-research"
_PROBE_RUNTIME_WRITE_FLAG = "--probe-runtime-write"
_PROBE_RUNTIME_READ_FLAG = "--probe-runtime-read"
_PROBE_MEMORY_STORE_FLAG = "--probe-memory-store"
_PROBE_MEMORY_ASK_FLAG = "--probe-memory-ask"
_PROBE_MEMORY_FLAG = "--probe-memory"


def is_non_gui_invocation(argv: list[str] | None = None) -> bool:
    """True when this process is a worker, not a window.

    Frozen ``sys.executable`` is Primus.exe. ProcessRunner and unittest
    spawn it with ``-m`` / ``-c``. Those children must not open a GUI.
    """
    args = list(sys.argv if argv is None else argv)
    if args and not args[0].startswith("-"):
        args = args[1:]
    if any(part.startswith("--multiprocessing-") for part in args):
        return True
    if any(part in _GUI_FLAGS for part in args):
        return False
    return "-m" in args or "-c" in args


def _ensure_import_paths() -> None:
    """Frozen builds must see ``ui`` and ``astra`` under the extract dir."""
    if not getattr(sys, "frozen", False):
        return
    exe_dir = Path(sys.executable).resolve().parent
    candidates = [
        getattr(sys, "_MEIPASS", None),
        exe_dir,
        exe_dir / "_internal",
    ]
    for candidate in candidates:
        if not candidate:
            continue
        path = str(Path(candidate))
        if path not in sys.path:
            sys.path.insert(0, path)


def prepare_process() -> bool:
    """Stop workers and duplicate frozen instances.

    Returns False when this process must not open a window.
    Worker argv exits silently. Never show a dialog for those.
    """
    global _MUTEX_HANDLE
    if is_non_gui_invocation():
        multiprocessing.freeze_support()
        return False
    multiprocessing.freeze_support()
    _ensure_import_paths()
    if not getattr(sys, "frozen", False):
        return True
    if sys.platform != "win32":
        return True
    try:
        import ctypes
        from ctypes import wintypes

        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.CreateMutexW.argtypes = [
            wintypes.LPVOID,
            wintypes.BOOL,
            wintypes.LPCWSTR,
        ]
        kernel32.CreateMutexW.restype = wintypes.HANDLE
        handle = kernel32.CreateMutexW(None, False, _MUTEX_NAME)
        err = ctypes.get_last_error()
        if not handle:
            return True
        if err == _ERROR_ALREADY_EXISTS:
            if _activate_existing_window():
                return False
            return True
        _MUTEX_HANDLE = handle
    except Exception:
        return True
    return True


def _activate_existing_window() -> bool:
    """Bring an existing Primus window forward. No MessageBox."""
    try:
        import ctypes

        user32 = ctypes.windll.user32
        for title in (f"{PRODUCT_NAME} [DEMO]", PRODUCT_NAME):
            hwnd = user32.FindWindowW(None, title)
            if hwnd:
                user32.ShowWindow(hwnd, 9)
                user32.SetForegroundWindow(hwnd)
                return True
    except Exception:
        return False
    return False


class ResearchConversation:
    """Legacy adapter. Production talk path is ConversationOrchestrator."""

    def __init__(self, controller: object) -> None:
        self._controller = controller

    def ask(self, text: str) -> ConversationReply:
        llm = getattr(self._controller, "llm", None)
        return AssistantConversation(llm).ask(text)


def _start_controller():
    try:
        from astra.configuration import load_settings
        from astra.core.astra_controller import AstraController
        from astra.reasoning.llama_cpp_backend import prepare_llama_native_libs

        prepare_llama_native_libs()
        settings = load_settings()
        ctrl = AstraController(settings)
        ctrl.start()
        return ctrl
    except Exception as exc:
        import logging

        logging.getLogger(__name__).warning(
            "%s controller startup failed (%s); window will run degraded",
            PRODUCT_NAME,
            exc,
        )
        return None


def _startup_log_path() -> Path:
    if getattr(sys, "frozen", False):
        root = Path(sys.executable).resolve().parent
    else:
        root = Path(__file__).resolve().parents[1]
    return root / "logs" / "primus-startup.log"


def _frozen_error(exc: BaseException) -> None:
    detail = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))
    log_path = _startup_log_path()
    try:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text(detail, encoding="utf-8")
    except OSError:
        log_path = Path.cwd() / "primus-startup.log"
        try:
            log_path.write_text(detail, encoding="utf-8")
        except OSError:
            pass
    message = (
        f"{PRODUCT_NAME} could not start.\n\n"
        f"{type(exc).__name__}: {exc}\n\n"
        f"Details: {log_path}"
    )
    if getattr(sys, "frozen", False):
        try:
            import ctypes

            ctypes.windll.user32.MessageBoxW(0, message[:2000], PRODUCT_NAME, 0x10)
            return
        except Exception:
            pass
    sys.stderr.write(message + "\n")
    sys.stderr.write(detail + "\n")


def _runtime_probe_result_path(name: str) -> Path:
    from astra.configuration.paths import project_root

    return project_root() / "logs" / name


def _probe_result_path() -> Path:
    from astra.configuration.paths import project_root

    return project_root() / "logs" / "primus-probe.json"


def _research_probe_result_path() -> Path:
    from astra.configuration.paths import project_root

    return project_root() / "logs" / "primus-probe-research.json"


def _ensure_probe_isolation() -> Path | None:
    """Run probes against scratch storage, never the production user DB."""
    import os
    import secrets

    from astra.configuration.paths import ENV_HOME, ENV_HOME_ALIAS, default_scratch_directory

    existing = os.environ.get(ENV_HOME) or os.environ.get(ENV_HOME_ALIAS)
    if existing:
        return Path(existing).expanduser().resolve()
    home = default_scratch_directory() / "probes" / secrets.token_hex(8)
    home.mkdir(parents=True, exist_ok=True)
    os.environ[ENV_HOME] = str(home)
    os.environ["ASTRA_PROBE"] = "1"
    return home


def _reuse_runtime_probe_home() -> Path | None:
    """Point read probe at the DB written by the last runtime-write probe."""
    import json
    import os

    from astra.configuration.paths import ENV_HOME, project_root

    write_log = project_root() / "logs" / "probe-runtime-write.json"
    if not write_log.is_file():
        return None
    try:
        payload = json.loads(write_log.read_text(encoding="utf-8"))
    except Exception:
        return None
    probe_home = str(payload.get("probe_home", "") or "").strip()
    if not probe_home:
        return None
    home = Path(probe_home).expanduser().resolve()
    if not home.is_dir():
        return None
    os.environ[ENV_HOME] = str(home)
    os.environ["ASTRA_PROBE"] = "1"
    return home


def _run_probe() -> int:
    """Headless frozen/source path: orchestrator + memory, no GUI."""
    import json

    from astra.presentation.orchestration import run_conversation_probe

    _ensure_probe_isolation()
    path = _probe_result_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    controller = _start_controller()
    if controller is None:
        path.write_text(
            json.dumps({"ok": False, "error": "controller failed"}),
            encoding="utf-8",
        )
        return 1
    try:
        payload = run_conversation_probe(controller)
        path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        return 0 if payload.get("ok") else 1
    except Exception as exc:
        path.write_text(
            json.dumps({"ok": False, "error": f"{type(exc).__name__}: {exc}"}),
            encoding="utf-8",
        )
        return 1
    finally:
        try:
            controller.shutdown()
        except Exception:
            pass


def _run_research_probe() -> int:
    """Headless frozen/source path: orchestrator + controlled HTTPS retrieval."""
    import json

    from astra.presentation.orchestration import run_research_probe

    _ensure_probe_isolation()
    path = _research_probe_result_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    controller = _start_controller()
    if controller is None:
        path.write_text(
            json.dumps({"ok": False, "error": "controller failed"}),
            encoding="utf-8",
        )
        return 1
    try:
        payload = run_research_probe(controller)
        path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        return 0 if payload.get("ok") else 1
    except Exception as exc:
        path.write_text(
            json.dumps({"ok": False, "error": f"{type(exc).__name__}: {exc}"}),
            encoding="utf-8",
        )
        return 1
    finally:
        try:
            controller.shutdown()
        except Exception:
            pass


def _run_named_probe(
    writer, filename: str, isolated: Path | None = None
) -> int:
    import json

    if isolated is None:
        isolated = _ensure_probe_isolation()
    path = _runtime_probe_result_path(filename)
    path.parent.mkdir(parents=True, exist_ok=True)
    controller = _start_controller()
    if controller is None:
        path.write_text(
            json.dumps({"ok": False, "error": "controller failed"}),
            encoding="utf-8",
        )
        return 1
    try:
        payload = writer(controller)
        if isinstance(payload, dict) and isolated is not None:
            payload = dict(payload)
            payload["probe_home"] = str(isolated)
        path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        return 0 if payload.get("ok") else 1
    except Exception as exc:
        path.write_text(
            json.dumps({"ok": False, "error": f"{type(exc).__name__}: {exc}"}),
            encoding="utf-8",
        )
        return 1
    finally:
        try:
            controller.shutdown()
        except Exception:
            pass


def _run_runtime_write_probe() -> int:
    from astra.presentation.orchestration import run_runtime_write_probe

    return _run_named_probe(run_runtime_write_probe, "probe-runtime-write.json")


def _run_runtime_read_probe() -> int:
    from astra.presentation.orchestration import run_runtime_read_probe

    isolated = _reuse_runtime_probe_home()
    if isolated is None:
        isolated = _ensure_probe_isolation()
    return _run_named_probe(run_runtime_read_probe, "probe-runtime-read.json", isolated)


def _run_memory_turn_probe(message: str, filename: str) -> int:
    import json

    from astra.presentation.orchestration import run_memory_turn_probe

    isolated = _ensure_probe_isolation()
    path = _runtime_probe_result_path(filename)
    path.parent.mkdir(parents=True, exist_ok=True)
    controller = _start_controller()
    if controller is None:
        path.write_text(
            json.dumps({"ok": False, "error": "controller failed"}),
            encoding="utf-8",
        )
        return 1
    try:
        payload = run_memory_turn_probe(controller, message)
        if isinstance(payload, dict) and isolated is not None:
            payload = dict(payload)
            payload["probe_home"] = str(isolated)
        path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        return 0 if payload.get("ok") else 1
    except Exception as exc:
        path.write_text(
            json.dumps({"ok": False, "error": f"{type(exc).__name__}: {exc}"}),
            encoding="utf-8",
        )
        return 1
    finally:
        try:
            controller.shutdown()
        except Exception:
            pass


def _probe_argument(argv: list[str], flag: str) -> str:
    if flag not in argv:
        return ""
    index = argv.index(flag)
    if index + 1 >= len(argv):
        return ""
    return str(argv[index + 1] or "").strip()


def _run_memory_inspect_probe() -> int:
    from astra.presentation.orchestration import run_memory_inspect_probe

    return _run_named_probe(run_memory_inspect_probe, "probe-memory-inspect.json")


def launch(argv: list[str]) -> int:
    """Open at most one Primus window. Lazy-imports the UI."""
    if is_non_gui_invocation(argv):
        multiprocessing.freeze_support()
        return 0
    if _PROBE_FLAG in argv:
        return _run_probe()
    if _PROBE_RESEARCH_FLAG in argv:
        return _run_research_probe()
    if _PROBE_RUNTIME_WRITE_FLAG in argv:
        return _run_runtime_write_probe()
    if _PROBE_RUNTIME_READ_FLAG in argv:
        return _run_runtime_read_probe()
    if _PROBE_MEMORY_STORE_FLAG in argv:
        return _run_memory_turn_probe(
            _probe_argument(argv, _PROBE_MEMORY_STORE_FLAG),
            "probe-memory-store.json",
        )
    if _PROBE_MEMORY_ASK_FLAG in argv:
        return _run_memory_turn_probe(
            _probe_argument(argv, _PROBE_MEMORY_ASK_FLAG),
            "probe-memory-ask.json",
        )
    if _PROBE_MEMORY_FLAG in argv:
        return _run_memory_inspect_probe()
    if not prepare_process():
        return 0
    demo = "--demo" in argv
    controller = None if demo else _start_controller()
    provider = None
    conversation = UnavailableConversation()
    if controller is not None:
        from astra.core.astra_controller import ControllerStateProvider

        provider = ControllerStateProvider(controller)
        try:
            conversation = ConversationOrchestrator.from_controller(controller)
        except Exception:
            conversation = ConversationOrchestrator.from_controller(None)
    try:
        from ui.app import main

        main(argv, provider=provider, conversation=conversation, controller=controller)
        return 0
    except Exception as exc:
        if getattr(sys, "frozen", False):
            _frozen_error(exc)
            return 1
        raise
    finally:
        if controller is not None:
            try:
                controller.shutdown()
            except Exception:
                pass


__all__ = [
    "ResearchConversation",
    "is_non_gui_invocation",
    "launch",
    "prepare_process",
]
