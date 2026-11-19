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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Intent-driven understanding with a real model, through the production path (CluCore, InferenceService, llama.cpp
 * grammar, GoalEngine, real reminder and date tools). Web search is a fixture page (the build machine's network policy
 * blocks search providers). The messages are paraphrases that do not appear in the planner's examples.
 *
 * Opt-in: -Dclu.goalModel=<gguf> [-Dclu.goalOut=<json>] [-Dclu.goalRuns=<runs, default 1>].
 * Records per case: the kind the model chose, whether the planned steps produced the right effect (checked in the
 * reminder store, not in the reply), the planning time and the whole turn; then success rate, median and p95.
 */
class GoalEvaluation {
    /** expectKind: the right reading; due: the reminder that must exist afterwards (null = nothing may change). */
    data class Case(val id: String, val text: String, val expectKind: MessageKind, val due: LocalDateTime? = null, val titleHas: String? = null,
                    val listItem: String? = null, val dueBy: LocalDateTime? = null)

    // The clock: Thursday 9 October 2025, 10:00 local time (Diwali 2025 is on Monday 20 October).
    private val zone = ZoneId.systemDefault()
    private val start = LocalDateTime.of(2025, 10, 9, 10, 0)
    private val cases = listOf(
        Case("diwali", "Diwali's coming up, look up the date and remind me to buy diyas three days before it", MessageKind.INSTRUCTION, LocalDateTime.of(2025, 10, 17, 9, 0), "diya"),
        Case("diwali-clean", "Can you check when Diwali falls this year and set a reminder two days before to clean the house?", MessageKind.INSTRUCTION, LocalDateTime.of(2025, 10, 18, 9, 0), "clean"),
        Case("rahul", "I have a meeting with Rahul tomorrow at 3 pm, remind me an hour before it", MessageKind.INSTRUCTION, LocalDateTime.of(2025, 10, 10, 14, 0), "rahul"),
        Case("assignment", "My assignment is due on 25 October. Remind me two days before so I can finish it", MessageKind.INSTRUCTION, LocalDateTime.of(2025, 10, 23, 9, 0), "assignment"),
        Case("call-mom", "Remind me to call mom at 7 pm today", MessageKind.INSTRUCTION, LocalDateTime.of(2025, 10, 9, 19, 0), "mom"),
        Case("shopping", "Add milk and eggs to my shopping list", MessageKind.INSTRUCTION, listItem = "milk"),
        Case("musing-dp", "I've been thinking about whether dynamic programming is overkill for this scheduling problem", MessageKind.THINKING_ALOUD),
        Case("musing-reminders", "Maybe I should start using reminders more often, I keep forgetting things", MessageKind.THINKING_ALOUD),
        Case("musing-diwali", "I was wondering if I'll have time to visit my parents for Diwali this year", MessageKind.THINKING_ALOUD),
        Case("question-capital", "What is the capital of Australia?", MessageKind.QUESTION),
        Case("question-diwali", "When is Diwali this year?", MessageKind.QUESTION),
        Case("chat", "Good morning! How's it going?", MessageKind.CHAT),
        // Acceptance test 1 (verbatim) and test 5 (the exam date is in CLU's memory): any reminder before the deadline counts.
        Case("t1-busy-thursday", "I'm going to be busy next Thursday. Make sure I don't forget my assignment submission.", MessageKind.INSTRUCTION,
            titleHas = "assign", dueBy = LocalDateTime.of(2025, 10, 16, 23, 59)),
        Case("t5-exam", "Find out when my next examination is and make sure I have enough time to prepare.", MessageKind.INSTRUCTION,
            titleHas = "", dueBy = LocalDateTime.of(2025, 10, 27, 23, 59)),
    )

    private fun pct(xs: List<Double>, p: Double): Double = xs.sorted().let { s -> if (s.isEmpty()) -1.0 else s[((s.size - 1) * p).toInt()] }

