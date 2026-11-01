package ai.clu.desktop

import ai.clu.core.CluCore
import ai.clu.core.agents.*
import ai.clu.core.config.Settings
import ai.clu.core.memory.ProposalKind
import kotlinx.serialization.json.*
import java.io.File

/**
 * The window's access to assistant tasks (M7). All of these are owner actions made with the owner's token; agents have no way to call them.
 *
 * | | |
 * |---|---|
 * | GET /v1/agents/status | limits, roles with their tools, risk categories, counts (pending approvals, active tasks) |
 * | GET, POST /v1/agents/tasks | list; create from a goal (planned by the model) or from explicit `steps` |
 * | GET /v1/agents/tasks/{id} | the plan, every step with its state and output, the result |
 * | POST /v1/agents/tasks/{id}/start, pause, resume, cancel, remember | control; `start` may also approve the plan's category-B actions |
 * | GET /v1/agents/approvals?status= | requests waiting for the owner (default) or the history |
 * | POST /v1/agents/approvals/{id} | `{decision: approve|reject, scope: once|task, acknowledge}` |
 * | GET /v1/agents/permissions | allowed folders, active grants, tools with their switches |
 * | POST /v1/agents/permissions, /{id}/revoke, /revoke-all | standing permission with an end; revoke one; revoke everything |
 * | POST /v1/agents/roots, /roots/remove | allow / stop allowing a folder for tasks |
 * | PUT /v1/agents/tools/{id} | switch a tool on or off |
 * | GET /v1/agents/audit, /audit/verify | the record (filters: task, kind, before), and a check of its integrity |
 * | POST /v1/agents/init | first-run: create and allow the workspace folder, switch tasks on or off |
 */
class AgentRoutes(private val core: CluCore, private val governor: ResourceGovernor) {
    private val svc get() = core.agents
    init { svc.gate = ResourceGate { governor.taskBlockReason() } }

    fun handle(c: Call): Boolean {
        val p = c.path
        if (!p.startsWith("/v1/agents/")) return false
        val task = Regex("^/v1/agents/tasks/([^/]+)(?:/(start|pause|resume|cancel|remember))?$").find(p)
        val appr = Regex("^/v1/agents/approvals/([^/]+)$").find(p)
        val revoke = Regex("^/v1/agents/permissions/([^/]+)/revoke$").find(p)
        val tool = Regex("^/v1/agents/tools/([^/]+)$").find(p)
        try {
            when {
                p == "/v1/agents/status" && c.method == "GET" -> c.ok(status())
                p == "/v1/agents/tasks" && c.method == "GET" -> c.ok(buildJsonArray { svc.list().forEach { add(taskJson(it, detail = false)) } })
                p == "/v1/agents/tasks" && c.method == "POST" -> create(c)
                task != null && task.groupValues[2].isEmpty() && c.method == "GET" -> svc.get(task.groupValues[1])?.let { c.ok(taskJson(it, detail = true)) } ?: c.fail(404, "no such task")
                task != null && c.method == "POST" -> control(c, task.groupValues[1], task.groupValues[2])
                p == "/v1/agents/approvals" && c.method == "GET" -> {
                    val all = c.query("status") == "all"
                    c.ok(buildJsonArray { (if (all) core.agentBroker.approvals(limit = 100) else core.agentBroker.approvals(status = ApprovalStatus.PENDING)).forEach { add(approvalJson(it)) } })
                }
                appr != null && c.method == "POST" -> decide(c, appr.groupValues[1])
                p == "/v1/agents/permissions" && c.method == "GET" -> c.ok(permissions())
                p == "/v1/agents/permissions" && c.method == "POST" -> standing(c)
                revoke != null && c.method == "POST" -> if (core.agentBroker.revoke(revoke.groupValues[1])) c.ok() else c.fail(404, "no such active permission")
                p == "/v1/agents/permissions/revoke-all" && c.method == "POST" -> c.ok(buildJsonObject { put("revoked", core.agentBroker.revokeAll()) })
                p == "/v1/agents/roots" && c.method == "POST" -> addRoot(c)
                p == "/v1/agents/roots/remove" && c.method == "POST" -> { val path = c.str("path") ?: return true.also { c.fail(400, "path is required") }
                    if (core.agentRoots.remove(path)) { core.agentAudit.record("permission.folder-removed", "", "owner", path, emptyMap()); c.ok() } else c.fail(404, "that folder is not on the list") }
                tool != null && c.method == "PUT" -> { val on = c.body["enabled"]?.jsonPrimitive?.booleanOrNull ?: return true.also { c.fail(400, "enabled is required") }
                    try { svc.setToolEnabled(tool.groupValues[1], on); c.ok() } catch (e: IllegalArgumentException) { c.fail(404, e.message ?: "no such tool") } }
                p == "/v1/agents/audit" && c.method == "GET" -> c.ok(buildJsonArray {
                    core.agentAudit.list(c.query("task")?.ifBlank { null }, kind = null, kindPrefix = c.query("kind")?.ifBlank { null }, limit = c.query("limit")?.toIntOrNull() ?: 100, beforeSeq = c.query("before")?.toLongOrNull()).forEach { e ->
                        addJsonObject { put("seq", e.seq); put("ts", e.ts); put("task", e.taskId); put("actor", e.actor); put("kind", e.kind); put("subject", e.subject); put("detail", e.detail) } } })
                p == "/v1/agents/audit/verify" && c.method == "GET" -> core.agentAudit.verify().let { v -> c.ok(buildJsonObject { put("ok", v.ok); put("records", v.events); v.firstBadSeq?.let { put("firstBadRecord", it) }; put("message", v.message) }) }
                p == "/v1/agents/init" && c.method == "POST" -> init(c)
                else -> c.fail(404, "no such endpoint")
            }
        } catch (e: PlanProblem) { c.fail(400, e.message ?: "The plan is not valid.")
        } catch (e: PathRefused) { c.fail(400, e.message ?: "That folder cannot be used.")
        } catch (e: IllegalStateException) { c.fail(409, e.message ?: "Not possible now.")
        } catch (e: IllegalArgumentException) { c.fail(400, e.message ?: "Invalid request.") }
        return true
    }

