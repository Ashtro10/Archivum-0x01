package ai.clu.desktop.voice

import ai.clu.core.CluCore
import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnOrigin
import ai.clu.core.agent.TurnRequest
import ai.clu.core.security.Actor
import ai.clu.core.security.PersonRole
import ai.clu.core.voice.AdaptiveChunker
import ai.clu.core.voice.SpeechText
import ai.clu.core.voice.WakePhraseMatcher
import ai.clu.core.voice.WakeWord
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** What CLU is doing in a room, as LiveKit clients read it from the lk.agent.state attribute. */
enum class AgentPhase(val wire: String) { STARTING("initializing"), DORMANT("idle"), LISTENING("listening"), THINKING("thinking"), SPEAKING("speaking") }

/**
 * One turn's timings, measured on the PC (ms). [stages] is the whole lifecycle relative to the moment the voice detector
 * heard speech (typed turns: relative to submission): VAD onset, endpoint, STT final, submission, CLU's own events
 * (route, memory, each model call with queue/prefill/tokens), first user-facing text, first visible text, first audio, done.
 * [heard] and [reply] are kept in memory for the diagnostics panel only (never written to disk).
 */
data class TurnTimings(val at: Long, val text: String, val sttFinalizeMs: Long, val firstTokenMs: Long, val ttsFirstMs: Long, val speechEndToFirstAudioMs: Long, val interrupted: Boolean,
                       val id: String = "", val heard: String = "", val reply: String = "", val outcome: String = "", val stages: List<Stage> = emptyList()) {
    data class Stage(val name: String, val ms: Long, val detail: String = "")
}

/** What the recogniser heard and when (monotonic ms, the same clock as RequestTrace). */
data class Heard(val text: String, val vadAt: Long, val endpointAt: Long, val sttDoneAt: Long, val speechEndAt: Long)

internal fun monoMs() = System.nanoTime() / 1_000_000

/**
 * CLU in one LiveKit room: hears the owner's microphone (16 kHz), finds the end of each utterance (Silero VAD), recognises
 * it while it is spoken (streaming STT, partial transcripts sent to the client), passes the final text to the one
 * conversation manager (CluAgent: the same history, memory and tools as typed chat), and speaks the reply through the
 * selected voice sentence by sentence as it is generated. Speaking over CLU interrupts it: playback stops, queued audio is
 * dropped and the generation is cancelled, and the new utterance is handled with the conversation intact.
 */
/** What stops CLU without asking it anything: "stop", "stop talking", "be quiet", "that's enough", "never mind", "cancel"… */
internal val STOP = Regex("(?i)^(?:(?:ok|okay|clu|clue|please|no)[,.!]?\\s*)*(?:stop(?: it| talking| speaking| there| now)?|be quiet|quiet|shut up|enough|that'?s enough|never ?mind|cancel(?: that)?|hold on|wait|silence|shush|hush)(?:[,.!]?\\s*(?:please|now|clu|clue))*[\\s.!?]*$")

/** Longest text synthesised in one call (characters); see VoiceAgent.speakable. */
const val MAX_TTS_CHARS = 220

