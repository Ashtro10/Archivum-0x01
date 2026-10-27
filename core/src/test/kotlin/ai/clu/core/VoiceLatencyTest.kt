package ai.clu.core

import ai.clu.core.agent.*
import ai.clu.core.config.Settings
import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.llm.*
import ai.clu.core.security.Actor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Stage 3.3: prompt-cache stability in long conversations and honest classification of latency samples. JVM only. */
class VoiceLatencyTest {
    private val core = newCore()
    private val owner = Actor.ownerInApp()

    @Test fun aLongConversationKeepsTheSameHistoryStartForManyTurns(): Unit = runBlocking {
        val engine = ScriptedEngine(ctx = 2000).also { core.useEngine(it) }
        core.settings.set(Settings.MAX_TOKENS, 64)
        val conv = core.conversations.create().id
        repeat(45) { i ->
            engine.reply("Reply $i: " + "the weather this weekend looks mild with light wind ".repeat(4))
            core.agent.respond(TurnRequest(conv, "question $i about the weekend", owner, viaVoice = true)).toList()
        }
        val budget = 2000 - 64 - 32
        // The first message after the system prompt in each prompt: where the kept history starts.
        val starts = engine.prompts.map { p -> p[1].content }
        val overflowed = engine.prompts.drop(1).indexOfFirst { p -> !p[1].content.startsWith("question 0 ") } + 1
        assertTrue(overflowed in 1..40, "the conversation outgrew the window (first trimmed prompt: $overflowed)")
        val moves = starts.drop(overflowed).zipWithNext().count { (a, b) -> a != b }
        val turnsAfter = starts.size - overflowed
        // Before Stage 3.3 the start moved on every turn once the window was full (moves == turnsAfter - 1).
        assertTrue(moves <= turnsAfter / 4, "history start moved $moves times in $turnsAfter turns: ${starts.drop(overflowed)}")
        engine.prompts.forEach { p ->
            assertTrue(engine.countTokens(p) <= budget, "every prompt fits the window")
            assertEquals("user", p[1].role, "the kept history starts with the owner's message")
        }
        assertTrue(engine.prompts.last().any { it.content.startsWith("Reply 43:") }, "the newest exchange is always kept")
    }

    @Test fun aShortConversationIsNeverTrimmed(): Unit = runBlocking {
        val engine = ScriptedEngine().also { core.useEngine(it) }
        val conv = core.conversations.create().id
        repeat(4) { i -> engine.reply("Answer $i."); core.agent.respond(TurnRequest(conv, "q$i", owner)).toList() }
        assertEquals(1 + 6 + 1, engine.prompts.last().size, "system + all 6 earlier messages + the new one")
    }

    @Test fun samplesTakenWhileTheAppWasHiddenAreKeptApart() {
        val d = core.diag
        val visible = d.visibilityMark()
        d.perfSpan("model_load_ms", 3000.0, visible)
        val spanning = d.visibilityMark()
        d.setForeground(false); d.setForeground(true)
        d.perfSpan("model_load_ms", 3_442_057.0, spanning)
        d.setForeground(false)
        val hidden = d.visibilityMark()
        d.setForeground(true)
        d.perfSpan("model_load_ms", 115_052.0, hidden)
        val by = d.perfSummary().associateBy { it.metric }
        assertEquals(1, by.getValue("model_load_ms").count)
        assertEquals(3000.0, by.getValue("model_load_ms").max)
        assertEquals(2, by.getValue("model_load_background_ms").count, "kept, not deleted")
        assertEquals("turn_total_timeout_ms", DiagnosticsLog.classified("turn_total_ms", "timeout"))
        assertEquals("tokens_per_s_short", DiagnosticsLog.classified("tokens_per_s", "short"))
    }

