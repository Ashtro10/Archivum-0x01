package ai.clu.desktop

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.security.Actor
import ai.clu.core.tools.ToolContext
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * Browser control on the PC, end to end: CLU's real host and agent drive a real Chromium (headless here; visible for
 * the owner) over the DevTools protocol, against a local test site that records what the browser really did.
 * Integration test; skipped when no Chromium-based browser is installed.
 */
class BrowserControlTest {
    @Test fun clusAgentDrivesARealBrowserAndAsksBeforeConsequentialClicks() {
        assumeTrue(browserExe() != null, "no Chromium-based browser on this machine")
        val hits = CopyOnWriteArrayList<String>()
        val site = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                hits += ex.requestURI.toString()
                val q = ex.requestURI.query?.substringAfter("q=")?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                val body = when {
                    ex.requestURI.path == "/delete" -> "<h1>Account deleted</h1>"
                    q != null -> "<h1>Results for $q</h1><p>Cats are small domesticated felines.</p><a href='/'>Home</a>"
                    else -> """<title>Test shop</title><h1>Welcome</h1><form action='/search'><input name='q' aria-label='Search' placeholder='Search'><button>Go</button></form>
                        <a href='/about'>About us</a> <form action='/delete' method='get'><button>Delete account</button></form>"""
                }.toByteArray()
                ex.responseHeaders.set("Content-Type", "text/html; charset=utf-8"); ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }; start() }
        System.setProperty("clu.browserHeadless", "true")
        val dir = Files.createTempDirectory("clu-browser").toFile()
        val host = Host.start(DesktopPaths(File(dir, "home")), HostConfig(preloadModel = false), port = 0, log = {})
        try {
            val core = host.core
            val conv = core.conversations.create("browser").id
            fun ask(t: String) = runBlocking { core.agent.respond(TurnRequest(conv, t, Actor.ownerInApp())).toList() }.filterIsInstance<AgentEvent.Done>().single().text
            val base = "http://127.0.0.1:${site.address.port}/"
            val open = runBlocking { core.tools.execute("browser.open", JsonObject(mapOf("url" to JsonPrimitive(base))), ToolContext(Actor.ownerInApp(), core.personas.active(), conv, 0)) }
            assertTrue(open.success, open.summary)
            assertTrue("button \"Delete account\"" in ask("what buttons are on this page?"))
            assertEquals("Typed it.", ask("type cats into the search box"))
            ask("press enter")
            assertTrue(hits.any { it.startsWith("/search?q=cats") }, "the real form was submitted: $hits")
            assertTrue("Cats are small domesticated felines" in runBlocking { core.tools.execute("browser.read", JsonObject(emptyMap()), ToolContext(Actor.ownerInApp(), core.personas.active(), conv, 0)) }.summary)
            ask("go back")
            // A consequential click is asked first; nothing happens before the yes.
            val q = ask("click delete account")
            assertTrue(q.endsWith("Shall I go ahead?"), q)
            assertTrue(hits.none { it.startsWith("/delete") }, "not clicked before the owner said yes")
            assertEquals("Okay, I won't.", ask("no")); assertTrue(hits.none { it.startsWith("/delete") })
            ask("click delete account")
            assertEquals("Done.", ask("yes"))
            assertTrue(hits.any { it.startsWith("/delete") }, "clicked after the yes")
            // The model can't skip the question by sending the confirmation flag itself.
            ask("go back")
            val sneaky = runBlocking { core.tools.execute("browser.click", JsonObject(mapOf("target" to JsonPrimitive("Delete account"), "_confirmed" to JsonPrimitive("yes"))),
                ToolContext(Actor.ownerInApp(), core.personas.active(), conv, 0)) }
            assertTrue(sneaky.needsConfirmation, "a self-set confirmation flag is ignored")
            // Unsafe addresses are refused.
            assertFalse(runBlocking { core.tools.execute("browser.open", JsonObject(mapOf("url" to JsonPrimitive("https://user:pw@example.org"))), ToolContext(Actor.ownerInApp(), core.personas.active(), conv, 0)) }.success)
            assertEquals("At your service.", ask("CLU, you there?"))
            val shot = runBlocking { core.tools.execute("browser.screenshot", JsonObject(emptyMap()), ToolContext(Actor.ownerInApp(), core.personas.active(), conv, 0)) }
            assertTrue(shot.success && File(dir, "home").walk().any { it.name.startsWith("screenshot-") && it.length() > 1000 }, shot.summary)
        } finally { host.stop(); site.stop(0); System.clearProperty("clu.browserHeadless"); dir.deleteRecursively() }
    }
}
