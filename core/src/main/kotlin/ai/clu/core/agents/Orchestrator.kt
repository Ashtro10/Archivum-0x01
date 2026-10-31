package ai.clu.core.agents

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.db.SqlDriver
import ai.clu.core.db.SqlRow
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.util.Clock
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class TaskStatus(val terminal: Boolean, val label: String) {
    PLANNING(false, "Planning"), AWAITING_START(false, "Ready to start"), QUEUED(false, "Waiting for a free slot"), RUNNING(false, "Running"),
    WAITING_APPROVAL(false, "Waiting for you"), PAUSED(false, "Paused"), COMPLETED(true, "Done"), PARTIAL(true, "Partly done"), FAILED(true, "Failed"), CANCELLED(true, "Cancelled"),
}
enum class StepStatus(val terminal: Boolean) { PENDING(false), RUNNING(false), WAITING_APPROVAL(false), DONE(true), FAILED(true), SKIPPED(true), REFUSED(true), CANCELLED(true) }

data class AgentStep(val id: String, val taskId: String, val idx: Int, val role: AgentRole, val kind: StepKind, val toolId: String, val args: JsonObject, val depends: List<String>, val description: String,
                     val status: StepStatus, val attempts: Int, val output: JsonObject?, val error: String, val flagged: List<String>, val destructive: Boolean, val startedAt: Long?, val finishedAt: Long?)

data class AgentTask(val id: String, val goal: String, val status: TaskStatus, val origin: String, val summary: String, val result: String, val error: String, val conversationId: String?,
                     val createdAt: Long, val updatedAt: Long, val startedAt: Long?, val finishedAt: Long?, val note: String, val steps: List<AgentStep>)

/** Lets the host hold tasks back while the PC is short of memory (the desktop governor); null = go ahead. */
fun interface ResourceGate { fun blockedReason(): String? }

/**
 * The task orchestrator. A goal becomes a task: planned (or given as explicit steps), shown to the owner, then carried out step by
 * step by role agents that only ever act through the [ExecutionManager] and the [PermissionBroker].
 *
 * Lifecycle: create -> plan (planner role, grammar-constrained, validated) -> review (owner starts it, or approves its actions)
 * -> run (dependencies respected, bounded parallelism, mutating steps one at a time) -> verify (read-only checks of what changed)
 * -> recover (bounded retries of read-only steps; refusals and failures end a step, never loop) -> synthesise the result ->
 * close (permissions of the task revoked, everything recorded).
 *
 * What keeps it bounded: step and task limits, a time limit, no step can create tasks or agents, a plan is made once from the goal
 * alone (text read during the task can change what is written, never what is done), identical calls are refused, and a restart pauses
 * everything instead of repeating anything.
 */
