package ai.clu.core

import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.voice.*
import java.io.File
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Real-time simulation of the voice output pipeline: model token stream, TTS worker and a playing sink. */
class StreamingSpeechTest {
    private fun t() = System.nanoTime() / 1_000_000

    /** Sink that "plays" in real time: tracks what is heard, enforces the stale-id contract, records violations. */
    class SimSink(private val bufferMs: Long = 120) : SpeechSink {
        data class Heard(val responseId: Long, val seq: Int, val from: Long, var to: Long, val text: String)
        val heard = CopyOnWriteArrayList<Heard>()
        @Volatile private var current = -1L
        @Volatile private var playEnd = 0L // when queued audio finishes
        @Volatile var stops = 0
        private fun now() = System.nanoTime() / 1_000_000
        override fun begin(responseId: Long) { current = responseId }
        private val lock = Any()
        override fun play(chunk: AudioChunk, onFirstFrame: (() -> Unit)?): Boolean {
            if (chunk.responseId != current) return false
            // Block while more than bufferMs is queued ahead (like AudioTrack.write in blocking mode).
            while (playEnd - now() > bufferMs) { if (chunk.responseId != current) return false; Thread.sleep(2) }
            val start: Long
            synchronized(lock) { // the id check and the enqueue are atomic with respect to stop()
                if (chunk.responseId != current) return false
                start = maxOf(now(), playEnd)
                playEnd = start + chunk.durationMs
                heard += Heard(chunk.responseId, chunk.seq, start, playEnd, chunk.text)
            }
            if (onFirstFrame != null) Thread { while (now() < start) Thread.sleep(1); onFirstFrame() }.start()
            return true
        }
        override fun drain(responseId: Long) { while (now() < playEnd && current == responseId) Thread.sleep(2) }
        override fun stop() = synchronized(lock) {
            stops++
            val n = now()
            heard.forEach { if (it.to > n) it.to = n } // everything still playing or queued is cut off now (to < from: never heard)
            playEnd = n
            current = -2L - stops // reject everything until the next begin()
        }
        /** Audio from [id] that was audible after [at]. */
        fun audibleAfter(id: Long, at: Long) = heard.filter { it.responseId == id && it.to > at + 1 }
    }

    /** TTS: 25 ms + 1.5 ms/char, [msPerWord] of audio per word (~2.9 words/s, normal speech) at 16 kHz; can fail on demand. */
    class SimSynth(val failOn: (String) -> Boolean = { false }, val baseMs: Long = 25, val msPerWord: Int = 340) : SpeechSynthesizer {
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        override fun synthesize(text: String, lang: String): Pair<FloatArray, Int>? {
            calls += text to lang
            Thread.sleep(baseMs + (text.length * 1.5).toLong())
            if (failOn(text)) throw IllegalStateException("synthetic TTS failure")
            val words = AdaptiveChunker.wordCount(text)
            return FloatArray(16 * msPerWord * words) { 0.1f } to 16_000
        }
    }

    /** Model: emits [text] word by word every [msPerWord] on its own thread until finished or cancelled. */
    class SimLlm(private val text: String, private val msPerWord: Long = 60) {
        val cancelled = AtomicBoolean(false)
        @Volatile var finishedAt = 0L
        fun run(r: StreamingSpeech.Response) = Thread {
            for (w in text.split(" ")) { if (cancelled.get()) return@Thread; Thread.sleep(msPerWord); r.text("$w ") }
            if (!cancelled.get()) { finishedAt = System.nanoTime() / 1_000_000; r.finish() }
        }.apply { start() }
    }

    private val synthPool = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val playPool = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    @AfterEach fun close() { synthPool.close(); playPool.close() }

    private fun pipeline(sink: SpeechSink, synth: SpeechSynthesizer, errors: MutableList<String> = mutableListOf()) =
        StreamingSpeech(synth, sink, synthPool, playPool, now = { t() }, onError = { m, _ -> errors += m })

    private val LONG = "Of course. I looked into the maintenance schedule and the next inspection is due on Monday morning at nine, " +
        "so I have added a reminder for Sunday evening. The technician will need access to the main panel, and the power " +
        "will be off for about twenty minutes while they replace the old breaker. Everything else looks normal."

    private fun await(ms: Long = 10_000, cond: () -> Boolean) {
        val end = t() + ms
        while (t() < end) { if (cond()) return; Thread.sleep(5) }
        fail("condition not met in $ms ms")
    }

