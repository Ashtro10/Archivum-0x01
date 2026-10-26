package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.ChatTurns
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.Settings
import ai.clu.core.security.Actor
import ai.clu.core.tools.HttpResponse
import ai.clu.core.voice.ClueVoices
import ai.clu.core.voice.TtsSelection
import ai.clu.core.voice.VoiceConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Voice choice, speech-aware prompting and tool progress (host; mocked engine where noted). */
class VoiceAgentTest {
    private val core = newCore().also { it.settings.set(Settings.LEARNING_ALTERNATIVES, false) }

    @Test fun curatedVoiceChoiceDrivesTheEngineWithoutFallback() {
        assertEquals(ClueVoices.JOE, ClueVoices.current(core.settings), "Joe is the default voice")
        ClueVoices.select(core.settings, ClueVoices.LEWIS)
        assertEquals(TtsSelection.KOKORO, core.settings.string(Settings.TTS_ENGINE))
        assertEquals(ClueVoices.LEWIS, ClueVoices.current(core.settings))
        val cfg = VoiceConfig.from(core.settings)
        // Not downloaded: a clear problem, no route, and certainly not another voice.
        val missing = TtsSelection.plan(cfg, TtsSelection.Facts(setOf("tts-joe"), emptySet(), null))
        assertNull(missing.english); assertTrue(missing.problem!!.contains("Lewis is not downloaded"), missing.problem)
        val ok = TtsSelection.plan(cfg, TtsSelection.Facts(setOf(TtsSelection.KOKORO_PACK), emptySet(), null))
        assertEquals(TtsSelection.KOKORO_PACK, ok.english!!.packId)
        ClueVoices.select(core.settings, ClueVoices.JOE)
        assertEquals("tts-joe", core.settings.string(Settings.TTS_PIPER_VOICE))
        assertEquals(ClueVoices.JOE, ClueVoices.current(core.settings))
        core.settings.set(Settings.TTS_ENGINE, TtsSelection.KITTEN)
        assertNull(ClueVoices.current(core.settings), "an advanced engine is reported as such")
    }

    @Test fun spokenTurnsWarnTheModelAboutRecognitionErrorsAndAskForSpokenStyle() = runBlocking {
        val engine = ScriptedEngine().reply("Sure.", "Sure.")
        core.useEngine(engine)
        val conv = core.conversations.create().id
        core.agent.handle(TurnRequest(conv, "how far is kweens", Actor.ownerInApp(), viaVoice = true)) {}
        assertTrue(engine.lastSystem.contains("misheard"), "voice turn context")
        assertTrue(engine.lastSystem.contains("spoken aloud"))
        core.agent.handle(TurnRequest(conv, "hello", Actor.ownerInApp())) {}
        assertFalse(engine.lastSystem.contains("misheard"), "typed turns are not treated as transcripts")
    }

    @Test fun slowToolsShowProgressInTheChat() = runBlocking {
        val platform = TestPlatform()
        val c = newCore(platform = platform)
        platform.fakeHttp.routes["duckduckgo"] = HttpResponse(200, """{"AbstractText":"Queens is a borough of New York City.","AbstractURL":"https://en.wikipedia.org/wiki/Queens"}""")
        // MOCK model: asks for a search, then answers from the result.
        c.useEngine(ScriptedEngine().reply("<tool>{\"name\": \"web.search\", \"args\": {\"query\": \"Queens\"}}</tool>", "Queens is a New York borough (Wikipedia)."))
        val chat = ChatTurns(c.agent, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val seen = mutableListOf<String?>()
        val watcher = launch(Dispatchers.Default) { chat.state.collect { seen += it.status } }
        chat.send(TurnRequest(c.conversations.create().id, "what is queens", Actor.ownerInApp()), "what is queens")
        val st = withTimeout(5_000) { chat.state.first { !it.running && it.seq == 1L } }
        watcher.cancel()
        assertTrue("Searching the web…" in seen, seen.toString())
        assertNull(st.status)
        assertTrue(st.events.any { it is AgentEvent.ToolFinished && it.result.success })
    }
}
