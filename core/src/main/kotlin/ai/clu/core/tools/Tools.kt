package ai.clu.core.tools

import ai.clu.core.config.*
import ai.clu.core.db.CluStore
import ai.clu.core.db.lng
import ai.clu.core.db.str
import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.persona.ActivePersona
import ai.clu.core.security.Actor
import ai.clu.core.security.AuthDecision
import ai.clu.core.security.AuthorizationPolicy
import ai.clu.core.security.Sensitivity
import ai.clu.core.util.CluJson
import ai.clu.core.util.newId
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

enum class NetCategory(val settingKey: String, val label: String) {
    WEB(Settings.NET_WEB, "web research"),
    MAPS(Settings.NET_MAPS, "maps"),
    TRAVEL(Settings.NET_TRAVEL, "travel information"),
    EXTERNAL_AI(Settings.EXT_AI_MODE, "external AI"),
    DOWNLOADS(Settings.NET_DOWNLOADS, "downloads"),
}

/**
 * Outcome of a tool. `verified` means the tool checked that the intended effect actually happened (or that returned
 * data really came from the source) – the agent never reports unverified success as success.
 */
data class ToolResult(
    val success: Boolean,
    val verified: Boolean,
    /** Text given to the model / used in templates. */
    val summary: String,
    val data: JsonElement? = null,
    val error: String? = null,
    val needsVerification: Boolean = false,
    val needsConfirmation: Boolean = false,
    /** Text the agent may say directly without the LLM (deterministic device actions). */
    val spoken: String? = null,
    val usedNetwork: Boolean = false,
) {
    companion object {
        fun ok(summary: String, verified: Boolean = true, data: JsonElement? = null, spoken: String? = summary, usedNetwork: Boolean = false) =
            ToolResult(true, verified, summary, data, spoken = spoken, usedNetwork = usedNetwork)
        fun fail(error: String, spoken: String? = error) = ToolResult(false, false, error, error = error, spoken = spoken)
    }
}

data class ToolContext(val actor: Actor, val persona: ActivePersona, val conversationId: String?, val now: Long)

interface Tool {
    val name: String
    val description: String
    /** Example argument object shown to the model, e.g. {"app":"YouTube"}. */
    val argsExample: String
    val sensitivity: Sensitivity
    val network: NetCategory? get() = null
    /** Acts on the device (apps, alarms, hardware) – characters need explicit permission. */
    val deviceAction: Boolean get() = false
    /** Ask for confirmation by default (high-impact actions). */
    val confirmByDefault: Boolean get() = false
    suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    fun register(t: Tool) {
        tools[t.name] = t
        Settings.registerDynamic(SettingDef("tool.${t.name}.enabled", "Allow ${t.name}", SettingCategory.TOOLS, SettingType.Bool, jsonOf(true),
            t.description, "ToolExecutor", "When off, ${t.name} is refused", verifiedBy = "ToolSettingsTest#perToolEnable"))
        Settings.registerDynamic(SettingDef("tool.${t.name}.confirm", "Confirm before ${t.name}", SettingCategory.TOOLS,
            SettingType.Choice(listOf("default" to (if (t.confirmByDefault) "Default (ask)" else "Default (don't ask)"), "always" to "Always ask", "never" to "Never ask")),
            jsonOf("default"), "Confirmation requirement for ${t.name}.", "ToolExecutor", "Confirmation prompt before execution",
            verifiedBy = "ToolSettingsTest#perToolConfirm", advanced = true))
    }

    operator fun get(name: String): Tool? = tools[name]
    fun all(): List<Tool> = tools.values.toList()
}

/** Network permission + connectivity check. */
class NetworkPolicy(private val settings: SettingsStore, private val online: () -> Boolean) {
    fun check(cat: NetCategory): String? {
        if (!settings.bool(Settings.NET_ENABLED)) return "Internet access is turned off in settings."
        val allowed = if (cat == NetCategory.EXTERNAL_AI) settings.string(Settings.EXT_AI_MODE) != "off" else settings.bool(cat.settingKey)
        if (!allowed) return "Internet use for ${cat.label} is turned off in settings."
        if (!online()) return "No internet connection is available right now."
        return null
    }
    fun isOnline() = online()
}

/** Asks the owner to confirm a consequential action (UI dialog or spoken yes/no). */
fun interface ConfirmationHandler { suspend fun confirm(tool: Tool, args: JsonObject, description: String): Boolean }

data class ToolRun(val id: String, val tool: String, val args: String, val success: Boolean, val verified: Boolean, val output: String,
                   val error: String?, val startedAt: Long, val durationMs: Long, val actor: String)

