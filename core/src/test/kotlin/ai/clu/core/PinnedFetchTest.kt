package ai.clu.core

import ai.clu.core.search.*
import ai.clu.core.tools.HttpResponse
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.*
import kotlin.concurrent.thread
import kotlin.test.*

/**
 * Stage 3.1: page fetching connects only to the address that was validated (no second DNS lookup), with full HTTPS
 * verification. HOST INTEGRATION: real TCP/TLS sockets on this machine's loopback interface — the "public" address the
 * client is pinned to is mapped to a local test server by the test's connector, which also records where the client
 * actually connected. No real website is contacted.
 */
class PinnedFetchTest {
    private val servers = mutableListOf<ServerSocket>()
    @AfterEach fun close() { servers.forEach { runCatching { it.close() } }; dir.deleteRecursively() }

    /** A one-shot-per-connection HTTP server answering with [respond] (raw bytes); records the requests it got. */
    private fun server(socket: ServerSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress()), respond: (String) -> ByteArray): Pair<Int, MutableList<String>> {
        servers += socket
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val s = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use { c ->
                        runCatching {
                            val req = StringBuilder(); val inp = c.getInputStream()
                            while (!req.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) break; req.append(b.toChar()) }
                            seen += req.toString(); c.getOutputStream().write(respond(req.toString())); c.getOutputStream().flush()
                        }
                    }
                }
            }
        }
        return socket.localPort to seen
    }

    private val connectedTo = java.util.Collections.synchronizedList(mutableListOf<String>())
    /** The test's network: records the address the client connects to and routes it to the local test server. */
    private fun connector(port: Int): (InetAddress, Int, Int) -> Socket = { ip, _, t ->
        connectedTo += ip.hostAddress
        Socket().apply { connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), t) }
    }

    private fun ok(body: String, extra: String = "") = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\n$extra\r\n$body".toByteArray()

    @Test fun connectsToTheValidatedAddressAndSendsTheRealHostName() {
        val (port, seen) = server { ok("<p>hello</p>") }
        val r = PinnedHttpClient(connect = connector(port)).get("http://page.example/a/b?q=1", "93.184.216.34", mapOf("User-Agent" to "Clu"), 1_000_000, 3000)
        assertEquals(200, r.code); assertEquals("<p>hello</p>", r.body); assertEquals("text/html; charset=utf-8", r.headers["content-type"])
        assertEquals(listOf("93.184.216.34"), connectedTo.toList(), "connected to exactly the validated address")
        assertTrue(seen.single().startsWith("GET /a/b?q=1 HTTP/1.1\r\nHost: page.example\r\n") && seen.single().contains("Connection: close"))
        assertFailsWith<PinnedHttpClient.PinnedHttpException> { PinnedHttpClient(connect = connector(port)).get("http://page.example/", "192.168.1.1", emptyMap(), 1000, 1000) }
        assertFailsWith<PinnedHttpClient.PinnedHttpException> { PinnedHttpClient(connect = connector(port)).get("http://u:p@page.example/", "93.184.216.34", emptyMap(), 1000, 1000) }
        assertFailsWith<PinnedHttpClient.PinnedHttpException> { PinnedHttpClient(connect = connector(port)).get("ftp://page.example/", "93.184.216.34", emptyMap(), 1000, 1000) }
        // Header injection through a header value is not possible.
        PinnedHttpClient(connect = connector(port)).get("http://page.example/", "93.184.216.34", mapOf("X-A" to "1\r\nX-Injected: yes"), 1000, 1000)
        assertFalse(seen.last().contains("X-Injected"))
    }

    @Test fun bodiesAreBoundedAndChunkedAndRedirectsAreNotFollowed() {
        val big = "a".repeat(3_000_000)
        val (port, _) = server { req ->
            when {
                req.startsWith("GET /big") -> ok(big)
                req.startsWith("GET /endless") -> ("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" + "b".repeat(2_500_000)).toByteArray()
                req.startsWith("GET /chunked") -> "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nHello\r\n7;ext=1\r\n, world\r\n0\r\n\r\n".toByteArray()
                req.startsWith("GET /redirect") -> "HTTP/1.1 302 Found\r\nLocation: http://192.168.1.1/admin\r\nContent-Length: 0\r\n\r\n".toByteArray()
                req.startsWith("GET /bad") -> "NOT HTTP\r\n\r\n".toByteArray()
                else -> ("HTTP/1.1 200 OK\r\n" + "X-Long: " + "h".repeat(40_000) + "\r\n\r\n").toByteArray()
            }
        }
        val c = PinnedHttpClient(connect = connector(port))
        assertEquals(1_000_000, c.get("http://p.example/big", "93.184.216.34", emptyMap(), 1_000_000, 3000).body.length)
        assertEquals(1_000_000, c.get("http://p.example/endless", "93.184.216.34", emptyMap(), 1_000_000, 3000).body.length)
        assertEquals("Hello, world", c.get("http://p.example/chunked", "93.184.216.34", emptyMap(), 1000, 3000).body)
        val red = c.get("http://p.example/redirect", "93.184.216.34", emptyMap(), 1000, 3000)
        assertEquals(302, red.code); assertEquals("http://192.168.1.1/admin", red.headers["location"]); assertEquals(1, connectedTo.count { it == "93.184.216.34" } - 3, "not followed")
        assertFailsWith<PinnedHttpClient.PinnedHttpException> { c.get("http://p.example/bad", "93.184.216.34", emptyMap(), 1000, 3000) }
        assertFailsWith<java.io.IOException> { c.get("http://p.example/hugeheader", "93.184.216.34", emptyMap(), 1000, 3000) }
    }

    @Test fun slowDripServersHitTheTotalDeadline() {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress()); servers += socket
        thread(isDaemon = true) {
            runCatching { socket.accept().use { c -> c.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray()); repeat(100) { Thread.sleep(150); c.getOutputStream().write('x'.code); c.getOutputStream().flush() } } }
        }
        val t0 = System.nanoTime()
        assertFailsWith<java.net.SocketTimeoutException> { PinnedHttpClient(connect = connector(socket.localPort)).get("http://p.example/", "93.184.216.34", emptyMap(), 1_000_000, 500) }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000, "bounded by 3 × timeout, not by the server")
    }

    // ------------------------------------------------------------------------------------------------ HTTPS

    private val dir: File = Files.createTempDirectory("clu-tls").toFile()
    private fun keystore(): File {
        val ks = File(dir, "server.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=good.example",
            "-ext", "SAN=dns:good.example", "-storetype", "PKCS12", "-keystore", ks.absolutePath, "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start()
        assertEquals(0, p.waitFor(), p.inputStream.bufferedReader().readText())
        return ks
    }

    @Test fun httpsVerifiesTheCertificateAndHostNameAndSendsSni() {
        val ks = KeyStore.getInstance("PKCS12").apply { keystore().inputStream().use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val serverCtx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val sniSeen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val ss = serverCtx.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket; servers += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() as SSLSocket }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { s.use { c ->
                    c.startHandshake()
                    (c.session as? ExtendedSSLSession)?.requestedServerNames?.forEach { sniSeen += (it as SNIHostName).asciiName }
                    val inp = c.getInputStream(); val req = StringBuilder(); while (!req.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) break; req.append(b.toChar()) }
                    c.getOutputStream().write(ok("<p>secure</p>")); c.getOutputStream().flush()
                } } }
            }
        }
        // A client that trusts only this test certificate (stands in for the platform's trusted CAs).
        val trust = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("s", ks.getCertificate("s")) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val clientFactory = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
        val good = PinnedHttpClient(sslFactory = clientFactory, connect = connector(ss.localPort)).get("https://good.example/", "93.184.216.34", emptyMap(), 1000, 3000)
        assertEquals("<p>secure</p>", good.body)
        assertEquals("good.example", sniSeen.first(), "SNI carries the real host name, not the IP")
        // The same trusted certificate presented for another name is refused (host-name verification).
        assertFailsWith<SSLException> { PinnedHttpClient(sslFactory = clientFactory, connect = connector(ss.localPort)).get("https://evil.example/", "93.184.216.34", emptyMap(), 1000, 3000) }
        // An untrusted certificate is refused with the default (system) trust.
        assertFailsWith<SSLException> { PinnedHttpClient(connect = connector(ss.localPort)).get("https://good.example/", "93.184.216.34", emptyMap(), 1000, 3000) }
        // A platform host-name verifier that says no is honoured.
        assertFailsWith<SSLPeerUnverifiedException> { PinnedHttpClient(sslFactory = clientFactory, hostnameVerifier = { _, _ -> false }, connect = connector(ss.localPort))
            .get("https://good.example/", "93.184.216.34", emptyMap(), 1000, 3000) }
    }

    // ------------------------------------------------------------------------------------------------ validation path

    @Test fun dnsChangesBetweenCheckAndConnectCannotRedirectTheRequest() = runBlocking {
        val http = FakeHttp()
        // Rebinding attack: first answer public, every later answer private.
        http.dnsAnswers["rebind.example"] = ArrayDeque(listOf(listOf("93.184.216.34"), listOf("192.168.1.1")))
        http.routes["rebind.example/page"] = HttpResponse(200, "<p>Some page text that is long enough to be a paragraph.</p>")
        val safe = SafeFetch(http)
        val (r, note) = safe.hop("https://rebind.example/page", emptyMap(), 1000, 1000)
        assertNull(note); assertEquals(200, r!!.code)
        assertEquals(listOf("https://rebind.example/page" to "93.184.216.34"), http.pinned.toList(), "the request went to the checked address")
        assertEquals(1, http.resolveCalls.count { it == "rebind.example" }, "the name was resolved once for this hop; the client never resolves it again")
        // Mixed answers (public and private) are refused outright.
        http.dns["mixed.example"] = listOf("93.184.216.34", "10.0.0.1")
        assertEquals("not a public web address", safe.hop("https://mixed.example/", emptyMap(), 1000, 1000).second)
        // Unvalidatable destinations fail safe.
        assertEquals("the site's address could not be checked", SafeFetch(object : ai.clu.core.tools.HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>) = HttpResponse(200, "x")
            override suspend fun postJson(url: String, json: String, headers: Map<String, String>) = HttpResponse(200, "x")
        }).hop("https://page.example/", emptyMap(), 1000, 1000).second)
        // A platform without a pinned client can't read pages at all (default implementation refuses).
        val noPin = object : ai.clu.core.tools.HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>) = HttpResponse(200, "<p>should never be returned by an unpinned fetch</p>")
            override suspend fun postJson(url: String, json: String, headers: Map<String, String>) = HttpResponse(200, "x")
            override suspend fun resolve(host: String) = listOf("93.184.216.34")
        }
        assertEquals("the page could not be loaded", SafeFetch(noPin).hop("https://page.example/", emptyMap(), 1000, 1000).second)
    }

    @Test fun robotsTxtCannotBeRedirectedIntoTheLocalNetwork() = runBlocking {
        val http = FakeHttp()
        http.routes["site.example/robots.txt"] = HttpResponse(301, "", mapOf("location" to "http://192.168.1.1/robots.txt"))
        http.routes["site.example/page"] = HttpResponse(200, "<p>Page text that is long enough to count as a paragraph here.</p>")
        val reader = PageReader(http, RobotsPolicy(http))
        assertEquals("the site's robots.txt doesn't allow reading it", reader.read("https://site.example/page", "page").note, "unverifiable robots.txt disallows")
        assertTrue(http.requests.none { it.contains("192.168") } && http.pinned.none { it.first.contains("192.168") })
        assertTrue(http.pinned.none { it.first == "https://site.example/page" }, "the page itself was not fetched")
        // robots.txt goes through the pinned path too.
        http.routes["ok.example/robots.txt"] = HttpResponse(200, "User-agent: *\nAllow: /")
        http.routes["ok.example/page"] = HttpResponse(200, "<p>Page text that is long enough to count as a paragraph here.</p>")
        assertNotNull(reader.read("https://ok.example/page", "page").text)
        assertTrue(http.pinned.any { it.first == "https://ok.example/robots.txt" && it.second == "93.184.216.34" })
    }

    @Test fun everySpellingOfANonPublicAddressIsRefused() {
        val refused = listOf("127.0.0.1", "10.1.2.3", "172.31.255.255", "192.168.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "255.255.255.255",
            "224.0.0.1", "240.0.0.1", "192.0.0.8", "192.0.2.10", "198.18.0.1", "198.51.100.7", "203.0.113.9", "192.88.99.1",
            "::", "::1", "fe80::1", "fec0::1", "fd00::1", "fc00::1", "ff02::1", "2001:db8::1", "2001:0:4136:e378::1",
            "::ffff:127.0.0.1", "::ffff:7f00:1", "::127.0.0.1", "64:ff9b::c0a8:101", "64:ff9b::10.0.0.1", "2002:c0a8:101::1", "2002:0a00:0001::",
            "[::1]", "::1%lo", "localhost", "example.com", "", "1.2.3", "01.2.3.4")
        refused.forEach { assertFalse(SafeAddress.publicIp(it), "should be refused: $it") }
        listOf("93.184.216.34", "8.8.8.8", "2606:4700:4700::1111", "::ffff:8.8.8.8", "64:ff9b::808:808", "2002:0808:0808::1").forEach { assertTrue(SafeAddress.publicIp(it), it) }
        listOf("http://", "http:///path", "http://exa mple.com/", "https://%31%32%37.0.0.1/", "http://example.com:99999/", "javascript:alert(1)",
            "file:///etc/hosts", "gopher://example.com/", "http://user@example.com/", "http://user:pw@example.com/", "http://[::1]/", "http://[64:ff9b::7f00:1]/",
            "http://[::ffff:10.0.0.1]/", "http://[fe80::1%25eth0]/", "http://0177.0.0.1/", "http://0x7f000001/", "http://2130706433/", "http://router/").forEach { assertFalse(SafeAddress.allowed(it), "should be refused: $it") }
    }

    @Test fun cancellingAFetchClosesItsConnectionAtOnce(): Unit = runBlocking {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress()); servers += socket
        val closedAt = java.util.concurrent.atomic.AtomicLong(0)
        thread(isDaemon = true) { runCatching { socket.accept().use { c ->
            c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n".toByteArray()); c.getOutputStream().flush()
            // Hold the connection open; reading returns -1 when the client closes it.
            runCatching { c.getInputStream().read() }; closedAt.set(System.nanoTime())
        } } }
        val client = PinnedHttpClient(connect = connector(socket.localPort))
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { client.fetch("http://slow.example/", "93.184.216.34", emptyMap(), 1_000_000, 10_000) }
        kotlinx.coroutines.delay(300)
        val t0 = System.nanoTime(); job.cancel(); job.join()
        repeat(50) { if (closedAt.get() == 0L) kotlinx.coroutines.delay(20) }
        assertTrue(closedAt.get() > 0, "the server saw the connection closed")
        assertTrue((closedAt.get() - t0) / 1_000_000 < 1000, "closed promptly, not after the 10 s timeout")
    }

    @Test fun theDefaultConnectorBypassesProxies() {
        // This machine's own non-loopback address (the JVM never proxies loopback, so it would prove nothing).
        val lan = java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address }
        org.junit.jupiter.api.Assumptions.assumeTrue(lan != null, "no non-loopback interface")
        val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("0.0.0.0"))
        val (port, seen) = server(ss) { ok("<p>direct</p>") }
        val old = System.getProperty("socksProxyHost"); val oldPort = System.getProperty("socksProxyPort")
        // An unreachable SOCKS proxy (TEST-NET address) configured for the whole JVM.
        System.setProperty("socksProxyHost", "203.0.113.1"); System.setProperty("socksProxyPort", "1080")
        try {
            // Control: a default socket honours the proxy and so can't reach the server.
            assertFails { java.net.Socket().use { it.connect(java.net.InetSocketAddress(lan, port), 1500) } }
            // The production connector goes directly.
            PinnedHttpClient.DIRECT(lan!!, port, 2000).use { s ->
                s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); s.getOutputStream().flush()
                assertTrue(s.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 200"))
            }
            assertEquals(1, seen.size, "reached the destination directly")
        } finally {
            if (old == null) System.clearProperty("socksProxyHost") else System.setProperty("socksProxyHost", old)
            if (oldPort == null) System.clearProperty("socksProxyPort") else System.setProperty("socksProxyPort", oldPort)
        }
    }
}
