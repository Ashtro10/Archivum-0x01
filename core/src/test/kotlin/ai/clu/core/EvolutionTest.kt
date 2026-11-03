package ai.clu.core

import ai.clu.core.agent.TurnRequest
import ai.clu.core.evolution.*
import ai.clu.core.llm.*
import ai.clu.core.util.ManualClock
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import org.junit.jupiter.api.Test

/** Self-repair building blocks: the source index, reconciliation, self-knowledge, diagnosis, patches, review and verification. */
class EvolutionTest {
    /** A repository in memory: files, commits, uncommitted paths. */
    private class FakeRepo(override val root: String = "/repo") : RepoReader {
        val content = linkedMapOf<String, String>(); val commits = mutableListOf<CommitInfo>(); var dirtyPaths = listOf<String>()
        override fun files() = content.mapValues { (_, v) -> Integer.toHexString(v.hashCode()) }
        override fun read(path: String) = content[path]
        override fun head() = commits.lastOrNull()?.hash ?: "0000000"
        override fun branch() = "main"
        override fun dirty() = dirtyPaths
        override fun commitsSince(since: String?, limit: Int): List<CommitInfo> {
            val i = if (since == null) 0 else commits.indexOfFirst { it.hash == since } + 1
            return commits.drop(i).takeLast(limit)
        }
        fun commit(hash: String, subject: String, author: String = "Owner", body: String = "", vararg changes: Pair<Char, String>) {
            commits += CommitInfo(hash, author, commits.size * 1000L, subject, body, changes.map { FileChange(it.first, it.second) })
        }
    }

    private fun store() = EvolutionStore(tempDb(), ManualClock(1_760_000_000_000L))

    private fun cluLikeRepo() = FakeRepo().apply {
        content["settings.gradle.kts"] = "rootProject.name = \"clu\"\ninclude(\":core\", \":desktop\")"
        content["core/build.gradle.kts"] = "plugins { kotlin(\"jvm\") }"
        content["desktop/build.gradle.kts"] = "dependencies { implementation(project(\":core\")) }"
        content["core/src/main/kotlin/ai/clu/core/memory/MemoryService.kt"] = """
            package ai.clu.core.memory
            /** The one memory API: recall, remember, forget. */
            class MemoryService {
                fun recall(q: String) = q
            }
        """.trimIndent()
        content["core/src/main/kotlin/ai/clu/core/llm/InferenceService.kt"] = """
            package ai.clu.core.llm
            // class NotADeclaration in a comment
            val s = "class AlsoNotADeclaration"
            /** The one way components use the local model. */
            class InferenceService { fun generate() = 1 }
        """.trimIndent()
        content["core/src/main/kotlin/ai/clu/core/agents/Broker.kt"] = "package ai.clu.core.agents\n/** Decides every tool call. */\nclass PermissionBroker {\n    fun authorize(x: Int) = x > 0\n}\n"
        content["core/src/test/kotlin/ai/clu/core/AgentsTest.kt"] = "package ai.clu.core\nimport ai.clu.core.agents.PermissionBroker\nclass AgentsTest {\n  fun t() { assertTrue(PermissionBroker().authorize(1)) }\n}\n"
        commit("c1", "Initial import", changes = arrayOf('A' to "core/src/main/kotlin/ai/clu/core/memory/MemoryService.kt"))
    }

    @Test fun theScannerFindsDeclarationsButNotWordsInsideStringsOrComments() {
        val s = KotlinScanner.scan(cluLikeRepo().content["core/src/main/kotlin/ai/clu/core/llm/InferenceService.kt"]!!)
        assertEquals("ai.clu.core.llm", s.pkg)
        assertEquals(listOf("InferenceService", "generate"), s.declarations.map { it.name })
        assertEquals(5, s.declarations.first().line)
        assertEquals("The one way components use the local model", s.declarations.first().doc)
        val k = KotlinScanner.scan("package a\nimport b.C\nenum class Mode { A }\ndata class D(val x: Int)\nfun interface F { fun f() }\nsealed interface S")
        assertEquals(listOf("b.C"), k.imports)
        assertEquals(mapOf("Mode" to "enum", "D" to "data class", "F" to "fun interface", "S" to "interface"), k.declarations.filter { it.name != "f" }.associate { it.name to it.kind })
    }

