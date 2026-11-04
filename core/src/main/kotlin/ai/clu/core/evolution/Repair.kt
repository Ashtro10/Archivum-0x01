package ai.clu.core.evolution

import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.util.CluJson
import kotlinx.serialization.Serializable

// --------------------------------------------------------------------------------------------- patches
/** One edit: replace [search] (exact text that must occur once) with [replace] in [file]. */
@Serializable
data class EditBlock(val file: String, val search: String, val replace: String)

/**
 * The edit format CLU's model is asked to produce: search/replace blocks for named files. It is a constrained format
 * on purpose. Each edit must match existing text exactly once, so a model cannot silently rewrite whole files, and an
 * edit that does not apply is reported as a failure, never guessed.
 */
object PatchFormat {
    private val BLOCK = Regex("""(?s)FILE:\s*([^\n]+?)\s*\n+<<<<<<< SEARCH\n(.*?)\n=======\n(.*?)\n>>>>>>> REPLACE""")

    fun parse(text: String): List<EditBlock> = BLOCK.findAll(text.replace("\r\n", "\n")).map {
        EditBlock(it.groupValues[1].trim().trim('`', '"', '\'').removePrefix("./"), it.groupValues[2], it.groupValues[3]) }.toList()

    sealed class Applied {
        data class Ok(val text: String) : Applied()
        data class Failed(val reason: String) : Applied()
    }

    /** Applies the edits for one file. Exact match first; then a match that ignores indentation differences, still required to be unique. */
    fun apply(original: String, edits: List<EditBlock>): Applied {
        var text = original
        for (e in edits) {
            if (e.search.isEmpty()) return Applied.Failed("an edit to ${e.file} has an empty SEARCH part")
            val count = occurrences(text, e.search)
            text = when {
                count == 1 -> text.replaceFirst(e.search, e.replace)
                count > 1 -> return Applied.Failed("the SEARCH text for ${e.file} occurs $count times; it must be unique")
                else -> {
                    val loose = looseReplace(text, e.search, e.replace) ?: return Applied.Failed("the SEARCH text for ${e.file} does not occur in the file")
                    loose
                }
            }
        }
        return Applied.Ok(text)
    }

    private fun occurrences(s: String, sub: String): Int { var n = 0; var i = s.indexOf(sub); while (i >= 0) { n++; i = s.indexOf(sub, i + 1) }; return n }

    private fun looseReplace(text: String, search: String, replace: String): String? {
        val lines = text.lines(); val want = search.lines().map { it.trim() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (want.isEmpty()) return null
        val starts = (0..lines.size - want.size).filter { i -> want.indices.all { j -> lines[i + j].trim() == want[j] } }
        if (starts.size != 1) return null
        val i = starts.first()
        val indent = lines[i].takeWhile { it == ' ' || it == '\t' }
        val replIndentBase = replace.lines().firstOrNull { it.isNotBlank() }?.takeWhile { it == ' ' || it == '\t' }.orEmpty()
        val repl = replace.lines().map { l -> if (l.startsWith(replIndentBase)) indent + l.removePrefix(replIndentBase) else l }
        return (lines.subList(0, i) + repl + lines.subList(i + want.size, lines.size)).joinToString("\n")
    }
}

// --------------------------------------------------------------------------------------------- reviewing a diff
@Serializable
data class ReviewFinding(val severity: String, val file: String, val message: String) {
    companion object { const val BLOCKING = "blocking"; const val WARNING = "warning" }
}

/**
 * Reviews a unified diff before anything is built: what files changed, whether tests were weakened, whether the
 * change went outside the files the investigation pointed to, and whether it touches CLU's trust anchors.
 */
object DiffReview {
    /** Files a repair may never change on its own (permission boundaries, audit, owner auth, update and trust code). */
    val TRUST_CRITICAL = listOf(Regex("core/src/main/kotlin/ai/clu/core/agents/(Broker|Audit)\\.kt$"), Regex("core/src/main/kotlin/ai/clu/core/security/"),
        Regex("core/src/main/kotlin/ai/clu/core/evolution/"), Regex("desktop/src/main/kotlin/ai/clu/desktop/evolution/"), Regex("^installer/"),
        Regex("(^|/)build\\.gradle\\.kts$"), Regex("^settings\\.gradle\\.kts$"), Regex("^gradle/"), Regex("^buildSrc/"), Regex("^\\.github/"))

