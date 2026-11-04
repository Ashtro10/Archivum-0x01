package ai.clu.desktop.evolution

import ai.clu.core.evolution.*
import ai.clu.core.util.SystemClock
import ai.clu.desktop.DesktopPaths
import ai.clu.desktop.JdbcSqlDriver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files

/**
 * The coding benchmark: real development tasks in disposable Git repositories, solved by CLU's own model through the
 * real self-repair workflow (worktree, Gradle build, tests with --rerun, review, verdict). Every result says who
 * wrote the change. Here it is always `model`: the benchmark never supplies a patch.
 *
 * It uses its own evolution database (under <data>/evolution/benchmark), so running it never points the owner's
 * CLU at a different repository.
 */
class CodingBenchmark(private val svcForModel: SelfRepairService, private val paths: DesktopPaths, private val cluRepo: File) {

    data class Task(val name: String, val category: String, val files: Map<String, String>, val tests: Map<String, String>, val filter: String,
                    /** Committed after the base commit (a defect introduced by a later change, or a test changed later). */
                    val later: Map<String, String> = emptyMap(), val laterMessage: String = "",
                    /** Where the defect really is (for scoring the diagnosis). */
                    val defectFile: String, val expected: String = "verified-repair",
                    val acceptTest: Pair<String, String>? = null, val goal: String? = null, val devFiles: List<String> = emptyList(),
                    /** Runs in a disposable clone of CLU's own repository instead of a small project. */
                    val inClu: Boolean = false, val cluEdit: Pair<String, Pair<String, String>>? = null)

    data class Result(val json: String, val summary: String)

    private fun pkg(body: String) = "package demo\n\n$body"
    private fun test(cls: String, body: String) = "package demo\n\nimport kotlin.test.*\nimport org.junit.jupiter.api.Test\n\nclass $cls {\n$body\n}\n"

