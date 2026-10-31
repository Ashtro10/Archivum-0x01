package ai.clu.desktop

import ai.clu.core.config.Settings
import ai.clu.core.llm.LlmStatus
import ai.clu.core.util.CluJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.*

/**
 * Model management and the inference API over the local API, against the real host and the real llama.cpp bridge with
 * the tiny random-weight GGUF (replies are meaningless text: these tests check behaviour, not what the model says).
 * Tests with a model that talks sensibly are in HostRealModelTest / RealModelBenchmarkTest (opt-in).
 */
class ModelRoutesTest {
    private val tiny = File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))
    private val dir = Files.createTempDirectory("clu-models").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) }
    private val paths = DesktopPaths(File(dir, "home"))
    private val host by lazy { assumeTrue(tiny.exists(), "tiny.gguf missing"); Host.start(paths, HostConfig(maintenanceMinutes = 0, governorIntervalSeconds = 600), port = 0, log = {}) }
    private val http = HttpClient.newHttpClient()
    private val core get() = host.core

    @AfterEach fun stop() { runCatching { host.stop() } }

    private fun req(path: String, method: String = "GET", body: String? = null, token: String? = host.token, accept: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create(host.url + path)); token?.let { b.header("Authorization", "Bearer $it") }; accept?.let { b.header("Accept", it) }
        return http.send(b.method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun obj(r: HttpResponse<String>) = CluJson.parseToJsonElement(r.body()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun register(): JsonObject { val r = req("/v1/models", "POST", """{"path":"${tiny.absolutePath.replace("\\", "\\\\")}","select":true}"""); assertEquals(201, r.statusCode(), r.body()); return obj(r) }
    private fun state() = obj(req("/v1/inference/status"))["state"]!!.jsonObject
    private fun waitFor(ms: Long = 20_000, what: String = "condition", c: () -> Boolean) { val t = System.currentTimeMillis(); while (!c()) { assertTrue(System.currentTimeMillis() - t < ms, "timed out waiting for $what"); Thread.sleep(25) } }

    @Test fun everythingNeedsTheToken() {
        for (p in listOf("/v1/models", "/v1/models/config", "/v1/inference/status", "/v1/models/benchmarks")) assertEquals(401, req(p, token = null).statusCode(), p)
        assertEquals(401, req("/v1/inference/generate", "POST", "{}", token = null).statusCode())
    }

    @Test fun registeringChecksTheFileAndReportsExactReasons() {
        assertEquals(400, req("/v1/models", "POST", """{"path":"relative/model.gguf"}""").statusCode())
        assertEquals(404, req("/v1/models", "POST", """{"path":"${File(dir, "nope.gguf").absolutePath.replace("\\", "\\\\")}"}""").statusCode())
        assertEquals(400, req("/v1/models", "POST", """{"path":"${dir.absolutePath.replace("\\", "\\\\")}"}""").statusCode(), "a folder is not a model")
        val bogus = File(dir, "bogus.gguf").apply { writeText("this is not a model") }
        val r = req("/v1/models", "POST", """{"path":"${bogus.absolutePath.replace("\\", "\\\\")}"}"""); assertEquals(422, r.statusCode())
        assertTrue(obj(r).s("error").contains("Not a compatible model") && obj(r).s("error").contains("GGUF"), r.body()); assertTrue(obj(r).s("suggestion").contains("Qwen2.5 3B"))
        val m = register()
        assertEquals("GGUF v3", m.s("format")); assertEquals("llama", m.s("architecture")); assertTrue(m["layers"]!!.jsonPrimitive.long > 0 && m["estimatedBytes"]!!.jsonPrimitive.long > tiny.length() && m["fitsNow"]!!.jsonPrimitive.boolean)
        assertEquals(tiny.name, m.s("file")); assertTrue(m["fileExists"]!!.jsonPrimitive.boolean); assertTrue(m["active"]!!.jsonPrimitive.boolean)
    }

    @Test fun loadChatUnloadAndRecoverWithRealStates() {
        val m = register(); val id = m.s("id")
        assertEquals("UNLOADED", state().s("status"), "registering does not load anything")
        val load = req("/v1/models/$id/load", "POST", "{}"); assertEquals(200, load.statusCode(), load.body())
        val st = state(); assertEquals("READY", st.s("status")); assertEquals("cpu", st.s("backend")); assertTrue(st["threads"]!!.jsonPrimitive.int > 0 && st["contextSize"]!!.jsonPrimitive.int > 0 && st["loadMs"]!!.jsonPrimitive.long >= 0)
        assertTrue(obj(req("/v1/models"))["models"]!!.jsonArray.single().jsonObject["loaded"]!!.jsonPrimitive.boolean)
        // The same state through /v1/status (what the window's pill shows).
        assertEquals("READY", obj(req("/v1/status"))["model"]!!.jsonObject.s("status"))
        val g = obj(req("/v1/inference/generate", "POST", """{"messages":[{"role":"user","content":"Hello"}],"maxTokens":12}"""))
        assertTrue(g.s("text").isNotEmpty() && g["generatedTokens"]!!.jsonPrimitive.int > 0, g.toString()); assertTrue(g["requestId"]!!.jsonPrimitive.content.isNotBlank())
        // Unload releases the model; the next request loads it again on its own.
        assertEquals("UNLOADED", obj(req("/v1/inference/unload", "POST", "{}"))["state"]!!.jsonObject.s("status"))
        val again = obj(req("/v1/inference/generate", "POST", """{"messages":[{"role":"user","content":"Again"}],"maxTokens":8}""")); assertTrue(again.s("text").isNotEmpty())
        assertEquals("READY", state().s("status"))
    }

    @Test fun generationStreamsEventsAndACancelByIdStopsItAtOnce() {
        register(); core.settings.set(Settings.MAX_QUEUED, 0)
        val t = req("/v1/inference/load", "POST", "{}"); assertEquals(200, t.statusCode())
        // Streaming: queued, started, tokens, completed, all with the request id in the header.
        val sse = req("/v1/inference/generate", "POST", """{"messages":[{"role":"user","content":"Hello"}],"maxTokens":10}""", accept = "text/event-stream")
        assertTrue(sse.headers().firstValue("X-Request-Id").isPresent)
        val names = Regex("(?m)^event: (\\w+)$").findAll(sse.body()).map { it.groupValues[1] }.toList()
        assertEquals(listOf("queued", "started"), names.take(2)); assertTrue(names.count { it == "token" } > 0); assertEquals("completed", names.last())
        // A long one in the background; read its events live.
        val conn = java.net.URI(host.url + "/v1/inference/generate").toURL().openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Authorization", "Bearer ${host.token}"); conn.setRequestProperty("Accept", "text/event-stream")
        conn.outputStream.use { it.write("""{"messages":[{"role":"user","content":"Talk"}],"maxTokens":1500,"temperature":1.0}""".toByteArray()) }
        val reqId = conn.getHeaderField("X-Request-Id"); assertNotNull(reqId)
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val reader = Thread { runCatching { conn.inputStream.bufferedReader().forEachLine { lines += it } } }.apply { start() }
        waitFor(what = "tokens") { lines.count { it == "event: token" } >= 3 }
        // Status shows it; a second request is refused while it runs (maxQueued = 0).
        val status = obj(req("/v1/inference/status")); assertEquals(reqId, status["running"]!!.jsonObject.s("id"))
        val busy = req("/v1/inference/generate", "POST", """{"messages":[{"role":"user","content":"Me too"}]}"""); assertEquals(429, busy.statusCode()); assertEquals("BUSY", obj(busy).s("error"))
        val t0 = System.nanoTime(); assertEquals(200, req("/v1/inference/$reqId/cancel", "POST", "{}").statusCode())
        waitFor(5_000, "completion") { lines.contains("event: completed") }
        reader.join(5_000)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3_000)
        assertTrue(lines.any { it.contains("\"outcome\":\"CANCELLED\"") }, lines.takeLast(3).toString())
        waitFor(what = "idle") { state().s("status") == "READY" && obj(req("/v1/inference/status"))["running"] == null }
        assertEquals(404, req("/v1/inference/$reqId/cancel", "POST", "{}").statusCode(), "nothing left to cancel")
        assertEquals(400, req("/v1/inference/generate", "POST", """{"messages":[{"role":"assistant","content":"x"}]}""").statusCode(), "must end with a user message")
    }

    @Test fun theSettingsThatShapeLoadingReallyChangeTheRuntime() {
        val id = register().s("id"); req("/v1/models/$id/load", "POST", "{}")
        val before = state()
        // Context and threads: applied by reloading the loaded model.
        val put = req("/v1/models/config", "PUT", """{"contextLength":1024,"threads":2,"batch":128}"""); assertEquals(200, put.statusCode(), put.body())
        waitFor(what = "reload with the new context") { state().let { it.s("status") == "READY" && it["contextSize"]!!.jsonPrimitive.int == 1024 && it["threads"]!!.jsonPrimitive.int == 2 } }
        assertEquals(1024, core.settings.int(Settings.CONTEXT_LENGTH)); assertTrue(before["contextSize"]!!.jsonPrimitive.int > 1024)
        // Out-of-range and unknown values are refused with the reason, and change nothing.
        val bad = req("/v1/models/config", "PUT", """{"contextLength":10}"""); assertEquals(400, bad.statusCode()); assertTrue(obj(bad).s("error").contains("contextLength"))
        assertEquals(1024, core.settings.int(Settings.CONTEXT_LENGTH))
        assertEquals(400, req("/v1/models/config", "PUT", """{"threads":"many"}""").statusCode())
        // Asking for the GPU on an engine build that has none: still works, on the CPU, and says so (never a silent claim).
        assertEquals(200, req("/v1/models/config", "PUT", """{"acceleration":"gpu","gpuLayers":20}""").statusCode())
        waitFor(what = "reload for the GPU request") { state().let { it.s("status") == "READY" && it.s("note").contains("GPU") } }
        val s = state(); assertEquals("cpu", s.s("backend")); assertEquals(0, s["gpuLayers"]!!.jsonPrimitive.int); assertTrue(s.s("note").contains("no GPU backend"), s.s("note"))
        assertTrue(obj(req("/v1/models/config"))["gpuLayers"]!!.jsonObject.s("value") == "20")
        // And the model still answers.
        assertTrue(obj(req("/v1/inference/generate", "POST", """{"messages":[{"role":"user","content":"Hi"}],"maxTokens":6}""")).s("text").isNotEmpty())
    }

    @Test fun removingARegistrationNeverDeletesTheFileAndReleasesTheModel() {
        val copy = File(dir, "copy.gguf").also { tiny.copyTo(it) }
        val id = obj(req("/v1/models", "POST", """{"path":"${copy.absolutePath.replace("\\", "\\\\")}","select":true}""")).s("id")
        req("/v1/models/$id/load", "POST", "{}"); assertEquals("READY", state().s("status"))
        val r = obj(req("/v1/models/$id", "DELETE"))
        assertTrue(r["fileStillExists"]!!.jsonPrimitive.boolean && copy.exists()); assertEquals(tiny.length(), copy.length())
        assertEquals("NO_MODEL", state().let { waitFor { state().s("status") != "READY" }; state().s("status") }.let { if (it == "UNLOADED") "NO_MODEL" else it })
        assertTrue(obj(req("/v1/models"))["models"]!!.jsonArray.isEmpty()); assertEquals("", core.settings.string(Settings.MODEL_ACTIVE))
        assertEquals(404, req("/v1/models/$id", "DELETE").statusCode())
    }

    @Test fun aDamagedModelFileReportsTheExactReasonAndASuggestionThenRecovers() {
        val copy = File(dir, "dmg.gguf").also { tiny.copyTo(it) }
        val id = obj(req("/v1/models", "POST", """{"path":"${copy.absolutePath.replace("\\", "\\\\")}","select":true}""")).s("id")
        copy.writeBytes(copy.readBytes().copyOf(900_000))
        val bad = req("/v1/models/$id/load", "POST", "{}"); assertEquals(422, bad.statusCode())
        val b = obj(bad); assertEquals("ERROR", b["state"]!!.jsonObject.s("status")); assertTrue(b["state"]!!.jsonObject.s("message").contains("damaged") || b["state"]!!.jsonObject.s("message").contains("incomplete"), b.toString())
        assertTrue(b.s("suggestion").contains("Qwen2.5 3B"))
        val listed = obj(req("/v1/models"))["models"]!!.jsonArray.single().jsonObject
        assertEquals("INCOMPATIBLE", listed.s("status")); assertTrue(listed.s("lastError").isNotBlank()); assertTrue(listed.s("suggestion").contains("Qwen2.5 3B"))
        tiny.copyTo(copy, overwrite = true)
        assertEquals(200, req("/v1/models/$id/load", "POST", "{}").statusCode()); assertEquals("READY", state().s("status"))
    }

    @Test fun theMemoryCeilingIsEnforcedAndLoadingRecoversWhenRoomReturns() {
        val id = register().s("id")
        core.settings.set(Settings.FIT_CONTEXT, false)
        // A 300 MB budget leaves nothing for a model on top of the host itself: refused with the numbers.
        host.governor.config = GovernorConfig(300L * 1024 * 1024, 0, 0)
        val refused = req("/v1/models/$id/load", "POST", "{}"); assertEquals(422, refused.statusCode())
        assertTrue(obj(refused)["state"]!!.jsonObject.s("message").startsWith("Not enough free memory"), refused.body())
        assertEquals(LlmStatus.ERROR, core.llm.state.value.status)
        // The user raises the budget: the same request now loads (no restart, nothing lost).
        host.governor.config = GovernorConfig(16L * 1024 * 1024 * 1024, 0, 0)
        assertEquals(200, req("/v1/models/$id/load", "POST", "{}").statusCode()); assertEquals("READY", state().s("status"))
        // Memory pressure: an idle model is unloaded by the governor, and the next request brings it back.
        host.governor.config = GovernorConfig(16L * 1024 * 1024 * 1024, 0, Long.MAX_VALUE / 4)
        assertNotNull(host.governor.tick(), "the governor unloads an idle model under pressure"); assertEquals("UNLOADED", state().s("status"))
        host.governor.config = GovernorConfig(16L * 1024 * 1024 * 1024, 0, 0)
        assertTrue(obj(req("/v1/inference/generate", "POST", """{"messages":[{"role":"user","content":"Back?"}],"maxTokens":6}""")).s("text").isNotEmpty())
    }

    @Test fun theBenchmarkRunsAsAJobAndRecordsEveryConditionOfTheMeasurement() {
        val id = register().s("id")
        val job = obj(req("/v1/models/$id/benchmark", "POST", """{"tokens":8,"runs":1,"promptTokens":32,"cpuSeconds":1}""")).s("job")
        assertEquals(409, req("/v1/models/$id/benchmark", "POST", "{}").statusCode(), "one at a time")
        var j = obj(req("/v1/models/benchmark/$job"))
        waitFor(60_000, "benchmark") { j = obj(req("/v1/models/benchmark/$job")); j["done"]!!.jsonPrimitive.boolean }
        assertNull(j["error"], j.toString())
        val r = j["result"]!!.jsonObject
        assertEquals(tiny.name, r["model"]!!.jsonObject.s("file")); assertTrue(r["model"]!!.jsonObject.s("quantization").isNotBlank())
        val c = r["configuration"]!!.jsonObject; assertEquals("cpu", c.s("backend")); assertTrue(c["contextTokens"]!!.jsonPrimitive.int > 0 && c["threads"]!!.jsonPrimitive.int > 0)
        assertTrue(r["machine"]!!.jsonObject.s("measuredOn") in setOf("linux", "windows", "macos")); assertTrue(r["engine"]!!.jsonObject.s("version").contains("b11844")); assertTrue(r["engine"]!!.jsonObject.s("gpuUtilisation").contains("not measured"))
        assertTrue(r["loadMsWall"]!!.jsonPrimitive.long >= 0 && r["generationTokensPerSecondMedian"]!!.jsonPrimitive.double > 0 && r["firstTokenMsMedian"]!!.jsonPrimitive.double >= 0 && r["cancelLatencyMs"]!!.jsonPrimitive.long >= 0)
        val mem = r["memoryMB"]!!.jsonObject; assertTrue(mem["processLoaded"]!!.jsonPrimitive.long >= mem["processBeforeLoad"]!!.jsonPrimitive.long && mem["processPeak"]!!.jsonPrimitive.long >= mem["processLoaded"]!!.jsonPrimitive.long)
        assertEquals(1, r["runs"]!!.jsonArray.size)
        assertEquals("UNLOADED", state().s("status"), "the benchmark leaves the model unloaded")
        assertEquals(1, ModelBenchmark.list(paths).size); assertEquals(1, CluJson.parseToJsonElement(req("/v1/models/benchmarks").body()).jsonArray.size)
        assertTrue(ModelBenchmark.summary(r).contains("measured on")); assertEquals(404, req("/v1/models/benchmark/nope").statusCode())
    }

    @Test fun theWindowsLaunchersKeepCluPoliteAndSmall() {
        val bg = File("src/dist/bin/clu-host-background.cmd").takeIf { it.exists() } ?: File("desktop/src/dist/bin/clu-host-background.cmd")
        assertTrue(bg.exists()); val text = bg.readText()
        assertTrue(text.contains("/belownormal") && text.contains("/min") && text.contains("clu-host.bat"), text); assertTrue(text.contains("\r\n"), "Windows line endings")
        val bat = listOf(File("build/scripts/clu-host.bat"), File("desktop/build/scripts/clu-host.bat")).firstOrNull { it.exists() }
        assumeTrue(bat != null, "launcher script not generated yet (run installDist)")
        val b = bat!!.readText(); assertTrue(b.contains("-Xmx384m") && b.contains("UseSerialGC") && b.contains("MaxMetaspaceSize"), "the host's own memory is capped")
    }
}
