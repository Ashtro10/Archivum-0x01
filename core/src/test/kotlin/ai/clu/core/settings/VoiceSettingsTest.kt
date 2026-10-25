package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.config.*
import ai.clu.core.voice.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*
import org.junit.jupiter.api.Test

class VoiceSettingsTest {
    private val core = newCore()
    private val s = core.settings
    private fun cfg() = VoiceConfig.from(s)

    @Test fun ttsEngine() {
        assertEquals("sherpa", cfg().ttsEngine)
        s.set(Settings.TTS_ENGINE, "android"); assertEquals("android", cfg().ttsEngine)
        assertTrue(Settings.TTS_ENGINE in VoiceConfig.pipelineKeys)
        assertIs<SettingResult.Invalid>(s.set(Settings.TTS_ENGINE, "espeak"))
    }

    @Test fun speaker() { s.set(Settings.TTS_SPEAKER, 3); assertEquals(3, cfg().sherpaSpeakerId); assertIs<SettingResult.Invalid>(s.set(Settings.TTS_SPEAKER, -1)) }
    @Test fun androidVoice() { s.set(Settings.ANDROID_VOICE, "en-in-x-ene-local"); assertEquals("en-in-x-ene-local", cfg().androidVoice) }
    @Test fun speed() { s.set(Settings.SPEED, 1.3); assertEquals(1.3f, cfg().speed); assertIs<SettingResult.Invalid>(s.set(Settings.SPEED, 3.0)) }
    @Test fun pitch() { s.set(Settings.PITCH, 0.7); assertEquals(0.7f, cfg().pitch); assertIs<SettingResult.Invalid>(s.set(Settings.PITCH, 0.1)) }

    @Test fun metallic() {
        val tone = FloatArray(1600) { (0.3 * sin(2 * PI * 220 * it / 16000)).toFloat() }
        s.set(Settings.METALLIC, 0.0)
        assertTrue(VoiceEffect(16000, cfg().metallic).isBypassed)
        assertContentEquals(tone, VoiceEffect(16000, cfg().metallic).process(tone))
        s.set(Settings.METALLIC, 0.8)
        val processed = VoiceEffect(16000, cfg().metallic).process(tone)
        val diff = tone.indices.sumOf { abs(tone[it] - processed[it]).toDouble() } / tone.size
        assertTrue(diff > 0.02, "metallic effect should audibly change the waveform (mean diff $diff)")
        assertTrue(processed.all { it in -1f..1f })
    }

    @Test fun volume() { s.set(Settings.VOLUME, 0.4); assertEquals(0.4f, cfg().volume) }

    @Test fun speakResponses() {
        val noon = core.clock.now()
        assertFalse(UiPolicy.shouldSpeak(s, viaVoice = false, nowMs = noon))
        assertTrue(UiPolicy.shouldSpeak(s, viaVoice = true, nowMs = noon))
        s.set(Settings.SPEAK_RESPONSES, true)
        assertTrue(UiPolicy.shouldSpeak(s, viaVoice = false, nowMs = noon))
    }

    @Test fun incremental() {
        assertTrue(cfg().incremental)
        val ch = SentenceChunker()
        val out = mutableListOf<String>()
        listOf("I have analysed your ", "schedule carefully. You are ", "overcommitted on Friday! Shall I ", "move something?").forEach { out += ch.push(it) }
        assertEquals(listOf("I have analysed your schedule carefully.", "You are overcommitted on Friday!"), out)
        assertEquals(listOf("Shall I move something?"), ch.flush())
        s.set(Settings.INCREMENTAL_TTS, false); assertFalse(cfg().incremental)
    }

    @Test fun bargeIn() {
        var t = 0L
        val tm = TurnManager(cfg()) { t }
        tm.start(wakeMode = false); tm.onUtterance("what's the weather"); tm.onSpeakingStarted()
        assertEquals(listOf(TurnAction.StopPlayback), tm.onSpeechStarted())
        assertEquals(TurnState.LISTENING, tm.state)
        s.set(Settings.BARGE_IN, false)
        val tm2 = TurnManager(cfg()) { t }
        tm2.start(false); tm2.onUtterance("tell me a story"); tm2.onSpeakingStarted()
        assertTrue(tm2.onSpeechStarted().isEmpty())
        assertEquals(TurnState.SPEAKING, tm2.state)
    }

