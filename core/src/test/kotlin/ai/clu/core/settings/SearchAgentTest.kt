package ai.clu.core.settings

import ai.clu.core.*

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.Citations
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.tools.HttpResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

/** The agent's automatic search: when it runs, how the answer is built from sources, and what happens when it fails. */
class SearchAgentTest {
    private val platform = TestPlatform()
    private val core = newCore(platform = platform)
    private val http get() = platform.fakeHttp
    private val engine = ScriptedEngine()
    private val conv = core.conversations.create().id
    private val owner = core.auth.actorForSpeaker(null, speakerIdActive = false)!!

    private val brave = """{"web":{"results":[
        {"title":"Android 16 release notes","url":"https://source.android.com/docs/whatsnew/android-16-release","description":"Android 16 was released in June 2025."},
        {"title":"Android version history","url":"https://en.wikipedia.org/wiki/Android_version_history","description":"Android 16 is the latest stable version."},
        {"title":"Third","url":"https://example.org/3","description":"c"},{"title":"Fourth","url":"https://example.org/4","description":"d"},
        {"title":"Fifth","url":"https://example.org/5","description":"e"}]}}"""

    init {
        core.useEngine(engine)
        core.settings.set(Settings.SEARCH_PROVIDER, "brave")
        core.settings.set(Settings.SEARCH_READ_PAGES, false)
        platform.secrets[CluCore.BRAVE_SEARCH_KEY] = "test-key"
        http.routes["api.search.brave.com"] = HttpResponse(200, brave)
    }

    private fun ask(text: String, voice: Boolean = false) = runBlocking { core.agent.respond(TurnRequest(conv, text, owner, viaVoice = voice)).toList() }
    private fun done(events: List<AgentEvent>) = events.filterIsInstance<AgentEvent.Done>().single()

    @Test fun personalDataNeverSentToTheProvider() {
        core.settings.set(Settings.OWNER_NAME, "Ashwin")
        core.memories.save(ai.clu.core.memory.Memory(category = ai.clu.core.memory.MemoryCategory.CORE_FACT, title = "Sister in Pune",
            content = "Pune weather forecast note: my sister Riya says carry an umbrella to flat zebra-17.", source = ai.clu.core.memory.MemorySource.OWNER_SUPPLIED))
        core.diary.add("Argued with Riya about the Pune flat. secret-diary-marker")
        ask("Hello, how are you?") // some history in the conversation
        engine.reply("Rain is expected in Pune today [1].")
        val d = done(ask("What's the weather forecast in Pune today?"))
        assertTrue(d.meta.sources.isNotEmpty())
        assertTrue(engine.prompts.last().any { it.content.contains("zebra-17") }, "the memory is used locally in the prompt: ${engine.prompts.last()}")
        val outgoing = http.requests + http.headers.map { it.toString() }
        assertTrue(outgoing.isNotEmpty())
        for (secret in listOf("zebra", "Riya", "secret-diary", "Ashwin", "Hello"))
            assertTrue(outgoing.none { it.contains(secret, ignoreCase = true) }, "\"$secret\" must not leave the device: $outgoing")
    }

    @Test fun autoSetting() {
        engine.reply("Android 16 is the latest stable version, released in June 2025 [1][2]. Also see [9].")
        val ev = ask("What is the latest version of Android?")
        assertTrue(ev.any { it is AgentEvent.ToolStarted && it.tool == "web.search" }, "searched automatically")
        val d = done(ev)
        assertEquals(listOf("https://source.android.com/docs/whatsnew/android-16-release", "https://en.wikipedia.org/wiki/Android_version_history"),
            d.meta.sources.take(2).map { it.url }, "sources are exactly what the provider returned")
        assertEquals("Android 16 is the latest stable version, released in June 2025 [1][2]. Also see.", d.text, "a citation to a non-existent source is removed")
        val sent = engine.prompts.last().last().content
        assertTrue(sent.startsWith("[Web search results]") && sent.contains("[1] Android 16 release notes (source.android.com; snippet only)"))
        assertFalse(sent.contains("https://"), "the model gets numbered sources and site names, not URLs")
        assertTrue(sent.contains("Cite sources as [1], [2]") && sent.contains("say so plainly"))
        assertEquals(1, http.requests.size, "one request: ${http.requests}"); assertTrue(http.requests.single().contains("q=What+is+the+latest+version+of+Android&"), "only the question is sent: ${http.requests}")
        // On request only: the same question is answered without searching.
        core.settings.set(Settings.SEARCH_AUTO, "explicit"); http.requests.clear()
        engine.reply("I can't check live information without searching.")
        val ev2 = ask("What is the latest version of Android?")
        assertTrue(http.requests.isEmpty()); assertTrue(done(ev2).meta.sources.isEmpty())
        // ...but "search …" still searches.
        engine.reply("Results say Android 16 [1].")
        assertTrue(done(ask("search latest Android version")).meta.sources.isNotEmpty())
    }

