package ai.clu.core.history

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.CluStore
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.memory.CLU_SCOPE
import ai.clu.core.memory.Certainty
import ai.clu.core.memory.Memory
import ai.clu.core.memory.MemoryCategory
import ai.clu.core.memory.MemoryRepository
import ai.clu.core.memory.MemorySource
import ai.clu.core.memory.TextAnalysis
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/**
 * The personal archive: memories with historical dates, the People catalog, projects, documents and the links
 * between them, with duplicate and contradiction checks that flag rather than change. Built on [MemoryRepository]
 * (the same memories Clu retrieves in conversation) and the app's SQLite database.
 */
class ArchiveService(private val store: CluStore, private val settings: SettingsStore, val memories: MemoryRepository, val time: CluTime, filesDir: () -> File?) {
    private val db = store.db
    val people = ArchivePeopleRepository(store)
    val projects = ProjectRepository(store)
    val links = LinkRepository(store)
    val conflicts = ConflictRepository(store)
    val documents = DocumentRepository(store, filesDir)
    val dates = ArchiveDates(settings, time)

    data class SaveResult(val memory: Memory, val duplicates: List<Memory>, val conflicts: List<ArchiveConflict>, val hints: List<String>,
                          val ambiguousPeople: Map<String, List<ArchivePerson>>)

    /**
     * Saves a memory and its links. People named in it are linked to their catalog profiles when the name is
     * unambiguous (otherwise reported, never guessed). Likely duplicates and date contradictions are returned and
     * recorded as open conflicts; nothing is merged or overwritten.
     */
    fun saveMemory(m: Memory, projectIds: List<String> = emptyList(), relatedIds: List<String> = emptyList(), documentIds: List<String> = emptyList(),
                   sameEventIds: List<String> = emptyList(), check: Boolean = true): SaveResult {
        val saved = memories.save(m)
        val ambiguous = linkPeople(saved)
        projectIds.forEach { links.link(Kind.MEMORY, saved.id, Kind.PROJECT, it, Relation.ABOUT) }
        relatedIds.filter { it != saved.id }.forEach { links.link(Kind.MEMORY, saved.id, Kind.MEMORY, it, Relation.RELATED) }
        documentIds.forEach { links.link(Kind.MEMORY, saved.id, Kind.DOCUMENT, it, Relation.ATTACHMENT) }
        sameEventIds.filter { it != saved.id }.forEach { links.link(Kind.MEMORY, saved.id, Kind.MEMORY, it, Relation.SAME_EVENT) }
        if (!check) return SaveResult(saved, emptyList(), emptyList(), emptyList(), ambiguous)
        val candidates = candidatesFor(saved)
        val dups = MemoryChecks.duplicates(saved, candidates)
        val sameEvent = links.linked(Kind.MEMORY, saved.id, Kind.MEMORY, Relation.SAME_EVENT).mapNotNull { memories.get(it) }.filter { !it.deleted }
        val opened = MemoryChecks.dateConflicts(saved, sameEvent, candidates).map { o ->
            conflicts.open(Kind.MEMORY, saved.id, o.id, "date", saved.historicalDate().label(), o.historicalDate().label())
        }
        return SaveResult(saved, dups, opened, hints(saved), ambiguous)
    }

    fun hints(m: Memory): List<String> = if (!settings.bool(Settings.SUGGEST_MISSING)) emptyList()
        else MemoryChecks.missingContext(m) { name -> people.resolve(name).isNotEmpty() }

    private fun candidatesFor(m: Memory): List<Memory> {
        val q = (m.title + " " + m.content.take(300)).trim()
        val ranked = if (q.isBlank()) emptyList() else memories.rank(q, scopes = null, limit = 20).map { it.memory }
        val sameTitle = db.query("SELECT id FROM memories WHERE deleted = 0 AND title = ? COLLATE NOCASE AND id <> ? LIMIT 20", listOf(m.title, m.id)) { it.str(0) }.mapNotNull { memories.get(it) }
        return (ranked + sameTitle).filter { it.id != m.id }.distinctBy { it.id }
    }

    private fun linkPeople(m: Memory): Map<String, List<ArchivePerson>> {
        val ambiguous = LinkedHashMap<String, List<ArchivePerson>>()
        for (name in m.people) {
            val found = people.resolve(name)
            when (found.size) { 0 -> {}; 1 -> links.link(Kind.MEMORY, m.id, Kind.PERSON, found[0].id, Relation.INVOLVES); else -> ambiguous[name] = found }
        }
        return ambiguous
    }

