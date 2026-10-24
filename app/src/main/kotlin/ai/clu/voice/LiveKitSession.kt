package ai.clu.voice

import ai.clu.CluApp
import ai.clu.core.CluCore
import ai.clu.core.config.Settings
import ai.clu.core.livekit.*
import ai.clu.livekit.LiveKitRoom
import ai.clu.livekit.PeerState
import ai.clu.livekit.RoomEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LiveKitUiState(
    val status: Status = Status.IDLE,
    val agentState: String = "",
    val partial: String = "",
    val lastHeard: String = "",
    val lastReply: String = "",
    val message: String = "",
    val connectMs: Long? = null,
) {
    enum class Status { IDLE, CONNECTING, CONNECTED, FAILED }
}

/**
 * The optional LiveKit voice session (Settings → LiveKit voice). Clu's brain stays here: the agent on the owner's
 * server sends transcripts, [RemoteVoiceBridge] answers them with the local agent, model, memory and tools.
 * The offline [VoiceSession] keeps the wake word, clap and mic button; on activation it hands the microphone over
 * to this session, and gets it back when the session ends.
 */
class LiveKitSession(private val app: CluApp) {
    private val core get() = app.core
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(LiveKitUiState())
    val state: StateFlow<LiveKitUiState> get() = _state
    @Volatile private var room: LiveKitRoom? = null
    @Volatile private var rtc: AndroidRtcFactory? = null
    @Volatile private var bridge: RemoteVoiceBridge? = null
    @Volatile private var agentIdentity: String? = null
    @Volatile private var resumeWake = false
    private val audio by lazy { app.getSystemService(android.media.AudioManager::class.java) }
    @Volatile private var savedAudioMode: Int? = null

