package ai.clu.core.notify

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.persona.QuietHours
import java.time.ZoneId

/** A notification as CLU sees it (from the live list; never stored). */
data class Incoming(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    /** Android's category: "msg", "call", "email", "alarm", "reminder", "event", "social"… or "" when the app gave none. */
    val category: String = "",
    val postedAt: Long = 0,
    val ongoing: Boolean = false,
)

/**
 * Which notifications CLU may read, summarise or announce, and how it words an announcement. The owner decides:
 * muted apps are never touched; announcing is off until switched on and then only covers the chosen apps (or, with no
 * apps chosen, messages, calls, alarms and reminders from any app); quiet hours silence it; each notification is
 * announced once; message text is spoken only if the owner allows it.
 */
object NotificationPolicy {
    private val IMPORTANT = setOf("msg", "call", "alarm", "reminder", "event", "email")

    fun muted(app: String, settings: SettingsStore): Boolean =
        settings.list(Settings.NOTIF_MUTED).any { it.isNotBlank() && app.contains(it.trim(), ignoreCase = true) }

    fun visible(n: Incoming, settings: SettingsStore): Boolean = !n.ongoing && !muted(n.app, settings)

    /** Notifications the owner should hear about, by their rules (independent of whether announcing is on). */
    fun important(n: Incoming, settings: SettingsStore): Boolean {
        if (!visible(n, settings)) return false
        val chosen = settings.list(Settings.NOTIF_APPS).filter { it.isNotBlank() }
        return if (chosen.isNotEmpty()) chosen.any { n.app.contains(it.trim(), ignoreCase = true) } else n.category in IMPORTANT
    }

    /** The words to speak for [n], or null when it must not be announced now. [announced] holds keys already spoken. */
    fun announcement(n: Incoming, settings: SettingsStore, now: Long, announced: Set<String>, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (!settings.bool(Settings.NOTIF_ANNOUNCE)) return null
        if (QuietHours.isQuiet(settings, now, zone)) return null
        if (!important(n, settings)) return null
        if (onceKey(n) in announced) return null
        val who = n.title.ifBlank { null }
        val head = when (n.category) {
            "call" -> "Call${who?.let { " from $it" } ?: ""} on ${n.app}"
            "msg", "email" -> "Message${who?.let { " from $it" } ?: ""} on ${n.app}"
            else -> "${n.app}${who?.let { ": $it" } ?: ""}"
        }
        val body = if (settings.bool(Settings.NOTIF_READ_CONTENT) && n.text.isNotBlank()) ": " + n.text.take(160) else ""
        return "$head$body."
    }

    /** Identity for "announce once": the same notification updated (same key, same text) is not announced again. */
    fun onceKey(n: Incoming) = n.key + "|" + (n.title + n.text).hashCode()

    /** "What did I miss": important notifications posted after [since], newest first, grouped by app. */
    fun missed(all: List<Incoming>, since: Long, settings: SettingsStore): List<Incoming> =
        all.filter { it.postedAt > since && important(it, settings) }.sortedByDescending { it.postedAt }
}

/**
 * The words for an incoming call. Names come from the owner's contacts only; an unknown number is announced as
 * "unknown number" (its digits are never read out) or not at all, as the owner chose. Quiet hours silence it.
 */
object CallAnnouncer {
    fun text(contactName: String?, number: String?, settings: SettingsStore, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (!settings.bool(Settings.CALL_ANNOUNCE)) return null
        if (QuietHours.isQuiet(settings, now, zone)) return null
        return when {
            !contactName.isNullOrBlank() -> "Call from $contactName."
            settings.string(Settings.CALL_UNKNOWN) == "silent" -> null
            number.isNullOrBlank() -> "Call from a private number."
            else -> "Call from an unknown number."
        }
    }
}
