package ai.clu.core.agent

import ai.clu.core.db.CluStore
import ai.clu.core.search.HazardCheck
import ai.clu.core.search.HazardAnswers
import ai.clu.core.search.WeatherNow
import ai.clu.core.util.CluJson
import kotlinx.serialization.Serializable

/**
 * What the conversation is about right now — kept as explicit state, not left to the model to work out from the
 * transcript: the active topic (the question, its subject, entities, what kind of question it was, whether Clu
 * answered it, what research found), the previous topic, and a pending clarification. Temporary conversational context,
 * stored per conversation (it survives errors, model reloads, app restarts and switching between voice and typing)
 * and never turned into a memory.
 */
@Serializable
data class Topic(
    val question: String,
    val subject: String? = null,
    val entities: List<String> = emptyList(),
    /** general | entity | current | hazard | weather */
    val kind: String = "general",
    /** Clu could not answer yet (it deferred, the search failed, or an error happened): "look it up" refers to this. */
    val answered: Boolean = true,
    val hazard: HazardCheck? = null,
    val weather: WeatherNow? = null,
    val turn: Int = 0,
    val at: Long = 0,
)

@Serializable
data class Pending(
    /** correction | ambiguity | location | subject */
    val kind: String,
    /** The request to carry out once the answer is known (for a correction: the corrected text). */
    val question: String,
    val options: List<String> = emptyList(),
    /** For a correction: the request as heard. */
    val literal: String = "",
    val turn: Int = 0,
    val at: Long = 0,
)

@Serializable
data class ConversationState(
    val turn: Int = 0,
    val topic: Topic? = null,
    val previous: Topic? = null,
    val pending: Pending? = null,
    /** A place named in this conversation (e.g. in answer to "which area?"): temporary, not the saved home location. */
    val place: String? = null,
)

/** Persists [ConversationState] per conversation (local only, never synced; removed with the conversation). */
class ConversationStateStore(private val store: CluStore) {
    private fun key(conversationId: String) = "conv_state:$conversationId"
    fun get(conversationId: String): ConversationState =
        store.meta(key(conversationId))?.let { runCatching { CluJson.decodeFromString(ConversationState.serializer(), it) }.getOrNull() } ?: ConversationState()
    fun put(conversationId: String, s: ConversationState) = store.setMeta(key(conversationId), CluJson.encodeToString(ConversationState.serializer(), s))
}

/** How a message relates to the conversation. */
sealed class Resolution {
    /** A request of its own (or one that needs no context). [text] may carry an ASR correction. */
    data class Standalone(val text: String) : Resolution()
    /**
     * Bound to the active topic: [question] is what to look up (or answer), [prompt] what the model is asked (the
     * original question for "look it up", the owner's own words for "which legion did he lead?"); [research] when it
     * must be looked up; [ask] what a follow-up on a hazard wants to know.
     */
    data class FollowUp(val question: String, val topic: Topic, val research: Boolean, val subject: String?, val ask: HazardAnswers.Ask? = null,
                        val compareWith: Topic? = null, val prompt: String = question) : Resolution()
    /** One short question back to the owner; [pending] says what the answer will complete. */
    data class Clarify(val text: String, val pending: Pending) : Resolution()
}

/** The outcome of [FollowUpResolver.resolve]: the resolution and, when speech was re-interpreted, what was heard. */
data class Resolved(val resolution: Resolution, val heard: String, val corrected: String? = null, val placeFromReply: String? = null)

/**
 * Resolves a message against the conversation state before any routing, so "search the web and tell me", "look it up",
 * "tell me more", "is it dangerous?" or "compare it with the previous one" act on what the conversation is about
 * instead of on their own words. Deterministic and model-free. Context is used only when the message is a follow-up
 * (a bare command, a reference such as "it"/"that"/"he", or an answer to Clu's question) and the topic is recent;
 * a request with its own subject never inherits an old one.
 */