    @Test fun longAnswerStartsSpeakingBeforeGenerationFinishes() {
        val sink = SimSink(); val synth = SimSynth()
        val sp = pipeline(sink, synth)
        val timings = TurnTimings(0).apply { userSpeechEnd = t(); transcriptFinal = t() }
        val llm = SimLlm(LONG)
        val r = sp.start(timings) { llm.cancelled.set(true) }
        llm.run(r)
        runBlocking { withTimeout(30_000) { r.completion.await() } }
        assertTrue(timings.firstAudio in 1 until llm.finishedAt, "first audio ${timings.firstAudio} before generation end ${llm.finishedAt}")
        val m = timings.metrics()
        println("long answer: $m, chunks=${timings.chunks}, synth=${timings.chunkSynthMs}")
        assertTrue(m.getValue("audio_started_before_generation_end_ms") > 1000, "spoke >1 s before the model finished")
        assertTrue(m.getValue("first_token_to_first_tts_ms") < 600, "first chunk requested within ~4 words")
        assertEquals(LONG, sink.heard.sortedBy { it.seq }.joinToString(" ") { it.text }, "every word heard once, in order")
        assertTrue(timings.chunkGapsMs.all { it < 50 }, "no starvation gaps: ${timings.chunkGapsMs}")
        assertTrue(sink.heard.zipWithNext().all { (a, b) -> b.from >= a.to }, "no overlapping/duplicated audio")
    }

    @Test fun modelSlowerThanSpeechKeepsGapsShort() {
        // ~4 words/s from the model vs ~2.9 words/s of speech, with an uneven start: pending words are spoken early
        // instead of waiting for a clause boundary, so playback rarely runs dry.
        val sink = SimSink(); val synth = SimSynth()
        val sp = pipeline(sink, synth)
        val text = LONG.split(" ").take(40).joinToString(" ")
        val llm = SimLlm(text, msPerWord = 250)
        val r = sp.start { llm.cancelled.set(true) }
        llm.run(r)
        runBlocking { withTimeout(40_000) { r.completion.await() } }
        println("slow model: ${r.timings.metrics()}, gaps=${r.timings.chunkGapsMs}, chunks=${sink.heard.map { it.text }}")
        assertEquals(text, sink.heard.joinToString(" ") { it.text })
        assertTrue(r.timings.chunkGapsMs.all { it < 300 }, "gaps ${r.timings.chunkGapsMs}")
    }

    private fun interruptAfter(firstChunkDone: Boolean) {
        val sink = SimSink(); val synth = SimSynth()
        val sp = pipeline(sink, synth)
        val llm = SimLlm(LONG)
        val r = sp.start { llm.cancelled.set(true) }
        llm.run(r)
        if (firstChunkDone) await { sink.heard.size >= 3 && t() > sink.heard[1].from } else await { r.timings.firstAudio > 0 }
        val tim = sp.interrupt()!!
        val silenceMs = tim.silentAt - tim.interruptedAt
        assertTrue(silenceMs < 100, "silent ${silenceMs} ms after interruption")
        assertTrue(llm.cancelled.get(), "model generation cancelled")
        runBlocking { withTimeout(2_000) { r.completion.await() } }
        Thread.sleep(400)
        assertTrue(sink.audibleAfter(r.id, tim.silentAt).isEmpty(), "no audio of the interrupted response after silence")
        // The next response plays normally and only its own audio.
        val r2 = sp.start(); r2.say("Yes? I'm listening.")
        runBlocking { withTimeout(5_000) { r2.completion.await() } }
        assertTrue(sink.heard.any { it.responseId == r2.id }, "follow-up spoken")
        assertTrue(sink.heard.filter { it.to > tim.silentAt + 1 }.all { it.responseId == r2.id }, "no stale audio in the follow-up")
    }

    @Test fun interruptionDuringFirstChunk() = interruptAfter(firstChunkDone = false)
    @Test fun interruptionDuringLaterChunk() = interruptAfter(firstChunkDone = true)

