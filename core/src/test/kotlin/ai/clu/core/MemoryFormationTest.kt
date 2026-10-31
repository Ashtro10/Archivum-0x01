package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.history.Kind
import ai.clu.core.memory.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

/** What CLU makes of what the owner says: categories, temporary vs persistent, repeats, contradictions, and the consent rules. */
class MemoryFormationTest {
    private val clock = ai.clu.core.util.ManualClock(1_760_000_000_000L)
    private val core = newCore(clock = clock)
    private val engine = ScriptedEngine().also { core.useEngine(it) }
    private val conv = core.conversations.create().id
    private val owner = core.auth.actorForSpeaker(null, false)!!
    private fun say(t: String) = runBlocking { engine.reply("Noted."); core.agent.respond(TurnRequest(conv, t, owner)).toList() }
    /** What the model was given as per-message context for the last turn (retrieved memories, notes), not the chat history. */
    private fun context() = engine.prompts.last().last().content.substringAfter("[Context]").substringBefore("[/Context]")
    private fun live() = core.memories.list(null).filter { it.category != MemoryCategory.DIARY }

    @Test fun extractionSortsWhatIsWorthKeeping() {
        val f = core.formation
        fun natures(t: String) = f.extract(t).map { it.nature }
        assertEquals(listOf(MemoryFormation.Nature.FACT), natures("My sister's name is Mira."))
        assertEquals(listOf(MemoryFormation.Nature.FACT), natures("I work at a robotics startup in Pune."))
        assertEquals(listOf(MemoryFormation.Nature.FACT), natures("I'm allergic to penicillin."))
        assertEquals(listOf(MemoryFormation.Nature.PREFERENCE), natures("I prefer green tea in the evening."))
        assertEquals(listOf(MemoryFormation.Nature.PREFERENCE), natures("My favourite editor is Neovim."))
        assertEquals(listOf(MemoryFormation.Nature.TEMPORARY), natures("I'm at the airport right now."))
        assertEquals(listOf(MemoryFormation.Nature.TEMPORARY), natures("I have a dentist appointment tomorrow."))
        val proc = f.extract("To deploy the website, first run the tests, then build with make release, and finally rsync the dist folder.")
        assertEquals(listOf(MemoryFormation.Nature.PROCEDURE), proc.map { it.nature }); assertEquals(MemoryCategory.PROCEDURE, proc.single().category)
        val numbered = f.extract("How to rotate the logs:\n1. stop the service\n2. move the file\n3. start the service")
        assertEquals(MemoryCategory.PROCEDURE, numbered.single().category)
        assertEquals(2, f.extract("My sister's name is Mira. I prefer tea.").size, "several statements in one message")
        assertEquals(2, f.extract("My name is Ashwin.").single().importance, "identity facts are important")
        // Not statements about the owner's stable world: questions, guesses, plans, feelings, other people, habits (an inference).
        for (t in listOf("What is my sister's name?", "Maybe I prefer tea, I'm not sure.", "I'm going to buy a car.", "I'm tired.", "I am a bit hungry.",
            "She prefers coffee.", "I always forget to drink water when I'm working", "Do I live in Pune?", "If I live in Pune I will need a bike.", "Hello there, how are you?"))
            assertTrue(f.extract(t).isEmpty(), "should not form a memory from: $t -> ${f.extract(t)}")
    }

