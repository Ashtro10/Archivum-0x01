"""SQLite schema and migrations for the Memory Engine.

Implements the schema approved in ADR-0003. Version is tracked with
``PRAGMA user_version``: version 0 is an empty database, version 1 is the
schema below.

Full-text search is created separately from the versioned migrations because
its availability is a property of the SQLite build rather than of the database
(ADR-0003 D8). A database created without FTS5 gains it, and is backfilled, the
first time it is opened by a build that has it.
"""

from __future__ import annotations

import sqlite3

SCHEMA_VERSION = 1

FTS_TABLE = "memory_fts"

_MIGRATION_1 = """
CREATE TABLE memory_records (
    record_id         TEXT    PRIMARY KEY,
    category          TEXT    NOT NULL,
    content           TEXT    NOT NULL,
    content_hash      TEXT    NOT NULL,
    verification      TEXT    NOT NULL DEFAULT 'unverified',
    source            TEXT    NOT NULL DEFAULT '',
    created_at        TEXT    NOT NULL,
    updated_at        TEXT    NOT NULL,

    -- Reserved for B-003 (ADR-0003 D12). Written by nobody yet.
    embedding_model   TEXT,
    embedding_version TEXT,
    embedding_dim     INTEGER,
    embedded_at       TEXT,

    CHECK (length(trim(content)) > 0),
    CHECK (verification IN ('unverified', 'verified', 'refuted')),
    CHECK (category IN (
        'user_preferences', 'project_knowledge', 'documentation',
        'architecture', 'research', 'code_summaries',
        'experiments', 'failures', 'hardware', 'ideas'
    ))
);

CREATE UNIQUE INDEX idx_memory_content_unique
    ON memory_records (category, content_hash);
CREATE INDEX idx_memory_category     ON memory_records (category);
CREATE INDEX idx_memory_verification ON memory_records (verification);
CREATE INDEX idx_memory_created_at   ON memory_records (created_at);

CREATE TABLE memory_tags (
    record_id TEXT NOT NULL
        REFERENCES memory_records (record_id) ON DELETE CASCADE,
    tag       TEXT NOT NULL,
    PRIMARY KEY (record_id, tag)
);

CREATE INDEX idx_memory_tags_tag ON memory_tags (tag);

-- Unknown information must never overwrite verified information.
CREATE TRIGGER trg_memory_protect_verified
BEFORE UPDATE ON memory_records
FOR EACH ROW
WHEN OLD.verification = 'verified' AND NEW.verification = 'unverified'
BEGIN
    SELECT RAISE(ABORT,
        'unverified content may not overwrite a verified record');
END;
"""

MIGRATIONS: tuple[tuple[int, str], ...] = ((1, _MIGRATION_1),)

_FTS_SETUP = f"""
CREATE VIRTUAL TABLE IF NOT EXISTS {FTS_TABLE} USING fts5(
    content,
    record_id UNINDEXED,
    tokenize = 'unicode61'
);

CREATE TRIGGER IF NOT EXISTS trg_memory_fts_insert
AFTER INSERT ON memory_records
BEGIN
    INSERT INTO {FTS_TABLE} (content, record_id)
    VALUES (NEW.content, NEW.record_id);
END;

CREATE TRIGGER IF NOT EXISTS trg_memory_fts_update
AFTER UPDATE OF content ON memory_records
BEGIN
    DELETE FROM {FTS_TABLE} WHERE record_id = OLD.record_id;
    INSERT INTO {FTS_TABLE} (content, record_id)
    VALUES (NEW.content, NEW.record_id);
END;

CREATE TRIGGER IF NOT EXISTS trg_memory_fts_delete
AFTER DELETE ON memory_records
BEGIN
    DELETE FROM {FTS_TABLE} WHERE record_id = OLD.record_id;
END;
"""

_FTS_BACKFILL = f"""
INSERT INTO {FTS_TABLE} (content, record_id)
SELECT content, record_id FROM memory_records
WHERE record_id NOT IN (SELECT record_id FROM {FTS_TABLE});
"""


def current_version(connection: sqlite3.Connection) -> int:
    """Return the schema version recorded in the database header."""
    return int(connection.execute("PRAGMA user_version").fetchone()[0])


def apply_migrations(connection: sqlite3.Connection) -> int:
    """Bring the database up to :data:`SCHEMA_VERSION` and return the version.

    Idempotent: already-applied steps are skipped. Each step and its version
    bump are committed together, so an interrupted run cannot leave the header
    claiming a schema that was not created.
    """
    version = current_version(connection)
    for target, script in MIGRATIONS:
        if version >= target:
            continue
        connection.executescript(script)
        # PRAGMA does not accept bound parameters; target is an integer
        # constant from MIGRATIONS, never external input.
        connection.execute(f"PRAGMA user_version = {int(target)}")
        connection.commit()
        version = target
    return version


def fts5_available(connection: sqlite3.Connection) -> bool:
    """Report whether this SQLite build provides FTS5.

    Probed rather than assumed (ADR-0003 D8): the answer is a property of the
    interpreter's SQLite, so it can differ between machines running the same
    code.
    """
    try:
        connection.execute(
            "CREATE VIRTUAL TABLE temp.astra_fts_probe USING fts5(probe)"
        )
    except sqlite3.OperationalError:
        return False
    connection.execute("DROP TABLE temp.astra_fts_probe")
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