    /** Communication mode (hardware echo cancellation) + loudspeaker unless a headset is connected; restored afterwards. */
    private fun enterCallAudio() {
        runCatching {
            savedAudioMode = audio.mode
            audio.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            val devices = audio.availableCommunicationDevices
            val headset = devices.firstOrNull { it.type in HEADSET_TYPES }
            (headset ?: devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })?.let { audio.setCommunicationDevice(it) }
        }.onFailure { core.diag.w("livekit", "audio routing: ${it.message}") }
    }

    private fun leaveCallAudio() {
        runCatching { audio.clearCommunicationDevice(); savedAudioMode?.let { audio.mode = it } }
        savedAudioMode = null
    }
    val active get() = room?.closed == false

    fun deviceKey(): String? = app.platform.secrets.get(CluCore.LIVEKIT_DEVICE_KEY)
    fun setDeviceKey(key: String?) { app.platform.secrets.put(CluCore.LIVEKIT_DEVICE_KEY, key?.trim()); core.liveKitTokens.invalidate() }

    /** Which path an activation should take now (never throws: a refused fallback is reported as OFFLINE + reason). */
    fun decide(): PathDecision = runCatching {
        VoiceModePolicy.select(core.settings, app.platform.isOnline(), core.liveKitTokens.isConfigured())
    }.getOrElse { PathDecision(ConversationPath.OFFLINE, it.message ?: "LiveKit unavailable") }

    /** Starts a LiveKit conversation. [resumeWakeAfter]: return to offline wake-word listening when it ends. */
    fun start(resumeWakeAfter: Boolean) {
        if (active || _state.value.status == LiveKitUiState.Status.CONNECTING) return
        resumeWake = resumeWakeAfter
        _state.value = LiveKitUiState(status = LiveKitUiState.Status.CONNECTING, message = "Connecting to your LiveKit server…")
        scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val key = deviceKey() ?: throw LiveKitException("No device key configured")
                val room = "clu-" + key.substringBefore('.').lowercase()
                val token = core.liveKitTokens.token(room, room, "Clu (${core.settings.string(Settings.DEVICE_NAME)})")
                connect(token, t0)
            } catch (e: Exception) {
                onFailure(e.message ?: e::class.simpleName ?: "error")
            }
        }
    }

    private suspend fun connect(token: RoomToken, t0: Long) {
        val f = AndroidRtcFactory(app)
        rtc = f
        lateinit var lk: LiveKitRoom
        val b = RemoteVoiceBridge(core.agent, scope, { app.currentConversation() },
            // LiveKit cannot identify the speaker. If the owner relies on speaker verification, every LiveKit voice is
            // treated as an unknown speaker ("Unknown voices" policy); otherwise as offline voice without verification.
            actor = {
                val verifying = core.settings.bool(Settings.SPEAKER_VERIFY) && core.people.voiceprints(VoicePacks.SPEAKER.id).isNotEmpty()
                core.auth.actorForSpeaker(null, speakerIdActive = verifying)
            },
            send = { topic, payload -> lk.sendData(topic, payload.toByteArray(), listOfNotNull(agentIdentity)) },
            events = ::onBridge, diag = core.diag)
        bridge = b
        lk = LiveKitRoom(f, ::onRoom)
        room = lk
        lk.connect(token.serverUrl, token.token)
        enterCallAudio()
        lk.publishMicrophone()
        val ms = System.currentTimeMillis() - t0
        core.diag.perf("livekit_connect_ms", ms.toDouble())
        core.diag.i("livekit", "connected to room ${token.room}")
        _state.value = _state.value.copy(status = LiveKitUiState.Status.CONNECTED, message = "Connected – waiting for the voice agent", connectMs = ms)
    }

    private fun onRoom(ev: RoomEvent) {
        when (ev) {
            is RoomEvent.ParticipantJoined -> if (ev.participant.isAgent) {
                agentIdentity = ev.participant.identity
                _state.value = _state.value.copy(message = "Voice agent ready")
            }
            is RoomEvent.ParticipantLeft -> if (ev.participant.identity == agentIdentity) {
                agentIdentity = null
                _state.value = _state.value.copy(message = "Voice agent left")
            }
            // Only the agent may drive Clu; data from any other participant is ignored.
            is RoomEvent.Data -> if (ev.from != null && ev.from == agentIdentity) bridge?.onData(ev.topic ?: "", String(ev.payload))
            is RoomEvent.MediaState -> if (ev.publisher == PeerState.CONNECTED) core.diag.d("livekit", "media connected")
            is RoomEvent.Disconnected -> scope.launch { finished("LiveKit session ended: ${ev.reason}") }
            else -> {}
        }
    }

    private fun onBridge(ev: BridgeEvent) {
        when (ev) {
            is BridgeEvent.Heard -> _state.value = _state.value.copy(lastHeard = ev.text, partial = "")
            is BridgeEvent.Replying -> _state.value = _state.value.copy(lastReply = ev.text)
            is BridgeEvent.AgentState -> _state.value = _state.value.copy(agentState = ev.state)
            is BridgeEvent.Partial -> _state.value = _state.value.copy(partial = ev.text)
            is BridgeEvent.Interrupted -> core.diag.i("livekit", "interrupted turn ${ev.turn}")
            is BridgeEvent.SessionEnded -> stop()
        }
    }

    private fun onFailure(reason: String) {
        core.diag.w("livekit", "session failed: $reason")
        cleanup()
        if (core.settings.bool(Settings.LK_FALLBACK)) {
            _state.value = LiveKitUiState(status = LiveKitUiState.Status.FAILED, message = "$reason – continuing offline")
            app.voice.startOfflineAfterFallback()
        } else {
            _state.value = LiveKitUiState(status = LiveKitUiState.Status.FAILED, message = reason)
            if (resumeWake) app.voice.start(wakeMode = true)
        }
    }

    private fun finished(message: String) {
        cleanup()
        _state.value = _state.value.copy(status = LiveKitUiState.Status.IDLE, agentState = "", partial = "", message = message)
        if (resumeWake && core.settings.bool(Settings.WAKE_ENABLED)) app.voice.start(wakeMode = true)
    }

    companion object {
        private val HEADSET_TYPES = setOf(android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    fun stop() {
        val r = room ?: return
        r.disconnect() // Disconnected event -> finished()
    }

    private fun cleanup() {
        leaveCallAudio()
        bridge?.interrupt(); bridge = null
        room = null; agentIdentity = null
        rtc?.let { f -> scope.launch { delay(300); f.dispose() } }; rtc = null
    }
}
