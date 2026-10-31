package ai.clu.core

import ai.clu.core.agent.*
import ai.clu.core.config.Settings
import ai.clu.core.history.ArchivePerson
import ai.clu.core.history.DateParser
import ai.clu.core.memory.Memory
import ai.clu.core.memory.MemoryCategory
import ai.clu.core.memory.MemorySource
import ai.clu.core.tools.HttpResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Stage 4 completion (1.8.2): the scenarios the Stage 4 brief lists that [ConversationContextTest] (1.7.0) did not
 * cover. Deterministic: scripted model, recorded provider responses in the services' documented formats; no network.
 */
class Stage4CompletionTest {
    private val platform = TestPlatform()
    private val http get() = platform.fakeHttp
    private val core = newCore(platform = platform)
    private val engine = ScriptedEngine().also { core.useEngine(it) }
    private val conv = core.conversations.create("stage4").id

    companion object {
        /** Two active cyclones both near Chennai: one in the Bay of Bengal, one in the Arabian Sea. */
        val TWO_NEAR = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[86.4,15.2]},"properties":{"eventtype":"TC","eventid":1001,"name":"DANA-24",
             "description":"Tropical Cyclone DANA-24","alertlevel":"Orange","iscurrent":"true","country":"India, Bangladesh","source":"JTWC",
             "datemodified":"2026-10-29T06:00:00","severitydata":{"severitytext":"Tropical Cyclone (maximum wind speed of 120 km/h)"}}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[72.0,12.0]},"properties":{"eventtype":"TC","eventid":1003,"name":"VAYU-24",
             "description":"Tropical Cyclone VAYU-24","alertlevel":"Green","iscurrent":"true","country":"India","source":"JTWC",
             "datemodified":"2026-10-29T05:00:00","severitydata":{"severitytext":"Tropical Storm (maximum wind speed of 75 km/h)"}}}]}"""

        /** Open-Meteo with three forecast days (today, tomorrow, the day after). */
        const val WEATHER = """{"current":{"time":"2026-10-29T15:00","temperature_2m":27.4,"relative_humidity_2m":70,"weather_code":61,"wind_speed_10m":12.0},
            "daily":{"time":["2026-10-29","2026-10-30","2026-10-31"],"weather_code":[61,3,0],"temperature_2m_max":[29.0,31.0,32.0],
            "temperature_2m_min":[21.0,22.0,23.0],"precipitation_probability_max":[80,20,5]}}"""
    }

    private fun route(q: String, r: HttpResponse) { http.routes["srsearch=" + java.net.URLEncoder.encode(q, "UTF-8") + "&"] = r }
    private fun ask(text: String, voice: Boolean = false, c: CluCore = core, conversation: String = conv) = runBlocking {
        c.agent.respond(TurnRequest(conversation, text, ai.clu.core.security.Actor.ownerInApp(), viaVoice = voice)).toList()
    }
    private fun say(text: String, voice: Boolean = false, conversation: String = conv) = ask(text, voice, conversation = conversation).filterIsInstance<AgentEvent.Done>().single()
    private fun queries() = http.requests.filter { "srsearch=" in it }.map { java.net.URLDecoder.decode(it.substringAfter("srsearch=").substringBefore("&"), "UTF-8") }
    private fun gdacs() = http.requests.count { "gdacsapi" in it }
    private fun meteo() = http.requests.count { "open-meteo" in it }
    private fun encyclopedia() = http.requests.count { "wikipedia" in it || "duckduckgo" in it }

    // 2 (exact wording) + 13: two cyclones near home are both named, and "it" is asked about rather than guessed.
    @Test fun twoNearbyCyclonesAreDistinguished() {
        core.settings.set(Settings.HOME_LOCATION, "Chennai"); http.routes["gdacsapi"] = HttpResponse(200, TWO_NEAR)
        val first = say("Which cyclone is approaching?").text
        assertTrue("DANA-24" in first && "VAYU-24" in first, first)
        assertTrue(Regex("DANA-24 is about [\\d,]+ km east-northeast of Chennai").containsMatchIn(first), first)
        assertTrue(Regex("VAYU-24 is about [\\d,]+ km west of Chennai").containsMatchIn(first), first)
        val q = say("Is it heading towards us?").text
        assertEquals("Do you mean DANA-24 or VAYU-24?", q)
        val a = say("VAYU").text
        assertTrue(a.startsWith("The data I have gives its current position, not a forecast track"), a)
        assertTrue("west of Chennai" in a && "east-northeast" !in a, "about VAYU-24, not the nearest one: $a")
        // The choice carries into the next turn.
        val d = say("Is it dangerous?").text
        assertTrue(d.startsWith("GDACS rates Tropical Cyclone VAYU-24 Green"), d)
        // Naming the other one switches to it without another question.
        val n = say("And is DANA dangerous?").text
        assertTrue(n.startsWith("GDACS rates Tropical Cyclone DANA-24 Orange"), n)
        assertEquals(1, gdacs(), "follow-ups reuse the fresh check"); assertEquals(0, encyclopedia())
        assertTrue(engine.prompts.isEmpty(), "no model call: every answer is from the data")
    }

    // 14: a home location entered as coordinates is used for distance and direction.
    @Test fun configuredCoordinatesAreUsed() {
        core.settings.set(Settings.HOME_LOCATION, "13.08, 80.27"); http.routes["gdacsapi"] = HttpResponse(200, ConversationContextTest.GDACS)
        val a = say("Is a cyclone heading towards us?").text
        assertTrue(Regex("Tropical Cyclone DANA-24 is about [\\d,]+ km east-northeast of").containsMatchIn(a), a)
    }

    // 15: "us" with no location, on a follow-up too: one question, then the follow-up is answered for that place.
    @Test fun aFollowUpAboutUsWithoutALocationAsksForIt() {
        http.routes["gdacsapi"] = HttpResponse(200, ConversationContextTest.GDACS)
        val first = say("Which cyclones are active right now?").text
        assertTrue("DANA-24" in first, first)
        // "Tell me more" isn't about the owner's location: no question.
        assertTrue(say("Tell me more").text.contains("DANA-24"))
        val q = say("Is it heading towards us?").text
        assertTrue(q.startsWith("Which city or area should I check"), "\"us\" is not guessed: $q")
        val a = say("Chennai").text
        assertTrue(a.startsWith("The data I have gives its current position, not a forecast track") && "of Chennai" in a, a)
    }

    // 11: weather goes to the weather service (never the encyclopedia); "what about tomorrow?" uses tomorrow's forecast.
    @Test fun weatherAndTomorrowUseTheForecast() {
        core.settings.set(Settings.HOME_LOCATION, "Chennai"); http.routes["api.open-meteo.com"] = HttpResponse(200, WEATHER)
        val today = say("What's the weather like today?").text
        assertTrue(today.startsWith("In Chennai it's 27°C and rainy") && "Today: 21 to 29°C, 80% chance of rain" in today, today)
        val tomorrow = say("What about tomorrow?").text
        assertTrue(tomorrow.startsWith("Tomorrow in Chennai: overcast, 22 to 31°C, 20% chance of rain"), tomorrow)
        assertEquals(1, meteo(), "the follow-up uses the forecast already fetched")
        // "Is there any update?" checks again (spoken, as in a voice conversation).
        val again = say("Is there any update?", voice = true).text
        assertEquals(2, meteo(), "an update is a new request: $again")
        assertTrue(again.startsWith("In Chennai it's"), again)
        assertEquals("", core.conversations.messages(conv).last { it.role == ai.clu.core.conversation.Role.USER }.meta.interpreted,
            "an ordinary word is never silently re-heard as the topic (\"update\" is not \"weather\")")
        val rain = say("Will it rain the day after tomorrow?", conversation = core.conversations.create("x").id).text
        assertTrue(rain.startsWith("The day after tomorrow in Chennai: clear, 23 to 32°C, 5% chance of rain"), rain)
        assertEquals(0, encyclopedia()); assertTrue(engine.prompts.isEmpty())
    }

    // 17 (weather): a weather-service failure is said plainly, never answered from the model or an encyclopedia.
    @Test fun aWeatherFailureIsAnHonestLimitation() {
        core.settings.set(Settings.HOME_LOCATION, "Chennai"); http.routes["api.open-meteo.com"] = HttpResponse(502, "Bad Gateway")
        val d = say("Will it rain tomorrow?")
        assertEquals("I couldn't get the weather for Chennai just now (the weather service returned HTTP 502), so I won't guess.", d.text)
        assertEquals("search_failed", d.meta.error); assertTrue(d.meta.sources.isEmpty()); assertTrue(engine.prompts.isEmpty())
    }

    // 18 (weather): Stop cancels the weather request and everything after it.
    @Test fun stopCancelsTheWeatherRequest(): Unit = runBlocking {
        core.settings.set(Settings.HOME_LOCATION, "Chennai"); http.routes["api.open-meteo.com"] = HttpResponse(200, WEATHER); http.delayMs = 3_000
        val job = async(Dispatchers.Default) { core.agent.respond(TurnRequest(conv, "What's the weather now?", ai.clu.core.security.Actor.ownerInApp())).toList() }
        delay(300); core.agent.cancel(null)
        val ev = withTimeout(2_000) { job.await() }
        assertTrue(ev.filterIsInstance<AgentEvent.Done>().single().meta.interrupted)
        assertEquals(1, http.requests.size); assertTrue(engine.prompts.isEmpty())
    }

    // 9: "search the web for …" researches the subject, not the command's words.
    @Test fun aSearchCommandResearchesTheSubjectNotTheFillerWords() {
        route("current CEO of Microsoft", ConversationContextTest.wiki("Satya Nadella" to "Satya Nadella is the chief executive officer of Microsoft, a role he has held since 2014."))
        engine.reply("Satya Nadella is the CEO of Microsoft.")
        for (cmd in listOf("Search the web for the current CEO of Microsoft.", "Can you look up who the current CEO of Microsoft is?",
                "Could you please find out the current CEO of Microsoft for me?")) {
            http.requests.clear(); engine.reply("Satya Nadella is the CEO of Microsoft.")
            say(cmd, conversation = core.conversations.create(cmd).id)
            assertEquals(listOf("current CEO of Microsoft"), queries(), cmd)
        }
    }

    // 10: a sentence with "and" in its subject is one request, not a plan.
    @Test fun aSubjectWithAndIsNotSplit() {
        for (s in listOf("Search for the history of Bosnia and Herzegovina", "Look up Marks and Spencer opening hours",
                "Tell me about Tom and Jerry", "Find out who won the match between India and Australia"))
            assertNull(TaskPlanner.plan(s), s)
        // A real chain still is one.
        assertEquals(listOf("Set a timer for 10 minutes", "Search for the weather in Pune"), TaskPlanner.plan("Set a timer for 10 minutes and then search for the weather in Pune"))
        route("history of Bosnia and Herzegovina", ConversationContextTest.wiki("History of Bosnia and Herzegovina" to "The history of Bosnia and Herzegovina spans many centuries."))
        engine.reply("The history of Bosnia and Herzegovina spans many centuries.")
        say("Search for the history of Bosnia and Herzegovina")
        assertEquals(listOf("history of Bosnia and Herzegovina"), queries())
    }

    // 1 (variant) + "is there any update?": a request for news on the open topic re-checks it.
    @Test fun anUpdateRequestRechecksTheTopic() {
        core.settings.set(Settings.HOME_LOCATION, "Chennai"); http.routes["gdacsapi"] = HttpResponse(200, ConversationContextTest.GDACS)
        say("Which cyclone is heading towards us?")
        assertEquals(1, gdacs())
        val u = say("Is there any update?").text
        assertEquals(2, gdacs(), "an update means checking again, not repeating the stored finding")
        assertTrue("DANA-24" in u, u)
        // With no topic, Clu asks what to check.
        assertEquals("What would you like me to look up?", say("Any updates?", conversation = core.conversations.create("y").id).text)
    }

    // 5: "he" with one person in play binds to him; with two, one short question.
    @Test fun pronounsBindOrAreClarified() {
        core.settings.set(Settings.SEARCH_AUTO, "explicit")
        engine.reply("Leman Russ is the Primarch of the Space Wolves.")
        say("Who is Leman Russ?")
        engine.reply("He led the Space Wolves legion.")
        say("What did he do?")
        assertTrue(engine.lastUserText.contains("What did he do?"), engine.lastUserText)
        assertTrue(engine.prompts.last().any { it.content.contains("Who is Leman Russ?") }, "the person in play is in the context")
        val other = core.conversations.create("two").id
        engine.reply("Roboute Guilliman leads the Ultramarines; Leman Russ the Space Wolves.")
        say("Tell me about Roboute Guilliman and Leman Russ", conversation = other)
        assertEquals("Do you mean Roboute Guilliman or Leman Russ?", say("What did he do?", conversation = other).text)
    }

    // 22: a question about the owner's memories is answered from the archive (no network); a current question is
    // researched, and the memory is never presented as the research result.
    @Test fun memoryRecallIsNotConfusedWithLiveResearch() {
        core.archive.saveMemory(Memory(category = MemoryCategory.EXPERIENCE, title = "Microsoft interview", content = "I had an interview at Microsoft in Hyderabad.",
            source = MemorySource.OWNER_SUPPLIED, eventDate = DateParser.parse("March 2019")!!))
        val recall = say("What did I tell you about Microsoft?").text
        assertTrue(recall.startsWith("Here's what you told me about Microsoft.") && "interview at Microsoft in Hyderabad" in recall, recall)
        assertTrue(http.requests.isEmpty(), "a memory question never goes to the web: ${http.requests}")
        route("current CEO of Microsoft", ConversationContextTest.wiki("Satya Nadella" to "Satya Nadella is the chief executive officer of Microsoft, a role he has held since 2014."))
        engine.reply("Satya Nadella is the CEO of Microsoft.")
        val live = say("Who is the current CEO of Microsoft?", conversation = core.conversations.create("z").id)
        assertEquals(listOf("current CEO of Microsoft"), queries())
        assertFalse("interview" in live.text || "you told me" in live.text, live.text)
        assertTrue(live.meta.sources.isNotEmpty() && live.meta.sources.none { "memory" in it.title.lowercase() })
        assertEquals(1, core.memories.count(), "neither turn saved anything new")
    }

    // 3 (people): a misheard name on a memory topic is re-heard against the person being discussed; the raw text is kept.
    @Test fun aMisheardNameIsReheardFromThePersonInPlay() {
        core.archive.people.save(ArchivePerson(name = "Riya", relation = "friend"))
        core.archive.saveMemory(Memory(category = MemoryCategory.EXPERIENCE, title = "Met Riya", content = "I met Riya at the fest in Pune.",
            source = MemorySource.OWNER_SUPPLIED, people = listOf("Riya"), eventDate = DateParser.parse("March 2018")!!))
        assertTrue(say("Do you remember Riya?", voice = true).text.startsWith("Yes, I remember Riya."))
        val d = say("Where did I meet Leah?", voice = true)
        val heard = core.conversations.messages(conv).last { it.role == ai.clu.core.conversation.Role.USER }
        assertEquals("Where did I meet Leah?", heard.content, "the raw transcript is kept")
        assertEquals("Do you mean Riya?", d.text, "a name is asked about, never silently changed")
        assertTrue(engine.prompts.isEmpty(), "nothing answered from a guess")
        // Confirming carries the corrected question on, with the memory about Riya in front of the model.
        engine.reply("You met Riya at the fest in Pune.")
        say("Yes", voice = true)
        assertEquals("Where did I meet Riya?", engine.lastUserText)
        assertTrue(engine.lastSystem.contains("I met Riya at the fest in Pune.") || engine.prompts.last().any { "I met Riya at the fest in Pune." in it.content })
        // A real, different person in the catalog is never "corrected".
        core.archive.people.save(ArchivePerson(name = "Leah", relation = "cousin"))
        assertNotEquals("Do you mean Riya?", say("Do you remember Leah?", voice = true).text)
    }

    // 23: conversation state is cleaned up when its conversation disappears by any path, and old state is pruned.
    @Test fun conversationStateIsCleanedUp() {
        core.settings.set(Settings.HOME_LOCATION, "Chennai"); http.routes["gdacsapi"] = HttpResponse(200, ConversationContextTest.GDACS)
        say("Which cyclone is heading towards us?")
        assertNotNull(core.store.meta("conv_state:$conv"))
        // A conversation deleted on the other device (sync) takes its context with it.
        val c = core.conversations.get(conv)!!
        core.conversations.applyRemoteConversation(c.copy(deleted = true, hlc = core.store.hlc.now(), origin = "tablet"))
        assertNull(core.store.meta("conv_state:$conv"), "removed when the conversation is deleted remotely")
        // Orphaned and long-idle state is pruned at start-up; an active conversation's state is kept.
        val active = core.conversations.create("active").id
        say("Which cyclone is heading towards us?", conversation = active)
        core.store.setMeta("conv_state:gone-1234", "{\"turn\":3}")
        val removed = ConversationStateStore(core.store).prune(core.conversations, core.clock.now())
        assertEquals(1, removed)
        assertNull(core.store.meta("conv_state:gone-1234")); assertNotNull(core.store.meta("conv_state:$active"))
    }
}
