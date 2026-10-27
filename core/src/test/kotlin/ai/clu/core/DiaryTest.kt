package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.db.Schema
import ai.clu.core.diary.DiaryEntry
import ai.clu.core.diary.DiarySource
import ai.clu.core.memory.MemoryCategory
import ai.clu.core.memory.ProposalKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

/** The diary: offline CRUD, browsing by date, search, promotion to memory only on request, persistence and migration. */
class DiaryTest {
    private val core = newCore()
    private val diary get() = core.diary

    @Test fun createEditDeleteBrowseAndSearchOffline() {
        core.platform.let { (it as TestPlatform).online = false } // nothing here needs a network
        val a = diary.add("Walked to the Lonavala viewpoint with Ashutosh. Rain all day.", date = "2025-09-14")
        val b = diary.add("Finished chapter 3 of the thesis.", title = "Thesis progress", date = "2025-10-02")
        diary.add("Dentist appointment went fine.", date = "2025-10-02")
        assertEquals("Walked to the Lonavala viewpoint with Ashutosh. Rain", a.title, "title from the first words")
        assertEquals(listOf("2025-10" to 2, "2025-09" to 1), diary.months())
        assertEquals(2, diary.list(day = "2025-10-02").size)
        assertEquals(1, diary.list(month = "2025-09").size)
        assertEquals(listOf(a.id), diary.search("lonavala rain").map { it.id }, "all words must match, any case")
        assertTrue(diary.search("lonavala snow").isEmpty())
        assertEquals(listOf(b.id), diary.search("THESIS").map { it.id })
        assertTrue(diary.search("100%").isEmpty(), "LIKE wildcards are escaped")
        val edited = diary.save(b.copy(body = "Finished chapter 3 and 4 of the thesis."))
        assertEquals(b.createdAt, edited.createdAt)
        assertTrue(diary.get(b.id)!!.body.contains("chapter 3 and 4"))
        diary.delete(a.id)
        assertNull(diary.get(a.id)); assertTrue(diary.search("lonavala").isEmpty())
        assertFailsWith<IllegalArgumentException> { diary.save(DiaryEntry(date = "2025-10-02", title = " ", body = "")) }
        assertFailsWith<IllegalArgumentException> { diary.save(DiaryEntry(date = "02/10/2025", title = "x", body = "y")) }
    }

    @Test fun entriesSurviveRestart() {
        val e = diary.add("Started learning the sitar.", date = "2025-10-10")
        val again = newCore(db = JdbcDriver((core.db as JdbcDriver).path))
        assertEquals("Started learning the sitar.", again.diary.get(e.id)!!.body)
    }

    @Test fun diaryIsNotMemoryUntilPromoted(): Unit = runBlocking {
        val engine = ScriptedEngine(); core.useEngine(engine)
        val e = diary.add("Decided to apply for the Pune job next month.", date = "2025-10-12")
        assertEquals(0, core.memories.count(), "a diary entry is not a memory")
        val conv = core.conversations.create().id
        val owner = core.auth.actorForSpeaker(null, false)!!
        engine.reply("Noted.")
        core.agent.respond(TurnRequest(conv, "What should I do about the Pune job?", owner)).toList()
        assertFalse(engine.lastSystem.contains("apply for the Pune job"), "diary text never reaches the prompt by itself")
        val m = diary.promote(e.id, MemoryCategory.DECISION)
        assertEquals(MemoryCategory.DECISION, m.category); assertEquals("from-diary", m.tags.single())
        assertEquals(m.id, diary.promote(e.id).id, "promoting twice keeps one memory")
        assertEquals(1, core.memories.count())
        engine.reply("Noted.")
        core.agent.respond(TurnRequest(conv, "What should I do about the Pune job?", owner)).toList()
        assertTrue(engine.lastSystem.contains("apply for the Pune job"), "once promoted, it is a memory and can be retrieved")
    }

