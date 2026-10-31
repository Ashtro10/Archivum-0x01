"""SQLite schema and migrations for the Knowledge Engine.

Implements the schema approved in ADR-0008. Version is tracked with
``PRAGMA user_version``: version 0 is an empty database, version 1 is the
schema below.

This is a separate database file from the Memory Engine (ADR-0008 D1). The
two stores have independent schema versions and independent migration paths.

Full-text search is created separately from the versioned migrations because
its availability is a property of the SQLite build rather than of the database
(mirrors ADR-0003 D8). A database created without FTS5 gains it, and is
backfilled, the first time it is opened by a build that has FTS5.
"""

from __future__ import annotations

import sqlite3

SCHEMA_VERSION = 1

FTS_TABLE = "knowledge_fts"

_MIGRATION_1 = """
CREATE TABLE knowledge_records (
    record_id        TEXT    PRIMARY KEY,
    category         TEXT    NOT NULL,
    content          TEXT    NOT NULL,
    content_hash     TEXT    NOT NULL,
    verification     TEXT    NOT NULL DEFAULT 'unverified',
    source           TEXT    NOT NULL DEFAULT '',
    confidence       REAL    NOT NULL DEFAULT 0.0,
    acceptance_note  TEXT    NOT NULL DEFAULT '',
    created_at       TEXT    NOT NULL,
    updated_at       TEXT    NOT NULL,

    CHECK (length(trim(content)) > 0),
    CHECK (verification IN ('unverified', 'verified', 'refuted')),
    CHECK (confidence >= 0.0 AND confidence <= 1.0),
    CHECK (category IN (
        'architecture', 'programming', 'artificial_intelligence',
        'mathematics', 'operating_systems', 'networking', 'security',
        'hardware', 'projects', 'user_preferences', 'experiments',
        'failures', 'ideas'
    ))
);

CREATE UNIQUE INDEX idx_knowledge_content_unique
    ON knowledge_records (category, content_hash);
CREATE INDEX idx_knowledge_category     ON knowledge_records (category);
CREATE INDEX idx_knowledge_verification ON knowledge_records (verification);
CREATE INDEX idx_knowledge_created_at   ON knowledge_records (created_at);

CREATE TABLE knowledge_tags (
    record_id TEXT NOT NULL
        REFERENCES knowledge_records (record_id) ON DELETE CASCADE,
    tag       TEXT NOT NULL,
    PRIMARY KEY (record_id, tag)
);

CREATE INDEX idx_knowledge_tags_tag ON knowledge_tags (tag);

-- Unknown information must never overwrite verified information (ADR-0008 D6).
CREATE TRIGGER trg_knowledge_protect_verified
BEFORE UPDATE ON knowledge_records
FOR EACH ROW
WHEN OLD.verification = 'verified' AND NEW.verification = 'unverified'
BEGIN
    SELECT RAISE(ABORT,
        'unverified content may not overwrite a verified knowledge record');
END;
"""

MIGRATIONS: tuple[tuple[int, str], ...] = ((1, _MIGRATION_1),)

_FTS_SETUP = f"""
CREATE VIRTUAL TABLE IF NOT EXISTS {FTS_TABLE} USING fts5(
    content,
    record_id UNINDEXED,
    tokenize = 'unicode61'
);

CREATE TRIGGER IF NOT EXISTS trg_knowledge_fts_insert
AFTER INSERT ON knowledge_records
BEGIN
    INSERT INTO {FTS_TABLE} (content, record_id)
    VALUES (NEW.content, NEW.record_id);
END;

CREATE TRIGGER IF NOT EXISTS trg_knowledge_fts_update
AFTER UPDATE OF content ON knowledge_records
BEGIN
    DELETE FROM {FTS_TABLE} WHERE record_id = OLD.record_id;
    INSERT INTO {FTS_TABLE} (content, record_id)
    VALUES (NEW.content, NEW.record_id);
END;

CREATE TRIGGER IF NOT EXISTS trg_knowledge_fts_delete
AFTER DELETE ON knowledge_records
BEGIN
    DELETE FROM {FTS_TABLE} WHERE record_id = OLD.record_id;
END;
"""

_FTS_BACKFILL = f"""
INSERT INTO {FTS_TABLE} (content, record_id)
SELECT content, record_id FROM knowledge_records
WHERE record_id NOT IN (SELECT record_id FROM {FTS_TABLE});
"""


def current_version(connection: sqlite3.Connection) -> int:
    """Return the schema version recorded in the database header."""
    return int(connection.execute("PRAGMA user_version").fetchone()[0])


def apply_migrations(connection: sqlite3.Connection) -> int:
    """Bring the database up to :data:`SCHEMA_VERSION` and return the version.

    Idempotent: already-applied steps are skipped. Each step and its version
    bump are committed together so an interrupted run cannot leave the header
    claiming a schema that was not created.
    """
    version = current_version(connection)
    for target, script in MIGRATIONS:
        if version >= target:
            continue
        connection.executescript(script)
        connection.execute(f"PRAGMA user_version = {int(target)}")
        connection.commit()
        version = target
    return version


def fts5_available(connection: sqlite3.Connection) -> bool:
    """Report whether this SQLite build provides FTS5."""
    try:
        connection.execute(
            "CREATE VIRTUAL TABLE temp.astra_knowledge_fts_probe USING fts5(probe)"
        )
    except sqlite3.OperationalError:
        return False
    connection.execute("DROP TABLE temp.astra_knowledge_fts_probe")
    return True


def ensure_fts(connection: sqlite3.Connection) -> bool:
    """Create the full-text table and its triggers when FTS5 is available.

    Returns:
        ``True`` if full-text search is usable after this call.
    """
    if not fts5_available(connection):
        return False
    connection.executescript(_FTS_SETUP)
    connection.executescript(_FTS_BACKFILL)
    connection.commit()
    return True


__all__ = [
    "SCHEMA_VERSION",
    "FTS_TABLE",
    "MIGRATIONS",
    "current_version",
    "apply_migrations",
    "fts5_available",
    "ensure_fts",
]