    @Test fun theIndexIsPersistentAndOnlyChangedFilesAreReadAgain() {
        val st = store(); val idx = SourceIndexer(st); val repo = cluLikeRepo()
        val first = idx.update(repo)
        assertEquals(first.files, first.added); assertEquals(0, first.changed)
        assertEquals(listOf("core", "desktop"), idx.modules())
        assertTrue(Triple("desktop", "core", "implementation") in idx.moduleDeps())
        val reads = CopyOnWriteArrayList<String>()
        val counting = object : RepoReader by repo { override fun read(path: String) = repo.read(path).also { reads += path } }
        assertEquals(0, idx.update(counting).let { it.added + it.changed + it.removed }); assertTrue(reads.isEmpty(), "nothing re-read when nothing changed: $reads")
        repo.content["core/src/main/kotlin/ai/clu/core/memory/MemoryService.kt"] += "\nclass MemoryHealth"
        repo.content.remove("core/src/main/kotlin/ai/clu/core/agents/Broker.kt")
        val third = idx.update(counting)
        assertEquals(1, third.changed); assertEquals(1, third.removed); assertEquals(listOf("core/src/main/kotlin/ai/clu/core/memory/MemoryService.kt"), reads.toList())
        assertEquals("core/src/main/kotlin/ai/clu/core/memory/MemoryService.kt", idx.symbols("MemoryHealth").single().file)
        assertTrue(idx.symbols("PermissionBroker").isEmpty())
    }

    @Test fun externalCommitsAreRecognisedAsLegitimateDevelopmentAndUncommittedWorkIsOnlyNoted() {
        val st = store(); val idx = SourceIndexer(st); val rec = Reconciler(st, idx); val repo = cluLikeRepo()
        assertTrue(rec.reconcile(repo).firstRun)
        repo.content["core/src/main/kotlin/ai/clu/core/db/Schema.kt"] = "package ai.clu.core.db\nobject Schema"
        repo.commit("c2", "Add schema v9", "Claude Code", "", 'A' to "core/src/main/kotlin/ai/clu/core/db/Schema.kt", 'M' to "core/build.gradle.kts")
        repo.commit("c3", "CLU's own repair", "CLU", "CLU-Repair: r-1", 'M' to "core/src/main/kotlin/ai/clu/core/memory/MemoryService.kt")
        repo.content["core/src/main/kotlin/ai/clu/core/memory/MemoryService.kt"] += "\n// work in progress"
        repo.dirtyPaths = listOf("core/src/main/kotlin/ai/clu/core/memory/MemoryService.kt")
        val r = rec.reconcile(repo) { it == "r-1" }
        assertEquals(listOf("c2", "c3"), r.newCommits.map { it.hash })
        assertEquals(listOf("external", "clu-repair"), r.newCommits.map { it.provenance }, "only a recorded repair id makes a commit CLU's own")
        assertEquals(listOf("database schema", "build/dependencies"), r.newCommits.first().categories)
        assertEquals(1, r.index.added, "the new file is indexed")
        assertEquals("external-uncommitted", idx.file("core/src/main/kotlin/ai/clu/core/memory/MemoryService.kt")!!.provenance)
        assertTrue(st.recentHistory(kind = "external-change").any { it.summary.contains("Add schema v9") && it.summary.startsWith("External") })
        // Another repair id that CLU never recorded does not make a commit CLU's own.
        repo.commit("c4", "Pretends to be CLU", "Someone", "CLU-Repair: forged", 'M' to "README.md")
        assertEquals("external", rec.reconcile(repo) { it == "r-1" }.newCommits.single().provenance)
    }

