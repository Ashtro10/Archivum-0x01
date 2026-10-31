"""Immutable baseline store (B-029, ADR-0028).

Controller-owned. Not an Agent. Not a Tool. Not VersionManager.
Writes under ``{scratch}/baselines/`` after ``check_temporary_write`` ALLOW.
Not a new permanent-write root. Not Git. Not a test workspace.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from astra.core.lifecycle import MeasurableProperty
from astra.evaluation.contracts import (
    BASELINE_INCOMPLETE,
    BASELINE_VALID,
    WRITE_DUPLICATE,
    WRITE_INCOMPLETE,
    WRITE_RECORDED,
    WRITE_REJECTED,
    BaselineRecord,
    BaselineWriteResult,
    EvaluationReport,
    MetricSample,
)
from astra.logging import get_logger
from astra.security.contracts import Verdict

_LOGGER = get_logger(__name__)

_BASELINE_ID = re.compile(r"^b-[0-9a-f]{16}$")
_INDEX_NAME = "index.json"
_RECORD_NAME = "record.json"


def baseline_id_for(subject: str) -> str:
    hasher = hashlib.sha256()
    hasher.update(b"astra-baseline-v1\n")
    hasher.update(str(subject or "").encode("utf-8"))
    return f"b-{hasher.hexdigest()[:16]}"


def samples_from_report(report: EvaluationReport) -> tuple[MetricSample, ...]:
    if report is None:
        return ()
    samples = tuple(getattr(report, "samples", ()) or ())
    if samples:
        return samples
    measurements = tuple(getattr(report, "measurements", ()) or ())
    extracted: list[MetricSample] = []
    for item in measurements:
        extracted.append(
            MetricSample(
                property=item.property,
                metric=str(item.metric or ""),
                value=float(item.candidate),
                unit=str(getattr(item, "unit", "") or ""),
                higher_is_better=bool(item.higher_is_better),
            )
        )
    return tuple(extracted)


def baseline_integrity(record: BaselineRecord) -> str:
    hasher = hashlib.sha256()
    hasher.update(b"astra-baseline-integrity-v1\n")
    hasher.update(str(record.baseline_id or "").encode("utf-8"))
    hasher.update(b"\n")
    hasher.update(str(record.subject or "").encode("utf-8"))
    hasher.update(b"\n")
    hasher.update(str(record.implementation or "").encode("utf-8"))
    hasher.update(b"\n")
    hasher.update(str(record.status or "").encode("utf-8"))
    hasher.update(b"\n")
    for sample in record.metrics:
        hasher.update(str(getattr(sample.property, "value", "")).encode("utf-8"))
        hasher.update(b"\0")
        hasher.update(str(sample.metric or "").encode("utf-8"))
        hasher.update(b"\0")
        hasher.update(repr(float(sample.value)).encode("utf-8"))
        hasher.update(b"\0")
        hasher.update(str(sample.unit or "").encode("utf-8"))
        hasher.update(b"\0")
        hasher.update(b"1" if sample.higher_is_better else b"0")
        hasher.update(b"\n")
    return hasher.hexdigest()


class LocalBaselineStore:
    """Append-only baseline index plus per-id JSON records under scratch."""

    def __init__(
        self,
        *,
        security: Any,
        store_root: Path | str | None,
    ) -> None:
        self._security = security
        self._store_root = Path(store_root) if store_root is not None else None

    def record(self, subject: str, report: EvaluationReport) -> BaselineWriteResult:
        ident_subject = str(subject or "").strip()
        if not ident_subject:
            return BaselineWriteResult(status=WRITE_REJECTED, reason="subject missing")
        if report is None or not isinstance(report, EvaluationReport):
            return BaselineWriteResult(
                status=WRITE_REJECTED, reason="EvaluationReport missing"
            )
        store = self._store()
        if store is None:
            return BaselineWriteResult(
                status=WRITE_REJECTED, reason="baseline store root missing"
            )
        ident = baseline_id_for(ident_subject)
        if not _BASELINE_ID.match(ident):
            return BaselineWriteResult(status=WRITE_REJECTED, reason="baseline id rejected")
        existing, load_err = self._load_index()
        if load_err:
            return BaselineWriteResult(status=WRITE_REJECTED, reason=load_err)
        if any(item.baseline_id == ident or item.subject == ident_subject for item in existing):
            return BaselineWriteResult(
                status=WRITE_DUPLICATE,
                baseline_id=ident,
                reason=f"baseline {ident} already recorded",
            )
        samples = samples_from_report(report)
        usable: list[MetricSample] = []
        for sample in samples:
            if not str(sample.metric or "").strip():
                return BaselineWriteResult(
                    status=WRITE_REJECTED, reason="metric name missing"
                )
            try:
                value = float(sample.value)
            except (TypeError, ValueError):
                return BaselineWriteResult(
                    status=WRITE_REJECTED, reason=f"metric {sample.metric} not numeric"
                )
            if not _finite(value):
                return BaselineWriteResult(
                    status=WRITE_REJECTED, reason=f"metric {sample.metric} not finite"
                )
            usable.append(
                MetricSample(
                    property=sample.property,
                    metric=str(sample.metric),
                    value=value,
                    unit=str(sample.unit or ""),
                    higher_is_better=bool(sample.higher_is_better),
                )
            )
        status = BASELINE_VALID if usable else BASELINE_INCOMPLETE
        record = BaselineRecord(
            baseline_id=ident,
            subject=ident_subject,
            recorded_at=datetime.now(timezone.utc),
            implementation=str(getattr(report, "implementation", "") or ""),
            metrics=tuple(usable),
            status=status,
        )
        record = BaselineRecord(
            baseline_id=record.baseline_id,
            subject=record.subject,
            recorded_at=record.recorded_at,
            implementation=record.implementation,
            metrics=record.metrics,
            status=record.status,
            integrity=baseline_integrity(record),
        )
        dest_dir = store / ident
        if dest_dir.exists():
            return BaselineWriteResult(
                status=WRITE_DUPLICATE,
                baseline_id=ident,
                reason=f"baseline directory {ident} already exists",
            )
        err = self._write_store_bytes(
            dest_dir / _RECORD_NAME,
            json.dumps(_record_to_json(record), indent=2, sort_keys=True).encode("utf-8"),
        )
        if err:
            return BaselineWriteResult(status=WRITE_REJECTED, reason=err)
        err = self._write_index((*existing, record))
        if err:
            return BaselineWriteResult(status=WRITE_REJECTED, reason=err)
        return BaselineWriteResult(
            status=WRITE_RECORDED if status == BASELINE_VALID else WRITE_INCOMPLETE,
            baseline_id=ident,
            reason="recorded" if status == BASELINE_VALID else "recorded incomplete",
        )

    def get(self, baseline_id: str) -> BaselineRecord | None:
        record, err = self.load(baseline_id)
        if err or record is None:
            return None
        return record

    def get_for_subject(self, subject: str) -> BaselineRecord | None:
        ident = baseline_id_for(str(subject or "").strip())
        return self.get(ident)

    def load(self, baseline_id: str) -> tuple[BaselineRecord | None, str]:
        ident = str(baseline_id or "").strip()
        if not ident or not _BASELINE_ID.match(ident):
            return None, "baseline id rejected"
        records, err = self._load_index()
        if err:
            return None, err
        if not any(item.baseline_id == ident for item in records):
            return None, "unknown baseline"
        store = self._store()
        if store is None:
            return None, "baseline store root missing"
        path = store / ident / _RECORD_NAME
        path_canon = _canonical(path)
        store_canon = store
        if path_canon is None or not _is_under(path_canon, store_canon):
            return None, "baseline path escaped store"
        if not path_canon.is_file():
            return None, "baseline record missing"
        try:
            raw = json.loads(path_canon.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            return None, f"baseline unreadable: {exc}"
        record = _record_from_json(raw)
        if record is None:
            return None, "baseline malformed"
        if record.baseline_id != ident:
            return None, "baseline id mismatch"
        expected = baseline_integrity(
            BaselineRecord(
                baseline_id=record.baseline_id,
                subject=record.subject,
                recorded_at=record.recorded_at,
                implementation=record.implementation,
                metrics=record.metrics,
                status=record.status,
                integrity="",
            )
        )
        if record.integrity != expected:
            return None, "baseline integrity mismatch"
        return record, ""

    def _store(self) -> Path | None:
        if self._store_root is None:
            return None
        return _canonical(self._store_root)

    def _index_path(self) -> Path | None:
        store = self._store()
        if store is None:
            return None
        return store / _INDEX_NAME

    def _load_index(self) -> tuple[tuple[BaselineRecord, ...], str]:
        path = self._index_path()
        if path is None:
            return (), "baseline store root missing"
        if not path.is_file():
            return (), ""
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            return (), f"baseline index unreadable: {exc}"
        if not isinstance(raw, list):
            return (), "baseline index is not a list"
        records: list[BaselineRecord] = []
        seen: set[str] = set()
        for item in raw:
            record = _record_from_json(item)
            if record is None:
                return (), "baseline index entry malformed"
            if record.baseline_id in seen:
                return (), f"duplicate baseline {record.baseline_id} in index"
            seen.add(record.baseline_id)
            records.append(record)
        return tuple(records), ""

    def _write_index(self, records: tuple[BaselineRecord, ...]) -> str:
        path = self._index_path()
        if path is None:
            return "baseline store root missing"
        payload = [_record_to_json(item) for item in records]
        return self._write_store_bytes(
            path,
            json.dumps(payload, indent=2, sort_keys=True).encode("utf-8"),
        )

    def _write_store_bytes(self, dest: Path, payload: bytes) -> str:
        check = getattr(self._security, "check_temporary_write", None)
        if check is None:
            return "security has no check_temporary_write"
        decision = check(dest)
        if getattr(decision, "verdict", None) is not Verdict.ALLOW:
            reason = str(getattr(decision, "reason", "temporary write denied") or "denied")
            return f"baseline store write denied: {reason}"
        try:
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(payload)
        except Exception as exc:  # noqa: BLE001
            return f"baseline store write failed: {exc}"
        return ""


def _finite(value: float) -> bool:
    return value == value and value not in (float("inf"), float("-inf"))


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


def _record_to_json(record: BaselineRecord) -> dict[str, object]:
    return {
        "baseline_id": record.baseline_id,
        "subject": record.subject,
        "recorded_at": record.recorded_at.isoformat() if record.recorded_at else "",
        "implementation": record.implementation,
        "status": record.status,
        "integrity": record.integrity,
        "metrics": [
            {
                "property": str(getattr(sample.property, "value", sample.property)),
                "metric": sample.metric,
                "value": float(sample.value),
                "unit": sample.unit,
                "higher_is_better": bool(sample.higher_is_better),
            }
            for sample in record.metrics
        ],
    }


def _record_from_json(raw: object) -> BaselineRecord | None:
    if not isinstance(raw, dict):
        return None
    ident = str(raw.get("baseline_id") or "").strip()
    subject = str(raw.get("subject") or "").strip()
    if not ident or not subject:
        return None
    stamp = str(raw.get("recorded_at") or "")
    try:
        recorded_at = datetime.fromisoformat(stamp) if stamp else datetime.now(timezone.utc)
    except ValueError:
        return None
    listed = raw.get("metrics") or ()
    if not isinstance(listed, (list, tuple)):
        return None
    metrics: list[MetricSample] = []
    for item in listed:
        sample = _sample_from_json(item)
        if sample is None:
            return None
        metrics.append(sample)
    status = str(raw.get("status") or BASELINE_INCOMPLETE)
    if status not in {BASELINE_VALID, BASELINE_INCOMPLETE}:
        status = BASELINE_INCOMPLETE
    return BaselineRecord(
        baseline_id=ident,
        subject=subject,
        recorded_at=recorded_at,
        implementation=str(raw.get("implementation") or ""),
        metrics=tuple(metrics),
        integrity=str(raw.get("integrity") or ""),
        status=status if metrics else BASELINE_INCOMPLETE,
    )


def _sample_from_json(raw: object) -> MetricSample | None:
    if not isinstance(raw, dict):
        return None
    name = str(raw.get("metric") or "").strip()
    prop_name = str(raw.get("property") or "").strip()
    if not name or not prop_name:
        return None
    try:
        prop = MeasurableProperty(prop_name)
    except ValueError:
        return None
    try:
        value = float(raw.get("value"))
    except (TypeError, ValueError):
        return None
    if not _finite(value):
        return None
    return MetricSample(
        property=prop,
        metric=name,
        value=value,
        unit=str(raw.get("unit") or ""),
        higher_is_better=bool(raw.get("higher_is_better", True)),
    )


__all__ = [
    "LocalBaselineStore",
    "baseline_id_for",
    "baseline_integrity",
    "samples_from_report",
]
