package ai.clu.core.tools

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.memory.*
import ai.clu.core.persona.PersonaManager
import ai.clu.core.security.Sensitivity
import ai.clu.core.util.CluJson
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

data class HttpResponse(val code: Int, val body: String)

/** Network access used by tools (Android: HttpURLConnection; tests: fakes). */
interface HttpFetcher {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
const val USER_AGENT = "Clu/1.0 (personal offline-first Android assistant)"

class TimeTool : Tool {
    override val name = "time.now"
    override val description = "Current local date and time."
    override val argsExample = "{}"
    override val sensitivity = Sensitivity.ROUTINE
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val s = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date(ctx.now))
        return ToolResult.ok("It is $s.")
    }
}

/** Safe arithmetic evaluator (no code execution): + - * / ^ % parentheses, decimals. */
class CalcTool : Tool {
    override val name = "calc"
    override val description = "Evaluate an arithmetic expression exactly."
    override val argsExample = """{"expression":"(12.5*4)/3"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val expr = args.str("expression") ?: return ToolResult.fail("No expression given.")
        return try {
            val v = Parser(expr).parse()
            val txt = if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else "%.6g".format(v)
            ToolResult.ok("$expr = $txt", data = JsonPrimitive(v))
        } catch (e: IllegalArgumentException) { ToolResult.fail("I can't evaluate that: ${e.message}") }
    }

    class Parser(private val s: String) {
        private var i = 0
        fun parse(): Double { val v = expr(); skip(); require(i == s.length) { "unexpected '${s.getOrNull(i)}'" }; return v }
        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun expr(): Double { var v = term(); while (true) { skip(); v = when (s.getOrNull(i)) { '+' -> { i++; v + term() }; '-' -> { i++; v - term() }; else -> return v } } }
        private fun term(): Double { var v = factor(); while (true) { skip(); v = when (s.getOrNull(i)) {
            '*', 'x', '×' -> { i++; v * factor() }; '/', '÷' -> { i++; val d = factor(); require(d != 0.0) { "division by zero" }; v / d }
            '%' -> { i++; v % factor() }; else -> return v } } }
        private fun factor(): Double { val b = unary(); skip(); return if (s.getOrNull(i) == '^') { i++; b.pow(factor()) } else b }
        private fun unary(): Double { skip(); return when (s.getOrNull(i)) { '-' -> { i++; -unary() }; '+' -> { i++; unary() }; else -> atom() } }
        private fun atom(): Double {
            skip()
            if (s.getOrNull(i) == '(') { i++; val v = expr(); skip(); require(s.getOrNull(i) == ')') { "missing )" }; i++; return v }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) i++
            require(i > st) { "number expected" }
            return s.substring(st, i).replace(",", "").toDoubleOrNull() ?: throw IllegalArgumentException("bad number")
        }
    }
}

class MemorySearchTool(private val memories: MemoryRepository) : Tool {
    override val name = "memory.search"
    override val description = "Search Clu's long-term memory and diary for a topic, person or event."
    override val argsExample = """{"query":"Ashutosh birthday"}"""
    override val sensitivity = Sensitivity.PERSONAL
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val q = args.str("query") ?: return ToolResult.fail("No query given.")
        val found = memories.rank(q, ctx.persona.readableScopes, limit = 6)
        if (found.isEmpty()) return ToolResult.ok("No memories found about \"$q\".", spoken = null)
        return ToolResult.ok(found.joinToString("\n") { PromptMemoryFormat.line(it.memory) }, spoken = null)
    }
}

class RememberTool(private val memories: MemoryRepository, private val settings: SettingsStore) : Tool {
    override val name = "memory.remember"
    override val description = "Save a fact the owner explicitly asked Clu to remember."
    override val argsExample = """{"text":"My exam is on 14 March"}"""
    override val sensitivity = Sensitivity.CONFIG
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val text = args.str("text") ?: return ToolResult.fail("Nothing to remember.")
        val scope = ctx.persona.writeScope ?: return ToolResult.fail("${ctx.persona.name} does not keep memories.")
        if (!settings.bool(Settings.MEMORY_ENABLED)) return ToolResult.fail("Persistent memory is turned off, so I can't save that.")
        val m = memories.save(Memory(category = MemoryCategory.CORE_FACT, title = text.take(60), content = text, source = MemorySource.OWNER_INSTRUCTION, scope = scope))
        val check = memories.get(m.id)
        return if (check != null && !check.deleted) ToolResult.ok("Saved to memory: \"$text\"", spoken = "Noted. I'll remember that.")
        else ToolResult.fail("Saving the memory could not be verified.")
    }
}

class DiaryRecentTool(private val memories: MemoryRepository) : Tool {
    override val name = "diary.recent"
    override val description = "List the most recent diary entries."
    override val argsExample = """{"count":5}"""
    override val sensitivity = Sensitivity.PERSONAL
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val n = (args.int("count") ?: 5).coerceIn(1, 20)
        val list = ctx.persona.readableScopes.flatMap { memories.list(it, MemoryCategory.DIARY, limit = n) }.take(n)
        if (list.isEmpty()) return ToolResult.ok("There are no diary entries yet.", spoken = null)
        return ToolResult.ok(list.joinToString("\n") { PromptMemoryFormat.line(it) }, spoken = null)
    }
}

class PersonaSwitchTool(private val personas: PersonaManager) : Tool {
    override val name = "persona.switch"
    override val description = "Switch the active persona to Clu or to one of the owner's characters."
    override val argsExample = """{"name":"Rick"}"""
    override val sensitivity = Sensitivity.CONFIG
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val n = args.str("name") ?: return ToolResult.fail("Which persona?")
        val id = personas.resolveByName(n) ?: return ToolResult.fail("I don't have a character called $n.")
        personas.switchTo(id)
        val now = personas.active()
        return if (now.id == id) ToolResult.ok("Active persona is now ${now.name}.", spoken = if (now.isClu) "I'm back." else "Switching to ${now.name}.")
        else ToolResult.fail("Switching to $n failed.")
    }
}

/** Formats memories for prompts/tools with date and provenance so the model knows what it did not witness. */
object PromptMemoryFormat {
    fun line(m: Memory): String {
        val date = m.eventAt?.let { SimpleDateFormat(if (m.eventHasTime) "d MMM yyyy HH:mm" else "d MMM yyyy", Locale.ENGLISH).format(Date(it)) }
        val prov = when (m.source) {
            MemorySource.OWNER_SUPPLIED, MemorySource.IMPORTED -> "owner told you; you did not witness it"
            MemorySource.OWNER_INSTRUCTION -> "owner asked you to remember"
            MemorySource.CONVERSATION -> "from your conversation"
            MemorySource.APPROVED_INFERENCE -> "your inference, approved by owner"
        }
        val body = m.bodyForPrompt().replace(Regex("\\s+"), " ").take(600)
        return "- ${if (date != null) "[$date] " else ""}${m.category.label}: ${if (m.title.isNotBlank() && !body.startsWith(m.title)) m.title + " — " else ""}$body ($prov)"
    }
}

// ------------------------------------------------------------------------------------------------ network tools

class WebSearchTool(private val http: HttpFetcher, private val settings: SettingsStore) : Tool {
    override val name = "web.search"
    override val description = "Search the web for current information (returns sources). Use for news, facts that may have changed, prices."
    override val argsExample = """{"query":"weather Pune today"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val network = NetCategory.WEB

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val q = args.str("query") ?: return ToolResult.fail("No query given.")
        val results = mutableListOf<String>()
        if (settings.string(Settings.SEARCH_PROVIDER) == "duckduckgo") {
            runCatching {
                val r = http.get("https://api.duckduckgo.com/?q=${enc(q)}&format=json&no_html=1&skip_disambig=1", mapOf("User-Agent" to USER_AGENT))
                if (r.code == 200) {
                    val o = CluJson.parseToJsonElement(r.body).jsonObject
                    o.str("AbstractText")?.let { results += "$it (source: ${o.str("AbstractURL") ?: "DuckDuckGo"})" }
                    o.str("Answer")?.let { results += "Answer: $it (DuckDuckGo)" }
                    (o["RelatedTopics"] as? JsonArray)?.take(3)?.forEach { t -> (t as? JsonObject)?.str("Text")?.let { results += "$it (${t.str("FirstURL") ?: ""})" } }
                }
            }
            if (results.isEmpty()) runCatching {
                val r = http.get("https://html.duckduckgo.com/html/?q=${enc(q)}", mapOf("User-Agent" to USER_AGENT))
                if (r.code == 200) results += parseDdgHtml(r.body).take(5)
            }
        }
        if (results.isEmpty()) runCatching { results += wikipedia(http, q) }
        if (results.isEmpty()) return ToolResult(false, false, "The search returned no usable results for \"$q\".", error = "no results",
            spoken = "I couldn't find reliable results for that.", usedNetwork = true)
        return ToolResult.ok("Search results for \"$q\" (retrieved now):\n" + results.joinToString("\n") { "- $it" }, spoken = null, usedNetwork = true)
    }

