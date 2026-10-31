package ai.clu.core.memory

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.CluStore
import ai.clu.core.db.lng
import ai.clu.core.db.queryOne
import ai.clu.core.db.str
import ai.clu.core.history.MemoryChecks
import ai.clu.core.history.Names
import ai.clu.core.util.newId

/** One line of the consolidation log: what was merged, suggested, approved, rejected or expired, and why. */
data class ConsolidationEntry(
    val id: String, val action: String, val memoryIds: List<String>, val resultId: String?, val status: String, val reason: String,
    val createdAt: Long, val decidedAt: Long?,
)

/**
 * Keeps memory tidy without losing anything.
 *
 * * Exact duplicates (same category, same words, compatible dates) are merged on their own: the later record is folded
 *   into the earlier one through the archive merge, which keeps the folded record in the revision history, repoints its
 *   links, and is logged here as AUTO.
 * * Near-duplicates are only **suggested** (PENDING): approving merges them the same way, rejecting remembers the
 *   decision so the pair is not suggested again.
 * * Expired temporary memories and working-memory items are removed (that is what expiry is for).
 *
 * Every merge can be traced: `consolidation_log` names the records involved and the result; the folded record's earlier
 * text stays in `record_revisions`. A run looks only at records changed since the previous run, a bounded number at a time.
 */
class Consolidator(private val store: CluStore, private val settings: SettingsStore, private val service: MemoryService) {
    private val db = store.db
    private val memories get() = service.memories
    private val archive get() = service.archive

    data class Report(val scanned: Int, val autoMerged: Int, val suggested: Int, val expiredMemories: Int, val expiredWorking: Int)

    fun run(maxScan: Int = 100, shouldStop: () -> Boolean = { false }): Report {
        val expiredMem = memories.purgeExpired()
        val expiredWork = service.working.purgeExpired()
        if (expiredMem + expiredWork > 0) log("expire", emptyList(), null, "AUTO", "$expiredMem expired temporary memories and $expiredWork expired working notes removed")
        if (!settings.bool(Settings.CONSOLIDATION)) return Report(0, 0, 0, expiredMem, expiredWork)

        val since = Jobs.get(db, JOB)?.checkpoint?.toLongOrNull() ?: 0L
        val batch = db.query("SELECT id, updated_at FROM memories WHERE deleted = 0 AND merged_into IS NULL AND updated_at > ? AND category NOT IN ('DIARY','TEMPORARY','INSTRUCTION') ORDER BY updated_at LIMIT $maxScan",
            listOf(since)) { it.str(0) to it.lng(1) }
        var auto = 0; var suggested = 0; var scanned = 0; var last = since
        for ((id, at) in batch) {
            if (shouldStop()) break
            val m = memories.get(id)?.takeIf { !it.deleted && it.mergedInto == null }
            if (m == null) { last = at; scanned++; continue }
            for (cand in memories.rank((m.title + " " + m.content.take(300)).trim(), scopes = null, limit = 8).map { it.memory }) {
                if (cand.id == m.id || cand.deleted || cand.mergedInto != null || cand.category != m.category || cand.scope != m.scope) continue
                val key = pairKey(m.id, cand.id)
                if (decided(key)) continue
                val (keep, other) = if (older(m, cand)) m to cand else cand to m
                when {
                    exact(keep, other) -> { runCatching { merge(keep, other, "AUTO", "same text and dates; the later record was folded into the earlier one", key) }.onSuccess { auto++ } }
                    near(m, cand) -> { log("merge", listOf(keep.id, other.id), null, "PENDING", "nearly the same: \"${keep.title.take(50)}\" and \"${other.title.take(50)}\"", key); suggested++ }
                }
                if (memories.get(m.id)?.deleted != false) break
            }
            last = at; scanned++
        }
        Jobs.put(db, store.clock.now(), JOB, "IDLE", checkpoint = last.toString(), detail = "scanned $scanned, merged $auto, suggested $suggested")
        return Report(scanned, auto, suggested, expiredMem, expiredWork)
    }

    private fun older(a: Memory, b: Memory) = when {
        a.pinned != b.pinned -> a.pinned
        a.createdAt != b.createdAt -> a.createdAt < b.createdAt
        else -> a.id < b.id
    }

    private fun norm(m: Memory) = Names.norm(m.title + " " + m.bodyForPrompt())
    private fun exact(a: Memory, b: Memory) = norm(a) == norm(b) && MemoryChecks.sameDates(a.historicalDate(), b.historicalDate()) && a.people.map { it.lowercase() }.toSet() == b.people.map { it.lowercase() }.toSet()
    private fun near(a: Memory, b: Memory): Boolean {
        // Records about different people, or at incompatible dates, are different records however alike their words are.
        if (!MemoryChecks.sameDates(a.historicalDate(), b.historicalDate())) return false
        val pa = a.people.map { it.lowercase() }.toSet(); val pb = b.people.map { it.lowercase() }.toSet()
        if (pa.isNotEmpty() && pb.isNotEmpty() && (pa intersect pb).isEmpty()) return false
        if (MemoryChecks.duplicates(a, listOf(b)).isNotEmpty()) return true
        val x = TextAnalysis.tokens(norm(a)).toSet(); val y = TextAnalysis.tokens(norm(b)).toSet()
        return x.isNotEmpty() && y.isNotEmpty() && (x intersect y).size.toDouble() / (x union y).size >= 0.85
    }

