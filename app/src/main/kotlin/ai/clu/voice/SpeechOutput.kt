package ai.clu.voice

import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.voice.SpeechTextPreprocessor
import ai.clu.core.voice.StreamingSpeech
import ai.clu.core.voice.TurnTimings
import ai.clu.core.voice.VoiceConfig
import ai.clu.core.voice.VoiceEffect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
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
    /** [lang]: "en" or "hi" (chunk script); engines without a voice for it use their default voice. */
    fun synthesize(text: String, cfg: VoiceConfig, lang: String = "en"): Pcm?
    fun release() {}
}

class SherpaTtsBackend(private val engines: SherpaEngines) : TtsBackend {
    override val id = "sherpa"
    override val enginePitch = false
    override fun synthesize(text: String, cfg: VoiceConfig, lang: String): Pcm? {
        // Devanagari chunks go to the Hindi voice pack when installed (the English voices cannot read Devanagari).
        val hindi = if (lang == "hi") engines.ttsHindi() else null
        val tts = hindi ?: engines.tts() ?: return null
        val sid = if (hindi != null) 0 else cfg.sherpaSpeakerId.coerceIn(0, maxOf(0, tts.numSpeakers() - 1))
        val a = tts.generate(text, sid = sid, speed = cfg.speed)
        return Pcm(a.samples, a.sampleRate)
    }
}

