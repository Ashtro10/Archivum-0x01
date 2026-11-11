package ai.clu.core

import ai.clu.core.api.Exchange
import ai.clu.core.api.LoopbackServer
import ai.clu.core.api.ShellHost
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * The PC window's shell hosted on a tablet: its files with the PC's security policy, the one-time launch code, the token,
 * the PC answering when paired and reachable (with the device key, never the shell's token or an Origin), and this
 * device answering when it is not, including a streamed chat reply from the on-device model.
 */
class ShellHostTest {
    private val servers = mutableListOf<LoopbackServer>()
    @AfterEach fun stop() { servers.forEach { it.stop() } }
    private fun serve(h: (Exchange) -> Unit) = LoopbackServer(handler = h).also { servers += it }.start()

    private val assets = mapOf("index.html" to "<html>CLU</html>", "shell.js" to "/* shell */", "primus/app.js" to "/* primus */")
    private var remote: ShellHost.Remote? = null
    private val core = newCore().apply { useEngine(ScriptedEngine().reply("Hello from the tablet.")) }
    private val windowCalls = CopyOnWriteArrayList<String>()
    private val volumeCalls = CopyOnWriteArrayList<String>()
    init { core.toolRegistry.register(object : ai.clu.core.tools.Tool {
        override val name = "device.volume"; override val description = "volume"; override val argsExample = "{}"
        override val sensitivity = ai.clu.core.security.Sensitivity.ROUTINE; override val deviceAction = true
        override suspend fun run(args: JsonObject, ctx: ai.clu.core.tools.ToolContext): ai.clu.core.tools.ToolResult {
            volumeCalls += args.toString(); return ai.clu.core.tools.ToolResult.ok("Volume set to 40%.") }
    }) }
    private val host = ShellHost(core, "9.9.9", { assets[it]?.toByteArray() }, { remote }, { windowCalls += it },
        deviceTools = setOf("device.volume"), platform = buildJsonObject { put("platform", "android"); putJsonArray("pcOnly") { add("agents") } })
    private val port = serve(host::handle)

    private fun call(path: String, method: String = "GET", body: String? = null, token: String? = null, accept: String? = null): Triple<Int, String, Map<String, String>> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method; token?.let { c.setRequestProperty("Authorization", "Bearer $it") }; accept?.let { c.setRequestProperty("Accept", it) }
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
        return Triple(code, text, c.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") })
    }
    private fun session(): String {
        val code = host.newLaunchCode()
        val (status, text) = call("/v1/session", "POST", """{"code":"$code"}""")
        assertEquals(200, status, text)
        assertEquals(401, call("/v1/session", "POST", """{"code":"$code"}""").first, "a launch code works once")
        return Json.parseToJsonElement(text).jsonObject["token"]!!.jsonPrimitive.content
    }

    @Test fun servesTheShellFilesWithThePCsSecurityPolicy() {
        val (code, text, headers) = call("/")
        assertEquals(200, code); assertEquals("<html>CLU</html>", text)
        assertTrue(headers["Content-Security-Policy"]!!.contains("script-src 'self'"))
        assertTrue(headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))
        assertTrue(call("/shell/primus/app.js").third["Content-Security-Policy"]!!.contains("frame-ancestors 'self'"), "the Primus core may be framed by the shell")
        assertEquals(404, call("/shell/../secret.js").first)
        assertEquals(404, call("/shell/missing.js").first)
    }

    @Test fun theApiNeedsThisRunsToken() {
        assertEquals(401, call("/v1/status").first)
        assertEquals(401, call("/v1/status", token = "guess").first)
        assertEquals(401, call("/v1/session", "POST", """{"code":"guess"}""").first)
        val t = session()
        assertEquals(200, call("/v1/status", token = t).first)
    }

    @Test fun withoutAPCThisDeviceAnswersAndStreamsAChatReply() {
        val t = session()
        val st = Json.parseToJsonElement(call("/v1/status", token = t).second).jsonObject
        assertEquals("device", st["link"]!!.jsonPrimitive.content)
        val (made, body) = call("/v1/conversations", "POST", "{}", t)
        assertEquals(201, made, body)
        val id = Json.parseToJsonElement(body).jsonObject["id"]!!.jsonPrimitive.content
        val (code, sse, headers) = call("/v1/conversations/$id/turns", "POST", """{"text":"Hi there"}""", t, "text/event-stream")
        assertEquals(200, code, sse)
        assertTrue(headers["Content-Type"]!!.startsWith("text/event-stream"))
        assertTrue("event: done" in sse && "Hello from the tablet." in sse, sse)
        val msgs = Json.parseToJsonElement(call("/v1/conversations/$id/messages", token = t).second).jsonArray
        assertEquals(listOf("user", "assistant"), msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val settings = Json.parseToJsonElement(call("/v1/settings", token = t).second).jsonObject
        assertTrue(settings["settings"]!!.jsonArray.isNotEmpty())
        val (models, why) = call("/v1/models", token = t)
        assertEquals(503, models); assertTrue("connected to your PC" in why, why)
    }

    @Test fun whenPairedThePCAnswersWithTheDeviceKeyAndStreamsThrough() {
        val seen = CopyOnWriteArrayList<String>()
        val pcPort = serve { ex ->
            seen += "${ex.method} ${ex.path}?${ex.rawQuery} auth=${ex.header("Authorization")} origin=${ex.header("Origin")} body=${String(ex.body)}"
            if (ex.path.endsWith("/turns")) ex.stream(200, "text/event-stream") { out -> listOf("Hel", "lo").forEach { out.write("event: text\ndata: {\"delta\":\"$it\"}\n\n".toByteArray()); out.flush() } }
            else ex.json(200, """{"name":"CLU","from":"pc"}""")
        }
        remote = ShellHost.Remote("http://127.0.0.1:$pcPort", "dev.key")
        val t = session()
        val st = Json.parseToJsonElement(call("/v1/status?x=1", token = t).second).jsonObject
        assertEquals("pc", st["from"]!!.jsonPrimitive.content)
        val (code, sse) = call("/v1/conversations/c1/turns", "POST", """{"text":"hi"}""", t, "text/event-stream")
        assertEquals(200, code); assertTrue("\"Hel\"" in sse && "\"lo\"" in sse, sse)
        assertTrue(seen.all { "auth=Bearer dev.key" in it && "origin=null" in it }, "the PC gets the device key, never the shell's token or an Origin: $seen")
        assertTrue(seen.none { t in it }, "the shell's token never leaves the device")
        assertTrue(seen.any { it.startsWith("GET /v1/status?x=1") } && seen.any { it.contains("body={\"text\":\"hi\"}") }, seen.toString())
        // The window's own lifecycle stays on the tablet.
        assertEquals(200, call("/v1/shell/ping", "POST", "{}", t).first)
        assertEquals(200, call("/v1/shell/quit", "POST", "{}", t).first)
        assertEquals(listOf("quit"), windowCalls.toList()); assertTrue(seen.none { "/v1/shell/" in it })
        assertEquals("pc", host.link())
    }

    @Test fun anUnreachablePCFallsBackToThisDevice() {
        val dead = java.net.ServerSocket(0).use { it.localPort } // nothing listens here now
        remote = ShellHost.Remote("http://127.0.0.1:$dead", "dev.key")
        val t = session()
        val st = Json.parseToJsonElement(call("/v1/status", token = t).second).jsonObject
        assertEquals("device", st["host"]!!.jsonPrimitive.content, "answered here")
        assertEquals("offline", host.link())
        assertTrue(call("/v1/models", token = t).second.contains("can't be reached"))
        val link = Json.parseToJsonElement(call("/v1/shell/link", token = t).second).jsonObject
        assertEquals("offline", link["link"]!!.jsonPrimitive.content)
    }

    @Test fun withoutAPCTheArchiveAndMemoryViewsWorkOnThisDevice() {
        val t = session()
        val (made, body) = call("/v1/memories", "POST", """{"title":"Lonavala trip","content":"Went with Ashutosh","when":"March 2018","people":["Ashutosh"]}""", t)
        assertEquals(201, made, body)
        assertTrue(call("/v1/memories?q=Lonavala", token = t).second.contains("Lonavala trip"))
        assertTrue(call("/v1/timeline", token = t).second.contains("Lonavala trip"), "dated memories on the timeline")
        assertEquals(201, call("/v1/people", "POST", """{"name":"Ashutosh","relation":"friend"}""", t).first)
        assertTrue(call("/v1/people", token = t).second.contains("Ashutosh"))
        assertEquals(200, call("/v1/memory/overview", token = t).first)
        assertTrue(call("/v1/memory/search?q=Lonavala", token = t).second.contains("Lonavala"))
        val backups = call("/v1/memory/backups", token = t)
        assertEquals(404, backups.first, "backups are the PC's: said so, not faked"); assertTrue("not available here" in backups.second)
    }

    @Test fun whilePairedADeviceCommandRunsHereNotOnThePC() {
        val seen = CopyOnWriteArrayList<String>()
        val pcPort = serve { ex -> seen += ex.path; ex.stream(200, "text/event-stream") { o -> o.write("event: done\ndata: {\"text\":\"pc\"}\n\n".toByteArray()) } }
        remote = ShellHost.Remote("http://127.0.0.1:$pcPort", "dev.key")
        val t = session()
        val (code, sse) = call("/v1/conversations/c1/turns", "POST", """{"text":"set volume to 40%"}""", t, "text/event-stream")
        assertEquals(200, code); assertTrue("Volume set to 40%." in sse && "event: done" in sse, sse)
        assertEquals(1, volumeCalls.size, "ran on this device"); assertTrue(seen.none { it.endsWith("/turns") }, "the PC was not asked: $seen")
        call("/v1/conversations/c1/turns", "POST", """{"text":"explain black holes"}""", t, "text/event-stream")
        assertTrue(seen.any { it.endsWith("/turns") }, "everything else goes to the PC")
        val p = Json.parseToJsonElement(call("/v1/shell/platform", token = t).second).jsonObject
        assertEquals("android", p["platform"]!!.jsonPrimitive.content); assertEquals("pc", p["link"]!!.jsonPrimitive.content)
        assertEquals(listOf("device.volume"), p["deviceTools"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
