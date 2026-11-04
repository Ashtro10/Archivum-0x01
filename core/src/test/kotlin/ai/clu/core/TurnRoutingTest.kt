package ai.clu.core

import ai.clu.core.agent.IntentRouter
import ai.clu.core.agent.ResearchPolicy
import ai.clu.core.agent.HazardPolicy
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * The cheapest correct path for everyday requests (measured 2026-11-04 with Qwen3-0.6B: "What is a robotic element?"
 * and "What is the weather?" went to web search, which failed offline instead of answering).
 */
class TurnRoutingTest {
    private val now = 1_793_800_000_000L
    private fun research(t: String) = ResearchPolicy.decide(t, now).kind == ResearchPolicy.Kind.RESEARCH

    @Test fun conceptsAreExplainedNotResearched() {
        listOf("What is a robotic element?", "What is a black hole?", "What is machine learning?", "Explain how a transistor works", "What is a neural network?")
            .forEach { assertFalse(research(it), it); assertNull(IntentRouter.route(it)?.takeIf { r -> r.tool == "web.search" }, it) }
    }

    @Test fun namedAndCurrentSubjectsAreStillResearched() {
        listOf("Who is Satya Nadella?", "What is the latest news about SpaceX?", "What is OpenAI's newest model?").forEach { assertTrue(research(it), it) }
    }

    @Test fun weatherGoesToTheWeatherService() {
        listOf("What is the weather?", "What's the weather like today?", "How's the weather in Pune?", "Will it rain tomorrow?")
            .forEach { assertEquals("WX", HazardPolicy.detect(it)?.type, it) }
    }

    @Test fun smallTalkNeedsNoResearch() {
        listOf("Hello", "How are you today?", "Thanks, that's all.", "Good morning CLU").forEach { assertFalse(research(it), it); assertNull(HazardPolicy.detect(it), it) }
    }

    @Test fun closingAnExchangeIsNotAnAnswerToAPendingQuestion() {
        val state = ai.clu.core.agent.ConversationState(turn = 3, pending = ai.clu.core.agent.Pending("location", "What is the weather?", emptyList(), turn = 3, at = now))
        listOf("Thanks, that's all.", "Never mind", "No thanks", "OK thanks", "That's it").forEach { t ->
            val r = ai.clu.core.agent.FollowUpResolver.resolve(t, state, false, now)
            assertTrue(r.resolution is ai.clu.core.agent.Resolution.Standalone, "$t -> ${r.resolution}") }
        assertFalse(ai.clu.core.agent.FollowUpResolver.resolve("Pune", state, false, now).resolution is ai.clu.core.agent.Resolution.Standalone, "a city still answers it")
    }
}
