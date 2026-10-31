package ai.clu.core

import ai.clu.core.config.Settings
import ai.clu.core.db.Schema
import ai.clu.core.history.Kind
import ai.clu.core.history.Relation
import ai.clu.core.memory.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** The unified memory API over the real stores: kinds, metadata, retrieval, relationships, working memory, persistence, concurrency. */
class MemoryServiceTest {
    private val platform = TestPlatform()
    private val clock = ai.clu.core.util.ManualClock(1_760_000_000_000L)
    private val core = newCore(platform = platform, clock = clock)
    private val svc get() = core.memory

    private fun mem(title: String, content: String, cat: MemoryCategory, date: String? = null, people: List<String> = emptyList(), importance: Int = 0,
                    source: MemorySource = MemorySource.OWNER_SUPPLIED) =
        svc.remember(Memory(category = cat, title = title, content = content, source = source, people = people, importance = importance,
            eventDate = date?.let { ai.clu.core.history.DateParser.parse(it)!! } ?: ai.clu.core.history.HistoricalDate.UNKNOWN)).memory

    /** A believable slice of a person's life and work (not lorem ipsum): enough variety to make ranking and filtering mean something. */
    private fun corpus(): Map<String, Memory> = buildMap {
        put("sister", mem("Sister's name", "My sister's name is Mira. She lives in Pune and works as a paediatrician.", MemoryCategory.CORE_FACT, people = listOf("Mira"), importance = 2))
        put("tea", mem("Prefers tea", "I prefer green tea in the evening and never drink coffee after 4 pm.", MemoryCategory.PREFERENCE))
        put("allergy", mem("Allergy", "I am allergic to penicillin. Always mention it at clinics.", MemoryCategory.CORE_FACT, importance = 2))
        put("project", mem("Hexapod robot project", "The Hexapod walking robot project: six-leg gait controller on an STM32 board, demo planned for the college fest.", MemoryCategory.PROJECT))
        put("decision", mem("Chose STM32 for Hexapod", "Decided to use an STM32 board instead of Arduino for the Hexapod because it has enough timers for six servos.", MemoryCategory.DECISION, "March 2024"))
        put("fest", mem("College fest demo", "Demonstrated the Hexapod at the college fest; the judges liked the tripod gait.", MemoryCategory.EXPERIENCE, "around April 2024", people = listOf("Mira")))
        put("fail", mem("Servo brown-out", "The Hexapod reset during the demo because the servo supply browned out. Lesson: use a separate battery for servos.", MemoryCategory.EXPERIENCE, "April 2024"))
        put("deploy", mem("How to deploy the site", "To deploy the website: first run the test suite, then build with make release, then rsync the dist folder to the server, finally purge the CDN cache.", MemoryCategory.PROCEDURE))
        put("pid", mem("PID control", "A PID controller combines proportional, integral and derivative terms to drive an error toward zero.", MemoryCategory.KNOWLEDGE))
        put("gait", mem("Tripod gait", "In a tripod gait a hexapod lifts three legs at a time, keeping the body statically stable.", MemoryCategory.KNOWLEDGE))
        put("move", mem("Moved to Pune", "I moved to Pune in June 2022 for the master's degree.", MemoryCategory.EXPERIENCE, "June 2022"))
    }

    @Test fun everyMemoryHasAKindAndTheMetadataTheSpecAsksFor() {
        val c = corpus()
        assertEquals(MemoryKind.CORE, svc.metaOf(c.getValue("sister")).kind)
        assertEquals(MemoryKind.EPISODIC, svc.metaOf(c.getValue("fest")).kind)
        assertEquals(MemoryKind.SEMANTIC, svc.metaOf(c.getValue("pid")).kind)
        assertEquals(MemoryKind.PROCEDURAL, svc.metaOf(c.getValue("deploy")).kind)
        assertEquals(MemoryKind.WORKING, svc.metaOf(mem("Parking", "Parked on level 3", MemoryCategory.TEMPORARY)).kind)
        val m = core.memories.get(c.getValue("sister").id)!!
        assertTrue(m.id.isNotBlank() && m.createdAt > 0 && m.updatedAt > 0 && m.importance == 2 && m.source == MemorySource.OWNER_SUPPLIED)
        svc.setMeta(m.id, confidence = 0.9, reviewAt = clock.now() + 86_400_000L, sourceRef = "conversation:abc")
        val meta = svc.metaOf(core.memories.get(m.id)!!)
        assertEquals(0.9, meta.confidence); assertEquals("conversation:abc", meta.sourceRef); assertNotNull(meta.reviewAt)
        assertEquals(Approval.NOT_REQUIRED, meta.approval); assertNull(meta.lastAccessed)
        // The overview counts per kind across the whole store.
        val o = svc.overview()
        assertEquals(MemoryKind.entries.toSet(), o.keys)
        assertTrue(o.getValue(MemoryKind.CORE) >= 4 && o.getValue(MemoryKind.SEMANTIC) == 2 && o.getValue(MemoryKind.PROCEDURAL) == 1)
    }

