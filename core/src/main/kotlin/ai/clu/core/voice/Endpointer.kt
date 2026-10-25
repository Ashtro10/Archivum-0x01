package ai.clu.core.voice

/**
 * Turn boundaries from the VAD. The VAD itself waits for the configured end-of-speech pause (its min-silence setting)
 * before it reports silence, so a pause shorter than that inside a sentence keeps the turn open.
 *
 * Measured on sentences with a thinking pause (scripts/eval_endpointing.py, docs/SPEECH_RECOGNITION.md): an 800 ms pause
 * setting ends the turn ~0.9 s after the last word and never split sentences at 0.4/0.7 s pauses; 650 ms split 12/30 at
 * 0.7 s. A "continue after 'and'/'the'" rule was tried and dropped: the live recogniser's words lag ~1.5 s, so the rule
 * rarely fired inside sentences and delayed real sentence ends.
 */
class Endpointer {
    var inSpeech = false; private set
    var speechStartAt = 0L; private set
    private var lastSpeechAt = 0L

    sealed class Event {
        object None : Event()
        object Started : Event()
        /** The turn is over after [speechMs] of speech. */
        data class Ended(val speechMs: Long) : Event()
    }

    fun onFrame(vadSpeech: Boolean, now: Long): Event {
        if (vadSpeech) {
            lastSpeechAt = now
            if (!inSpeech) { inSpeech = true; speechStartAt = now; return Event.Started }
            return Event.None
        }
        if (!inSpeech) return Event.None
        inSpeech = false
        return Event.Ended(lastSpeechAt - speechStartAt)
    }

    /** Forces the end of the turn (hold-to-talk released, "I'm done" in voice mode). */
    fun forceEnd(): Event.Ended? = if (!inSpeech) null else { inSpeech = false; Event.Ended(lastSpeechAt - speechStartAt) }

    fun reset() { inSpeech = false }
}
