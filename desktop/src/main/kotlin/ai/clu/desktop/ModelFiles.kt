package ai.clu.desktop

import ai.clu.core.llm.GgufInfo
import ai.clu.core.llm.GgufReader
import ai.clu.core.llm.ModelRegistry
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A model file found on disk that is not registered yet. */
data class FoundModel(val path: String, val sizeBytes: Long, val info: GgufInfo?, val problem: String?)

/** Looks for GGUF files in folders the owner chose (and a few usual places), a few levels deep, bounded in time and count. */
object LocalModelScanner {
    fun defaultFolders(paths: DesktopPaths): List<File> {
        val home = File(System.getProperty("user.home"))
        return listOf(paths.models, File(home, "Downloads"), File(home, "Documents"), File(home, "Desktop"), File(home, "models"), File(home, "Models"),
            File(home, ".cache/lm-studio/models"), File(home, ".lmstudio/models")).filter { it.isDirectory }.distinctBy { it.canonicalPath }
    }

    fun scan(folders: List<File>, registered: Set<String>, maxDepth: Int = 4, maxVisited: Int = 20_000, deadlineMs: Long = 6_000): List<FoundModel> {
        val end = System.currentTimeMillis() + deadlineMs; var visited = 0
        val out = mutableListOf<FoundModel>()
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth || visited > maxVisited || System.currentTimeMillis() > end) return
            val kids = dir.listFiles() ?: return
            for (f in kids) {
                if (++visited > maxVisited) return
                if (f.isDirectory && !f.name.startsWith(".") || (f.isDirectory && depth == 0)) { if (!java.nio.file.Files.isSymbolicLink(f.toPath())) walk(f, depth + 1) }
                else if (f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && f.length() >= 1_000_000 && f.absolutePath !in registered && !f.name.endsWith(".part")) {
                    out += try { FoundModel(f.absolutePath, f.length(), GgufReader.read(f), null) } catch (e: Exception) { FoundModel(f.absolutePath, f.length(), null, e.message ?: "not a model file") }
                }
            }
        }
        folders.forEach { if (it.isDirectory) walk(it, 0) }
        return out.distinctBy { it.path }
    }
}

/** One download, as the window shows it. */
data class DownloadJob(val id: String, val modelId: String, val name: String, val url: String, val destination: String, val totalBytes: Long, val bytes: Long,
                       val state: String, val message: String, val verified: Boolean, val registeredId: String?)

/**
 * Downloads a model the owner chose, safely:
 * * only on request, with the size, source and licence shown first (the caller enforces that; this class refuses without `confirmed`);
 * * checks free disk space first;
 * * resumes an interrupted download (HTTP Range, validated against the server's size and ETag), never mixes two files;
 * * verifies size, the SHA-256 the server publishes for the file (Hugging Face sends it as X-Linked-Etag) and that it is a readable GGUF
 *   before the file gets its final name or is registered;
 * * never selects the model: choosing the active model is the owner's separate decision.
 */
