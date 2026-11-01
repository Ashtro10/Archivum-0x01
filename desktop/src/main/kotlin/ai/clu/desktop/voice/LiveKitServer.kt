package ai.clu.desktop.voice

import ai.clu.desktop.OwnerOnly
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Room tokens for LiveKit, signed on this PC with CLU's own key. The secret never leaves this computer. */
class LiveKitTokens(val apiKey: String, private val secret: String, private val now: () -> Long = System::currentTimeMillis) {
    /** A short-lived token for one identity in one room. [agent]: CLU's own participant (marked as an agent for clients). */
    fun issue(identity: String, room: String, name: String = identity, ttlSeconds: Long = 600, canPublish: Boolean = true, agent: Boolean = false, attributes: Map<String, String> = emptyMap()): String {
        require(identity.isNotBlank() && room.isNotBlank())
        val t = now() / 1000
        val payload = buildJsonObject {
            put("iss", apiKey); put("sub", identity); put("name", name); put("nbf", t - 10); put("exp", t + ttlSeconds.coerceIn(30, 3600)); put("jti", rnd(12))
            putJsonObject("video") { put("room", room); put("roomJoin", true); put("canPublish", canPublish); put("canSubscribe", true); put("canPublishData", true); put("canUpdateOwnMetadata", agent) }
            if (agent) put("kind", "agent")
            if (attributes.isNotEmpty()) putJsonObject("attributes") { attributes.forEach { (k, v) -> put(k, v) } }
        }
        val enc = Base64.getUrlEncoder().withoutPadding()
        val head = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val body = enc.encodeToString(payload.toString().toByteArray())
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return "$head.$body.${enc.encodeToString(mac.doFinal("$head.$body".toByteArray()))}"
    }

