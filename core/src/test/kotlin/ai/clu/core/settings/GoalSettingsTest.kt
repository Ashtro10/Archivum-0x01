package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.security.Actor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.jupiter.api.Test

class GoalSettingsTest {
    /** agent.understanding: "model" plans with the model (one grammar-constrained call); "rules" never calls the planner. */
    @Test fun understanding() {
        val core = newCore(); val conv = core.conversations.create().id
        val plan = """{"kind":"chat","goal":"greeting","steps":[]}"""
        val m = ScriptedEngine(grammar = true).reply(plan, "Hello.")
        core.useEngine(m)
        assertEquals("model", core.settings.string(Settings.UNDERSTANDING))
        runBlocking { core.agent.respond(TurnRequest(conv, "good evening, how was your day", Actor.ownerInApp())).toList() }
        assertNotNull(m.params.first().grammar, "the planner ran with a grammar")
        core.settings.set(Settings.UNDERSTANDING, "rules")
        val n = m.params.size
        runBlocking { core.agent.respond(TurnRequest(conv, "and how are you now", Actor.ownerInApp())).toList() }
        assertTrue(m.params.drop(n).none { it.grammar != null }, "no planning call with rules")
    }
}
