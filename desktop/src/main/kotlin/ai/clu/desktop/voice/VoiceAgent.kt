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

/** One turn's timings, measured on the PC (ms). */
data class TurnTimings(val at: Long, val text: String, val sttFinalizeMs: Long, val firstTokenMs: Long, val ttsFirstMs: Long, val speechEndToFirstAudioMs: Long, val interrupted: Boolean)

/**
 * CLU in one LiveKit room: hears the owner's microphone (16 kHz), finds the end of each utterance (Silero VAD), recognises
 * it while it is spoken (streaming STT, partial transcripts sent to the client), passes the final text to the one
 * conversation manager (CluAgent: the same history, memory and tools as typed chat), and speaks the reply through the
 * selected voice sentence by sentence as it is generated. Speaking over CLU interrupts it: playback stops, queued audio is
 * dropped and the generation is cancelled, and the new utterance is handled with the conversation intact.
 */
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
        scope.launch(audio) { for (f in frames) runCatching { onFrame(f) }.onFailure { lastError = it.message ?: "audio error"; log("voice: audio error ${it.message}") } }
        listOf("clu.interrupt", "clu.set_audio_output", "clu.load_history", "clu.set_mic", "clu.push_to_talk").forEach(room::registerRpc)
        scope.launch { setPhase(if (settings().mode == ListenMode.WAKE_WORD) AgentPhase.DORMANT else AgentPhase.LISTENING) }
    }

    /** Called by the room for every event. */
    fun onEvent(e: LkEvent) {
        when (e) {
            is LkEvent.AudioTrack -> if (listener == null || listener == e.participant) { listener = e.participant; room.listen(e.participant, e.trackHandle) { frames.trySend(it) }; log("voice: listening to ${e.participant}") }
            is LkEvent.TrackGone -> if (e.participant == listener) { room.stopListening(e.participant); listener = null }
            is LkEvent.Left -> if (e.participant == listener) { room.stopListening(e.participant); listener = null; interrupt("the listener left") }
            is LkEvent.Text -> if (e.topic == "lk.chat" && e.text.isNotBlank()) scope.launch { startTurn(e.text.trim(), typed = true, speechEndAt = 0, sttMs = 0) }
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
        inSpeech = true; speechStartedAt = System.currentTimeMillis(); lastPartial = ""; segment = "u" + turnSeq.incrementAndGet()
        val rec = stt ?: engines.recognizer().also { stt = it }
        // Prime with 1 s of silence (stable features for the streaming model), then the pre-roll.
        val pre = preRoll.fold(FloatArray(16000)) { a, b -> a + b }; preRoll.clear()
        rec.begin(pre)
    }

    private fun endUtterance() {
        inSpeech = false
        val endAt = System.currentTimeMillis()
        val text = stt?.finish().orEmpty(); val sttMs = System.currentTimeMillis() - endAt
        if (text.isBlank()) return
        lastHeardAt = endAt
        scope.launch { onUtterance(text, endAt - settings().endSilenceMs, sttMs) }
    }

    private suspend fun onUtterance(heard: String, speechEndAt: Long, sttMs: Long) {
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
        if (s.isFarewell(text)) {
            log("voice: farewell heard")
            if (s.mode == ListenMode.WAKE_WORD) { awake = false; say("Goodbye."); setPhase(AgentPhase.DORMANT) } else say("Goodbye.")
            return
        }
        startTurn(text, typed = false, speechEndAt = speechEndAt, sttMs = sttMs)
    }

    private fun lastSpokeOrHeard() = maxOf(lastHeardAt, lastSpokeAt)
    @Volatile private var lastSpokeAt = 0L

    // ------------------------------------------------------------------------------------------------ thinking and speaking
    private suspend fun startTurn(text: String, typed: Boolean, speechEndAt: Long, sttMs: Long) {
        interrupt(null) // a new turn replaces anything still running
        val conv = conversation()
        setPhase(AgentPhase.THINKING)
        val sentences = Channel<String>(capacity = 32)
        val seg = "r" + turnSeq.incrementAndGet()
        val started = System.currentTimeMillis(); var firstToken = 0L; var firstAudio = 0L; var interrupted = false
        val replyText = StringBuilder()
        speech = scope.launch {
            var spokeAny = false
            for (chunk in sentences) {
                val s = settings()
                if (!spokenReplies) { transcriptReply(chunk, seg, final = false); continue }
                val v = engines.voice(s.voice)
                val raw = SpeechText.forSpeech(s.pronounce(chunk)).ifBlank { continue }
                val synth = withContext(Dispatchers.Default) { v.first.synth(raw, v.second, s.speed.toFloat()) }
                if (synth.isEmpty()) continue
                val paced = SpeechText.withPause(chunk, synth, v.first.sampleRate)
                val pcm = Pcm.toShort(Pcm.resample(paced, v.first.sampleRate, 24_000), s.volume.toFloat())
                if (!spokeAny) { spokeAny = true; firstAudio = System.currentTimeMillis(); setPhase(AgentPhase.SPEAKING) }
                transcriptReply(chunk, seg, final = false)
                room.speak(pcm) { !isActive }
                lastSpokeAt = System.currentTimeMillis()
            }
        }
        reply = scope.launch {
            val chunker = AdaptiveChunker()
            try {
                core.llm.prepare()
                core.agent.respond(TurnRequest(conv, text, Actor(null, PersonRole.OWNER, viaVoice = !typed), viaVoice = !typed, origin = if (typed) TurnOrigin.TEXT else TurnOrigin.VOICE)).collect { e ->
                    when (e) {
                        is AgentEvent.Text -> { if (firstToken == 0L) firstToken = System.currentTimeMillis(); replyText.append(e.delta); chunker.push(e.delta).forEach { sentences.send(it) } }
                        is AgentEvent.ResetText -> { replyText.setLength(0) }
                        is AgentEvent.Error -> { replyText.setLength(0); replyText.append(e.text); sentences.send(e.text) }
                        is AgentEvent.Done -> { chunker.flush().forEach { sentences.send(it) }; if (replyText.isEmpty() && e.text.isNotBlank()) { replyText.append(e.text); sentences.send(e.text) } }
                        else -> {}
                    }
                }
            } catch (c: CancellationException) { interrupted = true; throw c
            } catch (e: Exception) { lastError = e.message ?: "reply failed"; sentences.trySend("Sorry, something went wrong with that answer.")
            } finally { sentences.close() }
            speech?.join()
            transcriptReply(replyText.toString(), seg, final = true)
            val endToAudio = if (speechEndAt > 0 && firstAudio > 0) firstAudio - speechEndAt else -1
            record(TurnTimings(System.currentTimeMillis(), text.take(60), sttMs, if (firstToken > 0) firstToken - started else -1, if (firstAudio > 0 && firstToken > 0) firstAudio - firstToken else -1, endToAudio, interrupted))
            if (phase == AgentPhase.SPEAKING || phase == AgentPhase.THINKING) setPhase(if (settings().mode == ListenMode.WAKE_WORD && !awake) AgentPhase.DORMANT else AgentPhase.LISTENING)
        }
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
        log("voice: turn speech-end→first-audio ${t.speechEndToFirstAudioMs} ms (stt ${t.sttFinalizeMs}, first token ${t.firstTokenMs}, tts ${t.ttsFirstMs})${if (t.interrupted) ", interrupted" else ""}")
        scope.launch { room.sendData("clu.metrics", buildJsonObject { put("speech_end_to_first_audio_ms", t.speechEndToFirstAudioMs); put("stt_finalize_ms", t.sttFinalizeMs); put("first_token_ms", t.firstTokenMs); put("tts_first_ms", t.ttsFirstMs); put("interrupted", t.interrupted) }.toString().toByteArray()) }
    }

    private suspend fun setPhase(p: AgentPhase) { phase = p; room.setAttributes(mapOf("lk.agent.state" to p.wire, "clu.voice" to settings().voice, "clu.mode" to settings().mode.name.lowercase())) }

    private suspend fun transcript(text: String, final: Boolean) {
        room.sendText("lk.transcription", text, mapOf("lk.transcription_final" to final.toString(), "lk.segment_id" to segment, "lk.transcribed_track_id" to "mic"), to = listOfNotNull(listener), senderIdentity = listener)
        // (sent on behalf of the speaker, as LiveKit agents do: clients attribute a transcript by its sender)
    }
    private suspend fun transcriptReply(text: String, seg: String, final: Boolean) {
        if (text.isBlank()) return
        room.sendText("lk.transcription", text, mapOf("lk.transcription_final" to final.toString(), "lk.segment_id" to seg, "lk.transcribed_track_id" to room.trackSid))
    }

    suspend fun close() {
        interrupt(null); frames.close()
        runCatching { stt?.close() }; runCatching { vad?.close() }
        room.disconnect(); audio.close()
    }
}
