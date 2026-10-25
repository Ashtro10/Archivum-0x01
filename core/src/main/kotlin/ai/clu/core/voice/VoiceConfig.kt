package ai.clu.core.voice

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore

/**
 * Concrete engine parameters derived from settings. The Android voice pipeline consumes only this object,
 * so the mapping from user-facing settings to engine behaviour is unit-tested here.
 */
data class VoiceConfig(
    val ttsEngine: String,
    val sherpaSpeakerId: Int,
    val androidVoice: String,
    val speed: Float,
    val pitch: Float,
    val volume: Float,
    val metallic: Double,
    val incremental: Boolean,
    val bargeIn: Boolean,
    val outputRoute: String,
    val asrEngine: String,
    val whisperModel: String,
    val sttLanguage: String,
    val vadThreshold: Float,
    val minSilenceSec: Float,
    val ackMode: String,
    val ackPhrases: List<String>,
    val continueAfterReply: Boolean,
    val followUpTimeoutMs: Long,
    val audioSource: String,
    val noiseSuppression: Boolean,
    val pronunciations: List<String>,
    val wakeEnabled: Boolean,
    val wakeEngine: String,
    val wakeThreshold: Double,
    val kwsThreshold: Float,
    val requireLeading: Boolean,
    val policy: WakePolicy,
    val clapSensitivity: Double,
    val clapCount: Int,
    val terminationPhrases: List<String>,
    val backgroundListening: Boolean,
) {
    companion object {
        fun vadThresholdFor(sensitivity: Double): Float = (0.75 - 0.5 * sensitivity.coerceIn(0.0, 1.0)).toFloat()

        fun from(s: SettingsStore) = VoiceConfig(
            ttsEngine = s.string(Settings.TTS_ENGINE),
            sherpaSpeakerId = s.int(Settings.TTS_SPEAKER),
            androidVoice = s.string(Settings.ANDROID_VOICE),
            speed = s.double(Settings.SPEED).toFloat(),
            pitch = s.double(Settings.PITCH).toFloat(),
            volume = s.double(Settings.VOLUME).toFloat(),
            metallic = s.double(Settings.METALLIC),
            incremental = s.bool(Settings.INCREMENTAL_TTS),
            bargeIn = s.bool(Settings.BARGE_IN),
            outputRoute = s.string(Settings.OUTPUT_ROUTE),
            asrEngine = s.string(Settings.ASR_ENGINE),
            whisperModel = s.string(Settings.WHISPER_MODEL),
            sttLanguage = s.string(Settings.STT_LANGUAGE),
            vadThreshold = vadThresholdFor(s.double(Settings.VAD_SENSITIVITY)),
            minSilenceSec = s.int(Settings.END_SILENCE) / 1000f,
            ackMode = s.string(Settings.ACK_MODE),
            ackPhrases = s.list(Settings.ACK_PHRASES),
            continueAfterReply = s.bool(Settings.CONTINUE_LISTENING),
            followUpTimeoutMs = s.int(Settings.FOLLOWUP_TIMEOUT) * 1000L,
            audioSource = s.string(Settings.AUDIO_SOURCE),
            noiseSuppression = s.bool(Settings.NOISE_SUPPRESSION),
            pronunciations = s.list(Settings.PRONUNCIATIONS),
            wakeEnabled = s.bool(Settings.WAKE_ENABLED),
            wakeEngine = s.string(Settings.WAKE_ENGINE),
            wakeThreshold = s.double(Settings.WAKE_THRESHOLD),
            kwsThreshold = s.double(Settings.KWS_THRESHOLD).toFloat(),
            requireLeading = s.bool(Settings.WAKE_LEADING),
            policy = WakePolicy(
                words = s.wakeWords(),
                clapEnabled = s.bool(Settings.CLAP_ENABLED),
                noiseAdaptive = s.bool(Settings.NOISE_ADAPTIVE),
                strictInNoise = s.bool(Settings.STRICT_IN_NOISE),
                noisyThresholdDb = s.double(Settings.NOISY_DB),
            ),
            clapSensitivity = s.double(Settings.CLAP_SENSITIVITY),
            clapCount = s.int(Settings.CLAP_COUNT),
            terminationPhrases = s.list(Settings.TERMINATION_PHRASES),
            backgroundListening = s.bool(Settings.BACKGROUND_LISTENING),
        )

        /** Keys whose change requires rebuilding the voice pipeline. */
        val pipelineKeys = listOf(
            Settings.TTS_ENGINE, Settings.TTS_SPEAKER, Settings.ANDROID_VOICE, Settings.ASR_ENGINE, Settings.VAD_SENSITIVITY,
            Settings.END_SILENCE, Settings.AUDIO_SOURCE, Settings.NOISE_SUPPRESSION, Settings.WAKE_ENABLED, Settings.WAKE_ENGINE,
            Settings.WAKE_WORDS, Settings.KWS_THRESHOLD, Settings.CLAP_ENABLED, Settings.BACKGROUND_LISTENING, Settings.WHISPER_MODEL,
            Settings.OUTPUT_ROUTE,
        )
    }
}

