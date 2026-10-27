package ai.clu.core.memory

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.queryOne
import ai.clu.core.db.CluStore
import ai.clu.core.db.SqlRow
import ai.clu.core.db.bool
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.security.MessageDigest
import kotlin.math.ln

enum class MemoryCategory(val label: String) {
    CORE_FACT("Core fact"),
    EXPERIENCE("Relationship / experience"),
    /** Legacy: diary entries live in the diary since 1.4 (migrated); kept so old rows and synced peers still parse. */
    DIARY("Diary entry (legacy)"),
    TEMPORARY("Temporary context"),
    INSIGHT("Approved insight"),
    MANUAL("Manually inserted"),
    PREFERENCE("Preference"),
    PROJECT("Project / status"),
    /** How Clu should behave ("from now on…"): always part of Clu's instructions, not retrieved by relevance. */
    INSTRUCTION("Instruction for Clu"),
    DECISION("Decision / commitment");

    companion object {
        /** Categories the owner can pick for a memory (the diary is separate). */
        val selectable get() = values().filter { it != DIARY }

        /** Category for something the owner explicitly asked Clu to remember, from its wording. */
        fun classify(text: String): MemoryCategory {
            val t = text.trim().lowercase()
            return when {
                Regex("^(?:from now on|always|never|don't|do not|please (?:always|never)|whenever i|when i ask|you should|stop )").containsMatchIn(t) -> INSTRUCTION
                Regex("\\b(?:i (?:have )?decided|we (?:have )?decided|decision|i(?:'ll| will) |i promised|i committed|commitment|we agreed|i agreed)\\b").containsMatchIn(t) -> DECISION
                Regex("\\b(?:project|working on|milestone|deadline|status of|progress on|sprint|launch of)\\b").containsMatchIn(t) -> PROJECT
                Regex("\\b(?:i prefer|i like|i love|i hate|i don't like|i dislike|my favou?rite|i'd rather|i enjoy)\\b").containsMatchIn(t) -> PREFERENCE
                else -> CORE_FACT
            }
        }
    }
}

/** Where a memory came from. Clu must not claim to have witnessed OWNER_SUPPLIED events. */
enum class MemorySource(val label: String) {
    OWNER_SUPPLIED("Supplied by the owner"),
    OWNER_INSTRUCTION("Owner asked Clu to remember"),
    CONVERSATION("From a conversation with Clu"),
    APPROVED_INFERENCE("Inferred by Clu, approved by the owner"),
    IMPORTED("Imported from a file"),
}

enum class RetainMode { FULL, SUMMARY, BOTH }

/** Memory scope: "clu" for Clu's own memory, or a character id for character-private memory. */
const val CLU_SCOPE = "clu"

@Serializable
data class Memory(
    val id: String = newId(),
    val category: MemoryCategory,
    val title: String,
    /** Original text exactly as supplied (never replaced by a summary). Empty only if retainMode == SUMMARY. */
    val content: String,
    val summary: String? = null,
    val retainMode: RetainMode = RetainMode.FULL,
    /** Historical date of the event (epoch ms), if known. */
    val eventAt: Long? = null,
    val eventHasTime: Boolean = false,
    val source: MemorySource,
    val people: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
    val scope: String = CLU_SCOPE,
    val expiresAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
    val hlc: String = "",
    val origin: String = "",
) {
    /** Text used for retrieval and prompts, honouring the retention choice. */
    fun bodyForPrompt(): String = when (retainMode) {
        RetainMode.SUMMARY -> summary ?: content
        RetainMode.FULL -> content
        RetainMode.BOTH -> summary?.takeIf { it.isNotBlank() } ?: content
    }

    fun indexText(): String = listOf(title, content, summary ?: "", people.joinToString(" "), tags.joinToString(" ")).joinToString(" ")
}

class MemoryDisabledException : IllegalStateException("Persistent memory is disabled in settings")

data class ScoredMemory(val memory: Memory, val score: Double)

/**
 * Long-term memory store with a BM25 index. Conversation history is stored separately (ConversationRepository);
 * memories are derived/curated records.
 */
class MemoryRepository(private val store: CluStore, private val settings: SettingsStore) {
    private val db = store.db

    private fun ensureEnabled() { if (!settings.bool(Settings.MEMORY_ENABLED)) throw MemoryDisabledException() }

