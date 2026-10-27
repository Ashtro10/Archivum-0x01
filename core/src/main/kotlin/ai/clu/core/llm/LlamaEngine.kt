package ai.clu.core.llm

import ai.clu.core.util.CluJson
import kotlinx.serialization.json.Json

/** JNI surface of libclu_llama.so (native/jni/clu_llama_jni.cpp). */
object LlamaBridge {
    fun interface ByteCallback { fun onBytes(bytes: ByteArray): Boolean }

    @Volatile private var loaded = false

    /** Loads the native library. On Android: System.loadLibrary; tests pass an absolute path. */
    @Synchronized
    fun load(absolutePath: String? = null) {
        if (loaded) return
        if (absolutePath != null) System.load(absolutePath) else System.loadLibrary("clu_llama")
        nativeInit()
        loaded = true
    }

    val isLoaded get() = loaded

    @JvmStatic external fun nativeInit()
    @JvmStatic external fun nativeSystemInfo(): String
    @JvmStatic external fun nativeLastLog(): String
    @JvmStatic external fun nativeLastError(): String
    @JvmStatic external fun nativeLoadModel(path: String, nGpuLayers: Int, useMmap: Boolean): Long
    @JvmStatic external fun nativeFreeModel(model: Long)
    @JvmStatic external fun nativeModelInfo(model: Long): String
    @JvmStatic external fun nativeNewSession(model: Long, nCtx: Int, nBatch: Int, nThreads: Int): Long
    @JvmStatic external fun nativeFreeSession(session: Long)
    @JvmStatic external fun nativeCancel(session: Long)
    @JvmStatic external fun nativeResetSession(session: Long)
    @JvmStatic external fun nativeSetThreads(session: Long, nThreads: Int, nThreadsBatch: Int)
    @JvmStatic external fun nativeApplyTemplate(model: Long, templateOverride: String?, roles: Array<String>, contents: Array<ByteArray>, addAssistant: Boolean): ByteArray?
    @JvmStatic external fun nativeCountTokens(model: Long, text: ByteArray): Int
    @JvmStatic external fun nativeContextSize(session: Long): Int
    @JvmStatic external fun nativeGenerate(
        session: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, topK: Int, topP: Float, minP: Float,
        repeatPenalty: Float, seed: Int, grammar: String?, callback: ByteCallback,
    ): String?
}

@kotlinx.serialization.Serializable
data class NativeModelInfo(val desc: String = "", val sizeBytes: Long = 0, val params: Long = 0, val ctxTrain: Int = 0, val vocab: Int = 0, val hasChatTemplate: Boolean = false)

data class LlamaConfig(val contextLength: Int, val batchSize: Int, val threads: Int, val useMmap: Boolean, val gpuLayers: Int = 0, val chatTemplate: String = "auto")

/**
 * llama.cpp engine over one model + one context. Thread-confined: callers must serialise generate() calls
 * (LlmService does). cancel() may be called from any thread.
 */