    @Test fun cluAnswersQuestionsAboutItselfFromTheIndex() {
        val st = store(); val idx = SourceIndexer(st); val rec = Reconciler(st, idx); rec.reconcile(cluLikeRepo())
        val k = SelfKnowledge(idx, rec, st)
        assertTrue(k.answer("What components make up your architecture?").text.let { "core" in it && "desktop: " in it && "depends on core" in it })
        assertTrue(k.answer("Where is memory implemented?").text.lines().first { it.startsWith("- ") }.contains("memory/MemoryService.kt"))
        assertTrue(k.answer("Which files control model inference?").text.contains("llm/InferenceService.kt"))
        assertTrue(k.answer("Which tests cover the permission broker?").text.contains("AgentsTest.kt"))
        assertTrue(k.answer("What changed in your latest development update?").text.contains("Initial import"))
        assertTrue(k.isAboutSelf("Which module handles your memory?")); assertFalse(k.isAboutSelf("Remember that my car is red"))
        assertTrue(k.factsFor("What's the weather today?").isEmpty())
    }

    @Test fun theConversationGetsGroundedFactsWhenAskedAboutItself() {
        val core = CluCore(tempDb(), TestPlatform(), ManualClock(1_760_000_000_000L))
        val st = EvolutionStore(tempDb(), ManualClock(1)); val idx = SourceIndexer(st); val rec = Reconciler(st, idx); rec.reconcile(cluLikeRepo())
        core.selfKnowledge = SelfKnowledge(idx, rec, st)
        val prompts = CopyOnWriteArrayList<String>()
        core.useEngine(object : LlmEngine {
            override val id = "m"; override val source = "local"; override fun isReady() = true; override fun cancel() {}
            override fun countTokens(messages: List<ChatMessage>) = messages.sumOf { it.content.length / 4 }; override fun contextSize() = 8192
            override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult {
                prompts += messages.joinToString("\n") { it.content }; onText("It is in MemoryService."); return GenResult("It is in MemoryService.", GenStats(stopReason = "eog"), "local") }
        })
        val conv = core.conversations.create().id; val owner = core.auth.actorForSpeaker(null, speakerIdActive = false)!!
        runBlocking { core.agent.respond(TurnRequest(conv, "Where is your memory implemented?", owner)).toList() }
        assertTrue(prompts.last().contains("Facts about your own software") && prompts.last().contains("memory/MemoryService.kt"))
        runBlocking { core.agent.respond(TurnRequest(conv, "Tell me a joke about cats.", owner)).toList() }
        assertFalse(prompts.last().contains("Facts about your own software"), "only for questions about itself")
    }

    @Test fun testReportsAndStackTracesAreParsedIncludingModulePrefixedFrames() {
        val xml = """<?xml version="1.0"?><testsuite name="a.BTest"><testcase name="ok" classname="a.BTest" time="0.1"/>
            <testcase name="bad" classname="a.BTest" time="0.2"><failure message="expected: &lt;1&gt; but was: &lt;2&gt;">org.opentest4j.AssertionFailedError: x
            	at app//org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
            	at app//a.BTest.bad(BTest.kt:12)
            	at java.base@21/java.lang.Thread.run(Thread.java:1583)</failure></testcase>
            <testcase name="skip" classname="a.BTest"><skipped/></testcase></testsuite>"""
        val r = JUnitXml.parse(xml)
        assertEquals(listOf("passed", "failed", "skipped"), r.map { it.status })
        val frames = StackTraces.frames(r[1].trace).filterNot(StackTraces::isFramework)
        assertEquals(listOf(StackFrame("a.BTest", "bad", "BTest.kt", 12)), frames)
        assertEquals("expected <1> but was <2>", Investigator.parseAssertion(r[1].message))
        assertTrue(JUnitXml.parse("<not xml").isEmpty())
        assertTrue(JUnitXml.parse("""<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><testsuite><testcase name="&e;" classname="c"/></testsuite>""").isEmpty(), "no external entities")
    }

