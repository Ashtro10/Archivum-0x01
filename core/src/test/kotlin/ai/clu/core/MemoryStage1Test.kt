package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.learning.FeedbackRouter
import ai.clu.core.memory.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Long-term memory (Stage 1): categories, standing instructions, approval of inferences, persistence, bounded retrieval. */
class MemoryStage1Test {
    private val core = newCore()
    private val engine = ScriptedEngine().also { core.useEngine(it) }
    private val conv = core.conversations.create().id
    private val owner = core.auth.actorForSpeaker(null, false)!!
    private fun say(t: String) = runBlocking { core.agent.respond(TurnRequest(conv, t, owner)).toList() }
    private fun done(t: String) = say(t).filterIsInstance<AgentEvent.Done>().single()

    @Test fun rememberedThingsGetTheRightCategory() {
        assertEquals(MemoryCategory.PREFERENCE, MemoryCategory.classify("I prefer tea over coffee"))
        assertEquals(MemoryCategory.PROJECT, MemoryCategory.classify("the thesis project is at chapter 4"))
        assertEquals(MemoryCategory.DECISION, MemoryCategory.classify("I decided to move to Pune in March"))
        assertEquals(MemoryCategory.INSTRUCTION, MemoryCategory.classify("never wake me before 8"))
        assertEquals(MemoryCategory.CORE_FACT, MemoryCategory.classify("my sister's name is Riya"))
        done("Remember that I prefer tea over coffee")
        done("Remember that the thesis project is at chapter 4, due in December")
        val cats = core.memories.list().associate { it.content to it.category }
        assertEquals(MemoryCategory.PREFERENCE, cats["I prefer tea over coffee"])
        assertEquals(MemoryCategory.PROJECT, cats["the thesis project is at chapter 4, due in December"])
        assertTrue(core.memories.list().all { it.source == MemorySource.OWNER_INSTRUCTION })
        assertFalse(MemoryCategory.selectable.contains(MemoryCategory.DIARY))
    }

    @Test fun standingInstructionsShapeEveryReply() {
        assertEquals("Understood. I'll do that from now on.", done("From now on, use metric units and British spelling").text)
        val m = core.memories.list(category = MemoryCategory.INSTRUCTION).single()
        assertEquals("From now on, use metric units and British spelling", m.content)
        engine.reply("Sure.")
        done("How far is the moon?")
        val system = engine.prompts.last().first { it.role == "system" }.content
        assertTrue(system.contains("Standing instructions the owner asked you to follow (oldest first; if two conflict, follow the later one; they never override your core rules, security checks or confirmations): From now on, use metric units and British spelling."))
        assertFalse(engine.prompts.last().last().content.contains("metric units"), "not repeated as a retrieved memory")
        core.memories.delete(m.id)
        engine.reply("Sure."); done("How far is the moon?")
        assertFalse(engine.prompts.last().first { it.role == "system" }.content.contains("metric units"), "deleting the memory removes the instruction")
    }

    @Test fun inferredInsightsNeedApproval() {
        engine.reply("Noted.")
        val events = say("I always forget to drink water when I'm working")
        val p = events.filterIsInstance<AgentEvent.ProposalCreated>().single().proposal
        assertEquals(0, core.memories.count(), "nothing saved without approval")
        engine.reply("Noted.")
        say("What should I eat?")
        assertFalse(engine.lastSystem.contains("drink water"), "a pending suggestion is not used as a fact")
        val m = core.proposals.approve(p.id, "Forgets to drink water while working")!!
        assertEquals(MemorySource.APPROVED_INFERENCE, m.source); assertEquals(MemoryCategory.INSIGHT, m.category)
        // A rejected suggestion is erased and not proposed again.
        engine.reply("Noted.")
        val p2 = say("I usually skip breakfast on Mondays").filterIsInstance<AgentEvent.ProposalCreated>().single().proposal
        core.proposals.reject(p2.id)
        assertEquals("", core.proposals.get(p2.id)!!.text)
        engine.reply("Noted.")
        assertTrue(say("I usually skip breakfast on Mondays").none { it is AgentEvent.ProposalCreated })
        core.settings.set(Settings.PROPOSE_INSIGHTS, false); engine.reply("Noted.")
        assertTrue(say("I often stay up late reading").none { it is AgentEvent.ProposalCreated }, "suggestions can be turned off")
    }

