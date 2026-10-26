package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.memory.*
import ai.clu.core.security.Actor
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * End-to-end latency of real local inference through the agent (system prompt, memories, tools, history), as the app
 * runs it. Opt-in: -Dclu.llmBenchModel=<gguf>. Use scripts/make_test_gguf.py --shape 1b + llama-quantize Q4_K_M for a
 * model with the compute cost of a 1B model (random weights: replies are meaningless, timings are real).
 * Writes docs/measurements/llm_latency_host.json (or -Dclu.llmBenchOut).
 */
class LlmLatencyBenchmark {
    @Test fun measure(): Unit = runBlocking {
        val path = System.getProperty("clu.llmBenchModel")
        assumeTrue(path != null && File(path).exists())
        val threads = System.getProperty("clu.llmBenchThreads")?.toInt() ?: 4
        val clock = ai.clu.core.util.ManualClock(1_760_000_000_000L)
        val core = newCore(clock = clock)
        val s = core.settings
        s.set(Settings.THREADS, threads)
        s.set(Settings.MAX_TOKENS, 48)
        s.set(Settings.LOAD_ON_START, false)
        listOf("Owner's sister Priya lives in Pune.", "Owner prefers short answers in the morning.", "Owner has a cricket match on Sunday.",
            "Owner is learning Kotlin.", "Owner's car is due for service in November.").forEachIndexed { i, t ->
            core.memories.save(Memory(category = MemoryCategory.MANUAL, title = "Note $i", content = t, source = MemorySource.OWNER_SUPPLIED))
        }
        val model = core.models.register(File(path))
        s.set(Settings.MODEL_ACTIVE, model.id)
        val tLoad = System.nanoTime()
        core.llm.loadIfNeeded() ?: error(core.llm.state.value.message)
        val loadMs = (System.nanoTime() - tLoad) / 1e6
        // As the app does after every load (CluCore.startModel); -Dclu.llmBenchWarmUp=false measures without it.
        val warm = if (System.getProperty("clu.llmBenchWarmUp") != "false") core.agent.warmUp() else null
        val conv = core.conversations.create("bench").id
        val questions = listOf("What should I cook tonight?", "Remind me what my sister's city is.", "Explain recursion in one paragraph.",
            "How is the weather for my cricket match?", "Give me one tip for learning Kotlin.")
        val turns = mutableListOf<Map<String, Double>>()
        for (q in questions) {
            clock.advance(61_000) // a minute passes between messages, as in real use
            val t0 = System.nanoTime()
            var first = -1.0
            val events = core.agent.respond(TurnRequest(conv, q, Actor.ownerInApp())).onEach {
                if (it is AgentEvent.Text && first < 0) first = (System.nanoTime() - t0) / 1e6
            }.toList()
            val total = (System.nanoTime() - t0) / 1e6
            val done = events.filterIsInstance<AgentEvent.Done>().last()
            val st = done.meta.stats
            turns += mapOf("total_ms" to total, "first_text_ms" to first, "prompt_tokens" to (st["prompt_tokens"] ?: -1.0),
                "reused_tokens" to (st["reused_tokens"] ?: -1.0), "prompt_ms" to (st["prompt_ms"] ?: -1.0),
                "first_token_ms" to (st["first_token_ms"] ?: -1.0), "gen_ms" to (st["gen_ms"] ?: -1.0),
                "tokens" to (st["tokens"] ?: -1.0), "tok_per_s" to (st["tok_per_s"] ?: -1.0))
            println("turn: " + turns.last())
        }
        core.shutdown()
        val out = File(System.getProperty("clu.llmBenchOut", "../docs/measurements/llm_latency_host.json"))
        out.parentFile.mkdirs()
        fun f(d: Double) = "%.1f".format(d)
        out.writeText("{\n  \"model\": \"${File(path).name}\",\n  \"threads\": $threads,\n  \"max_tokens\": 48,\n  \"load_ms\": ${f(loadMs)},\n  \"warm_up_ms\": ${f(warm?.promptMs ?: 0.0)},\n  \"warm_up_tokens\": ${warm?.promptTokens ?: 0},\n  \"turns\": [\n" +
            turns.joinToString(",\n") { t -> "    {" + t.entries.joinToString(", ") { "\"${it.key}\": ${f(it.value)}" } + "}" } +
            "\n  ],\n  \"environment\": \"${System.getProperty("os.arch")} JVM, ${Runtime.getRuntime().availableProcessors()} vCPU (build container, not a phone)\"\n}\n")
    }
}
