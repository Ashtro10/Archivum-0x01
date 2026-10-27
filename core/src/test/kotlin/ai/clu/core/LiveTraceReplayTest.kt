package ai.clu.core

import ai.clu.core.conversation.Role
import ai.clu.core.live.AgentPhase
import ai.clu.core.live.Link
import ai.clu.core.live.LiveConversation
import ai.clu.core.util.CluJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

/**
 * Replays what a REAL agent session sent to the phone (recorded by server/tests/e2e_agents.py: agent state attributes,
 * transcript streams with their ids/attributes/chunks, metrics and events, in arrival order) through the app's
 * LiveConversation, routed exactly as LiveKitSession routes SDK events. Checks that the app's history and state agree
 * with what the agent actually did: every typed message and final voice transcript stored once, interim transcripts
 * not stored, each agent reply stored once with its full text, cut-off replies marked interrupted, nothing left
 * waiting at the end.
 */
class LiveTraceReplayTest {
    private val traceFile = File(System.getProperty("clu.liveTrace", "../docs/measurements/livekit-real-trace.jsonl"))

    @Test fun realAgentSessionReplaysIntoConsistentHistory() {
        assumeTrue(traceFile.exists(), "no recorded trace at $traceFile")
        val rows = traceFile.readLines().filter { it.isNotBlank() }.map { CluJson.parseToJsonElement(it).jsonObject }
        val core = newCore()
        val conv = core.conversations.create().id
        val live = LiveConversation(core.conversations, { conv })
        val streams = HashMap<String, Pair<Boolean, JsonObject>>()          // id -> (owner, attributes)
        val ownerText = HashMap<String, StringBuilder>()
        val agentText = LinkedHashMap<String, StringBuilder>()             // what the agent streamed, in order
        val finals = LinkedHashMap<String, String>()                         // final owner segments
        var interims = 0; var agentIdentity: String? = null; var cutOff = 0; var stops = 0
        val typed = mutableListOf<String>()

        fun s(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull
        for (r in rows) when (s(r, "ev")) {
            "connected" -> live.onLink(Link.CONNECTED)
            "attr" -> {
                val attrs = r["attrs"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
                attrs[LiveConversation.ATTR_AGENT_STATE]?.let { agentIdentity = s(r, "identity"); live.onAgentState(it) }
                live.onProviders(attrs)
            }
            "participant_left" -> if (s(r, "identity") == agentIdentity) live.onAgentState(null)
            "typed" -> { typed += s(r, "text")!!; live.onTypedSent(s(r, "text")!!) }
            "stop" -> { stops++; live.onStopRequested() }
            "stream_open" -> streams[s(r, "id")!!] = r["owner"]!!.jsonPrimitive.boolean to r["attrs"]!!.jsonObject
            "chunk" -> {
                val id = s(r, "id")!!; val (owner, _) = streams.getValue(id)
                if (owner) ownerText.getOrPut(id) { StringBuilder() }.append(s(r, "text"))
                else { agentText.getOrPut(id) { StringBuilder() }.append(s(r, "text")); live.onAgentChunk(id, s(r, "text")!!) }
            }
            "stream_end" -> {
                val id = s(r, "id")!!; val (owner, attrs) = streams.getValue(id)
                if (owner) {
                    val final = s(attrs, LiveConversation.ATTR_FINAL) == "true"
                    val seg = s(attrs, LiveConversation.ATTR_SEGMENT) ?: id
                    val text = ownerText[id]?.toString().orEmpty()
                    if (final) { if (text.isNotBlank()) finals[seg] = text.trim() } else interims++
                    if (live.state.value.agent == AgentPhase.SPEAKING && text.isNotBlank()) cutOff++
                    live.onUserTranscript(seg, text, final)
                } else live.onAgentStreamEnd(id)
            }
            "data" -> when (s(r, "topic")) {
                LiveConversation.TOPIC_METRICS -> live.onMetrics(s(r, "payload")!!)
                LiveConversation.TOPIC_EVENTS -> live.onAgentEvent(s(r, "payload")!!)
            }
        }

        val msgs = core.conversations.messages(conv)
        val users = msgs.filter { it.role == Role.USER }; val replies = msgs.filter { it.role == Role.ASSISTANT }
        assertTrue(typed.size >= 5 && finals.size >= 4, "the trace covers typed and spoken turns (typed=${typed.size}, voice=${finals.size})")
        assertTrue(interims > 0, "the agent sent interim transcripts")
        assertEquals(typed.map { it.trim() }, users.filter { it.meta.source == "typed" }.map { it.content }, "every typed message stored once, in order")
        assertEquals(finals.values.toList(), users.filter { it.meta.source == "voice" }.map { it.content }, "every final voice transcript stored once; interim ones not stored")
        val streamed = agentText.values.map { it.toString().trim() }.filter { it.isNotEmpty() }
        assertEquals(streamed, replies.map { it.content }, "each agent reply stored once with the full streamed text")
        assertTrue(replies.any { it.meta.interrupted }, "the reply the owner talked over (or stopped) is marked interrupted")
        assertTrue(replies.count { it.meta.interrupted } <= cutOff + stops + 1, "no reply marked interrupted without a reason")
        assertFalse(live.state.value.awaitingReply, "nothing is left waiting for an answer")
        assertEquals("", live.state.value.agentLive)
        assertEquals("local", live.state.value.providers["stack"])
        assertTrue(live.state.value.metrics.isNotEmpty(), "agent metrics arrived")
        core.shutdown()
    }
}
