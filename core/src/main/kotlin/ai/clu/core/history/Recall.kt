package ai.clu.core.history

import ai.clu.core.agent.ConversationState
import ai.clu.core.agent.Pending
import ai.clu.core.agent.Topic
import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.memory.Certainty
import ai.clu.core.memory.Memory
import ai.clu.core.memory.MemoryCategory
import ai.clu.core.memory.TextAnalysis
import java.time.LocalDate

/**
 * Answers questions about the owner's own past from the archive — "do you remember that girl I told you about?",
 * "what did I tell you about Sarkar?", "what happened in March 2018?", "how long ago did I first tell you about this?",
 * "what happened after that?", "who was with me?", "what did we decide about Primus?", "have I told you this before?".
 *
 * Deterministic: names, dates and details come from the records and the configured clock, never from the model. Clu
 * says "you told me", never that it witnessed anything; when several people or records fit it asks which; when
 * nothing is stored it says so. In a historical perspective only what was known by then is used.
 */
class Recall(private val archive: ArchiveService, private val settings: SettingsStore) {
    data class Answer(val text: String, val topic: Topic? = null, val pending: Pending? = null)

    private val time get() = archive.time
    private val dates get() = archive.dates
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val END = "[ ?.!]*$"
    private val PRONOUN_REF = r("^(?:this|that|it|her|him|them|that incident|that time|that day|this story|all that|those)$")
    private val HAVE_TOLD = r("^(?:have|did) i (?:ever |already )?(?:told|tell|mention(?:ed)?|talk(?:ed)? to you about|said) (?:you )?(?:about )?(.+?)(?: before| already)?$END")
    private val HOW_LONG = r("^(?:how long ago|when) (?:did|was it (?:that|when)) i (?:first )?(?:tell|told|mention(?:ed)?|talk(?:ed)? (?:to you )?about) (?:you )?(?:about )?(.+?)$END")
    private val AFTER = r("^(?:and |so |ok |okay )?(?:what happened (?:after that|next|afterwards|after)|then what(?: happened)?|what came next|and then)$END")
    private val BEFORE = r("^(?:and )?what happened before (?:that|it|then)$END")
    private val WHO_WITH = r("^(?:and )?who (?:was|were) (?:with me|there)(?: (?:at the time|then|back then|that day|that time))?$END")
    private val PERIOD = r("^(?:do|can) you (?:remember|recall) (?:what happened|anything|what i did|what i told you) (?:in|on|during|around|back in|from) (.+?)$END|" +
        "^what (?:happened|did i (?:do|tell you about)|was going on)(?: to me| with me| in my life)? (?:in|on|during|around|back in) (.+?)$END")
    private val DESC = r("^(?:do|can|would) you (?:still )?(?:remember|recall) (?:that|the|this|my|our) ([a-z]+(?: [a-z]+)?)(?: (?:i|that i|who i|you|we)\\b.*)?$END")
    private val REMEMBER = r("^(?:do|can|would) you (?:still )?(?:remember|recall) (.+?)$END")
    private val TOLD_ABOUT = r("^what (?:did|have) i (?:ever )?(?:tell|told|say|said|mention(?:ed)?)(?: to)? (?:you )?about (.+?)$END|" +
        "^what do you (?:remember|recall) about (.+?)$END")
    private val WHO_IS = r("^(?:who(?:'s| is| was)|tell me about|what do you know about) (.+?)$END")
    private val DECIDE = r("^what (?:did|have) (?:we|i) decide(?:d)? (?:about|on|for|regarding) (.+?)$END")
    private val PLAN = r("^what was (?:our|my|the) (?:original|first|initial|starting) (?:plan|idea|goal) (?:for|with|of|about) (.+?)$END")
    private val CHANGED = r("^what did (?:we|i) change(?: later| afterwards| after that)?(?: (?:about|in|for|with|on) (.+?))?(?: later)?$END")
    private val ABANDON = r("^(?:which|what) (?:approach(?:es)?|ideas?|plans?|options?|things?) did (?:we|i) (?:abandon|drop|give up(?: on)?|scrap)(?: for| in| with| on| about)?(?: (.+?))?(?:,? and why)?$END")
    private val LAST_DECISION = r("^what was (?:the|our|my) (?:last|latest|most recent) (?:important |big |major |key )?decision (?:we|i) (?:made|took) (?:about|on|for|regarding) (.+?)$END")
    private val GAPS = r("(?:gaps in my (?:history|archive|timeline|memories)|(?:which|what) (?:periods|years|months|times) (?:are|is) missing|where are the gaps)")
    private val DOC = r("^what (?:does|did) (?:my |the )?(?:document|file|notes?|pdf|letter|doc)(?: (?:called|named) (.+?))? say about (.+?)$END|^search my (?:documents|files|archive) for (.+?)$END")
    private val DESCRIPTORS = setOf("girl", "guy", "boy", "woman", "man", "lady", "person", "friend", "colleague", "teacher", "professor", "kid", "neighbour",
        "neighbor", "cousin", "classmate", "roommate", "boss", "mentor", "student", "doctor", "client", "partner", "ex", "crush", "girlfriend", "boyfriend")

