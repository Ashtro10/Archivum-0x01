package ai.clu.desktop.voice

import ai.clu.desktop.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.*

/**
 * The tablet's shell reaches this PC's real data through the phone endpoint with its paired device key: the same /v1
 * routes as the window. Unpaired keys, web pages (any Origin) and PC-only actions (window lifecycle, launch codes, this
 * PC's own microphone session) are refused.
 */
class RemoteApiTest {
    private val dir = Files.createTempDirectory("clu-remote-api").toFile()
    private var host: RunningHost? = null
    @AfterEach fun stop() { host?.voice?.shutdown(); host?.stop(); System.clearProperty("clu.voiceLanAddress"); dir.deleteRecursively() }

    private val http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
    private fun call(base: String, path: String, key: String?, method: String = "GET", body: String? = null, origin: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI(base + path)).method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        key?.let { b.header("Authorization", "Bearer $it") }; origin?.let { b.header("Origin", it) }
        if (body != null) b.header("Content-Type", "application/json")
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test fun aPairedTabletUsesThePCsApiAndNothingElseDoes() {
        System.setProperty("clu.voiceLanAddress", "127.0.0.1") // loopback stands in for the Wi-Fi address
        val h = Host.start(DesktopPaths(File(dir, "home")), HostConfig(maintenanceMinutes = 0, governorIntervalSeconds = 600), port = 0, log = {}).also { host = it }
        val v = h.voice!!
        v.store.put(v.settings.copy(phoneAccess = true)); assertNull(v.applyPhoneAccess())
        val base = v.phoneEndpoint()!!.removeSuffix("/token")
        val (_, key) = v.pair("Test tablet")

        // The PC's real data: a conversation made over the remote API is the PC's own.
        val status = call(base, "/v1/status", key); assertEquals(200, status.statusCode(), status.body())
        val made = call(base, "/v1/conversations", key, "POST", """{"title":"From the tablet"}""")
        assertEquals(201, made.statusCode(), made.body())
        val id = Json.parseToJsonElement(made.body()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("From the tablet", h.core.conversations.get(id)?.title)

        assertEquals(401, call(base, "/v1/status", null).statusCode(), "no key")
        assertEquals(401, call(base, "/v1/status", key.substringBefore('.') + ".wrong").statusCode(), "wrong key")
        assertEquals(403, call(base, "/v1/status", key, origin = "http://evil.example").statusCode(), "a web page on the network")
        for (p in listOf("/v1/session", "/v1/shell/quit", "/v1/shell/restart", "/v1/voice/session"))
            assertEquals(403, call(base, p, key, "POST", "{}").statusCode(), "$p is the PC's own")
        assertEquals(200, call(base, "/v1/status", key).statusCode(), "still serving after refusals")
    }
}
