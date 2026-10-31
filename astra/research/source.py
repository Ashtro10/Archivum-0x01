"""Research source abstraction and local implementations (ADR-0011).

A ``ResearchSource`` is anything that can answer questions with evidence.
All implementations in this module are local-only. Controlled HTTP/HTTPS
retrieval lives in :mod:`astra.research.http` and is not imported here.

Confidence semantics per source (ADR-0011 D3):

- ``TextSource`` / ``DocumentSource``: fraction of question keywords found in
  a paragraph (0.0–1.0).
- ``KnowledgeSource``: the stored ``KnowledgeRecord.confidence`` (classifier
  confidence in the category assignment).
- ``MemorySource``: FTS5 hits use fixed ``0.5``. Optional semantic-only hits
  use clipped cosine similarity from the vector index (ADR-0012 D11).
"""

from __future__ import annotations

import re
from abc import ABC, abstractmethod
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence, TYPE_CHECKING

from astra.logging import get_logger
from astra.research.contracts import Finding

if TYPE_CHECKING:
    from astra.knowledge.contracts import KnowledgeStore
    from astra.memory.contracts import MemoryStore
    from astra.memory.indexer import MemoryIndexer

_LOGGER = get_logger(__name__)

# Confidence assigned to every MemorySource hit (see module docstring).
MEMORY_HIT_CONFIDENCE = 0.5


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _keyword_confidence(question: str, text: str) -> float:
    """Fraction of question keywords (length ≥3) found in ``text``."""
    words = {w.lower() for w in re.findall(r"\b\w{3,}\b", question)}
    if not words:
        return 0.0
    text_lower = text.lower()
    matched = sum(1 for w in words if w in text_lower)
    return matched / len(words)


def _split_paragraphs(text: str) -> list[str]:
    """Split text into non-empty paragraphs on blank lines."""
    return [p.strip() for p in re.split(r"\n{2,}", text) if p.strip()]


# ---------------------------------------------------------------------------
# Abstract contract
# ---------------------------------------------------------------------------

class ResearchSource(ABC):
    """Provides evidence for a research question (ADR-0011 D1).

    Implementations must be local-only unless a future ADR explicitly
    authorises network access for a specific source type.
    """

    requires_network: bool = False

    @property
    @abstractmethod
    def name(self) -> str:
        """Unique identifier for this source."""
        raise NotImplementedError

    @abstractmethod
    def is_available(self) -> bool:
        """Whether this source can currently produce results. Never raises."""
        raise NotImplementedError

    @abstractmethod
    def query(self, question: str, *, limit: int = 10) -> Sequence[Finding]:
        """Return findings relevant to ``question``.

        Must not raise — return an empty sequence on failure. Failures are
        logged at WARNING level so the agent can continue with other sources.
        """
        raise NotImplementedError


# ---------------------------------------------------------------------------
# TextSource
# ---------------------------------------------------------------------------

class TextSource(ResearchSource):
    """Inline text research source.

    Splits the provided text into paragraphs and returns those where at least
    one question keyword appears.

    Confidence = fraction of 3+-letter question keywords found in paragraph.
    Always available (the text is held in memory).
    """

    def __init__(self, source_name: str, text: str) -> None:
        self._name = source_name
        self._paragraphs = _split_paragraphs(text)

    @property
    def name(self) -> str:
        return self._name

    def is_available(self) -> bool:
        return bool(self._paragraphs)

    def query(self, question: str, *, limit: int = 10) -> Sequence[Finding]:
        if not question.strip():
            return ()
        results: list[Finding] = []
        for para in self._paragraphs:
            conf = _keyword_confidence(question, para)
            if conf > 0.0:
                results.append(
                    Finding(
                        claim=para,
                        source=self._name,
                        confidence=conf,
                        retrieved_at=_now(),
                    )
                )
        # Sort highest confidence first, take up to limit.
        results.sort(key=lambda f: f.confidence, reverse=True)
        return tuple(results[:limit])


# ---------------------------------------------------------------------------
# DocumentSource
# ---------------------------------------------------------------------------

class DocumentSource(ResearchSource):
    """Local file research source.

    Reads a plain-text or Markdown file, splits into paragraphs, and returns
    those matching the question by keyword overlap.

    Confidence = fraction of 3+-letter question keywords found in paragraph.
    Available only when the file exists and is readable.
    """

    def __init__(self, path: Path | str) -> None:
        self._path = Path(path)

    @property
    def name(self) -> str:
        return str(self._path)

    def is_available(self) -> bool:
        try:
            return self._path.is_file()
        except OSError:
            return False

    def query(self, question: str, *, limit: int = 10) -> Sequence[Finding]:
        if not question.strip():
            return ()
        try:
            text = self._path.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            _LOGGER.warning("DocumentSource %s: read failed: %s", self._path, exc)
            return ()
        paragraphs = _split_paragraphs(text)
        results: list[Finding] = []
        for para in paragraphs:
            conf = _keyword_confidence(question, para)
            if conf > 0.0:
                results.append(
                    Finding(
                        claim=para,
                        source=str(self._path),
                        confidence=conf,
                        retrieved_at=_now(),
                    )
                )
        results.sort(key=lambda f: f.confidence, reverse=True)
        return tuple(results[:limit])


# ---------------------------------------------------------------------------
# DirectoryCorpusSource
# ---------------------------------------------------------------------------

_CORPUS_SKIP = frozenset(
    {".git", "data", "logs", "models", "_internal", "__pycache__", ".venv", "venv"}
)


