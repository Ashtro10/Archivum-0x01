package ai.clu.core.live

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The text behind Settings → Connection → Diagnostics → Copy: connection, agent and provider state, errors, measured
 * latency and the recent pipeline events with timestamps. Built only from [LiveState] and [LiveEvent]s, which hold
 * no conversation content; anything shaped like a device key or a token is removed as a second line of defence.
 */
object LiveDiagnostics {
    private val jwt = Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")
    private val deviceKey = Regex("""\b[a-z0-9][a-z0-9-]{0,30}\.[A-Za-z0-9_-]{16,}\b""")
    private val bearer = Regex("""(?i)bearer\s+\S+""")
    private val urlCredentials = Regex("""(?<=://)[^/@\s]+@""")

    fun redact(s: String): String = s.replace(jwt, "<token>").replace(bearer, "Bearer <key>").replace(urlCredentials, "<credentials>@")
        .replace(deviceKey, "<device-key>")

    fun time(ms: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(ms))

    fun report(state: LiveState, events: List<LiveEvent>, header: Map<String, String>, nowMs: Long = System.currentTimeMillis()): String = redact(buildString {
        appendLine("Clu diagnostics ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(nowMs))}")
        header.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine("connection: ${state.link.name.lowercase()}${if (state.reconnectAttempt > 0) " (attempt ${state.reconnectAttempt})" else ""}")
        appendLine("agent: ${state.agent.name.lowercase()}")
        appendLine("microphone: ${if (state.micOn) "on" else "off"}; spoken replies: ${if (state.spokenReplies) "on" else "off"}")
        if (state.providers.isEmpty()) appendLine("providers: (not reported - no agent connected yet)")
        else appendLine("providers: " + state.providers.entries.joinToString("; ") { "${it.key}=${it.value}" })
        appendLine("last error: ${state.error ?: "none"}")
        if (state.appMetrics.isNotEmpty()) appendLine("measured on this phone (ms): " + state.appMetrics.entries.joinToString("; ") { "${it.key}=${it.value}" })
        if (state.metrics.isNotEmpty()) appendLine("last turn, measured by the agent (ms): " + state.metrics.entries.joinToString("; ") { "${it.key}=${it.value.toLong()}" })
        appendLine("events (oldest first):")
        events.forEach { appendLine("  ${time(it.atMs)} ${it.kind}${if (it.detail.isNotEmpty()) " " + it.detail else ""}") }
    })
}