    data class FileDiff(val path: String, val added: List<String>, val removed: List<String>, val isNew: Boolean, val isDeleted: Boolean)

    fun parse(diff: String): List<FileDiff> {
        val out = mutableListOf<FileDiff>(); var path: String? = null; var add = mutableListOf<String>(); var rem = mutableListOf<String>(); var new = false; var del = false
        fun flush() { path?.let { out += FileDiff(it, add, rem, new, del) }; add = mutableListOf(); rem = mutableListOf(); new = false; del = false }
        for (l in diff.lines()) when {
            l.startsWith("diff --git ") -> { flush(); path = l.substringAfter(" b/") }
            l.startsWith("new file mode") -> new = true
            l.startsWith("deleted file mode") -> del = true
            l.startsWith("+++") || l.startsWith("---") -> {}
            l.startsWith("+") -> add += l.substring(1)
            l.startsWith("-") -> rem += l.substring(1)
        }
        flush(); return out
    }

    fun review(diff: String, allowed: Collection<String>, maxChangedLines: Int = 200): List<ReviewFinding> {
        val files = parse(diff); val f = mutableListOf<ReviewFinding>()
        if (files.isEmpty()) f += ReviewFinding(ReviewFinding.BLOCKING, "", "the patch changes nothing")
        for (d in files) {
            val isTest = "/src/test/" in d.path
            if (TRUST_CRITICAL.any { it.containsMatchIn(d.path) }) f += ReviewFinding(ReviewFinding.BLOCKING, d.path, "touches a trust-critical file (permissions, audit, security, self-repair, build or installer code); only the owner may change it")
            if (d.isDeleted) f += ReviewFinding(ReviewFinding.BLOCKING, d.path, "deletes a file")
            if (isTest) {
                val removedAsserts = d.removed.count { ASSERT.containsMatchIn(it) } - d.added.count { ASSERT.containsMatchIn(it) }
                if (removedAsserts > 0) f += ReviewFinding(ReviewFinding.BLOCKING, d.path, "removes $removedAsserts assertion(s) from a test")
                if (d.removed.any { it.contains("@Test") } && d.removed.count { it.contains("@Test") } > d.added.count { it.contains("@Test") }) f += ReviewFinding(ReviewFinding.BLOCKING, d.path, "removes a test")
                if (d.added.any { DISABLE.containsMatchIn(it) }) f += ReviewFinding(ReviewFinding.BLOCKING, d.path, "disables a test")
                if (d.removed.any { ASSERT.containsMatchIn(it) } && !d.isNew) f += ReviewFinding(ReviewFinding.BLOCKING, d.path, "changes an existing assertion (a repair must fix the code, not the expectation)")
            }
            if (allowed.isNotEmpty() && d.path !in allowed && !isTest) f += ReviewFinding(ReviewFinding.WARNING, d.path, "outside the files the investigation pointed to")
        }
        val changed = files.sumOf { it.added.size + it.removed.size }
        if (changed > maxChangedLines) f += ReviewFinding(ReviewFinding.BLOCKING, "", "$changed changed lines exceeds the limit of $maxChangedLines for a repair")
        return f
    }

