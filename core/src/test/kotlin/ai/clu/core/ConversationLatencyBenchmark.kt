package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.tools.HttpResponse
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Stage 4 latency: the reported conversations (a question, then "search the web and tell me", "look it up", "tell me
 * more", "is it dangerous?") through the production path with a real GGUF loaded by llama.cpp, timed per turn with the
 * monotonic clock. Only the network is a fixture (the same responses for every build), so the same file run on two
 * commits gives comparable numbers: total time, time to first text, model calls, prompt tokens and network requests.
 *
 * Opt-in: -Dclu.followupBench=<gguf> [-Dclu.followupBenchOut=<json>] [-Dclu.followupBenchRuns=3]. Written against API
 * that exists in the previous release as well (1.6.2 for the first three chains; 1.8.1 for the 1.8.2 chains: two
 * cyclones, weather with "tomorrow" and "any update?", "search the web for …", and a memory question).
 */
class ConversationLatencyBenchmark {
    private fun wiki(vararg hits: Pair<String, String>) = HttpResponse(200, """{"query":{"searchinfo":{"totalhits":${hits.size}},"search":[""" +
        hits.joinToString(",") { (t, s) -> """{"title":"$t","snippet":"$s","timestamp":"2026-10-28T10:00:00Z"}""" } + "]}}")

    private val gdacs = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[86.4,15.2]},
        "properties":{"eventtype":"TC","name":"DANA-24","description":"Tropical Cyclone DANA-24","alertlevel":"Orange","iscurrent":"true",
        "country":"India, Bangladesh","source":"JTWC","datemodified":"2026-10-28T09:00:00","url":{"report":"https://www.gdacs.org/report.aspx?eventid=1"},
        "severitydata":{"severitytext":"Tropical cyclone (maximum wind speed of 120 km/h)"}}}]}"""

    /** 1.8.2: two cyclones near Chennai (one in each sea). */
    private val gdacsTwo = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[86.4,15.2]},"properties":{"eventtype":"TC","eventid":1001,"name":"DANA-24",
             "description":"Tropical Cyclone DANA-24","alertlevel":"Orange","iscurrent":"true","country":"India, Bangladesh","source":"JTWC",
             "datemodified":"2026-10-29T06:00:00","severitydata":{"severitytext":"Tropical Cyclone (maximum wind speed of 120 km/h)"}}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[72.0,12.0]},"properties":{"eventtype":"TC","eventid":1003,"name":"VAYU-24",
             "description":"Tropical Cyclone VAYU-24","alertlevel":"Green","iscurrent":"true","country":"India","source":"JTWC",
             "datemodified":"2026-10-29T05:00:00","severitydata":{"severitytext":"Tropical Storm (maximum wind speed of 75 km/h)"}}}]}"""
    private val weather = """{"current":{"time":"2026-10-29T15:00","temperature_2m":27.4,"relative_humidity_2m":70,"weather_code":61,"wind_speed_10m":12.0},
            "daily":{"time":["2026-10-29","2026-10-30","2026-10-31"],"weather_code":[61,3,0],"temperature_2m_max":[29.0,31.0,32.0],
            "temperature_2m_min":[21.0,22.0,23.0],"precipitation_probability_max":[80,20,5]}}"""

    private val chains = listOf(
        "cyclone" to listOf("Which cyclone is heading towards us?" to true, "Search the web and tell me" to true, "Is it dangerous?" to true,
            "When will it arrive?" to true),
        "ceo" to listOf("Who is the current CEO of Microsoft?" to false, "Look it up." to false, "Tell me more" to false),
        "entity" to listOf("Who is Leman Russ?" to false, "Which legion does he lead?" to false, "Tell me more" to false),
        "two-cyclones" to listOf("Which cyclone is approaching?" to true, "Is it heading towards us?" to true, "VAYU" to true, "Is it dangerous?" to true),
        "weather" to listOf("What's the weather like today?" to true, "What about tomorrow?" to true, "Is there any update?" to true),
        "search-command" to listOf("Search the web for the current CEO of Microsoft." to false),
        "memory" to listOf("What did I tell you about Microsoft?" to true, "Who is the current CEO of Microsoft?" to true),
    )

    @Test fun measure(): Unit = runBlocking {
        val path = System.getProperty("clu.followupBench")
        assumeTrue(path != null && File(path).exists(), "no model given")
        val runs = System.getProperty("clu.followupBenchRuns", "3").toInt()
        val platform = TestPlatform(); val http = platform.fakeHttp
        val core = newCore(platform = platform)
        val s = core.settings
        s.set(Settings.THREADS, 4); s.set(Settings.CONTEXT_LENGTH, 4096); s.set(Settings.MAX_TOKENS, 120); s.set(Settings.LOAD_ON_START, false)
        s.set(Settings.SEARCH_READ_PAGES, false); s.set(Settings.TEMPERATURE, 0.0)
        Settings["identity.homeLocation"]?.let { s.set(it.key, "Chennai") } // 1.7: the owner's saved location
        s.set(Settings.MODEL_ACTIVE, core.models.register(File(path!!)).id)
        val engine = core.llm.loadIfNeeded() ?: error(core.llm.state.value.message)
        val rec = RealModelEvaluation.Recorder(engine); val timed = Timed(rec); core.useEngine(timed)
        core.agent.warmUp()

        // The same responses for every build: whatever a build searches for, it gets the same answer to the same query.
        http.routes["srsearch="] = wiki() // anything not listed: nothing found
        http.routes["api.duckduckgo.com"] = HttpResponse(200, "{}")
        http.routes["gdacsapi"] = HttpResponse(200, gdacs)
        http.routes["api.open-meteo.com"] = HttpResponse(200, weather)
        core.memories.save(ai.clu.core.memory.Memory(category = ai.clu.core.memory.MemoryCategory.EXPERIENCE, title = "Microsoft interview",
            content = "I had an interview at Microsoft in Hyderabad.", source = ai.clu.core.memory.MemorySource.OWNER_SUPPLIED))
        val ceo = "Satya Nadella" to "Satya Nadella is the chief executive officer of Microsoft, a role he has held since 2014."
        val russ = "Leman Russ" to "Leman Russ is the Primarch of the Space Wolves, one of the Space Marine Legions in Warhammer 40,000. He leads the VI Legion."
        for (q in listOf("current CEO of Microsoft", "Who is the current CEO of Microsoft", "the current CEO of Microsoft", "CEO of Microsoft", "Microsoft"))
            http.routes["srsearch=" + java.net.URLEncoder.encode(q, "UTF-8") + "&"] = wiki(ceo)
        for (q in listOf("Leman Russ", "Leman Russ legion", "Leman Russ legion lead", "Leman Russ legion does lead"))
            http.routes["srsearch=" + java.net.URLEncoder.encode(q, "UTF-8") + "&"] = wiki(russ)

        val rows = mutableListOf<JsonObject>()
        for (run in 1..runs) for ((chain, turns) in chains) {
            val conv = core.conversations.create("bench").id
            http.routes["gdacsapi"] = HttpResponse(200, if (chain == "two-cyclones") gdacsTwo else gdacs)
            for ((i, t) in turns.withIndex()) {
                val (text, voice) = t
                http.requests.clear(); rec.outputs.clear(); timed.ms = 0.0
                val t0 = System.nanoTime(); var first = -1.0
                val ev = core.agent.respond(TurnRequest(conv, text, ai.clu.core.security.Actor.ownerInApp(), viaVoice = voice))
                    .onEach { if (it is AgentEvent.Text && first < 0) first = (System.nanoTime() - t0) / 1e6 }.toList()
                val total = (System.nanoTime() - t0) / 1e6
                val done = ev.filterIsInstance<AgentEvent.Done>().single()
                rows += buildJsonObject {
                    put("run", run); put("chain", chain); put("turn", i + 1); put("text", text)
                    put("total_ms", total); put("first_text_ms", first); put("model_ms", timed.ms); put("outside_model_ms", total - timed.ms)
                    put("model_calls", rec.outputs.size); put("prompt_tokens_last_call", done.meta.stats["prompt_tokens"] ?: 0.0)
                    put("network_requests", http.requests.size)
                    put("searches", JsonArray(http.requests.filter { "srsearch=" in it }.map {
                        JsonPrimitive(java.net.URLDecoder.decode(it.substringAfter("srsearch=").substringBefore("&"), "UTF-8")) }))
                    put("answer", done.text.take(240))
                }
            }
        }
        val out = buildJsonObject { put("model", File(path).name); put("runs", runs); put("rows", JsonArray(rows)) }
        System.getProperty("clu.followupBenchOut")?.let { File(it).writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), out)) }
        println(out)
        assertTrue(rows.isNotEmpty())
    }

    private fun assertTrue(b: Boolean) = kotlin.test.assertTrue(b)

    /** Time spent inside the model's generate calls, so the rest of a turn (controller, checks, storage) is measured apart. */
    class Timed(private val e: ai.clu.core.llm.LlmEngine) : ai.clu.core.llm.LlmEngine {
        @Volatile var ms = 0.0
        override val id get() = e.id
        override val source get() = e.source
        override fun isReady() = e.isReady()
        override fun countTokens(messages: List<ai.clu.core.llm.ChatMessage>) = e.countTokens(messages)
        override fun contextSize() = e.contextSize()
        override fun cancel() = e.cancel()
        override fun generate(messages: List<ai.clu.core.llm.ChatMessage>, params: ai.clu.core.llm.GenParams, onText: (String) -> Unit): ai.clu.core.llm.GenResult {
            val t = System.nanoTime(); try { return e.generate(messages, params, onText) } finally { ms += (System.nanoTime() - t) / 1e6 }
        }
        override fun generate(messages: List<ai.clu.core.llm.ChatMessage>, params: ai.clu.core.llm.GenParams, signal: ai.clu.core.llm.CancelSignal,
                              onText: (String) -> Unit): ai.clu.core.llm.GenResult {
            val t = System.nanoTime(); try { return e.generate(messages, params, signal, onText) } finally { ms += (System.nanoTime() - t) / 1e6 }
        }
    }
}
