package ai.clu.core.settings

import ai.clu.core.newCore

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.IntentRouter
import ai.clu.core.agent.TurnRequest
import ai.clu.core.calendar.CalEvent
import ai.clu.core.calendar.CalendarLogic
import ai.clu.core.calendar.CalendarSource
import ai.clu.core.config.Settings
import ai.clu.core.notify.CallAnnouncer
import ai.clu.core.notify.Incoming
import ai.clu.core.notify.NotificationPolicy
import ai.clu.core.tools.str
import ai.clu.core.util.ManualClock
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.*
import org.junit.jupiter.api.Test

/** An in-memory calendar with the provider's semantics (ids, ranges by overlap), and permissions that can be denied. */
class FakeCalendar(var read: Boolean = true, var write: Boolean = true) : CalendarSource {
    val events = mutableListOf<CalEvent>(); var asked = 0; private var next = 100L
    override fun canRead() = read
    override fun canWrite() = write
    override fun requestAccess() { asked++ }
    override fun events(from: Long, to: Long) = events.filter { it.start < to && it.end > from }.sortedBy { it.start }
    override fun insert(title: String, start: Long, end: Long, location: String): Long? { val id = next++; events += CalEvent(id, title, start, end, location = location); return id }
    override fun update(id: Long, title: String?, start: Long?, end: Long?): Boolean {
        val i = events.indexOfFirst { it.id == id }; if (i < 0) return false
        events[i] = events[i].let { it.copy(title = title ?: it.title, start = start ?: it.start, end = end ?: it.end) }; return true }
    override fun delete(id: Long) = events.removeIf { it.id == id }
}

/** Calendar intelligence: unit tests of the logic, integration through CLU's agent with a provider-like calendar. */
class CalendarTest {
    private val zone = ZoneId.systemDefault()
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(4, 9) // Wednesday 4 November 2026, 09:00

    @Test fun rangesConflictsAndFreeSlots() {
        assertEquals("tomorrow", CalendarLogic.range("tomorrow", now, zone).label)
        assertEquals(at(5, 0), CalendarLogic.range("tomorrow", now, zone).from)
        CalendarLogic.range("this week", now, zone).let { assertEquals(at(4, 0), it.from); assertEquals(at(9, 0), it.to) }
        CalendarLogic.range("next 3 days", now, zone).let { assertEquals(at(7, 0), it.to) }
        assertEquals(at(6, 0), CalendarLogic.range("on Friday", now, zone).from)
        val ev = listOf(CalEvent(1, "Standup", at(4, 10), at(4, 10, 30)), CalEvent(2, "Design review", at(4, 10, 15), at(4, 11)),
            CalEvent(3, "Lunch", at(4, 13), at(4, 14)), CalEvent(4, "Holiday", at(4, 0), at(5, 0), allDay = true))
        assertEquals(listOf("Standup" to "Design review"), CalendarLogic.conflicts(ev).map { it.first.title to it.second.title }, "all-day events don't conflict")
        val today = CalendarLogic.range("today", now, zone)
        val free = CalendarLogic.freeSlots(ev, today, zone, now)
        assertEquals(listOf(at(4, 9) to at(4, 10), at(4, 11) to at(4, 13), at(4, 14) to at(4, 21)), free)
        assertEquals(listOf("Lunch"), CalendarLogic.busyAt(ev, at(4, 13, 30)).map { it.title })
        assertTrue(CalendarLogic.busyAt(ev, at(4, 12)).isEmpty())
        val said = CalendarLogic.describe(ev, today, zone)
        assertTrue("Standup overlaps Design review" in said && "all day Holiday" in said, said)
    }

