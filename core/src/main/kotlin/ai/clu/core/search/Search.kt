package ai.clu.core.search

import ai.clu.core.config.Settings
import ai.clu.core.config.SettingsStore
import ai.clu.core.tools.HttpFetcher
import ai.clu.core.tools.USER_AGENT
import ai.clu.core.util.CluJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder

/** One search result as the provider returned it; [pageText] is an excerpt of the page itself, when it could be read. */
@Serializable
data class SearchHit(val title: String, val url: String, val snippet: String, val provider: String, val pageText: String? = null)

/** What a search produced. [error] is set when nothing usable came back (the hits are then empty). */
data class SearchOutcome(val query: String, val provider: String, val hits: List<SearchHit>, val error: String? = null)

/**
 * A search back end with a documented API. Only the query text is sent: never memories, diary or conversation.
 * Implementations must not scrape result pages of search engines.
 */
interface SearchProvider {
    val id: String
    val label: String
    /** Why this provider can't be used right now (missing key or URL), or null when it can. */
    fun unavailable(): String? = null
    suspend fun search(query: String, count: Int): List<SearchHit>
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** Plain text from an HTML fragment (search snippets): tags removed, common entities decoded, whitespace collapsed. */
fun htmlToText(s: String): String = s.replace(Regex("(?i)<(?:br|/?p|/?div|/?li|/?h[1-6]|/?tr|/?td)[^>]*>"), " ").replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
    .replace("&lt;", "<").replace("&gt;", ">").replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
    .replace(Regex("\\s+"), " ").replace(Regex(" ([.,;:!?])"), "$1").trim()

/**
 * Brave Search API (https://api.search.brave.com/app/documentation/web-search/get-started): `GET /res/v1/web/search`
 * with the owner's subscription token in `X-Subscription-Token`. The key is entered on the device and stored
 * encrypted; it is not part of the app.
 */
class BraveSearch(private val http: HttpFetcher, private val key: () -> String?) : SearchProvider {
    override val id = "brave"
    override val label = "Brave Search"
    override fun unavailable() = if (key().isNullOrBlank()) "No Brave Search API key is set (Settings → Search & internet)." else null

    override suspend fun search(query: String, count: Int): List<SearchHit> {
        val k = key() ?: throw SearchException("No Brave Search API key is set.")
        val r = http.get("https://api.search.brave.com/res/v1/web/search?q=${enc(query)}&count=$count&text_decorations=false",
            mapOf("Accept" to "application/json", "X-Subscription-Token" to k, "User-Agent" to USER_AGENT))
        when (r.code) {
            200 -> {}
            401, 403 -> throw SearchException("Brave Search rejected the API key (HTTP ${r.code}).")
            429 -> throw SearchException("Brave Search rate limit or monthly quota reached (HTTP 429).")
            else -> throw SearchException("Brave Search returned HTTP ${r.code}.")
        }
        return parse(r.body).take(count)
    }

    companion object {
        fun parse(body: String): List<SearchHit> {
            val results = runCatching { CluJson.parseToJsonElement(body).jsonObject["web"]?.jsonObject?.get("results") as? JsonArray }.getOrNull()
                ?: throw SearchException("Brave Search returned an unexpected response.")
            return results.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val url = o.s("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val extra = (o["extra_snippets"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(" ").orEmpty()
                SearchHit(htmlToText(o.s("title") ?: url), url, htmlToText(listOfNotNull(o.s("description"), extra.ifBlank { null }).joinToString(" ")), "Brave Search")
            }
        }
    }
}

/**
 * A SearXNG instance the owner runs or trusts (https://docs.searxng.org/dev/search_api.html): `GET /search?format=json`.
 * The instance must have the JSON format enabled (`search.formats` in settings.yml).
 */
class SearxngSearch(private val http: HttpFetcher, private val baseUrl: () -> String) : SearchProvider {
    override val id = "searxng"
    override val label = "SearXNG"
    override fun unavailable() = if (baseUrl().isBlank()) "No SearXNG address is set (Settings → Search & internet)." else null

