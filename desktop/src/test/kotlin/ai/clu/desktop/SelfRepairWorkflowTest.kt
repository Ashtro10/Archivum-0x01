package ai.clu.desktop

import ai.clu.core.evolution.CodingBudget
import ai.clu.core.evolution.EvolutionStore
import ai.clu.core.evolution.StructuredPatches
import ai.clu.core.evolution.RepairRecord
import ai.clu.core.evolution.Verdict
import ai.clu.core.util.SystemClock
import ai.clu.desktop.evolution.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * The self-repair workflow end to end with real Git (repository, worktrees, branches, merge), real processes and real
 * JUnit XML reports. The "build" is a small shell script standing in for Gradle so the test runs in seconds; the full
 * Gradle path is exercised by the demonstration documented in docs/SELF_REPAIR.md.
 */
class SelfRepairWorkflowTest {
    private lateinit var repo: File
    private lateinit var home: File

    private fun git(vararg a: String, dir: File = repo): String {
        val p = ProcessBuilder(listOf("git", *a)).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "git ${a.joinToString(" ")}: $out" }; return out
    }

    private val calc get() = File(repo, "core/src/main/kotlin/demo/Calc.kt")

    @BeforeEach fun setUp() {
        assumeTrue(!CommandRunner.isWindows && File("/bin/sh").exists() && runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false))
        repo = Files.createTempDirectory("clu-repair-repo").toFile().also { it.deleteOnExit() }
        home = Files.createTempDirectory("clu-repair-home").toFile().also { it.deleteOnExit() }
        git("init", "-q", "-b", "main"); git("config", "user.name", "Owner"); git("config", "user.email", "owner@example.invalid")
        File(repo, "settings.gradle.kts").writeText("rootProject.name = \"clu\"\ninclude(\":core\")\n")
        File(repo, ".gitignore").writeText("build/\n/*/build/\n")
        calc.parentFile.mkdirs(); calc.writeText("package demo\n\nobject Calc {\n    fun add(a: Int, b: Int) = a + b\n    fun sub(a: Int, b: Int) = a - b\n}\n")
        File(repo, "core/src/test/kotlin/demo").mkdirs()
        File(repo, "core/src/test/kotlin/demo/CalcTest.kt").writeText("package demo\n\nimport demo.Calc\n\nclass CalcTest {\n    fun adds() {\n        assertEquals(5, Calc.add(2, 3))\n    }\n    fun subtracts() { assertEquals(1, Calc.sub(3, 2)) }\n}\n")
        // The stand-in build: "compiles" unless Calc.kt has a syntax marker, and reports two tests as JUnit XML.
        File(repo, "runtests.sh").writeText("""
            #!/bin/sh
            out=core/build/test-results/test; mkdir -p ${'$'}out
            if grep -q 'SYNTAX ERROR' core/src/main/kotlin/demo/Calc.kt; then echo "e: Calc.kt: syntax error"; echo "> Task :core:compileKotlin FAILED"; exit 1; fi
            if grep -q 'a + b' core/src/main/kotlin/demo/Calc.kt; then add='<testcase name="adds()" classname="demo.CalcTest" time="0.01"/>'; rc=0
            else add='<testcase name="adds()" classname="demo.CalcTest" time="0.01"><failure message="expected: &lt;5&gt; but was: &lt;-1&gt;">org.opentest4j.AssertionFailedError: expected: &lt;5&gt; but was: &lt;-1&gt;
            	at app//org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
            	at app//demo.CalcTest.adds(CalcTest.kt:7)</failure></testcase>'; rc=1; fi
            if grep -q 'a - b' core/src/main/kotlin/demo/Calc.kt; then sub='<testcase name="subtracts()" classname="demo.CalcTest" time="0.01"/>'
            else sub='<testcase name="subtracts()" classname="demo.CalcTest"><failure message="expected: &lt;1&gt; but was: &lt;5&gt;">x</failure></testcase>'; rc=1; fi
            echo "<testsuite name=\"demo.CalcTest\">${'$'}add${'$'}sub</testsuite>" > ${'$'}out/TEST-demo.CalcTest.xml
            echo "> Task :core:test"; exit ${'$'}rc
        """.trimIndent() + "\n")
        git("add", "-A"); git("commit", "-q", "-m", "Initial")
    }

    private fun introduceDefect() { calc.writeText(calc.readText().replace("a + b", "a - b")); git("commit", "-qam", "Calc: rewrite add (defect)") }

    private fun service(model: RepairModel? = null) = SelfRepairService(EvolutionStore(JdbcSqlDriver(File(home, "evolution.db").path), SystemClock), File(home, "ws"),
        model = model, testCommand = { _, _ -> listOf("sh", "runtests.sh") }, commandTimeoutMs = 60_000).also { it.connect(repo) }

    private fun patch(from: String, to: String): String {
        val tmp = Files.createTempDirectory("p").toFile()
        git("clone", "-q", repo.path, tmp.path, dir = tmp.parentFile)
        val f = File(tmp, "core/src/main/kotlin/demo/Calc.kt"); f.writeText(f.readText().replace(from, to))
        return git("diff", dir = tmp).also { tmp.deleteRecursively() }
    }

    @Test fun aDefectIsDetectedInvestigatedRepairedVerifiedAndIntegratedOnlyAfterApproval() {
        introduceDefect()
        val head = git("rev-parse", "HEAD").trim(); val calcBefore = calc.readText()
        val svc = service()
        val o = runBlocking { svc.run(SelfRepairService.Options(patch = patch("fun add(a: Int, b: Int) = a - b", "fun add(a: Int, b: Int) = a + b"))) }
        val r = o.repair!!
        assertEquals(RepairRecord.AWAITING_APPROVAL, r.state, o.message)
        assertEquals(Verdict.VERIFIED, r.verdict!!.outcome)
        assertEquals(listOf("demo.CalcTest#adds()"), r.verdict!!.fixed)
        assertEquals(listOf("core/src/main/kotlin/demo/Calc.kt"), r.files)
        // Nothing in the owner's tree changed: same commit, same file, no new uncommitted change.
        assertEquals(head, git("rev-parse", "HEAD").trim()); assertEquals(calcBefore, calc.readText()); assertEquals("", git("status", "--porcelain"))
        assertTrue(git("branch", "--list", r.branch).isNotBlank(), "the repair lives on its own branch")
        // The investigation pointed at the right code and an experiment confirmed it.
        val inv = svc.investigator.get(r.investigationId)!!
        assertEquals("core/src/main/kotlin/demo/Calc.kt", inv.suspects.first().file); assertEquals("add", inv.suspects.first().symbol)
        assertEquals("H1", inv.confirmedCause?.id)
        assertEquals(listOf("detect", "reproduce", "verify"), svc.store.commands(r.id).map { it.phase })
        val report = svc.report(r.id)!!
        assertTrue("verified-fix" in report && "fun add(a: Int, b: Int) = a + b" in report && "Nothing has been integrated" in report)

        val a = svc.approve(r.id)
        assertEquals(RepairRecord.APPROVED, a.repair!!.state, a.message)
        assertTrue(calc.readText().contains("a + b"), "integrated into the owner's branch after approval")
        assertTrue(git("log", "-1", "--format=%B").contains("CLU-Repair: ${r.id}"))
        assertTrue(git("log", "--format=%s").contains("Repair: CalcTest › adds() fails"), "the commit subject keeps its UTF-8 characters")
        assertFalse(File(r.worktree).exists())
        assertEquals("clu-repair", svc.reconciler.recentCommits(5).first { it.subject.startsWith("Repair:") }.provenance)
        assertEquals("resolved", svc.incidents.get(r.incidentId)!!.status)
        // Development history survives a restart (a new service over the same database).
        val again = service()
        assertTrue(again.store.recentHistory(100).map { it.kind }.containsAll(listOf("incident-detected", "investigation", "experiment", "repair-proposed", "repair-approved")))
    }

    @Test fun approvalNeverOverwritesUncommittedOwnerWork() {
        introduceDefect()
        val svc = service()
        val r = runBlocking { svc.run(SelfRepairService.Options(patch = patch("fun add(a: Int, b: Int) = a - b", "fun add(a: Int, b: Int) = a + b"))) }.repair!!
        val mine = calc.readText() + "\n// owner's work in progress\n"; calc.writeText(mine)
        val a = svc.approve(r.id)
        assertEquals(RepairRecord.AWAITING_APPROVAL, a.repair!!.state); assertTrue("uncommitted changes" in a.message)
        assertEquals(mine, calc.readText(), "the owner's uncommitted edit is untouched")
    }

    @Test fun aPatchThatWeakensTheTestIsRefusedBeforeAnyBuild() {
        introduceDefect()
        val svc = service()
        val tmp = Files.createTempDirectory("p").toFile(); git("clone", "-q", repo.path, tmp.path, dir = tmp.parentFile)
        val t = File(tmp, "core/src/test/kotlin/demo/CalcTest.kt"); t.writeText(t.readText().replace("assertEquals(5, Calc.add(2, 3))", "assertEquals(-1, Calc.add(2, 3))"))
        val weakening = git("diff", dir = tmp)
        val o = runBlocking { svc.run(SelfRepairService.Options(patch = weakening)) }
        assertEquals(RepairRecord.FAILED_VERIFICATION, o.repair!!.state)
        assertEquals(Verdict.REVIEW_BLOCKED, o.repair!!.verdict!!.outcome)
        assertEquals(listOf("detect", "reproduce"), svc.store.commands(o.repair!!.id).map { it.phase }, "nothing was built after the review refused it")
        assertFalse(File(o.repair!!.worktree).exists()); assertTrue(git("branch", "--list", o.repair!!.branch).isBlank(), "the failed experiment is discarded")
    }

    @Test fun aRepairThatDoesNotWorkIsRecordedAndTheSamePatchIsNotTriedAgain() {
        introduceDefect()
        val svc = service()
        val useless = patch("object Calc {", "object Calc { // tidy")
        val first = runBlocking { svc.run(SelfRepairService.Options(patch = useless)) }.repair!!
        assertEquals(RepairRecord.FAILED_VERIFICATION, first.state); assertEquals(Verdict.NOT_FIXED, first.verdict!!.outcome)
        val second = runBlocking { svc.run(SelfRepairService.Options(patch = useless)) }
        assertEquals(RepairRecord.PATCH_FAILED, second.repair!!.state); assertTrue("already tried" in second.message, second.message)
        assertTrue(svc.investigator.get(second.repair!!.investigationId)!!.priorAttempts.any { it.startsWith(first.id.take(8)) }, "the earlier attempt is part of the new investigation")
    }

    @Test fun cluCanWriteThePatchWithItsOwnModelAndAnUnusableAnswerIsReportedHonestly() {
        introduceDefect()
        val good = object : RepairModel {
            override val description = "scripted"; override val lastError: String? = null
            override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float, seed: Int, grammar: String?, reasoning: Boolean): String {
                assertTrue("Calc" in user && "expected <5> but was <-1>" in user, "the model sees the failure and the suspected code")
                return when (grammar) {
                    StructuredPatches.LOCATE_GRAMMAR -> "{\"file\": \"core/src/main/kotlin/demo/Calc.kt\", \"line\": 4, \"why\": \"subtracts\"}"
                    StructuredPatches.RAW_LINE_GRAMMAR -> "    fun add(a: Int, b: Int) = a + b"
                    else -> "{\"cause\": \"subtracts\", \"edits\": [{\"file\": \"core/src/main/kotlin/demo/Calc.kt\", \"op\": \"replace\", \"start\": 4, \"end\": 4, \"code\": \"    fun add(a: Int, b: Int) = a + b\"}]}"
                }
            }
        }
        val r = runBlocking { service(good).run(SelfRepairService.Options()) }.repair!!
        assertEquals(RepairRecord.AWAITING_APPROVAL, r.state); assertEquals("model", r.source)
        assertTrue(r.attempts.single().contains("VERIFIED")); assertEquals(1, service(good).experience.attempts(r.id).size)
        service(good).reject(r.id, "testing rejection").also { assertEquals(RepairRecord.REJECTED, it.repair!!.state) }
        assertTrue(git("branch", "--list", r.branch).isBlank())

        val vague = object : RepairModel {
            override val description = "weak"; override val lastError: String? = null
            override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float, seed: Int, grammar: String?, reasoning: Boolean) = "You should probably check the add function."
        }
        val o = runBlocking { service(vague).run(SelfRepairService.Options(budget = CodingBudget(maxAttempts = 3))) }
        assertEquals(RepairRecord.PATCH_FAILED, o.repair!!.state); assertTrue("did not produce a working patch in 3 attempt(s)" in o.message, o.message)
        assertTrue(service(vague).experience.attempts(o.repair!!.id).all { it["failure"] == "MALFORMED" })
        assertEquals("", git("status", "--porcelain"))
    }

    @Test fun aHealthyProjectIsCheckedAndLeftAlone() {
        val o = runBlocking { service().run(SelfRepairService.Options()) }
        assertEquals(RepairRecord.DISCARDED, o.repair!!.state); assertTrue("No problem found" in o.message)
        assertFalse(File(o.repair!!.worktree).exists())
    }
}
