package ai.clu.voice

import ai.clu.CluApp
import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.config.UiPolicy
import ai.clu.core.security.SpeakerMatcher
import ai.clu.core.voice.*
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import java.util.concurrent.atomic.AtomicBoolean

data class VoiceUiState(
    val running: Boolean = false,
    val turn: TurnState = TurnState.OFF,
    val noiseDb: Double = -90.0,
    val environment: Environment = Environment.QUIET,
    val partial: String = "",
    val lastHeard: String = "",
    val speaker: String = "",
    val error: String = "",
    val info: String = "",
    val lastLatency: Map<String, Double> = emptyMap(),
)

/**
 * The voice conversation loop. One capture thread feeds VAD, the noise estimator, clap detection and either the
 * wake detector or the streaming recogniser, driven by the core TurnManager state machine.
 */
class VoiceSession(private val app: CluApp) {
    private val core = app.core
    private val engines = app.sherpa
    private val out = app.speech
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> get() = _state
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var cfg = VoiceConfig.from(core.settings)
    private val turn = TurnManager(cfg, System::currentTimeMillis)
    private var agentJob: Job? = null
    private val androidAsr = AndroidAsr(app)
    @Volatile var wakeMode = true
        private set
    /** Set by hold-to-talk release: finish the current utterance immediately. */
    @Volatile private var forceEnd = false
    @Volatile private var stopAfterReply = false

    /** Hold-to-talk: user released the button. */
    fun endUtteranceNow() { forceEnd = true; stopAfterReply = true }

    init {
        scope.launch {
            core.settings.observeAny(VoiceConfig.pipelineKeys).drop(1).collect {
                if (running.get()) { core.diag.i("voice", "voice settings changed – restarting session"); val wm = wakeMode; stop(); start(wm) }
            }
        }
        scope.launch {
            core.settings.values.collect { cfg = VoiceConfig.from(core.settings); turn.config = cfg }
        }
        out.player.onIdle = {
            if (turn.state == TurnState.SPEAKING && agentJob?.isActive != true) {
                turn.onResponseComplete(); publish()
                if (stopAfterReply && !wakeMode) { stopAfterReply = false; scope.launch { stop() } }
            }
        }
    }

    val isRunning get() = running.get()

    /** Starts listening. wakeMode = wait for the wake word; otherwise start listening immediately (mic button). */
    @Synchronized
    fun start(wakeMode: Boolean) {
        if (running.get()) { if (!wakeMode) activate(); return }
        this.wakeMode = wakeMode
        running.set(true)
        turn.start(wakeMode)
        if (!wakeMode) onActivated(spokenAck = false)
        thread = Thread({ captureLoop() }, "clu-voice").apply { priority = Thread.MAX_PRIORITY - 1; start() }
        publish()
    }

    @Synchronized
    fun stop() {
        running.set(false)
        thread?.join(1500); thread = null
        agentJob?.cancel(); core.agent.cancel()
        out.stop()
        turn.stop()
        publish()
    }

    /** Manual activation (mic button while waiting for wake word). */
    fun activate() { if (running.get()) { onActivated(spokenAck = false) } else start(false) }

    /** Speaks text outside a turn (greetings, proactive messages). */
    fun say(text: String) = out.speak(text, cfg)

    private fun publish(update: (VoiceUiState) -> VoiceUiState = { it }) {
        _state.value = update(_state.value).copy(running = running.get(), turn = turn.state)
    }

    private fun onActivated(spokenAck: Boolean = true) {
        val actions = turn.onActivated()
        if (TurnAction.StopPlayback in actions) { out.stop(); core.agent.cancel() }
        if (spokenAck || turn.state == TurnState.LISTENING) {
            when (cfg.ackMode) {
                "tone" -> out.playTone(cfg)
                "speech" -> out.speak(cfg.ackPhrases.random(), cfg)
            }
        }
        if (spokenAck) UiPolicy.activationGreeting(core.settings, core.templates, System.currentTimeMillis())?.let { out.speak(it, cfg) }
        core.commands.byKind(ai.clu.core.commands.CommandKind.STARTUP).forEach { cmd ->
            scope.launch { cmd.steps.forEach { s -> core.tools.execute(s.tool, s.args, ai.clu.core.tools.ToolContext(app.ownerActor(), core.personas.active(), app.currentConversation(), System.currentTimeMillis())) } }
        }
        publish()
    }