    fun enabled() = settings.bool(Settings.RECALL_IN_CHAT) && settings.bool(Settings.MEMORY_RETRIEVAL)

    /**
     * An answer from the archive, or null when the message isn't a question about the owner's own past (it then goes
     * through the normal path). [recent] are the conversation's latest messages (newest last), for "that girl".
     */
    fun answer(text: String, state: ConversationState, recent: List<String>): Answer? {
        if (!enabled()) return null
        val t = text.trim().removePrefix("Clu, ").removePrefix("clu, ").trim()
        state.pending?.takeIf { it.kind == "recall" }?.let { p -> choose(t, p)?.let { id -> return personAnswer(p.question, listOf(id)) } }
        val topic = state.topic?.takeIf { it.kind == "memory" }

        if (AFTER.matches(t)) return topic?.let { next(it, after = true) } ?: noContext()
        if (BEFORE.matches(t)) return topic?.let { next(it, after = false) } ?: noContext()
        if (WHO_WITH.matches(t)) return topic?.let(::whoWith) ?: noContext()
        HOW_LONG.find(t)?.let { m -> return howLong(m.groupValues[1], topic, recent) }
        HAVE_TOLD.find(t)?.let { m -> return haveTold(m.groupValues[1], recent) }
        LAST_DECISION.find(t)?.let { m -> return project(m.groupValues[1], topic, "last") }
        PLAN.find(t)?.let { m -> return project(m.groupValues[1], topic, "plan") }
        DECIDE.find(t)?.let { m -> return project(m.groupValues[1], topic, "decide") }
        CHANGED.find(t)?.let { m -> return project(m.groupValues[1], topic, "change") }
        ABANDON.find(t)?.let { m -> return project(m.groupValues[1], topic, "abandoned") }
        if (GAPS.containsMatchIn(t)) return gaps()
        DOC.find(t)?.let { m -> return documents(m.groupValues[3].ifEmpty { m.groupValues[2] }, m.groupValues[1]) }
        PERIOD.find(t)?.let { m -> val span = m.groupValues[1].ifEmpty { m.groupValues[2] }
            DateParser.parse(span)?.takeIf { it.known }?.let { return period(it) } }
        DESC.find(t)?.let { m -> val words = m.groupValues[1].lowercase().split(' ')
            if (words.last() in DESCRIPTORS || words.first() in DESCRIPTORS) return described(words.first { it in DESCRIPTORS }, t, topic, recent) }
        REMEMBER.find(t)?.let { m -> return subject(m.groupValues[1], t, explicit = true) }
        TOLD_ABOUT.find(t)?.let { m -> return subject(m.groupValues[1].ifEmpty { m.groupValues[2] }, t, explicit = true) }
        // "Who is Riya?" / "tell me about Primus": answered from the archive only when it is someone or something in it.
        WHO_IS.find(t)?.let { m -> return subject(m.groupValues[1], t, explicit = false) }
        return null
    }

    // -------------------------------------------------------------------------------------------- subjects

    private fun clean(s: String) = s.trim().trimEnd('?', '.', '!').removePrefix("about ").removePrefix("the ").removePrefix("my ").trim()

