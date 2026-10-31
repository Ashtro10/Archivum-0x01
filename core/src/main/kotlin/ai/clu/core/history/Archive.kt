package ai.clu.core.history

import ai.clu.core.agent.Phonetics
import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.CluStore
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.memory.Memory
import ai.clu.core.memory.MemoryRepository
import ai.clu.core.memory.TextAnalysis
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

// ------------------------------------------------------------------------------------------------ records

@Serializable
data class ImportantDate(val label: String, val date: HistoricalDate, val remind: Boolean = false)

/**
 * Someone the owner knows (the People catalog). Separate from the security "people" who may talk to Clu: a catalog
 * entry grants no access. Only what the owner supplied is stored; nothing is inferred about anyone.
 */
@Serializable
data class ArchivePerson(
    val id: String = newId(),
    val name: String,
    val aliases: List<String> = emptyList(),
    /** A managed local copy of a photo (optional). */
    val photoPath: String = "",
    val relation: String = "",
    val background: String = "",
    val notes: String = "",
    val firstKnown: HistoricalDate = HistoricalDate.UNKNOWN,
    val importantDates: List<ImportantDate> = emptyList(),
    val howWeMet: String = "",
    val lastLeftOff: String = "",
    /** Only when the owner chooses to add it. */
    val contact: String = "",
    val mergedInto: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun allNames(): List<String> = (listOf(name) + aliases).filter { it.isNotBlank() }.distinctBy { it.lowercase() }
}

@Serializable
data class Project(
    val id: String = newId(),
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String = "",
    /** active | paused | done | abandoned */
    val status: String = "active",
    val started: HistoricalDate = HistoricalDate.UNKNOWN,
    val ended: HistoricalDate = HistoricalDate.UNKNOWN,
    val notes: String = "",
    val mergedInto: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun allNames(): List<String> = (listOf(name) + aliases).filter { it.isNotBlank() }.distinctBy { it.lowercase() }
}

@Serializable
data class ArchiveLink(val id: String = newId(), val aKind: String, val aId: String, val bKind: String, val bId: String, val relation: String,
                       val note: String = "", val createdAt: Long = 0)

/** Two records that disagree. Both stay as they are until the owner decides. */
@Serializable
data class ArchiveConflict(val id: String = newId(), val kind: String, val aId: String, val bId: String, val field: String, val aValue: String,
                           val bValue: String, val status: String = "OPEN", val resolution: String = "", val createdAt: Long = 0, val resolvedAt: Long? = null)

object Kind { const val MEMORY = "memory"; const val PERSON = "person"; const val PROJECT = "project"; const val DOCUMENT = "document" }
object Relation {
    const val INVOLVES = "involves"; const val ABOUT = "about"; const val SAME_EVENT = "same_event"; const val SAME_STORY = "same_story"
    const val RELATED = "related"; const val SUPPORTS = "supports"; const val ATTACHMENT = "attachment"
}

// ------------------------------------------------------------------------------------------------ names

/** Matching names, aliases and spelling variants. */
object Names {
    fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun editDistance(a: String, b: String): Int = ai.clu.core.agent.ResearchRunner.editDistance(a, b)

    /** 3 = same name or alias, 2 = one of its words (first or last name), 1 = a likely spelling variant, 0 = no. */
    fun score(query: String, names: List<String>): Int {
        val q = norm(query); if (q.isEmpty()) return 0
        var best = 0
        for (n in names.map(::norm).filter { it.isNotEmpty() }) {
            if (n == q) return 3
            val words = n.split(' ')
            if (q.split(' ').size == 1 && q in words && q.length >= 3) best = maxOf(best, 2)
            if (words.size > 1 && q.split(' ').all { it in words }) best = maxOf(best, 2)
            // Sounding alike counts only with the same first letter or a skeleton of 3+ sounds (Sara/Sarah, Kristina/Christina),
            // never for short names that merely share one consonant class (Leah and Riya are different people).
            val soundAlike = q.length >= 4 && Phonetics.similarity(q, n) >= 0.9 && (q.first() == n.first() || Phonetics.key(q).length >= 3)
            val close = (q.length >= 5 && editDistance(q, n) <= 1) || soundAlike ||
                words.any { w -> w.length >= 5 && q.length >= 5 && editDistance(q, w) <= 1 }
            if (close) best = maxOf(best, 1)
        }
        return best
    }
}

