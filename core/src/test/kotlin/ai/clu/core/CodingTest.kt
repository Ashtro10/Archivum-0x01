package ai.clu.core

import ai.clu.core.evolution.*
import ai.clu.core.util.ManualClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import org.junit.jupiter.api.Test

/** CLU's development mode: structured patches, code context, failure analysis and the iterative coding loop. */
class CodingTest {
    private val calcPath = "core/src/main/kotlin/demo/Calc.kt"
    private val calc = "package demo\n\nobject Calc {\n    fun add(a: Int, b: Int): Int = a - b\n\n    fun sub(a: Int, b: Int): Int = a - b\n}\n"
    private val window = listOf(StructuredPatches.Window(calcPath, 2, 7))
    private fun edit(vararg e: LineEdit) = StructuredPatch("c", e.toList())
    private fun ok(c: StructuredPatches.Checked) = (c as StructuredPatches.Checked.Ok).files
    private fun rejected(c: StructuredPatches.Checked) = (c as StructuredPatches.Checked.Rejected).reason

    @Test fun aStructuredPatchIsValidatedAgainstTheShownCodeBeforeAnythingIsWritten() {
        val src = { p: String -> if (p == calcPath) calc else null }
        assertEquals(calc.replace("Int = a - b\n\n", "Int = a + b\n\n"), ok(StructuredPatches.check(edit(LineEdit(calcPath, "replace", 4, 4, "    fun add(a: Int, b: Int): Int = a + b")), src, window, false))[calcPath])
        assertTrue("outside the code that was shown" in rejected(StructuredPatches.check(edit(LineEdit(calcPath, "replace", 1, 1, "package x")), src, window, false)))
        assertTrue("test file" in rejected(StructuredPatches.check(edit(LineEdit("core/src/test/kotlin/demo/CalcTest.kt", "replace", 1, 1, "x")), src, window, false)))
        assertTrue("does not exist" in rejected(StructuredPatches.check(edit(LineEdit("core/src/main/kotlin/demo/Nope.kt", "replace", 1, 1, "x")), src, window, false)))
        assertTrue("changes nothing" in rejected(StructuredPatches.check(edit(LineEdit(calcPath, "replace", 4, 4, "    fun add(a: Int, b: Int): Int = a - b")), src, window, false)))
        assertTrue("overlap" in rejected(StructuredPatches.check(edit(LineEdit(calcPath, "replace", 4, 6, "x"), LineEdit(calcPath, "replace", 6, 6, "y")), src, window, false)))
        assertTrue("invalid file path" in rejected(StructuredPatches.check(edit(LineEdit("../etc/passwd", "replace", 1, 1, "x")), src, window, false)))
        assertTrue("no code" in rejected(StructuredPatches.check(edit(LineEdit(calcPath, "replace", 4, 4, "  ")), src, window, false)))
        // Indentation dropped by the model is restored; deleting and inserting work.
        assertTrue(ok(StructuredPatches.check(edit(LineEdit(calcPath, "replace", 4, 4, "fun add(a: Int, b: Int): Int = a + b")), src, window, false))[calcPath]!!.contains("    fun add(a: Int, b: Int): Int = a + b"))
        assertFalse(ok(StructuredPatches.check(edit(LineEdit(calcPath, "delete", 5, 6)), src, window, false))[calcPath]!!.contains("sub"))
        assertTrue(ok(StructuredPatches.check(edit(LineEdit(calcPath, "insert", 6, 6, "    fun mul(a: Int, b: Int): Int = a * b")), src, window, false))[calcPath]!!.lines()[6].contains("mul"))
        // A new file only where the task allows it.
        assertTrue("does not exist" in rejected(StructuredPatches.check(edit(LineEdit("core/src/main/kotlin/demo/New.kt", "insert", 1, 1, "package demo")), src, window, false)))
        assertEquals("package demo\n", ok(StructuredPatches.check(edit(LineEdit("core/src/main/kotlin/demo/New.kt", "insert", 1, 1, "package demo")), src, window, false, setOf("core/src/main/kotlin/demo/New.kt")))["core/src/main/kotlin/demo/New.kt"])
    }

