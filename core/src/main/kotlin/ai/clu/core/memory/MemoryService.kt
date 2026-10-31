package ai.clu.core.memory

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.CluStore
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.db.queryOne
import ai.clu.core.history.ArchiveService
import ai.clu.core.history.Kind
import ai.clu.core.history.Passage
import ai.clu.core.history.Relation
import ai.clu.core.util.newId
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The five kinds of memory CLU keeps. Every stored record belongs to exactly one; the kind follows from its category
 * unless the record says otherwise. Working memory lives apart from persistent memory (see [WorkingMemory]).
 */
enum class MemoryKind(val label: String) {
    CORE("Core identity"), EPISODIC("Episodic"), SEMANTIC("Semantic"), PROCEDURAL("Procedural"), WORKING("Working");

    companion object {
        fun of(category: MemoryCategory): MemoryKind = when (category) {
            MemoryCategory.CORE_FACT, MemoryCategory.PREFERENCE, MemoryCategory.INSTRUCTION, MemoryCategory.PROJECT, MemoryCategory.MANUAL, MemoryCategory.INSIGHT -> CORE
            MemoryCategory.EXPERIENCE, MemoryCategory.DECISION, MemoryCategory.DIARY -> EPISODIC
            MemoryCategory.KNOWLEDGE -> SEMANTIC
            MemoryCategory.PROCEDURE -> PROCEDURAL
            MemoryCategory.TEMPORARY -> WORKING
        }
        fun categories(kind: MemoryKind): List<MemoryCategory> = MemoryCategory.entries.filter { of(it) == kind }
    }
}

/** Whether the owner's approval was needed for this record, and whether it was given. */
enum class Approval { NOT_REQUIRED, APPROVED }

/** What the unified memory API tracks about a record beyond the record itself. Device-local; rebuilt from defaults if lost. */
data class MemoryMeta(
    val memoryId: String, val kind: MemoryKind, val confidence: Double?, val lastAccessed: Long?, val accessCount: Int, val reinforced: Int,
    val reviewAt: Long?, val sourceRef: String, val approval: Approval, val updatedAt: Long,
)

/** Where a memory came from, in words (for the memory detail view). */
data class Provenance(val source: String, val reference: String, val documents: List<String>, val mergedFrom: List<String>, val approval: Approval)

data class RelatedRecord(val kind: String, val id: String, val title: String, val relation: String, val note: String, val linkId: String = "")

data class MemoryDetail(
    val memory: Memory, val meta: MemoryMeta, val provenance: Provenance, val related: List<RelatedRecord>,
    val revisions: List<Pair<Long, Memory>>, val conflicts: List<String>,
)

/** One filter-and-search request over everything CLU remembers. */
data class RecallRequest(
    val query: String = "",
    val kinds: Set<MemoryKind>? = null,
    val categories: Set<MemoryCategory>? = null,
    val people: List<String> = emptyList(),
    val from: LocalDate? = null, val to: LocalDate? = null,
    val includeDocuments: Boolean = true,
    val expandRelated: Boolean = true,
    val limit: Int = 10,
    val scopes: List<String>? = listOf(CLU_SCOPE),
    /** Record that the hits were used (last-accessed, access count). Browsing and searching in the UI pass false. */
    val touch: Boolean = true,
)

/** A result: a memory (conversation- or owner-derived) or a passage of an indexed document, never mixed up. */
data class RecallHit(val memory: Memory?, val passage: Passage?, val score: Double, val why: List<String>) {
    val isDocument get() = passage != null
}

/** A local embedding model (none is bundled yet): turns texts into vectors for semantic retrieval. Must run offline. */
interface Embedder {
    val id: String
    val dim: Int
    fun embed(texts: List<String>): List<FloatArray>
}

/**
 * The one memory API. Conversations, agents, planning and the shells all go through here, over the single
 * authoritative store (the `memories` table and its archive tables); this class adds per-record metadata, the five
 * kinds, working memory, hybrid retrieval and relationship-aware recall without a second copy of anything.
 */