// ------------------------------------------------------------------------------------------------ repositories

private inline fun <reified T> CluStore.saveJson(table: String, id: String, name: String, value: T, json: String, createdAt: Long) {
    val now = clock.now()
    db.transaction {
        val hlc = stamp(table, id)
        db.execute("""INSERT INTO $table(id, name, json, created_at, updated_at, deleted, hlc, origin) VALUES(?,?,?,?,?,0,?,?)
            ON CONFLICT(id) DO UPDATE SET name=excluded.name, json=excluded.json, updated_at=excluded.updated_at, deleted=0, hlc=excluded.hlc, origin=excluded.origin""",
            listOf(id, name, json, if (createdAt > 0) createdAt else now, now, hlc, deviceId))
    }
    notifyChanged(table)
}

class ArchivePeopleRepository(private val store: CluStore) {
    private val db = store.db

    fun list(includeMerged: Boolean = false): List<ArchivePerson> =
        db.query("SELECT json FROM $TABLE WHERE deleted = 0 ORDER BY name COLLATE NOCASE") { CluJson.decodeFromString(ArchivePerson.serializer(), it.str(0)) }
            .filter { includeMerged || it.mergedInto == null }

    fun get(id: String): ArchivePerson? = db.query("SELECT json FROM $TABLE WHERE id = ? AND deleted = 0", listOf(id)) {
        CluJson.decodeFromString(ArchivePerson.serializer(), it.str(0)) }.firstOrNull()

    fun save(p: ArchivePerson, reason: String = "edited"): ArchivePerson {
        require(p.name.isNotBlank()) { "a person needs a name" }
        val now = store.clock.now()
        val old = get(p.id)
        if (old != null && old.copy(updatedAt = 0) != p.copy(updatedAt = 0, createdAt = old.createdAt)) revision(store, Kind.PERSON, p.id, CluJson.encodeToString(ArchivePerson.serializer(), old), reason)
        val row = p.copy(name = p.name.trim(), aliases = p.aliases.map { it.trim() }.filter { it.isNotEmpty() && !it.equals(p.name.trim(), true) }.distinctBy { it.lowercase() },
            createdAt = old?.createdAt ?: (if (p.createdAt > 0) p.createdAt else now), updatedAt = now)
        store.saveJson(TABLE, row.id, row.name, row, CluJson.encodeToString(ArchivePerson.serializer(), row), row.createdAt)
        return row
    }

    /** Deletes a profile. Its revision history goes too, unless [keepHistory] (a merge keeps provenance). */
    fun delete(id: String, keepHistory: Boolean = false) {
        db.transaction {
            if (keepHistory) get(id)?.let { revision(store, Kind.PERSON, id, CluJson.encodeToString(ArchivePerson.serializer(), it), "merged") }
            else db.execute("DELETE FROM record_revisions WHERE kind = ? AND record_id = ?", listOf(Kind.PERSON, id))
            val hlc = store.stamp(TABLE, id)
            db.execute("UPDATE $TABLE SET deleted = 1, updated_at = ?, hlc = ?, origin = ? WHERE id = ?", listOf(store.clock.now(), hlc, store.deviceId, id))
            db.execute("DELETE FROM archive_links WHERE (a_kind = 'person' AND a_id = ?) OR (b_kind = 'person' AND b_id = ?)", listOf(id, id))
        }
        store.notifyChanged(TABLE)
    }

    /** People matching [name] (name, alias, first/last name, spelling variant), best first. */
    fun find(name: String): List<Pair<ArchivePerson, Int>> =
        list().map { it to Names.score(name, it.allNames()) }.filter { it.second > 0 }.sortedByDescending { it.second }

