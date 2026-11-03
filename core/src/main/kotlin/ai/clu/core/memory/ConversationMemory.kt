package ai.clu.core.memory

import ai.clu.core.agent.MemoryRetriever

/**
 * What the conversation engine (CluAgent) needs from memory, and nothing more: what is relevant to this turn, the
 * conversation's short-lived notes, and what to keep from what the owner just said. CluAgent depends on this interface,
 * not on the storage behind it, so the memory system can grow (episodic and semantic retrieval, autobiographical and
 * prospective memory, consolidation, better ranking) or be replaced without touching the conversation pipeline.
 *
 * [Default] is the memory CLU has today: ranked retrieval over the memory store with access tracking, related and
 * semantically close records (MemoryRetriever + MemoryService), working memory, and the formation pipeline.
 */
interface ConversationMemory {
    /** Records relevant to [query] that the given scopes may read, best first, for this turn's context. */
    fun relevant(query: String, scopes: List<String>): List<ScoredMemory>
    /** Short-lived notes for [conversationId] (working memory), newest first. */
    fun workingNotes(conversationId: String, limit: Int = 5): List<String>
    /** After an owner's turn: facts, preferences and procedures stated in [text] are kept per the owner's memory policy. */
    fun afterTurn(text: String, conversationId: String): List<MemoryFormation.Outcome>

    class Default(private val retriever: MemoryRetriever, private val service: MemoryService?, private val formation: MemoryFormation?) : ConversationMemory {
        override fun relevant(query: String, scopes: List<String>) = retriever.retrieve(query, scopes)
        override fun workingNotes(conversationId: String, limit: Int) = service?.working?.list(conversationId)?.take(limit)?.map { it.text }.orEmpty()
        override fun afterTurn(text: String, conversationId: String) = formation?.process(text, conversationId).orEmpty()
    }
}