class ToolExecutor(
    private val store: CluStore,
    private val registry: ToolRegistry,
    private val settings: SettingsStore,
    private val auth: AuthorizationPolicy,
    private val network: NetworkPolicy,
    private val diag: DiagnosticsLog?,
    var confirmation: ConfirmationHandler = ConfirmationHandler { _, _, _ -> false },
) {
    private val db = store.db

    fun has(name: String) = registry[name] != null
    fun networkCategory(name: String) = registry[name]?.network

    fun isEnabled(name: String) = runCatching { settings.bool("tool.$name.enabled") }.getOrDefault(true)

    fun available(persona: ActivePersona): List<Tool> = registry.all().filter { isEnabled(it.name) && personaAllows(persona, it) == null }

    private fun personaAllows(p: ActivePersona, t: Tool): String? {
        if (p.isClu) return null
        // The owner must always be able to leave a character, whatever that character's tool permissions are.
        if (t.name == "persona.switch") return null
        if (p.allowedTools?.contains(t.name) == true) return null
        if (t.deviceAction && p.canUseDeviceTools) return null
        return "${p.name} is not permitted to use ${t.name}."
    }

    suspend fun execute(name: String, args: JsonObject, ctx: ToolContext, confirmed: Boolean = false): ToolResult {
        val t0 = store.clock.now()
        val tool = registry[name] ?: return log(name, args, ctx, t0, ToolResult.fail("Unknown tool '$name'."))
        if (!isEnabled(name)) return log(name, args, ctx, t0, ToolResult.fail("The ${tool.name} tool is disabled in settings."))
        personaAllows(ctx.persona, tool)?.let { return log(name, args, ctx, t0, ToolResult.fail(it)) }
        if (tool.sensitivity == Sensitivity.PERSONAL && !ctx.persona.isClu && ctx.persona.readableScopes.isEmpty())
            return log(name, args, ctx, t0, ToolResult.fail("${ctx.persona.name} has no access to memories."))
        when (val d = auth.decide(ctx.actor, tool.sensitivity, if (tool.sensitivity == Sensitivity.ROUTINE) name else null)) {
            is AuthDecision.Deny -> return log(name, args, ctx, t0, ToolResult(false, false, "Not permitted: ${d.reason}", error = d.reason, spoken = auth.refusalLine()))
            is AuthDecision.NeedsVerification -> return ToolResult(false, false, "Owner verification required.", error = d.reason, needsVerification = true,
                spoken = "That requires verification.")
            AuthDecision.Allow -> {}
        }
        tool.network?.let { cat -> network.check(cat)?.let { return log(name, args, ctx, t0, ToolResult.fail(it)) } }
        val confirmMode = runCatching { settings.string("tool.$name.confirm") }.getOrDefault("default")
        val needConfirm = when (confirmMode) { "always" -> true; "never" -> false; else -> tool.confirmByDefault }
        if (needConfirm && !confirmed) {
            val ok = confirmation.confirm(tool, args, "${tool.description}: $args")
            if (!ok) return log(name, args, ctx, t0, ToolResult(false, false, "Cancelled: not confirmed.", error = "not confirmed", needsConfirmation = true,
                spoken = "Cancelled."))
        }
        val result = try {
            withTimeout(45_000) { tool.run(args, ctx) }
        } catch (e: TimeoutCancellationException) {
            ToolResult.fail("${tool.name} timed out.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            diag?.e("tool", "${tool.name} failed", e)
            ToolResult.fail("${tool.name} failed: ${e.message ?: e::class.simpleName}")
        }
        return log(name, args, ctx, t0, result)
    }

    private fun log(name: String, args: JsonObject, ctx: ToolContext, t0: Long, r: ToolResult): ToolResult {
        val now = store.clock.now()
        db.execute("INSERT INTO tool_runs(id, tool, args, success, verified, output, error, started_at, duration_ms, conversation_id, actor) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            listOf(newId(), name, args.toString().take(2000), r.success, r.verified, r.summary.take(4000), r.error, t0, now - t0, ctx.conversationId,
                ctx.actor.personId ?: ctx.actor.role?.name ?: "unknown"))
        diag?.log(if (r.success) ai.clu.core.diag.Level.INFO else ai.clu.core.diag.Level.WARN, "tool", "$name -> ${if (r.success) "ok" else "fail"}: ${r.summary.take(200)}")
        store.notifyChanged("tool_runs")
        return r
    }

    fun history(limit: Int = 200): List<ToolRun> = db.query(
        "SELECT id, tool, args, success, verified, output, error, started_at, duration_ms, actor FROM tool_runs ORDER BY started_at DESC LIMIT $limit") {
        ToolRun(it.str(0), it.str(1), it.str(2), (it.long(3) ?: 0) != 0L, (it.long(4) ?: 0) != 0L, it.str(5), it.string(6), it.lng(7), it.lng(8), it.str(9))
    }

    data class Reliability(val tool: String, val runs: Long, val failures: Long, val lastError: String?)

    /** Per-tool reliability (foundation for self-improvement suggestions). */
    fun reliability(): List<Reliability> = db.query(
        """SELECT tool, COUNT(*), SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END),
           (SELECT error FROM tool_runs t2 WHERE t2.tool = t.tool AND t2.success = 0 ORDER BY started_at DESC LIMIT 1)
           FROM tool_runs t GROUP BY tool ORDER BY tool""") { Reliability(it.str(0), it.lng(1), it.lng(2), it.string(3)) }

    fun purgeHistory() {
        val cutoff = store.clock.now() - settings.int(Settings.TOOL_HISTORY_DAYS) * 86_400_000L
        db.execute("DELETE FROM tool_runs WHERE started_at < ?", listOf(cutoff))
    }
}

/** Parses tool calls emitted by the model: <tool>{"name": "...", "args": {...}}</tool> */
object ToolCallParser {
    data class Call(val name: String, val args: JsonObject)

    private val re = Regex("<tool>\\s*(\\{.*?\\})\\s*</tool>", RegexOption.DOT_MATCHES_ALL)

    fun find(text: String): Call? {
        val m = re.find(text) ?: return null
        val obj = runCatching { CluJson.parseToJsonElement(m.groupValues[1]).jsonObject }.getOrNull() ?: return null
        val name = obj.str("name") ?: return null
        val args = obj["args"] as? JsonObject ?: JsonObject(emptyMap())
        return Call(name, args)
    }

    /** True if streamed text so far might be (the start of) a tool call and should not be shown/spoken yet. */
    fun maybeToolPrefix(text: String): Boolean {
        val t = text.trimStart()
        return t.isEmpty() || "<tool>".startsWith(t.take(6)) && t.length < 6 || t.startsWith("<tool>")
    }
}