class MemoryService(
    private val store: CluStore, private val settings: SettingsStore, val memories: MemoryRepository, val archive: ArchiveService,
) {
    private val db = store.db
    val working = WorkingMemory(store, settings)
    val vectors = VectorIndex(store)
    @Volatile var embedder: Embedder? = null

    // ------------------------------------------------------------------------------------------ metadata
    fun meta(id: String): MemoryMeta? = db.queryOne("SELECT memory_id, kind, confidence, last_accessed, access_count, reinforced, review_at, source_ref, approval, updated_at " +
        "FROM memory_meta WHERE memory_id = ?", listOf(id)) { r ->
        MemoryMeta(r.str(0), r.string(1)?.let { k -> runCatching { MemoryKind.valueOf(k) }.getOrNull() } ?: MemoryKind.CORE, r.double(2), r.long(3),
            r.lng(4).toInt(), r.lng(5).toInt(), r.long(6), r.str(7), runCatching { Approval.valueOf(r.str(8)) }.getOrDefault(Approval.NOT_REQUIRED), r.lng(9))
    }

    /** The record's metadata, with defaults derived from the record when nothing was stored (every record has a kind). */
    fun metaOf(m: Memory): MemoryMeta {
        val stored = meta(m.id)
        val kind = stored?.kind ?: MemoryKind.of(m.category)
        return (stored ?: MemoryMeta(m.id, kind, null, null, 0, 0, null, "", if (m.source == MemorySource.APPROVED_INFERENCE) Approval.APPROVED else Approval.NOT_REQUIRED, m.updatedAt)).copy(kind = kind)
    }

    fun setMeta(id: String, kind: MemoryKind? = null, confidence: Double? = null, reviewAt: Long? = null, sourceRef: String? = null, approval: Approval? = null) {
        val m = memories.get(id) ?: return
        val cur = metaOf(m)
        val now = store.clock.now()
        db.execute("""INSERT INTO memory_meta(memory_id, kind, confidence, last_accessed, access_count, reinforced, review_at, source_ref, approval, updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(memory_id) DO UPDATE SET kind=excluded.kind, confidence=excluded.confidence, review_at=excluded.review_at,
            source_ref=excluded.source_ref, approval=excluded.approval, updated_at=excluded.updated_at""",
            listOf(id, (kind ?: cur.kind).name, confidence ?: cur.confidence, cur.lastAccessed, cur.accessCount, cur.reinforced, reviewAt ?: cur.reviewAt,
                sourceRef ?: cur.sourceRef, (approval ?: cur.approval).name, now))
    }

    /** The owner restated something Clu already knew: remember that it was repeated (more confidence, never a new record). */
    fun reinforce(id: String) {
        val m = memories.get(id) ?: return
        val cur = metaOf(m); val now = store.clock.now()
        val conf = (((cur.confidence ?: 0.6) + 1.0) / 2 + 0.02).coerceAtMost(0.98)
        db.execute("""INSERT INTO memory_meta(memory_id, kind, confidence, last_accessed, access_count, reinforced, review_at, source_ref, approval, updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(memory_id) DO UPDATE SET confidence=excluded.confidence, reinforced=reinforced+1, updated_at=excluded.updated_at""",
            listOf(id, cur.kind.name, conf, cur.lastAccessed, cur.accessCount, 1, cur.reviewAt, cur.sourceRef, cur.approval.name, now))
    }

    /** After a merge: the kept record inherits the folded one's repeat count and best confidence; the folded record's metadata and vector go. */
    fun absorbMeta(keepId: String, otherId: String) {
        val keep = memories.get(keepId) ?: return
        val k = metaOf(keep); val o = meta(otherId)
        if (o != null) {
            db.execute("""INSERT INTO memory_meta(memory_id, kind, confidence, last_accessed, access_count, reinforced, review_at, source_ref, approval, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(memory_id) DO UPDATE SET confidence=excluded.confidence, access_count=excluded.access_count,
                reinforced=excluded.reinforced, last_accessed=excluded.last_accessed, updated_at=excluded.updated_at""",
                listOf(keepId, k.kind.name, listOfNotNull(k.confidence, o.confidence).maxOrNull(), listOfNotNull(k.lastAccessed, o.lastAccessed).maxOrNull(),
                    k.accessCount + o.accessCount, k.reinforced + o.reinforced + 1, k.reviewAt, k.sourceRef.ifBlank { o.sourceRef }, k.approval.name, store.clock.now()))
        }
        db.execute("DELETE FROM memory_meta WHERE memory_id = ?", listOf(otherId))
        db.execute("DELETE FROM memory_vectors WHERE kind = 'memory' AND ref_id = ?", listOf(otherId))
    }

    /** Marks records as used. A few statements per recall, nothing else. */
    fun touch(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val now = store.clock.now()
        db.transaction {
            for (id in ids) {
                val m = memories.get(id) ?: continue
                val cur = metaOf(m)
                db.execute("""INSERT INTO memory_meta(memory_id, kind, confidence, last_accessed, access_count, reinforced, review_at, source_ref, approval, updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(memory_id) DO UPDATE SET last_accessed=excluded.last_accessed, access_count=access_count+1""",
                    listOf(id, cur.kind.name, cur.confidence, now, 1, cur.reinforced, cur.reviewAt, cur.sourceRef, cur.approval.name, now))
            }
        }
    }

    // ------------------------------------------------------------------------------------------ writing
    /**
     * Saves a memory through the archive (links, duplicate and conflict checks) together with its provenance in one
     * transaction: either everything is written or nothing is.
     */
    fun remember(m: Memory, sourceRef: String = "", confidence: Double? = null, approval: Approval = Approval.NOT_REQUIRED,
                 kind: MemoryKind? = null, reviewAt: Long? = null): ArchiveService.SaveResult = db.transaction {
        val r = archive.saveMemory(m)
        setMeta(r.memory.id, kind = kind, confidence = confidence, sourceRef = sourceRef.ifBlank { null }, approval = approval, reviewAt = reviewAt)
        r.duplicates.forEach { archive.links.link(Kind.MEMORY, r.memory.id, Kind.MEMORY, it.id, Relation.RELATED, "possible duplicate") }
        r
    }

    /** Owner-made deletion: the record, its relationships, metadata and vector go together (earlier versions too). */
    fun forget(id: String) {
        db.transaction {
            memories.delete(id)
            db.execute("DELETE FROM memory_meta WHERE memory_id = ?", listOf(id))
            db.execute("DELETE FROM memory_vectors WHERE kind = 'memory' AND ref_id = ?", listOf(id))
        }
    }

    // ------------------------------------------------------------------------------------------ retrieval
    /**
     * Hybrid retrieval: BM25 keywords over memories (and, when asked, indexed documents), optional semantic matches
     * when a local [embedder] has indexed vectors, fused by rank; then metadata filters (kind, category, people, time),
     * importance/recency/explicitness weighting, and one hop of relationships from the best hits. Works fully offline;
     * without an embedder it is keyword + metadata + relationships.
     */
    fun recall(req: RecallRequest): List<RecallHit> {
        val now = store.clock.now()
        val q = req.query.trim()
        val wide = (req.limit * 4).coerceAtLeast(20)
        val timeSet: Set<String>? = if (req.from != null || req.to != null)
            memories.inPeriod(req.from ?: LocalDate.of(1, 1, 1), req.to ?: LocalDate.of(9999, 12, 31), scope = null, limit = 5000).map { it.id }.toSet() else null

        fun allowed(m: Memory): Boolean {
            if (m.deleted || m.mergedInto != null) return false
            if (req.scopes != null && m.scope !in req.scopes) return false
            if (m.expiresAt != null && m.expiresAt < now) return false
            if (req.categories != null && m.category !in req.categories) return false
            if (req.kinds != null && metaOf(m).kind !in req.kinds) return false
            if (timeSet != null && m.id !in timeSet) return false
            if (req.people.isNotEmpty() && req.people.none { p -> m.people.any { it.equals(p, true) } }) return false
            return true
        }

        val scores = LinkedHashMap<String, Double>(); val why = HashMap<String, MutableList<String>>()
        val byId = HashMap<String, Memory>()
        fun add(m: Memory, s: Double, reason: String) { byId[m.id] = m; scores[m.id] = (scores[m.id] ?: 0.0) + s; why.getOrPut(m.id) { mutableListOf() }.let { if (reason !in it) it += reason } }

        if (q.isNotEmpty()) {
            val keyword = memories.rank(q, scopes = null, limit = wide).filter { allowed(it.memory) }
            keyword.forEachIndexed { i, s -> add(s.memory, rrf(i) * 100 + s.score * 0.01, "keyword match") }
            semantic(q, wide).forEachIndexed { i, (id, sim) ->
                val m = byId[id] ?: memories.get(id) ?: return@forEachIndexed
                if (allowed(m)) add(m, rrf(i) * 100, "similar meaning (%.2f)".format(sim))
            }
        } else {
            // No words: a filtered browse (by time, kind, category, person), most important and recent first.
            memories.list(scope = null, limit = 1000).filter(::allowed).forEach { add(it, 0.0, "matches the filters") }
        }

        // Weighting: explicit over inferred, newer over older, important and confident over the rest, used-often a little.
        val weighted = scores.entries.mapNotNull { (id, s) ->
            val m = byId[id] ?: return@mapNotNull null
            val meta = metaOf(m)
            val imp = 1.0 + 0.12 * m.importance + if (m.pinned) 0.15 else 0.0
            val conf = 0.9 + 0.2 * (meta.confidence ?: 0.6)
            val used = 1.0 + 0.03 * ln(1.0 + meta.accessCount)
            val base = if (q.isEmpty()) importanceRecencyBase(m, now) else s
            id to base * MemoryRanking.weight(m, now) * imp * conf * used
        }.sortedByDescending { it.second }

        val hits = ArrayList<RecallHit>()
        weighted.take(req.limit).forEach { (id, s) -> hits += RecallHit(byId[id]!!, null, s, why[id].orEmpty()) }

        // Connected information: one hop from the three best memory hits, strongest links first, never repeating a hit.
        if (req.expandRelated && hits.isNotEmpty()) {
            val seen = hits.mapNotNull { it.memory?.id }.toMutableSet()
            var extra = 0
            for (top in hits.toList().take(3)) {
                val m = top.memory ?: continue
                for (rel in relatedTo(m.id, Kind.MEMORY)) {
                    if (extra >= req.limit.coerceAtMost(4)) break
                    if (rel.kind != Kind.MEMORY || rel.id in seen) continue
                    val other = memories.get(rel.id)?.takeIf { allowed(it) } ?: continue
                    seen += other.id; extra++
                    hits += RecallHit(other, null, top.score * 0.5, listOf("${rel.relation} → \"${m.title.take(40)}\""))
                }
            }
        }

        if (req.includeDocuments && q.isNotEmpty()) {
            archive.documents.search(q, limit = (req.limit / 2).coerceIn(1, 5)).forEach { p ->
                hits += RecallHit(null, p, 0.5 + p.score / (p.score + 4.0), listOf("passage ${p.chunk + 1} of ${p.document.name}"))
            }
        }
        val ordered = hits.sortedByDescending { it.score }.take(req.limit + (if (req.includeDocuments) 5 else 0))
        if (req.touch) touch(ordered.mapNotNull { it.memory?.id })
        return ordered
    }

    private fun rrf(rank: Int) = 1.0 / (60 + rank)
    private fun importanceRecencyBase(m: Memory, now: Long): Double = 1.0 + m.importance * 0.5 + (if (m.pinned) 1.0 else 0.0) + (m.updatedAt.toDouble() / now.coerceAtLeast(1)) * 0.1

    /** Best semantic matches (id, cosine) from vectors an embedder has indexed; empty when there is no embedder or no vectors. */
    fun semantic(query: String, k: Int): List<Pair<String, Double>> {
        val e = embedder ?: return emptyList()
        if (!vectors.any(e.id)) return emptyList()
        val qv = runCatching { e.embed(listOf(query)).firstOrNull() }.getOrNull() ?: return emptyList()
        return vectors.nearest(e.id, qv, k)
    }

    /** Embeds memories that have no current vector, a bounded batch at a time (background job; never on the reply path). */
    fun indexVectors(maxItems: Int = 50, shouldStop: () -> Boolean = { false }): Int {
        val e = embedder ?: return 0
        val todo = db.query("SELECT id FROM memories WHERE deleted = 0 ORDER BY updated_at DESC LIMIT 2000") { it.str(0) }
        var done = 0
        for (batch in todo.mapNotNull { memories.get(it) }.filter { vectors.stale(e.id, it.id, it.indexText()) }.take(maxItems).chunked(8)) {
            if (shouldStop()) break
            val vs = e.embed(batch.map { it.indexText().take(1200) })
            db.transaction { batch.zip(vs).forEach { (m, v) -> vectors.put(e.id, m.id, v, m.indexText()) } }
            done += batch.size
        }
        return done
    }

    // ------------------------------------------------------------------------------------------ relationships
    /** Records linked to a record (either direction), with the relation and a title for display. */
    fun relatedTo(id: String, kind: String = Kind.MEMORY): List<RelatedRecord> = archive.links.of(kind, id).mapNotNull { l ->
        val (ok, oid) = if (l.aKind == kind && l.aId == id) l.bKind to l.bId else l.aKind to l.aId
        val title = when (ok) {
            Kind.MEMORY -> memories.get(oid)?.takeIf { !it.deleted }?.title
            Kind.PERSON -> archive.people.get(oid)?.name
            Kind.PROJECT -> archive.projects.get(oid)?.name
            Kind.DOCUMENT -> archive.documents.get(oid)?.name
            else -> null
        } ?: return@mapNotNull null
        RelatedRecord(ok, oid, title, l.relation, l.note, l.id)
    }.sortedBy { relationOrder(it.relation) }

    /** Everything connected within [depth] hops (a small breadth-first walk over the link table), nearest first. */
    fun connected(id: String, depth: Int = 2, limit: Int = 30): List<Pair<Int, RelatedRecord>> {
        val seen = mutableSetOf(Kind.MEMORY to id); val out = mutableListOf<Pair<Int, RelatedRecord>>()
        var frontier = listOf(Kind.MEMORY to id)
        for (d in 1..depth) {
            val next = mutableListOf<Pair<String, String>>()
            for ((k, i) in frontier) for (r in relatedTo(i, k)) {
                if (!seen.add(r.kind to r.id)) continue
                out += d to r; next += r.kind to r.id
                if (out.size >= limit) return out
            }
            frontier = next
        }
        return out
    }

    /** Typed relationship between two records (e.g. a decision "belongs to" a project, a document "supports" a memory). Idempotent. */
    fun relate(aKind: String, aId: String, bKind: String, bId: String, relation: String, note: String = "") {
        require(relation in RELATIONS) { "unknown relation $relation" }
        archive.links.link(aKind, aId, bKind, bId, relation, note)
    }

    /** Removes one relationship (by its id); the records stay. */
    fun unrelate(linkId: String) { archive.links.unlink(linkId) }

    private fun relationOrder(r: String) = when (r) { Relation.SUPPORTS -> 0; CONTRADICTS -> 1; Relation.ABOUT -> 2; Relation.INVOLVES -> 3; else -> 4 }

    // ------------------------------------------------------------------------------------------ detail
    fun detail(id: String): MemoryDetail? {
        val m = memories.get(id)?.takeIf { !it.deleted } ?: return null
        val meta = metaOf(m)
        val related = relatedTo(id)
        val merged = memories.revisions(id).filter { true }
        val mergedFrom = db.query("SELECT memory_ids FROM consolidation_log WHERE result_id = ? AND status IN ('AUTO','APPROVED')", listOf(id)) { it.str(0) }
            .flatMap { ids -> ids.split(',').filter { it.isNotBlank() && it != id } }.distinct()
            .map { oid -> memories.revisions(oid).firstOrNull()?.second?.title ?: memories.get(oid)?.title ?: oid }
        val docs = related.filter { it.kind == Kind.DOCUMENT }.map { it.title }
        val conflicts = archive.conflicts.involving(id).map { "${it.field}: \"${it.aValue}\" vs \"${it.bValue}\"" } +
            related.filter { it.relation == CONTRADICTS }.map { "contradicts \"${it.title}\"" }
        val prov = Provenance(m.source.label, meta.sourceRef, docs, mergedFrom, meta.approval)
        return MemoryDetail(m, meta, prov, related, merged, conflicts)
    }

    /** Counts per kind and category for the browser's filters. */
    fun overview(): Map<MemoryKind, Int> {
        val counts = db.query("SELECT m.category, COUNT(*), mm.kind FROM memories m LEFT JOIN memory_meta mm ON mm.memory_id = m.id WHERE m.deleted = 0 AND m.merged_into IS NULL AND m.category <> 'DIARY' GROUP BY m.category, mm.kind") {
            Triple(it.str(0), it.lng(1).toInt(), it.string(2)) }
        val out = MemoryKind.entries.associateWith { 0 }.toMutableMap()
        for ((c, n, k) in counts) {
            val kind = k?.let { runCatching { MemoryKind.valueOf(it) }.getOrNull() } ?: runCatching { MemoryKind.of(MemoryCategory.valueOf(c)) }.getOrDefault(MemoryKind.CORE)
            out[kind] = (out[kind] ?: 0) + n
        }
        out[MemoryKind.WORKING] = (out[MemoryKind.WORKING] ?: 0) + working.count()
        return out
    }

    companion object {
        /** A memory contradicts another (the owner decides which stands; neither is overwritten). */
        const val CONTRADICTS = "contradicts"
        /** A record belongs to a project or milestone / influences / changes the status of another. */
        const val BELONGS_TO = "belongs_to"; const val INFLUENCES = "influences"; const val UPDATES = "updates"; const val DERIVED_FROM = "derived_from"
        val RELATIONS = setOf(Relation.INVOLVES, Relation.ABOUT, Relation.SAME_EVENT, Relation.SAME_STORY, Relation.RELATED, Relation.SUPPORTS, Relation.ATTACHMENT,
            CONTRADICTS, BELONGS_TO, INFLUENCES, UPDATES, DERIVED_FROM)
    }
}

