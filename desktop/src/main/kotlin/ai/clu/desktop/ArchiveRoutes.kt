package ai.clu.desktop

import ai.clu.core.CluCore
import ai.clu.core.history.ArchivePerson
import ai.clu.core.history.DateParser
import ai.clu.core.history.DocumentImportException
import ai.clu.core.history.HistoricalDate
import ai.clu.core.memory.Certainty
import ai.clu.core.memory.Memory
import ai.clu.core.memory.MemoryCategory
import ai.clu.core.memory.MemorySource
import kotlinx.serialization.json.*

/**
 * The personal archive over the local API: the same repositories and rules as the phone's archive screens
 * (historical dates never default to today; duplicates, conflicts and missing context are reported, not silently fixed;
 * memory suggestions are saved only when approved).
 *
 * | Method | Path | |
 * |---|---|---|
 * | GET, POST | /v1/memories | `?q=` search, `?category=`; create `{title, content, when, people, places, tags, category, certainty, importance}` |
 * | PUT, DELETE | /v1/memories/{id} | edit (same fields) / delete |
 * | GET | /v1/timeline | dated memories, oldest first |
 * | GET | /v1/dates/parse?q= | how a date is understood ("around March 2018"), for the editor's live reading |
 * | GET, POST | /v1/people | list / create `{name, relation, aliases, notes}` |
 * | GET, POST | /v1/documents | list / upload (raw bytes, `X-File-Name` header, up to 50 MB) |
 * | GET | /v1/documents/search?q= | passages |
 * | GET | /v1/suggestions | memory suggestions waiting for approval |
 * | POST | /v1/suggestions/{id}/approve, /reject | approve (optionally `{text}`) / reject (erases the suggestion) |
 */
class ArchiveRoutes(private val core: CluCore) {
    private val archive get() = core.archive