    @Test fun savingFromConversationOnRequest(): Unit = runBlocking {
        val engine = ScriptedEngine(); core.useEngine(engine)
        val conv = core.conversations.create().id
        val owner = core.auth.actorForSpeaker(null, false)!!
        suspend fun say(t: String) = core.agent.respond(TurnRequest(conv, t, owner)).toList().filterIsInstance<AgentEvent.Done>().single()
        assertEquals("Added to your diary.", say("Add to my diary: met Priya for coffee, talked about the startup").text)
        assertEquals("met Priya for coffee, talked about the startup", diary.list().single().body)
        engine.reply("Compound interest grows on the interest already earned.")
        say("What is compound interest?")
        assertEquals("Added to your diary.", say("Save this conversation to my diary").text)
        val saved = diary.list().first()
        assertEquals(DiarySource.CONVERSATION, saved.source)
        assertEquals("Me: What is compound interest?\nClu: Compound interest grows on the interest already earned.", saved.body)
        assertEquals("Added to your diary.", say("diary: long day, but productive").text)
        assertEquals(3, diary.count())
    }

    @Test fun conversationSummariesGoToTheDiary(): Unit = runBlocking {
        val engine = ScriptedEngine(); core.useEngine(engine)
        val conv = core.conversations.create().id
        repeat(3) { core.conversations.addMessage(conv, ai.clu.core.conversation.Role.USER, "message $it") }
        core.settings.set(Settings.DIARY_MODE, "automatic")
        engine.reply("The owner planned the week.")
        core.agent.writeDiaryIfDue(conv)
        assertEquals("The owner planned the week.", diary.list().single().body)
        assertEquals(0, core.memories.count(), "automatic diary entries are not memories")
        core.settings.set(Settings.DIARY_MODE, "propose")
        engine.reply("The owner reviewed the budget.")
        core.agent.writeDiaryIfDue(conv)
        val p = core.proposals.pending().single { it.kind == ProposalKind.DIARY }
        assertEquals(1, diary.count(), "a proposal is not written until approved")
        assertNull(core.proposals.approve(p.id), "an approved diary proposal becomes a diary entry, not a memory")
        assertEquals(2, diary.count()); assertEquals(0, core.memories.count())
    }

    @Test fun legacyDiaryMemoriesMigrate() {
        val db = tempDb()
        Schema.migrations[0].forEach { db.execute(it) }; db.userVersion = 1
        db.execute("""INSERT INTO memories(id, category, title, content, summary, retain_mode, event_at, event_has_time, source, people, tags, pinned, scope,
            expires_at, created_at, updated_at, deleted, hlc, origin) VALUES('m1','DIARY','Conversation 3 Oct','Talked about exams.',NULL,'FULL',
            1759500000000,0,'CONVERSATION','[]','[]',0,'clu',NULL,1759500000000,1759500000000,0,'h','o')""")
        db.execute("INSERT INTO memory_terms(memory_id, term, tf) VALUES('m1','exams',1)")
        db.execute("""INSERT INTO memories(id, category, title, content, summary, retain_mode, event_at, event_has_time, source, people, tags, pinned, scope,
            expires_at, created_at, updated_at, deleted, hlc, origin) VALUES('m2','CORE_FACT','Exam','Exam on Friday.',NULL,'FULL',NULL,0,'OWNER_SUPPLIED',
            '[]','[]',0,'clu',NULL,1759500000000,1759500000000,0,'h','o')""")
        val c = newCore(db = db)
        assertEquals(Schema.currentVersion, db.userVersion)
        val e = c.diary.get("m1")!!
        assertEquals("Talked about exams.", e.body); assertEquals(DiarySource.CONVERSATION, e.source)
        assertEquals(ai.clu.core.diary.DiaryRepository.dayOf(1759500000000), e.date)
        assertTrue(c.memories.get("m1")!!.deleted, "no longer a memory")
        assertTrue(c.memories.rank("exams").none { it.memory.id == "m1" }, "and no longer retrievable")
        assertFalse(c.memories.get("m2")!!.deleted, "other memories untouched")
    }
}
