package ai.clu.core

import ai.clu.core.agent.*
import ai.clu.core.config.Settings
import ai.clu.core.security.Sensitivity
import ai.clu.core.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Stage 2 task planning: detection, real per-step results, confirmation stops, cancellation, resume. */
class TaskPlanTest {
    private val platform = TestPlatform()
    private val core = newCore(platform = platform)
    private val engine = ScriptedEngine().also { core.useEngine(it) }
    private val conv = core.conversations.create().id
    private val owner = core.auth.actorForSpeaker(null, false)!!

    /** Stand-ins for the Android tools (core tests have no Android): they report what they did, or that they failed. */
    private class FakeTool(override val name: String, private val result: ToolResult, override val confirmByDefault: Boolean = false,
                           private val delayMs: Long = 0) : Tool {
        override val description = name; override val argsExample = "{}"; override val sensitivity = Sensitivity.ROUTINE
        override val deviceAction = true
        var runs = 0
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { runs++; if (delayMs > 0) delay(delayMs); return result }
    }
    private val timer = FakeTool("timer.set", ToolResult.ok("Timer set for 10 minutes.", spoken = "Timer set for 10 minutes."))
    private val app = FakeTool("app.open", ToolResult(false, false, "Spotify isn't installed on this device.", error = "Spotify isn't installed on this device."))
    private val alarm = FakeTool("alarm.set", ToolResult.ok("Alarm set for 6:00.", spoken = "Alarm set for 6:00."), confirmByDefault = true)
    init { listOf(timer, app, alarm).forEach { core.toolRegistry.register(it) } }

    private fun ask(t: String) = runBlocking { core.agent.respond(TurnRequest(conv, t, owner)).toList() }
    private fun done(ev: List<AgentEvent>) = ev.filterIsInstance<AgentEvent.Done>().single()

    @Test fun recognisesMultiStepRequestsOnly() {
        assertEquals(listOf("Set a timer for 10 minutes", "Look up the weather in Pune", "Open Spotify"),
            TaskPlanner.plan("Set a timer for 10 minutes, then look up the weather in Pune and open Spotify"))
        assertEquals(listOf("Remember that the plumber comes Friday", "Explain what a P-trap is"),
            TaskPlanner.plan("1. Remember that the plumber comes Friday\n2. Explain what a P-trap is"))
        listOf("I went to the market and then came home", "Search for salt and pepper prices", "What's the difference between a list and a set?",
            "Tell me a story about a fox and a crow", "Hello Clu", "Open Spotify").forEach { assertNull(TaskPlanner.plan(it), it) }
        assertEquals(5, TaskPlanner.plan("open a, open b, open c, open d, open e, open f")!!.size)
        assertEquals(1, TaskPlanner.overflow("open a, open b, open c, open d, open e, open f"))
        assertTrue(TaskPlanner.isResume("continue the task") && TaskPlanner.isResume("Clu, finish the remaining steps") && !TaskPlanner.isResume("continue the story"))
    }

    @Test fun eachStepReportsWhatReallyHappened() {
        engine.reply("A P-trap is the U-shaped pipe under a sink that holds water to block sewer gas.")
        val ev = ask("Set a timer for 10 minutes, then open Spotify and explain what a P-trap is")
        val d = done(ev)
        assertTrue(d.text.startsWith("Plan (3 steps):\n✓ 1. Set a timer for 10 minutes — Timer set for 10 minutes.\n" +
            "✗ 2. Open Spotify — Spotify isn't installed on this device.\n• 3. Explain what a P-trap is — answered below"), d.text)
        assertTrue(d.text.endsWith("A P-trap is the U-shaped pipe under a sink that holds water to block sewer gas."))
        assertEquals(1, timer.runs); assertEquals(1, app.runs)
        val sent = engine.prompts.last().last().content
        assertTrue(sent.startsWith("[Task]") && sent.contains("Step 3: Explain what a P-trap is") && sent.contains("Do not describe actions as done unless"))
        val progress = ev.filterIsInstance<AgentEvent.TaskProgress>()
        assertTrue(progress.size >= 4 && progress.last().steps.map { it.status } == listOf(StepStatus.DONE, StepStatus.FAILED, StepStatus.ANSWER))
        assertTrue(ev.filterIsInstance<AgentEvent.Status>().any { it.text == "Step 2 of 3: Open Spotify" }, "long tasks say which step is running")
    }

