package ai.clu.core.stt

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** JNI surface of libclu_whisper.so (native/jni/clu_whisper_jni.cpp). */
object WhisperBridge {
    @Volatile private var loaded = false

    /** Loads the native library. On Android: System.loadLibrary; tests pass an absolute path. */
    @Synchronized
    fun load(absolutePath: String? = null) {
        if (loaded) return
        if (absolutePath != null) System.load(absolutePath) else System.loadLibrary("clu_whisper")
        loaded = true
    }

    val isLoaded get() = loaded

    @JvmStatic external fun nativeInit(path: String, flashAttn: Boolean): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeAbort(handle: Long)
    @JvmStatic external fun nativeTranscribe(handle: Long, pcm: FloatArray, lang: String, threads: Int, audioCtx: Int, prompt: String?,
                                          maxTokens: Int, fallback: Boolean): ByteArray?
    @JvmStatic external fun nativeLastInfo(handle: Long): String
    @JvmStatic external fun nativeSystemInfo(): String
    @JvmStatic external fun nativeLastLog(): String
}

/** An official whisper.cpp ggml model (ggml-org/whisper.cpp models/README.md lists the SHA1 of each file). */
data class WhisperModel(val id: String, val title: String, val fileName: String, val sha1: String, val bytes: Long, val ramMb: Int) {
    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

object WhisperModels {
    val BASE = WhisperModel("whisper-base", "Whisper base (multilingual, faster; weak Hindi)", "ggml-base.bin",
        "465707469ff3a37a2b9b8d8f89f2f99de7299dac", 147_951_465, ramMb = 390)
    val SMALL = WhisperModel("whisper-small", "Whisper small (multilingual; minimum for usable Hindi)", "ggml-small.bin",
        "55356645c2b361a969dfd0ef2c5a50d530afd8d5", 487_601_967, ramMb = 1000)
    val all = listOf(BASE, SMALL)

    fun sha1(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(1 shl 16)
        input.use { while (true) { val n = it.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** True when [f] is a ggml whisper model file (magic "ggml" little endian). */
    fun looksLikeGgml(f: File): Boolean = runCatching {
        f.inputStream().use { s -> val b = ByteArray(4); s.read(b) == 4 && b.contentEquals(byteArrayOf(0x6c, 0x6d, 0x67, 0x67)) }
    }.getOrDefault(false)
}

/**
 * Offline speech recognition with whisper.cpp. Whisper is not a streaming model: it transcribes a complete
 * utterance (the voice session segments speech with the VAD and calls [transcribe] at end of speech).
 * Not thread-safe; one transcription at a time. [abort] may be called from any thread.
 */
class WhisperEngine private constructor(private var handle: Long, val modelPath: String) : AutoCloseable {
    data class Result(val text: String, val language: String, val encodeMs: Double, val totalMs: Double, val retried: Boolean = false)

    /**
     * [lang]: "en", "hi" or "auto". [fastShortAudio]: shrink the encoder window to the utterance length (much faster for
     * short commands; slightly less accurate). [prompt]: optional initial prompt (vocabulary hint, e.g. names).
     * [fallback]: whisper's temperature-fallback re-decoding (more robust on hard audio, but latency can multiply).
     */
    fun transcribe(pcm16k: FloatArray, lang: String, threads: Int = 4, fastShortAudio: Boolean = true, prompt: String? = null,
                   fallback: Boolean = false): Result? {
        check(handle != 0L) { "closed" }
        // Whisper needs at least ~1 s; pad short commands with silence.
        val pcm = if (pcm16k.size >= MIN_SAMPLES) pcm16k else pcm16k.copyOf(MIN_SAMPLES)
        val ctx = if (fastShortAudio) audioCtxFor(pcm.size) else 0
        val r = run(pcm, lang, threads, ctx, prompt, fallback) ?: return null
        // A shortened encoder window occasionally makes the decoder loop (measured: base, 7 s clip). Retry with the full window.
        if (ctx != 0 && looksRepetitive(r.text)) return run(pcm, lang, threads, 0, prompt, fallback)?.let { it.copy(totalMs = it.totalMs + r.totalMs, retried = true) }
        return r
    }

    private fun run(pcm: FloatArray, lang: String, threads: Int, ctx: Int, prompt: String?, fallback: Boolean): Result? {
        val bytes = WhisperBridge.nativeTranscribe(handle, pcm, lang, threads, ctx, prompt, maxTokensFor(pcm.size), fallback) ?: return null
        val info = WhisperBridge.nativeLastInfo(handle).split(' ')
        return Result(String(bytes, Charsets.UTF_8).trim(), info.getOrElse(0) { "" }, info.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
            info.getOrNull(2)?.toDoubleOrNull() ?: 0.0)
    }

    fun abort() { if (handle != 0L) WhisperBridge.nativeAbort(handle) }

    override fun close() { if (handle != 0L) { WhisperBridge.nativeFree(handle); handle = 0 } }

    companion object {
        const val MIN_SAMPLES = 16_000 + 1_600

        /** Encoder frames for [samples] of 16 kHz audio (1 frame = 20 ms) plus a margin, capped at the full 30 s window. */
        fun audioCtxFor(samples: Int): Int = minOf(1500, samples / 320 + 64).let { (it + 63) / 64 * 64 }.coerceAtMost(1500)

        /** True when [text] repeats a phrase of 3-40 words three or more times in a row (a decoder loop, not speech). */
        fun looksRepetitive(text: String): Boolean {
            val w = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            for (n in 3..minOf(40, w.size / 3)) for (i in 0..w.size - 3 * n) {
                val a = w.subList(i, i + n)
                if (a == w.subList(i + n, i + 2 * n) && a == w.subList(i + 2 * n, i + 3 * n)) return true
            }
            return false
        }

        /** Token budget for an utterance: generous for Devanagari (several byte-level tokens per word), bounds repetition loops. */
        fun maxTokensFor(samples: Int): Int = (24 + samples / 16_000.0 * 20).toInt().coerceAtMost(224)

        fun open(modelPath: String, flashAttn: Boolean = true): WhisperEngine {
            require(File(modelPath).isFile) { "model not found: $modelPath" }
            val h = WhisperBridge.nativeInit(modelPath, flashAttn)
            check(h != 0L) { "whisper.cpp could not load $modelPath: ${WhisperBridge.nativeLastLog().takeLast(300)}" }
            return WhisperEngine(h, modelPath)
        }
    }
}
