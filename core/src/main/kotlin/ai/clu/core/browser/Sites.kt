package ai.clu.core.browser

import java.net.URI
import java.net.URLEncoder

/**
 * Named websites CLU opens directly ("open YouTube", "search Amazon for headphones") instead of sending them through a
 * search engine, and the rules for which pages it may open at all: complete http(s) URLs only, never one carrying a
 * user name or password.
 */
object Sites {
    data class Site(val name: String, val home: String, val search: String?)

    private fun enc(q: String) = URLEncoder.encode(q.trim(), "UTF-8")

    val KNOWN: Map<String, Site> = listOf(
        Site("YouTube", "https://www.youtube.com", "https://www.youtube.com/results?search_query="),
        Site("YouTube Music", "https://music.youtube.com", "https://music.youtube.com/search?q="),
        Site("Google", "https://www.google.com", "https://www.google.com/search?q="),
        Site("Google Maps", "https://www.google.com/maps", "https://www.google.com/maps/search/"),
        Site("Gmail", "https://mail.google.com", null),
        Site("Wikipedia", "https://en.wikipedia.org", "https://en.wikipedia.org/w/index.php?search="),
        Site("Amazon", "https://www.amazon.in", "https://www.amazon.in/s?k="),
        Site("Flipkart", "https://www.flipkart.com", "https://www.flipkart.com/search?q="),
        Site("Reddit", "https://www.reddit.com", "https://www.reddit.com/search/?q="),
        Site("GitHub", "https://github.com", "https://github.com/search?q="),
        Site("Stack Overflow", "https://stackoverflow.com", "https://stackoverflow.com/search?q="),
        Site("Spotify", "https://open.spotify.com", "https://open.spotify.com/search/"),
        Site("Netflix", "https://www.netflix.com", "https://www.netflix.com/search?q="),
        Site("WhatsApp", "https://web.whatsapp.com", null),
        Site("LinkedIn", "https://www.linkedin.com", "https://www.linkedin.com/search/results/all/?keywords="),
        Site("X", "https://x.com", "https://x.com/search?q="),
        Site("Instagram", "https://www.instagram.com", null),
        Site("DuckDuckGo", "https://duckduckgo.com", "https://duckduckgo.com/?q="),
    ).flatMap { s -> aliases(s.name).map { it to s } }.toMap()

    private fun aliases(name: String): List<String> = when (name) {
        "YouTube" -> listOf("youtube", "you tube", "yt")
        "YouTube Music" -> listOf("youtube music", "yt music")
        "Google Maps" -> listOf("google maps", "maps")
        "Stack Overflow" -> listOf("stack overflow", "stackoverflow")
        "X" -> listOf("x", "twitter")
        else -> listOf(name.lowercase())
    }

    /** The site a spoken name refers to ("youtube", "the YouTube website"), or null. */
    fun named(raw: String): Site? {
        val n = raw.lowercase().trim().removePrefix("the ").removeSuffix(" website").removeSuffix(" site").removeSuffix(".com").trim()
        return KNOWN[n]
    }

    /** A URL for what the owner said: a known site, a domain ("example.org"), or a full URL. Null when it is neither. */
    fun urlFor(raw: String): String? {
        named(raw)?.let { return it.home }
        val t = raw.trim().removeSuffix(".").removeSuffix("/")
        val withScheme = when {
            Regex("(?i)^https?://\\S+$").matches(t) -> t
            Regex("(?i)^(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}(?:/\\S*)?$").matches(t) -> "https://$t"
            else -> return null
        }
        return withScheme.takeIf { allowed(it) }
    }

    /** The site's own search page for [query]; a general web search (DuckDuckGo) when no site is named or it has none. */
    fun searchUrl(site: String?, query: String): String {
        val s = site?.let(::named)
        return (s?.search ?: KNOWN.getValue("duckduckgo").search!!) + enc(query)
    }

    /** Only complete http(s) URLs, never with an embedded user name or password. */
    fun allowed(url: String): Boolean = runCatching {
        val u = URI(url.trim())
        (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrBlank() && u.userInfo == null
    }.getOrDefault(false)

    /** Words on a control that make clicking it consequential: CLU asks before pressing it. */
    private val CONSEQUENTIAL = Regex("(?i)\\b(send|submit|buy|purchase|order|pay|checkout|check out|place order|delete|remove|confirm|post|publish|" +
        "transfer|subscribe|unsubscribe|sign up|book|reserve|donate|accept|agree|install|uninstall|log ?out|sign ?out)\\b")
    fun consequential(target: String) = CONSEQUENTIAL.containsMatchIn(target)
}
