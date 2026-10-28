package ai.clu.core

import ai.clu.core.voice.AdaptiveChunker
import kotlin.test.*
import org.junit.jupiter.api.Test

class AdaptiveChunkerTest {
    /** Feeds text in small pieces like an LLM token stream; returns the chunks and the input offset at which each appeared. */
    private fun stream(text: String, step: Int = 3, c: AdaptiveChunker = AdaptiveChunker()): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        var i = 0
        while (i < text.length) { val end = minOf(text.length, i + step); c.push(text.substring(i, end)).forEach { out += it to end }; i = end }
        c.flush().forEach { out += it to text.length }
        return out
    }

    @Test fun firstChunkIsAShortPhraseBeforeTheSentenceEnds() {
        val text = "Sure, let me check that for you. The reactor is stable and all systems are running normally today."
        val chunks = stream(text)
        assertEquals("Sure, let me check that for you.", chunks[0].first, "first sentence is short: emitted at its end")
        assertEquals("The reactor is stable and all systems are running normally today.", chunks[1].first)
        assertEquals(2, chunks.size)
    }

    @Test fun firstChunkDoesNotWaitForTheWholeSentence() {
        val long = "I looked into the maintenance schedule and found that the next inspection is due on Monday morning at nine."
        val chunks = stream(long)
        val (first, at) = chunks.first()
        assertTrue(AdaptiveChunker.wordCount(first) in 3..12, "short first chunk: '$first'")
        assertTrue(at < long.length * 2 / 3, "available after ${at} of ${long.length} chars")
        assertFalse(first.split(" ").last().lowercase() in setOf("the", "to", "for", "a", "and", "of"), "does not end on a function word: '$first'")
        assertEquals(long, chunks.joinToString(" ") { it.first }, "nothing lost or duplicated")
    }

    @Test fun clauseBoundaryUsedForFirstChunk() {
        // "Of course," has only 2 words (too short to stand alone); "Right then, ..." would too. A 3+ word clause is used.
        assertEquals("Yes, that is right,", stream("Yes, that is right, the alarm is set for seven tomorrow and I will wake you gently.")[0].first)
        // With no 3-word clause or sentence end within 8 words, the forced cut prefers the latest natural clause boundary:
        // "Of course," is spoken at once while the rest is synthesised (all 8 words were available by then).
        val c = stream("Of course, the alarm is set for seven tomorrow and I will wake you gently.").map { it.first }
        assertEquals(listOf("Of course,", "the alarm is set for seven tomorrow and I will wake you gently."), c)
        // Without any boundary, the cut is at a word boundary after ~8 words, not on a function word.
        val n = stream("Please remember that tomorrow morning we need groceries milk bread eggs and coffee beans.").map { it.first }
        assertTrue(AdaptiveChunker.wordCount(n[0]) in 8..10, "'${n[0]}'")
        assertFalse(n[0].split(" ").last().lowercase() in setOf("and", "the", "for", "to"), "'${n[0]}'")
    }

    @Test fun decimalsAbbreviationsAndInitialsAreNotSplit() {
        val text = "Dr. Rao said the value is 3.5 percent, e.g. the usual rate. J. R. R. Tolkien wrote it. Next sentence follows here."
        val chunks = stream(text, step = 1).map { it.first }
        assertTrue(chunks.none { it.endsWith("Dr.") || it.endsWith("3.") || it.endsWith("e.g.") || it.endsWith(" J.") || it.endsWith(" R.") }, "$chunks")
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test fun laterChunksAreSentencesOrBoundedClauses() {
        val text = "Okay. " + "This is a long explanation that keeps going with many words, and it continues with another clause that is also long, " +
            "and then yet another part follows without any full stop for quite a while longer than usual."
        val chunks = stream(text).map { it.first }
        assertTrue(chunks.drop(1).all { it.length <= 220 }, "bounded: ${chunks.map { it.length }}")
        assertTrue(chunks.size >= 2)
        // Stage 3 (final): a later chunk ends at a comma only once it is long (a cut comma is spoken like a sentence end).
        assertTrue(chunks.drop(1).filter { it.endsWith(",") }.all { it.length >= 110 }, "$chunks")
        assertEquals(text.replace(Regex("\\s+"), " ").trim(), chunks.joinToString(" "))
    }

    @Test fun hindiDandaIsASentenceBoundaryAndScriptIsDetected() {
        val text = "ठीक है, मैं अभी देखता हूँ। आज मौसम साफ़ रहेगा। शाम को हल्की बारिश हो सकती है।"
        val chunks = stream(text).map { it.first }
        assertEquals("ठीक है, मैं अभी देखता हूँ।", chunks[0])
        assertEquals("hi", AdaptiveChunker.script(chunks[0]))
        assertEquals("en", AdaptiveChunker.script("Remind me at nine"))
        assertEquals("hi", AdaptiveChunker.script("मुझे नौ बजे remind"))
    }

    @Test fun codeAndToolMarkupAreNotSpoken() {
        val chunks = stream("Here is the command. ```val x = 1``` That should work fine for you now.").map { it.first }
        assertTrue(chunks.none { "val x" in it }, "$chunks")
        assertTrue(chunks.any { "That should work" in it })
    }

    @Test fun stallFlushNeedsTwoWords() {
        val c = AdaptiveChunker()
        c.push("Hmm ")
        assertTrue(c.flushIfStalled().isEmpty())
        c.push("let me ")
        assertEquals(listOf("Hmm let me"), c.flushIfStalled())
    }

    @Test fun stallFlushNeverSplitsAWordOrEndsOnAFunctionWordWhenAsked() {
        val c = AdaptiveChunker()
        c.push("Done. I looked into the mainten") // "mainten" may still grow
        assertEquals(listOf("Done. I looked"), c.flushIfStalled(minWords = 2, avoidFunctionWordEnd = true))
        c.push("ance schedule and ")
        assertEquals(listOf("into the maintenance schedule and"), c.flushIfStalled(minWords = 3))
        c.push("then") ; c.push(" ok.")
        assertEquals(listOf("then ok."), c.flush())
    }
}
