"""Deterministic metric comparison (B-029, ADR-0028).

No I/O. No fabricated values. Exact equality; no default tolerance.
"""

from __future__ import annotations

import math
from typing import Sequence

from astra.evaluation.contracts import (
    COMPARISON_IMPROVED,
    COMPARISON_INCOMPLETE,
    COMPARISON_REGRESSED,
    COMPARISON_UNAVAILABLE,
    COMPARISON_UNCHANGED,
    Measurement,
    MetricSample,
)


def metric_key(sample: MetricSample) -> tuple[str, str, str]:
    return (
        str(getattr(sample.property, "value", sample.property)),
        str(sample.metric or ""),
        str(sample.unit or ""),
    )


def compare_samples(
    baseline: Sequence[MetricSample],
    candidate: Sequence[MetricSample],
) -> tuple[tuple[Measurement, ...], tuple[str, ...], str]:
    """Pair baseline and candidate samples by property+metric+unit.

    Direction comes from the baseline sample. A direction mismatch is
    unavailable, not a flipped comparison.
    """
    if not baseline and not candidate:
        return (), (), COMPARISON_UNAVAILABLE
    base_map: dict[tuple[str, str, str], MetricSample] = {}
    for sample in baseline:
        if not _usable(sample):
            return (), (str(sample.metric or "invalid"),), COMPARISON_INCOMPLETE
        key = metric_key(sample)
        if key in base_map:
            return (), (sample.metric,), COMPARISON_INCOMPLETE
        base_map[key] = sample
    cand_map: dict[tuple[str, str, str], MetricSample] = {}
    for sample in candidate:
        if not _usable(sample):
            return (), (str(sample.metric or "invalid"),), COMPARISON_INCOMPLETE
        key = metric_key(sample)
        if key in cand_map:
            return (), (sample.metric,), COMPARISON_INCOMPLETE
        cand_map[key] = sample
    unavailable: list[str] = []
    paired: list[Measurement] = []
    for key, base in base_map.items():
        cand = cand_map.get(key)
        if cand is None:
            unavailable.append(base.metric)
            continue
        if bool(cand.higher_is_better) != bool(base.higher_is_better):
            unavailable.append(base.metric)
            continue
        paired.append(
            Measurement(
                property=base.property,
                metric=base.metric,
                baseline=float(base.value),
                candidate=float(cand.value),
                higher_is_better=bool(base.higher_is_better),
                unit=base.unit,
            )
        )
    for key, cand in cand_map.items():
        if key not in base_map:
            unavailable.append(cand.metric)
    if unavailable:
        return tuple(paired), tuple(unavailable), COMPARISON_INCOMPLETE
    if not paired:
        return (), (), COMPARISON_UNAVAILABLE
    if any(item.regressed for item in paired):
        return tuple(paired), (), COMPARISON_REGRESSED
    if any(item.improved for item in paired):
        return tuple(paired), (), COMPARISON_IMPROVED
    return tuple(paired), (), COMPARISON_UNCHANGED


def _usable(sample: MetricSample) -> bool:
    if sample is None:
        return False
    if not str(getattr(sample, "metric", "") or "").strip():
        return False
    try:
        value = float(sample.value)
    except (TypeError, ValueError):
        return False
    return math.isfinite(value)


__all__ = ["compare_samples", "metric_key"]