    @Test fun routesNaturalCalendarRequests() {
        fun r(t: String) = IntentRouter.route(t)!!
        r("what's on my calendar tomorrow?").let { assertEquals("calendar.read", it.tool); assertEquals("tomorrow", it.args.str("range")) }
        assertEquals("calendar.read", r("do I have any meetings this week").tool)
        assertEquals("calendar.read", r("any conflicts next week?").tool)
        r("am I free tomorrow at 3 pm?").let { assertEquals("calendar.free", it.tool); assertEquals("tomorrow at 3 pm", it.args.str("at")) }
        r("when am I free on Friday").let { assertEquals("calendar.free", it.tool); assertEquals("on Friday", it.args.str("range")) }
        r("schedule a meeting with Priya tomorrow at 4 pm").let { assertEquals("calendar.create", it.tool); assertEquals("Meeting with Priya", it.args.str("title")); assertEquals("tomorrow at 4 pm", it.args.str("when")) }
        r("add dentist to my calendar on Friday at 5 pm").let { assertEquals("dentist", it.args.str("title")) }
        r("move my dentist appointment to Monday at 10").let { assertEquals("calendar.move", it.tool); assertEquals("dentist appointment", it.args.str("title")) }
        r("cancel the design review meeting").let { assertEquals("calendar.delete", it.tool) }
        assertEquals("butler.evening", r("how was my day?").tool)
        assertEquals("butler.evening", r("evening summary").tool)
        assertEquals("butler.briefing", r("morning briefing").tool)
        assertEquals("notifications.missed", r("what did I miss?").tool)
        r("reply to Rahul saying on my way").let { assertEquals("notifications.reply", it.tool); assertEquals("Rahul", it.args.str("from")) }
        assertEquals("WhatsApp", r("clear WhatsApp notifications").args.str("app"))
        r("turn on call announcements").let { assertEquals("calls.announce", it.tool); assertEquals("on", it.args.str("state")) }
        r("stop announcing notifications").let { assertEquals("notifications.announce", it.tool); assertEquals("off", it.args.str("state")) }
        // Not calendar: lists still go to the butler.
        assertEquals("task.done", r("remove eggs from my shopping list").tool)
    }

    @Test fun throughClusAgentWithPermissionsAndHonestFailures() {
        val clock = ManualClock(now); val core = newCore(clock = clock)
        val cal = FakeCalendar(read = false, write = false); core.calendarSource = cal
        val conv = core.conversations.create("cal").id
        fun ask(t: String) = runBlocking { core.agent.respond(TurnRequest(conv, t, ai.clu.core.security.Actor.ownerInApp())).toList() }.filterIsInstance<AgentEvent.Done>().single().text
        // Denied: CLU asks Android for access and says so; it never invents events.
        assertTrue("permission" in ask("what's on my calendar today?")); assertEquals(1, cal.asked)
        cal.read = true; cal.write = true
        cal.events += CalEvent(1, "Standup", at(4, 10), at(4, 10, 30)); cal.events += CalEvent(2, "Design review", at(4, 10, 15), at(4, 11))
        val today = ask("what's on my calendar today?")
        assertTrue("Standup" in today && "Conflict" in today, today)
        assertTrue("You're busy" in ask("am I free today at 10:20?"))
        assertTrue(ask("when am I free today?").startsWith("Free today: 9:00 AM–10:00 AM"))
        val made = ask("schedule a meeting with Priya tomorrow at 4 pm")
        assertTrue(made.startsWith("Added \"Meeting with Priya\" on Thursday 5 November at 4:00 PM"), made)
        assertTrue(cal.events.any { it.title == "Meeting with Priya" && it.start == at(5, 16) && it.end == at(5, 17) })
        assertTrue(ask("move my meeting with Priya to Friday at 11").startsWith("Moved"))
        assertEquals(at(6, 11), cal.events.single { it.title == "Meeting with Priya" }.start)
        assertTrue("couldn't find" in ask("move the board meeting to Friday at 9"), "nothing invented for an unknown event")
        // Deleting asks for confirmation first (irreversible); nothing is deleted before.
        // Deleting is irreversible: CLU asks in the conversation (no app dialog is needed), and acts only on the owner's yes.
        val q = ask("cancel the design review meeting")
        assertTrue(q.endsWith("Shall I go ahead?") || q.endsWith("?"), q); assertTrue(cal.events.any { it.title == "Design review" }, "nothing deleted before the yes")
        assertEquals("Okay, I won't.", ask("no")); assertTrue(cal.events.any { it.title == "Design review" })
        ask("cancel the design review meeting")
        ask("what's on my calendar today?") // the owner moved on: the pending question lapses
        assertFalse(ask("yes").startsWith("Deleted"), "a stale yes deletes nothing"); assertTrue(cal.events.any { it.title == "Design review" })
        ask("cancel the design review meeting")
        clock.time += 4 * 60_000L
        assertFalse(ask("yes").startsWith("Deleted"), "an expired question deletes nothing")
        ask("cancel the design review meeting")
        assertTrue(ask("yes, go ahead").startsWith("Deleted \"Design review\""))
        assertTrue(cal.events.none { it.title == "Design review" })
    }

