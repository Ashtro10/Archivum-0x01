package ai.clu.core

import ai.clu.core.agent.AgentEvent
import ai.clu.core.agent.IntentRouter
import ai.clu.core.agent.TurnRequest
import ai.clu.core.butler.Butler
import ai.clu.core.butler.ReminderScheduler
import ai.clu.core.db.Schema
import ai.clu.core.tools.str
import ai.clu.core.util.ManualClock
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.*
import org.junit.jupiter.api.Test

/**
 * Butler: tasks, lists, reminders and the daily briefing. Unit tests (time parsing, recurrence, routing) and integration
 * through CLU's real agent with the real SQLite database (no language model is needed: these are explicit commands).
 */
class ButlerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    // Wednesday 2026-11-04 10:00 in Kolkata.
    private val now = LocalDateTime.of(2026, 11, 4, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private fun at(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()

    @Test fun parsesWhenARemindersIsDue() {
        assertEquals(LocalDateTime.of(2026, 11, 4, 10, 20), at(Butler.parseWhen("in 20 minutes", now, zone)!!.at))
        assertEquals(LocalDateTime.of(2026, 11, 4, 18, 0), at(Butler.parseWhen("at 6 pm", now, zone)!!.at))
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 0), at(Butler.parseWhen("tomorrow at 9", now, zone)!!.at))
        assertEquals(LocalDateTime.of(2026, 11, 5, 8, 0), at(Butler.parseWhen("at 8 am", now, zone)!!.at), "a time already past today means tomorrow")
        Butler.parseWhen("every day at 8 am", now, zone)!!.let { assertEquals("daily", it.recur); assertEquals(LocalDateTime.of(2026, 11, 5, 8, 0), at(it.at)) }
        Butler.parseWhen("every Monday at 10", now, zone)!!.let { assertEquals("weekly", it.recur); assertEquals(LocalDateTime.of(2026, 11, 9, 10, 0), at(it.at)) }
        assertEquals(LocalDateTime.of(2026, 11, 4, 19, 0), at(Butler.parseWhen("tonight", now, zone)!!.at))
        assertNull(Butler.parseWhen("whenever you like", now, zone))
    }

    @Test fun splitsWhatFromWhen() {
        assertEquals("call mom" to "tomorrow at 9", Butler.splitReminder("call mom tomorrow at 9", now, zone))
        assertEquals("stretch" to "in 10 minutes", Butler.splitReminder("in 10 minutes to stretch", now, zone))
        assertEquals("pick up the kids at school" to "at 4 pm", Butler.splitReminder("pick up the kids at school at 4 pm", now, zone), "a place stays in the task")
        assertEquals("take my medicine" to "every day at 8 am", Butler.splitReminder("take my medicine every day at 8 am", now, zone))
        assertNull(Butler.splitReminder("buy flowers", now, zone))
    }

    @Test fun routesButlerCommands() {
        fun r(t: String) = IntentRouter.route(t)!!
        r("remind me to call the dentist tomorrow at 10 am").let { assertEquals("reminder.set", it.tool); assertEquals("call the dentist", it.args.str("what")); assertEquals("tomorrow at 10 am", it.args.str("when")) }
        r("Clu, remind me in 20 minutes to check the oven").let { assertEquals("check the oven", it.args.str("what")); assertEquals("in 20 minutes", it.args.str("when")) }
        r("add milk and eggs to my shopping list").let { assertEquals("task.add", it.tool); assertEquals("milk and eggs", it.args.str("title")); assertEquals("shopping", it.args.str("list")) }
        r("put renew passport on my to-do list").let { assertEquals("task.add", it.tool); assertEquals("to-do", it.args.str("list")) }
        assertEquals("task.list", r("what's on my shopping list?").tool)
        assertEquals("task.list", r("what are my tasks").tool)
        assertEquals("task.done", r("mark renew passport as done").tool)
        r("I bought the milk").let { assertEquals("task.done", it.tool); assertEquals("shopping", it.args.str("list")) }
        assertEquals("task.done", r("remove eggs from my shopping list").tool)
        assertEquals("butler.briefing", r("what does my day look like?").tool)
        assertEquals("butler.briefing", r("brief me").tool)
        // Not butler commands: conversation stays with the model.
        assertNull(IntentRouter.route("I need to remember to be kinder to myself"))
        assertNotEquals("task.add", IntentRouter.route("add some humour to your answers")?.tool)
        assertNull(IntentRouter.route("mark my words, it will rain"))
    }

    @Test fun listsRemindersRecurrenceAndBriefing() {
        val clock = ManualClock(now); val core = newCore(clock = clock)
        val b = Butler(core.store) { zone }
        b.add("milk", "shopping"); b.add("eggs", "groceries"); b.add("renew passport", "to-do list")
        assertEquals(listOf("milk", "eggs"), b.open("shopping").map { it.title }, "grocery and shopping are the same list")
        assertEquals(1, b.open("tasks").size)
        val pill = b.add("take medicine", dueAt = Butler.parseWhen("every day at 8 am", now, zone)!!.at, recur = "daily")
        val call = b.add("call the bank", dueAt = now + 30 * 60_000L)
        assertTrue(b.due(now).isEmpty()); assertEquals(call.dueAt, b.nextDue(now))
        // 10:30: the call is due once.
        clock.time = now + 31 * 60_000L
        assertEquals(listOf("call the bank"), b.due(clock.time).map { it.title })
        b.delivered(b.due(clock.time).single(), clock.time)
        assertTrue(b.due(clock.time).isEmpty(), "delivered once, not again")
        assertFalse(b.get(call.id)!!.done, "a delivered reminder stays a task until it is done")
        // Next morning 08:01: the daily reminder fires and moves to the day after.
        clock.time = LocalDateTime.of(2026, 11, 5, 8, 1).atZone(zone).toInstant().toEpochMilli()
        val due = b.due(clock.time).single { it.id == pill.id }
        b.delivered(due, clock.time)
        assertEquals(LocalDateTime.of(2026, 11, 6, 8, 0), at(b.get(pill.id)!!.dueAt!!))
        // Briefing names overdue and listed items; done items disappear.
        val brief = b.briefing(clock.time)
        assertTrue("Overdue: call the bank" in brief && "Shopping (2): milk, eggs" in brief, brief)
        assertEquals("milk", b.complete("milk", "shopping")!!.title)
        assertNull(b.complete("bread", "shopping"), "nothing invented")
        assertFalse("milk" in b.briefing(clock.time))
    }

    @Test fun throughClusAgentWithARealDatabase() {
        val clock = ManualClock(now); val core = newCore(clock = clock)
        var rescheduled = 0
        core.reminderScheduler = ReminderScheduler { rescheduled++ }
        val conv = core.conversations.create("butler").id
        fun ask(t: String) = runBlocking { core.agent.respond(TurnRequest(conv, t, ai.clu.core.security.Actor.ownerInApp())).toList() }
            .filterIsInstance<AgentEvent.Done>().single().text
        assertTrue(ask("add bread, butter and jam to my shopping list").startsWith("Added bread, butter, jam"), "split into three items")
        assertEquals(3, core.butler.open("shopping").size)
        val r = ask("remind me to call the dentist in 2 hours")
        assertTrue(r.startsWith("I'll remind you to call the dentist today at"), r)
        assertEquals(1, rescheduled, "the platform was asked to schedule the alarm")
        val list = ask("what's on my shopping list?")
        assertTrue("bread, butter, jam" in list, list)
        assertTrue(ask("I bought the butter").startsWith("Done: butter"))
        assertTrue("call the dentist" in ask("what are my tasks"))
        assertTrue("Today: call the dentist" in ask("brief me"))
        // Without a platform that can ring, CLU says so instead of claiming a reminder.
        core.reminderScheduler = null
        assertTrue("can't ring" in ask("remind me to water the plants at 7 pm"))
    }

    @Test fun theFirstGreetingOfTheDayCarriesTheBriefingOnlyWhenSomethingIsDue() {
        val noon = java.time.LocalDateTime.of(2026, 11, 4, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val clock = ManualClock(noon); val core = newCore(clock = clock)
        val opened = ai.clu.core.proactive.ProactiveContext(appInForeground = true, appOpenedNow = true)
        val plain = core.proactive.plan(opened, noon)!!
        assertEquals("greeting", plain.kind); assertFalse("Today:" in plain.text, "nothing due: just a greeting")
        val core2 = newCore(clock = clock)
        core2.butler.add("dentist", dueAt = noon + 3 * 3_600_000L)
        val withDay = core2.proactive.plan(opened, noon)!!
        assertTrue("Today: dentist at" in withDay.text, withDay.text)
    }

    @Test fun upgradingAVersion6DatabaseAddsButlerItemsAndKeepsEverything() {
        val db = tempDb()
        Schema.migrations.take(6).forEach { step -> step.forEach { db.execute(it) } }; db.userVersion = 6
        db.execute("INSERT INTO diary_entries(id, entry_date, title, body, source, created_at, updated_at, deleted, hlc, origin) VALUES('d1','2026-11-01','Day','A good day','manual',1,1,0,'h','o')")
        val c = newCore(db = db)
        assertEquals(7, db.userVersion)
        assertEquals("A good day", c.diary.get("d1")!!.body)
        c.butler.add("first task"); assertEquals(1, c.butler.open().size)
    }
}

