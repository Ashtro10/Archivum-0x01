package ai.clu.core.agent

import ai.clu.core.conversation.SourceRef

/**
 * What the model is given from a search, and the units an answer is checked against.
 *
 * The model receives the results in the same safe frame as before (untrusted-text header, one line per result with its
 * site), but each result's text is cut to the sentences most relevant to the question: a short prompt is both easier
 * for a small model to use and faster to read on the phone (prompt reading dominated device latency).
 */
object Evidence {
    private val SENTENCE = Regex("(?<=[.!?])\\s+(?=[\\p{Lu}\\d\"'(\\[])")
    private val RESULT_LINE = Regex("^(\\[\\d+] [^\\n]*?\\([^()\\n]*;[^()\\n]*\\)): (.*)$")
    private const val PAGE = "    Page excerpt: "

    fun sentences(text: String): List<String> = text.replace(Regex("\\s+"), " ").trim().split(SENTENCE).map { it.trim() }.filter { it.isNotEmpty() }

    /** The most relevant sentences of [text] (original order kept), at most [maxChars]. */
    fun relevant(text: String, question: String, subjects: List<String>, maxChars: Int): String {
        val all = sentences(text)
        if (text.length <= maxChars || all.size <= 1) return text.trim().take(maxChars)
        val want = Grounding.words(question + " " + subjects.joinToString(" "))
        val ranked = all.withIndex().sortedByDescending { (i, sn) -> Grounding.words(sn).count { it in want } * 10 - i }
        val keep = mutableSetOf<Int>(); var len = 0
        for ((i, sn) in ranked) { if (len + sn.length > maxChars && keep.isNotEmpty()) continue; keep += i; len += sn.length + 1 }
        return all.withIndex().filter { it.index in keep }.joinToString(" ") { it.value }.take(maxChars)
    }

    /** The search summary with each result's snippet and page excerpt cut to their relevant sentences. */
    fun condense(summary: String, question: String, subjects: List<String>, snippetChars: Int = 320, pageChars: Int = 480): String =
        summary.lines().joinToString("\n") { line ->
            val m = RESULT_LINE.find(line)
            when {
                m != null -> m.groupValues[1] + ": " + relevant(m.groupValues[2], question, subjects, snippetChars)
                line.startsWith(PAGE) -> PAGE + relevant(line.removePrefix(PAGE), question, subjects, pageChars)
                else -> line
            }
        }

    /**
     * The units a claim must be supported by: per result, its title and the sentences of its snippet and page excerpt
     * (from the full results, not the condensed prompt), so that two neighbouring sentences can support one claim
     * together ("Leman Russ is a Primarch. He leads the VI Legion."). Sentences that address an AI are never support.
     */
    fun units(summary: String, sources: List<SourceRef>, subjects: List<String> = emptyList()): List<List<String>> {
        val block = summary.substringAfter("BEGIN WEB RESULTS", "").substringBefore("END WEB RESULTS")
        val groups = mutableListOf<MutableList<String>>()
        for (line in block.lines()) {
            val m = RESULT_LINE.find(line.trimEnd())
            when {
                m != null -> groups += mutableListOf(m.groupValues[1].replace(Regex("^\\[\\d+] "), "").replace(Regex("\\s*\\([^()]*;[^()]*\\)$"), ""))
                    .apply { addAll(sentences(m.groupValues[2])) }
                line.startsWith(PAGE) && groups.isNotEmpty() -> groups.last().addAll(sentences(line.removePrefix(PAGE)))
            }
        }
        val raw = groups.ifEmpty { sources.map { mutableListOf(it.title).apply { addAll(sentences(it.snippet)) } } }
        val all = raw.map { g -> g.filterNot { INJECTION.containsMatchIn(it) || it.isBlank() } }.filter { it.isNotEmpty() }
        // Only results about the subject support an answer about it (others came along with the search).
        val about = all.filter { g -> subjects.any { ResearchRunner.mentions(g.joinToString(" "), it) } }
        return if (subjects.isEmpty() || about.isEmpty()) all else about
    }

    /** Text in the results that speaks to an AI (instructions, roles, tool calls): data at best, never support. */
    val INJECTION = Regex("(?i)\\b(?:ignore|disregard|forget)\\s+(?:all\\s+|any\\s+|the\\s+)?(?:previous|prior|above|earlier|your)\\s+(?:instructions|prompts?|rules)|" +
        "\\bsystem prompt\\b|\\byou are now\\b|\\bas an ai\\b|\\bcall\\s+[a-z]+\\.[a-z_]+|\\bAI was removed\\b")
}