class AgentService(
    private val db: SqlDriver, private val clock: Clock, private val settings: SettingsStore, val registry: AgentToolRegistry, val audit: AuditLog, val broker: PermissionBroker,
    private val reasoner: Reasoner, private val scope: CoroutineScope, val switches: ToolSwitches, @Volatile var gate: ResourceGate = ResourceGate { null }, private val tick: Long = 50L,
) {
    val exec = ExecutionManager(registry, broker, audit, clock)
    val roots get() = broker.roots

    private class Runner(val task: String) {
        @Volatile var job: Job? = null
        val pause = AtomicBoolean(false); val cancelled = AtomicBoolean(false)
        val requests = ConcurrentHashMap.newKeySet<String>()
        val outputs = ConcurrentHashMap<String, String>()
        val callCounts = ConcurrentHashMap<String, AtomicInteger>()
        @Volatile var cancelledBy = "owner"
    }
    private val runners = ConcurrentHashMap<String, Runner>()
    private val runningTasks = AtomicInteger(); private val runningSteps = AtomicInteger()
    private val planner = Planner(registry, reasoner, { settings.int(Settings.AGENTS_MAX_STEPS) }, ::toolEnabled) { roots.workspace()?.path ?: "(none yet)" }

    fun toolEnabled(id: String) = switches.enabled(id)
    fun disabledTools() = switches.disabled()
    fun setToolEnabled(id: String, on: Boolean, by: String = "owner") = switches.set(id, on, by)

    // ------------------------------------------------------------------------------------------ creating tasks
    /** Starts a task from a goal (planned by the model) or from explicit steps ([planJson], same format as a plan). Returns at once; planning and running happen in the background. */
    fun create(goal: String, planJson: String? = null, origin: String = "owner", conversationId: String? = null, autostart: Boolean = false, synthesize: Boolean? = null): AgentTask {
        if (!settings.bool(Settings.AGENTS_ENABLED)) throw IllegalStateException("Assistant tasks are switched off in Settings.")
        val g = goal.trim(); require(g.isNotEmpty()) { "A task needs a goal." }; require(g.length <= 2000) { "The goal is too long." }
        val plan = planJson?.let { PlanValidator.parse(it, registry, settings.int(Settings.AGENTS_MAX_STEPS), ::toolEnabled) }
        val id = newId(); val now = clock.now()
        db.execute("INSERT INTO agent_tasks(id, goal, status, origin, plan, created_at, updated_at, conversation_id, meta) VALUES(?,?,?,?,?,?,?,?,?)",
            listOf(id, g, (if (plan != null) TaskStatus.AWAITING_START else TaskStatus.PLANNING).name, origin, planJson ?: "", now, now, conversationId, CluJson.encodeToString(JsonObject.serializer(), obj("synthesize" to (synthesize ?: (plan == null))))))
        audit.record("task.created", id, origin, "", mapOf("goal" to g, "explicit" to (plan != null), "origin" to origin))
        runners[id] = Runner(id)
        if (plan != null) { store(id, plan); if (autostart) startInternal(id) }
        else launchPlanning(id, autostart)
        return get(id)!!
    }

    private fun launchPlanning(id: String, autostart: Boolean) {
        val r = runners.getOrPut(id) { Runner(id) }
        r.job = scope.launch {
            try {
                audit.record("task.plan.requested", id, "planner", "", emptyMap())
                reasoner.unavailable()?.let { fail(id, "Planning needs a language model. $it", "task.plan.failed"); return@launch }
                val plan = planner.plan(get(id)!!.goal, "task-$id")
                store(id, plan)
                db.execute("UPDATE agent_tasks SET plan = ?, summary = ?, status = ?, updated_at = ? WHERE id = ?", listOf(planToJson(plan), plan.summary, TaskStatus.AWAITING_START.name, clock.now(), id))
                audit.record("task.plan.created", id, "planner", "", mapOf("summary" to plan.summary, "steps" to plan.steps.map { "${it.id}:${if (it.kind == StepKind.TOOL) it.toolId else "reason/" + it.role.id}" }))
                plan.steps.forEach { audit.record("step.assigned", id, "orchestrator", it.id, mapOf("role" to it.role.id, "tool" to it.toolId)) }
                if (autostart || !settings.bool(Settings.AGENTS_REVIEW_PLAN)) startInternal(id)
            } catch (e: PlanProblem) { fail(id, e.message ?: "The plan was not valid.", "task.plan.failed")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { fail(id, "Planning failed: ${e.message}", "task.plan.failed") }
        }
    }

    private fun planToJson(p: Plan): String = CluJson.encodeToString(JsonObject.serializer(), obj("summary" to p.summary, "steps" to JsonArray(p.steps.map { s ->
        buildJsonObject { put("id", s.id); if (s.kind == StepKind.TOOL) { put("tool", s.toolId); put("args", s.args) } else { put("reason", s.role.id); put("instruction", s.note) }; if (s.after.isNotEmpty()) put("after", JsonArray(s.after.map(::JsonPrimitive))) } })))

    private fun store(taskId: String, plan: Plan) {
        db.transaction {
            db.execute("DELETE FROM agent_steps WHERE task_id = ?", listOf(taskId))
            plan.steps.forEachIndexed { i, s ->
                val t = if (s.kind == StepKind.TOOL) registry[s.toolId] else null
                db.execute("INSERT INTO agent_steps(id, task_id, idx, role, kind, tool_id, args, depends, description, status, destructive) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    listOf(s.id, taskId, i, s.role.id, s.kind.name, s.toolId, CluJson.encodeToString(JsonObject.serializer(), s.args), CluJson.encodeToString(JsonArray.serializer(), JsonArray(s.after.map(::JsonPrimitive))),
                        s.note.ifBlank { t?.let { runCatching { it.intent(literal(it, s.args)).summary }.getOrNull() } ?: "" }, StepStatus.PENDING.name, if (t?.spec?.mutates == true) 1 else 0))
            }
        }
    }

    private fun literal(t: AgentTool, args: JsonObject) = JsonObject(args.mapValues { (k, v) -> if (k in t.spec.dataFields && v is JsonPrimitive && v.isString && PlanValidator.templateRefs(v.content).isNotEmpty()) JsonPrimitive("(result of an earlier step)") else v })

    // ------------------------------------------------------------------------------------------ control
    /** The owner starts a task that is ready. [approveActions] also approves the plan's category-B actions (never category C) so those do not ask one by one. */
    fun start(taskId: String, approveActions: Boolean = false, by: String = "owner"): Result<AgentTask> {
        val t = get(taskId) ?: return Result.failure(IllegalArgumentException("no such task"))
        if (t.status != TaskStatus.AWAITING_START) return Result.failure(IllegalStateException("This task is ${t.status.label.lowercase()}, not waiting to start."))
        if (approveActions) {
            val actions = t.steps.filter { it.kind == StepKind.TOOL }.mapNotNull { s ->
                val tool = registry[s.toolId] ?: return@mapNotNull null; val lit = literal(tool, s.args)
                runCatching { PlannedAction(s.id, s.toolId, tool.spec.scope, tool.classify(lit), tool.resources(lit)) }.getOrNull() }
            broker.approvePlan(taskId, actions, by)
            audit.record("task.plan.approved", taskId, by, "", mapOf("actions" to actions.filter { it.category == Category.B }.size, "confirmationsStillNeeded" to actions.count { it.category == Category.C }))
        }
        audit.record("task.start-requested", taskId, by, "", emptyMap())
        startInternal(taskId); return Result.success(get(taskId)!!)
    }

    private fun startInternal(taskId: String) {
        val r = runners.getOrPut(taskId) { Runner(taskId) }
        setStatus(taskId, TaskStatus.QUEUED)
        r.pause.set(false)
        r.job = scope.launch { runTask(r) }
    }

    fun pause(taskId: String, by: String = "owner"): Boolean { val r = runners[taskId] ?: return false; val t = get(taskId) ?: return false
        if (t.status.terminal || t.status == TaskStatus.PAUSED) return false
        r.pause.set(true); audit.record("task.pause-requested", taskId, by, "", emptyMap())
        if (t.status == TaskStatus.QUEUED) { r.job?.cancel(); setStatus(taskId, TaskStatus.PAUSED) }
        return true }

    fun resume(taskId: String, by: String = "owner"): Result<AgentTask> {
        val t = get(taskId) ?: return Result.failure(IllegalArgumentException("no such task"))
        if (t.status != TaskStatus.PAUSED) return Result.failure(IllegalStateException("This task is ${t.status.label.lowercase()}, not paused."))
        if (!settings.bool(Settings.AGENTS_ENABLED)) return Result.failure(IllegalStateException("Assistant tasks are switched off in Settings."))
        audit.record("task.resumed", taskId, by, "", emptyMap())
        if (t.steps.isEmpty()) { setStatus(taskId, TaskStatus.PLANNING); launchPlanning(taskId, autostart = false) } else startInternal(taskId)
        return Result.success(get(taskId)!!)
    }

    fun cancel(taskId: String, by: String = "owner"): Boolean {
        val t = get(taskId) ?: return false
        if (t.status.terminal) return false
        val r = runners.getOrPut(taskId) { Runner(taskId) }
        r.cancelled.set(true); r.cancelledBy = by
        r.requests.forEach(reasoner::cancel)
        broker.cancelPending(taskId, "the task was cancelled"); broker.revokeForTask(taskId, "the task was cancelled")
        audit.record("task.cancelled", taskId, by, "", emptyMap())
        r.job?.cancel(CancellationException("cancelled by $by"))
        finishCancelled(taskId) // idempotent: also covers a task that is only planning, queued or paused
        return true
    }

    /** The owner refuses one waiting action (the same as pressing Reject); the task goes on without it. */
    fun rejectPending(taskId: String, by: String = "owner"): Int = broker.approvals(taskId = taskId, status = ApprovalStatus.PENDING).count { broker.decide(it.id, false, by = by).isSuccess }

    /** Pauses everything that was running when CLU stopped. Nothing is repeated on its own; read-only steps that were running may run again when the owner resumes, changing steps are marked failed (check the result, then run again). */
    fun recoverAfterRestart() {
        val interrupted = db.query("SELECT id, status FROM agent_tasks WHERE status IN ('PLANNING','QUEUED','RUNNING','WAITING_APPROVAL')") { it.str(0) to it.str(1) }
        broker.cancelAllPendingAfterRestart()
        for ((id, st) in interrupted) {
            val now = clock.now()
            db.execute("UPDATE agent_steps SET status = 'FAILED', error = 'CLU stopped while this step was changing something. Check the result yourself before running the task again.', finished_at = ? WHERE task_id = ? AND status IN ('RUNNING','WAITING_APPROVAL') AND destructive = 1", listOf(now, id))
            db.execute("UPDATE agent_steps SET status = 'PENDING' WHERE task_id = ? AND status IN ('RUNNING','WAITING_APPROVAL')", listOf(id))
            setStatus(id, TaskStatus.PAUSED, note = "CLU was restarted while this task was ${st.lowercase().replace('_', ' ')}. It is paused; nothing was repeated.")
            broker.revokeForTask(id, "CLU was restarted")
            audit.record("task.recovered", id, "system", "", mapOf("was" to st))
        }
    }

    // ------------------------------------------------------------------------------------------ running
    private suspend fun runTask(r: Runner) {
        val id = r.task
        try {
            while (runningTasks.get() >= settings.int(Settings.AGENTS_MAX_TASKS) && !r.cancelled.get() && !r.pause.get()) delay(200)
            runningTasks.incrementAndGet()
            try {
                if (r.pause.get()) { setStatus(id, TaskStatus.PAUSED); return }
                db.execute("UPDATE agent_tasks SET started_at = COALESCE(started_at, ?) WHERE id = ?", listOf(clock.now(), id))
                setStatus(id, TaskStatus.RUNNING); audit.record("task.started", id, "orchestrator", "", emptyMap())
                r.outputs.clear(); get(id)!!.steps.filter { it.status == StepStatus.DONE }.forEach { s -> s.output?.str("text")?.let { r.outputs[s.id] = it } }
                val finished = withTimeoutOrNull(settings.int(Settings.AGENTS_TASK_TIMEOUT) * 60_000L) { drive(r); true }
                if (finished == null) { broker.cancelPending(id, "the task timed out"); r.requests.forEach(reasoner::cancel); fail(id, "The task ran longer than the limit and was stopped.", "task.timeout"); return }
                if (r.pause.get() && !r.cancelled.get()) { setStatus(id, TaskStatus.PAUSED); audit.record("task.paused", id, "orchestrator", "", emptyMap()); return }
                finish(r)
            } finally { runningTasks.decrementAndGet() }
        } catch (e: CancellationException) { if (r.cancelled.get()) finishCancelled(id) else throw e
        } catch (e: Exception) { fail(id, "The task stopped because of an error: ${e.message}", "task.error") }
    }

    private suspend fun drive(r: Runner) = coroutineScope {
        val id = r.task; val done = Channel<Unit>(Channel.UNLIMITED); val running = ConcurrentHashMap<String, Job>(); val totalCalls = AtomicInteger()
        val maxCalls = settings.int(Settings.AGENTS_MAX_STEPS) * 3
        var waitedForGate = 0L
        while (true) {
            if (r.cancelled.get()) throw CancellationException("cancelled")
            val steps = get(id)!!.steps
            // A step whose prerequisite did not complete cannot run.
            steps.filter { it.status == StepStatus.PENDING && it.depends.any { d -> steps.firstOrNull { x -> x.id == d }?.status?.let { s -> s.terminal && s != StepStatus.DONE } == true } }
                .forEach { setStep(id, it.id, StepStatus.SKIPPED, error = "A step it needs did not complete."); audit.record("step.skipped", id, "orchestrator", it.id, emptyMap()) }
            val fresh = get(id)!!.steps
            val ready = fresh.filter { it.status == StepStatus.PENDING && it.depends.all { d -> fresh.firstOrNull { x -> x.id == d }?.status == StepStatus.DONE } }
            val active = fresh.count { it.status == StepStatus.RUNNING || it.status == StepStatus.WAITING_APPROVAL }
            if (ready.isEmpty() && active == 0) break
            if (!r.pause.get()) {
                val blocked = gate.blockedReason()
                if (blocked != null && ready.isNotEmpty() && active == 0) {
                    setStatus(id, TaskStatus.RUNNING, note = "Waiting: $blocked"); delay(1000); waitedForGate += 1000
                    if (waitedForGate > 10 * 60_000L) { fail(id, "The PC stayed short of memory for 10 minutes, so the task was stopped: $blocked", "task.resource"); throw CancellationException("resource") }
                    continue
                }
                if (waitedForGate > 0) { waitedForGate = 0; setStatus(id, TaskStatus.RUNNING, note = "") }
                for (s in ready) {
                    if (running.containsKey(s.id)) continue
                    val mutatingNow = fresh.any { (it.status == StepStatus.RUNNING || it.status == StepStatus.WAITING_APPROVAL) && it.destructive }
                    if (mutatingNow) break
                    if (s.destructive && running.isNotEmpty()) break
                    if (runningSteps.get() >= settings.int(Settings.AGENTS_MAX_PARALLEL) && running.isNotEmpty()) break
                    if (totalCalls.incrementAndGet() > maxCalls) { setStep(id, s.id, StepStatus.FAILED, error = "The task reached its limit of $maxCalls step runs."); audit.record("task.limit", id, "orchestrator", s.id, mapOf("limit" to maxCalls)); continue }
                    runningSteps.incrementAndGet()
                    running[s.id] = launch { try { runStep(r, s) } finally { runningSteps.decrementAndGet(); running.remove(s.id); done.trySend(Unit) } }
                    if (s.destructive) break
                }
            } else if (active == 0) break
            withTimeoutOrNull(1000) { done.receive() }
            delay(tick)
        }
    }

    private suspend fun runStep(r: Runner, s0: AgentStep) {
        val id = r.task
        val s = s0.copy(attempts = s0.attempts + 1)
        db.execute("UPDATE agent_steps SET status = 'RUNNING', attempts = ?, started_at = COALESCE(started_at, ?), error = '' WHERE task_id = ? AND id = ?", listOf(s.attempts, clock.now(), id, s.id))
        audit.record("step.started", id, s.role.id, s.id, mapOf("kind" to s.kind.name, "tool" to s.toolId, "attempt" to s.attempts))
        try {
            if (s.kind == StepKind.TOOL) runToolStep(r, s) else runReasonStep(r, s)
        } catch (e: CancellationException) { if (get(id)?.steps?.firstOrNull { it.id == s.id }?.status?.terminal != true) setStep(id, s.id, StepStatus.CANCELLED, error = "Cancelled"); throw e
        } catch (e: Exception) { setStep(id, s.id, StepStatus.FAILED, error = e.message ?: "failed"); audit.record("step.finished", id, s.role.id, s.id, mapOf("ok" to false, "error" to (e.message ?: ""))) }
    }

    private suspend fun runToolStep(r: Runner, s: AgentStep) {
        val id = r.task; val tool = registry[s.toolId]
        if (tool == null) { setStep(id, s.id, StepStatus.FAILED, error = "The tool ${s.toolId} is not available."); return }
        // Fill in earlier results, only where the tool allows data to flow in.
        val args = JsonObject(s.args.mapValues { (k, v) -> if (k in tool.spec.dataFields && v is JsonPrimitive && v.isString) JsonPrimitive(PlanValidator.resolve(v.content, r.outputs, 20_000)) else v })
        val key = PermissionBroker.callKey(s.toolId, args)
        if (r.callCounts.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet() > 2) { setStep(id, s.id, StepStatus.FAILED, error = "The same call was already made twice in this task, so it was not made again."); audit.record("task.loop-prevented", id, "orchestrator", s.id, mapOf("tool" to s.toolId)); return }
        var attempt = 0
        while (true) {
            attempt++
            val outcome = exec.invoke(ToolCall(id, s.id, s.role, s.toolId, args, s.description.ifBlank { get(id)?.goal.orEmpty() }) { r.cancelled.get() },
                onWaiting = { a -> setStep(id, s.id, StepStatus.WAITING_APPROVAL); setStatus(id, TaskStatus.WAITING_APPROVAL, note = "Waiting for your approval: ${a.intent}") },
                onResumed = { if (!r.cancelled.get()) { setStep(id, s.id, StepStatus.RUNNING); setStatus(id, TaskStatus.RUNNING, note = "") } })
            when (outcome) {
                is ToolOutcome.Ok -> {
                    val verified = verify(r, s, tool, args, outcome.output)
                    if (verified != null) { setStep(id, s.id, StepStatus.FAILED, output = outcome.output, error = "It ran, but checking the result failed: $verified", flagged = outcome.flagged); audit.record("step.finished", id, s.role.id, s.id, mapOf("ok" to false, "verification" to verified)); return }
                    r.outputs[s.id] = outcome.output.str("text").orEmpty()
                    setStep(id, s.id, StepStatus.DONE, output = outcome.output, flagged = outcome.flagged)
                    audit.record("step.finished", id, s.role.id, s.id, mapOf("ok" to true, "verified" to (tool.verify(args, outcome.output) != null), "flagged" to outcome.flagged.size)); return
                }
                is ToolOutcome.Refused -> { setStep(id, s.id, StepStatus.REFUSED, error = outcome.message); audit.record("step.finished", id, s.role.id, s.id, mapOf("ok" to false, "refused" to outcome.code)); return }
                ToolOutcome.Cancelled -> { setStep(id, s.id, StepStatus.CANCELLED, error = "Cancelled"); return }
                is ToolOutcome.Failed -> {
                    // A read-only step that timed out or hit a transient error is tried once more; a step that changes things is never repeated on its own.
                    if (!tool.spec.mutates && attempt < 2 && outcome.code in setOf("TIMEOUT", "ERROR", "UNAVAILABLE")) { audit.record("step.retry", id, "orchestrator", s.id, mapOf("code" to outcome.code)); delay(500); continue }
                    setStep(id, s.id, StepStatus.FAILED, error = outcome.message); audit.record("step.finished", id, s.role.id, s.id, mapOf("ok" to false, "code" to outcome.code)); return
                }
            }
        }
    }

    /** The Verifier role: after a change, a read-only look at whether it really happened. Null = verified (or nothing to verify); otherwise what is wrong. */
    private suspend fun verify(r: Runner, s: AgentStep, tool: AgentTool, args: JsonObject, output: JsonObject): String? {
        val plan = tool.verify(args, output) ?: return null
        val o = exec.invoke(ToolCall(r.task, s.id + "-verify", AgentRole.VERIFIER, plan.tool, plan.args, "Check that ${plan.describe}") { r.cancelled.get() })
        return when (o) { is ToolOutcome.Ok -> if (plan.expect(o.output)) null else "expected ${plan.describe}, found: ${o.output.str("text")}"; is ToolOutcome.Failed -> o.message; is ToolOutcome.Refused -> o.message; ToolOutcome.Cancelled -> "cancelled" }
    }

    private suspend fun runReasonStep(r: Runner, s: AgentStep) {
        val id = r.task
        reasoner.unavailable()?.let { setStep(id, s.id, StepStatus.FAILED, error = it); return }
        val task = get(id)!!
        val sources = (if (s.depends.isEmpty()) task.steps.filter { it.status == StepStatus.DONE && it.idx < s.idx } else task.steps.filter { it.id in s.depends })
        val evidence = evidence(sources)
        val system = (Roles.instructions[s.role] ?: "") + "\n\n" + Untrusted.RULES
        val user = "Task: ${task.goal}\nInstruction: ${s.description}\n\nResults of earlier steps:\n$evidence"
        var last = ""
        for (attempt in 1..2) {
            val rid = "step-${s.taskId}-${s.id}-$attempt"; r.requests += rid
            val res = try { reasoner.reason(s.role, system, user, 500, rid) } finally { r.requests -= rid }
            if (r.cancelled.get()) throw CancellationException("cancelled")
            if (res.error == null && res.text.isNotBlank()) {
                r.outputs[s.id] = res.text
                setStep(id, s.id, StepStatus.DONE, output = obj("text" to res.text)); audit.record("step.finished", id, s.role.id, s.id, mapOf("ok" to true, "chars" to res.text.length)); return
            }
            last = res.error ?: "The model returned no text."
            if (res.code == "BUSY") delay(2000) else if (res.code == "NO_MODEL" || res.code == "LOAD_FAILED") break
        }
        setStep(id, s.id, StepStatus.FAILED, error = last); audit.record("step.finished", id, s.role.id, s.id, mapOf("ok" to false, "error" to last))
    }

    private fun evidence(steps: List<AgentStep>): String {
        val per = (Roles.MAX_EVIDENCE_CHARS / steps.size.coerceAtLeast(1)).coerceAtLeast(500)
        return steps.joinToString("\n\n") { st ->
            val text = st.output?.str("text").orEmpty()
            val untrusted = st.kind == StepKind.TOOL && registry[st.toolId]?.spec?.untrustedOutput == true
            "Step ${st.id} (${if (st.kind == StepKind.TOOL) st.toolId else st.role.id}):\n" + if (untrusted || st.kind == StepKind.REASON) Untrusted.wrap("${st.id} ${st.toolId}".trim(), text, per) else text.take(per) }
    }

    // ------------------------------------------------------------------------------------------ finishing
    private suspend fun finish(r: Runner) {
        val id = r.task; val t = get(id)!!
        val done = t.steps.filter { it.status == StepStatus.DONE }
        val status = when { t.steps.isNotEmpty() && done.size == t.steps.size -> TaskStatus.COMPLETED; done.isNotEmpty() -> TaskStatus.PARTIAL; else -> TaskStatus.FAILED }
        var result = ""
        val wantSynthesis = meta(id).getBoolean("synthesize") ?: true
        val lastStep = t.steps.lastOrNull()
        if (lastStep != null && lastStep.kind == StepKind.REASON && lastStep.status == StepStatus.DONE) result = lastStep.output?.str("text").orEmpty()
        else if (done.isNotEmpty() && wantSynthesis && reasoner.unavailable() == null) {
            val rid = "final-$id"; r.requests += rid
            val role = if (done.any { it.toolId.startsWith("web.") }) AgentRole.RESEARCH else AgentRole.KNOWLEDGE
            val notDone = t.steps.filter { it.status != StepStatus.DONE }.joinToString("\n") { "- ${it.id}: ${it.status.name.lowercase()}${if (it.error.isNotBlank()) " (${it.error.take(160)})" else ""}" }
            val res = try { reasoner.reason(role, Roles.instructions.getValue(role) + "\n\n" + Untrusted.RULES, "Task: ${t.goal}\nWrite a short report for the owner of what was done and found. Mention anything that was not done.\n\nResults:\n${evidence(done)}\n\nNot done:\n${notDone.ifBlank { "(nothing)" }}", 450, rid) } finally { r.requests -= rid }
            result = res.text.takeIf { res.error == null && it.isNotBlank() } ?: ""
        }
        if (result.isBlank()) result = done.joinToString("\n") { "• ${it.description.ifBlank { it.toolId }}: ${it.output?.str("text").orEmpty().take(300)}" }.ifBlank { "Nothing was completed." } +
            (if (t.steps.any { it.status != StepStatus.DONE }) "\n\nNot done: " + t.steps.filter { it.status != StepStatus.DONE }.joinToString("; ") { "${it.id} ${it.status.name.lowercase()}" } else "")
        val now = clock.now()
        // Everything is recorded and revoked before the task shows as finished, so anyone who sees it finished sees its whole record.
        broker.revokeForTask(id, "the task ended"); broker.cancelPending(id, "the task ended")
        audit.record("task.finished", id, "orchestrator", "", mapOf("status" to status.name, "steps" to t.steps.size, "done" to done.size))
        db.execute("UPDATE agent_tasks SET status = ?, result = ?, updated_at = ?, finished_at = ?, error = '' WHERE id = ?", listOf(status.name, result.take(8000), now, now, id))
        runners.remove(id)
    }

    private fun finishCancelled(id: String) {
        db.execute("UPDATE agent_steps SET status = 'CANCELLED', finished_at = ? WHERE task_id = ? AND status IN ('PENDING','RUNNING','WAITING_APPROVAL')", listOf(clock.now(), id))
        val now = clock.now()
        db.execute("UPDATE agent_tasks SET status = 'CANCELLED', updated_at = ?, finished_at = ? WHERE id = ? AND status NOT IN ('COMPLETED','PARTIAL','FAILED')", listOf(now, now, id))
        broker.revokeForTask(id, "the task was cancelled"); broker.cancelPending(id, "the task was cancelled"); runners.remove(id)
    }

    private fun fail(id: String, message: String, kind: String) {
        val now = clock.now()
        db.execute("UPDATE agent_steps SET status = 'CANCELLED', finished_at = ? WHERE task_id = ? AND status IN ('PENDING','RUNNING','WAITING_APPROVAL')", listOf(now, id))
        broker.revokeForTask(id, "the task failed"); broker.cancelPending(id, "the task failed")
        audit.record(kind, id, "orchestrator", "", mapOf("error" to message))
        db.execute("UPDATE agent_tasks SET status = 'FAILED', error = ?, updated_at = ?, finished_at = ? WHERE id = ?", listOf(message.take(500), now, now, id)); runners.remove(id)
    }

    // ------------------------------------------------------------------------------------------ storage
    private fun meta(id: String): JsonObject = runCatching { CluJson.parseToJsonElement(db.query("SELECT meta FROM agent_tasks WHERE id = ?", listOf(id)) { it.str(0) }.firstOrNull() ?: "{}").jsonObject }.getOrDefault(JsonObject(emptyMap()))
    private fun JsonObject.getBoolean(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

    private fun setStatus(id: String, st: TaskStatus, note: String? = null) {
        val m = if (note != null) CluJson.encodeToString(JsonObject.serializer(), JsonObject(meta(id) + ("note" to JsonPrimitive(note)))) else null
        if (m != null) db.execute("UPDATE agent_tasks SET status = ?, updated_at = ?, meta = ? WHERE id = ? AND status NOT IN ('COMPLETED','PARTIAL','FAILED','CANCELLED')", listOf(st.name, clock.now(), m, id))
        else db.execute("UPDATE agent_tasks SET status = ?, updated_at = ? WHERE id = ? AND status NOT IN ('COMPLETED','PARTIAL','FAILED','CANCELLED')", listOf(st.name, clock.now(), id))
    }

    private fun setStep(taskId: String, id: String, st: StepStatus, output: JsonObject? = null, error: String = "", flagged: List<String> = emptyList()) {
        db.execute("UPDATE agent_steps SET status = ?, output = COALESCE(?, output), error = ?, flagged = ?, finished_at = CASE WHEN ? THEN ? ELSE finished_at END WHERE task_id = ? AND id = ?",
            listOf(st.name, output?.let { CluJson.encodeToString(JsonObject.serializer(), it) }, error.take(600), if (flagged.isNotEmpty()) 1 else 0, if (st.terminal) 1 else 0, clock.now(), taskId, id))
    }

    fun get(id: String): AgentTask? = db.query("SELECT id, goal, status, origin, summary, result, error, conversation_id, created_at, updated_at, started_at, finished_at, meta FROM agent_tasks WHERE id = ?", listOf(id)) { toTask(it, withSteps = true) }.firstOrNull()
    fun list(limit: Int = 50): List<AgentTask> = db.query("SELECT id, goal, status, origin, summary, result, error, conversation_id, created_at, updated_at, started_at, finished_at, meta FROM agent_tasks ORDER BY created_at DESC LIMIT ${limit.coerceIn(1, 200)}") { toTask(it, withSteps = false) }
        .map { it.copy(steps = stepsOf(it.id)) }
    fun activeCount(): Int = db.query("SELECT COUNT(*) FROM agent_tasks WHERE status IN ('PLANNING','QUEUED','RUNNING','WAITING_APPROVAL')") { it.lng(0).toInt() }.first()

    private fun toTask(r: SqlRow, withSteps: Boolean): AgentTask {
        val id = r.str(0); val meta = runCatching { CluJson.parseToJsonElement(r.str(12)).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        return AgentTask(id, r.str(1), TaskStatus.valueOf(r.str(2)), r.str(3), r.str(4), r.str(5), r.str(6), r.string(7), r.lng(8), r.lng(9), r.long(10), r.long(11), meta.str("note").orEmpty(), if (withSteps) stepsOf(id) else emptyList())
    }
    private fun stepsOf(taskId: String): List<AgentStep> = db.query("SELECT id, task_id, idx, role, kind, tool_id, args, depends, description, status, attempts, output, error, flagged, destructive, started_at, finished_at FROM agent_steps WHERE task_id = ? ORDER BY idx", listOf(taskId)) { r ->
        AgentStep(r.str(0), r.str(1), r.lng(2).toInt(), AgentRole.of(r.str(3)) ?: AgentRole.TOOL, StepKind.valueOf(r.str(4)), r.str(5), runCatching { CluJson.parseToJsonElement(r.str(6)).jsonObject }.getOrDefault(JsonObject(emptyMap())),
            runCatching { CluJson.parseToJsonElement(r.str(7)).jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList()), r.str(8), StepStatus.valueOf(r.str(9)), r.lng(10).toInt(),
            r.str(11).takeIf { it.isNotBlank() }?.let { runCatching { CluJson.parseToJsonElement(it).jsonObject }.getOrNull() }, r.str(12),
            if (r.lng(13) != 0L) (r.str(11).takeIf { it.isNotBlank() }?.let { runCatching { CluJson.parseToJsonElement(it).jsonObject.str("text")?.let(Untrusted::scan) }.getOrNull() } ?: listOf("suspicious content")) else emptyList(),
            r.lng(14) != 0L, r.long(15), r.long(16)) }

    /** Offers the task's result to the owner as a suggested memory (it is only a suggestion: nothing is saved until the owner approves it under Suggestions). */
    fun suggestRemembering(taskId: String, propose: (String, String) -> Boolean): Boolean {
        val t = get(taskId) ?: return false; if (t.result.isBlank()) return false
        val ok = propose("Task \"${t.goal.take(80)}\": ${t.result.take(300)}", "Result of a task you asked for")
        audit.record("task.memory-suggested", taskId, "owner", "", mapOf("suggested" to ok)); return ok
    }

    /** What the Agents window shows about the system itself. */
    fun overview(): JsonObject = buildJsonObject {
        put("enabled", settings.bool(Settings.AGENTS_ENABLED)); put("reviewPlan", settings.bool(Settings.AGENTS_REVIEW_PLAN))
        put("limits", buildJsonObject { put("maxSteps", settings.int(Settings.AGENTS_MAX_STEPS)); put("maxParallel", settings.int(Settings.AGENTS_MAX_PARALLEL)); put("maxTasks", settings.int(Settings.AGENTS_MAX_TASKS)); put("taskTimeoutMin", settings.int(Settings.AGENTS_TASK_TIMEOUT)) })
        put("activeTasks", activeCount()); put("pendingApprovals", broker.pendingCount()); put("auditRecords", audit.count())
        putJsonArray("roles") { AgentRole.entries.forEach { role -> addJsonObject { put("id", role.id); put("title", role.title); put("purpose", role.purpose)
            putJsonArray("tools") { registry.all().filter { role in it.spec.roles }.forEach { add(it.spec.id) } }
            put("reasons", role in Roles.reasoningRoles || role == AgentRole.PLANNER) } } }
        putJsonArray("categories") { Category.entries.forEach { c -> addJsonObject { put("id", c.name); put("label", c.label); put("meaning", c.meaning) } } }
    }
}

/** Which tools the owner has switched on or off (a tool that is off by default, like running programs, stays off until the owner switches it on). */
class ToolSwitches(private val db: SqlDriver, private val registry: AgentToolRegistry, private val audit: AuditLog) {
    private fun overrides(): Map<String, Boolean> = runCatching { CluJson.parseToJsonElement(db.query("SELECT value FROM kv_meta WHERE key = 'agents.toolOverrides'") { it.str(0) }.firstOrNull() ?: "{}").jsonObject.mapValues { it.value.jsonPrimitive.boolean } }.getOrDefault(emptyMap())
    fun enabled(id: String): Boolean = overrides()[id] ?: registry[id]?.spec?.defaultEnabled ?: false
    fun disabled(): Set<String> = registry.ids().filter { !enabled(it) }.toSet()
    fun set(id: String, on: Boolean, by: String = "owner") {
        require(registry[id] != null) { "no such tool" }
        val o = overrides().toMutableMap().also { it[id] = on }
        db.execute("INSERT INTO kv_meta(key, value) VALUES('agents.toolOverrides', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", listOf(CluJson.encodeToString(JsonObject.serializer(), JsonObject(o.mapValues { JsonPrimitive(it.value) }))))
        audit.record("permission.tool-switch", "", by, id, mapOf("enabled" to on))
    }
}
