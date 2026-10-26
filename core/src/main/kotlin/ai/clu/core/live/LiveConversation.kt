package ai.clu.core.live

import ai.clu.core.conversation.ConversationRepository
import ai.clu.core.conversation.MessageMeta
import ai.clu.core.conversation.Role
import ai.clu.core.util.CluJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Connection to Clu's conversation server (LiveKit room + agent). */
enum class Link { NOT_CONFIGURED, DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

/** What the agent is doing, from its `lk.agent.state` attribute (set by the LiveKit Agents framework). */
enum class AgentPhase { ABSENT, INITIALIZING, LISTENING, THINKING, SPEAKING;
    companion object {
        fun from(raw: String?): AgentPhase = when (raw) {
            "initializing" -> INITIALIZING; "listening" -> LISTENING; "thinking" -> THINKING; "speaking" -> SPEAKING; else -> ABSENT
        }
    }
}

data class LiveState(
    val link: Link = Link.DISCONNECTED,
    val agent: AgentPhase = AgentPhase.ABSENT,
    /** The microphone track is published and unmuted. */
    val micOn: Boolean = false,
    /** Replies are spoken (agent audio output on); text replies are always shown. */
    val spokenReplies: Boolean = true,
    /** Words being recognised right now (interim transcript). */
    val userLive: String = "",
    /** The agent reply being streamed right now (empty when none). */
    val agentLive: String = "",
    /** A typed message is waiting for its reply. */
    val awaitingReply: Boolean = false,
    val error: String? = null,
    /** Last per-turn latency report from the agent (data topic "clu.metrics"). */
    val metrics: Map<String, Double> = emptyMap(),
    /** 0..1 levels sampled from the audio tracks (HUD animation follows real audio only). */
    val micLevel: Float = 0f,
    val agentLevel: Float = 0f,
    val reconnectAttempt: Int = 0,
) {
    /** Thinking is shown only when it is really happening: the agent says so, or a typed message is unanswered. */
    val thinking get() = agent == AgentPhase.THINKING || (awaitingReply && agentLive.isEmpty() && agent != AgentPhase.SPEAKING)
    val ready get() = link == Link.CONNECTED && agent != AgentPhase.ABSENT && agent != AgentPhase.INITIALIZING
}

/**
 * The live conversation with Clu's agent, independent of the LiveKit SDK (the app feeds it SDK events; tests feed it
 * directly). Keeps one state for both screens and stores finished messages in the conversation history:
 *  - the owner's speech: final transcripts (interim ones are only shown live);
 *  - typed messages: stored when sent;
 *  - Clu's replies: stored when the reply's transcript stream ends, marked interrupted if the owner cut it short.
 * Every typed message ends in a reply, an error or a disconnect; nothing waits forever.
 */
class LiveConversation(
    private val conversations: ConversationRepository,
    private val conversationId: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> get() = _state
    private val agentStreams = LinkedHashMap<String, StringBuilder>()
    private val userSegments = HashMap<String, String>()
    private var awaitingSince = 0L

    // ------------------------------------------------------------------------------------------------ link & agent
    @Synchronized fun onLink(link: Link, error: String? = null, attempt: Int = 0) {
        _state.update { s ->
            val down = link != Link.CONNECTED && link != Link.RECONNECTING
            s.copy(link = link, error = error ?: if (link == Link.CONNECTED) null else s.error, reconnectAttempt = attempt,
                agent = if (down) AgentPhase.ABSENT else s.agent, micOn = if (down) false else s.micOn,
                userLive = if (down) "" else s.userLive, awaitingReply = if (down) false else s.awaitingReply)
        }
        if (link == Link.DISCONNECTED || link == Link.FAILED) endAgentStreams(interrupted = true)
    }

    @Synchronized fun onAgentState(raw: String?) {
        val phase = AgentPhase.from(raw)
        _state.update { it.copy(agent = phase) }
    }

    fun onMic(on: Boolean) = _state.update { it.copy(micOn = on) }
    fun onSpokenReplies(on: Boolean) = _state.update { it.copy(spokenReplies = on) }
    fun onLevels(mic: Float, agent: Float) = _state.update { it.copy(micLevel = mic.coerceIn(0f, 1f), agentLevel = agent.coerceIn(0f, 1f)) }
    fun onError(message: String?) = _state.update { it.copy(error = message) }

    // ------------------------------------------------------------------------------------------------ transcripts
    /** The owner's speech (`lk.transcription` from the owner's audio track). */
    @Synchronized fun onUserTranscript(segmentId: String, text: String, final: Boolean) {
        val t = text.trim()
        if (!final) { _state.update { it.copy(userLive = t) }; return }
        if (t.isNotEmpty() && userSegments[segmentId] != t) {
            userSegments[segmentId] = t
            conversations.addMessage(conversationId(), Role.USER, t, MessageMeta(source = "voice"))
        }
        _state.update { it.copy(userLive = "") }
    }

    /** A chunk of Clu's reply (`lk.transcription` stream from the agent). */
    @Synchronized fun onAgentChunk(streamId: String, chunk: String) {
        val b = agentStreams.getOrPut(streamId) { StringBuilder() }
        b.append(chunk)
        _state.update { it.copy(agentLive = agentStreams.values.joinToString(" ") { s -> s.toString().trim() }.trim(), awaitingReply = false) }
    }

    /** The reply stream closed: [interrupted] when the owner talked over it (or the connection dropped). */
    @Synchronized fun onAgentStreamEnd(streamId: String, interrupted: Boolean = false) {
        val text = agentStreams.remove(streamId)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) conversations.addMessage(conversationId(), Role.ASSISTANT, text, MessageMeta(source = "livekit", interrupted = interrupted))
        _state.update { it.copy(agentLive = agentStreams.values.joinToString(" ") { s -> s.toString().trim() }.trim()) }
    }

