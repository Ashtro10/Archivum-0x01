package ai.clu.core.diag

import ai.clu.core.tools.ToolExecutor

data class Suggestion(val title: String, val detail: String, val settingKey: String? = null)

/**
 * Foundation for safe self-improvement: turns measured tool reliability and performance into concrete suggestions.
 * It never changes configuration by itself; the owner applies changes through settings (which are versioned and
 * can be rolled back).
 */
object ImprovementAdvisor {
    fun suggestions(reliability: List<ToolExecutor.Reliability>, perf: List<DiagnosticsLog.PerfSummary>): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        for (r in reliability) if (r.runs >= 3 && r.failures * 2 > r.runs) out += Suggestion(
            "${r.tool} fails often (${r.failures}/${r.runs})",
            "Last error: ${r.lastError ?: "unknown"}. Check its permissions/network settings, or disable it so Clu stops trying.",
            "tool.${r.tool}.enabled")
        fun avg(m: String) = perf.firstOrNull { it.metric == m }?.takeIf { it.count >= 3 }?.avg
        avg("first_audio_ms")?.let { if (it > 2500) out += Suggestion("Spoken replies start slowly (avg ${"%.0f".format(it)} ms)",
            "Keep \"Speak while generating\" on, choose a brief response length, or use a smaller model.", "voice.incremental") }
        avg("tokens_per_s")?.let { if (it < 4.0) out += Suggestion("Text generation is slow (avg ${"%.1f".format(it)} tokens/s)",
            "Try the 1.5B model, reduce the context length, or check thermal throttling in diagnostics.", "model.contextLength") }
        avg("model_load_ms")?.let { if (it > 20_000) out += Suggestion("Model loading is slow (avg ${"%.0f".format(it / 1000)} s)",
            "Keep \"Load model at start\" on and raise \"Unload when idle\" so the model stays ready.", "model.autoUnloadMin") }
        avg("asr_finalize_ms")?.let { if (it > 800) out += Suggestion("Speech recognition finalises slowly (avg ${"%.0f".format(it)} ms)",
            "Shorten the end-of-speech pause or close other heavy apps.", "voice.endSilenceMs") }
        return out
    }
}
