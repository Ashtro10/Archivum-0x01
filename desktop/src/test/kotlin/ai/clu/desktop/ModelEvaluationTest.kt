package ai.clu.desktop

import ai.clu.core.config.Settings
import ai.clu.core.evolution.DevModel
import ai.clu.desktop.capability.AuthoringBench
import ai.clu.desktop.capability.CapabilityStore
import ai.clu.desktop.capability.ExperienceVerifier
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * The model benchmark: its harness is checked with known-good and known-bad answers; real models are measured on
 * request (`-Dclu.capModels=a.gguf,b.gguf`), with the per-task results written to `-Dclu.capShots` (or build/model-eval).
 */
class ModelEvaluationTest {
    private val dir = Files.createTempDirectory("clu-bench").toFile().also { d -> Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() }) }

    private class Fixed(val answers: Map<String, String>) : DevModel {
        override val description = "fixed"; override val lastError: String? = null
        override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float, seed: Int, grammar: String?, reasoning: Boolean) =
            answers.entries.firstOrNull { user.contains(it.key) }?.value ?: "I am not sure."
    }

    @Test fun theHarnessTellsWorkingCodeFromBrokenCode() {
        assumeTrue(ExperienceVerifier.find() != null, "no browser")
        val store = CapabilityStore(JdbcSqlDriver(File(dir, "c.db").absolutePath))
        val bench = AuthoringBench(store, ExperienceVerifier(scratch = dir), dir)
        val good = Fixed(mapOf(
            "formatSize" to "=== FILE: solution.js ===\nfunction formatSize(b){ if (b < 1024) return b + ' B'; if (b < 1048576) return (b/1024).toFixed(1) + ' KB'; return (b/1048576).toFixed(1) + ' MB'; }\n=== END ===",
            "sortEntries" to "```js\nfunction sortEntries(e){ return e.slice().sort((a,b)=> (a.type==='dir'?0:1)-(b.type==='dir'?0:1) || a.name.toLowerCase().localeCompare(b.name.toLowerCase())); }\n```",
            "greet" to "=== FILE: solution.js ===\nfunction greet(name, greeting = 'Hello') { return greeting + ', ' + name + '!'; }\n=== END ===",
            "parts.pop" to "=== FILE: solution.js ===\nfunction parent(path){ const parts = path.split('/'); parts.pop(); return parts.join('/'); }\n=== END ===",
            "countFiles" to "=== FILE: solution.js ===\nfunction countFiles(e){ return e.filter(x => x.type === 'file').length; }\n=== END ===",
            "render(entries)" to "=== FILE: solution.js ===\nfunction render(entries){ const ul = document.getElementById('list'); ul.replaceChildren(...entries.map(e => { const li = document.createElement('li'); li.textContent = e.name; li.dataset.entry = e.name; return li; })); }\n=== END ===",
        ))
        val r = bench.run("good", good)
        assertTrue(r.all { it.passed }, r.toString())
        val bad = bench.run("bad", Fixed(mapOf("formatSize" to "=== FILE: solution.js ===\nfunction formatSize(b){ return b + ' bytes'; }\n=== END ===",
            "parts.pop" to "=== FILE: solution.js ===\nfunction parent(path) { parts.pop(;\n=== END ===")))
        assertTrue(bad.none { it.passed }, bad.toString())
        assertTrue(bad.first { it.task == "fix-syntax-error" }.detail.isNotBlank())
        assertEquals(6, store.score("good", "code")!!.successes + store.score("good", "edit")!!.successes + store.score("good", "debug")!!.successes)
    }

    @Test fun realModelsAreMeasuredOnRealTasks() {
        val files = System.getProperty("clu.capModels")?.split(',')?.map(::File)?.filter { it.isFile }.orEmpty()
        assumeTrue(files.isNotEmpty(), "opt-in: -Dclu.capModels=<a.gguf,b.gguf>")
        assumeTrue(ExperienceVerifier.find() != null, "no browser")
        val out = File(System.getProperty("clu.capShots") ?: "build/model-eval").apply { mkdirs() }
        val report = StringBuilder("model\ttask\trole\tpassed\tms\tdetail\n")
        for (f in files) {
            val host = Host.start(DesktopPaths(File(dir, "h-${f.nameWithoutExtension}")), HostConfig(), port = 0, log = {})
            try {
                val m = host.core.models.register(f); host.core.settings.set(Settings.MODEL_ACTIVE, m.id)
                val cap = host.api.capabilities!!
                val cand = cap.models.candidates().single { !it.remote }
                val results = AuthoringBench(CapabilityStore(JdbcSqlDriver(File(dir, "s-${f.nameWithoutExtension}.db").absolutePath)), ExperienceVerifier(scratch = dir), dir).run(cand.id, cand.model)
                results.forEach { r -> report.append("${f.name}\t${r.task}\t${r.role}\t${r.passed}\t${r.ms}\t${r.detail.replace('\t', ' ')}\n"); println("[bench] ${f.name} ${r.task}: ${if (r.passed) "PASS" else "fail"} (${r.ms} ms) ${r.detail.take(120)}") }
                println("[bench] ${f.name}: ${results.count { it.passed }}/${results.size}")
            } finally { host.stop() }
        }
        File(out, "model-benchmark.tsv").writeText(report.toString())
    }
}
