package ai.clu.core

import ai.clu.core.config.Settings
import ai.clu.core.evolution.StructuredPatches
import ai.clu.core.llm.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/** Opt-in (-Dclu.codingModels=…): every development-mode grammar is accepted by the native llama.cpp parser. */
class GrammarProbeTest {
    @Test fun everyDevelopmentGrammarIsAcceptedByLlamaCpp() {
        val m = System.getProperty("clu.codingModels")?.split(',')?.map(::File)?.firstOrNull { it.isFile }
        assumeTrue(m != null, "set -Dclu.codingModels=model.gguf")
        val core = newCore(platform = TestPlatform()); core.settings.set(Settings.THREADS, 4)
        val e = core.models.register(m!!); core.settings.set(Settings.MODEL_ACTIVE, e.id); runBlocking { core.llm.loadIfNeeded() }
        for ((n, g) in listOf("patch" to StructuredPatches.GRAMMAR, "locate" to StructuredPatches.LOCATE_GRAMMAR, "rewrite" to StructuredPatches.REWRITE_GRAMMAR, "raw-line" to StructuredPatches.RAW_LINE_GRAMMAR, "blocks" to StructuredPatches.BLOCK_GRAMMAR, "member" to StructuredPatches.MEMBER_GRAMMAR,
                "candidates" to StructuredPatches.locateGrammar(listOf("core/src/main/kotlin/demo/A.kt" to 4, "core/src/main/kotlin/demo/A.kt" to 6, "core/src/main/kotlin/demo/B.kt" to 12)))) {
            val r = runCatching { runBlocking { core.inferenceService.complete(InferenceRequest(listOf(ChatMessage("user", "Answer as JSON")), GenParams(maxTokens = 40, grammar = g), owner = "probe", fitContext = false)).text } }
            assertTrue(r.isSuccess && r.getOrThrow().isNotBlank(), "$n grammar: ${r.exceptionOrNull() ?: r.getOrNull()}")
            println("GRAMMAR $n ok: ${r.getOrThrow().replace("\n", "⏎").take(100)}")
        }
        core.shutdown()
    }
}
