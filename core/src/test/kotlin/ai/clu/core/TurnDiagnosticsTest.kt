package ai.clu.core

import ai.clu.core.agent.*
import ai.clu.core.config.Settings
import ai.clu.core.diag.PerfReport
import ai.clu.core.llm.*
import ai.clu.core.security.Actor
import ai.clu.core.voice.TurnTimings
import ai.clu.core.voice.VoiceTurnTimeline
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Stage 3.4: each reply's prompt processing is attributed to a cause, and each voice turn gets a numbers-only stage
 * timeline linked to its request number. JVM only, simulated model cache.
 */
class TurnDiagnosticsTest {
    private val core = newCore()
    private val owner = Actor.ownerInApp()

    /** Behaves like llama.cpp's cache for statistics: holds the last prompt + reply tokens, reuses the common prefix. */
    private class CacheEngine(private val ctx: Int = 4096) : LlmEngine {
        override val id = "cache"; override val source = "local"
        var cache = emptyList<String>()
        override fun isReady() = true
        private fun toks(m: List<ChatMessage>) = m.flatMap { msg -> listOf("<${msg.role}>") + msg.content.split(' ') }
        override fun countTokens(messages: List<ChatMessage>) = toks(messages).size
        override fun contextSize() = ctx
        override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult {
            val t = toks(messages)
            val reused = cache.zip(t).takeWhile { (a, b) -> a == b }.size
            val reply = if (params.maxTokens == 0) emptyList() else listOf("Fine", "thanks.")
            val st = GenStats(promptTokens = t.size, reusedTokens = reused, cachedTokens = cache.size, generatedTokens = reply.size,
                promptMs = (t.size - reused) * 18.0, firstTokenMs = (t.size - reused) * 18.0 + 5, stopReason = if (reply.isEmpty()) "max_tokens" else "eog")
            cache = t + reply
            reply.forEach { onText("$it ") }
            return GenResult(reply.joinToString(" "), st, source)
        }
        override fun cancel() {}
    }

    private fun causes() = core.diag.perfSummary().map { it.metric }.filter { it.startsWith("turn_prefill_") }

    @Test fun promptProcessingIsAttributedToItsCause(): Unit = runBlocking {
        val engine = CacheEngine().also { core.useEngine(it) }
        val conv = core.conversations.create().id
        core.agent.warmUp()
        core.agent.respond(TurnRequest(conv, "first question", owner, viaVoice = true)).toList()
        assertEquals(listOf("turn_prefill_after_load_ms"), causes(), "first request after loading")
        core.agent.respond(TurnRequest(conv, "second question", owner, viaVoice = true)).toList()
        assertTrue("turn_prefill_steady_ms" in causes())
        engine.cache = listOf("<system>", "something", "else") // another use of the model in between (diary, test, …)
        core.agent.respond(TurnRequest(conv, "third question", owner, viaVoice = true)).toList()
        assertTrue("turn_prefill_cache_replaced_ms" in causes(), "${causes()}")
        engine.cache = emptyList()
        core.agent.respond(TurnRequest(conv, "fourth question", owner, viaVoice = true)).toList()
        assertTrue("turn_prefill_cold_ms" in causes(), "${causes()}")
        assertTrue(core.diag.perfSummary().any { it.metric == "turn_new_tokens_steady" }, "token counts per cause")
        // The reply's statistics carry what the voice timeline needs, linked by request number.
        val done = core.agent.respond(TurnRequest(conv, "fifth question", owner, viaVoice = true)).toList().filterIsInstance<AgentEvent.Done>().single()
        val st = done.meta.stats
        assertEquals(PrefillCause.STEADY.ordinal.toDouble(), st["prefill_cause"])
        for (k in listOf("request_id", "model_ready_ms", "prompt_build_ms", "queue_ms", "prompt_ms", "prompt_tokens", "reused_tokens", "cached_tokens")) assertNotNull(st[k], k)
    }

    @Test fun aCutHistoryIsReportedAsRetrim(): Unit = runBlocking {
        CacheEngine(ctx = 1400).also { core.useEngine(it) }
        core.settings.set(Settings.MAX_TOKENS, 64)
        val conv = core.conversations.create().id
        repeat(40) { i -> core.agent.respond(TurnRequest(conv, "question $i " + "about the weekend plans ".repeat(8), owner)).toList() }
        val by = core.diag.perfSummary().associateBy { it.metric }
        val retrims = by["turn_prefill_retrim_ms"]?.count ?: 0
        val steady = by["turn_prefill_steady_ms"]?.count ?: 0
        assertTrue(retrims in 1..10 && steady > retrims, "retrim $retrims, steady $steady: ${by.keys}")
        assertTrue(by.getValue("turn_new_tokens_retrim").p50 > by.getValue("turn_new_tokens_steady").p50, "a cut costs more than a normal turn")
    }

    @Test fun voiceTurnTimelineHasStagesAndNoText() {
        val t = TurnTimings(1).apply {
            transcriptFinal = 10_858; firstToken = 13_900; firstTtsRequest = 14_190; firstAudio = 16_000; firstChunkChars = 42
            chunkSynthMs += 1_700
        }
        val stats = mapOf("request_id" to 7.0, "model_ready_ms" to 2.0, "prompt_build_ms" to 15.0, "queue_ms" to 0.0, "prompt_ms" to 3_000.0,
            "prefill_cause" to PrefillCause.STEADY.ordinal.toDouble(), "prompt_tokens" to 2109.0, "reused_tokens" to 1935.0)
        val line = VoiceTurnTimeline.line(3, endpoint = 10_000, accepted = 10_860, t = t, stats = stats, causes = PrefillCause.entries.map { it.key }, outcome = "completed")
        assertEquals("voice turn 3 · request #7 · completed | asr 858 · accept 2 · model ready 2 · build 15 · queue 0 · prefill 3000 (steady, new 174 of 2109) · " +
            "first word 3042 · tts request 290 · synth 1700 (42 chars) · audible 110 | end of speech → audio 6000", line)
        val m = t.metrics()
        assertEquals(1700.0, m["first_chunk_synth_ms"]); assertEquals(110.0, m["first_audio_after_synth_ms"]); assertEquals(42.0, m["first_chunk_chars"])
    }

    @Test fun reportListsRecentTurnsAndKeepsTheTimelineOutOfTheSummary() {
        core.diag.perf(PerfReport.VOICE_TIMELINE, 6000.0, "voice turn 1 · request #1 · completed | asr 858")
        core.diag.perf("turn_new_tokens_steady", 174.0)
        val report = PerfReport.build("1.5.5", "test", mapOf("context" to "4096 tokens"), null, core.diag.perfSummary(), 0L,
            core.diag.perfDetails(PerfReport.VOICE_TIMELINE, 15))
        assertTrue("- voice turn 1 · request #1 · completed | asr 858" in report, report)
        assertFalse(report.lines().any { it.startsWith("- ${PerfReport.VOICE_TIMELINE}") }, "not as a summary row")
        assertTrue(report.lines().any { it.startsWith("- turn_new_tokens_steady: p50 174 tokens") }, report)
        assertTrue("context=4096 tokens" in report)
    }
}
