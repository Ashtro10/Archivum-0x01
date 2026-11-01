package ai.clu.desktop.voice

import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How a voice pack is synthesised (all by sherpa-onnx; one engine, several model families). */
enum class TtsKind(val code: Int) { VITS(0), KITTEN(1), KOKORO(2) }

/** A voice pack: one model file set (some packs hold several speakers). */
data class VoicePack(val id: String, val kind: TtsKind, val title: String, val url: String?, val sha256: String?, val archiveBytes: Long,
                     val license: String, val source: String, val keep: Map<String, String> = emptyMap(), val bundled: Boolean = false)

/** One selectable voice: a pack and a speaker in it, with what the owner needs to choose. Pitch figures were measured from the engine's output. */
data class VoiceOption(val id: String, val pack: String, val speaker: Int, val name: String, val language: String, val accent: String, val presentation: String, val character: String)

object VoiceCatalog {
    private const val R = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    private fun piperKeep(model: String) = mapOf("$model.onnx" to "model.onnx", "tokens.txt" to "tokens.txt")
    val packs = listOf(
        VoicePack("piper-joe", TtsKind.VITS, "Piper Joe (medium)", "$R/vits-piper-en_US-joe-medium.tar.bz2", "261b903a1fb2ce621e2b5b14233cd41223c3b9f9590f896c11daee4df22d9bd3", 67_169_394,
            "Voice: CC0 dataset (NabuCasa); model MIT (Piper)", "rhasspy/piper via k2-fsa/sherpa-onnx", piperKeep("en_US-joe-medium"), bundled = true),
        VoicePack("piper-alan", TtsKind.VITS, "Piper Alan (medium)", "$R/vits-piper-en_GB-alan-medium.tar.bz2", "a48d4017da0f77668b27bed63fe6e04dd64c6397e1fadad4f460efb0ef7c9012", 67_220_121,
            "Model MIT (Piper); see the voice's model card", "rhasspy/piper via k2-fsa/sherpa-onnx", piperKeep("en_GB-alan-medium"), bundled = true),
        VoicePack("piper-ryan", TtsKind.VITS, "Piper Ryan (medium)", "$R/vits-piper-en_US-ryan-medium.tar.bz2", "c546af78b6395b4e7c4ce1ed899438b64426a362f5d4ec5fecd090ded9ad7505", 67_213_100,
            "Model MIT (Piper); see the voice's model card", "rhasspy/piper via k2-fsa/sherpa-onnx", piperKeep("en_US-ryan-medium"), bundled = true),
        VoicePack("kitten-nano", TtsKind.KITTEN, "KittenTTS nano v0.8 (8 speakers)", "$R/kitten-nano-en-v0_8-int8.tar.bz2", "6fa5be852612ce761094ba74ee6123b4fc4acfefa79bf64dc63acae4a83af2fd", 31_220_690,
            "Apache-2.0", "KittenML via k2-fsa/sherpa-onnx", mapOf("model.int8.onnx" to "model.onnx", "voices.bin" to "voices.bin", "tokens.txt" to "tokens.txt"), bundled = true),
        VoicePack("piper-northern", TtsKind.VITS, "Piper Northern English Male (medium)", "$R/vits-piper-en_GB-northern_english_male-medium.tar.bz2", "2bb2c1e709f58c11f17c693b3b38f500e110e7f54f2651774ec48b8d41f12c55", 67_210_490,
            "Model MIT (Piper); see the voice's model card", "rhasspy/piper via k2-fsa/sherpa-onnx", piperKeep("en_GB-northern_english_male-medium")),
        VoicePack("piper-arctic", TtsKind.VITS, "Piper Arctic (medium, 18 speakers)", "$R/vits-piper-en_US-arctic-medium.tar.bz2", "caf83305b2fecc84491e9581f31ef2c81d4e1dacb8b76a4e481ad3fb6d4bf25c", 80_255_511,
            "Model MIT (Piper); CMU Arctic data", "rhasspy/piper via k2-fsa/sherpa-onnx", piperKeep("en_US-arctic-medium")),
        VoicePack("kokoro-en", TtsKind.KOKORO, "Kokoro-82M v0.19 (11 speakers)", "$R/kokoro-en-v0_19.tar.bz2", "912804855a04745fa77a30be545b3f9a5d15c4d66db00b88cbcd4921df605ac7", 319_625_534,
            "Apache-2.0", "hexgrad/Kokoro-82M via k2-fsa/sherpa-onnx", mapOf("model.onnx" to "model.onnx", "voices.bin" to "voices.bin", "tokens.txt" to "tokens.txt")),
        VoicePack("piper-hi-pratham", TtsKind.VITS, "Piper Pratham, Hindi (medium)", "$R/vits-piper-hi_IN-pratham-medium.tar.bz2", "2084d321e1d2752f2b64ed3012ba27751df01a80da46f52920098cdcb7e35648", 67_238_438,
            "Training data CC BY-NC-SA 4.0 (non-commercial)", "rhasspy/piper via k2-fsa/sherpa-onnx", piperKeep("hi_IN-pratham-medium")),
    )
    val voices = listOf(
        VoiceOption("joe", "piper-joe", 0, "Joe", "English", "US", "Masculine", "Deep, calm, measured (the default)"),
        VoiceOption("alan", "piper-alan", 0, "Alan", "English", "British", "Masculine", "Composed, crisp"),
        VoiceOption("ryan", "piper-ryan", 0, "Ryan", "English", "US", "Masculine", "Brighter, energetic"),
        VoiceOption("kitten-0", "kitten-nano", 0, "Kitten 1", "English", "US", "Not stated by the model", "Light, quick (small model)"),
        VoiceOption("kitten-1", "kitten-nano", 1, "Kitten 2", "English", "US", "Not stated by the model", "Light, quick (small model)"),
        VoiceOption("kitten-2", "kitten-nano", 2, "Kitten 3", "English", "US", "Not stated by the model", "Light, quick (small model)"),
        VoiceOption("kitten-3", "kitten-nano", 3, "Kitten 4", "English", "US", "Not stated by the model", "Light, quick (small model)"),
        VoiceOption("kitten-4", "kitten-nano", 4, "Kitten 5", "English", "US", "Not stated by the model", "Light, quick (small model)"),
        VoiceOption("kitten-5", "kitten-nano", 5, "Kitten 6", "English", "US", "Not stated by the model", "Light, quick (small model)"),
        VoiceOption("kitten-6", "kitten-nano", 6, "Kitten 7", "English", "US", "Not stated by the model", "Light, quick (small model)"),
        VoiceOption("kitten-7", "kitten-nano", 7, "Kitten 8", "English", "US", "Not stated by the model", "Light, quick (small model)"),
        VoiceOption("northern", "piper-northern", 0, "Northern", "English", "Northern English", "Masculine", "Grounded, regional"),
        VoiceOption("arctic-rms", "piper-arctic", 1, "Arctic (rms)", "English", "US", "Masculine", "Deep, plain"),
        VoiceOption("lewis", "kokoro-en", 10, "Lewis", "English", "British", "Masculine", "Deep, most expressive (large model, slower)"),
        VoiceOption("pratham", "piper-hi-pratham", 0, "Pratham", "Hindi", "Indian", "Masculine", "For Hindi text"),
    )
    fun voice(id: String) = voices.firstOrNull { it.id == id }
    fun pack(id: String) = packs.firstOrNull { it.id == id }
    const val DEFAULT_VOICE = "joe"
}