    @Test fun repeatedInterruptionsAndRapidFollowUps() {
        val sink = SimSink(); val synth = SimSynth()
        val sp = pipeline(sink, synth)
        val ids = mutableListOf<Long>()
        repeat(6) { i ->
            val llm = SimLlm(LONG, msPerWord = 20)
            val r = sp.start { llm.cancelled.set(true) }
            ids += r.id
            llm.run(r)
            Thread.sleep(listOf(40L, 150, 400, 10, 700, 90)[i]) // interrupt at varied points, some before any audio
            sp.interrupt()
        }
        val tLast = t()
        val final = sp.start(); final.say("Here is the answer to your last question.")
        runBlocking { withTimeout(5_000) { final.completion.await() } }
        Thread.sleep(200)
        val after = sink.heard.filter { it.to > tLast + 5 }
        assertTrue(after.all { it.responseId == final.id }, "only the latest response audible: ${after.map { it.responseId }}")
        assertEquals("Here is the answer to your last question.", sink.heard.filter { it.responseId == final.id }.joinToString(" ") { it.text })
    }

    @Test fun cancellationWhileSynthesising() {
        val sink = SimSink()
        val started = java.util.concurrent.CountDownLatch(1)
        val slow = object : SpeechSynthesizer {
            override fun synthesize(text: String, lang: String): Pair<FloatArray, Int>? {
                started.countDown(); Thread.sleep(400) // long synthesis in progress when the owner interrupts
                return FloatArray(16_000) { 0.1f } to 16_000
            }
        }
        val sp = pipeline(sink, slow)
        val r = sp.start()
        r.say("This sentence is being synthesised when the interruption arrives.")
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val tInt = t()
        sp.interrupt()
        runBlocking { withTimeout(2_000) { r.completion.await() } }
        Thread.sleep(600) // the synthesis call returns after the interruption; its audio must be discarded
        assertTrue(sink.heard.none { it.responseId == r.id }, "audio finished after cancellation was not played: ${sink.heard}")
        assertTrue(r.timings.cancelDoneAt == 0L || r.timings.cancelDoneAt >= tInt)
    }

    @Test fun ttsErrorSkipsChunkAndPipelineRecovers() {
        val sink = SimSink(); val errors = mutableListOf<String>()
        val synth = SimSynth(failOn = { "inspection" in it })
        val sp = pipeline(sink, synth, errors)
        val llm = SimLlm(LONG, msPerWord = 15)
        // (plays the whole answer: ~20 s of simulated speech)
        val r = sp.start { llm.cancelled.set(true) }
        llm.run(r)
        runBlocking { withTimeout(30_000) { r.completion.await() } }
        assertEquals(1, r.timings.errors.get())
        assertTrue(errors.single().startsWith("synthesis failed"))
        val heard = sink.heard.filter { it.responseId == r.id }.joinToString(" ") { it.text }
        assertFalse("inspection" in heard); assertTrue("Everything else looks normal." in heard, "later chunks still spoken")
        val r2 = sp.start(); r2.say("All good again.")
        runBlocking { withTimeout(5_000) { r2.completion.await() } }
        assertTrue(sink.heard.any { it.responseId == r2.id }, "next response unaffected")
    }

    @Test fun hindiAndMixedChunksAreRoutedByScript() {
        val sink = SimSink(); val synth = SimSynth()
        val sp = pipeline(sink, synth)
        val r = sp.start()
        r.say("ठीक है, मैं अभी देखता हूँ। The meeting is at nine. कल सुबह नौ बजे याद दिला दूँगा।")
        runBlocking { withTimeout(10_000) { r.completion.await() } }
        assertEquals(listOf("hi", "en", "hi"), synth.calls.map { it.second })
    }

    @Test fun soundsPlayInOrderWithSpeech() {
        val sink = SimSink(); val synth = SimSynth()
        val sp = pipeline(sink, synth)
        val r = sp.start()
        r.sound(FloatArray(2_880) { 0.2f }, 16_000) // 180 ms tone
        r.say("Good evening. All systems are ready.")
        runBlocking { withTimeout(10_000) { r.completion.await() } }
        assertEquals(listOf("", "Good evening.", "All systems are ready."), sink.heard.map { it.text })
        assertEquals(180, sink.heard[0].to - sink.heard[0].from)
    }

    @Test fun generationStallFlushesPendingWords() {
        val sink = SimSink(); val synth = SimSynth()
        val sp = pipeline(sink, synth)
        val r = sp.start()
        r.text("Let me think ") // the model pauses (e.g. a tool call) without a boundary
        await(3_000) { synth.calls.isNotEmpty() }
        assertEquals("Let me think", synth.calls.first().first)
        r.text("about that for a second."); r.finish()
        runBlocking { withTimeout(5_000) { r.completion.await() } }
    }

