package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.net.JvmHttp
import ai.clu.core.tools.HttpFetcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Live research on real websites: CLU's own search providers and page reader over the real network, the real model,
 * the production agent. Nothing is a fixture. Recorded per question: the queries sent, the sources returned and which
 * pages were actually opened, whether the answer names a disagreement when the sources disagree, and the answer.
 * The last case is the whole chain: research, then a document written from what was found, then the archive is checked
 * for that document and its content.
 *
 * Opt-in, needs network access: -Dclu.liveResearch=<gguf> [-Dclu.liveResearchOut=<json>] [-Dclu.searchProvider=duckduckgo|wikipedia|brave]
 * (Brave also needs CLU_BRAVE_KEY in the environment). It is skipped, not failed, when Wikipedia is unreachable.
 */
class LiveResearchEvaluation {
    private class LivePlatform(val base: TestPlatform, override val http: HttpFetcher) : CluPlatform by base

    data class Case(val id: String, val text: String, val expect: List<String> = emptyList(), val disagreement: Boolean = false,
                    val document: String? = null)

    private val cases = listOf(
        Case("everest", "How tall is Mount Everest? Do the sources agree?", listOf("8,?84[89]|8,?850"), disagreement = true),
        Case("cricket", "Who won the 2023 Cricket World Cup final, and by what margin?", listOf("Australia", "wicket|run")),
        Case("kernel", "What is the latest stable version of the Linux kernel?", listOf("\\b[5-9]\\.\\d+")),
        Case("tenzing", "Tell me about Tenzing Norgay.", listOf("Everest", "1953")),
        Case("eiffel-doc", "Look up the history of the Eiffel Tower and save a short document about it.", listOf("1889|Eiffel"), document = "Eiffel"),
    )

    private fun reachable(http: HttpFetcher) = runCatching { runBlocking { http.get("https://en.wikipedia.org/w/api.php?action=query&meta=siteinfo&format=json", emptyMap()).code in 200..399 } }.getOrDefault(false)

    @Test fun evaluate(): Unit = runBlocking {
        val path = System.getProperty("clu.liveResearch")
        assumeTrue(path != null && File(path).exists(), "no real model given")
        val http = JvmHttp()
        assumeTrue(reachable(http), "no network access to Wikipedia from here")
        val base = TestPlatform()
        System.getenv("CLU_BRAVE_KEY")?.let { base.secrets[CluCore.BRAVE_SEARCH_KEY] = it }
        val core = CluCore(tempDb(), LivePlatform(base, http), ai.clu.core.util.ManualClock(System.currentTimeMillis()))
        core.reminderScheduler = ai.clu.core.butler.ReminderScheduler { }
        val s = core.settings
        s.set(Settings.THREADS, 4); s.set(Settings.CONTEXT_LENGTH, 4096); s.set(Settings.LOAD_ON_START, false); s.set(Settings.SEARCH_READ_PAGES, true)
        System.getProperty("clu.searchProvider")?.let { s.set(Settings.SEARCH_PROVIDER, it) }
        s.set(Settings.MODEL_ACTIVE, core.models.register(File(path)).id)
        core.llm.loadIfNeeded() ?: error(core.llm.state.value.message)
        val rows = mutableListOf<JsonObject>()
        for (c in cases) {
            val conv = core.conversations.create("live-research").id
            val docsBefore = core.archive.documents.list().map { it.id }.toSet()
            val t0 = System.nanoTime()
            val ev = core.agent.respond(TurnRequest(conv, c.text, ai.clu.core.security.Actor.ownerInApp())).toList()
            val ms = (System.nanoTime() - t0) / 1e6
            val done = ev.filterIsInstance<AgentEvent.Done>().last()
            val sources = done.meta.sources
            val goalSources = core.goalEngine.recent(conv).flatMap { g -> g.steps.mapNotNull { it.evidence.takeIf { e -> e.isNotBlank() } } }
            val newDocs = core.archive.documents.list().filter { it.id !in docsBefore }
            val docText = newDocs.firstOrNull()?.let { core.archive.documents.text(it.id) }.orEmpty()
            val reply = done.text
            val found = c.expect.filter { Regex("(?i)$it").containsMatchIn(reply + "\n" + docText) }
            val namesDisagreement = Regex("(?i)differ|disagree|vary|varies|whereas|while|however|other sources?|another source|some sources").containsMatchIn(reply)
            val docOk = c.document == null || (newDocs.size == 1 && docText.contains(c.document, true))
            val ok = found.size == c.expect.size && (!c.disagreement || namesDisagreement) && docOk && (sources.isNotEmpty() || goalSources.isNotEmpty())
            rows += buildJsonObject {
                put("case", c.id); put("text", c.text); put("ok", ok); put("total_ms", ms); put("reply", reply); put("error", done.meta.error ?: "")
                put("sources", JsonArray(sources.map { buildJsonObject { put("title", it.title); put("url", it.url); put("opened", it.opened); put("published", it.published) } }))
                put("pages_opened", sources.count { it.opened }); put("distinct_sites", sources.map { runCatching { java.net.URI(it.url).host }.getOrNull() }.distinct().size)
                put("goal_evidence", JsonArray(goalSources.map { JsonPrimitive(it.take(300)) }))
                put("expected_found", JsonArray(found.map { JsonPrimitive(it) })); put("names_disagreement", namesDisagreement)
                if (c.document != null) { put("document_saved", newDocs.size == 1); put("document", docText.take(2000)) }
            }
            println("live ${if (ok) "ok " else "BAD"} ${c.id} [${"%.0f".format(ms)} ms, ${sources.size} sources, ${sources.count { it.opened }} opened] ${reply.replace('\n', ' ').take(240)}")
        }
        val out = buildJsonObject {
            put("model", File(path).name); put("provider", s.string(Settings.SEARCH_PROVIDER)); put("at", System.currentTimeMillis())
            put("passed", "${rows.count { it["ok"]!!.jsonPrimitive.boolean }}/${rows.size}"); put("rows", JsonArray(rows))
        }
        System.getProperty("clu.liveResearchOut")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), out)) }
        core.shutdown()
    }
}