    /**
     * The person [name] refers to, or the candidates when it could be more than one. Exact name/alias matches win over
     * partial ones; two equally good matches are ambiguous (the caller asks which).
     */
    fun resolve(name: String): List<ArchivePerson> {
        val found = find(name); if (found.isEmpty()) return emptyList()
        val top = found.first().second
        return found.filter { it.second == top }.map { it.first }
    }

    /** Profiles that may be the same person as [p] (same or similar names / aliases). */
    fun duplicatesOf(p: ArchivePerson): List<ArchivePerson> = list().filter { it.id != p.id && p.allNames().any { n -> Names.score(n, it.allNames()) >= 2 ||
        (Names.score(n, it.allNames()) == 1 && n.length >= 5) } }

    companion object { const val TABLE = "archive_people" }
}

class ProjectRepository(private val store: CluStore) {
    private val db = store.db
    fun list(includeMerged: Boolean = false): List<Project> = db.query("SELECT json FROM $TABLE WHERE deleted = 0 ORDER BY name COLLATE NOCASE") {
        CluJson.decodeFromString(Project.serializer(), it.str(0)) }.filter { includeMerged || it.mergedInto == null }
    fun get(id: String): Project? = db.query("SELECT json FROM $TABLE WHERE id = ? AND deleted = 0", listOf(id)) { CluJson.decodeFromString(Project.serializer(), it.str(0)) }.firstOrNull()
    fun save(p: Project, reason: String = "edited"): Project {
        require(p.name.isNotBlank()) { "a project needs a name" }
        val now = store.clock.now(); val old = get(p.id)
        if (old != null && old.copy(updatedAt = 0) != p.copy(updatedAt = 0, createdAt = old.createdAt)) revision(store, Kind.PROJECT, p.id, CluJson.encodeToString(Project.serializer(), old), reason)
        val row = p.copy(name = p.name.trim(), createdAt = old?.createdAt ?: (if (p.createdAt > 0) p.createdAt else now), updatedAt = now)
        store.saveJson(TABLE, row.id, row.name, row, CluJson.encodeToString(Project.serializer(), row), row.createdAt)
        return row
    }
    fun delete(id: String) {
        db.transaction {
            db.execute("DELETE FROM record_revisions WHERE kind = ? AND record_id = ?", listOf(Kind.PROJECT, id))
            val hlc = store.stamp(TABLE, id)
            db.execute("UPDATE $TABLE SET deleted = 1, updated_at = ?, hlc = ?, origin = ? WHERE id = ?", listOf(store.clock.now(), hlc, store.deviceId, id))
            db.execute("DELETE FROM archive_links WHERE (a_kind = 'project' AND a_id = ?) OR (b_kind = 'project' AND b_id = ?)", listOf(id, id))
        }
        store.notifyChanged(TABLE)
    }
    fun resolve(name: String): List<Project> {
        val found = list().map { it to Names.score(name, it.allNames()) }.filter { it.second >= 2 || (it.second == 1 && name.length >= 5) }
        if (found.isEmpty()) return emptyList()
        val top = found.maxOf { it.second }
        return found.filter { it.second == top }.map { it.first }
    }
    companion object { const val TABLE = "archive_projects" }
}

private fun revision(store: CluStore, kind: String, id: String, json: String, reason: String) =
    store.db.execute("INSERT INTO record_revisions(id, kind, record_id, json, reason, created_at) VALUES(?,?,?,?,?,?)",
        listOf(newId(), kind, id, json, reason, store.clock.now()))

class LinkRepository(private val store: CluStore) {
    private val db = store.db

    /** Links two records (idempotent; direction-insensitive for the same relation). */
    fun link(aKind: String, aId: String, bKind: String, bId: String, relation: String, note: String = ""): ArchiveLink {
        existing(aKind, aId, bKind, bId, relation)?.let { return it }
        val l = ArchiveLink(newId(), aKind, aId, bKind, bId, relation, note, store.clock.now())
        db.execute("INSERT INTO archive_links(id, a_kind, a_id, b_kind, b_id, relation, note, created_at, deleted) VALUES(?,?,?,?,?,?,?,?,0)",
            listOf(l.id, aKind, aId, bKind, bId, relation, note, l.createdAt))
        store.notifyChanged(TABLE)
        return l
    }

