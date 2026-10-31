"""SQLite implementation of :class:`astra.knowledge.contracts.KnowledgeStore`.

Implements ADR-0008. This database is the source of truth for knowledge; any
vector index built over it is derived and rebuildable from these rows.

Two constitutional rules are enforced by the database itself, with the Python
checks existing only to produce readable errors:

* Verified knowledge is not duplicated, via a unique ``(category, content_hash)``
  index.
* Unknown information never overwrites verified information, via a
  ``BEFORE UPDATE`` trigger.

Classification is NOT performed here. The caller is responsible for classifying
content before constructing a ``KnowledgeRecord`` and passing it to
:meth:`store` (ADR-0008 D9).
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
from astra.knowledge.contracts import (
    KnowledgeCategory,
    KnowledgeRecord,
    KnowledgeStore,
    KnowledgeVerification,
)
from astra.knowledge.errors import (
    DuplicateKnowledgeError,
    KnowledgeStoreError,
    VerifiedKnowledgeProtectedError,
)
from astra.knowledge.schema import FTS_TABLE, apply_migrations, ensure_fts

_LOGGER = get_logger(__name__)

TIMESTAMP_FORMAT = "%Y-%m-%dT%H:%M:%S.%fZ"

_COLUMNS = (
    "record_id, category, content, content_hash, verification, "
    "source, confidence, acceptance_note, created_at, updated_at"
)


def normalise(content: str) -> str:
    """Normalise content for hashing (mirrors ADR-0003 D2 for knowledge).

    Unicode NFC, outer whitespace stripped, internal whitespace runs collapsed.
    No case folding, no punctuation stripping.
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


