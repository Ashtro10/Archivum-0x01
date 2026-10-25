package ai.clu.voice

import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.voice.VoiceConfig
import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class Pcm(val samples: FloatArray, val rate: Int)

/** One synthesiser for one voice. Called on the synthesis worker only. Throws with a readable message on failure. */
interface TtsBackend {
    /** Shown to the owner, e.g. "Piper · en_US-bryce-medium". */
    val label: String
    /** Whether pitch is applied by the engine itself (then playback pitch stays 1.0). */
    val enginePitch: Boolean
    fun synthesize(text: String, cfg: VoiceConfig): Pcm
    /** Barge-in: abandon synthesis in progress if the engine supports it (its result is discarded anyway). */
    fun cancel() {}
}

/** Piper or KittenTTS model running in Clu's own sherpa-onnx runtime (in-process, no IPC). */
class SherpaBackend(override val label: String, private val tts: () -> OfflineTts, private val sid: Int) : TtsBackend {
    override val enginePitch = false
    override fun synthesize(text: String, cfg: VoiceConfig): Pcm {
        val t = tts()
        val a = t.generate(text, sid = sid.coerceIn(0, maxOf(0, t.numSpeakers() - 1)), speed = cfg.speed)
        check(a.samples.isNotEmpty()) { "$label produced no audio" }
        return Pcm(a.samples, a.sampleRate)
    }
}

/**
 * An installed Android TTS engine (HayaiTTS, Google, ...) bound by package through the public TextToSpeech API.
 * Audio is captured from the engine's synthesis callbacks (onBeginSynthesis/onAudioAvailable) and played through
 * Clu's own pipeline, so streaming, barge-in, volume and routing behave as with the on-device engines. Engines return
 * a phrase only when it is complete (HayaiTTS synthesises the whole request before streaming it out).
 */
