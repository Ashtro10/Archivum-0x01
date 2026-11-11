package ai.clu.core.api

import ai.clu.core.llm.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * CLU's loaded model behind the OpenAI chat-completions protocol (`/openai/v1/...` on the PC, this computer only), so
 * local tools that speak it (OpenCode, editors, scripts) use the same model, queue, timeouts and governor as CLU itself:
 * there is never a second copy of the model in memory. Requests are queued like CLU's own ("openai" in the status) and
 * can be refused with 429 when the queue is full. The model is whatever is selected in CLU; the `model` field is ignored.
 *
 * Supported: messages (text, or text parts of multi-part content), max_tokens / max_completion_tokens, temperature,
 * top_p, stream (server-sent chunks ending in `[DONE]`). Not supported (refused or ignored, never faked): tools, images,
 * n > 1, logprobs.
 */
class OpenAiEndpoint(
    private val generate: (InferenceRequest) -> Flow<InferenceEvent>,
    /** The selected model's display name (or "" when none). */
    private val modelName: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun handle(ex: Exchange, path: String) {
        when {
            path == "/openai/v1/models" && ex.method == "GET" -> ex.json(200, buildJsonObject {
                put("object", "list")
                putJsonArray("data") { modelName().ifBlank { null }?.let { n -> addJsonObject { put("id", n); put("object", "model"); put("created", now() / 1000); put("owned_by", "clu") } } }
            }.toString())
            path == "/openai/v1/chat/completions" && ex.method == "POST" -> chat(ex)
            else -> ex.json(404, error("Not supported by CLU's endpoint: ${ex.method} $path", "invalid_request_error"))
        }
    }

    private fun chat(ex: Exchange) {
        val body = runCatching { Json.parseToJsonElement(String(ex.body)).jsonObject }.getOrNull() ?: return ex.json(400, error("The body must be a JSON object.", "invalid_request_error"))
        val request = try { request(body) } catch (e: IllegalArgumentException) { return ex.json(400, error(e.message ?: "invalid request", "invalid_request_error")) }
        val model = modelName().ifBlank { "clu" }
        val id = "chatcmpl-" + request.id
        val created = now() / 1000
        if (body["stream"]?.jsonPrimitive?.booleanOrNull == true) {
            ex.stream(200, "text/event-stream; charset=utf-8", mapOf("X-Content-Type-Options" to "nosniff")) { out ->
                fun send(o: JsonObject) { out.write("data: $o\n\n".toByteArray()); out.flush() }
                send(chunk(id, model, created, JsonObject(mapOf("role" to JsonPrimitive("assistant"), "content" to JsonPrimitive(""))), null))
                runBlocking {
                    generate(request).collect { e ->
                        when (e) {
                            is InferenceEvent.Token -> send(chunk(id, model, created, JsonObject(mapOf("content" to JsonPrimitive(e.text))), null))
                            is InferenceEvent.Completed -> send(chunk(id, model, created, JsonObject(emptyMap()), finish(e.outcome)))
                            is InferenceEvent.Failed -> send(buildJsonObject { put("error", Json.parseToJsonElement(error(e.message, e.error.name.lowercase()))
                                .jsonObject["error"]!!) })
                            else -> {}
                        }
                    }
                }
                out.write("data: [DONE]\n\n".toByteArray()); out.flush()
            }
            return
        }
        var text = StringBuilder(); var done: InferenceEvent.Completed? = null; var failed: InferenceEvent.Failed? = null
        runBlocking { generate(request).collect { e -> when (e) { is InferenceEvent.Token -> text.append(e.text); is InferenceEvent.Completed -> done = e; is InferenceEvent.Failed -> failed = e; else -> {} } } }
        failed?.let { f -> return ex.json(status(f.error), error(f.message, f.error.name.lowercase())) }
        val d = done ?: return ex.json(500, error("The model gave no result.", "server_error"))
        val reply = d.result.text.ifEmpty { text.toString() }
        ex.json(200, buildJsonObject {
            put("id", id); put("object", "chat.completion"); put("created", created); put("model", model)
            putJsonArray("choices") { addJsonObject {
                put("index", 0); putJsonObject("message") { put("role", "assistant"); put("content", reply) }; put("finish_reason", finish(d.outcome))
            } }
            putJsonObject("usage") {
                put("prompt_tokens", d.result.stats.promptTokens); put("completion_tokens", d.result.stats.generatedTokens)
                put("total_tokens", d.result.stats.promptTokens + d.result.stats.generatedTokens)
            }
        }.toString())
    }

    companion object {
        /** The OpenAI request as CLU's inference request; IllegalArgumentException for what CLU does not do. */
        fun request(body: JsonObject): InferenceRequest {
            val raw = body["messages"] as? JsonArray ?: throw IllegalArgumentException("messages is required")
            require(raw.isNotEmpty()) { "messages must not be empty" }
            require(body["tools"].let { it == null || it is JsonNull || (it as? JsonArray)?.isEmpty() == true }) { "tools are not supported by CLU's endpoint" }
            require((body["n"]?.jsonPrimitive?.intOrNull ?: 1) == 1) { "n must be 1" }
            val messages = raw.map { m ->
                val o = m as? JsonObject ?: throw IllegalArgumentException("each message must be an object")
                val role = o["role"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("each message needs a role")
                require(role in setOf("system", "developer", "user", "assistant", "tool")) { "unknown role '$role'" }
                val content = when (val c = o["content"]) {
                    null, is JsonNull -> ""
                    is JsonPrimitive -> c.content
                    is JsonArray -> c.joinToString("\n") { part ->
                        val p = part as? JsonObject ?: throw IllegalArgumentException("content parts must be objects")
                        require(p["type"]?.jsonPrimitive?.contentOrNull == "text") { "only text content is supported" }
                        p["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                    else -> throw IllegalArgumentException("content must be text")
                }
                ChatMessage(when (role) { "developer" -> "system"; "tool" -> "user"; else -> role }, content)
            }
            val d = GenParams()
            val max = (body["max_completion_tokens"] ?: body["max_tokens"])?.jsonPrimitive?.intOrNull
            val params = d.copy(
                maxTokens = (max ?: 1024).coerceIn(1, 8192),
                temperature = body["temperature"]?.jsonPrimitive?.floatOrNull?.coerceIn(0f, 2f) ?: d.temperature,
                topP = body["top_p"]?.jsonPrimitive?.floatOrNull?.coerceIn(0f, 1f) ?: d.topP)
            return InferenceRequest(messages, params, owner = "openai")
        }

        fun chunk(id: String, model: String, created: Long, delta: JsonObject, finish: String?) = buildJsonObject {
            put("id", id); put("object", "chat.completion.chunk"); put("created", created); put("model", model)
            putJsonArray("choices") { addJsonObject { put("index", 0); put("delta", delta); put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull) } }
        }

        fun finish(outcome: InferenceOutcome) = if (outcome == InferenceOutcome.LENGTH) "length" else "stop"
        fun status(e: InferenceError) = when (e) { InferenceError.BUSY -> 429; InferenceError.NO_MODEL, InferenceError.LOAD_FAILED -> 503; InferenceError.CONTEXT_TOO_LARGE -> 400
            InferenceError.TIMEOUT_FIRST_TOKEN, InferenceError.TIMEOUT_STALL, InferenceError.TIMEOUT_TOTAL -> 504; else -> 500 }
        fun error(message: String, type: String) = buildJsonObject { putJsonObject("error") { put("message", message); put("type", type) } }.toString()
    }
}
