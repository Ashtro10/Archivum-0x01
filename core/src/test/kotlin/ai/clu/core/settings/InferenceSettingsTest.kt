package ai.clu.core.settings

import ai.clu.core.config.Settings
import ai.clu.core.llm.*
import ai.clu.core.newCore
import ai.clu.core.TestLlmPlatform
import ai.clu.core.TestPlatform
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

/** The M5 inference settings change what the runtime does (the registry requires one verifying test per setting). */
class InferenceSettingsTest {
    private val model = File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))
    private val msgs = listOf(ChatMessage("user", "Hello"))
    private fun events(core: ai.clu.core.CluCore, p: GenParams = GenParams(maxTokens = 8)) = runBlocking { core.inferenceService.generate(InferenceRequest(msgs, p)).toList() }

    @Test fun gpuLayers() {
        assumeTrue(model.exists(), "tiny.gguf missing")
        var asked = -1
        val platform = object : TestLlmPlatform() { override val gpuAvailable = true }
        val core = newCore(platform = TestPlatform(llm = platform))
        val llm = LlmService(core.settings, core.models, platform, null, loader = ModelLoader { id, path, cfg -> asked = cfg.gpuLayers; LlamaEngine.load(id, path, cfg) })
        core.settings.set(Settings.MODEL_ACTIVE, core.models.register(model).id)
        core.settings.set(Settings.ACCELERATION, "cpu"); runBlocking { llm.loadIfNeeded() }; assertEquals(0, asked)
        core.settings.set(Settings.ACCELERATION, "gpu"); core.settings.set(Settings.GPU_LAYERS, 12); runBlocking { llm.reload() }; assertEquals(12, asked)
        runBlocking { llm.unload() }
    }

    @Test fun fitContext() {
        assumeTrue(model.exists(), "tiny.gguf missing")
        val entryCore = newCore()
        val e = entryCore.models.register(model)
        val need4096 = ModelRegistry.estimateRamBytes(e, 4096); val need1024 = ModelRegistry.estimateRamBytes(e, 1024)
        assertTrue(need1024 < need4096)
        val avail = ((need1024 + need4096) / 2 / 1.35).toLong() // enough for a smaller context, not for 4096
        val platform = TestLlmPlatform(available = avail)
        val core = newCore(platform = TestPlatform(llm = platform))
        core.settings.set(Settings.MODEL_ACTIVE, core.models.register(model).id); core.settings.set(Settings.CONTEXT_LENGTH, 4096)
        runBlocking { core.llm.loadIfNeeded() }
        val st = core.llm.state.value
        assertEquals(LlmStatus.READY, st.status); assertTrue(st.contextSize in 1024 until 4096, "context ${st.contextSize}"); assertTrue(st.note.contains("Context reduced"))
        runBlocking { core.llm.unload() }
        core.settings.set(Settings.FIT_CONTEXT, false)
        runBlocking { core.llm.loadIfNeeded() }
        assertEquals(LlmStatus.ERROR, core.llm.state.value.status); assertTrue(core.llm.state.value.message.startsWith("Not enough free memory"))
    }

    @Test fun maxQueued() {
        assumeTrue(model.exists(), "tiny.gguf missing")
        val core = newCore(); core.settings.set(Settings.MODEL_ACTIVE, core.models.register(model).id)
        core.settings.set(Settings.MAX_QUEUED, 0)
        runBlocking {
            val svc = core.inferenceService
            val first = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { svc.generate(InferenceRequest(msgs, GenParams(maxTokens = 600, temperature = 1f), id = "a")).toList() }
            kotlinx.coroutines.withTimeout(30_000) { while (svc.snapshot().running == null) kotlinx.coroutines.delay(5) }
            val refused = svc.generate(InferenceRequest(msgs, id = "b")).toList()
            assertEquals(InferenceError.BUSY, (refused.single() as InferenceEvent.Failed).error)
            svc.cancel("a"); first.await()
        }
        core.llm.let { runBlocking { it.unload() } }
    }

    @Test fun timeouts() {
        val core = newCore()
        // The limits are real settings with sane ranges, read by the service on every request.
        core.settings.set(Settings.FIRST_TOKEN_TIMEOUT, 30); core.settings.set(Settings.STALL_TIMEOUT, 10); core.settings.set(Settings.TOTAL_TIMEOUT, 60)
        val snap = core.inferenceService.snapshot()
        assertEquals(Triple(30, 10, 60), Triple(snap.firstTokenTimeoutS, snap.stallTimeoutS, snap.totalTimeoutS))
        assertIs<ai.clu.core.config.SettingResult.Invalid>(core.settings.set(Settings.TOTAL_TIMEOUT, 5))
    }
}
