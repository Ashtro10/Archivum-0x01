package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnOrigin
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.conversation.Role
import ai.clu.core.llm.*
import ai.clu.core.memory.*
import ai.clu.core.util.ManualClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import org.junit.jupiter.api.Test

/**
 * Milestone 1: the foundation the rest of CLU is built on, tested through the real conversation engine (CluAgent with its
 * real stores), with stand-in models only where a real model would make the test slow or random.
 */
class FoundationTest {
    /** A model stand-in that records every prompt and answers with its own name, so it is visible which backend answered. */
    private class NamedModel(val name: String, val context: Int = 4096, val reply: (List<ChatMessage>) -> String = { "This is $name speaking." }) : LlmEngine {
        val prompts = CopyOnWriteArrayList<List<ChatMessage>>()
        @Volatile var delayPerPieceMs = 0L
        override val id = name; override val source = "local"
        override fun isReady() = true
        override fun countTokens(messages: List<ChatMessage>) = messages.sumOf { it.content.length / 4 + 4 }
        override fun contextSize() = context
        @Volatile private var stop = false
        override fun cancel() { stop = true }
        override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult {
            prompts += messages; stop = false
            val text = reply(messages)
            for (p in text.chunked(4)) { if (stop) return GenResult("", GenStats(stopReason = "cancelled"), source); onText(p); if (delayPerPieceMs > 0) Thread.sleep(delayPerPieceMs) }
            return GenResult(text, GenStats(promptTokens = countTokens(messages), generatedTokens = text.length / 4, stopReason = "eog"), source)
        }
    }

    private val platform = TestPlatform()
    private fun core(memory: ConversationMemory? = null) = CluCore(tempDb(), platform, ManualClock(1_760_000_000_000L), memory)
    private fun CluCore.owner() = auth.actorForSpeaker(null, speakerIdActive = false)!!
    private fun CluCore.ask(conv: String, text: String): List<AgentEvent> = runBlocking { agent.respond(TurnRequest(conv, text, owner())).toList() }
    private fun List<AgentEvent>.final() = filterIsInstance<AgentEvent.Done>().lastOrNull()?.text

    @Test fun theModelBackendIsReplaceableAndTheConversationOutlivesIt() {
        val c = core(); val conv = c.conversations.create().id
        val a = NamedModel("model A"); c.useEngine(a)
        assertEquals("This is model A speaking.", c.ask(conv, "Hello, who's there?").final())
        val b = NamedModel("model B"); c.useEngine(b)
        assertEquals("This is model B speaking.", c.ask(conv, "And now?").final())
        // The identity and the conversation belong to CLU, not to the model: the new backend gets the same history and instructions.
        val history = b.prompts.last().joinToString("\n") { it.content }
        assertTrue("Hello, who's there?" in history && "This is model A speaking." in history, "history carried over")
        assertEquals(a.prompts.last().first().content, b.prompts.last().first().content, "same system instructions for any backend")
        assertEquals(4, c.conversations.messages(conv).size)
    }

    @Test fun theConversationEngineHasNoUserInterfaceOrPlatformDependencies() {
        val root = listOf(File("src/main/kotlin"), File("core/src/main/kotlin")).first { it.isDirectory }
        val offenders = root.walk().filter { it.extension == "kt" }.filter { f ->
            f.readLines().any { it.startsWith("import android.") || it.startsWith("import androidx.") || it.startsWith("import javax.swing") || it.startsWith("import java.awt") } }.toList()
        assertTrue(offenders.isEmpty(), "core imports UI/platform code: $offenders")
    }

    @Test fun memoryIsReachedThroughOneReplaceableInterface() {
        val asked = CopyOnWriteArrayList<String>(); val kept = CopyOnWriteArrayList<String>()
        val fake = object : ConversationMemory {
            override fun relevant(query: String, scopes: List<String>): List<ScoredMemory> { asked += query
                return listOf(ScoredMemory(Memory(category = MemoryCategory.CORE_FACT, title = "Bike", content = "The owner's bike is a red Royal Enfield.", source = MemorySource.OWNER_SUPPLIED), 9.0)) }
            override fun workingNotes(conversationId: String, limit: Int) = listOf("Parked on level 3")
            override fun afterTurn(text: String, conversationId: String): List<MemoryFormation.Outcome> { kept += text; return emptyList() }
        }
        val c = core(fake); val m = NamedModel("m") { "It is red." }; c.useEngine(m)
        val conv = c.conversations.create().id
        assertEquals("It is red.", c.ask(conv, "What colour is my bike?").final())
        val prompt = m.prompts.last().joinToString("\n") { it.content }
        assertEquals(listOf("What colour is my bike?"), asked.toList(), "relevance asked once per turn, with the owner's words")
        assertTrue("red Royal Enfield" in prompt && "Parked on level 3" in prompt, "what memory returned reaches the model")
        assertEquals(listOf("What colour is my bike?"), kept.toList(), "the turn is offered to memory afterwards")
    }

