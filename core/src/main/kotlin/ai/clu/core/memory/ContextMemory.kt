package ai.clu.core.memory

import kotlin.math.exp

/**
 * Ordering of memories that already passed the relevance threshold. Relevance (BM25) stays the main signal; among
 * comparably relevant memories, what the owner stated explicitly beats Clu's approved inferences, and recent beats
 * old. Boosts only reorder — they never let an irrelevant memory in.
 */
object MemoryRanking {
    private const val YEAR_MS = 365.0 * 24 * 3_600_000

    fun weight(m: Memory, now: Long): Double {
        val explicit = when (m.source) {
            MemorySource.OWNER_INSTRUCTION, MemorySource.OWNER_SUPPLIED -> 1.15
            MemorySource.IMPORTED, MemorySource.CONVERSATION -> 1.0
            MemorySource.APPROVED_INFERENCE -> 0.9
        }
        val ageYears = ((now - m.updatedAt).coerceAtLeast(0)) / YEAR_MS
        val recency = 0.8 + 0.2 * exp(-ageYears) // 1.0 today, ~0.87 after a year, never below 0.8
        return explicit * recency
    }

    fun rerank(list: List<ScoredMemory>, now: Long): List<ScoredMemory> = list.sortedByDescending { it.score * weight(it.memory, now) }
}

/**
 * Memories that probably contradict or supersede each other: same kind of memory, mostly the same subject, different
 * content (e.g. "prefers tea" then "switched to coffee"; "thesis at chapter 3" then "at chapter 4"). Clu is told about
 * them instead of silently picking one.
 */
object MemoryConflicts {
    // Not plain facts: many facts about one topic are normal and don't supersede each other.
    private val kinds = setOf(MemoryCategory.PREFERENCE, MemoryCategory.DECISION, MemoryCategory.PROJECT, MemoryCategory.INSTRUCTION)

    private fun subject(m: Memory) = TextAnalysis.tokens(m.title + " " + m.bodyForPrompt()).toSet()

    fun find(list: List<Memory>): List<Pair<Memory, Memory>> {
        val out = mutableListOf<Pair<Memory, Memory>>()
        val candidates = list.filter { it.category in kinds }
        for (i in candidates.indices) for (j in i + 1 until candidates.size) {
            val a = candidates[i]; val b = candidates[j]
            if (a.category != b.category || a.bodyForPrompt().trim().equals(b.bodyForPrompt().trim(), ignoreCase = true)) continue
            val sa = subject(a); val sb = subject(b)
            if (sa.isEmpty() || sb.isEmpty()) continue
            val overlap = (sa intersect sb).size.toDouble() / minOf(sa.size, sb.size)
            if (overlap >= 0.6 && (sa intersect sb).size >= 2) out += if (a.updatedAt <= b.updatedAt) a to b else b to a // (older, newer)
        }
        return out.take(2) // a couple of notes at most: the prompt stays compact
    }

    /** One line for the prompt per conflict, oldest first: what to prefer and when to ask. */
    fun note(older: Memory, newer: Memory, date: (Long) -> String): String {
        val prefer = when {
            newer.source == MemorySource.APPROVED_INFERENCE && older.source != MemorySource.APPROVED_INFERENCE ->
                "the older one is the owner's own statement and the newer is your approved inference — prefer the owner's statement"
            else -> "prefer the newer one"
        }
        return "- Possible conflict: \"${older.title.ifBlank { older.bodyForPrompt().take(60) }}\" (${date(older.updatedAt)}) vs " +
            "\"${newer.title.ifBlank { newer.bodyForPrompt().take(60) }}\" (${date(newer.updatedAt)}): $prefer; if it matters for the answer, ask the owner which is current."
    }
}

/**
 * Picking up a project where it was left: when the request is about a project memory (or says "continue / back to /
 * where were we with X"), the decisions, constraints and status recorded for that project come along, compactly.
 */
object ProjectContext {
    private val resume = Regex("(?i)\\b(continue|resume|pick up|back to|where (?:were|was) we|carry on|status of|progress on|update on|next steps? (?:for|on))\\b")

    fun wantsResume(text: String) = resume.containsMatchIn(text)

    /** Related memories for [projects] (decisions, facts, preferences sharing the project's key words), best first. */
    fun related(projects: List<Memory>, rank: (String) -> List<ScoredMemory>, exclude: Set<String>, max: Int): List<ScoredMemory> =
        projects.flatMap { p -> rank(p.title.ifBlank { p.bodyForPrompt().take(80) }) }
            .filter { it.memory.id !in exclude && it.memory.category in setOf(MemoryCategory.DECISION, MemoryCategory.PROJECT, MemoryCategory.CORE_FACT, MemoryCategory.PREFERENCE) }
            .distinctBy { it.memory.id }.sortedByDescending { it.score }.take(max)
}
