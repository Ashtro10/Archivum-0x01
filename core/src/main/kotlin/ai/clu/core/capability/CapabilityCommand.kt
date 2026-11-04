package ai.clu.core.capability

import ai.clu.core.agent.IntentRouter

/** What the owner asked CLU to do with one of its own capabilities. */
enum class CapabilityOp { CREATE, IMPROVE, LIST, OPEN, SET_DEFAULT, ENABLE, DISABLE, ROLLBACK, UNINSTALL, INSPECT,
    /** A change to CLU's own source (memory, voice, conversation, navigation…): goes to self-repair, never to the plugin path. */
    UPGRADE_CORE }

/**
 * A development or management instruction about CLU's own capabilities ("Give yourself a sci-fi weather service",
 * "Show me my maps", "Make the tactical map my default"), recognised deterministically so it is handled as an
 * instruction, never answered as a chat question.
 *
 * [kind] is the capability family (`weather`, `map`, `dashboard`; for CREATE an unrecognised family is kept as the
 * owner's words so CLU can say honestly that it cannot build that yet); null means "any" (e.g. "list my capabilities").
 * [style] is the requested presentation (`scifi`, `tactical`, `fantasy`, `classic`, …, or the owner's own word);
 * [target] the words that name an existing capability ("the tactical map"); [details] the rest of the requirement
 * ("with an hourly forecast"); [variant] whether another one was asked for ("another", "too", "also").
 */