class LlamaEngine private constructor(
    override val id: String,
    private var model: Long,
    private var session: Long,
    val info: NativeModelInfo,
    val config: LlamaConfig,
) : LlmEngine {
    override val source = "local"

    override fun isReady() = model != 0L && session != 0L

    private fun template(): String? = when (config.chatTemplate) {
        "auto" -> null
        else -> config.chatTemplate
    }

    fun format(messages: List<ChatMessage>, addAssistant: Boolean = true): String {
        check(isReady()) { "engine released" }
        val bytes = LlamaBridge.nativeApplyTemplate(model, template(), messages.map { it.role }.toTypedArray(),
            messages.map { it.content.toByteArray() }.toTypedArray(), addAssistant)
            ?: LlamaBridge.nativeApplyTemplate(model, "chatml", messages.map { it.role }.toTypedArray(),
                messages.map { it.content.toByteArray() }.toTypedArray(), addAssistant)
            ?: throw LlmException("chat template not supported")
        return String(bytes, Charsets.UTF_8)
    }

    override fun countTokens(messages: List<ChatMessage>): Int = LlamaBridge.nativeCountTokens(model, format(messages).toByteArray())

    override fun contextSize(): Int = LlamaBridge.nativeContextSize(session)

    override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult =
        generate(messages, params, CancelSignal(), onText)

    /**
     * The signal sets the native cancel flag (stops prompt processing and token generation within one decode step) and
     * is also checked after every token, which covers a cancel that lands between the check below and the native call
     * resetting its flag.
     */
    override fun generate(messages: List<ChatMessage>, params: GenParams, signal: CancelSignal, onText: (String) -> Unit): GenResult {
        check(isReady()) { "engine released" }
        signal.onCancel { cancel() }.use { return generateLocked(messages, params, signal, onText) }
    }

    private var decodeThreads = config.threads

    private fun generateLocked(messages: List<ChatMessage>, params: GenParams, signal: CancelSignal, onText: (String) -> Unit): GenResult {
        if (signal.isCancelled) return cancelledResult(source)
        // The prompt is always read with every configured thread (speech is not running yet); only token-by-token
        // generation leaves cores to a synthesiser that runs alongside it (GenParams.reserveThreads).
        val want = decodeThreadsFor(config.threads, params.reserveThreads)
        if (want != decodeThreads) { LlamaBridge.nativeSetThreads(session, want, config.threads); decodeThreads = want }
        val prompt = format(messages)
        val utf8 = Utf8Stream()
        val stops = StopSequenceFilter(params.stop)
        val out = StringBuilder()
        val statsJson = LlamaBridge.nativeGenerate(session, prompt.toByteArray(), params.maxTokens, params.temperature, params.topK,
            params.topP, params.minP, params.repeatPenalty, params.seed, params.grammar) { bytes ->
            val emitted = stops.push(utf8.push(bytes))
            if (emitted.isNotEmpty() && !signal.isCancelled) { out.append(emitted); onText(emitted) }
            !stops.stopped && !signal.isCancelled
        } ?: if (signal.isCancelled) return GenResult(out.toString(), GenStats(stopReason = "cancelled"), source)
             else throw LlmException(LlamaBridge.nativeLastError().ifEmpty { "generation failed" })
        val tail = if (signal.isCancelled) "" else stops.push(utf8.finish()) + stops.flush()
        if (tail.isNotEmpty()) { out.append(tail); onText(tail) }
        var stats = lenientJson.decodeFromString(GenStats.serializer(), statsJson)
        if (signal.isCancelled) stats = stats.copy(stopReason = "cancelled")
        return GenResult(out.toString(), stats, source)
    }

    override fun cancel() { if (session != 0L) LlamaBridge.nativeCancel(session) }

    fun resetCache() { if (session != 0L) LlamaBridge.nativeResetSession(session) }

    @Synchronized
    fun release() {
        if (session != 0L) { LlamaBridge.nativeFreeSession(session); session = 0 }
        if (model != 0L) { LlamaBridge.nativeFreeModel(model); model = 0 }
    }

    companion object {
        /** Decode threads with [reserve] cores left free, never below 2 (or the configured count if that is lower). */
        fun decodeThreadsFor(configured: Int, reserve: Int): Int = if (reserve <= 0) configured else maxOf(minOf(configured, 2), configured - reserve)

        private val lenientJson = Json { ignoreUnknownKeys = true }

        /** Loads a GGUF model. Throws [LlmException] with llama.cpp's diagnostics on failure. */
        fun load(id: String, path: String, cfg: LlamaConfig): LlamaEngine {
            check(LlamaBridge.isLoaded) { "native library not loaded" }
            val m = LlamaBridge.nativeLoadModel(path, cfg.gpuLayers, cfg.useMmap)
            if (m == 0L) throw LlmException(LlamaBridge.nativeLastError() + "\n" + LlamaBridge.nativeLastLog().takeLast(800))
            val info = runCatching { CluJson.decodeFromString(NativeModelInfo.serializer(), LlamaBridge.nativeModelInfo(m)) }.getOrDefault(NativeModelInfo())
            val ctx = if (info.ctxTrain > 0) minOf(cfg.contextLength, info.ctxTrain) else cfg.contextLength
            val s = LlamaBridge.nativeNewSession(m, ctx, cfg.batchSize, cfg.threads)
            if (s == 0L) {
                LlamaBridge.nativeFreeModel(m)
                throw LlmException(LlamaBridge.nativeLastError() + "\n" + LlamaBridge.nativeLastLog().takeLast(800))
            }
            return LlamaEngine(id, m, s, info, cfg.copy(contextLength = ctx))
        }
    }
}