    // -------------------------------------------------------------------------------------------------- views
    private fun status() = buildJsonObject {
        svc.overview().forEach { (k, v) -> put(k, v) }
        put("workspace", core.agentRoots.workspace()?.path ?: ""); put("modelAvailable", core.inferenceService.state.value.status != ai.clu.core.llm.LlmStatus.NO_MODEL)
        put("blockedReason", governor.taskBlockReason() ?: "")
    }

    private fun permissions() = buildJsonObject {
        putJsonArray("roots") { core.agentRoots.list().forEach { r -> addJsonObject { put("path", r.path); put("writable", r.writable); put("label", r.label); put("exists", File(r.path).isDirectory) } } }
        putJsonArray("grants") { core.agentBroker.grants(onlyActive = true).forEach { g -> add(grantJson(g)) } }
        putJsonArray("recentGrants") { core.agentBroker.grants(onlyActive = false, limit = 30).filter { it.revokedAt != null || it.expiresAt <= System.currentTimeMillis() }.take(15).forEach { g -> add(grantJson(g)) } }
        putJsonArray("tools") { core.agentTools.all().forEach { t -> add(toolJson(t)) } }
        putJsonArray("approvals") { core.agentBroker.approvals(limit = 30).filter { it.status != ApprovalStatus.PENDING }.take(20).forEach { add(approvalJson(it)) } }
        put("shellAllowlist", JsonArray(core.settings.list(Settings.AGENTS_SHELL_ALLOWLIST).map(::JsonPrimitive)))
    }

    private fun toolJson(t: AgentTool) = buildJsonObject {
        val s = t.spec
        put("id", s.id); put("description", s.description); put("category", s.category.name); put("categoryLabel", s.category.label); put("scope", s.scope); put("timeoutMs", s.timeoutMs)
        put("environment", s.environment); put("cancellable", s.cancellable); put("mutates", s.mutates); put("enabled", svc.toolEnabled(s.id)); put("defaultEnabled", s.defaultEnabled)
        putJsonArray("roles") { s.roles.forEach { add(it.id) } }
        putJsonObject("input") { s.input.forEach { (k, f) -> put(k, f.type + (if (f.required) " (required)" else "")) } }
        put("untrustedOutput", s.untrustedOutput); putJsonArray("dataFields") { s.dataFields.forEach { add(it) } }
        if (s.category == Category.B && t.classify(buildJsonObject {}) != s.category) put("note", "Some uses need explicit confirmation")
        if (s.id == "fs.write") put("note", "Creating or adding to a file needs approval; replacing a file needs confirmation each time (the old version is kept).")
    }