    override suspend fun search(query: String, count: Int): List<SearchHit> {
        val base = baseUrl().trimEnd('/').ifBlank { throw SearchException("No SearXNG address is set.") }
        val r = http.get("$base/search?q=${enc(query)}&format=json&safesearch=1", mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT))
        when (r.code) {
            200 -> {}
            403 -> throw SearchException("The SearXNG instance refused JSON results (HTTP 403): enable the json format in its settings.yml.")
            429 -> throw SearchException("The SearXNG instance is rate-limiting requests (HTTP 429).")
            else -> throw SearchException("The SearXNG instance returned HTTP ${r.code}.")
        }
        return parse(r.body).take(count)
    }

    companion object {
        fun parse(body: String): List<SearchHit> {
            val results = runCatching { CluJson.parseToJsonElement(body).jsonObject["results"] as? JsonArray }.getOrNull()
                ?: throw SearchException("The SearXNG instance returned an unexpected response.")
            return results.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val url = o.s("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                SearchHit(htmlToText(o.s("title") ?: url), url, htmlToText(o.s("content").orEmpty()), "SearXNG" + (o.s("engine")?.let { " ($it)" } ?: ""))
            }.distinctBy { it.url }
        }
    }
}

/**
 * Keyless and free, through documented public APIs: Wikipedia search (MediaWiki API) and, unless Wikipedia-only is
 * chosen, DuckDuckGo's Instant Answer API. Good for encyclopedic facts; it does not cover news, prices or anything
 * very recent, which the answer says.
 */
class KeylessSearch(private val http: HttpFetcher, private val includeInstantAnswers: () -> Boolean) : SearchProvider {
    override val id = "keyless"
    override val label get() = if (includeInstantAnswers()) "Wikipedia + DuckDuckGo Instant Answers" else "Wikipedia"

    override suspend fun search(query: String, count: Int): List<SearchHit> = coroutineScope {
        val ia: kotlinx.coroutines.Deferred<List<SearchHit>>? = if (includeInstantAnswers()) async { runCatching { instantAnswer(query) }.getOrDefault(emptyList()) } else null
        val wiki = async { runCatching { wikipedia(query, count) } }
        val w = wiki.await()
        val hits = (ia?.await().orEmpty() + w.getOrDefault(emptyList())).distinctBy { it.url }
        // One source failing doesn't discard the other's results; only report the failure when nothing came back.
        if (hits.isEmpty()) w.exceptionOrNull()?.let { throw it }
        hits.take(count)
    }

    private suspend fun instantAnswer(q: String): List<SearchHit> {
        val r = http.get("https://api.duckduckgo.com/?q=${enc(q)}&format=json&no_html=1&skip_disambig=1&t=clu", mapOf("User-Agent" to USER_AGENT))
        if (r.code != 200) return emptyList()
        return parseInstantAnswer(r.body)
    }

    private suspend fun wikipedia(q: String, count: Int): List<SearchHit> {
        val r = http.get("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(q)}&format=json&srlimit=$count&utf8=1",
            mapOf("User-Agent" to USER_AGENT))
        if (r.code != 200) throw SearchException("Wikipedia returned HTTP ${r.code}.")
        return parseWikipedia(r.body)
    }

