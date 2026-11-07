package ai.clu.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class RoutedIntent(val tool: String, val args: JsonObject, val reason: String)

/**
 * Fast, deterministic recognition of explicit commands. Explicit device actions skip the language model entirely,
 * which keeps them instant and reliable on a small model. Anything ambiguous goes to the model.
 */
object IntentRouter {
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val politeness = r("^(?:(?:hey|ok|okay)\\s+)?(?:clu|clue)[,!.]?\\s+|^(?:please|kindly|can you|could you|would you|will you)\\s+|\\s+please[.!?]?$|[.!?]+$")

    fun clean(text: String): String {
        var t = text.trim()
        repeat(3) { t = politeness.replace(t, "").trim() }
        return t
    }

    fun route(text: String): RoutedIntent? {
        val t = clean(text)
        val lower = t.lowercase()

        // A persona is named, not described: "switch to Rick" is a command; "switch to dark mode" and "let me talk to you
        // about my day" are conversation (M1: they were routed as persona switches and answered with a failure).
        r("^(?:switch|change)\\s+(?:back\\s+)?to\\s+(.+?)(\\s+mode)?$").find(t)?.let { m -> if (namesSomeone(m.groupValues[1]) && (m.groupValues[2].isEmpty() || isClu(m.groupValues[1]))) return persona(m.groupValues[1]) }
        r("^(?:let me\\s+)?(?:talk|speak)\\s+(?:to|with)\\s+(.+)$").find(t)?.let { if (namesSomeone(it.groupValues[1])) return persona(it.groupValues[1]) }
        if (r("^(?:bring\\s+back|back\\s+to|return\\s+to)\\s+(?:clu|clue)$").matches(t)) return persona("clu")

        // "Remember that my bike service is due in June" is something to keep; "Remember that time we went to Goa?" and
        // "remember when…" are conversation about the past (they were saved as memory suggestions before M1).
        r("^remember\\s+(?:that\\s+)?(.{3,})$").find(t)?.takeUnless { text.trimEnd().endsWith("?") || r("^remember\\s+(?:that\\s+time|the\\s+time|when|how|what|who|where|why|us|me)\\b").containsMatchIn(t) }
            ?.let { return RoutedIntent("memory.remember", obj("text" to it.groupValues[1].trim()), "remember") }
        // A standing instruction is explicit: saved as an instruction memory and applied to every later reply.
        r("^(?:from now on|going forward|in future)[,:]?\\s+(.{3,})$").find(t)?.let {
            return RoutedIntent("memory.remember", obj("text" to "From now on, " + it.groupValues[1].trim(), "category" to "INSTRUCTION"), "instruction") }

        r("^(?:save|add|put|write)\\s+(?:this|that|our|the)\\s+(?:conversation|chat|exchange|answer|reply)\\s+(?:to|in|into)\\s+(?:my\\s+)?diary$").find(t)?.let {
            return RoutedIntent("diary.add", obj("conversation" to "true"), "diary") }
        r("^(?:add|write|put|note|save)\\s+(?:this\\s+|it\\s+)?(?:to|in|into|down in)\\s+(?:my\\s+)?diary[:,]?\\s+(?:that\\s+)?(.{2,})$").find(t)?.let {
            return RoutedIntent("diary.add", obj("text" to it.groupValues[1].trim()), "diary") }
        r("^(?:diary(?:\\s+entry)?|dear diary)[:,]\\s*(.{2,})$").find(t)?.let { return RoutedIntent("diary.add", obj("text" to it.groupValues[1].trim()), "diary") }
        r("^(?:make|write)\\s+a\\s+(?:diary|journal)\\s+(?:entry|note)[:,]?\\s+(?:that\\s+)?(.{2,})$").find(t)?.let { return RoutedIntent("diary.add", obj("text" to it.groupValues[1].trim()), "diary") }

        // Butler: reminders, lists and the day's briefing (local, offline).
        r("^(?:remind\\s+me|set\\s+(?:a\\s+)?reminder|create\\s+(?:a\\s+)?reminder)\\s+(?:to\\s+|about\\s+|that\\s+)?(.{3,})$").find(t)?.let { m ->
            val split = ai.clu.core.butler.Butler.splitReminder(m.groupValues[1])
            return if (split != null) RoutedIntent("reminder.set", obj("what" to split.first, "when" to split.second), "reminder")
                else RoutedIntent("reminder.set", obj("what" to m.groupValues[1]), "reminder") }
        r("^(?:add|put)\\s+(.+?)\\s+(?:to|on|in)\\s+(?:my\\s+|the\\s+)?(shopping|grocery|groceries|to-?do|todo|task|tasks|[a-z]+)\\s*(?:list)?$").find(t)?.let { m ->
            val list = m.groupValues[2]
            if (t.lowercase().contains("list") || list.lowercase() in setOf("tasks", "task", "todo", "to-do", "groceries")) return RoutedIntent("task.add", obj("title" to m.groupValues[1], "list" to list), "list add") }
        r("^(?:what(?:'s| is)\\s+on|show(?:\\s+me)?|read(?:\\s+(?:me|out))?|open|check)\\s+(?:my\\s+|the\\s+)?(shopping|grocery|to-?do|todo|[a-z]+)\\s+list\\??$").find(t)?.let {
            return RoutedIntent("task.list", obj("list" to it.groupValues[1]), "list read") }
        if (r("^(?:what\\s+are\\s+my\\s+(?:tasks|to-?dos|reminders)|(?:show|list|read)(?:\\s+me)?\\s+my\\s+(?:tasks|to-?dos|reminders|lists)|my\\s+(?:tasks|to-?do\\s+list|reminders))\\??$").matches(t))
            return RoutedIntent("task.list", obj(), "list read")
        r("^(?:mark|tick\\s+off|check\\s+off|cross\\s+off)\\s+(.+?)(?:\\s+(?:as\\s+)?(?:done|complete|completed|finished))?$").find(t)?.let { m ->
            if (!lower.startsWith("mark") || Regex("\\b(?:done|complete|completed|finished)$").containsMatchIn(lower)) return RoutedIntent("task.done", obj("title" to m.groupValues[1]), "list done") }
        r("^(?:remove|delete|take)\\s+(.+?)\\s+(?:from|off)\\s+(?:my\\s+|the\\s+)?(shopping|grocery|to-?do|todo|[a-z]+)\\s+list$").find(t)?.let {
            return RoutedIntent("task.done", obj("title" to it.groupValues[1], "list" to it.groupValues[2]), "list done") }
        r("^i(?:'ve|\\s+have)?\\s+bought\\s+(?:the\\s+)?(.+)$").find(t)?.let { return RoutedIntent("task.done", obj("title" to it.groupValues[1], "list" to "shopping"), "list done") }
        if (r("^(?:what(?:'s| is| does)\\s+my\\s+day(?:\\s+look\\s+like)?(?:\\s+today)?|(?:give\\s+me\\s+)?(?:my\\s+)?(?:daily|morning)\\s+(?:briefing|summary)|brief\\s+me|what(?:'s| is)\\s+(?:on\\s+)?(?:for\\s+)?today|what(?:'s| is)\\s+pending|what\\s+do\\s+i\\s+(?:have|need)\\s+to\\s+do(?:\\s+today)?)\\??$").matches(t))
            return RoutedIntent("butler.briefing", obj(), "briefing")

        if (r("^(?:how\\s+was\\s+my\\s+day|(?:give\\s+me\\s+)?(?:my\\s+|the\\s+)?(?:evening|end[- ]of[- ]day|nightly)\\s+(?:summary|briefing|report)|summari[sz]e\\s+(?:my\\s+)?(?:day|today)|what\\s+did\\s+i\\s+(?:get\\s+done|do|finish|complete)\\s+today)\\??$").matches(t))
            return RoutedIntent("butler.evening", obj(), "evening")

        // Calendar: reading, free time, conflicts, creating, moving, cancelling (the calendar provider; permission asked when needed).
        r("^(?:what(?:'s|\\s+is)\\s+(?:on\\s+)?my\\s+(?:calendar|schedule|agenda)|(?:show|read|check|open)\\s+(?:me\\s+)?my\\s+(?:calendar|schedule|agenda)|what\\s+(?:meetings|appointments|events|plans)\\s+(?:do\\s+)?i\\s+have|do\\s+i\\s+have\\s+(?:any\\s+)?(?:meetings|appointments|events|plans)|(?:are\\s+there\\s+)?any\\s+(?:calendar\\s+|schedule\\s+)?(?:conflicts|clashes|overlaps))(?:\\s+(?:for|on|in))?\\s*(.*?)\\??$").find(t)?.let {
            return RoutedIntent("calendar.read", obj("range" to it.groupValues[1]), "calendar") }
        r("^when\\s+am\\s+i\\s+(?:free|available)\\s*(.*?)\\??$").find(t)?.let { return RoutedIntent("calendar.free", obj("range" to it.groupValues[1]), "free time") }
        r("^(?:am\\s+i\\s+(?:free|busy|available)|do\\s+i\\s+have\\s+(?:time|anything(?:\\s+on)?))\\s*(?:at|on|around|for)?\\s*(.+?)\\??$").find(t)?.let { m ->
            val w = m.groupValues[1]
            return if (Regex("\\d|noon|midnight").containsMatchIn(w)) RoutedIntent("calendar.free", obj("at" to w), "free time") else RoutedIntent("calendar.free", obj("range" to w), "free time") }
        r("^(?:add|put|schedule|create)\\s+(.+?)\\s+(?:to|on|in)\\s+(?:my\\s+)?calendar\\s+(?:for\\s+|at\\s+|on\\s+)?(.+)$").find(t)?.let {
            return RoutedIntent("calendar.create", obj("title" to it.groupValues[1], "when" to it.groupValues[2]), "calendar add") }
        r("^(?:schedule|book|set\\s+up|arrange)\\s+(?:a|an|my)\\s+((?:meeting|appointment|call|lunch|dinner|session|class|interview)(?:\\s+with\\s+.+?)?)\\s+((?:on|at|for|tomorrow|today|next|this|in)\\b.+)$").find(t)?.let {
            return RoutedIntent("calendar.create", obj("title" to it.groupValues[1].replaceFirstChar { c -> c.uppercase() }, "when" to it.groupValues[2]), "calendar add") }
        r("^(?:move|reschedule|push|shift)\\s+(?:my\\s+|the\\s+)?(.+?)\\s+(?:to|until|till)\\s+(.+)$").find(t)?.let { m ->
            if (!Regex("\\b(?:list|clipboard)\\b").containsMatchIn(m.groupValues[2])) return RoutedIntent("calendar.move", obj("title" to m.groupValues[1], "when" to m.groupValues[2]), "calendar move") }
        r("^(?:cancel|delete)\\s+(?:my\\s+|the\\s+)?(.+?\\b(?:meeting|appointment|event|call|session|class|interview|lunch|dinner))(?:\\s+(?:on|at|for|tomorrow|today)\\b.*)?$").find(t)?.let {
            return RoutedIntent("calendar.delete", obj("title" to it.groupValues[1]), "calendar delete") }
        r("^(?:remove|delete)\\s+(.+?)\\s+from\\s+(?:my\\s+)?calendar$").find(t)?.let { return RoutedIntent("calendar.delete", obj("title" to it.groupValues[1]), "calendar delete") }

        // Images: answered by the vision engine if there is one, otherwise honestly ("I can't see images yet").
        if (r("^(?:what(?:'s|\\s+is)\\s+in\\s+(?:this|the|my)\\s+(?:image|picture|photo|pic|screenshot)|describe\\s+(?:this|the|my)\\s+(?:image|picture|photo|screenshot)|(?:read|extract)\\s+(?:the\\s+)?text\\s+(?:in|from)\\s+(?:this|the|my)\\s+(?:image|picture|photo|screenshot)|look\\s+at\\s+(?:this|my)\\s+(?:image|picture|photo|screenshot|screen)|what\\s+do\\s+you\\s+see)\\??$").matches(t))
            return RoutedIntent("vision.describe", obj(), "image")

        // Notifications: what was missed, replies, dismissal, and the owner's switches for announcements.
        if (r("^(?:what\\s+did\\s+i\\s+miss|(?:did\\s+i\\s+miss|any|what)\\s+(?:missed|important)\\s+(?:messages|notifications|calls)|anything\\s+important)\\??$").matches(t))
            return RoutedIntent("notifications.missed", obj(), "missed")
        r("^(?:reply|respond|answer)\\s+to\\s+(.+?)(?:'s\\s+message)?\\s+(?:saying|with|that|:)\\s+(.+)$").find(t)?.let {
            return RoutedIntent("notifications.reply", obj("from" to it.groupValues[1], "text" to it.groupValues[2]), "reply") }
        r("^(?:dismiss|clear)\\s+(?:all\\s+)?(?:my\\s+|the\\s+)?(?:(.+?)\\s+)?notifications?$").find(t)?.let { return RoutedIntent("notifications.dismiss", obj("app" to it.groupValues[1]), "dismiss") }
        r("^(?:(?:turn|switch)\\s+(on|off)\\s+(?:notification|call)\\s+announcements?|(?:turn|switch)\\s+(?:notification|call)\\s+announcements?\\s+(on|off)|(start|stop)\\s+announcing\\s+(?:my\\s+)?(?:notifications|messages|calls))$").find(t)?.let { m ->
            val on = (m.groupValues[1] + m.groupValues[2] + m.groupValues[3]).lowercase().let { it == "on" || it == "start" }
            return RoutedIntent(if (lower.contains("call")) "calls.announce" else "notifications.announce", obj("state" to if (on) "on" else "off"), "announcements") }

        r("^(?:set|create|add|make)\\s+(?:an?\\s+)?alarm\\s+(?:for|at)\\s+(.+?)(?:\\s+(?:called|named|labell?ed|for)\\s+(.+))?$").find(t)?.let {
            return RoutedIntent("alarm.set", obj("time" to it.groupValues[1], "label" to it.groupValues[2]), "alarm") }
        r("^wake\\s+me\\s+(?:up\\s+)?(?:at|by)\\s+(.+)$").find(t)?.let { return RoutedIntent("alarm.set", obj("time" to it.groupValues[1]), "alarm") }
        r("^(?:set\\s+)?(?:a\\s+)?timer\\s+(?:for\\s+)?(.+)$").find(t)?.let { return RoutedIntent("timer.set", obj("duration" to it.groupValues[1]), "timer") }

        r("^(?:turn|switch)\\s+(on|off)\\s+(?:the\\s+)?(?:flash\\s?light|torch)$").find(t)?.let { return RoutedIntent("device.flashlight", obj("state" to it.groupValues[1].lowercase()), "torch") }
        r("^(?:turn|switch)\\s+(?:the\\s+)?(?:flash\\s?light|torch)\\s+(on|off)$").find(t)?.let { return RoutedIntent("device.flashlight", obj("state" to it.groupValues[1].lowercase()), "torch") }
        r("^(?:flash\\s?light|torch)\\s+(on|off)$").find(t)?.let { return RoutedIntent("device.flashlight", obj("state" to it.groupValues[1].lowercase()), "torch") }

        r("^(?:set\\s+)?(?:the\\s+)?(?:media\\s+)?volume\\s+(?:to\\s+)?(\\d{1,3})\\s*(?:%|percent)?$").find(t)?.let { return RoutedIntent("device.volume", obj("level" to it.groupValues[1]), "volume") }
        r("^(?:turn\\s+(?:the\\s+)?volume|volume)\\s+(up|down)$").find(t)?.let { return RoutedIntent("device.volume", obj("direction" to it.groupValues[1].lowercase()), "volume") }

        r("^(?:how\\s+far|what(?:'s| is)\\s+the\\s+distance|how\\s+long\\s+(?:does\\s+it|will\\s+it|would\\s+it)\\s+take(?:\\s+to\\s+(?:get|go|travel|drive|walk))?)\\s+(?:is\\s+(?:it\\s+)?)?(?:from\\s+)?(.+?)\\s+(?:to|and|from)\\s+(.+?)\\??$").find(t)?.let {
            val a = it.groupValues[1].removePrefix("between ").trim(); val (b, mode) = destination(it.groupValues[2], lower)
            return RoutedIntent("maps.distance", obj("from" to a, "to" to b, "mode" to mode), "distance") }
        r("^(?:distance|route|directions)\\s+(?:from|between)\\s+(.+?)\\s+(?:to|and)\\s+(.+)$").find(t)?.let {
            val (b, mode) = destination(it.groupValues[2], lower)
            return RoutedIntent("maps.distance", obj("from" to it.groupValues[1], "to" to b, "mode" to mode), "distance") }
        r("^(?:navigate|directions|take me|get me directions|drive)\\s+to\\s+(.+)$").find(t)?.let { return RoutedIntent("maps.open", obj("destination" to it.groupValues[1]), "navigate") }
        r("^(?:show\\s+(?:me\\s+)?)(.+?)\\s+on\\s+(?:the\\s+)?maps?$").find(t)?.let { return RoutedIntent("maps.open", obj("query" to it.groupValues[1]), "map") }
        r("^(?:open|find)\\s+(.+?)\\s+(?:in|on)\\s+(?:google\\s+)?maps$").find(t)?.let { return RoutedIntent("maps.open", obj("query" to it.groupValues[1]), "map") }

        // System settings: checked before "open <app>" so "open Wi-Fi settings" opens the panel, not an app search.
        r("^(?:open|show|go\\s+to|show\\s+me)\\s+(?:the\\s+|my\\s+)?(?:(.+?)\\s+)?settings$").find(t)?.let {
            return RoutedIntent("device.settings", obj("panel" to (it.groupValues[1].ifBlank { "main" })), "settings") }
        r("^(?:turn|switch)\\s+(?:on|off)\\s+(?:the\\s+)?(wi-?fi|bluetooth|location|gps|mobile\\s+data|data|airplane\\s+mode|flight\\s+mode)$").find(t)?.let {
            return RoutedIntent("device.settings", obj("panel" to it.groupValues[1], "toggle" to "true"), "settings") }
        r("^(?:turn|switch)\\s+(?:the\\s+)?(wi-?fi|bluetooth|location|gps|mobile\\s+data|airplane\\s+mode|flight\\s+mode)\\s+(?:on|off)$").find(t)?.let {
            return RoutedIntent("device.settings", obj("panel" to it.groupValues[1], "toggle" to "true"), "settings") }

        // Presence check ("CLU, you there?"): answered at once, no model.
        if (r("^(?:are\\s+)?you\\s+(?:there|around|awake|listening|here)\\??$").matches(t)) return RoutedIntent("clu.presence", obj(), "presence")
        // Websites: a named site is searched with its own search, a domain is opened directly (PC: CLU's browser; phone: its browser/app).
        r("^(?:search|look\\s+up|find)\\s+(?:on\\s+)?($SITES)\\s+for\\s+(.+)$").find(t)?.let { return RoutedIntent("browser.search", obj("site" to it.groupValues[1], "query" to it.groupValues[2]), "site search") }
        r("^(?:search\\s+(?:for\\s+)?|look\\s+up\\s+|find\\s+|play\\s+)(.+?)\\s+on\\s+($SITES)$").find(t)?.let { return RoutedIntent("browser.search", obj("site" to it.groupValues[2], "query" to it.groupValues[1]), "site search") }
        r("^(?:open|go\\s+to|visit|browse\\s+to)\\s+((?:https?://)?(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}(?:/\\S*)?)$").find(t)?.let { return RoutedIntent("browser.open", obj("url" to it.groupValues[1]), "open site") }
        r("^(?:go\\s+to|visit)\\s+(?:the\\s+)?($SITES)(?:\\s+website|\\s+site)?$").find(t)?.let { return RoutedIntent("browser.open", obj("site" to it.groupValues[1]), "open site") }
        // The page open in CLU's browser (PC).
        if (r("^(?:read\\s+(?:me\\s+)?(?:the|this)\\s+page|what(?:'s|\\s+is)\\s+on\\s+(?:the|this)\\s+page|read\\s+(?:me\\s+)?the\\s+(?:results|article))\\??$").matches(t)) return RoutedIntent("browser.read", obj(), "page")
        if (r("^(?:what\\s+can\\s+i\\s+click|what\\s+(?:buttons|links|options)\\s+are\\s+(?:there|on\\s+(?:the|this)\\s+page)|inspect\\s+(?:the|this)\\s+page)\\??$").matches(t)) return RoutedIntent("browser.inspect", obj(), "page")
        r("^scroll\\s+(up|down)(?:\\s+(?:the|a)\\s+(?:page|bit))?$").find(t)?.let { return RoutedIntent("browser.scroll", obj("direction" to it.groupValues[1].lowercase()), "scroll") }
        if (r("^(?:go\\s+back(?:\\s+a\\s+page)?|previous\\s+page|back\\s+a\\s+page)$").matches(t)) return RoutedIntent("browser.back", obj(), "back")
        r("^(?:click|tap|press)\\s+(?:on\\s+)?(?:the\\s+)?(.+?)(?:\\s+(?:button|link))?$").find(t)?.let { m ->
            val k = m.groupValues[1]
            if (r("^(?:enter|escape|esc|tab|backspace|up|down|left|right|return)$").matches(k)) return RoutedIntent("browser.key", obj("key" to k), "key")
            if (!lower.startsWith("press") && k.split(' ').size <= 6) return RoutedIntent("browser.click", obj("target" to k), "click") }
        r("^type\\s+(.+?)\\s+(?:in|into)\\s+(?:the\\s+)?(.+?)(?:\\s+(?:box|field|bar))?$").find(t)?.let { return RoutedIntent("browser.type", obj("text" to it.groupValues[1], "target" to it.groupValues[2]), "type") }
        if (r("^(?:take\\s+a\\s+)?screenshot(?:\\s+(?:of\\s+)?(?:the|this)\\s+page)?$").matches(t)) return RoutedIntent("browser.screenshot", obj(), "screenshot")

        r("^(?:open|launch|start|run)\\s+(?:the\\s+|my\\s+)?(.+?)(?:\\s+app)?$").find(t)?.let {
            val app = it.groupValues[1]
            // An app has a name; "run me through it", "start over", "start a story" are not apps (M1).
            val words = app.lowercase().split(Regex("\\s+"))
            if (words.size <= 4 && words.none { w -> w in NOT_APP_WORDS } && !app.endsWith("?")) return RoutedIntent("app.open", obj("app" to app), "open app")
        }

        if (r("^(?:what(?:'s| is)\\s+(?:the\\s+)?(?:time|date|day)(?:\\s+(?:now|today|it is))?|what\\s+time\\s+is\\s+it|what\\s+day\\s+is\\s+(?:it|today))\\??$").matches(t))
            return RoutedIntent("time.now", obj(), "time")
        if (r("^(?:status report|system status|device status|(?:how much|what(?:'s| is)(?: my| the)?) battery(?: level| left)?.*|battery(?: level| status)?)\\??$").matches(t))
            return RoutedIntent("device.status", obj(), "status")

        r("^(?:search(?:\\s+(?:the\\s+(?:web|internet|net)|online|google))?|google|look\\s+up)\\s+(?:for\\s+|about\\s+)?(.+)$").find(t)?.let { return RoutedIntent("web.search", obj("query" to it.groupValues[1]), "search") }
        if (r("\\b(pnr|running status|train\\s+(?:no|number)|train\\s+\\d{5}|\\d{5}\\s+(?:express|train|mail))\\b").containsMatchIn(lower))
            return RoutedIntent("trains.lookup", obj("query" to t), "train")
        r("^(?:calculate|compute|what(?:'s| is))\\s+([\\d\\s.,+\\-*/x×÷^%()]+)\\??$").find(t)?.let {
            val e = it.groupValues[1].trim(); if (e.any(Char::isDigit) && e.any { c -> c in "+-*/x×÷^%" }) return RoutedIntent("calc", obj("expression" to e), "calc") }
        r("^(?:call|dial)\\s+([+\\d][\\d\\s\\-]{4,})$").find(t)?.let { return RoutedIntent("phone.dial", obj("number" to it.groupValues[1].replace(" ", "")), "dial") }

        // Media playing in any app (media keys).
        r("^(?:(pause|stop|resume|play|unpause|continue)\\s+(?:the\\s+|my\\s+)?(?:music|song|track|video|playback|podcast|audio|media)|(next|previous)\\s+(?:song|track)|skip\\s+(?:this\\s+)?(?:song|track)|go\\s+back\\s+a\\s+(?:song|track))$").find(t)?.let { m ->
            val a = m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { if (lower.startsWith("skip")) "next" else "previous" }
            return RoutedIntent("media.control", obj("action" to a.lowercase()), "media") }
        // Notifications (needs notification access).
        r("^(?:(?:read|show|check|summari[sz]e|tell\\s+me)\\s+(?:me\\s+)?(?:my\\s+|the\\s+)?(?:new\\s+|latest\\s+|recent\\s+)?notifications|(?:do\\s+i\\s+have\\s+)?any\\s+(?:new\\s+)?notifications|what\\s+(?:are\\s+)?(?:my\\s+)?notifications(?:\\s+do\\s+i\\s+have)?)(?:\\s+from\\s+(.+?))?\\??$").find(t)?.let {
            return RoutedIntent("notifications.read", obj("app" to it.groupValues[1]), "notifications") }
        // Contacts, calls, messages, email: CLU prepares, the owner sends or presses call.
        r("^what(?:'s|\\s+is)\\s+(.+?)(?:'s|s')\\s+(?:phone\\s+|mobile\\s+)?number\\??$").find(t)?.let { return RoutedIntent("contacts.find", obj("name" to it.groupValues[1]), "contact") }
        r("^(?:text|message|sms|send\\s+(?:a\\s+)?(?:text|message|sms)\\s+to)\\s+(.+?)(?:\\s+saying|\\s+that|:)\\s+(.+)$").find(t)?.let {
            return RoutedIntent("sms.compose", obj(if (it.groupValues[1].any(Char::isDigit)) "number" to it.groupValues[1] else "name" to it.groupValues[1], "text" to it.groupValues[2]), "sms") }
        r("^(?:e-?mail|write\\s+(?:an\\s+)?e-?mail\\s+to|send\\s+(?:an\\s+)?e-?mail\\s+to)\\s+(.+?)(?:\\s+(?:about|saying|that)\\s+(.+))?$").find(t)?.let {
            return RoutedIntent("email.compose", obj("to" to it.groupValues[1], "subject" to it.groupValues[2], "text" to it.groupValues[2]), "email") }
        r("^(?:call|ring|phone)\\s+(.{2,40})$").find(t)?.let { m ->
            val who = m.groupValues[1].trim(); val w = who.lowercase().split(Regex("\\s+"))
            if (w.size <= 3 && w.first() !in setOf("me", "it", "us", "back", "off", "them", "him", "her", "the", "this", "that", "a", "an", "out", "up", "in", "for", "you", "your"))
                return RoutedIntent("phone.call", obj("name" to who), "call") }
        // Clipboard and brightness.
        r("^copy\\s+(?:that|this|it|your\\s+(?:last\\s+)?(?:answer|reply|message))(?:\\s+to\\s+(?:the\\s+|my\\s+)?clipboard)?$").find(t)?.let { return RoutedIntent("clipboard.copy", obj(), "copy") }
        r("^(?:set\\s+)?(?:the\\s+)?(?:screen\\s+)?brightness\\s+(?:to\\s+)?(\\d{1,3})\\s*(?:%|percent)?$").find(t)?.let { return RoutedIntent("device.brightness", obj("level" to it.groupValues[1]), "brightness") }
        r("^(?:turn\\s+(?:the\\s+)?brightness|brightness)\\s+(up|down)$").find(t)?.let { return RoutedIntent("device.brightness", obj("direction" to it.groupValues[1].lowercase()), "brightness") }
        r("^make\\s+(?:the\\s+)?screen\\s+(brighter|darker|dimmer)$").find(t)?.let { return RoutedIntent("device.brightness", obj("direction" to if (it.groupValues[1].lowercase() == "brighter") "up" else "down"), "brightness") }
        r("^(?:turn|switch)\\s+(on|off)\\s+(?:auto(?:matic)?|adaptive)\\s+brightness$").find(t)?.let { return RoutedIntent("device.brightness", obj("adaptive" to it.groupValues[1].lowercase()), "brightness") }
        return null
    }

