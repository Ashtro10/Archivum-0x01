package ai.clu.core.butler

import ai.clu.core.db.CluStore
import ai.clu.core.db.SqlRow
import ai.clu.core.db.bool
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.persona.ClockTimeParser
import ai.clu.core.util.newId
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * One butler item: a to-do, a shopping-list entry or a reminder. A reminder is an item with [dueAt]; a recurring one
 * has [recur] = "daily" or "weekly" and moves to its next time when it fires instead of being closed.
 */
data class ButlerItem(
    val id: String,
    val title: String,
    val list: String,
    val dueAt: Long?,
    val recur: String,
    val done: Boolean,
    val notifiedAt: Long?,
    val createdAt: Long,
    val doneAt: Long?,
    /** Sync version (hybrid logical clock), the device that made it, and deletion (kept as a tombstone so it syncs). */
    val hlc: String = "",
    val origin: String = "",
    val deleted: Boolean = false,
    val updatedAt: Long = createdAt,
)

/** When something is due: the first time and the repeat rule ("" = once). */
data class When(val at: Long, val recur: String = "")

/**
 * The owner's tasks, lists and reminders: local, offline, in CLU's own database (table butler_items). Nothing here is
 * inferred: every item exists because the owner asked for it. Reminders are delivered by the platform (Android:
 * AlarmManager → notification); this class only decides what is due and what comes next.
 *
 * Synchronised with the paired device (setting sync.tasks): every change is stamped with the store's hybrid logical
 * clock and logged, removals are tombstones, and an incoming version is applied only if it is newer, so both devices
 * converge on the same lists without duplicates however often they sync.
 */
