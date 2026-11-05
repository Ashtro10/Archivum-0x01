package ai.clu

import ai.clu.core.calendar.CalEvent
import ai.clu.core.calendar.CalendarSource
import ai.clu.core.config.Settings
import ai.clu.core.security.Actor
import ai.clu.core.sync.PlainKeyVault
import ai.clu.core.tools.ToolContext
import ai.clu.core.tools.ToolResult
import ai.clu.platform.AndroidPlatform
import ai.clu.tools.AndroidCalendar
import ai.clu.tools.CluNotificationListener
import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.media.AudioManager
import android.net.Uri
import android.os.Process
import android.provider.CalendarContract
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A calendar provider with Android's contract (Instances by time window, Calendars, Events insert/update/delete). */
class FakeCalendarProvider : ContentProvider() {
    data class Ev(val id: Long, var title: String, var start: Long, var end: Long, val allDay: Int = 0, val calendar: Long = 1)
    companion object { val events = mutableListOf<Ev>(); var nextId = 500L }
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val c = MatrixCursor(projection ?: emptyArray())
        val seg = uri.pathSegments
        when {
            seg.firstOrNull() == "instances" -> { val from = seg[2].toLong(); val to = seg[3].toLong()
                events.filter { it.start < to && it.end > from }.sortedBy { it.start }.forEach { e -> c.addRow(projection!!.map { p -> when (p) {
                    CalendarContract.Instances.EVENT_ID -> e.id; CalendarContract.Instances.TITLE -> e.title; CalendarContract.Instances.BEGIN -> e.start
                    CalendarContract.Instances.END -> e.end; CalendarContract.Instances.ALL_DAY -> e.allDay; CalendarContract.Instances.EVENT_LOCATION -> ""
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME -> "Personal"; else -> null } }.toTypedArray()) } }
            seg.firstOrNull() == "calendars" -> c.addRow(arrayOf<Any?>(1L, 1))
        }
        return c
    }
    override fun insert(uri: Uri, v: ContentValues?): Uri {
        val e = Ev(nextId++, v!!.getAsString(CalendarContract.Events.TITLE), v.getAsLong(CalendarContract.Events.DTSTART), v.getAsLong(CalendarContract.Events.DTEND))
        events += e; return ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id)
    }
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int {
        val e = events.firstOrNull { it.id == ContentUris.parseId(uri) } ?: return 0
        v!!.getAsString(CalendarContract.Events.TITLE)?.let { e.title = it }; v.getAsLong(CalendarContract.Events.DTSTART)?.let { e.start = it }; v.getAsLong(CalendarContract.Events.DTEND)?.let { e.end = it }
        return 1
    }
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = if (events.removeIf { it.id == ContentUris.parseId(uri) }) 1 else 0
}