    private fun captureLoop() {
        val c = cfg
        val capture = AudioCapture(c)
        try { capture.start() } catch (e: Exception) {
            running.set(false); publish { it.copy(error = e.message ?: "microphone error") }; return
        }
        publish { it.copy(error = "", info = "Mic effects: ${capture.effectsInfo}") }
        val vad = engines.vad(c.vadThreshold, c.minSilenceSec)
        val noise = NoiseEstimator(frameMs = 100)
        val clap = ClapDetector(SAMPLE_RATE, c.clapSensitivity, c.clapCount)
        val ring = AudioRing(4)
        var kwsStream: OnlineStream? = null
        var kwsWords: List<String> = emptyList()
        var asrStream: OnlineStream? = null
        val utterance = ArrayList<FloatArray>()
        var speechActive = false
        var speechStartAt = 0L
        var lastNoisePublish = 0L
        var asrMatchBuffer = ArrayList<FloatArray>()

        fun newAsrStream(preRollSec: Double): OnlineStream {
            val asr = engines.asr()
            val s = asr.createStream()
            // This streaming model needs ~1 s of context before speech (measured); prime with silence + real pre-roll.
            s.acceptWaveform(FloatArray(SAMPLE_RATE), SAMPLE_RATE)
            if (preRollSec > 0) s.acceptWaveform(ring.last((SAMPLE_RATE * preRollSec).toInt()), SAMPLE_RATE)
            return s
        }

        fun finishUtterance(audio: FloatArray) {
            val s = asrStream ?: return
            val asr = engines.asr()
            val tEnd = System.currentTimeMillis()
            s.acceptWaveform(FloatArray((SAMPLE_RATE * 0.5).toInt()), SAMPLE_RATE)
            s.inputFinished()
            while (asr.isReady(s)) asr.decode(s)
            val text = asr.getResult(s).text.trim().lowercase()
            s.release(); asrStream = null
            val asrMs = (System.currentTimeMillis() - tEnd).toDouble()
            core.diag.perf("asr_finalize_ms", asrMs)
            if (text.isBlank()) { asrStream = if (turn.state == TurnState.LISTENING) newAsrStream(0.0) else null; return }
            val speakerId = identifySpeaker(audio)
            publish { it.copy(lastHeard = text, partial = "") }
            for (a in turn.onUtterance(text)) when (a) {
                is TurnAction.Process -> runAgent(a.text, speakerId, tEnd, asrMs)
                TurnAction.EndSession -> endSession(text, speakerId)
                else -> {}
            }
        }

        try {
            while (running.get()) {
                if (turn.state == TurnState.LISTENING && cfg.asrEngine != "sherpa") {
                    // Android recognizers own the microphone while they run.
                    capture.stop()
                    val tStart = System.currentTimeMillis()
                    val r = runBlocking { androidAsr.recognizeOnce(onDevice = cfg.asrEngine == "android_ondevice") }
                    try { capture.start() } catch (e: Exception) { publish { it.copy(error = e.message ?: "microphone error") }; break }
                    when (r) {
                        is AndroidAsr.Result.Error -> {
                            // Don't spin on a missing/broken recognizer: report it and leave listening mode.
                            publish { it.copy(error = r.message) }
                            if (!wakeMode) running.set(false) else { turn.stop(); turn.start(true) }
                        }
                        is AndroidAsr.Result.Text -> if (r.text.isNotBlank()) {
                            val text = r.text.lowercase(); publish { it.copy(lastHeard = text, error = "") }
                            for (a in turn.onUtterance(text)) when (a) {
                                is TurnAction.Process -> runAgent(a.text, null, System.currentTimeMillis(), (System.currentTimeMillis() - tStart).toDouble())
                                TurnAction.EndSession -> endSession(text, null)
                                else -> {}
                            }
                        } else for (a in turn.tick()) {}
                    }
                    continue
                }
                val frame = capture.read() ?: break
                ring.add(frame)
                vad.acceptWaveform(frame)
                // While Clu's own acknowledgement/greeting plays in LISTENING state, don't treat it as the owner speaking.
                val isSpeech = vad.isSpeechDetected() && !(out.busy && turn.state == TurnState.LISTENING)
                noise.addFrame(frame, isSpeech)
                val env = cfg.policy.environment(noise.floorDb)
                val now = System.currentTimeMillis()
                if (now - lastNoisePublish > 500) { lastNoisePublish = now; publish { it.copy(noiseDb = noise.floorDb, environment = env) } }

                // Speech start/end bookkeeping (barge-in and utterance boundaries).
                if (isSpeech && !speechActive) {
                    speechActive = true; speechStartAt = now
                    if (turn.state == TurnState.LISTENING && asrStream == null) asrStream = newAsrStream(0.4)
                }
                if (speechActive && isSpeech && turn.state == TurnState.SPEAKING && now - speechStartAt > 300) {
                    for (a in turn.onSpeechStarted()) if (a == TurnAction.StopPlayback) {
                        out.stop(); core.agent.cancel(); agentJob?.cancel()
                        asrStream = newAsrStream(0.6)
                        utterance.clear(); utterance += ring.last((SAMPLE_RATE * 0.6).toInt())
                        publish()
                    }
                }
                if (speechActive && isSpeech && turn.state == TurnState.LISTENING) turn.onSpeechStarted() // keeps the follow-up window open

                when (turn.state) {
                    TurnState.WAITING_FOR_WAKE -> {
                        if (cfg.wakeEnabled) {
                            if (cfg.wakeEngine == "kws") {
                                val words = cfg.policy.allowedWords(env).flatMap { listOf(it.phrase) + it.aliases.filter { a -> a.split(' ').size <= 3 && !a.contains("clu") } }
                                if (kwsStream == null || words != kwsWords) {
                                    kwsStream?.release()
                                    val kws = engines.kws(cfg.kwsThreshold)
                                    kwsStream = kws.createStream(SentencePieceBpe.keywordSpec(engines.bpe, words)); kwsWords = words
                                }
                                val kws = engines.kws(cfg.kwsThreshold)
                                kwsStream!!.acceptWaveform(frame, SAMPLE_RATE)
                                while (kws.isReady(kwsStream!!)) {
                                    kws.decode(kwsStream!!)
                                    val r = kws.getResult(kwsStream!!)
                                    if (r.keyword.isNotEmpty()) {
                                        kws.reset(kwsStream!!)
                                        core.diag.i("voice", "wake word '${r.keyword}' (env=$env, noise=${"%.1f".format(noise.floorDb)} dBFS)")
                                        onActivated()
                                        asrStream = newAsrStream(0.0)
                                        utterance.clear()
                                        break
                                    }
                                }
                            } else if (speechActive) {
                                asrMatchBuffer.add(frame)
                            }
                        }
                        if (cfg.policy.clapAllowed(env) && clap.process(frame)) {
                            core.diag.i("voice", "clap activation (env=$env)")
                            onActivated(); asrStream = newAsrStream(0.0); utterance.clear()
                        }
                    }
                    TurnState.LISTENING -> {
                        asrStream?.let { s ->
                            s.acceptWaveform(frame, SAMPLE_RATE)
                            val asr = engines.asr()
                            while (asr.isReady(s)) asr.decode(s)
                            if (speechActive) { utterance += frame; publish { it.copy(partial = asr.getResult(s).text.lowercase()) } }
                        }
                        for (a in turn.tick()) if (a == TurnAction.SessionTimedOut) {
                            asrStream?.release(); asrStream = null
                            core.diag.i("voice", "follow-up window elapsed")
                            if (!wakeMode && !cfg.wakeEnabled) running.set(false)
                            publish()
                        }
                    }
                    else -> {}
                }

                // Utterance end per VAD (min silence = configured end-of-speech pause), or hold-to-talk release.
                val forced = forceEnd && turn.state == TurnState.LISTENING
                if (forced) { forceEnd = false; if (asrStream != null) speechActive = true }
                if (speechActive && (!isSpeech || forced)) {
                    speechActive = false
                    while (!vad.empty()) vad.pop()
                    if (turn.state == TurnState.LISTENING && asrStream != null) {
                        val audio = utterance.flatMap { it.asIterable() }.toFloatArray(); utterance.clear()
                        finishUtterance(audio)
                    } else if (turn.state == TurnState.WAITING_FOR_WAKE && cfg.wakeEngine == "asr" && asrMatchBuffer.isNotEmpty()) {
                        val seg = asrMatchBuffer.flatMap { it.asIterable() }.toFloatArray(); asrMatchBuffer = ArrayList()
                        val s = newAsrStream(0.3); s.acceptWaveform(seg, SAMPLE_RATE); s.acceptWaveform(FloatArray(8000), SAMPLE_RATE); s.inputFinished()
                        val asr = engines.asr(); while (asr.isReady(s)) asr.decode(s)
                        val heard = asr.getResult(s).text; s.release()
                        WakePhraseMatcher.match(heard, cfg.policy.allowedWords(env), cfg.wakeThreshold)?.let { m ->
                            core.diag.i("voice", "wake phrase '${m.word.phrase}' via ASR ('$heard')")
                            onActivated()
                            if (m.remainder.isNotBlank()) for (a in turn.onUtterance(m.remainder)) if (a is TurnAction.Process) runAgent(a.text, identifySpeaker(seg), System.currentTimeMillis(), 0.0)
                            else asrStream = newAsrStream(0.0)
                        }
                    }
                }
                if (asrMatchBuffer.size > 80) asrMatchBuffer.clear() // > 8 s without end: drop
            }
        } catch (e: Exception) {
            core.diag.e("voice", "voice loop failed", e)
            publish { it.copy(error = "Voice error: ${e.message}") }
        } finally {
            capture.stop()
            kwsStream?.release(); asrStream?.release(); vad.release()
            running.set(false)
            publish()
        }
    }

