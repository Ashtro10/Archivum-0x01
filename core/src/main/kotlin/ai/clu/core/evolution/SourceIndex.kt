package ai.clu.core.evolution

import ai.clu.core.db.lng
import ai.clu.core.db.str

/**
 * What the self-knowledge layer needs from a source repository. The desktop host implements it with the Git command
 * line ([ai.clu.desktop.evolution.GitCli]); tests implement it in memory. Read-only.
 */
interface RepoReader {
    val root: String
    /** Tracked files with their Git blob ids (content hashes), plus untracked-but-not-ignored files (hash of content). */
    fun files(): Map<String, String>
    fun read(path: String): String?
    fun head(): String
    fun branch(): String
    /** Paths with uncommitted changes (modified, added, deleted, untracked), relative to the root. */
    fun dirty(): List<String>
    /** Commits after [since] (exclusive; null = the last [limit]), oldest first. */
    fun commitsSince(since: String?, limit: Int = 200): List<CommitInfo>
}

data class CommitInfo(val hash: String, val author: String, val at: Long, val subject: String, val body: String, val changes: List<FileChange>)
data class FileChange(val status: Char, val path: String, val oldPath: String? = null)

/** One declaration found in a Kotlin source file. */
data class Declaration(val name: String, val kind: String, val line: Int, val doc: String)
data class ScannedKotlin(val pkg: String, val imports: List<String>, val declarations: List<Declaration>)

/**
 * A lexer-level Kotlin reader: strings and comments are blanked out first (so a `class` inside a string or a comment
 * is not a declaration), then package, imports and declarations are read with their line numbers. KDoc right above a
 * declaration is kept as its one-line description. It does not resolve types: references are approximate by design.
 */
object KotlinScanner {
    private val DECL = Regex("""(?:^|[{;])[ \t]*((?:(?:public|private|internal|protected|open|abstract|sealed|data|enum|annotation|inline|value|inner|override|suspend|operator|infix|tailrec|external|const|lateinit|actual|expect|fun)\s+)*)(class|interface|object|fun|typealias)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)""", RegexOption.MULTILINE)

