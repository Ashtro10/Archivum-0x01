package ai.clu.core.voice

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore

/**
 * The voices offered in the normal interface: a short curated list with human names, a preview and a size, instead
 * of engines and model files. Choosing one writes the underlying engine settings (so Diagnostics and the advanced
 * voice screen still show exactly what runs). Other engines remain available under Advanced.
 *
 * Measured on the build host (scripts/scan_voices.py, docs/VOICE.md; not yet on a phone):
 *  - Joe: Piper medium, median pitch 89 Hz, first phrase 129 ms, real-time factor 0.07. Fastest; flatter intonation
 *    (6.1 semitone range). Dataset CC0, but the model is fine-tuned from Piper's "lessac" voice (Blizzard 2013 data,
 *    research licence): fine for personal use; check before redistributing.
 *  - Lewis: Kokoro-82M v0.19 fp32 (Apache-2.0), speaker bm_lewis, median pitch 84 Hz, wider intonation (10.7
 *    semitones), but ~1 s to synthesise a sentence and real-time factor 0.54 on the host; 320 MB download.
 */
object ClueVoices {
    data class Voice(
        val id: String,
        val name: String,
        val description: String,
        val engine: String,
        val packId: String,
        val downloadBytes: Long,
        /** Bundled preview clip (APK asset), playable before anything is downloaded. */
        val previewAsset: String,
    )

    val JOE = Voice("joe", "Joe", "Deep, calm American voice. Starts speaking fastest.", TtsSelection.PIPER, "tts-joe", 67_169_394,
        "voices/preview-joe.wav")
    val LEWIS = Voice("lewis", "Lewis", "Deep British voice with the most natural, expressive delivery. Larger download, and each sentence takes longer to start.",
        TtsSelection.KOKORO, TtsSelection.KOKORO_PACK, 319_625_534, "voices/preview-lewis.wav")
    val all = listOf(JOE, LEWIS)
    val DEFAULT = JOE

    /** The curated voice the current settings select, or null when an advanced engine/voice is in use. */
    fun current(settings: SettingsStore): Voice? {
        val engine = TtsSelection.normalise(settings.string(Settings.TTS_ENGINE))
        return when (engine) {
            TtsSelection.KOKORO -> LEWIS
            TtsSelection.PIPER -> all.firstOrNull { it.engine == TtsSelection.PIPER && it.packId == settings.string(Settings.TTS_PIPER_VOICE) }
            else -> null
        }
    }

    /** Selects [v]: engine (and Piper voice) settings; persisted like every setting, so it survives updates. */
    fun select(settings: SettingsStore, v: Voice) {
        if (v.engine == TtsSelection.PIPER) { settings.set(Settings.TTS_PIPER_VOICE, v.packId); settings.set(Settings.TTS_SPEAKER, 0) }
        settings.set(Settings.TTS_ENGINE, v.engine)
    }
}