    fun handle(c: Call): Boolean {
        val memoryId = Regex("^/v1/memories/([^/]+)$").find(c.path)?.groupValues?.get(1)
        val suggestion = Regex("^/v1/suggestions/([^/]+)/(approve|reject)$").find(c.path)
        when {
            c.path == "/v1/memories" && c.method == "GET" -> {
                val q = c.query("q")?.trim().orEmpty()
                val cat = c.query("category")?.let { runCatching { MemoryCategory.valueOf(it) }.getOrNull() }
                val list = if (q.isNotEmpty()) core.memories.search(q, null).filter { cat == null || it.category == cat } else core.memories.list(null, cat)
                c.ok(buildJsonArray { list.filter { it.category != MemoryCategory.DIARY }.forEach { add(memory(it)) } })
            }
            c.path == "/v1/memories" && c.method == "POST" -> save(c, null)
            memoryId != null && c.method == "PUT" -> save(c, core.memories.get(memoryId)?.takeIf { !it.deleted } ?: return true.also { c.fail(404, "no such memory") })
            memoryId != null && c.method == "DELETE" -> {
                if (core.memories.get(memoryId)?.deleted != false) c.fail(404, "no such memory") else { core.memory.forget(memoryId); c.ok() }
            }
            c.path == "/v1/dates/parse" && c.method == "GET" -> {
                val q = c.query("q")?.trim().orEmpty()
                val d = if (q.isEmpty()) HistoricalDate.UNKNOWN else DateParser.parse(q)
                c.ok(buildJsonObject { put("understood", d != null); put("label", d?.takeIf { it.known }?.label() ?: ""); put("precision", d?.precision?.name ?: "") })
            }
            c.path == "/v1/timeline" && c.method == "GET" -> c.ok(buildJsonArray { archive.timeline().forEach { add(memory(it)) } })
            c.path == "/v1/people" && c.method == "GET" -> c.ok(buildJsonArray { archive.people.list().forEach { add(person(it)) } })
            c.path == "/v1/people" && c.method == "POST" -> {
                val name = c.str("name")?.trim().orEmpty()
                if (name.isEmpty()) return true.also { c.fail(400, "name is required") }
                val p = ArchivePerson(name = name.take(120), relation = c.str("relation")?.trim()?.take(120).orEmpty(),
                    aliases = c.strings("aliases").map { it.take(120) }, notes = c.str("notes")?.trim()?.take(4000).orEmpty())
                val dups = archive.people.duplicatesOf(p)
                val saved = archive.people.save(p, reason = "added from the desktop")
                c.send(201, buildJsonObject { put("person", person(saved)); putJsonArray("possibleDuplicates") { dups.forEach { add(it.name) } } })
            }
            c.path == "/v1/documents" && c.method == "GET" -> c.ok(buildJsonArray { archive.documents.list().forEach { d -> addJsonObject {
                put("id", d.id); put("name", d.name); put("type", d.type); put("sizeBytes", d.sizeBytes); put("textStatus", d.textStatus); put("chars", d.chars)
                put("date", d.date.takeIf { it.known }?.label() ?: "") } } })
            c.path == "/v1/documents/search" && c.method == "GET" -> {
                val q = c.query("q")?.trim().orEmpty()
                if (q.isEmpty()) return true.also { c.fail(400, "q is required") }
                c.ok(buildJsonArray { archive.documents.search(q, limit = 10).forEach { p -> addJsonObject {
                    put("documentId", p.document.id); put("document", p.document.name); put("text", p.text.take(600)) } } })
            }
            c.path == "/v1/suggestions" && c.method == "GET" -> c.ok(buildJsonArray { core.proposals.pending().forEach { p -> addJsonObject {
                put("id", p.id); put("kind", p.kind.name); put("kindLabel", p.kind.label); put("text", p.text); put("rationale", p.rationale); put("createdAt", p.createdAt)
                p.category?.let { put("category", it.name); put("categoryLabel", it.label) }
                p.confidence?.let { put("confidence", it) }
                put("inferred", p.kind == ai.clu.core.memory.ProposalKind.OBSERVATION || p.kind == ai.clu.core.memory.ProposalKind.HYPOTHESIS)
                p.relatedId?.let { rid -> core.memories.get(rid)?.takeIf { !it.deleted }?.let { o -> putJsonObject("conflictsWith") { put("id", o.id); put("title", o.title); put("content", o.content.take(200)) } } }
                p.conversationId?.let { cid -> core.conversations.get(cid)?.let { put("conversation", it.title) } }
            } } })
            suggestion != null && c.method == "POST" -> {
                val (id, action) = suggestion.destructured
                val cat = if (action == "approve") c.str("category")?.let { runCatching { MemoryCategory.valueOf(it) }.getOrNull() ?: return true.also { _ -> c.fail(400, "unknown category $it") } } else null
                runCatching {
                    if (action == "approve") {
                    val m = core.proposals.approve(id, c.str("text")?.trim()?.ifEmpty { null }, cat); c.ok(buildJsonObject { put("ok", true); m?.let { put("memory", memory(it)) } }) }
                    else { core.proposals.reject(id); c.ok() }
                }.onFailure { c.fail(409, it.message ?: "not possible") }
            }
            else -> return false
        }
        return true
    }

