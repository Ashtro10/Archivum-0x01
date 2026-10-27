package ai.clu.core.live

import ai.clu.core.conversation.ConversationRepository
import ai.clu.core.conversation.MessageMeta
import ai.clu.core.conversation.Role
import ai.clu.core.util.CluJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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
    /** Which providers the agent runs (its `clu.*` attributes: stack, stt, llm, tts, turn). */
    val providers: Map<String, String> = emptyMap(),
    /** Latency measured on this device (ms): text_to_first_text, text_to_speaking, connect, agent_ready. */
    val appMetrics: Map<String, Long> = emptyMap(),
) {
    /** Thinking is shown only when it is really happening: the agent says so, or a typed message is unanswered. */
    val thinking get() = agent == AgentPhase.THINKING || (awaitingReply && agentLive.isEmpty() && agent != AgentPhase.SPEAKING)
    val ready get() = link == Link.CONNECTED && agent != AgentPhase.ABSENT && agent != AgentPhase.INITIALIZING
}

/** One diagnostic event. [detail] never contains conversation content, keys or tokens. */
data class LiveEvent(val atMs: Long, val kind: String, val detail: String = "")

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
    private var thinkingSince = 0L
    private var firstTextPending = false
    /** A typed message was sent and the agent has not reacted yet (no thinking, speaking or reply text). */
    private var ackPending = false
    private var speakingPending = false
    /** The owner spoke over the reply being spoken or streamed, or pressed stop: that reply ends as interrupted. */
    private var ownerSpokeDuringReply = false
    private val events = ArrayDeque<LiveEvent>()

    // ------------------------------------------------------------------------------------------------ diagnostics
    /** Records a pipeline event for Diagnostics (bounded; [detail] must not contain conversation content). */
    @Synchronized fun log(kind: String, detail: String = "") {
        events.addLast(LiveEvent(now(), kind, detail.take(240)))
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    @Synchronized fun events(): List<LiveEvent> = events.toList()

    fun onAppMetric(name: String, ms: Long) { _state.update { it.copy(appMetrics = it.appMetrics + (name to ms)) }; log("metric", "$name=$ms ms") }

    fun onProviders(attrs: Map<String, String>) {
        val p = attrs.filterKeys { it.startsWith("clu.") }.mapKeys { it.key.removePrefix("clu.") }
        if (p.isNotEmpty() && p != _state.value.providers) { _state.update { it.copy(providers = it.providers + p) }; log("providers", p.entries.joinToString { "${it.key}=${it.value}" }) }
    }

    /**
     * An event from the agent (data topic "clu.events"): a speech-recognition, model or voice failure, or the agent
     * session ending. Shown as an actionable error; a typed message waiting for an answer stops waiting.
     */
    @Synchronized fun onAgentEvent(json: String) {
        val o = runCatching { CluJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        val type = o["type"]?.jsonPrimitive?.contentOrNull ?: return
        val component = o["component"]?.jsonPrimitive?.contentOrNull ?: "agent"
        val message = o["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val recoverable = o["recoverable"]?.jsonPrimitive?.booleanOrNull ?: false
        log("agent_$type", "$component${if (recoverable) " (retrying)" else ""} $message".trim())
        val what = when (component) {
            "stt" -> "speech recognition"; "llm" -> "language model"; "tts" -> "voice (speech synthesis)"; else -> "agent"
        }
        val error = when {
            type == "session_closed" && o["reason"]?.jsonPrimitive?.contentOrNull == "error" ->
                "Clu's agent stopped after repeated $what failures. Check the server; Clu will reconnect."
            type == "error" && recoverable -> null // the framework retries; report only if it gives up
            type == "error" -> "Clu's $what failed on the server: ${message.ifBlank { "no details" }}"
            else -> null
        } ?: return
        _state.update { it.copy(error = error, awaitingReply = false) }
    }

    // ------------------------------------------------------------------------------------------------ link & agent
    @Synchronized fun onLink(link: Link, error: String? = null, attempt: Int = 0) {
        _state.update { s ->
            val down = link != Link.CONNECTED && link != Link.RECONNECTING
            s.copy(link = link, error = error ?: if (link == Link.CONNECTED) null else s.error, reconnectAttempt = attempt,
                agent = if (down) AgentPhase.ABSENT else s.agent, micOn = if (down) false else s.micOn,
                userLive = if (down) "" else s.userLive, awaitingReply = if (down) false else s.awaitingReply)
        }
        if (link == Link.DISCONNECTED || link == Link.FAILED) endAgentStreams(interrupted = true)
        log("link", link.name.lowercase() + (error?.let { ": $it" } ?: "") + (if (attempt > 0) " (attempt $attempt)" else ""))
    }

    @Synchronized fun onAgentState(raw: String?) {
        val phase = AgentPhase.from(raw)
        val before = _state.value.agent
        if (phase == before) return
        if (phase == AgentPhase.THINKING) thinkingSince = now()
        if (phase == AgentPhase.THINKING || phase == AgentPhase.SPEAKING) ackPending = false
        if (phase == AgentPhase.SPEAKING && speakingPending) { speakingPending = false; onAppMetric("text_to_speaking", now() - awaitingSince) }
        _state.update { it.copy(agent = phase, error = if (phase == AgentPhase.LISTENING && before == AgentPhase.ABSENT) null else it.error) }
        log("agent_state", phase.name.lowercase())
    }

    fun onMic(on: Boolean) = _state.update { it.copy(micOn = on) }
    fun onSpokenReplies(on: Boolean) = _state.update { it.copy(spokenReplies = on) }
    fun onLevels(mic: Float, agent: Float) = _state.update { it.copy(micLevel = mic.coerceIn(0f, 1f), agentLevel = agent.coerceIn(0f, 1f)) }
    fun onError(message: String?) { _state.update { it.copy(error = message) }; if (message != null) log("error", message) }

    // ------------------------------------------------------------------------------------------------ transcripts
    /** The owner's speech (`lk.transcription` from the owner's audio track). */
    @Synchronized fun onUserTranscript(segmentId: String, text: String, final: Boolean) {
        val t = text.trim()
        if (t.isNotEmpty() && (_state.value.agent == AgentPhase.SPEAKING || agentStreams.isNotEmpty())) ownerSpokeDuringReply = true
        if (!final) { _state.update { it.copy(userLive = t) }; return }
        if (t.isNotEmpty() && userSegments[segmentId] != t) {
            userSegments[segmentId] = t
            conversations.addMessage(conversationId(), Role.USER, t, MessageMeta(source = "voice"))
        }
        _state.update { it.copy(userLive = "") }
    }

    /** A chunk of Clu's reply (`lk.transcription` stream from the agent). */
    @Synchronized fun onAgentChunk(streamId: String, chunk: String) {
        if (agentStreams.isEmpty() && streamId !in agentStreams) ownerSpokeDuringReply = false
        ackPending = false
        if (firstTextPending) { firstTextPending = false; onAppMetric("text_to_first_text", now() - awaitingSince) }
        val b = agentStreams.getOrPut(streamId) { StringBuilder() }
        b.append(chunk)
        _state.update { it.copy(agentLive = agentStreams.values.joinToString(" ") { s -> s.toString().trim() }.trim(), awaitingReply = false) }
    }

    /** The reply stream closed: interrupted when the owner talked over it (or [interrupted]: the connection dropped). */
    @Synchronized fun onAgentStreamEnd(streamId: String, interrupted: Boolean = ownerSpokeDuringReply) {
        val text = agentStreams.remove(streamId)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) conversations.addMessage(conversationId(), Role.ASSISTANT, text, MessageMeta(source = "livekit", interrupted = interrupted))
        _state.update { it.copy(agentLive = agentStreams.values.joinToString(" ") { s -> s.toString().trim() }.trim()) }
    }

    /** The owner pressed stop: the reply being spoken or streamed ends as interrupted. */
    @Synchronized fun onStopRequested() {
        if (_state.value.agent == AgentPhase.SPEAKING || _state.value.agent == AgentPhase.THINKING || agentStreams.isNotEmpty()) ownerSpokeDuringReply = true
        _state.update { it.copy(awaitingReply = false) }
        log("stop_requested")
    }

    private fun endAgentStreams(interrupted: Boolean) { agentStreams.keys.toList().forEach { onAgentStreamEnd(it, interrupted) } }

    // ------------------------------------------------------------------------------------------------ typed messages
    /** A typed message was handed to the agent: stored now, and the screen shows that a reply is due. */
    @Synchronized fun onTypedSent(text: String) {
        conversations.addMessage(conversationId(), Role.USER, text.trim(), MessageMeta(source = "typed"))
        awaitingSince = now(); firstTextPending = true; ackPending = true; speakingPending = _state.value.spokenReplies
        _state.update { it.copy(awaitingReply = true, error = null) }
        log("typed_sent", "${text.trim().length} chars")
    }

    /** No reply within [timeoutMs] of a typed message: a clear error instead of waiting forever. */
    @Synchronized fun checkReplyTimeout(timeoutMs: Long = 30_000, thinkingTimeoutMs: Long = 45_000): Boolean {
        val s = _state.value
        if (s.agent == AgentPhase.THINKING && s.agentLive.isEmpty() && now() - thinkingSince >= thinkingTimeoutMs && s.error == null) {
            _state.update { it.copy(awaitingReply = false, error = "Clu has been thinking for ${thinkingTimeoutMs / 1000} s without an answer. The language model on the server may be stuck or overloaded.") }
            log("timeout", "agent thinking > ${thinkingTimeoutMs / 1000} s")
            return true
        }
        if (!s.awaitingReply || s.agentLive.isNotEmpty() || s.agent == AgentPhase.THINKING || now() - awaitingSince < timeoutMs) return false
        _state.update { it.copy(awaitingReply = false, error = "Clu didn't answer within ${timeoutMs / 1000} s. Check the connection and Diagnostics, then try again.") }
        log("timeout", "no reply to typed message within ${timeoutMs / 1000} s")
        return true
    }

    /**
     * True while a typed message is still unacknowledged by the agent. The agent framework drops a chat message that
     * arrives before it has processed this phone (re)joining the room (measured: a message sent ~60 ms after a rejoin
     * was ignored); the client resends once when this stays true for a few seconds.
     */
    @Synchronized fun typedUnacknowledged(): Boolean = ackPending && _state.value.awaitingReply

    fun sendFailed(message: String) { _state.update { it.copy(awaitingReply = false, error = message) }; log("send_failed", message) }

    // ------------------------------------------------------------------------------------------------ metrics
    fun onMetrics(json: String) {
        val m = runCatching { CluJson.parseToJsonElement(json).jsonObject.mapNotNull { (k, v) -> v.jsonPrimitive.doubleOrNull?.let { k to it } }.toMap() }.getOrNull() ?: return
        _state.update { it.copy(metrics = m) }
        log("agent_metrics", m.entries.joinToString { "${it.key}=${it.value.toLong()}" })
    }

    /** The last [max] messages of the current conversation, for the agent's context after a (re)connect. */
    fun history(max: Int = 24): List<Pair<String, String>> =
        conversations.messages(conversationId()).filter { it.role == Role.USER || it.role == Role.ASSISTANT }.takeLast(max)
            .map { (if (it.role == Role.USER) "user" else "assistant") to it.content }

    companion object {
        const val TOPIC_CHAT = "lk.chat"
        const val TOPIC_TRANSCRIPTION = "lk.transcription"
        const val TOPIC_METRICS = "clu.metrics"
        const val TOPIC_EVENTS = "clu.events"
        const val MAX_EVENTS = 200
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
