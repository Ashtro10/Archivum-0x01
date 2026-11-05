package ai.clu.core.calendar

import ai.clu.core.butler.Butler
import ai.clu.core.security.Sensitivity
import ai.clu.core.tools.Tool
import ai.clu.core.tools.ToolContext
import ai.clu.core.tools.ToolResult
import ai.clu.core.tools.int
import ai.clu.core.tools.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** One calendar event as the platform reports it (Android: CalendarContract.Instances, so repeats are expanded). */
data class CalEvent(val id: Long, val title: String, val start: Long, val end: Long, val allDay: Boolean = false, val location: String = "", val calendar: String = "")

/**
 * The owner's calendar on this device. Android supplies it over the calendar provider (READ_CALENDAR / WRITE_CALENDAR,
 * asked for the first time they are needed). Null results mean "no permission": tools say so, never invent events.
 */
interface CalendarSource {
    fun canRead(): Boolean
    fun canWrite(): Boolean
    /** Asks the owner for calendar access (read and write) through the visible screen. */
    fun requestAccess()
    fun events(from: Long, to: Long): List<CalEvent>
    /** Inserts an event in the owner's primary writable calendar; returns its id, or null when it couldn't. */
    fun insert(title: String, start: Long, end: Long, location: String = ""): Long?
    fun update(id: Long, title: String? = null, start: Long? = null, end: Long? = null): Boolean
    fun delete(id: Long): Boolean
}

data class Range(val from: Long, val to: Long, val label: String)

/** Pure calendar reasoning: date ranges, conflicts, free time and wording. Tested on its own. */
object CalendarLogic {
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    /** "today", "tomorrow", "this week", "next week", "this weekend", "next 3 days", "on Friday", "the 12th"… default today. */
    fun range(phrase: String?, now: Long, zone: ZoneId): Range {
        val s = (phrase ?: "").lowercase().trim().removePrefix("for ").removePrefix("on ").trim()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun days(a: LocalDate, n: Long, label: String) = Range(a.atStartOfDay(zone).toInstant().toEpochMilli(), a.plusDays(n).atStartOfDay(zone).toInstant().toEpochMilli(), label)
        Regex("(?:next|coming)\\s+(\\d{1,2}|two|three|four|five|six|seven)\\s+days").find(s)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: listOf("two", "three", "four", "five", "six", "seven").indexOf(m.groupValues[1]) + 2L
            return days(today, n, "the next $n days") }
        return when {
            s.isEmpty() || s == "today" || s.contains("today") -> days(today, 1, "today")
            s.contains("tomorrow") -> days(today.plusDays(1), 1, "tomorrow")
            s.contains("weekend") -> { val sat = if (today.dayOfWeek == DayOfWeek.SUNDAY) today.minusDays(1) else today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
                days(if (s.contains("next")) sat.plusWeeks(1) else sat, 2, "the weekend") }
            s.contains("next week") -> days(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)), 7, "next week")
            s.contains("week") -> days(today, (8 - today.dayOfWeek.value).toLong(), "the rest of this week")
            s.contains("month") -> days(today, today.lengthOfMonth() - today.dayOfMonth + 1L, "the rest of this month")
            else -> DayOfWeek.values().firstOrNull { s.contains(it.name.lowercase()) }?.let { d ->
                val day = today.with(TemporalAdjusters.nextOrSame(d)).let { if (s.contains("next") && it == today) it.plusWeeks(1) else it }
                days(day, 1, day.format(DAY)) } ?: days(today, 1, "today")
        }
    }

    /** Pairs of timed events that overlap (all-day events don't conflict with anything). */
    fun conflicts(events: List<CalEvent>): List<Pair<CalEvent, CalEvent>> {
        val timed = events.filter { !it.allDay }.sortedBy { it.start }
        val out = ArrayList<Pair<CalEvent, CalEvent>>()
        for (i in timed.indices) for (j in i + 1 until timed.size) { if (timed[j].start >= timed[i].end) break; out += timed[i] to timed[j] }
        return out
    }

    /** Free gaps of at least [minMinutes] between [dayStartHour] and [dayEndHour] on each day of [range]. */
    fun freeSlots(events: List<CalEvent>, range: Range, zone: ZoneId, now: Long, minMinutes: Int = 30, dayStartHour: Int = 8, dayEndHour: Int = 21): List<Pair<Long, Long>> {
        val timed = events.filter { !it.allDay }.sortedBy { it.start }
        val out = ArrayList<Pair<Long, Long>>()
        var day = Instant.ofEpochMilli(range.from).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(range.to - 1).atZone(zone).toLocalDate()
        while (!day.isAfter(last)) {
            var cursor = maxOf(day.atTime(dayStartHour, 0).atZone(zone).toInstant().toEpochMilli(), now)
            val end = day.atTime(dayEndHour, 0).atZone(zone).toInstant().toEpochMilli()
            for (e in timed) { if (e.end <= cursor || e.start >= end) continue
                if (e.start - cursor >= minMinutes * 60_000L) out += cursor to e.start
                cursor = maxOf(cursor, e.end) }
            if (end - cursor >= minMinutes * 60_000L) out += cursor to end
            day = day.plusDays(1)
        }
        return out
    }

    /** Events overlapping [at, at + minutes). */
    fun busyAt(events: List<CalEvent>, at: Long, minutes: Int = 30): List<CalEvent> = events.filter { !it.allDay && it.start < at + minutes * 60_000L && it.end > at }

    fun clock(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(CLOCK)
    fun day(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(DAY)

    fun line(e: CalEvent, zone: ZoneId, withDay: Boolean): String {
        val d = if (withDay) day(e.start, zone) + " " else ""
        val time = if (e.allDay) "all day" else "${clock(e.start, zone)}–${clock(e.end, zone)}"
        return "$d$time ${e.title.ifBlank { "(no title)" }}" + (if (e.location.isNotBlank()) " at ${e.location}" else "")
    }

    /** The spoken schedule for [range]: events, then conflicts. */
    fun describe(events: List<CalEvent>, range: Range, zone: ZoneId): String {
        if (events.isEmpty()) return "Nothing on your calendar ${range.label}."
        val multiDay = range.to - range.from > 86_400_000L
        val sb = StringBuilder("${range.label.replaceFirstChar { it.uppercase() }}: ")
        sb.append(events.sortedBy { it.start }.joinToString("; ") { line(it, zone, multiDay) }).append('.')
        val c = conflicts(events)
        if (c.isNotEmpty()) sb.append(" Conflict").append(if (c.size > 1) "s" else "").append(": ")
            .append(c.joinToString("; ") { (a, b) -> "${a.title} overlaps ${b.title}" }).append('.')
        return sb.toString()
    }

    /** Events whose title best matches [query] (all its words), soonest first. */
    fun match(events: List<CalEvent>, query: String): List<CalEvent> {
        val words = query.lowercase().replace(Regex("\\b(my|the|a|an|meeting|appointment|event)\\b"), " ").split(Regex("\\W+")).filter { it.length > 1 }
        if (words.isEmpty()) return emptyList()
        return events.filter { e -> words.all { it in e.title.lowercase() } }.sortedBy { it.start }
    }
}

private fun noAccess(src: CalendarSource): ToolResult { src.requestAccess()
    return ToolResult.fail("I need permission to use your calendar for that. I've asked Android; allow it and ask me again.") }

class CalendarReadTool(private val src: () -> CalendarSource?, private val zone: () -> ZoneId) : Tool {
    override val name = "calendar.read"
    override val description = "Read calendar events (today, tomorrow, this week…) with conflicts."
    override val argsExample = """{"range":"tomorrow"}"""
    override val sensitivity = Sensitivity.PERSONAL
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val s = src() ?: return ToolResult.fail("This device has no calendar I can read.")
        if (!s.canRead()) return noAccess(s)
        val r = CalendarLogic.range(args.str("range"), ctx.now, zone())
        val ev = s.events(r.from, r.to)
        val data = buildJsonArray { ev.forEach { addJsonObject { put("title", it.title); put("start", it.start); put("end", it.end); put("allDay", it.allDay); put("location", it.location) } } }
        return ToolResult.ok(CalendarLogic.describe(ev, r, zone()), data = data)
    }
}

