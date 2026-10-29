package ai.clu.core.config

import ai.clu.core.conversation.MessageMeta
import ai.clu.core.persona.QuietHours

/**
 * Decisions the Android UI and services make from settings. Kept here (pure functions) so that every UI/service
 * setting has a tested binding; the app calls these instead of reading raw values.
 */
object UiPolicy {
    enum class MicBehavior { HOLD_TO_TALK, TAP_AUTO_STOP, CONVERSATION }

    fun micBehavior(s: SettingsStore) = when (s.string(Settings.MIC_MODE)) {
        "hold" -> MicBehavior.HOLD_TO_TALK; "conversation" -> MicBehavior.CONVERSATION; else -> MicBehavior.TAP_AUTO_STOP
    }

    /** Whether a reply should be spoken aloud. */
    fun shouldSpeak(s: SettingsStore, viaVoice: Boolean, nowMs: Long): Boolean {
        if (QuietHours.speechMuted(s, nowMs)) return false
        return viaVoice || s.bool(Settings.SPEAK_RESPONSES)
    }

    /** Greeting spoken when a voice session starts, or null if greetings are disabled. */
    fun activationGreeting(s: SettingsStore, templates: ai.clu.core.persona.ResponseTemplates, nowMs: Long): String? =
        if (s.bool(Settings.GREET_ON_START)) templates.pick(Settings.GREETINGS, nowMs) else null

    fun showLatency(s: SettingsStore) = s.bool(Settings.DEV_SHOW_LATENCY)
    fun showToolTraces(s: SettingsStore) = s.bool(Settings.DEV_TOOL_TRACES)
    fun diagnosticsVisible(s: SettingsStore) = s.bool(Settings.DEV_DIAGNOSTICS)

    /** Source badge under a reply, or null when disabled. */
    /**
     * The one line shown for a reply's sources (collapsed; tapping it lists them). The answer is the product: sources
     * support it and are never spoken. A single official source is named ("Source: GDACS …").
     */
    fun sourcesSummary(sources: List<ai.clu.core.conversation.SourceRef>): String? = when {
        sources.isEmpty() -> null
        sources.size == 1 && sources[0].title.startsWith("GDACS") -> "Source: GDACS (UN / European Commission)"
        sources.size == 1 && sources[0].title.startsWith("Open-Meteo") -> "Source: Open-Meteo forecast data"
        else -> "Sources (${sources.size})"
    }

    fun sourceBadge(s: SettingsStore, meta: MessageMeta): String? {
        if (!s.bool(Settings.SHOW_SOURCES)) return null
        val where = when (meta.source) {
            "local" -> "On-device model"
            "home_device" -> "Home device's model"
            "external" -> "External AI service"
            "tool" -> "Direct action"
            "template" -> "Built-in response"
            else -> meta.source
        }
        // A searched answer leads with that (its sources are listed under it); a failed search is said plainly.
        if (meta.sources.isNotEmpty()) return if (meta.tools.any { it == "hazards" || it == "weather" }) "Checked live data · $where" else "Searched the web · $where"
        if (meta.error == "search_failed") return "Search unavailable · answered without the web"
        val net = if (meta.stats["network"] == 1.0) " · used internet" else ""
        val tools = if (meta.tools.isNotEmpty()) " · " + meta.tools.joinToString() else ""
        return where + net + tools
    }

    fun latencyLine(meta: MessageMeta): String {
        val st = meta.stats
        fun f(k: String) = st[k]?.takeIf { it >= 0 }?.let { "%.0f".format(it) }
        return listOfNotNull(
            f("asr_ms")?.let { "ASR $it ms" }, f("prompt_ms")?.let { "prompt $it ms" }, f("first_token_ms")?.let { "first token $it ms" },
            st["tok_per_s"]?.takeIf { it > 0 }?.let { "%.1f tok/s".format(it) }, f("first_audio_ms")?.let { "first audio $it ms" },
        ).joinToString(" · ")
    }

    /** Settings visible in the settings screen: advanced ones only with diagnostics enabled. */
    fun visibleSettings(s: SettingsStore, category: SettingCategory): List<SettingDef> =
        Settings.all.filter { it.category == category && (!it.advanced || diagnosticsVisible(s)) }
}

object ServicePolicy {
    enum class ProactiveDelivery { NOTIFICATION, SPEAK }

    /** Background listening runs only if enabled, the wake word or clap is on, and the mic permission is granted. */
    /** Background wake listening belongs to the on-device engine; with the Clu server selected it stays off (no silent offline replies). */
    fun shouldRunListeningService(s: SettingsStore, micPermission: Boolean): Boolean =
        micPermission && s.string(Settings.VOICE_MODE) != "livekit" && s.bool(Settings.BACKGROUND_LISTENING) &&
            (s.bool(Settings.WAKE_ENABLED) || s.bool(Settings.CLAP_ENABLED))

    fun shouldServeSync(s: SettingsStore) = s.bool(Settings.SYNC_ENABLED) && s.bool(Settings.SYNC_SERVE)

    /** Listen only when there is someone to talk to: a paired device, or an open pairing window (reduces exposure on shared networks). */
    fun shouldListenForSync(s: SettingsStore, pairedPeers: Int, pairingOpen: Boolean) =
        shouldServeSync(s) && (pairedPeers > 0 || pairingOpen)

    /** Interval between automatic sync attempts, or null for manual only. */
    fun syncIntervalMs(s: SettingsStore): Long? =
        if (!s.bool(Settings.SYNC_ENABLED)) null else s.int(Settings.SYNC_INTERVAL).takeIf { it > 0 }?.let { it * 60_000L }

    fun proactiveDelivery(s: SettingsStore, appInForeground: Boolean): ProactiveDelivery =
        if (s.string(Settings.PROACTIVE_DELIVERY) == "speak" && appInForeground) ProactiveDelivery.SPEAK else ProactiveDelivery.NOTIFICATION
}

/** Which apps the open-app tool may launch. */
object AppPolicy {
    fun allowed(s: SettingsStore, label: String, packageName: String): Boolean {
        if (s.string(Settings.APP_POLICY) != "allowlist") return true
        return s.list(Settings.APP_ALLOWLIST).any { it.equals(label, ignoreCase = true) || it.equals(packageName, ignoreCase = true) }
    }
}
