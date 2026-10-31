package ai.clu.desktop

import ai.clu.core.CluCore
import ai.clu.core.config.SettingResult
import ai.clu.core.config.Settings
import ai.clu.core.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Model management and the inference API over the local API. Everything here goes to the core's one inference service
 * and registry; nothing touches the native runtime directly.
 *
 * | Method | Path | |
 * |---|---|---|
 * | GET | /v1/models | registered models with metadata and memory estimates, the active one, the runtime state, the settings that shape loading |
 * | POST | /v1/models | `{path, name?, select?}` register a local GGUF file in place (the file is never copied or changed) |
 * | POST | /v1/models/{id}/select, /load, /benchmark | make it the default / select and load / benchmark it (a job) |
 * | DELETE | /v1/models/{id} | remove the registration; the file on disk is never deleted |
 * | GET, PUT | /v1/models/config | the settings that shape loading: contextLength, threads, batch, acceleration, gpuLayers, mmap, autoUnloadMin, maxTokens, fitContext |
 * | GET | /v1/models/benchmark/{job}, /v1/models/benchmarks | progress and result of a benchmark job / saved results |
 * | GET | /v1/inference/status | runtime state, backend, running and queued requests, limits |
 * | POST | /v1/inference/load, /unload | load the selected model (retrying after a failure) / release it |
 * | POST | /v1/inference/generate | `{messages:[{role,content}], maxTokens?, temperature?, topK?, topP?}`; SSE (queued, started, token, completed, failed) or one JSON reply |
 * | POST | /v1/inference/{id}/cancel | cancel one request |
 */
