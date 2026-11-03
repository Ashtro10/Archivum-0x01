package ai.clu.desktop.evolution

import ai.clu.core.evolution.CommitInfo
import ai.clu.core.evolution.FileChange
import ai.clu.core.evolution.RepoReader
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class GitException(message: String) : Exception(message)

/**
 * CLU's access to a Git repository through the `git` command line (no shell, argument lists only). Reading never
 * changes anything. The only writes are to repair worktrees and repair branches, plus [integrate], which the owner
 * approves explicitly and which refuses to run over uncommitted work.
 */
class GitCli(rootDir: File) : RepoReader {
    val dir: File = rootDir.canonicalFile
    override val root: String = dir.path

    fun run(vararg args: String, cwd: File = dir, timeoutS: Long = 120, input: String? = null): String {
        val pb = ProcessBuilder(listOf("git", "-c", "core.quotepath=off", *args)).directory(cwd).redirectErrorStream(false)
        val p = try { pb.start() } catch (e: Exception) { throw GitException("git could not be started: ${e.message}. Is Git installed?") }
        if (input != null) p.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) } else p.outputStream.close()
        val out = StringBuilder(); val err = StringBuilder()
        val t1 = Thread { out.append(p.inputStream.bufferedReader(Charsets.UTF_8).readText()) }.apply { start() }
        val t2 = Thread { err.append(p.errorStream.bufferedReader(Charsets.UTF_8).readText()) }.apply { start() }
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroyForcibly(); throw GitException("git ${args.firstOrNull()} timed out") }
        t1.join(); t2.join()
        if (p.exitValue() != 0) throw GitException("git ${args.joinToString(" ").take(200)} failed (${p.exitValue()}): ${err.toString().trim().take(800)}")
        return out.toString()
    }

    fun isRepository(): Boolean = runCatching { run("rev-parse", "--is-inside-work-tree").trim() == "true" }.getOrDefault(false)
    fun topLevel(): File = File(run("rev-parse", "--show-toplevel").trim())

    override fun head(): String = run("rev-parse", "HEAD").trim()
    override fun branch(): String = run("rev-parse", "--abbrev-ref", "HEAD").trim()

    override fun files(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (rec in run("ls-files", "-s", "-z").split('\u0000')) {
            if (rec.isBlank()) continue
            val (meta, path) = rec.split('\t', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val parts = meta.split(' ')
            if (parts.size >= 2 && parts[0] != "160000") map[path] = parts[1] // skip submodules
        }
        // Uncommitted state is what is on disk: hash modified and untracked files as Git would, drop deleted ones.
        for (p in dirty()) {
            val f = File(dir, p)
            if (f.isFile) map[p] = blobId(f.readBytes()) else map.remove(p)
        }
        return map
    }

    override fun read(path: String): String? {
        val f = File(dir, path).canonicalFile
        if (!f.path.startsWith(dir.path + File.separator) || !f.isFile || f.length() > 2_000_000) return null
        return runCatching { f.readText() }.getOrNull()
    }

    override fun dirty(): List<String> = run("status", "--porcelain=v1", "-z", "--untracked-files=all").split('\u0000').let { recs ->
        val out = mutableListOf<String>(); var i = 0
        while (i < recs.size) {
            val r = recs[i]; i++
            if (r.length < 4) continue
            out += r.substring(3)
            if (r[0] == 'R' || r[0] == 'C') i++ // the source path of a rename follows
        }
        out
    }

    override fun commitsSince(since: String?, limit: Int): List<CommitInfo> {
        val range = if (since != null && runCatching { run("merge-base", "--is-ancestor", since, "HEAD"); true }.getOrDefault(false)) listOf("$since..HEAD") else listOf("-n", limit.toString(), "HEAD")
        val hashes = run("rev-list", "--reverse", *range.toTypedArray()).lines().filter { it.isNotBlank() }.takeLast(limit)
        return hashes.map { show(it) }
    }

    fun show(hash: String): CommitInfo {
        val raw = run("show", "--no-patch", "--format=%H%x1f%an%x1f%at%x1f%s%x1f%b", hash)
        val f = raw.split('\u001f')
        val changes = run("show", "--format=", "--name-status", "-M", hash).lines().filter { it.isNotBlank() }.map { l ->
            val p = l.split('\t')
            if (p[0].startsWith("R") || p[0].startsWith("C")) FileChange(p[0][0], p.getOrElse(2) { "" }, p.getOrElse(1) { null }) else FileChange(p[0].first(), p.getOrElse(1) { "" })
        }
        return CommitInfo(f[0].trim(), f.getOrElse(1) { "" }, (f.getOrElse(2) { "0" }.trim().toLongOrNull() ?: 0) * 1000, f.getOrElse(3) { "" }, f.getOrElse(4) { "" }.trim(), changes)
    }

    /** Short history of one file (for "did this change recently?"). */
    fun recentChanges(path: String, cwd: File = dir, max: Int = 3): List<String> =
        runCatching { run("log", "-n", max.toString(), "--since=30.days", "--format=%h %s", "--", path, cwd = cwd).lines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    /** When [path] last changed in a commit (epoch ms; 0 = never committed). */
    fun lastChangeAt(path: String, cwd: File = dir): Long =
        runCatching { (run("log", "-1", "--format=%ct", "--", path, cwd = cwd).trim().toLongOrNull() ?: 0L) * 1000 }.getOrDefault(0L)

    // ------------------------------------------------------------------ repair workspaces
    fun addWorktree(path: File, branch: String, base: String) { path.parentFile.mkdirs(); run("worktree", "add", "-b", branch, path.path, base, timeoutS = 300) }
    fun removeWorktree(path: File) { runCatching { run("worktree", "remove", "--force", path.path, timeoutS = 300) }; if (path.exists()) path.deleteRecursively(); runCatching { run("worktree", "prune") } }
    fun deleteBranch(branch: String) { runCatching { run("branch", "-D", branch) } }
    fun branchExists(branch: String) = runCatching { run("rev-parse", "--verify", "--quiet", "refs/heads/$branch"); true }.getOrDefault(false)

    fun diff(wt: File): String = run("diff", "--no-color", "HEAD", cwd = wt)
    fun changedFiles(wt: File): List<String> = run("diff", "--name-only", "HEAD", cwd = wt).lines().filter { it.isNotBlank() }
    /** Applies a unified diff to the worktree and its index (so new files count as part of the change). */
    fun applyPatch(wt: File, patch: String) { run("apply", "--index", "--whitespace=nowarn", "-", cwd = wt, input = patch) }
    fun commitAll(wt: File, message: String): String {
        // Tracked changes only (plus files a patch added to the index): never the links to ignored build inputs.
        run("add", "-u", cwd = wt)
        // The message goes through stdin as UTF-8: command-line arguments use the platform charset, which may not be UTF-8.
        run("-c", "user.name=CLU", "-c", "user.email=clu@localhost", "-c", "i18n.commitEncoding=UTF-8", "commit", "-q", "-F", "-", cwd = wt, input = message)
        return run("rev-parse", "HEAD", cwd = wt).trim()
    }

    /**
     * Integrates an approved repair branch into [target] in the owner's working tree. Refuses when [target] is not the
     * checked-out branch or when tracked files have uncommitted changes, so the owner's work is never overwritten.
     */
    fun integrate(repairBranch: String, target: String, message: String): String {
        val current = branch()
        if (current != target) throw GitException("the repository is on '$current', not '$target'. Check out '$target' first; nothing was changed")
        val modified = run("status", "--porcelain=v1", "--untracked-files=no").lines().filter { it.isNotBlank() }
        if (modified.isNotEmpty()) throw GitException("there are uncommitted changes in ${modified.size} tracked file(s); commit or stash them first. Nothing was changed")
        val base = run("merge-base", "HEAD", repairBranch).trim()
        if (base == head()) run("merge", "--ff-only", "-q", repairBranch)
        else run("-c", "user.name=CLU", "-c", "user.email=clu@localhost", "merge", "--no-ff", "-q", "-m", message, repairBranch)
        return head()
    }

    companion object {
        fun blobId(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-1"); md.update("blob ${bytes.size}\u0000".toByteArray()); md.update(bytes)
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Finds CLU's own source repository: an explicit path, `CLU_SOURCE_REPO`, or the nearest parent of the working directory that is CLU's Gradle build. */
object RepoDiscovery {
    fun find(explicit: String?, remembered: String?): File? {
        val candidates = listOfNotNull(explicit, System.getenv("CLU_SOURCE_REPO"), remembered).map { File(it) } +
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.toList()
        return candidates.firstOrNull { isCluRepo(it) }?.canonicalFile
    }
    fun isCluRepo(d: File): Boolean = File(d, ".git").exists() && File(d, "settings.gradle.kts").isFile &&
        File(d, "settings.gradle.kts").readText().contains("rootProject.name = \"clu\"") && File(d, "core/src/main/kotlin/ai/clu/core").isDirectory
}
