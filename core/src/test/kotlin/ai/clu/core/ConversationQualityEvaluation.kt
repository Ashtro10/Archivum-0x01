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
 * Ordinary conversation, separate from goals: explanations, technical reasoning, follow-ups, long-form answers, honest
 * uncertainty, appropriate length, and comparison of sources (search results are a fixture: two Wikipedia hits that
 * disagree). Production path: CluCore, the agent, the real model through llama.cpp, production sampling settings.
 *
 * Checks are deliberately coarse (concepts a correct answer cannot avoid, word counts, an admission of not knowing);
 * every reply is saved so a person can read it. Orchestration is asserted only where it is CLU's job: a conversational
 * message must not start a goal or change the owner's lists, reminders or documents.
 *
 * Opt-in: -Dclu.convModel=<gguf> [-Dclu.convOut=<json>].
 */
class ConversationQualityEvaluation {
    data class Case(val id: String, val group: String, val text: String, val chain: String? = null, val concepts: List<String> = emptyList(),
                    val minConcepts: Int = concepts.size, val minWords: Int = 0, val maxWords: Int = Int.MAX_VALUE,
                    val uncertain: Boolean = false, val sources: List<Pair<String, String>> = emptyList(), val offline: Boolean = true)

    private val unsure = Regex("(?i)\\b(?:i don'?t know|i do not know|not sure|can'?t (?:know|predict|tell|say)|cannot (?:know|predict|tell|say)|no way to (?:know|predict)|" +
        "don'?t have (?:access|information|any information|that information|a record)|haven'?t (?:told|mentioned|shared)|didn'?t (?:tell|mention|share)|" +
        "unpredictable|impossible to (?:know|predict)|uncertain|no one can|nobody can)")

    private val cases = listOf(
        Case("fridge", "explanation", "Explain how a refrigerator keeps food cold.", concepts = listOf("refrigerant|coolant", "evaporat", "compress", "heat"), minConcepts = 3, minWords = 60),
        Case("tides", "explanation", "Why are there two high tides a day and not one?", concepts = listOf("moon", "gravit", "bulge|side|opposite"), minConcepts = 2, minWords = 40),
        Case("binary-search", "technical", "Why is binary search O(log n)? Walk me through the reasoning.", concepts = listOf("half|halv", "log", "sorted|compar|step"), minConcepts = 2, minWords = 40),
        Case("thread-process", "technical", "What's the difference between a process and a thread?", concepts = listOf("memory|address space", "shar", "lightweight|overhead|cheaper|faster|own"), minConcepts = 2, minWords = 30),
        Case("sql-index", "technical", "My SQL query got slow once the table reached a few million rows. What should I check first?", concepts = listOf("index", "explain|query plan|execution plan|scan"), minConcepts = 1, minWords = 30),
        Case("seasons-1", "follow-up", "What causes the seasons on Earth?", chain = "seasons", concepts = listOf("tilt|axis|23"), minWords = 25),
        Case("seasons-2", "follow-up", "And why are they reversed in the southern hemisphere?", chain = "seasons", concepts = listOf("hemisphere", "tilt|toward|away|axis"), minConcepts = 2, minWords = 15),
        Case("gil-1", "follow-up", "Tell me about the Python GIL.", chain = "gil", concepts = listOf("lock|interpreter", "thread"), minConcepts = 2, minWords = 30),
        Case("gil-2", "follow-up", "Does that affect multiprocessing?", chain = "gil", concepts = listOf("process", "separate|own|not affect|doesn'?t|no\\b|avoid|bypass|sidestep"), minConcepts = 2, minWords = 10),
        Case("breakfast", "uncertainty", "What did I have for breakfast yesterday?", uncertain = true, maxWords = 80),
        Case("sensex", "uncertainty", "What exactly will the Sensex close at next Friday?", uncertain = true, maxWords = 120),
        Case("arithmetic", "length", "What's 2 + 2?", concepts = listOf("\\b4\\b|four"), maxWords = 15),
        Case("thanks-hindi", "length", "How do I say thank you in Hindi?", concepts = listOf("dhanyavaad|dhanyawad|dhanyavad|shukriya|धन्यवाद|शुक्रिया"), maxWords = 40),
        Case("vaccines-long", "long-form", "Write a detailed explanation, in several paragraphs, of how vaccines train the immune system.",
            concepts = listOf("antigen|protein|weakened|inactivated|mrna", "antibod", "memory"), minConcepts = 2, minWords = 120),
        Case("everest-sources", "multi-source", "How tall is Mount Everest? Do the sources agree?", offline = false,
            sources = listOf("Mount Everest" to "Mount Everest is Earth's highest mountain above sea level, with an elevation of 8,848.86 m, measured by China and Nepal in 2020.",
                "Everest height survey" to "The 1955 Indian survey gave the height of Mount Everest as 8,848 m; a 1999 American survey measured 8,850 m."),
            concepts = listOf("8,?848|8,?850", "differ|disagree|vary|varies|slightly|while|whereas|but|however|survey|measure"), minConcepts = 2),
    )

    private fun wiki(hits: List<Pair<String, String>>) = HttpResponse(200, """{"query":{"searchinfo":{"totalhits":${hits.size}},"search":[""" +
        hits.joinToString(",") { (t, s) -> """{"title":"$t","snippet":"$s","timestamp":"2025-11-02T10:00:00Z"}""" } + "]}}")

