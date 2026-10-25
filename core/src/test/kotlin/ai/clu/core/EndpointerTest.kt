package ai.clu.core

import ai.clu.core.voice.Endpointer
import kotlin.test.*
import org.junit.jupiter.api.Test

class EndpointerTest {
    @Test fun oneTurnPerVadSpeechSegment() {
        val e = Endpointer(); var t = 0L
        fun feed(sp: Boolean, ms: Int) = (0 until ms / 30).map { e.onFrame(sp, t).also { t += 30 } }.filter { it != Endpointer.Event.None }
        assertTrue(feed(false, 300).isEmpty())
        assertEquals(listOf<Endpointer.Event>(Endpointer.Event.Started), feed(true, 900)) // the VAD keeps reporting speech through short pauses
        val end = feed(false, 300).single()
        assertTrue(end is Endpointer.Event.Ended && end.speechMs in 840L..900L, "$end")
    }

    @Test fun forcedEndOnlyWhileSpeaking() {
        val e = Endpointer()
        assertNull(e.forceEnd())
        e.onFrame(true, 0); e.onFrame(true, 600)
        assertEquals(600L, e.forceEnd()!!.speechMs)
        assertFalse(e.inSpeech)
    }
}
