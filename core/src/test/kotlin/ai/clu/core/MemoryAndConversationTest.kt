package ai.clu.core

import ai.clu.core.agent.MemoryRetriever
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.conversation.Role
import ai.clu.core.memory.*
import ai.clu.core.security.Actor
import ai.clu.core.tools.PromptMemoryFormat
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.jupiter.api.Test

class MemoryTest {
    private val db = tempDb()
    private val core = newCore(db)
    private val pasted = """
        Me: Bro are we still on for Lonavala on Saturday?
        Ashutosh: Yes! Leaving at 6am, I'll drive. Bring the speaker.
        Me: Done. Also remind me to pay you back the 2000 for tickets.
    """.trimIndent()

    private fun insertManual() = core.memories.save(Memory(
        category = MemoryCategory.MANUAL, title = "Lonavala trip plan", content = pasted,
        summary = "Planned a Lonavala trip with Ashutosh; owes him 2000 for tickets.", retainMode = RetainMode.BOTH,
        eventAt = java.time.LocalDate.of(2024, 7, 13).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
        source = MemorySource.OWNER_SUPPLIED, people = listOf("Ashutosh"), tags = listOf("travel", "money")))

    @Test fun manualMemoryPersistsAfterRestartWithOriginalText() {
        val m = insertManual()
        val reopened = newCore(JdbcDriver(db.path))
        val back = reopened.memories.get(m.id)!!
        assertEquals(pasted, back.content, "original pasted text is kept verbatim")
        assertEquals("Planned a Lonavala trip with Ashutosh; owes him 2000 for tickets.", back.summary)
        assertEquals(MemorySource.OWNER_SUPPLIED, back.source)
        assertEquals(listOf("Ashutosh"), back.people)
        assertNotNull(back.eventAt)
    }

    @Test fun relevantQuestionRetrievesInsertedMemoryAndIrrelevantDoesNot() {
        insertManual()
        core.memories.save(Memory(category = MemoryCategory.CORE_FACT, title = "Allergy", content = "I am allergic to peanuts.", source = MemorySource.OWNER_INSTRUCTION))
        val r = MemoryRetriever(core.settings, core.memories)
        assertEquals("Lonavala trip plan", r.retrieve("How much money do I owe Ashutosh?", listOf(CLU_SCOPE)).first().memory.title)
        assertEquals("Allergy", r.retrieve("Can I eat peanut butter?", listOf(CLU_SCOPE)).single().memory.title)
        assertTrue(r.retrieve("Explain quantum entanglement", listOf(CLU_SCOPE)).isEmpty())
    }

    @Test fun promptMarksOwnerSuppliedMemoriesAsNotWitnessed() {
        val line = PromptMemoryFormat.line(insertManual())
        assertTrue(line.contains("owner told you; you did not witness it"))
        assertTrue(line.contains("[13 Jul 2024]"))
    }

    @Test fun agentInjectsRetrievedMemoryIntoPrompt(): Unit = runBlocking {
        insertManual()
        val engine = ScriptedEngine().reply("You owe him 2000.")
        core.useEngine(engine)
        val conv = core.conversations.create()
        core.agent.handle(TurnRequest(conv.id, "How much do I owe Ashutosh?", Actor.ownerInApp())) {}
        assertTrue(engine.lastSystem.contains("owes him 2000"))
        core.agent.handle(TurnRequest(conv.id, "What is the capital of France?", Actor.ownerInApp())) {}
        assertFalse(engine.lastSystem.contains("Lonavala"), "irrelevant memories are not injected")
    }

    @Test fun editingAndDeletingChangesRetrieval() {
        val m = insertManual()
        core.memories.save(m.copy(content = "Trip to Lonavala with Ashutosh was cancelled.", summary = null, retainMode = RetainMode.FULL))
        val r = MemoryRetriever(core.settings, core.memories)
        assertTrue(r.retrieve("cancelled trip", listOf(CLU_SCOPE)).isNotEmpty())
        assertTrue(r.retrieve("speaker tickets 2000", listOf(CLU_SCOPE)).isEmpty(), "old text no longer indexed")
        core.memories.delete(m.id)
        assertTrue(r.retrieve("Lonavala Ashutosh", listOf(CLU_SCOPE)).isEmpty())
        assertTrue(core.memories.search("Lonavala").isEmpty())
    }

    @Test fun summaryOnlyIsExplicitChoice() {
        val m = core.memories.save(Memory(category = MemoryCategory.MANUAL, title = "Old chat", content = "long text", summary = "short", retainMode = RetainMode.SUMMARY,
            source = MemorySource.IMPORTED))
        assertEquals("", m.content); assertEquals("short", m.bodyForPrompt())
        assertFailsWith<IllegalArgumentException> { core.memories.save(Memory(category = MemoryCategory.MANUAL, title = "x", content = "y", retainMode = RetainMode.SUMMARY, source = MemorySource.IMPORTED)) }
    }

    @Test fun pinnedAndSearch() {
        val m = insertManual()
        core.memories.setPinned(m.id, true)
        assertTrue(core.memories.list().first().pinned)
        assertEquals(m.id, core.memories.search("Lonavala").first().id)
    }

