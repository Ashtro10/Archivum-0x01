package ai.clu.desktop.capability

import ai.clu.desktop.Call
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * Narrowly scoped host access for applications CLU develops. An application gets nothing by default; it declares the
 * permissions it needs in its manifest, and the owner's grants decide what it really gets:
 *
 * * `fs.read`: read-only access below one folder the owner has permitted (`/v1/apps/{id}/fs/list|read|search`).
 *   Paths are relative to that folder; `..`, absolute paths and links leading outside it are refused (judged by real
 *   path). Listings are capped; file reads are text only and capped.
 *
 * Grants are kept in the registry (`grant.<id>.fs.read`). While CLU verifies a new version, the version under test is
 * granted a fixture folder CLU creates inside its workspace, never the owner's files.
 */
class AppServices(private val store: CapabilityStore) {
    private val previewGrants = ConcurrentHashMap<String, File>()

    fun grantPreview(id: String, root: File) { previewGrants[id] = root.canonicalFile }
    fun revokePreview(id: String) { previewGrants.remove(id) }
    fun grant(id: String, perm: String, value: String?) = store.setPref("grant.$id.$perm", value)
    fun grantOf(id: String, perm: String): String? = store.pref("grant.$id.$perm")

    /** Folders the owner allows CLU's applications to read (Capabilities view → Permitted folders). */
    fun permittedRoots(): List<File> = store.pref("fs.roots")?.split('\n')?.map { it.trim() }?.filter { it.isNotBlank() }?.map(::File)?.filter { it.isDirectory } ?: emptyList()
    fun setPermittedRoots(roots: List<String>) = store.setPref("fs.roots", roots.map { File(it).canonicalPath }.distinct().joinToString("\n").ifBlank { null })

    fun fsRoot(id: String): File? = previewGrants[id] ?: grantOf(id, "fs.read")?.let(::File)?.takeIf { it.isDirectory }

    class Denied(msg: String) : Exception(msg)

    /** [rel] resolved inside [root] by real path; refuses anything that ends up outside. */
    fun resolve(root: File, rel: String): Path {
        val r = root.toPath().toRealPath()
        val clean = rel.replace('\\', '/').trim().trimStart('/')
        if (clean.split('/').any { it == ".." }) throw Denied("'..' is not allowed")
        val p = if (clean.isEmpty()) r else r.resolve(clean).normalize()
        if (!p.startsWith(r)) throw Denied("outside the permitted folder")
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) throw Denied("no such file or folder")
        val real = try { p.toRealPath() } catch (e: Exception) { throw Denied("cannot be read (${e::class.simpleName})") }
        if (!real.startsWith(r)) throw Denied("leads outside the permitted folder")
        return real
    }

    private fun entry(root: Path, p: Path): JsonObject {
        val link = Files.isSymbolicLink(p)
        val attrs = runCatching { Files.readAttributes(p, BasicFileAttributes::class.java) }.getOrNull()
        val outside = link && runCatching { !p.toRealPath().startsWith(root) }.getOrDefault(true)
        return buildJsonObject {
            put("name", p.fileName.toString()); put("path", root.relativize(p).toString().replace('\\', '/'))
            put("type", when { outside -> "link"; attrs?.isDirectory == true -> "dir"; else -> "file" })
            put("size", if (attrs?.isDirectory == false) attrs.size() else 0); put("modified", attrs?.lastModifiedTime()?.toMillis() ?: 0)
        }
    }

    fun list(root: File, rel: String): JsonObject {
        val r = root.toPath().toRealPath(); val dir = resolve(root, rel)
        if (!Files.isDirectory(dir)) throw Denied("not a folder")
        val kids = try { Files.list(dir).use { s -> s.limit(LIST_CAP + 1L).toList() } } catch (e: java.nio.file.AccessDeniedException) { throw Denied("permission denied") }
        return buildJsonObject {
            put("path", r.relativize(dir).toString().replace('\\', '/')); put("truncated", kids.size > LIST_CAP)
            putJsonArray("entries") { kids.take(LIST_CAP).sortedWith(compareBy({ !Files.isDirectory(it) }, { it.fileName.toString().lowercase() })).forEach { add(entry(r, it)) } }
        }
    }

    fun read(root: File, rel: String): JsonObject {
        val f = resolve(root, rel)
        if (Files.isDirectory(f)) throw Denied("is a folder")
        val size = Files.size(f)
        val bytes = Files.newInputStream(f).use { it.readNBytes(READ_CAP) }
        val text = bytes.toString(Charsets.UTF_8)
        val binary = bytes.any { it == 0.toByte() }
        return buildJsonObject { put("path", rel); put("size", size); put("truncated", size > READ_CAP); put("binary", binary); if (!binary) put("text", text) }
    }

    /** Names containing [q] (case-insensitive) anywhere below [rel], breadth first, capped; links are not followed. */
    fun search(root: File, rel: String, q: String): JsonObject {
        val r = root.toPath().toRealPath(); val start = resolve(root, rel); val needle = q.lowercase().trim()
        if (needle.isEmpty()) throw Denied("empty search")
        val found = mutableListOf<Path>(); val queue = ArrayDeque<Path>().apply { add(start) }; var visited = 0
        while (queue.isNotEmpty() && found.size < SEARCH_CAP && visited < 20_000) {
            val d = queue.removeFirst()
            val kids = runCatching { Files.list(d).use { it.toList() } }.getOrDefault(emptyList())
            for (k in kids) { visited++
                if (k.fileName.toString().lowercase().contains(needle)) found.add(k)
                if (Files.isDirectory(k, LinkOption.NOFOLLOW_LINKS)) queue.addLast(k)
            }
        }
        return buildJsonObject { put("query", q); putJsonArray("entries") { found.take(SEARCH_CAP).forEach { add(entry(r, it)) } } }
    }

    fun handle(c: Call): Boolean {
        val m = Regex("^/v1/apps/([a-z0-9-]{1,60})/fs/(list|read|search)$").find(c.path) ?: return c.path.startsWith("/v1/apps/").also { if (it) c.fail(404, "no such endpoint") }
        val (id, op) = m.destructured
        if (c.method != "GET") return true.also { c.fail(405, "GET only") }
        val root = fsRoot(id) ?: return true.also { c.fail(403, "this application has no permission to read files (grant it a folder in Capabilities → Permissions)") }
        try {
            val rel = c.query("path") ?: ""
            c.ok(when (op) { "list" -> list(root, rel); "read" -> read(root, rel); else -> search(root, rel, c.query("q") ?: "") })
        } catch (e: Denied) { c.fail(403, e.message ?: "denied") } catch (e: java.io.IOException) { c.fail(409, "could not read: ${e.message}") }
        return true
    }

    companion object { const val LIST_CAP = 500; const val READ_CAP = 256 * 1024; const val SEARCH_CAP = 200 }
}
