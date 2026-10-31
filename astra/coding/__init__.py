"""Coding Agent.

B-019 / B-020: :class:`LocalCodingAgent` produces reviewable proposals only.
It retains the last proposal for inspection. It never applies changes.
"""

from __future__ import annotations

from astra.coding.agent import LocalCodingAgent
from astra.coding.contracts import ChangeProposal, CodingAgent, FileChange

__all__ = ["FileChange", "ChangeProposal", "CodingAgent", "LocalCodingAgent"]
