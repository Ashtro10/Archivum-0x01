package ai.clu.core.memory

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.CluStore
import ai.clu.core.db.lng
import ai.clu.core.db.queryOne
import ai.clu.core.db.str
import ai.clu.core.learning.PreferenceLearner

data class HealthIssue(val code: String, val count: Int, val detail: String, val repairable: Boolean)
data class HealthReport(val ok: Boolean, val integrity: String, val issues: List<HealthIssue>, val memories: Long, val documents: Long, val checkedAt: Long)

/**
 * Integrity of the memory store: SQLite's own check plus the relationships SQLite doesn't know about (an index entry
 * with no record, a link to a deleted record, a vector for something gone). Everything derived (term indexes, vectors,
 * metadata) can be rebuilt from the records, so repair never touches the records themselves.
 */
class MemoryHealth(private val store: CluStore, private val service: MemoryService) {
    private val db = store.db

    fun check(quick: Boolean = false): HealthReport {
        val integrity = runCatching { db.query(if (quick) "PRAGMA quick_check" else "PRAGMA integrity_check") { it.str(0) }.joinToString("; ").ifBlank { "ok" } }.getOrElse { "check failed: ${it.message}" }
        val issues = mutableListOf<HealthIssue>()
        fun count(sql: String) = (db.queryOne(sql) { it.lng(0) } ?: 0L).toInt()
        fun add(code: String, n: Int, detail: String, repairable: Boolean = true) { if (n > 0) issues += HealthIssue(code, n, detail, repairable) }

        add("orphan_terms", count("SELECT COUNT(DISTINCT memory_id) FROM memory_terms WHERE memory_id NOT IN (SELECT id FROM memories WHERE deleted = 0)"),
            "search index entries for records that no longer exist")
        add("unindexed", count("SELECT COUNT(*) FROM memories WHERE deleted = 0 AND category <> 'DIARY' AND (title <> '' OR content <> '') AND id NOT IN (SELECT DISTINCT memory_id FROM memory_terms)"),
            "records missing from the search index")
        add("dangling_links", count("SELECT COUNT(*) FROM archive_links WHERE deleted = 0 AND ((a_kind = 'memory' AND a_id NOT IN (SELECT id FROM memories WHERE deleted = 0)) OR (b_kind = 'memory' AND b_id NOT IN (SELECT id FROM memories WHERE deleted = 0)))"),
            "relationships pointing at deleted records")
        add("orphan_meta", count("SELECT COUNT(*) FROM memory_meta WHERE memory_id NOT IN (SELECT id FROM memories WHERE deleted = 0)"), "metadata for deleted records")
        add("orphan_vectors", count("SELECT COUNT(*) FROM memory_vectors WHERE kind = 'memory' AND ref_id NOT IN (SELECT id FROM memories WHERE deleted = 0)"), "vectors for deleted records")
        add("orphan_chunks", count("SELECT COUNT(*) FROM document_chunks WHERE doc_id NOT IN (SELECT id FROM archive_documents WHERE deleted = 0)"), "document text of deleted documents")
        add("orphan_stats", count("SELECT COUNT(*) FROM memory_stats WHERE key LIKE 'len:%' AND substr(key, 5) NOT IN (SELECT id FROM memories WHERE deleted = 0)"), "length statistics of deleted records")
        add("expired", count("SELECT COUNT(*) FROM memories WHERE deleted = 0 AND expires_at IS NOT NULL AND expires_at < ${store.clock.now()}"), "temporary memories past their expiry")
        if (integrity != "ok") issues += HealthIssue("sqlite_integrity", 1, integrity.take(300), false)
        return HealthReport(integrity == "ok" && issues.none { it.code == "sqlite_integrity" }, integrity, issues, service.memories.count(),
            (db.queryOne("SELECT COUNT(*) FROM archive_documents WHERE deleted = 0") { it.lng(0) } ?: 0L), store.clock.now())
    }

