package ai.clu.core.agents

import ai.clu.core.db.SqlDriver
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.util.Clock
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

// ------------------------------------------------------------------------------------------------ folders agents may touch

/** A folder that file tools may use. Everything outside the list is out of reach, whatever an agent or a plan asks for. */
data class AgentRoot(val path: String, val writable: Boolean, val label: String, val createdAt: Long)

class PathRefused(message: String) : Exception(message)

/**
 * The only way file tools turn a name into a file. Resolution is done on the real filesystem (symbolic links followed), so a
 * link inside an allowed folder that points elsewhere, "..", mixed separators or upper/lower case tricks cannot leave the
 * allowed folders. CLU's own data (database, keys, tokens, backups, logs) is never reachable, even if a folder above it is allowed.
 */
class AgentRoots(private val db: SqlDriver, private val clock: Clock, private val forbidden: () -> List<File> = { emptyList() }) {
    fun list(): List<AgentRoot> = db.query("SELECT path, writable, label, created_at FROM agent_roots ORDER BY created_at") { AgentRoot(it.str(0), it.lng(1) != 0L, it.str(2), it.lng(3)) }

    fun add(folder: File, writable: Boolean, label: String = ""): AgentRoot {
        val real = realPath(folder.absoluteFile.toPath())
        if (!real.toFile().isDirectory) throw PathRefused("${folder.path} is not a folder")
        if (real.nameCount == 0) throw PathRefused("A whole drive cannot be allowed; choose a folder")
        val home = File(System.getProperty("user.home")).toPath().let(::realPath)
        if (real == home) throw PathRefused("Your whole home folder cannot be allowed; choose a folder inside it")
        forbidden().map { realPath(it.toPath()) }.firstOrNull { real.startsWith(it) || it.startsWith(real) }?.let { throw PathRefused("That folder contains or is inside CLU's own data and cannot be allowed") }
        db.execute("INSERT INTO agent_roots(path, writable, label, created_at) VALUES(?,?,?,?) ON CONFLICT(path) DO UPDATE SET writable = excluded.writable, label = excluded.label", listOf(real.toString(), if (writable) 1 else 0, label.take(60), clock.now()))
        return list().first { it.path == real.toString() }
    }

    fun remove(path: String): Boolean { val n = list().size; db.execute("DELETE FROM agent_roots WHERE path = ?", listOf(path)); return list().size < n }

    /** The folder relative names are resolved against: the first writable one (the workspace). */
    fun workspace(): AgentRoot? = list().firstOrNull { it.writable }

    /** Resolves [name] (absolute, or relative to the workspace) to a real path inside an allowed folder, or throws [PathRefused] with the reason. */
    fun resolve(name: String, write: Boolean): Path {
        if (name.isBlank() || name.any { it.code == 0 }) throw PathRefused("No file name given")
        val roots = list()
        if (roots.isEmpty()) throw PathRefused("No folder is allowed for CLU's tasks yet. Allow one in Agents > Permissions.")
        val base = (roots.firstOrNull { it.writable } ?: roots.first()).path
        val raw = Path.of(name).let { if (it.isAbsolute) it else Path.of(base).resolve(it) }
        val real = realPath(raw)
        val root = roots.firstOrNull { real.startsWith(realPath(Path.of(it.path))) } ?: throw PathRefused("$name is outside the folders CLU's tasks may use")
        if (write && !root.writable) throw PathRefused("$name is in a folder that is read-only for tasks")
        forbidden().map { realPath(it.toPath()) }.firstOrNull { real.startsWith(it) }?.let { throw PathRefused("$name is part of CLU's own data") }
        return real
    }

    companion object {
        /** Real path of the deepest existing ancestor (links resolved) plus the rest, normalised: works for files that do not exist yet. */
        fun realPath(p: Path): Path {
            var existing = p.toAbsolutePath().normalize(); val rest = ArrayDeque<Path>()
            while (existing != null && !java.nio.file.Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { rest.addFirst(existing.fileName ?: break); existing = existing.parent }
            var out = existing?.let { runCatching { it.toRealPath() }.getOrDefault(it) } ?: p.toAbsolutePath().normalize()
            for (r in rest) out = out.resolve(r)
            return out.normalize()
        }
    }
}

// ------------------------------------------------------------------------------------------------ approvals and grants

enum class ApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED }
enum class GrantKind { ONCE, TASK, STANDING }

