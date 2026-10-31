"""Classify-then-store helper for the Knowledge Engine (B-040).

Does not create a second database. Uses the existing classifier and store.
Research still does not write knowledge; the caller decides (ADR-0011 D6).
"""

from __future__ import annotations

from astra.knowledge.contracts import (
    KnowledgeClassifier,
    KnowledgeRecord,
    KnowledgeStore,
    KnowledgeVerification,
)
from astra.knowledge.errors import DuplicateKnowledgeError, KnowledgeStoreError


def ingest_text(
    store: KnowledgeStore,
    classifier: KnowledgeClassifier,
    content: str,
    *,
    source: str,
    note: str = "",
) -> str:
    """Classify ``content`` and store it. Empty string if duplicate or invalid."""
    text = str(content or "").strip()
    if not text:
        return ""
    classification = classifier.classify(text)
    record = KnowledgeRecord(
        record_id="",
        category=classification.category,
        content=text,
        source=source,
        confidence=classification.confidence,
        verification=KnowledgeVerification.UNVERIFIED,
        acceptance_note=note or classification.rationale,
        tags=("ingest",),
    )
    try:
        return str(store.store(record) or "")
    except DuplicateKnowledgeError:
        return ""
    except KnowledgeStoreError:
        return ""


__all__ = ["ingest_text"]
