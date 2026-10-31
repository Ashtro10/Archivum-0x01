package ai.clu.desktop

import ai.clu.core.CluCore
import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.conversation.Message
import ai.clu.core.security.Actor
import ai.clu.core.util.CluJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors

/** The secret every API call except /v1/health must carry (`Authorization: Bearer <token>`); stored owner-only. */
object ApiToken {
    fun loadOrCreate(file: File): String {
        if (file.exists()) file.readText().trim().takeIf { it.length >= 32 }?.let { return it }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        OwnerOnly.write(file, token.toByteArray())
        return token
    }
}

/**
 * CLU's local API for the desktop shell (M3) and the phone gateway (M5). Bound to 127.0.0.1 only; every call except
 * `/v1/health` needs the bearer token; requests whose Host or Origin isn't this address are refused (a web page can't
 * reach it through DNS rebinding or a cross-site request); bodies are limited to 64 KiB. Owner-level access: the token
 * is the owner's credential on this PC.
 *
 * | Method | Path | |
 * |---|---|---|
 * | GET | /v1/health | liveness, no token |
 * | GET | /v1/status | model, memory, governor |
 * | GET, POST | /v1/conversations | list / create |
 * | GET | /v1/conversations/{id}/messages | history |
 * | POST | /v1/conversations/{id}/turns | `{"text": "...", "voice": false}`; `Accept: text/event-stream` streams `status`, `text`, `done` events, otherwise one JSON reply |
 * | POST | /v1/stop | cancel the current reply |
 * | POST | /v1/model/load, /v1/model/unload | |
 */
