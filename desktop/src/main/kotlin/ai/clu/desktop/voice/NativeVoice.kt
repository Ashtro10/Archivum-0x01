package ai.clu.desktop.voice

import java.io.File

/**
 * The JNI bridge (native/voice/clu_voice_jni.c) to LiveKit's Rust SDK (livekit_ffi) and sherpa-onnx's C API.
 * [load] loads the libraries from CLU's native folder in dependency order, so Windows finds them next to each other.
 */
object NativeVoice {
    @Volatile var loaded = false; private set
    @Volatile var loadError: String? = null; private set
    @Volatile var eventSink: ((ByteArray) -> Unit)? = null

    /** The folder holding the voice libraries: `-Dclu.voiceNative`, or lib/native/<os>-x64 next to CLU's jars, or the build output. */
    fun folder(): File? {
        System.getProperty("clu.voiceNative")?.let { return File(it).takeIf { f -> f.isDirectory } }
        val os = if (System.getProperty("os.name").lowercase().contains("win")) "windows-x64" else "linux-x64"
        val jar = runCatching { File(NativeVoice::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
        val candidates = listOfNotNull(jar?.parentFile?.let { File(it, "native/$os") }, File("native/build/voice/$os"), File("../native/build/voice/$os"))
        return candidates.firstOrNull { File(it, lib("clu_voice")).isFile }
    }

    private fun lib(name: String) = if (System.getProperty("os.name").lowercase().contains("win")) "$name.dll" else "lib$name.so"

    @Synchronized fun load(): Boolean {
        if (loaded) return true
        val dir = folder() ?: run { loadError = "The voice libraries are not installed (clu_voice). Reinstall CLU."; return false }
        return try {
            for (n in listOf("onnxruntime", "sherpa-onnx-c-api", "livekit_ffi", "clu_voice")) System.load(File(dir, lib(n)).absolutePath)
            loaded = true; loadError = null; true
        } catch (e: Throwable) { loadError = "The voice libraries could not be loaded: ${e.message}"; false }
    }

    /** Called by the native side (LiveKit's runtime threads) with one serialized FfiEvent. */
    @JvmStatic fun onFfiEvent(bytes: ByteArray) { runCatching { eventSink?.invoke(bytes) } }

    @JvmStatic external fun lkInit(version: String)
    @JvmStatic external fun lkDispose()
    @JvmStatic external fun lkRequest(request: ByteArray): ByteArray
    @JvmStatic external fun lkDropHandle(handle: Long): Boolean
    @JvmStatic external fun pcmRead(ptr: Long, samples: Int): ShortArray
    @JvmStatic external fun pcmAlloc(pcm: ShortArray): Long
    @JvmStatic external fun bytesAlloc(data: ByteArray): Long
    @JvmStatic external fun bytesRead(ptr: Long, n: Int): ByteArray
    @JvmStatic external fun memFree(ptr: Long)

    @JvmStatic external fun vadCreate(model: String, threshold: Float, minSilence: Float, minSpeech: Float, maxSpeech: Float, threads: Int): Long
    @JvmStatic external fun vadAccept(vad: Long, samples: FloatArray)
    @JvmStatic external fun vadDetected(vad: Long): Boolean
    @JvmStatic external fun vadPop(vad: Long): FloatArray?
    @JvmStatic external fun vadReset(vad: Long)
    @JvmStatic external fun vadFlush(vad: Long)
    @JvmStatic external fun vadDestroy(vad: Long)

    @JvmStatic external fun asrCreate(encoder: String, decoder: String, joiner: String, tokens: String, threads: Int): Long
    @JvmStatic external fun asrStream(recognizer: Long): Long
    @JvmStatic external fun asrAccept(stream: Long, rate: Int, samples: FloatArray)
    @JvmStatic external fun asrDecode(recognizer: Long, stream: Long): String
    @JvmStatic external fun asrInputFinished(stream: Long)
    @JvmStatic external fun asrStreamDestroy(stream: Long)
    @JvmStatic external fun asrDestroy(recognizer: Long)

    @JvmStatic external fun whisperCreate(encoder: String, decoder: String, tokens: String, language: String?, threads: Int): Long
    @JvmStatic external fun whisperDecode(recognizer: Long, samples: FloatArray): String
    @JvmStatic external fun whisperDestroy(recognizer: Long)

    @JvmStatic external fun ttsCreate(kind: Int, model: String, voices: String?, tokens: String, dataDir: String?, threads: Int): Long
    @JvmStatic external fun ttsSampleRate(tts: Long): Int
    @JvmStatic external fun ttsNumSpeakers(tts: Long): Int
    @JvmStatic external fun ttsGenerate(tts: Long, text: String, speaker: Int, speed: Float): FloatArray?
    @JvmStatic external fun ttsDestroy(tts: Long)

    @JvmStatic external fun kwsCreate(encoder: String, decoder: String, joiner: String, tokens: String, keywords: String, threshold: Float, score: Float, threads: Int): Long
    @JvmStatic external fun kwsStream(kws: Long): Long
    @JvmStatic external fun kwsAccept(kws: Long, stream: Long, samples: FloatArray): String
    @JvmStatic external fun kwsDestroy(kws: Long, stream: Long)
}