class ModelDownloader(private val dir: File, private val register: (File) -> String?, private val client: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build(), private val minFreeMargin: Long = 500L * 1024 * 1024) {
    private class Active(val id: String, val modelId: String, val name: String, val url: String, val dest: File) {
        @Volatile var total = 0L; @Volatile var bytes = 0L; @Volatile var state = "starting"; @Volatile var message = ""; @Volatile var verified = false; @Volatile var registered: String? = null
        val cancel = AtomicBoolean(false)
    }
    private val jobs = ConcurrentHashMap<String, Active>()
    private val pool = Executors.newFixedThreadPool(1) { r -> Thread(r, "clu-download").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    fun list(): List<DownloadJob> = jobs.values.map { snapshot(it) }
    fun get(id: String) = jobs[id]?.let(::snapshot)
    private fun snapshot(a: Active) = DownloadJob(a.id, a.modelId, a.name, a.url, a.dest.absolutePath, a.total, a.bytes, a.state, a.message, a.verified, a.registered)

    /** Starts (or resumes) a download. [confirmed] must be true: the owner has seen the size, source and licence. */
    fun start(modelId: String, name: String, url: String, fileName: String, confirmed: Boolean): DownloadJob {
        require(confirmed) { "A download needs the owner's confirmation." }
        require(url.startsWith("https://") || url.startsWith("http://127.0.0.1")) { "Only https downloads are allowed." }
        require(fileName.matches(Regex("[A-Za-z0-9._-]{1,120}\\.gguf"))) { "Unexpected file name." }
        jobs.values.firstOrNull { it.modelId == modelId && it.state in setOf("starting", "downloading", "verifying") }?.let { return snapshot(it) }
        dir.mkdirs()
        val a = Active(ai.clu.core.util.newId(), modelId, name, url, File(dir, fileName)).also { jobs[it.id] = it }
        pool.execute { runCatching { download(a) }.onFailure { a.state = "failed"; a.message = it.message ?: it.toString() } }
        return snapshot(a)
    }

    fun cancel(id: String) { jobs[id]?.let { it.cancel.set(true) } }

    /** Removes a model file that CLU downloaded (only inside its own models folder, never anything else). Returns true if a file was deleted. */
    fun deleteDownloaded(file: File): Boolean {
        val inside = runCatching { file.canonicalFile.parentFile == dir.canonicalFile }.getOrDefault(false)
        require(inside) { "CLU only deletes files it downloaded into its own models folder." }
        return file.delete()
    }

    private fun head(url: String): Triple<Long, String?, Boolean> {
        val r = client.send(HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.discarding())
        if (r.statusCode() !in 200..299) throw IOException("The server answered ${r.statusCode()} for $url")
        val len = r.headers().firstValueAsLong("Content-Length").orElse(-1)
        val sha = (r.headers().firstValue("X-Linked-Etag").orElse(null) ?: r.headers().firstValue("ETag").orElse(null))?.trim('"', 'W', '/')?.trim('"')?.takeIf { Regex("[0-9a-fA-F]{64}").matches(it) }
        return Triple(len, sha?.lowercase(), r.headers().firstValue("Accept-Ranges").orElse("").equals("bytes", true))
    }

    private fun download(a: Active) {
        val (total, sha, ranges) = head(a.url)
        if (total <= 0) throw IOException("The server did not say how large the file is; refusing to download blindly.")
        a.total = total
        val free = dir.usableSpace
        val part = File(a.dest.path + ".part"); val meta = File(a.dest.path + ".part.meta")
        val have = if (part.isFile && meta.isFile && meta.readText() == "$total|${sha ?: ""}|${a.url.substringBefore('?')}") part.length() else 0L
        if (have == 0L) { part.delete(); meta.delete() }
        if (free < total - have + minFreeMargin) throw IOException("Not enough free space: the model needs ${total / 1_048_576} MB and ${(free - minFreeMargin).coerceAtLeast(0) / 1_048_576} MB is available.")
        meta.writeText("$total|${sha ?: ""}|${a.url.substringBefore('?')}")
        var start = if (ranges) have else 0L
        if (start > total) { part.delete(); start = 0 }
        a.bytes = start; a.state = "downloading"
        if (start < total) {
            val b = HttpRequest.newBuilder(URI.create(a.url)).GET().timeout(Duration.ofMinutes(10))
            if (start > 0) b.header("Range", "bytes=$start-")
            val resp = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream())
            if (resp.statusCode() != 200 && resp.statusCode() != 206) throw IOException("The server answered ${resp.statusCode()}.")
            val resumed = resp.statusCode() == 206
            if (start > 0 && !resumed) { start = 0; a.bytes = 0 } // the server ignored the range: start over rather than corrupt the file
            RandomAccessFile(part, "rw").use { raf ->
                if (resumed) raf.seek(start) else raf.setLength(0)
                resp.body().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (a.cancel.get()) { a.state = "cancelled"; a.message = "Cancelled. The part already downloaded is kept, so it can be resumed."; return }
                        val n = input.read(buf); if (n < 0) break
                        raf.write(buf, 0, n); a.bytes += n
                    }
                }
            }
        }
        a.state = "verifying"
        if (part.length() != total) throw IOException("The download ended early (${part.length()} of $total bytes). Start it again to resume.")
        if (sha != null) {
            val md = MessageDigest.getInstance("SHA-256"); part.inputStream().use { i -> val buf = ByteArray(1 shl 20); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != sha) { part.delete(); meta.delete(); throw IOException("The file does not match the checksum the server published; it was discarded.") }
            a.verified = true
        }
        val info = try { GgufReader.read(part) } catch (e: Exception) { part.delete(); meta.delete(); throw IOException("The downloaded file is not a usable model: ${e.message}") }
        if (a.dest.exists()) a.dest.delete()
        if (!part.renameTo(a.dest)) throw IOException("Could not move the finished download into place.")
        meta.delete()
        a.registered = register(a.dest)
        a.state = "done"; a.message = (if (a.verified) "Downloaded and verified (SHA-256 matches)." else "Downloaded; the server published no checksum, so only the size and the model format were verified.") +
            " It is registered but not selected: choose it as your model when you are ready (${info.quantization})."
    }
}