    @Test fun thePolicyDecidesWhatHappens() {
        // Default: suggest. Nothing is saved without approval.
        assertEquals("suggest", core.settings.string(Settings.FORMATION))
        val out = core.formation.process("My sister's name is Mira.", conv)
        assertTrue(out.single() is MemoryFormation.Outcome.Suggested)
        assertEquals(0, live().size)
        val p = core.proposals.pending().single()
        assertEquals(ProposalKind.FACT, p.kind); assertEquals(MemoryCategory.CORE_FACT, p.category); assertEquals("conversation:$conv", p.sourceRef)
        assertTrue(p.rationale.contains("only save it if you approve"))

        // Off: nothing at all, not even a suggestion or a note.
        core.settings.set(Settings.FORMATION, "off")
        assertTrue(core.formation.process("I prefer tea.", conv).isEmpty()); assertTrue(core.formation.process("I'm at the airport right now.", conv).isEmpty())
        assertEquals(1, core.proposals.pending().size); assertEquals(0, core.memory.working.count())

        // Auto: clearly stated facts and preferences are saved, with their conversation as provenance; a procedure is still only suggested.
        core.settings.set(Settings.FORMATION, "auto")
        val saved = core.formation.process("I'm allergic to penicillin. To deploy, first run the tests, then build, and finally upload the folder.", conv)
        val memory = saved.filterIsInstance<MemoryFormation.Outcome.Saved>().single().memory
        assertEquals(MemorySource.CONVERSATION, memory.source); assertEquals(2, memory.importance)
        val meta = core.memory.metaOf(memory); assertEquals("conversation:$conv", meta.sourceRef); assertEquals(0.9, meta.confidence)
        assertTrue(saved.filterIsInstance<MemoryFormation.Outcome.Suggested>().single().proposal.kind == ProposalKind.PROCEDURE)

        // Memory switched off entirely: nothing is formed.
        core.settings.set(Settings.MEMORY_ENABLED, false)
        assertTrue(core.formation.process("My brother's name is Kabir.", conv).isEmpty())
        // Only the owner's words count.
        core.settings.set(Settings.MEMORY_ENABLED, true)
        assertTrue(core.formation.process("My brother's name is Kabir.", conv, ownerSpeaking = false).isEmpty())
    }

    @Test fun temporaryDetailsGoToWorkingMemoryAndExpire() {
        core.settings.set(Settings.FORMATION, "auto")
        val out = core.formation.process("I'm at the airport right now, my flight is later today.", conv)
        assertTrue(out.single() is MemoryFormation.Outcome.Working)
        assertEquals(0, live().size, "temporary details never become persistent memory")
        assertEquals(1, core.memory.working.list(conv).size)
        clock.advance(25 * 3_600_000L)
        assertEquals(0, core.memory.working.list(conv).size)
    }

    @Test fun repeatingAKnownFactReinforcesItInsteadOfDuplicatingIt() {
        core.settings.set(Settings.FORMATION, "auto")
        core.formation.process("My sister's name is Mira.", conv)
        val first = live().single()
        val before = core.memory.metaOf(first).confidence!!
        val out = core.formation.process("my sister's name is Mira", conv)
        assertTrue(out.single() is MemoryFormation.Outcome.Reinforced)
        assertEquals(1, live().size); assertEquals(0, core.proposals.pending().size)
        assertTrue(core.memory.metaOf(first).confidence!! > before); assertEquals(1, core.memory.meta(first.id)!!.reinforced)
    }

    @Test fun aContradictionIsNeverSavedOverTheOldMemory() {
        core.settings.set(Settings.FORMATION, "auto")
        core.formation.process("My favourite editor is Vim.", conv)
        val old = live().single()
        val out = core.formation.process("My favourite editor is Neovim.", conv)
        // Even in auto mode a conflicting statement is only suggested, and it names what it conflicts with.
        val s = out.single() as MemoryFormation.Outcome.Suggested
        assertEquals(old.id, s.conflictsWith!!.id); assertEquals(old.id, s.proposal.relatedId); assertTrue(s.proposal.rationale.contains("may conflict"))
        assertEquals("My favourite editor is Vim", core.memories.get(old.id)!!.content.trimEnd('.'), "unchanged")
        // Approving keeps both and records the contradiction; the owner decides which stands.
        val approved = core.proposals.approve(s.proposal.id)!!
        assertEquals(2, live().size)
        assertTrue(core.memory.relatedTo(approved.id).any { it.id == old.id && it.relation == MemoryService.CONTRADICTS })
        assertEquals(listOf("contradicts \"My favourite editor is Vim\""), core.memory.detail(approved.id)!!.conflicts)
        assertEquals(Approval.APPROVED, core.memory.metaOf(approved).approval)
    }

