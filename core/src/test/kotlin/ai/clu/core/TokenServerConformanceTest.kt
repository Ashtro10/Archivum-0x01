package ai.clu.core

import ai.clu.core.config.Settings
import ai.clu.core.livekit.LiveKitException
import ai.clu.core.tools.HttpFetcher
import ai.clu.core.tools.HttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import kotlin.test.*

/**
 * The app's token client (TokenProvisioner, unchanged) against the REAL token server (server/clu_voice_server.py)
 * running as a separate process: device-key authentication, room/identity rules, the claims the app checks, and the
 * refusal messages the app shows. Opt-in: -Dclu.serverPython=<python with server/requirements.txt>.
 * (No LiveKit server is needed: the server's agent dispatch fails and is logged, the token is still issued.)
 */
class TokenServerConformanceTest {
    private val python = System.getProperty("clu.serverPython")
    private val serverDir = File(System.getProperty("clu.serverDir", "../server"))
    private var proc: Process? = null

    /** Same as the app's AndroidHttp (HttpURLConnection), without Android. */
    private class JvmHttp : HttpFetcher {
        override suspend fun get(url: String, headers: Map<String, String>) = error("unused")
        override suspend fun postJson(url: String, json: String, headers: Map<String, String>): HttpResponse {
            val c = (URL(url).openConnection() as HttpURLConnection)
            try {
                c.connectTimeout = 10_000; c.readTimeout = 30_000; c.requestMethod = "POST"; c.doOutput = true
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toByteArray()) }
                val code = c.responseCode
                val body = (if (code in 200..399) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                return HttpResponse(code, body)
            } finally { c.disconnect() }
        }
    }

    @AfterEach fun stop() { proc?.destroy(); proc?.waitFor(); work?.deleteRecursively() }
    private var work: File? = null

    @Test fun appTokenClientAgreesWithTheRealTokenServer(): Unit = runBlocking {
        assumeTrue(python != null, "set -Dclu.serverPython to run against the real token server")
        val work = Files.createTempDirectory("clu-token").toFile().also { this@TokenServerConformanceTest.work = it }
        val port = ServerSocket(0).use { it.localPort }
        fun env(pb: ProcessBuilder) = pb.apply {
            environment().apply {
                listOf("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy").forEach { remove(it) }
                put("LIVEKIT_API_KEY", "devkey"); put("LIVEKIT_API_SECRET", "secret-for-the-conformance-test-0123456789")
                put("CLU_PUBLIC_LIVEKIT_URL", "ws://127.0.0.1:7880"); put("CLU_INTERNAL_LIVEKIT_URL", "ws://127.0.0.1:9")
                put("CLU_DEVICES", File(work, "devices.json").path); put("CLU_PORT", "$port"); put("CLU_BIND", "127.0.0.1"); put("CLU_RUN_AGENT", "0")
            }
            directory(serverDir)
        }
        val add = env(ProcessBuilder(python, "clu_voice_server.py", "add-device", "phone")).redirectErrorStream(true).start()
        val key = add.inputStream.bufferedReader().readLines().last().trim()
        assertEquals(0, add.waitFor()); assertTrue(key.startsWith("phone."), "device key format id.secret")
        proc = env(ProcessBuilder(python, "clu_voice_server.py", "serve")).redirectErrorStream(true).redirectOutput(File(work, "server.log")).start()

        val platform = TestPlatform(); var deviceKey: String? = key
        val core = newCore(platform = platform)
        core.settings.set(Settings.LK_TOKEN_URL, "http://127.0.0.1:$port/token", verified = true)
        val tokens = ai.clu.core.livekit.TokenProvisioner(core.settings, JvmHttp(), { deviceKey }, System::currentTimeMillis)
        val deadline = System.currentTimeMillis() + 20_000
        var t = runCatching { tokens.token("clu-phone", "clu-phone", "Clu (phone)") }
        while (t.isFailure && System.currentTimeMillis() < deadline) { Thread.sleep(300); t = runCatching { tokens.token("clu-phone", "clu-phone", "Clu (phone)") } }
        val token = t.getOrThrow()
        assertEquals("ws://127.0.0.1:7880", token.serverUrl)
        assertEquals("clu-phone", token.room); assertEquals("clu-phone", token.identity)
        assertTrue(token.expiresAtMs - System.currentTimeMillis() in 500_000..610_000, "10-minute token")

        fun refusal(block: suspend () -> Unit): String = runBlocking { tokens.invalidate(); assertFailsWith<LiveKitException> { block() }.message!! }
        // The room/identity rule: the device's own room only.
        assertTrue(refusal { tokens.token("clu-tablet", "clu-tablet", "x") }.contains("may only join room 'clu-phone'"))
        // A different agent name than the server runs: the server's reason reaches the screen.
        core.settings.set(Settings.LK_AGENT_NAME, "jarvis")
        assertTrue(refusal { tokens.token("clu-phone", "clu-phone", "x") }.contains("this server runs 'clu'"))
        core.settings.set(Settings.LK_AGENT_NAME, "clu")
        // Wrong key, then throttling after repeated wrong keys (10 per minute per address).
        deviceKey = "phone.wrong-secret-0123456789abcdef"
        assertTrue(refusal { tokens.token("clu-phone", "clu-phone", "x") }.contains("rejected this device key"))
        repeat(9) { refusal { tokens.token("clu-phone", "clu-phone", "x") } }
        assertTrue(refusal { tokens.token("clu-phone", "clu-phone", "x") }.contains("about a minute"))
        val log = File(work, "server.log").readText()
        assertFalse(log.contains(key.substringAfter('.')), "the server never logs the device secret")
        core.shutdown()
    }
}