/**
 * Android TextToSpeech (any installed system engine, e.g. Google TTS or HayaiTTS) rendered to a file per chunk, then
 * played through [AudioTrackSink] so interruption, volume and the metallic effect behave the same as with the Clu voice.
 * Chunk-level streaming only: each chunk costs an IPC + file round trip.
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

    fun voices(language: String = "en"): List<Voice> = runCatching { tts.voices.filter { it.locale.language == language }.sortedBy { it.name } }.getOrDefault(emptyList())

    /** Prefer the configured voice; otherwise an offline voice for [lang] whose name suggests a male speaker. */
    private fun pickVoice(cfg: VoiceConfig, lang: String): Voice? {
        val vs = voices(lang)
        if (cfg.androidVoice.isNotBlank()) vs.firstOrNull { it.name == cfg.androidVoice }?.let { return it }
        val offline = vs.filter { !it.isNetworkConnectionRequired }
        val maleHints = listOf("iom", "iol", "tpd", "rjs", "gbd", "ene", "end", "male")
        return offline.firstOrNull { v -> maleHints.any { v.name.lowercase().contains(it) } } ?: offline.firstOrNull()
    }

    override fun synthesize(text: String, cfg: VoiceConfig, lang: String): Pcm? = runBlocking {
        if (withTimeoutOrNull(5000) { ready.await() } != true) { diag?.w("tts", "Android TTS not available"); return@runBlocking null }
        pickVoice(cfg, lang)?.let { tts.voice = it } ?: run { tts.language = if (lang == "hi") Locale("hi", "IN") else Locale.US }
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
 * Speech output for replies and announcements. Text goes through [StreamingSpeech]: a synthesis worker and a
 * playback worker run concurrently with generation, the audio queue is bounded, and [AudioTrackSink] plays the
 * chunks gaplessly. [stop] (barge-in) silences output at once and cancels synthesis, queued audio and the producer.
 */
class SpeechOutput(private val context: Context, private val engines: SherpaEngines, private val diag: DiagnosticsLog?) {
    val sink = AudioTrackSink()
    private val synthDispatcher = Executors.newSingleThreadExecutor { Thread(it, "clu-tts") }.asCoroutineDispatcher()
    private val playDispatcher = Executors.newSingleThreadExecutor { Thread(it, "clu-audio").apply { priority = Thread.MAX_PRIORITY } }.asCoroutineDispatcher()
    private var sherpa: TtsBackend? = null
    private var android: AndroidTtsBackend? = null
    @Volatile private var cfg: VoiceConfig? = null
    @Volatile var lastBackend = ""
        private set
    /** "communication" while a voice conversation uses call audio (set by [VoiceSession]); otherwise "media". */
    @Volatile var route = "media"

    val stream = StreamingSpeech(
        synth = { text, lang -> synthesize(text, lang) },
        sink = sink, synthDispatcher = synthDispatcher, playDispatcher = playDispatcher,
        onError = { m, e -> diag?.e("tts", m, e) },
    )

    fun backend(cfg: VoiceConfig): TtsBackend {
        if (cfg.ttsEngine == "sherpa" && engines.tts() != null) return sherpa ?: SherpaTtsBackend(engines).also { sherpa = it }
        return android ?: AndroidTtsBackend(context, diag).also { android = it }
    }

    private fun synthesize(text: String, lang: String): Pair<FloatArray, Int>? {
        val c = cfg ?: return null
        val clean = SpeechTextPreprocessor(c.pronunciations).apply(text)
        if (clean.isBlank()) return null
        val b = backend(c)
        val t0 = System.currentTimeMillis()
        val pcm = b.synthesize(clean, c, lang) ?: return null
        diag?.perf("tts_ms_per_char", (System.currentTimeMillis() - t0).toDouble() / clean.length, b.id)
        lastBackend = b.id
        val effect = VoiceEffect(pcm.rate, c.metallic)
        return effect.process(pcm.samples) to pcm.rate
    }

    // ------------------------------------------------------------------ audio focus
    private val audio by lazy { context.getSystemService(AudioManager::class.java) }
    private var focus: AudioFocusRequest? = null

    private fun usage() = if (route == "communication") AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT

    @Synchronized private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage()).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                // A call or another app took the audio: stop speaking (do not talk over a phone call).
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
            }.build()
        runCatching { audio.requestAudioFocus(req) }
        focus = req
    }

    @Synchronized private fun abandonFocus() { focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }; focus = null }

    // ------------------------------------------------------------------ responses
    /** Starts a streamed spoken reply (cancelling anything playing). Feed it with text()/finish(). */
    fun reply(cfg: VoiceConfig, timings: TurnTimings? = null, onCancel: () -> Unit = {}): StreamingSpeech.Response {
        synchronized(this) { queue.clear(); announcing = null }
        return begin(cfg, timings, onCancel)
    }

    private fun begin(cfg: VoiceConfig, timings: TurnTimings?, onCancel: () -> Unit): StreamingSpeech.Response {
        this.cfg = cfg
        val b = backend(cfg)
        sink.configure(if (b.enginePitch) 1f else cfg.pitch, cfg.volume, usage())
        requestFocus()
        val r = stream.start(timings, onCancel)
        r.completion.invokeOnCompletion { if (stream.current === r && !busy) abandonFocus() }
        return r
    }

    private class Announcement(val text: String?, val tone: Boolean, val cfg: VoiceConfig, val onFirstAudio: (() -> Unit)?)
    private val queue = ArrayDeque<Announcement>()
    private var announcing: StreamingSpeech.Response? = null

    /** Speaks a complete text (greeting, acknowledgement, proactive message) after any announcement already playing. */
    fun speak(text: String, cfg: VoiceConfig, onFirstAudio: (() -> Unit)? = null) = enqueue(Announcement(text, false, cfg, onFirstAudio))

    fun playTone(cfg: VoiceConfig) = enqueue(Announcement(null, true, cfg, null))

    @Synchronized private fun enqueue(a: Announcement) {
        queue.addLast(a)
        val cur = announcing
        if (cur == null || cur.done || stream.current !== cur) next()
    }

    @Synchronized private fun next() {
        val a = queue.removeFirstOrNull() ?: run { announcing = null; return }
        val r = begin(a.cfg, null) {}
        r.onFirstAudio = a.onFirstAudio
        announcing = r
        if (a.tone) { r.sound(Tones.ack(), 22_050); r.finish() } else r.say(a.text!!)
        r.completion.invokeOnCompletion { synchronized(this) { if (announcing === r && !r.cancelled) next() } }
    }

    /** Barge-in / stop button: silence now, drop everything queued, cancel the producer. */
    fun stop() {
        synchronized(this) { queue.clear(); announcing = null }
        stream.interrupt()
        abandonFocus()
    }

    val busy get() = stream.speaking || synchronized(this) { queue.isNotEmpty() }

    fun androidVoices(): List<String> = (android ?: AndroidTtsBackend(context, diag).also { android = it }).voices().map { it.name }

    fun release() { stop(); stream.shutdown(); sink.release(); synthDispatcher.close(); playDispatcher.close() }
}

object Tones {
    /** Short rising two-tone acknowledgement. */
    fun ack(rate: Int = 22_050): FloatArray {
        val n = (rate * 0.18).toInt()
        return FloatArray(n) { i ->
            val f = if (i < n / 2) 880.0 else 1320.0
            val env = kotlin.math.sin(Math.PI * (i % (n / 2)) / (n / 2))
            (0.25 * env * kotlin.math.sin(2 * Math.PI * f * i / rate)).toFloat()
        }
    }
}