class SystemTtsBackend(private val context: Context, val enginePackage: String, private val voiceName: String,
                       private val lang: String, private val diag: DiagnosticsLog?) : TtsBackend {
    override val enginePitch = true
    private val init = CompletableDeferred<Int>()
    private val counter = AtomicLong()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val dir = File(context.cacheDir, "tts").apply { mkdirs() }

    private class Job {
        val pcm = ByteArrayOutputStream(); var rate = 0; var format = AudioFormat.ENCODING_PCM_16BIT; var channels = 1
        val done = CompletableDeferred<String?>() // null = ok, else error
    }

    private val tts: TextToSpeech = run {
        val listener = TextToSpeech.OnInitListener { status -> init.complete(status) }
        if (enginePackage.isBlank()) TextToSpeech(context, listener) else TextToSpeech(context, listener, enginePackage)
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onBeginSynthesis(id: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                jobs[id]?.let { it.rate = sampleRateInHz; it.format = audioFormat; it.channels = channelCount }
            }
            override fun onAudioAvailable(id: String, audio: ByteArray) { jobs[id]?.pcm?.write(audio) }
            override fun onDone(id: String) { jobs[id]?.done?.complete(null) }
            @Deprecated("deprecated") override fun onError(id: String) { jobs[id]?.done?.complete("engine error") }
            override fun onError(id: String, code: Int) { jobs[id]?.done?.complete("engine error $code") }
            override fun onStop(id: String, interrupted: Boolean) { jobs[id]?.done?.complete("stopped") }
        })
    }

    override val label: String get() = "${engineLabel()} · ${voiceName.ifBlank { "engine default ($lang)" }}"

    private fun engineLabel() = runCatching { tts.engines.firstOrNull { it.name == effectivePackage() }?.label }.getOrNull()
        ?: effectivePackage().ifBlank { "Android TTS" }

    fun effectivePackage(): String = enginePackage.ifBlank { runCatching { tts.defaultEngine }.getOrNull() ?: "" }

    /** Null when the engine is ready, else why not (waits up to [timeoutMs] for the engine to start). */
    fun problem(timeoutMs: Long = 6000): String? = runBlocking {
        when (val st = withTimeoutOrNull(timeoutMs) { init.await() }) {
            null -> "${engineLabel()} did not start within ${timeoutMs / 1000} s"
            TextToSpeech.SUCCESS -> null
            else -> "${engineLabel()} failed to start (status $st)"
        }
    }

    fun voices(): List<Voice> = runCatching { tts.voices?.toList() ?: emptyList() }.getOrDefault(emptyList())

    override fun synthesize(text: String, cfg: VoiceConfig): Pcm = synthesizeWith(text, cfg, voiceName, lang)

    /** Synthesises with a specific engine voice (used by the deepest-voice finder). */
    @Synchronized
    fun synthesizeWith(text: String, cfg: VoiceConfig, voiceName: String, lang: String): Pcm = runBlocking {
        problem()?.let { error(it) }
        if (voiceName.isNotBlank()) {
            val v = voices().firstOrNull { it.name == voiceName } ?: error("voice '$voiceName' is not available in ${engineLabel()}")
            check(tts.setVoice(v) == TextToSpeech.SUCCESS) { "${engineLabel()} rejected voice '$voiceName'" }
        } else {
            val loc = if (lang == "hi") Locale("hi", "IN") else Locale.US
            val r = tts.setLanguage(loc)
            check(r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                "${engineLabel()} has no ${if (lang == "hi") "Hindi" else "English"} voice installed"
            }
        }
        tts.setSpeechRate(cfg.speed)
        tts.setPitch(cfg.pitch)
        val id = "clu" + counter.incrementAndGet()
        val job = Job(); jobs[id] = job
        val f = File(dir, "$id.wav")
        try {
            check(tts.synthesizeToFile(text, Bundle(), f, id) == TextToSpeech.SUCCESS) { "${engineLabel()} refused the request" }
            val err = withTimeoutOrNull(20_000) { job.done.await() } ?: "timed out"
            check(err == null) { "${engineLabel()}: $err" }
            decode(job) ?: readWav(f) ?: error("${engineLabel()} returned no audio")
        } finally { jobs.remove(id); f.delete() }
    }

    private fun decode(j: Job): Pcm? {
        val b = j.pcm.toByteArray()
        if (b.isEmpty() || j.rate <= 0) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val ch = maxOf(1, j.channels)
        val samples = when (j.format) {
            AudioFormat.ENCODING_PCM_FLOAT -> FloatArray(b.size / 4 / ch) { bb.getFloat(it * 4 * ch) }
            AudioFormat.ENCODING_PCM_8BIT -> FloatArray(b.size / ch) { ((b[it * ch].toInt() and 0xff) - 128) / 128f }
            else -> FloatArray(b.size / 2 / ch) { bb.getShort(it * 2 * ch) / 32768f }
        }
        return Pcm(samples, j.rate)
    }

    /** Tells the engine to stop the request in progress, so it stops using CPU after a barge-in. */
    override fun cancel() {
        runCatching { tts.stop() }
        jobs.values.forEach { it.done.complete("stopped") }
    }

    fun release() { runCatching { tts.shutdown() } }

    companion object {
        /** Minimal PCM16 WAV reader (engines that only write the file without streaming callbacks). */
        fun readWav(f: File): Pcm? {
            val b = runCatching { f.readBytes() }.getOrNull() ?: return null
            if (b.size < 44) return null
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12; var rate = 22050; var bits = 16; var channels = 1
            while (pos + 8 <= b.size) {
                val id = String(b, pos, 4, Charsets.US_ASCII); val len = bb.getInt(pos + 4)
                if (id == "fmt ") { channels = bb.getShort(pos + 10).toInt(); rate = bb.getInt(pos + 12); bits = bb.getShort(pos + 22).toInt() }
                if (id == "data") {
                    if (bits != 16) return null
                    val n = minOf(len, b.size - pos - 8) / 2 / channels
                    return Pcm(FloatArray(n) { bb.getShort(pos + 8 + it * 2 * channels) / 32768f }, rate)
                }
                pos += 8 + len + (len and 1)
            }
            return null
        }
    }
}
