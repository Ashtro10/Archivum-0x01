package ai.clu.desktop

import ai.clu.core.api.LoopbackServer
import ai.clu.core.api.ShellHost
import ai.clu.core.llm.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CLU's window as the Android app serves it, in a real browser at tablet size (opt-in `-Dclu.shellUi=<node>`): the
 * packaged shell through [ShellHost] on 127.0.0.1 with the app's native bridge stubbed, (a) on the tablet alone and
 * (b) paired with a real PC: tablet shell -> ShellHost -> the PC's phone endpoint (device key) -> the PC's API.
 */
class TabletShellTest {
    private class Hello : LlmEngine {
        override val id = "scripted"; override val source = "local"
        override fun isReady() = true
        override fun countTokens(messages: List<ChatMessage>) = messages.sumOf { it.content.length / 4 + 4 }
        override fun contextSize() = 8192
        override fun cancel() {}
        override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult {
            val t = "Hello. I'm here."; t.chunked(3).forEach { onText(it); Thread.sleep(40) }
            return GenResult(t, GenStats(promptTokens = 10, generatedTokens = 5, stopReason = "eog"), source)
        }
    }
    /** What the APK packages (app/build.gradle.kts shellAssets): the shell without the browser voice client and the gesture page. */
    private val apkAsset: (String) -> ByteArray? = { name ->
        if (name == "livekit-client.js") "/* CLU's Android app talks through its native voice session (platform.js); the browser LiveKit client is not shipped. */\n".toByteArray()
        else if (name in setOf("hands.html", "hands.js", "hands.css", "hand-gestures.js")) null
        else ShellHost::class.java.getResourceAsStream("/shell/$name")?.use { it.readBytes() }
    }

    private fun run(mode: String, base: String, code: String) {
        val node = System.getProperty("clu.shellUi")
        val script = listOf(File("src/test/ui/tablet.ui.js"), File("desktop/src/test/ui/tablet.ui.js")).first { it.exists() }
        val out = File(System.getProperty("clu.shellUiShots") ?: "build/tablet-ui").apply { mkdirs() }
        val p = ProcessBuilder(node, script.absolutePath, base, code, out.absolutePath, mode).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText(); val done = p.waitFor(240, TimeUnit.SECONDS)
        println(log); File(out, "tablet-$mode-transcript.txt").writeText(log)
        assertTrue(done, "timed out"); assertEquals(0, p.exitValue(), log)
    }

    private fun start(dir: File, name: String) = Host.start(DesktopPaths(File(dir, name)), HostConfig(maintenanceMinutes = 0, governorIntervalSeconds = 600), port = 0, log = {}).also {
        val e = Hello(); it.core.remoteEngineProvider = { e }; it.core.settings.set(ai.clu.core.config.Settings.REMOTE_INFERENCE, "prefer_home")
    }

    @Test fun theTabletWindowWorksOnItsOwn() {
        assumeTrue(!System.getProperty("clu.shellUi").isNullOrBlank(), "opt-in: -Dclu.shellUi=<node>")
        val dir = Files.createTempDirectory("clu-tablet").toFile()
        val tablet = start(dir, "tablet") // stands in for the app's core
        val shell = ShellHost(tablet.core, "1.13.0", apkAsset, { null })
        val server = LoopbackServer(handler = shell::handle); val port = server.start()
        try { run("device", "http://127.0.0.1:$port", shell.newLaunchCode()) }
        finally { server.stop(); tablet.stop(); dir.deleteRecursively() }
    }

    @Test fun thePairedTabletWindowShowsThePCsCLU() {
        assumeTrue(!System.getProperty("clu.shellUi").isNullOrBlank(), "opt-in: -Dclu.shellUi=<node>")
        System.setProperty("clu.voiceLanAddress", "127.0.0.1") // loopback stands in for the Wi-Fi address
        val dir = Files.createTempDirectory("clu-tablet-pc").toFile()
        val pc = start(dir, "pc"); val tablet = start(dir, "tablet")
        val v = pc.voice!!
        var server: LoopbackServer? = null
        try {
            v.store.put(v.settings.copy(phoneAccess = true)); kotlin.test.assertNull(v.applyPhoneAccess())
            val (_, key) = v.pair("Pad 6")
            val shell = ShellHost(tablet.core, "1.13.0", apkAsset, { ShellHost.Remote(v.phoneEndpoint()!!.removeSuffix("/token"), key) })
            server = LoopbackServer(handler = shell::handle); val port = server.start()
            run("pc", "http://127.0.0.1:$port", shell.newLaunchCode())
            // The typed conversation lives on the PC, not on the tablet.
            assertTrue(pc.core.conversations.list().any { c -> pc.core.conversations.messages(c.id).any { it.content == "Hello" } }, "the PC has the conversation")
            assertTrue(tablet.core.conversations.list().none { c -> tablet.core.conversations.messages(c.id).any { it.content == "Hello" } }, "the tablet does not")
        } finally { server?.stop(); v.shutdown(); pc.stop(); tablet.stop(); System.clearProperty("clu.voiceLanAddress"); dir.deleteRecursively() }
    }
}
