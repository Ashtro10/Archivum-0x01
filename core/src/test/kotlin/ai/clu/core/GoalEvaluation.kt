package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.MessageKind
import ai.clu.core.agent.TurnRequest
import ai.clu.core.butler.ReminderScheduler
import ai.clu.core.config.Settings
import ai.clu.core.security.Sensitivity
import ai.clu.core.tools.Tool
import ai.clu.core.tools.ToolContext
import ai.clu.core.tools.ToolResult
import ai.clu.core.tools.str
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
 * Intent-driven understanding and execution with a real model, through the production path (CluCore,
 * InferenceService, llama.cpp grammar, GoalEngine, the real reminder, list and date tools, the agent's research path).
 * Web search is a fixture that answers by topic (the build machine's network policy blocks search providers). The
 * messages are paraphrases that do not appear in the planner's examples.
 *
 * Opt-in: -Dclu.goalModel=<gguf> [-Dclu.goalOut=<json>] [-Dclu.goalRuns=<runs, default 1>] [-Dclu.goalPlanner=stepwise|plan].
 * Effects are checked in the stores (reminders, lists) and the tools that ran, never in the reply's claims.
 */
class GoalEvaluation {
    /**
     * expectKind: the right reading. due / dueOn / dueBy: the one reminder that must exist afterwards (exact, that day,
     * or any time before). items: list entries that must exist. prior: what the owner said earlier in the conversation.
     * searched: the web must (true) or must not (false) be searched. replyHas: the answer must say this. No effect given =
     * nothing may change.
     */
    data class Case(val id: String, val group: String, val text: String, val expectKind: MessageKind, val due: LocalDateTime? = null, val titleHas: String? = null,
                    val items: List<String> = emptyList(), val dueBy: LocalDateTime? = null, val dueOn: LocalDate? = null, val prior: List<String> = emptyList(),
                    val searched: Boolean? = null, val replyHas: Regex? = null, val alsoReminder: Boolean = false) {
        val changes get() = due != null || dueBy != null || dueOn != null || items.isNotEmpty()
    }

    // The clock: Thursday 9 October 2025, 10:00 local time (Diwali 2025 is on Monday 20 October).
    private val zone = ZoneId.systemDefault()
    private val start = LocalDateTime.of(2025, 10, 9, 10, 0)
    private val cases = listOf(
        // The original 14 (phase 1).
        Case("diwali", "lookup+act", "Diwali's coming up, look up the date and remind me to buy diyas three days before it", MessageKind.INSTRUCTION, LocalDateTime.of(2025, 10, 17, 9, 0), "diya"),
        Case("diwali-clean", "lookup+act", "Can you check when Diwali falls this year and set a reminder two days before to clean the house?", MessageKind.INSTRUCTION, LocalDateTime.of(2025, 10, 18, 9, 0), "clean"),
        Case("rahul", "act", "I have a meeting with Rahul tomorrow at 3 pm, remind me an hour before it", MessageKind.INSTRUCTION, LocalDateTime.of(2025, 10, 10, 14, 0), "rahul"),
        Case("assignment", "act", "My assignment is due on 25 October. Remind me two days before so I can finish it", MessageKind.INSTRUCTION, LocalDateTime.of(2025, 10, 23, 9, 0), "assignment"),
        Case("call-mom", "act", "Remind me to call mom at 7 pm today", MessageKind.INSTRUCTION, LocalDateTime.of(2025, 10, 9, 19, 0), "mom"),
        Case("shopping", "act", "Add milk and eggs to my shopping list", MessageKind.INSTRUCTION, items = listOf("milk")),
        Case("musing-dp", "no-action", "I've been thinking about whether dynamic programming is overkill for this scheduling problem", MessageKind.THINKING_ALOUD),
        Case("musing-reminders", "no-action", "Maybe I should start using reminders more often, I keep forgetting things", MessageKind.THINKING_ALOUD),
        Case("musing-diwali", "no-action", "I was wondering if I'll have time to visit my parents for Diwali this year", MessageKind.THINKING_ALOUD),
        Case("question-capital", "no-action", "What is the capital of Australia?", MessageKind.QUESTION, replyHas = Regex("(?i)canberra")),
        Case("question-diwali", "research", "When is Diwali this year?", MessageKind.QUESTION, searched = true, replyHas = Regex("(?i)20 october|october 20")),
        Case("chat", "no-action", "Good morning! How's it going?", MessageKind.CHAT),
        Case("t1-busy-thursday", "acceptance", "I'm going to be busy next Thursday. Make sure I don't forget my assignment submission.", MessageKind.INSTRUCTION,
            titleHas = "assign", dueBy = LocalDateTime.of(2025, 10, 16, 23, 59)),
        Case("t5-exam", "acceptance", "Find out when my next examination is and make sure I have enough time to prepare.", MessageKind.INSTRUCTION,
            dueBy = LocalDateTime.of(2025, 10, 27, 23, 59)),
        // Phase 2 additions.
        Case("para-vitamins", "paraphrase", "Can you nudge me to take my vitamins at 9 tonight?", MessageKind.INSTRUCTION, LocalDateTime.of(2025, 10, 9, 21, 0), "vitamin"),
        Case("para-bill", "paraphrase", "Don't let me forget the electricity bill on Monday", MessageKind.INSTRUCTION, dueOn = LocalDate.of(2025, 10, 13), titleHas = "bill"),
        Case("para-groceries", "paraphrase", "we're out of coffee, stick it on the shopping list", MessageKind.INSTRUCTION, items = listOf("coffee")),
        Case("indirect-birthday", "indirect", "My sister's birthday is on 2 November and I always forget to call her", MessageKind.INSTRUCTION,
            dueBy = LocalDateTime.of(2025, 11, 2, 23, 59), titleHas = "sister"),
        Case("context-dentist", "context", "Remind me the day before.", MessageKind.INSTRUCTION, dueOn = LocalDate.of(2025, 10, 29),
            prior = listOf("My dentist appointment is on 30 October at 11 am.")),
        Case("research-worldcup", "research", "Who won the last men's cricket world cup, and who did they beat?", MessageKind.QUESTION, searched = true,
            replyHas = Regex("(?i)australia")),
        Case("multi-tool", "multi-tool", "Add eggs to the shopping list and remind me to buy them tomorrow at 6 pm", MessageKind.INSTRUCTION,
            items = listOf("egg"), due = LocalDateTime.of(2025, 10, 10, 18, 0), alsoReminder = true),
        Case("musing-gym", "no-action", "I wonder if joining a gym near the office would actually make me go more often", MessageKind.THINKING_ALOUD),
    )

    /** A fixture web: answers by topic, like a search provider's snippets. */
    private fun page(q: String): String = when {
        Regex("(?i)diwali").containsMatchIn(q) -> "Diwali 2025: the festival of lights falls on Monday, 20 October 2025. Lakshmi Puja is performed in the evening."
        Regex("(?i)world cup|cricket").containsMatchIn(q) -> "Australia won the 2023 ICC Men's Cricket World Cup, beating India by six wickets in the final at Ahmedabad on 19 November 2023."
        else -> ""
    }

    private fun pct(xs: List<Double>, p: Double): Double = xs.sorted().let { s -> if (s.isEmpty()) -1.0 else s[((s.size - 1) * p).toInt()] }

    @Test fun evaluate(): Unit = runBlocking {
        val path = System.getProperty("clu.goalModel")
        assumeTrue(path != null && File(path).exists(), "no real model given")
        val runs = System.getProperty("clu.goalRuns", "1").toInt()
        val planner = System.getProperty("clu.goalPlanner", "stepwise")
        val clock = ai.clu.core.util.ManualClock(start.atZone(zone).toInstant().toEpochMilli())
        val core = newCore(clock = clock)
        core.reminderScheduler = ReminderScheduler { }
        val searches = mutableListOf<String>()
        core.toolRegistry.register(object : Tool {
            override val name = "web.search"; override val description = "Search the web for current facts."; override val argsExample = """{"query":"..."}"""
            override val sensitivity = Sensitivity.ROUTINE
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { val q = args.str("query").orEmpty(); searches += q
                val p = page(q); return if (p.isEmpty()) ToolResult.fail("no results") else ToolResult.ok(p, spoken = null, usedNetwork = true) }
        })
        core.memories.save(ai.clu.core.memory.Memory(category = ai.clu.core.memory.MemoryCategory.CORE_FACT, title = "Next exam",
            content = "My next examination is Data Structures on 28 October 2025.", source = ai.clu.core.memory.MemorySource.OWNER_INSTRUCTION))
        val s = core.settings
        s.set(Settings.THREADS, 4); s.set(Settings.CONTEXT_LENGTH, 4096); s.set(Settings.LOAD_ON_START, false); s.set(Settings.PLANNER, planner)
        s.set(Settings.MODEL_ACTIVE, core.models.register(File(path)).id)
        val tLoad = System.nanoTime(); core.llm.loadIfNeeded() ?: error(core.llm.state.value.message); val loadMs = (System.nanoTime() - tLoad) / 1e6
        val rows = mutableListOf<JsonObject>()
        for (run in 1..runs) for (c in cases) {
            val conv = core.conversations.create("eval").id
            for (p in c.prior) core.agent.respond(TurnRequest(conv, p, ai.clu.core.security.Actor.ownerInApp())).toList()
            val before = core.butler.open().map { it.id }.toSet()
            core.goalEngine.last = null; searches.clear()
            val t0 = System.nanoTime()
            val ev = core.agent.respond(TurnRequest(conv, c.text, ai.clu.core.security.Actor.ownerInApp())).toList()
            val totalMs = (System.nanoTime() - t0) / 1e6
            val added = core.butler.open().filter { it.id !in before }
            val goal = core.goalEngine.recent(conv).firstOrNull()
            val u = core.goalEngine.last
            val kind = goal?.kind ?: u?.kind
            val reply = ev.filterIsInstance<AgentEvent.Done>().lastOrNull()?.text.orEmpty()
            val ran = ev.filterIsInstance<AgentEvent.ToolStarted>().map { it.tool }
            fun dueOf(i: ai.clu.core.butler.ButlerItem) = i.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
            val reminders = added.filter { it.dueAt != null }; val listItems = added.filter { it.dueAt == null }
            val reminderOk = when {
                c.due != null -> reminders.size == 1 && dueOf(reminders[0]) == c.due
                c.dueOn != null -> reminders.size == 1 && dueOf(reminders[0])?.toLocalDate() == c.dueOn
                c.dueBy != null -> reminders.size == 1 && dueOf(reminders[0])!!.let { it.isAfter(start) && !it.isAfter(c.dueBy) }
                else -> reminders.isEmpty()
            } && (c.titleHas.isNullOrEmpty() || reminders.isEmpty() || reminders[0].title.contains(c.titleHas, true))
            val itemsOk = if (c.items.isEmpty()) listItems.isEmpty() else c.items.all { w -> listItems.any { it.title.contains(w, true) } }
            val searchOk = c.searched == null || (c.searched == ran.contains("web.search"))
            val replyOk = c.replyHas == null || c.replyHas.containsMatchIn(reply)
            val effectOk = reminderOk && itemsOk && searchOk && replyOk
            rows += buildJsonObject {
                put("run", run); put("case", c.id); put("group", c.group); put("text", c.text); put("expected_kind", c.expectKind.name); put("kind", kind?.name ?: "none")
                put("kind_ok", kind == c.expectKind); put("effect_ok", effectOk); put("planned", u != null)
                put("unwanted_change", !c.changes && added.isNotEmpty())
                put("goal_state", goal?.state?.name ?: "-"); put("steps", goal?.steps?.joinToString(" > ") { it.tool ?: (it.lookup?.let { l -> "lookup($l)" }) ?: "extract" } ?: "-")
                put("tools_run", JsonArray(ran.map { JsonPrimitive(it) })); put("args", JsonArray(goal?.steps.orEmpty().filter { it.tool != null }.map { it.args })); put("searches", JsonArray(searches.map { JsonPrimitive(it) }))
                put("added", JsonArray(added.map { JsonPrimitive("${it.title} @ ${dueOf(it)}") }))
                put("refused", JsonArray(core.goalEngine.lastRefusals.map { JsonPrimitive(it) })); put("model_calls", goal?.modelCalls ?: (u?.modelCalls ?: 0))
                put("plan_ms", u?.ms?.toDouble() ?: -1.0); put("total_ms", "%.0f".format(totalMs).toDouble()); put("reply", reply.take(400))
            }
            println("goal-eval: ${rows.last()}")
            added.forEach { core.butler.remove(it.id) }
        }
        val total = rows.map { it["total_ms"]!!.jsonPrimitive.double }; val plan = rows.map { it["plan_ms"]!!.jsonPrimitive.double }.filter { it >= 0 }
        fun count(k: String) = rows.count { it[k]!!.jsonPrimitive.boolean }
        val summary = buildJsonObject {
            put("model", File(path).name); put("planner", planner); put("loaded", core.llm.loadedSummary().orEmpty().toString()); put("load_ms", "%.0f".format(loadMs).toDouble())
            put("runs", runs); put("cases", rows.size); put("task_success", count("effect_ok")); put("kind_correct", count("kind_ok"))
            put("no_valid_plan", rows.count { !it["planned"]!!.jsonPrimitive.boolean }); put("unwanted_changes", count("unwanted_change"))
            put("original_14_success", rows.take(14).count { it["effect_ok"]!!.jsonPrimitive.boolean })
            put("by_group", buildJsonObject { rows.groupBy { it["group"]!!.jsonPrimitive.content }.forEach { (g, rs) -> put(g, "${rs.count { it["effect_ok"]!!.jsonPrimitive.boolean }}/${rs.size}") } })
            put("plan_ms_median", pct(plan, 0.5)); put("plan_ms_p95", pct(plan, 0.95)); put("total_ms_median", pct(total, 0.5)); put("total_ms_p95", pct(total, 0.95))
            put("rows", JsonArray(rows))
        }
        println("goal-eval summary: " + JsonObject(summary.filterKeys { it != "rows" }))
        System.getProperty("clu.goalOut")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), summary)) }
        core.shutdown()
    }
}
