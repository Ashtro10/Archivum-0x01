package ai.clu.core.voice

/**
 * Which text-to-speech engine speaks, decided only from the owner's settings and what is actually installed.
 * There is no fallback between engines: if the selected engine cannot run, [TtsPlan.problem] says why and nothing is
 * spoken until the owner installs it or picks another engine. Hindi text goes to an explicitly configured Hindi voice.
 */
object TtsSelection {
    const val PIPER = "piper"
    const val KITTEN = "kitten"
    const val HAYAI = "hayai"
    const val SYSTEM = "system"
    const val HAYAI_PACKAGE = "dev.ahmedmohamed.hayaitts"

    /** Hindi routing: the selected engine's own Hindi voice, the Piper Pratham pack, or do not speak Hindi text. */
    const val HINDI_ENGINE = "engine"
    const val HINDI_PRATHAM = "pratham"
    const val HINDI_OFF = "off"

    /** Engine ids stored by earlier versions. */
    fun normalise(engine: String): String = when (engine) {
        "sherpa" -> PIPER
        "android" -> SYSTEM
        else -> engine
    }

    fun label(engine: String): String = when (normalise(engine)) {
        PIPER -> "Piper (on-device, sherpa-onnx)"
        KITTEN -> "KittenTTS nano (on-device, sherpa-onnx; English only)"
        HAYAI -> "HayaiTTS (installed app, Android TTS service)"
        SYSTEM -> "Android system TTS engine"
        else -> "unknown engine '$engine'"
    }

    /** What the device offers right now (filled in by the app). */
    data class Facts(
        val installedPacks: Set<String>,
        /** Package names of installed Android TTS engines (TextToSpeech.getEngines()). */
        val systemEngines: Set<String>,
        val defaultSystemEngine: String?,
    )

    data class Route(val engine: String, val voice: String, val packId: String? = null)

    data class TtsPlan(
        val engine: String,
        val english: Route?,
        /** Null when Hindi text will not be spoken; see [hindiNote]. */
        val hindi: Route?,
        val problem: String?,
        val hindiNote: String?,
        /** For system engines: the package to bind ("" = the device default engine). */
        val systemPackage: String? = null,
    ) {
        val available get() = problem == null && english != null
        val languages: List<String> get() = listOfNotNull(english?.let { "English" }, hindi?.let { "Hindi" })
    }

    fun plan(cfg: VoiceConfig, f: Facts): TtsPlan {
        val engine = normalise(cfg.ttsEngine)
        var problem: String? = null
        var systemPackage: String? = null
        val english: Route? = when (engine) {
            PIPER -> if (cfg.piperVoice in f.installedPacks) Route(PIPER, cfg.piperVoice, cfg.piperVoice)
                else {
                    val name = PIPER_VOICES.firstOrNull { it.first == cfg.piperVoice }?.second?.substringBefore(" –") ?: cfg.piperVoice
                    val others = PIPER_VOICES.filter { it.first in f.installedPacks }.map { it.second.substringBefore(" –") }
                    problem = "The Piper voice $name is not installed. Download it on the Voice screen" +
                        (if (others.isNotEmpty()) ", or choose an installed voice (${others.joinToString()})" else "") + ", or choose another engine."
                    null
                }
            KITTEN -> if (KITTEN_PACK in f.installedPacks) Route(KITTEN, "speaker ${cfg.kittenSpeaker}", KITTEN_PACK)
                else { problem = "KittenTTS is not installed (31 MB download on the Voice screen). Choose another engine or install it."; null }
            HAYAI -> if (HAYAI_PACKAGE in f.systemEngines) { systemPackage = HAYAI_PACKAGE; Route(HAYAI, cfg.androidVoice.ifBlank { "HayaiTTS default voice" }) }
                else { problem = "HayaiTTS is not installed on this device (it is a separate app). Install it, or choose another engine."; null }
            SYSTEM -> {
                val pkg = cfg.systemEngine.ifBlank { f.defaultSystemEngine ?: "" }
                when {
                    f.systemEngines.isEmpty() -> { problem = "No Android TTS engine is installed. Choose another engine."; null }
                    pkg.isNotBlank() && pkg !in f.systemEngines -> { problem = "The Android TTS engine '$pkg' is not installed. Choose another engine."; null }
                    else -> { systemPackage = pkg; Route(SYSTEM, cfg.androidVoice.ifBlank { "engine default voice" }) }
                }
            }
            else -> { problem = "Unknown speech engine '${cfg.ttsEngine}'. Choose an engine in Settings → Voice."; null }
        }
        var hindiNote: String? = null
        val hindi: Route? = when (cfg.hindiVoice) {
            HINDI_OFF -> { hindiNote = "Hindi text is not spoken (Hindi voice: off)."; null }
            HINDI_PRATHAM -> if (PRATHAM_PACK in f.installedPacks) Route(PIPER, "Pratham (Piper hi_IN)", PRATHAM_PACK)
                else { hindiNote = "Hindi voice Pratham is not installed (67 MB on the Voice screen): Hindi text is not spoken."; null }
            HINDI_ENGINE -> when {
                english == null -> null
                engine == KITTEN -> { hindiNote = "KittenTTS only speaks English: Hindi text is not spoken. Choose Hindi voice = Pratham."; null }
                engine == PIPER -> { hindiNote = "Piper's English voices cannot read Hindi: choose Hindi voice = Pratham."; null }
                else -> Route(engine, cfg.androidVoiceHi.ifBlank { "engine's Hindi voice" })
            }
            else -> { hindiNote = "Unknown Hindi voice setting '${cfg.hindiVoice}'."; null }
        }
        return TtsPlan(engine, english, hindi, problem, hindiNote, systemPackage)
    }

    /**
     * Piper English voices offered, deepest measured first (YIN median F0 over three representative Clu replies, sherpa-onnx
     * 1.13.8 on the build host; scripts/scan_voices.py, docs/measurements/voice_scan_en.json).
     */
    val PIPER_VOICES = listOf(
        "tts-joe" to "Joe – US male, deepest natural voice tested (~90 Hz), clearest – recommended",
        "tts-arctic-rms" to "Arctic rms – US male, ~91 Hz, steady",
        "tts-alan" to "Alan – UK male, ~96 Hz, flat delivery",
        "tts-northern-male" to "Northern English male – UK, ~100 Hz",
        "tts-ryan" to "Ryan – US male, ~156 Hz (not deep; previous default)",
    )
    const val PIPER_DEFAULT = "tts-joe"
    const val KITTEN_PACK = "tts-kitten-nano"
    const val PRATHAM_PACK = "tts-hi-pratham"
}