    @Test fun aRangeOneOrTwoLinesTooWideIsTightenedButNothingElseIsGuessed() {
        val src = { p: String -> if (p == calcPath) calc else null }
        // end=5 covers a blank line after the one-line replacement: the blank line is kept.
        assertEquals(calc.replace("a - b\n\n    fun sub", "a + b\n\n    fun sub"), ok(StructuredPatches.check(edit(LineEdit(calcPath, "replace", 4, 5, "    fun add(a: Int, b: Int): Int = a + b")), src, window, false))[calcPath])
        // An end past the file is cut to the replacement's length.
        assertEquals(calc.replace("a - b\n\n    fun sub", "a + b\n\n    fun sub"), ok(StructuredPatches.check(edit(LineEdit(calcPath, "replace", 4, 9, "    fun add(a: Int, b: Int): Int = a + b")), src, window, false))[calcPath])
    }

    @Test fun patchesAreParsedFromTheModelAnswerOrRefused() {
        assertEquals(4, StructuredPatches.parse("Sure! {\"cause\": \"x\", \"edits\": [{\"file\": \"a.kt\", \"op\": \"replace\", \"start\": 4, \"end\": 4, \"code\": \"y\"}]} done")!!.edits.single().start)
        assertNull(StructuredPatches.parse("Change the minus to a plus."))
        assertNull(StructuredPatches.parse("{\"cause\": \"x\", \"edits\": []}"), "a patch with no edits is no patch")
        assertNotEquals(StructuredPatches.fingerprint(mapOf("a" to "x = 1")), StructuredPatches.fingerprint(mapOf("a" to "x = 2")))
        assertEquals(StructuredPatches.fingerprint(mapOf("a" to "x  =  1\n")), StructuredPatches.fingerprint(mapOf("a" to "x = 1")), "whitespace-only differences are the same change")
    }

    @Test fun theContextIsTheEnclosingFunctionWithLineNumbers() {
        val src = "package demo\n\nobject Words {\n    /** Longest. */\n    fun longest(words: List<String>): String? {\n        var best: String? = null\n        for (w in words) if (best == null || w.length < best.length) best = w\n        return best\n    }\n\n    fun other() = 1\n}\n"
        val w = CodeContext.window("Words.kt", src, 7, "suspect", pad = 0)
        assertEquals(5, w.from); assertEquals(9, w.to)
        assertTrue(w.numbered.lines().first().startsWith("   5| ") && w.numbered.contains("   7|         for (w in words)"))
        assertFalse(w.numbered.contains("other"))
    }

    @Test fun compilerErrorsAreReadAndClassified() {
        val out = "> Task :core:compileKotlin FAILED\ne: file:///ws/abc/core/src/main/kotlin/demo/Calc.kt:4:37 Unresolved reference 'F'.\ne: file:///ws/abc/core/src/main/kotlin/demo/Calc.kt:9:1 Syntax error: Expecting '}'.\n"
        val errors = Failures.compilerErrors(out, "/ws/abc")
        assertEquals(listOf(CompilerError("core/src/main/kotlin/demo/Calc.kt", 4, "Unresolved reference 'F'."), CompilerError("core/src/main/kotlin/demo/Calc.kt", 9, "Syntax error: Expecting '}'.")), errors)
        assertFalse(Failures.isSyntax(errors)); assertTrue(Failures.isSyntax(errors.drop(1)))
        assertTrue("Calc.kt:4: Unresolved reference" in Failures.feedback(FailureKind.COMPILE, errors, null, emptyList()))
    }

    /** An in-memory workspace whose "build" compiles if braces balance and whose test passes if add() adds. */
    private class FakeWorkspace(val base: Map<String, String>) : DevWorkspace {
        val files = base.toMutableMap(); var builds = 0
        override val root = "/ws"
        override fun read(path: String) = files[path]
        override fun write(files: Map<String, String>): Boolean { this.files.putAll(files); return true }
        override fun reset() { files.clear(); files.putAll(base) }
        override fun diff() = files.filter { base[it.key] != it.value }.keys.joinToString("\n") { "diff --git a/$it b/$it\n--- a/$it\n+++ b/$it\n@@ -1 +1 @@\n-old\n+new" }
        private var out = ""
        override fun buildOutput() = out
        override fun test(label: String, phase: String): TestRun {
            builds++
            val src = files["core/src/main/kotlin/demo/Calc.kt"]!!
            if (src.count { it == '{' } != src.count { it == '}' }) { out = "e: file:///ws/core/src/main/kotlin/demo/Calc.kt:7:1 Syntax error: Expecting '}'."; return TestRun(label, "t", 1, false, emptyList(), 5) }
            if ("= F" in src) { out = "e: file:///ws/core/src/main/kotlin/demo/Calc.kt:4:37 Unresolved reference 'F'."; return TestRun(label, "t", 1, false, emptyList(), 5) }
            out = ""
            val adds = "a + b" in src.lines()[3]; val subs = "a - b" in src.lines().getOrElse(5) { "" }
            return TestRun(label, "t", if (adds && subs) 0 else 1, true, listOf(TestCaseResult("demo.CalcTest", "adds()", if (adds) "passed" else "failed", if (adds) "" else "expected: <5> but was: <-1>"),
                TestCaseResult("demo.CalcTest", "subtracts()", if (subs) "passed" else "failed")), 5)
        }
    }

