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
        model("""{"kind":"instruction","goal":"","lookup":[],"tool":"reminder.set"}""", "yes",
            """{"what":"call the plumber","when":"17 October 2025 at 9 am"}""",
            """{"what":"call the plumber","when":"tomorrow at 9 am"}""")
        val ev = say("remind me to call the plumber tomorrow at 9 am")
        assertEquals("call the plumber", reminders().single().title)
        assertEquals(LocalDateTime.of(2025, 10, 10, 9, 0), local(reminders().single().dueAt!!))
        assertTrue(core.goalEngine.lastRefusals.any { "17 October" in it }, core.goalEngine.lastRefusals.toString())
        assertTrue("remind you" in reply(ev))
    }

    @Test fun detailsTheToolCantUseAreFilledAgain() {
        model("""{"kind":"instruction","goal":"","lookup":[],"tool":"reminder.set"}""", "yes",
            """{"what":"call the plumber","when":"call the plumber"}""",
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
        model("""{"kind":"thinking_aloud","goal":"","lookup":[],"tool":"reminder.set"}""", "Sounds like a good idea.")
        say("Maybe I should start using reminders more often")
        model("""{"kind":"question","goal":"","lookup":[],"tool":"reminder.set"}""", "Paris.")
        say("what's the capital of France")
        assertTrue(reminders().isEmpty())
    }

    @Test fun aMisreadMessageIsCheckedBeforeAnythingChanges() {
        // The model calls a greeting an instruction to set a reminder; the separate yes/no check says no: nothing is set.
        val m = model("""{"kind":"instruction","goal":"","lookup":[],"tool":"reminder.set"}""", "no", "Good morning to you too.")
        val text = reply(say("Good morning! How's it going?"))
        assertTrue(reminders().isEmpty()); assertEquals("Good morning to you too.", text)
        assertEquals("root ::= \"yes\" | \"no\"", m.params[1].grammar)
        assertTrue(core.goalEngine.lastRefusals.any { "intent check" in it })
    }

    @Test fun anInstructionWithoutAToolGetsAFocusedChoice() {
        val m = model("""{"kind":"instruction","goal":"","lookup":[],"tool":"none"}""", "reminder.set", "yes",
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

    // ------------------------------------------------------------------------------------------------ Phase 3

    @Test fun everyArgumentRecordsWhereItCameFrom() {
        model("""{"kind":"instruction","goal":"diyas","lookup":["date of Diwali 2025"],"tools":["reminder.set"]}""", "yes", "20 October 2025",
            """{"what":"buy diyas","when":"3 days before"}""")
        say("Diwali's coming up, look up the date and remind me to buy diyas three days before it")
        val act = core.goalEngine.recent(conv).first().steps.last()
        val p = act.provenance!!
        assertEquals(listOf("owner"), (p["what"] as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') })
        val whenSrc = (p["when"] as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') }
        assertTrue("lookup:s1" in whenSrc && "calc" in whenSrc, "the date came from the lookup, the offset from a calculation: $whenSrc")
        assertEquals(LocalDateTime.of(2025, 10, 17, 9, 0), local(reminders().single().dueAt!!))
    }

    @Test fun paraphraseIsAllowedButInventionIsNot() {
        val e = core.goalEngine
        fun src(args: String, objective: String, steps: List<GoalStep> = emptyList()) =
            e.provenance(kotlinx.serialization.json.Json.parseToJsonElement(args) as JsonObject, objective, steps)
        // "prepare" → "start preparing for your exam": a word form and connecting words, not new facts... except "start", which is not the owner's.
        assertTrue((src("""{"what":"preparing for your exam"}""", "remind me to prepare for my exam")["what"] as kotlinx.serialization.json.JsonArray).isNotEmpty())
        assertTrue((src("""{"what":"call Ravi"}""", "remind me to call mom")["what"] as kotlinx.serialization.json.JsonArray).isEmpty(), "an invented name has no source")
        // Earlier messages are a source of their own.
        val ctx = src("""{"what":"dentist appointment"}""", "My dentist appointment is on 30 October.\nRemind me the day before.")
        assertEquals("context", (ctx["what"] as kotlinx.serialization.json.JsonArray).single().toString().trim('"'))
        // A note to the owner may name the obvious task ("renew" what "runs out"): derived. A message to someone else may not.
        val note = e.provenance(kotlinx.serialization.json.Json.parseToJsonElement("""{"what":"renew my car insurance"}""") as JsonObject,
            "My car insurance runs out on 5 November", emptyList(), tool = "reminder.set")
        assertEquals(setOf("derived", "owner"), (note["what"] as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') }.toSet())
        val sent = e.provenance(kotlinx.serialization.json.Json.parseToJsonElement("""{"text":"renew my car insurance"}""") as JsonObject,
            "My car insurance runs out on 5 November", emptyList(), tool = "sms.compose")
        assertTrue((sent["text"] as kotlinx.serialization.json.JsonArray).isEmpty())
        // A model that ignores the grammar and invents a name is refused and asked again.
        model("""{"kind":"instruction","goal":"","lookup":[],"tools":["reminder.set"]}""", "yes",
            """{"what":"call Ravi","when":"tomorrow at 9 am"}""", """{"what":"call mom","when":"tomorrow at 9 am"}""")
        say("remind me to call mom tomorrow at 9 am")
        assertEquals("call mom", reminders().single().title)
        assertTrue(core.goalEngine.lastRefusals.any { "Ravi" in it || "no source" in it }, core.goalEngine.lastRefusals.toString())
    }

    @Test fun anUnrelatedRecordIsNotEvidenceForAFact() {
        // The exam memory mentions "2025" but not Diwali: the lookup skips it and reads the web instead.
        core.memories.save(Memory(category = MemoryCategory.CORE_FACT, title = "Next exam", content = "My next examination is Data Structures on 28 October 2025.",
            source = MemorySource.OWNER_INSTRUCTION))
        model("""{"kind":"instruction","goal":"diyas","lookup":["Diwali 2025"],"tools":["reminder.set"]}""", "yes", "20 October 2025",
            """{"what":"buy diyas","when":"3 days before 20 October 2025"}""")
        say("look up Diwali and remind me to buy diyas three days before it")
        val lookup = core.goalEngine.recent(conv).first().steps.first()
        assertTrue("from the web" in lookup.result && "Diwali" in lookup.evidence, lookup.result)
        assertEquals(LocalDateTime.of(2025, 10, 17, 9, 0), local(reminders().single().dueAt!!))
        // A copied example lookup that shares only a generic word with the message is dropped.
        assertFalse(GoalEngine.about("Diwali's coming up, look up the date", "date of the next Chennai marathon"))
        assertTrue(GoalEngine.about("Find out when my next examination is", "date of my next examination"))
    }

    @Test fun anUnderstandingUnrelatedToTheMessageDrivesNoAction() {
        // The model restates a copied example as the goal: whatever it planned, nothing is changed.
        model("""{"kind":"instruction","goal":"marathon registration reminder","lookup":[],"tools":["reminder.set"]}""", "Sure.")
        say("Diwali's coming up and I need diyas")
        assertTrue(reminders().isEmpty())
        assertTrue(core.goalEngine.lastRefusals.any { "not about the message" in it })
    }

    @Test fun oneMessageCanDriveSeveralDependentTools() {
        model("""{"kind":"instruction","goal":"diyas","lookup":["date of Diwali 2025"],"tools":["task.add","reminder.set"]}""", "yes", "yes",
            "20 October 2025", """{"title":"diyas","list":"shopping"}""", """{"what":"buy diyas","when":"3 days before 20 October 2025"}""")
        val ev = say("Look up when Diwali is, put diyas on my shopping list and remind me to buy them three days before")
        assertEquals(listOf("memory.search", "web.search", "task.add", "reminder.set"), tools(ev))
        assertTrue(core.butler.open().any { it.dueAt == null && it.title.contains("diya", true) })
        assertEquals(LocalDateTime.of(2025, 10, 17, 9, 0), local(reminders().single().dueAt!!))
        val text = reply(ev); assertTrue("diyas" in text && "remind you" in text, text)
    }

    @Test fun anActionTheMessageDoesNotAskForIsLeftOutOfAMultiToolPlan() {
        model("""{"kind":"instruction","goal":"","lookup":[],"tools":["task.add","reminder.set"]}""", "yes", "no", """{"title":"coffee","list":"shopping"}""")
        say("we're out of coffee, stick it on the shopping list")
        assertTrue(reminders().isEmpty()); assertTrue(core.butler.open().any { it.title == "coffee" })
    }

    @Test fun statementsCommitmentsAndHypotheticalsNeverAct() {
        model("""{"kind":"statement","goal":"bill on Monday","lookup":[],"tools":["reminder.set"]}""", "Noted.")
        say("I'll pay the electricity bill on Monday.")
        model("""{"kind":"statement","goal":"weekend","lookup":[],"tools":["reminder.set"]}""", "Coorg is lovely.")
        say("If I had a free weekend I'd probably drive to Coorg.")
        assertTrue(reminders().isEmpty())
        assertEquals(MessageKind.STATEMENT, core.goalEngine.last?.kind)
    }

    @Test fun aDocumentIsWrittenFromResearchAndSavedWithoutOverwriting() {
        fun plan() = model("""{"kind":"instruction","goal":"Diwali document","lookup":["Diwali 2025"],"tools":["document.write"]}""", "yes",
            "20 October 2025", """{"title":"Diwali","content":""}""", "# Diwali\n\nDiwali 2025 falls on Monday, 20 October 2025 (web).")
        plan()
        val ev = say("Write me a short document about Diwali from what you can find online.")
        assertEquals(listOf("memory.search", "web.search", "document.write"), tools(ev))
        val doc = core.archive.documents.list().single()
        assertEquals("Diwali.md", doc.name)
        assertTrue("20 October 2025 (web)" in core.archive.documents.text(doc.id))
        val p = core.goalEngine.recent(conv).first().steps.last().provenance!!
        assertTrue((p["content"] as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') }.containsAll(listOf("composed", "lookup:s1")))
        assertTrue("Saved the document" in reply(ev))
        // Writing it again keeps the first one.
        plan(); say("Write me a short document about Diwali from what you can find online.")
        assertEquals(setOf("Diwali.md", "Diwali (2).md"), core.archive.documents.list().map { it.name }.toSet())
    }

    @Test fun aGoalStillRunsWhenTheConversationHasOutgrownTheContextWindow() {
        val m = ScriptedEngine(ctx = 1600, grammar = true)
        core.useEngine(m)
        repeat(30) { i -> m.reply("Reply $i: " + "a long answer about the weather and the weekend plans ".repeat(5)); say("question $i about the weekend and the weather forecast") }
        m.reply("""{"kind":"instruction","goal":"","lookup":[],"tools":["reminder.set"]}""", "yes", """{"what":"call mom","when":"tomorrow at 9 am"}""")
        val before = m.prompts.size
        say("remind me to call mom tomorrow at 9 am")
        assertEquals("call mom", reminders().single().title)
        assertTrue(m.prompts.drop(before).all { m.countTokens(it) <= 1600 }, "every planning prompt fits the window")
    }

    // ------------------------------------------------------------------------------------------------ Phase 4

    private val threeTools = """{"kind":"instruction","goal":"diyas","lookup":["date of Diwali 2025"],"tools":["task.add","reminder.set","document.write"]}"""
    private val threeToolText = "Look up when Diwali is, put diyas on my shopping list, remind me to buy them three days before, and save a short note about it"
    private fun threeToolReplies() = arrayOf(threeTools, "yes", "yes", "yes", "20 October 2025", """{"title":"diyas","list":"shopping"}""",
        """{"what":"buy diyas","when":"3 days before 20 October 2025"}""", """{"title":"Diwali","content":""}""", "# Diwali\n\nDiwali 2025 falls on 20 October 2025 (web).")

    @Test fun threeDependentToolsRunInOrderAfterTheLookup() {
        model(*threeToolReplies())
        val ev = say(threeToolText)
        assertEquals(listOf("memory.search", "web.search", "task.add", "reminder.set", "document.write"), tools(ev))
        assertTrue(core.butler.open().any { it.dueAt == null && it.title.contains("diya", true) })
        assertEquals(LocalDateTime.of(2025, 10, 17, 9, 0), local(reminders().single().dueAt!!))
        assertEquals("Diwali.md", core.archive.documents.list().single().name)
        val g = core.goalEngine.recent(conv).first()
        assertEquals(GoalState.COMPLETED, g.state); assertTrue(g.steps.all { it.status == GoalStepStatus.DONE })
        assertTrue(g.steps.filter { it.tool != null }.all { it.provenance != null }, "every action carries its provenance")
    }

    @Test fun aThreeToolTaskInterruptedByARestartResumesWithoutRepeatingFinishedActions() {
        model(*threeToolReplies())
        say(threeToolText)
        // Rewind to the state CLU would have been in had it stopped after the list entry: the reminder and note not yet run.
        val done = core.goalEngine.recent(conv).first()
        val steps = done.steps.mapIndexed { i, st -> if (i >= 2) st.copy(status = GoalStepStatus.PENDING, args = JsonObject(emptyMap()), fill = true, provenance = null) else st }
        reminders().forEach { core.butler.remove(it.id) }; core.archive.documents.list().forEach { core.archive.documents.delete(it.id) }
        GoalStore(core.db, core.clock::now).save(done.copy(state = GoalState.RUNNING, steps = steps))
        assertEquals(1, GoalStore(core.db, core.clock::now).recoverAfterRestart())
        model("""{"what":"buy diyas","when":"3 days before 20 October 2025"}""", """{"title":"Diwali","content":""}""", "# Diwali\n\nDiwali 2025 falls on 20 October 2025 (web).")
        val ev = say("continue the task")
        assertEquals(listOf("reminder.set", "document.write"), tools(ev), "the lookup and the list entry are not repeated")
        assertEquals(1, core.butler.open().count { it.dueAt == null && it.title.contains("diya", true) }, "still exactly one list entry")
        assertEquals(1, reminders().size); assertEquals(1, core.archive.documents.list().size)
        assertEquals(GoalState.COMPLETED, core.goalEngine.recent(conv).first().state)
    }

    @Test fun aConfirmationInTheMiddleOfAStepwiseTaskContinuesTheRest() {
        model("""{"kind":"instruction","goal":"Asha dinner","lookup":[],"tools":["note.send","reminder.set"]}""", "yes", "yes",
            """{"to":"Asha","text":"dinner at 8"}""")
        say("send Asha a note saying dinner at 8 and remind me about dinner with Asha today at 7 pm")
        assertTrue(sent.isEmpty() && reminders().isEmpty())
        assertEquals(GoalState.WAITING_FOR_EXTERNAL_RESULT, core.goalEngine.recent(conv).first().state)
        model("""{"what":"dinner with Asha","when":"today at 7 pm"}""")
        val ev = say("yes")
        assertEquals(listOf("dinner at 8"), sent); assertEquals("dinner with Asha", reminders().single().title)
        assertTrue("Sent the note" in reply(ev) && "remind you" in reply(ev), reply(ev))
    }

    @Test fun aMultiToolTaskStillRunsWhenTheConversationHasOutgrownTheWindow() {
        val m = ScriptedEngine(ctx = 1800, grammar = true)
        core.useEngine(m)
        // A long conversation already in the store (far more than the window holds).
        repeat(40) { i ->
            core.conversations.addMessage(conv, ai.clu.core.conversation.Role.USER, "question $i about my project notes and the budget figures")
            core.conversations.addMessage(conv, ai.clu.core.conversation.Role.ASSISTANT, "Reply $i: " + "a long answer about the project notes and the budget figures ".repeat(5))
        }
        m.reply("""{"kind":"instruction","goal":"eggs","lookup":[],"tools":["task.add","reminder.set"]}""", "yes", "yes",
            """{"title":"eggs","list":"shopping"}""", """{"what":"buy eggs","when":"tomorrow at 6 pm"}""")
        val before = m.prompts.size
        say("Add eggs to the list and remind me to buy them tomorrow at 6")
        assertTrue(core.butler.open().any { it.title == "eggs" }); assertEquals("buy eggs", reminders().single().title)
        assertTrue(m.prompts.drop(before).all { m.countTokens(it) <= 1800 }, "every prompt fits the window")
    }

    @Test fun anAmbiguousInstructionChangesNothingAndSaysWhatIsMissing() {
        // "Remind me about it" with nothing to refer to: the details have no source, so nothing is set and CLU says so.
        model("""{"kind":"instruction","goal":"","lookup":[],"tools":["reminder.set"]}""", "yes", """{"what":"the thing","when":"later"}""",
            """{"what":"the thing","when":"later"}""")
        val text = reply(say("remind me about it"))
        assertTrue(reminders().isEmpty())
        assertTrue(text.startsWith("I couldn't finish that") && "Nothing was changed" in text, text)
    }

    @Test fun aFollowUpInstructionUsesTheEarlierOne() {
        model("""{"kind":"instruction","goal":"milk","lookup":[],"tools":["task.add"]}""", "yes", """{"title":"milk","list":"shopping"}""")
        say("add milk to the shopping list")
        model("""{"kind":"instruction","goal":"bread on the shopping list","lookup":[],"tools":["task.add"]}""", "yes", """{"title":"bread","list":"shopping"}""")
        say("oh, and bread too")
        assertEquals(setOf("milk", "bread"), core.butler.open().filter { it.dueAt == null }.map { it.title }.toSet())
        assertEquals("shopping", core.goalEngine.recent(conv).first().steps.last().args["list"]?.toString()?.trim('"'))
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
        model("""{"kind":"instruction","goal":"","steps":[{"id":"s1","tool":"note.send","args":{"to":"Asha","text":"dinner at 8"}},
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