class ApiServer(
    private val core: CluCore,
    private val governor: ResourceGovernor,
    private val token: String,
    port: Int,
    private val version: String,
    private val startedAt: Long = System.currentTimeMillis(),
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16)
    private val executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "clu-api").apply { isDaemon = true } }
    val port: Int get() = server.address.port
    val address: InetAddress get() = server.address.address

    init {
        server.executor = executor
        server.createContext("/") { ex -> runCatching { handle(ex) }.onFailure { e -> runCatching { send(ex, 500, err("internal error: ${e::class.simpleName}")) } } }
    }

    fun start(): ApiServer { server.start(); return this }
    fun stop() { server.stop(0); executor.shutdownNow() }

    private fun err(msg: String) = buildJsonObject { put("error", msg) }

    private fun send(ex: HttpExchange, code: Int, body: JsonElement) {
        val bytes = body.toString().toByteArray()
        ex.responseHeaders.apply {
            set("Content-Type", "application/json; charset=utf-8"); set("Cache-Control", "no-store"); set("X-Content-Type-Options", "nosniff")
        }
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** Host and Origin must name this server (127.0.0.1 / localhost / [::1] on our port): defeats DNS rebinding and CSRF. */
    private fun sameMachine(ex: HttpExchange): Boolean {
        val allowed = setOf("127.0.0.1:$port", "localhost:$port", "[::1]:$port")
        val host = ex.requestHeaders.getFirst("Host") ?: return false
        if (host.lowercase() !in allowed) return false
        val origin = ex.requestHeaders.getFirst("Origin") ?: return true
        return origin.lowercase().removePrefix("http://") in allowed
    }

    private fun authorised(ex: HttpExchange): Boolean {
        val h = ex.requestHeaders.getFirst("Authorization") ?: return false
        if (!h.startsWith("Bearer ")) return false
        return MessageDigest.isEqual(h.removePrefix("Bearer ").trim().toByteArray(), token.toByteArray())
    }

    private fun body(ex: HttpExchange): JsonObject? {
        val bytes = ex.requestBody.use { it.readNBytes(MAX_BODY + 1) }
        if (bytes.size > MAX_BODY) return null
        if (bytes.isEmpty()) return JsonObject(emptyMap())
        return runCatching { CluJson.parseToJsonElement(String(bytes)).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
    }

    private fun handle(ex: HttpExchange) {
        if (!sameMachine(ex)) return send(ex, 403, err("requests must come from this computer"))
        val path = ex.requestURI.path.trimEnd('/')
        val method = ex.requestMethod.uppercase()
        if (path == "/v1/health" && method == "GET") return send(ex, 200, buildJsonObject { put("ok", true); put("name", "CLU"); put("version", version) })
        if (!authorised(ex)) return send(ex, 401, err("missing or wrong token"))
        val req = if (method == "POST") body(ex) ?: return send(ex, 413, err("request body over ${MAX_BODY / 1024} KiB")) else JsonObject(emptyMap())
        val turn = Regex("^/v1/conversations/([^/]+)/turns$").find(path)
        val history = Regex("^/v1/conversations/([^/]+)/messages$").find(path)
        when {
            path == "/v1/status" && method == "GET" -> send(ex, 200, status())
            path == "/v1/conversations" && method == "GET" -> send(ex, 200, buildJsonArray {
                core.conversations.list().forEach { c -> addJsonObject { put("id", c.id); put("title", c.title); put("updatedAt", c.updatedAt) } } })
            path == "/v1/conversations" && method == "POST" -> {
                val c = core.conversations.create(req["title"]?.jsonPrimitive?.contentOrNull?.take(120)?.ifBlank { null } ?: "New conversation")
                send(ex, 201, buildJsonObject { put("id", c.id); put("title", c.title) })
            }
            history != null && method == "GET" -> {
                val id = history.groupValues[1]
                if (core.conversations.get(id)?.deleted != false) return send(ex, 404, err("no such conversation"))
                send(ex, 200, buildJsonArray { core.conversations.messages(id).forEach { add(message(it)) } })
            }
            turn != null && method == "POST" -> turn(ex, turn.groupValues[1], req)
            path == "/v1/stop" && method == "POST" -> { core.agent.cancel(null); send(ex, 200, buildJsonObject { put("ok", true) }) }
            path == "/v1/model/unload" && method == "POST" -> { runBlocking { core.llm.unload() }; send(ex, 200, model()) }
            path == "/v1/model/load" && method == "POST" -> { runBlocking { core.llm.loadIfNeeded() }; send(ex, 200, model()) }
            else -> send(ex, 404, err("no such endpoint"))
        }
    }

    private fun model() = core.llm.state.value.let { s ->
        buildJsonObject { put("status", s.status.name); put("name", s.modelName ?: ""); put("message", s.message); put("contextSize", s.contextSize); put("loadMs", s.loadMs) }
    }

    private fun status() = buildJsonObject {
        put("name", "CLU"); put("version", version); put("uptimeMs", System.currentTimeMillis() - startedAt)
        put("model", model())
        putJsonObject("governor") {
            governor.snapshot().forEach { (k, v) -> put(k, v) }
            putJsonArray("events") { governor.events().forEach { e -> addJsonObject { put("at", e.at); put("action", e.action); put("reason", e.reason) } } }
        }
        put("conversations", core.conversations.list().size)
    }

    private fun message(m: Message) = buildJsonObject {
        put("id", m.id); put("role", m.role.name.lowercase()); put("content", m.content); put("createdAt", m.createdAt)
        if (m.meta.error.isNotEmpty()) put("error", m.meta.error)
        putJsonArray("sources") { m.meta.sources.forEach { s -> addJsonObject { put("title", s.title); put("url", s.url) } } }
    }

    private fun turn(ex: HttpExchange, conversationId: String, req: JsonObject) {
        if (core.conversations.get(conversationId)?.deleted != false) return send(ex, 404, err("no such conversation"))
        val text = req["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) return send(ex, 400, err("text is required"))
        val voice = req["voice"]?.jsonPrimitive?.booleanOrNull ?: false
        core.llm.prepare() // counts as use for the idle timer
        val request = TurnRequest(conversationId, text, Actor.ownerInApp(), viaVoice = voice)
        val stream = ex.requestHeaders.getFirst("Accept")?.contains("text/event-stream") == true
        if (!stream) {
            var done: AgentEvent.Done? = null; var error: String? = null
            runBlocking { core.agent.respond(request).collect { e -> if (e is AgentEvent.Done) done = e; if (e is AgentEvent.Error) error = e.text } }
            val d = done
            return send(ex, 200, buildJsonObject {
                put("text", d?.text ?: ""); put("error", d?.meta?.error?.ifEmpty { null } ?: error ?: "")
                put("interrupted", d?.meta?.interrupted ?: false); put("messageId", d?.message?.id ?: "")
                putJsonArray("sources") { d?.meta?.sources?.forEach { s -> addJsonObject { put("title", s.title); put("url", s.url) } } }
            })
        }
        ex.responseHeaders.apply { set("Content-Type", "text/event-stream; charset=utf-8"); set("Cache-Control", "no-store"); set("X-Content-Type-Options", "nosniff") }
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { out ->
            fun event(name: String, data: JsonElement) { out.write("event: $name\ndata: $data\n\n".toByteArray()); out.flush() }
            runBlocking {
                core.agent.respond(request).collect { e ->
                    when (e) {
                        is AgentEvent.Status -> event("status", buildJsonObject { put("text", e.text) })
                        is AgentEvent.Text -> event("text", buildJsonObject { put("delta", e.delta) })
                        is AgentEvent.Error -> event("error", buildJsonObject { put("text", e.text); put("kind", e.kind) })
                        is AgentEvent.Done -> event("done", buildJsonObject {
                            put("text", e.text); put("error", e.meta.error); put("interrupted", e.meta.interrupted); put("messageId", e.message?.id ?: "")
                            putJsonArray("sources") { e.meta.sources.forEach { s -> addJsonObject { put("title", s.title); put("url", s.url) } } }
                        })
                        else -> {}
                    }
                }
            }
        }
    }

    companion object { const val MAX_BODY = 64 * 1024 }
}
