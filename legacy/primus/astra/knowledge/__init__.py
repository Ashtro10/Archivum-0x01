"""Knowledge Engine.

Classifies information before storage and keeps it retrievable across model
upgrades. Implemented in B-004 per ADR-0008.

Key components:

* :class:`KnowledgeCategory` — the 13 constitutional categories.
* :class:`KnowledgeVerification` — trust level for a knowledge record.
* :class:`Classification` — result of classifying content.
* :class:`KnowledgeRecord` — one classified, stored piece of knowledge.
* :class:`KnowledgeClassifier` — abstract contract; classify before storing.
* :class:`KnowledgeStore` — abstract contract; durable storage.
* :class:`KeywordKnowledgeClassifier` — deterministic implementation.
* :class:`SQLiteKnowledgeStore` — SQLite-backed implementation.
"""

from __future__ import annotations

from astra.knowledge.classifier import KeywordKnowledgeClassifier
from astra.knowledge.ingest import ingest_text
from astra.knowledge.contracts import (
    Classification,
    KnowledgeCategory,
    KnowledgeClassifier,
    KnowledgeRecord,
    KnowledgeStore,
    KnowledgeVerification,
)
from astra.knowledge.errors import (
    DuplicateKnowledgeError,
    KnowledgeStoreError,
    VerifiedKnowledgeProtectedError,
)
from astra.knowledge.sqlite_store import SQLiteKnowledgeStore

__all__ = [
    "KnowledgeCategory",
    "KnowledgeVerification",
    "Classification",
    "KnowledgeRecord",
    "KnowledgeClassifier",
    "KnowledgeStore",
    "KeywordKnowledgeClassifier",
    "SQLiteKnowledgeStore",
    "ingest_text",
    "KnowledgeStoreError",
    "DuplicateKnowledgeError",
    "VerifiedKnowledgeProtectedError",
]