    private fun subject(raw: String, question: String, explicit: Boolean): Answer? {
        val s = clean(raw)
        if (s.isEmpty() || PRONOUN_REF.matches(s)) return null
        val persons = archive.people.resolve(s)
        if (persons.size > 1) return clarify(question, persons)
        if (persons.size == 1) return personAnswer(question, listOf(persons[0].id))
        val projects = archive.projects.resolve(s)
        if (projects.size == 1) return projectSummary(projects[0], question)
        // "Tell me about cricket" is a general question even if a memory mentions cricket; only "what did I tell you
        // about…" / "do you remember…" search the memories themselves.
        if (!explicit) return null
        val found = matching(s)
        if (found.isNotEmpty()) return memoriesAnswer(found, s, question)
        return Answer(if (time.perspective() != null) "As of ${dayLabel(time.today())}, you hadn't told me anything about $s."
            else "I don't have anything stored about $s. If you tell me about it, I can remember it.")
    }

    /** Memories that are clearly about [s] (its words appear in the record), relevance-ranked, visible now. */
    private fun matching(s: String): List<Memory> {
        val words = TextAnalysis.tokens(s); if (words.isEmpty()) return emptyList()
        val ranked = archive.memories.rank(s, scopes = null, limit = 12).map { it.memory }
        return dates.visible(ranked.filter { m -> val have = TextAnalysis.tokens(m.indexText()).toSet(); words.count { it in have } * 2 >= words.size + (if (words.size > 1) 1 else 0) }
            .filter { it.category != MemoryCategory.INSTRUCTION && it.category != MemoryCategory.DIARY })
    }

    /** "That girl I told you about": the person in the current conversation, else the profiles or records fitting the description. */
    private fun described(word: String, question: String, topic: Topic?, recent: List<String>): Answer {
        val inTopic = topic?.personIds.orEmpty().mapNotNull { archive.people.get(it) }
        val inChat = recent.reversed().flatMap { msg -> archive.people.list().filter { p -> p.allNames().any { n -> Regex("(?i)\\b" + Regex.escape(n) + "\\b").containsMatchIn(msg) } } }.distinctBy { it.id }
        val context = (inTopic + inChat).distinctBy { it.id }
        val fits = { p: ArchivePerson -> describes(p, word) }
        val fromContext = context.filter(fits).ifEmpty { if (inTopic.size == 1) inTopic else emptyList() }
        if (fromContext.size == 1) return personAnswer(question, listOf(fromContext[0].id))
        val candidates = (fromContext.ifEmpty { archive.people.list().filter(fits) })
        return when (candidates.size) {
            0 -> Answer("I'm not sure who you mean — I don't have a record of a $word you told me about. Who was it?")
            1 -> personAnswer(question, listOf(candidates[0].id))
            else -> clarify(question, candidates)
        }
    }

    /** Whether the owner's own words describe [p] that way (profile, or a record mentioning them as such). */
    private fun describes(p: ArchivePerson, word: String): Boolean {
        val w = Regex("(?i)\\b${Regex.escape(word)}s?\\b")
        if (w.containsMatchIn(p.relation + " " + p.background + " " + p.notes + " " + p.howWeMet)) return true
        return archive.memoriesOf(p).any { m -> val txt = m.title + " " + m.content
            p.allNames().any { n -> Regex("(?i)\\b${Regex.escape(word)}\\b[^.]{0,40}\\b${Regex.escape(n)}\\b|\\b${Regex.escape(n)}\\b[^.]{0,40}\\b${Regex.escape(word)}\\b").containsMatchIn(txt) } }
    }

    private fun clarify(question: String, people: List<ArchivePerson>): Answer {
        val shown = people.take(4)
        val labels = shown.map { p -> p.name + (p.relation.takeIf { it.isNotBlank() }?.let { " (your $it)" } ?: "") }
        val ask = "Do you mean " + if (labels.size == 2) "${labels[0]} or ${labels[1]}?" else labels.dropLast(1).joinToString(", ") + ", or ${labels.last()}?"
        return Answer(ask, pending = Pending("recall", question, labels, ids = shown.map { it.id }))
    }

    private fun choose(reply: String, p: Pending): String? {
        val w = TextAnalysis.tokens(reply).toSet()
        val ordinal = listOf("first", "second", "third", "fourth").indexOfFirst { Regex("(?i)\\b$it\\b").containsMatchIn(reply) }
        if (ordinal in p.ids.indices) return p.ids[ordinal]
        val hits = p.options.indices.filter { i -> TextAnalysis.tokens(p.options[i]).any { it in w } }
        return hits.singleOrNull()?.let { p.ids[it] }
    }

