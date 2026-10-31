package ai.clu.core.llm

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.learning.PreferenceEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import java.io.File
import java.util.concurrent.Executors

/** Platform hooks (Android supplies real implementations; tests supply fakes). */
interface LlmPlatform {
    fun loadNativeLibrary()
    /** Number of performance cores to use when threads = auto. */
    fun autoThreads(): Int
    /** True when the OS reports severe thermal stress. */
    fun thermalSevere(): Boolean
    /** Currently available RAM in bytes (or Long.MAX_VALUE if unknown). */
    fun availableMemoryBytes(): Long
    /** Whether a GPU load may be attempted (the engine falls back to the CPU if it cannot be initialised). */
    val gpuAvailable: Boolean get() = false
    /** The model is loaded when a request needs it (and released when idle), never ahead of time: for a PC that is shared with the owner's own work. */
    val loadOnDemand: Boolean get() = false
}

/** Loads a model file into an engine (replaceable in tests to simulate failures). */
fun interface ModelLoader { fun load(id: String, path: String, cfg: LlamaConfig): LlamaEngine }

/**
 * The runtime state of the one local model. NO_MODEL/UNLOADED: nothing in memory; LOADING; READY; GENERATING;
 * CANCELLING: a generation was told to stop and is winding down; UNLOADING: the model is being released; ERROR.
 */
enum class LlmStatus { NO_MODEL, UNLOADED, LOADING, READY, GENERATING, ERROR, CANCELLING, UNLOADING }

data class LlmState(val status: LlmStatus, val modelId: String? = null, val modelName: String? = null, val message: String = "",
                    val loadMs: Long = 0, val contextSize: Int = 0, /** Increments with every successful model load. */ val loadSeq: Long = 0,
                    /** What the loaded engine actually runs on ("cpu" or "gpu"), read from the engine's own log, never assumed. */
                    val backend: String = "", val threads: Int = 0, val gpuLayers: Int = 0, val estimatedBytes: Long = 0,
                    /** Why the load differs from what was asked for (GPU unavailable, context reduced), or empty. */
                    val note: String = "")

