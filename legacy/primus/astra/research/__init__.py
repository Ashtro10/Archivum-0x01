"""Research Agent (B-009, ADR-0011, B-041).

Implemented: ``LocalResearchAgent`` with ``ResearchSource`` abstraction.
Local sources: ``TextSource``, ``DocumentSource``, ``DirectoryCorpusSource``,
``KnowledgeSource``, ``MemorySource``. Controlled HTTP/HTTPS retrieval lives
in ``astra.research.http`` and is registered only when
``research.allow_external`` is enabled.

The agent never writes Memory or Knowledge. Networking is not a Tool.
"""

from __future__ import annotations

from astra.research.agent import LocalResearchAgent
from astra.research.contracts import Finding, ResearchAgent, ResearchReport
from astra.research.errors import ResearchError, SourceError
from astra.research.source import (
    DirectoryCorpusSource,
    DocumentSource,
    KnowledgeSource,
    MemorySource,
    ResearchSource,
    TextSource,
)

__all__ = [
    "Finding",
    "ResearchReport",
    "ResearchAgent",
    "LocalResearchAgent",
    "ResearchSource",
    "TextSource",
    "DocumentSource",
    "DirectoryCorpusSource",
    "KnowledgeSource",
    "MemorySource",
    "ResearchError",
    "SourceError",
]
