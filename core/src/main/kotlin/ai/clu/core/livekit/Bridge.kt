package ai.clu.core.livekit

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.CluAgent
import ai.clu.core.agent.TurnOrigin
import ai.clu.core.agent.TurnRequest
import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.security.Actor
import ai.clu.core.util.CluJson
import ai.clu.core.voice.SentenceChunker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/**
 * Message protocol between Clu (on the device) and the Clu voice agent (on the owner's LiveKit server), carried as
 * reliable LiveKit data packets. JSON, version 1.
 *
 * agent -> Clu:  clu.transcript {turn, text, asr_ms?} · clu.interrupt {turn} · clu.spoken {turn}
 *                clu.agent_state {state} · clu.partial {text}           (the last two are for the UI only)
 * Clu -> agent:  clu.reply {turn, seq, text, final, end_session}
 */
object VoiceProtocol {
    const val VERSION = 1
    const val TRANSCRIPT = "clu.transcript"
    const val INTERRUPT = "clu.interrupt"
    const val SPOKEN = "clu.spoken"
    const val AGENT_STATE = "clu.agent_state"
    const val PARTIAL = "clu.partial"
    const val REPLY = "clu.reply"
    const val MAX_TEXT = 2_000

    fun reply(turn: Long, seq: Int, text: String, final: Boolean, endSession: Boolean = false) = buildJsonObject {
        put("v", VERSION); put("turn", turn); put("seq", seq); put("text", text); put("final", final); put("end_session", endSession)
    }.toString()
}

sealed class BridgeEvent {
    data class Heard(val turn: Long, val text: String) : BridgeEvent()
    data class Replying(val turn: Long, val text: String) : BridgeEvent()
    data class AgentState(val state: String) : BridgeEvent()
    data class Partial(val text: String) : BridgeEvent()
    data class Interrupted(val turn: Long) : BridgeEvent()
    /** The agent finished speaking a reply that ended the session ("End of the line"). */
    data class SessionEnded(val turn: Long) : BridgeEvent()
}

/**
 * Runs each transcript from the voice agent through Clu's own agent - local model, memory, tools and the same voice
 * permissions as an offline voice session - and streams the reply back sentence by sentence so the agent can start
 * speaking before generation finishes. A new transcript or an interrupt cancels the reply in progress.
 */
class RemoteVoiceBridge(
    private val agent: CluAgent,
    private val scope: CoroutineScope,
    private val conversationId: () -> String,
    private val actor: () -> Actor?,
    private val send: (topic: String, payload: String) -> Unit,
    private val events: (BridgeEvent) -> Unit = {},
    private val diag: DiagnosticsLog? = null,
) {
    @Volatile private var currentTurn = -1L
    @Volatile private var job: Job? = null
    @Volatile private var endingTurn = -1L

    val busy get() = job?.isActive == true

    /** Handles one data packet from the agent. Malformed input is logged and ignored, never thrown. */
    fun onData(topic: String, payload: String) {
        val msg = runCatching { CluJson.parseToJsonElement(payload).jsonObject }.getOrElse {
            diag?.w("livekit", "ignored malformed '$topic' message"); return
        }
        val turn = msg["turn"]?.jsonPrimitive?.longOrNull ?: -1L
        when (topic) {
            VoiceProtocol.TRANSCRIPT -> {
                val text = msg["text"]?.jsonPrimitive?.contentOrNull?.trim()?.take(VoiceProtocol.MAX_TEXT).orEmpty()
                if (text.isEmpty()) return
                val asrMs = msg["asr_ms"]?.jsonPrimitive?.doubleOrNull
                respond(turn, text, asrMs)
            }
            VoiceProtocol.INTERRUPT -> if (turn == currentTurn || turn < 0) interrupt(reported = true)
            VoiceProtocol.SPOKEN -> if (turn >= 0 && turn == endingTurn) events(BridgeEvent.SessionEnded(turn))
            VoiceProtocol.AGENT_STATE -> msg["state"]?.jsonPrimitive?.contentOrNull?.let { events(BridgeEvent.AgentState(it)) }
            VoiceProtocol.PARTIAL -> msg["text"]?.jsonPrimitive?.contentOrNull?.let { events(BridgeEvent.Partial(it.take(VoiceProtocol.MAX_TEXT))) }
            else -> diag?.d("livekit", "ignored topic '$topic'")
        }
    }

    /**
     * Stops the reply in progress. [reported] = the agent says the owner talked over Clu: always surfaced as an event,
     * even when generation had already finished and only playback was stopped (on the agent's side).
     */
    fun interrupt(reported: Boolean = false) {
        val t = currentTurn
        val generating = job?.isActive == true
        if (generating) { agent.cancel(TurnOrigin.LIVEKIT); job?.cancel() }
        if (generating || reported) {
            diag?.i("livekit", "turn $t interrupted" + if (generating) " during generation" else " during playback")
            events(BridgeEvent.Interrupted(t))
        }
    }

    private fun respond(turn: Long, text: String, asrMs: Double?) {
        // A newer turn supersedes the reply in progress.
        if (job?.isActive == true) { agent.cancel(TurnOrigin.LIVEKIT); job?.cancel() }
        currentTurn = turn
        events(BridgeEvent.Heard(turn, text))
        val who = actor() ?: run {
            diag?.i("livekit", "ignored turn $turn: voice input not accepted from unknown speakers")
            send(VoiceProtocol.REPLY, VoiceProtocol.reply(turn, 0, "", final = true)); return
        }
        job = scope.launch {
            var chunker = SentenceChunker()
            var seq = 0
            var sentAny = false
            var endSession = false
            val out = StringBuilder()
            fun emit(sentence: String) {
                if (sentence.isBlank()) return
                send(VoiceProtocol.REPLY, VoiceProtocol.reply(turn, seq++, sentence, final = false)); sentAny = true
                out.append(sentence).append(' ')
            }
            val stats = asrMs?.let { mapOf("asr_ms" to it) } ?: emptyMap()
            agent.respond(TurnRequest(conversationId(), text, who, viaVoice = true, preStats = stats, origin = TurnOrigin.LIVEKIT)).collect { ev ->
                when (ev) {
                    is AgentEvent.Text -> chunker.push(ev.delta).forEach(::emit)
                    is AgentEvent.ResetText -> chunker = SentenceChunker()
                    is AgentEvent.Done -> {
                        chunker.flush().forEach(::emit)
                        if (!sentAny && ev.text.isNotBlank()) (chunker.push(ev.text) + chunker.flush()).forEach(::emit)
                    }
                    is AgentEvent.Control -> if (ev.command == "end_session") endSession = true
                    is AgentEvent.Error -> emit("Something went wrong.")
                    else -> {}
                }
            }
            if (endSession) endingTurn = turn
            send(VoiceProtocol.REPLY, VoiceProtocol.reply(turn, seq, "", final = true, endSession = endSession))
            events(BridgeEvent.Replying(turn, out.toString().trim()))
        }
    }
}