data class ApprovalRequest(val id: String, val taskId: String, val stepId: String, val toolId: String, val scope: String, val category: Category, val resources: List<String>,
                    val intent: String, val why: String, val reversible: Boolean, val onSuccess: String, val onFailure: String, val argsPreview: String, val callKey: String,
                    val status: ApprovalStatus, val decisionKind: String?, val createdAt: Long, val expiresAt: Long, val decidedAt: Long?, val decidedBy: String?)

data class Grant(val id: String, val toolId: String, val scope: String, val resources: List<String>, val taskId: String, val kind: GrantKind, val usesLeft: Int?,
                 val createdAt: Long, val expiresAt: Long, val revokedAt: Long?, val approvalId: String, val note: String) {
    fun active(now: Long) = revokedAt == null && now < expiresAt && (usesLeft == null || usesLeft > 0)
}

/** A request to do one thing, as the broker sees it. */
data class AuthRequest(val taskId: String, val stepId: String, val role: AgentRole, val tool: ToolSpec, val category: Category, val resources: List<String>,
                       val intent: Intent, val argsPreview: String, val callKey: String, val why: String)

sealed class Decision {
    data class Allowed(val via: String, val grantId: String? = null) : Decision()
    data class NeedsApproval(val approval: ApprovalRequest) : Decision()
    data class Denied(val code: String, val reason: String) : Decision()
}

/**
 * The one place that decides whether a tool call may run. Agents never reach a tool except through it.
 *
 * * Least privilege: a role may only use the tools listed for it; a tool may only touch the folders allowed for tasks.
 * * A: automatic (and recorded). B: needs a matching, unexpired, unrevoked grant, which only comes from the owner's approval.
 *   C: needs the owner's explicit confirmation for this very call; no task, standing or earlier grant ever covers it.
 * * Grants are narrow (one tool, one scope, exact resources), expire, can be revoked at once, and are checked again at the
 *   moment of use, so revoking stops a task that is already running.
 * * A rejected action is remembered for the task: the same call is not asked again, the step ends as refused.
 */