/**
 * Where the speech models are: the ones installed with CLU (read-only, [bundled]) and the ones the owner downloaded ([user]).
 * A voice is usable when its folder holds model.onnx and tokens.txt (and voices.bin for multi-speaker families).
 */
class SpeechModels(val bundled: File?, val user: File) {
    fun packDir(id: String): File? = listOfNotNull(bundled?.let { File(it, "voices/$id") }, File(user, "voices/$id")).firstOrNull { complete(it, VoiceCatalog.pack(id)) }
    fun installed(id: String) = packDir(id) != null
    fun espeak(): File? = listOfNotNull(bundled?.let { File(it, "espeak-ng-data") }, File(user, "espeak-ng-data")).firstOrNull { File(it, "phontab").isFile }
    val vad: File? get() = bundled?.let { File(it, "vad/silero_vad.onnx") }?.takeIf { it.isFile }
    val kroko: File? get() = bundled?.let { File(it, "stt-kroko-en") }?.takeIf { File(it, "encoder.onnx").isFile }
    val whisper: File? get() = listOfNotNull(bundled?.let { File(it, "optional/stt-whisper-base") }, File(user, "stt-whisper-base")).firstOrNull { File(it, "encoder.onnx").isFile }
    private fun complete(d: File, p: VoicePack?): Boolean = File(d, "model.onnx").isFile && File(d, "tokens.txt").isFile && (p?.kind == TtsKind.VITS || File(d, "voices.bin").isFile)