    companion object {
        fun parseDdgHtml(html: String): List<String> {
            val out = mutableListOf<String>()
            val re = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>.*?class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            for (m in re.findAll(html)) {
                val title = strip(m.groupValues[2]); val snippet = strip(m.groupValues[3])
                var url = m.groupValues[1]
                Regex("uddg=([^&]+)").find(url)?.let { url = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
                out += "$title: $snippet ($url)"
            }
            return out
        }
        fun strip(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()

        suspend fun wikipedia(http: HttpFetcher, q: String): List<String> {
            val r = http.get("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(q)}&format=json&srlimit=3", mapOf("User-Agent" to USER_AGENT))
            if (r.code != 200) return emptyList()
            val hits = CluJson.parseToJsonElement(r.body).jsonObject["query"]?.jsonObject?.get("search") as? JsonArray ?: return emptyList()
            return hits.mapNotNull { h ->
                val o = h as? JsonObject ?: return@mapNotNull null
                val title = o.str("title") ?: return@mapNotNull null
                "$title: ${strip(o.str("snippet") ?: "")} (https://en.wikipedia.org/wiki/${enc(title.replace(' ', '_'))})"
            }
        }
    }
}

data class GeoPoint(val name: String, val lat: Double, val lon: Double, val importance: Double = 0.0, val type: String = "")

class Geocoder(private val http: HttpFetcher) {
    suspend fun find(place: String): GeoPoint? = candidates(place, 1).firstOrNull()

    /** Up to [limit] matches from OpenStreetMap Nominatim, best first. */
    suspend fun candidates(place: String, limit: Int = 3): List<GeoPoint> {
        val r = http.get("https://nominatim.openstreetmap.org/search?q=${enc(place)}&format=json&limit=$limit", mapOf("User-Agent" to USER_AGENT))
        if (r.code != 200) return emptyList()
        val arr = runCatching { CluJson.parseToJsonElement(r.body) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            GeoPoint(o.str("display_name") ?: place, o.str("lat")?.toDoubleOrNull() ?: return@mapNotNull null, o.str("lon")?.toDoubleOrNull() ?: return@mapNotNull null,
                (o["importance"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, o.str("type") ?: "")
        }
    }

    companion object {
        /**
         * Two or more genuinely different places for one name (e.g. "Springfield"): matches more than 50 km apart whose
         * relevance is close to the best one. Then the agent should ask which one is meant instead of guessing.
         */
        fun ambiguous(c: List<GeoPoint>): List<GeoPoint> {
            val best = c.firstOrNull() ?: return emptyList()
            val rivals = c.drop(1).filter { DistanceTool.haversineKm(best.lat, best.lon, it.lat, it.lon) > 50 && it.importance >= best.importance * 0.8 }
            return if (rivals.isEmpty()) emptyList() else listOf(best) + rivals
        }
    }
}

class GeocodeTool(private val geo: Geocoder) : Tool {
    override val name = "maps.find"
    override val description = "Find a place (OpenStreetMap): returns the best matches with their full names, to check what the owner meant."
    override val argsExample = """{"place":"Gateway of India"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val network = NetCategory.MAPS
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val p = args.str("place") ?: return ToolResult.fail("Which place?")
        val c = geo.candidates(p, 3)
        val g = c.firstOrNull() ?: return ToolResult(false, false, "No location found for \"$p\".", error = "not found", usedNetwork = true)
        val amb = Geocoder.ambiguous(c)
        val summary = if (amb.isNotEmpty()) "Several different places match \"$p\": " + amb.joinToString("; ") { it.name } + ". Ask which one is meant."
            else "${g.name} (lat ${"%.5f".format(g.lat)}, lon ${"%.5f".format(g.lon)}). Source: OpenStreetMap."
        return ToolResult.ok(summary, data = buildJsonObject { put("lat", g.lat); put("lon", g.lon); put("name", g.name) }, spoken = null, usedNetwork = true)
    }
}

/**
 * Route distance, travel time and alternatives between two places: Nominatim (OpenStreetMap) for the places, OSRM on
 * the FOSSGIS servers (routing.openstreetmap.de: car, bike and foot profiles, free, no key, light use only) for routes.
 * Times are typical times from road speeds: there is no live traffic in this free data, and the result says so.
 * Public transport is not covered by OSRM: the tool says so and the agent can open Google Maps with transit directions.
 */
class DistanceTool(private val http: HttpFetcher, private val geo: Geocoder) : Tool {
    override val name = "maps.distance"
    override val description = "Route distance, travel time and alternative routes between two places (OpenStreetMap; no live traffic). " +
        "mode: driving, walking, cycling or transit."
    override val argsExample = """{"from":"Manhattan","to":"Queens","mode":"driving"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val network = NetCategory.MAPS

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val from = args.str("from") ?: return ToolResult.fail("Distance from where?")
        val to = args.str("to") ?: return ToolResult.fail("Distance to where?")
        val mode = normaliseMode(args.str("mode"))
        val ca = geo.candidates(from, 3); val cb = geo.candidates(to, 3)
        val a = ca.firstOrNull() ?: return ToolResult(false, false, "Could not locate \"$from\".", error = "not found", spoken = "I couldn't find $from on the map.", usedNetwork = true)
        val b = cb.firstOrNull() ?: return ToolResult(false, false, "Could not locate \"$to\".", error = "not found", spoken = "I couldn't find $to on the map.", usedNetwork = true)
        // A place name that fits several far-apart places is a question for the owner, not a guess.
        for ((label, c) in listOf(from to ca, to to cb)) {
            val amb = Geocoder.ambiguous(c)
            if (amb.isNotEmpty()) {
                val opts = amb.take(3).map { short(it.name, 3) }
                return ToolResult(true, true, "Ambiguous place \"$label\": " + amb.joinToString("; ") { it.name },
                    spoken = "I found ${amb.size} places called $label: ${opts.joinToString(", or ")}. Which one do you mean?", usedNetwork = true)
            }
        }
        val straight = haversineKm(a.lat, a.lon, b.lat, b.lon)
        if (mode == "transit") {
            val s = "Public transport routes aren't available in my map data. ${short(a.name)} to ${short(b.name)} is %.0f km in a straight line; I can open Google Maps with transit directions.".format(straight)
            return ToolResult.ok(s, verified = true, spoken = s, usedNetwork = true)
        }
        val profile = when (mode) { "walking" -> "routed-foot/route/v1/foot"; "cycling" -> "routed-bike/route/v1/bike"; else -> "routed-car/route/v1/driving" }
        val r = runCatching { http.get("https://routing.openstreetmap.de/$profile/${a.lon},${a.lat};${b.lon},${b.lat}?overview=false&alternatives=true&steps=true",
            mapOf("User-Agent" to USER_AGENT)) }.getOrNull()
        val routes = if (r?.code == 200) parseRoutes(r.body) else emptyList()
        if (routes.isEmpty()) {
            val s = "Straight-line distance from ${short(a.name)} to ${short(b.name)}: %.0f km. Road routing was unavailable right now, so the actual route is longer.".format(straight)
            return ToolResult(true, false, s, spoken = s, usedNetwork = true)
        }
        val verb = when (mode) { "walking" -> "on foot"; "cycling" -> "by bike"; else -> "by car" }
        val best = routes.first()
        val via = best.via.takeIf { it.isNotBlank() }?.let { " via $it" } ?: ""
        val alt = routes.drop(1).take(2).joinToString(" ") { o ->
            "An alternative${o.via.takeIf { it.isNotBlank() }?.let { " via $it" } ?: ""} is ${km(o.km)} and ${duration(o.min)}."
        }
        val spoken = "From ${short(a.name)} to ${short(b.name)} it's ${km(best.km)} $verb, about ${duration(best.min)}$via." +
            (if (alt.isNotEmpty()) " $alt The first route is the quickest." else "") +
            (if (mode == "driving") " That's a typical time; I don't have live traffic." else "")
        val summary = "$spoken Source: OSRM routing on OpenStreetMap data (routing.openstreetmap.de), retrieved now. From: ${a.name}. To: ${b.name}."
        return ToolResult.ok(summary, verified = true, spoken = spoken, usedNetwork = true)
    }

    data class Route(val km: Double, val min: Double, val via: String)

    private fun short(n: String, parts: Int = 2) = n.split(",").take(parts).joinToString(",").trim()
    private fun km(k: Double) = if (k < 10) "%.1f kilometres".format(k) else "%.0f kilometres".format(k)

    companion object {
        fun normaliseMode(m: String?): String = when (m?.lowercase()?.trim()) {
            null, "", "car", "drive", "driving", "road" -> "driving"
            "walk", "walking", "foot", "on foot" -> "walking"
            "bike", "bicycle", "cycle", "cycling" -> "cycling"
            "transit", "train", "bus", "metro", "subway", "public transport", "public transit" -> "transit"
            else -> "driving"
        }

        /** OSRM route response → routes (fastest first). `legs[].summary` names the main roads when steps=true. */
        fun parseRoutes(body: String): List<Route> = runCatching {
            val o = CluJson.parseToJsonElement(body).jsonObject
            if (o.str("code") != "Ok") return emptyList()
            (o["routes"] as JsonArray).map { e ->
                val rt = e.jsonObject
                val via = (rt["legs"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("summary") }?.filter { it.isNotBlank() }?.joinToString(", ") ?: ""
                Route(rt["distance"]!!.jsonPrimitive.double / 1000, rt["duration"]!!.jsonPrimitive.double / 60, via)
            }.sortedBy { it.min }
        }.getOrDefault(emptyList())

        fun duration(min: Double): String {
            val h = (min / 60).toInt(); val m = (min % 60).roundToInt()
            return when { h > 0 && m > 0 -> "$h hour${if (h > 1) "s" else ""} $m minutes"; h > 0 -> "$h hour${if (h > 1) "s" else ""}"; else -> "${maxOf(1, m)} minutes" }
        }

        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }
    }
}

/**
 * Indian Railways has no free public API. This tool performs a web search for the train/route and returns the
 * snippets clearly labelled as unofficial search results, plus the official sources to check.
 */
class TrainTool(private val search: WebSearchTool) : Tool {
    override val name = "trains.lookup"
    override val description = "Look up train information (running status, schedule, availability) via web search. Results are unofficial."
    override val argsExample = """{"query":"12127 Intercity running status"}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val network = NetCategory.TRAVEL
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val q = args.str("query") ?: return ToolResult.fail("Which train or route?")
        val r = search.run(buildJsonObject { put("query", "$q train") }, ctx)
        val note = "Note: live running status and seat availability are only authoritative on NTES (enquiry.indianrail.gov.in) and IRCTC; I could not query them directly."
        return if (r.success) ToolResult(true, false, r.summary + "\n" + note, spoken = null, usedNetwork = true)
        else ToolResult(false, false, "No train information found. $note", error = r.error, usedNetwork = true)
    }
}

/** Optional OpenAI-compatible endpoint for deeper analysis. Never required; disabled by default. */
class ExternalAiTool(private val http: HttpFetcher, private val settings: SettingsStore, private val apiKey: () -> String?) : Tool {
    override val name = "external.ai"
    override val description = "Ask the owner's configured external AI service for a deeper analysis. Only when the owner asks for deep analysis or settings allow."
    override val argsExample = """{"question":"Compare these two job offers in depth: ..."}"""
    override val sensitivity = Sensitivity.ROUTINE
    override val network = NetCategory.EXTERNAL_AI
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val q = args.str("question") ?: return ToolResult.fail("No question given.")
        val base = settings.string(Settings.EXT_AI_URL).trimEnd('/')
        if (base.isEmpty()) return ToolResult.fail("No external AI endpoint is configured.")
        ai.clu.core.livekit.EndpointPolicy.check(base, settings.bool(Settings.LK_LAN_CLEARTEXT))?.let { return ToolResult.fail("External AI endpoint rejected: $it.") }
        val model = settings.string(Settings.EXT_AI_MODEL).ifEmpty { "default" }
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", q) } }
            put("max_tokens", 1024)
        }
        val headers = buildMap { put("Content-Type", "application/json"); apiKey()?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") } }
        val url = if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
        val r = http.postJson(url, body.toString(), headers)
        if (r.code != 200) return ToolResult(false, false, "External AI returned HTTP ${r.code}.", error = "http ${r.code}", usedNetwork = true)
        val text = runCatching {
            CluJson.parseToJsonElement(r.body).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject.str("content")!!
        }.getOrNull() ?: return ToolResult(false, false, "External AI response could not be parsed.", error = "parse", usedNetwork = true)
        return ToolResult.ok("External AI ($model) answered:\n$text", spoken = null, usedNetwork = true)
    }
}