    private fun existing(aKind: String, aId: String, bKind: String, bId: String, relation: String): ArchiveLink? =
        db.query("SELECT $COLS FROM archive_links WHERE deleted = 0 AND relation = ? AND ((a_kind = ? AND a_id = ? AND b_kind = ? AND b_id = ?) OR " +
            "(a_kind = ? AND a_id = ? AND b_kind = ? AND b_id = ?))", listOf(relation, aKind, aId, bKind, bId, bKind, bId, aKind, aId), ::read).firstOrNull()

    fun unlink(id: String) { db.execute("DELETE FROM archive_links WHERE id = ?", listOf(id)); store.notifyChanged(TABLE) }

    fun all(): List<ArchiveLink> = db.query("SELECT $COLS FROM archive_links WHERE deleted = 0", emptyList(), ::read)

    /** Links touching a record, either direction. */
    fun of(kind: String, id: String): List<ArchiveLink> =
        db.query("SELECT $COLS FROM archive_links WHERE deleted = 0 AND ((a_kind = ? AND a_id = ?) OR (b_kind = ? AND b_id = ?))", listOf(kind, id, kind, id), ::read)

    /** Ids of records of [otherKind] linked to the record, optionally only with [relation]. */
    fun linked(kind: String, id: String, otherKind: String, relation: String? = null): List<String> = of(kind, id)
        .filter { relation == null || it.relation == relation }
        .mapNotNull { l -> when { l.aKind == kind && l.aId == id && l.bKind == otherKind -> l.bId; l.bKind == kind && l.bId == id && l.aKind == otherKind -> l.aId; else -> null } }
        .distinct()

    /** Moves every link of one record to another (after a merge). */
    fun repoint(kind: String, fromId: String, toId: String) {
        of(kind, fromId).forEach { l ->
            val a = if (l.aKind == kind && l.aId == fromId) toId else l.aId
            val b = if (l.bKind == kind && l.bId == fromId) toId else l.bId
            db.execute("DELETE FROM archive_links WHERE id = ?", listOf(l.id))
            if (!(l.aKind == l.bKind && a == b)) link(l.aKind, a, l.bKind, b, l.relation, l.note)
        }
    }

    fun restore(l: ArchiveLink) { if (existing(l.aKind, l.aId, l.bKind, l.bId, l.relation) == null)
        db.execute("INSERT OR IGNORE INTO archive_links(id, a_kind, a_id, b_kind, b_id, relation, note, created_at, deleted) VALUES(?,?,?,?,?,?,?,?,0)",
            listOf(l.id, l.aKind, l.aId, l.bKind, l.bId, l.relation, l.note, l.createdAt)) }

    private fun read(r: ai.clu.core.db.SqlRow) = ArchiveLink(r.str(0), r.str(1), r.str(2), r.str(3), r.str(4), r.str(5), r.str(6), r.lng(7))
    companion object { const val TABLE = "archive_links"; private const val COLS = "id, a_kind, a_id, b_kind, b_id, relation, note, created_at" }
}

class ConflictRepository(private val store: CluStore) {
    private val db = store.db