    fun scan(source: String): ScannedKotlin {
        val clean = blank(source)
        val pkg = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE).find(clean)?.groupValues?.get(1) ?: ""
        val imports = Regex("""^\s*import\s+([\w.*]+)""", RegexOption.MULTILINE).findAll(clean).map { it.groupValues[1] }.toList()
        val lineStarts = IntArray(source.length + 1).also { var line = 1; for (i in source.indices) { it[i] = line; if (source[i] == '\n') line++ }; it[source.length] = line }
        val decls = DECL.findAll(clean).mapNotNull { m ->
            val mods = m.groupValues[1]; var kind = m.groupValues[2]; val name = m.groupValues[3]
            if (kind == "fun" && name.isEmpty()) return@mapNotNull null
            if (kind == "class") kind = when { "enum" in mods -> "enum"; "data" in mods -> "data class"; "annotation" in mods -> "annotation"; else -> "class" }
            if (kind == "interface" && "fun" in mods) kind = "fun interface"
            val start = (m.groups[1]?.takeIf { it.value.isNotEmpty() } ?: m.groups[2]!!).range.first
            Declaration(name, kind, lineStarts[start.coerceAtMost(source.length)], docAbove(source, start))
        }.toList()
        return ScannedKotlin(pkg, imports, decls)
    }

    /** The first sentence of a KDoc comment ending right above [pos]. */
    private fun docAbove(source: String, pos: Int): String {
        val before = source.substring(0, pos).trimEnd()
        if (!before.endsWith("*/")) return ""
        val open = before.lastIndexOf("/**"); if (open < 0) return ""
        val text = before.substring(open + 3, before.length - 2).lines().joinToString(" ") { it.trim().removePrefix("*").trim() }.replace(Regex("\\s+"), " ").trim()
        return text.substringBefore(". ").removeSuffix(".").take(200)
    }

    /** Comments and string contents replaced by spaces (newlines kept), so offsets and line numbers stay the same. */
    fun blank(s: String): String {
        val out = StringBuilder(s.length); var i = 0
        fun space(c: Char) = if (c == '\n') '\n' else ' '
        while (i < s.length) {
            val c = s[i]
            when {
                s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') { out.append(' '); i++ }
                s.startsWith("/*", i) -> { var depth = 0
                    while (i < s.length) {
                        if (s.startsWith("/*", i)) { depth++; out.append("  "); i += 2; continue }
                        if (s.startsWith("*/", i)) { depth--; out.append("  "); i += 2; if (depth == 0) break; continue }
                        out.append(space(s[i])); i++ } }
                s.startsWith("\"\"\"", i) -> { out.append("\"\"\""); i += 3
                    while (i < s.length && !s.startsWith("\"\"\"", i)) { out.append(space(s[i])); i++ }
                    if (i < s.length) { out.append("\"\"\""); i += 3 } }
                c == '"' -> { out.append('"'); i++
                    while (i < s.length && s[i] != '"' && s[i] != '\n') { if (s[i] == '\\' && i + 1 < s.length) { out.append("  "); i += 2 } else { out.append(' '); i++ } }
                    if (i < s.length && s[i] == '"') { out.append('"'); i++ } }
                c == '\'' && i + 2 < s.length && (s[i + 2] == '\'' || s[i + 1] == '\\') -> { val end = s.indexOf('\'', i + 2).takeIf { it in i + 2..i + 8 } ?: (i + 1)
                    repeat(end - i + 1) { out.append(' ') }; i = end + 1 }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}

/** The index as CLU queries it. */
data class IndexedFile(val path: String, val module: String, val pkg: String, val kind: String, val language: String, val lines: Int, val provenance: String)
data class SymbolHit(val file: String, val name: String, val kind: String, val line: Int, val fqname: String, val doc: String)
data class IndexStats(val files: Int, val added: Int, val changed: Int, val removed: Int, val symbols: Int, val durationMs: Long, val head: String)

/**
 * The persistent, incremental index of CLU's own source. A file is re-read only when its Git blob id changed, so
 * keeping the index current after a commit costs milliseconds, not a re-read of the repository.
 */
class SourceIndexer(private val store: EvolutionStore) {
    private val db = store.db

    fun update(repo: RepoReader, provenanceOf: (String) -> String = { "external" }): IndexStats {
        val t0 = System.nanoTime()
        val files = repo.files().filterKeys { indexable(it) }
        val known = db.query("SELECT path, blob FROM source_files") { it.str(0) to it.str(1) }.toMap()
        val head = repo.head()
        var added = 0; var changed = 0
        val removed = known.keys - files.keys
        db.transaction {
            for (p in removed) deleteFile(p)
            for ((path, blob) in files) {
                val old = known[path]
                if (old == blob) continue
                if (old == null) added++ else changed++
                deleteFile(path)
                // Unreadable (too large, not a regular file): still recorded, so it is not "added" again on every check.
                insertFile(path, blob, repo.read(path) ?: "", provenanceOf(path), head)
            }
            // The module graph is rebuilt only when a build file changed (or on the first run).
            val touched = removed + files.filter { (p, b) -> known[p] != b }.keys
            if (known.isEmpty() || touched.any { it.endsWith(".gradle.kts") }) indexBuildGraph(repo)
            store.setMeta("indexed_head", head)
            store.setMeta("indexed_at", store.clock.now().toString())
            store.setMeta("repo_root", repo.root)
        }
        val symbols = db.query("SELECT COUNT(*) FROM symbols") { it.lng(0) }.first().toInt()
        return IndexStats(files.size, added, changed, removed.size, symbols, (System.nanoTime() - t0) / 1_000_000, head)
    }

    private fun deleteFile(path: String) {
        db.execute("DELETE FROM source_files WHERE path = ?", listOf(path))
        db.execute("DELETE FROM symbols WHERE file = ?", listOf(path))
        db.execute("DELETE FROM file_imports WHERE file = ?", listOf(path))
    }

    private fun insertFile(path: String, blob: String, text: String, provenance: String, head: String) {
        val language = languageOf(path)
        val scanned = if (language == "kotlin") KotlinScanner.scan(text) else null
        val pkg = scanned?.pkg ?: ""
        db.execute("INSERT INTO source_files(path, blob, module, package, kind, language, lines, provenance, last_commit, indexed_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            listOf(path, blob, moduleOf(path), pkg, kindOf(path), language, text.count { it == '\n' } + 1, provenance, head, store.clock.now()))
        scanned?.declarations?.forEach { d ->
            db.execute("INSERT INTO symbols(file, name, kind, line, fqname, doc) VALUES(?,?,?,?,?,?)", listOf(path, d.name, d.kind, d.line, if (pkg.isEmpty()) d.name else "$pkg.${d.name}", d.doc))
        }
        scanned?.imports?.forEach { db.execute("INSERT INTO file_imports(file, target) VALUES(?,?)", listOf(path, it)) }
    }

    /** Module-level dependencies from Gradle (`project(":x")`) and the module list from settings.gradle.kts. */
    private fun indexBuildGraph(repo: RepoReader) {
        db.execute("DELETE FROM module_deps")
        val settings = repo.read("settings.gradle.kts").orEmpty()
        val modules = Regex("\"(:[\\w:-]+)\"").findAll(settings.substringAfter("include(", "")).map { it.groupValues[1].removePrefix(":").replace(':', '/') }.toList()
        store.setMeta("modules", modules.joinToString(","))
        for (m in modules) {
            val build = repo.read("$m/build.gradle.kts") ?: continue
            Regex("""(implementation|api|compileOnly|testImplementation|runtimeOnly)\s*\(\s*project\(\s*"(:[\w:-]+)"""").findAll(build).forEach {
                db.execute("INSERT OR IGNORE INTO module_deps(from_module, to_module, kind) VALUES(?,?,?)", listOf(m, it.groupValues[2].removePrefix(":").replace(':', '/'), it.groupValues[1]))
            }
            // The custom Android build reads core's sources directly instead of a project() dependency.
            if (Regex("""["']\.\./core|rootProject\.file\(\s*"core/|project\(":core"\)""").containsMatchIn(build) && m != "core")
                db.execute("INSERT OR IGNORE INTO module_deps(from_module, to_module, kind) VALUES(?,?,?)", listOf(m, "core", "sources"))
        }
    }

    // ------------------------------------------------------------------ queries
    fun file(path: String): IndexedFile? = db.query("$FILE_SELECT WHERE path = ?", listOf(path), ::readFile).firstOrNull()
    fun files(module: String? = null, kind: String? = null): List<IndexedFile> {
        val where = listOfNotNull(module?.let { "module = ?" }, kind?.let { "kind = ?" })
        return db.query("$FILE_SELECT ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY path", listOfNotNull(module, kind), ::readFile)
    }
    fun modules(): List<String> = store.meta("modules")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    fun moduleDeps(): List<Triple<String, String, String>> = db.query("SELECT from_module, to_module, kind FROM module_deps ORDER BY from_module, to_module") { Triple(it.str(0), it.str(1), it.str(2)) }
    fun packages(): List<Pair<String, Int>> = db.query("SELECT package, COUNT(*) FROM source_files WHERE language = 'kotlin' AND kind = 'main' AND package != '' GROUP BY package ORDER BY package") { it.str(0) to it.lng(1).toInt() }

    /** Declarations whose name matches [query] (exact first, then prefix, then contains), main code before tests. */
    fun symbols(query: String, limit: Int = 20): List<SymbolHit> {
        val q = query.trim(); if (q.isEmpty()) return emptyList()
        return db.query("""SELECT s.file, s.name, s.kind, s.line, s.fqname, s.doc FROM symbols s JOIN source_files f ON f.path = s.file
            WHERE s.name LIKE ? ESCAPE '\' ORDER BY (s.name = ?) DESC, (s.name LIKE ? ESCAPE '\') DESC, (f.kind = 'main') DESC, length(s.name), s.file LIMIT ${limit.coerceIn(1, 500)}""",
            listOf("%${like(q)}%", q, "${like(q)}%")) { SymbolHit(it.str(0), it.str(1), it.str(2), it.lng(3).toInt(), it.str(4), it.str(5)) }
    }
    fun symbolsIn(file: String): List<SymbolHit> = db.query("SELECT file, name, kind, line, fqname, doc FROM symbols WHERE file = ? ORDER BY line", listOf(file)) {
        SymbolHit(it.str(0), it.str(1), it.str(2), it.lng(3).toInt(), it.str(4), it.str(5)) }
    /** Files that import anything from [fqPrefix] (a class or package). */
    fun importers(fqPrefix: String): List<String> = db.query("SELECT DISTINCT file FROM file_imports WHERE target = ? OR target LIKE ? ESCAPE '\\' OR target = ? ORDER BY file",
        listOf(fqPrefix, "${like(fqPrefix)}.%", "$fqPrefix.*")) { it.str(0) }
    fun imports(file: String): List<String> = db.query("SELECT target FROM file_imports WHERE file = ?", listOf(file)) { it.str(0) }

    /** Test files likely to exercise [file]: tests importing its declarations or its package, and tests named after them. */
    fun testsFor(file: String): List<String> {
        val f = file(file) ?: return emptyList()
        val names = symbolsIn(file).filter { it.kind != "fun" }.map { it.name }.ifEmpty { listOf(file.substringAfterLast('/').substringBefore('.')) }
        val byImport = names.flatMap { n -> importers(if (f.pkg.isEmpty()) n else "${f.pkg}.$n") } + (if (f.pkg.isNotEmpty()) importers(f.pkg) else emptyList())
        val tests = files(kind = "test").map { it.path }
        val byName = tests.filter { t -> names.any { n -> t.substringAfterLast('/').startsWith(n) } }
        val samePackage = if (f.pkg.isEmpty()) emptyList() else db.query("SELECT path FROM source_files WHERE kind = 'test' AND package = ?", listOf(f.pkg)) { it.str(0) }
        val usesName = tests.filter { t -> t !in byName && names.isNotEmpty() && (samePackage.contains(t) || t in byImport) }
        return (byName + byImport.filter { it in tests } + usesName).distinct()
    }

    fun indexedHead(): String? = store.meta("indexed_head")
    fun count(): Int = db.query("SELECT COUNT(*) FROM source_files") { it.lng(0) }.first().toInt()

    companion object {
        private const val FILE_SELECT = "SELECT path, module, package, kind, language, lines, provenance FROM source_files"
        private fun readFile(r: ai.clu.core.db.SqlRow) = IndexedFile(r.str(0), r.str(1), r.str(2), r.str(3), r.str(4), r.lng(5).toInt(), r.str(6))
        private fun like(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        private val SOURCE_EXT = setOf("kt", "kts", "java", "c", "cc", "cpp", "h", "js", "py", "md", "json", "nsi", "sh", "ps1", "gradle", "toml", "properties", "xml")
        /** Source we care about: no vendored third-party trees, no generated or binary files. */
        fun indexable(path: String): Boolean {
            if (path.startsWith("legacy/") || path.startsWith("toolchain/") || path.contains("/build/") || path.startsWith("build/") || path.startsWith("dist/")) return false
            if (path.startsWith("native/") && !(path.startsWith("native/src/") || path.startsWith("native/jni/") || path.count { it == '/' } == 1)) return false
            if (path.startsWith("docs/measurements/") || path.startsWith("docs/screenshots/")) return false
            return path.substringAfterLast('.', "") in SOURCE_EXT
        }
        fun languageOf(path: String) = when (path.substringAfterLast('.', "")) {
            "kt" -> "kotlin"; "kts" -> "gradle-kotlin"; "java" -> "java"; "c", "cc", "cpp", "h" -> "c/c++"; "js" -> "javascript"; "py" -> "python"
            "md" -> "markdown"; "json" -> "json"; "sh", "ps1" -> "script"; "nsi" -> "nsis"; else -> "other" }
        fun moduleOf(path: String): String = when {
            path.startsWith("testing/") -> path.split('/').take(2).joinToString("/")
            '/' in path -> path.substringBefore('/')
            else -> "(root)" }
        fun kindOf(path: String): String = when {
            "/src/test/" in path || "/src/androidTest/" in path -> "test"
            path.endsWith(".gradle.kts") || path.endsWith(".gradle") || path == "gradle.properties" -> "build"
            path.endsWith(".md") -> "doc"
            "/src/main/" in path -> "main"
            else -> "other" }
    }
}
