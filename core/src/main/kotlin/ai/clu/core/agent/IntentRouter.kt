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

        r("^(?:switch|change)\\s+(?:back\\s+)?to\\s+(.+?)(?:\\s+mode)?$").find(t)?.let { return persona(it.groupValues[1]) }
        r("^(?:let me\\s+)?(?:talk|speak)\\s+(?:to|with)\\s+(.+)$").find(t)?.let { return persona(it.groupValues[1]) }
        if (r("^(?:bring\\s+back|back\\s+to|return\\s+to)\\s+(?:clu|clue)$").matches(t)) return persona("clu")

        r("^remember\\s+(?:that\\s+)?(.{3,})$").find(t)?.let { return RoutedIntent("memory.remember", obj("text" to it.groupValues[1].trim()), "remember") }
        // A standing instruction is explicit: saved as an instruction memory and applied to every later reply.
        r("^(?:from now on|going forward|in future)[,:]?\\s+(.{3,})$").find(t)?.let {
            return RoutedIntent("memory.remember", obj("text" to "From now on, " + it.groupValues[1].trim(), "category" to "INSTRUCTION"), "instruction") }

        r("^(?:save|add|put|write)\\s+(?:this|that|our|the)\\s+(?:conversation|chat|exchange|answer|reply)\\s+(?:to|in|into)\\s+(?:my\\s+)?diary$").find(t)?.let {
            return RoutedIntent("diary.add", obj("conversation" to "true"), "diary") }
        r("^(?:add|write|put|note|save)\\s+(?:this\\s+|it\\s+)?(?:to|in|into|down in)\\s+(?:my\\s+)?diary[:,]?\\s+(?:that\\s+)?(.{2,})$").find(t)?.let {
            return RoutedIntent("diary.add", obj("text" to it.groupValues[1].trim()), "diary") }
        r("^(?:diary(?:\\s+entry)?|dear diary)[:,]\\s*(.{2,})$").find(t)?.let { return RoutedIntent("diary.add", obj("text" to it.groupValues[1].trim()), "diary") }
        r("^(?:make|write)\\s+a\\s+(?:diary|journal)\\s+(?:entry|note)[:,]?\\s+(?:that\\s+)?(.{2,})$").find(t)?.let { return RoutedIntent("diary.add", obj("text" to it.groupValues[1].trim()), "diary") }

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

        r("^(?:open|launch|start|run)\\s+(?:the\\s+|my\\s+)?(.+?)(?:\\s+app)?$").find(t)?.let {
            val app = it.groupValues[1]
            if (app.split(' ').size <= 4) return RoutedIntent("app.open", obj("app" to app), "open app")
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

    private fun persona(name: String) = RoutedIntent("persona.switch", obj("name" to name.trim()), "persona")
    private fun obj(vararg kv: Pair<String, String>) = buildJsonObject { kv.filter { it.second.isNotBlank() }.forEach { put(it.first, it.second.trim()) } }
}
