package ai.clu.core.voice

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable
data class WakeWord(
    val phrase: String,
    val enabled: Boolean = true,
    val primary: Boolean = false,
    /** Alternative spellings/transcriptions (e.g. how the recogniser hears the phrase; added from recorded samples). */
    val aliases: List<String> = emptyList(),
) {
    companion object {
        fun defaults() = listOf(
            WakeWord("Clue", primary = true, aliases = listOf("clu", "klu", "clew", "hey clue", "hey clu")),
            WakeWord("Buddy"),
            WakeWord("Friend"),
        )
    }
}

enum class Environment { QUIET, NOISY }

data class WakeMatch(val word: WakeWord, val score: Double, val heard: String, val remainder: String)

/**
 * Matches recognised text against wake phrases with a normalised edit-distance similarity over word windows,
 * so "hey clue what's the time" activates "Clue" and yields the remainder "what's the time".
 */
object WakePhraseMatcher {
    fun normalize(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val d = levenshtein(a, b)
        return 1.0 - d.toDouble() / max(a.length, b.length)
    }

    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Best match within the first few words of [text]; null if below [threshold]. */
    fun match(text: String, words: List<WakeWord>, threshold: Double, searchWords: Int = 4): WakeMatch? {
        val tokens = normalize(text).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        var best: WakeMatch? = null
        for (w in words) {
            val variants = (listOf(w.phrase) + w.aliases).map(::normalize).filter { it.isNotEmpty() }
            for (v in variants) {
                val n = v.split(' ').size
                for (start in 0 until min(searchWords, tokens.size)) {
                    for (len in max(1, n - 1)..min(n + 1, tokens.size - start)) {
                        val window = tokens.subList(start, start + len).joinToString(" ")
                        val s = similarity(window, v)
                        if (s >= threshold && (best == null || s > best.score)) {
                            best = WakeMatch(w, s, window, tokens.drop(start + len).joinToString(" "))
                        }
                    }
                }
            }
        }
        return best
    }

    /** True if the whole utterance is (approximately) one of the termination phrases. */
    fun isTermination(text: String, phrases: List<String>, threshold: Double = 0.8): Boolean {
        val t = normalize(text)
        return phrases.any { p ->
            val n = normalize(p)
            similarity(t, n) >= threshold || (t.endsWith(n) && t.length - n.length <= 12)
        }
    }
}

/**
 * Decides which activation methods are allowed given the measured environment.
 * Quiet: all enabled phrases (+ clap if enabled). Noisy + strict: primary phrase only, no clap.
 */
class WakePolicy(
    val words: List<WakeWord>,
    val clapEnabled: Boolean,
    val noiseAdaptive: Boolean,
    val strictInNoise: Boolean,
    val noisyThresholdDb: Double,
) {
    fun environment(noiseFloorDb: Double): Environment =
        if (noiseAdaptive && noiseFloorDb > noisyThresholdDb) Environment.NOISY else Environment.QUIET

    fun allowedWords(env: Environment): List<WakeWord> {
        val enabled = words.filter { it.enabled }
        return if (env == Environment.NOISY && strictInNoise) enabled.filter { it.primary } else enabled
    }

    fun clapAllowed(env: Environment): Boolean = clapEnabled && !(env == Environment.NOISY && strictInNoise)
}
