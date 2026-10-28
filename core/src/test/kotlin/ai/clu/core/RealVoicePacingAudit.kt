package ai.clu.core

import ai.clu.core.util.CluJson
import ai.clu.core.voice.SpeechText
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.*

/**
 * Pause shaping measured on real synthesised speech (Kokoro "Lewis", Clu's primary voice, and Piper Joe), through the
 * production [SpeechText.withPause]. Opt-in: -Dclu.realTtsDir=<dir with index.json + .f32 chunks> (made by a small
 * sherpa-onnx script; see docs/FINAL_DEVICE_VALIDATION.md) [-Dclu.realTtsOut=<json>]. Host synthesis, not the phone:
 * the same models and the same pause code, but not the phone's audio output.
 */
class RealVoicePacingAudit {
    private fun load(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.remaining() / 4) { b.float }
    }

    private fun silenceRuns(x: FloatArray, rate: Int): List<Pair<Int, Int>> { // (startMs, lengthMs) of internal pauses ≥ 60 ms
        val win = rate / 50 // 20 ms windows; a window is silent below -40 dB relative to the chunk's loudest window
        val rms = (0 until x.size / win).map { w -> sqrt((0 until win).sumOf { (x[w * win + it] * x[w * win + it]).toDouble() } / win) }
        val top = rms.maxOrNull() ?: return emptyList()
        val silent = rms.map { it < top * 0.01 }
        val first = silent.indexOfFirst { !it }; val last = silent.indexOfLast { !it }
        val runs = mutableListOf<Pair<Int, Int>>(); var i = first
        while (i in first..last) {
            if (silent[i]) { val s = i; while (i <= last && silent[i]) i++; if ((i - s) * 20 >= 60) runs += (s * 20) to ((i - s) * 20) } else i++
        }
        return runs
    }

    @Test fun pauseShapingOnRealSpeech() {
        val dir = System.getProperty("clu.realTtsDir")?.let(::File)
        assumeTrue(dir != null && File(dir, "index.json").exists(), "real TTS audio not provided")
        val index = CluJson.parseToJsonElement(File(dir, "index.json").readText()).jsonArray
        val rows = mutableListOf<JsonObject>()
        for (e in index) {
            val o = e.jsonObject
            val text = o["text"]!!.jsonPrimitive.content; val rate = o["rate"]!!.jsonPrimitive.int
            val x = load(File(dir!!, o["file"]!!.jsonPrimitive.content))
            val peak = x.maxOf { abs(it) }
            val th = peak * 0.02f
            val lead = x.indexOfFirst { abs(it) >= th } * 1000 / rate
            val tail = (x.size - 1 - x.indexOfLast { abs(it) >= th }) * 1000 / rate
            val y = SpeechText.withPause(text, x, rate) // production pause shaping
            val yPeak = y.maxOf { abs(it) }
            // Every audible sample survives, in order (the audible parts of x and y are identical).
            val ax = x.filter { abs(it) >= th }; val ay = y.filter { abs(it) >= th }
            assertEquals(ax, ay, "no audible sample removed or reordered: '$text'")
            assertEquals(peak, yPeak)
            val newLead = y.indexOfFirst { abs(it) >= th } * 1000 / rate
            val newTail = (y.size - 1 - y.indexOfLast { abs(it) >= th }) * 1000 / rate
            val gap = newTail + SpeechText.LEAD_MS // silence before the next chunk's first sound
            val target = SpeechText.targetGapMs(text)
            assertTrue(abs(gap - target) <= 25, "boundary gap $gap ms vs target $target ms for '$text' (voice gave lead $lead, tail $tail)")
            assertTrue(newLead <= SpeechText.LEAD_MS + 1)
            // The loudest 20 ms of what was removed, relative to the chunk's peak (dB): it must be inaudible.
            val removed = x.copyOfRange(0, maxOf(0, x.indexOfFirst { abs(it) >= th } - SpeechText.LEAD_MS * rate / 1000)).maxOfOrNull { abs(it) } ?: 0f
            // What 1.5.x did: pad only, up to the target minus an assumed 60 ms lead, never trim; the next chunk then
            // added its own lead-in (taken here as this voice's typical lead).
            val oldTail = maxOf(SpeechText.trailingSilenceMs(x, rate), SpeechText.targetGapMs(text) - 60)
            val oldGap = oldTail + lead
            rows += buildJsonObject {
                put("old_boundary_gap_ms", oldGap)
                put("voice", o["voice"]!!.jsonPrimitive.content); put("text", text)
                put("voice_lead_ms", lead); put("voice_tail_ms", tail)
                put("shaped_lead_ms", newLead); put("boundary_gap_ms", gap); put("target_ms", target)
                put("removed_max_db_re_peak", if (removed > 0) "%.1f".format(20 * log10(removed / peak)).toDouble() else -120.0)
                put("internal_pauses_ms", JsonArray(silenceRuns(x, rate).map { JsonPrimitive(it.second) }))
                put("synth_ms", o["synth_ms"]!!.jsonPrimitive.int)
            }
            println("pacing: ${rows.last()}")
        }
        System.getProperty("clu.realTtsOut")?.let { File(it).apply { parentFile.mkdirs() }.writeText(JsonArray(rows).toString()) }
    }
}
