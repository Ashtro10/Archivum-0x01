package ai.clu.core

import ai.clu.core.stt.WhisperEngine
import ai.clu.core.stt.WhisperModels
import kotlin.test.*
import org.junit.jupiter.api.Test

class WhisperEngineTest {
    @Test fun repetitionLoopsAreDetected() {
        val loop = "After early nightfall, the yellow lamps would light up here and there, the squalid quarter of the brothels. " .repeat(4)
        assertTrue(WhisperEngine.looksRepetitive(loop))
        assertTrue(WhisperEngine.looksRepetitive("मुझे याद दिलाना मुझे याद दिलाना मुझे याद दिलाना"))
        assertFalse(WhisperEngine.looksRepetitive("Set an alarm for seven tomorrow morning."))
        assertFalse(WhisperEngine.looksRepetitive("no no no, I said seven, not eleven")) // single-word repeats are speech
    }

    @Test fun audioContextAndTokenBudget() {
        assertEquals(128, WhisperEngine.audioCtxFor(16_000))       // 1 s -> 50 + 64 margin, rounded up to 64
        assertEquals(1500, WhisperEngine.audioCtxFor(16_000 * 40)) // never beyond the 30 s window
        assertEquals(64, WhisperEngine.maxTokensFor(16_000 * 2))
        assertEquals(224, WhisperEngine.maxTokensFor(16_000 * 60))
    }

    @Test fun officialModelCatalogue() {
        // SHA1 values from ggml-org/whisper.cpp models/README.md.
        assertEquals("465707469ff3a37a2b9b8d8f89f2f99de7299dac", WhisperModels.BASE.sha1)
        assertEquals("55356645c2b361a969dfd0ef2c5a50d530afd8d5", WhisperModels.SMALL.sha1)
        assertTrue(WhisperModels.all.all { it.url.startsWith("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-") })
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", WhisperModels.sha1("abc".byteInputStream()))
    }
}
