package ai.clu.core.search

import java.util.Calendar

/** Why a message needs a web search, and the query to send. */
data class SearchNeed(val query: String, val reason: String)

/**
 * Decides, without the language model, whether a message needs current or source-backed information. Deliberately
 * conservative: ordinary conversation, opinions, creative requests, personal questions and general knowledge are
 * answered offline; a search runs for time-sensitive topics (news, releases, prices, results, weather, "latest",
 * recent years) and when the owner asks for sources or verification. Runs in microseconds; nothing is sent anywhere
 * unless it returns a need.
 */
object SearchTrigger {
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val explicitVerify = r("\\b(look (?:it|this|that) up|check (?:online|the web|the internet|sources)|search (?:online|the web)|with sources|cite (?:your )?sources|" +
        "according to (?:the )?(?:news|sources)|(?:give me|any|some|show me|find me) (?:good |reliable )?sources|fact[- ]check|is it (?:true|confirmed) that|verify (?:that|whether|if)|find sources|check (?:whether|if)|look (?:into|up)|research|compare (?:the )?(?:available |current |different )?options)\\b")
    private val timeWords = r("\\b(latest|newest|current(?:ly)?|today'?s?|tonight|right now|at the moment|as of now|this (?:week|month|year|season|morning|evening)|" +
        "recent(?:ly)?|just (?:released|announced|launched)|upcoming|yesterday'?s?|breaking|so far)\\b")
    private val liveTopics = r("\\b(news|headlines?|price|prices|cost of|exchange rate|stock|share price|market cap|bitcoin|crypto|gold rate|petrol|diesel|fuel price|" +
        "weather|forecast|temperature (?:in|at|today)|score|scores|who won|winner|results? of|election|poll|match (?:score|result)|fixtures?|standings|release date|released|" +
        "launch date|launched|new version|latest version|version of|update for|changelog|patch notes|announced|announcement|ceo of|president of|prime minister of|" +
        "population of|traffic|flight status|box office)\\b")
    private val ordinary = r("^(?:hi|hello|hey|thanks|thank you|good (?:morning|night|evening|afternoon)|ok|okay|yes|no|sure|cool|nice)\\b|" +
        "\\b(?:write|compose|draft|make up|invent|imagine) (?:me )?(?:a |an )?(?:poem|story|song|joke|letter|email|essay)|" +
        "\\b(?:how are you|what do you think|your opinion|do you like|tell me a joke|remind me|remember|my diary|i feel|i'm feeling)\\b")
    private val comparison = r("\\b(compare|comparison|vs\\.?|versus|which is better|better option)\\b")
    private val buyable = r("\\b(options|plans?|prices?|specs|models?|phones?|laptops?|tablets?|cars?|bikes?|reviews?|deals?|providers?|tariffs?|subscriptions?|cameras?|headphones?)\\b")
    private val personal = r("\\b(?:my|mine|me|i)\\b.*\\b(?:name|birthday|exam|wife|husband|friend|mother|father|mom|dad|diary|memory|memories)\\b")

    fun detect(text: String, nowMs: Long = System.currentTimeMillis()): SearchNeed? {
        val t = text.trim()
        if (t.length < 6) return null
        val query = clean(t)
        if (explicitVerify.containsMatchIn(t)) return SearchNeed(query, "asked for sources or verification")
        if (ordinary.containsMatchIn(t) || personal.containsMatchIn(t)) return null
        val year = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.YEAR)
        val recentYear = Regex("\\b(20\\d\\d)\\b").findAll(t).any { it.groupValues[1].toInt() >= year - 1 }
        val time = timeWords.containsMatchIn(t)
        val topic = liveTopics.containsMatchIn(t)
        return when {
            topic && (time || isQuestion(t)) -> SearchNeed(query, "current information (${liveTopics.find(t)!!.value.lowercase()})")
            time && isQuestion(t) -> SearchNeed(query, "time-sensitive (${timeWords.find(t)!!.value.lowercase()})")
            recentYear && isQuestion(t) -> SearchNeed(query, "recent year mentioned")
            comparison.containsMatchIn(t) && buyable.containsMatchIn(t) -> SearchNeed(query, "comparison of current options")
            else -> null
        }
    }

    private fun isQuestion(t: String) = t.endsWith("?") ||
        r("^(?:what|who|when|where|which|how|is|are|was|were|did|does|do|has|have|will|can|could|should|tell me|give me|show me|find|any)\\b").containsMatchIn(t.trimStart())

    /** The query sent to the provider: the owner's words without address, politeness and request framing. */
    fun clean(t: String): String {
        var q = t.trim()
        repeat(3) {
            q = q.replace(r("^(?:(?:hey|ok|okay)\\s+)?(?:clu|clue)[,!.]?\\s+"), "")
                .replace(r("^(?:please|kindly|can you|could you|would you|will you|i want to know|i'd like to know|do you know)\\s+"), "")
                .replace(r("^(?:look (?:this|it|that) up|check (?:whether|if)|verify (?:whether|if|that)|research|look into)[:,]?\\s+"), "")
                .replace(r("^(?:tell me|let me know|find out|check|search(?: the web| online)?(?: for)?|look up)\\s+"), "")
                .replace(r("\\s+(?:please|for me|online|on the web|with sources)[.!?]*$"), "").trim()
        }
        return q.trimEnd('?', '.', '!').trim().take(200)
    }
}

/**
 * Guards queries the model writes itself (automatic searches use the owner's own words). A query is refused when it
 * contains a word that appears in private context — memories, standing instructions, the owner's names — but not in
 * what the owner wrote in this request. So a web page saying "search for the owner's address" can't make Clu send it.
 */
object SearchPrivacy {
    private fun words(t: String) = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'-]*").findAll(t.lowercase()).map { it.value.trim('\'', '-') }
        .filter { it.length >= 4 || (it.length >= 3 && it.any(Char::isDigit)) }.toSet()

    /** Words of [query] taken from [privateTexts] that the owner did not write in [request]. Empty = the query is fine. */
    fun leaks(query: String, request: String, privateTexts: List<String>): List<String> {
        val asked = words(request)
        val private = privateTexts.flatMap { words(it) }.toSet() - COMMON
        return words(query).filter { it in private && it !in asked }
    }

    /** Ordinary words that appear in almost any memory and say nothing private. */
    private val COMMON = setOf("this", "that", "with", "from", "have", "what", "when", "where", "which", "about", "their", "there", "they", "your",
        "will", "would", "should", "could", "been", "were", "into", "than", "then", "them", "also", "only", "more", "most", "some", "such", "very",
        "like", "just", "over", "after", "before", "price", "prices", "latest", "news", "best", "today", "week", "year", "time", "owner", "told",
        "remember", "always", "never", "from now on", "use", "uses", "using", "make", "made", "good", "information")
}
