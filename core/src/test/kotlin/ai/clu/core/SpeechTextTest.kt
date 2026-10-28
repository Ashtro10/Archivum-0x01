package ai.clu.core

import ai.clu.core.voice.AdaptiveChunker
import ai.clu.core.voice.SpeechText
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the voice hears (host unit tests; the audio itself is checked on the device, DEVICE_ACCEPTANCE section W). */
class SpeechTextTest {
    private fun say(s: String) = SpeechText.forSpeech(s)

    @Test fun markdownUrlsAndToolMarkupAreNotSpoken() {
        assertEquals("Steps", say("## Steps"))
        assertEquals("Open the app.", say("1. Open the app."))
        assertEquals("This is important.", say("This is **important**."))
        assertEquals("See the guide for details.", say("See the [guide](https://example.com/a?b=c) for details."))
        assertEquals("", say("Source: https://en.wikipedia.org/wiki/Queens"), "a sources line is for the screen, never spoken")
        assertEquals("Run ls -la now.", say("Run `ls -la` now."))
        assertEquals("Done.", say("Done. <tool>{\"name\":\"calc\",\"args\":{}}</tool>"))
        assertEquals("Great.", say("Great 👍."))
        assertFalse(say("| a | b |\n|---|---|\n| 1 | 2 |").contains("-"))
    }

    @Test fun notationBecomesWords() {
        assertEquals("The meeting is at 9 30 A M.", say("The meeting is at 9:30 am."))
        assertEquals("Leave at 18 oh 5.", say("Leave at 18:05."))
        assertEquals("It is due on 26 October 2026.", say("It is due on 2026-10-26."))
        assertEquals("It costs 1500 rupees.", say("It costs ₹1,500."))
        assertEquals("About 5 thousand dollars.", say("About $5k."))
        assertEquals("Drive 12 kilometres at 40 kilometres per hour.", say("Drive 12 km at 40 km/h."))
        assertEquals("It is 31 degrees Celsius and 70 percent humid.", say("It is 31°C and 70% humid."))
        assertEquals("Wait 5 to 10 minutes.", say("Wait 5-10 minutes."))
        assertEquals("Bring fruit, for example apples.", say("Bring fruit, e.g. apples."))
        assertEquals("Call 98765-43210.", say("Call 98765-43210."), "phone numbers are not ranges")
        assertEquals("Version 3.5 is out.", say("Version 3.5 is out."))
    }

    @Test fun meaningIsPreserved() {
        val s = "I checked twice. The road is closed, so take the highway instead!"
        assertEquals(s, say(s))
        assertEquals("Well… maybe.", say("Well... maybe."))
    }

    @Test fun pausesFollowPunctuationAndTopUpTheVoicesOwnSilence() {
        assertTrue(SpeechText.targetGapMs("Hello there.") > SpeechText.targetGapMs("Hello there,"))
        assertTrue(SpeechText.targetGapMs("Well…") > SpeechText.targetGapMs("Hello there."))
        assertTrue(SpeechText.targetGapMs("End of part" + SpeechText.PARAGRAPH) > SpeechText.targetGapMs("Hello there."))
        val rate = 22050
        val voiced = FloatArray(rate / 2) { kotlin.math.sin(it * 0.05f) * 0.5f }
        // A voice that already leaves 300 ms of silence after a sentence gets nothing added.
        assertEquals(0, SpeechText.padMs("Done.", voiced + FloatArray(rate * 300 / 1000), rate))
        // One that stops abruptly after a sentence gets topped up to the target (minus the next chunk's lead-in).
        assertEquals(SpeechText.targetGapMs("Done.") - 60, SpeechText.padMs("Done.", voiced, rate))
        assertTrue(SpeechText.withPause("Done.", voiced, rate).size > voiced.size)
    }

    @Test fun chunkerMarksParagraphsAndListLinesForPauses() {
        val c = AdaptiveChunker()
        val out = c.push("Here is the plan for today.\n\nFirst we check the weather now.\n- Pack an umbrella today\n- Leave by nine o'clock ") + c.flush()
        assertTrue(out.any { it.endsWith(SpeechText.PARAGRAPH) }, out.toString())
        assertTrue(out.any { it.endsWith(SpeechText.LINE) }, out.toString())
        assertTrue(out.none { SpeechText.forSpeech(it).contains(' ') || SpeechText.forSpeech(it).contains(' ') })
    }
}
