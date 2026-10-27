package ai.clu.core.agent

import kotlinx.serialization.Serializable

enum class StepStatus(val mark: String) { PENDING("•"), RUNNING("…"), DONE("✓"), FAILED("✗"), NEEDS_YOU("!"), SKIPPED("–"), ANSWER("•") }

/** One step of a multi-step request and what actually happened (from the tool's own result, never assumed). */
@Serializable
data class PlanStep(val text: String, val status: StepStatus = StepStatus.PENDING, val result: String = "")

/** An active multi-step task, kept per conversation so it can be resumed while it is recent. */
@Serializable
data class TaskState(val conversationId: String, val createdAt: Long, val steps: List<PlanStep>) {
    val unfinished get() = steps.filter { it.status == StepStatus.PENDING || it.status == StepStatus.SKIPPED || it.status == StepStatus.NEEDS_YOU }
}

/**
 * Recognises requests that chain several actions ("set a timer for 10 minutes, then look up the weather in Pune and
 * open Spotify") and splits them into at most [MAX_STEPS] steps. Deliberately strict: every step must start with an
 * action verb, so ordinary sentences with "and"/"then" ("I went to the market and then came home", "salt and pepper")
 * are not plans. No model is involved.
 */
object TaskPlanner {
    const val MAX_STEPS = 5
    private val verbs = "set|start|create|add|remind|open|launch|turn|switch|search|look|find|check|compare|remember|note|write|save|show|play|" +
        "explain|summari[sz]e|research|read|tell|give|list|calculate|convert|translate|schedule|navigate|get"
    private val stepStart = Regex("(?i)^(?:(?:and|also|then|please|first|next|finally|after that|afterwards|can you|could you)\\s+)*(?:$verbs)\\b")
    private val splitter = Regex("(?i)\\s*(?:;|\\n+|,?\\s*(?:and then|then|after that|afterwards|finally|next)\\s+|,\\s*and\\s+|,\\s+(?=(?:$verbs)\\b)|\\s+and\\s+(?=(?:also\\s+)?(?:$verbs)\\b))\\s*")
    private val numbered = Regex("(?m)^\\s*(?:\\d+[.)]|[-*•])\\s+")
    private val lead = Regex("(?i)^(?:(?:and|also|then|please|first|next|finally|after that|afterwards|can you|could you)\\s+)+")

    private fun parts(t: String) = (if (numbered.containsMatchIn(t)) t.split(numbered) else t.split(splitter)).map { it.trim().trimEnd(',', '.') }.filter { it.isNotEmpty() }

    /** The steps, or null when this is not a multi-step request. */
    fun plan(text: String): List<String>? {
        val t = text.trim().trimEnd('.', '!')
        if (t.length < 12 || t.endsWith("?") && !t.contains(Regex("(?i)\\b(then|after that)\\b"))) return null
        val parts = parts(t)
        if (parts.size < 2) return null
        if (!parts.all { stepStart.containsMatchIn(it) && it.split(Regex("\\s+")).size >= 2 }) return null
        return parts.map { lead.replace(it, "").replaceFirstChar { c -> c.uppercase() } }.take(MAX_STEPS)
    }

    /** Steps beyond [MAX_STEPS] that were not taken on. */
    fun overflow(text: String): Int = if (plan(text) == null) 0 else (parts(text.trim().trimEnd('.', '!')).size - MAX_STEPS).coerceAtLeast(0)

    private val resume = Regex("(?i)^(?:clu[, ]+)?(?:continue|resume|finish|carry on with)\\s+(?:the |my |that )?(?:task|plan|remaining steps|rest(?: of (?:the )?(?:task|plan|steps))?)[.!]?$")
    fun isResume(text: String) = resume.matches(text.trim())

    /** The progress report: one line per step with what actually happened. */
    fun report(steps: List<PlanStep>, overflow: Int = 0, cancelled: Boolean = false): String = buildString {
        append("Plan (${steps.size} step${if (steps.size == 1) "" else "s"}):")
        steps.forEachIndexed { i, s -> append("\n${s.status.mark} ${i + 1}. ${s.text}"); if (s.result.isNotBlank()) append(" — ${s.result}") }
        if (overflow > 0) append("\nI handle at most $MAX_STEPS steps at a time; $overflow more weren't started.")
        if (cancelled) append("\nStopped: the remaining steps were not started.")
        val needs = steps.filter { it.status == StepStatus.NEEDS_YOU }
        if (needs.isNotEmpty()) append("\nWaiting for you: ${needs.joinToString { it.text }}. Say \"continue the task\" when ready.")
    }
}
