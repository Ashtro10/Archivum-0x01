"""Sole Git subprocess boundary (B-033, ADR-0032).

Controller-owned. Not a Tool. Distinct from the testing process runner.
Testing, or Evaluation. Fixed operations only; never a general Git argv API.
"""

from __future__ import annotations

import os
import re
import subprocess
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from astra.deployment.paths import (
    canonical,
    denied_reason,
    is_link,
    is_non_relative_path,
    is_under,
    resolve_dest,
)
from astra.logging import get_logger

_LOGGER = get_logger(__name__)

MAX_OUTPUT_BYTES = 65536
DEFAULT_TIMEOUT = 30.0
MAX_TIMEOUT = 60.0
_WINDOWS_HOST_VARS = ("SYSTEMROOT", "WINDIR", "SystemDrive")
_COMMIT_ID = re.compile(r"^[0-9a-f]{40}(?:[0-9a-f]{24})?$")
_VERSION_ID = re.compile(r"^d-[0-9a-f]{16}-\d{4,}$")
_REV = re.compile(r"^(HEAD(?:\^)?|[0-9a-f]{40}|[0-9a-f]{64})$")
_GIT_NAME = re.compile(r"^[A-Za-z0-9 ._'-]{1,64}$")
_GIT_EMAIL = re.compile(r"^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$")
_ALLOWED_CONFIG_KEYS = frozenset(
    {"user.name", "user.email", "commit.gpgsign", "core.hooksPath", "safe.directory"}
)
_ALLOWED_SUBCOMMANDS = frozenset(
    {
        "status",
        "init",
        "add",
        "commit",
        "rev-parse",
        "log",
        "cat-file",
        "ls-tree",
        "checkout",
        "diff",
    }
)
_FORBIDDEN_EXE_NAMES = frozenset(
    {
        "cmd",
        "cmd.exe",
        "powershell",
        "powershell.exe",
        "pwsh",
        "pwsh.exe",
        "python",
        "python.exe",
        "pythonw",
        "pythonw.exe",
        "bash",
        "bash.exe",
        "sh",
        "sh.exe",
        "wsl",
        "wsl.exe",
    }
)


@dataclass(frozen=True, slots=True)
class GitExecResult:
    """Outcome of one internally constructed Git invocation."""

    ok: bool = False
    reason: str = ""
    stdout: str = ""
    stderr: str = ""
    exit_code: int | None = None
    timed_out: bool = False


def parse_commit_id(stdout: str) -> str:
    """Return a strict commit id from the first stdout line, or empty."""
    if not stdout:
        return ""
    line = stdout.splitlines()[0].strip()
    if _COMMIT_ID.fullmatch(line):
        return line
    return ""


