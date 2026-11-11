package ai.clu.core.api

import kotlinx.serialization.json.*

/**
 * One JSON API request, independent of the transport: the PC answers it from the JDK HTTP server (desktop `Call`),
 * the tablet and phone from [LoopbackServer] ([ExchangeCall]). Route groups written against this run unchanged on both
 * platforms (shared, not copied): the archive, the memory views, the shell's chat API.
 */
abstract class ApiCall(val path: String, val method: String, val body: JsonObject) {
    abstract fun send(code: Int, body: JsonElement)
    abstract fun header(name: String): String?
    protected abstract val rawQuery: String?

    fun ok(body: JsonElement = buildJsonObject { put("ok", true) }) = send(200, body)
    fun fail(code: Int, msg: String) = send(code, buildJsonObject { put("error", msg) })
    fun str(key: String) = body[key]?.jsonPrimitive?.contentOrNull
    fun strings(key: String): List<String> = when (val v = body[key]) {
        is JsonArray -> v.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.filter { it.isNotEmpty() }
        is JsonPrimitive -> v.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        else -> emptyList()
    }
    fun query(key: String): String? = rawQuery?.split('&')?.map { it.split('=', limit = 2) }
        ?.firstOrNull { it[0] == key }?.getOrNull(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
}

/** An [ApiCall] answered over [LoopbackServer] (Android). */
class ExchangeCall(val ex: Exchange, path: String = ex.path.trimEnd('/').ifEmpty { "/" }) : ApiCall(path, ex.method,
    runCatching { Json.parseToJsonElement(String(ex.body)).jsonObject }.getOrDefault(JsonObject(emptyMap()))) {
    override fun send(code: Int, body: JsonElement) = ex.json(code, body.toString())
    override fun header(name: String) = ex.header(name)
    override val rawQuery get() = ex.rawQuery
}

/** Background memory maintenance, where the platform runs it (the PC's scheduler); absent elsewhere. */
interface MaintenanceHost {
    data class Run(val at: Long, val ran: Boolean, val summary: String)
    val scheduled: Boolean
    val lastRun: Run?
    fun busyReason(): String?
    fun runNow(): Run
}

/** Database backups, where the platform keeps them (the PC's data folder); absent elsewhere. */
interface BackupHost {
    data class Backup(val name: String, val sizeBytes: Long, val createdAt: Long, val kind: String)
    val folder: String
    fun list(): List<Backup>
    fun create(): Backup
}
