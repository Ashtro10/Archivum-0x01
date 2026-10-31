"""Security Agent.

B-015: :class:`PolicySecurityAgent` implements deny-by-default policy
(ADR-0014). It does not execute tools, open sockets, or write files.
"""

from __future__ import annotations

from astra.security.agent import PolicySecurityAgent, RESEARCH_NETWORK_SENTINEL
from astra.security.contracts import (
    PolicyDecision,
    ProcessSpec,
    SecurityAgent,
    Verdict,
)

__all__ = [
    "Verdict",
    "PolicyDecision",
    "ProcessSpec",
    "SecurityAgent",
    "PolicySecurityAgent",
    "RESEARCH_NETWORK_SENTINEL",
]