    /** Marks two memories as the same event (and flags their dates if they disagree). */
    fun linkSameEvent(aId: String, bId: String): ArchiveConflict? {
        links.link(Kind.MEMORY, aId, Kind.MEMORY, bId, Relation.SAME_EVENT)
        val a = memories.get(aId) ?: return null; val b = memories.get(bId) ?: return null
        if (MemoryChecks.sameDates(a.historicalDate(), b.historicalDate())) return null
        return conflicts.open(Kind.MEMORY, aId, bId, "date", a.historicalDate().label(), b.historicalDate().label())
    }

    /**
     * Merges [otherId] into [keepId]: texts, people, places, tags, links and the earliest "told" date are combined. If
     * their dates disagree, the kept date stays and a conflict is opened instead. The other record is kept in the
     * revision history and tombstoned as merged.
     */
    fun mergeMemories(keepId: String, otherId: String): Memory {
        val keep = memories.get(keepId) ?: error("memory not found"); val other = memories.get(otherId) ?: error("memory not found")
        require(keepId != otherId && !keep.deleted && !other.deleted) { "cannot merge these records" }
        val content = when {
            other.content.isBlank() || keep.content.contains(other.content.trim()) -> keep.content
            keep.content.isBlank() -> other.content
            else -> keep.content.trimEnd() + "\n\n[Merged from \"" + other.title.ifBlank { "another record" } + "\"]\n" + other.content.trim()
        }
        val keepDate = keep.historicalDate(); val otherDate = other.historicalDate()
        val date = if (!keepDate.known) otherDate else keepDate
        if (keepDate.known && otherDate.known && !MemoryChecks.sameDates(keepDate, otherDate))
            conflicts.open(Kind.MEMORY, keepId, otherId, "date", keepDate.label(), otherDate.label())
        val told = listOfNotNull(keep.told, other.told).filter { it.known }.minByOrNull { it.startDate()!! }
        memories.revisionReason = "merged with \"${other.title}\""
        val merged = memories.save(keep.copy(content = content, eventDate = date, told = told ?: keep.told, people = (keep.people + other.people).distinctBy { it.lowercase() },
            places = (keep.places + other.places).distinctBy { it.lowercase() }, tags = (keep.tags + other.tags).distinctBy { it.lowercase() },
            links = (keep.links + other.links).distinct(), importance = maxOf(keep.importance, other.importance), pinned = keep.pinned || other.pinned,
            significance = keep.significance.ifBlank { other.significance },
            certainty = if (keep.certainty == other.certainty) keep.certainty else Certainty.UNCERTAIN))
        links.repoint(Kind.MEMORY, otherId, keepId)
        memories.revisionReason = "merged into \"${keep.title}\""
        memories.save(other.copy(mergedInto = keepId))
        memories.delete(otherId, keepHistory = true) // the merged record stays in the history (provenance)
        linkPeople(merged)
        return merged
    }

    /** Merges two People profiles; differing details become open conflicts instead of being overwritten. */
    fun mergePeople(keepId: String, otherId: String): ArchivePerson {
        val keep = people.get(keepId) ?: error("person not found"); val other = people.get(otherId) ?: error("person not found")
        require(keepId != otherId) { "cannot merge a person with themself" }
        fun pick(field: String, a: String, b: String): String = when {
            a.isBlank() -> b; b.isBlank() || a.equals(b, true) -> a
            else -> { conflicts.open(Kind.PERSON, keepId, otherId, field, a, b); a }
        }
        val first = when {
            !keep.firstKnown.known -> other.firstKnown
            other.firstKnown.known && !MemoryChecks.sameDates(keep.firstKnown, other.firstKnown) -> { conflicts.open(Kind.PERSON, keepId, otherId, "firstKnown", keep.firstKnown.label(), other.firstKnown.label()); keep.firstKnown }
            else -> keep.firstKnown
        }
        val merged = people.save(keep.copy(aliases = (keep.aliases + other.allNames()).filter { !it.equals(keep.name, true) }.distinctBy { it.lowercase() },
            relation = pick("relation", keep.relation, other.relation), background = pick("background", keep.background, other.background),
            notes = listOf(keep.notes, other.notes).filter { it.isNotBlank() }.distinct().joinToString("\n\n"),
            howWeMet = pick("howWeMet", keep.howWeMet, other.howWeMet), lastLeftOff = pick("lastLeftOff", keep.lastLeftOff, other.lastLeftOff),
            contact = pick("contact", keep.contact, other.contact), photoPath = keep.photoPath.ifBlank { other.photoPath }, firstKnown = first,
            importantDates = (keep.importantDates + other.importantDates).distinctBy { it.label.lowercase() + it.date }), reason = "merged with ${other.name}")
        links.repoint(Kind.PERSON, otherId, keepId)
        people.save(other.copy(mergedInto = keepId), reason = "merged into ${keep.name}")
        people.delete(otherId, keepHistory = true)
        return merged
    }

