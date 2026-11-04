package ai.clu.desktop

import ai.clu.core.llm.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The restored Primus core in CLU's real window (Chromium with software WebGL): opt-in `-Dclu.shellUi=<node>`.
 * The reply comes from a scripted engine; what is checked is the window: the original core runs and follows CLU's states.
 */
class PrimusShellTest {
    private class Hello : LlmEngine {
        override val id = "scripted"; override val source = "local"
        override fun isReady() = true
        override fun countTokens(messages: List<ChatMessage>) = messages.sumOf { it.content.length / 4 + 4 }
        override fun contextSize() = 8192
        override fun cancel() {}
        override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult {
            val t = "Hello. I'm here."; t.chunked(3).forEach { onText(it); Thread.sleep(60) }
            return GenResult(t, GenStats(promptTokens = 10, generatedTokens = 5, stopReason = "eog"), source)
        }
    }

    @Test fun thePrimusCoreRunsBehindTheInterfaceAndFollowsClu() {
        val node = System.getProperty("clu.shellUi"); assumeTrue(!node.isNullOrBlank(), "opt-in: -Dclu.shellUi=<node>")
        val script = System.getProperty("clu.primusScript")?.let(::File) ?: listOf(File("src/test/ui/primus.ui.js"), File("desktop/src/test/ui/primus.ui.js")).first { it.exists() }
        val out = File(System.getProperty("clu.shellUiShots") ?: "build/primus-ui").apply { mkdirs() }
        val dir = Files.createTempDirectory("clu-primus").toFile()
        val host = Host.start(DesktopPaths(File(dir, "home")), HostConfig(), port = 0, log = {})
        try {
            val engine = Hello(); host.core.remoteEngineProvider = { engine }
            host.core.settings.set(ai.clu.core.config.Settings.REMOTE_INFERENCE, "prefer_home")
            val p = ProcessBuilder(node, script.absolutePath, host.url, host.api.issueLaunchCode(), out.absolutePath).redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText(); val done = p.waitFor(180, TimeUnit.SECONDS)
            println(log); File(out, "primus-transcript.txt").writeText(log)
            assertTrue(done, "timed out"); assertEquals(0, p.exitValue(), log)
        } finally { host.stop() }
    }
}