    val tasks: List<Task> = listOf(
        Task("simple-function", "simple Kotlin function defect",
            mapOf("core/src/main/kotlin/demo/Calc.kt" to pkg("object Calc {\n    fun add(a: Int, b: Int): Int = a - b\n\n    fun sub(a: Int, b: Int): Int = a - b\n}\n")),
            mapOf("core/src/test/kotlin/demo/CalcTest.kt" to test("CalcTest", "    @Test fun adds() {\n        assertEquals(5, Calc.add(2, 3))\n    }\n\n    @Test fun subtracts() {\n        assertEquals(1, Calc.sub(3, 2))\n    }")),
            "demo.CalcTest", defectFile = "core/src/main/kotlin/demo/Calc.kt"),
        Task("null-handling", "null-handling defect",
            mapOf("core/src/main/kotlin/demo/Names.kt" to pkg("object Names {\n    /** Initials of a name, \"\" for no name. */\n    fun initials(name: String?): String {\n        val parts = name!!.trim().split(\" \")\n        return parts.filter { it.isNotEmpty() }.joinToString(\"\") { it.first().uppercase() }\n    }\n}\n")),
            mapOf("core/src/test/kotlin/demo/NamesTest.kt" to test("NamesTest", "    @Test fun twoNames() {\n        assertEquals(\"AL\", Names.initials(\"ada lovelace\"))\n    }\n\n    @Test fun noName() {\n        assertEquals(\"\", Names.initials(null))\n    }")),
            "demo.NamesTest", defectFile = "core/src/main/kotlin/demo/Names.kt"),
        Task("logic-error", "logic error",
            mapOf("core/src/main/kotlin/demo/Words.kt" to pkg("object Words {\n    /** The longest word (the first one on a tie), or null for an empty list. */\n    fun longest(words: List<String>): String? {\n        var best: String? = null\n        for (w in words) if (best == null || w.length < best.length) best = w\n        return best\n    }\n}\n")),
            mapOf("core/src/test/kotlin/demo/WordsTest.kt" to test("WordsTest", "    @Test fun longest() {\n        assertEquals(\"banana\", Words.longest(listOf(\"fig\", \"banana\", \"kiwi\")))\n    }\n\n    @Test fun empty() {\n        assertNull(Words.longest(emptyList()))\n    }")),
            "demo.WordsTest", defectFile = "core/src/main/kotlin/demo/Words.kt"),
        Task("broken-test", "broken unit test (the test, not the code, is wrong)",
            mapOf("core/src/main/kotlin/demo/Grades.kt" to pkg("object Grades {\n    /** 50 or more passes. */\n    fun passed(score: Int): Boolean = score >= 50\n}\n")),
            mapOf("core/src/test/kotlin/demo/GradesTest.kt" to test("GradesTest", "    @Test fun fiftyPasses() {\n        assertTrue(Grades.passed(50))\n    }\n\n    @Test fun fortyFails() {\n        assertFalse(Grades.passed(40))\n    }")),
            "demo.GradesTest",
            later = mapOf("core/src/test/kotlin/demo/GradesTest.kt" to test("GradesTest", "    @Test fun fiftyPasses() {\n        assertFalse(Grades.passed(50))\n    }\n\n    @Test fun fortyFails() {\n        assertFalse(Grades.passed(40))\n    }")),
            laterMessage = "GradesTest: tighten the pass mark expectation", defectFile = "core/src/test/kotlin/demo/GradesTest.kt", expected = "diagnosed-test"),
        Task("compile-error", "compilation error",
            mapOf("core/src/main/kotlin/demo/Temps.kt" to pkg("object Temps {\n    fun toFahrenheit(c: Double): Double {\n        val f = c * 9 / 5 + 32\n        return F\n    }\n}\n")),
            mapOf("core/src/test/kotlin/demo/TempsTest.kt" to test("TempsTest", "    @Test fun boiling() {\n        assertEquals(212.0, Temps.toFahrenheit(100.0))\n    }")),
            "demo.TempsTest", defectFile = "core/src/main/kotlin/demo/Temps.kt", expected = "verified-build"),
        Task("multi-file", "multi-file modification",
            mapOf("core/src/main/kotlin/demo/Prices.kt" to pkg("data class Item(val name: String, val cents: Int, val quantity: Int)\n\nobject Prices {\n    /** Total price of the items in cents (price × quantity). */\n    fun total(items: List<Item>): Int = items.sumOf { it.cents }\n}\n"),
                "core/src/main/kotlin/demo/Format.kt" to pkg("object Format {\n    /** Cents as euros with two decimals, e.g. 250 -> \"2.50 EUR\". */\n    fun money(cents: Int): String = \"${'$'}{cents / 100}.${'$'}{(cents % 100).toString().padStart(2, '0')} USD\"\n}\n")),
            mapOf("core/src/test/kotlin/demo/BillTest.kt" to test("BillTest", "    @Test fun bill() {\n        assertEquals(\"5.00 EUR\", Format.money(Prices.total(listOf(Item(\"tea\", 125, 2), Item(\"cake\", 250, 1)))))\n    }")),
            "demo.BillTest", defectFile = "core/src/main/kotlin/demo/Prices.kt"),
        Task("regression-sensitive", "regression-sensitive modification",
            mapOf("core/src/main/kotlin/demo/Clamp.kt" to pkg("object Clamp {\n    /** [v] limited to [lo]..[hi]. */\n    fun clamp(v: Int, lo: Int, hi: Int): Int {\n        if (v < lo) return hi\n        if (v > hi) return hi\n        return v\n    }\n}\n")),
            mapOf("core/src/test/kotlin/demo/ClampTest.kt" to test("ClampTest", "    @Test fun low() {\n        assertEquals(0, Clamp.clamp(-5, 0, 10))\n    }\n\n    @Test fun high() {\n        assertEquals(10, Clamp.clamp(15, 0, 10))\n    }\n\n    @Test fun middle() {\n        assertEquals(7, Clamp.clamp(7, 0, 10))\n    }")),
            "demo.ClampTest", defectFile = "core/src/main/kotlin/demo/Clamp.kt"),
        Task("feature", "bounded development task (owner's acceptance test)",
            mapOf("core/src/main/kotlin/demo/Strings.kt" to pkg("object Strings {\n    /** The text reversed. */\n    fun reverse(s: String): String = s.reversed()\n}\n")),
            mapOf("core/src/test/kotlin/demo/StringsTest.kt" to test("StringsTest", "    @Test fun reverses() {\n        assertEquals(\"cba\", Strings.reverse(\"abc\"))\n    }")),
            "demo.StringsTest", defectFile = "core/src/main/kotlin/demo/Strings.kt", expected = "verified-feature",
            acceptTest = "core/src/test/kotlin/demo/PalindromeTest.kt" to test("PalindromeTest", "    @Test fun palindromes() {\n        assertTrue(Strings.isPalindrome(\"level\"))\n        assertFalse(Strings.isPalindrome(\"kotlin\"))\n    }"),
            goal = "Add a function isPalindrome(s: String): Boolean to object Strings that is true when s reads the same backwards.", devFiles = listOf("core/src/main/kotlin/demo/Strings.kt")),
        Task("clu-component", "existing CLU component (Reasoning.disable in a clone of CLU)", emptyMap(), emptyMap(), "ai.clu.core.ReasoningTest",
            defectFile = "core/src/main/kotlin/ai/clu/core/llm/Reasoning.kt", inClu = true,
            cluEdit = "core/src/main/kotlin/ai/clu/core/llm/Reasoning.kt" to ("                trimmed.endsWith(\"</think>\") -> prompt\n" to "                trimmed.endsWith(\"</think>\") -> prompt + CLOSED_BLOCK\n")),
    )