    /**
     * Resolves a conflict: keep both accounts (nothing changes), or use one value for both records. The replaced value
     * stays in the record's revision history.
     */
    fun resolveConflict(id: String, choice: String) {
        val c = conflicts.list().firstOrNull { it.id == id } ?: error("conflict not found")
        when (choice) {
            "both" -> conflicts.close(id, "RESOLVED", "kept both")
            "a", "b" -> {
                val (from, to) = if (choice == "a") c.aId to c.bId else c.bId to c.aId
                if (c.kind == Kind.MEMORY && c.field == "date") {
                    val src = memories.get(from) ?: error("record gone"); val dst = memories.get(to) ?: error("record gone")
                    memories.revisionReason = "date corrected (conflict resolved)"
                    memories.save(dst.copy(eventDate = src.historicalDate(), eventAt = null))
                } else if (c.kind == Kind.PERSON) {
                    // Person conflicts come from a merge: the kept profile is aId; choosing b puts the other value in.
                    if (choice == "b") people.get(c.aId)?.let { p -> people.save(applyPersonField(p, c.field, c.bValue), reason = "conflict resolved") }
                }
                conflicts.close(id, "RESOLVED", "used " + (if (choice == "a") c.aValue else c.bValue))
            }
            "dismiss" -> conflicts.close(id, "DISMISSED", "not a conflict")
            else -> error("unknown choice")
        }
    }

    private fun applyPersonField(p: ArchivePerson, field: String, v: String) = when (field) {
        "relation" -> p.copy(relation = v); "background" -> p.copy(background = v); "howWeMet" -> p.copy(howWeMet = v)
        "lastLeftOff" -> p.copy(lastLeftOff = v); "contact" -> p.copy(contact = v)
        "firstKnown" -> p.copy(firstKnown = DateParser.parse(v) ?: p.firstKnown); else -> p
    }

    /** Memories about a person: linked ones, and ones naming any of their names. Oldest first. */
    fun memoriesOf(p: ArchivePerson): List<Memory> {
        val linked = links.linked(Kind.PERSON, p.id, Kind.MEMORY).mapNotNull { memories.get(it) }
        val named = p.allNames().flatMap { n -> memories.search(n, scope = null, limit = 40) }.filter { m -> m.people.any { Names.score(it, p.allNames()) >= 2 } ||
            p.allNames().any { n -> Regex("(?i)\\b" + Regex.escape(n) + "\\b").containsMatchIn(m.title + " " + m.content) } }
        return chronological((linked + named).filter { !it.deleted }.distinctBy { it.id })
    }

    /** Memories in a project's history: linked ones and ones mentioning its name. Oldest first. */
    fun memoriesOf(p: Project): List<Memory> {
        val linked = links.linked(Kind.PROJECT, p.id, Kind.MEMORY).mapNotNull { memories.get(it) }
        val named = p.allNames().flatMap { n -> memories.search(n, scope = null, limit = 60) }.filter { m ->
            p.allNames().any { n -> Regex("(?i)\\b" + Regex.escape(n) + "\\b").containsMatchIn(m.title + " " + m.content + " " + m.tags.joinToString(" ")) } }
        return chronological((linked + named).filter { !it.deleted }.distinctBy { it.id })
    }

    fun documentsOf(kind: String, id: String): List<ArchiveDocument> = links.linked(kind, id, Kind.DOCUMENT).mapNotNull { documents.get(it) }

    fun chronological(list: List<Memory>) = list.sortedWith(compareBy<Memory>({ it.historicalDate().start ?: "9999" }, { it.createdAt }))

    /** Important dates marked for a reminder that fall on [today] (day and month). */
    fun dueReminders(today: LocalDate = time.today()): List<Pair<ArchivePerson, ImportantDate>> = people.list().flatMap { p ->
        p.importantDates.filter { it.remind && it.date.precision == DatePrecision.DAY && it.date.startDate()!!.let { d -> d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth } }
            .map { p to it } }

