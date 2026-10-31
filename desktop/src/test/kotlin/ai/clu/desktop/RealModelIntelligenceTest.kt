package ai.clu.desktop

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.llm.*
import ai.clu.core.memory.*
import ai.clu.core.tools.HttpFetcher
import ai.clu.core.tools.HttpResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * M5 with a REAL model that talks (SmolLM2-135M-Instruct, Q4_1) through the whole stack: registry, inference service,
 * the chat agent, memory retrieval, cancellation, restart. Opt-in: -Dclu.desktopRealModel=<file.gguf>. Real-model
 * tests, unlike ModelRoutesTest (tiny random model), can check that replies are text a person could read; they still
 * can't judge quality, which a 135M model does not have: that needs the 3B model on the PC (see M5_LOCAL_INTELLIGENCE.md).
 */
class RealModelIntelligenceTest {
    private val modelPath = System.getProperty("clu.desktopRealModel")
    private val dir = Files.createTempDirectory("clu-real-m5").toFile()
    private val net = object : HttpFetcher {
        val requests = CopyOnWriteArrayList<String>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse { requests += url; throw java.io.IOException("offline") }
        override suspend fun postJson(url: String, json: String, headers: Map<String, String>): HttpResponse { requests += url; throw java.io.IOException("offline") }
    }
    private fun start() = Host.start(DesktopPaths(File(dir, "home")), HostConfig(maintenanceMinutes = 0, governorIntervalSeconds = 600), port = 0, http = net, log = {})

    @Test fun conversationMemoryCancellationAndRestartWithoutAnyNetwork() {
        assumeTrue(modelPath != null && File(modelPath).isFile, "no model given (-Dclu.desktopRealModel)")
        var host = start()
        try {
            val core = host.core
            val e = core.models.register(File(modelPath!!)); core.settings.set(Settings.MODEL_ACTIVE, e.id)
            core.settings.set(Settings.CONTEXT_LENGTH, 2048); core.settings.set(Settings.MAX_TOKENS, 64); core.settings.set(Settings.SEARCH_AUTO, "off")
            val owner = core.auth.actorForSpeaker(null, false)!!
            val conv = core.conversations.create().id
            fun say(t: String) = runBlocking { core.agent.respond(TurnRequest(conv, t, owner)).toList() }
            assertEquals(LlmStatus.UNLOADED, core.llm.state.value.status, "nothing loaded until a request needs the model")

            // 1. A conversation: the first request loads the model; the reply streams and is readable text.
            val t0 = System.nanoTime(); val ev = say("Hello! Please introduce yourself in one sentence.")
            val first = ev.filterIsInstance<AgentEvent.Done>().single(); val deltas = ev.filterIsInstance<AgentEvent.Text>().size
            assertTrue(ev.none { it is AgentEvent.Error }, ev.toString()); assertTrue(first.text.length > 10 && first.text.any { it.isLetter() }, "reply: ${first.text}")
            assertTrue(deltas > 1, "streamed in $deltas pieces")
            println("REAL-M5 first reply (incl. model load) ${(System.nanoTime() - t0) / 1_000_000} ms: ${first.text.take(100).replace('\n', ' ')}")
            assertEquals(LlmStatus.READY, core.llm.state.value.status); assertEquals("cpu", core.llm.state.value.backend)

            // 2. Context continuity and trimming: many turns on a 2048-token window never fail.
            repeat(10) { i -> val r = say("Tell me one short fact about the number $i."); assertTrue(r.none { it is AgentEvent.Error } && r.any { it is AgentEvent.Done }, "turn $i: $r") }
            assertTrue(core.conversations.messages(conv).size >= 22)

            // 3. Memory reaches the real model's prompt (retrieval is keyword-based: no embeddings needed).
            val m = core.memory.remember(Memory(category = MemoryCategory.CORE_FACT, title = "Hexapod battery", content = "The Hexapod robot uses a 2200 mAh LiPo battery mounted low on the chassis.", source = MemorySource.OWNER_SUPPLIED)).memory
            val before = core.memory.meta(m.id)!!.accessCount
            val mem = say("What do you know about the Hexapod battery?")
            assertTrue(mem.any { it is AgentEvent.Done } && mem.none { it is AgentEvent.Error }, mem.toString())
            assertTrue(core.memory.meta(m.id)!!.accessCount > before, "the memory was retrieved for this prompt")

            // 4. Cancellation with a real model: stops in well under a second, and the next request is clean.
            core.settings.set(Settings.MAX_TOKENS, 1500); core.settings.set(Settings.TEMPERATURE, 1.0)
            val tokens = CopyOnWriteArrayList<String>(); var outcome: InferenceOutcome? = null; var cancelMs = -1L
            runBlocking {
                val job = launch(Dispatchers.Default) { core.inferenceService.generate(InferenceRequest(listOf(ChatMessage("user", "Write a very long story.")), GenParams(maxTokens = 1500, temperature = 1f), id = "story")).collect { if (it is InferenceEvent.Token) tokens += it.text; if (it is InferenceEvent.Completed) outcome = it.outcome } }
                withTimeout(30_000) { while (tokens.size < 5) delay(5) }
                val c0 = System.nanoTime(); core.inferenceService.cancel("story"); withTimeout(5_000) { job.join() }; cancelMs = (System.nanoTime() - c0) / 1_000_000
            }
            assertEquals(InferenceOutcome.CANCELLED, outcome); assertTrue(cancelMs < 1500, "cancel took $cancelMs ms"); assertTrue(tokens.size < 1400)
            core.settings.set(Settings.MAX_TOKENS, 32)
            val after = say("Say OK."); assertTrue(after.filterIsInstance<AgentEvent.Done>().single().text.isNotBlank())
            println("REAL-M5 cancel took $cancelMs ms after ${tokens.size} tokens")

            // 5. Unload, then restart the whole host on the same data folder: registration, memory and conversation survive; the model reloads on demand.
            runBlocking { core.inferenceService.unload() }; assertEquals(LlmStatus.UNLOADED, core.llm.state.value.status)
            host.stop()
            host = start()
            val core2 = host.core
            assertEquals(e.id, core2.settings.string(Settings.MODEL_ACTIVE)); assertEquals(1, core2.models.list().size)
            assertTrue(core2.conversations.messages(conv).size >= 24); assertEquals(m.id, core2.memory.recall(RecallRequest("Hexapod battery LiPo", touch = false)).first().memory!!.id)
            core2.settings.set(Settings.MAX_TOKENS, 32)
            val owner2 = core2.auth.actorForSpeaker(null, false)!!
            val again = runBlocking { core2.agent.respond(TurnRequest(conv, "Are you still there?", owner2)).toList() }
            assertTrue(again.filterIsInstance<AgentEvent.Done>().single().text.isNotBlank() && again.none { it is AgentEvent.Error }, again.toString())
            assertEquals(LlmStatus.READY, core2.llm.state.value.status)

            // 6. The whole time there was no internet and nothing failed. The only attempts were web lookups of the subject the owner
            //    asked about; nothing from memory, the conversation or the documents was put in a request.
            assertTrue(net.requests.all { it.contains("duckduckgo") || it.contains("wikipedia") }, "only search providers were contacted: ${net.requests}")
            assertTrue(net.requests.none { r -> listOf("LiPo", "2200", "mAh", "chassis", "introduce", "Mira").any { r.contains(it, true) } }, "no private text left the PC: ${net.requests}")
        } finally { runCatching { host.stop() }; dir.deleteRecursively() }
    }
}
