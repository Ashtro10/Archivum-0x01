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
    val gpuAvailable: Boolean get() = false
}

enum class LlmStatus { NO_MODEL, UNLOADED, LOADING, READY, GENERATING, ERROR }

data class LlmState(val status: LlmStatus, val modelId: String? = null, val modelName: String? = null, val message: String = "",
                    val loadMs: Long = 0, val contextSize: Int = 0, /** Increments with every successful model load. */ val loadSeq: Long = 0)

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
        Settings.CHAT_TEMPLATE, Settings.ACCELERATION)

    init { refreshNoModelState() }

    /** Starts background behaviour: reload on config change, idle unloading, optional preload. */
    fun start() {
        refreshNoModelState()
        scope.launch {
            settings.observeAny(reloadKeys).drop(1).collect {
                if (engine != null) {
                    diag?.i("llm", "configuration changed – reloading")
                    reload()
                } else {
                    refreshNoModelState()
                    if (settings.bool(Settings.LOAD_ON_START) && settings.string(Settings.MODEL_ACTIVE).isNotEmpty()) loadIfNeeded()
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(30_000)
                checkIdle()
            }
        }
        if (settings.bool(Settings.LOAD_ON_START)) scope.launch { loadIfNeeded() }
    }

    fun checkIdle() {
        val mins = settings.int(Settings.AUTO_UNLOAD)
        if (mins > 0 && engine != null && _state.value.status == LlmStatus.READY && now() - lastUsed > mins * 60_000L) {
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
            gpuLayers = if (settings.string(Settings.ACCELERATION) == "gpu" && platform.gpuAvailable) 99 else 0,
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
            return null
        }
        // Integrity before loading: a file whose size changed since it was checked (replaced, truncated, partly
        // overwritten) is validated again instead of being handed to llama.cpp blindly.
        if (File(entry.path).length() != entry.sizeBytes) {
            val problem = try { GgufReader.read(File(entry.path)); null } catch (e: GgufException) { e.message } catch (e: java.io.IOException) { e.message }
            if (problem != null) {
                registry.setStatus(entry.id, ModelStatus.INCOMPATIBLE, problem)
                _state.value = LlmState(LlmStatus.ERROR, entry.id, entry.name, "Model file is damaged: $problem. Import or download it again.")
                diag?.w("llm", "integrity check failed for ${entry.name}: $problem")
                return null
            }
            registry.register(File(entry.path), entry.name)
        }
        val cfg = configFor(entry)
        val need = ModelRegistry.estimateRamBytes(entry, cfg.contextLength)
        val avail = platform.availableMemoryBytes()
        if (need > avail * 1.35) {
            val msg = "Not enough free memory: model needs ~${need / 1_048_576} MB, ${avail / 1_048_576} MB available. Choose a smaller model or context."
            _state.value = LlmState(LlmStatus.ERROR, entry.id, entry.name, msg)
            diag?.w("llm", msg)
            return null
        }
        _state.value = LlmState(LlmStatus.LOADING, entry.id, entry.name, "Loading ${entry.name}…")
        val t0 = now()
        return try {
            if (!nativeReady) { platform.loadNativeLibrary(); nativeReady = true }
            val e = LlamaEngine.load(entry.id, entry.path, cfg)
            engine = e
            lastUsed = now()
            val ms = now() - t0
            diag?.perf("model_load_ms", ms.toDouble(), entry.name)
            diag?.i("llm", "loaded ${entry.name} in ${ms}ms (ctx=${e.config.contextLength}, threads=${cfg.threads})")
            _state.value = LlmState(LlmStatus.READY, entry.id, entry.name, "Ready", ms, e.config.contextLength, ++loadSeq)
            e
        } catch (t: Throwable) {
            val msg = (t.message ?: t.toString()).take(1000)
            registry.setStatus(entry.id, ModelStatus.INCOMPATIBLE, msg)
            diag?.e("llm", "load failed", t)
            _state.value = LlmState(LlmStatus.ERROR, entry.id, entry.name, "Could not load model: $msg")
            null
        }
    }

    suspend fun unload() = withContext(dispatcher) {
        engine?.release(); engine = null
        refreshNoModelState()
        if (_state.value.status != LlmStatus.NO_MODEL) _state.value = _state.value.copy(status = LlmStatus.UNLOADED, message = "Unloaded")
    }

    suspend fun reload(): LlamaEngine? = withContext(dispatcher) {
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
                runOnLlm(signal, cancelled = { cancelledResult(source) }) {
                    // Queued behind another request and cancelled meanwhile: never start.
                    if (signal.isCancelled) return@runOnLlm cancelledResult(source)
                    val e = engine ?: throw LlmException("model unloaded")
                    _state.value = _state.value.copy(status = LlmStatus.GENERATING)
                    try {
                        val r = e.generate(messages, params, signal, onText)
                        lastUsed = now()
                        registry.get(e.id)?.takeIf { it.status != ModelStatus.VERIFIED && r.stats.generatedTokens > 0 }
                            ?.let { registry.setStatus(it.id, ModelStatus.VERIFIED) }
                        if (r.stats.stopReason != "cancelled" && params.maxTokens > 0) {
                            diag?.perf("prompt_ms", r.stats.promptMs)
                            diag?.perf("first_token_ms", r.stats.firstTokenMs)
                            diag?.perf("tokens_per_s", r.stats.tokensPerSecond)
                        }
                        r
                    } finally {
                        if (_state.value.status == LlmStatus.GENERATING) _state.value = _state.value.copy(status = LlmStatus.READY)
                    }
                }
            override fun cancel() { engine?.cancel() }
        }
    }

    fun cancel() { engine?.cancel() }

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

    fun shutdown() {
        scope.cancel()
        runBlocking { unload() }
        thread.shutdown()
    }
}
