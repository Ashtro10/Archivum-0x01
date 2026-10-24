package ai.clu.core.persona

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Renders owner-editable spoken templates: {owner} {greeting} {farewell} {time} {name}. */
class ResponseTemplates(private val settings: SettingsStore, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    fun ownerAddress(): String = settings.string(Settings.OWNER_ADDRESS).ifBlank { settings.string(Settings.OWNER_NAME) }

    fun render(template: String, nowMs: Long): String {
        val t = LocalTime.ofInstant(Instant.ofEpochMilli(nowMs), zone())
        val h = t.hour
        val greeting = when (h) { in 4..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
        val farewell = if (h >= 21 || h < 4) "Good night" else "Goodbye"
        val owner = ownerAddress()
        var s = template
            .replace("{greeting}", greeting)
            .replace("{farewell}", farewell)
            .replace("{time}", t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)))
            .replace("{name}", settings.string(Settings.CLU_NAME))
        s = if (owner.isBlank()) s.replace(Regex(",?\\s*\\{owner\\}"), "") else s.replace("{owner}", owner)
        return s.replace(Regex("\\s+([.,!?])"), "$1").replace(Regex("\\s{2,}"), " ").trim()
    }

    fun pick(key: String, nowMs: Long, seed: Long = nowMs / 1000): String {
        val list = settings.list(key)
        return render(list[Math.floorMod(seed, list.size.toLong()).toInt()], nowMs)
    }
}

object QuietHours {
    fun isQuiet(settings: SettingsStore, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!settings.bool(Settings.QUIET_ENABLED)) return false
        val t = LocalTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val start = LocalTime.parse(settings.string(Settings.QUIET_START))
        val end = LocalTime.parse(settings.string(Settings.QUIET_END))
        return if (start <= end) t >= start && t < end else t >= start || t < end
    }

    fun speechMuted(settings: SettingsStore, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()) =
        settings.bool(Settings.QUIET_MUTE_SPEECH) && isQuiet(settings, nowMs, zone)
}

/** Parses spoken/typed clock times: "7", "7:30", "7.30 pm", "19:05", "half past 7", "quarter to 8", "noon". */
object ClockTimeParser {
    fun parse(input: String): Pair<Int, Int>? {
        val s = input.lowercase().trim().replace("a.m.", "am").replace("p.m.", "pm").replace(Regex("\\s+"), " ")
        if (s.contains("noon") || s.contains("midday")) return 12 to 0
        if (s.contains("midnight")) return 0 to 0
        Regex("half past (\\d{1,2})").find(s)?.let { return adjust(it.groupValues[1].toInt(), 30, s) }
        Regex("quarter past (\\d{1,2})").find(s)?.let { return adjust(it.groupValues[1].toInt(), 15, s) }
        Regex("quarter to (\\d{1,2})").find(s)?.let { val h = it.groupValues[1].toInt() - 1; return adjust(if (h < 0) 23 else h, 45, s) }
        val m = Regex("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|in the morning|in the evening|at night|tonight)?").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        if (h > 23 || min > 59) return null
        return adjust(h, min, m.groupValues[3].ifEmpty { s })
    }

    private fun adjust(h0: Int, m: Int, ctx: String): Pair<Int, Int>? {
        var h = h0
        val pm = ctx.contains("pm") || ctx.contains("evening") || ctx.contains("night") || ctx.contains("tonight")
        val am = ctx.contains("am") || ctx.contains("morning")
        if (pm && h in 1..11) h += 12
        if (am && h == 12) h = 0
        return if (h in 0..23 && m in 0..59) h to m else null
    }

    /** Parses durations like "5 minutes", "1 hour 30 minutes", "90 seconds" into seconds. */
    fun parseDurationSec(input: String): Int? {
        val s = input.lowercase()
        var total = 0; var found = false
        for (m in Regex("(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b").findAll(s)) {
            val v = m.groupValues[1].toDouble(); found = true
            total += when (m.groupValues[2].first()) { 'h' -> (v * 3600).toInt(); 'm' -> (v * 60).toInt(); else -> v.toInt() }
        }
        if (!found && Regex("half an? hour").containsMatchIn(s)) return 1800
        return if (found && total > 0) total else null
    }
}
