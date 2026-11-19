package ai.clu.core

import ai.clu.core.agent.*
import ai.clu.core.butler.ReminderScheduler
import ai.clu.core.llm.*
import ai.clu.core.memory.Memory
import ai.clu.core.memory.MemoryCategory
import ai.clu.core.memory.MemorySource
import ai.clu.core.security.Actor
import ai.clu.core.security.Sensitivity
import ai.clu.core.tools.Tool
import ai.clu.core.tools.ToolContext
import ai.clu.core.tools.ToolResult
import ai.clu.core.tools.str
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.*
import org.junit.jupiter.api.Test

/**
 * The stepwise planner (the default): the model answers small constrained questions (kind, missing facts, one tool),
 * the engine looks the facts up (the owner's memory first, then the web) and the model fills the tool's arguments with
 * the facts in hand. Also: a goal continues after the owner confirms a step, and long replies are not cut off.
 */
class StepwiseGoalTest {
    private val clock = ai.clu.core.util.ManualClock(LocalDateTime.of(2025, 10, 9, 10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    private val core = newCore(clock = clock).also { it.reminderScheduler = ReminderScheduler { } }
    private val conv = core.conversations.create().id
    private val owner = Actor.ownerInApp()
    private val searches = mutableListOf<String>()
    private val sent = mutableListOf<String>()

    init {
        core.toolRegistry.register(object : Tool {
            override val name = "web.search"; override val description = "Search the web."; override val argsExample = """{"query":"..."}"""
            override val sensitivity = Sensitivity.ROUTINE
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { searches += args.str("query") ?: ""
                return ToolResult.ok("Diwali 2025 falls on Monday, 20 October 2025.", spoken = null, usedNetwork = true) }
        })
        // An action that needs the owner's yes (like sending a message).
        core.toolRegistry.register(object : Tool {
            override val name = "note.send"; override val description = "Send a note to someone."; override val argsExample = """{"to":"Asha","text":"..."}"""
            override val sensitivity = Sensitivity.ROUTINE; override val confirmByDefault = true
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { sent += args.str("text") ?: ""; return ToolResult.ok("Sent the note to ${args.str("to")}.") }
        })
    }

    private fun model(vararg r: String) = ScriptedEngine(grammar = true).reply(*r).also { core.useEngine(it) }
    private fun say(t: String) = runBlocking { core.agent.respond(TurnRequest(conv, t, owner)).toList() }
    private fun reply(ev: List<AgentEvent>) = ev.filterIsInstance<AgentEvent.Done>().last().text
    private fun tools(ev: List<AgentEvent>) = ev.filterIsInstance<AgentEvent.ToolStarted>().map { it.tool }
    private fun reminders() = core.butler.open().filter { it.dueAt != null }
    private fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()

    @Test fun diwaliLooksUpTheDateThenSetsTheReminder() {
        val m = model("""{"kind":"instruction","goal":"diyas reminder","lookup":["date of Diwali 2025"],"tool":"reminder.set"}""", "yes",
            "20 October 2025",                                                   // read from the web result (memory had nothing)
            """{"what":"buy diyas","when":"3 days before 20 October 2025"}""")
        val ev = say("Diwali's coming up, look up the date and remind me to buy diyas three days before it")
        assertEquals(listOf("memory.search", "web.search", "reminder.set"), tools(ev), "memory first, then the web, then the action")
        assertEquals(LocalDateTime.of(2025, 10, 17, 9, 0), local(reminders().single().dueAt!!))
        assertTrue("20 October 2025" in reply(ev) && "remind you to buy diyas" in reply(ev), reply(ev))
        assertNotNull(m.params[0].grammar); assertTrue(m.params.take(3).all { it.grammar != null }, "every planning call is constrained")
        assertEquals(GoalState.COMPLETED, core.goalEngine.recent(conv).first().state)
    }

    @Test fun acceptanceTest1BusyThursday() {
        model("""{"kind":"instruction","goal":"assignment reminder","lookup":[],"tool":"reminder.set"}""", "yes",
            """{"what":"submit my assignment","when":"Wednesday at 6 pm"}""")
        val ev = say("I'm going to be busy next Thursday. Make sure I don't forget my assignment submission.")
        val r = reminders().single()
        assertEquals(java.time.DayOfWeek.WEDNESDAY, local(r.dueAt!!).dayOfWeek); assertTrue("assignment" in r.title)
        assertTrue("remind you" in reply(ev))
    }

    @Test fun acceptanceTest5ExamFromMemory() {
        core.memories.save(Memory(category = MemoryCategory.CORE_FACT, title = "Next exam", content = "My next examination is Data Structures on 28 October 2025.",
            source = MemorySource.OWNER_INSTRUCTION))
        model("""{"kind":"instruction","goal":"exam preparation","lookup":["date of my next examination"],"tool":"reminder.set"}""", "yes",
            "28 October 2025",
            """{"what":"start preparing for the Data Structures examination","when":"a week before 28 October 2025"}""")
        val ev = say("Find out when my next examination is and make sure I have enough time to prepare.")
        assertEquals(listOf("memory.search", "reminder.set"), tools(ev), "found in memory: no web search")
        assertTrue(searches.isEmpty())
        assertEquals(LocalDateTime.of(2025, 10, 21, 9, 0), local(reminders().single().dueAt!!))
    }

    @Test fun inventedDetailsAreSentBackAndFilledAgain() {
        // The first fill invents a date nobody gave: refused, and filled again with the owner's words.
        model("""{"kind":"instruction","goal":"g","lookup":[],"tool":"reminder.set"}""", "yes",
            """{"what":"call the plumber","when":"17 October 2025 at 9 am"}""",
            """{"what":"call the plumber","when":"tomorrow at 9 am"}""")
        val ev = say("remind me to call the plumber tomorrow at 9 am")
        assertEquals("call the plumber", reminders().single().title)
        assertEquals(LocalDateTime.of(2025, 10, 10, 9, 0), local(reminders().single().dueAt!!))
        assertTrue(core.goalEngine.lastRefusals.any { "17 October" in it }, core.goalEngine.lastRefusals.toString())
        assertTrue("remind you" in reply(ev))
    }

    @Test fun detailsTheToolCantUseAreFilledAgain() {
        model("""{"kind":"instruction","goal":"g","lookup":[],"tool":"reminder.set"}""", "yes",
            """{"what":"call the plumber","when":"sometime soonish"}""",
            """{"what":"call the plumber","when":"tomorrow at 9 am"}""")
        say("remind me to call the plumber tomorrow at 9 am")
        assertEquals(LocalDateTime.of(2025, 10, 10, 9, 0), local(reminders().single().dueAt!!))
        assertEquals(1, core.goalEngine.recent(conv).first().retries)
    }

    @Test fun questionsTheModelCantAnswerAreResearched() {
        model("""{"kind":"question","goal":"Diwali date","lookup":["date of Diwali 2025"],"tool":"none"}""", "Diwali 2025 is on Monday 20 October.")
        val ev = say("so when's Diwali happening this year")
        assertTrue("web.search" in tools(ev), "the research path ran: ${tools(ev)}")
        assertTrue(reminders().isEmpty())
    }

    @Test fun thinkingAloudAndChatNeverAct() {
        model("""{"kind":"thinking_aloud","goal":"g","lookup":[],"tool":"reminder.set"}""", "Sounds like a good idea.")
        say("Maybe I should start using reminders more often")
        model("""{"kind":"question","goal":"g","lookup":[],"tool":"reminder.set"}""", "Paris.")
        say("what's the capital of France")
        assertTrue(reminders().isEmpty())
    }

    @Test fun aMisreadMessageIsCheckedBeforeAnythingChanges() {
        // The model calls a greeting an instruction to set a reminder; the separate yes/no check says no: nothing is set.
        val m = model("""{"kind":"instruction","goal":"g","lookup":[],"tool":"reminder.set"}""", "no", "Good morning to you too.")
        val text = reply(say("Good morning! How's it going?"))
        assertTrue(reminders().isEmpty()); assertEquals("Good morning to you too.", text)
        assertEquals("root ::= \"yes\" | \"no\"", m.params[1].grammar)
        assertTrue(core.goalEngine.lastRefusals.any { "intent check" in it })
    }

    @Test fun anInstructionWithoutAToolGetsAFocusedChoice() {
        val m = model("""{"kind":"instruction","goal":"g","lookup":[],"tool":"none"}""", "reminder.set", "yes",
            """{"what":"call her","when":"2 November"}""")
        say("My sister's birthday is on 2 November and I always forget to call her")
        assertEquals(LocalDateTime.of(2025, 11, 2, 9, 0), local(reminders().single().dueAt!!))
        assertTrue(m.params[1].grammar!!.contains("\"reminder.set\"") && !m.params[1].grammar!!.contains("web.search"), "only actions to choose from")
    }

    @Test fun filledTextCanOnlyBeTheOwnersWords() {
        val g = core.goalEngine.fillGrammar(kotlinx.serialization.json.Json.parseToJsonElement("""{"what":"call Ravi","when":"tomorrow 9 am"}""") as JsonObject,
            "remind me to buy diyas three days before Diwali", listOf("20 October 2025"), core.goalEngine.calendar())
        assertFalse("Ravi" in g, "example values are not in the vocabulary")
        assertTrue("\"diyas\"" in g && "\"October\"" in g && "\"october\"" in g)
        assertFalse("\"march\"" in g, "a month nobody mentioned can't be written")
        assertTrue("timeval" in g && "textval" in g)
    }

    @Test fun aTimeRelativeToAnUnnamedMomentIsAnchored() {
        val e = core.goalEngine
        fun w(json: String, owner: String, facts: List<String> = emptyList()) =
            (e.anchored(kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject, owner, facts)["when"] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals("two days before 25 October", w("""{"when":"two days before"}""", "My assignment is due on 25 October. Remind me two days before"))
        assertEquals("three days before 20 October 2025", w("""{"when":"three days before it"}""", "look up Diwali and remind me three days before it", listOf("20 October 2025")))
        assertEquals("an hour before tomorrow at 3 pm", w("""{"when":"an hour before"}""", "I have a meeting with Rahul tomorrow at 3 pm, remind me an hour before it"))
        assertEquals("tomorrow at 9 am", w("""{"when":"tomorrow at 9 am"}""", "anything"), "a complete time is left alone")
        assertEquals("a day before", w("""{"when":"a day before"}""", "between 3 October and 5 October"), "two candidate moments: not guessed")
    }

    @Test fun aGoalContinuesAfterTheOwnerConfirmsAStep() {
        core.settings.set(ai.clu.core.config.Settings.PLANNER, "plan")
        val plan = """{"kind":"instruction","goal":"note and reminder","steps":[{"id":"s1","tool":"note.send","args":{"to":"Asha","text":"dinner at 8"}},
            {"id":"s2","tool":"reminder.set","args":{"what":"dinner with Asha","when":"today at 7 pm"}}]}"""
        model(plan)
        val ask = reply(say("send Asha a note saying dinner at 8 and remind me about dinner with Asha today at 7 pm"))
        assertTrue(sent.isEmpty() && reminders().isEmpty(), "nothing runs before the owner says yes: $ask")
        val g0 = core.goalEngine.recent(conv).first(); assertEquals(GoalState.WAITING_FOR_EXTERNAL_RESULT, g0.state)
        val ev = say("yes")
        assertEquals(listOf("dinner at 8"), sent, "the confirmed step ran once")
        assertEquals("dinner with Asha", reminders().single().title, "and the rest of the goal continued")
        assertTrue("Sent the note" in reply(ev) && "remind you" in reply(ev), reply(ev))
        assertEquals(GoalState.COMPLETED, core.goalEngine.recent(conv).first().state)
        assertEquals(listOf("note.send", "reminder.set"), tools(ev))
    }

    @Test fun declinedStepEndsTheGoalAndSaysSo() {
        core.settings.set(ai.clu.core.config.Settings.PLANNER, "plan")
        model("""{"kind":"instruction","goal":"g","steps":[{"id":"s1","tool":"note.send","args":{"to":"Asha","text":"dinner at 8"}},
            {"id":"s2","tool":"reminder.set","args":{"what":"dinner with Asha","when":"today at 7 pm"}}]}""")
        say("send Asha a note saying dinner at 8 and remind me about dinner with Asha today at 7 pm")
        val text = reply(say("no"))
        assertTrue(sent.isEmpty() && reminders().isEmpty()); assertTrue(text.startsWith("Okay, I won't"), text)
        assertEquals(GoalState.CANCELLED, core.goalEngine.recent(conv).first().state)
    }

    /** An engine whose first reply stops at the token limit mid-sentence. */
    private class CutOff : LlmEngine {
        val prompts = mutableListOf<List<ChatMessage>>()
        override val id = "cut"; override val source = "local"
        override fun isReady() = true
        override fun countTokens(messages: List<ChatMessage>) = messages.sumOf { it.content.length / 4 + 4 }
        override fun contextSize() = 4096
        override fun cancel() {}
        override fun generate(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit): GenResult {
            prompts += messages
            val (t, stop) = if (prompts.size == 1) "The Moon stabilises Earth's axial tilt and drives the tides. Without it, the tides would shrink to the" to "max_tokens"
                else " Sun's smaller pull, about a third as strong, and nights would be much darker." to "eog"
            onText(t); return GenResult(t, GenStats(generatedTokens = 20, stopReason = stop), source)
        }
    }

    @Test fun aMalformedToolCallNeverReachesTheAnswer() {
        assertEquals("It depends on your schedule. Let's see...", AnswerStyle.withoutInternalMarkup("It depends on your schedule. Let's see... [tool]date.calc {\"date\":\"8 Nov\",\"days\":-3}[/tool"))
        assertEquals("Sure.", AnswerStyle.withoutInternalMarkup("Sure. <tool>{\"name\": \"time.now\"}"))
        assertEquals("[Paris](https://example.org) is the capital.", AnswerStyle.withoutInternalMarkup("[Paris](https://example.org) is the capital."))
    }

    @Test fun aLongReplyIsNotCutOffAtTheTokenLimit() {
        val e = CutOff(); core.useEngine(e)
        val text = reply(say("What would happen if the Moon suddenly disappeared?"))
        assertTrue(text.endsWith("much darker."), text)
        assertTrue("shrink to the Sun's smaller pull" in text, text)
        assertEquals(2, e.prompts.size)
        assertEquals("assistant", e.prompts[1][e.prompts[1].size - 2].role, "the continuation sees what was already said")
    }
}