    @Test fun theInvestigatorPointsAtTheCodeTheFailingLineCallsAndOnlyExperimentsConfirm() {
        val st = store(); val idx = SourceIndexer(st); val repo = cluLikeRepo()
        repo.content["core/src/main/kotlin/ai/clu/core/calc/Calc.kt"] = "package ai.clu.core.calc\n\nobject Calc {\n    fun add(a: Int, b: Int) = a - b\n}\n"
        repo.content["core/src/test/kotlin/ai/clu/core/CalcTest.kt"] = "package ai.clu.core\n\nimport ai.clu.core.calc.Calc\n\nclass CalcTest {\n    fun adds() {\n        assertEquals(5, Calc.add(2, 3))\n    }\n}\n"
        idx.update(repo)
        val failure = TestCaseResult("ai.clu.core.CalcTest", "adds()", TestCaseResult.FAILED, "expected: <5> but was: <-1>",
            "org.opentest4j.AssertionFailedError: expected: <5> but was: <-1>\n\tat app//kotlin.test.AssertionsKt.assertEquals(Unknown Source)\n\tat app//ai.clu.core.CalcTest.adds(CalcTest.kt:7)")
        val incidents = Incidents(st)
        val inc = incidents.report("test-failure", Incidents.testSignature(failure), "CalcTest › adds fails", "", "c1")
        assertEquals(inc.id, incidents.report("test-failure", Incidents.testSignature(failure), "CalcTest › adds fails", "", "c1").id, "a recurrence is the same incident")
        assertEquals(2, incidents.get(inc.id)!!.count)
        val inv = Investigator(st, idx).investigate(inc, listOf(failure), repo::read, { emptyList() }, "gradle test",
            lastChangedAt = { if (it.contains("Calc.kt")) 2000L else 1000L })
        val top = inv.suspects.first()
        assertEquals("core/src/main/kotlin/ai/clu/core/calc/Calc.kt", top.file); assertEquals("add", top.symbol); assertEquals(4, top.line)
        assertTrue(inv.testExcerpt.contains(">   7"))
        assertEquals(Hypothesis.SUPPORTED, inv.hypotheses.first { it.id == "H1" }.status)
        assertEquals(Hypothesis.PROPOSED, inv.hypotheses.first { it.id == "H2" }.status, "the code changed after the test: no support for a wrong expectation")
        assertNull(inv.confirmedCause, "nothing is confirmed without an experiment")
        val withModel = Investigator(st, idx).addModelHypothesis(inv, "The subtraction should be an addition.")
        assertEquals(Hypothesis.PROPOSED, withModel.hypotheses.last().status); assertEquals("model", withModel.hypotheses.last().origin)
        val done = Investigator(st, idx).recordExperiment(withModel, Experiment("repair", "p", "cmd", "passes", "passes", "verified-fix", 1), confirms = listOf("H1"), refutes = listOf("H3"))
        assertEquals("H1", done.confirmedCause?.id)
        assertEquals(done, Investigator(st, idx).get(done.id), "the investigation persists")
    }

