package ai.clu.core.search

import ai.clu.core.tools.HttpResponse
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.async
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A deliberately small HTTP/1.1 GET client for reading web pages, which connects to one **already validated IP address**
 * instead of resolving the host name itself. That closes the gap where a client re-resolves a name after it was checked
 * (DNS rebinding): the address that was checked is the address that is used.
 *
 * - HTTPS keeps full verification: the platform's trusted certificates, SNI with the real host name, and host-name
 *   verification (`endpointIdentificationAlgorithm = HTTPS`, plus an optional platform [hostnameVerifier]).
 * - Never follows redirects (the caller re-validates each hop), asks for an uncompressed body, sends `Connection: close`.
 * - Bounded: [timeoutMs] per connect/read, a total deadline of 3 × [timeoutMs], 32 KB of headers, at most maxBytes of body.
 * - Standard JDK sockets only (the same APIs on Android); no extra dependency.
 */
class PinnedHttpClient(
    private val sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val hostnameVerifier: ((String, SSLSession) -> Boolean)? = null,
    private val connect: (InetAddress, Int, Int) -> Socket = DIRECT,
) {
    class PinnedHttpException(message: String) : IOException(message)

    /**
     * [get] on the IO dispatcher, cancellable: cancelling the coroutine closes the socket at once (a blocked read can't
     * be interrupted otherwise), so a stopped request releases its connection instead of waiting for a timeout.
     */
    suspend fun fetch(url: String, address: String, headers: Map<String, String>, maxBytes: Int, timeoutMs: Int): HttpResponse = kotlinx.coroutines.coroutineScope {
        val open = java.util.concurrent.atomic.AtomicReference<Socket?>()
        val call = async(kotlinx.coroutines.Dispatchers.IO) { get(url, address, headers, maxBytes, timeoutMs) { open.set(it) } }
        try { call.await() } catch (e: kotlinx.coroutines.CancellationException) { runCatching { open.get()?.close() }; throw e }
    }

    fun get(url: String, address: String, headers: Map<String, String>, maxBytes: Int, timeoutMs: Int, onSocket: (Socket) -> Unit = {}): HttpResponse {
        val u = URI(url)
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw PinnedHttpException("unsupported scheme")
        if (u.userInfo != null) throw PinnedHttpException("credentials in URL")
        val host = u.host?.trim('[', ']') ?: throw PinnedHttpException("no host")
        // Defence in depth: the caller validated it, but never connect to a non-public address from here.
        if (!SafeAddress.publicIp(address)) throw PinnedHttpException("address is not public")
        val ip = InetAddress.getByAddress(host, InetAddress.getByName(address).address) // literal: no DNS lookup
        val port = if (u.port > 0) u.port else if (scheme == "https") 443 else 80
        val deadline = System.nanoTime() + 3L * timeoutMs * 1_000_000
        var socket: Socket = connect(ip, port, timeoutMs)
        onSocket(socket)
        try {
            socket.soTimeout = timeoutMs
            if (scheme == "https") {
                val ssl = sslFactory.createSocket(socket, host, port, true) as SSLSocket
                socket = ssl
                ssl.sslParameters = ssl.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    if (!host.contains(':') && !Regex("^[0-9.]+$").matches(host)) serverNames = listOf(SNIHostName(host))
                }
                ssl.startHandshake()
                if (hostnameVerifier != null && !hostnameVerifier.invoke(host, ssl.session)) throw SSLPeerUnverifiedException("certificate does not match $host")
            }
            val path = (u.rawPath?.ifEmpty { null } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
            val hostHeader = (if (host.contains(':')) "[$host]" else host) + if (u.port > 0) ":${u.port}" else ""
            val req = StringBuilder("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nAccept-Encoding: identity\r\nConnection: close\r\n")
            headers.forEach { (k, v) -> if (k.none { it == '\r' || it == '\n' || it == ':' } && v.none { it == '\r' || it == '\n' }) req.append("$k: $v\r\n") }
            req.append("\r\n")
            socket.getOutputStream().apply { write(req.toString().toByteArray(Charsets.ISO_8859_1)); flush() }
            val input = BufferedInputStream(socket.getInputStream())
            val status = readLine(input, deadline) ?: throw PinnedHttpException("empty response")
            val code = Regex("^HTTP/1\\.[01] (\\d{3})").find(status)?.groupValues?.get(1)?.toInt() ?: throw PinnedHttpException("malformed status line")
            val hdrs = HashMap<String, String>()
            var headerBytes = status.length
            while (true) {
                val line = readLine(input, deadline) ?: break
                if (line.isEmpty()) break
                headerBytes += line.length
                if (headerBytes > MAX_HEADER_BYTES) throw PinnedHttpException("headers too large")
                val i = line.indexOf(':'); if (i <= 0) continue
                hdrs.putIfAbsent(line.substring(0, i).trim().lowercase(), line.substring(i + 1).trim())
            }
            val body = when {
                hdrs["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> readChunked(input, maxBytes, deadline)
                hdrs["content-length"]?.toLongOrNull() != null -> readUpTo(input, minOf(hdrs["content-length"]!!.toLong(), maxBytes.toLong()).toInt(), deadline)
                else -> readUpTo(input, maxBytes, deadline)
            }
            val charset = Regex("(?i)charset=\"?([\\w.:-]+)").find(hdrs["content-type"].orEmpty())?.groupValues?.get(1)
                ?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
            return HttpResponse(code, String(body, charset), hdrs.filterKeys { it in setOf("content-type", "location", "last-modified") })
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun check(deadline: Long) { if (System.nanoTime() > deadline) throw SocketTimeoutException("page took too long") }

    private fun readLine(input: InputStream, deadline: Long): String? {
        val sb = StringBuilder()
        while (true) {
            check(deadline)
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) throw PinnedHttpException("header line too long")
        }
    }

    private fun readUpTo(input: InputStream, max: Int, deadline: Long): ByteArray {
        val out = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            check(deadline)
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream, max: Int, deadline: Long): ByteArray {
        val out = ByteArrayOutputStream()
        while (out.size() < max) {
            val sizeLine = readLine(input, deadline) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw PinnedHttpException("malformed chunk")
            if (size == 0) break
            if (size < 0) throw PinnedHttpException("malformed chunk")
            val take = minOf(size, max - out.size())
            out.write(readUpTo(input, take, deadline))
            if (take < size) break // limit reached: stop reading
            readLine(input, deadline) // CRLF after the chunk
        }
        return out.toByteArray()
    }

    companion object {
        /** Direct connection: no system or SOCKS proxy may route the request somewhere other than the validated address. */
        val DIRECT: (InetAddress, Int, Int) -> Socket = { ip, port, t -> Socket(java.net.Proxy.NO_PROXY).apply { connect(InetSocketAddress(ip, port), t) } }
        const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_LINE = 8 * 1024
    }
}
