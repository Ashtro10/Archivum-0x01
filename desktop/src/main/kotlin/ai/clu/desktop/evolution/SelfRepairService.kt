package ai.clu.desktop.evolution

import ai.clu.core.evolution.*
import ai.clu.core.llm.ChatMessage
import ai.clu.core.llm.GenParams
import ai.clu.core.llm.Reasoning
import ai.clu.core.util.newId
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** CLU's own model in developer mode (the shared inference service). Null text = the model could not be used, with [lastError] saying why. */
interface RepairModel {
    val description: String
    suspend fun complete(system: String, user: String, maxTokens: Int): String?
    val lastError: String?
}

/**
 * The self-repair workflow, end to end, on the PC:
 *
 * observe (reconcile the repository, run its tests in an isolated worktree) → detect (failing tests become incidents)
 * → investigate (evidence, suspects, hypotheses) → reproduce (experiment) → patch (CLU's model, or a patch supplied by
 * the owner or another development tool) → review the diff → build and test → compare before/after → report → wait
 * for the owner's decision → integrate only on approval.
 *
 * The owner's working tree, branches and installed CLU are never touched by any step except [approve], and approve
 * refuses to run over uncommitted work. Every command, result and decision is recorded in `evolution.db`.
 */
class SelfRepairService(
    val store: EvolutionStore,
    private val workspaces: File,
    private val runner: CommandRunner = CommandRunner(),
    private val model: RepairModel? = null,
    /** Why expensive work should wait (CLU is serving the owner, the PC is short of memory); null = go ahead. */
    private val busyReason: () -> String? = { null },
    private val selfName: () -> String = { "CLU" },
    private val log: (String) -> Unit = {},
    /** Time limit for one build-and-test command. */
    private val commandTimeoutMs: Long = 20 * 60_000L,
    /** The build-and-test command for a module (and optional test filter); Gradle by default. */
    private val testCommand: ((String, String?) -> List<String>)? = null,
    /** Where that command leaves JUnit XML reports. */
    private val resultsDir: (File, String) -> File = { wt, module -> File(wt, "$module/build/test-results/test") },
) {
    val indexer = SourceIndexer(store)
    val reconciler = Reconciler(store, indexer)
    val knowledge = SelfKnowledge(indexer, reconciler, store)
    val incidents = Incidents(store)
    val investigator = Investigator(store, indexer)
    val repairs = RepairStore(store)
    private val active = AtomicReference<String?>(null)
    @Volatile var progress: String = "idle"; private set

    fun repoRoot(): File? = store.meta("repo_root")?.let(::File)?.takeIf { it.isDirectory }
    fun connect(repo: File): Reconciler.Result {
        val git = GitCli(repo)
        if (!git.isRepository()) throw GitException("${repo.path} is not a Git repository")
        return reconcile(GitCli(git.topLevel()))
    }

    fun reconcile(git: GitCli = GitCli(repoRoot() ?: throw GitException("no source repository is connected"))): Reconciler.Result =
        reconciler.reconcile(git) { id -> repairs.exists(id) || repairs.get(id) != null }.also { log("self-repair: ${it.describe().lineSequence().first()}") }

    // ------------------------------------------------------------------------------------------ the workflow
    data class Options(val module: String = "core", val tests: String? = null, val patch: String? = null, val patchSource: String = "external",
                       val useModel: Boolean = true, val targetBranch: String? = null)

    data class Outcome(val repair: RepairRecord?, val incidents: List<Incident>, val message: String, val reportFile: File?)

    /** Runs the whole workflow once. Blocking; one at a time. */
    suspend fun run(opt: Options): Outcome {
        busyReason()?.let { return Outcome(null, emptyList(), "Not started: $it.", null) }
        if (!active.compareAndSet(null, "starting")) return Outcome(null, emptyList(), "A self-repair run is already in progress (${progress}).", null)
        try { return runLocked(opt) } finally { active.set(null); progress = "idle" }
    }

    private suspend fun runLocked(opt: Options): Outcome {
        val repoDir = repoRoot() ?: return Outcome(null, emptyList(), "No source repository is connected (use --self-repo or POST /v1/evolution/repo).", null)
        val git = GitCli(repoDir)
        step("reconciling the repository with the index")
        val rec = reconcile(git)
        val base = rec.head
        val id = newId(); val short = id.take(8)
        val branch = "clu/repair/$short"
        val wt = File(workspaces, short)
        val target = opt.targetBranch ?: rec.branch
        var r = RepairRecord(id, "", "", RepairRecord.INVESTIGATING, if (opt.patch != null) opt.patchSource else "model", base, branch, wt.path,
            targetBranch = target, module = opt.module, testFilter = opt.tests.orEmpty(), createdAt = store.clock.now())
        repairs.save(r)
        store.history("repair-started", id, "Self-repair run $short on ${opt.module}${opt.tests?.let { " ($it)" } ?: ""} at ${base.take(10)}",
            if (rec.dirty.isNotEmpty()) "Uncommitted changes in the owner's tree are not part of this run (it starts from the last commit): ${rec.dirty.take(20).joinToString()}" else "")
        step("creating the isolated workspace $wt")
        git.addWorktree(wt, branch, base)
        linkIgnoredInputs(repoDir, wt)

        // 1. Observe: the module's tests on the unchanged code. This run is also the "before" of the comparison.
        step("building and testing the unchanged code")
        val before = testRun(r.id, "before (unchanged code)", "detect", wt, opt.module, opt.tests)
        r = repairs.save(r.copy(before = before))
        if (!before.compiled) return finish(git, r.copy(state = RepairRecord.FAILED_VERIFICATION, outcome = "the unchanged code does not build; see the build output"), emptyList(), keepWorkspace = false,
            "The unchanged code does not build (exit ${before.exitCode}). Build failures are recorded but not repaired in this version.")
        if (before.failed.isEmpty()) {
            store.history("health-check", id, "Health check passed: ${before.passed}/${before.executed} tests in ${opt.module}${opt.tests?.let { " ($it)" } ?: ""} at ${base.take(10)}")
            return finish(git, r.copy(state = RepairRecord.DISCARDED, outcome = "nothing to repair: all ${before.executed} tests passed"), emptyList(), keepWorkspace = false,
                "No problem found: ${before.passed} of ${before.executed} tests passed (${opt.module}${opt.tests?.let { ", $it" } ?: ""}).")
        }

        // 2. Detect: each failing test is an incident (recurrences are counted, not duplicated).
        val found = before.failed.map { t -> incidents.report("test-failure", Incidents.testSignature(t), "${t.suite.substringAfterLast('.')} › ${t.name} fails", "${Investigator.parseAssertion(t.message)}\n\n${t.trace.take(6000)}", base) }
        val incident = found.first()
        val failures = before.failed.filter { Incidents.testSignature(it) == incident.signature }
        incidents.setStatus(incident.id, "investigating")

        // 3. Investigate from evidence, in the workspace's source.
        step("investigating ${failures.first().id}")
        val wtGit = GitCli(wt)
        var inv = investigator.investigate(incident, failures, { p -> wtGit.read(p) }, { p -> git.recentChanges(p) },
            reproduceCommand = gradle(opt.module, failures.first().suite).joinToString(" "), lastChangedAt = { p -> git.lastChangeAt(p) })
        r = repairs.save(r.copy(incidentId = incident.id, investigationId = inv.id))

        // 4. Reproduce (experiment): the failing test again, alone, on the same code.
        step("reproducing the failure")
        val repro = testRun(r.id, "reproduction", "reproduce", wt, opt.module, failures.first().suite)
        val stillFails = repro.results.any { it.id == failures.first().id && it.failed }
        inv = investigator.recordExperiment(inv, Experiment("reproduction", "Is the failure deterministic on this commit (rules out flakiness)?", repro.command,
            "the test fails again", if (stillFails) "it failed again (exit ${repro.exitCode})" else "it passed this time", if (stillFails) "reproduced" else "not reproduced", store.clock.now()),
            refutes = if (stillFails) listOf("H3") else emptyList(), state = if (stillFails) "reproduced" else "not-reproducible")
        if (!stillFails) {
            inv = investigator.recordExperiment(inv, Experiment("flakiness", "The same test passed on a re-run of the same code", repro.command, "fails", "passed", "flaky", store.clock.now()),
                confirms = listOf("H3"), conclusion = "The failure did not reproduce: treated as flaky/environmental. No code repair is attempted.")
            incidents.setStatus(incident.id, "flaky")
            return finish(git, r.copy(state = RepairRecord.DISCARDED, outcome = "not reproducible (flaky)"), found, keepWorkspace = false, "The failure did not reproduce; recorded as flaky, no repair attempted.")
        }

        // 5. A patch: supplied (owner / development tool / fixture) or written by CLU's model.
        step("preparing a patch")
        val patchResult = if (opt.patch != null) applySupplied(wtGit, wt, opt.patch) else if (opt.useModel) modelPatch(wt, inv) else "No patch: the model was not used and no patch was supplied." to ""
        val (problem, notes) = patchResult
        r = r.copy(modelNotes = notes)
        if (problem != null) {
            store.history("patch-failed", id, problem, notes)
            return finish(git, r.copy(state = RepairRecord.PATCH_FAILED, outcome = problem), found, keepWorkspace = false, "Investigation recorded, but no repair could be prepared: $problem")
        }
        val diff = wtGit.diff(wt); val files = wtGit.changedFiles(wt)
        val patchHash = sha256(diff)
        repairs.failedBefore(incident.id, patchHash)?.let { prev ->
            return finish(git, r.copy(state = RepairRecord.PATCH_FAILED, diff = diff, files = files, patchHash = patchHash, outcome = "this exact patch already failed in repair ${prev.id.take(8)} (${prev.state})"),
                found, keepWorkspace = false, "Refused: the same patch was already tried for this problem in ${prev.id.take(8)} and did not succeed.")
        }

        // 6. Review the diff before building anything.
        val review = DiffReview.review(diff, inv.suspects.map { it.file })
        r = repairs.save(r.copy(diff = diff, files = files, patchHash = patchHash, review = review))
        val blocking = review.filter { it.severity == ReviewFinding.BLOCKING }
        if (blocking.isNotEmpty()) {
            val v = Verdict(Verdict.REVIEW_BLOCKED, "not built: the diff review found blocking problems", emptyList(), inv.failingTests, emptyList(), emptyList(), before.executed, 0, blocking.map { "${it.file} ${it.message}".trim() })
            return finish(git, r.copy(state = RepairRecord.FAILED_VERIFICATION, verdict = v, outcome = v.reasons.joinToString("; ")), found, keepWorkspace = false, "The patch was refused by the diff review: ${v.reasons.joinToString("; ")}")
        }

        // 7. Build and run the same tests on the modified code, then compare.
        step("building and testing the modified code")
        val after = testRun(r.id, "after (with the repair)", "verify", wt, opt.module, opt.tests)
        val verdict = Verification.compare(inv.failingTests, before, after, review)
        r = repairs.save(r.copy(after = after, verdict = verdict))
        val h1 = inv.hypotheses.firstOrNull { it.id == "H1" }
        val confinedToH1 = h1 != null && files.isNotEmpty() && files.all { it in h1.files }
        inv = investigator.recordExperiment(inv, Experiment("repair", "Does the change make the failing test pass with nothing else failing?", after.command,
            "the failing test passes, no regressions", verdict.reasons.joinToString("; "), verdict.outcome, store.clock.now()),
            confirms = if (verdict.verified && confinedToH1) listOf("H1") else emptyList(),
            refutes = if (verdict.verified) listOf("H2") else emptyList(),
            state = if (verdict.verified) "cause-confirmed" else inv.state,
            conclusion = when {
                verdict.verified && confinedToH1 -> "Confirmed: the failure was caused by ${h1!!.claim.substringAfter("A defect in ").substringBefore(" produces")}; changing only that code fixes it and nothing else regressed."
                verdict.verified -> "The change fixes the failure, but it touches files outside the top suspect, so the original hypothesis is not confirmed as the cause."
                else -> "Not confirmed: the repair did not verify (${verdict.outcome})." })

        if (!verdict.verified) {
            store.history("repair-failed", id, "Repair did not verify: ${verdict.outcome} — ${verdict.reasons.joinToString("; ")}", diff.take(20_000))
            incidents.setStatus(incident.id, "open")
            return finish(git, r.copy(state = RepairRecord.FAILED_VERIFICATION, outcome = verdict.reasons.joinToString("; ")), found, keepWorkspace = false,
                "The repair did not pass verification (${verdict.outcome}): ${verdict.reasons.joinToString("; ")}")
        }

        // 8. Verified: commit on the repair branch only, and wait for the owner.
        val commit = wtGit.commitAll(wt, "Repair: ${incident.title}\n\n${inv.conclusion}\n\nVerification: ${verdict.summary}\n\nCLU-Repair: $id")
        incidents.setStatus(incident.id, "repair-proposed")
        r = repairs.save(r.copy(state = RepairRecord.AWAITING_APPROVAL, patchCommit = commit, outcome = "verified; waiting for the owner"))
        store.history("repair-proposed", id, "Verified repair ready for review: ${incident.title} (${files.joinToString()})", verdict.summary)
        return finish(git, r, found, keepWorkspace = true, "Verified repair ${short} is waiting for your approval: ${verdict.summary}.")
    }

    private fun finish(git: GitCli, r: RepairRecord, found: List<Incident>, keepWorkspace: Boolean, message: String): Outcome {
        repairs.save(r)
        if (!keepWorkspace) { git.removeWorktree(File(r.worktree)); if (r.state != RepairRecord.AWAITING_APPROVAL) git.deleteBranch(r.branch) }
        val report = writeReport(r)
        if (r.state != RepairRecord.AWAITING_APPROVAL) store.history("repair-closed", r.id, "Repair ${r.id.take(8)} closed: ${r.state} — ${r.outcome}".take(400))
        return Outcome(repairs.get(r.id), found, message, report)
    }

    // ------------------------------------------------------------------------------------------ owner decisions
    /** Integrates a verified repair into its target branch. Only the owner calls this (CLI or the token-authenticated API). */
    fun approve(id: String, by: String = "owner"): Outcome {
        val r = repairs.get(id) ?: return Outcome(null, emptyList(), "No repair $id.", null)
        if (r.state != RepairRecord.AWAITING_APPROVAL || r.verdict?.verified != true) return Outcome(r, emptyList(), "Repair ${r.id.take(8)} is ${r.state}; only a verified repair waiting for approval can be integrated.", null)
        val git = GitCli(repoRoot() ?: return Outcome(r, emptyList(), "No source repository is connected.", null))
        val merged = try { git.integrate(r.branch, r.targetBranch, "Integrate CLU repair ${r.id.take(8)}\n\nCLU-Repair: ${r.id}") }
        catch (e: GitException) { store.history("repair-approve-refused", r.id, "Approval could not be applied: ${e.message}"); return Outcome(r, emptyList(), "Not integrated: ${e.message}", null) }
        git.removeWorktree(File(r.worktree)); git.deleteBranch(r.branch)
        val done = repairs.save(r.copy(state = RepairRecord.APPROVED, decidedBy = by, decidedAt = store.clock.now(), integratedCommit = merged, outcome = "integrated into ${r.targetBranch}"))
        incidents.setStatus(r.incidentId, "resolved")
        store.history("repair-approved", r.id, "Owner approved repair ${r.id.take(8)}; integrated into ${r.targetBranch} at ${merged.take(10)}")
        runCatching { reconcile(git) }
        return Outcome(done, emptyList(), "Integrated into ${r.targetBranch} at ${merged.take(10)}. The installed CLU is unchanged until it is rebuilt and installed.", writeReport(done))
    }

    fun reject(id: String, reason: String, by: String = "owner"): Outcome {
        val r = repairs.get(id) ?: return Outcome(null, emptyList(), "No repair $id.", null)
        if (r.state != RepairRecord.AWAITING_APPROVAL) return Outcome(r, emptyList(), "Repair ${r.id.take(8)} is ${r.state}; nothing to reject.", null)
        repoRoot()?.let { val git = GitCli(it); git.removeWorktree(File(r.worktree)); git.deleteBranch(r.branch) }
        val done = repairs.save(r.copy(state = RepairRecord.REJECTED, decidedBy = by, decidedAt = store.clock.now(), outcome = "rejected by $by: $reason"))
        incidents.setStatus(r.incidentId, "open")
        store.history("repair-rejected", r.id, "Owner rejected repair ${r.id.take(8)}: $reason")
        return Outcome(done, emptyList(), "Rejected; the workspace and branch were removed. The same patch will not be proposed again for this problem.", writeReport(done))
    }

    fun report(id: String): String? = repairs.get(id)?.let { r -> RepairReport.markdown(r, incidents.get(r.incidentId), investigator.get(r.investigationId), store.commands(r.id)) }

    // ------------------------------------------------------------------------------------------ steps
    private fun step(s: String) { progress = s; active.set(s); log("self-repair: $s") }

    private fun gradle(module: String, tests: String?): List<String> {
        testCommand?.let { return it(module, tests) }
        val wrapper = if (CommandRunner.isWindows) "gradlew.bat" else "./gradlew"
        // --rerun: the tests always execute now; a result restored from Gradle's build cache would not be evidence about this run.
        return listOf(wrapper, ":$module:test", "--rerun") + (tests?.let { listOf("--tests", it) } ?: emptyList()) + listOf("--offline", "--continue", "--console=plain")
    }

    private fun testRun(repairId: String, label: String, phase: String, wt: File, module: String, tests: String?): TestRun {
        val results = resultsDir(wt, module)
        results.deleteRecursively()
        val cmd = gradle(module, tests)
        val res = runner.run(cmd, wt, commandTimeoutMs)
        store.recordCommand(repairId, phase, res.display, wt.path, res.exitCode, res.durationMs, res.timedOut, res.output)
        val cases = results.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }.flatMap { JUnitXml.parse(it.readText()) }
        val compileFailed = Regex("Task :\\S*:compile\\S* FAILED|Compilation error|^e: ", RegexOption.MULTILINE).containsMatchIn(res.output)
        // Never accept test results that Gradle restored instead of running.
        if (Regex("Task :$module:test (FROM-CACHE|UP-TO-DATE)").containsMatchIn(res.output)) return TestRun(label, res.display, res.exitCode, !compileFailed, emptyList(), res.durationMs)
        return TestRun(label, res.display, res.exitCode, !compileFailed && !res.timedOut, cases, res.durationMs)
    }

    /** Ignored inputs the build needs (toolchain, native libraries) are linked read-only from the owner's tree instead of copied. */
    private fun linkIgnoredInputs(repo: File, wt: File) {
        for (rel in listOf("toolchain", "native/build", "local.properties")) {
            val src = File(repo, rel); val dst = File(wt, rel)
            if (src.exists() && !dst.exists()) runCatching { dst.parentFile.mkdirs(); java.nio.file.Files.createSymbolicLink(dst.toPath(), src.toPath()) }
                .onFailure { log("self-repair: could not link $rel into the workspace: ${it.message}") }
        }
    }

    private fun applySupplied(git: GitCli, wt: File, patch: String): Pair<String?, String> = try {
        git.applyPatch(wt, patch); null to "patch supplied externally (${patch.lines().size} lines)"
    } catch (e: GitException) { "the supplied patch does not apply: ${e.message}" to "" }

    /** Asks CLU's model for edit blocks and applies them. Returns (problem or null, notes). */
    private suspend fun modelPatch(wt: File, inv: Investigation): Pair<String?, String> {
        val m = model ?: return "no language model is available to write a patch (none configured or it could not be loaded)" to ""
        if (inv.suspects.isEmpty()) return "the investigation found no suspect code to change" to ""
        val code = inv.suspects.map { it.file }.distinct().take(2).associateWith { f ->
            val src = File(wt, f).takeIf { it.isFile }?.readText().orEmpty()
            RepairPrompt.window(src, inv.suspects.first { it.file == f }.line) }
        val t0 = System.nanoTime()
        val raw = m.complete(RepairPrompt.system(selfName()), RepairPrompt.user(inv, code), 900)
            ?: return "the model could not be used: ${m.lastError ?: "unknown error"}" to "model: ${m.description}"
        val text = Reasoning.strip(raw)
        val secs = (System.nanoTime() - t0) / 1e9
        val notes = "model ${m.description}, ${"%.1f".format(secs)} s, ${text.length} characters of output"
        store.history("model-output", inv.id, "Model proposed a patch (${notes})", text.take(8000))
        val edits = PatchFormat.parse(text)
        if (edits.isEmpty()) return "the model's answer contained no usable edit block (output kept in the development history)" to notes
        for ((file, list) in edits.groupBy { it.file }) {
            val f = File(wt, file).canonicalFile
            if (!f.path.startsWith(wt.canonicalPath + File.separator) || !f.isFile) return "the model named a file that does not exist in the workspace: $file" to notes
            when (val a = PatchFormat.apply(f.readText(), list)) {
                is PatchFormat.Applied.Ok -> f.writeText(a.text)
                is PatchFormat.Applied.Failed -> return "the model's edit does not apply: ${a.reason}" to notes
            }
        }
        return null to "$notes; ${edits.size} edit block(s) applied"
    }

    private fun writeReport(r: RepairRecord): File? = runCatching {
        val f = File(workspaces, "reports/repair-${r.id.take(8)}.md"); f.parentFile.mkdirs(); f.writeText(report(r.id).orEmpty()); f }.getOrNull()

    companion object {
        fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        fun modelMessages(system: String, user: String) = listOf(ChatMessage("system", system), ChatMessage("user", user))
        val PATCH_PARAMS = GenParams(maxTokens = 900, temperature = 0.2f, topK = 20, topP = 0.9f, repeatPenalty = 1.05f)
    }
}
