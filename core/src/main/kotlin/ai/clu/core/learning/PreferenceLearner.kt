package ai.clu.core.learning

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.CluStore
import ai.clu.core.db.bool
import ai.clu.core.db.lng
import ai.clu.core.db.queryOne
import ai.clu.core.db.str
import ai.clu.core.util.newId

/** How far a preference has been established. Only the owner can move it to STABLE (by saying so, or approving the question). */
enum class PrefScope(val label: String) {
    ONCE("Said once"), TASK("For this kind of task"), RECURRING("Keeps coming up"), STABLE("Standing preference")
}

data class PrefStatus(val target: String, val value: String, val scope: PrefScope, val signals: Int, val negative: Int, val conversations: Int, val days: Int, val tasks: Set<String>)

data class PrefCandidate(val id: String, val target: String, val value: String, val question: String, val evidence: String, val status: String, val createdAt: Long)

/**
 * Learns how the owner wants replies, without guessing. Every explicit signal (a feedback command, a thumbs-down with a
 * reason, a choice between two replies, a correction) is recorded with where it came from. What a pile of signals
 * means is decided by simple, visible rules:
 *
 * * one signal: ONCE: it changes nothing beyond what the owner explicitly asked for;
 * * two or more for the same kind of task: TASK: applied to that kind of task only;
 * * three or more across at least two conversations on at least two different days: RECURRING: CLU **asks** whether
 *   to make it a standing preference; it never promotes by itself;
 * * accepted (or stated with "from now on" / "always"): STABLE.
 *
 * Rejecting the question is remembered: it is not asked again until three more signals have arrived.
 * `target` is `setting:<key>` (a settings value, applied through the settings registry), `pref:<dimension>` (a learned
 * dimension) or `instruction` (free text kept as an instruction for CLU).
 */
