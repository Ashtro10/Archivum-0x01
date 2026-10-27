package ai.clu

import ai.clu.core.conversation.MessageMeta
import ai.clu.core.conversation.Role
import ai.clu.core.conversation.SourceRef
import ai.clu.core.memory.ProposalKind
import ai.clu.core.sync.PlainKeyVault
import ai.clu.platform.AndroidPlatform
import ai.clu.ui.*
import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.view.MotionEvent
import android.os.SystemClock
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Stage 3.2: controls must respond to real taps — MotionEvents dispatched through the window's view hierarchy, exactly
 * as a finger tap arrives — not just to performClick(). Robolectric (Android framework code), not a physical device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CluApp::class)
class TouchDispatchTest {
    companion object {
        @JvmStatic @BeforeClass fun hook() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } }
    }

    private val app get() = RuntimeEnvironment.getApplication() as CluApp
    private fun idle(ms: Long = 50) { repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(ms / 10 + 1) } }
    private fun <T : Activity> launch(cls: Class<T>, intent: Intent? = null): T =
        (if (intent != null) Robolectric.buildActivity(cls, intent) else Robolectric.buildActivity(cls)).setup().get().also { idle() }
    private fun allViews(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else emptyList()
    private fun texts(a: Activity) = allViews(a.window.decorView).filterIsInstance<TextView>()

    /** A real tap at the centre of [v]: ACTION_DOWN + ACTION_UP dispatched from the window's root. */
    private fun tap(a: Activity, v: View) {
        // Like a finger: scroll the control into view first.
        var p = v.parent
        while (p != null && p !is android.widget.ScrollView) p = p.parent
        (p as? android.widget.ScrollView)?.let { sv ->
            val r = android.graphics.Rect(); v.getDrawingRect(r); sv.offsetDescendantRectToMyCoords(v, r)
            sv.scrollTo(0, (r.top - sv.height / 3).coerceAtLeast(0)); idle()
        }
        val loc = IntArray(2); v.getLocationInWindow(loc)
        val root0 = a.window.decorView
        assertTrue("tap point inside the window: $loc ${v.width}x${v.height} in ${root0.width}x${root0.height}",
            loc[1] + v.height / 2 in 0..root0.height && loc[0] + v.width / 2 in 0..root0.width)
        val x = loc[0] + v.width / 2f; val y = loc[1] + v.height / 2f
        val t = SystemClock.uptimeMillis(); val root = a.window.decorView
        assertTrue("view has a size to tap (${v.width}x${v.height})", v.width > 0 && v.height > 0)
        root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        root.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
        idle()
    }

    private fun textView(a: Activity, text: String) = texts(a).first { it.text.toString() == text }

    @Test fun settingsSectionsOpenWhenTheirTitleIsTapped() {
        val expected = mapOf("Diagnostics" to DiagnosticsActivity::class.java, "Connection" to ConnectionActivity::class.java,
            "Phone & tablet" to DevicesActivity::class.java, "Memory & diary" to SettingsCategoryActivity::class.java,
            "Search & internet" to SettingsCategoryActivity::class.java, "Conversation" to SettingsCategoryActivity::class.java)
        for ((title, cls) in expected) {
            val s = launch(SettingsActivity::class.java)
            tap(s, textView(s, title))
            val started = shadowOf(s).nextStartedActivity
            assertNotNull("tapping \"$title\" opened nothing", started)
            assertEquals(title, cls.name, started.component!!.className)
        }
        // The description line of a section is part of the same control.
        val s = launch(SettingsActivity::class.java)
        tap(s, texts(s).first { it.text.toString().startsWith("Request timings, speech recognition") })
        assertEquals(DiagnosticsActivity::class.java.name, shadowOf(s).nextStartedActivity.component!!.className)
    }

    private fun byDescription(a: Activity, d: String) = allViews(a.window.decorView).first { it.contentDescription?.toString() == d }

    /** Clickable views that cover (almost) the whole screen and sit above other controls would block every tap. */
    private fun assertNoBlockingOverlay(a: Activity) {
        val root = a.window.decorView
        val blockers = allViews(root).filter { v -> v !== root && v.isShown && (v.isClickable || v.isLongClickable) &&
            v.width >= root.width * 0.9 && v.height >= root.height * 0.6 && v !is android.widget.ListView && v !is android.widget.ScrollView }
        assertTrue("views that would swallow taps: ${blockers.map { "${it.javaClass.simpleName} ${it.width}x${it.height}" }}", blockers.isEmpty())
    }

    @Test fun chatControlsRespondToRealTapsBeforeAndAfterARequestAndAfterBackgrounding() {
        app.core.store.setMeta("onboarded", "1")
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); idle()
        val a = c.get()
        assertNoBlockingOverlay(a)
        tap(a, byDescription(a, "Conversations and settings"))
        assertTrue("☰ opens the menu", org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss(); idle()
        tap(a, byDescription(a, "Voice chat"))
        assertEquals(VoiceChatActivity::class.java.name, shadowOf(a).nextStartedActivity.component!!.className)
        // Send a message with a real tap on Send, then check everything still responds.
        allViews(a.window.decorView).filterIsInstance<EditText>().first().setText("what time is it?"); idle()
        tap(a, byDescription(a, "Send"))
        val conv = app.currentConversation()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) { idle(); if (app.core.conversations.messages(conv).size >= 2) break }
        assertEquals(2, app.core.conversations.messages(conv).size)
        repeat(2) { c.pause().stop(); idle(); c.restart().start().resume(); idle() }
        assertNoBlockingOverlay(a)
        tap(a, byDescription(a, "Conversations and settings"))
        assertTrue("☰ still opens after a reply and backgrounding", org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss(); idle()
        tap(a, byDescription(a, "Voice chat"))
        assertEquals(VoiceChatActivity::class.java.name, shadowOf(a).nextStartedActivity.component!!.className)
    }

    @Test fun voiceScreenControlsWorkAndTheHudNeverTakesTouches() {
        val v = launch(VoiceChatActivity::class.java)
        val hud = allViews(v.window.decorView).filterIsInstance<HexHudView>().single()
        assertFalse("the HUD is decoration: not clickable", hud.isClickable || hud.isLongClickable || hud.isFocusable)
        assertNoBlockingOverlay(v)
        // A tap on the HUD does nothing and doesn't stop the controls around it from working.
        while (shadowOf(v).nextStartedActivity != null) { } // the screen's own microphone-permission request on opening
        tap(v, hud)
        assertNull(shadowOf(v).nextStartedActivity); assertFalse(v.isFinishing)
        val before = app.core.settings.bool(ai.clu.core.config.Settings.SPEAK_RESPONSES)
        tap(v, byDescription(v, "Spoken replies on/off"))
        assertNotEquals("the speaker button toggles spoken replies", before, app.core.settings.bool(ai.clu.core.config.Settings.SPEAK_RESPONSES))
        tap(v, byDescription(v, "Text chat"))
        assertTrue("⌨ returns to the chat", v.isFinishing)
    }

    @Test fun connectionEngineCardsSelectOnTap() {
        app.core.settings.set(ai.clu.core.config.Settings.VOICE_MODE, "livekit")
        val c = launch(ConnectionActivity::class.java)
        tap(c, texts(c).first { it.text.toString().endsWith("This device") })
        assertEquals("tapping the on-device card's title selects it", "offline", app.core.settings.string(ai.clu.core.config.Settings.VOICE_MODE))
    }

    @Test fun theVoiceMainButtonStartsAndStopsListening() {
        app.core.settings.set(ai.clu.core.config.Settings.VOICE_MODE, "offline")
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val v = launch(VoiceChatActivity::class.java)
        app.voice.stop(); idle()
        assertFalse(app.voice.isRunning)
        tap(v, byDescription(v, "Unmute microphone"))
        assertTrue("a real tap on the main button starts the voice pipeline", app.voice.isRunning)
        app.voice.state.value.let { st -> assertTrue(st.running || st.error.isNotBlank()) }
        // On the JVM the native speech engines can't run, so the session may end with an error; either way, a second tap
        // (or the session ending) leaves it stopped, never stuck.
        val desc = allViews(v.window.decorView).first { it.contentDescription?.toString() in setOf("Mute microphone", "Unmute microphone", "Stop Clu") }
        if (app.voice.isRunning && desc.contentDescription == "Mute microphone") { tap(v, desc); assertFalse("a second tap stops it", app.voice.isRunning) }
        app.voice.stop()
    }

    @Test fun theHudDrawsNoTextInAnyState() {
        val v = launch(VoiceChatActivity::class.java)
        val hud = allViews(v.window.decorView).filterIsInstance<HexHudView>().single()
        val texts = mutableListOf<String>()
        val canvas = object : android.graphics.Canvas(android.graphics.Bitmap.createBitmap(hud.width.coerceAtLeast(1), hud.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)) {
            override fun drawText(text: String, x: Float, y: Float, paint: android.graphics.Paint) { texts += text }
            override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: android.graphics.Paint) { texts += text.toString() }
            override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: android.graphics.Paint) { texts += String(text, index, count) }
            override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: android.graphics.Paint) { texts += text }
            override fun drawTextOnPath(text: String, path: android.graphics.Path, h: Float, vv: Float, paint: android.graphics.Paint) { texts += text }
        }
        // The static frame is rendered into a cached bitmap (that is where the old labels were drawn): check it too.
        HexHudView::class.java.getDeclaredMethod("drawFrame", android.graphics.Canvas::class.java).apply { isAccessible = true }.invoke(hud, canvas)
        for (m in HexHudView.Mode.values()) { hud.mode = m; hud.level = 0.6f; hud.draw(canvas) }
        assertTrue("no text inside the hexagon: $texts", texts.isEmpty())
        // The state is still announced (outside the hexagon and to accessibility services).
        assertTrue(hud.contentDescription.toString().startsWith("Clu"))
    }
}
