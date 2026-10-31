"""Controlled process runner (B-024, ADR-0023).

Not a TestingAgent. Not a Tool. Sole testing subprocess boundary.
Consults Security, then may spawn ``sys.executable`` in a known subset session.
"""

from __future__ import annotations

import enum
import os
import subprocess
import sys
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from astra.logging import get_logger
from astra.security.contracts import ProcessSpec, Verdict
from astra.testing.workspace import _canonical

_LOGGER = get_logger(__name__)

DEFAULT_PROBE_TIMEOUT = 5.0
MAX_OUTPUT_BYTES = 65536
_WINDOWS_HOST_VARS = ("SYSTEMROOT", "WINDIR", "SystemDrive")


class ProcessStatus(enum.Enum):
    """Outcome of a process request. Not a test verdict."""

    NOT_AUTHORIZED = "not_authorized"
    NOT_SPAWNED = "not_spawned"
    SPAWN_FAILURE = "spawn_failure"
    TIMED_OUT = "timed_out"
    EXITED = "exited"
    RUNNING = "running"


@dataclass(frozen=True, slots=True)
class ProcessResult:
    """Outcome of a process request.

    ``success`` is True only when ``status`` is ``exited`` (any exit code).
    That is not ``tests passed`` and is not a ``TestReport``.
    """

    spawned: bool = False
    success: bool = False
    reason: str = ""
    status: ProcessStatus = ProcessStatus.NOT_SPAWNED
    exit_code: int | None = None
    stdout: str = ""
    stderr: str = ""
    timed_out: bool = False
    stdout_truncated: bool = False
    stderr_truncated: bool = False
    pid: int | None = None


