"""Sole project write/delete boundary for Deployment (B-027 / B-028).

Call only after proposal, tests, evaluation, approval, security, and path
checks have all passed. Not a Tool. Does not consult Security again.
Re-checks symlink/junction and project containment immediately before each
write. That is policy TOCTOU reduction, not an OS-atomic guarantee.
"""

from __future__ import annotations

from pathlib import Path

from astra.deployment.paths import canonical, is_link, is_under
from astra.logging import get_logger

_LOGGER = get_logger(__name__)


def _dest_denied(dest: Path, project: Path) -> str:
    project_canon = canonical(project)
    if project_canon is None:
        return "project_root unresolvable"
    if is_link(dest):
        return "destination is a symlink or junction; refuse"
    dest_canon = canonical(dest)
    if dest_canon is None or not is_under(dest_canon, project_canon):
        return "destination escaped project_root"
    parent = dest.parent
    while is_under(parent, project_canon) and parent != project_canon:
        if is_link(parent):
            return "destination parent is a symlink or junction; refuse"
        nxt = parent.parent
        if nxt == parent:
            break
        parent = nxt
    return ""


def commit_files(
    planned: tuple[tuple[Path, str], ...],
    *,
    project: Path | None = None,
) -> tuple[tuple[Path, ...], str]:
    """Write every ``(destination, text)`` pair.

    Returns ``(written, error)``. ``error`` is empty only when every write
    completed. Partial writes are reported honestly; this function does not
    roll back.
    """
    written: list[Path] = []
    if not planned:
        return (), "no files to commit"
    if project is None:
        return (), "project_root required for commit; fail closed"
    try:
        for dest, content in planned:
            denied = _dest_denied(dest, project)
            if denied:
                return tuple(written), denied
            dest.parent.mkdir(parents=True, exist_ok=True)
            denied = _dest_denied(dest, project)
            if denied:
                return tuple(written), denied
            dest.write_text(content, encoding="utf-8", newline="\n")
            written.append(dest)
            _LOGGER.debug("deployed %s", dest)
    except Exception as exc:  # noqa: BLE001
        return tuple(written), f"write failed after {len(written)} file(s): {exc}"
    return tuple(written), ""


def restore_entries(
    planned: tuple[tuple[Path, bytes | None], ...],
    *,
    project: Path | None = None,
) -> tuple[tuple[Path, ...], tuple[Path, ...], str]:
    """Restore bytes or delete. ``None`` payload means the file must go.

    Returns ``(restored, deleted, error)``. Partial work is reported; no
    automatic undo of the partial work.
    """
    restored: list[Path] = []
    deleted: list[Path] = []
    if not planned:
        return (), (), "no files to restore"
    if project is None:
        return (), (), "project_root required for restore; fail closed"
    try:
        for dest, payload in planned:
            denied = _dest_denied(dest, project)
            if denied:
                return tuple(restored), tuple(deleted), denied
            if payload is None:
                if dest.is_file() or dest.is_symlink():
                    dest.unlink()
                deleted.append(dest)
                _LOGGER.debug("rollback deleted %s", dest)
            else:
                dest.parent.mkdir(parents=True, exist_ok=True)
                denied = _dest_denied(dest, project)
                if denied:
                    return tuple(restored), tuple(deleted), denied
                dest.write_bytes(payload)
                restored.append(dest)
                _LOGGER.debug("rollback restored %s", dest)
    except Exception as exc:  # noqa: BLE001
        return (
            tuple(restored),
            tuple(deleted),
            f"rollback write failed after {len(restored) + len(deleted)} file(s): {exc}",
        )
    return tuple(restored), tuple(deleted), ""


__all__ = ["commit_files", "restore_entries"]