object FollowUpResolver {
    const val TOPIC_TURNS = 6
    const val TOPIC_MS = 60 * 60_000L
    private const val PENDING_TURNS = 2

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val WORD = Regex("[\\p{L}\\p{N}']+")

    /** Words a bare request to research is made of ("could you search the web and tell me", "look it up", "check online"). */
    private val BARE = setOf("search", "google", "look", "lookup", "check", "find", "research", "browse", "verify", "confirm", "up", "out",
        "it", "that", "this", "them", "for", "on", "in", "the", "web", "internet", "online", "net", "and", "then", "tell", "let", "me", "know",
        "please", "pls", "can", "could", "would", "will", "you", "go", "ahead", "do", "a", "quick", "now", "again", "ok", "okay", "so", "clu", "just",
        "yes", "yeah", "sure", "try", "what", "say", "says", "show", "us", "to", "is", "there", "anything", "about", "info", "information")
    private val RESEARCH_VERB = setOf("search", "google", "look", "lookup", "check", "find", "research", "browse", "verify", "confirm")
    private val MORE = r("^(?:(?:ok|okay|and|so|please|clu)[ ,]+)*(?:tell me more|more(?: details?| info(?:rmation)?)?|go on|continue|keep going|what else|anything else|elaborate|explain more|say more|and)(?: about (?:it|that|this|him|her|them))?[ .!?]*$")
    private val PRONOUN = r("\\b(?:it|its|it's|he|him|his|she|her|hers|they|them|their|this|that|those|these|there)\\b")
    private val SINGULAR = r("\\b(?:it|its|it's|he|him|his|she|her|this|that)\\b")
    private val PREVIOUS = r("\\b(?:the )?(?:previous|last|earlier|other|first) one\\b|\\bthe one before\\b")
    private val WHAT_ABOUT = r("^(?:and |so )?(?:what|how) about\\s+(.+?)[ ?.!]*$")
    private val YES = r("^(?:yes|yeah|yep|yup|right|correct|exactly|that's right|that one|sure|ok|okay)\\b")
    private val NO = r("^(?:no|nope|nah|not that|wrong|neither)\\b")

    // Hazard follow-up intents (what is being asked about the event).
    private val ASK_LOCATION = r("\\b(?:where|location|position|how far|distance|near(?:by)?|close)\\b")
    private val ASK_ARRIVAL = r("\\b(?:when|arrive|arrival|reach|hit|landfall|make landfall|get here|come here|coming here)\\b")
    private val ASK_DIRECTION = r("\\b(?:direction|heading|which way|moving|track|path|course|towards)\\b")
    private val ASK_DANGER = r("\\b(?:dangerous|danger|strong|severe|serious|safe|risk|worried|threat|intense|intensity|category|bad)\\b")
    private val ASK_COMPARE = r("\\b(?:compare|compared|versus|vs|difference|bigger|stronger|weaker|worse)\\b")

    fun words(t: String) = WORD.findAll(t.lowercase()).map { it.value }.toList()

    fun topicUsable(s: ConversationState, now: Long) = s.topic?.let { s.turn - it.turn <= TOPIC_TURNS && now - it.at <= TOPIC_MS } == true

    /** A bare request to research the current topic: every word is a research/filler word and one of them is a research verb. */
    fun isBareResearch(text: String): Boolean {
        val w = words(text).map { it.removeSuffix("'s") }
        return w.isNotEmpty() && w.size <= 14 && w.all { it in BARE } && w.any { it in RESEARCH_VERB }
    }

    fun isMore(text: String) = MORE.matches(text.trim())

