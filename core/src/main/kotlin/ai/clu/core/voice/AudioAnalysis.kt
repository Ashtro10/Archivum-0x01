package ai.clu.core.voice

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

object AudioMath {
    fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        if (to <= from) return 0.0
        var acc = 0.0
        for (i in from until to) acc += samples[i] * samples[i]
        return sqrt(acc / (to - from))
    }

    fun dbfs(rms: Double): Double = if (rms <= 1e-9) -120.0 else 20 * log10(rms)

    fun pcm16ToFloat(pcm: ShortArray, n: Int = pcm.size): FloatArray = FloatArray(n) { pcm[it] / 32768f }
}

/**
 * Tracks the background noise floor in dBFS from frames that are NOT speech (per the VAD), using a slow
 * exponential average of frame energies with a low-percentile bias so transient sounds do not inflate it.
 */
class NoiseEstimator(private val frameMs: Int = 30, private val timeConstantSec: Double = 4.0) {
    var floorDb: Double = -60.0
        private set
    private var initialized = false
    private val alpha get() = frameMs / 1000.0 / timeConstantSec

    fun addFrame(samples: FloatArray, isSpeech: Boolean) {
        if (isSpeech) return
        val db = AudioMath.dbfs(AudioMath.rms(samples))
        if (!initialized) { floorDb = db; initialized = true; return }
        // Rise slowly, fall faster: biases toward the quieter percentile of the background.
        val a = if (db > floorDb) alpha else alpha * 4
        floorDb += a * (db - floorDb)
    }
}

/**
 * Detects hand claps: short broadband transients with a sharp energy rise from the background, decaying within ~120 ms.
 * Sensitivity in [0,1] scales the required rise and peak level. Emits when [requiredClaps] claps occur with
 * 120-700 ms spacing.
 */
class ClapDetector(sampleRate: Int, sensitivity: Double, private val requiredClaps: Int) {
    private val win = sampleRate / 100 // 10 ms analysis windows
    private val riseDb = 24.0 - 14.0 * sensitivity.coerceIn(0.0, 1.0) // 24 dB (strict) .. 10 dB (sensitive)
    private val minPeakDb = -18.0 - 16.0 * sensitivity.coerceIn(0.0, 1.0) // -18 .. -34 dBFS
    private var bgDb = -60.0
    private var t = 0L // elapsed windows
    private var lastClapAt = -1000L
    private var inTransient = false
    private var transientStart = 0L
    private var transientPeak = -120.0
    private val claps = ArrayDeque<Long>()
    private val buf = FloatArray(win)
    private var fill = 0
    private var prevDb = -60.0

    /** Feed audio; returns true when a complete clap pattern was detected. */
    fun process(samples: FloatArray): Boolean {
        var detected = false
        for (s in samples) {
            buf[fill++] = s
            if (fill == win) { fill = 0; if (window(buf)) detected = true }
        }
        return detected
    }

    private fun window(w: FloatArray): Boolean {
        t++
        val db = AudioMath.dbfs(AudioMath.rms(w))
        // High-frequency content: claps are broadband; voiced speech is dominated by low frequencies.
        var diff = 0.0
        for (i in 1 until w.size) diff += abs(w[i] - w[i - 1])
        var mag = 0.0
        for (x in w) mag += abs(x)
        val hfRatio = if (mag > 1e-9) diff / mag else 0.0
        var result = false
        if (!inTransient) {
            if (db - max(bgDb, prevDb) >= riseDb && db >= minPeakDb && hfRatio > 0.35) {
                inTransient = true; transientStart = t; transientPeak = db
            } else {
                bgDb += (if (db > bgDb) 0.02 else 0.1) * (db - bgDb)
            }
        } else {
            transientPeak = max(transientPeak, db)
            val dur = t - transientStart
            if (db < transientPeak - 12 || dur > 15) {
                inTransient = false
                if (dur <= 12) result = registerClap(transientStart)
            }
        }
        prevDb = db
        return result
    }

    private fun registerClap(at: Long): Boolean {
        if (at - lastClapAt < 12) return false // < 120 ms: same clap/echo
        while (claps.isNotEmpty() && at - claps.last() > 70) claps.clear() // > 700 ms gap breaks pattern
        claps.addLast(at)
        lastClapAt = at
        if (claps.size >= requiredClaps) { claps.clear(); return true }
        return false
    }
}

/**
 * Splits streamed text into speakable chunks (sentences), so synthesis can start before generation finishes.
 * Tool-call markup and code blocks are not spoken.
 */
