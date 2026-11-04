package ai.clu.desktop.capability

import ai.clu.core.db.Schema
import ai.clu.core.db.SqlDriver
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.util.CluJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** One file of an installed capability version, with its hash (integrity is re-checked before it is served as installed). */
@Serializable data class CapFile(val path: String, val sha256: String, val size: Long)

/** The verification evidence of one version: every check that ran, what it found, and the screenshot taken of the rendered result. */
@Serializable data class Verification(
    val passed: Boolean, val checks: List<Check>, val screenshot: String? = null, val browser: String = "", val attempts: Int = 1,
    /** What could not be verified here (e.g. live data while the provider is unreachable). Never counted as a pass. */
    val unverified: List<String> = emptyList(),
) {
    @Serializable data class Check(val name: String, val ok: Boolean, val detail: String = "")
}

/** A capability: one experience of one family (`map`, `weather`, `dashboard`), with its versions on disk. */
data class Capability(
    val id: String, val kind: String, val style: String, val name: String, val description: String, val category: String,
    val createdAt: Long, val updatedAt: Long, val activeVersion: Int, val enabled: Boolean, val uninstalled: Boolean,
    val config: JsonObject, val limitations: List<String>, val dependencies: List<String>, val interfaces: List<String>, val recovery: String,
)

data class CapVersion(
    val capId: String, val version: Int, val dir: String, val files: List<CapFile>, val blueprint: Blueprint, val verification: Verification?,
    val verified: Boolean, val installedAt: Long, val runId: String, val notes: String,
)

/** One development run (create, improve, variant): what was asked, understood, decided, tried, fixed and verified. */
data class DevRun(
    val id: String, val request: String, val op: String, val kind: String, val style: String, val capId: String?, val state: String,
    val requirements: List<String>, val research: List<String>, val decisions: List<String>, val files: List<String>, val models: List<String>,
    val errors: List<String>, val fixes: List<String>, val verification: Verification?, val version: Int, val limitations: List<String>,
    val startedAt: Long, val finishedAt: Long, val conversationId: String?, val message: String,
) {
    companion object { const val RUNNING = "running"; const val INSTALLED = "installed"; const val FAILED = "failed"; const val DECLINED = "declined" }
}

data class DevStep(val runId: String, val at: Long, val step: String, val status: String, val detail: String)

/**
 * CLU's capability registry and development memory (its own SQLite database in the data folder): capabilities, their
 * versions (each a folder of verified files), preferences such as the default experience per family, the history of
 * every create/update/switch/rollback, and every development run with its steps.
 */
class CapabilityStore(val db: SqlDriver, private val now: () -> Long = System::currentTimeMillis) {
    init { Schema.migrate(db, steps = MIGRATIONS, target = MIGRATIONS.size) }

    private val json = CluJson
    private inline fun <reified T> enc(v: T) = json.encodeToString(v)
    private fun list(s: String): List<String> = runCatching { json.decodeFromString<List<String>>(s) }.getOrDefault(emptyList())
    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    // ------------------------------------------------------------------ capabilities
    private val CAP_COLS = "id, kind, style, name, description, category, created_at, updated_at, active_version, enabled, uninstalled, config, limitations, dependencies, interfaces, recovery"
    private fun cap(r: ai.clu.core.db.SqlRow) = Capability(r.str(0), r.str(1), r.str(2), r.str(3), r.str(4), r.str(5), r.lng(6), r.lng(7), r.lng(8).toInt(), r.lng(9) != 0L, r.lng(10) != 0L,
        runCatching { json.parseToJsonElement(r.str(11)) as JsonObject }.getOrDefault(JsonObject(emptyMap())), list(r.str(12)), list(r.str(13)), list(r.str(14)), r.str(15))

