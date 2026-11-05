package ai.clu.core.settings

import ai.clu.core.newCore

import ai.clu.core.calendar.CalEvent
import ai.clu.core.config.Settings
import ai.clu.core.proactive.ProactiveContext
import ai.clu.core.util.ManualClock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.*
import org.junit.jupiter.api.Test

/** Evening summary and routine recognition: integration with CLU's real database, planner and proposals. */
class DayBriefTest {
    private val zone = ZoneId.systemDefault()
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun evening() {
        val clock = ManualClock(at(4, 9)); val core = newCore(clock = clock)
        val opened = ProactiveContext(appInForeground = false)
        core.butler.add("buy stamps"); core.butler.add("call the bank", dueAt = at(4, 11))
        clock.time = at(4, 15); core.butler.complete("buy stamps")
        clock.time = at(4, 21)
        assertNull(core.proactive.plan(opened, at(4, 21))?.takeIf { it.kind == "evening" }, "off until the owner turns it on")
        core.settings.set(Settings.EVENING_ENABLED, true)
        clock.time = at(4, 20); assertNull(core.proactive.plan(opened, at(4, 20))?.takeIf { it.kind == "evening" }, "not before its time (20:30)")
        clock.time = at(4, 21)
        val m = core.proactive.plan(opened, at(4, 21))!!
        assertEquals("evening", m.kind)
        assertTrue("Done today: buy stamps." in m.text && "Still open from today: call the bank." in m.text, m.text)
        assertNull(core.proactive.plan(opened, at(4, 21, 30))?.takeIf { it.kind == "evening" }, "once a day")
        // Proactive messages switched off: the evening summary, asked for explicitly, still comes; quiet hours still silence it.
        core.settings.set(Settings.PROACTIVE_ENABLED, false)
        assertEquals("evening", core.proactive.plan(opened, at(5, 20, 45))!!.kind)
        assertNull(core.proactive.plan(opened, at(6, 23, 0)), "quiet hours (22:30–07:00)")
        core.settings.set(Settings.EVENING_DAYS, "weekends")
        assertNull(core.dayBrief.nextEvening(at(6, 23)).takeIf { java.time.Instant.ofEpochMilli(it!!).atZone(zone).dayOfMonth != 7 }, "next is Saturday the 7th")
    }

    @Test fun sections() {
        val clock = ManualClock(at(4, 21)); val core = newCore(clock = clock)
        val cal = FakeCalendar(); core.calendarSource = cal
        cal.events += CalEvent(1, "Physics exam", at(5, 10), at(5, 12))
        core.diary.add("A long day at the lab.")
        core.notifiedApps = { listOf("WhatsApp", "Gmail", "WhatsApp") }
        val all = core.dayBrief.evening(at(4, 21))
        assertTrue("Physics exam" in all && "1 diary entry" in all, all)
        assertFalse("WhatsApp" in all, "notification apps are private: off by default")
        assertFalse("A long day" in all, "diary text is never repeated")
        core.settings.set(Settings.EVENING_NOTIFICATIONS, true); core.settings.set(Settings.EVENING_CALENDAR, false)
        val s = core.dayBrief.evening(at(4, 21))
        assertTrue("Notifications today from WhatsApp, Gmail." in s && "Physics" !in s, s)
        cal.read = false
        core.settings.set(Settings.EVENING_CALENDAR, true)
        assertFalse("calendar" in core.dayBrief.evening(at(4, 21)).lowercase().replace("nothing on your lists or calendar", ""), "no access: no invented calendar section")
    }
}

class RoutinesTest {
    private val zone = ZoneId.systemDefault()
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun propose() {
        val clock = ManualClock(at(1, 8)); val core = newCore(clock = clock)
        for (d in 1..4) { clock.time = at(d, 7, 50); core.butler.add("take vitamins", dueAt = at(d, 8)) }
        clock.time = at(2, 9); core.butler.add("water plants") // once: not a routine
        for (d in 1..5) core.store.db.execute("INSERT INTO tool_runs(id, tool, args, success, verified, output, started_at, duration_ms) VALUES(?,?,?,1,1,'',?,5)",
            listOf("r$d", "app.open", """{"app":"Spotify"}""", at(d, 19, d)))
        clock.time = at(6, 10)
        val made = core.routines.run(at(6, 10))
        assertEquals(setOf("You regularly set a reminder to \"take vitamins\" in the morning.", "You often open Spotify in the evening."), made.map { it.text }.toSet())
        assertTrue(made.all { it.kind == ai.clu.core.memory.ProposalKind.OBSERVATION }, "a guess is offered, not saved")
        assertEquals(0, core.memories.count(), "nothing becomes memory without approval")
        assertTrue(core.routines.run(at(6, 18)).isEmpty(), "once a day")
        // Rejected: never offered again.
        core.proposals.reject(made.first().id)
        core.store.setMeta("routines_last_run", "")
        assertTrue(core.routines.run(at(7, 10)).none { it.text == made.first().text })
        // Approved: becomes an (approved-inference) memory the owner can still edit or delete.
        val m = core.proposals.approve(made.last().id)!!
        assertEquals(ai.clu.core.memory.MemorySource.APPROVED_INFERENCE, m.source)
        // Switched off: nothing is looked at.
        core.settings.set(Settings.ROUTINES_ENABLED, false); core.store.setMeta("routines_last_run", "")
        assertTrue(core.routines.run(at(8, 10)).isEmpty())
    }
}
