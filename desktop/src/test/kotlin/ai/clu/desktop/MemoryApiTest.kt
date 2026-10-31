package ai.clu.desktop

import ai.clu.core.memory.*
import ai.clu.core.util.CluJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.*

/** The unified memory over the local API, against the real host (database, core, governor, maintenance, backups). */
class MemoryApiTest {
    private val dir = Files.createTempDirectory("clu-memapi").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) }
    private val paths = DesktopPaths(File(dir, "home"))
    private val host = Host.start(paths, HostConfig(maintenanceMinutes = 0), port = 0, log = {})
    private val http = HttpClient.newHttpClient()
    private val core get() = host.core

    @AfterEach fun stop() = host.stop()

    private fun req(path: String, method: String = "GET", body: String? = null, token: String? = host.token, bytes: ByteArray? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create(host.url + path)); token?.let { b.header("Authorization", "Bearer $it") }; headers.forEach { (k, v) -> b.header(k, v) }
        val pub = when { bytes != null -> HttpRequest.BodyPublishers.ofByteArray(bytes); body != null -> HttpRequest.BodyPublishers.ofString(body); else -> HttpRequest.BodyPublishers.noBody() }
        return http.send(b.method(method, pub).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun obj(r: HttpResponse<String>) = CluJson.parseToJsonElement(r.body()).jsonObject
    private fun arr(r: HttpResponse<String>) = CluJson.parseToJsonElement(r.body()).jsonArray
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun create(title: String, content: String, category: String = "CORE_FACT", extra: String = ""): JsonObject {
        val r = req("/v1/memories", "POST", """{"title":"$title","content":"$content","category":"$category"$extra}""")
        assertEquals(201, r.statusCode(), r.body()); return obj(r)["memory"]!!.jsonObject
    }

    @Test fun nothingIsAvailableWithoutTheToken() {
        for (p in listOf("/v1/memory/overview", "/v1/memory/search?q=x", "/v1/memory/health", "/v1/memory/backups", "/v1/memory/working", "/v1/memory/learning", "/v1/memory/consolidation"))
            assertEquals(401, req(p, token = null).statusCode(), p)
    }

    @Test fun browseSearchAndFilterAcrossKindsCategoriesPeopleAndTime() {
        val sister = create("Sister's name", "My sister's name is Mira and she lives in Pune.", extra = ""","people":"Mira","importance":2""")
        create("Hexapod decision", "Decided to use an STM32 board for the Hexapod robot.", "DECISION", ""","when":"March 2024"""")
        create("Fest demo", "Demonstrated the Hexapod robot at the college fest.", "EXPERIENCE", ""","when":"April 2024","people":"Mira"""")
        create("PID control", "A PID controller combines proportional, integral and derivative terms.", "KNOWLEDGE")
        create("Deploy the site", "First run the tests, then build with make release, finally rsync the dist folder.", "PROCEDURE")

        val ov = obj(req("/v1/memory/overview"))
        assertEquals(1, ov["kinds"]!!.jsonObject["SEMANTIC"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertEquals(1, ov["kinds"]!!.jsonObject["PROCEDURAL"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertEquals("suggest", ov.s("formation")); assertEquals("", ov.s("embeddings"), "no embedding model is wired in yet, and the API says so")

        fun titles(q: String) = arr(req("/v1/memory/search?$q")).filter { it.jsonObject.s("type") == "memory" }.map { it.jsonObject["memory"]!!.jsonObject.s("title") }
        assertEquals("Hexapod decision", titles("q=" + enc("hexapod STM32 board")).first())
        assertEquals(listOf("PID control"), titles("kind=SEMANTIC"))
        assertEquals(listOf("Deploy the site"), titles("kind=PROCEDURAL"))
        assertEquals(listOf("Fest demo"), titles("category=EXPERIENCE"))
        assertEquals(setOf("Sister's name", "Fest demo"), titles("person=mira").toSet())
        assertEquals(setOf("Fest demo"), titles("from=2024-04-01&to=2024-04-30").toSet())
        assertEquals("Sister's name", titles("").first(), "browsing: the very important memory comes first")
        val hit = arr(req("/v1/memory/search?q=${enc("rsync dist")}")).first().jsonObject
        assertEquals("Procedure / workflow", hit["memory"]!!.jsonObject.s("categoryLabel")); assertEquals("PROCEDURAL", hit["memory"]!!.jsonObject.s("kind"))
        assertEquals(400, req("/v1/memory/search?kind=NOPE").statusCode()); assertEquals(400, req("/v1/memory/search?from=yesterday").statusCode())
        // Searching from the window does not count as CLU using the memory.
        assertEquals(0, obj(req("/v1/memory/${sister.s("id")}"))["meta"]!!.jsonObject["accessCount"]!!.jsonPrimitive.int)
    }

    @Test fun detailShowsWhereAMemoryCameFromAndWhatItIsConnectedTo() {
        val project = create("Hexapod project", "Six-leg walking robot on an STM32 board.", "PROJECT")
        val decision = create("Battery choice", "Decided on a separate servo battery.", "DECISION")
        val conv = core.conversations.create("Robot chat").id
        val p = core.proposals.propose(ProposalKind.FACT, "My servo supply is 7.4 volts.", "You said it.", conv, confidence = 0.9, sourceRef = "conversation:$conv")!!
        val saved = obj(req("/v1/suggestions/${p.id}/approve", "POST", "{}"))["memory"]!!.jsonObject.s("id")

        val d = obj(req("/v1/memory/$saved"))
        assertEquals("Core identity", d["meta"]!!.jsonObject.s("kindLabel")); assertEquals("APPROVED", d["provenance"]!!.jsonObject.s("approval"))
        assertEquals("From your conversation \"Robot chat\"", d["provenance"]!!.jsonObject.s("reference"))
        assertEquals(0.9, d["meta"]!!.jsonObject["confidence"]!!.jsonPrimitive.double)
        assertTrue(d["meta"]!!.jsonObject["createdAt"]!!.jsonPrimitive.long > 0 && d["meta"]!!.jsonObject["updatedAt"]!!.jsonPrimitive.long > 0)
        assertEquals("From a conversation with Clu", d["meta"]!!.jsonObject.s("sourceLabel"))
        assertEquals("Entered by you in the desktop window", obj(req("/v1/memory/${project.s("id")}"))["provenance"]!!.jsonObject.s("reference"))

        // Link, list, remove.
        assertEquals(201, req("/v1/memory/${decision.s("id")}/links", "POST", """{"otherId":"${project.s("id")}","relation":"belongs_to"}""").statusCode())
        val related = obj(req("/v1/memory/${project.s("id")}"))["related"]!!.jsonArray.single().jsonObject
        assertEquals("Battery choice", related.s("title")); assertEquals("belongs_to", related.s("relation"))
        // Search brings the connected record along and says why.
        val hits = arr(req("/v1/memory/search?q=${enc("separate servo battery")}&docs=0"))
        val why = hits.first { it.jsonObject["memory"]!!.jsonObject.s("id") == project.s("id") }.jsonObject["why"]!!.jsonArray.single().jsonPrimitive.content
        assertTrue(why.startsWith("belongs_to"), why)
        assertEquals(400, req("/v1/memory/${decision.s("id")}/links", "POST", """{"otherId":"${project.s("id")}","relation":"friends"}""").statusCode())
        assertEquals(400, req("/v1/memory/${decision.s("id")}/links", "POST", """{"otherId":"${decision.s("id")}","relation":"related"}""").statusCode())
        assertEquals(404, req("/v1/memory/${decision.s("id")}/links", "POST", """{"otherId":"nope","relation":"related"}""").statusCode())
        assertEquals(200, req("/v1/memory/${decision.s("id")}/links", "DELETE", """{"linkId":"${related.s("linkId")}"}""").statusCode())
        assertTrue(obj(req("/v1/memory/${project.s("id")}"))["related"]!!.jsonArray.isEmpty())

        // Edit keeps the earlier version; delete removes everything.
        val put = req("/v1/memories/${project.s("id")}", "PUT", """{"content":"Six-leg walking robot on an STM32 board, now with a camera."}"""); assertEquals(200, put.statusCode(), put.body())
        assertEquals(1, obj(req("/v1/memory/${project.s("id")}"))["revisions"]!!.jsonArray.size)
        assertEquals(200, req("/v1/memories/${project.s("id")}", "DELETE").statusCode())
        assertEquals(404, req("/v1/memory/${project.s("id")}").statusCode())
        assertNull(core.memory.meta(project.s("id")))
    }

    @Test fun workingNotesAreSeparateFromMemoryAndCanBeDismissed() {
        val n = obj(req("/v1/memory/working", "POST", """{"text":"Parked on level 3","kind":"note"}"""))
        assertEquals("NOTE", n.s("kind")); assertTrue(n["expiresAt"]!!.jsonPrimitive.long > n["createdAt"]!!.jsonPrimitive.long)
        assertEquals(1, arr(req("/v1/memory/working")).size); assertEquals(0, arr(req("/v1/memories")).size)
        assertEquals(1, obj(req("/v1/memory/overview"))["working"]!!.jsonPrimitive.int)
        assertEquals(400, req("/v1/memory/working", "POST", """{"text":" "}""").statusCode())
        req("/v1/memory/working/${n.s("id")}", "DELETE"); assertEquals(0, arr(req("/v1/memory/working")).size)
    }

    @Test fun suggestionsShowTheirEvidenceAndConflictsAndOnlyApprovalSavesThem() {
        val old = create("Editor", "My favourite editor is Vim.", "PREFERENCE")
        val conv = core.conversations.create("Editors").id
        core.formation.process("My favourite editor is Neovim.", conv)
        core.proposals.propose(ProposalKind.OBSERVATION, "Tends to start projects late at night.", "Mentioned working at night in three conversations.", conv)
        val list = arr(req("/v1/suggestions")).map { it.jsonObject }
        val fact = list.first { it.s("kind") == "PREFERENCE" }; val inferred = list.first { it.s("kind") == "OBSERVATION" }
        assertEquals(old.s("id"), fact["conflictsWith"]!!.jsonObject.s("id")); assertEquals("Editors", fact.s("conversation")); assertTrue(fact.s("rationale").contains("may conflict"))
        assertTrue(inferred["inferred"]!!.jsonPrimitive.boolean); assertFalse(fact["inferred"]!!.jsonPrimitive.boolean)
        assertEquals(1, arr(req("/v1/memories")).size, "nothing saved yet")
        // Approve the inference with the owner's edit and a category; reject the fact.
        val m = obj(req("/v1/suggestions/${inferred.s("id")}/approve", "POST", """{"text":"Often starts projects late at night.","category":"INSIGHT"}"""))["memory"]!!.jsonObject
        assertEquals("Often starts projects late at night.", m.s("content")); assertEquals("APPROVED_INFERENCE", m.s("source"))
        assertEquals(400, req("/v1/suggestions/${fact.s("id")}/approve", "POST", """{"category":"NOPE"}""").statusCode())
        assertEquals(200, req("/v1/suggestions/${fact.s("id")}/reject", "POST", "{}").statusCode())
        assertEquals(2, arr(req("/v1/memories")).size)
    }

    @Test fun consolidationIsVisibleAndTheOwnerDecidesNearDuplicates() {
        create("Chose STM32", "Decided to use an STM32 board instead of Arduino for the hexapod project.", "DECISION")
        create("Chose STM32", "Decided to use an STM32 board instead of an Arduino for the hexapod robot project.", "DECISION")
        create("Same words", "The exact same words here."); create("Same words", "The exact same words here.")
        val run = obj(req("/v1/memory/consolidation/run", "POST", "{}"))
        assertEquals(1, run["autoMerged"]!!.jsonPrimitive.int); assertEquals(1, run["suggested"]!!.jsonPrimitive.int)
        val c = obj(req("/v1/memory/consolidation"))
        val pending = c["pending"]!!.jsonArray.single().jsonObject
        assertEquals(2, pending["memories"]!!.jsonArray.size)
        assertTrue(c["activity"]!!.jsonArray.any { it.jsonObject.s("status") == "AUTO" }, "the automatic merge is in the activity list")
        assertEquals(3, arr(req("/v1/memories")).size, "one exact duplicate folded, the near-duplicate still waits")
        assertEquals(200, req("/v1/memory/consolidation/${pending.s("id")}/approve", "POST", "{}").statusCode())
        assertEquals(2, arr(req("/v1/memories")).size)
        assertEquals(409, req("/v1/memory/consolidation/${pending.s("id")}/approve", "POST", "{}").statusCode(), "already decided")
        assertEquals(0, obj(req("/v1/memory/overview"))["consolidation"]!!.jsonPrimitive.int)
    }

    @Test fun learningQuestionsAreAnsweredByTheOwnerOnly() {
        val day = 86_400_000L; val now = System.currentTimeMillis()
        listOf(3, 2, 1).forEachIndexed { i, ago -> core.store.db.execute("INSERT INTO preference_signals(id, target, value, polarity, explicit, task_tag, conversation_id, note, created_at) VALUES(?,?,?,1,1,'','c$i','be more concise',?)",
            listOf("s$i", "setting:${ai.clu.core.config.Settings.RESPONSE_LENGTH}", "brief", now - ago * day)) }
        core.learner.record("setting:${ai.clu.core.config.Settings.RESPONSE_LENGTH}", "brief", explicit = true, conversationId = "c4")
        val l = obj(req("/v1/memory/learning"))
        val q = l["questions"]!!.jsonArray.single().jsonObject
        assertTrue(q.s("question").contains("Make it my standing preference?")); assertEquals("RECURRING", l["noticed"]!!.jsonArray.first().jsonObject.s("scope"))
        assertEquals("balanced", core.settings.string(ai.clu.core.config.Settings.RESPONSE_LENGTH))
        assertEquals(200, req("/v1/memory/learning/${q.s("id")}/accept", "POST", "{}").statusCode())
        assertEquals("brief", core.settings.string(ai.clu.core.config.Settings.RESPONSE_LENGTH))
        assertEquals("STABLE", obj(req("/v1/memory/learning"))["noticed"]!!.jsonArray.first().jsonObject.s("scope"))
        assertEquals(409, req("/v1/memory/learning/${q.s("id")}/accept", "POST", "{}").statusCode())
    }

    @Test fun oneReactionIsRecordedButNeverChangesAnything() {
        val r = obj(req("/v1/memory/learning/feedback", "POST", """{"reason":"too_long","conversationId":"c1"}"""))
        assertTrue(r["recorded"]!!.jsonPrimitive.boolean); assertEquals("ONCE", r.s("scope")); assertTrue(r.s("message").contains("won't change anything on one reaction"))
        assertEquals("balanced", core.settings.string(ai.clu.core.config.Settings.RESPONSE_LENGTH))
        assertFalse(obj(req("/v1/memory/learning/feedback", "POST", """{"reason":"good"}"""))["recorded"]!!.jsonPrimitive.boolean)
        assertEquals(400, req("/v1/memory/learning/feedback", "POST", """{"reason":"banana"}""").statusCode())
        assertEquals(200, req("/v1/memory/learning/reset", "POST", "{}").statusCode()); assertTrue(obj(req("/v1/memory/learning"))["noticed"]!!.jsonArray.isEmpty())
    }

    @Test fun healthRepairAndBackupsWork() {
        create("Allergy", "I am allergic to penicillin.")
        val h = obj(req("/v1/memory/health")); assertTrue(h["ok"]!!.jsonPrimitive.boolean); assertTrue(h["issues"]!!.jsonArray.isEmpty()); assertEquals(1, h["memories"]!!.jsonPrimitive.int)
        core.store.db.execute("INSERT INTO memory_terms(memory_id, term, tf) VALUES('ghost','phantom',3)")
        assertEquals("orphan_terms", obj(req("/v1/memory/health"))["issues"]!!.jsonArray.single().jsonObject.s("code"))
        val fixed = obj(req("/v1/memory/health", "POST", """{"action":"repair"}"""))
        assertTrue(fixed["done"]!!.jsonArray.isNotEmpty()); assertTrue(fixed["health"]!!.jsonObject["issues"]!!.jsonArray.isEmpty())
        assertEquals(1, obj(req("/v1/memory/health", "POST", """{"action":"reindex"}"""))["reindexed"]!!.jsonPrimitive.int)
        assertEquals(400, req("/v1/memory/health", "POST", """{"action":"format"}""").statusCode())

        assertEquals(201, req("/v1/memory/backups", "POST", "{}").statusCode())
        val b = obj(req("/v1/memory/backups"))
        val backup = b["backups"]!!.jsonArray.single().jsonObject
        assertEquals("manual", backup.s("kind")); assertTrue(b.s("restore").contains("--restore-backup"))
        val v = DatabaseBackup.verify(File(paths.backups, backup.s("name"))); assertTrue(v.ok); assertEquals(1, v.memories)
    }

    @Test fun maintenanceStatusAndARunNowThatIsBoundedAndHonest() {
        create("Dup", "Exactly the same words."); create("Dup", "Exactly the same words.")
        val before = obj(req("/v1/memory/maintenance"))
        assertFalse(before["scheduled"]!!.jsonPrimitive.boolean, "maintenanceMinutes = 0 in this test host")
        val r = obj(req("/v1/memory/maintenance", "POST", "{}"))
        assertTrue(r["ran"]!!.jsonPrimitive.boolean); assertTrue(r.s("summary").contains("merged 1"), r.s("summary")); assertTrue(r.s("summary").contains("backup clu-"))
        assertEquals(1, arr(req("/v1/memories")).size)
        val after = obj(req("/v1/memory/maintenance"))
        assertTrue(after["jobs"]!!.jsonArray.any { it.jsonObject.s("name") == "consolidate" }); assertNotNull(after["last"])
    }

    @Test fun documentsHoldingSourceCodeAreIndexedAndComeBackAsDocumentsNotMemories() {
        create("Hexapod project", "Six-leg walking robot.", "PROJECT")
        val code = "fun tripodGait(phase: Int): List<Boolean> = List(6) { (it + phase) % 2 == 0 } // hexapod leg schedule".toByteArray()
        val up = req("/v1/documents", "POST", bytes = code, headers = mapOf("X-File-Name" to "gait.kt")); assertEquals(201, up.statusCode(), up.body())
        val md = req("/v1/documents", "POST", bytes = "# Build notes\nThe hexapod chassis is printed in PETG.".toByteArray(), headers = mapOf("X-File-Name" to "notes.md")); assertEquals(201, md.statusCode())
        val hits = arr(req("/v1/memory/search?q=hexapod")).map { it.jsonObject }
        assertEquals(setOf("memory", "document"), hits.map { it.s("type") }.toSet())
        val doc = hits.first { it.s("type") == "document" && it["document"]!!.jsonObject.s("name") == "gait.kt" }
        assertEquals("kt", doc["document"]!!.jsonObject.s("docType")); assertEquals(1, doc["chunk"]!!.jsonPrimitive.int); assertTrue(doc.s("text").contains("tripodGait"))
        assertTrue(arr(req("/v1/memory/search?q=hexapod&docs=0")).all { it.jsonObject.s("type") == "memory" })
    }
}
