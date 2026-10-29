package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.*
import ai.clu.core.security.Actor
import ai.clu.core.security.Sensitivity
import ai.clu.core.tools.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*
import org.junit.jupiter.api.Test

/** A device-like tool used to verify executor policies without Android. */
class RecordingTool(override val name: String = "test.action", override val confirmByDefault: Boolean = false) : Tool {
    override val description = "Test action"
    override val argsExample = "{}"
    override val sensitivity = Sensitivity.ROUTINE
    override val deviceAction = true
    var runs = 0
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { runs++; return ToolResult.ok("done") }
}

/** Mirrors the app's open-app policy logic, which lives in core so it can be tested. */
class ToolSettingsTest {
    private val core = newCore()
    private val s = core.settings
    private val ctx get() = ToolContext(Actor.ownerInApp(), core.personas.active(), null, core.clock.now())

    @Test fun appPolicy() {
        assertTrue(AppPolicy.allowed(s, "YouTube", "com.google.android.youtube"))
        s.set(Settings.APP_POLICY, "allowlist"); s.set(Settings.APP_ALLOWLIST, listOf("Spotify"))
        assertFalse(AppPolicy.allowed(s, "YouTube", "com.google.android.youtube"))
        assertTrue(AppPolicy.allowed(s, "Spotify", "com.spotify.music"))
        s.set(Settings.APP_ALLOWLIST, listOf("com.google.android.youtube"))
        assertTrue(AppPolicy.allowed(s, "YouTube", "com.google.android.youtube"))
    }

    @Test fun historyRetention(): Unit = runBlocking {
        val t = RecordingTool(); core.toolRegistry.register(t)
        core.tools.execute(t.name, JsonObject(emptyMap()), ctx)
        assertEquals(1, core.tools.history().size)
        s.set(Settings.TOOL_HISTORY_DAYS, 1)
        (core.clock as ai.clu.core.util.ManualClock).advance(2 * 86_400_000L)
        core.tools.purgeHistory()
        assertEquals(0, core.tools.history().size)
    }

    @Test fun maxSteps(): Unit = runBlocking {
        val t = RecordingTool(); core.toolRegistry.register(t)
        // Distinct calls: an exact repeat is refused by the agent controller anyway (see AgentAcceptanceTest).
        fun call(n: Int) = "<tool>{\"name\": \"test.action\", \"args\": {\"n\": $n}}</tool>"
        s.set(Settings.AGENT_MAX_STEPS, 2)
        core.useEngine(ScriptedEngine().reply(call(1), call(2), call(3), call(4), "Finished."))
        val conv = core.conversations.create()
        core.agent.handle(TurnRequest(conv.id, "do the thing repeatedly", Actor.ownerInApp())) {}
        assertEquals(2, t.runs, "agent must stop calling tools after the configured number of steps")
    }

    @Test fun perToolEnable(): Unit = runBlocking {
        val t = RecordingTool(); core.toolRegistry.register(t)
        assertTrue(core.tools.execute(t.name, JsonObject(emptyMap()), ctx).success)
        s.set("tool.test.action.enabled", false)
        val r = core.tools.execute(t.name, JsonObject(emptyMap()), ctx)
        assertFalse(r.success); assertTrue(r.summary.contains("disabled"))
        assertEquals(1, t.runs)
        assertTrue(core.tools.available(core.personas.active()).none { it.name == t.name })
    }

    @Test fun perToolConfirm(): Unit = runBlocking {
        val t = RecordingTool("test.purchase", confirmByDefault = true); core.toolRegistry.register(t)
        var asked = 0
        core.tools.confirmation = ConfirmationHandler { _, _, _ -> asked++; false }
        assertFalse(core.tools.execute(t.name, JsonObject(emptyMap()), ctx).success)
        assertEquals(1, asked); assertEquals(0, t.runs)
        s.set("tool.test.purchase.confirm", "never")
        assertTrue(core.tools.execute(t.name, JsonObject(emptyMap()), ctx).success)
        val routine = RecordingTool("test.routine"); core.toolRegistry.register(routine)
        assertTrue(core.tools.execute(routine.name, JsonObject(emptyMap()), ctx).success)
        assertEquals(1, asked, "routine actions don't ask by default")
        s.set("tool.test.routine.confirm", "always")
        assertFalse(core.tools.execute(routine.name, JsonObject(emptyMap()), ctx).success)
        assertEquals(2, asked)
    }
}