    /**
     * [command]: the message is an explicit command for a tool ("save this conversation to my diary"); it is still
     * checked for a mishearing, but its words ("this", "it") are the command's own, not a follow-up.
     */
    fun resolve(text: String, s: ConversationState, viaVoice: Boolean, now: Long, command: Boolean = false): Resolved {
        val t = text.trim()
        // 1. An answer to Clu's own question.
        s.pending?.takeIf { s.turn - it.turn <= PENDING_TURNS && now - it.at <= 15 * 60_000L }?.let { p -> answerPending(t, p, s, now)?.let { return it } }
        val usable = topicUsable(s, now)
        val topic = s.topic.takeIf { usable }
        // 2. Speech may be misheard: compare it with the active topic before acting on it.
        var heard = t
        var corrected: String? = null
        if (viaVoice && topic != null) when (val fix = ContextualCorrection.find(t, topic)) {
            null -> {}
            else -> if (fix.silent) { corrected = fix.text; heard = fix.text }
                else return Resolved(Resolution.Clarify("Do you mean ${fix.to}?", Pending("correction", fix.text, listOf(fix.to), literal = t, turn = s.turn, at = now)), t)
        }
        val res = if (command) Resolution.Standalone(heard) else resolveText(heard, topic, s)
        return Resolved(res, t, corrected)
    }

    private fun resolveText(t: String, topic: Topic?, s: ConversationState): Resolution {
        // Bare research command: research the open question (or the current topic).
        if (isBareResearch(t)) {
            if (topic == null) return Resolution.Clarify("What would you like me to look up?", Pending("subject", "", turn = s.turn))
            return Resolution.FollowUp(topic.question, topic, research = true, subject = topic.subject, prompt = topic.question)
        }
        if (topic == null) return Resolution.Standalone(t)
        if (isMore(t)) return Resolution.FollowUp(topic.subject?.let { "Tell me about $it" } ?: topic.question, topic, research = topic.kind != "general",
            subject = topic.subject, ask = if (topic.kind == "hazard") HazardAnswers.Ask.MORE else null,
            prompt = topic.subject?.let { "Tell me more about $it." } ?: "Tell me more about that.")
        // A reference: a pronoun, "the previous one", "what about …", or the topic named with "the" ("is the cyclone dangerous?").
        // Named with "the" only when it is the subject itself and the message adds nothing else of its own
        // ("is the cyclone dangerous?" yes; "the latest news about the Pune metro" no).
        val head = words((topic.subject ?: "") + " " + topic.entities.joinToString(" ")).filter { it.length >= 4 }.toSet()
        val known = head + words(topic.question)
        val namesTopic = Regex("(?i)\\bthe\\s+([\\p{L}'-]+)").findAll(t).any { it.groupValues[1].lowercase() in head } &&
            words(t).none { it.length >= 4 && it !in known && it !in ContextualCorrection.COMMON && it !in Grounding.FUNCTION }
        val refers = PRONOUN.containsMatchIn(t) || PREVIOUS.containsMatchIn(t) || WHAT_ABOUT.matches(t) || namesTopic
        // A request with its own subject is its own request, even right after another topic.
        if (!refers || hasOwnSubject(t, topic)) return Resolution.Standalone(t)
        // Comparison with an earlier topic.
        if (PREVIOUS.containsMatchIn(t) || ASK_COMPARE.containsMatchIn(t)) {
            val other = s.previous?.takeIf { it.subject != topic.subject }
            if (topic.kind == "hazard") return Resolution.FollowUp(topic.question, topic, research = false, subject = topic.subject, ask = HazardAnswers.Ask.COMPARE, compareWith = other)
            if (other?.subject != null && topic.subject != null) return Resolution.FollowUp("Compare ${topic.subject} with ${other.subject}", topic, research = false,
                subject = topic.subject, compareWith = other)
        }
        // "it" with two candidates in play: ask once rather than guess.
        if (SINGULAR.containsMatchIn(t) && topic.entities.size >= 2)
            return Resolution.Clarify("Do you mean ${topic.entities.dropLast(1).joinToString(", ")} or ${topic.entities.last()}?",
                Pending("ambiguity", t, topic.entities, turn = s.turn))
        if (topic.kind == "weather") return Resolution.FollowUp(topic.question, topic, research = true, subject = topic.subject, prompt = t)
        if (topic.kind == "hazard") {
            val ask = when {
                ASK_DANGER.containsMatchIn(t) -> HazardAnswers.Ask.DANGER
                ASK_ARRIVAL.containsMatchIn(t) -> HazardAnswers.Ask.ARRIVAL
                ASK_DIRECTION.containsMatchIn(t) -> HazardAnswers.Ask.DIRECTION
                ASK_LOCATION.containsMatchIn(t) -> HazardAnswers.Ask.LOCATION
                else -> HazardAnswers.Ask.MORE
            }
            return Resolution.FollowUp(topic.question, topic, research = false, subject = topic.subject, ask = ask)
        }
        // Bind the reference to the subject: "what about its location?" about X → "X location".
        val subject = topic.subject ?: return Resolution.Standalone(t)
        val extra = ResearchPolicy.contentWords(WHAT_ABOUT.find(t)?.groupValues?.get(1) ?: t)
            .filter { w -> w.lowercase() !in subject.lowercase().split(' ') && !PRONOUN.matches(w) }
        return Resolution.FollowUp((subject + " " + extra.joinToString(" ")).trim(), topic, research = topic.kind != "general", subject = subject, prompt = t)
    }