/** Optional vectors for semantic retrieval; brute-force cosine, bounded, stored as float32 blobs next to everything else. */
class VectorIndex(private val store: CluStore) {
    private val db = store.db
    private fun hash(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    private fun pack(v: FloatArray): ByteArray = java.nio.ByteBuffer.allocate(v.size * 4).also { b -> v.forEach { b.putFloat(it) } }.array()
    private fun unpack(b: ByteArray): FloatArray = java.nio.ByteBuffer.wrap(b).let { bb -> FloatArray(b.size / 4) { bb.getFloat() } }

    fun any(model: String): Boolean = (db.queryOne("SELECT COUNT(*) FROM memory_vectors WHERE model = ?", listOf(model)) { it.lng(0) } ?: 0L) > 0
    fun count(model: String): Int = (db.queryOne("SELECT COUNT(*) FROM memory_vectors WHERE model = ?", listOf(model)) { it.lng(0) } ?: 0L).toInt()
    fun stale(model: String, id: String, text: String): Boolean =
        db.queryOne("SELECT text_hash FROM memory_vectors WHERE kind = 'memory' AND ref_id = ? AND model = ?", listOf(id, model)) { it.str(0) } != hash(text)

    fun put(model: String, id: String, v: FloatArray, text: String) {
        db.execute("""INSERT INTO memory_vectors(kind, ref_id, model, dim, vec, text_hash, created_at) VALUES('memory',?,?,?,?,?,?)
            ON CONFLICT(kind, ref_id, model) DO UPDATE SET dim=excluded.dim, vec=excluded.vec, text_hash=excluded.text_hash, created_at=excluded.created_at""",
            listOf(id, model, v.size, pack(v), hash(text), store.clock.now()))
    }

    fun nearest(model: String, q: FloatArray, k: Int): List<Pair<String, Double>> {
        val qn = norm(q); if (qn == 0.0) return emptyList()
        val top = java.util.PriorityQueue<Pair<String, Double>>(compareBy { it.second })
        for ((id, v) in db.query("SELECT ref_id, vec FROM memory_vectors WHERE kind = 'memory' AND model = ? AND dim = ?", listOf(model, q.size)) { it.str(0) to unpack(it.blob(1) ?: ByteArray(0)) }) {
            val sim = dot(q, v) / (qn * norm(v).coerceAtLeast(1e-9))
            if (sim < 0.2) continue
            top.add(id to sim); if (top.size > k) top.poll()
        }
        return top.sortedByDescending { it.second }
    }
    private fun dot(a: FloatArray, b: FloatArray): Double { var s = 0.0; for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]; return s }
    private fun norm(a: FloatArray) = sqrt(dot(a, a))
    fun prune() { db.execute("DELETE FROM memory_vectors WHERE kind = 'memory' AND ref_id NOT IN (SELECT id FROM memories WHERE deleted = 0)") }
}

