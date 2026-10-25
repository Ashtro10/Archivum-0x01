package ai.clu

import ai.clu.core.voice.AudioChunk
import ai.clu.voice.AudioTrackSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The response-id guard of the gapless sink on the Android AudioTrack API (Robolectric shadows: no real audio timing). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35])
class AudioOutputTest {
    private fun chunk(id: Long, ms: Int = 50, rate: Int = 22_050) = AudioChunk(id, 0, "x", FloatArray(rate * ms / 1000) { 0.1f }, rate)

    @Test fun staleAudioIsNeverWrittenAfterStopOrANewResponse() {
        // Robolectric's AudioTrack does not consume audio, so accepted chunks end in the stall guard here; what is checked
        // is that stale chunks are rejected immediately, before anything is written.
        val sink = AudioTrackSink()
        fun timed(block: () -> Boolean): Pair<Boolean, Long> { val t0 = System.nanoTime(); return block() to (System.nanoTime() - t0) / 1_000_000 }
        try {
            sink.begin(1)
            val (_, ms) = timed { sink.play(chunk(1), null) }
            assertTrue("a stalled output gives up instead of hanging", ms < AudioTrackSink.STALL_MS + 500)
            assertTrue(timed { sink.play(chunk(2), null) }.let { !it.first && it.second < 50 })
            sink.stop()
            assertTrue("stopped: rejected", timed { sink.play(chunk(1), null) }.let { !it.first && it.second < 50 })
            sink.begin(2)
            assertTrue("old response rejected after a new begin", timed { sink.play(chunk(1), null) }.let { !it.first && it.second < 50 })
        } finally { sink.release() }
    }

    @Test fun resamplerKeepsDurationAndShape() {
        val x = FloatArray(16_000) { kotlin.math.sin(2 * Math.PI * 200 * it / 16_000).toFloat() }
        val y = AudioTrackSink.resample(x, 16_000, 22_050)
        assertEquals(22_050, y.size)
        assertEquals(1.0f, y.maxOrNull()!!, 0.01f)
        assertSame(x, AudioTrackSink.resample(x, 16_000, 16_000))
    }
}