    /** True when [t] names something of its own (a capitalised name or several content words not about the topic). */
    private fun hasOwnSubject(t: String, topic: Topic): Boolean {
        val topicWords = words(topic.question + " " + (topic.subject ?: "") + " " + topic.entities.joinToString(" ")).toSet()
        val names = Regex("(?<![\\p{L}'])\\p{Lu}[\\p{L}'-]{2,}").findAll(t).map { it.value.lowercase() }.drop(if (t.firstOrNull()?.isUpperCase() == true) 1 else 0)
            .filter { it !in topicWords && it !in Grounding.FUNCTION }.toList()
        return names.isNotEmpty()
    }

    private fun answerPending(t: String, p: Pending, s: ConversationState, now: Long): Resolved? {
        val w = words(t)
        return when (p.kind) {
            "correction" -> when {
                YES.containsMatchIn(t) || p.options.any { o -> words(o).filter { it.length > 2 }.all { it in w } } -> Resolved(resolveText(p.question, s.topic, s), t, corrected = p.question)
                NO.containsMatchIn(t) -> Resolved(resolveText(p.literal, s.topic, s), t)
                else -> null
            }
            "ambiguity" -> p.options.firstOrNull { o -> words(o).any { it.length > 2 && it in w } }?.let { choice ->
                val topic = s.topic ?: return null
                val chosen = topic.copy(subject = choice, entities = listOf(choice))
                // The question asked again with the choice in place of "it": "how big is it?" → "how big is Venus?".
                val asked = SINGULAR.replaceFirst(p.question, choice)
                when (val res = resolveText(p.question, chosen, s.copy(topic = chosen))) {
                    is Resolution.FollowUp -> Resolved(res.copy(prompt = asked), t)
                    else -> Resolved(Resolution.Standalone(asked), t)
                }
            }
            "location" -> ai.clu.core.search.Places.resolve(t)?.let { place ->
                val topic = s.topic ?: Topic(p.question)
                Resolved(Resolution.FollowUp(p.question, topic, research = true, subject = topic.subject, prompt = p.question), t, placeFromReply = place.name)
            }
            // Which of several things with the same name (from a search): research the one chosen.
            "choice" -> p.options.firstOrNull { o -> words(o).filter { it.length > 2 }.any { it in w } }?.let { choice ->
                val topic = s.topic ?: Topic(p.question)
                Resolved(Resolution.FollowUp("Tell me about $choice", topic.copy(subject = choice, entities = listOf(choice)), research = true, subject = choice,
                    prompt = "Tell me about $choice."), t)
            }
            else -> null
        }
    }
}