    @Test fun rememberIsAllOrNothing() {
        val before = core.memories.count()
        // A summary-only memory without a summary is refused inside the archive save; nothing may be left behind.
        assertFails { svc.remember(Memory(category = MemoryCategory.CORE_FACT, title = "x", content = "y", source = MemorySource.OWNER_SUPPLIED, retainMode = RetainMode.SUMMARY), sourceRef = "r") }
        assertEquals(before, core.memories.count())
        assertEquals(0L, core.store.db.query("SELECT COUNT(*) FROM memory_meta") { it.long(0) }.first())
        // Memory switched off: refused, nothing written.
        core.settings.set(Settings.MEMORY_ENABLED, false)
        assertFailsWith<MemoryDisabledException> { mem("Nope", "Nope", MemoryCategory.CORE_FACT) }
        assertEquals(before, core.memories.count())
    }

    @Test fun keywordSearchFindsTheRightRecordsAndFiltersNarrowThem() {
        val c = corpus()
        val hits = svc.recall(RecallRequest("hexapod servo supply", includeDocuments = false, touch = false)).mapNotNull { it.memory?.id }
        assertEquals(c.getValue("fail").id, hits.first(), "the brown-out memory mentions servo supply: $hits")
        assertTrue(hits.containsAll(listOf(c.getValue("project").id, c.getValue("decision").id)))
        // Category filter.
        assertTrue(svc.recall(RecallRequest("hexapod", categories = setOf(MemoryCategory.KNOWLEDGE), expandRelated = false, includeDocuments = false)).all { it.memory!!.category == MemoryCategory.KNOWLEDGE })
        // Kind filter.
        val procedural = svc.recall(RecallRequest("website deploy build", kinds = setOf(MemoryKind.PROCEDURAL), includeDocuments = false))
        assertEquals(listOf(c.getValue("deploy").id), procedural.mapNotNull { it.memory?.id })
        // People filter.
        val mira = svc.recall(RecallRequest("", people = listOf("mira"), includeDocuments = false)).mapNotNull { it.memory?.id }.toSet()
        assertEquals(setOf(c.getValue("sister").id, c.getValue("fest").id), mira)
        // Time filter: April 2024 only (episodic, dated); the March decision and the 2022 move are outside.
        val april = svc.recall(RecallRequest("", from = LocalDate.of(2024, 4, 1), to = LocalDate.of(2024, 4, 30), includeDocuments = false)).mapNotNull { it.memory?.id }.toSet()
        assertEquals(setOf(c.getValue("fest").id, c.getValue("fail").id), april)
        // Importance is a tie-break signal, not a filter: with equally relevant text the important one comes first.
        mem("Clinic hours", "Clinics open at nine in the morning.", MemoryCategory.CORE_FACT)
        mem("Clinic closing", "Clinics close at five in the evening.", MemoryCategory.CORE_FACT, importance = 2)
        assertEquals("Clinic closing", svc.recall(RecallRequest("clinics", includeDocuments = false, touch = false)).first().memory!!.title)
    }

    @Test fun recallFollowsRelationshipsToConnectedInformation() {
        val c = corpus()
        svc.relate(Kind.MEMORY, c.getValue("decision").id, Kind.MEMORY, c.getValue("project").id, MemoryService.BELONGS_TO, "decision of the project")
        svc.relate(Kind.MEMORY, c.getValue("gait").id, Kind.MEMORY, c.getValue("project").id, Relation.SUPPORTS)
        // "timers" appears only in the decision; the project and the knowledge note are reached through links.
        val hits = svc.recall(RecallRequest("timers servos", includeDocuments = false, touch = false))
        assertEquals(c.getValue("decision").id, hits.first().memory!!.id)
        val project = hits.first { it.memory?.id == c.getValue("project").id }
        assertTrue(project.why.single().contains("belongs_to"), project.why.toString())
        // Two hops: the gait note is two links from the decision.
        val reach = svc.connected(c.getValue("decision").id, depth = 2).associate { it.second.id to it.first }
        assertEquals(1, reach[c.getValue("project").id]); assertEquals(2, reach[c.getValue("gait").id])
        assertFails { svc.relate(Kind.MEMORY, "a", Kind.MEMORY, "b", "friends_with") }
    }