    @Test fun evaluate(): Unit = runBlocking {
        val path = System.getProperty("clu.goalModel")
        assumeTrue(path != null && File(path).exists(), "no real model given")
        val runs = System.getProperty("clu.goalRuns", "1").toInt()
        val clock = ai.clu.core.util.ManualClock(start.atZone(zone).toInstant().toEpochMilli())
        val core = newCore(clock = clock)
        core.reminderScheduler = ReminderScheduler { }
        core.toolRegistry.register(object : Tool {
            override val name = "web.search"; override val description = "Search the web for current facts."; override val argsExample = """{"query":"..."}"""
            override val sensitivity = Sensitivity.ROUTINE
            override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult.ok(
                "Diwali 2025: the festival of lights falls on Monday, 20 October 2025. Lakshmi Puja is performed in the evening.", spoken = null, usedNetwork = true)
        })
        core.memories.save(ai.clu.core.memory.Memory(category = ai.clu.core.memory.MemoryCategory.CORE_FACT, title = "Next exam", content = "My next examination is Data Structures on 28 October 2025.", source = ai.clu.core.memory.MemorySource.OWNER_INSTRUCTION))
        val s = core.settings
        s.set(Settings.THREADS, 4); s.set(Settings.CONTEXT_LENGTH, 4096); s.set(Settings.MAX_TOKENS, 120); s.set(Settings.LOAD_ON_START, false)
        s.set(Settings.MODEL_ACTIVE, core.models.register(File(path)).id)
        val tLoad = System.nanoTime(); core.llm.loadIfNeeded() ?: error(core.llm.state.value.message); val loadMs = (System.nanoTime() - tLoad) / 1e6
        val rows = mutableListOf<JsonObject>()
        for (run in 1..runs) for (c in cases) {
            val before = core.butler.open().map { it.id }.toSet()
            val conv = core.conversations.create("eval").id
            core.goalEngine.last = null
            val t0 = System.nanoTime()
            val ev = core.agent.respond(TurnRequest(conv, c.text, ai.clu.core.security.Actor.ownerInApp())).toList()
            val totalMs = (System.nanoTime() - t0) / 1e6
            val added = core.butler.open().filter { it.id !in before }
            val goal = core.goalEngine.recent(conv).firstOrNull()
            val u = core.goalEngine.last
            val kind = u?.kind
            val planMs = u?.ms?.toDouble() ?: -1.0
            val effectOk = when {
                c.dueBy != null -> added.size == 1 && added[0].dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }?.let { it.isAfter(start) && !it.isAfter(c.dueBy) } == true &&
                    (c.titleHas.isNullOrEmpty() || added[0].title.contains(c.titleHas, true))
                c.due != null -> added.size == 1 && added[0].dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() } == c.due &&
                    (c.titleHas == null || added[0].title.contains(c.titleHas, true))
                c.listItem != null -> added.any { it.title.contains(c.listItem, true) }
                else -> added.isEmpty()
            }
            val reply = ev.filterIsInstance<AgentEvent.Done>().lastOrNull()?.text.orEmpty()
            rows += buildJsonObject {
                put("run", run); put("case", c.id); put("text", c.text); put("expected_kind", c.expectKind.name); put("kind", kind?.name ?: "none")
                put("kind_ok", kind == c.expectKind); put("effect_ok", effectOk); put("planned", kind != null)
                put("goal_state", goal?.state?.name ?: "-"); put("steps", goal?.steps?.joinToString(" > ") { it.tool ?: "extract" } ?: "-")
                put("added", JsonArray(added.map { JsonPrimitive("${it.title} @ ${it.dueAt?.let { d -> Instant.ofEpochMilli(d).atZone(zone).toLocalDateTime() }}") }))
                put("refused", JsonArray(core.goalEngine.lastRefusals.map { JsonPrimitive(it) })); put("plan_ms", planMs); put("total_ms", "%.0f".format(totalMs).toDouble()); put("reply", reply.take(300))
            }
            println("goal-eval: ${rows.last()}")
            added.forEach { core.butler.remove(it.id) }
        }
        val total = rows.map { it["total_ms"]!!.jsonPrimitive.double }; val plan = rows.map { it["plan_ms"]!!.jsonPrimitive.double }.filter { it >= 0 }
        val summary = buildJsonObject {
            put("model", File(path).name); put("loaded", core.llm.loadedSummary().orEmpty().toString()); put("load_ms", "%.0f".format(loadMs).toDouble()); put("runs", runs); put("cases", rows.size)
            put("task_success", rows.count { it["effect_ok"]!!.jsonPrimitive.boolean }); put("kind_correct", rows.count { it["kind_ok"]!!.jsonPrimitive.boolean })
            put("no_valid_plan", rows.count { !it["planned"]!!.jsonPrimitive.boolean })
            put("unwanted_actions", rows.count { r -> cases.first { it.id == r["case"]!!.jsonPrimitive.content }.let { it.due == null && it.listItem == null && it.dueBy == null } && !r["effect_ok"]!!.jsonPrimitive.boolean })
            put("plan_ms_median", pct(plan, 0.5)); put("plan_ms_p95", pct(plan, 0.95)); put("total_ms_median", pct(total, 0.5)); put("total_ms_p95", pct(total, 0.95))
            put("rows", JsonArray(rows))
        }
        println("goal-eval summary: " + JsonObject(summary.filterKeys { it != "rows" }))
        System.getProperty("clu.goalOut")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), summary)) }
        core.shutdown()
    }
}
