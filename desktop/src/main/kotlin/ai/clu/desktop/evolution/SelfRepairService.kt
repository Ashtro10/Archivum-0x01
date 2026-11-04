package ai.clu.desktop.evolution

import ai.clu.core.evolution.*
import ai.clu.core.llm.ChatMessage
import ai.clu.core.util.newId
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** CLU's own model in developer mode: the core [DevModel] (structured output, temperature, optional reasoning). */
typealias RepairModel = DevModel

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
    val experience = CodingExperience(store)
    private val active = AtomicReference<String?>(null)
    @Volatile var progress: String = "idle"; private set

    fun modelForBenchmark(): RepairModel? = model
    fun modelDescription(): String = model?.description ?: "no model"

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
                       val useModel: Boolean = true, val targetBranch: String? = null,
                       /** How hard CLU's model may try (attempts, time, temperatures). */
                       val budget: CodingBudget = CodingBudget(),
                       /** A development task instead of a repair: the goal in words. */
                       val goal: String? = null,
                       /** Development task: files CLU may change (default: found from the goal through the index). */
                       val files: List<String> = emptyList(),
                       /** Development task: an acceptance test supplied by the owner (path in the repository → content). */
                       val acceptTest: Pair<String, String>? = null)

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
        if (!before.compiled) return repairBuild(git, r, wt, before, opt)
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

        // 4b. The evidence says the test changed after the code: the test is the likelier culprit, and CLU never edits a
        // test to make it pass. It reports instead of bending the code to a possibly wrong expectation.
        if (opt.patch == null && inv.hypotheses.firstOrNull { it.id == "H2" }?.status == Hypothesis.SUPPORTED) {
            incidents.setStatus(incident.id, "test-suspected")
            store.history("diagnosis", id, "Not repaired: the failing test changed after the code it tests, so the test's expectation is the likelier error", inv.hypotheses.first { it.id == "H2" }.evidenceFor.joinToString("\n"))
            return finish(git, r.copy(state = RepairRecord.DISCARDED, outcome = "diagnosis: the test changed after the code; check the test (no code change made)"), found, false,
                "No repair attempted: the failing test was changed after the code it tests, so the test is the likelier error. Please check ${inv.failingTests.first()}.")
        }

        // 5. A patch: supplied (owner / development tool / fixture) or written by CLU's model.
        step("preparing a patch")
        if (opt.patch == null && opt.useModel) return codeRepair(git, r, wt, inv, incident, found, before, opt)
        val patchResult = if (opt.patch != null) applySupplied(wtGit, wt, opt.patch) else "No patch: the model was not used and no patch was supplied." to ""
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
        if (!keepWorkspace) { git.removeWorktree(File(r.worktree), workspaces); if (r.state != RepairRecord.AWAITING_APPROVAL) git.deleteBranch(r.branch) }
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
        git.removeWorktree(File(r.worktree), workspaces); git.deleteBranch(r.branch)
        val done = repairs.save(r.copy(state = RepairRecord.APPROVED, decidedBy = by, decidedAt = store.clock.now(), integratedCommit = merged, outcome = "integrated into ${r.targetBranch}"))
        incidents.setStatus(r.incidentId, "resolved")
        store.history("repair-approved", r.id, "Owner approved repair ${r.id.take(8)}; integrated into ${r.targetBranch} at ${merged.take(10)}")
        runCatching { reconcile(git) }
        return Outcome(done, emptyList(), "Integrated into ${r.targetBranch} at ${merged.take(10)}. The installed CLU is unchanged until it is rebuilt and installed.", writeReport(done))
    }

    fun reject(id: String, reason: String, by: String = "owner"): Outcome {
        val r = repairs.get(id) ?: return Outcome(null, emptyList(), "No repair $id.", null)
        if (r.state != RepairRecord.AWAITING_APPROVAL) return Outcome(r, emptyList(), "Repair ${r.id.take(8)} is ${r.state}; nothing to reject.", null)
        repoRoot()?.let { val git = GitCli(it); git.removeWorktree(File(r.worktree), workspaces); git.deleteBranch(r.branch) }
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
        return listOf(wrapper, ":$module:test", "--rerun") + (tests?.split(',')?.filter { it.isNotBlank() }?.flatMap { listOf("--tests", it.trim()) } ?: emptyList()) +
            listOf("--offline", "--continue", "--console=plain")
    }

    private fun testRun(repairId: String, label: String, phase: String, wt: File, module: String, tests: String?): TestRun {
        val results = resultsDir(wt, module)
        ai.clu.core.util.SafeDelete.tree(results, listOf(wt))
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

    // ------------------------------------------------------------------------------------------ CLU writes the code
    /** The workspace as the coding loop sees it: a Git worktree, reset to its base commit between attempts. */
    private inner class WorktreeWorkspace(private val wt: File, private val repairId: String, private val module: String, private val tests: String?,
                                          private val extra: Map<String, String> = emptyMap()) : DevWorkspace {
        private val git = GitCli(wt)
        private val created = mutableSetOf<String>()
        @Volatile private var lastOutput = ""
        override val root: String = wt.canonicalPath
        override fun read(path: String): String? = git.read(path)
        override fun write(files: Map<String, String>): Boolean {
            for ((p, text) in files) {
                val f = File(wt, p).canonicalFile
                if (!f.path.startsWith(wt.canonicalPath + File.separator)) return false
                if (!f.exists()) created += p
                f.parentFile.mkdirs(); f.writeText(text)
            }
            return true
        }
        override fun reset() {
            runCatching { git.run("checkout", "--", ".", cwd = wt) }
            created.forEach { File(wt, it).delete() }; created.clear()
            writeExtra()
        }
        fun writeExtra() { for ((p, t) in extra) { val f = File(wt, p); f.parentFile.mkdirs(); f.writeText(t) } }
        override fun test(label: String, phase: String): TestRun = testRun(repairId, label, phase, wt, module, tests).also { lastOutput = store.commands(repairId).lastOrNull()?.output.orEmpty() }
        override fun diff(): String {
            // New files (patch-created and owner-supplied acceptance tests) are part of the change: show them too.
            (created + extra.keys).forEach { runCatching { git.run("add", "-N", "--", it, cwd = wt) } }
            return git.diff(wt)
        }
        override fun buildOutput(): String = lastOutput
    }

    private fun yieldToOwner(): suspend () -> Unit = {
        var waited = 0
        while (true) { val why = busyReason() ?: break; if (waited == 0) step("paused: $why"); kotlinx.coroutines.delay(5_000); waited += 5; if (waited > 30 * 60) break }
    }

    private fun recordAttempts(r: RepairRecord, kind: String, goalKey: String, res: CodingResult) =
        res.attempts.forEach { experience.record(r.id, kind, goalKey, model?.description ?: "none", it) }

    private fun attemptLines(res: CodingResult) = res.attempts.map { a ->
        "attempt ${a.n} (${a.strategy}, t=${a.temperature}, ${a.generationMs / 1000.0} s model${if (a.buildMs > 0) ", ${a.buildMs / 1000.0} s build" else ""}): " +
            (if (a.failure == FailureKind.NONE.name) "VERIFIED" else FailureKind.valueOf(a.failure).label) + " — " + a.detail.lines().first().take(160) }

    /** Repair of a failing test by CLU's model: the iterative coding loop over the suspects the investigation found. */
    private suspend fun codeRepair(git: GitCli, r0: RepairRecord, wt: File, inv0: Investigation, incident: Incident, found: List<Incident>, before: TestRun, opt: Options): Outcome {
        var r = r0; var inv = inv0
        val m = model ?: return finish(git, r.copy(state = RepairRecord.PATCH_FAILED, outcome = "no language model is available to write a patch"), found, false,
            "Investigation recorded, but no model is available to write a patch.")
        if (inv.suspects.isEmpty()) return finish(git, r.copy(state = RepairRecord.PATCH_FAILED, outcome = "the investigation found no suspect code to change"), found, false,
            "Investigation recorded, but it found no suspect code to change.")
        val wtGit = GitCli(wt)
        val windows = inv.suspects.distinctBy { it.file }.take(2).mapNotNull { sp -> wtGit.read(sp.file)?.let { CodeContext.window(sp.file, it, sp.line, "suspected: ${sp.symbol} — ${sp.reason}") } }
        val primary = inv.suspects.first()
        val evidence = buildString {
            appendLine("Failing test: ${inv.failingTests.joinToString()}")
            appendLine("Failure: ${inv.assertion.ifBlank { inv.exception }}")
            if (inv.testExcerpt.isNotBlank()) { appendLine("Test code (> marks the failing line):"); appendLine(inv.testExcerpt) }
            val w = windows.firstOrNull { it.file == primary.file }
            appendLine("The defect is most likely inside ${primary.symbol} in ${primary.file}${w?.let { " (lines ${it.from}-${it.to})" } ?: ""}; the test is correct.")
        }
        val task = CodingTask("Fix the production code so the failing test passes. The test is correct; do not change it.", inv.failingTests, windows, evidence)
        val ws = WorktreeWorkspace(wt, r.id, opt.module, opt.tests)
        step("coding: up to ${opt.budget.maxAttempts} attempt(s) with ${m.description}")
        val res = CodingLoop(m, selfName(), onStep = ::step).run(task, ws, before, opt.budget, experience.failedFingerprints(incident.signature),
            review = { d -> DiffReview.review(d, windows.map { it.file }) }, yieldToOwner = yieldToOwner())
        recordAttempts(r, "repair", incident.signature, res)
        val lines = attemptLines(res)
        r = r.copy(source = "model", attempts = lines, modelNotes = "${m.description}; ${res.attempts.size} attempt(s); ${if (res.verified) "verified on attempt ${res.attempts.last().n}" else res.finalFailure.label}")
        store.history("coding", r.id, "Coding loop for ${incident.title}: ${if (res.verified) "verified after ${res.attempts.size} attempt(s)" else "no working patch in ${res.attempts.size} attempt(s) (${res.finalFailure.label})"}", lines.joinToString("\n"))
        if (!res.verified) {
            incidents.setStatus(incident.id, "open")
            val last = res.attempts.lastOrNull { it.diff.isNotBlank() }
            return finish(git, r.copy(state = RepairRecord.PATCH_FAILED, diff = last?.diff.orEmpty(), after = res.after, outcome = "no verified patch: ${res.finalFailure.label}"), found, false,
                "CLU's model did not produce a working patch in ${res.attempts.size} attempt(s): ${res.finalFailure.label}.")
        }
        val diff = wtGit.diff(wt); val files = wtGit.changedFiles(wt)
        val verdict = Verification.compare(inv.failingTests, before, res.after!!, res.review)
        r = repairs.save(r.copy(diff = diff, files = files, patchHash = sha256(diff), review = res.review, after = res.after, verdict = verdict))
        val h1 = inv.hypotheses.firstOrNull { it.id == "H1" }
        val confinedToH1 = h1 != null && files.isNotEmpty() && files.all { it in h1.files }
        inv = investigator.recordExperiment(inv, Experiment("repair", "Does CLU's change make the failing test pass with nothing else failing?", res.after!!.command,
            "the failing test passes, no regressions", verdict.reasons.joinToString("; "), verdict.outcome, store.clock.now()),
            confirms = if (confinedToH1) listOf("H1") else emptyList(), refutes = listOf("H2"), state = "cause-confirmed",
            conclusion = if (confinedToH1) "Confirmed: the failure was caused by ${h1!!.claim.substringAfter("A defect in ").substringBefore(" produces")}; changing only that code fixes it and nothing else regressed."
                else "The change fixes the failure, but it touches files outside the top suspect, so the original hypothesis is not confirmed as the cause.")
        return propose(git, r, wt, "Repair: ${incident.title}\n\n${inv.conclusion}", verdict, found, incident.id)
    }

    /** The unchanged code does not compile: CLU's model works from the compiler errors until it compiles and the tests pass. */
    private suspend fun repairBuild(git: GitCli, r0: RepairRecord, wt: File, before: TestRun, opt: Options): Outcome {
        var r = r0
        val output = store.commands(r.id).lastOrNull()?.output.orEmpty()
        val errors = Failures.compilerErrors(output, wt.canonicalPath)
        val title = errors.firstOrNull()?.let { "Build fails: ${it.file}:${it.line} ${it.message.take(80)}" } ?: "Build fails (exit ${before.exitCode})"
        val incident = incidents.report("build-failure", Incidents.signature("build", errors.firstOrNull()?.let { "${it.file}:${it.message}" } ?: output.takeLast(300)), title,
            errors.joinToString("\n") { "${it.file}:${it.line}: ${it.message}" }.ifBlank { output.takeLast(4000) }, r.baseCommit)
        r = repairs.save(r.copy(incidentId = incident.id, kind = "build"))
        val m = model
        if (errors.isEmpty() || m == null || !opt.useModel || opt.patch != null)
            return finish(git, r.copy(state = RepairRecord.FAILED_VERIFICATION, outcome = "the unchanged code does not build"), listOf(incident), false,
                "The unchanged code does not build (exit ${before.exitCode})${if (errors.isEmpty()) "; no compiler error could be read from the output" else ""}.")
        val wtGit = GitCli(wt)
        val windows = errors.distinctBy { it.file to it.line / 40 }.take(3).mapNotNull { e -> wtGit.read(e.file)?.let { CodeContext.window(e.file, it, e.line, "compiler error at line ${e.line}") } }
        val evidence = "The project does not compile. Compiler errors:\n" + errors.take(8).joinToString("\n") { "- ${it.file}:${it.line}: ${it.message}" }
        val task = CodingTask("Fix the code so that it compiles. Do not change tests.", emptyList(), windows, evidence)
        val ws = WorktreeWorkspace(wt, r.id, opt.module, opt.tests)
        step("coding: fixing the build with ${m.description}")
        val res = CodingLoop(m, selfName(), onStep = ::step).run(task, ws, before, opt.budget, experience.failedFingerprints(incident.signature),
            review = { d -> DiffReview.review(d, windows.map { it.file }) }, judge = { after, f -> Verification.compareBuild(before, after, f) }, yieldToOwner = yieldToOwner())
        recordAttempts(r, "build", incident.signature, res)
        r = r.copy(source = "model", attempts = attemptLines(res), modelNotes = "${m.description}; ${res.attempts.size} attempt(s)")
        if (!res.verified) return finish(git, r.copy(state = RepairRecord.PATCH_FAILED, after = res.after, outcome = "no verified build fix: ${res.finalFailure.label}"), listOf(incident), false,
            "CLU's model did not make the build pass in ${res.attempts.size} attempt(s): ${res.finalFailure.label}.")
        val diff = wtGit.diff(wt)
        val verdict = Verification.compareBuild(before, res.after!!, res.review)
        r = repairs.save(r.copy(diff = diff, files = wtGit.changedFiles(wt), patchHash = sha256(diff), review = res.review, after = res.after, verdict = verdict))
        return propose(git, r, wt, "Repair: $title", verdict, listOf(incident), incident.id)
    }

    /** A development task from the owner: CLU changes bounded files until the owner's acceptance test passes with no regressions. */
    suspend fun develop(opt: Options): Outcome {
        busyReason()?.let { return Outcome(null, emptyList(), "Not started: $it.", null) }
        if (!active.compareAndSet(null, "starting")) return Outcome(null, emptyList(), "A self-repair run is already in progress (${progress}).", null)
        try { return developLocked(opt) } finally { active.set(null); progress = "idle" }
    }

    private suspend fun developLocked(opt: Options): Outcome {
        val goal = opt.goal ?: return Outcome(null, emptyList(), "No goal given.", null)
        val accept = opt.acceptTest ?: return Outcome(null, emptyList(), "A development task needs an acceptance test (--accept <test file>): it is how the result is judged.", null)
        val m = model ?: return Outcome(null, emptyList(), "No language model is available.", null)
        val repoDir = repoRoot() ?: return Outcome(null, emptyList(), "No source repository is connected.", null)
        val git = GitCli(repoDir)
        val rec = reconcile(git)
        val id = newId(); val short = id.take(8); val wt = File(workspaces, short)
        val files = opt.files.ifEmpty { knowledge.relevantFiles(goal, 3).map { it.first } }.filter { f -> DiffReview.TRUST_CRITICAL.none { it.containsMatchIn(f) } && "/src/test/" !in f }
        var r = repairs.save(RepairRecord(id, "", "", RepairRecord.INVESTIGATING, "model", rec.head, "clu/repair/$short", wt.path, targetBranch = opt.targetBranch ?: rec.branch,
            module = opt.module, testFilter = opt.tests.orEmpty(), createdAt = store.clock.now(), kind = "feature", goal = goal))
        store.history("task-started", id, "Development task $short: $goal", "files: ${files.joinToString()}\nacceptance test: ${accept.first}")
        if (files.isEmpty()) return finish(git, r.copy(state = RepairRecord.PATCH_FAILED, outcome = "no file found that CLU may change for this goal"), emptyList(), false, "No changeable file found for this goal (security-critical files and tests are excluded).")
        git.addWorktree(wt, r.branch, rec.head); linkIgnoredInputs(repoDir, wt)
        step("building and testing the unchanged code")
        val before = testRun(r.id, "before (unchanged code)", "detect", wt, opt.module, opt.tests)
        r = repairs.save(r.copy(before = before))
        if (!before.compiled || before.failed.isNotEmpty()) return finish(git, r.copy(state = RepairRecord.FAILED_VERIFICATION, outcome = "the unchanged code does not pass its tests"), emptyList(), false,
            "Not started: the unchanged code does not build or has failing tests; repair those first.")
        val acceptClass = Regex("package\\s+([\\w.]+)").find(accept.second)?.groupValues?.get(1)?.let { "$it." }.orEmpty() + accept.first.substringAfterLast('/').substringBefore('.')
        val wtGit = GitCli(wt)
        val windows = files.mapNotNull { f -> wtGit.read(f)?.let { CodeContext.whole(f, it, "may be changed for this task") } }
        val evidence = "The owner's acceptance test (${accept.first}) must pass; it is read-only:\n" + accept.second.lines().take(80).joinToString("\n")
        val task = CodingTask(goal, emptyList(), windows, evidence, feature = true)
        val testsAfter = listOfNotNull(opt.tests, acceptClass).joinToString(",")
        val ws = WorktreeWorkspace(wt, r.id, opt.module, testsAfter, extra = mapOf(accept.first to accept.second)).also { it.writeExtra() }
        step("coding: ${m.description} works on \"$goal\"")
        val res = CodingLoop(m, selfName(), onStep = ::step).run(task, ws, before, opt.budget, experience.failedFingerprints(goal),
            review = { d -> DiffReview.review(d, windows.map { it.file } + accept.first) }, judge = { after, f -> Verification.compareFeature(setOf(acceptClass), before, after, f) },
            yieldToOwner = yieldToOwner())
        recordAttempts(r, "feature", goal, res)
        r = r.copy(attempts = attemptLines(res), modelNotes = "${m.description}; ${res.attempts.size} attempt(s)")
        if (!res.verified) return finish(git, r.copy(state = RepairRecord.PATCH_FAILED, after = res.after, outcome = "no verified implementation: ${res.finalFailure.label}"), emptyList(), false,
            "CLU's model did not complete the task in ${res.attempts.size} attempt(s): ${res.finalFailure.label}.")
        val diff = ws.diff()
        val verdict = Verification.compareFeature(setOf(acceptClass), before, res.after!!, res.review)
        r = repairs.save(r.copy(diff = diff, files = wtGit.changedFiles(wt) + accept.first, patchHash = sha256(diff), review = res.review, after = res.after, verdict = verdict))
        return propose(git, r, wt, "Feature: $goal", verdict, emptyList(), null)
    }

    /** Commit on the repair branch only, and wait for the owner. */
    private fun propose(git: GitCli, r0: RepairRecord, wt: File, title: String, verdict: Verdict, found: List<Incident>, incidentId: String?): Outcome {
        val commit = GitCli(wt).commitAll(wt, "$title\n\nWritten by CLU's model (${model?.description}).\nVerification: ${verdict.summary}\n\nCLU-Repair: ${r0.id}")
        incidentId?.let { incidents.setStatus(it, "repair-proposed") }
        val r = repairs.save(r0.copy(state = RepairRecord.AWAITING_APPROVAL, patchCommit = commit, outcome = "verified; waiting for the owner"))
        store.history("repair-proposed", r.id, "Verified ${r.kind} ready for review (written by CLU's model): ${title.lineSequence().first()} (${r.files.joinToString()})", verdict.summary)
        return finish(git, r, found, keepWorkspace = true, "Verified ${r.kind} ${r.id.take(8)}, written by CLU's model, is waiting for your approval: ${verdict.summary}.")
    }

    private fun writeReport(r: RepairRecord): File? = runCatching {
        val f = File(workspaces, "reports/repair-${r.id.take(8)}.md"); f.parentFile.mkdirs(); f.writeText(report(r.id).orEmpty()); f }.getOrNull()

    companion object {
        fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        fun modelMessages(system: String, user: String) = listOf(ChatMessage("system", system), ChatMessage("user", user))
    }
}
