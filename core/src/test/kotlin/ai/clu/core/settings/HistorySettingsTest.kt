package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.history.*
import ai.clu.core.memory.*
import ai.clu.core.security.Actor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.*

/** Each personal-archive setting changes what Clu does (none is decorative). Device clock: 9 October 2025. */
class HistorySettingsTest {
    private val core = newCore()
    private val s = core.settings
    private val engine = ScriptedEngine().also { core.useEngine(it) }
    private val conv = core.conversations.create().id
    private fun ask(t: String) = runBlocking { core.agent.respond(TurnRequest(conv, t, Actor.ownerInApp())).toList() }.filterIsInstance<AgentEvent.Done>().single().text
    private fun mem(title: String, date: String, content: String, people: List<String> = emptyList(), told: String? = null, kind: String = "", project: String? = null) =
        core.archive.saveMemory(Memory(category = MemoryCategory.EXPERIENCE, title = title, content = content, source = MemorySource.OWNER_SUPPLIED, people = people,
            eventDate = DateParser.parse(date)!!, told = told?.let { DateParser.parse(it) }, threadKind = kind), projectIds = listOfNotNull(project)).memory

    @Test fun timelineStart() {
        engine.reply("Hi.")
        ask("Hello there, how is it going")
        assertTrue("Your history with the owner began in January 2018" in engine.lastSystem)
        s.set(Settings.TIMELINE_START, "2016-05-01")
        ask("Hello again, how is it going")
        assertTrue("Your history with the owner began in May 2016" in engine.lastSystem)
        assertEquals(LocalDate.of(2016, 5, 1), core.time.timelineStart())
        // Gaps start at the timeline start; a "told" date for an older event is never before it.
        s.set(Settings.TIMELINE_START, "2018-01-01")
        val m = mem("Childhood", "2005", "A childhood story.")
        assertEquals("1 January 2018", core.archive.dates.told(m)!!.label())
        assertEquals(java.time.YearMonth.of(2018, 1), core.archive.gaps().first().from)
        assertNotEquals(ai.clu.core.config.SettingResult.Ok, s.set(Settings.TIMELINE_START, "not a date"), "invalid dates are refused")
        assertEquals("2018-01-01", s.string(Settings.TIMELINE_START))
    }

    @Test fun simulatedTime() {
        assertEquals(LocalDate.of(2025, 10, 9), core.time.today())
        s.set(Settings.SIMULATED_DATE, "2026-10-29")
        assertEquals(LocalDate.of(2025, 10, 9), core.time.today(), "the date alone does nothing while the mode is off")
        s.set(Settings.SIMULATED_TIME, "fixed")
        assertEquals(LocalDate.of(2026, 10, 29), core.time.today())
        assertTrue(core.time.promptLine().contains("29 October 2026"))
        s.set(Settings.SIMULATED_TIME, "off")
        assertTrue(core.time.promptLine().startsWith("Current date and time: Thursday 9 October 2025"))
    }

    @Test fun perspective() {
        mem("Early", "2018", "Something early.")
        mem("Late", "2024", "Something late.")
        assertEquals(2, core.archive.dates.visible(core.memories.list()).size)
        s.set(Settings.HISTORY_PERSPECTIVE, "2019-06-01")
        assertEquals(listOf("Early"), core.archive.dates.visible(core.memories.list()).map { it.title })
        assertEquals(LocalDate.of(2019, 6, 1), core.time.today())
        assertTrue(core.time.promptLine().startsWith("Historical perspective: answer as of Saturday 1 June 2019"))
    }

    @Test fun toldDefault() {
        val m = mem("Met Riya", "March 2018", "Met Riya.")
        assertEquals("March 2018", core.archive.dates.told(m)!!.label(), "default: told when it happened")
        s.set(Settings.TOLD_DEFAULT, "unknown")
        assertNull(core.archive.dates.told(m))
        assertFalse(core.archive.dates.availableBy(m, LocalDate.of(2030, 1, 1)), "not assumed known at any earlier date")
    }

    @Test fun recallInChat() {
        core.archive.people.save(ArchivePerson(name = "Riya"))
        mem("Met Riya", "March 2018", "I met Riya at the fest.", people = listOf("Riya"))
        assertTrue(ask("Do you remember Riya?").startsWith("Yes, I remember Riya."))
        assertTrue(engine.prompts.isEmpty())
        s.set(Settings.RECALL_IN_CHAT, false)
        engine.reply("I think so.")
        assertEquals("I think so.", ask("Do you remember Riya?"), "off: the model answers, with memories in its context")
        assertTrue("met Riya at the fest" in engine.lastSystem)
    }

    @Test fun resurface() {
        s.set(Settings.MEMORY_MAX, 1) // only the best match is retrieved directly; the decision can only come along as resurfaced context
        core.memories.save(Memory(category = MemoryCategory.PROJECT, title = "Primus", content = "Primus status: training phase, Primus milestone two.", source = MemorySource.OWNER_SUPPLIED))
        core.memories.save(Memory(category = MemoryCategory.DECISION, title = "Storage choice for Primus", content = "We keep all memory in SQLite.", source = MemorySource.OWNER_SUPPLIED))
        listOf("Tea" to "Likes masala chai.", "Bike" to "Rides a blue bike.", "Exam" to "Exam in March.").forEach { (t, c) ->
            core.memories.save(Memory(category = MemoryCategory.CORE_FACT, title = t, content = c, source = MemorySource.OWNER_SUPPLIED)) }
        engine.reply("OK."); engine.reply("OK.")
        ask("Let's continue with Primus status")
        assertTrue("SQLite" in engine.lastSystem, "the related earlier decision is resurfaced: ${engine.lastSystem.substringAfter("[Context]")}")
        s.set(Settings.RESURFACE, false)
        ask("Let's continue with Primus status")
        assertFalse("SQLite" in engine.lastSystem, "off: only what matched directly")
    }

    @Test fun suggestMissing() {
        val m = Memory(category = MemoryCategory.EXPERIENCE, title = "Something", content = "x", source = MemorySource.OWNER_SUPPLIED)
        assertTrue(core.archive.saveMemory(m).hints.isNotEmpty())
        s.set(Settings.SUGGEST_MISSING, false)
        assertTrue(core.archive.saveMemory(m.copy(id = ai.clu.core.util.newId())).hints.isEmpty())
    }
}
