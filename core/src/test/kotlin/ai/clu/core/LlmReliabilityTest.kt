package ai.clu.core

import ai.clu.core.agent.*
import ai.clu.core.config.Settings
import ai.clu.core.diag.TurnEvent
import ai.clu.core.llm.*
import ai.clu.core.security.Actor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Request pipeline reliability (docs/LLM_RELIABILITY.md). Two kinds of tests, never mixed:
 *  - [Mocked]: a scripted engine ([SlowEngine]) stands in for the model to make timing, failures and cancellation
 *    deterministic. These test the agent / request ownership, NOT inference.
 *  - [RealInference]: the real llama.cpp JNI bridge with the tiny random-weight GGUF (native/build/test/tiny.gguf).
 *    Real tokenisation, prompt processing, sampling, cancellation and KV cache; replies are meaningless text.
 */
class LlmReliabilityTest {

    /** MOCK engine: emits [tokens] words, one every [delayMs], honouring the per-request cancel signal. */
    class SlowEngine(var tokens: Int = 20, var delayMs: Long = 20, var firstDelayMs: Long = 0, var throwWith: Throwable? = null,
                     var text: (Int) -> String = { "w$it " }, var firstDelayFor: ((call: Int) -> Long)? = null) : LlmEngine {
        override val id = "mock-slow"
        override val source = "local"
        val started = AtomicInteger(0)
        @Volatile var lastThread = ""
        @Volatile var lastStop = ""
        override fun isReady() = true
        override fun countTokens(messages: List<ChatMessage>) = messages.sumOf { it.content.length / 4 + 4 }
        override fun contextSize() = 4096
        override fun cancel() {}
        override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit) = generate(messages, params, CancelSignal(), onText)
        override fun generate(messages: List<ChatMessage>, params: GenParams, signal: CancelSignal, onText: (String) -> Unit): GenResult {
            val call = started.incrementAndGet(); lastThread = Thread.currentThread().name
            throwWith?.let { throw it }
            val out = StringBuilder()
            var waited = 0L
            val firstDelay = firstDelayFor?.invoke(call) ?: firstDelayMs
            while (waited < firstDelay && !signal.isCancelled) { Thread.sleep(5); waited += 5 }
            var n = 0
            while (n < tokens && !signal.isCancelled) {
                Thread.sleep(delayMs)
                if (signal.isCancelled) break
                val t = text(n); out.append(t); onText(t); n++
            }
            lastStop = if (signal.isCancelled) "cancelled" else "eog"
            return GenResult(out.toString(), GenStats(promptTokens = 10, generatedTokens = n, stopReason = lastStop), source)
        }
    }

    private fun req(core: CluCore, text: String = "Hello", conv: String = core.conversations.create().id, voice: Boolean = false) =
        TurnRequest(conv, text, Actor.ownerInApp(), viaVoice = voice)

    private fun events(core: CluCore, r: TurnRequest, timeoutMs: Long = 10_000) = runBlocking { withTimeout(timeoutMs) { core.agent.respond(r).toList() } }

    private fun lastTrace(core: CluCore) = core.agent.trace.recent().first()

    @Nested inner class Mocked {
        private val core = newCore().also { it.settings.set(Settings.LEARNING_ALTERNATIVES, false) }
        private val engine = SlowEngine().also { core.useEngine(it) }

        @Test fun `1 normal request completes with a full lifecycle`() {
            val ev = events(core, req(core))
            val done = ev.filterIsInstance<AgentEvent.Done>().single()
            assertEquals((0 until 20).joinToString("") { "w$it " }.trim(), done.text)
            assertEquals(ev.filterIsInstance<AgentEvent.Text>().joinToString("") { it.delta }.trim(), done.text, "streamed text equals the final reply")
            val names = lastTrace(core).events.map { it.name }
            assertEquals(listOf(TurnEvent.MESSAGE_RECEIVED, TurnEvent.REQUEST_CREATED, TurnEvent.MODEL_READY, TurnEvent.GENERATION_STARTED,
                TurnEvent.FIRST_TOKEN, TurnEvent.GENERATION_COMPLETED), names)
            assertFalse(core.agent.busy())
        }

        @Test fun `3 slow generation completes, a stalled one times out with a clear error`() {
            core.agent.timeouts = TurnTimeouts(firstTokenMs = 400, stallMs = 300)
            engine.tokens = 10; engine.delayMs = 100 // slow but steady: every token within the stall limit
            assertNotNull(events(core, req(core)).filterIsInstance<AgentEvent.Done>().single().text.takeIf { it.isNotEmpty() })
            assertEquals(TurnEvent.GENERATION_COMPLETED, lastTrace(core).terminal!!.name)

            engine.firstDelayMs = 60_000 // never produces a first token
            val t0 = System.nanoTime()
            val ev = events(core, req(core))
            val ms = (System.nanoTime() - t0) / 1_000_000
            val err = ev.filterIsInstance<AgentEvent.Error>().single()
            assertEquals("timeout", err.kind); assertTrue(err.text.contains("took too long"), err.text)
            assertTrue(ev.last() is AgentEvent.Done, "a timed-out request still ends")
            assertEquals(TurnEvent.REQUEST_TIMEOUT, lastTrace(core).terminal!!.name)
            assertTrue(ms < 3_000, "timed out after ${ms}ms (limit 400ms)")

            engine.firstDelayMs = 0; engine.tokens = 3; engine.delayMs = 1_000 // stalls between tokens
            assertEquals("timeout", events(core, req(core)).filterIsInstance<AgentEvent.Error>().single().kind)
        }

        @Test fun `4 a runtime exception ends the request and clears the thinking state`() = runBlocking {
            engine.throwWith = IllegalStateException("native crash simulation")
            val chat = ChatTurns(core.agent, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            assertTrue(chat.send(req(core), "Hello"))
            val st = withTimeout(5_000) { chat.state.first { !it.running } }
            assertNull(st.streaming)
            assertTrue(st.events.any { it is AgentEvent.Error }, "error shown: ${st.events}")
            assertEquals(TurnEvent.INFERENCE_ERROR, lastTrace(core).terminal!!.name)
            // LlmException (llama.cpp reported an error): error with Retry, not saved as Clu's reply.
            engine.throwWith = LlmException("llama_decode failed during prompt (rc=1)")
            val conv = core.conversations.create().id
            val ev = core.agent.respond(req(core, conv = conv)).toList()
            assertTrue(ev.filterIsInstance<AgentEvent.Error>().single().text.contains("llama_decode failed"))
            assertEquals(listOf(ai.clu.core.conversation.Role.USER), core.conversations.messages(conv).map { it.role })
            assertFalse(core.agent.busy())
        }

        @Test fun `5 explicit cancel stops promptly and keeps the partial reply`() = runBlocking {
            engine.tokens = 1000; engine.delayMs = 10
            val conv = core.conversations.create().id
            val got = mutableListOf<AgentEvent>()
            val job = launch(Dispatchers.Default) { core.agent.respond(req(core, conv = conv)).collect { got += it } }
            withTimeout(5_000) { while (got.none { it is AgentEvent.Text }) delay(5) }
            val t0 = System.nanoTime()
            core.agent.cancel(TurnOrigin.TEXT)
            withTimeout(5_000) { job.join() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms < 500, "stopped in ${ms}ms")
            val done = got.filterIsInstance<AgentEvent.Done>().single()
            assertTrue(done.meta.interrupted)
            assertTrue(got.none { it is AgentEvent.Error }, "cancellation is not an error")
            assertEquals(TurnEvent.REQUEST_CANCELLED, lastTrace(core).terminal!!.name)
            assertEquals("cancelled", engine.lastStop)
            assertTrue(core.conversations.messages(conv).last().meta.interrupted)
        }

        @Test fun `6 voice cancel and stopping speech do not cancel a typed reply`() = runBlocking {
            engine.tokens = 30; engine.delayMs = 10
            val chat = ChatTurns(core.agent, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            assertTrue(chat.send(req(core), "Hello"))
            withTimeout(5_000) { chat.state.first { !it.streaming.isNullOrEmpty() } }
            core.agent.cancel(TurnOrigin.VOICE)    // voice screen closed / barge-in / mic tapped
            core.agent.cancel(TurnOrigin.LIVEKIT)
            val st = withTimeout(5_000) { chat.state.first { !it.running } }
            assertTrue(st.events.none { it is AgentEvent.Error })
            assertEquals(TurnEvent.GENERATION_COMPLETED, lastTrace(core).terminal!!.name)
            assertEquals("eog", engine.lastStop, "typed generation ran to completion")
        }

        @Test fun `7 repeated send does not start a second request or corrupt state`() = runBlocking {
            engine.tokens = 20; engine.delayMs = 10
            val chat = ChatTurns(core.agent, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            val conv = core.conversations.create().id
            assertTrue(chat.send(req(core, "One", conv), "One"))
            repeat(5) { assertFalse(chat.send(req(core, "One", conv), "One"), "duplicate submission ignored") }
            withTimeout(5_000) { chat.state.first { !it.running } }
            assertEquals(1, engine.started.get())
            assertEquals(listOf("One"), core.conversations.messages(conv).filter { it.role == ai.clu.core.conversation.Role.USER }.map { it.content })
            assertTrue(chat.send(req(core, "Two", conv), "Two"), "a new message is accepted once the reply is done")
            withTimeout(5_000) { chat.state.first { !it.running && it.seq == 2L } }
            assertEquals(4, core.conversations.messages(conv).size)
            // Agent-level policy: a second typed request supersedes the first (which ends cancelled, not hung).
            engine.tokens = 500
            val a = async(Dispatchers.Default) { core.agent.respond(req(core, "A", conv)).toList() }
            delay(100)
            val b = async(Dispatchers.Default) { core.agent.respond(req(core, "B", conv)).toList() }
            assertTrue(withTimeout(5_000) { a.await() }.filterIsInstance<AgentEvent.Done>().single().meta.interrupted)
            engine.tokens = 5
            assertFalse(withTimeout(10_000) { b.await() }.filterIsInstance<AgentEvent.Done>().single().meta.interrupted)
        }

        @Test fun `8 empty or invalid output is reported, not shown as a reply`() {
            engine.tokens = 0
            val conv = core.conversations.create().id
            val ev = events(core, req(core, conv = conv))
            assertEquals("empty", ev.filterIsInstance<AgentEvent.Error>().single().kind)
            assertEquals(1, core.conversations.messages(conv).size, "nothing saved as Clu's reply")
            assertEquals(TurnEvent.INFERENCE_ERROR, lastTrace(core).terminal!!.name)
            // Only a malformed tool call: same handling.
            engine.tokens = 1; engine.text = { "<tool>{not json" }
            assertEquals("empty", events(core, req(core)).filterIsInstance<AgentEvent.Error>().single().kind)
        }

        @Test fun `11 the chat state survives the screen and never stays thinking`() = runBlocking {
            engine.tokens = 40; engine.delayMs = 10
            val chat = ChatTurns(core.agent, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            val conv = core.conversations.create().id
            // Screen 1 observes, then is destroyed mid-reply (rotation, back): only its observer is cancelled.
            val screen1 = launch(Dispatchers.Default) { chat.state.collect {} }
            chat.send(req(core, "Hi", conv), "Hi")
            withTimeout(5_000) { chat.state.first { !it.streaming.isNullOrEmpty() } }
            screen1.cancel()
            // Screen 2 opens: sees the reply still in progress, then finished and saved.
            assertTrue(chat.state.value.running)
            val st = withTimeout(5_000) { chat.state.first { !it.running } }
            assertNull(st.streaming)
            assertEquals(2, core.conversations.messages(conv).size)
            // After a stop the state is idle too.
            engine.tokens = 1000
            chat.send(req(core, "Again", conv), "Again")
            withTimeout(5_000) { chat.state.first { !it.streaming.isNullOrEmpty() } }
            chat.stop()
            assertNull(withTimeout(5_000) { chat.state.first { !it.running } }.streaming)
        }

        @Test fun `closing the reply flow cancels its generation`() = runBlocking {
            engine.tokens = 1000; engine.delayMs = 10 // 10 s if nobody stopped it
            val job = launch(Dispatchers.Default) { core.agent.respond(req(core)).collect {} }
            withTimeout(5_000) { while (engine.started.get() == 0) delay(5) }
            delay(100)
            job.cancel() // the screen that collected it went away
            val t0 = System.nanoTime()
            withTimeout(5_000) { while (engine.lastStop.isEmpty()) delay(5) }
            assertEquals("cancelled", engine.lastStop)
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 500)
            withTimeout(2_000) { while (core.agent.trace.recent().first().terminal == null) delay(5) }
            assertEquals(TurnEvent.REQUEST_CANCELLED, lastTrace(core).terminal!!.name)
        }

        @Test fun `12 generation and streaming run off the caller's thread`() = runBlocking {
            engine.tokens = 50; engine.delayMs = 20 // ~1 s of streaming
            val main = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()
            val chat = ChatTurns(core.agent, CoroutineScope(SupervisorJob() + main))
            withContext(main) { chat.send(req(core), "Hi") }
            var worst = 0L
            while (chat.state.value.running) {
                val t0 = System.nanoTime()
                withContext(main) {} // a UI frame
                worst = maxOf(worst, (System.nanoTime() - t0) / 1_000_000)
                delay(16)
            }
            assertNotEquals("fake-main", engine.lastThread)
            assertTrue(worst < 100, "main thread stayed responsive while streaming (worst ${worst}ms)")
            main.close()
        }

        @Test fun `follow-up work never delays the next reply`() = runBlocking {
            core.settings.set(Settings.LEARNING_ALTERNATIVES, true); core.settings.set(Settings.LEARNING_ALT_EVERY, 5)
            repeat(6) { core.prefs.recordReply() }
            engine.tokens = 5; engine.delayMs = 5
            engine.firstDelayFor = { call -> if (call == 2) 30_000 else 0 } // call 2 = the alternative reply: very slow
            val conv = core.conversations.create().id
            val chat = ChatTurns(core.agent, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            chat.send(req(core, "One", conv), "One")
            withTimeout(5_000) { chat.state.first { !it.running } } // reply delivered; the alternative is generated now
            withTimeout(5_000) { while (engine.started.get() < 2) delay(5) }
            assertFalse(core.agent.busy(), "follow-up work does not count as busy")
            val t0 = System.nanoTime()
            assertTrue(chat.send(req(core, "Two", conv), "Two"))
            withTimeout(5_000) { chat.state.first { !it.running && it.seq == 2L } }
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000, "second reply not stuck behind the alternative")
        }
    }

    @Nested inner class RealInference {
        private val model = File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))
        private val platform = TestPlatform()
        private val core = newCore(platform = platform)

        private fun selectRealModel(f: File = model) {
            assumeTrue(model.exists(), "tiny.gguf missing")
            core.settings.set(Settings.LEARNING_ALTERNATIVES, false)
            core.settings.set(Settings.MODEL_ACTIVE, core.models.register(f).id)
        }

        @Test fun `1 and 2 consecutive real requests complete and reuse the prompt cache`() {
            selectRealModel()
            core.settings.set(Settings.MAX_TOKENS, 24)
            val conv = core.conversations.create().id
            val reused = mutableListOf<Double>()
            repeat(5) { i ->
                val ev = events(core, req(core, "Message number $i", conv), 60_000)
                val done = ev.filterIsInstance<AgentEvent.Done>().single()
                assertTrue(ev.none { it is AgentEvent.Error }, "turn $i: ${ev.filterIsInstance<AgentEvent.Error>()}")
                assertEquals(TurnEvent.GENERATION_COMPLETED, lastTrace(core).terminal!!.name, "turn $i")
                assertTrue((done.meta.stats["tokens"] ?: 0.0) > 0)
                reused += done.meta.stats["reused_tokens"] ?: 0.0
            }
            assertEquals(10, core.conversations.messages(conv).size)
            assertEquals(LlmStatus.READY, core.llm.state.value.status)
            // System prompt + history come from the KV cache from the second message on (prefix-cache layout).
            assertTrue(reused.drop(1).all { it > 100 }, "reused tokens per turn: $reused")
        }

        @Test fun `5 cancel stops real llama_cpp generation and the model stays usable`() = runBlocking {
            selectRealModel()
            core.settings.set(Settings.MAX_TOKENS, 4096)
            core.settings.set(Settings.TEMPERATURE, 1.0)
            val got = mutableListOf<AgentEvent>()
            val job = launch(Dispatchers.Default) { core.agent.respond(req(core, "Talk")).collect { got += it } }
            withTimeout(30_000) { while (got.none { it is AgentEvent.Text }) delay(1) }
            core.agent.cancel(TurnOrigin.TEXT)
            withTimeout(5_000) { job.join() }
            val done = got.filterIsInstance<AgentEvent.Done>().single()
            assertTrue(done.meta.interrupted)
            assertTrue((done.meta.stats["tokens"] ?: 0.0) < 1500, "stopped early: ${done.meta.stats["tokens"]} tokens")
            core.settings.set(Settings.MAX_TOKENS, 8)
            assertTrue(events(core, req(core, "Again"), 30_000).filterIsInstance<AgentEvent.Done>().single().text.isNotEmpty())
        }

        @Test fun `6 a queued request cancelled does not stop the running one`() = runBlocking {
            selectRealModel()
            val e = core.llm.engine()!!
            val a = CancelSignal(); val b = CancelSignal()
            val ra = async(Dispatchers.IO) { e.generate(listOf(ChatMessage("user", "A")), GenParams(maxTokens = 300, temperature = 1f), a) {} }
            delay(20)
            val rb = async(Dispatchers.IO) { e.generate(listOf(ChatMessage("user", "B")), GenParams(maxTokens = 300, temperature = 1f), b) {} }
            b.cancel()
            assertEquals("cancelled", withTimeout(30_000) { rb.await() }.stats.stopReason)
            assertNotEquals("cancelled", withTimeout(30_000) { ra.await() }.stats.stopReason)
        }

        @Test fun `9 model init failure gives a useful error`() {
            assumeTrue(model.exists())
            // Header and tensor table intact, but the last tensor's data is cut off: passes Clu's checks, llama.cpp
            // refuses to load it.
            val min = GgufReader.read(model).minimumBytes
            val broken = File.createTempFile("broken", ".gguf").apply { deleteOnExit(); writeBytes(model.readBytes().copyOf(min.toInt() + 16)) }
            selectRealModel(broken)
            val ev = events(core, req(core), 30_000)
            val err = ev.filterIsInstance<AgentEvent.Error>().single()
            assertEquals("model", err.kind)
            assertTrue(err.text.contains("couldn't load my language model", ignoreCase = true), err.text)
            assertEquals(TurnEvent.MODEL_INIT_FAILED, lastTrace(core).terminal!!.name)
            assertEquals(ModelStatus.INCOMPATIBLE, core.models.list().single().status)
            // Not enough memory is explained, before llama.cpp is even asked.
            core.settings.set(Settings.MODEL_ACTIVE, core.models.register(model).id)
            platform.llm.available = 1_000_000
            runBlocking { core.llm.reload() }
            assertTrue(events(core, req(core)).filterIsInstance<AgentEvent.Error>().single().text.contains("Not enough free memory"))
        }

        @Test fun `10 a missing or corrupted model file is detected`() {
            assumeTrue(model.exists())
            val garbage = File.createTempFile("garbage", ".gguf").apply { deleteOnExit(); writeText("this is not a model") }
            val e = assertFailsWith<LlmException> { core.models.register(garbage) }
            assertTrue(e.message!!.contains("Not a compatible model"))
            val copy = File.createTempFile("gone", ".gguf").also { it.deleteOnExit(); model.copyTo(it, overwrite = true) }
            selectRealModel(copy)
            copy.delete()
            assertEquals(ModelStatus.MISSING, core.models.list().single().status)
            val err = events(core, req(core)).filterIsInstance<AgentEvent.Error>().single()
            assertEquals("model", err.kind)
            assertTrue(err.text.contains("missing"), err.text)
        }

        @Test fun `10b an interrupted download or copy is detected before llama_cpp sees it`() {
            assumeTrue(model.exists())
            val bytes = model.readBytes()
            val partial = File.createTempFile("partial", ".gguf").apply { deleteOnExit(); writeBytes(bytes.copyOf(bytes.size / 2)) }
            val e = assertFailsWith<LlmException> { core.models.register(partial) }
            assertTrue(e.message!!.contains("incomplete"), e.message)
            // A registered model truncated later (storage error, partial overwrite) is re-validated at load.
            val copy = File.createTempFile("later", ".gguf").also { it.deleteOnExit(); model.copyTo(it, overwrite = true) }
            selectRealModel(copy)
            java.io.RandomAccessFile(copy, "rw").use { it.setLength(copy.length() / 2) }
            val err = events(core, req(core)).filterIsInstance<AgentEvent.Error>().single()
            assertTrue(err.text.contains("damaged"), err.text)
            assertEquals(ModelStatus.INCOMPATIBLE, core.models.list().single().status)
            assertEquals(core.models.register(model).info.minimumBytes <= model.length(), true)
        }
    }
}
