"""Evaluation Agent contract.

Evidence over assumptions: a change is an improvement only when a measurement
says so. This agent produces the evidence that the human approval gate
depends on. Proposal evaluation (B-026) judges an existing TestReport; it
does not run tests, approve, or deploy. Baseline comparison (B-029) records
immutable metric samples and compares them deterministically.

``deployment_authorized`` is never set by evaluation.
"""

from __future__ import annotations

import math
from abc import abstractmethod
from dataclasses import dataclass, field
from datetime import datetime

from astra.coding.contracts import ChangeProposal
from astra.core.contracts import Agent
from astra.core.lifecycle import MeasurableProperty
from astra.testing.contracts import TestReport

BASELINE_MISSING = "missing"
BASELINE_VALID = "valid"
BASELINE_INCOMPLETE = "incomplete"
BASELINE_CORRUPTED = "corrupted"
BASELINE_MISMATCH = "mismatch"

CANDIDATE_MISSING = "missing"
CANDIDATE_VALID = "valid"
CANDIDATE_INCOMPLETE = "incomplete"

COMPARISON_UNAVAILABLE = "unavailable"
COMPARISON_INCOMPLETE = "incomplete"
COMPARISON_IMPROVED = "improved"
COMPARISON_REGRESSED = "regressed"
COMPARISON_UNCHANGED = "unchanged"

WRITE_RECORDED = "recorded"
WRITE_DUPLICATE = "duplicate"
WRITE_REJECTED = "rejected"
WRITE_INCOMPLETE = "incomplete"


@dataclass(frozen=True, slots=True)
class MetricSample:
    """One measured value. Not a comparison. Not a test count."""

    property: MeasurableProperty
    metric: str
    value: float
    unit: str = ""
    higher_is_better: bool = True


@dataclass(frozen=True, slots=True)
class Measurement:
    """A single before-and-after measurement.

    Attributes:
        property: Which measurable property was assessed.
        metric: Name of the metric, for example ``p95_latency_ms``.
        baseline: Value before the change.
        candidate: Value after the change.
        higher_is_better: Direction of improvement for this metric.
        unit: Unit recorded with both values. Empty if unspecified.
    """

    property: MeasurableProperty
    metric: str
    baseline: float
    candidate: float
    higher_is_better: bool = True
    unit: str = ""

    @property
    def improved(self) -> bool:
        """Whether the candidate beats the baseline. Exact comparison."""
        if not math.isfinite(self.baseline) or not math.isfinite(self.candidate):
            return False
        if self.candidate == self.baseline:
            return False
        if self.higher_is_better:
            return self.candidate > self.baseline
        return self.candidate < self.baseline

    @property
    def regressed(self) -> bool:
        """Whether the candidate is worse than the baseline. Exact comparison."""
        if not math.isfinite(self.baseline) or not math.isfinite(self.candidate):
            return False
        if self.candidate == self.baseline:
            return False
        return not self.improved


@dataclass(frozen=True, slots=True)
class BaselineRecord:
    """Immutable recorded baseline. Bytes live in the baseline store."""

    baseline_id: str
    subject: str
    recorded_at: datetime
    implementation: str = ""
    metrics: tuple[MetricSample, ...] = field(default=())
    integrity: str = ""
    status: str = BASELINE_INCOMPLETE


@dataclass(frozen=True, slots=True)
class BaselineWriteResult:
    """Outcome of one record_baseline attempt."""

    status: str
    baseline_id: str = ""
    reason: str = ""

    @property
    def recorded(self) -> bool:
        return self.status == WRITE_RECORDED


@dataclass(frozen=True, slots=True)
class EvaluationReport:
    """The evidence supporting or refuting a proposed improvement.

    ``improved`` is measurement-only. Passing tests do not invent measurements
    and do not authorize deployment. ``deployment_authorized`` is never set by
    evaluation; human ``ApprovalRecord`` remains a separate gate.
    """

    subject: str
    measurements: tuple[Measurement, ...] = field(default=())
    notes: str = ""
    tests_passed: bool = False
    evaluation_passed: bool = False
    deployment_authorized: bool = False
    incomplete: bool = False
    reason: str = ""
    implementation: str = ""
    samples: tuple[MetricSample, ...] = field(default=())
    baseline_id: str = ""
    baseline_status: str = BASELINE_MISSING
    candidate_status: str = CANDIDATE_MISSING
    comparison_status: str = COMPARISON_UNAVAILABLE
    unavailable_metrics: tuple[str, ...] = field(default=())

    @property
    def improved(self) -> bool:
        """True when at least one property improved and none regressed."""
        if not self.measurements:
            return False
        return any(m.improved for m in self.measurements) and all(
            m.improved or m.candidate == m.baseline for m in self.measurements
        )


class EvaluationAgent(Agent):
    """Judges tested proposals; does not approve or deploy them."""

    name = "evaluation"

    @abstractmethod
    def evaluate(
        self,
        subject: str,
        candidate: tuple[MetricSample, ...] = (),
    ) -> EvaluationReport:
        """Compare candidate samples against the recorded baseline.

        Does not run tests, invent measurements, approve, or deploy.
        Missing candidate samples yield an incomplete comparison.
        """
        raise NotImplementedError

    @abstractmethod
    def evaluate_proposal(
        self,
        proposal: ChangeProposal,
        tests: TestReport,
        *,
        baseline_id: str | None = None,
        candidate: tuple[MetricSample, ...] = (),
    ) -> EvaluationReport:
        """Judge a proposal using an existing TestReport.

        Optional ``baseline_id`` and ``candidate`` enable comparative
        evaluation. Must not run tests, mutate the proposal, create
        approval, or deploy.
        """
        raise NotImplementedError

    @abstractmethod
    def record_baseline(
        self, subject: str, report: EvaluationReport
    ) -> BaselineWriteResult:
        """Store a report's samples as the immutable baseline for ``subject``."""
        raise NotImplementedError


__all__ = [
    "BASELINE_CORRUPTED",
    "BASELINE_INCOMPLETE",
    "BASELINE_MISMATCH",
    "BASELINE_MISSING",
    "BASELINE_VALID",
    "CANDIDATE_INCOMPLETE",
    "CANDIDATE_MISSING",
    "CANDIDATE_VALID",
    "COMPARISON_IMPROVED",
    "COMPARISON_INCOMPLETE",
    "COMPARISON_REGRESSED",
    "COMPARISON_UNAVAILABLE",
    "COMPARISON_UNCHANGED",
    "WRITE_DUPLICATE",
    "WRITE_INCOMPLETE",
    "WRITE_RECORDED",
    "WRITE_REJECTED",
    "BaselineRecord",
    "BaselineWriteResult",
    "EvaluationAgent",
    "EvaluationReport",
    "Measurement",
    "MetricSample",
]
