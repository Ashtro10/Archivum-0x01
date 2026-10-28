package ai.clu.core.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private class Clip(val samples: FloatArray, val rate: Int)

/** Synthesised audio for one text chunk of one response. */
class AudioChunk(val responseId: Long, val seq: Int, val text: String, val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
}

/** Text-to-speech engine. Called on the synthesis worker only (engines need not be thread-safe). */
fun interface SpeechSynthesizer {
    /** Synthesises [text] in [lang] ("en"/"hi"); null if nothing could be produced. May throw. */
    fun synthesize(text: String, lang: String): Pair<FloatArray, Int>?
}

/**
 * Continuous audio output. Implementations keep one output stream running and play chunks back to back.
 * Contract: after [begin] with id N, chunks with any other response id are rejected (never played), and [stop]
 * silences output immediately and discards everything queued.
 */
interface SpeechSink {
    fun begin(responseId: Long)
    /** Queues [chunk] behind the previous one; blocks while the device buffer is full. False if rejected (stale/stopped). */
    fun play(chunk: AudioChunk, onFirstFrame: (() -> Unit)?): Boolean
    /** Blocks until everything queued for [responseId] has been heard (or output was stopped). */
    fun drain(responseId: Long)
    /** Silences output now and drops queued audio. */
    fun stop()
}

/** Timings of one spoken response, all in ms since the epoch of [StreamingSpeech.now] (0 = not reached). */
class TurnTimings(val responseId: Long) {
    @Volatile var userSpeechEnd = 0L
    @Volatile var transcriptFinal = 0L
    @Volatile var firstToken = 0L
    @Volatile var firstTtsRequest = 0L
    @Volatile var firstAudio = 0L
    @Volatile var lastTokenAt = 0L
    @Volatile var interruptedAt = 0L
    @Volatile var silentAt = 0L
    @Volatile var cancelDoneAt = 0L
    val chunkGapsMs = java.util.Collections.synchronizedList(mutableListOf<Long>())
    val chunkSynthMs = java.util.Collections.synchronizedList(mutableListOf<Long>())
    val errors = AtomicInteger()
    @Volatile var chunks = 0
    /** Length of the first text chunk sent to the synthesiser (characters). */
    @Volatile var firstChunkChars = 0

    private fun d(a: Long, b: Long) = if (a > 0 && b > 0 && b >= a) (b - a).toDouble() else null

    /** The measurements requested for the voice pipeline, by name. Missing stages are omitted. */
    fun metrics(): Map<String, Double> = buildMap {
        d(userSpeechEnd, transcriptFinal)?.let { put("speech_end_to_transcript_ms", it) }
        d(transcriptFinal, firstToken)?.let { put("transcript_to_first_token_ms", it) }
        d(userSpeechEnd, firstToken)?.let { put("speech_end_to_first_token_ms", it) }
        d(firstToken, firstTtsRequest)?.let { put("first_token_to_first_tts_ms", it) }
        d(firstTtsRequest, firstAudio)?.let { put("first_tts_to_first_audio_ms", it) }
        d(userSpeechEnd, firstAudio)?.let { put("speech_end_to_first_audio_ms", it) }
        d(interruptedAt, silentAt)?.let { put("interrupt_to_silence_ms", it) }
        d(interruptedAt, cancelDoneAt)?.let { put("cancel_complete_ms", it) }
        if (chunkGapsMs.isNotEmpty()) put("max_chunk_gap_ms", chunkGapsMs.max().toDouble())
        if (firstAudio > 0 && lastTokenAt > 0) put("audio_started_before_generation_end_ms", (lastTokenAt - firstAudio).toDouble())
        // First TTS request -> first audio, split: synthesising the first chunk, then the time until it was heard.
        chunkSynthMs.firstOrNull()?.let { synth ->
            put("first_chunk_synth_ms", synth.toDouble())
            if (firstChunkChars > 0) put("first_chunk_chars", firstChunkChars.toDouble())
            d(firstTtsRequest + synth, firstAudio)?.let { put("first_audio_after_synth_ms", it) }
        }
    }
}

