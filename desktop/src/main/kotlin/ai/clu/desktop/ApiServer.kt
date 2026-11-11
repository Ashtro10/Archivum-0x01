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
/** The PC's [ai.clu.core.api.ApiCall]: one request on the JDK HTTP server. */
class Call(val ex: HttpExchange, path: String, method: String, body: JsonObject) : ai.clu.core.api.ApiCall(path, method, body) {
    override fun send(code: Int, body: JsonElement) {
        val bytes = body.toString().toByteArray()
        ex.responseHeaders.apply {
            set("Content-Type", "application/json; charset=utf-8"); set("Cache-Control", "no-store"); set("X-Content-Type-Options", "nosniff")
        }
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
    override fun header(name: String): String? = ex.requestHeaders.getFirst(name)
    override val rawQuery: String? get() = ex.requestURI.rawQuery
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
    /** Pieces of the first-run and installed-app experience (null in tests that don't need them). */
    platform: DesktopLlmPlatform? = null,
    val lifecycle: HostLifecycle = HostLifecycle(),
    autostart: Autostart = Autostart(),
    /** CLU's voice (M8); null in tests that do not need it. */
    val voice: ai.clu.desktop.voice.VoiceService? = null,
    /** Self-repair (index, incidents, repairs, owner decisions); null when not set up. */
    private val evolution: ai.clu.desktop.evolution.EvolutionRoutes? = null,
    /** Capabilities CLU develops for itself: registry, development runs, experience files and their services. */
    val capabilities: ai.clu.desktop.capability.CapabilitySetup? = null,
    /** The hand-tracking runtime for the camera gesture interface (downloaded on request). */
    val gesture: GesturePack? = paths?.let { GesturePack(java.io.File(it.root, "gesture")) },
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16)
    private val executor = Executors.newFixedThreadPool(6) { r -> Thread(r, "clu-api").apply { isDaemon = true } }
    private val archive = ai.clu.core.api.ArchiveRoutes(core)
    private val settings = SettingsRoutes(core, governor, hostConfig)
    private val memory = ai.clu.core.api.MemoryRoutes(core, maintenance?.let { m -> object : ai.clu.core.api.MaintenanceHost {
        override val scheduled get() = m.scheduled
        override val lastRun get() = m.last?.let { ai.clu.core.api.MaintenanceHost.Run(it.at, it.ran, it.summary) }
        override fun busyReason() = m.busyReason()
        override fun runNow() = m.tick(force = true).let { ai.clu.core.api.MaintenanceHost.Run(it.at, it.ran, it.summary) }
    } }, paths?.let { p -> object : ai.clu.core.api.BackupHost {
        override val folder get() = p.backups.absolutePath
        override fun list() = DatabaseBackup.list(p.backups).map { ai.clu.core.api.BackupHost.Backup(it.file.name, it.sizeBytes, it.createdAt, it.label) }
        override fun create() = DatabaseBackup.create(core.db, p.backups, System.currentTimeMillis(), "manual", keep = 10).let { ai.clu.core.api.BackupHost.Backup(it.file.name, it.sizeBytes, it.createdAt, it.label) }
    } })
    private val models = ModelRoutes(core, governor, paths)
    private val agents = AgentRoutes(core, governor)
    private val voiceRoutes = voice?.let { ai.clu.desktop.voice.VoiceRoutes(it) }
    private val downloader = paths?.let { p -> ModelDownloader(p.models, { f -> core.models.register(f).id }) }
    private val setup = if (paths != null && platform != null && downloader != null) SetupRoutes(core, governor, paths, platform, downloader, autostart, hostConfig) else null
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