class CalendarFreeTool(private val src: () -> CalendarSource?, private val zone: () -> ZoneId) : Tool {
    override val name = "calendar.free"
    override val description = "Free time on the calendar, or whether a given time is free."
    override val argsExample = """{"range":"tomorrow"}"""
    override val sensitivity = Sensitivity.PERSONAL
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val s = src() ?: return ToolResult.fail("This device has no calendar I can read.")
        if (!s.canRead()) return noAccess(s)
        val z = zone()
        args.str("at")?.let { at ->
            val w = Butler.parseWhen(at, ctx.now, z) ?: return ToolResult.fail("I couldn't understand the time \"$at\".")
            val minutes = args.int("minutes") ?: 30
            val busy = CalendarLogic.busyAt(s.events(w.at - 86_400_000L, w.at + 86_400_000L), w.at, minutes)
            val when_ = "${CalendarLogic.day(w.at, z)} at ${CalendarLogic.clock(w.at, z)}"
            return ToolResult.ok(if (busy.isEmpty()) "You're free $when_." else "You're busy $when_: " + busy.joinToString("; ") { CalendarLogic.line(it, z, false) } + ".")
        }
        val r = CalendarLogic.range(args.str("range"), ctx.now, z)
        val slots = CalendarLogic.freeSlots(s.events(r.from, r.to), r, z, ctx.now, args.int("minutes") ?: 30)
        if (slots.isEmpty()) return ToolResult.ok("No free time of ${args.int("minutes") ?: 30} minutes or more ${r.label} between 8 am and 9 pm.")
        val multi = r.to - r.from > 86_400_000L
        return ToolResult.ok("Free ${r.label}: " + slots.take(8).joinToString("; ") { (a, b) -> (if (multi) CalendarLogic.day(a, z) + " " else "") + "${CalendarLogic.clock(a, z)}–${CalendarLogic.clock(b, z)}" } + ".")
    }
}

