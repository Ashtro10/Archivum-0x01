"""Deny-by-default Security Agent (B-015, ADR-0014).

Policy only. Does not execute tools, open sockets, spawn processes, or
write files. Checks record an in-memory history for observation.
"""

from __future__ import annotations

import os
import sys
from collections import deque
from pathlib import Path
from typing import Any, Sequence
from urllib.parse import urlparse

from astra.core.contracts import AgentRequest, AgentResult
from astra.logging import get_logger
from astra.security.contracts import PolicyDecision, ProcessSpec, SecurityAgent, Verdict
from astra.security.netpolicy import classify_url, ip_is_blocked

_LOGGER = get_logger(__name__)

_HISTORY_MAX = 32
_RESEARCH_SENTINEL_URL = "https://outbound-research.invalid/"
_LOOPBACK_HOSTS = frozenset(
    {"localhost", "127.0.0.1", "::1", "0.0.0.0", "[::1]", "ip6-localhost"}
)
_SHELL_BASENAMES = frozenset(
    {
        "cmd",
        "cmd.exe",
        "command.com",
        "powershell",
        "powershell.exe",
        "pwsh",
        "pwsh.exe",
        "sh",
        "bash",
        "bash.exe",
        "zsh",
        "fish",
        "csh",
        "ksh",
    }
)
_DENIED_ENV_KEYS = frozenset({"path", "pythonpath"})
_DENIED_APP_BASENAMES = frozenset(
    {
        "regedit",
        "regedit.exe",
        "mmc",
        "mmc.exe",
        "gpedit",
        "gpedit.msc",
        "msiexec",
        "msiexec.exe",
        "taskkill",
        "taskkill.exe",
        "shutdown",
        "shutdown.exe",
        "format",
        "format.com",
        "diskpart",
        "diskpart.exe",
    }
)
_INSPECT_BANNED = (
    "kill",
    "terminate",
    "taskkill",
    "admin",
    "install",
    "delete",
    "format",
    "shutdown",
)
_ENV_BANNED = (
    "upload",
    "http://",
    "https://",
    "network",
    "cloud",
    "click",
    "mouse",
    "keyboard",
    "ocr",
)
_ENV_ALLOWED = frozenset(
    {
        "screen",
        "window",
        "desktop",
        "capture_screen",
        "inspect_active_window",
    }
)
_INPUT_BANNED = (
    "password",
    "passwd",
    "credential",
    "api_key",
    "apikey",
    "secret",
    "token",
    "uac",
    "runas",
    "privilege",
    "shutdown",
    "restart",
    "reboot",
    "alt+f4",
    "ctrl+alt+del",
    "ctrl+shift+esc",
    "win+l",
    "win+r",
)
MAX_PROCESS_TIMEOUT_SECONDS = 60.0


def _canonical(path: Path | str) -> Path | None:
    """Absolute normalised path. Does not create files or directories."""
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


def _host_from_url(url: str) -> str:
    raw = url.strip()
    if not raw:
        return ""
    if "://" not in raw:
        raw = "http://" + raw
    try:
        parsed = urlparse(raw)
        host = (parsed.hostname or parsed.netloc or "").lower()
        return host.strip("[]")
    except Exception:
        return ""


def _is_loopback_host(host: str) -> bool:
    if not host:
        return False
    if host in _LOOPBACK_HOSTS:
        return True
    if host.startswith("127."):
        return True
    if host.endswith(".localhost"):
        return True
    return False


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


_READ_SUFFIXES = frozenset({".py", ".md", ".toml", ".txt", ".json", ".csv"})
_HIDDEN_OK = frozenset({".gitignore", ".gitattributes"})
_DENIED_NAMES = frozenset(
    {"credentials.json", "secrets.json", "id_rsa", "id_ed25519"}
)
_DENIED_SEGMENTS = frozenset({".git", ".ssh", "__pycache__", ".venv", "venv"})


