package ai.clu.voice

import ai.clu.core.diag.DiagnosticsLog
import ai.clu.core.voice.SpeechTextPreprocessor
import ai.clu.core.voice.StreamingSpeech
import ai.clu.core.voice.TtsSelection
import ai.clu.core.voice.TurnTimings
import ai.clu.core.voice.VoiceConfig
import ai.clu.core.voice.VoiceEffect
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/** What the owner sees about speech output: the plan from the settings, and what actually happened last. */
data class TtsStatus(
    val plan: TtsSelection.TtsPlan,
    /** The voice that spoke most recently (engine · voice), or "" if nothing was spoken yet. */
    val lastSpoke: String = "",
    /** The last synthesis failure, shown until a phrase succeeds again. */
    val lastError: String? = null,
)

/** An installed Android TTS engine. */
data class SystemEngine(val packageName: String, val label: String)

/**
 * Speech output for replies and announcements. Text goes through [StreamingSpeech]: a synthesis worker and a
 * playback worker run concurrently with generation, the audio queue is bounded, and [AudioTrackSink] plays the
 * chunks gaplessly. [stop] (barge-in) silences output at once and cancels synthesis, queued audio and the producer.
 *
 * Engine choice comes only from [TtsSelection.plan]: the selected engine speaks, or nothing does and [status] says
 * why. There is no automatic fallback to another engine.
 */