    private fun grantJson(g: Grant) = buildJsonObject {
        put("id", g.id); put("tool", g.toolId); put("scope", g.scope); put("kind", g.kind.name); put("task", g.taskId); put("createdAt", g.createdAt); put("expiresAt", g.expiresAt)
        g.usesLeft?.let { put("usesLeft", it) }; g.revokedAt?.let { put("revokedAt", it) }; put("note", g.note)
        putJsonArray("resources") { g.resources.forEach { add(it) } }
    }

    private fun approvalJson(a: ApprovalRequest) = buildJsonObject {
        put("id", a.id); put("task", a.taskId); put("step", a.stepId); put("tool", a.toolId); put("scope", a.scope); put("category", a.category.name); put("categoryLabel", a.category.label)
        put("intent", a.intent); put("why", a.why); put("reversible", a.reversible); put("onSuccess", a.onSuccess); put("onFailure", a.onFailure); put("args", a.argsPreview)
        put("status", a.status.name); a.decisionKind?.let { put("decision", it) }; put("createdAt", a.createdAt); put("expiresAt", a.expiresAt); a.decidedAt?.let { put("decidedAt", it) }; a.decidedBy?.let { put("decidedBy", it) }
        putJsonArray("resources") { a.resources.forEach { add(it) } }
        svc.get(a.taskId)?.let { put("taskGoal", it.goal) }
    }

    private fun taskJson(t: AgentTask, detail: Boolean) = buildJsonObject {
        put("id", t.id); put("goal", t.goal); put("status", t.status.name); put("statusLabel", t.status.label); put("terminal", t.status.terminal); put("origin", t.origin)
        put("summary", t.summary); put("result", t.result); put("error", t.error); put("note", t.note); put("createdAt", t.createdAt); put("updatedAt", t.updatedAt)
        t.startedAt?.let { put("startedAt", it) }; t.finishedAt?.let { put("finishedAt", it) }
        put("stepsTotal", t.steps.size); put("stepsDone", t.steps.count { it.status == StepStatus.DONE })
        putJsonArray("steps") { t.steps.forEach { s -> addJsonObject {
            put("id", s.id); put("role", s.role.id); put("roleTitle", s.role.title); put("kind", s.kind.name); put("tool", s.toolId); put("description", s.description); put("status", s.status.name)
            put("attempts", s.attempts); put("error", s.error); put("changes", s.destructive); put("after", JsonArray(s.depends.map(::JsonPrimitive)))
            putJsonArray("flagged") { s.flagged.forEach { add(it) } }
            if (detail) {
                (s.output?.get("text") as? JsonPrimitive)?.contentOrNull?.let { put("output", it.take(2500)) }
                if (s.kind == StepKind.TOOL) { val tl = core.agentTools[s.toolId]
                    put("args", JsonObject(s.args.mapValues { (k, v) -> val str = (v as? JsonPrimitive)?.takeIf { it.isString }?.content; if (str != null && str.length > 200) JsonPrimitive(str.take(200) + "… (${str.length} characters)") else v }))
                    tl?.let { put("category", runCatching { it.classify(s.args) }.getOrDefault(it.spec.category).name) } }
            }
            s.startedAt?.let { put("startedAt", it) }; s.finishedAt?.let { put("finishedAt", it) } } } }
        if (detail) putJsonArray("approvals") { core.agentBroker.approvals(taskId = t.id, limit = 50).forEach { add(approvalJson(it)) } }
    }

