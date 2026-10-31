package ai.clu.desktop

import ai.clu.core.CluCore
import ai.clu.core.config.Settings
import ai.clu.core.llm.GenParams
import ai.clu.core.llm.ChatMessage
import ai.clu.core.llm.InferenceRequest
import ai.clu.core.llm.LlmStatus
import ai.clu.core.llm.ModelRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File

/**
 * Everything the first-run wizard and the Models view need that is not about the running model itself:
 *
 * | Method | Path | |
 * |---|---|---|
 * | GET | /v1/setup/status | has setup been completed; this PC's checks; suggested models with suitability; the owner's models; downloads |
 * | POST | /v1/setup/scan | `{folders?:[…]}` GGUF files found (not registered yet), with metadata |
 * | POST | /v1/setup/download | `{modelId, confirm:true, select?}` start or resume a download of a suggested model |
 * | GET | /v1/setup/downloads, POST /v1/setup/downloads/{id}/cancel | progress / stop (the part is kept for resuming) |
 * | POST | /v1/setup/test | a short real generation with the selected model: text, first-token time, speed, backend |
 * | POST | /v1/setup/complete, /v1/setup/reopen | finish the wizard (applies the chosen settings) / show it again |
 * | GET, PUT | /v1/app/config | run in the background when the window closes; start with Windows |
 * | POST | /v1/models/{id}/delete-file | `{confirm:true}` delete a model file CLU downloaded (never any other file) |
 */