    /** Periods with no records between the timeline start and today. */
    fun gaps(minMonths: Int = 3) = ArchiveGaps.find(dates.visible(memories.timeline(scope = null)), time.timelineStart(), time.today(), minMonths)

    data class TimelineFilter(val from: LocalDate? = null, val to: LocalDate? = null, val personId: String? = null, val projectId: String? = null,
                              val category: MemoryCategory? = null, val keyword: String = "")

    /** The timeline: dated memories in event order, filtered; undated ones are listed separately by the caller. */
    fun timeline(f: TimelineFilter = TimelineFilter()): List<Memory> {
        var list = when {
            f.personId != null -> people.get(f.personId)?.let(::memoriesOf).orEmpty()
            f.projectId != null -> projects.get(f.projectId)?.let(::memoriesOf).orEmpty()
            f.keyword.isNotBlank() -> chronological(memories.search(f.keyword, scope = null, limit = 500))
            f.from != null || f.to != null -> memories.inPeriod(f.from ?: LocalDate.of(1, 1, 1), f.to ?: LocalDate.of(9999, 12, 31), scope = null)
            else -> memories.timeline(scope = null)
        }
        if (f.from != null || f.to != null) list = list.filter { it.historicalDate().overlaps(f.from ?: LocalDate.MIN, f.to ?: LocalDate.MAX) }
        if (f.category != null) list = list.filter { it.category == f.category }
        return dates.visible(list.filter { it.historicalDate().known && it.category != MemoryCategory.DIARY })
    }

    // ------------------------------------------------------------------------------------ bulk import

    data class ImportRow(val line: Int, val memory: Memory, val key: String, val projects: List<String>, val duplicateOf: Memory? = null,
                         val alreadyImported: Boolean = false, val hints: List<String> = emptyList())
    data class ImportError(val line: Int, val message: String)
    data class ImportPreview(val source: String, val fingerprint: String, val rows: List<ImportRow>, val errors: List<ImportError>,
                             val newPeople: List<String>, val newProjects: List<String>) {
        val importable get() = rows.filter { !it.alreadyImported && it.duplicateOf == null }
    }
    data class ImportResult(val jobId: String, val imported: Int, val skipped: Int, val status: String, val error: String? = null)

    private val FIELDS = setOf("date", "title", "description", "people", "places", "projects", "tags", "category", "certainty", "importance", "told", "kind", "significance")

