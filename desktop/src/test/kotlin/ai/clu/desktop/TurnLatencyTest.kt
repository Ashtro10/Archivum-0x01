package ai.clu.desktop

import ai.clu.core.config.Settings
import ai.clu.core.util.CluJson
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Measures real chat turns with a real model through CLU's API (`-Dclu.capModel=<gguf>`): time to the first streamed
 * text, total time, the route each turn took and every model call it cost, from CLU's own request trace. Results go
 * to `-Dclu.capShots` (default build/turn-latency) as turns.tsv. Category: integration with a real model (no audio).
 */
class TurnLatencyTest {
    @Test fun realTurnsAreMeasured() {
        val model = System.getProperty("clu.capModel")?.let(::File)
        assumeTrue(model?.isFile == true, "opt-in: -Dclu.capModel=<gguf>")
        val out = File(System.getProperty("clu.capShots") ?: "build/turn-latency").apply { mkdirs() }
        val dir = Files.createTempDirectory("clu-turns").toFile()
        val host = Host.start(DesktopPaths(File(dir, "home")), HostConfig(), port = 0, log = {})
        val http = HttpClient.newHttpClient()
        fun req(path: String, method: String = "GET", body: String? = null, accept: String = "application/json") =
            http.send(HttpRequest.newBuilder(URI.create(host.url + path)).header("Authorization", "Bearer ${host.token}").header("Accept", accept)
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofLines())
        try {
            val m = host.core.models.register(model!!); host.core.settings.set(Settings.MODEL_ACTIVE, m.id)
            val t0 = System.currentTimeMillis(); kotlinx.coroutines.runBlocking { host.core.inferenceService.load() }; val loadMs = System.currentTimeMillis() - t0
            val conv = CluJson.parseToJsonElement(req("/v1/conversations", "POST", "{}").body().toList().joinToString("")).jsonObject["id"]!!.jsonPrimitive.content
            val prompts = listOf("Hello", "How are you today?", "What is a robotic element?", "Explain what a black hole is in one sentence.", "What is the weather?",
                "Thanks, that's all.", "Build me an application that tracks my water intake")
            val rows = StringBuilder("prompt\tfirst_text_ms\ttotal_ms\troute\tllm_calls\tllm_detail\treply\n")
            for (p in prompts) {
                val start = System.currentTimeMillis(); var first = -1L; val reply = StringBuilder(); var event = ""
                req("/v1/conversations/$conv/turns", "POST", buildJsonObject { put("text", p) }.toString(), "text/event-stream").body().forEach { line ->
                    if (line.startsWith("event: ")) event = line.removePrefix("event: ")
                    else if (line.startsWith("data: ")) { val d = CluJson.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                        if (event == "text" && first < 0) first = System.currentTimeMillis() - start
                        if (event == "done") { reply.setLength(0); reply.append(d["text"]?.jsonPrimitive?.contentOrNull ?: "") } }
                }
                val total = System.currentTimeMillis() - start
                val trace = CluJson.parseToJsonElement(req("/v1/requests").body().toList().joinToString("")).jsonArray.first().jsonObject
                val events = trace["events"]!!.jsonArray.map { it.jsonObject }
                val route = events.filter { it["name"]!!.jsonPrimitive.content == "ROUTE" }.joinToString("|") { it["detail"]?.jsonPrimitive?.content ?: "" }
                val calls = events.filter { it["name"]!!.jsonPrimitive.content == "LLM_CALL" }.map { it["detail"]?.jsonPrimitive?.content ?: "" }
                rows.append("$p\t$first\t$total\t$route\t${calls.size}\t${calls.joinToString(" || ")}\t${reply.toString().replace('\n', ' ').take(200)}\n")
                File(out, "timeline.txt").appendText("\"$p\": " + events.joinToString(" → ") { "${it["name"]!!.jsonPrimitive.content}@${it["ms"]!!.jsonPrimitive.content}" } + "\n")
                println("[turn] \"$p\": first text ${first}ms, total ${total}ms, route $route, ${calls.size} model call(s)\n        ${reply.toString().take(160)}")
            }
            File(out, "turns.tsv").writeText("model\t${model.name}\tload_ms\t$loadMs\tcontext\t${host.core.llm.state.value.contextSize}\tthreads\t${host.core.llm.state.value.threads}\n" + rows)
            assertTrue(rows.lines().size > prompts.size)
        } finally { host.stop() }
    }
}
