package ai.clu.core.settings

import ai.clu.core.*

import ai.clu.core.config.Settings
import ai.clu.core.search.*
import ai.clu.core.tools.HttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Search providers against responses in each provider's documented format (recorded shapes, served by a fake HTTP
 * client: the build machine has no access to the real services), robots.txt handling, page excerpts, the trigger that
 * decides when to search, and the service's error handling. Live provider calls are not part of these tests.
 */
class SearchTest {
    private val platform = TestPlatform()
    private val core = newCore(platform = platform)
    private val http get() = platform.fakeHttp

    private val brave = """{"type":"search","web":{"type":"search","results":[
        {"title":"Android 16 <strong>release</strong>","url":"https://source.android.com/docs/whatsnew/android-16-release","description":"Android 16 was released in June 2025.","extra_snippets":["Stable on Pixel devices."]},
        {"title":"No URL","description":"dropped"},
        {"title":"Android version history","url":"https://en.wikipedia.org/wiki/Android_version_history","description":"List of Android versions."}]}}"""
    private val searx = """{"query":"x","number_of_results":2,"results":[
        {"url":"https://example.org/a","title":"A &amp; B","content":"First <b>result</b>.","engine":"duckduckgo"},
        {"url":"https://example.org/a","title":"dup","content":"dup","engine":"bing"},
        {"url":"https://example.net/b","title":"B","content":"Second.","engine":"brave"}]}"""
    private val wiki = """{"batchcomplete":"","query":{"search":[{"ns":0,"title":"Pune","pageid":1,"snippet":"<span class=\"searchmatch\">Pune</span> is a city in Maharashtra"}]}}"""
    private val ia = """{"Heading":"Pune","AbstractText":"Pune is a city in western India.","AbstractURL":"https://en.wikipedia.org/wiki/Pune","AbstractSource":"Wikipedia","Answer":""}"""

    @Test fun braveFormatParsedAndKeySentAsHeader(): Unit = runBlocking {
        val hits = BraveSearch.parse(brave)
        assertEquals(2, hits.size, "results without a URL are dropped")
        assertEquals("Android 16 release", hits[0].title, "markup removed")
        assertEquals("Android 16 was released in June 2025. Stable on Pixel devices.", hits[0].snippet)
        assertFailsWith<SearchException> { BraveSearch.parse("<html>") }
        http.routes["api.search.brave.com"] = HttpResponse(200, brave)
        val p = BraveSearch(http) { "key-123" }
        p.search("android 16", 3)
        assertEquals("key-123", http.headers.last()["X-Subscription-Token"])
        http.routes["api.search.brave.com"] = HttpResponse(429, "{}")
        assertTrue(assertFailsWith<SearchException> { p.search("x", 3) }.message!!.contains("quota"))
        http.routes["api.search.brave.com"] = HttpResponse(401, "{}")
        assertTrue(assertFailsWith<SearchException> { p.search("x", 3) }.message!!.contains("rejected the API key"))
        assertNotNull(BraveSearch(http) { null }.unavailable(), "no key: unavailable, nothing sent")
    }

    @Test fun searxng(): Unit = runBlocking {
        val hits = SearxngSearch.parse(searx)
        assertEquals(listOf("https://example.org/a", "https://example.net/b"), hits.map { it.url }, "duplicates merged")
        assertEquals("A & B", hits[0].title); assertEquals("First result.", hits[0].snippet)
        assertNotNull(SearxngSearch(http) { "" }.unavailable())
        core.settings.set(Settings.SEARCH_PROVIDER, "searxng")
        core.settings.set(Settings.SEARXNG_URL, "https://searx.example.org/")
        http.routes["searx.example.org"] = HttpResponse(200, searx)
        core.settings.set(Settings.SEARCH_READ_PAGES, false)
        val out = core.search.search("x")
        assertNull(out.error); assertEquals(2, out.hits.size)
        assertTrue(http.requests.any { it.startsWith("https://searx.example.org/search?q=x&format=json") })
        http.routes["searx.example.org"] = HttpResponse(403, "")
        assertTrue(core.search.search("x").error!!.contains("enable the json format"))
        assertIs<ai.clu.core.config.SettingResult.Invalid>(core.settings.set(Settings.SEARXNG_URL, "not a url"))
    }