    // -------------------------------------------------------------------------------------------------- actions
    private fun create(c: Call) {
        val goal = c.str("goal")?.trim().orEmpty(); if (goal.isEmpty()) return c.fail(400, "Describe the goal of the task.")
        val steps = c.body["steps"]
        val planJson = if (steps is JsonArray) buildJsonObject { put("steps", steps) }.toString() else null
        val start = c.body["start"]?.jsonPrimitive?.booleanOrNull ?: (planJson != null)
        val t = svc.create(goal, planJson, origin = "owner", autostart = start && planJson != null, synthesize = c.body["report"]?.jsonPrimitive?.booleanOrNull)
        c.send(202, taskJson(t, detail = true))
    }

    private fun control(c: Call, id: String, action: String) {
        val r: Result<AgentTask> = when (action) {
            "start" -> svc.start(id, approveActions = c.body["approveActions"]?.jsonPrimitive?.booleanOrNull == true)
            "resume" -> svc.resume(id)
            "pause" -> if (svc.pause(id)) Result.success(svc.get(id)!!) else Result.failure(IllegalStateException("This task cannot be paused now."))
            "cancel" -> if (svc.cancel(id)) Result.success(svc.get(id)!!) else Result.failure(IllegalStateException("This task is already finished or does not exist."))
            "remember" -> {
                val ok = svc.suggestRemembering(id) { text, why -> core.proposals.propose(ProposalKind.OBSERVATION, text, why, sourceRef = "task:$id") != null }
                if (ok) Result.success(svc.get(id)!!) else Result.failure(IllegalStateException("Nothing to suggest (the task has no result, or suggestions are switched off)."))
            }
            else -> Result.failure(IllegalArgumentException("unknown action"))
        }
        r.fold({ c.ok(taskJson(it, detail = true)) }, { c.fail(if (it is IllegalArgumentException) 400 else 409, it.message ?: "Not possible now.") })
    }

    private fun decide(c: Call, id: String) {
        val decision = c.str("decision") ?: return c.fail(400, "decision must be approve or reject")
        if (decision != "approve" && decision != "reject") return c.fail(400, "decision must be approve or reject")
        val kind = c.str("scope") ?: "once"; if (kind != "once" && kind != "task") return c.fail(400, "scope must be once or task")
        val r = core.agentBroker.decide(id, decision == "approve", kind, acknowledge = c.body["acknowledge"]?.jsonPrimitive?.booleanOrNull == true)
        r.fold({ c.ok(approvalJson(it)) }, { c.fail(if (it is IllegalArgumentException) 400 else 409, it.message ?: "Not possible now.") })
    }

    private fun standing(c: Call) {
        val g = core.agentBroker.createStanding(c.str("tool") ?: "", c.strings("resources"), c.body["hours"]?.jsonPrimitive?.intOrNull ?: 1)
        g.fold({ c.send(201, grantJson(it)) }, { c.fail(400, it.message ?: "refused") })
    }

    private fun addRoot(c: Call) {
        val path = c.str("path")?.trim().orEmpty(); if (path.isEmpty()) return c.fail(400, "path is required")
        val f = File(path); if (!f.isAbsolute) return c.fail(400, "Give the full path of the folder.")
        val r = core.agentRoots.add(f, writable = c.body["writable"]?.jsonPrimitive?.booleanOrNull == true, label = c.str("label") ?: "")
        core.agentAudit.record("permission.folder-added", "", "owner", r.path, mapOf("writable" to r.writable)); c.send(201, buildJsonObject { put("path", r.path); put("writable", r.writable) })
    }

    /** First run: tasks on or off, and the one folder they may work in (created if needed). Nothing else is allowed. */
    private fun init(c: Call) {
        val enabled = c.body["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        core.settings.set(Settings.AGENTS_ENABLED, enabled)
        var folder = ""
        if (enabled) c.str("workspace")?.trim()?.takeIf { it.isNotEmpty() }?.let { w ->
            val f = File(w); if (!f.isAbsolute) return c.fail(400, "Give the full path of the folder.")
            f.mkdirs(); folder = core.agentRoots.add(f, writable = true, label = "workspace").path
            core.agentAudit.record("permission.folder-added", "", "owner", folder, mapOf("writable" to true, "via" to "first-run guide"))
        }
        core.agentAudit.record("permission.initialised", "", "owner", "", mapOf("enabled" to enabled, "folder" to folder))
        c.ok(buildJsonObject { put("enabled", enabled); put("workspace", folder) })
    }
}
