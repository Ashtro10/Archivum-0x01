package ai.clu.core.learning

import ai.clu.core.config.Settings

/** One explicit preference change the owner asked for, and how Clu confirms it. */
data class FeedbackChange(val key: String, val value: Any, val confirmation: String)

/**
 * Turns explicit feedback about Clu's replies ("be more concise", "call me Ashwin", "no jokes") into the matching
 * setting, so it changes real behaviour and shows up in Settings → Conversation. Only short messages that are clearly
 * feedback match; nothing is inferred from ordinary conversation, and nothing about the owner is saved here.
 */
object FeedbackRouter {
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val lead = r("^(?:(?:hey|ok|okay)\\s+)?(?:clu|clue)[,!.]?\\s+|^(?:please|can you|could you|would you|i want you to|i'd like you to|from now on,?|in future,?|going forward,?)\\s+|\\s+(?:please|from now on|in future|going forward)[.!]*$|[.!]+$")

    private val rules: List<Pair<Regex, (MatchResult) -> FeedbackChange?>> = listOf(
        r("^(?:be |keep (?:it|your (?:answers|replies)) )?(?:more )?(?:shorter|briefer|brief|more concise|concise|less verbose)$|^(?:your )?(?:answers|replies) (?:are|were) too long$|^stop rambling$|^too long$") to { _ ->
            FeedbackChange(Settings.RESPONSE_LENGTH, "brief", "Understood. Shorter replies from now on.") },
        r("^(?:give me |i want )?(?:more detail(?:ed answers)?|longer (?:answers|replies)|more detailed (?:answers|replies))$|^(?:you're|you are) too brief$|^(?:answers|replies) (?:are|were) too short$|^elaborate more$") to { _ ->
            FeedbackChange(Settings.RESPONSE_LENGTH, "detailed", "Understood. More detailed replies from now on.") },
        r("^(?:be |speak |talk )?(?:more )?formal(?:ly)?$|^(?:be |speak )?more formally$") to { _ -> FeedbackChange(Settings.FORMALITY, "formal", "Understood. I'll keep it formal.") },
        r("^(?:be |speak |talk )?(?:more )?casual(?:ly)?$|^(?:be )?less formal$") to { _ -> FeedbackChange(Settings.FORMALITY, "casual", "Understood. More casual, then.") },
        r("^(?:just |only )?(?:give me )?the (?:answer|conclusion)s?(?: only)?$|^(?:skip|no need for) the explanations?$|^don't explain(?: your reasoning)?$") to { _ ->
            FeedbackChange(Settings.EXPLANATION, "minimal", "Understood. Conclusions only.") },
        r("^(?:explain|show) (?:your |the )?reasoning(?: step by step)?$|^explain (?:more|in more depth)$") to { _ ->
            FeedbackChange(Settings.EXPLANATION, "thorough", "Understood. I'll explain my reasoning step by step.") },
        r("^(?:no (?:more )?jokes|stop joking|be serious|no humou?r)$") to { _ -> FeedbackChange(Settings.HUMOR, "none", "Understood. No humour.") },
        r("^(?:only answer what i ask|(?:stop|don't|do not) (?:give|giving|add|adding|offer|offering) (?:me )?(?:unsolicited|unrequested|extra) (?:advice|suggestions|comments))$") to { _ ->
            FeedbackChange(Settings.PROACTIVITY, "off", "Understood. I'll answer only what you ask.") },
        r("^(?:point out (?:risks|problems|issues)(?: proactively| without me asking)?|feel free to (?:suggest|point out) (?:things|improvements|better options))$") to { _ ->
            FeedbackChange(Settings.PROACTIVITY, "active", "Understood. I'll point out risks and better options when I see them.") },
        r("^(?:call me|address me as|refer to me as)\\s+[\"']?([\\p{L}][\\p{L} .'-]{0,38}?)[\"']?$") to { m ->
            val name = m.groupValues[1].trim()
            val first = name.lowercase().substringBefore(' ')
            // "call me back / later / a taxi / when …" is a request, not a form of address.
            if (name.isEmpty() || first in setOf("back", "later", "tomorrow", "tonight", "soon", "a", "an", "the", "when", "if", "after", "at", "on", "in", "once", "now", "again")) null
            else FeedbackChange(Settings.OWNER_ADDRESS, name, "Understood, $name.") },
        r("^(?:stop|don't|do not) call(?:ing)? me\\s+.+$") to { _ -> FeedbackChange(Settings.OWNER_ADDRESS, "", "Understood. I'll use your name instead.") },
        r("^(?:always )?(?:speak|read) (?:your )?(?:replies|answers)(?: aloud| out loud)?$|^(?:speak|talk) to me$") to { _ ->
            FeedbackChange(Settings.SPEAK_RESPONSES, true, "Understood. I'll speak my replies.") },
        r("^(?:stop|don't|do not) (?:speak|speaking|read|reading) (?:your )?(?:replies|answers)(?: aloud| out loud)?$|^text only$") to { _ ->
            FeedbackChange(Settings.SPEAK_RESPONSES, false, "Understood. Text only.") },
    )

    fun match(text: String): FeedbackChange? {
        if (text.length > 90) return null
        var t = text.trim()
        repeat(3) { t = lead.replace(t, "").trim() }
        if (t.isEmpty()) return null
        for ((re, f) in rules) re.matchEntire(t)?.let { m -> return f(m) }
        return null
    }
}