    // -------------------------------------------------------------------------------------------- answers

    private fun personAnswer(question: String, ids: List<String>): Answer {
        val p = archive.people.get(ids.first()) ?: return Answer("I can't find that person in your archive any more.")
        val ms = dates.visible(archive.memoriesOf(p))
        val today = time.today()
        val sb = StringBuilder("Yes, I remember ${p.name}.")
        val told = ms.mapNotNull { dates.told(it) }.minByOrNull { it.startDate()!! }
        if (told != null) sb.append(" You first told me about ${p.name} ${TimePhrases.whenPhrase(told, today)}").append(TimePhrases.ago(told, today)?.let { " — that's $it." } ?: ".")
        if (p.relation.isNotBlank()) sb.append(" You described ${p.name} as your ${p.relation}.")
        if (ms.isEmpty()) {
            if (time.perspective() != null) return Answer("As of ${dayLabel(today)}, you hadn't told me about ${p.name} yet.", topicFor(question, p.name, emptyList(), listOf(p)))
            sb.append(" ${p.name} is in your People catalog, but there are no memories about them yet.")
        }
        facts(ms.take(3), sb)
        if (ms.size > 3) sb.append(" There ${if (ms.size - 3 == 1) "is one more record" else "are ${ms.size - 3} more records"} about ${p.name} in your archive.")
        conflictNote(ms, sb)
        documentNote(p.allNames().first(), sb)
        // The conversation now focuses on the first of these events ("who was with me then?", "what happened after that?").
        return Answer(sb.toString().trim(), topicFor(question, p.name, ms.take(1), listOf(p)))
    }

    private fun memoriesAnswer(ms: List<Memory>, s: String, question: String): Answer {
        val list = archive.chronological(ms).take(4)
        val today = time.today()
        val told = list.mapNotNull { dates.told(it) }.minByOrNull { it.startDate()!! }
        val sb = StringBuilder("Here's what you told me about $s.")
        if (told != null) sb.append(" You first mentioned it ${TimePhrases.whenPhrase(told, today)}").append(TimePhrases.ago(told, today)?.let { " — $it." } ?: ".")
        facts(list, sb)
        conflictNote(list, sb)
        documentNote(s, sb)
        val persons = list.flatMap { m -> archive.links.linked(Kind.MEMORY, m.id, Kind.PERSON) }.distinct().mapNotNull { archive.people.get(it) }
        return Answer(sb.toString().trim(), topicFor(question, s, list, persons))
    }

    private fun period(d: HistoricalDate): Answer {
        val today = time.today()
        val from = d.startDate()!!; val to = d.endDate()!!
        if (time.perspective() != null && from.isAfter(today)) return Answer("That's after ${dayLabel(today)} — in this historical perspective I don't know about it yet.")
        val ms = dates.visible(archive.memories.inPeriod(from, to, scope = null)).filter { it.category != MemoryCategory.INSTRUCTION && it.category != MemoryCategory.DIARY }
        val label = d.label()
        if (ms.isEmpty()) return Answer("I don't have anything recorded for $label. That's a gap in what you've told me, not proof that nothing happened.")
        val sb = StringBuilder("Here's what you told me about $label:")
        facts(ms.take(5), sb)
        sb.append(if (ms.size > 5) " That's five of ${ms.size} records from then; the timeline in Memory shows them all." else " That's everything I have for that period.")
        conflictNote(ms, sb)
        val persons = ms.take(5).flatMap { m -> archive.links.linked(Kind.MEMORY, m.id, Kind.PERSON) }.distinct().mapNotNull { archive.people.get(it) }
        return Answer(sb.toString().trim(), topicFor("what happened in $label", label, ms.take(5), persons))
    }