    /** Records a disagreement once (the same pair and field is not flagged twice while open). */
    fun open(kind: String, aId: String, bId: String, field: String, aValue: String, bValue: String): ArchiveConflict {
        list("OPEN").firstOrNull { it.kind == kind && it.field == field && setOf(it.aId, it.bId) == setOf(aId, bId) }?.let { return it }
        val c = ArchiveConflict(newId(), kind, aId, bId, field, aValue, bValue, "OPEN", "", store.clock.now())
        db.execute("INSERT INTO archive_conflicts(id, kind, a_id, b_id, field, a_value, b_value, status, resolution, created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            listOf(c.id, kind, aId, bId, field, aValue, bValue, c.status, "", c.createdAt))
        store.notifyChanged(TABLE)
        return c
    }

    fun list(status: String? = null): List<ArchiveConflict> =
        db.query("SELECT id, kind, a_id, b_id, field, a_value, b_value, status, resolution, created_at, resolved_at FROM archive_conflicts" +
            (if (status != null) " WHERE status = ?" else "") + " ORDER BY created_at DESC", listOfNotNull(status)) {
            ArchiveConflict(it.str(0), it.str(1), it.str(2), it.str(3), it.str(4), it.str(5), it.str(6), it.str(7), it.str(8), it.lng(9), it.long(10)) }

    fun involving(id: String): List<ArchiveConflict> = list("OPEN").filter { it.aId == id || it.bId == id }

    fun close(id: String, status: String, resolution: String) {
        db.execute("UPDATE archive_conflicts SET status = ?, resolution = ?, resolved_at = ? WHERE id = ?", listOf(status, resolution, store.clock.now(), id))
        store.notifyChanged(TABLE)
    }

    fun restore(c: ArchiveConflict) = db.execute("INSERT OR IGNORE INTO archive_conflicts(id, kind, a_id, b_id, field, a_value, b_value, status, resolution, created_at, resolved_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        listOf(c.id, c.kind, c.aId, c.bId, c.field, c.aValue, c.bValue, c.status, c.resolution, c.createdAt, c.resolvedAt))

    companion object { const val TABLE = "archive_conflicts" }
}

// ------------------------------------------------------------------------------------------------ dates and availability

/**
 * When Clu learned something, in its timeline: the recorded "told" date; otherwise, when [Settings.TOLD_DEFAULT] is
 * "event", the event date (but never before Clu's timeline began); otherwise unknown.
 */
class ArchiveDates(private val settings: SettingsStore, private val time: CluTime) {
    fun told(m: Memory): HistoricalDate? {
        m.told?.takeIf { it.known }?.let { return it }
        if (settings.string(Settings.TOLD_DEFAULT) != "event") return null
        val e = m.historicalDate().takeIf { it.known } ?: return null
        val start = time.timelineStart()
        return if (e.endDate()!!.isBefore(start)) HistoricalDate.day(start, approx = false).copy(precision = DatePrecision.DAY) else e
    }

    /**
     * Whether Clu knew [m] on [cutoff] (historical perspective). It must have been told by then and the event must have
     * happened by then; an undated record is not assumed to have been known at any earlier date.
     */
    fun availableBy(m: Memory, cutoff: LocalDate): Boolean {
        val t = told(m) ?: return false
        if (t.startDate()!!.isAfter(cutoff)) return false
        val e = m.historicalDate()
        return !e.known || !e.startDate()!!.isAfter(cutoff)
    }

    /** Memories usable now: all of them, or only those known by the perspective date. */
    fun visible(list: List<Memory>): List<Memory> = time.perspective()?.let { c -> list.filter { availableBy(it, c) } } ?: list
}

/** Periods with little or no recorded information (gaps in the archive, not proof that nothing happened). */
object ArchiveGaps {
    data class Gap(val from: YearMonth, val to: YearMonth, val yearLevelRecords: Int) {
        val months: Int get() = (to.year - from.year) * 12 + to.monthValue - from.monthValue + 1
        fun label(): String {
            val m = { y: YearMonth -> y.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + y.year }
            return if (from == to) m(from) else "${m(from)} – ${m(to)}"
        }
    }

    /** Months between [from] and [to] with no record dated to that month (or to a range of ≤ 3 months covering it), merged into spans of at least [minMonths]. */
    fun find(memories: List<Memory>, from: LocalDate, to: LocalDate, minMonths: Int = 3): List<Gap> {
        if (to.isBefore(from)) return emptyList()
        val covered = HashSet<YearMonth>(); val yearOnly = HashMap<Int, Int>()
        for (m in memories) {
            val d = m.historicalDate(); val s = d.startDate() ?: continue; val e = d.endDate() ?: s
            when {
                d.precision == DatePrecision.YEAR -> yearOnly.merge(s.year, 1, Int::plus)
                java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(s), YearMonth.from(e)) <= 2 -> {
                    var y = YearMonth.from(s); while (!y.isAfter(YearMonth.from(e))) { covered += y; y = y.plusMonths(1) }
                }
            }
        }
        val out = mutableListOf<Gap>()
        var y = YearMonth.from(from); var start: YearMonth? = null
        val end = YearMonth.from(to)
        while (!y.isAfter(end)) {
            if (y !in covered) { if (start == null) start = y }
            else if (start != null) { out += Gap(start, y.minusMonths(1), 0); start = null }
            y = y.plusMonths(1)
        }
        if (start != null) out += Gap(start, end, 0)
        return out.filter { it.months >= minMonths }.map { g -> g.copy(yearLevelRecords = (g.from.year..g.to.year).sumOf { yearOnly[it] ?: 0 }) }
    }
}

/** Likely duplicates and contradictions between memories. Nothing is merged or changed here; the owner decides. */
object MemoryChecks {
    private val SMALL = setOf("a", "an", "the", "and", "or", "of", "to", "in", "on", "at", "with", "for", "my", "i", "we", "was", "is")
    /** Words for comparing records; numbers of any length count ("Event 2" and "Event 30" are different records). */
    private fun words(s: String) = Names.norm(s).split(' ').filter { it.isNotEmpty() && it !in SMALL }.map(TextAnalysis::stem).toSet()
    private fun jaccard(a: Set<String>, b: Set<String>) = if (a.isEmpty() || b.isEmpty()) 0.0 else (a intersect b).size.toDouble() / (a union b).size

