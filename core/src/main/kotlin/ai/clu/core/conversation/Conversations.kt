package ai.clu.core.conversation

import ai.clu.core.db.queryOne
import ai.clu.core.db.CluStore
import ai.clu.core.db.SqlRow
import ai.clu.core.db.bool
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.serialization.Serializable

enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

@Serializable
data class Attachment(val kind: String, val uri: String, val name: String, val mime: String = "", val analyzed: Boolean = false, val note: String = "")

/** Per-message metadata: where the reply was produced, which tools ran, timings, attachments, speaker. */
@Serializable
data class MessageMeta(
    val source: String = "local",          // local | home_device | external | template | tool
    val engine: String = "",
    val tools: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val speaker: String = "",              // person id or "" (typed by owner) / "unknown"
    val stats: Map<String, Double> = emptyMap(),
    val error: String = "",
    val interrupted: Boolean = false,
    val personaId: String = "clu",
)

data class Conversation(
    val id: String, val title: String, val personaId: String, val archived: Boolean, val createdAt: Long, val updatedAt: Long,
    val deleted: Boolean = false, val hlc: String = "", val origin: String = "",
)

data class Message(
    val id: String, val conversationId: String, val role: Role, val content: String, val createdAt: Long,
    val meta: MessageMeta = MessageMeta(), val deleted: Boolean = false, val hlc: String = "", val origin: String = "",
)

/** Conversation history: original messages and replies, stored separately from derived memories. */
class ConversationRepository(private val store: CluStore) {
    private val db = store.db

    fun create(title: String = "New conversation", personaId: String = "clu"): Conversation {
        val now = store.clock.now()
        val c = Conversation(newId(), title, personaId, false, now, now)
        db.transaction { writeConv(c.copy(hlc = store.stamp(CONV, c.id), origin = store.deviceId)) }
        store.notifyChanged(CONV)
        return get(c.id)!!
    }

    fun get(id: String): Conversation? = db.query("SELECT $CCOLS FROM conversations WHERE id = ?", listOf(id), ::readConv).firstOrNull()

    fun list(includeArchived: Boolean = false, personaId: String? = null): List<Conversation> {
        val w = buildList {
            add("deleted = 0")
            if (!includeArchived) add("archived = 0")
            if (personaId != null) add("persona_id = ?")
        }.joinToString(" AND ")
        return db.query("SELECT $CCOLS FROM conversations WHERE $w ORDER BY updated_at DESC", listOfNotNull(personaId), ::readConv)
    }

    fun rename(id: String, title: String) = update(id) { it.copy(title = title.trim().take(120).ifEmpty { "Untitled" }) }
    fun setArchived(id: String, archived: Boolean) = update(id) { it.copy(archived = archived) }

    fun delete(id: String) {
        update(id) { it.copy(deleted = true) }
        db.query("SELECT id FROM messages WHERE conversation_id = ? AND deleted = 0", listOf(id)) { it.str(0) }.forEach { mid ->
            db.transaction {
                val h = store.stamp(MSG, mid)
                db.execute("UPDATE messages SET deleted = 1, content = '', hlc = ?, origin = ? WHERE id = ?", listOf(h, store.deviceId, mid))
            }
        }
        store.notifyChanged(MSG)
    }

    private fun update(id: String, f: (Conversation) -> Conversation) {
        val c = get(id) ?: return
        db.transaction { writeConv(f(c).copy(updatedAt = store.clock.now(), hlc = store.stamp(CONV, id), origin = store.deviceId)) }
        store.notifyChanged(CONV)
    }

    fun addMessage(conversationId: String, role: Role, content: String, meta: MessageMeta = MessageMeta()): Message {
        val now = store.clock.now()
        val m = Message(newId(), conversationId, role, content, now, meta)
        db.transaction {
            writeMsg(m.copy(hlc = store.stamp(MSG, m.id), origin = store.deviceId))
            db.execute("UPDATE conversations SET updated_at = ? WHERE id = ?", listOf(now, conversationId))
        }
        store.notifyChanged(MSG)
        return m
    }

    /** Used when a streamed reply is finalised (content/meta known only at the end). */
    fun updateMessage(id: String, content: String, meta: MessageMeta) {
        db.transaction {
            val h = store.stamp(MSG, id)
            db.execute("UPDATE messages SET content = ?, meta = ?, hlc = ?, origin = ? WHERE id = ?",
                listOf(content, CluJson.encodeToString(MessageMeta.serializer(), meta), h, store.deviceId, id))
        }
        store.notifyChanged(MSG)
    }

