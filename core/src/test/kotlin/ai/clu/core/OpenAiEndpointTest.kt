package ai.clu.core

import ai.clu.core.api.LoopbackServer
import ai.clu.core.api.OpenAiEndpoint
import ai.clu.core.llm.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.*

/** OpenAI-protocol clients (OpenCode, editors) get CLU's model through its queue: request mapping, replies, streaming, errors. */
class OpenAiEndpointTest {
    private val seen = mutableListOf<InferenceRequest>()
    private var fail: InferenceError? = null
    private val endpoint = OpenAiEndpoint({ req ->
        seen += req
        flow {
            emit(InferenceEvent.Started(req.id))
            fail?.let { emit(InferenceEvent.Failed(req.id, it, it.label)); return@flow }
            listOf("Hel", "lo", "!").forEach { emit(InferenceEvent.Token(req.id, it)) }
            emit(InferenceEvent.Completed(req.id, GenResult("Hello!", GenStats(promptTokens = 12, generatedTokens = 3, stopReason = "eog"), "local"), InferenceOutcome.COMPLETE))
        }
    }, { "Qwen3-4B" }, { 1_760_000_000_000L })
    private val server = LoopbackServer { ex -> endpoint.handle(ex, ex.path) }
    private val port = server.start()
    @AfterEach fun stop() = server.stop()

    private fun call(path: String, body: String? = null): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        return code to ((if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: "")
    }
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun listsCLUsModelAndAnswersAChatCompletion() {
        val models = obj(call("/openai/v1/models").second)
        assertEquals("Qwen3-4B", models["data"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        val (code, text) = call("/openai/v1/chat/completions", """{"model":"anything","messages":[{"role":"developer","content":"Be brief."},
            {"role":"user","content":[{"type":"text","text":"Say hello"}]}],"max_tokens":50,"temperature":0.2}""")
        assertEquals(200, code, text)
        val r = obj(text)
        assertEquals("chat.completion", r["object"]!!.jsonPrimitive.content)
        assertEquals("Hello!", r["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(15, r["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.int)
        val req = seen.single()
        assertEquals(listOf("system" to "Be brief.", "user" to "Say hello"), req.messages.map { it.role to it.content })
        assertEquals(50, req.params.maxTokens); assertEquals(0.2f, req.params.temperature); assertEquals("openai", req.owner)
    }

    @Test fun streamsChunksEndingInDone() {
        val (code, text) = call("/openai/v1/chat/completions", """{"messages":[{"role":"user","content":"hi"}],"stream":true}""")
        assertEquals(200, code)
        val events = text.split("\n\n").filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }
        assertEquals("[DONE]", events.last())
        val chunks = events.dropLast(1).map(::obj)
        assertEquals("Hello!", chunks.joinToString("") { it["choices"]!!.jsonArray[0].jsonObject["delta"]!!.jsonObject["content"]?.jsonPrimitive?.content ?: "" })
        assertEquals("stop", chunks.last()["choices"]!!.jsonArray[0].jsonObject["finish_reason"]!!.jsonPrimitive.content)
    }

    @Test fun refusesWhatItDoesNotDoAndReportsModelErrors() {
        assertEquals(400, call("/openai/v1/chat/completions", """{"messages":[]}""").first)
        assertEquals(400, call("/openai/v1/chat/completions", """{"messages":[{"role":"user","content":"x"}],"tools":[{"type":"function"}]}""").first)
        assertEquals(400, call("/openai/v1/chat/completions", """{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"x"}}]}]}""").first)
        assertEquals(404, call("/openai/v1/embeddings", "{}").first)
        fail = InferenceError.BUSY
        val (code, text) = call("/openai/v1/chat/completions", """{"messages":[{"role":"user","content":"x"}]}""")
        assertEquals(429, code); assertEquals("busy", obj(text)["error"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        fail = InferenceError.NO_MODEL
        assertEquals(503, call("/openai/v1/chat/completions", """{"messages":[{"role":"user","content":"x"}]}""").first)
    }
}
