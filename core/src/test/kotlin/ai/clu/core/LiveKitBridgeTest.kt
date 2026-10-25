package ai.clu.core

import ai.clu.core.config.Settings
import ai.clu.core.livekit.*
import ai.clu.core.memory.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.jupiter.api.Test

/** The LiveKit brain bridge uses Clu's own agent, memory and permissions; the server only carries text and audio. */
class LiveKitBridgeTest {
    private val core = newCore()
    private val engine = ScriptedEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, JsonObject>>())
    private val events = java.util.Collections.synchronizedList(mutableListOf<BridgeEvent>())
    private val conv by lazy { core.conversations.create(personaId = core.personas.active().id).id }
    private val bridge by lazy {
        RemoteVoiceBridge(core.agent, scope, { conv }, { core.auth.actorForSpeaker(null, speakerIdActive = false) },
            send = { t, p -> sent += t to Json.parseToJsonElement(p).jsonObject }, events = { events += it }, diag = core.diag)
    }

    init { core.useEngine(engine) }

    private fun transcript(turn: Long, text: String) = bridge.onData(VoiceProtocol.TRANSCRIPT, """{"v":1,"turn":$turn,"text":${JsonPrimitive(text)}}""")
    private fun replies(turn: Long) = synchronized(sent) { sent.toList() }.filter { it.first == VoiceProtocol.REPLY && it.second["turn"]!!.jsonPrimitive.long == turn }.map { it.second }
    private fun awaitFinal(turn: Long, ms: Long = 5000): List<JsonObject> {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { val r = replies(turn); if (r.any { it["final"]!!.jsonPrimitive.boolean }) return r; Thread.sleep(10) }
        fail("no final reply for turn $turn: $sent")
    }

    @Test fun transcriptIsAnsweredByLocalAgentSentenceBySentence() {
        engine.reply("Good evening. The reactor is stable and all systems are nominal. Anything else?")
        transcript(1, "Status report please")
        val r = awaitFinal(1)
        val texts = r.filter { !it["final"]!!.jsonPrimitive.boolean }.map { it["text"]!!.jsonPrimitive.content }
        assertTrue(texts.size >= 2, "streamed as several sentences: $texts")
        assertEquals("Good evening. The reactor is stable and all systems are nominal. Anything else?", texts.joinToString(" "))
        assertEquals((0 until r.size).toList(), r.map { it["seq"]!!.jsonPrimitive.int }, "ordered sequence numbers")
        assertFalse(r.last()["end_session"]!!.jsonPrimitive.boolean)
        // Same conversation history and persona as offline: the turn is stored, prompt built by Clu's PromptBuilder.
        val msgs = core.conversations.messages(conv)
        assertEquals(listOf("Status report please"), msgs.filter { it.role.name == "USER" }.map { it.content })
        assertTrue(engine.lastSystem.contains("Clu"))
    }

    @Test fun memoryIsUsedThroughTheBridge() {
        listOf("Car service" to "The car service is due in March.", "Sister's birthday" to "Her birthday is on 12 March.",
            "Gym" to "Leg day is on Wednesdays.", "Favourite tea" to "The owner likes masala chai tea with less sugar.").forEach { (t, c) ->
            core.memories.save(Memory(category = MemoryCategory.MANUAL, title = t, content = c, source = MemorySource.OWNER_SUPPLIED))
        }
        engine.reply("You like masala chai with less sugar.")
        transcript(1, "What tea do I like?")
        awaitFinal(1)
        assertTrue(engine.lastSystem.contains("masala chai"), "retrieved memory reached the local model's prompt")
    }

    @Test fun endOfTheLineEndsTheSessionAfterTheAgentHasSpoken() {
        transcript(7, "End of the line")
        val r = awaitFinal(7)
        assertTrue(r.last()["end_session"]!!.jsonPrimitive.boolean)
        assertTrue(r.any { it["text"]!!.jsonPrimitive.content.isNotBlank() }, "farewell is spoken")
        assertTrue(events.none { it is BridgeEvent.SessionEnded })
        bridge.onData(VoiceProtocol.SPOKEN, """{"v":1,"turn":7}""")
        assertTrue(events.any { it is BridgeEvent.SessionEnded && it.turn == 7L })
    }

    @Test fun interruptCancelsTheReplyInProgress() {
        val slow = object : ai.clu.core.llm.LlmEngine by engine {
            @Volatile var cancelled = false
            override fun cancel() { cancelled = true }
            override fun generate(messages: List<ai.clu.core.llm.ChatMessage>, params: ai.clu.core.llm.GenParams, onText: (String) -> Unit): ai.clu.core.llm.GenResult {
                repeat(200) { if (cancelled) return ai.clu.core.llm.GenResult("", ai.clu.core.llm.GenStats(stopReason = "cancelled"), "local"); onText("word "); Thread.sleep(10) }
                return ai.clu.core.llm.GenResult("done", ai.clu.core.llm.GenStats(), "local")
            }
        }
        core.useEngine(slow)
        transcript(3, "Tell me a very long story")
        Thread.sleep(150)
        assertTrue(bridge.busy)
        val t0 = System.nanoTime()
        bridge.onData(VoiceProtocol.INTERRUPT, """{"v":1,"turn":3}""")
        val end = System.currentTimeMillis() + 2000
        while (bridge.busy && System.currentTimeMillis() < end) Thread.sleep(5)
        val ms = (System.nanoTime() - t0) / 1e6
        assertFalse(bridge.busy, "reply stopped")
        assertTrue(slow.cancelled, "local model generation cancelled")
        assertTrue(ms < 500, "interrupt handled in ${"%.0f".format(ms)} ms")
        assertTrue(events.any { it is BridgeEvent.Interrupted && it.turn == 3L })
    }

    @Test fun malformedOrUnknownMessagesAreIgnored() {
        bridge.onData(VoiceProtocol.TRANSCRIPT, "not json")
        bridge.onData(VoiceProtocol.TRANSCRIPT, """{"v":1,"turn":1,"text":"   "}""")
        bridge.onData("something.else", """{"v":1}""")
        Thread.sleep(100)
        assertTrue(sent.isEmpty())
        assertTrue(core.diag.recent().any { it.message.contains("malformed") })
    }

    @Test fun voiceInputCannotDoSensitiveActions() {
        // Same rule as offline voice: voice-only sessions cannot complete sensitive actions without on-screen verification.
        val actor = core.auth.actorForSpeaker(null, speakerIdActive = false)!!
        assertTrue(actor.viaVoice)
        core.settings.set(Settings.UNKNOWN_POLICY, "ignore", verified = true)
        val strict = RemoteVoiceBridge(core.agent, scope, { conv }, { core.auth.actorForSpeaker(null, speakerIdActive = true) },
            send = { t, p -> sent += t to Json.parseToJsonElement(p).jsonObject })
        strict.onData(VoiceProtocol.TRANSCRIPT, """{"v":1,"turn":9,"text":"hello"}""")
        val r = awaitFinal(9)
        assertEquals("", r.single()["text"]!!.jsonPrimitive.content, "unknown speakers are not answered when the policy says ignore")
    }
}
