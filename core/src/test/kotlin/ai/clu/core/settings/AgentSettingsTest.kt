package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.agents.*
import ai.clu.core.config.Settings
import ai.clu.core.config.SettingResult
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Each assistant-task setting changes what the system does (these are the tests the settings register points to). */
class AgentSettingsTest {
    private class Env { val core = newCore(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val ws: File = Files.createTempDirectory("clu-set").toFile().canonicalFile
        val reasoner = ScriptedReasoner()
        val svc = AgentService(core.db, core.clock, core.settings, core.agentTools, core.agentAudit, core.agentBroker, reasoner, scope, core.agentSwitches, tick = 5)
        init { core.agentRoots.add(ws, true) }
        fun done() { scope.cancel(); ws.deleteRecursively() }
        fun wait(id: String, ms: Long = 10_000, c: (AgentTask) -> Boolean = { it.status.terminal }) { val e = System.currentTimeMillis() + ms; while (!c(svc.get(id)!!)) { assertTrue(System.currentTimeMillis() < e, "timeout ${svc.get(id)!!.status}"); Thread.sleep(10) } }
        fun plan(vararg s: String) = """{"steps":[${s.joinToString(",")}]}"""
    }
    private fun env(f: (Env) -> Any?) { val e = Env(); try { f(e) } finally { e.done() } }

    @Test fun enabled() = env { e ->
        assertTrue(e.core.settings.bool(Settings.AGENTS_ENABLED))
        e.core.settings.set(Settings.AGENTS_ENABLED, false)
        assertFailsWith<IllegalStateException> { e.svc.create("anything", e.plan("""{"id":"s1","tool":"system.status","args":{}}""")) }
        e.core.settings.set(Settings.AGENTS_ENABLED, true); assertNotNull(e.svc.create("again", e.plan("""{"id":"s1","tool":"system.status","args":{}}""")))
    }

    @Test fun reviewPlan() = env { e ->
        e.reasoner.answer = { c -> if (c.role == AgentRole.PLANNER) """{"summary":"s","steps":[{"id":"s1","tool":"system.status","args":{}}]}""" else "ok" }
        e.core.settings.set(Settings.AGENTS_REVIEW_PLAN, true)
        val a = e.svc.create("one"); e.wait(a.id) { it.status == TaskStatus.AWAITING_START }; Thread.sleep(150); assertEquals(TaskStatus.AWAITING_START, e.svc.get(a.id)!!.status, "waits for the owner")
        e.core.settings.set(Settings.AGENTS_REVIEW_PLAN, false)
        val b = e.svc.create("two"); e.wait(b.id); assertEquals(TaskStatus.COMPLETED, e.svc.get(b.id)!!.status, "runs on its own when review is off")
    }

    @Test fun maxSteps() = env { e ->
        val s = (1..5).map { """{"id":"s$it","tool":"fs.list","args":{"path":"d$it"}}""" }.toTypedArray()
        e.core.settings.set(Settings.AGENTS_MAX_STEPS, 4); assertFailsWith<PlanProblem> { e.svc.create("g", e.plan(*s)) }
        e.core.settings.set(Settings.AGENTS_MAX_STEPS, 5); assertNotNull(e.svc.create("g", e.plan(*s)))
        assertEquals(2, Settings.all.first { it.key == "agents.maxSteps" }.let { (it.type as ai.clu.core.config.SettingType.IntRange).min })
    }

    @Test fun maxParallel() = env { e ->
        val now = AtomicInteger(); val peak = AtomicInteger()
        e.core.agentTools.register(TestTool("test.p", Category.A) { _, _ -> val n = now.incrementAndGet(); peak.updateAndGet { maxOf(it, n) }; delay(100); now.decrementAndGet(); obj("text" to "x") })
        val steps = (1..4).map { """{"id":"s$it","tool":"test.p","args":{"n":$it}}""" }.toTypedArray()
        e.core.settings.set(Settings.AGENTS_MAX_PARALLEL, 1); val a = e.svc.create("g", e.plan(*steps), autostart = true, synthesize = false); e.wait(a.id); assertEquals(1, peak.get())
        peak.set(0); e.core.settings.set(Settings.AGENTS_MAX_PARALLEL, 3); val b = e.svc.create("g", e.plan(*steps), autostart = true, synthesize = false); e.wait(b.id); assertEquals(3, peak.get())
    }

    @Test fun maxTasks() = env { e ->
        val gate = CompletableDeferred<Unit>(); e.core.agentTools.register(TestTool("test.g", Category.A) { _, _ -> gate.await(); obj("text" to "x") })
        e.core.settings.set(Settings.AGENTS_MAX_TASKS, 1)
        val a = e.svc.create("g", e.plan("""{"id":"s1","tool":"test.g","args":{}}"""), autostart = true); e.wait(a.id) { it.status == TaskStatus.RUNNING }
        val b = e.svc.create("g", e.plan("""{"id":"s1","tool":"system.status","args":{}}"""), autostart = true); Thread.sleep(150); assertEquals(TaskStatus.QUEUED, e.svc.get(b.id)!!.status)
        e.core.settings.set(Settings.AGENTS_MAX_TASKS, 2); e.wait(b.id); assertEquals(TaskStatus.COMPLETED, e.svc.get(b.id)!!.status); gate.complete(Unit); e.wait(a.id)
    }

    @Test fun taskTimeout() = env { e ->
        val d = Settings.all.first { it.key == "agents.taskTimeoutMin" }; assertEquals(120, d.default.jsonPrimitive.int)
        assertTrue(e.core.settings.set(Settings.AGENTS_TASK_TIMEOUT, 1) is SettingResult.Invalid, "a limit shorter than five minutes is refused")
        assertTrue(e.core.settings.set(Settings.AGENTS_TASK_TIMEOUT, 5) !is SettingResult.Invalid); assertEquals(5, e.core.settings.int(Settings.AGENTS_TASK_TIMEOUT))
        val a = e.svc.create("g", e.plan("""{"id":"s1","tool":"system.status","args":{}}"""), autostart = true); e.wait(a.id); assertEquals(TaskStatus.COMPLETED, e.svc.get(a.id)!!.status)
    }

    @Test fun shellAllowlist() = env { e ->
        assertEquals(emptyList(), e.core.settings.list(Settings.AGENTS_SHELL_ALLOWLIST), "empty by default: no program may run")
        e.svc.setToolEnabled("proc.run", true)
        val args = """{"exe":"/bin/echo","args":["x"]}"""
        assertFailsWith<PlanProblem> { e.svc.create("g", e.plan("""{"id":"s1","tool":"proc.run","args":$args}""")) }
        e.core.settings.set(Settings.AGENTS_SHELL_ALLOWLIST, listOf("/bin/echo")); assertNotNull(e.svc.create("g", e.plan("""{"id":"s1","tool":"proc.run","args":$args}""")))
    }
}
