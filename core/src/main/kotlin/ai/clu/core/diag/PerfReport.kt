package ai.clu.core.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Diagnostics → Performance view and its shareable report. Lists the measurements the device acceptance checklist
 * asks for, with units, and says plainly when one hasn't been measured yet (never a placeholder number). Contains no
 * message text, memories, diary, logs or keys — only versions, device model, conditions and timings.
 */
object PerfReport {
    data class Metric(val key: String, val label: String, val unit: String)

    /** The measurements the checklist uses, in the order shown. */
    val KEY_METRICS = listOf(
        Metric("app_create_ms", "Cold start (app process ready)", "ms"),
        Metric("resume_to_frame_ms", "Return from background → first frame", "ms"),
        Metric("endpoint_to_first_audio_ms", "End of speech → first audio", "ms"),
        Metric("stop_to_silence_ms", "Stop/interruption → playback silenced (app side)", "ms"),
        Metric("barge_in_detect_ms", "Interruption detected while Clu speaks", "ms"),
        Metric("turn_first_token_ms", "Message → first word of the reply", "ms"),
        Metric("turn_total_ms", "Message → reply complete", "ms"),
        Metric("asr_end_to_text_ms", "End of speech → recognised text", "ms"),
        Metric("tts_first_phrase_ms", "Text → first synthesised phrase", "ms"),
        Metric("memory_retrieval_ms", "Memory retrieval per message", "ms"),
        Metric("model_load_ms", "Language model load", "ms"),
    )
    private val units = mapOf("tokens_per_s" to "tokens/s", "tokens_per_s_short" to "tokens/s", "prefill_tok_per_s" to "tokens/s",
        "prompt_tokens" to "tokens", "prompt_new_tokens" to "tokens", "bench_tok_per_s" to "tokens/s", "tts_rtf" to "× real time",
        "tts_ms_per_char" to "ms/char", "tts_errors" to "count")

    /** One sample per voice turn; its detail is the turn's stage timeline (VoiceTurnTimeline), listed separately. */
    const val VOICE_TIMELINE = "voice_turn_timeline"

    fun unitOf(metric: String) = KEY_METRICS.firstOrNull { it.key == metric }?.unit ?: units[metric]
        ?: if (metric.endsWith("_ms")) "ms" else if (metric.startsWith("turn_new_tokens")) "tokens" else ""

    private fun n(v: Double) = if (v >= 100) "%.0f".format(Locale.US, v) else "%.1f".format(Locale.US, v)

    fun line(s: DiagnosticsLog.PerfSummary): String {
        val u = unitOf(s.metric).let { if (it.isEmpty()) "" else " $it" }
        return "p50 ${n(s.p50)}$u · p90 ${n(s.p90)}$u · min ${n(s.min)} · max ${n(s.max)} · last ${n(s.last)} (n=${s.count})"
    }

    /** Key metrics first (with "not measured yet" when absent), then any other recorded metric. */
    fun rows(perf: List<DiagnosticsLog.PerfSummary>): List<Pair<String, String>> {
        val byKey = perf.associateBy { it.metric }
        return KEY_METRICS.map { m -> "${m.label} (${m.key})" to (byKey[m.key]?.let(::line) ?: "not measured yet") } +
            perf.filter { p -> p.metric != VOICE_TIMELINE && KEY_METRICS.none { it.key == p.metric } }.map { it.metric to line(it) }
    }

    fun build(app: String, device: String, conditions: Map<String, String>, memory: String?, perf: List<DiagnosticsLog.PerfSummary>, now: Long,
              recentTurns: List<String> = emptyList()): String = buildString {
        appendLine("Clu performance report")
        appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(now))}")
        appendLine("App: $app")
        appendLine("Device: $device")
        appendLine("Conditions: " + conditions.entries.joinToString(", ") { "${it.key}=${it.value}" })
        appendLine("Memory now: ${memory ?: "not available"}")
        appendLine("Measurements on this device (p50 = median, p90 = 90th percentile, over the last samples kept):")
        appendLine("(\"…_background_ms\": the app was not visible during the measurement; \"…_cancelled/_timeout/_error/_interrupted\": " +
            "stopped, timed-out, failed or interrupted requests. Kept apart from the normal timings, not deleted.)")
        rows(perf).forEach { (k, v) -> appendLine("- $k: $v") }
        appendLine("- Battery: not measured by Clu (see Android Settings → Battery for Clu's share)")
        appendLine("Recent voice turns, newest first (ms; each stage from the end of the previous one; prefill cause and new/total prompt tokens):")
        if (recentTurns.isEmpty()) appendLine("- none recorded yet") else recentTurns.forEach { appendLine("- $it") }
        append("This report contains no messages, memories, diary entries, logs or keys.")
    }
}