    @Test fun bargeInIgnoresEchoAndCatchesRealSpeech() {
        val d = BargeInDetector(minSpeechMs = 200, marginDb = 6.0)
        var t = 0L
        // Clu's own voice leaking back at about -40 dBFS, sometimes mis-flagged as speech by the VAD.
        repeat(30) { assertFalse(d.onFrame(vadSpeech = it % 5 == 0, levelDb = -40.0 + (it % 3), frameMs = 32, now = t)); t += 32 }
        repeat(10) { assertFalse(d.onFrame(vadSpeech = true, levelDb = -39.0, frameMs = 32, now = t)); t += 32 } // echo flagged as speech: still ignored
        // The owner speaks over it, clearly louder.
        val decisions = (0 until 10).map { d.onFrame(vadSpeech = true, levelDb = -25.0, frameMs = 32, now = t).also { t += 32 } }
        assertEquals(6, decisions.indexOf(true), "interrupts after ~200 ms of real speech (frame ${decisions.indexOf(true)})")
    }

    /**
     * Repeated runs for percentiles (opt-in: -Dclu.bench=1). Simulated model/TTS/sink, real threads and timing: measures
     * the pipeline's own scheduling overhead, interruption path and stale-audio guard - not device audio latency.
     */
    @Test fun benchmarkPipelineOverhead() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("clu.bench") != null, "benchmark not requested")
        val firstTtsToAudio = mutableListOf<Double>(); val interruptToSilence = mutableListOf<Double>()
        val cancelComplete = mutableListOf<Double>(); val maxGaps = mutableListOf<Double>(); var stale = 0
        val rnd = java.util.Random(7)
        repeat(30) { i ->
            val sink = SimSink(); val sp = pipeline(sink, SimSynth())
            val llm = SimLlm(LONG, msPerWord = 40)
            val r = sp.start { llm.cancelled.set(true) }
            llm.run(r)
            if (i % 2 == 0) {
                runBlocking { withTimeout(40_000) { r.completion.await() } }
                r.timings.metrics()["max_chunk_gap_ms"]?.let { maxGaps += it }
            } else {
                Thread.sleep(200L + rnd.nextInt(1500))
                val tim = sp.interrupt()!!
                runBlocking { withTimeout(5_000) { r.completion.await() } }
                Thread.sleep(150)
                interruptToSilence += (tim.silentAt - tim.interruptedAt).toDouble()
                tim.metrics()["cancel_complete_ms"]?.let { cancelComplete += it }
                stale += sink.audibleAfter(r.id, tim.silentAt).size
            }
            r.timings.metrics()["first_tts_to_first_audio_ms"]?.let { firstTtsToAudio += it }
            sp.shutdown()
        }
        fun pct(v: List<Double>) = mapOf("n" to v.size.toDouble(), "p50" to DiagnosticsLog.percentile(v.sorted(), 50),
            "p90" to DiagnosticsLog.percentile(v.sorted(), 90), "p99" to DiagnosticsLog.percentile(v.sorted(), 99), "max" to (v.maxOrNull() ?: 0.0))
        val out = mapOf("first_tts_to_first_audio_ms (sim TTS ~40 ms)" to pct(firstTtsToAudio), "interrupt_to_silence_ms (sink.stop call)" to pct(interruptToSilence),
            "cancel_complete_ms" to pct(cancelComplete), "max_chunk_gap_ms per answer" to pct(maxGaps))
        println("pipeline bench: $out stale=$stale")
        assertEquals(0, stale, "stale audio incidents")
        File(System.getProperty("clu.benchOut")!!).parentFile.resolve("streaming_pipeline_host.json").writeText(
            kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("note", kotlinx.serialization.json.JsonPrimitive("Host x86, simulated LLM (40 ms/word), TTS (25 ms + 1.5 ms/char) and real-time sink; 30 runs, half interrupted at random points. Pipeline overhead only, not device audio latency."))
                    put("stale_audio_incidents", kotlinx.serialization.json.JsonPrimitive(stale))
                    out.forEach { (k, v) -> put(k, kotlinx.serialization.json.buildJsonObject { v.forEach { (a, b) -> put(a, kotlinx.serialization.json.JsonPrimitive(b)) } }) }
                }))
    }
}
