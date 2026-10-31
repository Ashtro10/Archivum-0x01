package ai.clu.core.agents

import ai.clu.core.db.SqlDriver
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.util.Clock
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Removes anything that looks like a credential before it is written anywhere an agent, a log or the window can read it. */
object Redactor {
    private val extra = listOf(
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}") to "$1<hidden>",
        Regex("(?i)((?:token|password|passwd|secret|api[_-]?key|authorization|cookie)\\s*[=:]\\s*)[^\\s,;\"']{4,}") to "$1<hidden>",
        Regex("(?i)\"((?:token|password|passwd|secret|api[_-]?key|authorization|cookie)[A-Za-z_]*)\"\\s*:\\s*\"[^\"]{4,}\"") to "\"$1\":\"<hidden>\"",
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----") to "<private key>",
        Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}") to "<token>",
        Regex("#code=[A-Za-z0-9_-]+") to "#code=<hidden>",
    )
    fun redact(s: String): String = extra.fold(DiagnosticsLog.redact(s)) { acc, (re, rep) -> re.replace(acc, rep) }
}

/** One audit record as stored and shown. */
data class AuditEvent(val seq: Long, val id: String, val ts: Long, val taskId: String, val actor: String, val kind: String, val subject: String, val detail: JsonObject, val hash: String)

data class AuditVerification(val ok: Boolean, val events: Long, val firstBadSeq: Long?, val message: String)

/**
 * The append-only record of everything agents do: task creation, plans, role assignments, permission checks, approvals and
 * rejections, tool calls and their results, errors, cancellations and permission changes.
 *
 * * Append-only in the database itself: triggers refuse UPDATE and DELETE, and no agent tool has database access.
 * * Hash-chained: each row holds the SHA-256 of its own content and the previous row's hash, so a row changed or removed
 *   by someone with direct access to the file is found by [verify].
 * * Never holds secrets: details are passed through [Redactor] and long values are cut before they are stored.
 */
class AuditLog(private val db: SqlDriver, private val clock: Clock) {
    private val lock = Any()

    fun record(kind: String, taskId: String = "", actor: String = "system", subject: String = "", detail: Map<String, Any?> = emptyMap()): AuditEvent = synchronized(lock) {
        val obj = JsonObject(detail.mapValues { (_, v) -> toJson(v) })
        val text = Redactor.redact(CluJson.encodeToString(JsonObject.serializer(), obj)).take(MAX_DETAIL)
        val safe = runCatching { CluJson.parseToJsonElement(text).jsonObject }.getOrElse { buildJsonObject { put("note", text) } } // a cut-off detail is kept as text
        val stored = CluJson.encodeToString(JsonObject.serializer(), safe)
        val ts = clock.now(); val id = newId()
        val prev = db.query("SELECT hash FROM audit_events ORDER BY seq DESC LIMIT 1") { it.str(0) }.firstOrNull() ?: GENESIS
        val hash = hashOf(prev, ts, taskId, actor, kind, subject, stored)
        db.execute("INSERT INTO audit_events(id, ts, task_id, actor, kind, subject, detail, prev_hash, hash) VALUES(?,?,?,?,?,?,?,?,?)", listOf(id, ts, taskId, actor, kind, subject.take(300), stored, prev, hash))
        val seq = db.query("SELECT seq FROM audit_events WHERE id = ?", listOf(id)) { it.lng(0) }.first()
        AuditEvent(seq, id, ts, taskId, actor, kind, subject.take(300), safe, hash)
    }

    fun list(taskId: String? = null, kind: String? = null, kindPrefix: String? = null, limit: Int = 200, beforeSeq: Long? = null): List<AuditEvent> {
        val where = mutableListOf<String>(); val args = mutableListOf<Any?>()
        taskId?.let { where += "task_id = ?"; args += it }; kind?.let { where += "kind = ?"; args += it }
        kindPrefix?.let { where += "kind LIKE ?"; args += "$it%" }; beforeSeq?.let { where += "seq < ?"; args += it }
        val sql = "SELECT seq, id, ts, task_id, actor, kind, subject, detail, hash FROM audit_events" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY seq DESC LIMIT ${limit.coerceIn(1, 1000)}"
        return db.query(sql, args) { r -> AuditEvent(r.lng(0), r.str(1), r.lng(2), r.str(3), r.str(4), r.str(5), r.str(6), runCatching { CluJson.parseToJsonElement(r.str(7)).jsonObject }.getOrDefault(JsonObject(emptyMap())), r.str(8)) }
    }

    fun count(): Long = db.query("SELECT COUNT(*) FROM audit_events") { it.lng(0) }.first()

    /** Recomputes the whole chain. Fails at the first row that does not match its own hash or does not follow its predecessor. */
    fun verify(): AuditVerification {
        var prev = GENESIS; var n = 0L; var bad: Long? = null; var why = ""
        db.query("SELECT seq, ts, task_id, actor, kind, subject, detail, prev_hash, hash FROM audit_events ORDER BY seq") { r ->
            if (bad == null) {
                val seq = r.lng(0)
                if (r.str(7) != prev) { bad = seq; why = "the record before #$seq is missing or changed" }
                else if (hashOf(prev, r.lng(1), r.str(2), r.str(3), r.str(4), r.str(5), r.str(6)) != r.str(8)) { bad = seq; why = "record #$seq was changed" }
                prev = r.str(8); n++
            }
        }
        return if (bad == null) AuditVerification(true, n, null, "All $n records are intact.") else AuditVerification(false, n, bad, why)
    }

    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull; is JsonElement -> v; is Boolean -> JsonPrimitive(v); is Number -> JsonPrimitive(v)
        is Collection<*> -> JsonArray(v.take(50).map(::toJson)); is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
        else -> JsonPrimitive(v.toString().take(MAX_VALUE))
    }

    companion object {
        const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"
        const val MAX_DETAIL = 6000; const val MAX_VALUE = 1200
        fun hashOf(prev: String, ts: Long, task: String, actor: String, kind: String, subject: String, detail: String): String =
            MessageDigest.getInstance("SHA-256").digest("$prev|$ts|$task|$actor|$kind|$subject|$detail".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
