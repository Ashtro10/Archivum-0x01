package ai.clu.desktop.voice

import ai.clu.core.CluCore
import ai.clu.core.db.str
import ai.clu.desktop.DesktopPaths
import ai.clu.desktop.OwnerOnly
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** The speech engines, loaded when first needed and unloaded when voice has been idle (they hold a few hundred MB). */
class VoiceEngines(val models: SpeechModels, private val settings: () -> VoiceSettings, private val log: (String) -> Unit = {}) {
    private var kroko: StreamingRecognizerModel? = null
    private var whisper: Pair<String, WhisperModel>? = null
    private val voices = LinkedHashMap<String, TtsVoice>()
    @Volatile var lastUse = System.currentTimeMillis(); private set

    fun vad(minSilence: Float): Vad = Vad(models.vad ?: throw IllegalStateException("The voice activity model is missing; reinstall CLU."), minSilence = minSilence).also { lastUse = System.currentTimeMillis() }

    @Synchronized fun recognizer(): SpeechRecognizer {
        lastUse = System.currentTimeMillis()
        val lang = settings().language
        if (lang != "en") models.whisper?.let { dir ->
            val code = if (lang == "auto") "" else lang
            val w = whisper?.takeIf { it.first == code }?.second ?: WhisperModel(dir, code).also { whisper?.second?.close(); whisper = code to it; log("voice: Whisper loaded ($lang)") }
            return w.session()
        }
        val k = kroko ?: StreamingRecognizerModel(models.kroko ?: throw IllegalStateException("The speech recognition model is missing; reinstall CLU.")).also { kroko = it; log("voice: Kroko streaming recogniser loaded") }
        return k.session()
    }

    /** The loaded voice for [id] and the speaker to use in it. At most two packs stay loaded (the chosen one and a preview). */
    @Synchronized fun voice(id: String): Pair<TtsVoice, Int> {
        lastUse = System.currentTimeMillis()
        val opt = VoiceCatalog.voice(id) ?: VoiceCatalog.voice(VoiceCatalog.DEFAULT_VOICE)!!
        val pack = VoiceCatalog.pack(opt.pack)!!
        val loaded = voices.remove(pack.id) ?: run {
            val dir = models.packDir(pack.id) ?: throw IllegalStateException("The voice ${opt.name} is not installed. Download it in Settings → Voice.")
            TtsVoice(pack, dir, models.espeak()).also { log("voice: loaded ${pack.title} (${it.speakers} speaker(s), ${it.sampleRate} Hz)") }
        }
        voices[pack.id] = loaded
        while (voices.size > 2) { val (k, v) = voices.entries.first(); voices.remove(k); v.close() }
        return loaded to opt.speaker
    }

    /** Replaces voices that have synthesised more than [limit] times with fresh instances (between turns), so their memory stays bounded. */
    @Synchronized fun recycleWorn(limit: Int = 120) {
        for ((id, v) in voices.entries.toList()) {
            if (v.uses <= limit) continue
            val dir = models.packDir(id) ?: continue
            val fresh = runCatching { TtsVoice(v.pack, dir, models.espeak()) }.getOrNull() ?: continue
            voices[id] = fresh; v.close(); log("voice: ${v.pack.title} reloaded after ${v.uses} uses (keeps its memory bounded)")
        }
    }

    @Synchronized fun unload() {
        kroko?.close(); kroko = null; whisper?.second?.close(); whisper = null
        voices.values.forEach { it.close() }; voices.clear()
    }
    @Synchronized fun loadedSummary() = buildJsonObject { put("recognizer", kroko?.name ?: whisper?.second?.name ?: ""); putJsonArray("voices") { voices.keys.forEach { add(it) } } }
}

/**
 * CLU's voice on the PC: one place that owns the LiveKit server, the speech engines, the voice settings, the paired phones
 * and the live voice sessions (one per device, all on the same conversation history).
 */
class VoiceService(private val core: CluCore, private val paths: DesktopPaths, private val log: (String) -> Unit = {}) {
    val dir = File(paths.root, "voice").apply { mkdirs() }
    val store = VoiceSettingsStore(core.db)
    val settings get() = store.get()
    val models = SpeechModels(SpeechModels.locate(), dir)
    val engines = VoiceEngines(models, { settings }, log)
    val server = LiveKitServer(dir, NativeVoice.folder()?.let { File(it, if (isWindows()) "livekit-server.exe" else "livekit-server") }, log)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile private var lastActivity = System.currentTimeMillis()
    private var lan: HttpServer? = null
    private val badKeys = ConcurrentHashMap<String, MutableList<Long>>()