    @Test fun emptyInputIsRejectedWithoutTouchingTheModelOrTheHistory() {
        val c = core(); val m = NamedModel("m"); c.useEngine(m); val conv = c.conversations.create().id
        for (t in listOf("", "   ", "\n\t")) {
            val ev = c.ask(conv, t)
            assertTrue(ev.any { it is AgentEvent.Done || it is AgentEvent.Error }, "the turn ends: $ev")
        }
        assertTrue(m.prompts.isEmpty(), "the model is never asked to answer nothing")
        assertTrue(c.conversations.messages(conv).isEmpty(), "nothing empty is stored")
    }

    @Test fun aLongConversationKeepsWorkingAndKeepsTheNewestContext() {
        val c = core(); val m = NamedModel("m", context = 1024) { msgs -> "Noted: " + msgs.last { it.role == "user" }.content.substringAfterLast("]").trim().take(40) }; c.useEngine(m)
        val conv = c.conversations.create().id
        repeat(60) { i -> assertTrue(c.ask(conv, "Message number $i about topic ${i % 7}.").final()!!.contains("Message number $i"), "turn $i answered") }
        assertEquals(120, c.conversations.messages(conv).size)
        val last = m.prompts.last()
        assertTrue(last.sumOf { it.content.length / 4 + 4 } <= 1024, "the prompt stays inside the context window")
        assertTrue(last.last().content.contains("Message number 59"), "the newest message is always there")
        assertTrue(last.none { it.content.contains("Message number 0 ") }, "the oldest turns were dropped, not the newest")
    }

    @Test fun aNewerMessageSupersedesTheOlderReplyWithoutDuplicates() {
        val c = core(); val m = NamedModel("m") { msgs -> "Answer to " + msgs.last { it.role == "user" }.content.substringAfterLast("]").trim() + " " + "and more ".repeat(30) }
        m.delayPerPieceMs = 15; c.useEngine(m)
        val conv = c.conversations.create().id
        runBlocking {
            val first = async(Dispatchers.Default) { c.agent.respond(TurnRequest(conv, "First question", c.owner(), origin = TurnOrigin.TEXT)).toList() }
            withTimeout(5_000) { while (m.prompts.isEmpty()) delay(5) }
            val second = c.agent.respond(TurnRequest(conv, "Second question", c.owner(), origin = TurnOrigin.TEXT)).toList()
            val firstEvents = first.await()
            assertTrue(second.final()!!.startsWith("Answer to Second question"))
            val firstDone = firstEvents.filterIsInstance<AgentEvent.Done>().lastOrNull()
            assertTrue(firstDone == null || firstDone.meta.interrupted || firstDone.text.isBlank() || firstEvents.any { it is AgentEvent.Error }, "the older reply was stopped: $firstDone")
        }
        val msgs = c.conversations.messages(conv)
        assertEquals(1, msgs.count { it.role == Role.USER && it.content == "Second question" })
        assertEquals(1, msgs.count { it.role == Role.ASSISTANT && it.content.startsWith("Answer to Second question") }, "exactly one reply to the newer message")
        assertTrue(msgs.none { it.role == Role.ASSISTANT && it.content.startsWith("Answer to First question") && it.content.length > 300 }, "the superseded reply did not complete")
    }

    @Test fun specialTokensAndReasoningNeverReachTheOwner() {
        val c = core(); val conv = c.conversations.create().id
        c.useEngine(NamedModel("m") { "<think>plan</think>Hello there.<|im_end|>" })
        val ev = c.ask(conv, "Hi")
        val shown = ev.filterIsInstance<AgentEvent.Text>().joinToString("") { it.delta } + "|" + ev.final()
        assertFalse(Regex("<\\|[a-z_]+\\|>|</?think>").containsMatchIn(shown), shown)
        assertEquals("Hello there.", ev.final())
    }

    @Test fun settingsReallyChangeWhatTheModelIsAsked() {
        val c = core(); val m = NamedModel("m"); c.useEngine(m); val conv = c.conversations.create().id
        c.settings.set(Settings.RESPONSE_LENGTH, "brief"); c.ask(conv, "Tell me about rivers.")
        assertTrue(m.prompts.last().first().content.contains("Keep replies brief"))
        c.settings.set(Settings.RESPONSE_LENGTH, "detailed"); c.ask(conv, "Tell me about lakes.")
        assertTrue(m.prompts.last().first().content.contains("detailed"))
        c.settings.set(Settings.CLU_NAME, "Clue", verified = true); c.ask(conv, "Hi")
        assertTrue(m.prompts.last().first().content.startsWith("You are Clue"), "the name the owner chose is the identity")
    }
}