    @Test fun approvedInferenceIsSavedRejectedIsNot() {
        val p1 = core.proposals.propose(ProposalKind.OBSERVATION, "Owner tends to work late on Sundays", "Observed in several conversations")!!
        val p2 = core.proposals.propose(ProposalKind.HYPOTHESIS, "Owner may be avoiding the gym", "Speculative")!!
        assertEquals(0, core.memories.count(), "nothing saved before approval")
        val m = core.proposals.approve(p1.id)
        assertEquals(MemorySource.APPROVED_INFERENCE, m.source); assertEquals(MemoryCategory.INSIGHT, m.category)
        core.proposals.reject(p2.id)
        assertEquals(1, core.memories.count())
        assertTrue(core.memories.search("gym").isEmpty())
        assertEquals("", core.proposals.get(p2.id)!!.text, "rejected text is erased")
        assertNull(core.proposals.propose(ProposalKind.HYPOTHESIS, "Owner may be avoiding the gym", "again"), "not re-proposed")
    }

    @Test fun agentProposesButNeverSavesInsights(): Unit = runBlocking {
        core.useEngine(ScriptedEngine().reply("Noted."))
        val conv = core.conversations.create()
        val events = mutableListOf<ai.clu.core.agent.AgentEvent>()
        core.agent.handle(TurnRequest(conv.id, "I always skip breakfast when I'm busy.", Actor.ownerInApp())) { events += it }
        assertTrue(events.any { it is ai.clu.core.agent.AgentEvent.ProposalCreated })
        assertEquals(0, core.memories.count())
    }

    @Test fun characterMemoriesAreSeparate() {
        val rick = core.characters.save(ai.clu.core.persona.CharacterProfile(name = "Rick", memoryScope = ai.clu.core.persona.CharacterMemoryScope.OWN))
        insertManual()
        core.memories.save(Memory(category = MemoryCategory.EXPERIENCE, title = "Rick portal", content = "Rick talked about portal guns", source = MemorySource.CONVERSATION, scope = rick.id))
        core.personas.switchTo(rick.id)
        val p = core.personas.active()
        val r = MemoryRetriever(core.settings, core.memories)
        assertTrue(r.retrieve("Lonavala Ashutosh money", p.readableScopes).isEmpty(), "character cannot read Clu's private memory")
        assertTrue(r.retrieve("portal guns", p.readableScopes).isNotEmpty())
        // Deleting the character removes only its own memories.
        core.characters.delete(rick.id)
        assertEquals(1, core.memories.count())
        assertEquals("Lonavala trip plan", core.memories.list().single().title)
        assertTrue(core.personas.active().isClu, "deleted character falls back to Clu")
    }
}

class ConversationTest {
    private val db = tempDb()
    private val core = newCore(db)

    @Test fun messagesPersistAndReopen() {
        val c = core.conversations.create()
        core.conversations.addMessage(c.id, Role.USER, "Hello")
        core.conversations.addMessage(c.id, Role.ASSISTANT, "Hello, sir.")
        val again = newCore(JdbcDriver(db.path))
        assertEquals(listOf("Hello", "Hello, sir."), again.conversations.messages(c.id).map { it.content })
    }

    @Test fun searchRenameArchiveDelete() {
        val a = core.conversations.create(); core.conversations.addMessage(a.id, Role.USER, "Plan the Kerala trip")
        val b = core.conversations.create(); core.conversations.addMessage(b.id, Role.USER, "Fix my resume")
        assertEquals(listOf(a.id), core.conversations.search("kerala").map { it.id })
        core.conversations.rename(b.id, "Resume work")
        assertEquals(listOf(b.id), core.conversations.search("Resume work").map { it.id })
        core.conversations.setArchived(a.id, true)
        assertEquals(listOf(b.id), core.conversations.list().map { it.id })
        assertEquals(2, core.conversations.list(includeArchived = true).size)
        core.conversations.delete(b.id)
        assertTrue(core.conversations.search("resume").isEmpty())
        assertTrue(core.conversations.messages(b.id).isEmpty())
    }

    @Test fun repliesBelongToTheirConversationAndAutoTitle(): Unit = runBlocking {
        core.useEngine(ScriptedEngine().reply("Reply one", "Reply two"))
        val a = core.conversations.create(); val b = core.conversations.create()
        core.agent.handle(TurnRequest(a.id, "First question about budgets", Actor.ownerInApp())) {}
        core.agent.handle(TurnRequest(b.id, "Second question", Actor.ownerInApp())) {}
        assertEquals(listOf("First question about budgets", "Reply one"), core.conversations.messages(a.id).map { it.content })
        assertEquals(listOf("Second question", "Reply two"), core.conversations.messages(b.id).map { it.content })
        assertEquals("First question about budgets", core.conversations.get(a.id)!!.title)
    }

    @Test fun interruptedReplyKeepsStateConsistent(): Unit = runBlocking {
        val engine = object : ai.clu.core.llm.LlmEngine by ScriptedEngine() {
            override fun generate(messages: List<ai.clu.core.llm.ChatMessage>, params: ai.clu.core.llm.GenParams, onText: (String) -> Unit): ai.clu.core.llm.GenResult {
                onText("Partial ans"); core.agent.cancel()
                return ai.clu.core.llm.GenResult("Partial ans", ai.clu.core.llm.GenStats(stopReason = "cancelled"), "local")
            }
        }
        core.useEngine(engine)
        val c = core.conversations.create()
        core.agent.handle(TurnRequest(c.id, "Tell me a long story", Actor.ownerInApp())) {}
        val msgs = core.conversations.messages(c.id)
        assertEquals(2, msgs.size)
        assertTrue(msgs[1].meta.interrupted)
        core.useEngine(ScriptedEngine().reply("Next reply"))
        core.agent.handle(TurnRequest(c.id, "Never mind", Actor.ownerInApp())) {}
        assertEquals(4, core.conversations.messages(c.id).size)
    }
}