    companion object {
        /** `-Dclu.voiceModels`, or voice-models next to CLU's program folder, or the build output. */
        fun locate(): File? {
            System.getProperty("clu.voiceModels")?.let { return File(it).takeIf { f -> f.isDirectory } }
            val jar = runCatching { File(SpeechModels::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
            return listOfNotNull(jar?.parentFile?.parentFile?.let { File(it, "voice-models") }, File("build/voice-models"), File("../build/voice-models")).firstOrNull { File(it, "vad").isDirectory }
        }
    }
}

// ------------------------------------------------------------------------------------------------ audio helpers

object Pcm {
    fun toFloat(s: ShortArray): FloatArray = FloatArray(s.size) { s[it] / 32768f }
    fun toShort(f: FloatArray, gain: Float = 1f): ShortArray = ShortArray(f.size) { (f[it] * gain * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() }
    fun rms(f: FloatArray): Double { if (f.isEmpty()) return 0.0; var s = 0.0; for (x in f) s += x * x; return Math.sqrt(s / f.size) }

    /** Windowed-sinc resampling (16 taps each side): clean enough for speech, no aliasing whine. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val ratio = to.toDouble() / from; val n = floor(x.size * ratio).toInt(); val out = FloatArray(n)
        val cutoff = min(1.0, ratio); val taps = 16
        for (i in 0 until n) {
            val center = i / ratio; val lo = floor(center).toInt() - taps + 1; var acc = 0.0; var wsum = 0.0
            for (j in lo until lo + 2 * taps) {
                if (j < 0 || j >= x.size) continue
                val t = center - j; val arg = PI * t * cutoff
                val sinc = if (abs(arg) < 1e-9) 1.0 else sin(arg) / arg
                val w = 0.5 + 0.5 * Math.cos(PI * t / taps).let { if (abs(t) > taps) -1.0 else it }
                val k = sinc * w * cutoff; acc += x[j] * k; wsum += k
            }
            out[i] = if (wsum != 0.0) (acc / wsum * cutoff / cutoff).toFloat() else 0f
        }
        return out
    }

    /** A 16-bit mono WAV file in memory (for previews in the window). */
    fun wav(pcm: ShortArray, rate: Int): ByteArray {
        val data = pcm.size * 2; val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data); pcm.forEach { b.putShort(it) }
        return b.array()
    }
}

// ------------------------------------------------------------------------------------------------ engines

/** Silero VAD over 16 kHz audio in 512-sample windows. [speaking] is true while speech is going on. */
class Vad(model: File, threshold: Float = 0.5f, minSilence: Float = 0.6f, minSpeech: Float = 0.2f) : AutoCloseable {
    private val h = NativeVoice.vadCreate(model.absolutePath, threshold, minSilence, minSpeech, 20f, 1).also { require(it != 0L) { "the voice activity detector could not be loaded" } }
    private val buf = FloatArray(512); private var n = 0
    @Volatile var speaking = false; private set
    /** Feeds audio; returns the finished speech segments (if any). */
    fun accept(samples: FloatArray): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        for (s in samples) { buf[n++] = s; if (n == 512) { NativeVoice.vadAccept(h, buf.copyOf()); n = 0; speaking = NativeVoice.vadDetected(h)
            while (true) out += (NativeVoice.vadPop(h) ?: break) } }
        return out
    }
    fun reset() { NativeVoice.vadReset(h); n = 0; speaking = false }
    override fun close() = NativeVoice.vadDestroy(h)
}

/** Speech recognition for one utterance at a time. */
interface SpeechRecognizer : AutoCloseable {
    val name: String
    /** Begins a new utterance (pre-roll audio may be passed in). */
    fun begin(preRoll: FloatArray)
    fun accept(samples: FloatArray)
    /** What has been recognised so far (cheap enough to call every ~200 ms). */
    fun partial(): String
    /** Ends the utterance and returns the final text. */
    fun finish(): String
}

/** Streaming transducer (Kroko English) loaded once; each conversation gets its own [session]. Words appear while the owner speaks. */
class StreamingRecognizerModel(dir: File, threads: Int = 2) : AutoCloseable {
    val name = "Kroko streaming English (sherpa-onnx)"
    internal val rec = NativeVoice.asrCreate(File(dir, "encoder.onnx").path, File(dir, "decoder.onnx").path, File(dir, "joiner.onnx").path, File(dir, "tokens.txt").path, threads)
        .also { require(it != 0L) { "the speech recogniser could not be loaded" } }
    internal val lock = Any()
    fun session(): SpeechRecognizer = Session()
    override fun close() = NativeVoice.asrDestroy(rec)