    @Test fun resultCount() {
        engine.reply("ok [1]")
        ask("What is the latest version of Android?")
        assertTrue(http.requests.last().contains("count=4"), "default 4")
        assertEquals(4, engine.prompts.last().last().content.lines().count { Regex("^\\[\\d] ").containsMatchIn(it) })
        core.settings.set(Settings.SEARCH_RESULTS, 3); engine.reply("ok")
        ask("What is the latest version of Android?")
        assertTrue(http.requests.last().contains("count=3"))
        assertEquals(3, engine.prompts.last().last().content.lines().count { Regex("^\\[\\d] ").containsMatchIn(it) })
        assertIs<ai.clu.core.config.SettingResult.Invalid>(core.settings.set(Settings.SEARCH_RESULTS, 9))
    }

    @Test fun ordinaryConversationNeverSearches() {
        listOf("Hello Clu", "How are you?", "Explain recursion simply", "Write a short poem about rain", "What is photosynthesis?", "Thanks").forEach {
            engine.reply("Sure."); ask(it)
        }
        assertTrue(http.requests.isEmpty(), "no network request for ordinary conversation: ${http.requests}")
        assertTrue(engine.prompts.all { p -> p.none { it.content.startsWith("[Web search results]") } })
    }

    @Test fun searchFailureOfflineDoesNotBreakChat() {
        core.settings.set(Settings.OFFLINE_BEHAVIOR, "explain") // the default is now "local_answer" (tested below)
        platform.online = false
        val d = done(ask("What is the price of gold today?"))
        assertTrue(d.text.startsWith("I couldn't search the web just now (No internet connection is available right now)"), d.text)
        assertEquals("search_failed", d.meta.error); assertTrue(d.meta.sources.isEmpty())
        assertTrue(engine.prompts.isEmpty(), "with \"explain\", nothing is made up by the model")
        // The next ordinary message is answered offline as usual.
        engine.reply("Recursion is a function calling itself.")
        assertEquals("Recursion is a function calling itself.", done(ask("Explain recursion simply")).text)
    }

    @Test fun searchFailureWithLocalAnswer() {
        core.settings.set(Settings.OFFLINE_BEHAVIOR, "local_answer")
        http.routes["api.search.brave.com"] = HttpResponse(429, "{}")
        engine.reply("I couldn't complete the search. From my own knowledge, which may be outdated: gold was around ...")
        val d = done(ask("What is the price of gold today?"))
        assertTrue(engine.prompts.last().last().content.contains("OFFLINE: the web search could not be completed (Brave Search rate limit or monthly quota reached (HTTP 429))"))
        assertTrue(d.text.startsWith("I couldn't complete the search")); assertTrue(d.meta.sources.isEmpty(), "no sources claimed"); assertEquals("search_failed", d.meta.error, "labelled as answered without the web")
    }

    @Test fun modelCalledSearchAlsoCarriesSources() {
        engine.reply("<tool>{\"name\": \"web.search\", \"args\": {\"query\": \"kotlin coroutines guide\"}}</tool>", "Coroutines are lightweight threads [1].")
        val d = done(ask("Find me a good explanation of Kotlin coroutines"))
        assertEquals("https://source.android.com/docs/whatsnew/android-16-release", d.meta.sources.first().url)
        assertTrue(engine.prompts.last().last().content.startsWith("[Web search results]"))
    }

    @Test fun spokenAnswersHaveNoCitationNumbers() {
        engine.reply("Android 16 is the latest [1].")
        val d = done(ask("What is the latest version of Android?", voice = true))
        assertEquals("Android 16 is the latest.", d.text)
        assertTrue(engine.prompts.last().last().content.contains("do not read out numbers or links"))
        assertEquals("A [1] b.", Citations.clean("A [1] b [3].", 2, spoken = false))
    }

    @Test fun voiceStaysResponsiveDuringASlowSearch(): Unit = runBlocking {
        http.delayMs = 1500
        engine.reply("Gold is ... [1]")
        val text = async(kotlinx.coroutines.Dispatchers.Default) { core.agent.respond(TurnRequest(conv, "What is the price of gold today?", owner)).toList() }
        delay(200) // the search is now in flight
        val t0 = System.nanoTime()
        val voice = core.agent.respond(TurnRequest(conv, "what time is it", owner, viaVoice = true)).toList()
        val voiceMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(voice.filterIsInstance<AgentEvent.Done>().single().text.startsWith("It is"))
        assertTrue(voiceMs < 500, "a voice request is answered while the search waits on the network (took $voiceMs ms)")
        assertFalse(text.isCompleted, "the search was still running")
        assertTrue(text.await().filterIsInstance<AgentEvent.Done>().single().meta.sources.isNotEmpty())
    }
}
