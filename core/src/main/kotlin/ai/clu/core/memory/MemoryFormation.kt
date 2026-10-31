package ai.clu.core.memory

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.history.Kind

/**
 * Finds what is worth remembering in something the owner said, and decides what happens to it:
 *
 * * **temporary** details ("I'm at the airport right now", "tomorrow's meeting is at 3") go to working memory, which
 *   expires; they never become persistent memory by themselves;
 * * **stated facts, preferences and procedures** ("my sister's name is Mira", "I prefer tea", "to deploy, first run…")
 *   are handled by the owner's policy (`memory.formation`): off, suggested for approval (default), or saved directly
 *   when stated clearly;
 * * a statement that **repeats** something already known reinforces that memory instead of creating a second one;
 * * a statement that **conflicts** with a memory is never saved over it: it becomes a suggestion that names the
 *   conflict, and both stay until the owner decides;
 * * **inferences** about the owner (insights, habits and patterns such as "I always forget…") are not produced here at
 *   at all; they come from the insight proposer and always need approval.
 *
 * Extraction is rule-based on purpose: it works offline, costs nothing at idle, never calls the model, and only reacts
 * to first-person statements, so it cannot invent anything the owner did not say.
 */
class MemoryFormation(private val settings: SettingsStore, private val service: MemoryService, private val proposals: ProposalRepository) {

    enum class Nature { FACT, PREFERENCE, PROCEDURE, TEMPORARY }
    data class Candidate(val text: String, val nature: Nature, val category: MemoryCategory, val importance: Int, val confidence: Double, val reason: String)

    sealed class Outcome {
        data class Saved(val memory: Memory) : Outcome()
        data class Suggested(val proposal: MemoryProposal, val conflictsWith: Memory? = null) : Outcome()
        data class Working(val item: WorkingMemory.Item) : Outcome()
        data class Reinforced(val memory: Memory) : Outcome()
    }

    fun extract(message: String): List<Candidate> {
        val out = mutableListOf<Candidate>()
        procedure(message)?.let { out += it }
        for (raw in sentences(message)) {
            val s = raw.trim().trimEnd('.', '!', ' ')
            if (s.length < 8 || s.length > 300) continue
            if (s.endsWith("?") || QUESTION.containsMatchIn(s) || HEDGE.containsMatchIn(s)) continue
            if (out.any { it.nature == Nature.PROCEDURE && it.text.contains(s) }) continue
            val first = FIRST_PERSON.containsMatchIn(s)
            if (!first) continue
            if (TEMPORARY.containsMatchIn(s)) { out += Candidate(s, Nature.TEMPORARY, MemoryCategory.TEMPORARY, 0, 0.8, "a detail for now"); continue }
            PREFERENCE.firstOrNull { it.containsMatchIn(s) }?.let {
                out += Candidate(s, Nature.PREFERENCE, MemoryCategory.PREFERENCE, 0, 0.85, "a preference you stated"); return@let
            } ?: FACT.firstOrNull { it.containsMatchIn(s) }?.let {
                if (!NOT_A_FACT.containsMatchIn(s)) out += Candidate(s, Nature.FACT, MemoryCategory.CORE_FACT, if (IMPORTANT.containsMatchIn(s)) 2 else if (RELATION.containsMatchIn(s)) 1 else 0, 0.9, "a fact you stated")
            }
        }
        return out.distinctBy { it.text.lowercase() }.take(4)
    }

    /** Applies the owner's policy to everything found in [message]. Returns what happened, in order. */
    fun process(message: String, conversationId: String?, ownerSpeaking: Boolean = true): List<Outcome> {
        val mode = settings.string(Settings.FORMATION)
        if (!ownerSpeaking || mode == "off" || !settings.bool(Settings.MEMORY_ENABLED)) return emptyList()
        val ref = conversationId?.let { "conversation:$it" } ?: ""
        val results = mutableListOf<Outcome>()
        for (c in extract(message)) {
            if (c.nature == Nature.TEMPORARY) { results += Outcome.Working(service.working.put(c.text, WorkingMemory.Kind.NOTE, conversationId)); continue }
            val similar = service.memories.rank(c.text, scopes = null, limit = 6).map { it.memory }.filter { !it.deleted && it.category != MemoryCategory.INSTRUCTION }
            similar.firstOrNull { sameStatement(it, c.text) }?.let { service.reinforce(it.id); results += Outcome.Reinforced(it); continue }
            val conflict = similar.firstOrNull { conflicts(it, c) }
            val kind = when (c.nature) { Nature.FACT -> ProposalKind.FACT; Nature.PREFERENCE -> ProposalKind.PREFERENCE; else -> ProposalKind.PROCEDURE }
            val direct = mode == "auto" && conflict == null && c.nature != Nature.PROCEDURE
            if (direct) {
                val r = service.remember(Memory(category = c.category, title = c.text.take(60), content = c.text, source = MemorySource.CONVERSATION,
                    importance = c.importance, certainty = Certainty.RECOLLECTION), sourceRef = ref, confidence = c.confidence)
                results += Outcome.Saved(r.memory)
            } else {
                val why = if (conflict != null) "You said: \"${c.text}\". This may conflict with what I have: \"${conflict.bodyForPrompt().take(120)}\". Both stay until you decide."
                    else "You said: \"${c.text}\" (${c.reason}). I only save it if you approve."
                proposals.propose(kind, c.text, why, conversationId, category = c.category, relatedId = conflict?.id, confidence = c.confidence, sourceRef = ref)
                    ?.let { results += Outcome.Suggested(it, conflict) }
            }
        }
        return results
    }

