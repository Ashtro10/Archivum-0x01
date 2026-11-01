package ai.clu.desktop.voice

import ai.clu.core.config.Settings
import ai.clu.core.llm.LlmStatus
import ai.clu.desktop.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Turn rules of the voice agent that need no conversation: stop phrases, and the model warm while a session is open. */
class VoiceTurnRulesTest {
    @Test fun stopPhrasesStopWithoutAskingTheModelAndOrdinarySentencesDoNot() {
        for (t in listOf("Stop talking.", "stop", "Stop it!", "Be quiet.", "OK, that's enough.", "Never mind", "Clue, stop.", "Cancel that.", "Shut up", "Hold on."))
            assertTrue(STOP.matches(t.trim()), t)
        for (t in listOf("Stop the timer when it rings.", "How do I stop a dripping tap?", "Can you hear me?", "Who is Leman Russ?", "Wait for me at the station.", "That is enough sugar."))
            assertFalse(STOP.matches(t.trim()), t)
    }

    /** A voice session loads the model and warms CLU's instructions without any request (the first spoken turn must not wait for a load). */
    @Test fun aVoiceSessionWarmsTheModel() = runBlocking {
        val tiny = File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))
        assumeTrue(tiny.exists() && SpeechModels.locate() != null && NativeVoice.load(), "tiny model or voice build missing")
        val dir = Files.createTempDirectory("clu-voice-warm").toFile()
        val host = Host.start(DesktopPaths(File(dir, "home")), HostConfig(maintenanceMinutes = 0, governorIntervalSeconds = 600), port = 0, log = {})
        try {
            val e = host.core.models.register(tiny); host.core.settings.set(Settings.MODEL_ACTIVE, e.id)
            assertNotEquals(LlmStatus.READY, host.core.llm.state.value.status, "nothing loaded ahead of time on the PC")
            host.voice!!.startDesktop(host.core.conversations.create("Warm").id)
            withTimeout(60_000) { while (host.core.llm.state.value.status != LlmStatus.READY) delay(100) }
            assertEquals(0, host.core.agent.trace.recent().size, "loaded for the session, not by a request")
        } finally { host.stop(); dir.deleteRecursively() }
    }
}
