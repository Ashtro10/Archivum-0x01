"""Copied-subset isolated test workspace (B-023, ADR-0022).

Not a TestingAgent. Does not run tests, spawn processes, or apply changes.
Copies an explicit allowlist under scratch after ``check_temporary_write`` ALLOW.
``project_root`` is never mutated. Not an OS sandbox.
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
from astra.testing.workspace import (
    MAX_FILE_BYTES,
    MAX_FILES,
    MaterializeResult,
    _canonical,
    _is_under,
    is_non_relative_path,
    secret_shaped_reason,
)

_LOGGER = get_logger(__name__)

COPY_DIR_PREFIXES: tuple[str, ...] = ("astra", "tests", "ui")
COPY_FILES: tuple[str, ...] = (
    "CONSTITUTION.md",
    "pyproject.toml",
    "benchmarks/results/embedding_verification.json",
)
ALLOWED_SUFFIXES = frozenset({".py", ".md", ".toml", ".txt", ".json", ".csv"})
DENIED_DIR_NAMES = frozenset(
    {
        ".git",
        ".ssh",
        "__pycache__",
        ".venv",
        "venv",
        "data",
        "logs",
        "models",
        ".pytest_cache",
        ".mypy_cache",
        ".ruff_cache",
        "htmlcov",
        "build",
        "dist",
    }
)
DENIED_NAMES = frozenset(
    {
        "credentials.json",
        "secrets.json",
        "id_rsa",
        "id_ed25519",
        ".coverage",
    }
)
DENIED_SUFFIXES = frozenset(
    {".pyc", ".pyo", ".gguf", ".db", ".sqlite3", ".faiss", ".coverage"}
)


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


def _denied_relative(rel: Path) -> bool:
    denied_dirs = {os.path.normcase(name) for name in DENIED_DIR_NAMES}
    denied_names = {os.path.normcase(name) for name in DENIED_NAMES}
    for part in rel.parts:
        if os.path.normcase(part) in denied_dirs:
            return True
        if part.startswith(".") and part not in (".", ".."):
            return True
        if part.lower().endswith(".egg-info"):
            return True
    name = rel.name
    if os.path.normcase(name) in denied_names:
        return True
    if name.lower().startswith(".env"):
        return True
    suffix = rel.suffix.lower()
    if suffix in DENIED_SUFFIXES:
        return True
    if suffix not in ALLOWED_SUFFIXES:
        return True
    return False


@dataclass(frozen=True, slots=True)
class SubsetResult:
    """Outcome of a copied-subset write. Ordinary failure stays in this object."""

    success: bool
    session_id: str = ""
    subset_root: Path | None = None
    copied: tuple[Path, ...] = ()
    reason: str = ""


class CopiedSubsetWorkspace:
    """Copy an allowlisted subset into scratch, then overlay proposals onto it.

    Not an Agent. Not a Tool. Does not spawn. Does not replace ProposalWorkspace.
    """

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

    def copy(self) -> SubsetResult:
        """Copy allowlisted files under scratch. Never mutates ``project_root``."""
        project = _canonical(self._project_root)
        scratch = _canonical(self._scratch_root) if self._scratch_root is not None else None
        if project is None or scratch is None:
            return SubsetResult(success=False, reason="project or scratch root unresolvable")
        if scratch == project or _is_under(scratch, project):
            return SubsetResult(
                success=False, reason="scratch is under project_root; fail closed"
            )

        astra_dir = self._project_root / "astra"
        if _is_link(astra_dir):
            return SubsetResult(success=False, reason="astra/ is a symlink; fail closed")
        if not astra_dir.is_dir():
            return SubsetResult(success=False, reason="astra/ missing from project")

        sources, collect_reason = self._collect_sources(project)
        if collect_reason:
            return SubsetResult(success=False, reason=collect_reason)
        if not sources:
            return SubsetResult(success=False, reason="copy allowlist matched no files")

        snapshots: dict[Path, bytes] = {}
        try:
            for source in sources:
                snapshots[source] = source.read_bytes()
        except OSError as exc:
            return SubsetResult(success=False, reason=f"could not snapshot source: {exc}")

        session_id = uuid.uuid4().hex
        session = scratch / session_id
        session_canon = _canonical(session)
        if session_canon is None or not _is_under(session_canon, scratch):
            return SubsetResult(success=False, reason="session dir escaped scratch")
        if _is_under(session_canon, project):
            return SubsetResult(
                success=False, reason="session dest is under project_root; fail closed"
            )

        planned: list[tuple[Path, Path, Path]] = []
        for source in sources:
            try:
                rel = source.relative_to(project)
            except ValueError:
                return SubsetResult(
                    success=False, reason=f"source {source} is not under project_root"
                )
            if ".." in rel.parts or not rel.parts:
                return SubsetResult(success=False, reason=f"relative path escaped: {rel}")
            dest = session / rel
            dest_canon = _canonical(dest)
            if dest_canon is None or not _is_under(dest_canon, session_canon):
                return SubsetResult(
                    success=False, reason=f"copy dest escaped session for {rel}"
                )
            if dest_canon == project or _is_under(dest_canon, project):
                return SubsetResult(
                    success=False,
                    reason=f"copy dest {rel} is under project_root; fail closed",
                )
            check = getattr(self._security, "check_temporary_write", None)
            if check is None:
                return SubsetResult(
                    success=False, reason="security has no check_temporary_write"
                )
            decision = check(dest)
            verdict = getattr(decision, "verdict", None)
            if verdict is not Verdict.ALLOW:
                reason = getattr(decision, "reason", "temporary write denied")
                return SubsetResult(success=False, reason=str(reason))
            planned.append((source, rel, dest))

        copied: list[Path] = []
        try:
            session.mkdir(parents=True, exist_ok=True)
            for source, _rel, dest in planned:
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(source.read_bytes())
                copied.append(dest)
        except Exception as exc:  # noqa: BLE001
            self._cleanup(session)
            return SubsetResult(success=False, reason=f"copy write failed: {exc}")

        for source, before in snapshots.items():
            try:
                after = source.read_bytes()
            except OSError as exc:
                self._cleanup(session)
                return SubsetResult(
                    success=False, reason=f"could not re-read {source}: {exc}"
                )
            if after != before:
                self._cleanup(session)
                return SubsetResult(
                    success=False,
                    reason=f"project_root {source} changed; copy discarded",
                )

        self._sessions[session_id] = session
        _LOGGER.debug(
            "copied-subset session %s wrote %s files", session_id, len(copied)
        )
        return SubsetResult(
            success=True,
            session_id=session_id,
            subset_root=session,
            copied=tuple(copied),
            reason="subset copied",
        )

    def create_empty_session(self) -> SubsetResult:
        """Create a scratch session with no project copy. Isolated python cwd."""
        project = _canonical(self._project_root)
        scratch = _canonical(self._scratch_root) if self._scratch_root is not None else None
        if project is None or scratch is None:
            return SubsetResult(success=False, reason="project or scratch root unresolvable")
        if scratch == project or _is_under(scratch, project):
            return SubsetResult(
                success=False, reason="scratch is under project_root; fail closed"
            )
        session_id = uuid.uuid4().hex
        session = scratch / session_id
        session_canon = _canonical(session)
        if session_canon is None or not _is_under(session_canon, scratch):
            return SubsetResult(success=False, reason="session dir escaped scratch")
        if _is_under(session_canon, project):
            return SubsetResult(
                success=False, reason="session dest is under project_root; fail closed"
            )
        probe = session / "agent_run.py"
        check = getattr(self._security, "check_temporary_write", None)
        if check is None:
            return SubsetResult(
                success=False, reason="security has no check_temporary_write"
            )
        decision = check(probe)
        verdict = getattr(decision, "verdict", None)
        if verdict is not Verdict.ALLOW:
            reason = getattr(decision, "reason", "temporary write denied")
            return SubsetResult(success=False, reason=str(reason))
        try:
            session.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            return SubsetResult(success=False, reason=f"session mkdir failed: {exc}")
        self._sessions[session_id] = session
        return SubsetResult(
            success=True,
            session_id=session_id,
            subset_root=session,
            copied=(),
            reason="empty scratch session",
        )

    def overlay(self, session_id: str, proposal: ChangeProposal) -> MaterializeResult:
        """Write proposed files into an existing subset session. Caps match B-021."""
        session = self._sessions.get(session_id)
        if session is None:
            return MaterializeResult(
                success=False, reason=f"unknown subset session {session_id}"
            )
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
        session_canon = _canonical(session)
        if project is None or scratch is None or session_canon is None:
            return MaterializeResult(
                success=False, reason="project, scratch, or session unresolvable"
            )
        if not _is_under(session_canon, scratch):
            return MaterializeResult(success=False, reason="session escaped scratch")

        planned: list[tuple[Path, str, bytes | None]] = []
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

        extra_markers: list[tuple[Path, bytes]] = []
        for marker_name in ("CONSTITUTION.md", "astra/__init__.py"):
            marker = project / marker_name
            if marker.is_file():
                try:
                    extra_markers.append((marker, marker.read_bytes()))
                except OSError as exc:
                    return MaterializeResult(
                        success=False, reason=f"could not snapshot {marker_name}: {exc}"
                    )

        dests: list[Path] = []
        for rel, _content, _snap in planned:
            dest = session / rel
            dest_canon = _canonical(dest)
            if dest_canon is None or not _is_under(dest_canon, session_canon):
                return MaterializeResult(
                    success=False, reason=f"overlay dest escaped session for {rel}"
                )
            if dest_canon == project or _is_under(dest_canon, project):
                return MaterializeResult(
                    success=False,
                    reason=f"overlay dest {rel} is under project_root; fail closed",
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
            for (rel, content, _snap), dest in zip(planned, dests, strict=True):
                if _is_link(dest) or _is_link(dest.parent):
                    return MaterializeResult(
                        success=False,
                        reason=f"overlay dest {rel} is a symlink or junction; refuse",
                    )
                dest.parent.mkdir(parents=True, exist_ok=True)
                if _is_link(dest) or _is_link(dest.parent):
                    return MaterializeResult(
                        success=False,
                        reason=f"overlay dest {rel} swapped to link; refuse",
                    )
                dest.write_text(content, encoding="utf-8", newline="\n")
                written.append(dest)
        except Exception as exc:  # noqa: BLE001
            for dest in written:
                try:
                    dest.unlink(missing_ok=True)
                except OSError:
                    pass
            return MaterializeResult(
                success=False, reason=f"overlay write failed: {exc}"
            )

        for rel, _content, snapshot in planned:
            source = project / rel
            if snapshot is None:
                if source.exists():
                    return MaterializeResult(
                        success=False,
                        reason=f"project_root gained {rel}; overlay not trusted",
                    )
            else:
                try:
                    after = source.read_bytes()
                except OSError as exc:
                    return MaterializeResult(
                        success=False, reason=f"could not re-read {rel}: {exc}"
                    )
                if after != snapshot:
                    return MaterializeResult(
                        success=False,
                        reason=f"project_root {rel} changed; overlay not trusted",
                    )
        for marker, before in extra_markers:
            try:
                after = marker.read_bytes()
            except OSError as exc:
                return MaterializeResult(
                    success=False, reason=f"could not re-read {marker}: {exc}"
                )
            if after != before:
                return MaterializeResult(
                    success=False,
                    reason=f"project_root {marker} changed; overlay not trusted",
                )

        _LOGGER.debug(
            "copied-subset overlay session %s wrote %s files", session_id, len(written)
        )
        return MaterializeResult(
            success=True,
            session_id=session_id,
            overlay_root=session,
            written=tuple(written),
            reason="overlay written into subset",
        )

    def session_root(self, session_id: str) -> Path | None:
        """Return the session directory, or None if unknown."""
        return self._sessions.get(session_id)

    def discard(self, session_id: str) -> bool:
        """Remove one session directory. Unknown id is a no-op False."""
        session = self._sessions.pop(session_id, None)
        if session is None:
            return False
        self._cleanup(session)
        return True

    def discard_all(self) -> None:
        """Remove every session directory this workspace created."""
        for session_id in list(self._sessions):
            self.discard(session_id)

    def _collect_sources(self, project: Path) -> tuple[list[Path], str]:
        files: list[Path] = []
        seen: set[str] = set()
        for prefix in COPY_DIR_PREFIXES:
            root = project / prefix
            if not root.exists():
                continue
            if _is_link(root):
                return [], f"{prefix}/ is a symlink; fail closed"
            if not root.is_dir():
                return [], f"{prefix}/ is not a directory"
            for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
                for name in list(dirnames):
                    child = Path(dirpath) / name
                    if _is_link(child):
                        return [], f"symlink directory {child}; fail closed"
                    if name in DENIED_DIR_NAMES or name.startswith(".") or name.endswith(
                        ".egg-info"
                    ):
                        dirnames.remove(name)
                for name in filenames:
                    path = Path(dirpath) / name
                    if _is_link(path):
                        return [], f"symlink file {path}; fail closed"
                    rel = path.relative_to(project)
                    if _denied_relative(rel):
                        continue
                    key = os.path.normcase(str(path.resolve(strict=False)))
                    if key in seen:
                        continue
                    seen.add(key)
                    files.append(path)
        for rel_s in COPY_FILES:
            path = project / rel_s
            if not path.exists():
                continue
            if _is_link(path):
                return [], f"symlink file {path}; fail closed"
            if not path.is_file():
                continue
            rel = Path(rel_s)
            if _denied_relative(rel):
                continue
            key = os.path.normcase(str(path.resolve(strict=False)))
            if key in seen:
                continue
            seen.add(key)
            files.append(path)
        return files, ""

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
    def _cleanup(session: Path) -> None:
        try:
            shutil.rmtree(session, ignore_errors=True)
        except Exception:  # noqa: BLE001
            _LOGGER.warning("could not remove subset session %s", session)


__all__ = [
    "CopiedSubsetWorkspace",
    "SubsetResult",
    "COPY_DIR_PREFIXES",
    "COPY_FILES",
]
