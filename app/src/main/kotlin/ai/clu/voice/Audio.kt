package ai.clu.voice

import ai.clu.core.voice.VoiceConfig
import ai.clu.core.voice.VoiceEffect
import android.annotation.SuppressLint
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

const val SAMPLE_RATE = 16000

/** Microphone capture at 16 kHz mono float, 100 ms frames. */
class AudioCapture(private val cfg: VoiceConfig) {
    private var record: AudioRecord? = null
    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    val frameSamples = SAMPLE_RATE / 10
    var effectsInfo = ""
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        val source = when (cfg.audioSource) {
            "voice_recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            "mic" -> MediaRecorder.AudioSource.MIC
            else -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val r = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, frameSamples * 4 * 4))
        check(r.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable (permission revoked or in use)" }
        val info = mutableListOf<String>()
        if (cfg.noiseSuppression && NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }; info += "NS" }
        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION && AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }; info += "AEC"
        }
        effectsInfo = info.joinToString("+").ifEmpty { "none" }
        r.startRecording()
        record = r
    }

    /** Blocking read of one frame; returns null when stopped. */
    fun read(): FloatArray? {
        val r = record ?: return null
        val buf = FloatArray(frameSamples)
        var off = 0
        while (off < buf.size) {
            val n = r.read(buf, off, buf.size - off, AudioRecord.READ_BLOCKING)
            if (n <= 0) return null
            off += n
        }
        return buf
    }

    fun stop() {
        runCatching { record?.stop() }; record?.release(); record = null
        ns?.release(); ns = null; aec?.release(); aec = null
    }
}

/** Fixed-size ring of recent audio, used for pre-roll before the recogniser starts. */
class AudioRing(seconds: Int) {
    private val buf = FloatArray(SAMPLE_RATE * seconds)
    private var pos = 0
    private var filled = 0
    @Synchronized fun add(x: FloatArray) { for (v in x) { buf[pos] = v; pos = (pos + 1) % buf.size }; filled = minOf(buf.size, filled + x.size) }
    @Synchronized fun last(samples: Int): FloatArray {
        val n = minOf(samples, filled)
        return FloatArray(n) { buf[(pos - n + it + buf.size) % buf.size] }
    }
}

/**
 * Interruptible speech player. Pitch uses AudioTrack PlaybackParams (independent of speed); volume and the metallic
 * effect are applied to the PCM. stop() takes effect within ~50 ms.
 */
class AudioPlayer {
    private data class Chunk(val samples: FloatArray, val rate: Int, val pitch: Float, val volume: Float, val metallic: Double, val onStart: (() -> Unit)?)
    private val queue = LinkedBlockingQueue<Chunk>()
    private val stopFlag = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    @Volatile var playing = false
        private set
    @Volatile private var track: AudioTrack? = null
    private var trackRate = 0
    var onIdle: (() -> Unit)? = null

    private val worker = Thread({ loop() }, "clu-audio").apply { isDaemon = true; start() }

    fun enqueue(samples: FloatArray, rate: Int, pitch: Float, volume: Float, metallic: Double, onStart: (() -> Unit)? = null) {
        queue.put(Chunk(samples, rate, pitch, volume, metallic, onStart))
    }

    val pending get() = queue.size + if (playing) 1 else 0

    fun stop() {
        generation.incrementAndGet()
        queue.clear()
        stopFlag.set(true)
        runCatching { track?.pause(); track?.flush() }
    }

    private fun ensureTrack(rate: Int): AudioTrack {
        track?.let { if (trackRate == rate) return it; it.release() }
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rate / 5 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t; trackRate = rate
        return t
    }

    private fun loop() {
        while (true) {
            val c = queue.take()
            val gen = generation.get()
            stopFlag.set(false)
            playing = true
            try {
                val t = ensureTrack(c.rate)
                runCatching { t.playbackParams = PlaybackParams().setSpeed(1f).setPitch(c.pitch.coerceIn(0.5f, 2f)) }
                t.setVolume(c.volume.coerceIn(0f, 1f))
                val pcm = VoiceEffect(c.rate, c.metallic).process(c.samples)
                t.play()
                c.onStart?.invoke()
                var off = 0
                val step = c.rate / 20
                while (off < pcm.size && !stopFlag.get() && gen == generation.get()) {
                    val n = minOf(step, pcm.size - off)
                    t.write(pcm, off, n, AudioTrack.WRITE_BLOCKING)
                    off += n
                }
                if (!stopFlag.get()) {
                    // Wait for the buffered tail to play out.
                    val end = System.currentTimeMillis() + 2000
                    while (t.playbackHeadPosition < pcm.size && System.currentTimeMillis() < end && !stopFlag.get()) Thread.sleep(10)
                }
                t.pause(); t.flush()
            } catch (e: Exception) {
                // Device audio failure: drop this chunk, keep the worker alive.
            } finally {
                playing = false
                if (queue.isEmpty()) onIdle?.invoke()
            }
        }
    }

    companion object {
        /** Short rising two-tone acknowledgement. */
        fun ackTone(rate: Int = 22050): FloatArray {
            val n = (rate * 0.18).toInt()
            return FloatArray(n) { i ->
                val f = if (i < n / 2) 880.0 else 1320.0
                val env = sin(PI * (i % (n / 2)) / (n / 2))
                (0.25 * env * sin(2 * PI * f * i / rate)).toFloat()
            }
        }
    }
}