    @Test fun documentKnowledgeIsKeptApartFromMemories() {
        corpus()
        val notes = ("Robotics club minutes, 12 May. The Hexapod needs a heavier battery. Action: order a 2200 mAh LiPo pack from the supplier and mount it low on the chassis.").toByteArray()
        val doc = core.archive.documents.import("club-minutes.md", notes)
        val code = "fun gait(phase: Int): List<Boolean> = List(6) { (it + phase) % 2 == 0 } // tripod gait helper for the hexapod".toByteArray()
        val src = core.archive.documents.import("gait.kt", code)
        assertEquals("kt", src.type); assertEquals("indexed", src.textStatus)
        val hits = svc.recall(RecallRequest("hexapod battery chassis"))
        val passage = hits.first { it.isDocument }
        assertEquals("club-minutes.md", passage.passage!!.document.name); assertNull(passage.memory)
        assertTrue(passage.why.single().contains("passage 1 of club-minutes.md"))
        assertTrue(hits.any { !it.isDocument }, "memories about the hexapod are still returned, as memories")
        // The managed copy is byte-identical; the supplied bytes are never modified.
        assertContentEquals(notes, File(doc.storedPath).readBytes())
        // A document supports a memory: the link is visible in the detail view.
        val project = corpus().getValue("project")
        svc.relate(Kind.MEMORY, project.id, Kind.DOCUMENT, doc.id, Relation.SUPPORTS)
        assertEquals(listOf("club-minutes.md"), svc.detail(project.id)!!.provenance.documents)
        // A file with nothing to read is attached but not claimed as understood: no passages come from it.
        val scan = core.archive.documents.import("scan.png", byteArrayOf(1, 2, 3, 4), allowNoText = true)
        assertEquals("no_text", scan.textStatus)
        assertTrue(svc.recall(RecallRequest("scan")).none { it.passage?.document?.id == scan.id })
        // PDF text is indexed too.
        val pdf = core.archive.documents.import("primus-notes.pdf", javaClass.getResourceAsStream("/archive/primus-notes.pdf")!!.readBytes())
        assertEquals("indexed", pdf.textStatus)
    }

    @Test fun retrievalWorksWithNoNetworkAtAll() {
        corpus()
        platform.fakeHttp.failWith = java.io.IOException("offline")
        val hits = svc.recall(RecallRequest("allergic penicillin", includeDocuments = true))
        assertEquals("Allergy", hits.first().memory!!.title)
        assertTrue(platform.fakeHttp.requests.isEmpty(), "retrieval never goes to the network: ${platform.fakeHttp.requests}")
    }

    /** A deterministic stand-in for a local embedding model: words with related meanings share an axis. It tests the fusion machinery, not model quality. */
    private class ConceptEmbedder : Embedder {
        override val id = "test-concepts"; override val dim = 4
        private val axes = listOf(setOf("car", "automobile", "vehicle", "drive"), setOf("tea", "beverage", "drink"), setOf("robot", "hexapod", "servo"), setOf("allergic", "allergy", "penicillin"))
        var calls = 0
        override fun embed(texts: List<String>): List<FloatArray> { calls++; return texts.map { t -> val w = t.lowercase().split(Regex("\\W+")); FloatArray(dim) { i -> w.count { it in axes[i] }.toFloat() } } }
    }