    @Test fun keylessWikipediaAndInstantAnswers(): Unit = runBlocking {
        assertEquals("https://en.wikipedia.org/wiki/Pune", KeylessSearch.parseWikipedia(wiki).single().url)
        assertEquals("Pune is a city in Maharashtra", KeylessSearch.parseWikipedia(wiki).single().snippet)
        assertEquals("Pune is a city in western India.", KeylessSearch.parseInstantAnswer(ia).single().snippet)
        assertTrue(KeylessSearch.parseInstantAnswer("not json").isEmpty())
        core.settings.set(Settings.SEARCH_READ_PAGES, false)
        http.routes["api.duckduckgo.com"] = HttpResponse(200, ia)
        http.routes["wikipedia.org/w/api.php"] = HttpResponse(200, wiki)
        val out = core.search.search("Pune")
        assertEquals("Wikipedia + DuckDuckGo Instant Answers", out.provider)
        assertEquals(1, out.hits.size, "same page from both sources counted once")
        http.requests.clear()
        core.settings.set(Settings.SEARCH_PROVIDER, "wikipedia")
        core.search.search("Pune")
        assertTrue(http.requests.none { it.contains("duckduckgo") }, "Wikipedia only")
    }

    @Test fun robotsRules() {
        val rules = RobotsPolicy.parse("""
            User-agent: Googlebot
            Disallow: /
            User-agent: *
            Disallow: /private/
            Allow: /private/public-*.html$
            Disallow: /*.pdf$
        """.trimIndent())
        assertTrue(RobotsPolicy.decide(rules, "/news/today"))
        assertFalse(RobotsPolicy.decide(rules, "/private/x"))
        assertTrue(RobotsPolicy.decide(rules, "/private/public-1.html"), "longer Allow wins")
        assertFalse(RobotsPolicy.decide(rules, "/docs/file.pdf"))
        assertTrue(RobotsPolicy.decide(rules, "/docs/file.pdf?x=1"), "\$ anchors the end")
        val own = RobotsPolicy.parse("User-agent: *\nDisallow: /\n\nUser-agent: Clu\nAllow: /")
        assertTrue(RobotsPolicy.decide(own, "/anything"), "Clu's own group takes precedence over *")
    }

    @Test fun pageReading(): Unit = runBlocking {
        val page = """<html><head><script>var x = "Android 16 secret";</script></head><body><nav>Home Android</nav>
            <p>Unrelated introduction that is long enough to count as a paragraph here.</p>
            <p>Android 16 was released to Pixel phones on 10 June 2025, earlier than usual.</p><footer>Android footer text</footer></body></html>"""
        val ex = PageReader.extract(page, "android 16 release", 1200)!!
        assertTrue(ex.contains("10 June 2025")); assertFalse(ex.contains("secret") || ex.contains("footer"))
        // robots.txt is honoured: a disallowed page is not fetched.
        http.routes["blocked.example/robots.txt"] = HttpResponse(200, "User-agent: *\nDisallow: /")
        http.routes["open.example/robots.txt"] = HttpResponse(404, "")
        http.routes["blocked.example/page"] = HttpResponse(200, page)
        http.routes["open.example/page"] = HttpResponse(200, page)
        val reader = PageReader(http, RobotsPolicy(http))
        assertNull(reader.excerpt("https://blocked.example/page", "android 16"))
        assertFalse(http.requests.contains("https://blocked.example/page"), "never requested")
        assertNotNull(reader.excerpt("https://open.example/page", "android 16"))
        http.routes["down.example/robots.txt"] = HttpResponse(503, "")
        assertNull(reader.excerpt("https://down.example/page", "x"), "robots.txt server error: treated as disallowed")
        // The service reads at most the top two pages, only when the setting is on.
        http.routes["searx.example.org"] = HttpResponse(200, """{"results":[
            {"url":"https://open.example/page","title":"T1","content":"c1"},{"url":"https://open.example/page2","title":"T2","content":"c2"},
            {"url":"https://open.example/page3","title":"T3","content":"c3"}]}""")
        http.routes["open.example/page2"] = HttpResponse(200, page); http.routes["open.example/page3"] = HttpResponse(200, page)
        core.settings.set(Settings.SEARCH_PROVIDER, "searxng"); core.settings.set(Settings.SEARXNG_URL, "https://searx.example.org")
        http.requests.clear()
        val out = core.search.search("android 16 release")
        assertNotNull(out.hits[0].pageText); assertNotNull(out.hits[1].pageText); assertNull(out.hits[2].pageText)
        assertFalse(http.requests.contains("https://open.example/page3"))
        core.settings.set(Settings.SEARCH_READ_PAGES, false); http.requests.clear()
        assertTrue(core.search.search("android 16 release").hits.all { it.pageText == null })
        assertTrue(http.requests.none { it.contains("open.example") })
    }