class VoiceAgent(
    private val core: CluCore, private val engines: VoiceEngines, private val settings: () -> VoiceSettings,
    private val room: LkRoom, private val conversation: () -> String, private val scope: CoroutineScope, private val log: (String) -> Unit,
) {
    private val audio = Executors.newSingleThreadExecutor { r -> Thread(r, "clu-voice-audio").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val frames = Channel<ShortArray>(capacity = 600, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile var phase = AgentPhase.STARTING; private set
    @Volatile var spokenReplies = true
    @Volatile var micOpen = true          // manual mode: the client switches it
    @Volatile var pushToTalk = false      // push-to-talk mode: held down
    @Volatile var awake = false           // wake-word mode: after a wake word, until a farewell or 30 s of silence
    @Volatile var listener: String? = null; private set
    val timings = ArrayDeque<TurnTimings>()
    private var reply: Job? = null; private var speech: Job? = null
    private val turnSeq = AtomicLong()
    private var lastHeardAt = System.currentTimeMillis()
    @Volatile var lastError = ""; private set

    @Volatile var started = false; private set
    fun start() {
        started = true
        audioLoop = scope.launch(audio) { for (f in frames) runCatching { onFrame(f) }.onFailure { lastError = it.message ?: "audio error"; log("voice: audio error ${it.message}") } }
        listOf("clu.interrupt", "clu.set_audio_output", "clu.load_history", "clu.set_mic", "clu.push_to_talk").forEach(room::registerRpc)
        scope.launch { setPhase(if (settings().mode == ListenMode.WAKE_WORD) AgentPhase.DORMANT else AgentPhase.LISTENING) }
    }

    /** Called by the room for every event. */
    fun onEvent(e: LkEvent) {
        when (e) {
            is LkEvent.AudioTrack -> if (listener == null || listener == e.participant) { listener = e.participant; room.listen(e.participant, e.trackHandle) { frames.trySend(it) }; log("voice: listening to ${e.participant}") }
            is LkEvent.TrackGone -> if (e.participant == listener) { room.stopListening(e.participant); listener = null }
            is LkEvent.Left -> if (e.participant == listener) { room.stopListening(e.participant); listener = null; interrupt("the listener left") }
            is LkEvent.Text -> if (e.topic == "lk.chat" && e.text.isNotBlank()) scope.launch { startTurn(e.text.trim(), typed = true) }
            is LkEvent.Rpc -> handleRpc(e)
            else -> {}
        }
    }

    private fun handleRpc(e: LkEvent.Rpc) {
        val p = runCatching { Json.parseToJsonElement(e.payload.ifBlank { "{}" }).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        when (e.method) {
            "clu.interrupt" -> { interrupt("stop pressed"); e.respond("{}", null) }
            "clu.set_audio_output" -> { spokenReplies = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: true; if (!spokenReplies) stopSpeaking(); e.respond("{}", null) }
            "clu.set_mic" -> { micOpen = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: true; e.respond("{}", null) }
            "clu.push_to_talk" -> { pushToTalk = p["down"]?.jsonPrimitive?.booleanOrNull ?: false; e.respond("{}", null) }
            "clu.load_history" -> e.respond("{}", null) // the PC already holds the whole conversation
            else -> e.respond(null, "unknown method")
        }
    }

    // ------------------------------------------------------------------------------------------------ hearing
    private var vad: Vad? = null
    private var stt: SpeechRecognizer? = null
    private val preRoll = ArrayDeque<FloatArray>()
    private var inSpeech = false; private var speechStartedAt = 0L; private var lastPartialAt = 0L; private var lastPartial = ""; private var segment = ""
    private var vadSilence = 0

    private fun onFrame(pcm: ShortArray) {
        val s = settings()
        if (vad == null || vadSilence != s.endSilenceMs) { vad?.close(); vad = engines.vad(s.endSilenceMs / 1000f); vadSilence = s.endSilenceMs }
        val f = Pcm.toFloat(pcm)
        val gateOpen = when (s.mode) { ListenMode.MANUAL -> micOpen; ListenMode.PUSH_TO_TALK -> pushToTalk; else -> micOpen }
        if (!gateOpen) { if (inSpeech) endUtterance(); return }
        val v = vad!!
        v.accept(f)
        if (!inSpeech) {
            preRoll.addLast(f); while (preRoll.size > 100) preRoll.removeFirst() // 1 s: the detector fires 0.25-0.45 s after the first sound (measured), this keeps the first syllable
            if (v.speaking) beginUtterance()
        } else {
            stt?.accept(f)
            val now = System.currentTimeMillis()
            if (now - lastPartialAt > 250) {
                lastPartialAt = now; val p = stt?.partial().orEmpty(); if (System.getProperty("clu.voiceDebug") != null) log("voice: partial \"$p\" at ${now - speechStartedAt} ms")
                // While dormant (wake-word mode) nothing heard is shown or sent anywhere until a wake word is recognised.
                if (p.isNotBlank() && p != lastPartial && !(s.mode == ListenMode.WAKE_WORD && !awake)) { lastPartial = p; scope.launch { transcript(p, final = false) } }
                // Barge-in: the owner talks over CLU for bargeInMs of continuous speech (the clients cancel CLU's own voice from the
                // microphone with WebRTC echo cancellation, so what the detector hears here is the owner).
                if ((phase == AgentPhase.SPEAKING || phase == AgentPhase.THINKING) && now - speechStartedAt >= s.bargeInMs) interrupt("barge-in")
            }
            if (!v.speaking) endUtterance()
        }
    }

    private fun beginUtterance() {
        inSpeech = true; speechStartedAt = System.currentTimeMillis(); vadAt = monoMs(); lastPartial = ""; segment = "u" + turnSeq.incrementAndGet()
        val rec = stt ?: engines.recognizer().also { stt = it }
        // Prime with 1 s of silence (stable features for the streaming model), then the pre-roll.
        val pre = preRoll.fold(FloatArray(16000)) { a, b -> a + b }; preRoll.clear()
        rec.begin(pre)
    }

    @Volatile private var vadAt = 0L
    private fun endUtterance() {
        inSpeech = false
        val endpointAt = monoMs()
        val text = stt?.finish().orEmpty(); val sttDoneAt = monoMs()
        if (text.isBlank()) { log("voice: utterance with no recognised words (${endpointAt - vadAt} ms of speech)"); return }
        lastHeardAt = System.currentTimeMillis()
        val h = Heard(text, vadAt, endpointAt, sttDoneAt, endpointAt - settings().endSilenceMs)
        scope.launch { onUtterance(text, h) }
    }

    private suspend fun onUtterance(heard: String, h: Heard) {
        val s = settings()
        var text = heard
        if (s.mode == ListenMode.WAKE_WORD) {
            if (awake && System.currentTimeMillis() - lastSpokeOrHeard() > 30_000) { awake = false; setPhase(AgentPhase.DORMANT) }
            if (!awake) {
                val m = WakePhraseMatcher.match(heard, s.wakeWords.mapIndexed { i, w -> WakeWord(w, primary = i == 0) }, s.wakeThreshold) ?: return // not for CLU: nothing is sent anywhere
                awake = true; log("voice: woken by \"${m.word.phrase}\"")
                text = m.remainder.trim()
                if (text.isEmpty()) { transcript(heard, final = true); setPhase(AgentPhase.LISTENING); say("Yes?"); return }
            }
        }
        transcript(heard, final = true)
        // "Stop talking", "be quiet", "never mind": stop at once (audio and generation) and wait; never a question for the model.
        if (isStopPhrase(text)) {
            log("voice: stop phrase heard"); interrupt("stop phrase")
            setPhase(if (s.mode == ListenMode.WAKE_WORD && !awake) AgentPhase.DORMANT else AgentPhase.LISTENING)
            return
        }
        if (s.isFarewell(text)) {
            log("voice: farewell heard")
            if (s.mode == ListenMode.WAKE_WORD) { awake = false; say("Goodbye."); setPhase(AgentPhase.DORMANT) } else say("Goodbye.")
            return
        }
        startTurn(text, typed = false, h = h.copy(text = text))
    }

    private fun lastSpokeOrHeard() = maxOf(lastHeardAt, lastSpokeAt)

    private fun isStopPhrase(t: String) = STOP.matches(t.trim())
    @Volatile private var lastSpokeAt = 0L

    // ------------------------------------------------------------------------------------------------ thinking and speaking
    private suspend fun startTurn(text: String, typed: Boolean, h: Heard? = null) {
        val speechEndAt = h?.let { System.currentTimeMillis() - (monoMs() - it.speechEndAt) } ?: 0L
        val sttMs = h?.let { it.sttDoneAt - it.endpointAt } ?: 0L
        val turnId = "t" + turnSeq.incrementAndGet()
        val origin = h?.vadAt ?: monoMs()
        val marks = java.util.concurrent.ConcurrentHashMap<String, Long>()
        var agentTrace: ai.clu.core.diag.RequestTrace.Request? = null
        fun mark(name: String) { marks.putIfAbsent(name, monoMs()) }
        h?.let { marks["vad_onset"] = it.vadAt; marks["speech_end_est"] = it.speechEndAt; marks["endpoint"] = it.endpointAt; marks["stt_final"] = it.sttDoneAt }
        mark("submitted")
        interrupt(null) // a new turn replaces anything still running
        mark("previous_stopped")
        val conv = conversation()
        setPhase(AgentPhase.THINKING)
        mark("phase_thinking")
        val sentences = Channel<String>(capacity = 32)
        val seg = "r" + turnSeq.incrementAndGet()
        val started = System.currentTimeMillis(); var firstToken = 0L; var firstAudio = 0L; var interrupted = false
        val replyText = StringBuilder()
        speech = scope.launch {
            var spokeAny = false
            for (whole in sentences) for (chunk in speakable(ai.clu.core.agent.AnswerStyle.withoutInternalMarkup(whole))) {
                val s = settings()
                if (!spokenReplies) continue // the text was already shown when the sentence was complete
                var v = engines.voice(s.voice)
                val raw = SpeechText.forSpeech(s.pronounce(chunk)).ifBlank { continue }
                var synth = withContext(Dispatchers.Default) { v.first.synth(raw, v.second, s.speed.toFloat()) }
                if (synth.isEmpty() && v.first.closed) { v = engines.voice(s.voice); synth = withContext(Dispatchers.Default) { v.first.synth(raw, v.second, s.speed.toFloat()) } } // replaced meanwhile
                if (synth.isEmpty()) continue
                val paced = SpeechText.withPause(chunk, synth, v.first.sampleRate)
                val pcm = Pcm.toShort(Pcm.resample(paced, v.first.sampleRate, 24_000), s.volume.toFloat())
                if (!spokeAny) { spokeAny = true; firstAudio = System.currentTimeMillis(); mark("first_audio"); setPhase(AgentPhase.SPEAKING) }
                room.speak(pcm) { !isActive }
                lastSpokeAt = System.currentTimeMillis()
            }
        }
        reply = scope.launch {
            val chunker = AdaptiveChunker()
            try {
                mark("model_prepare")
                core.llm.prepare()
                mark("model_ready")
                core.agent.respond(TurnRequest(conv, text, Actor(null, PersonRole.OWNER, viaVoice = !typed), viaVoice = !typed, origin = if (typed) TurnOrigin.TEXT else TurnOrigin.VOICE,
                    spokenOnDevice = !typed, onTrace = { agentTrace = it }, vocabulary = settings().vocabulary)).collect { e ->
                    when (e) {
                        is AgentEvent.Text -> { if (firstToken == 0L) { firstToken = System.currentTimeMillis(); mark("first_text") }; replyText.append(e.delta)
                            // Each complete sentence is shown at once (not when its audio is ready) and queued for speech.
                            chunker.push(e.delta).forEach { c -> mark("first_visible"); transcriptReply(ai.clu.core.agent.AnswerStyle.withoutInternalMarkup(c), seg, final = false); sentences.send(c) } }
                        is AgentEvent.ResetText -> { replyText.setLength(0) }
                        is AgentEvent.Error -> { replyText.setLength(0); replyText.append(e.text); sentences.send(e.text) }
                        is AgentEvent.Done -> { chunker.flush().forEach { c -> mark("first_visible"); transcriptReply(ai.clu.core.agent.AnswerStyle.withoutInternalMarkup(c), seg, final = false); sentences.send(c) }
                            if (replyText.isEmpty() && e.text.isNotBlank()) { replyText.append(e.text); mark("first_visible"); transcriptReply(e.text, seg, final = false); sentences.send(e.text) }
                            if (e.text.isNotBlank()) { replyText.setLength(0); replyText.append(e.text) } } // the final, checked text is what is stored and shown
                        else -> {}
                    }
                }
            } catch (c: CancellationException) { interrupted = true; throw c
            } catch (e: Exception) { lastError = e.message ?: "reply failed"; sentences.trySend("Sorry, something went wrong with that answer.")
            } finally { sentences.close() }
            speech?.join()
            transcriptReply(replyText.toString(), seg, final = true)
            val endToAudio = if (speechEndAt > 0 && firstAudio > 0) firstAudio - speechEndAt else -1
            mark("done")
            val stages = (marks.map { (k, v) -> TurnTimings.Stage(k, v - origin) } +
                (agentTrace?.events.orEmpty().map { ev -> TurnTimings.Stage("clu." + ev.name.lowercase(), ev.atMs - origin, ev.detail) })).sortedBy { it.ms }
            val outcome = agentTrace?.terminal?.name?.lowercase() ?: if (interrupted) "interrupted" else "no result"
            record(TurnTimings(System.currentTimeMillis(), text.take(60), sttMs, if (firstToken > 0) firstToken - started else -1, if (firstAudio > 0 && firstToken > 0) firstAudio - firstToken else -1, endToAudio, interrupted,
                id = turnId, heard = h?.text ?: text, reply = replyText.toString().take(400), outcome = outcome, stages = stages))
            scope.launch(Dispatchers.Default) { engines.recycleWorn() } // between turns: keeps the voice's memory bounded
            if (phase == AgentPhase.SPEAKING || phase == AgentPhase.THINKING) setPhase(if (settings().mode == ListenMode.WAKE_WORD && !awake) AgentPhase.DORMANT else AgentPhase.LISTENING)
        }
    }

    /**
     * The chunker cuts at word boundaries, so text without spaces (CJK, or a broken model's output) can arrive as one
     * very long chunk. Synthesis time and the engine's memory grow with the input, so nothing longer than [MAX_TTS_CHARS]
     * is synthesised at once: split at the last space or punctuation before the limit, else hard.
     */
    private fun speakable(text: String): List<String> {
        if (text.length <= MAX_TTS_CHARS) return listOf(text)
        val out = mutableListOf<String>(); var rest = text
        while (rest.length > MAX_TTS_CHARS) {
            val window = rest.substring(0, MAX_TTS_CHARS)
            val cut = window.indexOfLast { it.isWhitespace() || it in ",.;:!?，。、" }.takeIf { it >= MAX_TTS_CHARS / 3 }?.plus(1) ?: MAX_TTS_CHARS
            out += rest.substring(0, cut).trim(); rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotBlank()) out += rest
        return out.filter { it.isNotBlank() }
    }

    /** Speaks a short fixed phrase (acknowledgements); not part of the conversation history. */
    private suspend fun say(text: String) {
        if (!spokenReplies) return
        val s = settings(); val v = engines.voice(s.voice)
        val a = withContext(Dispatchers.Default) { v.first.synth(text, v.second, s.speed.toFloat()) }
        setPhase(AgentPhase.SPEAKING); room.speak(Pcm.toShort(Pcm.resample(a, v.first.sampleRate, 24_000), s.volume.toFloat())); lastSpokeAt = System.currentTimeMillis()
        setPhase(if (s.mode == ListenMode.WAKE_WORD && !awake) AgentPhase.DORMANT else AgentPhase.LISTENING)
    }

    /** Stops speaking and thinking at once: queued audio is dropped, the generation cancelled. The conversation stays as it is. */
    fun interrupt(why: String?) {
        val active = reply?.isActive == true || speech?.isActive == true
        if (!active) return
        stopSpeaking()
        reply?.cancel(); core.agent.cancel(TurnOrigin.VOICE); core.agent.cancel(TurnOrigin.TEXT)
        if (why != null) { log("voice: interrupted ($why)"); scope.launch { room.sendData("clu.events", buildJsonObject { put("type", "interrupted"); put("why", why) }.toString().toByteArray()); setPhase(AgentPhase.LISTENING) } }
    }

    private fun stopSpeaking() { speech?.cancel(); room.clear() }

    private fun record(t: TurnTimings) {
        synchronized(timings) { timings.addLast(t); while (timings.size > 50) timings.removeFirst() }
        log("voice: turn ${t.id} ${t.outcome}: speech-end→first-audio ${t.speechEndToFirstAudioMs} ms (stt ${t.sttFinalizeMs}, first token ${t.firstTokenMs}, tts ${t.ttsFirstMs})${if (t.interrupted) ", interrupted" else ""}")
        log("voice: turn ${t.id} trace " + t.stages.joinToString(" | ") { "${it.name} +${it.ms}${if (it.detail.isNotEmpty()) " (${it.detail})" else ""}" })
        scope.launch { room.sendData("clu.metrics", buildJsonObject { put("speech_end_to_first_audio_ms", t.speechEndToFirstAudioMs); put("stt_finalize_ms", t.sttFinalizeMs); put("first_token_ms", t.firstTokenMs); put("tts_first_ms", t.ttsFirstMs); put("interrupted", t.interrupted)
            put("turn", t.id); put("outcome", t.outcome); putJsonArray("stages") { t.stages.forEach { st -> addJsonObject { put("name", st.name); put("ms", st.ms); if (st.detail.isNotEmpty()) put("detail", st.detail) } } } }.toString().toByteArray()) }
    }

    /**
     * CLU's state changes at once; telling the clients (a LiveKit attribute update, acknowledged by the server) happens
     * in the background, latest state first, so a turn never waits for it (it held a turn for ~5 s in the M8.5 trace).
     */
    private val phaseOut = Channel<AgentPhase>(Channel.CONFLATED)
    private val phaseSender = scope.launch { for (p in phaseOut) runCatching { room.setAttributes(mapOf("lk.agent.state" to p.wire, "clu.voice" to settings().voice, "clu.mode" to settings().mode.name.lowercase())) } }
    private suspend fun setPhase(p: AgentPhase) { phase = p; phaseOut.trySend(p) }

    private suspend fun transcript(text: String, final: Boolean) {
        room.sendText("lk.transcription", text, mapOf("lk.transcription_final" to final.toString(), "lk.segment_id" to segment, "lk.transcribed_track_id" to "mic"), to = listOfNotNull(listener), senderIdentity = listener)
        // (sent on behalf of the speaker, as LiveKit agents do: clients attribute a transcript by its sender)
    }
    private suspend fun transcriptReply(text: String, seg: String, final: Boolean) {
        if (text.isBlank()) return
        room.sendText("lk.transcription", text, mapOf("lk.transcription_final" to final.toString(), "lk.segment_id" to seg, "lk.transcribed_track_id" to room.trackSid))
    }

    @Volatile private var audioLoop: Job? = null

    /**
     * Ends the session. The voice detector and recogniser are native objects used only by the audio thread: they are freed
     * on that thread after its last frame, never while it may still be inside them (freeing them from here while a frame
     * was being processed crashed the JVM: SIGSEGV in SherpaOnnxVoiceActivityDetectorAcceptWaveform).
     */
    suspend fun close() {
        interrupt(null); frames.close(); phaseOut.close()
        withTimeoutOrNull(3_000) { audioLoop?.join() }
        withTimeoutOrNull(3_000) { runCatching { withContext(audio) { runCatching { stt?.close() }; runCatching { vad?.close() }; stt = null; vad = null } } } // if the audio thread were stuck: leak rather than crash
        room.disconnect(); audio.close()
    }
}
