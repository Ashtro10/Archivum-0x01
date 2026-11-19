package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.GoalStepStatus
import ai.clu.core.agent.MessageKind
import ai.clu.core.agent.TurnRequest
import ai.clu.core.butler.ReminderScheduler
import ai.clu.core.config.Settings
import ai.clu.core.llm.*
import ai.clu.core.security.Sensitivity
import ai.clu.core.tools.Tool
import ai.clu.core.tools.ToolContext
import ai.clu.core.tools.ToolResult
import ai.clu.core.tools.str
import ai.clu.core.util.CluJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Intent-driven understanding and execution with a real model through the production path (CluCore, InferenceService,
 * llama.cpp grammar, GoalEngine, the real reminder, list, date and document tools, the agent's research path). Web search
 * is a topic fixture (the build machine's network policy blocks search providers). The messages are paraphrases that do
 * not appear in the planner's examples.
 *
 * **Stage-by-stage oracle substitution.** Every case has gold answers for each model stage. In mode `none` the model
 * answers everything; in `understand` the gold answers replace the model's understanding (kind, missing facts, tools),
 * tool choice and intent check; in `full` they also replace argument filling and fact extraction. A case that passes only
 * with the oracle failed at the substituted stage; a case that fails even in `full` failed in CLU's own orchestration
 * (execution, verification, routing or response), which no model can fix.
 *
 * Opt-in: -Dclu.goalModel=<gguf> [-Dclu.goalOut=<json>] [-Dclu.goalRuns=<n>] [-Dclu.goalPlanner=stepwise|plan]
 * [-Dclu.goalOracle=none,understand,full]. Effects are checked in the stores, never in the reply's claims.
 */
class GoalEvaluation {
    /** Gold answers for one case: what a perfect model would say at each stage. */
    data class Gold(val kind: String, val lookups: List<String> = emptyList(), val tools: List<String> = emptyList(),
                    val fills: Map<String, String> = emptyMap(), val extracts: List<String> = emptyList())

    data class Case(val id: String, val group: String, val text: String, val expectKinds: Set<MessageKind>, val gold: Gold,
                    val due: LocalDateTime? = null, val titleHas: String? = null, val items: List<String> = emptyList(),
                    val dueBy: LocalDateTime? = null, val dueOn: LocalDate? = null, val prior: List<String> = emptyList(),
                    val searched: Boolean? = null, val replyHas: Regex? = null, val docHas: String? = null) {
        val changes get() = due != null || dueBy != null || dueOn != null || items.isNotEmpty() || docHas != null
    }

    private val I = setOf(MessageKind.INSTRUCTION); private val Q = setOf(MessageKind.QUESTION)
    private val NOACT = setOf(MessageKind.THINKING_ALOUD, MessageKind.STATEMENT, MessageKind.CHAT)
    private fun g(kind: String, lookups: List<String> = emptyList(), tools: List<String> = emptyList(), extracts: List<String> = emptyList(), vararg fills: Pair<String, String>) =
        Gold(kind, lookups, tools, fills.toMap(), extracts)
    private val diwaliDate = "date of Diwali 2025"

    // The clock: Thursday 9 October 2025, 10:00 local time (Diwali 2025 is on Monday 20 October).
    private val zone = ZoneId.systemDefault()
    private val start = LocalDateTime.of(2025, 10, 9, 10, 0)
    private val cases = listOf(
        // Phase 1 / 2 cases.
        Case("diwali", "lookup+act", "Diwali's coming up, look up the date and remind me to buy diyas three days before it", I,
            g("instruction", listOf(diwaliDate), listOf("reminder.set"), listOf("20 October 2025"), "reminder.set" to """{"what":"buy diyas","when":"3 days before 20 October 2025"}"""),
            LocalDateTime.of(2025, 10, 17, 9, 0), "diya"),
        Case("diwali-clean", "lookup+act", "Can you check when Diwali falls this year and set a reminder two days before to clean the house?", I,
            g("instruction", listOf(diwaliDate), listOf("reminder.set"), listOf("20 October 2025"), "reminder.set" to """{"what":"clean the house","when":"2 days before 20 October 2025"}"""),
            LocalDateTime.of(2025, 10, 18, 9, 0), "clean"),
        Case("rahul", "act", "I have a meeting with Rahul tomorrow at 3 pm, remind me an hour before it", I,
            g("instruction", tools = listOf("reminder.set"), fills = arrayOf("reminder.set" to """{"what":"meeting with Rahul","when":"an hour before tomorrow at 3 pm"}""")),
            LocalDateTime.of(2025, 10, 10, 14, 0), "rahul"),
        Case("assignment", "act", "My assignment is due on 25 October. Remind me two days before so I can finish it", I,
            g("instruction", tools = listOf("reminder.set"), fills = arrayOf("reminder.set" to """{"what":"finish my assignment","when":"two days before 25 October"}""")),
            LocalDateTime.of(2025, 10, 23, 9, 0), "assignment"),
        Case("call-mom", "act", "Remind me to call mom at 7 pm today", I,
            g("instruction", tools = listOf("reminder.set"), fills = arrayOf("reminder.set" to """{"what":"call mom","when":"today at 7 pm"}""")),
            LocalDateTime.of(2025, 10, 9, 19, 0), "mom"),
        Case("shopping", "act", "Add milk and eggs to my shopping list", I,
            g("instruction", tools = listOf("task.add"), fills = arrayOf("task.add" to """{"title":"milk and eggs","list":"shopping"}""")), items = listOf("milk", "egg")),
        Case("musing-dp", "no-action", "I've been thinking about whether dynamic programming is overkill for this scheduling problem", NOACT, g("thinking_aloud")),
        Case("musing-reminders", "no-action", "Maybe I should start using reminders more often, I keep forgetting things", NOACT, g("thinking_aloud")),
        Case("musing-diwali", "no-action", "I was wondering if I'll have time to visit my parents for Diwali this year", NOACT, g("thinking_aloud")),
        Case("question-capital", "question", "What is the capital of Australia?", Q, g("question"), replyHas = Regex("(?i)canberra")),
        Case("question-diwali", "research", "When is Diwali this year?", Q, g("question", listOf(diwaliDate)), searched = true, replyHas = Regex("(?i)20 october|october 20")),
        Case("chat", "no-action", "Good morning! How's it going?", NOACT, g("chat")),
        Case("t1-busy-thursday", "acceptance", "I'm going to be busy next Thursday. Make sure I don't forget my assignment submission.", I,
            g("instruction", tools = listOf("reminder.set"), fills = arrayOf("reminder.set" to """{"what":"submit my assignment","when":"Wednesday at 6 pm"}""")),
            titleHas = "assign", dueBy = LocalDateTime.of(2025, 10, 16, 23, 59)),
        Case("t5-exam", "acceptance", "Find out when my next examination is and make sure I have enough time to prepare.", I,
            g("instruction", listOf("date of my next examination"), listOf("reminder.set"), listOf("28 October 2025"),
                "reminder.set" to """{"what":"start preparing for the examination","when":"a week before 28 October 2025"}"""),
            dueBy = LocalDateTime.of(2025, 10, 27, 23, 59)),
        Case("para-vitamins", "paraphrase", "Can you nudge me to take my vitamins at 9 tonight?", I,
            g("instruction", tools = listOf("reminder.set"), fills = arrayOf("reminder.set" to """{"what":"take my vitamins","when":"today at 9 pm"}""")),
            LocalDateTime.of(2025, 10, 9, 21, 0), "vitamin"),
        Case("para-bill", "paraphrase", "Don't let me forget the electricity bill on Monday", I,
            g("instruction", tools = listOf("reminder.set"), fills = arrayOf("reminder.set" to """{"what":"pay the electricity bill","when":"Monday"}""")),
            dueOn = LocalDate.of(2025, 10, 13), titleHas = "bill"),
        Case("para-groceries", "paraphrase", "we're out of coffee, stick it on the shopping list", I,
            g("instruction", tools = listOf("task.add"), fills = arrayOf("task.add" to """{"title":"coffee","list":"shopping"}""")), items = listOf("coffee")),
        Case("indirect-birthday", "indirect", "My sister's birthday is on 2 November and I always forget to call her", I,
            g("instruction", tools = listOf("reminder.set"), fills = arrayOf("reminder.set" to """{"what":"call my sister for her birthday","when":"2 November"}""")),
            dueBy = LocalDateTime.of(2025, 11, 2, 23, 59), titleHas = "sister"),
        Case("context-dentist", "context", "Remind me the day before.", I,
            g("instruction", tools = listOf("reminder.set"), fills = arrayOf("reminder.set" to """{"what":"dentist appointment","when":"the day before 30 October"}""")),
            dueOn = LocalDate.of(2025, 10, 29), prior = listOf("My dentist appointment is on 30 October at 11 am.")),
        Case("research-worldcup", "research", "Who won the last men's cricket world cup, and who did they beat?", Q,
            g("question", listOf("winner of the last men's cricket world cup")), searched = true, replyHas = Regex("(?i)australia")),
        Case("multi-tool", "multi-tool", "Add eggs to the shopping list and remind me to buy them tomorrow at 6 pm", I,
            g("instruction", tools = listOf("task.add", "reminder.set"), fills = arrayOf("task.add" to """{"title":"eggs","list":"shopping"}""",
                "reminder.set" to """{"what":"buy eggs","when":"tomorrow at 6 pm"}""")),
            items = listOf("egg"), due = LocalDateTime.of(2025, 10, 10, 18, 0)),
        Case("musing-gym", "no-action", "I wonder if joining a gym near the office would actually make me go more often", NOACT, g("thinking_aloud")),
        // Phase 3 additions.
        Case("stmt-commitment", "statement", "I'll pay the electricity bill on Monday.", NOACT, g("statement")),
        Case("stmt-hypothetical", "statement", "If I had a free weekend I'd probably drive down to Coorg.", NOACT, g("statement")),
        Case("multi-dependent", "multi-tool", "Look up when Diwali is, put diyas on my shopping list and remind me to buy them three days before.", I,
            g("instruction", listOf(diwaliDate), listOf("task.add", "reminder.set"), listOf("20 October 2025"),
                "task.add" to """{"title":"diyas","list":"shopping"}""", "reminder.set" to """{"what":"buy diyas","when":"3 days before 20 October 2025"}"""),
            items = listOf("diya"), due = LocalDateTime.of(2025, 10, 17, 9, 0)),
        Case("doc-research", "document", "Put together a short write-up on Diwali from what you can find online and save it.", I,
            g("instruction", listOf("Diwali 2025"), listOf("document.write"), listOf("20 October 2025"), "document.write" to """{"title":"Diwali","content":""}"""),
            docHas = "October"),
        Case("indirect-insurance", "indirect", "My car insurance runs out on 5 November and I've let it lapse before.", I,
            g("instruction", tools = listOf("reminder.set"), fills = arrayOf("reminder.set" to """{"what":"renew my car insurance","when":"5 November"}""")),
            dueBy = LocalDateTime.of(2025, 11, 5, 23, 59), titleHas = "insurance"),
        Case("context-list", "context", "Put those on the shopping list.", I,
            g("instruction", tools = listOf("task.add"), fills = arrayOf("task.add" to """{"title":"milk, bread and coffee","list":"shopping"}""")),
            items = listOf("milk", "bread", "coffee"), prior = listOf("We need milk, bread and coffee for the weekend.")),
        Case("para-water", "paraphrase", "ping me at 6:30 this evening to water the plants", I,
            g("instruction", tools = listOf("reminder.set"), fills = arrayOf("reminder.set" to """{"what":"water the plants","when":"today at 6:30 pm"}""")),
            LocalDateTime.of(2025, 10, 9, 18, 30), "water"),
        Case("question-moon", "question", "What would happen if the Moon suddenly disappeared?", Q, g("question"), replyHas = Regex("(?i)tide")),
    )

    /** A fixture web: answers by topic, like a search provider's snippets. */
    private fun page(q: String): String = when {
        Regex("(?i)diwali").containsMatchIn(q) -> "Diwali 2025: the festival of lights falls on Monday, 20 October 2025. Lakshmi Puja is performed in the evening; homes are lit with diyas."
        Regex("(?i)world cup|cricket").containsMatchIn(q) -> "Australia won the 2023 ICC Men's Cricket World Cup, beating India by six wickets in the final at Ahmedabad on 19 November 2023."
        else -> ""
    }

    /** Wraps the real model; for the stages the oracle covers, returns the case's gold answer instead. */
    private class Oracle(private val e: LlmEngine) : LlmEngine {
        @Volatile var mode = "none"; @Volatile var gold: Gold? = null
        @Volatile var describe: (String) -> String = { it }
        override val id get() = e.id; override val source get() = e.source
        override val supportsGrammar get() = e.supportsGrammar
        override fun isReady() = e.isReady(); override fun countTokens(messages: List<ChatMessage>) = e.countTokens(messages)
        override fun contextSize() = e.contextSize(); override fun cancel() = e.cancel()
        override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit) = generate(messages, params, CancelSignal(), onText)
        override fun generate(messages: List<ChatMessage>, params: GenParams, signal: CancelSignal, onText: (String) -> Unit): GenResult {
            val ask = messages.lastOrNull()?.content.orEmpty(); val g = gold
            val answer: String? = if (g == null || mode == "none") null else when {
                "[Plan] Today:" in ask -> buildJsonObject { put("kind", g.kind); put("goal", ask.substringAfterLast("Message: ").take(60)); put("lookup", JsonArray(g.lookups.map { JsonPrimitive(it) }))
                    put("tools", JsonArray(g.tools.map { JsonPrimitive(it) })) }.toString()
                ask.startsWith("[Choose]") -> g.tools.firstOrNull() ?: "none"
                ask.startsWith("[Check]") -> if (g.tools.any { describe(it).lowercase().trimEnd('.') in ask.lowercase() }) "yes" else "no"
                mode == "full" && ask.startsWith("[Fill]") -> Regex("for ([a-z.]+) \\(").find(ask)?.groupValues?.get(1)?.let { g.fills[it] }
                mode == "full" && ask.startsWith("[Extract]") -> g.extracts.firstOrNull()
                else -> null
            }
            if (answer != null) { onText(answer); return GenResult(answer, GenStats(stopReason = "eog"), source) }
            return e.generate(messages, params, signal, onText)
        }
    }

    private fun pct(xs: List<Double>, p: Double): Double = xs.sorted().let { s -> if (s.isEmpty()) -1.0 else s[((s.size - 1) * p).toInt()] }

    @Test fun evaluate(): Unit = runBlocking {
        val path = System.getProperty("clu.goalModel")
        assumeTrue(path != null && File(path).exists(), "no real model given")
        val runs = System.getProperty("clu.goalRuns", "1").toInt()
        val planner = System.getProperty("clu.goalPlanner", "stepwise")
        val modes = System.getProperty("clu.goalOracle", "none").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val clock = ai.clu.core.util.ManualClock(start.atZone(zone).toInstant().toEpochMilli())
        val core = newCore(clock = clock)
        core.reminderScheduler = ReminderScheduler { }
        val searches = mutableListOf<String>()
        core.toolRegistry.register(object : Tool {
            override val name = "web.search"; override val description = "Search the web for current facts."; override val argsExample = """{"query":"..."}"""
            override val sensitivity = Sensitivity.ROUTINE
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { val q = args.str("query").orEmpty(); searches += q
                // Shaped like the real search tool's result: a summary and source records (title, url, snippet).
                val p = page(q); return if (p.isEmpty()) ToolResult.fail("no results") else ToolResult.ok(p, spoken = null, usedNetwork = true,
                    data = buildJsonArray { addJsonObject { put("title", p.substringBefore(':').take(60)); put("url", "https://example.org/" + q.replace(' ', '-').lowercase())
                        put("snippet", p) } }) }
        })
        core.memories.save(ai.clu.core.memory.Memory(category = ai.clu.core.memory.MemoryCategory.CORE_FACT, title = "Next exam",
            content = "My next examination is Data Structures on 28 October 2025.", source = ai.clu.core.memory.MemorySource.OWNER_INSTRUCTION))
        val s = core.settings
        s.set(Settings.THREADS, 4); s.set(Settings.CONTEXT_LENGTH, 4096); s.set(Settings.LOAD_ON_START, false); s.set(Settings.PLANNER, planner)
        s.set(Settings.MODEL_ACTIVE, core.models.register(File(path)).id)
        val tLoad = System.nanoTime(); val real = core.llm.loadIfNeeded() ?: error(core.llm.state.value.message); val loadMs = (System.nanoTime() - tLoad) / 1e6
        // The production engine (InferenceService), wrapped so the oracle can answer chosen stages.
        val oracle = Oracle(core.inferenceService.engine()!!).also { o -> o.describe = { core.tools.describe(it) } }
        core.useEngine(oracle)
        val rows = mutableListOf<JsonObject>()
        for (mode in modes) for (run in 1..runs) for (c in cases) {
            oracle.mode = mode; oracle.gold = c.gold
            val conv = core.conversations.create("eval").id
            for (p in c.prior) { oracle.gold = null; core.agent.respond(TurnRequest(conv, p, ai.clu.core.security.Actor.ownerInApp())).toList(); oracle.gold = c.gold }
            val before = core.butler.open().map { it.id }.toSet(); val docsBefore = core.archive.documents.list().map { it.id }.toSet()
            core.goalEngine.last = null; core.goalEngine.lastRaw = ""; searches.clear()
            val t0 = System.nanoTime()
            val ev = core.agent.respond(TurnRequest(conv, c.text, ai.clu.core.security.Actor.ownerInApp())).toList()
            val totalMs = (System.nanoTime() - t0) / 1e6
            val added = core.butler.open().filter { it.id !in before }
            val newDocs = core.archive.documents.list().filter { it.id !in docsBefore }
            val goal = core.goalEngine.recent(conv).firstOrNull()
            val u = core.goalEngine.last
            val kind = u?.kind ?: goal?.kind
            val reply = ev.filterIsInstance<AgentEvent.Done>().lastOrNull()?.text.orEmpty()
            val ran = ev.filterIsInstance<AgentEvent.ToolStarted>().map { it.tool }
            fun dueOf(i: ai.clu.core.butler.ButlerItem) = i.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
            val reminders = added.filter { it.dueAt != null }; val listItems = added.filter { it.dueAt == null }
            val reminderOk = when {
                c.due != null -> reminders.size == 1 && dueOf(reminders[0]) == c.due
                c.dueOn != null -> reminders.size == 1 && dueOf(reminders[0])?.toLocalDate() == c.dueOn
                c.dueBy != null -> reminders.size == 1 && dueOf(reminders[0])!!.let { it.isAfter(start) && !it.isAfter(c.dueBy) }
                else -> reminders.isEmpty()
            }
            val titleOk = c.titleHas.isNullOrEmpty() || reminders.isEmpty() || reminders[0].title.contains(c.titleHas, true)
            val itemsOk = if (c.items.isEmpty()) listItems.isEmpty() else c.items.all { w -> listItems.any { it.title.contains(w, true) } }
            val docOk = if (c.docHas == null) newDocs.isEmpty() else newDocs.size == 1 && core.archive.documents.text(newDocs[0].id).contains(c.docHas, true)
            val searchOk = c.searched == null || (c.searched == ran.contains("web.search"))
            val replyOk = c.replyHas == null || c.replyHas.containsMatchIn(reply)
            val effectOk = reminderOk && titleOk && itemsOk && docOk && searchOk && replyOk
            val failedChecks = listOfNotNull("reminder".takeIf { !reminderOk }, "title".takeIf { !titleOk }, "items".takeIf { !itemsOk }, "document".takeIf { !docOk },
                "search".takeIf { !searchOk }, "reply".takeIf { !replyOk })
            rows += buildJsonObject {
                put("mode", mode); put("run", run); put("case", c.id); put("group", c.group); put("text", c.text); put("kind", kind?.name ?: "none")
                put("kind_ok", kind in c.expectKinds); put("effect_ok", effectOk); put("failed_checks", JsonArray(failedChecks.map { JsonPrimitive(it) }))
                put("unwanted_change", !c.changes && (added.isNotEmpty() || newDocs.isNotEmpty()))
                put("understanding_raw", core.goalEngine.lastRaw.take(400))
                put("goal_state", goal?.state?.name ?: "-"); put("goal_error", goal?.error.orEmpty())
                put("steps", goal?.steps?.joinToString(" > ") { (it.tool ?: (it.lookup?.let { l -> "lookup($l)" }) ?: "extract") + ":" + it.status.name.lowercase() } ?: "-")
                put("args", JsonArray(goal?.steps.orEmpty().filter { it.tool != null }.map { it.args }))
                put("provenance", JsonArray(goal?.steps.orEmpty().filter { it.tool != null }.mapNotNull { it.provenance }))
                put("lookups", JsonArray(goal?.steps.orEmpty().filter { it.lookup != null }.map { JsonPrimitive("${it.lookup} = ${it.value} [${it.status.name.lowercase()}]") }))
                put("tools_run", JsonArray(ran.map { JsonPrimitive(it) })); put("searches", JsonArray(searches.map { JsonPrimitive(it) }))
                put("added", JsonArray((added.map { "${it.title} @ ${dueOf(it)}" } + newDocs.map { "document ${it.name}" }).map { JsonPrimitive(it) }))
                put("refused", JsonArray(core.goalEngine.lastRefusals.map { JsonPrimitive(it) }))
                put("plan_ms", u?.ms?.toDouble() ?: -1.0); put("total_ms", "%.0f".format(totalMs).toDouble())
                put("reply_words", Regex("\\S+").findAll(reply).count()); put("reply", reply.take(500))
            }
            println("goal-eval: ${rows.last()}")
            added.forEach { core.butler.remove(it.id) }; newDocs.forEach { core.archive.documents.delete(it.id) }
        }
        // Failure attribution: the stage a failure belongs to, from the oracle runs (when they ran) and the recorded steps.
        fun row(mode: String, id: String) = rows.firstOrNull { it["mode"]!!.jsonPrimitive.content == mode && it["case"]!!.jsonPrimitive.content == id }
        fun ok(r: JsonObject?) = r?.get("effect_ok")?.jsonPrimitive?.boolean == true
        val attribution = buildJsonArray {
            for (c in cases) {
                val base = row(modes.first(), c.id) ?: continue
                if (ok(base)) continue
                val und = row("understand", c.id); val full = row("full", c.id)
                val raw = runCatching { CluJson.parseToJsonElement(base["understanding_raw"]!!.jsonPrimitive.content).jsonObject }.getOrNull()
                val rawKind = raw?.get("kind")?.jsonPrimitive?.content
                val rawTools = (raw?.get("tools") as? JsonArray)?.map { it.jsonPrimitive.content } ?: listOfNotNull(raw?.get("tool")?.jsonPrimitive?.content).filter { it != "none" }
                val rawLookups = (raw?.get("lookup") as? JsonArray)?.size ?: 0
                val stage = when {
                    und != null && ok(und) -> when {
                        rawKind != c.gold.kind && !(c.gold.kind in setOf("statement", "thinking_aloud", "chat") && rawKind in setOf("statement", "thinking_aloud", "chat")) -> "intent interpretation"
                        c.gold.lookups.isNotEmpty() && rawLookups == 0 -> "missing-information detection"
                        rawTools.toSet() != c.gold.tools.toSet() -> "tool selection"
                        base["refused"]!!.jsonArray.any { "intent check" in it.jsonPrimitive.content } -> "intent check"
                        else -> "objective extraction"
                    }
                    full != null && ok(full) -> if (base["lookups"]!!.jsonArray.any { "[failed]" in it.jsonPrimitive.content }) "fact retrieval" else "argument generation"
                    full != null -> { // fails even with every model stage answered correctly: CLU's own orchestration
                        val checks = full["failed_checks"]!!.jsonArray.map { it.jsonPrimitive.content }
                        when {
                            full["refused"]!!.jsonArray.isNotEmpty() -> "grounding validation (CLU)"
                            full["goal_state"]!!.jsonPrimitive.content == "FAILED" -> "execution (CLU)"
                            checks == listOf("reply") -> "response generation"
                            "search" in checks -> "research routing (CLU)"
                            else -> "verification/orchestration (CLU)"
                        }
                    }
                    else -> "not attributed (oracle modes not run)"
                }
                add(buildJsonObject { put("case", c.id); put("stage", stage); put("model_said", base["understanding_raw"]!!.jsonPrimitive.content.take(200))
                    put("passes_with_oracle_understanding", und?.let { ok(it) }); put("passes_with_full_oracle", full?.let { ok(it) })
                    put("failed_checks", base["failed_checks"]!!); put("detail", (base["refused"]!!.jsonArray.firstOrNull()?.jsonPrimitive?.content ?: base["goal_error"]!!.jsonPrimitive.content).take(200)) })
            }
        }
        val summary = buildJsonObject {
            put("model", File(path).name); put("planner", planner); put("loaded", core.llm.loadedSummary().orEmpty().toString()); put("load_ms", "%.0f".format(loadMs).toDouble())
            put("runs", runs); put("cases_per_run", cases.size)
            put("by_mode", buildJsonObject { for (m in modes) { val rs = rows.filter { it["mode"]!!.jsonPrimitive.content == m }
                put(m, buildJsonObject {
                    put("task_success", "${rs.count { ok(it) }}/${rs.size}"); put("unwanted_changes", rs.count { it["unwanted_change"]!!.jsonPrimitive.boolean })
                    put("kind_correct", "${rs.count { it["kind_ok"]!!.jsonPrimitive.boolean }}/${rs.size}")
                    put("by_group", buildJsonObject { rs.groupBy { it["group"]!!.jsonPrimitive.content }.forEach { (g, x) -> put(g, "${x.count { ok(it) }}/${x.size}") } })
                    val plan = rs.map { it["plan_ms"]!!.jsonPrimitive.double }.filter { it >= 0 }; val total = rs.map { it["total_ms"]!!.jsonPrimitive.double }
                    put("plan_ms_median", pct(plan, 0.5)); put("plan_ms_p95", pct(plan, 0.95)); put("total_ms_median", pct(total, 0.5)); put("total_ms_p95", pct(total, 0.95))
                }) } })
            put("failure_attribution", attribution)
            put("rows", JsonArray(rows))
        }
        println("goal-eval summary: " + JsonObject(summary.filterKeys { it != "rows" }))
        System.getProperty("clu.goalOut")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), summary)) }
        core.shutdown()
    }
}
