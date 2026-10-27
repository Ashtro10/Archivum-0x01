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

/** Stage 3.1: chat screen lifecycle on Android framework code (Robolectric) — backgrounding, recreation, no duplicates, no stuck loading state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CluApp::class)
class Stage31LifecycleTest {
    companion object {
        @JvmStatic @BeforeClass fun hook() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } }
    }

    private val app get() = RuntimeEnvironment.getApplication() as CluApp
    private fun idle(ms: Long = 50) { repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(ms / 10 + 1) } }
    private fun <T : Activity> launch(cls: Class<T>, intent: Intent? = null): T =
        (if (intent != null) Robolectric.buildActivity(cls, intent) else Robolectric.buildActivity(cls)).setup().get().also { idle() }
    private fun allViews(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else emptyList()
    private fun texts(a: Activity) = allViews(a.window.decorView).filterIsInstance<TextView>()

    private fun send(a: Activity, text: String) {
        val views = allViews(a.window.decorView)
        views.filterIsInstance<EditText>().first().setText(text)
        views.filterIsInstance<TextView>().first { it.contentDescription == "Send" }.performClick()
    }

    @Test fun backgroundingAndRecreatingNeitherDuplicatesNorResendsNorLeavesLoading() {
        app.core.store.setMeta("onboarded", "1")
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        send(c.get(), "what time is it?")
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) { idle(); if (app.core.conversations.messages(app.currentConversation()).size >= 2) break }
        val conv = app.currentConversation()
        assertEquals(2, app.core.conversations.messages(conv).size)
        // Home, then back (background → foreground), several times.
        repeat(3) { c.pause().stop(); idle(); c.restart().start().resume(); idle() }
        assertEquals("nothing re-sent or duplicated after returning", 2, app.core.conversations.messages(conv).size)
        // Rotation / process-style recreation of the screen.
        c.recreate(); idle()
        assertEquals(2, app.core.conversations.messages(conv).size)
        assertEquals("the same conversation is shown", conv, app.currentConversation())
        val texts = texts(c.get()).map { it.text.toString() }
        assertTrue("the reply is shown after recreation", texts.any { it.startsWith("It is") })
        assertFalse("no stuck loading indicator", texts.contains("Thinking…"))
        assertFalse(app.core.chat.state.value.running)
        // The next message still works.
        send(c.get(), "what time is it?")
        val d2 = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < d2) { idle(); if (app.core.conversations.messages(conv).size >= 4) break }
        assertEquals(4, app.core.conversations.messages(conv).size)
    }

    @Test fun diagnosticsShowsUnitsGapsAndAShareablePerformanceReport() {
        app.core.diag.clearPerf()
        app.core.diag.perf("app_create_ms", 812.0)
        val d = launch(DiagnosticsActivity::class.java)
        val t = texts(d).map { it.text.toString() }
        assertTrue(t.any { it.startsWith("Cold start (app process ready) (app_create_ms): p50 812 ms") })
        assertTrue(t.contains("Stop/interruption → playback silenced (app side) (stop_to_silence_ms): not measured yet"))
        assertTrue(t.any { it.startsWith("Battery: not measured by Clu") })
        val share = allViews(d.window.decorView).filterIsInstance<Button>().first { it.text.toString() == "Share performance report" }
        share.performClick(); idle()
        val clip = (d.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip!!.getItemAt(0).text.toString()
        assertTrue(clip.startsWith("Clu performance report") && clip.contains("App: ") && clip.contains("versionCode") && clip.contains("Device: "))
        assertTrue(clip.contains("app_create_ms): p50 812 ms"))
    }
}