class ModelRoutes(private val core: CluCore, private val governor: ResourceGovernor, private val paths: DesktopPaths?) {
    private class Job(val id: String, val modelId: String) {
        @Volatile var step = "queued"; @Volatile var done = false; @Volatile var result: JsonObject? = null; @Volatile var error: String? = null
        @Volatile var cancelled = false
    }
    private val jobs = ConcurrentHashMap<String, Job>()
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "clu-benchmark").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    fun handle(c: Call): Boolean {
        val p = c.path
        if (!p.startsWith("/v1/models") && !p.startsWith("/v1/inference")) return false
        val model = Regex("^/v1/models/([^/]+)(?:/(select|load|benchmark))?$").find(p)
        val job = Regex("^/v1/models/benchmark/([^/]+)(/cancel)?$").find(p)
        val cancel = Regex("^/v1/inference/([^/]+)/cancel$").find(p)
        when {
            p == "/v1/models" && c.method == "GET" -> c.ok(overview())
            p == "/v1/models" && c.method == "POST" -> register(c)
            p == "/v1/models/config" && c.method == "GET" -> c.ok(configJson())
            p == "/v1/models/config" && c.method == "PUT" -> updateConfig(c)
            p == "/v1/models/benchmarks" && c.method == "GET" -> c.ok(buildJsonArray { paths?.let { ModelBenchmark.list(it) }.orEmpty().take(20).forEach { f ->
                runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull()?.let { r -> addJsonObject { put("file", f.name); put("result", r) } } } })
            job != null && c.method == "GET" -> jobStatus(c, job.groupValues[1])
            job != null && c.method == "POST" && job.groupValues[2].isNotEmpty() -> { jobs[job.groupValues[1]]?.cancelled = true; c.ok() }
            model != null && model.groupValues[1] !in setOf("config", "benchmark", "benchmarks") -> modelRoute(c, model.groupValues[1], model.groupValues[2])
            p == "/v1/inference/status" && c.method == "GET" -> c.ok(statusJson())
            p == "/v1/inference/load" && c.method == "POST" -> { runBlocking { core.inferenceService.load() }; c.ok(statusJson()) }
            p == "/v1/inference/unload" && c.method == "POST" -> { runBlocking { core.inferenceService.unload() }; c.ok(statusJson()) }
            p == "/v1/inference/generate" && c.method == "POST" -> generate(c)
            cancel != null && c.method == "POST" -> if (core.inferenceService.cancel(cancel.groupValues[1])) c.ok() else c.fail(404, "no such request (it may have finished)")
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------------------------------ models
    private fun modelRoute(c: Call, id: String, action: String) {
        val m = core.models.get(id)
        when {
            c.method == "DELETE" && action.isEmpty() -> {
                if (m == null) return c.fail(404, "no such model")
                // The registration goes; the file never does (ownedDir = null means "nothing here is ours to delete").
                if (core.settings.string(Settings.MODEL_ACTIVE) == id) { runBlocking { core.inferenceService.unload() }; core.settings.set(Settings.MODEL_ACTIVE, "") }
                core.models.remove(id, ownedDir = null)
                c.ok(buildJsonObject { put("removed", m.name); put("fileKept", m.path); put("fileStillExists", File(m.path).exists()) })
            }
            m == null -> c.fail(404, "no such model")
            c.method == "POST" && action == "select" -> { core.settings.set(Settings.MODEL_ACTIVE, id); c.ok(overview()) }
            c.method == "POST" && action == "load" -> {
                core.settings.set(Settings.MODEL_ACTIVE, id)
                val st = runBlocking { core.inferenceService.reload() }
                c.send(if (st.status == LlmStatus.READY) 200 else 422, buildJsonObject { put("state", stateJson(st)); put("model", modelJson(core.models.get(id) ?: m)); suggestion(st)?.let { put("suggestion", it) } })
            }
            c.method == "POST" && action == "benchmark" -> {
                val p = paths ?: return c.fail(404, "benchmarks need a data folder")
                if (jobs.values.any { !it.done }) return c.fail(409, "a benchmark is already running")
                val opts = ModelBenchmark.Options(tokens = (c.body["tokens"]?.jsonPrimitive?.intOrNull ?: 64).coerceIn(8, 512), repeats = (c.body["runs"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(1, 10),
                    promptTokens = (c.body["promptTokens"]?.jsonPrimitive?.intOrNull ?: 256).coerceIn(16, 4096), cpuSampleSeconds = (c.body["cpuSeconds"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(1, 30))
                val j = Job(ai.clu.core.util.newId(), id); jobs[j.id] = j
                pool.execute {
                    try {
                        j.result = ModelBenchmark.run(core, m, opts, { j.step = it }, { j.cancelled }).also { ModelBenchmark.save(p, it) }
                    } catch (e: ModelBenchmark.Cancelled) { j.error = "cancelled" } catch (e: Throwable) { j.error = e.message ?: e.toString() } finally { j.done = true; j.step = if (j.error != null) "failed" else "finished" }
                }
                c.send(202, buildJsonObject { put("job", j.id) })
            }
            else -> c.fail(405, "unsupported")
        }
    }

    private fun jobStatus(c: Call, id: String) {
        val j = jobs[id] ?: return c.fail(404, "no such benchmark job")
        c.ok(buildJsonObject { put("job", j.id); put("step", j.step); put("done", j.done); j.error?.let { put("error", it) }; j.result?.let { put("result", it) } })
    }

    private fun register(c: Call) {
        val path = c.str("path")?.trim()?.removeSurrounding("\"").orEmpty()
        if (path.isEmpty()) return c.fail(400, "path is required")
        val f = File(path)
        if (!f.isAbsolute) return c.fail(400, "give the full path of the .gguf file, e.g. D:\\models\\model.gguf")
        if (!f.exists()) return c.fail(404, "no such file: $path")
        if (!f.isFile) return c.fail(400, "that is a folder, not a model file")
        try {
            val e = core.models.register(f, c.str("name")?.trim()?.ifEmpty { null })
            if (c.body["select"]?.jsonPrimitive?.booleanOrNull == true) core.settings.set(Settings.MODEL_ACTIVE, e.id)
            c.send(201, modelJson(e))
        } catch (e: LlmException) {
            c.send(422, buildJsonObject { put("error", e.message ?: "not a compatible model"); put("suggestion", ModelRegistry.catalog.first().name) })
        }
    }

    // ------------------------------------------------------------------------------------------ config
    private data class Knob(val api: String, val key: String, val kind: String)
    private val knobs = listOf(Knob("contextLength", Settings.CONTEXT_LENGTH, "int"), Knob("threads", Settings.THREADS, "int"), Knob("batch", Settings.BATCH, "int"),
        Knob("acceleration", Settings.ACCELERATION, "string"), Knob("gpuLayers", Settings.GPU_LAYERS, "int"), Knob("mmap", Settings.USE_MMAP, "bool"),
        Knob("autoUnloadMin", Settings.AUTO_UNLOAD, "int"), Knob("maxTokens", Settings.MAX_TOKENS, "int"), Knob("fitContext", Settings.FIT_CONTEXT, "bool"))

    private fun configJson() = buildJsonObject {
        knobs.forEach { k ->
            val v = core.settings.raw(k.key).jsonPrimitive
            putJsonObject(k.api) {
                put("value", v); val d = Settings[k.key]!!; put("title", d.title); put("description", d.description)
                when (val t = d.type) {
                    is ai.clu.core.config.SettingType.IntRange -> { put("min", t.min); put("max", t.max) }
                    is ai.clu.core.config.SettingType.Choice -> putJsonArray("options") { t.options.forEach { (value, label) -> addJsonObject { put("value", value); put("label", label) } } }
                    else -> {}
                }
            }
        }
        put("autoThreads", core.platform.llm.autoThreads())
    }

    private fun updateConfig(c: Call) {
        val errors = mutableListOf<String>()
        for (k in knobs) {
            val v = c.body[k.api] ?: continue
            val r = when (k.kind) {
                "int" -> core.settings.set(k.key, v.jsonPrimitive.intOrNull ?: return c.fail(400, "${k.api} must be a whole number"))
                "bool" -> core.settings.set(k.key, v.jsonPrimitive.booleanOrNull ?: return c.fail(400, "${k.api} must be true or false"))
                else -> core.settings.set(k.key, v.jsonPrimitive.content)
            }
            when (r) { SettingResult.Ok -> {}; is SettingResult.Invalid -> errors += "${k.api}: ${r.reason}"; SettingResult.RequiresVerification -> errors += "${k.api}: needs verification"; else -> errors += "${k.api}: not accepted" }
        }
        if (errors.isNotEmpty()) c.fail(400, errors.joinToString("; ")) else c.ok(buildJsonObject { put("config", configJson()); put("note", "Applied. A loaded model reloads with the new values.") })
    }

    // ------------------------------------------------------------------------------------------ views
    private fun overview() = buildJsonObject {
        val active = core.settings.string(Settings.MODEL_ACTIVE); val st = core.llm.state.value
        putJsonArray("models") { core.models.list().forEach { add(modelJson(it, active, st)) } }
        put("active", active); put("state", stateJson(st)); put("config", configJson())
        putJsonObject("hardware") {
            val g = governor.snapshot()
            put("logicalCores", Runtime.getRuntime().availableProcessors()); put("autoThreads", core.platform.llm.autoThreads())
            put("memoryBudgetMB", g.getValue("budgetMB")); put("availableForModelMB", g.getValue("availableForModelMB")); put("systemTotalMB", g.getValue("systemTotalMB"))
            put("engine", ModelBenchmark.ENGINE)
        }
        putJsonArray("suggested") { ModelRegistry.catalog.forEach { m -> addJsonObject { put("name", m.name); put("approxBytes", m.approxBytes); put("notes", m.notes); put("url", m.url) } } }
        put("downloads", "CLU never downloads a model by itself. Download one from the address shown, then register the file here.")
    }

    private fun modelJson(e: ModelEntry, active: String = core.settings.string(Settings.MODEL_ACTIVE), st: LlmState = core.llm.state.value) = buildJsonObject {
        val ctx = core.settings.int(Settings.CONTEXT_LENGTH)
        val estimate = ModelRegistry.estimateRamBytes(e, ctx); val allowed = governor.availableForModel()
        put("id", e.id); put("name", e.name); put("path", e.path); put("file", File(e.path).name); put("inModelsFolder", paths != null && runCatching { File(e.path).canonicalFile.parentFile == paths.models.canonicalFile }.getOrDefault(false)); put("fileExists", File(e.path).exists()); put("sizeBytes", e.sizeBytes); put("format", e.format)
        put("architecture", e.info.architecture); put("quantization", e.info.quantization); put("parameters", e.info.parameterCountHint)
        put("trainContext", e.info.contextLength); put("layers", e.info.blockCount); put("embedding", e.info.embeddingLength); put("hasChatTemplate", e.info.hasChatTemplate)
        put("status", e.status.name); put("statusLabel", e.status.label); e.lastError?.let { put("lastError", it) }
        put("estimatedBytes", estimate); put("estimateContext", ctx); put("fitsNow", estimate <= allowed); put("allowedBytes", allowed)
        put("active", e.id == active); put("loaded", e.id == st.modelId && st.status in setOf(LlmStatus.READY, LlmStatus.GENERATING, LlmStatus.CANCELLING))
        if (e.status == ModelStatus.INCOMPATIBLE || e.status == ModelStatus.MISSING) put("suggestion", "Use ${ModelRegistry.catalog.first().name} (${ModelRegistry.catalog.first().approxBytes / 1_000_000} MB): ${ModelRegistry.catalog.first().notes}")
    }

    private fun suggestion(st: LlmState): String? = if (st.status == LlmStatus.ERROR) "If this model keeps failing, try ${ModelRegistry.catalog.first().name}: ${ModelRegistry.catalog.first().notes}" else null

    fun stateJson(s: LlmState) = buildJsonObject {
        put("status", s.status.name); put("modelId", s.modelId ?: ""); put("name", s.modelName ?: ""); put("message", s.message); put("note", s.note)
        put("contextSize", s.contextSize); put("loadMs", s.loadMs); put("backend", s.backend); put("threads", s.threads); put("gpuLayers", s.gpuLayers); put("estimatedBytes", s.estimatedBytes)
    }

    private fun statusJson(): JsonObject {
        val s = core.inferenceService.snapshot()
        fun ri(r: RequestInfo) = buildJsonObject { put("id", r.id); put("owner", r.owner); put("state", r.state); put("tokens", r.tokens); put("queuedAt", r.queuedAt); r.startedAt?.let { put("startedAt", it) } }
        return buildJsonObject {
            put("state", stateJson(s.state)); s.running?.let { put("running", ri(it)) }
            putJsonArray("queued") { s.queued.forEach { add(ri(it)) } }
            putJsonObject("limits") { put("maxQueued", s.maxQueued); put("firstTokenTimeoutS", s.firstTokenTimeoutS); put("stallTimeoutS", s.stallTimeoutS); put("totalTimeoutS", s.totalTimeoutS) }
            put("processMB", governor.snapshot().getValue("processMB"))
            suggestion(s.state)?.let { put("suggestion", it) }
        }
    }

    // ------------------------------------------------------------------------------------------ generate
    private fun generate(c: Call) {
        val msgs = (c.body["messages"] as? JsonArray)?.mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val role = o["role"]?.jsonPrimitive?.contentOrNull; val content = o["content"]?.jsonPrimitive?.contentOrNull
            if (role in setOf("system", "user", "assistant") && content != null) ChatMessage(role!!, content) else null
        }.orEmpty()
        if (msgs.isEmpty() || msgs.last().role != "user") return c.fail(400, "messages must end with a user message (roles: system, user, assistant)")
        fun num(k: String) = c.body[k]?.jsonPrimitive?.doubleOrNull
        val def = GenParams()
        val params = def.copy(maxTokens = (num("maxTokens")?.toInt() ?: core.settings.int(Settings.MAX_TOKENS)).coerceIn(1, 4096), temperature = (num("temperature") ?: def.temperature.toDouble()).coerceIn(0.0, 2.0).toFloat(),
            topK = (num("topK")?.toInt() ?: def.topK).coerceIn(0, 200), topP = (num("topP") ?: def.topP.toDouble()).coerceIn(0.0, 1.0).toFloat())
        val request = InferenceRequest(msgs, params, owner = "api")
        val stream = c.ex.requestHeaders.getFirst("Accept")?.contains("text/event-stream") == true
        if (!stream) {
            val r = runBlocking { runCatching { core.inferenceService.complete(request) } }
            val ok = r.getOrNull()
            return if (ok != null) c.ok(buildJsonObject { put("requestId", ok.requestId); put("text", ok.text); put("outcome", ok.outcome.name); put("promptTokens", ok.stats.promptTokens); put("generatedTokens", ok.stats.generatedTokens)
                put("firstTokenMs", ok.stats.firstTokenMs); put("tokensPerSecond", ok.stats.tokensPerSecond); put("droppedMessages", ok.droppedMessages) })
            else (r.exceptionOrNull() as? InferenceException).let { e -> c.send(if (e?.error == InferenceError.BUSY) 429 else 503, buildJsonObject { put("requestId", request.id); put("error", e?.error?.name ?: "FAILED"); put("message", e?.message ?: r.exceptionOrNull()?.message ?: "failed") }) }
        }
        c.ex.responseHeaders.apply { set("Content-Type", "text/event-stream; charset=utf-8"); set("Cache-Control", "no-store"); set("X-Content-Type-Options", "nosniff"); set("X-Request-Id", request.id) }
        c.ex.sendResponseHeaders(200, 0)
        c.ex.responseBody.use { out ->
            fun event(name: String, data: JsonElement) { out.write("event: $name\ndata: $data\n\n".toByteArray()); out.flush() }
            try {
                runBlocking {
                    core.inferenceService.generate(request).collect { e ->
                        when (e) {
                            is InferenceEvent.Queued -> event("queued", buildJsonObject { put("requestId", e.requestId); put("position", e.position) })
                            is InferenceEvent.Started -> event("started", buildJsonObject { put("requestId", e.requestId) })
                            is InferenceEvent.Token -> event("token", buildJsonObject { put("text", e.text) })
                            is InferenceEvent.Completed -> event("completed", buildJsonObject { put("requestId", e.requestId); put("text", e.result.text); put("outcome", e.outcome.name)
                                put("promptTokens", e.result.stats.promptTokens); put("generatedTokens", e.result.stats.generatedTokens); put("firstTokenMs", e.result.stats.firstTokenMs); put("tokensPerSecond", e.result.stats.tokensPerSecond) })
                            is InferenceEvent.Failed -> event("failed", buildJsonObject { put("requestId", e.requestId); put("error", e.error.name); put("message", e.message) })
                        }
                    }
                }
            } catch (_: java.io.IOException) { core.inferenceService.cancel(request.id) } // the client went away: the request stops with it
        }
    }
}