class SetupRoutes(private val core: CluCore, private val governor: ResourceGovernor, private val paths: DesktopPaths, private val platform: DesktopLlmPlatform,
                  private val downloader: ModelDownloader, private val autostart: Autostart, private val hostConfig: HostConfigStore?,
                  private val probe: SystemProbe = SystemProbe(paths, platform, governor)) {

    private fun completed() = core.store.meta(SETUP_KEY) != null

    fun handle(c: Call): Boolean {
        val p = c.path
        val dl = Regex("^/v1/setup/downloads/([^/]+)/cancel$").find(p)
        val del = Regex("^/v1/models/([^/]+)/delete-file$").find(p)
        when {
            p == "/v1/setup/status" && c.method == "GET" -> c.ok(status())
            p == "/v1/setup/scan" && c.method == "POST" -> scan(c)
            p == "/v1/setup/download" && c.method == "POST" -> download(c)
            p == "/v1/setup/downloads" && c.method == "GET" -> c.ok(buildJsonArray { downloader.list().forEach { applyChosenSelection(it); add(jobJson(it)) } })
            dl != null && c.method == "POST" -> { downloader.cancel(dl.groupValues[1]); c.ok() }
            p == "/v1/setup/test" && c.method == "POST" -> test(c)
            p == "/v1/setup/complete" && c.method == "POST" -> complete(c)
            p == "/v1/setup/reopen" && c.method == "POST" -> { core.store.db.execute("DELETE FROM kv_meta WHERE key = ?", listOf(SETUP_KEY)); c.ok() }
            p == "/v1/app/config" && c.method == "GET" -> c.ok(appConfig())
            p == "/v1/app/config" && c.method == "PUT" -> appConfigUpdate(c)
            del != null && c.method == "POST" -> deleteFile(c, del.groupValues[1])
            else -> return false
        }
        return true
    }

    private fun status() = buildJsonObject {
        val sys = probe.snapshot(); val ctx = core.settings.int(Settings.CONTEXT_LENGTH)
        val g = governor.snapshot()
        put("completed", completed()); put("system", sys)
        putJsonArray("suggested") {
            ModelCatalog.models.forEach { m ->
                val (fit, why) = ModelCatalog.suitability(m, ctx, g.getValue("availableForModelMB") * 1_048_576, g.getValue("systemTotalMB") * 1_048_576)
                addJsonObject {
                    put("id", m.id); put("name", m.name); put("parameters", m.parameters); put("quantization", m.quantization); put("source", m.sourcePage); put("url", m.url); put("fileName", m.fileName)
                    put("sizeBytes", m.approxBytes); put("storageNeededBytes", (m.approxBytes * 11 / 10)); put("license", m.license); put("licenseUrl", m.licenseUrl); put("notes", m.notes)
                    put("estimatedRamBytes", m.estimateBytes(ctx)); put("estimateContext", ctx); put("suitability", fit); put("suitabilityDetail", why)
                    put("recommended", m.id == ModelCatalog.models.first().id && fit == "suitable"); put("present", File(paths.models, m.fileName).isFile)
                }
            }
        }
        putJsonArray("models") { core.models.list().forEach { m -> addJsonObject { put("id", m.id); put("name", m.name); put("path", m.path); put("sizeBytes", m.sizeBytes); put("quantization", m.info.quantization); put("status", m.status.name) } } }
        put("hasModel", core.models.list().isNotEmpty()); put("activeModel", core.settings.string(Settings.MODEL_ACTIVE))
        putJsonObject("defaults") { put("contextLength", 4096); put("threads", core.platform.llm.autoThreads()); put("acceleration", "cpu"); put("workspace", defaultWorkspace().absolutePath) }
        putJsonArray("downloads") { downloader.list().forEach { applyChosenSelection(it); add(jobJson(it)) } }
        putJsonArray("scanFolders") { LocalModelScanner.defaultFolders(paths).forEach { add(it.absolutePath) } }
    }

    private fun defaultWorkspace() = File(File(System.getProperty("user.home"), "Documents"), "CLU Workspace")

    private fun jobJson(j: DownloadJob) = buildJsonObject {
        put("id", j.id); put("modelId", j.modelId); put("name", j.name); put("destination", j.destination); put("totalBytes", j.totalBytes); put("bytes", j.bytes)
        put("state", j.state); put("message", j.message); put("verified", j.verified); j.registeredId?.let { put("registeredId", it) }
    }

    private fun scan(c: Call) {
        val extra = c.strings("folders").map { File(it) }
        extra.firstOrNull { !it.isDirectory }?.let { return c.fail(400, "${it.path} is not a folder") }
        val registered = core.models.list().map { it.path }.toSet()
        val found = LocalModelScanner.scan(if (extra.isEmpty()) LocalModelScanner.defaultFolders(paths) else extra, registered)
        c.ok(buildJsonObject { putJsonArray("found") { found.forEach { f -> addJsonObject {
            put("path", f.path); put("sizeBytes", f.sizeBytes); put("name", f.info?.name?.ifBlank { File(f.path).nameWithoutExtension } ?: File(f.path).nameWithoutExtension)
            put("quantization", f.info?.quantization ?: ""); put("architecture", f.info?.architecture ?: ""); put("usable", f.problem == null); f.problem?.let { put("problem", it) }
        } } } })
    }

    private fun download(c: Call) {
        val m = ModelCatalog.get(c.str("modelId") ?: "") ?: return c.fail(404, "no such suggested model")
        if (c.body["confirm"]?.jsonPrimitive?.booleanOrNull != true) return c.fail(400, "Downloads start only when you confirm them (confirm: true), after seeing the size, source and licence.")
        val job = try { downloader.start(m.id, m.name, m.url, m.fileName, confirmed = true) } catch (e: IllegalArgumentException) { return c.fail(400, e.message ?: "refused") }
        selectWhenDone[job.id] = c.body["select"]?.jsonPrimitive?.booleanOrNull == true
        c.send(202, jobJson(job))
    }
    private val selectWhenDone = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Called when the owner looks at a finished job whose "select" choice was made at the start: applies that one explicit choice. */
    fun applyChosenSelection(job: DownloadJob) {
        if (job.state == "done" && job.registeredId != null && selectWhenDone.remove(job.id) == true) core.settings.set(Settings.MODEL_ACTIVE, job.registeredId)
    }

    private fun test(c: Call) {
        if (core.models.list().isEmpty()) return c.send(409, buildJsonObject { put("error", "No model is registered yet."); put("hint", "Choose or download a model first; CLU works for everything else without one.") })
        val r = runBlocking { runCatching { core.inferenceService.complete(InferenceRequest(listOf(ChatMessage("system", "You are CLU, a helpful assistant. Answer in one short sentence."), ChatMessage("user", "Say hello and tell me you are ready.")),
            GenParams(maxTokens = 40, temperature = 0.3f), owner = "setup", fitContext = false)) } }
        val st = core.llm.state.value
        val ok = r.getOrNull()
        if (ok == null) return c.send(502, buildJsonObject { put("error", r.exceptionOrNull()?.message ?: "The test failed."); put("state", st.status.name); put("message", st.message) })
        c.ok(buildJsonObject {
            put("text", ok.text); put("firstTokenMs", ok.stats.firstTokenMs); put("tokensPerSecond", ok.stats.tokensPerSecond); put("generatedTokens", ok.stats.generatedTokens)
            put("backend", st.backend); put("threads", st.threads); put("contextSize", st.contextSize); put("loadMs", st.loadMs); put("note", st.note)
        })
    }

    private fun complete(c: Call) {
        val errors = mutableListOf<String>()
        c.body["contextLength"]?.jsonPrimitive?.intOrNull?.let { v -> (core.settings.set(Settings.CONTEXT_LENGTH, v) as? ai.clu.core.config.SettingResult.Invalid)?.let { errors += "context: ${it.reason}" } }
        c.body["threads"]?.jsonPrimitive?.intOrNull?.let { v -> (core.settings.set(Settings.THREADS, v) as? ai.clu.core.config.SettingResult.Invalid)?.let { errors += "threads: ${it.reason}" } }
        c.body["acceleration"]?.jsonPrimitive?.contentOrNull?.let { v -> (core.settings.set(Settings.ACCELERATION, v) as? ai.clu.core.config.SettingResult.Invalid)?.let { errors += "acceleration: ${it.reason}" } }
        c.body["activeModel"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { id ->
            if (core.models.get(id) == null) errors += "that model is not registered" else core.settings.set(Settings.MODEL_ACTIVE, id) }
        c.body["ownerName"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { core.settings.set(Settings.OWNER_NAME, it.take(60)) }
        if (errors.isNotEmpty()) return c.fail(400, errors.joinToString("; "))
        core.store.setMeta(SETUP_KEY, System.currentTimeMillis().toString())
        c.ok(buildJsonObject { put("completed", true) })
    }

    private fun appConfig() = buildJsonObject {
        put("runInBackground", hostConfig?.current?.runInBackground ?: true)
        put("startWithWindowsSupported", autostart.supported); autostart.isEnabled()?.let { put("startWithWindows", it) }
        put("dataFolder", paths.root.absolutePath); put("logFile", File(paths.logs, "host.log").absolutePath); put("backupFolder", paths.backups.absolutePath)
        put("version", Host.VERSION)
    }

    private fun appConfigUpdate(c: Call) {
        c.body["runInBackground"]?.jsonPrimitive?.booleanOrNull?.let { v -> hostConfig?.update(hostConfig.current.copy(runInBackground = v)) }
        c.body["startWithWindows"]?.jsonPrimitive?.booleanOrNull?.let { v -> autostart.set(v)?.let { return c.fail(409, it) } }
        c.ok(appConfig())
    }

    private fun deleteFile(c: Call, id: String) {
        val m = core.models.get(id) ?: return c.fail(404, "no such model")
        if (c.body["confirm"]?.jsonPrimitive?.booleanOrNull != true) return c.fail(400, "Deleting the file needs confirm: true. Removing the registration alone keeps the file.")
        val f = File(m.path)
        try { downloader.deleteDownloaded(f) } catch (e: IllegalArgumentException) { return c.fail(403, e.message ?: "refused") }
        if (core.settings.string(Settings.MODEL_ACTIVE) == id) { runBlocking { core.inferenceService.unload() }; core.settings.set(Settings.MODEL_ACTIVE, "") }
        core.models.remove(id, ownedDir = null)
        c.ok(buildJsonObject { put("deleted", f.name); put("fileExists", f.exists()) })
    }

    companion object { const val SETUP_KEY = "setup.completed" }
}
