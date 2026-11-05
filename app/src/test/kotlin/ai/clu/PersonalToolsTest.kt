package ai.clu

import ai.clu.core.conversation.MessageMeta
import ai.clu.core.conversation.Role
import ai.clu.core.security.Actor
import ai.clu.core.sync.PlainKeyVault
import ai.clu.core.tools.ToolContext
import ai.clu.core.tools.ToolResult
import ai.clu.platform.AndroidPlatform
import ai.clu.tools.CluNotificationListener
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Android personal tools (reminders delivery, media keys, calls, messages, clipboard, brightness, notifications)
 * run against Android's framework classes in Robolectric: integration with the platform APIs, not a physical device.
 * Contact lookups with real contacts and real media players can't be exercised here (no contacts provider or player).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CluApp::class)
class PersonalToolsTest {
    companion object {
        @JvmStatic @BeforeClass fun hook() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } }
    }

    private val app get() = RuntimeEnvironment.getApplication() as CluApp
    private fun run(tool: String, vararg kv: Pair<String, String>, conv: String? = null): ToolResult = runBlocking {
        app.core.toolRegistry[tool]!!.run(JsonObject(kv.associate { it.first to JsonPrimitive(it.second) }),
            ToolContext(Actor.ownerInApp(), app.core.personas.active(), conv, System.currentTimeMillis()))
    }
    private fun started(): Intent? = shadowOf(app).nextStartedActivity

    @Test fun remindersAreScheduledWithAlarmManagerAndDeliveredAsNotifications() {
        val r = run("reminder.set", "what" to "stretch", "when" to "in 30 minutes")
        assertTrue(r.summary, r.success && r.verified)
        val alarm = shadowOf(app.getSystemService(AlarmManager::class.java)).nextScheduledAlarm
        assertNotNull("an alarm is set for the reminder", alarm)
        val item = app.core.butler.open().single { it.title == "stretch" }
        assertEquals(item.dueAt, alarm!!.triggerAtTime)
        // A reminder whose time has come is posted as a notification, once.
        app.core.butler.add("call the bank", dueAt = System.currentTimeMillis() - 1000)
        assertEquals(listOf("call the bank"), app.reminders.deliverDue())
        val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        assertTrue(posted.any { it.extras.getCharSequence("android.text") == "call the bank" && it.channelId == CluApp.CH_REMINDERS })
        assertEquals(emptyList<String>(), app.reminders.deliverDue())
    }

    @Test fun mediaKeysReachTheMediaSystem() {
        val am = app.getSystemService(AudioManager::class.java)
        val r = run("media.control", "action" to "next")
        assertTrue(r.success)
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_NEXT), shadowOf(am).dispatchedMediaKeyEvents.map { it.keyCode })
        assertEquals("no false 'paused'", "Nothing is playing.", run("media.control", "action" to "pause").summary)
        assertFalse(run("media.control", "action" to "dance").success)
    }

    @Test fun callsAndMessagesArePreparedForTheOwnerToSend() {
        val call = run("phone.call", "number" to "+91 98765 43210")
        assertTrue(call.success)
        started()!!.let { assertEquals(Intent.ACTION_DIAL, it.action); assertEquals("tel:%2B919876543210", it.data.toString()) }
        val sms = run("sms.compose", "number" to "9876543210", "text" to "Running late")
        assertTrue(sms.success); assertFalse("CLU can't confirm a message it didn't send", sms.verified)
        started()!!.let { assertEquals(Intent.ACTION_SENDTO, it.action); assertEquals("smsto:9876543210", it.data.toString()); assertEquals("Running late", it.getStringExtra("sms_body")) }
        val mail = run("email.compose", "to" to "priya@example.com", "subject" to "Notes", "text" to "Here they are")
        assertTrue(mail.success)
        assertTrue(started()!!.data.toString().startsWith("mailto:priya%40example.com?subject=Notes"))
    }

    @Test fun aContactNameWithoutPermissionAsksAndDoesNotPretend() {
        app.permissionWanted.value = null
        val r = run("phone.call", "name" to "Mom")
        assertFalse(r.success)
        assertEquals(Manifest.permission.READ_CONTACTS, app.permissionWanted.value)
        assertNull("nothing was dialled", started())
    }

    @Test fun clipboardGetsClusLastReply() {
        val conv = app.core.conversations.create("clip").id
        app.core.conversations.addMessage(conv, Role.ASSISTANT, "The meeting is at 4 pm in room 2.", MessageMeta())
        val r = run("clipboard.copy", conv = conv)
        assertTrue(r.verified)
        assertEquals("The meeting is at 4 pm in room 2.", app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun brightnessNeedsTheOwnersPermissionAndThenReallyChanges() {
        // Robolectric has no switch for "Modify system settings", so the permission check is the one thing given here.
        fun tool(allowed: Boolean) = ai.clu.tools.BrightnessTool(app) { allowed }
        fun go(allowed: Boolean, level: String) = runBlocking { tool(allowed).run(JsonObject(mapOf("level" to JsonPrimitive(level))), ToolContext(Actor.ownerInApp(), app.core.personas.active(), null, 0)) }
        val denied = go(false, "40")
        assertFalse(denied.success)
        assertEquals(Settings.ACTION_MANAGE_WRITE_SETTINGS, started()!!.action)
        val ok = go(true, "40")
        assertTrue(ok.summary, ok.success && ok.verified)
        assertEquals(102, Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        assertEquals(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE))
    }

    @Test fun notificationsNeedAccessAndAreSummarisedPerApp() {
        val r = run("notifications.read")
        assertFalse(r.success); assertTrue(r.summary.contains("notification access"))
        val s = CluNotificationListener.summarise(listOf(CluNotificationListener.Item("WhatsApp", "Rahul", "On my way", 3), CluNotificationListener.Item("WhatsApp", "Mom", "Call me", 2),
            CluNotificationListener.Item("Gmail", "Bank", "Your statement is ready", 1)))
        assertEquals("3 notifications. WhatsApp (2): Rahul: On my way; Mom: Call me. Gmail: Bank: Your statement is ready.", s)
    }
}