    class Session(val room: String, val device: String, val agent: VoiceAgent, val startedAt: Long)

    init {
        scope.launch { while (isActive) { delay(30_000); runCatching { housekeeping() } } }
    }

    /** Why voice cannot work on this PC right now, or null. */
    fun unavailable(): String? = when {
        !NativeVoice.load() -> NativeVoice.loadError
        models.vad == null || models.kroko == null -> "The speech models are not installed. Reinstall CLU."
        models.packDir(VoiceCatalog.voice(settings.voice)?.pack ?: "") == null -> "The selected voice is not installed. Choose another one in Settings → Voice."
        else -> null
    }

    // ------------------------------------------------------------------------------------------------ sessions
    /** Starts a voice session for this PC's window: CLU joins a fresh room and the window gets a token for it. */
    suspend fun startDesktop(conversationId: String?): JsonObject {
        unavailable()?.let { throw IllegalStateException(it) }
        if (!server.ensure(lan = settings.phoneAccess)) throw IllegalStateException(server.message)
        sessions.values.filter { it.device == "desktop" }.forEach { stop(it.room) }
        val conv = conversationId?.takeIf { id -> core.conversations.get(id)?.deleted == false } ?: latestConversation()
        val room = "clu-desktop-" + LiveKitTokens.rnd(6).lowercase().replace("-", "x").replace("_", "y")
        join(room, "desktop") { conv }
        val identity = "owner-desktop"
        return buildJsonObject { put("url", server.localUrl); put("token", server.tokens.issue(identity, room, "You", ttlSeconds = 900)); put("room", room); put("identity", identity); put("conversationId", conv)
            put("voice", settings.voice); put("voiceName", VoiceCatalog.voice(settings.voice)?.name ?: settings.voice) }
    }

    private suspend fun join(room: String, device: String, conversation: () -> String): Session {
        lastActivity = System.currentTimeMillis()
        var agentRef: VoiceAgent? = null
        val (lk, initial) = LkRoom.connect(server.localUrl, server.tokens.issue("clu", room, "CLU", ttlSeconds = 3600, agent = true), scope) { e ->
            if (e is LkEvent.Disconnected) { log("voice: room $room closed (${e.reason})"); sessions.remove(room) }
            agentRef?.onEvent(e)
        }
        val agent = VoiceAgent(core, engines, { settings }, lk, conversation, scope, log)
        agentRef = agent
        initial.forEach(agent::onEvent)
        lk.ready()
        lk.publishVoice(24_000)
        // Load the recogniser and the chosen voice before saying "listening", so the first answer does not wait for them
        // (clients show "initializing" meanwhile; audio that arrives is kept and handled once ready).
        scope.launch(Dispatchers.IO) {
            // A voice session means the owner is about to talk: load the language model and read CLU's fixed instructions
            // into its cache now, alongside the speech engines (the model loads on demand otherwise, which cost the first
            // spoken turn ~6 s of loading plus a cold prompt read in the M8.5 trace).
            val model = async { runCatching {
                val t0 = System.currentTimeMillis(); core.llm.prepare()
                val loaded = core.llm.loadIfNeeded(); val t1 = System.currentTimeMillis()
                val st = if (loaded != null) core.agent.warmUp() else null
                log("voice: model ready in ${t1 - t0} ms, instructions read in ${System.currentTimeMillis() - t1} ms (${st?.promptTokens ?: 0} tokens)")
            }.onFailure { log("voice: model warm-up failed: ${it.message}") } }
            runCatching { engines.voice(settings.voice); engines.recognizer().close() }.onFailure { log("voice: warm-up failed: ${it.message}") }
            agent.start() // listening as soon as hearing and speaking work; a turn that comes before the model is ready waits for it (shown in the trace)
        }
        val s = Session(room, device, agent, System.currentTimeMillis()); sessions[room] = s
        log("voice: CLU joined $room for $device")
        return s
    }

    suspend fun stop(room: String): Boolean { val s = sessions.remove(room) ?: return false; s.agent.close(); log("voice: session $room ended"); return true }

    fun session(room: String) = sessions[room]

    /** The conversation phones talk in: the one used most recently on any device (so speaking on the phone and typing on the PC continue each other). */
    fun latestConversation(): String = core.conversations.list().firstOrNull()?.id ?: core.conversations.create("Conversation").id