    private fun identifySpeaker(audio: FloatArray): String? {
        if (!core.settings.bool(Settings.SPEAKER_VERIFY) || audio.size < SAMPLE_RATE / 2) return null
        val ext = engines.speaker() ?: return null
        return runCatching {
            val s = ext.createStream(); s.acceptWaveform(audio, SAMPLE_RATE); s.inputFinished()
            val emb = ext.compute(s); s.release()
            val enrolled = core.people.voiceprints(VoicePacks.SPEAKER.id)
            if (enrolled.isEmpty()) return null
            val overrides = core.people.list().mapNotNull { p -> p.voiceThreshold?.let { p.id to it } }.toMap()
            val m = SpeakerMatcher.identify(emb, enrolled, core.settings.double(Settings.SPEAKER_THRESHOLD), overrides)
            core.diag.d("voice", "speaker score ${"%.2f".format(m.score)} -> ${m.personId ?: "unknown"}")
            m.personId ?: UNKNOWN
        }.getOrNull()
    }

    private fun runAgent(text: String, speakerId: String?, tEndOfSpeech: Long, asrMs: Double) {
        val speakerIdActive = core.settings.bool(Settings.SPEAKER_VERIFY) && engines.speaker() != null &&
            core.people.voiceprints(VoicePacks.SPEAKER.id).isNotEmpty()
        val actor = core.auth.actorForSpeaker(speakerId?.takeIf { it != UNKNOWN }, speakerIdActive)
        if (actor == null) { core.diag.i("voice", "ignored unrecognised speaker"); turn.onResponseComplete(); publish(); return }
        val name = actor.personId?.let { core.people.get(it)?.name }
        publish { it.copy(speaker = name ?: if (actor.role == ai.clu.core.security.PersonRole.OWNER) "owner" else "unknown") }
        val convId = app.currentConversation()
        val chunker = SentenceChunker()
        var firstAudioAt = 0L
        val speak = UiPolicy.shouldSpeak(core.settings, viaVoice = true, nowMs = System.currentTimeMillis())
        val onFirst = { if (firstAudioAt == 0L) { firstAudioAt = System.currentTimeMillis(); turn.onSpeakingStarted(); publish() } }
        agentJob = scope.launch {
            core.agent.respond(TurnRequest(convId, text, actor, viaVoice = true, speakerName = name, preStats = mapOf("asr_ms" to asrMs))).collect { ev ->
                when (ev) {
                    is AgentEvent.Text -> if (speak && cfg.incremental) chunker.push(ev.delta).forEach { out.speak(it, cfg, onFirst) }
                    is AgentEvent.ResetText -> {}
                    is AgentEvent.Done -> {
                        if (speak) { if (cfg.incremental) chunker.flush().forEach { out.speak(it, cfg, onFirst) } else out.speak(ev.text, cfg, onFirst) }
                        scope.launch {
                            // Wait for first audio to compute end-of-speech -> first-audio latency.
                            val deadline = System.currentTimeMillis() + 15_000
                            while (firstAudioAt == 0L && System.currentTimeMillis() < deadline && speak) delay(20)
                            if (firstAudioAt > 0) {
                                val lat = (firstAudioAt - tEndOfSpeech).toDouble()
                                core.diag.perf("first_audio_ms", lat)
                                publish { it.copy(lastLatency = ev.meta.stats + mapOf("first_audio_ms" to lat)) }
                            }
                        }
                        if (!speak || ev.text.isBlank()) { turn.onResponseComplete(); publish() }
                    }
                    is AgentEvent.Control -> when (ev.command) {
                        "stop_listening" -> scope.launch { delay(1500); app.stopListening() }
                        "end_session" -> {}
                    }
                    is AgentEvent.Error -> { out.speak("Something went wrong.", cfg); turn.onResponseComplete() }
                    else -> {}
                }
            }
            // Watchdog: if synthesis failed or produced no audio, don't leave the turn stuck in PROCESSING/SPEAKING.
            val deadline = System.currentTimeMillis() + 120_000
            while (out.busy && System.currentTimeMillis() < deadline) delay(100)
            if (turn.state == TurnState.PROCESSING || turn.state == TurnState.SPEAKING) { turn.onResponseComplete(); publish() }
        }
    }

