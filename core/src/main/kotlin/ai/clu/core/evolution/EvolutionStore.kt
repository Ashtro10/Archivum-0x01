package ai.clu.core.evolution

import ai.clu.core.db.Schema
import ai.clu.core.db.SqlDriver
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.util.Clock
import ai.clu.core.util.newId

/**
 * CLU's development memory: what it knows about its own source (the index), what changed and who changed it, the
 * problems it found, its investigations and every repair attempt with the exact commands and results.
 *
 * It lives in its own SQLite file (`evolution.db`) through the same [SqlDriver] and migration mechanism as `clu.db`:
 * it is derived, machine-oriented data that must not bloat the owner's backups or sync, and can be deleted without
 * touching conversations or memories. Index tables can be rebuilt from Git; history tables cannot, so they are kept.
 */
class EvolutionStore(val db: SqlDriver, val clock: Clock) {
    init { Schema.migrate(db, steps = MIGRATIONS, target = MIGRATIONS.size) }

    // ------------------------------------------------------------------ development history (append-only log)
    data class HistoryEvent(val id: String, val at: Long, val kind: String, val ref: String, val summary: String, val detail: String)

    fun history(kind: String, ref: String, summary: String, detail: String = ""): HistoryEvent {
        val e = HistoryEvent(newId(), clock.now(), kind, ref, DiagnosticsLog.redact(summary).take(2000), DiagnosticsLog.redact(detail).take(64_000))
        db.execute("INSERT INTO dev_history(id, at, kind, ref, summary, detail) VALUES(?,?,?,?,?,?)", listOf(e.id, e.at, e.kind, e.ref, e.summary, e.detail))
        return e
    }

    fun recentHistory(limit: Int = 50, kind: String? = null): List<HistoryEvent> =
        db.query("SELECT id, at, kind, ref, summary, detail FROM dev_history ${if (kind != null) "WHERE kind = ?" else ""} ORDER BY at DESC, rowid DESC LIMIT ${limit.coerceIn(1, 1000)}",
            listOfNotNull(kind)) { HistoryEvent(it.str(0), it.lng(1), it.str(2), it.str(3), it.str(4), it.str(5)) }

    // ------------------------------------------------------------------ commands (exact command, exit code, output)
    data class CommandRecord(val id: String, val repairId: String, val phase: String, val command: String, val cwd: String, val exitCode: Int,
                             val durationMs: Long, val timedOut: Boolean, val output: String, val at: Long)