    /** Rebuilds what can be rebuilt. Returns what was done, one line each. Records are never touched. */
    fun repair(): List<String> {
        val done = mutableListOf<String>()
        db.transaction {
            fun del(sql: String, what: String) { val before = db.queryOne("SELECT total_changes()") { it.lng(0) } ?: 0L; db.execute(sql); val n = (db.queryOne("SELECT total_changes()") { it.lng(0) } ?: 0L) - before; if (n > 0) done += "removed $n $what" }
            del("DELETE FROM memory_terms WHERE memory_id NOT IN (SELECT id FROM memories WHERE deleted = 0)", "orphaned search index entries")
            del("DELETE FROM archive_links WHERE (a_kind = 'memory' AND a_id NOT IN (SELECT id FROM memories WHERE deleted = 0)) OR (b_kind = 'memory' AND b_id NOT IN (SELECT id FROM memories WHERE deleted = 0))", "relationships to deleted records")
            del("DELETE FROM memory_meta WHERE memory_id NOT IN (SELECT id FROM memories WHERE deleted = 0)", "orphaned metadata rows")
            del("DELETE FROM memory_vectors WHERE kind = 'memory' AND ref_id NOT IN (SELECT id FROM memories WHERE deleted = 0)", "orphaned vectors")
            del("DELETE FROM document_terms WHERE doc_id NOT IN (SELECT id FROM archive_documents WHERE deleted = 0)", "orphaned document index entries")
            del("DELETE FROM document_chunks WHERE doc_id NOT IN (SELECT id FROM archive_documents WHERE deleted = 0)", "orphaned document chunks")
            del("DELETE FROM memory_stats WHERE key LIKE 'len:%' AND substr(key, 5) NOT IN (SELECT id FROM memories WHERE deleted = 0)", "orphaned statistics")
        }
        val missing = db.query("SELECT id FROM memories WHERE deleted = 0 AND category <> 'DIARY' AND (title <> '' OR content <> '') AND id NOT IN (SELECT DISTINCT memory_id FROM memory_terms)") { it.str(0) }
        if (missing.isNotEmpty()) { service.memories.reindexMissing(missing); done += "re-indexed ${missing.size} records" }
        return done
    }

    /** Rebuilds the whole keyword index (after a suspected corruption). Resumes after an interruption. */
    fun reindexAll(maxBatches: Int = Int.MAX_VALUE): Int {
        val job = Jobs.get(db, "reindex")
        val from = if (job?.state == "RUNNING") job.checkpoint else ""
        Jobs.put(db, store.clock.now(), "reindex", "RUNNING", checkpoint = from)
        var batches = 0
        val n = service.memories.reindexAll(from) { last -> Jobs.put(db, store.clock.now(), "reindex", "RUNNING", checkpoint = last); ++batches < maxBatches }
        if (batches < maxBatches) Jobs.put(db, store.clock.now(), "reindex", "IDLE", checkpoint = "", detail = "re-indexed $n records")
        return n
    }
}

/**
 * Everything that runs in the background, in bounded steps: expiry and consolidation, vector backfill (only when an
 * embedder exists), preference questions, and a periodic quick integrity check. Each step checkpoints, stops when
 * [shouldStop] says the PC is busy, and is safe to interrupt at any point.
 */
class MemoryMaintenance(private val store: CluStore, private val settings: SettingsStore, private val service: MemoryService,
                        private val consolidator: Consolidator, private val health: MemoryHealth, private val learner: PreferenceLearner?) {
    private val db = store.db
    data class Result(val consolidation: Consolidator.Report?, val vectorsIndexed: Int, val health: HealthReport?, val steps: List<String>)

    fun runOnce(shouldStop: () -> Boolean = { false }, scanLimit: Int = 100, vectorLimit: Int = 40): Result {
        val steps = mutableListOf<String>()
        Jobs.put(db, store.clock.now(), "maintenance", "RUNNING")
        var report: Consolidator.Report? = null; var vectors = 0; var hr: HealthReport? = null
        try {
            if (Jobs.get(db, "reindex")?.state == "RUNNING") { health.reindexAll(); steps += "finished an interrupted re-index" }
            if (!shouldStop()) { report = consolidator.run(scanLimit, shouldStop); steps += "consolidation: scanned ${report.scanned}, merged ${report.autoMerged}, suggested ${report.suggested}" }
            if (!shouldStop() && service.embedder != null) { vectors = service.indexVectors(vectorLimit, shouldStop); if (vectors > 0) steps += "indexed $vectors memories for semantic search" }
            val lastCheck = Jobs.get(db, "health")?.lastRun ?: 0L
            if (!shouldStop() && store.clock.now() - lastCheck > 24 * 3_600_000L) {
                hr = health.check(quick = true)
                Jobs.put(db, store.clock.now(), "health", "IDLE", detail = if (hr.ok && hr.issues.isEmpty()) "ok" else hr.issues.joinToString { "${it.code}:${it.count}" })
                if (hr.issues.any { it.repairable && it.code != "expired" }) steps += "repaired: " + health.repair().joinToString()
                steps += "integrity check: ${if (hr.ok) "ok" else hr.integrity.take(80)}"
            }
        } finally {
            Jobs.put(db, store.clock.now(), "maintenance", "IDLE", detail = steps.joinToString("; "))
        }
        return Result(report, vectors, hr, steps)
    }
}
