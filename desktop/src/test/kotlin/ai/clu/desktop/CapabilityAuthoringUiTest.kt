package ai.clu.desktop

import ai.clu.core.config.Settings
import ai.clu.core.util.CluJson
import ai.clu.desktop.capability.ExperienceVerifier
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * The milestone acceptance in CLU's real window with a real development model (`-Dclu.shellUi=<node>`
 * `-Dclu.capModel=<gguf>`): the galaxy file explorer request, opening it by name, a restart, "add a search function",
 * and the list of developed applications. Whatever the model manages, the test checks that CLU's account is true:
 * an installed explorer must really browse the owner's permitted folder; a failed one must leave nothing installed
 * and say so. The outcome and screenshots go to `-Dclu.shellUiShots` (default build/authoring-ui).
 */
class CapabilityAuthoringUiTest {
    private val dir = Files.createTempDirectory("clu-author-ui").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) }
    private val paths = DesktopPaths(File(dir, "home"))
    private var host: RunningHost? = null
    private var provider: com.sun.net.httpserver.HttpServer? = null
    @AfterEach fun stop() { host?.stop(); provider?.stop(0); System.clearProperty("clu.kit.weatherUrl") }

    private fun phase(node: String, script: File, out: File, name: String): JsonObject {
        val p = ProcessBuilder(node, script.absolutePath, host!!.url, host!!.api.issueLaunchCode(), out.absolutePath, name).redirectErrorStream(true).start()
        val log = StringBuilder(); val t = Thread { p.inputStream.bufferedReader().forEachLine { log.appendLine(it); println("[ui:$name] $it") } }.apply { start() }
        val done = p.waitFor(4 * 3600, TimeUnit.SECONDS); if (!done) p.destroyForcibly(); t.join(2000)
        File(out, "transcript-$name.txt").writeText(log.toString())
        assertTrue(done, "UI script ($name) timed out")
        return CluJson.parseToJsonElement(File(out, "outcome-$name.json").readText()).jsonObject
    }

    @Test fun galaxyFileExplorerAcceptance() {
        val node = System.getProperty("clu.shellUi"); val model = System.getProperty("clu.capModel")?.let(::File)
        assumeTrue(!node.isNullOrBlank() && model?.isFile == true, "opt-in: -Dclu.shellUi=<node> -Dclu.capModel=<gguf>")
        assumeTrue(ExperienceVerifier.find() != null, "no browser")
        val script = listOf(File("src/test/ui/authoring.ui.js"), File("desktop/src/test/ui/authoring.ui.js")).first { it.exists() }
        val out = File(System.getProperty("clu.shellUiShots") ?: "build/authoring-ui").apply { mkdirs() }
        provider = OpenMeteoStandIn.start(); System.setProperty("clu.kit.weatherUrl", "http://127.0.0.1:${provider!!.address.port}/v1/forecast")
        // The owner's folder the explorer may browse.
        val owner = File(dir, "Owner Files").apply { mkdirs(); listOf("Documents/letters", "Documents/taxes", "Projects/clu", "Photos/2026", "Music").forEach { File(this, it).mkdirs() }
            File(this, "Documents/letters/thank-you.txt").writeText("Thanks!"); File(this, "Projects/clu/plan.md").writeText("# Plan"); File(this, "todo.txt").writeText("milk") }
        host = Host.start(paths, HostConfig(), port = 0, log = { println("[host] $it") })
        val m = host!!.core.models.register(model!!); host!!.core.settings.set(Settings.MODEL_ACTIVE, m.id)
        // The owner gives the model room to write a whole application (default 4096 tokens is too small; CLU says so).
        host!!.core.settings.set(Settings.CONTEXT_LENGTH, 16384)
        host!!.api.capabilities!!.apps.setPermittedRoots(listOf(owner.path))
        val first = phase(node!!, script, out, "build")
        val explorer = first["explorer"]?.jsonObject
        val installed = explorer?.get("state")?.jsonPrimitive?.content == "Installed"
        val apps = host!!.api.capabilities!!.manager.store.all().filter { it.kind == "app" }
        if (installed) {
            assertEquals(1, apps.size)
            val entries = first["explorerEntries"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(entries.containsAll(listOf("Documents", "Projects", "todo.txt")), "the installed explorer shows the owner's permitted folder: $entries")
        } else {
            assertTrue(apps.isEmpty(), "a failed development installs nothing")
            assertTrue(explorer?.get("message")?.jsonPrimitive?.content?.contains("couldn't", ignoreCase = true) == true, "CLU says plainly that it failed: $explorer")
        }
        host!!.stop(); host = Host.start(paths, HostConfig(), port = 0, log = { println("[host] $it") })
        val second = phase(node, script, out, "restarted")
        val mod = second["modification"]?.jsonObject
        if (!installed) assertTrue(mod?.get("state")?.jsonPrimitive?.content == "not-started" && mod["message"]!!.jsonPrimitive.content.contains("don't have"),
            "with no explorer installed CLU must say so, never claim it added search: $mod")
        // Evidence: every development run with all its steps, and the code the model wrote (kept for failed runs).
        val runs = host!!.api.capabilities!!.manager.store.runs(50).map { host!!.api.capabilities!!.manager.runJson(it) }
        File(out, "runs.json").writeText(JsonArray(runs).toString())
        File(paths.root, "capabilities/evidence").takeIf { it.isDirectory }?.copyRecursively(File(out, "evidence"), overwrite = true)
        val listed = second["listFinal"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        assertTrue(listed.any { it.contains("Weather") } && listed.any { it.contains("Map") }, "the list shows the existing experiences: $listed")
        assertEquals(installed, listed.any { it.contains("Explorer", ignoreCase = true) }, "the list shows the explorer exactly when it is installed: $listed")
        File(out, "summary.json").writeText(buildJsonObject { put("build", first); put("restarted", second) }.toString())
    }
}