    private class Scripted(val answers: List<String?>) : DevModel {
        val prompts = CopyOnWriteArrayList<String>(); var i = 0
        override val description = "scripted"; override val lastError: String? = "offline"
        override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float, seed: Int, grammar: String?, reasoning: Boolean): String? {
            prompts += user; return answers.getOrElse(i++) { "nothing" } }
    }

    private fun patch(start: Int, end: Int, code: String) = Json.encodeToString(StructuredPatch.serializer(), StructuredPatch("c", listOf(LineEdit("core/src/main/kotlin/demo/Calc.kt", "replace", start, end, code))))

    private val before = TestRun("before", "t", 1, true, listOf(TestCaseResult("demo.CalcTest", "adds()", "failed", "expected: <5> but was: <-1>"), TestCaseResult("demo.CalcTest", "subtracts()", "passed")), 5)
    private fun task() = CodingTask("Fix the code.", listOf("demo.CalcTest#adds()"), listOf(CodeContext.window(calcPath, calc, 4, "suspect", pad = 3)), "Failing test: adds")

    @Test fun theLoopCorrectsItselfFromCompilerAndTestFeedbackAndNeverResubmitsAFailedPatch() {
        val model = Scripted(listOf(
            "I think the minus is wrong.",                                  // 1 malformed
            patch(4, 4, "    fun add(a: Int, b: Int): Int = F"),           // 2 compile error
            patch(4, 4, "    fun add(a: Int, b: Int): Int = a * b"),       // 3 compiles, test still fails
            patch(4, 4, "    fun add(a: Int, b: Int): Int = a * b"),       // 4 the same again: refused without building
            patch(4, 6, "    fun add(a: Int, b: Int): Int = a + b"),       // 5 fixes add but deletes sub: regression
            patch(4, 4, "    fun add(a: Int, b: Int): Int = a + b"),       // 6 verified
        ))
        val ws = FakeWorkspace(mapOf(calcPath to calc))
        val res = runBlocking { CodingLoop(model).run(task(), ws, before, CodingBudget(maxAttempts = 8, lineStrategy = false), review = { emptyList() }) }
        assertTrue(res.verified)
        assertEquals(listOf("MALFORMED", "COMPILE", "TEST_FAILED", "REPEATED", "REGRESSION", "NONE"), res.attempts.map { it.failure })
        assertEquals(4, ws.builds, "malformed, repeated (and rejected) patches never reach the build")
        assertTrue("Unresolved reference 'F'" in model.prompts[2], "the compiler error is fed back")
        assertTrue("expected <5> but was <-1>" in model.prompts[3], "the test failure is fed back")
        assertTrue("now failing: demo.CalcTest#subtracts()" in model.prompts[5], "the regression is fed back")
        assertTrue(ws.files[calcPath]!!.contains("a + b") && ws.files[calcPath]!!.contains("fun sub"), "the verified change is left in the workspace")
        assertEquals(listOf(0.0f, 0.4f, 0.7f, 0.9f), res.attempts.take(4).map { it.temperature }, "each retry samples differently")
    }

    @Test fun theLineStrategyLocatesThenRewritesOneLine() {
        val model = Scripted(listOf("{\"file\": \"$calcPath\", \"line\": 4, \"why\": \"subtracts instead of adding\"}", "    fun add(a: Int, b: Int): Int = a + b"))
        val res = runBlocking { CodingLoop(model).run(task(), FakeWorkspace(mapOf(calcPath to calc)), before, CodingBudget(maxAttempts = 2), review = { emptyList() }) }
        assertTrue(res.verified); assertEquals("locate-then-rewrite", res.attempts.single().strategy)
        assertTrue(">>   4|" in model.prompts[1], "the rewrite prompt marks the chosen line")
    }

    @Test fun anExhaustedBudgetIsReportedAndAnUnusableModelStopsAtOnce() {
        val wrong = Scripted(List(4) { n -> patch(4, 4, "    fun add(a: Int, b: Int): Int = a * ${n + 2}") })
        val res = runBlocking { CodingLoop(wrong).run(task(), FakeWorkspace(mapOf(calcPath to calc)), before, CodingBudget(maxAttempts = 4, lineStrategy = false), review = { emptyList() }) }
        assertFalse(res.verified); assertEquals(FailureKind.WRONG_DIAGNOSIS, res.finalFailure, "only failing tests, never a compile error: the suspected code may not be the cause")
        val broken = Scripted(listOf(null))
        val r2 = runBlocking { CodingLoop(broken).run(task(), FakeWorkspace(mapOf(calcPath to calc)), before, CodingBudget(maxAttempts = 4, lineStrategy = false), review = { emptyList() }) }
        assertEquals(FailureKind.MODEL_INCAPABLE, r2.finalFailure); assertEquals(1, r2.attempts.size)
        val blocked = Scripted(listOf(patch(4, 4, "    fun add(a: Int, b: Int): Int = a + b")))
        val r3 = runBlocking { CodingLoop(blocked).run(task(), FakeWorkspace(mapOf(calcPath to calc)), before, CodingBudget(maxAttempts = 1, lineStrategy = false),
            review = { listOf(ReviewFinding(ReviewFinding.BLOCKING, calcPath, "touches a trust-critical file")) }) }
        assertEquals("REVIEW_BLOCKED", r3.attempts.single().failure)
    }

    @Test fun codingExperienceKeepsFailuresToAvoidAndOnlyVerifiedChangesAsExamples() {
        val exp = CodingExperience(EvolutionStore(tempDb(), ManualClock(1)))
        val model = Scripted(listOf(patch(4, 4, "    fun add(a: Int, b: Int): Int = a * b"), patch(4, 4, "    fun add(a: Int, b: Int): Int = a + b")))
        val res = runBlocking { CodingLoop(model).run(task(), FakeWorkspace(mapOf(calcPath to calc)), before, CodingBudget(maxAttempts = 3, lineStrategy = false), review = { emptyList() }) }
        res.attempts.forEach { exp.record("t1", "repair", "sig-1", "scripted", it) }
        assertEquals(1, exp.failedFingerprints("sig-1").size)
        assertEquals(1, exp.verifiedExamples().size)
        assertEquals(2, exp.stats().attempts); assertEquals(1, exp.stats().verified)
        // A later run on the same problem never submits the failed change again.
        val again = Scripted(listOf(patch(4, 4, "    fun add(a: Int, b: Int): Int = a * b")))
        val r2 = runBlocking { CodingLoop(again).run(task(), FakeWorkspace(mapOf(calcPath to calc)), before, CodingBudget(maxAttempts = 1, lineStrategy = false), exp.failedFingerprints("sig-1"), review = { emptyList() }) }
        assertEquals("REPEATED", r2.attempts.single().failure)
    }

    @Test fun buildAndFeatureVerdictsNeedRealEvidence() {
        val notCompiled = TestRun("b", "t", 1, false, emptyList(), 1)
        val green = TestRun("a", "t", 0, true, listOf(TestCaseResult("S", "a", "passed")), 1)
        assertEquals(Verdict.VERIFIED, Verification.compareBuild(notCompiled, green, emptyList()).outcome)
        assertEquals(Verdict.INCONCLUSIVE, Verification.compareBuild(notCompiled, TestRun("a", "t", 0, true, emptyList(), 1), emptyList()).outcome)
        assertEquals(Verdict.BUILD_FAILED, Verification.compareBuild(notCompiled, notCompiled, emptyList()).outcome)
        val base = TestRun("b", "t", 0, true, listOf(TestCaseResult("demo.StringsTest", "reverses()", "passed")), 1)
        val done = TestRun("a", "t", 0, true, listOf(TestCaseResult("demo.StringsTest", "reverses()", "passed"), TestCaseResult("demo.PalindromeTest", "palindromes()", "passed")), 1)
        assertEquals(Verdict.VERIFIED, Verification.compareFeature(setOf("demo.PalindromeTest"), base, done, emptyList()).outcome)
        assertEquals(Verdict.INCONCLUSIVE, Verification.compareFeature(setOf("demo.PalindromeTest"), base, base, emptyList()).outcome, "the acceptance test must run")
        assertEquals(Verdict.REGRESSION, Verification.compareFeature(setOf("demo.PalindromeTest"), base,
            TestRun("a", "t", 1, true, listOf(TestCaseResult("demo.StringsTest", "reverses()", "failed"), TestCaseResult("demo.PalindromeTest", "palindromes()", "passed")), 1), emptyList()).outcome)
    }
}
