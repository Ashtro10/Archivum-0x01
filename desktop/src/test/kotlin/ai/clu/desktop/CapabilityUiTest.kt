package ai.clu.desktop

import ai.clu.desktop.capability.ExperienceVerifier
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance tests A–H in CLU's real window: the owner types the instructions into chat, CLU develops, verifies,
 * installs and opens the experiences, the host is restarted, and the improvement is made. Screenshots of every step go
 * to `-Dclu.shellUiShots` (default build/capability-ui).
 *
 * Opt-in like the other UI tests: `-Dclu.shellUi=<node>`. Weather data: by default a local stand-in that answers in
 * Open-Meteo's format (`-Dclu.capProvider=real` uses the real Open-Meteo, which must then be reachable for figures to
 * appear; when it is not, the experience must say so).
 */
class CapabilityUiTest {
    private val dir = Files.createTempDirectory("clu-cap-ui").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) }
    private val paths = DesktopPaths(File(dir, "home"))
    private var host: RunningHost? = null
    private var provider: HttpServer? = null

    @AfterEach fun stop() { host?.stop(); provider?.stop(0); System.clearProperty("clu.kit.weatherUrl") }

    private fun phase(node: String, script: File, shots: File, name: String) {
        val h = host!!
        val p = ProcessBuilder(node, script.absolutePath, h.url, h.api.issueLaunchCode(), shots.absolutePath, name).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it); println("[ui:$name] $it") } }.apply { start() }
        val finished = p.waitFor(900, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(2000)
        File(shots, "transcript-$name.txt").writeText(out.toString())
        assertTrue(finished, "UI script ($name) timed out:\n$out")
        assertEquals(0, p.exitValue(), "UI script ($name) failed:\n$out")
    }

    @Test fun acceptanceAtoHInTheRealWindow() {
        val node = System.getProperty("clu.shellUi")
        assumeTrue(!node.isNullOrBlank(), "opt-in: -Dclu.shellUi=<node>")
        assumeTrue(ExperienceVerifier.find() != null, "no Chromium-based browser for CLU to verify with")
        val script = listOf(File("src/test/ui/capability.ui.js"), File("desktop/src/test/ui/capability.ui.js")).first { it.exists() }
        val shots = File(System.getProperty("clu.shellUiShots") ?: "build/capability-ui").apply { mkdirs() }
        if (System.getProperty("clu.capProvider") != "real") {
            provider = OpenMeteoStandIn.start()
            System.setProperty("clu.kit.weatherUrl", "http://127.0.0.1:${provider!!.address.port}/v1/forecast")
        }
        host = Host.start(paths, HostConfig(), port = 0, log = { println("[host] $it") })
        // Optional: a real local model for the development steps that use one (`-Dclu.capModel=<file.gguf>`).
        System.getProperty("clu.capModel")?.let { File(it) }?.takeIf { it.isFile }?.let { f ->
            val m = host!!.core.models.register(f); host!!.core.settings.set(ai.clu.core.config.Settings.MODEL_ACTIVE, m.id); println("[host] development model: ${f.name}") }
        phase(node, script, shots, "build")
        // TEST G: restart CLU (a new host process state over the same data folder).
        host!!.stop(); host = Host.start(paths, HostConfig(), port = 0, log = { println("[host] $it") })
        phase(node, script, shots, "restarted")
        File(paths.root, "capabilities/installed").walkTopDown().filter { it.name == "preview.png" }.forEach { it.copyTo(File(shots, "preview-${it.parentFile.parentFile.name}-v${it.parentFile.name}.png"), overwrite = true) }
    }
}
