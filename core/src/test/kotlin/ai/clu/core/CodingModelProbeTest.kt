package ai.clu.core

import ai.clu.core.config.Settings
import ai.clu.core.evolution.*
import ai.clu.core.llm.ChatMessage
import ai.clu.core.llm.GenParams
import ai.clu.core.llm.InferenceRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Opt-in probe of real local models in CLU's development mode (structured patches through the shared inference service):
 * `-Dclu.codingModels=a.gguf,b.gguf [-Dclu.codingOut=file.json]`. Scores whether the patch is valid against the shown code
 * and whether applying it gives an accepted fix. A quick proxy; the compile-and-test benchmark is CodingBenchmark.
 */
class CodingModelProbeTest {
    data class Probe(val name: String, val file: String, val src: String, val line: Int, val evidence: String, val accepted: List<String>)

    private val probes = listOf(
        Probe("arithmetic", "core/src/main/kotlin/demo/Calc.kt", "package demo\n\nobject Calc {\n    fun add(a: Int, b: Int): Int = a - b\n\n    fun sub(a: Int, b: Int): Int = a - b\n}\n", 4,
            "Failing test CalcTest.adds: assertEquals(5, Calc.add(2, 3)) — expected <5> but was <-1>", listOf("fun add(a: Int, b: Int): Int = a + b", "fun add(a: Int, b: Int): Int = b + a")),
        Probe("null-handling", "core/src/main/kotlin/demo/Names.kt", "package demo\n\nobject Names {\n    fun initials(name: String?): String {\n        val parts = name!!.trim().split(\" \")\n        return parts.filter { it.isNotEmpty() }.joinToString(\"\") { it.first().uppercase() }\n    }\n}\n", 5,
            "Failing test NamesTest.nullNameGivesEmptyInitials: assertEquals(\"\", Names.initials(null)) — java.lang.NullPointerException at demo.Names.initials(Names.kt:5)",
            listOf("val parts = (name ?: return \"\").trim().split(\" \")", "val parts = name?.trim()?.split(\" \") ?: return \"\"", "if (name == null) return \"\"", "val parts = (name ?: \"\").trim().split(\" \")", "name.orEmpty().trim().split(\" \")")),
        Probe("off-by-one", "core/src/main/kotlin/demo/Ranges.kt", "package demo\n\nobject Ranges {\n    /** Numbers from 1 to n inclusive. */\n    fun upTo(n: Int): List<Int> = (1 until n).toList()\n}\n", 5,
            "Failing test RangesTest.inclusive: assertEquals(listOf(1, 2, 3), Ranges.upTo(3)) — expected <[1, 2, 3]> but was <[1, 2]>", listOf("(1..n).toList()", "(1 until n + 1).toList()", "(1..n)")),
        Probe("comparison", "core/src/main/kotlin/demo/Grades.kt", "package demo\n\nobject Grades {\n    fun passed(score: Int): Boolean = score > 50\n}\n", 4,
            "Failing test GradesTest.fiftyPasses: assertTrue(Grades.passed(50)) — expected <true> but was <false>. The documented rule: 50 or more passes.", listOf("score >= 50")),
        Probe("logic", "core/src/main/kotlin/demo/Words.kt", "package demo\n\nobject Words {\n    fun longest(words: List<String>): String? {\n        var best: String? = null\n        for (w in words) if (best == null || w.length < best.length) best = w\n        return best\n    }\n}\n", 6,
            "Failing test WordsTest.longest: assertEquals(\"banana\", Words.longest(listOf(\"fig\", \"banana\", \"kiwi\"))) — expected <banana> but was <fig>",
            listOf("w.length > best.length", "w.length > best!!.length")),
    )

    private fun normalized(s: String) = s.replace(Regex("\\s+"), " ").trim()

    @Test fun probeLocalModels() {
        val models = System.getProperty("clu.codingModels")?.split(',')?.map(::File)?.filter { it.isFile }.orEmpty()
        assumeTrue(models.isNotEmpty(), "set -Dclu.codingModels=a.gguf,b.gguf")
        val out = buildJsonArray {
            for (m in models) {
                val core = newCore(platform = TestPlatform())
                core.settings.set(Settings.THREADS, 4); core.settings.set(Settings.CONTEXT_LENGTH, 4096); core.settings.set(Settings.NET_ENABLED, false)
                val e = core.models.register(m); core.settings.set(Settings.MODEL_ACTIVE, e.id)
                val t0 = System.nanoTime(); checkNotNull(runBlocking { core.llm.loadIfNeeded() }) { core.llm.state.value.message }; val loadMs = (System.nanoTime() - t0) / 1_000_000
                val loop = CodingLoop(object : DevModel { override val description = m.name; override val lastError: String? = null
                    override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float, seed: Int, grammar: String?, reasoning: Boolean): String? = null })
                for ((strategy, temps, reasoning) in listOf(Triple("structured", listOf(0.0f), false), Triple("structured-sampled", listOf(0.3f, 0.6f, 0.9f), false), Triple("reasoning", listOf(0.6f), true))) {
                    for (p in probes) {
                        val w = CodeContext.window(p.file, p.src, p.line, "suspected code")
                        val task = CodingTask("Make the failing test pass by fixing the code.", emptyList(), listOf(w), p.evidence)
                        for (temp in temps) {
                            val s0 = System.nanoTime()
                            val raw = runBlocking { core.inferenceService.complete(InferenceRequest(listOf(ChatMessage("system", loop.systemPrompt(false)), ChatMessage("user", loop.userPrompt(task, emptyList()))),
                                GenParams(maxTokens = if (reasoning) 1600 else 500, temperature = temp, seed = 7, grammar = if (reasoning) null else StructuredPatches.GRAMMAR, reasoning = reasoning),
                                owner = "probe", fitContext = false)).text }
                            val ms = (System.nanoTime() - s0) / 1_000_000
                            val patch = StructuredPatches.parse(raw)
                            val checked = patch?.let { StructuredPatches.check(it, { f -> if (f == p.file) p.src else null }, listOf(StructuredPatches.Window(w.file, w.from, w.to)), false) }
                            val result = (checked as? StructuredPatches.Checked.Ok)?.files?.get(p.file)
                            val correct = result != null && p.accepted.any { normalized(result).contains(normalized(it)) } && !normalized(result).contains(normalized(p.src.lines()[p.line - 1]))
                            val verdict = when { patch == null -> "malformed"; checked is StructuredPatches.Checked.Rejected -> "rejected: ${checked.reason}"; correct -> "correct"; else -> "valid-but-wrong" }
                            println("PROBE ${m.name} $strategy t=$temp ${p.name}: $verdict (${ms} ms) ${raw.replace("\n", "⏎").take(240)}")
                            add(buildJsonObject { put("model", m.name); put("strategy", strategy); put("temperature", temp); put("probe", p.name); put("verdict", verdict); put("ms", ms); put("loadMs", loadMs); put("raw", raw.take(1500)) })
                        }
                    }
                }
                core.shutdown()
            }
        }
        System.getProperty("clu.codingOut")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), out)) }
    }
}
