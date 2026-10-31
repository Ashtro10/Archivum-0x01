"""Isolated proposal overlay (B-021, ADR-0020).

Not a TestingAgent. Does not run tests, spawn processes, or apply changes.
Writes proposed file bodies under a Security-gated scratch root only after
``check_temporary_write`` ALLOW. ``project_root`` is never mutated.
"""

from __future__ import annotations

import os
import shutil
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from astra.coding.contracts import ChangeProposal
from astra.logging import get_logger
from astra.security.contracts import Verdict

_LOGGER = get_logger(__name__)

MAX_FILES = 3
MAX_FILE_BYTES = 65536

SECRET_SEGMENTS = frozenset(
    {
        ".git",
        ".ssh",
        "data",
        "logs",
        "models",
        "scratch",
        "__pycache__",
        ".venv",
        "venv",
    }
)
SECRET_NAMES = frozenset(
    {
        "credentials.json",
        "secrets.json",
        "id_rsa",
        "id_ed25519",
    }
)


def is_non_relative_path(path: Path | str) -> bool:
    """True for absolute, UNC, extended, or drive-letter proposal paths."""
    text = str(path).strip()
    if not text:
        return False
    raw = Path(path)
    if raw.is_absolute():
        return True
    if text.startswith("\\\\") or text.startswith("//"):
        return True
    if text.startswith("\\\\?\\") or text.startswith("//?/"):
        return True
    if len(text) >= 2 and text[1] == ":" and text[0].isalpha():
        return True
    return False


def secret_shaped_reason(rel: Path) -> str | None:
    """Reject secret-shaped or protected relative paths. Policy only."""
    denied_segs = {os.path.normcase(part) for part in SECRET_SEGMENTS}
    denied_names = {os.path.normcase(name) for name in SECRET_NAMES}
    for part in rel.parts:
        if os.path.normcase(part) in denied_segs:
            return f"path segment {part!r} is forbidden for deployment"
    name = rel.name
    lower = name.lower()
    if lower.startswith(".env"):
        return "dotenv files are forbidden for deployment"
    if os.path.normcase(name) in denied_names:
        return f"basename {name!r} is forbidden for deployment"
    return None


def _is_link(path: Path) -> bool:
    try:
        if path.is_symlink():
            return True
        is_junction = getattr(path, "is_junction", None)
        if callable(is_junction) and is_junction():
            return True
    except OSError:
        return True
    return False


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


@dataclass(frozen=True, slots=True)
class MaterializeResult:
    """Outcome of an overlay write. Ordinary failure stays in this object."""

    success: bool
    session_id: str = ""
    overlay_root: Path | None = None
    written: tuple[Path, ...] = ()
    reason: str = ""