    fun recordCommand(repairId: String, phase: String, command: String, cwd: String, exitCode: Int, durationMs: Long, timedOut: Boolean, output: String): CommandRecord {
        val r = CommandRecord(newId(), repairId, phase, command, cwd, exitCode, durationMs, timedOut, DiagnosticsLog.redact(tail(output, OUTPUT_CAP)), clock.now())
        db.execute("INSERT INTO dev_commands(id, repair_id, phase, command, cwd, exit_code, duration_ms, timed_out, output, at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            listOf(r.id, r.repairId, r.phase, r.command, r.cwd, r.exitCode, r.durationMs, if (r.timedOut) 1 else 0, r.output, r.at))
        return r
    }

    fun commands(repairId: String): List<CommandRecord> =
        db.query("SELECT id, repair_id, phase, command, cwd, exit_code, duration_ms, timed_out, output, at FROM dev_commands WHERE repair_id = ? ORDER BY at, rowid", listOf(repairId)) {
            CommandRecord(it.str(0), it.str(1), it.str(2), it.str(3), it.str(4), it.lng(5).toInt(), it.lng(6), it.lng(7) != 0L, it.str(8), it.lng(9)) }

    companion object {
        /** Output kept per command: the end of a build log is where failures are reported. */
        const val OUTPUT_CAP = 64_000
        fun tail(s: String, n: Int) = if (s.length <= n) s else "[… ${s.length - n} earlier characters not kept …]\n" + s.takeLast(n)

        val MIGRATIONS: List<List<String>> = listOf(
            listOf(
                """CREATE TABLE repo_snapshots (id TEXT PRIMARY KEY, repo TEXT NOT NULL, head TEXT NOT NULL, branch TEXT NOT NULL, dirty_files TEXT NOT NULL,
                    files INTEGER NOT NULL, at INTEGER NOT NULL)""",
                """CREATE INDEX idx_snapshots_at ON repo_snapshots(at)""",
                """CREATE TABLE source_files (path TEXT PRIMARY KEY, blob TEXT NOT NULL, module TEXT NOT NULL, package TEXT NOT NULL, kind TEXT NOT NULL,
                    language TEXT NOT NULL, lines INTEGER NOT NULL, provenance TEXT NOT NULL, last_commit TEXT NOT NULL, indexed_at INTEGER NOT NULL)""",
                """CREATE INDEX idx_source_module ON source_files(module, package)""",
                """CREATE TABLE symbols (file TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, line INTEGER NOT NULL, fqname TEXT NOT NULL, doc TEXT NOT NULL)""",
                """CREATE INDEX idx_symbols_name ON symbols(name)""",
                """CREATE INDEX idx_symbols_file ON symbols(file)""",
                """CREATE TABLE file_imports (file TEXT NOT NULL, target TEXT NOT NULL)""",
                """CREATE INDEX idx_imports_file ON file_imports(file)""",
                """CREATE INDEX idx_imports_target ON file_imports(target)""",
                """CREATE TABLE module_deps (from_module TEXT NOT NULL, to_module TEXT NOT NULL, kind TEXT NOT NULL, PRIMARY KEY (from_module, to_module, kind))""",
                """CREATE TABLE index_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)""",
                """CREATE TABLE dev_history (id TEXT PRIMARY KEY, at INTEGER NOT NULL, kind TEXT NOT NULL, ref TEXT NOT NULL, summary TEXT NOT NULL, detail TEXT NOT NULL)""",
                """CREATE INDEX idx_history_at ON dev_history(at)""",
                """CREATE TABLE commits_seen (hash TEXT PRIMARY KEY, author TEXT NOT NULL, at INTEGER NOT NULL, subject TEXT NOT NULL, provenance TEXT NOT NULL,
                    files TEXT NOT NULL, categories TEXT NOT NULL, recorded_at INTEGER NOT NULL)""",
                """CREATE TABLE incidents (id TEXT PRIMARY KEY, signature TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL,
                    status TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, count INTEGER NOT NULL, commit_seen TEXT NOT NULL)""",
                """CREATE TABLE investigations (id TEXT PRIMARY KEY, incident_id TEXT NOT NULL, state TEXT NOT NULL, json TEXT NOT NULL, created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL)""",
                """CREATE INDEX idx_investigations_incident ON investigations(incident_id)""",
                """CREATE TABLE repairs (id TEXT PRIMARY KEY, incident_id TEXT NOT NULL, investigation_id TEXT NOT NULL, state TEXT NOT NULL, source TEXT NOT NULL,
                    base_commit TEXT NOT NULL, branch TEXT NOT NULL, worktree TEXT NOT NULL, patch_commit TEXT NOT NULL, patch_hash TEXT NOT NULL, json TEXT NOT NULL,
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
                """CREATE INDEX idx_repairs_incident ON repairs(incident_id)""",
                """CREATE TABLE dev_commands (id TEXT PRIMARY KEY, repair_id TEXT NOT NULL, phase TEXT NOT NULL, command TEXT NOT NULL, cwd TEXT NOT NULL,
                    exit_code INTEGER NOT NULL, duration_ms INTEGER NOT NULL, timed_out INTEGER NOT NULL, output TEXT NOT NULL, at INTEGER NOT NULL)""",
                """CREATE INDEX idx_commands_repair ON dev_commands(repair_id)""",
            ),
            Schema2.STEP,
        )
    }

    fun meta(key: String): String? = db.query("SELECT value FROM index_meta WHERE key = ?", listOf(key)) { it.str(0) }.firstOrNull()
    fun setMeta(key: String, value: String) = db.execute("INSERT INTO index_meta(key, value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", listOf(key, value))
}
