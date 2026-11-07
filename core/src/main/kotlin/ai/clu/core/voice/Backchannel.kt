package ai.clu.core.voice

/**
 * Adaptive interruptions: while CLU is speaking, short listener noises ("mhm", "yeah", "right", "okay", "I see") are
 * the owner following along, not taking the turn, so CLU keeps talking and doesn't answer them. Anything with real
 * content ("wait", "no, I meant…", a question) still interrupts. Pure text rule over the recogniser's words.
 */
object Backchannel {
    private val WORDS = setOf("mhm", "mm", "mmm", "hmm", "hm", "uh", "huh", "uh-huh", "uhhuh", "um", "ah", "oh", "ok", "okay", "yeah", "yes", "yep",
        "yup", "right", "sure", "true", "exactly", "nice", "cool", "great", "alright", "fine", "got", "it", "i", "see", "go", "on", "interesting", "wow", "really", "indeed")
    /** Words that always mean "listen to me", even inside a short utterance. */
    private val TAKE_TURN = setOf("wait", "stop", "no", "hold", "but", "what", "why", "how", "pause", "sorry", "actually", "clu", "clue")

    /** True when [text] is only a backchannel: at most four words, all of them listener noises. */
    fun only(text: String): Boolean {
        val w = text.lowercase().replace(Regex("[^a-z\\- ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        return w.isNotEmpty() && w.size <= 4 && w.none { it in TAKE_TURN } && w.all { it in WORDS }
    }
}
