package ai.clu.core.agents

import ai.clu.core.util.CluJson
import kotlinx.serialization.json.*

/** How risky an action is, which decides how it is permitted. */
enum class Category(val label: String, val meaning: String) {
    A("Safe", "Reads or looks things up inside what CLU is already allowed to see. Done automatically and recorded."),
    B("Needs approval", "Creates or changes something, sends data out, or uses the network. Needs your approval (once, or for one task)."),
    C("Needs explicit confirmation", "Deletes or overwrites data, runs programs, or cannot be undone. Needs your confirmation every single time."),
}

/** The logical roles agents work in. They are not separate models: every reasoning step goes through the one shared inference service. */
enum class AgentRole(val id: String, val title: String, val purpose: String) {
    PLANNER("planner", "Planner", "Breaks a goal into a short plan of steps. It has no tools: it only proposes."),
    RESEARCH("research", "Research", "Looks things up on the web and summarises what it found, with sources."),
    KNOWLEDGE("knowledge", "Knowledge", "Searches your memories and documents and answers from them. May suggest (never save) new memories."),
    TOOL("tool", "Computer", "Works with files in the folders you allowed, and runs allowed programs. Every change goes through the permission broker."),
    VERIFIER("verifier", "Verifier", "Checks that a step really did what it said (read-only), before the task goes on.");
    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
}

/** One field of a tool's input or output. Unknown fields are rejected; nothing is coerced silently. */
data class Field(val type: String, val required: Boolean = false, val maxLength: Int = 4000, val min: Long? = null, val max: Long? = null,
                 val choices: List<String>? = null, val itemType: String? = null, val maxItems: Int = 20, val description: String = "")

class SchemaViolation(message: String) : Exception(message)

object SchemaValidator {
    /** Checks [value] against [schema]; returns the first problem, or null. */
    fun check(schema: Map<String, Field>, value: JsonObject, rejectUnknown: Boolean = true): String? {
        if (rejectUnknown) value.keys.firstOrNull { it !in schema }?.let { return "unexpected field \"$it\"" }
        for ((name, f) in schema) {
            val v = value[name]
            if (v == null || v is JsonNull) { if (f.required) return "missing required field \"$name\""; continue }
            when (f.type) {
                "string" -> { val p = v as? JsonPrimitive ?: return "\"$name\" must be text"; if (!p.isString) return "\"$name\" must be text"
                    if (p.content.length > f.maxLength) return "\"$name\" is too long (${p.content.length} > ${f.maxLength})"
                    f.choices?.let { if (p.content !in it) return "\"$name\" must be one of ${it.joinToString(", ")}" }
                    if (p.content.any { it.code == 0 }) return "\"$name\" contains a null character" }
                "integer" -> { val p = v as? JsonPrimitive ?: return "\"$name\" must be a whole number"; val n = if (p.isString) null else p.longOrNull ?: return "\"$name\" must be a whole number"
                    if (n == null) return "\"$name\" must be a whole number"
                    f.min?.let { if (n < it) return "\"$name\" must be at least $it" }; f.max?.let { if (n > it) return "\"$name\" must be at most $it" } }
                "number" -> { val p = v as? JsonPrimitive ?: return "\"$name\" must be a number"; if (p.isString || p.doubleOrNull == null) return "\"$name\" must be a number" }
                "boolean" -> { val p = v as? JsonPrimitive ?: return "\"$name\" must be true or false"; if (p.isString || p.booleanOrNull == null) return "\"$name\" must be true or false" }
                "array" -> { val a = v as? JsonArray ?: return "\"$name\" must be a list"; if (a.size > f.maxItems) return "\"$name\" has too many items (${a.size} > ${f.maxItems})"
                    if (f.itemType == "string") a.forEach { e -> val p = e as? JsonPrimitive; if (p == null || !p.isString || p.content.length > f.maxLength) return "\"$name\" must contain text items of at most ${f.maxLength} characters" } }
                "object" -> if (v !is JsonObject) return "\"$name\" must be an object"
                else -> return "unknown field type ${f.type}"
            }
        }
        return null
    }
}

/** What a person is shown before approving, and what the audit records. */
data class Intent(val summary: String, val reversible: Boolean, val onSuccess: String, val onFailure: String, val resources: List<String>)

/**
 * A tool's declaration. Everything a broker, the orchestrator and the window need to know about it is here: what it does,
 * what it accepts and returns, how risky it is, which permission scope it needs, how long it may run, whether it can be
 * stopped, where it runs, and what must be recorded.
 */
data class ToolSpec(
    val id: String, val description: String, val scope: String, val category: Category,
    val input: Map<String, Field>, val output: Map<String, Field>,
    val roles: Set<AgentRole>, val timeoutMs: Long, val cancellable: Boolean = true, val environment: String = "in-process",
    /** Input fields that may take text from an earlier step's output ("{{s1}}"); every other field must be literal, so data cannot steer what is done. */
    val dataFields: Set<String> = emptySet(),
    /** The output holds text from outside (files, web pages, documents): it is data for reading, never instructions. */
    val untrustedOutput: Boolean = false,
    /** What the audit keeps about the call: the input fields recorded in full (others are summarised). */
    val auditFields: Set<String> = emptySet(),
    val mutates: Boolean = false, val defaultEnabled: Boolean = true,
)

class ToolContext(val taskId: String, val stepId: String, val role: AgentRole, val isCancelled: () -> Boolean, val now: () -> Long)

class ToolFailure(message: String, val code: String = "failed") : Exception(message)

interface AgentTool {
    val spec: ToolSpec
    /** The category for these exact arguments (e.g. creating a file is B, replacing one is C). */
    fun classify(args: JsonObject): Category = spec.category
    /** The things this call touches (paths, URLs, queries), as the broker should check and the person should see them. */
    fun resources(args: JsonObject): List<String>
    fun intent(args: JsonObject): Intent
    suspend fun run(args: JsonObject, ctx: ToolContext): JsonObject
    /** After a mutating call: a read-only check that the effect really happened. Null = nothing to check. */
    fun verify(args: JsonObject, output: JsonObject): VerifyPlan? = null
}

/** A check made with read-only tools (by the Verifier role): call [tool] with [args]; [expect] decides whether the output proves the effect. */
class VerifyPlan(val tool: String, val args: JsonObject, val describe: String, val expect: (JsonObject) -> Boolean)

class AgentToolRegistry {
    private val tools = LinkedHashMap<String, AgentTool>()
    fun register(t: AgentTool) { require(t.spec.id !in tools) { "duplicate tool ${t.spec.id}" }; tools[t.spec.id] = t }
    operator fun get(id: String): AgentTool? = tools[id]
    fun all(): List<AgentTool> = tools.values.toList()
    fun ids(): Set<String> = tools.keys
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.toInt()
internal fun obj(vararg p: Pair<String, Any?>): JsonObject = JsonObject(p.associate { (k, v) -> k to when (v) {
    null -> JsonNull; is JsonElement -> v; is Boolean -> JsonPrimitive(v); is Number -> JsonPrimitive(v); is Collection<*> -> JsonArray(v.map { JsonPrimitive(it.toString()) }); else -> JsonPrimitive(v.toString()) } })
internal fun JsonObject.compact(): String = CluJson.encodeToString(JsonObject.serializer(), this)