class NetworkSettingsTest {
    private val platform = TestPlatform()
    private val core = newCore(platform = platform)
    private val s = core.settings
    private val ctx get() = ToolContext(Actor.ownerInApp(), core.personas.active(), null, core.clock.now())
    private val wikiBody = """{"query":{"search":[{"title":"Pune","snippet":"<span>Pune</span> is a city in Maharashtra"}]}}"""

    @Test fun master(): Unit = runBlocking {
        platform.fakeHttp.routes["wikipedia.org"] = ai.clu.core.tools.HttpResponse(200, wikiBody)
        s.set(Settings.SEARCH_PROVIDER, "wikipedia")
        assertTrue(core.tools.execute("web.search", buildJsonObject { put("query", "Pune") }, ctx).success)
        s.set(Settings.NET_ENABLED, false)
        val before = platform.fakeHttp.requests.size
        val r = core.tools.execute("web.search", buildJsonObject { put("query", "Pune") }, ctx)
        assertFalse(r.success); assertTrue(r.summary.contains("turned off"))
        assertEquals(before, platform.fakeHttp.requests.size, "no request may be sent when internet access is off")
    }

    @Test fun categories(): Unit = runBlocking {
        s.set(Settings.NET_MAPS, false)
        assertTrue(core.tools.execute("maps.distance", buildJsonObject { put("from", "Pune"); put("to", "Mumbai") }, ctx).summary.contains("maps is turned off"))
        s.set(Settings.NET_TRAVEL, false)
        assertTrue(core.tools.execute("trains.lookup", buildJsonObject { put("query", "12127") }, ctx).summary.contains("travel information is turned off"))
        s.set(Settings.NET_WEB, false)
        assertTrue(core.tools.execute("web.search", buildJsonObject { put("query", "x") }, ctx).summary.contains("web research is turned off"))
        assertEquals("Internet use for downloads is turned off in settings.", core.network.check(NetCategory.DOWNLOADS).let { s.set(Settings.NET_DOWNLOADS, false); core.network.check(NetCategory.DOWNLOADS) })
        assertTrue(platform.fakeHttp.requests.isEmpty())
    }

    @Test fun searchProvider(): Unit = runBlocking {
        platform.fakeHttp.routes["wikipedia.org"] = ai.clu.core.tools.HttpResponse(200, wikiBody)
        core.tools.execute("web.search", buildJsonObject { put("query", "Pune") }, ctx)
        assertTrue(platform.fakeHttp.requests.any { it.contains("api.duckduckgo.com") }, "keyless default queries Instant Answers (documented API) ...")
        assertTrue(platform.fakeHttp.requests.none { it.contains("html.duckduckgo.com") }, "... and never scrapes result pages")
        platform.fakeHttp.requests.clear()
        s.set(Settings.SEARCH_PROVIDER, "wikipedia")
        val r = core.tools.execute("web.search", buildJsonObject { put("query", "Pune") }, ctx)
        assertTrue(platform.fakeHttp.requests.none { it.contains("duckduckgo") })
        assertTrue(r.summary.contains("Pune is a city in Maharashtra"))
        s.set(Settings.SEARCH_PROVIDER, "brave"); platform.fakeHttp.requests.clear()
        val noKey = core.tools.execute("web.search", buildJsonObject { put("query", "Pune") }, ctx)
        assertFalse(noKey.success); assertTrue(platform.fakeHttp.requests.isEmpty(), "Brave without a key sends nothing")
        s.set(Settings.SEARCH_PROVIDER, "searxng"); s.set(Settings.SEARXNG_URL, "https://searx.example.org")
        core.tools.execute("web.search", buildJsonObject { put("query", "Pune") }, ctx)
        assertTrue(platform.fakeHttp.requests.any { it.startsWith("https://searx.example.org/search?") })
    }

    @Test fun searchNeverFabricates(): Unit = runBlocking {
        // All providers fail -> the tool reports failure instead of inventing results.
        val r = core.tools.execute("web.search", buildJsonObject { put("query", "anything") }, ctx)
        assertFalse(r.success); assertFalse(r.verified)
    }

    @Test fun externalAi(): Unit = runBlocking {
        platform.fakeHttp.routes["example.org"] = ai.clu.core.tools.HttpResponse(200, """{"choices":[{"message":{"content":"Deep answer."}}]}""")
        assertFalse(core.tools.execute("external.ai", buildJsonObject { put("question", "q") }, ctx).success, "off by default")
        s.set(Settings.EXT_AI_MODE, "on_request"); s.set(Settings.EXT_AI_URL, "https://example.org"); s.set(Settings.EXT_AI_MODEL, "big-model")
        platform.secrets["external_ai_key"] = "sk-secretvalue123456789"
        val r = core.tools.execute("external.ai", buildJsonObject { put("question", "q") }, ctx)
        assertTrue(r.success); assertTrue(r.summary.contains("Deep answer."))
        assertTrue(platform.fakeHttp.requests.last().contains("https://example.org/v1/chat/completions"))
        assertTrue(platform.fakeHttp.requests.last().contains("\"model\":\"big-model\""))
        assertIs<SettingResult.Invalid>(s.set(Settings.EXT_AI_URL, "not a url"))
        // The API key must never reach the diagnostics log.
        assertTrue(core.diag.recent().none { it.message.contains("sk-secretvalue") })
    }

