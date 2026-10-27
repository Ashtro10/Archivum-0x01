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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder

/**
 * One search result as the provider returned it. [pageText] is an excerpt of the page itself when it was opened;
 * [pageNote] says why it wasn't (robots.txt, timeout, not HTML…) when Clu tried. [published] is a date only when the
 * provider or the page states one.
 */
@Serializable
data class SearchHit(val title: String, val url: String, val snippet: String, val provider: String, val pageText: String? = null,
                     val pageNote: String? = null, val published: String? = null)

/** What a search produced. [error] is set when nothing usable came back (the hits are then empty). */
data class SearchOutcome(val query: String, val provider: String, val hits: List<SearchHit>, val error: String? = null,
                         val queries: List<String> = listOf(query), val retrievedAt: Long = 0) {
    /** Different websites among the results (several results from one site are not independent confirmation). */
    val independentSites get() = hits.mapNotNull { hostOf(it.url) }.distinct().size
}

/** Host without "www.", or null for a malformed URL. */
fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()

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
                SearchHit(htmlToText(o.s("title") ?: url), url, htmlToText(listOfNotNull(o.s("description"), extra.ifBlank { null }).joinToString(" ")), "Brave Search",
                    published = o.s("page_age")?.let(::isoDay) ?: o.s("age")?.takeIf { it.length <= 40 })
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
                SearchHit(htmlToText(o.s("title") ?: url), url, htmlToText(o.s("content").orEmpty()), "SearXNG" + (o.s("engine")?.let { " ($it)" } ?: ""),
                    published = o.s("publishedDate")?.let(::isoDay))
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
                SearchHit(title, "https://en.wikipedia.org/wiki/" + enc(title.replace(' ', '_')).replace("+", "%20"), htmlToText(o.s("snippet").orEmpty()), "Wikipedia",
                    published = o.s("timestamp")?.let(::isoDay)?.let { "updated $it" })
            }
        }
    }
}

class SearchException(message: String) : Exception(message)

/** "2025-06-10T14:00:00Z" → "2025-06-10"; anything that doesn't start with a date → null (never guessed). */
fun isoDay(s: String): String? = Regex("^(\\d{4}-\\d{2}-\\d{2})").find(s.trim())?.groupValues?.get(1)

/**
 * Addresses Clu will open for reading: http(s) on a public host. Refuses loopback, private, link-local and
 * single-label hosts, so a search result (or a redirect) can't make the phone fetch pages from the owner's router or
 * local network. Hosts are checked by name/literal only (no extra DNS lookup).
 */
object SafeAddress {
    fun allowed(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        if (u.scheme?.lowercase() !in setOf("http", "https")) return false
        if (u.userInfo != null) return false
        val host = u.host?.lowercase()?.trim('[', ']') ?: return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan") || !host.contains('.') && !host.contains(':')) return false
        val v4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(host)?.groupValues?.drop(1)?.map { it.toInt() }
        if (v4 != null) {
            val (a, b) = v4
            return !(a == 10 || a == 127 || a == 0 || a >= 224 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127))
        }
        if (host.contains(':')) return !(host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80") || host == "::")
        return true
    }
}

/**
 * Plans the searches for one request: one focused query, or for a comparison ("compare A and B", "A vs B") the
 * comparison plus one query per option. Never more than 3 queries, so research stays cheap and bounded.
 */
object ResearchPlan {
    private val compare = Regex("(?i)^(?:compare|comparison of|difference between|differences between)\\s+(.+?)\\s+(?:and|with|vs\\.?|versus)\\s+(.+?)(?:\\s+((?:for|in|on|when|as)\\s+.+?))?[?.!]*$")
    private val versus = Regex("(?i)^(?:which is better[,:]?\\s+|should i (?:buy|get|use|choose)\\s+)?(.+?)\\s+(?:vs\\.?|versus|or)\\s+(.+?)(?:\\s+((?:for|in|on|when|as)\\s+.+?))?[?.!]*$")

    fun queries(query: String): List<String> {
        val q = query.trim()
        val m = compare.find(q) ?: versus.find(q)?.takeIf { Regex("(?i)\\b(vs\\.?|versus|which is better|should i (?:buy|get|use|choose))\\b").containsMatchIn(q) }
            ?: return listOf(q)
        val a = m.groupValues[1].trim(); val b = m.groupValues[2].trim(); val ctx = m.groupValues.getOrNull(3)?.trim().orEmpty()
        if (a.isEmpty() || b.isEmpty() || a.split(' ').size > 6 || b.split(' ').size > 6) return listOf(q)
        val suffix = if (ctx.isNotEmpty()) " $ctx" else ""
        return listOf("$a vs $b$suffix", "$a$suffix", "$b$suffix").distinct()
    }
}

