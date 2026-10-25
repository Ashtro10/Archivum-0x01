package ai.clu.core.voice

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Objective voice-depth measurement: fundamental frequency (F0) of voiced 50 ms frames with the YIN algorithm
 * (de Cheveigne & Kawahara 2002: cumulative-mean-normalised difference, absolute threshold 0.15, parabolic refinement,
 * 60-400 Hz). Same method as scripts/scan_voices.py, so device and host numbers compare. Checked against librosa pYIN
 * on male and female voices: medians agree within 1-10 Hz, without octave errors.
 * Adult male speech typically has a median F0 of about 85-155 Hz, female about 165-255 Hz.
 */
object PitchAnalysis {
    data class Stats(val medianHz: Double, val p10Hz: Double, val p90Hz: Double, val voicedFrames: Int) {
        /** Pitch range between the 10th and 90th percentile in semitones; very small = monotone. */
        val spreadSemitones get() = 12 * ln(p90Hz / p10Hz) / ln(2.0)
    }

    fun track(x: FloatArray, sr: Int): DoubleArray {
        val frame = (0.05 * sr).toInt(); val hop = (0.01 * sr).toInt()
        val tMin = sr / 400; val tMax = sr / 60; val w = frame - tMax
        if (x.size < frame || w <= 0) return DoubleArray(0)
        var e = 0.0; for (v in x) e += v * v
        val rmsAll = sqrt(e / x.size) + 1e-9
        val d = DoubleArray(tMax + 1); val cm = DoubleArray(tMax + 1)
        val out = ArrayList<Double>()
        var i = 0
        while (i + frame <= x.size) {
            var fe = 0.0; for (k in 0 until frame) fe += x[i + k] * x[i + k]
            if (sqrt(fe / frame) >= 0.3 * rmsAll) {
                for (t in 1..tMax) { var s = 0.0; for (k in 0 until w) { val q = x[i + k] - x[i + k + t]; s += q * q }; d[t] = s }
                var cs = 0.0; cm[0] = 1.0
                for (t in 1..tMax) { cs += d[t]; cm[t] = if (cs > 1e-12) d[t] * t / cs else 1.0 }
                var t = tMin
                while (t < tMax && cm[t] >= 0.15) t++
                if (t < tMax) {
                    while (t + 1 < tMax && cm[t + 1] < cm[t]) t++
                    if (cm[t] <= 0.35) { // clearly periodic (voiced)
                        val a = cm[t - 1]; val b = cm[t]; val c = cm[t + 1]; val dd = a - 2 * b + c
                        out += sr / (t + if (dd != 0.0) 0.5 * (a - c) / dd else 0.0)
                    }
                }
            }
            i += hop
        }
        return out.toDoubleArray()
    }

    /** Null when there are too few voiced frames to judge (silence, very short audio). */
    fun stats(x: FloatArray, sr: Int): Stats? {
        val f = track(x, sr).sorted()
        if (f.size < 20) return null
        fun q(p: Double) = f[((f.size - 1) * p).toInt()]
        return Stats(q(0.5), q(0.1), q(0.9), f.size)
    }
}
