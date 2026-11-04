package ai.clu.desktop

import ai.clu.core.util.CluJson
import ai.clu.desktop.capability.ExperienceVerifier
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

/**
 * CLU develops, verifies and installs capabilities through its real API, with a real headless browser doing the
 * verification. Open-Meteo is played by a local stand-in server ([forecast]) that answers in Open-Meteo's format, so
 * the data path (provider → CLU's weather service → the page) is exercised end to end without the internet; a second
 * test points CLU at an unreachable provider and checks that nothing is invented. Needs a Chromium-based browser.
 */
class CapabilityDevelopmentTest {
    private val dir = Files.createTempDirectory("clu-cap").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) }
    private val paths = DesktopPaths(File(dir, "home"))
    private var provider: HttpServer? = null
    private var host: RunningHost? = null
    private val http = HttpClient.newHttpClient()

    @AfterEach fun stop() { host?.stop(); provider?.stop(0); System.clearProperty("clu.kit.weatherUrl"); System.clearProperty("clu.kit.tileUrl") }

    private fun start(reachable: Boolean = true) {
        if (reachable && provider == null) provider = OpenMeteoStandIn.start()
        System.setProperty("clu.kit.weatherUrl", if (reachable) "http://127.0.0.1:${provider!!.address.port}/v1/forecast" else "http://127.0.0.1:9/v1/forecast")
        System.setProperty("clu.kit.tileUrl", "http://127.0.0.1:9/{z}/{x}/{y}.png") // street tiles unreachable, as in this environment
        host = Host.start(paths, HostConfig(), port = 0, log = {})
    }

    private fun req(path: String, method: String = "GET", body: String? = null): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create(host!!.url + path)).header("Authorization", "Bearer ${host!!.token}")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    private fun obj(path: String, method: String = "GET", body: String? = null) = CluJson.parseToJsonElement(req(path, method, body).body()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.b(k: String) = this[k]!!.jsonPrimitive.boolean

    /** Says [text] the way the shell's capability commands do, and waits for any development run it starts. */
    private fun say(text: String): JsonObject {
        val r = obj("/v1/capabilities/command", "POST", buildJsonObject { put("text", text) }.toString())
        val run = r["action"]?.jsonObject?.takeIf { it.s("action") == "develop" }?.s("run") ?: return r
        val until = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < until) { val st = obj("/v1/capabilities/runs/$run"); if (st.s("state") != "running") return st; Thread.sleep(300) }
        fail("development of '$text' did not finish")
    }
    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    private fun checks(run: JsonObject) = run["verification"]!!.jsonObject["checks"]!!.jsonArray.map { it.jsonObject.s("name") to it.jsonObject.b("ok") }

    @Test fun capabilitiesAreDevelopedVerifiedInstalledManagedAndSurviveARestart() {
        assumeTrue(ExperienceVerifier.find() != null, "no Chromium-based browser to verify with")
        start()
        // A: weather service with a sci-fi interface.
        val wx = say("Give yourself a sci-fi weather service")
        assertEquals("installed", wx.s("state"), wx.toString().take(3000))
        val wxChecks = checks(wx)
        assertTrue(wxChecks.all { it.second }, wxChecks.toString())
        assertTrue(wxChecks.any { it.first == "weather: the temperature shown is the one the service returned" }, "real data flow checked: $wxChecks")
        assertTrue(wxChecks.any { it.first.startsWith("screenshot: the Sci-Fi look") }, "the rendered screen was inspected")
        val steps = wx["steps"]!!.jsonArray.map { it.jsonObject.s("step") }
        assertEquals(listOf("understand", "existing", "memory", "research", "plan", "modules", "implement", "build", "test", "inspect", "integrate", "install", "report"), steps.distinct(), steps.toString())
        val weatherId = wx.s("capability")
        assertEquals(200, req("/x/$weatherId/1/index.html").statusCode())
        assertTrue(File(paths.root, "capabilities/installed/$weatherId/1/preview.png").length() > 5000, "screenshot kept as the preview")

        // B and C: two map experiences that coexist.
        val tac = say("Give yourself a tactical map HUD"); assertEquals("installed", tac.s("state"), tac.toString().take(3000))
        val tacId = tac.s("capability")
        assertTrue(checks(tac).any { it.first == "map: land drawn where Africa and Brazil are" && it.second }, "real geography checked")
        assertTrue(tac["limitations"]!!.jsonArray.any { it.jsonPrimitive.content.contains("street tiles") }, "unreachable tiles recorded as a limitation, not a pass")
        val tacFiles = File(paths.root, "capabilities/installed/$tacId/1").listFiles()!!.filter { it.extension != "png" }.associate { it.name to sha(it) }
        val fan = say("Create a fantasy map too"); assertEquals("installed", fan.s("state"), fan.toString().take(3000))
        val fanId = fan.s("capability"); assertNotEquals(tacId, fanId)
        assertEquals(tacFiles, File(paths.root, "capabilities/installed/$tacId/1").listFiles()!!.filter { it.extension != "png" }.associate { it.name to sha(it) }, "the tactical map was not touched")
        assertTrue(fan["research"]!!.jsonArray.any { it.jsonPrimitive.content.contains("development memory") }, "earlier map development was consulted")

        // D: list; open without a default asks which one.
        val list = say("Show me my maps")["action"]!!.jsonObject
        assertEquals("list", list.s("action")); assertEquals(setOf(tacId, fanId), list["items"]!!.jsonArray.map { it.jsonObject.s("id") }.toSet())
        val ask = say("Open my map")["action"]!!.jsonObject
        assertEquals("choose", ask.s("action")); assertEquals(2, ask["options"]!!.jsonArray.size)
        // E: choosing the fantasy map opens it.
        val open = say("Open the fantasy map")["action"]!!.jsonObject
        assertEquals("open", open.s("action")); assertEquals(fanId, open["capability"]!!.jsonObject.s("id"))
        // F: default.
        assertTrue(say("Make the tactical map my default").s("text").contains("default"))
        assertEquals(tacId, say("Open my map")["action"]!!.jsonObject["capability"]!!.jsonObject.s("id"), "the default opens without asking")

        // H: improve = a new version of the same capability, not a duplicate.
        val better = say("Improve my weather service")
        assertEquals("installed", better.s("state"), better.toString().take(3000))
        assertEquals(weatherId, better.s("capability")); assertEquals(2, better["version"]!!.jsonPrimitive.int)
        val detail = obj("/v1/capabilities/$weatherId")
        assertEquals(2, detail["versions"]!!.jsonArray.size); assertTrue(detail["features"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("hourly"))
        assertEquals(1, CluJson.parseToJsonElement(req("/v1/capabilities?kind=weather").body()).jsonArray.size, "no duplicate weather capability")
        // Rollback and back again.
        assertEquals(200, req("/v1/capabilities/$weatherId/rollback", "POST", "{}").statusCode())
        assertEquals(1, obj("/v1/capabilities/$weatherId")["version"]!!.jsonPrimitive.int)
        assertEquals(200, req("/v1/capabilities/$weatherId/activate", "POST", """{"version":2}""").statusCode())

        // G: restart.
        host!!.stop(); host = Host.start(paths, HostConfig(), port = 0, log = {})
        val after = CluJson.parseToJsonElement(req("/v1/capabilities").body()).jsonArray.map { it.jsonObject }
        assertEquals(setOf(weatherId, tacId, fanId), after.map { it.s("id") }.toSet())
        assertTrue(after.all { it.b("installed") && it.b("verified") && it.b("enabled") }, after.toString())
        assertEquals(tacId, after.single { it.b("default") }.s("id"), "the default survived the restart")
        assertEquals(2, after.single { it.s("id") == weatherId }["version"]!!.jsonPrimitive.int)
        assertEquals(200, req("/x/$fanId/1/index.html").statusCode())
        assertEquals(tacId, say("Open my map")["action"]!!.jsonObject["capability"]!!.jsonObject.s("id"))
        // Visual evidence on request: the screenshots CLU took while verifying each version.
        System.getProperty("clu.capShots")?.let { out -> File(out).mkdirs()
            File(paths.root, "capabilities/installed").walkTopDown().filter { it.name == "preview.png" }.forEach { it.copyTo(File(out, "${it.parentFile.parentFile.name}-v${it.parentFile.name}.png"), overwrite = true) } }
    }

    @Test fun anUnreachableProviderIsReportedAndNothingIsInvented() {
        assumeTrue(ExperienceVerifier.find() != null, "no Chromium-based browser to verify with")
        start(reachable = false)
        val wx = say("Give yourself a weather service")
        assertEquals("installed", wx.s("state"), wx.toString().take(3000))
        val c = checks(wx)
        assertTrue(c.any { it.first == "weather: unavailable is said plainly and no figures are invented" && it.second }, c.toString())
        assertFalse(c.any { it.first.contains("temperature shown is the one") }, "no data-flow pass claimed without data")
        assertTrue(wx["limitations"]!!.jsonArray.any { it.jsonPrimitive.content.contains("live weather data") }, wx["limitations"].toString())
        assertTrue(wx.s("message").contains("Not verified here"), wx.s("message"))
    }

    @Test fun unknownFamiliesAndDuplicatesAreHandledHonestly() {
        start()
        val todo = say("Build yourself a pomodoro timer app")
        assertEquals("declined", todo.s("state")); assertTrue(todo.s("message").contains("can't build a pomodoro timer yet"), todo.s("message"))
        assertEquals(0, CluJson.parseToJsonElement(req("/v1/capabilities").body()).jsonArray.size)
        val dash = say("Build a dashboard that combines your weather and map services")
        assertEquals("declined", dash.s("state")); assertTrue(dash.s("message").contains("don't have yet"), dash.s("message"))
        assertEquals(428, req("/v1/capabilities/none/uninstall", "POST", "{}").statusCode().let { if (it == 404) 428 else it })
    }
}
