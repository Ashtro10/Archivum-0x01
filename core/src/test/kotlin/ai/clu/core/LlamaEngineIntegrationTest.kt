package ai.clu.core

import ai.clu.core.llm.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.*

/**
 * Exercises the real llama.cpp JNI bridge (host build of native/jni/clu_llama_jni.cpp) with a tiny random-weight
 * GGUF that has a real Qwen2 tokenizer. Verifies plumbing, not response quality.
 * Requires: scripts/build_native.sh host && scripts/make_test_gguf.py (run by ./gradlew :core:test via prepareNativeTest).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LlamaEngineIntegrationTest {
    private val lib = File(System.getProperty("clu.nativeLib", "../native/build/host/libclu_llama.so"))
    private val model = File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))
    private lateinit var engine: LlamaEngine

    @BeforeAll
    fun setup() {
        assumeTrue(lib.exists() && model.exists(), "native test artifacts missing")
        LlamaBridge.load(lib.absolutePath)
        engine = LlamaEngine.load("tiny", model.absolutePath, LlamaConfig(contextLength = 1024, batchSize = 128, threads = 2, useMmap = true))
    }

    private val chat = listOf(ChatMessage("system", "You are Clu."), ChatMessage("user", "Hello there 👋"))

    @Test fun modelInfoIsReported() {
        assertTrue(engine.info.vocab > 100_000)
        assertTrue(engine.info.hasChatTemplate)
        assertTrue(engine.info.params > 0)
        assertEquals(1024, engine.contextSize())
    }

    @Test fun chatTemplateIsApplied() {
        val p = engine.format(chat)
        assertTrue(p.contains("<|im_start|>system\nYou are Clu.<|im_end|>"), p)
        assertTrue(p.endsWith("<|im_start|>assistant\n"), p)
        assertTrue(p.contains("👋"))
        assertTrue(engine.countTokens(chat) in 10..60)
    }

    @Test fun generatesTokensAndReusesPromptCache() {
        val pieces = mutableListOf<String>()
        val r1 = engine.generate(chat, GenParams(maxTokens = 12, temperature = 0f)) { pieces += it }
        assertEquals(12, r1.stats.generatedTokens)
        assertEquals(r1.text, pieces.joinToString(""))
        assertEquals("max_tokens", r1.stats.stopReason)
        // Same prefix + a new turn: most of the prompt must come from the KV cache.
        val follow = chat + ChatMessage("assistant", r1.text) + ChatMessage("user", "And again")
        val r2 = engine.generate(follow, GenParams(maxTokens = 4, temperature = 0f)) {}
        assertTrue(r2.stats.reusedTokens >= r1.stats.promptTokens - 1, "reused=${r2.stats.reusedTokens} prompt1=${r1.stats.promptTokens}")
        // Greedy decoding is deterministic for identical prompts.
        engine.resetCache()
        val r3 = engine.generate(chat, GenParams(maxTokens = 12, temperature = 0f)) {}
        assertEquals(r1.text, r3.text)
    }

    @Test fun grammarConstrainsOutput() {
        val r = engine.generate(chat, GenParams(maxTokens = 20, temperature = 0.8f, grammar = "root ::= (\"yes\" | \"no\")")) {}
        assertTrue(r.text == "yes" || r.text == "no", "got '${r.text}'")
    }

    @Test fun invalidGrammarIsReported() {
        val e = assertFailsWith<LlmException> { engine.generate(chat, GenParams(maxTokens = 5, grammar = "root ::= (")) {} }
        assertTrue(e.message!!.contains("grammar"))
    }

    @Test fun cancellationStopsGeneration() {
        var count = 0
        val t = thread { Thread.sleep(50) }
        val r = engine.generate(chat, GenParams(maxTokens = 5000, temperature = 1f)) { count++; if (count == 5) engine.cancel() }
        t.join()
        assertEquals("cancelled", r.stats.stopReason)
        assertTrue(r.stats.generatedTokens < 50)
    }

    @Test fun promptLongerThanContextFailsCleanly() {
        val huge = listOf(ChatMessage("user", "word ".repeat(3000)))
        val e = assertFailsWith<LlmException> { engine.generate(huge, GenParams(maxTokens = 5)) {} }
        assertTrue(e.message!!.contains("prompt too long"), e.message)
    }

    @Test fun missingModelFailsWithMessage() {
        val e = assertFailsWith<LlmException> { LlamaEngine.load("x", "/nonexistent.gguf", LlamaConfig(512, 64, 1, true)) }
        assertTrue(e.message!!.contains("failed to load model"))
    }

    @Test fun corruptModelFailsWithMessage() {
        val f = File.createTempFile("bad", ".gguf"); f.writeBytes("GGUF".toByteArray() + ByteArray(64) { 7 })
        assertFailsWith<LlmException> { LlamaEngine.load("x", f.absolutePath, LlamaConfig(512, 64, 1, true)) }
    }

    @Test fun stopSequencesTruncate() {
        val f = StopSequenceFilter(listOf("<END>"))
        val out = StringBuilder()
        for (p in listOf("Hello <E", "N", "D> trailing")) out.append(f.push(p))
        out.append(f.flush())
        assertEquals("Hello ", out.toString())
        assertTrue(f.stopped)
    }

    @Test fun utf8SplitAcrossTokens() {
        val bytes = "é👋".toByteArray()
        val u = Utf8Stream()
        val s = bytes.map { u.push(byteArrayOf(it)) }.joinToString("") + u.finish()
        assertEquals("é👋", s)
    }
}