    private fun next(topic: Topic, after: Boolean): Answer {
        val current = topic.memoryIds.mapNotNull { archive.memories.get(it) }.filter { !it.deleted }
        val ref = (if (after) current.maxByOrNull { it.historicalDate().start ?: "" } else current.minByOrNull { it.historicalDate().start ?: "9999" })
            ?: return Answer("I'm not sure which memory you mean — what should I look at?")
        val refDate = ref.historicalDate().takeIf { it.known } ?: return Answer("That record has no date, so I can't tell what came ${if (after) "after" else "before"} it.")
        val related = (topic.personIds.mapNotNull { archive.people.get(it) }.flatMap { archive.memoriesOf(it) } +
            topic.projectIds.mapNotNull { archive.projects.get(it) }.flatMap { archive.memoriesOf(it) } +
            archive.links.of(Kind.MEMORY, ref.id).filter { it.relation in setOf(Relation.SAME_STORY, Relation.RELATED, Relation.SAME_EVENT) }
                .mapNotNull { l -> archive.memories.get(if (l.aId == ref.id) l.bId else l.aId) })
        val pool = dates.visible(related.filter { !it.deleted && it.id != ref.id && it.historicalDate().known }.distinctBy { it.id })
        val pick = if (after) pool.filter { it.historicalDate().startDate()!!.isAfter(refDate.endDate()!!) }.minByOrNull { it.historicalDate().start!! }
            else pool.filter { it.historicalDate().endDate()!!.isBefore(refDate.startDate()!!) }.maxByOrNull { it.historicalDate().start!! }
        val about = topic.subject ?: "that"
        if (pick == null) return Answer("That's the ${if (after) "last" else "earliest"} thing I have recorded about $about${if (after) " after that" else ""}.", topic)
        val sb = StringBuilder(factSentence(pick, time.today(), lead = false, connector = if (after) "After that" else "Before that"))
        conflictNote(listOf(pick), sb)
        return Answer(sb.toString().trim(), topic.copy(memoryIds = listOf(pick.id)))
    }

    private fun whoWith(topic: Topic): Answer {
        val m = topic.memoryIds.mapNotNull { archive.memories.get(it) }.lastOrNull() ?: return noContext()
        val owner = settings.string(Settings.OWNER_NAME)
        val names = m.people.filter { it.isNotBlank() && !it.equals(owner, true) && !it.equals("me", true) }
        val where = m.places.takeIf { it.isNotEmpty() }?.let { " It was in ${it.joinToString(" and ")}." } ?: ""
        if (names.isEmpty()) return Answer("That record doesn't say who was with you.$where", topic)
        val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
        return Answer("From what you told me, $list ${if (names.size == 1) "was" else "were"} there.$where", topic)
    }

    private fun howLong(raw: String, topic: Topic?, recent: List<String>): Answer {
        val s = clean(raw)
        val today = time.today()
        val (label, ms) = if (PRONOUN_REF.matches(s) || s.isEmpty()) {
            topic ?: return noContext()
            (topic.subject ?: "that") to (topic.personIds.mapNotNull { archive.people.get(it) }.flatMap { archive.memoriesOf(it) } + topic.memoryIds.mapNotNull { archive.memories.get(it) })
        } else {
            val persons = archive.people.resolve(s)
            if (persons.size > 1) return clarify("how long ago did I first tell you about $s", persons)
            (persons.firstOrNull()?.name ?: s) to (persons.firstOrNull()?.let { archive.memoriesOf(it) } ?: matching(s))
        }
        val visible = dates.visible(ms.filter { !it.deleted }.distinctBy { it.id })
        if (visible.isEmpty()) return Answer("I don't have anything stored about $label, so you haven't told me about it — at least not in a way I recorded.")
        val told = visible.mapNotNull { dates.told(it) }.minByOrNull { it.startDate()!! }
        if (told == null) {
            val earliest = visible.mapNotNull { it.historicalDate().takeIf { d -> d.known } }.minByOrNull { it.startDate()!! }
            return Answer("I don't have a record of when you first told me about $label." + (earliest?.let { " The earliest event you recorded about it is from ${it.label()}." } ?: ""))
        }
        val ago = TimePhrases.ago(told, today)
        return Answer("You first told me about $label ${TimePhrases.whenPhrase(told, today)}" + (ago?.let { " — that's $it." } ?: "."),
            topic ?: topicFor("how long ago", label, visible.take(3), emptyList()))
    }