    @Test fun shareMemories(): Unit = runBlocking {
        platform.fakeHttp.routes["example.org"] = ai.clu.core.tools.HttpResponse(200, """{"choices":[{"message":{"content":"ok"}}]}""")
        s.set(Settings.EXT_AI_MODE, "auto"); s.set(Settings.EXT_AI_URL, "https://example.org")
        core.memories.save(ai.clu.core.memory.Memory(category = ai.clu.core.memory.MemoryCategory.CORE_FACT, title = "Salary", content = "My salary is 12 lakh",
            source = ai.clu.core.memory.MemorySource.OWNER_SUPPLIED))
        val leak = "<tool>{\"name\": \"external.ai\", \"args\": {\"question\": \"Owner salary is 12 lakh; should he switch jobs?\"}}</tool>"
        core.useEngine(ScriptedEngine().reply(leak, "Done."))
        val conv = core.conversations.create()
        core.agent.handle(TurnRequest(conv.id, "Should I switch jobs? Give me a deep analysis", Actor.ownerInApp())) {}
        val sent = platform.fakeHttp.requests.last()
        assertFalse(sent.contains("12 lakh"), "memories must not be sent externally when sharing is off")
        assertTrue(sent.contains("Should I switch jobs?"))
        s.set(Settings.EXT_AI_SHARE_MEMORY, true)
        core.useEngine(ScriptedEngine().reply(leak, "Done."))
        core.agent.handle(TurnRequest(conv.id, "Should I switch jobs? Give me a deep analysis", Actor.ownerInApp())) {}
        assertTrue(platform.fakeHttp.requests.last().contains("12 lakh"))
    }

    @Test fun showSources() {
        val meta = ai.clu.core.conversation.MessageMeta(source = "home_device", tools = listOf("web.search"), stats = mapOf("network" to 1.0))
        assertEquals("Home device's model · used internet · web.search", UiPolicy.sourceBadge(s, meta))
        val searched = meta.copy(source = "local", sources = listOf(ai.clu.core.conversation.SourceRef("A", "https://a.example/x"),
            ai.clu.core.conversation.SourceRef("B", "https://b.example/y")))
        // Stage 4: answer first; the sources are one collapsed line under the answer, not a count in the badge.
        assertEquals("Searched the web · On-device model", UiPolicy.sourceBadge(s, searched))
        assertEquals("Sources (2)", UiPolicy.sourcesSummary(searched.sources))
        assertEquals("Search unavailable · answered without the web", UiPolicy.sourceBadge(s, meta.copy(tools = emptyList(), error = "search_failed")))
        s.set(Settings.SHOW_SOURCES, false)
        assertNull(UiPolicy.sourceBadge(s, meta))
    }

    @Test fun offlineBehavior(): Unit = runBlocking {
        assertEquals("local_answer", s.string(Settings.OFFLINE_BEHAVIOR), "default: say it's offline, then answer from local knowledge")
        s.set(Settings.OFFLINE_BEHAVIOR, "explain")
        platform.online = false
        val conv = core.conversations.create()
        val engine = ScriptedEngine().reply("I'm offline, so this may be outdated: Pune is in Maharashtra.")
        core.useEngine(engine)
        var reply = ""
        core.agent.handle(TurnRequest(conv.id, "search for Pune news", Actor.ownerInApp())) { if (it is AgentEvent.Done) reply = it.text }
        assertEquals("No internet connection is available right now.", reply)
        assertTrue(engine.prompts.isEmpty(), "explain mode does not call the model")
        s.set(Settings.OFFLINE_BEHAVIOR, "local_answer")
        core.agent.handle(TurnRequest(conv.id, "search for Pune news", Actor.ownerInApp())) { if (it is AgentEvent.Done) reply = it.text }
        assertTrue(reply.startsWith("I'm offline"))
        assertTrue(engine.prompts.last().any { it.content.contains("OFFLINE") })
        assertTrue(engine.lastSystem.contains("The device is OFFLINE"))
    }
}
