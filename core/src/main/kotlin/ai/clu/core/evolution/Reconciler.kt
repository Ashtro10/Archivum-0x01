package ai.clu.core.evolution

import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Recognises development that happened outside CLU (the owner, Claude Code, any editor) as legitimate evolution of its
 * software: new commits are recorded with what they touched, the index is brought up to date, and uncommitted work is
 * noted but never touched. Nothing here reverts, "repairs" or flags a change because CLU did not make it.
 *
 * Provenance: a commit is CLU's own (`clu-repair`) only if it carries the trailer `CLU-Repair: <id>` **and** that id is
 * a repair CLU recorded; everything else is `external`.
 */
class Reconciler(private val store: EvolutionStore, private val indexer: SourceIndexer) {
    private val db = store.db

    data class Result(val head: String, val branch: String, val newCommits: List<CommitSummary>, val dirty: List<String>, val index: IndexStats,
                      val headMoved: Boolean, val firstRun: Boolean) {
        fun describe(): String = buildString {
            if (firstRun) append("First look at the repository: indexed ${index.files} files (${index.symbols} declarations) in ${index.durationMs} ms at ${head.take(10)} on $branch.")
            else if (!headMoved && index.added + index.changed + index.removed == 0) append("No change since the last check (${head.take(10)} on $branch).")
            else {
                append("${newCommits.size} new commit(s) on $branch, now at ${head.take(10)}. ")
                append("Index: ${index.added} added, ${index.changed} changed, ${index.removed} removed file(s).")
                newCommits.takeLast(5).forEach { append("\n- ${it.hash.take(10)} ${it.subject} (${it.provenance}; ${it.categories.joinToString().ifEmpty { "code" }}; ${it.files} file(s))") }
            }
            if (dirty.isNotEmpty()) append("\nUncommitted changes in ${dirty.size} file(s) (left untouched): ${dirty.take(8).joinToString()}${if (dirty.size > 8) ", …" else ""}")
        }
    }
    data class CommitSummary(val hash: String, val author: String, val subject: String, val provenance: String, val categories: List<String>, val files: Int)

    fun reconcile(repo: RepoReader, isOwnRepair: (String) -> Boolean = { false }): Result {
        val head = repo.head(); val branch = repo.branch()
        val last = db.query("SELECT head FROM repo_snapshots WHERE repo = ? ORDER BY at DESC, rowid DESC LIMIT 1", listOf(repo.root)) { it.str(0) }.firstOrNull()
        val firstRun = last == null
        // On the first run, record recent history only (the latest 20), so CLU knows its latest development updates.
        val commits = if (last == head) emptyList() else repo.commitsSince(last, if (firstRun) 20 else 500).filter { seen(it.hash).not() }
        val summaries = commits.map { c ->
            val repairId = Regex("(?m)^CLU-Repair:\\s*(\\S+)").find(c.body)?.groupValues?.get(1)
            val provenance = if (repairId != null && isOwnRepair(repairId)) "clu-repair" else "external"
            val cats = categorize(c.changes.map { it.path } + c.changes.mapNotNull { it.oldPath })
            db.execute("INSERT OR IGNORE INTO commits_seen(hash, author, at, subject, provenance, files, categories, recorded_at) VALUES(?,?,?,?,?,?,?,?)",
                listOf(c.hash, c.author, c.at, c.subject.take(300), provenance,
                    CluJson.encodeToString(JsonArray.serializer(), JsonArray(c.changes.map { JsonPrimitive("${it.status} ${it.oldPath?.let { o -> "$o -> " } ?: ""}${it.path}") })),
                    cats.joinToString(","), store.clock.now()))
            if (!firstRun) store.history("external-change", c.hash, "${if (provenance == "external") "External" else "CLU's own"} commit ${c.hash.take(10)} by ${c.author}: ${c.subject}",
                "categories: ${cats.joinToString().ifEmpty { "code" }}\n" + c.changes.joinToString("\n") { "${it.status} ${it.oldPath?.let { o -> "$o -> " } ?: ""}${it.path}" })
            CommitSummary(c.hash, c.author, c.subject, provenance, cats, c.changes.size)
        }
        val dirty = repo.dirty()
        val idx = indexer.update(repo) { p -> if (p in dirty) "external-uncommitted" else "external" }
        db.execute("INSERT INTO repo_snapshots(id, repo, head, branch, dirty_files, files, at) VALUES(?,?,?,?,?,?,?)",
            listOf(newId(), repo.root, head, branch, dirty.joinToString("\n"), idx.files, store.clock.now()))
        val result = Result(head, branch, summaries, dirty, idx, last != null && last != head, firstRun)
        if (firstRun || result.headMoved || idx.added + idx.changed + idx.removed > 0) store.history("reconcile", head, result.describe().lineSequence().first(), result.describe())
        return result
    }

    private fun seen(hash: String) = db.query("SELECT 1 FROM commits_seen WHERE hash = ?", listOf(hash)) { 1 }.isNotEmpty()

    /** Recent commits CLU knows about, newest first. */
    fun recentCommits(limit: Int = 10): List<CommitSummary> = db.query(
        "SELECT hash, author, subject, provenance, categories, files FROM commits_seen ORDER BY at DESC LIMIT ${limit.coerceIn(1, 200)}") {
        CommitSummary(it.str(0), it.str(1), it.str(2), it.str(3), it.str(4).split(',').filter { s -> s.isNotBlank() },
            runCatching { CluJson.parseToJsonElement(it.str(5)).let { e -> (e as JsonArray).size } }.getOrDefault(0)) }

    fun commitFiles(hash: String): List<String> = db.query("SELECT files FROM commits_seen WHERE hash LIKE ?", listOf("$hash%")) { it.str(0) }.firstOrNull()
        ?.let { runCatching { (CluJson.parseToJsonElement(it) as JsonArray).map { e -> (e as JsonPrimitive).content } }.getOrNull() }.orEmpty()

    fun lastSnapshotAt(): Long? = db.query("SELECT at FROM repo_snapshots ORDER BY at DESC LIMIT 1") { it.lng(0) }.firstOrNull()

    companion object {
        /** What kind of change a set of paths is, for the development history. */
        fun categorize(paths: List<String>): List<String> {
            val c = linkedSetOf<String>()
            for (p in paths) when {
                p.endsWith(".gradle.kts") || p.startsWith("gradle/") || p == "gradle.properties" || p.startsWith("buildSrc/") -> c += "build/dependencies"
                p.endsWith("db/Schema.kt") -> c += "database schema"
                p.endsWith("config/Settings.kt") || p.endsWith("HostConfig.kt") -> c += "configuration"
                p.endsWith("Routes.kt") || p.endsWith("ApiServer.kt") || p.startsWith("contract/") -> c += "API"
                "/src/test/" in p -> c += "tests"
                p.endsWith(".md") -> c += "documentation"
                p.startsWith("native/") -> c += "native code"
                p.startsWith("installer/") -> c += "installer"
            }
            return c.toList()
        }
    }
}