    @Test fun actionsOnlyNeedNoModelAndLookupsUseSearch() {
        val noModel = newCore(platform = TestPlatform()).also { c -> listOf(FakeTool("timer.set", ToolResult.ok("Timer set for 5 minutes.")),
            FakeTool("app.open", ToolResult.ok("Opened Notes."))).forEach { c.toolRegistry.register(it) } }
        val d = runBlocking { noModel.agent.respond(TurnRequest(noModel.conversations.create().id, "Set a timer for 5 minutes and open Notes", owner)).toList() }
            .filterIsInstance<AgentEvent.Done>().single()
        assertEquals("Plan (2 steps):\n✓ 1. Set a timer for 5 minutes — Timer set for 5 minutes.\n✓ 2. Open Notes — Opened Notes.", d.text)
        // A lookup step searches (mocked provider) and its sources are cited under the report.
        core.settings.set(Settings.SEARCH_PROVIDER, "searxng"); core.settings.set(Settings.SEARXNG_URL, "https://searx.example.org")
        core.settings.set(Settings.SEARCH_READ_PAGES, false)
        platform.fakeHttp.routes["searx.example.org"] = HttpResponse(200, """{"results":[{"url":"https://imd.example/pune","title":"Pune forecast","content":"Rain likely today"}]}""")
        engine.reply("Rain is likely in Pune today [1].")
        val r = done(ask("Set a timer for 10 minutes and then look up the weather in Pune today"))
        assertTrue(r.text.contains("✓ 2. Look up the weather in Pune today — searched, 1 source — answer below") && r.text.endsWith("Rain is likely in Pune today."), r.text)
        assertEquals("https://imd.example/pune", r.meta.sources.single().url)
    }

    @Test fun unconfirmedActionsStopThePlanAndCanBeResumed() {
        val ev = ask("Set an alarm for 6:00, then set a timer for 10 minutes")
        val d = done(ev)
        assertTrue(d.text.contains("! 1. Set an alarm for 6:00 — not confirmed, so not done") && d.text.contains("– 2. Set a timer for 10 minutes"), d.text)
        assertTrue(d.text.contains("Waiting for you: Set an alarm for 6:00. Say \"continue the task\" when ready."))
        assertEquals(0, alarm.runs); assertEquals(0, timer.runs, "later steps don't run after an action that needed you")
        // The owner confirms this time: "continue the task" picks up where it stopped.
        core.tools.confirmation = ConfirmationHandler { _, _, _ -> true }
        val r = done(ask("continue the task"))
        assertTrue(r.text.contains("✓ 1. Set an alarm for 6:00 — Alarm set for 6:00.") && r.text.contains("✓ 2. Set a timer for 10 minutes — Timer set for 10 minutes."), r.text)
        assertEquals(1, alarm.runs); assertEquals(1, timer.runs)
        assertNull(core.agent.activeTask(conv), "nothing left to resume")
        assertTrue(done(ask("continue the task")).text.startsWith("I don't have a recent unfinished task"))
    }

    @Test fun cancellingSkipsTheRemainingSteps(): Unit = runBlocking {
        val slow = FakeTool("timer.set", ToolResult.ok("Timer set."), delayMs = 800)
        core.toolRegistry.register(slow)
        val events = async(Dispatchers.Default) { core.agent.respond(TurnRequest(conv, "Set a timer for 1 minute, then open Spotify", owner)).toList() }
        delay(300); core.agent.cancel(null)
        val d = events.await().filterIsInstance<AgentEvent.Done>().single()
        assertTrue(d.text.contains("– 2. Open Spotify") && d.text.contains("Stopped: the remaining steps were not started."), d.text)
        assertEquals(0, app.runs, "no further work after cancelling")
        assertTrue(d.meta.interrupted)
    }
}
