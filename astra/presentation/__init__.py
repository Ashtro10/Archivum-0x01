"""Presentation Layer — ApplicationState assembly and talk orchestration.

This package sits above the engine and agent layers. ``AstraStateProvider``
reads engines to assemble status. ``ConversationOrchestrator`` (B-039) is the
production user-turn coordinator: it retrieves and persists Memory Engine
records and calls existing controller capabilities, then the LLM backend.
It does not reimplement those engines. It does not dispatch testing,
evaluation, or deployment.

The ``ui/`` directory imports from here; no ``astra.*`` engine imports
are allowed inside ``ui/`` (ADR-0006 D1, ADR-0038 D1).
"""

from astra.presentation.provider import AstraStateProvider
from astra.presentation.progress import MILESTONES, Milestone

__all__ = ["AstraStateProvider", "MILESTONES", "Milestone"]