    private fun git(dir: File, vararg a: String) { val p = ProcessBuilder(listOf("git", *a)).directory(dir).redirectErrorStream(true).start(); val o = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { "git ${a.joinToString(" ")}: $o" } }

    private fun smallProject(dir: File, t: Task) {
        File(dir, "settings.gradle.kts").writeText("pluginManagement { repositories { maven(\"https://maven-central.storage-download.googleapis.com/maven2/\"); gradlePluginPortal(); mavenCentral() } }\n" +
            "dependencyResolutionManagement { repositories { maven(\"https://maven-central.storage-download.googleapis.com/maven2/\"); mavenCentral() } }\nrootProject.name = \"bench\"\ninclude(\":core\")\n")
        File(dir, "build.gradle.kts").writeText("plugins { kotlin(\"jvm\") version \"2.2.21\" apply false }\n")
        File(dir, "gradle.properties").writeText("org.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8\norg.gradle.caching=true\n")
        File(dir, ".gitignore").writeText(".gradle/\nbuild/\n/*/build/\n.kotlin/\n")
        File(dir, "core").mkdirs()
        File(dir, "core/build.gradle.kts").writeText("""
            plugins { kotlin("jvm") }
            kotlin { jvmToolchain(21) }
            dependencies { testImplementation(kotlin("test")); testImplementation("org.junit.jupiter:junit-jupiter:5.11.4"); testRuntimeOnly("org.junit.platform:junit-platform-launcher") }
            tasks.test { useJUnitPlatform() }
        """.trimIndent() + "\n")
        File(cluRepo, "gradlew").copyTo(File(dir, "gradlew")).setExecutable(true)
        File(cluRepo, "gradle/wrapper").copyRecursively(File(dir, "gradle/wrapper"))
        (t.files + t.tests).forEach { (p, c) -> File(dir, p).apply { parentFile.mkdirs(); writeText(c) } }
        git(dir, "init", "-q", "-b", "main"); git(dir, "config", "user.name", "Benchmark"); git(dir, "config", "user.email", "bench@example.invalid")
        git(dir, "add", "-A"); git(dir, "commit", "-q", "-m", "Initial (task ${t.name})")
        if (t.later.isNotEmpty()) { Thread.sleep(1100); t.later.forEach { (p, c) -> File(dir, p).writeText(c) }; git(dir, "commit", "-qam", t.laterMessage) }
    }

    private fun cluClone(dir: File, t: Task) {
        git(dir.parentFile, "clone", "-q", "--shared", cluRepo.path, dir.path)
        for (l in listOf("toolchain", "native/build")) File(cluRepo, l).takeIf { it.exists() }?.let { Files.createSymbolicLink(File(dir, l).toPath(), it.toPath()); File(dir, ".git/info/exclude").appendText("$l\n") }
        git(dir, "config", "user.name", "Benchmark"); git(dir, "config", "user.email", "bench@example.invalid")
        val (path, edit) = t.cluEdit!!
        val f = File(dir, path); val src = f.readText(); check(src.contains(edit.first)) { "benchmark edit does not apply to $path" }
        f.writeText(src.replace(edit.first, edit.second)); git(dir, "commit", "-qam", "Controlled defect for the coding benchmark")
    }

