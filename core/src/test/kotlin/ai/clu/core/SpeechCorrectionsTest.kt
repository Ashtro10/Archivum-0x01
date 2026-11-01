package ai.clu.core

import ai.clu.core.agent.ProperNouns
import ai.clu.core.agent.SaidCorrection
import kotlin.test.*
import org.junit.jupiter.api.Test

/** Misheard names and spoken corrections, resolved from what the conversation knows, never by blind rewriting. */
class SpeechCorrectionsTest {
    private val known = listOf("Leman Russ", "Space Wolves", "Fenris")

    @Test fun aMisheardNameTheConversationKnowsIsCorrected() {
        assertEquals("Who is Leman Russ?", ProperNouns.correct("Who is Lemon Russ?", known)?.text)
        val r = ProperNouns.correct("tell me about lemon rust", known)
        assertEquals("tell me about Leman Russ", r?.text); assertEquals("lemon rust", r?.from)
    }

    @Test fun unrelatedSpeechIsNotRewritten() {
        for (t in listOf("I would like a lemon tart.", "Can you hear me?", "Answer me.", "The car is covered in rust.", "Stop talking.", "Hello, Clue."))
            assertNull(ProperNouns.correct(t, known), t)
        assertNull(ProperNouns.correct("Who is Leman Russ?", known), "said right: nothing to fix")
        assertNull(ProperNouns.correct("I rode a horse", listOf("Horus")), "a single-word name is never matched against an ordinary word")
    }

    @Test fun namesAreFoundInText() {
        assertEquals(listOf("Leman Russ", "Primarch", "Space Wolves", "Warhammer"),
            ProperNouns.namesIn("Leman Russ is the Primarch of the Space Wolves in Warhammer 40,000.").take(4))
        assertTrue(ProperNouns.namesIn("Hello! How can I help you today?").isEmpty())
    }

    @Test fun iSaidCorrectsThePreviousRequest() {
        assertEquals("Leman Russ", SaidCorrection.parse("I said Leman Russ."))
        assertEquals("talking", SaidCorrection.parse("No, I said talking."))
        assertEquals("Fenris", SaidCorrection.parse("I meant Fenris"))
        assertNull(SaidCorrection.parse("Who is Leman Russ?")); assertNull(SaidCorrection.parse("Can you hear me?"))
        assertEquals("Who is Leman Russ?", SaidCorrection.apply("Leman Russ", "Who is Lemon Russ?"))
        assertEquals("Stop talking.", SaidCorrection.apply("talking", "Stop walking."))
        assertEquals("Who is Leman Russ?", SaidCorrection.apply("Leman Russ", "Who is Leman Russ?"), "heard right the first time: asked again as it was")
        assertNull(SaidCorrection.apply("Leman Russ", "Can you hear me?"), "nothing in the previous request sounds like it")
    }
}