/** Routing of the Android personal tools (media, notifications, contacts, messages, clipboard, brightness): unit tests. */
class PersonalRoutesTest {
    private fun r(t: String) = IntentRouter.route(t)
    @Test fun routesDeviceCommands() {
        assertEquals("pause", r("pause the music")!!.args.str("action"))
        assertEquals("next", r("skip this song")!!.args.str("action"))
        assertEquals("previous", r("previous track")!!.args.str("action"))
        assertEquals("notifications.read", r("read my notifications")!!.tool)
        assertEquals("WhatsApp", r("any new notifications from WhatsApp?")!!.args.str("app"))
        r("call Mom")!!.let { assertEquals("phone.call", it.tool); assertEquals("Mom", it.args.str("name")) }
        assertEquals("phone.dial", r("call 98765 43210")!!.tool, "numbers still go to the dialer tool")
        r("text Rahul saying running ten minutes late")!!.let { assertEquals("sms.compose", it.tool); assertEquals("Rahul", it.args.str("name")); assertEquals("running ten minutes late", it.args.str("text")) }
        r("email Priya about the meeting notes")!!.let { assertEquals("email.compose", it.tool); assertEquals("Priya", it.args.str("to")) }
        assertEquals("Rahul", r("what's Rahul's number?")!!.args.str("name"))
        assertEquals("clipboard.copy", r("copy that to the clipboard")!!.tool)
        assertEquals("40", r("set brightness to 40%")!!.args.str("level"))
        assertEquals("down", r("make the screen darker")!!.args.str("direction"))
        assertEquals("on", r("turn on adaptive brightness")!!.args.str("adaptive"))
        assertEquals("device.settings", r("open notification access settings")!!.tool)
        // Conversation is not mistaken for commands.
        assertNull(r("call it a day"))
        assertNull(r("call me Ishaan"))
        assertNull(r("pause"), "a bare 'pause' may be meant for CLU's own speech")
        assertNull(r("stop"))
    }
}
