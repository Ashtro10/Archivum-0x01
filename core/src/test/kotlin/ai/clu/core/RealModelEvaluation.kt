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
import kotlin.test.*

/**
 * The production agent with a real, trained GGUF model through llama.cpp (no stand-in): the same CluCore, LlmService,
 * prompt, agent controller and research provider the app runs. Only the network is a fixture (Wikipedia's JSON for the
 * queries Clu makes), because the build machine can't reach Wikipedia. Opt-in: -Dclu.realModel=<gguf>
 * [-Dclu.realModelOut=<json>]. Orchestration properties are asserted; answer quality is recorded, not asserted, so the
 * report shows what the model actually says.
 */
class RealModelEvaluation {
    private fun wiki(vararg hits: Pair<String, String>, suggestion: String? = null) = HttpResponse(200,
        """{"query":{"searchinfo":{"totalhits":${hits.size}${suggestion?.let { ",\"suggestion\":\"$it\"" } ?: ""}},"search":[""" +
            hits.joinToString(",") { (t, s) -> """{"title":"$t","snippet":"$s","timestamp":"2025-11-02T10:00:00Z"}""" } + "]}}")

    data class Case(val id: String, val text: String, val expectResearch: Boolean, val keywords: List<String>, val voice: Boolean = false,
                    val offline: Boolean = false, val searchFails: Boolean = false)

