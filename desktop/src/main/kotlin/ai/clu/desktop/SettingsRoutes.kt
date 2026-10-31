package ai.clu.desktop

import ai.clu.core.CluCore
import ai.clu.core.config.SettingCategory
import ai.clu.core.config.SettingResult
import ai.clu.core.config.SettingType
import ai.clu.core.config.Settings
import kotlinx.serialization.json.*
import java.io.File

/** host.json, held in memory and written back on change; the governor takes new limits at once. */
class HostConfigStore(private val file: File, initial: HostConfig, private val governor: () -> ResourceGovernor?) {
    @Volatile var current: HostConfig = initial
        private set

    @Synchronized fun update(next: HostConfig): HostConfig {
        val v = next.validated()
        file.writeText(kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }.encodeToString(HostConfig.serializer(), v) + "\n")
        current = v
        governor()?.config = v.governor()
        return v
    }
}

/**
 * Settings over the local API: CLU's own settings (the same registry, validation and rules as on the phone) and this
 * PC's host.json. Settings marked sensitive need owner verification, which arrives with the permission broker (M6):
 * until then they are shown but refused here.
 *
 * | Method | Path | |
 * |---|---|---|
 * | GET | /v1/settings | categories and every setting with its type, value and default |
 * | PUT | /v1/settings | `{key, value}` |
 * | GET, PUT | /v1/host-config | host.json (`port` changes apply at the next start) |
 */
class SettingsRoutes(private val core: CluCore, private val governor: ResourceGovernor, private val host: HostConfigStore?) {
    fun handle(c: Call): Boolean {
        when {
            c.path == "/v1/settings" && c.method == "GET" -> c.ok(buildJsonObject {
                putJsonArray("categories") { SettingCategory.entries.forEach { addJsonObject { put("id", it.name); put("title", it.title) } } }
                putJsonArray("settings") { Settings.all.forEach { d -> addJsonObject {
                    put("key", d.key); put("title", d.title); put("description", d.description); put("category", d.category.name)
                    put("value", core.settings.raw(d.key)); put("default", d.default); put("sensitive", d.sensitive); put("advanced", d.advanced)
                    put("type", type(d.type))
                } } }
            })
            c.path == "/v1/settings" && c.method == "PUT" -> {
                val key = c.str("key") ?: return true.also { c.fail(400, "key is required") }
                val value = c.body["value"] ?: return true.also { c.fail(400, "value is required") }
                when (val r = core.settings.set(key, value)) {
                    SettingResult.Ok -> c.ok(buildJsonObject { put("ok", true); put("value", core.settings.raw(key)) })
                    is SettingResult.Invalid -> c.fail(400, r.reason)
                    SettingResult.RequiresVerification -> c.fail(403, "changing this needs owner verification, which comes with the permission broker (M6); change it on the phone for now")
                    SettingResult.UnknownSetting -> c.fail(404, "no such setting")
                }
            }
            c.path == "/v1/host-config" && c.method == "GET" -> {
                val h = host ?: return true.also { c.fail(404, "host config not available") }
                c.ok(Json.encodeToJsonElement(HostConfig.serializer(), h.current))
            }
            c.path == "/v1/host-config" && c.method == "PUT" -> {
                val h = host ?: return true.also { c.fail(404, "host config not available") }
                val merged = JsonObject(Json.encodeToJsonElement(HostConfig.serializer(), h.current).jsonObject + c.body)
                runCatching { h.update(Json.decodeFromJsonElement(HostConfig.serializer(), merged)) }
                    .onSuccess { c.ok(buildJsonObject { put("ok", true); put("config", Json.encodeToJsonElement(HostConfig.serializer(), it)); put("governor", governorJson()) }) }
                    .onFailure { c.fail(400, it.message ?: "invalid") }
            }
            else -> return false
        }
        return true
    }

    private fun governorJson() = buildJsonObject { governor.snapshot().forEach { (k, v) -> put(k, v) } }

    private fun type(t: SettingType): JsonObject = buildJsonObject {
        when (t) {
            SettingType.Bool -> put("kind", "bool")
            is SettingType.IntRange -> { put("kind", "int"); put("min", t.min); put("max", t.max) }
            is SettingType.FloatRange -> { put("kind", "float"); put("min", t.min); put("max", t.max) }
            is SettingType.Choice -> { put("kind", "choice"); putJsonArray("options") { t.options.forEach { (v, l) -> addJsonObject { put("value", v); put("label", l) } } } }
            is SettingType.Text -> { put("kind", if (t.multiline) "multiline" else "text"); put("maxLength", t.maxLength) }
            SettingType.TimeOfDay -> put("kind", "time")
            is SettingType.TextList -> { put("kind", "list"); put("maxItems", t.maxItems) }
            is SettingType.Structured -> put("kind", "structured")
        }
    }
}
