package ai.clu.core.diag

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.queryOne
import ai.clu.core.db.CluStore
import ai.clu.core.db.lng
import ai.clu.core.db.str

enum class Level { DEBUG, INFO, WARN, ERROR }

data class DiagEvent(val ts: Long, val level: Level, val tag: String, val message: String)

/**
 * Bounded diagnostics log (SQLite). Respects the configured level and capacity. Secrets must never be logged:
 * [redact] removes anything that looks like a PIN, key or bearer token.
 */
class DiagnosticsLog(private val store: CluStore, private val settings: SettingsStore) {
    private val db = store.db
    private var writes = 0

    fun log(level: Level, tag: String, message: String) {
        val min = when (settings.string(Settings.DEV_LOG_LEVEL)) { "error" -> Level.ERROR; "warn" -> Level.WARN; "debug" -> Level.DEBUG; else -> Level.INFO }
        if (level < min) return
        db.execute("INSERT INTO diag_events(ts, level, tag, message) VALUES(?,?,?,?)", listOf(store.clock.now(), level.name, tag, redact(message).take(4000)))
        if (++writes % 50 == 0) trim()
    }

    fun d(tag: String, m: String) = log(Level.DEBUG, tag, m)
    fun i(tag: String, m: String) = log(Level.INFO, tag, m)
    fun w(tag: String, m: String) = log(Level.WARN, tag, m)
    fun e(tag: String, m: String, t: Throwable? = null) = log(Level.ERROR, tag, if (t != null) "$m: ${t::class.simpleName}: ${t.message}" else m)

    fun trim() {
        val cap = settings.int(Settings.DEV_LOG_CAP)
        db.execute("DELETE FROM diag_events WHERE id <= (SELECT MAX(id) FROM diag_events) - ?", listOf(cap))
    }

    fun recent(limit: Int = 500, minLevel: Level = Level.DEBUG): List<DiagEvent> =
        db.query("SELECT ts, level, tag, message FROM diag_events ORDER BY id DESC LIMIT $limit") {
            DiagEvent(it.lng(0), Level.valueOf(it.str(1)), it.str(2), it.str(3))
        }.filter { it.level >= minLevel }

    fun count(): Long = db.queryOne("SELECT COUNT(*) FROM diag_events") { it.lng(0) } ?: 0
    fun clear() = db.execute("DELETE FROM diag_events")

    fun perf(metric: String, value: Double, detail: String = "") {
        db.execute("INSERT INTO perf_samples(ts, metric, value, detail) VALUES(?,?,?,?)", listOf(store.clock.now(), metric, value, detail))
        db.execute("DELETE FROM perf_samples WHERE id <= (SELECT MAX(id) FROM perf_samples) - 5000")
    }

    data class PerfSummary(val metric: String, val count: Long, val avg: Double, val min: Double, val max: Double, val last: Double)

    fun perfSummary(): List<PerfSummary> = db.query(
        """SELECT metric, COUNT(*), AVG(value), MIN(value), MAX(value),
           (SELECT value FROM perf_samples p2 WHERE p2.metric = p.metric ORDER BY id DESC LIMIT 1)
           FROM perf_samples p GROUP BY metric ORDER BY metric""") {
        PerfSummary(it.str(0), it.lng(1), it.double(2) ?: 0.0, it.double(3) ?: 0.0, it.double(4) ?: 0.0, it.double(5) ?: 0.0)
    }

    companion object {
        private val secretPatterns = listOf(
            Regex("(?i)(pin|password|passphrase|secret|api[_-]?key|token)\\s*[:=]\\s*\\S+") to "$1=<redacted>",
            Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]+") to "Bearer <redacted>",
            Regex("sk-[A-Za-z0-9]{12,}") to "<redacted-key>",
        )
        fun redact(s: String): String = secretPatterns.fold(s) { acc, (re, rep) -> re.replace(acc, rep) }
    }
}
