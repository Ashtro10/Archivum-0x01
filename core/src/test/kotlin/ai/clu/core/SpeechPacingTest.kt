package ai.clu.core

import ai.clu.core.voice.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.test.*

/**
 * Final Stage 3: speech pacing. Commas give short pauses, sentence ends longer ones, no word is lost, doubled or
 * reordered, and streaming still starts before the reply is complete. Synthetic audio stands in for a voice that,
 * like Kokoro, adds its own lead-in and ~190 ms of trailing silence to every chunk.
 */
class SpeechPacingTest {
    private val rate = 16_000
    private fun ms(n: Int) = n * rate / 1000

    /** A chunk as a voice returns it: [leadMs] silence, voiced audio with a soft onset/decay, [tailMs] silence. */
    private fun voiced(words: Int, leadMs: Int = 120, tailMs: Int = 190): FloatArray {
        val body = FloatArray(ms(300 * words)) { i -> (kotlin.math.sin(i * 0.05) * 0.6).toFloat() }
        for (i in 0 until ms(15)) { body[i] *= i / ms(15).toFloat() * 0.5f } // soft consonant at the start
        return FloatArray(ms(leadMs)) + body + FloatArray(ms(tailMs))
    }

    private fun silence(x: FloatArray, fromEnd: Boolean): Int {
        val peak = x.maxOf { abs(it) }; var n = 0
        if (fromEnd) { var i = x.size - 1; while (i >= 0 && abs(x[i]) < peak * 0.02f) { n++; i-- } }
        else { var i = 0; while (i < x.size && abs(x[i]) < peak * 0.02f) { n++; i++ } }
        return n * 1000 / rate
    }

    private fun audible(x: FloatArray) = x.count { abs(it) >= x.maxOf { v -> abs(v) } * 0.02f }

    @Test fun boundaryPausesFollowPunctuationWhateverTheVoiceAdds() {
        val gap = { chunk: String -> SpeechText.withPause(chunk, voiced(4), rate).let { silence(it, true) } + SpeechText.LEAD_MS }
        val comma = gap("the inspection is due,")
        val stop = gap("The inspection is due.")
        val question = gap("Is the inspection due?")
        val paragraph = gap("That is all." + SpeechText.PARAGRAPH)
        assertTrue(comma in 100..150, "comma ≈ 120 ms, got $comma")
        assertTrue(stop in 340..390, "full stop ≈ 360 ms, got $stop")
        assertTrue(question >= stop && paragraph > question, "question $question, paragraph $paragraph")
        assertTrue(stop - comma >= 200, "a sentence end is clearly longer than a comma ($stop vs $comma)")
        // The voice's own lead-in is cut to the fixed lead, so it doesn't add to the gap before the next chunk.
        assertTrue(silence(SpeechText.withPause("Next part.", voiced(3, leadMs = 200), rate), false) <= SpeechText.LEAD_MS + 1)
    }

    @Test fun noAudibleSoundIsEverRemoved() {
        for ((lead, tail) in listOf(0 to 0, 120 to 190, 300 to 600, 10 to 5)) for (end in listOf(",", ".", "?", "")) {
            val x = voiced(3, lead, tail)
            val y = SpeechText.withPause("some words here$end", x, rate)
            assertEquals(audible(x), audible(y), "every audible sample kept (lead $lead, tail $tail, '$end')")
        }
        assertEquals(0, SpeechText.withPause("x.", FloatArray(0), rate).size)
        assertContentEquals(FloatArray(100), SpeechText.withPause("x.", FloatArray(100), rate), "pure silence is left alone")
    }