    @Test fun evaluate(): Unit = runBlocking {
        val path = System.getProperty("clu.realModel")
        assumeTrue(path != null && File(path).exists(), "no real model given")
        val platform = TestPlatform(); val http = platform.fakeHttp
        val core = newCore(platform = platform)
        val s = core.settings
        s.set(Settings.THREADS, 4); s.set(Settings.CONTEXT_LENGTH, 4096); s.set(Settings.MAX_TOKENS, 120); s.set(Settings.LOAD_ON_START, false)
        s.set(Settings.SEARCH_READ_PAGES, false)
        val model = core.models.register(File(path))
        s.set(Settings.MODEL_ACTIVE, model.id)
        val tLoad = System.nanoTime(); core.llm.loadIfNeeded() ?: error(core.llm.state.value.message); val loadMs = (System.nanoTime() - tLoad) / 1e6
        core.agent.warmUp()
        fun route(q: String, r: HttpResponse) { http.routes["srsearch=" + java.net.URLEncoder.encode(q, "UTF-8") + "&"] = r }
        val russ = "Leman Russ" to "Leman Russ is the Primarch of the Space Wolves, one of the Space Marine Legions in Warhammer 40,000. He leads the VI Legion."
        route("Leman Russ", wiki(russ)); route("lemon rush", wiki(suggestion = "leman russ")); route("leman russ", wiki(russ))
        route("leman russ legion lead", wiki("Space Wolves" to "Leman Russ leads the VI Legion, the Space Wolves, from their home world Fenris. They are fierce."))
        route("Tenzing Norgay", wiki("Tenzing Norgay" to "Tenzing Norgay was a Nepali-Indian Sherpa mountaineer who reached the summit of Mount Everest in 1953 with Edmund Hillary."))
        route("What is the latest Android version", wiki("Android 16" to "Android 16 is the latest stable version of Android, released in June 2025."))
        route("Mercury", wiki("Mercury (disambiguation)" to "Mercury may refer to several things.", "Mercury (planet)" to "Mercury is the smallest planet.",
            "Mercury (element)" to "Mercury is a chemical element.", "Freddie Mercury" to "Freddie Mercury was a singer."))
        val cases = listOf(
            Case("entity", "Who is Leman Russ?", true, listOf("primarch", "space wolves", "warhammer")),
            Case("misheard", "who is lemon rush", true, listOf("primarch", "space wolves", "leman"), voice = true),
            Case("follow-up", "Which legion did he lead?", true, listOf("vi legion", "sixth", "fenris")),
            Case("simple", "What is two plus two?", false, listOf("\\b4\\b", "\\bfour\\b")),
            Case("unfamiliar", "Who was Tenzing Norgay?", true, listOf("everest", "sherpa", "mountaineer")),
            Case("current", "What is the latest Android version?", true, listOf("android 16", "16")),
            Case("ambiguous", "Tell me about Mercury", true, listOf("do you mean")),
            Case("provider-down", "Who is Tenzing Norgay?", true, listOf(), searchFails = true),
            Case("offline", "Who is Leman Russ?", false, listOf(), offline = true),
        )
        val conv = core.conversations.create("eval").id
        val rows = mutableListOf<JsonObject>()
        for (c in cases) {
            http.requests.clear(); platform.online = !c.offline
            http.failWith = if (c.searchFails) java.io.IOException("connection refused") else null
            val t0 = System.nanoTime(); var firstText = -1.0
            val ev = core.agent.respond(TurnRequest(conv, c.text, ai.clu.core.security.Actor.ownerInApp(), viaVoice = c.voice))
                .onEach { if (it is AgentEvent.Text && firstText < 0) firstText = (System.nanoTime() - t0) / 1e6 }.toList()
            val totalMs = (System.nanoTime() - t0) / 1e6
            val done = ev.filterIsInstance<AgentEvent.Done>().single()
            val queries = http.requests.filter { "list=search" in it }.map { java.net.URLDecoder.decode(it.substringAfter("srsearch=").substringBefore("&"), "UTF-8") }
            val researched = ev.any { it is AgentEvent.ToolStarted && it.tool == "web.search" }
            // Orchestration: decided by Clu's code, so asserted.
            if (c.offline) assertTrue(http.requests.isEmpty(), "offline: nothing sent")
            else assertEquals(c.expectResearch, researched, "research decision for '${c.text}'")
            if (c.searchFails) { assertEquals("search_failed", done.meta.error); assertTrue(done.meta.sources.isEmpty()) }
            val answer = done.text
            val sourceTalk = Regex("(?i)wikipedia|https?://|\\[\\d]|according to").containsMatchIn(answer)
            assertFalse(sourceTalk, "no source talk reaches the owner: $answer")
            val st = done.meta.stats
            rows += buildJsonObject {
                put("case", c.id); put("question", c.text); put("researched", researched)
                put("queries", JsonArray(queries.map { JsonPrimitive(it) }))
                put("answer", answer)
                put("mentions_expected", c.keywords.isEmpty() || c.keywords.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(answer) })
                put("error", done.meta.error); put("grounding_fallback", (st["grounding_fallback"] ?: 0.0) > 0)
                put("streamed", ev.filterIsInstance<AgentEvent.Text>().joinToString("") { it.delta }.take(300))
                put("first_text_ms", "%.0f".format(firstText).toDouble()); put("total_ms", "%.0f".format(totalMs).toDouble())
                put("prompt_tokens", st["prompt_tokens"] ?: -1.0); put("new_prompt_tokens", (st["prompt_tokens"] ?: 0.0) - (st["reused_tokens"] ?: 0.0))
                put("generated_tokens", st["tokens"] ?: -1.0); put("tok_per_s", st["tok_per_s"] ?: -1.0)
            }
            println("eval: ${rows.last()}")
        }
        val info = core.llm.loadedSummary().orEmpty()
        core.shutdown()
        System.getProperty("clu.realModelOut")?.let { out ->
            File(out).apply { parentFile.mkdirs() }.writeText(buildJsonObject {
                put("model", File(path!!).name); put("load_ms", "%.0f".format(loadMs).toDouble())
                info.forEach { (k, v) -> put(k, v) }
                put("environment", "${System.getProperty("os.arch")} JVM, ${Runtime.getRuntime().availableProcessors()} vCPU (build machine, not a phone); network = Wikipedia JSON fixtures")
                put("cases", JsonArray(rows))
            }.toString())
        }
    }
}