    private val ASSERT = Regex("\\bassert\\w*\\s*\\(|\\bfail\\s*\\(|\\bexpect\\w*\\s*\\(")
    private val DISABLE = Regex("@(Disabled|Ignore|DisabledIf\\w*)\\b|assumeTrue\\(\\s*false")
}

// --------------------------------------------------------------------------------------------- verification
@Serializable
data class TestRun(val label: String, val command: String, val exitCode: Int, val compiled: Boolean, val results: List<TestCaseResult>, val durationMs: Long) {
    val executed get() = results.count { it.status != TestCaseResult.SKIPPED }
    val failed get() = results.filter { it.failed }
    val passed get() = results.count { it.status == TestCaseResult.PASSED }
}

@Serializable
data class Verdict(val outcome: String, val summary: String, val fixed: List<String>, val stillFailing: List<String>, val regressions: List<String>,
                   val newTests: List<String>, val executedBefore: Int, val executedAfter: Int, val reasons: List<String>) {
    val verified get() = outcome == VERIFIED
    companion object {
        const val VERIFIED = "verified-fix"; const val NOT_FIXED = "not-fixed"; const val REGRESSION = "regression"; const val BUILD_FAILED = "build-failed"
        const val REVIEW_BLOCKED = "review-blocked"; const val INCONCLUSIVE = "inconclusive"
    }
}

/**
 * Decides whether a repair works by comparing the same tests before and after the change. A successful compilation is
 * never enough. The failing tests must fail before and pass after, nothing that passed before may fail after, tests
 * must actually run, and the diff review must have no blocking finding.
 */
object Verification {
    fun compare(target: List<String>, before: TestRun, after: TestRun, review: List<ReviewFinding>): Verdict {
        val b = before.results.associateBy { it.id }; val a = after.results.associateBy { it.id }
        val fixed = target.filter { b[it]?.failed == true && a[it]?.status == TestCaseResult.PASSED }
        val still = target.filter { a[it]?.status != TestCaseResult.PASSED }
        val regress = b.values.filter { it.status == TestCaseResult.PASSED && a[it.id]?.failed == true }.map { it.id }
        val missing = b.values.filter { it.status == TestCaseResult.PASSED && a[it.id] == null }.map { it.id }
        val newTests = a.keys.filter { it !in b }
        val reasons = mutableListOf<String>()
        val blocking = review.filter { it.severity == ReviewFinding.BLOCKING }
        val outcome = when {
            !after.compiled -> { reasons += "the modified project did not compile (exit ${after.exitCode})"; Verdict.BUILD_FAILED }
            blocking.isNotEmpty() -> { blocking.forEach { reasons += "review: ${it.file} ${it.message}".trim() }; Verdict.REVIEW_BLOCKED }
            after.executed == 0 -> { reasons += "no test ran after the change"; Verdict.INCONCLUSIVE }
            target.isEmpty() || target.none { b[it]?.failed == true } -> { reasons += "the target tests did not fail before the change, so nothing proves the change fixed them"; Verdict.INCONCLUSIVE }
            regress.isNotEmpty() || missing.isNotEmpty() -> { reasons += "${regress.size} test(s) that passed before now fail${if (missing.isNotEmpty()) "; ${missing.size} test(s) no longer ran" else ""}"; Verdict.REGRESSION }
            still.isNotEmpty() -> { reasons += "${still.size} target test(s) still fail"; Verdict.NOT_FIXED }
            else -> { reasons += "all ${fixed.size} failing test(s) now pass; ${after.passed} of ${after.executed} executed tests pass; no regressions"; Verdict.VERIFIED }
        }
        val summary = "before: ${before.passed}/${before.executed} passed (${before.failed.size} failed); after: ${after.passed}/${after.executed} passed (${after.failed.size} failed)"
        return Verdict(outcome, summary, fixed, still, regress + missing, newTests, before.executed, after.executed, reasons)
    }

    /** Code that did not compile: verified when it now compiles and every test of the run passes. */
    fun compareBuild(before: TestRun, after: TestRun, review: List<ReviewFinding>): Verdict {
        val blocking = review.filter { it.severity == ReviewFinding.BLOCKING }
        val reasons = mutableListOf<String>()
        val outcome = when {
            !after.compiled -> { reasons += "it still does not compile (exit ${after.exitCode})"; Verdict.BUILD_FAILED }
            blocking.isNotEmpty() -> { blocking.forEach { reasons += "review: ${it.file} ${it.message}".trim() }; Verdict.REVIEW_BLOCKED }
            after.executed == 0 -> { reasons += "it compiles but no test ran"; Verdict.INCONCLUSIVE }
            after.failed.isNotEmpty() -> { reasons += "it compiles but ${after.failed.size} test(s) fail"; Verdict.NOT_FIXED }
            else -> { reasons += "the code compiles again (it did not before: exit ${before.exitCode}); ${after.passed} of ${after.executed} tests pass"; Verdict.VERIFIED }
        }
        return Verdict(outcome, "before: did not compile; after: ${if (after.compiled) "${after.passed}/${after.executed} passed" else "did not compile"}", emptyList(),
            after.failed.map { it.id }, emptyList(), after.results.map { it.id }, 0, after.executed, reasons)
    }