/**
 * Checks one sentence of a researched answer against the results, claim by claim: its names and numbers must occur
 * together in one result's text (one sentence, or two neighbouring ones), and at least half of its other content words
 * with them. This tests that the *relation* is in the evidence ("Tenzing Norgay reached Everest in 1953" needs a
 * sentence with Norgay, Everest and 1953), not only that the words occur somewhere. It is still lexical: a statement
 * that swaps who did what among words of one sentence is not caught.
 */
object Claims {
    enum class Verdict { SUPPORTED, NEUTRAL, UNSUPPORTED }
    data class Result(val verdict: Verdict, val problem: String? = null)

    /** A research answer announcing an action ("I'll save your address"): web text can't start actions. */
    private val ACTION = Regex("(?i)\\bI(?:'ll|\\s+will|\\s+have|'ve|\\s+am going to)\\s+(?:now\\s+)?(?:save|remember|store|call|send|open|delete|set|book|buy|share|forward|email|text)\\b")
    private val NUMBER = Regex("\\d+")
    private val NAME = Regex("(?<![\\p{L}'])\\p{Lu}[\\p{L}'-]{2,}")
    private val TOKEN = Regex("[\\p{L}\\p{N}']+")
    private val RESULTS_FORMAT = Regex("(?i)\\([^()]*;\\s*snippet only\\)|\\bsnippet only\\b|\\b(?:BEGIN|END) WEB RES|\\[(?:Information found|Tool result)")
    private val LIGHT = setOf("company", "firm", "corporation", "organisation", "organization", "business", "role", "position", "post", "job",
        "led", "lead", "leads", "leading", "head", "heads", "headed", "run", "runs", "ran", "running", "serve", "serves", "served", "serving",
        "held", "hold", "holds", "holding", "became", "become", "becomes", "currently", "current", "still", "now", "today", "since")

    fun check(sentence: String, units: List<List<String>>, question: String, subjects: List<String>, sources: List<SourceRef>,
              instructions: String = ""): Result {
        // The results block's own format ("(en.wikipedia.org, updated …; snippet only)", "BEGIN WEB RESULTS") copied into the answer.
        if (RESULTS_FORMAT.containsMatchIn(sentence)) return Result(Verdict.UNSUPPORTED, "it copied the information block instead of answering")
        val clean = AnswerStyle.withoutSourceTalk(sentence).trim()
        if (clean.isEmpty() || TOKEN.findAll(clean).none()) return Result(Verdict.NEUTRAL)
        if (Grounding.echoes(clean, instructions)) return Result(Verdict.UNSUPPORTED, "it repeated my own notes instead of answering")
        if (NonAnswer.matches(clean)) return Result(Verdict.UNSUPPORTED, "it said the answer wasn't known although the information answers it")
        if (ACTION.containsMatchIn(clean)) return Result(Verdict.UNSUPPORTED, "it offered to take an action; web information can't start actions")
        val sites = sources.mapNotNull { Grounding.siteName(it.url) }.toSet() - Grounding.words(question)
        Grounding.words(clean).firstOrNull { it in sites }?.let { return Result(Verdict.UNSUPPORTED, "it named a website ('$it'); answer without sources") }

        val topic = tokens(question + " " + subjects.joinToString(" "))
        val found = names(clean, units)
        val allNames = found.map { it.lowercase().removeSuffix("'s") }.filter { it !in Grounding.FUNCTION }.toSet()
        val names = allNames.filter { !has(topic, it) }.toSet()
        // The subject's own name, when the claim uses it, must be in the result that supports the claim (not borrowed
        // from another result about someone else). An acronym from the question ("CEO") is often spelt out there instead.
        val acronyms = found.filter { it.length <= 5 && it.all { c -> c.isUpperCase() || c.isDigit() } }.map { it.lowercase() }.toSet()
        val subjectNames = allNames - names - acronyms
        val nums = NUMBER.findAll(clean).map { it.value }.filter { it !in topic }.toSet()
        val content = Grounding.words(clean).map { it.removeSuffix("'s") }.filter { it !in allNames && it !in Grounding.FUNCTION && !has(topic, it) }.toSet()
        if (names.isEmpty() && nums.isEmpty() && content.isEmpty()) return Result(Verdict.NEUTRAL)

        val windows = units.flatMap { u -> val ut = tokens(u.joinToString(" "))
            if (subjectNames.any { !has(ut, it) }) emptyList() else u.indices.flatMap { i -> listOfNotNull(u[i], if (i + 1 < u.size) u[i] + " " + u[i + 1] else null) } }
        var best = -1.0
        for (w in windows) {
            val wt = tokens(w)
            if (names.any { !has(wt, it) } || nums.any { it !in wt }) continue
            // Light words that restate a relation the names and numbers already pin down ("has led the company since 2014"
            // for "has held the role since 2014") count as matching when the names and numbers are all in this sentence.
            val hits = content.count { has(wt, it) || (it in LIGHT && (names.isNotEmpty() || nums.isNotEmpty())) }
            if (content.isEmpty() || hits * 2 >= content.size) return Result(Verdict.SUPPORTED)
            best = maxOf(best, hits.toDouble() / content.size)
        }
        // Why not: the most specific reason, for the one correction attempt.
        val everywhere = tokens(units.flatten().joinToString(" "))
        names.firstOrNull { !has(everywhere, it) }?.let { n -> return Result(Verdict.UNSUPPORTED, "the name '${original(clean, n)}' is not in the information") }
        nums.firstOrNull { it !in everywhere }?.let { return Result(Verdict.UNSUPPORTED, "the number $it is not in the information") }
        if (best < 0 && (names.size + nums.size) >= 2)
            return Result(Verdict.UNSUPPORTED, "it connects ${(names.map { original(clean, it) } + nums).take(3).joinToString(", ")} in a way the information doesn't")
        val missing = content.filter { !has(everywhere, it) && it !in LIGHT }.take(3)
        return Result(Verdict.UNSUPPORTED, if (missing.isEmpty()) "it isn't what the information says" else "it says things the information doesn't (${missing.joinToString(", ")})")
    }

