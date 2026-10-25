package ai.clu.core

import ai.clu.core.voice.AsrRouting
import kotlin.test.*
import org.junit.jupiter.api.Test

class AsrRoutingTest {
    @Test fun englishIsLive() { val p = AsrRouting.plan("en", whisperInstalled = true); assertNull(p.whisperLanguage); assertTrue(p.livePartials) }

    @Test fun hindiAndMixedUseWhisperHindiDecoding() {
        assertEquals("hi", AsrRouting.plan("hi", true).whisperLanguage)
        assertEquals("hi", AsrRouting.plan("mixed", true).whisperLanguage)
        assertFalse(AsrRouting.plan("hi", true).livePartials, "English live words are not shown for Hindi")
        assertEquals("auto", AsrRouting.plan("auto", true).whisperLanguage)
    }

    @Test fun withoutWhisperHindiIsNotSilentlyForcedToEnglish() {
        val p = AsrRouting.plan("hi", whisperInstalled = false)
        assertNull(p.whisperLanguage)
        assertTrue(p.note!!.contains("Whisper"), "the limitation is stated")
    }

    @Test fun legacyEngineIds() { assertEquals("clu", AsrRouting.normaliseEngine("whisper")); assertEquals("clu", AsrRouting.normaliseEngine("sherpa")) }
}