    @Test fun evaluate(): Unit = runBlocking {
        val path = System.getProperty("clu.convModel")
        assumeTrue(path != null && File(path).exists(), "no real model given")
        val platform = TestPlatform(); val http = platform.fakeHttp
        val core = newCore(platform = platform)
        core.reminderScheduler = ai.clu.core.butler.ReminderScheduler { }
        val s = core.settings
        s.set(Settings.THREADS, 4); s.set(Settings.CONTEXT_LENGTH, 4096); s.set(Settings.LOAD_ON_START, false); s.set(Settings.SEARCH_READ_PAGES, false)
        s.set(Settings.MODEL_ACTIVE, core.models.register(File(path)).id)
        val rec = RealModelEvaluation.Recorder(core.llm.loadIfNeeded() ?: error(core.llm.state.value.message)); core.useEngine(rec)
        core.agent.warmUp()
        val only = System.getProperty("clu.convOnly")?.split(',')?.toSet()
        val chains = mutableMapOf<String, String>()
        val rows = mutableListOf<JsonObject>()
        var hijacks = 0
        for (c in cases.filter { only == null || it.id in only || it.chain in only }) {
            rec.outputs.clear(); val logFrom = core.diag.recent(1).firstOrNull()?.ts ?: 0L
            platform.online = !c.offline; http.routes.clear(); http.requests.clear()
            if (c.sources.isNotEmpty()) http.routes["srsearch="] = wiki(c.sources)
            val conv = c.chain?.let { k -> chains.getOrPut(k) { core.conversations.create("conv-eval").id } } ?: core.conversations.create("conv-eval").id
            val itemsBefore = core.butler.open().size; val docsBefore = core.archive.documents.list().size
            core.goalEngine.last = null
            val t0 = System.nanoTime(); var first = -1.0
            val ev = core.agent.respond(TurnRequest(conv, c.text, ai.clu.core.security.Actor.ownerInApp()))
                .onEach { if (it is AgentEvent.Text && first < 0) first = (System.nanoTime() - t0) / 1e6 }.toList()
            val ms = (System.nanoTime() - t0) / 1e6
            val done = ev.filterIsInstance<AgentEvent.Done>().last()
            val reply = done.text
            val words = Regex("[\\p{L}\\p{N}'’,.-]+").findAll(reply).count()
            val hits = c.concepts.filter { Regex("(?i)$it").containsMatchIn(reply) }
            val tools = ev.filterIsInstance<AgentEvent.ToolStarted>().map { it.tool }
            // A goal is an action plan; a read-only lookup (calculator, search, memory) is part of answering.
            val goalStarted = core.goalEngine.recent(conv).any { g -> g.steps.any { !it.readOnly } }
            val changed = core.butler.open().size != itemsBefore || core.archive.documents.list().size != docsBefore
            if (goalStarted || changed) hijacks++
            val failed = listOfNotNull(
                "concepts ${hits.size}/${c.concepts.size}".takeIf { hits.size < c.minConcepts },
                "too short ($words words)".takeIf { words < c.minWords },
                "too long ($words words)".takeIf { words > c.maxWords },
                "no admission of uncertainty".takeIf { c.uncertain && !unsure.containsMatchIn(reply) },
                "started a goal or tool".takeIf { goalStarted }, "changed owner data".takeIf { changed })
            val st = done.meta.stats
            rows += buildJsonObject {
                put("case", c.id); put("group", c.group); put("text", c.text); put("ok", failed.isEmpty()); put("failed", JsonArray(failed.map { JsonPrimitive(it) }))
                put("words", words); put("concepts_found", JsonArray(hits.map { JsonPrimitive(it) })); put("kind", core.goalEngine.last?.kind?.name ?: "none")
                put("first_text_ms", first); put("total_ms", ms); put("generated_tokens", st["tokens"] ?: -1.0); put("stop", done.meta.error ?: "")
                put("searched", http.requests.isNotEmpty()); put("tools", JsonArray(tools.map { JsonPrimitive(it) })); put("sources", done.meta.sources.size); put("reply", reply)
                put("model_outputs", JsonArray(rec.outputs.map { JsonPrimitive(it) }))
                put("log", JsonArray(core.diag.recent(200).filter { it.ts >= logFrom && it.tag in setOf("agent", "goal", "research") }.reversed().map { JsonPrimitive("${it.tag}: ${it.message}") }))
            }
            println("conv ${if (failed.isEmpty()) "ok " else "BAD"} ${c.id} [$words w, ${"%.0f".format(ms)} ms] $failed :: ${reply.replace('\n', ' ').take(240)}")
        }
        val byGroup = rows.groupBy { it["group"]!!.jsonPrimitive.content }.mapValues { (_, r) -> "${r.count { it["ok"]!!.jsonPrimitive.boolean }}/${r.size}" }
        val summary = buildJsonObject {
            put("model", File(path).name); put("model_info", JsonObject(core.llm.loadedSummary().orEmpty().mapValues { JsonPrimitive(it.value) }))
            put("passed", "${rows.count { it["ok"]!!.jsonPrimitive.boolean }}/${rows.size}"); put("by_group", JsonObject(byGroup.mapValues { JsonPrimitive(it.value) }))
            put("goal_hijacks", hijacks); put("note", "Coarse automated checks; read the replies. Search results for the multi-source case are a fixture, not live web.")
            put("rows", JsonArray(rows))
        }
        println("conv summary: ${summary["passed"]} ${summary["by_group"]} hijacks=$hijacks")
        System.getProperty("clu.convOut")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), summary)) }
        core.shutdown()
        // CLU's own responsibility: conversation never turns into an action.
        kotlin.test.assertEquals(0, hijacks, "conversational messages started goals or changed data")
    }
}
