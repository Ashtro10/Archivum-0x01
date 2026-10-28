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

    @Test fun reservingThreadsForSpeechChangesOnlyTokenGeneration() {
        val e = LlamaEngine.load("tiny4", model.absolutePath, LlamaConfig(contextLength = 512, batchSize = 128, threads = 4, useMmap = true))
        try {
            val greedy = GenParams(maxTokens = 12, temperature = 0f)
            val plain = e.generate(chat, greedy) {}.text
            // Same greedy output with 2 decode threads (cache reused), and again after going back to all threads.
            assertEquals(plain, e.generate(chat, greedy.copy(reserveThreads = 2)) {}.text)
            assertEquals(plain, e.generate(chat, greedy) {}.text)
        } finally { e.release() }
        assertEquals(3, LlamaEngine.decodeThreadsFor(5, 2)); assertEquals(4, LlamaEngine.decodeThreadsFor(6, 2))
        assertEquals(2, LlamaEngine.decodeThreadsFor(3, 2)); assertEquals(2, LlamaEngine.decodeThreadsFor(2, 2))
        assertEquals(1, LlamaEngine.decodeThreadsFor(1, 2)); assertEquals(6, LlamaEngine.decodeThreadsFor(6, 0))
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

    /**
     * Stage 3.4: stopping a reply (barge-in, Stop, a newer request) must not throw away the prompt cache. The cancel flag
     * aborts the decode step in progress; the native code used to clear the whole cache on any abort, so the next
     * reply re-read the entire conversation (seconds per hundred tokens on the phone).
     */
    @Test fun aCancelledReplyKeepsThePromptCache() {
        val long = listOf(ChatMessage("system", "You are Clu. " + "Keep answers short and precise. ".repeat(20)), ChatMessage("user", "Tell me a story."))
        engine.resetCache()
        val first = engine.generate(long, GenParams(maxTokens = 4, temperature = 0f)) {}
        val signal = CancelSignal(); var n = 0
        val cut = engine.generate(long, GenParams(maxTokens = 64, temperature = 0f), signal) { if (++n == 3) signal.cancel() }
        assertEquals("cancelled", cut.stats.stopReason)
        val next = engine.generate(long + ChatMessage("assistant", cut.text) + ChatMessage("user", "Stop. Something else."), GenParams(maxTokens = 2, temperature = 0f)) {}
        assertTrue(next.stats.reusedTokens >= first.stats.promptTokens - 1,
            "the conversation so far stays cached after a cancel: reused=${next.stats.reusedTokens} of ${next.stats.promptTokens} (prompt before: ${first.stats.promptTokens})")
        // What stays cached is exact: cancel during the prompt this time, then the same greedy reply as from an empty cache.
        val follow = long + ChatMessage("assistant", "Once upon a time") + ChatMessage("user", "Go on, but briefly please.")
        val s2 = CancelSignal()
        Thread { Thread.sleep(2); s2.cancel() }.start()
        engine.generate(follow, GenParams(maxTokens = 32, temperature = 0f), s2) {}
        val fromCache = engine.generate(follow, GenParams(maxTokens = 12, temperature = 0f)) {}
        engine.resetCache()
        val fresh = engine.generate(follow, GenParams(maxTokens = 12, temperature = 0f)) {}
        assertEquals(fresh.text, fromCache.text, "reply from the kept cache equals the reply from a fresh prompt")
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
