package ai.clu.core.history

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.util.Clock
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How precisely a historical date is known. Never more precise than what the owner said. */
enum class DatePrecision { DAY, MONTH, YEAR, RANGE, UNKNOWN }

/**
 * When something happened, as precisely as it is known: an exact day, a month, a year, a range ("between 2018 and
 * 2019"), or unknown, optionally approximate ("around March 2018"). Stored as ISO dates (yyyy-MM-dd) of the first and
 * last possible day, never as an instant, so no time zone can move a date-only memory to another day.
 */
@Serializable
data class HistoricalDate(
    val precision: DatePrecision = DatePrecision.UNKNOWN,
    val start: String? = null,
    val end: String? = null,
    val approximate: Boolean = false,
) {
    val known: Boolean get() = precision != DatePrecision.UNKNOWN && start != null
    fun startDate(): LocalDate? = start?.let(LocalDate::parse)
    fun endDate(): LocalDate? = (end ?: start)?.let(LocalDate::parse)

    /** True when the two dates can refer to the same time (their possible ranges overlap). */
    fun overlaps(from: LocalDate, to: LocalDate): Boolean {
        val s = startDate() ?: return false; val e = endDate() ?: return false
        return !e.isBefore(from) && !s.isAfter(to)
    }

    /** "12 March 2018", "March 2018", "around 2018", "between 2018 and 2019", "date unknown". */
    fun label(): String {
        val s = startDate() ?: return "date unknown"
        val e = endDate() ?: s
        val core = when (precision) {
            DatePrecision.DAY -> "${s.dayOfMonth} ${month(s)} ${s.year}"
            DatePrecision.MONTH -> "${month(s)} ${s.year}"
            DatePrecision.YEAR -> "${s.year}"
            DatePrecision.RANGE -> when {
                s.dayOfMonth == 1 && s.monthValue == 1 && e.monthValue == 12 && e.dayOfMonth == 31 -> "between ${s.year} and ${e.year}"
                s.dayOfMonth == 1 && e.dayOfMonth == YearMonth.from(e).lengthOfMonth() -> "between ${month(s)} ${s.year} and ${month(e)} ${e.year}"
                else -> "between ${rangeEnd(s, true)} and ${rangeEnd(e, false)}"
            }
            DatePrecision.UNKNOWN -> return "date unknown"
        }
        return if (approximate) (if (precision == DatePrecision.RANGE) "roughly $core" else "around $core") else core
    }

    companion object {
        val UNKNOWN = HistoricalDate()
        fun day(d: LocalDate, approx: Boolean = false) = HistoricalDate(DatePrecision.DAY, d.toString(), d.toString(), approx)
        fun month(y: Int, m: Int, approx: Boolean = false) = YearMonth.of(y, m).let { HistoricalDate(DatePrecision.MONTH, it.atDay(1).toString(), it.atEndOfMonth().toString(), approx) }
        fun year(y: Int, approx: Boolean = false) = HistoricalDate(DatePrecision.YEAR, LocalDate.of(y, 1, 1).toString(), LocalDate.of(y, 12, 31).toString(), approx)
        fun range(from: LocalDate, to: LocalDate, approx: Boolean = false): HistoricalDate {
            val (a, b) = if (to.isBefore(from)) to to from else from to to
            return HistoricalDate(DatePrecision.RANGE, a.toString(), b.toString(), approx)
        }
        /** A date-only value from a legacy epoch timestamp (pre-1.8 memories stored the event as noon local time). */
        fun fromEpoch(ms: Long, zone: ZoneId = ZoneId.systemDefault()) = day(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())

        private fun month(d: LocalDate) = d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        private fun rangeEnd(d: LocalDate, first: Boolean): String = "${d.dayOfMonth} ${month(d)} ${d.year}"
    }
}

/**
 * Reads the ways people say when something happened: "12 March 2018", "2018-03-12", "March 2018", "around March 2018",
 * "early 2019", "summer 2019", "2018", "somewhere between 2018 and 2019", "from March to June 2019", "date unknown".
 * Returns null when the text isn't a date it understands (the caller asks again rather than guessing).
 */
