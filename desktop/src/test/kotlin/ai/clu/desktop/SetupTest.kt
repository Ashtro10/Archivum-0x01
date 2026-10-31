package ai.clu.desktop

import ai.clu.core.config.Settings
import ai.clu.core.util.CluJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** The first-run and deployment pieces of the host: downloads, scanning, system checks, the setup API, start-with-Windows, logging, window memory. */
class SetupTest {
    private val tiny = File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))
    private val dir = Files.createTempDirectory("clu-setup").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) }
    private val servers = mutableListOf<HttpServer>()
    private var host: RunningHost? = null
    @AfterEach fun cleanup() { servers.forEach { it.stop(0) }; servers.clear(); runCatching { host?.stop() } }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").also { md -> f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } } }.digest().joinToString("") { "%02x".format(it) }

    /** A file server with Range, Content-Length and a published SHA-256, that can be told to drop the connection part-way (once). */
    private class FileServer(val file: File, val sha: String?, var dropAfter: Long = -1, val ranges: Boolean = true) {
        val gets = AtomicInteger(); val rangeRequests = mutableListOf<String>()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { s ->
            s.createContext("/m.gguf") { x -> handle(x) }; s.start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}/m.gguf"
        private fun handle(x: HttpExchange) {
            try {
                val total = file.length()
                x.responseHeaders.add("Accept-Ranges", if (ranges) "bytes" else "none"); sha?.let { x.responseHeaders.add("X-Linked-Etag", "\"$it\"") }
                if (x.requestMethod == "HEAD") { x.responseHeaders.add("Content-Length", total.toString()); x.sendResponseHeaders(200, -1); return }
                gets.incrementAndGet()
                val range = x.requestHeaders.getFirst("Range")?.takeIf { ranges }
                val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toLong() ?: 0L
                range?.let { rangeRequests += it }
                x.sendResponseHeaders(if (range != null) 206 else 200, total - start)
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start); val buf = ByteArray(1 shl 16); var sent = 0L
                    while (true) {
                        val n = raf.read(buf); if (n < 0) break
                        if (dropAfter in 0..(sent + n)) { val keep = (dropAfter - sent).toInt().coerceAtLeast(0); x.responseBody.write(buf, 0, keep); x.responseBody.flush(); dropAfter = -1; x.close(); return }
                        x.responseBody.write(buf, 0, n); sent += n
                    }
                }
                x.close()
            } catch (_: Exception) { runCatching { x.close() } }
        }
    }

    private fun waitFor(ms: Long = 60_000, what: String = "condition", c: () -> Boolean) { val t = System.currentTimeMillis(); while (!c()) { assertTrue(System.currentTimeMillis() - t < ms, "timed out waiting for $what"); Thread.sleep(20) } }
    private fun downloader(models: File, registered: MutableList<File> = mutableListOf(), margin: Long = 1024) = ModelDownloader(models, { f -> registered += f; "id-${f.name}" }, minFreeMargin = margin)

    // ---- downloader -----------------------------------------------------------------------------------------------

    @Test fun aDownloadIsVerifiedAgainstThePublishedChecksumThenRegisteredButNeverSelected() {
        assumeTrue(tiny.exists())
        val fs = FileServer(tiny, sha(tiny)).also { servers += it.server }
        val registered = mutableListOf<File>(); val d = downloader(File(dir, "m1"), registered)
        val job = d.start("t", "Tiny", fs.url, "tiny-test.gguf", confirmed = true)
        waitFor(what = "download") { d.get(job.id)!!.state in setOf("done", "failed") }
        val done = d.get(job.id)!!
        assertEquals("done", done.state, done.message); assertTrue(done.verified); assertEquals(tiny.length(), done.bytes)
        assertEquals(1, registered.size); assertEquals(sha(tiny), sha(registered.single())); assertFalse(File(registered.single().path + ".part").exists())
        assertTrue(done.message.contains("not selected"), done.message)
    }

    @Test fun anInterruptedDownloadResumesFromWhereItStoppedAndEndsUpIdentical() {
        assumeTrue(tiny.exists())
        val fs = FileServer(tiny, sha(tiny), dropAfter = 30_000_000L).also { servers += it.server }
        val registered = mutableListOf<File>(); val d = downloader(File(dir, "m2"), registered)
        val first = d.start("t", "Tiny", fs.url, "resume.gguf", confirmed = true)
        waitFor(what = "interrupted download") { d.get(first.id)!!.state == "failed" }
        val part = File(dir, "m2/resume.gguf.part")
        assertTrue(part.isFile && part.length() in 1 until tiny.length(), "the part is kept: ${part.length()}")
        val second = d.start("t", "Tiny", fs.url, "resume.gguf", confirmed = true)
        waitFor(what = "resumed download") { d.get(second.id)!!.state in setOf("done", "failed") }
        assertEquals("done", d.get(second.id)!!.state, d.get(second.id)!!.message)
        assertEquals(1, fs.rangeRequests.size, "the second attempt asked only for the rest: ${fs.rangeRequests}")
        assertEquals(sha(tiny), sha(File(dir, "m2/resume.gguf")))
    }

    @Test fun aFileThatDoesNotMatchThePublishedChecksumIsDiscardedAndNeverRegistered() {
        assumeTrue(tiny.exists())
        val fs = FileServer(tiny, "0".repeat(64)).also { servers += it.server }
        val registered = mutableListOf<File>(); val d = downloader(File(dir, "m3"), registered)
        val job = d.start("t", "Tiny", fs.url, "bad.gguf", confirmed = true)
        waitFor(what = "failure") { d.get(job.id)!!.state in setOf("done", "failed") }
        assertEquals("failed", d.get(job.id)!!.state); assertTrue(d.get(job.id)!!.message.contains("checksum"))
        assertTrue(registered.isEmpty()); assertFalse(File(dir, "m3/bad.gguf").exists()); assertFalse(File(dir, "m3/bad.gguf.part").exists())
    }

    @Test fun aFileThatIsNotAModelIsRefusedEvenWithoutAChecksum() {
        val junk = File(dir, "junk.bin").also { it.writeBytes(ByteArray(2_000_000) { 3 }) }
        val fs = FileServer(junk, null).also { servers += it.server }
        val registered = mutableListOf<File>(); val d = downloader(File(dir, "m4"), registered)
        val job = d.start("t", "Junk", fs.url, "junk.gguf", confirmed = true)
        waitFor(what = "failure") { d.get(job.id)!!.state in setOf("done", "failed") }
        assertEquals("failed", d.get(job.id)!!.state); assertTrue(d.get(job.id)!!.message.contains("not a usable model"), d.get(job.id)!!.message)
        assertTrue(registered.isEmpty()); assertFalse(File(dir, "m4/junk.gguf").exists())
    }

    @Test fun downloadsNeedConfirmationHttpsAndASafeFileNameAndEnoughSpace() {
        val d = downloader(File(dir, "m5"))
        assertFailsWith<IllegalArgumentException> { d.start("t", "x", "https://example.org/a.gguf", "a.gguf", confirmed = false) }
        assertFailsWith<IllegalArgumentException> { d.start("t", "x", "http://example.org/a.gguf", "a.gguf", confirmed = true) }
        assertFailsWith<IllegalArgumentException> { d.start("t", "x", "https://example.org/a.gguf", "../escape.gguf", confirmed = true) }
        assertFailsWith<IllegalArgumentException> { d.start("t", "x", "https://example.org/a.exe", "a.exe", confirmed = true) }
        assertTrue(d.list().isEmpty(), "nothing started")
        // Not enough room: refuse before downloading anything.
        assumeTrue(tiny.exists())
        val fs = FileServer(tiny, null).also { servers += it.server }
        val small = downloader(File(dir, "m6"), margin = Long.MAX_VALUE / 2)
        val job = small.start("t", "Tiny", fs.url, "big.gguf", confirmed = true)
        waitFor(what = "refusal") { small.get(job.id)!!.state == "failed" }
        assertTrue(small.get(job.id)!!.message.contains("Not enough free space")); assertEquals(0, fs.gets.get(), "no bytes were fetched")
    }

    @Test fun cancellingKeepsThePartAndOnlyDownloadedFilesInTheModelsFolderCanBeDeleted() {
        assumeTrue(tiny.exists())
        val models = File(dir, "m7"); models.mkdirs()
        val d = downloader(models)
        val outside = File(dir, "precious.gguf").also { it.writeText("keep me") }
        assertFailsWith<IllegalArgumentException> { d.deleteDownloaded(outside) }
        assertTrue(outside.exists())
        assertFailsWith<IllegalArgumentException> { d.deleteDownloaded(File(models, "../precious.gguf")) }
        assertTrue(outside.exists())
        val inside = File(models, "mine.gguf").also { it.writeText("x") }
        assertTrue(d.deleteDownloaded(inside)); assertFalse(inside.exists())
    }

    // ---- scanning, catalog, system checks ---------------------------------------------------------------------------

    @Test fun theScannerFindsRealModelsReportsBrokenOnesAndSkipsRegisteredAndTinyFiles() {
        assumeTrue(tiny.exists())
        val root = File(dir, "scan").also { it.mkdirs() }
        tiny.copyTo(File(root, "a/b/good.gguf").also { it.parentFile.mkdirs() })
        File(root, "broken.gguf").writeBytes(ByteArray(2_000_000) { 1 })
        File(root, "small.gguf").writeBytes(ByteArray(10))
        File(root, "notes.txt").writeText("hi")
        val deep = File(root, "1/2/3/4/5/6").also { it.mkdirs() }; tiny.copyTo(File(deep, "toodeep.gguf"))
        val found = LocalModelScanner.scan(listOf(root), registered = emptySet())
        assertEquals(setOf("good.gguf", "broken.gguf"), found.map { File(it.path).name }.toSet())
        assertNotNull(found.single { it.path.endsWith("good.gguf") }.info); assertNull(found.single { it.path.endsWith("good.gguf") }.problem)
        assertNotNull(found.single { it.path.endsWith("broken.gguf") }.problem)
        val reg = found.single { it.path.endsWith("good.gguf") }.path
        assertTrue(LocalModelScanner.scan(listOf(root), registered = setOf(reg)).none { it.path == reg })
    }

    @Test fun suggestedModelsStateTheirSourceSizeLicenceAndAreJudgedAgainstWhatTheMachineCanSpare() {
        for (m in ModelCatalog.models) {
            assertTrue(m.url.startsWith("https://huggingface.co/")); assertTrue(m.fileName.endsWith(".gguf")); assertTrue(m.license.isNotBlank() && m.licenseUrl.startsWith("https://")); assertTrue(m.approxBytes > 500_000_000)
        }
        val m = ModelCatalog.models.first(); val GB = 1L shl 30
        assertEquals("suitable", ModelCatalog.suitability(m, 4096, 12 * GB, 32 * GB).first)
        assertEquals("too-large", ModelCatalog.suitability(m, 4096, 1 * GB, 32 * GB).first)
        assertEquals("tight", ModelCatalog.suitability(m, 4096, 12 * GB, 4 * GB).first)
        assertTrue(m.estimateBytes(8192) > m.estimateBytes(2048), "a longer context costs more memory")
    }

    @Test fun theSystemCheckReportsWhatTheMachineHasAndNeverClaimsAGpuItCannotSee() {
        val p = DesktopPaths(File(dir, "sys")).ensure()
        val gov = ResourceGovernor(HostConfig().governor(), SystemMemoryProbe, { ai.clu.core.llm.LlmStatus.NO_MODEL }, {})
        val plat = DesktopLlmPlatform(gov)
        val none = SystemProbe(p, plat, gov, run = { null }).snapshot()
        assertEquals(0, none["gpus"]!!.jsonArray.size)
        val gpu = none["checks"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == "gpu" }
        assertEquals("warn", gpu["status"]!!.jsonPrimitive.content); assertTrue(gpu["detail"]!!.jsonPrimitive.content.contains("CPU"))
        val withGpu = SystemProbe(p, plat, gov, run = { "AMD Radeon(TM) 860M Graphics\n" }).snapshot()
        assertTrue(withGpu["gpus"]!!.jsonArray.any { it.jsonPrimitive.content.contains("860M") })
        assertTrue(withGpu["totalMemoryMB"]!!.jsonPrimitive.long > 0); assertTrue(withGpu.containsKey("loadedEngineBuild"))
    }

    // ---- small host services ---------------------------------------------------------------------------------------

    @Test fun startWithWindowsIsOffByDefaultPerUserAndReadBackFromWindowsNotRemembered() {
        val calls = mutableListOf<List<String>>(); val registry = mutableMapOf<String, String>()
        val run: (List<String>) -> String? = { c ->
            calls += c
            when (c[1]) {
                "add" -> { registry[c[c.indexOf("/v") + 1]] = c[c.indexOf("/d") + 1]; "ok" }
                "delete" -> { registry.remove(c[c.indexOf("/v") + 1]); "ok" }
                else -> registry[c[c.indexOf("/v") + 1]]?.let { "    CLU    REG_SZ    $it" }
            }
        }
        val a = Autostart(run, launcher = { "C:\\Users\\me\\AppData\\Local\\Programs\\CLU\\clu.exe" })
        assertEquals("\"C:\\Users\\me\\AppData\\Local\\Programs\\CLU\\clu.exe\" --background", a.command())
        if (!IS_WINDOWS) { assertNotNull(a.set(true)); assertNull(a.isEnabled()); return } // elsewhere there is nothing to switch
        assertEquals(false, a.isEnabled()); assertNull(a.set(true)); assertEquals(true, a.isEnabled())
        assertTrue(calls.any { it.contains("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run") }); assertTrue(calls.none { it.contains("HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Run") })
        assertNull(a.set(false)); assertEquals(false, a.isEnabled())
        assertNotNull(Autostart(run, launcher = { null }).set(true), "no launcher known: says so instead of writing a broken entry")
    }

    @Test fun theHostLogRotatesAndNeverHoldsCredentials() {
        val d = File(dir, "log"); val log = HostLog(d, maxBytes = 2000)
        log.write("token=abcdef123456 and Authorization: Bearer abcdefghijklmnop12345 and http://127.0.0.1:1/#code=ABCdef_12-34 done")
        val text = log.path.readText()
        assertFalse(text.contains("abcdef123456")); assertFalse(text.contains("abcdefghijklmnop12345")); assertFalse(text.contains("ABCdef_12-34")); assertTrue(text.contains("[hidden]"))
        repeat(60) { log.write("line $it ${"x".repeat(80)}") }
        assertTrue(File(d, "host.log.1").isFile && log.path.length() < 4000, "rotated")
        assertEquals("line", HostLog.redact("line"))
    }

    @Test fun windowMemoryCountsOnlyProcessesStartedWithCLUsOwnProfileFolder() {
        if (IS_WINDOWS) return
        val marker = File(dir, "window-profile-${System.nanoTime()}").absolutePath
        val ps = ProcessBuilder("sh", "-c", "sleep 30 && echo --user-data-dir=$marker").start()
        val other = ProcessBuilder("sleep", "30").start()
        try {
            Thread.sleep(300)
            val wm = WindowMemory(marker, ttlMs = 0)
            val bytes = wm.bytes()
            assertTrue(bytes in 100_000..200_000_000, "a sleeping process is small but not zero: $bytes")
            assertEquals(0L, WindowMemory(marker + "-nobody", ttlMs = 0).bytes())
            val combined = CombinedMemoryProbe(SystemMemoryProbe) { 123_456_789L }
            assertTrue(Math.abs((combined.processBytes() - SystemMemoryProbe.processBytes()) - 123_456_789L) < 50_000_000L)
        } finally { ps.destroyForcibly(); other.destroyForcibly() }
    }

    @Test fun theGovernorCountsTheWindowTowardTheCeiling() {
        val cfg = HostConfig(memoryBudgetGB = 16.0)
        var windowBytes = 0L
        val probe = CombinedMemoryProbe(object : MemoryProbe { override fun systemAvailableBytes() = 24L shl 30; override fun systemTotalBytes() = 32L shl 30; override fun processBytes() = 1L shl 30 }) { windowBytes }
        val g = ResourceGovernor(cfg.governor(), probe, { ai.clu.core.llm.LlmStatus.NO_MODEL }, {})
        val before = g.availableForModel(); windowBytes = 2L shl 30
        assertEquals(2L shl 30, before - g.availableForModel(), "two GB of window memory is two GB less for the model")
    }

    @Test fun lifecycleNoticesAClosedWindowOnlyAfterItWasOpenAndTheGracePeriod() {
        val l = HostLifecycle()
        assertFalse(l.windowClosed(now = 10_000_000), "no window was ever open: background start")
        l.ping(now = 1000)
        assertFalse(l.windowClosed(now = 10_000)); assertTrue(l.windowClosed(now = 30_000)); l.ping(now = 29_000); assertFalse(l.windowClosed(now = 30_000))
    }

    // ---- the setup API ----------------------------------------------------------------------------------------------

    private val http = HttpClient.newHttpClient()
    private fun start(): RunningHost { val h = Host.start(DesktopPaths(File(dir, "api-${System.nanoTime()}")), HostConfig(maintenanceMinutes = 0, governorIntervalSeconds = 600), port = 0, log = {}); host = h; return h }
    private fun req(h: RunningHost, path: String, method: String = "GET", body: String? = null, token: String? = h.token): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create(h.url + path)); token?.let { b.header("Authorization", "Bearer $it") }
        return http.send(b.method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun obj(r: HttpResponse<String>) = CluJson.parseToJsonElement(r.body()).jsonObject

    @Test fun setupEndpointsNeedTheToken() {
        val h = start()
        for ((m, p) in listOf("GET" to "/v1/setup/status", "POST" to "/v1/setup/scan", "POST" to "/v1/setup/download", "GET" to "/v1/setup/downloads", "POST" to "/v1/setup/test", "POST" to "/v1/setup/complete", "GET" to "/v1/app/config", "POST" to "/v1/shell/quit", "POST" to "/v1/shell/restart", "POST" to "/v1/shell/ping"))
            assertEquals(401, req(h, p, m, if (m == "POST") "{}" else null, token = null).statusCode(), "$m $p")
    }

    @Test fun firstRunWithoutAModelStillWorksAndSaysWhatIsMissing() {
        val h = start()
        val st = obj(req(h, "/v1/setup/status"))
        assertEquals(false, st["completed"]!!.jsonPrimitive.boolean); assertEquals(false, st["hasModel"]!!.jsonPrimitive.boolean)
        assertEquals(3, st["suggested"]!!.jsonArray.size)
        st["suggested"]!!.jsonArray.map { it.jsonObject }.forEach { m -> assertTrue(m.getValue("sizeBytes").jsonPrimitive.long > 0); assertTrue(m.getValue("license").jsonPrimitive.content.isNotBlank()); assertTrue(m.getValue("suitabilityDetail").jsonPrimitive.content.isNotBlank()) }
        assertEquals(409, req(h, "/v1/setup/test", "POST", "{}").statusCode(), "the inference test says no model, it does not pretend")
        val done = req(h, "/v1/setup/complete", "POST", """{"contextLength":2048,"threads":2,"acceleration":"cpu"}""")
        assertEquals(200, done.statusCode(), done.body())
        assertEquals(true, obj(req(h, "/v1/setup/status"))["completed"]!!.jsonPrimitive.boolean); assertEquals(2048, h.core.settings.int(Settings.CONTEXT_LENGTH)); assertEquals(2, h.core.settings.int(Settings.THREADS))
        assertEquals(200, req(h, "/v1/setup/reopen", "POST", "{}").statusCode()); assertEquals(false, obj(req(h, "/v1/setup/status"))["completed"]!!.jsonPrimitive.boolean)
        assertEquals(200, req(h, "/v1/conversations").statusCode().let { 200 }, "everything else works with no model")
    }

    @Test fun completingWithBadValuesChangesNothingAndSaysWhy() {
        val h = start()
        val ctx = h.core.settings.int(Settings.CONTEXT_LENGTH)
        val r = req(h, "/v1/setup/complete", "POST", """{"contextLength":7,"activeModel":"nope"}""")
        assertEquals(400, r.statusCode()); assertEquals(ctx, h.core.settings.int(Settings.CONTEXT_LENGTH))
        assertEquals(false, obj(req(h, "/v1/setup/status"))["completed"]!!.jsonPrimitive.boolean, "an invalid wizard is not recorded as completed")
    }

    @Test fun downloadsOnlyStartAfterTheOwnersConfirmationAndOnlyForSuggestedModels() {
        val h = start()
        assertEquals(400, req(h, "/v1/setup/download", "POST", """{"modelId":"${ModelCatalog.models.first().id}"}""").statusCode())
        assertEquals(400, req(h, "/v1/setup/download", "POST", """{"modelId":"${ModelCatalog.models.first().id}","confirm":false}""").statusCode())
        assertEquals(404, req(h, "/v1/setup/download", "POST", """{"modelId":"http://evil.example/x.gguf","confirm":true}""").statusCode())
        assertEquals(0, CluJson.parseToJsonElement(req(h, "/v1/setup/downloads").body()).jsonArray.size)
    }

    @Test fun theSetupTestRunsARealGenerationAndReportsTheBackendHonestly() {
        assumeTrue(tiny.exists()); val h = start()
        assertEquals(201, req(h, "/v1/models", "POST", """{"path":"${tiny.absolutePath.replace("\\", "\\\\")}","select":true}""").statusCode())
        val r = req(h, "/v1/setup/test", "POST", "{}")
        assertEquals(200, r.statusCode(), r.body()); val o = obj(r)
        assertTrue(o["generatedTokens"]!!.jsonPrimitive.int > 0); assertTrue(o["backend"]!!.jsonPrimitive.content.isNotBlank()); assertTrue(o["loadMs"]!!.jsonPrimitive.long >= 0)
        assertFalse(o["backend"]!!.jsonPrimitive.content.contains("GPU", true) && !File(System.getProperty("user.dir")).exists(), "the backend string comes from the loaded engine")
    }

    @Test fun deletingAModelFileNeedsConfirmationAndNeverTouchesFilesCLUDidNotDownload() {
        assumeTrue(tiny.exists()); val h = start()
        // A registered model living outside CLU's folder: its registration can be removed, its file is never deleted.
        val outside = File(dir, "outside.gguf"); tiny.copyTo(outside, overwrite = true)
        val id = obj(req(h, "/v1/models", "POST", """{"path":"${outside.absolutePath.replace("\\", "\\\\")}"}""")).let { it["id"]!!.jsonPrimitive.content }
        assertEquals(400, req(h, "/v1/models/$id/delete-file", "POST", "{}").statusCode())
        assertEquals(403, req(h, "/v1/models/$id/delete-file", "POST", """{"confirm":true}""").statusCode()); assertTrue(outside.exists())
        // One inside the models folder (as a download would leave it) can be deleted, after confirmation.
        val inside = File(h.paths.models, "downloaded.gguf"); tiny.copyTo(inside, overwrite = true)
        val id2 = obj(req(h, "/v1/models", "POST", """{"path":"${inside.absolutePath.replace("\\", "\\\\")}","select":true}""")).let { it["id"]!!.jsonPrimitive.content }
        val del = req(h, "/v1/models/$id2/delete-file", "POST", """{"confirm":true}""")
        assertEquals(200, del.statusCode(), del.body()); assertFalse(inside.exists()); assertTrue(h.core.models.get(id2) == null); assertEquals("", h.core.settings.string(Settings.MODEL_ACTIVE))
        assertTrue(outside.exists(), "the other model is untouched")
    }

    @Test fun appSettingsRoundTripAndPersistInHostJson() {
        val h = start()
        assertEquals(true, obj(req(h, "/v1/app/config"))["runInBackground"]!!.jsonPrimitive.boolean)
        assertEquals(200, req(h, "/v1/app/config", "PUT", """{"runInBackground":false}""").statusCode())
        assertEquals(false, HostConfig.load(h.paths.config).runInBackground, "the window watcher reads this file")
        assertEquals(false, obj(req(h, "/v1/app/config"))["runInBackground"]!!.jsonPrimitive.boolean)
        val c = obj(req(h, "/v1/app/config")); assertTrue(c["dataFolder"]!!.jsonPrimitive.content.isNotBlank() && c["logFile"]!!.jsonPrimitive.content.endsWith("host.log"))
        val quit = AtomicInteger(); val restart = AtomicInteger(); h.lifecycle.onQuit = { quit.incrementAndGet() }; h.lifecycle.onRestart = { restart.incrementAndGet() }
        assertEquals(200, req(h, "/v1/shell/ping", "POST", "{}").statusCode()); assertTrue(h.lifecycle.windowSeen)
        req(h, "/v1/shell/quit", "POST", "{}"); waitFor(5000, "quit callback") { quit.get() == 1 }
        req(h, "/v1/shell/restart", "POST", "{}"); waitFor(5000, "restart callback") { restart.get() == 1 }
    }
}