    @Test fun semanticMatchesAreAddedWhenALocalEmbedderHasIndexedVectorsAndNeverRequired() {
        mem("Old car", "I sold my old automobile before moving.", MemoryCategory.EXPERIENCE)
        val tea = mem("Evening drink", "A hot beverage helps me wind down.", MemoryCategory.PREFERENCE)
        // No embedder: pure keyword search, nothing for a synonym.
        assertTrue(svc.recall(RecallRequest("vehicle", includeDocuments = false)).isEmpty())
        val e = ConceptEmbedder(); svc.embedder = e
        assertTrue(svc.recall(RecallRequest("vehicle", includeDocuments = false)).isEmpty(), "an embedder without indexed vectors adds nothing")
        assertEquals(2, svc.indexVectors()); assertEquals(0, svc.indexVectors(), "unchanged records are not embedded again")
        val sem = svc.recall(RecallRequest("vehicle", includeDocuments = false))
        assertEquals("Old car", sem.single().memory!!.title); assertTrue(sem.single().why.single().startsWith("similar meaning"))
        // A keyword hit and a semantic hit on the same record are fused, not duplicated.
        assertEquals(1, svc.recall(RecallRequest("automobile", includeDocuments = false)).count { it.memory!!.title == "Old car" })
        assertEquals(tea.id, svc.recall(RecallRequest("drink", includeDocuments = false)).first().memory!!.id)
        // Editing a record makes its vector stale; the next backfill re-embeds only that one.
        core.memories.save(core.memories.get(tea.id)!!.copy(content = "A hot beverage helps me wind down, usually chamomile."))
        assertEquals(1, svc.indexVectors())
        // Deleting removes the vector.
        svc.forget(tea.id); assertEquals(1, svc.vectors.count(e.id))
        svc.embedder = null
        assertTrue(svc.recall(RecallRequest("vehicle", includeDocuments = false)).isEmpty(), "back to keywords only")
    }

    @Test fun recallingMarksMemoriesAsUsed() {
        val c = corpus()
        assertEquals(0, svc.metaOf(c.getValue("allergy")).accessCount)
        svc.recall(RecallRequest("penicillin")); svc.recall(RecallRequest("penicillin"))
        val m = svc.metaOf(core.memories.get(c.getValue("allergy").id)!!)
        assertEquals(2, m.accessCount); assertEquals(clock.now(), m.lastAccessed)
        svc.recall(RecallRequest("penicillin", touch = false))
        assertEquals(2, svc.meta(c.getValue("allergy").id)!!.accessCount, "browsing in the UI does not count as use")
    }

    @Test fun workingMemoryExpiresOnItsOwnAndStaysApartFromPersistentMemory() {
        core.settings.set(Settings.WORKING_HOURS, 2)
        val n = svc.working.put("Plan: finish the report, then call the clinic", WorkingMemory.Kind.PLAN, "conv1")
        svc.working.put("Parked on level 3", WorkingMemory.Kind.NOTE)
        assertEquals(2, svc.working.list("conv1").size); assertEquals(1, svc.working.list("other").size, "a conversation sees its own and global notes")
        assertEquals(0L, core.memories.count(), "never persistent memory")
        clock.advance(3 * 3_600_000L)
        assertTrue(svc.working.list().isEmpty(), "expired items are invisible at once")
        assertEquals(2, svc.working.purgeExpired()); assertEquals(0, svc.working.count())
        assertTrue(n.expiresAt - n.createdAt == 2 * 3_600_000L)
        // Temporary memories expire through the retention setting.
        core.settings.set(Settings.TEMP_RETENTION, 1)
        val t = mem("Parking", "level 3", MemoryCategory.TEMPORARY)
        clock.advance(2 * 86_400_000L)
        assertTrue(svc.recall(RecallRequest("parking level", includeDocuments = false)).isEmpty())
        assertEquals(1, core.memories.purgeExpired()); assertNull(core.memories.get(t.id)?.takeIf { !it.deleted })
    }

    @Test fun editingAndDeletingKeepOrRemoveTheRightThings() {
        val c = corpus()
        val tea = c.getValue("tea")
        val r = svc.remember(core.memories.get(tea.id)!!.copy(content = "I prefer black tea now, no sugar."))
        assertEquals(tea.id, r.memory.id)
        assertEquals("I prefer green tea in the evening and never drink coffee after 4 pm.", core.memories.revisions(tea.id).single().second.content, "the earlier version is kept")
        assertEquals(1, svc.detail(tea.id)!!.revisions.size)
        // Deleting is complete: record, earlier versions, relationships, metadata and vector.
        svc.relate(Kind.MEMORY, tea.id, Kind.MEMORY, c.getValue("sister").id, Relation.RELATED)
        svc.setMeta(tea.id, confidence = 0.7)
        svc.forget(tea.id)
        assertNull(svc.detail(tea.id)); assertTrue(core.memories.revisions(tea.id).isEmpty()); assertNull(svc.meta(tea.id))
        assertTrue(svc.relatedTo(c.getValue("sister").id).none { it.id == tea.id })
        assertTrue(svc.recall(RecallRequest("green tea")).none { it.memory?.id == tea.id })
    }

