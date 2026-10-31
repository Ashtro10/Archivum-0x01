"""Coding Agent contract.

The agent proposes changes; it never applies them. Applying a change is a
permanent modification and belongs to the Deployment Agent, after testing,
benchmarking, and human approval.

Interfaces only: no behaviour is implemented here.
"""

from __future__ import annotations

from abc import abstractmethod
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

from astra.core.contracts import Agent
from astra.core.lifecycle import MeasurableProperty


@dataclass(frozen=True, slots=True)
class FileChange:
    """A proposed edit to one file.

    ``new_content`` is the complete intended contents, not a patch, so that a
    proposal can be reviewed and applied without replaying history.
    """

    path: Path
    new_content: str
    rationale: str = ""


@dataclass(frozen=True, slots=True)
class ChangeProposal:
    """A reviewable set of edits.

    Attributes:
        objective: What the change is for.
        changes: The edits themselves.
        improves: Which measurable properties the change claims to improve.
            The constitution forbids changes that improve none of them.
        risks: Known ways this could go wrong.
    """

    objective: str
    changes: tuple[FileChange, ...] = field(default=())
    improves: tuple[MeasurableProperty, ...] = field(default=())
    risks: tuple[str, ...] = field(default=())


class CodingAgent(Agent):
    """Writes code proposals for review."""

    name = "coding"

    @abstractmethod
    def propose_change(
        self, objective: str, targets: Sequence[Path] | None = None
    ) -> ChangeProposal:
        """Produce a reviewable proposal.

        Implementations must extend existing modules rather than rewriting
        them, and must never return a proposal whose ``improves`` field is
        empty.
        """
        raise NotImplementedError

    @abstractmethod
    def review(self, proposal: ChangeProposal) -> Sequence[str]:
        """Return objections to a proposal.

        An empty sequence means no objection was found, which is not the same
        as the proposal being correct.
        """
        raise NotImplementedError


__all__ = ["FileChange", "ChangeProposal", "CodingAgent"]
