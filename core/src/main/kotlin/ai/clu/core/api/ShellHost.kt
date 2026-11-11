package ai.clu.core.api

import ai.clu.core.CluCore
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.SettingResult
import ai.clu.core.security.Actor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom
import java.util.Base64

/**
 * The CLU shell (the PC window's interface) hosted on a tablet or phone. A WebView loads it from [LoopbackServer]; this
 * class answers its requests:
 *  - `/` and `/shell/…`: the shell's files, read by [asset] (from the APK), with the same Content-Security-Policy as the PC;
 *  - `/v1/session`: the one-time launch code (made by the activity, passed in the URL) exchanged for this run's token;
 *  - `/v1/shell/…`: the window's own lifecycle, answered here (never sent to the PC);
 *  - every other `/v1/…` call: sent to the paired PC with the device key ([pc]), so the tablet shows the PC's real
 *    conversations, memories, models and settings; streamed replies are passed through as they arrive;
 *  - when no PC is paired or it cannot be reached: answered here by the [LocalApi] subset, on this device's own data.
 *
 * Requests need this run's token; the PC never sees it (the device key goes to the PC instead), and the shell never
 * sees the device key.
 */
class ShellHost(
    private val core: CluCore,
    private val version: String,
    /** A shell file by its path below `shell/` (e.g. "index.html", "primus/app.js"), or null. */
    private val asset: (String) -> ByteArray?,
    /** The paired PC to forward to, or null for this device alone. */
    private val pc: () -> Remote?,
    /** The shell asked to close or reload its window. */
    private val window: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Tools that act on this device itself (alarm, volume, open an app…). While paired, a typed command for one of these
     * runs here, not on the PC, which would otherwise set the alarm or open the app on the PC.
     */
    private val deviceTools: Set<String> = emptySet(),
    /** What this platform can do, for the shell (capability detection; nothing unsupported is pretended). */
    private val platform: JsonObject = buildJsonObject { put("platform", "android"); putJsonArray("pcOnly") { PC_ONLY.forEach { add(it) } } },
) {
    data class Remote(val base: String, val deviceKey: String)

    private val random = SecureRandom()
    private fun secret() = ByteArray(24).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val token = secret()
    @Volatile private var launchCode: Pair<String, Long>? = null
    /** Until when the PC is treated as unreachable after a failed connection (answers come from this device meanwhile). */
    @Volatile private var pcDownUntil = 0L
    @Volatile var lastPcError: String? = null; private set
    private val local = LocalApi(core, version, now)

    /** A fresh single-use launch code for the WebView's URL (valid for two minutes). */
    fun newLaunchCode(): String = secret().also { launchCode = it to now() + 120_000 }

    /** Where the shell is answered from right now: "pc", "device" (none paired) or "offline" (paired, not reachable). */
    fun link(): String = when { pc() == null -> "device"; now() < pcDownUntil -> "offline"; else -> "pc" }

    fun handle(ex: Exchange) {
        val path = ex.path.trimEnd('/').ifEmpty { "/" }
        if (ex.method == "GET" && (path == "/" || path.startsWith("/shell/"))) return serveAsset(ex, path)
        if (!path.startsWith("/v1/")) return ex.json(404, err("not found"))
        if (path == "/v1/session" && ex.method == "POST") {
            val code = runCatching { Json.parseToJsonElement(String(ex.body)).jsonObject["code"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            val held = launchCode
            if (code == null || held == null || held.second < now() || !constantTimeEquals(code, held.first)) return ex.json(401, err("launch code missing, used or expired"))
            launchCode = null
            return ex.json(200, buildJsonObject { put("token", token) }.toString())
        }
        if (!constantTimeEquals(ex.header("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty(), token)) return ex.json(401, err("missing or wrong token"))
        if (path.startsWith("/v1/shell/")) return shell(ex, path)
        val remote = pc()
        if (remote != null && ex.method == "POST" && TURN.matches(path) && deviceCommand(ex)) return
        if (remote != null && now() >= pcDownUntil && forward(ex, remote)) return
        local.handle(ex, path, link())
    }

    private fun shell(ex: Exchange, path: String) = when (path) {
        "/v1/shell/ping" -> ex.json(200, """{"ok":true}""")
        // Where answers come from, for the shell's status line (and to reload when it changes).
        "/v1/shell/link" -> ex.json(200, buildJsonObject { put("link", link()); lastPcError?.let { put("error", it) } }.toString())
        // Capability detection: which host answers and what this platform supports.
        "/v1/shell/platform" -> ex.json(200, buildJsonObject { platform.forEach { (k, v) -> put(k, v) }; put("link", link()); put("version", version)
            putJsonArray("deviceTools") { deviceTools.sorted().forEach { add(it) } } }.toString())
        "/v1/shell/quit", "/v1/shell/restart" -> { window(path.substringAfterLast('/')); ex.json(200, """{"ok":true}""") }
        else -> ex.json(404, err("not available on this device"))
    }

    /**
     * Sends the request to the PC and copies the answer back as it arrives. False if the PC could not be reached at all
     * (then this device answers); once the PC has answered, its answer is final, errors included.
     */
    private fun forward(ex: Exchange, remote: Remote): Boolean {
        val url = remote.base.trimEnd('/') + ex.path + (ex.rawQuery?.let { "?$it" } ?: "")
        val c = try { URI(url).toURL().openConnection() as HttpURLConnection } catch (e: Exception) { lastPcError = "bad PC address"; return false }
        try {
            c.connectTimeout = CONNECT_TIMEOUT_MS; c.readTimeout = 0; c.instanceFollowRedirects = false; c.useCaches = false
            c.requestMethod = ex.method
            c.setRequestProperty("Authorization", "Bearer " + remote.deviceKey)
            // Only what the API reads; never Origin or Cookie (the PC refuses requests from web pages).
            for (h in FORWARDED) ex.header(h)?.let { c.setRequestProperty(h, it) }
            if (ex.body.isNotEmpty() || ex.method in setOf("POST", "PUT", "PATCH")) {
                c.doOutput = true; c.setFixedLengthStreamingMode(ex.body.size); c.outputStream.use { it.write(ex.body) }
            }
            val code = c.responseCode
            pcDownUntil = 0; lastPcError = null
            val type = c.contentType ?: "application/octet-stream"
            val input = (if (code >= 400) c.errorStream else c.inputStream)
            val extra = mapOf("X-Content-Type-Options" to "nosniff", "X-Clu-Link" to "pc")
            if (input == null) { ex.respond(code, type, ByteArray(0), extra); return true }
            input.use { s ->
                if (type.startsWith("text/event-stream")) ex.stream(code, type, extra) { out ->
                    val buf = ByteArray(8192)
                    while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); out.flush() }
                } else ex.respond(code, type, s.readBytes(), extra + ("Cache-Control" to "no-store"))
            }
            return true
        } catch (e: IOException) {
            if (ex.committed) return true // the PC went away mid-answer; the shell sees the reply end
            pcDownUntil = now() + RETRY_MS; lastPcError = e.message ?: e::class.simpleName
            return false
        } finally { c.disconnect() }
    }

    /** A device command typed while paired: run on this device, answered in the turn stream's format. False = not one. */
    private fun deviceCommand(ex: Exchange): Boolean {
        if (deviceTools.isEmpty()) return false
        val text = runCatching { Json.parseToJsonElement(String(ex.body)).jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim() ?: return false
        val intent = ai.clu.core.agent.IntentRouter.route(text) ?: return false
        if (intent.tool !in deviceTools || core.toolRegistry[intent.tool] == null) return false
        val r = runBlocking { core.tools.execute(intent.tool, intent.args, ai.clu.core.tools.ToolContext(Actor.ownerInApp(), core.personas.active(), null, now())) }
        val reply = (r.spoken ?: r.summary).ifBlank { if (r.success) "Done." else "That didn't work." }
        ex.stream(200, "text/event-stream; charset=utf-8", mapOf("X-Content-Type-Options" to "nosniff", "X-Clu-Link" to "device")) { out ->
            out.write(ShellApi.sse("status", buildJsonObject { put("text", "On this device") }))
            out.write(ShellApi.sse("text", buildJsonObject { put("delta", reply) }))
            out.write(ShellApi.sse("done", ShellApi.done(reply, error = if (r.success) "" else (r.error ?: reply)))); out.flush()
        }
        return true
    }

    private fun serveAsset(ex: Exchange, path: String) {
        val primus = path.startsWith("/shell/primus/")
        val name = if (path == "/") "index.html" else path.removePrefix("/shell/")
        val ok = if (primus) PRIMUS.matches(name.removePrefix("primus/")) else NAME.matches(name)
        val bytes = if (ok) asset(name) else null
        if (bytes == null) return ex.json(404, err("not found"))
        val csp = when {
            primus -> CSP.replace("frame-ancestors 'none'", "frame-ancestors 'self'")
            name == "hands.html" -> CSP.replace("script-src 'self'", "script-src 'self' 'wasm-unsafe-eval'").replace("frame-ancestors 'none'", "frame-ancestors 'self'")
            else -> CSP
        }
        ex.respond(200, TYPES.getValue(name.substringAfterLast('.')) + "; charset=utf-8", bytes, mapOf(
            "Content-Security-Policy" to csp, "X-Content-Type-Options" to "nosniff", "Referrer-Policy" to "no-referrer",
            "Cache-Control" to if (primus && name.endsWith(".min.js")) "max-age=86400" else "no-store"))
    }

    private fun constantTimeEquals(a: String, b: String) = java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        const val CONNECT_TIMEOUT_MS = 2500
        private val TURN = Regex("^/v1/conversations/[^/]+/turns$")
        /** Shell views only the PC provides: coding agents, model management, capabilities (self-built apps), camera gestures. */
        val PC_ONLY = listOf("agents", "models", "capabilities", "hands")
        const val RETRY_MS = 15_000L
        private val FORWARDED = listOf("Content-Type", "Accept", "X-File-Name")
        private val NAME = Regex("^[a-z0-9_-]+\\.(html|css|js|svg)$")
        private val PRIMUS = Regex("^[a-z0-9_-]+(?:\\.min)?\\.(html|css|js)$")
        private val TYPES = mapOf("html" to "text/html", "css" to "text/css", "js" to "text/javascript", "svg" to "image/svg+xml")
        /** As on the PC; connections only to this server (the PC is reached through it, never by the page itself). */
        const val CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; media-src 'self' blob:; " +
            "frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
        fun err(m: String) = buildJsonObject { put("error", m) }.toString()
    }
}

/**
 * What the shell can do on this device alone (no PC paired, or the PC is out of reach): chat with the on-device model,
 * the conversations and settings stored here, suggestions. Everything that belongs to the PC (models, agents, documents,
 * capabilities, voice settings) answers 503 with a plain explanation.
 */
class LocalApi(private val core: CluCore, private val version: String, private val now: () -> Long) {
    private val startedAt = now()
    /** The same archive and memory routes as the PC (shared code, this device's data). */
    private val archive = ArchiveRoutes(core)
    private val memory = MemoryRoutes(core)

    fun handle(ex: Exchange, path: String, link: String) {
        val m = ex.method
        val body: JsonObject by lazy { runCatching { Json.parseToJsonElement(String(ex.body)).jsonObject }.getOrDefault(JsonObject(emptyMap())) }
        val messages = Regex("^/v1/conversations/([^/]+)/messages$").find(path)
        val turn = Regex("^/v1/conversations/([^/]+)/turns$").find(path)
        when {
            path == "/v1/status" && m == "GET" -> ok(ex, status(link))
            path == "/v1/app/config" && m == "GET" -> ok(ex, buildJsonObject { put("version", version); put("runInBackground", true); put("startWithWindowsSupported", false); put("host", "device") })
            path == "/v1/settings" && m == "GET" -> ok(ex, ShellApi.settings(core.settings))
            path == "/v1/settings" && m == "PUT" -> {
                val key = body["key"]?.jsonPrimitive?.contentOrNull ?: return ex.json(400, ShellHost.err("key is required"))
                val value = body["value"] ?: return ex.json(400, ShellHost.err("value is required"))
                when (val r = core.settings.set(key, value)) {
                    SettingResult.Ok -> ok(ex, buildJsonObject { put("ok", true); put("value", core.settings.raw(key)) })
                    is SettingResult.Invalid -> ex.json(400, ShellHost.err(r.reason))
                    SettingResult.RequiresVerification -> ex.json(403, ShellHost.err("Changing this needs owner verification: use Settings in the classic view."))
                    SettingResult.UnknownSetting -> ex.json(404, ShellHost.err("no such setting"))
                }
            }
            path == "/v1/conversations" && m == "GET" -> ok(ex, buildJsonArray {
                core.conversations.list().forEach { cv -> addJsonObject { put("id", cv.id); put("title", cv.title); put("updatedAt", cv.updatedAt) } } })
            path == "/v1/conversations" && m == "POST" -> {
                val cv = core.conversations.create(body["title"]?.jsonPrimitive?.contentOrNull?.take(120)?.ifBlank { null } ?: "New conversation")
                ex.json(201, buildJsonObject { put("id", cv.id); put("title", cv.title) }.toString())
            }
            messages != null && m == "GET" -> {
                val id = messages.groupValues[1]
                if (core.conversations.get(id)?.deleted != false) ex.json(404, ShellHost.err("no such conversation"))
                else ok(ex, buildJsonArray { core.conversations.messages(id).forEach { add(ShellApi.message(it)) } })
            }
            turn != null && m == "POST" -> turn(ex, turn.groupValues[1], body)
            path == "/v1/stop" && m == "POST" -> { core.agent.cancel(null); ex.json(200, """{"ok":true}""") }
            path == "/v1/documents" && m == "POST" -> archive.upload(ExchangeCall(ex, path), ex.body)
            path == "/v1/capabilities" && m == "GET" -> ok(ex, JsonArray(emptyList()))
            path == "/v1/agents/approvals" && m == "GET" -> ok(ex, JsonArray(emptyList()))
            ExchangeCall(ex, path).let { c -> archive.handle(c) || memory.handle(c) } -> {}
            else -> ex.json(503, ShellHost.err(if (link == "offline") "Your PC can't be reached right now. This is available again when it is." else "This is available when this device is connected to your PC (Settings → Connection in the classic view)."))
        }
    }

    private fun ok(ex: Exchange, e: JsonElement) = ex.json(200, e.toString())

    private fun status(link: String) = buildJsonObject {
        put("name", "CLU"); put("version", version); put("uptimeMs", now() - startedAt); put("host", "device"); put("link", link)
        core.llm.state.value.let { s -> putJsonObject("model") { put("status", s.status.name); put("name", s.modelName ?: ""); put("message", s.message); put("contextSize", s.contextSize); put("backend", s.backend) } }
        putJsonObject("governor") { putJsonArray("events") {} }
        put("conversations", core.conversations.list().size)
        put("pendingSuggestions", core.proposals.pending().size)
        put("pendingApprovals", 0); put("activeTasks", 0)
    }

    private fun turn(ex: Exchange, conversationId: String, body: JsonObject) {
        if (core.conversations.get(conversationId)?.deleted != false) return ex.json(404, ShellHost.err("no such conversation"))
        val text = body["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) return ex.json(400, ShellHost.err("text is required"))
        core.llm.prepare()
        val request = TurnRequest(conversationId, text, Actor.ownerInApp(), viaVoice = body["voice"]?.jsonPrimitive?.booleanOrNull ?: false)
        ex.stream(200, "text/event-stream; charset=utf-8", mapOf("X-Content-Type-Options" to "nosniff", "X-Clu-Link" to "device")) { out ->
            runBlocking { core.agent.respond(request).collect { e -> ShellApi.event(e)?.let { (n, d) -> out.write(ShellApi.sse(n, d)); out.flush() } } }
        }
    }
}
