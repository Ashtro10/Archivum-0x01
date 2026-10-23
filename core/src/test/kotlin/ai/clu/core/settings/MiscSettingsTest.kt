package ai.clu.core.settings

import ai.clu.core.*
import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.TurnRequest
import ai.clu.core.config.*
import ai.clu.core.diag.Level
import ai.clu.core.llm.GenerationParams
import ai.clu.core.memory.*
import ai.clu.core.persona.PromptContext
import ai.clu.core.proactive.ProactiveContext
import ai.clu.core.security.Actor
import ai.clu.core.util.ManualClock
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.*
import org.junit.jupiter.api.Test

private fun at(h: Int, m: Int = 0, day: Int = 23) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

class ProactiveSettingsTest {
    private val clock = ManualClock(at(10))
    private val core = newCore(clock = clock)
    private val s = core.settings

    @Test fun enabled() {
        assertNotNull(core.proactive.plan(ProactiveContext(true, appOpenedNow = true)))
        clock.time = at(10, day = 24)
        s.set(Settings.PROACTIVE_ENABLED, false)
        assertNull(core.proactive.plan(ProactiveContext(true, appOpenedNow = true)))
    }

    @Test fun limits() {
        s.set(Settings.PROACTIVE_GAP, 2)
        assertEquals("greeting", core.proactive.plan(ProactiveContext(true, appOpenedNow = true))?.kind)
        clock.time = at(11)
        assertNull(core.proactive.plan(ProactiveContext(true, batteryPercent = 5)), "within minimum gap")
        clock.time = at(13)
        assertEquals("battery", core.proactive.plan(ProactiveContext(true, batteryPercent = 5))?.kind)
        clock.time = at(19)
        assertNull(core.proactive.plan(ProactiveContext(true, batteryPercent = 5)), "daily cap of 2 reached")
        s.set(Settings.PROACTIVE_MAX, 3)
        assertNotNull(core.proactive.plan(ProactiveContext(true, batteryPercent = 5)))
        clock.time = at(23, 30)
        s.set(Settings.PROACTIVE_MAX, 10)
        assertNull(core.proactive.plan(ProactiveContext(true, batteryPercent = 5)), "quiet hours")
    }

    @Test fun kinds() {
        s.set(Settings.PROACTIVE_GAP, 1); s.set(Settings.PROACTIVE_MAX, 10)
        core.memories.save(Memory(category = MemoryCategory.EXPERIENCE, title = "Physics exam", content = "Physics exam at 9am", eventAt = at(9),
            source = MemorySource.OWNER_SUPPLIED))
        clock.time = at(15)
        val f = core.proactive.plan(ProactiveContext(true))
        assertEquals("followup", f?.kind); assertTrue(f!!.text.contains("Physics exam"))
        clock.time = at(16, 30)
        assertNull(core.proactive.plan(ProactiveContext(true)), "follow-up not repeated, no other candidate in the afternoon")
        core.memories.save(Memory(category = MemoryCategory.EXPERIENCE, title = "Feeling sick", content = "I have a fever today", source = MemorySource.OWNER_SUPPLIED))
        clock.time = at(17, 40)
        assertEquals("wellbeing", core.proactive.plan(ProactiveContext(true))?.kind)
        clock.time = at(19)
        assertEquals("checkin", core.proactive.plan(ProactiveContext(true, lastInteractionAt = at(10)))?.kind)
        // Turning kinds off removes them.
        s.set(Settings.PROACTIVE_CHECKINS, false); s.set(Settings.PROACTIVE_WELLBEING, false); s.set(Settings.PROACTIVE_FOLLOWUPS, false); s.set(Settings.PROACTIVE_GREET_OPEN, false)
        clock.time = at(20, 30, day = 24)
        assertNull(core.proactive.plan(ProactiveContext(true, appOpenedNow = true, lastInteractionAt = 0)))
    }

    @Test fun delivery() {
        assertEquals(ServicePolicy.ProactiveDelivery.NOTIFICATION, ServicePolicy.proactiveDelivery(s, appInForeground = true))
        s.set(Settings.PROACTIVE_DELIVERY, "speak")
        assertEquals(ServicePolicy.ProactiveDelivery.SPEAK, ServicePolicy.proactiveDelivery(s, appInForeground = true))
        assertEquals(ServicePolicy.ProactiveDelivery.NOTIFICATION, ServicePolicy.proactiveDelivery(s, appInForeground = false))
    }

    @Test fun battery() {
        assertEquals("battery", core.proactive.plan(ProactiveContext(false, batteryPercent = 9))?.kind)
        clock.time = at(10, day = 25)
        assertNull(core.proactive.plan(ProactiveContext(false, batteryPercent = 9, charging = true)))
        s.set(Settings.AWARE_BATTERY, false)
        assertNull(core.proactive.plan(ProactiveContext(false, batteryPercent = 9)))
    }
}

class LearningSettingsTest {
    private val core = newCore()
    private val s = core.settings
    private fun system() = core.promptBuilder.system(PromptContext(core.personas.active(), emptyList(), emptyList(), core.clock.now(), true, "portable"))

