package ai.clu.voice

import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.voice.SpeechTextPreprocessor
import ai.clu.core.voice.VoiceConfig
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class Pcm(val samples: FloatArray, val rate: Int)

interface TtsBackend {
    val id: String
    /** Whether pitch is applied by the engine itself (then playback pitch stays 1.0). */
    val enginePitch: Boolean
    fun synthesize(text: String, cfg: VoiceConfig): Pcm?
    fun release() {}
}

class SherpaTtsBackend(private val engines: SherpaEngines) : TtsBackend {
    override val id = "sherpa"
    override val enginePitch = false
    override fun synthesize(text: String, cfg: VoiceConfig): Pcm? {
        val tts = engines.tts() ?: return null
        val sid = cfg.sherpaSpeakerId.coerceIn(0, maxOf(0, tts.numSpeakers() - 1))
        val a = tts.generate(text, sid = sid, speed = cfg.speed)
        return Pcm(a.samples, a.sampleRate)
    }
}

/**
 * Android TextToSpeech rendered to a file, then played through [AudioPlayer] so interruption, volume and the
 * metallic effect behave the same as with the Clu voice.
 */
class AndroidTtsBackend(context: Context, private val diag: DiagnosticsLog?) : TtsBackend {
    override val id = "android"
    override val enginePitch = true
    private val ready = CompletableDeferred<Boolean>()
    private val dir = File(context.cacheDir, "tts").apply { mkdirs() }
    private val counter = AtomicLong()
    private val done = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val tts = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { done.remove(id)?.complete(true) }
            @Deprecated("deprecated") override fun onError(id: String) { done.remove(id)?.complete(false) }
            override fun onError(id: String, code: Int) { done.remove(id)?.complete(false) }
        })
    }

    fun voices(): List<Voice> = runCatching { tts.voices.filter { it.locale.language == "en" }.sortedBy { it.name } }.getOrDefault(emptyList())

    /** Prefer the configured voice; otherwise an offline English voice whose name suggests a male speaker. */
    private fun pickVoice(cfg: VoiceConfig): Voice? {
        val vs = voices()
        if (cfg.androidVoice.isNotBlank()) vs.firstOrNull { it.name == cfg.androidVoice }?.let { return it }
        val offline = vs.filter { !it.isNetworkConnectionRequired }
        val maleHints = listOf("iom", "iol", "tpd", "rjs", "gbd", "ene", "end", "male")
        return offline.firstOrNull { v -> maleHints.any { v.name.lowercase().contains(it) } } ?: offline.firstOrNull()
    }

    override fun synthesize(text: String, cfg: VoiceConfig): Pcm? = runBlocking {
        if (withTimeoutOrNull(5000) { ready.await() } != true) { diag?.w("tts", "Android TTS not available"); return@runBlocking null }
        pickVoice(cfg)?.let { tts.voice = it } ?: run { tts.language = Locale.US }
        tts.setSpeechRate(cfg.speed)
        tts.setPitch(cfg.pitch)
        val id = "u" + counter.incrementAndGet()
        val f = File(dir, "$id.wav")
        val d = CompletableDeferred<Boolean>(); done[id] = d
        if (tts.synthesizeToFile(text, Bundle(), f, id) != TextToSpeech.SUCCESS) return@runBlocking null
        val ok = withTimeoutOrNull(15_000) { d.await() } ?: false
        val pcm = if (ok) readWav(f) else null
        f.delete()
        pcm
    }

    override fun release() { tts.shutdown() }

    companion object {
        /** Minimal PCM16 WAV reader. */
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

/**
 * Speech output queue: sentences are synthesised on a worker thread and played in order. stop() cancels both
 * pending synthesis and playback (barge-in).
 */
class SpeechOutput(private val context: Context, private val engines: SherpaEngines, private val diag: DiagnosticsLog?) {
    val player = AudioPlayer()
    private val synth = Executors.newSingleThreadExecutor { Thread(it, "clu-tts") }
    private val generation = AtomicLong()
    private val inFlight = java.util.concurrent.atomic.AtomicInteger()
    private var sherpa: TtsBackend? = null
    private var android: AndroidTtsBackend? = null
    @Volatile var lastBackend = ""
        private set

    fun backend(cfg: VoiceConfig): TtsBackend {
        if (cfg.ttsEngine == "sherpa" && engines.tts() != null) return sherpa ?: SherpaTtsBackend(engines).also { sherpa = it }
        return android ?: AndroidTtsBackend(context, diag).also { android = it }
    }

    /** Queues one sentence. [onFirstAudio] fires when its playback starts. */
    fun speak(text: String, cfg: VoiceConfig, onFirstAudio: (() -> Unit)? = null) {
        val gen = generation.get()
        val clean = SpeechTextPreprocessor(cfg.pronunciations).apply(text)
        if (clean.isBlank()) return
        inFlight.incrementAndGet()
        synth.execute { try { synthesizeAndQueue(clean, cfg, gen, onFirstAudio) } finally { inFlight.decrementAndGet() } }
    }

    private fun synthesizeAndQueue(clean: String, cfg: VoiceConfig, gen: Long, onFirstAudio: (() -> Unit)?) {
        run {
            if (gen != generation.get()) return@run
            val b = backend(cfg)
            val t0 = System.currentTimeMillis()
            val pcm = runCatching { b.synthesize(clean, cfg) }.onFailure { diag?.e("tts", "synthesis failed", it) }.getOrNull() ?: return@run
            diag?.perf("tts_ms_per_char", (System.currentTimeMillis() - t0).toDouble() / clean.length, b.id)
            lastBackend = b.id
            if (gen != generation.get()) return@run
            player.enqueue(pcm.samples, pcm.rate, if (b.enginePitch) 1f else cfg.pitch, cfg.volume, cfg.metallic, onFirstAudio)
        }
    }

    fun playTone(cfg: VoiceConfig) = player.enqueue(AudioPlayer.ackTone(), 22050, 1f, cfg.volume * 0.8f, 0.0)

    fun stop() { generation.incrementAndGet(); player.stop() }

    val busy get() = inFlight.get() > 0 || player.pending > 0

    fun androidVoices(): List<String> = (android ?: AndroidTtsBackend(context, diag).also { android = it }).voices().map { it.name }
}
