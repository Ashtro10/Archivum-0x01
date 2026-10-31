"""The pipelines and measures defined by the constitution.

These enumerations exist so that lifecycle stages are referenced by name rather
than by loose strings, and so that a skipped stage is detectable in code. They
mirror ``CONSTITUTION.md`` exactly; adding a member here means amending the
constitution first.
"""

from __future__ import annotations

from enum import Enum


class LifecycleStage(Enum):
    """Development lifecycle. No stage should be skipped."""

    RESEARCH = "research"
    DESIGN = "design"
    IMPLEMENTATION = "implementation"
    TESTING = "testing"
    EVALUATION = "evaluation"
    DOCUMENTATION = "documentation"
    DEPLOYMENT = "deployment"


class ImprovementStage(Enum):
    """Self-improvement protocol.

    ``HUMAN_APPROVAL`` is a hard gate: no code path may bypass it.
    """

    RESEARCH = "research"
    PROPOSAL = "proposal"
    IMPLEMENTATION = "implementation"
    TESTING = "testing"
    BENCHMARKING = "benchmarking"
    HUMAN_APPROVAL = "human_approval"
    DEPLOYMENT = "deployment"


class ScientificStage(Enum):
    """Scientific method, applied to every new idea."""

    OBSERVATION = "observation"
    HYPOTHESIS = "hypothesis"
    EXPERIMENT = "experiment"
    RESULTS = "results"
    CONCLUSION = "conclusion"
    DOCUMENTATION = "documentation"
    DEPLOYMENT = "deployment"


class MeasurableProperty(Enum):
    """Properties a change may claim to improve.

    Every modification must improve at least one of these. Appearing cleaner is
    not among them.
    """

    PERFORMANCE = "performance"
    SIMPLICITY = "simplicity"
    MAINTAINABILITY = "maintainability"
    RELIABILITY = "reliability"
    SECURITY = "security"
    ACCURACY = "accuracy"
    RESOURCE_EFFICIENCY = "resource_efficiency"
    USER_EXPERIENCE = "user_experience"


class Competence(Enum):
    """The competence model Astra is built to increase."""

    UNDERSTANDING = "understanding"
    PREDICTION = "prediction"
    PLANNING = "planning"
    EXECUTION = "execution"
    REFLECTION = "reflection"
    CONTINUOUS_IMPROVEMENT = "continuous_improvement"


LIFECYCLE_ORDER: tuple[LifecycleStage, ...] = tuple(LifecycleStage)
IMPROVEMENT_ORDER: tuple[ImprovementStage, ...] = tuple(ImprovementStage)
SCIENTIFIC_ORDER: tuple[ScientificStage, ...] = tuple(ScientificStage)

__all__ = [
    "LifecycleStage",
    "ImprovementStage",
    "ScientificStage",
    "MeasurableProperty",
    "Competence",
    "LIFECYCLE_ORDER",
    "IMPROVEMENT_ORDER",
    "SCIENTIFIC_ORDER",
]