    fun messages(conversationId: String, limit: Int = 1000): List<Message> =
        db.query("SELECT $MCOLS FROM messages WHERE conversation_id = ? AND deleted = 0 ORDER BY created_at ASC, rowid ASC LIMIT $limit",
            listOf(conversationId), ::readMsg)

    fun message(id: String): Message? = db.query("SELECT $MCOLS FROM messages WHERE id = ?", listOf(id), ::readMsg).firstOrNull()

    /** Searches titles and message text; returns matching conversations, most recent first. */
    fun search(query: String): List<Conversation> {
        val q = "%${query.trim()}%"
        return db.query(
            """SELECT DISTINCT c.id, c.title, c.persona_id, c.archived, c.created_at, c.updated_at, c.deleted, c.hlc, c.origin
               FROM conversations c LEFT JOIN messages m ON m.conversation_id = c.id AND m.deleted = 0
               WHERE c.deleted = 0 AND (c.title LIKE ? OR m.content LIKE ?) ORDER BY c.updated_at DESC""",
            listOf(q, q), ::readConv
        )
    }

    fun countMessages(): Long = db.queryOne("SELECT COUNT(*) FROM messages WHERE deleted = 0") { it.lng(0) } ?: 0

    // ---------------------------------------------------------------- sync
    fun applyRemoteConversation(c: Conversation): Boolean {
        val local = get(c.id)
        if (local != null && local.hlc >= c.hlc) return false
        store.hlc.observe(c.hlc)
        db.transaction { writeConv(c); store.logRemote(CONV, c.id, c.hlc) }
        store.notifyChanged(CONV)
        return true
    }

    fun applyRemoteMessage(m: Message): Boolean {
        val local = message(m.id)
        if (local != null && local.hlc >= m.hlc) return false
        store.hlc.observe(m.hlc)
        db.transaction { writeMsg(m); store.logRemote(MSG, m.id, m.hlc) }
        store.notifyChanged(MSG)
        return true
    }

    private fun writeConv(c: Conversation) = db.execute(
        """INSERT INTO conversations(id, title, persona_id, archived, created_at, updated_at, deleted, hlc, origin) VALUES(?,?,?,?,?,?,?,?,?)
           ON CONFLICT(id) DO UPDATE SET title=excluded.title, persona_id=excluded.persona_id, archived=excluded.archived,
           updated_at=excluded.updated_at, deleted=excluded.deleted, hlc=excluded.hlc, origin=excluded.origin""",
        listOf(c.id, c.title, c.personaId, c.archived, c.createdAt, c.updatedAt, c.deleted, c.hlc, c.origin)
    )

    private fun writeMsg(m: Message) = db.execute(
        """INSERT INTO messages(id, conversation_id, role, content, created_at, meta, deleted, hlc, origin) VALUES(?,?,?,?,?,?,?,?,?)
           ON CONFLICT(id) DO UPDATE SET content=excluded.content, meta=excluded.meta, deleted=excluded.deleted, hlc=excluded.hlc, origin=excluded.origin""",
        listOf(m.id, m.conversationId, m.role.name, m.content, m.createdAt, CluJson.encodeToString(MessageMeta.serializer(), m.meta),
            m.deleted, m.hlc, m.origin)
    )

    private fun readConv(r: SqlRow) = Conversation(r.str(0), r.str(1), r.string(2) ?: "clu", r.bool(3), r.lng(4), r.lng(5), r.bool(6), r.str(7), r.str(8))
    private fun readMsg(r: SqlRow) = Message(r.str(0), r.str(1), Role.valueOf(r.str(2)), r.str(3), r.lng(4),
        runCatching { CluJson.decodeFromString(MessageMeta.serializer(), r.str(5)) }.getOrDefault(MessageMeta()), r.bool(6), r.str(7), r.str(8))

    companion object {
        const val CONV = "conversations"
        const val MSG = "messages"
        private const val CCOLS = "id, title, persona_id, archived, created_at, updated_at, deleted, hlc, origin"
        private const val MCOLS = "id, conversation_id, role, content, created_at, meta, deleted, hlc, origin"
    }
}