    /**
     * Reads a batch of memories without saving anything: one per line ("March 2018 | Met Riya at the fest | details |
     * people: Riya"), CSV with a header row, or JSON. Each row is validated; problems are reported per line.
     */
    fun previewImport(text: String, source: String = "pasted text"): ImportPreview {
        val fingerprint = sha(text)
        val parsed: List<Pair<Int, Map<String, String>>>
        val errors = mutableListOf<ImportError>()
        val t = text.trim().removePrefix("﻿")
        parsed = when {
            t.startsWith("[") || t.startsWith("{") -> runCatching { jsonRows(t) }.getOrElse { errors += ImportError(0, "Not valid JSON: ${it.message?.take(120)}"); emptyList() }
            looksLikeCsv(t) -> csvRows(t, errors)
            else -> lineRows(t, errors)
        }
        val rows = mutableListOf<ImportRow>(); val seen = HashSet<String>()
        val newPeople = LinkedHashSet<String>(); val newProjects = LinkedHashSet<String>()
        for ((line, f) in parsed) {
            val title = f["title"].orEmpty().trim(); val desc = f["description"].orEmpty().trim()
            if (title.isEmpty() && desc.isEmpty()) { errors += ImportError(line, "No title or description."); continue }
            val dateText = f["date"].orEmpty().trim()
            val date = if (dateText.isEmpty()) HistoricalDate.UNKNOWN else DateParser.parse(dateText)
            if (date == null) { errors += ImportError(line, "Couldn't read the date \"$dateText\"."); continue }
            val toldText = f["told"].orEmpty().trim()
            val told = if (toldText.isEmpty()) null else DateParser.parse(toldText)?.takeIf { it.known } ?: run { errors += ImportError(line, "Couldn't read the 'told' date \"$toldText\"."); null } ?: continue
            val category = f["category"]?.takeIf { it.isNotBlank() }?.let { c -> MemoryCategory.selectable.firstOrNull { it.name.equals(c.replace(' ', '_'), true) || it.label.equals(c, true) }
                ?: run { errors += ImportError(line, "Unknown category \"$c\"."); null } ?: continue } ?: MemoryCategory.EXPERIENCE
            val certainty = f["certainty"]?.takeIf { it.isNotBlank() }?.let { c -> Certainty.values().firstOrNull { it.name.equals(c, true) || it.label.equals(c, true) || it.name.startsWith(c.uppercase().take(4)) }
                ?: run { errors += ImportError(line, "Unknown certainty \"$c\" (confirmed, recollection, uncertain)."); null } ?: continue } ?: Certainty.RECOLLECTION
            val importance = f["importance"]?.takeIf { it.isNotBlank() }?.let { i -> i.trim().toIntOrNull()?.takeIf { it in 0..2 } ?: run { errors += ImportError(line, "Importance must be 0, 1 or 2."); null } ?: continue } ?: 0
            val kind = f["kind"].orEmpty().trim().lowercase().let { k -> if (k.isEmpty() || ThreadKindNames.contains(k)) k else { errors += ImportError(line, "Unknown kind \"$k\"."); continue } }
            fun list(k: String) = f[k].orEmpty().split(Regex("[,;]")).map { it.trim() }.filter { it.isNotEmpty() }
            val m = Memory(category = category, title = title.ifEmpty { desc.take(60) }, content = desc, source = MemorySource.IMPORTED, people = list("people"),
                tags = list("tags"), places = list("places"), eventDate = date, told = told, certainty = certainty, importance = importance, threadKind = kind,
                significance = f["significance"].orEmpty().trim())
            val key = sha(Names.norm(m.title) + "|" + date.label() + "|" + Names.norm(desc))
            val already = db.query("SELECT 1 FROM import_keys WHERE key = ?", listOf(key)) { 1 }.isNotEmpty()
            val dup = if (already) null else if (!seen.add(key)) m else MemoryChecks.duplicates(m, candidatesFor(m)).firstOrNull()
            m.people.filter { people.resolve(it).isEmpty() }.forEach { newPeople += it }
            list("projects").filter { projects.resolve(it).isEmpty() }.forEach { newProjects += it }
            rows += ImportRow(line, m, key, list("projects"), dup, already, hints(m))
        }
        return ImportPreview(source, fingerprint, rows, errors.sortedBy { it.line }, newPeople.toList(), newProjects.toList())
    }

    private val ThreadKindNames = ai.clu.core.memory.ThreadKind.all.map { it.first }.toSet()

    /**
     * Saves a previewed batch in transactions of [batch] rows. Already-imported rows (by content key) are skipped, so a
     * batch that was interrupted can be applied again without duplicates. [shouldStop] lets the caller cancel between
     * rows; the progress so far is kept.
     */
    fun applyImport(p: ImportPreview, createPeople: Boolean = true, createProjects: Boolean = true, batch: Int = 25,
                    progress: (done: Int, total: Int) -> Unit = { _, _ -> }, shouldStop: () -> Boolean = { false }): ImportResult {
        val rows = p.importable
        val job = newId(); val now = store.clock.now()
        db.execute("INSERT INTO import_jobs(id, source, fingerprint, total, done, status, started_at, updated_at) VALUES(?,?,?,?,0,'running',?,?)",
            listOf(job, p.source, p.fingerprint, rows.size, now, now))
        if (createPeople) p.newPeople.filter { people.resolve(it).isEmpty() }.forEach { people.save(ArchivePerson(name = it, notes = "Created from an import (${p.source}).")) }
        val projectIds = HashMap<String, String>()
        rows.flatMap { it.projects }.distinctBy { it.lowercase() }.forEach { n ->
            val found = projects.resolve(n)
            when { found.size == 1 -> projectIds[n.lowercase()] = found[0].id
                found.isEmpty() && createProjects -> projectIds[n.lowercase()] = projects.save(Project(name = n)).id }
        }
        var done = 0; var skipped = 0
        try {
            for (chunk in rows.chunked(batch)) {
                if (shouldStop()) { finish(job, done, "interrupted", null); return ImportResult(job, done, skipped, "interrupted") }
                db.transaction {
                    for (r in chunk) {
                        if (db.query("SELECT 1 FROM import_keys WHERE key = ?", listOf(r.key)) { 1 }.isNotEmpty()) { skipped++; continue }
                        val saved = saveMemory(r.memory, projectIds = r.projects.mapNotNull { projectIds[it.lowercase()] }, check = false).memory
                        db.execute("INSERT INTO import_keys(key, record_id, job_id) VALUES(?,?,?)", listOf(r.key, saved.id, job))
                        done++
                    }
                    db.execute("UPDATE import_jobs SET done = ?, updated_at = ? WHERE id = ?", listOf(done, store.clock.now(), job))
                }
                progress(done + skipped, rows.size)
            }
        } catch (e: Exception) {
            finish(job, done, "failed", e.message ?: e.javaClass.simpleName)
            return ImportResult(job, done, skipped, "failed", e.message)
        }
        finish(job, done, "done", null)
        return ImportResult(job, done, skipped, "done")
    }

