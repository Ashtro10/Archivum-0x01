package ai.clu.core

import ai.clu.core.agent.MemoryRetriever
import ai.clu.core.memory.*
import ai.clu.core.persona.PromptContext
import ai.clu.core.settings.SyncHarness
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Host (x86_64 JVM) measurements of core subsystems. Run with -Dclu.bench=true; writes docs/measurements/core_host.json. */
class HostBenchmark {
    @Test fun measure() {
        assumeTrue(System.getProperty("clu.bench") == "true")
        val results = linkedMapOf<String, Any>()
        val core = newCore()
        val words = "trip exam interview birthday cricket goa lonavala office project deadline doctor fever mom dad friend movie book gym diet money loan salary car bike phone".split(" ")
        val rnd = java.util.Random(1)
        val t0 = System.nanoTime()
        repeat(2000) { i ->
            val text = (1..40).joinToString(" ") { words[rnd.nextInt(words.size)] } + " note$i"
            core.memories.save(Memory(category = MemoryCategory.MANUAL, title = "Memory $i", content = text, source = MemorySource.OWNER_SUPPLIED))
        }
        results["memory_insert_ms_per_record_2000"] = (System.nanoTime() - t0) / 1e6 / 2000
        val r = MemoryRetriever(core.settings, core.memories)
        r.retrieve("warmup", listOf(CLU_SCOPE))
        val t1 = System.nanoTime()
        repeat(50) { r.retrieve("what happened at the interview with the doctor about my fever", listOf(CLU_SCOPE)) }
        results["memory_retrieval_ms_at_2000_records"] = (System.nanoTime() - t1) / 1e6 / 50
        val t2 = System.nanoTime()
        repeat(200) { core.promptBuilder.system(PromptContext(core.personas.active(), r.retrieve("goa trip", listOf(CLU_SCOPE)), core.tools.available(core.personas.active()), 0, true, "home")) }
        results["prompt_build_with_retrieval_ms"] = (System.nanoTime() - t2) / 1e6 / 200
        val t3 = System.nanoTime()
        repeat(500) { core.settings.set(ai.clu.core.config.Settings.TEMPERATURE, 0.5 + (it % 5) * 0.1) }
        results["setting_write_ms"] = (System.nanoTime() - t3) / 1e6 / 500
        val db = File((core.db as JdbcDriver).path)
        results["db_bytes_with_2000_memories"] = db.length()

        val h = SyncHarness()
        h.pair()
        repeat(1000) { h.memory(h.phone, "Sync note $it", "Some content for sync note $it with a bit of text to be realistic") }
        val ts = System.nanoTime()
        val s = h.sync()
        results["sync_1000_memories_ms_localhost"] = (System.nanoTime() - ts) / 1e6
        results["sync_1000_memories_ok"] = s.ok
        h.close()
        val out = File(System.getProperty("clu.benchOut", "../docs/measurements/core_host.json"))
        out.parentFile.mkdirs()
        out.writeText("{\n" + results.entries.joinToString(",\n") { "  \"${it.key}\": ${if (it.value is String) "\"${it.value}\"" else if (it.value is Double) "%.3f".format(it.value) else it.value}" } +
            ",\n  \"environment\": \"${System.getProperty("os.arch")} JVM ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} vCPU (build container, not a phone)\"\n}\n")
    }
}
