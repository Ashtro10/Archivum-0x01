package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.security.Actor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Stage 3.3: how much of each voice turn's prompt llama.cpp can reuse from its cache in a conversation that grows past
 * the context window (the voice screen keeps one conversation, so this is the normal case after a while). Token counts
 * depend only on the prompt layout and the tokenizer, not on the CPU, so they carry over to the phone; the times do not.
 * Opt-in: -Dclu.llmBenchModel=<gguf> [-Dclu.cacheAuditOut=<json> -Dclu.cacheAuditCtx=<tokens> -Dclu.cacheAuditTurns=<n>].
 */
class PromptCacheAudit {
    @Test fun voiceTurnsInALongConversation(): Unit = runBlocking {
        val path = System.getProperty("clu.llmBenchModel")
        assumeTrue(path != null && File(path).exists())
        val clock = ai.clu.core.util.ManualClock(1_760_000_000_000L)
        val core = newCore(clock = clock)
        val ctx = System.getProperty("clu.cacheAuditCtx")?.toInt() ?: 1536
        val s = core.settings
        s.set(Settings.THREADS, 4); s.set(Settings.MAX_TOKENS, 64); s.set(Settings.CONTEXT_LENGTH, ctx); s.set(Settings.LOAD_ON_START, false)
        s.set(Settings.MODEL_ACTIVE, core.models.register(File(path)).id)
        core.llm.loadIfNeeded() ?: error(core.llm.state.value.message)
        core.agent.warmUp()
        val conv = core.conversations.create("audit").id
        val rows = mutableListOf<Map<String, Double>>()
        repeat(System.getProperty("clu.cacheAuditTurns")?.toInt() ?: 18) { i ->
            clock.advance(20_000)
            val done = core.agent.respond(TurnRequest(conv, "question number $i about my plans for the weekend and the weather", Actor.ownerInApp(), viaVoice = true))
                .toList().filterIsInstance<AgentEvent.Done>().last()
            val st = done.meta.stats
            val prompt = st["prompt_tokens"] ?: -1.0; val reused = st["reused_tokens"] ?: -1.0
            rows += mapOf("turn" to i.toDouble(), "prompt_tokens" to prompt, "reused_tokens" to reused, "new_tokens" to prompt - reused,
                "prompt_ms" to (st["prompt_ms"] ?: -1.0))
            println("cache-audit: " + rows.last())
        }
        core.shutdown()
        System.getProperty("clu.cacheAuditOut")?.let { out ->
            File(out).apply { parentFile.mkdirs() }.writeText("{\n  \"model\": \"${File(path).name}\",\n  \"context\": $ctx,\n  \"max_tokens\": 64,\n  \"turns\": [\n" +
                rows.joinToString(",\n") { r -> "    {" + r.entries.joinToString(", ") { "\"${it.key}\": ${"%.0f".format(it.value)}" } + "}" } +
                "\n  ],\n  \"environment\": \"${System.getProperty("os.arch")} JVM, ${Runtime.getRuntime().availableProcessors()} vCPU (build container, not a phone)\"\n}\n")
        }
    }
}
