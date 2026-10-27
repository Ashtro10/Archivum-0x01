package ai.clu.voice

import ai.clu.CluApp
import ai.clu.core.agent.TurnOrigin
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.config.UiPolicy
import ai.clu.core.live.AgentPhase
import ai.clu.core.live.Link
import ai.clu.core.voice.TurnState
import ai.clu.core.voice.VoiceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What both conversation screens show, from whichever engine answers. Every field reflects something really happening. */
data class EngineUi(
    /** Clu's server (LiveKit agent) answers; false = the on-device engine. */
    val server: Boolean = false,
    val link: Link = Link.DISCONNECTED,
    val phase: AgentPhase = AgentPhase.ABSENT,
    val micOn: Boolean = false,
    val spokenReplies: Boolean = true,
    val userLive: String = "",
    val agentLive: String = "",
    val thinking: Boolean = false,
    val error: String? = null,
    val micLevel: Float = 0f,
    val agentLevel: Float = 0f,
    val reconnectAttempt: Int = 0,
    val metrics: Map<String, Double> = emptyMap(),
) {
    /** Something can be stopped right now (thinking, streaming or speaking). */
    val busy get() = thinking || agentLive.isNotEmpty() || phase == AgentPhase.SPEAKING || phase == AgentPhase.THINKING
}

/**
 * One facade over the two engines so the screens don't care which one answers:
 *  - server: [LiveKitSession] (official LiveKit SDK + Clu agent) for voice and text;
 *  - device: [VoiceSession] for voice and the app-scoped chat (local model) for text.
 * The engine is the owner's choice (Settings → Connection); there is no silent switching between them.
 */
class Engine(private val app: CluApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val server get() = app.liveKit.enabled()

    val ui: StateFlow<EngineUi> = combine(app.liveKit.state, app.voice.state, app.core.chat.state, app.core.settings.values) { lk, v, chat, _ ->
        if (app.liveKit.enabled()) EngineUi(true, lk.link, lk.agent, lk.micOn, lk.spokenReplies, lk.userLive, lk.agentLive, lk.thinking, lk.error,
            lk.micLevel, lk.agentLevel, lk.reconnectAttempt, lk.metrics)
        else {
            val phase = when (v.turn) {
                TurnState.LISTENING -> AgentPhase.LISTENING
                TurnState.PROCESSING -> AgentPhase.THINKING
                TurnState.SPEAKING -> AgentPhase.SPEAKING
                else -> if (chat.running) AgentPhase.THINKING else AgentPhase.ABSENT
            }
            EngineUi(false, Link.CONNECTED, phase, v.running && v.turn == TurnState.LISTENING, app.core.settings.bool(Settings.SPEAK_RESPONSES),
                v.partial, chat.streaming.orEmpty(), chat.running && chat.streaming.isNullOrEmpty() || v.turn == TurnState.PROCESSING,
                v.error.ifBlank { null }, if (v.running) ((v.levelDb + 60) / 50).toFloat().coerceIn(0f, 1f) else 0f,
                if (app.speech.busy) 0.5f else 0f)
        }
    }.stateIn(scope, SharingStarted.Eagerly, EngineUi())

    private val visibleScreens = HashSet<String>()
    private var idleClose: Job? = null

    /**
     * A conversation screen became visible or hidden. When none is visible (Clu in the background or closed), the
     * server connection ends after [IDLE_DISCONNECT_MS]: an open room keeps an agent session running (and, on LiveKit
     * Cloud, billed). Coming back reconnects; the conversation history stays on the phone.
     */
    fun screenVisible(screen: String, visible: Boolean) {
        if (visible) { visibleScreens += screen; idleClose?.cancel(); idleClose = null; return }
        visibleScreens -= screen
        if (visibleScreens.isNotEmpty() || !server) return
        idleClose?.cancel()
        idleClose = scope.launch {
            delay(IDLE_DISCONNECT_MS)
            if (visibleScreens.isEmpty()) { app.liveKit.live.log("idle_disconnect", "no Clu screen visible for ${IDLE_DISCONNECT_MS / 1000} s"); app.liveKit.close() }
        }
    }

    /** Voice screen opened: listen and speak replies. */
    fun startVoice() {
        if (server) app.liveKit.open(mic = true, spokenReplies = true)
        else if (app.hasMic()) app.voice.start(false)
    }

    /** Voice screen left for the chat: stop listening; the server connection stays for text. */
    fun stopVoice() {
        if (server) { app.liveKit.setMic(false); app.liveKit.setSpokenReplies(app.core.settings.bool(Settings.SPEAK_RESPONSES)) }
        else { app.voice.stop(); app.speech.stop() }
    }

    /** Chat screen opened: connect for text (no microphone). */
    fun startChat() { if (server) app.liveKit.open(mic = false, spokenReplies = app.core.settings.bool(Settings.SPEAK_RESPONSES)) }

    fun setMic(on: Boolean) {
        if (server) app.liveKit.setMic(on)
        else if (on) { if (app.hasMic()) app.voice.start(false) } else app.voice.stop()
    }

    /** Spoken replies on/off (paths voice/text -> voice vs. -> text). Remembered for the chat screen. */
    fun setSpokenReplies(on: Boolean) {
        app.core.settings.set(Settings.SPEAK_RESPONSES, on)
        if (server) app.liveKit.setSpokenReplies(on) else if (!on) app.speech.stop()
    }

    /** Sends a typed message to the active engine. */
    fun send(text: String): Boolean {
        if (server) return app.liveKit.sendText(text)
        val core = app.core
        val speak = UiPolicy.shouldSpeak(core.settings, viaVoice = false, nowMs = System.currentTimeMillis())
        val cfg = VoiceConfig.from(core.settings)
        return core.chat.send(TurnRequest(app.currentConversation(), text, app.ownerActor()), text,
            speech = if (speak) ({ app.speech.reply(cfg) }) else null, incremental = cfg.incremental)
    }

    /** Stop: Clu stops thinking/speaking now (the conversation and the microphone stay as they are). */
    fun stop() {
        if (server) app.liveKit.interrupt()
        else { app.core.chat.stop(); app.core.agent.cancel(TurnOrigin.VOICE); app.speech.stop() }
    }

    /** End the voice session entirely. */
    fun end() { if (server) app.liveKit.close() else { app.voice.stop(); app.speech.stop() } }

    companion object { const val IDLE_DISCONNECT_MS = 60_000L }
}