class CalendarCreateTool(private val src: () -> CalendarSource?, private val zone: () -> ZoneId) : Tool {
    override val name = "calendar.create"
    override val description = "Add an event to the calendar."
    override val argsExample = """{"title":"Dentist","when":"Friday at 4 pm","minutes":"60"}"""
    override val sensitivity = Sensitivity.PERSONAL
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val s = src() ?: return ToolResult.fail("This device has no calendar I can write to.")
        if (!s.canWrite()) return noAccess(s)
        val title = args.str("title") ?: return ToolResult.fail("What is the event?")
        val whenText = args.str("when") ?: return ToolResult.fail("When is it?")
        val w = Butler.parseWhen(whenText, ctx.now, zone()) ?: return ToolResult.fail("I couldn't understand when: \"$whenText\".")
        val end = w.at + (args.int("minutes") ?: 60) * 60_000L
        val clash = CalendarLogic.busyAt(s.events(w.at - 86_400_000L, end + 86_400_000L), w.at, ((end - w.at) / 60_000L).toInt())
        val id = s.insert(title, w.at, end, args.str("location") ?: "") ?: return ToolResult.fail("Android's calendar refused the new event.")
        val ok = s.events(w.at - 60_000L, end + 60_000L).any { it.id == id }
        val z = zone()
        val text = "Added \"$title\" on ${CalendarLogic.day(w.at, z)} at ${CalendarLogic.clock(w.at, z)}." +
            (if (clash.isNotEmpty()) " Note: it overlaps ${clash.joinToString(", ") { it.title }}." else "")
        return ToolResult(true, ok, text, spoken = text)
    }
}

class CalendarMoveTool(private val src: () -> CalendarSource?, private val zone: () -> ZoneId) : Tool {
    override val name = "calendar.move"
    override val description = "Move an event to a new time (keeps its length)."
    override val argsExample = """{"title":"dentist","when":"Monday at 10"}"""
    override val sensitivity = Sensitivity.PERSONAL
    override val deviceAction = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val s = src() ?: return ToolResult.fail("This device has no calendar I can change.")
        if (!s.canWrite()) return noAccess(s)
        val q = args.str("title") ?: return ToolResult.fail("Which event?")
        val w = Butler.parseWhen(args.str("when") ?: return ToolResult.fail("To when?"), ctx.now, zone()) ?: return ToolResult.fail("I couldn't understand the new time.")
        val hits = CalendarLogic.match(s.events(ctx.now - 86_400_000L, ctx.now + 60 * 86_400_000L), q)
        val e = hits.firstOrNull() ?: return ToolResult.fail("I couldn't find \"$q\" on your calendar in the next two months.")
        if (hits.map { it.title }.distinct().size > 1) return ToolResult.fail("Which one: ${hits.take(3).joinToString(", ") { CalendarLogic.line(it, zone(), true) }}?")
        val ok = s.update(e.id, start = w.at, end = w.at + (e.end - e.start))
        return if (ok) ToolResult.ok("Moved \"${e.title}\" to ${CalendarLogic.day(w.at, zone())} at ${CalendarLogic.clock(w.at, zone())}.") else ToolResult.fail("Android's calendar refused the change.")
    }
}

class CalendarDeleteTool(private val src: () -> CalendarSource?, private val zone: () -> ZoneId) : Tool {
    override val name = "calendar.delete"
    override val description = "Delete an event from the calendar."
    override val argsExample = """{"title":"dentist"}"""
    override val sensitivity = Sensitivity.PERSONAL
    override val deviceAction = true
    /** Deleting is irreversible: asks first unless the owner turned that off. */
    override val confirmByDefault = true
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val s = src() ?: return ToolResult.fail("This device has no calendar I can change.")
        if (!s.canWrite()) return noAccess(s)
        val q = args.str("title") ?: return ToolResult.fail("Which event?")
        val hits = CalendarLogic.match(s.events(ctx.now - 3_600_000L, ctx.now + 60 * 86_400_000L), q)
        val e = hits.firstOrNull() ?: return ToolResult.fail("I couldn't find \"$q\" on your calendar in the next two months.")
        if (hits.map { it.title }.distinct().size > 1 || hits.size > 1) return ToolResult.fail("Which one: ${hits.take(3).joinToString(", ") { CalendarLogic.line(it, zone(), true) }}?")
        val ok = s.delete(e.id)
        return if (ok) ToolResult.ok("Deleted \"${e.title}\" (${CalendarLogic.line(e, zone(), true)}).") else ToolResult.fail("Android's calendar refused to delete it.")
    }
}
