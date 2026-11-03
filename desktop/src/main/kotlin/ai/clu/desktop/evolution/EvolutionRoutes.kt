package ai.clu.desktop.evolution

import ai.clu.core.CluCore
import ai.clu.core.config.Settings
import ai.clu.core.evolution.RepairRecord
import ai.clu.core.llm.InferenceRequest
import ai.clu.core.llm.LlmStatus
import ai.clu.desktop.Call
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File

/** CLU's own model, used through the shared inference service (the same queue, limits and watchdog as chat). */
class InferenceRepairModel(private val core: CluCore) : RepairModel {
    override val description: String get() = core.models.get(core.settings.string(Settings.MODEL_ACTIVE))?.let { "${it.name} (${File(it.path).name})" } ?: "no model selected"
    @Volatile override var lastError: String? = null; private set
    override suspend fun complete(system: String, user: String, maxTokens: Int): String? = try {
        val st = core.inferenceService.load()
        if (st.status != LlmStatus.READY) { lastError = "the model is not ready (${st.status}${st.message.ifBlank { null }?.let { ": $it" } ?: ""})"; null }
        else core.inferenceService.complete(InferenceRequest(SelfRepairService.modelMessages(system, user), SelfRepairService.PATCH_PARAMS.copy(maxTokens = maxTokens),
            owner = "self-repair", fitContext = false)).text
    } catch (e: Exception) { lastError = "${e::class.simpleName}: ${e.message}"; null }
}

/**
 * The self-repair API (owner token required, like every /v1 route):
 *
 * | | |
 * |---|---|
 * | GET /v1/evolution/status | connected repository, index size, last reconcile, current run, counts |
 * | POST /v1/evolution/repo | `{path}` connect CLU's source repository (re-indexes) |
 * | POST /v1/evolution/reconcile | look for external changes now |
 * | POST /v1/evolution/ask | `{question}` answer from the source index |
 * | POST /v1/evolution/run | `{module, tests, patch?, patchSource?, useModel?}` start a self-repair run (background) |
 * | GET /v1/evolution/incidents, /repairs, /history | records |
 * | GET /v1/evolution/repairs/{id} | the record; `?format=report` the Markdown report |
 * | POST /v1/evolution/repairs/{id}/approve, /reject | the owner's decision (`{reason}` for reject) |
 */
class EvolutionRoutes(private val svc: SelfRepairService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastOutcome: SelfRepairService.Outcome? = null

    fun handle(c: Call): Boolean {
        val p = c.path
        if (!p.startsWith("/v1/evolution")) return false
        val repair = Regex("^/v1/evolution/repairs/([^/]+)(?:/(approve|reject))?$").find(p)
        try {
            when {
                p == "/v1/evolution/status" && c.method == "GET" -> c.ok(status())
                p == "/v1/evolution/repo" && c.method == "POST" -> { val path = c.str("path") ?: return true.also { c.fail(400, "path is required") }
                    val r = svc.connect(File(path)); c.ok(buildJsonObject { put("message", r.describe()); put("head", r.head) }) }
                p == "/v1/evolution/reconcile" && c.method == "POST" -> c.ok(buildJsonObject { put("message", svc.reconcile().describe()) })
                p == "/v1/evolution/ask" && c.method == "POST" -> { val q = c.str("question") ?: return true.also { c.fail(400, "question is required") }
                    val a = svc.knowledge.answer(q); c.ok(buildJsonObject { put("answer", a.text); put("kind", a.kind); putJsonArray("citations") { a.citations.forEach { add(it) } } }) }
                p == "/v1/evolution/run" && c.method == "POST" -> {
                    val opt = SelfRepairService.Options(module = c.str("module") ?: "core", tests = c.str("tests"), patch = c.str("patch"),
                        patchSource = c.str("patchSource") ?: "external", useModel = c.body["useModel"]?.jsonPrimitive?.booleanOrNull ?: true)
                    if (opt.module !in setOf("core", "desktop", "app")) return true.also { c.fail(400, "module must be core, desktop or app") }
                    scope.launch { lastOutcome = svc.run(opt) }
                    c.send(202, buildJsonObject { put("started", true) })
                }
                p == "/v1/evolution/incidents" && c.method == "GET" -> c.ok(buildJsonArray { svc.incidents.list().forEach { i -> addJsonObject {
                    put("id", i.id); put("kind", i.kind); put("title", i.title); put("status", i.status); put("count", i.count); put("lastSeen", i.lastSeen) } } })
                p == "/v1/evolution/repairs" && c.method == "GET" -> c.ok(buildJsonArray { svc.repairs.list().forEach { add(summary(it)) } })
                p == "/v1/evolution/history" && c.method == "GET" -> c.ok(buildJsonArray { svc.store.recentHistory(100).forEach { e -> addJsonObject {
                    put("at", e.at); put("kind", e.kind); put("ref", e.ref); put("summary", e.summary) } } })
                repair != null && repair.groupValues[2].isEmpty() && c.method == "GET" -> {
                    val r = svc.repairs.get(repair.groupValues[1]) ?: return true.also { c.fail(404, "no such repair") }
                    if (c.query("format") == "report") c.ok(buildJsonObject { put("report", svc.report(r.id).orEmpty()) })
                    else c.ok(summary(r).let { s -> JsonObject(s + mapOf("diff" to JsonPrimitive(r.diff), "commands" to buildJsonArray { svc.store.commands(r.id).forEach { cmd -> addJsonObject {
                        put("phase", cmd.phase); put("command", cmd.command); put("exitCode", cmd.exitCode); put("durationMs", cmd.durationMs); put("timedOut", cmd.timedOut) } } })) })
                }
                repair != null && repair.groupValues[2] == "approve" && c.method == "POST" -> outcome(c, svc.approve(repair.groupValues[1]))
                repair != null && repair.groupValues[2] == "reject" && c.method == "POST" -> outcome(c, svc.reject(repair.groupValues[1], c.str("reason")?.take(500) ?: "no reason given"))
                else -> c.fail(404, "no such endpoint")
            }
        } catch (e: GitException) { c.fail(409, e.message ?: "git failed") }
        return true
    }

    private fun outcome(c: Call, o: SelfRepairService.Outcome) = c.ok(buildJsonObject { put("message", o.message); o.repair?.let { put("repair", summary(it)) } })

    private fun status() = buildJsonObject {
        put("repository", svc.repoRoot()?.path)
        put("indexedFiles", svc.indexer.count()); put("indexedHead", svc.indexer.indexedHead())
        put("lastReconcile", svc.reconciler.lastSnapshotAt())
        put("progress", svc.progress)
        put("awaitingApproval", svc.repairs.count(RepairRecord.AWAITING_APPROVAL))
        put("openIncidents", svc.incidents.list("open").size)
        lastOutcome?.let { put("lastRun", it.message) }
    }

    private fun summary(r: RepairRecord) = buildJsonObject {
        put("id", r.id); put("state", r.state); put("source", r.source); put("incidentId", r.incidentId); put("branch", r.branch); put("targetBranch", r.targetBranch)
        put("baseCommit", r.baseCommit); put("patchCommit", r.patchCommit); put("files", JsonArray(r.files.map { JsonPrimitive(it) }))
        put("verdict", r.verdict?.outcome); put("verification", r.verdict?.summary); put("outcome", r.outcome); put("createdAt", r.createdAt)
    }
}