class SpeechOutput(private val context: Context, private val engines: SherpaEngines, private val packs: VoicePackManager,
                   private val diag: DiagnosticsLog?) {
    val sink = AudioTrackSink()
    private val synthDispatcher = Executors.newSingleThreadExecutor { Thread(it, "clu-tts") }.asCoroutineDispatcher()
    private val playDispatcher = Executors.newSingleThreadExecutor { Thread(it, "clu-audio").apply { priority = Thread.MAX_PRIORITY } }.asCoroutineDispatcher()
    @Volatile private var cfg: VoiceConfig? = null
    @Volatile private var plan: TtsSelection.TtsPlan? = null
    /** One backend per language ("en"/"hi"), with the configuration key it was built for. */
    private val backends = HashMap<String, Pair<String, TtsBackend>>()
    private val _status = MutableStateFlow(TtsStatus(TtsSelection.TtsPlan("", null, null, "not initialised", null)))
    val status: StateFlow<TtsStatus> get() = _status
    /** "communication" while a voice conversation uses call audio (set by [VoiceSession]); otherwise "media". */
    @Volatile var route = "media"

    val stream = StreamingSpeech(
        synth = { text, lang -> synthesize(text, lang) },
        sink = sink, synthDispatcher = synthDispatcher, playDispatcher = playDispatcher,
        onError = { m, e -> diag?.e("tts", m, e) },
    )

    /** Installed Android TTS engines (package visibility via the TTS_SERVICE query in the manifest). */
    fun systemEngines(): List<SystemEngine> = runCatching {
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { SystemEngine(it.serviceInfo.packageName, it.loadLabel(context.packageManager).toString()) }.distinctBy { it.packageName }
    }.getOrDefault(emptyList())

    fun defaultSystemEngine(): String? = runCatching { android.provider.Settings.Secure.getString(context.contentResolver, "tts_default_synth") }.getOrNull()

    fun facts() = TtsSelection.Facts(packs.installedIds(), systemEngines().map { it.packageName }.toSet(), defaultSystemEngine())

    /** Recomputes the plan for [cfg] and publishes it (Voice screen, settings, diagnostics). */
    fun refresh(cfg: VoiceConfig): TtsSelection.TtsPlan {
        val p = TtsSelection.plan(cfg, facts())
        plan = p
        _status.value = _status.value.copy(plan = p, lastError = p.problem)
        return p
    }

    /** The backend for [lang] under the current plan, or an exception explaining why there is none. */
    fun backendFor(cfg: VoiceConfig, lang: String): TtsBackend? {
        val p = plan ?: refresh(cfg)
        p.problem?.let { error(it) }
        val r = (if (lang == "hi") p.hindi else p.english) ?: return null
        val key = "${r.engine}|${r.packId}|${r.voice}|$lang|${p.systemPackage}|${cfg.sherpaSpeakerId}|${cfg.kittenSpeaker}"
        synchronized(backends) {
            backends[lang]?.let { (k, b) -> if (k == key) return b else (b as? SystemTtsBackend)?.release() }
            val b: TtsBackend = when (r.engine) {
                TtsSelection.PIPER -> {
                    val pack = VoicePacks.byId(r.packId!!)!!
                    val sid = if (lang == "hi") 0 else cfg.sherpaSpeakerId
                    SherpaBackend("Piper · ${pack.title}${if (sid > 0) " · speaker $sid" else ""}", { engines.tts(pack.id) }, sid)
                }
                TtsSelection.KOKORO -> SherpaBackend("Lewis (Kokoro)", { engines.tts(TtsSelection.KOKORO_PACK) }, TtsSelection.KOKORO_SPEAKER)
                TtsSelection.KITTEN -> SherpaBackend("KittenTTS nano · speaker ${cfg.kittenSpeaker}", { engines.tts(TtsSelection.KITTEN_PACK) }, cfg.kittenSpeaker)
                else -> SystemTtsBackend(context, p.systemPackage ?: "", if (lang == "hi") cfg.androidVoiceHi else cfg.androidVoice, lang, diag)
            }
            backends[lang] = key to b
            return b
        }
    }

    private fun synthesize(text: String, lang: String): Pair<FloatArray, Int>? {
        val c = cfg ?: return null
        // Notation becomes words (markdown, URLs, times, units...); the owner's pronunciation fixes apply after that.
        val clean = SpeechTextPreprocessor(c.pronunciations).apply(ai.clu.core.voice.SpeechText.forSpeech(text))
        if (clean.isBlank()) return null
        val b = try { backendFor(c, lang) } catch (e: Exception) { report(e.message ?: "speech engine unavailable"); throw e }
            ?: return null // no voice for this language (plan.hindiNote explains it on the Voice screen)
        val t0 = System.nanoTime() / 1_000_000
        val pcm = try { b.synthesize(clean, c) } catch (e: Exception) { report("${b.label}: ${e.message}"); throw e }
        diag?.perf("tts_ms_per_char", (System.nanoTime() / 1_000_000 - t0).toDouble() / clean.length, b.label)
        _status.value = _status.value.copy(lastSpoke = b.label, lastError = null)
        // Pause after the chunk by how it ends (sentence, clause, paragraph), topped up over the voice's own silence.
        return ai.clu.core.voice.SpeechText.withPause(text, VoiceEffect(pcm.rate, c.metallic).process(pcm.samples), pcm.rate) to pcm.rate
    }

    // ------------------------------------------------------------------ measurements (Voice screen; run off the main thread)
    data class VoiceMeasurement(val label: String, val lang: String, val firstPhraseMs: Long, val rtf: Double, val audioSec: Double,
                                val pitch: ai.clu.core.voice.PitchAnalysis.Stats?)

    /**
     * Measures the configured voice for [lang] on this device with representative Clu replies: synthesis time of a
     * short first phrase (what decides first-audio latency), real-time factor, and median pitch. Recorded in
     * Diagnostics → Performance.
     */
    fun measureVoice(cfg: VoiceConfig, lang: String): VoiceMeasurement {
        refresh(cfg)
        val b = backendFor(cfg, lang) ?: error((if (lang == "hi") plan?.hindiNote else plan?.problem) ?: "no voice for $lang")
        val texts = if (lang == "hi") SAMPLE_HI else SAMPLE_EN
        b.synthesize(if (lang == "hi") "ठीक है।" else "Ready.", cfg) // warm-up (model load)
        val t0 = System.nanoTime()
        val first = b.synthesize(texts[0], cfg)
        val firstMs = (System.nanoTime() - t0) / 1_000_000
        var synthNs = 0L; val audio = ArrayList<FloatArray>(); var rate = first.rate
        for (t in texts.drop(1)) { val t1 = System.nanoTime(); val p = b.synthesize(t, cfg); synthNs += System.nanoTime() - t1; audio += p.samples; rate = p.rate }
        val all = FloatArray(audio.sumOf { it.size }).also { var o = 0; for (a in audio) { a.copyInto(it, o); o += a.size } }
        val sec = all.size.toDouble() / rate
        val m = VoiceMeasurement(b.label, lang, firstMs, synthNs / 1e9 / sec, sec, ai.clu.core.voice.PitchAnalysis.stats(all, rate))
        diag?.perf("tts_first_phrase_ms", firstMs.toDouble(), "${b.label} $lang")
        diag?.perf("tts_rtf", m.rtf, "${b.label} $lang")
        m.pitch?.let { diag?.perf("tts_median_f0_hz", it.medianHz, "${b.label} $lang") }
        return m
    }

    /** Median pitch of each installed voice of a system engine for [lang] (at most [limit] voices), deepest first. */
    fun rankSystemVoices(cfg: VoiceConfig, enginePackage: String, lang: String, limit: Int = 40,
                         progress: (Int, Int) -> Unit = { _, _ -> }): List<Pair<String, Double?>> {
        val b = SystemTtsBackend(context, enginePackage, "", lang, diag)
        try {
            b.problem()?.let { error(it) }
            val voices = b.voices().filter { v -> v.locale.language == lang && !v.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features }.take(limit)
            val text = if (lang == "hi") SAMPLE_HI[1] else SAMPLE_EN[1]
            return voices.mapIndexed { i, v ->
                progress(i + 1, voices.size)
                v.name to runCatching { b.synthesizeWith(text, cfg, v.name, lang).let { ai.clu.core.voice.PitchAnalysis.stats(it.samples, it.rate)?.medianHz } }.getOrNull()
            }.sortedBy { it.second ?: 999.0 }
        } finally { b.release() }
    }

    /**
     * Plays a long reply through the real pipeline (text fed word by word at [wordsPerSec], like a model), then
     * interrupts it: returns the measured stage timings (first audio, gaps, interrupt-to-silence) on this device.
     */
    suspend fun benchmarkStreaming(cfg: VoiceConfig, wordsPerSec: Double = 12.0, interruptAfterMs: Long = 4000): Map<String, Double> {
        val timings = TurnTimings(0)
        val r = reply(cfg, timings)
        val words = SAMPLE_LONG.split(" ")
        val t0 = System.currentTimeMillis()
        for (w in words) { kotlinx.coroutines.delay((1000 / wordsPerSec).toLong()); r.text("$w ") }
        r.finish()
        val wait = interruptAfterMs - (System.currentTimeMillis() - t0)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        if (!r.done) stop()
        r.completion.await()
        val m = timings.metrics()
        m.forEach { (k, v) -> diag?.perf("bench_$k", v, status.value.lastSpoke) }
        return m
    }

    private fun report(problem: String) {
        if (_status.value.lastError != problem) diag?.w("tts", problem)
        _status.value = _status.value.copy(lastError = problem)
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
        if (cfg != this.cfg) refresh(cfg)
        this.cfg = cfg
        val systemEngine = plan?.engine == TtsSelection.HAYAI || plan?.engine == TtsSelection.SYSTEM
        sink.configure(if (systemEngine) 1f else cfg.pitch, cfg.volume, usage())
        requestFocus()
        val r = stream.start(timings, onCancel)
        r.completion.invokeOnCompletion { if (stream.current === r && !busy) abandonFocus() }
        return r
    }

    private class Announcement(val text: String?, val tone: Boolean, val cfg: VoiceConfig, val onFirstAudio: (() -> Unit)?)
    private val queue = ArrayDeque<Announcement>()
    private var announcing: StreamingSpeech.Response? = null
    @Volatile private var announcingWords = false

    /**
     * True while Clu is saying words outside a reply turn (spoken acknowledgement, greeting, proactive message). The
     * voice session ignores the microphone then; a plain tone is not words and does not mask the owner's speech.
     */
    val speakingWords: Boolean get() = announcingWords && announcing?.let { !it.done } == true

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
        announcingWords = !a.tone
        if (a.tone) { r.sound(Tones.ack(), 22_050); r.finish() } else r.say(a.text!!)
        r.completion.invokeOnCompletion { synchronized(this) { if (announcing === r && !r.cancelled) next() } }
    }

    /** Barge-in / stop button: silence now, drop everything queued, cancel the producer. */
    fun stop() {
        val t0 = System.nanoTime()
        val wasSpeaking = busy
        synchronized(this) { queue.clear(); announcing = null }
        stream.interrupt()
        synchronized(backends) { backends.values.forEach { it.second.cancel() } }
        abandonFocus()
        // Stop request → playback paused and flushed (Diagnostics → Performance). The device's own output latency
        // after pause() is not visible to the app and is not included.
        if (wasSpeaking) diag?.perf("stop_to_silence_ms", (System.nanoTime() - t0) / 1e6)
    }

    val busy get() = stream.speaking || synchronized(this) { queue.isNotEmpty() }

    fun release() {
        stop(); stream.shutdown(); sink.release(); synthDispatcher.close(); playDispatcher.close()
        synchronized(backends) { backends.values.forEach { (it.second as? SystemTtsBackend)?.release() }; backends.clear() }
    }
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

private val SAMPLE_EN = listOf(
    "Good evening.",
    "All systems are running normally, and I have nothing urgent to report.",
    "Your meeting with the design team starts at nine tomorrow morning. I will remind you an hour before.",
    "I could not reach the thermostat. Check that it is powered on, and I will try again in a minute.")
private val SAMPLE_HI = listOf(
    "नमस्ते।",
    "सभी सिस्टम सामान्य रूप से चल रहे हैं।",
    "कल सुबह नौ बजे आपकी मीटिंग है। मैं एक घंटा पहले याद दिला दूँगा।",
    "मैं थर्मोस्टेट से संपर्क नहीं कर पाया। कृपया जाँच लें कि वह चालू है।")
private const val SAMPLE_LONG = "Of course. I looked into the maintenance schedule, and the next inspection is due on Monday morning at nine, " +
    "so I have added a reminder for Sunday evening. The technician will need access to the main panel, and the power will be off " +
    "for about twenty minutes while they replace the old breaker. Everything else looks normal, and the backup battery is fully charged."