    companion object {
        fun parseInstantAnswer(body: String): List<SearchHit> {
            val o = runCatching { CluJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
            val out = mutableListOf<SearchHit>()
            val abstract = o.s("AbstractText"); val url = o.s("AbstractURL")
            if (abstract != null && url != null) out += SearchHit(o.s("Heading") ?: url, url, abstract, "DuckDuckGo Instant Answer (${o.s("AbstractSource") ?: "source"})")
            val answer = o.s("Answer")
            if (answer != null) out += SearchHit("Instant answer", "https://duckduckgo.com/?q=${enc(o.s("Heading") ?: answer)}", answer, "DuckDuckGo Instant Answer")
            return out
        }

        fun parseWikipedia(body: String): List<SearchHit> {
            val hits = runCatching { CluJson.parseToJsonElement(body).jsonObject["query"]?.jsonObject?.get("search") as? JsonArray }.getOrNull()
                ?: throw SearchException("Wikipedia returned an unexpected response.")
            return hits.mapNotNull { h ->
                val o = h as? JsonObject ?: return@mapNotNull null
                val title = o.s("title") ?: return@mapNotNull null
                SearchHit(title, "https://en.wikipedia.org/wiki/" + enc(title.replace(' ', '_')).replace("+", "%20"), htmlToText(o.s("snippet").orEmpty()), "Wikipedia")
            }
        }
    }
}

class SearchException(message: String) : Exception(message)

/**
 * robots.txt rules for fetching result pages (RFC 9309, the subset that matters here): groups for `Clu` or `*`,
 * Allow/Disallow with longest-match precedence, `*` and `$` wildcards. Unreachable robots.txt (404) allows; a
 * server error or timeout disallows, as the RFC recommends.
 */
class RobotsPolicy(private val http: HttpFetcher, private val now: () -> Long = System::currentTimeMillis) {
    private data class Rules(val rules: List<Pair<Boolean, String>>, val fetchedAt: Long)
    private val cache = HashMap<String, Rules>()

    suspend fun allowed(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val host = "${u.scheme}://${u.authority}"
        val rules = synchronized(cache) { cache[host]?.takeIf { now() - it.fetchedAt < 3_600_000 } } ?: fetch(host).also { synchronized(cache) { cache[host] = it } }
        return decide(rules.rules, (u.rawPath.ifEmpty { "/" }) + (u.rawQuery?.let { "?$it" } ?: ""))
    }

    private suspend fun fetch(host: String): Rules {
        val r = runCatching { withTimeoutOrNull(4000) { http.get("$host/robots.txt", mapOf("User-Agent" to USER_AGENT)) } }.getOrNull()
        val rules = when {
            r == null || r.code >= 500 -> listOf(false to "/")
            r.code in 400..499 -> emptyList()
            else -> parse(r.body)
        }
        return Rules(rules, now())
    }