class Butler(private val store: CluStore, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val db = store.db

    fun add(title: String, list: String = TASKS, dueAt: Long? = null, recur: String = ""): ButlerItem {
        val t = title.trim().trimEnd('.', '!').ifEmpty { throw IllegalArgumentException("An item needs a title.") }
        require(recur in RECURS) { "Unknown repeat rule: $recur" }
        val now = store.clock.now()
        return save(ButlerItem(newId(), t.take(300), normaliseList(list), dueAt, recur, false, null, now, null))
    }

    /** Writes a local change: stamped, logged for sync, observers told. */
    private fun save(item: ButlerItem): ButlerItem {
        var row = item.copy(updatedAt = store.clock.now(), origin = store.deviceId)
        db.transaction { row = row.copy(hlc = store.stamp(TABLE, row.id)); write(row) }
        store.notifyChanged(TABLE)
        return row
    }

    private fun write(r: ButlerItem) = db.execute("""INSERT INTO butler_items(id, title, list_name, due_at, recur, done, notified_at, created_at, updated_at, done_at, hlc, origin, deleted)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title, list_name=excluded.list_name, due_at=excluded.due_at, recur=excluded.recur,
        done=excluded.done, notified_at=excluded.notified_at, created_at=excluded.created_at, updated_at=excluded.updated_at, done_at=excluded.done_at,
        hlc=excluded.hlc, origin=excluded.origin, deleted=excluded.deleted""",
        listOf(r.id, r.title, r.list, r.dueAt, r.recur, if (r.done) 1 else 0, r.notifiedAt, r.createdAt, r.updatedAt, r.doneAt, r.hlc, r.origin, if (r.deleted) 1 else 0))

    /** Items created before task sync existed (no version yet) get one, so they can be sent. Returns how many. */
    fun stampUnversioned(): Int {
        val ids = db.query("SELECT id FROM butler_items WHERE hlc = ''", emptyList()) { it.str(0) }
        ids.forEach { id -> row(id)?.let { r -> db.transaction { write(r.copy(hlc = store.stamp(TABLE, id), origin = store.deviceId)) } } }
        return ids.size
    }

    /** The stored row including tombstones (for synchronisation). */
    fun row(id: String): ButlerItem? = db.query("SELECT $COLS FROM butler_items WHERE id = ?", listOf(id), ::read).firstOrNull()

    /** A version from the paired device: applied only when newer than ours (idempotent; older or equal versions are ignored). */
    fun applyRemote(item: ButlerItem): Boolean {
        val local = row(item.id)
        if (local != null && local.hlc >= item.hlc) return false
        require(item.recur in RECURS) { "invalid repeat rule" }
        store.hlc.observe(item.hlc)
        db.transaction { write(if (item.deleted) item.copy(title = "") else item.copy(list = normaliseList(item.list))); store.logRemote(TABLE, item.id, item.hlc) }
        store.notifyChanged(TABLE)
        return true
    }

    fun get(id: String): ButlerItem? = row(id)?.takeUnless { it.deleted }

    /** Open items, reminders by due time first, then the rest in the order they were added. */
    fun open(list: String? = null): List<ButlerItem> =
        db.query("SELECT $COLS FROM butler_items WHERE done = 0 AND deleted = 0 ${if (list != null) "AND list_name = ?" else ""} ORDER BY due_at IS NULL, due_at, created_at",
            listOfNotNull(list?.let(::normaliseList)), ::read)

    fun lists(): List<String> = db.query("SELECT DISTINCT list_name FROM butler_items WHERE done = 0 AND deleted = 0 ORDER BY list_name", emptyList()) { it.str(0) }

    /** Closes the open item that best matches [query] (exact title, then a title containing all its words). */
    fun complete(query: String, list: String? = null): ButlerItem? {
        val item = find(query, list) ?: return null
        return save(item.copy(done = true, doneAt = store.clock.now()))
    }

    /** Removes an item everywhere (a tombstone, so the paired device removes it too). */
    fun remove(id: String) { row(id)?.takeUnless { it.deleted }?.let { save(it.copy(deleted = true, title = "")) } }

    /** Items closed in [from, to) (the evening summary). */
    fun completedBetween(from: Long, to: Long): List<ButlerItem> =
        db.query("SELECT $COLS FROM butler_items WHERE done = 1 AND deleted = 0 AND done_at >= ? AND done_at < ? ORDER BY done_at", listOf(from, to), ::read)

    /** Every item ever added, done or not (routine recognition looks for repeats); tombstones excluded. */
    fun history(since: Long): List<ButlerItem> = db.query("SELECT $COLS FROM butler_items WHERE deleted = 0 AND created_at >= ? ORDER BY created_at", listOf(since), ::read)

    fun find(query: String, list: String? = null): ButlerItem? {
        val q = query.trim().lowercase().removePrefix("the ").trim()
        if (q.isEmpty()) return null
        val open = open(list)
        open.firstOrNull { it.title.lowercase() == q }?.let { return it }
        val words = q.split(Regex("\\W+")).filter { it.length > 1 }
        return open.filter { i -> words.isNotEmpty() && words.all { it in i.title.lowercase() } }.minByOrNull { it.title.length }
    }

    /** Reminders whose time has come and that haven't been delivered for this time. */
    fun due(now: Long = store.clock.now()): List<ButlerItem> =
        db.query("SELECT $COLS FROM butler_items WHERE done = 0 AND deleted = 0 AND due_at IS NOT NULL AND due_at <= ? AND (notified_at IS NULL OR notified_at < due_at) ORDER BY due_at",
            listOf(now), ::read)

    /** The next reminder time after [now] (for the platform alarm), or null when none is pending. */
    fun nextDue(now: Long = store.clock.now()): Long? =
        db.query("SELECT MIN(due_at) FROM butler_items WHERE done = 0 AND deleted = 0 AND due_at IS NOT NULL AND (notified_at IS NULL OR notified_at < due_at) AND due_at > ?", listOf(now)) { it.long(0) }.firstOrNull()

    /** Records that [item] was delivered: a repeating reminder moves to its next time, a one-off one stays open (it is a task until closed). */
    fun delivered(item: ButlerItem, now: Long = store.clock.now()) {
        val next = item.dueAt?.let { nextOccurrence(it, item.recur, now) }
        // Synced too: once one device has rung it, the other one won't ring it again later.
        save(if (next != null) item.copy(dueAt = next) else item.copy(notifiedAt = now))
    }

    private fun nextOccurrence(dueAt: Long, recur: String, now: Long): Long? {
        val step = when (recur) { "daily" -> 1L; "weekly" -> 7L; else -> return null }
        var t = Instant.ofEpochMilli(dueAt).atZone(zone())
        while (t.toInstant().toEpochMilli() <= now) t = t.plusDays(step)
        return t.toInstant().toEpochMilli()
    }

    /** A plain spoken summary of what is due today, overdue and open: the morning briefing. */
    fun briefing(now: Long = store.clock.now()): String {
        val z = zone(); val today = Instant.ofEpochMilli(now).atZone(z).toLocalDate()
        val endOfDay = today.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        val open = open()
        val overdue = open.filter { it.dueAt != null && it.dueAt < now && it.recur.isEmpty() }
        val todayItems = open.filter { it.dueAt != null && it.dueAt in now until endOfDay }
        val undated = open.filter { it.dueAt == null }
        if (open.isEmpty()) return "Nothing is on your lists and no reminders are pending."
        return buildString {
            if (todayItems.isNotEmpty()) append("Today: ").append(todayItems.joinToString("; ") { "${it.title} at ${clock(it.dueAt!!)}" }).append(". ")
            else append("No reminders for the rest of today. ")
            if (overdue.isNotEmpty()) append("Overdue: ").append(overdue.joinToString("; ") { it.title }).append(". ")
            undated.groupBy { it.list }.forEach { (l, items) -> append("${l.replaceFirstChar { it.uppercase() }} (${items.size}): ").append(items.take(5).joinToString(", ") { it.title }).append(if (items.size > 5) ", …" else "").append(". ") }
        }.trim()
    }

    fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
    fun describe(ms: Long, now: Long = store.clock.now()): String {
        val z = zone(); val d = Instant.ofEpochMilli(ms).atZone(z).toLocalDate(); val today = Instant.ofEpochMilli(now).atZone(z).toLocalDate()
        val day = when (d) { today -> "today"; today.plusDays(1) -> "tomorrow"; else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)) }
        return "$day at ${clock(ms)}"
    }

    private fun read(r: SqlRow) = ButlerItem(r.str(0), r.str(1), r.str(2), r.long(3), r.str(4), r.bool(5), r.long(6), r.lng(7), r.long(8), r.str(9), r.str(10), r.bool(11), r.lng(12))

    companion object {
        const val TABLE = "butler_items"
        const val TASKS = "tasks"
        const val SHOPPING = "shopping"
        val RECURS = setOf("", "daily", "weekly")
        private const val COLS = "id, title, list_name, due_at, recur, done, notified_at, created_at, done_at, hlc, origin, deleted, updated_at"

        fun normaliseList(name: String): String {
            val n = name.trim().lowercase().removePrefix("my ").removePrefix("the ").removeSuffix(" list").trim()
            return when (n) { "", "task", "tasks", "to-do", "todo", "to do", "to-dos", "todos" -> TASKS; "shopping", "grocery", "groceries" -> SHOPPING; else -> n.take(40) }
        }

        private val WHEN_WORDS = Regex("^(?:in|at|on|by|for|tomorrow|today|tonight|every|each|day|days|daily|morning|evening|night|afternoon|noon|midnight|the|next|this|half|an|a|" +
            "hour|hours|hr|hrs|minute|minutes|min|mins|second|seconds|sec|secs|am|pm|a\\.m\\.|p\\.m\\.|o'clock|" +
            "monday|tuesday|wednesday|thursday|friday|saturday|sunday|mondays|tuesdays|wednesdays|thursdays|fridays|saturdays|sundays|\\d{1,2}(?:[:.]\\d{2})?(?:am|pm)?|\\d+(?:h|m|s)?)$")
        private val WHEN_START = Regex("\\s(?=(?:in|at|on|tomorrow|today|tonight|every|each|daily|this|next)\\b)")

        /**
         * Splits "call mom tomorrow at 9" / "in 10 minutes to stretch" into (what, when). The when-part is the earliest
         * tail made only of time words that parses ("pick up the kids at school at 4 pm" keeps "at school" in the task).
         */
        fun splitReminder(rest: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Pair<String, String>? {
            val r = rest.trim().trimEnd('.', '!', '?')
            fun timeWords(w: String) = w.lowercase().split(Regex("\\s+")).all { WHEN_WORDS.matches(it.trim(',')) }
            // When first: "in 10 minutes to stretch", "tomorrow at 9 to call mom".
            Regex("^(.+?)\\s+(?:to|that|about)\\s+(.+)$", RegexOption.IGNORE_CASE).find(r)?.let { m ->
                val w = m.groupValues[1]; if (timeWords(w) && parseWhen(w, now, zone) != null) return m.groupValues[2].trim() to w.trim() }
            for (m in WHEN_START.findAll(r)) {
                val what = r.substring(0, m.range.first).trim(); val w = r.substring(m.range.last + 1).trim()
                if (what.isNotEmpty() && timeWords(w) && parseWhen(w, now, zone) != null) return what.removePrefix("to ").removePrefix("about ").trim() to w
            }
            return null
        }

        /**
         * Parses when a reminder is due: "in 20 minutes", "at 6 pm", "tomorrow at 9", "at 7 tomorrow", "every day at 8 am",
         * "daily at 8", "every Monday at 10". A time already past today means tomorrow (or next week for weekdays).
         */
        fun parseWhen(phrase: String, now: Long, zone: ZoneId): When? {
            val s = phrase.lowercase().trim().replace(Regex("\\s+"), " ")
            Regex("^in (.+)$").find(s)?.let { m -> ClockTimeParser.parseDurationSec(m.groupValues[1])?.let { return When(now + it * 1000L) } }
            val base = Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime()
            val clock = Regex("\\b(?:at )?(\\d{1,2}(?:[:.]\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)?|noon|midnight)\\b").findAll(s)
                .map { it.groupValues[1] }.firstNotNullOfOrNull { c -> ClockTimeParser.parse(if (c.trim().all(Char::isDigit) || c.contains(':') || c.contains('.') || c.contains('m') || c == "noon" || c == "midnight") c else "") }
            val time = clock?.let { LocalTime.of(it.first, it.second) } ?: if (Regex("\\bmorning\\b").containsMatchIn(s)) LocalTime.of(9, 0) else if (Regex("\\b(?:evening|tonight)\\b").containsMatchIn(s)) LocalTime.of(19, 0) else null
            val weekday = DayOfWeek.values().firstOrNull { d -> Regex("\\b${d.name.lowercase()}s?\\b").containsMatchIn(s) }
            val recur = when { Regex("\\b(?:every ?day|daily|each day|every morning|every evening|every night)\\b").containsMatchIn(s) -> "daily"; weekday != null && Regex("\\b(?:every|each)\\b").containsMatchIn(s) -> "weekly"; else -> "" }
            time ?: return null
            var date: LocalDate = base.toLocalDate()
            if (Regex("\\btomorrow\\b").containsMatchIn(s)) date = date.plusDays(1)
            else if (weekday != null) date = date.with(TemporalAdjusters.nextOrSame(weekday))
            var at = LocalDateTime.of(date, time)
            if (!at.isAfter(base)) at = if (weekday != null) at.plusWeeks(1) else at.plusDays(1)
            return When(at.atZone(zone).toInstant().toEpochMilli(), recur)
        }
    }
}
