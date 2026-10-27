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

/** Stage 2 screens on Android framework code: source status in chat, phone & tablet sync reachable, sync file controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CluApp::class)
class Stage2UiTest {
    companion object {
        @JvmStatic @BeforeClass fun hook() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } }
    }

    private val app get() = RuntimeEnvironment.getApplication() as CluApp
    private fun idle(ms: Long = 50) { repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(ms / 10 + 1) } }
    private fun <T : Activity> launch(cls: Class<T>, intent: Intent? = null): T =
        (if (intent != null) Robolectric.buildActivity(cls, intent) else Robolectric.buildActivity(cls)).setup().get().also { idle() }
    private fun allViews(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else emptyList()
    private fun texts(a: Activity) = allViews(a.window.decorView).filterIsInstance<TextView>()

    @Test fun sourceCardsSayWhetherThePageWasRead() {
        val conv = app.currentConversation()
        app.core.conversations.addMessage(conv, Role.USER, "When was Android 16 released?")
        app.core.conversations.addMessage(conv, Role.ASSISTANT, "On 10 June 2025 [1][2].", MessageMeta(tools = listOf("web.search"), sources = listOf(
            SourceRef("Android 16 is out", "https://open.example/a", "Released in June", opened = true, published = "2025-06-10", retrievedAt = 1_760_000_000_000L),
            SourceRef("Android 16 news", "https://blocked.example/b", "Google released it", note = "the site's robots.txt doesn't allow reading it"))))
        val t = texts(launch(MainActivity::class.java)).map { it.text.toString() }
        assertTrue(t.any { it.startsWith("Page read · published 2025-06-10 · retrieved ") })
        assertTrue(t.contains("Snippet only (the site's robots.txt doesn't allow reading it)"))
    }

    @Test fun phoneAndTabletSyncIsReachableWithSyncFiles() {
        val s = launch(SettingsActivity::class.java)
        val card = texts(s).first { it.text.toString() == "Phone & tablet" }
        (card.parent as View).performClick()
        assertEquals(DevicesActivity::class.java.name, shadowOf(s).nextStartedActivity.component!!.className)
        val d = launch(DevicesActivity::class.java)
        val buttons = allViews(d.window.decorView).filterIsInstance<Button>().map { it.text.toString() }
        assertTrue(buttons.containsAll(listOf("Export sync file", "Import sync file", "Sync now", "Pair with…")))
    }
}