    // ------------------------------------------------------------------------------------------ OpenAI-compatible endpoint
    /**
     * Local tools that speak the OpenAI protocol (OpenCode, editors, scripts) use CLU's loaded model through
     * http://127.0.0.1:<port>/openai/v1 with this computer's local API key (never offered to other devices: the phone
     * endpoint only forwards /v1, and requests must come from this computer).
     */
    private val openAi = ai.clu.core.api.OpenAiEndpoint({ core.inferenceService.generate(it) }, { core.llm.state.value.modelName ?: "" })
    private val keyFile = paths?.let { java.io.File(it.root, "local-api-key") }
    /** Created on first use and kept (tools are configured once); 0600 where the file system supports it. */
    val localApiKey: String by lazy {
        keyFile?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length >= 32 } ?: run {
            val k = "clu-" + java.util.UUID.randomUUID().toString().replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", "")
            keyFile?.let { f -> f.parentFile.mkdirs(); f.writeText(k); runCatching { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true) } }
            k
        }
    }
    private fun openAi(ex: HttpExchange, path: String) {
        val auth = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
        val exchange = ai.clu.core.api.Exchange(ex.requestMethod.uppercase(), path, ex.requestURI.rawQuery,
            ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") },
            if (ex.requestMethod.uppercase() == "POST") readBody(ex, 4 * 1024 * 1024) ?: return Call(ex, path, "POST", JsonObject(emptyMap())).fail(413, "body over 4 MB") else ByteArray(0)) { code, headers, length ->
            headers.forEach { (k, v) -> ex.responseHeaders.set(k, v) }
            ex.sendResponseHeaders(code, if (length == null) 0 else if (length == 0L) -1 else length)
            ex.responseBody
        }
        try {
            if (!MessageDigest.isEqual(auth.toByteArray(), localApiKey.toByteArray()) && !MessageDigest.isEqual(auth.toByteArray(), token.toByteArray()))
                exchange.json(401, ai.clu.core.api.OpenAiEndpoint.error("Use CLU's local API key (GET /v1/openai from CLU's window).", "invalid_api_key"))
            else openAi.handle(exchange, path)
        } finally { ex.close() } // every path completes the response (an unclosed exchange leaves the client waiting)
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

    /**
     * A request from a paired tablet or phone on the local network, already authenticated with its device key by the
     * phone endpoint (VoiceService). It reaches the same routes as the window, so the tablet's shell shows this PC's
     * real data, with these exceptions: no browser may call it (an Origin header is refused, which defeats cross-site
     * requests from web pages on the network), and actions that only make sense at the PC are not offered remotely.
     */
    fun handleRemote(ex: HttpExchange, device: String) {
        val path = ex.requestURI.path.trimEnd('/').ifEmpty { "/" }
        val method = ex.requestMethod.uppercase()
        val c = Call(ex, path, method, JsonObject(emptyMap()))
        if (ex.requestHeaders.getFirst("Origin") != null) return c.fail(403, "not available to web pages")
        if (!path.startsWith("/v1/") || path in REMOTE_DENIED) return c.fail(403, "not available from another device")
        runCatching { handle(ex, remote = device) }.onFailure { e -> runCatching { c.fail(500, "internal error: ${e::class.simpleName}") } }
    }

    private fun handle(ex: HttpExchange, remote: String? = null) {
        val empty = JsonObject(emptyMap())
        val path = ex.requestURI.path.trimEnd('/').ifEmpty { "/" }
        val method = ex.requestMethod.uppercase()
        if (remote == null && !sameMachine(ex)) return Call(ex, path, method, empty).fail(403, "requests must come from this computer")
        if (method == "GET" && path.startsWith("/shell/gesture/")) return gesture?.serve(ex, path.removePrefix("/shell/gesture/")) ?: run { ex.sendResponseHeaders(404, -1); ex.close() }
        if (method == "GET" && (path == "/" || path.startsWith("/shell/"))) return ShellAssets.serve(ex, path)
        if (path.startsWith("/openai/v1/") && remote == null) return openAi(ex, path)
        if (method == "GET" && capabilities?.files?.serves(path) == true) return capabilities.files.serve(ex, path)
        if (path == "/v1/health" && method == "GET") return Call(ex, path, method, empty).ok(buildJsonObject { put("ok", true); put("name", "CLU"); put("version", version) })
        if (path == "/v1/session" && method == "POST") {
            val code = readBody(ex, 4096)?.let { runCatching { CluJson.parseToJsonElement(String(it)).jsonObject["code"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
            val expiry = code?.let { launchCodes.remove(it) }
            val c = Call(ex, path, method, empty)
            return if (expiry != null && expiry >= System.currentTimeMillis()) c.ok(buildJsonObject { put("token", token) }) else c.fail(401, "launch code missing, used or expired")
        }
        if (remote == null && !authorised(ex)) return Call(ex, path, method, empty).fail(401, "missing or wrong token")
        if (path == "/v1/openai" && method == "GET") return Call(ex, path, method, empty).let { c -> if (remote != null) c.fail(403, "not available from another device")
            else c.ok(buildJsonObject { put("baseUrl", "http://127.0.0.1:$port/openai/v1"); put("apiKey", localApiKey); put("model", core.llm.state.value.modelName ?: "") }) }
        if (path == "/v1/documents" && method == "POST") {
            val bytes = readBody(ex, ai.clu.core.api.ArchiveRoutes.MAX_UPLOAD) ?: return Call(ex, path, method, empty).fail(413, "file over 50 MB")
            return archive.upload(Call(ex, path, method, empty), bytes)
        }
        val body = if (method == "POST" || method == "PUT" || method == "DELETE") {
            val bytes = readBody(ex, MAX_BODY) ?: return Call(ex, path, method, empty).fail(413, "request body over ${MAX_BODY / 1024} KiB")
            if (bytes.isEmpty()) empty else runCatching { CluJson.parseToJsonElement(String(bytes)).jsonObject }.getOrNull() ?: return Call(ex, path, method, empty).fail(400, "body must be a JSON object")
        } else empty
        val call = Call(ex, path, method, body)
        if (coreRoute(call) || archive.handle(call) || memory.handle(call) || models.handle(call) || agents.handle(call) || evolution?.handle(call) == true || capabilities?.routes?.handle(call) == true || capabilities?.kit?.handle(call) == true || capabilities?.apps?.handle(call) == true || voiceRoutes?.handle(call) == true || setup?.handle(call) == true || settings.handle(call)) return
        call.fail(404, "no such endpoint")
    }

    private fun coreRoute(c: Call): Boolean {
        val turn = Regex("^/v1/conversations/([^/]+)/turns$").find(c.path)
        val history = Regex("^/v1/conversations/([^/]+)/messages$").find(c.path)
        when {
            c.path == "/v1/status" && c.method == "GET" -> c.ok(status())
            // Request lifecycle of recent turns (text and voice): stage names, timings, token counts; no conversation content.
            c.path == "/v1/requests" && c.method == "GET" -> c.ok(buildJsonArray { core.agent.trace.recent().forEach { r -> addJsonObject {
                put("id", r.id); put("origin", r.origin); put("summary", r.summary()); put("outcome", r.terminal?.name?.lowercase() ?: "running")
                putJsonArray("events") { r.events.forEach { e -> addJsonObject { put("name", e.name); put("ms", e.atMs - r.startedAt); if (e.detail.isNotEmpty()) put("detail", e.detail) } } } } } })
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
            c.path == "/v1/gesture" && c.method == "GET" -> c.ok(gesture?.status() ?: buildJsonObject { put("installed", false); put("progress", "not available") })
            c.path == "/v1/gesture/install" && c.method == "POST" -> {
                val g = gesture ?: return true.also { c.fail(409, "not available") }
                if (c.body["confirm"]?.jsonPrimitive?.booleanOrNull != true) return true.also { c.fail(428, "downloading ${g.status()["bytes"]} bytes needs {\"confirm\": true}") }
                if (g.verified()) return true.also { c.ok(g.status()) }
                Thread({ g.download() }, "clu-gesture-pack").apply { isDaemon = true }.start()
                c.send(202, g.status())
            }
            c.path == "/v1/shell/ping" && c.method == "POST" -> { lifecycle.ping(); c.ok() }
            c.path == "/v1/shell/quit" && c.method == "POST" -> { c.ok(); Thread { Thread.sleep(200); lifecycle.onQuit?.invoke() }.start() }
            c.path == "/v1/shell/restart" && c.method == "POST" -> { c.ok(); Thread { Thread.sleep(200); lifecycle.onRestart?.invoke() }.start() }
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
        put("pendingApprovals", core.agentBroker.pendingCount()); put("activeTasks", core.agents.activeCount())
    }

    private fun message(m: Message) = ai.clu.core.api.ShellApi.message(m)

    private fun turn(c: Call, conversationId: String) {
        if (core.conversations.get(conversationId)?.deleted != false) return c.fail(404, "no such conversation")
        val text = c.str("text")?.trim().orEmpty()
        if (text.isEmpty()) return c.fail(400, "text is required")
        val voice = c.body["voice"]?.jsonPrimitive?.booleanOrNull ?: false
        core.llm.prepare() // counts as use for the idle timer
        val request = TurnRequest(conversationId, text, Actor.ownerInApp(), viaVoice = voice)
        val stream = c.ex.requestHeaders.getFirst("Accept")?.contains("text/event-stream") == true
        if (!stream) {
            var done: AgentEvent.Done? = null; var error: String? = null; var action: JsonObject? = null
            runBlocking { core.agent.respond(request).collect { e -> if (e is AgentEvent.Done) done = e; if (e is AgentEvent.Error) error = e.text
                if (e is AgentEvent.ToolFinished && e.tool == ai.clu.core.capability.CapabilityCommand.TOOL) action = e.result.data as? JsonObject } }
            val d = done
            return c.ok(buildJsonObject {
                put("text", d?.text ?: ""); put("error", d?.meta?.error?.ifEmpty { null } ?: error ?: "")
                put("interrupted", d?.meta?.interrupted ?: false); put("messageId", d?.message?.id ?: ""); action?.let { put("action", it) }
                putJsonArray("sources") { d?.meta?.sources?.forEach { s -> addJsonObject { put("title", s.title); put("url", s.url) } } }
            })
        }
        c.ex.responseHeaders.apply { set("Content-Type", "text/event-stream; charset=utf-8"); set("Cache-Control", "no-store"); set("X-Content-Type-Options", "nosniff") }
        c.ex.sendResponseHeaders(200, 0)
        c.ex.responseBody.use { out ->
            fun event(name: String, data: JsonElement) { out.write(ai.clu.core.api.ShellApi.sse(name, data)); out.flush() }
            runBlocking { core.agent.respond(request).collect { e -> ai.clu.core.api.ShellApi.event(e)?.let { (n, d) -> event(n, d) } } }
        }
    }

    companion object {
        const val MAX_BODY = 64 * 1024
        /** Window lifecycle, the launch-code exchange and this PC's own microphone session: never from another device. */
        val REMOTE_DENIED = setOf("/v1/session", "/v1/shell/quit", "/v1/shell/restart", "/v1/shell/open", "/v1/voice/session", "/v1/voice/session/stop")
    }
}

/**
 * The shell's static files, from the jar (`/shell/…`): an allowlisted name pattern (no paths, no "..") and a strict
 * Content-Security-Policy (scripts, styles and connections from this server only, no framing).
 */
object ShellAssets {
    private val NAME = Regex("^[a-z0-9_-]+\\.(html|css|js|svg)$")
    private val TYPES = mapOf("html" to "text/html", "css" to "text/css", "js" to "text/javascript", "svg" to "image/svg+xml")
    const val CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self' ws://127.0.0.1:* http://127.0.0.1:*; media-src 'self' blob:; " +
        "frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

    /** The Primus core (original jarvis-main files): framed by the shell only, so it may be embedded by CLU's own window. */
    private val PRIMUS = Regex("^[a-z0-9_-]+(?:\\.min)?\\.(html|css|js)$")

    fun serve(ex: HttpExchange, path: String) {
        val primus = path.startsWith("/shell/primus/")
        val name = if (path == "/") "index.html" else path.removePrefix("/shell/")
        val bytes = when {
            primus -> name.removePrefix("primus/").takeIf { PRIMUS.matches(it) }?.let { ShellAssets::class.java.getResourceAsStream("/shell/primus/$it")?.use { s -> s.readBytes() } }
            NAME.matches(name) -> ShellAssets::class.java.getResourceAsStream("/shell/$name")?.use { it.readBytes() }
            else -> null
        }
        ex.responseHeaders.apply {
            set("Content-Security-Policy", when { primus -> CSP.replace("frame-ancestors 'none'", "frame-ancestors 'self'")
                // The gesture page compiles MediaPipe's verified WebAssembly (needs 'wasm-unsafe-eval'; still no eval of text, no other sites).
                name == "hands.html" -> CSP.replace("script-src 'self'", "script-src 'self' 'wasm-unsafe-eval'").replace("frame-ancestors 'none'", "frame-ancestors 'self'")
                else -> CSP })
            set("X-Content-Type-Options", "nosniff"); set("Referrer-Policy", "no-referrer"); set("Cache-Control", if (primus && name.endsWith(".min.js")) "max-age=86400" else "no-store")
        }
        if (bytes == null) { ex.sendResponseHeaders(404, -1); ex.close(); return }
        ex.responseHeaders.set("Content-Type", TYPES.getValue(name.substringAfterLast('.')) + "; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
