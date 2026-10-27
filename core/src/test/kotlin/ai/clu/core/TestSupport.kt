package ai.clu.core

import ai.clu.core.db.SqlDriver
import ai.clu.core.db.SqlRow
import ai.clu.core.llm.*
import ai.clu.core.sync.KeyVault
import ai.clu.core.sync.PlainKeyVault
import ai.clu.core.tools.HttpFetcher
import ai.clu.core.tools.HttpResponse
import ai.clu.core.util.ManualClock
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** JDBC SQLite driver: executes exactly the same SQL the Android driver executes. */
class JdbcDriver(val path: String) : SqlDriver {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private var depth = 0

    private fun bind(st: java.sql.PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.setNull(i + 1, java.sql.Types.NULL)
                is String -> st.setString(i + 1, a)
                is Long -> st.setLong(i + 1, a)
                is Int -> st.setLong(i + 1, a.toLong())
                is Double -> st.setDouble(i + 1, a)
                is Float -> st.setDouble(i + 1, a.toDouble())
                is Boolean -> st.setLong(i + 1, if (a) 1 else 0)
                is ByteArray -> st.setBytes(i + 1, a)
                else -> error("unsupported arg ${a::class}")
            }
        }
    }

    @Synchronized override fun execute(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { st -> bind(st, args); st.execute() }
    }

    @Synchronized override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs ->
                val row = Row(rs); val out = mutableListOf<T>()
                while (rs.next()) out += map(row)
                out
            }
        }

    @Synchronized override fun <T> transaction(block: () -> T): T {
        if (depth == 0) conn.autoCommit = false
        depth++
        try {
            val r = block()
            depth--
            if (depth == 0) { conn.commit(); conn.autoCommit = true }
            return r
        } catch (t: Throwable) {
            depth--
            if (depth == 0) { conn.rollback(); conn.autoCommit = true }
            throw t
        }
    }

    override var userVersion: Int
        get() = query("PRAGMA user_version") { it.long(0)!!.toInt() }.first()
        set(v) = execute("PRAGMA user_version = $v")

    override fun close() = conn.close()

    private class Row(val rs: ResultSet) : SqlRow {
        override fun string(i: Int): String? = rs.getString(i + 1)
        override fun long(i: Int): Long? = rs.getLong(i + 1).takeUnless { rs.wasNull() }
        override fun double(i: Int): Double? = rs.getDouble(i + 1).takeUnless { rs.wasNull() }
        override fun blob(i: Int): ByteArray? = rs.getBytes(i + 1)
        override fun isNull(i: Int): Boolean { rs.getObject(i + 1); return rs.wasNull() }
    }
}

class FakeHttp : HttpFetcher {
    /** Every request URL in order (thread-safe: searches fetch in parallel). */
    val requests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val routes: MutableMap<String, HttpResponse> = java.util.concurrent.ConcurrentHashMap()
    /** Headers of GET requests, in order. */
    val headers: MutableList<Map<String, String>> = java.util.Collections.synchronizedList(mutableListOf())
    /** When set, every request fails with this exception (e.g. no network). */
    @Volatile var failWith: Exception? = null
    /** Simulated latency per request. */
    @Volatile var delayMs = 0L
    /** The most specific route whose key is part of the URL (longest key wins). */
    private fun route(url: String) = routes.entries.filter { url.contains(it.key) }.maxByOrNull { it.key.length }?.value ?: HttpResponse(404, "")
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        requests += url; this.headers += headers
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        failWith?.let { throw it }
        return route(url)
    }
    val postHeaders: MutableList<Map<String, String>> = java.util.Collections.synchronizedList(mutableListOf())
    /** Host → addresses for [resolve]; hosts not listed resolve to a public test address. */
    val dns: MutableMap<String, List<String>> = java.util.concurrent.ConcurrentHashMap()
    override suspend fun resolve(host: String): List<String> = dns[host] ?: listOf("93.184.216.34")
    override suspend fun postJson(url: String, json: String, headers: Map<String, String>): HttpResponse {
        requests += "POST $url $json"
        postHeaders += headers
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        failWith?.let { throw it }
        return route(url)
    }
}

class TestLlmPlatform(var available: Long = Long.MAX_VALUE, var thermal: Boolean = false) : LlmPlatform {
    var loads = 0
    override fun loadNativeLibrary() {
        loads++
        val lib = File(System.getProperty("clu.nativeLib", "../native/build/host/libclu_llama.so"))
        LlamaBridge.load(lib.absolutePath)
    }
    override fun autoThreads() = 2
    override fun thermalSevere() = thermal
    override fun availableMemoryBytes() = available
}

class TestPlatform(val fakeHttp: FakeHttp = FakeHttp(), override val llm: TestLlmPlatform = TestLlmPlatform()) : CluPlatform {
    var online = true
    var name = "Test device"
    val secrets = mutableMapOf<String, String>()
    override val keyVault: KeyVault = PlainKeyVault
    override fun isOnline() = online
    override fun deviceName() = name
    override fun secret(name: String) = secrets[name]
    override val http: HttpFetcher get() = fakeHttp
}

/** Deterministic engine: returns scripted replies and records every prompt it receives. */
class ScriptedEngine(private val replies: ArrayDeque<String> = ArrayDeque(), val ctx: Int = 4096) : LlmEngine {
    override val id = "scripted"
    override val source = "local"
    val prompts = mutableListOf<List<ChatMessage>>()
    val params = mutableListOf<GenParams>()
    var cancelled = false
    fun reply(vararg r: String) = apply { replies.addAll(r) }
    override fun isReady() = true
    override fun countTokens(messages: List<ChatMessage>) = messages.sumOf { it.content.length / 4 + 4 }
    override fun contextSize() = ctx
    override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult {
        prompts += messages; this.params += params
        var text = replies.removeFirstOrNull() ?: "OK."
        for (stop in params.stop) { val i = text.indexOf(stop); if (i >= 0) text = text.substring(0, i) }
        text.chunked(5).forEach(onText)
        return GenResult(text, GenStats(promptTokens = 10, generatedTokens = text.length / 4, promptMs = 5.0, generateMs = 10.0, firstTokenMs = 6.0, stopReason = "eog"), source)
    }
    override fun cancel() { cancelled = true }
    /** Instructions the model saw: the system prompt plus the per-message [Context] block on the newest user message. */
    /** The newest user message as the owner wrote it (without the [Context] block). */
    val lastUserText get() = prompts.last().last { it.role == "user" }.content.substringAfter("[/Context]\n")
    val lastSystem get() = prompts.last().let { p -> p.first { it.role == "system" }.content + "\n" + (p.lastOrNull { it.role == "user" && it.content.startsWith("[Context]") }?.content?.substringBefore("[/Context]") ?: "") }
}

fun tempDb(): JdbcDriver = JdbcDriver(Files.createTempFile("clu", ".db").toFile().absolutePath)

fun newCore(db: SqlDriver = tempDb(), platform: TestPlatform = TestPlatform(), clock: ManualClock = ManualClock(1_760_000_000_000L)): CluCore =
    CluCore(db, platform, clock)

/** Replaces the inference router's local path with a scripted engine. */
fun CluCore.useEngine(engine: LlmEngine?) { remoteEngineProvider = { engine }; settings.set(ai.clu.core.config.Settings.REMOTE_INFERENCE, "prefer_home") }
