package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.diag.TurnEvent
import ai.clu.core.llm.LlmStatus
import ai.clu.core.memory.Memory
import ai.clu.core.memory.MemoryCategory
import ai.clu.core.memory.MemorySource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

/**
 * Milestone 1 performance baseline, measured through the real conversation engine (CluCore → CluAgent → inference service
 * → llama.cpp) with real GGUF models: core start-up, model initialisation, instruction warm-up, time to the first token,
 * generation speed, total turn time, process memory, cancellation latency and memory-retrieval latency.
 *
 * Opt-in: `-Dclu.perfModels=<a.gguf>,<b.gguf>`; `-Dclu.perfOut=<file.json>` writes the results.
 */
class PerformanceBaselineTest {
    private fun rssMb(): Long = File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.filter(Char::isDigit).toLong() / 1024
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    @Test fun measureTheConversationPipeline() {
        val models = System.getProperty("clu.perfModels")?.split(',')?.map(::File)?.filter { it.isFile }.orEmpty()
        assumeTrue(models.isNotEmpty(), "opt-in: -Dclu.perfModels=<gguf,...>")
        val results = buildJsonArray { for (m in models) add(measure(m)) }
        println("[perf] $results")
        System.getProperty("clu.perfOut")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), results)) }
    }

    private fun measure(model: File): JsonObject = runBlocking {
        System.gc()
        val rssStart = rssMb()
        var t0 = System.nanoTime()
        val core = newCore(platform = TestPlatform())
        val startupMs = ms(t0)
        core.settings.set(Settings.THREADS, 4); core.settings.set(Settings.CONTEXT_LENGTH, 4096)
        core.settings.set(Settings.NET_ENABLED, false); core.settings.set(Settings.LEARNING_ALTERNATIVES, false)
        val entry = core.models.register(model); core.settings.set(Settings.MODEL_ACTIVE, entry.id)
        t0 = System.nanoTime()
        val loaded = core.llm.loadIfNeeded()
        val loadMs = ms(t0)
        assertNotNull(loaded, core.llm.state.value.message)
        val rssLoaded = rssMb()
        val st = core.llm.state.value
        val loadEvents = core.diag.recent(200).filter { it.tag == "llm" && it.message.startsWith("loaded ") }.map { it.message }
        t0 = System.nanoTime()
        val warm = core.agent.warmUp()
        val warmMs = ms(t0)
        val owner = core.auth.actorForSpeaker(null, speakerIdActive = false)!!
        val conv = core.conversations.create().id
        val questions = listOf("Hello! How are you today?", "What is the capital of France?", "Why is the sky blue? Explain briefly.", "Thanks. And what about sunsets?")
        val turns = buildJsonArray {
            for (q in questions) {
                var first = -1L; var trace: ai.clu.core.diag.RequestTrace.Request? = null
                val start = System.nanoTime()
                val ev = core.agent.respond(TurnRequest(conv, q, owner, onTrace = { trace = it })).onEachText { if (first < 0) first = ms(start) }.toList()
                val total = ms(start)
                val done = ev.filterIsInstance<AgentEvent.Done>().lastOrNull()
                val call = trace!!.events.lastOrNull { it.name == TurnEvent.LLM_CALL }?.detail.orEmpty()
                fun num(k: String) = Regex("$k=(\\d+)").find(call)?.groupValues?.get(1)?.toLong() ?: -1
                val gen = num("gen"); val tps = done?.meta?.stats?.get("tok_per_s") ?: -1.0
                addJsonObject {
                    put("question", q); put("firstTokenMs", first); put("totalMs", total)
                    put("promptTokens", num("prompt")); put("reusedTokens", num("reused")); put("promptReadMs", num("prefill"))
                    put("generatedTokens", gen); put("tokensPerSecond", tps)
                    put("memoryLookupMs", trace!!.events.firstOrNull { it.name == TurnEvent.MEMORY }?.detail?.substringBefore("ms")?.toLongOrNull() ?: -1)
                    put("reply", (done?.text ?: ev.filterIsInstance<AgentEvent.Error>().lastOrNull()?.text ?: "").take(160))
                }
            }
        }
        val rssAfterTurns = rssMb()
        // Cancellation: a long answer is stopped after its first words.
        var firstAt = 0L
        val job = launch(Dispatchers.Default) { core.agent.respond(TurnRequest(conv, "Write a long, detailed essay about the history of the Roman Empire.", owner)).collect { e -> if (e is AgentEvent.Text && firstAt == 0L) firstAt = System.nanoTime() } }
        withTimeout(120_000) { while (firstAt == 0L) delay(5) }
        delay(300)
        val cancelAt = System.nanoTime(); core.agent.cancel(null)
        job.join(); val cancelMs = ms(cancelAt)
        val readyAfterCancel = core.llm.state.value.status
        // Memory retrieval with a realistic store: 2,000 records.
        repeat(2000) { i -> core.memory.remember(Memory(category = if (i % 3 == 0) MemoryCategory.KNOWLEDGE else MemoryCategory.CORE_FACT, title = "Note $i",
            content = "Record $i about ${listOf("bikes", "Pune", "the hexapod project", "music", "work", "family", "cooking")[i % 7]} and detail ${i * 7}.", source = MemorySource.OWNER_SUPPLIED)) }
        val lookups = (1..10).map { val t = System.nanoTime(); core.memories.rank("tell me about the hexapod project", listOf("clu"), limit = 8); ms(t) }
        var t2 = System.nanoTime()
        core.llm.unload(); val unloadMs = ms(t2)
        System.gc(); Thread.sleep(300)
        buildJsonObject {
            put("model", entry.name); put("file", model.name); put("fileMb", model.length() / 1_048_576); put("quantization", entry.info.quantization)
            put("threads", st.threads); put("contextTokens", st.contextSize)
            put("coreStartupMs", startupMs); put("modelLoadCallMs", loadMs); put("nativeLoadMs", st.loadMs); put("loadsLogged", JsonArray(loadEvents.map(::JsonPrimitive)))
            put("instructionTokens", warm?.promptTokens ?: -1); put("instructionWarmUpMs", warmMs)
            put("turns", turns)
            put("cancelLatencyMs", cancelMs); put("modelAfterCancel", readyAfterCancel.name)
            put("memoryLookup2000RecordsMsMedian", lookups.sorted()[lookups.size / 2]); put("memoryLookup2000RecordsMsMax", lookups.max())
            put("unloadMs", unloadMs)
            put("rssMbStart", rssStart); put("rssMbModelLoaded", rssLoaded); put("rssMbAfterTurns", rssAfterTurns); put("rssMbAfterUnload", rssMb())
        }.also { core.shutdown() }
    }

    private fun kotlinx.coroutines.flow.Flow<AgentEvent>.onEachText(f: () -> Unit) = kotlinx.coroutines.flow.flow { collect { if (it is AgentEvent.Text) f(); emit(it) } }
}