    private fun endSession(text: String, speakerId: String?) {
        // The agent produces the configured farewell and runs shutdown commands.
        runAgent(text, speakerId, System.currentTimeMillis(), 0.0)
        core.commands.byKind(ai.clu.core.commands.CommandKind.SHUTDOWN).forEach { cmd ->
            scope.launch { cmd.steps.forEach { s -> core.tools.execute(s.tool, s.args, ai.clu.core.tools.ToolContext(app.ownerActor(), core.personas.active(), app.currentConversation(), System.currentTimeMillis())) } }
        }
        if (!wakeMode && !cfg.wakeEnabled) scope.launch { delay(4000); stop() }
    }

    /** Records [seconds] of audio and returns the speaker embedding (voice enrolment). */
    suspend fun recordEmbedding(seconds: Int = 6, onLevel: (Double) -> Unit = {}): FloatArray? = withContext(Dispatchers.IO) {
        val ext = engines.speaker() ?: return@withContext null
        val wasRunning = running.get(); val wm = wakeMode
        if (wasRunning) stop()
        val cap = AudioCapture(cfg)
        try {
            cap.start()
            val buf = ArrayList<FloatArray>()
            repeat(seconds * 10) { cap.read()?.let { f -> buf += f; onLevel(AudioMath.dbfs(AudioMath.rms(f))) } }
            val audio = buf.flatMap { it.asIterable() }.toFloatArray()
            val s = ext.createStream(); s.acceptWaveform(audio, SAMPLE_RATE); s.inputFinished()
            ext.compute(s).also { s.release() }
        } finally { cap.stop(); if (wasRunning) start(wm) }
    }

    /** Records a short sample and returns what the recogniser heard (used to add wake-word aliases). */
    suspend fun transcribeSample(seconds: Int = 3): String = withContext(Dispatchers.IO) {
        val wasRunning = running.get(); val wm = wakeMode
        if (wasRunning) stop()
        val cap = AudioCapture(cfg)
        try {
            cap.start()
            val asr = engines.asr(); val s = asr.createStream()
            s.acceptWaveform(FloatArray(SAMPLE_RATE), SAMPLE_RATE)
            repeat(seconds * 10) { cap.read()?.let { f -> s.acceptWaveform(f, SAMPLE_RATE); while (asr.isReady(s)) asr.decode(s) } }
            s.acceptWaveform(FloatArray(8000), SAMPLE_RATE); s.inputFinished(); while (asr.isReady(s)) asr.decode(s)
            asr.getResult(s).text.trim().lowercase().also { s.release() }
        } finally { cap.stop(); if (wasRunning) start(wm) }
    }

    companion object { const val UNKNOWN = "__unknown__" }
}
