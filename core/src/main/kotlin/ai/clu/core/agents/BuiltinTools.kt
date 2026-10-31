package ai.clu.core.agents

import ai.clu.core.memory.MemoryService
import ai.clu.core.memory.ProposalKind
import ai.clu.core.memory.ProposalRepository
import ai.clu.core.memory.RecallRequest
import ai.clu.core.search.SearchService
import ai.clu.core.util.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** What the built-in tools need from the rest of CLU. */
class AgentDeps(
    val memory: MemoryService?, val proposals: ProposalRepository?, val search: SearchService?, val roots: AgentRoots, val clock: Clock,
    val status: () -> JsonObject, val shellAllowlist: () -> List<String> = { emptyList() },
)

private val textOut = mapOf("text" to Field("string", required = true, maxLength = 40_000), "count" to Field("integer", min = 0))
private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

object BuiltinTools {
    fun registerAll(r: AgentToolRegistry, d: AgentDeps) {
        listOf(SystemStatus(d), MemorySearch(d), MemorySuggest(d), FsList(d), FsRead(d), FsStat(d), FsMkdir(d), FsWrite(d), FsDelete(d), WebSearch(d), WebFetch(d), ProcRun(d)).forEach(r::register)
    }

    class SystemStatus(private val d: AgentDeps) : AgentTool {
        override val spec = ToolSpec("system.status", "CLU's own state: the time, the model, memory use.", "system.read", Category.A, emptyMap(),
            mapOf("text" to Field("string", required = true, maxLength = 4000)), setOf(AgentRole.KNOWLEDGE, AgentRole.TOOL, AgentRole.VERIFIER, AgentRole.RESEARCH), 5_000)
        override fun resources(args: JsonObject) = listOf("system")
        override fun intent(args: JsonObject) = Intent("Read CLU's own status", true, "A short status text", "Nothing changes", resources(args))
        override suspend fun run(args: JsonObject, ctx: ToolContext) = obj("text" to d.status().entries.joinToString("\n") { (k, v) -> "$k: ${(v as? JsonPrimitive)?.content ?: v}" })
    }

    class MemorySearch(private val d: AgentDeps) : AgentTool {
        override val spec = ToolSpec("memory.search", "Search the owner's memories and imported documents. Returns matching items with where they came from.", "memory.read", Category.A,
            mapOf("query" to Field("string", required = true, maxLength = 300), "limit" to Field("integer", min = 1, max = 10)), textOut, setOf(AgentRole.KNOWLEDGE), 15_000,
            untrustedOutput = true, auditFields = setOf("query"))
        override fun resources(args: JsonObject) = listOf("memory")
        override fun intent(args: JsonObject) = Intent("Search your memories for \"${args.str("query")}\"", true, "Matching memories and document passages, read-only", "Nothing changes", resources(args))
        override suspend fun run(args: JsonObject, ctx: ToolContext): JsonObject {
            val mem = d.memory ?: throw ToolFailure("Memory is not available.", "unavailable")
            val hits = withContext(Dispatchers.IO) { mem.recall(RecallRequest(args.str("query")!!, limit = args.int("limit") ?: 5, touch = false)) }
            val lines = hits.mapIndexed { i, h ->
                if (h.memory != null) "[${i + 1}] memory \"${h.memory.title}\" (${h.memory.category.name.lowercase()}, id ${h.memory.id.take(8)}): ${h.memory.content.take(400)}"
                else "[${i + 1}] document \"${h.passage!!.document.name}\": ${h.passage.text.take(400)}" }
            return obj("text" to (if (lines.isEmpty()) "No matching memories or documents." else lines.joinToString("\n")), "count" to hits.size)
        }
    }