class DirectoryCorpusSource(ResearchSource):
    """Local directory of Markdown/text files. No network. No subprocess."""

    def __init__(
        self,
        root: Path | str,
        *,
        suffixes: tuple[str, ...] = (".md", ".txt"),
        max_files: int = 24,
        max_bytes: int = 256_000,
    ) -> None:
        self._root = Path(root)
        self._suffixes = tuple(item.lower() for item in suffixes)
        self._max_files = max_files
        self._max_bytes = max_bytes

    @property
    def name(self) -> str:
        return f"corpus:{self._root.name}"

    def is_available(self) -> bool:
        try:
            return self._root.is_dir()
        except OSError:
            return False

    def query(self, question: str, *, limit: int = 10) -> Sequence[Finding]:
        if not question.strip() or not self.is_available():
            return ()
        results: list[Finding] = []
        for path in self._iter_files():
            try:
                hits = DocumentSource(path).query(question, limit=limit)
            except Exception as exc:
                _LOGGER.warning("DirectoryCorpusSource %s: %s", path, exc)
                continue
            results.extend(hits)
        results.sort(key=lambda f: f.confidence, reverse=True)
        return tuple(results[:limit])

    def _iter_files(self) -> list[Path]:
        found: list[Path] = []
        try:
            direct = sorted(self._root.glob("*.md")) + sorted(self._root.glob("*.txt"))
            nested = []
            adr = self._root / "adr"
            if adr.is_dir():
                nested = sorted(adr.glob("*.md"))
            for path in (*direct, *nested):
                if len(found) >= self._max_files:
                    break
                if any(part in _CORPUS_SKIP for part in path.parts):
                    continue
                if path.suffix.lower() not in self._suffixes:
                    continue
                try:
                    if not path.is_file() or path.stat().st_size > self._max_bytes:
                        continue
                except OSError:
                    continue
                found.append(path)
        except OSError as exc:
            _LOGGER.warning("DirectoryCorpusSource list failed: %s", exc)
        return found


# ---------------------------------------------------------------------------
# KnowledgeSource
# ---------------------------------------------------------------------------

class KnowledgeSource(ResearchSource):
    """Research source backed by the Knowledge Engine.

    Delegates to :meth:`KnowledgeStore.search`. Confidence is taken directly
    from the stored ``KnowledgeRecord.confidence`` (the classifier's confidence
    in the category assignment at storage time).
    """

    def __init__(self, store: "KnowledgeStore") -> None:
        self._store = store

    @property
    def name(self) -> str:
        return "knowledge"

    def is_available(self) -> bool:
        try:
            # A quick structural probe: store exists and is initialised.
            self._store.search("", limit=0)
            return True
        except Exception:
            return False

    def query(self, question: str, *, limit: int = 10) -> Sequence[Finding]:
        if not question.strip():
            return ()
        try:
            records = self._store.search(question, limit=limit)
        except Exception as exc:
            _LOGGER.warning("KnowledgeSource query failed: %s", exc)
            return ()
        now = _now()
        return tuple(
            Finding(
                claim=r.content,
                source=f"knowledge:{r.source}" if r.source else f"knowledge:{r.record_id}",
                confidence=r.confidence,
                retrieved_at=now,
            )
            for r in records
        )


# ---------------------------------------------------------------------------
# MemorySource
# ---------------------------------------------------------------------------

class MemorySource(ResearchSource):
    """Research source backed by the Memory Engine.

    Delegates to :meth:`MemoryStore.search` (FTS5). When an optional
    :class:`~astra.memory.indexer.MemoryIndexer` is supplied and the embedding
    backend is available, semantic hits are merged in. FTS5 remains the
    baseline: it always runs, and FTS hits keep ``MEMORY_HIT_CONFIDENCE``.
    Semantic retrieval does not replace keyword search (ADR-0012 D11).
    """

    def __init__(
        self,
        store: "MemoryStore",
        indexer: "MemoryIndexer | None" = None,
    ) -> None:
        self._store = store
        self._indexer = indexer

    @property
    def name(self) -> str:
        return "memory"

    def is_available(self) -> bool:
        try:
            self._store.search("", limit=0)
            return True
        except Exception:
            return False

    def query(self, question: str, *, limit: int = 10) -> Sequence[Finding]:
        if not question.strip():
            return ()
        try:
            records = self._store.search(question, limit=limit)
        except Exception as exc:
            _LOGGER.warning("MemorySource query failed: %s", exc)
            return ()
        now = _now()
        seen: set[str] = set()
        findings: list[Finding] = []
        for r in records:
            seen.add(r.record_id)
            findings.append(
                Finding(
                    claim=r.content,
                    source=f"memory:{r.source}" if r.source else f"memory:{r.record_id}",
                    confidence=MEMORY_HIT_CONFIDENCE,
                    retrieved_at=now,
                )
            )

        indexer = self._indexer
        if indexer is not None and len(findings) < limit:
            try:
                if indexer.backend_available():
                    for rec, score in indexer.search_with_scores(
                        question, limit=limit
                    ):
                        if rec.record_id in seen:
                            continue
                        seen.add(rec.record_id)
                        findings.append(
                            Finding(
                                claim=rec.content,
                                source=(
                                    f"memory:{rec.source}"
                                    if rec.source
                                    else f"memory:{rec.record_id}"
                                ),
                                confidence=max(0.0, min(1.0, float(score))),
                                retrieved_at=now,
                            )
                        )
                        if len(findings) >= limit:
                            break
            except Exception as exc:
                _LOGGER.warning("MemorySource semantic merge failed: %s", exc)

        return tuple(findings[:limit])


__all__ = [
    "ResearchSource",
    "TextSource",
    "DocumentSource",
    "DirectoryCorpusSource",
    "KnowledgeSource",
    "MemorySource",
    "MEMORY_HIT_CONFIDENCE",
]