    @Test fun memoryAndItsMetadataSurviveARestartOnAFileDatabase() {
        val f = Files.createTempFile("clu-restart", ".db").toFile().also { listOf("", "-journal", "-wal", "-shm").forEach { s -> File(it.path + s).deleteOnExit() } }
        val first = newCore(JdbcDriver(f.absolutePath), clock = clock)
        val id = first.memory.remember(Memory(category = MemoryCategory.CORE_FACT, title = "Allergy", content = "Allergic to penicillin.", source = MemorySource.OWNER_SUPPLIED, importance = 2),
            sourceRef = "conversation:c1", confidence = 0.95).memory.id
        first.memory.recall(RecallRequest("penicillin"))
        first.store.db.close()
        val second = newCore(JdbcDriver(f.absolutePath), clock = clock)
        val hit = second.memory.recall(RecallRequest("penicillin allergy", touch = false)).single()
        assertEquals(id, hit.memory!!.id)
        val meta = second.memory.meta(id)!!
        assertEquals("conversation:c1", meta.sourceRef); assertEquals(0.95, meta.confidence); assertEquals(1, meta.accessCount)
    }

    @Test fun upgradingAVersion4DatabaseKeepsEverythingAndAddsTheNewTables() {
        val db = tempDb()
        Schema.migrations.take(4).forEach { step -> step.forEach { db.execute(it) } }; db.userVersion = 4
        db.execute("""INSERT INTO memories(id, category, title, content, summary, retain_mode, event_at, event_has_time, source, people, tags, pinned, scope,
            expires_at, created_at, updated_at, deleted, hlc, origin) VALUES('m1','PREFERENCE','Tea','Likes masala chai.',NULL,'FULL',NULL,0,'OWNER_SUPPLIED','[]','[]',0,'clu',NULL,1700000000000,1700000000000,0,'h','o')""")
        db.execute("INSERT INTO memory_terms(memory_id, term, tf) VALUES('m1','chai',1)")
        db.execute("""INSERT INTO memory_proposals(id, kind, text, rationale, status, created_at, fingerprint) VALUES('p1','OBSERVATION','Likes chai','seen twice','PENDING',1,'fp')""")
        val c = newCore(db = db)
        assertEquals(Schema.currentVersion, db.userVersion); assertEquals(6, Schema.currentVersion, "the current schema is v6 (M7 added the agent tables)")
        assertEquals("Likes masala chai.", c.memories.get("m1")!!.content)
        assertEquals(MemoryKind.CORE, c.memory.metaOf(c.memories.get("m1")!!).kind, "records without metadata get derived defaults")
        assertEquals("m1", c.memory.recall(RecallRequest("chai", touch = false)).single().memory!!.id)
        val p = c.proposals.pending().single(); assertNull(p.category); assertEquals("", p.sourceRef)
        // And it is a real migration: a failure rolls everything back.
        val broken = Schema.migrations + listOf(listOf("ALTER TABLE memory_meta ADD COLUMN x TEXT", "THIS IS NOT SQL"))
        assertFails { Schema.migrate(db, target = broken.size, steps = broken) }
        assertEquals(Schema.currentVersion, db.userVersion, "a failed migration leaves the database as it was")
        assertEquals(0L, db.query("SELECT COUNT(*) FROM pragma_table_info('memory_meta') WHERE name = 'x'") { it.long(0) }.first())
    }

    @Test fun manyThreadsCanRememberRecallAndReinforceAtOnce() {
        val seed = corpus()
        val errors = CopyOnWriteArrayList<Throwable>()
        val pool = Executors.newFixedThreadPool(8)
        val writes = java.util.concurrent.atomic.AtomicInteger()
        repeat(8) { t ->
            pool.execute {
                runCatching {
                    repeat(25) { i ->
                        when ((t + i) % 4) {
                            0 -> { mem("Thread $t note $i", "Unique note number $i from worker $t about calibration", MemoryCategory.KNOWLEDGE); writes.incrementAndGet() }
                            1 -> assertTrue(svc.recall(RecallRequest("hexapod gait servo")).isNotEmpty())
                            2 -> svc.reinforce(seed.getValue("tea").id)
                            else -> svc.working.put("t$t i$i", WorkingMemory.Kind.NOTE)
                        }
                    }
                }.onFailure { errors += it }
            }
        }
        pool.shutdown(); assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertTrue(errors.isEmpty(), errors.joinToString { it.toString() })
        assertEquals(seed.size + writes.get(), core.memories.count().toInt())
        assertEquals(50, svc.meta(seed.getValue("tea").id)!!.reinforced, "every reinforcement was counted exactly once")
        assertTrue(core.memoryHealth.check().let { it.ok && it.issues.none { i -> i.code != "expired" } }, core.memoryHealth.check().toString())
    }
}