/**
 * Web text is untrusted data. Before it reaches the model: markup the agent or the chat template treats specially is
 * neutralised (so a page can't fake a tool call or a new turn), and sentences addressed to an AI ("ignore previous
 * instructions…") are removed and marked.
 */
object WebContent {
    private val markers = Regex("(?i)</?tool>|<\\|[a-z_]+\\|>|\\[/?(?:context|web search results|tool result[^\\]]*|system|inst)\\]|</?(?:system|assistant|user)>")
    private val injection = Regex("(?i)(ignore|disregard|forget|override)\\s+(all\\s+|any\\s+|the\\s+|your\\s+)?(previous|prior|above|earlier|system|original)\\s+(instructions?|prompts?|rules?|messages?)|" +
        "\\b(you are now|act as|pretend to be|new instructions?|system prompt|developer mode|jailbreak)\\b|" +
        "\\b(reveal|print|repeat|send|share|output)\\b.{0,40}\\b(memor(y|ies)|diary|system prompt|instructions|password|secret|api key)\\b|" +
        "\\b(call|use|run|execute|invoke)\\b.{0,20}\\b(tool|function|command)\\b")

    fun clean(text: String): String {
        val noMarkup = markers.replace(text, " ")
        var removed = false
        val kept = noMarkup.split(Regex("(?<=[.!?])\\s+")).filter { sentence -> !injection.containsMatchIn(sentence).also { if (it) removed = true } }
        val out = kept.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        return if (removed) "$out [text that addressed an AI was removed]".trim() else out
    }

    fun suspicious(text: String) = injection.containsMatchIn(text) || markers.containsMatchIn(text)
}

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

/** The outcome of trying to read one result page: [text] when it was read, otherwise [note] says why not. */
data class PageResult(val text: String?, val note: String?, val published: String? = null)

/**
 * Reads a small excerpt of a result page when robots.txt permits: the paragraphs that mention the query, up to
 * [maxChars]. Only public http(s) addresses, HTML/text only, at most [MAX_BYTES] read, [TIMEOUT_MS] per connect/read,
 * at most [MAX_REDIRECTS] redirects (each target re-checked for address and robots.txt). Scripts, styles and
 * navigation are removed. Failures are reported, never thrown.
 */
class PageReader(private val http: HttpFetcher, private val robots: RobotsPolicy) {
    suspend fun excerpt(url: String, query: String, maxChars: Int = 1200): String? = read(url, query, maxChars).text