    private inner class Session : SpeechRecognizer {
        override val name get() = this@StreamingRecognizerModel.name
        private var stream = 0L
        override fun begin(preRoll: FloatArray) = synchronized(lock) { if (stream != 0L) NativeVoice.asrStreamDestroy(stream); stream = NativeVoice.asrStream(rec); if (preRoll.isNotEmpty()) NativeVoice.asrAccept(stream, 16000, preRoll) }
        override fun accept(samples: FloatArray) = synchronized(lock) { if (stream != 0L) NativeVoice.asrAccept(stream, 16000, samples) }
        override fun partial(): String = synchronized(lock) { if (stream == 0L) "" else NativeVoice.asrDecode(rec, stream).trim() }
        override fun finish(): String = synchronized(lock) {
            if (stream == 0L) return ""
            NativeVoice.asrAccept(stream, 16000, FloatArray(16000 * 3 / 2)) // the model emits its last word only after enough look-ahead (measured: 1.5 s)
            NativeVoice.asrInputFinished(stream)
            val t = NativeVoice.asrDecode(rec, stream).trim()
            NativeVoice.asrStreamDestroy(stream); stream = 0L
            t
        }
        override fun close() = synchronized(lock) { if (stream != 0L) NativeVoice.asrStreamDestroy(stream); stream = 0L }
    }
}

/** A recogniser that owns its model (tests, one-off use). */
class StreamingRecognizer(dir: File, threads: Int = 2) : SpeechRecognizer {
    private val model = StreamingRecognizerModel(dir, threads); private val s = model.session()
    override val name get() = model.name
    override fun begin(preRoll: FloatArray) = s.begin(preRoll)
    override fun accept(samples: FloatArray) = s.accept(samples)
    override fun partial() = s.partial()
    override fun finish() = s.finish()
    override fun close() { s.close(); model.close() }
}

/** Whisper base (multilingual, optional): decodes the utterance so far for partial results, and once at the end. [language] "" = detect. */
class WhisperModel(dir: File, language: String, threads: Int = 4) : AutoCloseable {
    val name = "Whisper base (sherpa-onnx)"
    private val rec = NativeVoice.whisperCreate(File(dir, "encoder.onnx").path, File(dir, "decoder.onnx").path, File(dir, "tokens.txt").path, language, threads)
        .also { require(it != 0L) { "Whisper could not be loaded" } }
    @Volatile var detectedLanguage = ""; private set
    @Synchronized fun decode(a: FloatArray): String = runCatching { val o = Json.parseToJsonElement(NativeVoice.whisperDecode(rec, a)).jsonObject
        detectedLanguage = o["lang"]?.jsonPrimitive?.contentOrNull ?: ""; o["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: "" }.getOrDefault("")
    fun session(): SpeechRecognizer = object : SpeechRecognizer {
        override val name get() = this@WhisperModel.name
        private var audio = FloatArray(0); private var lastAt = 0; private var last = ""
        override fun begin(preRoll: FloatArray) { audio = preRoll.copyOf(); lastAt = 0; last = "" }
        override fun accept(samples: FloatArray) { if (audio.size < 16000 * 30) audio += samples }
        override fun partial(): String { if (audio.size - lastAt < 16000) return last; lastAt = audio.size; last = decode(audio); return last } // one re-decode per second of new audio
        override fun finish(): String { val t = decode(audio); audio = FloatArray(0); return t }
        override fun close() {}
    }
    override fun close() = NativeVoice.whisperDestroy(rec)
}

/** One loaded voice pack. Synthesis is serialised per pack (sherpa-onnx models are not re-entrant). */
class TtsVoice(val pack: VoicePack, dir: File, espeak: File?, threads: Int = 2) : AutoCloseable {
    private val h = NativeVoice.ttsCreate(pack.kind.code, File(dir, "model.onnx").path, File(dir, "voices.bin").takeIf { it.isFile }?.path, File(dir, "tokens.txt").path, espeak?.path, threads)
        .also { require(it != 0L) { "the voice ${pack.title} could not be loaded" } }
    val sampleRate = NativeVoice.ttsSampleRate(h)
    val speakers = NativeVoice.ttsNumSpeakers(h)
    /** Synthesis calls so far. ONNX Runtime keeps memory for every input shape it has seen (measured: +0.7 MB per English
     *  sentence, more for unusual text, all returned on close), so VoiceEngines replaces a voice after many uses. */
    @Volatile var uses = 0; private set
    @Volatile var closed = false; private set
    @Synchronized fun synth(text: String, speaker: Int, speed: Float): FloatArray {
        if (closed) return FloatArray(0)
        uses++
        return NativeVoice.ttsGenerate(h, text, speaker.coerceIn(0, max(0, speakers - 1)), speed.coerceIn(0.5f, 2f)) ?: FloatArray(0)
    }
    @Synchronized override fun close() { if (!closed) { closed = true; NativeVoice.ttsDestroy(h) } }
}
