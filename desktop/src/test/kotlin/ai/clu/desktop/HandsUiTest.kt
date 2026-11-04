package ai.clu.desktop

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CLU · Hands, the camera gesture interface, in CLU's real window with the real MediaPipe hand landmarker.
 * Category: integration with a simulated camera (Chromium's fake capture device playing clips made from real photographs
 * of hands). Opt-in: `-Dclu.shellUi=<node> -Dclu.gesturePack=<folder with the four pack files> -Dclu.handClips=<folder
 * with point.y4m, victory.y4m, none.y4m>`. The pack is installed with [GesturePack.installFrom], which verifies every
 * file against its pinned hash, exactly as after the in-app download.
 */
class HandsUiTest {
    private fun run(node: String, script: File, host: RunningHost, out: File, phase: String, clip: File? = null): String {
        val cmd = mutableListOf(node, script.absolutePath, host.url, host.api.issueLaunchCode(), out.absolutePath, phase); clip?.let { cmd += it.absolutePath }
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText(); val done = p.waitFor(150, TimeUnit.SECONDS)
        println("--- $phase\n$log"); File(out, "hands-$phase.txt").writeText(log)
        assertTrue(done, "$phase timed out"); assertEquals(0, p.exitValue(), log)
        return log
    }

    @Test fun handsTracksRealHandsAndExplainsWhatItCannotDo() {
        val node = System.getProperty("clu.shellUi"); val pack = System.getProperty("clu.gesturePack")?.let(::File); val clips = System.getProperty("clu.handClips")?.let(::File)
        assumeTrue(!node.isNullOrBlank() && pack?.isDirectory == true && clips?.isDirectory == true, "opt-in: -Dclu.shellUi=<node> -Dclu.gesturePack=<dir> -Dclu.handClips=<dir>")
        val script = listOf(File("src/test/ui/hands.ui.js"), File("desktop/src/test/ui/hands.ui.js")).first { it.exists() }
        val out = File(System.getProperty("clu.shellUiShots") ?: "build/hands-ui").apply { mkdirs() }
        val dir = Files.createTempDirectory("clu-hands").toFile()
        val host = Host.start(DesktopPaths(File(dir, "home")), HostConfig(preloadModel = false), port = 0, log = {})
        try {
            val g = host.api.gesture!!
            assertTrue(!g.verified())
            run(node, script, host, out, "nopack")
            assertTrue(!g.verified(), "nothing was downloaded without the owner's click")
            g.installFrom(pack!!)
            run(node, script, host, out, "point", File(clips, "point.y4m"))
            run(node, script, host, out, "victory", File(clips, "victory.y4m"))
            run(node, script, host, out, "none", File(clips, "none.y4m"))
            run(node, script, host, out, "denied", File(clips, "point.y4m"))
        } finally { host.stop(); dir.deleteRecursively() }
    }
}
