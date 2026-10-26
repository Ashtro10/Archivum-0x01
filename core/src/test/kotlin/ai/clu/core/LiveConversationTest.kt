package ai.clu.core

import ai.clu.core.conversation.Role
import ai.clu.core.live.AgentPhase
import ai.clu.core.live.Link
import ai.clu.core.live.LiveConversation
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
}
