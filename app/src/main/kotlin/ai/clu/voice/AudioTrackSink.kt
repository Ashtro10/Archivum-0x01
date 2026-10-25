package ai.clu.voice

import ai.clu.core.voice.AudioChunk
import ai.clu.core.voice.SpeechSink
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams

/**
 * Gapless speech output: one AudioTrack stays playing for a whole response and chunks are written back to back,
 * so there is no per-chunk start/drain/flush (the old player's audible gaps). Every chunk is converted to one
 * fixed output rate. Writes go in 10 ms blocks and re-check the response id between blocks; [stop] pauses and
 * flushes the track at once, so silence follows within one block plus the device's output latency.
 *
 * Thread use: [play]/[drain] on the playback worker; [begin]/[stop]/[configure] from any thread.
 */
class AudioTrackSink(private val outRate: Int = 22_050) : SpeechSink {
    private val lock = Object()
    @Volatile private var current = -1L
    private var track: AudioTrack? = null
    private var trackUsage = -1
    private var written = 0L         // frames written since the last flush
    private var headBase = 0L        // playback head at the last flush
    private var pendingFirst: (() -> Unit)? = null
    private var pendingFirstAt = 0L  // frame (since flush) at which the pending first-frame callback fires
    private val blockFrames = outRate / 100
    private val events = android.os.HandlerThread("clu-audio-events").apply { start() }
    private val eventHandler = android.os.Handler(events.looper)
    @Volatile var usage = AudioAttributes.USAGE_VOICE_COMMUNICATION
    @Volatile private var pitch = 1f
    @Volatile private var volume = 1f
    /** Chunks abandoned because the output device stopped accepting audio. */
    @Volatile var stalls = 0; private set

    fun configure(pitch: Float, volume: Float, usage: Int) {
        this.pitch = pitch.coerceIn(0.5f, 2f); this.volume = volume.coerceIn(0f, 1f); this.usage = usage
    }

    private fun ensureTrack(): AudioTrack {
        track?.let { if (trackUsage == usage) return it; it.release() }
        val min = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(outRate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            // ~120 ms of buffer: enough to ride over scheduling jitter, small enough that stop() is quick.
            .setBufferSizeInBytes(maxOf(min, outRate * 4 * 12 / 100))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        // The marker fires on the event thread when the first frame of a response reaches the speaker.
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) = synchronized(lock) { checkFirstFrame(track) }
            override fun onPeriodicNotification(track: AudioTrack) {}
        }, eventHandler)
        track = t; trackUsage = usage; written = 0; headBase = 0
        return t
    }

    override fun begin(responseId: Long) = synchronized(lock) {
        current = responseId
        val t = ensureTrack()
        runCatching { t.playbackParams = PlaybackParams().setSpeed(1f).setPitch(pitch) }
        t.setVolume(volume)
        Unit
    }

    override fun play(chunk: AudioChunk, onFirstFrame: (() -> Unit)?): Boolean {
        if (chunk.responseId != current) return false
        val pcm = if (chunk.sampleRate == outRate) chunk.samples else resample(chunk.samples, chunk.sampleRate, outRate)
        val t: AudioTrack
        synchronized(lock) {
            if (chunk.responseId != current) return false
            t = track ?: return false
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            if (onFirstFrame != null) {
                pendingFirst = onFirstFrame; pendingFirstAt = written
                t.notificationMarkerPosition = maxOf(1L, headBase + written + 1).toInt()
            }
        }
        var off = 0
        var lastProgress = System.currentTimeMillis()
        while (off < pcm.size) {
            val n = minOf(blockFrames, pcm.size - off)
            synchronized(lock) {
                if (chunk.responseId != current) return false
                // Non-blocking under the lock so stop() is never held up; back off briefly when the buffer is full.
                val w = t.write(pcm, off, n, AudioTrack.WRITE_NON_BLOCKING)
                if (w < 0) return false
                off += w; written += w
                if (w > 0) lastProgress = System.currentTimeMillis()
                checkFirstFrame(t)
            }
            // The device stopped consuming audio (routing change, output lost): give up on this chunk instead of spinning.
            if (System.currentTimeMillis() - lastProgress > STALL_MS) { stalls++; return false }
            if (off < pcm.size) Thread.sleep(4)
        }
        return true
    }

    // Fires the first-frame callback once the playback head has reached the chunk's first frame.
    private fun checkFirstFrame(t: AudioTrack) {
        val cb = pendingFirst ?: return
        val head = (t.playbackHeadPosition.toLong() and 0xffffffffL) - headBase
        if (head >= pendingFirstAt) { pendingFirst = null; cb() }
    }

    override fun drain(responseId: Long) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            synchronized(lock) {
                if (current != responseId) return
                val t = track ?: return
                checkFirstFrame(t)
                val head = (t.playbackHeadPosition.toLong() and 0xffffffffL) - headBase
                if (head >= written) { t.pause(); return } // all heard: pause (keeps the track for the next response)
            }
            Thread.sleep(10)
        }
    }

    override fun stop() = synchronized(lock) {
        current = -1L
        pendingFirst = null
        track?.let { t ->
            runCatching { t.pause(); t.flush() }
            headBase = t.playbackHeadPosition.toLong() and 0xffffffffL
            written = 0
        }
        Unit
    }

    fun release() { synchronized(lock) { current = -1L; track?.release(); track = null }; events.quitSafely() }

    companion object {
        const val STALL_MS = 1500L

        /** Linear-interpolation resampler (speech; the engines output 16-44.1 kHz). */
        fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
            if (x.isEmpty() || from == to) return x
            val n = (x.size.toLong() * to / from).toInt()
            val step = from.toDouble() / to
            return FloatArray(n) { i ->
                val p = i * step; val j = p.toInt(); val f = (p - j).toFloat()
                if (j + 1 < x.size) x[j] * (1 - f) + x[j + 1] * f else x[x.size - 1]
            }
        }
    }
}