    private fun merge(keep: Memory, other: Memory, status: String, reason: String, key: String): Memory = db.transaction {
        val merged = archive.mergeMemories(keep.id, other.id)
        service.absorbMeta(keep.id, other.id)
        log("merge", listOf(keep.id, other.id), merged.id, status, reason, key)
        merged
    }

    fun pending(): List<ConsolidationEntry> = entries("WHERE status = 'PENDING' ORDER BY created_at DESC")
    fun activity(limit: Int = 50): List<ConsolidationEntry> = entries("ORDER BY created_at DESC LIMIT $limit")

    /** The owner approves a suggested merge: the later record is folded into the earlier one (history kept). */
    fun approve(id: String): Memory {
        val e = entries("WHERE id = ?", listOf(id)).firstOrNull() ?: error("no such suggestion")
        check(e.status == "PENDING") { "already decided" }
        val a = memories.get(e.memoryIds[0])?.takeIf { !it.deleted } ?: error("a record is gone")
        val b = memories.get(e.memoryIds[1])?.takeIf { !it.deleted } ?: error("a record is gone")
        val merged = db.transaction {
            val m = archive.mergeMemories(a.id, b.id)
            service.absorbMeta(a.id, b.id)
            db.execute("UPDATE consolidation_log SET status = 'APPROVED', result_id = ?, decided_at = ? WHERE id = ?", listOf(m.id, store.clock.now(), id))
            m
        }
        return merged
    }

    fun reject(id: String) { db.execute("UPDATE consolidation_log SET status = 'REJECTED', decided_at = ? WHERE id = ? AND status = 'PENDING'", listOf(store.clock.now(), id)) }

    private fun decided(key: String) = (db.queryOne("SELECT COUNT(*) FROM consolidation_log WHERE pair_key = ?", listOf(key)) { it.lng(0) } ?: 0L) > 0
    private fun pairKey(a: String, b: String) = listOf(a, b).sorted().joinToString("|")

    private fun log(action: String, ids: List<String>, result: String?, status: String, reason: String, key: String = "") {
        db.execute("INSERT INTO consolidation_log(id, action, memory_ids, result_id, status, reason, pair_key, created_at, decided_at) VALUES(?,?,?,?,?,?,?,?,?)",
            listOf(newId(), action, ids.joinToString(","), result, status, reason, key, store.clock.now(), if (status == "PENDING") null else store.clock.now()))
    }

    private fun entries(where: String, args: List<Any?> = emptyList()) =
        db.query("SELECT id, action, memory_ids, result_id, status, reason, created_at, decided_at FROM consolidation_log $where", args) {
            ConsolidationEntry(it.str(0), it.str(1), it.str(2).split(',').filter { s -> s.isNotBlank() }, it.string(3), it.str(4), it.str(5), it.lng(6), it.long(7))
        }

    companion object { const val JOB = "consolidate" }
}

/** Checkpoints for background work: an interrupted run resumes from where it stopped instead of starting over. */
object Jobs {
    data class State(val name: String, val state: String, val checkpoint: String, val detail: String, val lastRun: Long?, val updatedAt: Long)

    fun get(db: ai.clu.core.db.SqlDriver, name: String): State? =
        db.queryOne("SELECT name, state, checkpoint, detail, last_run, updated_at FROM maintenance_jobs WHERE name = ?", listOf(name)) { State(it.str(0), it.str(1), it.str(2), it.str(3), it.long(4), it.lng(5)) }

    fun all(db: ai.clu.core.db.SqlDriver): List<State> =
        db.query("SELECT name, state, checkpoint, detail, last_run, updated_at FROM maintenance_jobs ORDER BY name") { State(it.str(0), it.str(1), it.str(2), it.str(3), it.long(4), it.lng(5)) }

    fun put(db: ai.clu.core.db.SqlDriver, now: Long, name: String, state: String, checkpoint: String? = null, detail: String = "") {
        val cur = get(db, name)
        db.execute("""INSERT INTO maintenance_jobs(name, state, checkpoint, detail, last_run, updated_at) VALUES(?,?,?,?,?,?)
            ON CONFLICT(name) DO UPDATE SET state=excluded.state, checkpoint=excluded.checkpoint, detail=excluded.detail, last_run=excluded.last_run, updated_at=excluded.updated_at""",
            listOf(name, state, checkpoint ?: cur?.checkpoint ?: "", detail, if (state == "RUNNING") cur?.lastRun else now, now))
    }
}
