package ai.clu.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * CLU's interface sounds, synthesised here (no recordings ship in the APK): soft sine tones and sweeps, quiet and short,
 * in the manner of a computer interface rather than a game. Pure arithmetic, testable on the JVM.
 */
object SfxSynth {
    const val RATE = 44_100
    enum class Sound { STARTUP, ACTIVATE, CLICK, NOTIFY, LISTEN, PROCESS, SHUTDOWN, TICK }

    /** 16-bit mono PCM for [s]. Every sound fades in and out (no clicks) and peaks well below full scale. */
    fun pcm(s: Sound): ShortArray {
        val f = when (s) {
            Sound.CLICK -> render(0.030) { t -> tone(t, 2300.0) * env(t, 0.002, 0.007) * 0.10 + tone(t, 4600.0) * env(t, 0.001, 0.003) * 0.03 }
            Sound.TICK -> render(0.014) { t -> tone(t, 3200.0) * env(t, 0.001, 0.003) * 0.05 }
            Sound.ACTIVATE -> render(0.24) { t ->
                val ph = sweepPhase(t, 520.0, 1320.0, 0.20)
                (sin(ph) * 0.75 + sin(2 * ph) * 0.18) * env(t, 0.012, 0.07) * 0.18 }
            Sound.LISTEN -> render(0.13) { t -> sin(sweepPhase(t, 480.0, 920.0, 0.11)) * env(t, 0.008, 0.035) * 0.15 }
            Sound.PROCESS -> render(0.16) { t ->
                val a = if (t < 0.045) env(t, 0.003, 0.012) else env(t - 0.085, 0.003, 0.014) * if (t >= 0.085) 1.0 else 0.0
                tone(t, 1040.0) * a * 0.11 }
            Sound.NOTIFY -> render(0.34) { t ->
                val n1 = tone(t, 1318.5) * env(t, 0.004, 0.06) * if (t < 0.12) 1.0 else exp(-(t - 0.12) * 40)
                val n2 = if (t >= 0.10) tone(t, 1760.0) * env(t - 0.10, 0.004, 0.08) else 0.0
                (n1 + n2) * 0.13 }
            Sound.SHUTDOWN -> render(0.42) { t -> (sin(sweepPhase(t, 880.0, 190.0, 0.40)) * 0.8 + tone(t, 110.0) * 0.2) * env(t, 0.01, 0.16) * 0.17 }
            Sound.STARTUP -> render(0.95) { t ->
                // A low swell, then three rising glints (A5, D6, A6), like an interface coming online.
                val pad = (tone(t, 110.0) * 0.55 + tone(t, 165.0) * 0.3 + tone(t, 220.0) * 0.15) * smooth(t / 0.45) * exp(-((t - 0.45).coerceAtLeast(0.0)) * 3.0)
                var glints = 0.0
                for ((at, hz) in listOf(0.28 to 880.0, 0.42 to 1174.7, 0.56 to 1760.0)) if (t >= at) glints += tone(t, hz) * env(t - at, 0.004, 0.09) * 0.5
                (pad * 0.6 + glints) * 0.2 }
        }
        // Final fade over the last 3 ms so every sound ends exactly at zero.
        val n = f.size; val fade = min(n, (RATE * 0.003).toInt())
        for (i in 0 until fade) f[n - 1 - i] *= i.toDouble() / fade
        return ShortArray(n) { (f[it].coerceIn(-1.0, 1.0) * 32767).toInt().toShort() }
    }

    private fun render(sec: Double, fn: (Double) -> Double) = DoubleArray((sec * RATE).toInt()) { fn(it.toDouble() / RATE) }
    private fun tone(t: Double, hz: Double) = sin(2 * PI * hz * t)
    /** Linear attack of [a] s, then exponential decay with time constant [d] s. */
    private fun env(t: Double, a: Double, d: Double) = if (t < 0) 0.0 else if (t < a) t / a else exp(-(t - a) / d)
    private fun smooth(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
    /** Phase of an exponential sweep from [f0] to [f1] Hz over [dur] s (held at f1 afterwards). */
    private fun sweepPhase(t: Double, f0: Double, f1: Double, dur: Double): Double {
        val k = Math.log(f1 / f0) / dur
        return if (t <= dur) 2 * PI * f0 * (exp(k * t) - 1) / k else 2 * PI * (f0 * (exp(k * dur) - 1) / k + f1 * (t - dur))
    }

    /** A standard RIFF/WAVE file (PCM 16-bit mono) for SoundPool. */
    fun wav(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(data)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }
}

/**
 * Plays the interface sounds. Off when the owner turns them off (Settings → General → Interface sounds), and silent while
 * the microphone is listening or CLU is speaking, so they never reach speech recognition or talk over CLU's voice.
 * Uses the sonification stream (follows the system's media/notification volume rules, no audio focus taken).
 */
object Sfx {
    @Volatile private var pool: SoundPool? = null
    private val ids = IntArray(SfxSynth.Sound.values().size)
    private val loaded = BooleanArray(ids.size)
    @Volatile var enabled: () -> Boolean = { false }
    /** True while sounds must stay silent (microphone open, CLU speaking). */
    @Volatile var quiet: () -> Boolean = { false }
    /** Every sound actually started, for tests and diagnostics (bounded). */
    val played = java.util.concurrent.ConcurrentLinkedDeque<SfxSynth.Sound>()
    private var lastClick = 0L

    fun init(ctx: Context, enabled: () -> Boolean, quiet: () -> Boolean) {
        this.enabled = enabled; this.quiet = quiet
        if (pool != null) return
        Thread({
            runCatching {
                val dir = File(ctx.cacheDir, "sfx-v1").apply { mkdirs() }
                val p = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
                p.setOnLoadCompleteListener { _, id, status -> val i = ids.indexOf(id); if (i >= 0 && status == 0) loaded[i] = true }
                for (s in SfxSynth.Sound.values()) {
                    val f = File(dir, s.name.lowercase() + ".wav")
                    if (!f.exists() || f.length() < 100) f.writeBytes(SfxSynth.wav(SfxSynth.pcm(s)))
                    ids[s.ordinal] = p.load(f.absolutePath, 1)
                }
                pool = p
            }
        }, "clu-sfx").apply { isDaemon = true }.start()
    }

    /** Plays [s] now if sounds are on and nothing is listening or speaking. Returns whether it was started. */
    fun play(s: SfxSynth.Sound): Boolean {
        if (!enabled() || quiet()) return false
        if (s == SfxSynth.Sound.CLICK || s == SfxSynth.Sound.TICK) {
            val now = System.currentTimeMillis(); if (now - lastClick < 45) return false; lastClick = now
        }
        played.add(s); while (played.size > 64) played.pollFirst()
        val p = pool ?: return true
        if (loaded[s.ordinal]) p.play(ids[s.ordinal], 1f, 1f, 0, 0, 1f)
        return true
    }

    fun release() { pool?.release(); pool = null; loaded.fill(false) }
}
