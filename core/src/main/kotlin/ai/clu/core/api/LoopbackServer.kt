package ai.clu.core.api

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A minimal HTTP/1.1 server on 127.0.0.1 for CLU's own web interface on Android (the PC uses the JDK's server): the
 * same shell the PC window shows is loaded from it by a WebView, so its fetch calls, request bodies and streamed
 * replies (server-sent events) work unchanged. One request per connection; bodies need Content-Length (fetch always
 * sends it); limits on header and body size. Never listens on any other interface. Pure JVM: tested on the build host.
 */
class LoopbackServer(private val maxBody: Int = 50 * 1024 * 1024, private val handler: (Exchange) -> Unit) {
    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null
    @Volatile private var running = false

    val port: Int get() = socket?.localPort ?: -1

    @Synchronized fun start(port: Int = 0): Int {
        if (running) return this.port
        val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 32) }
        socket = s; running = true
        val p = Executors.newFixedThreadPool(8) { r -> Thread(r, "clu-loopback").apply { isDaemon = true } }; pool = p
        Thread({
            while (running) {
                val c = runCatching { s.accept() }.getOrNull() ?: continue
                p.execute { serve(c) }
            }
        }, "clu-loopback-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    @Synchronized fun stop() { running = false; runCatching { socket?.close() }; pool?.shutdownNow(); socket = null; pool = null }

    private fun serve(c: Socket) {
        c.use { sock ->
            sock.soTimeout = 30_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val ex = try { parse(input, out) } catch (e: BadRequest) { Exchange.error(out, e.code, e.message ?: "bad request"); return }
                catch (e: Exception) { return }
            sock.soTimeout = 0 // a streamed reply may stay open while CLU thinks
            try { handler(ex) } catch (e: Exception) { if (!ex.committed) runCatching { ex.json(500, """{"error":"internal error"}""") } }
            finally { runCatching { out.flush() } }
        }
    }

    private class BadRequest(val code: Int, msg: String) : Exception(msg)

    private fun parse(input: InputStream, out: OutputStream): Exchange {
        val requestLine = readLine(input) ?: throw BadRequest(400, "empty request")
        val parts = requestLine.split(' ')
        if (parts.size < 3) throw BadRequest(400, "malformed request line")
        val headers = LinkedHashMap<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            total += line.length; if (total > 32 * 1024) throw BadRequest(431, "headers too large")
            val i = line.indexOf(':'); if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) throw BadRequest(411, "chunked request bodies are not supported")
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length < 0 || length > maxBody) throw BadRequest(413, "body too large")
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) { val n = input.read(body, read, body.size - read); if (n < 0) throw BadRequest(400, "body ended early"); read += n }
        val target = parts[1]
        val q = target.indexOf('?')
        return Exchange(parts[0].uppercase(), if (q >= 0) target.substring(0, q) else target, if (q >= 0) target.substring(q + 1) else null, headers, body, out)
    }

    /** One CRLF- (or LF-) terminated ASCII line, at most 8 KiB. */
    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString(Charsets.ISO_8859_1.name())
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > 8192) throw BadRequest(414, "line too long")
        }
        return b.toString(Charsets.ISO_8859_1.name())
    }
}

/** One request and its response. Answer with [respond]/[json], or [stream] for an open-ended reply (server-sent events). */
class Exchange(val method: String, val path: String, val rawQuery: String?, val headers: Map<String, String>, val body: ByteArray, private val out: OutputStream) {
    var committed = false; private set

    fun header(name: String): String? = headers[name.lowercase()]
    fun query(key: String): String? = rawQuery?.split('&')?.map { it.split('=', limit = 2) }?.firstOrNull { it[0] == key }?.getOrNull(1)
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    fun respond(code: Int, contentType: String, bytes: ByteArray, extra: Map<String, String> = emptyMap()) {
        check(!committed) { "response already started" }; committed = true
        val h = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
        for ((k, v) in extra) h.append(k).append(": ").append(v).append("\r\n")
        h.append("\r\n")
        out.write(h.toString().toByteArray(Charsets.ISO_8859_1)); out.write(bytes); out.flush()
    }

    fun json(code: Int, body: String) = respond(code, "application/json; charset=utf-8", body.toByteArray(), mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"))

    /** Starts an open-ended response (no length; the connection closes when [write] returns). */
    fun stream(code: Int, contentType: String, extra: Map<String, String> = emptyMap(), write: (OutputStream) -> Unit) {
        check(!committed) { "response already started" }; committed = true
        val h = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\nContent-Type: $contentType\r\nConnection: close\r\nCache-Control: no-store\r\n")
        for ((k, v) in extra) h.append(k).append(": ").append(v).append("\r\n")
        h.append("\r\n")
        out.write(h.toString().toByteArray(Charsets.ISO_8859_1)); out.flush()
        write(out); out.flush()
    }

    companion object {
        fun reason(code: Int) = when (code) { 200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 411 -> "Length Required"; 413 -> "Payload Too Large"; 500 -> "Internal Server Error"
            502 -> "Bad Gateway"; 503 -> "Service Unavailable"; else -> "Status" }
        internal fun error(out: OutputStream, code: Int, msg: String) = runCatching {
            val b = """{"error":${kotlinx.serialization.json.JsonPrimitive(msg)}}""".toByteArray()
            out.write("HTTP/1.1 $code ${reason(code)}\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(b); out.flush()
        }
    }
}