    private fun vmHwmMb(): Long = runCatching { File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.split(Regex("\\s+"))[1].toLong() / 1024 }.getOrDefault(-1)
    private fun gradleDaemonRssMb(): Long = runCatching {
        ProcessHandle.allProcesses().filter { p -> p.info().commandLine().orElse("").contains("GradleDaemon") }.toList().sumOf { p ->
            File("/proc/${p.pid()}/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong() / 1024 } }.getOrDefault(-1)

    fun run(only: Set<String>?, budget: CodingBudget, log: (String) -> Unit): Result {
        val root = File(SelfRepairSetup.evolutionDir(paths), "benchmark").apply { ai.clu.core.util.SafeDelete.tree(this, listOf(SelfRepairSetup.evolutionDir(paths)), protected = listOf(cluRepo)); mkdirs() }
        val db = JdbcSqlDriver(File(root, "benchmark.db").path)
        val results = mutableListOf<JsonObject>()
        try {
            for (t in tasks.filter { only == null || it.name in only }) {
                log("task ${t.name} (${t.category})")
                val repo = File(root, "repo-${t.name}").apply { mkdirs() }
                if (t.inClu) { repo.delete(); cluClone(repo, t) } else smallProject(repo, t)
                val svc = SelfRepairService(EvolutionStore(db, SystemClock), File(root, "ws"), model = svcForModel.modelForBenchmark(), log = { log("    $it") })
                svc.connect(repo)
                val t0 = System.currentTimeMillis()
                val o = runBlocking {
                    if (t.acceptTest != null) svc.develop(SelfRepairService.Options("core", t.filter, goal = t.goal, files = t.devFiles, acceptTest = t.acceptTest, budget = budget))
                    else svc.run(SelfRepairService.Options("core", t.filter, budget = budget))
                }
                val ms = System.currentTimeMillis() - t0
                val r = o.repair
                val inv = r?.investigationId?.takeIf { it.isNotEmpty() }?.let { svc.investigator.get(it) }
                val diagnosed = when {
                    t.expected == "diagnosed-test" -> r?.outcome?.contains("test changed after the code") == true
                    t.expected == "verified-build" -> r?.kind == "build" && svc.incidents.get(r.incidentId)?.detail?.contains(t.defectFile) == true
                    t.acceptTest != null -> true
                    else -> inv?.suspects?.firstOrNull()?.file == t.defectFile || inv?.suspects?.any { it.file == t.defectFile } == true
                }
                val attempts = r?.let { svc.experience.attempts(it.id) }.orEmpty()
                val success = when (t.expected) {
                    "diagnosed-test" -> diagnosed && r?.state != RepairRecord.AWAITING_APPROVAL
                    else -> r?.state == RepairRecord.AWAITING_APPROVAL && r.verdict?.verified == true && r.source == "model"
                }
                val line = "${t.name}: ${if (success) "SUCCESS" else "FAILED"} — ${r?.state} ${r?.verdict?.outcome ?: ""}; attempts ${attempts.size}; ${ms / 1000} s; ${o.message.take(160)}"
                log(line)
                results += buildJsonObject {
                    put("task", t.name); put("category", t.category); put("expected", t.expected); put("success", success)
                    put("diagnosisCorrect", diagnosed); put("state", r?.state); put("verdict", r?.verdict?.outcome); put("source", r?.source)
                    put("patchValidAttempts", attempts.count { it["failure"] !in setOf("MALFORMED", "REJECTED", "REPEATED") })
                    put("compiledAttempts", attempts.count { it["failure"] !in setOf("MALFORMED", "REJECTED", "REPEATED", "SYNTAX", "COMPILE", "REVIEW_BLOCKED") })
                    put("regressionAttempts", attempts.count { it["failure"] == "REGRESSION" })
                    put("attempts", attempts.size); putJsonArray("attemptOutcomes") { attempts.forEach { add(it["failure"]) } }
                    put("verifiedOnAttempt", attempts.indexOfFirst { it["failure"] == "NONE" }.let { if (it < 0) null else it + 1 })
                    put("seconds", ms / 1000.0); put("peakCluRssMb", vmHwmMb()); put("gradleDaemonRssMb", gradleDaemonRssMb())
                    put("diff", r?.diff.orEmpty().take(4000)); put("message", o.message)
                }
                svc.repairs.list(5).filter { it.state == RepairRecord.AWAITING_APPROVAL }.forEach { svc.reject(it.id, "benchmark run (not integrated)", by = "benchmark") }
            }
        } finally { db.close() }
        val ok = results.count { it["success"]!!.jsonPrimitive.boolean }
        val summary = "Coding benchmark with ${svcForModel.modelDescription()}: $ok of ${results.size} tasks solved by the model\n" +
            results.joinToString("\n") { r -> "  ${r["task"]!!.jsonPrimitive.content.padEnd(22)} ${if (r["success"]!!.jsonPrimitive.boolean) "SUCCESS" else "failed "}  attempts ${r["attempts"]}  ${r["verifiedOnAttempt"]?.takeIf { it !is JsonNull }?.let { "verified on #$it" } ?: r["state"]!!.jsonPrimitive.contentOrNull}  ${r["seconds"]} s" }
        val json = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("model", svcForModel.modelDescription()); put("budget", budget.toString()); put("solved", ok); put("tasks", results.size)
            put("cpu", Runtime.getRuntime().availableProcessors()); putJsonArray("results") { results.forEach { add(it) } } })
        return Result(json, summary)
    }
}
