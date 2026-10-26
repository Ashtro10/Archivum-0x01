package ai.clu.voice

import ai.clu.CluApp
import ai.clu.core.CluCore
import ai.clu.core.config.Settings
import ai.clu.core.live.AgentPhase
import ai.clu.core.live.Link
import ai.clu.core.live.LiveConversation
import ai.clu.core.live.LiveConversation.Companion.ATTR_AGENT_STATE
import ai.clu.core.live.LiveConversation.Companion.ATTR_FINAL
import ai.clu.core.live.LiveConversation.Companion.ATTR_SEGMENT
import ai.clu.core.live.LiveState
import ai.clu.core.livekit.LiveKitException
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.datastream.StreamTextOptions
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** Routes call audio while a Clu conversation is connected: communication mode (hardware echo cancellation) and focus. */
class CluAudioHandler(private val app: CluApp) : AudioHandler {
    override fun start() = app.callAudio.enter()
    override fun stop() = app.callAudio.leave()
}

/**
 * Clu's conversation over LiveKit with the official Android SDK. The phone streams the microphone and typed messages;
 * Clu's agent (server/clu_agent.py, LiveKit Agents) recognises speech, reasons and speaks. All four paths:
 *  voice -> voice and voice -> text (mic on; spoken replies on/off), text -> text and text -> voice (lk.chat; spoken
 *  replies on/off). Transcripts of both sides arrive as `lk.transcription` text streams and are kept in Clu's history.
 *
 * Connection handling: the SDK resumes and re-establishes media on network changes (Reconnecting/Reconnected);
 * if the session is lost anyway, this class reconnects with a fresh token after 1, 2, 4... 30 s while a screen wants
 * the conversation, and asks the token server for the agent again if it has not joined within 15 s.
 */
class LiveKitSession(private val app: CluApp) {
    private val core get() = app.core
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val live = LiveConversation(app.core.conversations, { app.currentConversation() })
    val state: StateFlow<LiveState> get() = live.state

    @Volatile private var room: Room? = null
    private var session: Job? = null
    private var reconnect: Job? = null
    private var agentIdentity: String? = null
    private var historySent = false
    /** A screen wants the conversation (false after the owner closes it): drives automatic reconnects. */
    @Volatile private var wanted = false
    private var wantMic = false
    private var userSpokeDuringReply = false

    fun deviceKey(): String? = app.platform.secrets.get(CluCore.LIVEKIT_DEVICE_KEY)
    fun setDeviceKey(key: String?) { app.platform.secrets.put(CluCore.LIVEKIT_DEVICE_KEY, key?.trim()); core.liveKitTokens.invalidate() }
    fun configured() = core.liveKitTokens.isConfigured()
    /** The server engine is selected (Settings → Conversation engine) and set up. */
    fun enabled() = core.settings.string(Settings.VOICE_MODE) == "livekit"

    // ------------------------------------------------------------------------------------------------ public API
    /** Connects (if needed). [mic]: publish the microphone; spoken replies follow [spokenReplies]. */
    fun open(mic: Boolean, spokenReplies: Boolean) {
        wanted = true; wantMic = mic
        live.onSpokenReplies(spokenReplies)
        if (!configured()) { live.onLink(Link.NOT_CONFIGURED, "Clu's server isn't set up yet (Settings → Connection)."); return }
        val r = room
        if (r != null && session?.isActive == true) {
            scope.launch { applyMic(r); applySpokenReplies() }
            return
        }
        connect(attempt = 0)
    }

    /** The owner closed the conversation: disconnect and stop reconnecting. */
    fun close() {
        wanted = false
        reconnect?.cancel(); reconnect = null
        session?.cancel(); session = null
        dropRoom()
        live.onLink(Link.DISCONNECTED)
    }

    fun setMic(on: Boolean) { wantMic = on; room?.let { r -> scope.launch { applyMic(r) } } ?: run { if (on) open(true, live.state.value.spokenReplies) } }

    fun setSpokenReplies(on: Boolean) { live.onSpokenReplies(on); scope.launch { applySpokenReplies() } }

