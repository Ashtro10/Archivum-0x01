package ai.clu.core

import ai.clu.core.agents.*
import ai.clu.core.config.Settings
import ai.clu.core.llm.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Agents against the real shared inference service and the real llama.cpp engine (the tiny random-weight model: its text is
 * meaningless, so these tests check the machinery, not the quality: that planning is grammar-constrained, that reasoning steps
 * and chat share one model, and that nothing hangs or crashes when the model is useless).
 */
class AgentIntegrationTest {
    private val lib = File(System.getProperty("clu.nativeLib", "../native/build/host/libclu_llama.so"))
    private val tiny = File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))

    private fun realCore(): CluCore {
        assumeTrue(lib.exists() && tiny.exists(), "native test artifacts missing")
        val core = newCore()
        val m = core.models.register(tiny); core.settings.set(Settings.MODEL_ACTIVE, m.id); core.settings.set(Settings.CONTEXT_LENGTH, 2048)
        return core
    }

    /** Records what the model actually produced, in front of the real reasoner. */
    private class Tap(val inner: Reasoner) : Reasoner by inner {
        val raw = java.util.concurrent.CopyOnWriteArrayList<ReasonResult>(); val requestIds = java.util.concurrent.CopyOnWriteArrayList<String>()
        override suspend fun reason(role: AgentRole, system: String, user: String, maxTokens: Int, requestId: String, grammar: String?, temperature: Float): ReasonResult {
            requestIds += requestId; return inner.reason(role, system, user, maxTokens, requestId, grammar, temperature).also { raw += it }
        }
    }

    @Test fun thePlannerOutputIsGrammarConstrainedJsonWithRealToolNamesEvenFromAUselessModel() = runBlocking {
        val core = realCore(); val tap = Tap(InferenceReasoner(core.inferenceService))
        val planner = Planner(core.agentTools, tap, { 6 }, { core.agentSwitches.enabled(it) }) { "(workspace)" }
        var valid = 0; var refused = 0; var attempts = 0
        // The model has random weights and fills every string it may, so some replies hit the token limit before the closing brace (that is a refused plan, handled below);
        // the ones that finish must be valid JSON with only real tool names. Ask until two have finished.
        fun finished() = tap.raw.count { it.text.trimEnd().endsWith("}") }
        while (finished() < 2 && attempts < 12) {
            try { val p = withTimeout(120_000) { planner.plan("look at the status of CLU number $attempts", "it-$attempts") }; valid++; assertTrue(p.steps.isNotEmpty() && p.steps.size <= 6) }
            catch (e: PlanProblem) { refused++; assertTrue(e.message!!.isNotBlank()) }
            attempts++
        }
        assertTrue(tap.raw.isNotEmpty() && tap.raw.all { it.error == null }, "every planning request went through the shared service and completed: ${tap.raw.map { it.error }}")
        assertTrue(finished() >= 2, "at least two replies were complete within $attempts attempts")
        for (r in tap.raw.filter { it.text.trimEnd().endsWith("}") }) {
            val json = try { Json.parseToJsonElement(r.text).jsonObject } catch (e: Exception) { fail("not JSON (${r.text.length} chars): ${r.text.take(700).replace("\n", "\\n")} ... ${e.message}") }  // complete replies always parse: the grammar guarantees the structure
            val steps = json["steps"]!!.jsonArray; assertTrue(steps.size in 1..6)
            for (s in steps) { val o = s.jsonObject; val tool = o["tool"]?.jsonPrimitive?.content; if (tool != null) assertTrue(tool in core.agentTools.ids() && core.agentSwitches.enabled(tool), "the model could only name real, switched-on tools: $tool") }
        }
        println("[agents-real] planner: ${tap.raw.size} model replies (${finished()} complete), $valid valid plans, $refused refused (random weights: refusal is fine, a crash or a hang is not)")
    }

    @Test fun aReasoningStepRunsOnTheOneSharedModelAlongsideChat() = runBlocking {
        val core = realCore(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val svc = AgentService(core.db, core.clock, core.settings, core.agentTools, core.agentAudit, core.agentBroker, InferenceReasoner(core.inferenceService), scope, core.agentSwitches)
            val t0 = svc.create("tell me about the weather", """{"steps":[{"id":"s1","reason":"knowledge","instruction":"Say one short sentence."}]}""", autostart = true, synthesize = false)
            // while the agent's request is being served, an ordinary chat request goes to the same service and also completes
            val chat = async(Dispatchers.Default) { core.inferenceService.complete(InferenceRequest(listOf(ChatMessage("user", "Hi")), GenParams(maxTokens = 8, temperature = 0f), owner = "chat")) }
            val end = System.currentTimeMillis() + 120_000
            while (svc.get(t0.id)!!.status.let { !it.terminal } && System.currentTimeMillis() < end) delay(50)
            val t = svc.get(t0.id)!!
            assertEquals(StepStatus.DONE, t.steps.single().status, t.steps.single().error); assertTrue(t.steps.single().output!!.jsonObject["text"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(chat.await().stats.generatedTokens > 0, "chat was served by the same model at the same time")
            assertEquals(1, core.models.list().size, "one model, one service: the agents created no second model")
            assertEquals(LlmStatus.READY, core.inferenceService.state.value.status)
            assertTrue(core.agentAudit.list(taskId = t0.id, limit = 100).any { it.kind == "step.finished" && it.actor == "knowledge" })
        } finally { scope.cancel() }
    }

    @Test fun cancellingAnAgentRequestStopsTheRealGenerationAndLeavesTheModelFree() = runBlocking {
        val core = realCore(); val reasoner = InferenceReasoner(core.inferenceService)
        core.settings.set(Settings.FIRST_TOKEN_TIMEOUT, 600)
        val started = System.currentTimeMillis()
        val running = async(Dispatchers.Default) { reasoner.reason(AgentRole.KNOWLEDGE, "You write long stories.", "Write a very long story.", 1500, "cancel-me", grammar = "root ::= [a-z ]{3000}", temperature = 1.0f) }
        val end = System.currentTimeMillis() + 60_000
        while (core.inferenceService.snapshot().running?.id != "cancel-me" && !running.isCompleted && System.currentTimeMillis() < end) delay(2)
        if (running.isCompleted) fail("the request ended by itself before it could be cancelled: ${running.await()}")
        assertEquals("cancel-me", core.inferenceService.snapshot().running?.id, "the agent's request is the one running on the shared model; its owner is ${core.inferenceService.snapshot().running?.owner}")
        assertEquals("agent:knowledge", core.inferenceService.snapshot().running!!.owner)
        reasoner.cancel("cancel-me")
        val r = withTimeout(30_000) { running.await() }
        assertTrue(System.currentTimeMillis() - started < 60_000, "stopped promptly instead of generating all 1500 tokens")
        println("[agents-real] cancelled generation returned: error=${r.error} chars=${r.text.length}")
        val ok = withTimeout(60_000) { core.inferenceService.complete(InferenceRequest(listOf(ChatMessage("user", "Hi")), GenParams(maxTokens = 4, temperature = 0f), owner = "chat")) }
        assertTrue(ok.stats.generatedTokens > 0, "the model is free for the next request after a cancelled one")
        assertTrue(core.inferenceService.snapshot().let { it.running == null && it.queued.isEmpty() })
    }

    @Test fun withNoModelTasksFailClearlyAndTheRestOfCluKeepsWorking() = runBlocking {
        val core = newCore(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val svc = AgentService(core.db, core.clock, core.settings, core.agentTools, core.agentAudit, core.agentBroker, InferenceReasoner(core.inferenceService), scope, core.agentSwitches)
            val t0 = svc.create("find something out")
            val end = System.currentTimeMillis() + 10_000
            while (!svc.get(t0.id)!!.status.terminal && System.currentTimeMillis() < end) delay(10)
            val t = svc.get(t0.id)!!; assertEquals(TaskStatus.FAILED, t.status); assertTrue(t.error.contains("language model"), t.error)
            val explicit = svc.create("status", """{"steps":[{"id":"s1","tool":"system.status","args":{}}]}""", autostart = true, synthesize = false)
            while (!svc.get(explicit.id)!!.status.terminal && System.currentTimeMillis() < end) delay(10)
            assertEquals(TaskStatus.COMPLETED, svc.get(explicit.id)!!.status, "tasks that need no model still run")
        } finally { scope.cancel() }
    }
}