    /** Inserts or updates a memory. Throws [MemoryDisabledException] when persistent memory is off. */
    fun save(m: Memory): Memory {
        ensureEnabled()
        require(m.title.isNotBlank() || m.content.isNotBlank()) { "memory needs a title or content" }
        require(m.retainMode != RetainMode.SUMMARY || !m.summary.isNullOrBlank()) { "summary-only memories need a summary" }
        val now = store.clock.now()
        val expires = if (m.category == MemoryCategory.TEMPORARY && m.expiresAt == null)
            now + settings.int(Settings.TEMP_RETENTION) * 86_400_000L else m.expiresAt
        // A summary-only memory keeps no original text by the owner's explicit choice.
        val content = if (m.retainMode == RetainMode.SUMMARY) "" else m.content
        val existing = get(m.id)
        val saved = db.transaction {
            val hlc = store.stamp(TABLE, m.id)
            val row = m.copy(content = content, expiresAt = expires, createdAt = existing?.createdAt ?: (if (m.createdAt > 0) m.createdAt else now),
                updatedAt = now, hlc = hlc, origin = store.deviceId, deleted = false)
            write(row)
            row
        }
        store.notifyChanged(TABLE)
        return saved
    }

    private fun write(m: Memory) {
        db.execute(
            """INSERT INTO memories(id, category, title, content, summary, retain_mode, event_at, event_has_time, source, people, tags,
               pinned, scope, expires_at, created_at, updated_at, deleted, hlc, origin) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET category=excluded.category, title=excluded.title, content=excluded.content, summary=excluded.summary,
               retain_mode=excluded.retain_mode, event_at=excluded.event_at, event_has_time=excluded.event_has_time, source=excluded.source,
               people=excluded.people, tags=excluded.tags, pinned=excluded.pinned, scope=excluded.scope, expires_at=excluded.expires_at,
               created_at=excluded.created_at, updated_at=excluded.updated_at, deleted=excluded.deleted, hlc=excluded.hlc, origin=excluded.origin""",
            listOf(m.id, m.category.name, m.title, m.content, m.summary, m.retainMode.name, m.eventAt, m.eventHasTime, m.source.name,
                CluJson.encodeToString(ListSerializer(String.serializer()), m.people), CluJson.encodeToString(ListSerializer(String.serializer()), m.tags),
                m.pinned, m.scope, m.expiresAt, m.createdAt, m.updatedAt, m.deleted, m.hlc, m.origin)
        )
        reindex(m)
    }