    /**
     * The opening of a researched answer (its first sentence or two, shown only after this check): no unsupported
     * claim, and at least one supported one — an answer, not just an introduction.
     */
    fun opening(text: String, units: List<List<String>>, question: String, subjects: List<String>, sources: List<SourceRef>, instructions: String = ""): Result {
        var supported = false
        for (sn in Evidence.sentences(text)) {
            val r = check(sn, units, question, subjects, sources, instructions)
            if (r.verdict == Verdict.UNSUPPORTED) return r
            if (r.verdict == Verdict.SUPPORTED) supported = true
        }
        return if (supported) Result(Verdict.SUPPORTED) else Result(Verdict.UNSUPPORTED, "it didn't answer the question from the information")
    }

    /**
     * Capitalised words that are names. A word capitalised only because it starts the sentence ("See …", "Its …") is a
     * name only when another capitalised word follows it ("Lemmertz Norgay") or the results write it capitalised.
     */
    private fun names(text: String, units: List<List<String>>): List<String> {
        val ms = NAME.findAll(text).toList()
        val evidenceCaps = NAME.findAll(units.flatten().joinToString(" ")).map { it.value.lowercase() }.toSet()
        return ms.filterIndexed { i, m ->
            val sentenceStart = text.substring(0, m.range.first).trimEnd().let { it.isEmpty() || it.last() in ".!?:" }
            !sentenceStart || m.value.all { it.isUpperCase() } || m.value.lowercase() in evidenceCaps ||
                ms.getOrNull(i + 1)?.let { n -> text.substring(m.range.last + 1, n.range.first).isBlank() } == true
        }.map { it.value }
    }

    private fun tokens(t: String) = TOKEN.findAll(t.lowercase()).flatMap { m -> listOf(m.value, m.value.removeSuffix("'s")) + m.value.split('-', '\'') }.toSet()

    /** Present, allowing plurals, possessives and other endings of the same word ("legion"/"legions", "mountaineer"/"mountaineering"). */
    private fun has(set: Set<String>, w: String): Boolean {
        if (w in set || w.removeSuffix("s") in set || (w + "s") in set || w.removeSuffix("es") in set) return true
        if (w.contains('-')) return w.split('-').filter { it.isNotEmpty() }.all { has(set, it) }
        if (w.length < 5) return false
        val stem = w.take(maxOf(5, w.length - 3))
        return set.any { it.length >= 5 && (it.startsWith(stem) || w.startsWith(it.take(maxOf(5, it.length - 3)))) }
    }

    private fun original(text: String, lower: String) = NAME.findAll(text).map { it.value }.firstOrNull { it.lowercase().removeSuffix("'s") == lower } ?: lower
}
