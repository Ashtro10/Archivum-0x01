package ai.clu.core

import ai.clu.core.agent.*
import ai.clu.core.commands.*
import ai.clu.core.config.Settings
import ai.clu.core.llm.LlamaEngine
import ai.clu.core.persona.*
import ai.clu.core.security.*
import ai.clu.core.settings.RecordingTool
import ai.clu.core.tools.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*
import org.junit.jupiter.api.Test

class IntentRouterTest {
    private fun tool(t: String) = IntentRouter.route(t)?.tool
    private fun args(t: String) = IntentRouter.route(t)!!.args

    @Test fun routesExplicitCommands() {
        assertEquals("app.open", tool("Clu, open YouTube"))
        assertEquals("YouTube", args("open the YouTube app").str("app"))
        assertEquals("alarm.set", tool("set an alarm for 6:45 am"))
        assertEquals("6:45 am", args("set an alarm for 6:45 am").str("time"))
        assertEquals("alarm.set", tool("wake me up at 7"))
        assertEquals("timer.set", tool("set a timer for 10 minutes"))
        assertEquals("on", args("turn on the flashlight").str("state"))
        assertEquals("maps.distance", tool("how far is Pune from Mumbai?"))
        assertEquals("Pune" to "Mumbai", args("distance from Pune to Mumbai").let { it.str("from") to it.str("to") })
        assertEquals("maps.open", tool("navigate to Phoenix Mall"))
        assertEquals("time.now", tool("what time is it?"))
        assertEquals("device.status", tool("status report"))
        assertEquals("web.search", tool("search for cricket score"))
        assertEquals("memory.remember", tool("remember that my bike service is due in June"))
        assertEquals("persona.switch", tool("switch to Rick"))
        assertEquals("persona.switch", tool("back to Clu"))
        assertEquals("calc", tool("what is 12 * 7"))
        assertEquals("trains.lookup", tool("running status of train 12127"))
        assertEquals("device.volume", tool("set volume to 40%"))
    }

    @Test fun leavesConversationToTheModel() {
        assertNull(IntentRouter.route("I'm thinking about quitting my job. What do you think?"))
        assertNull(IntentRouter.route("What is the meaning of life?"))
        assertNull(IntentRouter.route("open a discussion about my career plans with me and help me prioritise"))
    }

    @Test fun clockAndDurationParsing() {
        assertEquals(6 to 45, ClockTimeParser.parse("6:45 am"))
        assertEquals(19 to 30, ClockTimeParser.parse("7.30 pm"))
        assertEquals(7 to 30, ClockTimeParser.parse("half past 7"))
        assertEquals(21 to 0, ClockTimeParser.parse("9 tonight"))
        assertEquals(12 to 0, ClockTimeParser.parse("noon"))
        assertNull(ClockTimeParser.parse("whenever"))
        assertEquals(600, ClockTimeParser.parseDurationSec("10 minutes"))
        assertEquals(5400, ClockTimeParser.parseDurationSec("1 hour 30 minutes"))
        assertEquals(1800, ClockTimeParser.parseDurationSec("half an hour"))
    }
}

class AgentTest {
    private val core = newCore()
    private val conv = core.conversations.create()
    private suspend fun ask(text: String, actor: Actor = Actor.ownerInApp(), voice: Boolean = false): List<AgentEvent> =
        core.agent.respond(TurnRequest(conv.id, text, actor, viaVoice = voice)).toList()
    private fun List<AgentEvent>.reply() = filterIsInstance<AgentEvent.Done>().single().text

