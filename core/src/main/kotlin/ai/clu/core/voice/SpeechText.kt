package ai.clu.core.voice

/**
 * Prepares reply text for the voice: the chat shows the original, the voice hears a version without formatting that
 * reads naturally. Nothing is added to or removed from the meaning; only notation is turned into words.
 *
 * - Markdown (headings, emphasis, list bullets, tables, inline code, links), raw URLs, tool markup and emoji are not
 *   spoken; a link keeps its label.
 * - Times, ISO dates, currency, units next to numbers, ranges and common abbreviations are written out, because the
 *   phonemiser (espeak-ng, used by every on-device voice) reads "9:30", "2026-10-26", "₹500" or "km/h" badly.
 */
object SpeechText {
    /** Marks a paragraph end inside a chunk (set by [AdaptiveChunker]); becomes a longer pause, never spoken. */
    const val PARAGRAPH = '\u2029'
    /** Marks a line end without punctuation (list item); a sentence-length pause. */
    const val LINE = '\u2028'

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val URL = Regex("""\b(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    private val MD_LINK = Regex("""\[([^\]]*)]\(([^)]*)\)""")
    private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")
    private val TIME = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)(?::[0-5]\d)?\s*([AaPp])\.?\s?[Mm]\.?(?=\W|$)|\b([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val ISO_DATE = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val RANGE = Regex("""(?<![\d-])(\d{1,4}(?:\.\d+)?)\s?[–-]\s?(\d{1,4}(?:\.\d+)?)\b(?!-)""")
    private val CURRENCY_BEFORE = Regex("""(₹|Rs\.?\s?|INR\s?|\$|€|£)\s?(\d[\d,]*(?:\.\d+)?)(\s?(?:k|K|lakh|crore|million|billion))?""")
    private val UNITS = listOf(
        "km/h" to "kilometres per hour", "kmph" to "kilometres per hour", "mph" to "miles per hour", "m/s" to "metres per second",
        "km" to "kilometres", "kg" to "kilograms", "cm" to "centimetres", "mm" to "millimetres", "mg" to "milligrams", "ml" to "millilitres",
        "GB" to "gigabytes", "MB" to "megabytes", "TB" to "terabytes", "GHz" to "gigahertz", "MHz" to "megahertz", "kWh" to "kilowatt hours",
        "°C" to "degrees Celsius", "°F" to "degrees Fahrenheit", "℃" to "degrees Celsius", "%" to "percent", "hrs" to "hours", "hr" to "hours",
        "mins" to "minutes", "min" to "minutes", "sec" to "seconds", "ms" to "milliseconds", "m" to "metres", "g" to "grams", "h" to "hours",
    )
    private val UNIT_RE = UNITS.map { (u, w) -> Regex("(\\d)\\s?" + Regex.escape(u) + "(?=[^\\p{L}\\d]|$)") to w }
    private val ABBREV = listOf(
        Regex("""\be\.g\.""", RegexOption.IGNORE_CASE) to "for example", Regex("""\bi\.e\.""", RegexOption.IGNORE_CASE) to "that is",
        Regex("""\betc\.""", RegexOption.IGNORE_CASE) to "et cetera", Regex("""\bvs\.?(?=\s)""", RegexOption.IGNORE_CASE) to "versus",
        Regex("""\bapprox\.""", RegexOption.IGNORE_CASE) to "approximately", Regex("""\bDr\.(?=\s)""") to "Doctor",
        Regex("""\bMr\.(?=\s)""") to "Mister", Regex("""\bMrs\.(?=\s)""") to "Missus", Regex("""\bSt\.(?=\s\p{Lu})""") to "Saint",
        Regex("""&""") to " and ", Regex("""\bw/(?=\s)""") to "with",
    )

    fun forSpeech(input: String): String {
        var s = input
        s = s.replace(Regex("<tool>.*?(</tool>|$)", RegexOption.DOT_MATCHES_ALL), " ")
        s = s.replace(Regex("(?m)^\\s*CALL\\s+\\S+.*$"), " ")
        s = MD_LINK.replace(s) { it.groupValues[1] }
        s = URL.replace(s, " ")
        // Sources are for the screen, never for the voice: no "[2]", "according to Wikipedia", "(source: …)".
        s = ai.clu.core.agent.AnswerStyle.withoutSourceTalk(s)
        s = s.replace(Regex("`([^`]*)`"), "$1")
        s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")                 // headings
        s = s.replace(Regex("(?m)^\\s*(?:[-*+•]|\\d{1,2}[.)])\\s+"), "")    // list bullets / numbers
        s = s.replace(Regex("(?m)^\\s*\\|?\\s*:?-{3,}.*$"), " ")            // table separator rows
        s = s.replace('|', ',')
        s = s.replace(Regex("(\\*\\*|__|\\*|_|~~)(?=\\S)(.+?)(?<=\\S)\\1"), "$2") // emphasis
        s = s.replace(Regex("[*#>`~]+"), " ")
        s = EMOJI.replace(s, "")
        s = TIME.replace(s) { m ->
            val g = m.groupValues
            if (g[1].isNotEmpty()) "${g[1].toInt()}${minutes(g[2])} ${g[3].uppercase()} M" + (if (m.value.endsWith(".")) "." else "")
            else "${g[4].toInt()}${minutes(g[5])}"
        }
        s = ISO_DATE.replace(s) { m ->
            val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
            if (mo in 1..12 && d in 1..31) "$d ${MONTHS[mo - 1]} ${m.groupValues[1]}" else m.value
        }
        s = CURRENCY_BEFORE.replace(s) { m ->
            val unit = when (m.groupValues[1].trim().uppercase().trimEnd('.')) { "₹", "RS", "INR" -> "rupees"; "$" -> "dollars"; "€" -> "euros"; else -> "pounds" }
            val scale = m.groupValues[3].trim().let { if (it.equals("k", true)) " thousand" else if (it.isNotEmpty()) " $it" else "" }
            "${m.groupValues[2].replace(",", "")}$scale $unit"
        }
        s = RANGE.replace(s) { m -> "${m.groupValues[1]} to ${m.groupValues[2]}" }
        for ((re, w) in UNIT_RE) s = re.replace(s) { m -> "${m.groupValues[1]} $w" }
        for ((re, w) in ABBREV) s = re.replace(s, w)
        s = s.replace(Regex("\\.{3,}|…"), "…")
        return s.replace(Regex("[ \\t]+"), " ").replace(Regex(" +([,.!?;:…])"), "$1").replace(PARAGRAPH.toString(), "").replace(LINE.toString(), "").trim()
    }

    private fun minutes(mm: String) = when { mm == "00" -> ""; mm.startsWith("0") -> " oh ${mm[1]}"; else -> " $mm" }

    /**
     * Total silence wanted after a chunk, by how it ends: what a calm speaker leaves between clauses, sentences and
     * paragraphs. Voices leave some silence themselves (measured on the build host: Piper Joe ~180 ms after a sentence,
     * ~50-80 ms after a comma or ellipsis; Kokoro ~190 ms everywhere, plus 50-130 ms before the next chunk), so
     * [padMs] only tops up the difference and never stretches a voice that already pauses enough.
     */
    fun targetGapMs(rawChunk: String): Int {
        val t = rawChunk.trimEnd()
        if (rawChunk.trimEnd(' ', '\t').endsWith(PARAGRAPH) || rawChunk.endsWith("\n\n")) return 650
        if (rawChunk.trimEnd(' ', '\t').endsWith(LINE)) return maxOf(320, targetGapMs(rawChunk.trimEnd(' ', '\t', LINE)))
        return when {
            t.endsWith("…") || t.endsWith("...") -> 450
            t.endsWith("?") || t.endsWith("!") -> 380
            t.endsWith(".") || t.endsWith("।") -> 360
            t.endsWith(":") || t.endsWith(";") -> 280
            t.endsWith(",") || t.endsWith("—") || t.endsWith("–") -> 170
            else -> 120 // cut without punctuation (stall flush): keep the flow
        }
    }

    /** Extra silence to append to synthesised audio so the gap after it reaches [targetGapMs]. */
    fun padMs(rawChunk: String, samples: FloatArray, rate: Int, nextLeadMs: Int = 60): Int =
        (targetGapMs(rawChunk) - trailingSilenceMs(samples, rate) - nextLeadMs).coerceAtLeast(0)

    fun trailingSilenceMs(x: FloatArray, rate: Int): Int {
        if (x.isEmpty()) return 0
        var peak = 0f
        for (v in x) { val a = kotlin.math.abs(v); if (a > peak) peak = a }
        if (peak <= 0f) return x.size * 1000 / rate
        val th = peak * 0.1f
        var i = x.size - 1
        while (i >= 0 && kotlin.math.abs(x[i]) < th) i--
        return (x.size - 1 - i) * 1000 / rate
    }

    /**
     * The chunk's audio with its silences set exactly: at most [LEAD_MS] before the first sound and, after the last
     * sound, the pause its ending calls for ([targetGapMs] − [LEAD_MS], so with the next chunk's lead the gap is the
     * target). Voices add their own silence around every chunk they synthesise as if it were a whole utterance
     * (Kokoro ~190 ms after a comma as after a full stop, plus a lead-in), and only padding (never trimming) made a
     * comma between two chunks nearly as long as a full stop while a full stop stood out little. Only near-silent
     * samples (below [SILENCE] of the chunk's peak) are ever removed; every audible sample is kept, in order.
     */
    fun withPause(rawChunk: String, samples: FloatArray, rate: Int): FloatArray {
        if (samples.isEmpty() || rate <= 0) return samples
        var peak = 0f
        for (v in samples) { val a = kotlin.math.abs(v); if (a > peak) peak = a }
        if (peak <= 0f) return samples
        val th = peak * SILENCE
        var first = 0; while (first < samples.size && kotlin.math.abs(samples[first]) < th) first++
        var last = samples.size - 1; while (last > first && kotlin.math.abs(samples[last]) < th) last--
        val start = maxOf(0, first - LEAD_MS * rate / 1000)
        val tail = (targetGapMs(rawChunk) - LEAD_MS).coerceAtLeast(0) * rate / 1000
        val keptTail = minOf(tail, samples.size - 1 - last) // the voice's own decay first, then digital silence
        val out = FloatArray(last + 1 - start + tail)
        System.arraycopy(samples, start, out, 0, last + 1 - start + keptTail)
        return out
    }

    /** Silence before a chunk's first sound (ms) kept by [withPause]. */
    const val LEAD_MS = 40
    /** Below this share of the chunk's peak a sample counts as silence (about −34 dB): soft consonants stay. */
    private const val SILENCE = 0.02f
}
