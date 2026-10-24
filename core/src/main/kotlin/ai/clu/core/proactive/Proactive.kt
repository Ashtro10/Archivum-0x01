package ai.clu.core.proactive

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.CluStore
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.memory.CLU_SCOPE
import ai.clu.core.memory.MemoryCategory
import ai.clu.core.memory.MemoryRepository
import ai.clu.core.persona.QuietHours
import ai.clu.core.persona.ResponseTemplates
import ai.clu.core.util.newId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ProactiveContext(
    val appInForeground: Boolean,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val lastInteractionAt: Long = 0,
    val appOpenedNow: Boolean = false,
)

data class ProactiveMessage(val kind: String, val text: String, val refId: String? = null)

/**
 * Decides whether Clu should start a conversation. Respects enable switch, quiet hours, daily cap and spacing.
 * Only uses approved data it already has (memories, battery state); it does not detect physical presence.
 */
class ProactivePlanner(
    private val store: CluStore,
    private val settings: SettingsStore,
    private val memories: MemoryRepository,
    private val templates: ResponseTemplates,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val diag: ai.clu.core.diag.DiagnosticsLog? = null,
) {
    private val db = store.db
    private val wellbeing = Regex("\\b(sick|ill|unwell|fever|cold|flu|stressed|stress|sad|upset|anxious|anxiety|headache|hurt|injur\\w*|exhausted|depressed|lonely)\\b", RegexOption.IGNORE_CASE)

    fun plan(ctx: ProactiveContext, now: Long = store.clock.now()): ProactiveMessage? {
        if (!settings.bool(Settings.PROACTIVE_ENABLED)) return null
        if (QuietHours.isQuiet(settings, now, zone())) return null
        val dayStart = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().atStartOfDay(zone()).toInstant().toEpochMilli()
        val today = db.queryOne("SELECT COUNT(*) FROM proactive_log WHERE ts >= ?", listOf(dayStart))
        if (today >= settings.int(Settings.PROACTIVE_MAX)) return null
        val last = db.queryOne("SELECT COALESCE(MAX(ts), 0) FROM proactive_log", emptyList())
        if (last > 0 && now - last < settings.int(Settings.PROACTIVE_GAP) * 3_600_000L) return null

        val hour = Instant.ofEpochMilli(now).atZone(zone()).hour
        val candidate = guarded("battery") { batteryCandidate(ctx) }
            ?: guarded("followup") { followUpCandidate(now) }
            ?: guarded("wellbeing") { wellbeingCandidate(now) }
            ?: guarded("greeting") { greetingCandidate(ctx, now, dayStart) }
            ?: guarded("checkin") { checkInCandidate(ctx, now, hour, dayStart) }
            ?: return null
        db.execute("INSERT INTO proactive_log(id, ts, kind, text) VALUES(?,?,?,?)", listOf(newId(), now, candidate.kind + (candidate.refId?.let { ":$it" } ?: ""), candidate.text))
        return candidate
    }

    /**
     * Error boundary per candidate: a failure (for example a malformed owner-edited template) is logged and that
     * message is skipped; the other candidates and the caller (app start, background job) carry on.
     */
    private inline fun guarded(kind: String, block: () -> ProactiveMessage?): ProactiveMessage? = try {
        block()?.takeIf { it.text.isNotBlank() }
    } catch (e: Exception) {
        diag?.e("proactive", "$kind message skipped", e)
        null
    }

    private fun batteryCandidate(ctx: ProactiveContext): ProactiveMessage? {
        if (!settings.bool(Settings.AWARE_BATTERY)) return null
        val pct = ctx.batteryPercent ?: return null
        if (pct > 15 || ctx.charging) return null
        return ProactiveMessage("battery", templates.render("{owner}, battery is at $pct%. You may want to charge me.", store.clock.now()))
    }

    private fun alreadyDone(kind: String, ref: String) =
        db.queryOne("SELECT COUNT(*) FROM proactive_log WHERE kind = ?", listOf("$kind:$ref")) > 0

    private fun followUpCandidate(now: Long): ProactiveMessage? {
        if (!settings.bool(Settings.PROACTIVE_FOLLOWUPS)) return null
        val m = memories.list(CLU_SCOPE, limit = 300).firstOrNull { mem ->
            val at = mem.eventAt ?: return@firstOrNull false
            mem.category != MemoryCategory.DIARY && at in (now - 36 * 3_600_000L)..(now - 2 * 3_600_000L) && !alreadyDone("followup", mem.id)
        } ?: return null
        return ProactiveMessage("followup", templates.render("{owner}, how did it go — ${m.title.trimEnd('.')}?", now), m.id)
    }

    private fun wellbeingCandidate(now: Long): ProactiveMessage? {
        if (!settings.bool(Settings.PROACTIVE_WELLBEING)) return null
        val m = memories.list(CLU_SCOPE, limit = 100).firstOrNull { mem ->
            mem.updatedAt >= now - 3 * 86_400_000L && wellbeing.containsMatchIn(mem.title + " " + mem.content) && !alreadyDone("wellbeing", mem.id)
        } ?: return null
        val word = wellbeing.find(m.title + " " + m.content)!!.value.lowercase()
        return ProactiveMessage("wellbeing", templates.render("{owner}, you mentioned being $word recently. How are you feeling now?", now), m.id)
    }

    private fun greetingCandidate(ctx: ProactiveContext, now: Long, dayStart: Long): ProactiveMessage? {
        if (!settings.bool(Settings.PROACTIVE_GREET_OPEN) || !ctx.appOpenedNow) return null
        if (db.queryOne("SELECT COUNT(*) FROM proactive_log WHERE kind = 'greeting' AND ts >= ?", listOf(dayStart)) > 0) return null
        return ProactiveMessage("greeting", templates.pick(Settings.GREETINGS, now))
    }

    private fun checkInCandidate(ctx: ProactiveContext, now: Long, hour: Int, dayStart: Long): ProactiveMessage? {
        if (!settings.bool(Settings.PROACTIVE_CHECKINS) || hour !in 18..21) return null
        if (ctx.lastInteractionAt > now - 4 * 3_600_000L) return null
        if (db.queryOne("SELECT COUNT(*) FROM proactive_log WHERE kind = 'checkin' AND ts >= ?", listOf(dayStart)) > 0) return null
        return ProactiveMessage("checkin", templates.render("How was your day, {owner}?", now))
    }

    fun history(limit: Int = 50) = db.query("SELECT ts, kind, text FROM proactive_log ORDER BY ts DESC LIMIT $limit") { Triple(it.lng(0), it.str(1), it.str(2)) }

    private fun ai.clu.core.db.SqlDriver.queryOne(sql: String, args: List<Any?>): Long = query(sql, args) { it.long(0) ?: 0L }.firstOrNull() ?: 0L
}
