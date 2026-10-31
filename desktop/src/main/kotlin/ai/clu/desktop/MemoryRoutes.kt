package ai.clu.desktop

import ai.clu.core.CluCore
import ai.clu.core.config.Settings
import ai.clu.core.history.Kind
import ai.clu.core.learning.PrefScope
import ai.clu.core.memory.*
import kotlinx.serialization.json.*
import java.time.LocalDate

/**
 * The unified memory over the local API (the shell, and later the phone, use only these and `/v1/memories`):
 *
 * | Method | Path | |
 * |---|---|---|
 * | GET | /v1/memory/overview | counts per kind, what is waiting for the owner, background job states |
 * | GET | /v1/memory/search | `q, kind, category, person, from, to, docs=0, limit`: hybrid search; no `q` = filtered browse |
 * | GET | /v1/memory/{id} | everything about one memory: metadata, provenance, related records, earlier versions, conflicts |
 * | POST, DELETE | /v1/memory/{id}/links | `{otherId, otherKind?, relation}` / `{linkId}` |
 * | GET, POST, DELETE | /v1/memory/working[/{id}] | short-lived notes; POST `{text,kind}` |
 * | GET | /v1/memory/consolidation | merge suggestions waiting, and recent activity |
 * | POST | /v1/memory/consolidation/{id}/approve, /reject, /v1/memory/consolidation/run | |
 * | GET | /v1/memory/learning | questions about standing preferences, what CLU has noticed, learned preferences |
 * | POST | /v1/memory/learning/{id}/accept, /reject, /v1/memory/learning/feedback, /v1/memory/learning/reset | |
 * | GET, POST | /v1/memory/health | integrity report / `{action: repair\|reindex}` |
 * | GET, POST | /v1/memory/backups | list / make one now |
 * | GET, POST | /v1/memory/maintenance | job states / run once now |
 */
class MemoryRoutes(private val core: CluCore, private val maintenance: MaintenanceScheduler?, private val paths: DesktopPaths?) {
    private val svc get() = core.memory

