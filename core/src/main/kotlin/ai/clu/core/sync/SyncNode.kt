package ai.clu.core.sync

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.CluStore
import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.llm.*
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Asks the user to compare the 6-digit code shown on both devices. */
fun interface PairingHandler { suspend fun confirm(peerName: String, peerRole: String, sas: String): Boolean }

data class SyncResult(val ok: Boolean, val peerId: String?, val sent: Int, val received: Int, val conflicts: Int, val error: String? = null, val durationMs: Long = 0)

/**
 * One device's network endpoint: TCP server for the paired peer, client sessions for pairing/sync, and remote
 * inference both ways (serving the home device's model; using it from the portable device).
 */
class SyncNode(
    private val store: CluStore,
    private val settings: SettingsStore,
    private val engine: SyncEngine,
    vault: KeyVault,
    private val deviceName: () -> String,
    var pairing: PairingHandler,
    private val inferenceProvider: suspend () -> LlmEngine?,
    private val diag: DiagnosticsLog?,
) {
    private val keys: KeyPair = engine.identityKey(vault)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    @Volatile private var pairingOpenUntil = 0L
    private val running = AtomicBoolean(false)

    val publicKey: ByteArray get() = keys.public.encoded
    val isServing get() = server?.isClosed == false

    // ================================================================= server
    fun startServer(port: Int = settings.int(Settings.SYNC_PORT)): Int {
        stopServer()
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        running.set(true)
        scope.launch {
            while (running.get() && !ss.isClosed) {
                val sock = try { ss.accept() } catch (e: IOException) { break }
                launch { handleIncoming(sock) }
            }
        }
        diag?.i("sync", "listening on ${ss.localPort}")
        return ss.localPort
    }

    fun stopServer() { running.set(false); runCatching { server?.close() }; server = null }

    fun openPairingWindow(ms: Long = 120_000) { pairingOpenUntil = store.clock.now() + ms }
    fun isPairingOpen() = store.clock.now() < pairingOpenUntil

    private suspend fun handleIncoming(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 60_000
            s.tcpNoDelay = true
            val session = try { handshake(s, client = false, pairMode = null) } catch (e: Exception) {
                diag?.w("sync", "incoming handshake failed: ${e.message}"); return
            }
            engine.markSeen(session.peerId, s.inetAddress.hostAddress)
            try { serve(session) } catch (e: IOException) { diag?.d("sync", "session ended: ${e.message}") }
        }
    }

    // ================================================================= handshake
    private class Session(val channel: SecureChannel, val peerId: String, val peerName: String, val peerRole: String, val socket: Socket)

    private fun hello(mode: String, eph: KeyPair) = buildJsonObject {
        put("t", "hello"); put("v", 1); put("mode", mode); put("deviceId", store.deviceId); put("name", deviceName())
        put("role", settings.string(Settings.DEVICE_ROLE)); put("entityId", store.entityId)
        put("static", SyncEngine.b64(keys.public.encoded)); put("eph", SyncEngine.b64(eph.public.encoded))
        put("nonce", SyncEngine.b64(SecureChannel.nonce()))
    }

    private suspend fun handshake(s: Socket, client: Boolean, pairMode: Boolean?): Session {
        val inp = s.getInputStream(); val out = s.getOutputStream()
        val eph = Crypto.newKeyPair()
        val myHelloBytes: ByteArray
        val peerHelloBytes: ByteArray
        val mode: String
        if (client) {
            mode = if (pairMode == true) "pair" else "session"
            myHelloBytes = hello(mode, eph).toString().toByteArray()
            SecureChannel.writeRaw(out, myHelloBytes)
            peerHelloBytes = SecureChannel.readRaw(inp)
        } else {
            peerHelloBytes = SecureChannel.readRaw(inp)
            val ph = CluJson.parseToJsonElement(String(peerHelloBytes)).jsonObject
            mode = ph["mode"]?.jsonPrimitive?.content ?: "session"
            if (mode == "pair" && !isPairingOpen()) { SecureChannel.writeRaw(out, err("pairing is not open on this device")); throw ChannelException("pairing not open") }
            if (mode == "session") {
                val pid = ph["deviceId"]?.jsonPrimitive?.content ?: ""
                if (!settings.bool(Settings.SYNC_ENABLED)) { SecureChannel.writeRaw(out, err("sync disabled")); throw ChannelException("sync disabled") }
                if (engine.peer(pid) == null) { SecureChannel.writeRaw(out, err("unknown device – pair first")); throw ChannelException("unknown peer") }
            }
            myHelloBytes = hello(mode, eph).toString().toByteArray()
            SecureChannel.writeRaw(out, myHelloBytes)
        }
        val peer = CluJson.parseToJsonElement(String(peerHelloBytes)).jsonObject
        if (peer["t"]?.jsonPrimitive?.content == "error") throw ChannelException(peer["reason"]?.jsonPrimitive?.content ?: "rejected")
        val peerId = peer["deviceId"]!!.jsonPrimitive.content
        val peerName = peer["name"]?.jsonPrimitive?.content ?: "device"
        val peerRole = peer["role"]?.jsonPrimitive?.content ?: "portable"
        if (peerId == store.deviceId) throw ChannelException("connected to self")
        val peerStatic = SyncEngine.b64d(peer["static"]!!.jsonPrimitive.content)
        val peerEph = Crypto.publicFrom(SyncEngine.b64d(peer["eph"]!!.jsonPrimitive.content))
        val transcript = if (client) Crypto.sha256(myHelloBytes, peerHelloBytes) else Crypto.sha256(peerHelloBytes, myHelloBytes)
        val ee = Crypto.ecdh(eph.private, peerEph)

        val k: ByteArray = if (mode == "pair") {
            val kk = Crypto.hkdf(ee, transcript, "clu-pair")
            val sas = Crypto.sas(kk, transcript)
            val ok = withTimeoutOrNull(120_000) { pairing.confirm(peerName, peerRole, sas) } ?: false
            val myConfirm = buildJsonObject { put("t", "confirm"); put("ok", ok); put("mac", SyncEngine.b64(Crypto.hmac(kk, "confirm".toByteArray(), store.deviceId.toByteArray(), transcript))) }
            SecureChannel.writeRaw(out, myConfirm.toString().toByteArray())
            val pc = CluJson.parseToJsonElement(String(SecureChannel.readRaw(inp))).jsonObject
            val peerOk = pc["ok"]?.jsonPrimitive?.booleanOrNull == true
            val expected = Crypto.hmac(kk, "confirm".toByteArray(), peerId.toByteArray(), transcript)
            if (!java.security.MessageDigest.isEqual(expected, SyncEngine.b64d(pc["mac"]!!.jsonPrimitive.content))) throw ChannelException("pairing MAC mismatch")
            if (!ok || !peerOk) throw ChannelException("pairing code not confirmed")
            engine.addPeer(peerId, peerName, peerRole, peerStatic)
            // One Clu: the portable device adopts the home device's entity (ties: lower device id wins).
            val peerEntity = peer["entityId"]?.jsonPrimitive?.content
            val myRole = settings.string(Settings.DEVICE_ROLE)
            val adopt = peerEntity != null && (peerRole == "home" && myRole != "home" || peerRole == myRole && peerId < store.deviceId)
            if (adopt) store.adoptEntity(peerEntity!!)
            diag?.i("sync", "paired with $peerName ($peerRole)")
            Crypto.hkdf(ee + Crypto.ecdh(keys.private, Crypto.publicFrom(peerStatic)), transcript, "clu-session")
        } else {
            val known = engine.peer(peerId) ?: throw ChannelException("unknown device – pair first")
            if (!known.publicKey.contentEquals(peerStatic)) throw ChannelException("device key mismatch – re-pair required")
            val ss = Crypto.ecdh(keys.private, Crypto.publicFrom(peerStatic))
            val kk = Crypto.hkdf(ee + ss, transcript, "clu-session")
            val myMac = Crypto.hmac(kk, "auth".toByteArray(), store.deviceId.toByteArray(), transcript)
            SecureChannel.writeRaw(out, buildJsonObject { put("t", "auth"); put("mac", SyncEngine.b64(myMac)) }.toString().toByteArray())
            val pa = CluJson.parseToJsonElement(String(SecureChannel.readRaw(inp))).jsonObject
            val expected = Crypto.hmac(kk, "auth".toByteArray(), peerId.toByteArray(), transcript)
            if (!java.security.MessageDigest.isEqual(expected, SyncEngine.b64d(pa["mac"]?.jsonPrimitive?.content ?: ""))) throw ChannelException("authentication failed")
            kk
        }
        val c2s = Crypto.hkdf(k, transcript, "c2s"); val s2c = Crypto.hkdf(k, transcript, "s2c")
        val ch = if (client) SecureChannel(inp, out, c2s, s2c) else SecureChannel(inp, out, s2c, c2s)
        return Session(ch, peerId, peerName, peerRole, s)
    }

    private fun err(reason: String) = buildJsonObject { put("t", "error"); put("reason", reason) }.toString().toByteArray()

    private fun Session.send(o: JsonObject) = channel.send(o.toString().toByteArray())
    private fun Session.recv(): JsonObject = CluJson.parseToJsonElement(String(channel.receive())).jsonObject

    // ================================================================= sync protocol
    /** Sends our changes until the peer has acknowledged everything. Resumable: acks are persisted per chunk. */
    private fun Session.pushChanges(): Int {
        var sent = 0
        while (true) {
            val acked = engine.peer(peerId)?.ackedSeq ?: 0
            val (rows, upto) = engine.changesSince(acked)
            if (upto <= acked) break
            send(buildJsonObject { put("t", "changes"); put("upto", upto); put("rows", CluJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(SyncRow.serializer()), rows)) })
            val ack = recv()
            if (ack["t"]?.jsonPrimitive?.content != "ack") throw ChannelException("expected ack")
            engine.setAcked(peerId, ack["upto"]!!.jsonPrimitive.long)
            sent += rows.size
        }
        send(buildJsonObject { put("t", "done") })
        return sent
    }

    private fun Session.pullChanges(stats: SyncEngine.ApplyStats): Int {
        var received = 0
        while (true) {
            val m = recv()
            when (m["t"]?.jsonPrimitive?.content) {
                "changes" -> {
                    val rows = CluJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(SyncRow.serializer()), m["rows"]!!)
                    val st = engine.apply(rows, peerId)
                    stats.applied += st.applied; stats.conflicts += st.conflicts; stats.skipped += st.skipped
                    received += rows.size
                    send(buildJsonObject { put("t", "ack"); put("upto", m["upto"]!!.jsonPrimitive.long) })
                }
                "done" -> return received
                else -> throw ChannelException("unexpected ${m["t"]}")
            }
        }
    }

    /** Server side of a session: answer sync and inference requests until the client says bye. */
    private suspend fun serve(sess: Session) {
        while (true) {
            val m = sess.recv()
            when (m["t"]?.jsonPrimitive?.content) {
                "sync" -> {
                    val hist = engine.startHistory(sess.peerName)
                    val st = SyncEngine.ApplyStats()
                    val got = sess.pullChanges(st)
                    val sent = sess.pushChanges()
                    engine.completeSync(sess.peerId)
                    engine.finishHistory(hist, sent, got, st.conflicts, null)
                }
                "infer" -> serveInference(sess, m)
                "ctx" -> { val e = inferenceProvider(); sess.send(buildJsonObject { put("t", "ctx"); put("size", e?.contextSize() ?: 0); put("ready", e != null) }) }
                "count" -> {
                    val e = inferenceProvider()
                    val n = e?.countTokens(parseMessages(m)) ?: -1
                    sess.send(buildJsonObject { put("t", "count"); put("n", n) })
                }
                "cancel" -> { cancelling[m["id"]?.jsonPrimitive?.content ?: ""]?.cancel(); sess.send(buildJsonObject { put("t", "ok") }) }
                "ping" -> sess.send(buildJsonObject { put("t", "pong"); put("name", deviceName()) })
                "bye" -> return
                else -> throw ChannelException("unknown request ${m["t"]}")
            }
        }
    }

    private suspend fun serveInference(sess: Session, m: JsonObject) {
        val id = m["id"]?.jsonPrimitive?.content ?: newId()
        val e = inferenceProvider()
        if (e == null) { sess.send(buildJsonObject { put("t", "error"); put("id", id); put("reason", "no model loaded on ${deviceName()}") }); return }
        val p = m["params"]!!.jsonObject
        val params = GenParams(maxTokens = p["maxTokens"]!!.jsonPrimitive.int, temperature = p["temperature"]!!.jsonPrimitive.float,
            topK = p["topK"]!!.jsonPrimitive.int, topP = p["topP"]!!.jsonPrimitive.float, minP = p["minP"]!!.jsonPrimitive.float,
            repeatPenalty = p["repeatPenalty"]!!.jsonPrimitive.float, grammar = p["grammar"]?.jsonPrimitive?.contentOrNull,
            stop = p["stop"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
        // Cancellation arrives on a separate authenticated connection ("cancel" request, see RemoteEngine.cancel).
        cancelling[id] = e
        try {
            val r = withContext(Dispatchers.IO) { e.generate(parseMessages(m), params) { text -> sess.send(buildJsonObject { put("t", "chunk"); put("id", id); put("text", text) }) } }
            sess.send(buildJsonObject { put("t", "result"); put("id", id); put("stats", CluJson.encodeToJsonElement(GenStats.serializer(), r.stats)) })
        } catch (ex: LlmException) {
            sess.send(buildJsonObject { put("t", "error"); put("id", id); put("reason", ex.message ?: "generation failed") })
        } finally { cancelling.remove(id) }
    }

    private val cancelling = ConcurrentHashMap<String, LlmEngine>()

    private fun parseMessages(m: JsonObject) = m["messages"]!!.jsonArray.map { val o = it.jsonObject; ChatMessage(o["role"]!!.jsonPrimitive.content, o["content"]!!.jsonPrimitive.content) }

    // ================================================================= client operations
    private fun connect(host: String, port: Int, timeoutMs: Int = 4000): Socket = Socket().apply {
        connect(InetSocketAddress(host, port), timeoutMs); soTimeout = 120_000; tcpNoDelay = true
    }

    suspend fun pair(host: String, port: Int = settings.int(Settings.SYNC_PORT)): SyncResult = withContext(Dispatchers.IO) {
        try {
            connect(host, port).use { s ->
                val sess = handshake(s, client = true, pairMode = true)
                engine.markSeen(sess.peerId, host)
                val r = runSync(sess)
                sess.send(buildJsonObject { put("t", "bye") })
                r
            }
        } catch (e: Exception) { SyncResult(false, null, 0, 0, 0, e.message ?: e::class.simpleName) }
    }

    /** Full bidirectional sync with a paired peer reachable at host:port. */
    suspend fun syncWith(host: String, port: Int = settings.int(Settings.SYNC_PORT)): SyncResult = withContext(Dispatchers.IO) {
        if (!settings.bool(Settings.SYNC_ENABLED)) return@withContext SyncResult(false, null, 0, 0, 0, "Synchronisation is turned off.")
        try {
            connect(host, port).use { s ->
                val sess = handshake(s, client = true, pairMode = false)
                engine.markSeen(sess.peerId, host, sess.peerName, sess.peerRole)
                val r = runSync(sess)
                sess.send(buildJsonObject { put("t", "bye") })
                r
            }
        } catch (e: Exception) {
            diag?.w("sync", "sync with $host failed: ${e.message}")
            SyncResult(false, null, 0, 0, 0, e.message ?: e::class.simpleName)
        }
    }

    private fun runSync(sess: Session): SyncResult {
        val t0 = store.clock.now()
        val hist = engine.startHistory(sess.peerName)
        return try {
            sess.send(buildJsonObject { put("t", "sync") })
            val sent = sess.pushChanges()
            val st = SyncEngine.ApplyStats()
            val got = sess.pullChanges(st)
            engine.completeSync(sess.peerId)
            engine.finishHistory(hist, sent, got, st.conflicts, null)
            SyncResult(true, sess.peerId, sent, got, st.conflicts, durationMs = store.clock.now() - t0)
        } catch (e: Exception) {
            engine.finishHistory(hist, 0, 0, 0, e.message)
            throw e
        }
    }

    /** Tries each candidate address of a paired peer; returns the first successful sync. */
    suspend fun syncAny(candidates: List<String>): SyncResult {
        var last = SyncResult(false, null, 0, 0, 0, "no reachable paired device")
        for (c in candidates.distinct()) {
            val (h, p) = splitHostPort(c, settings.int(Settings.SYNC_PORT))
            last = syncWith(h, p)
            if (last.ok) return last
        }
        return last
    }

    fun remoteEngine(host: String, port: Int = settings.int(Settings.SYNC_PORT)) = RemoteEngine(host, port)

    /**
     * LlmEngine backed by the paired home device. Keeps one authenticated connection for requests.
     */
    inner class RemoteEngine(private val host: String, private val port: Int) : LlmEngine {
        override val id = "home:$host"
        override val source = "home_device"
        private var sess: Session? = null
        private var ctx = 0
        @Volatile private var cancelFlag = false
        @Volatile private var currentId: String? = null

        @Synchronized
        private fun session(): Session {
            sess?.let { if (!it.socket.isClosed) return it }
            val s = connect(host, port, 2500)
            val ss = runBlocking { handshake(s, client = true, pairMode = false) }
            sess = ss
            return ss
        }

        /** Connects and checks the peer has a model loaded. */
        fun probe(): Boolean = try {
            val s = session()
            s.send(buildJsonObject { put("t", "ctx") })
            val r = s.recv()
            ctx = r["size"]?.jsonPrimitive?.int ?: 0
            r["ready"]?.jsonPrimitive?.booleanOrNull == true
        } catch (e: Exception) { close(); false }

        override fun isReady() = ctx > 0

        override fun countTokens(messages: List<ChatMessage>): Int = synchronized(this) {
            val s = session()
            s.send(buildJsonObject { put("t", "count"); put("messages", msgs(messages)) })
            s.recv()["n"]?.jsonPrimitive?.int?.takeIf { it >= 0 } ?: messages.sumOf { it.content.length / 3 + 8 }
        }

        override fun contextSize() = ctx

        override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult = synchronized(this) {
            cancelFlag = false
            val s = try { session() } catch (e: Exception) { throw LlmException("home device unreachable: ${e.message}") }
            val id = newId()
            currentId = id
            try {
                s.send(buildJsonObject {
                    put("t", "infer"); put("id", id); put("messages", msgs(messages))
                    putJsonObject("params") {
                        put("maxTokens", params.maxTokens); put("temperature", params.temperature); put("topK", params.topK); put("topP", params.topP)
                        put("minP", params.minP); put("repeatPenalty", params.repeatPenalty); params.grammar?.let { put("grammar", it) }
                        putJsonArray("stop") { params.stop.forEach { add(it) } }
                    }
                })
                val out = StringBuilder()
                while (true) {
                    val m = s.recv()
                    when (m["t"]?.jsonPrimitive?.content) {
                        "chunk" -> { val t = m["text"]!!.jsonPrimitive.content; out.append(t); if (!cancelFlag) onText(t) }
                        "result" -> return GenResult(out.toString(), CluJson.decodeFromJsonElement(GenStats.serializer(), m["stats"]!!).let {
                            if (cancelFlag) it.copy(stopReason = "cancelled") else it }, source)
                        "error" -> throw LlmException(m["reason"]?.jsonPrimitive?.content ?: "remote error")
                        else -> throw LlmException("unexpected remote message")
                    }
                }
                @Suppress("UNREACHABLE_CODE") throw LlmException("unreachable")
            } catch (e: IOException) { close(); throw LlmException("connection to home device lost: ${e.message}") }
        }

        /** Stops forwarding text immediately and asks the home device to abort generation over a second connection. */
        override fun cancel() {
            cancelFlag = true
            val id = currentId ?: return
            scope.launch {
                runCatching {
                    connect(host, port, 2500).use { s ->
                        val c = handshake(s, client = true, pairMode = false)
                        c.send(buildJsonObject { put("t", "cancel"); put("id", id) }); c.recv(); c.send(buildJsonObject { put("t", "bye") })
                    }
                }
            }
        }

        fun close() { runCatching { sess?.socket?.close() }; sess = null; ctx = 0 }

        private fun msgs(list: List<ChatMessage>) = buildJsonArray { list.forEach { m -> addJsonObject { put("role", m.role); put("content", m.content) } } }
    }

    fun shutdown() { stopServer(); scope.cancel() }

    companion object {
        fun splitHostPort(s: String, defaultPort: Int): Pair<String, Int> {
            val t = s.trim()
            if (t.startsWith("[")) { val end = t.indexOf(']'); val host = t.substring(1, end); val port = t.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort; return host to port }
            val parts = t.split(":")
            return if (parts.size == 2) parts[0] to (parts[1].toIntOrNull() ?: defaultPort) else t to defaultPort
        }
    }
}

/** Builds candidate addresses for reaching the paired peer, in priority order. */
class PeerLocator(private val settings: SettingsStore, private val engine: SyncEngine,
                  private val discovered: () -> List<String>, private val gateway: () -> String?) {
    fun candidates(): List<String> {
        val out = mutableListOf<String>()
        settings.string(Settings.SYNC_ADDRESS).takeIf { it.isNotBlank() }?.let { out += it }
        if (settings.string(Settings.SYNC_DISCOVERY) == "auto") {
            out += discovered()
            engine.peers().mapNotNull { it.lastAddress }.forEach { out += it }
            gateway()?.let { out += it } // On a phone hotspot the phone is the tablet's gateway.
        }
        return out.distinct()
    }
}
