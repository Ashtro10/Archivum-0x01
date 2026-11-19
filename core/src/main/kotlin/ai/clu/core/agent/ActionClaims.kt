package ai.clu.core.agent

/**
 * A reply never claims an action that did not happen. Measured with a 0.6B model: when planning failed, the chat reply
 * said "I'll remind you an hour before your meeting" with no reminder set. Claims are first-person statements of doing
 * ("I'll remind you", "I've added …", "reminder set"), not offers ("would you like me to…", "I can…").
 */
object ActionClaims {
    private data class Claim(val pattern: Regex, val tools: Set<String>, val what: String)

    private const val I = "(?:i'?ll|i will|i've|i have|i'm going to|i am going to|i've just|i just)"
    private val claims = listOf(
        Claim(Regex("(?i)\\b$I\\s+(?:remind you|set (?:a|an|the|your) (?:reminder|alarm|timer))|\\b(?:reminder|alarm|timer) (?:is |has been )?set\\b"),
            setOf("reminder.set", "alarm.set", "timer.set", "calendar.create", "task.add"), "set that reminder"),
        Claim(Regex("(?i)\\b$I\\s+(?:added|add|put|saved|save|noted|note)\\b.{0,40}\\b(?:list|tasks?|calendar|diary|memory|notes?)\\b|\\badded (?:it |that |them )?to your (?:list|tasks|calendar)"),
            setOf("task.add", "calendar.create", "diary.add", "memory.remember", "reminder.set"), "added that"),
        Claim(Regex("(?i)\\b$I\\s+(?:scheduled|schedule|booked|book|moved|move|cancelled|cancel|deleted|delete)\\b.{0,30}\\b(?:meeting|event|appointment|calendar)"),
            setOf("calendar.create", "calendar.move", "calendar.delete"), "changed your calendar"),
        Claim(Regex("(?i)\\b$I\\s+(?:sent|send|texted|text|messaged|message|emailed|email)\\b"),
            setOf("sms.compose", "email.compose", "notifications.reply"), "sent anything"),
        Claim(Regex("(?i)\\b$I\\s+(?:called|call|dialled|dialed|dial)\\b"), setOf("phone.call", "phone.dial"), "called anyone"),
    )

    /** What the reply claims to have done without the tool having run this turn ("set that reminder"), or null. */
    fun unbacked(reply: String, toolsRun: Collection<String>): String? =
        claims.firstOrNull { c -> c.pattern.containsMatchIn(reply) && toolsRun.none { it in c.tools } }?.what
}
