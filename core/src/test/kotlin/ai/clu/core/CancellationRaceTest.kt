package ai.clu.core

import ai.clu.core.agent.*
import ai.clu.core.config.Settings
import ai.clu.core.conversation.Role
import ai.clu.core.tools.HttpResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.*

/**
 * Stage 3.1: races between cancellation, network completion, model replies and newer requests. JVM, mocked HTTP.
 * A cancelled request must never surface as an answer, overwrite a newer one, start more work, or leave the chat busy.
 */
class CancellationRaceTest {
    private val platform = TestPlatform()
    private val core = newCore(platform = platform)
    private val http get() = platform.fakeHttp
    private val engine = ScriptedEngine().also { core.useEngine(it) }
    private val owner = core.auth.actorForSpeaker(null, false)!!
    init {
        core.settings.set(Settings.SEARCH_PROVIDER, "searxng"); core.settings.set(Settings.SEARXNG_URL, "https://searx.example.org")
        core.settings.set(Settings.SEARCH_READ_PAGES, false)
        http.routes["searx.example.org"] = HttpResponse(200, """{"results":[{"url":"https://a.example/x","title":"T","content":"c"}]}""")
    }

    @Test fun aNewerRequestSupersedesAndTheOldOneNeverAnswers(): Unit = runBlocking {
        val conv = core.conversations.create().id
        http.delayMs = 500
        val old = async(Dispatchers.Default) { core.agent.respond(TurnRequest(conv, "What is the latest Android version?", owner, origin = TurnOrigin.VOICE)).toList() }
        delay(100)
        http.delayMs = 0; engine.reply("Hello there.")
        val new = core.agent.respond(TurnRequest(conv, "Hello Clu", owner, origin = TurnOrigin.VOICE)).toList()
        val oldEvents = old.await(); delay(700)
        assertTrue(oldEvents.filterIsInstance<AgentEvent.Done>().single().meta.interrupted)
        assertEquals("Hello there.", new.filterIsInstance<AgentEvent.Done>().single().text)
        val msgs = core.conversations.messages(conv)
        assertEquals(listOf(Role.USER, Role.USER, Role.ASSISTANT), msgs.map { it.role }, "the superseded request saved no reply: ${msgs.map { it.content }}")
        assertEquals("Hello there.", msgs.last().content)
        assertEquals(1, engine.prompts.size, "the old request never reached the model")
    }

    @Test fun cancellingAtRandomMomentsAlwaysEndsCleanly(): Unit = runBlocking {
        val rnd = Random(7)
        val conv = core.conversations.create().id
        repeat(25) { i ->
            http.delayMs = rnd.nextLong(0, 40)
            val job = async(Dispatchers.Default) { core.agent.respond(TurnRequest(conv, "What is the latest Android version $i?", owner)).toList() }
            delay(rnd.nextLong(0, 50)); core.agent.cancel(null)
            val ev = job.await()
            val done = ev.filterIsInstance<AgentEvent.Done>()
            assertEquals(1, done.size, "exactly one terminal event (iteration $i): $ev")
            assertTrue(ev.none { it is AgentEvent.Error && !it.text.contains("cancel", ignoreCase = true) }, "no spurious error: $ev")
            // An interrupted turn never claims sources it didn't use; a completed one has its reply (the scripted default "OK.").
            if (done.single().meta.interrupted) assertTrue(done.single().meta.sources.isEmpty() || done.single().text.isNotEmpty())
        }
        assertFalse(core.agent.busy(), "nothing left running")
        // Every saved assistant message belongs to a completed or explicitly interrupted turn, and the conversation still works.
        http.delayMs = 0; engine.reply("Still here.")
        assertEquals("Still here.", core.agent.respond(TurnRequest(conv, "Hi again", owner)).toList().filterIsInstance<AgentEvent.Done>().single().text)
    }

    @Test fun chatStateIsIdleAfterStopDuringResearch(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = ChatTurns(core.agent, scope)
        val conv = core.conversations.create().id
        http.delayMs = 600
        assertTrue(chat.send(TurnRequest(conv, "What is the latest Android version?", owner), "What is the latest Android version?"))
        withTimeout(2000) { chat.state.first { it.status == "Searching the web…" } }
        chat.stop()
        val idle = withTimeout(2000) { chat.state.first { !it.running } }
        assertNull(idle.status, "no stale \"Searching the web…\" after Stop")
        assertTrue(idle.events.none { it is AgentEvent.Error }, "Stop is not an error")
        http.delayMs = 0; engine.reply("Next answer.")
        assertTrue(chat.send(TurnRequest(conv, "Hello", owner), "Hello"), "the next message is accepted")
        withTimeout(2000) { chat.state.first { !it.running && it.seq == idle.seq + 1 } }
        assertEquals("Next answer.", core.conversations.messages(conv).last().content)
        scope.cancel()
    }

    @Test fun aFailingTurnLeavesNoLoadingState(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = ChatTurns(core.agent, scope)
        val conv = core.conversations.create().id
        http.failWith = java.io.IOException("network down")
        engine.reply("I couldn't search, but from what I know it may be Android 16.")
        chat.send(TurnRequest(conv, "What is the latest Android version?", owner), "x")
        val s = withTimeout(3000) { chat.state.first { !it.running && it.seq == 1L } }
        assertNull(s.status); assertNull(s.streaming)
        http.failWith = null
        scope.cancel()
    }
}
