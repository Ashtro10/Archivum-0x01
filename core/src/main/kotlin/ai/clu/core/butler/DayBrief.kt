package ai.clu.core.butler

import ai.clu.core.calendar.CalendarLogic
import ai.clu.core.calendar.CalendarSource
import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.diary.DiaryRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The owner's day in a few sentences, from what CLU actually has: their lists and reminders, their calendar (only with
 * calendar access), how many diary entries they wrote (never the text) and, if they enable it, which apps notified them.
 * Morning: what's ahead. Evening: what got done, what's open, tomorrow.
 */
class DayBrief(
    private val butler: Butler,
    private val settings: SettingsStore,
    private val calendar: () -> CalendarSource?,
    private val diary: DiaryRepository?,
    /** App names that notified the owner today (Android, with notification access), or null. */
    private val notifiedApps: () -> List<String>? = { null },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private fun dayBounds(now: Long, plusDays: Long = 0): Pair<Long, Long> {
        val d = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().plusDays(plusDays)
        return d.atStartOfDay(zone()).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
    }

    private fun events(from: Long, to: Long) = calendar()?.takeIf { it.canRead() }?.let { runCatching { it.events(from, to) }.getOrNull() }

    /** Morning: today's calendar (when readable), reminders, overdue items and lists. */
    fun morning(now: Long): String {
        val (a, b) = dayBounds(now)
        val ev = events(maxOf(a, now), b)
        val cal = ev?.let { if (it.isEmpty()) "No more events on your calendar today." else CalendarLogic.describe(it, ai.clu.core.calendar.Range(now, b, "today"), zone()) }
        return listOfNotNull(cal, butler.briefing(now)).joinToString(" ")
    }

    /** True if there is anything worth a morning greeting addition. */
    fun morningWorthSaying(now: Long): Boolean {
        val (a, b) = dayBounds(now)
        return butler.open().any { it.dueAt != null && it.dueAt < b } || (events(maxOf(a, now), b)?.isNotEmpty() ?: false)
    }

    /** Evening: finished today, still open, tomorrow's calendar, diary count, notifying apps: each section as configured. */
    fun evening(now: Long): String {
        val (a, b) = dayBounds(now)
        val parts = ArrayList<String>()
        if (settings.bool(Settings.EVENING_TASKS)) {
            val done = butler.completedBetween(a, b)
            val open = butler.open()
            parts += if (done.isEmpty()) "You didn't tick anything off today." else "Done today: ${done.take(6).joinToString(", ") { it.title }}${if (done.size > 6) " and ${done.size - 6} more" else ""}."
            val overdue = open.filter { it.dueAt != null && it.dueAt < now && it.recur.isEmpty() }
            val tomorrow = open.filter { it.dueAt != null && it.dueAt in b until b + 86_400_000L }
            if (overdue.isNotEmpty()) parts += "Still open from today: ${overdue.joinToString(", ") { it.title }}."
            if (tomorrow.isNotEmpty()) parts += "Reminders tomorrow: ${tomorrow.joinToString("; ") { "${it.title} at ${butler.clock(it.dueAt!!)}" }}."
            val undated = open.count { it.dueAt == null }
            if (undated > 0) parts += "$undated item${if (undated == 1) "" else "s"} on your lists."
        }
        if (settings.bool(Settings.EVENING_CALENDAR)) {
            val (ta, tb) = dayBounds(now, 1)
            events(ta, tb)?.let { parts += if (it.isEmpty()) "Nothing on your calendar tomorrow." else CalendarLogic.describe(it, ai.clu.core.calendar.Range(ta, tb, "tomorrow"), zone()) }
        }
        if (settings.bool(Settings.EVENING_DIARY) && diary != null) {
            val n = diary.list(day = diary.today(now)).size
            if (n > 0) parts += "You wrote $n diary entr${if (n == 1) "y" else "ies"} today."
        }
        if (settings.bool(Settings.EVENING_NOTIFICATIONS)) notifiedApps()?.takeIf { it.isNotEmpty() }?.let { parts += "Notifications today from ${it.distinct().take(6).joinToString(", ")}." }
        return parts.joinToString(" ").ifBlank { "A quiet day: nothing on your lists or calendar." }
    }

    /** Whether the evening summary is due at [now]: enabled, past its time, on a configured day. */
    fun eveningDue(now: Long): Boolean {
        if (!settings.bool(Settings.EVENING_ENABLED)) return false
        val t = Instant.ofEpochMilli(now).atZone(zone())
        val day = t.dayOfWeek
        val weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
        when (settings.string(Settings.EVENING_DAYS)) { "weekdays" -> if (weekend) return false; "weekends" -> if (!weekend) return false }
        return t.toLocalTime() >= LocalTime.parse(settings.string(Settings.EVENING_TIME))
    }

    /** The next moment the evening summary becomes due (for the platform alarm), or null when it is off. */
    fun nextEvening(now: Long): Long? {
        if (!settings.bool(Settings.EVENING_ENABLED)) return null
        val z = zone(); val time = LocalTime.parse(settings.string(Settings.EVENING_TIME))
        var d = Instant.ofEpochMilli(now).atZone(z).toLocalDate()
        repeat(8) { val at = d.atTime(time).atZone(z).toInstant().toEpochMilli()
            if (at > now && eveningDue(at)) return at; d = d.plusDays(1) }
        return null
    }
}