/**
 * Real-time speech output for streamed LLM replies: text deltas -> [AdaptiveChunker] -> synthesis worker ->
 * bounded audio queue -> playback worker -> [SpeechSink]. Generation, synthesis and playback run concurrently;
 * the first chunk is spoken while the model is still generating.
 *
 * Every response has an id. Starting a new response or [interrupt] cancels the previous one: its workers are
 * cancelled, the sink is stopped, and the sink rejects any chunk carrying an old id, so a cancelled worker can
 * never play into a new response. The audio queue holds at most [audioQueue] chunks so synthesis cannot run far
 * ahead of playback (bounded latency, less CPU contention with the model).
 */
class StreamingSpeech(
    private val synth: SpeechSynthesizer,
    private val sink: SpeechSink,
    private val synthDispatcher: CoroutineDispatcher,
    private val playDispatcher: CoroutineDispatcher,
    val now: () -> Long = System::currentTimeMillis,
    private val chunker: () -> AdaptiveChunker = { AdaptiveChunker() },
    private val audioQueue: Int = 2,
    private val stallFlushMs: Long = 350,
    /** When the queued audio ends within this time and nothing else is being synthesised, speak pending words now. */
    private val underrunMs: Long = 400,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val ids = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var current: Response? = null
        private set
    /** Chunks a sink rejected because they belonged to an old response (prevented stale audio). */
    val staleRejected = AtomicInteger()

    /** Starts a new spoken response, cancelling any previous one. [onCancel] stops the producer (e.g. LLM generation). */
    fun start(timings: TurnTimings? = null, onCancel: () -> Unit = {}): Response {
        current?.cancel(reason = "superseded")
        val id = ids.incrementAndGet()
        val r = Response(id, timings ?: TurnTimings(id), onCancel)
        sink.begin(id)
        current = r
        r.launch()
        return r
    }

    /** User barge-in: silence now and cancel synthesis, queued audio and generation. Returns the response's timings. */
    fun interrupt(): TurnTimings? = current?.let { it.cancel("interrupted"); it.timings }

    val speaking: Boolean get() = current?.let { !it.done } ?: false

    fun shutdown() { current?.cancel("shutdown"); scope.cancel() }

    inner class Response internal constructor(val id: Long, val timings: TurnTimings, private val onCancel: () -> Unit) {
        private val text = Channel<Any>(Channel.UNLIMITED) // String chunks or prepared Clips; the bounded queue is the audio one
        private val audio = Channel<AudioChunk>(audioQueue)
        private val chunks = chunker()
        private val seq = AtomicInteger()
        @Volatile var cancelled = false; private set
        @Volatile var done = false; private set
        @Volatile private var finished = false
        @Volatile private var lastDeltaAt = 0L
        @Volatile private var audioEndsAt = 0L   // estimated end of everything handed to the sink
        private val inFlight = AtomicInteger()   // chunks emitted by the chunker but not yet played or skipped
        private val lock = Any()
        private lateinit var job: Job
        val completion = CompletableDeferred<Unit>()
        /** Called (on the audio side) when the first audio of this response is heard. */
        @Volatile var onFirstAudio: (() -> Unit)? = null

        internal fun launch() {
            job = scope.launch {
                val synthJob = launch(synthDispatcher) { synthLoop() }
                val playJob = launch(playDispatcher) { playLoop() }
                val stallJob = launch { stallLoop() }
                synthJob.join(); playJob.join(); stallJob.cancel()
            }
            job.invokeOnCompletion { done = true; completion.complete(Unit) }
        }

        /** Streamed text from the model. */
        fun text(delta: String) {
            if (cancelled || finished || delta.isEmpty()) return
            val t = now()
            if (timings.firstToken == 0L) timings.firstToken = t
            lastDeltaAt = t; timings.lastTokenAt = t
            emit(synchronized(lock) { chunks.push(delta) })
        }

        private fun emit(ready: List<String>) = ready.forEach { inFlight.incrementAndGet(); text.trySend(it) }

        /** Plays prepared audio (e.g. an acknowledgement tone) in order with the speech of this response. */
        fun sound(samples: FloatArray, rate: Int) {
            if (cancelled || finished) return
            emit(synchronized(lock) { chunks.flush() }) // keep the order: text before the sound is spoken first
            inFlight.incrementAndGet(); text.trySend(Clip(samples, rate))
        }

        /** Speaks a complete text (template replies, greetings) through the same pipeline. */
        fun say(full: String) { text(full); finish() }

        /** The model finished: speak what is left, then end the response after playback. */
        fun finish() {
            if (cancelled || finished) return
            finished = true
            if (timings.lastTokenAt == 0L) timings.lastTokenAt = now()
            emit(synchronized(lock) { chunks.flush() })
            text.close()
        }

        /** Stops this response immediately. Safe to call from any thread, more than once. */
        fun cancel(reason: String = "cancelled") {
            if (cancelled || done) return
            cancelled = true
            timings.interruptedAt = now()
            sink.stop()                    // silence first
            timings.silentAt = now()
            runCatching(onCancel)          // stop generation
            text.cancel(); audio.cancel()  // drop pending text and audio
            if (::job.isInitialized) job.cancel(CancellationException(reason))
            scope.launch { if (::job.isInitialized) job.join(); timings.cancelDoneAt = now() }
        }

        private suspend fun stallLoop() {
            // Speak pending words early when (a) the model pauses mid-phrase (tool call, slow token), or (b) playback
            // is about to run dry while the model is slower than speech: a short complete-word chunk now beats a gap.
            while (currentCoroutineContext().isActive && !finished) {
                delay(50)
                if (lastDeltaAt == 0L) continue
                val t = now()
                val starving = timings.firstAudio > 0 && inFlight.get() == 0 && audioEndsAt - t < underrunMs
                if (starving) emit(synchronized(lock) { chunks.flushIfStalled(minWords = 3, avoidFunctionWordEnd = true) })
                else if (t - lastDeltaAt >= stallFlushMs) emit(synchronized(lock) { chunks.flushIfStalled() })
            }
        }

        private suspend fun synthLoop() {
            try {
                for (item in text) {
                    if (cancelled) break
                    if (item is Clip) { audio.send(AudioChunk(id, seq.getAndIncrement(), "", item.samples, item.rate)); continue }
                    val chunk = item as String
                    val t0 = now()
                    if (timings.firstTtsRequest == 0L) { timings.firstTtsRequest = t0; timings.firstChunkChars = chunk.length }
                    val lang = AdaptiveChunker.script(chunk)
                    val pcm = try { synth.synthesize(chunk, lang) } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        timings.errors.incrementAndGet(); onError("synthesis failed for chunk ${seq.get()}", e); null
                    }
                    timings.chunkSynthMs += now() - t0
                    currentCoroutineContext().ensureActive()
                    if (cancelled || pcm == null || pcm.first.isEmpty()) { inFlight.decrementAndGet(); continue }
                    audio.send(AudioChunk(id, seq.getAndIncrement(), chunk, pcm.first, pcm.second)) // suspends when the queue is full
                }
            } finally {
                audio.close()
            }
        }

        private suspend fun playLoop() {
            var expectedEnd = 0L // when the previous chunk finishes playing (estimated from durations)
            for (c in audio) {
                if (cancelled) break
                val accepted = runInterruptible {
                    sink.play(c, if (timings.firstAudio == 0L) ({ if (timings.firstAudio == 0L) { timings.firstAudio = now(); onFirstAudio?.invoke() } }) else null)
                }
                if (!accepted) { if (!cancelled) staleRejected.incrementAndGet(); break }
                val t = now()
                if (expectedEnd > 0 && t > expectedEnd) timings.chunkGapsMs += t - expectedEnd // starvation gap
                expectedEnd = maxOf(t, expectedEnd) + c.durationMs
                audioEndsAt = expectedEnd
                inFlight.decrementAndGet()
                timings.chunks++
            }
            if (!cancelled) runInterruptible { sink.drain(id) }
        }
    }
}

