"""Evaluation package.

``LocalEvaluationAgent.evaluate_proposal`` judges a ChangeProposal against
an existing TestReport (B-026). ``evaluate`` / ``record_baseline`` compare
and persist immutable metric samples (B-029). No approval, no deployment,
no test execution.
"""

from __future__ import annotations

from astra.evaluation.agent import LocalEvaluationAgent
from astra.evaluation.contracts import (
    BaselineRecord,
    BaselineWriteResult,
    EvaluationAgent,
    EvaluationReport,
    Measurement,
    MetricSample,
)
from astra.evaluation.store import LocalBaselineStore

__all__ = [
    "BaselineRecord",
    "BaselineWriteResult",
    "Measurement",
    "MetricSample",
    "EvaluationReport",
    "EvaluationAgent",
    "LocalBaselineStore",
    "LocalEvaluationAgent",
]
