package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.persona.AnswerDepth
import ai.clu.core.persona.AnswerDepth.Depth
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.jupiter.api.Test

/** Phase 5: how much answer a message asks for, and what the model is told per message (offline wording included). */
class AnswerDepthTest {
    @Test fun explanationsBriefRequestsAndEverythingElse() {
        for (q in listOf("Explain how a refrigerator keeps food cold.", "Why are there two high tides a day and not one?", "How does HTTPS keep my data private?",
            "What's the difference between a process and a thread?", "My SQL query got slow once the table reached a few million rows. What should I check first?",
            "Can you walk me through how a mortgage works?", "Compare SSDs and hard drives for a home server."))
            assertEquals(Depth.EXPLAIN, AnswerDepth.of(q), q)
        for (q in listOf("Briefly, why is the sky blue?", "In one sentence, what is entropy?", "Is Pune bigger than Nashik? yes or no"))
            assertEquals(Depth.BRIEF, AnswerDepth.of(q), q)
        for (q in listOf("What's 2 + 2?", "How are you?", "How much does a litre of milk weigh?", "How many legs does a spider have?", "Hi", "remind me to call mom at 7",
            "How do you say thank you in Hindi?".replace("How do you say", "Say")))
            assertNull(AnswerDepth.of(q), q)
    }

    private fun prompt(text: String, online: Boolean = true, length: String? = null): String = runBlocking {
        val platform = TestPlatform().apply { this.online = online }
        val core = newCore(platform = platform)
        length?.let { core.settings.set(Settings.RESPONSE_LENGTH, it) }
        val engine = ScriptedEngine().reply("ok"); core.useEngine(engine)
        core.agent.respond(TurnRequest(core.conversations.create("t").id, text, ai.clu.core.security.Actor.ownerInApp())).toList().filterIsInstance<AgentEvent.Done>()
        engine.prompts.joinToString("\n") { m -> m.joinToString("\n") { it.content } }
    }

    @Test fun theModelIsToldWhatTheMessageAsksFor() {
        assertContains(prompt("Explain how a refrigerator keeps food cold."), "This question asks for an explanation")
        assertContains(prompt("Briefly, how does a fridge work?"), "reply in one or two sentences")
        assertFalse(prompt("Hi there").contains("asks for an explanation"))
        // The owner's "brief" setting wins.
        assertFalse(prompt("Explain how a refrigerator keeps food cold.", length = "brief").contains("asks for an explanation"))
    }

    @Test fun offlineOnlyLiveInformationIsOutOfReach() {
        val p = prompt("Explain how a refrigerator keeps food cold.", online = false)
        assertContains(p, "you cannot get live information")
        assertContains(p, "Answer everything else from your own knowledge")
    }
}
