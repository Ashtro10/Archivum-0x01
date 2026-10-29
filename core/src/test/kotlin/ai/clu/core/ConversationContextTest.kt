package ai.clu.core

import ai.clu.core.agent.*
import ai.clu.core.config.Settings
import ai.clu.core.conversation.Role
import ai.clu.core.tools.HttpResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * One coherent conversation: follow-ups resolved from explicit conversation state, speech re-interpreted from context,
 * current hazards from an official source, answers before bibliography. Deterministic: scripted model, recorded
 * provider responses (GDACS GeoJSON in its documented format, Wikipedia JSON); no network, no trained model.
 */
class ConversationContextTest {
    private val platform = TestPlatform()
    private val http get() = platform.fakeHttp
    private val core = newCore(platform = platform)
    private val engine = ScriptedEngine().also { core.useEngine(it) }
    private val conv = core.conversations.create("context").id

    companion object {
        /** Two active tropical cyclones (Bay of Bengal, western Pacific), as GDACS's event list API returns them. */
        val GDACS = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[86.4,15.2]},"properties":{"eventtype":"TC","eventid":1001,"name":"DANA-24",
             "description":"Tropical Cyclone DANA-24","alertlevel":"Orange","iscurrent":"true","country":"India, Bangladesh","source":"JTWC",
             "datemodified":"2026-10-29T06:00:00","severitydata":{"severity":120,"severitytext":"Tropical Cyclone (maximum wind speed of 120 km/h)","severityunit":"km/h"},
             "url":{"report":"https://www.gdacs.org/report.aspx?eventid=1001&eventtype=TC"}}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[132.0,18.5]},"properties":{"eventtype":"TC","eventid":1002,"name":"KONG-REY-24",
             "description":"Tropical Cyclone KONG-REY-24","alertlevel":"Green","iscurrent":"true","country":"Philippines","source":"JTWC",
             "datemodified":"2026-10-29T03:00:00","severitydata":{"severitytext":"Tropical Storm (maximum wind speed of 83 km/h)"}}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[-60.0,15.0]},"properties":{"eventtype":"TC","name":"OLD-24","description":"Tropical Cyclone OLD-24",
             "alertlevel":"Green","iscurrent":"false"}}]}"""

        fun wiki(vararg hits: Pair<String, String>) = HttpResponse(200, """{"query":{"searchinfo":{"totalhits":${hits.size}},"search":[""" +
            hits.joinToString(",") { (t, s) -> """{"title":"$t","snippet":"$s","timestamp":"2026-10-28T10:00:00Z"}""" } + "]}}")
    }

    private fun route(q: String, r: HttpResponse) { http.routes["srsearch=" + java.net.URLEncoder.encode(q, "UTF-8") + "&"] = r }
    private fun ask(text: String, voice: Boolean = false, c: CluCore = core, conversation: String = conv) = runBlocking {
        c.agent.respond(TurnRequest(conversation, text, ai.clu.core.security.Actor.ownerInApp(), viaVoice = voice)).toList()
    }
    private fun done(ev: List<AgentEvent>) = ev.filterIsInstance<AgentEvent.Done>().single()
    private fun queries() = http.requests.filter { "srsearch=" in it }.map { java.net.URLDecoder.decode(it.substringAfter("srsearch=").substringBefore("&"), "UTF-8") }
    private fun gdacsCalls() = http.requests.count { "gdacsapi" in it }
    private fun encyclopedia() = http.requests.count { "wikipedia" in it || "duckduckgo" in it }

    // 1. A question, then "search for it" / "look it up": the question is researched, never the words of the command.
    @Test fun aBareSearchCommandResearchesTheOpenQuestion() {
        core.settings.set(Settings.SEARCH_AUTO, "explicit") // Clu searches only when asked, as in the reported conversation
        route("current CEO of Microsoft", wiki("Satya Nadella" to "Satya Nadella is the chief executive officer of Microsoft, a role he has held since 2014."))
        engine.reply("I need to check current information.")
        ask("Who is the current CEO of Microsoft?")
        assertTrue(queries().isEmpty(), "explicit mode: nothing searched yet")
        engine.reply("Microsoft's CEO is Satya Nadella, who has led the company since 2014.")
        val d = done(ask("Look it up."))
        assertEquals(listOf("current CEO of Microsoft"), queries(), "the open question is researched, not 'look it up'")
        assertEquals("Microsoft's CEO is Satya Nadella, who has led the company since 2014.", d.text, "a correct answer in different words is kept (10)")
        assertTrue(engine.prompts.last().any { it.content.contains("Who is the current CEO of Microsoft?") }, "the model is asked the original question")
        // The other phrasings resolve the same way.
        for (cmd in listOf("Search for it.", "search the web and tell me", "Can you check online?", "find out")) {
            http.requests.clear(); engine.reply("Microsoft's CEO is Satya Nadella, who has led the company since 2014.")
            ask(cmd)
            assertEquals(listOf("current CEO of Microsoft"), queries(), cmd)
        }
    }

    // 2 + 9. The reported conversation: a cyclone question, "search the web and tell me", then direction and danger.
    @Test fun aCycloneQuestionIsAnsweredFromTheOfficialSourceAndFollowedUp() {
        core.settings.set(Settings.SEARCH_AUTO, "explicit"); core.settings.set(Settings.HOME_LOCATION, "Chennai")
        http.routes["gdacsapi"] = HttpResponse(200, GDACS)
        engine.reply("I can't reliably identify the current cyclone without checking live information.")
        ask("Which cyclone is heading towards us?")
        val d = done(ask("Search the web and tell me"))
        assertTrue(d.text.startsWith("The active tropical cyclone nearest to Chennai: Tropical Cyclone DANA-24 is about 700 km east-northeast of Chennai"), d.text)
        assertTrue("maximum wind speed of 120 km/h" in d.text && "GDACS alert level: Orange" in d.text && "India Meteorological Department" in d.text, d.text)
        assertTrue("doesn't include its direction of movement" in d.text, "what the data lacks is said, not invented")
        assertFalse("KONG-REY" in d.text, "the far-away cyclone isn't presented as heading towards us")
        assertFalse("OLD-24" in d.text, "finished events are left out")
        assertEquals(1, gdacsCalls()); assertEquals(0, encyclopedia(), "no encyclopedia search for a current emergency (9)")
        assertEquals(1, d.meta.sources.size, "one compact attribution, not a bibliography"); assertTrue(d.meta.sources[0].title.startsWith("GDACS"))
        val dir = done(ask("Which way is it moving?")).text
        assertTrue(dir.startsWith("The data I have gives its current position, not a forecast track") && "India Meteorological Department" in dir, dir)
        val danger = done(ask("Is it dangerous?")).text
        assertTrue(danger.startsWith("GDACS rates Tropical Cyclone DANA-24 Orange: a significant impact is possible"), danger)
        assertTrue(done(ask("When will it arrive?")).text.contains("can't say when or whether it will reach you without guessing"))
        assertEquals(1, gdacsCalls(), "follow-ups reuse what was found (no new request while it is fresh)")
        assertTrue(engine.prompts.size == 1, "no model call after the first turn: answers come from the data")
        assertEquals(0, core.memories.count(), "a temporary question never becomes a memory (17)")
    }

    // 3. A plausible mis-hearing on the second turn is re-interpreted from the conversation; the raw transcript is kept.
    @Test fun aMisheardWordIsReinterpretedFromTheConversation() {
        core.settings.set(Settings.HOME_LOCATION, "Chennai"); http.routes["gdacsapi"] = HttpResponse(200, GDACS)
        ask("Which cyclone is heading towards us?", voice = true)
        val d = done(ask("Is the convoy dangerous?", voice = true))
        assertTrue(d.text.startsWith("GDACS rates Tropical Cyclone DANA-24 Orange"), "answered about the cyclone, not a convoy: ${d.text}")
        val heard = core.conversations.messages(conv).last { it.role == Role.USER }
        assertEquals("Is the convoy dangerous?", heard.content, "the raw transcript is kept")
        assertEquals("Is the cyclone dangerous?", heard.meta.interpreted, "and the interpretation separately")
        // The same words typed are not re-interpreted (typing isn't misheard).
        assertEquals("", core.conversations.messages(conv).first { it.role == Role.USER }.meta.interpreted)
    }

    // 4 + 17. The topic survives a model reload and an app restart (same database, new process).
    @Test fun theTopicSurvivesAReloadAndARestart() {
        val db = tempDb()
        val c1 = newCore(db = db, platform = platform).also { it.useEngine(engine) }
        val conversation = c1.conversations.create("restart").id
        c1.settings.set(Settings.HOME_LOCATION, "Chennai"); http.routes["gdacsapi"] = HttpResponse(200, GDACS)
        c1.memories.save(ai.clu.core.memory.Memory(category = ai.clu.core.memory.MemoryCategory.MANUAL, title = "Bike", content = "Owner rides a blue bike.",
            source = ai.clu.core.memory.MemorySource.OWNER_SUPPLIED))
        ask("Which cyclone is heading towards us?", c = c1, conversation = conversation)
        runBlocking { c1.llm.reload() } // model reload: no effect on the conversation context
        c1.shutdown()
        val c2 = newCore(db = db, platform = platform).also { it.useEngine(engine) } // the app restarted
        assertEquals(1, c2.memories.count(), "memories survive"); assertEquals(2, c2.conversations.messages(conversation).size, "history survives")
        val d = done(ask("Is it dangerous?", c = c2, conversation = conversation))
        assertTrue(d.text.startsWith("GDACS rates Tropical Cyclone DANA-24 Orange"), d.text)
        // The stored history is what the model is given after the restart (not only kept in the database).
        ask("What should I keep ready at home?", c = c2, conversation = conversation)
        val prompt = engine.prompts.last().joinToString("\n") { it.content }
        assertTrue("Which cyclone is heading towards us?" in prompt && "GDACS rates Tropical Cyclone DANA-24" in prompt, prompt)
        // The context is temporary: deleting the conversation removes it too.
        assertNotNull(c2.store.meta("conv_state:$conversation"))
        c2.conversations.delete(conversation)
        assertNull(c2.store.meta("conv_state:$conversation"), "the topic is deleted with the conversation")
        c2.shutdown()
    }

    // 10. A correct answer in different words from the result is kept (not replaced by a quote).
    @Test fun aCorrectAnswerInOtherWordsIsKept() {
        route("current CEO of Microsoft", wiki("Satya Nadella" to "Satya Nadella is the chief executive officer of Microsoft, a role he has held since 2014."))
        engine.reply("Microsoft's CEO is Satya Nadella, who has led the company since 2014.")
        val d = done(ask("Who is the current CEO of Microsoft?"))
        assertEquals("Microsoft's CEO is Satya Nadella, who has led the company since 2014.", d.text)
        assertNull(d.meta.stats["answer_extractive"]); assertNull(d.meta.stats["answer_repairs"])
        // ...while the same light words around a name the result doesn't have are still caught.
        engine.reply("Bill Gates has led the company since 2014.")
        val wrong = done(ask("Who is the current CEO of Microsoft?", conversation = core.conversations.create("x").id))
        assertFalse("Bill Gates" in wrong.text, wrong.text)
    }

    // 5. A new, unrelated question does not inherit the previous topic.
    @Test fun anUnrelatedQuestionStartsANewTopic() {
        core.settings.set(Settings.HOME_LOCATION, "Chennai"); http.routes["gdacsapi"] = HttpResponse(200, GDACS)
        ask("Which cyclone is heading towards us?")
        engine.reply("Paris is the capital of France.")
        val d = done(ask("What is the capital of France?"))
        assertEquals("Paris is the capital of France.", d.text)
        assertEquals("What is the capital of France?", engine.lastUserText, "asked as it was, with nothing about the cyclone attached")
        engine.reply("It's home to about two million people.")
        ask("How many people live there?")
        assertEquals(1, gdacsCalls(), "the follow-up refers to Paris, not to the cyclone")
    }

    // 6. An ambiguous "it" gets one short question; the answer completes the request.
    @Test fun anAmbiguousFollowUpIsClarifiedOnce() {
        core.settings.set(Settings.SEARCH_AUTO, "explicit")
        engine.reply("Mercury is the smallest planet; Venus is the hottest.")
        ask("Tell me about Mercury and Venus")
        val c = done(ask("How big is it?"))
        assertEquals("Do you mean Mercury or Venus?", c.text)
        engine.reply("Venus is about 12,104 km across.")
        val d = done(ask("Venus"))
        assertEquals("Venus is about 12,104 km across.", d.text)
        assertEquals("How big is Venus?", engine.lastUserText, "the question is completed with the choice")
        engine.reply("Its surface is about 465°C.")
        assertNotEquals("Do you mean Mercury or Venus?", done(ask("How hot is it?")).text, "no repeated clarification once chosen")
    }

    // 7. Unrelated results (a website ranking, a webcomic) are not an answer.
    @Test fun irrelevantResultsAreRejected() {
        route("latest Android version", wiki("List of most-visited websites" to "This is a list of the most visited websites in the world.",
            "Xkcd" to "xkcd is a webcomic created by Randall Munroe."))
        route("Android", wiki()) // the retry with the query's key word finds nothing either
        engine.reply("The most visited website is Google.")
        val d = done(ask("What is the latest Android version?"))
        assertEquals(listOf("latest Android version", "Android"), queries(), "one bounded retry with a more precise query")
        assertEquals("search_empty", d.meta.error, "nothing relevant was found")
        assertTrue(d.meta.sources.isEmpty(), "unrelated results are never shown as sources")
        assertFalse("Google" in d.text || "xkcd" in d.text.lowercase(), d.text)
    }

    // 8. A failed search is never presented as research.
    @Test fun aFailedSearchIsNotPresentedAsResearch() {
        core.settings.set(Settings.HOME_LOCATION, "Chennai")
        http.routes["gdacsapi"] = HttpResponse(503, "Service Unavailable")
        val d = done(ask("Is a cyclone heading towards us?"))
        assertTrue(d.text.startsWith("I couldn't reach GDACS, the official disaster-alert service, just now"), d.text)
        assertEquals("search_failed", d.meta.error); assertTrue(d.meta.sources.isEmpty())
        // The open question stays open: "try again" after the failure retries it.
        http.routes["gdacsapi"] = HttpResponse(200, GDACS)
        assertTrue(done(ask("Search again")).text.contains("Tropical Cyclone DANA-24"))
    }

    // Location unknown: one focused question, not a guess (18); the answer completes the request.
    // 9 (location). A hazard "near us" with no known location: one question, and the answer completes the request.
    @Test fun anUnknownLocationIsAskedForOnce() {
        http.routes["gdacsapi"] = HttpResponse(200, GDACS)
        val c = done(ask("Is a cyclone heading towards us?"))
        assertTrue(c.text.startsWith("Which city or area should I check for tropical cyclones for?"), c.text)
        assertEquals(0, gdacsCalls(), "nothing fetched before the place is known")
        val d = done(ask("Kolkata"))
        assertTrue(d.text.startsWith("The active tropical cyclone nearest to Kolkata: Tropical Cyclone DANA-24"), d.text)
        assertEquals("", core.settings.string(Settings.HOME_LOCATION), "a place named in conversation isn't saved as a setting")
        assertTrue(done(ask("Is it dangerous?")).text.contains("Kolkata"), "the place stays for the rest of this conversation")
    }

    // 11. Answers come first: no URLs, citation numbers, site names or retrieval dates in what is shown or spoken.
    @Test fun answersCarryNoBibliography() {
        route("Leman Russ", wiki("Leman Russ" to "Leman Russ is the Primarch of the Space Wolves in Warhammer 40,000."))
        engine.reply("According to Wikipedia, Leman Russ is the Primarch of the Space Wolves [1]. See https://en.wikipedia.org/wiki/Leman_Russ")
        val d = done(ask("Who is Leman Russ?", voice = true))
        assertEquals("Leman Russ is the Primarch of the Space Wolves.", d.text)
        val ui = ai.clu.core.config.UiPolicy.sourcesSummary(d.meta.sources)
        assertEquals("Sources (1)", ui, "the source list is one collapsed line")
    }

    // 12. Cancelling stops the check and everything after it.
    @Test fun cancellingStopsFurtherWork(): Unit = runBlocking {
        core.settings.set(Settings.HOME_LOCATION, "Chennai"); http.routes["gdacsapi"] = HttpResponse(200, GDACS); http.delayMs = 3_000
        val job = async(Dispatchers.Default) { core.agent.respond(TurnRequest(conv, "Which cyclone is heading towards us?", ai.clu.core.security.Actor.ownerInApp())).toList() }
        delay(300)
        core.agent.cancel(null)
        val ev = withTimeout(2_000) { job.await() }
        assertTrue(done(ev).meta.interrupted, "reported as interrupted")
        assertTrue(engine.prompts.isEmpty(), "no model call after cancelling")
        assertEquals(1, http.requests.size, "no further request")
    }

    // 13. Voice and typed turns resolve the same way.
    @Test fun voiceAndTypingBehaveAlike() {
        core.settings.set(Settings.HOME_LOCATION, "Chennai"); http.routes["gdacsapi"] = HttpResponse(200, GDACS)
        val typed = listOf("Which cyclone is heading towards us?", "Is it dangerous?", "Where is it now?").map { done(ask(it)).text }
        val conv2 = core.conversations.create("voice").id
        val spoken = listOf("Which cyclone is heading towards us?", "Is it dangerous?", "Where is it now?").map { done(ask(it, voice = true, conversation = conv2)).text }
        assertEquals(typed, spoken)
    }

    // 15. Ordinary follow-ups keep the instructions identical (llama.cpp reuses the cached prompt).
    @Test fun followUpsKeepTheCachedPrompt() {
        core.settings.set(Settings.SEARCH_AUTO, "explicit")
        engine.reply("Photosynthesis turns light into chemical energy.", "Mostly in the leaves.", "It releases oxygen.")
        ask("What is photosynthesis?"); ask("Where does it happen?"); ask("What does it produce?")
        assertEquals(3, engine.prompts.size, "one model call per turn")
        assertEquals(1, engine.prompts.map { it.first().content }.distinct().size, "the system prompt never changed")
        // Each prompt extends the previous conversation: earlier turns are sent unchanged, in order.
        val second = engine.prompts[1].drop(1).dropLast(1).map { it.content }; val third = engine.prompts[2].drop(1).dropLast(1).map { it.content }
        assertEquals(second, third.take(second.size), "the history part of the prompt is a stable prefix")
    }

    // 16. Simple questions: no research, no planning, one model call.
    @Test fun simpleQuestionsTakeTheShortPath() {
        engine.reply("I'm well, thank you.", "Two plus two is four.", "Paris.")
        for (q in listOf("How are you?", "What is two plus two?", "What is the capital of France?")) ask(q)
        assertTrue(http.requests.isEmpty(), "no network request: ${http.requests}")
        assertEquals(3, engine.prompts.size, "exactly one model call each")
    }

    // 18. A consequential request is never silently re-interpreted: Clu asks.
    @Test fun aConsequentialMishearingIsAskedAboutNotGuessed() {
        core.settings.set(Settings.SEARCH_AUTO, "explicit")
        engine.reply("Leman Russ is the Primarch of the Space Wolves.")
        ask("Who is Leman Russ?", voice = true)
        val d = done(ask("Send lemon a message about it", voice = true))
        assertEquals("Do you mean Leman?", d.text)
        assertEquals(1, engine.prompts.size, "nothing was done before the answer")
    }
}
