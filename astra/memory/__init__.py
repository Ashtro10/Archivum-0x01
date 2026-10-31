"""Memory Engine (B-001 + B-003 + B-013).

SQLite is the source of truth; the FAISS vector index is derived and
disposable (ADR-0001 D1). Deleting the index and rebuilding from SQLite
must always be possible.

Implemented:
  B-001: SQLiteMemoryStore (FTS5 search)
  B-003: FaissVectorIndex (IndexFlatIP, L2-normalized, rebuild_index)
  B-013: MemoryIndexer (embed-on-write, provenance, semantic search)
"""

from __future__ import annotations

from astra.memory.contracts import (
    EmbeddingProvenance,
    MemoryCategory,
    MemoryRecord,
    MemoryStore,
    VerificationState,
)
from astra.memory.errors import (
    DuplicateRecordError,
    MemoryStoreError,
    VectorIndexError,
    VerifiedRecordProtectedError,
)
from astra.memory.faiss_index import FaissVectorIndex, rebuild_index
from astra.memory.indexer import MemoryIndexer
from astra.memory.schema import SCHEMA_VERSION
from astra.memory.sqlite_store import SQLiteMemoryStore
from astra.memory.vector import VectorIndex, VectorMatch
from astra.memory.user_facts import (
    USER_FIRST_CRUSH,
    USER_NAME,
    USER_PREFERRED_NAME,
    USER_PREFERENCE,
    USER_PROJECT,
    filter_user_durable,
    is_user_durable,
    query_field_key,
    record_value,
    select_user_fact,
)

__all__ = [
    "MemoryCategory",
    "VerificationState",
    "EmbeddingProvenance",
    "MemoryRecord",
    "MemoryStore",
    "SQLiteMemoryStore",
    "SCHEMA_VERSION",
    "MemoryStoreError",
    "DuplicateRecordError",
    "VerifiedRecordProtectedError",
    "VectorIndexError",
    "VectorIndex",
    "VectorMatch",
    "FaissVectorIndex",
    "rebuild_index",
    "MemoryIndexer",
    "USER_NAME",
    "USER_PREFERRED_NAME",
    "USER_FIRST_CRUSH",
    "USER_PREFERENCE",
    "USER_PROJECT",
    "filter_user_durable",
    "is_user_durable",
    "query_field_key",
    "record_value",
    "select_user_fact",
]
