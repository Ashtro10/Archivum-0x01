package ai.clu.core

import ai.clu.core.api.LoopbackServer
import ai.clu.core.api.ShellApi
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import kotlin.test.*

/** The tablet's local web server: plain requests, request bodies, streamed replies, limits, and loopback-only binding. */
class LoopbackServerTest {
    private fun <T> server(maxBody: Int = 1024, block: (Int) -> T): T {
        val s = LoopbackServer(maxBody) { ex ->
            when (ex.path) {
                "/echo" -> ex.json(200, buildJsonObject { put("method", ex.method); put("body", String(ex.body)); put("q", ex.query("q") ?: ""); put("auth", ex.header("Authorization") ?: "") }.toString())
                "/sse" -> ex.stream(200, "text/event-stream") { out -> repeat(3) { out.write(ShellApi.sse("text", buildJsonObject { put("delta", "$it") })); out.flush() } }
                "/boom" -> error("handler failure")
                else -> ex.json(404, """{"error":"not found"}""")
            }
        }
        val port = s.start()
        try { return block(port) } finally { s.stop() }
    }

    private fun call(port: Int, path: String, method: String = "GET", body: String? = null): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method; c.setRequestProperty("Authorization", "Bearer t")
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
        return code to text
    }

    @Test fun answersRequestsWithBodiesQueriesAndHeaders() = server { port ->
        val (code, text) = call(port, "/echo?q=hello%20there", "POST", "payload")
        assertEquals(200, code)
        val o = Json.parseToJsonElement(text).jsonObject
        assertEquals("POST", o["method"]!!.jsonPrimitive.content)
        assertEquals("payload", o["body"]!!.jsonPrimitive.content)
        assertEquals("hello there", o["q"]!!.jsonPrimitive.content)
        assertEquals("Bearer t", o["auth"]!!.jsonPrimitive.content)
        assertEquals(404, call(port, "/nothing").first)
    }

    @Test fun streamsServerSentEventsInOrder() = server { port ->
        val (code, text) = call(port, "/sse")
        assertEquals(200, code)
        assertEquals(listOf("0", "1", "2"), Regex("\"delta\":\"(\\d)\"").findAll(text).map { it.groupValues[1] }.toList())
    }

    @Test fun refusesOversizedBodiesAndSurvivesHandlerFailures() = server(maxBody = 16) { port ->
        assertEquals(413, call(port, "/echo", "POST", "x".repeat(100)).first)
        assertEquals(500, call(port, "/boom").first)
        assertEquals(200, call(port, "/echo").first) // still serving
    }

    @Test fun malformedRequestsGetAnErrorNotAHang() = server { port ->
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write("NONSENSE\r\n\r\n".toByteArray())
            assertTrue(String(s.getInputStream().readBytes()).startsWith("HTTP/1.1 400"))
        }
    }

    @Test fun listensOnLoopbackOnly() {
        val s = LoopbackServer { it.json(200, "{}") }
        s.start()
        try {
            val addr = java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            if (addr != null) assertFailsWith<java.io.IOException> { Socket(addr, s.port).close() }
        } finally { s.stop() }
    }
}