    private fun finish(job: String, done: Int, status: String, error: String?) =
        db.execute("UPDATE import_jobs SET done = ?, status = ?, error = ?, updated_at = ? WHERE id = ?", listOf(done, status, error, store.clock.now(), job))

    /** Import jobs that did not finish (to offer resuming). */
    fun unfinishedImports(): List<Triple<String, String, String>> = db.query("SELECT id, source, status FROM import_jobs WHERE status IN ('running','interrupted','failed') ORDER BY started_at DESC") {
        Triple(it.str(0), it.str(1), it.str(2)) }

    private fun looksLikeCsv(t: String): Boolean {
        val header = t.lineSequence().first().lowercase().split(',').map { it.trim().trim('"') }
        return header.size >= 2 && header.count { it in FIELDS } >= 2
    }

    private fun lineRows(t: String, errors: MutableList<ImportError>): List<Pair<Int, Map<String, String>>> = t.lines().mapIndexedNotNull { i, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
        val f = HashMap<String, String>()
        if (line.contains('|')) {
            val parts = line.split('|').map { it.trim() }
            var pos = 0
            for (part in parts) {
                val kv = Regex("^([A-Za-z]+)\\s*:\\s*(.*)$").find(part)?.takeIf { it.groupValues[1].lowercase() in FIELDS + setOf("person", "place", "project", "tag") }
                if (kv != null) {
                    val k = when (val key = kv.groupValues[1].lowercase()) { "person" -> "people"; "place" -> "places"; "project" -> "projects"; "tag" -> "tags"; else -> key }
                    f[k] = kv.groupValues[2]
                } else { when (pos) { 0 -> f["date"] = part; 1 -> f["title"] = part; 2 -> f["description"] = part; else -> f["description"] = (f["description"].orEmpty() + " " + part).trim() }; pos++ }
            }
        } else {
            val m = Regex("^(.{3,40}?)\\s*[:–—-]\\s+(.+)$").find(line)
            if (m != null && DateParser.parse(m.groupValues[1])?.known == true) { f["date"] = m.groupValues[1]; f["title"] = m.groupValues[2] }
            else { errors += ImportError(i + 1, "Couldn't read this line. Use: date | title | details | people: …"); return@mapIndexedNotNull null }
        }
        if (f["title"].isNullOrBlank()) { errors += ImportError(i + 1, "No title (the second column)."); return@mapIndexedNotNull null }
        (i + 1) to f
    }

    private fun csvRows(t: String, errors: MutableList<ImportError>): List<Pair<Int, Map<String, String>>> {
        val records = Csv.parse(t)
        if (records.isEmpty()) return emptyList()
        val header = records.first().map { it.trim().lowercase() }
        header.filter { it.isNotEmpty() && it !in FIELDS }.forEach { errors += ImportError(1, "Unknown column \"$it\" (ignored).") }
        return records.drop(1).mapIndexedNotNull { i, r -> if (r.all { it.isBlank() }) null else (i + 2) to header.zip(r).filter { it.first in FIELDS }.toMap() }
    }

    private fun jsonRows(t: String): List<Pair<Int, Map<String, String>>> {
        val el = CluJson.parseToJsonElement(t)
        val arr = when { el is JsonArray -> el; el is JsonObject && el["memories"] is JsonArray -> el["memories"]!!.jsonArray; else -> error("expected a list of memories") }
        return arr.mapIndexed { i, e -> (i + 1) to e.jsonObject.mapNotNull { (k, v) -> val key = k.lowercase(); if (key !in FIELDS) null else key to when (v) {
            is JsonArray -> v.joinToString(", ") { it.jsonPrimitive.content }; is JsonPrimitive -> v.content; else -> v.toString() } }.toMap() }
    }

