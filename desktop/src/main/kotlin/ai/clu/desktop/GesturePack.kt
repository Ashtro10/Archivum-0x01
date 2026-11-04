package ai.clu.desktop

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * The hand-tracking runtime for CLU's camera gesture interface: Google MediaPipe Tasks Vision 0.10.14 (Apache-2.0) and
 * its hand landmark model. About 17.5 MB, so it is not shipped inside CLU; it is downloaded once, when the owner asks,
 * from its official sources (the npm registry tarball, verified by npm's SHA-512 integrity; the model from Google's
 * model storage), every file checked against the SHA-256 pinned here, kept in the data folder and served by CLU from
 * then on, so the gestures work offline. Nothing else is downloaded and nothing runs that was not verified.
 */
class GesturePack(val dir: File, private val log: (String) -> Unit = {}) {
    data class Pinned(val name: String, val sha256: String, val size: Long)

    companion object {
        const val VERSION = "0.10.14"
        const val TARBALL = "https://registry.npmjs.org/@mediapipe/tasks-vision/-/tasks-vision-0.10.14.tgz"
        const val TARBALL_SHA512 = "vOifgZhkndgybdvoRITzRkIueWWSiCKuEUXXK6Q4FaJsFvRJuwgg++vqFUMlL0Uox62U5aEXFhHxlhV7Ja5e3Q=="
        const val MODEL = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
        /** Files CLU uses, by their name in the pack (tarball path → served name). */
        val FROM_TARBALL = mapOf("package/vision_bundle.mjs" to "vision_bundle.mjs", "package/wasm/vision_wasm_internal.js" to "vision_wasm_internal.js",
            "package/wasm/vision_wasm_internal.wasm" to "vision_wasm_internal.wasm")
        val FILES = listOf(
            Pinned("vision_bundle.mjs", "e77f281f9619150d937023c355bae170e9120e3b9e43f1e23a2a7bee07197669", 136_870),
            Pinned("vision_wasm_internal.js", "9440cf0cc0cea21800e31581ec32aeedcc5fbf9df4509796bbc7d3f99e52ab9c", 209_826),
            Pinned("vision_wasm_internal.wasm", "f82a8e6c05e08a44cc9f9e7ec5f845935bcbb1b1500ebe8c2f4812fb4e2917dc", 9_423_986),
            Pinned("hand_landmarker.task", "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1", 7_819_105),
        )
        val TOTAL_BYTES = FILES.sumOf { it.size }
        private val TYPES = mapOf("mjs" to "text/javascript", "js" to "text/javascript", "wasm" to "application/wasm", "task" to "application/octet-stream")
        fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }

    @Volatile var progress: String = ""; private set

    /** Files present and matching their pinned hashes. */
    fun verified(): Boolean = FILES.all { f -> File(dir, f.name).let { it.isFile && it.length() == f.size && sha256(it.readBytes()) == f.sha256 } }

    fun status() = buildJsonObject {
        put("installed", verified()); put("version", VERSION); put("bytes", TOTAL_BYTES); put("progress", progress)
        putJsonArray("sources") { add(TARBALL); add(MODEL) }
        put("licence", "Apache-2.0 (Google MediaPipe)")
        put("folder", dir.path)
    }

    private fun accept(name: String, bytes: ByteArray) {
        val pin = FILES.single { it.name == name }
        val got = sha256(bytes)
        require(got == pin.sha256 && bytes.size.toLong() == pin.size) { "$name does not match its pinned hash (got $got, ${bytes.size} bytes)" }
        dir.mkdirs(); val tmp = File(dir, "$name.part"); tmp.writeBytes(bytes)
        java.nio.file.Files.move(tmp.toPath(), File(dir, name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    /** Reads [names] out of a .tgz stream (ustar), nothing else is kept. */
    private fun untar(input: InputStream, names: Set<String>): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>(); val gz = GZIPInputStream(input).buffered(); val header = ByteArray(512)
        while (true) {
            if (gz.readNBytes(header, 0, 512) < 512 || header.all { it == 0.toByte() }) break
            val name = String(header, 0, 100).trimEnd('\u0000').let { n -> val prefix = String(header, 345, 155).trimEnd('\u0000'); if (prefix.isNotEmpty()) "$prefix/$n" else n }
            val size = String(header, 124, 12).trim('\u0000', ' ').toLong(8)
            val padded = (size + 511) / 512 * 512
            if (name in names) { out[name] = gz.readNBytes(size.toInt()); gz.skipNBytes(padded - size) } else gz.skipNBytes(padded)
            if (out.size == names.size) break
        }
        return out
    }

    /** Installs from a folder that already holds the four files (a copy made elsewhere); each file is verified. */
    fun installFrom(source: File) { FILES.forEach { f -> accept(f.name, File(source, f.name).readBytes()) }; check(verified()) }

    /** Downloads and verifies the pack; returns null on success or why it failed (nothing unverified is kept). */
    fun download(http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()): String? = try {
        progress = "downloading the MediaPipe runtime (npm, ~6 MB)"
        val tgz = http.send(HttpRequest.newBuilder(URI(TARBALL)).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        if (tgz.statusCode() != 200) error("the npm registry answered HTTP ${tgz.statusCode()}")
        val integrity = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(tgz.body()))
        require(integrity == TARBALL_SHA512) { "the runtime package does not match npm's published integrity" }
        untar(tgz.body().inputStream(), FROM_TARBALL.keys).forEach { (path, bytes) -> accept(FROM_TARBALL.getValue(path), bytes) }
        progress = "downloading the hand model (Google, ~7.8 MB)"
        val model = http.send(HttpRequest.newBuilder(URI(MODEL)).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        if (model.statusCode() != 200) error("the model storage answered HTTP ${model.statusCode()}")
        accept("hand_landmarker.task", model.body())
        progress = if (verified()) "installed" else "incomplete"
        if (verified()) null else "some files are missing after the download"
    } catch (e: Exception) { progress = "failed: ${e.message}"; log("gesture pack: ${e.message}"); e.message ?: e::class.simpleName }

    /** `/shell/gesture/<file>`: the verified pack files only, to CLU's own pages. */
    fun serve(ex: HttpExchange, name: String) {
        val pin = FILES.firstOrNull { it.name == name }
        val f = pin?.let { File(dir, it.name) }?.takeIf { it.isFile && it.length() == pin.size }
        ex.responseHeaders.apply { set("X-Content-Type-Options", "nosniff"); set("Cache-Control", "private, max-age=86400"); set("Cross-Origin-Resource-Policy", "same-origin") }
        if (f == null) { ex.sendResponseHeaders(404, -1); ex.close(); return }
        val bytes = f.readBytes()
        ex.responseHeaders.set("Content-Type", TYPES.getValue(name.substringAfterLast('.')))
        ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
    }
}