    /** Creates or edits a memory: the date is parsed as the owner wrote it; an unreadable date is refused, never guessed. */
    private fun save(c: Call, existing: Memory?) {
        val title = c.str("title")?.trim() ?: existing?.title.orEmpty()
        val content = c.str("content") ?: existing?.content.orEmpty()
        if (title.isEmpty() && content.isBlank()) return c.fail(400, "a title or what happened is required")
        val whenText = c.str("when")
        val date = when {
            whenText == null -> existing?.historicalDate() ?: HistoricalDate.UNKNOWN
            whenText.isBlank() -> HistoricalDate.UNKNOWN
            else -> DateParser.parse(whenText) ?: return c.fail(400, "the date \"$whenText\" isn't understood: try \"March 2018\", \"around 2019\" or \"12 March 2018\"")
        }
        val category = c.str("category")?.let { runCatching { MemoryCategory.valueOf(it) }.getOrNull() ?: return c.fail(400, "unknown category $it") }
        val certainty = c.str("certainty")?.let { runCatching { Certainty.valueOf(it) }.getOrNull() ?: return c.fail(400, "unknown certainty $it") }
        val importance = c.body["importance"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 2)
        val base = existing ?: Memory(category = category ?: MemoryCategory.EXPERIENCE, title = "", content = "", source = MemorySource.OWNER_SUPPLIED)
        val m = base.copy(title = title.take(200), content = content.take(200_000), eventDate = date, eventAt = null, eventHasTime = false,
            category = category ?: base.category, certainty = certainty ?: base.certainty, importance = importance ?: base.importance,
            people = if ("people" in c.body) c.strings("people") else base.people, places = if ("places" in c.body) c.strings("places") else base.places,
            tags = if ("tags" in c.body) c.strings("tags") else base.tags)
        val r = runCatching { core.memory.remember(m, sourceRef = if (existing == null) "desktop:owner" else "") }.getOrElse { return c.fail(400, it.message ?: "could not save") }
        c.send(if (existing == null) 201 else 200, buildJsonObject {
            put("memory", memory(r.memory))
            putJsonArray("possibleDuplicates") { r.duplicates.forEach { addJsonObject { put("id", it.id); put("title", it.title); put("when", it.historicalDate().label()) } } }
            putJsonArray("conflicts") { r.conflicts.forEach { addJsonObject { put("field", it.field); put("otherValue", it.bValue) } } }
            putJsonArray("hints") { r.hints.forEach { add(it) } }
            putJsonObject("ambiguousPeople") { r.ambiguousPeople.forEach { (n, ps) -> putJsonArray(n) { ps.forEach { add(it.name) } } } }
        })
    }

    private fun memory(m: Memory) = memoryJson(core, m)

    private fun person(p: ArchivePerson) = buildJsonObject {
        put("id", p.id); put("name", p.name); put("relation", p.relation); put("notes", p.notes)
        putJsonArray("aliases") { p.aliases.forEach { add(it) } }
        put("memories", archive.memoriesOf(p).size)
    }

    /** A document upload: raw bytes with the file name in `X-File-Name`; refusals are explained, nothing partial is kept. */
    fun upload(c: Call, bytes: ByteArray) {
        val name = c.ex.requestHeaders.getFirst("X-File-Name")?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()
        if (name.isNullOrEmpty()) return c.fail(400, "X-File-Name header is required")
        try {
            val d = archive.documents.import(name, bytes, originalRef = "desktop upload")
            c.send(201, buildJsonObject { put("id", d.id); put("name", d.name); put("textStatus", d.textStatus); put("chars", d.chars) })
        } catch (e: DocumentImportException) {
            c.send(if (e.reason == DocumentImportException.Reason.DUPLICATE) 409 else 422, buildJsonObject { put("error", e.message ?: "refused"); put("reason", e.reason.name) })
        }
    }

    companion object {
        const val MAX_UPLOAD = 50 * 1024 * 1024

        /** One memory as the API shows it, with its kind and the metadata the unified memory service keeps. */
        fun memoryJson(core: CluCore, m: Memory): JsonObject = buildJsonObject {
            put("id", m.id); put("title", m.title); put("content", m.content); put("category", m.category.name); put("categoryLabel", m.category.label)
            val meta = core.memory.metaOf(m)
            put("kind", meta.kind.name); put("kindLabel", meta.kind.label); meta.confidence?.let { put("confidence", it) }
            val d = m.historicalDate()
            put("when", if (d.known) d.label() else ""); put("precision", d.precision.name); d.startDate()?.let { put("sortDate", it.toString()) }
            put("certainty", m.certainty.name); put("importance", m.importance); put("pinned", m.pinned)
            put("source", m.source.name); put("sourceLabel", m.source.label); put("createdAt", m.createdAt); put("updatedAt", m.updatedAt)
            putJsonArray("people") { m.people.forEach { add(it) } }; putJsonArray("places") { m.places.forEach { add(it) } }; putJsonArray("tags") { m.tags.forEach { add(it) } }
        }
    }
}
