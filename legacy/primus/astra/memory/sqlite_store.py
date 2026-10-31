"""SQLite implementation of :class:`astra.memory.contracts.MemoryStore`.

Implements ADR-0003. This database is the source of truth for memory; any
vector index built over it is derived and rebuildable from these rows
(ADR-0001 D1).

Two constitutional rules are enforced by the database itself, with the Python
checks below existing only to produce readable errors:

* verified knowledge is not duplicated, via a unique index, and
* unknown information never overwrites verified information, via a trigger.
"""

from __future__ import annotations

import sqlite3
import unicodedata
import uuid
from datetime import datetime, timezone
from hashlib import sha256
from pathlib import Path
from typing import Iterator, Sequence

from astra.logging import get_logger
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
    VerifiedRecordProtectedError,
)
from astra.memory.schema import FTS_TABLE, apply_migrations, ensure_fts
from astra.memory.user_facts import (
    USER_DURABLE_SOURCES,
    field_key_tag,
    is_user_durable,
    query_field_key,
    score_user_fact,
)

_LOGGER = get_logger(__name__)

TIMESTAMP_FORMAT = "%Y-%m-%dT%H:%M:%S.%fZ"

_COLUMNS = (
    "record_id, category, content, content_hash, verification, "
    "source, created_at, updated_at"
)


def normalise(content: str) -> str:
    """Normalise content for hashing (ADR-0003 D2).

    Unicode NFC, outer whitespace stripped, internal whitespace runs collapsed.
    Deliberately no case folding and no punctuation stripping: merging two
    records that only look alike is silent and lossy, while keeping two near
    duplicates is visible and fixable.
    """
    return " ".join(unicodedata.normalize("NFC", content).split())


def content_fingerprint(content: str) -> str:
    """Return the SHA-256 hex digest of the normalised content."""
    return sha256(normalise(content).encode("utf-8")).hexdigest()


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _format_timestamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).strftime(TIMESTAMP_FORMAT)


def _parse_timestamp(value: str) -> datetime:
    return datetime.strptime(value, TIMESTAMP_FORMAT).replace(tzinfo=timezone.utc)