    companion object {
        private val random = SecureRandom()
        fun rnd(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}

/**
 * Runs CLU's own LiveKit server (the open-source livekit-server, installed with CLU) on this PC: generated credentials
 * (never the development key), loopback only unless phone access is switched on (then this PC's private-network address
 * too, never a public one), health-checked, restarted after a crash (a few times, then it says so), stopped when idle.
 */
class LiveKitServer(private val dir: File, private val binary: File?, private val log: (String) -> Unit = {}) {
    enum class State { STOPPED, STARTING, RUNNING, ERROR }
    @Volatile var state = State.STOPPED; private set
    @Volatile var message = ""; private set
    @Volatile private var process: Process? = null
    @Volatile var port = 7880; private set
    @Volatile var lanAddress: String? = null; private set
    private val restarts = ArrayDeque<Long>()
    private var watchdog: Thread? = null
    @Volatile private var wanted = false
    val logFile get() = File(dir, "livekit.log")

    val tokens: LiveKitTokens by lazy { keys().let { LiveKitTokens(it.first, it.second) } }
    val localUrl get() = "ws://127.0.0.1:$port"
    val lanUrl get() = lanAddress?.let { "ws://$it:$port" }

    /** The API key and secret, generated once and kept owner-only in CLU's data folder. */
    private fun keys(): Pair<String, String> {
        dir.mkdirs()
        val f = File(dir, "livekit-keys.json")
        runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull()?.let { o ->
            val k = o["apiKey"]?.jsonPrimitive?.contentOrNull; val s = o["secret"]?.jsonPrimitive?.contentOrNull
            if (k != null && s != null && s.length >= 32) return k to s }
        val k = "CLU" + LiveKitTokens.rnd(9).replace("-", "x").replace("_", "y"); val s = LiveKitTokens.rnd(36)
        f.writeText(buildJsonObject { put("apiKey", k); put("secret", s) }.toString()); OwnerOnly.restrict(f)
        return k to s
    }

    /** Private (RFC 1918 / CGNAT) IPv4 address of this PC on the local network, if any. */
    /** This PC's private-network IPv4 address. `-Dclu.voiceLanAddress` overrides it (tests; it must still be a private or loopback address). */
    fun detectLanAddress(): String? = System.getProperty("clu.voiceLanAddress")?.let { o -> runCatching { InetAddress.getByName(o) }.getOrNull()?.takeIf { it.isLoopbackAddress || isPrivate(it) }?.hostAddress } ?: runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
            .flatMap { it.inetAddresses.asSequence() }.filter { it.address.size == 4 && isPrivate(it) }.map { it.hostAddress }.firstOrNull()
    }.getOrNull()

    private fun isPrivate(a: InetAddress): Boolean { val b = a.address; val b0 = b[0].toInt() and 0xff; val b1 = b[1].toInt() and 0xff
        return a.isSiteLocalAddress || (b0 == 100 && b1 in 64..127) }

    /** Starts the server (or reuses a healthy one). [lan]: also reachable from phones on this network. Blocks until healthy or failed. */
    @Synchronized fun ensure(lan: Boolean): Boolean {
        wanted = true
        val wantLan = if (lan) detectLanAddress() else null
        if (state == State.RUNNING && process?.isAlive == true && wantLan == lanAddress && healthy()) return true
        if (process?.isAlive == true) stopProcess()
        val bin = binary?.takeIf { it.isFile } ?: return fail("The LiveKit server is not installed with CLU. Reinstall CLU.")
        if (lan && wantLan == null) log("phone access requested but this PC has no private-network address; LiveKit stays on this PC only")
        lanAddress = wantLan
        port = freePort(7880)
        val rtcTcp = freePort(port + 1); val rtcUdp = freePort(rtcTcp + 1)
        val k = keys()
        val bind = listOfNotNull("127.0.0.1", lanAddress).distinct()
        val yaml = buildString {
            appendLine("# Written by CLU on each start. Not the development configuration: CLU's own generated key, no TURN, no public addresses.")
            appendLine("port: $port")
            appendLine("bind_addresses: [${bind.joinToString { "\"$it\"" }}]")
            appendLine("rtc:")
            appendLine("  tcp_port: $rtcTcp")
            appendLine("  udp_port: $rtcUdp")
            appendLine("  use_external_ip: false")
            appendLine("  node_ip: ${lanAddress ?: "127.0.0.1"}")
            appendLine("  enable_loopback_candidate: true")
            appendLine("  ips:")
            appendLine("    includes: [${(listOf("127.0.0.0/8") + listOfNotNull(lanAddress?.let { "$it/32" })).joinToString { "\"$it\"" }}]")
            appendLine("keys:")
            appendLine("  ${k.first}: ${k.second}")
            appendLine("room:")
            appendLine("  auto_create: true")
            appendLine("  empty_timeout: 120")
            appendLine("  max_participants: 6")
            appendLine("logging:")
            appendLine("  level: info")
        }
        val cfg = File(dir, "livekit.yaml"); cfg.writeText(yaml); OwnerOnly.restrict(cfg)
        state = State.STARTING; message = "Starting"
        if (logFile.length() > 2_000_000) logFile.delete()
        val p = runCatching { ProcessBuilder(bin.absolutePath, "--config", cfg.absolutePath).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logFile)).start() }
            .getOrElse { return fail("The LiveKit server could not be started: ${it.message}") }
        process = p
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            if (!p.isAlive) return fail("The LiveKit server stopped right after starting (exit ${p.exitValue()}). Details: ${logFile.absolutePath}")
            if (healthy()) { state = State.RUNNING; message = "Running on port $port" + (lanAddress?.let { " (this PC and the local network: $it)" } ?: " (this PC only)"); startWatchdog(); log("livekit-server running: $message"); return true }
            Thread.sleep(100)
        }
        stopProcess(); return fail("The LiveKit server did not become ready in 15 seconds. Details: ${logFile.absolutePath}")
    }

    fun healthy(): Boolean = runCatching {
        val c = URI("http://127.0.0.1:$port/").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 500; c.readTimeout = 1000
        c.responseCode == 200
    }.getOrDefault(false)

    private fun startWatchdog() {
        if (watchdog?.isAlive == true) return
        watchdog = Thread({
            while (wanted) {
                Thread.sleep(3000)
                val p = process
                if (!wanted) break
                if (p != null && p.isAlive && healthy()) continue
                // Crashed or hung: restart, at most 3 times in 5 minutes.
                val now = System.currentTimeMillis(); while (restarts.isNotEmpty() && now - restarts.first() > 300_000) restarts.removeFirst()
                if (restarts.size >= 3) { fail("The LiveKit server keeps stopping (3 restarts in 5 minutes). Voice is off until CLU is restarted. Details: ${logFile.absolutePath}"); wanted = false; break }
                restarts.addLast(now); log("livekit-server not healthy; restarting")
                runCatching { ensure(lanAddress != null) }
            }
        }, "clu-livekit-watchdog").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() { wanted = false; stopProcess(); state = State.STOPPED; message = "Stopped" }

    private fun stopProcess() {
        val p = process ?: return
        p.destroy(); if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        process = null
    }

    /** The server process's memory (for the resource governor), or 0. */
    fun pid(): Long? = process?.takeIf { it.isAlive }?.pid()

    private fun fail(m: String): Boolean { state = State.ERROR; message = m; log(m); return false }

    private fun freePort(from: Int): Int { for (p in from until from + 50) if (runCatching { ServerSocket(p, 1, InetAddress.getByName("127.0.0.1")).use { true } }.getOrDefault(false)) return p; return from }
}