enum class TurnState { OFF, WAITING_FOR_WAKE, LISTENING, PROCESSING, SPEAKING }

sealed class TurnAction {
    object Acknowledge : TurnAction()
    object StopPlayback : TurnAction()
    data class Process(val text: String) : TurnAction()
    object EndSession : TurnAction()
    object SessionTimedOut : TurnAction()
}

/**
 * Conversational turn-taking state machine for a voice session. Pure logic (no audio), driven by events from the
 * Android pipeline: wake detection, finished utterances, playback events and periodic ticks.
 */
class TurnManager(@Volatile var config: VoiceConfig, private val now: () -> Long) {
    var state = TurnState.OFF
        private set
    private var lastActivity = 0L

    fun start(wakeMode: Boolean) { state = if (wakeMode) TurnState.WAITING_FOR_WAKE else TurnState.LISTENING; lastActivity = now() }
    fun stop() { state = TurnState.OFF }

    fun onActivated(): List<TurnAction> {
        if (state == TurnState.OFF) return emptyList()
        val actions = mutableListOf<TurnAction>()
        if (state == TurnState.SPEAKING) actions += TurnAction.StopPlayback
        state = TurnState.LISTENING
        lastActivity = now()
        actions += TurnAction.Acknowledge
        return actions
    }

    /** User started talking (VAD). Implements barge-in. */
    fun onSpeechStarted(): List<TurnAction> {
        lastActivity = now()
        if (state == TurnState.SPEAKING && config.bargeIn) {
            state = TurnState.LISTENING
            return listOf(TurnAction.StopPlayback)
        }
        return emptyList()
    }

    fun onUtterance(text: String): List<TurnAction> {
        if (state != TurnState.LISTENING) return emptyList()
        lastActivity = now()
        if (text.isBlank()) return emptyList()
        if (WakePhraseMatcher.isTermination(text, config.terminationPhrases)) {
            state = TurnState.WAITING_FOR_WAKE
            return listOf(TurnAction.EndSession)
        }
        state = TurnState.PROCESSING
        return listOf(TurnAction.Process(text))
    }

    fun onSpeakingStarted() { if (state == TurnState.PROCESSING) state = TurnState.SPEAKING }

    fun onResponseComplete() {
        if (state != TurnState.PROCESSING && state != TurnState.SPEAKING) return
        lastActivity = now()
        state = if (config.continueAfterReply) TurnState.LISTENING else TurnState.WAITING_FOR_WAKE
    }

    fun tick(): List<TurnAction> {
        if (state == TurnState.LISTENING && now() - lastActivity > config.followUpTimeoutMs) {
            state = TurnState.WAITING_FOR_WAKE
            return listOf(TurnAction.SessionTimedOut)
        }
        return emptyList()
    }
}
