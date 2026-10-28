package ai.clu.core.learning

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.CluStore
import ai.clu.core.db.bool
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.util.newId

enum class PreferenceSource(val label: String) {
    QUESTION("Answer to a preference question"),
    ALTERNATIVE_CHOICE("Chosen between two alternative replies"),
    MANUAL("Set manually"),
    FEEDBACK("Response feedback"),
}

data class Preference(val key: String, val value: String, val source: PreferenceSource, val note: String, val updatedAt: Long)

/** A preference dimension with the prompt instruction for each value. */
data class PreferenceDimension(val key: String, val title: String, val instructions: Map<String, String>, val labels: Map<String, String>)

data class PreferenceQuestion(val id: String, val dimension: String, val text: String, val options: List<Pair<String, String>>)

data class AlternativeOffer(val dimension: String, val optionA: String, val optionB: String, val styleA: String, val styleB: String)

/**
 * Learns how the owner wants responses delivered, from explicit choices only (questions and A/B picks).
 * Learned preferences become prompt instructions, so they measurably change generation.
 */
class PreferenceEngine(private val store: CluStore, private val settings: SettingsStore) {
    private val db = store.db

    val dimensions: List<PreferenceDimension> = listOf(
        PreferenceDimension("verdict_order", "Decision answers",
            mapOf("verdict_first" to "When evaluating a decision, state your verdict first, then the reasoning.",
                "reasoning_first" to "When evaluating a decision, explain the reasoning first and end with the verdict.",
                "compare_first" to "When evaluating a decision, compare both sides before giving a verdict."),
            mapOf("verdict_first" to "Verdict first", "reasoning_first" to "Reasoning first", "compare_first" to "Compare both sides first")),
        PreferenceDimension("detail", "Level of detail",
            mapOf("concise" to "The owner prefers concise answers: lead with the answer, minimal elaboration.",
                "detailed" to "The owner prefers detailed answers with thorough explanation."),
            mapOf("concise" to "Concise", "detailed" to "Detailed")),
        PreferenceDimension("tone", "Tone",
            mapOf("direct" to "Be direct and unvarnished.", "diplomatic" to "Be tactful and diplomatic when delivering criticism."),
            mapOf("direct" to "Direct", "diplomatic" to "Diplomatic")),
        PreferenceDimension("recommendations", "Recommendations",
            mapOf("practical" to "Give a single practical recommendation unless asked for options.",
                "comprehensive" to "Lay out the options comprehensively before recommending."),
            mapOf("practical" to "One practical recommendation", "comprehensive" to "Comprehensive analysis")),
        PreferenceDimension("examples", "Examples",
            mapOf("include" to "Include a concrete example when explaining something.", "skip" to "Skip examples unless asked."),
            mapOf("include" to "Include examples", "skip" to "Skip examples")),
    )

    val questions: List<PreferenceQuestion> = listOf(
        PreferenceQuestion("q_verdict", "verdict_order", "When you ask me to evaluate a decision, would you prefer that I:",
            listOf("verdict_first" to "Give the verdict first", "reasoning_first" to "Explain the reasoning first", "compare_first" to "Compare both sides before deciding")),
        PreferenceQuestion("q_detail", "detail", "By default, how much detail do you want in my answers?",
            listOf("concise" to "Short and to the point", "detailed" to "Thorough explanations")),
        PreferenceQuestion("q_tone", "tone", "When I disagree with you, how should I put it?",
            listOf("direct" to "Bluntly", "diplomatic" to "Tactfully")),
        PreferenceQuestion("q_recs", "recommendations", "When you ask for advice, do you want:",
            listOf("practical" to "My single best recommendation", "comprehensive" to "All the options laid out")),
        PreferenceQuestion("q_examples", "examples", "When I explain something, should I include an example?",
            listOf("include" to "Yes, usually", "skip" to "Only if I ask")),
    )

    fun all(): List<Preference> = db.query("SELECT key, value, source, note, updated_at FROM preferences WHERE deleted = 0 ORDER BY key") {
        Preference(it.str(0), it.str(1), PreferenceSource.valueOf(it.str(2)), it.str(3), it.lng(4))
    }

    fun get(key: String): Preference? = all().firstOrNull { it.key == key }

    fun set(key: String, value: String, source: PreferenceSource, note: String = "") {
        val dim = dimensions.firstOrNull { it.key == key }
        require(dim == null || value in dim.instructions) { "invalid value $value for $key" }
        val now = store.clock.now()
        db.transaction {
            val hlc = store.stamp(TABLE, key)
            db.execute("""INSERT INTO preferences(key, value, source, note, created_at, updated_at, deleted, hlc, origin) VALUES(?,?,?,?,?,?,0,?,?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value, source=excluded.source, note=excluded.note, updated_at=excluded.updated_at,
                deleted=0, hlc=excluded.hlc, origin=excluded.origin""", listOf(key, value, source.name, note, now, now, hlc, store.deviceId))
        }
        store.notifyChanged(TABLE)
    }

    fun remove(key: String) {
        db.transaction {
            val hlc = store.stamp(TABLE, key)
            db.execute("UPDATE preferences SET deleted = 1, updated_at = ?, hlc = ?, origin = ? WHERE key = ?", listOf(store.clock.now(), hlc, store.deviceId, key))
        }
        store.notifyChanged(TABLE)
    }

    fun resetAll() = all().forEach { remove(it.key) }