    fun sameDates(a: HistoricalDate, b: HistoricalDate) = !a.known || !b.known || a.overlaps(b.startDate()!!, b.endDate()!!)

    /** Existing memories that look like the same record as [m]: nearly the same title or text, at compatible dates. */
    fun duplicates(m: Memory, candidates: List<Memory>): List<Memory> = candidates.filter { o ->
        o.id != m.id && !o.deleted && sameDates(m.historicalDate(), o.historicalDate()) && (
            (Names.norm(o.title).isNotEmpty() && Names.norm(o.title) == Names.norm(m.title)) ||
                (jaccard(words(o.title), words(m.title)) >= 0.6 && jaccard(words(o.title + " " + o.content), words(m.title + " " + m.content)) >= 0.75))
    }

    /** Memories that describe the same event (linked, or nearly the same title with a shared person) but give different dates. */
    fun dateConflicts(m: Memory, sameEvent: List<Memory>, candidates: List<Memory>): List<Memory> {
        val d = m.historicalDate(); if (!d.known) return emptyList()
        val similar = candidates.filter { o -> o.id != m.id && !o.deleted && (Names.norm(o.title).isNotEmpty() && Names.norm(o.title) == Names.norm(m.title) ||
            jaccard(words(o.title), words(m.title)) >= 0.6 && words(o.title).intersect(words(m.title)).size >= 2) &&
            (m.people.isEmpty() || o.people.any { p -> m.people.any { it.equals(p, true) } }) }
        return (sameEvent + similar).distinctBy { it.id }.filter { o -> o.historicalDate().known && !sameDates(d, o.historicalDate()) }
    }

    /** Hints for an incomplete memory (never required to save). */
    fun missingContext(m: Memory, knownPeople: (String) -> Boolean): List<String> = buildList {
        if (!m.historicalDate().known) add("No date yet — even a year, a range or \"around\" helps place it on the timeline.")
        m.people.filter { !knownPeople(it) }.forEach { add("$it isn't in your People catalog yet.") }
        if (m.content.isBlank() && m.summary.isNullOrBlank()) add("No description — a sentence or two makes it easier for Clu to recall.")
        if (m.people.isEmpty() && Regex("(?i)\\b(?:with|met|told|friend|girl|guy|she|he|they)\\b").containsMatchIn(m.content + " " + m.title))
            add("It mentions someone but no person is linked.")
    }
}