    @Test fun editBlocksApplyOnlyWhenTheyMatchExactlyOnce() {
        val blocks = PatchFormat.parse("Here:\nFILE: `a/B.kt`\n<<<<<<< SEARCH\n    fun add(a: Int, b: Int) = a - b\n=======\n    fun add(a: Int, b: Int) = a + b\n>>>>>>> REPLACE\nDone.")
        assertEquals(listOf(EditBlock("a/B.kt", "    fun add(a: Int, b: Int) = a - b", "    fun add(a: Int, b: Int) = a + b")), blocks)
        val src = "object Calc {\n    fun add(a: Int, b: Int) = a - b\n}\n"
        assertEquals("object Calc {\n    fun add(a: Int, b: Int) = a + b\n}\n", (PatchFormat.apply(src, blocks) as PatchFormat.Applied.Ok).text)
        val reindented = EditBlock("a/B.kt", "fun add(a: Int, b: Int) = a - b", "fun add(a: Int, b: Int) = a + b")
        assertEquals("object Calc {\n    fun add(a: Int, b: Int) = a + b\n}\n", (PatchFormat.apply(src, listOf(reindented)) as PatchFormat.Applied.Ok).text, "indentation differences tolerated")
        assertIs<PatchFormat.Applied.Failed>(PatchFormat.apply(src, listOf(EditBlock("a/B.kt", "fun missing()", "x"))))
        assertIs<PatchFormat.Applied.Failed>(PatchFormat.apply("x\nx\n", listOf(EditBlock("f", "x", "y"))), "ambiguous edits are refused")
        assertTrue(PatchFormat.parse("I think you should change the minus to a plus.").isEmpty())
    }

    private fun diff(path: String, vararg lines: String) = "diff --git a/$path b/$path\n--- a/$path\n+++ b/$path\n@@ -1 +1 @@\n" + lines.joinToString("\n")

    @Test fun theReviewBlocksWeakenedTestsAndTrustCriticalChanges() {
        assertTrue(DiffReview.review(diff("core/src/main/kotlin/x/Calc.kt", "-a - b", "+a + b"), listOf("core/src/main/kotlin/x/Calc.kt")).isEmpty())
        fun blocking(d: String) = DiffReview.review(d, emptyList()).filter { it.severity == ReviewFinding.BLOCKING }.map { it.message }
        assertTrue(blocking(diff("core/src/test/kotlin/x/CalcTest.kt", "-        assertEquals(5, Calc.add(2, 3))")).any { "removes 1 assertion" in it })
        assertTrue(blocking(diff("core/src/test/kotlin/x/CalcTest.kt", "-        assertEquals(5, Calc.add(2, 3))", "+        assertEquals(-1, Calc.add(2, 3))")).any { "changes an existing assertion" in it })
        assertTrue(blocking(diff("core/src/test/kotlin/x/CalcTest.kt", "+    @Disabled")).any { "disables a test" in it })
        assertTrue(blocking(diff("core/src/main/kotlin/ai/clu/core/agents/Broker.kt", "-deny", "+allow")).any { "trust-critical" in it })
        assertTrue(blocking(diff("core/build.gradle.kts", "+implementation(\"evil:lib:1\")")).any { "trust-critical" in it })
        assertTrue(blocking("").any { "changes nothing" in it })
        assertEquals(ReviewFinding.WARNING, DiffReview.review(diff("core/src/main/kotlin/x/Other.kt", "-a", "+b"), listOf("core/src/main/kotlin/x/Calc.kt")).single().severity)
    }

    private fun run(label: String, vararg r: Pair<String, String>, compiled: Boolean = true) =
        TestRun(label, "gradle test", if (r.any { it.second == "failed" }) 1 else 0, compiled, r.map { TestCaseResult("S", it.first, it.second) }, 10)