    /** Never saves a memory: it adds a suggestion the owner sees under Suggestions and approves or rejects, as for every inferred memory. */
    class MemorySuggest(private val d: AgentDeps) : AgentTool {
        override val spec = ToolSpec("memory.suggest", "Suggest something worth remembering. The owner decides; nothing is saved until they approve it.", "memory.suggest", Category.A,
            mapOf("text" to Field("string", required = true, maxLength = 600), "why" to Field("string", maxLength = 300)),
            mapOf("text" to Field("string", required = true, maxLength = 600), "count" to Field("integer")), setOf(AgentRole.KNOWLEDGE), 5_000, dataFields = setOf("text"), auditFields = setOf("why"))
        override fun resources(args: JsonObject) = listOf("suggestions")
        override fun intent(args: JsonObject) = Intent("Suggest a memory: \"${args.str("text")?.take(80)}\"", true, "A suggestion appears under Suggestions; nothing is saved until you approve it", "Nothing changes", resources(args))
        override suspend fun run(args: JsonObject, ctx: ToolContext): JsonObject {
            val repo = d.proposals ?: throw ToolFailure("Suggestions are not available.", "unavailable")
            val p = repo.propose(ProposalKind.OBSERVATION, args.str("text")!!, args.str("why")?.ifBlank { null } ?: "Suggested while working on a task", sourceRef = "task:${ctx.taskId}")
            return obj("text" to (if (p != null) "Suggested for your approval." else "Not suggested (suggestions are off, or this repeats an earlier one)."), "count" to (if (p != null) 1 else 0))
        }
    }