    /**
     * A development task: verified when the acceptance tests (new, so absent before) all run and pass, and nothing that
     * passed before fails or vanished.
     */
    fun compareFeature(acceptSuites: Set<String>, before: TestRun, after: TestRun, review: List<ReviewFinding>): Verdict {
        val b = before.results.associateBy { it.id }; val a = after.results.associateBy { it.id }
        val accept = after.results.filter { it.suite in acceptSuites }
        val regress = b.values.filter { it.status == TestCaseResult.PASSED && a[it.id]?.failed != false }.map { it.id }
        val blocking = review.filter { it.severity == ReviewFinding.BLOCKING }
        val reasons = mutableListOf<String>()
        val outcome = when {
            !after.compiled -> { reasons += "the change does not compile"; Verdict.BUILD_FAILED }
            blocking.isNotEmpty() -> { blocking.forEach { reasons += "review: ${it.file} ${it.message}".trim() }; Verdict.REVIEW_BLOCKED }
            accept.isEmpty() -> { reasons += "the acceptance tests did not run"; Verdict.INCONCLUSIVE }
            regress.isNotEmpty() -> { reasons += "${regress.size} test(s) that passed before now fail or did not run"; Verdict.REGRESSION }
            accept.any { it.status != TestCaseResult.PASSED } -> { reasons += "${accept.count { it.status != TestCaseResult.PASSED }} of ${accept.size} acceptance test(s) fail"; Verdict.NOT_FIXED }
            else -> { reasons += "all ${accept.size} acceptance test(s) pass; ${after.passed} of ${after.executed} executed tests pass; no regressions"; Verdict.VERIFIED }
        }
        return Verdict(outcome, "before: ${before.passed}/${before.executed} passed; after: ${after.passed}/${after.executed} passed (${accept.count { it.status == TestCaseResult.PASSED }}/${accept.size} acceptance)",
            accept.filter { it.status == TestCaseResult.PASSED }.map { it.id }, accept.filter { it.status != TestCaseResult.PASSED }.map { it.id }, regress, accept.map { it.id }, before.executed, after.executed, reasons)
    }
}

// --------------------------------------------------------------------------------------------- repairs (records)
@Serializable
data class RepairRecord(
    val id: String, val incidentId: String, val investigationId: String, val state: String,
    /** `model` (generated by CLU's configured model), `external` (a patch supplied by the owner or another development tool), `fixture` (a test fixture, for demonstrations). */
    val source: String,
    val baseCommit: String, val branch: String, val worktree: String, val patchCommit: String = "", val patchHash: String = "",
    /** The branch the repair is meant for (the one checked out when the problem was found). */
    val targetBranch: String = "", val module: String = "", val testFilter: String = "",
    val files: List<String> = emptyList(), val diff: String = "", val review: List<ReviewFinding> = emptyList(),
    val before: TestRun? = null, val after: TestRun? = null, val verdict: Verdict? = null,
    val modelNotes: String = "", val outcome: String = "", val decidedBy: String = "", val decidedAt: Long = 0, val integratedCommit: String = "",
    val createdAt: Long = 0,
    /** `repair` (a failing test), `build` (code that does not compile), `feature` (a development task from the owner). */
    val kind: String = "repair", val goal: String = "",
    /** One line per coding attempt (the full records are in coding_attempts). */
    val attempts: List<String> = emptyList(),
) {
    companion object {
        const val INVESTIGATING = "investigating"; const val PATCH_FAILED = "patch-failed"; const val FAILED_VERIFICATION = "failed-verification"
        const val AWAITING_APPROVAL = "awaiting-approval"; const val APPROVED = "approved-integrated"; const val REJECTED = "rejected"; const val DISCARDED = "discarded"
    }
}

class RepairStore(private val store: EvolutionStore) {
    private val db = store.db
    fun save(r: RepairRecord): RepairRecord {
        val now = store.clock.now()
        db.execute("""INSERT INTO repairs(id, incident_id, investigation_id, state, source, base_commit, branch, worktree, patch_commit, patch_hash, json, created_at, updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET incident_id = excluded.incident_id, investigation_id = excluded.investigation_id, state = excluded.state, source = excluded.source, worktree = excluded.worktree,
            patch_commit = excluded.patch_commit, patch_hash = excluded.patch_hash, json = excluded.json, updated_at = excluded.updated_at""",
            listOf(r.id, r.incidentId, r.investigationId, r.state, r.source, r.baseCommit, r.branch, r.worktree, r.patchCommit, r.patchHash,
                CluJson.encodeToString(RepairRecord.serializer(), r), if (r.createdAt > 0) r.createdAt else now, now))
        return r
    }
    fun get(id: String): RepairRecord? = db.query("SELECT json FROM repairs WHERE id = ? OR id LIKE ? ORDER BY created_at DESC LIMIT 1", listOf(id, "$id%")) { it.str(0) }
        .firstOrNull()?.let { CluJson.decodeFromString(RepairRecord.serializer(), it) }
    fun list(limit: Int = 50): List<RepairRecord> = db.query("SELECT json FROM repairs ORDER BY created_at DESC LIMIT ${limit.coerceIn(1, 500)}") { it.str(0) }
        .map { CluJson.decodeFromString(RepairRecord.serializer(), it) }
    fun exists(id: String) = db.query("SELECT 1 FROM repairs WHERE id = ?", listOf(id)) { 1 }.isNotEmpty()
    /** True if this exact patch was already tried for this incident and did not succeed (so it is not tried again). */
    fun failedBefore(incidentId: String, patchHash: String): RepairRecord? = list(500).firstOrNull {
        it.incidentId == incidentId && it.patchHash == patchHash && it.state in setOf(RepairRecord.FAILED_VERIFICATION, RepairRecord.REJECTED, RepairRecord.PATCH_FAILED) }
    fun count(state: String) = db.query("SELECT COUNT(*) FROM repairs WHERE state = ?", listOf(state)) { it.lng(0) }.first()
}

// --------------------------------------------------------------------------------------------- the model's part
/**
 * Prompts CLU's own model (the shared inference service) in its developer mode. The model gets only bounded
 * context: the failure, the test excerpt, the suspected code, and earlier failed attempts. It must answer with edit
 * blocks. Its output is untrusted: it is parsed, reviewed and verified like any other patch.
 */
object RepairPrompt {
    fun system(name: String) = """You are $name, working in developer mode on your own source code (Kotlin). You fix one failing test at a time.
Rules: change only production code, never the tests; make the smallest change that fixes the failure; keep the existing style.
Answer ONLY with one or more edit blocks in exactly this format, and nothing else:
FILE: path/to/File.kt
<<<<<<< SEARCH
exact existing lines to replace
=======
replacement lines
>>>>>>> REPLACE"""

    fun user(inv: Investigation, code: Map<String, String>): String = buildString {
        appendLine("Failing test: ${inv.failingTests.joinToString()}")
        if (inv.assertion.isNotBlank()) appendLine("Failure: ${inv.assertion}") else appendLine("Failure: ${inv.exception}")
        if (inv.testExcerpt.isNotBlank()) { appendLine("Test code around the failing line (> marks it):"); appendLine(inv.testExcerpt) }
        for ((file, text) in code) { appendLine(); appendLine("Suspected code, $file:"); appendLine(text) }
        if (inv.priorAttempts.isNotEmpty()) { appendLine(); appendLine("These earlier attempts did NOT work; do something different:"); inv.priorAttempts.forEach { appendLine("- $it") } }
        appendLine(); append("Write the edit block(s) that fix the code.")
    }

    /** A window of a source file around [line], without line numbers (so SEARCH text can be copied verbatim). */
    fun window(src: String, line: Int, radius: Int = 40): String {
        val l = src.lines(); if (l.size <= radius * 2 + 1) return src
        val from = maxOf(0, line - 1 - radius); val to = minOf(l.size, line + radius)
        return l.subList(from, to).joinToString("\n")
    }
}
