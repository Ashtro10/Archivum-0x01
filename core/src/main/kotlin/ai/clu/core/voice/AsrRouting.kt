package ai.clu.core.voice

/**
 * Which recogniser produces the final transcript of an utterance, from the "Spoken language" setting and what is
 * installed. English: the live streaming recogniser (words appear while speaking, final ~0.1 s after the turn ends).
 * Hindi, mixed and automatic: Whisper after the turn ends (no streaming Hindi model exists for the offline runtime).
 * Without the Whisper model those fall back to English only, and [note] says so; the language setting is not changed.
 */
object AsrRouting {
    data class Plan(
        /** Whisper language ("en", "hi", "auto") for the final transcript, or null for the live English recogniser. */
        val whisperLanguage: String?,
        /** Live partial words from the streaming English recogniser are meaningful for this language. */
        val livePartials: Boolean,
        val note: String?,
    ) {
        val engineLabel get() = if (whisperLanguage == null) "live English recogniser" else "Whisper ($whisperLanguage)"
    }

    /** Engine ids stored by earlier versions. */
    fun normaliseEngine(e: String) = if (e == "whisper" || e == "sherpa") "clu" else e

    fun plan(language: String, whisperInstalled: Boolean): Plan = when (language) {
        "en" -> Plan(null, true, null)
        "hi", "mixed", "auto" -> {
            val wl = when (language) { "auto" -> "auto"; else -> "hi" } // mixed Hindi-English: Hindi decoding keeps both (measured)
            if (whisperInstalled) Plan(wl, language == "auto", null)
            else Plan(null, true, "Hindi and automatic language need the Whisper model (Voice → Speech recognition). Recognising English only until it is installed.")
        }
        else -> Plan(null, true, "Unknown language '$language'; recognising English.")
    }
}
