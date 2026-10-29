package ai.clu.core.db

/**
 * Versioned schema. Each entry migrates from version (index) to (index + 1).
 * Never edit a released migration; append a new one.
 *
 * Synced tables carry `hlc` (hybrid logical clock timestamp) and `origin` (device id of last writer).
 */
object Schema {
    val migrations: List<List<String>> = listOf(
        // v0 -> v1
        listOf(
            """CREATE TABLE kv_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)""",
            """CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL, scope TEXT NOT NULL,
                updated_at INTEGER NOT NULL, hlc TEXT NOT NULL, origin TEXT NOT NULL)""",
            """CREATE TABLE settings_snapshots (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, label TEXT NOT NULL,
                json TEXT NOT NULL, known_good INTEGER NOT NULL DEFAULT 0)""",
            """CREATE TABLE conversations (id TEXT PRIMARY KEY, title TEXT NOT NULL, persona_id TEXT,
                archived INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, origin TEXT NOT NULL)""",
            """CREATE TABLE messages (id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, role TEXT NOT NULL,
                content TEXT NOT NULL, created_at INTEGER NOT NULL, meta TEXT NOT NULL DEFAULT '{}',
                deleted INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, origin TEXT NOT NULL)""",
            """CREATE INDEX idx_messages_conv ON messages(conversation_id, created_at)""",
            """CREATE TABLE memories (id TEXT PRIMARY KEY, category TEXT NOT NULL, title TEXT NOT NULL,
                content TEXT NOT NULL, summary TEXT, retain_mode TEXT NOT NULL, event_at INTEGER, event_has_time INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL, people TEXT NOT NULL DEFAULT '[]', tags TEXT NOT NULL DEFAULT '[]',
                pinned INTEGER NOT NULL DEFAULT 0, scope TEXT NOT NULL, expires_at INTEGER,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
                hlc TEXT NOT NULL, origin TEXT NOT NULL)""",
            """CREATE INDEX idx_memories_scope ON memories(scope, deleted)""",
            """CREATE TABLE memory_terms (memory_id TEXT NOT NULL, term TEXT NOT NULL, tf INTEGER NOT NULL)""",
            """CREATE INDEX idx_memory_terms_term ON memory_terms(term)""",
            """CREATE INDEX idx_memory_terms_mem ON memory_terms(memory_id)""",
            """CREATE TABLE memory_stats (key TEXT PRIMARY KEY, value REAL NOT NULL)""",
            """CREATE TABLE memory_proposals (id TEXT PRIMARY KEY, kind TEXT NOT NULL, text TEXT NOT NULL,
                rationale TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, decided_at INTEGER,
                memory_id TEXT, conversation_id TEXT, fingerprint TEXT NOT NULL)""",
            """CREATE TABLE preferences (key TEXT PRIMARY KEY, value TEXT NOT NULL, source TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, origin TEXT NOT NULL)""",
            """CREATE TABLE feedback_log (id TEXT PRIMARY KEY, kind TEXT NOT NULL, ref TEXT NOT NULL,
                choice TEXT NOT NULL, created_at INTEGER NOT NULL)""",
            """CREATE TABLE characters (id TEXT PRIMARY KEY, json TEXT NOT NULL, status TEXT NOT NULL,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
                hlc TEXT NOT NULL, origin TEXT NOT NULL)""",
            """CREATE TABLE people (id TEXT PRIMARY KEY, json TEXT NOT NULL, created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, origin TEXT NOT NULL)""",
            """CREATE TABLE voiceprints (id TEXT PRIMARY KEY, person_id TEXT NOT NULL, model TEXT NOT NULL,
                embedding BLOB NOT NULL, created_at INTEGER NOT NULL)""",
            """CREATE TABLE credentials (kind TEXT PRIMARY KEY, hash BLOB NOT NULL, salt BLOB NOT NULL,
                iterations INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
            """CREATE TABLE auth_state (key TEXT PRIMARY KEY, value INTEGER NOT NULL)""",
            """CREATE TABLE custom_commands (id TEXT PRIMARY KEY, json TEXT NOT NULL, created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, origin TEXT NOT NULL)""",
            """CREATE TABLE tool_runs (id TEXT PRIMARY KEY, tool TEXT NOT NULL, args TEXT NOT NULL, success INTEGER NOT NULL,
                verified INTEGER NOT NULL, output TEXT NOT NULL, error TEXT, started_at INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL, conversation_id TEXT, actor TEXT NOT NULL DEFAULT 'owner')""",
            """CREATE INDEX idx_tool_runs_time ON tool_runs(started_at)""",
            """CREATE TABLE models (id TEXT PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL, format TEXT NOT NULL,
                size_bytes INTEGER NOT NULL, added_at INTEGER NOT NULL, status TEXT NOT NULL, last_error TEXT,
                info TEXT NOT NULL DEFAULT '{}', kind TEXT NOT NULL DEFAULT 'llm')""",
            """CREATE TABLE sync_peers (device_id TEXT PRIMARY KEY, name TEXT NOT NULL, role TEXT NOT NULL,
                public_key BLOB NOT NULL, paired_at INTEGER NOT NULL, last_seen INTEGER, last_address TEXT,
                last_sync_at INTEGER, acked_seq INTEGER NOT NULL DEFAULT 0, received_hlc TEXT NOT NULL DEFAULT '')""",
            """CREATE TABLE change_log (seq INTEGER PRIMARY KEY AUTOINCREMENT, table_name TEXT NOT NULL,
                row_id TEXT NOT NULL, hlc TEXT NOT NULL)""",
            """CREATE INDEX idx_change_log_row ON change_log(table_name, row_id)""",
            """CREATE TABLE sync_history (id TEXT PRIMARY KEY, peer TEXT NOT NULL, started_at INTEGER NOT NULL,
                finished_at INTEGER, sent INTEGER NOT NULL DEFAULT 0, received INTEGER NOT NULL DEFAULT 0,
                conflicts INTEGER NOT NULL DEFAULT 0, error TEXT)""",
            """CREATE TABLE diag_events (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, level TEXT NOT NULL,
                tag TEXT NOT NULL, message TEXT NOT NULL)""",
            """CREATE TABLE proactive_log (id TEXT PRIMARY KEY, ts INTEGER NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL)""",
            """CREATE TABLE perf_samples (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, metric TEXT NOT NULL,
                value REAL NOT NULL, detail TEXT NOT NULL DEFAULT '')""",
        ),
        // v1 -> v2: the diary becomes its own store, separate from memories. Diary-category memories move into it (as
        // entries the owner wrote or approved) and are tombstoned in memories, so they no longer reach prompts.
        listOf(
            """CREATE TABLE diary_entries (id TEXT PRIMARY KEY, entry_date TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL,
                source TEXT NOT NULL, conversation_id TEXT, memory_id TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0)""",
            """CREATE INDEX idx_diary_date ON diary_entries(deleted, entry_date)""",
            """INSERT INTO diary_entries(id, entry_date, title, body, source, conversation_id, memory_id, created_at, updated_at, deleted)
                SELECT id, strftime('%Y-%m-%d', COALESCE(event_at, created_at) / 1000, 'unixepoch', 'localtime'), title,
                       CASE WHEN content <> '' THEN content ELSE COALESCE(summary, '') END,
                       CASE source WHEN 'CONVERSATION' THEN 'conversation' ELSE 'manual' END, NULL, NULL, created_at, updated_at, 0
                FROM memories WHERE category = 'DIARY' AND deleted = 0""",
            """DELETE FROM memory_terms WHERE memory_id IN (SELECT id FROM memories WHERE category = 'DIARY')""",
            """DELETE FROM memory_stats WHERE key IN (SELECT 'len:' || id FROM memories WHERE category = 'DIARY')""",
            """UPDATE memories SET deleted = 1, content = '', summary = NULL WHERE category = 'DIARY'""",
        ),
        // v2 -> v3: diary entries become synchronisable (separately switchable). Existing entries are queued once so a
        // paired device can receive them if diary sync is turned on; nothing is sent while it is off.
        listOf(
            """ALTER TABLE diary_entries ADD COLUMN hlc TEXT NOT NULL DEFAULT ''""",
            """ALTER TABLE diary_entries ADD COLUMN origin TEXT NOT NULL DEFAULT ''""",
            """INSERT INTO change_log(table_name, row_id, hlc) SELECT 'diary_entries', id, '' FROM diary_entries""",
        ),
        // v3 -> v4: the personal archive. Memories get historical dates (day / month / year / range / unknown, possibly
        // approximate) separate from when they were recorded, a "told to Clu" date in Clu's timeline, places, certainty,
        // importance and story fields. Legacy event timestamps become date-only values (they were saved at local noon).
        // New tables: people catalog, projects, documents (text chunks + term index), links between records, revisions,
        // conflicts and import progress. Existing rows are only extended, never removed.
        listOf(
            """ALTER TABLE memories ADD COLUMN event_start TEXT""",
            """ALTER TABLE memories ADD COLUMN event_end TEXT""",
            """ALTER TABLE memories ADD COLUMN event_precision TEXT NOT NULL DEFAULT 'UNKNOWN'""",
            """ALTER TABLE memories ADD COLUMN event_approx INTEGER NOT NULL DEFAULT 0""",
            """ALTER TABLE memories ADD COLUMN told TEXT""",
            """ALTER TABLE memories ADD COLUMN places TEXT NOT NULL DEFAULT '[]'""",
            """ALTER TABLE memories ADD COLUMN certainty TEXT NOT NULL DEFAULT 'RECOLLECTION'""",
            """ALTER TABLE memories ADD COLUMN importance INTEGER NOT NULL DEFAULT 0""",
            """ALTER TABLE memories ADD COLUMN significance TEXT NOT NULL DEFAULT ''""",
            """ALTER TABLE memories ADD COLUMN thread_kind TEXT NOT NULL DEFAULT ''""",
            """ALTER TABLE memories ADD COLUMN links TEXT NOT NULL DEFAULT '[]'""",
            """ALTER TABLE memories ADD COLUMN merged_into TEXT""",
            """UPDATE memories SET event_start = strftime('%Y-%m-%d', event_at / 1000, 'unixepoch', 'localtime'),
                event_end = strftime('%Y-%m-%d', event_at / 1000, 'unixepoch', 'localtime'), event_precision = 'DAY' WHERE event_at IS NOT NULL""",
            """CREATE INDEX idx_memories_event ON memories(deleted, event_start)""",
            """CREATE TABLE archive_people (id TEXT PRIMARY KEY, name TEXT NOT NULL, json TEXT NOT NULL, created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, origin TEXT NOT NULL)""",
            """CREATE TABLE archive_projects (id TEXT PRIMARY KEY, name TEXT NOT NULL, json TEXT NOT NULL, created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, origin TEXT NOT NULL)""",
            """CREATE TABLE archive_documents (id TEXT PRIMARY KEY, name TEXT NOT NULL, sha256 TEXT NOT NULL, json TEXT NOT NULL,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, hlc TEXT NOT NULL, origin TEXT NOT NULL)""",
            """CREATE INDEX idx_documents_sha ON archive_documents(sha256)""",
            """CREATE TABLE document_chunks (id TEXT PRIMARY KEY, doc_id TEXT NOT NULL, seq INTEGER NOT NULL, text TEXT NOT NULL)""",
            """CREATE INDEX idx_chunks_doc ON document_chunks(doc_id, seq)""",
            """CREATE TABLE document_terms (chunk_id TEXT NOT NULL, doc_id TEXT NOT NULL, term TEXT NOT NULL, tf INTEGER NOT NULL)""",
            """CREATE INDEX idx_document_terms_term ON document_terms(term)""",
            """CREATE INDEX idx_document_terms_doc ON document_terms(doc_id)""",
            """CREATE TABLE archive_links (id TEXT PRIMARY KEY, a_kind TEXT NOT NULL, a_id TEXT NOT NULL, b_kind TEXT NOT NULL, b_id TEXT NOT NULL,
                relation TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)""",
            """CREATE INDEX idx_links_a ON archive_links(a_kind, a_id, deleted)""",
            """CREATE INDEX idx_links_b ON archive_links(b_kind, b_id, deleted)""",
            """CREATE TABLE record_revisions (id TEXT PRIMARY KEY, kind TEXT NOT NULL, record_id TEXT NOT NULL, json TEXT NOT NULL,
                reason TEXT NOT NULL, created_at INTEGER NOT NULL)""",
            """CREATE INDEX idx_revisions_record ON record_revisions(kind, record_id, created_at)""",
            """CREATE TABLE archive_conflicts (id TEXT PRIMARY KEY, kind TEXT NOT NULL, a_id TEXT NOT NULL, b_id TEXT NOT NULL, field TEXT NOT NULL,
                a_value TEXT NOT NULL, b_value TEXT NOT NULL, status TEXT NOT NULL, resolution TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,
                resolved_at INTEGER)""",
            """CREATE TABLE import_jobs (id TEXT PRIMARY KEY, source TEXT NOT NULL, fingerprint TEXT NOT NULL, total INTEGER NOT NULL,
                done INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, error TEXT, started_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
            """CREATE TABLE import_keys (key TEXT PRIMARY KEY, record_id TEXT NOT NULL, job_id TEXT NOT NULL)""",
        ),
    )

    val currentVersion: Int get() = migrations.size

    /**
     * Brings [db] to [target] (default: the current version). All steps run in one transaction: if any statement fails,
     * nothing is changed and the version stays where it was, so the app can be fixed and opened again without data loss.
     */
    fun migrate(db: SqlDriver, target: Int = currentVersion, steps: List<List<String>> = migrations) {
        val from = db.userVersion
        require(from <= steps.size) { "Database version $from is newer than this app (${steps.size})" }
        if (from >= target) return
        db.transaction {
            for (v in from until target) steps[v].forEach { db.execute(it) }
            db.userVersion = target
        }
    }
}