class SentenceChunker(private val minChars: Int = 24, private val maxChars: Int = 240) {
    private val buf = StringBuilder()
    private var inCode = false

    fun push(text: String): List<String> {
        buf.append(text)
        val out = mutableListOf<String>()
        while (true) {
            val s = buf.toString()
            if (s.contains("```")) {
                // Toggle code mode: drop everything inside fences.
                val idx = s.indexOf("```")
                val before = s.substring(0, idx)
                if (!inCode && before.isNotBlank()) out += flushText(before)
                inCode = !inCode
                buf.setLength(0); buf.append(s.substring(idx + 3))
                continue
            }
            if (inCode) { if (buf.length > 4) buf.delete(0, buf.length - 3); break }
            val cut = findBoundary(s) ?: break
            val sentence = s.substring(0, cut)
            buf.delete(0, cut)
            val clean = clean(sentence)
            if (clean.isNotEmpty()) out += clean
        }
        return out
    }

    fun flush(): List<String> {
        val s = if (inCode) "" else buf.toString()
        buf.setLength(0); inCode = false
        val c = clean(s)
        return if (c.isEmpty()) emptyList() else listOf(c)
    }

    private fun flushText(s: String): List<String> = clean(s).let { if (it.isEmpty()) emptyList() else listOf(it) }

    private fun findBoundary(s: String): Int? {
        for (i in s.indices) {
            val c = s[i]
            val atEnd = i + 1 >= s.length
            if (c == '\n' && i >= 1) return i + 1
            if ((c == '.' || c == '!' || c == '?' || c == ';') && !atEnd && s[i + 1].isWhitespace() && i + 1 >= minChars) return i + 1
        }
        if (s.length > maxChars) {
            val sp = s.lastIndexOf(' ', maxChars)
            return if (sp > minChars) sp + 1 else maxChars
        }
        return null
    }

    companion object {
        fun clean(s: String): String = s
            .replace(Regex("<tool>.*?</tool>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("(?m)^\\s*CALL\\s+\\S+.*$"), " ")
            .replace(Regex("[*_#`>|]+"), " ")
            .replace(Regex("\\[(.*?)]\\((.*?)\\)"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

/** Applies owner-defined pronunciation fixes ("word=say-it-like-this") before speech synthesis. */
class SpeechTextPreprocessor(entries: List<String>) {
    private val rules: List<Pair<Regex, String>> = entries.mapNotNull { e ->
        val i = e.indexOf('=')
        if (i <= 0 || i == e.length - 1) null
        else Regex("\\b" + Regex.escape(e.substring(0, i).trim()) + "\\b", RegexOption.IGNORE_CASE) to e.substring(i + 1).trim()
    }

    fun apply(text: String): String = rules.fold(text) { acc, (re, rep) -> re.replace(acc, rep) }
}

/**
 * "Metallic" voice colouring: blend of ring modulation (sine carrier) and a short feedback comb filter,
 * giving a controlled synthetic timbre without destroying intelligibility. amount in [0,1].
 */
class VoiceEffect(private val sampleRate: Int, amount: Double) {
    private val amt = amount.coerceIn(0.0, 1.0)
    private val carrierHz = 55.0 + 40.0 * amt
    private val combDelay = (sampleRate * 0.0045).toInt().coerceAtLeast(1) // 4.5 ms
    private val combFb = 0.35 + 0.3 * amt
    private val line = FloatArray(combDelay)
    private var lineIdx = 0
    private var phase = 0.0

    val isBypassed get() = amt <= 0.001

    fun process(x: FloatArray): FloatArray {
        if (isBypassed) return x
        val out = FloatArray(x.size)
        val ringMix = 0.45 * amt
        val combMix = 0.6 * amt
        val step = 2 * Math.PI * carrierHz / sampleRate
        for (i in x.indices) {
            val ring = x[i] * kotlin.math.sin(phase).toFloat()
            phase += step; if (phase > 2 * Math.PI) phase -= 2 * Math.PI
            val delayed = line[lineIdx]
            val combOut = x[i] + (combFb * delayed).toFloat()
            line[lineIdx] = combOut
            lineIdx = (lineIdx + 1) % combDelay
            val y = (1 - ringMix - combMix * 0.5) * x[i] + ringMix * ring + combMix * 0.5 * combOut * 0.6
            out[i] = y.toFloat().coerceIn(-1f, 1f)
        }
        return out
    }
}
