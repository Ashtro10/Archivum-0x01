package ai.clu.core.voice

/**
 * Splits streamed LLM text into speakable chunks for incremental synthesis.
 *
 * The first chunk is short so that speech can start early: the first sentence end after 2 words, a clause boundary
 * (comma, dash) only after [firstMinWordsClause] words, or - if the model has produced [firstMaxWords] words without
 * either - the latest clause boundary or word boundary, never ending on a dangling function word ("the", "to", "for"...).
 * Later chunks follow sentence boundaries, use a comma only once a chunk is long ([laterClauseChars]), and are
 * force-cut at a word boundary beyond [laterMaxChars].
 *
 * Commas are cut sparingly on purpose: every chunk is synthesised as a separate utterance, so a cut comma used to get
 * a sentence-like pause (and any wait for the next chunk), while a comma inside a chunk keeps the voice's natural
 * short pause. SpeechText.withPause then sets each boundary's pause by its punctuation.
 *
 * A boundary only counts once the next character has arrived and is whitespace, so decimals ("3.5"), abbreviations
 * ("Dr.", "e.g.") and partial words are never split. Code blocks and tool-call markup are not spoken.
 * Works for Devanagari (danda "।") as well as Latin script.
 */
class AdaptiveChunker(
    private val firstMinWordsSentence: Int = 2,
    private val firstMinWordsClause: Int = 5,
    private val firstMaxWords: Int = 10,
    private val laterMinWords: Int = 3,
    private val laterClauseChars: Int = 110,
    private val laterMaxChars: Int = 200,
) {
    private val buf = StringBuilder()
    private var inCode = false
    /** A chunk that ends at a line break waits for one more character: a second break means a paragraph (longer pause). */
    private var pendingCut = -1
    var emitted = 0
        private set

    /** Adds streamed text; returns the chunks that are now ready to synthesise. */
    fun push(delta: String): List<String> {
        val out = mutableListOf<String>()
        for (ch in delta) {
            buf.append(ch)
            if (pendingCut >= 0) { emitCut(pendingCut, if (ch == '\n') SpeechText.PARAGRAPH else SpeechText.LINE, out); pendingCut = -1 }
            // Code fences: drop everything between ``` markers.
            if (buf.endsWith("```")) {
                val before = buf.substring(0, buf.length - 3)
                buf.setLength(0)
                if (!inCode) clean(before).takeIf { it.isNotEmpty() }?.let { out += it; emitted++ }
                inCode = !inCode
                continue
            }
            if (inCode) { if (buf.length > 4) buf.delete(0, buf.length - 3); continue }
            cut()?.let { n -> if (buf[n] == '\n') pendingCut = n else emitCut(n, null, out) }
        }
        return out
    }

    /** Emits buf[0, n) with an optional pause mark (line / paragraph end; see SpeechText.targetGapMs). */
    private fun emitCut(n: Int, mark: Char?, out: MutableList<String>) {
        val chunk = clean(buf.substring(0, n)).let { if (it.isNotEmpty() && mark != null) it + mark else it }
        buf.delete(0, n)
        while (buf.isNotEmpty() && buf[0].isWhitespace()) buf.deleteCharAt(0)
        if (chunk.isNotEmpty()) { out += chunk; emitted++ }
    }

    private fun takePending(): List<String> {
        if (pendingCut < 0) return emptyList()
        val out = mutableListOf<String>(); emitCut(pendingCut, SpeechText.LINE, out); pendingCut = -1
        return out
    }

    /** End of the response: whatever is left (unless inside a code block). */
    fun flush(): List<String> {
        val pending = takePending()
        val rest = if (inCode) "" else clean(buf.toString())
        buf.setLength(0); inCode = false
        return if (rest.isEmpty()) pending else (pending + rest).also { emitted++ }
    }

    /** Text waiting for a boundary (used by the pipeline to flush on a generation stall). */
    val pendingWords: Int get() = wordCount(buf)

    /**
     * Emits the pending complete words as a chunk if there are at least [minWords] of them (generation stalled, or
     * playback is about to run dry). A trailing partial word stays buffered: a word is only complete once the
     * following whitespace has arrived. With [avoidFunctionWordEnd] the cut is moved back so the chunk does not end
     * on "the"/"to"/"for"..., if that still leaves [minWords].
     */
    fun flushIfStalled(minWords: Int = firstMinWordsSentence, avoidFunctionWordEnd: Boolean = false): List<String> {
        if (inCode) return emptyList()
        takePending().let { if (it.isNotEmpty()) return it }
        var end = buf.indexOfLast { it.isWhitespace() }
        if (avoidFunctionWordEnd) {
            var e = end
            while (e > 0) {
                val start = buf.lastIndexOfAny(charArrayOf(' ', '\t', '\n'), e - 1) + 1
                val word = buf.substring(start, e).lowercase().trim(*PUNCT)
                if (word.isNotEmpty() && word !in FUNCTION_WORDS) break
                e = start - 1
            }
            if (e > 0 && wordCount(buf.subSequence(0, e)) >= minWords) end = e
        }
        if (end <= 0 || wordCount(buf.subSequence(0, end)) < minWords) return emptyList()
        val chunk = clean(buf.substring(0, end))
        buf.delete(0, end)
        while (buf.isNotEmpty() && buf[0].isWhitespace()) buf.deleteCharAt(0)
        if (chunk.isEmpty()) return emptyList()
        emitted++
        return listOf(chunk)
    }

    // Returns the end index (exclusive) of the next chunk in buf, or null if none is ready yet.
    private fun cut(): Int? {
        val s = buf
        val last = s.length - 1
        if (last < 1 || !s[last].isWhitespace()) return null // decide only when a word has been completed
        val boundary = last // the char before `last` ends a word; check what kind of boundary it is
        val prev = s[boundary - 1]
        val first = emitted == 0
        val words = wordCount(s)
        val strong = prev in STRONG && !isAbbreviation(s, boundary - 1) || prev == '\n' || s[last] == '\n'
        val weak = prev in WEAK || isDash(s, boundary)
        if (first) {
            if (strong && words >= firstMinWordsSentence) return boundary
            if (weak && words >= firstMinWordsClause) return boundary
            if (words >= firstMaxWords) return forcedCut(s, minWords = firstMinWordsSentence, slackWords = 2, words = words)
            return null
        }
        if (strong && words >= laterMinWords) return boundary
        if (weak && boundary >= laterClauseChars) return boundary
        if (boundary >= laterMaxChars) return forcedCut(s, minWords = laterMinWords, slackWords = 3, words = words)
        return null
    }

    /** Latest clause boundary with enough words before it; else the current word boundary unless it ends on a function word. */
    private fun forcedCut(s: CharSequence, minWords: Int, slackWords: Int, words: Int): Int? {
        for (i in s.length - 2 downTo 1) {
            if ((s[i] in WEAK || s[i] in STRONG) && s[i + 1].isWhitespace() && wordCount(s.subSequence(0, i + 1)) >= minWords) return i + 1
        }
        val lastWord = s.trimEnd().split(WS).lastOrNull()?.lowercase()?.trim(*PUNCT) ?: return null
        // Allow a little slack so a chunk does not end on "the"/"to"/"for"... (bad prosody), then cut regardless.
        return if (lastWord in FUNCTION_WORDS && words < firstMaxWords + slackWords && emitted == 0) null
        else if (lastWord in FUNCTION_WORDS && s.length < laterMaxChars + 40 && emitted > 0) null
        else s.length - 1
    }

    companion object {
        private const val STRONG = ".!?;:।॥\n"
        private const val WEAK = ",،"
        private val WS = Regex("\\s+")
        private val PUNCT = charArrayOf('.', ',', '!', '?', ';', ':', '"', '\'', ')', '(')
        private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "sr", "jr", "vs", "etc", "e.g", "i.e", "no", "prof", "approx", "dept", "fig")
        private val FUNCTION_WORDS = setOf(
            "a", "an", "the", "to", "of", "for", "and", "or", "but", "with", "in", "on", "at", "by", "from", "as", "is", "are",
            "was", "were", "be", "my", "your", "our", "their", "his", "her", "its", "this", "that", "these", "those", "if", "so",
            "than", "then", "into", "about", "i", "you", "we", "it", "not", "will", "can", "would", "should", "do", "have", "has",
            "का", "की", "के", "को", "में", "से", "पर", "और", "या", "है", "हैं", "एक", "यह", "वह")

        fun wordCount(s: CharSequence): Int {
            var n = 0; var inWord = false
            for (c in s) { if (c.isWhitespace()) inWord = false else if (!inWord) { inWord = true; n++ } }
            return n
        }

        private fun isDash(s: CharSequence, ws: Int): Boolean =
            ws >= 2 && (s[ws - 1] == '—' || s[ws - 1] == '–' || (s[ws - 1] == '-' && s[ws - 2].isWhitespace()))

        private fun isAbbreviation(s: CharSequence, dot: Int): Boolean {
            if (s[dot] != '.') return false
            var i = dot - 1
            while (i >= 0 && !s[i].isWhitespace()) i--
            val word = s.subSequence(i + 1, dot).toString().lowercase()
            return word in ABBREVIATIONS || (word.length == 1 && word[0].isLetter()) // initials: "J. R. R."
        }

        fun clean(s: String): String = SentenceChunker.clean(s)

        /** "hi" when the text is mostly Devanagari, else "en" (used to route a chunk to a Hindi or English voice). */
        fun script(text: String): String {
            var deva = 0; var latin = 0
            for (c in text) { if (c in 'ऀ'..'ॿ') deva++ else if (c in 'a'..'z' || c in 'A'..'Z') latin++ }
            return if (deva > latin) "hi" else "en"
        }
    }
}
