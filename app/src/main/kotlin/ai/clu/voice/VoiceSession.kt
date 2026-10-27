package ai.clu.voice

import ai.clu.CluApp
import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnOrigin
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
    /** Current microphone level (dBFS), for the voice-mode orb. */
    val levelDb: Double = -90.0,
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
    /** The last utterances (text, timing, levels) and the audio of the latest one; memory only. */
    val asrLog = AsrLog()

    /** Hold-to-talk: user released the button. */
    fun endUtteranceNow() { forceEnd = true; stopAfterReply = true }

    /** Voice-mode mic tapped while listening: "I'm done", answer now without waiting for the end-of-speech pause. */
    fun endUtteranceSoon() { forceEnd = true }

    init {
        scope.launch {
            core.settings.observeAny(VoiceConfig.pipelineKeys).drop(1).collect {
                if (running.get()) { core.diag.i("voice", "voice settings changed – restarting session"); val wm = wakeMode; stop(); start(wm) }
            }
        }
        scope.launch {
            core.settings.values.collect { cfg = VoiceConfig.from(core.settings); turn.config = cfg }
        }
    }

    val isRunning get() = running.get()

    /** Starts listening. wakeMode = wait for the wake word; otherwise start listening immediately (mic button). */
    @Synchronized
    fun start(wakeMode: Boolean) {
        if (running.get()) { if (!wakeMode) activate(); return }
        this.wakeMode = wakeMode
        activatedAt = System.currentTimeMillis()
        running.set(true)
        core.llm.prepare() // the model loads while the owner speaks, not after

        if (cfg.outputRoute == "communication") { app.callAudio.enter(); out.route = "communication"; callAudioHeld = true }
        turn.start(wakeMode)
        if (!wakeMode) onActivated(spokenAck = false)
        thread = Thread({ captureLoop() }, "clu-voice").apply { priority = Thread.MAX_PRIORITY - 1; start() }
        publish()
    }

    @Synchronized
    fun stop() {
        running.set(false)
        thread?.join(1500); thread = null
        agentJob?.cancel(); core.agent.cancel(TurnOrigin.VOICE)
        out.stop()
        if (callAudioHeld) { callAudioHeld = false; out.route = "media"; app.callAudio.leave() }
        turn.stop()
        publish()
    }

    @Volatile private var callAudioHeld = false

    /** The reply finished playing (not interrupted): end the turn, or stop after a hold-to-talk reply. */
    @Volatile private var replyEndedAt = 0L

    private fun onReplyPlayed() {
        if (turn.state == TurnState.SPEAKING || turn.state == TurnState.PROCESSING) {
            turn.onResponseComplete(); publish()
            // The microphone stays open through the reply (barge-in), so listening resumes without restarting capture.
            if (replyEndedAt > 0 && turn.state == TurnState.LISTENING) core.diag.perf("reply_end_to_listening_ms", (System.currentTimeMillis() - replyEndedAt).toDouble())
            // "One question" listening mode (or hold-to-talk): the microphone closes after the reply.
            val oneShot = core.settings.string(Settings.MIC_MODE) == "tap"
            if ((stopAfterReply || oneShot) && !wakeMode) { stopAfterReply = false; scope.launch { stop() } }
        }
    }

    /** Manual activation (mic button while waiting for wake word). */
    fun activate() { if (running.get()) { onActivated(spokenAck = false) } else start(false) }

    /** Speaks text outside a turn (greetings, proactive messages). */
    fun say(text: String) = out.speak(text, cfg)

    private fun publish(update: (VoiceUiState) -> VoiceUiState = { it }) {
        _state.value = update(_state.value).copy(running = running.get(), turn = turn.state)
    }

    /** When the microphone session was opened or (re)activated: pre-roll never reaches back before this. */
    @Volatile private var activatedAt = 0L

    private fun onActivated(spokenAck: Boolean = true) {
        activatedAt = System.currentTimeMillis()
        val actions = turn.onActivated()
        if (TurnAction.StopPlayback in actions) { out.stop(); core.agent.cancel(TurnOrigin.VOICE) }
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
        val capture = AudioCapture(c, frameMs = FRAME_MS)
        try { capture.start() } catch (e: Exception) {
            running.set(false); publish { it.copy(error = e.message ?: "microphone error") }; return
        }
        publish { it.copy(error = "", info = "Mic effects: ${capture.effectsInfo}") }
        // The VAD waits for the configured end-of-speech pause before reporting silence (default 800 ms: tolerates
        // 0.7 s thinking pauses, measured); the Endpointer turns its decisions into turn start/end events.
        val vad = engines.vad(c.vadThreshold, c.minSilenceSec)
        val endpointer = Endpointer()
        var maskedUntil = 0L      // Clu's own spoken words (spoken acknowledgement) are not the owner's speech
        val stats = UtteranceStats()
        val noise = NoiseEstimator(frameMs = FRAME_MS)
        val bargeIn = BargeInDetector(minSpeechMs = 180, marginDb = if (cfg.outputRoute == "communication") 6.0 else 10.0)
        var lastPartialPublish = 0L
        val clap = ClapDetector(SAMPLE_RATE, c.clapSensitivity, c.clapCount)
        val ring = AudioRing(4)
        var kwsStream: OnlineStream? = null
        var kwsWords: List<String> = emptyList()
        var kwsFed = 0L                  // samples fed to the current KWS stream (its timestamp clock)
        var kwsSpeechOnset: Float? = null // stream time at which the VAD reported the current utterance
        var asrStream: OnlineStream? = null
        val utterance = ArrayList<FloatArray>()
        var speechActive = false
        var speechStartAt = 0L
        var vadOnsetAt = 0L // start of the current unbroken run of voice activity (barge-in timing origin)
        var lastNoisePublish = 0L
        var lastLevelPublish = 0L
        var asrMatchBuffer = ArrayList<FloatArray>()
        // Final transcripts: the live English recogniser, or Whisper for Hindi / mixed / automatic (AsrRouting).
        val whisperModel = app.whisper.model(c.whisperModel)?.takeIf { app.whisper.isInstalled(it) }
        AsrRouting.plan(c.sttLanguage, whisperModel != null).let { r ->
            if (r.whisperLanguage != null && whisperModel != null) app.whisper.warmUp(whisperModel)
            r.note?.let { n -> publish { it.copy(info = it.info + " · " + n) } }
        }
        val transcripts = java.util.concurrent.ConcurrentLinkedQueue<Transcript>()

        /** Audio from before the VAD fired: up to [preRollSec], but never from before the mic opened or Clu's own words. */
        fun preRoll(preRollSec: Double): FloatArray {
            val sinceOpen = ((System.currentTimeMillis() - maxOf(activatedAt, maskedUntil)) * SAMPLE_RATE / 1000).coerceAtLeast(0)
            return ring.last(minOf((SAMPLE_RATE * preRollSec).toLong(), sinceOpen).toInt())
        }

        fun newAsrStream(preRollSec: Double): OnlineStream {
            val asr = engines.asr()
            val s = asr.createStream()
            // Prime with silence (stable features), then the real pre-roll: the VAD fires ~0.25-0.45 s after the first
            // sound (measured), so 1 s of pre-roll keeps the first syllable ("A-pple").
            s.acceptWaveform(FloatArray(SAMPLE_RATE), SAMPLE_RATE)
            if (preRollSec > 0) preRoll(preRollSec).takeIf { it.isNotEmpty() }?.let { s.acceptWaveform(it, SAMPLE_RATE) }
            return s
        }

        fun handleTranscript(text: String, audio: FloatArray, tEnd: Long, asrMs: Double, engine: String, language: String) {
            asrLog.add(stats.record(engine, language, text, asrMs.toLong(), capture.effectsInfo, if (text.isBlank()) "nothing recognised" else ""), audio)
            core.diag.perf("asr_end_to_text_ms", stats.endSilenceMs + asrMs, engine)
            if (text.isBlank()) { if (turn.state == TurnState.LISTENING && asrStream == null) asrStream = newAsrStream(0.0); return }
            val speakerId = identifySpeaker(audio)
            publish { it.copy(lastHeard = text, partial = "") }
            for (a in turn.onUtterance(text)) when (a) {
                is TurnAction.Process -> runAgent(a.text, speakerId, tEnd, asrMs)
                TurnAction.EndSession -> endSession(text, speakerId)
                else -> {}
            }
        }

        fun finishUtterance(audio: FloatArray) {
            val s = asrStream ?: return
            val asr = engines.asr()
            val tEnd = System.currentTimeMillis()
            val route = AsrRouting.plan(cfg.sttLanguage, whisperModel != null)
            val whisperLang = route.whisperLanguage
            if (whisperModel != null && whisperLang != null) {
                // Whisper transcribes the whole utterance on its own worker (capture keeps running). The streaming
                // recogniser's text (live partials) is the fallback if Whisper fails.
                val partialText = asr.getResult(s).text.trim().lowercase()
                s.release(); asrStream = null
                publish { it.copy(partial = "…") }
                val padded = audio.copyOf(audio.size + SAMPLE_RATE * 3 / 10)
                app.whisper.transcribe(whisperModel, padded, whisperLang, threads = 4) { r ->
                    val ms = (System.currentTimeMillis() - tEnd).toDouble()
                    if (r != null) {
                        core.diag.perf("whisper_ms", ms, "${whisperModel.id} ${r.language} ${"%.1f".format(audio.size / 16000.0)}s${if (r.retried) " retried" else ""}")
                        core.diag.perf("whisper_encode_ms", r.encodeMs, whisperModel.id)
                    } else core.diag.w("voice", "Whisper failed; using the streaming recogniser's text")
                    transcripts.add(Transcript(r?.text?.lowercase() ?: partialText, audio, tEnd, ms,
                        if (r != null) "Whisper ${whisperModel.id}" else "live English (Whisper failed)", r?.language?.ifBlank { null } ?: whisperLang))
                }
                return
            }
            s.acceptWaveform(FloatArray((SAMPLE_RATE * ASR_TAIL_SEC).toInt()), SAMPLE_RATE)
            s.inputFinished()
            while (asr.isReady(s)) asr.decode(s)
            val text = asr.getResult(s).text.trim().lowercase()
            s.release(); asrStream = null
            val asrMs = (System.currentTimeMillis() - tEnd).toDouble()
            core.diag.perf("asr_finalize_ms", asrMs)
            handleTranscript(text, audio, tEnd, asrMs, "live English (${engines.asrModelName})", "en")
        }

        try {
            while (running.get()) {
                if (turn.state == TurnState.LISTENING && cfg.asrEngine != "clu") {
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
                while (true) { val t = transcripts.poll() ?: break; handleTranscript(t.text, t.audio, t.tEnd, t.asrMs, t.engine, t.language) }
                ring.add(frame)
                vad.acceptWaveform(frame)
                // Only Clu's own spoken words in LISTENING (spoken acknowledgement, greeting) are masked. The activation
                // tone is not: masking it used to swallow the first syllable of anyone who started talking right away.
                val masked = out.speakingWords && turn.state == TurnState.LISTENING
                if (masked) maskedUntil = System.currentTimeMillis()
                val isSpeech = vad.isSpeechDetected() && !masked
                noise.addFrame(frame, isSpeech)
                val env = cfg.policy.environment(noise.floorDb)
                val now = System.currentTimeMillis()
                if (now - lastNoisePublish > 500) { lastNoisePublish = now; publish { it.copy(noiseDb = noise.floorDb, environment = env) } }
                if (now - lastLevelPublish > 90) { lastLevelPublish = now; val db = AudioMath.dbfs(AudioMath.rms(frame)); publish { it.copy(levelDb = db) } }

                // Speech start/end bookkeeping (barge-in and utterance boundaries): the Endpointer tolerates pauses inside a
                // sentence (longer after "and", "the", ...), so Clu does not answer half a sentence.
                val ep = endpointer.onFrame(isSpeech, now)
                if (ep is Endpointer.Event.Started && !speechActive) {
                    speechActive = true; speechStartAt = now
                    kwsSpeechOnset = (kwsFed + frame.size) / SAMPLE_RATE.toFloat() // same convention as the measurements
                    if (turn.state == TurnState.LISTENING && asrStream == null) asrStream = newAsrStream(PRE_ROLL_SEC)
                    if (turn.state == TurnState.LISTENING && utterance.isEmpty()) utterance += preRoll(PRE_ROLL_SEC)
                    stats.start(now, utterance.sumOf { it.size })
                }
                // Barge-in: no wake word needed. The detector ignores Clu's own voice leaking into the microphone (level
                // relative to the echo floor measured during playback) and needs ~180 ms of real speech.
                val vadSpeech = vad.isSpeechDetected()
                vadOnsetAt = if (!vadSpeech) 0L else if (vadOnsetAt == 0L) now else vadOnsetAt
                if (turn.state == TurnState.SPEAKING && cfg.bargeIn) {
                    if (bargeIn.onFrame(vadSpeech, AudioMath.dbfs(AudioMath.rms(frame)), FRAME_MS, now)) {
                        for (a in turn.onSpeechStarted()) if (a == TurnAction.StopPlayback) {
                            out.stop(); core.agent.cancel(TurnOrigin.VOICE); agentJob?.cancel()
                            // From the start of the voice activity that triggered it. (It used to be measured from the endpointer's
                            // speech start, which can be seconds older: echo or an earlier utterance that never ended.)
                            core.diag.perf("barge_in_detect_ms", (now - (if (vadOnsetAt > 0) vadOnsetAt else now)).toDouble())
                            activatedAt = now - 1000 // the owner's words just before the barge-in decision are wanted
                            asrStream = newAsrStream(0.8)
                            utterance.clear(); utterance += preRoll(0.8)
                            speechActive = true
                            if (!endpointer.inSpeech) endpointer.onFrame(true, now)
                            stats.start(now, utterance.sumOf { it.size })
                            publish()
                        }
                    }
                } else bargeIn.reset()
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
                                    kwsFed = 0; kwsSpeechOnset = if (speechActive) 0f else null
                                }
                                val kws = engines.kws(cfg.kwsThreshold)
                                kwsStream!!.acceptWaveform(frame, SAMPLE_RATE)
                                kwsFed += frame.size
                                while (kws.isReady(kwsStream!!)) {
                                    kws.decode(kwsStream!!)
                                    val r = kws.getResult(kwsStream!!)
                                    if (r.keyword.isNotEmpty()) {
                                        val leading = WakePolicy.isLeading(r.timestamps.firstOrNull(), if (speechActive) kwsSpeechOnset else null)
                                        // Fresh stream after each detection so timestamps restart from zero.
                                        kwsStream!!.release(); kwsStream = kws.createStream(SentencePieceBpe.keywordSpec(engines.bpe, words))
                                        kwsFed = 0; kwsSpeechOnset = if (speechActive) 0f else null
                                        if (cfg.requireLeading && !leading) {
                                            core.diag.i("voice", "ignored mid-sentence '${r.keyword}'")
                                            break
                                        }
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
                            if (speechActive) {
                                utterance += frame
                                stats.frame(frame)
                                if (now - lastPartialPublish > 150) { lastPartialPublish = now; val pt = asr.getResult(s).text.lowercase(); publish { it.copy(partial = pt) } }
                            }
                        }
                        for (a in turn.tick()) if (a == TurnAction.SessionTimedOut) {
                            asrStream?.release(); asrStream = null
                            core.diag.i("voice", "follow-up window elapsed")
                            // A session opened with the mic button closes the microphone when the follow-up window passes in
                            // silence; only a wake-word session (Background listening) goes back to waiting for the wake word.
                            if (!wakeMode) running.set(false)
                            publish()
                        }
                    }
                    else -> {}
                }

                // Utterance end per Endpointer (end-of-speech pause, longer after an unfinished phrase), or hold-to-talk release.
                val forced = forceEnd && turn.state == TurnState.LISTENING
                if (forced) { forceEnd = false; if (asrStream != null) speechActive = true; endpointer.forceEnd() }
                if (speechActive && (ep is Endpointer.Event.Ended || forced)) {
                    speechActive = false
                    endpointer.reset()
                    while (!vad.empty()) vad.pop()
                    if (turn.state == TurnState.LISTENING && asrStream != null) {
                        val audio = utterance.flatMap { it.asIterable() }.toFloatArray(); utterance.clear()
                        stats.end(now, if (forced) 0 else (cfg.minSilenceSec * 1000).toLong(), audio)
                        finishUtterance(audio)
                    } else if (turn.state == TurnState.WAITING_FOR_WAKE && cfg.wakeEngine == "asr" && asrMatchBuffer.isNotEmpty()) {
                        val seg = asrMatchBuffer.flatMap { it.asIterable() }.toFloatArray(); asrMatchBuffer = ArrayList()
                        val s = newAsrStream(0.3); s.acceptWaveform(seg, SAMPLE_RATE); s.acceptWaveform(FloatArray((SAMPLE_RATE * ASR_TAIL_SEC).toInt()), SAMPLE_RATE); s.inputFinished()
                        val asr = engines.asr(); while (asr.isReady(s)) asr.decode(s)
                        val heard = asr.getResult(s).text; s.release()
                        WakePhraseMatcher.match(heard, cfg.policy.allowedWords(env), cfg.wakeThreshold)?.let { m ->
                            core.diag.d("voice", "wake phrase '${m.word.phrase}' via ASR ('$heard')")
                            onActivated()
                            if (m.remainder.isNotBlank()) for (a in turn.onUtterance(m.remainder)) if (a is TurnAction.Process) runAgent(a.text, identifySpeaker(seg), System.currentTimeMillis(), 0.0)
                            else asrStream = newAsrStream(0.0)
                        }
                    }
                }
                if (asrMatchBuffer.size > 8000 / FRAME_MS) asrMatchBuffer.clear() // > 8 s without end: drop
            }
        } catch (e: Exception) {
            core.diag.e("voice", "voice loop failed", e)
            publish { it.copy(error = "Voice error: ${e.message}") }
        } finally {
            capture.stop()
            kwsStream?.release(); asrStream?.release(); vad.release()
            app.whisper.cancel()
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
        val speak = UiPolicy.shouldSpeak(core.settings, viaVoice = true, nowMs = System.currentTimeMillis())
        val c = cfg
        // tEndOfSpeech is the VAD endpoint, which fires after the configured end-of-speech pause; the owner actually
        // stopped talking about that much earlier, and the latency targets are measured from there.
        val timings = TurnTimings(0).apply {
            userSpeechEnd = tEndOfSpeech - (c.minSilenceSec * 1000).toLong(); transcriptFinal = tEndOfSpeech + asrMs.toLong()
        }
        val reply = if (speak) out.reply(c, timings) { core.agent.cancel(TurnOrigin.VOICE) } else null
        reply?.onFirstAudio = { turn.onSpeakingStarted(); publish() }
        var stats: Map<String, Double> = emptyMap()
        val note = "voice turn ${voiceTurns.incrementAndGet()}"
        reply?.completion?.invokeOnCompletion {
            replyEndedAt = System.currentTimeMillis()
            val m = timings.metrics()
            // An interrupted reply's playback figures (gaps, overlap with generation) describe a cut-off reply: kept apart.
            m.forEach { (k, v) -> core.diag.perf(if (reply.cancelled && k in PLAYBACK_METRICS) ai.clu.core.diag.DiagnosticsLog.classified(k, "interrupted") else k, v, note) }
            if (timings.firstAudio > 0) core.diag.perf("endpoint_to_first_audio_ms", (timings.firstAudio - tEndOfSpeech).toDouble(), note)
            core.diag.perf("tts_errors", timings.errors.get().toDouble(), note)
            publish { it.copy(lastLatency = stats + m) }
            if (!reply.cancelled) onReplyPlayed()
        }
        agentJob = scope.launch {
            var spokeError = false
            var spokeText = false
            var narrated = false
            core.agent.respond(TurnRequest(convId, text, actor, viaVoice = true, speakerName = name, preStats = mapOf("asr_ms" to asrMs),
                spokenOnDevice = reply != null)).collect { ev ->
                when (ev) {
                    is AgentEvent.Text -> { spokeText = true; if (c.incremental) reply?.text(ev.delta) }
                    is AgentEvent.ResetText -> {}
                    // A slow online lookup gets one short spoken heads-up instead of silence ("Checking the map.").
                    is AgentEvent.ToolStarted -> if (!spokeText && !narrated && c.incremental) ai.clu.core.tools.ToolProgress.spoken(ev.tool)?.let {
                        narrated = true; reply?.text("$it ") }
                    is AgentEvent.Done -> {
                        stats = ev.meta.stats
                        if (reply != null) { if (!c.incremental) reply.say(ev.text) else reply.finish() }
                        if (reply == null || ev.text.isBlank()) { turn.onResponseComplete(); publish() }
                    }
                    is AgentEvent.Control -> when (ev.command) {
                        "stop_listening" -> scope.launch { delay(1500); app.stopListening() }
                        "end_session" -> {}
                    }
                    is AgentEvent.Error -> {
                        spokeError = true
                        if (reply != null) reply.say("Something went wrong.") else { out.speak("Something went wrong.", c); turn.onResponseComplete() }
                    }
                    else -> {}
                }
            }
            reply?.finish() // producer ended without Done (error/cancel): speak what is pending and end the response
            if (reply == null && !spokeError && (turn.state == TurnState.PROCESSING || turn.state == TurnState.SPEAKING)) { turn.onResponseComplete(); publish() }
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
            s.acceptWaveform(FloatArray((SAMPLE_RATE * ASR_TAIL_SEC).toInt()), SAMPLE_RATE); s.inputFinished(); while (asr.isReady(s)) asr.decode(s)
            asr.getResult(s).text.trim().lowercase().also { s.release() }
        } finally { cap.stop(); if (wasRunning) start(wm) }
    }

    private val voiceTurns = java.util.concurrent.atomic.AtomicLong()

    private class Transcript(val text: String, val audio: FloatArray, val tEnd: Long, val asrMs: Double, val engine: String, val language: String)

    companion object {
        const val UNKNOWN = "__unknown__"
        /** Capture frame: short so barge-in and end of speech are noticed quickly (VAD works in 32 ms windows). */
        const val FRAME_MS = 30
        /** Audio kept from before the VAD fired (VAD onset delay measured at ~0.25-0.45 s; see docs/SPEECH_RECOGNITION.md). */
        const val PRE_ROLL_SEC = 1.0
        private val PLAYBACK_METRICS = setOf("max_chunk_gap_ms", "audio_started_before_generation_end_ms")

    }
}
