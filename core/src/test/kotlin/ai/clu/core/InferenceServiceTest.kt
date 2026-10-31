package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.llm.*
import ai.clu.core.memory.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * The authoritative inference API. Two kinds of tests, never mixed:
 *  - [Mocked]: pure logic (context fitting, output guard) with no model.
 *  - [RealInference]: the real llama.cpp bridge and the tiny random-weight GGUF (native/build/test/tiny.gguf). Real
 *    loading, tokenising, sampling, cancellation, unloading; the generated text is meaningless, so these tests assert
 *    behaviour (events, states, timings), never what the model says. Tests with a model that talks sensibly are in
 *    :desktop (HostRealModelTest, opt-in).
 */
class InferenceServiceTest {

    @Nested inner class Mocked {
        private fun m(role: String, n: Int) = ChatMessage(role, "x".repeat(n))
        private val count: (List<ChatMessage>) -> Int = { l -> l.sumOf { it.content.length / 4 + 4 } }

        @Test fun `a conversation that fits is untouched`() {
            val msgs = listOf(m("system", 40), m("user", 40))
            assertEquals(ContextFitter.Fit(msgs, 0, true), ContextFitter.fit(msgs, 1000, 100, count))
        }

        @Test fun `the oldest turns go first, the system message and the newest message stay, and it starts on a user turn`() {
            val msgs = listOf(m("system", 100), m("user", 400), m("assistant", 400), m("user", 400), m("assistant", 400), m("user", 200))
            val f = ContextFitter.fit(msgs, 330, 50, count)
            assertTrue(f.fits); assertEquals("system", f.messages.first().role); assertSame(msgs.last(), f.messages.last())
            assertEquals("user", f.messages[1].role, "no reply without its question")
            assertTrue(f.dropped > 0 && count(f.messages) <= 330 - 50 - 32, "dropped ${f.dropped}, tokens ${count(f.messages)}")
        }

        @Test fun `a newest message that cannot fit is refused rather than cut`() {
            val msgs = listOf(m("system", 100), m("user", 4000))
            assertFalse(ContextFitter.fit(msgs, 330, 50, count).fits)
        }

        @Test fun `control characters are removed but text, newlines and tabs are kept`() {
            val g = OutputGuard()
            assertEquals("Hello\n\tworld", g.push("Hel\u0000lo\n\t\u0007wor\u001bld"))
            assertFalse(g.looping)
        }

        @Test fun `a reply stuck in a loop is stopped and trimmed to one copy`() {
            val g = OutputGuard(); val shown = StringBuilder()
            shown.append(g.push("The answer is simple. "))
            repeat(40) { shown.append(g.push("I do not know the answer. ")) }
            assertTrue(g.looping)
            assertEquals(1, Regex("I do not know the answer\\.").findAll(g.text()).count(), "one copy is kept: ${g.text()}")
            assertTrue(g.text().length < 200, g.text())
            assertEquals("", g.push("more"), "nothing is passed on after the loop is declared")
        }

        @Test fun `ordinary repetition is not a loop`() {
            val g = OutputGuard()
            listOf("Ha ha ha ha ha, that is funny. ", "No, no, no, no, I mean it. ", "The cat sat. The cat sat on the mat. ", "1, 2, 3, 4, 5, 6, 7, 8, 9, 10. ").forEach { g.push(it) }
            assertFalse(g.looping)
        }
    }