    @Test fun onlyAFailureThatTurnsIntoAPassWithNoRegressionIsAVerifiedFix() {
        val before = run("before", "a" to "failed", "b" to "passed", "c" to "passed")
        assertEquals(Verdict.VERIFIED, Verification.compare(listOf("S#a"), before, run("after", "a" to "passed", "b" to "passed", "c" to "passed"), emptyList()).outcome)
        assertEquals(Verdict.BUILD_FAILED, Verification.compare(listOf("S#a"), before, run("after", compiled = false), emptyList()).outcome, "compilation is checked first")
        assertEquals(Verdict.REGRESSION, Verification.compare(listOf("S#a"), before, run("after", "a" to "passed", "b" to "failed", "c" to "passed"), emptyList()).outcome)
        assertEquals(Verdict.REGRESSION, Verification.compare(listOf("S#a"), before, run("after", "a" to "passed", "b" to "passed"), emptyList()).outcome, "a test that vanished is a regression")
        assertEquals(Verdict.NOT_FIXED, Verification.compare(listOf("S#a"), before, run("after", "a" to "failed", "b" to "passed", "c" to "passed"), emptyList()).outcome)
        assertEquals(Verdict.INCONCLUSIVE, Verification.compare(listOf("S#a"), before, run("after"), emptyList()).outcome, "a compiling build with no tests run proves nothing")
        assertEquals(Verdict.INCONCLUSIVE, Verification.compare(listOf("S#b"), before, run("after", "a" to "passed", "b" to "passed", "c" to "passed"), emptyList()).outcome, "the target must fail before")
        assertEquals(Verdict.REVIEW_BLOCKED, Verification.compare(listOf("S#a"), before, run("after", "a" to "passed", "b" to "passed", "c" to "passed"),
            listOf(ReviewFinding(ReviewFinding.BLOCKING, "t", "removes a test"))).outcome)
    }

    @Test fun developmentHistorySurvivesARestart() {
        val f = File.createTempFile("evolution", ".db").also { it.delete(); it.deleteOnExit() }
        EvolutionStore(JdbcDriver(f.absolutePath), ManualClock(5)).also { s ->
            s.history("repair-rejected", "r1", "Owner rejected repair r1: wrong file", "")
            RepairStore(s).save(RepairRecord("r1", "i1", "v1", RepairRecord.REJECTED, "model", "abc", "clu/repair/r1", "/w", patchHash = "h1"))
            s.db.close()
        }
        val again = EvolutionStore(JdbcDriver(f.absolutePath), ManualClock(6))
        assertEquals("Owner rejected repair r1: wrong file", again.recentHistory().single().summary)
        assertNotNull(RepairStore(again).failedBefore("i1", "h1"), "a rejected patch is remembered and not proposed again")
        again.db.close()
    }

    @Test fun theReportStatesTheVerdictTheCauseAndEveryCommand() {
        val st = store()
        val inv = Investigation("v1", "i1", "cause-confirmed", listOf("S#a"), "AssertionFailedError", "expected <5> but was <-1>",
            listOf(Suspect("Calc.kt", "add", 4, "called on the failing line", 4.5)), "> 7  assertEquals(5, Calc.add(2, 3))",
            listOf(Hypothesis("H1", "A defect in add", Hypothesis.CONFIRMED, "deterministic", listOf("e"), emptyList(), "p", listOf("Calc.kt"))), emptyList(), "gradle test", "Confirmed")
        val r = RepairRecord("r1", "i1", "v1", RepairRecord.FAILED_VERIFICATION, "model", "abc123", "clu/repair/r1", "/w", files = listOf("Calc.kt"), diff = "-a\n+b",
            before = run("before", "a" to "failed"), after = run("after", "a" to "failed"), verdict = Verdict(Verdict.NOT_FIXED, "s", emptyList(), listOf("S#a"), emptyList(), emptyList(), 1, 1, listOf("1 target test(s) still fail")))
        val cmd = st.recordCommand("r1", "verify", "./gradlew :core:test", "/w", 1, 1200, false, "BUILD FAILED\ntoken=Bearer abcdefghijklmnopqrstuvwxyz0123456789")
        val md = RepairReport.markdown(r, null, inv, listOf(cmd))
        assertTrue("**not-fixed**" in md && "Confirmed by experiment:" in md && "`./gradlew :core:test` in `/w` → exit 1" in md && "The repair is not verified" in md)
        assertFalse(st.commands("r1").single().output.contains("abcdefghijklmnopqrstuvwxyz0123456789"), "secrets are redacted from stored output")
    }
}