    @Test fun modelToolLoopUsesRealResult(): Unit = runBlocking {
        val engine = ScriptedEngine().reply("<tool>{\"name\": \"calc\", \"args\": {\"expression\": \"17*23\"}}</tool>", "17 times 23 is 391.")
        core.useEngine(engine)
        val ev = ask("I need 17 multiplied by 23 for my invoice, quickly")
        assertTrue(ev.any { it is AgentEvent.ToolFinished && it.result.success && it.result.summary.contains("391") })
        assertEquals("17 times 23 is 391.", ev.reply())
        assertTrue(engine.prompts[1].any { it.content.contains("[Tool result: calc]") && it.content.contains("391") })
        assertEquals(listOf("calc"), ev.filterIsInstance<AgentEvent.Done>().single().meta.tools)
    }

    @Test fun toolFailureIsReportedNotHidden(): Unit = runBlocking {
        val engine = ScriptedEngine().reply("<tool>{\"name\": \"maps.find\", \"args\": {\"place\": \"Atlantis\"}}</tool>", "I couldn't find that place.")
        core.useEngine(engine)
        ask("where exactly is Atlantis located")
        assertTrue(engine.prompts[1].last().content.contains("FAILED"), "the model sees the failure")
    }

    @Test fun toolMarkupIsNeverShownToTheUser(): Unit = runBlocking {
        core.useEngine(ScriptedEngine().reply("<tool>{\"name\": \"time.now\", \"args\": {}}</tool>", "It's late."))
        val text = ask("is it late already?").filterIsInstance<AgentEvent.Text>().joinToString("") { it.delta }
        assertFalse(text.contains("<tool>"))
    }

    @Test fun deviceCommandsBypassTheModel(): Unit = runBlocking {
        val engine = ScriptedEngine()
        core.useEngine(engine)
        val ev = ask("what time is it?")
        assertTrue(ev.reply().startsWith("It is "))
        assertTrue(engine.prompts.isEmpty())
        assertEquals("tool", ev.filterIsInstance<AgentEvent.Done>().single().meta.source)
    }

    @Test fun noModelGivesHonestMessage(): Unit = runBlocking {
        val reply = ask("tell me something interesting").reply()
        assertTrue(reply.contains("language model isn't loaded"))
    }

    @Test fun guestCannotUseDeviceTools(): Unit = runBlocking {
        val t = RecordingTool("app.open"); core.toolRegistry.register(t)
        val guest = core.auth.actorForSpeaker(null, true)!!
        val r = ask("open YouTube", guest, voice = true).reply()
        assertTrue(r in core.settings.list(Settings.REFUSAL_LINES))
        assertEquals(0, t.runs)
        val friend = core.people.save(Person(name = "Ashutosh", role = PersonRole.TRUSTED, allowedTools = listOf("app.open")))
        ask("open YouTube", core.auth.actorForSpeaker(friend.id, true)!!, voice = true)
        assertEquals(1, t.runs, "a trusted person can use tools the owner allowed")
    }

    @Test fun guestDoesNotSeePrivateMemories(): Unit = runBlocking {
        core.memories.save(ai.clu.core.memory.Memory(category = ai.clu.core.memory.MemoryCategory.CORE_FACT, title = "Bank PIN hint", content = "Bank PIN hint is grandma's birthday",
            source = ai.clu.core.memory.MemorySource.OWNER_SUPPLIED))
        val engine = ScriptedEngine().reply("I can't share that.")
        core.useEngine(engine)
        ask("what's the bank PIN hint?", core.auth.actorForSpeaker(null, true)!!, voice = true)
        assertFalse(engine.lastSystem.contains("grandma"))
        assertTrue(engine.lastSystem.contains("not the owner"))
    }

    @Test fun characterSwitchByVoiceChangesInstructions(): Unit = runBlocking {
        core.characters.save(CharacterProfile(name = "Rick Sanchez", personality = "Burps mid-sentence, nihilistic scientist."))
        val engine = ScriptedEngine().reply("Wubba lubba dub dub.")
        core.useEngine(engine)
        assertTrue(ask("switch to Rick").reply().contains("Rick"))
        ask("Who are you?")
        assertTrue(engine.lastSystem.startsWith("You are Rick Sanchez"))
        assertTrue(engine.lastSystem.contains("nihilistic scientist"))
        assertTrue(ask("back to Clu").reply().contains("back"))
        assertTrue(core.personas.active().isClu)
        assertEquals("Clu", core.settings.string(Settings.CLU_NAME), "character never overwrote Clu's identity")
    }

