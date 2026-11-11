package ai.clu.desktop

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

/** The OpenAI-compatible endpoint on the PC: this computer's local key (or the window's token) only, the window learns it, errors in OpenAI form. */
class OpenAiRouteTest {
    private val dir = Files.createTempDirectory("clu-openai").toFile()
    private var host: RunningHost? = null
    @AfterEach fun stop() { host?.stop(); dir.deleteRecursively() }
    private val http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
    private fun call(url: String, key: String?, body: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI(url)).method(if (body != null) "POST" else "GET", body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        key?.let { b.header("Authorization", "Bearer $it") }; if (body != null) b.header("Content-Type", "application/json")
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test fun localToolsUseCLUsModelWithTheLocalKey() {
        val h = Host.start(DesktopPaths(File(dir, "home")), HostConfig(maintenanceMinutes = 0, governorIntervalSeconds = 600), port = 0, log = {}).also { host = it }
        val token = Json.parseToJsonElement(call("${h.url}/v1/session", null, """{"code":"${h.api.issueLaunchCode()}"}""").body()).jsonObject["token"]!!.jsonPrimitive.content
        val info = Json.parseToJsonElement(call("${h.url}/v1/openai", token).body()).jsonObject
        val base = info["baseUrl"]!!.jsonPrimitive.content; val key = info["apiKey"]!!.jsonPrimitive.content
        assertTrue(base.endsWith("/openai/v1") && key.length >= 32, info.toString())
        assertEquals(key, File(dir, "home").walkTopDown().first { it.name == "local-api-key" }.readText().trim(), "kept for tools configured once")

        assertEquals(401, call("$base/models", null).statusCode())
        assertEquals(401, call("$base/models", "wrong").statusCode())
        assertEquals(200, call("$base/models", key).statusCode())
        // No model registered in a fresh install: refused in OpenAI's error form, not faked.
        val r = call("$base/chat/completions", key, """{"messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(503, r.statusCode(), r.body())
        assertNotNull(Json.parseToJsonElement(r.body()).jsonObject["error"]?.jsonObject?.get("message"))
    }
}
