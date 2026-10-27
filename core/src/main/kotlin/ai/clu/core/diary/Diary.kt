package ai.clu.core.diary

import ai.clu.core.db.CluStore
import ai.clu.core.db.SqlRow
import ai.clu.core.db.bool
import ai.clu.core.db.lng
import ai.clu.core.db.queryOne
import ai.clu.core.db.str
import ai.clu.core.memory.Memory
import ai.clu.core.memory.MemoryCategory
import ai.clu.core.memory.MemoryRepository
import ai.clu.core.memory.MemorySource
import ai.clu.core.util.newId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Who wrote an entry: the owner, or Clu from a conversation the owner asked it to note down. */
enum class DiarySource(val id: String, val label: String) {
    MANUAL("manual", "Written by you"),
    CONVERSATION("conversation", "Noted from a conversation");
    companion object { fun of(id: String) = values().firstOrNull { it.id == id } ?: MANUAL }
}

data class DiaryEntry(
    val id: String = newId(),
    /** Day the entry is about, yyyy-MM-dd (local time). */
    val date: String,
    val title: String,
    val body: String,
    val source: DiarySource = DiarySource.MANUAL,
    val conversationId: String? = null,
    /** Set when the owner promoted the entry into a memory. */
    val memoryId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

/**
 * The owner's diary: local, plain records the owner writes or explicitly asks Clu to note. Separate from long-term
 * memory: entries never reach prompts by themselves, and become a memory only when the owner promotes one. Search is
 * a plain word match over title and text (no models, no index to maintain); it is fast for thousands of entries.
 * Not synchronised between devices in this version.
 */
class DiaryRepository(private val store: CluStore, private val memories: MemoryRepository) {
    private val db = store.db

    fun today(nowMs: Long = store.clock.now()): String = dayOf(nowMs)

    /** Creates or updates an entry (blank entries are refused). */
    fun save(e: DiaryEntry): DiaryEntry {
        require(e.title.isNotBlank() || e.body.isNotBlank()) { "A diary entry needs a title or text." }
        require(DATE.matches(e.date)) { "Date must be yyyy-MM-dd." }
        val now = store.clock.now()
        val existing = get(e.id)
        val row = e.copy(title = e.title.trim(), body = e.body.trim(), createdAt = existing?.createdAt ?: (if (e.createdAt > 0) e.createdAt else now),
            updatedAt = now, deleted = false)
        db.execute("""INSERT INTO diary_entries(id, entry_date, title, body, source, conversation_id, memory_id, created_at, updated_at, deleted)
            VALUES(?,?,?,?,?,?,?,?,?,0) ON CONFLICT(id) DO UPDATE SET entry_date=excluded.entry_date, title=excluded.title, body=excluded.body,
            source=excluded.source, conversation_id=excluded.conversation_id, memory_id=excluded.memory_id, updated_at=excluded.updated_at, deleted=0""",
            listOf(row.id, row.date, row.title, row.body, row.source.id, row.conversationId, row.memoryId, row.createdAt, row.updatedAt))
        store.notifyChanged(TABLE)
        return row
    }

    /** Adds a note dated today (or [date]). Title: the first words of the text unless given. */
    fun add(text: String, title: String? = null, source: DiarySource = DiarySource.MANUAL, conversationId: String? = null, date: String = today()): DiaryEntry =
        save(DiaryEntry(date = date, title = title?.takeIf { it.isNotBlank() } ?: titleFor(text), body = text, source = source, conversationId = conversationId))

    /** Deleting erases the text; a promoted memory stays (it is managed in Memory). */
    fun delete(id: String) {
        db.execute("UPDATE diary_entries SET deleted = 1, title = '', body = '', updated_at = ? WHERE id = ?", listOf(store.clock.now(), id))
        store.notifyChanged(TABLE)
    }

    fun get(id: String): DiaryEntry? = db.query("SELECT $COLS FROM diary_entries WHERE id = ? AND deleted = 0", listOf(id), ::read).firstOrNull()

    /** Newest first; optionally one day (yyyy-MM-dd) or one month (yyyy-MM). */
    fun list(day: String? = null, month: String? = null, limit: Int = 500): List<DiaryEntry> {
        val (w, a) = when {
            day != null -> "AND entry_date = ?" to listOf<Any?>(day)
            month != null -> "AND substr(entry_date, 1, 7) = ?" to listOf<Any?>(month)
            else -> "" to emptyList()
        }
        return db.query("SELECT $COLS FROM diary_entries WHERE deleted = 0 $w ORDER BY entry_date DESC, created_at DESC LIMIT $limit", a, ::read)
    }

    /** Months that have entries, newest first, with counts (for browsing by date). */
    fun months(): List<Pair<String, Int>> =
        db.query("SELECT substr(entry_date, 1, 7), COUNT(*) FROM diary_entries WHERE deleted = 0 GROUP BY 1 ORDER BY 1 DESC") { it.str(0) to it.lng(1).toInt() }

    /** Entries containing every word of [query] (case-insensitive) in title or text, newest first. */
    fun search(query: String, limit: Int = 100): List<DiaryEntry> {
        val words = query.lowercase().split(Regex("\\s+")).map { it.trim('"', '\'', ',', '.', '?', '!') }.filter { it.isNotEmpty() }.take(8)
        if (words.isEmpty()) return emptyList()
        val where = words.joinToString(" AND ") { "(lower(title) LIKE ? ESCAPE '\\' OR lower(body) LIKE ? ESCAPE '\\')" }
        val args = words.flatMap { w -> val p = "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; listOf(p, p) }
        return db.query("SELECT $COLS FROM diary_entries WHERE deleted = 0 AND $where ORDER BY entry_date DESC, created_at DESC LIMIT $limit", args, ::read)
    }

    fun count(): Long = db.queryOne("SELECT COUNT(*) FROM diary_entries WHERE deleted = 0") { it.lng(0) } ?: 0

    /**
     * The owner chose to keep this entry as a long-term memory. The memory is a copy (marked as supplied by the owner,
     * dated the entry's day); the entry remembers the link so it isn't promoted twice.
     */
    fun promote(id: String, category: MemoryCategory = MemoryCategory.EXPERIENCE): Memory {
        val e = get(id) ?: throw IllegalArgumentException("Diary entry not found.")
        e.memoryId?.let { mid -> memories.get(mid)?.takeIf { !it.deleted }?.let { return it } }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(e.date)!!.time + 12 * 3_600_000L
        val m = memories.save(Memory(category = category, title = e.title, content = e.body, eventAt = day,
            source = if (e.source == DiarySource.CONVERSATION) MemorySource.CONVERSATION else MemorySource.OWNER_SUPPLIED, tags = listOf("from-diary")))
        save(e.copy(memoryId = m.id))
        return m
    }

    private fun read(r: SqlRow) = DiaryEntry(r.str(0), r.str(1), r.str(2), r.str(3), DiarySource.of(r.str(4)), r.string(5), r.string(6), r.lng(7), r.lng(8), r.bool(9))

    companion object {
        const val TABLE = "diary_entries"
        private const val COLS = "id, entry_date, title, body, source, conversation_id, memory_id, created_at, updated_at, deleted"
        private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
        fun dayOf(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
        fun titleFor(text: String): String = text.trim().lineSequence().first().split(Regex("\\s+")).take(8).joinToString(" ").take(60).ifEmpty { "Diary entry" }
    }
}