    @Test fun characterToolPermissions(): Unit = runBlocking {
        val t = RecordingTool("app.open"); core.toolRegistry.register(t)
        val c = core.characters.save(CharacterProfile(name = "Optimus", canUseDeviceTools = false))
        core.personas.switchTo(c.id)
        ask("open YouTube")
        assertEquals(0, t.runs)
        core.characters.save(c.copy(canUseDeviceTools = true))
        ask("open YouTube")
        assertEquals(1, t.runs)
    }

    @Test fun customCommandsRunConfiguredActions(): Unit = runBlocking {
        val t = RecordingTool("device.flashlight"); core.toolRegistry.register(t)
        core.commands.save(CustomCommand(name = "Movie mode", kind = CommandKind.ACTION, triggers = listOf("movie mode", "cinema time"),
            steps = listOf(CommandStep("device.flashlight", buildJsonObject { put("state", "off") })), responses = listOf("Enjoy the film, {owner}.")))
        core.settings.set(Settings.OWNER_ADDRESS, "boss")
        val r = ask("cinema time").reply()
        assertEquals(1, t.runs)
        assertTrue(r.startsWith("Enjoy the film, boss."))
        core.commands.save(core.commands.list().first { it.name == "Movie mode" }.copy(enabled = false))
        ask("cinema time")
        assertEquals(1, t.runs)
    }

    @Test fun terminationPhraseEndsSession(): Unit = runBlocking {
        val ev = ask("End of the line.", voice = true)
        assertTrue(ev.any { it is AgentEvent.Control && it.command == "end_session" })
        assertTrue(ev.reply().isNotBlank())
    }

    @Test fun historyIsTrimmedToFitContext(): Unit = runBlocking {
        val engine = ScriptedEngine(ctx = 600)
        repeat(40) { engine.reply("Answer $it " + "x".repeat(200)) }
        core.useEngine(engine)
        repeat(20) { ask("Question $it " + "y".repeat(200)) }
        val last = engine.prompts.last()
        assertTrue(engine.countTokens(last) <= 600, "prompt must fit the context window")
        assertTrue(last.last().content.startsWith("Question 19"))
    }
}

/** End-to-end turn through CluCore with the real llama.cpp engine (tiny random model): plumbing, persistence, stats. */
class EndToEndLlamaTest {
    @Test fun fullTurnWithRealEngine(): Unit = runBlocking {
        val core = newCore()
        val model = File(System.getProperty("clu.testModel", "../native/build/test/tiny.gguf"))
        core.settings.set(Settings.MODEL_ACTIVE, core.models.register(model).id)
        core.settings.set(Settings.CONTEXT_LENGTH, 2048)
        core.settings.set(Settings.MAX_TOKENS, 40)
        val conv = core.conversations.create()
        val events = core.agent.respond(TurnRequest(conv.id, "Hello Clu, how are you today?", Actor.ownerInApp())).toList()
        val done = events.filterIsInstance<AgentEvent.Done>().single()
        assertEquals("local", done.meta.source)
        assertTrue(done.meta.stats.getValue("tokens") > 0)
        assertTrue(done.meta.stats.getValue("first_token_ms") >= 0)
        assertEquals(2, core.conversations.messages(conv.id).size)
        // Second turn reuses the KV cache for the shared prefix.
        val e2 = core.agent.respond(TurnRequest(conv.id, "Tell me more.", Actor.ownerInApp())).toList()
        assertTrue(e2.filterIsInstance<AgentEvent.Done>().single().meta.stats.getValue("reused_tokens") > 50)
        core.shutdown()
    }
}
