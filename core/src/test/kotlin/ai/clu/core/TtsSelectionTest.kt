package ai.clu.core

import ai.clu.core.config.Settings
import ai.clu.core.voice.PitchAnalysis
import ai.clu.core.voice.TtsSelection
import ai.clu.core.voice.TtsSelection.Facts
import ai.clu.core.voice.VoiceConfig
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*
import org.junit.jupiter.api.Test

class TtsSelectionTest {
    private val core = newCore()
    private val s = core.settings
    private fun cfg() = VoiceConfig.from(s)
    private val nothing = Facts(emptySet(), emptySet(), null)

    @Test fun selectedEngineIsNeverSilentlyReplaced() {
        s.set(Settings.TTS_ENGINE, "hayai")
        val p = TtsSelection.plan(cfg(), Facts(setOf(TtsSelection.PRATHAM_PACK, "tts-ryan"), setOf("com.google.android.tts"), "com.google.android.tts"))
        assertEquals("hayai", p.engine)
        assertFalse(p.available)
        assertNull(p.english, "no other engine is substituted")
        assertTrue(p.problem!!.contains("HayaiTTS is not installed"), p.problem)
    }

    @Test fun hayaiBindsItsOwnPackage() {
        s.set(Settings.TTS_ENGINE, "hayai")
        val p = TtsSelection.plan(cfg(), Facts(emptySet(), setOf(TtsSelection.HAYAI_PACKAGE, "com.google.android.tts"), "com.google.android.tts"))
        assertTrue(p.available); assertEquals(TtsSelection.HAYAI_PACKAGE, p.systemPackage)
    }

    @Test fun piperNeedsTheChosenVoiceNotJustAnyPack() {
        s.set(Settings.TTS_ENGINE, "piper")
        val chosen = cfg().piperVoice
        val other = Facts(setOf("tts-ryan", "tts-alan").minus(chosen), emptySet(), null)
        assertFalse(TtsSelection.plan(cfg(), other).available, "another installed Piper voice is not used instead")
        val ok = TtsSelection.plan(cfg(), Facts(setOf(chosen), emptySet(), null))
        assertTrue(ok.available); assertEquals(chosen, ok.english!!.packId)
    }

    @Test fun kittenIsEnglishOnlyAndHindiRoutingIsExplicit() {
        s.set(Settings.TTS_ENGINE, "kitten"); s.set(Settings.TTS_HINDI_VOICE, "engine")
        val p = TtsSelection.plan(cfg(), Facts(setOf(TtsSelection.KITTEN_PACK), emptySet(), null))
        assertTrue(p.available); assertNull(p.hindi); assertTrue(p.hindiNote!!.contains("only speaks English"))
        assertEquals(listOf("English"), p.languages)
        s.set(Settings.TTS_HINDI_VOICE, "pratham")
        val q = TtsSelection.plan(cfg(), Facts(setOf(TtsSelection.KITTEN_PACK, TtsSelection.PRATHAM_PACK), emptySet(), null))
        assertEquals(TtsSelection.PRATHAM_PACK, q.hindi!!.packId); assertEquals(listOf("English", "Hindi"), q.languages)
    }

    @Test fun systemEngineReportsMissingEngines() {
        s.set(Settings.TTS_ENGINE, "system")
        assertTrue(TtsSelection.plan(cfg(), nothing).problem!!.contains("No Android TTS engine"))
        s.set(Settings.TTS_SYSTEM_ENGINE, "org.example.tts")
        assertTrue(TtsSelection.plan(cfg(), Facts(emptySet(), setOf("com.google.android.tts"), null)).problem!!.contains("org.example.tts"))
    }

    @Test fun legacyEngineIdsMapToTheSameEngines() {
        assertEquals("piper", TtsSelection.normalise("sherpa"))
        assertEquals("system", TtsSelection.normalise("android"))
    }

    private fun voice(f0: Double, sr: Int): FloatArray {
        var phase = 0.0
        return FloatArray(sr * 2) { i ->
            val t = i.toDouble() / sr
            phase += 2 * PI * f0 * (1 + 0.03 * sin(2 * PI * 3 * t)) / sr // integrated phase (proper frequency modulation)
            var v = 0.0; for (h in 1..8) v += sin(h * phase) / h
            (0.2 * v).toFloat()
        }
    }

    @Test fun pitchOfAVoiceLikeSignalIsMeasured() {
        // Harmonic series with slight vibrato: deep male (90 Hz), male (110 Hz) and female (220 Hz) - no octave errors.
        val sr = 22_050
        assertEquals(90.0, PitchAnalysis.stats(voice(90.0, sr), sr)!!.medianHz, 3.0)
        assertEquals(220.0, PitchAnalysis.stats(voice(220.0, sr), 22_050)!!.medianHz, 5.0)
        assertEquals(220.0, PitchAnalysis.stats(voice(220.0, 24_000), 24_000)!!.medianHz, 5.0)
        val x = voice(110.0, sr)
        val st = PitchAnalysis.stats(x, sr)!!
        assertEquals(110.0, st.medianHz, 3.0)
        assertTrue(st.spreadSemitones in 0.5..2.0, "${st.spreadSemitones}")
        assertNull(PitchAnalysis.stats(FloatArray(sr), sr), "silence has no pitch")
    }
}
