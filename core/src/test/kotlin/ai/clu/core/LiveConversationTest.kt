package ai.clu.core

import ai.clu.core.conversation.Role
import ai.clu.core.live.AgentPhase
import ai.clu.core.live.Link
import ai.clu.core.live.LiveConversation
import ai.clu.core.live.LiveDiagnostics
import ai.clu.core.util.ManualClock
import org.junit.jupiter.api.Test
import kotlin.test.*

/** The live conversation state and history, fed with the events the LiveKit client delivers (no network). */
class LiveConversationTest {
    private val clock = ManualClock(1_760_000_000_000L)
    private val core = newCore(clock = clock)
    private val conv = core.conversations.create().id
    private val live = LiveConversation(core.conversations, { conv }, clock::now)
    private fun history() = core.conversations.messages(conv).map { it.role to it.content }

    @Test fun voiceTurnStoresFinalTranscriptsAndReplyNotInterimText() {
        live.onLink(Link.CONNECTED); live.onAgentState("listening"); live.onMic(true)
        live.onUserTranscript("seg1", "what time", final = false)
        assertEquals("what time", live.state.value.userLive)
        live.onUserTranscript("seg1", "What time is it?", final = true)
        live.onUserTranscript("seg1", "What time is it?", final = true) // repeated final for the same segment: stored once
        assertEquals("", live.state.value.userLive)
        live.onAgentState("thinking")
        assertTrue(live.state.value.thinking)
        live.onAgentState("speaking")
        live.onAgentChunk("r1", "It's half past "); live.onAgentChunk("r1", "nine.")
        assertEquals("It's half past nine.", live.state.value.agentLive)
        live.onAgentStreamEnd("r1")
        live.onAgentState("listening")
        assertEquals(listOf(Role.USER to "What time is it?", Role.ASSISTANT to "It's half past nine."), history())
        assertEquals("", live.state.value.agentLive)
        assertFalse(live.state.value.thinking)
    }

    @Test fun typedMessageShowsThinkingUntilTheReplyArrives() {
        live.onLink(Link.CONNECTED); live.onAgentState("listening")
        live.onTypedSent("Remember forty two")
        assertTrue(live.state.value.thinking && live.state.value.awaitingReply)
        live.onAgentChunk("r2", "Noted.")
        assertFalse(live.state.value.thinking, "first words end the thinking state")
        live.onAgentStreamEnd("r2")
        assertEquals(listOf(Role.USER to "Remember forty two", Role.ASSISTANT to "Noted."), history())
        assertEquals("typed", core.conversations.messages(conv).first().meta.source)
    }

    @Test fun unansweredMessageTimesOutWithAClearError() {
        live.onLink(Link.CONNECTED); live.onAgentState("listening")
        live.onTypedSent("Hello?")
        clock.advance(10_000); assertFalse(live.checkReplyTimeout(30_000))
        clock.advance(25_000); assertTrue(live.checkReplyTimeout(30_000))
        assertFalse(live.state.value.thinking)
        assertTrue(live.state.value.error!!.contains("didn't answer"))
    }

    @Test fun interruptedReplyIsKeptAndMarked() {
        live.onLink(Link.CONNECTED); live.onAgentState("speaking")
        live.onAgentChunk("r3", "Once there was a lighthouse keeper who")
        live.onAgentStreamEnd("r3", interrupted = true)
        val m = core.conversations.messages(conv).single()
        assertTrue(m.meta.interrupted); assertEquals("Once there was a lighthouse keeper who", m.content)
    }

    @Test fun disconnectClearsLiveStateAndNeverLeavesThinking() {
        live.onLink(Link.CONNECTED); live.onAgentState("thinking"); live.onMic(true)
        live.onTypedSent("Question")
        live.onAgentChunk("r4", "Partial ans")
        live.onLink(Link.DISCONNECTED, "connection lost")
        val s = live.state.value
        assertEquals(AgentPhase.ABSENT, s.agent); assertFalse(s.micOn); assertFalse(s.thinking); assertEquals("", s.agentLive)
        assertEquals("connection lost", s.error)
        assertTrue(core.conversations.messages(conv).last().meta.interrupted, "the partial reply is kept, marked interrupted")
        live.onLink(Link.RECONNECTING); live.onLink(Link.CONNECTED)
        assertNull(live.state.value.error, "a successful reconnect clears the error")
    }