    @Test fun outputRoute() {
        assertEquals("communication", cfg().outputRoute)
        s.set(Settings.OUTPUT_ROUTE, "media"); assertEquals("media", cfg().outputRoute)
        assertIs<SettingResult.Invalid>(s.set(Settings.OUTPUT_ROUTE, "bluetooth"))
    }
    @Test fun asrEngine() {
        assertEquals("whisper", cfg().asrEngine)
        s.set(Settings.ASR_ENGINE, "android_ondevice"); assertEquals("android_ondevice", cfg().asrEngine)
    }
    @Test fun whisperModel() { assertEquals("whisper-small", cfg().whisperModel); s.set(Settings.WHISPER_MODEL, "whisper-base"); assertEquals("whisper-base", cfg().whisperModel) }
    @Test fun sttLanguage() {
        assertEquals("auto", cfg().sttLanguage)
        s.set(Settings.STT_LANGUAGE, "hi"); assertEquals("hi", cfg().sttLanguage)
        assertIs<SettingResult.Invalid>(s.set(Settings.STT_LANGUAGE, "fr"))
    }

    @Test fun vadSensitivity() {
        s.set(Settings.VAD_SENSITIVITY, 0.0); val strict = cfg().vadThreshold
        s.set(Settings.VAD_SENSITIVITY, 1.0); val loose = cfg().vadThreshold
        assertEquals(0.75f, strict, 1e-6f); assertEquals(0.25f, loose, 1e-6f)
    }

    @Test fun endSilence() { s.set(Settings.END_SILENCE, 1200); assertEquals(1.2f, cfg().minSilenceSec); assertIs<SettingResult.Invalid>(s.set(Settings.END_SILENCE, 100)) }

    @Test fun ackMode() {
        assertEquals("tone", cfg().ackMode)
        s.set(Settings.ACK_MODE, "speech"); s.set(Settings.ACK_PHRASES, listOf("At your service."))
        assertEquals("speech", cfg().ackMode); assertEquals(listOf("At your service."), cfg().ackPhrases)
    }

    @Test fun micMode() {
        assertEquals(UiPolicy.MicBehavior.TAP_AUTO_STOP, UiPolicy.micBehavior(s))
        s.set(Settings.MIC_MODE, "hold"); assertEquals(UiPolicy.MicBehavior.HOLD_TO_TALK, UiPolicy.micBehavior(s))
        s.set(Settings.MIC_MODE, "conversation"); assertEquals(UiPolicy.MicBehavior.CONVERSATION, UiPolicy.micBehavior(s))
    }

    @Test fun continueListening() {
        val tm = TurnManager(cfg()) { 0 }
        tm.start(true); tm.onActivated(); tm.onUtterance("hello"); tm.onResponseComplete()
        assertEquals(TurnState.LISTENING, tm.state)
        s.set(Settings.CONTINUE_LISTENING, false)
        val tm2 = TurnManager(cfg()) { 0 }
        tm2.start(true); tm2.onActivated(); tm2.onUtterance("hello"); tm2.onResponseComplete()
        assertEquals(TurnState.WAITING_FOR_WAKE, tm2.state)
    }

    @Test fun followUpTimeout() {
        var t = 0L
        s.set(Settings.FOLLOWUP_TIMEOUT, 5)
        val tm = TurnManager(cfg()) { t }
        tm.start(true); tm.onActivated()
        t = 4_000; assertTrue(tm.tick().isEmpty())
        t = 5_500; assertEquals(listOf(TurnAction.SessionTimedOut), tm.tick())
        assertEquals(TurnState.WAITING_FOR_WAKE, tm.state)
    }

    @Test fun audioSource() { s.set(Settings.AUDIO_SOURCE, "mic"); assertEquals("mic", cfg().audioSource) }
    @Test fun noiseSuppression() { s.set(Settings.NOISE_SUPPRESSION, false); assertFalse(cfg().noiseSuppression) }
}