    private fun endAgentStreams(interrupted: Boolean) { agentStreams.keys.toList().forEach { onAgentStreamEnd(it, interrupted) } }

    // ------------------------------------------------------------------------------------------------ typed messages
    /** A typed message was handed to the agent: stored now, and the screen shows that a reply is due. */
    @Synchronized fun onTypedSent(text: String) {
        conversations.addMessage(conversationId(), Role.USER, text.trim(), MessageMeta(source = "typed"))
        awaitingSince = now()
        _state.update { it.copy(awaitingReply = true, error = null) }
    }

    /** No reply within [timeoutMs] of a typed message: a clear error instead of waiting forever. */
    @Synchronized fun checkReplyTimeout(timeoutMs: Long = 30_000): Boolean {
        val s = _state.value
        if (!s.awaitingReply || s.agentLive.isNotEmpty() || s.agent == AgentPhase.THINKING || now() - awaitingSince < timeoutMs) return false
        _state.update { it.copy(awaitingReply = false, error = "Clu didn't answer. Check the connection and try again.") }
        return true
    }

    fun sendFailed(message: String) = _state.update { it.copy(awaitingReply = false, error = message) }

    // ------------------------------------------------------------------------------------------------ metrics
    fun onMetrics(json: String) {
        val m = runCatching { CluJson.parseToJsonElement(json).jsonObject.mapNotNull { (k, v) -> v.jsonPrimitive.doubleOrNull?.let { k to it } }.toMap() }.getOrNull() ?: return
        _state.update { it.copy(metrics = m) }
    }

    /** The last [max] messages of the current conversation, for the agent's context after a (re)connect. */
    fun history(max: Int = 24): List<Pair<String, String>> =
        conversations.messages(conversationId()).filter { it.role == Role.USER || it.role == Role.ASSISTANT }.takeLast(max)
            .map { (if (it.role == Role.USER) "user" else "assistant") to it.content }

    companion object {
        const val TOPIC_CHAT = "lk.chat"
        const val TOPIC_TRANSCRIPTION = "lk.transcription"
        const val TOPIC_METRICS = "clu.metrics"
        const val ATTR_AGENT_STATE = "lk.agent.state"
        const val ATTR_FINAL = "lk.transcription_final"
        const val ATTR_TRACK = "lk.transcribed_track_id"
        const val ATTR_SEGMENT = "lk.segment_id"
        const val RPC_AUDIO_OUTPUT = "clu.set_audio_output"
        const val RPC_INTERRUPT = "clu.interrupt"
        const val RPC_HISTORY = "clu.load_history"

        /** Reconnect delay after an unexpected disconnect: 1, 2, 4, 8, 16, then 30 s. */
        fun backoffMs(attempt: Int): Long = minOf(30_000L, 1000L shl minOf(attempt, 5).coerceAtLeast(0))
    }
}