    @Test fun historyForTheAgentAndMetrics() {
        live.onTypedSent("one"); live.onAgentChunk("a", "two"); live.onAgentStreamEnd("a")
        assertEquals(listOf("user" to "one", "assistant" to "two"), live.history())
        live.onMetrics("""{"speech_end_to_first_audio_ms": 820, "llm_ttft_ms": 310}""")
        assertEquals(820.0, live.state.value.metrics["speech_end_to_first_audio_ms"])
        live.onMetrics("not json") // ignored
        assertEquals(2, live.state.value.metrics.size)
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (0..6).map { LiveConversation.backoffMs(it) })
    }

    @Test fun agentPhasesFromAttributes() {
        assertEquals(AgentPhase.SPEAKING, AgentPhase.from("speaking"))
        assertEquals(AgentPhase.ABSENT, AgentPhase.from(null))
        assertEquals(AgentPhase.ABSENT, AgentPhase.from("bogus"))
        live.onLink(Link.CONNECTED); live.onAgentState("initializing")
        assertFalse(live.state.value.ready)
        live.onAgentState("listening")
        assertTrue(live.state.value.ready)
    }

    @Test fun replyIsMarkedInterruptedOnlyWhenTheOwnerSpokeOverIt() {
        live.onLink(Link.CONNECTED); live.onAgentState("speaking")
        live.onAgentChunk("a1", "A complete answer.")
        live.onAgentStreamEnd("a1")
        live.onAgentChunk("a2", "Once there was a")
        live.onUserTranscript("u9", "stop", final = false) // the owner talks over the reply
        live.onAgentStreamEnd("a2")
        live.onAgentState("listening")
        live.onAgentChunk("a3", "Four.") // the next reply starts clean
        live.onAgentStreamEnd("a3")
        assertEquals(listOf(false, true, false), core.conversations.messages(conv).filter { it.role == Role.ASSISTANT }.map { it.meta.interrupted })
    }

    @Test fun agentErrorsBecomeActionableMessages() {
        live.onLink(Link.CONNECTED); live.onAgentState("listening")
        live.onTypedSent("Hello")
        live.onAgentEvent("""{"type":"error","component":"llm","recoverable":true,"message":"APIConnectionError: Connection error."}""")
        assertNull(live.state.value.error, "the framework retries recoverable errors; nothing to report yet")
        assertTrue(live.state.value.awaitingReply)
        live.onAgentEvent("""{"type":"error","component":"llm","recoverable":false,"message":"APIConnectionError: Connection error."}""")
        assertEquals("Clu's language model failed on the server: APIConnectionError: Connection error.", live.state.value.error)
        assertFalse(live.state.value.awaitingReply, "no endless thinking after a model failure")
        live.onAgentEvent("""{"type":"session_closed","reason":"error","component":"tts","message":"x"}""")
        assertTrue(live.state.value.error!!.contains("stopped after repeated agent failures") || live.state.value.error!!.contains("repeated"))
        live.onAgentEvent("not json"); live.onAgentEvent("""{"no":"type"}""") // ignored
        live.onAgentState(null); live.onAgentState("listening")
        assertNull(live.state.value.error, "a fresh agent that is listening clears the error")
    }

    @Test fun thinkingWithoutAnAnswerTimesOut() {
        live.onLink(Link.CONNECTED); live.onAgentState("listening"); live.onAgentState("thinking")
        clock.advance(44_000); assertFalse(live.checkReplyTimeout())
        clock.advance(2_000); assertTrue(live.checkReplyTimeout())
        assertTrue(live.state.value.error!!.contains("thinking for 45 s"))
    }

    @Test fun latencyMeasuredOnThePhoneAndDiagnosticsWithoutConversationContent() {
        live.onLink(Link.CONNECTED); live.onAgentState("listening")
        live.onProviders(mapOf("clu.stack" to "local", "clu.llm" to "smollm2 (OpenAI-compatible server)", "lk.agent.state" to "listening"))
        assertEquals(mapOf("stack" to "local", "llm" to "smollm2 (OpenAI-compatible server)"), live.state.value.providers)
        live.onSpokenReplies(true)
        live.onTypedSent("My secret plan is to visit Lonavala")
        clock.advance(400); live.onAgentState("thinking")
        clock.advance(300); live.onAgentChunk("r", "Sounds good.")
        clock.advance(200); live.onAgentState("speaking")
        assertEquals(700L, live.state.value.appMetrics["text_to_first_text"])
        assertEquals(900L, live.state.value.appMetrics["text_to_speaking"])
        val report = LiveDiagnostics.report(live.state.value, live.events(), mapOf("device key" to "set", "note" to "phone.AbCdEfGhIjKlMnOpQrStUv"))
        assertFalse(report.contains("Lonavala") || report.contains("Sounds good"), "no conversation content in diagnostics")
        assertTrue(report.contains("typed_sent 35 chars") && report.contains("text_to_first_text=700"))
        assertTrue(report.contains("<device-key>") && !report.contains("AbCdEfGhIjKlMnOpQrStUv"), "device-key-shaped strings are removed")
        assertEquals("Bearer <key> <token> http://<credentials>@lk.example", LiveDiagnostics.redact("Bearer phone.0123456789abcdef0 eyJhbGciOiJIUzI1.eyJzdWIiOiJjbHUt.c2lnbmF0dXJlcw http://user:pw@lk.example"))
    }

    @Test fun eventLogIsBounded() {
        repeat(LiveConversation.MAX_EVENTS + 50) { live.log("x", "$it") }
        assertEquals(LiveConversation.MAX_EVENTS, live.events().size)
        assertEquals("50", live.events().first().detail)
    }

    @Test fun typedMessageCountsAsUnacknowledgedUntilTheAgentReacts() {
        live.onLink(Link.CONNECTED); live.onAgentState("listening")
        live.onTypedSent("What is my name?")
        assertTrue(live.typedUnacknowledged(), "dropped by an agent that hasn't seen the rejoin: the client resends")
        live.onAgentState("thinking")
        assertFalse(live.typedUnacknowledged())
        live.onAgentState("listening"); live.onAgentChunk("x", "Ashwin."); live.onAgentStreamEnd("x")
        live.onTypedSent("And again?")
        live.onAgentChunk("y", "Yes.") // a reply without a visible thinking phase also counts
        assertFalse(live.typedUnacknowledged())
    }
}