class SQLiteMemoryStore(MemoryStore):
    """Durable memory storage backed by a single SQLite file.

    Example:
        >>> store = SQLiteMemoryStore(path)      # doctest: +SKIP
        >>> store.initialise()                   # doctest: +SKIP
        >>> record_id = store.store(record)      # doctest: +SKIP

    The store owns one connection and is not thread-safe, which matches the
    single-user, single-process design in ADR-0001 D3.
    """

    def __init__(self, database_path: Path | str, *, allow_cross_thread: bool = False) -> None:
        self._path = Path(database_path)
        self._connection: sqlite3.Connection | None = None
        self._search_mode = "uninitialised"
        self._allow_cross_thread = allow_cross_thread

    # ------------------------------------------------------------------
    # lifecycle
    # ------------------------------------------------------------------

    def initialise(self) -> None:
        """Create or migrate the schema. Idempotent."""
        if self._connection is None:
            self._connection = self._connect()

        version = apply_migrations(self._connection)
        self._search_mode = "fts5" if ensure_fts(self._connection) else "like"

        if self._search_mode == "like":
            _LOGGER.warning(
                "FTS5 is unavailable in this SQLite build; memory search has "
                "fallen back to substring matching"
            )
        _LOGGER.debug(
            "memory store ready at %s (schema v%s, search=%s)",
            self._path,
            version,
            self._search_mode,
        )

    def close(self) -> None:
        """Flush WAL then close. Safe to call more than once."""
        if self._connection is not None:
            try:
                self._connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                self._connection.commit()
            except sqlite3.Error:
                pass
            self._connection.close()
            self._connection = None

    def __enter__(self) -> "SQLiteMemoryStore":
        self.initialise()
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()

    @property
    def search_mode(self) -> str:
        """Which search implementation is active: ``fts5`` or ``like``."""
        return self._search_mode

    @property
    def database_path(self) -> Path:
        """Location of the SQLite file."""
        return self._path

    def _connect(self) -> sqlite3.Connection:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(
            self._path,
            check_same_thread=not self._allow_cross_thread,
        )
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA journal_mode = WAL")
        connection.execute("PRAGMA synchronous = FULL")
        connection.execute("PRAGMA foreign_keys = ON")
        return connection

    def _require_connection(self) -> sqlite3.Connection:
        if self._connection is None:
            raise MemoryStoreError(
                "memory store is not initialised; call initialise() first"
            )
        return self._connection

    # ------------------------------------------------------------------
    # writes
    # ------------------------------------------------------------------

    def store(self, record: MemoryRecord) -> str:
        """Persist a record and return its identifier.

        Inserts when the record has no identifier or an unknown one, and
        updates otherwise.

        Raises:
            DuplicateRecordError: if the same content already exists in the
                same category.
            VerifiedRecordProtectedError: if unverified content would overwrite
                a verified record.
            MemoryStoreError: if the content is empty.
        """
        connection = self._require_connection()

        if not normalise(record.content):
            raise MemoryStoreError("memory content must not be empty")

        fingerprint = content_fingerprint(record.content)
        existing = self._row(record.record_id) if record.record_id else None
        now = _now()

        try:
            if existing is None:
                record_id = record.record_id or str(uuid.uuid4())
                self._guard_duplicate(record, fingerprint, exclude=None)
                self._insert(record, record_id, fingerprint, now)
            else:
                record_id = record.record_id
                self._guard_verified(existing, record)
                self._guard_duplicate(record, fingerprint, exclude=record_id)
                self._update(record, record_id, fingerprint, now)

            self._replace_tags(record_id, record.tags)
            connection.commit()
        except sqlite3.IntegrityError as exc:
            connection.rollback()
            raise self._translate(exc, record) from exc
        except Exception:
            connection.rollback()
            raise

        return record_id

    def _insert(
        self,
        record: MemoryRecord,
        record_id: str,
        fingerprint: str,
        now: datetime,
    ) -> None:
        created = record.created_at or now
        self._require_connection().execute(
            f"INSERT INTO memory_records ({_COLUMNS}) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (
                record_id,
                record.category.value,
                record.content,
                fingerprint,
                record.verification.value,
                record.source,
                _format_timestamp(created),
                _format_timestamp(now),
            ),
        )

    def _update(
        self,
        record: MemoryRecord,
        record_id: str,
        fingerprint: str,
        now: datetime,
    ) -> None:
        self._require_connection().execute(
            "UPDATE memory_records SET category = ?, content = ?, "
            "content_hash = ?, verification = ?, source = ?, updated_at = ? "
            "WHERE record_id = ?",
            (
                record.category.value,
                record.content,
                fingerprint,
                record.verification.value,
                record.source,
                _format_timestamp(now),
                record_id,
            ),
        )

    def _replace_tags(self, record_id: str, tags: Sequence[str]) -> None:
        connection = self._require_connection()
        connection.execute("DELETE FROM memory_tags WHERE record_id = ?", (record_id,))
        connection.executemany(
            "INSERT INTO memory_tags (record_id, tag) VALUES (?, ?)",
            [(record_id, tag) for tag in dict.fromkeys(tags)],
        )

    def _guard_duplicate(
        self, record: MemoryRecord, fingerprint: str, *, exclude: str | None
    ) -> None:
        """Report duplication clearly before the unique index does it bluntly."""
        query = (
            "SELECT record_id FROM memory_records "
            "WHERE category = ? AND content_hash = ?"
        )
        parameters: list[object] = [record.category.value, fingerprint]
        if exclude is not None:
            query += " AND record_id <> ?"
            parameters.append(exclude)

        row = self._require_connection().execute(query, parameters).fetchone()
        if row is not None:
            raise DuplicateRecordError(
                f"identical content already stored in category "
                f"{record.category.value!r} as record {row['record_id']}"
            )

    @staticmethod
    def _guard_verified(existing: sqlite3.Row, record: MemoryRecord) -> None:
        """Mirror the database trigger with an explanatory error."""
        was_verified = existing["verification"] == VerificationState.VERIFIED.value
        becoming_unverified = record.verification is VerificationState.UNVERIFIED
        if was_verified and becoming_unverified:
            raise VerifiedRecordProtectedError(
                f"record {existing['record_id']} is verified; unverified "
                "content may not overwrite it"
            )

    @staticmethod
    def _translate(error: sqlite3.IntegrityError, record: MemoryRecord) -> Exception:
        message = str(error)
        if "verified record" in message:
            return VerifiedRecordProtectedError(message)
        if "idx_memory_content_unique" in message or "UNIQUE" in message:
            return DuplicateRecordError(
                f"identical content already stored in category "
                f"{record.category.value!r}"
            )
        return MemoryStoreError(message)

    # ------------------------------------------------------------------
    # reads
    # ------------------------------------------------------------------

    def get(self, record_id: str) -> MemoryRecord | None:
        """Return one record, or ``None`` if it does not exist."""
        row = self._row(record_id)
        return None if row is None else self._to_record(row)

    def search(
        self,
        query: str,
        *,
        category: MemoryCategory | None = None,
        limit: int = 20,
    ) -> Sequence[MemoryRecord]:
        """Return records matching a textual query, most relevant first.

        Uses FTS5 when available and substring matching otherwise; see
        :attr:`search_mode`.
        """
        connection = self._require_connection()
        terms = normalise(query)
        if not terms:
            return ()

        if self._search_mode == "fts5":
            sql = (
                f"SELECT r.* FROM {FTS_TABLE} f "
                "JOIN memory_records r ON r.record_id = f.record_id "
                f"WHERE {FTS_TABLE} MATCH ?"
            )
            parameters: list[object] = [_as_match_expression(terms)]
            if category is not None:
                sql += " AND r.category = ?"
                parameters.append(category.value)
            sql += " ORDER BY rank LIMIT ?"
        else:
            sql = (
                "SELECT * FROM memory_records "
                "WHERE content LIKE ? ESCAPE '\\'"
            )
            parameters = [f"%{_escape_like(terms)}%"]
            if category is not None:
                sql += " AND category = ?"
                parameters.append(category.value)
            sql += " ORDER BY updated_at DESC LIMIT ?"

        parameters.append(limit)
        rows = connection.execute(sql, parameters).fetchall()
        return tuple(self._to_record(row) for row in rows)

    def search_user_durable(
        self,
        query: str,
        *,
        field_key: str | None = None,
        limit: int = 10,
    ) -> Sequence[MemoryRecord]:
        """Search only explicit, durable user facts.

        This is intentionally separate from :meth:`search`: the general memory
        store also contains research, project knowledge, documentation and other
        records that must never be treated as personal memory.  The method also
        ranks an exact ``key:user.*`` field above generic lexical similarity.
        """
        if limit <= 0:
            return ()
        requested = field_key or query_field_key(query)
        candidates: list[MemoryRecord] = []
        for record in self.iter_records():
            if not is_user_durable(record):
                continue
            if requested and field_key_tag(requested) not in set(record.tags):
                # Preserve legacy records whose field can still be inferred.
                from_key = None
                try:
                    from astra.memory.user_facts import record_field_key
                    from_key = record_field_key(record)
                except Exception:
                    pass
                if from_key != requested:
                    continue
            candidates.append(record)

        if not candidates:
            return ()
        ranked = sorted(
            candidates,
            key=lambda record: score_user_fact(query, record, requested),
            reverse=True,
        )
        return tuple(ranked[:limit])

    def iter_records(
        self, *, category: MemoryCategory | None = None
    ) -> Iterator[MemoryRecord]:
        """Stream stored records, oldest first.

        Rows are yielded as the cursor produces them so that a rebuild of the
        derived vector index never loads the whole table into memory.
        """
        connection = self._require_connection()
        sql = "SELECT * FROM memory_records"
        parameters: tuple[object, ...] = ()
        if category is not None:
            sql += " WHERE category = ?"
            parameters = (category.value,)
        sql += " ORDER BY created_at"

        cursor = connection.execute(sql, parameters)
        try:
            for row in cursor:
                yield self._to_record(row)
        finally:
            cursor.close()

    def _row(self, record_id: str) -> sqlite3.Row | None:
        return (
            self._require_connection()
            .execute("SELECT * FROM memory_records WHERE record_id = ?", (record_id,))
            .fetchone()
        )

    def _tags(self, record_id: str) -> tuple[str, ...]:
        rows = self._require_connection().execute(
            "SELECT tag FROM memory_tags WHERE record_id = ? ORDER BY tag",
            (record_id,),
        )
        return tuple(row["tag"] for row in rows)

    def _to_record(self, row: sqlite3.Row) -> MemoryRecord:
        return MemoryRecord(
            record_id=row["record_id"],
            category=MemoryCategory(row["category"]),
            content=row["content"],
            verification=VerificationState(row["verification"]),
            source=row["source"],
            created_at=_parse_timestamp(row["created_at"]),
            updated_at=_parse_timestamp(row["updated_at"]),
            tags=self._tags(row["record_id"]),
        )

    # ------------------------------------------------------------------
    # embedding provenance (ADR-0012 D5)
    # ------------------------------------------------------------------

    def set_embedding_provenance(
        self,
        record_id: str,
        model: str,
        version: str,
        dim: int,
        embedded_at: datetime | None = None,
    ) -> None:
        """Write ADR-0003 D12 columns for ``record_id``.

        Raises:
            MemoryStoreError: if no row exists for ``record_id``.
        """
        connection = self._require_connection()
        stamped = _format_timestamp(embedded_at or _now())
        cursor = connection.execute(
            "UPDATE memory_records SET embedding_model = ?, "
            "embedding_version = ?, embedding_dim = ?, embedded_at = ? "
            "WHERE record_id = ?",
            (model, version, int(dim), stamped, record_id),
        )
        if cursor.rowcount == 0:
            raise MemoryStoreError(
                f"cannot set embedding provenance: unknown record {record_id!r}"
            )
        connection.commit()

    def get_embedding_provenance(
        self, record_id: str
    ) -> EmbeddingProvenance | None:
        """Return provenance for ``record_id``, or ``None`` if unset or missing."""
        row = self._row(record_id)
        if row is None:
            return None
        model = row["embedding_model"]
        if model is None:
            return None
        embedded_at = row["embedded_at"]
        return EmbeddingProvenance(
            record_id=record_id,
            embedding_model=model,
            embedding_version=row["embedding_version"] or "",
            embedding_dim=int(row["embedding_dim"] or 0),
            embedded_at=(
                _parse_timestamp(embedded_at) if embedded_at else None
            ),
        )

    def clear_embedding_provenance(self, record_id: str) -> None:
        """Null the four provenance columns. No-op if the row does not exist."""
        connection = self._require_connection()
        connection.execute(
            "UPDATE memory_records SET embedding_model = NULL, "
            "embedding_version = NULL, embedding_dim = NULL, "
            "embedded_at = NULL WHERE record_id = ?",
            (record_id,),
        )
        connection.commit()

    # ------------------------------------------------------------------
    # deletion
    # ------------------------------------------------------------------

    def delete(self, record_id: str) -> bool:
        """Remove a record, its tags, and its search entry.

        Deleting memory is a permanent change and requires human approval
        upstream; this method performs no approval check of its own.
        """
        connection = self._require_connection()
        cursor = connection.execute(
            "DELETE FROM memory_records WHERE record_id = ?", (record_id,)
        )
        connection.commit()
        return cursor.rowcount > 0


def _as_match_expression(terms: str) -> str:
    """Quote each term so that user text cannot be read as FTS5 syntax."""
    return " ".join(f'"{term.replace(chr(34), "")}"' for term in terms.split())


def _escape_like(terms: str) -> str:
    return terms.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


__all__ = [
    "SQLiteMemoryStore",
    "TIMESTAMP_FORMAT",
    "normalise",
    "content_fingerprint",
]