object DateParser {
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private fun monthOf(w: String): Int? {
        val t = w.lowercase().trimEnd('.')
        if (t.length < 3) return null
        return MONTHS.indexOfFirst { it.startsWith(t) && (t.length >= 3) && (t == it || t == it.take(3) || t == it.take(4) || (t.length >= 3 && it.startsWith(t))) }.takeIf { it >= 0 }?.plus(1)
    }
    private val APPROX = Regex("(?i)^(?:around|about|approximately|approx\\.?|roughly|circa|c\\.|~|sometime (?:in|around)|some ?time in|probably|maybe|i think)\\s+")
    private val UNKNOWN = Regex("(?i)^(?:date\\s+)?(?:unknown|not sure|don'?t know|no idea|\\?|n/?a|-|none|unknown date)$")
    private val RANGE = Regex("(?i)^(?:somewhere |sometime )?(?:between|from)\\s+(.+?)\\s+(?:and|to|until|till|-|–)\\s+(.+)$")
    private val DASH_RANGE = Regex("^(\\d{4})\\s*[-–/]\\s*(\\d{4})$")
    private val ISO = Regex("^(\\d{4})-(\\d{1,2})(?:-(\\d{1,2}))?$")
    private val DMY = Regex("^(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?([A-Za-z]+\\.?),?\\s+(\\d{4})$")
    private val MDY = Regex("^([A-Za-z]+\\.?)\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})$")
    private val SLASH = Regex("^(\\d{1,2})[/.](\\d{1,2})[/.](\\d{4})$")
    private val MY = Regex("^([A-Za-z]+\\.?),?\\s+(?:of\\s+)?(\\d{4})$")
    private val Y = Regex("^(?:in\\s+|back in\\s+)?(\\d{4})$")
    private val PART = Regex("(?i)^(early|mid|middle of|late|end of|start of|beginning of|spring|summer|autumn|fall|winter)\\s*(?:of\\s+)?(\\d{4})$")

    fun parse(raw: String?): HistoricalDate? {
        var t = raw?.trim()?.trimEnd('.', '?', '!')?.replace(Regex("\\s+"), " ") ?: return null
        if (t.isEmpty() || UNKNOWN.matches(t)) return HistoricalDate.UNKNOWN
        t = t.replace(Regex("(?i)^(?:in|on|during|back in)\\s+"), "")
        var approx = false
        APPROX.find(t)?.let { approx = true; t = t.substring(it.range.last + 1) }
        RANGE.find(t)?.let { m ->
            val a = parse(m.groupValues[1]) ?: return null; val b = parse(m.groupValues[2]) ?: return null
            if (!a.known || !b.known) return null
            return HistoricalDate.range(a.startDate()!!, b.endDate()!!, approx || a.approximate || b.approximate)
        }
        DASH_RANGE.find(t)?.let { m -> return HistoricalDate.range(LocalDate.of(m.groupValues[1].toInt(), 1, 1), LocalDate.of(m.groupValues[2].toInt(), 12, 31), approx) }
        return runCatching { single(t, approx) }.getOrNull()
    }

