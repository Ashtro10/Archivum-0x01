package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.conversation.Role
import ai.clu.core.db.Schema
import ai.clu.core.history.*
import ai.clu.core.memory.*
import ai.clu.core.security.Actor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import java.util.TimeZone
import kotlin.test.*

/**
 * Historical memory system (1.8.0): simulated time, historical dates, the People catalog, documents, recall in
 * conversation, historical perspective, duplicates/conflicts, bulk import, backup and upgrades. Real CluCore, real
 * SQLite, real agent path (scripted model), real document parsers on files made by fpdf2, python-docx and LibreOffice.
 * The device clock is 9 October 2025 (ManualClock); the simulated date, when on, is 29 October 2026.
 */
class HistoryArchiveTest {
    private val platform = TestPlatform()
    private val core = newCore(platform = platform)
    private val engine = ScriptedEngine().also { core.useEngine(it) }
    private val archive get() = core.archive
    private val conv = core.conversations.create("history").id

    private fun simulate(date: String = "2026-10-29", mode: String = "fixed") { core.settings.set(Settings.SIMULATED_DATE, date); core.settings.set(Settings.SIMULATED_TIME, mode) }
    private fun d(s: String) = DateParser.parse(s)!!
    private fun mem(title: String, date: String, content: String = "", people: List<String> = emptyList(), category: MemoryCategory = MemoryCategory.EXPERIENCE,
                    kind: String = "", importance: Int = 0, told: String? = null, places: List<String> = emptyList()) =
        archive.saveMemory(Memory(category = category, title = title, content = content, source = MemorySource.OWNER_SUPPLIED, people = people,
            eventDate = d(date), threadKind = kind, importance = importance, told = told?.let(::d), places = places)).memory
    private fun ask(text: String, c: CluCore = core, conversation: String = conv) = runBlocking {
        c.agent.respond(TurnRequest(conversation, text, Actor.ownerInApp())).toList() }.filterIsInstance<AgentEvent.Done>().single().text
    private fun resource(name: String) = File("src/test/resources/archive/$name").readBytes()

    // 1. The simulated current date is configured independently of the device date.
    @Test fun simulatedDateIsIndependentOfTheDeviceDate() {
        assertEquals(LocalDate.of(2025, 10, 9), core.time.today(), "off: the device date")
        simulate()
        assertEquals(LocalDate.of(2026, 10, 29), core.time.today())
        assertEquals(LocalDate.of(2025, 10, 9), core.time.realToday(), "the real clock stays available internally")
        engine.reply("Fine.")
        ask("How are you today?")
        val ctx = engine.lastSystem
        assertTrue("Today's date in your timeline with the owner: Thursday 29 October 2026" in ctx, ctx)
        assertFalse("2025" in ctx, "the device date is not narrated as Clu's date")
        assertTrue("Your history with the owner began in January 2018" in ctx, "identity follows the configured timeline, not the install date")
        val tool = runBlocking { core.tools.execute("time.now", kotlinx.serialization.json.buildJsonObject { }, ai.clu.core.tools.ToolContext(Actor.ownerInApp(),
            core.personas.active(), conv, core.clock.now())) }
        assertTrue(tool.summary.contains("29 October 2026"), tool.summary)
        // Advancing: moves on with real days from when it was set.
        simulate(mode = "advancing"); (core.clock as ai.clu.core.util.ManualClock).advance(3 * 86_400_000L)
        assertEquals(LocalDate.of(2026, 11, 1), core.time.today())
    }

    // 2. A memory entered today about March 2018 stays a March 2018 memory.
    @Test fun aMemoryEnteredTodayKeepsItsHistoricalDate() {
        val db = tempDb()
        val c1 = newCore(db = db)
        val m = c1.archive.saveMemory(Memory(category = MemoryCategory.EXPERIENCE, title = "Met Riya at the college fest", content = "I met Riya at the college fest in Pune.",
            source = MemorySource.OWNER_SUPPLIED, eventDate = d("March 2018"))).memory
        c1.shutdown()
        val c2 = newCore(db = db)
        val back = c2.memories.get(m.id)!!
        assertEquals("March 2018", back.historicalDate().label()); assertEquals(DatePrecision.MONTH, back.historicalDate().precision)
        assertEquals(1_760_000_000_000L, back.createdAt, "the recording time is kept separately (2025)")
        assertNull(back.eventAt, "no invented exact day")
        val line = ai.clu.core.tools.PromptMemoryFormat.line(back, null, LocalDate.of(2026, 10, 29))
        assertTrue(line.startsWith("- [March 2018, about eight years ago]"), line)
        assertFalse("2025" in line, "the entry date never reaches the prompt")
        c2.shutdown()
    }

