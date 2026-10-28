package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.llm.*
import ai.clu.core.memory.Memory
import ai.clu.core.memory.MemoryCategory
import ai.clu.core.memory.MemorySource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

/**
 * Final Stage 3: the language model is replaceable behind the model layer. Two differently built models — Qwen2
 * tokenizer with a ChatML template, and the Llama 3 tokenizer (128k vocabulary) with Llama 3's header template — load
 * through the same LlmService path and serve the same agent; memories, conversations and settings survive the switch;
 * a model that can't be loaded falls back to the last one that worked. Real llama.cpp through the JNI bridge; the
 * models have random weights (plumbing, not answer quality).
 */
class ModelProviderTest {
    private val lib = File(System.getProperty("clu.nativeLib", "../native/build/host/libclu_llama.so"))
    private val qwenShaped = File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))
    private val llama3Shaped = File(qwenShaped.parentFile, "tiny-llama3.gguf")

    private fun available() = lib.exists() && qwenShaped.exists() && llama3Shaped.exists()

    @Test fun differentTokenizersAndTemplatesLoadThroughTheSameEngine() {
        assumeTrue(available(), "native test artifacts missing")
        LlamaBridge.load(lib.absolutePath)
        val chat = listOf(ChatMessage("system", "You are Clu."), ChatMessage("user", "Hello"))
        val cfg = LlamaConfig(contextLength = 512, batchSize = 64, threads = 2, useMmap = true)
        val a = LlamaEngine.load("a", qwenShaped.absolutePath, cfg)
        val b = LlamaEngine.load("b", llama3Shaped.absolutePath, cfg)
        try {
            assertTrue(a.format(chat).contains("<|im_start|>user\nHello<|im_end|>"), "ChatML from the first model's own template")
            assertTrue(b.format(chat).contains("<|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|>"), "Llama 3 headers from the second model's template")
            assertNotEquals(a.info.vocab, b.info.vocab, "different tokenizers (${a.info.vocab} vs ${b.info.vocab})")
            assertTrue(b.info.vocab == 128_256)
            for (e in listOf(a, b)) assertEquals(4, e.generate(chat, GenParams(maxTokens = 4, temperature = 0f)) {}.stats.generatedTokens)
        } finally { a.release(); b.release() }
    }

    @Test fun switchingModelsKeepsMemoriesConversationsAndTheAgentWorking(): Unit = runBlocking {
        assumeTrue(available(), "native test artifacts missing")
        val core = newCore()
        val s = core.settings
        s.set(Settings.THREADS, 2); s.set(Settings.CONTEXT_LENGTH, 1024); s.set(Settings.MAX_TOKENS, 16); s.set(Settings.LOAD_ON_START, false)
        val a = core.models.register(qwenShaped); val b = core.models.register(llama3Shaped)
        core.memories.save(Memory(category = MemoryCategory.MANUAL, title = "Bike", content = "Owner rides a blue bike.", source = MemorySource.OWNER_SUPPLIED))
        val conv = core.conversations.create().id
        val owner = ai.clu.core.security.Actor.ownerInApp()
        suspend fun reply(text: String) = core.agent.respond(TurnRequest(conv, text, owner)).toList()
            .filterIsInstance<AgentEvent.Done>().single()

        s.set(Settings.MODEL_ACTIVE, a.id); core.llm.loadIfNeeded()
        val first = reply("hello there")
        assertEquals("local", first.meta.source); assertEquals(a.id, core.llm.state.value.modelId)

        s.set(Settings.MODEL_ACTIVE, b.id); core.llm.reload()
        assertEquals(b.id, core.llm.state.value.modelId)
        assertEquals(LlmStatus.READY, core.llm.state.value.status)
        val second = reply("and again")
        assertEquals("local", second.meta.source)
        assertEquals(1, core.memories.count(), "memories are independent of the model")
        assertEquals(4, core.conversations.messages(conv).size, "the conversation continues across the switch")
        assertEquals(1024, core.llm.state.value.contextSize, "settings apply to the new model")
        core.shutdown()
    }

    @Test fun aModelThatCannotLoadFallsBackToTheLastWorkingOne(): Unit = runBlocking {
        assumeTrue(available(), "native test artifacts missing")
        val core = newCore()
        val s = core.settings
        s.set(Settings.THREADS, 2); s.set(Settings.CONTEXT_LENGTH, 512); s.set(Settings.MAX_TOKENS, 8); s.set(Settings.LOAD_ON_START, false)
        val good = core.models.register(qwenShaped)
        s.set(Settings.MODEL_ACTIVE, good.id); core.llm.loadIfNeeded()
        core.agent.respond(TurnRequest(core.conversations.create().id, "hi", ai.clu.core.security.Actor.ownerInApp())).toList()
        assertEquals(ModelStatus.VERIFIED, core.models.get(good.id)!!.status, "it generated, so it is known to work")
        // A new model that imported fine but whose file was then cut short (an interrupted copy).
        val broken = File.createTempFile("broken", ".gguf").apply { deleteOnExit(); writeBytes(qwenShaped.readBytes()) }
        val bad = core.models.register(broken, "Broken download")
        broken.writeBytes(qwenShaped.readBytes().copyOf(4 * 1024 * 1024))
        s.set(Settings.MODEL_ACTIVE, bad.id)
        val e = core.llm.reload()
        assertNotNull(e, "Clu still has a model")
        assertEquals(good.id, core.llm.state.value.modelId)
        assertEquals(good.id, s.string(Settings.MODEL_ACTIVE), "the selection is switched back too")
        assertTrue(core.llm.state.value.message.startsWith("Couldn't load Broken download"), core.llm.state.value.message)
        assertEquals(ModelStatus.INCOMPATIBLE, core.models.get(bad.id)!!.status)
        core.shutdown()
    }
}