/**
 * Re-interprets a misheard word using the conversation: a word in a spoken follow-up that the conversation never used,
 * which sounds like a word of the active topic, in a sentence that otherwise continues that topic ("which convoy is
 * heading towards us?" after "which cyclone is heading towards us?"). With strong evidence the correction is applied
 * (and recorded); with partial evidence, or when the word could be a name or the request an action, Clu asks once.
 */
object ContextualCorrection {
    data class Fix(val from: String, val to: String, val text: String, val silent: Boolean)

    private val ACTION = Regex("(?i)\\b(?:call|ring|dial|text|message|send|email|pay|transfer|buy|order|book|delete|remove|erase|unlock|open|turn off|switch off|cancel)\\b")
    internal val COMMON = setOf("what", "which", "where", "when", "will", "would", "could", "should", "there", "their", "about", "tell", "know", "this",
        "that", "with", "from", "have", "does", "doing", "going", "come", "coming", "heading", "towards", "toward", "near", "today", "now", "still",
        "dangerous", "danger", "safe", "arrive", "reach", "much", "many", "more", "very", "really", "please", "think", "look", "search", "find",
        "check", "just", "also", "like", "make", "made", "take", "give", "said", "says", "sure", "okay", "yeah", "right", "time", "here")

    fun find(text: String, topic: Topic): Fix? {
        // Only a specific subject (a name, a storm, a place) is distinctive enough to re-hear words against; ordinary
        // chat ("first question" → "second question") is taken as said.
        val named = topic.entities.any { e -> e.any { it.isUpperCase() } } || Regex("(?<=\\S\\s)\\p{Lu}").containsMatchIn(topic.question)
        if (topic.kind == "general" && !named) return null
        val tokens = FollowUpResolver.words(text)
        if (tokens.size > 14) return null
        val vocab = FollowUpResolver.words(topic.question + " " + (topic.subject ?: "") + " " + topic.entities.joinToString(" "))
        val topicTerms = vocab.filter { it.length >= 4 && it !in COMMON && it !in Grounding.FUNCTION }.distinct()
        if (topicTerms.isEmpty()) return null
        val unknown = tokens.filter { it.length >= 4 && it !in vocab && it !in COMMON && it !in Grounding.FUNCTION }
        var best: Triple<String, String, Double>? = null
        for (u in unknown) for (term in topicTerms) {
            val sim = Phonetics.similarity(u, term)
            if (best == null || sim > best.third) best = Triple(u, term, sim)
        }
        consequential(text, tokens, vocab, topicTerms)?.let { f ->
            val cased = Regex("(?i)\\b${Regex.escape(f.to)}\\b").find(topic.question + " " + (topic.subject ?: ""))?.value ?: f.to
            return f.copy(to = cased, text = f.text.replace(f.to, cased))
        }
        val (from, to, sim) = best ?: return null
        if (sim < 0.6) return null
        // How much of the rest of the sentence continues the topic.
        val rest = tokens.filter { it != from && it.length >= 3 && it !in Grounding.FUNCTION }
        val frame = rest.count { it in vocab }
        val strong = frame >= 2 || (frame >= 1 && sim >= 0.7)
        val continues = unknown.none { it != from } && tokens.size <= 9 // a short follow-up whose only unfamiliar word is this one
        if (!strong && !continues) return null
        // The topic's own spelling: "Leman" stays a name; a common noun gets "the" when offered back ("Do you mean the cyclone?").
        val original = Regex("(?i)\\b${Regex.escape(to)}\\b").find(topic.question + " " + (topic.subject ?: "") + " " + topic.entities.joinToString(" "))?.value ?: to
        val fixed = Regex("\\b${Regex.escape(from)}\\b", RegexOption.IGNORE_CASE).replace(text, original)
        val offer = if (original.first().isUpperCase() && !topic.question.startsWith(original)) original else "the $to"
        // Never silently: an action ("call …", "send …"), or a word written like a name (capitalised mid-sentence).
        val capitalised = Regex.escape(from.replaceFirstChar { it.uppercase() })
        val nameLike = Regex("(?<=\\S\\s)$capitalised\\b").containsMatchIn(text)
        val silent = !ACTION.containsMatchIn(text) && !nameLike && (strong || sim >= 0.7)
        return Fix(from, offer, fixed, silent)
    }

