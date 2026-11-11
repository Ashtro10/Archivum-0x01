package ai.clu.core.api

import ai.clu.core.agent.AgentEvent
import ai.clu.core.conversation.Message
import kotlinx.serialization.json.*

/**
 * The JSON the CLU shell reads, in one place for the PC window and the tablet: messages, and the server-sent events of
 * a streamed turn (status, text, error, suggestion, action, done). Same names and fields on both, so the same shell
 * code runs on both.
 */
object ShellApi {
    fun message(m: Message) = buildJsonObject {
        put("id", m.id); put("role", m.role.name.lowercase()); put("content", m.content); put("createdAt", m.createdAt)
        if (m.meta.error.isNotEmpty()) put("error", m.meta.error)
        putJsonArray("sources") { m.meta.sources.forEach { s -> addJsonObject { put("title", s.title); put("url", s.url) } } }
    }

    /** The event a streamed turn sends for [e], or null for events the shell doesn't show. */
    fun event(e: AgentEvent): Pair<String, JsonObject>? = when (e) {
        is AgentEvent.Status -> "status" to buildJsonObject { put("text", e.text) }
        is AgentEvent.Text -> "text" to buildJsonObject { put("delta", e.delta) }
        is AgentEvent.Error -> "error" to buildJsonObject { put("text", e.text); put("kind", e.kind) }
        is AgentEvent.ProposalCreated -> "suggestion" to buildJsonObject { put("id", e.proposal.id); put("text", e.proposal.text) }
        // A capability instruction's result for the window: open this experience, ask which one, show progress or a list.
        is AgentEvent.ToolFinished -> if (e.tool == ai.clu.core.capability.CapabilityCommand.TOOL) (e.result.data as? JsonObject)?.let { "action" to it } else null
        is AgentEvent.Done -> "done" to done(e.text, e.meta.error, e.meta.interrupted, e.message?.id ?: "", e.meta.sources.map { it.title to it.url })
        else -> null
    }

    fun done(text: String, error: String = "", interrupted: Boolean = false, messageId: String = "", sources: List<Pair<String, String>> = emptyList()) = buildJsonObject {
        put("text", text); put("error", error); put("interrupted", interrupted); put("messageId", messageId)
        putJsonArray("sources") { sources.forEach { (t, u) -> addJsonObject { put("title", t); put("url", u) } } }
    }

    /** Every setting with its category, value and type: the Settings view on both hosts. */
    fun settings(settings: ai.clu.core.config.SettingsStore) = buildJsonObject {
        putJsonArray("categories") { ai.clu.core.config.SettingCategory.entries.forEach { addJsonObject { put("id", it.name); put("title", it.title) } } }
        putJsonArray("settings") { ai.clu.core.config.Settings.all.forEach { d -> addJsonObject {
            put("key", d.key); put("title", d.title); put("description", d.description); put("category", d.category.name)
            put("value", settings.raw(d.key)); put("default", d.default); put("sensitive", d.sensitive); put("advanced", d.advanced)
            put("type", type(d.type))
        } } }
    }

    fun type(t: ai.clu.core.config.SettingType): JsonObject = buildJsonObject {
        when (t) {
            ai.clu.core.config.SettingType.Bool -> put("kind", "bool")
            is ai.clu.core.config.SettingType.IntRange -> { put("kind", "int"); put("min", t.min); put("max", t.max) }
            is ai.clu.core.config.SettingType.FloatRange -> { put("kind", "float"); put("min", t.min); put("max", t.max) }
            is ai.clu.core.config.SettingType.Choice -> { put("kind", "choice"); putJsonArray("options") { t.options.forEach { (v, l) -> addJsonObject { put("value", v); put("label", l) } } } }
            is ai.clu.core.config.SettingType.Text -> { put("kind", if (t.multiline) "multiline" else "text"); put("maxLength", t.maxLength) }
            ai.clu.core.config.SettingType.TimeOfDay -> put("kind", "time")
            is ai.clu.core.config.SettingType.TextList -> { put("kind", "list"); put("maxItems", t.maxItems) }
            is ai.clu.core.config.SettingType.Structured -> put("kind", "structured")
        }
    }

    /** One server-sent event, as written on the wire. */
    fun sse(name: String, data: JsonElement) = "event: $name\ndata: $data\n\n".toByteArray()
}