    /**
     * The destination of a compound question ("… and Queens, how long will it take, and which route?") ends at the first
     * comma or follow-up clause; a travel mode anywhere in the question is kept ("by bike", "walking", "by train").
     */
    private fun destination(raw: String, whole: String): Pair<String, String> {
        var d = raw.split(Regex("[,;?]|\\s+(?:and\\s+)?(?:how|which|what|when)\\b", RegexOption.IGNORE_CASE)).first()
        d = d.replace(Regex("\\s+(?:by\\s+(?:car|bike|bicycle|cycle|train|bus|metro|public transport)|on\\s+foot|walking|cycling|driving)$", RegexOption.IGNORE_CASE), "")
        val mode = when {
            r("\\b(on foot|walk(?:ing)?)\\b").containsMatchIn(whole) -> "walking"
            r("\\b(by (?:bike|bicycle|cycle)|cycling)\\b").containsMatchIn(whole) -> "cycling"
            r("\\b(by (?:train|bus|metro|subway)|public transport|transit)\\b").containsMatchIn(whole) -> "transit"
            else -> "driving"
        }
        return d.trim() to mode
    }

    private val SITES = ai.clu.core.browser.Sites.KNOWN.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private fun persona(name: String) = RoutedIntent("persona.switch", obj("name" to name.trim()), "persona")
    private fun isClu(name: String) = name.trim().lowercase() in setOf("clu", "clue")
    /** A name, not a description: at most three words, none of them a pronoun or an ordinary word ("you about my day"). */
    private fun namesSomeone(name: String): Boolean {
        val w = name.trim().lowercase().split(Regex("\\s+"))
        return w.size <= 3 && w.none { it in NOT_APP_WORDS || it in setOf("mode", "dark", "light", "night", "silent", "english", "hindi") }
    }
    private val NOT_APP_WORDS = setOf("me", "you", "it", "us", "him", "her", "them", "this", "that", "these", "those", "over", "again", "through",
        "about", "with", "a", "an", "some", "something", "story", "conversation", "discussion", "from", "thinking", "talking", "now", "later", "up")
    private fun obj(vararg kv: Pair<String, String>) = buildJsonObject { kv.filter { it.second.isNotBlank() }.forEach { put(it.first, it.second.trim()) } }
}