    /** Sends a typed message; false (with the reason in the state) if it cannot be delivered now. */
    fun sendText(text: String): Boolean {
        val t = text.trim(); if (t.isEmpty()) return false
        val r = room
        if (r == null || live.state.value.link != Link.CONNECTED) {
            live.sendFailed(if (configured()) "Not connected to Clu's server yet." else "Clu's server isn't set up yet (Settings → Connection).")
            if (configured()) open(wantMic, live.state.value.spokenReplies)
            return false
        }
        live.onTypedSent(t)
        scope.launch {
            r.localParticipant.sendText(t, StreamTextOptions(topic = LiveConversation.TOPIC_CHAT))
                .onFailure { live.sendFailed("Couldn't send: ${it.message ?: it::class.simpleName}") }
        }
        return true
    }

    /** Stop button: Clu stops speaking/thinking now. */
    fun interrupt() { scope.launch { rpc(LiveConversation.RPC_INTERRUPT, "{}") } }

    // ------------------------------------------------------------------------------------------------ connection
    /** Each connection attempt gets a fresh Room (handlers are registered once per Room). */
    private fun dropRoom() {
        room?.let { r -> runCatching { r.disconnect() }; runCatching { r.release() } }
        room = null; agentIdentity = null
    }

    private fun connect(attempt: Int) {
        reconnect?.cancel(); reconnect = null
        session?.cancel()
        dropRoom()
        live.onLink(if (attempt == 0) Link.CONNECTING else Link.RECONNECTING, attempt = attempt)
        session = scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val key = deviceKey() ?: throw LiveKitException("No device key configured")
                val roomName = "clu-" + key.substringBefore('.').lowercase()
                val token = withContext(Dispatchers.IO) { core.liveKitTokens.token(roomName, roomName, "Clu (${core.settings.string(Settings.DEVICE_NAME)})") }
                val r = LiveKit.create(app, RoomOptions(adaptiveStream = false, dynacast = false),
                    LiveKitOverrides(audioOptions = AudioOptions(audioHandler = CluAudioHandler(app)))).also { room = it }
                historySent = false
                r.registerTextStreamHandler(LiveConversation.TOPIC_TRANSCRIPTION) { receiver, identity ->
                    scope.launch { onTranscript(r, receiver.info.id, receiver.info.attributes, identity.value, receiver.flow) }
                }
                val events = launch { r.events.collect { ev -> onEvent(r, ev) } }
                r.connect(token.serverUrl, token.token)
                core.diag.perf("livekit_connect_ms", (System.currentTimeMillis() - t0).toDouble())
                live.onLink(Link.CONNECTED)
                r.remoteParticipants.values.forEach { p -> p.attributes[ATTR_AGENT_STATE]?.let { s -> agentIdentity = p.identity?.value; live.onAgentState(s) } }
                applyMic(r)
                launch { watchdog(roomName) }
                launch { sampleLevels(r) }
                events.join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.diag.w("livekit", "connect failed: ${e.message}")
                live.onLink(Link.FAILED, e.message ?: "Couldn't connect to Clu's server.", attempt)
                scheduleReconnect(attempt + 1)
            }
        }
    }

    private fun scheduleReconnect(attempt: Int) {
        if (!wanted || reconnect?.isActive == true) return
        val wait = LiveConversation.backoffMs(attempt - 1)
        reconnect = scope.launch {
            delay(wait)
            reconnect = null
            if (wanted && live.state.value.link != Link.CONNECTED) { core.liveKitTokens.invalidate(); connect(attempt) }
        }
    }

    private fun onEvent(r: Room, ev: RoomEvent) {
        when (ev) {
            is RoomEvent.ParticipantAttributesChanged -> ev.changedAttributes[ATTR_AGENT_STATE]?.let { s ->
                agentIdentity = ev.participant.identity?.value
                live.onAgentState(s)
                if (s == "listening" && !historySent) scope.launch { sendHistory(); applySpokenReplies() }
            }
            is RoomEvent.ParticipantConnected -> ev.participant.attributes[ATTR_AGENT_STATE]?.let { s -> agentIdentity = ev.participant.identity?.value; live.onAgentState(s) }
            is RoomEvent.ParticipantDisconnected -> if (ev.participant.identity?.value == agentIdentity) { agentIdentity = null; live.onAgentState(null) }
            is RoomEvent.Reconnecting -> live.onLink(Link.RECONNECTING)
            is RoomEvent.Reconnected -> { live.onLink(Link.CONNECTED); scope.launch { applyMic(r) } }
            is RoomEvent.DataReceived -> if (ev.topic == LiveConversation.TOPIC_METRICS) {
                val json = String(ev.data); live.onMetrics(json)
                live.state.value.metrics.forEach { (k, v) -> core.diag.perf("lk_$k", v) }
            }
            is RoomEvent.Disconnected -> {
                core.diag.i("livekit", "disconnected: ${ev.reason}")
                live.onLink(Link.DISCONNECTED, if (wanted) "Connection lost – reconnecting…" else null)
                session?.cancel()
                if (wanted) scheduleReconnect(1)
            }
            else -> {}
        }
    }

    private suspend fun onTranscript(r: Room, streamId: String, attrs: Map<String, String>, sender: String, flow: kotlinx.coroutines.flow.Flow<String>) {
        val fromOwner = sender == r.localParticipant.identity?.value
        if (fromOwner) {
            val text = StringBuilder(); flow.collect { text.append(it) }
            if (live.state.value.agent == AgentPhase.SPEAKING) userSpokeDuringReply = true
            live.onUserTranscript(attrs[ATTR_SEGMENT] ?: streamId, text.toString(), attrs[ATTR_FINAL] == "true")
        } else {
            userSpokeDuringReply = false
            flow.collect { live.onAgentChunk(streamId, it) }
            live.onAgentStreamEnd(streamId, interrupted = userSpokeDuringReply)
        }
    }

    private suspend fun applyMic(r: Room) {
        runCatching { r.localParticipant.setMicrophoneEnabled(wantMic) }
            .onSuccess { live.onMic(wantMic) }
            .onFailure { live.onError("Microphone unavailable: ${it.message}"); live.onMic(false) }
    }

    private suspend fun applySpokenReplies() {
        rpc(LiveConversation.RPC_AUDIO_OUTPUT, buildJsonObject { put("enabled", live.state.value.spokenReplies) }.toString())
    }

    private suspend fun sendHistory() {
        historySent = true
        val msgs = buildJsonObject { putJsonArray("messages") { live.history().forEach { (role, text) -> addJsonObject { put("role", role); put("text", text) } } } }
        rpc(LiveConversation.RPC_HISTORY, msgs.toString())
    }

    private suspend fun rpc(method: String, payload: String): String? {
        val r = room ?: return null
        val agent = agentIdentity ?: return null
        return runCatching { r.localParticipant.performRpc(Participant.Identity(agent), method, payload) }
            .onFailure { core.diag.w("livekit", "rpc $method failed: ${it.message}") }.getOrNull()
    }

    /** No agent 15 s after connecting: ask the token server again (it dispatches one) and say what is wrong. */
    private suspend fun watchdog(roomName: String) {
        var waited = 0
        while (currentCoroutineContext().isActive) {
            delay(1000); waited++
            live.checkReplyTimeout(30_000)
            if (live.state.value.agent != AgentPhase.ABSENT) { waited = 0; continue }
            if (waited == 15 || waited % 30 == 0) {
                live.onError("Clu's agent hasn't joined. Is clu_agent.py running on the server?")
                runCatching { core.liveKitTokens.invalidate(); withContext(Dispatchers.IO) { core.liveKitTokens.token(roomName, roomName, "Clu") } }
            }
        }
    }

    /** Real audio levels for the HUD (~15 Hz): the agent's playback and the owner's microphone. */
    private suspend fun sampleLevels(r: Room) {
        while (currentCoroutineContext().isActive) {
            val agent = agentIdentity?.let { id -> r.remoteParticipants.values.firstOrNull { it.identity?.value == id } }
            live.onLevels(if (wantMic) r.localParticipant.audioLevel else 0f, agent?.audioLevel ?: 0f)
            delay(66)
        }
    }
}