class PreferenceLearner(private val store: CluStore, private val settings: SettingsStore, private val prefs: PreferenceEngine,
                        private val saveInstruction: (String) -> Unit = {}) {
    private val db = store.db

    fun record(target: String, value: String, polarity: Int = 1, explicit: Boolean = false, taskTag: String = "", conversationId: String = "", note: String = ""): PrefStatus {
        require(target.startsWith("setting:") || target.startsWith("pref:") || target == "instruction") { "unknown target $target" }
        if (!settings.bool(Settings.LEARN_PREFERENCES)) return status(target, value)
        db.execute("INSERT INTO preference_signals(id, target, value, polarity, explicit, task_tag, conversation_id, note, created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            listOf(newId(), target, value, if (polarity >= 0) 1 else -1, explicit, taskTag.lowercase().take(40), conversationId, note.take(300), store.clock.now()))
        val st = status(target, value)
        if (polarity >= 0 && st.scope == PrefScope.RECURRING) askIfNeeded(st)
        return st
    }

    /** Where this preference stands, from the recorded signals. */
    fun status(target: String, value: String): PrefStatus {
        val rows = db.query("SELECT polarity, explicit, task_tag, conversation_id, created_at FROM preference_signals WHERE target = ? AND value = ?", listOf(target, value)) {
            arrayOf(it.lng(0), it.lng(1), it.str(2), it.str(3), it.lng(4))
        }
        val pos = rows.filter { it[0] as Long > 0 }
        val tasks = pos.map { it[2] as String }.filter { it.isNotEmpty() }
        val convs = pos.map { it[3] as String }.filter { it.isNotEmpty() }.toSet().size
        val days = pos.map { (it[4] as Long) / 86_400_000L }.toSet().size
        val accepted = (db.queryOne("SELECT COUNT(*) FROM preference_candidates WHERE target = ? AND value = ? AND status = 'ACCEPTED'", listOf(target, value)) { it.lng(0) } ?: 0L) > 0
        val scope = when {
            accepted -> PrefScope.STABLE
            pos.isEmpty() -> PrefScope.ONCE
            pos.size >= 3 && convs >= 2 && days >= 2 -> PrefScope.RECURRING
            tasks.groupingBy { it }.eachCount().any { it.value >= 2 } -> PrefScope.TASK
            else -> PrefScope.ONCE
        }
        return PrefStatus(target, value, scope, pos.size, rows.size - pos.size, convs, days, tasks.toSet())
    }

    /** Statuses of everything ever signalled, strongest first (the "what CLU has noticed" list). */
    fun overview(): List<PrefStatus> =
        db.query("SELECT DISTINCT target, value FROM preference_signals") { it.str(0) to it.str(1) }.map { (t, v) -> status(t, v) }.sortedByDescending { it.scope.ordinal * 100 + it.signals }

    private fun askIfNeeded(st: PrefStatus) {
        val prior = db.query("SELECT status, signals_at_decision FROM preference_candidates WHERE target = ? AND value = ? ORDER BY created_at DESC LIMIT 1", listOf(st.target, st.value)) { it.str(0) to it.lng(1) }.firstOrNull()
        if (prior != null && prior.first == "PENDING") return
        if (prior != null && prior.first == "REJECTED" && st.signals < prior.second + 3) return
        val q = "You've asked for this ${st.signals} times, in ${st.conversations} conversations: ${describe(st)}. Make it my standing preference?"
        db.execute("INSERT INTO preference_candidates(id, target, value, question, evidence, status, signals_at_decision, created_at) VALUES(?,?,?,?,?,'PENDING',?,?)",
            listOf(newId(), st.target, st.value, q, evidence(st), st.signals, store.clock.now()))
    }

    fun describe(st: PrefStatus): String = when {
        st.target.startsWith("setting:") -> "${Settings[st.target.removePrefix("setting:")]?.title ?: st.target.removePrefix("setting:")} → ${st.value}"
        st.target.startsWith("pref:") -> prefs.dimensions.firstOrNull { it.key == st.target.removePrefix("pref:") }?.let { d -> "${d.title}: ${d.labels[st.value] ?: st.value}" } ?: st.value
        else -> "\"${st.value.take(80)}\""
    }

    private fun evidence(st: PrefStatus) = db.query("SELECT created_at, note, explicit FROM preference_signals WHERE target = ? AND value = ? ORDER BY created_at DESC LIMIT 5", listOf(st.target, st.value)) {
        "${java.time.Instant.ofEpochMilli(it.lng(0)).atZone(java.time.ZoneOffset.UTC).toLocalDate()}${if (it.bool(2)) " (you said so)" else ""}${it.str(1).let { n -> if (n.isNotBlank()) ": $n" else "" }}"
    }.joinToString("; ")

    fun pending(): List<PrefCandidate> = db.query("SELECT id, target, value, question, evidence, status, created_at FROM preference_candidates WHERE status = 'PENDING' ORDER BY created_at DESC") {
        PrefCandidate(it.str(0), it.str(1), it.str(2), it.str(3), it.str(4), it.str(5), it.lng(6))
    }

    /** The owner agrees: the preference becomes standing and is applied the way its target says. */
    fun accept(id: String) {
        val c = candidate(id) ?: error("no such question"); check(c.status == "PENDING") { "already decided" }
        db.transaction {
            apply(c.target, c.value)
            db.execute("UPDATE preference_candidates SET status = 'ACCEPTED', decided_at = ? WHERE id = ?", listOf(store.clock.now(), id))
        }
    }

    fun reject(id: String) {
        val c = candidate(id) ?: error("no such question"); check(c.status == "PENDING") { "already decided" }
        db.execute("UPDATE preference_candidates SET status = 'REJECTED', decided_at = ?, signals_at_decision = ? WHERE id = ?", listOf(store.clock.now(), status(c.target, c.value).signals, id))
    }

    private fun candidate(id: String) = db.query("SELECT id, target, value, question, evidence, status, created_at FROM preference_candidates WHERE id = ?", listOf(id)) {
        PrefCandidate(it.str(0), it.str(1), it.str(2), it.str(3), it.str(4), it.str(5), it.lng(6)) }.firstOrNull()

    private fun apply(target: String, value: String) {
        when {
            target.startsWith("setting:") -> {
                val key = target.removePrefix("setting:")
                val r = when { value == "true" -> settings.set(key, true); value == "false" -> settings.set(key, false)
                    value.toIntOrNull() != null && Settings[key]?.type is ai.clu.core.config.SettingType.IntRange -> settings.set(key, value.toInt())
                    else -> settings.set(key, value) }
                check(r == ai.clu.core.config.SettingResult.Ok) { "couldn't apply $key: $r" }
            }
            target.startsWith("pref:") -> prefs.set(target.removePrefix("pref:"), value, PreferenceSource.FEEDBACK, "Confirmed after repeated feedback")
            else -> saveInstruction(value)
        }
    }

    /** Instructions that hold only for a kind of task (scope TASK, matched by the task tag), for the prompt of that task. */
    fun taskInstructions(taskTag: String): List<String> {
        if (taskTag.isBlank() || !settings.bool(Settings.LEARN_PREFERENCES)) return emptyList()
        return db.query("SELECT DISTINCT target, value FROM preference_signals WHERE task_tag = ? AND polarity > 0", listOf(taskTag.lowercase())) { it.str(0) to it.str(1) }
            .map { (t, v) -> status(t, v) }.filter { it.scope == PrefScope.TASK && taskTag.lowercase() in it.tasks }
            .mapNotNull { st -> when {
                st.target.startsWith("pref:") -> prefs.dimensions.firstOrNull { it.key == st.target.removePrefix("pref:") }?.instructions?.get(st.value)
                st.target == "instruction" -> st.value
                else -> null } }
    }

    /** Forget everything recorded (the owner's reset). Standing preferences already applied stay until changed in Settings. */
    fun clearSignals() { db.execute("DELETE FROM preference_signals"); db.execute("DELETE FROM preference_candidates WHERE status <> 'ACCEPTED'") }
}