    private fun reindex(m: Memory) {
        db.execute("DELETE FROM memory_terms WHERE memory_id = ?", listOf(m.id))
        if (m.deleted) return
        val tf = TextAnalysis.termFrequencies(m.indexText())
        for ((term, n) in tf) db.execute("INSERT INTO memory_terms(memory_id, term, tf) VALUES(?,?,?)", listOf(m.id, term, n))
        db.execute("INSERT INTO memory_stats(key, value) VALUES('len:' || ?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            listOf(m.id, tf.values.sum().toDouble()))
    }

    /** Soft delete (tombstone, so the deletion synchronises). Deleting is an owner action and works even if memory is off. */
    fun delete(id: String) {
        val m = get(id) ?: return
        db.transaction {
            val hlc = store.stamp(TABLE, id)
            write(m.copy(deleted = true, content = "", summary = null, updatedAt = store.clock.now(), hlc = hlc, origin = store.deviceId))
            db.execute("DELETE FROM memory_stats WHERE key = ?", listOf("len:$id"))
        }
        store.notifyChanged(TABLE)
    }

    fun setPinned(id: String, pinned: Boolean) { get(id)?.let { save(it.copy(pinned = pinned)) } }

    fun get(id: String): Memory? = db.query("SELECT $COLS FROM memories WHERE id = ?", listOf(id), ::read).firstOrNull()

    fun list(scope: String? = CLU_SCOPE, category: MemoryCategory? = null, includeDeleted: Boolean = false, limit: Int = 500): List<Memory> {
        val where = mutableListOf<String>(); val args = mutableListOf<Any?>()
        if (!includeDeleted) where += "deleted = 0"
        if (scope != null) { where += "scope = ?"; args += scope }
        if (category != null) { where += "category = ?"; args += category.name }
        val w = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        return db.query("SELECT $COLS FROM memories $w ORDER BY pinned DESC, COALESCE(event_at, created_at) DESC LIMIT $limit", args, ::read)
    }

    /** Free-text search for the memory browser: ranked results, plus substring matches on title. */
    fun search(query: String, scope: String? = CLU_SCOPE, limit: Int = 50): List<Memory> {
        val ranked = rank(query, scopes = scope?.let { listOf(it) }, limit = limit).map { it.memory }
        val like = db.query("SELECT $COLS FROM memories WHERE deleted = 0 ${if (scope != null) "AND scope = ?" else ""} AND (title LIKE ? OR content LIKE ?) LIMIT $limit",
            listOfNotNull(scope) + listOf("%$query%", "%$query%"), ::read)
        return (ranked + like).distinctBy { it.id }.take(limit)
    }

    /**
     * BM25 ranking over the term index. Pinned memories get a small boost; expired temporary memories are excluded.
     */
    fun rank(query: String, scopes: List<String>? = listOf(CLU_SCOPE), limit: Int = 10): List<ScoredMemory> {
        val qTerms = TextAnalysis.tokens(query).distinct()
        if (qTerms.isEmpty()) return emptyList()
        val n = db.queryOne("SELECT COUNT(*) FROM memories WHERE deleted = 0") { it.lng(0) } ?: 0L
        if (n == 0L) return emptyList()
        val avgLen = db.queryOne("SELECT AVG(value) FROM memory_stats WHERE key LIKE 'len:%'") { it.double(0) } ?: 1.0
        val placeholders = qTerms.joinToString(",") { "?" }
        val df = db.query("SELECT term, COUNT(DISTINCT memory_id) FROM memory_terms WHERE term IN ($placeholders) GROUP BY term", qTerms) {
            it.str(0) to it.lng(1)
        }.toMap()
        val postings = db.query("SELECT memory_id, term, tf FROM memory_terms WHERE term IN ($placeholders)", qTerms) {
            Triple(it.str(0), it.str(1), it.lng(2))
        }
        // Document lengths for all candidates in batched queries (avoids one query per posting).
        val lengths = HashMap<String, Double>()
        postings.map { it.first }.distinct().chunked(500).forEach { chunk ->
            db.query("SELECT key, value FROM memory_stats WHERE key IN (${chunk.joinToString(",") { "?" }})", chunk.map { "len:$it" }) {
                it.str(0).removePrefix("len:") to (it.double(1) ?: avgLen)
            }.forEach { (k, v) -> lengths[k] = v }
        }
        val scores = HashMap<String, Double>()
        val k1 = 1.2; val b = 0.75
        for ((id, term, tf) in postings) {
            val len = lengths[id] ?: avgLen
            val d = (df[term] ?: 1L).toDouble()
            val idf = ln(1 + (n - d + 0.5) / (d + 0.5))
            val s = idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * len / avgLen))
            scores[id] = (scores[id] ?: 0.0) + s
        }
        val now = store.clock.now()
        return scores.entries.sortedByDescending { it.value }.take(limit * 3).mapNotNull { (id, s) ->
            val m = get(id) ?: return@mapNotNull null
            if (m.deleted) return@mapNotNull null
            if (scopes != null && m.scope !in scopes) return@mapNotNull null
            if (m.expiresAt != null && m.expiresAt < now) return@mapNotNull null
            ScoredMemory(m, s + if (m.pinned) 0.5 else 0.0)
        }.sortedByDescending { it.score }.take(limit)
    }

    fun purgeExpired(): Int {
        val now = store.clock.now()
        val ids = db.query("SELECT id FROM memories WHERE deleted = 0 AND expires_at IS NOT NULL AND expires_at < ?", listOf(now)) { it.str(0) }
        ids.forEach(::delete)
        return ids.size
    }

    fun count(): Long = db.queryOne("SELECT COUNT(*) FROM memories WHERE deleted = 0") { it.lng(0) } ?: 0

    // ------------------------------------------------------------ sync
    fun rowsChangedSince(ids: Collection<String>): List<Memory> = ids.mapNotNull { id -> db.query("SELECT $COLS FROM memories WHERE id = ?", listOf(id), ::read).firstOrNull() }

    /** Applies a memory received from a peer if newer. Returns true when applied. Bypasses the enable switch only for deletions. */
    fun applyRemote(m: Memory): Boolean {
        val local = get(m.id)
        if (local != null && local.hlc >= m.hlc) return false
        if (!m.deleted && !settings.bool(Settings.MEMORY_ENABLED)) return false
        store.hlc.observe(m.hlc)
        db.transaction { write(m); store.logRemote(TABLE, m.id, m.hlc) }
        store.notifyChanged(TABLE)
        return true
    }

    fun writeConflictCopy(m: Memory): Memory {
        val copy = m.copy(id = newId(), title = m.title + " (conflict copy)")
        return save(copy)
    }

    private fun read(r: SqlRow): Memory = Memory(
        id = r.str(0), category = MemoryCategory.valueOf(r.str(1)), title = r.str(2), content = r.str(3), summary = r.string(4),
        retainMode = RetainMode.valueOf(r.str(5)), eventAt = r.long(6), eventHasTime = r.bool(7), source = MemorySource.valueOf(r.str(8)),
        people = CluJson.decodeFromString(ListSerializer(String.serializer()), r.str(9)),
        tags = CluJson.decodeFromString(ListSerializer(String.serializer()), r.str(10)),
        pinned = r.bool(11), scope = r.str(12), expiresAt = r.long(13), createdAt = r.lng(14), updatedAt = r.lng(15),
        deleted = r.bool(16), hlc = r.str(17), origin = r.str(18),
    )

    companion object {
        const val TABLE = "memories"
        private const val COLS = "id, category, title, content, summary, retain_mode, event_at, event_has_time, source, people, tags, pinned, scope, expires_at, created_at, updated_at, deleted, hlc, origin"
    }
}