    // 3. Approximate dates and ranges survive saving and reopening (and no time zone shifts a date-only memory).
    @Test fun approximateDatesAndRangesSurviveReopening() {
        val db = tempDb()
        val inputs = listOf("March 2018" to "March 2018", "Around March 2018" to "around March 2018", "Somewhere between 2018 and 2019" to "between 2018 and 2019",
            "12 March 2018" to "12 March 2018", "Date unknown" to "date unknown", "early 2019" to "roughly between January 2019 and April 2019", "2018-03-12" to "12 March 2018")
        val tz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")) // UTC+14
            val c1 = newCore(db = db)
            val ids = inputs.map { (raw, _) -> c1.archive.saveMemory(Memory(category = MemoryCategory.EXPERIENCE, title = raw, content = "x", source = MemorySource.OWNER_SUPPLIED,
                eventDate = DateParser.parse(raw)!!)).memory.id }
            c1.shutdown()
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles")) // UTC-7/-8
            val c2 = newCore(db = db)
            ids.zip(inputs).forEach { (id, io) -> assertEquals(io.second, c2.memories.get(id)!!.historicalDate().label(), io.first) }
            c2.shutdown()
        } finally { TimeZone.setDefault(tz) }
        assertNull(DateParser.parse("Marhc 2018"), "an unreadable date is not guessed")
    }

    // 4. Elapsed time uses the configured simulated date.
    @Test fun elapsedTimeUsesTheSimulatedDate() {
        val riya = archive.people.save(ArchivePerson(name = "Riya"))
        mem("Met Riya", "March 2018", "I met Riya at the college fest.", people = listOf("Riya"))
        assertEquals("about seven years ago", TimePhrases.ago(d("March 2018"), core.time.today()), "device date 2025")
        simulate()
        assertEquals("about eight years ago", TimePhrases.ago(d("March 2018"), core.time.today()))
        val a = ask("Do you remember Riya?")
        assertTrue(a.startsWith("Yes, I remember Riya. You first told me about Riya back in March 2018 — that's about eight years ago."), a)
        assertTrue("Back in March 2018, you met Riya at the college fest." in a, a)
        assertEquals(0, engine.prompts.size, "answered from the records, not the model")
        assertTrue(archive.links.linked(Kind.PERSON, riya.id, Kind.MEMORY).isNotEmpty())
    }

    // 5. A natural-language question retrieves the matching historical memory.
    @Test fun aNaturalQuestionRetrievesTheMemory() {
        simulate()
        archive.people.save(ArchivePerson(name = "Amit Sarkar", aliases = listOf("Sarkar"), relation = "lab supervisor"))
        mem("Sarkar reviewed the dataset", "June 2019", "Sarkar agreed to review my dataset before the summer break.", people = listOf("Sarkar"))
        mem("Fest", "12 March 2018", "I went to the college fest with Karan.", people = listOf("Karan"))
        val a = ask("What did I tell you about Sarkar?")
        assertTrue(a.startsWith("Yes, I remember Amit Sarkar."), a)
        assertTrue("Back in June 2019, Sarkar agreed to review your dataset before the summer break." in a, a)
        assertTrue("your lab supervisor" in a)
        val p = ask("Do you remember what happened in March 2018?")
        assertEquals("Here's what you told me about March 2018: On 12 March 2018, you went to the college fest with Karan. That's everything I have for that period.", p)
        assertEquals(0, engine.prompts.size)
    }

    // 6. A follow-up ("that girl", "who was with me", "what happened after that") resolves from the conversation.
    @Test fun followUpsResolveToThePersonBeingDiscussed() {
        simulate()
        archive.people.save(ArchivePerson(name = "Riya", relation = "friend from college"))
        archive.people.save(ArchivePerson(name = "Ananya", notes = "A girl from the robotics club."))
        mem("Met Riya", "March 2018", "I met Riya, a girl from Pune, at the college fest.", people = listOf("Riya", "Karan"), places = listOf("Pune"))
        mem("Band with Riya", "April 2018", "Riya and I started a band.", people = listOf("Riya"))
        assertTrue(ask("Tell me about Riya").startsWith("Yes, I remember Riya."))
        val a = ask("Do you remember that girl I told you about?")
        assertTrue(a.startsWith("Yes, I remember Riya."), "the girl in this conversation, not a guess: $a")
        assertEquals("From what you told me, Riya and Karan were there. It was in Pune.", ask("Who was with me at the time?"))
        assertEquals("After that, in April 2018, Riya and you started a band.", ask("What happened after that?"))
        // In a new conversation with two girls on record and no context, Clu asks.
        val fresh = core.conversations.create("x").id
        val q = ask("Do you remember that girl I told you about?", conversation = fresh)
        assertTrue(q.startsWith("Do you mean "), q); assertTrue("Riya" in q && "Ananya" in q, q)
        assertTrue(ask("Ananya", conversation = fresh).startsWith("Yes, I remember Ananya."))
        assertEquals(0, engine.prompts.size)
    }

    // 7. Ambiguous people trigger one clarification; unknown subjects get an honest answer.
    @Test fun ambiguityIsClarifiedAndNothingIsFabricated() {
        archive.people.save(ArchivePerson(name = "Rahul Sharma", relation = "cousin"))
        archive.people.save(ArchivePerson(name = "Rahul Verma", relation = "colleague"))
        mem("Rahul Verma's offer", "2021", "Rahul Verma offered me a job at his startup.", people = listOf("Rahul Verma"))
        assertEquals("Do you mean Rahul Sharma (your cousin) or Rahul Verma (your colleague)?", ask("What did I tell you about Rahul?"))
        val a = ask("the colleague")
        assertTrue(a.startsWith("Yes, I remember Rahul Verma."), a); assertTrue("offered you a job" in a, a)
        assertEquals("I don't have anything stored about Zorblax. If you tell me about it, I can remember it.", ask("Do you remember Zorblax?"))
        assertEquals("No, I don't have that in your archive. If you'd like me to remember it, just say so.", ask("Have I told you about the trip to Ladakh before?"))
        assertEquals(0, engine.prompts.size, "no model call: nothing to invent")
        // A general question about someone not in the archive is not hijacked (it goes the normal way).
        engine.reply("Albert Einstein was a physicist.")
        ask("Who was Albert Einstein?")
        assertTrue(engine.prompts.isNotEmpty())
    }

    // 8. The People catalog links to related memories (names, aliases, spelling variants).
    @Test fun peopleCatalogLinksToMemories() {
        val p = archive.people.save(ArchivePerson(name = "Riya Kapoor", aliases = listOf("Ri"), relation = "friend"))
        val a = mem("Coffee", "2019", "Coffee with Riya Kapoor.", people = listOf("Riya Kapoor"))
        val b = mem("Call", "2020", "Long call with Ri.", people = listOf("Ri"))
        val c = mem("Trip", "2021", "Trip with Riyaa.", people = listOf("Riyaa")) // spelling variant
        assertEquals(setOf(a.id, b.id, c.id), archive.links.linked(Kind.PERSON, p.id, Kind.MEMORY).toSet())
        assertEquals(listOf(a.id, b.id, c.id), archive.memoriesOf(p).map { it.id }, "oldest first")
        archive.people.save(ArchivePerson(name = "Riya Sen"))
        val r = archive.saveMemory(Memory(category = MemoryCategory.EXPERIENCE, title = "Dinner", content = "Dinner with Riya.", source = MemorySource.OWNER_SUPPLIED, people = listOf("Riya")))
        assertEquals(setOf("Riya Kapoor", "Riya Sen"), r.ambiguousPeople["Riya"]!!.map { it.name }.toSet(), "two Riyas: not linked by guess")
        assertTrue(archive.people.duplicatesOf(ArchivePerson(name = "Riya Kapur")).any { it.name == "Riya Kapoor" }, "a likely duplicate profile is detected")
    }

    // 9. Supported documents are imported, indexed, searched and linked; bad files are refused clearly.
    @Test fun documentsAreImportedIndexedSearchedAndLinked() {
        val primus = archive.documents.import("primus-notes.pdf", resource("primus-notes.pdf"), date = d("June 2019"))
        val riya = archive.documents.import("riya-letter.pdf", resource("riya-letter-unicode.pdf"))
        val astra = archive.documents.import("astra-log.docx", resource("astra-log.docx"))
        val lo = archive.documents.import("astra-log-lo.pdf", resource("astra-log-libreoffice.pdf"))
        val md = archive.documents.import("notes.md", "# Primus\n\nThe first Primus prototype ran on a Raspberry Pi.".toByteArray())
        assertTrue("Sarkar agreed to review the dataset" in archive.documents.text(primus.id), archive.documents.text(primus.id))
        assertTrue("café evenings with Karan and Ananya" in archive.documents.text(riya.id), "embedded font with ToUnicode: ${archive.documents.text(riya.id)}")
        assertTrue("abandoned the cloud-only approach" in archive.documents.text(astra.id))
        assertTrue("abandoned the cloud-only approach" in archive.documents.text(lo.id), "LibreOffice PDF: ${archive.documents.text(lo.id)}")
        assertEquals("indexed", md.textStatus)
        assertEquals(primus.id, archive.documents.search("dataset review").first().document.id)
        assertTrue(File(primus.storedPath).exists(), "a managed local copy")
        val p = archive.people.save(ArchivePerson(name = "Ananya"))
        archive.links.link(Kind.DOCUMENT, riya.id, Kind.PERSON, p.id, Relation.ABOUT)
        assertEquals(listOf(riya.id), archive.documentsOf(Kind.PERSON, p.id).map { it.id })
        val a = ask("What does my document say about the dataset?")
        assertTrue(a.startsWith("Your document \"primus-notes.pdf\" says:") && "Sarkar agreed to review the dataset" in a && a.endsWith("I haven't verified it."), a)
        // Refusals, with nothing stored.
        val before = archive.documents.list().size
        fun refused(name: String, bytes: ByteArray) = assertFailsWith<DocumentImportException> { archive.documents.import(name, bytes) }.reason
        assertEquals(DocumentImportException.Reason.CORRUPT, refused("corrupt.pdf", resource("corrupt.pdf")))
        assertEquals(DocumentImportException.Reason.CORRUPT, refused("not-a-docx.docx", resource("not-a-docx.docx")))
        assertEquals(DocumentImportException.Reason.UNSUPPORTED, refused("unsupported.xlsx", resource("unsupported.xlsx")))
        assertEquals(DocumentImportException.Reason.EMPTY, refused("empty.txt", ByteArray(0)))
        assertEquals(DocumentImportException.Reason.DUPLICATE, refused("again.pdf", resource("primus-notes.pdf")))
        assertEquals(DocumentImportException.Reason.NO_TEXT, refused("photo-scan.pdf", "%PDF-1.4\n1 0 obj <</Type/Page>> endobj\n".toByteArray()))
        assertEquals(before, archive.documents.list().size)
        val photo = archive.documents.import("photo.jpg", byteArrayOf(-1, -40, -1, 0, 1, 2), description = "Fest photo")
        assertEquals("no_text", photo.textStatus, "an image is kept as an attachment, clearly without searchable text")
    }

    // 10. Duplicates and conflicts are flagged; nothing is silently destroyed.
    @Test fun duplicatesAndConflictsNeverDestroyInformation() {
        val a = mem("Met Riya at the college fest", "March 2018", "I met Riya at the fest.", people = listOf("Riya"))
        val dup = archive.saveMemory(Memory(category = MemoryCategory.EXPERIENCE, title = "Met Riya at the college fest", content = "Met Riya at the college fest.",
            source = MemorySource.OWNER_SUPPLIED, eventDate = d("around March 2018"), people = listOf("Riya")))
        assertEquals(listOf(a.id), dup.duplicates.map { it.id }, "a likely duplicate is reported, not merged")
        val other = archive.saveMemory(Memory(category = MemoryCategory.EXPERIENCE, title = "Met Riya at the college fest", content = "It was the spring fest.",
            source = MemorySource.OWNER_SUPPLIED, eventDate = d("April 2019"), people = listOf("Riya")))
        assertTrue(other.conflicts.isNotEmpty() && other.conflicts.all { it.field == "date" && it.aValue == "April 2019" })
        assertTrue(other.conflicts.any { it.bId == a.id && it.bValue == "March 2018" }, other.conflicts.toString())
        assertEquals("March 2018", core.memories.get(a.id)!!.historicalDate().label(), "neither record was changed")
        assertEquals("April 2019", core.memories.get(other.memory.id)!!.historicalDate().label())
        // Merge keeps both texts and the conflicting date stays flagged; the merged record is in the history.
        val merged = archive.mergeMemories(a.id, other.memory.id)
        assertTrue("I met Riya at the fest." in merged.content && "It was the spring fest." in merged.content, merged.content)
        assertEquals("March 2018", merged.historicalDate().label())
        assertTrue(core.memories.get(other.memory.id)!!.deleted)
        assertEquals(a.id, core.memories.revisions(other.memory.id).first().second.mergedInto)
        assertTrue(core.memories.revisions(a.id).any { it.second.content == "I met Riya at the fest." }, "the pre-merge version is kept")
        assertTrue(archive.conflicts.list("OPEN").isNotEmpty())
        // Resolving uses one value for both, keeping the replaced value in the history.
        val b = mem("Graduation", "June 2021", "Graduated.")
        val c = mem("Graduation", "July 2021", "Graduated with honours.")
        val cf = archive.conflicts.involving(c.id).single()
        archive.resolveConflict(cf.id, if (cf.aId == b.id) "a" else "b")
        assertEquals("June 2021", core.memories.get(c.id)!!.historicalDate().label())
        assertEquals("July 2021", core.memories.revisions(c.id).first().second.historicalDate().label())
        // People: merging profiles with different details opens conflicts, aliases are combined.
        val p1 = archive.people.save(ArchivePerson(name = "Karan Mehta", relation = "classmate"))
        val p2 = archive.people.save(ArchivePerson(name = "Karan M.", relation = "bandmate", aliases = listOf("KM")))
        val mp = archive.mergePeople(p1.id, p2.id)
        assertEquals("classmate", mp.relation); assertTrue(mp.aliases.containsAll(listOf("Karan M.", "KM")))
        assertTrue(archive.conflicts.list("OPEN").any { it.kind == Kind.PERSON && it.field == "relation" && it.bValue == "bandmate" })
        assertNull(archive.people.get(p2.id))
    }

    // 11. Historical perspective uses only what was known by the cutoff.
    @Test fun historicalPerspectiveRespectsTheCutoff() {
        archive.projects.save(Project(name = "Primus"))
        mem("Primus started", "May 2018", "We started Primus on my laptop.", kind = "plan")
        mem("Primus moved", "2024", "We moved Primus to a GPU server.", kind = "change")
        mem("Old school story", "2010", "I won a quiz in school.", told = "2025-01-10") // told to Clu only in 2025
        core.settings.set(Settings.HISTORY_PERSPECTIVE, "2019-01-01")
        assertEquals(LocalDate.of(2019, 1, 1), core.time.today())
        val a = ask("What did we change later about Primus?")
        assertFalse("GPU server" in a, a)
        assertEquals("That's after 1 January 2019 — in this historical perspective I don't know about it yet.", ask("Do you remember what happened in 2024?"))
        assertEquals("I don't have anything recorded for 2010. That's a gap in what you've told me, not proof that nothing happened.", ask("Do you remember what happened in 2010?"),
            "an event from 2010 that Clu only heard about in 2025 isn't known in 2019")
        engine.reply("OK.")
        ask("Tell me how Primus is going on the GPU server")
        assertFalse("GPU server" in engine.lastSystem.substringAfter("Relevant memories", ""), "future memories don't reach the model either")
        core.settings.set(Settings.HISTORY_PERSPECTIVE, "")
        assertTrue("GPU server" in ask("What did we change later about Primus?"))
    }

    // 12. Export and backup keep dates, relationships and document metadata.
    @Test fun backupPreservesDatesRelationshipsAndDocuments() {
        val p = archive.people.save(ArchivePerson(name = "Riya", relation = "friend", firstKnown = d("March 2018"),
            importantDates = listOf(ImportantDate("Birthday", d("14 August 2000"), remind = true))))
        val pr = archive.projects.save(Project(name = "Primus", started = d("2018")))
        val m = archive.saveMemory(Memory(category = MemoryCategory.EXPERIENCE, title = "Met Riya", content = "Met Riya.", source = MemorySource.OWNER_SUPPLIED,
            people = listOf("Riya"), eventDate = d("around March 2018"), told = d("April 2018"), places = listOf("Pune"), certainty = Certainty.CONFIRMED, importance = 2),
            projectIds = listOf(pr.id)).memory
        val doc = archive.documents.import("primus-notes.pdf", resource("primus-notes.pdf"), date = d("June 2019"), tags = listOf("primus"))
        archive.links.link(Kind.DOCUMENT, doc.id, Kind.MEMORY, m.id, Relation.SUPPORTS)
        val json = archive.exportBundle(includeFiles = true)

        val other = newCore(platform = TestPlatform())
        val preview = other.archive.previewBundle(json)
        assertEquals(1, preview.newMemories); assertEquals(1, preview.newDocuments)
        other.archive.restoreBundle(preview)
        val back = other.memories.get(m.id)!!
        assertEquals("around March 2018", back.historicalDate().label()); assertEquals("April 2018", back.told!!.label())
        assertEquals(listOf("Pune"), back.places); assertEquals(Certainty.CONFIRMED, back.certainty); assertEquals(2, back.importance)
        assertEquals("14 August 2000", other.archive.people.get(p.id)!!.importantDates.single().date.label())
        assertTrue(other.archive.links.linked(Kind.MEMORY, m.id, Kind.PERSON).contains(p.id))
        assertTrue(other.archive.links.linked(Kind.MEMORY, m.id, Kind.PROJECT).contains(pr.id))
        assertTrue(other.archive.links.linked(Kind.MEMORY, m.id, Kind.DOCUMENT).contains(doc.id))
        val d2 = other.archive.documents.get(doc.id)!!
        assertEquals("June 2019", d2.date.label()); assertEquals(listOf("primus"), d2.tags); assertEquals(doc.sha256, d2.sha256)
        assertTrue(File(d2.storedPath).readBytes().contentEquals(resource("primus-notes.pdf")), "the file itself is restored")
        assertEquals(doc.id, other.archive.documents.search("dataset").first().document.id)
        // Restoring again adds nothing (no duplicates).
        assertEquals(0, other.archive.restoreBundle(other.archive.previewBundle(json)))
        assertFailsWith<IllegalArgumentException> { other.archive.previewBundle("{\"format\":\"something-else\"}") }
        assertFailsWith<IllegalArgumentException> { other.archive.previewBundle(json.take(json.length / 2)) }
    }

    // 13. Existing memories survive the upgrade; a failed migration changes nothing.
    @Test fun existingMemoriesSurviveTheUpgrade() {
        val db = tempDb()
        Schema.migrate(db, target = 3) // a 1.7.0 database
        db.execute("""INSERT INTO memories(id, category, title, content, summary, retain_mode, event_at, event_has_time, source, people, tags, pinned, scope,
            expires_at, created_at, updated_at, deleted, hlc, origin) VALUES('old1','CORE_FACT','Exam','Exam on Friday.',NULL,'FULL',1520856000000,0,'OWNER_SUPPLIED',
            '["Riya"]','["school"]',1,'clu',NULL,1700000000000,1700000000000,0,'h','o')""")
        db.execute("""INSERT INTO memories(id, category, title, content, summary, retain_mode, event_at, event_has_time, source, people, tags, pinned, scope,
            expires_at, created_at, updated_at, deleted, hlc, origin) VALUES('old2','PREFERENCE','Tea','Likes masala chai.',NULL,'FULL',NULL,0,'OWNER_SUPPLIED',
            '[]','[]',0,'clu',NULL,1700000000000,1700000000000,0,'h','o')""")
        db.execute("INSERT INTO memory_terms(memory_id, term, tf) VALUES('old1','exam',1)")
        db.execute("INSERT INTO diary_entries(id, entry_date, title, body, source, created_at, updated_at, deleted, hlc, origin) VALUES('d1','2025-01-01','Day','A day.','manual',1,1,0,'','')")
        // A broken future step: rolled back entirely, version unchanged, data intact.
        val broken = Schema.migrations + listOf(listOf("ALTER TABLE memories ADD COLUMN extra TEXT", "THIS IS NOT SQL"))
        assertFails { Schema.migrate(db, target = broken.size, steps = broken) }
        assertEquals(3, db.userVersion)
        assertEquals(2L, db.query("SELECT COUNT(*) FROM memories") { it.long(0) }.first())
        val c = newCore(db = db)
        assertEquals(Schema.currentVersion, db.userVersion)
        val old = c.memories.get("old1")!!
        assertEquals("Exam on Friday.", old.content); assertTrue(old.pinned); assertEquals(listOf("Riya"), old.people)
        assertEquals(DatePrecision.DAY, old.historicalDate().precision)
        assertEquals(HistoricalDate.fromEpoch(1520856000000L).label(), old.historicalDate().label(), "the legacy timestamp becomes a date")
        assertFalse(c.memories.get("old2")!!.historicalDate().known)
        assertEquals("A day.", c.diary.get("d1")!!.body)
        assertTrue(c.memories.rank("exam").any { it.memory.id == "old1" }, "still retrievable")
        c.shutdown()
    }

    // 14. Core memory functions work offline (no network request at all).
    @Test fun memoryFunctionsWorkOffline() {
        platform.online = false
        simulate()
        archive.people.save(ArchivePerson(name = "Riya"))
        mem("Met Riya", "March 2018", "I met Riya at the fest.", people = listOf("Riya"))
        archive.documents.import("primus-notes.pdf", resource("primus-notes.pdf"))
        assertTrue(ask("Do you remember Riya?").startsWith("Yes, I remember Riya."))
        assertTrue(ask("Do you remember what happened in March 2018?").contains("you met Riya at the fest"))
        assertTrue(archive.documents.search("dataset").isNotEmpty())
        assertTrue(archive.timeline().isNotEmpty()); archive.gaps()
        val pv = archive.previewImport("2019 | Started Primus | people: Riya"); archive.applyImport(pv)
        assertEquals(0, platform.fakeHttp.requests.size, "nothing left the device")
    }

    // 15. Existing conversation and memory features keep working.
    @Test fun existingConversationAndMemoryFeaturesStillWork() {
        assertTrue(ask("Remember that my favourite tea is masala chai").isNotBlank())
        assertTrue(core.memories.search("masala chai").isNotEmpty(), "\"remember that…\" still saves")
        engine.reply("Masala chai.")
        assertEquals("Masala chai.", ask("Which tea do I like best? masala chai or green"))
        assertTrue("masala chai" in engine.lastSystem, "ordinary retrieval still feeds the model")
        engine.reply("Two plus two is four.")
        assertEquals("Two plus two is four.", ask("What is two plus two?"))
    }

    // 17. Failures: unknown dates, invalid imports, interrupted imports and recovery.
    @Test fun importFailuresAreReportedAndInterruptedImportsResume() {
        val bad = archive.previewImport("""
            March 2018 | Met Riya at the fest | people: Riya, Karan | tags: college
            Marhc 2018 | Typo date
            | | only pipes
            Date unknown | Something I can't date
            2019 | Primus kickoff | project: Primus | kind: plan | importance: 5
            around 2020 | Lockdown band practice | certainty: maybe
            this line has no structure
        """.trimIndent())
        assertEquals(listOf("Met Riya at the fest", "Something I can't date"), bad.rows.map { it.memory.title })
        assertFalse(bad.rows[1].memory.historicalDate().known, "an unknown date stays unknown")
        assertEquals(listOf(2, 3, 5, 6, 7), bad.errors.map { it.line }, bad.errors.toString())
        assertTrue(bad.errors.first().message.contains("Marhc 2018"))
        assertEquals(listOf("Riya", "Karan"), bad.newPeople)
        assertEquals(0, core.memories.count(), "a preview saves nothing")
        assertTrue(archive.previewImport("[{\"title\": broken").errors.isNotEmpty(), "invalid JSON is reported")

        // A larger CSV, interrupted after the first batch, then applied again.
        val csv = buildString { appendLine("date,title,description,people,tags"); (1..60).forEach { appendLine("${2018 + it % 8},Event $it,\"Detail $it, with a comma\",Riya,history") } }
        val pv = archive.previewImport(csv, "history.csv")
        assertEquals(60, pv.importable.size)
        var calls = 0
        val first = archive.applyImport(pv, batch = 25, shouldStop = { calls++ >= 1 })
        assertEquals("interrupted", first.status); assertEquals(25, first.imported)
        assertTrue(archive.unfinishedImports().any { it.first == first.jobId })
        val again = archive.applyImport(archive.previewImport(csv, "history.csv"))
        assertEquals("done", again.status); assertEquals(35, again.imported)
        assertEquals(60 + 0, core.memories.list(scope = null, limit = 1000).count { it.title.startsWith("Event ") }, "no duplicates after resuming")
        assertEquals("Detail 7, with a comma", core.memories.search("Event 7").first { it.title == "Event 7" }.content)

        // A batch that fails part-way is rolled back to the last complete batch.
        core.settings.set(Settings.MEMORY_ENABLED, false)
        val failed = archive.applyImport(archive.previewImport("2020 | One more | people: Riya"))
        assertEquals("failed", failed.status); assertEquals(0, failed.imported)
        core.settings.set(Settings.MEMORY_ENABLED, true)
    }

    // Projects: the plan, what changed, what was abandoned, the last important decision.
    @Test fun projectHistoryIsAnsweredFromTheRecords() {
        simulate()
        val primus = archive.projects.save(Project(name = "Primus", aliases = listOf("Project Primus")))
        fun add(t: String, date: String, c: String, kind: String, imp: Int = 0) = archive.saveMemory(Memory(category = MemoryCategory.PROJECT, title = t, content = c,
            source = MemorySource.OWNER_SUPPLIED, eventDate = d(date), threadKind = kind, importance = imp), projectIds = listOf(primus.id)).memory
        add("Original plan", "May 2018", "The plan was to run Primus entirely on my laptop.", "plan")
        add("Cloud attempt", "2019", "We tried hosting Primus in the cloud but abandoned it because of cost.", "abandoned")
        add("Move to GPU", "March 2021", "We moved Primus to a local GPU server.", "change")
        add("Use SQLite", "2022", "We decided to keep all of Primus's memory in SQLite.", "decision", imp = 1)
        add("Rename logs", "2023", "We decided to rename the log files.", "decision")
        assertEquals("The original plan for Primus, as you recorded it: Back in May 2018, the plan was to run Primus entirely on your laptop. It changed later (2 recorded changes).",
            ask("What was our original plan for Primus?"))
        assertTrue(ask("What did we change later about Primus?").contains("Back in March 2021, we moved Primus to a local GPU server."))
        assertTrue(ask("Which approach did we abandon in Primus, and why?").contains("abandoned it because of cost"))
        assertEquals("The last important decision about Primus: Back in 2022, we decided to keep all of Primus's memory in SQLite.",
            ask("What was the last important decision we made about Primus?"))
        assertTrue(ask("What did we decide about Primus?").contains("rename the log files"))
        assertEquals(0, engine.prompts.size)
        assertEquals("I don't have anything recorded about Zephyr.", ask("What did we decide about Zephyr?"))
    }

    @Test fun howLongAgoAndHaveIToldYouBefore() {
        simulate()
        archive.people.save(ArchivePerson(name = "Riya"))
        mem("Met Riya", "March 2018", "I met Riya at the fest.", people = listOf("Riya"), told = "April 2018")
        assertEquals("You first told me about Riya back in April 2018 — that's about eight years ago.", ask("How long ago did I first tell you about Riya?"))
        ask("Do you remember Riya?")
        assertEquals("You first told me about Riya back in April 2018 — that's about eight years ago.", ask("How long ago did I first tell you about this?"))
        val x = core.conversations.create("y").id
        core.conversations.addMessage(x, Role.USER, "I met Riya at the fest, you know.")
        assertTrue(ask("Have I told you this before?", conversation = x).startsWith("Yes — you told me about this back in April 2018."), ask("Have I told you this before?", conversation = x))
        core.settings.set(Settings.TOLD_DEFAULT, "unknown")
        mem("Karan's band", "2019", "Karan joined the band.", people = listOf("Karan"))
        archive.people.save(ArchivePerson(name = "Karan"))
        assertEquals("I don't have a record of when you first told me about Karan. The earliest event you recorded about it is from 2019.", ask("When did I first tell you about Karan?"))
    }

    @Test fun timelineGapsAndReminders() {
        simulate("2019-12-31")
        mem("Fest", "March 2018", "Fest.")
        mem("Exams", "May 2018", "Exams.")
        mem("Summer", "2019", "Year-level record.")
        val gaps = archive.gaps()
        assertEquals(listOf("June 2018 – December 2019"), gaps.map { it.label() }, "gaps of three months or more")
        assertEquals(1, gaps.single().yearLevelRecords, "a record dated only to 2019 is mentioned, not counted as covering every month")
        assertEquals("January 2018 – February 2018", archive.gaps(minMonths = 1).first().label())
        assertTrue(ask("Where are the gaps in my history?").contains("not proof that nothing happened"))
        assertEquals(listOf("Fest", "Exams", "Summer"), archive.timeline().map { it.title })
        assertEquals(listOf("Exams"), archive.timeline(ArchiveService.TimelineFilter(from = LocalDate.of(2018, 4, 1), to = LocalDate.of(2018, 12, 31))).map { it.title })
        archive.people.save(ArchivePerson(name = "Riya", importantDates = listOf(ImportantDate("Birthday", d("31 December 2000"), remind = true))))
        assertEquals("Birthday", archive.dueReminders().single().second.label)
    }

    @Test fun missingContextIsSuggestedNotRequired() {
        val r = archive.saveMemory(Memory(category = MemoryCategory.EXPERIENCE, title = "Met someone", content = "I met a girl at the station.", source = MemorySource.OWNER_SUPPLIED,
            people = listOf("Meera")))
        assertTrue(r.hints.any { "No date yet" in it }); assertTrue(r.hints.any { "Meera isn't in your People catalog" in it })
        assertNotNull(core.memories.get(r.memory.id), "saved anyway")
    }
}
