package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.config.*
import ai.clu.core.llm.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*
import org.junit.jupiter.api.Test

/** Model settings exercised against the real llama.cpp engine (tiny random-weight GGUF models). */
class ModelSettingsTest {
    private val modelA = File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))
    private val modelB = File(modelA.parentFile, "tiny2.gguf")
    private val platform = TestPlatform()
    private val core = newCore(platform = platform)
    private val s = core.settings

    private fun registerAndSelect(f: File): ModelEntry = core.models.register(f).also { s.set(Settings.MODEL_ACTIVE, it.id) }
    private fun greedy(): String = runBlocking {
        val e = core.llm.engine() ?: error("no engine: ${core.llm.state.value.message}")
        e.generate(listOf(ChatMessage("user", "Hello")), GenParams(maxTokens = 8, temperature = 0f)) {}.text
    }

    @Test fun activeModel(): Unit = runBlocking {
        assertEquals(LlmStatus.NO_MODEL, core.llm.state.value.status)
        val a = registerAndSelect(modelA)
        assertEquals("GGUF v3", a.format); assertEquals("llama", a.info.architecture); assertEquals(ModelStatus.UNVERIFIED, a.status)
        val textA = greedy()
        assertEquals(LlmStatus.READY, core.llm.state.value.status)
        assertEquals(ModelStatus.VERIFIED, core.models.get(a.id)!!.status)
        // Switching the active model changes actual inference output.
        val b = core.models.register(modelB, "tiny-b")
        s.set(Settings.MODEL_ACTIVE, b.id)
        core.llm.reload()
        assertEquals(b.id, core.llm.state.value.modelId)
        val textB = greedy()
        assertNotEquals(textA, textB, "different weights must produce different greedy output")
        // Missing file is reported, not crashed on; Clu falls back to the last model that worked (final Stage 3).
        val tmp = File.createTempFile("gone", ".gguf"); modelA.copyTo(tmp, overwrite = true)
        val c = core.models.register(tmp, "temp"); tmp.delete()
        s.set(Settings.MODEL_ACTIVE, c.id); core.llm.reload()
        assertEquals(LlmStatus.READY, core.llm.state.value.status)
        assertTrue(core.llm.state.value.message.contains("missing"), core.llm.state.value.message)
        assertEquals(b.id, core.llm.state.value.modelId, "back on the last working model")
        assertEquals(ModelStatus.MISSING, core.models.get(c.id)!!.status)
    }

    @Test fun incompatibleFileIsRejectedBeforeLoading() {
        val bad = File.createTempFile("bad", ".gguf").apply { writeText("not a model") }
        val e = assertFailsWith<LlmException> { core.models.register(bad) }
        assertTrue(e.message!!.contains("bad magic"))
    }

    @Test fun contextLength(): Unit = runBlocking {
        registerAndSelect(modelA)
        s.set(Settings.CONTEXT_LENGTH, 1024); core.llm.reload()
        assertEquals(1024, core.llm.engine()!!.contextSize())
        s.set(Settings.CONTEXT_LENGTH, 512); core.llm.reload()
        assertEquals(512, core.llm.engine()!!.contextSize())
        assertIs<SettingResult.Invalid>(s.set(Settings.CONTEXT_LENGTH, 100))
    }

    @Test fun maxTokens(): Unit = runBlocking {
        registerAndSelect(modelA)
        assertIs<SettingResult.Invalid>(s.set(Settings.MAX_TOKENS, 6))
        s.set(Settings.MAX_TOKENS, 32)
        val p = GenerationParams.from(s, null, false)
        assertEquals(32, p.maxTokens)
        val r = core.llm.engine()!!.generate(listOf(ChatMessage("user", "Count")), p.copy(temperature = 0f)) {}
        assertTrue(r.stats.generatedTokens in 1..32)
    }

    @Test fun sampling(): Unit = runBlocking {
        s.set(Settings.TEMPERATURE, 0.0); s.set(Settings.TOP_K, 5); s.set(Settings.TOP_P, 0.5); s.set(Settings.MIN_P, 0.1); s.set(Settings.REPEAT_PENALTY, 1.3)
        val p = GenerationParams.from(s, null, false)
        assertEquals(0f, p.temperature); assertEquals(5, p.topK); assertEquals(0.5f, p.topP); assertEquals(0.1f, p.minP); assertEquals(1.3f, p.repeatPenalty)
        registerAndSelect(modelA)
        val e = core.llm.engine()!!
        val msgs = listOf(ChatMessage("user", "Hi"))
        assertEquals(e.generate(msgs, p.copy(maxTokens = 10)) {}.text, e.generate(msgs, p.copy(maxTokens = 10)) {}.text, "temperature 0 is deterministic")
    }

    @Test fun threads() {
        val e = registerAndSelect(modelA)
        assertEquals(2, core.llm.configFor(e).threads) // auto
        s.set(Settings.THREADS, 4); assertEquals(4, core.llm.configFor(e).threads)
    }

    @Test fun batch() { val e = registerAndSelect(modelA); s.set(Settings.BATCH, 64); assertEquals(64, core.llm.configFor(e).batchSize) }

    @Test fun mmap(): Unit = runBlocking {
        val e = registerAndSelect(modelA)
        s.set(Settings.USE_MMAP, false)
        assertFalse(core.llm.configFor(e).useMmap)
        core.llm.reload(); assertEquals(LlmStatus.READY, core.llm.state.value.status)
    }

    @Test fun acceleration() {
        val e = registerAndSelect(modelA)
        s.set(Settings.ACCELERATION, "gpu")
        assertFalse(platform.llm.gpuAvailable)
        assertEquals(0, core.llm.configFor(e).gpuLayers, "GPU must not be claimed when the build has no GPU backend")
    }

    @Test fun autoUnload(): Unit = runBlocking {
        registerAndSelect(modelA)
        s.set(Settings.AUTO_UNLOAD, 1)
        core.llm.loadIfNeeded()
        assertEquals(LlmStatus.READY, core.llm.state.value.status)
        (core.clock as ai.clu.core.util.ManualClock).advance(30_000); core.llm.checkIdle()
        assertEquals(LlmStatus.READY, core.llm.state.value.status)
        (core.clock as ai.clu.core.util.ManualClock).advance(61_000); core.llm.checkIdle()
        assertEquals(LlmStatus.UNLOADED, core.llm.state.value.status)
        s.set(Settings.AUTO_UNLOAD, 0); core.llm.loadIfNeeded()
        (core.clock as ai.clu.core.util.ManualClock).advance(10 * 3_600_000); core.llm.checkIdle()
        assertEquals(LlmStatus.READY, core.llm.state.value.status)
    }

    @Test fun loadOnStart() {
        registerAndSelect(modelA)
        s.set(Settings.LOAD_ON_START, false)
        core.llm.start(); Thread.sleep(300)
        assertEquals(LlmStatus.UNLOADED, core.llm.state.value.status)
        val core2 = newCore(platform = TestPlatform())
        core2.settings.set(Settings.MODEL_ACTIVE, core2.models.register(modelA).id)
        core2.llm.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (core2.llm.state.value.status != LlmStatus.READY && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(LlmStatus.READY, core2.llm.state.value.status)
    }

    @Test fun thermal() {
        val e = registerAndSelect(modelA)
        s.set(Settings.THREADS, 4)
        val normal = GenerationParams.from(s, null, thermalSevere = false).maxTokens
        assertTrue(GenerationParams.from(s, null, thermalSevere = true).maxTokens < normal)
        platform.llm.thermal = true
        assertEquals(2, core.llm.configFor(e).threads)
        s.set(Settings.THERMAL, false)
        assertEquals(4, core.llm.configFor(e).threads)
        assertEquals(normal, GenerationParams.from(s, null, thermalSevere = true).maxTokens)
    }

    @Test fun chatTemplate(): Unit = runBlocking {
        registerAndSelect(modelA)
        val engine = core.llm.loadIfNeeded()!!
        assertTrue(engine.format(listOf(ChatMessage("user", "hi"))).contains("<|im_start|>user"))
        s.set(Settings.CHAT_TEMPLATE, "llama3")
        val reloaded = core.llm.reload()!!
        assertTrue(reloaded.format(listOf(ChatMessage("user", "hi"))).contains("<|start_header_id|>user<|end_header_id|>"))
    }

    @Test fun insufficientMemoryIsReportedHonestly(): Unit = runBlocking {
        registerAndSelect(modelA)
        platform.llm.available = 10_000_000
        assertNull(core.llm.loadIfNeeded())
        assertEquals(LlmStatus.ERROR, core.llm.state.value.status)
        assertTrue(core.llm.state.value.message.startsWith("Not enough free memory"))
    }
}