// ---------------------------------------------------------------- inferred memories (proposals)

enum class ProposalKind(val label: String) { OBSERVATION("Observation"), HYPOTHESIS("Hypothesis"), DIARY("Diary entry") }
enum class ProposalStatus { PENDING, APPROVED, REJECTED }

data class MemoryProposal(
    val id: String, val kind: ProposalKind, val text: String, val rationale: String, val status: ProposalStatus,
    val createdAt: Long, val decidedAt: Long?, val memoryId: String?, val conversationId: String?,
)

/**
 * Inferred memories never become permanent without explicit owner approval. Rejected proposals keep only a
 * fingerprint (to avoid re-suggesting the same thing); their text is erased.
 */
class ProposalRepository(private val store: CluStore, private val settings: SettingsStore, private val memories: MemoryRepository) {
    private val db = store.db
    /** Where approved diary suggestions go (the diary, not memory). Set by the composition root. */
    @Volatile var diarySink: ((text: String, createdAt: Long, conversationId: String?) -> Unit)? = null

    fun propose(kind: ProposalKind, text: String, rationale: String, conversationId: String? = null): MemoryProposal? {
        if (kind != ProposalKind.DIARY && !settings.bool(Settings.PROPOSE_INSIGHTS)) return null
        if (!settings.bool(Settings.MEMORY_ENABLED)) return null
        val fp = fingerprint(text)
        val exists = db.queryOne("SELECT COUNT(*) FROM memory_proposals WHERE fingerprint = ?", listOf(fp)) { it.lng(0) } ?: 0
        if (exists > 0) return null
        val p = MemoryProposal(newId(), kind, text, rationale, ProposalStatus.PENDING, store.clock.now(), null, null, conversationId)
        db.execute("INSERT INTO memory_proposals(id, kind, text, rationale, status, created_at, conversation_id, fingerprint) VALUES(?,?,?,?,?,?,?,?)",
            listOf(p.id, kind.name, text, rationale, p.status.name, p.createdAt, conversationId, fp))
        store.notifyChanged(TABLE)
        return p
    }

    fun pending(): List<MemoryProposal> = query("WHERE status = 'PENDING' ORDER BY created_at DESC")
    fun get(id: String): MemoryProposal? = query("WHERE id = ?", listOf(id)).firstOrNull()

    /**
     * Owner approves; the (optionally edited) text becomes a memory with APPROVED_INFERENCE provenance. An approved
     * diary suggestion becomes a diary entry instead (returns null): the diary is not memory.
     */
    fun approve(id: String, editedText: String? = null, category: MemoryCategory? = null): Memory? {
        val p = get(id) ?: throw IllegalArgumentException("proposal not found")
        check(p.status == ProposalStatus.PENDING) { "proposal already decided" }
        val text = editedText?.takeIf { it.isNotBlank() } ?: p.text
        val sink = diarySink
        if (p.kind == ProposalKind.DIARY && sink != null) {
            sink(text, p.createdAt, p.conversationId)
            db.execute("UPDATE memory_proposals SET status = 'APPROVED', decided_at = ? WHERE id = ?", listOf(store.clock.now(), id))
            store.notifyChanged(TABLE)
            return null
        }
        val m = memories.save(Memory(category = category ?: MemoryCategory.INSIGHT, title = text.take(60), content = text,
            source = MemorySource.APPROVED_INFERENCE, tags = listOf(p.kind.name.lowercase())))
        db.execute("UPDATE memory_proposals SET status = 'APPROVED', decided_at = ?, memory_id = ? WHERE id = ?", listOf(store.clock.now(), m.id, id))
        store.notifyChanged(TABLE)
        return m
    }

    fun reject(id: String) {
        db.execute("UPDATE memory_proposals SET status = 'REJECTED', decided_at = ?, text = '', rationale = '' WHERE id = ?", listOf(store.clock.now(), id))
        store.notifyChanged(TABLE)
    }

    private fun query(where: String, args: List<Any?> = emptyList()) =
        db.query("SELECT id, kind, text, rationale, status, created_at, decided_at, memory_id, conversation_id FROM memory_proposals $where", args) {
            MemoryProposal(it.str(0), ProposalKind.valueOf(it.str(1)), it.str(2), it.str(3), ProposalStatus.valueOf(it.str(4)), it.lng(5),
                it.long(6), it.string(7), it.string(8))
        }

    private fun fingerprint(text: String): String {
        val norm = TextAnalysis.tokens(text).sorted().joinToString(" ")
        return MessageDigest.getInstance("SHA-256").digest(norm.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    companion object { const val TABLE = "memory_proposals" }
}
