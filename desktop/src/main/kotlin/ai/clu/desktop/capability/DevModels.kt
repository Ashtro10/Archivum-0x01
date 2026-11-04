package ai.clu.desktop.capability

import ai.clu.core.CluCore
import ai.clu.core.evolution.DevModel
import ai.clu.core.tools.HttpFetcher
import ai.clu.core.util.CluJson
import kotlinx.serialization.json.*

/**
 * Which model CLU uses for development work. The local model (CLU's own, through the shared inference service) is the
 * default. A remote model is an option the owner turns on explicitly: an OpenAI-compatible endpoint (for example
 * Ollama Cloud's free tier, `https://ollama.com/v1`) with three separate confirmations kept in the registry:
 * `dev.remote.enabled`, `dev.remote.free` (the owner states the endpoint costs nothing; CLU cannot see billing, so it
 * never assumes) and `dev.remote.share` (the owner allows development context to be sent). Without all three no
 * request leaves the computer. The choice is made on measured results per task (how often each model's output passed
 * validation), not on parameter count.
 */
class DevModels(private val core: CluCore, private val store: CapabilityStore, private val http: HttpFetcher, private val local: DevModel?, private val secret: () -> String?) {
    data class Candidate(val id: String, val model: DevModel, val remote: Boolean)
    data class Availability(val id: String, val available: Boolean, val reason: String, val remote: Boolean, val score: CapabilityStore.ModelScore?)

    fun remoteAllowed(): String? = when {
        store.pref("dev.remote.enabled") != "true" -> "remote models are off (the default)"
        store.pref("dev.remote.free") != "true" -> "the owner has not confirmed that the remote endpoint is free"
        store.pref("dev.remote.share") != "true" -> "the owner has not allowed development context to be sent"
        store.pref("dev.remote.url").isNullOrBlank() || store.pref("dev.remote.model").isNullOrBlank() -> "no remote endpoint or model is configured"
        else -> null
    }

    private fun remote(): Candidate? {
        if (remoteAllowed() != null) return null
        val url = store.pref("dev.remote.url")!!; val model = store.pref("dev.remote.model")!!
        return Candidate("remote:$model", RemoteDevModel(http, url, model, secret), true)
    }

    fun candidates(): List<Candidate> = listOfNotNull(local?.let { Candidate("local:" + it.description, it, false) }, remote())

    /** The best candidate for [task] by measured validation rate (a model with no record yet is tried; ties go to the local one). */
    fun choose(task: String): Candidate? = candidates().maxWithOrNull(compareBy<Candidate>({ c ->
        store.score(c.id, task)?.let { s -> if (s.attempts == 0) 0.5 else s.successes.toDouble() / s.attempts } ?: 0.5 }, { if (it.remote) 0 else 1 }))

    fun availability(task: String): List<Availability> = buildList {
        val st = core.llm.state.value
        add(Availability("local:" + (local?.description ?: "none"), local != null && core.models.get(core.settings.string(ai.clu.core.config.Settings.MODEL_ACTIVE)) != null,
            if (local == null) "no local model service" else if (core.models.get(core.settings.string(ai.clu.core.config.Settings.MODEL_ACTIVE)) == null) "no model selected" else st.status.name.lowercase(),
            false, store.score("local:" + (local?.description ?: ""), task)))
        val why = remoteAllowed()
        add(Availability("remote:" + (store.pref("dev.remote.model") ?: "none"), why == null, why ?: "configured", true, store.pref("dev.remote.model")?.let { store.score("remote:$it", task) }))
    }
}

/** An OpenAI-compatible chat endpoint (Ollama Cloud, a self-hosted server…), used only when [DevModels] allows it. */
class RemoteDevModel(private val http: HttpFetcher, private val base: String, private val model: String, private val key: () -> String?) : DevModel {
    override val description = "$model @ ${base.substringAfter("://").substringBefore('/')}"
    @Volatile override var lastError: String? = null; private set
    override suspend fun complete(system: String, user: String, maxTokens: Int, temperature: Float, seed: Int, grammar: String?, reasoning: Boolean): String? {
        ai.clu.core.livekit.EndpointPolicy.check(base, false)?.let { lastError = "endpoint rejected: $it"; return null }
        val body = buildJsonObject {
            put("model", model); put("max_tokens", maxTokens); put("temperature", temperature); put("seed", seed)
            putJsonArray("messages") { addJsonObject { put("role", "system"); put("content", system) }; addJsonObject { put("role", "user"); put("content", user) } }
        }
        val headers = buildMap { put("Content-Type", "application/json"); key()?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") } }
        val url = base.trimEnd('/').let { if (it.endsWith("/v1")) "$it/chat/completions" else "$it/v1/chat/completions" }
        return try {
            val r = http.postJson(url, body.toString(), headers)
            if (r.code != 200) { lastError = "HTTP ${r.code}"; null }
            else CluJson.parseToJsonElement(r.body).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) { lastError = e.message; null }
    }
}