    private fun haveTold(raw: String, recent: List<String>): Answer {
        val s = clean(raw)
        val text = if (PRONOUN_REF.matches(s) || s.isEmpty()) recent.dropLast(1).lastOrNull { !it.trim().endsWith("?") } ?: recent.dropLast(1).lastOrNull() else s
        if (text.isNullOrBlank()) return Answer("I'm not sure what you mean by \"$s\". What should I check?")
        val persons = archive.people.resolve(text)
        if (persons.size == 1 && (s.isNotEmpty() && !PRONOUN_REF.matches(s))) {
            val ms = dates.visible(archive.memoriesOf(persons[0]))
            if (ms.isNotEmpty()) return personAnswer("have I told you about ${persons[0].name}", listOf(persons[0].id)).let { a -> a.copy(text = "Yes. " + a.text.removePrefix("Yes, I remember ${persons[0].name}. ")) }
        }
        val words = TextAnalysis.tokens(text).toSet()
        val best = archive.memories.rank(text, scopes = null, limit = 8).map { it.memory }.filter { m ->
            val have = TextAnalysis.tokens(m.indexText()).toSet(); words.isNotEmpty() && (words intersect have).size.toDouble() / words.size >= 0.5 }
        val visible = dates.visible(best)
        if (visible.isEmpty()) return Answer("No, I don't have that in your archive. If you'd like me to remember it, just say so.")
        val m = visible.first(); val told = dates.told(m)
        val sb = StringBuilder("Yes — you told me about this")
        sb.append(told?.let { " " + TimePhrases.whenPhrase(it, time.today()) } ?: "").append(". ")
        sb.append(factSentence(m, time.today(), lead = true))
        return Answer(sb.toString().trim(), topicFor("have I told you", m.title.ifBlank { "that" }, listOf(m), emptyList()))
    }

    private fun projectSummary(p: Project, question: String): Answer {
        val ms = dates.visible(archive.memoriesOf(p))
        val sb = StringBuilder("${p.name}" + (p.description.takeIf { it.isNotBlank() }?.let { ": ${it.trim().trimEnd('.')}." } ?: "."))
        if (p.started.known) sb.append(" You started it ${TimePhrases.whenPhrase(p.started, time.today())}.")
        if (p.status != "active") sb.append(" Its status is ${p.status}.")
        facts(ms.takeLast(3), sb)
        if (ms.isEmpty()) sb.append(" There are no memories in its history yet.")
        return Answer(sb.toString().trim(), Topic(question, subject = p.name, entities = listOf(p.name), kind = "memory", memoryIds = ms.takeLast(3).map { it.id }, projectIds = listOf(p.id)))
    }