/** What llama.cpp reported about where the model's layers went. */
object BackendReport {
    private val offloaded = Regex("offloaded (\\d+)/(\\d+) layers to GPU")
    /** Layers actually on the GPU according to the engine's load log (0 if it says nothing, as CPU-only builds do). */
    fun gpuLayers(log: String): Int = offloaded.findAll(log).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

/** Generation parameters derived from settings, learned preferences and thermal state. */
object GenerationParams {
    fun from(settings: SettingsStore, prefs: PreferenceEngine?, thermalSevere: Boolean): GenParams {
        val lengthFactor = when (settings.string(Settings.RESPONSE_LENGTH)) { "brief" -> 0.5; "detailed" -> 1.6; else -> 1.0 }
        val prefFactor = prefs?.tokenBudgetFactor() ?: 1.0
        val thermalFactor = if (thermalSevere && settings.bool(Settings.THERMAL)) 0.6 else 1.0
        val max = (settings.int(Settings.MAX_TOKENS) * lengthFactor * prefFactor * thermalFactor).toInt().coerceIn(16, 4096)
        return GenParams(
            maxTokens = max,
            temperature = settings.double(Settings.TEMPERATURE).toFloat(),
            topK = settings.int(Settings.TOP_K),
            topP = settings.double(Settings.TOP_P).toFloat(),
            minP = settings.double(Settings.MIN_P).toFloat(),
            repeatPenalty = settings.double(Settings.REPEAT_PENALTY).toFloat(),
        )
    }
}

/**
 * Owns the local model: loading/unloading, reload on configuration change, idle auto-unload and serialised
 * generation on a dedicated thread (llama contexts are not thread-safe).
 */
class LlmService(
    private val settings: SettingsStore,
    private val registry: ModelRegistry,
    private val platform: LlmPlatform,
    private val diag: DiagnosticsLog?,
    private val now: () -> Long = System::currentTimeMillis,
    private val loader: ModelLoader = ModelLoader { id, path, cfg -> LlamaEngine.load(id, path, cfg) },
) {
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "clu-llm").apply { priority = Thread.NORM_PRIORITY + 1 } }
    private val dispatcher = thread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(LlmState(LlmStatus.UNLOADED))
    val state: StateFlow<LlmState> get() = _state

    @Volatile private var engine: LlamaEngine? = null
    @Volatile private var lastUsed = now()
    @Volatile private var nativeReady = false
    private var loadSeq = 0L

    private val reloadKeys = listOf(Settings.MODEL_ACTIVE, Settings.CONTEXT_LENGTH, Settings.THREADS, Settings.BATCH, Settings.USE_MMAP,
        Settings.CHAT_TEMPLATE, Settings.ACCELERATION, Settings.GPU_LAYERS)

    init { refreshNoModelState() }

    /**
     * Starts background behaviour: reload on config change, idle unloading, optional preload. [preload] = false leaves
     * the load to [prepare] (the app calls it when a screen is visible or voice starts): a process started in the
     * background loaded the model while Android was freezing it (loads "taking" up to an hour) and held its memory.
     */
    fun start(preload: Boolean = true) {
        refreshNoModelState()
        scope.launch {
            settings.observeAny(reloadKeys).drop(1).collect {
                if (loadedMatchesSettings()) return@collect // e.g. a fallback just loaded exactly this
                if (engine != null) {
                    diag?.i("llm", "configuration changed – reloading")
                    reload()
                } else {
                    refreshNoModelState()
                    if (settings.bool(Settings.LOAD_ON_START) && !platform.loadOnDemand && settings.string(Settings.MODEL_ACTIVE).isNotEmpty()) loadIfNeeded()
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(30_000)
                checkIdle()
            }
        }
        if (preload && settings.bool(Settings.LOAD_ON_START) && !platform.loadOnDemand) scope.launch { loadIfNeeded() }
    }

    /**
     * The owner is about to use Clu (a screen became visible, voice started): counts as use for the idle timer and, if
     * the model is set to load at start, loads it now so the first reply doesn't wait for it.
     */
    fun prepare() {
        lastUsed = now()
        val st = _state.value.status
        if (settings.bool(Settings.LOAD_ON_START) && !platform.loadOnDemand && engine == null && st != LlmStatus.LOADING && st != LlmStatus.ERROR &&
            settings.string(Settings.MODEL_ACTIVE).isNotEmpty()) scope.launch { loadIfNeeded() }
    }

    @Volatile private var lastIdleCheck = 0L

    fun checkIdle() {
        val mins = settings.int(Settings.AUTO_UNLOAD)
        // A check that comes much later than its 30 s period means the process was frozen or asleep: it is waking up,
        // usually because the owner is coming back. Skip that round instead of unloading the model they are about to use.
        val t = now(); val gap = t - lastIdleCheck; lastIdleCheck = t
        if (gap > IDLE_CHECK_MAX_GAP_MS) return
        if (mins > 0 && engine != null && _state.value.status == LlmStatus.READY && t - lastUsed > mins * 60_000L) {
            diag?.i("llm", "idle for $mins min – unloading")
            runBlocking { unload() }
        }
    }

    private fun refreshNoModelState() {
        if (engine == null && _state.value.status != LlmStatus.LOADING) {
            val id = settings.string(Settings.MODEL_ACTIVE)
            val e = id.takeIf { it.isNotEmpty() }?.let { registry.get(it) }
            _state.value = if (e == null) LlmState(LlmStatus.NO_MODEL, message = "No model selected. Import or download a GGUF model in Models.")
            else LlmState(LlmStatus.UNLOADED, e.id, e.name)
        }
    }

    fun configFor(entry: ModelEntry): LlamaConfig {
        val auto = platform.autoThreads()
        var threads = settings.int(Settings.THREADS).takeIf { it > 0 } ?: auto
        if (settings.bool(Settings.THERMAL) && platform.thermalSevere()) threads = maxOf(1, threads / 2)
        return LlamaConfig(
            contextLength = settings.int(Settings.CONTEXT_LENGTH),
            batchSize = settings.int(Settings.BATCH),
            threads = threads,
            useMmap = settings.bool(Settings.USE_MMAP),
            gpuLayers = if (settings.string(Settings.ACCELERATION) == "gpu" && platform.gpuAvailable) settings.int(Settings.GPU_LAYERS) else 0,
            chatTemplate = settings.string(Settings.CHAT_TEMPLATE),
        )
    }

    suspend fun loadIfNeeded(): LlamaEngine? = withContext(dispatcher) { engine ?: loadLocked() }

    private fun loadLocked(): LlamaEngine? {
        val id = settings.string(Settings.MODEL_ACTIVE)
        val entry = id.takeIf { it.isNotEmpty() }?.let { registry.get(it) }
        if (entry == null) { refreshNoModelState(); return null }
        if (!File(entry.path).exists()) {
            registry.setStatus(entry.id, ModelStatus.MISSING, "file not found")
            _state.value = LlmState(LlmStatus.ERROR, entry.id, entry.name, "Model file is missing: ${entry.path}")
            return fallbackFrom(entry, "its file is missing")
        }
        // Integrity before loading: a file whose size changed since it was checked (replaced, truncated, partly
        // overwritten) is validated again instead of being handed to llama.cpp blindly.
        if (File(entry.path).length() != entry.sizeBytes) {
            val problem = try { GgufReader.read(File(entry.path)); null } catch (e: GgufException) { e.message ?: "invalid GGUF" } catch (e: java.io.IOException) { e.message ?: "the file ends early (${e::class.simpleName})" }
            if (problem != null) {
                registry.setStatus(entry.id, ModelStatus.INCOMPATIBLE, problem)
                _state.value = LlmState(LlmStatus.ERROR, entry.id, entry.name, "Model file is damaged: $problem. Import or download it again.")
                diag?.w("llm", "integrity check failed for ${entry.name}: $problem")
                return fallbackFrom(entry, "the file is damaged")
            }
            registry.register(File(entry.path), entry.name)
        }
        var cfg = configFor(entry)
        var need = ModelRegistry.estimateRamBytes(entry, cfg.contextLength)
        val avail = platform.availableMemoryBytes()
        val limit = if (avail == Long.MAX_VALUE) Long.MAX_VALUE else (avail * 1.35).toLong()
        var note = ""
        if (need > limit) {
            // Rather than refuse, load with the largest context that fits (the weights still have to).
            val fit = if (settings.bool(Settings.FIT_CONTEXT)) ModelRegistry.fitContext(entry, cfg.contextLength, limit) else null
            if (fit == null) {
                val msg = "Not enough free memory: model needs ~${need / 1_048_576} MB, ${avail / 1_048_576} MB available. Choose a smaller model or context."
                _state.value = LlmState(LlmStatus.ERROR, entry.id, entry.name, msg)
                diag?.w("llm", msg)
                return null
            }
            note = "Context reduced from ${cfg.contextLength} to $fit tokens to fit the memory CLU may use."
            diag?.w("llm", note)
            cfg = cfg.copy(contextLength = fit); need = ModelRegistry.estimateRamBytes(entry, fit)
        }
        _state.value = LlmState(LlmStatus.LOADING, entry.id, entry.name, "Loading ${entry.name}…")
        val t0 = System.nanoTime()
        val visible = diag?.visibilityMark() ?: 0
        return try {
            if (!nativeReady) { platform.loadNativeLibrary(); nativeReady = true }
            val e = try { loader.load(entry.id, entry.path, cfg) } catch (t: Throwable) {
                // The GPU could not be initialised: the same model on the CPU, and say so (never a silent fallback, never a dead end).
                if (cfg.gpuLayers <= 0) throw t
                val why = (t.message ?: t.toString()).lineSequence().first().take(160)
                note = (note + " GPU could not be used ($why); running on the CPU.").trim()
                diag?.w("llm", "GPU load failed ($why); retrying on the CPU")
                cfg = cfg.copy(gpuLayers = 0)
                loader.load(entry.id, entry.path, cfg)
            }
            engine = e
            lastUsed = now()
            val ms = (System.nanoTime() - t0) / 1_000_000
            val onGpu = if (cfg.gpuLayers > 0) runCatching { BackendReport.gpuLayers(LlamaBridge.nativeLastLog()) }.getOrDefault(0) else 0
            if (cfg.gpuLayers > 0 && onGpu == 0 && !note.contains("GPU")) note = (note + " The GPU was requested but this engine build has no GPU backend; running on the CPU.").trim()
            diag?.perfSpan("model_load_ms", ms.toDouble(), visible, entry.name)
            diag?.i("llm", "loaded ${entry.name} in ${ms}ms (ctx=${e.config.contextLength}, threads=${cfg.threads}, backend=${if (onGpu > 0) "gpu($onGpu layers)" else "cpu"})")
            _state.value = LlmState(LlmStatus.READY, entry.id, entry.name, note.ifEmpty { "Ready" }, ms, e.config.contextLength, ++loadSeq,
                backend = if (onGpu > 0) "gpu" else "cpu", threads = cfg.threads, gpuLayers = onGpu, estimatedBytes = need, note = note)
            e
        } catch (t: Throwable) {
            val msg = (t.message ?: t.toString()).take(1000)
            registry.setStatus(entry.id, ModelStatus.INCOMPATIBLE, msg)
            diag?.e("llm", "load failed", t)
            _state.value = LlmState(LlmStatus.ERROR, entry.id, entry.name, "Could not load model: $msg")
            fallbackFrom(entry, msg.lineSequence().first().take(160))
        }
    }

    /**
     * A newly chosen model that can't be loaded must not leave Clu without one: switch back to the most recent model
     * that has loaded and generated on this device (ModelStatus.VERIFIED), select it again, and say so. Memories,
     * conversations and settings are not touched; they never depend on the model.
     */
    private fun fallbackFrom(failed: ModelEntry, why: String): LlamaEngine? {
        val good = registry.list().lastOrNull { it.status == ModelStatus.VERIFIED && it.id != failed.id && File(it.path).exists() } ?: return null
        diag?.w("llm", "${failed.name} could not be loaded ($why); falling back to ${good.name}")
        settings.set(Settings.MODEL_ACTIVE, good.id)
        val e = loadLocked() ?: return null
        _state.value = _state.value.copy(message = "Couldn't load ${failed.name} ($why). Using ${good.name} instead.")
        return e
    }

    /** The loaded model is exactly what the settings ask for (same file, same configuration). */
    private fun loadedMatchesSettings(): Boolean {
        val e = engine ?: return false
        val entry = registry.get(settings.string(Settings.MODEL_ACTIVE)) ?: return false
        val want = configFor(entry)
        return e.id == entry.id && want.copy(contextLength = e.config.contextLength) == e.config && want.contextLength >= e.config.contextLength
    }

    suspend fun unload() = withContext(dispatcher) {
        if (engine != null) _state.value = _state.value.copy(status = LlmStatus.UNLOADING, message = "Unloading…")
        engine?.release(); engine = null
        refreshNoModelState()
        if (_state.value.status != LlmStatus.NO_MODEL) _state.value = _state.value.copy(status = LlmStatus.UNLOADED, message = "Unloaded")
    }

    suspend fun reload(): LlamaEngine? = withContext(dispatcher) {
        if (engine != null) _state.value = _state.value.copy(status = LlmStatus.UNLOADING, message = "Unloading…")
        engine?.release(); engine = null
        _state.value = _state.value.copy(status = LlmStatus.UNLOADED)
        loadLocked()
    }

    /** Engine view that runs on the LLM thread and tracks usage. Null if no model can be loaded. */
    suspend fun engine(): LlmEngine? {
        loadIfNeeded() ?: return null
        return object : LlmEngine {
            override val id get() = engine?.id ?: ""
            override val source = "local"
            override fun isReady() = engine?.isReady() == true
            override fun countTokens(messages: List<ChatMessage>) = runOnLlm { (engine ?: throw LlmException("model unloaded")).countTokens(messages) }
            override fun contextSize() = engine?.contextSize() ?: 0
            override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult =
                generate(messages, params, CancelSignal(), onText)
            override fun generate(messages: List<ChatMessage>, params: GenParams, signal: CancelSignal, onText: (String) -> Unit): GenResult =
                generateTracked(messages, params, signal, {}, onText)
            override fun cancel() { engine?.cancel() }
        }
    }

    /**
     * One generation on the model thread. [onStarted] runs when this request actually gets the model (after any queue
     * wait), so callers can time "first token" from the right moment. The state shows GENERATING, then CANCELLING if
     * the request is cancelled while it runs, and returns to READY afterwards.
     */
    internal fun generateTracked(messages: List<ChatMessage>, params: GenParams, signal: CancelSignal, onStarted: () -> Unit, onText: (String) -> Unit): GenResult {
        val submitted = System.nanoTime()
        val visible = diag?.visibilityMark() ?: 0
        return runOnLlm(signal, cancelled = { cancelledResult("local") }) {
            val queueMs = (System.nanoTime() - submitted) / 1e6
            // Queued behind another request and cancelled meanwhile: never start.
            if (signal.isCancelled) return@runOnLlm cancelledResult("local")
            val e = engine ?: throw LlmException("model unloaded")
            _state.value = _state.value.copy(status = LlmStatus.GENERATING)
            onStarted()
            signal.onCancel { _state.value = _state.value.let { st -> if (st.status == LlmStatus.GENERATING) st.copy(status = LlmStatus.CANCELLING) else st } }.use {
                try {
                    val r = e.generate(messages, params, signal, onText).let { it.copy(stats = it.stats.copy(queueMs = queueMs)) }
                    lastUsed = now()
                    registry.get(e.id)?.takeIf { it.status != ModelStatus.VERIFIED && r.stats.generatedTokens > 0 }
                        ?.let { registry.setStatus(it.id, ModelStatus.VERIFIED) }
                    if (params.maxTokens > 0) recordGeneration(r.stats, visible)
                    r
                } finally {
                    _state.value = _state.value.let { st -> if (st.status == LlmStatus.GENERATING || st.status == LlmStatus.CANCELLING) st.copy(status = LlmStatus.READY) else st }
                }
            }
        }
    }

    /** Tokens [messages] occupy once formatted for the loaded model. */
    fun countTokens(messages: List<ChatMessage>): Int = runOnLlm { (engine ?: throw LlmException("model unloaded")).countTokens(messages) }

    fun cancel() {
        engine?.cancel()
        _state.value = _state.value.let { st -> if (st.status == LlmStatus.GENERATING) st.copy(status = LlmStatus.CANCELLING) else st }
    }

    /** Settings of the loaded model for the performance report: context, threads, batch, size and type (no file name). */
    fun loadedSummary(): Map<String, String>? = engine?.let { e ->
        mapOf("context" to "${e.config.contextLength} tokens", "threads" to "${e.config.threads} (prompt) / ${LlamaEngine.decodeThreadsFor(e.config.threads, 2)} (generation while speaking)",
            "batch" to "${e.config.batchSize}", "max reply" to "${GenerationParams.from(settings, null, false).maxTokens} tokens",
            "model" to "${e.info.desc.ifBlank { "?" }}, ${e.info.sizeBytes / 1_048_576} MB")
    }

    /**
     * Per generation: prompt processing (with how many tokens were new, i.e. not reused from the cache, which decides
     * its cost) and generation speed. Speeds over fewer than [MIN_RATE_TOKENS] tokens are kept apart ("…_short"):
     * a 3-token reply's rate is mostly noise.
     */
    private fun recordGeneration(st: GenStats, visible: Long) {
        val d = diag ?: return
        // All from one native call: promptMs covers tokenising the prompt and processing exactly promptTokens -
        // reusedTokens new tokens (no model load, no queue wait: that is queueMs, measured here before the call).
        val fresh = st.promptTokens - st.reusedTokens
        val detail = "prompt ${st.promptTokens} reused ${st.reusedTokens} cached ${st.cachedTokens} generated ${st.generatedTokens} queue ${st.queueMs.toLong()}"
        d.perfSpan("llm_queue_ms", st.queueMs, visible, detail)
        if (st.stopReason == "cancelled") {
            // Stopped requests are kept apart; a request stopped during its prompt has no statistics at all.
            if (st.promptTokens > 0) d.perf(DiagnosticsLog.classified("prompt_ms", "cancelled"), st.promptMs, detail)
            return
        }
        d.perfSpan("prompt_ms", st.promptMs, visible, detail)
        // First token: -1 when the model ended without any token (an empty reply); that is not a time.
        if (st.firstTokenMs >= 0) d.perfSpan("first_token_ms", st.firstTokenMs, visible, detail) else d.perf("first_token_none", 1.0, detail)
        d.perf("prompt_tokens", st.promptTokens.toDouble(), detail)
        d.perf("prompt_new_tokens", fresh.toDouble(), detail)
        if (fresh >= MIN_RATE_TOKENS && st.promptMs > 0) d.perf("prefill_tok_per_s", fresh * 1000.0 / st.promptMs, detail)
        d.perf(if (st.generatedTokens >= MIN_RATE_TOKENS) "tokens_per_s" else "tokens_per_s_short", st.tokensPerSecond, detail)
    }

    private fun <T> runOnLlm(block: () -> T): T = try {
        thread.submit<T> { block() }.get()
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    }

    /** Like [runOnLlm], but a cancel while the task is still queued removes it from the queue. */
    private fun <T> runOnLlm(signal: CancelSignal, cancelled: () -> T, block: () -> T): T {
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val f = thread.submit<T> { started.set(true); block() }
        // A running task is stopped by the engine through the same signal and returns its partial result.
        return signal.onCancel { if (!started.get()) f.cancel(false) }.use {
            try { f.get() } catch (e: java.util.concurrent.CancellationException) { cancelled() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
        }
    }

    private companion object {
        const val IDLE_CHECK_MAX_GAP_MS = 90_000L
        const val MIN_RATE_TOKENS = 16
    }

    fun shutdown() {
        scope.cancel()
        runBlocking { unload() }
        thread.shutdown()
    }
}
