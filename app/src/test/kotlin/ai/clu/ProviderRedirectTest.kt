package ai.clu

import ai.clu.platform.AndroidHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Stage 3.1: the search-provider client must not carry an API key to another host through a redirect.
 * HOST INTEGRATION on the JVM's HttpURLConnection under Robolectric (two local servers on loopback); the Android
 * runtime's own HttpURLConnection is not exercised here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderRedirectTest {
    private fun server(respond: (String) -> String): Pair<ServerSocket, MutableList<String>> {
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress()); val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        thread(isDaemon = true) { while (!ss.isClosed) { val s = runCatching { ss.accept() }.getOrNull() ?: break
            s.use { c -> val req = StringBuilder(); val inp = c.getInputStream()
                while (!req.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) break; req.append(b.toChar()) }
                seen += req.toString(); c.getOutputStream().write(respond(req.toString()).toByteArray()); c.getOutputStream().flush() } } }
        return ss to seen
    }

    @Test fun credentialHeadersAreNeverForwardedThroughRedirects() = runBlocking {
        val (other, otherSeen) = server { "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}" }
        val (provider, _) = server { "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${other.localPort}/collect\r\nContent-Length: 0\r\n\r\n" }
        val r = AndroidHttp().get("http://127.0.0.1:${provider.localPort}/res/v1/web/search?q=x", mapOf("X-Subscription-Token" to "secret-key-123"))
        assertTrue("key not sent to the redirect target: $otherSeen", otherSeen.none { it.contains("secret-key-123") })
        assertEquals("a redirect from a keyed request is reported, not followed", 302, r.code)
        // Requests without credentials still follow ordinary redirects.
        val (plain, _) = server { "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${other.localPort}/ok\r\nContent-Length: 0\r\n\r\n" }
        assertEquals(200, AndroidHttp().get("http://127.0.0.1:${plain.localPort}/x", emptyMap()).code)
        listOf(other, provider, plain).forEach { it.close() }
    }
}