data class CapabilityCommand(
    val op: CapabilityOp, val kind: String?, val style: String?, val target: String?, val details: String, val variant: Boolean, val text: String,
) {
    companion object {
        const val TOOL = "capability.manage"

        private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

        /** Family nouns, most specific first. */
        private val KINDS = listOf(
            "dashboard" to r("\\b(?:dashboards?|control\\s+panels?|overview\\s+screens?|command\\s+cent(?:er|re)s?)\\b"),
            "weather" to r("\\b(?:weather|forecasts?|meteo(?:rology)?)\\b"),
            "map" to r("\\b(?:maps?|atlas(?:es)?|cartograph\\w*|map\\s+viewers?)\\b"),
        )
        /** Words that make a phrase a software capability even when the family is not one CLU knows. */
        private val SOFTWARE = r("\\b(?:service|app|application|interface|experience|viewer|hud|ui|tool|widget|screen|module|capabilit(?:y|ies)|feature)s?\\b")
        private val GENERIC_PLURAL = r("\\b(?:capabilities|experiences|features|apps|applications|programs|tools|services|interfaces|modules|things\\s+you(?:'ve|\\s+have)\\s+(?:built|made|created|developed))\\b")

        /** Presentation styles CLU knows, by key. Unknown style words are kept as given. */
        private val STYLES = listOf(
            "scifi" to r("\\b(?:sci[\\s-]?fi|scifi|science[\\s-]fiction|futuristic|space(?:ship)?|starship|holo(?:graphic)?)\\b"),
            "tactical" to r("\\b(?:tactical|military|combat|recon|radar|night[\\s-]vision)\\b"),
            "fantasy" to r("\\b(?:fantasy|medieval|parchment|tolkien\\w*|old[\\s-]world|treasure|antique|ancient)\\b"),
            "retro" to r("\\b(?:retro|synthwave|80s|eighties|arcade|vaporwave)\\b"),
            "classic" to r("\\b(?:classic|conventional|standard|normal|plain|regular|ordinary|simple|traditional)\\b"),
            "minimal" to r("\\b(?:minimal(?:ist)?|clean|calm)\\b"),
            "noir" to r("\\b(?:noir|monochrome|black[\\s-]and[\\s-]white)\\b"),
        )

        private val DET = "(?:my|your|the|our|this|that)"
        private val VARIANT = r("\\b(?:another|too|also|as\\s+well|second|one\\s+more|additional|a\\s+new|new)\\b")

        /** "tracks my water intake" → "water intake tracker": what an unnamed "application" is for. */
        fun purpose(clause: String): String {
            val m = r("^(?:can\\s+|will\\s+|would\\s+)?(track|show|display|manage|organi[sz]e|plan|monitor|search|find|list|count|calculate|convert|play|record|remind|edit|browse|visuali[sz]e|log|draw|time)s?\\s+(?:me\\s+)?(?:my\\s+|the\\s+|your\\s+|all\\s+)?(.+)$").find(clause.trim())
            if (m == null) return clause.trim().split(Regex("\\s+")).take(4).joinToString(" ").ifBlank { "application" }
            val noun = mapOf("track" to "tracker", "show" to "viewer", "display" to "viewer", "manage" to "manager", "organise" to "organizer", "organize" to "organizer", "plan" to "planner",
                "monitor" to "monitor", "search" to "search", "find" to "finder", "list" to "list", "count" to "counter", "calculate" to "calculator", "convert" to "converter", "play" to "player",
                "record" to "recorder", "remind" to "reminder", "edit" to "editor", "browse" to "browser", "visualise" to "visualizer", "visualize" to "visualizer", "log" to "log", "draw" to "drawing app", "time" to "timer")
            return (m.groupValues[2].split(Regex("\\s+")).take(3).joinToString(" ") + " " + (noun[m.groupValues[1].lowercase()] ?: "app")).trim()
        }

        fun kindOf(phrase: String): String? {
            val hits = KINDS.mapNotNull { (k, re) -> re.find(phrase)?.let { k to it.range.first } }
            if (hits.isEmpty()) return null
            return hits.firstOrNull { it.first == "dashboard" }?.first ?: hits.minByOrNull { it.second }!!.first
        }

        fun styleOf(phrase: String): String? {
            STYLES.firstOrNull { it.second.containsMatchIn(phrase) }?.let { return it.first }
            // Owner's own words: "make it underwater-themed", "an ocean-themed map", "with a steampunk look".
            val custom = r("\\b([a-z]+(?:[\\s-][a-z]+)?)[\\s-]themed\\b").find(phrase)?.groupValues?.get(1)
                ?: r("\\bwith\\s+an?\\s+([a-z]+(?:[\\s-][a-z]+)?)\\s+(?:look|style|theme|ui|interface|skin|feel|aesthetic)\\b").find(phrase)?.groupValues?.get(1)
                ?: r("\\bin\\s+(?:an?\\s+)?([a-z]+)\\s+style\\b").find(phrase)?.groupValues?.get(1)
            return custom?.lowercase()?.replace(' ', '-')?.removePrefix("make-it-")?.takeUnless { it in setOf("a", "an", "the", "new", "nice", "good", "better", "cool") }
        }

        private fun plural(phrase: String) = r("\\b(?:maps|atlases|dashboards|forecasts|weathers?\\s+(?:services|apps|interfaces|experiences))\\b").containsMatchIn(phrase) ||
            GENERIC_PLURAL.containsMatchIn(phrase) || r("\\ball\\b").containsMatchIn(phrase) || r("\\b(?:map|weather|dashboard)\\s+(?:experiences|interfaces|variants|versions|views|services|apps)\\b").containsMatchIn(phrase)

        /** Names of the capabilities installed on this device (set by the host), so "open the galaxy file explorer" is recognised. */
        @Volatile var installedNames: () -> List<String> = { emptyList() }
        private val NAME_STOP = setOf("the", "my", "your", "and", "for", "with", "app", "application", "service", "interface", "experience", "viewer", "tool")
        private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}0-9]+")).filter { it.length > 2 && it !in NAME_STOP }.toSet()
        fun namesInstalled(phrase: String): Boolean { val w = words(phrase); return w.isNotEmpty() && installedNames().any { n -> words(n).let { nw -> nw.isNotEmpty() && (w intersect nw).size >= minOf(2, nw.size) } } }
        private fun known(phrase: String) = kindOf(phrase) != null || namesInstalled(phrase)
        /** Something software-like the owner says CLU has ("your galaxy file explorer"): handled as an instruction even when no such capability exists, so CLU can say so instead of a chat reply claiming work it never did. */
        private val APP_NOUN = r("\\b(?:app|application|explorer|editor|browser|timer|tracker|dashboard|viewer|player|monitor|manager|calculator|planner|graph|hud|interface|tool|widget|service|engine|map|game|visuali[sz]er|organi[sz]er|finder|search\\s+engine)s?\\b")
        private fun softwareRef(phrase: String) = known(phrase) || APP_NOUN.containsMatchIn(phrase)

        private fun cmd(op: CapabilityOp, phrase: String, text: String, details: String = "", target: String? = phrase, kind: String? = kindOf(phrase)) =
            CapabilityCommand(op, kind, styleOf(phrase), target?.trim()?.trimEnd('.', '?', '!')?.ifBlank { null }, details.trim(), VARIANT.containsMatchIn(text), text)

        /** Recognises an instruction about CLU's capabilities, or returns null for anything else (left to normal chat). */
        fun parse(raw: String): CapabilityCommand? {
            val t = IntentRouter.clean(raw).replace(Regex("\\s+"), " ").trim()
            if (t.length < 4 || t.length > 400) return null

            // --- default preference: "make the tactical map my default", "set the fantasy map as my default map viewer"
            r("^(?:make|set|use|choose)\\s+(?:$DET\\s+)?(.+?)\\s+(?:as\\s+)?(?:my|your|the)\\s+(?:new\\s+)?default(?:\\s+(.+))?$").find(t)?.let { m ->
                val phrase = m.groupValues[1] + " " + m.groupValues[2]
                if (known(phrase)) return cmd(CapabilityOp.SET_DEFAULT, m.groupValues[1], raw, kind = kindOf(phrase)) }
            r("^(?:set|change|switch)\\s+(?:my|your|the)\\s+default\\s+(.+?)\\s+to\\s+(?:$DET\\s+)?(.+)$").find(t)?.let { m ->
                val phrase = m.groupValues[1] + " " + m.groupValues[2]
                if (known(phrase)) return cmd(CapabilityOp.SET_DEFAULT, m.groupValues[2], raw, kind = kindOf(phrase)) }

            // --- core upgrades: "improve your memory retrieval", "add a new model provider to yourself"
            val subsystem = "(memory|memories|voice|speech|conversation|conversations|chat|navigation|reasoning|thinking|development\\s+engine|coding|self-repair|model\\s+provider|inference|research|recall|retrieval|wake\\s+word|personality)"
            r("^(?:improve|upgrade|enhance|rework|refactor|speed\\s+up|optimi[sz]e|extend|change|fix)\\s+(?:your|ur)\\s+(?:own\\s+)?$subsystem\\b(.*)$").find(t)?.takeUnless { namesInstalled(it.groupValues[1] + it.groupValues[2]) }?.let { m ->
                return CapabilityCommand(CapabilityOp.UPGRADE_CORE, m.groupValues[1].lowercase(), null, null, (m.groupValues[1] + m.groupValues[2]).trim(), false, raw) }
            r("^(?:add|give\\s+yourself)\\s+(?:a\\s+|an\\s+)?(?:new\\s+)?$subsystem\\b(.*?)(?:\\s+to\\s+(?:your|yourself)\\b.*)?$").find(t)?.takeIf { r("\\b(?:new|yourself|your)\\b").containsMatchIn(t) }?.let { m ->
                return CapabilityCommand(CapabilityOp.UPGRADE_CORE, m.groupValues[1].lowercase(), null, null, t, false, raw) }

            // --- rollback / uninstall / enable / disable / inspect an existing capability
            r("^(?:roll\\s*back|revert|downgrade|undo\\s+the\\s+(?:last\\s+)?(?:update|change|upgrade)s?\\s+(?:to|of))\\s+(?:$DET\\s+)?(.+?)(?:\\s+to\\s+(?:the\\s+)?(?:previous|last|old|older|earlier|prior)\\s+version)?$").find(t)?.let { m ->
                if (known(m.groupValues[1]) || SOFTWARE.containsMatchIn(m.groupValues[1])) return cmd(CapabilityOp.ROLLBACK, m.groupValues[1], raw) }
            r("^(?:uninstall|remove|delete|get\\s+rid\\s+of)\\s+$DET\\s+(.+)$").find(t)?.let { m ->
                if (known(m.groupValues[1])) return cmd(CapabilityOp.UNINSTALL, m.groupValues[1], raw) }
            r("^(?:disable|deactivate|turn\\s+off|switch\\s+off|hide)\\s+$DET\\s+(.+)$").find(t)?.let { m ->
                if (known(m.groupValues[1])) return cmd(CapabilityOp.DISABLE, m.groupValues[1], raw) }
            r("^(?:enable|re-?enable|activate|turn\\s+on|switch\\s+on|unhide)\\s+$DET\\s+(.+)$").find(t)?.let { m ->
                if (known(m.groupValues[1])) return cmd(CapabilityOp.ENABLE, m.groupValues[1], raw) }
            r("^(?:inspect|describe|tell\\s+me\\s+about|show\\s+(?:me\\s+)?(?:the\\s+)?(?:details|history|versions)\\s+(?:of|for))\\s+$DET\\s+(.+)$").find(t)?.let { m ->
                if (known(m.groupValues[1])) return cmd(CapabilityOp.INSPECT, m.groupValues[1], raw) }

            // --- improve: "improve my weather service", "upgrade the tactical map with a measuring tool", "add an hourly forecast to my weather"
            r("^(?:improve|upgrade|enhance|update|refine|polish|extend|evolve)\\s+$DET\\s+(.+?)(?:\\s+(?:with|by|so\\s+that|so\\s+it|to\\s+(?:add|include|show))\\s+(.+))?$").find(t)?.let { m ->
                if (softwareRef(m.groupValues[1])) return cmd(CapabilityOp.IMPROVE, m.groupValues[1], raw, details = m.groupValues[2]) }
            r("^(?:add|put|include|build|give)\\s+(.+?)\\s+(?:to|in|into|on|for)\\s+(?:my|your|the|our)\\s+(.+)$").find(t)?.let { m ->
                if (softwareRef(m.groupValues[2])) return cmd(CapabilityOp.IMPROVE, m.groupValues[2], raw, details = m.groupValues[1]) }

            // --- list: "show me my maps", "show me all the map experiences you've created", "list your capabilities"
            r("^(?:show|list|display|give)\\s+(?:me\\s+)?(?:a\\s+list\\s+of\\s+)?(?:all\\s+)?(?:of\\s+)?(?:$DET\\s+)?(.+)$").find(t)?.let { m ->
                val p = m.groupValues[1]
                if ((known(p) || GENERIC_PLURAL.containsMatchIn(p)) && plural(p)) return cmd(CapabilityOp.LIST, p, raw, target = null) }
            r("^(?:which|what)\\s+(.+?)\\s+(?:do\\s+you\\s+have|have\\s+you\\s+(?:got|built|made|created|developed)|are\\s+(?:installed|available))$").find(t)?.let { m ->
                if (known(m.groupValues[1]) || GENERIC_PLURAL.containsMatchIn(m.groupValues[1])) return cmd(CapabilityOp.LIST, m.groupValues[1], raw, target = null) }

            // --- open: "open my map", "launch the fantasy map", "show me my weather"
            r("^(?:open|launch|start|show|bring\\s+up|pull\\s+up|display|go\\s+to|switch\\s+to)\\s+(?:me\\s+)?(?:up\\s+)?$DET\\s+(.+)$").find(t)?.let { m ->
                if (known(m.groupValues[1]) && !r("\\bon\\s+(?:the\\s+)?map\\b").containsMatchIn(m.groupValues[1])) return cmd(CapabilityOp.OPEN, m.groupValues[1], raw) }

            // --- create: "give yourself a sci-fi weather service", "build yourself a tactical map HUD", "create a fantasy map too",
            //     "create another map interface, but make it fantasy-themed", "build a dashboard that combines your weather and map services"
            r("^(?:give\\s+yourself|get\\s+yourself|build(?:\\s+yourself|\\s+me)?|create(?:\\s+yourself|\\s+me)?|make(?:\\s+yourself|\\s+me)?|develop|design|code\\s+up|i\\s+(?:want|need|would\\s+like)(?:\\s+you\\s+to\\s+have)?)\\s+(?:an?\\s+|another\\s+|one\\s+more\\s+|a\\s+new\\s+|new\\s+|some\\s+)?(.+)$").find(t)?.let { m ->
                val p = m.groupValues[1]
                if (known(p) || (SOFTWARE.containsMatchIn(p) && !r("^(?:sure|sense|it|that|this|note|list|copy|backup|reminder|plan|summary|joke|story|poem)\\b").containsMatchIn(p))) {
                    val head = p.split(Regex("\\s+(?:that|which|who|so\\s+that|to|for|with|but|and\\s+make)\\s+"), limit = 2)
                    return cmd(CapabilityOp.CREATE, p, raw, details = head.getOrElse(1) { "" }, target = null,
                        kind = kindOf(p) ?: head[0].replace(SOFTWARE, "").replace(Regex("\\b(?:yourself|for\\s+me|too|also|please)\\b"), "").trim().ifBlank { purpose(head.getOrElse(1) { "" }) })
                }
            }
            return null
        }
    }
}