    private fun sentences(text: String) = text.split(Regex("(?<=[.!?])\\s+|\\n+"))

    /** A described procedure: the message, or the part of it from the sentence that opens with "to …" / "the steps for …" / "how to …". */
    private fun procedure(message: String): Candidate? {
        val parts = message.split(Regex("(?<=[.!?:])\\s+|\\n")).filter { it.isNotBlank() }
        val start = parts.indexOfFirst { PROCEDURE_LEAD.containsMatchIn(it.trim()) }
        val t = (if (start >= 0) parts.drop(start).joinToString("\n") else message).trim()
        if (t.length < 25 || t.length > 1500 || t.endsWith("?")) return null
        val numbered = Regex("(?m)^\\s*(?:\\d+[.)]|[-*])\\s+\\S").findAll(t).count()
        val lead = start >= 0
        if (!(lead && (numbered >= 2 || STEPS.containsMatchIn(t))) && !(numbered >= 3 && Regex("(?i)\\b(?:how to|steps?|procedure|process|workflow|whenever)\\b").containsMatchIn(message))) return null
        return Candidate(t.take(1200), Nature.PROCEDURE, MemoryCategory.PROCEDURE, 1, 0.7, "a procedure you described")
    }

    private fun toks(s: String) = TextAnalysis.tokens(s).toSet()

    /** The owner restated what is already remembered (same words, near enough). */
    private fun sameStatement(m: Memory, text: String): Boolean {
        val a = toks(m.title + " " + m.bodyForPrompt()); val b = toks(text)
        if (a.isEmpty() || b.isEmpty()) return false
        val inter = (a intersect b).size.toDouble()
        return inter / (a union b).size >= 0.8 || (b.size >= 3 && inter / b.size >= 0.95)
    }

    /** Same subject, different statement: the same kind of memory, largely the same topic words. */
    private fun conflicts(m: Memory, c: Candidate): Boolean {
        if (m.category != c.category && !(m.category == MemoryCategory.CORE_FACT && c.category == MemoryCategory.CORE_FACT)) return false
        val a = toks(m.title + " " + m.bodyForPrompt()); val b = toks(c.text)
        if (a.isEmpty() || b.isEmpty() || sameStatement(m, c.text)) return false
        val shared = (a intersect b).size
        val overlap = shared.toDouble() / minOf(a.size, b.size)
        return (shared >= 2 && overlap >= 0.5) || (minOf(a.size, b.size) <= 3 && shared >= 1 && overlap >= 0.5)
    }

    companion object {
        private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)
        private val FIRST_PERSON = r("^(?:i|i'm|i’m|i am|i've|i’ve|i have|i'd|my|we|we're|our)\\b")
        private val QUESTION = r("^(?:what|who|when|where|why|how|which|do|does|did|can|could|would|should|is|are|will)\\b")
        private val HEDGE = r("\\b(?:maybe|perhaps|might|i think|i guess|not sure|probably|if i|suppose|hypothetically|what if)\\b")
        private val TEMPORARY = r("\\b(?:right now|at the moment|today|tonight|tomorrow|this (?:morning|afternoon|evening|week|weekend)|later|for now|currently (?:at|in|on|working on)|in a (?:few )?(?:minutes?|hours?))\\b")
        private val PREFERENCE = listOf(
            r("^i (?:really |also |much )?(?:prefer|like|love|enjoy|hate|dislike|can't stand|can’t stand|don't like|don’t like)\\b.{3,}"),
            r("^my (?:favou?rite|least favou?rite) .{2,40} (?:is|are) .{2,}"),
            r("^i(?:'d| would) rather .{3,}"),
        )
        private val FACT = listOf(
            r("^my (?:name|birthday|birth date|date of birth|age|email|e-mail|phone(?: number)?|address|job|occupation|profession|hometown|nationality|surname|nickname|employer|company|city|school|university|degree|major|blood (?:group|type)) is .{2,}"),
            r("^my (?:wife|husband|partner|girlfriend|boyfriend|spouse|son|daughter|sister|brother|mother|father|mom|dad|friend|boss|manager|dog|cat|pet)(?:'s| ’s)? (?:name )?is .{2,}"),
            r("^i (?:live|work|study|grew up|was born|am based|am from)\\b(?: in| at| for| on| as)? .{2,}"),
            r("^i(?:'m| am) an? (?!bit |little |lot |few |bit of ).{3,}"),
            r("^i(?:'m| am) allergic to .{2,}"),
            r("^i have (?:an? |two |three |four |\\d+ )?(?:dog|cat|pet|child|children|kid|kids|sister|brother|car|bike|appointment|exam|deadline|allerg\\w+|condition).{0,60}"),
            r("^(?:we|our) (?:use|run|deploy|host|build with) .{3,}"),
        )
        private val NOT_A_FACT = r("\\b(?:going to|about to|trying to|looking for|want to|need to|have to|have got to)\\b")
        private val IMPORTANT = r("\\b(?:allergic|birthday|date of birth|blood|name is|medication|diagnos)\\b")
        private val RELATION = r("\\b(?:wife|husband|partner|girlfriend|boyfriend|spouse|son|daughter|sister|brother|mother|father|mom|dad)\\b")
        private val PROCEDURE_LEAD = r("^(?:to |when you want to |the (?:way|process|procedure|steps?) (?:to|for) |how to |here(?:'s| is) how |steps? (?:to|for) |whenever )")
        private val STEPS = r("\\b(?:first|then|after that|finally|next)\\b.*\\b(?:then|after that|finally|next)\\b")
    }
}
