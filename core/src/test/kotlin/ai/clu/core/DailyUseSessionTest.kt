package ai.clu.core

import ai.clu.core.agent.*
import ai.clu.core.butler.ReminderScheduler
import ai.clu.core.security.Actor
import ai.clu.core.security.Sensitivity
import ai.clu.core.tools.HttpResponse
import ai.clu.core.tools.Tool
import ai.clu.core.tools.ToolContext
import ai.clu.core.tools.ToolResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.*
import org.junit.jupiter.api.Test

/**
 * One session shaped like a day of use, through the production agent, with a scripted grammar-capable model (the
 * model's own quality is measured elsewhere; here every decision CLU's code makes is checked): conversation, a complex
 * question, a follow-up, an earlier personal fact, research, a document, a reminder, an interrupted and resumed task,
 * an application restart on the same database, and voice. Each step is recorded on its own; the test fails listing
 * every failed step instead of stopping at the first.
 */
class DailyUseSessionTest {
    private val clock = ai.clu.core.util.ManualClock(LocalDateTime.of(2025, 10, 9, 10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    private val dbFile = File.createTempFile("clu-daily", ".db").also { f -> listOf("", "-journal", "-wal", "-shm").forEach { File(f.path + it).deleteOnExit() } }
    private val platform = TestPlatform()
    private var core = start()
    private val owner = Actor.ownerInApp()
    private val conv = core.conversations.create("day").id
    private val results = mutableListOf<Triple<Int, String, String?>>() // step, name, failure (null = passed)
    @Volatile private var interruptNext = false

    private fun start(): CluCore = CluCore(JdbcDriver(dbFile.absolutePath), platform, clock).also { c ->
        c.reminderScheduler = ReminderScheduler { }
        // A slow step the owner can interrupt (stands in for any long action).
        c.toolRegistry.register(object : Tool {
            override val name = "note.send"; override val description = "Send a note to someone."; override val argsExample = """{"to":"Asha","text":"..."}"""
            override val sensitivity = Sensitivity.ROUTINE
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                if (interruptNext) { interruptNext = false; core.agent.cancel(null); kotlinx.coroutines.delay(50) }
                return ToolResult.ok("Sent the note to ${(args["to"] as? kotlinx.serialization.json.JsonPrimitive)?.content}.")
            }
        })
    }

    private fun model(vararg r: String) = ScriptedEngine(grammar = true).reply(*r).also { core.useEngine(it) }
    private fun say(t: String, voice: Boolean = false) = runBlocking { core.agent.respond(TurnRequest(conv, t, owner, viaVoice = voice)).toList() }
    private fun reply(ev: List<AgentEvent>) = ev.filterIsInstance<AgentEvent.Done>().lastOrNull()?.text.orEmpty()
    private fun tools(ev: List<AgentEvent>) = ev.filterIsInstance<AgentEvent.ToolStarted>().map { it.tool }
    private fun seen(m: ScriptedEngine) = m.prompts.joinToString("\n") { p -> p.joinToString("\n") { it.content } }
    private fun step(n: Int, name: String, block: () -> Unit) {
        val failure = try { block(); null } catch (e: Throwable) { e.message ?: e.toString() }
        results += Triple(n, name, failure); println("daily ${if (failure == null) "ok " else "BAD"} $n. $name${failure?.let { ": $it" } ?: ""}")
    }

    @Test fun aDayOfUse() {
        step(1, "ordinary conversation") {
            model("""{"kind":"chat","goal":"","lookup":[],"tools":["none"]}""", "Morning! Doing well, thanks. What's on your mind today?")
            val ev = say("Good morning! How's it going?")
            assertTrue(tools(ev).isEmpty(), "no tool for small talk: ${tools(ev)}"); assertTrue(reply(ev).isNotBlank())
            assertTrue(core.butler.open().isEmpty() && core.goalEngine.recent(conv).isEmpty(), "small talk changed nothing")
        }
        step(2, "complex question gets an explanation, not a one-liner") {
            val m = model("""{"kind":"question","goal":"","lookup":[],"tools":["none"]}""",
                "A refrigerator moves heat out rather than making cold. A refrigerant evaporates inside the cabinet and absorbs heat; the compressor squeezes it so it gets hot, and the coils at the back release that heat into the room. The liquid then expands through a valve, cools, and the cycle repeats.")
            val ev = say("Explain how a refrigerator keeps food cold.")
            assertContains(seen(m), "This question asks for an explanation", message = "the model was told this needs an explanation")
            assertTrue(reply(ev).split(' ').size > 40, "the full explanation reached the owner: ${reply(ev)}")
            assertFalse("web.search" in tools(ev), "a stable knowledge question is not researched")
        }
        step(3, "contextual follow-up sees the previous exchange") {
            val m = model("""{"kind":"question","goal":"","lookup":[],"tools":["none"]}""", "The compressor uses most of the energy, because it does the work of moving heat uphill.")
            say("Which part of it uses the most electricity?")
            assertContains(seen(m), "refrigerant", message = "the earlier answer is in the follow-up's prompt")
        }
        step(4, "an earlier personal fact is retrieved") {
            core.memories.save(ai.clu.core.memory.Memory(category = ai.clu.core.memory.MemoryCategory.CORE_FACT, title = "Sister",
                content = "My sister Meera lives in Pune.", source = ai.clu.core.memory.MemorySource.OWNER_INSTRUCTION))
            val m = model("""{"kind":"question","goal":"","lookup":[],"tools":["none"]}""", "Meera lives in Pune.")
            val ev = say("Where does my sister live these days?")
            assertContains(seen(m), "Meera lives in Pune", message = "the memory was given to the model")
            assertFalse("web.search" in tools(ev), "the owner's own life is not web research: ${tools(ev)}")
        }
        step(5, "research on an unfamiliar topic uses sources") {
            platform.fakeHttp.routes["srsearch="] = HttpResponse(200, """{"query":{"searchinfo":{"totalhits":1},"search":[{"title":"Tenzing Norgay","snippet":"Tenzing Norgay was a Nepali-Indian Sherpa mountaineer who reached the summit of Mount Everest in 1953 with Edmund Hillary.","timestamp":"2025-10-01T10:00:00Z"}]}}""")
            model("""{"kind":"question","goal":"","lookup":[],"tools":["none"]}""", "Tenzing Norgay was a Sherpa mountaineer who reached the summit of Everest in 1953 with Edmund Hillary.")
            val ev = say("Who was Tenzing Norgay?")
            assertTrue("web.search" in tools(ev), "researched: ${tools(ev)}")
            assertTrue(ev.filterIsInstance<AgentEvent.Done>().last().meta.sources.isNotEmpty(), "sources attached to the answer")
            assertContains(reply(ev), "1953")
        }
        step(6, "a document is created from research and saved to the archive") {
            core.toolRegistry.register(object : Tool {
                override val name = "web.search"; override val description = "Search the web."; override val argsExample = """{"query":"..."}"""
                override val sensitivity = Sensitivity.ROUTINE
                override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult.ok("Diwali 2025 falls on Monday, 20 October 2025.", spoken = null, usedNetwork = true)
            })
            model("""{"kind":"instruction","goal":"Diwali document","lookup":["Diwali 2025"],"tools":["document.write"]}""", "yes",
                "20 October 2025", """{"title":"Diwali","content":""}""", "# Diwali\n\nDiwali 2025 falls on Monday, 20 October 2025 (web).")
            val ev = say("Write me a short document about Diwali from what you can find online.")
            val doc = core.archive.documents.list().singleOrNull() ?: fail("no document saved: ${reply(ev)}")
            assertContains(core.archive.documents.text(doc.id), "20 October 2025")
        }
        step(7, "a reminder from natural language") {
            model("""{"kind":"instruction","goal":"vitamins reminder","lookup":[],"tools":["reminder.set"]}""", "yes", """{"what":"take my vitamins","when":"tonight at 9"}""")
            val ev = say("Can you nudge me to take my vitamins at 9 tonight?")
            val r = core.butler.open().singleOrNull { it.dueAt != null } ?: fail("no reminder: ${reply(ev)}")
            assertEquals(LocalDateTime.of(2025, 10, 9, 21, 0), java.time.Instant.ofEpochMilli(r.dueAt!!).atZone(ZoneId.systemDefault()).toLocalDateTime())
        }
        step(8, "a task interrupted by the owner, then resumed") {
            interruptNext = true
            model("""{"kind":"instruction","goal":"Asha dinner","lookup":[],"tools":["note.send","reminder.set"]}""", "yes", "yes",
                """{"to":"Asha","text":"dinner at 8"}""", """{"what":"dinner with Asha","when":"today at 7 pm"}""")
            say("send Asha a note saying dinner at 8 and remind me about dinner with Asha today at 7 pm")
            val g = core.goalEngine.recent(conv).first()
            assertNotEquals(GoalState.COMPLETED, g.state, "the interruption stopped the task (state ${g.state})")
            assertTrue(core.butler.open().none { it.title.contains("Asha", true) }, "nothing after the interruption ran")
            model("""{"to":"Asha","text":"dinner at 8"}""", """{"what":"dinner with Asha","when":"today at 7 pm"}""")
            val ev = say("continue the task")
            assertTrue(core.butler.open().any { it.title.contains("Asha", true) }, "resumed and finished: ${reply(ev)} ${tools(ev)}")
            assertEquals(GoalState.COMPLETED, core.goalEngine.recent(conv).first().state)
        }
        step(9, "the application restarts and everything is still there") {
            // A task interrupted by the process dying (RUNNING in the store), then a new process on the same database.
            model("""{"kind":"instruction","goal":"milk and bread","lookup":[],"tools":["task.add","task.add"]}""", "yes",
                """{"title":"milk","list":"shopping"}""", """{"title":"bread","list":"shopping"}""")
            say("add milk and bread to the shopping list")
            val g = core.goalEngine.recent(conv).first()
            val rewound = g.steps.mapIndexed { i, s -> if (i == g.steps.lastIndex) s.copy(status = GoalStepStatus.PENDING, args = JsonObject(emptyMap()), fill = true, provenance = null) else s }
            core.butler.open().filter { it.title == "bread" }.forEach { core.butler.remove(it.id) }
            GoalStore(core.db, core.clock::now).save(g.copy(state = GoalState.RUNNING, steps = rewound))
            val messagesBefore = core.conversations.messages(conv).size; val docsBefore = core.archive.documents.list().size
            core.shutdown()
            core = start() // the restart
            assertEquals(messagesBefore, core.conversations.messages(conv).size, "conversation history survived")
            assertEquals(docsBefore, core.archive.documents.list().size, "documents survived")
            assertTrue(core.butler.open().any { it.title.contains("vitamins") }, "reminders survived")
            assertTrue(core.memories.rank("sister Pune").isNotEmpty(), "memories survived")
            model("""{"title":"bread","list":"shopping"}""")
            val ev = say("continue the task")
            assertTrue(core.butler.open().any { it.title == "bread" }, "the interrupted task finished after the restart: ${reply(ev)}")
            assertEquals(1, core.butler.open().count { it.title == "milk" }, "the finished step was not repeated")
        }
        step(10, "continuing by voice") {
            val m = model("""{"kind":"question","goal":"","lookup":[],"tools":["none"]}""", "It's because the Earth's axis is tilted, so each hemisphere leans toward the Sun for part of the year.")
            val ev = say("why do we have seasons", voice = true)
            assertContains(seen(m), "will be spoken aloud", message = "the model knew the reply is spoken")
            assertContains(seen(m), "about four to six spoken sentences", message = "an explanation by voice is sized for speech")
            assertFalse(Regex("[*#`]|https?://").containsMatchIn(reply(ev)), "nothing unspeakable in the reply: ${reply(ev)}")
        }
        val failed = results.filter { it.third != null }
        println("daily summary: ${results.size - failed.size}/${results.size} steps passed")
        assertTrue(failed.isEmpty(), "failed steps:\n" + failed.joinToString("\n") { "${it.first}. ${it.second}: ${it.third}" })
    }
}