    @Nested inner class RealInference {
        private val tiny = File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))
        private val clock = ai.clu.core.util.ManualClock(1_760_000_000_000L)
        private val core by lazy { newCore(clock = clock).also { c -> assumeTrue(tiny.exists(), "tiny.gguf missing"); c.settings.set(Settings.MODEL_ACTIVE, c.models.register(tiny).id); c.settings.set(Settings.CONTEXT_LENGTH, 2048) } }
        private val svc get() = core.inferenceService
        private val hello = listOf(ChatMessage("system", "You are a test."), ChatMessage("user", "Hello"))
        private fun long(id: String = "long") = InferenceRequest(hello, GenParams(maxTokens = 1500, temperature = 1.0f), id = id, owner = "test")
        private fun short(id: String = "short") = InferenceRequest(hello, GenParams(maxTokens = 12), id = id, owner = "test")
        private suspend fun waitFor(ms: Long = 30_000, cond: () -> Boolean) = withTimeout(ms) { while (!cond()) delay(2) }

        @Test fun `a request streams Queued, Started, tokens and Completed, and the state follows`() = runBlocking {
            val seen = CopyOnWriteArrayList<LlmStatus>()
            val watcher = launch(Dispatchers.Unconfined) { core.llm.state.collect { if (seen.lastOrNull() != it.status) seen += it.status } }
            assertTrue(core.llm.state.value.status in listOf(LlmStatus.UNLOADED, LlmStatus.NO_MODEL), "nothing is loaded before the first request")
            val ev = svc.generate(short()).toList()
            assertEquals(listOf("Queued", "Started"), ev.take(2).map { it::class.simpleName })
            assertTrue(ev.drop(2).dropLast(1).all { it is InferenceEvent.Token } && ev.drop(2).dropLast(1).isNotEmpty(), ev.map { it::class.simpleName }.toString())
            val done = ev.last() as InferenceEvent.Completed
            assertTrue(ev.all { it.requestId == "short" })
            assertTrue(done.result.stats.generatedTokens > 0 && done.result.stats.promptTokens > 0)
            assertEquals(ev.filterIsInstance<InferenceEvent.Token>().joinToString("") { it.text }, done.result.text)
            val st = core.llm.state.value
            assertEquals(LlmStatus.READY, st.status); assertEquals("cpu", st.backend); assertTrue(st.threads > 0 && st.contextSize in 1..2048 && st.estimatedBytes > tiny.length())
            assertTrue(seen.indexOf(LlmStatus.LOADING) < seen.indexOf(LlmStatus.READY) && seen.contains(LlmStatus.GENERATING), seen.toString())
            watcher.cancel()
            Unit
        }

        @Test fun `cancelling a running request stops generation at once and leaves the model ready`() = runBlocking {
            val tokens = CopyOnWriteArrayList<String>(); var outcome: InferenceOutcome? = null
            val job = launch(Dispatchers.Default) { svc.generate(long()).collect { if (it is InferenceEvent.Token) tokens += it.text; if (it is InferenceEvent.Completed) outcome = it.outcome } }
            waitFor { tokens.size >= 3 }
            assertEquals("running", svc.snapshot().running!!.state); assertEquals("long", svc.snapshot().running!!.id)
            val t0 = System.nanoTime(); assertTrue(svc.cancel("long"))
            withTimeout(5_000) { job.join() }
            assertEquals(InferenceOutcome.CANCELLED, outcome); assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000)
            val atCancel = tokens.size
            delay(300)
            assertEquals(atCancel, tokens.size, "no text arrives after the request ended")
            assertTrue(atCancel < 1400, "stopped early: $atCancel tokens")
            assertNull(svc.snapshot().running); assertEquals(LlmStatus.READY, core.llm.state.value.status)
            assertFalse(svc.cancel("long"), "nothing left to cancel")
            // The next request is not affected by the cancelled one.
            val next = svc.complete(short("after-cancel")); assertTrue(next.text.isNotEmpty() && next.stats.generatedTokens > 0)
            Unit
        }

        @Test fun `the queue is bounded, ordered, and a queued request that is cancelled never starts`() = runBlocking {
            core.settings.set(Settings.MAX_QUEUED, 1)
            val a = async(Dispatchers.Default) { svc.generate(long("a")).toList() }
            waitFor { svc.snapshot().running?.id == "a" }
            val b = async(Dispatchers.Default) { svc.generate(short("b")).toList() }
            waitFor { svc.snapshot().queued.map { it.id } == listOf("b") }
            val c = svc.generate(short("c")).toList()
            val busy = c.single() as InferenceEvent.Failed
            assertEquals(InferenceError.BUSY, busy.error); assertTrue(busy.message.contains("working on 2 requests"))
            // Cancel the queued one: it ends without ever starting.
            assertTrue(svc.cancel("b"))
            val bEv = withTimeout(10_000) { b.await() }
            assertTrue(bEv.none { it is InferenceEvent.Started }, bEv.map { it::class.simpleName }.toString()); assertEquals(InferenceOutcome.CANCELLED, (bEv.last() as InferenceEvent.Completed).outcome)
            svc.cancel("a"); a.await()
            assertEquals(0, svc.snapshot().queued.size); assertNull(svc.snapshot().running)
            Unit
        }

        @Test fun `queued requests run one after another in submission order`() = runBlocking {
            core.settings.set(Settings.MAX_QUEUED, 4)
            val order = CopyOnWriteArrayList<String>()
            val jobs = (1..3).map { i -> async(Dispatchers.Default) { svc.generate(short("r$i")).collect { if (it is InferenceEvent.Started) order += "r$i" } }.also { waitFor { svc.snapshot().let { s -> (s.running != null) && s.queued.size + 1 >= i || i == 1 } } } }
            jobs.awaitAll()
            assertEquals(listOf("r1", "r2", "r3"), order)
            Unit
        }

        @Test fun `a collector that goes away cancels its request`() = runBlocking {
            val tokens = CopyOnWriteArrayList<String>()
            val job = launch(Dispatchers.Default) { svc.generate(long("gone")).collect { if (it is InferenceEvent.Token) tokens += it.text } }
            waitFor { tokens.size >= 2 }
            job.cancelAndJoin()
            waitFor(10_000) { svc.snapshot().running == null && core.llm.state.value.status == LlmStatus.READY }
            Unit
        }

        @Test fun `a request that runs past its limit is stopped with a clear error, not left spinning`() = runBlocking {
            core.settings.set(Settings.TOTAL_TIMEOUT, 30)
            val timed = InferenceService(core.llm, core.settings) { clock.time }   // same model service, time under the test's control
            val ev = CopyOnWriteArrayList<InferenceEvent>()
            val job = launch(Dispatchers.Default) { timed.generate(long("slow")).collect { ev += it } }
            waitFor { ev.any { it is InferenceEvent.Token } }
            clock.advance(31_000)
            withTimeout(10_000) { job.join() }
            val failed = ev.last() as InferenceEvent.Failed
            assertEquals(InferenceError.TIMEOUT_TOTAL, failed.error); assertTrue(failed.message.contains("too long"))
            assertEquals(LlmStatus.READY, core.llm.state.value.status); assertNull(timed.snapshot().running)
            assertTrue(timed.complete(short("fresh")).text.isNotEmpty(), "and the model works for the next request")
            Unit
        }

        @Test fun `unloading cancels what is running, releases the model and the next request loads it again`() = runBlocking {
            val tokens = CopyOnWriteArrayList<String>(); var outcome: InferenceOutcome? = null
            val job = launch(Dispatchers.Default) { svc.generate(long("u")).collect { if (it is InferenceEvent.Token) tokens += it.text; if (it is InferenceEvent.Completed) outcome = it.outcome } }
            waitFor { tokens.size >= 2 }
            val st = svc.unload()
            withTimeout(5_000) { job.join() }
            assertEquals(LlmStatus.UNLOADED, st.status); assertEquals(InferenceOutcome.CANCELLED, outcome)
            val loadSeq = core.llm.state.value.loadSeq
            val again = svc.complete(short("back")); assertTrue(again.text.isNotEmpty())
            assertEquals(LlmStatus.READY, core.llm.state.value.status); assertTrue(core.llm.state.value.loadSeq > loadSeq)
            Unit
        }

        @Test fun `a model that cannot be loaded gives a clear error and loads once the file is fixed`() = runBlocking {
            val copy = File.createTempFile("clu-broken", ".gguf").also { it.deleteOnExit(); tiny.copyTo(it, overwrite = true) }
            core.settings.set(Settings.MODEL_ACTIVE, core.models.register(copy).id)
            copy.writeBytes(copy.readBytes().copyOf(1_000_000))       // truncated, as an interrupted copy would leave it
            val ev = svc.generate(short("bad")).toList()
            val failed = ev.last() as InferenceEvent.Failed
            assertEquals(InferenceError.LOAD_FAILED, failed.error); assertTrue(failed.message.isNotBlank(), failed.message)
            assertEquals(LlmStatus.ERROR, core.llm.state.value.status)
            tiny.copyTo(copy, overwrite = true)                        // the file is repaired
            val ok = svc.complete(short("good")); assertTrue(ok.text.isNotEmpty()); assertEquals(LlmStatus.READY, core.llm.state.value.status)
            Unit
        }

        @Test fun `a conversation too long for the context is shortened from the oldest end and reported`() = runBlocking {
            core.settings.set(Settings.CONTEXT_LENGTH, 1024)
            val big = listOf(ChatMessage("system", "You are a test.")) + (1..30).flatMap { listOf(ChatMessage("user", "Question number $it about many different things. ".repeat(6)), ChatMessage("assistant", "Answer number $it, with some detail. ".repeat(6))) } + ChatMessage("user", "Final question?")
            val r = svc.complete(InferenceRequest(big, GenParams(maxTokens = 16)))
            assertTrue(r.droppedMessages > 0, "dropped ${r.droppedMessages}"); assertTrue(r.stats.promptTokens < 1024 - 16)
            val impossible = svc.generate(InferenceRequest(listOf(ChatMessage("user", "word ".repeat(4000))), GenParams(maxTokens = 16))).toList()
            assertEquals(InferenceError.CONTEXT_TOO_LARGE, (impossible.last() as InferenceEvent.Failed).error)
            Unit
        }

        @Test fun `many concurrent loads leave exactly one model in memory`() = runBlocking {
            val engines = (1..8).map { async(Dispatchers.Default) { core.llm.loadIfNeeded() } }.awaitAll()
            assertEquals(1, engines.filterNotNull().map { System.identityHashCode(it) }.distinct().size)
            assertEquals(1L, core.llm.state.value.loadSeq)
            Unit
        }

        @Test fun `the chat agent uses the same service, and retrieved memories reach it`() = runBlocking {
            core.settings.set(Settings.MAX_TOKENS, 12)
            val m = core.memory.remember(Memory(category = MemoryCategory.CORE_FACT, title = "Sister", content = "My sister's name is Mira and she lives in Pune.", source = MemorySource.OWNER_SUPPLIED)).memory
            val conv = core.conversations.create().id
            val owner = core.auth.actorForSpeaker(null, false)!!
            val ev = core.agent.respond(TurnRequest(conv, "Which city does Mira live in? Tell me about my sister.", owner)).toList()
            assertTrue(ev.none { it is AgentEvent.Error }, ev.filterIsInstance<AgentEvent.Error>().toString())
            assertTrue(ev.any { it is AgentEvent.Done })
            assertTrue(core.memory.meta(m.id)!!.accessCount >= 1, "the memory was retrieved for the real model's prompt")
            assertEquals(LlmStatus.READY, core.llm.state.value.status)
            Unit
        }
    }
}