class PermissionBroker(private val db: SqlDriver, private val clock: Clock, private val audit: AuditLog, val roots: AgentRoots,
                       private val registry: AgentToolRegistry, private val disabledTools: () -> Set<String>) {
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<ApprovalStatus>>()
    var approvalTtlMs: Long = 30 * 60_000L
    var confirmTtlMs: Long = 10 * 60_000L
    var taskGrantTtlMs: Long = 60 * 60_000L
    var taskGrantUses: Int = 25
    val maxStandingMs: Long = 7 * 24 * 3_600_000L

    fun authorize(r: AuthRequest): Decision {
        val tool = r.tool
        fun deny(code: String, reason: String): Decision.Denied { audit.record("permission.denied", r.taskId, "broker", tool.id, mapOf("code" to code, "reason" to reason, "step" to r.stepId, "resources" to r.resources, "category" to r.category.name)); return Decision.Denied(code, reason) }
        if (tool.id in disabledTools()) return deny("TOOL_DISABLED", "The tool ${tool.id} is switched off in Agents > Permissions.")
        if (r.role !in tool.roles) return deny("ROLE_FORBIDDEN", "The ${r.role.title} role is not allowed to use ${tool.id}.")
        if (rejectedBefore(r.taskId, r.callKey)) return deny("PREVIOUSLY_REJECTED", "You already rejected exactly this action in this task, so it was not asked again.")
        if (r.category == Category.A) { audit.record("permission.allowed", r.taskId, "broker", tool.id, mapOf("via" to "category A (safe)", "step" to r.stepId, "resources" to r.resources)); return Decision.Allowed("category A") }
        if (r.category == Category.B) findGrant(r)?.let { g ->
            if (g.kind == GrantKind.ONCE) consume(g)
            else if (g.usesLeft != null) db.execute("UPDATE permission_grants SET uses_left = uses_left - 1 WHERE id = ? AND uses_left > 0", listOf(g.id))
            audit.record("permission.allowed", r.taskId, "broker", tool.id, mapOf("via" to "grant ${g.id} (${g.kind})", "step" to r.stepId, "resources" to r.resources))
            return Decision.Allowed("grant ${g.kind.name.lowercase()}", g.id)
        }
        if (r.category == Category.C) findGrant(r, onlyOnceAcknowledged = true)?.let { g -> consume(g)
            audit.record("permission.allowed", r.taskId, "broker", tool.id, mapOf("via" to "your confirmation ${g.approvalId}", "step" to r.stepId, "resources" to r.resources))
            return Decision.Allowed("confirmation", g.id) }
        // Needs the owner. One pending request per identical call.
        val existing = approvals(taskId = r.taskId, status = ApprovalStatus.PENDING).firstOrNull { it.callKey == r.callKey && it.expiresAt > clock.now() }
        if (existing != null) return Decision.NeedsApproval(existing)
        val now = clock.now(); val id = newId()
        val ttl = if (r.category == Category.C) confirmTtlMs else approvalTtlMs
        db.execute("INSERT INTO approvals(id, task_id, step_id, tool_id, scope, category, resources, intent, why, reversible, on_success, on_failure, args_preview, call_key, status, created_at, expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(id, r.taskId, r.stepId, tool.id, tool.scope, r.category.name, CluJson.encodeToString(JsonArray.serializer(), JsonArray(r.resources.map(::JsonPrimitive))), r.intent.summary.take(300), r.why.take(400),
                if (r.intent.reversible) 1 else 0, r.intent.onSuccess.take(300), r.intent.onFailure.take(300), Redactor.redact(r.argsPreview).take(600), r.callKey, ApprovalStatus.PENDING.name, now, now + ttl))
        waiting[id] = CompletableDeferred()
        audit.record("approval.requested", r.taskId, "broker", tool.id, mapOf("approval" to id, "category" to r.category.name, "step" to r.stepId, "resources" to r.resources, "intent" to r.intent.summary))
        return Decision.NeedsApproval(approval(id)!!)
    }

    /** Suspends until the owner decides (or the request runs out). Returns the final status. */
    suspend fun await(approvalId: String): ApprovalStatus {
        val a = approval(approvalId) ?: return ApprovalStatus.CANCELLED
        if (a.status != ApprovalStatus.PENDING) return a.status
        val d = waiting.getOrPut(approvalId) { CompletableDeferred() }
        val left = (a.expiresAt - clock.now()).coerceAtLeast(1)
        val got = withTimeoutOrNull(left) { d.await() }
        if (got != null) return got
        expire(approvalId); return approval(approvalId)?.status ?: ApprovalStatus.EXPIRED
    }

    /**
     * The owner's decision. [kind] is "once" or "task" (task covers exactly the approved resources of this tool for the rest of the task,
     * for category B only). Category C needs [acknowledge] and is always single-use.
     */
    fun decide(approvalId: String, approve: Boolean, kind: String = "once", acknowledge: Boolean = false, by: String = "owner"): Result<ApprovalRequest> {
        val a = approval(approvalId) ?: return Result.failure(IllegalArgumentException("no such approval"))
        if (a.status != ApprovalStatus.PENDING) return Result.failure(IllegalStateException("This request was already ${a.status.name.lowercase()}."))
        val now = clock.now()
        if (now >= a.expiresAt) { expire(approvalId); return Result.failure(IllegalStateException("This request ran out of time. CLU will ask again if the task still needs it.")) }
        if (!approve) {
            db.execute("UPDATE approvals SET status = ?, decided_at = ?, decided_by = ?, decision_kind = 'reject' WHERE id = ?", listOf(ApprovalStatus.REJECTED.name, now, by, approvalId))
            audit.record("approval.rejected", a.taskId, by, a.toolId, mapOf("approval" to approvalId, "resources" to a.resources))
            waiting[approvalId]?.complete(ApprovalStatus.REJECTED); return Result.success(approval(approvalId)!!)
        }
        if (a.category == Category.C && !acknowledge) return Result.failure(IllegalArgumentException("This action cannot be undone by CLU. Tick the confirmation to go ahead."))
        val grantKind = when { a.category == Category.C -> GrantKind.ONCE; kind == "task" -> GrantKind.TASK; else -> GrantKind.ONCE }
        val g = Grant(newId(), a.toolId, a.scope, a.resources, a.taskId, grantKind, if (grantKind == GrantKind.ONCE) 1 else taskGrantUses, now,
            now + if (grantKind == GrantKind.ONCE) confirmTtlMs else taskGrantTtlMs, null, approvalId, "approved by $by")
        insertGrant(g)
        db.execute("UPDATE approvals SET status = ?, decided_at = ?, decided_by = ?, decision_kind = ? WHERE id = ?", listOf(ApprovalStatus.APPROVED.name, now, by, grantKind.name.lowercase(), approvalId))
        audit.record("approval.approved", a.taskId, by, a.toolId, mapOf("approval" to approvalId, "grant" to g.id, "kind" to grantKind.name, "resources" to a.resources, "expiresAt" to g.expiresAt))
        waiting[approvalId]?.complete(ApprovalStatus.APPROVED); return Result.success(approval(approvalId)!!)
    }

    /** The owner approves a whole plan's category-B steps at once: one task grant per step, covering exactly what that step will touch. Category C is never included. */
    fun approvePlan(taskId: String, steps: List<PlannedAction>, by: String = "owner"): List<Grant> {
        val now = clock.now()
        return steps.filter { it.category == Category.B }.map { s ->
            val g = Grant(newId(), s.toolId, s.scope, s.resources, taskId, GrantKind.TASK, taskGrantUses, now, now + taskGrantTtlMs, null, "", "plan approved by $by")
            insertGrant(g); audit.record("grant.created", taskId, by, s.toolId, mapOf("grant" to g.id, "kind" to "TASK", "via" to "plan approval", "resources" to s.resources)); g
        }
    }

    /** A standing permission for category B actions, always with an end (at most a week). */
    fun createStanding(toolId: String, resources: List<String>, hours: Int, by: String = "owner"): Result<Grant> {
        val tool = registry[toolId] ?: return Result.failure(IllegalArgumentException("no such tool"))
        if (tool.spec.category == Category.A) return Result.failure(IllegalArgumentException("${tool.spec.id} needs no permission"))
        if (tool.spec.category == Category.C || tool.spec.id == "proc.run") return Result.failure(IllegalArgumentException("${tool.spec.id} always needs confirmation for each action; it cannot be pre-approved"))
        if (resources.isEmpty() || resources.any { it == "*" || it.isBlank() }) return Result.failure(IllegalArgumentException("A permission must name what it covers (a folder or exact items), not everything"))
        val now = clock.now(); val ms = (hours.coerceIn(1, 168) * 3_600_000L).coerceAtMost(maxStandingMs)
        val resolved = runCatching { if (tool.spec.scope.startsWith("fs.")) resources.map { r -> roots.resolve(r, write = tool.spec.mutates).toString().let { p -> if (r.endsWith("/") || r.endsWith("\\")) p + File.separator else p } } else resources }.getOrElse { return Result.failure(it) }
        val g = Grant(newId(), toolId, tool.spec.scope, resolved, "", GrantKind.STANDING, null, now, now + ms, null, "", "created by $by")
        insertGrant(g); audit.record("grant.created", "", by, toolId, mapOf("grant" to g.id, "kind" to "STANDING", "resources" to resolved, "hours" to hours)); return Result.success(g)
    }

    fun revoke(grantId: String, by: String = "owner", why: String = "revoked"): Boolean {
        val g = grants(onlyActive = false).firstOrNull { it.id == grantId } ?: return false
        if (g.revokedAt != null) return false
        db.execute("UPDATE permission_grants SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", listOf(clock.now(), grantId))
        audit.record("grant.revoked", g.taskId, by, g.toolId, mapOf("grant" to grantId, "why" to why)); return true
    }

    fun revokeAll(by: String = "owner", why: String = "all permissions revoked"): Int = grants(onlyActive = true).count { revoke(it.id, by, why) }.also { rejectAllPending(by) }
    fun revokeForTask(taskId: String, why: String): Int = grants(onlyActive = true).filter { it.taskId == taskId }.count { revoke(it.id, "system", why) }

    /** Pending requests of a task that is being stopped. */
    fun cancelPending(taskId: String, why: String) { approvals(taskId = taskId, status = ApprovalStatus.PENDING).forEach { setFinal(it.id, ApprovalStatus.CANCELLED, why) } }
    fun cancelAllPendingAfterRestart() { approvals(status = ApprovalStatus.PENDING).forEach { setFinal(it.id, ApprovalStatus.EXPIRED, "CLU was restarted") } }
    private fun rejectAllPending(by: String) { approvals(status = ApprovalStatus.PENDING).forEach { setFinal(it.id, ApprovalStatus.CANCELLED, "permissions were revoked by $by") } }

    private fun expire(id: String) = setFinal(id, ApprovalStatus.EXPIRED, "ran out of time")
    private fun setFinal(id: String, st: ApprovalStatus, why: String) {
        db.execute("UPDATE approvals SET status = ?, decided_at = ?, decided_by = 'system' WHERE id = ? AND status = 'PENDING'", listOf(st.name, clock.now(), id))
        audit.record("approval.${st.name.lowercase()}", approval(id)?.taskId ?: "", "system", approval(id)?.toolId ?: "", mapOf("approval" to id, "why" to why))
        waiting[id]?.complete(st)
    }

    // -- queries
    fun approval(id: String): ApprovalRequest? = db.query("$APPROVAL_SELECT WHERE id = ?", listOf(id), ::readApproval).firstOrNull()
    fun approvals(taskId: String? = null, status: ApprovalStatus? = null, limit: Int = 100): List<ApprovalRequest> {
        val w = mutableListOf<String>(); val a = mutableListOf<Any?>()
        taskId?.let { w += "task_id = ?"; a += it }; status?.let { w += "status = ?"; a += it.name }
        return db.query("$APPROVAL_SELECT ${if (w.isEmpty()) "" else "WHERE " + w.joinToString(" AND ")} ORDER BY created_at DESC LIMIT ${limit.coerceIn(1, 500)}", a, ::readApproval)
    }
    fun pendingCount(): Int = approvals(status = ApprovalStatus.PENDING).count { it.expiresAt > clock.now() }
    fun expireOverdue() { approvals(status = ApprovalStatus.PENDING).filter { it.expiresAt <= clock.now() }.forEach { expire(it.id) } }

    fun grants(onlyActive: Boolean = true, limit: Int = 200): List<Grant> {
        val now = clock.now()
        return db.query("SELECT id, tool_id, scope, resources, task_id, kind, uses_left, created_at, expires_at, revoked_at, approval_id, note FROM permission_grants ORDER BY created_at DESC LIMIT $limit") { r ->
            Grant(r.str(0), r.str(1), r.str(2), runCatching { CluJson.parseToJsonElement(r.str(3)).jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList()), r.str(4), GrantKind.valueOf(r.str(5)),
                r.long(6)?.toInt(), r.lng(7), r.lng(8), r.long(9), r.str(10), r.str(11)) }.filter { !onlyActive || it.active(now) }
    }

    private fun findGrant(r: AuthRequest, onlyOnceAcknowledged: Boolean = false): Grant? {
        val now = clock.now()
        return grants().firstOrNull { g ->
            g.toolId == r.tool.id && g.scope == r.tool.scope && g.active(now) && (g.taskId.isEmpty() || g.taskId == r.taskId) &&
                (!onlyOnceAcknowledged || (g.kind == GrantKind.ONCE && g.taskId == r.taskId)) && (r.category != Category.C || g.kind == GrantKind.ONCE) &&
                covers(g.resources, r.resources)
        }
    }

    private fun consume(g: Grant) = db.execute("UPDATE permission_grants SET uses_left = 0, revoked_at = ? WHERE id = ?", listOf(clock.now(), g.id))
    private fun insertGrant(g: Grant) = db.execute("INSERT INTO permission_grants(id, tool_id, scope, resources, task_id, kind, uses_left, created_at, expires_at, revoked_at, approval_id, note) VALUES(?,?,?,?,?,?,?,?,?,NULL,?,?)",
        listOf(g.id, g.toolId, g.scope, CluJson.encodeToString(JsonArray.serializer(), JsonArray(g.resources.map(::JsonPrimitive))), g.taskId, g.kind.name, g.usesLeft, g.createdAt, g.expiresAt, g.approvalId, g.note))
    private fun rejectedBefore(taskId: String, callKey: String) = db.query("SELECT 1 FROM approvals WHERE task_id = ? AND call_key = ? AND status = 'REJECTED' LIMIT 1", listOf(taskId, callKey)) { 1 }.isNotEmpty()

    private fun readApproval(r: ai.clu.core.db.SqlRow) = ApprovalRequest(r.str(0), r.str(1), r.str(2), r.str(3), r.str(4), Category.valueOf(r.str(5)),
        runCatching { CluJson.parseToJsonElement(r.str(6)).jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList()), r.str(7), r.str(8), r.lng(9) != 0L, r.str(10), r.str(11), r.str(12), r.str(13),
        ApprovalStatus.valueOf(r.str(14)), r.string(15), r.lng(16), r.lng(17), r.long(18), r.string(19))

    companion object {
        private const val APPROVAL_SELECT = "SELECT id, task_id, step_id, tool_id, scope, category, resources, intent, why, reversible, on_success, on_failure, args_preview, call_key, status, decision_kind, created_at, expires_at, decided_at, decided_by FROM approvals"

        /** Every wanted resource is the same as, or inside (for folders written with a closing slash), one a grant names. */
        fun covers(granted: List<String>, wanted: List<String>): Boolean = wanted.isNotEmpty() && wanted.all { w ->
            granted.any { g -> g == w || ((g.endsWith("/") || g.endsWith("\\")) && w.startsWith(g)) } }

        fun callKey(toolId: String, args: JsonObject): String = MessageDigest.getInstance("SHA-256").digest("$toolId|${canonical(args)}".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        private fun canonical(e: JsonElement): String = when (e) {
            is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${canonical(it.value)}" }
            is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
            else -> e.toString()
        }
    }
}

/** What a plan intends to do with one tool, for approving a plan as a whole. */
data class PlannedAction(val stepId: String, val toolId: String, val scope: String, val category: Category, val resources: List<String>)
