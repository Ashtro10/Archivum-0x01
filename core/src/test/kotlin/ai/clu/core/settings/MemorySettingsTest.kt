package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.agent.MemoryRetriever
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.*
import ai.clu.core.memory.*
import ai.clu.core.security.Actor
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.jupiter.api.Test

class MemorySettingsTest {
    private val core = newCore()
    private val s = core.settings
    private val retriever = MemoryRetriever(s, core.memories)

    private fun seed() {
        core.memories.save(Memory(category = MemoryCategory.MANUAL, title = "Trip to Goa with Ashutosh",
            content = "In December 2023 I went to Goa with Ashutosh. We rented scooters and he crashed into a fruit stall.", source = MemorySource.OWNER_SUPPLIED,
            people = listOf("Ashutosh")))
        core.memories.save(Memory(category = MemoryCategory.CORE_FACT, title = "Favourite food", content = "My favourite food is paneer tikka.", source = MemorySource.OWNER_INSTRUCTION))
        core.memories.save(Memory(category = MemoryCategory.EXPERIENCE, title = "Job interview", content = "Interview at the bank went badly; nervous about presentations.", source = MemorySource.CONVERSATION))
    }

    @Test fun enabled() {
        s.set(Settings.MEMORY_ENABLED, false)
        assertFailsWith<MemoryDisabledException> { core.memories.save(Memory(category = MemoryCategory.CORE_FACT, title = "x", content = "x", source = MemorySource.OWNER_SUPPLIED)) }
        assertNull(core.proposals.propose(ProposalKind.OBSERVATION, "Owner likes tea", "test"))
        // Background paths must not write either: diary and "remember" tool.
        runBlocking {
            val conv = core.conversations.create()
            core.useEngine(ScriptedEngine().reply("ok", "ok", "ok"))
            s.set(Settings.DIARY_MODE, "automatic")
            repeat(3) { core.agent.handle(TurnRequest(conv.id, "tell me something $it", Actor.ownerInApp())) {} }
            core.agent.writeDiaryIfDue(conv.id)
            core.agent.handle(TurnRequest(conv.id, "remember that my car is blue", Actor.ownerInApp())) {}
        }
        assertEquals(0, core.memories.count(), "no persistent memory may be written while disabled")
        s.set(Settings.MEMORY_ENABLED, true)
        core.memories.save(Memory(category = MemoryCategory.CORE_FACT, title = "x", content = "x", source = MemorySource.OWNER_SUPPLIED))
        assertEquals(1, core.memories.count())
    }

    @Test fun retrieval() {
        seed()
        assertTrue(retriever.retrieve("What happened in Goa with Ashutosh?", listOf(CLU_SCOPE)).isNotEmpty())
        s.set(Settings.MEMORY_RETRIEVAL, false)
        assertTrue(retriever.retrieve("What happened in Goa with Ashutosh?", listOf(CLU_SCOPE)).isEmpty())
    }

    @Test fun maxInjected() {
        repeat(8) { i -> core.memories.save(Memory(category = MemoryCategory.CORE_FACT, title = "Cricket fact $i", content = "Cricket match number $i with friends", source = MemorySource.OWNER_SUPPLIED)) }
        s.set(Settings.MEMORY_MIN_SCORE, 0.0)
        s.set(Settings.MEMORY_MAX, 2); assertEquals(2, retriever.retrieve("cricket match", listOf(CLU_SCOPE)).size)
        s.set(Settings.MEMORY_MAX, 6); assertEquals(6, retriever.retrieve("cricket match", listOf(CLU_SCOPE)).size)
        s.set(Settings.MEMORY_MAX, 0); assertTrue(retriever.retrieve("cricket match", listOf(CLU_SCOPE)).isEmpty())
    }

    @Test fun minScore() {
        seed()
        // An unrelated question retrieves nothing; the relevant one passes the threshold.
        assertTrue(retriever.retrieve("How do black holes evaporate?", listOf(CLU_SCOPE)).isEmpty())
        val goa = retriever.retrieve("scooter crash in Goa", listOf(CLU_SCOPE))
        assertEquals("Trip to Goa with Ashutosh", goa.first().memory.title)
        s.set(Settings.MEMORY_MIN_SCORE, 10.0)
        assertTrue(retriever.retrieve("scooter crash in Goa", listOf(CLU_SCOPE)).isEmpty())
    }

    @Test fun temporaryRetention() {
        s.set(Settings.TEMP_RETENTION, 2)
        val m = core.memories.save(Memory(category = MemoryCategory.TEMPORARY, title = "Parking", content = "Car parked on level 3 spot B12", source = MemorySource.OWNER_INSTRUCTION))
        assertEquals(core.clock.now() + 2 * 86_400_000L, m.expiresAt)
        assertTrue(core.memories.rank("where is my car parked", listOf(CLU_SCOPE)).isNotEmpty())
        (core.clock as ai.clu.core.util.ManualClock).advance(3 * 86_400_000L)
        assertTrue(core.memories.rank("where is my car parked", listOf(CLU_SCOPE)).isEmpty(), "expired memories are not retrieved")
        assertEquals(1, core.memories.purgeExpired())
        assertTrue(core.memories.get(m.id)!!.deleted)
    }

    @Test fun proposeInsights() {
        assertNotNull(core.proposals.propose(ProposalKind.OBSERVATION, "Owner prefers mornings for hard work", "pattern"))
        s.set(Settings.PROPOSE_INSIGHTS, false)
        assertNull(core.proposals.propose(ProposalKind.HYPOTHESIS, "Owner dislikes crowds", "pattern"))
    }

    @Test fun rememberCommand(): Unit = runBlocking {
        val conv = core.conversations.create()
        core.agent.handle(TurnRequest(conv.id, "Remember that my passport expires in May 2027", Actor.ownerInApp())) {}
        val saved = core.memories.list().single()
        assertEquals(MemorySource.OWNER_INSTRUCTION, saved.source)
        assertTrue(saved.content.contains("passport expires in May 2027"))
        s.set(Settings.REMEMBER_COMMAND, false)
        core.agent.handle(TurnRequest(conv.id, "Remember that my locker code is at home", Actor.ownerInApp())) {}
        assertEquals(1, core.memories.count(), "with the setting off, the command creates a proposal instead")
        assertTrue(core.proposals.pending().any { it.text.contains("locker code") })
    }

    @Test fun diaryMode(): Unit = runBlocking {
        core.useEngine(ScriptedEngine().reply("a", "b", "c", "The owner discussed exam preparation and felt confident."))
        val conv = core.conversations.create()
        for (q in listOf("I have an exam tomorrow", "It's on physics", "I think I'm ready")) core.agent.handle(TurnRequest(conv.id, q, Actor.ownerInApp())) {}
        core.agent.writeDiaryIfDue(conv.id)
        assertTrue(core.proposals.pending().any { it.kind == ProposalKind.DIARY })
        assertEquals(0, core.memories.list(category = MemoryCategory.DIARY).size)
        s.set(Settings.DIARY_MODE, "automatic")
        core.useEngine(ScriptedEngine().reply("The owner prepared for a physics exam."))
        core.agent.writeDiaryIfDue(conv.id)
        assertEquals("The owner prepared for a physics exam.", core.memories.list(category = MemoryCategory.DIARY).single().content)
        s.set(Settings.DIARY_MODE, "off")
        core.agent.writeDiaryIfDue(conv.id)
        assertEquals(1, core.memories.list(category = MemoryCategory.DIARY).size)
    }
}
