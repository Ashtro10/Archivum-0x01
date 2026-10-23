package ai.clu.core.memory

/**
 * Deterministic text analysis used for memory indexing and retrieval (identical on device and in tests).
 * Lower-cases, splits on non-letters/digits, drops stopwords and applies a light suffix stemmer.
 */
object TextAnalysis {
    private val stop = setOf(
        "a", "an", "the", "and", "or", "but", "if", "then", "so", "of", "to", "in", "on", "at", "by", "for", "with", "about",
        "from", "into", "over", "after", "before", "is", "am", "are", "was", "were", "be", "been", "being", "do", "does", "did",
        "have", "has", "had", "i", "me", "my", "mine", "we", "us", "our", "you", "your", "yours", "he", "him", "his", "she",
        "her", "it", "its", "they", "them", "their", "this", "that", "these", "those", "what", "which", "who", "whom", "whose",
        "when", "where", "why", "how", "can", "could", "would", "should", "will", "shall", "may", "might", "must", "not", "no",
        "yes", "just", "very", "too", "also", "as", "than", "there", "here", "up", "down", "out", "all", "any", "some", "more",
        "most", "much", "many", "do", "don't", "dont", "i'm", "im", "it's", "remember", "tell", "know", "clu", "please", "again",
        "ever", "said", "say", "like", "get", "got", "let", "us", "one", "thing", "things", "something", "anything",
    )

    fun tokens(text: String): List<String> =
        text.lowercase()
            .replace("'s ", " ")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it !in stop }
            .map(::stem)

    fun termFrequencies(text: String): Map<String, Int> = tokens(text).groupingBy { it }.eachCount()

    /** Small suffix-stripping stemmer (subset of Porter step 1) – enough to match plural/tense variants. */
    fun stem(w: String): String {
        if (w.length <= 3 || w.any { it.isDigit() }) return w
        var s = w
        s = when {
            s.endsWith("sses") -> s.dropLast(2)
            s.endsWith("ies") && s.length > 4 -> s.dropLast(3) + "y"
            s.endsWith("ss") -> s
            s.endsWith("s") && !s.endsWith("us") && !s.endsWith("is") -> s.dropLast(1)
            else -> s
        }
        s = when {
            s.endsWith("eed") -> s
            s.endsWith("ing") && s.length > 5 -> undouble(s.dropLast(3))
            s.endsWith("ed") && s.length > 4 -> undouble(s.dropLast(2))
            else -> s
        }
        if (s.endsWith("ly") && s.length > 5) s = s.dropLast(2)
        if (s.endsWith("e") && s.length > 4) s = s.dropLast(1)
        return s
    }

    private fun undouble(s: String): String =
        if (s.length >= 3 && s[s.length - 1] == s[s.length - 2] && s.last() !in "lsz") s.dropLast(1) else s
}
