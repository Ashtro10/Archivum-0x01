package ai.clu.core.butler

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.CluStore
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.memory.MemoryProposal
import ai.clu.core.memory.ProposalKind
import ai.clu.core.memory.ProposalRepository
import ai.clu.core.util.CluJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId

/**
 * Notices routines from what CLU already records locally: items the owner keeps adding (tasks, reminders) and apps they
 * open through CLU at similar times. It never monitors anything else. A pattern is only a suggestion: it goes to the
 * memory suggestions for the owner to approve, edit or reject, and becomes a memory only when approved. A rejected
 * suggestion is never offered again (proposal fingerprints). Runs at most once a day, over the last 30 days.
 */
class RoutineDetector(
    private val store: CluStore,
    private val settings: SettingsStore,
    private val butler: Butler,
    private val proposals: ProposalRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    data class Pattern(val text: String, val rationale: String)

    private fun hour(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone()).hour
    private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()
    private fun partOfDay(h: Int) = when (h) { in 5..11 -> "in the morning"; in 12..16 -> "in the afternoon"; in 17..21 -> "in the evening"; else -> "at night" }
    private fun around(hours: List<Int>): String? {
        val sorted = hours.sorted(); val median = sorted[sorted.size / 2]
        return if (sorted.count { kotlin.math.abs(it - median) <= 1 } * 4 >= sorted.size * 3) partOfDay(median) else null
    }

    /** Patterns in the last 30 days (pure: no proposal is made). */
    fun patterns(now: Long): List<Pattern> {
        val since = now - 30 * 86_400_000L
        val out = ArrayList<Pattern>()
        // Items added again and again on different days, not already a repeating reminder.
        val items = butler.history(since)
        val recurring = items.filter { it.recur.isNotEmpty() }.map { norm(it.title) }.toSet()
        items.groupBy { norm(it.title) }.filterKeys { it.isNotEmpty() && it !in recurring }.forEach { (_, xs) ->
            val days = xs.map { day(it.createdAt) }.distinct().size
            if (days >= 3) {
                val title = xs.last().title
                val times = xs.mapNotNull { it.dueAt }.map(::hour)
                val whenText = times.takeIf { it.size >= 3 }?.let(::around)
                out += Pattern("You regularly ${if (times.isNotEmpty()) "set a reminder to" else "add"} \"$title\"${whenText?.let { " $it" } ?: ""}.",
                    "Seen on $days different days in the last month. If it's a routine, you can say \"remind me to $title every day at …\" to make it repeat.")
            }
        }
        // Apps opened through CLU at a similar time of day.
        val opens = store.db.query("SELECT args, started_at FROM tool_runs WHERE tool = 'app.open' AND success = 1 AND started_at >= ?", listOf(since)) { it.str(0) to it.lng(1) }
            .mapNotNull { (a, t) -> runCatching { CluJson.parseToJsonElement(a).jsonObject["app"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { it to t } }
        opens.groupBy { it.first.lowercase() }.forEach { (_, xs) ->
            val days = xs.map { day(it.second) }.distinct().size
            val whenText = around(xs.map { hour(it.second) })
            if (xs.size >= 5 && days >= 4 && whenText != null) out += Pattern("You often open ${xs.last().first} $whenText.", "Opened through CLU ${xs.size} times on $days days in the last month.")
        }
        return out
    }

    /** Offers new patterns as suggestions (once a day at most). Returns what was proposed. */
    fun run(now: Long = store.clock.now()): List<MemoryProposal> {
        if (!settings.bool(Settings.ROUTINES_ENABLED)) return emptyList()
        val today = day(now).toString()
        if (store.meta("routines_last_run") == today) return emptyList()
        store.setMeta("routines_last_run", today)
        return patterns(now).mapNotNull { proposals.propose(ProposalKind.OBSERVATION, it.text, it.rationale, sourceRef = "routine") }
    }

    private fun norm(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()
}