    private fun single(t: String, approx: Boolean): HistoricalDate? {
        ISO.find(t)?.let { m ->
            val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            return if (m.groupValues[3].isEmpty()) HistoricalDate.month(y, mo, approx) else HistoricalDate.day(LocalDate.of(y, mo, m.groupValues[3].toInt()), approx)
        }
        DMY.find(t)?.let { m -> val mo = monthOf(m.groupValues[2]) ?: return null; return HistoricalDate.day(LocalDate.of(m.groupValues[3].toInt(), mo, m.groupValues[1].toInt()), approx) }
        MDY.find(t)?.let { m -> val mo = monthOf(m.groupValues[1]) ?: return null; return HistoricalDate.day(LocalDate.of(m.groupValues[3].toInt(), mo, m.groupValues[2].toInt()), approx) }
        SLASH.find(t)?.let { m -> return HistoricalDate.day(LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()), approx) } // day/month/year
        PART.find(t)?.let { m ->
            val y = m.groupValues[2].toInt()
            val (a, b) = when (m.groupValues[1].lowercase()) {
                "early", "start of", "beginning of" -> 1 to 4
                "mid", "middle of" -> 5 to 8
                "late", "end of" -> 9 to 12
                "spring" -> 3 to 5; "summer" -> 6 to 8; "autumn", "fall" -> 9 to 11
                else -> 12 to 14 // winter: December to February
            }
            val from = YearMonth.of(y, a).atDay(1)
            val to = if (b > 12) YearMonth.of(y + 1, b - 12).atEndOfMonth() else YearMonth.of(y, b).atEndOfMonth()
            return HistoricalDate.range(from, to, true)
        }
        MY.find(t)?.let { m -> val mo = monthOf(m.groupValues[1]) ?: return null; return HistoricalDate.month(m.groupValues[2].toInt(), mo, approx) }
        Y.find(t)?.let { m -> return HistoricalDate.year(m.groupValues[1].toInt(), approx) }
        return null
    }

    /** A date mentioned inside a question ("what happened in March 2018?"): the first span that parses. */
    fun find(text: String): HistoricalDate? {
        val words = text.replace(Regex("[?!,]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        for (len in 7 downTo 1) for (i in 0..words.size - len) {
            val span = words.subList(i, i + len).joinToString(" ")
            if (!Regex("\\d{4}").containsMatchIn(span)) continue
            parse(span)?.takeIf { it.known }?.let { return it }
        }
        return null
    }
}

/**
 * The one time source for conversation and history. Three clocks, never confused:
 *  - the real clock (device), for database integrity, audit and diagnostics — never narrated as Clu's date;
 *  - the conversation date: the simulated current date when simulated time is on, else the real date;
 *  - a historical perspective date: when set, Clu answers as of that day, using only what it knew by then.
 * Event dates come from the records themselves. Switching modes never rewrites a record.
 */
class CluTime(private val settings: SettingsStore, private val clock: Clock, private val settingChangedAt: (List<String>) -> Long? = { null }) {
    val zone: ZoneId get() = ZoneId.systemDefault()

    fun realNow(): Long = clock.now()
    fun realToday(): LocalDate = Instant.ofEpochMilli(clock.now()).atZone(zone).toLocalDate()

    fun mode(): String = settings.string(Settings.SIMULATED_TIME)
    fun simulated(): Boolean = mode() != "off"

    fun timelineStart(): LocalDate = parseDay(settings.string(Settings.TIMELINE_START)) ?: LocalDate.of(2018, 1, 1)

    /** The historical-perspective cutoff, if set. */
    fun perspective(): LocalDate? = parseDay(settings.string(Settings.HISTORY_PERSPECTIVE))

    /** The date Clu treats as "today" in conversation. */
    fun today(): LocalDate {
        perspective()?.let { return it }
        return when (mode()) {
            "fixed" -> parseDay(settings.string(Settings.SIMULATED_DATE)) ?: realToday()
            "advancing" -> {
                val base = parseDay(settings.string(Settings.SIMULATED_DATE)) ?: return realToday()
                // The simulated date moves on with real time from the moment it was set.
                val setAt = settingChangedAt(listOf(Settings.SIMULATED_DATE, Settings.SIMULATED_TIME)) ?: return base
                val since = ChronoUnit.DAYS.between(Instant.ofEpochMilli(setAt).atZone(zone).toLocalDate(), realToday()).coerceAtLeast(0)
                base.plusDays(since)
            }
            else -> realToday()
        }
    }

    /** "Now" in the conversation: [today] at the real time of day (only the date is simulated). */
    fun conversationNow(): Long {
        val t = Instant.ofEpochMilli(clock.now()).atZone(zone).toLocalTime()
        return today().atTime(if (perspective() != null) LocalTime.NOON else t).atZone(zone).toInstant().toEpochMilli()
    }