class SQLiteKnowledgeStore(KnowledgeStore):
    """Durable knowledge storage backed by a single SQLite file.

    Example:
        >>> store = SQLiteKnowledgeStore(path)      # doctest: +SKIP
        >>> store.initialise()                       # doctest: +SKIP
        >>> record_id = store.store(record)          # doctest: +SKIP

    The store owns one connection and is not thread-safe, matching the
    single-user, single-process design in ADR-0001 D3.

    Classification is the caller's responsibility; the store enforces storage
    invariants only (ADR-0008 D9).
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
                "FTS5 unavailable in this SQLite build; knowledge search "
                "fallen back to substring matching"
            )
        _LOGGER.debug(
            "knowledge store ready at %s (schema v%s, search=%s)",
            self._path,
            version,
            self._search_mode,
        )

    def close(self) -> None:
        """Close the connection. Safe to call more than once."""
        if self._connection is not None:
            self._connection.close()
            self._connection = None

    def __enter__(self) -> "SQLiteKnowledgeStore":
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
            raise KnowledgeStoreError(
                "knowledge store is not initialised; call initialise() first"
            )
        return self._connection

    # ------------------------------------------------------------------
    # writes
    # ------------------------------------------------------------------

    def store(self, record: KnowledgeRecord) -> str:
        """Persist a classified record and return its identifier.

        Inserts when the record has no identifier or an unknown one, and
        updates otherwise.

        Raises:
            DuplicateKnowledgeError: if the same content already exists in the
                same category.
            VerifiedKnowledgeProtectedError: if unverified content would
                overwrite a verified record.
            KnowledgeStoreError: if the content is empty.
        """
        connection = self._require_connection()

        if not normalise(record.content):
            raise KnowledgeStoreError("knowledge content must not be empty")

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
        record: KnowledgeRecord,
        record_id: str,
        fingerprint: str,
        now: datetime,
    ) -> None:
        created = record.created_at or now
        self._require_connection().execute(
            f"INSERT INTO knowledge_records ({_COLUMNS}) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                record_id,
                record.category.value,
                record.content,
                fingerprint,
                record.verification.value,
                record.source,
                record.confidence,
                record.acceptance_note,
                _format_timestamp(created),
                _format_timestamp(now),
            ),
        )

    def _update(
        self,
        record: KnowledgeRecord,
        record_id: str,
        fingerprint: str,
        now: datetime,
    ) -> None:
        self._require_connection().execute(
            "UPDATE knowledge_records SET category = ?, content = ?, "
            "content_hash = ?, verification = ?, source = ?, "
            "confidence = ?, acceptance_note = ?, updated_at = ? "
            "WHERE record_id = ?",
            (
                record.category.value,
                record.content,
                fingerprint,
                record.verification.value,
                record.source,
                record.confidence,
                record.acceptance_note,
                _format_timestamp(now),
                record_id,
            ),
        )

    def _replace_tags(self, record_id: str, tags: Sequence[str]) -> None:
        connection = self._require_connection()
        connection.execute(
            "DELETE FROM knowledge_tags WHERE record_id = ?", (record_id,)
        )
        connection.executemany(
            "INSERT INTO knowledge_tags (record_id, tag) VALUES (?, ?)",
            [(record_id, tag) for tag in dict.fromkeys(tags)],
        )

    def _guard_duplicate(
        self, record: KnowledgeRecord, fingerprint: str, *, exclude: str | None
    ) -> None:
        query = (
            "SELECT record_id FROM knowledge_records "
            "WHERE category = ? AND content_hash = ?"
        )
        parameters: list[object] = [record.category.value, fingerprint]
        if exclude is not None:
            query += " AND record_id <> ?"
            parameters.append(exclude)

        row = self._require_connection().execute(query, parameters).fetchone()
        if row is not None:
            raise DuplicateKnowledgeError(
                f"identical content already stored in category "
                f"{record.category.value!r} as record {row['record_id']}"
            )

    @staticmethod
    def _guard_verified(existing: sqlite3.Row, record: KnowledgeRecord) -> None:
        was_verified = (
            existing["verification"] == KnowledgeVerification.VERIFIED.value
        )
        becoming_unverified = (
            record.verification is KnowledgeVerification.UNVERIFIED
        )
        if was_verified and becoming_unverified:
            raise VerifiedKnowledgeProtectedError(
                f"record {existing['record_id']} is verified; unverified "
                "content may not overwrite it"
            )

    @staticmethod
    def _translate(
        error: sqlite3.IntegrityError, record: KnowledgeRecord
    ) -> Exception:
        message = str(error)
        if "verified knowledge record" in message:
            return VerifiedKnowledgeProtectedError(message)
        if "idx_knowledge_content_unique" in message or "UNIQUE" in message:
            return DuplicateKnowledgeError(
                f"identical content already stored in category "
                f"{record.category.value!r}"
            )
        return KnowledgeStoreError(message)

    # ------------------------------------------------------------------
    # reads
    # ------------------------------------------------------------------

    def get(self, record_id: str) -> KnowledgeRecord | None:
        """Return one record, or ``None`` if it does not exist."""
        row = self._row(record_id)
        return None if row is None else self._to_record(row)

    def search(
        self,
        query: str,
        *,
        category: KnowledgeCategory | None = None,
        limit: int = 20,
    ) -> Sequence[KnowledgeRecord]:
        """Return records matching a textual query, most relevant first."""
        connection = self._require_connection()
        terms = normalise(query)
        if not terms:
            return ()

        if self._search_mode == "fts5":
            sql = (
                f"SELECT r.* FROM {FTS_TABLE} f "
                "JOIN knowledge_records r ON r.record_id = f.record_id "
                f"WHERE {FTS_TABLE} MATCH ?"
            )
            parameters: list[object] = [_as_match_expression(terms)]
            if category is not None:
                sql += " AND r.category = ?"
                parameters.append(category.value)
            sql += " ORDER BY rank LIMIT ?"
        else:
            sql = (
                "SELECT * FROM knowledge_records "
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

    def iter_records(
        self, *, category: KnowledgeCategory | None = None
    ) -> Iterator[KnowledgeRecord]:
        """Stream stored records, oldest first.

        Rows are yielded as the cursor produces them so that a future rebuild
        of a derived vector index never loads the whole table into memory.
        """
        connection = self._require_connection()
        sql = "SELECT * FROM knowledge_records"
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
            .execute(
                "SELECT * FROM knowledge_records WHERE record_id = ?",
                (record_id,),
            )
            .fetchone()
        )

    def _tags(self, record_id: str) -> tuple[str, ...]:
        rows = self._require_connection().execute(
            "SELECT tag FROM knowledge_tags WHERE record_id = ? ORDER BY tag",
            (record_id,),
        )
        return tuple(row["tag"] for row in rows)

    def _to_record(self, row: sqlite3.Row) -> KnowledgeRecord:
        return KnowledgeRecord(
            record_id=row["record_id"],
            category=KnowledgeCategory(row["category"]),
            content=row["content"],
            source=row["source"],
            confidence=row["confidence"],
            verification=KnowledgeVerification(row["verification"]),
            created_at=_parse_timestamp(row["created_at"]),
            updated_at=_parse_timestamp(row["updated_at"]),
            acceptance_note=row["acceptance_note"],
            tags=self._tags(row["record_id"]),
        )

    # ------------------------------------------------------------------
    # deletion
    # ------------------------------------------------------------------

    def delete(self, record_id: str) -> bool:
        """Remove a record, its tags, and its search entry.

        Deletion is a permanent change and requires human approval upstream;
        this method performs no approval check of its own.
        """
        connection = self._require_connection()
        cursor = connection.execute(
            "DELETE FROM knowledge_records WHERE record_id = ?", (record_id,)
        )
        connection.commit()
        return cursor.rowcount > 0


def _as_match_expression(terms: str) -> str:
    """Quote each term so user text cannot be interpreted as FTS5 syntax."""
    return " ".join(f'"{term.replace(chr(34), "")}"' for term in terms.split())


def _escape_like(terms: str) -> str:
    return terms.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


__all__ = [
    "SQLiteKnowledgeStore",
    "TIMESTAMP_FORMAT",
    "normalise",
    "content_fingerprint",
]
