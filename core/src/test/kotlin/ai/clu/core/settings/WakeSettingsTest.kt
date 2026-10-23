package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.config.*
import ai.clu.core.voice.*
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.*
import org.junit.jupiter.api.Test

class WakeSettingsTest {
    private val core = newCore()
    private val s = core.settings
    private fun cfg() = VoiceConfig.from(s)

    companion object {
        const val SR = 16000
        /** Synthetic audio: background noise with optional clap bursts at given times (seconds). */
        fun audio(seconds: Double, noise: Double, claps: List<Double> = emptyList(), clapAmp: Double = 0.6, seed: Int = 1): FloatArray {
            val r = Random(seed)
            val a = FloatArray((seconds * SR).toInt()) { (noise * (r.nextDouble() * 2 - 1)).toFloat() }
            for (c in claps) {
                val start = (c * SR).toInt()
                for (i in 0 until (0.03 * SR).toInt()) {
                    val idx = start + i; if (idx >= a.size) break
                    val env = kotlin.math.exp(-i / (0.006 * SR))
                    a[idx] += (clapAmp * env * (r.nextDouble() * 2 - 1)).toFloat()
                }
            }
            return a
        }
        fun speechLike(seconds: Double, amp: Double = 0.4) = FloatArray((seconds * SR).toInt()) {
            val t = it.toDouble() / SR
            (amp * sin(2 * PI * 140 * t) * (0.6 + 0.4 * sin(2 * PI * 4 * t)) + 0.3 * amp * sin(2 * PI * 280 * t)).toFloat()
        }
        fun detect(det: ClapDetector, a: FloatArray): Boolean { var hit = false; a.toList().chunked(480).forEach { if (det.process(it.toFloatArray())) hit = true }; return hit }
    }

    @Test fun wakeEnabled() { assertTrue(cfg().wakeEnabled); s.set(Settings.WAKE_ENABLED, false); assertFalse(cfg().wakeEnabled) }

    @Test fun wakeWords() {
        val words = cfg().policy.words
        assertEquals("Clue", words.single { it.primary }.phrase)
        assertNotNull(WakePhraseMatcher.match("hey clue what time is it", words, 0.75)).let {
            assertEquals("Clue", it.word.phrase); assertEquals("what time is it", it.remainder)
        }
        // Add a new wake word, disable "Friend".
        s.setWakeWords(words.map { if (it.phrase == "Friend") it.copy(enabled = false) else it } + WakeWord("Jarvis"))
        val p = cfg().policy
        assertEquals("Jarvis", WakePhraseMatcher.match("jarvis open youtube", p.allowedWords(Environment.QUIET), 0.75)?.word?.phrase)
        assertNull(WakePhraseMatcher.match("friend are you there", p.allowedWords(Environment.QUIET), 0.75))
        // Validation: exactly one primary, no duplicates.
        assertIs<SettingResult.Invalid>(s.setWakeWords(listOf(WakeWord("A"), WakeWord("B"))))
        assertIs<SettingResult.Invalid>(s.setWakeWords(listOf(WakeWord("Clue", primary = true), WakeWord("clue"))))
        assertIs<SettingResult.Invalid>(s.setWakeWords(listOf(WakeWord("Clue", primary = true, enabled = false))))
    }

    @Test fun engine() {
        assertEquals("kws", cfg().wakeEngine)
        s.set(Settings.WAKE_ENGINE, "asr"); assertEquals("asr", cfg().wakeEngine)
        assertEquals(0.1f, cfg().kwsThreshold)
        s.set(Settings.KWS_THRESHOLD, 0.2); assertEquals(0.2f, cfg().kwsThreshold)
        assertIs<SettingResult.Invalid>(s.set(Settings.KWS_THRESHOLD, 0.9))
    }

    @Test fun threshold() {
        val words = cfg().policy.words
        assertNotNull(WakePhraseMatcher.match("glue what's up", words, 0.7))
        s.set(Settings.WAKE_THRESHOLD, 0.95)
        assertNull(WakePhraseMatcher.match("glue what's up", words, cfg().wakeThreshold))
        assertNotNull(WakePhraseMatcher.match("clue what's up", words, cfg().wakeThreshold))
    }

    @Test fun clap() {
        assertFalse(cfg().policy.clapAllowed(Environment.QUIET))
        s.set(Settings.CLAP_ENABLED, true)
        assertTrue(cfg().policy.clapAllowed(Environment.QUIET))
    }