    private fun project(raw: String, topic: Topic?, what: String): Answer {
        val s = clean(raw)
        val p = if (s.isEmpty() || PRONOUN_REF.matches(s)) topic?.projectIds?.firstOrNull()?.let { archive.projects.get(it) }
            else archive.projects.resolve(s).let { found -> if (found.size == 1) found[0] else if (found.size > 1) return Answer("Do you mean " + found.joinToString(" or ") { it.name } + "?") else Project(id = "", name = s) }
        p ?: return Answer("Which project do you mean?")
        val ms = dates.visible(archive.memoriesOf(p))
        val today = time.today()
        fun kind(m: Memory, k: String) = m.threadKind == k || (k == "decision" && m.threadKind.isEmpty() && m.category == MemoryCategory.DECISION) ||
            (k == "plan" && m.threadKind.isEmpty() && Regex("(?i)\\b(?:plan|planned|idea was|goal)\\b").containsMatchIn(m.title + " " + m.content))
        val incomplete = " That's what's recorded about ${p.name}; the archive may not be complete."
        val topicOut = { list: List<Memory> -> Topic("project ${p.name}", subject = p.name, entities = listOf(p.name), kind = "memory", memoryIds = list.map { it.id },
            projectIds = listOfNotNull(p.id.takeIf { it.isNotEmpty() })) }
        if (ms.isEmpty()) return Answer("I don't have anything recorded about ${p.name}.")
        return when (what) {
            "plan" -> {
                val plan = ms.firstOrNull { kind(it, "plan") } ?: return Answer("I don't have the original plan for ${p.name} recorded. The earliest record is: " + factSentence(ms.first(), today, true), topicOut(listOf(ms.first())))
                val changes = ms.count { kind(it, "change") || kind(it, "abandoned") }
                Answer("The original plan for ${p.name}, as you recorded it: " + factSentence(plan, today, true) + (if (changes > 0) " It changed later ($changes recorded change${if (changes > 1) "s" else ""})." else ""), topicOut(listOf(plan)))
            }
            "change" -> {
                val list = ms.filter { kind(it, "change") }
                if (list.isEmpty()) Answer("I don't have any recorded changes to ${p.name}.$incomplete", topicOut(emptyList()))
                else Answer(StringBuilder("What changed in ${p.name}:").also { facts(list.take(4), it) }.append(incomplete).toString(), topicOut(list.take(4)))
            }
            "abandoned" -> {
                val list = ms.filter { kind(it, "abandoned") }
                if (list.isEmpty()) Answer("I don't have any abandoned approaches recorded for ${p.name}.$incomplete", topicOut(emptyList()))
                else Answer(StringBuilder("What you abandoned in ${p.name}:").also { facts(list.take(4), it) }.append(incomplete).toString(), topicOut(list.take(4)))
            }
            "last" -> {
                val decisions = ms.filter { kind(it, "decision") }
                val important = decisions.filter { it.importance >= 1 || it.pinned }
                val pick = (important.ifEmpty { decisions }).lastOrNull() ?: return Answer("I don't have any decisions about ${p.name} recorded.$incomplete")
                Answer("The last${if (important.isNotEmpty()) " important" else ""} decision about ${p.name}: " + factSentence(pick, today, true) +
                    (if (important.isEmpty()) " (None of the decisions is marked important, so this is simply the latest.)" else ""), topicOut(listOf(pick)))
            }
            else -> {
                val list = ms.filter { kind(it, "decision") }
                if (list.isEmpty()) Answer("I don't have any decisions about ${p.name} recorded.$incomplete", topicOut(emptyList()))
                else Answer(StringBuilder("What you decided about ${p.name}:").also { facts(list.takeLast(4), it) }.append(incomplete).toString(), topicOut(list.takeLast(4)))
            }
        }
    }

    private fun gaps(): Answer {
        val g = archive.gaps()
        if (g.isEmpty()) return Answer("There are no gaps of three months or more in your timeline since ${dayLabel(time.timelineStart())}.")
        val shown = g.take(5).joinToString("; ") { it.label() + if (it.yearLevelRecords > 0) " (some records give only the year)" else "" }
        return Answer("Your archive has no records for: $shown${if (g.size > 5) "; and ${g.size - 5} more" else ""}. Those are gaps in what you've recorded, not proof that nothing happened.")
    }

    private fun documents(query: String, docName: String): Answer {
        val q = clean(query); if (q.isEmpty()) return Answer("What should I look for in your documents?")
        val only = docName.takeIf { it.isNotBlank() }?.let { n -> archive.documents.list().firstOrNull { Names.score(n, listOf(it.name, it.name.substringBeforeLast('.'))) >= 1 || it.name.contains(n, true) } }
        if (docName.isNotBlank() && only == null) return Answer("I can't find a document called \"$docName\" in your archive.")
        val hits = archive.documents.search(q, limit = 2, onlyDoc = only?.id)
        if (hits.isEmpty()) return Answer("I didn't find anything about $q in your documents.")
        val sb = StringBuilder()
        hits.forEach { h -> sb.append("Your document \"${h.document.name}\" says: \"${excerpt(h.text, q)}\" ") }
        sb.append("That's what the document says; I haven't verified it.")
        return Answer(sb.toString().trim())
    }

    private fun noContext() = Answer("I'm not sure which memory you mean — tell me who or what, and I'll check.")

    // -------------------------------------------------------------------------------------------- wording

    private fun facts(ms: List<Memory>, sb: StringBuilder) {
        val today = time.today()
        ms.forEachIndexed { i, m -> sb.append(" ").append(factSentence(m, today, lead = i == 0)) }
    }

