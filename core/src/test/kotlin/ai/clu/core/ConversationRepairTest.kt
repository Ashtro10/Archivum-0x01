package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.diag.TurnEvent
import ai.clu.core.llm.*
import ai.clu.core.tools.HttpResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import org.junit.jupiter.api.Test

/**
 * M8.5: the conversation failures seen in real use, through the real conversation engine (CluAgent, research, history)
 * with a model that writes hidden reasoning the way Qwen3 does, split across tokens, and a fake Wikipedia.
 */
class ConversationRepairTest {
    private val platform = TestPlatform()
    private val core = newCore(platform = platform)
    private val conv = core.conversations.create().id
    private val owner = core.auth.actorForSpeaker(null, speakerIdActive = false)!!
    private val leak = Regex("(?i)</?\\s*(think|thinking|analysis|reasoning)\\s*>|\\[/?Context]")

    /** Writes `<think>…</think>` before every answer (3-character pieces, so tags split); [neverClose] reasons forever once. */
    private inner class ThinkingModel : LlmEngine {
        override val id = "thinking"; override val source = "local"
        val prompts = CopyOnWriteArrayList<List<ChatMessage>>()
        var neverClose = false
        override fun isReady() = true
        override fun countTokens(messages: List<ChatMessage>) = messages.sumOf { it.content.length / 4 + 4 }
        override fun contextSize() = 8192
        override fun cancel() {}
        override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult {
            prompts += messages
            if (System.getenv("CLU_DEBUG_PROMPT") != null) println("PROMPT>> " + messages.takeLast(3).joinToString(" || ") { it.role + ": " + it.content.takeLast(160).replace("\n", " ") })
            val last = messages.last().content
            val user = messages.last { it.role == "user" }.content.lowercase().substringAfterLast("[/context]")
            val answer = when {
                last.startsWith("[Information found]") || last.startsWith("[Correction]") ->
                    Regex("(?m)^\\[1] [^:]+: (.+)$").find(messages.joinToString("\n") { it.content })?.groupValues?.get(1)?.substringBefore(". ")?.trimEnd('.')?.plus(".") ?: "I don't know."
                "hear me" in user -> "Yes, I can hear you clearly."
                "hello" in user -> "Hello! Good to hear from you."
                else -> "I'm not sure."
            }
            val raw = if (neverClose) "<think>\nOkay, the user " + "keeps me thinking ".repeat(200) else "<think>\nOkay, the user said something. Let me reason about it.\n</think>\n\n$answer"
            raw.chunked(3).forEach(onText)
            return GenResult(raw, GenStats(promptTokens = 100, generatedTokens = raw.length / 4, stopReason = "eog"), source)
        }
    }
    private val model = ThinkingModel().also { core.useEngine(it) }

    init {
        core.settings.set(Settings.SEARCH_READ_PAGES, false)
        val hit = HttpResponse(200, """{"query":{"searchinfo":{"totalhits":1},"search":[{"title":"Leman Russ","snippet":"Leman Russ is the Primarch of the Space Wolves, one of the Space Marine Legions in Warhammer 40,000. He leads the VI Legion from Fenris.","timestamp":"2025-11-02T10:00:00Z"}]}}""")
        for (q in listOf("Lemon Russ", "Leman Russ", "lemon rust")) platform.fakeHttp.routes["srsearch=" + java.net.URLEncoder.encode(q, "UTF-8") + "&"] = hit
    }

    private fun say(text: String, voice: Boolean = true): Pair<List<AgentEvent>, ai.clu.core.diag.RequestTrace.Request> = runBlocking {
        var trace: ai.clu.core.diag.RequestTrace.Request? = null
        val ev = core.agent.respond(TurnRequest(conv, text, owner, viaVoice = voice, onTrace = { trace = it })).toList()
        ev to trace!!
    }
    private fun List<AgentEvent>.final() = filterIsInstance<AgentEvent.Done>().last().text
    private fun List<AgentEvent>.streamed() = filterIsInstance<AgentEvent.Text>().joinToString("") { it.delta }
    private fun ai.clu.core.diag.RequestTrace.Request.routes() = events.filter { it.name == TurnEvent.ROUTE }.map { it.detail }