class ProcessRunner:
    """Single spawn site. Controller-owned. Does not interpret tests."""

    def __init__(self, *, security: Any, subset: Any | None = None) -> None:
        self._security = security
        self._subset = subset

    def run(self, spec: ProcessSpec, *, session_id: str = "") -> ProcessResult:
        """Consult Security. Spawn only inside a known copied-subset session."""
        sid = str(session_id or "").strip()
        if not sid:
            return ProcessResult(
                reason="missing session_id; fail closed",
                status=ProcessStatus.NOT_SPAWNED,
            )
        lookup = getattr(self._subset, "session_root", None)
        session = lookup(sid) if callable(lookup) else None
        if session is None:
            return ProcessResult(
                reason=f"unknown subset session {sid}",
                status=ProcessStatus.NOT_SPAWNED,
            )
        session_canon = _canonical(session)
        if session_canon is None:
            return ProcessResult(
                reason="session path unresolvable",
                status=ProcessStatus.NOT_SPAWNED,
            )
        if not session_canon.is_dir():
            return ProcessResult(
                reason="session directory missing",
                status=ProcessStatus.NOT_SPAWNED,
            )

        cwd_raw = getattr(spec, "cwd", None)
        if cwd_raw is None or str(cwd_raw).strip() == "":
            cwd_canon = session_canon
        else:
            cwd_canon = _canonical(cwd_raw)
        if cwd_canon is None or cwd_canon != session_canon:
            return ProcessResult(
                reason="cwd is not the requested subset session; fail closed",
                status=ProcessStatus.NOT_SPAWNED,
            )

        argv = tuple(str(part) for part in (getattr(spec, "argv", ()) or ()))
        gated = ProcessSpec(
            argv=argv,
            cwd=session_canon,
            env=(),
            timeout_seconds=float(getattr(spec, "timeout_seconds", 0) or 0),
        )
        check = getattr(self._security, "check_process_execution", None)
        if check is None:
            return ProcessResult(
                reason="security has no check_process_execution",
                status=ProcessStatus.NOT_SPAWNED,
            )
        try:
            decision = check(gated)
        except Exception as exc:  # noqa: BLE001
            return ProcessResult(
                reason=f"process policy check failed: {exc}",
                status=ProcessStatus.NOT_SPAWNED,
            )
        verdict = getattr(decision, "verdict", None)
        reason = str(getattr(decision, "reason", "") or "process denied")
        if verdict is not Verdict.ALLOW:
            return ProcessResult(
                reason=reason,
                status=ProcessStatus.NOT_AUTHORIZED,
            )

        exe_canon = _canonical(argv[0]) if argv else None
        interpreter = _canonical(sys.executable)
        if exe_canon is None or interpreter is None or exe_canon != interpreter:
            return ProcessResult(
                reason="spawn requires the current interpreter; fail closed",
                status=ProcessStatus.NOT_SPAWNED,
            )
        if getattr(sys, "frozen", False):
            return ProcessResult(
                reason="frozen executable is not a Python interpreter; fail closed",
                status=ProcessStatus.NOT_SPAWNED,
            )

        child_env, env_reason = self._child_env(session_canon)
        if child_env is None:
            return ProcessResult(
                reason=env_reason,
                status=ProcessStatus.NOT_SPAWNED,
            )

        timeout = float(gated.timeout_seconds)
        hidden = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        try:
            proc = subprocess.Popen(  # noqa: S603
                list(argv),
                cwd=str(session_canon),
                env=child_env,
                shell=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                creationflags=hidden,
            )
        except Exception as exc:  # noqa: BLE001
            _LOGGER.debug("process spawn failed: %s", exc)
            return ProcessResult(
                reason=f"spawn failed: {exc}",
                status=ProcessStatus.SPAWN_FAILURE,
            )

        stdout_b, stderr_b, out_trunc, err_trunc, timed_out, code = _bounded_wait(
            proc, timeout=timeout, max_bytes=MAX_OUTPUT_BYTES
        )
        stdout = stdout_b.decode("utf-8", errors="replace")
        stderr = stderr_b.decode("utf-8", errors="replace")
        if timed_out:
            return ProcessResult(
                spawned=True,
                success=False,
                reason="process timed out; direct child killed",
                status=ProcessStatus.TIMED_OUT,
                exit_code=code,
                stdout=stdout,
                stderr=stderr,
                timed_out=True,
                stdout_truncated=out_trunc,
                stderr_truncated=err_trunc,
            )
        return ProcessResult(
            spawned=True,
            success=True,
            reason="process exited",
            status=ProcessStatus.EXITED,
            exit_code=code,
            stdout=stdout,
            stderr=stderr,
            timed_out=False,
            stdout_truncated=out_trunc,
            stderr_truncated=err_trunc,
        )

    def launch(self, spec: ProcessSpec, *, session_id: str = "") -> ProcessResult:
        """Spawn an allowlisted application. Does not wait for the app to exit."""
        sid = str(session_id or "").strip()
        if not sid:
            return ProcessResult(
                reason="missing session_id; fail closed",
                status=ProcessStatus.NOT_SPAWNED,
            )
        lookup = getattr(self._subset, "session_root", None)
        session = lookup(sid) if callable(lookup) else None
        if session is None:
            return ProcessResult(
                reason=f"unknown subset session {sid}",
                status=ProcessStatus.NOT_SPAWNED,
            )
        session_canon = _canonical(session)
        if session_canon is None or not session_canon.is_dir():
            return ProcessResult(
                reason="session directory missing",
                status=ProcessStatus.NOT_SPAWNED,
            )
        cwd_raw = getattr(spec, "cwd", None)
        if cwd_raw is None or str(cwd_raw).strip() == "":
            cwd_canon = session_canon
        else:
            cwd_canon = _canonical(cwd_raw)
        if cwd_canon is None or cwd_canon != session_canon:
            return ProcessResult(
                reason="cwd is not the requested subset session; fail closed",
                status=ProcessStatus.NOT_SPAWNED,
            )
        argv = tuple(str(part) for part in (getattr(spec, "argv", ()) or ()))
        gated = ProcessSpec(
            argv=argv,
            cwd=session_canon,
            env=(),
            timeout_seconds=float(getattr(spec, "timeout_seconds", 0) or 0),
        )
        check = getattr(self._security, "check_process_execution", None)
        if check is None:
            return ProcessResult(
                reason="security has no check_process_execution",
                status=ProcessStatus.NOT_SPAWNED,
            )
        try:
            decision = check(gated)
        except Exception as exc:  # noqa: BLE001
            return ProcessResult(
                reason=f"process policy check failed: {exc}",
                status=ProcessStatus.NOT_SPAWNED,
            )
        verdict = getattr(decision, "verdict", None)
        reason = str(getattr(decision, "reason", "") or "process denied")
        if verdict is not Verdict.ALLOW:
            return ProcessResult(
                reason=reason,
                status=ProcessStatus.NOT_AUTHORIZED,
            )
        exe_canon = _canonical(argv[0]) if argv else None
        if exe_canon is None or not exe_canon.is_file():
            return ProcessResult(
                reason="executable missing or invalid; fail closed",
                status=ProcessStatus.NOT_SPAWNED,
            )
        timeout = float(gated.timeout_seconds)
        if timeout <= 0:
            return ProcessResult(
                reason="timeout missing or above cap; fail closed",
                status=ProcessStatus.NOT_SPAWNED,
            )
        child_env = self._launch_env(exe_canon)
        flags = 0
        extra: dict[str, Any] = {}
        if os.name == "nt":
            flags = int(getattr(subprocess, "DETACHED_PROCESS", 0)) | int(
                getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
            )
        else:
            extra["start_new_session"] = True
        try:
            proc = subprocess.Popen(  # noqa: S603
                list(argv),
                cwd=str(session_canon),
                env=child_env,
                shell=False,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=flags,
                **extra,
            )
        except Exception as exc:  # noqa: BLE001
            _LOGGER.debug("application spawn failed: %s", exc)
            return ProcessResult(
                reason=f"spawn failed: {exc}",
                status=ProcessStatus.SPAWN_FAILURE,
            )
        code = proc.poll()
        pid = int(proc.pid) if proc.pid else None
        if code is None:
            return ProcessResult(
                spawned=True,
                success=True,
                reason="application is running",
                status=ProcessStatus.RUNNING,
                pid=pid,
            )
        return ProcessResult(
            spawned=True,
            success=False,
            reason="application exited immediately after launch",
            status=ProcessStatus.EXITED,
            exit_code=code,
            pid=pid,
        )

    def inspect(self, *, query: str = "", pid: int | None = None) -> list[dict[str, Any]]:
        """Read-only process listing. Never terminates a process."""
        import psutil

        needle = str(query or "").strip().lower()
        want_pid = int(pid) if pid is not None else None
        found: list[dict[str, Any]] = []
        for proc in psutil.process_iter(attrs=("pid", "name", "status")):
            try:
                info = proc.info
                current_pid = int(info.get("pid") or 0)
                name = str(info.get("name") or "")
                status = str(info.get("status") or "")
            except (psutil.Error, TypeError, ValueError):
                continue
            if want_pid is not None and current_pid != want_pid:
                continue
            if needle and needle not in name.lower() and needle not in str(current_pid):
                continue
            found.append(
                {
                    "pid": current_pid,
                    "name": name,
                    "status": status,
                    "running": status.lower() not in {"zombie", "dead"},
                }
            )
            if len(found) >= 32:
                break
        return found

    def _launch_env(self, executable: Path) -> dict[str, str]:
        env: dict[str, str] = {"PATH": str(executable.parent)}
        for key in _WINDOWS_HOST_VARS:
            value = os.environ.get(key)
            if value:
                env[key] = value
        return env

    def _child_env(self, session: Path) -> tuple[dict[str, str] | None, str]:
        tmp = session / ".tmp"
        check = getattr(self._security, "check_temporary_write", None)
        if check is None:
            return None, "security has no check_temporary_write"
        decision = check(tmp)
        if getattr(decision, "verdict", None) is not Verdict.ALLOW:
            reason = getattr(decision, "reason", "temporary write denied")
            return None, str(reason)
        try:
            tmp.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            return None, f"could not create session temp dir: {exc}"

        interpreter = _canonical(sys.executable)
        if interpreter is None:
            return None, "interpreter path unresolvable"
        env: dict[str, str] = {
            "PYTHONPATH": str(session),
            "PATH": str(interpreter.parent),
            "PYTHONNOUSERSITE": "1",
            "PYTHONSAFEPATH": "1",
            "TEMP": str(tmp),
            "TMP": str(tmp),
            "TMPDIR": str(tmp),
        }
        for key in _WINDOWS_HOST_VARS:
            value = os.environ.get(key)
            if value:
                env[key] = value
        return env, ""