/**
 * Decides when owner speech during Clu's playback is a real interruption, not Clu's own voice leaking back into
 * the microphone. Needs [minSpeechMs] of continuous voice activity whose level exceeds the echo floor (the
 * microphone level measured while Clu speaks and the VAD reports no speech) by [marginDb].
 */
class BargeInDetector(private val minSpeechMs: Int = 200, private val marginDb: Double = 6.0) {
    private var speechMs = 0
    private var echoDb = -90.0
    private var hasEcho = false
    var lastDecisionAt = 0L; private set

    fun reset() { speechMs = 0 }

    /** Feeds one microphone frame captured while Clu is speaking; true when an interruption should happen now. */
    fun onFrame(vadSpeech: Boolean, levelDb: Double, frameMs: Int, now: Long): Boolean {
        if (!vadSpeech) {
            // Learn the echo floor from non-speech frames (slow rise, fast fall).
            echoDb = if (!hasEcho) levelDb else if (levelDb > echoDb) echoDb * 0.9 + levelDb * 0.1 else echoDb * 0.7 + levelDb * 0.3
            hasEcho = true
            speechMs = 0
            return false
        }
        val loudEnough = !hasEcho || levelDb >= echoDb + marginDb
        speechMs = if (loudEnough) speechMs + frameMs else 0
        if (speechMs >= minSpeechMs) { speechMs = 0; lastDecisionAt = now; return true }
        return false
    }