    @Test fun serviceNeverInventsResults(): Unit = runBlocking {
        core.settings.set(Settings.SEARCH_READ_PAGES, false)
        core.settings.set(Settings.SEARCH_PROVIDER, "brave")
        val noKey = core.search.search("x")
        assertTrue(noKey.hits.isEmpty() && noKey.error!!.contains("No Brave Search API key"))
        assertTrue(http.requests.isEmpty(), "nothing sent without a key")
        platform.secrets[CluCore.BRAVE_SEARCH_KEY] = "k"
        http.routes["api.search.brave.com"] = HttpResponse(200, """{"web":{"results":[]}}""")
        assertTrue(core.search.search("x").error!!.contains("no results"))
        http.routes["api.search.brave.com"] = HttpResponse(200, "<html>captcha</html>")
        assertTrue(core.search.search("x").error!!.contains("unexpected response"))
        http.failWith = java.io.IOException("Unable to resolve host")
        assertTrue(core.search.search("x").error!!.contains("could not be reached"))
    }

    @Test fun triggerSearchesOnlyWhenNeeded() {
        val now = core.clock.now() // October 2025 in the test clock
        val needs = listOf(
            "What's the latest version of Android?", "Any news about the ISRO launch today?", "What is the price of gold today?",
            "Who won the match yesterday?", "What's the weather in Pune tomorrow?", "When was Kotlin 2.2 released?",
            "Is it true that Pluto is a planet again? Check online.", "What is the current exchange rate of USD to INR?",
            "Tell me the headlines this morning", "What happened in the election results?", "Who is the current CEO of OpenAI?",
            "What are the new features in iOS 2025?", "Look it up: population of Japan", "Can you give me sources on intermittent fasting research?",
        )
        val ordinary = listOf(
            "Hello Clu", "How are you today?", "Write a poem about rain", "What do you think about my plan to study at night?",
            "What is photosynthesis?", "Explain recursion simply", "Remember that my exam is on Friday", "What's my wife's birthday?",
            "Thanks, that helps", "I'm feeling tired today", "Tell me a joke", "How do I make tea?", "Does this match the colour of my shirt?",
            "Translate good morning into Hindi", "What is 23 times 7?", "Who wrote Hamlet?", "Summarise what we discussed",
        )
        needs.forEach { assertNotNull(SearchTrigger.detect(it, now), "should search: $it") }
        ordinary.forEach { assertNull(SearchTrigger.detect(it, now), "should not search: $it") }
        assertEquals("the latest version of Android", SearchTrigger.clean("Clu, can you tell me the latest version of Android please?"))
    }
}