    /** One line for Clu's per-message context. */
    fun promptLine(): String {
        val d = today()
        val day = "${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${d.year}"
        perspective()?.let { return "Historical perspective: answer as of $day. Use only what you knew by then; do not mention anything that happened or that you learned later." }
        if (simulated()) return "Today's date in your timeline with the owner: $day. Use this date for \"today\", \"last year\" and how long ago things happened."
        val t = java.text.SimpleDateFormat("HH:mm", Locale.ENGLISH).format(java.util.Date(clock.now()))
        return "Current date and time: $day, $t."
    }

    companion object {
        fun parseDay(s: String?): LocalDate? = s?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
}

/** How long ago and when, in words, from a historical date and the conversation date. Deterministic. */
object TimePhrases {
    private fun month(d: LocalDate) = d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    private val NUM = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
    private fun n(i: Long) = if (i in 0..12) NUM[i.toInt()] else i.toString()

    /** "about eight years ago", "last year", "three months ago", "this month"; null when the date is unknown or in the future. */
    fun ago(date: HistoricalDate, today: LocalDate): String? {
        val s = date.startDate() ?: return null
        if (s.isAfter(today)) return null
        val approx = date.approximate || date.precision == DatePrecision.RANGE
        val about = if (approx) "about " else ""
        return when (date.precision) {
            DatePrecision.DAY -> {
                val days = ChronoUnit.DAYS.between(s, today)
                val months = ChronoUnit.MONTHS.between(s, today); val years = ChronoUnit.YEARS.between(s, today)
                when {
                    days == 0L -> "today"
                    days == 1L -> "yesterday"
                    days < 7 -> "${n(days)} days ago"
                    days < 14 -> "last week"
                    months < 1 -> "${n(days / 7)} weeks ago"
                    months == 1L -> "a month ago"
                    years < 1 -> "${n(months)} months ago"
                    years == 1L -> "${about}a year ago"
                    else -> "${about}${n(years)} years ago"
                }.let { if (approx && days >= 7 && !it.startsWith("about")) "about $it" else it }
            }
            DatePrecision.MONTH -> {
                val months = ChronoUnit.MONTHS.between(YearMonth.from(s), YearMonth.from(today))
                when {
                    months == 0L -> "this month"
                    months == 1L -> "last month"
                    months < 12 -> "$about${n(months)} months ago"
                    months < 24 -> "${about}a year ago"
                    else -> "about ${n(months / 12)} years ago"
                }
            }
            else -> {
                val years = (today.year - s.year).toLong()
                when {
                    date.precision == DatePrecision.YEAR && years == 0L -> "earlier this year"
                    date.precision == DatePrecision.YEAR && years == 1L -> "last year"
                    years <= 1 -> "${about}a year ago"
                    else -> "${if (date.precision == DatePrecision.YEAR && !date.approximate) "" else "about "}${n(years)} years ago"
                }
            }
        }
    }

    /** "back in March 2018", "on 12 March 2018", "in 2019", "around March 2018", "sometime between 2018 and 2019". */
    fun whenPhrase(date: HistoricalDate, today: LocalDate): String {
        val s = date.startDate() ?: return "at a time I don't have recorded"
        val longAgo = ChronoUnit.MONTHS.between(s, today) >= 12
        val back = if (longAgo) "back in " else "in "
        return when (date.precision) {
            DatePrecision.DAY -> (if (date.approximate) "around " else "on ") + "${s.dayOfMonth} ${month(s)} ${s.year}"
            DatePrecision.MONTH -> if (date.approximate) "around ${month(s)} ${s.year}" else back + "${month(s)} ${s.year}"
            DatePrecision.YEAR -> if (date.approximate) "around ${s.year}" else back + "${s.year}"
            DatePrecision.RANGE -> "sometime " + date.label().removePrefix("roughly ")
            DatePrecision.UNKNOWN -> "at a time I don't have recorded"
        }
    }
}