/** Short-lived context kept apart from persistent memory: active tasks and plans, temporary facts, recent context. */
class WorkingMemory(private val store: CluStore, private val settings: SettingsStore) {
    private val db = store.db
    enum class Kind { TASK, PLAN, NOTE, CONTEXT }
    data class Item(val id: String, val conversationId: String?, val kind: Kind, val text: String, val createdAt: Long, val expiresAt: Long)

    fun put(text: String, kind: Kind = Kind.NOTE, conversationId: String? = null, ttlHours: Int? = null): Item {
        require(text.isNotBlank()) { "nothing to keep" }
        val now = store.clock.now()
        val ttl = (ttlHours ?: settings.int(Settings.WORKING_HOURS)).coerceAtLeast(1) * 3_600_000L
        val it = Item(newId(), conversationId, kind, text.trim().take(2000), now, now + ttl)
        db.execute("INSERT INTO working_memory(id, conversation_id, kind, text, created_at, expires_at) VALUES(?,?,?,?,?,?)", listOf(it.id, it.conversationId, it.kind.name, it.text, it.createdAt, it.expiresAt))
        return it
    }

    fun list(conversationId: String? = null): List<Item> = db.query(
        "SELECT id, conversation_id, kind, text, created_at, expires_at FROM working_memory WHERE expires_at > ? ${if (conversationId != null) "AND (conversation_id = ? OR conversation_id IS NULL)" else ""} ORDER BY created_at DESC",
        listOf(store.clock.now()) + listOfNotNull(conversationId)) { Item(it.str(0), it.string(1), Kind.valueOf(it.str(2)), it.str(3), it.lng(4), it.lng(5)) }

    fun remove(id: String) = db.execute("DELETE FROM working_memory WHERE id = ?", listOf(id))
    fun count(): Int = (db.queryOne("SELECT COUNT(*) FROM working_memory WHERE expires_at > ?", listOf(store.clock.now())) { it.lng(0) } ?: 0L).toInt()

    /** Promotes an item to persistent memory only on the owner's say-so (the caller supplies the category). */
    fun purgeExpired(): Int {
        val n = (db.queryOne("SELECT COUNT(*) FROM working_memory WHERE expires_at <= ?", listOf(store.clock.now())) { it.lng(0) } ?: 0L).toInt()
        if (n > 0) db.execute("DELETE FROM working_memory WHERE expires_at <= ?", listOf(store.clock.now()))
        return n
    }
}