    val echoFloorDb get() = echoDb
}

/**
 * One voice turn as a privacy-safe line of stage durations (Diagnostics → Performance → recent voice turns). Numbers and
 * stage names only, never text. Stages, each measured from the end of the previous one:
 * end of speech (VAD endpoint) → recognised text → request accepted → model ready (from request start) → prompt built →
 * waiting for the model thread → prompt processed → first word → first chunk sent to the synthesiser → first chunk
 * synthesised → audible. [stats] are the reply's agent statistics (see CluAgent: request_id, model_ready_ms,
 * prompt_build_ms, queue_ms, prompt_ms, prefill_cause, reused/prompt tokens).
 */
object VoiceTurnTimeline {
    fun line(voiceTurn: Long, endpoint: Long, accepted: Long, t: TurnTimings, stats: Map<String, Double>, causes: List<String>, outcome: String): String {
        fun ms(v: Long?) = if (v == null || v < 0) "–" else v.toString()
        fun d(a: Long, b: Long) = if (a > 0 && b > 0 && b >= a) b - a else null
        fun st(k: String) = stats[k]?.toLong()
        val synth = t.chunkSynthMs.firstOrNull()
        val cause = stats["prefill_cause"]?.toInt()?.let { causes.getOrNull(it) } ?: "–"
        val fresh = st("prompt_tokens")?.let { p -> st("reused_tokens")?.let { p - it } }
        return buildString {
            append("voice turn $voiceTurn")
            st("request_id")?.let { append(" · request #$it") }
            append(" · $outcome")
            append(" | asr ${ms(d(endpoint, t.transcriptFinal))}")
            append(" · accept ${ms(d(t.transcriptFinal, accepted))}")
            append(" · model ready ${ms(st("model_ready_ms"))}")
            append(" · build ${ms(st("prompt_build_ms"))}")
            append(" · queue ${ms(st("queue_ms"))}")
            append(" · prefill ${ms(st("prompt_ms"))} ($cause, new ${fresh ?: "–"} of ${st("prompt_tokens") ?: "–"})")
            append(" · first word ${ms(d(t.transcriptFinal, t.firstToken))}")
            append(" · tts request ${ms(d(t.firstToken, t.firstTtsRequest))}")
            append(" · synth ${ms(synth)}${if (t.firstChunkChars > 0) " (${t.firstChunkChars} chars)" else ""}")
            append(" · audible ${ms(if (synth != null) d(t.firstTtsRequest + synth, t.firstAudio) else null)}")
            append(" | end of speech → audio ${ms(d(endpoint, t.firstAudio))}")
        }
    }
}
