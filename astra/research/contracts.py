"""Research Agent contracts.

Research is the first stage of every lifecycle in the constitution. Network
access is optional, user-initiated, and gated by the Security Agent; the agent
must produce useful results from local sources when offline.

B-009 (ADR-0011): concrete implementation in :mod:`astra.research.agent`.
Source abstraction in :mod:`astra.research.source`.
"""

from __future__ import annotations

from abc import abstractmethod
from dataclasses import dataclass, field
from datetime import datetime
from typing import Sequence

from astra.core.contracts import Agent, AgentResult


@dataclass(frozen=True, slots=True)
class Finding:
    """One piece of evidence gathered during research.

    Attributes:
        claim: What was found.
        source: Where it came from. Findings without a source are assumptions.
        confidence: Value in ``[0.0, 1.0]``. Semantics are defined per source
            implementation; see :mod:`astra.research.source`.
        retrieved_at: When this finding was collected. ``None`` for findings
            whose timestamp is unknown or not yet set.
    """

    claim: str
    source: str
    confidence: float = 0.0
    retrieved_at: datetime | None = None


@dataclass(frozen=True, slots=True)
class ResearchReport:
    """The outcome of investigating one question."""

    question: str
    findings: tuple[Finding, ...] = field(default_factory=tuple)
    open_questions: tuple[str, ...] = field(default_factory=tuple)
    used_network: bool = False
    outcome: str = "no_sources"


class ResearchAgent(Agent):
    """Gathers evidence for a question."""

    name = "research"

    @abstractmethod
    def investigate(self, question: str, *, allow_network: bool = False) -> ResearchReport:
        """Research a question and return findings.

        Args:
            question: What to investigate.
            allow_network: Whether outbound access is permitted for this call.
                Defaults to ``False``. Implementations must treat ``True`` as
                permission granted by the user for this call only, and must
                still ask the Security Agent before any request.

        Implementations must record whether the network was used, so that a
        result can be reproduced offline or flagged as unreproducible.
        """
        raise NotImplementedError

    @abstractmethod
    def summarise(self, findings: Sequence[Finding]) -> AgentResult:
        """Condense findings into a result suitable for storage in memory."""
        raise NotImplementedError


__all__ = ["Finding", "ResearchReport", "ResearchAgent"]

