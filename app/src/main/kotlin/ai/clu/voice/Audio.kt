package ai.clu.voice

import ai.clu.core.voice.VoiceConfig
import android.annotation.SuppressLint
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

const val SAMPLE_RATE = 16000

/** Microphone capture at 16 kHz mono float in frames of [frameMs]. */
class AudioCapture(private val cfg: VoiceConfig, frameMs: Int = 100) {
    private var record: AudioRecord? = null
    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    val frameSamples = SAMPLE_RATE * frameMs / 1000
    private var pcm16 = false
    private val shortBuf = ShortArray(frameSamples)
    var effectsInfo = ""
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        val source = when (cfg.audioSource) {
            "voice_recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            "mic" -> MediaRecorder.AudioSource.MIC
            else -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
        // Float capture where supported; some devices only accept 16-bit PCM for a given source.
        var r = runCatching {
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, SAMPLE_RATE / 10 * 4 * 4))
        }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            r?.release()
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            r = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SAMPLE_RATE / 10 * 2 * 4))
            pcm16 = true
        }
        check(r.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable (permission revoked or in use)" }
        val info = mutableListOf<String>()
        if (cfg.noiseSuppression && NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }; info += "NS" }
        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION && AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }; info += "AEC"
        }
        effectsInfo = info.joinToString("+").ifEmpty { "none" } + if (pcm16) " · PCM16" else ""
        r.startRecording()
        record = r
    }

    /** Blocking read of one frame; returns null when stopped. */
    fun read(): FloatArray? {
        val r = record ?: return null
        val buf = FloatArray(frameSamples)
        if (pcm16) {
            var o = 0
            while (o < shortBuf.size) { val n = r.read(shortBuf, o, shortBuf.size - o, AudioRecord.READ_BLOCKING); if (n <= 0) return null; o += n }
            for (i in buf.indices) buf[i] = shortBuf[i] / 32768f
            return buf
        }
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
