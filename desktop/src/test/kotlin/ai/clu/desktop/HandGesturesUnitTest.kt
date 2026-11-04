package ai.clu.desktop

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests of the gesture engine (shell/hand-gestures.js) with synthetic landmarks, run in Node: opt-in `-Dclu.shellUi=<node>`. */
class HandGesturesUnitTest {
    @Test fun gestureEngine() {
        val node = System.getProperty("clu.shellUi"); assumeTrue(!node.isNullOrBlank(), "opt-in: -Dclu.shellUi=<node>")
        val root = listOf(File("."), File("desktop")).first { File(it, "src/test/ui/hand-gestures.test.js").exists() }
        val p = ProcessBuilder(node, File(root, "src/test/ui/hand-gestures.test.js").absolutePath, File(root, "src/main/resources/shell/hand-gestures.js").absolutePath).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText(); assertTrue(p.waitFor(60, TimeUnit.SECONDS)); println(log)
        assertEquals(0, p.exitValue(), log)
    }
}