    @Test fun suggestionsAreApprovedEditedOrRejectedByTheOwnerOnly() {
        core.formation.process("I prefer green tea in the evening. My dog's name is Biscuit.", conv)
        val (tea, dog) = core.proposals.pending().sortedBy { it.text }.let { it.first { p -> "tea" in p.text } to it.first { p -> "Biscuit" in p.text } }
        assertEquals(0, live().size)
        // Edit before approving: the owner's edited text is what is stored.
        val m = core.proposals.approve(tea.id, "I prefer green tea in the evening, without sugar.")!!
        assertEquals(MemoryCategory.PREFERENCE, m.category); assertEquals(MemorySource.CONVERSATION, m.source)
        assertEquals("I prefer green tea in the evening, without sugar.", m.content)
        val meta = core.memory.metaOf(m); assertEquals(Approval.APPROVED, meta.approval); assertEquals("conversation:$conv", meta.sourceRef); assertEquals(0.85, meta.confidence)
        // Reject: the text is erased and the same suggestion does not come back.
        core.proposals.reject(dog.id)
        assertEquals("", core.proposals.get(dog.id)!!.text)
        assertTrue(core.formation.process("My dog's name is Biscuit.", conv).isEmpty())
        assertEquals(1, live().size)
        assertFailsWith<IllegalStateException> { core.proposals.approve(tea.id) }
    }

    @Test fun inferencesAboutTheOwnerStillNeedApprovalAndNeverBecomeMemoriesByThemselves() {
        core.settings.set(Settings.FORMATION, "auto")
        val events = say("I always forget to drink water when I'm working")
        val p = events.filterIsInstance<AgentEvent.ProposalCreated>().single().proposal
        assertEquals(ProposalKind.OBSERVATION, p.kind)
        assertEquals(0, live().size)
        val m = core.proposals.approve(p.id, "Forgets to drink water while working")!!
        assertEquals(MemorySource.APPROVED_INFERENCE, m.source); assertEquals(MemoryCategory.INSIGHT, m.category); assertEquals(MemoryKind.CORE, core.memory.metaOf(m).kind)
        assertEquals(Approval.APPROVED, core.memory.metaOf(m).approval)
    }

    @Test fun throughTheConversationTheOwnerSeesASuggestionAndNothingIsSavedUntilTheyApprove() {
        val events = say("My sister's name is Mira")
        val p = events.filterIsInstance<AgentEvent.ProposalCreated>().single().proposal
        assertEquals(ProposalKind.FACT, p.kind); assertEquals(0, live().size)
        say("What should I cook tonight?")
        assertFalse(context().contains("Mira"), "a pending suggestion is not used as a fact")
        core.proposals.approve(p.id)
        say("Tell me about my sister Mira")
        assertTrue(context().contains("My sister's name is Mira"), "an approved fact reaches the prompt")
        assertEquals(1, core.memory.meta(live().single().id)!!.accessCount, "and is recorded as used")
    }

    @Test fun workingNotesReachThePromptOnlyForTheirConversation() {
        core.settings.set(Settings.FORMATION, "auto")
        say("I'm at the airport right now")
        say("Where should I get lunch?")
        assertTrue(context().contains("I'm at the airport right now"), context())
        val other = core.conversations.create().id
        engine.reply("Sure."); runBlocking { core.agent.respond(TurnRequest(other, "Where should I get lunch?", owner)).toList() }
        assertFalse(context().contains("airport"), "a note belongs to the conversation it came from")
    }

    @Test fun relatedMemoriesComeWithTheBestMatchThroughTheirLinks() {
        val project = core.memory.remember(Memory(category = MemoryCategory.PROJECT, title = "Hexapod project", content = "Six-leg walking robot with an STM32 board.", source = MemorySource.OWNER_SUPPLIED)).memory
        val decision = core.memory.remember(Memory(category = MemoryCategory.DECISION, title = "Battery choice", content = "Decided on a separate servo battery after the brown-out.", source = MemorySource.OWNER_SUPPLIED)).memory
        val retriever = ai.clu.core.agent.MemoryRetriever(core.settings, core.memories, service = core.memory)
        assertEquals(listOf(decision.id), retriever.retrieve("separate servo battery brown-out", listOf("clu")).map { it.memory.id })
        core.memory.relate(Kind.MEMORY, decision.id, Kind.MEMORY, project.id, MemoryService.BELONGS_TO)
        val ids = retriever.retrieve("separate servo battery brown-out", listOf("clu")).map { it.memory.id }
        assertEquals(listOf(decision.id, project.id), ids, "the linked project record came with the decision")
        // Both retrievals used the decision; only the second reached the project through the link.
        assertEquals(2, core.memory.meta(decision.id)!!.accessCount); assertEquals(1, core.memory.meta(project.id)!!.accessCount)
    }
}
