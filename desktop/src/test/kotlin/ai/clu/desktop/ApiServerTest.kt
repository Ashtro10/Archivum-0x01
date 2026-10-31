package ai.clu.desktop

import ai.clu.core.config.Settings
import ai.clu.core.llm.*
import ai.clu.core.util.CluJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** The local API end to end: the real host (database, core, governor, server) with a scripted engine instead of llama.cpp. */
class ApiServerTest {
    private val dir = Files.createTempDirectory("clu-api").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) }
    private val host = Host.start(DesktopPaths(File(dir, "home")), HostConfig(), port = 0, log = {})
    private val http = HttpClient.newHttpClient()

    /** Replies from a script, streaming in 5-character pieces; or, with [block], waits until the request is cancelled. */
    private inner class Engine(var reply: String = "Hello from CLU.", val block: Boolean = false) : LlmEngine {
        val started = CountDownLatch(1)
        override val id = "scripted"; override val source = "local"
        override fun isReady() = true
        override fun countTokens(messages: List<ChatMessage>) = messages.sumOf { it.content.length / 4 + 4 }
        override fun contextSize() = 4096
        override fun cancel() {}
        override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult {
            reply.chunked(5).forEach(onText)
            return GenResult(reply, GenStats(promptTokens = 10, generatedTokens = 4, promptMs = 1.0, generateMs = 1.0, firstTokenMs = 1.0, stopReason = "eog"), source)
        }
        override fun generate(messages: List<ChatMessage>, params: GenParams, signal: CancelSignal, onText: (String) -> Unit): GenResult {
            if (!block) return generate(messages, params, onText)
            started.countDown(); onText("Thinking")
            val t0 = System.currentTimeMillis()
            while (!signal.isCancelled && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(10)
            return cancelledResult(source)
        }
    }

    private fun use(engine: Engine) = engine.also { e -> host.core.remoteEngineProvider = { e }; host.core.settings.set(Settings.REMOTE_INFERENCE, "prefer_home") }

    @AfterEach fun stop() = host.stop()

    private fun req(path: String, method: String = "GET", body: String? = null, token: String? = host.token, accept: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create(host.url + path))
        token?.let { b.header("Authorization", "Bearer $it") }
        accept?.let { b.header("Accept", it) }
        b.method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun json(r: HttpResponse<String>) = CluJson.parseToJsonElement(r.body())

    /** A raw request, so the Host and Origin headers can be set the way a hostile page would send them. */
    private fun raw(host: String, origin: String? = null): String = Socket("127.0.0.1", this.host.api.port).use { s ->
        val o = origin?.let { "Origin: $it\r\n" } ?: ""
        s.getOutputStream().write("GET /v1/status HTTP/1.1\r\nHost: $host\r\n${o}Authorization: Bearer ${this.host.token}\r\nConnection: close\r\n\r\n".toByteArray())
        s.getInputStream().readBytes().toString(Charsets.UTF_8).lineSequence().first()
    }

    @Test fun healthNeedsNoTokenEverythingElseDoes() {
        assertEquals(200, req("/v1/health", token = null).statusCode())
        assertEquals(401, req("/v1/status", token = null).statusCode())
        assertEquals(401, req("/v1/status", token = "wrong-token-wrong-token-wrong-token-wrong").statusCode())
        val s = req("/v1/status"); assertEquals(200, s.statusCode())
        val o = json(s).jsonObject
        assertEquals("CLU", o["name"]!!.jsonPrimitive.content)
        assertEquals("NO_MODEL", o["model"]!!.jsonObject["status"]!!.jsonPrimitive.content, "no model loaded at start (idle footprint)")
        assertTrue(o["governor"]!!.jsonObject["budgetMB"]!!.jsonPrimitive.long == 16 * 1024L)
    }

    @Test fun onlyThisComputerCanCall() {
        assertTrue(host.api.address.isLoopbackAddress, "bound to 127.0.0.1 only")
        val port = host.api.port
        assertTrue(raw("127.0.0.1:$port").contains(" 200 "))
        assertTrue(raw("localhost:$port").contains(" 200 "))
        assertTrue(raw("evil.example:$port").contains(" 403 "), "DNS rebinding: a foreign Host is refused")
        assertTrue(raw("127.0.0.1:$port", origin = "https://evil.example").contains(" 403 "), "a cross-site request is refused")
        assertTrue(raw("127.0.0.1:$port", origin = "http://127.0.0.1:$port").contains(" 200 "), "the local shell's own origin is fine")
    }

    @Test fun aConversationAndATurnAsJson() {
        use(Engine("Hello from CLU."))
        val c = json(req("/v1/conversations", "POST", """{"title":"desk"}""")).jsonObject
        assertEquals("desk", c["title"]!!.jsonPrimitive.content)
        val id = c["id"]!!.jsonPrimitive.content
        val t = req("/v1/conversations/$id/turns", "POST", """{"text":"Hi there, how are you?"}""")
        assertEquals(200, t.statusCode(), t.body())
        assertEquals("Hello from CLU.", json(t).jsonObject["text"]!!.jsonPrimitive.content)
        val history = json(req("/v1/conversations/$id/messages")).jsonArray
        assertEquals(listOf("user", "assistant"), history.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("Hi there, how are you?", history[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue(json(req("/v1/conversations")).jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == id })
    }

    @Test fun aTurnStreamsAsServerSentEvents() {
        use(Engine("Streaming works nicely."))
        val id = json(req("/v1/conversations", "POST", "{}")).jsonObject["id"]!!.jsonPrimitive.content
        val r = req("/v1/conversations/$id/turns", "POST", """{"text":"Tell me something"}""", accept = "text/event-stream")
        assertEquals(200, r.statusCode())
        assertTrue(r.headers().firstValue("Content-Type").get().startsWith("text/event-stream"))
        val events = r.body().split("\n\n").filter { it.isNotBlank() }.map { e ->
            e.lines().first { it.startsWith("event: ") }.removePrefix("event: ") to CluJson.parseToJsonElement(e.lines().first { it.startsWith("data: ") }.removePrefix("data: ")).jsonObject }
        val text = events.filter { it.first == "text" }.joinToString("") { it.second["delta"]!!.jsonPrimitive.content }
        assertEquals("Streaming works nicely.", text, "the deltas add up to the reply")
        assertEquals("done", events.last().first)
        assertEquals("Streaming works nicely.", events.last().second["text"]!!.jsonPrimitive.content)
    }

    @Test fun stopCancelsTheReplyInProgress() {
        val engine = use(Engine(block = true))
        val id = json(req("/v1/conversations", "POST", "{}")).jsonObject["id"]!!.jsonPrimitive.content
        var result: HttpResponse<String>? = null
        val t = Thread { result = req("/v1/conversations/$id/turns", "POST", """{"text":"Write a long story"}""") }.apply { start() }
        assertTrue(engine.started.await(10, TimeUnit.SECONDS), "the reply started")
        assertEquals(200, req("/v1/stop", "POST").statusCode())
        t.join(5_000)
        assertFalse(t.isAlive, "the turn ended promptly after Stop")
        assertTrue(json(result!!).jsonObject["interrupted"]!!.jsonPrimitive.boolean, result!!.body())
    }

    @Test fun badRequestsAreRefusedClearly() {
        assertEquals(404, req("/v1/conversations/nope/turns", "POST", """{"text":"x"}""").statusCode())
        val id = json(req("/v1/conversations", "POST", "{}")).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(400, req("/v1/conversations/$id/turns", "POST", """{"text":"  "}""").statusCode())
        assertEquals(413, req("/v1/conversations", "POST", "{\"title\":\"" + "x".repeat(70_000) + "\"}").statusCode())
        assertEquals(404, req("/v1/nothing").statusCode())
    }

    @Test fun theModelCanBeUnloadedOnRequest() {
        val r = req("/v1/model/unload", "POST")
        assertEquals(200, r.statusCode())
        assertTrue(json(r).jsonObject["status"]!!.jsonPrimitive.content in setOf("NO_MODEL", "UNLOADED"))
    }
}