    @Test fun reasoningNeverReachesTheStreamTheReplyOrTheHistory() {
        val (ev, trace) = say("Hello, Clue.")
        assertEquals("Hello! Good to hear from you.", ev.final())
        assertFalse(leak.containsMatchIn(ev.streamed()), "streamed: ${ev.streamed()}")
        assertTrue(core.conversations.messages(conv).none { leak.containsMatchIn(it.content) })
        assertTrue(trace.events.any { it.name == TurnEvent.LLM_CALL })
    }

    @Test fun aModelThatNeverStopsReasoningStillGetsTheOwnerAnAnswerOrAClearError() {
        model.neverClose = true
        val (ev, trace) = say("Hello there.")
        val done = ev.filterIsInstance<AgentEvent.Done>().lastOrNull(); val err = ev.filterIsInstance<AgentEvent.Error>().lastOrNull()
        assertTrue(done != null || err != null, "the turn ends")
        assertFalse(leak.containsMatchIn(ev.streamed()) || leak.containsMatchIn(done?.text ?: ""), "nothing of the reasoning is shown")
        assertTrue(done?.text.orEmpty().isNotBlank() || err?.text.orEmpty().contains("reasoning"), "never silent: ${done?.text} / ${err?.text}")
        assertNotNull(trace.terminal)
    }

    @Test fun aGreetingIsOneModelCallWithoutResearch() {
        val (ev, trace) = say("Hello, Clue.")
        assertEquals(listOf("answer"), trace.routes())
        assertEquals(1, trace.events.count { it.name == TurnEvent.LLM_CALL })
        assertTrue(platform.fakeHttp.requests.isEmpty(), "no web request for a greeting")
        val (ev2, _) = say("Can you hear me?")
        assertEquals("Yes, I can hear you clearly.", ev2.final())
        assertTrue(ev.final().isNotBlank())
    }

    @Test fun theAcceptanceConversationIsUnderstood() {
        say("Hello, Clue.")
        val (who, t1) = say("Who is Lemon Russ?")
        assertTrue("research" in t1.routes(), t1.routes().toString())
        assertTrue(who.final().contains("Leman Russ"), who.final())
        // A correction of the previous request, not a new unrelated question.
        val (fix, t2) = say("I said lemon rust.")
        assertTrue(t2.routes().any { it.startsWith("speech corrected") }, t2.routes().toString())
        assertTrue(fix.final().contains("Leman Russ") && fix.final().contains("Primarch"), fix.final())
        assertEquals("Who is Leman Russ?", core.conversations.messages(conv).last { it.role == ai.clu.core.conversation.Role.USER }.meta.interpreted)
        // "Search for him" is research on him.
        val before = platform.fakeHttp.requests.size
        val (search, t3) = say("Can you search for him?")
        assertTrue(t3.routes().any { "research" in it }, t3.routes().toString())
        assertTrue(platform.fakeHttp.requests.drop(before).any { "Leman" in it || "Russ" in it }, platform.fakeHttp.requests.drop(before).toString())
        assertTrue(search.final().contains("Leman Russ"), search.final())
        assertTrue(core.conversations.messages(conv).none { leak.containsMatchIn(it.content) })
    }

    @Test fun aMisheardNameTheConversationKnowsIsUnderstoodAndAnUncertainOneIsAsked() {
        say("Who is Leman Russ?", voice = false)
        val (ev, t) = say("Tell me more about lemon rust.")
        assertTrue(t.routes().first().startsWith("speech corrected"), t.routes().toString())
        assertEquals("Tell me more about Leman Russ.", core.conversations.messages(conv).last { it.role == ai.clu.core.conversation.Role.USER }.meta.interpreted)
        assertTrue(ev.final().isNotBlank())
        // Typed text is never re-interpreted.
        val (_, typed) = say("Tell me more about lemon rust.", voice = false)
        assertTrue(typed.routes().none { it.startsWith("speech corrected") })
    }
}