    /** Prompt instructions from learned preferences (empty when adaptation is disabled). */
    fun instructions(): List<String> {
        if (!settings.bool(Settings.LEARNING_ENABLED)) return emptyList()
        return all().mapNotNull { p ->
            dimensions.firstOrNull { it.key == p.key }?.instructions?.get(p.value) ?: if (p.key.startsWith("custom:")) p.value else null
        }
    }

    /** Detail preference adjusts the token budget so it changes generation, not only wording. */
    fun tokenBudgetFactor(): Double = if (!settings.bool(Settings.LEARNING_ENABLED)) 1.0 else when (get("detail")?.value) {
        "concise" -> 0.6
        "detailed" -> 1.5
        else -> 1.0
    }

    // ---------------------------------------------------------- offering questions / alternatives

    fun recordReply() {
        bump("replies_since_question"); bump("replies_since_alternative")
    }

    /** Returns a question to ask now, respecting the enable switch and frequency setting. Never repeats answered dimensions. */
    fun nextQuestion(): PreferenceQuestion? {
        if (!settings.bool(Settings.LEARNING_ENABLED) || !settings.bool(Settings.LEARNING_QUESTIONS)) return null
        if (counter("replies_since_question") < settings.int(Settings.LEARNING_Q_EVERY)) return null
        val answered = all().map { it.key }.toSet()
        val asked = db.query("SELECT ref FROM feedback_log WHERE kind = 'question'") { it.str(0) }.toSet()
        return questions.firstOrNull { it.dimension !in answered && it.id !in asked }
    }

    fun answerQuestion(questionId: String, optionKey: String) {
        val q = questions.first { it.id == questionId }
        require(q.options.any { it.first == optionKey }) { "invalid option" }
        log("question", questionId, optionKey)
        set(q.dimension, optionKey, PreferenceSource.QUESTION, q.text)
        resetCounter("replies_since_question")
    }

    fun dismissQuestion(questionId: String) { log("question", questionId, "dismissed"); resetCounter("replies_since_question") }

    fun nextAlternative(): AlternativeOffer? {
        if (!settings.bool(Settings.LEARNING_ENABLED) || !settings.bool(Settings.LEARNING_ALTERNATIVES)) return null
        if (counter("replies_since_alternative") < settings.int(Settings.LEARNING_ALT_EVERY)) return null
        val known = all().map { it.key }.toSet()
        val dim = listOf("detail", "tone", "recommendations").firstOrNull { it !in known } ?: return null
        val d = dimensions.first { it.key == dim }
        val (a, b) = d.instructions.keys.toList().let { it[0] to it[1] }
        return AlternativeOffer(dim, a, b, d.instructions.getValue(a), d.instructions.getValue(b))
    }

    /**
     * An alternative was generated and offered: the cooldown starts now, chosen or not. (Before 1.6.2 it restarted only
     * on a choice, so an owner who ignored the offer got a second generation after every reply from then on.)
     */
    fun markAlternativeOffered() = resetCounter("replies_since_alternative")

    fun chooseAlternative(offer: AlternativeOffer, chosen: String, persist: Boolean) {
        require(chosen == offer.optionA || chosen == offer.optionB)
        log("alternative", offer.dimension, chosen)
        resetCounter("replies_since_alternative")
        if (persist) set(offer.dimension, chosen, PreferenceSource.ALTERNATIVE_CHOICE, "Chosen between two replies")
    }

    fun feedbackHistory(limit: Int = 100): List<Triple<String, String, String>> =
        db.query("SELECT kind, ref, choice FROM feedback_log ORDER BY created_at DESC LIMIT $limit") { Triple(it.str(0), it.str(1), it.str(2)) }

    private fun log(kind: String, ref: String, choice: String) =
        db.execute("INSERT INTO feedback_log(id, kind, ref, choice, created_at) VALUES(?,?,?,?,?)", listOf(newId(), kind, ref, choice, store.clock.now()))

    private fun counter(k: String) = store.meta("pref:$k")?.toIntOrNull() ?: 0
    private fun bump(k: String) = store.setMeta("pref:$k", (counter(k) + 1).toString())
    private fun resetCounter(k: String) = store.setMeta("pref:$k", "0")

    data class Row(val key: String, val value: String, val source: String, val note: String, val deleted: Boolean, val hlc: String, val origin: String)
    fun row(key: String): Row? = db.query("SELECT key, value, source, note, deleted, hlc, origin FROM preferences WHERE key = ?", listOf(key)) {
        Row(it.str(0), it.str(1), it.str(2), it.str(3), it.bool(4), it.str(5), it.str(6))
    }.firstOrNull()

    fun applyRemote(r: Row): Boolean {
        val local = row(r.key)
        if (local != null && local.hlc >= r.hlc) return false
        store.hlc.observe(r.hlc)
        db.transaction {
            db.execute("""INSERT INTO preferences(key, value, source, note, created_at, updated_at, deleted, hlc, origin) VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value, source=excluded.source, note=excluded.note, updated_at=excluded.updated_at,
                deleted=excluded.deleted, hlc=excluded.hlc, origin=excluded.origin""",
                listOf(r.key, r.value, r.source, r.note, store.clock.now(), store.clock.now(), r.deleted, r.hlc, r.origin))
            store.logRemote(TABLE, r.key, r.hlc)
        }
        store.notifyChanged(TABLE)
        return true
    }

    companion object { const val TABLE = "preferences" }
}
