package ai.clu.core

import ai.clu.core.llm.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Stage 3.3: what happens to token generation when other CPU work runs at the same time, as speech synthesis does while
 * a spoken reply is still being generated. Busy threads stand in for the synthesiser's worker threads; they also report
 * how much work they got done, which is what the synthesiser would lose. Opt-in: -Dclu.llmBenchModel=<gguf>
 * [-Dclu.contentionOut=<json>]. A 4-vCPU build container, not a phone: this shows the mechanism, not phone numbers.
 */
class ContentionAudit {
    @Test fun generationUnderConcurrentCpuLoad() {
        val path = System.getProperty("clu.llmBenchModel")
        assumeTrue(path != null && File(path).exists())
        LlamaBridge.load(System.getProperty("clu.nativeLib"))
        val cores = Runtime.getRuntime().availableProcessors()
        val rows = mutableListOf<String>()
        val prompt = listOf(ChatMessage("system", "You are a helpful assistant."), ChatMessage("user", "Tell me about the history of trains in India."))
        for (threads in listOf(cores, cores - 1, cores / 2).distinct().filter { it > 0 }) {
            val e = LlamaEngine.load("bench", path!!, LlamaConfig(contextLength = 1024, batchSize = 256, threads = threads, useMmap = true))
            e.generate(prompt, GenParams(maxTokens = 8, temperature = 0f)) {} // warm
            for (busy in listOf(0, 2, cores)) {
                val stop = AtomicBoolean(false); val work = AtomicLong()
                val workers = (1..busy).map { Thread { var x = 0L; while (!stop.get()) { repeat(10_000) { x = x * 31 + it }; work.incrementAndGet() }; if (x == 42L) println() }.apply { start() } }
                Thread.sleep(200)
                val w0 = work.get(); val t0 = System.nanoTime()
                val r = e.generate(prompt, GenParams(maxTokens = 64, temperature = 0f)) {}
                val sec = (System.nanoTime() - t0) / 1e9
                val workRate = (work.get() - w0) / sec / maxOf(busy, 1)
                stop.set(true); workers.forEach { it.join() }
                val row = "{\"llama_threads\": $threads, \"busy_threads\": $busy, \"tok_per_s\": ${"%.1f".format(r.stats.tokensPerSecond)}, " +
                    "\"busy_work_per_thread_per_s\": ${"%.0f".format(workRate)}}"
                println("contention: $row"); rows += row
            }
            if (threads == cores) {
                // The Stage 3.3 change: all threads for the prompt, [threads] - 2 for generation while speech runs.
                val stop = AtomicBoolean(false); val work = AtomicLong()
                val workers = (1..2).map { Thread { var x = 0L; while (!stop.get()) { repeat(10_000) { x = x * 31 + it }; work.incrementAndGet() }; if (x == 42L) println() }.apply { start() } }
                Thread.sleep(200)
                val w0 = work.get(); val t0 = System.nanoTime()
                val r = e.generate(prompt, GenParams(maxTokens = 64, temperature = 0f, reserveThreads = 2)) {}
                val sec = (System.nanoTime() - t0) / 1e9
                stop.set(true); workers.forEach { it.join() }
                val row = "{\"llama_threads\": $threads, \"reserve_threads\": 2, \"busy_threads\": 2, \"tok_per_s\": ${"%.1f".format(r.stats.tokensPerSecond)}, " +
                    "\"busy_work_per_thread_per_s\": ${"%.0f".format((work.get() - w0) / sec / 2)}}"
                println("contention: $row"); rows += row
            }
            e.release()
        }
        System.getProperty("clu.contentionOut")?.let { out ->
            File(out).apply { parentFile.mkdirs() }.writeText("{\n  \"model\": \"${File(path!!).name}\",\n  \"vcpus\": $cores,\n  \"rows\": [\n    " +
                rows.joinToString(",\n    ") + "\n  ],\n  \"environment\": \"${System.getProperty("os.arch")} JVM, $cores vCPU (build container, not a phone); busy threads simulate synthesis workers\"\n}\n")
        }
    }
}