    suspend fun read(url: String, query: String, maxChars: Int = 1200): PageResult {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            if (!SafeAddress.allowed(current)) return PageResult(null, "not a public web address")
            if (!robots.allowed(current)) return PageResult(null, "the site's robots.txt doesn't allow reading it")
            val r = withTimeoutOrNull(TIMEOUT_MS + 1000L) {
                runCatching { http.fetchPage(current, mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,text/plain"), MAX_BYTES, TIMEOUT_MS) }
            } ?: return PageResult(null, "the page took too long to load")
            val resp = r.getOrElse { return PageResult(null, "the page could not be loaded") }
            if (resp.code in 300..399) {
                val loc = resp.headers["location"] ?: return PageResult(null, "the page redirected without a target")
                current = runCatching { URI(current).resolve(loc.trim()).toString() }.getOrElse { return PageResult(null, "the page redirected to an invalid address") }
                return@repeat
            }
            if (resp.code != 200) return PageResult(null, "the site answered HTTP ${resp.code}")
            val type = resp.headers["content-type"]?.lowercase()
            if (type != null && !type.startsWith("text/html") && !type.startsWith("text/plain") && !type.startsWith("application/xhtml")) return PageResult(null, "not a web page ($type)")
            val head = resp.body.take(512).lowercase().trimStart()
            if (head.startsWith("{") || head.startsWith("[") || head.startsWith("%pdf") || !(head.contains("<html") || head.contains("<!doctype") || head.contains("<p") || head.none { it.code < 9 }))
                return PageResult(null, "not a web page")
            val text = runCatching { extract(resp.body, query, maxChars) }.getOrNull() ?: return PageResult(null, "no readable text on the page")
            return PageResult(WebContent.clean(text), null, published(resp.body))
        }
        return PageResult(null, "too many redirects")
    }

    companion object {
        const val MAX_BYTES = 1_000_000
        const val TIMEOUT_MS = 6000
        const val MAX_REDIRECTS = 3

        /** A publication date the page itself declares (article:published_time, datePublished, or a <time datetime>). */
        fun published(html: String): String? = listOf(
            Regex("(?i)<meta[^>]+(?:property|name)=[\"'](?:article:published_time|og:published_time|date|pubdate|publish-date|dc\\.date)[\"'][^>]*content=[\"']([^\"']+)"),
            Regex("(?i)\"datePublished\"\\s*:\\s*\"([^\"]+)\""),
            Regex("(?i)<time[^>]+datetime=[\"']([^\"']+)"),
        ).firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1)?.let(::isoDay) }

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
 * Research with the configured provider: plans 1–3 queries ([ResearchPlan]), runs them in parallel, merges the results
 * (no duplicate URLs, at most two per site so one site can't dominate), then reads short excerpts of the best pages
 * from *different* sites when enabled, recording for each page whether it was read and, if not, why. Everything runs
 * on the IO dispatcher with bounded time; nothing runs between requests. Snippets and page text are cleaned of
 * instruction-like content ([WebContent]) before anything else sees them.
 */
class SearchService(private val settings: SettingsStore, http: HttpFetcher, braveKey: () -> String?, private val now: () -> Long = System::currentTimeMillis) {
    private val providers: Map<String, SearchProvider> = listOf(
        BraveSearch(http, braveKey),
        SearxngSearch(http) { settings.string(Settings.SEARXNG_URL) },
        KeylessSearch(http) { settings.string(Settings.SEARCH_PROVIDER) != "wikipedia" },
    ).associateBy { it.id }
    private val reader = PageReader(http, RobotsPolicy(http, now))

    fun provider(): SearchProvider = when (settings.string(Settings.SEARCH_PROVIDER)) {
        "brave" -> providers.getValue("brave"); "searxng" -> providers.getValue("searxng"); else -> providers.getValue("keyless")
    }

    suspend fun search(query: String): SearchOutcome = withContext(Dispatchers.IO) {
        val p = provider()
        val started = now()
        p.unavailable()?.let { return@withContext SearchOutcome(query, p.label, emptyList(), it, retrievedAt = started) }
        val count = settings.int(Settings.SEARCH_RESULTS)
        val queries = ResearchPlan.queries(query)
        // Comparison queries each get a few results; the merged list is capped so the prompt stays small.
        val per = if (queries.size == 1) count else 3
        val results = coroutineScope {
            queries.map { q -> async { runOne(p, q, per) } }.awaitAll()
        }
        val errors = results.mapNotNull { it.exceptionOrNull() }
        val merged = merge(results.mapNotNull { it.getOrNull() }, if (queries.size == 1) count else count + 2)
        if (merged.isEmpty()) {
            val e = errors.firstOrNull()
            val msg = when (e) {
                null -> "${p.label} returned no results."
                is SearchException -> e.message
                is kotlinx.coroutines.TimeoutCancellationException -> "${p.label} did not answer in time."
                else -> "${p.label} could not be reached (${e.message ?: e::class.simpleName})."
            }
            return@withContext SearchOutcome(query, p.label, emptyList(), msg, queries, started)
        }
        val toRead = if (!settings.bool(Settings.SEARCH_READ_PAGES)) emptyList()
            else merged.distinctBy { hostOf(it.url) }.take(if (queries.size > 1) 3 else 2)
        val read = coroutineScope {
            toRead.map { h -> async { h.url to runCatching { reader.read(h.url, query) }.getOrElse { PageResult(null, "the page could not be loaded") } } }.awaitAll().toMap()
        }
        SearchOutcome(query, p.label, merged.map { h ->
            val clean = h.copy(title = WebContent.clean(h.title), snippet = WebContent.clean(h.snippet))
            read[h.url]?.let { r -> clean.copy(pageText = r.text, pageNote = r.note, published = clean.published ?: r.published) } ?: clean
        }, null, queries, started)
    }

    private suspend fun runOne(p: SearchProvider, q: String, count: Int): Result<List<SearchHit>> = try {
        Result.success(withTimeout(PROVIDER_TIMEOUT_MS) { p.search(q, count) })
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) { Result.failure(e)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) { Result.failure(e) }

    companion object {
        const val PROVIDER_TIMEOUT_MS = 15_000L

        /** Round-robin over the query result lists, no duplicate URLs, at most two results per site, at most [max]. */
        fun merge(lists: List<List<SearchHit>>, max: Int): List<SearchHit> {
            val out = mutableListOf<SearchHit>(); val perHost = HashMap<String, Int>()
            val iters = lists.map { it.iterator() }
            while (out.size < max && iters.any { it.hasNext() }) for (it in iters) {
                if (!it.hasNext() || out.size >= max) continue
                val h = it.next(); val host = hostOf(h.url) ?: continue
                if (out.any { o -> o.url == h.url } || (perHost[host] ?: 0) >= 2) continue
                perHost[host] = (perHost[host] ?: 0) + 1; out += h
            }
            return out
        }
    }
}
