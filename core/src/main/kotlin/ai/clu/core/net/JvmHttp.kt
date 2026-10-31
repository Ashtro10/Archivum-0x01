package ai.clu.core.net

import ai.clu.core.tools.HttpFetcher
import ai.clu.core.tools.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/*
 * The HTTP client for every JVM platform (Android and the desktop host): plain java.net, no platform APIs.
 * Moved unchanged from the Android app (formerly AndroidHttp) so both platforms share one implementation.
 */
private val CREDENTIAL_HEADER = Regex("(?i)^(authorization|proxy-authorization|cookie|x-subscription-token|x-api-key|api-key)$|token|secret|key")

class JvmHttp : HttpFetcher {
    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            // A request carrying a credential (API key, token) never follows a redirect: the client would forward the
            // header to whatever host the redirect names. The redirect is returned as-is (reported as an HTTP error).
            if (headers.keys.any { CREDENTIAL_HEADER.containsMatchIn(it) }) instanceFollowRedirects = false
        }

    private fun read(c: HttpURLConnection, maxBytes: Int = 2_000_000): HttpResponse {
        val code = c.responseCode
        val stream = if (code in 200..399) c.inputStream else c.errorStream
        // Bounded read: never holds more than maxBytes of a response in memory, however large the page is.
        val body = stream?.use { s ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
            while (out.size() < maxBytes) { val n = s.read(buf, 0, minOf(buf.size, maxBytes - out.size())); if (n < 0) break; out.write(buf, 0, n) }
            out.toString(Charsets.UTF_8.name())
        } ?: ""
        val h = listOf("content-type", "location", "last-modified").mapNotNull { k -> c.getHeaderField(k)?.let { k to it } }.toMap()
        return HttpResponse(code, body, h)
    }

    override suspend fun resolve(host: String): List<String> = withContext(Dispatchers.IO) { java.net.InetAddress.getAllByName(host).mapNotNull { it.hostAddress } }

    /** Pages are fetched from the validated address only (no second DNS lookup); TLS keeps the platform's verification. */
    private val pinned = ai.clu.core.search.PinnedHttpClient(hostnameVerifier = { host, session ->
        javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session) })

    override suspend fun fetchPage(url: String, headers: Map<String, String>, maxBytes: Int, timeoutMs: Int, address: String) =
        pinned.fetch(url, address, headers, maxBytes, timeoutMs)

    override suspend fun get(url: String, headers: Map<String, String>) = withContext(Dispatchers.IO) {
        val c = open(url, headers); try { read(c) } finally { c.disconnect() }
    }

    override suspend fun postJson(url: String, json: String, headers: Map<String, String>) = withContext(Dispatchers.IO) {
        val c = open(url, headers)
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray()) }
            read(c)
        } finally { c.disconnect() }
    }
}