    @Test fun clapSensitivity() {
        val soft = audio(1.5, 0.003, listOf(0.3, 0.7), clapAmp = 0.12)
        s.set(Settings.CLAP_SENSITIVITY, 0.0)
        assertFalse(detect(ClapDetector(SR, cfg().clapSensitivity, cfg().clapCount), soft), "strict setting must ignore soft claps")
        s.set(Settings.CLAP_SENSITIVITY, 1.0)
        assertTrue(detect(ClapDetector(SR, cfg().clapSensitivity, cfg().clapCount), soft), "sensitive setting must hear soft claps")
        // Speech-like audio never counts as claps, even at max sensitivity.
        assertFalse(detect(ClapDetector(SR, 1.0, 2), speechLike(2.0)))
    }

    @Test fun clapCount() {
        val double = audio(1.5, 0.003, listOf(0.3, 0.7))
        val single = audio(1.5, 0.003, listOf(0.3))
        assertTrue(detect(ClapDetector(SR, 0.5, cfg().clapCount), double))
        assertFalse(detect(ClapDetector(SR, 0.5, cfg().clapCount), single))
        s.set(Settings.CLAP_COUNT, 1)
        assertTrue(detect(ClapDetector(SR, 0.5, cfg().clapCount), single))
        s.set(Settings.CLAP_COUNT, 3)
        assertFalse(detect(ClapDetector(SR, 0.5, cfg().clapCount), double))
        assertTrue(detect(ClapDetector(SR, 0.5, 3), audio(2.0, 0.003, listOf(0.3, 0.7, 1.1))))
    }

    @Test fun noiseAdaptive() {
        // Real measurement: the estimator follows the actual noise floor of non-speech frames.
        val est = NoiseEstimator()
        audio(5.0, 0.002).toList().chunked(480).forEach { est.addFrame(it.toFloatArray(), false) }
        val quietDb = est.floorDb
        audio(12.0, 0.08, seed = 2).toList().chunked(480).forEach { est.addFrame(it.toFloatArray(), false) }
        val noisyDb = est.floorDb
        assertTrue(quietDb < -50 && noisyDb > -30.5, "quiet=$quietDb noisy=$noisyDb")
        // Speech frames are excluded from the estimate.
        val before = est.floorDb
        speechLike(2.0, 0.9).toList().chunked(480).forEach { est.addFrame(it.toFloatArray(), true) }
        assertEquals(before, est.floorDb)
        val p = cfg().policy
        assertEquals(Environment.QUIET, p.environment(quietDb)); assertEquals(Environment.NOISY, p.environment(noisyDb))
        s.set(Settings.NOISE_ADAPTIVE, false)
        assertEquals(Environment.QUIET, cfg().policy.environment(noisyDb))
    }

    @Test fun noisyThreshold() {
        assertEquals(Environment.QUIET, cfg().policy.environment(-45.0))
        s.set(Settings.NOISY_DB, -50.0)
        assertEquals(Environment.NOISY, cfg().policy.environment(-45.0))
    }

    @Test fun strictInNoise() {
        s.set(Settings.CLAP_ENABLED, true)
        val p = cfg().policy
        assertEquals(listOf("Clue"), p.allowedWords(Environment.NOISY).map { it.phrase })
        assertEquals(3, p.allowedWords(Environment.QUIET).size)
        assertFalse(p.clapAllowed(Environment.NOISY))
        s.set(Settings.STRICT_IN_NOISE, false)
        assertEquals(3, cfg().policy.allowedWords(Environment.NOISY).size)
        assertTrue(cfg().policy.clapAllowed(Environment.NOISY))
    }

    @Test fun background() {
        assertFalse(ServicePolicy.shouldRunListeningService(s, micPermission = true))
        s.set(Settings.BACKGROUND_LISTENING, true)
        assertTrue(ServicePolicy.shouldRunListeningService(s, micPermission = true))
        assertFalse(ServicePolicy.shouldRunListeningService(s, micPermission = false))
        s.set(Settings.WAKE_ENABLED, false)
        assertFalse(ServicePolicy.shouldRunListeningService(s, micPermission = true))
    }

    @Test fun termination() {
        val tm = TurnManager(cfg()) { 0 }
        tm.start(true); tm.onActivated()
        assertEquals(listOf(TurnAction.EndSession), tm.onUtterance("End of the line."))
        assertEquals(TurnState.WAITING_FOR_WAKE, tm.state)
        s.set(Settings.TERMINATION_PHRASES, listOf("that will be all"))
        val tm2 = TurnManager(cfg()) { 0 }
        tm2.start(true); tm2.onActivated()
        assertIs<TurnAction.Process>(tm2.onUtterance("end of the line").single())
        tm2.onResponseComplete()
        assertEquals(listOf(TurnAction.EndSession), tm2.onUtterance("okay that will be all"))
        s.set(Settings.TERMINATION_RESPONSES, listOf("{farewell}, {owner}."))
        s.set(Settings.OWNER_ADDRESS, "sir")
        assertTrue(core.templates.pick(Settings.TERMINATION_RESPONSES, core.clock.now()).endsWith(", sir."))
    }
}