    /** Emits one word, then waits until it is cancelled (Stop, barge-in). */
    private class HangingEngine : LlmEngine {
        override val id = "hang"; override val source = "local"
        override fun isReady() = true
        override fun countTokens(messages: List<ChatMessage>) = messages.sumOf { it.content.length / 4 + 4 }
        override fun contextSize() = 4096
        override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit) = error("signal variant only")
        override fun generate(messages: List<ChatMessage>, params: GenParams, signal: CancelSignal, onText: (String) -> Unit): GenResult {
            onText("Well")
            while (!signal.isCancelled) Thread.sleep(5)
            return GenResult("Well", GenStats(stopReason = "cancelled"), source)
        }
        override fun cancel() {}
    }

    @Test fun stoppedRequestsAreRecordedSeparatelyWithTheirRequestNumber(): Unit = runBlocking {
        core.useEngine(HangingEngine())
        val conv = core.conversations.create().id
        val job = async(Dispatchers.Default) { core.agent.respond(TurnRequest(conv, "tell me a long story", owner, viaVoice = true)).toList() }
        delay(300); core.agent.cancel(null)
        job.await()
        val metrics = core.diag.perfSummary().map { it.metric }
        assertTrue("turn_total_cancelled_ms" in metrics, "$metrics")
        assertFalse("turn_total_ms" in metrics, "a stopped request is not a normal completion time: $metrics")
        assertTrue("turn_first_token_cancelled_ms" in metrics)
    }

    @Test fun completedRequestsStayUnderTheirUsualNames(): Unit = runBlocking {
        ScriptedEngine().reply("Hello.").also { core.useEngine(it) }
        core.agent.respond(TurnRequest(core.conversations.create().id, "hi", owner)).toList()
        val metrics = core.diag.perfSummary().map { it.metric }
        assertTrue("turn_total_ms" in metrics && "turn_first_token_ms" in metrics, "$metrics")
        assertTrue(metrics.none { it.contains("cancelled") || it.contains("background") }, "$metrics")
    }

    // Barge-in with the production settings (VoiceSession: 180 ms, 10 dB over the echo floor, 30 ms frames). Unchanged in
    // Stage 3.3; these pin its behaviour in the situations the stage asked about.
    private fun detector() = ai.clu.core.voice.BargeInDetector(minSpeechMs = 180, marginDb = 10.0)
    private fun feed(d: ai.clu.core.voice.BargeInDetector, frames: Int, vad: Boolean, db: Double, t0: Long): Int? {
        for (i in 0 until frames) if (d.onFrame(vad, db, 30, t0 + i * 30L)) return i
        return null
    }

    @Test fun bargeInSustainedSpeechOverCluIsCaughtAfter180ms() {
        val d = detector()
        assertNull(feed(d, 40, vad = false, db = -42.0, t0 = 0))           // Clu speaking: echo floor learned
        assertNull(feed(d, 20, vad = true, db = -38.0, t0 = 1200))          // echo the VAD calls speech: ignored
        assertEquals(5, feed(d, 20, vad = true, db = -24.0, t0 = 1800))     // owner: 6th frame = 180 ms
    }

    @Test fun bargeInShortWordsWithGapsDoNotInterruptButContinuedSpeechDoes() {
        val d = detector()
        feed(d, 40, vad = false, db = -42.0, t0 = 0)
        assertNull(feed(d, 4, vad = true, db = -24.0, t0 = 1200))           // "mm" (120 ms)
        assertNull(feed(d, 2, vad = false, db = -42.0, t0 = 1320))          // pause
        assertNull(feed(d, 4, vad = true, db = -24.0, t0 = 1380))           // "ok" (120 ms)
        assertNotNull(feed(d, 10, vad = true, db = -24.0, t0 = 1500))       // then real speech
    }

    @Test fun bargeInLoudNoiseThatIsNotSpeechNeverInterrupts() {
        val d = detector()
        assertNull(feed(d, 100, vad = false, db = -15.0, t0 = 0))
    }

    @Test fun bargeInWorksFromTheFirstFramesOfPlayback() {
        // No echo floor yet (playback just started): the owner can still interrupt after 180 ms of speech.
        assertEquals(5, feed(detector(), 10, vad = true, db = -24.0, t0 = 0))
    }
}
