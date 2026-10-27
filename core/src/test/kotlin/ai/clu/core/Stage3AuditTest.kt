package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.search.*
import ai.clu.core.tools.HttpResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.*

/**
 * Stage 3 audit: behavioural regression tests for defects found by probing (MOCKED HTTP; no live service).
 * Each test names the defect it guards against.
 */
class Stage3AuditTest {
    private val platform = TestPlatform()
    private val core = newCore(platform = platform)
    private val http get() = platform.fakeHttp
    private val engine = ScriptedEngine().also { core.useEngine(it) }
    private val owner = core.auth.actorForSpeaker(null, false)!!
    init { core.settings.set(Settings.SEARCH_PROVIDER, "searxng"); core.settings.set(Settings.SEARXNG_URL, "https://searx.example.org") }

    /** Defect: regex-based extraction took 189 s (and longer) on pages with thousands of unclosed tags, pinning a CPU core. */
    @Test fun hostilePagesAreProcessedInLinearTime() {
        val pages = mapOf(
            "unclosed scripts" to "<p>x</p>" + "<script>".repeat(20_000) + "a".repeat(800_000),
            "unclosed paragraphs" to "<p>".repeat(100_000) + "word ".repeat(100_000),
            "deep nesting" to "<div>".repeat(150_000),
            "no tags" to "ignore ".repeat(150_000),
            "unterminated tag" to "<p>Good text about Android releases here. " + "<a href='".repeat(50_000),
            "comment bomb" to "<!--".repeat(100_000) + "<p>after</p>")
        for ((name, html) in pages) {
            val t0 = System.nanoTime()
            runCatching { PageReader.extract(html, "android version", 1200) }
            WebContent.clean(html.take(PageReader.MAX_BYTES))
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms < 2000, "$name took $ms ms")
        }
        // Normal pages still extract the right text.
        val page = """<html><head><script>var x = "<p>fake paragraph inside a script tag here</p>";</script><style>p{}</style></head><body><nav>Home Android menu</nav>
            <p>Unrelated introduction that is long enough to count as a paragraph here.</p><!-- <p>commented out paragraph about Android 16</p> -->
            <p>Android 16 was released to <b>Pixel</b> phones on 10 June 2025, earlier than usual.</p><footer>Android footer text</footer></body></html>"""
        val ex = PageReader.extract(page, "android 16 release", 1200)!!
        assertTrue(ex.contains("Android 16 was released to Pixel phones on 10 June 2025"), ex)
        assertFalse(ex.contains("fake paragraph") || ex.contains("commented out") || ex.contains("footer") || ex.contains("menu"))
    }

    /** Defect: Stop during a search let the provider, robots.txt and page requests continue, and the turn ended without a Done. */
    @Test fun stopDuringResearchStopsFurtherRequests(): Unit = runBlocking {
        http.routes["searx.example.org"] = HttpResponse(200, """{"results":[{"url":"https://a.example/x","title":"T","content":"c"},{"url":"https://b.example/y","title":"U","content":"d"}]}""")
        http.routes["a.example/x"] = HttpResponse(200, "<html><p>Long enough paragraph about the latest Android version release.</p></html>")
        http.delayMs = 400
        val conv = core.conversations.create().id
        val job = async(Dispatchers.Default) { core.agent.respond(TurnRequest(conv, "What is the latest Android version?", owner)).toList() }
        delay(150); core.agent.cancel(null)
        val ev = job.await()
        delay(600) // anything still running would have issued its next request by now
        assertEquals(listOf("https://searx.example.org/search?q=What+is+the+latest+Android+version&format=json&safesearch=1"), http.requests.toList(),
            "no robots.txt or page requests after Stop")
        assertTrue(engine.prompts.isEmpty(), "the model isn't run on a cancelled search")
        val done = ev.filterIsInstance<AgentEvent.Done>().single()
        assertTrue(done.meta.interrupted); assertTrue(done.meta.sources.isEmpty())
        // The next message works normally.
        http.delayMs = 0; engine.reply("Hello.")
        assertEquals("Hello.", core.agent.respond(TurnRequest(conv, "Hi Clu", owner)).toList().filterIsInstance<AgentEvent.Done>().single().text)
    }

    /** Defect: names were checked only by spelling, so a public-looking name resolving into the LAN (DNS rebinding) was fetched. */
    @Test fun namesResolvingIntoTheLocalNetworkAreRefused(): Unit = runBlocking {
        val reader = PageReader(http, RobotsPolicy(http))
        http.dns["rebind.example"] = listOf("192.168.1.1")
        http.dns["mixed.example"] = listOf("93.184.216.34", "10.0.0.7")
        http.dns["v6local.example"] = listOf("fd12::1")
        http.dns["mapped.example"] = listOf("::ffff:127.0.0.1")
        for (h in listOf("rebind.example", "mixed.example", "v6local.example", "mapped.example")) {
            http.routes["$h/page"] = HttpResponse(200, "<p>secret router page content that must never be read</p>")
            assertEquals("not a public web address", reader.read("https://$h/page", "router").note, h)
        }
        // A redirect to such a name is refused too.
        http.routes["public.example/go"] = HttpResponse(302, "", mapOf("location" to "https://rebind.example/page"))
        assertEquals("not a public web address", reader.read("https://public.example/go", "x").note)
        assertTrue(http.requests.none { it.contains("rebind.example/page") || it.contains("mixed.example/page") || it.contains("v6local") || it.contains("mapped.example/page") },
            "${http.requests}")
        // Unusual numeric spellings of local addresses.
        listOf("http://0300.0250.0.1/", "http://0x7f.0.0.1/", "http://127.1/", "http://2130706433/", "http://[::ffff:192.168.1.1]/", "http://[fe80::1]/",
            "http://256.1.1.1/", "http://010.0.0.1/").forEach { assertFalse(SafeAddress.allowed(it), it) }
        listOf("https://1e100.net/", "https://cafe.be/", "http://8.8.8.8/", "http://[2606:4700::1111]/").forEach { assertTrue(SafeAddress.allowed(it), it) }
        assertTrue(SafeAddress.publicIp("2606:4700::1111") && !SafeAddress.publicIp("::1") && !SafeAddress.publicIp("100.64.1.1"))
    }

    /** Ordinary conversation, online or offline, never touches the network. */
    @Test fun ordinaryConversationMakesNoNetworkRequests(): Unit = runBlocking {
        val conv = core.conversations.create().id
        for (online in listOf(true, false)) {
            platform.online = online
            for (t in listOf("Hello Clu", "How are you today?", "Tell me a story about a fox", "Explain recursion simply", "I feel tired today",
                "What do you think about my plan to study at night?", "Remember that I like jazz")) {
                engine.reply("OK.")
                core.agent.respond(TurnRequest(conv, t, owner)).toList()
            }
        }
        assertTrue(http.requests.isEmpty(), "${http.requests}")
    }

    /** Malformed provider responses and pages never crash research; they become a stated failure. */
    @Test fun garbageResponsesNeverCrash(): Unit = runBlocking {
        val rnd = Random(42)
        val bodies = listOf("", "{", "[]", "null", "{\"results\":5}", "{\"results\":[1,2,null,{\"url\":5}]}", "{\"web\":{\"results\":[{\"url\":\"javascript:alert(1)\"}]}}",
            "<html>not json</html>", "{\"query\":{\"search\":\"x\"}}", "\u0000\u0001\u0002") +
            List(30) { String(CharArray(rnd.nextInt(1, 4000)) { (rnd.nextInt(0, 0xFFFF)).toChar() }) }
        for (b in bodies) {
            for (parse in listOf<(String) -> Any>({ SearxngSearch.parse(it) }, { BraveSearch.parse(it) }, { KeylessSearch.parseWikipedia(it) }, { KeylessSearch.parseInstantAnswer(it) })) {
                try { parse(b) } catch (e: SearchException) { /* stated failure */ }
            }
            http.routes["searx.example.org"] = HttpResponse(200, b)
            val out = core.search.search("anything")
            assertTrue(out.error != null || out.hits.all { it.url.startsWith("http") })
            http.routes["page.example/p"] = HttpResponse(200, b)
            PageReader(http, RobotsPolicy(http)).read("https://page.example/p", "x")
        }
    }
}