class ProposalWorkspace:
    """Materialize and discard proposal overlays. Not an Agent."""

    def __init__(
        self,
        *,
        security: Any,
        scratch_root: Path | str | None,
        project_root: Path | str,
    ) -> None:
        self._security = security
        self._scratch_root = Path(scratch_root) if scratch_root is not None else None
        self._project_root = Path(project_root)
        self._sessions: dict[str, Path] = {}

    def materialize(self, proposal: ChangeProposal) -> MaterializeResult:
        """Write proposed files under scratch. Never mutates ``project_root``."""
        changes = tuple(getattr(proposal, "changes", ()) or ())
        if not changes:
            return MaterializeResult(success=False, reason="empty proposal; no writes")
        if len(changes) > MAX_FILES:
            return MaterializeResult(
                success=False,
                reason=f"proposal has {len(changes)} files; cap is {MAX_FILES}",
            )

        project = _canonical(self._project_root)
        scratch = _canonical(self._scratch_root) if self._scratch_root is not None else None
        if project is None or scratch is None:
            return MaterializeResult(
                success=False, reason="project or scratch root unresolvable"
            )

        planned: list[tuple[Path, str, Path | None]] = []
        seen: set[str] = set()
        for change in changes:
            rel = self._relative_key(Path(change.path), project)
            if rel is None:
                return MaterializeResult(
                    success=False,
                    reason=f"path {change.path!s} is not a relative project file",
                )
            key = os.path.normcase(str(rel))
            if key in seen:
                return MaterializeResult(
                    success=False, reason=f"duplicate overlay path {rel}"
                )
            seen.add(key)
            denied = secret_shaped_reason(rel)
            if denied:
                return MaterializeResult(success=False, reason=denied)
            content = change.new_content if isinstance(change.new_content, str) else ""
            encoded = content.encode("utf-8")
            if len(encoded) > MAX_FILE_BYTES:
                return MaterializeResult(
                    success=False,
                    reason=f"{rel} exceeds {MAX_FILE_BYTES} bytes; fail closed",
                )
            source = project / rel
            snapshot = source.read_bytes() if source.is_file() else None
            planned.append((rel, content, snapshot))

        session_id = uuid.uuid4().hex
        overlay = scratch / session_id
        dests: list[Path] = []
        for rel, _content, _snap in planned:
            dest = overlay / rel
            dest_canon = _canonical(dest)
            if dest_canon is None or not _is_under(dest_canon, _canonical(overlay) or overlay):
                return MaterializeResult(
                    success=False, reason=f"overlay dest escaped scratch for {rel}"
                )
            check = getattr(self._security, "check_temporary_write", None)
            if check is None:
                return MaterializeResult(
                    success=False, reason="security has no check_temporary_write"
                )
            decision = check(dest)
            verdict = getattr(decision, "verdict", None)
            if verdict is not Verdict.ALLOW:
                reason = getattr(decision, "reason", "temporary write denied")
                return MaterializeResult(success=False, reason=str(reason))
            dests.append(dest)

        written: list[Path] = []
        try:
            overlay.mkdir(parents=True, exist_ok=True)
            for (rel, content, _snap), dest in zip(planned, dests, strict=True):
                if _is_link(dest) or _is_link(dest.parent):
                    self._cleanup(overlay)
                    return MaterializeResult(
                        success=False,
                        reason=f"overlay dest {rel} is a symlink or junction; refuse",
                    )
                dest.parent.mkdir(parents=True, exist_ok=True)
                if _is_link(dest) or _is_link(dest.parent):
                    self._cleanup(overlay)
                    return MaterializeResult(
                        success=False,
                        reason=f"overlay dest {rel} swapped to link; refuse",
                    )
                dest.write_text(content, encoding="utf-8", newline="\n")
                written.append(dest)
        except Exception as exc:  # noqa: BLE001
            self._cleanup(overlay)
            return MaterializeResult(
                success=False, reason=f"overlay write failed: {exc}"
            )

        for rel, _content, snapshot in planned:
            source = project / rel
            if snapshot is None:
                if source.exists():
                    self._cleanup(overlay)
                    return MaterializeResult(
                        success=False,
                        reason=f"project_root gained {rel}; overlay discarded",
                    )
            else:
                try:
                    after = source.read_bytes()
                except OSError as exc:
                    self._cleanup(overlay)
                    return MaterializeResult(
                        success=False, reason=f"could not re-read {rel}: {exc}"
                    )
                if after != snapshot:
                    self._cleanup(overlay)
                    return MaterializeResult(
                        success=False,
                        reason=f"project_root {rel} changed; overlay discarded",
                    )

        self._sessions[session_id] = overlay
        _LOGGER.debug("proposal overlay session %s wrote %s files", session_id, len(written))
        return MaterializeResult(
            success=True,
            session_id=session_id,
            overlay_root=overlay,
            written=tuple(written),
            reason="overlay materialized",
        )

    def discard(self, session_id: str) -> bool:
        """Remove one session directory. Unknown id is a no-op False."""
        overlay = self._sessions.pop(session_id, None)
        if overlay is None:
            return False
        self._cleanup(overlay)
        return True

    def discard_all(self) -> None:
        """Remove every session directory this workspace created."""
        for session_id in list(self._sessions):
            self.discard(session_id)

    def _relative_key(self, path: Path, project: Path) -> Path | None:
        raw = Path(path)
        if str(raw).strip() == "" or raw.name in ("", ".", ".."):
            return None
        if is_non_relative_path(raw) or ".." in raw.parts:
            return None
        candidate = _canonical(project / raw)
        if candidate is None:
            return None
        try:
            rel = candidate.relative_to(project)
        except ValueError:
            return None
        if not rel.parts or rel.parts[0] == "..":
            return None
        return rel

    @staticmethod
    def _cleanup(overlay: Path) -> None:
        try:
            shutil.rmtree(overlay, ignore_errors=True)
        except Exception:  # noqa: BLE001
            _LOGGER.warning("could not remove overlay %s", overlay)


__all__ = [
    "MAX_FILE_BYTES",
    "MAX_FILES",
    "MaterializeResult",
    "ProposalWorkspace",
    "SECRET_NAMES",
    "SECRET_SEGMENTS",
    "is_non_relative_path",
    "secret_shaped_reason",
]
