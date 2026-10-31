"""SQLite schema and migrations for the Planning Engine.

Implements the schema approved in ADR-0009 (v1 tasks) and ADR-0013 (v2 goals).
Version tracked with ``PRAGMA user_version``: version 0 is empty, version 1 is
tasks, version 2 adds goal tables.

Separate database file from Memory and Knowledge (ADR-0009 D1). Independent
``user_version`` counter.
"""

from __future__ import annotations

import sqlite3

SCHEMA_VERSION = 2

_MIGRATION_1 = """
CREATE TABLE planning_tasks (
    task_id         TEXT    PRIMARY KEY,
    title           TEXT    NOT NULL,
    notes           TEXT    NOT NULL DEFAULT '',
    priority        TEXT    NOT NULL,
    impact          TEXT    NOT NULL,
    effort          TEXT    NOT NULL,
    risk            TEXT    NOT NULL,
    status          TEXT    NOT NULL DEFAULT 'todo',
    created_at      TEXT    NOT NULL,
    updated_at      TEXT    NOT NULL,
    completed_at    TEXT,
    completion_note TEXT    NOT NULL DEFAULT '',

    CHECK (length(trim(title)) > 0),
    CHECK (priority        IN ('p0', 'p1', 'p2', 'p3')),
    CHECK (impact          IN ('trivial', 'small', 'medium', 'large')),
    CHECK (effort          IN ('trivial', 'small', 'medium', 'large')),
    CHECK (risk            IN ('none', 'low', 'medium', 'high')),
    CHECK (status          IN ('todo', 'in_progress', 'blocked', 'done', 'dropped'))
);

CREATE INDEX idx_planning_priority   ON planning_tasks (priority);
CREATE INDEX idx_planning_status     ON planning_tasks (status);
CREATE INDEX idx_planning_created_at ON planning_tasks (created_at);

CREATE TABLE planning_deps (
    task_id TEXT NOT NULL REFERENCES planning_tasks (task_id) ON DELETE CASCADE,
    dep_id  TEXT NOT NULL,
    PRIMARY KEY (task_id, dep_id)
);

CREATE INDEX idx_planning_deps_dep_id ON planning_deps (dep_id);

-- DONE and DROPPED are terminal; no further status change is allowed
-- (ADR-0009 D4). Python guard gives a readable error first; trigger is
-- the backstop that holds even outside this codebase.
CREATE TRIGGER trg_planning_terminal_state
BEFORE UPDATE OF status ON planning_tasks
FOR EACH ROW
WHEN OLD.status IN ('done', 'dropped') AND NEW.status != OLD.status
BEGIN
    SELECT RAISE(ABORT,
        'task is in a terminal state and cannot be transitioned');
END;
"""

_MIGRATION_2 = """
CREATE TABLE planning_goals (
    goal_id     TEXT    PRIMARY KEY,
    description TEXT    NOT NULL,
    status      TEXT    NOT NULL DEFAULT 'proposed',
    created_at  TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL,
    CHECK (length(trim(description)) > 0),
    CHECK (status IN ('proposed', 'active', 'achieved', 'abandoned'))
);

CREATE INDEX idx_planning_goals_status ON planning_goals (status);

CREATE TABLE planning_goal_criteria (
    goal_id   TEXT    NOT NULL
        REFERENCES planning_goals (goal_id) ON DELETE CASCADE,
    ordinal   INTEGER NOT NULL,
    criterion TEXT    NOT NULL,
    PRIMARY KEY (goal_id, ordinal),
    CHECK (length(trim(criterion)) > 0)
);

CREATE TABLE planning_goal_tasks (
    goal_id TEXT NOT NULL
        REFERENCES planning_goals (goal_id) ON DELETE CASCADE,
    task_id TEXT NOT NULL,
    PRIMARY KEY (goal_id, task_id)
);

CREATE TABLE planning_goal_evaluations (
    eval_id        TEXT    PRIMARY KEY,
    goal_id        TEXT    NOT NULL
        REFERENCES planning_goals (goal_id) ON DELETE CASCADE,
    evaluated_at   TEXT    NOT NULL,
    met            INTEGER NOT NULL,
    embedding_used INTEGER NOT NULL DEFAULT 0,
    CHECK (met IN (0, 1)),
    CHECK (embedding_used IN (0, 1))
);

CREATE TABLE planning_goal_evidence (
    eval_id      TEXT NOT NULL
        REFERENCES planning_goal_evaluations (eval_id) ON DELETE CASCADE,
    criterion    TEXT NOT NULL,
    kind         TEXT NOT NULL,
    record_id    TEXT NOT NULL,
    excerpt      TEXT NOT NULL DEFAULT '',
    retrieved_at TEXT NOT NULL,
    CHECK (kind IN ('memory', 'knowledge'))
);

CREATE TRIGGER trg_planning_goal_terminal
BEFORE UPDATE OF status ON planning_goals
FOR EACH ROW
WHEN OLD.status IN ('achieved', 'abandoned') AND NEW.status != OLD.status
BEGIN
    SELECT RAISE(ABORT,
        'goal is in a terminal state and cannot be transitioned');
END;
"""

MIGRATIONS: tuple[tuple[int, str], ...] = ((1, _MIGRATION_1), (2, _MIGRATION_2))


def current_version(connection: sqlite3.Connection) -> int:
    """Return the schema version recorded in the database header."""
    return int(connection.execute("PRAGMA user_version").fetchone()[0])


def apply_migrations(connection: sqlite3.Connection) -> int:
    """Bring the database up to :data:`SCHEMA_VERSION` and return the version.

    Idempotent: already-applied steps are skipped.
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


__all__ = [
    "SCHEMA_VERSION",
    "MIGRATIONS",
    "current_version",
    "apply_migrations",
]