def _read_deny_reason(canon: Path, exclusions: Sequence[Path]) -> str | None:
    """Return a deny reason, or None if the path is still a candidate."""
    for part in canon.parts:
        if os.path.normcase(part) in {
            os.path.normcase(seg) for seg in _DENIED_SEGMENTS
        }:
            return f"path segment {part!r} is excluded from project reads"
    for excluded in exclusions:
        if _is_under(canon, excluded):
            return f"path is under excluded prefix {excluded}"
    name = canon.name
    lower = name.lower()
    if lower.startswith(".env"):
        return "dotenv files are excluded from project reads"
    if lower in _DENIED_NAMES:
        return f"basename {name!r} is excluded from project reads"
    if name.startswith(".") and name not in _HIDDEN_OK:
        return "hidden files are excluded from project reads"
    if name not in _HIDDEN_OK and canon.suffix.lower() not in _READ_SUFFIXES:
        suffix = canon.suffix.lower() or "(none)"
        return f"suffix {suffix!r} is not an allowed project-read type"
    return None


class PolicySecurityAgent(SecurityAgent):
    """Concrete :class:`SecurityAgent`. Answers policy questions only."""

    name = "security"

    def __init__(
        self,
        *,
        allow_outbound_network: bool = False,
        require_human_approval: bool = True,
        write_roots: Sequence[Path | str] = (),
        read_roots: Sequence[Path | str] = (),
        read_exclusions: Sequence[Path | str] = (),
        scratch_root: Path | str | None = None,
        project_roots: Sequence[Path | str] = (),
        process_executables: Sequence[Path | str] = (),
        allow_research_retrieval: bool = False,
    ) -> None:
        self._allow_outbound = bool(allow_outbound_network)
        self._require_approval = bool(require_human_approval)
        self._allow_research_retrieval = bool(allow_research_retrieval)
        self._write_roots: tuple[Path, ...] = _unique_canonical(write_roots)
        self._read_roots: tuple[Path, ...] = _unique_canonical(read_roots)
        self._read_exclusions: tuple[Path, ...] = _unique_canonical(read_exclusions)
        self._scratch_root: Path | None = (
            _canonical(scratch_root) if scratch_root is not None
            and str(scratch_root).strip() != ""
            else None
        )
        self._project_roots: tuple[Path, ...] = _unique_canonical(project_roots)
        self._process_executables: tuple[Path, ...] = _unique_canonical(
            process_executables
        )
        self._history: deque[dict[str, str]] = deque(maxlen=_HISTORY_MAX)

    @classmethod
    def from_settings(cls, settings: Any) -> "PolicySecurityAgent":
        """Build from an :class:`~astra.configuration.settings.AstraSettings`."""
        sec = getattr(settings, "security", None)
        allow = bool(getattr(sec, "allow_outbound_network", False)) if sec else False
        require = bool(getattr(sec, "require_human_approval", True)) if sec else True
        roots: list[Path | str] = []
        for attr, field in (
            ("memory", "database_path"),
            ("knowledge", "database_path"),
            ("planning", "database_path"),
            ("memory", "vector_index_path"),
        ):
            section = getattr(settings, attr, None)
            path = getattr(section, field, None) if section is not None else None
            if path is not None:
                roots.append(Path(path).parent)
        logging = getattr(settings, "logging", None)
        directory = getattr(logging, "directory", None) if logging is not None else None
        if directory is not None:
            roots.append(Path(directory))
        from astra.configuration.paths import (
            project_read_exclusion_paths,
            project_root,
        )

        testing = getattr(settings, "testing", None)
        scratch = (
            getattr(testing, "scratch_directory", None) if testing is not None else None
        )
        research = getattr(settings, "research", None)
        allow_research = (
            bool(getattr(research, "allow_external", False)) if research else False
        )
        executables: list[Path | str] = [Path(sys.executable)]
        allowed_apps = (
            getattr(sec, "allowed_application_executables", ()) if sec else ()
        )
        for item in allowed_apps or ():
            if item is not None and str(item).strip():
                executables.append(Path(item))
        return cls(
            allow_outbound_network=allow,
            require_human_approval=require,
            write_roots=roots,
            read_roots=(project_root(),),
            read_exclusions=project_read_exclusion_paths(settings),
            scratch_root=scratch,
            project_roots=(project_root(),),
            process_executables=executables,
            allow_research_retrieval=allow_research,
        )

    @property
    def allow_outbound_network(self) -> bool:
        return self._allow_outbound

    @property
    def require_human_approval(self) -> bool:
        return self._require_approval

    @property
    def checks_total(self) -> int:
        return len(self._history)

    @property
    def last_verdict(self) -> str:
        if not self._history:
            return ""
        return self._history[-1]["verdict"]

    @property
    def last_reason(self) -> str:
        if not self._history:
            return ""
        return self._history[-1]["reason"]

    @property
    def last_kind(self) -> str:
        if not self._history:
            return ""
        return self._history[-1]["kind"]

    def check_network_access(self, url: str) -> PolicyDecision:
        text = (url or "").strip()
        if not text:
            return self._record(
                "network",
                url or "",
                Verdict.DENY,
                "empty URL; fail closed",
            )
        host = _host_from_url(text)
        if _is_loopback_host(host):
            return self._record(
                "network",
                text,
                Verdict.DENY,
                "loopback is network access, not an inference transport "
                "(ADR-0004, ADR-0014 D5)",
            )
        if not self._allow_outbound:
            return self._record(
                "network",
                text,
                Verdict.DENY,
                "outbound network denied by default "
                "(allow_outbound_network=False)",
            )
        return self._record(
            "network",
            text,
            Verdict.REQUIRES_APPROVAL,
            "allow_outbound_network=True does not grant access; "
            "human approval required; no network source exists",
        )

    def check_research_url(self, url: str) -> PolicyDecision:
        """Allow only an approved research retrieval destination.

        Independent of ``check_network_access``. Tools still cannot fetch.
        """
        text = (url or "").strip()
        if not self._allow_research_retrieval:
            return self._record(
                "research_url",
                text,
                Verdict.DENY,
                "external research retrieval is disabled "
                "(research.allow_external=False)",
            )
        if not text:
            return self._record(
                "research_url",
                "",
                Verdict.DENY,
                "empty URL; fail closed",
            )
        verdict, reason = classify_url(text)
        if verdict != "allow":
            return self._record("research_url", text, Verdict.DENY, reason)
        return self._record("research_url", text, Verdict.ALLOW, reason)

    def check_resolved_ip(self, ip: str) -> PolicyDecision:
        """Re-check a DNS result. Fail closed on private or metadata addresses."""
        text = (ip or "").strip()
        if not self._allow_research_retrieval:
            return self._record(
                "research_ip",
                text,
                Verdict.DENY,
                "external research retrieval is disabled "
                "(research.allow_external=False)",
            )
        if not text or ip_is_blocked(text):
            return self._record(
                "research_ip",
                text,
                Verdict.DENY,
                f"blocked address {text or '(empty)'}",
            )
        return self._record(
            "research_ip",
            text,
            Verdict.ALLOW,
            f"public address {text}",
        )

    def check_file_write(self, path: Path) -> PolicyDecision:
        if path is None or str(path).strip() == "":
            return self._record(
                "file_write",
                "",
                Verdict.DENY,
                "empty path; fail closed",
            )
        if not self._write_roots:
            return self._record(
                "file_write",
                str(path),
                Verdict.DENY,
                "no write allowlist configured; fail closed",
            )
        canon = _canonical(path)
        if canon is None:
            return self._record(
                "file_write",
                str(path),
                Verdict.DENY,
                "path could not be canonicalised; fail closed",
            )
        for root in self._write_roots:
            if _is_under(canon, root):
                return self._record(
                    "file_write",
                    str(canon),
                    Verdict.ALLOW,
                    f"path is under allowlisted Astra directory {root}",
                )
        return self._record(
            "file_write",
            str(canon),
            Verdict.DENY,
            "path is outside configured Astra data/log directories",
        )

    def check_file_read(self, path: Path) -> PolicyDecision:
        if path is None or str(path).strip() == "":
            return self._record(
                "file_read",
                "",
                Verdict.DENY,
                "empty path; fail closed",
            )
        if not self._read_roots:
            return self._record(
                "file_read",
                str(path),
                Verdict.DENY,
                "no read allowlist configured; fail closed",
            )
        canon = _canonical(path)
        if canon is None:
            return self._record(
                "file_read",
                str(path),
                Verdict.DENY,
                "path could not be canonicalised; fail closed",
            )
        if not any(_is_under(canon, root) for root in self._read_roots):
            return self._record(
                "file_read",
                str(canon),
                Verdict.DENY,
                "path is outside configured project read root",
            )
        denied = _read_deny_reason(canon, self._read_exclusions)
        if denied is not None:
            return self._record("file_read", str(canon), Verdict.DENY, denied)
        return self._record(
            "file_read",
            str(canon),
            Verdict.ALLOW,
            "path is under authorized project root and not excluded",
        )

    def check_temporary_write(self, path: Path) -> PolicyDecision:
        if path is None or str(path).strip() == "":
            return self._record(
                "temporary_write",
                "",
                Verdict.DENY,
                "empty path; fail closed",
            )
        if self._scratch_root is None:
            return self._record(
                "temporary_write",
                str(path),
                Verdict.DENY,
                "no scratch root configured; fail closed",
            )
        if any(_is_under(self._scratch_root, root) for root in self._project_roots):
            return self._record(
                "temporary_write",
                str(path),
                Verdict.DENY,
                "scratch root is under project_root; fail closed",
            )
        canon = _canonical(path)
        if canon is None:
            return self._record(
                "temporary_write",
                str(path),
                Verdict.DENY,
                "path could not be canonicalised; fail closed",
            )
        if any(_is_under(canon, root) for root in self._project_roots):
            return self._record(
                "temporary_write",
                str(canon),
                Verdict.DENY,
                "path is under project_root; overlay writes forbidden there",
            )
        if _is_under(canon, self._scratch_root):
            return self._record(
                "temporary_write",
                str(canon),
                Verdict.ALLOW,
                f"path is under configured scratch root {self._scratch_root}",
            )
        return self._record(
            "temporary_write",
            str(canon),
            Verdict.DENY,
            "path is outside configured scratch root",
        )

    def check_process_execution(self, spec: ProcessSpec) -> PolicyDecision:
        if spec is None:
            return self._record(
                "process",
                "",
                Verdict.DENY,
                "missing process spec; fail closed",
            )
        argv = tuple(str(part) for part in (getattr(spec, "argv", ()) or ()))
        if not argv or not str(argv[0]).strip():
            return self._record(
                "process",
                "",
                Verdict.DENY,
                "empty argv; fail closed",
            )
        exe_name = Path(argv[0]).name.lower()
        if exe_name in _SHELL_BASENAMES:
            return self._record(
                "process",
                " ".join(argv),
                Verdict.DENY,
                f"shell executable {exe_name!r} is forbidden",
            )
        if any(str(part) == "-c" for part in argv):
            return self._record(
                "process",
                " ".join(argv),
                Verdict.DENY,
                "python -c is forbidden; no eval of generated text",
            )
        env_pairs = tuple(getattr(spec, "env", ()) or ())
        for key, _value in env_pairs:
            if str(key).strip().lower() in _DENIED_ENV_KEYS:
                return self._record(
                    "process",
                    " ".join(argv),
                    Verdict.DENY,
                    f"environment key {key!r} is forbidden in B-022",
                )
        if not self._process_executables:
            return self._record(
                "process",
                " ".join(argv),
                Verdict.DENY,
                "no process executable allowlist; fail closed",
            )
        exe_canon = _canonical(argv[0])
        allowed = {os.path.normcase(str(path)) for path in self._process_executables}
        if exe_canon is None or os.path.normcase(str(exe_canon)) not in allowed:
            return self._record(
                "process",
                " ".join(argv),
                Verdict.DENY,
                "executable is not on the process allowlist",
            )
        cwd_raw = getattr(spec, "cwd", None)
        if cwd_raw is None or str(cwd_raw).strip() == "":
            return self._record(
                "process",
                " ".join(argv),
                Verdict.DENY,
                "empty cwd; fail closed",
            )
        if self._scratch_root is None:
            return self._record(
                "process",
                " ".join(argv),
                Verdict.DENY,
                "no scratch root configured; fail closed",
            )
        if any(_is_under(self._scratch_root, root) for root in self._project_roots):
            return self._record(
                "process",
                " ".join(argv),
                Verdict.DENY,
                "scratch root is under project_root; fail closed",
            )
        cwd_canon = _canonical(cwd_raw)
        if cwd_canon is None:
            return self._record(
                "process",
                " ".join(argv),
                Verdict.DENY,
                "cwd could not be canonicalised; fail closed",
            )
        if any(_is_under(cwd_canon, root) for root in self._project_roots):
            return self._record(
                "process",
                str(cwd_canon),
                Verdict.DENY,
                "cwd is under project_root; process cwd forbidden there",
            )
        if cwd_canon == self._scratch_root:
            return self._record(
                "process",
                str(cwd_canon),
                Verdict.DENY,
                "cwd is the scratch root; process cwd must be a session descendant",
            )
        if not _is_under(cwd_canon, self._scratch_root):
            return self._record(
                "process",
                str(cwd_canon),
                Verdict.DENY,
                "cwd is outside configured scratch root",
            )
        try:
            timeout = float(getattr(spec, "timeout_seconds", 0) or 0)
        except (TypeError, ValueError):
            timeout = 0.0
        if timeout <= 0 or timeout > MAX_PROCESS_TIMEOUT_SECONDS:
            return self._record(
                "process",
                " ".join(argv),
                Verdict.DENY,
                "timeout missing or above cap; fail closed",
            )
        return self._record(
            "process",
            " ".join(argv),
            Verdict.ALLOW,
            "process request matches allowlist, scratch cwd, and timeout cap",
        )

    def check_application_launch(self, executable: str) -> PolicyDecision:
        """ALLOW only allowlisted user applications. DENY shells and admin tools."""
        text = str(executable or "").strip()
        if not text:
            return self._record(
                "process",
                "",
                Verdict.DENY,
                "empty application; fail closed",
            )
        name = Path(text).name.lower()
        if name in _SHELL_BASENAMES:
            return self._record(
                "process",
                text,
                Verdict.DENY,
                f"shell executable {name!r} is forbidden",
            )
        if name in _DENIED_APP_BASENAMES:
            return self._record(
                "process",
                text,
                Verdict.DENY,
                f"privileged executable {name!r} is forbidden",
            )
        resolved = self._resolve_allowed_executable(text)
        if resolved is None:
            return self._record(
                "process",
                text,
                Verdict.DENY,
                "executable is not on the process allowlist",
            )
        return self._record(
            "process",
            str(resolved),
            Verdict.ALLOW,
            "application is on the process allowlist",
        )

    def check_process_inspection(self, query: str) -> PolicyDecision:
        """Read-only process listing. Never authorizes kill or admin actions."""
        text = str(query or "").strip().lower()
        for banned in _INSPECT_BANNED:
            if banned in text:
                return self._record(
                    "process",
                    text,
                    Verdict.DENY,
                    f"process inspection refuses {banned!r}",
                )
        return self._record(
            "process",
            text or "(query)",
            Verdict.ALLOW,
            "read-only process inspection",
        )

    def check_input_control(self, description: str) -> PolicyDecision:
        """Mouse/keyboard. Deny unsafe requests. Else require approval.

        Safe-mode ALLOW is not implemented. Never bypasses this check.
        """
        text = str(description or "").strip().lower()
        if not text:
            return self._record(
                "input",
                "",
                Verdict.DENY,
                "empty input-control description; fail closed",
            )
        for banned in _INPUT_BANNED:
            if banned in text:
                return self._record(
                    "input",
                    text,
                    Verdict.DENY,
                    f"input control refuses {banned!r}",
                )
        return self._record(
            "input",
            text,
            Verdict.REQUIRES_APPROVAL,
            "mouse and keyboard actions require human approval",
        )

    def check_environment_perception(self, kind: str) -> PolicyDecision:
        """Read-only local screen/window perception. No upload or control."""
        text = str(kind or "").strip().lower()
        for banned in _ENV_BANNED:
            if banned in text:
                return self._record(
                    "environment",
                    text,
                    Verdict.DENY,
                    f"environment perception refuses {banned!r}",
                )
        if text in _ENV_ALLOWED:
            return self._record(
                "environment",
                text,
                Verdict.ALLOW,
                "read-only local environment perception",
            )
        return self._record(
            "environment",
            text or "(kind)",
            Verdict.DENY,
            "unknown environment perception kind",
        )

    def _resolve_allowed_executable(self, executable: str) -> Path | None:
        if not self._process_executables:
            return None
        allowed = {os.path.normcase(str(path)): path for path in self._process_executables}
        looks_like_path = any(sep in executable for sep in ("/", "\\")) or bool(
            Path(executable).suffix
        )
        if looks_like_path:
            canon = _canonical(executable)
            if canon is None:
                return None
            return allowed.get(os.path.normcase(str(canon)))
        needle = Path(executable).name.lower()
        stem = needle.removesuffix(".exe")
        for path in self._process_executables:
            if path.name.lower() == needle or path.stem.lower() == stem:
                return path
        return None

    def check_permanent_change(self, description: str) -> PolicyDecision:
        text = (description or "").strip()
        if not text:
            return self._record(
                "permanent_change",
                "",
                Verdict.DENY,
                "empty change description; fail closed",
            )
        if not self._require_approval:
            return self._record(
                "permanent_change",
                text,
                Verdict.DENY,
                "require_human_approval missing; fail closed; never ALLOW",
            )
        return self._record(
            "permanent_change",
            text,
            Verdict.REQUIRES_APPROVAL,
            "permanent changes require human approval (ADR-0014 D7)",
        )

    def run(self, request: AgentRequest) -> AgentResult:
        last = self._history[-1] if self._history else None
        summary = (
            f"security policy: outbound={self._allow_outbound} "
            f"human_approval={self._require_approval} "
            f"checks={len(self._history)}"
        )
        if last is not None:
            summary += f"; last={last['verdict']}: {last['reason']}"
        else:
            summary += "; no policy checks yet"
        return AgentResult(
            agent=self.name,
            success=True,
            summary=summary,
            details={
                "allow_outbound_network": self._allow_outbound,
                "require_human_approval": self._require_approval,
                "checks_total": len(self._history),
                "write_root_count": len(self._write_roots),
                "read_root_count": len(self._read_roots),
                "last_verdict": last["verdict"] if last else "",
                "last_reason": last["reason"] if last else "",
                "last_kind": last["kind"] if last else "",
                "executed": False,
            },
            evidence=(),
        )

    def _record(
        self, kind: str, target: str, verdict: Verdict, reason: str
    ) -> PolicyDecision:
        decision = PolicyDecision(verdict=verdict, reason=reason)
        self._history.append(
            {
                "kind": kind,
                "target": target,
                "verdict": verdict.value,
                "reason": reason,
            }
        )
        _LOGGER.debug(
            "security %s %s -> %s (%s)", kind, target, verdict.value, reason
        )
        return decision


RESEARCH_NETWORK_SENTINEL = _RESEARCH_SENTINEL_URL

__all__ = ["PolicySecurityAgent", "RESEARCH_NETWORK_SENTINEL"]
