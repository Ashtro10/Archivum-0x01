package ai.clu.core.agent

/**
 * Names the speech recogniser gets wrong ("Leman Russ" heard as "lemon rust"), corrected from what the conversation
 * already knows — names that CLU or the owner said in the recent conversation, research results, and the owner's own
 * vocabulary list — never from guesses. A phrase is corrected only when it sounds like a whole known name: every word
 * of a multi-word name must sound alike, so "a lemon tart" never becomes "Leman Russ"; a single-word name needs a very
 * close match and is never matched against an ordinary word. Weaker matches are not applied: CLU asks.
 */
object ProperNouns {
    data class Fix(val text: String, val from: String, val to: String, val confident: Boolean)

    private val WORD = Regex("[\\p{L}\\p{N}']+")
    private val NAME = Regex("(?<![\\p{L}'])(\\p{Lu}[\\p{L}'-]+(?:\\s+(?:of\\s+|the\\s+|de\\s+|von\\s+)?\\p{Lu}[\\p{L}'-]+){0,3})")
    private val NOT_NAMES = setOf("I", "I'm", "I've", "I'll", "OK", "Okay", "Yes", "No", "The", "A", "An", "It", "This", "That", "What", "Who", "Where", "When",
        "Why", "How", "Can", "Could", "Would", "Should", "Please", "Hello", "Hi", "Hey", "Sorry", "Sure", "Well", "So", "And", "But", "If", "You", "He", "She",
        "We", "They", "My", "Your", "Here", "There", "Thanks", "Thank", "Good", "Great", "Let", "Do", "Did", "Is", "Are", "Was", "Were", "Tell", "In", "On", "At")

    /** Capitalised names (one to four words) in [text], excluding sentence-initial function words. */
    fun namesIn(text: String): List<String> = NAME.findAll(text).map { it.value.trim() }
        .map { n -> n.split(Regex("\\s+")).dropWhile { it in NOT_NAMES }.joinToString(" ") }
        .filter { it.length >= 3 && it !in NOT_NAMES }.distinct().toList()

    /** [name] without greeting/function words around it ("Hello, Clue" → "Clue"); null if nothing name-like is left. */
    fun clean(name: String): String? = WORD.findAll(name).map { it.value }.filter { it !in NOT_NAMES && it.lowercase() !in Grounding.FUNCTION }
        .joinToString(" ").takeIf { it.length >= 3 }

    fun correct(text: String, vocabulary: Collection<String>): Fix? {
        val tokens = WORD.findAll(text).toList()
        if (tokens.isEmpty() || tokens.size > 25) return null
        var best: Pair<Fix, Double>? = null
        for (entry in vocabulary.map { it.trim() }.filter { it.length >= 3 }.distinct()) {
            val parts = WORD.findAll(entry).map { it.value }.toList()
            if (parts.isEmpty() || parts.size > 4) continue
            // Already said correctly: nothing to fix for this name.
            if (Regex("(?i)(?<![\\p{L}])${Regex.escape(entry)}(?![\\p{L}])").containsMatchIn(text)) continue
            for (i in 0..tokens.size - parts.size) {
                val window = tokens.subList(i, i + parts.size)
                // A misheard word keeps its first sound in practice ("Lemon"/"Leman", "rust"/"Russ"); words that start
                // differently are not taken for the name ("hear" is never "Hello").
                if (window.zip(parts).any { (t, p) -> !t.value.equals(p, true) && (t.value.length < 3 || !t.value[0].equals(p[0], true)) }) continue
                val sims = window.zip(parts).map { (t, p) -> if (t.value.equals(p, true)) 1.0 else Phonetics.similarity(t.value.lowercase(), p.lowercase()) }
                val mean = sims.average()
                val common = window.all { it.value.lowercase() in ContextualCorrection.COMMON || it.value.lowercase() in Grounding.FUNCTION }
                if (common) continue
                val (ok, confident) = if (parts.size >= 2) (sims.all { it >= 0.55 } && mean >= 0.62) to (sims.all { it >= 0.6 } && mean >= 0.72)
                    else (sims[0] >= 0.85 && window[0].value.lowercase() !in COMMON_WORDS) to true
                if (!ok) continue
                val from = text.substring(window.first().range.first, window.last().range.last + 1)
                val fixed = text.substring(0, window.first().range.first) + entry + text.substring(window.last().range.last + 1)
                if (best == null || mean > best.second) best = Fix(fixed, from, entry, confident) to mean
            }
        }
        return best?.first
    }

    /** Ordinary words a single-word name is never matched against ("horse" is not "Horus"). */
    private val COMMON_WORDS = setOf("horse", "house", "lemon", "rust", "russ", "talking", "walking", "stop", "search", "answer", "hear", "here", "there",
        "their", "clue", "glue", "blue", "true", "said", "send", "sent", "name", "names", "time", "times", "word", "words", "world", "work", "worked")
}

/**
 * "I said Leman Russ." / "No, I said talking." / "I meant …": a correction of the owner's previous request, not a new
 * question. The phrase in the previous request that sounds most like the correction is replaced, and the corrected
 * request is what CLU answers ("Who is Lemon Russ?" + "I said Leman Russ" → "Who is Leman Russ?").
 */
object SaidCorrection {
    private val RE = Regex("(?i)^(?:(?:no|nope|not quite|wrong|sorry)[,.!]?\\s*)*(?:i said|i meant|i asked(?: about)?|i was asking about|i'?m asking about|i was talking about|i'?m talking about|i was saying)[,:]?\\s+(.+?)[\\s.!?]*$")
    private val WORD = Regex("[\\p{L}\\p{N}']+")

    /** The corrected words, if [text] is a correction. */
    fun parse(text: String): String? = RE.find(text.trim())?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }

    /** [previous] with the phrase that sounds most like [said] replaced by it, or null when nothing in it sounds similar. */
    fun apply(said: String, previous: String?): String? {
        val prev = previous?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val tokens = WORD.findAll(prev).toList()
        val parts = WORD.findAll(said).map { it.value }.toList()
        if (tokens.isEmpty() || parts.isEmpty()) return null
        if (Regex("(?i)(?<![\\p{L}])${Regex.escape(said)}(?![\\p{L}])").containsMatchIn(prev)) return prev // it was heard right: ask it again as it was
        // Word by word against windows of the same length: every word must sound alike (no run-together matching).
        val n = parts.size
        if (n > tokens.size) return null
        var best: Pair<Int, Double>? = null
        for (i in 0..tokens.size - n) {
            val sims = tokens.subList(i, i + n).zip(parts).map { (t, p) -> if (t.value.equals(p, true)) 1.0 else Phonetics.similarity(t.value.lowercase(), p.lowercase()) }
            if (sims.any { it < 0.5 }) continue
            val mean = sims.average()
            if (best == null || mean > best.second) best = i to mean
        }
        val (i, sim) = best ?: return null
        if (sim < 0.6) return null
        return prev.substring(0, tokens[i].range.first) + said + prev.substring(tokens[i + n - 1].range.last + 1)
    }
}