    @Test fun enabled() {
        core.prefs.answerQuestion("q_verdict", "verdict_first")
        assertTrue(system().contains("state your verdict first"))
        core.prefs.set("detail", "concise", ai.clu.core.learning.PreferenceSource.MANUAL)
        val concise = GenerationParams.from(s, core.prefs, false).maxTokens
        s.set(Settings.LEARNING_ENABLED, false)
        assertFalse(system().contains("state your verdict first"))
        assertTrue(GenerationParams.from(s, core.prefs, false).maxTokens > concise, "preference stops affecting the token budget")
    }

    @Test fun alternatives(): Unit = runBlocking {
        s.set(Settings.LEARNING_ALT_EVERY, 5)
        repeat(4) { core.prefs.recordReply() }
        assertNull(core.prefs.nextAlternative())
        core.prefs.recordReply()
        val offer = assertNotNull(core.prefs.nextAlternative())
        // End-to-end: the agent offers two styles and the choice becomes a preference that changes later prompts.
        val engine = ScriptedEngine().reply("Short answer.", "A much longer, comprehensive answer.")
        core.useEngine(engine)
        val conv = core.conversations.create()
        var alt: AgentEvent.Alternatives? = null
        core.agent.handle(TurnRequest(conv.id, "Explain inflation", Actor.ownerInApp())) { if (it is AgentEvent.Alternatives) alt = it }
        assertNotNull(alt)
        assertEquals(offer.dimension, alt!!.offer.dimension)
        assertTrue(engine.prompts[1].first().content.contains(alt!!.offer.styleB), "second reply generated with the alternative style")
        core.agent.chooseAlternative(alt!!.messageId, alt!!.offer, chosenA = false, textChosen = alt!!.textB, persist = true)
        assertEquals(alt!!.offer.optionB, core.prefs.get(offer.dimension)!!.value)
        assertEquals("A much longer, comprehensive answer.", core.conversations.message(alt!!.messageId)!!.content)
        assertTrue(system().contains(core.prefs.dimensions.first { it.key == offer.dimension }.instructions.getValue(offer.optionB)))
        s.set(Settings.LEARNING_ALTERNATIVES, false)
        repeat(50) { core.prefs.recordReply() }
        assertNull(core.prefs.nextAlternative())
    }

    @Test fun questions() {
        s.set(Settings.LEARNING_Q_EVERY, 3)
        repeat(2) { core.prefs.recordReply() }
        assertNull(core.prefs.nextQuestion())
        core.prefs.recordReply()
        val q = assertNotNull(core.prefs.nextQuestion())
        core.prefs.answerQuestion(q.id, q.options[1].first)
        assertEquals(q.options[1].first, core.prefs.get(q.dimension)!!.value)
        repeat(3) { core.prefs.recordReply() }
        assertNotEquals(q.id, core.prefs.nextQuestion()?.id, "answered questions are not repeated")
        s.set(Settings.LEARNING_QUESTIONS, false)
        assertNull(core.prefs.nextQuestion())
    }
}

class DevSettingsTest {
    private val core = newCore()
    private val s = core.settings

    @Test fun diagnostics() {
        assertTrue(UiPolicy.visibleSettings(s, SettingCategory.MODEL).none { it.advanced })
        assertFalse(UiPolicy.diagnosticsVisible(s))
        s.set(Settings.DEV_DIAGNOSTICS, true)
        assertTrue(UiPolicy.diagnosticsVisible(s))
        assertTrue(UiPolicy.visibleSettings(s, SettingCategory.MODEL).any { it.key == Settings.TOP_K })
    }

    @Test fun logLevel() {
        core.diag.clear()
        core.diag.d("t", "debug line"); core.diag.i("t", "info line")
        assertEquals(listOf("info line"), core.diag.recent().map { it.message })
        s.set(Settings.DEV_LOG_LEVEL, "debug"); core.diag.d("t", "debug 2")
        assertTrue(core.diag.recent().any { it.message == "debug 2" })
        s.set(Settings.DEV_LOG_LEVEL, "error"); core.diag.w("t", "warn")
        assertTrue(core.diag.recent().none { it.level == Level.WARN })
    }

    @Test fun logCapacity() {
        s.set(Settings.DEV_LOG_CAP, 200)
        repeat(500) { core.diag.i("t", "line $it") }
        core.diag.trim()
        assertTrue(core.diag.count() <= 200, "count=${core.diag.count()}")
        core.diag.i("t", "pin=1234 and api_key: abcdef")
        assertTrue(core.diag.recent().first().message.contains("pin=<redacted>"))
        assertFalse(core.diag.recent().first().message.contains("abcdef"))
    }

    @Test fun showLatency() { assertFalse(UiPolicy.showLatency(s)); s.set(Settings.DEV_SHOW_LATENCY, true); assertTrue(UiPolicy.showLatency(s)) }
    @Test fun toolTraces() { assertTrue(UiPolicy.showToolTraces(s)); s.set(Settings.DEV_TOOL_TRACES, false); assertFalse(UiPolicy.showToolTraces(s)) }
}