class GitRunner:
    """Single Git spawn site. Commands are built internally from fixed ops."""

    def __init__(
        self,
        *,
        project_root: Path | str,
        executable: Path | str | None,
        scratch_root: Path | str | None = None,
        author_name: str = "Astra",
        author_email: str = "astra@localhost",
        timeout_seconds: float = DEFAULT_TIMEOUT,
    ) -> None:
        self._project = canonical(project_root)
        self._scratch = canonical(scratch_root) if scratch_root is not None else None
        self._author_name = str(author_name or "")
        self._author_email = str(author_email or "")
        timeout = float(timeout_seconds)
        if timeout <= 0 or timeout > MAX_TIMEOUT:
            timeout = DEFAULT_TIMEOUT
        self._timeout = timeout
        self._exe, self._exe_reason = _validate_executable(executable, self._scratch)

    @property
    def configured(self) -> bool:
        """True only when a trusted Git executable was accepted."""
        return self._exe is not None

    def status(self) -> GitExecResult:
        """Read-only repository status."""
        argv, err = self._base_argv()
        if err:
            return err
        return self._spawn((*argv, "status", "--porcelain=v1"))

    def init_repository(self) -> GitExecResult:
        """Create a repository at the configured project_root only."""
        argv, err = self._base_argv()
        if err:
            return err
        return self._spawn((*argv, "init", "--", "."))

    def add(self, paths: tuple[str, ...]) -> GitExecResult:
        """Stage validated relative paths. Never ``git add -A``."""
        argv, err = self._base_argv()
        if err:
            return err
        rels, path_err = self._validated_rels(paths)
        if path_err or not rels:
            return GitExecResult(reason=path_err or "no addable paths")
        return self._spawn((*argv, "add", "--", *rels))

    def commit(self, version_id: str) -> GitExecResult:
        """Commit with a deterministic message. No caller-supplied message."""
        ident = str(version_id or "").strip()
        if not _VERSION_ID.fullmatch(ident):
            return GitExecResult(reason="version id rejected")
        if not _GIT_NAME.fullmatch(self._author_name):
            return GitExecResult(reason="git author name rejected")
        if not _GIT_EMAIL.fullmatch(self._author_email):
            return GitExecResult(reason="git author email rejected")
        argv, err = self._base_argv()
        if err:
            return err
        message = f"Astra deployment {ident}"
        identity = (
            "-c",
            f"user.name={self._author_name}",
            "-c",
            f"user.email={self._author_email}",
            "-c",
            "commit.gpgsign=false",
        )
        return self._spawn((*argv, *identity, "commit", "-m", message))

    def rev_parse(self, rev: str = "HEAD") -> GitExecResult:
        """Resolve a tightly validated revision to a commit id."""
        argv, err = self._base_argv()
        if err:
            return err
        token = str(rev or "").strip()
        if not _REV.fullmatch(token):
            return GitExecResult(reason="revision rejected")
        return self._spawn((*argv, "rev-parse", "--verify", token))

    def log(self, limit: int = 20) -> GitExecResult:
        """Inspect recent commits. Format is fixed."""
        argv, err = self._base_argv()
        if err:
            return err
        count = int(limit)
        if count < 1:
            count = 1
        if count > 50:
            count = 50
        return self._spawn(
            (*argv, "log", f"-n{count}", "--format=%H%x09%P%x09%s%x09%cI")
        )

    def cat_file_type(self, commit_id: str) -> GitExecResult:
        """Verify an object is a commit. Stdout is not trusted as an id."""
        argv, err = self._base_argv()
        if err:
            return err
        digest = str(commit_id or "").strip()
        if not _COMMIT_ID.fullmatch(digest):
            return GitExecResult(reason="commit id rejected")
        return self._spawn((*argv, "cat-file", "-t", digest))

    def ls_tree(self, commit_id: str) -> GitExecResult:
        """List paths in a commit. Caller must still filter them."""
        argv, err = self._base_argv()
        if err:
            return err
        digest = str(commit_id or "").strip()
        if not _COMMIT_ID.fullmatch(digest):
            return GitExecResult(reason="commit id rejected")
        return self._spawn((*argv, "ls-tree", "-r", "--name-only", digest))

    def checkout_paths(self, commit_id: str, paths: tuple[str, ...]) -> GitExecResult:
        """Restore validated paths from a known commit. Never ``checkout .``."""
        argv, err = self._base_argv()
        if err:
            return err
        digest = str(commit_id or "").strip()
        if not _COMMIT_ID.fullmatch(digest):
            return GitExecResult(reason="commit id rejected")
        rels, path_err = self._validated_rels(paths)
        if path_err or not rels:
            return GitExecResult(reason=path_err or "no restorable paths")
        return self._spawn((*argv, "checkout", "-f", digest, "--", *rels))

    def diff_quiet(self, commit_id: str, paths: tuple[str, ...]) -> GitExecResult:
        """Exit 0 only when worktree matches the commit for those paths."""
        argv, err = self._base_argv()
        if err:
            return err
        digest = str(commit_id or "").strip()
        if not _COMMIT_ID.fullmatch(digest):
            return GitExecResult(reason="commit id rejected")
        rels, path_err = self._validated_rels(paths)
        if path_err or not rels:
            return GitExecResult(reason=path_err or "no comparable paths")
        return self._spawn((*argv, "diff", "--quiet", digest, "--", *rels))

    def _base_argv(self) -> tuple[tuple[str, ...], GitExecResult | None]:
        denied = self._repo_denied()
        if denied:
            return (), GitExecResult(reason=denied)
        if self._exe is None:
            return (), GitExecResult(reason=self._exe_reason or "git executable not configured")
        project = self._project
        hooks = (
            "-c",
            f"core.hooksPath={os.devnull}",
            "-c",
            f"safe.directory={project}",
        )
        return (str(self._exe), *hooks), None

    def _repo_denied(self) -> str:
        project = self._project
        if project is None or not project.is_dir():
            return "repository path unresolvable"
        if is_link(project):
            return "repository path is a symlink or junction; refuse"
        if self._scratch is not None:
            if project == self._scratch or is_under(project, self._scratch):
                return "scratch cannot be a Git repository"
        git_dir = project / ".git"
        if git_dir.exists() or git_dir.is_symlink():
            if git_dir.is_file() or (not git_dir.is_dir()):
                return "fake .git path rejected"
            if is_link(git_dir):
                return ".git is a symlink or junction; refuse"
        return ""

    def _validated_rels(self, paths: tuple[str, ...]) -> tuple[tuple[str, ...], str]:
        project = self._project
        if project is None:
            return (), "repository path unresolvable"
        rels: list[str] = []
        seen: set[str] = set()
        for raw in paths:
            text = str(raw or "").strip()
            if not text or text.startswith("-"):
                return (), "git path rejected"
            if text.startswith("\\\\") or text.startswith("//"):
                return (), "UNC git path rejected"
            if is_non_relative_path(text):
                canon = canonical(text)
                if canon is None or not is_under(canon, project):
                    return (), "absolute git path escaped project_root"
                try:
                    text = canon.relative_to(project).as_posix()
                except ValueError:
                    return (), "absolute git path escaped project_root"
            dest, rel, err = resolve_dest(text, project)
            if err or dest is None or rel is None:
                return (), err or "git path rejected"
            posix = rel.as_posix()
            denied = denied_reason(rel)
            if denied:
                return (), denied
            key = os.path.normcase(posix)
            if key in seen:
                continue
            seen.add(key)
            rels.append(posix)
        if not rels:
            return (), "no git paths"
        return tuple(rels), ""

    def _spawn(self, argv: tuple[str, ...]) -> GitExecResult:
        if self._exe is None:
            return GitExecResult(reason=self._exe_reason or "git executable not configured")
        if not argv or argv[0] != str(self._exe):
            return GitExecResult(reason="git argv[0] is not the configured executable")
        for part in argv:
            if any(ch in part for ch in ("\n", "\r", "\0")):
                return GitExecResult(reason="control characters in git argv")
        if not _argv_is_fixed(argv):
            return GitExecResult(reason="git argv rejected")
        project = self._project
        if project is None:
            return GitExecResult(reason="repository path unresolvable")
        env = _git_child_env(self._exe, self._author_name, self._author_email)
        try:
            proc = subprocess.Popen(  # noqa: S603
                list(argv),
                cwd=str(project),
                env=env,
                shell=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        except Exception as exc:  # noqa: BLE001
            _LOGGER.debug("git spawn failed: %s", exc)
            return GitExecResult(reason=f"git spawn failed: {exc}")
        stdout_b, stderr_b, timed_out, code = _bounded_wait(
            proc, timeout=self._timeout, max_bytes=MAX_OUTPUT_BYTES
        )
        stdout = stdout_b.decode("utf-8", errors="replace")
        stderr = stderr_b.decode("utf-8", errors="replace")
        if timed_out:
            return GitExecResult(
                reason="git timed out",
                stdout=stdout,
                stderr=stderr,
                exit_code=code,
                timed_out=True,
            )
        if code != 0:
            return GitExecResult(
                reason="git exited non-zero",
                stdout=stdout,
                stderr=stderr,
                exit_code=code,
            )
        return GitExecResult(
            ok=True,
            reason="git exited",
            stdout=stdout,
            stderr=stderr,
            exit_code=code,
        )


def _validate_executable(
    raw: Path | str | None, scratch: Path | None
) -> tuple[Path | None, str]:
    if raw is None or str(raw).strip() == "":
        return None, "git executable not configured"
    path = canonical(raw)
    if path is None:
        return None, "git executable unresolvable"
    name = path.name.lower()
    if name not in {"git", "git.exe"}:
        return None, "git executable name rejected"
    if name in _FORBIDDEN_EXE_NAMES:
        return None, "git executable name rejected"
    if scratch is not None and (path == scratch or is_under(path, scratch)):
        return None, "git executable under scratch; refuse"
    if not path.is_file():
        return None, "git executable missing"
    return path, ""


def _argv_is_fixed(argv: tuple[str, ...]) -> bool:
    if len(argv) < 2:
        return False
    idx = 1
    while idx + 1 < len(argv) and argv[idx] == "-c":
        assignment = argv[idx + 1]
        key, sep, _value = assignment.partition("=")
        if not sep or key not in _ALLOWED_CONFIG_KEYS:
            return False
        idx += 2
    if idx >= len(argv):
        return False
    return argv[idx] in _ALLOWED_SUBCOMMANDS


def _git_child_env(exe: Path, author_name: str, author_email: str) -> dict[str, str]:
    env: dict[str, str] = {
        "PATH": str(exe.parent),
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_CONFIG_SYSTEM": os.devnull,
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_ASKPASS": os.devnull,
        "GCM_INTERACTIVE": "never",
        "LC_ALL": "C",
        "GIT_AUTHOR_NAME": author_name,
        "GIT_AUTHOR_EMAIL": author_email,
        "GIT_COMMITTER_NAME": author_name,
        "GIT_COMMITTER_EMAIL": author_email,
    }
    for key in _WINDOWS_HOST_VARS:
        value = os.environ.get(key)
        if value:
            env[key] = value
    return env


def _bounded_wait(
    proc: subprocess.Popen[bytes],
    *,
    timeout: float,
    max_bytes: int,
) -> tuple[bytes, bytes, bool, int | None]:
    stdout_data = bytearray()
    stderr_data = bytearray()
    lock = threading.Lock()

    def _reader(src: Any, which: str) -> None:
        try:
            while True:
                chunk = src.read(4096)
                if not chunk:
                    break
                with lock:
                    bucket = stdout_data if which == "out" else stderr_data
                    room = max_bytes - len(bucket)
                    if room <= 0:
                        continue
                    bucket.extend(chunk[:room])
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
    return bytes(stdout_data), bytes(stderr_data), timed_out, proc.returncode


__all__ = ["GitExecResult", "GitRunner", "parse_commit_id", "MAX_OUTPUT_BYTES"]
