"""Local VersionManager (B-028, ADR-0027).

Controller-owned. Not an Agent. Not a Tool. Stores immutable pre-deployment
snapshots under scratch after ``check_temporary_write`` ALLOW. Not a new
permanent-write root. Not Git.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence

from astra.deployment.apply import restore_entries
from astra.deployment.contracts import (
    ApprovalRecord,
    RollbackResult,
    STATUS_APPROVAL_INVALID,
    STATUS_APPROVAL_MISSING,
    STATUS_CORRUPTED,
    STATUS_INVALID_PROPOSAL,
    STATUS_ROLLBACK_ERROR,
    STATUS_ROLLED_BACK,
    STATUS_SECURITY_DENIED,
    STATUS_UNKNOWN_VERSION,
)
from astra.deployment.fingerprint import rollback_fingerprint
from astra.deployment.paths import canonical, is_link, is_under, resolve_dest
from astra.deployment.versioning import VersionFileSnapshot, VersionManager, VersionRecord
from astra.logging import get_logger
from astra.security.contracts import Verdict

_LOGGER = get_logger(__name__)

_VERSION_ID = re.compile(r"^d-[0-9a-f]{16}-\d{4,}$")
_INDEX_NAME = "index.json"
_MANIFEST_NAME = "manifest.json"
_FILES_DIR = "files"


def capture_preimage(
    planned: tuple[tuple[Path, str], ...], project: Path
) -> tuple[tuple[VersionFileSnapshot, ...], str]:
    """Read bytes that exist immediately before deploy. Does not write."""
    snaps: list[VersionFileSnapshot] = []
    for dest, _content in planned:
        try:
            rel = dest.relative_to(project).as_posix()
        except ValueError:
            return (), f"path {dest} is outside project_root"
        if is_link(dest):
            return (), f"{rel} is a symlink or junction; refuse"
        if dest.exists() and not dest.is_file():
            return (), f"{rel} exists and is not a file; refuse"
        if dest.is_file():
            data = dest.read_bytes()
            if is_link(dest):
                return (), f"{rel} became a symlink or junction; refuse"
            snaps.append(
                VersionFileSnapshot(
                    relative_path=rel,
                    existed=True,
                    sha256=hashlib.sha256(data).hexdigest(),
                    original_bytes=data,
                )
            )
        else:
            snaps.append(
                VersionFileSnapshot(
                    relative_path=rel,
                    existed=False,
                    sha256="",
                    original_bytes=None,
                )
            )
    if not snaps:
        return (), "no files to snapshot"
    return tuple(snaps), ""


def snapshot_integrity(version_id: str, files: Sequence[VersionFileSnapshot]) -> str:
    hasher = hashlib.sha256()
    hasher.update(b"astra-version-v1\n")
    hasher.update(str(version_id or "").encode("utf-8"))
    hasher.update(b"\n")
    ordered = sorted(files, key=lambda item: item.relative_path.replace("\\", "/"))
    for item in ordered:
        rel = str(item.relative_path or "").replace("\\", "/")
        hasher.update(rel.encode("utf-8"))
        hasher.update(b"\0")
        hasher.update(b"1" if item.existed else b"0")
        hasher.update(b"\0")
        hasher.update(str(item.sha256 or "").encode("utf-8"))
        hasher.update(b"\n")
    return hasher.hexdigest()


class LocalVersionManager(VersionManager):
    """Append-only version index plus per-version pre-image blobs under scratch."""

    def __init__(
        self,
        *,
        security: Any,
        project_root: Path | str,
        store_root: Path | str | None,
    ) -> None:
        self._security = security
        self._project_root = Path(project_root)
        self._store_root = Path(store_root) if store_root is not None else None

    def current(self) -> VersionRecord | None:
        items = self.history(limit=1)
        return items[0] if items else None

    def record(self, version: VersionRecord) -> None:
        if version is None or not str(getattr(version, "version", "") or "").strip():
            raise ValueError("VersionRecord missing version id")
        records, err = self._load_index()
        if err:
            raise RuntimeError(err)
        ident = str(version.version)
        if any(item.version == ident for item in records):
            raise ValueError(f"version {ident} already recorded")
        err = self._write_index((*records, version))
        if err:
            raise RuntimeError(err)

    def history(self, limit: int = 50) -> Sequence[VersionRecord]:
        records, err = self._load_index()
        if err:
            _LOGGER.debug("version history unreadable: %s", err)
            return ()
        newest = tuple(reversed(records))
        if limit <= 0:
            return newest
        return newest[:limit]

    def get(self, version: str) -> VersionRecord | None:
        ident = str(version or "").strip()
        if not ident:
            return None
        records, err = self._load_index()
        if err:
            return None
        for item in records:
            if item.version == ident:
                return item
        return None

    def save_preimage(
        self,
        *,
        evidence_fingerprint: str,
        summary: str,
        approved_by: str,
        evidence: tuple[str, ...],
        files: tuple[VersionFileSnapshot, ...],
        deployed_at: datetime | None = None,
    ) -> tuple[str, str]:
        """Persist an immutable pre-image. Returns ``(version_id, error)``."""
        if not files:
            return "", "no snapshot files"
        store = self._store()
        if store is None:
            return "", "version store root missing"
        records, err = self._load_index()
        if err:
            return "", err
        digest = str(evidence_fingerprint or "").strip()
        if len(digest) < 16:
            return "", "evidence fingerprint too short"
        version_id = f"d-{digest[:16]}-{len(records) + 1:04d}"
        if not _VERSION_ID.match(version_id):
            return "", "version id rejected"
        if any(item.version == version_id for item in records):
            return "", f"version {version_id} already recorded"
        integrity = snapshot_integrity(version_id, files)
        version_dir = store / version_id
        if version_dir.exists():
            return "", f"version directory {version_id} already exists"
        err = self._persist_snapshot(version_dir, version_id, files, integrity)
        if err:
            return "", err
        record = VersionRecord(
            version=version_id,
            deployed_at=deployed_at or datetime.now(timezone.utc),
            summary=str(summary or ""),
            approved_by=str(approved_by or ""),
            evidence=tuple(evidence or ()),
            file_count=len(files),
            integrity=integrity,
        )
        err = self._write_index((*records, record))
        if err:
            return "", err
        return version_id, ""

    def rollback(self, version: str, approval: ApprovalRecord) -> RollbackResult:
        if approval is None:
            return _rb(STATUS_APPROVAL_MISSING, "ApprovalRecord missing")
        if not isinstance(approval, ApprovalRecord):
            return _rb(STATUS_APPROVAL_INVALID, "ApprovalRecord malformed")
        approved_by = str(getattr(approval, "approved_by", "") or "").strip()
        subject = str(getattr(approval, "subject", "") or "").strip()
        if not approved_by or not subject:
            return _rb(STATUS_APPROVAL_INVALID, "ApprovalRecord incomplete")
        ident = str(version or "").strip()
        if not ident:
            return _rb(STATUS_UNKNOWN_VERSION, "version id missing")
        expected = rollback_fingerprint(ident)
        if subject != expected:
            return _rb(
                STATUS_APPROVAL_INVALID,
                "ApprovalRecord does not match this version",
            )
        record = self.get(ident)
        if record is None:
            return _rb(STATUS_UNKNOWN_VERSION, f"unknown version {ident}")
        files, snap_err = self._load_snapshot(ident, record)
        if snap_err:
            return _rb(STATUS_CORRUPTED, snap_err)
        project = canonical(self._project_root)
        if project is None:
            return _rb(STATUS_INVALID_PROPOSAL, "project_root unresolvable")
        planned, path_err = _plan_restore(files, project)
        if path_err:
            return _rb(STATUS_INVALID_PROPOSAL, path_err)
        check = getattr(self._security, "check_permanent_change", None)
        if check is None:
            return _rb(STATUS_SECURITY_DENIED, "security has no check_permanent_change")
        decision = check(f"rollback {ident}")
        verdict = getattr(decision, "verdict", None)
        if verdict is Verdict.DENY:
            return _rb(
                STATUS_SECURITY_DENIED,
                str(getattr(decision, "reason", "permanent change denied") or "denied"),
            )
        if verdict is not Verdict.REQUIRES_APPROVAL:
            return _rb(
                STATUS_SECURITY_DENIED,
                "permanent change is not REQUIRES_APPROVAL; fail closed; never ALLOW",
            )
        restored, deleted, write_err = restore_entries(planned, project=project)
        restored_names = tuple(str(path) for path in restored)
        deleted_names = tuple(str(path) for path in deleted)
        if write_err:
            return RollbackResult(
                status=STATUS_ROLLBACK_ERROR,
                reason=write_err,
                restored=restored_names,
                deleted=deleted_names,
            )
        return RollbackResult(
            status=STATUS_ROLLED_BACK,
            reason="rolled back",
            restored=restored_names,
            deleted=deleted_names,
        )

    def _store(self) -> Path | None:
        if self._store_root is None:
            return None
        return canonical(self._store_root)

    def _index_path(self) -> Path | None:
        store = self._store()
        if store is None:
            return None
        return store / _INDEX_NAME

    def _load_index(self) -> tuple[tuple[VersionRecord, ...], str]:
        path = self._index_path()
        if path is None:
            return (), "version store root missing"
        if not path.is_file():
            return (), ""
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            return (), f"version index unreadable: {exc}"
        if not isinstance(raw, list):
            return (), "version index is not a list"
        records: list[VersionRecord] = []
        seen: set[str] = set()
        for item in raw:
            record = _record_from_json(item)
            if record is None:
                return (), "version index entry malformed"
            if record.version in seen:
                return (), f"duplicate version {record.version} in index"
            seen.add(record.version)
            records.append(record)
        return tuple(records), ""

    def _write_index(self, records: tuple[VersionRecord, ...]) -> str:
        path = self._index_path()
        if path is None:
            return "version store root missing"
        payload = [_record_to_json(item) for item in records]
        return self._write_store_bytes(
            path,
            json.dumps(payload, indent=2, sort_keys=True).encode("utf-8"),
        )

    def _persist_snapshot(
        self,
        version_dir: Path,
        version_id: str,
        files: tuple[VersionFileSnapshot, ...],
        integrity: str,
    ) -> str:
        files_root = version_dir / _FILES_DIR
        for item in files:
            rel = str(item.relative_path or "").replace("\\", "/")
            if (
                not rel
                or Path(rel).is_absolute()
                or ".." in Path(rel).parts
            ):
                return f"snapshot path {rel!r} rejected"
            if item.existed:
                if item.original_bytes is None:
                    return f"snapshot {rel} existed but has no bytes"
                digest = hashlib.sha256(item.original_bytes).hexdigest()
                if digest != item.sha256:
                    return f"snapshot {rel} hash mismatch"
                dest = files_root / Path(rel)
                dest_canon = canonical(dest)
                files_canon = canonical(files_root)
                if (
                    dest_canon is None
                    or files_canon is None
                    or not is_under(dest_canon, files_canon)
                ):
                    return f"snapshot blob path escaped store for {rel}"
                err = self._write_store_bytes(dest_canon, item.original_bytes)
                if err:
                    return err
            elif item.original_bytes is not None:
                return f"snapshot {rel} marked new but has bytes"
        manifest = {
            "version": version_id,
            "integrity": integrity,
            "files": [
                {
                    "path": str(item.relative_path).replace("\\", "/"),
                    "existed": bool(item.existed),
                    "sha256": str(item.sha256 or ""),
                }
                for item in files
            ],
        }
        return self._write_store_bytes(
            version_dir / _MANIFEST_NAME,
            json.dumps(manifest, indent=2, sort_keys=True).encode("utf-8"),
        )

    def _load_snapshot(
        self, version_id: str, record: VersionRecord
    ) -> tuple[tuple[VersionFileSnapshot, ...], str]:
        store = self._store()
        if store is None:
            return (), "version store root missing"
        version_dir = store / version_id
        manifest_path = version_dir / _MANIFEST_NAME
        if not manifest_path.is_file():
            return (), f"snapshot manifest missing for {version_id}"
        try:
            raw = json.loads(manifest_path.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            return (), f"snapshot manifest unreadable: {exc}"
        if not isinstance(raw, dict):
            return (), "snapshot manifest malformed"
        if str(raw.get("version") or "") != version_id:
            return (), "snapshot manifest version mismatch"
        listed = raw.get("files")
        if not isinstance(listed, list) or not listed:
            return (), "snapshot manifest has no files"
        files_root = version_dir / _FILES_DIR
        snaps: list[VersionFileSnapshot] = []
        for item in listed:
            if not isinstance(item, dict):
                return (), "snapshot file entry malformed"
            rel = str(item.get("path") or "").replace("\\", "/")
            if (
                not rel
                or Path(rel).is_absolute()
                or ".." in Path(rel).parts
            ):
                return (), f"snapshot path {rel!r} rejected"
            existed = bool(item.get("existed"))
            digest = str(item.get("sha256") or "")
            blob = files_root / Path(rel)
            blob_canon = canonical(blob)
            files_canon = canonical(files_root)
            if (
                blob_canon is None
                or files_canon is None
                or not is_under(blob_canon, files_canon)
            ):
                return (), f"snapshot blob path escaped store for {rel}"
            if existed:
                if not blob_canon.is_file() or is_link(blob_canon):
                    return (), f"snapshot blob missing or not a file for {rel}"
                data = blob_canon.read_bytes()
                actual = hashlib.sha256(data).hexdigest()
                if actual != digest:
                    return (), f"snapshot blob corrupted for {rel}"
                snaps.append(
                    VersionFileSnapshot(
                        relative_path=rel,
                        existed=True,
                        sha256=digest,
                        original_bytes=data,
                    )
                )
            else:
                if blob_canon.exists():
                    return (), f"snapshot blob present for new file {rel}"
                if digest:
                    return (), f"snapshot hash present for new file {rel}"
                snaps.append(
                    VersionFileSnapshot(
                        relative_path=rel,
                        existed=False,
                        sha256="",
                        original_bytes=None,
                    )
                )
        integrity = snapshot_integrity(version_id, snaps)
        listed_integrity = str(raw.get("integrity") or "")
        if listed_integrity != integrity or record.integrity != integrity:
            return (), "snapshot integrity mismatch"
        if record.file_count and record.file_count != len(snaps):
            return (), "snapshot file count mismatch"
        return tuple(snaps), ""

    def _write_store_bytes(self, dest: Path, payload: bytes) -> str:
        check = getattr(self._security, "check_temporary_write", None)
        if check is None:
            return "security has no check_temporary_write"
        decision = check(dest)
        if getattr(decision, "verdict", None) is not Verdict.ALLOW:
            reason = str(getattr(decision, "reason", "temporary write denied") or "denied")
            return f"version store write denied: {reason}"
        try:
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(payload)
        except Exception as exc:  # noqa: BLE001
            return f"version store write failed: {exc}"
        return ""


def _plan_restore(
    files: tuple[VersionFileSnapshot, ...], project: Path
) -> tuple[tuple[tuple[Path, bytes | None], ...], str]:
    planned: list[tuple[Path, bytes | None]] = []
    seen: set[str] = set()
    for item in files:
        dest, rel, err = resolve_dest(item.relative_path, project)
        if err or dest is None or rel is None:
            return (), err or "path rejected"
        key = os.path.normcase(str(rel))
        if key in seen:
            return (), f"duplicate rollback path {rel.as_posix()}"
        seen.add(key)
        if item.existed:
            if item.original_bytes is None:
                return (), f"{rel.as_posix()} missing original bytes"
            planned.append((dest, item.original_bytes))
        else:
            planned.append((dest, None))
    if not planned:
        return (), "no files to restore"
    return tuple(planned), ""


def _record_to_json(record: VersionRecord) -> dict[str, object]:
    deployed = record.deployed_at.isoformat() if record.deployed_at else ""
    return {
        "version": record.version,
        "deployed_at": deployed,
        "summary": record.summary,
        "approved_by": record.approved_by,
        "evidence": list(record.evidence),
        "file_count": int(record.file_count),
        "integrity": record.integrity,
    }


def _record_from_json(raw: object) -> VersionRecord | None:
    if not isinstance(raw, dict):
        return None
    ident = str(raw.get("version") or "").strip()
    if not ident:
        return None
    stamp = str(raw.get("deployed_at") or "")
    try:
        deployed_at = datetime.fromisoformat(stamp) if stamp else datetime.now(timezone.utc)
    except ValueError:
        return None
    evidence_raw = raw.get("evidence") or ()
    if not isinstance(evidence_raw, (list, tuple)):
        return None
    return VersionRecord(
        version=ident,
        deployed_at=deployed_at,
        summary=str(raw.get("summary") or ""),
        approved_by=str(raw.get("approved_by") or ""),
        evidence=tuple(str(item) for item in evidence_raw),
        file_count=int(raw.get("file_count") or 0),
        integrity=str(raw.get("integrity") or ""),
    )


def _rb(status: str, reason: str) -> RollbackResult:
    _LOGGER.debug("rollback refused: %s (%s)", status, reason)
    return RollbackResult(status=status, reason=reason)


__all__ = ["LocalVersionManager", "capture_preimage", "snapshot_integrity"]