    class FsList(private val d: AgentDeps) : AgentTool {
        override val spec = ToolSpec("fs.list", "List the files in an allowed folder.", "fs.read", Category.A, mapOf("path" to Field("string", maxLength = 500)), textOut,
            setOf(AgentRole.TOOL, AgentRole.VERIFIER), 10_000, untrustedOutput = true, auditFields = setOf("path"))
        override fun resources(args: JsonObject) = listOf(d.roots.resolve(args.str("path")?.ifBlank { null } ?: ".", write = false).toString())
        override fun intent(args: JsonObject) = Intent("List the files in ${args.str("path") ?: "the workspace"}", true, "File names, read-only", "Nothing changes", resources(args))
        override suspend fun run(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
            val dir = d.roots.resolve(args.str("path")?.ifBlank { null } ?: ".", write = false).toFile()
            if (!dir.isDirectory) throw ToolFailure("${dir.name} is not a folder.", "not_a_folder")
            val kids = (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".clu-trash") }.sortedBy { it.name.lowercase() }
            obj("text" to (if (kids.isEmpty()) "(empty)" else kids.take(200).joinToString("\n") { (if (it.isDirectory) "[folder] " else "") + it.name + (if (it.isFile) " (${it.length()} bytes)" else "") }), "count" to kids.size)
        }
    }

    class FsRead(private val d: AgentDeps) : AgentTool {
        override val spec = ToolSpec("fs.read", "Read a text file in an allowed folder.", "fs.read", Category.A,
            mapOf("path" to Field("string", required = true, maxLength = 500), "maxChars" to Field("integer", min = 100, max = 20_000)),
            mapOf("text" to Field("string", required = true, maxLength = 40_000), "bytes" to Field("integer"), "truncated" to Field("boolean")), setOf(AgentRole.TOOL, AgentRole.VERIFIER), 10_000,
            untrustedOutput = true, auditFields = setOf("path"))
        override fun resources(args: JsonObject) = listOf(d.roots.resolve(args.str("path")!!, write = false).toString())
        override fun intent(args: JsonObject) = Intent("Read the file ${args.str("path")}", true, "Its text, read-only", "Nothing changes", resources(args))
        override suspend fun run(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
            val f = d.roots.resolve(args.str("path")!!, write = false).toFile()
            if (!f.isFile) throw ToolFailure("${f.name} is not a file.", "not_a_file")
            if (f.length() > 2_000_000) throw ToolFailure("${f.name} is larger than 2 MB; CLU's tasks do not read files that large.", "too_large")
            val bytes = f.readBytes()
            if (bytes.any { it.toInt() == 0 }) throw ToolFailure("${f.name} is not a text file.", "binary")
            val max = args.int("maxChars") ?: 8000; val text = String(bytes, Charsets.UTF_8)
            obj("text" to text.take(max), "bytes" to bytes.size, "truncated" to (text.length > max))
        }
    }

    class FsStat(private val d: AgentDeps) : AgentTool {
        override val spec = ToolSpec("fs.stat", "Whether a file or folder exists, how large it is and its checksum (used to verify other steps).", "fs.read", Category.A,
            mapOf("path" to Field("string", required = true, maxLength = 500)),
            mapOf("text" to Field("string", required = true, maxLength = 400), "exists" to Field("boolean", required = true), "isDirectory" to Field("boolean"), "bytes" to Field("integer"), "sha256" to Field("string", maxLength = 64)),
            setOf(AgentRole.VERIFIER, AgentRole.TOOL), 10_000, auditFields = setOf("path"))
        override fun resources(args: JsonObject) = listOf(d.roots.resolve(args.str("path")!!, write = false).toString())
        override fun intent(args: JsonObject) = Intent("Check ${args.str("path")}", true, "Whether it exists, its size and checksum", "Nothing changes", resources(args))
        override suspend fun run(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
            val f = d.roots.resolve(args.str("path")!!, write = false).toFile()
            if (!f.exists()) return@withContext obj("text" to "${f.name} does not exist", "exists" to false)
            if (f.isDirectory) return@withContext obj("text" to "${f.name} is a folder", "exists" to true, "isDirectory" to true)
            obj("text" to "${f.name} exists, ${f.length()} bytes", "exists" to true, "isDirectory" to false, "bytes" to f.length(), "sha256" to (if (f.length() <= 50_000_000) sha256(f.readBytes()) else ""))
        }
    }

    class FsMkdir(private val d: AgentDeps) : AgentTool {
        override val spec = ToolSpec("fs.mkdir", "Create a new folder inside an allowed folder.", "fs.write", Category.B, mapOf("path" to Field("string", required = true, maxLength = 500)),
            mapOf("text" to Field("string", required = true, maxLength = 400), "created" to Field("boolean")), setOf(AgentRole.TOOL), 10_000, mutates = true, auditFields = setOf("path"))
        override fun resources(args: JsonObject) = listOf(d.roots.resolve(args.str("path")!!, write = true).toString())
        override fun intent(args: JsonObject) = Intent("Create the folder ${args.str("path")}", true, "The folder exists (an empty folder is easy to remove)", "Nothing is created", resources(args))
        override suspend fun run(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
            val f = d.roots.resolve(args.str("path")!!, write = true).toFile()
            if (f.isFile) throw ToolFailure("${f.name} is a file; a folder with that name cannot be created.", "exists")
            val created = !f.exists(); f.mkdirs(); if (!f.isDirectory) throw ToolFailure("The folder could not be created.", "io")
            obj("text" to (if (created) "Created ${f.name}" else "${f.name} already existed"), "created" to created)
        }
        override fun verify(args: JsonObject, output: JsonObject) = VerifyPlan("fs.stat", obj("path" to args.str("path")), "the folder exists") { it["exists"]?.jsonPrimitive?.booleanOrNull == true && it["isDirectory"]?.jsonPrimitive?.booleanOrNull == true }
    }

    class FsWrite(private val d: AgentDeps) : AgentTool {
        override val spec = ToolSpec("fs.write", "Create a new text file, append to one, or replace one (replacing needs explicit confirmation and keeps the old version).", "fs.write", Category.B,
            mapOf("path" to Field("string", required = true, maxLength = 500), "content" to Field("string", required = true, maxLength = 100_000), "mode" to Field("string", choices = listOf("create", "append", "replace"))),
            mapOf("text" to Field("string", required = true, maxLength = 400), "bytes" to Field("integer"), "sha256" to Field("string", maxLength = 64), "backup" to Field("string", maxLength = 500)),
            setOf(AgentRole.TOOL), 20_000, mutates = true, dataFields = setOf("content"), auditFields = setOf("path", "mode"))
        private fun mode(a: JsonObject) = a.str("mode") ?: "create"
        override fun classify(args: JsonObject): Category = if (mode(args) == "replace") Category.C else Category.B
        override fun resources(args: JsonObject) = listOf(d.roots.resolve(args.str("path")!!, write = true).toString())
        override fun intent(args: JsonObject): Intent {
            val n = args.str("content")!!.length
            return when (mode(args)) {
                "replace" -> Intent("Replace the file ${args.str("path")} with new content ($n characters)", true, "The file has the new content; the old version is kept in the task trash folder", "The file is unchanged", resources(args))
                "append" -> Intent("Add $n characters to the end of ${args.str("path")}", false, "The text is added to the file", "The file is unchanged", resources(args))
                else -> Intent("Create the new file ${args.str("path")} ($n characters)", true, "The file exists with that content (it can be deleted again)", "No file is created", resources(args))
            }
        }
        override suspend fun run(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
            val p = d.roots.resolve(args.str("path")!!, write = true); val f = p.toFile(); val content = args.str("content")!!; val m = mode(args)
            if (f.isDirectory) throw ToolFailure("${f.name} is a folder.", "is_folder")
            if (m == "create" && f.exists()) throw ToolFailure("${f.name} already exists. (Replacing a file needs the \"replace\" mode, which asks for your confirmation.)", "exists")
            if (m != "create" && !f.exists()) throw ToolFailure("${f.name} does not exist, so there is nothing to ${m}.", "missing")
            p.parent?.let { Files.createDirectories(it) }
            var backup = ""
            if (m == "replace") backup = trash(f, "versions", copy = true)
            val newBytes = if (m == "append") f.readBytes() + content.toByteArray() else content.toByteArray()
            val tmp = Files.createTempFile(p.parent, ".clu-write-", ".tmp")
            try { Files.write(tmp, newBytes); Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING) } finally { Files.deleteIfExists(tmp) }
            obj("text" to "Wrote ${f.name} (${newBytes.size} bytes)", "bytes" to newBytes.size, "sha256" to sha256(newBytes), "backup" to backup)
        }
        override fun verify(args: JsonObject, output: JsonObject) = VerifyPlan("fs.stat", obj("path" to args.str("path")), "the file exists with the written content") {
            it["exists"]?.jsonPrimitive?.booleanOrNull == true && it.str("sha256") == output.str("sha256") }

        /** Keeps what is replaced or deleted, inside the same allowed folder, so nothing is lost for good. */
        private fun trash(f: File, sub: String, copy: Boolean): String {
            val root = d.roots.list().map { Path.of(it.path) }.firstOrNull { f.toPath().toAbsolutePath().startsWith(it) } ?: throw PathRefused("outside the allowed folders")
            val dir = root.resolve(".clu-trash").resolve(sub).also { Files.createDirectories(it) }
            val dest = dir.resolve("${d.clock.now()}-${f.name}")
            if (copy) Files.copy(f.toPath(), dest) else Files.move(f.toPath(), dest)
            return dest.toString()
        }
    }

    class FsDelete(private val d: AgentDeps) : AgentTool {
        override val spec = ToolSpec("fs.delete", "Delete a file or folder. It is moved to the task trash folder (.clu-trash), not erased, so it can be recovered.", "fs.delete", Category.C,
            mapOf("path" to Field("string", required = true, maxLength = 500)), mapOf("text" to Field("string", required = true, maxLength = 400), "movedTo" to Field("string", maxLength = 500)),
            setOf(AgentRole.TOOL), 20_000, mutates = true, auditFields = setOf("path"))
        override fun resources(args: JsonObject) = listOf(d.roots.resolve(args.str("path")!!, write = true).toString())
        override fun intent(args: JsonObject) = Intent("Delete ${args.str("path")}", true, "It is moved to the .clu-trash folder next to it; you can move it back", "Nothing is deleted", resources(args))
        override suspend fun run(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
            val p = d.roots.resolve(args.str("path")!!, write = true); val f = p.toFile()
            if (!f.exists()) throw ToolFailure("${f.name} does not exist.", "missing")
            if (d.roots.list().any { Path.of(it.path) == p }) throw PathRefused("An allowed folder itself cannot be deleted by a task")
            if (p.any { it.toString() == ".clu-trash" }) throw PathRefused("The trash folder is not touched by tasks")
            val root = d.roots.list().map { Path.of(it.path) }.first { p.startsWith(it) }
            val dir = root.resolve(".clu-trash").resolve("deleted").also { Files.createDirectories(it) }
            val dest = dir.resolve("${d.clock.now()}-${f.name}"); Files.move(p, dest)
            obj("text" to "Moved ${f.name} to the trash folder", "movedTo" to dest.toString())
        }
        override fun verify(args: JsonObject, output: JsonObject) = VerifyPlan("fs.stat", obj("path" to args.str("path")), "the item is gone from its place") { it["exists"]?.jsonPrimitive?.booleanOrNull == false }
    }

    class WebSearch(private val d: AgentDeps) : AgentTool {
        override val spec = ToolSpec("web.search", "Search the web. The search words are sent to the search provider chosen in CLU's settings.", "net.search", Category.B,
            mapOf("query" to Field("string", required = true, maxLength = 200)), textOut, setOf(AgentRole.RESEARCH), 40_000, untrustedOutput = true, auditFields = setOf("query"), environment = "network")
        override fun resources(args: JsonObject) = listOf("search:" + args.str("query"))
        override fun intent(args: JsonObject) = Intent("Search the web for \"${args.str("query")}\" (the words are sent to your search provider)", true, "A list of results with sources", "Nothing is sent or changed", resources(args))
        override suspend fun run(args: JsonObject, ctx: ToolContext): JsonObject {
            val s = d.search ?: throw ToolFailure("Web search is not available.", "unavailable")
            val o = s.search(args.str("query")!!)
            if (o.hits.isEmpty()) throw ToolFailure(o.error ?: "No results.", "no_results")
            return obj("text" to o.hits.take(6).mapIndexed { i, h -> "[${i + 1}] ${h.title} (${h.url}): ${h.snippet.take(300)}" }.joinToString("\n"), "count" to o.hits.size)
        }
    }

    class WebFetch(private val d: AgentDeps) : AgentTool {
        override val spec = ToolSpec("web.fetch", "Read the relevant part of one public web page (only if the site's robots.txt allows it).", "net.fetch", Category.B,
            mapOf("url" to Field("string", required = true, maxLength = 500), "focus" to Field("string", maxLength = 200)),
            mapOf("text" to Field("string", required = true, maxLength = 40_000), "url" to Field("string", maxLength = 500)), setOf(AgentRole.RESEARCH), 30_000, untrustedOutput = true, auditFields = setOf("url"), environment = "network")
        override fun resources(args: JsonObject): List<String> {
            val u = args.str("url")!!; if (!(u.startsWith("https://") || u.startsWith("http://"))) throw PathRefused("Only http and https addresses can be read"); return listOf(u)
        }
        override fun intent(args: JsonObject) = Intent("Read the web page ${args.str("url")}", true, "An excerpt of the page", "Nothing is changed", resources(args))
        override suspend fun run(args: JsonObject, ctx: ToolContext): JsonObject {
            val s = d.search ?: throw ToolFailure("Reading web pages is not available.", "unavailable")
            val r = s.readPage(args.str("url")!!, args.str("focus") ?: "", 4000)
            return obj("text" to (r.text ?: throw ToolFailure("The page could not be read: ${r.note ?: "no reason given"}", "unreadable")), "url" to args.str("url"))
        }
    }

    /**
     * Runs a program the owner explicitly allowed (an absolute path in CLU's allow-list), with an argument list (never through a
     * shell), in an allowed folder, with a time limit and capped output. Off unless the owner switches it on; always asks for confirmation.
     * This is not a sandbox: an allowed program can do whatever that program can do, which is why each call is confirmed and the list starts empty.
     */
    class ProcRun(private val d: AgentDeps) : AgentTool {
        override val spec = ToolSpec("proc.run", "Run an allowed program (never a shell). Off by default; every run needs your confirmation.", "proc.run", Category.C,
            mapOf("exe" to Field("string", required = true, maxLength = 500), "args" to Field("array", itemType = "string", maxLength = 300, maxItems = 20), "cwd" to Field("string", maxLength = 500),
                "timeoutSeconds" to Field("integer", min = 1, max = 60)),
            mapOf("text" to Field("string", required = true, maxLength = 20_000), "exitCode" to Field("integer")), setOf(AgentRole.TOOL), 70_000, mutates = true, untrustedOutput = true,
            environment = "restricted-process", defaultEnabled = false, auditFields = setOf("exe", "args", "cwd"))
        private fun allowed(exe: String): File {
            val want = runCatching { File(exe).canonicalFile }.getOrNull() ?: throw PathRefused("Not a valid program path")
            if (!File(exe).isAbsolute) throw PathRefused("Programs must be given by their full path")
            val ok = d.shellAllowlist().any { runCatching { File(it).canonicalFile == want }.getOrDefault(false) }
            if (!ok) throw PathRefused("${want.name} is not in CLU's list of programs tasks may run (Agents > Permissions)")
            if (!want.isFile) throw PathRefused("${want.name} was not found")
            return want
        }
        override fun resources(args: JsonObject): List<String> {
            val exe = allowed(args.str("exe")!!); val cwd = d.roots.resolve(args.str("cwd")?.ifBlank { null } ?: ".", write = true)
            return listOf("run:${exe.path} ${(args["args"] as? JsonArray)?.joinToString(" ") { it.jsonPrimitive.content }.orEmpty()} in $cwd")
        }
        override fun intent(args: JsonObject) = Intent("Run the program ${File(args.str("exe")!!).name} ${(args["args"] as? JsonArray)?.joinToString(" ") { it.jsonPrimitive.content }.orEmpty()}", false,
            "The program runs in your task folder and its output is shown", "Nothing is run", resources(args))
        override suspend fun run(args: JsonObject, ctx: ToolContext): JsonObject {
            val exe = allowed(args.str("exe")!!); val cwd = d.roots.resolve(args.str("cwd")?.ifBlank { null } ?: ".", write = true).toFile()
            val argv = listOf(exe.path) + ((args["args"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty())
            val limit = (args.int("timeoutSeconds") ?: 30).toLong()
            return withContext(Dispatchers.IO) {
                val pb = ProcessBuilder(argv).directory(cwd).redirectErrorStream(true)
                pb.environment().keys.retainAll(setOf("PATH", "SystemRoot", "SYSTEMROOT", "TEMP", "TMP", "HOME"))
                val p = pb.start(); p.outputStream.close()
                val sb = StringBuilder(); val reader = Thread { p.inputStream.bufferedReader().use { r -> val buf = CharArray(2048); while (true) { val n = r.read(buf); if (n < 0) break; if (sb.length < 20_000) synchronized(sb) { sb.append(buf, 0, n) } } } }.also { it.isDaemon = true; it.start() }
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(limit)
                try {
                    while (p.isAlive) { if (System.nanoTime() > end) throw ToolFailure("The program ran longer than $limit seconds and was stopped.", "timeout"); if (ctx.isCancelled() || !coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("cancelled"); delay(50) }
                } finally { if (p.isAlive) { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() }; reader.join(1000) }
                obj("text" to synchronized(sb) { sb.toString().take(20_000) }.ifEmpty { "(no output)" }, "exitCode" to p.exitValue())
            }
        }
    }
}