    // ------------------------------------------------------------------------------------ backup

    @Serializable data class BundleDocument(val doc: ArchiveDocument, val text: String, val fileBase64: String? = null)
    @Serializable data class ArchiveBundle(val format: String = FORMAT, val version: Int = 1, val exportedAt: Long, val timeline: Map<String, String> = emptyMap(),
                                           val memories: List<Memory>, val people: List<ArchivePerson>, val projects: List<Project>,
                                           val documents: List<BundleDocument>, val links: List<ArchiveLink>, val conflicts: List<ArchiveConflict>)

    /** The whole archive as one JSON file: dates, people, projects, documents (with their text, and optionally the files), links and conflicts. */
    fun exportBundle(includeFiles: Boolean = false): String {
        val b = ArchiveBundle(exportedAt = store.clock.now(),
            timeline = listOf(Settings.TIMELINE_START, Settings.SIMULATED_TIME, Settings.SIMULATED_DATE, Settings.TOLD_DEFAULT).associateWith { settings.string(it) },
            memories = memories.list(scope = null, limit = 1_000_000).filter { it.category != MemoryCategory.DIARY },
            people = people.list(), projects = projects.list(),
            documents = documents.list().map { d -> BundleDocument(d.copy(storedPath = ""), documents.text(d.id),
                if (includeFiles) documents.fileBytes(d)?.let { java.util.Base64.getEncoder().encodeToString(it) } else null) },
            links = links.all(), conflicts = conflicts.list())
        return CluJson.encodeToString(ArchiveBundle.serializer(), b)
    }

    data class BundlePreview(val bundle: ArchiveBundle, val newMemories: Int, val existingMemories: Int, val newPeople: Int, val newProjects: Int, val newDocuments: Int)

    /** Validates a backup file and counts what it would add (existing records are never overwritten). */
    fun previewBundle(json: String): BundlePreview {
        val b = try { CluJson.decodeFromString(ArchiveBundle.serializer(), json) } catch (e: Exception) { throw IllegalArgumentException("This isn't a Clu archive backup (or it is damaged).") }
        require(b.format == FORMAT) { "This isn't a Clu archive backup." }
        require(b.version <= 1) { "This backup was made by a newer version of Clu." }
        val newM = b.memories.count { memories.get(it.id) == null }
        return BundlePreview(b, newM, b.memories.size - newM, b.people.count { people.get(it.id) == null }, b.projects.count { projects.get(it.id) == null },
            b.documents.count { documents.get(it.doc.id) == null && documents.bySha(it.doc.sha256) == null })
    }

    /** Restores a backup: adds what is missing, keeps everything already here, and re-links. */
    fun restoreBundle(p: BundlePreview, applyTimelineSettings: Boolean = false): Int {
        val b = p.bundle; var added = 0
        b.people.filter { people.get(it.id) == null }.forEach { people.save(it, reason = "restored"); added++ }
        b.projects.filter { projects.get(it.id) == null }.forEach { projects.save(it, reason = "restored"); added++ }
        b.memories.filter { memories.get(it.id) == null && !it.deleted }.forEach { memories.save(it.copy(hlc = "", origin = "")); added++ }
        b.documents.filter { documents.get(it.doc.id) == null && documents.bySha(it.doc.sha256) == null }.forEach { d ->
            documents.restore(d.doc, d.text, d.fileBase64?.let { java.util.Base64.getDecoder().decode(it) }); added++ }
        b.links.forEach { links.restore(it) }
        b.conflicts.forEach { conflicts.restore(it) }
        if (applyTimelineSettings) b.timeline.forEach { (k, v) -> settings.set(k, v) }
        return added
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)

    companion object { const val FORMAT = "clu-archive" }
}

/** RFC 4180 CSV (quoted fields, doubled quotes, newlines inside quotes). */
object Csv {
    fun parse(text: String): List<List<String>> {
        val out = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val f = StringBuilder(); var q = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                q && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { f.append('"'); i++ }
                c == '"' -> q = !q
                !q && c == ',' -> { row += f.toString(); f.clear() }
                !q && (c == '\n' || c == '\r') -> { if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++; row += f.toString(); f.clear(); out += row; row = mutableListOf() }
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row += f.toString(); out += row }
        return out
    }
}