    /** "Back in March 2018, you met Riya at the college fest." From the record; first person turned into "you". */
    fun factSentence(m: Memory, today: LocalDate, lead: Boolean, connector: String? = null): String {
        val d = m.historicalDate()
        val body = (m.content.takeIf { it.isNotBlank() }?.let(::firstSentence) ?: m.summary?.takeIf { it.isNotBlank() }?.let(::firstSentence) ?: m.title).trim().trimEnd('.', '!', ' ')
        val text = toSecondPerson(body)
        val sentence = when {
            connector != null && d.known -> "$connector, ${TimePhrases.whenPhrase(d, today).removePrefix("back ")}, " + lowerFirst(text, m)
            connector != null -> "$connector, " + lowerFirst(text, m)
            d.known -> TimePhrases.whenPhrase(d, today).replaceFirstChar { it.uppercase() } + ", " + lowerFirst(text, m)
            else -> (if (lead) "You told me: " else "You also told me: ") + text
        }
        val note = when { m.certainty == Certainty.UNCERTAIN -> " (you weren't sure about this)"; m.significance.isNotBlank() -> " (${m.significance.trim().trimEnd('.')})"; else -> "" }
        return "$sentence$note."
    }

    private fun firstSentence(s: String) = s.replace(Regex("\\s+"), " ").trim().let { t -> Regex("^(.{20,220}?[.!?])(\\s|$)").find(t)?.groupValues?.get(1) ?: t.take(220) }

    /** Lower-cases the first word unless it is a name (a person, project or place in the archive) or written in capitals. */
    private fun lowerFirst(s: String, m: Memory): String {
        val first = s.substringBefore(' ').trim(',', ';', ':')
        val names = (m.people + m.places + archive.people.list().flatMap { it.allNames() } + archive.projects.list().flatMap { it.allNames() })
            .flatMap { it.split(' ') }.map { it.lowercase() }.toSet()
        val keep = first.length > 1 && (first.lowercase() in names || first.drop(1).any { it.isUpperCase() } || first == first.uppercase())
        return if (keep) s else s.replaceFirstChar { it.lowercase() }
    }

    private val SWAPS = listOf("\\bI am\\b" to "you were", "\\bI'm\\b" to "you're", "\\bI was\\b" to "you were", "\\bI've\\b" to "you've", "\\bI'd\\b" to "you'd",
        "\\bI'll\\b" to "you'll", "\\bI\\b" to "you", "\\bmyself\\b" to "yourself", "\\bmine\\b" to "yours", "\\bmy\\b" to "your", "\\bme\\b" to "you",
        "\\bMy\\b" to "Your", "\\bMe\\b" to "You")

    /** The owner's first-person account as Clu says it back ("I met Riya" → "you met Riya"). */
    fun toSecondPerson(s: String): String = SWAPS.fold(s) { acc, (re, rep) -> Regex(re).replace(acc, rep) }

    private fun conflictNote(ms: List<Memory>, sb: StringBuilder) {
        val c = ms.asSequence().flatMap { archive.conflicts.involving(it.id).asSequence() }.firstOrNull() ?: return
        sb.append(" Two of your records disagree about ${if (c.field == "date") "when that was" else c.field} (${c.aValue} vs ${c.bValue}); I've kept both for you to check.")
    }

    private fun documentNote(s: String, sb: StringBuilder) {
        val hit = archive.documents.search(s, limit = 1).firstOrNull()?.takeIf { h -> s.split(' ').all { w -> h.text.contains(w, true) } } ?: return
        sb.append(" Your document \"${hit.document.name}\" also mentions it: \"${excerpt(hit.text, s)}\" (that's what the document says, not something I've verified).")
    }

    private fun excerpt(text: String, q: String): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        val sentences = t.split(Regex("(?<=[.!?])\\s+"))
        val words = TextAnalysis.tokens(q).toSet()
        val best = sentences.maxByOrNull { s -> TextAnalysis.tokens(s).count { it in words } } ?: t
        return best.take(240).trim()
    }

    private fun topicFor(question: String, subject: String, ms: List<Memory>, people: List<ArchivePerson>) = Topic(question, subject = subject,
        entities = people.map { it.name }.ifEmpty { listOf(subject) }.take(3), kind = "memory", memoryIds = ms.map { it.id }, personIds = people.map { it.id },
        projectIds = ms.flatMap { m -> archive.links.linked(Kind.MEMORY, m.id, Kind.PROJECT) }.distinct())

    private fun dayLabel(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} ${d.year}"
}