    fun handle(c: Call): Boolean {
        val p = c.path
        if (!p.startsWith("/v1/memory")) return false
        val id = Regex("^/v1/memory/([^/]+)$").find(p)?.groupValues?.get(1)
        val sub = Regex("^/v1/memory/([^/]+)/(links)$").find(p)
        val cons = Regex("^/v1/memory/consolidation/([^/]+)/(approve|reject)$").find(p)
        val learn = Regex("^/v1/memory/learning/([^/]+)/(accept|reject)$").find(p)
        val working = Regex("^/v1/memory/working/([^/]+)$").find(p)
        when {
            p == "/v1/memory/overview" && c.method == "GET" -> overview(c)
            p == "/v1/memory/search" && c.method == "GET" -> search(c)
            p == "/v1/memory/working" && c.method == "GET" -> c.ok(buildJsonArray { svc.working.list().forEach { add(workingJson(it)) } })
            p == "/v1/memory/working" && c.method == "POST" -> {
                val text = c.str("text")?.trim().orEmpty()
                if (text.isEmpty()) return true.also { c.fail(400, "text is required") }
                val kind = c.str("kind")?.let { runCatching { WorkingMemory.Kind.valueOf(it.uppercase()) }.getOrNull() } ?: WorkingMemory.Kind.NOTE
                c.send(201, workingJson(svc.working.put(text, kind, c.str("conversationId"))))
            }
            working != null && c.method == "DELETE" -> { svc.working.remove(working.groupValues[1]); c.ok() }
            p == "/v1/memory/consolidation" && c.method == "GET" -> consolidation(c)
            p == "/v1/memory/consolidation/run" && c.method == "POST" -> c.ok(buildJsonObject {
                val r = core.consolidator.run(maxScan = 200)
                put("scanned", r.scanned); put("autoMerged", r.autoMerged); put("suggested", r.suggested); put("expiredMemories", r.expiredMemories); put("expiredWorking", r.expiredWorking) })
            cons != null && c.method == "POST" -> {
                val (cid, action) = cons.destructured
                runCatching { if (action == "approve") c.ok(buildJsonObject { put("memory", ArchiveRoutes.memoryJson(core, core.consolidator.approve(cid))) }) else { core.consolidator.reject(cid); c.ok() } }
                    .onFailure { c.fail(409, it.message ?: "not possible") }
            }
            p == "/v1/memory/learning" && c.method == "GET" -> learning(c)
            p == "/v1/memory/learning/feedback" && c.method == "POST" -> feedback(c)
            p == "/v1/memory/learning/reset" && c.method == "POST" -> { core.learner.clearSignals(); c.ok() }
            learn != null && c.method == "POST" -> {
                val (lid, action) = learn.destructured
                runCatching { if (action == "accept") core.learner.accept(lid) else core.learner.reject(lid); c.ok() }.onFailure { c.fail(409, it.message ?: "not possible") }
            }
            p == "/v1/memory/health" && c.method == "GET" -> c.ok(healthJson(core.memoryHealth.check()))
            p == "/v1/memory/health" && c.method == "POST" -> when (c.str("action")) {
                "repair" -> c.ok(buildJsonObject { putJsonArray("done") { core.memoryHealth.repair().forEach { add(it) } }; put("health", healthJson(core.memoryHealth.check())) })
                "reindex" -> c.ok(buildJsonObject { put("reindexed", core.memoryHealth.reindexAll()); put("health", healthJson(core.memoryHealth.check())) })
                else -> c.fail(400, "action must be repair or reindex")
            }
            p == "/v1/memory/backups" && c.method == "GET" -> backups(c)
            p == "/v1/memory/backups" && c.method == "POST" -> {
                val dir = paths?.backups ?: return true.also { c.fail(404, "backups are not available here") }
                runCatching { DatabaseBackup.create(core.db, dir, System.currentTimeMillis(), "manual", keep = 10) }
                    .onSuccess { b -> c.send(201, buildJsonObject { put("name", b.file.name); put("sizeBytes", b.sizeBytes) }) }.onFailure { c.fail(500, it.message ?: "backup failed") }
            }
            p == "/v1/memory/maintenance" && c.method == "GET" -> maintenanceStatus(c)
            p == "/v1/memory/maintenance" && c.method == "POST" -> {
                val m = maintenance ?: return true.also { c.fail(404, "background maintenance is not available here") }
                val o = m.tick(force = true); c.ok(buildJsonObject { put("ran", o.ran); put("summary", o.summary); put("at", o.at) })
            }
            sub != null -> link(c, sub.groupValues[1])
            id != null && c.method == "GET" -> detail(c, id)
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------------------------------ views
    private fun overview(c: Call) = c.ok(buildJsonObject {
        putJsonObject("kinds") { svc.overview().forEach { (k, n) -> putJsonObject(k.name) { put("label", k.label); put("count", n) } } }
        put("suggestions", core.proposals.pending().size)
        put("consolidation", core.consolidator.pending().size)
        put("learning", core.learner.pending().size)
        put("working", svc.working.count())
        putJsonArray("jobs") { ai.clu.core.memory.Jobs.all(core.db).forEach { j -> addJsonObject { put("name", j.name); put("state", j.state); put("detail", j.detail); j.lastRun?.let { put("lastRun", it) } } } }
        maintenance?.last?.let { o -> putJsonObject("lastMaintenance") { put("at", o.at); put("ran", o.ran); put("summary", o.summary) } }
        put("formation", core.settings.string(Settings.FORMATION))
        put("embeddings", svc.embedder?.id ?: "")
    })

    private fun search(c: Call) {
        val q = c.query("q")?.trim().orEmpty()
        val kind = c.query("kind")?.takeIf { it.isNotBlank() }?.let { runCatching { MemoryKind.valueOf(it) }.getOrNull() ?: return c.fail(400, "unknown kind $it") }
        val cat = c.query("category")?.takeIf { it.isNotBlank() }?.let { runCatching { MemoryCategory.valueOf(it) }.getOrNull() ?: return c.fail(400, "unknown category $it") }
        fun date(k: String): LocalDate? = c.query(k)?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        for (k in listOf("from", "to")) if (!c.query(k).isNullOrBlank() && date(k) == null) return c.fail(400, "$k must be a date like 2024-03-31")
        val from = date("from"); val to = date("to")
        val person = c.query("person")?.trim()?.takeIf { it.isNotEmpty() }
        val limit = (c.query("limit")?.toIntOrNull() ?: 60).coerceIn(1, 200)
        val hits = svc.recall(RecallRequest(q, kinds = kind?.let { setOf(it) }, categories = cat?.let { setOf(it) }, people = listOfNotNull(person), from = from, to = to,
            includeDocuments = c.query("docs") != "0", expandRelated = q.isNotEmpty(), limit = limit, scopes = null, touch = false))
        c.ok(buildJsonArray { hits.forEach { h -> addJsonObject {
            put("score", h.score); putJsonArray("why") { h.why.forEach { add(it) } }
            if (h.memory != null) { put("type", "memory"); put("memory", ArchiveRoutes.memoryJson(core, h.memory!!)) }
            else { val ps = h.passage!!; put("type", "document"); putJsonObject("document") { put("id", ps.document.id); put("name", ps.document.name); put("docType", ps.document.type) }
                put("chunk", ps.chunk + 1); put("text", ps.text.replace(Regex("\\s+"), " ").take(500)) }
        } } })
    }

    private fun detail(c: Call, id: String) {
        val d = svc.detail(id) ?: return c.fail(404, "no such memory")
        c.ok(buildJsonObject {
            put("memory", ArchiveRoutes.memoryJson(core, d.memory))
            putJsonObject("meta") {
                put("kind", d.meta.kind.name); put("kindLabel", d.meta.kind.label); d.meta.confidence?.let { put("confidence", it) }
                d.meta.lastAccessed?.let { put("lastAccessed", it) }; put("accessCount", d.meta.accessCount); put("reinforced", d.meta.reinforced)
                d.meta.reviewAt?.let { put("reviewAt", it) }; put("approval", d.meta.approval.name)
                put("createdAt", d.memory.createdAt); put("updatedAt", d.memory.updatedAt); put("source", d.memory.source.name); put("sourceLabel", d.memory.source.label)
            }
            putJsonObject("provenance") {
                put("source", d.provenance.source); put("reference", provenanceText(d.meta.sourceRef)); put("approval", d.provenance.approval.name)
                putJsonArray("documents") { d.provenance.documents.forEach { add(it) } }; putJsonArray("mergedFrom") { d.provenance.mergedFrom.forEach { add(it) } }
            }
            putJsonArray("related") { d.related.forEach { r -> addJsonObject { put("kind", r.kind); put("id", r.id); put("title", r.title); put("relation", r.relation); put("note", r.note); put("linkId", r.linkId) } } }
            putJsonArray("revisions") { d.revisions.forEach { (at, m) -> addJsonObject { put("at", at); put("title", m.title); put("content", m.content.take(2000)) } } }
            putJsonArray("conflicts") { d.conflicts.forEach { add(it) } }
        })
    }

    /** "conversation:<id>" becomes words the owner recognises. */
    private fun provenanceText(ref: String): String = when {
        ref.startsWith("conversation:") -> core.conversations.get(ref.removePrefix("conversation:"))?.let { "From your conversation \"${it.title}\"" } ?: "From a conversation that has since been deleted"
        ref.startsWith("desktop:") -> "Entered by you in the desktop window"
        ref.isBlank() -> ""
        else -> ref
    }

    private fun link(c: Call, id: String) {
        if (core.memories.get(id)?.deleted != false) return c.fail(404, "no such memory")
        when (c.method) {
            "POST" -> {
                val other = c.str("otherId") ?: return c.fail(400, "otherId is required")
                val kind = c.str("otherKind") ?: Kind.MEMORY
                val relation = c.str("relation") ?: ai.clu.core.history.Relation.RELATED
                if (kind !in setOf(Kind.MEMORY, Kind.PERSON, Kind.PROJECT, Kind.DOCUMENT)) return c.fail(400, "unknown kind $kind")
                if (relation !in MemoryService.RELATIONS) return c.fail(400, "relation must be one of ${MemoryService.RELATIONS.sorted().joinToString()}")
                if (kind == Kind.MEMORY && core.memories.get(other)?.deleted != false) return c.fail(404, "no such memory to link")
                if (kind == Kind.MEMORY && other == id) return c.fail(400, "a memory can't be linked to itself")
                svc.relate(Kind.MEMORY, id, kind, other, relation, c.str("note").orEmpty().take(200)); c.send(201, buildJsonObject { put("ok", true) })
            }
            "DELETE" -> { svc.unrelate(c.str("linkId") ?: return c.fail(400, "linkId is required")); c.ok() }
            else -> c.fail(405, "use POST or DELETE")
        }
    }

    private fun consolidation(c: Call) = c.ok(buildJsonObject {
        fun entry(e: ConsolidationEntry) = buildJsonObject {
            put("id", e.id); put("action", e.action); put("status", e.status); put("reason", e.reason); put("createdAt", e.createdAt); e.decidedAt?.let { put("decidedAt", it) }
            putJsonArray("memories") { e.memoryIds.forEach { mid -> core.memories.get(mid)?.let { m -> addJsonObject {
                put("id", m.id); put("title", m.title.ifBlank { "(untitled)" }); put("content", m.content.take(300)); put("deleted", m.deleted)
                put("when", m.historicalDate().takeIf { it.known }?.label().orEmpty()) } } } }
            e.resultId?.let { put("resultId", it) }
        }
        putJsonArray("pending") { core.consolidator.pending().forEach { add(entry(it)) } }
        putJsonArray("activity") { core.consolidator.activity(40).forEach { add(entry(it)) } }
        put("enabled", core.settings.bool(Settings.CONSOLIDATION))
    })

    private fun learning(c: Call) = c.ok(buildJsonObject {
        put("enabled", core.settings.bool(Settings.LEARN_PREFERENCES))
        putJsonArray("questions") { core.learner.pending().forEach { q -> addJsonObject { put("id", q.id); put("question", q.question); put("evidence", q.evidence); put("createdAt", q.createdAt) } } }
        putJsonArray("noticed") { core.learner.overview().take(30).forEach { s -> addJsonObject {
            put("description", core.learner.describe(s)); put("scope", s.scope.name); put("scopeLabel", s.scope.label); put("signals", s.signals); put("against", s.negative)
            put("conversations", s.conversations); put("days", s.days) } } }
        putJsonArray("learned") { core.prefs.all().forEach { p -> addJsonObject { put("key", p.key); put("value", p.value); put("source", p.source.label)
            put("label", core.prefs.dimensions.firstOrNull { it.key == p.key }?.let { d -> "${d.title}: ${d.labels[p.value] ?: p.value}" } ?: p.value) } } }
    })

    /** Thumbs and reasons on a reply. They are recorded as evidence, never applied by themselves (one reaction is not a preference). */
    private fun feedback(c: Call) {
        val reason = c.str("reason") ?: return c.fail(400, "reason is required")
        val conv = c.str("conversationId").orEmpty()
        val (target, value, polarity) = when (reason) {
            "too_long" -> Triple("setting:${Settings.RESPONSE_LENGTH}", "brief", 1)
            "too_short" -> Triple("setting:${Settings.RESPONSE_LENGTH}", "detailed", 1)
            "too_formal" -> Triple("setting:${Settings.FORMALITY}", "casual", 1)
            "too_casual" -> Triple("setting:${Settings.FORMALITY}", "formal", 1)
            "good" -> Triple("instruction", "", 0)
            "wrong" -> Triple("instruction", "", 0)
            else -> return c.fail(400, "reason must be too_long, too_short, too_formal, too_casual, good or wrong")
        }
        if (target == "instruction") { c.ok(buildJsonObject { put("recorded", false); put("message", "Thanks. A thumbs up or down alone doesn't change anything; tell me what to do differently and I'll learn from that.") }); return }
        val st = core.learner.record(target, value, polarity, explicit = false, conversationId = conv, note = reason.replace('_', ' '))
        c.ok(buildJsonObject { put("recorded", true); put("scope", st.scope.name); put("signals", st.signals)
            put("message", if (st.scope == PrefScope.RECURRING) "Noted. This keeps coming up, so I'll ask whether to make it a standing preference."
            else "Noted, once. I won't change anything on one reaction; say \"be more concise\" if you want it now.") })
    }

    private fun healthJson(h: HealthReport) = buildJsonObject {
        put("ok", h.ok); put("integrity", h.integrity); put("memories", h.memories); put("documents", h.documents); put("checkedAt", h.checkedAt)
        putJsonArray("issues") { h.issues.forEach { i -> addJsonObject { put("code", i.code); put("count", i.count); put("detail", i.detail); put("repairable", i.repairable) } } }
    }

    private fun backups(c: Call) {
        val dir = paths?.backups ?: return c.fail(404, "backups are not available here")
        c.ok(buildJsonObject {
            put("folder", dir.absolutePath)
            putJsonArray("backups") { DatabaseBackup.list(dir).forEach { b -> addJsonObject { put("name", b.file.name); put("sizeBytes", b.sizeBytes); put("createdAt", b.createdAt); put("kind", b.label) } } }
            put("restore", "With CLU stopped: clu-host --restore-backup \"<file>\" (the current database is kept aside, not deleted)")
        })
    }

    private fun maintenanceStatus(c: Call) = c.ok(buildJsonObject {
        put("scheduled", maintenance?.scheduled == true)
        maintenance?.last?.let { o -> putJsonObject("last") { put("at", o.at); put("ran", o.ran); put("summary", o.summary) } }
        maintenance?.busyReason()?.let { put("waiting", it) }
        putJsonArray("jobs") { ai.clu.core.memory.Jobs.all(core.db).forEach { j -> addJsonObject { put("name", j.name); put("state", j.state); put("detail", j.detail); j.lastRun?.let { put("lastRun", it) } } } }
    })

    private fun workingJson(i: WorkingMemory.Item) = buildJsonObject {
        put("id", i.id); put("kind", i.kind.name); put("text", i.text); put("createdAt", i.createdAt); put("expiresAt", i.expiresAt); i.conversationId?.let { put("conversationId", it) }
    }
}
