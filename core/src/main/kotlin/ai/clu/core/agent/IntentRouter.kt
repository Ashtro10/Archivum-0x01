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

        r("^(?:set|create|add|make)\\s+(?:an?\\s+)?alarm\\s+(?:for|at)\\s+(.+?)(?:\\s+(?:called|named|labell?ed|for)\\s+(.+))?$").find(t)?.let {
            return RoutedIntent("alarm.set", obj("time" to it.groupValues[1], "label" to it.groupValues[2]), "alarm") }
        r("^wake\\s+me\\s+(?:up\\s+)?(?:at|by)\\s+(.+)$").find(t)?.let { return RoutedIntent("alarm.set", obj("time" to it.groupValues[1]), "alarm") }
        r("^(?:set\\s+)?(?:a\\s+)?timer\\s+(?:for\\s+)?(.+)$").find(t)?.let { return RoutedIntent("timer.set", obj("duration" to it.groupValues[1]), "timer") }

        r("^(?:turn|switch)\\s+(on|off)\\s+(?:the\\s+)?(?:flash\\s?light|torch)$").find(t)?.let { return RoutedIntent("device.flashlight", obj("state" to it.groupValues[1].lowercase()), "torch") }
        r("^(?:turn|switch)\\s+(?:the\\s+)?(?:flash\\s?light|torch)\\s+(on|off)$").find(t)?.let { return RoutedIntent("device.flashlight", obj("state" to it.groupValues[1].lowercase()), "torch") }
        r("^(?:flash\\s?light|torch)\\s+(on|off)$").find(t)?.let { return RoutedIntent("device.flashlight", obj("state" to it.groupValues[1].lowercase()), "torch") }

        r("^(?:set\\s+)?(?:the\\s+)?(?:media\\s+)?volume\\s+(?:to\\s+)?(\\d{1,3})\\s*(?:%|percent)?$").find(t)?.let { return RoutedIntent("device.volume", obj("level" to it.groupValues[1]), "volume") }
        r("^(?:turn\\s+(?:the\\s+)?volume|volume)\\s+(up|down)$").find(t)?.let { return RoutedIntent("device.volume", obj("direction" to it.groupValues[1].lowercase()), "volume") }

        r("^(?:how\\s+far|what(?:'s| is)\\s+the\\s+distance)\\s+(?:is\\s+(?:it\\s+)?)?(?:from\\s+)?(.+?)\\s+(?:to|and|from)\\s+(.+?)\\??$").find(t)?.let {
            val a = it.groupValues[1].removePrefix("between ").trim(); val b = it.groupValues[2].trim()
            return RoutedIntent("maps.distance", obj("from" to a, "to" to b), "distance") }
        r("^(?:distance)\\s+(?:from|between)\\s+(.+?)\\s+(?:to|and)\\s+(.+)$").find(t)?.let {
            return RoutedIntent("maps.distance", obj("from" to it.groupValues[1], "to" to it.groupValues[2]), "distance") }
        r("^(?:navigate|directions|take me|get me directions|drive)\\s+to\\s+(.+)$").find(t)?.let { return RoutedIntent("maps.open", obj("destination" to it.groupValues[1]), "navigate") }
        r("^(?:show\\s+(?:me\\s+)?)(.+?)\\s+on\\s+(?:the\\s+)?maps?$").find(t)?.let { return RoutedIntent("maps.open", obj("query" to it.groupValues[1]), "map") }
        r("^(?:open|find)\\s+(.+?)\\s+(?:in|on)\\s+(?:google\\s+)?maps$").find(t)?.let { return RoutedIntent("maps.open", obj("query" to it.groupValues[1]), "map") }

        r("^(?:open|launch|start|run)\\s+(?:the\\s+|my\\s+)?(.+?)(?:\\s+app)?$").find(t)?.let {
            val app = it.groupValues[1]
            if (app.split(' ').size <= 4) return RoutedIntent("app.open", obj("app" to app), "open app")
        }

        if (r("^(?:what(?:'s| is)\\s+(?:the\\s+)?(?:time|date|day)(?:\\s+(?:now|today|it is))?|what\\s+time\\s+is\\s+it|what\\s+day\\s+is\\s+(?:it|today))\\??$").matches(t))
            return RoutedIntent("time.now", obj(), "time")
        if (r("^(?:status report|system status|device status|(?:how much|what(?:'s| is)(?: my| the)?) battery(?: level| left)?.*|battery(?: level| status)?)\\??$").matches(t))
            return RoutedIntent("device.status", obj(), "status")

        r("^(?:search|google|look\\s+up|search\\s+the\\s+web|search\\s+online)\\s+(?:for\\s+)?(.+)$").find(t)?.let { return RoutedIntent("web.search", obj("query" to it.groupValues[1]), "search") }
        if (r("\\b(pnr|running status|train\\s+(?:no|number)|train\\s+\\d{5}|\\d{5}\\s+(?:express|train|mail))\\b").containsMatchIn(lower))
            return RoutedIntent("trains.lookup", obj("query" to t), "train")
        r("^(?:calculate|compute|what(?:'s| is))\\s+([\\d\\s.,+\\-*/x×÷^%()]+)\\??$").find(t)?.let {
            val e = it.groupValues[1].trim(); if (e.any(Char::isDigit) && e.any { c -> c in "+-*/x×÷^%" }) return RoutedIntent("calc", obj("expression" to e), "calc") }
        r("^(?:call|dial)\\s+([+\\d][\\d\\s\\-]{4,})$").find(t)?.let { return RoutedIntent("phone.dial", obj("number" to it.groupValues[1].replace(" ", "")), "dial") }
        return null
    }

    private fun persona(name: String) = RoutedIntent("persona.switch", obj("name" to name.trim()), "persona")
    private fun obj(vararg kv: Pair<String, String>) = buildJsonObject { kv.filter { it.second.isNotBlank() }.forEach { put(it.first, it.second.trim()) } }
}