def _bounded_wait(
    proc: subprocess.Popen[bytes],
    *,
    timeout: float,
    max_bytes: int,
) -> tuple[bytes, bytes, bool, bool, bool, int | None]:
    stdout_data = bytearray()
    stderr_data = bytearray()
    out_trunc = False
    err_trunc = False
    lock = threading.Lock()

    def _reader(src: Any, which: str) -> None:
        nonlocal out_trunc, err_trunc
        try:
            while True:
                chunk = src.read(4096)
                if not chunk:
                    break
                with lock:
                    if which == "out":
                        room = max_bytes - len(stdout_data)
                        if room <= 0:
                            out_trunc = True
                            continue
                        stdout_data.extend(chunk[:room])
                        if len(chunk) > room:
                            out_trunc = True
                    else:
                        room = max_bytes - len(stderr_data)
                        if room <= 0:
                            err_trunc = True
                            continue
                        stderr_data.extend(chunk[:room])
                        if len(chunk) > room:
                            err_trunc = True
        except Exception:  # noqa: BLE001
            return

    t_out = threading.Thread(target=_reader, args=(proc.stdout, "out"), daemon=True)
    t_err = threading.Thread(target=_reader, args=(proc.stderr, "err"), daemon=True)
    t_out.start()
    t_err.start()
    timed_out = False
    try:
        proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        try:
            proc.kill()
        except OSError:
            pass
        try:
            proc.wait(timeout=1.0)
        except Exception:  # noqa: BLE001
            pass
    t_out.join(timeout=1.0)
    t_err.join(timeout=1.0)
    if proc.stdout is not None:
        proc.stdout.close()
    if proc.stderr is not None:
        proc.stderr.close()
    code = proc.returncode
    return bytes(stdout_data), bytes(stderr_data), out_trunc, err_trunc, timed_out, code


__all__ = [
    "ProcessResult",
    "ProcessRunner",
    "ProcessStatus",
    "DEFAULT_PROBE_TIMEOUT",
    "MAX_OUTPUT_BYTES",
]
