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

/** Stage 1 screens on Android framework code: search sources in chat, memory suggestions, diary, search settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CluApp::class)
class Stage1UiTest {
    companion object {
        @JvmStatic @BeforeClass fun hook() { CluApp.platformFactory = { AndroidPlatform(it, PlainKeyVault) } }
    }

    private val app get() = RuntimeEnvironment.getApplication() as CluApp
    private fun idle(ms: Long = 50) { repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(ms / 10 + 1) } }
    private fun <T : Activity> launch(cls: Class<T>, intent: Intent? = null): T =
        (if (intent != null) Robolectric.buildActivity(cls, intent) else Robolectric.buildActivity(cls)).setup().get().also { idle() }
    private fun allViews(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else emptyList()
    private fun texts(a: Activity) = allViews(a.window.decorView).filterIsInstance<TextView>()

    @Test fun searchedAnswerShowsBadgeAndClickableSources() {
        val conv = app.currentConversation()
        app.core.conversations.addMessage(conv, Role.USER, "What's the latest Android version?")
        app.core.conversations.addMessage(conv, Role.ASSISTANT, "Android 16 is the latest stable release [1].", MessageMeta(tools = listOf("web.search"),
            sources = listOf(SourceRef("Android 16 release notes", "https://source.android.com/docs/whatsnew/android-16-release", "Android 16 is now available."))))
        app.core.conversations.addMessage(conv, Role.ASSISTANT, "Hello.", MessageMeta())
        val a = launch(MainActivity::class.java)
        val t = texts(a).map { it.text.toString() }
        assertTrue(t.contains("🔎 Searched the web · 1 source · On-device model"))
        assertTrue("an offline answer is labelled differently", t.contains("On-device model"))
        val src = texts(a).first { it.text.toString() == "[1] Android 16 release notes" }
        assertTrue(t.any { it.startsWith("source.android.com — Android 16 is now available.") })
        (src.parent as View).performClick()
        val started = shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://source.android.com/docs/whatsnew/android-16-release", started.dataString)
    }

    @Test fun memorySuggestionInChatIsSavedOnlyWhenApproved() {
        val conv = app.currentConversation()
        val p = app.core.proposals.propose(ProposalKind.OBSERVATION, "Forgets to drink water while working", "said so", conv)!!
        val a = launch(MainActivity::class.java)
        assertTrue(texts(a).any { it.text.toString() == "Forgets to drink water while working" })
        assertEquals(0L, app.core.memories.count())
        texts(a).first { it.text.toString() == "Save" }.performClick(); idle()
        assertEquals("Forgets to drink water while working", app.core.memories.list().single().content)
        assertNotEquals(ai.clu.core.memory.ProposalStatus.PENDING, app.core.proposals.get(p.id)!!.status)
    }

    @Test fun diaryEntryWrittenBrowsedAndKeptSeparateFromMemory() {
        val e = launch(DiaryEditActivity::class.java)
        val edits = allViews(e.window.decorView).filterIsInstance<EditText>()
        edits[0].setText("Hike"); edits[1].setText("Walked up Sinhagad with Riya.")
        allViews(e.window.decorView).filterIsInstance<Button>().first { it.text.toString() == "Save" }.performClick(); idle()
        val saved = app.core.diary.list().single()
        assertEquals("Hike", saved.title); assertEquals(app.core.diary.today(), saved.date)
        assertEquals("a diary entry is not a memory", 0L, app.core.memories.count())
        val d = launch(DiaryActivity::class.java)
        assertTrue(texts(d).any { it.text.toString() == "Walked up Sinhagad with Riya." })
        assertTrue(allViews(d.window.decorView).filterIsInstance<Button>().any { it.text.toString() == "Keep as memory" })
    }

    @Test fun searchSettingsAndKeyEntryAreReachable() {
        val s = launch(SettingsActivity::class.java)
        assertTrue(texts(s).any { it.text.toString() == "Search & internet" }); assertTrue(texts(s).any { it.text.toString() == "Memory & diary" })
        val tools = launch(SettingsCategoryActivity::class.java, Intent(app, SettingsCategoryActivity::class.java).putExtra("section", "tools"))
        val t = texts(tools).map { it.text.toString() }
        listOf("Search automatically", "Search provider", "SearXNG address", "Brave Search API key").forEach { k -> assertTrue(k, t.any { it.startsWith(k) }) }
        assertTrue(t.any { it.startsWith("No key saved.") })
    }
}