    companion object {
        /** Rules that apply to Clu: its own group if one exists, otherwise the `*` group. (allow, pattern) pairs. */
        fun parse(txt: String): List<Pair<Boolean, String>> {
            val groups = mutableListOf<Pair<MutableList<String>, MutableList<Pair<Boolean, String>>>>()
            var lastWasAgent = false
            for (raw in txt.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val key = line.substringBefore(':').trim().lowercase(); val value = line.substringAfter(':', "").trim()
                when (key) {
                    "user-agent" -> { if (!lastWasAgent) groups += mutableListOf<String>() to mutableListOf(); groups.last().first += value.lowercase(); lastWasAgent = true }
                    "allow", "disallow" -> { if (groups.isNotEmpty() && value.isNotEmpty()) groups.last().second += (key == "allow") to value; lastWasAgent = false }
                    else -> lastWasAgent = false
                }
            }
            val own = groups.filter { g -> g.first.any { it == "clu" || "clu/" in it } }
            return (own.ifEmpty { groups.filter { "*" in it.first } }).flatMap { it.second }
        }

        private fun pattern(p: String): Regex {
            val anchored = p.endsWith("$")
            val body = (if (anchored) p.dropLast(1) else p).split("*").joinToString(".*") { Regex.escape(it) }
            return Regex("^$body" + if (anchored) "$" else "")
        }

        /** Longest matching rule wins; on equal length Allow wins. No matching rule: allowed. */
        fun decide(rules: List<Pair<Boolean, String>>, path: String): Boolean {
            val best = rules.filter { (_, p) -> pattern(p).containsMatchIn(path) }
                .maxWithOrNull(compareBy<Pair<Boolean, String>> { it.second.length }.thenBy { it.first }) ?: return true
            return best.first
        }
    }
}

/**
 * Reads a small excerpt of a result page when robots.txt permits: the paragraphs that mention the query, up to
 * [maxChars]. Only HTML/text, at most 2 MB (the HTTP client's cap), within a few seconds; scripts, styles and
 * navigation are removed.
 */
class PageReader(private val http: HttpFetcher, private val robots: RobotsPolicy) {
    suspend fun excerpt(url: String, query: String, maxChars: Int = 1200): String? {
        if (!robots.allowed(url)) return null
        val r = withTimeoutOrNull(6000) { runCatching { http.get(url, mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,text/plain")) }.getOrNull() } ?: return null
        if (r.code != 200) return null
        val head = r.body.take(512).lowercase().trimStart()
        if (head.startsWith("{") || head.startsWith("[") || head.startsWith("%pdf") || !(head.contains("<html") || head.contains("<!doctype") || head.contains("<p") || head.none { it.code < 9 })) return null
        return extract(r.body, query, maxChars)
    }

    companion object {
        fun extract(html: String, query: String, maxChars: Int): String? {
            val body = html.replace(Regex("(?is)<(script|style|noscript|nav|header|footer|aside|form|svg)[^>]*>.*?</\\1>"), " ")
            val paragraphs = Regex("(?is)<(p|li|h[1-4]|td)[^>]*>(.*?)</\\1>").findAll(body).map { htmlToText(it.groupValues[2]) }.filter { it.length >= 40 }.toList()
                .ifEmpty { htmlToText(body).split(Regex("(?<=[.!?])\\s+")).filter { it.length >= 40 } }
            if (paragraphs.isEmpty()) return null
            val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
            val scored = paragraphs.mapIndexed { i, p -> Triple(i, p, terms.count { p.lowercase().contains(it) }) }
            val chosen = scored.filter { it.third > 0 }.sortedByDescending { it.third }.take(6).sortedBy { it.first }.ifEmpty { scored.take(3) }
            val sb = StringBuilder()
            for ((_, p, _) in chosen) { if (sb.length + p.length > maxChars) { if (sb.isEmpty()) sb.append(p.take(maxChars)); break }; if (sb.isNotEmpty()) sb.append(' '); sb.append(p) }
            return sb.toString().trim().ifEmpty { null }
        }
    }
}

/**
 * Picks the configured provider, runs one search (3–5 results) and, if enabled, reads excerpts of the top two pages in
 * parallel. All network work runs on the IO dispatcher; nothing runs in the background between requests.
 */
class SearchService(private val settings: SettingsStore, http: HttpFetcher, braveKey: () -> String?) {
    private val providers: Map<String, SearchProvider> = listOf(
        BraveSearch(http, braveKey),
        SearxngSearch(http) { settings.string(Settings.SEARXNG_URL) },
        KeylessSearch(http) { settings.string(Settings.SEARCH_PROVIDER) != "wikipedia" },
    ).associateBy { it.id }
    private val reader = PageReader(http, RobotsPolicy(http))

    fun provider(): SearchProvider = when (settings.string(Settings.SEARCH_PROVIDER)) {
        "brave" -> providers.getValue("brave"); "searxng" -> providers.getValue("searxng"); else -> providers.getValue("keyless")
    }

    suspend fun search(query: String): SearchOutcome = withContext(Dispatchers.IO) {
        val p = provider()
        p.unavailable()?.let { return@withContext SearchOutcome(query, p.label, emptyList(), it) }
        val count = settings.int(Settings.SEARCH_RESULTS)
        val hits = try { p.search(query, count) } catch (e: SearchException) {
            return@withContext SearchOutcome(query, p.label, emptyList(), e.message)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return@withContext SearchOutcome(query, p.label, emptyList(), "${p.label} could not be reached (${e.message ?: e::class.simpleName}).")
        }
        if (hits.isEmpty()) return@withContext SearchOutcome(query, p.label, emptyList(), "${p.label} returned no results.")
        val read = if (settings.bool(Settings.SEARCH_READ_PAGES)) coroutineScope {
            hits.take(2).map { h -> async { h.url to runCatching { reader.excerpt(h.url, query) }.getOrNull() } }.awaitAll().toMap()
        } else emptyMap()
        SearchOutcome(query, p.label, hits.map { h -> read[h.url]?.let { h.copy(pageText = it) } ?: h })
    }
}