    // ------------------------------------------------------------------------------------------------ phones
    data class Device(val id: String, val name: String, val keyHash: String, val createdAt: Long, val lastSeen: Long)
    private val jsonFmt = Json { ignoreUnknownKeys = true }
    fun devices(): List<Device> = runCatching { jsonFmt.parseToJsonElement(core.db.query("SELECT value FROM kv_meta WHERE key = 'voice.devices'") { it.str(0) }.firstOrNull() ?: "[]").jsonArray.map { e -> val o = e.jsonObject
        Device(o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["keyHash"]!!.jsonPrimitive.content, o["createdAt"]!!.jsonPrimitive.long, o["lastSeen"]?.jsonPrimitive?.long ?: 0) } }.getOrDefault(emptyList())
    private fun saveDevices(l: List<Device>) = core.db.execute("INSERT INTO kv_meta(key, value) VALUES('voice.devices', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        listOf(JsonArray(l.map { d -> buildJsonObject { put("id", d.id); put("name", d.name); put("keyHash", d.keyHash); put("createdAt", d.createdAt); put("lastSeen", d.lastSeen) } }).toString()))
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Pairs a phone: returns its key once (only a hash is kept). The phone joins its own room, clu-<id>, and nothing else. */
    @Synchronized fun pair(name: String): Pair<Device, String> {
        require(devices().size < 8) { "At most 8 phones can be paired; remove one first." }
        val id = "p" + LiveKitTokens.rnd(5).lowercase().filter { it.isLetterOrDigit() }.take(6)
        val key = "$id.${LiveKitTokens.rnd(24)}"
        val d = Device(id, name.trim().take(40).ifBlank { "Phone" }, sha(key), System.currentTimeMillis(), 0)
        saveDevices(devices() + d); log("voice: paired phone ${d.name} ($id)")
        return d to key
    }
    @Synchronized fun unpair(id: String): Boolean {
        val l = devices(); if (l.none { it.id == id }) return false
        saveDevices(l.filter { it.id != id }); scope.launch { sessions.values.filter { it.device == id }.forEach { stop(it.room) } }
        log("voice: removed phone $id"); return true
    }

    /**
     * The phone's token request (LiveKit's endpoint token format, so the existing Android client works unchanged):
     * `Authorization: Bearer <device key>`, body {room_name, participant_identity, participant_name} -> {server_url, participant_token}.
     * Also makes CLU join that room (like an agent dispatch). Returns HTTP status and JSON.
     */
    suspend fun phoneToken(auth: String?, body: String, remote: String): Pair<Int, JsonObject> {
        val now = System.currentTimeMillis()
        val tries = badKeys.getOrPut(remote) { mutableListOf() }.apply { removeAll { now - it > 60_000 } }
        if (tries.size >= 5) return 429 to err("Too many wrong keys from this address; wait a minute.")
        val key = auth?.removePrefix("Bearer ")?.trim().orEmpty()
        val dev = devices().firstOrNull { it.keyHash == sha(key) } ?: run { tries += now; return 401 to err("Unknown or revoked device key.") }
        val req = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return 400 to err("Body must be JSON.")
        val room = req["room_name"]?.jsonPrimitive?.contentOrNull ?: "clu-${dev.id}"
        if (room != "clu-${dev.id}") return 403 to err("This device may only join its own room (clu-${dev.id}).")
        val identity = req["participant_identity"]?.jsonPrimitive?.contentOrNull?.filter { it.isLetterOrDigit() || it in "-_." }?.take(40)?.ifBlank { null } ?: "phone-${dev.id}"
        unavailable()?.let { return 503 to err(it) }
        if (!server.ensure(lan = true) || server.lanUrl == null) return 503 to err(if (server.lanUrl == null) "This PC has no local-network address for phones." else server.message)
        saveDevices(devices().map { if (it.id == dev.id) it.copy(lastSeen = now) else it })
        if (sessions[room] == null) runCatching { join(room, dev.id) { latestConversation() } }.onFailure { return 503 to err("CLU could not join the room: ${it.message}") }
        return 200 to buildJsonObject { put("server_url", server.lanUrl); put("participant_token", server.tokens.issue(identity, room, req["participant_name"]?.jsonPrimitive?.contentOrNull ?: dev.name, ttlSeconds = 600)) }
    }
    private fun err(m: String) = buildJsonObject { put("error", m) }

    /** The phones' token endpoint on this PC's private-network address (only while phone access is on). */
    @Synchronized fun applyPhoneAccess(port: Int = 47822): String? {
        lan?.stop(0); lan = null
        if (!settings.phoneAccess) return null
        val ip = server.detectLanAddress() ?: return "This PC has no private-network address (connect it to your Wi-Fi or the phone's hotspot)."
        return try {
            val s = HttpServer.create(InetSocketAddress(InetAddress.getByName(ip), port), 16)
            s.createContext("/token") { ex ->
                try {
                    if (ex.requestMethod != "POST") { ex.sendResponseHeaders(405, -1); return@createContext }
                    val body = ex.requestBody.use { String(it.readNBytes(8192)) }
                    val (code, json) = runBlocking { phoneToken(ex.requestHeaders.getFirst("Authorization"), body, ex.remoteAddress.address.hostAddress) }
                    val b = json.toString().toByteArray(); ex.responseHeaders.set("Content-Type", "application/json"); ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
                } catch (e: Exception) { runCatching { ex.sendResponseHeaders(500, -1) } } finally { ex.close() }
            }
            s.executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "clu-phone-token").apply { isDaemon = true } }; s.start(); lan = s
            log("voice: phone token endpoint on http://$ip:$port/token"); null
        } catch (e: IOException) { "The phone endpoint could not listen on $ip:$port (${e.message})." }
    }
    fun phoneEndpoint(): String? = lan?.address?.let { "http://${it.address.hostAddress}:${it.port}/token" }

    // ------------------------------------------------------------------------------------------------ previews and downloads
    /** Synthesises [text] with voice [id] through the real engine and returns a WAV file (what CLU will sound like). */
    fun preview(id: String, text: String?): ByteArray {
        NativeVoice.load() || throw IllegalStateException(NativeVoice.loadError)
        val s = settings; val v = engines.voice(id)
        val t = text?.take(300)?.ifBlank { null } ?: "Good evening. I am CLU. I'll keep this brief, and tell you what I find."
        val a = v.first.synth(ai.clu.core.voice.SpeechText.forSpeech(s.pronounce(t)), v.second, s.speed.toFloat())
        return Pcm.wav(Pcm.toShort(a, s.volume.toFloat()), v.first.sampleRate)
    }

    data class Download(val pack: String, @Volatile var bytes: Long = 0, @Volatile var total: Long = 0, @Volatile var state: String = "starting", @Volatile var message: String = "")
    private val downloads = ConcurrentHashMap<String, Download>()
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
    fun downloads() = downloads.values.toList()

    /** Downloads a voice pack the owner confirmed: resumable, SHA-256 checked before anything is unpacked, then registered (not selected). */
    fun download(packId: String, confirmed: Boolean): Download {
        require(confirmed) { "Downloads start only after you confirm them." }
        val p = VoiceCatalog.pack(packId) ?: throw IllegalArgumentException("no such voice")
        require(p.url != null && p.sha256 != null) { "This voice is installed with CLU." }
        if (models.installed(p.id)) return Download(p.id, state = "done", message = "Already installed.")
        downloads[p.id]?.takeIf { it.state in setOf("starting", "downloading", "verifying", "unpacking") }?.let { return it }
        val d = Download(p.id, total = p.archiveBytes).also { downloads[p.id] = it }
        scope.launch(Dispatchers.IO) { runCatching { fetch(p, d) }.onFailure { d.state = "failed"; d.message = it.message ?: "failed" } }
        return d
    }

    private fun fetch(p: VoicePack, d: Download) {
        val part = File(dir, "${p.id}.tar.bz2.part"); val have = if (part.isFile) part.length() else 0L
        if (dir.usableSpace < p.archiveBytes * 3 - have) throw IOException("Not enough free disk space for ${p.title}.")
        val b = HttpRequest.newBuilder(URI.create(p.url!!)).timeout(Duration.ofMinutes(15)).GET(); if (have > 0) b.header("Range", "bytes=$have-")
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream())
        if (r.statusCode() !in listOf(200, 206)) throw IOException("The server answered ${r.statusCode()}.")
        val append = r.statusCode() == 206; d.state = "downloading"; d.bytes = if (append) have else 0
        java.io.FileOutputStream(part, append).use { out -> r.body().use { inp -> val buf = ByteArray(1 shl 16); while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); d.bytes += n } } }
        d.state = "verifying"
        val got = MessageDigest.getInstance("SHA-256").let { md -> part.inputStream().use { i -> val buf = ByteArray(1 shl 20); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }; md.digest().joinToString("") { "%02x".format(it) } }
        if (got != p.sha256) { part.delete(); throw IOException("The download does not match its published checksum and was discarded.") }
        d.state = "unpacking"
        val target = File(dir, "voices/${p.id}"); val tmp = File(dir, "voices/${p.id}.tmp"); ai.clu.core.util.SafeDelete.tree(tmp, listOf(dir)); tmp.mkdirs()
        val espeak = File(dir, "espeak-ng-data"); val needEspeak = models.espeak() == null
        TarArchiveInputStream(BZip2CompressorInputStream(part.inputStream().buffered())).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.substringAfter('/'); val base = name.substringAfterLast('/')
                val out = when { p.keep.containsKey(base) && !name.contains("espeak") -> File(tmp, p.keep.getValue(base)); needEspeak && name.startsWith("espeak-ng-data/") -> File(espeak, name.removePrefix("espeak-ng-data/")); else -> null } ?: continue
                if (!out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue // never write outside CLU's voice folder
                out.parentFile.mkdirs(); out.outputStream().use { tar.copyTo(it) }
            }
        }
        ai.clu.core.util.SafeDelete.tree(target, listOf(dir)); if (!tmp.renameTo(target)) throw IOException("Could not move the voice into place.")
        part.delete(); d.state = "done"; d.message = "Installed. Choose it as CLU's voice when you are ready."
        log("voice: installed ${p.title}")
    }

    // ------------------------------------------------------------------------------------------------ status and upkeep
    fun status() = buildJsonObject {
        val s = settings
        put("available", unavailable() == null); unavailable()?.let { put("problem", it) }
        put("server", server.state.name); put("serverMessage", server.message); put("localUrl", server.localUrl); server.lanUrl?.let { put("lanUrl", it) }
        phoneEndpoint()?.let { put("phoneEndpoint", it) }
        put("engines", engines.loadedSummary())
        putJsonArray("sessions") { sessions.values.forEach { x -> addJsonObject { put("room", x.room); put("device", x.device); put("phase", x.agent.phase.wire); put("listener", x.agent.listener ?: ""); put("startedAt", x.startedAt); put("error", x.agent.lastError)
            putJsonArray("timings") { synchronized(x.agent.timings) { x.agent.timings.toList() }.takeLast(10).forEach { t -> addJsonObject { put("at", t.at); put("speechEndToFirstAudioMs", t.speechEndToFirstAudioMs); put("sttFinalizeMs", t.sttFinalizeMs); put("firstTokenMs", t.firstTokenMs); put("ttsFirstMs", t.ttsFirstMs); put("interrupted", t.interrupted)
                put("id", t.id); put("outcome", t.outcome); put("heard", t.heard); put("reply", t.reply)
                putJsonArray("stages") { t.stages.forEach { st -> addJsonObject { put("name", st.name); put("ms", st.ms); if (st.detail.isNotEmpty()) put("detail", st.detail) } } } } } } } } }
        put("voice", s.voice); put("mode", s.mode.name.lowercase())
        put("recognizer", if (s.language != "en" && models.whisper != null) "Whisper base (${s.language})" else "Kroko streaming English")
        put("whisperInstalled", models.whisper != null)
    }

    private suspend fun housekeeping() {
        val now = System.currentTimeMillis()
        if (sessions.isNotEmpty()) { lastActivity = now; core.llm.prepare() } // keeps the model loaded (idle timer) while a voice session is open
        if (sessions.isEmpty() && now - lastActivity > 5 * 60_000) engines.unload()
        if (sessions.isEmpty() && lan == null && now - lastActivity > 10 * 60_000 && server.state == LiveKitServer.State.RUNNING) { server.stop(); log("voice: LiveKit server stopped (idle)") }
    }

    /** Memory of the LiveKit server process (bytes) for the resource governor; 0 when it is not running. */
    fun serverMemory(): Long {
        val pid = server.pid() ?: return 0L
        val now = System.currentTimeMillis()
        if (pid == memPid && now - memAt < 30_000) return memBytes
        memBytes = runCatching {
            if (isWindows()) { // tasklist reports the working set ("12,345 K"); cached for 30 s so the governor's tick stays cheap
                val p = ProcessBuilder("tasklist", "/FI", "PID eq $pid", "/FO", "CSV", "/NH").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText(); p.waitFor()
                out.trim().split("\",\"").last().filter(Char::isDigit).toLong() * 1024
            } else File("/proc/$pid/status").readLines().first { it.startsWith("VmRSS:") }.filter(Char::isDigit).toLong() * 1024
        }.getOrDefault(0L)
        memPid = pid; memAt = now
        return memBytes
    }
    @Volatile private var memPid = -1L; @Volatile private var memAt = 0L; @Volatile private var memBytes = 0L

    fun shutdown() {
        runBlocking { withTimeoutOrNull(5000) { sessions.keys.toList().forEach { stop(it) } } }
        lan?.stop(0); server.stop(); engines.unload(); scope.cancel()
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")
}