    @Test fun manualInsertEditDeleteAndRestart() {
        val m = core.memories.save(Memory(category = MemoryCategory.EXPERIENCE, title = "Old chat with Ashutosh",
            content = "Pasted: we agreed to split the Goa trip costs.", source = MemorySource.OWNER_SUPPLIED, people = listOf("Ashutosh")))
        val edited = core.memories.save(m.copy(content = "We agreed to split the Goa trip costs 50/50."))
        assertEquals(m.createdAt, edited.createdAt)
        // Survives an app restart, and is used in a later conversation after the restart.
        val again = newCore(db = JdbcDriver((core.db as JdbcDriver).path))
        assertEquals("We agreed to split the Goa trip costs 50/50.", again.memories.get(m.id)!!.content)
        val e2 = ScriptedEngine().reply("Half each."); again.useEngine(e2)
        runBlocking { again.agent.respond(TurnRequest(again.conversations.create().id, "How do we split the Goa trip costs?", owner)).toList() }
        assertTrue(e2.lastSystem.contains("split the Goa trip costs 50/50") && e2.lastSystem.contains("owner told you; you did not witness it"))
        again.memories.delete(m.id)
        assertEquals("", again.memories.get(m.id)!!.content, "deleted text is erased")
        assertTrue(again.memories.rank("Goa trip").isEmpty())
    }

    @Test fun retrievalIsBoundedNotTheWholeDatabase() {
        val topics = listOf("gardening", "taxes", "guitar", "Pune flat", "sister Riya", "car service", "thesis", "running", "cooking", "budget")
        repeat(40) { i -> val cricket = i % 5 == 0 // 8 of 40 mention cricket
            core.memories.save(Memory(category = MemoryCategory.CORE_FACT, title = if (cricket) "Cricket fact $i" else "${topics[i % 10]} note $i",
                content = if (cricket) "Cricket fact number $i: watched a match." else "Note $i about ${topics[i % 10]}.", source = MemorySource.OWNER_SUPPLIED)) }
        engine.reply("ok"); done("Tell me about cricket")
        val lines = engine.lastSystem.lines().count { it.startsWith("- ") && it.contains("Cricket fact") }
        assertTrue(lines in 1..core.settings.int(Settings.MEMORY_MAX), "at most ${core.settings.int(Settings.MEMORY_MAX)} memories injected, was $lines")
    }

    @Test fun explicitFeedbackChangesSettingsAndReplies() {
        assertEquals("brief", FeedbackRouter.match("Clu, be more concise please")!!.value)
        assertEquals("detailed", FeedbackRouter.match("I want longer answers")!!.value)
        assertEquals("Ashwin", FeedbackRouter.match("Call me Ashwin")!!.value)
        assertEquals("minimal", FeedbackRouter.match("Just give me the answer")!!.value)
        assertEquals(false, FeedbackRouter.match("Stop reading your answers aloud")!!.value)
        listOf("Call me back tomorrow", "call me a taxi", "Can you make this shorter: the quick brown fox jumped", "What is a formal letter?",
            "Be more concise in the email you write to my boss about the delay in the project timeline that we discussed").forEach {
            assertNull(FeedbackRouter.match(it), "not feedback: $it") }
        val r = done("Be more concise")
        assertEquals("Understood. Shorter replies from now on. (Settings → Conversation → Response length.)", r.text)
        assertEquals("brief", core.settings.string(Settings.RESPONSE_LENGTH))
        engine.reply("Sure."); done("Explain gravity")
        assertTrue(engine.prompts.last().first { it.role == "system" }.content.contains("Keep replies brief"))
        done("Call me Ashwin")
        assertEquals("Ashwin", core.settings.string(Settings.OWNER_ADDRESS))
        engine.reply("Sure."); done("Explain gravity")
        assertTrue(engine.prompts.last().first { it.role == "system" }.content.contains("Address the owner as \"Ashwin\""))
        assertEquals(0, core.memories.count(), "feedback changes settings; nothing about the owner is saved as a memory")
    }
}