    @Test fun remindBefore() {
        assertEquals(0, newCore().settings.int(Settings.CAL_REMIND_MIN), "off by default: the calendar app already alerts")
        assertEquals(ai.clu.core.config.SettingResult.Ok, newCore().settings.set(Settings.CAL_REMIND_MIN, 15))
    }
}

/** Notification announcement rules and call announcements: unit tests of the owner's controls. */
class NotificationPolicyTest {
    private val noon = LocalDateTime.of(2026, 11, 4, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val msg = Incoming("k1", "WhatsApp", "Rahul", "On my way", "msg", noon)
    private val promo = Incoming("k2", "ShopApp", "Sale", "50% off", "promo", noon)

    @Test fun rules() {
        val s = newCore().settings
        assertNull(NotificationPolicy.announcement(msg, s, noon, emptySet()), "off until the owner turns it on")
        s.set(Settings.NOTIF_ANNOUNCE, true)
        assertEquals("Message from Rahul on WhatsApp.", NotificationPolicy.announcement(msg, s, noon, emptySet()), "who and which app, not the text")
        assertNull(NotificationPolicy.announcement(promo, s, noon, emptySet()), "promotions aren't important by default")
        assertNull(NotificationPolicy.announcement(msg, s, noon, setOf(NotificationPolicy.onceKey(msg))), "announced once")
        s.set(Settings.NOTIF_READ_CONTENT, true)
        assertEquals("Message from Rahul on WhatsApp: On my way.", NotificationPolicy.announcement(msg, s, noon, emptySet()))
        s.set(Settings.NOTIF_APPS, JsonArray(listOf(JsonPrimitive("ShopApp"))))
        assertNotNull(NotificationPolicy.announcement(promo, s, noon, emptySet()), "chosen apps are announced")
        assertNull(NotificationPolicy.announcement(msg, s, noon, emptySet()), "with apps chosen, only those")
        s.set(Settings.NOTIF_MUTED, JsonArray(listOf(JsonPrimitive("shop"))))
        assertFalse(NotificationPolicy.visible(promo, s), "muted apps are never read or announced")
        assertNull(NotificationPolicy.announcement(promo, s, noon, emptySet()))
        val night = LocalDateTime.of(2026, 11, 4, 23, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        s.set(Settings.NOTIF_APPS, JsonArray(emptyList())); s.set(Settings.NOTIF_MUTED, JsonArray(emptyList()))
        assertNull(NotificationPolicy.announcement(msg.copy(postedAt = night), s, night, emptySet()), "quiet hours")
        assertEquals(listOf("k1"), NotificationPolicy.missed(listOf(msg, promo, msg.copy(key = "old", postedAt = noon - 7_200_000)), noon - 3_600_000, s).map { it.key })
    }
}

class CallAwarenessTest {
    @Test fun announce() {
        val noon = LocalDateTime.of(2026, 11, 4, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val s = newCore().settings
        assertNull(CallAnnouncer.text("Mom", "+911234", s, noon), "off by default")
        s.set(Settings.CALL_ANNOUNCE, true)
        assertEquals("Call from Mom.", CallAnnouncer.text("Mom", "+911234", s, noon))
        assertEquals("Call from an unknown number.", CallAnnouncer.text(null, "+919999", s, noon), "digits are never read out")
        assertEquals("Call from a private number.", CallAnnouncer.text(null, null, s, noon))
        s.set(Settings.CALL_UNKNOWN, "silent"); assertNull(CallAnnouncer.text(null, "+919999", s, noon))
        val night = LocalDateTime.of(2026, 11, 4, 23, 45).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertNull(CallAnnouncer.text("Mom", "+911234", s, night), "quiet hours")
    }
}