    private fun chunks(text: String, step: Int = 4): List<String> {
        val c = AdaptiveChunker(); val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) { out += c.push(text.substring(i, minOf(text.length, i + step))); i += step }
        return out + c.flush()
    }

    private fun words(s: String) = s.replace(SpeechText.PARAGRAPH.toString(), " ").replace(SpeechText.LINE.toString(), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test fun punctuationCasesKeepEveryWordInOrder() {
        val cases = listOf(
            "Yes. It is ready.",                                                                    // short sentences
            "Well, I checked the schedule, the logs, and the alarms, and everything looks fine.", // commas
            "Is it ready? It is. Shall I start it now? Of course!",                               // questions, exclamations
            "Dr. Rao met Mr. Singh at St. Mary's, e.g. on Monday. J. R. R. Tolkien wrote it.",    // abbreviations, initials
            "The value rose from 3.5 to 4.75 percent, about 1.2 points. Version 2.0.1 fixed it.",  // decimals, versions
            "This is a deliberately long sentence that keeps going with many words and no punctuation at all for a very long " +
                "time so that the chunker has to cut it somewhere sensible without losing anything at all along the way",
            "First paragraph ends here.\n\nSecond paragraph starts, continues, and ends.",
        )
        for (text in cases) for (step in listOf(1, 3, 7)) {
            val out = chunks(text, step)
            assertEquals(words(text), out.flatMap(::words), "no word dropped, duplicated or reordered (step $step): $out")
            assertTrue(out.none { it.endsWith("Dr.") || it.endsWith("Mr.") || it.endsWith("St.") || Regex("\\d\\.$").containsMatchIn(it) || it.endsWith("e.g.") },
                "never cut after an abbreviation or inside a number: $out")
        }
    }

    @Test fun commasAreRarelyChunkBoundariesWhileSentencesAre() {
        val text = "Of course, the inspection is due on Monday, at nine. The technician needs the panel, the keys, and the logbook. " +
            "Everything else, as far as I can see, looks normal. Shall I remind you on Sunday?"
        val out = chunks(text)
        // Only the first chunk may end at a comma (a clause of 5+ words, so speech can start early; its pause is short).
        assertTrue(AdaptiveChunker.wordCount(out[0]) >= 5)
        assertEquals(0, out.drop(1).count { it.trimEnd().endsWith(",") }, "later short clauses stay inside their sentence: $out")
        assertEquals(3, out.drop(1).count { it.trimEnd().let { e -> e.endsWith(".") || e.endsWith("?") } }, "each later sentence is its own chunk: $out")
    }

    /** Collects what would be heard, chunk by chunk, with the silence between chunks' sounds. */
    private class Ear : SpeechSink {
        val audio = CopyOnWriteArrayList<Pair<String, FloatArray>>()
        @Volatile var id = -1L
        override fun begin(responseId: Long) { id = responseId }
        override fun play(chunk: AudioChunk, onFirstFrame: (() -> Unit)?): Boolean {
            if (chunk.responseId != id) return false
            onFirstFrame?.invoke(); audio += chunk.text to chunk.samples; return true
        }
        override fun drain(responseId: Long) {}
        override fun stop() { id = -2 }
    }

    @Test fun streamedReplyIsHeardCompleteWithPunctuationPaced(): Unit = runBlocking {
        val ear = Ear()
        val synth = SpeechSynthesizer { text, _ -> SpeechText.withPause(text, voiced(AdaptiveChunker.wordCount(text)), rate) to rate } // as SpeechOutput does
        val pool = Executors.newSingleThreadExecutor().asCoroutineDispatcher(); val play = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val speech = StreamingSpeech(synth, ear, pool, play)
        val reply = "Of course, the inspection is due on Monday. The technician needs the panel, the keys, and the logbook. Shall I remind you?"
        val r = speech.start()
        var firstHeardBeforeEnd = false
        reply.chunked(5).forEach { r.text(it); Thread.sleep(15); if (ear.audio.isNotEmpty()) firstHeardBeforeEnd = true }
        r.finish()
        withTimeout(10_000) { r.completion.await() }
        assertTrue(firstHeardBeforeEnd, "speech started while the reply was still streaming")
        assertEquals(words(reply), ear.audio.flatMap { words(it.first) }, "heard in order, complete")
        // Gap after each chunk = its trailing silence + the next chunk's lead.
        val gaps = ear.audio.zipWithNext { (t, a), (_, b) -> t to silence(a, true) + silence(b, false) }
        gaps.forEach { (t, g) -> if (t.endsWith(".") || t.endsWith("?")) assertTrue(g in 340..400, "sentence gap $g after '$t'") else if (t.endsWith(",")) assertTrue(g in 100..160, "comma gap $g after '$t'") }
        speech.shutdown(); pool.close(); play.close()
    }

    @Test fun stopSilencesAtOnceAndNothingLaterIsHeard(): Unit = runBlocking {
        val ear = Ear()
        val synth = SpeechSynthesizer { text, _ -> Thread.sleep(30); SpeechText.withPause(text, voiced(2), rate) to rate }
        val pool = Executors.newSingleThreadExecutor().asCoroutineDispatcher(); val play = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val speech = StreamingSpeech(synth, ear, pool, play)
        val r = speech.start()
        r.text("One sentence here. Another sentence there. ")
        while (ear.audio.isEmpty()) Thread.sleep(5)
        speech.interrupt()
        val heard = ear.audio.size
        r.text("More words after the stop. And more. ")
        Thread.sleep(300)
        assertEquals(heard, ear.audio.size, "nothing is played after Stop / barge-in")
        assertTrue(r.cancelled)
        speech.shutdown(); pool.close(); play.close()
    }
}
