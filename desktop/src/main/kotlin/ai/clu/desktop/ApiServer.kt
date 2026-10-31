package ai.clu.desktop

import ai.clu.core.CluCore
import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.conversation.Message
import ai.clu.core.security.Actor
import ai.clu.core.util.CluJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** The secret every API call except /v1/health must carry (`Authorization: Bearer <token>`); stored owner-only. */
object ApiToken {
    fun loadOrCreate(file: File): String {
        if (file.exists()) file.readText().trim().takeIf { it.length >= 32 }?.let { return it }
        val token = random()
        OwnerOnly.write(file, token.toByteArray())
        return token
    }
    fun random(bytes: Int = 32): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
}

/** One API call: the exchange plus the helpers every route uses. */
class Call(val ex: HttpExchange, val path: String, val method: String, val body: JsonObject) {
    fun send(code: Int, body: JsonElement) {
        val bytes = body.toString().toByteArray()
        ex.responseHeaders.apply {
            set("Content-Type", "application/json; charset=utf-8"); set("Cache-Control", "no-store"); set("X-Content-Type-Options", "nosniff")
        }
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
    fun ok(body: JsonElement = buildJsonObject { put("ok", true) }) = send(200, body)
    fun fail(code: Int, msg: String) = send(code, buildJsonObject { put("error", msg) })
    fun str(key: String) = body[key]?.jsonPrimitive?.contentOrNull
    fun strings(key: String): List<String> = when (val v = body[key]) {
        is JsonArray -> v.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.filter { it.isNotEmpty() }
        is JsonPrimitive -> v.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        else -> emptyList()
    }
    fun query(key: String): String? = ex.requestURI.rawQuery?.split('&')?.map { it.split('=', limit = 2) }
        ?.firstOrNull { it[0] == key }?.getOrNull(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
}

/**
 * CLU's local API for the desktop shell (M3) and the phone gateway (M5). Bound to 127.0.0.1 only; every call except
 * `/v1/health`, `/v1/session` and the shell's own static files needs the bearer token; requests whose Host or Origin
 * isn't this address are refused (a web page can't reach it through DNS rebinding or a cross-site request); JSON
 * bodies are limited to 64 KiB (document uploads to 50 MB). Owner-level access: the token is the owner's credential on
 * this PC. Endpoints: desktop/README.md.
 */
class ApiServer(
    private val core: CluCore,
    private val governor: ResourceGovernor,
    private val token: String,
    port: Int,
    private val version: String,
    private val startedAt: Long = System.currentTimeMillis(),
    private val hostConfig: HostConfigStore? = null,
    /** Opens the shell window (the tray's "Open CLU"); null when there is no desktop session. */
    var openShell: (() -> Unit)? = null,
    /** Background memory maintenance and the data folder (backups); null in tests that don't need them. */
    maintenance: MaintenanceScheduler? = null,
    paths: DesktopPaths? = null,
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16)
    private val executor = Executors.newFixedThreadPool(6) { r -> Thread(r, "clu-api").apply { isDaemon = true } }
    private val archive = ArchiveRoutes(core)
    private val settings = SettingsRoutes(core, governor, hostConfig)
    private val memory = MemoryRoutes(core, maintenance, paths)
    private val models = ModelRoutes(core, governor, paths)
    /** One-time codes that let the shell window obtain the token without it ever appearing on a command line. */
    private val launchCodes = ConcurrentHashMap<String, Long>()
    val port: Int get() = server.address.port
    val address: InetAddress get() = server.address.address

    init {
        server.executor = executor
        server.createContext("/") { ex ->
            runCatching { handle(ex) }.onFailure { e -> runCatching { Call(ex, "", "", JsonObject(emptyMap())).fail(500, "internal error: ${e::class.simpleName}") } }
        }
    }

    fun start(): ApiServer { server.start(); return this }
    fun stop() { server.stop(0); executor.shutdownNow() }

    /** A code the shell exchanges for the token at /v1/session: single use, valid for [ttlMs]. */
    fun issueLaunchCode(ttlMs: Long = 60_000): String {
        val now = System.currentTimeMillis()
        launchCodes.entries.removeIf { it.value < now }
        return ApiToken.random(18).also { launchCodes[it] = now + ttlMs }
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

    private fun readBody(ex: HttpExchange, limit: Int): ByteArray? {
        val bytes = ex.requestBody.use { it.readNBytes(limit + 1) }
        return if (bytes.size > limit) null else bytes
    }

    private fun handle(ex: HttpExchange) {
        val empty = JsonObject(emptyMap())
        val path = ex.requestURI.path.trimEnd('/').ifEmpty { "/" }
        val method = ex.requestMethod.uppercase()
        if (!sameMachine(ex)) return Call(ex, path, method, empty).fail(403, "requests must come from this computer")
        if (method == "GET" && (path == "/" || path.startsWith("/shell/"))) return ShellAssets.serve(ex, path)
        if (path == "/v1/health" && method == "GET") return Call(ex, path, method, empty).ok(buildJsonObject { put("ok", true); put("name", "CLU"); put("version", version) })
        if (path == "/v1/session" && method == "POST") {
            val code = readBody(ex, 4096)?.let { runCatching { CluJson.parseToJsonElement(String(it)).jsonObject["code"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
            val expiry = code?.let { launchCodes.remove(it) }
            val c = Call(ex, path, method, empty)
            return if (expiry != null && expiry >= System.currentTimeMillis()) c.ok(buildJsonObject { put("token", token) }) else c.fail(401, "launch code missing, used or expired")
        }
        if (!authorised(ex)) return Call(ex, path, method, empty).fail(401, "missing or wrong token")
        if (path == "/v1/documents" && method == "POST") {
            val bytes = readBody(ex, ArchiveRoutes.MAX_UPLOAD) ?: return Call(ex, path, method, empty).fail(413, "file over 50 MB")
            return archive.upload(Call(ex, path, method, empty), bytes)
        }
        val body = if (method == "POST" || method == "PUT" || method == "DELETE") {
            val bytes = readBody(ex, MAX_BODY) ?: return Call(ex, path, method, empty).fail(413, "request body over ${MAX_BODY / 1024} KiB")
            if (bytes.isEmpty()) empty else runCatching { CluJson.parseToJsonElement(String(bytes)).jsonObject }.getOrNull() ?: return Call(ex, path, method, empty).fail(400, "body must be a JSON object")
        } else empty
        val call = Call(ex, path, method, body)
        if (coreRoute(call) || archive.handle(call) || memory.handle(call) || models.handle(call) || settings.handle(call)) return
        call.fail(404, "no such endpoint")
    }

    private fun coreRoute(c: Call): Boolean {
        val turn = Regex("^/v1/conversations/([^/]+)/turns$").find(c.path)
        val history = Regex("^/v1/conversations/([^/]+)/messages$").find(c.path)
        when {
            c.path == "/v1/status" && c.method == "GET" -> c.ok(status())
            c.path == "/v1/conversations" && c.method == "GET" -> c.ok(buildJsonArray {
                core.conversations.list().forEach { cv -> addJsonObject { put("id", cv.id); put("title", cv.title); put("updatedAt", cv.updatedAt) } } })
            c.path == "/v1/conversations" && c.method == "POST" -> {
                val cv = core.conversations.create(c.str("title")?.take(120)?.ifBlank { null } ?: "New conversation")
                c.send(201, buildJsonObject { put("id", cv.id); put("title", cv.title) })
            }
            history != null && c.method == "GET" -> {
                val id = history.groupValues[1]
                if (core.conversations.get(id)?.deleted != false) c.fail(404, "no such conversation")
                else c.ok(buildJsonArray { core.conversations.messages(id).forEach { add(message(it)) } })
            }
            turn != null && c.method == "POST" -> turn(c, turn.groupValues[1])
            c.path == "/v1/stop" && c.method == "POST" -> { core.agent.cancel(null); c.ok() }
            c.path == "/v1/model/unload" && c.method == "POST" -> { runBlocking { core.inferenceService.unload() }; c.ok(model()) }
            c.path == "/v1/model/load" && c.method == "POST" -> { runBlocking { core.inferenceService.load() }; c.ok(model()) }
            c.path == "/v1/shell/open" && c.method == "POST" -> {
                val open = openShell ?: return true.also { c.fail(409, "no desktop session to open a window in") }
                open(); c.ok()
            }
            else -> return false
        }
        return true
    }

    private fun model() = core.llm.state.value.let { s ->
        buildJsonObject { put("status", s.status.name); put("name", s.modelName ?: ""); put("message", s.message); put("contextSize", s.contextSize); put("loadMs", s.loadMs)
            put("backend", s.backend); put("threads", s.threads); put("gpuLayers", s.gpuLayers); put("estimatedBytes", s.estimatedBytes); put("note", s.note)
            core.inferenceService.snapshot().let { sn -> put("running", sn.running != null); put("queued", sn.queued.size) } }
    }

    private fun status() = buildJsonObject {
        put("name", "CLU"); put("version", version); put("uptimeMs", System.currentTimeMillis() - startedAt)
        put("model", model())
        putJsonObject("governor") {
            governor.snapshot().forEach { (k, v) -> put(k, v) }
            putJsonArray("events") { governor.events().forEach { e -> addJsonObject { put("at", e.at); put("action", e.action); put("reason", e.reason) } } }
        }
        put("conversations", core.conversations.list().size)
        put("pendingSuggestions", core.proposals.pending().size)
    }

    private fun message(m: Message) = buildJsonObject {
        put("id", m.id); put("role", m.role.name.lowercase()); put("content", m.content); put("createdAt", m.createdAt)
        if (m.meta.error.isNotEmpty()) put("error", m.meta.error)
        putJsonArray("sources") { m.meta.sources.forEach { s -> addJsonObject { put("title", s.title); put("url", s.url) } } }
    }

    private fun turn(c: Call, conversationId: String) {
        if (core.conversations.get(conversationId)?.deleted != false) return c.fail(404, "no such conversation")
        val text = c.str("text")?.trim().orEmpty()
        if (text.isEmpty()) return c.fail(400, "text is required")
        val voice = c.body["voice"]?.jsonPrimitive?.booleanOrNull ?: false
        core.llm.prepare() // counts as use for the idle timer
        val request = TurnRequest(conversationId, text, Actor.ownerInApp(), viaVoice = voice)
        val stream = c.ex.requestHeaders.getFirst("Accept")?.contains("text/event-stream") == true
        if (!stream) {
            var done: AgentEvent.Done? = null; var error: String? = null
            runBlocking { core.agent.respond(request).collect { e -> if (e is AgentEvent.Done) done = e; if (e is AgentEvent.Error) error = e.text } }
            val d = done
            return c.ok(buildJsonObject {
                put("text", d?.text ?: ""); put("error", d?.meta?.error?.ifEmpty { null } ?: error ?: "")
                put("interrupted", d?.meta?.interrupted ?: false); put("messageId", d?.message?.id ?: "")
                putJsonArray("sources") { d?.meta?.sources?.forEach { s -> addJsonObject { put("title", s.title); put("url", s.url) } } }
            })
        }
        c.ex.responseHeaders.apply { set("Content-Type", "text/event-stream; charset=utf-8"); set("Cache-Control", "no-store"); set("X-Content-Type-Options", "nosniff") }
        c.ex.sendResponseHeaders(200, 0)
        c.ex.responseBody.use { out ->
            fun event(name: String, data: JsonElement) { out.write("event: $name\ndata: $data\n\n".toByteArray()); out.flush() }
            runBlocking {
                core.agent.respond(request).collect { e ->
                    when (e) {
                        is AgentEvent.Status -> event("status", buildJsonObject { put("text", e.text) })
                        is AgentEvent.Text -> event("text", buildJsonObject { put("delta", e.delta) })
                        is AgentEvent.Error -> event("error", buildJsonObject { put("text", e.text); put("kind", e.kind) })
                        is AgentEvent.ProposalCreated -> event("suggestion", buildJsonObject { put("id", e.proposal.id); put("text", e.proposal.text) })
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

/**
 * The shell's static files, from the jar (`/shell/…`): an allowlisted name pattern (no paths, no "..") and a strict
 * Content-Security-Policy (scripts, styles and connections from this server only, no framing).
 */
object ShellAssets {
    private val NAME = Regex("^[a-z0-9_-]+\\.(html|css|js|svg)$")
    private val TYPES = mapOf("html" to "text/html", "css" to "text/css", "js" to "text/javascript", "svg" to "image/svg+xml")
    const val CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; " +
        "frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

    fun serve(ex: HttpExchange, path: String) {
        val name = if (path == "/") "index.html" else path.removePrefix("/shell/")
        val bytes = if (NAME.matches(name)) ShellAssets::class.java.getResourceAsStream("/shell/$name")?.use { it.readBytes() } else null
        ex.responseHeaders.apply {
            set("Content-Security-Policy", CSP); set("X-Content-Type-Options", "nosniff"); set("Referrer-Policy", "no-referrer"); set("Cache-Control", "no-store")
        }
        if (bytes == null) { ex.sendResponseHeaders(404, -1); ex.close(); return }
        ex.responseHeaders.set("Content-Type", TYPES.getValue(name.substringAfterLast('.')) + "; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