    /** An action ("send lemon a message") whose object sounds just like the topic's name: never acted on as heard, never silently changed. */
    private fun consequential(text: String, tokens: List<String>, vocab: List<String>, terms: List<String>): Fix? {
        if (!ACTION.containsMatchIn(text)) return null
        val hit = tokens.filter { it.length >= 3 && it !in vocab }.flatMap { u -> terms.map { Triple(u, it, Phonetics.similarity(u, it)) } }
            .filter { it.third >= 0.85 }.maxByOrNull { it.third } ?: return null
        val original = Regex("(?i)\\b${Regex.escape(hit.second)}\\b").find(vocab.joinToString(" "))?.value ?: hit.second
        return Fix(hit.first, original, Regex("\\b${Regex.escape(hit.first)}\\b", RegexOption.IGNORE_CASE).replace(text, original), silent = false)
    }
}

/**
 * Rough sound similarity of two English words, for spotting likely mis-hearings: consonant "sound classes" in order
 * (vowels dropped, c/k/q/g alike, s/z/soft c alike, b/p/f/v/w alike, d/t alike, l/r alike, m/n alike), compared by
 * longest common subsequence, plus the first sound and the number of syllables.
 */
object Phonetics {
    fun key(word: String): String {
        val w = word.lowercase().filter { it.isLetter() }
        val sb = StringBuilder()
        var i = 0
        fun push(c: Char) { if (sb.isEmpty() || sb.last() != c) sb.append(c) }
        while (i < w.length) {
            val c = w[i]; val n = w.getOrNull(i + 1)
            when {
                c == 'p' && n == 'h' -> { push('P'); i++ }
                c == 't' && n == 'h' -> { push('T'); i++ }
                c == 'c' && n == 'k' -> { push('K'); i++ }
                c == 'c' && n != null && n in "eiy" -> push('S')
                c == 'g' && n != null && n in "ei" -> push('J')
                c in "ckqg" -> push('K')
                c == 'x' -> { push('K'); push('S') }
                c in "sz" -> push('S')
                c in "bpfvw" -> push('P')
                c in "dt" -> push('T')
                c in "lr" -> push('L')
                c in "mn" -> push('N')
                c == 'j' -> push('J')
                else -> {} // vowels, h, y
            }
            i++
        }
        return sb.toString()
    }

    fun syllables(word: String): Int {
        val w = word.lowercase().filter { it.isLetter() }
        val groups = Regex("[aeiouy]+").findAll(w).count()
        return maxOf(1, groups - if (w.endsWith("e") && groups > 1 && !w.endsWith("le")) 1 else 0)
    }

    private fun lcs(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 1..a.length) for (j in 1..b.length) d[i][j] = if (a[i - 1] == b[j - 1]) d[i - 1][j - 1] + 1 else maxOf(d[i - 1][j], d[i][j - 1])
        return d[a.length][b.length]
    }

    fun similarity(a: String, b: String): Double {
        if (a.equals(b, true)) return 1.0
        val ka = key(a); val kb = key(b)
        if (ka.isEmpty() || kb.isEmpty()) return 0.0
        val skeleton = lcs(ka, kb).toDouble() / maxOf(ka.length, kb.length)
        val first = if (ka[0] == kb[0] || a.first().lowercaseChar() == b.first().lowercaseChar()) 1.0 else 0.0
        val sa = syllables(a); val sb = syllables(b)
        val syl = 1.0 - kotlin.math.abs(sa - sb).toDouble() / maxOf(sa, sb)
        return 0.6 * skeleton + 0.2 * first + 0.2 * syl
    }
}