/**
 * Calendar provider bridge, notification replies and dismissal, call announcements and call-state awareness,
 * appointment alerts and the evening summary through the alarm path. Robolectric on Android 15 framework classes
 * (platform integration, not a physical device).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CluApp::class)
class AssistantIntegrationsTest {
    companion object {
        @JvmStatic @BeforeClass fun hook() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } }
    }
    private val app get() = RuntimeEnvironment.getApplication() as CluApp
    private fun run(tool: String, vararg kv: Pair<String, String>): ToolResult = runBlocking {
        app.core.toolRegistry[tool]!!.run(JsonObject(kv.associate { it.first to JsonPrimitive(it.second) }), ToolContext(Actor.ownerInApp(), app.core.personas.active(), null, System.currentTimeMillis()))
    }

    @Test fun calendarProviderReadWriteAndRevokedPermission() {
        Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
        FakeCalendarProvider.events.clear()
        val cal = app.core.calendarSource as AndroidCalendar
        // Not granted: nothing is read, CLU asks the visible screen for access.
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        app.permissionWanted.value = null
        val denied = run("calendar.read", "range" to "today")
        assertFalse(denied.success); assertEquals(Manifest.permission.READ_CALENDAR, app.permissionWanted.value)
        assertTrue(cal.events(0, Long.MAX_VALUE).isEmpty())
        // Granted: real provider queries.
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val now = System.currentTimeMillis()
        FakeCalendarProvider.events += FakeCalendarProvider.Ev(1, "Standup", now + 3_600_000, now + 5_400_000)
        assertEquals(listOf("Standup"), cal.events(now, now + 86_400_000).map { it.title })
        val id = cal.insert("Dentist", now + 7_200_000, now + 10_800_000)!!
        assertTrue(FakeCalendarProvider.events.any { it.id == id && it.title == "Dentist" })
        assertTrue(cal.update(id, start = now + 14_400_000, end = now + 18_000_000))
        assertEquals(now + 14_400_000, FakeCalendarProvider.events.single { it.id == id }.start)
        assertTrue(cal.delete(id)); assertFalse("a second delete reports failure", cal.delete(id))
        // Revoked later: back to "no access", no crash, no stale data.
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        assertFalse(cal.canRead()); assertTrue(cal.events(0, Long.MAX_VALUE).isEmpty())
    }

    private fun sbn(key: Int, title: String, text: String, reply: PendingIntent?): StatusBarNotification {
        val b = Notification.Builder(app, CluApp.CH_CLU).setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle(title).setContentText(text).setCategory(Notification.CATEGORY_MESSAGE)
        if (reply != null) b.addAction(Notification.Action.Builder(null, "Reply", reply).addRemoteInput(RemoteInput.Builder("reply_text").setLabel("Reply").build()).build())
        @Suppress("DEPRECATION")
        return StatusBarNotification("com.whatsapp", "com.whatsapp", key, null, Process.myUid(), 0, 0, b.build(), Process.myUserHandle(), System.currentTimeMillis())
    }

    @Test fun notificationRepliesUseTheAppsReplyBoxAndDismissalClears() {
        val svc = Robolectric.setupService(CluNotificationListener::class.java)
        svc.onListenerConnected()
        val replyPi = PendingIntent.getBroadcast(app, 7, Intent("test.REPLY").setPackage(app.packageName), PendingIntent.FLAG_MUTABLE)
        shadowOf(svc).addActiveNotification(sbn(1, "Rahul", "Are you coming?", replyPi))
        shadowOf(svc).addActiveNotification(sbn(2, "Mom", "Call me", null))
        val items = svc.snapshot()
        assertEquals(setOf("Rahul", "Mom"), items.map { it.title }.toSet())
        assertTrue(items.single { it.title == "Rahul" }.canReply); assertFalse(items.single { it.title == "Mom" }.canReply)
        assertTrue(svc.reply(items.single { it.title == "Rahul" }.key, "On my way"))
        val sent = shadowOf(app).broadcastIntents.last { it.action == "test.REPLY" }
        assertEquals("On my way", RemoteInput.getResultsFromIntent(sent)!!.getCharSequence("reply_text").toString())
        assertFalse("no reply box: no pretend reply", svc.reply(items.single { it.title == "Mom" }.key, "ok"))
        // Announcements off (default): a posted notification is not spoken, and nothing crashes.
        assertFalse(app.core.settings.bool(Settings.NOTIF_ANNOUNCE))
        svc.onNotificationPosted(sbn(3, "Priya", "Hi", null))
        assertEquals(2, svc.dismiss(null))
    }

    @Test fun callAnnouncementsNeedTheScreeningRoleAndCallsPauseClu() {
        app.permissionWanted.value = null
        val r = run("calls.announce", "state" to "on")
        assertTrue(r.success); assertTrue(app.core.settings.bool(Settings.CALL_ANNOUNCE))
        assertEquals("the call-screening role is requested", ai.clu.tools.CallsAnnounceTool.ROLE_REQUEST, app.permissionWanted.value)
        assertFalse(r.verified)
        // Call state: ringing stops the microphone, an active call stops speech too; back to normal afterwards.
        app.callState.onMode(AudioManager.MODE_RINGTONE); assertTrue(app.callState.inCall)
        app.callState.onMode(AudioManager.MODE_IN_CALL); assertTrue(app.callState.inCall)
        app.callState.onMode(AudioManager.MODE_NORMAL); assertFalse(app.callState.inCall)
        assertTrue(run("calls.announce", "state" to "off").success); assertFalse(app.core.settings.bool(Settings.CALL_ANNOUNCE))
    }

    @Test fun appointmentAlertsAndTheEveningSummaryArriveThroughTheAlarmPath() {
        val now = System.currentTimeMillis()
        app.core.calendarSource = object : CalendarSource {
            override fun canRead() = true; override fun canWrite() = false; override fun requestAccess() {}
            override fun events(from: Long, to: Long) = listOf(CalEvent(9, "Dentist", now + 10 * 60_000L, now + 40 * 60_000L)).filter { it.start < to && it.end > from }
            override fun insert(title: String, start: Long, end: Long, location: String): Long? = null
            override fun update(id: Long, title: String?, start: Long?, end: Long?) = false
            override fun delete(id: Long) = false
        }
        app.core.settings.set(Settings.CAL_REMIND_MIN, 15)
        assertEquals(listOf("event:Dentist"), app.reminders.deliverDue())
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertTrue(nm.allNotifications.any { it.extras.getCharSequence("android.title")?.startsWith("In 10 min: Dentist") == true })
        assertEquals("alerted once", emptyList<String>(), app.reminders.deliverDue())
        // Evening summary: enabled, its time passed today, no quiet hours → delivered once through the proactive planner.
        app.core.settings.set(Settings.QUIET_ENABLED, false)
        app.core.settings.set(Settings.EVENING_TIME, "00:00"); app.core.settings.set(Settings.EVENING_ENABLED, true)
        val before = nm.allNotifications.size
        app.reminders.deliverDue()
        assertTrue(nm.allNotifications.size > before)
        val msgs = app.core.store.db.query("SELECT kind FROM proactive_log") { it.string(0) }
        assertEquals(1, msgs.count { it == "evening" })
        app.reminders.deliverDue(); assertEquals("once a day", 1, app.core.store.db.query("SELECT kind FROM proactive_log") { it.string(0) }.count { it == "evening" })
    }
}