    fun all(includeUninstalled: Boolean = false): List<Capability> =
        db.query("SELECT $CAP_COLS FROM capabilities ${if (includeUninstalled) "" else "WHERE uninstalled = 0"} ORDER BY kind, created_at", emptyList(), ::cap)
    fun get(id: String): Capability? = db.query("SELECT $CAP_COLS FROM capabilities WHERE id = ?", listOf(id), ::cap).firstOrNull()

    fun create(kind: String, style: String, name: String, description: String, category: String, dependencies: List<String>, interfaces: List<String>): Capability {
        val base = (kind + "-" + style).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
        var id = base; var n = 2
        while (get(id) != null) id = "$base-${n++}"
        val t = now()
        db.execute("INSERT INTO capabilities($CAP_COLS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(id, kind, style, name, description, category, t, t, 0, 1, 0, "{}", "[]", enc(dependencies), enc(interfaces), ""))
        event(id, "created", "$name ($kind, $style)")
        return get(id)!!
    }

    fun update(id: String, name: String? = null, description: String? = null, activeVersion: Int? = null, enabled: Boolean? = null, uninstalled: Boolean? = null,
               config: JsonObject? = null, limitations: List<String>? = null, dependencies: List<String>? = null, interfaces: List<String>? = null, recovery: String? = null) {
        val c = get(id) ?: return
        db.execute("UPDATE capabilities SET name=?, description=?, active_version=?, enabled=?, uninstalled=?, config=?, limitations=?, dependencies=?, interfaces=?, recovery=?, updated_at=? WHERE id=?",
            listOf(name ?: c.name, description ?: c.description, activeVersion ?: c.activeVersion, if (enabled ?: c.enabled) 1 else 0, if (uninstalled ?: c.uninstalled) 1 else 0,
                (config ?: c.config).toString(), enc(limitations ?: c.limitations), enc(dependencies ?: c.dependencies), enc(interfaces ?: c.interfaces), recovery ?: c.recovery, now(), id))
    }

    // ------------------------------------------------------------------ versions
    private val VER_COLS = "cap_id, version, dir, files, blueprint, verification, verified, installed_at, run_id, notes"
    private fun ver(r: ai.clu.core.db.SqlRow) = CapVersion(r.str(0), r.lng(1).toInt(), r.str(2),
        runCatching { json.decodeFromString<List<CapFile>>(r.str(3)) }.getOrDefault(emptyList()), json.decodeFromString(r.str(4)),
        r.str(5).takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<Verification>(it) }.getOrNull() }, r.lng(6) != 0L, r.lng(7), r.str(8), r.str(9))

    fun versions(capId: String): List<CapVersion> = db.query("SELECT $VER_COLS FROM capability_versions WHERE cap_id = ? ORDER BY version", listOf(capId), ::ver)
    fun version(capId: String, v: Int): CapVersion? = db.query("SELECT $VER_COLS FROM capability_versions WHERE cap_id = ? AND version = ?", listOf(capId, v), ::ver).firstOrNull()
    fun active(capId: String): CapVersion? = get(capId)?.let { c -> version(capId, c.activeVersion) }
    fun nextVersion(capId: String): Int = (db.query("SELECT MAX(version) FROM capability_versions WHERE cap_id = ?", listOf(capId)) { it.long(0) }.firstOrNull() ?: 0L).toInt() + 1

    fun addVersion(v: CapVersion) {
        db.execute("INSERT INTO capability_versions($VER_COLS) VALUES(?,?,?,?,?,?,?,?,?,?)",
            listOf(v.capId, v.version, v.dir, enc(v.files), enc(v.blueprint), v.verification?.let { enc(it) } ?: "", if (v.verified) 1 else 0, v.installedAt, v.runId, v.notes))
    }

    // ------------------------------------------------------------------ preferences (default experience per family, development model choices)
    fun pref(key: String): String? = db.query("SELECT value FROM capability_prefs WHERE key = ?", listOf(key)) { it.str(0) }.firstOrNull()
    fun setPref(key: String, value: String?) {
        if (value == null) db.execute("DELETE FROM capability_prefs WHERE key = ?", listOf(key))
        else db.execute("INSERT INTO capability_prefs(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", listOf(key, value))
    }
    fun defaultFor(kind: String): String? = pref("default.$kind")

    // ------------------------------------------------------------------ history of each capability
    data class Event(val capId: String, val at: Long, val kind: String, val detail: String)
    fun event(capId: String, kind: String, detail: String) =
        db.execute("INSERT INTO capability_events(id, cap_id, at, kind, detail) VALUES(?,?,?,?,?)", listOf(newId(), capId, now(), kind, DiagnosticsLog.redact(detail).take(4000)))
    fun events(capId: String): List<Event> = db.query("SELECT cap_id, at, kind, detail FROM capability_events WHERE cap_id = ? ORDER BY at, rowid", listOf(capId)) { Event(it.str(0), it.lng(1), it.str(2), it.str(3)) }

    // ------------------------------------------------------------------ development memory
    private val RUN_COLS = "id, request, op, kind, style, cap_id, state, requirements, research, decisions, files, models, errors, fixes, verification, version, limitations, started_at, finished_at, conversation_id, message"
    private fun run(r: ai.clu.core.db.SqlRow) = DevRun(r.str(0), r.str(1), r.str(2), r.str(3), r.str(4), r.string(5), r.str(6), list(r.str(7)), list(r.str(8)), list(r.str(9)), list(r.str(10)),
        list(r.str(11)), list(r.str(12)), list(r.str(13)), r.str(14).takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<Verification>(it) }.getOrNull() },
        r.lng(15).toInt(), list(r.str(16)), r.lng(17), r.lng(18), r.string(19), r.str(20))

    fun saveRun(d: DevRun) {
        db.execute("INSERT INTO dev_runs($RUN_COLS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET request=excluded.request, op=excluded.op, kind=excluded.kind, " +
            "style=excluded.style, cap_id=excluded.cap_id, state=excluded.state, requirements=excluded.requirements, research=excluded.research, decisions=excluded.decisions, files=excluded.files, " +
            "models=excluded.models, errors=excluded.errors, fixes=excluded.fixes, verification=excluded.verification, version=excluded.version, limitations=excluded.limitations, " +
            "finished_at=excluded.finished_at, message=excluded.message",
            listOf(d.id, DiagnosticsLog.redact(d.request).take(2000), d.op, d.kind, d.style, d.capId, d.state, enc(d.requirements), enc(d.research), enc(d.decisions), enc(d.files), enc(d.models),
                enc(d.errors.map { DiagnosticsLog.redact(it).take(2000) }), enc(d.fixes), d.verification?.let { enc(it) } ?: "", d.version, enc(d.limitations), d.startedAt, d.finishedAt, d.conversationId, d.message.take(4000)))
    }
    fun run(id: String): DevRun? = db.query("SELECT $RUN_COLS FROM dev_runs WHERE id = ?", listOf(id), ::run).firstOrNull()
    fun runs(limit: Int = 50, kind: String? = null): List<DevRun> =
        db.query("SELECT $RUN_COLS FROM dev_runs ${if (kind != null) "WHERE kind = ?" else ""} ORDER BY started_at DESC, rowid DESC LIMIT ${limit.coerceIn(1, 500)}", listOfNotNull(kind), ::run)

    fun step(runId: String, step: String, status: String, detail: String = "") =
        db.execute("INSERT INTO dev_steps(run_id, at, step, status, detail) VALUES(?,?,?,?,?)", listOf(runId, now(), step, status, DiagnosticsLog.redact(detail).take(4000)))
    fun steps(runId: String): List<DevStep> = db.query("SELECT run_id, at, step, status, detail FROM dev_steps WHERE run_id = ? ORDER BY at, rowid", listOf(runId)) { DevStep(it.str(0), it.lng(1), it.str(2), it.str(3), it.str(4)) }

    // ------------------------------------------------------------------ measured model ability (per task), used to choose a development model
    data class ModelScore(val model: String, val task: String, val attempts: Int, val successes: Int, val avgMs: Long)
    fun recordModel(model: String, task: String, ok: Boolean, ms: Long) {
        val s = score(model, task)
        val attempts = (s?.attempts ?: 0) + 1; val successes = (s?.successes ?: 0) + if (ok) 1 else 0
        val avg = if (s == null) ms else (s.avgMs * s.attempts + ms) / attempts
        db.execute("INSERT INTO model_scores(model, task, attempts, successes, avg_ms, updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(model, task) DO UPDATE SET attempts=excluded.attempts, " +
            "successes=excluded.successes, avg_ms=excluded.avg_ms, updated_at=excluded.updated_at", listOf(model, task, attempts, successes, avg, now()))
    }
    fun score(model: String, task: String): ModelScore? = db.query("SELECT model, task, attempts, successes, avg_ms FROM model_scores WHERE model = ? AND task = ?", listOf(model, task)) {
        ModelScore(it.str(0), it.str(1), it.lng(2).toInt(), it.lng(3).toInt(), it.lng(4)) }.firstOrNull()
    fun scores(): List<ModelScore> = db.query("SELECT model, task, attempts, successes, avg_ms FROM model_scores ORDER BY model, task", emptyList()) {
        ModelScore(it.str(0), it.str(1), it.lng(2).toInt(), it.lng(3).toInt(), it.lng(4)) }

    companion object {
        val MIGRATIONS: List<List<String>> = listOf(listOf(
            """CREATE TABLE capabilities (id TEXT PRIMARY KEY, kind TEXT NOT NULL, style TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, category TEXT NOT NULL,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, active_version INTEGER NOT NULL, enabled INTEGER NOT NULL, uninstalled INTEGER NOT NULL,
                config TEXT NOT NULL, limitations TEXT NOT NULL, dependencies TEXT NOT NULL, interfaces TEXT NOT NULL, recovery TEXT NOT NULL)""",
            """CREATE TABLE capability_versions (cap_id TEXT NOT NULL, version INTEGER NOT NULL, dir TEXT NOT NULL, files TEXT NOT NULL, blueprint TEXT NOT NULL,
                verification TEXT NOT NULL, verified INTEGER NOT NULL, installed_at INTEGER NOT NULL, run_id TEXT NOT NULL, notes TEXT NOT NULL, PRIMARY KEY (cap_id, version))""",
            """CREATE TABLE capability_prefs (key TEXT PRIMARY KEY, value TEXT NOT NULL)""",
            """CREATE TABLE capability_events (id TEXT PRIMARY KEY, cap_id TEXT NOT NULL, at INTEGER NOT NULL, kind TEXT NOT NULL, detail TEXT NOT NULL)""",
            """CREATE TABLE dev_runs (id TEXT PRIMARY KEY, request TEXT NOT NULL, op TEXT NOT NULL, kind TEXT NOT NULL, style TEXT NOT NULL, cap_id TEXT, state TEXT NOT NULL,
                requirements TEXT NOT NULL, research TEXT NOT NULL, decisions TEXT NOT NULL, files TEXT NOT NULL, models TEXT NOT NULL, errors TEXT NOT NULL, fixes TEXT NOT NULL,
                verification TEXT NOT NULL, version INTEGER NOT NULL, limitations TEXT NOT NULL, started_at INTEGER NOT NULL, finished_at INTEGER NOT NULL, conversation_id TEXT, message TEXT NOT NULL)""",
            """CREATE TABLE dev_steps (run_id TEXT NOT NULL, at INTEGER NOT NULL, step TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL)""",
            """CREATE INDEX dev_steps_run ON dev_steps(run_id)""",
            """CREATE TABLE model_scores (model TEXT NOT NULL, task TEXT NOT NULL, attempts INTEGER NOT NULL, successes INTEGER NOT NULL, avg_ms INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, PRIMARY KEY (model, task))""",
        ))
    }
}
