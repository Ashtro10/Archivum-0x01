package ai.clu.voice

import ai.clu.core.voice.AudioMath
import kotlin.math.abs

/** One recognised (or empty) utterance, for Diagnostics → Speech recognition. Stays in memory on this device. */
data class AsrRecord(
    val at: Long,
    val engine: String,
    val language: String,
    val text: String,
    val speechMs: Long,
    val preRollMs: Long,
    /** Silence waited after the last speech before the turn was ended (endpoint delay). */
    val endSilenceMs: Long,
    /** Recognition time after the turn ended. */
    val asrMs: Long,
    val peakDbfs: Double,
    val rmsDbfs: Double,
    /** Share of samples at or near full scale (clipping). */
    val clippedPct: Double,
    val capture: String,
    val note: String = "",
)

/** Per-utterance audio statistics collected on the capture thread. */
class UtteranceStats {
    var startAt = 0L; private set
    var preRollSamples = 0; private set
    var speechMs = 0L; private set
    var endSilenceMs = 0L; private set
    var peak = 0f; private set
    var rms = 0.0; private set
    var clipped = 0.0; private set

    fun start(now: Long, preRoll: Int) { startAt = now; preRollSamples = preRoll }
    fun frame(@Suppress("UNUSED_PARAMETER") f: FloatArray) {}
    fun end(now: Long, silenceMs: Long, audio: FloatArray) {
        endSilenceMs = silenceMs
        speechMs = (now - startAt - silenceMs).coerceAtLeast(0)
        var pk = 0f; var e = 0.0; var c = 0
        for (v in audio) { val a = abs(v); if (a > pk) pk = a; e += v * v; if (a >= 0.99f) c++ }
        peak = pk; rms = if (audio.isEmpty()) 0.0 else kotlin.math.sqrt(e / audio.size); clipped = if (audio.isEmpty()) 0.0 else 100.0 * c / audio.size
    }

    fun record(engine: String, language: String, text: String, asrMs: Long, capture: String, note: String = "") = AsrRecord(
        System.currentTimeMillis(), engine, language, text, speechMs, preRollSamples * 1000L / SAMPLE_RATE, endSilenceMs, asrMs,
        AudioMath.dbfs(peak.toDouble()), AudioMath.dbfs(rms), clipped, capture, note)
}

/** The last utterances and the audio of the most recent one ("Play what Clu heard"). Memory only, never uploaded. */
class AsrLog(private val max: Int = 40) {
    private val items = ArrayDeque<AsrRecord>()
    @Volatile var lastAudio: FloatArray? = null; private set
    @Synchronized fun add(r: AsrRecord, audio: FloatArray?) { items.addFirst(r); while (items.size > max) items.removeLast(); if (audio != null) lastAudio = audio }
    @Synchronized fun all(): List<AsrRecord> = items.toList()
    @Synchronized fun clear() { items.clear(); lastAudio = null }
}
